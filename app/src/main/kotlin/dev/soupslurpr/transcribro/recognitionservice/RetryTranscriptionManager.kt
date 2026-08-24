package dev.soupslurpr.transcribro.recognitionservice

import android.content.Context
import android.util.Log
import dev.soupslurpr.transcribro.memory.ChuchoteStore
import dev.soupslurpr.transcribro.memory.EtatDictee
import dev.soupslurpr.transcribro.preferences.PrivacyConsent
import dev.soupslurpr.transcribro.recognitionservice.audio.AudioSegmentPlanner
import dev.soupslurpr.transcribro.recognitionservice.audio.AudioSegmentSource
import dev.soupslurpr.transcribro.recognitionservice.audio.PrivateAudioPathResolver
import dev.soupslurpr.transcribro.recognitionservice.audio.RecoverableWavFile
import dev.soupslurpr.transcribro.recognitionservice.audio.SegmentText
import dev.soupslurpr.transcribro.recognitionservice.audio.SegmentTranscriber
import dev.soupslurpr.transcribro.recognitionservice.audio.SequentialTranscriptionPipeline
import dev.soupslurpr.transcribro.recognitionservice.audio.WavInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File

/** Exclusion logique qui reste détenue pendant les suspensions du workflow. */
internal class RetryWorkflowGate {
    private val mutex = Mutex()

    suspend fun <T> runExclusive(workflow: suspend () -> T): T {
        mutex.lock()
        return try {
            workflow()
        } finally {
            mutex.unlock()
        }
    }
}

/** Politique pure partagée par l'historique et le workflow de reprise. */
internal object RetryTranscriptionPolicy {
    private val unavailableAudioErrors = setOf(
        "audio_missing",
        "empty_audio",
        "retry_audio_missing",
        "retry_audio_invalid",
    )

    fun canRetry(
        state: EtatDictee,
        audioPath: String?,
        errorCode: String? = null,
    ): Boolean =
        state in setOf(EtatDictee.A_REESSAYER, EtatDictee.TERMINEE) &&
            !audioPath.isNullOrBlank() &&
            errorCode !in unavailableAudioErrors

    fun requiresConfirmation(state: EtatDictee, transcript: String): Boolean =
        state == EtatDictee.TERMINEE && transcript.isNotBlank()
}

/**
 * Une erreur de transcription est d'abord rendue récupérable dans SQLite.
 * Le diagnostic vient ensuite et ne peut jamais masquer cette tentative, même
 * si le logger manque lui-même de mémoire.
 */
internal object RecoveryFailureBoundary {
    suspend fun persistThenDiagnose(
        persist: suspend () -> Unit,
        diagnose: () -> Unit,
        onPersistenceFailure: (Throwable) -> Unit = {},
    ) {
        var persistenceFailure: Throwable? = null
        withContext(NonCancellable) {
            try {
                persist()
            } catch (error: Exception) {
                persistenceFailure = error
            } catch (error: OutOfMemoryError) {
                persistenceFailure = error
            }
        }
        persistenceFailure?.let { error ->
            runDiagnosticSafely { onPersistenceFailure(error) }
        }
        runDiagnosticSafely(diagnose)
    }

    private fun runDiagnosticSafely(diagnostic: () -> Unit) {
        try {
            diagnostic()
        } catch (_: Exception) {
            // Le diagnostic reste secondaire à la tentative de récupération.
        } catch (_: OutOfMemoryError) {
            // Ne jamais perdre l'état durable à cause du logger.
        }
    }
}

internal sealed interface RetryAudioRecoveryResult {
    data class Ready(val file: File, val info: WavInfo) : RetryAudioRecoveryResult
    data object Missing : RetryAudioRecoveryResult
    data object Invalid : RetryAudioRecoveryResult
}

/**
 * Classe les erreurs ordinaires du WAV comme invalides, tout en laissant un
 * manque de mémoire remonter vers la récupération générale et réessayable.
 */
internal object RetryAudioRecovery {
    fun inspect(
        audio: File,
        expectedSampleRate: Int,
        recoverFile: (File) -> File? = { RecoverableWavFile.recoverIfNeeded(it) },
        inspectFile: (File) -> WavInfo = { RecoverableWavFile.inspect(it) },
    ): RetryAudioRecoveryResult {
        val recovered = try {
            recoverFile(audio)
        } catch (_: Exception) {
            return RetryAudioRecoveryResult.Invalid
        } ?: return RetryAudioRecoveryResult.Missing

        val info = try {
            inspectFile(recovered)
        } catch (_: Exception) {
            return RetryAudioRecoveryResult.Invalid
        }
        if (info.totalSamples <= 0 || info.sampleRate != expectedSampleRate) {
            return RetryAudioRecoveryResult.Invalid
        }
        return RetryAudioRecoveryResult.Ready(recovered, info)
    }
}

