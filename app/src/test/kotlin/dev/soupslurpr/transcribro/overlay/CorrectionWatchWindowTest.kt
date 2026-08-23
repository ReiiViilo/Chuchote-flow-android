package dev.soupslurpr.transcribro.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrectionWatchWindowTest {
    @Test
    fun `retains only insertion and short anchors from a long field`() {
        val prefix = "a".repeat(4_000)
        val suffix = "z".repeat(4_000)
        val fullText = prefix + "texte dicte" + suffix

        val window = CorrectionWatchWindow.create(
            fullText = fullText,
            insertionStart = prefix.length,
            insertionEnd = prefix.length + "texte dicte".length,
        )

        assertNotNull(window)
        assertEquals("texte dicte", window?.baseline)
        assertTrue(window!!.retainedCharacterCount <= "texte dicte".length + 64)
    }

    @Test
    fun `captures a length changing correction between stable anchors`() {
        val baseline = "Avant le mauvai mot apres"
        val window = CorrectionWatchWindow.create(baseline, 9, 19)

        assertEquals(
            "mauvais mot",
            window?.capture("Avant le mauvais mot apres"),
        )
    }

    @Test
    fun `rejects a changed left anchor`() {
        val baseline = "Avant le mauvai mot apres"
        val window = CorrectionWatchWindow.create(baseline, 9, 19)

        assertNull(window?.capture("Autour du mauvais mot apres"))
    }

    @Test
    fun `keeps surrogate pairs intact at anchor boundaries`() {
        val prefix = "a".repeat(31) + "😀"
        val baseline = prefix + "mot" + "👋" + "z".repeat(31)
        val window = CorrectionWatchWindow.create(
            baseline,
            prefix.length,
            prefix.length + 3,
        )

        assertEquals("mots", window?.capture(prefix + "mots" + "👋" + "z".repeat(31)))
    }

    @Test
    fun `rejects an unbounded replacement`() {
        val window = CorrectionWatchWindow.create("mot fin", 0, 3)

        assertNotNull(window)
        assertNull(
            window?.capture(
                "x".repeat(CorrectionWatchWindow.MAX_CAPTURE_CHARS + 1) + " fin",
            ),
        )
    }

    @Test
    fun `refuses learning without a right anchor at the end of a field`() {
        assertNull(CorrectionWatchWindow.create("mot", 0, 3))
    }
}
