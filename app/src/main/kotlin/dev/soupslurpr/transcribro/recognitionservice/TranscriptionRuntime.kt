package dev.soupslurpr.transcribro.recognitionservice

import android.content.Context
import com.whispercpp.whisper.WhisperContext
import dev.soupslurpr.transcribro.preferences.PrivacyConsent
import dev.soupslurpr.transcribro.recognitionservice.whisper.ResultatTranscription
import dev.soupslurpr.transcribro.recognitionservice.whisper.WhisperApi
import dev.soupslurpr.transcribro.recognitionservice.whisper.WhisperLocalDataSource
import dev.soupslurpr.transcribro.recognitionservice.whisper.WhisperRepository
import dev.soupslurpr.transcribro.remote.RemoteTranscriber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex

/**
 * Sérialise l'accès au moteur et revalide le consentement après toute attente
 * du verrou, au dernier instant avant l'appel au moteur.
 */
internal class ConsentAwareTranscriptionGate {
    private val mutex = Mutex()

    suspend fun <T> run(
        consentAccepted: suspend () -> Boolean,
        transcribe: suspend () -> T,
    ): T {
        mutex.lock()
        return try {
            currentCoroutineContext().ensureActive()
            if (!consentAccepted()) {
                throw CancellationException("Consentement retiré avant la transcription")
            }
            val result = transcribe()
            currentCoroutineContext().ensureActive()
            result
        } finally {
            mutex.unlock()
        }
    }
}

/**
 * Un seul contexte Whisper et une seule transcription active par processus.
 *
 * Une reprise lancée depuis l'historique ne peut ainsi ni doubler la mémoire
 * du modèle ni transcrire en parallèle avec une nouvelle dictée.
 */
internal object TranscriptionRuntime {
    private val transcriptionGate = ConsentAwareTranscriptionGate()

    @Volatile
    private var repository: WhisperRepository? = null

    suspend fun transcribe(context: Context, samples: ShortArray): ResultatTranscription =
        transcriptionGate.run(
            consentAccepted = { PrivacyConsent.isAccepted(context.applicationContext) },
            transcribe = {
                getRepository(context.applicationContext).transcribeAudio(samples)
            },
        )

    private fun getRepository(context: Context): WhisperRepository =
        repository ?: synchronized(this) {
            repository ?: WhisperRepository(
                WhisperLocalDataSource(
                    whisperApi = object : WhisperApi {
                        override fun getWhisperContext(): WhisperContext =
                            WhisperContext.createContextFromAsset(
                                context.assets,
                                "models/whisper/ggml-small-q8_0.bin",
                            )
                    },
                    ioDispatcher = Dispatchers.IO,
                ),
                remoteTranscriber = RemoteTranscriber(context),
            ).also { repository = it }
        }
}
