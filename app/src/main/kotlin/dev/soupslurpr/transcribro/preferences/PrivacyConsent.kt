package dev.soupslurpr.transcribro.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import dev.soupslurpr.transcribro.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** Versionne le consentement quand les traitements de données changent. */
object PrivacyConsent {
    const val CURRENT_POLICY_PREFERENCE =
        "ACCEPTED_PRIVACY_POLICY_AND_LICENSE_2026_08_23"
    const val PREVIOUS_POLICY_PREFERENCE =
        "ACCEPTED_PRIVACY_POLICY_AND_LICENSE_V0.3.0"

    /**
     * Une acceptation historique ne vaut jamais acceptation de la politique
     * actuelle. Le paramètre historique rend cette règle de migration
     * explicite et directement testable.
     */
    @Suppress("UNUSED_PARAMETER")
    fun isCurrentPolicyAccepted(
        currentPolicyAccepted: Boolean?,
        previousPolicyAccepted: Boolean?,
    ): Boolean = currentPolicyAccepted == true

    fun acceptanceFlow(context: Context): Flow<Boolean> =
        context.applicationContext.dataStore.data
            .map { preferences ->
                isCurrentPolicyAccepted(
                    currentPolicyAccepted = preferences[
                        booleanPreferencesKey(CURRENT_POLICY_PREFERENCE)
                    ],
                    previousPolicyAccepted = preferences[
                        booleanPreferencesKey(PREVIOUS_POLICY_PREFERENCE)
                    ],
                )
            }
            // Une lecture DataStore corrompue ou indisponible ne doit jamais
            // conserver silencieusement un ancien état "accepté" en mémoire.
            .catch { emit(false) }
            .distinctUntilChanged()

    suspend fun isAccepted(context: Context): Boolean =
        acceptanceFlow(context).first()

    /** Dernière barrière synchrone des callbacks Android non suspendus. */
    fun isAcceptedBlocking(context: Context): Boolean = runCatching {
        runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(CONSENT_READ_TIMEOUT_MS) {
                isAccepted(context.applicationContext)
            } ?: false
        }
    }.getOrDefault(false)

    private const val CONSENT_READ_TIMEOUT_MS = 1_000L
}