/**
 * Une reprise ne possède le droit de réécrire l'état qu'après avoir lu la
 * ligne visée ou après avoir durablement revendiqué son passage en attente.
 */
internal object RetryFailurePersistencePolicy {
    fun canPersistFailure(
        startingState: EtatDictee?,
        queuedForRetry: Boolean,
    ): Boolean = startingState != null || queuedForRetry
}

/** Relance, dans l'ordre et une seule fois à la fois, un WAV déjà sauvegardé. */
internal class RetryTranscriptionManager private constructor(context: Context) {
    private val applicationContext = context.applicationContext
    private val store = ChuchoteStore.get(applicationContext)
    private val audioPaths = PrivateAudioPathResolver(
        noBackupFilesDir = applicationContext.noBackupFilesDir,
        filesDir = applicationContext.filesDir,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(1)
    )
    private val jobs = mutableMapOf<Long, Job>()
    private val workflowGate = RetryWorkflowGate()

    /** Retourne false lorsqu'une tentative pour cette dictée est déjà active. */
    @Synchronized
    fun retry(dictationId: Long): Boolean {
        if (!PrivacyConsent.isAcceptedBlocking(applicationContext)) return false
        if (jobs[dictationId]?.isActive == true) return false
        val job = scope.launch { retryInternal(dictationId) }
        jobs[dictationId] = job
        job.invokeOnCompletion {
            synchronized(this@RetryTranscriptionManager) {
                if (jobs[dictationId] === job) jobs.remove(dictationId)
            }
        }
        return true
    }

    private suspend fun retryInternal(dictationId: Long) = workflowGate.runExclusive {
        retryExclusively(dictationId)
    }

