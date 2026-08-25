package dev.soupslurpr.transcribro.recognitionservice.audio

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Met la musique en pause pendant la dictée.
 *
 * Demander le focus audio transitoire exclusif fait taire les lecteurs bien
 * élevés — Spotify, YouTube Music, le lecteur du navigateur — le temps de
 * l'enregistrement; le rendre les fait repartir d'eux-mêmes. Exclusif plutôt
 * qu'avec atténuation : une musique simplement baissée resterait dans le
 * micro et dans la transcription.
 *
 * Tout est best-effort : un refus de focus n'empêche jamais la dictée, il
 * laisse simplement la musique jouer. Les deux appels sont idempotents.
 */
class DictationAudioFocus(private val audioManager: AudioManager?) {

    private var held: AudioFocusRequest? = null

    /** Demande le focus; sans effet si déjà détenu ou si le système refuse. */
    fun acquire() {
        val manager = audioManager ?: return
        if (held != null) return
        val request = AudioFocusRequest.Builder(
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
        )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
        val outcome = runCatching { manager.requestAudioFocus(request) }
            .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        if (outcome == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            held = request
        }
    }

    /** Rend le focus; la musique interrompue reprend d'elle-même. */
    fun release() {
        val request = held ?: return
        held = null
        runCatching { audioManager?.abandonAudioFocusRequest(request) }
    }
}
