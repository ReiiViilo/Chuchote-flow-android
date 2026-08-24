package dev.soupslurpr.transcribro.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RecognizerCommandBoundaryTest {

    @Test
    fun `un echec stop reste dans le callback et declenche la recuperation UI`() {
        var recoveryCalls = 0

        val succeeded = RecognizerCommandBoundary.execute(
            command = { throw IllegalStateException("binder perdu") },
            onFailure = { recoveryCalls += 1 },
        )

        assertFalse(succeeded)
        assertEquals(1, recoveryCalls)
    }

    @Test
    fun `cancel et destroy sont tous deux tentes meme si chacun echoue`() {
        var cancelCalls = 0
        var destroyCalls = 0

        RecognizerCommandBoundary.cleanup(
            cancel = {
                cancelCalls += 1
                throw IllegalStateException("cancel")
            },
            destroy = {
                destroyCalls += 1
                throw IllegalStateException("destroy")
            },
        )

        assertEquals(1, cancelCalls)
        assertEquals(1, destroyCalls)
    }
}
