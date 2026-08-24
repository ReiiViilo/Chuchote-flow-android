package dev.soupslurpr.transcribro.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusedTargetMatcherTest {
    @Test
    fun `a reusable view id alone cannot authorize a renewed node`() {
        val captured = FocusedTargetDescriptor("com.example", 7, "message")

        assertTrue(FocusedTargetMatcher.matches(captured, captured, sameNode = true))
        assertFalse(FocusedTargetMatcher.matches(captured, captured, sameNode = false))
    }

    @Test
    fun `a renewed node without a stable field id is rejected`() {
        val captured = FocusedTargetDescriptor("com.example", 7, null)

        assertFalse(FocusedTargetMatcher.matches(captured, captured, sameNode = false))
    }

    @Test
    fun `a stable accessibility unique id survives a changed view id`() {
        val captured = FocusedTargetDescriptor(
            packageName = "com.example",
            windowId = 7,
            viewId = "message",
            uniqueId = "composer-42",
        )
        val renewed = captured.copy(viewId = "message-renewed")

        assertTrue(FocusedTargetMatcher.matches(captured, renewed, sameNode = false))
    }

    @Test
    fun `a renewed source becomes the watcher anchor after an authorized insertion`() {
        val captured = FocusedTargetDescriptor(
            packageName = "com.example",
            windowId = 7,
            viewId = "message",
            uniqueId = "composer-42",
        )
        val renewed = captured.copy(viewId = "message-renewed")

        assertEquals(
            renewed,
            FocusedTargetMatcher.reanchorIfMatching(
                expected = captured,
                actual = renewed,
                sameNode = false,
            ),
        )
        assertNull(
            FocusedTargetMatcher.reanchorIfMatching(
                expected = captured,
                actual = renewed.copy(uniqueId = "composer-99"),
                sameNode = false,
            ),
        )
    }

    @Test
    fun `different accessibility unique ids never fall back to a reused view id`() {
        val captured = FocusedTargetDescriptor(
            packageName = "com.example",
            windowId = 7,
            viewId = "message",
            uniqueId = "composer-42",
        )
        val otherField = captured.copy(uniqueId = "composer-99")

        assertFalse(FocusedTargetMatcher.matches(captured, otherField, sameNode = false))
    }

    @Test
    fun `an undefined window is never a delivery target`() {
        val captured = FocusedTargetDescriptor(
            packageName = "com.example",
            windowId = -1,
            viewId = "message",
            uniqueId = "composer-42",
        )

        assertFalse(FocusedTargetMatcher.matches(captured, captured, sameNode = true))
    }

    @Test
    fun `a package window or view change is a different target`() {
        val captured = FocusedTargetDescriptor("com.example", 7, "message")

        assertFalse(
            FocusedTargetMatcher.matches(
                captured,
                captured.copy(packageName = "com.other"),
                sameNode = true,
            ),
        )
        assertFalse(
            FocusedTargetMatcher.matches(
                captured,
                captured.copy(windowId = 8),
                sameNode = true,
            ),
        )
        assertFalse(
            FocusedTargetMatcher.matches(
                captured,
                captured.copy(viewId = "subject"),
                sameNode = true,
            ),
        )
    }
}
