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

    @Test
    fun `fin de champ - continuer d ecrire ne propose rien, corriger le dernier mot propose`() {
        // Le scénario que la fenêtre laisse désormais passer jusqu'au diff.
        assertTrue(
            CorrectionDiff.proposer(
                "envoie le rapport",
                "envoie le rapport et ajoute le budget",
            ).isEmpty(),
        )
        assertEquals(
            listOf(CorrectionDiff.Proposition("rappor", "rapport")),
            CorrectionDiff.proposer("envoie le rappor", "envoie le rapport"),
        )
    }
}
