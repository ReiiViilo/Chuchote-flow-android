package dev.soupslurpr.transcribro.recognitionservice

import android.Manifest
import android.content.Context
import android.content.ContextParams
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import dev.soupslurpr.transcribro.memory.ChuchoteStore
import dev.soupslurpr.transcribro.preferences.PrivacyConsent
import dev.soupslurpr.transcribro.recognitionservice.audio.AudioSegment
import dev.soupslurpr.transcribro.recognitionservice.audio.AudioSegmentPlanner
import dev.soupslurpr.transcribro.recognitionservice.audio.AudioSegmentSource
import dev.soupslurpr.transcribro.recognitionservice.audio.RecoverableWavFile
import dev.soupslurpr.transcribro.recognitionservice.audio.SegmentText
import dev.soupslurpr.transcribro.recognitionservice.audio.SegmentTranscriber
import dev.soupslurpr.transcribro.recognitionservice.audio.SequentialTranscriptionPipeline
import dev.soupslurpr.transcribro.recognitionservice.audio.TranscriptionSessionGate
import dev.soupslurpr.transcribro.recognitionservice.silerovad.SileroVadApi
import dev.soupslurpr.transcribro.recognitionservice.silerovad.SileroVadDetector
import dev.soupslurpr.transcribro.recognitionservice.silerovad.SileroVadLocalDataSource
import dev.soupslurpr.transcribro.recognitionservice.silerovad.SileroVadRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.log10
import kotlin.math.sqrt

private class RecognitionSessionControl {
    @Volatile
    var stopRequested: Boolean = false
        private set

    @Volatile
    var cancelRequested: Boolean = false
        private set

    @Volatile
    var stopRequestedAtMs: Long = 0L
        private set

    fun requestStop(nowMs: Long = System.currentTimeMillis()) {
        if (stopRequestedAtMs == 0L) stopRequestedAtMs = nowMs
        stopRequested = true
    }

    fun requestCancel() {
        cancelRequested = true
        stopRequested = true
    }
}

private data class ActiveRecognitionSession(
    val job: Job,
    val gate: TranscriptionSessionGate,
    val audioRecord: AudioRecord,
    val audioCleanup: IdempotentCleanup,
    val control: RecognitionSessionControl,
)

/**
 * Service de reconnaissance à mémoire bornée.
 *
 * L'audio est d'abord écrit sur disque, puis relu par segments de 30 secondes
 * au maximum. Le VAD et Whisper sont appelés sur une seule coroutine, dans
 * l'ordre. Cette sérialisation est intentionnelle : le modèle ONNX, le contexte
 * Whisper et les collections de l'ancienne version n'étaient pas sûrs lorsque
 * plusieurs coroutines les modifiaient en parallèle.
 */
class MainRecognitionService : RecognitionService() {

