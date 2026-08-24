package dev.soupslurpr.transcribro.overlay

/**
 * État normalisé d'un champ éditable vu par l'accessibilité.
 *
 * Les champs natifs exposent leur texte et une sélection valide. Les champs
 * web et React Native (Gmail dans Chrome, ChatGPT, Claude) sont moins
 * disciplinés : un champ vide peut présenter son placeholder comme texte
 * (`isShowingHintText`), un texte `null`, ou une sélection absente `(-1, -1)`.
 * Refuser ces états revenait à rejeter l'insertion dans un champ pourtant vide
 * et sûr. Cette normalisation est pure pour rester vérifiable en test JVM.
 */
data class EditableFieldState(
    val existing: String,
    val selectionStart: Int,
    val selectionEnd: Int,
) {
    companion object {
        fun read(
            rawText: CharSequence?,
            showingHint: Boolean,
            selectionStart: Int,
            selectionEnd: Int,
        ): EditableFieldState {
            // Un placeholder n'est pas du contenu : le champ est vide.
            if (showingHint) return EditableFieldState("", 0, 0)

            val text = rawText?.toString().orEmpty()
            if (text.isEmpty()) return EditableFieldState("", 0, 0)

            // Texte présent mais curseur inconnu : insérer en fin de champ.
            // Le texte existant est intégralement conservé; la vérification
            // après ACTION_SET_TEXT signale toute divergence.
            if (selectionStart !in 0..text.length || selectionEnd !in 0..text.length) {
                return EditableFieldState(text, text.length, text.length)
            }

            return EditableFieldState(text, selectionStart, selectionEnd)
        }
    }
}
