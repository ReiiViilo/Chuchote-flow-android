package dev.soupslurpr.transcribro.overlay

/**
 * Texte complet à remettre dans le champ et bornes UTF-16 de l'opération.
 *
 * [contentStart] et [contentEnd] encadrent exactement le contenu dicté. Ils
 * excluent les espaces que le compositeur ajoute pour éviter la fusion de deux
 * mots. [replacedStart] et [replacedEnd] décrivent la sélection source, ce qui
 * permet de remanier les spans sans tenter de la deviner depuis le texte final.
 */
data class TextInsertionComposition(
    val text: String,
    val cursor: Int,
    val contentStart: Int,
    val contentEnd: Int,
    val replacedStart: Int,
    val replacedEnd: Int,
)

/** Résultat pur du remaniement conservateur d'un span autour d'un remplacement. */
sealed class TextSpanRemap {
    data class Preserve(val start: Int, val end: Int) : TextSpanRemap()

    object DROP : TextSpanRemap()

    object UNSAFE : TextSpanRemap()

    companion object {
        fun preserve(start: Int, end: Int): TextSpanRemap = Preserve(start, end)
    }
}

/**
 * Remanie une plage de span sans dépendre d'Android, pour garder ce contrat
 * vérifiable en test JVM pur.
 *
 * Les parties de span associées au texte supprimé disparaissent. Les parties
 * encore présentes sont conservées; un span qui encadrait toute la sélection
 * encadre aussi le texte de remplacement. Toute métadonnée incohérente échoue
 * fermé au lieu d'être corrigée silencieusement.
 */
object TextInsertionSpanMapper {
    fun remap(
        sourceLength: Int,
        spanStart: Int,
        spanEnd: Int,
        replacedStart: Int,
        replacedEnd: Int,
        replacementLength: Int,
    ): TextSpanRemap {
        if (
            sourceLength < 0 ||
            spanStart !in 0..sourceLength ||
            spanEnd !in spanStart..sourceLength ||
            replacedStart !in 0..sourceLength ||
            replacedEnd !in replacedStart..sourceLength ||
            replacementLength < 0
        ) {
            return TextSpanRemap.UNSAFE
        }

        val finalLength = sourceLength.toLong() -
            (replacedEnd - replacedStart).toLong() +
            replacementLength.toLong()
        if (finalLength !in 0..Int.MAX_VALUE.toLong()) return TextSpanRemap.UNSAFE

        val delta = replacementLength - (replacedEnd - replacedStart)
        val replacementEnd = replacedStart + replacementLength

        // Une plage qui finit au point de remplacement reste à gauche. Une
        // plage qui commence après la sélection suit le texte déplacé.
        if (spanEnd <= replacedStart) return TextSpanRemap.preserve(spanStart, spanEnd)
        if (spanStart >= replacedEnd) {
            return TextSpanRemap.preserve(spanStart + delta, spanEnd + delta)
        }

        // La sélection a entièrement remplacé le contenu auquel ce span était
        // attaché : ne pas appliquer son sens au nouveau texte dicté.
        if (spanStart >= replacedStart && spanEnd <= replacedEnd) {
            return TextSpanRemap.DROP
        }

        if (spanStart < replacedStart && spanEnd > replacedEnd) {
            return TextSpanRemap.preserve(spanStart, spanEnd + delta)
        }
        if (spanStart < replacedStart) {
            return TextSpanRemap.preserve(spanStart, replacedStart)
        }
        if (spanEnd > replacedEnd) {
            return TextSpanRemap.preserve(replacementEnd, spanEnd + delta)
        }
        return TextSpanRemap.UNSAFE
    }
}

/** Niveau de preuve observable après une demande `ACTION_SET_TEXT`. */
enum class TextInsertionVerification {
    CONFIRMED,
    CURSOR_UNCONFIRMED,
    ACTION_UNCONFIRMED,
}

object TextInsertionVerifier {
    fun verify(
        refreshed: Boolean,
        expectedText: String,
        expectedCursor: Int,
        actualText: CharSequence?,
        actualSelectionStart: Int,
        actualSelectionEnd: Int,
    ): TextInsertionVerification {
        if (!refreshed || actualText?.toString() != expectedText) {
            return TextInsertionVerification.ACTION_UNCONFIRMED
        }
        if (
            actualSelectionStart != expectedCursor ||
            actualSelectionEnd != expectedCursor
        ) {
            return TextInsertionVerification.CURSOR_UNCONFIRMED
        }
        return TextInsertionVerification.CONFIRMED
    }
}

/**
 * Prépare une insertion sûre pour `ACTION_SET_TEXT`.
 *
 * Android exprime les sélections en offsets UTF-16. Réécrire tout le champ à
 * partir d'une borne absente, hors limites ou située au milieu d'une paire de
 * substituts pourrait supprimer du texte ou produire une chaîne corrompue.
 * Dans ces cas, le compositeur refuse l'opération et laisse l'appelant choisir
 * un repli explicite qui ne modifie pas le champ.
 */
