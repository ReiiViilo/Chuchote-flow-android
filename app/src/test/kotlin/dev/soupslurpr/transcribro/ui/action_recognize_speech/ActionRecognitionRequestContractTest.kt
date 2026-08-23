package dev.soupslurpr.transcribro.ui.action_recognize_speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionRecognitionRequestContractTest {
    @Test
    fun `only the public recognize speech action is accepted`() {
        assertNull(
            ActionRecognitionRequestContract.validate(
                actionMatches = false,
                languageModel = "free_form",
                partialResults = true,
                autoStop = false,
                hasUnsupportedExtras = false,
                pendingBundleWithoutPendingIntent = false,
                extrasHaveExpectedTypes = true,
                prompt = null,
                language = null,
                maxResults = null,
            ),
        )
    }

    @Test
    fun `language model is required by the public contract`() {
        assertNull(
            ActionRecognitionRequestContract.validate(
                actionMatches = true,
                languageModel = "  ",
                partialResults = false,
                autoStop = true,
                hasUnsupportedExtras = false,
                pendingBundleWithoutPendingIntent = false,
                extrasHaveExpectedTypes = true,
                prompt = null,
                language = null,
                maxResults = null,
            ),
        )
    }

    @Test
    fun `only Android documented language models are accepted`() {
        val webSearch = ActionRecognitionRequestContract.validate(
            actionMatches = true,
            languageModel = "web_search",
            partialResults = false,
            autoStop = true,
            hasUnsupportedExtras = false,
            pendingBundleWithoutPendingIntent = false,
            extrasHaveExpectedTypes = true,
            prompt = null,
            language = null,
            maxResults = null,
        )

        requireNotNull(webSearch)
        assertEquals("web_search", webSearch.languageModel)
        assertNull(
            ActionRecognitionRequestContract.validate(
                actionMatches = true,
                languageModel = "dictation",
                partialResults = false,
                autoStop = true,
                hasUnsupportedExtras = false,
                pendingBundleWithoutPendingIntent = false,
                extrasHaveExpectedTypes = true,
                prompt = null,
                language = null,
                maxResults = null,
            ),
        )
    }

    @Test
    fun `validated request retains only values honored by recognition`() {
        val request = requireNotNull(
            ActionRecognitionRequestContract.validate(
                actionMatches = true,
                languageModel = " free_form ",
                partialResults = true,
                autoStop = false,
                hasUnsupportedExtras = false,
                pendingBundleWithoutPendingIntent = false,
                extrasHaveExpectedTypes = true,
                prompt = null,
                language = null,
                maxResults = null,
            ),
        )

        assertEquals("free_form", request.languageModel)
        assertTrue(request.partialResults)
        assertFalse(request.autoStop)
    }

    @Test
    fun `unsupported extras reject the whole exported request`() {
        assertNull(
            ActionRecognitionRequestContract.validate(
                actionMatches = true,
                languageModel = "free_form",
                partialResults = false,
                autoStop = true,
                hasUnsupportedExtras = true,
                pendingBundleWithoutPendingIntent = false,
                extrasHaveExpectedTypes = true,
                prompt = null,
                language = null,
                maxResults = null,
            ),
        )
    }

    @Test
    fun `pending intent bundle cannot be supplied without pending intent`() {
        assertNull(
            ActionRecognitionRequestContract.validate(
                actionMatches = true,
                languageModel = "free_form",
                partialResults = false,
                autoStop = true,
                hasUnsupportedExtras = false,
                pendingBundleWithoutPendingIntent = true,
                extrasHaveExpectedTypes = true,
                prompt = null,
                language = null,
                maxResults = null,
            ),
        )
    }

    @Test
    fun `wrongly typed supported extras fail closed`() {
        assertNull(
            ActionRecognitionRequestContract.validate(
                actionMatches = true,
                languageModel = "free_form",
                partialResults = false,
                autoStop = true,
                hasUnsupportedExtras = false,
                pendingBundleWithoutPendingIntent = false,
                extrasHaveExpectedTypes = false,
                prompt = null,
                language = null,
                maxResults = null,
            ),
        )
    }

    @Test
    fun `supported prompt language and positive max results are accepted but not retained`() {
        val request = ActionRecognitionRequestContract.validate(
            actionMatches = true,
            languageModel = "free_form",
            partialResults = false,
            autoStop = true,
            hasUnsupportedExtras = false,
            pendingBundleWithoutPendingIntent = false,
            extrasHaveExpectedTypes = true,
            prompt = "Parlez maintenant",
            language = "fr-CA",
            maxResults = 3,
        )

        requireNotNull(request)
        assertEquals("free_form", request.languageModel)
    }

    @Test
    fun `max results must be positive when supplied`() {
        assertNull(
            ActionRecognitionRequestContract.validate(
                actionMatches = true,
                languageModel = "free_form",
                partialResults = false,
                autoStop = true,
                hasUnsupportedExtras = false,
                pendingBundleWithoutPendingIntent = false,
                extrasHaveExpectedTypes = true,
                prompt = null,
                language = null,
                maxResults = 0,
            ),
        )
    }
}
