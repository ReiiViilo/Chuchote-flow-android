package dev.soupslurpr.transcribro.remote

/**
 * Dernière barrière synchrone avant que HttpURLConnection ouvre le corps.
 *
 * Le consentement peut être retiré pendant la préparation du WAV; l'état de
 * la coroutine peut lui aussi changer avant que le premier octet soit envoyé.
 */
internal object RemoteUploadGate {
    fun canOpenRequestBody(
        consentAccepted: Boolean,
        coroutineActive: Boolean,
    ): Boolean = consentAccepted && coroutineActive
}