object TextInsertionComposer {
    private data class BoundedInsertion(
        val text: String,
        val contentStartOffset: Int,
        val contentEndOffset: Int,
    )

    /**
     * Ajoute uniquement les séparateurs nécessaires pour empêcher deux mots de
     * fusionner. La ponctuation reste intacte : insérer `,` avant une espace ne
     * fabrique pas d'espace avant la virgule.
     */
    fun withWordBoundaries(
        before: CharSequence?,
        after: CharSequence?,
        inserted: String,
    ): String = boundInsertion(before, after, inserted).text

    private fun boundInsertion(
        before: CharSequence?,
        after: CharSequence?,
        inserted: String,
    ): BoundedInsertion {
        if (inserted.isEmpty()) return BoundedInsertion("", 0, 0)
        val needsLeading = before.lastCodePointOrNull()?.let {
            Character.isLetterOrDigit(it)
        } == true && inserted.firstCodePointOrNull()?.let {
            Character.isLetterOrDigit(it)
        } == true
        val needsTrailing = inserted.lastCodePointOrNull()?.let {
            Character.isLetterOrDigit(it)
        } == true && after.firstCodePointOrNull()?.let {
            Character.isLetterOrDigit(it)
        } == true
        val boundedText = buildString(inserted.length + 2) {
            if (needsLeading) append(' ')
            append(inserted)
            if (needsTrailing) append(' ')
        }
        val contentStartOffset = if (needsLeading) 1 else 0
        return BoundedInsertion(
            text = boundedText,
            contentStartOffset = contentStartOffset,
            contentEndOffset = contentStartOffset + inserted.length,
        )
    }

    /**
     * Prépare le texte pour `InputConnection.commitText` sans réécrire tout le
     * champ. Deux unités UTF-16 de chaque côté suffisent à reconnaître un point
     * de code supplémentaire et à refuser un curseur placé au milieu de sa
     * paire de substituts.
     */
    fun prepareForImeCommit(
        before: CharSequence?,
        after: CharSequence?,
        inserted: String,
    ): String? {
        // `null` signifie que l'éditeur ne permet pas de prouver la borne;
        // seul un vrai texte vide prouve le début ou la fin du champ.
        val beforeText = before?.toString() ?: return null
        val afterText = after?.toString() ?: return null
        if (!inserted.hasWellFormedUtf16()) return null
        if (!beforeText.hasSafeCodePointBeforeCursor()) return null
        if (!afterText.hasSafeCodePointAfterCursor()) return null
        return withWordBoundaries(beforeText, afterText, inserted)
    }

    fun compose(
        existing: CharSequence?,
        selectionStart: Int,
        selectionEnd: Int,
        inserted: String,
    ): TextInsertionComposition? {
        val source = existing?.toString() ?: return null
        if (!source.hasWellFormedUtf16() || !inserted.hasWellFormedUtf16()) return null
        if (selectionStart !in 0..source.length || selectionEnd !in 0..source.length) {
            return null
        }
        if (source.splitsSurrogatePair(selectionStart) || source.splitsSurrogatePair(selectionEnd)) {
            return null
        }

        val from = minOf(selectionStart, selectionEnd)
        val to = maxOf(selectionStart, selectionEnd)
        val boundedInsertion = boundInsertion(
            before = source.substring(0, from),
            after = source.substring(to),
            inserted = inserted,
        )
        return TextInsertionComposition(
            text = source.replaceRange(from, to, boundedInsertion.text),
            cursor = from + boundedInsertion.text.length,
            contentStart = from + boundedInsertion.contentStartOffset,
            contentEnd = from + boundedInsertion.contentEndOffset,
            replacedStart = from,
            replacedEnd = to,
        )
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

    private fun String.hasSafeCodePointBeforeCursor(): Boolean {
        if (isEmpty()) return true
        val last = this[lastIndex]
        return when {
            last.isHighSurrogate() -> false
            last.isLowSurrogate() -> length >= 2 && this[length - 2].isHighSurrogate()
            else -> true
        }
    }

    private fun String.hasSafeCodePointAfterCursor(): Boolean {
        if (isEmpty()) return true
        val first = this[0]
        return when {
            first.isLowSurrogate() -> false
            first.isHighSurrogate() -> length >= 2 && this[1].isLowSurrogate()
            else -> true
        }
    }

    private fun CharSequence?.firstCodePointOrNull(): Int? {
        if (this == null || isEmpty()) return null
        val first = this[0]
        return when {
            first.isHighSurrogate() && length >= 2 && this[1].isLowSurrogate() ->
                Character.toCodePoint(first, this[1])
            first.isSurrogate() -> null
            else -> first.code
        }
    }

    private fun CharSequence?.lastCodePointOrNull(): Int? {
        if (this == null || isEmpty()) return null
        val last = this[length - 1]
        return when {
            last.isLowSurrogate() && length >= 2 && this[length - 2].isHighSurrogate() ->
                Character.toCodePoint(this[length - 2], last)
            last.isSurrogate() -> null
            else -> last.code
        }
    }
}
