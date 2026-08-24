package dev.soupslurpr.transcribro.recognitionservice.audio

import kotlinx.coroutines.CancellationException

/**
 * Point de linéarisation entre une annulation Binder et les effets terminaux.
 *
 * Un callback ou une écriture terminale déjà entré dans [runIfActive] finit
 * avant que [cancel] retourne. Après le retour de [cancel], aucun nouvel effet
 * protégé ne peut démarrer.
 */
internal class TranscriptionSessionGate {
    private val lock = Any()

    @Volatile
    private var cancelled = false

    fun cancel() {
        synchronized(lock) {
            cancelled = true
        }
    }

    fun ensureActive() {
        if (cancelled) throw CancellationException("Session de transcription annulée")
    }

    fun runIfActive(effect: () -> Unit): Boolean = synchronized(lock) {
        if (cancelled) return@synchronized false
        effect()
        true
    }
}
