package dev.soupslurpr.transcribro.remote

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Envoie l'audio au relais de transcription plutôt que de le transcrire sur
 * l'appareil, quand un relais est configuré.
 *
 * Toute défaillance — relais éteint, réseau absent, jeton refusé — se solde par
 * un `null` : l'appelant repasse alors sur la transcription locale. La dictée
 * n'est donc jamais perdue parce qu'un serveur n'a pas répondu.
 */
class RemoteTranscriber(context: Context) {

    // Différé : ce transcripteur est construit dans un initialiseur de champ du
    // service, avant que le contexte ne soit attaché. Lire les préférences à ce
    // moment-là échouerait ; garder la référence, non.
    private val settings by lazy { RemoteTranscriptionSettings(context) }

    suspend fun transcribe(pcm: ShortArray, sampleRate: Int): String? {
        if (!settings.isUsable || pcm.isEmpty()) return null

        return withContext(Dispatchers.IO) {
            runCatching { post(wavBytes(pcm, sampleRate)) }
                .onFailure { Log.w(TAG, "Relais injoignable, repli sur la transcription locale", it) }
                .getOrNull()
        }
    }

    private fun post(audio: ByteArray): String? {
        val url = URL("${settings.baseUrl}/api/transcribe")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            // Des délais courts : au-delà, la transcription locale aurait déjà
            // rendu la main. Mieux vaut abandonner tôt que faire attendre.
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer ${settings.token}")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$BOUNDARY")
        }

        try {
            DataOutputStream(connection.outputStream).use { out ->
                out.writeBytes("--$BOUNDARY\r\n")
                out.writeBytes(
                    "Content-Disposition: form-data; name=\"file\"; filename=\"dictee.wav\"\r\n"
                )
                out.writeBytes("Content-Type: audio/wav\r\n\r\n")
                out.write(audio)
                out.writeBytes("\r\n")

                out.writeBytes("--$BOUNDARY\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"language\"\r\n\r\n")
                out.writeBytes("fr\r\n")

                out.writeBytes("--$BOUNDARY--\r\n")
            }

            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }
                Log.w(TAG, "Relais a répondu $code : ${detail?.take(300)}")
                return null
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val text = JSONObject(body).optString("text").trim()
            return text.ifEmpty { null }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Emballe l'audio brut dans un conteneur WAV.
     *
     * Le WAV n'est qu'un en-tête de 44 octets suivi des échantillons : aucune
     * compression, donc aucun encodeur à faire fonctionner et rien qui puisse
     * échouer silencieusement selon l'appareil. En contrepartie le fichier est
     * volumineux (environ 32 Ko par seconde), ce qui reste sans conséquence ici
     * puisque l'audio est envoyé par courts segments délimités par les silences.
     */
    private fun wavBytes(pcm: ShortArray, sampleRate: Int): ByteArray {
        val dataSize = pcm.size * 2
        val output = ByteArrayOutputStream(44 + dataSize)

        fun ascii(value: String) = output.write(value.toByteArray(Charsets.US_ASCII))
        fun int32(value: Int) {
            output.write(value and 0xFF)
            output.write((value shr 8) and 0xFF)
            output.write((value shr 16) and 0xFF)
            output.write((value shr 24) and 0xFF)
        }
        fun int16(value: Int) {
            output.write(value and 0xFF)
            output.write((value shr 8) and 0xFF)
        }

        ascii("RIFF")
        int32(36 + dataSize)
        ascii("WAVE")
        ascii("fmt ")
        int32(16)          // taille du bloc de format
        int16(1)           // PCM non compressé
        int16(1)           // mono
        int32(sampleRate)
        int32(sampleRate * 2)  // octets par seconde
        int16(2)           // alignement de bloc
        int16(16)          // bits par échantillon
        ascii("data")
        int32(dataSize)

        for (sample in pcm) {
            int16(sample.toInt())
        }

        return output.toByteArray()
    }

    companion object {
        private const val TAG = "RemoteTranscriber"
        private const val BOUNDARY = "----ChuchoteFlowBoundary"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 30_000
    }
}
