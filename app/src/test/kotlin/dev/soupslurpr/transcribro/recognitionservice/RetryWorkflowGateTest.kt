package dev.soupslurpr.transcribro.recognitionservice

import dev.soupslurpr.transcribro.memory.EtatDictee
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class RetryWorkflowGateTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `un audio termine peut etre retranscrit avec confirmation`() {
        assertTrue(
            RetryTranscriptionPolicy.canRetry(
                state = EtatDictee.TERMINEE,
                audioPath = "/private/dictation.wav",
            ),
        )
        assertTrue(
            RetryTranscriptionPolicy.requiresConfirmation(
                state = EtatDictee.TERMINEE,
                transcript = "Texte deja transcrit",
            ),
        )
    }

    @Test
    fun `une interruption avec audio reste relancable sans confirmation`() {
        assertTrue(
            RetryTranscriptionPolicy.canRetry(
                state = EtatDictee.A_REESSAYER,
                audioPath = "/private/dictation.wav",
            ),
        )
        assertFalse(
            RetryTranscriptionPolicy.requiresConfirmation(
                state = EtatDictee.A_REESSAYER,
                transcript = "",
            ),
        )
    }

    @Test
    fun `un etat actif ou un chemin audio absent ne peut pas etre relance`() {
        assertFalse(
            RetryTranscriptionPolicy.canRetry(
                state = EtatDictee.TRANSCRIPTION,
                audioPath = "/private/dictation.wav",
            ),
        )
        assertFalse(
            RetryTranscriptionPolicy.canRetry(
                state = EtatDictee.TERMINEE,
                audioPath = null,
            ),
        )
    }

    @Test
    fun `un audio confirme absent ou invalide ne propose plus une relance`() {
        assertFalse(
            RetryTranscriptionPolicy.canRetry(
                state = EtatDictee.TERMINEE,
                audioPath = "/private/dictation.wav",
                errorCode = "retry_audio_missing",
            ),
        )
        assertFalse(
            RetryTranscriptionPolicy.canRetry(
                state = EtatDictee.TERMINEE,
                audioPath = "/private/dictation.wav",
                errorCode = "retry_audio_invalid",
            ),
        )
        assertFalse(
            RetryTranscriptionPolicy.canRetry(
                state = EtatDictee.A_REESSAYER,
                audioPath = "/private/dictation.wav",
                errorCode = "empty_audio",
            ),
        )
        assertFalse(
            RetryTranscriptionPolicy.canRetry(
                state = EtatDictee.A_REESSAYER,
                audioPath = "/private/dictation.wav",
                errorCode = "audio_missing",
            ),
        )
    }

    @Test
    fun `les workflows restent logiquement sequentiels pendant les suspensions`() = runBlocking {
        val gate = RetryWorkflowGate()
        val start = CompletableDeferred<Unit>()
        val logicalActive = AtomicInteger(0)
        val maximumLogicalActive = AtomicInteger(0)

        val attempts = (1..3).map {
            async(Dispatchers.Default) {
                start.await()
                gate.runExclusive {
                    val active = logicalActive.incrementAndGet()
                    maximumLogicalActive.updateAndGet { previous -> maxOf(previous, active) }
                    try {
                        delay(25)
                    } finally {
                        logicalActive.decrementAndGet()
                    }
                }
            }
        }

        start.complete(Unit)
        attempts.awaitAll()

        assertEquals(1, maximumLogicalActive.get())
    }

    @Test
    fun `la recuperation durable precede toujours un diagnostic qui manque de memoire`() = runBlocking {
        var recoveryAttempted = false
        var persistenceFailureReported = false

        RecoveryFailureBoundary.persistThenDiagnose(
            persist = { recoveryAttempted = true },
            diagnose = { throw OutOfMemoryError("logger") },
        )

        RecoveryFailureBoundary.persistThenDiagnose(
            persist = { throw IllegalStateException("sqlite") },
            diagnose = {},
            onPersistenceFailure = {
                persistenceFailureReported = true
                throw OutOfMemoryError("logger")
            },
        )

        assertTrue(recoveryAttempted)
        assertTrue(persistenceFailureReported)
    }

    @Test
    fun `la resolution du chemin masque les erreurs de chemin mais jamais un OOM`() {
        assertNull(
            RetryAudioPathResolution.resolve<String> {
                throw IOException("chemin invalide")
            },
        )
        assertThrows(OutOfMemoryError::class.java) {
            RetryAudioPathResolution.resolve<String> {
                throw OutOfMemoryError("memoire")
            }
        }
    }

    @Test
    fun `une reprise sans lecture ni revendication ne peut jamais reecrire la dictee`() {
        assertFalse(
            RetryFailurePersistencePolicy.canPersistFailure(
                startingState = null,
                queuedForRetry = false,
            ),
        )
        assertTrue(
            RetryFailurePersistencePolicy.canPersistFailure(
                startingState = EtatDictee.A_REESSAYER,
                queuedForRetry = false,
            ),
        )
        assertTrue(
            RetryFailurePersistencePolicy.canPersistFailure(
                startingState = null,
                queuedForRetry = true,
            ),
        )
    }

    @Test
    fun `un wav final corrompu devient invalide et ne boucle plus depuis completed`() {
        val wav = File(temporaryFolder.root, "corrupt.wav").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }

        assertTrue(
            RetryAudioRecovery.inspect(wav, expectedSampleRate = 16_000) is
                RetryAudioRecoveryResult.Invalid,
        )
        assertFalse(
            RetryTranscriptionPolicy.canRetry(
                state = EtatDictee.TERMINEE,
                audioPath = wav.path,
                errorCode = "retry_audio_invalid",
            ),
        )
    }

    @Test
    fun `un part corrompu devient invalide et ne boucle plus depuis retryable`() {
        val wav = File(temporaryFolder.root, "corrupt-part.wav")
        File("${wav.path}.part").writeBytes(ByteArray(64))

        assertTrue(
            RetryAudioRecovery.inspect(wav, expectedSampleRate = 16_000) is
                RetryAudioRecoveryResult.Invalid,
        )
        assertFalse(
            RetryTranscriptionPolicy.canRetry(
                state = EtatDictee.A_REESSAYER,
                audioPath = wav.path,
                errorCode = "retry_audio_invalid",
            ),
        )
    }

    @Test
    fun `la classification wav ne transforme jamais un OOM en audio invalide`() {
        val wav = File(temporaryFolder.root, "memory.wav")

        assertThrows(OutOfMemoryError::class.java) {
            RetryAudioRecovery.inspect(
                audio = wav,
                expectedSampleRate = 16_000,
                recoverFile = { throw OutOfMemoryError("memoire") },
            )
        }
    }
}
