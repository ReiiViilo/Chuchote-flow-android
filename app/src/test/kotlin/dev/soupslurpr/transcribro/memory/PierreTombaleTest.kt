package dev.soupslurpr.transcribro.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PierreTombaleTest {

    private val entrees = listOf(
        EntreeDictionnaire(1, "hop hop", "OpOp"),
        EntreeDictionnaire(2, "hop hop", "OpOp"), // doublon de 1
        EntreeDictionnaire(3, " chuchotte ", "chuchote"),
        EntreeDictionnaire(4, "chuchotte", "chuchoté"), // même forme, autre remplacement : autre paire
    )

    @Test
    fun `la derniere ligne d une paire est annoncee, espaces retires`() {
        assertEquals("chuchotte" to "chuchote", PierreTombale.aAnnoncer(entrees, 3))
        assertEquals("chuchotte" to "chuchoté", PierreTombale.aAnnoncer(entrees, 4))
    }

    @Test
    fun `un doublon supprime n annonce rien tant que l autre ligne reste`() {
        assertNull(PierreTombale.aAnnoncer(entrees, 1))
        assertNull(PierreTombale.aAnnoncer(entrees, 2))
        // Le doublon parti, la ligne restante redevient la dernière.
        assertEquals("hop hop" to "OpOp", PierreTombale.aAnnoncer(entrees.filter { it.id != 2L }, 1))
    }

    @Test
    fun `une ligne inconnue n annonce rien`() {
        assertNull(PierreTombale.aAnnoncer(entrees, 99))
    }
}
