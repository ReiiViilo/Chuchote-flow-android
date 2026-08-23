package dev.soupslurpr.transcribro.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextInsertionComposerTest {
    @Test
    fun `verification confirms matching text and cursor`() {
        assertEquals(
            TextInsertionVerification.CONFIRMED,
            TextInsertionVerifier.verify(
                refreshed = true,
                expectedText = "Bonjour",
                expectedCursor = 7,
                actualText = "Bonjour",
                actualSelectionStart = 7,
                actualSelectionEnd = 7,
            ),
        )
    }

    @Test
    fun `verification distinguishes confirmed text from uncertain cursor`() {
        assertEquals(
            TextInsertionVerification.CURSOR_UNCONFIRMED,
            TextInsertionVerifier.verify(
                refreshed = true,
                expectedText = "Bonjour",
                expectedCursor = 7,
                actualText = "Bonjour",
                actualSelectionStart = 0,
                actualSelectionEnd = 0,
            ),
        )
    }

    @Test
    fun `verification never claims insertion when refreshed text differs`() {
        assertEquals(
            TextInsertionVerification.ACTION_UNCONFIRMED,
            TextInsertionVerifier.verify(
                refreshed = true,
                expectedText = "Bonjour",
                expectedCursor = 7,
                actualText = "",
                actualSelectionStart = 0,
                actualSelectionEnd = 0,
            ),
        )
    }

    @Test
    fun `inserts into an empty reliable field`() {
        assertComposition(
            actual = TextInsertionComposer.compose("", 0, 0, "Bonjour"),
            text = "Bonjour",
            cursor = 7,
            contentStart = 0,
            contentEnd = 7,
            replacedStart = 0,
            replacedEnd = 0,
        )
    }

    @Test
    fun `inserts at cursor and returns final cursor`() {
        assertComposition(
            actual = TextInsertionComposer.compose("Bonjour monde", 8, 8, "cher"),
            text = "Bonjour cher monde",
            cursor = 13,
            contentStart = 8,
            contentEnd = 12,
            replacedStart = 8,
            replacedEnd = 8,
        )
    }

    @Test
    fun `adds a leading boundary when cursor is before existing whitespace`() {
        assertComposition(
            actual = TextInsertionComposer.compose("Bonjour monde", 7, 7, "cher"),
            text = "Bonjour cher monde",
            cursor = 12,
            contentStart = 8,
            contentEnd = 12,
            replacedStart = 7,
            replacedEnd = 7,
        )
    }

    @Test
    fun `reports exact dictated bounds when both word boundaries are synthetic`() {
        assertComposition(
            actual = TextInsertionComposer.compose("AB", 1, 1, "mot"),
            text = "A mot B",
            cursor = 6,
            contentStart = 2,
            contentEnd = 5,
            replacedStart = 1,
            replacedEnd = 1,
        )
    }

    @Test
    fun `does not add an artificial space before punctuation`() {
        assertComposition(
            actual = TextInsertionComposer.compose("Bonjour monde", 7, 7, ","),
            text = "Bonjour, monde",
            cursor = 8,
            contentStart = 7,
            contentEnd = 8,
            replacedStart = 7,
            replacedEnd = 7,
        )
    }

    @Test
    fun `adds boundaries at beginning and end of a field`() {
        assertComposition(
            actual = TextInsertionComposer.compose("monde", 0, 0, "Salut"),
            text = "Salut monde",
            cursor = 6,
            contentStart = 0,
            contentEnd = 5,
            replacedStart = 0,
            replacedEnd = 0,
        )
        assertComposition(
            actual = TextInsertionComposer.compose("Bonjour", 7, 7, "monde"),
            text = "Bonjour monde",
            cursor = 13,
            contentStart = 8,
            contentEnd = 13,
            replacedStart = 7,
            replacedEnd = 7,
        )
    }

    @Test
    fun `IME commit refuses a cursor that splits a surrogate pair`() {
        assertNull(
            TextInsertionComposer.prepareForImeCommit(
                before = "\uD83D",
                after = "\uDE00",
                inserted = "x",
            ),
        )
    }

    @Test
    fun `word boundaries classify supplementary unicode letters by code point`() {
        assertEquals(
            " mot",
            TextInsertionComposer.prepareForImeCommit(
                before = "𐐀",
                after = "",
                inserted = "mot",
            ),
        )
        assertEquals(
            "mot ",
            TextInsertionComposer.prepareForImeCommit(
                before = "",
                after = "𐐀",
                inserted = "mot",
            ),
        )
    }

    @Test
    fun `IME commit refuses malformed inserted unicode`() {
        assertNull(
            TextInsertionComposer.prepareForImeCommit(
                before = "A",
                after = "B",
                inserted = "\uDE00",
            ),
        )
    }

    @Test
    fun `IME commit refuses unavailable cursor context`() {
        assertNull(
            TextInsertionComposer.prepareForImeCommit(
                before = null,
                after = "B",
                inserted = "x",
            ),
        )
        assertNull(
            TextInsertionComposer.prepareForImeCommit(
                before = "A",
                after = null,
                inserted = "x",
            ),
        )
    }

    @Test
    fun `replaces selected text and places cursor after insertion`() {
        assertComposition(
            actual = TextInsertionComposer.compose("Bonjour nuage", 8, 13, "Claude"),
            text = "Bonjour Claude",
            cursor = 14,
            contentStart = 8,
            contentEnd = 14,
            replacedStart = 8,
            replacedEnd = 13,
        )
    }

    @Test
    fun `preserves a valid reversed selection`() {
        assertComposition(
            actual = TextInsertionComposer.compose("Bonjour nuage", 13, 8, "Claude"),
            text = "Bonjour Claude",
            cursor = 14,
            contentStart = 8,
            contentEnd = 14,
            replacedStart = 8,
            replacedEnd = 13,
        )
    }

    @Test
    fun `preserves multiline text around the selection`() {
        assertComposition(
            actual = TextInsertionComposer.compose("un\nX\ntrois", 3, 4, "deux"),
            text = "un\ndeux\ntrois",
            cursor = 7,
            contentStart = 3,
            contentEnd = 7,
            replacedStart = 3,
            replacedEnd = 4,
        )
    }

    @Test
    fun `refuses when only selection start is invalid`() {
        assertNull(TextInsertionComposer.compose("Bonjour", -1, 4, "Salut"))
    }

    @Test
    fun `refuses when only selection end is invalid`() {
        assertNull(TextInsertionComposer.compose("Bonjour", 4, 99, "Salut"))
    }

    @Test
    fun `refuses when both selection bounds are unavailable`() {
        assertNull(TextInsertionComposer.compose("Bonjour", -1, -1, "Salut"))
    }

    @Test
    fun `inserts unicode around an intact surrogate pair`() {
        assertComposition(
            actual = TextInsertionComposer.compose("A👋🏽", 5, 5, " monde"),
            text = "A👋🏽 monde",
            cursor = 11,
            contentStart = 5,
            contentEnd = 11,
            replacedStart = 5,
            replacedEnd = 5,
        )
    }

    @Test
    fun `reports unicode dictated bounds in UTF-16 without splitting code points`() {
        assertComposition(
            actual = TextInsertionComposer.compose("A𐐀", 1, 1, "𐐁"),
            text = "A 𐐁 𐐀",
            cursor = 5,
            contentStart = 2,
            contentEnd = 4,
            replacedStart = 1,
            replacedEnd = 1,
        )
    }

    @Test
    fun `span mapper preserves ranges outside a replaced selection`() {
        assertEquals(
            TextSpanRemap.preserve(0, 3),
            TextInsertionSpanMapper.remap(
                sourceLength = 10,
                spanStart = 0,
                spanEnd = 3,
                replacedStart = 4,
                replacedEnd = 7,
                replacementLength = 5,
            ),
        )
        assertEquals(
            TextSpanRemap.preserve(9, 12),
            TextInsertionSpanMapper.remap(
                sourceLength = 10,
                spanStart = 7,
                spanEnd = 10,
                replacedStart = 4,
                replacedEnd = 7,
                replacementLength = 5,
            ),
        )
    }

    @Test
    fun `span mapper reshapes surviving spans around a replaced selection`() {
        assertEquals(
            TextSpanRemap.preserve(1, 10),
            TextInsertionSpanMapper.remap(
                sourceLength = 10,
                spanStart = 1,
                spanEnd = 8,
                replacedStart = 4,
                replacedEnd = 7,
                replacementLength = 5,
            ),
        )
        assertEquals(
            TextSpanRemap.preserve(1, 4),
            TextInsertionSpanMapper.remap(
                sourceLength = 10,
                spanStart = 1,
                spanEnd = 6,
                replacedStart = 4,
                replacedEnd = 7,
                replacementLength = 5,
            ),
        )
        assertEquals(
            TextSpanRemap.preserve(9, 11),
            TextInsertionSpanMapper.remap(
                sourceLength = 10,
                spanStart = 5,
                spanEnd = 9,
                replacedStart = 4,
                replacedEnd = 7,
                replacementLength = 5,
            ),
        )
    }

    @Test
    fun `span mapper drops spans wholly replaced and rejects invalid metadata`() {
        assertEquals(
            TextSpanRemap.DROP,
            TextInsertionSpanMapper.remap(
                sourceLength = 10,
                spanStart = 4,
                spanEnd = 7,
                replacedStart = 4,
                replacedEnd = 7,
                replacementLength = 5,
            ),
        )
        assertEquals(
            TextSpanRemap.UNSAFE,
            TextInsertionSpanMapper.remap(
                sourceLength = 10,
                spanStart = -1,
                spanEnd = 7,
                replacedStart = 4,
                replacedEnd = 7,
                replacementLength = 5,
            ),
        )
        assertEquals(
            TextSpanRemap.UNSAFE,
            TextInsertionSpanMapper.remap(
                sourceLength = Int.MAX_VALUE,
                spanStart = 0,
                spanEnd = 0,
                replacedStart = 0,
                replacedEnd = 0,
                replacementLength = Int.MAX_VALUE,
            ),
        )
    }

    @Test
    fun `refuses a cursor that splits a surrogate pair`() {
        assertNull(TextInsertionComposer.compose("A😀B", 2, 2, "x"))
    }

    @Test
    fun `refuses a selection bound that splits a surrogate pair`() {
        assertNull(TextInsertionComposer.compose("A😀B", 1, 2, "x"))
    }

    @Test
    fun `refuses unavailable source text`() {
        assertNull(TextInsertionComposer.compose(null, 0, 0, "texte"))
    }

    @Test
    fun `refuses malformed source unicode`() {
        assertNull(TextInsertionComposer.compose("A\uD83DB", 1, 1, "x"))
    }

    @Test
    fun `refuses malformed inserted unicode`() {
        assertNull(TextInsertionComposer.compose("AB", 1, 1, "\uDE00"))
    }

    private fun assertComposition(
        actual: TextInsertionComposition?,
        text: String,
        cursor: Int,
        contentStart: Int,
        contentEnd: Int,
        replacedStart: Int,
        replacedEnd: Int,
    ) {
        requireNotNull(actual)
        assertEquals(text, actual.text)
        assertEquals(cursor, actual.cursor)
        assertEquals(contentStart, actual.contentStart)
        assertEquals(contentEnd, actual.contentEnd)
        assertEquals(replacedStart, actual.replacedStart)
        assertEquals(replacedEnd, actual.replacedEnd)
    }
}
