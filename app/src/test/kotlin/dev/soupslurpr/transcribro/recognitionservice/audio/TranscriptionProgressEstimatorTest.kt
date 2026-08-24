package dev.soupslurpr.transcribro.recognitionservice.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionProgressEstimatorTest {

    @Test
    fun `une dictee longue progresse plus lentement a temps egal`() {
        val short = TranscriptionProgressEstimator.estimate(
            elapsedMs = 10_000,
            audioDurationMs = 10_000,
            completedSegments = 0,
            totalSegments = 1,
        )
        val long = TranscriptionProgressEstimator.estimate(
            elapsedMs = 10_000,
            audioDurationMs = 180_000,
            completedSegments = 0,
            totalSegments = 6,
        )

        assertTrue(short > long)
    }

    @Test
    fun `le temps seul ne peut jamais annoncer une reussite`() {
        val progress = TranscriptionProgressEstimator.estimate(
            elapsedMs = 30 * 60_000L,
            audioDurationMs = 60_000,
            completedSegments = 0,
            totalSegments = 2,
        )

        assertTrue(progress <= TranscriptionProgressEstimator.MAX_BEFORE_SUCCESS)
    }

    @Test
    fun `les segments termines dominent lestimation temporelle`() {
        val progress = TranscriptionProgressEstimator.estimate(
            elapsedMs = 1_000,
            audioDurationMs = 120_000,
            completedSegments = 3,
            totalSegments = 4,
        )

        assertTrue(progress >= 0.75f)
    }

    @Test
    fun `la reussite explicite atteint un`() {
        assertEquals(
            1f,
            TranscriptionProgressEstimator.estimate(
                elapsedMs = 0,
                audioDurationMs = 1,
                completedSegments = 1,
                totalSegments = 1,
                succeeded = true,
            ),
        )
    }

    @Test
    fun `le dernier segment ne devient pas vert avant le resultat`() {
        val progress = TranscriptionProgressEstimator.estimate(
            elapsedMs = 60_000,
            audioDurationMs = 60_000,
            completedSegments = 2,
            totalSegments = 2,
        )

        assertEquals(TranscriptionProgressEstimator.MAX_BEFORE_SUCCESS, progress)
    }
}
