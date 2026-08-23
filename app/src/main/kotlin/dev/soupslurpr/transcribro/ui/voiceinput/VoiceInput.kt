package dev.soupslurpr.transcribro.ui.voiceinput

import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration.ORIENTATION_PORTRAIT
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.SpeechRecognizer.createSpeechRecognizer
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration.getKeyRepeatDelay
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.Toast
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowRightAlt
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.automirrored.outlined.KeyboardReturn
import androidx.compose.material.icons.automirrored.outlined.NavigateBefore
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.soupslurpr.transcribro.MainActivity
import dev.soupslurpr.transcribro.R
import dev.soupslurpr.transcribro.dataStore
import dev.soupslurpr.transcribro.preferences.PrivacyConsent
import dev.soupslurpr.transcribro.preferences.PreferencesViewModel
import dev.soupslurpr.transcribro.recognitionservice.MainRecognitionService
import dev.soupslurpr.transcribro.recognitionservice.RecognitionSessionTracker
import dev.soupslurpr.transcribro.overlay.TextInsertionComposer
import dev.soupslurpr.transcribro.overlay.RecognizerCommandBoundary
import dev.soupslurpr.transcribro.ui.reusablecomposables.ScreenLazyColumn
import dev.soupslurpr.transcribro.ui.reusablecomposables.longPressableKey
import dev.soupslurpr.transcribro.ui.theme.TranscribroTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

private var speechRecognizer: MutableState<SpeechRecognizer?> = mutableStateOf(null)

private var isRecognizing by mutableStateOf(false)

private var showInsufficientPermissionsError by mutableStateOf(false)

private var isSpeaking by mutableStateOf(false)

/** Effet observable à appliquer à l'éditeur pour un événement de reconnaissance. */
data class ImeTranscriptionUpdate(
    val textToCommit: String,
    val replaceSelection: Boolean,
    val terminal: Boolean,
    val shouldAutoSend: Boolean,
)

enum class ImeAttemptState { IDLE, PENDING, ACTIVE, STOPPING }

/** Génération locale couvrant même les callbacks Android sans Bundle/UUID. */
class ImeAttemptGate {
    private var generation = 0L
    private var state = ImeAttemptState.IDLE

    @Synchronized
    fun begin(): Long? {
        if (state != ImeAttemptState.IDLE) return null
        generation += 1
        state = ImeAttemptState.PENDING
        return generation
    }

    @Synchronized
    fun activate(attempt: Long): Boolean {
        if (attempt != generation || state != ImeAttemptState.PENDING) return false
        state = ImeAttemptState.ACTIVE
        return true
    }

    @Synchronized
    fun requestStop(attempt: Long): Boolean {
        if (attempt != generation || state != ImeAttemptState.ACTIVE) return false
        state = ImeAttemptState.STOPPING
        return true
    }

    @Synchronized
    fun accepts(attempt: Long): Boolean =
        attempt == generation && state != ImeAttemptState.IDLE

    @Synchronized
    fun finish(attempt: Long): Boolean {
        if (!accepts(attempt)) return false
        generation += 1
        state = ImeAttemptState.IDLE
        return true
    }

    @Synchronized
    fun cancel() {
        generation += 1
        state = ImeAttemptState.IDLE
    }

    @Synchronized
    fun isBusy(): Boolean = state != ImeAttemptState.IDLE

    @Synchronized
    fun currentState(): ImeAttemptState = state
}

/** Un échec Binder pendant stop rend immédiatement la tentative réutilisable. */
internal object ImeStopCommandBoundary {
    fun requestStop(
        gate: ImeAttemptGate,
        attempt: Long,
        stop: () -> Unit,
        onFailure: () -> Unit,
    ): Boolean {
        if (!gate.requestStop(attempt)) return false
        return RecognizerCommandBoundary.execute(
            command = stop,
            onFailure = {
                gate.cancel()
                onFailure()
            },
        )
    }
}

object ImeCommitDecision {
    fun shouldAutoSend(
        update: ImeTranscriptionUpdate,
        commitSucceeded: Boolean,
        editorCurrent: Boolean,
        consentAccepted: Boolean,
    ): Boolean = update.shouldAutoSend &&
        postCommitGuaranteesHold(
            update = update,
            commitSucceeded = commitSucceeded,
            editorCurrent = editorCurrent,
            consentAccepted = consentAccepted,
        )

    fun shouldAutoSwitch(
        update: ImeTranscriptionUpdate,
        autoSwitchEnabled: Boolean,
        commitSucceeded: Boolean,
        editorCurrent: Boolean,
        consentAccepted: Boolean,
    ): Boolean = autoSwitchEnabled &&
        postCommitGuaranteesHold(
            update = update,
            commitSucceeded = commitSucceeded,
            editorCurrent = editorCurrent,
            consentAccepted = consentAccepted,
        )

    private fun postCommitGuaranteesHold(
        update: ImeTranscriptionUpdate,
        commitSucceeded: Boolean,
        editorCurrent: Boolean,
        consentAccepted: Boolean,
    ): Boolean =
        update.terminal &&
        update.textToCommit.isNotEmpty() &&
        commitSucceeded &&
        editorCurrent &&
        consentAccepted
}

enum class ImeFinalIdentityDecision {
    PROCESS,
    TERMINATE_FAIL_CLOSED;

