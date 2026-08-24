package dev.soupslurpr.transcribro.recognitionservice.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioSegmentCodecTest {
    @Test
    fun `round trip preserves valid segments`() {
        val segments = listOf(
            AudioSegment(0, 16_000),
            AudioSegment(20_000, 480_000),
        )

        assertEquals(segments, AudioSegmentCodec.decode(AudioSegmentCodec.encode(segments)))
    }

    @Test
    fun `decode ignores malformed and empty segments`() {
        assertEquals(
            listOf(AudioSegment(10, 20)),
            AudioSegmentCodec.decode("bad;-1:2;3:3;10:20;9:nope"),
        )
    }
}
