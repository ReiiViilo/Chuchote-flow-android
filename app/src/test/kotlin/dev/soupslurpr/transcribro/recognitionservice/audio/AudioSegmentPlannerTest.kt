package dev.soupslurpr.transcribro.recognitionservice.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSegmentPlannerTest {

    @Test
    fun `une plage longue est decoupee sans trou et sans segment demesure`() {
        val planned = AudioSegmentPlanner.normalizeAndSplit(
            segments = listOf(AudioSegment(8_000, 1_608_000)),
            totalSamples = 1_608_000,
            maxSegmentSamples = 480_000,
        )

        assertEquals(4, planned.size)
        assertEquals(8_000, planned.first().startSample)
        assertEquals(1_608_000, planned.last().endSampleExclusive)
        planned.zipWithNext().forEach { (left, right) ->
            assertEquals(left.endSampleExclusive, right.startSample)
        }
        assertTrue(planned.all { it.sampleCount <= 480_000 })
    }

    @Test
    fun `sans detection vocale tout audio reste recuperable mais borne`() {
        val planned = AudioSegmentPlanner.normalizeAndSplit(
            segments = emptyList(),
            totalSamples = 1_120_000,
            maxSegmentSamples = 480_000,
        )

        assertEquals(
            listOf(
                AudioSegment(0, 480_000),
                AudioSegment(480_000, 960_000),
                AudioSegment(960_000, 1_120_000),
            ),
            planned,
        )
    }

    @Test
    fun `les plages hors fichier sont bornees et les plages vides ignorees`() {
        val planned = AudioSegmentPlanner.normalizeAndSplit(
            segments = listOf(
                AudioSegment.unchecked(-20, 100),
                AudioSegment.unchecked(200, 200),
                AudioSegment.unchecked(900, 1_500),
            ),
            totalSamples = 1_000,
            maxSegmentSamples = 500,
        )

        assertEquals(listOf(AudioSegment(0, 100), AudioSegment(900, 1_000)), planned)
    }
}
