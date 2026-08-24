package dev.soupslurpr.transcribro.recognitionservice.audio

import kotlin.math.max

/** Progression prudente : le temps seul ne peut jamais produire le vert final. */
internal object TranscriptionProgressEstimator {
    const val MAX_BEFORE_SUCCESS = 0.92f
    private const val MAX_TIME_BASED_PROGRESS = 0.55f
    private const val MIN_EXPECTED_PROCESSING_MS = 20_000L
    private const val PROCESSING_TIME_PER_AUDIO_MS = 1.5

    fun estimate(
        elapsedMs: Long,
        audioDurationMs: Long,
        completedSegments: Int,
        totalSegments: Int,
        succeeded: Boolean = false,
    ): Float {
        if (succeeded) return 1f

        val expectedProcessingMs = max(
            MIN_EXPECTED_PROCESSING_MS,
            (audioDurationMs.coerceAtLeast(1) * PROCESSING_TIME_PER_AUDIO_MS).toLong(),
        )
        val timeProgress = (
            elapsedMs.coerceAtLeast(0).toDouble() / expectedProcessingMs * MAX_TIME_BASED_PROGRESS
            ).toFloat().coerceIn(0f, MAX_TIME_BASED_PROGRESS)
        val segmentProgress = if (totalSegments > 0) {
            val completed = completedSegments.coerceIn(0, totalSegments)
            if (completed >= totalSegments) {
                MAX_BEFORE_SUCCESS
            } else {
                completed.toFloat() / totalSegments
            }
        } else {
            0f
        }

        return max(timeProgress, segmentProgress).coerceAtMost(MAX_BEFORE_SUCCESS)
    }
}
