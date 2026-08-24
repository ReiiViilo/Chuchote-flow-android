package dev.soupslurpr.transcribro.overlay

import kotlin.math.abs

/**
 * Fenêtre bornée de correction autour d'une insertion.
 *
 * Seul le texte inséré devient un snapshot comparable. Deux petites ancres
 * prises dans le texte environnant permettent de retrouver sa fin si une
 * correction change sa longueur, sans conserver le contenu complet du champ.
 */
internal data class CorrectionWatchWindow private constructor(
    val baseline: String,
    private val insertionStart: Int,
    private val expectedInsertionEnd: Int,
    private val leftAnchorStart: Int,
    private val leftAnchor: String,
    private val rightAnchor: String,
) {
    /** Nombre de caractères UTF-16 effectivement conservés par cette fenêtre. */
    val retainedCharacterCount: Int
        get() = baseline.length + leftAnchor.length + rightAnchor.length

    /** Extrait la version courante de la même plage, ou refuse si les ancres dérivent. */
    fun capture(fullText: String): String? {
        if (insertionStart > fullText.length) return null
        if (leftAnchorStart + leftAnchor.length > fullText.length) return null
        if (!fullText.regionMatches(leftAnchorStart, leftAnchor, 0, leftAnchor.length)) {
            return null
        }

        val currentEnd = if (rightAnchor.isEmpty()) {
            fullText.length
        } else {
            findClosestRightAnchor(fullText) ?: return null
        }
        if (currentEnd < insertionStart || currentEnd - insertionStart > MAX_CAPTURE_CHARS) {
            return null
        }
        if (fullText.splitsSurrogatePair(insertionStart) || fullText.splitsSurrogatePair(currentEnd)) {
            return null
        }
        val captured = fullText.substring(insertionStart, currentEnd)
        return captured.takeIf { it.hasWellFormedUtf16() }
    }

    private fun findClosestRightAnchor(fullText: String): Int? {
        var occurrence = fullText.indexOf(rightAnchor, startIndex = insertionStart)
        var closest = -1
        var closestDistance = Int.MAX_VALUE
        while (occurrence >= 0 && occurrence - insertionStart <= MAX_CAPTURE_CHARS) {
            val distance = abs(occurrence - expectedInsertionEnd)
            if (distance < closestDistance) {
                closest = occurrence
                closestDistance = distance
            }
            occurrence = fullText.indexOf(rightAnchor, startIndex = occurrence + 1)
        }
        return closest.takeIf { it >= 0 }
    }

    companion object {
        private const val ANCHOR_CHARS = 32
        internal const val MAX_INSERTED_CHARS = 512
        internal const val MAX_CAPTURE_CHARS = 640

        fun create(
            fullText: String,
            insertionStart: Int,
            insertionEnd: Int,
        ): CorrectionWatchWindow? {
            if (!fullText.hasWellFormedUtf16()) return null
            if (insertionStart !in 0..fullText.length) return null
            if (insertionEnd !in insertionStart..fullText.length) return null
            val insertedLength = insertionEnd - insertionStart
            if (insertedLength !in 1..MAX_INSERTED_CHARS) return null
            if (
                fullText.splitsSurrogatePair(insertionStart) ||
                fullText.splitsSurrogatePair(insertionEnd)
            ) {
                return null
            }

            var leftAnchorStart = (insertionStart - ANCHOR_CHARS).coerceAtLeast(0)
            var rightAnchorEnd = (insertionEnd + ANCHOR_CHARS).coerceAtMost(fullText.length)
            if (fullText.splitsSurrogatePair(leftAnchorStart)) leftAnchorStart--
            if (fullText.splitsSurrogatePair(rightAnchorEnd)) rightAnchorEnd++
            val rightAnchor = fullText.substring(insertionEnd, rightAnchorEnd)
            // Sans ancre droite, il est impossible de distinguer une
            // correction du dernier mot d'une nouvelle phrase tapée après la
            // dictée. L'apprentissage est alors volontairement désactivé.
            if (rightAnchor.isEmpty()) return null

            return CorrectionWatchWindow(
                baseline = fullText.substring(insertionStart, insertionEnd),
                insertionStart = insertionStart,
                expectedInsertionEnd = insertionEnd,
                leftAnchorStart = leftAnchorStart,
                leftAnchor = fullText.substring(leftAnchorStart, insertionStart),
                rightAnchor = rightAnchor,
            )
        }
    }
}

private fun String.splitsSurrogatePair(offset: Int): Boolean =
    offset > 0 &&
        offset < length &&
        this[offset - 1].isHighSurrogate() &&
        this[offset].isLowSurrogate()

private fun String.hasWellFormedUtf16(): Boolean {
    var index = 0
    while (index < length) {
        when {
            this[index].isHighSurrogate() -> {
                if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return false
                index += 2
            }

            this[index].isLowSurrogate() -> return false
            else -> index++
        }
    }
    return true
}
