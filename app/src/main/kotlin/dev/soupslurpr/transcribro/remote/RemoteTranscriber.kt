package dev.soupslurpr.transcribro.remote

import android.content.Context
import android.util.Log
import dev.soupslurpr.transcribro.memory.ChuchoteStore
import dev.soupslurpr.transcribro.preferences.PrivacyConsent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Envoie l'audio au relais de transcription plutôt que de le transcrire sur
 * l'appareil, quand un relais est configuré.
 *
 * Toute défaillance — relais éteint, réseau absent, jeton refusé — se solde par
 * un `null` : l'appelant repasse alors sur la transcription locale. Une vraie
 * annulation reste toutefois une annulation afin de ne lancer aucun travail
 * après que le propriétaire de la session a disparu.
 */
class RemoteTranscriber(context: Context) {

    private val applicationContext = context.applicationContext

    // Différé : ce transcripteur est construit dans un initialiseur de champ du
    // service, avant que le contexte ne soit attaché. Lire les préférences à ce
    // moment-là échouerait ; garder la référence, non.
    private val settings by lazy { RemoteTranscriptionSettings(context) }
    private val store by lazy { ChuchoteStore.get(context) }

    suspend fun transcribe(pcm: ShortArray, sampleRate: Int): String? {
        // Cette vérification précède même la lecture des réglages du relais :
        // aucun audio ni vocabulaire ne quitte l'app sans consentement actuel.
        // L'absence de consentement n'est pas une panne réseau : renvoyer null
        // lancerait Whisper local et contournerait l'annulation de la session.
        if (!PrivacyConsent.isAccepted(applicationContext)) {
            throw RemoteConsentRevokedException(
                "Consentement absent avant la transcription distante",
            )
        }
        val requestTarget = settings.snapshot().requestTarget
        if (requestTarget == null || pcm.isEmpty()) return null

        return try {
            RemoteConsentGuard.run(
                consent = PrivacyConsent.acceptanceFlow(applicationContext),
                upload = { post(wavBytes(pcm, sampleRate), requestTarget) },
            )
        } catch (error: CancellationException) {
            // Une annulation du propriétaire doit rester une annulation. La
            // transformer en `null` lancerait à tort le repli local.
            throw error
        } catch (error: Exception) {
            val diagnostic = RemoteRelayDiagnostic.transportFailure(error)
            Log.w(TAG, "Relais injoignable, repli local ($diagnostic)")
            null
        }
    }

    private suspend fun post(
        audio: ByteArray,
        requestTarget: RemoteRequestTarget,
    ): String? = withContext(Dispatchers.IO) {
        // HttpURLConnection est bloquant et n'observe pas Job.cancel(). Le
        // handler déconnecte la socket depuis le thread qui annule, ce qui
        // débloque outputStream/responseCode/inputStream sans attendre 30 s.
        suspendCancellableCoroutine { continuation ->
            val connectionSlot = CancellableConnectionSlot<HttpURLConnection> {
                it.disconnect()
            }
            continuation.invokeOnCancellation {
                connectionSlot.cancel()
            }

            try {
                val url = URL("${requestTarget.baseUrl}/api/transcribe")
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    // Des délais courts : au-delà, la transcription locale aurait déjà
                    // rendu la main. Mieux vaut abandonner tôt que faire attendre.
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("Authorization", "Bearer ${requestTarget.token}")
                    setRequestProperty("Content-Type", "multipart/form-data; boundary=$BOUNDARY")
                }
                if (!connectionSlot.attach(connection)) return@suspendCancellableCoroutine

                // Le WAV peut avoir été préparé alors que le consentement était
                // encore vrai. Le relire immédiatement avant outputStream ferme
                // cette fenêtre; une annulation concurrente ferme la connexion.
                val mayOpenRequestBody = RemoteUploadGate.canOpenRequestBody(
                    consentAccepted = PrivacyConsent.isAcceptedBlocking(applicationContext),
                    coroutineActive = continuation.isActive,
                )
                if (!mayOpenRequestBody) {
                    if (continuation.isActive) {
                        continuation.cancel(RemoteConsentRevokedException())
                    }
                    return@suspendCancellableCoroutine
                }

                DataOutputStream(connection.outputStream).use { out ->
                    out.writeBytes("--$BOUNDARY\r\n")
                    out.writeBytes(
                        "Content-Disposition: form-data; name=\"file\"; " +
                            "filename=\"dictee.wav\"\r\n",
                    )
                    out.writeBytes("Content-Type: audio/wav\r\n\r\n")
                    out.write(audio)
                    out.writeBytes("\r\n")

                    out.writeBytes("--$BOUNDARY\r\n")
                    out.writeBytes("Content-Disposition: form-data; name=\"language\"\r\n\r\n")
                    out.writeBytes("fr\r\n")

                    // Les mots du dictionnaire personnel guident le modèle vers
                    // les bons noms propres. writeBytes tronquerait les accents à
                    // un octet par caractère, d'où l'écriture explicite en UTF-8.
                    val vocabulaire = store.motsPourBiais()
                    if (vocabulaire.isNotEmpty()) {
                        out.writeBytes("--$BOUNDARY\r\n")
                        out.writeBytes("Content-Disposition: form-data; name=\"prompt\"\r\n\r\n")
                        out.write(vocabulaire.toByteArray(Charsets.UTF_8))
                        out.writeBytes("\r\n")
                    }

                    out.writeBytes("--$BOUNDARY--\r\n")
                }

                val code = connection.responseCode
                val result = if (code !in 200..299) {
                    // Le corps d'erreur appartient au serveur et peut contenir un
                    // extrait de transcription ou d'autres données sensibles.
                    connection.errorStream?.close()
                    Log.w(TAG, "Le relais a répondu avec le code HTTP $code")
                    null
                } else {
                    when (
                        val decoded = connection.inputStream.bufferedReader().use {
                            RemoteResponseDecoder.decode(it) { body ->
                                RemoteTextFieldContract.requireString(
                                    JSONObject(body).opt("text"),
                                )
                            }
                        }
                    ) {
                        is RemoteResponseResult.Success -> decoded.text
                        is RemoteResponseResult.Failure -> {
                            val diagnostic =
                                RemoteRelayDiagnostic.responseFailure(decoded.code)
                            Log.w(TAG, "Réponse relais inutilisable ($diagnostic)")
                            null
                        }
                    }
                }

                if (continuation.isActive) continuation.resume(result)
            } catch (error: Throwable) {
                // Si disconnect() a réveillé l'I/O, la continuation porte déjà
                // la CancellationException et l'IOException ne doit pas l'écraser.
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally {
                connectionSlot.close()
            }
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
