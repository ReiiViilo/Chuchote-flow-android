package dev.soupslurpr.transcribro.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Non-régression du repli presse-papiers dans les champs web et React Native :
 * un placeholder présenté comme texte, un texte `null` ou une sélection
 * `(-1, -1)` faisaient refuser l'insertion directe d'un champ pourtant sûr
 * (observé dans ChatGPT sur SM-S721W le 24 août 2026).
 */
class EditableFieldStateTest {

    @Test
    fun `un placeholder n'est pas du contenu`() {
        val state = EditableFieldState.read(
            rawText = "Poser une question",
            showingHint = true,
            selectionStart = -1,
            selectionEnd = -1,
        )

        assertEquals(EditableFieldState("", 0, 0), state)
    }

    @Test
    fun `texte null et selection absente valent un champ vide`() {
        val state = EditableFieldState.read(
            rawText = null,
            showingHint = false,
            selectionStart = -1,
            selectionEnd = -1,
        )

        assertEquals(EditableFieldState("", 0, 0), state)
    }

    @Test
    fun `texte present mais curseur inconnu insere en fin de champ`() {
        val state = EditableFieldState.read(
            rawText = "Bonjour",
            showingHint = false,
            selectionStart = -1,
            selectionEnd = -1,
        )

        assertEquals(EditableFieldState("Bonjour", 7, 7), state)
    }

    @Test
    fun `une selection valide est conservee telle quelle`() {
        val state = EditableFieldState.read(
            rawText = "Bonjour le monde",
            showingHint = false,
            selectionStart = 8,
            selectionEnd = 10,
        )

        assertEquals(EditableFieldState("Bonjour le monde", 8, 10), state)
    }

    @Test
    fun `une selection hors bornes est ramenee en fin de champ`() {
        val state = EditableFieldState.read(
            rawText = "abc",
            showingHint = false,
            selectionStart = 2,
            selectionEnd = 99,
        )

        assertEquals(EditableFieldState("abc", 3, 3), state)
    }

    @Test
    fun `un champ vide sans placeholder reste un champ vide`() {
        val state = EditableFieldState.read(
            rawText = "",
            showingHint = false,
            selectionStart = 0,
            selectionEnd = 0,
        )

        assertEquals(EditableFieldState("", 0, 0), state)
    }
}
