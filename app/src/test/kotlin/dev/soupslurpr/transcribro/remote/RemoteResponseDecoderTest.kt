package dev.soupslurpr.transcribro.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.StringReader

class RemoteResponseDecoderTest {

    @Test
    fun `a valid relay object yields its trimmed transcript`() {
        val result = RemoteResponseDecoder.decode(
            reader = StringReader("{\"text\":\"  Bonjour Olivier  \"}"),
            maxChars = 1_024,
            extractText = { body ->
                assertEquals("{\"text\":\"  Bonjour Olivier  \"}", body)
                "  Bonjour Olivier  "
            },
        )

        assertEquals(RemoteResponseResult.Success("Bonjour Olivier"), result)
    }

    @Test
    fun `malformed content is reduced to a non sensitive code`() {
        val secretTranscript = "DICTEE_ULTRA_SECRETE"
        val result = RemoteResponseDecoder.decode(
            reader = StringReader("$secretTranscript n'est pas du JSON"),
            maxChars = 1_024,
            extractText = { throw IllegalArgumentException(secretTranscript) },
        )

        assertEquals(
            RemoteResponseResult.Failure(RemoteResponseFailure.MALFORMED),
            result,
        )
        val diagnostic = RemoteRelayDiagnostic.responseFailure(
            (result as RemoteResponseResult.Failure).code,
        )
        assertEquals("relay_response_malformed", diagnostic)
        assertFalse(diagnostic.contains(secretTranscript))
    }

    @Test
    fun `relay body is rejected once the bounded reader limit is exceeded`() {
        val result = RemoteResponseDecoder.decode(
            reader = StringReader("x".repeat(33)),
            maxChars = 32,
            extractText = { throw AssertionError("Le parseur ne doit pas être appelé") },
        )

        assertEquals(
            RemoteResponseResult.Failure(RemoteResponseFailure.TOO_LARGE),
            result,
        )
    }

    @Test
    fun `only an actual string is accepted as the relay text field`() {
        val invalidValues: List<Any?> = listOf(
            mapOf("error" to "unexpected"),
            123,
            null,
        )

        invalidValues.forEach { invalidValue ->
            val result = RemoteResponseDecoder.decode(
                reader = StringReader("{}"),
                maxChars = 1_024,
                extractText = {
                    RemoteTextFieldContract.requireString(invalidValue)
                },
            )

            assertEquals(
                RemoteResponseResult.Failure(RemoteResponseFailure.MALFORMED),
                result,
            )
        }
    }

    @Test
    fun `transport diagnostics never contain the exception message`() {
        val secretTranscript = "DICTEE_ULTRA_SECRETE"

        val known = RemoteRelayDiagnostic.transportFailure(IOException(secretTranscript))
        val unexpected = RemoteRelayDiagnostic.transportFailure(
            IllegalStateException(secretTranscript),
        )

        assertEquals("relay_transport_io", known)
        assertEquals("relay_transport_unexpected", unexpected)
        assertTrue(listOf(known, unexpected).none { it.contains(secretTranscript) })
    }
}
