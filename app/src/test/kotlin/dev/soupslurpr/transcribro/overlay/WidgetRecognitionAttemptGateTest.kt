package dev.soupslurpr.transcribro.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetRecognitionAttemptGateTest {
    @Test
    fun `a pending recognition attempt rejects a second start`() {
        val gate = WidgetRecognitionAttemptGate()

        val first = gate.begin()

        assertEquals(1L, first)
        assertNull(gate.begin())
        assertTrue(gate.isPending(first!!))
    }

    @Test
    fun `late callbacks from a cancelled attempt cannot affect its successor`() {
        val gate = WidgetRecognitionAttemptGate()
        val first = gate.begin()!!
        gate.cancel()
        val second = gate.begin()!!

        assertFalse(gate.fail(first))
        assertTrue(gate.isPending(second))
        assertTrue(gate.ready(second))
        assertFalse(gate.ready(first))
        assertTrue(gate.isRecording(second))
    }

    @Test
    fun `only the active recording can enter and complete transcription`() {
        val gate = WidgetRecognitionAttemptGate()
        val generation = gate.begin()!!

        assertFalse(gate.confirm(generation))
        assertTrue(gate.ready(generation))
        assertTrue(gate.confirm(generation))
        assertTrue(gate.isTranscribing(generation))
        assertFalse(gate.complete(generation + 1))
        assertTrue(gate.complete(generation))
        assertEquals(2L, gate.begin())
    }
}
