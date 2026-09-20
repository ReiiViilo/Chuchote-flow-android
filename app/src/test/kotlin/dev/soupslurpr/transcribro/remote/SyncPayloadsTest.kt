package dev.soupslurpr.transcribro.remote

import dev.soupslurpr.transcribro.memory.EntreeDictionnaire
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrat inter-dépôts avec `Chuchote-Flow/server/app/api/sync/` : le relais
 * lit `heard`, `replace_with`, `deleted` pour le dictionnaire, et `device`,
 * `device_local_id`, `created_at`, `final_text` (obligatoires) pour une dictée.
 */
class SyncPayloadsTest {

    private companion object {
        const val RELAIS = "https://relais.test"
    }


    private fun entree(id: Long, entendu: String, remplacerPar: String = "") =
        EntreeDictionnaire(id, entendu, remplacerPar)

    @Test
    fun `une dictee porte les champs que le relais exige`() {
        val payload = SyncPayloads.dictation(
            installationId = "inst",
            localId = 42,
            createdAtMs = 1_757_900_000_000,
            rawText = "hop hop conseil",
            finalText = "OpOp conseil",
            durationMs = 1234,
            source = "relais",
        )
        val dictee = payload.getJSONArray("dictations").getJSONObject(0)
        assertEquals("android", dictee.getString("device"))
        assertEquals("inst:42", dictee.getString("device_local_id"))
        assertEquals("2025-09-15T01:33:20Z", dictee.getString("created_at"))
        assertEquals("hop hop conseil", dictee.getString("raw_text"))
        assertEquals("OpOp conseil", dictee.getString("final_text"))
        assertEquals(1234, dictee.getInt("duration_ms"))
        assertEquals("relais", dictee.getString("source"))
    }

    @Test
    fun `les champs facultatifs absents partent en null explicite`() {
        val dictee = SyncPayloads.dictation("inst", 1, 0, null, "texte", null, null)
            .getJSONArray("dictations").getJSONObject(0)
        assertTrue(dictee.isNull("raw_text"))
        assertTrue(dictee.isNull("duration_ms"))
        assertTrue(dictee.isNull("source"))
    }

    @Test
    fun `sans identifiant d installation le numero de ligne part tel quel`() {
        val dictee = SyncPayloads.dictation("", 7, 0, null, "texte", null, null)
            .getJSONArray("dictations").getJSONObject(0)
        assertEquals("7", dictee.getString("device_local_id"))
    }

    @Test
    fun `un ajout et une pierre tombale portent la meme cle avec deleted oppose`() {
        val ajout = SyncPayloads.dictionaryEntry(" hop hop ", " OpOp ")
            .getJSONArray("entries").getJSONObject(0)
        val retrait = SyncPayloads.dictionaryTombstone(" hop hop ", " OpOp ")
            .getJSONArray("entries").getJSONObject(0)
        assertEquals("hop hop", ajout.getString("heard"))
        assertEquals("OpOp", ajout.getString("replace_with"))
        assertFalse(ajout.getBoolean("deleted"))
        assertEquals("hop hop", retrait.getString("heard"))
        assertEquals("OpOp", retrait.getString("replace_with"))
        assertTrue(retrait.getBoolean("deleted"))
    }

    @Test
    fun `la republication ignore les entrees vides et decoupe en lots de 500`() {
        assertTrue(SyncPayloads.dictionaryBatches(listOf(entree(1, "  "))).isEmpty())

        val petit = SyncPayloads.dictionaryBatches(listOf(entree(1, "a", "b"), entree(2, " ")))
        assertEquals(1, petit.size)
        val entries = petit[0].getJSONArray("entries")
        assertEquals(1, entries.length())
        assertFalse(entries.getJSONObject(0).getBoolean("deleted"))

        // 501 entrées : deux lots, rien n'est laissé de côté.
        val trop = (1..SyncPayloads.MAX_DICTIONARY_ENTRIES_PER_REQUEST + 1)
            .map { entree(it.toLong(), "mot$it", "x") }
        val lots = SyncPayloads.dictionaryBatches(trop)
        assertEquals(2, lots.size)
        assertEquals(SyncPayloads.MAX_DICTIONARY_ENTRIES_PER_REQUEST, lots[0].getJSONArray("entries").length())
        assertEquals(1, lots[1].getJSONArray("entries").length())
        assertEquals("mot501", lots[1].getJSONArray("entries").getJSONObject(0).getString("heard"))
    }

    @Test
    fun `la signature ne depend ni de l ordre ni des espaces mais du contenu`() {
        val a = SyncPayloads.dictionarySignature(listOf(entree(1, "hop hop", "OpOp"), entree(2, "Flow")), RELAIS)
        val b = SyncPayloads.dictionarySignature(listOf(entree(9, "Flow "), entree(3, " hop hop", "OpOp ")), RELAIS)
        val c = SyncPayloads.dictionarySignature(listOf(entree(1, "hop hop", "OpOp")), RELAIS)
        assertEquals(a, b)
        assertNotEquals(a, c)
        assertEquals(64, a.length)
    }

    @Test
    fun `la signature change avec le relais, pas avec une barre oblique finale`() {
        val entrees = listOf(entree(1, "hop hop", "OpOp"))
        val a = SyncPayloads.dictionarySignature(entrees, "https://a.test")
        val b = SyncPayloads.dictionarySignature(entrees, "https://b.test")
        assertNotEquals(a, b)
        assertEquals(a, SyncPayloads.dictionarySignature(entrees, "https://a.test/"))
    }

    @Test
    fun `la signature distingue ou tombe la frontiere entre entendu et remplacement`() {
        // Un séparateur espace laisserait ces deux dictionnaires se confondre,
        // et le second ne serait jamais republié après le premier.
        val gauche = SyncPayloads.dictionarySignature(listOf(entree(1, "a b", "c")), RELAIS)
        val droite = SyncPayloads.dictionarySignature(listOf(entree(1, "a", "b c")), RELAIS)
        assertNotEquals(gauche, droite)
    }

    @Test
    fun `le json final est celui que le relais recevra`() {
        val texte = SyncPayloads.dictionaryTombstone("été", "Été").toString()
        val relu = JSONObject(texte).getJSONArray("entries").getJSONObject(0)
        assertEquals("été", relu.getString("heard"))
        assertTrue(relu.getBoolean("deleted"))
    }
}
