package dev.soupslurpr.transcribro.recognitionservice.whisper

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class NativeResourceOwnerHandoffTest {

    @Test
    fun `cancellation while native creation finishes releases the unowned result once`() =
        runBlocking {
            val executor = Executors.newSingleThreadExecutor()
            val dispatcher = executor.asCoroutineDispatcher()
            try {
                val creationStarted = CompletableDeferred<Unit>()
                val finishCreation = CountDownLatch(1)
                val resource = FakeNativeResource()
                var published = false

                val ownerJob = launch {
                    runCatching {
                        NativeResourceOwnerHandoff.createAndPublish(
                            dispatcher = dispatcher,
                            create = {
                                creationStarted.complete(Unit)
                                finishCreation.await()
                                resource
                            },
                            publish = {
                                published = true
                                true
                            },
                            release = { it.release() },
                        )
                    }
                }

                creationStarted.await()
                ownerJob.cancel()
                finishCreation.countDown()
                ownerJob.join()

                assertFalse(published)
                assertEquals(1, resource.releaseCalls)
            } finally {
                dispatcher.close()
                executor.shutdownNow()
            }
        }

    @Test
    fun `published result remains owned until the repository releases it`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val resource = FakeNativeResource()
            var owned: FakeNativeResource? = null

            NativeResourceOwnerHandoff.createAndPublish(
                dispatcher = dispatcher,
                create = { resource },
                publish = {
                    owned = it
                    true
                },
                release = { it.release() },
            )

            assertSame(resource, owned)
            assertEquals(0, resource.releaseCalls)
            owned?.release()
            assertEquals(1, resource.releaseCalls)
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `a rejected publication releases the duplicate immediately`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val resource = FakeNativeResource()

            val published = NativeResourceOwnerHandoff.createAndPublish(
                dispatcher = dispatcher,
                create = { resource },
                publish = { false },
                release = { it.release() },
            )

            assertFalse(published)
            assertEquals(1, resource.releaseCalls)
            assertTrue(resource.released)
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    private class FakeNativeResource {
        var releaseCalls = 0
            private set
        val released: Boolean get() = releaseCalls > 0

        fun release() {
            releaseCalls += 1
        }
    }
}
