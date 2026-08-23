package dev.soupslurpr.transcribro.remote

import android.content.Context

internal data class RemoteRequestTarget(
    val baseUrl: String,
    val token: String,
)

internal data class RemoteConfigurationSnapshot(
    val enabled: Boolean,
    val baseUrl: String,
    val token: String,
) {
    val hasCompleteEndpoint: Boolean
        get() = baseUrl.isNotBlank() && token.isNotBlank()

    val requestTarget: RemoteRequestTarget?
        get() = if (enabled && hasCompleteEndpoint) {
            RemoteRequestTarget(baseUrl = baseUrl, token = token)
        } else {
            null
        }
}

internal object RemoteConfigurationPolicy {
    fun importSharedLink(
        current: RemoteConfigurationSnapshot,
        value: String,
    ): RemoteConfigurationSnapshot? {
        val separator = value.indexOf('#')
        if (separator < 0) return null
        val baseUrl = normalizeBaseUrl(value.substring(0, separator))
        val token = value.substring(separator + 1).trim()
        if (baseUrl.isEmpty() || token.isEmpty()) return null
        return current.copy(baseUrl = baseUrl, token = token)
    }

    fun editBaseUrl(
        current: RemoteConfigurationSnapshot,
        value: String,
    ): RemoteConfigurationSnapshot = current.copy(
        enabled = false,
        baseUrl = normalizeBaseUrl(value),
        token = "",
    )

    fun editToken(
        current: RemoteConfigurationSnapshot,
        value: String,
    ): RemoteConfigurationSnapshot = current.copy(
        enabled = false,
        token = value.trim(),
    )

    fun withEnabled(
        current: RemoteConfigurationSnapshot,
        requested: Boolean,
    ): RemoteConfigurationSnapshot = current.copy(
        enabled = requested && current.hasCompleteEndpoint,
    )

    fun normalized(candidate: RemoteConfigurationSnapshot): RemoteConfigurationSnapshot {
        val normalized = candidate.copy(
            baseUrl = normalizeBaseUrl(candidate.baseUrl),
            token = candidate.token.trim(),
        )
        return normalized.copy(enabled = normalized.enabled && normalized.hasCompleteEndpoint)
    }

    private fun normalizeBaseUrl(value: String): String = value.trim().trimEnd('/')
}

/**
 * Réglages du relais de transcription.
 *
 * Volontairement rangés dans leurs propres préférences plutôt que dans le
 * DataStore de l'application : le service de reconnaissance doit pouvoir les
 * lire de façon synchrone, au moment précis où il s'apprête à transcrire.
 */
class RemoteTranscriptionSettings(context: Context) {

    private val preferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    val enabled: Boolean
        get() = snapshot().enabled

    /** Adresse du relais, sans barre oblique finale ni chemin. */
    val baseUrl: String
        get() = snapshot().baseUrl

    val token: String
        get() = snapshot().token

    /** Le relais n'est tenté que s'il est activé et complètement configuré. */
    val isUsable: Boolean
        get() = snapshot().requestTarget != null

    internal fun snapshot(): RemoteConfigurationSnapshot = synchronized(PREFERENCES_LOCK) {
        RemoteConfigurationPolicy.normalized(
            RemoteConfigurationSnapshot(
                enabled = preferences.getBoolean(KEY_ENABLED, false),
                baseUrl = preferences.getString(KEY_BASE_URL, "").orEmpty(),
                token = preferences.getString(KEY_TOKEN, "").orEmpty(),
            ),
        )
    }

    /** Écrit toujours enabled, URL et jeton dans un seul Editor atomique. */
    internal fun replace(candidate: RemoteConfigurationSnapshot): RemoteConfigurationSnapshot =
        synchronized(PREFERENCES_LOCK) {
            val normalized = RemoteConfigurationPolicy.normalized(candidate)
            preferences.edit()
                .putBoolean(KEY_ENABLED, normalized.enabled)
                .putString(KEY_BASE_URL, normalized.baseUrl)
                .putString(KEY_TOKEN, normalized.token)
                .apply()
            normalized
        }

    companion object {
        private const val FILE_NAME = "remote_transcription"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_TOKEN = "token"
        private val PREFERENCES_LOCK = Any()
    }
}
