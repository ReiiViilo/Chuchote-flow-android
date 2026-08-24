package dev.soupslurpr.transcribro.memory

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.soupslurpr.transcribro.recognitionservice.audio.RecoverableWavFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ChuchoteStoreMigrationTest {
    @Test
    fun noOpStartupLoadsHistoryOnlyOnce() {
        val context = isolatedTestContext()
        val databaseName = uniqueDatabaseName("no-op-startup")
        var store: ChuchoteStore? = null
        var historyProjectionReads = 0
        try {
            createVersion2Database(context, databaseName)
            val openedStore = ChuchoteStore.openIsolatedForTesting(
                context = context,
                databaseName = databaseName,
                onHistoryProjectionRead = { historyProjectionReads++ },
            )
            store = openedStore
            runBlocking { openedStore.awaitInitializationForTesting() }

            assertEquals(1, historyProjectionReads)
        } finally {
            cleanupSandbox(context, databaseName, store)
        }
    }

    @Test
    fun migrationV2ToV3PreservesExistingDictation() {
        val context = isolatedTestContext()
        val databaseName = uniqueDatabaseName("migration")
        var store: ChuchoteStore? = null
        try {
            assertTrue(!context.getDatabasePath(databaseName).exists())
            createVersion2Database(context, databaseName)
            val openedStore = ChuchoteStore.openIsolatedForTesting(context, databaseName)
            store = openedStore
            runBlocking { openedStore.awaitInitializationForTesting() }
            val migrated = openedStore.dictees.value.single()
            assertEquals(42L, migrated.id)
            assertEquals("Ancienne dictée", migrated.texte)
            assertEquals("Ancienne dictée", migrated.texteBrut)
            assertEquals(EtatDictee.TERMINEE, migrated.etat)
            assertEquals("local", migrated.source)
            assertNull(migrated.cheminAudio)
            context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use {
                assertEquals(3, it.version)
            }
        } finally {
            cleanupSandbox(context, databaseName, store)
        }
    }

    @Test
    fun historicalRehabilitationPreservesRowAndWavAndNewWritesRemainAbsolute() {
        val context = isolatedTestContext()
        val databaseName = uniqueDatabaseName("audio-contract")
        val legacyAudio = File(
            context.filesDir,
            "dictations/legacy-${UUID.randomUUID()}.wav",
        ).canonicalFile
        val freshAudio = File(
            context.noBackupFilesDir,
            "dictations/fresh-${UUID.randomUUID()}.wav",
        ).canonicalFile
        var bootstrap: ChuchoteStore? = null
        var store: ChuchoteStore? = null
        try {
            createVersion2Database(context, databaseName)
            val bootstrapStore = ChuchoteStore.openIsolatedForTesting(context, databaseName)
            bootstrap = bootstrapStore
            runBlocking {
                bootstrapStore.awaitInitializationForTesting()
                bootstrapStore.closeIsolatedForTesting()
            }
            bootstrap = null

            legacyAudio.parentFile?.mkdirs()
            val writer = RecoverableWavFile.create(legacyAudio, sampleRate = 16_000)
            try {
                writer.append(ShortArray(16_000) { it.toShort() })
                writer.finalizeRecording()
            } finally {
                writer.close()
            }
            val bytesBefore = legacyAudio.readBytes()
            val modifiedBefore = legacyAudio.lastModified()
            val legacyFilesBefore = regularFilesUnder(File(context.filesDir, "dictations"))
            val preferredFilesBefore = regularFilesUnder(
                File(context.noBackupFilesDir, "dictations"),
            )
            assertEquals(listOf(legacyAudio.path), legacyFilesBefore)
            assertTrue(preferredFilesBefore.isEmpty())

            context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { database ->
                val updated = database.update(
                    "dictees",
                    ContentValues().apply {
                        put("texte", "Texte conservé")
                        put("raw_text", "Brut conservé")
                        put("audio_path", legacyAudio.path)
                        putNull("audio_duration_ms")
                        put("etat", EtatDictee.A_REESSAYER.valeurStockee)
                        put("error_code", "audio_missing")
                        put("tentatives", 7)
                    },
                    "id = ?",
                    arrayOf("42"),
                )
                assertEquals(1, updated)
            }

            val openedStore = ChuchoteStore.openIsolatedForTesting(context, databaseName)
            store = openedStore
            runBlocking { openedStore.awaitInitializationForTesting() }
            val rehabilitated = openedStore.dictees.value.single()
            assertEquals("Texte conservé", rehabilitated.texte)
            assertEquals("Brut conservé", rehabilitated.texteBrut)
            assertEquals(EtatDictee.A_REESSAYER, rehabilitated.etat)
            assertEquals(legacyAudio.path, rehabilitated.cheminAudio)
            assertEquals(7, rehabilitated.tentatives)
            assertEquals(1_000L, rehabilitated.dureeAudioMs)
            assertNull(rehabilitated.erreur)
            assertArrayEquals(bytesBefore, legacyAudio.readBytes())
            assertEquals(modifiedBefore, legacyAudio.lastModified())
            assertEquals(
                "La réhabilitation ne doit copier aucun fichier historique",
                legacyFilesBefore,
                regularFilesUnder(File(context.filesDir, "dictations")),
            )
            assertEquals(
                "La nouvelle racine doit rester vide avant une nouvelle capture",
                preferredFilesBefore,
                regularFilesUnder(File(context.noBackupFilesDir, "dictations")),
            )

            freshAudio.parentFile?.mkdirs()
            val freshId = runBlocking { openedStore.creerDicteeEnCours(freshAudio) }
            val fresh = runBlocking { openedStore.obtenirDictee(freshId) }
            assertNotNull(fresh)
            assertTrue(File(fresh?.cheminAudio.orEmpty()).isAbsolute)
            assertEquals(freshAudio.path, fresh?.cheminAudio)
        } finally {
            cleanupSandbox(context, databaseName, bootstrap, store)
        }
    }

    @Test
    fun rehabilitationFailureDoesNotBlockHistoryOrDictionary() {
        val context = isolatedTestContext()
        val databaseName = uniqueDatabaseName("rehabilitation-failure")
        val legacyAudio = File(
            context.filesDir,
            "dictations/failure-${UUID.randomUUID()}.wav",
        ).canonicalFile
        var bootstrap: ChuchoteStore? = null
        var store: ChuchoteStore? = null
        var pendingId = -1L
        try {
            createVersion2Database(context, databaseName)
            val bootstrapStore = ChuchoteStore.openIsolatedForTesting(context, databaseName)
            bootstrap = bootstrapStore
            runBlocking {
                bootstrapStore.awaitInitializationForTesting()
                bootstrapStore.closeIsolatedForTesting()
            }
            bootstrap = null

            val writer = RecoverableWavFile.create(legacyAudio, sampleRate = 16_000)
            try {
                writer.append(ShortArray(8_000) { it.toShort() })
                writer.finalizeRecording()
            } finally {
                writer.close()
            }
            context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { database ->
                assertEquals(
                    1,
                    database.update(
                        "dictees",
                        ContentValues().apply {
                            put("texte", "Historique disponible")
                            put("raw_text", "Historique brut")
                            put("audio_path", legacyAudio.path)
                            put("etat", EtatDictee.A_REESSAYER.valeurStockee)
                            put("error_code", "audio_missing")
                        },
                        "id = ?",
                        arrayOf("42"),
                    ),
                )
                database.insertOrThrow("dictionnaire", null, ContentValues().apply {
                    put("entendu", "shawi")
                    put("remplacer_par", "Shawinigan")
                })
                pendingId = database.insertOrThrow("dictees", null, ContentValues().apply {
                    put("texte", "Capture interrompue")
                    put("raw_text", "Capture interrompue brute")
                    put("cree_le", System.currentTimeMillis() - 60_000L)
                    put("etat", EtatDictee.ENREGISTREMENT.valeurStockee)
                })
                database.execSQL(
                    "CREATE TRIGGER fail_historical_rehabilitation " +
                            "BEFORE UPDATE OF error_code ON dictees " +
                            "WHEN OLD.error_code IN ('audio_missing', 'retry_audio_missing') " +
                            "BEGIN SELECT RAISE(ABORT, 'forced_rehabilitation_failure'); END"
                )
            }

            val openedStore = ChuchoteStore.openIsolatedForTesting(context, databaseName)
            store = openedStore
            runBlocking { openedStore.awaitInitializationForTesting() }

            val rowsById = openedStore.dictees.value.associateBy { it.id }
            val row = rowsById.getValue(42L)
            assertEquals("Historique disponible", row.texte)
            assertEquals("Historique brut", row.texteBrut)
            assertEquals("audio_missing", row.erreur)
            assertNull(row.dureeAudioMs)
            val dictionaryEntry = openedStore.dictionnaire.value.single()
            assertEquals("shawi", dictionaryEntry.entendu)
            assertEquals("Shawinigan", dictionaryEntry.remplacerPar)
            val recoveredPending = rowsById.getValue(pendingId)
            assertEquals(EtatDictee.A_REESSAYER, recoveredPending.etat)
            assertEquals("audio_missing", recoveredPending.erreur)
        } finally {
            cleanupSandbox(context, databaseName, bootstrap, store)
        }
    }

    @Test
    fun partialInterruptedRecoveryIsPublishedAndInitializationFailureIsRethrown() {
        val context = isolatedTestContext()
        val databaseName = uniqueDatabaseName("partial-recovery-failure")
        var bootstrap: ChuchoteStore? = null
        var store: ChuchoteStore? = null
        var firstPendingId = -1L
        var secondPendingId = -1L
        try {
            createVersion2Database(context, databaseName)
            val bootstrapStore = ChuchoteStore.openIsolatedForTesting(context, databaseName)
            bootstrap = bootstrapStore
            runBlocking {
                bootstrapStore.awaitInitializationForTesting()
                bootstrapStore.closeIsolatedForTesting()
            }
            bootstrap = null

            context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { database ->
                fun insertPending(label: String): Long = database.insertOrThrow(
                    "dictees",
                    null,
                    ContentValues().apply {
                        put("texte", label)
                        put("raw_text", "$label brut")
                        put("cree_le", System.currentTimeMillis() - 60_000L)
                        put("etat", EtatDictee.ENREGISTREMENT.valeurStockee)
                    },
                )
                firstPendingId = insertPending("Première capture interrompue")
                secondPendingId = insertPending("Seconde capture interrompue")
                database.execSQL(
                    "CREATE TRIGGER fail_second_pending_recovery " +
                            "BEFORE UPDATE OF etat ON dictees " +
                            "WHEN OLD.id = $secondPendingId " +
                            "BEGIN SELECT RAISE(ABORT, 'forced_second_recovery_failure'); END",
                )
            }

            val openedStore = ChuchoteStore.openIsolatedForTesting(context, databaseName)
            store = openedStore
            val failure = assertThrows(SQLiteException::class.java) {
                runBlocking { openedStore.awaitInitializationForTesting() }
            }
            assertTrue(failure.message.orEmpty().contains("forced_second_recovery_failure"))

            val rowsById = openedStore.dictees.value.associateBy { it.id }
            val recoveredFirst = rowsById.getValue(firstPendingId)
            assertEquals(EtatDictee.A_REESSAYER, recoveredFirst.etat)
            assertEquals("audio_missing", recoveredFirst.erreur)
            val failedSecond = rowsById.getValue(secondPendingId)
            assertEquals(EtatDictee.ENREGISTREMENT, failedSecond.etat)
            assertNull(failedSecond.erreur)
        } finally {
            cleanupSandbox(context, databaseName, bootstrap, store)
        }
    }

    @Test
    fun historicalRehabilitationCursorAdvancesPastInvalidFirstPage() {
        val context = isolatedTestContext()
        val databaseName = uniqueDatabaseName("rehabilitation-cursor")
        val validAudio = File(
            context.filesDir,
            "dictations/valid-after-first-page-${UUID.randomUUID()}.wav",
        ).canonicalFile
        val wrappedAudio = File(
            context.filesDir,
            "dictations/valid-after-wrap-${UUID.randomUUID()}.wav",
        ).canonicalFile
        var bootstrap: ChuchoteStore? = null
        var firstStore: ChuchoteStore? = null
        var secondStore: ChuchoteStore? = null
        var thirdStore: ChuchoteStore? = null
        var validId = -1L
        val invalidPathsById = linkedMapOf<Long, String>()
        try {
            createVersion2Database(context, databaseName)
            val bootstrapStore = ChuchoteStore.openIsolatedForTesting(context, databaseName)
            bootstrap = bootstrapStore
            runBlocking {
                bootstrapStore.awaitInitializationForTesting()
                bootstrapStore.closeIsolatedForTesting()
            }
            bootstrap = null

            val writer = RecoverableWavFile.create(validAudio, sampleRate = 16_000)
            try {
                writer.append(ShortArray(16_000) { it.toShort() })
                writer.finalizeRecording()
            } finally {
                writer.close()
            }

            context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { database ->
                assertEquals(1, database.delete("dictees", null, null))
                database.beginTransaction()
                try {
                    repeat(100) { index ->
                        val missingPath = File(
                            context.filesDir,
                            "dictations/permanently-missing-$index.wav",
                        ).canonicalPath
                        val invalidId = database.insertOrThrow(
                            "dictees",
                            null,
                            ContentValues().apply {
                                put("texte", "Invalide $index")
                                put("raw_text", "Invalide brut $index")
                                put("cree_le", System.currentTimeMillis())
                                put("audio_path", missingPath)
                                put("etat", EtatDictee.A_REESSAYER.valeurStockee)
                                put("error_code", "audio_missing")
                            },
                        )
                        invalidPathsById[invalidId] = missingPath
                    }
                    validId = database.insertOrThrow("dictees", null, ContentValues().apply {
                        put("texte", "Valide après cent invalides")
                        put("raw_text", "Valide après cent invalides brut")
                        put("cree_le", System.currentTimeMillis())
                        put("audio_path", validAudio.path)
                        put("etat", EtatDictee.A_REESSAYER.valeurStockee)
                        put("error_code", "audio_missing")
                    })
                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
                assertRehabilitationKeysetPlan(database)
            }

            val firstOpenedStore = ChuchoteStore.openIsolatedForTesting(context, databaseName)
            firstStore = firstOpenedStore
            runBlocking { firstOpenedStore.awaitInitializationForTesting() }
            val afterFirstPage = firstOpenedStore.dictees.value.single { it.id == validId }
            assertEquals("audio_missing", afterFirstPage.erreur)
            assertNull(afterFirstPage.dureeAudioMs)
            assertInvalidDiagnosticsUnchanged(
                firstOpenedStore.dictees.value,
                invalidPathsById,
            )
            runBlocking { firstOpenedStore.closeIsolatedForTesting() }
            firstStore = null

            val secondOpenedStore = ChuchoteStore.openIsolatedForTesting(context, databaseName)
            secondStore = secondOpenedStore
            runBlocking { secondOpenedStore.awaitInitializationForTesting() }
            val afterCursorAdvance = secondOpenedStore.dictees.value.single { it.id == validId }
            assertNull(afterCursorAdvance.erreur)
            assertEquals(1_000L, afterCursorAdvance.dureeAudioMs)
            assertInvalidDiagnosticsUnchanged(
                secondOpenedStore.dictees.value,
                invalidPathsById,
            )
            runBlocking { secondOpenedStore.closeIsolatedForTesting() }
            secondStore = null

            val wrapWriter = RecoverableWavFile.create(wrappedAudio, sampleRate = 16_000)
            try {
                wrapWriter.append(ShortArray(16_000) { it.toShort() })
                wrapWriter.finalizeRecording()
            } finally {
                wrapWriter.close()
            }
            val wrappedId = invalidPathsById.keys.first()
            context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { database ->
                assertEquals(
                    1,
                    database.update(
                        "dictees",
                        ContentValues().apply { put("audio_path", wrappedAudio.path) },
                        "id = ?",
                        arrayOf(wrappedId.toString()),
                    ),
                )
            }

            val thirdOpenedStore = ChuchoteStore.openIsolatedForTesting(context, databaseName)
            thirdStore = thirdOpenedStore
            runBlocking { thirdOpenedStore.awaitInitializationForTesting() }
            val afterWrap = thirdOpenedStore.dictees.value.single { it.id == wrappedId }
            assertEquals(wrappedAudio.path, afterWrap.cheminAudio)
            assertNull(afterWrap.erreur)
            assertEquals(1_000L, afterWrap.dureeAudioMs)
            assertInvalidDiagnosticsUnchanged(
                thirdOpenedStore.dictees.value,
                invalidPathsById - wrappedId,
            )
        } finally {
            cleanupSandbox(
                context,
                databaseName,
                bootstrap,
                firstStore,
                secondStore,
                thirdStore,
            )
        }
    }

    private fun isolatedTestContext(): IsolatedStoreContext {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(
            targetContext.cacheDir,
            "chuchote-store-tests/${UUID.randomUUID()}",
        ).canonicalFile
        check(root.mkdirs()) { "Impossible de créer le cache SQLite jetable" }
        return IsolatedStoreContext(targetContext, root)
    }

    private fun uniqueDatabaseName(label: String): String =
        "test-chuchote-$label-${UUID.randomUUID()}.db"

    private fun regularFilesUnder(root: File): List<String> =
        if (!root.exists()) {
            emptyList()
        } else {
            root.walkTopDown()
                .filter { it.isFile }
                .map { it.canonicalPath }
                .sorted()
                .toList()
        }

    private fun assertInvalidDiagnosticsUnchanged(
        rows: List<Dictee>,
        expectedPathsById: Map<Long, String>,
    ) {
        val rowsById = rows.associateBy { it.id }
        assertEquals(expectedPathsById.size, expectedPathsById.keys.count { it in rowsById })
        expectedPathsById.forEach { (id, expectedPath) ->
            val row = rowsById.getValue(id)
            assertEquals(expectedPath, row.cheminAudio)
            assertEquals(EtatDictee.A_REESSAYER, row.etat)
            assertEquals("audio_missing", row.erreur)
            assertNull(row.dureeAudioMs)
            assertEquals(0, row.tentatives)
        }
    }

    private fun assertRehabilitationKeysetPlan(database: SQLiteDatabase) {
        val indexNames = database.rawQuery("PRAGMA index_list('dictees')", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(1))
            }
        }
        assertTrue(REHABILITATION_INDEX in indexNames)

        listOf(">", "<=").forEach { comparison ->
            val planDetails = database.rawQuery(
                "EXPLAIN QUERY PLAN " +
                        "SELECT id, audio_path, error_code FROM dictees " +
                        "INDEXED BY $REHABILITATION_INDEX " +
                        "WHERE audio_path IS NOT NULL " +
                        "AND error_code IN ('audio_missing', 'retry_audio_missing') " +
                        "AND id $comparison ? ORDER BY id ASC LIMIT ?",
                arrayOf("0", "100"),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(3))
                }
            }
            assertTrue(planDetails.joinToString().contains(REHABILITATION_INDEX))
            assertTrue(planDetails.none { it.contains("TEMP B-TREE", ignoreCase = true) })
        }
    }

    private companion object {
        const val REHABILITATION_INDEX = "idx_dictees_historical_audio_errors"
    }

    private fun cleanupSandbox(
        context: IsolatedStoreContext,
        databaseName: String,
        vararg stores: ChuchoteStore?,
    ) {
        var firstFailure: Exception? = null
        fun attempt(cleanup: () -> Unit) {
            try {
                cleanup()
            } catch (error: Exception) {
                val previous = firstFailure
                if (previous == null) {
                    firstFailure = error
                } else {
                    previous.addSuppressed(error)
                }
            }
        }

        stores.filterNotNull().distinct().forEach { store ->
            attempt { runBlocking { store.closeIsolatedForTesting() } }
        }
        attempt {
            val databaseFile = context.getDatabasePath(databaseName)
            val deleted = context.deleteDatabase(databaseName)
            check(deleted || !databaseFile.exists()) {
                "La base SQLite jetable n'a pas été supprimée"
            }
        }
        attempt { context.clearSandbox() }
        firstFailure?.let { throw it }
    }

    private fun createVersion2Database(context: Context, databaseName: String) {
        val databaseDirectory = context.getDatabasePath(databaseName).parentFile
        checkNotNull(databaseDirectory)
        check(databaseDirectory.isDirectory || databaseDirectory.mkdirs()) {
            "Impossible de créer le dossier SQLite privé du paquet de test"
        }
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { database ->
            database.execSQL(
                "CREATE TABLE dictees (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "texte TEXT NOT NULL, " +
                        "cree_le INTEGER NOT NULL, " +
                        "duree_ms INTEGER, " +
                        "source TEXT)"
            )
            database.execSQL(
                "CREATE TABLE dictionnaire (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "entendu TEXT NOT NULL, " +
                        "remplacer_par TEXT NOT NULL DEFAULT '')"
            )
            database.insertOrThrow("dictees", null, ContentValues().apply {
                put("id", 42L)
                put("texte", "Ancienne dictée")
                put("cree_le", 1_700_000_000_000L)
                put("duree_ms", 2_500L)
                put("source", "local")
            })
            database.version = 2
        }
    }
}

