package dev.soupslurpr.transcribro.recognitionservice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class TranscriptionRuntimeGateTest {

    @Test
    fun `une attente du moteur ne demarre plus apres retrait du consentement`() = runBlocking {
        val gate = ConsentAwareTranscriptionGate()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val consentAccepted = AtomicBoolean(true)
        val repositoryInvocations = AtomicInteger(0)

        val first = async(Dispatchers.Default) {
            gate.run(
                consentAccepted = { consentAccepted.get() },
                transcribe = {
                    repositoryInvocations.incrementAndGet()
                    firstEntered.complete(Unit)
                    releaseFirst.await()
                    "premiere"
                },
            )
        }
        firstEntered.await()

        val second = async(Dispatchers.Default) {
            gate.run(
                consentAccepted = { consentAccepted.get() },
                transcribe = {
                    repositoryInvocations.incrementAndGet()
                    "seconde"
                },
            )
        }
        yield()
        consentAccepted.set(false)
        releaseFirst.complete(Unit)

        assertEquals("premiere", first.await())
        assertThrows(CancellationException::class.java) {
            runBlocking { second.await() }
        }
        assertEquals(1, repositoryInvocations.get())
    }
}
