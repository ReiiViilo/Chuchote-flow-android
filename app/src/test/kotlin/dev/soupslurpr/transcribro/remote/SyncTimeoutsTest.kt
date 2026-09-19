package dev.soupslurpr.transcribro.remote

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le contrat qui tient l'ordre des écritures au relais : un envoi n'est tenu
 * pour expiré qu'une fois que le relais l'a lui-même abandonné.
 */
class SyncTimeoutsTest {

    @Test
    fun `chaque delai de lecture depasse la duree que le relais s accorde`() {
        assertTrue(SyncTimeouts.READ_TIMEOUT_MS > SyncTimeouts.RELAY_MAX_DURATION_MS)
        assertTrue(SyncTimeouts.BATCH_READ_TIMEOUT_MS > SyncTimeouts.RELAY_MAX_DURATION_MS)
    }
}
