package dev.soupslurpr.transcribro.recognitionservice

/**
 * Lie les callbacks client à l'identité envoyée par RecognitionService.
 *
 * Invalider avant `cancel()` ferme la fenêtre où un callback Binder déjà en
 * file pourrait sinon écrire dans un nouveau champ ou une nouvelle session.
 */
internal class RecognitionSessionTracker {
    private var activeSessionId: String? = null

    @Synchronized
    fun activate(sessionId: String?): Boolean {
        val valid = sessionId?.takeIf { it.isNotBlank() }
        activeSessionId = valid
        return valid != null
    }

    @Synchronized
    fun accepts(sessionId: String?): Boolean =
        sessionId != null && sessionId == activeSessionId

    @Synchronized
    fun complete(sessionId: String?): Boolean {
        if (!accepts(sessionId)) return false
        activeSessionId = null
        return true
    }

    @Synchronized
    fun invalidate() {
        activeSessionId = null
    }
}
