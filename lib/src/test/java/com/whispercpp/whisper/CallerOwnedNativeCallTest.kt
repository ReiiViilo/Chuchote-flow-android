package com.whispercpp.whisper

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class CallerOwnedNativeCallTest {

    @Test
    fun `a cancelled owner cannot enter a queued native call`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val blockerStarted = CountDownLatch(1)
        val unblockDispatcher = CountDownLatch(1)
        try {
            executor.submit {
                blockerStarted.countDown()
                unblockDispatcher.await()
            }
            blockerStarted.await()

            var nativeCalls = 0
            val callRequested = CompletableDeferred<Unit>()
            val owner = launch {
                callRequested.complete(Unit)
                runCatching {
                    CallerOwnedNativeCall.run(dispatcher) {
                        nativeCalls += 1
                    }
                }
            }

            callRequested.await()
            owner.cancel()
            unblockDispatcher.countDown()
            owner.join()

            assertEquals(0, nativeCalls)
        } finally {
            unblockDispatcher.countDown()
            dispatcher.close()
            executor.shutdownNow()
        }
    }
}
