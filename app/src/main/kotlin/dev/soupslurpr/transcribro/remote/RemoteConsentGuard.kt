package dev.soupslurpr.transcribro.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Annulation spécifique à une requête distante dont le consentement vient de
 * disparaître. C'est volontairement une [CancellationException] : les appelants
 * ne doivent jamais l'interpréter comme une panne du relais autorisant un repli.
 */
internal class RemoteConsentRevokedException(
    message: String = "Consentement retiré pendant la transcription distante",
) : CancellationException(message)

/**
 * Garde un upload enfant sous l'observation structurée du consentement.
 *
 * L'upload reste paresseux jusqu'à la première valeur de consentement. Une
 * révocation ou la perte inattendue de l'observateur annule l'enfant; son
 * handler d'annulation peut ainsi déconnecter immédiatement la ressource
 * réseau bloquante. Le `finally` attend toujours la fin de l'observateur afin
 * de ne laisser aucun collecteur DataStore survivre à la requête.
 */
internal object RemoteConsentGuard {
    suspend fun <T> run(
        consent: Flow<Boolean>,
        upload: suspend () -> T,
    ): T = coroutineScope {
        val firstObservation = CompletableDeferred<Boolean>()
        val uploadTask = async(start = CoroutineStart.LAZY) {
            upload()
        }

        fun revoke(message: String) {
            // Annuler le LAZY avant de réveiller l'attente initiale. Dans
            // l'ordre inverse, await() pourrait démarrer l'upload sur un autre
            // thread pendant la minuscule fenêtre précédant cancel().
            uploadTask.cancel(RemoteConsentRevokedException(message))
            firstObservation.complete(false)
        }

        val observerTask = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                consent.collect { accepted ->
                    firstObservation.complete(accepted)
                    if (!accepted) {
                        revoke("Consentement retiré pendant la transcription distante")
                    }
                }

                // Un Flow de préférences doit rester actif. Sa terminaison
                // normale ferait perdre la protection d'une requête en cours.
                revoke("Surveillance du consentement interrompue")
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // PrivacyConsent est déjà fail-closed, mais ce second rempart
                // évite qu'une future source de Flow laisse l'upload sans garde.
                revoke("Surveillance du consentement indisponible")
            } finally {
                if (currentCoroutineContext().isActive && !uploadTask.isCompleted) {
                    revoke("Surveillance du consentement interrompue")
                }
            }
        }

        try {
            if (!firstObservation.await()) {
                // await() restitue la RemoteConsentRevokedException exacte et
                // empêche toute conversion en résultat null/repli local.
                uploadTask.await()
            }

            uploadTask.start()
            uploadTask.await()
        } finally {
            observerTask.cancelAndJoin()
            if (!uploadTask.isCompleted) uploadTask.cancelAndJoin()
        }
    }
}
