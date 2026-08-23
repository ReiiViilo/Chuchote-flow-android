package dev.soupslurpr.transcribro.recognitionservice

import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** Règle pure empêchant deux sessions de partager le VAD mutable. */
internal object RecognitionSessionLifecycle {
    fun canStart(previousJob: Job?): Boolean =
        previousJob == null || previousJob.isCompleted
}

/**
 * Regroupe un commit durable et la capture de son résultat sous la même
 * frontière non annulable. La section doit rester courte et strictement locale
 * (par exemple un INSERT/UPDATE SQLite), sans réseau ni calcul modèle.
 */
internal object DurableCommitBoundary {
    suspend fun <T> capture(
        commit: suspend () -> T,
        remember: (T) -> Unit,
    ): T = withContext(NonCancellable) {
        commit().also(remember)
    }
}

/** Publication atomique d'un propriétaire de session, avec retrait par identité. */
internal class ExclusiveSessionRegistry<T : Any> {
    private val lock = Any()
    private var owner: T? = null

    fun current(): T? = synchronized(lock) { owner }

    fun isOwnedBy(candidate: T): Boolean = synchronized(lock) { owner === candidate }

    fun tryInstall(candidate: T, mayReplace: (T) -> Boolean): Boolean = synchronized(lock) {
        val current = owner
        if (current != null && !mayReplace(current)) return@synchronized false
        owner = candidate
        true
    }

    fun clearIfOwned(candidate: T): Boolean = synchronized(lock) {
        if (owner !== candidate) return@synchronized false
        owner = null
        true
    }
}

internal enum class RecognitionSessionStartResult {
    STARTED,
    BUSY,
    CONSENT_REVOKED,
    START_FAILED,
}

/**
 * Propriétaire unique du protocole publication → revalidation → démarrage →
 * nettoyage. MainRecognitionService n'assemble plus ces transitions à la main.
 */
internal class RecognitionSessionCoordinator<T : Any>(
    private val jobOf: (T) -> Job,
) {
    private val registry = ExclusiveSessionRegistry<T>()

    fun current(): T? = registry.current()

    fun canAccept(): Boolean = RecognitionSessionLifecycle.canStart(
        registry.current()?.let(jobOf),
    )

    fun tryStart(
        candidate: T,
        consentAccepted: () -> Boolean,
        release: (T) -> Unit,
        cancel: (T, String) -> Unit,
        onReleaseFailure: (Throwable) -> Unit = {},
    ): RecognitionSessionStartResult {
        val job = jobOf(candidate)
        val releaseOnce = IdempotentCleanup { release(candidate) }
        job.invokeOnCompletion {
            releaseSafely(releaseOnce, onReleaseFailure)
            registry.clearIfOwned(candidate)
        }

        val installed = registry.tryInstall(candidate) { current ->
            RecognitionSessionLifecycle.canStart(jobOf(current))
        }
        if (!installed) {
            cancelSafely(candidate, "Une autre session détient le service", cancel)
            releaseSafely(releaseOnce, onReleaseFailure)
            return RecognitionSessionStartResult.BUSY
        }

        val mayStart = RecognitionSessionStartPolicy.canStartAfterPublication(
            ownerStillPublished = registry.isOwnedBy(candidate),
            consentStillAccepted = consentAccepted(),
            ownerAlreadyCancelled = job.isCancelled,
        )
        if (!mayStart) {
            cancelSafely(candidate, "Consentement retiré avant le démarrage", cancel)
            registry.clearIfOwned(candidate)
            releaseSafely(releaseOnce, onReleaseFailure)
            return RecognitionSessionStartResult.CONSENT_REVOKED
        }

        if (!job.start()) {
            cancelSafely(candidate, "Session annulée avant son démarrage", cancel)
            registry.clearIfOwned(candidate)
            releaseSafely(releaseOnce, onReleaseFailure)
            return RecognitionSessionStartResult.START_FAILED
        }
        return RecognitionSessionStartResult.STARTED
    }

    fun cancelCurrent(
        reason: String,
        cancel: (T, String) -> Unit,
    ): Boolean {
        val owner = registry.current() ?: return false
        cancelSafely(owner, reason, cancel)
        return true
    }

    private fun cancelSafely(
        owner: T,
        reason: String,
        cancel: (T, String) -> Unit,
    ) {
        try {
            cancel(owner, reason)
        } catch (_: Exception) {
            jobOf(owner).cancel()
        } catch (_: OutOfMemoryError) {
            jobOf(owner).cancel()
        }
    }

    private fun releaseSafely(
        cleanup: IdempotentCleanup,
        onFailure: (Throwable) -> Unit,
    ) {
        try {
            cleanup.run()
        } catch (error: Exception) {
            notifyReleaseFailure(error, onFailure)
        } catch (error: OutOfMemoryError) {
            notifyReleaseFailure(error, onFailure)
        }
    }

    private fun notifyReleaseFailure(
        error: Throwable,
        callback: (Throwable) -> Unit,
    ) {
        try {
            callback(error)
        } catch (_: Exception) {
            // Le diagnostic ne peut pas reprendre possession de la session.
        } catch (_: OutOfMemoryError) {
            // Le diagnostic ne peut pas reprendre possession de la session.
        }
    }
}