private class IsolatedStoreContext(
    base: Context,
    private val sandboxRoot: File,
) : ContextWrapper(base) {
    override fun getApplicationContext(): Context = this

    override fun getCacheDir(): File = sandboxRoot

    override fun getFilesDir(): File = directory("files")

    override fun getNoBackupFilesDir(): File = directory("no_backup")

    override fun getDatabasePath(name: String): File =
        File(directory("databases"), name)

    override fun openOrCreateDatabase(
        name: String,
        mode: Int,
        factory: SQLiteDatabase.CursorFactory?,
    ): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(
        databasePathReady(name),
        factory,
    )

    override fun openOrCreateDatabase(
        name: String,
        mode: Int,
        factory: SQLiteDatabase.CursorFactory?,
        errorHandler: DatabaseErrorHandler?,
    ): SQLiteDatabase {
        val databasePath = databasePathReady(name)
        return if (errorHandler == null) {
            SQLiteDatabase.openOrCreateDatabase(databasePath, factory)
        } else {
            SQLiteDatabase.openDatabase(
                databasePath.path,
                factory,
                SQLiteDatabase.CREATE_IF_NECESSARY,
                errorHandler,
            )
        }
    }

    override fun deleteDatabase(name: String): Boolean =
        SQLiteDatabase.deleteDatabase(getDatabasePath(name))

    fun clearSandbox() {
        val root = sandboxRoot.canonicalFile
        val targetCache = baseContext.cacheDir.canonicalFile
        check(root != targetCache && root.toPath().startsWith(targetCache.toPath())) {
            "Refus de nettoyer un chemin hors du cache cible"
        }
        val deleted = root.deleteRecursively()
        check(deleted || !root.exists()) { "Le cache UUID jetable n'a pas été supprimé" }
        check(!root.exists()) { "Le cache UUID existe encore après le nettoyage" }
    }

    private fun databasePathReady(name: String): File =
        getDatabasePath(name).also { database ->
            check(database.parentFile?.isDirectory == true) {
                "Le dossier SQLite jetable n'existe pas"
            }
        }

    private fun directory(name: String): File = File(sandboxRoot, name).also { directory ->
        check(directory.isDirectory || directory.mkdirs()) {
            "Impossible de créer le dossier jetable ${directory.name}"
        }
    }
}
