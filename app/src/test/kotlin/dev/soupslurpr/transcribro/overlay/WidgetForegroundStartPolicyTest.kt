package dev.soupslurpr.transcribro.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetForegroundStartPolicyTest {

    @Test
    fun `api moderne refuse toute promotion micro sans lancement visible et permissions`() {
        assertFalse(
            WidgetForegroundStartPolicy.canPromote(
                launchedFromVisibleActivity = false,
                consentAccepted = true,
                microphoneGranted = true,
                overlayGranted = true,
            ),
        )
        assertFalse(
            WidgetForegroundStartPolicy.canPromote(
                launchedFromVisibleActivity = true,
                consentAccepted = true,
                microphoneGranted = false,
                overlayGranted = true,
            ),
        )
        assertFalse(
            WidgetForegroundStartPolicy.canPromote(
                launchedFromVisibleActivity = true,
                consentAccepted = false,
                microphoneGranted = true,
                overlayGranted = true,
            ),
        )
        assertFalse(
            WidgetForegroundStartPolicy.canPromote(
                launchedFromVisibleActivity = true,
                consentAccepted = true,
                microphoneGranted = true,
                overlayGranted = false,
            ),
        )
    }

    @Test
    fun `promotion micro permise seulement apres le gate visible complet`() {
        assertTrue(
            WidgetForegroundStartPolicy.canPromote(
                launchedFromVisibleActivity = true,
                consentAccepted = true,
                microphoneGranted = true,
                overlayGranted = true,
            ),
        )
    }

    @Test
    fun `le premier accord micro attend le retour explicite a resumed`() {
        assertFalse(
            VisibleWidgetLaunchPolicy.canLaunch(
                activityResumed = false,
                microphoneGranted = true,
            ),
        )
        assertTrue(
            VisibleWidgetLaunchPolicy.canLaunch(
                activityResumed = true,
                microphoneGranted = true,
            ),
        )
    }
}
