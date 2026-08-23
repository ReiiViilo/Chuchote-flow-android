package dev.soupslurpr.transcribro.privacy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardSensitivityPolicyTest {

    @Test
    fun `android 13 et plus masque l apercu des dictees copiees`() {
        assertFalse(ClipboardSensitivityPolicy.shouldMarkSensitive(sdkInt = 32))
        assertTrue(ClipboardSensitivityPolicy.shouldMarkSensitive(sdkInt = 33))
        assertTrue(ClipboardSensitivityPolicy.shouldMarkSensitive(sdkInt = 36))
    }
}
