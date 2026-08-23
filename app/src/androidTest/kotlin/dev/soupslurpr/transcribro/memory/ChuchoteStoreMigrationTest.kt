package dev.soupslurpr.transcribro.memory

import android.content.ContentValues
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChuchoteStoreMigrationTest {

    @Test
    fun migrationV2ToV3PreservesExistingDictation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".qa")) {
            "Ce test destructif doit viser uniquement la variante QA"
        }
        context.deleteDatabase(DATABASE_NAME)
        createVersion2Database(context)

        val store = ChuchoteStore.get(context)
        val migrated = runBlocking {
            withTimeout(10_000) {
                store.dictees.filter { it.size == 1 }.first().single()
            }
        }

        assertEquals(42L, migrated.id)
        assertEquals("Ancienne dictée", migrated.texte)
        assertEquals("Ancienne dictée", migrated.texteBrut)
        assertEquals(EtatDictee.TERMINEE, migrated.etat)
        assertEquals("local", migrated.source)
        assertNull(migrated.cheminAudio)

        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { database ->
            assertEquals(3, database.version)
        }
    }

    private fun createVersion2Database(context: Context) {
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { database ->
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

    companion object {
        private const val DATABASE_NAME = "chuchote.db"
    }
}
