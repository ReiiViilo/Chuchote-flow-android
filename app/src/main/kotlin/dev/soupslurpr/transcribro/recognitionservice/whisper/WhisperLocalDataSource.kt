package dev.soupslurpr.transcribro.recognitionservice.whisper

import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class WhisperLocalDataSource(
    private val whisperApi: WhisperApi,
    private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun publishWhisperContext(
        publish: (WhisperContext) -> Boolean,
    ): Boolean = NativeResourceOwnerHandoff.createAndPublish(
        dispatcher = ioDispatcher,
        create = { whisperApi.getWhisperContext() },
        publish = publish,
        release = { it.release() },
    )
}

interface WhisperApi {
    fun getWhisperContext(): WhisperContext
}

/**
 * Crée et transfère une ressource native dans la même frontière dispatcher.
 *
 * Si le propriétaire est annulé pendant l'appel natif bloquant, la ressource
 * fraîchement créée est libérée avant que `withContext` puisse perdre son
 * résultat au retour. Si l'annulation arrive après la publication, le
 * propriétaire possède déjà la référence et pourra la libérer normalement.
 * `publish` doit être une opération atomique qui ne lève pas après transfert.
 */
internal object NativeResourceOwnerHandoff {
    suspend fun <T : Any> createAndPublish(
        dispatcher: CoroutineDispatcher,
        create: () -> T,
        publish: (T) -> Boolean,
        release: suspend (T) -> Unit,
    ): Boolean = withContext(dispatcher) {
        val resource = create()
        var published = false
        var releaseAttempted = false

        try {
            currentCoroutineContext().ensureActive()
            published = publish(resource)
            if (!published) {
                releaseAttempted = true
                release(resource)
            }
            published
        } catch (error: Throwable) {
            if (!published && !releaseAttempted) {
                releaseAttempted = true
                try {
                    release(resource)
                } catch (cleanupError: Throwable) {
                    if (cleanupError !== error) error.addSuppressed(cleanupError)
                }
            }
            throw error
        }
    }
}
