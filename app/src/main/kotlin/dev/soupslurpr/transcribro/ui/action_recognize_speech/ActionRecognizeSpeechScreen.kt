package dev.soupslurpr.transcribro.ui.action_recognize_speech

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import dev.soupslurpr.transcribro.preferences.PrivacyConsent

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ActionRecognizeSpeechScreen(
    showSnackbarError: (String, String?, Boolean, SnackbarDuration) -> Unit,
) {
    val speechRecognizerViewModel: SpeechRecognizerViewModel = viewModel()
    val speechRecognizerUiState by speechRecognizerViewModel.uiState.collectAsState()
    val terminalEvent by speechRecognizerViewModel.terminalEvent.collectAsState()
    val activity = LocalActivity.current ?: return

    // Lire l'Intent d'entrée une fois par instance d'Activity. Le ViewModel et
    // la tentative survivent à une rotation, mais la sortie est toujours livrée
    // par l'Activity actuellement attachée.
    val request = remember(activity, activity.intent) {
        activity.intent.toValidatedActionRecognitionRequest()
    }

    val microphonePermissionState = rememberPermissionState(
        Manifest.permission.RECORD_AUDIO,
    )
    var alreadyRequestedMicrophonePermissionOnce by rememberSaveable {
        mutableStateOf(false)
    }

    LaunchedEffect(activity, request) {
        if (request == null) {
            finishCanceled(activity)
        }
    }

    // La préférence observée par MainActivity peut retirer ce composable à la
    // même frame. Cette collecte locale clôt donc explicitement le contrat
    // Activity avant qu'une ancienne UI puisse rester en attente.
    LaunchedEffect(activity) {
        PrivacyConsent.acceptanceFlow(activity.applicationContext).collect { accepted ->
            if (!accepted) {
                cancelForRevocationAndFinish(
                    activity = activity,
                    speechRecognizerViewModel = speechRecognizerViewModel,
                )
            }
        }
    }

    LaunchedEffect(terminalEvent, activity) {
        val event = terminalEvent ?: return@LaunchedEffect
        if (
            activity.isFinishing ||
            activity.isDestroyed ||
            activity.isChangingConfigurations
        ) {
            // L'événement reste dans le ViewModel; l'Activity créée après la
            // rotation le réclamera. Aucun listener ne capture l'ancienne.
            return@LaunchedEffect
        }
        if (!speechRecognizerViewModel.claimTerminalDelivery(event)) {
            return@LaunchedEffect
        }

        when (event) {
            is ActionRecognitionTerminalEvent.Canceled -> finishCanceled(activity)
            is ActionRecognitionTerminalEvent.Result -> deliverResult(
                activity = activity,
                event = event,
                speechRecognizerViewModel = speechRecognizerViewModel,
            )
        }
        speechRecognizerViewModel.completeTerminalDelivery(event)
    }

    LaunchedEffect(speechRecognizerUiState.showInsufficientPermissionsError) {
        if (speechRecognizerUiState.showInsufficientPermissionsError) {
            microphonePermissionState.launchPermissionRequest()
            alreadyRequestedMicrophonePermissionOnce = true
            speechRecognizerViewModel.setShowInsufficientPermissionsError(false)
        }
    }

    LaunchedEffect(speechRecognizerUiState.showRecognizerBusyOrClientError) {
        if (speechRecognizerUiState.showRecognizerBusyOrClientError) {
            showSnackbarError(
                "Recognition is finishing, please wait or cancel.",
                null,
                true,
                SnackbarDuration.Short,
            )
            speechRecognizerViewModel.setShowRecognizerBusyOrClientError(false)
        }
    }

    LaunchedEffect(
        microphonePermissionState.status.isGranted,
        request,
    ) {
        if (
            request != null &&
            !speechRecognizerUiState.isRecognizing &&
            (!alreadyRequestedMicrophonePermissionOnce ||
                microphonePermissionState.status.isGranted)
        ) {
            speechRecognizerViewModel.startListening(request)
        }
    }

    FilledIconToggleButton(
        checked = speechRecognizerUiState.isRecognizing,
        onCheckedChange = {
            if (speechRecognizerUiState.isRecognizing) {
                speechRecognizerViewModel.stopListening()
            } else if (request != null) {
                speechRecognizerViewModel.startListening(request)
            }
        },
        modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Mic,
            contentDescription = if (speechRecognizerUiState.isRecognizing) {
                "Speech recognition active"
            } else {
                "Speech recognition inactive"
            },
            modifier = Modifier.size(165.dp),
        )
    }
}

