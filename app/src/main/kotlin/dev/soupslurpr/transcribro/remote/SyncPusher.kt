package dev.soupslurpr.transcribro.remote

import android.content.Context
import android.util.Log
import dev.soupslurpr.transcribro.preferences.PrivacyConsent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pousse chaque dictée terminée vers le cerveau commun Chuchote Flow
 * (`/api/sync/dictations` sur le relais), avec la même adresse et le même
 * jeton que la transcription distante — aucune configuration de plus.
 *
 * Mêmes frontières que le relais : rien ne part sans le consentement courant
 * ET sans que le relais soit activé et configuré. Tout est best-effort et
 * hors du chemin critique : un échec est journalisé puis oublié, la vérité
 * locale (`chuchote.db`) n'attend jamais le cloud. L'identifiant local rend
 * l'envoi idempotent côté serveur : un renvoi ne crée jamais de doublon.
 */
class SyncPusher(context: Context) {

    private val applicationContext = context.applicationContext
    private val settings by lazy { RemoteTranscriptionSettings(applicationContext) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun pushDictation(
        localId: Long,
        createdAtMs: Long,
        rawText: String?,
        finalText: String,
        durationMs: Long?,
        source: String?,
    ) {
        if (finalText.isBlank()) return
        val target = settings.snapshot().requestTarget ?: return

        scope.launch {
            // Le consentement est relu au dernier moment, dans la coroutine :
            // une révocation entre la dictée et l'envoi est respectée.
            if (!PrivacyConsent.isAccepted(applicationContext)) return@launch
            runCatching {
                val payload = JSONObject().put(
                    "dictations",
                    JSONArray().put(
                        JSONObject()
                            .put("device", "android")
                            .put("device_local_id", localId.toString())
                            .put("created_at", isoUtc(createdAtMs))
                            .put("raw_text", rawText ?: JSONObject.NULL)
                            .put("final_text", finalText)
                            .put("duration_ms", durationMs ?: JSONObject.NULL)
                            .put("source", source ?: JSONObject.NULL),
                    ),
                )
                val url = URL(
                    target.baseUrl.trimEnd('/') + "/api/sync/dictations",
                )
                val connection = url.openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 15_000
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setRequestProperty(
                        "Authorization",
                        "Bearer ${target.token}",
                    )
                    connection.outputStream.use {
                        it.write(payload.toString().toByteArray(Charsets.UTF_8))
                    }
                    val code = connection.responseCode
                    if (code in 200..299) {
                        Log.d(TAG, "Dictée $localId synchronisée")
                    } else {
                        Log.w(TAG, "Sync refusée: HTTP $code")
                    }
                } finally {
                    connection.disconnect()
                }
            }.onFailure { error ->
                Log.w(TAG, "Sync impossible: ${error.message}")
            }
        }
    }

    private fun isoUtc(epochMs: Long): String =
        java.time.Instant.ofEpochMilli(epochMs).toString()

    private companion object {
        const val TAG = "SyncPusher"
    }
}