    companion object {
        const val EXTRA_AUTO_STOP = "dev.soupslurpr.transcribro.EXTRA_AUTO_STOP"
        const val EXTRA_AUDIO_DURATION_MS =
            "dev.soupslurpr.transcribro.EXTRA_AUDIO_DURATION_MS"
        const val EXTRA_COMPLETED_SEGMENTS =
            "dev.soupslurpr.transcribro.EXTRA_COMPLETED_SEGMENTS"
        const val EXTRA_TOTAL_SEGMENTS =
            "dev.soupslurpr.transcribro.EXTRA_TOTAL_SEGMENTS"
        const val EXTRA_SESSION_ID =
            "dev.soupslurpr.transcribro.EXTRA_SESSION_ID"

        private const val TAG = "MainRecognitionService"
        private const val SAMPLE_RATE = 16_000
        private const val MAX_SEGMENT_SAMPLES = 480_000 // 30 secondes
        private const val SPEECH_START_PAD_SAMPLES = 24_000L
        private const val SILENCE_DB = -60f
        private const val AUDIO_DIRECTORY = "dictations"
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private val sessionCoordinator =
        RecognitionSessionCoordinator<ActiveRecognitionSession> { it.job }

    private val store by lazy { ChuchoteStore.get(this) }

    private val sileroVadRepository = SileroVadRepository(
        SileroVadLocalDataSource(
            sileroVadApi = object : SileroVadApi {
                override fun getSileroVadDetector(): SileroVadDetector {
                    val modelBytes = assets.open(
                        "models/silero_vad/silero_vad.with_runtime_opt.ort"
                    ).use { it.readBytes() }
                    return SileroVadDetector(
                        modelBytes = modelBytes,
                        startThreshold = 0.6f,
                        endThreshold = 0.45f,
                        samplingRate = SAMPLE_RATE,
                        minSilenceDurationMs = 3_000,
                        speechPadMs = 0,
                    )
                }
            },
            ioDispatcher = Dispatchers.IO,
        ),
    )

    init {
        // Le handler appartient au job racine du service : il ne crée ni scope
        // orphelin ni cleanup pouvant survivre au propriétaire Android.
        serviceJob.invokeOnCompletion {
            runCatching { sileroVadRepository.release() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Le contrôle de onStartListening ferme le démarrage; ce collecteur
        // ferme aussi une session déjà active si l'utilisateur retire ensuite
        // son consentement dans l'application.
        serviceScope.launch {
            PrivacyConsent.acceptanceFlow(this@MainRecognitionService)
                .collect { accepted ->
                    if (!accepted) {
                        sessionCoordinator.cancelCurrent(
                            reason = "Consentement retiré",
                            cancel = ::cancelSession,
                        )
                    }
                }
        }
    }

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        // Dernière barrière avant toute création de ligne, de WAV ou requête
        // relais. L'ancienne acceptation ne déverrouille jamais ce chemin.
        if (!PrivacyConsent.isAcceptedBlocking(this)) {
            Log.w(TAG, "Reconnaissance refusée: politique actuelle non acceptée")
            signalError(listener, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            return
        }

        // Un job cancelling n'est plus "active", mais son finally peut encore
        // finaliser le WAV et réinitialiser le VAD. Attendre isCompleted évite
        // que la session suivante partage cet état mutable.
        if (!sessionCoordinator.canAccept()) {
            signalError(listener, SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            return
        }

        val capture = try {
            createCapture(listener)
        } catch (error: SecurityException) {
            Log.w(TAG, "Accès au micro refusé", error)
            signalError(listener, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            return
        } catch (error: Exception) {
            Log.e(TAG, "Impossible d'initialiser le micro", error)
            signalError(listener, SpeechRecognizer.ERROR_AUDIO)
            return
        }

        val autoStop = recognizerIntent
            ?.getBooleanExtra(EXTRA_AUTO_STOP, true)
            ?: true
        val partialResults = recognizerIntent
            ?.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            ?: false

        val sessionGate = TranscriptionSessionGate()
        val sessionControl = RecognitionSessionControl()
        val sessionId = UUID.randomUUID().toString()
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            recordAndTranscribe(
                audioRecord = capture.audioRecord,
                bufferSamples = capture.bufferSamples,
                autoStop = autoStop,
                partialResults = partialResults,
                listener = listener,
                sessionGate = sessionGate,
                sessionId = sessionId,
                sessionControl = sessionControl,
                audioCleanup = capture.cleanup,
            )
        }
        val owner = ActiveRecognitionSession(
            job = job,
            gate = sessionGate,
            audioRecord = capture.audioRecord,
            audioCleanup = capture.cleanup,
            control = sessionControl,
        )
        // Le coordinateur possède désormais la publication lazy, le recheck
        // durable, le cleanup et le retrait par identité comme un seul contrat.
        when (
            sessionCoordinator.tryStart(
                candidate = owner,
                consentAccepted = { PrivacyConsent.isAcceptedBlocking(this) },
                release = { it.audioCleanup.run() },
                cancel = ::cancelSession,
                onReleaseFailure = {
                    Log.w(TAG, "Impossible de libérer la capture audio", it)
                },
            )
        ) {
            RecognitionSessionStartResult.STARTED -> Unit
            RecognitionSessionStartResult.BUSY ->
                signalError(listener, SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            RecognitionSessionStartResult.CONSENT_REVOKED ->
                signalError(listener, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            RecognitionSessionStartResult.START_FAILED ->
                signalError(listener, SpeechRecognizer.ERROR_CLIENT)
        }
    }

    private suspend fun recordAndTranscribe(
        audioRecord: AudioRecord,
        bufferSamples: Int,
        autoStop: Boolean,
        partialResults: Boolean,
        listener: Callback?,
        sessionGate: TranscriptionSessionGate,
        sessionId: String,
        sessionControl: RecognitionSessionControl,
        audioCleanup: IdempotentCleanup,
    ) {
        val finalAudio = File(
            File(noBackupFilesDir, AUDIO_DIRECTORY),
            "dictation-${System.currentTimeMillis()}-${UUID.randomUUID()}.wav",
        )
        var dictationId: Long? = null
        var writer: RecoverableWavFile? = null
        var samplesWrittenFallback = 0L
        var terminalStateWritten = false
        var failureCode = "pipeline_interrupted"
        var endOfSpeechSignalled = false

        try {
            ensureSessionActive(sessionGate)
            // La ligne existe avant le premier échantillon : même une mort du
            // processus entre les deux étapes laissera un état récupérable.
            val activeDictationId = DurableCommitBoundary.capture(
                commit = { store.creerDicteeEnCours(finalAudio) },
                remember = { dictationId = it },
            )
            ensureSessionActive(sessionGate)
            writer = RecoverableWavFile.create(finalAudio, SAMPLE_RATE)
            sileroVadRepository.reset()

            ensureSessionActive(sessionGate)
            audioRecord.startRecording()
            signalReady(listener, sessionGate, sessionId)

            val buffer = ShortArray(bufferSamples)
            val detectedSegments = mutableListOf<AudioSegment>()
            var activeSpeechStart: Long? = null

            while (!sessionControl.stopRequested && currentCoroutineContext().isActive) {
                val count = try {
                    audioRecord.read(buffer, 0, buffer.size)
                } catch (error: IllegalStateException) {
                    if (sessionControl.stopRequested) break else throw error
                }

                if (count < 0) {
                    if (sessionControl.stopRequested) break
                    throw IOException("AudioRecord.read a retourné $count")
                }
                if (count == 0) continue

                ensureSessionActive(sessionGate)
                writer.append(buffer, count)
                samplesWrittenFallback += count
                signalRms(listener, buffer, count, sessionGate)

                // Le détecteur voit les blocs l'un après l'autre sur cette
                // même coroutine. Son compteur correspond donc au WAV.
                val vadBuffer = if (count == buffer.size) buffer else buffer.copyOf(count)
                ensureSessionActive(sessionGate)
                val event = sileroVadRepository.detect(vadBuffer).orEmpty()
                ensureSessionActive(sessionGate)

                event["start"]?.let { rawStart ->
                    if (activeSpeechStart == null) {
                        activeSpeechStart = (
                            rawStart.toLong() - SPEECH_START_PAD_SAMPLES
                            ).coerceAtLeast(0L)
                        signalBeginningOfSpeech(listener, sessionGate)
                    }
                }

                event["end"]?.let { rawEnd ->
                    val end = rawEnd.toLong().coerceIn(0L, samplesWrittenFallback)
                    activeSpeechStart?.let { start ->
                        if (end > start) detectedSegments += AudioSegment(start, end)
                    }
                    activeSpeechStart = null
                    signalEndOfSpeech(listener, sessionGate)
                    endOfSpeechSignalled = true

                    if (autoStop) {
                        sessionControl.requestStop()
                    }
                }
            }

            stopAudioRecord(audioRecord)
            ensureSessionActive(sessionGate)

            val totalSamples = samplesWrittenFallback
            activeSpeechStart?.let { start ->
                if (totalSamples > start) detectedSegments += AudioSegment(start, totalSamples)
            }
            if (!endOfSpeechSignalled && !sessionControl.cancelRequested) {
                signalEndOfSpeech(listener, sessionGate)
                endOfSpeechSignalled = true
            }

            writer.finalizeRecording()
            writer = null
            ensureSessionActive(sessionGate)

            if (totalSamples <= 0L) {
                store.marquerAReessayer(activeDictationId, "empty_audio", 0L)
                terminalStateWritten = true
                signalError(listener, SpeechRecognizer.ERROR_NO_MATCH, sessionGate)
                return
            }

            val audioDurationMs = totalSamples * 1_000L / SAMPLE_RATE
            val segments = AudioSegmentPlanner.normalizeAndSplit(
                segments = detectedSegments,
                totalSamples = totalSamples,
                maxSegmentSamples = MAX_SEGMENT_SAMPLES,
            )
            ensureSessionActive(sessionGate)
            check(store.marquerEnAttente(activeDictationId, audioDurationMs, segments)) {
                "La dictée $activeDictationId ne peut plus être mise en attente"
            }
            ensureSessionActive(sessionGate)
            check(store.marquerTranscriptionEnCours(activeDictationId)) {
                "La dictée $activeDictationId n'est plus en attente"
            }
            val transcriptionStartedAt = System.currentTimeMillis()
            val partialTexts = mutableListOf<String>()

            if (partialResults) {
                signalPartial(
                    listener = listener,
                    text = "",
                    audioDurationMs = audioDurationMs,
                    completedSegments = 0,
                    totalSegments = segments.size,
                    sessionGate = sessionGate,
                    sessionId = sessionId,
                )
            }

            val pipeline = SequentialTranscriptionPipeline(
                source = AudioSegmentSource { segment ->
                    RecoverableWavFile.readSamples(finalAudio, segment)
                },
                transcriber = SegmentTranscriber { samples ->
                    ensureSessionActive(sessionGate)
                    val result = TranscriptionRuntime.transcribe(this@MainRecognitionService, samples)
                    ensureSessionActive(sessionGate)
                    result.texte.trim().takeIf { it.isNotEmpty() }?.let(partialTexts::add)
                    SegmentText(result.texte, viaRemote = result.viaRelais)
                },
            )

            val combined = pipeline.transcribe(segments) { completed, total ->
                if (partialResults) {
                    signalPartial(
                        listener = listener,
                        text = store.appliquerCorrections(partialTexts.joinToString(" ")),
                        audioDurationMs = audioDurationMs,
                        completedSegments = completed,
                        totalSegments = total,
                        sessionGate = sessionGate,
                        sessionId = sessionId,
                    )
                }
            }

            ensureSessionActive(sessionGate)
            val rawText = combined.text.trim()
            if (rawText.isEmpty()) {
                store.marquerAReessayer(activeDictationId, "empty_transcription", audioDurationMs)
                terminalStateWritten = true
                signalError(listener, SpeechRecognizer.ERROR_NO_MATCH, sessionGate)
                return
            }

            val correctedText = store.appliquerCorrections(rawText)
            val source = when {
                combined.usedRemote && combined.usedLocal -> "mixte"
                combined.usedRemote -> "relais"
                combined.usedLocal -> "local"
                else -> null
            }
            val delayMs = if (sessionControl.stopRequestedAtMs > 0L) {
                System.currentTimeMillis() - sessionControl.stopRequestedAtMs
            } else {
                System.currentTimeMillis() - transcriptionStartedAt
            }

            ensureSessionActive(sessionGate)
            val completed = store.marquerTermineeSiActive(
                id = activeDictationId,
                texteBrut = rawText,
                texteCorrige = correctedText,
                dureeMs = delayMs,
                source = source,
                sessionGate = sessionGate,
            )
            if (!completed) throw CancellationException("Annulation avant l'état terminal")
            terminalStateWritten = true
            signalResults(listener, correctedText, sessionGate, sessionId)
        } catch (error: CancellationException) {
            failureCode = if (sessionControl.cancelRequested) "cancelled" else "service_interrupted"
            throw error
        } catch (_: OutOfMemoryError) {
            // Le pipeline borné rend ce cas beaucoup moins probable, mais
            // l'audio reste récupérable même si un composant natif sature.
            failureCode = "out_of_memory"
            RecoveryFailureBoundary.persistThenDiagnose(
                persist = {},
                diagnose = { Log.e(TAG, "Mémoire insuffisante pendant la transcription") },
            )
            signalError(listener, SpeechRecognizer.ERROR_SERVER, sessionGate)
        } catch (error: SecurityException) {
            failureCode = "microphone_permission"
            Log.w(TAG, "Autorisation du micro perdue pendant la dictée", error)
            signalError(
                listener,
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                sessionGate,
            )
        } catch (error: IOException) {
            failureCode = "audio_io"
            Log.e(TAG, "Erreur d'entrée/sortie audio", error)
            signalError(listener, SpeechRecognizer.ERROR_AUDIO, sessionGate)
        } catch (error: Exception) {
            failureCode = "pipeline_error"
            Log.e(TAG, "Échec de la dictée; le WAV est conservé", error)
            signalError(listener, SpeechRecognizer.ERROR_SERVER, sessionGate)
        } finally {
            stopAudioRecord(audioRecord)
            runCatching { audioCleanup.run() }
            runCatching { sileroVadRepository.reset() }

            RecognitionRecoveryBoundary.finish(
                fallbackSamples = samplesWrittenFallback,
                readSamples = {
                    writer?.samplesWritten
                        ?: RecoverableWavFile.inspect(finalAudio).totalSamples
                },
                finalizeAudio = { writer?.finalizeRecording() },
                closeAudio = { writer?.close() },
                persistRecovery = { snapshot ->
                    if (!terminalStateWritten) {
                        dictationId?.let { id ->
                            store.marquerAReessayer(
                                id = id,
                                codeErreur = if (snapshot.audioFinalizeFailed) {
                                    "audio_finalize_failed"
                                } else {
                                    failureCode
                                },
                                dureeAudioMs = snapshot.samplesWritten * 1_000L / SAMPLE_RATE,
                            )
                        }
                    }
                },
                onPersistenceFailure = {
                    Log.e(TAG, "Impossible de marquer la dictée à reprendre")
                },
            )
        }
    }

    override fun onStopListening(listener: Callback?) {
        sessionCoordinator.current()?.let { owner ->
            owner.control.requestStop()
            stopAudioRecord(owner.audioRecord)
        }
    }

    override fun onCancel(listener: Callback?) {
        sessionCoordinator.current()?.let { owner ->
            cancelSession(owner, "Annulation demandée par le client")
        }
    }

    override fun onDestroy() {
        sessionCoordinator.current()?.let { owner ->
            cancelSession(owner, "Service détruit")
        }
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun cancelSession(owner: ActiveRecognitionSession, reason: String) {
        owner.control.requestCancel()
        owner.gate.cancel()
        stopAudioRecord(owner.audioRecord)
        owner.job.cancel(CancellationException(reason))
    }

    private fun createCapture(listener: Callback?): Capture {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("Autorisation RECORD_AUDIO absente")
        }
        val minBufferBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minBufferBytes > 0) { "Taille de tampon AudioRecord invalide" }
        val bufferSamples = maxOf(minBufferBytes / 2, 512)

        val audioFormat = AudioFormat.Builder()
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()

        val builder = AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSamples * 2)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val attributionContext: Context = if (listener != null) {
                createContext(
                    ContextParams.Builder()
                        .setNextAttributionSource(listener.callingAttributionSource)
                        .build()
                )
            } else {
                this
            }
            builder.setContext(attributionContext)
        }

        val audioRecord = builder.build()
        val cleanup = IdempotentCleanup { audioRecord.release() }
        AudioCaptureValidation.requireInitialized(
            initialized = audioRecord.state == AudioRecord.STATE_INITIALIZED,
            cleanup = cleanup,
        )
        return Capture(audioRecord, bufferSamples, cleanup)
    }

    private suspend fun ensureSessionActive(sessionGate: TranscriptionSessionGate) {
        currentCoroutineContext().ensureActive()
        sessionGate.ensureActive()
        currentCoroutineContext().ensureActive()
    }

    private fun signalReady(
        listener: Callback?,
        sessionGate: TranscriptionSessionGate,
        sessionId: String,
    ) {
        sessionGate.runIfActive {
            val bundle = Bundle().apply { putString(EXTRA_SESSION_ID, sessionId) }
            runCatching { listener?.readyForSpeech(bundle) }
                .onFailure { Log.w(TAG, "Client disparu avant le démarrage", it) }
        }
    }

    private fun signalBeginningOfSpeech(
        listener: Callback?,
        sessionGate: TranscriptionSessionGate,
    ) {
        sessionGate.runIfActive {
            runCatching { listener?.beginningOfSpeech() }
                .onFailure { Log.w(TAG, "Client disparu pendant la dictée", it) }
        }
    }

    private fun signalEndOfSpeech(listener: Callback?, sessionGate: TranscriptionSessionGate) {
        sessionGate.runIfActive {
            runCatching { listener?.endOfSpeech() }
                .onFailure { Log.w(TAG, "Client disparu à la fin de la dictée", it) }
        }
    }

    private fun signalRms(
        listener: Callback?,
        buffer: ShortArray,
        count: Int,
        sessionGate: TranscriptionSessionGate,
    ) {
        var sumOfSquares = 0.0
        for (index in 0 until count) {
            val sample = buffer[index].toDouble()
            sumOfSquares += sample * sample
        }
        val rms = sqrt(sumOfSquares / count)
        val decibels = if (rms > 0.0) {
            (20 * log10(rms / Short.MAX_VALUE.toDouble())).toFloat()
        } else {
            SILENCE_DB
        }
        sessionGate.runIfActive {
            runCatching { listener?.rmsChanged(decibels) }
        }
    }

    private fun signalPartial(
        listener: Callback?,
        text: String,
        audioDurationMs: Long,
        completedSegments: Int,
        totalSegments: Int,
        sessionGate: TranscriptionSessionGate,
        sessionId: String,
    ) {
        sessionGate.runIfActive {
            val bundle = Bundle().apply {
                putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
                putLong(EXTRA_AUDIO_DURATION_MS, audioDurationMs)
                putInt(EXTRA_COMPLETED_SEGMENTS, completedSegments)
                putInt(EXTRA_TOTAL_SEGMENTS, totalSegments)
                putString(EXTRA_SESSION_ID, sessionId)
            }
            runCatching { listener?.partialResults(bundle) }
                .onFailure { Log.w(TAG, "Client disparu pendant la transcription", it) }
        }
    }

    private fun signalResults(
        listener: Callback?,
        text: String,
        sessionGate: TranscriptionSessionGate,
        sessionId: String,
    ) {
        sessionGate.runIfActive {
            val bundle = Bundle().apply {
                putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
                putString(EXTRA_SESSION_ID, sessionId)
            }
            // La dictée est déjà durable à ce stade. La disparition du client
            // ne doit jamais faire planter le processus ni l'accessibilité.
            runCatching { listener?.results(bundle) }
                .onFailure { Log.w(TAG, "Résultat sauvegardé mais client disparu", it) }
        }
    }

    private fun signalError(
        listener: Callback?,
        errorCode: Int,
        sessionGate: TranscriptionSessionGate? = null,
    ) {
        val signal = {
            runCatching { listener?.error(errorCode) }
                .onFailure { Log.w(TAG, "Impossible de signaler l'erreur au client", it) }
            Unit
        }
        if (sessionGate == null) signal() else sessionGate.runIfActive(signal)
    }

    private fun stopAudioRecord(audioRecord: AudioRecord) {
        runCatching {
            if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord.stop()
            }
        }
    }

    private data class Capture(
        val audioRecord: AudioRecord,
        val bufferSamples: Int,
        val cleanup: IdempotentCleanup,
    )
}