/** Dernière décision pure avant de démarrer un Job publié mais encore paresseux. */
internal object RecognitionSessionStartPolicy {
    fun canStartAfterPublication(
        ownerStillPublished: Boolean,
        consentStillAccepted: Boolean,
        ownerAlreadyCancelled: Boolean,
    ): Boolean = ownerStillPublished && consentStillAccepted && !ownerAlreadyCancelled
}

/** Exécute au plus une fois le relâchement d'une ressource native. */
internal class IdempotentCleanup(private val cleanup: () -> Unit) {
    private val completed = AtomicBoolean(false)

    fun run(): Boolean {
        if (!completed.compareAndSet(false, true)) return false
        cleanup()
        return true
    }
}

/** Ferme immédiatement une capture native que la plateforme n'a pas initialisée. */
internal object AudioCaptureValidation {
    fun requireInitialized(
        initialized: Boolean,
        cleanup: IdempotentCleanup,
    ) {
        if (initialized) return
        cleanup.run()
        throw IllegalStateException("AudioRecord non initialisé")
    }
}

/** Données minimales fiables transmises à la persistance de récupération. */
internal data class RecognitionRecoverySnapshot(
    val samplesWritten: Long,
    val audioFinalizeFailed: Boolean,
)

/**
 * Frontière de fin de session : aucune panne de lecture, finalisation ou
 * fermeture du WAV ne peut empêcher la tentative d'écriture de récupération.
 */
internal object RecognitionRecoveryBoundary {
    suspend fun finish(
        fallbackSamples: Long,
        readSamples: () -> Long,
        finalizeAudio: () -> Unit,
        closeAudio: () -> Unit,
        persistRecovery: suspend (RecognitionRecoverySnapshot) -> Unit,
        onPersistenceFailure: (Throwable) -> Unit = {},
    ) = withContext(NonCancellable) {
        val samplesWritten = try {
            readSamples().coerceAtLeast(0L)
        } catch (_: Exception) {
            fallbackSamples.coerceAtLeast(0L)
        } catch (_: OutOfMemoryError) {
            fallbackSamples.coerceAtLeast(0L)
        }

        var audioFinalizeFailed = false
        try {
            finalizeAudio()
        } catch (_: Exception) {
            audioFinalizeFailed = true
        } catch (_: OutOfMemoryError) {
            audioFinalizeFailed = true
        }
        if (audioFinalizeFailed) {
            try {
                closeAudio()
            } catch (_: Exception) {
                // La persistance ci-dessous reste prioritaire.
            } catch (_: OutOfMemoryError) {
                // La persistance ci-dessous reste prioritaire.
            }
        }

        val snapshot = RecognitionRecoverySnapshot(samplesWritten, audioFinalizeFailed)
        try {
            persistRecovery(snapshot)
        } catch (error: Exception) {
            notifyPersistenceFailure(error, onPersistenceFailure)
        } catch (error: OutOfMemoryError) {
            notifyPersistenceFailure(error, onPersistenceFailure)
        }
    }

    private fun notifyPersistenceFailure(
        error: Throwable,
        callback: (Throwable) -> Unit,
    ) {
        try {
            callback(error)
        } catch (_: Exception) {
            // Un diagnostic ne doit jamais masquer la fin de session.
        } catch (_: OutOfMemoryError) {
            // Un diagnostic ne doit jamais masquer la fin de session.
        }
    }
}