    private suspend fun retryExclusively(dictationId: Long) {
        // Revalider dans la coroutine ferme la course entre le tap utilisateur
        // et l'accès réel au WAV. Une ancienne acceptation ne suffit jamais.
        if (!PrivacyConsent.isAccepted(applicationContext)) return
        var queuedForRetry = false
        var startingState: EtatDictee? = null

        try {
            val dictation = store.obtenirDictee(dictationId) ?: return
            startingState = dictation.etat
            if (!RetryTranscriptionPolicy.canRetry(
                    state = dictation.etat,
                    audioPath = dictation.cheminAudio,
                    errorCode = dictation.erreur,
                )
            ) return
            val audio = privateAudioOrNull(dictation.cheminAudio)
            if (audio == null) {
                persistRetryFailureSafely(
                    dictationId = dictationId,
                    startingState = startingState,
                    queuedForRetry = queuedForRetry,
                    errorCode = "retry_audio_missing",
                )
                return
            }

            currentCoroutineContext().ensureActive()
            val recoveredAudio = when (
                val recovery = RetryAudioRecovery.inspect(audio, SAMPLE_RATE)
            ) {
                RetryAudioRecoveryResult.Missing -> {
                    persistRetryFailureSafely(
                        dictationId = dictationId,
                        startingState = startingState,
                        queuedForRetry = queuedForRetry,
                        errorCode = "retry_audio_missing",
                    )
                    return
                }
                RetryAudioRecoveryResult.Invalid -> {
                    persistRetryFailureSafely(
                        dictationId = dictationId,
                        startingState = startingState,
                        queuedForRetry = queuedForRetry,
                        errorCode = "retry_audio_invalid",
                    )
                    return
                }
                is RetryAudioRecoveryResult.Ready -> recovery
            }
            val recovered = recoveredAudio.file
            val info = recoveredAudio.info

            val durationMs = info.totalSamples * 1_000L / info.sampleRate
            val segments = AudioSegmentPlanner.normalizeAndSplit(
                segments = dictation.segments,
                totalSamples = info.totalSamples,
                maxSegmentSamples = MAX_SEGMENT_SAMPLES,
            )
            val queued = DurableCommitBoundary.capture(
                commit = { store.marquerEnAttente(dictationId, durationMs, segments) },
                remember = { queuedForRetry = it },
            )
            if (!queued) return
            currentCoroutineContext().ensureActive()
            if (!store.marquerTranscriptionEnCours(dictationId)) return
            val startedAt = System.currentTimeMillis()

            val pipeline = SequentialTranscriptionPipeline(
                source = AudioSegmentSource { segment ->
                    RecoverableWavFile.readSamples(recovered, segment)
                },
                transcriber = SegmentTranscriber { samples ->
                    if (!PrivacyConsent.isAccepted(applicationContext)) {
                        throw CancellationException("Consentement retiré pendant la reprise")
                    }
                    val result = TranscriptionRuntime.transcribe(applicationContext, samples)
                    SegmentText(result.texte, result.viaRelais)
                },
            )
            val result = pipeline.transcribe(segments)
            if (!PrivacyConsent.isAccepted(applicationContext)) {
                throw CancellationException("Consentement retiré avant la finalisation")
            }
            val rawText = result.text.trim()
            if (rawText.isEmpty()) {
                store.marquerAReessayer(dictationId, "empty_transcription", durationMs)
                return
            }

            val source = when {
                result.usedRemote && result.usedLocal -> "mixte"
                result.usedRemote -> "relais"
                result.usedLocal -> "local"
                else -> null
            }
            currentCoroutineContext().ensureActive()
            if (!store.marquerTerminee(
                id = dictationId,
                texteBrut = rawText,
                texteCorrige = store.appliquerCorrections(rawText),
                dureeMs = System.currentTimeMillis() - startedAt,
                source = source,
            )) return
        } catch (error: CancellationException) {
            RecoveryFailureBoundary.persistThenDiagnose(
                persist = {
                    if (
                        RetryFailurePersistencePolicy.canPersistFailure(
                            startingState = startingState,
                            queuedForRetry = queuedForRetry,
                        ) && (queuedForRetry || startingState != EtatDictee.TERMINEE)
                    ) {
                        store.marquerAReessayer(dictationId, "retry_interrupted")
                    }
                },
                diagnose = {},
                onPersistenceFailure = { logPersistenceFailure() },
            )
            throw error
        } catch (error: OutOfMemoryError) {
            RecoveryFailureBoundary.persistThenDiagnose(
                persist = {
                    markRetryFailure(
                        dictationId = dictationId,
                        startingState = startingState,
                        queuedForRetry = queuedForRetry,
                        errorCode = "out_of_memory",
                    )
                },
                diagnose = { Log.e(TAG, "Mémoire insuffisante pendant une reprise") },
                onPersistenceFailure = { logPersistenceFailure() },
            )
        } catch (error: Exception) {
            RecoveryFailureBoundary.persistThenDiagnose(
                persist = {
                    markRetryFailure(
                        dictationId = dictationId,
                        startingState = startingState,
                        queuedForRetry = queuedForRetry,
                        errorCode = "retry_failed",
                    )
                },
                diagnose = { Log.e(TAG, "La reprise a échoué; le WAV est conservé", error) },
                onPersistenceFailure = { logPersistenceFailure() },
            )
        }
    }

    private suspend fun markRetryFailure(
        dictationId: Long,
        startingState: EtatDictee?,
        queuedForRetry: Boolean,
        errorCode: String,
    ) {
        if (!RetryFailurePersistencePolicy.canPersistFailure(startingState, queuedForRetry)) {
            return
        }
        if (startingState == EtatDictee.TERMINEE && !queuedForRetry) {
            store.marquerErreurRepriseTerminee(dictationId, errorCode)
        } else {
            store.marquerAReessayer(dictationId, errorCode)
        }
    }

    private suspend fun persistRetryFailureSafely(
        dictationId: Long,
        startingState: EtatDictee?,
        queuedForRetry: Boolean,
        errorCode: String,
    ) {
        RecoveryFailureBoundary.persistThenDiagnose(
            persist = {
                markRetryFailure(
                    dictationId = dictationId,
                    startingState = startingState,
                    queuedForRetry = queuedForRetry,
                    errorCode = errorCode,
                )
            },
            diagnose = {},
            onPersistenceFailure = { logPersistenceFailure() },
        )
    }

    private fun privateAudioOrNull(path: String?): File? {
        return audioPaths.resolve(path)
    }

    private fun logPersistenceFailure() {
        Log.e(TAG, "Impossible de persister l'état récupérable de la reprise")
    }

    companion object {
        private const val TAG = "RetryTranscription"
        private const val SAMPLE_RATE = 16_000
        private const val MAX_SEGMENT_SAMPLES = 480_000

        @Volatile
        private var instance: RetryTranscriptionManager? = null

        fun get(context: Context): RetryTranscriptionManager =
            instance ?: synchronized(this) {
                instance ?: RetryTranscriptionManager(context).also { instance = it }
            }
    }
}
