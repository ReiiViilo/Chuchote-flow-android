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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * Une suppression est donc la seule chose qui soit **mise en file** : la
 * pierre tombale attend dans `chuchote_sync` tant que le relais ne l'a pas
 * acceptée, et se rejoue au démarrage. Sans cela, une suppression faite hors
 * ligne laisserait l'autre appareil réécrire le mot indéfiniment, puisque la
 * republication de démarrage ne porte que des entrées vivantes. La file n'est
 * alimentée que si la synchronisation a déjà été configurée une fois sur cet
 * appareil — un appareil sans relais ne garde aucune trace de ce qu'il
 * supprime, mais un relais momentanément éteint (rotation de jeton, changement
 * d'adresse) ne fait rien perdre — et [PendingTombstones] la borne en taille
 * et en âge. Un consentement retiré ne la vide pas : rien ne part tant qu'il
 * est absent, et la péremption s'en charge.
 *
 * Mêmes frontières que le relais : rien ne part sans le consentement courant
 * ET sans que le relais soit activé et configuré. Tout est best-effort et
 * hors du chemin critique : un échec est journalisé (sans contenu) puis
 * oublié — sauf la pierre tombale, qui reste en file —, la vérité locale
 * (`chuchote.db`) n'attend jamais le cloud. Les deux points d'entrée sont
 * idempotents côté serveur — l'identifiant local pour les dictées, la paire
 * (entendu, remplacement) pour le dictionnaire — donc un renvoi ne crée
 * jamais de doublon. Le thread appelant ne fait que lancer une coroutine :
 * réglages, consentement, sérialisation et empreinte y sont tous calculés.
 */
class SyncPusher(context: Context) {

    private val applicationContext = context.applicationContext
    private val settings by lazy { RemoteTranscriptionSettings(applicationContext) }
    private val prefs: SharedPreferences by lazy {
        applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Toute lecture-modification-écriture de la file de pierres tombales passe
    // par ce verrou, tenu brièvement et jamais pendant un appel réseau : un
    // retrait et un démarrage peuvent se chevaucher.
    private val fileTombales = Mutex()

    // Les rejeux, eux, sont sérialisés entre eux : deux rejeux concurrents
    // enverraient les mêmes pierres tombales deux fois (sans dommage, mais
    // sans intérêt).
    private val rejeu = Mutex()

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
            send(
                "/api/sync/dictations",
                SyncPayloads.dictation(localId, createdAtMs, rawText, finalText, durationMs, source),
                "Dictée $localId",
                READ_TIMEOUT_MS,
            )
        }
    }

    /** Publie un mot appris; republier une entrée déjà connue est sans effet. */
    fun pushDictionaryEntry(entendu: String, remplacerPar: String) {
        val mot = entendu.trim()
        if (mot.isEmpty()) return
        scope.launch {
            // Un mot réappris annule la pierre tombale qui l'attendait encore :
            // sinon elle l'effacerait du relais après coup.
            oublierTombale(mot to remplacerPar.trim())
            send("/api/sync/dictionary", SyncPayloads.dictionaryEntry(mot, remplacerPar), "Mot #ajout", READ_TIMEOUT_MS)
        }
    }

    /**
     * Publie la pierre tombale d'un mot supprimé, pour que l'autre appareil
     * cesse de l'appliquer. Mise en file d'abord, envoi ensuite : un échec la
     * laisse en attente du prochain démarrage.
     */
    fun pushDictionaryTombstone(entendu: String, remplacerPar: String) {
        val mot = entendu.trim()
        if (mot.isEmpty()) return
        scope.launch {
            // Sans relais jamais configuré, rien ne partira jamais : ne pas
            // conserver la trace d'une suppression que personne n'attend. Le
            // consentement, lui, se relit à l'envoi — une révocation ne vide
            // pas la file.
            if (!synchronisationDejaConfiguree()) return@launch
            memoriserTombale(mot to remplacerPar.trim())
            rejouerTombales(exclure = emptySet())
        }
    }

    /**
     * Au démarrage du magasin : rejoue les pierres tombales en attente, puis
     * republie tout le dictionnaire — mais seulement s'il a changé depuis la
     * dernière republication réussie, car le processus du service redémarre
     * souvent. Le relais traite chaque entrée par un aller-retour distinct
     * sous un budget de 30 s : le dictionnaire part par lots de 500, chacun
     * avec un délai de lecture qui couvre ce budget, et l'empreinte n'est
     * enregistrée que si tous les lots sont passés.
     */
    fun synchroniserAuDemarrage(entrees: List<EntreeDictionnaire>) {
        scope.launch {
            runCatching {
                val vivantes = entrees.map { it.entendu.trim() to it.remplacerPar.trim() }.toSet()
                rejouerTombales(exclure = vivantes)
                republierSiChange(entrees)
            }.onFailure { error ->
                Log.w(TAG, "Synchronisation du dictionnaire au démarrage abandonnée (${error.javaClass.simpleName})")
            }
        }
    }

    private suspend fun republierSiChange(entrees: List<EntreeDictionnaire>) {
        val lots = SyncPayloads.dictionaryBatches(entrees)
        if (lots.isEmpty()) return
        val signature = SyncPayloads.dictionarySignature(entrees)
        if (prefs.getString(KEY_DICTIONARY_SIGNATURE, null) == signature) return
        lots.forEachIndexed { index, lot ->
            val ok = send("/api/sync/dictionary", lot, "lot ${index + 1}/${lots.size} du dictionnaire", BATCH_READ_TIMEOUT_MS)
            // Empreinte non enregistrée : tout repartira au prochain démarrage.
            if (!ok) return
        }
        prefs.edit(commit = true) { putString(KEY_DICTIONARY_SIGNATURE, signature) }
    }

    /**
     * Envoie les pierres tombales en attente, dans l'ordre; s'arrête au premier
     * échec (le relais est probablement injoignable, inutile d'insister). Une
     * paire redevenue vivante localement entre-temps ([exclure]) ou périmée
     * est retirée de la file sans être envoyée. La politique elle-même —
     * quoi rejouer, quoi garder — est celle de [PendingTombstones].
     *
     * Le verrou de la file n'est tenu que pour la lire et la réécrire, jamais
     * pendant un appel réseau : une suppression faite pendant les envois est
     * écrite sans attendre le délai de connexion. Les envoyées sont ensuite
     * retirées de la file **courante** — pas remplacées par l'instantané du
     * départ, qui effacerait cette suppression concurrente.
     */
    private suspend fun rejouerTombales(exclure: Set<Pair<String, String>>) = rejeu.withLock {
        val maintenant = System.currentTimeMillis()
        val aRejouer = fileTombales.withLock {
            val enAttente = lireFile()
            val gardees = PendingTombstones.aRejouer(enAttente, exclure, maintenant)
            // Les exclues et les périmées sortent tout de suite, sous le verrou.
            if (gardees.size != enAttente.size) ecrireFile(gardees)
            gardees
        }
        val envoyees = mutableListOf<TombaleEnAttente>()
        for (tombale in aRejouer) {
            val ok = send(
                "/api/sync/dictionary",
                SyncPayloads.dictionaryTombstone(tombale.entendu, tombale.remplacerPar),
                "Mot #retrait",
                READ_TIMEOUT_MS,
            )
            if (!ok) break
            envoyees += tombale
        }
        if (envoyees.isNotEmpty()) {
            fileTombales.withLock {
                val courante = lireFile()
                val restantes = PendingTombstones.restantes(courante, envoyees)
                if (restantes.size != courante.size) ecrireFile(restantes)
            }
        }
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

    private suspend fun memoriserTombale(paire: Pair<String, String>) = fileTombales.withLock {
        ecrireFile(PendingTombstones.ajouter(lireFile(), paire, System.currentTimeMillis()))
    }

    private suspend fun oublierTombale(paire: Pair<String, String>) = fileTombales.withLock {
        val enAttente = lireFile()
        val sans = PendingTombstones.retirer(enAttente, paire)
        if (sans.size != enAttente.size) ecrireFile(sans)
    }

    /** À appeler sous [fileTombales]. Une file illisible est signalée une fois, puis abandonnée. */
    private fun lireFile(): List<TombaleEnAttente> =
        PendingTombstones.decode(prefs.getString(KEY_PENDING_TOMBSTONES, null)) ?: run {
            Log.w(TAG, "File de pierres tombales illisible : abandonnée, le desktop peut garder des mots supprimés ici")
            prefs.edit(commit = true) { remove(KEY_PENDING_TOMBSTONES) }
            emptyList()
        }

    /** À appeler sous [fileTombales]. */
    private fun ecrireFile(file: List<TombaleEnAttente>) {
        prefs.edit(commit = true) { putString(KEY_PENDING_TOMBSTONES, PendingTombstones.encode(file)) }
    }

    /**
     * Envoi best-effort commun. Vrai si le relais a accepté. Faux — sans
     * bruit — si la synchronisation n'est pas configurée ou consentie; faux
     * avec un journal expurgé (jamais le message brut, qui peut porter un nom
     * d'hôte) sur toute défaillance.
     */
    private suspend fun send(
        path: String,
        payload: JSONObject,
        label: String,
        readTimeoutMs: Int,
    ): Boolean {
        val target = settings.snapshot().requestTarget ?: return false
        noterRelaisConfigure()
        // Le consentement est relu au dernier moment, dans la coroutine :
        // une révocation entre l'apprentissage et l'envoi est respectée.
        if (!PrivacyConsent.isAccepted(applicationContext)) return false
        return runCatching {
            val url = URL(target.baseUrl.trimEnd('/') + path)
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = CONNECT_TIMEOUT_MS
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
        const val KEY_DICTIONARY_SIGNATURE = "dictionary_signature"
        const val KEY_PENDING_TOMBSTONES = "pending_tombstones"
        const val KEY_SYNC_CONFIGURED = "sync_configured"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000

        /** Le relais s'accorde 30 s par lot (`maxDuration`); on attend un peu au-delà. */
        const val BATCH_READ_TIMEOUT_MS = 35_000
    }
}
