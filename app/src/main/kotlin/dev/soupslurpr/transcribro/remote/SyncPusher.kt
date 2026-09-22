package dev.soupslurpr.transcribro.remote

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import dev.soupslurpr.transcribro.memory.EntreeDictionnaire
import dev.soupslurpr.transcribro.preferences.PrivacyConsent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Pousse vers le cerveau commun Chuchote Flow ce que l'appareil vient
 * d'apprendre : chaque dictée terminée (`/api/sync/dictations`), chaque mot
 * ajouté ou retiré du dictionnaire personnel (`/api/sync/dictionary`). Même
 * adresse et même jeton que la transcription distante — aucune configuration
 * de plus.
 *
 * Le dictionnaire compte au moins autant que les dictées : c'est lui qui
 * permet au desktop de corriger « hop hop » en « OpOp » sans qu'on ait à le
 * lui réapprendre — et de cesser de le faire quand l'entrée est supprimée ici.
 * Tout ce qui le concerne — ordre des envois, file des pierres tombales,
 * empreinte de la dernière republication — est la politique de
 * [DictionarySyncCoordinator], Kotlin pur; cette classe lui prête l'appareil :
 * `chuchote_sync`, les réglages du relais et l'envoi HTTP. La file n'est
 * alimentée que si la synchronisation a déjà été configurée une fois sur cet
 * appareil — un appareil sans relais ne garde aucune trace de ce qu'il
 * supprime, mais un relais momentanément éteint (rotation de jeton, changement
 * d'adresse) ne fait rien perdre. Un consentement retiré ne la vide pas : rien
 * ne part tant qu'il est absent, et la péremption s'en charge.
 *
 * Mêmes frontières que le relais : rien ne part sans le consentement courant
 * ET sans que le relais soit activé et configuré. Tout est best-effort et
 * hors du chemin critique : un échec est journalisé (sans contenu) puis
 * oublié — sauf la pierre tombale, qui reste en file —, la vérité locale
 * (`chuchote.db`) n'attend jamais le cloud. Les deux points d'entrée sont
 * idempotents côté serveur — `<installation>:<ligne>` pour les dictées
 * (voir `installationId`), la paire (entendu, remplacement) pour le
 * dictionnaire — donc un renvoi ne crée jamais de doublon.
 *
 * Fils : une dictée ne fait que lancer une coroutine, l'appelant peut être
 * n'importe où. Une mutation du dictionnaire inscrit d'abord sa trace dans
 * `chuchote_sync` sur le fil appelant — celui du magasin, déjà hors du fil
 * principal — parce que cette trace doit exister avant que l'envoi attende
 * son tour.
 */
class SyncPusher(context: Context) {

    private val applicationContext = context.applicationContext
    private val settings by lazy { RemoteTranscriptionSettings(applicationContext) }
    private val prefs: SharedPreferences by lazy {
        applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val coordinateur by lazy {
        DictionarySyncCoordinator(
            memoire = PreferencesSyncMemoire(prefs),
            destination = { settings.snapshot().requestTarget },
            envoyer = { cible, path, payload, label, readTimeoutMs -> send(cible, path, payload, label, readTimeoutMs) },
            scope = scope,
            journal = { message -> Log.w(TAG, message) },
        )
    }

    /**
     * L'identifiant de cette installation, tiré au hasard une fois et gardé
     * dans `chuchote_sync` : il préfixe le `device_local_id` des dictées,
     * dont l'identifiant local est un numéro de ligne SQLite — le même sur
     * deux téléphones, ou après une réinstallation — alors que le relais ne
     * connaît que la plateforme et écraserait, en silence, la dictée de
     * l'autre (décision F du plan de synchronisation desktop; constat
     * externe Codex, 19 septembre 2026). Un identifiant tiré, jamais un
     * identifiant matériel : un pseudonyme stable par installation, sans
     * matériel ni identité derrière. Si l'écriture est refusée, il ne vaut
     * que pour ce processus — les dictées de ce lancement restent distinctes
     * entre elles, et le suivant en tire un autre. Lu sur le fil d'envoi,
     * jamais sur le fil principal.
     */
    private val installationId: String by lazy {
        prefs.getString(KEY_INSTALLATION_ID, null)?.takeIf { it.isNotBlank() } ?: run {
            val tire = UUID.randomUUID().toString().replace("-", "")
            if (!prefs.edit().putString(KEY_INSTALLATION_ID, tire).commit()) {
                Log.w(TAG, "Identifiant d'installation non enregistré (écriture des préférences refusée) : valable pour ce processus seulement")
            }
            tire
        }
    }

    fun pushDictation(
        localId: Long,
        createdAtMs: Long,
        rawText: String?,
        finalText: String,
        durationMs: Long?,
        source: String?,
    ) {
        if (finalText.isBlank()) return
        scope.launch {
            val target = settings.snapshot().requestTarget ?: return@launch
            send(
                target,
                "/api/sync/dictations",
                SyncPayloads.dictation(installationId, localId, createdAtMs, rawText, finalText, durationMs, source),
                "Dictée $localId",
                SyncTimeouts.READ_TIMEOUT_MS,
            )
        }
    }

    /** Publie un mot appris; republier une entrée déjà connue est sans effet. */
    fun pushDictionaryEntry(entendu: String, remplacerPar: String) {
        val mot = entendu.trim()
        if (mot.isEmpty()) return
        coordinateur.ajouter(mot to remplacerPar.trim())
    }

    /**
     * Publie la pierre tombale d'un mot supprimé, pour que l'autre appareil
     * cesse de l'appliquer. Mise en file d'abord, envoi ensuite : un échec la
     * laisse en attente du prochain démarrage. Faux seulement si la pierre
     * tombale n'a pas pu être inscrite dans `chuchote_sync` — elle part alors
     * aussitôt, une seule fois, et l'appelant décide de sa suppression locale.
     */
    fun pushDictionaryTombstone(entendu: String, remplacerPar: String): Boolean {
        val mot = entendu.trim()
        if (mot.isEmpty()) return true
        // Sans relais jamais configuré, rien ne partira jamais : ne pas
        // conserver la trace d'une suppression que personne n'attend. Le
        // consentement, lui, se relit à l'envoi — une révocation ne vide pas
        // la file.
        if (!synchronisationDejaConfiguree()) return true
        return coordinateur.retirer(mot to remplacerPar.trim())
    }

    /**
     * Au démarrage du magasin : rejoue les pierres tombales en attente, puis
     * republie tout le dictionnaire — mais seulement s'il a changé depuis la
     * dernière republication acceptée par ce relais, car le processus du
     * service redémarre souvent. [dictionnaire] est relu au moment où le
     * travail part. Le relais traite chaque entrée par un aller-retour distinct
     * sous un budget de 30 s : le dictionnaire part par lots de 500, chacun
     * avec un délai de lecture qui couvre ce budget, et l'empreinte n'est
     * enregistrée que si tous les lots sont passés.
     */
    fun synchroniserAuDemarrage(dictionnaire: () -> List<EntreeDictionnaire>) {
        coordinateur.synchroniserAuDemarrage(dictionnaire)
    }

    /**
     * Vrai si un relais est configuré maintenant, ou l'a été un jour sur cet
     * appareil (drapeau collant) : modifier l'adresse ou faire tourner le jeton
     * éteint le relais le temps de la saisie, et une suppression faite là ne
     * doit pas être perdue. Un appareil qui n'a jamais eu de relais ne garde
     * rien; la file reste bornée en taille et en âge dans tous les cas.
     */
    private fun synchronisationDejaConfiguree(): Boolean {
        if (settings.snapshot().requestTarget != null) {
            noterRelaisConfigure()
            return true
        }
        return prefs.getBoolean(KEY_SYNC_CONFIGURED, false)
    }

    private fun noterRelaisConfigure() {
        if (!prefs.getBoolean(KEY_SYNC_CONFIGURED, false)) {
            prefs.edit(commit = true) { putBoolean(KEY_SYNC_CONFIGURED, true) }
        }
    }

    /**
     * Envoi best-effort commun, vers la cible que l'appelant a lue — une
     * republication en plusieurs lots les adresse tous au même relais, même
     * si l'adresse change entre deux. Vrai si le relais a accepté. Faux — sans
     * bruit — si la synchronisation n'est pas consentie; faux avec un journal
     * expurgé (jamais le message brut, qui peut porter un nom d'hôte) sur
     * toute défaillance.
     *
     * Le consentement est relu au dernier moment, dans la coroutine, puis
     * observé pendant tout l'envoi par le même garde que la transcription
     * distante : retiré pendant l'envoi, la connexion est fermée depuis le
     * fil qui révoque, sans attendre le délai de lecture. Ce qui n'est pas
     * parti reste dû — faux, comme un relais injoignable : une pierre tombale
     * reste en file, un lot n'inscrit pas d'empreinte —; les octets déjà
     * transmis sont tenus pour irrévocables.
     */
    private suspend fun send(
        target: RemoteRequestTarget,
        path: String,
        payload: JSONObject,
        label: String,
        readTimeoutMs: Int,
    ): Boolean {
        noterRelaisConfigure()
        if (!PrivacyConsent.isAccepted(applicationContext)) return false
        return try {
            RemoteConsentGuard.run(
                consent = PrivacyConsent.acceptanceFlow(applicationContext),
                upload = { post(target, path, payload, label, readTimeoutMs) },
            )
        } catch (error: RemoteConsentRevokedException) {
            // Une annulation de la portée elle-même reste une annulation : le
            // fil d'envoi ne doit pas la prendre pour un refus du relais.
            currentCoroutineContext().ensureActive()
            // La même exception sert quand la surveillance du consentement
            // est interrompue ou indisponible : fermer est le bon réflexe
            // dans les trois cas, le message ne prétend donc pas savoir lequel.
            Log.w(TAG, "Sync interrompue pour $label : garde du consentement fermée (consentement retiré, ou sa surveillance indisponible)")
            false
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Sync impossible pour $label (${RemoteRelayDiagnostic.transportFailure(error)})")
            false
        }
    }

    /**
     * L'envoi HTTP lui-même. `HttpURLConnection` est bloquant et n'observe pas
     * `Job.cancel()` : le handler d'annulation déconnecte la socket depuis le
     * fil qui annule, ce qui débloque `outputStream` et `responseCode` sans
     * attendre le délai. Vrai si le relais a accepté, faux sur un code hors
     * 2xx; une panne de transport lève.
     */
    private suspend fun post(
        target: RemoteRequestTarget,
        path: String,
        payload: JSONObject,
        label: String,
        readTimeoutMs: Int,
    ): Boolean = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val connectionSlot = CancellableConnectionSlot<HttpURLConnection> {
                it.disconnect()
            }
            continuation.invokeOnCancellation {
                connectionSlot.cancel()
            }

            try {
                val url = URL(target.baseUrl.trimEnd('/') + path)
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = SyncTimeouts.CONNECT_TIMEOUT_MS
                    readTimeout = readTimeoutMs
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer ${target.token}")
                }
                if (!connectionSlot.attach(connection)) return@suspendCancellableCoroutine

                // Dernière relecture synchrone avant que le premier octet — un
                // mot du dictionnaire personnel — quitte l'appareil.
                val mayOpenRequestBody = RemoteUploadGate.canOpenRequestBody(
                    consentAccepted = PrivacyConsent.isAcceptedBlocking(applicationContext),
                    coroutineActive = continuation.isActive,
                )
                if (!mayOpenRequestBody) {
                    if (continuation.isActive) {
                        continuation.cancel(
                            RemoteConsentRevokedException("Consentement retiré avant l'envoi de synchronisation"),
                        )
                    }
                    return@suspendCancellableCoroutine
                }

                connection.outputStream.use {
                    it.write(payload.toString().toByteArray(Charsets.UTF_8))
                }
                val code = connection.responseCode
                val accepted = code in 200..299
                if (accepted) {
                    Log.d(TAG, "$label synchronisé")
                } else {
                    Log.w(TAG, "Sync refusée pour $label: HTTP $code")
                }
                if (continuation.isActive) continuation.resume(accepted)
            } catch (error: Throwable) {
                // Si disconnect() a réveillé l'I/O, la continuation porte déjà
                // la CancellationException et l'IOException ne doit pas l'écraser.
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally {
                connectionSlot.close()
            }
        }
    }

    private companion object {
        const val TAG = "SyncPusher"
        const val PREFS = "chuchote_sync"
        const val KEY_SYNC_CONFIGURED = "sync_configured"
        const val KEY_INSTALLATION_ID = "installation_id"
    }
}
