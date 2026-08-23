package dev.soupslurpr.transcribro.memory

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import dev.soupslurpr.transcribro.recognitionservice.audio.AudioSegment
import dev.soupslurpr.transcribro.recognitionservice.audio.AudioSegmentCodec
import dev.soupslurpr.transcribro.recognitionservice.audio.RecoverableWavFile
import dev.soupslurpr.transcribro.recognitionservice.audio.TranscriptionSessionGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Une mutation durable ne redevient jamais un échec logique parce que la
 * projection mémoire a manqué de ressources après le commit SQLite.
 */
internal object DurableProjection {
    fun <T> commitThenRefresh(
        commit: () -> T,
        refresh: () -> Unit,
        onRefreshFailure: (Throwable) -> Unit = {},
    ): T {
        val committed = commit()
        refreshBestEffort(refresh, onRefreshFailure)
        return committed
    }

    fun refreshBestEffort(
        refresh: () -> Unit,
        onRefreshFailure: (Throwable) -> Unit = {},
    ) {
        try {
            refresh()
        } catch (error: Exception) {
            notifyRefreshFailure(error, onRefreshFailure)
        } catch (error: OutOfMemoryError) {
            notifyRefreshFailure(error, onRefreshFailure)
        }
    }

    private fun notifyRefreshFailure(
        error: Throwable,
        onRefreshFailure: (Throwable) -> Unit,
    ) {
        try {
            onRefreshFailure(error)
        } catch (_: Exception) {
            // Une projection et son diagnostic restent secondaires au commit.
        } catch (_: OutOfMemoryError) {
            // Éviter qu'un logger à court de mémoire réinterprète le commit.
        }
    }
}

/**
 * Les erreurs ordinaires peuvent signifier qu'un chemin ou un WAV historique
 * est invalide. Un manque de mémoire doit remonter sans produire le faux fait
 * durable « audio absent ».
 */
internal object StoreAudioResolution {
    fun <T> resolve(block: () -> T): T? = try {
        block()
    } catch (_: Exception) {
        null
    }
}

/**
 * Tente d'effacer le WAV et son journal `.part`, puis vérifie le disque.
 * La valeur de retour ne dépend jamais du booléen optimiste d'un `delete()`.
 */
internal fun deleteAudioPair(
    finalFile: File,
    deleteFile: (File) -> Boolean = { it.delete() },
): Boolean {
    val files = listOf(finalFile, File("${finalFile.path}.part"))
    files.filter { it.exists() }.forEach { file ->
        runCatching { deleteFile(file) }
    }
    return files.none { it.exists() }
}

enum class EtatDictee(val valeurStockee: String) {
    ENREGISTREMENT("recording"),
    EN_ATTENTE("queued"),
    TRANSCRIPTION("transcribing"),
    A_REESSAYER("retryable"),
    TERMINEE("completed");

    companion object {
        fun depuisValeurStockee(value: String?): EtatDictee =
            entries.firstOrNull { it.valeurStockee == value } ?: TERMINEE
    }
}

data class Dictee(
    val id: Long,
    val texte: String,
    /** Texte produit par le moteur avant les corrections du dictionnaire. */
    val texteBrut: String? = null,
    /** Moment de la dictée, en millisecondes depuis l'époque Unix. */
    val creeLe: Long,
    /** Délai vécu entre la validation et le texte, en millisecondes. */
    val dureeMs: Long? = null,
    /** Chemin de transcription : « relais », « local » ou « mixte ». */
    val source: String? = null,
    /** Fichier WAV privé, conservé pour permettre une nouvelle tentative. */
    val cheminAudio: String? = null,
    /** Durée réelle de l'enregistrement. */
    val dureeAudioMs: Long? = null,
    val etat: EtatDictee = EtatDictee.TERMINEE,
    val erreur: String? = null,
    val tentatives: Int = 0,
    val segments: List<AudioSegment> = emptyList(),
)

/** Une entrée du dictionnaire personnel. */
data class EntreeDictionnaire(
    val id: Long,
    val entendu: String,
    val remplacerPar: String,
)

