package dev.soupslurpr.transcribro.recognitionservice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionSessionTrackerTest {
    @Test
    fun `a late callback is rejected after cancellation and a new activation`() {
        val tracker = RecognitionSessionTracker()
        assertTrue(tracker.activate("session-a"))
        assertTrue(tracker.accepts("session-a"))

        tracker.invalidate()
        assertFalse(tracker.accepts("session-a"))

        assertTrue(tracker.activate("session-b"))
        assertFalse(tracker.complete("session-a"))
        assertTrue(tracker.complete("session-b"))
        assertFalse(tracker.complete("session-b"))
    }

    @Test
    fun `a missing session identity fails closed`() {
        val tracker = RecognitionSessionTracker()

        assertFalse(tracker.activate(null))
        assertFalse(tracker.accepts(null))
        assertFalse(tracker.complete(null))
    }
}
