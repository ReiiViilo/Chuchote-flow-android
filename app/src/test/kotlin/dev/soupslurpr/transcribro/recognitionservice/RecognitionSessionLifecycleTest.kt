package dev.soupslurpr.transcribro.recognitionservice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class RecognitionSessionLifecycleTest {
    @Test
    fun `a cancelling job remains busy until its finally completes`() = runBlocking {
        val finallyStarted = CompletableDeferred<Unit>()
        val releaseFinally = CompletableDeferred<Unit>()
        val previous = launch {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    finallyStarted.complete(Unit)
                    releaseFinally.await()
                }
            }
        }
        yield()

        previous.cancel()
        finallyStarted.await()
        assertFalse(RecognitionSessionLifecycle.canStart(previous))

        releaseFinally.complete(Unit)
        previous.join()
        assertTrue(RecognitionSessionLifecycle.canStart(previous))
    }

    @Test
    fun `a durable commit is remembered even when cancellation arrives before resume`() = runBlocking {
        val commitStarted = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        var rememberedId: Long? = null

        val owner = launch {
            DurableCommitBoundary.capture<Long>(
                commit = {
                    commitStarted.complete(Unit)
                    releaseCommit.await()
                    42L
                },
                remember = { rememberedId = it },
            )
        }

        commitStarted.await()
        owner.cancel()
        releaseCommit.complete(Unit)
        owner.join()

        assertEquals(42L, rememberedId)
        assertTrue(owner.isCancelled)
    }

    @Test
    fun `late completion from an old owner never clears its successor`() = runBlocking {
        data class Owner(val name: String, val job: kotlinx.coroutines.Job)

        val registry = ExclusiveSessionRegistry<Owner>()
        val oldJob = launch { }
        oldJob.join()
        val old = Owner("old", oldJob)
        val successorJob = launch { awaitCancellation() }
        val successor = Owner("successor", successorJob)

        assertTrue(registry.tryInstall(old) { current -> current.job.isCompleted })
        assertTrue(registry.tryInstall(successor) { current -> current.job.isCompleted })
        assertFalse(registry.clearIfOwned(old))
        assertSame(successor, registry.current())

        successorJob.cancelAndJoin()
    }

    @Test
    fun `revoked consent after publication prevents a lazy session from starting`() {
        assertFalse(
            RecognitionSessionStartPolicy.canStartAfterPublication(
                ownerStillPublished = true,
                consentStillAccepted = false,
                ownerAlreadyCancelled = false,
            ),
        )
        assertTrue(
            RecognitionSessionStartPolicy.canStartAfterPublication(
                ownerStillPublished = true,
                consentStillAccepted = true,
                ownerAlreadyCancelled = false,
            ),
        )
    }

    @Test
    fun `revocation before a lazy start releases its capture exactly once`() = runBlocking {
        val releases = AtomicInteger(0)
        val cleanup = IdempotentCleanup { releases.incrementAndGet() }
        val lazyOwner = launch(start = CoroutineStart.LAZY) {
            try {
                awaitCancellation()
            } finally {
                cleanup.run()
            }
        }
        lazyOwner.invokeOnCompletion { cleanup.run() }

        lazyOwner.cancel()
        lazyOwner.start()
        cleanup.run()
        lazyOwner.join()

        assertEquals(1, releases.get())
    }

    @Test
    fun `an uninitialized capture is released before validation fails`() {
        val releases = AtomicInteger(0)
        val cleanup = IdempotentCleanup { releases.incrementAndGet() }

        assertThrows(IllegalStateException::class.java) {
            AudioCaptureValidation.requireInitialized(
                initialized = false,
                cleanup = cleanup,
            )
        }
        cleanup.run()

        assertEquals(1, releases.get())
    }

    @Test
    fun `wav cleanup failures cannot prevent recovery persistence`() = runBlocking {
        var persisted: RecognitionRecoverySnapshot? = null
        var closeAttempted = false

        RecognitionRecoveryBoundary.finish(
            fallbackSamples = 73L,
            readSamples = { throw IOException("length") },
            finalizeAudio = { throw IOException("finalize") },
            closeAudio = {
                closeAttempted = true
                throw IOException("close")
            },
            persistRecovery = { snapshot -> persisted = snapshot },
        )

        assertTrue(closeAttempted)
        assertEquals(73L, persisted?.samplesWritten)
        assertTrue(persisted?.audioFinalizeFailed == true)
    }

    @Test
    fun `coordinator closes revoked lazy owner then accepts its successor`() = runBlocking {
        data class Owner(val name: String, val job: kotlinx.coroutines.Job)

        val coordinator = RecognitionSessionCoordinator<Owner> { it.job }
        var revokedOwnerStarted = false
        val revokedJob = launch(start = CoroutineStart.LAZY) {
            revokedOwnerStarted = true
        }
        val revoked = Owner("revoked", revokedJob)
        val releases = AtomicInteger(0)

        assertEquals(
            RecognitionSessionStartResult.CONSENT_REVOKED,
            coordinator.tryStart(
                candidate = revoked,
                consentAccepted = { false },
                release = { releases.incrementAndGet() },
                cancel = { owner, reason ->
                    owner.job.cancel(kotlinx.coroutines.CancellationException(reason))
                },
            ),
        )
        revokedJob.join()
        assertFalse(revokedOwnerStarted)
        assertEquals(1, releases.get())
        assertEquals(null, coordinator.current())

        val successorJob = launch(start = CoroutineStart.LAZY) { awaitCancellation() }
        val successor = Owner("successor", successorJob)
        assertEquals(
            RecognitionSessionStartResult.STARTED,
            coordinator.tryStart(
                candidate = successor,
                consentAccepted = { true },
                release = { releases.incrementAndGet() },
                cancel = { owner, reason ->
                    owner.job.cancel(kotlinx.coroutines.CancellationException(reason))
                },
            ),
        )
        assertSame(successor, coordinator.current())

        assertTrue(
            coordinator.cancelCurrent("test") { owner, reason ->
                owner.job.cancel(kotlinx.coroutines.CancellationException(reason))
            },
        )
        successorJob.join()
        assertEquals(2, releases.get())
        assertEquals(null, coordinator.current())
    }
}
