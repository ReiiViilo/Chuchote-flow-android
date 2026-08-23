package dev.soupslurpr.transcribro.recognitionservice.audio

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SequentialTranscriptionPipelineTest {

    @Test
    fun `les segments sont transcrits un par un et remis dans le bon ordre`() = runBlocking {
        val active = AtomicInteger(0)
        val maximumActive = AtomicInteger(0)
        val progress = mutableListOf<Pair<Int, Int>>()
        val source = object : AudioSegmentSource {
            override fun read(segment: AudioSegment): ShortArray =
                shortArrayOf(segment.startSample.toShort())
        }
        val transcriber = object : SegmentTranscriber {
            override suspend fun transcribe(samples: ShortArray): SegmentText {
                val now = active.incrementAndGet()
                maximumActive.updateAndGet { previous -> maxOf(previous, now) }
                delay(5)
                active.decrementAndGet()
                return SegmentText("segment-${samples.single()}", viaRemote = false)
            }
        }
        val pipeline = SequentialTranscriptionPipeline(source, transcriber)

        val result = pipeline.transcribe(
            listOf(AudioSegment(1, 2), AudioSegment(2, 3), AudioSegment(3, 4)),
        ) { completed, total -> progress += completed to total }

        assertEquals(1, maximumActive.get())
        assertEquals("segment-1 segment-2 segment-3", result.text)
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), progress)
    }
}