/**
 * Mémoire locale de Chuchote Flow.
 *
 * Une ligne de dictée est maintenant créée avant le premier échantillon. Le
 * WAV est écrit dans le dossier privé de l'application et l'état de la ligne
 * suit tout le cycle de vie. Une mort de processus ne transforme donc plus
 * une longue dictée en donnée invisible.
 */
class ChuchoteStore private constructor(context: Context) {

    private val applicationContext = context.applicationContext
    private val db = Db(applicationContext)
    private val audioRoot = File(
        applicationContext.noBackupFilesDir,
        AUDIO_DIRECTORY,
    ).canonicalFile
    private val storeStartedAtMs = System.currentTimeMillis()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val dbDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dbDispatcher)

    private val _dictees = MutableStateFlow<List<Dictee>>(emptyList())
    val dictees: StateFlow<List<Dictee>> = _dictees.asStateFlow()

    private val _dictionnaire = MutableStateFlow<List<EntreeDictionnaire>>(emptyList())
    val dictionnaire: StateFlow<List<EntreeDictionnaire>> = _dictionnaire.asStateFlow()

    init {
        audioRoot.mkdirs()
        scope.launch {
            recupererDicteesInterrompues()
            rechargerDictees()
            rechargerDictionnaire()
        }
    }

    // ------------------------------------------------------------------
    // Historique et reprise
    // ------------------------------------------------------------------

    /** Crée la trace durable avant le démarrage effectif d'AudioRecord. */
    suspend fun creerDicteeEnCours(fichierAudio: File): Long = withContext(dbDispatcher) {
        val audio = exigerFichierAudioPrive(fichierAudio)
        DurableProjection.commitThenRefresh(
            commit = {
                db.writableDatabase.insertOrThrow("dictees", null, ContentValues().apply {
                    put("texte", "")
                    put("cree_le", System.currentTimeMillis())
                    put("audio_path", audio.path)
                    put("etat", EtatDictee.ENREGISTREMENT.valeurStockee)
                })
            },
            refresh = ::rechargerDictees,
            onRefreshFailure = ::journaliserEchecProjection,
        )
    }

    suspend fun marquerEnAttente(
        id: Long,
        dureeAudioMs: Long,
        segments: List<AudioSegment>,
    ): Boolean = withContext(dbDispatcher) {
        DurableProjection.commitThenRefresh(
            commit = {
                db.writableDatabase.update("dictees", ContentValues().apply {
                    put("audio_duration_ms", dureeAudioMs.coerceAtLeast(0))
                    put("segments", AudioSegmentCodec.encode(segments))
                    put("etat", EtatDictee.EN_ATTENTE.valeurStockee)
                    putNull("error_code")
                }, "id = ? AND etat IN (?, ?, ?)", arrayOf(
                    id.toString(),
                    EtatDictee.ENREGISTREMENT.valeurStockee,
                    EtatDictee.A_REESSAYER.valeurStockee,
                    EtatDictee.TERMINEE.valeurStockee,
                )) == 1
            },
            refresh = ::rechargerDictees,
            onRefreshFailure = ::journaliserEchecProjection,
        )
    }

    /** Une seule transition `queued -> transcribing` peut revendiquer la ligne. */
    suspend fun marquerTranscriptionEnCours(id: Long): Boolean = withContext(dbDispatcher) {
        val claimed = db.writableDatabase.compileStatement(
            "UPDATE dictees SET etat = ?, error_code = NULL, " +
                    "transcription_started_at = ?, tentatives = tentatives + 1 " +
                    "WHERE id = ? AND etat = ?",
        ).use { statement ->
            statement.bindString(1, EtatDictee.TRANSCRIPTION.valeurStockee)
            statement.bindLong(2, System.currentTimeMillis())
            statement.bindLong(3, id)
            statement.bindString(4, EtatDictee.EN_ATTENTE.valeurStockee)
            statement.executeUpdateDelete() == 1
        }
        if (claimed) actualiserDicteesApresMutation()
        claimed
    }

    suspend fun marquerTerminee(
        id: Long,
        texteBrut: String,
        texteCorrige: String,
        dureeMs: Long?,
        source: String?,
    ): Boolean = withContext(dbDispatcher) {
        val completed = ecrireDicteeTerminee(id, texteBrut, texteCorrige, dureeMs, source)
        if (completed) actualiserDicteesApresMutation()
        completed
    }

    /**
     * Écrit l'état terminal sous le même verrou que l'annulation Binder.
     * Retourne false si l'annulation a gagné la course.
     */
    internal suspend fun marquerTermineeSiActive(
        id: Long,
        texteBrut: String,
        texteCorrige: String,
        dureeMs: Long?,
        source: String?,
        sessionGate: TranscriptionSessionGate,
    ): Boolean = withContext(dbDispatcher) {
        var completed = false
        val active = sessionGate.runIfActive {
            completed = ecrireDicteeTerminee(id, texteBrut, texteCorrige, dureeMs, source)
            if (completed) actualiserDicteesApresMutation()
        }
        active && completed
    }

    private fun ecrireDicteeTerminee(
        id: Long,
        texteBrut: String,
        texteCorrige: String,
        dureeMs: Long?,
        source: String?,
    ): Boolean {
        return db.writableDatabase.update("dictees", ContentValues().apply {
            put("raw_text", texteBrut.trim())
            put("texte", texteCorrige.trim())
            dureeMs?.takeIf { it >= 0 }?.let { put("duree_ms", it) } ?: putNull("duree_ms")
            source?.let { put("source", it) } ?: putNull("source")
            put("etat", EtatDictee.TERMINEE.valeurStockee)
            putNull("error_code")
            put("transcription_ended_at", System.currentTimeMillis())
        }, "id = ? AND etat = ?", arrayOf(
            id.toString(),
            EtatDictee.TRANSCRIPTION.valeurStockee,
        )) == 1
    }

    suspend fun marquerAReessayer(
        id: Long,
        codeErreur: String,
        dureeAudioMs: Long? = null,
    ) = withContext(dbDispatcher) {
        val updated = db.writableDatabase.update("dictees", ContentValues().apply {
            put("etat", EtatDictee.A_REESSAYER.valeurStockee)
            put("error_code", codeErreur.take(MAX_ERROR_CODE_LENGTH))
            dureeAudioMs?.takeIf { it >= 0 }?.let { put("audio_duration_ms", it) }
            put("transcription_ended_at", System.currentTimeMillis())
        }, "id = ? AND etat != ?", arrayOf(
            id.toString(),
            EtatDictee.TERMINEE.valeurStockee,
        )) == 1
        if (updated) actualiserDicteesApresMutation()
    }

    /** Conserve le transcript terminé tout en rendant visible l'échec de reprise. */
    suspend fun marquerErreurRepriseTerminee(
        id: Long,
        codeErreur: String,
    ): Boolean = withContext(dbDispatcher) {
        val updated = db.writableDatabase.update("dictees", ContentValues().apply {
            put("error_code", codeErreur.take(MAX_ERROR_CODE_LENGTH))
        }, "id = ? AND etat = ?", arrayOf(
            id.toString(),
            EtatDictee.TERMINEE.valeurStockee,
        )) == 1
        if (updated) actualiserDicteesApresMutation()
        updated
    }

    suspend fun obtenirDictee(id: Long): Dictee? = withContext(dbDispatcher) {
        db.readableDatabase.rawQuery(
            "SELECT $DICTEE_COLUMNS FROM dictees WHERE id = ?",
            arrayOf(id.toString()),
        ).use { cursor ->
            if (cursor.moveToFirst()) lireDictee(cursor) else null
        }
    }

    /** Compatibilité avec les écrans qui ajoutent déjà une dictée terminée. */
    fun ajouterDictee(texte: String, dureeMs: Long? = null, source: String? = null) {
        val propre = texte.trim()
        if (propre.isEmpty()) return
        scope.launch {
            db.writableDatabase.insert("dictees", null, ContentValues().apply {
                put("texte", propre)
                put("raw_text", propre)
                put("cree_le", System.currentTimeMillis())
                dureeMs?.takeIf { it > 0 }?.let { put("duree_ms", it) }
                source?.let { put("source", it) }
                put("etat", EtatDictee.TERMINEE.valeurStockee)
            })
            actualiserDicteesApresMutation()
        }
    }

    fun supprimerDictee(id: Long) {
        scope.launch {
            val row = db.readableDatabase.rawQuery(
                "SELECT audio_path, etat FROM dictees WHERE id = ?",
                arrayOf(id.toString()),
            ).use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                cursor.stringOrNull(0) to EtatDictee.depuisValeurStockee(cursor.stringOrNull(1))
            }
            if (row == null || row.second !in DELETABLE_STATES) return@launch
            supprimerDicteeApresAudio(id, row.first)
            actualiserDicteesApresMutation()
        }
    }

    fun effacerHistorique() {
        scope.launch {
            val candidates = mutableListOf<Triple<Long, String?, EtatDictee>>()
            db.readableDatabase.rawQuery(
                "SELECT id, audio_path, etat FROM dictees WHERE " +
                        "etat IN ('completed', 'retryable')",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    candidates += Triple(
                        cursor.getLong(0),
                        cursor.stringOrNull(1),
                        EtatDictee.depuisValeurStockee(cursor.stringOrNull(2)),
                    )
                }
            }
            candidates.forEach { (id, path, state) ->
                if (state in DELETABLE_STATES) supprimerDicteeApresAudio(id, path)
            }
            actualiserDicteesApresMutation()
        }
    }

    // ------------------------------------------------------------------
    // Dictionnaire
    // ------------------------------------------------------------------

    fun ajouterEntree(entendu: String, remplacerPar: String) {
        val mot = entendu.trim()
        if (mot.isEmpty()) return
        scope.launch {
            db.writableDatabase.insert("dictionnaire", null, ContentValues().apply {
                put("entendu", mot)
                put("remplacer_par", remplacerPar.trim())
            })
            rechargerDictionnaire()
        }
    }

    fun supprimerEntree(id: Long) {
        scope.launch {
            db.writableDatabase.delete("dictionnaire", "id = ?", arrayOf(id.toString()))
            rechargerDictionnaire()
        }
    }

    fun appliquerCorrections(texte: String): String {
        var resultat = texte
        for (entree in _dictionnaire.value) {
            if (entree.remplacerPar.isEmpty()) continue
            val regex = Regex(
                "(?iu)(?<![\\p{L}\\p{N}])${Regex.escape(entree.entendu)}(?![\\p{L}\\p{N}])"
            )
            resultat = regex.replace(resultat) { correspondance ->
                val brut = entree.remplacerPar
                if (
                    correspondance.value.first().isUpperCase() &&
                    brut.firstOrNull()?.isLowerCase() == true
                ) {
                    brut.replaceFirstChar { it.uppercase() }
                } else {
                    brut
                }
            }
        }
        return resultat
    }

    fun motsPourBiais(): String {
        val mots = _dictionnaire.value
            .map { it.remplacerPar.ifEmpty { it.entendu } }
            .distinct()
        if (mots.isEmpty()) return ""
        return mots.joinToString(", ").take(MAX_BIAIS_CARACTERES)
    }

    // ------------------------------------------------------------------

    private fun rechargerDictees() {
        val liste = mutableListOf<Dictee>()
        db.readableDatabase.rawQuery(
            "SELECT $DICTEE_COLUMNS FROM dictees ORDER BY id DESC",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) liste += lireDictee(cursor)
        }
        _dictees.value = liste
    }

    private fun actualiserDicteesApresMutation() {
        DurableProjection.refreshBestEffort(
            refresh = ::rechargerDictees,
            onRefreshFailure = ::journaliserEchecProjection,
        )
    }

    private fun journaliserEchecProjection(error: Throwable) {
        Log.w(
            STORE_TAG,
            "Mutation SQLite confirmée; actualisation de l'historique différée",
            error,
        )
    }

    private fun lireDictee(cursor: Cursor): Dictee = Dictee(
        id = cursor.getLong(0),
        texte = cursor.getString(1),
        texteBrut = cursor.stringOrNull(2),
        creeLe = cursor.getLong(3),
        dureeMs = cursor.longOrNull(4),
        source = cursor.stringOrNull(5),
        cheminAudio = cursor.stringOrNull(6),
        dureeAudioMs = cursor.longOrNull(7),
        etat = EtatDictee.depuisValeurStockee(cursor.stringOrNull(8)),
        erreur = cursor.stringOrNull(9),
        tentatives = cursor.getInt(10),
        segments = AudioSegmentCodec.decode(cursor.stringOrNull(11)),
    )

    private fun rechargerDictionnaire() {
        val liste = mutableListOf<EntreeDictionnaire>()
        db.readableDatabase.rawQuery(
            "SELECT id, entendu, remplacer_par FROM dictionnaire ORDER BY entendu COLLATE NOCASE",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                liste += EntreeDictionnaire(
                    cursor.getLong(0),
                    cursor.getString(1),
                    cursor.getString(2),
                )
            }
        }
        _dictionnaire.value = liste
    }

    /** Les enregistrements interrompus deviennent visibles et relançables. */
    private fun recupererDicteesInterrompues() {
        val pendingStates = listOf(
            EtatDictee.ENREGISTREMENT,
            EtatDictee.EN_ATTENTE,
            EtatDictee.TRANSCRIPTION,
        ).joinToString(",") { "'${it.valeurStockee}'" }

        val pending = mutableListOf<Pair<Long, String?>>()
        db.readableDatabase.rawQuery(
            "SELECT id, audio_path FROM dictees WHERE etat IN ($pendingStates) AND cree_le < ?",
            arrayOf(storeStartedAtMs.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                pending += cursor.getLong(0) to cursor.stringOrNull(1)
            }
        }

        pending.forEach { (id, path) ->
            val audio = fichierAudioPriveOuNull(path)
            val recovered = audio?.let {
                StoreAudioResolution.resolve { RecoverableWavFile.recoverIfNeeded(it) }
            }
            val info = recovered?.let {
                StoreAudioResolution.resolve { RecoverableWavFile.inspect(it) }
            }
            val hasAudio = info != null && info.totalSamples > 0
            db.writableDatabase.update("dictees", ContentValues().apply {
                put("etat", EtatDictee.A_REESSAYER.valeurStockee)
                put("error_code", if (hasAudio) "process_interrupted" else "audio_missing")
                info?.let {
                    put("audio_duration_ms", it.totalSamples * 1000L / it.sampleRate)
                }
            }, "id = ?", arrayOf(id.toString()))
        }
    }

    private fun exigerFichierAudioPrive(file: File): File {
        val canonical = file.canonicalFile
        require(canonical.path.startsWith(audioRoot.path + File.separator)) {
            "Le fichier audio doit rester dans le stockage privé de Chuchote Flow"
        }
        require(canonical.extension.equals("wav", ignoreCase = true)) {
            "Le fichier audio doit être un WAV"
        }
        return canonical
    }

    private fun fichierAudioPriveOuNull(path: String?): File? {
        if (path.isNullOrBlank()) return null
        return StoreAudioResolution.resolve { exigerFichierAudioPrive(File(path)) }
    }

    private fun supprimerDicteeApresAudio(id: Long, path: String?) {
        val audio = fichierAudioPriveOuNull(path)
        val deletionConfirmed = when {
            path.isNullOrBlank() -> true
            audio == null -> invalidAudioPairConfirmedAbsent(path)
            else -> deleteAudioPair(audio)
        }

        if (deletionConfirmed) {
            db.writableDatabase.delete("dictees", "id = ?", arrayOf(id.toString()))
            return
        }

        db.writableDatabase.update("dictees", ContentValues().apply {
            put(
                "error_code",
                if (audio == null) "audio_path_invalid" else "audio_delete_failed",
            )
        }, "id = ?", arrayOf(id.toString()))
    }

    /** Compatibilité sûre : un ancien chemin invalide n'est jamais effacé. */
    private fun invalidAudioPairConfirmedAbsent(path: String): Boolean = runCatching {
        val finalFile = File(path).canonicalFile
        !finalFile.exists() && !File("${finalFile.path}.part").exists()
    }.getOrDefault(false)

    private class Db(context: Context) : SQLiteOpenHelper(
        context,
        DATABASE_NAME,
        null,
        DATABASE_VERSION,
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE dictees (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "texte TEXT NOT NULL, " +
                        "raw_text TEXT, " +
                        "cree_le INTEGER NOT NULL, " +
                        "duree_ms INTEGER, " +
                        "source TEXT, " +
                        "audio_path TEXT, " +
                        "audio_duration_ms INTEGER, " +
                        "etat TEXT NOT NULL DEFAULT 'completed', " +
                        "error_code TEXT, " +
                        "tentatives INTEGER NOT NULL DEFAULT 0, " +
                        "segments TEXT, " +
                        "transcription_started_at INTEGER, " +
                        "transcription_ended_at INTEGER)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX idx_dictees_audio_path ON dictees(audio_path) " +
                        "WHERE audio_path IS NOT NULL"
            )
            db.execSQL(
                "CREATE TABLE dictionnaire (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "entendu TEXT NOT NULL, " +
                        "remplacer_par TEXT NOT NULL DEFAULT '')"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE dictees ADD COLUMN duree_ms INTEGER")
                db.execSQL("ALTER TABLE dictees ADD COLUMN source TEXT")
            }
            if (oldVersion < 3) {
                db.execSQL("ALTER TABLE dictees ADD COLUMN raw_text TEXT")
                db.execSQL("ALTER TABLE dictees ADD COLUMN audio_path TEXT")
                db.execSQL("ALTER TABLE dictees ADD COLUMN audio_duration_ms INTEGER")
                db.execSQL("ALTER TABLE dictees ADD COLUMN etat TEXT NOT NULL DEFAULT 'completed'")
                db.execSQL("ALTER TABLE dictees ADD COLUMN error_code TEXT")
                db.execSQL("ALTER TABLE dictees ADD COLUMN tentatives INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE dictees ADD COLUMN segments TEXT")
                db.execSQL("ALTER TABLE dictees ADD COLUMN transcription_started_at INTEGER")
                db.execSQL("ALTER TABLE dictees ADD COLUMN transcription_ended_at INTEGER")
                // La v2 n'a jamais publié d'audio durable : aucune ancienne
                // ligne ne reçoit un chemin inventé pendant la migration.
                db.execSQL(
                    "UPDATE dictees SET raw_text = texte, audio_path = NULL, etat = 'completed'"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS idx_dictees_audio_path " +
                            "ON dictees(audio_path) WHERE audio_path IS NOT NULL"
                )
            }
        }
    }

    companion object {
        private const val DATABASE_NAME = "chuchote.db"
        private const val DATABASE_VERSION = 3
        private const val AUDIO_DIRECTORY = "dictations"
        private const val MAX_ERROR_CODE_LENGTH = 120
        private const val MAX_BIAIS_CARACTERES = 600
        private const val STORE_TAG = "ChuchoteStore"
        private const val DICTEE_COLUMNS =
            "id, texte, raw_text, cree_le, duree_ms, source, audio_path, " +
                    "audio_duration_ms, etat, error_code, tentatives, segments"
        private val DELETABLE_STATES = setOf(
            EtatDictee.A_REESSAYER,
            EtatDictee.TERMINEE,
        )

        @Volatile
        private var instance: ChuchoteStore? = null

        fun get(context: Context): ChuchoteStore =
            instance ?: synchronized(this) {
                instance ?: ChuchoteStore(context.applicationContext).also { instance = it }
            }
    }
}

private fun Cursor.stringOrNull(index: Int): String? =
    if (isNull(index)) null else getString(index)

private fun Cursor.longOrNull(index: Int): Long? =
    if (isNull(index)) null else getLong(index)
