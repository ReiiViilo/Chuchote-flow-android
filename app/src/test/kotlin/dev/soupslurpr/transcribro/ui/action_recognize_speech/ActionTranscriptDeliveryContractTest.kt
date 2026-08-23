package dev.soupslurpr.transcribro.ui.action_recognize_speech

import org.junit.Assert.assertEquals
import org.junit.Test

class ActionTranscriptDeliveryContractTest {
    @Test
    fun `activity-only request delivers transcript through activity once`() {
        val contract = ActionTranscriptDeliveryContract(hasPendingIntent = false)

        assertEquals(
            ActionTranscriptDeliveryContract.Action.SET_ACTIVITY_RESULT,
            contract.begin(consentAccepted = true),
        )
        assertEquals(
            ActionTranscriptDeliveryContract.Action.NONE,
            contract.begin(consentAccepted = true),
        )
    }

    @Test
    fun `pending request sends pending intent then finishes without activity payload`() {
        val contract = ActionTranscriptDeliveryContract(hasPendingIntent = true)

        assertEquals(
            ActionTranscriptDeliveryContract.Action.SEND_PENDING_INTENT,
            contract.begin(consentAccepted = true),
        )
        assertEquals(
            ActionTranscriptDeliveryContract.Action.FINISH_WITHOUT_ACTIVITY_PAYLOAD,
            contract.completePending(
                ActionTranscriptDeliveryContract.PendingOutcome.SENT,
            ),
        )
        assertEquals(
            ActionTranscriptDeliveryContract.Action.NONE,
            contract.begin(consentAccepted = true),
        )
    }

    @Test
    fun `canceled pending intent cancels activity without fallback`() {
        val contract = ActionTranscriptDeliveryContract(hasPendingIntent = true)
        contract.begin(consentAccepted = true)

        assertEquals(
            ActionTranscriptDeliveryContract.Action.CANCEL_ACTIVITY,
            contract.completePending(
                ActionTranscriptDeliveryContract.PendingOutcome.CANCELED,
            ),
        )
        assertEquals(
            ActionTranscriptDeliveryContract.Action.NONE,
            contract.begin(consentAccepted = true),
        )
    }

    @Test
    fun `pending send failure cancels activity without fallback`() {
        val contract = ActionTranscriptDeliveryContract(hasPendingIntent = true)
        contract.begin(consentAccepted = true)

        assertEquals(
            ActionTranscriptDeliveryContract.Action.CANCEL_ACTIVITY,
            contract.completePending(
                ActionTranscriptDeliveryContract.PendingOutcome.FAILED,
            ),
        )
        assertEquals(
            ActionTranscriptDeliveryContract.Action.NONE,
            contract.begin(consentAccepted = true),
        )
    }

    @Test
    fun `invalid pending payload cancels activity without fallback`() {
        val contract = ActionTranscriptDeliveryContract(hasPendingIntent = true)

        assertEquals(
            ActionTranscriptDeliveryContract.Action.CANCEL_ACTIVITY,
            contract.rejectInvalidPendingPayload(),
        )
    }

    @Test
    fun `revocation cancels either channel before transcript delivery`() {
        val activityContract = ActionTranscriptDeliveryContract(hasPendingIntent = false)
        val pendingContract = ActionTranscriptDeliveryContract(hasPendingIntent = true)

        assertEquals(
            ActionTranscriptDeliveryContract.Action.CANCEL_ACTIVITY,
            activityContract.begin(consentAccepted = false),
        )
        assertEquals(
            ActionTranscriptDeliveryContract.Action.CANCEL_ACTIVITY,
            pendingContract.begin(consentAccepted = false),
        )
    }

    @Test
    fun `pending and activity transcript actions are mutually exclusive`() {
        val activityContract = ActionTranscriptDeliveryContract(hasPendingIntent = false)
        val pendingContract = ActionTranscriptDeliveryContract(hasPendingIntent = true)

        assertEquals(
            ActionTranscriptDeliveryContract.Action.SET_ACTIVITY_RESULT,
            activityContract.begin(consentAccepted = true),
        )
        assertEquals(
            ActionTranscriptDeliveryContract.Action.SEND_PENDING_INTENT,
            pendingContract.begin(consentAccepted = true),
        )
        assertEquals(
            ActionTranscriptDeliveryContract.Action.NONE,
            activityContract.completePending(
                ActionTranscriptDeliveryContract.PendingOutcome.SENT,
            ),
        )
    }
}
