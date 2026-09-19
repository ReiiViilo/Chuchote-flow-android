package dev.soupslurpr.transcribro.remote

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import dev.soupslurpr.transcribro.memory.EntreeDictionnaire
import dev.soupslurpr.transcribro.preferences.PrivacyConsent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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
 * idempotents côté serveur — l'identifiant local pour les dictées, la paire
 * (entendu, remplacement) pour le dictionnaire — donc un renvoi ne crée
 * jamais de doublon.
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
            memoire = object : SyncMemoire {
                override fun lire(cle: String): String? = prefs.getString(cle, null)
                override fun ecrire(cle: String, valeur: String) = prefs.edit(commit = true) { putString(cle, valeur) }
                override fun effacer(cle: String) = prefs.edit(commit = true) { remove(cle) }
            },
            destination = { settings.snapshot().requestTarget },
            envoyer = { cible, path, payload, label, readTimeoutMs -> send(cible, path, payload, label, readTimeoutMs) },
            scope = scope,
            journal = { message -> Log.w(TAG, message) },
        )
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
                SyncPayloads.dictation(localId, createdAtMs, rawText, finalText, durationMs, source),
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
     * laisse en attente du prochain démarrage.
     */
    fun pushDictionaryTombstone(entendu: String, remplacerPar: String) {
        val mot = entendu.trim()
        if (mot.isEmpty()) return
        // Sans relais jamais configuré, rien ne partira jamais : ne pas
        // conserver la trace d'une suppression que personne n'attend. Le
        // consentement, lui, se relit à l'envoi — une révocation ne vide pas
        // la file.
        if (!synchronisationDejaConfiguree()) return
        coordinateur.retirer(mot to remplacerPar.trim())
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
     */
    private suspend fun send(
        target: RemoteRequestTarget,
        path: String,
        payload: JSONObject,
        label: String,
        readTimeoutMs: Int,
    ): Boolean {
        noterRelaisConfigure()
        // Le consentement est relu au dernier moment, dans la coroutine :
        // une révocation entre l'apprentissage et l'envoi est respectée.
        if (!PrivacyConsent.isAccepted(applicationContext)) return false
        return runCatching {
            val url = URL(target.baseUrl.trimEnd('/') + path)
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = SyncTimeouts.CONNECT_TIMEOUT_MS
                connection.readTimeout = readTimeoutMs
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Authorization", "Bearer ${target.token}")
                connection.outputStream.use {
                    it.write(payload.toString().toByteArray(Charsets.UTF_8))
                }
                val code = connection.responseCode
                if (code in 200..299) {
                    Log.d(TAG, "$label synchronisé")
                    true
                } else {
                    Log.w(TAG, "Sync refusée pour $label: HTTP $code")
                    false
                }
            } finally {
                connection.disconnect()
            }
        }.onFailure { error ->
            Log.w(TAG, "Sync impossible pour $label (${RemoteRelayDiagnostic.transportFailure(error)})")
        }.getOrDefault(false)
    }

    private companion object {
        const val TAG = "SyncPusher"
        const val PREFS = "chuchote_sync"
        const val KEY_SYNC_CONFIGURED = "sync_configured"
    }
}
