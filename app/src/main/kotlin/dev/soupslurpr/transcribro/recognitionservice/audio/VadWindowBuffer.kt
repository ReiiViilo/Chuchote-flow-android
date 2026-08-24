package dev.soupslurpr.transcribro.recognitionservice.audio

/**
 * Regroupe les blocs audio avant le détecteur de voix.
 *
 * Silero refuse toute fenêtre de moins de [minWindowSamples] échantillons
 * (512 à 16 kHz). Or `AudioRecord.read` peut retourner un bloc partiel plus
 * court, ce qui faisait échouer la dictée entière alors que le WAV était
 * complet et sain. Ce tampon retient ces miettes et ne restitue que des
 * fenêtres d'une taille acceptée : le compteur du détecteur reste aligné sur
 * le WAV, au retard près des échantillons en attente, résorbé dès la fenêtre
 * suivante.
 */
class VadWindowBuffer(private val minWindowSamples: Int) {

    init {
        require(minWindowSamples > 0) { "minWindowSamples doit être positif" }
    }

    private var carry = ShortArray(0)

    /** Échantillons retenus, pas encore restitués au détecteur. */
    val pendingSamples: Int
        get() = carry.size

    /**
     * Ajoute les [count] premiers échantillons de [block] et retourne la
     * fenêtre à soumettre au détecteur, ou `null` si le cumul reste sous le
     * minimum. Le tableau retourné n'est jamais un alias de [block].
     */
    fun feed(block: ShortArray, count: Int): ShortArray? {
        require(count in 0..block.size) {
            "count ($count) hors des bornes du bloc (${block.size})"
        }
        if (count == 0) {
            return if (carry.size >= minWindowSamples) drainInternal() else null
        }

        val combined = ShortArray(carry.size + count).also {
            carry.copyInto(it)
            block.copyInto(it, carry.size, 0, count)
        }
        if (combined.size < minWindowSamples) {
            carry = combined
            return null
        }
        carry = ShortArray(0)
        return combined
    }

    /** Vide le tampon, pour un nouvel enregistrement. */
    fun reset() {
        carry = ShortArray(0)
    }

    private fun drainInternal(): ShortArray {
        val out = carry
        carry = ShortArray(0)
        return out
    }
}
