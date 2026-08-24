package dev.soupslurpr.transcribro.ui.action_recognize_speech

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.soupslurpr.transcribro.R
import dev.soupslurpr.transcribro.preferences.PrivacyConsent
import dev.soupslurpr.transcribro.recognitionservice.MainRecognitionService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal sealed interface ActionRecognitionTerminalEvent {
    val id: Long

    data class Result(
        override val id: Long,
        val transcript: String,
    ) : ActionRecognitionTerminalEvent

    data class Canceled(
        override val id: Long,
    ) : ActionRecognitionTerminalEvent
}

class SpeechRecognizerViewModel(application: Application) : AndroidViewModel(application) {

    class SpeechRecognizerUiState {
        var isSpeaking by mutableStateOf(false)
        var isRecognizing by mutableStateOf(false)
        var showInsufficientPermissionsError by mutableStateOf(false)
        var showRecognizerBusyOrClientError by mutableStateOf(false)
    }

    private data class ActiveRecognizer(
        val generation: Long,
        val recognizer: SpeechRecognizer,
    )

    private val _uiState = MutableStateFlow(SpeechRecognizerUiState())
    val uiState: StateFlow<SpeechRecognizerUiState> = _uiState.asStateFlow()

    private val _terminalEvent = MutableStateFlow<ActionRecognitionTerminalEvent?>(null)
    internal val terminalEvent: StateFlow<ActionRecognitionTerminalEvent?> =
        _terminalEvent.asStateFlow()

    private val applicationContext = application.applicationContext
    private val terminalGate = ActionRecognitionTerminalGate()
    private val recognizerLock = Any()
    private val terminalEventLock = Any()
    private var activeRecognizer: ActiveRecognizer? = null
    private var terminalEventSequence = 0L
    private var claimedTerminalEventId: Long? = null
    private var revocationEvent: ActionRecognitionTerminalEvent.Canceled? = null

    private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val startedRecognitionMediaPlayer: MediaPlayer? =
        MediaPlayer.create(application, R.raw.started_recognition)
    private val stoppedRecognitionMediaPlayer: MediaPlayer? =
        MediaPlayer.create(application, R.raw.stopped_recognition)

    init {
        // Le ViewModel survit aux rotations. Sa collecte reste donc attachée à
        // la tentative, pas à une ancienne Activity capturée par un listener.
        viewModelScope.launch {
            PrivacyConsent.acceptanceFlow(applicationContext).collect { accepted ->
                if (!accepted) cancelForConsentRevocation()
            }
        }
    }

    fun setShowInsufficientPermissionsError(value: Boolean) {
        _uiState.value.showInsufficientPermissionsError = value
    }

    fun setShowRecognizerBusyOrClientError(value: Boolean) {
        _uiState.value.showRecognizerBusyOrClientError = value
    }

