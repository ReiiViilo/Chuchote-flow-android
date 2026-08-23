package dev.soupslurpr.transcribro.overlay

/** Préconditions cumulatives avant toute promotion en service microphone. */
internal object WidgetForegroundStartPolicy {
    fun canPromote(
        launchedFromVisibleActivity: Boolean,
        consentAccepted: Boolean,
        microphoneGranted: Boolean,
        overlayGranted: Boolean,
    ): Boolean =
        launchedFromVisibleActivity &&
            consentAccepted &&
            microphoneGranted &&
            overlayGranted
}

/** Le retour d'une permission n'autorise le lancement qu'une fois RESUMED. */
internal object VisibleWidgetLaunchPolicy {
    fun canLaunch(
        activityResumed: Boolean,
        microphoneGranted: Boolean,
    ): Boolean = activityResumed && microphoneGranted
}
