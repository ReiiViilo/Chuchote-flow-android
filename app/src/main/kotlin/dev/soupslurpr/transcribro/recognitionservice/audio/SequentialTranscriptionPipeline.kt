package dev.soupslurpr.transcribro.recognitionservice.audio

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class SegmentText(
    val text: String,
    val viaRemote: Boolean,
)

internal data class CombinedTranscription(
    val text: String,
    val usedRemote: Boolean,
    val usedLocal: Boolean,
)

internal fun interface AudioSegmentSource {
    fun read(segment: AudioSegment): ShortArray
}

internal fun interface SegmentTranscriber {
    suspend fun transcribe(samples: ShortArray): SegmentText
}

/**
 * Une seule transcription à la fois, dans l'ordre du fichier.
 *
 * Cette propriété est volontaire : l'ancien code partageait un contexte
 * Whisper et une liste mutable entre plusieurs coroutines, ce qui a produit
 * des `ConcurrentModificationException` réelles sur l'appareil.
 */
internal class SequentialTranscriptionPipeline(
    private val source: AudioSegmentSource,
    private val transcriber: SegmentTranscriber,
) {
    suspend fun transcribe(
        segments: List<AudioSegment>,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): CombinedTranscription {
        val texts = mutableListOf<String>()
        var usedRemote = false
        var usedLocal = false

        segments.forEachIndexed { index, segment ->
            currentCoroutineContext().ensureActive()
            val samples = source.read(segment)
            currentCoroutineContext().ensureActive()
            val result = transcriber.transcribe(samples)
            currentCoroutineContext().ensureActive()
            result.text.trim().takeIf { it.isNotEmpty() }?.let(texts::add)
            usedRemote = usedRemote || result.viaRemote
            usedLocal = usedLocal || !result.viaRemote
            onProgress(index + 1, segments.size)
            currentCoroutineContext().ensureActive()
        }

        currentCoroutineContext().ensureActive()
        return CombinedTranscription(
            text = texts.joinToString(" "),
            usedRemote = usedRemote,
            usedLocal = usedLocal,
        )
    }
}
