package dev.soupslurpr.transcribro.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteUploadGateTest {
    @Test
    fun `request body opens only while consent and coroutine are still active`() {
        assertTrue(
            RemoteUploadGate.canOpenRequestBody(
                consentAccepted = true,
                coroutineActive = true,
            ),
        )
        assertFalse(
            RemoteUploadGate.canOpenRequestBody(
                consentAccepted = false,
                coroutineActive = true,
            ),
        )
        assertFalse(
            RemoteUploadGate.canOpenRequestBody(
                consentAccepted = true,
                coroutineActive = false,
            ),
        )
    }
}
