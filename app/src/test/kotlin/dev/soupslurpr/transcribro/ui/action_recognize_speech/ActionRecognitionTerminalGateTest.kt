package dev.soupslurpr.transcribro.ui.action_recognize_speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionRecognitionTerminalGateTest {
    @Test
    fun `a terminal result is delivered only once`() {
        val gate = ActionRecognitionTerminalGate()

        val attempt = requireNotNull(gate.beginAttempt())

        assertTrue(gate.activate(attempt, "session-a"))
        assertTrue(gate.claimResult(attempt, "session-a"))
        assertFalse(gate.claimResult(attempt, "session-a"))
        assertFalse(gate.claimError(attempt))
    }

    @Test
    fun `generations increase monotonically between attempts`() {
        val gate = ActionRecognitionTerminalGate()

        val first = requireNotNull(gate.beginAttempt())
        assertTrue(gate.claimError(first))
        val second = requireNotNull(gate.beginAttempt())

        assertTrue(second > first)
    }

    @Test
    fun `late ready error and result callbacks cannot affect a newer attempt`() {
        val gate = ActionRecognitionTerminalGate()

        val first = requireNotNull(gate.beginAttempt())
        assertTrue(gate.activate(first, "session-a"))
        assertTrue(gate.claimError(first))

        val second = requireNotNull(gate.beginAttempt())
        assertFalse(gate.activate(first, "late-session-a"))
        assertFalse(gate.claimError(first))
        assertFalse(gate.claimResult(first, "session-a"))
        assertTrue(gate.activate(second, "session-b"))
        assertTrue(gate.claimResult(second, "session-b"))
    }

    @Test
    fun `a second start is rejected while an attempt is pending or active`() {
        val gate = ActionRecognitionTerminalGate()

        val attempt = requireNotNull(gate.beginAttempt())

        assertTrue(gate.beginAttempt() == null)
        assertTrue(gate.activate(attempt, "session-a"))
        assertTrue(gate.beginAttempt() == null)
    }

    @Test
    fun `a missing service identity fails closed`() {
        val gate = ActionRecognitionTerminalGate()

        val attempt = requireNotNull(gate.beginAttempt())

        assertFalse(gate.activate(attempt, null))
        assertFalse(gate.claimResult(attempt, null))
        assertTrue(gate.claimError(attempt))
    }

    @Test
    fun `revocation invalidates pending callback generation`() {
        val gate = ActionRecognitionTerminalGate()
        val attempt = requireNotNull(gate.beginAttempt())

        assertTrue(gate.claimCancellation(attempt))
        assertFalse(gate.activate(attempt, "late-session"))
        assertFalse(gate.claimError(attempt))
    }

    @Test
    fun `stop can only be requested once for an active attempt`() {
        val gate = ActionRecognitionTerminalGate()
        val attempt = requireNotNull(gate.beginAttempt())
        assertTrue(gate.activate(attempt, "session-a"))

        assertTrue(gate.requestStop(attempt))
        assertFalse(gate.requestStop(attempt))
        assertTrue(gate.claimResult(attempt, "session-a"))
    }
}