    /**
     * Démarre une tentative isolée. Chaque génération possède son propre
     * SpeechRecognizer et son propre listener, car onError() ne transporte
     * aucun identifiant de session permettant de distinguer A de B.
     */
    internal fun startListening(request: ActionRecognitionRequest): Boolean {
        if (!PrivacyConsent.isAcceptedBlocking(applicationContext)) {
            cancelForConsentRevocation()
            return false
        }
        synchronized(terminalEventLock) {
            if (_terminalEvent.value != null) return false
        }

        val generation = terminalGate.beginAttempt() ?: return false
        setIsRecognizing(true)
        setIsSpeaking(false)

        val recognizer = try {
            SpeechRecognizer.createSpeechRecognizer(
                applicationContext,
                ComponentName(applicationContext, MainRecognitionService::class.java),
            )
        } catch (_: RuntimeException) {
            finishError(generation, SpeechRecognizer.ERROR_CLIENT)
            return false
        }

        synchronized(recognizerLock) {
            activeRecognizer = ActiveRecognizer(generation, recognizer)
        }

        if (!PrivacyConsent.isAcceptedBlocking(applicationContext)) {
            if (terminalGate.claimCancellation(generation)) {
                cleanupRecognizer(generation)
            }
            cancelForConsentRevocation()
            return false
        }

        val listener = AttemptRecognitionListener(generation)
        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            // Allowlist volontaire: le contrat public requis et les deux
            // réglages effectivement lus par MainRecognitionService. PROMPT,
            // LANGUAGE et MAX_RESULTS ont été validés à l'entrée, mais ne sont
            // pas recopiés: l'UI minimale n'affiche pas le prompt, n'impose pas
            // la langue et le service produit actuellement un seul résultat.
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, request.languageModel)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, request.partialResults)
            putExtra(MainRecognitionService.EXTRA_AUTO_STOP, request.autoStop)
        }

        return try {
            recognizer.setRecognitionListener(listener)
            recognizer.startListening(recognizerIntent)
            true
        } catch (_: RuntimeException) {
            finishError(generation, SpeechRecognizer.ERROR_CLIENT)
            false
        }
    }

    fun stopListening() {
        val active = synchronized(recognizerLock) { activeRecognizer } ?: return
        if (terminalGate.requestStop(active.generation)) {
            runCatching { active.recognizer.stopListening() }
                .onFailure { finishError(active.generation, SpeechRecognizer.ERROR_CLIENT) }
            return
        }

        // Un arrêt demandé avant onReadyForSpeech() est une annulation locale.
        // Le callback tardif du recognizer détruit est rejeté par sa génération.
        if (terminalGate.claimCancellation(active.generation)) {
            cleanupRecognizer(active.generation)
            setIsSpeaking(false)
            setIsRecognizing(false)
            playStoppedSound()
        }
    }

    @Synchronized
    internal fun cancelForConsentRevocation(): ActionRecognitionTerminalEvent.Canceled {
        revocationEvent?.let { return it }
        val active = synchronized(recognizerLock) { activeRecognizer }
        if (active != null && terminalGate.claimCancellation(active.generation)) {
            cleanupRecognizer(active.generation)
            setIsSpeaking(false)
            setIsRecognizing(false)
            playStoppedSound()
        }
        return publishCancellation().also { event -> revocationEvent = event }
    }

    internal fun claimTerminalDelivery(event: ActionRecognitionTerminalEvent): Boolean =
        synchronized(terminalEventLock) {
            if (_terminalEvent.value != event || claimedTerminalEventId == event.id) {
                false
            } else {
                claimedTerminalEventId = event.id
                true
            }
        }

    internal fun completeTerminalDelivery(event: ActionRecognitionTerminalEvent) {
        synchronized(terminalEventLock) {
            if (_terminalEvent.value == event) {
                _terminalEvent.value = null
            }
        }
    }

    private inner class AttemptRecognitionListener(
        private val generation: Long,
    ) : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            val sessionId = params?.getString(MainRecognitionService.EXTRA_SESSION_ID)
            if (!terminalGate.activate(generation, sessionId)) {
                // Un identifiant absent sur la génération courante est une
                // violation de contrat; un callback ancien reste sans effet.
                finishError(generation, SpeechRecognizer.ERROR_CLIENT)
                return
            }
            setIsRecognizing(true)
            playStartedSound()
        }

        override fun onBeginningOfSpeech() {
            if (terminalGate.accepts(generation)) setIsSpeaking(true)
        }

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            if (terminalGate.accepts(generation)) setIsSpeaking(false)
        }

        override fun onError(error: Int) {
            finishError(generation, error)
        }

        override fun onResults(results: Bundle?) {
            val sessionId = results?.getString(MainRecognitionService.EXTRA_SESSION_ID)
            if (!terminalGate.claimResult(generation, sessionId)) {
                // Un mauvais identifiant sur la génération courante ferme la
                // tentative; les callbacks d'une ancienne génération échouent
                // aussi claimError() et ne touchent donc jamais la suivante.
                finishError(generation, SpeechRecognizer.ERROR_CLIENT)
                return
            }

            cleanupRecognizer(generation)
            setIsSpeaking(false)
            setIsRecognizing(false)
            playStoppedSound()

            if (!PrivacyConsent.isAcceptedBlocking(applicationContext)) {
                publishCancellation()
                return
            }
            val transcript = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (transcript.isBlank()) {
                publishCancellation()
            } else {
                publishResult(transcript)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) = Unit

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun finishError(generation: Long, error: Int) {
        if (!terminalGate.claimError(generation)) return
        cleanupRecognizer(generation)
        setIsSpeaking(false)
        setIsRecognizing(false)
        playStoppedSound()

        when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                setShowInsufficientPermissionsError(true)
            }

            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_CLIENT -> setShowRecognizerBusyOrClientError(true)

            else -> Unit
        }
    }

    private fun cleanupRecognizer(generation: Long) {
        val recognizer = synchronized(recognizerLock) {
            val active = activeRecognizer
            if (active?.generation != generation) return
            activeRecognizer = null
            active.recognizer
        }
        // Le retrait atomique ci-dessus garantit exactement un cleanup même
        // si cancel() provoque immédiatement un callback onError().
        runCatching { recognizer.cancel() }
        runCatching { recognizer.destroy() }
    }

    private fun publishResult(transcript: String) {
        synchronized(terminalEventLock) {
            terminalEventSequence += 1L
            claimedTerminalEventId = null
            _terminalEvent.value = ActionRecognitionTerminalEvent.Result(
                id = terminalEventSequence,
                transcript = transcript,
            )
        }
    }

    private fun publishCancellation(): ActionRecognitionTerminalEvent.Canceled =
        synchronized(terminalEventLock) {
            val current = _terminalEvent.value
            if (current is ActionRecognitionTerminalEvent.Canceled) return@synchronized current
            terminalEventSequence += 1L
            claimedTerminalEventId = null
            // Le remplacement retire aussi de la mémoire tout transcript
            // terminal qui attendait encore d'être livré.
            ActionRecognitionTerminalEvent.Canceled(
                id = terminalEventSequence,
            ).also { event -> _terminalEvent.value = event }
        }

    private fun setIsRecognizing(value: Boolean) {
        _uiState.value.isRecognizing = value
    }

    private fun setIsSpeaking(value: Boolean) {
        _uiState.value.isSpeaking = value
    }

    private fun playStartedSound() {
        if (audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            startedRecognitionMediaPlayer?.let { player -> runCatching { player.start() } }
        }
    }

    private fun playStoppedSound() {
        if (audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            stoppedRecognitionMediaPlayer?.let { player -> runCatching { player.start() } }
        }
    }

    override fun onCleared() {
        terminalGate.invalidate()
        val recognizer = synchronized(recognizerLock) {
            val current = activeRecognizer?.recognizer
            activeRecognizer = null
            current
        }
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        startedRecognitionMediaPlayer?.release()
        stoppedRecognitionMediaPlayer?.release()
        super.onCleared()
    }
}
