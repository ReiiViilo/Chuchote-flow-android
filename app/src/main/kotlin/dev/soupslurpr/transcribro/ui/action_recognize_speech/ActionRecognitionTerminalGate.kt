package dev.soupslurpr.transcribro.ui.action_recognize_speech

/**
 * Associe le résultat exporté à une tentative et à l'identité du service.
 *
 * L'activité est publique puisqu'elle implémente ACTION_RECOGNIZE_SPEECH. Une
 * réponse ne doit donc être envoyée au PendingIntent du demandeur qu'une fois,
 * et jamais par un callback Binder appartenant à une tentative précédente.
 */
internal class ActionRecognitionTerminalGate {
    private enum class Phase {
        IDLE,
        WAITING_FOR_SESSION,
        ACTIVE,
        STOPPING,
        TERMINAL,
    }

    private var phase = Phase.IDLE
    private var generation = 0L
    private var activeGeneration: Long? = null
    private var activeSessionId: String? = null

    @Synchronized
    fun beginAttempt(): Long? {
        if (phase == Phase.WAITING_FOR_SESSION || phase == Phase.ACTIVE) return null
        if (phase == Phase.STOPPING) return null
        generation += 1L
        activeGeneration = generation
        activeSessionId = null
        phase = Phase.WAITING_FOR_SESSION
        return generation
    }

    @Synchronized
    fun activate(attempt: Long, sessionId: String?): Boolean {
        val validSessionId = sessionId?.takeIf { it.isNotBlank() } ?: return false
        if (attempt != activeGeneration || phase != Phase.WAITING_FOR_SESSION) return false
        activeSessionId = validSessionId
        phase = Phase.ACTIVE
        return true
    }

    @Synchronized
    fun requestStop(attempt: Long): Boolean {
        if (attempt != activeGeneration || phase != Phase.ACTIVE) return false
        phase = Phase.STOPPING
        return true
    }

    @Synchronized
    fun accepts(attempt: Long): Boolean =
        attempt == activeGeneration && (
            phase == Phase.WAITING_FOR_SESSION ||
                phase == Phase.ACTIVE ||
                phase == Phase.STOPPING
            )

    @Synchronized
    fun claimResult(attempt: Long, sessionId: String?): Boolean {
        if (attempt != activeGeneration) return false
        if (phase != Phase.ACTIVE && phase != Phase.STOPPING) return false
        if (sessionId != activeSessionId) return false
        closeAttempt()
        return true
    }

    @Synchronized
    fun claimError(attempt: Long): Boolean {
        if (!accepts(attempt)) return false
        closeAttempt()
        return true
    }

    @Synchronized
    fun claimCancellation(attempt: Long): Boolean {
        if (!accepts(attempt)) return false
        closeAttempt()
        return true
    }

    @Synchronized
    fun invalidate() {
        generation += 1L
        activeGeneration = null
        activeSessionId = null
        phase = Phase.TERMINAL
    }

    private fun closeAttempt() {
        activeGeneration = null
        activeSessionId = null
        phase = Phase.TERMINAL
    }
}

internal data class ActionRecognitionRequest(
    val languageModel: String,
    val partialResults: Boolean,
    val autoStop: Boolean,
)

/** Pure validation boundary for the exported ACTION_RECOGNIZE_SPEECH surface. */
internal object ActionRecognitionRequestContract {
    // Valeurs publiques de RecognizerIntent.LANGUAGE_MODEL_* gardées ici pour
    // que le validateur et ses tests restent purs sur la JVM locale.
    private val supportedLanguageModels = setOf("free_form", "web_search")

    /**
     * prompt/language/maxResults sont tolérés sur le contrat Android d'entrée,
     * mais volontairement absents d'ActionRecognitionRequest: l'UI minimale
     * n'affiche pas le prompt, n'impose pas une langue et le service ne renvoie
     * qu'un résultat. Le ViewModel ne peut donc pas prétendre les honorer.
     */
    @Suppress("UNUSED_PARAMETER")
    fun validate(
        actionMatches: Boolean,
        languageModel: String?,
        partialResults: Boolean,
        autoStop: Boolean,
        hasUnsupportedExtras: Boolean,
        pendingBundleWithoutPendingIntent: Boolean,
        extrasHaveExpectedTypes: Boolean,
        prompt: String?,
        language: String?,
        maxResults: Int?,
    ): ActionRecognitionRequest? {
        if (
            !actionMatches ||
            hasUnsupportedExtras ||
            pendingBundleWithoutPendingIntent ||
            !extrasHaveExpectedTypes
        ) {
            return null
        }
        if (maxResults != null && maxResults <= 0) return null
        val validLanguageModel = languageModel?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (validLanguageModel !in supportedLanguageModels) return null
        return ActionRecognitionRequest(
            languageModel = validLanguageModel,
            partialResults = partialResults,
            autoStop = autoStop,
        )
    }
}

/**
 * Machine d'exclusivité des deux canaux de sortie du contrat Android.
 * Une fois un canal choisi, aucune branche ne peut livrer le transcript par
 * l'autre canal, même si le PendingIntent échoue.
 */
internal class ActionTranscriptDeliveryContract(
    private val hasPendingIntent: Boolean,
) {
    enum class Action {
        SEND_PENDING_INTENT,
        SET_ACTIVITY_RESULT,
        FINISH_WITHOUT_ACTIVITY_PAYLOAD,
        CANCEL_ACTIVITY,
        NONE,
    }

    enum class PendingOutcome {
        SENT,
        CANCELED,
        FAILED,
    }

    private enum class Phase {
        READY,
        AWAITING_PENDING_RESULT,
        TERMINAL,
    }

    private var phase = Phase.READY

    @Synchronized
    fun begin(consentAccepted: Boolean): Action {
        if (phase != Phase.READY) return Action.NONE
        if (!consentAccepted) {
            phase = Phase.TERMINAL
            return Action.CANCEL_ACTIVITY
        }
        return if (hasPendingIntent) {
            phase = Phase.AWAITING_PENDING_RESULT
            Action.SEND_PENDING_INTENT
        } else {
            phase = Phase.TERMINAL
            Action.SET_ACTIVITY_RESULT
        }
    }

    @Synchronized
    fun rejectInvalidPendingPayload(): Action {
        if (!hasPendingIntent || phase != Phase.READY) return Action.NONE
        phase = Phase.TERMINAL
        return Action.CANCEL_ACTIVITY
    }

    @Synchronized
    fun completePending(outcome: PendingOutcome): Action {
        if (phase != Phase.AWAITING_PENDING_RESULT) return Action.NONE
        phase = Phase.TERMINAL
        return if (outcome == PendingOutcome.SENT) {
            Action.FINISH_WITHOUT_ACTIVITY_PAYLOAD
        } else {
            Action.CANCEL_ACTIVITY
        }
    }
}
