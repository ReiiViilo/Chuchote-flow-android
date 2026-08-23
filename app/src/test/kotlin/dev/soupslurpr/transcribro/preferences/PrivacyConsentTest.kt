package dev.soupslurpr.transcribro.preferences

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyConsentTest {
    @Test
    fun `a previous policy acceptance never accepts the current policy`() {
        assertFalse(
            PrivacyConsent.isCurrentPolicyAccepted(
                currentPolicyAccepted = null,
                previousPolicyAccepted = true,
            ),
        )
    }

    @Test
    fun `an explicit current policy acceptance is accepted`() {
        assertTrue(
            PrivacyConsent.isCurrentPolicyAccepted(
                currentPolicyAccepted = true,
                previousPolicyAccepted = false,
            ),
        )
    }
}
