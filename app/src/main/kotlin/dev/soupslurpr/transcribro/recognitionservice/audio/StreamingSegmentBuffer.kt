package dev.soupslurpr.transcribro.recognitionservice.audio

/** Segment clos pendant l'enregistrement, avec ses échantillons en mémoire. */
internal class ClosedSegment(
    val segment: AudioSegment,
    val samples: ShortArray,
)

/**
 * Clôt les segments de parole au fil de l'enregistrement, échantillons en
 * mémoire, pour que la transcription commence pendant que l'utilisateur parle
 * au lieu d'attendre le crochet.
 *
 * La fenêtre mémoire est bornée : au repos elle ne garde qu'un pré-roll (le
 * rétro-padding que la boucle applique au début de parole détecté), et un
 * segment actif est tranché dès [maxSegmentSamples], exactement comme
 * [AudioSegmentPlanner] le ferait après coup. Les bornes émises reproduisent
 * donc celles du plan final; l'appelant vérifie cette correspondance et
 * retombe sur le chemin lent au moindre écart — ce tampon est une
 * optimisation opportuniste, jamais l'autorité.
 */
internal class StreamingSegmentBuffer(
    private val maxSegmentSamples: Int,
    private val preRollSamples: Int,
) {
    init {
        require(maxSegmentSamples > 0) { "maxSegmentSamples doit être positif" }
        require(preRollSamples >= 0) { "preRollSamples doit être positif ou nul" }
    }

    private var window = ShortArray(INITIAL_CAPACITY)
    private var windowSize = 0
    private var windowStartAbs = 0L
    private var totalSeen = 0L
    private var activeStartAbs: Long? = null

    /**
     * Vrai si un début de parole a dû être tronqué faute de pré-roll : les
     * bornes émises ne correspondent alors plus au plan final et l'appelant
     * doit ignorer les résultats anticipés.
     */
    var bordersDiverged = false
        private set

    /** Ajoute les [count] premiers échantillons de [block]; émet les tranches closes. */
    fun append(block: ShortArray, count: Int): List<ClosedSegment> {
        require(count in 0..block.size) { "count hors bornes" }
        if (count == 0) return emptyList()
        ensureCapacity(windowSize + count)
        block.copyInto(window, windowSize, 0, count)
        windowSize += count
        totalSeen += count

        val out = mutableListOf<ClosedSegment>()
        val active = activeStartAbs
        if (active != null) {
            var cursor = active
            // Même découpe que le planner : tranches pleines dès que possible.
            while (totalSeen - cursor >= maxSegmentSamples) {
                out += slice(cursor, cursor + maxSegmentSamples)
                cursor += maxSegmentSamples
            }
            if (cursor != active) {
                activeStartAbs = cursor
                compactTo(cursor)
            }
        } else {
            compactTo(totalSeen - preRollSamples)
        }
        return out
    }

    /** Début de parole, déjà rétro-paddé par l'appelant (position absolue). */
    fun onSpeechStart(paddedStartAbs: Long) {
        val clamped = paddedStartAbs.coerceAtLeast(windowStartAbs)
        if (clamped != paddedStartAbs) bordersDiverged = true
        activeStartAbs = clamped
    }

    /** Fin de parole (position absolue, exclusive); émet les tranches du segment. */
    fun onSpeechEnd(endAbs: Long): List<ClosedSegment> {
        val active = activeStartAbs ?: return emptyList()
        activeStartAbs = null
        val end = endAbs.coerceIn(active, totalSeen)
        val out = emitSlices(active, end)
        compactTo(maxOf(end, totalSeen - preRollSamples))
        return out
    }

    /** Queue de parole active à l'arrêt de l'enregistrement. */
    fun finish(): List<ClosedSegment> {
        val active = activeStartAbs ?: return emptyList()
        activeStartAbs = null
        return emitSlices(active, totalSeen)
    }

    private fun emitSlices(startAbs: Long, endAbs: Long): List<ClosedSegment> {
        if (endAbs <= startAbs) return emptyList()
        val out = mutableListOf<ClosedSegment>()
        var cursor = startAbs
        while (cursor < endAbs) {
            val sliceEnd = minOf(endAbs, cursor + maxSegmentSamples)
            out += slice(cursor, sliceEnd)
            cursor = sliceEnd
        }
        return out
    }

    private fun slice(startAbs: Long, endAbs: Long): ClosedSegment {
        val from = (startAbs - windowStartAbs).toInt()
        val to = (endAbs - windowStartAbs).toInt()
        check(from >= 0 && to <= windowSize) {
            "tranche [$startAbs,$endAbs) hors de la fenêtre [$windowStartAbs,${windowStartAbs + windowSize})"
        }
        return ClosedSegment(
            segment = AudioSegment(startAbs, endAbs),
            samples = window.copyOfRange(from, to),
        )
    }

    private fun compactTo(newStartAbs: Long) {
        val target = newStartAbs.coerceIn(windowStartAbs, windowStartAbs + windowSize)
        val drop = (target - windowStartAbs).toInt()
        if (drop <= 0) return
        window.copyInto(window, 0, drop, windowSize)
        windowSize -= drop
        windowStartAbs = target
    }

    private fun ensureCapacity(needed: Int) {
        if (needed <= window.size) return
        var size = window.size
        while (size < needed) size *= 2
        window = window.copyOf(size)
    }

    private companion object {
        const val INITIAL_CAPACITY = 32_768
    }
}
