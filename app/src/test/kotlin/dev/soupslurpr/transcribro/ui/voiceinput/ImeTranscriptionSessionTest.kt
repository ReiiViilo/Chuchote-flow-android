package dev.soupslurpr.transcribro.ui.voiceinput

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeTranscriptionSessionTest {

    @Test
    fun `a pending attempt rejects a second start`() {
        val gate = ImeAttemptGate()
        val first = gate.begin()

        assertTrue(first != null)
        assertEquals(null, gate.begin())
        assertFalse(gate.requestStop(requireNotNull(first)))
        assertEquals(ImeAttemptState.PENDING, gate.currentState())
        assertTrue(gate.isBusy())
    }

    @Test
    fun `late callbacks from a cancelled attempt cannot affect the next one`() {
        val gate = ImeAttemptGate()
        val first = requireNotNull(gate.begin())
        gate.cancel()
        val second = requireNotNull(gate.begin())

        assertFalse(gate.activate(first))
        assertFalse(gate.finish(first))
        assertTrue(gate.activate(second))
        assertTrue(gate.accepts(second))
    }

    @Test
    fun `a terminal attempt is accepted only once`() {
        val gate = ImeAttemptGate()
        val attempt = requireNotNull(gate.begin())
        assertTrue(gate.activate(attempt))
        assertTrue(gate.finish(attempt))
        assertFalse(gate.finish(attempt))
        assertFalse(gate.accepts(attempt))
    }

    @Test
    fun `stop qui leve revient a idle et permet une deuxieme dictee`() {
        val gate = ImeAttemptGate()
        val first = requireNotNull(gate.begin())
        assertTrue(gate.activate(first))
        var cleanupCalled = false

        assertFalse(
            ImeStopCommandBoundary.requestStop(
                gate = gate,
                attempt = first,
                stop = { throw IllegalStateException("binder") },
                onFailure = { cleanupCalled = true },
            ),
        )

        assertTrue(cleanupCalled)
        assertEquals(ImeAttemptState.IDLE, gate.currentState())
        assertTrue(gate.begin() != null)
    }

    @Test
    fun `auto send requires a successful commit and a current editor`() {
        val update = ImeTranscriptionUpdate("Bonjour", false, true, true)

        assertFalse(ImeCommitDecision.shouldAutoSend(update, false, true, true))
        assertFalse(ImeCommitDecision.shouldAutoSend(update, true, false, true))
        assertFalse(ImeCommitDecision.shouldAutoSend(update, true, true, false))
        assertTrue(ImeCommitDecision.shouldAutoSend(update, true, true, true))
    }

    @Test
    fun `auto switch requires the same terminal commit editor and consent proof`() {
        val terminal = ImeTranscriptionUpdate("Bonjour", false, true, false)
        val empty = terminal.copy(textToCommit = "")
        val partial = terminal.copy(terminal = false)

        assertFalse(ImeCommitDecision.shouldAutoSwitch(terminal, false, true, true, true))
        assertFalse(ImeCommitDecision.shouldAutoSwitch(empty, true, true, true, true))
        assertFalse(ImeCommitDecision.shouldAutoSwitch(partial, true, true, true, true))
        assertFalse(ImeCommitDecision.shouldAutoSwitch(terminal, true, false, true, true))
        assertFalse(ImeCommitDecision.shouldAutoSwitch(terminal, true, true, false, true))
        assertFalse(ImeCommitDecision.shouldAutoSwitch(terminal, true, true, true, false))
        assertTrue(ImeCommitDecision.shouldAutoSwitch(terminal, true, true, true, true))
    }

    @Test
    fun `a current final with a missing or invalid session identity terminates fail closed`() {
        assertEquals(
            ImeFinalIdentityDecision.TERMINATE_FAIL_CLOSED,
            ImeFinalIdentityDecision.fromSessionCompletion(false),
        )
        assertEquals(
            ImeFinalIdentityDecision.PROCESS,
            ImeFinalIdentityDecision.fromSessionCompletion(true),
        )
    }

    @Test
    fun `les partiels ne modifient pas le champ et le final remplace une seule fois`() {
        val session = ImeTranscriptionSession()

        val firstSegment = session.onPartial(
            cumulativeText = "Bonjour",
            selectionActive = true,
        )
        val secondSegment = session.onPartial(
            cumulativeText = "Bonjour le monde",
            // Simule même un éditeur qui signalerait encore une sélection.
            selectionActive = true,
        )
        val final = session.onFinal(
            cumulativeText = "Bonjour le monde",
            selectionActive = true,
            autoSendEnabled = true,
        )
        val duplicateFinal = session.onFinal(
            cumulativeText = "Bonjour le monde",
            selectionActive = false,
            autoSendEnabled = true,
        )

        assertEquals("", firstSegment.textToCommit)
        assertFalse(firstSegment.replaceSelection)
        assertFalse(firstSegment.terminal)
        assertFalse(firstSegment.shouldAutoSend)

        assertEquals("", secondSegment.textToCommit)
        assertFalse(secondSegment.replaceSelection)
        assertFalse(secondSegment.terminal)
        assertFalse(secondSegment.shouldAutoSend)

        assertEquals("Bonjour le monde", final.textToCommit)
        assertTrue(final.replaceSelection)
        assertTrue(final.terminal)
        assertTrue(final.shouldAutoSend)
        assertEquals("", duplicateFinal.textToCommit)
        assertFalse(duplicateFinal.terminal)
        assertFalse(duplicateFinal.shouldAutoSend)
        assertEquals(
            "Bonjour le monde",
            final.textToCommit,
        )
    }

    @Test
    fun `un final revise apres un partiel est insere en entier`() {
        val session = ImeTranscriptionSession()
        session.onPartial("texte stable", selectionActive = false)

        val final = session.onFinal(
            cumulativeText = "texte revise",
            selectionActive = false,
            autoSendEnabled = true,
        )

        assertEquals("texte revise", final.textToCommit)
        assertTrue(final.shouldAutoSend)
    }
}
