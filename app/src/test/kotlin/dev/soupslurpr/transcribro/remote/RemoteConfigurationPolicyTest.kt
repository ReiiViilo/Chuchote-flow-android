package dev.soupslurpr.transcribro.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteConfigurationPolicyTest {
    private val endpointA = RemoteConfigurationSnapshot(
        enabled = true,
        baseUrl = "https://relay-a.example",
        token = "token-a",
    )

    @Test
    fun `a complete shared link replaces url and token as one pair`() {
        val endpointB = RemoteConfigurationPolicy.importSharedLink(
            current = endpointA,
            value = "https://relay-b.example#token-b",
        )

        assertEquals("https://relay-b.example", endpointB?.baseUrl)
        assertEquals("token-b", endpointB?.token)
        assertFalse(endpointB?.token == "token-a")
    }

    @Test
    fun `an incomplete shared link is rejected without a partial candidate`() {
        assertNull(
            RemoteConfigurationPolicy.importSharedLink(
                current = endpointA,
                value = "https://relay-b.example#",
            ),
        )
        assertEquals("https://relay-a.example", endpointA.baseUrl)
        assertEquals("token-a", endpointA.token)
    }

    @Test
    fun `editing a url alone disables the relay and clears the previous token`() {
        val draft = RemoteConfigurationPolicy.editBaseUrl(
            current = endpointA,
            value = "https://relay-b.example",
        )

        assertFalse(draft.enabled)
        assertEquals("https://relay-b.example", draft.baseUrl)
        assertEquals("", draft.token)
    }

    @Test
    fun `a request target remains an immutable pair while settings change`() {
        val inFlight = endpointA.requestTarget
        val endpointB = RemoteConfigurationPolicy.importSharedLink(
            current = endpointA,
            value = "https://relay-b.example#token-b",
        )

        assertEquals("https://relay-a.example", inFlight?.baseUrl)
        assertEquals("token-a", inFlight?.token)
        assertEquals("https://relay-b.example", endpointB?.requestTarget?.baseUrl)
        assertEquals("token-b", endpointB?.requestTarget?.token)
        assertTrue(endpointB?.enabled == true)
    }
}