    companion object {
        fun fromSessionCompletion(sessionCompleted: Boolean): ImeFinalIdentityDecision =
            if (sessionCompleted) PROCESS else TERMINATE_FAIL_CLOSED
    }
}

/**
 * Contrat pur entre les résultats cumulatifs Android et l'unique commit IME.
 *
 * Les partiels ne modifient jamais l'application hôte : Android peut réviser
 * leur préfixe et un commit incrémental ne peut alors être réparé sans risquer
 * d'effacer le texte de l'utilisateur. Seul le résultat final est inséré.
 */
class ImeTranscriptionSession {
    private var terminalSeen = false

    @Synchronized
    fun reset() {
        terminalSeen = false
    }

    @Synchronized
    fun onPartial(cumulativeText: String, selectionActive: Boolean): ImeTranscriptionUpdate =
        ImeTranscriptionUpdate(
            textToCommit = "",
            replaceSelection = false,
            terminal = false,
            shouldAutoSend = false,
        )

    @Synchronized
    fun onFinal(
        cumulativeText: String,
        selectionActive: Boolean,
        autoSendEnabled: Boolean,
    ): ImeTranscriptionUpdate {
        if (terminalSeen) {
            return ImeTranscriptionUpdate(
                textToCommit = "",
                replaceSelection = false,
                terminal = false,
                shouldAutoSend = false,
            )
        }
        terminalSeen = true
        val finalText = cumulativeText.takeIf { it.isNotBlank() }.orEmpty()

        return ImeTranscriptionUpdate(
            textToCommit = finalText,
            replaceSelection = finalText.isNotEmpty() && selectionActive,
            terminal = true,
            shouldAutoSend = autoSendEnabled && finalText.isNotEmpty(),
        )
    }
}

class VoiceInput : InputMethodService() {
    private val voiceInputLifecycleOwner = VoiceInputLifecycleOwner()
    private val transcriptionSession = ImeTranscriptionSession()
    private val recognitionSessions = RecognitionSessionTracker()
    private val attemptGate = ImeAttemptGate()
    private var editorEpoch = 0L
    private var currentAttempt: Long? = null
    private var pendingRecognitionEditorEpoch: Long? = null
    private var activeRecognitionEditorEpoch: Long? = null

