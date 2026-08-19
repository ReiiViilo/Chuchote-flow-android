package dev.soupslurpr.transcribro.recognitionservice.whisper

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.whispercpp.whisper.WhisperContext
import dev.soupslurpr.transcribro.remote.RemoteTranscriber

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

    private var whisperContext: MutableState<WhisperContext?> =
        mutableStateOf(null)

    private suspend fun loadWhisperContextIfNull() {
        if (whisperContext.value == null) {
            whisperContext.value = whisperLocalDataSource.getWhisperContext()
        }
    }

    suspend fun transcribeAudio(data: ShortArray): String {
        remoteTranscriber?.transcribe(data, SAMPLE_RATE)?.let { return it }

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

        val transcript = whisperContext.value?.transcribeData(buffer, ((data.size / 16000f) * 1000f).toLong()) ?: ""
        return transcript.removeSuffix(" .") // remove hallucination
    }

    suspend fun release() {
        whisperContext.value?.release()
    }

    companion object {
        /** Fréquence à laquelle le service capte l'audio. */
        private const val SAMPLE_RATE = 16000
    }
}