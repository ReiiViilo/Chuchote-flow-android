package dev.soupslurpr.transcribro.recognitionservice.audio

/** Format SQLite compact et tolérant : `début:fin;début:fin`. */
internal object AudioSegmentCodec {
    fun encode(segments: List<AudioSegment>): String = segments
        .filter { it.startSample >= 0 && it.endSampleExclusive > it.startSample }
        .joinToString(";") { "${it.startSample}:${it.endSampleExclusive}" }

    fun decode(encoded: String?): List<AudioSegment> {
        if (encoded.isNullOrBlank()) return emptyList()
        return encoded.split(';').mapNotNull { token ->
            val parts = token.split(':', limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val start = parts[0].toLongOrNull() ?: return@mapNotNull null
            val end = parts[1].toLongOrNull() ?: return@mapNotNull null
            AudioSegment(start, end).takeIf {
                it.startSample >= 0 && it.endSampleExclusive > it.startSample
            }
        }
    }
}
