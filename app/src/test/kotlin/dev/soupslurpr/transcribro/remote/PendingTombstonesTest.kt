package dev.soupslurpr.transcribro.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingTombstonesTest {

    private val t0 = 1_757_900_000_000L
    private fun tombale(entendu: String, remplacerPar: String = "", depuisMs: Long = t0) =
        TombaleEnAttente(entendu, remplacerPar, depuisMs)

    @Test
    fun `un aller-retour conserve l ordre, les deux champs et la date`() {
        val file = listOf(tombale("hop hop", "OpOp"), tombale("été", "", t0 + 1), tombale("chuchotte", "chuchote", t0 + 2))
        assertEquals(file, PendingTombstones.decode(PendingTombstones.encode(file)))
    }

    @Test
    fun `une file absente vaut vide, une file illisible vaut null`() {
        assertEquals(emptyList<TombaleEnAttente>(), PendingTombstones.decode(null))
        assertEquals(emptyList<TombaleEnAttente>(), PendingTombstones.decode(""))
        assertNull(PendingTombstones.decode("pas du json"))
        assertNull(PendingTombstones.decode("""{"heard":"objet, pas tableau"}"""))
        assertEquals("[]", PendingTombstones.encode(emptyList()))
    }

    @Test
    fun `les elements muets ou en double sont ecartes et une date absente vaut zero`() {
        val raw = """[{"heard":"a","replace_with":"b","since":5},{"replace_with":"sans entendu"},7,{"heard":"a","replace_with":"b"},{"heard":"c"}]"""
        assertEquals(listOf(tombale("a", "b", 5), tombale("c", "", 0)), PendingTombstones.decode(raw))
    }

    @Test
    fun `ajouter date la paire, remplace un doublon et plafonne par les plus anciennes`() {
        val file = PendingTombstones.ajouter(listOf(tombale("a", "b", 1)), "a" to "b", 9)
        assertEquals(listOf(tombale("a", "b", 9)), file)

        var pleine = emptyList<TombaleEnAttente>()
        (1..PendingTombstones.PLAFOND + 2).forEach { i ->
            pleine = PendingTombstones.ajouter(pleine, "mot$i" to "", i.toLong())
        }
        assertEquals(PendingTombstones.PLAFOND, pleine.size)
        assertEquals("mot3", pleine.first().entendu)
        assertEquals("mot${PendingTombstones.PLAFOND + 2}", pleine.last().entendu)
    }

    @Test
    fun `le rejeu ecarte les paires vivantes et les perimees, et ne garde que ce qui n a pas ete envoye`() {
        val file = listOf(
            tombale("vivante", "x", t0),
            tombale("fraiche", "y", t0),
            tombale("perimee", "z", t0 - PendingTombstones.PEREMPTION_MS - 1),
            tombale("limite", "w", t0 - PendingTombstones.PEREMPTION_MS),
            tombale("autre", "v", t0),
        )
        val aRejouer = PendingTombstones.aRejouer(file, exclure = setOf("vivante" to "x"), maintenantMs = t0)
        assertEquals(listOf("fraiche", "limite", "autre"), aRejouer.map { it.entendu })

        // Le relais a accepté la première, la seconde a échoué : la file ne
        // garde que ce qui n'est pas parti, sans ressusciter vivante ni périmée.
        val restantes = PendingTombstones.restantes(aRejouer, envoyees = listOf(aRejouer[0]))
        assertEquals(listOf("limite", "autre"), restantes.map { it.entendu })
        assertTrue(PendingTombstones.retirer(restantes, "limite" to "w").none { it.entendu == "limite" })
    }

    @Test
    fun `le retrait des envoyees porte sur la file courante, a l identite paire plus date`() {
        val envoyee = tombale("a", "b", t0)
        // Pendant les envois : « c » a été supprimée, et « a »/« b » supprimée de
        // nouveau après avoir été réapprise (donc redatée). Les deux survivent.
        val courante = listOf(tombale("c", "", t0 + 5), tombale("a", "b", t0 + 9))
        assertEquals(courante, PendingTombstones.restantes(courante, listOf(envoyee)))
        assertEquals(emptyList<TombaleEnAttente>(), PendingTombstones.restantes(listOf(envoyee), listOf(envoyee)))
    }
}
