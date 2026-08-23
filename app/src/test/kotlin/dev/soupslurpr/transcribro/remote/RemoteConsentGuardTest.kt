package dev.soupslurpr.transcribro.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class RemoteConsentGuardTest {
    @Test
    fun `revocation before upload start cancels without invoking upload`() = runBlocking {
        val uploadStarted = AtomicBoolean(false)

        val error = runCatching {
            RemoteConsentGuard.run(
                consent = flowOf(false),
                upload = {
                    uploadStarted.set(true)
                    "unexpected"
                },
            )
        }.exceptionOrNull()

        assertTrue(error is RemoteConsentRevokedException)
        assertFalse(uploadStarted.get())
    }

    @Test
    fun `revocation during upload cancels child and propagates cancellation`() = runBlocking {
        val consent = MutableStateFlow(true)
        val uploadStarted = CompletableDeferred<Unit>()
        val uploadCancelled = CompletableDeferred<Unit>()

        val outcome = async {
            runCatching {
                RemoteConsentGuard.run(
                    consent = consent,
                    upload = {
                        uploadStarted.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            uploadCancelled.complete(Unit)
                        }
                    },
                )
            }
        }

        uploadStarted.await()
        consent.value = false

        val error = outcome.await().exceptionOrNull()
        uploadCancelled.await()
        assertTrue(error is RemoteConsentRevokedException)
        assertTrue(error is CancellationException)
        assertEquals(0, consent.subscriptionCount.value)
    }

    @Test
    fun `revocation reaches blocking connection cancellation handler exactly once`() = runBlocking {
        val consent = MutableStateFlow(true)
        val connection = TestConnection()
        val connectionSlot = CancellableConnectionSlot<TestConnection> { it.disconnect() }
        val connectionAttached = CompletableDeferred<Unit>()

        val outcome = async {
            runCatching {
                RemoteConsentGuard.run(
                    consent = consent,
                    upload = {
                        suspendCancellableCoroutine<Unit> { continuation ->
                            continuation.invokeOnCancellation { connectionSlot.cancel() }
                            assertTrue(connectionSlot.attach(connection))
                            connectionAttached.complete(Unit)
                        }
                    },
                )
            }
        }

        connectionAttached.await()
        consent.value = false

        assertTrue(outcome.await().exceptionOrNull() is RemoteConsentRevokedException)
        connectionSlot.close()
        assertEquals(1, connection.disconnectCalls)
    }

    @Test
    fun `successful upload returns value and always stops consent observer`() = runBlocking {
        val observerStopped = CompletableDeferred<Unit>()
        val expected = Any()
        val consent = flow {
            try {
                emit(true)
                awaitCancellation()
            } finally {
                observerStopped.complete(Unit)
            }
        }

        val actual = RemoteConsentGuard.run(
            consent = consent,
            upload = { expected },
        )

        observerStopped.await()
        assertSame(expected, actual)
    }

    @Test
    fun `loss of consent observation fails closed while upload is active`() = runBlocking {
        val uploadStarted = CompletableDeferred<Unit>()

        val consent = flow {
            emit(true)
            uploadStarted.await()
        }
        val error = runCatching {
            RemoteConsentGuard.run(
                consent = consent,
                upload = {
                    uploadStarted.complete(Unit)
                    awaitCancellation()
                },
            )
        }.exceptionOrNull()

        assertTrue(uploadStarted.isCompleted)
        assertTrue(error is RemoteConsentRevokedException)
    }

    @Test
    fun `revocation after completed upload cannot replace completed result`() = runBlocking {
        val consent = MutableStateFlow(true)
        val uploadCompleted = CompletableDeferred<Unit>()

        val result = RemoteConsentGuard.run(
            consent = consent,
            upload = {
                uploadCompleted.complete(Unit)
                "transcription"
            },
        )

        uploadCompleted.await()
        consent.value = false
        assertEquals("transcription", result)
    }

    private class TestConnection {
        var disconnectCalls: Int = 0
            private set

        fun disconnect() {
            disconnectCalls += 1
        }
    }
}