    override fun onCreate() {
        super.onCreate()
        voiceInputLifecycleOwner.onCreate()
    }

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreateInputView(): View {
        voiceInputLifecycleOwner.attachToDecorView(window?.window?.decorView)

        val view = ComposeView(this)

        view.setContent {
            val context = LocalContext.current

            val audioManager = context.getSystemService(AudioManager::class.java)

            val startedRecognitionMediaPlayer = remember {
                MediaPlayer.create(context, R.raw.started_recognition)
            }

            val stoppedRecognitionMediaPlayer = remember {
                MediaPlayer.create(context, R.raw.stopped_recognition)
            }

            val preferencesViewModel: PreferencesViewModel = viewModel(
                factory = PreferencesViewModel.PreferencesViewModelFactory(dataStore)
            )

            val preferencesUiState by preferencesViewModel.uiState.collectAsState()

            val acceptedPrivacyPolicyAndLicense = preferencesUiState.acceptedPrivacyPolicyAndLicense.second.value

            val autoStopRecognition by preferencesUiState.autoStopRecognition.second

            val autoStartRecognition by preferencesUiState.autoStartRecognition.second

            val snackbarHostState = remember { SnackbarHostState() }

            val snackbarCoroutine = rememberCoroutineScope()

            var snackbarJob: Job? by remember {
                mutableStateOf(null)
            }

            fun cancelSnackbarJobAndLaunch(
                message: String,
                actionLabel: String? = null,
                withDismissAction: Boolean = false,
                duration: SnackbarDuration =
                    if (actionLabel == null) SnackbarDuration.Short else SnackbarDuration.Indefinite
            ) {
                snackbarJob?.cancel()
                snackbarJob = snackbarCoroutine.launch {
                    snackbarHostState.showSnackbar(
                        message,
                        actionLabel,
                        withDismissAction,
                        duration
                    )
                }
            }

            val maxHeight = if (LocalConfiguration.current.orientation == ORIENTATION_PORTRAIT) {
                LocalConfiguration.current.screenHeightDp.dp * 0.45f
            } else {
                LocalConfiguration.current.screenHeightDp.dp * 0.65f
            }

            TranscribroTheme(preferencesViewModel = preferencesViewModel) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(maxHeight)
                ) {
                    Scaffold(
                        snackbarHost = {
                            SnackbarHost(snackbarHostState)
                        },
                    ) { innerPadding ->
                        Box(
                            modifier = Modifier
                                .padding(innerPadding)
                                .padding(8.dp)
                        ) {
                            LaunchedEffect(acceptedPrivacyPolicyAndLicense) {
                                if (!acceptedPrivacyPolicyAndLicense) {
                                    cancelRecognition()
                                }
                            }
                            if (!acceptedPrivacyPolicyAndLicense) {
                                ScreenLazyColumn {
                                    item {
                                        Text("Please accept the privacy policy and license first!")
                                    }
                                    item {
                                        Button(
                                            onClick = {
                                                startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName))
                                            }
                                        ) {
                                            Text("Open Chuchote Flow")
                                        }
                                    }
                                }
                            } else if (showInsufficientPermissionsError) {
                                ScreenLazyColumn {
                                    item {
                                        Text(
                                            "Please grant \"Allow only while using the app\" microphone permission in " +
                                                    "settings to continue."
                                        )
                                    }
                                    item {
                                        Button(
                                            onClick = {
                                                val intent = Intent(
                                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                    Uri.fromParts("package", context.packageName, null)
                                                )

                                                intent.addCategory(Intent.CATEGORY_DEFAULT)

                                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                                                startActivity(intent)

                                                showInsufficientPermissionsError = false
                                            }
                                        ) {
                                            Text("Open App Settings")
                                        }
                                    }
                                }
                            } else {
                                LaunchedEffect(Unit) {
                                    if (autoStartRecognition && !attemptGate.isBusy()) {
                                        startRecognition(
                                            longForm = autoStopRecognition,
                                            autoSendEnabled = preferencesUiState
                                                .autoSendTranscription.second.value,
                                            autoSwitchEnabled = preferencesUiState
                                                .autoSwitchToPreviousInputMethod.second.value,
                                            audioManager = audioManager,
                                            startedPlayer = startedRecognitionMediaPlayer,
                                            stoppedPlayer = stoppedRecognitionMediaPlayer,
                                            onBusy = {
                                                cancelSnackbarJobAndLaunch(
                                                    "Recognition is finishing, please wait or cancel.",
                                                    withDismissAction = true,
                                                    duration = SnackbarDuration.Short,
                                                )
                                            },
                                        )
                                    }
                                }

                                Column(
                                    Modifier
                                        .fillMaxSize()
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(
                                            Modifier.weight(1f)
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .weight(0.25f)
                                                    .padding(bottom = 6.dp),
                                                verticalAlignment = Alignment.Top,
                                                horizontalArrangement = Arrangement.Center
                                            ) {
                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxHeight()
                                                        .fillMaxWidth()
                                                        .weight(0.5f),
                                                ) {
                                                    OutlinedIconButton(
                                                        onClick = {
                                                            cancelRecognition()

                                                            val intent = context.packageManager
                                                                .getLaunchIntentForPackage(context.packageName)!!
                                                                .apply {
                                                                    action = (Intent.ACTION_APPLICATION_PREFERENCES)
                                                                }

                                                            startActivity(intent)
                                                        },
                                                        modifier = Modifier
                                                            .fillMaxHeight()
                                                            .fillMaxWidth()
                                                            .weight(1f),
                                                        shape = RoundedCornerShape(10.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Filled.Settings,
                                                            contentDescription = "Open Chuchote Flow settings"
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.size(8.dp))
                                                    FilledTonalIconButton(
                                                        onClick = {
                                                            cancelRecognition()
                                                            switchToPreviousInputMethod()
                                                        },
                                                        modifier = Modifier
                                                            .fillMaxHeight()
                                                            .fillMaxWidth()
                                                            .weight(1f),
                                                        shape = RoundedCornerShape(10.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Filled.Keyboard,
                                                            contentDescription = "Cancel recognition and switch to the previous input method"
                                                        )
                                                    }
                                                }
                                                Spacer(modifier = Modifier.size(8.dp))
                                                OutlinedButton(
                                                    onClick = {
                                                        if (isRecognizing) {
                                                            cancelRecognition()
                                                        }
                                                    },
                                                    modifier = Modifier
                                                        .fillMaxHeight()
                                                        .fillMaxWidth()
                                                        .weight(1f),
                                                    enabled = isRecognizing,
                                                    shape = RoundedCornerShape(10.dp)
                                                ) {
                                                    Text(
                                                        "Cancel Recognition"
                                                    )
                                                }
                                            }
                                            FilledIconToggleButton(
                                                checked = isRecognizing,
                                                onCheckedChange = {
                                                    if (isRecognizing) {
                                                        requestStopRecognition()
                                                    } else {
                                                        startRecognition(
                                                            longForm = autoStopRecognition,
                                                            autoSendEnabled = preferencesUiState
                                                                .autoSendTranscription.second.value,
                                                            autoSwitchEnabled = preferencesUiState
                                                                .autoSwitchToPreviousInputMethod.second.value,
                                                            audioManager = audioManager,
                                                            startedPlayer = startedRecognitionMediaPlayer,
                                                            stoppedPlayer = stoppedRecognitionMediaPlayer,
                                                            onBusy = {
                                                                cancelSnackbarJobAndLaunch(
                                                                    "Recognition is finishing, please wait or cancel.",
                                                                    withDismissAction = true,
                                                                    duration = SnackbarDuration.Short,
                                                                )
                                                            },
                                                        )
                                                    }
                                                },
                                                modifier = Modifier
                                                    .fillMaxHeight()
                                                    .fillMaxWidth()
                                                    .weight(0.75f)
                                                    .padding(top = 2.dp),
                                                shape = RoundedCornerShape(10.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Mic,
                                                    contentDescription = if (isRecognizing) {
                                                        "Speech recognition active"
                                                    } else {
                                                        "Speech recognition inactive"
                                                    },
                                                    modifier = Modifier.size(165.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.size(8.dp))
                                        Column(
                                            modifier = Modifier.fillMaxWidth(0.4f)
                                        ) {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxHeight()
                                                    .fillMaxWidth()
                                                    .weight(1f),
                                            ) {
                                                FilledTonalButton(
                                                    onClick = {
                                                        val extractedText = currentInputConnection.getExtractedText(
                                                            ExtractedTextRequest(),
                                                            0
                                                        ).text
                                                        val beforeCursorText =
                                                            currentInputConnection.getTextBeforeCursor(
                                                                extractedText.length,
                                                                0
                                                            )
                                                        val afterCursorText = currentInputConnection.getTextAfterCursor(
                                                            extractedText.length,
                                                            0
                                                        )

                                                        if (beforeCursorText != null) {
                                                            if (afterCursorText != null) {
                                                                currentInputConnection.deleteSurroundingText(
                                                                    beforeCursorText.length,
                                                                    afterCursorText.length
                                                                )
                                                            }
                                                        }
                                                    },
                                                    modifier = Modifier
                                                        .fillMaxHeight()
                                                        .fillMaxWidth()
                                                        .weight(1f),
                                                    shape = RoundedCornerShape(10.dp)
                                                ) {
                                                    Text("Clear Unselected")
                                                }
                                                Spacer(modifier = Modifier.size(8.dp))
                                                Row(
                                                    Modifier
                                                        .fillMaxHeight()
                                                        .fillMaxWidth()
                                                        .weight(1f),
                                                ) {
                                                    val undoInteractionSource =
                                                        remember { MutableInteractionSource() }
                                                    FilledTonalIconButton(
                                                        onClick = {
                                                            val downMetaState = KeyEvent.META_CTRL_ON
                                                            val upMetaState = 0

                                                            currentInputConnection.sendKeyEvent(
                                                                KeyEvent(
                                                                    System.currentTimeMillis(),
                                                                    System.currentTimeMillis(),
                                                                    KeyEvent.ACTION_DOWN,
                                                                    KeyEvent.KEYCODE_Z,
                                                                    0,
                                                                    downMetaState
                                                                )
                                                            )
                                                            currentInputConnection.sendKeyEvent(
                                                                KeyEvent(
                                                                    System.currentTimeMillis(),
                                                                    System.currentTimeMillis(),
                                                                    KeyEvent.ACTION_UP,
                                                                    KeyEvent.KEYCODE_Z,
                                                                    0,
                                                                    upMetaState
                                                                )
                                                            )
                                                        },
                                                        modifier = Modifier
                                                            .fillMaxHeight()
                                                            .fillMaxWidth()
                                                            .weight(1f)
                                                            .longPressableKey(
                                                                interactionSource = undoInteractionSource,
                                                                onLongPress = {
                                                                    while (isActive) {
                                                                        val downMetaState =
                                                                            KeyEvent.META_CTRL_ON
                                                                        val upMetaState = 0

                                                                        currentInputConnection.sendKeyEvent(
                                                                            KeyEvent(
                                                                                System.currentTimeMillis(),
                                                                                System.currentTimeMillis(),
                                                                                KeyEvent.ACTION_DOWN,
                                                                                KeyEvent.KEYCODE_Z,
                                                                                0,
                                                                                downMetaState
                                                                            )
                                                                        )
                                                                        currentInputConnection.sendKeyEvent(
                                                                            KeyEvent(
                                                                                System.currentTimeMillis(),
                                                                                System.currentTimeMillis(),
                                                                                KeyEvent.ACTION_UP,
                                                                                KeyEvent.KEYCODE_Z,
                                                                                0,
                                                                                upMetaState
                                                                            )
                                                                        )
                                                                        delay(getKeyRepeatDelay().milliseconds)
                                                                    }
                                                                },
                                                            ),
                                                        shape = RoundedCornerShape(10.dp),
                                                        interactionSource = undoInteractionSource,
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.AutoMirrored.Outlined.Undo,
                                                            contentDescription = "Undo",
                                                            modifier = Modifier.fillMaxSize(0.5f)
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.size(8.dp))

                                                    val redoInteractionSource =
                                                        remember { MutableInteractionSource() }
                                                    FilledTonalIconButton(
                                                        onClick = {
                                                            val downMetaState =
                                                                KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON
                                                            val upMetaState = 0

                                                            currentInputConnection.sendKeyEvent(
                                                                KeyEvent(
                                                                    System.currentTimeMillis(),
                                                                    System.currentTimeMillis(),
                                                                    KeyEvent.ACTION_DOWN,
                                                                    KeyEvent.KEYCODE_Z,
                                                                    0,
                                                                    downMetaState
                                                                )
                                                            )
                                                            currentInputConnection.sendKeyEvent(
                                                                KeyEvent(
                                                                    System.currentTimeMillis(),
                                                                    System.currentTimeMillis(),
                                                                    KeyEvent.ACTION_UP,
                                                                    KeyEvent.KEYCODE_Z,
                                                                    0,
                                                                    upMetaState
                                                                )
                                                            )
                                                        },
                                                        modifier = Modifier
                                                            .fillMaxHeight()
                                                            .fillMaxWidth()
                                                            .weight(1f)
                                                            .longPressableKey(
                                                                interactionSource = redoInteractionSource,
                                                                onLongPress = {
                                                                    while (isActive) {
                                                                        val downMetaState =
                                                                            KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON
                                                                        val upMetaState =
                                                                            0

                                                                        currentInputConnection.sendKeyEvent(
                                                                            KeyEvent(
                                                                                System.currentTimeMillis(),
                                                                                System.currentTimeMillis(),
                                                                                KeyEvent.ACTION_DOWN,
                                                                                KeyEvent.KEYCODE_Z,
                                                                                0,
                                                                                downMetaState
                                                                            )
                                                                        )
                                                                        currentInputConnection.sendKeyEvent(
                                                                            KeyEvent(
                                                                                System.currentTimeMillis(),
                                                                                System.currentTimeMillis(),
                                                                                KeyEvent.ACTION_UP,
                                                                                KeyEvent.KEYCODE_Z,
                                                                                0,
                                                                                upMetaState
                                                                            )
                                                                        )
                                                                        delay(getKeyRepeatDelay().milliseconds)
                                                                    }
                                                                }
                                                            ),
                                                        shape = RoundedCornerShape(10.dp),
                                                        interactionSource = redoInteractionSource
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.AutoMirrored.Outlined.Redo,
                                                            contentDescription = "Redo",
                                                            modifier = Modifier.fillMaxSize(0.5f)
                                                        )
                                                    }
                                                }
                                            }
                                            Spacer(modifier = Modifier.size(8.dp))
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxHeight()
                                                    .fillMaxWidth()
                                                    .weight(1f)
                                            ) {
                                                val backspaceInteractionSource =
                                                    remember { MutableInteractionSource() }
                                                FilledTonalIconButton(
                                                    onClick = {
                                                        val selectedText =
                                                            currentInputConnection.getSelectedText(0)
                                                        if (selectedText.isNullOrEmpty()) {
                                                            currentInputConnection.deleteSurroundingText(1, 0)
                                                        } else {
                                                            currentInputConnection.commitText("", 1)
                                                        }
                                                    },
                                                    modifier = Modifier
                                                        .fillMaxHeight()
                                                        .fillMaxWidth()
                                                        .weight(1f)
                                                        .longPressableKey(
                                                            interactionSource = backspaceInteractionSource,
                                                            onLongPress = {
                                                                while (isActive) {
                                                                    val selectedText =
                                                                        currentInputConnection.getSelectedText(0)
                                                                    if (selectedText.isNullOrEmpty()) {
                                                                        currentInputConnection.deleteSurroundingText(1, 0)
                                                                    } else {
                                                                        currentInputConnection.commitText("", 1)
                                                                    }
                                                                    delay(getKeyRepeatDelay().milliseconds)
                                                                }
                                                            },
                                                        ),
                                                    shape = RoundedCornerShape(10.dp),
                                                    interactionSource = backspaceInteractionSource,
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.AutoMirrored.Outlined.Backspace,
                                                        contentDescription = "Backspace",
                                                        modifier = Modifier.fillMaxSize(0.5f)
                                                    )
                                                }

                                                Spacer(modifier = Modifier.size(8.dp))

                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxHeight()
                                                        .fillMaxWidth()
                                                        .weight(1f),
                                                ) {
                                                    // TODO: why is the actionId seemingly always 0???
                                                    val (actionKeyIcon, actionKeyContentDescription) = when (currentInputEditorInfo.actionId) {
                                                        EditorInfo.IME_ACTION_NEXT -> Pair(
                                                            Icons.AutoMirrored.Outlined.NavigateNext,
                                                            "Next"
                                                        )

                                                        EditorInfo.IME_ACTION_GO -> Pair(
                                                            Icons.AutoMirrored.Outlined.ArrowRightAlt,
                                                            "Go"
                                                        )

                                                        EditorInfo.IME_ACTION_SEND -> Pair(
                                                            Icons.AutoMirrored.Outlined.Send,
                                                            "Send"
                                                        )

                                                        EditorInfo.IME_ACTION_DONE -> Pair(Icons.Outlined.Done, "Done")
                                                        EditorInfo.IME_ACTION_NONE -> Pair(Icons.Outlined.Block, "None")
                                                        EditorInfo.IME_ACTION_PREVIOUS -> Pair(
                                                            Icons.AutoMirrored.Outlined.NavigateBefore,
                                                            "Previous"
                                                        )

                                                        EditorInfo.IME_ACTION_SEARCH -> Pair(
                                                            Icons.Outlined.Search,
                                                            "Search"
                                                        )

                                                        else -> Pair(Icons.AutoMirrored.Outlined.Send, "Send")
                                                    }

                                                    FilledTonalIconButton(
                                                        onClick = {
                                                            if (actionKeyContentDescription == "Send") {
                                                                currentInputConnection.performEditorAction(EditorInfo.IME_ACTION_SEND)
                                                            } else {
                                                                currentInputConnection.performEditorAction(
                                                                    currentInputEditorInfo.actionId
                                                                )
                                                            }
                                                        },
                                                        modifier = Modifier
                                                            .fillMaxHeight()
                                                            .fillMaxWidth()
                                                            .weight(1f),
                                                        shape = RoundedCornerShape(10.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = actionKeyIcon,
                                                            contentDescription = actionKeyContentDescription,
                                                            modifier = Modifier.fillMaxSize(0.5f)
                                                        )
                                                    }

                                                    Spacer(modifier = Modifier.size(8.dp))

                                                    FilledTonalIconButton(
                                                        onClick = {
                                                            currentInputConnection.sendKeyEvent(
                                                                KeyEvent(
                                                                    KeyEvent.ACTION_DOWN,
                                                                    KeyEvent.KEYCODE_ENTER
                                                                )
                                                            )
                                                            currentInputConnection.sendKeyEvent(
                                                                KeyEvent(
                                                                    KeyEvent.ACTION_UP,
                                                                    KeyEvent.KEYCODE_ENTER
                                                                )
                                                            )
                                                        },
                                                        modifier = Modifier
                                                            .fillMaxHeight()
                                                            .fillMaxWidth()
                                                            .weight(1f),
                                                        shape = RoundedCornerShape(10.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.AutoMirrored.Outlined.KeyboardReturn,
                                                            contentDescription = "Return",
                                                            modifier = Modifier.fillMaxSize(0.5f)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return view
    }

    override fun onStartInputView(editorInfo: EditorInfo?, restarting: Boolean) {
        voiceInputLifecycleOwner.onResume()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        voiceInputLifecycleOwner.onPause()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        editorEpoch += 1
        cancelRecognition()
    }

    override fun onFinishInput() {
        editorEpoch += 1
        cancelRecognition()
        super.onFinishInput()
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceInputLifecycleOwner.onDestroy()

        cancelRecognition()
    }

    private fun getStartListeningIntent(longForm: Boolean): Intent {
        return Intent().apply {
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(MainRecognitionService.EXTRA_AUTO_STOP, longForm)
        }
    }

    private fun startRecognition(
        longForm: Boolean,
        autoSendEnabled: Boolean,
        autoSwitchEnabled: Boolean,
        audioManager: AudioManager,
        startedPlayer: MediaPlayer,
        stoppedPlayer: MediaPlayer,
        onBusy: () -> Unit,
    ) {
        if (!PrivacyConsent.isAcceptedBlocking(this)) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }

        val attempt = attemptGate.begin()
        if (attempt == null) {
            onBusy()
            return
        }

        recognitionSessions.invalidate()
        activeRecognitionEditorEpoch = null
        pendingRecognitionEditorEpoch = editorEpoch
        currentAttempt = attempt
        // PENDING est déjà une tentative occupée : l'UI doit permettre de
        // l'annuler et ne doit pas proposer un second démarrage.
        isRecognizing = true

        val previousRecognizer = speechRecognizer.value
        speechRecognizer.value = null
        previousRecognizer?.let { previous ->
            RecognizerCommandBoundary.cleanup(
                cancel = { previous.cancel() },
                destroy = { previous.destroy() },
            )
        }

        val recognizer = try {
            createSpeechRecognizer(
                applicationContext,
                ComponentName(applicationContext, MainRecognitionService::class.java),
            )
        } catch (_: RuntimeException) {
            abortPendingAttempt(attempt)
            onBusy()
            return
        }

        speechRecognizer.value = recognizer
        RecognizerCommandBoundary.execute(
            command = {
                recognizer.setRecognitionListener(
                    createAttemptListener(
                        attempt = attempt,
                        attemptEditorEpoch = editorEpoch,
                        recognizer = recognizer,
                        autoSendEnabled = autoSendEnabled,
                        autoSwitchEnabled = autoSwitchEnabled,
                        audioManager = audioManager,
                        startedPlayer = startedPlayer,
                        stoppedPlayer = stoppedPlayer,
                        onBusy = onBusy,
                    ),
                )
                recognizer.startListening(getStartListeningIntent(longForm))
            },
            onFailure = {
                finishRecognitionAttempt(attempt, recognizer)
                onBusy()
            },
        )
    }

    private fun createAttemptListener(
        attempt: Long,
        attemptEditorEpoch: Long,
        recognizer: SpeechRecognizer,
        autoSendEnabled: Boolean,
        autoSwitchEnabled: Boolean,
        audioManager: AudioManager,
        startedPlayer: MediaPlayer,
        stoppedPlayer: MediaPlayer,
        onBusy: () -> Unit,
    ): RecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (!isCurrentCallback(attempt, attemptEditorEpoch, recognizer)) return
            if (!PrivacyConsent.isAcceptedBlocking(this@VoiceInput)) {
                cancelRecognition()
                return
            }
            if (!attemptGate.activate(attempt)) return
            val sessionAccepted = recognitionSessions.activate(
                params?.getString(MainRecognitionService.EXTRA_SESSION_ID),
            )
            if (!sessionAccepted) {
                cancelRecognition()
                return
            }

            activeRecognitionEditorEpoch = attemptEditorEpoch
            pendingRecognitionEditorEpoch = null
            transcriptionSession.reset()
            isRecognizing = true
            if (audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                startedPlayer.start()
            }
        }

        override fun onBeginningOfSpeech() {
            if (isCurrentCallback(attempt, attemptEditorEpoch, recognizer)) {
                isSpeaking = true
            }
        }

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            if (isCurrentCallback(attempt, attemptEditorEpoch, recognizer)) {
                isSpeaking = false
            }
        }

        override fun onError(error: Int) {
            if (!isCurrentCallback(attempt, attemptEditorEpoch, recognizer)) return
            val consentAccepted = PrivacyConsent.isAcceptedBlocking(this@VoiceInput)
            if (!finishRecognitionAttempt(attempt, recognizer)) return

            if (!consentAccepted) return
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    showInsufficientPermissionsError = true
                }

                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_CLIENT -> onBusy()

                else -> Unit
            }
        }

