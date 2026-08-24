package dev.soupslurpr.transcribro.recognitionservice.whisper

import com.whispercpp.whisper.WhisperContext
import dev.soupslurpr.transcribro.remote.RemoteTranscriber
import java.util.concurrent.atomic.AtomicReference

/** Le texte transcrit, et le chemin qui l'a produit — utile pour comprendre
 * d'où vient la lenteur quand il y en a. */
data class ResultatTranscription(val texte: String, val viaRelais: Boolean)

/**
 * @param remoteTranscriber relais éventuel. Quand il est configuré, il est
 * tenté en premier : un serveur bien doté transcrit plus vite que le
 * processeur du téléphone. Son échec n'est jamais fatal — on retombe sur le
 * modèle embarqué.
 */
class WhisperRepository(
    private val whisperLocalDataSource: WhisperLocalDataSource,
    private val remoteTranscriber: RemoteTranscriber? = null,
) {

    private val whisperContext = AtomicReference<WhisperContext?>(null)

    private suspend fun loadWhisperContextIfNull() {
        if (whisperContext.get() == null) {
            whisperLocalDataSource.publishWhisperContext { created ->
                whisperContext.compareAndSet(null, created)
            }
        }
    }

    suspend fun transcribeAudio(data: ShortArray): ResultatTranscription {
        remoteTranscriber?.transcribe(data, SAMPLE_RATE)?.let {
            return ResultatTranscription(it, viaRelais = true)
        }

        loadWhisperContextIfNull()
        // assume we only have one channel
        var buffer = FloatArray(data.size) { index ->
            (data[index] / 32767.0f).coerceIn(-1f..1f)
        }

        if (data.size < 32000) {
            val newBuffer = FloatArray(32000)

            for ((i, value) in buffer.withIndex()) {
                newBuffer[i] = value
            }

            newBuffer.fill(0f, data.size, newBuffer.size)

            buffer = newBuffer
        }

        val transcript = whisperContext.get()
            ?.transcribeData(buffer, ((data.size / 16000f) * 1000f).toLong())
            ?: ""
        return ResultatTranscription(
            transcript.removeSuffix(" ."), // remove hallucination
            viaRelais = false,
        )
    }

    suspend fun release() {
        whisperContext.getAndSet(null)?.release()
    }

    companion object {
        /** Fréquence à laquelle le service capte l'audio. */
        private const val SAMPLE_RATE = 16000
    }
}
