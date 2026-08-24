package dev.soupslurpr.transcribro.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrectionDiffTest {
    @Test
    fun `proposes a correction for a single word`() {
        assertEquals(
            listOf(CorrectionDiff.Proposition("chichotte", "Chuchote")),
            CorrectionDiff.proposer("chichotte", "Chuchote"),
        )
    }

    @Test
    fun `proposes a correction for the final word`() {
        assertEquals(
            listOf(CorrectionDiff.Proposition("chichotte", "Chuchote")),
            CorrectionDiff.proposer("bonjour chichotte", "bonjour Chuchote"),
        )
    }

    @Test
    fun `proposes a correction between stable words`() {
        assertEquals(
            listOf(CorrectionDiff.Proposition("chichotte", "Chuchote")),
            CorrectionDiff.proposer("bonjour chichotte ami", "bonjour Chuchote ami"),
        )
    }

    @Test
    fun `ignores pure additions and deletions`() {
        assertTrue(CorrectionDiff.proposer("bonjour", "bonjour ami").isEmpty())
        assertTrue(CorrectionDiff.proposer("bonjour ami", "bonjour").isEmpty())
    }
}