        override fun onResults(results: Bundle?) {
            if (!isCurrentCallback(attempt, attemptEditorEpoch, recognizer)) return
            if (!PrivacyConsent.isAcceptedBlocking(this@VoiceInput)) {
                cancelRecognition()
                return
            }

            val sessionId = results?.getString(MainRecognitionService.EXTRA_SESSION_ID)
            when (
                ImeFinalIdentityDecision.fromSessionCompletion(
                    recognitionSessions.complete(sessionId),
                )
            ) {
                ImeFinalIdentityDecision.PROCESS -> Unit
                ImeFinalIdentityDecision.TERMINATE_FAIL_CLOSED -> {
                    // Le callback est bien celui de cette génération locale,
                    // mais son identité de service est absente ou invalide.
                    // Ne rien écrire et libérer la tentative : attendre un
                    // autre callback laisserait le clavier bloqué indéfiniment.
                    finishRecognitionAttempt(attempt, recognizer)
                    return
                }
            }
            val transcription = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            val ic = currentInputConnection
            val update = transcriptionSession.onFinal(
                cumulativeText = transcription,
                selectionActive = !ic?.getSelectedText(0).isNullOrEmpty(),
                autoSendEnabled = autoSendEnabled,
            )
            if (!finishRecognitionAttempt(attempt, recognizer)) return

            val editorCurrent = attemptEditorEpoch == editorEpoch &&
                ic != null &&
                currentInputConnection === ic
            val consentBeforeCommit = PrivacyConsent.isAcceptedBlocking(this@VoiceInput)
            val commitSucceeded = editorCurrent &&
                consentBeforeCommit &&
                ic != null &&
                applyImeUpdate(ic, update)
            val editorCurrentBeforeSend = attemptEditorEpoch == editorEpoch &&
                ic != null &&
                currentInputConnection === ic
            val consentBeforeSend = PrivacyConsent.isAcceptedBlocking(this@VoiceInput)
            if (
                ic != null &&
                ImeCommitDecision.shouldAutoSend(
                    update = update,
                    commitSucceeded = commitSucceeded,
                    editorCurrent = editorCurrentBeforeSend,
                    consentAccepted = consentBeforeSend,
                )
            ) {
                ic.performEditorAction(EditorInfo.IME_ACTION_SEND)
            }

            if (update.terminal && audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                stoppedPlayer.start()
            }
            val editorCurrentBeforeSwitch = attemptEditorEpoch == editorEpoch &&
                ic != null &&
                currentInputConnection === ic
            val consentBeforeSwitch = PrivacyConsent.isAcceptedBlocking(this@VoiceInput)
            if (
                ImeCommitDecision.shouldAutoSwitch(
                    update = update,
                    autoSwitchEnabled = autoSwitchEnabled,
                    commitSucceeded = commitSucceeded,
                    editorCurrent = editorCurrentBeforeSwitch,
                    consentAccepted = consentBeforeSwitch,
                )
            ) {
                switchToPreviousInputMethod()
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!isCurrentCallback(attempt, attemptEditorEpoch, recognizer)) return
            if (!PrivacyConsent.isAcceptedBlocking(this@VoiceInput)) {
                cancelRecognition()
                return
            }
            val sessionId = partialResults?.getString(MainRecognitionService.EXTRA_SESSION_ID)
            if (!recognitionSessions.accepts(sessionId)) return
            transcriptionSession.onPartial(
                cumulativeText = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty(),
                selectionActive = false,
            )
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun isCurrentCallback(
        attempt: Long,
        attemptEditorEpoch: Long,
        recognizer: SpeechRecognizer,
    ): Boolean = currentAttempt == attempt &&
        attemptGate.accepts(attempt) &&
        attemptEditorEpoch == editorEpoch &&
        speechRecognizer.value === recognizer

    private fun requestStopRecognition() {
        val attempt = currentAttempt ?: return
        when (attemptGate.currentState()) {
            ImeAttemptState.PENDING -> cancelRecognition()
            ImeAttemptState.ACTIVE -> {
                val recognizer = speechRecognizer.value
                if (recognizer == null) {
                    cancelRecognition()
                    Toast.makeText(
                        this,
                        "Connexion vocale interrompue; tu peux recommencer.",
                        Toast.LENGTH_SHORT,
                    ).show()
                    return
                }
                ImeStopCommandBoundary.requestStop(
                    gate = attemptGate,
                    attempt = attempt,
                    stop = { recognizer.stopListening() },
                    onFailure = {
                        cancelRecognition()
                        Toast.makeText(
                            this,
                            "Connexion vocale interrompue; tu peux recommencer.",
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                )
            }

            ImeAttemptState.IDLE,
            ImeAttemptState.STOPPING -> Unit
        }
    }

    private fun abortPendingAttempt(attempt: Long) {
        if (!attemptGate.finish(attempt)) return
        currentAttempt = null
        pendingRecognitionEditorEpoch = null
        activeRecognitionEditorEpoch = null
        recognitionSessions.invalidate()
        isRecognizing = false
        isSpeaking = false
    }

    private fun finishRecognitionAttempt(
        attempt: Long,
        recognizer: SpeechRecognizer,
    ): Boolean {
        if (currentAttempt != attempt || speechRecognizer.value !== recognizer) return false
        if (!attemptGate.finish(attempt)) return false

        currentAttempt = null
        pendingRecognitionEditorEpoch = null
        activeRecognitionEditorEpoch = null
        recognitionSessions.invalidate()
        speechRecognizer.value = null
        RecognizerCommandBoundary.execute(command = { recognizer.destroy() })
        isRecognizing = false
        isSpeaking = false
        return true
    }

    private fun cancelRecognition() {
        attemptGate.cancel()
        currentAttempt = null
        pendingRecognitionEditorEpoch = null
        activeRecognitionEditorEpoch = null
        recognitionSessions.invalidate()
        val recognizer = speechRecognizer.value
        speechRecognizer.value = null
        recognizer?.let { active ->
            RecognizerCommandBoundary.cleanup(
                cancel = { active.cancel() },
                destroy = { active.destroy() },
            )
        }
        isRecognizing = false
        isSpeaking = false
    }
}

private fun applyImeUpdate(ic: InputConnection, update: ImeTranscriptionUpdate): Boolean {
    val transcription = update.textToCommit
    if (transcription.isEmpty()) return false

    var textToCommit = if (
        ic.getTextBeforeCursor(2, 0) == "" ||
        ic.getTextBeforeCursor(1, 0) == "\n" ||
        ic.getTextBeforeCursor(1, 0) == " "
    ) {
        transcription.removePrefix(" ")
    } else {
        transcription
    }
    if (textToCommit.isEmpty()) return false

    val selectedText = if (update.replaceSelection) ic.getSelectedText(0) else null
    val readdUppercase = textToCommit
        .filter { it.isLetter() }
        .firstOrNull { !it.isUpperCase() } == null

    if (!selectedText.isNullOrEmpty()) {
        val trimmedTranscription = textToCommit.trim()
        val firstContent = trimmedTranscription.indexOfFirst { it.isLetterOrDigit() }
        val lastContent = trimmedTranscription.indexOfLast { it.isLetterOrDigit() }
        if (firstContent >= 0 && lastContent >= firstContent) {
            textToCommit = trimmedTranscription.substring(firstContent, lastContent + 1)
        }

        val selectedTrimmed = selectedText.trim()
        val firstSelectedCharacter = selectedTrimmed.firstOrNull()
        if (firstSelectedCharacter != null && textToCommit.isNotEmpty()) {
            textToCommit = when {
                textToCommit.all { !it.isLetter() || it.isUpperCase() } -> textToCommit
                firstSelectedCharacter.isUpperCase() ->
                    textToCommit.replaceFirstChar { it.uppercase() }
                firstSelectedCharacter.isLowerCase() ->
                    textToCommit.replaceFirstChar { it.lowercase() }
                selectedText.none { it.isLetterOrDigit() || it.isWhitespace() } ->
                    textToCommit.replaceFirstChar { it.lowercase() }
                else -> textToCommit
            }
        }

        val selectedLastContent = selectedTrimmed.indexOfLast { it.isLetterOrDigit() }
        if (selectedLastContent >= 0 && selectedLastContent < selectedTrimmed.lastIndex) {
            textToCommit += selectedTrimmed.substring(selectedLastContent + 1)
        }

        if (selectedText.none { it.isLetterOrDigit() }) {
            textToCommit = " $textToCommit"
            if (textToCommit.lastOrNull()?.isLetterOrDigit() == true) {
                textToCommit += selectedText
            }
        }
    } else {
        val twoCharactersBeforeCursor = ic.getTextBeforeCursor(2, 0)
        val firstLetterOrDigit = textToCommit.withIndex()
            .firstOrNull { it.value.isLetterOrDigit() }
        if (
            twoCharactersBeforeCursor != null &&
            twoCharactersBeforeCursor.length >= 2 &&
            twoCharactersBeforeCursor[0].isLetterOrDigit() &&
            (twoCharactersBeforeCursor[1].isLetterOrDigit() ||
                    twoCharactersBeforeCursor[1].isWhitespace()) &&
            firstLetterOrDigit != null
        ) {
            val chars = textToCommit.toCharArray()
            chars[firstLetterOrDigit.index] = firstLetterOrDigit.value.lowercaseChar()
            textToCommit = chars.concatToString()
        }
    }

    if (readdUppercase) textToCommit = textToCommit.uppercase()
    textToCommit = TextInsertionComposer.prepareForImeCommit(
        before = ic.getTextBeforeCursor(2, 0),
        after = ic.getTextAfterCursor(2, 0),
        inserted = textToCommit,
    ) ?: return false
    return textToCommit.isNotEmpty() && ic.commitText(textToCommit, 1)
}

class VoiceInputLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry: LifecycleRegistry =
        LifecycleRegistry(this)
    override val lifecycle: Lifecycle = lifecycleRegistry

    override val viewModelStore: ViewModelStore = ViewModelStore()

    private val savedStateRegistryController =
        SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry =
        savedStateRegistryController.savedStateRegistry

    fun onCreate() {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun onResume() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun onPause() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    }

    fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
    }

    fun attachToDecorView(decorView: View?) {
        if (decorView == null) return

        decorView.setViewTreeLifecycleOwner(this)
        decorView.setViewTreeViewModelStoreOwner(this)
        decorView.setViewTreeSavedStateRegistryOwner(this)
    }
}
