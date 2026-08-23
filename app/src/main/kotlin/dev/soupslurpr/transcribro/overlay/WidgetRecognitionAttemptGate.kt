package dev.soupslurpr.transcribro.overlay

/**
 * Identifie localement une tentative de reconnaissance, indépendamment de
 * l'UUID envoyé par le service de reconnaissance.
 *
 * L'état PENDING est acquis avant `startListening()`: un deuxième tap ne peut
 * donc pas lancer une tentative concurrente pendant que le callback
 * `onReadyForSpeech` est encore en vol.
 */
internal class WidgetRecognitionAttemptGate {
    private var nextGeneration = 0L
    private var activeGeneration: Long? = null
    private var state = State.IDLE

    @Synchronized
    fun begin(): Long? {
        if (state != State.IDLE) return null
        val generation = ++nextGeneration
        activeGeneration = generation
        state = State.PENDING
        return generation
    }

    @Synchronized
    fun isPending(generation: Long): Boolean =
        activeGeneration == generation && state == State.PENDING

    @Synchronized
    fun ready(generation: Long): Boolean {
        if (activeGeneration != generation || state != State.PENDING) return false
        state = State.RECORDING
        return true
    }

    @Synchronized
    fun isRecording(generation: Long): Boolean =
        activeGeneration == generation && state == State.RECORDING

    @Synchronized
    fun confirm(generation: Long): Boolean {
        if (activeGeneration != generation || state != State.RECORDING) return false
        state = State.TRANSCRIBING
        return true
    }

    @Synchronized
    fun isTranscribing(generation: Long): Boolean =
        activeGeneration == generation && state == State.TRANSCRIBING

    @Synchronized
    fun complete(generation: Long): Boolean {
        if (activeGeneration != generation || state != State.TRANSCRIBING) return false
        reset()
        return true
    }

    @Synchronized
    fun fail(generation: Long): Boolean {
        if (activeGeneration != generation) return false
        reset()
        return true
    }

    @Synchronized
    fun cancel() {
        reset()
    }

    private fun reset() {
        activeGeneration = null
        state = State.IDLE
    }

    private enum class State { IDLE, PENDING, RECORDING, TRANSCRIBING }
}
