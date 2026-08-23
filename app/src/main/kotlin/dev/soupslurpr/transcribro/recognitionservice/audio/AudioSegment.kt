package dev.soupslurpr.transcribro.recognitionservice.audio

/**
 * Plage d'échantillons PCM. La fin est exclusive, comme dans les collections
 * Kotlin, afin d'éviter le `..` inclusif qui créait facilement un échantillon
 * de trop dans l'ancien pipeline.
 */
data class AudioSegment(
    val startSample: Long,
    val endSampleExclusive: Long,
) {
    val sampleCount: Long
        get() = (endSampleExclusive - startSample).coerceAtLeast(0)

    companion object {
        /** Sert à normaliser des bornes externes potentiellement invalides. */
        fun unchecked(startSample: Long, endSampleExclusive: Long): AudioSegment =
            AudioSegment(startSample, endSampleExclusive)
    }
}

/** Normalise les résultats VAD et borne la mémoire de chaque transcription. */
internal object AudioSegmentPlanner {

    fun normalizeAndSplit(
        segments: List<AudioSegment>,
        totalSamples: Long,
        maxSegmentSamples: Int,
    ): List<AudioSegment> {
        require(maxSegmentSamples > 0) { "maxSegmentSamples doit être positif" }
        if (totalSamples <= 0) return emptyList()

        val normalized = segments
            .map {
                AudioSegment(
                    startSample = it.startSample.coerceIn(0, totalSamples),
                    endSampleExclusive = it.endSampleExclusive.coerceIn(0, totalSamples),
                )
            }
            .filter { it.endSampleExclusive > it.startSample }
            .sortedBy { it.startSample }

        val source = if (normalized.isEmpty()) {
            listOf(AudioSegment(0, totalSamples))
        } else {
            mergeOverlaps(normalized)
        }

        return buildList {
            source.forEach { segment ->
                var cursor = segment.startSample
                while (cursor < segment.endSampleExclusive) {
                    val end = minOf(
                        segment.endSampleExclusive,
                        cursor + maxSegmentSamples.toLong(),
                    )
                    add(AudioSegment(cursor, end))
                    cursor = end
                }
            }
        }
    }

    private fun mergeOverlaps(segments: List<AudioSegment>): List<AudioSegment> {
        if (segments.size < 2) return segments
        val merged = mutableListOf<AudioSegment>()
        var current = segments.first()

        for (next in segments.drop(1)) {
            current = if (next.startSample <= current.endSampleExclusive) {
                AudioSegment(
                    current.startSample,
                    maxOf(current.endSampleExclusive, next.endSampleExclusive),
                )
            } else {
                merged += current
                next
            }
        }
        merged += current
        return merged
    }
}