private fun deliverResult(
    activity: Activity,
    event: ActionRecognitionTerminalEvent.Result,
    speechRecognizerViewModel: SpeechRecognizerViewModel,
) {
    val hasPendingIntent = runCatching {
        activity.intent.hasExtra(RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT)
    }.getOrElse {
        finishCanceled(activity)
        return
    }
    val deliveryContract = ActionTranscriptDeliveryContract(hasPendingIntent)
    val transcripts = arrayListOf(event.transcript)

    if (hasPendingIntent) {
        val pendingDelivery = runCatching {
            val pendingIntent = requireNotNull(activity.intent.resultsPendingIntent())
            val pendingIntentResult = Intent().apply {
                // Ce bundle appartient exclusivement au PendingIntent. Le
                // copier protège l'Intent d'entrée et toute erreur de parcel
                // ferme le contrat sans repli vers le résultat Activity.
                activity.intent.getBundleExtra(
                    RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT_BUNDLE,
                )?.let { putExtras(Bundle(it)) }
                putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, transcripts)
            }
            pendingIntent to pendingIntentResult
        }.getOrElse {
            deliveryContract.rejectInvalidPendingPayload()
            finishCanceled(activity)
            return
        }

        // La lecture est volontairement le dernier travail effectué avant
        // l'unique appel send(): aucun autre canal ne reçoit le transcript.
        when (
            deliveryContract.begin(
                PrivacyConsent.isAcceptedBlocking(activity.applicationContext),
            )
        ) {
            ActionTranscriptDeliveryContract.Action.SEND_PENDING_INTENT -> {
                val outcome = try {
                    pendingDelivery.first.send(
                        activity,
                        Activity.RESULT_OK,
                        pendingDelivery.second,
                    )
                    ActionTranscriptDeliveryContract.PendingOutcome.SENT
                } catch (_: PendingIntent.CanceledException) {
                    ActionTranscriptDeliveryContract.PendingOutcome.CANCELED
                } catch (_: Exception) {
                    ActionTranscriptDeliveryContract.PendingOutcome.FAILED
                }
                when (deliveryContract.completePending(outcome)) {
                    ActionTranscriptDeliveryContract.Action.FINISH_WITHOUT_ACTIVITY_PAYLOAD -> {
                        finishWithoutActivityPayload(activity)
                    }

                    ActionTranscriptDeliveryContract.Action.CANCEL_ACTIVITY -> {
                        finishCanceled(activity)
                    }

                    else -> finishCanceled(activity)
                }
            }

            ActionTranscriptDeliveryContract.Action.CANCEL_ACTIVITY -> {
                cancelForRevocationAndFinish(activity, speechRecognizerViewModel)
            }

            else -> finishCanceled(activity)
        }
        return
    }

    val activityResult = Intent().apply {
        putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, transcripts)
    }
    // Sans PendingIntent, cette lecture précède immédiatement setResult().
    // MainRecognitionService ne fournit aucun score: ne pas en inventer un.
    when (
        deliveryContract.begin(
            PrivacyConsent.isAcceptedBlocking(activity.applicationContext),
        )
    ) {
        ActionTranscriptDeliveryContract.Action.SET_ACTIVITY_RESULT -> {
            activity.setResult(Activity.RESULT_OK, activityResult)
            activity.finish()
        }

        ActionTranscriptDeliveryContract.Action.CANCEL_ACTIVITY -> {
            cancelForRevocationAndFinish(activity, speechRecognizerViewModel)
        }

        else -> finishCanceled(activity)
    }
}

private fun Intent.resultsPendingIntent(): PendingIntent? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(
            RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT,
            PendingIntent::class.java,
        )
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT)
    }

private fun finishCanceled(activity: Activity) {
    // Aucun Intent de résultat: une révocation ou une requête invalide ne doit
    // exposer ni transcript, ni bundle fourni par l'appelant.
    activity.setResult(Activity.RESULT_CANCELED)
    activity.finish()
}

private fun finishWithoutActivityPayload(activity: Activity) {
    // Le transcript a déjà été livré par le canal PendingIntent exclusif.
    activity.setResult(Activity.RESULT_OK)
    activity.finish()
}

private fun cancelForRevocationAndFinish(
    activity: Activity,
    speechRecognizerViewModel: SpeechRecognizerViewModel,
) {
    val cancellation = speechRecognizerViewModel.cancelForConsentRevocation()
    if (!speechRecognizerViewModel.claimTerminalDelivery(cancellation)) return
    finishCanceled(activity)
    speechRecognizerViewModel.completeTerminalDelivery(cancellation)
}
