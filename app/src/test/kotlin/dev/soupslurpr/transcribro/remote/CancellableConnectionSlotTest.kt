package dev.soupslurpr.transcribro.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CancellableConnectionSlotTest {
    @Test
    fun `cancelling an attached connection disconnects it exactly once`() {
        val connection = TestConnection()
        val slot = CancellableConnectionSlot<TestConnection> { it.disconnect() }

        assertTrue(slot.attach(connection))
        slot.cancel()
        slot.close()

        assertTrue(connection.disconnected)
        assertTrue(connection.disconnectCalls == 1)
    }

    @Test
    fun `a connection attached after cancellation is immediately disconnected`() {
        val connection = TestConnection()
        val slot = CancellableConnectionSlot<TestConnection> { it.disconnect() }

        slot.cancel()

        assertFalse(slot.attach(connection))
        assertTrue(connection.disconnected)
        assertTrue(connection.disconnectCalls == 1)
    }

    private class TestConnection {
        var disconnected = false
            private set
        var disconnectCalls = 0
            private set

        fun disconnect() {
            disconnected = true
            disconnectCalls += 1
        }
    }
}
