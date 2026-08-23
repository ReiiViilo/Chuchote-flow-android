package dev.soupslurpr.transcribro.remote

import java.io.IOException
import java.io.Reader

internal enum class RemoteResponseFailure {
    MALFORMED,
    TOO_LARGE,
}

internal sealed interface RemoteResponseResult {
    data class Success(val text: String?) : RemoteResponseResult
    data class Failure(val code: RemoteResponseFailure) : RemoteResponseResult
}

private class RemoteResponseContractException : IllegalArgumentException()

/** Le contrat du relais accepte uniquement une vraie chaîne JSON. */
internal object RemoteTextFieldContract {
    fun requireString(value: Any?): String =
        value as? String ?: throw RemoteResponseContractException()
}

/** Décode une petite réponse de contrôle sans conserver ni journaliser son corps. */
internal object RemoteResponseDecoder {
    private const val DEFAULT_MAX_CHARS = 262_144

    fun decode(
        reader: Reader,
        maxChars: Int = DEFAULT_MAX_CHARS,
        extractText: (String) -> String?,
    ): RemoteResponseResult {
        require(maxChars > 0)
        val body = StringBuilder(minOf(maxChars, 8_192))
        val buffer = CharArray(8_192)

        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            if (body.length + count > maxChars) {
                return RemoteResponseResult.Failure(RemoteResponseFailure.TOO_LARGE)
            }
            body.append(buffer, 0, count)
        }

        return try {
            val text = extractText(body.toString())
                ?.trim()
                ?.ifEmpty { null }
            RemoteResponseResult.Success(text)
        } catch (_: Exception) {
            RemoteResponseResult.Failure(RemoteResponseFailure.MALFORMED)
        }
    }
}

/** Produit seulement des codes bornés; ni corps HTTP ni message d'exception. */
internal object RemoteRelayDiagnostic {
    fun responseFailure(code: RemoteResponseFailure): String = when (code) {
        RemoteResponseFailure.MALFORMED -> "relay_response_malformed"
        RemoteResponseFailure.TOO_LARGE -> "relay_response_too_large"
    }

    fun transportFailure(error: Throwable): String = when (error) {
        is IOException -> "relay_transport_io"
        is SecurityException -> "relay_transport_security"
        is IllegalArgumentException -> "relay_transport_configuration"
        else -> "relay_transport_unexpected"
    }
}
