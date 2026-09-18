package dev.soupslurpr.transcribro.remote

import java.io.ByteArrayOutputStream

/**
 * Le corps `multipart/form-data` envoyé au relais de transcription, construit
 * sans réseau ni Android : c'est la frontière par laquelle le vocabulaire
 * personnel quitte l'appareil, et elle doit pouvoir être vérifiée sur la JVM.
 *
 * Champs : `file` (le WAV), `language`, et `prompt` seulement si un
 * vocabulaire non vide est fourni. Le texte est écrit en UTF-8 explicite —
 * `DataOutputStream.writeBytes` tronquerait chaque caractère accentué à un
 * octet.
 */
object TranscriptionRequestBody {

    const val BOUNDARY = "----ChuchoteFlowBoundary"

    fun build(audio: ByteArray, language: String, vocabulaire: String): ByteArray {
        val out = ByteArrayOutputStream(audio.size + 512)
        fun ascii(value: String) = out.write(value.toByteArray(Charsets.US_ASCII))
        fun utf8(value: String) = out.write(value.toByteArray(Charsets.UTF_8))

        ascii("--$BOUNDARY\r\n")
        ascii("Content-Disposition: form-data; name=\"file\"; filename=\"dictee.wav\"\r\n")
        ascii("Content-Type: audio/wav\r\n\r\n")
        out.write(audio)
        ascii("\r\n")

        ascii("--$BOUNDARY\r\n")
        ascii("Content-Disposition: form-data; name=\"language\"\r\n\r\n")
        utf8(language)
        ascii("\r\n")

        // Le vocabulaire personnel guide le modèle vers les bons noms propres —
        // les seules entrées de vocabulaire, jamais les cibles de substitution
        // (voir DictionnaireSubstitution). Absent quand il n'y a rien à dire.
        if (vocabulaire.isNotEmpty()) {
            ascii("--$BOUNDARY\r\n")
            ascii("Content-Disposition: form-data; name=\"prompt\"\r\n\r\n")
            utf8(vocabulaire)
            ascii("\r\n")
        }

        ascii("--$BOUNDARY--\r\n")
        return out.toByteArray()
    }
}
