package dev.soupslurpr.transcribro.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableProjectionTest {
    @Test
    fun `a committed identifier survives an out of memory projection refresh`() {
        var committed = false
        var failureObserved = false

        val id = DurableProjection.commitThenRefresh(
            commit = {
                committed = true
                42L
            },
            refresh = { throw OutOfMemoryError("historique trop grand") },
            onRefreshFailure = { failureObserved = true },
        )

        assertTrue(committed)
        assertTrue(failureObserved)
        assertEquals(42L, id)
    }

    @Test
    fun `a failing diagnostic callback cannot turn a commit into a failure`() {
        val id = DurableProjection.commitThenRefresh(
            commit = { 84L },
            refresh = { throw OutOfMemoryError("projection") },
            onRefreshFailure = { throw OutOfMemoryError("logger") },
        )

        assertEquals(84L, id)
    }
}
