package dev.soupslurpr.transcribro.memory

import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class StoreStartupBoundaryTest {
    @Test
    fun ordinaryFailureIsReportedWithoutEscapingTheBoundary() {
        val failure = IOException("disque temporairement indisponible")
        var diagnosed: Throwable? = null

        StoreStartupBoundary.runBestEffort(
            step = { throw failure },
            onFailure = { diagnosed = it },
        )

        assertSame(failure, diagnosed)
    }

    @Test
    fun virtualMachineFailureIsNotMisreportedAsRecoverable() {
        val failure = OutOfMemoryError("test")

        val thrown = assertThrows(OutOfMemoryError::class.java) {
            StoreStartupBoundary.runBestEffort(
                step = { throw failure },
                onFailure = {},
            )
        }

        assertSame(failure, thrown)
    }
}
