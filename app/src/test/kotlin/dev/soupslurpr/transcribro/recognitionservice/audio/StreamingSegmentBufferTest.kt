package dev.soupslurpr.transcribro.recognitionservice.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le tampon de segments au fil de l'eau doit reproduire exactement les bornes
 * que [AudioSegmentPlanner.normalizeAndSplit] produirait après coup : c'est la
 * condition pour que la transcription anticipée soit réutilisable telle
 * quelle. Ces tests vérifient cette équivalence et la conservation des
 * échantillons.
 */
class StreamingSegmentBufferTest {

    private companion object {
        const val MAX = 1_000
        const val PRE_ROLL = 200
    }

    /** Rampe déterministe : la valeur d'un échantillon encode sa position. */
    private fun ramp(startAbs: Long, count: Int): ShortArray =
        ShortArray(count) { ((startAbs + it) % 3_000).toInt().toShort() }

    private fun feed(
        buffer: StreamingSegmentBuffer,
        startAbs: Long,
        count: Int,
    ): List<ClosedSegment> = buffer.append(ramp(startAbs, count), count)

    @Test
    fun `un segment vad clos est emis avec ses echantillons exacts`() {
        val buffer = StreamingSegmentBuffer(MAX, PRE_ROLL)
        feed(buffer, 0, 500)
        // Rétro-padding de 150 échantillons : couvert par le pré-roll de 200.
        buffer.onSpeechStart(350)
        feed(buffer, 500, 300)
        val closed = buffer.onSpeechEnd(700)

        assertEquals(1, closed.size)
        assertEquals(AudioSegment(350, 700), closed[0].segment)
        assertArrayEquals(ramp(350, 350), closed[0].samples)
        assertFalse(buffer.bordersDiverged)
    }

    @Test
    fun `un long segment est tranche en direct comme le planner`() {
        val buffer = StreamingSegmentBuffer(MAX, PRE_ROLL)
        buffer.onSpeechStart(0)
        val emitted = mutableListOf<ClosedSegment>()
        var pos = 0L
        repeat(7) {
            emitted += feed(buffer, pos, 500)
            pos += 500
        }
        emitted += buffer.onSpeechEnd(3_500)

        val planned = AudioSegmentPlanner.normalizeAndSplit(
            segments = listOf(AudioSegment(0, 3_500)),
            totalSamples = 3_500,
            maxSegmentSamples = MAX,
        )
        assertEquals(planned, emitted.map { it.segment })
        // Chaque tranche transporte exactement ses échantillons.
        emitted.forEach {
            assertArrayEquals(
                ramp(it.segment.startSample, it.segment.sampleCount.toInt()),
                it.samples,
            )
        }
    }

    @Test
    fun `le pre-roll couvre le retro-padding du debut de parole`() {
        val buffer = StreamingSegmentBuffer(MAX, PRE_ROLL)
        feed(buffer, 0, 900) // silence : la fenêtre ne garde que PRE_ROLL
        buffer.onSpeechStart(750) // rétro-paddé, mais dans le pré-roll conservé
        feed(buffer, 900, 100)
        val closed = buffer.onSpeechEnd(1_000)

        assertEquals(AudioSegment(750, 1_000), closed.single().segment)
        assertArrayEquals(ramp(750, 250), closed.single().samples)
        assertFalse(buffer.bordersDiverged)
    }

    @Test
    fun `un retro-padding au-dela du pre-roll marque la divergence`() {
        val buffer = StreamingSegmentBuffer(MAX, PRE_ROLL)
        feed(buffer, 0, 900)
        buffer.onSpeechStart(100) // bien avant la fenêtre conservée
        feed(buffer, 900, 100)
        val closed = buffer.onSpeechEnd(1_000)

        assertTrue(buffer.bordersDiverged)
        // Le segment émis reste valide (tronqué), mais l'appelant l'ignorera.
        assertTrue(closed.single().segment.startSample >= 700)
    }

    @Test
    fun `finish emet la queue de parole active`() {
        val buffer = StreamingSegmentBuffer(MAX, PRE_ROLL)
        buffer.onSpeechStart(0)
        feed(buffer, 0, 2_400)
        val tail = buffer.finish()

        val allSegments = tail.map { it.segment }
        val planned = AudioSegmentPlanner.normalizeAndSplit(
            segments = listOf(AudioSegment(0, 2_400)),
            totalSamples = 2_400,
            maxSegmentSamples = MAX,
        )
        // Les tranches pleines sont sorties par append; finish livre le reste.
        assertEquals(planned.takeLast(allSegments.size), allSegments)
        assertEquals(2_400L, (tail.lastOrNull()?.segment?.endSampleExclusive ?: 0L))
    }

    @Test
    fun `silence total sans parole n'emet rien`() {
        val buffer = StreamingSegmentBuffer(MAX, PRE_ROLL)
        feed(buffer, 0, 5_000)
        assertTrue(buffer.finish().isEmpty())
        assertFalse(buffer.bordersDiverged)
    }

    @Test
    fun `deux segments successifs conservent leurs bornes et echantillons`() {
        val buffer = StreamingSegmentBuffer(MAX, PRE_ROLL)
        val emitted = mutableListOf<ClosedSegment>()
        buffer.onSpeechStart(0)
        emitted += feed(buffer, 0, 400)
        emitted += buffer.onSpeechEnd(400)
        emitted += feed(buffer, 400, 300) // silence entre les deux
        buffer.onSpeechStart(600)
        emitted += feed(buffer, 700, 200)
        emitted += buffer.onSpeechEnd(900)

        assertEquals(
            listOf(AudioSegment(0, 400), AudioSegment(600, 900)),
            emitted.map { it.segment },
        )
        assertArrayEquals(ramp(600, 300), emitted[1].samples)
    }
}
