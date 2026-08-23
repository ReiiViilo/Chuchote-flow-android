package dev.soupslurpr.transcribro.remote

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Possède une ressource bloquante et la ferme sans course avec cancel().
 *
 * L'attachement peut survenir juste avant ou juste après l'annulation. Dans les
 * deux ordres, la ressource est fermée une seule fois et attach() indique si
 * l'appelant peut encore l'utiliser.
 */
internal class CancellableConnectionSlot<T : Any>(
    private val disconnect: (T) -> Unit,
) {
    private val cancelled = AtomicBoolean(false)
    private val connection = AtomicReference<T?>()

    fun attach(value: T): Boolean {
        check(connection.compareAndSet(null, value)) { "Une connexion est déjà attachée" }
        if (!cancelled.get()) return true
        disconnectAttached()
        return false
    }

    fun cancel() {
        cancelled.set(true)
        disconnectAttached()
    }

    fun close() {
        disconnectAttached()
    }

    private fun disconnectAttached() {
        connection.getAndSet(null)?.let(disconnect)
    }
}
