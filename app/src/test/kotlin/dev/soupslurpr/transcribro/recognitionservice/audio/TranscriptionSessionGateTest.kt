package dev.soupslurpr.transcribro.recognitionservice.audio

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class TranscriptionSessionGateTest {

    @Test
    fun `aucun effet protege ne demarre apres annulation`() {
        val gate = TranscriptionSessionGate()
        var callbacks = 0

        assertEquals(true, gate.runIfActive { callbacks++ })
        gate.cancel()

        assertFalse(gate.runIfActive { callbacks++ })
        assertEquals(1, callbacks)
        assertThrows(CancellationException::class.java) {
            gate.ensureActive()
        }
    }
}
