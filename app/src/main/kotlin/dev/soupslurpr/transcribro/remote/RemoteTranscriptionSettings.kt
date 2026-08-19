package dev.soupslurpr.transcribro.remote

import android.content.Context

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

    var enabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, false)
        set(value) = preferences.edit().putBoolean(KEY_ENABLED, value).apply()

    /** Adresse du relais, sans barre oblique finale ni chemin. */
    var baseUrl: String
        get() = preferences.getString(KEY_BASE_URL, "")?.trim()?.trimEnd('/') ?: ""
        set(value) = preferences.edit().putString(KEY_BASE_URL, value.trim().trimEnd('/')).apply()

    var token: String
        get() = preferences.getString(KEY_TOKEN, "")?.trim() ?: ""
        set(value) = preferences.edit().putString(KEY_TOKEN, value.trim()).apply()

    /** Le relais n'est tenté que s'il est activé et complètement configuré. */
    val isUsable: Boolean
        get() = enabled && baseUrl.isNotEmpty() && token.isNotEmpty()

    companion object {
        private const val FILE_NAME = "remote_transcription"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_TOKEN = "token"
    }
}
