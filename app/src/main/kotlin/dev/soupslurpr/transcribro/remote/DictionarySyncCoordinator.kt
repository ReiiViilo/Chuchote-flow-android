package dev.soupslurpr.transcribro.remote

import dev.soupslurpr.transcribro.memory.EntreeDictionnaire
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * La mémoire durable de la synchronisation — `chuchote_sync` sur l'appareil,
 * une table en test. Trois clés, lues et écrites sous le verrou du
 * coordinateur et nulle part ailleurs.
 */
internal interface SyncMemoire {
    fun lire(cle: String): String?
    fun ecrire(cle: String, valeur: String)
    fun effacer(cle: String)
}

/**
 * Ordonne ce que le dictionnaire envoie au relais. Kotlin pur, vérifié sur la
 * JVM : l'appareil lui prête sa mémoire ([SyncMemoire]), son envoi (`envoyer`,
 * vrai si le relais a accepté, faux sans bruit s'il n'est ni configuré ni
 * consenti) et l'adresse du relais du moment (`destination`, nulle sans relais).
 *
 * Le relais applique les écritures dans l'ordre où elles lui arrivent
 * (dernier-écrit-gagne sur la paire). Deux envois concurrents se croisent donc
 * librement : une republication de démarrage encore en vol qui porte un mot
 * qu'on vient de supprimer le ressusciterait après sa pierre tombale — et rien
 * ne le réparerait ensuite, le mot étant absent ici et la pierre tombale
 * partie. D'où un seul fil d'envoi : chaque travail part dans l'ordre où il a
 * été demandé, et le suivant attend qu'il soit fini.
 *
 * L'ordre de demande est celui du fil du magasin, qui appelle après chaque
 * écriture locale. Ce qui doit survivre à une mort du processus — la pierre
 * tombale, l'invalidation de l'empreinte — est inscrit **sur ce fil-là**,
 * avant la mise en file, et non dans le travail qui attendra son tour
 * derrière un lot de cinq cents. La republication, elle, lit le dictionnaire
 * au moment où elle part, pas au moment où elle a été demandée : une
 * suppression faite entre les deux est déjà hors du dictionnaire et déjà en
 * file, et son propre travail suit.
 *
 * L'empreinte enregistrée après une republication signifie « le relais
 * [destination] a accepté exactement ce dictionnaire ». Toute mutation
 * l'efface avant même de tenter son envoi — un ajout manqué hors ligne n'a
 * pas de file, c'est la republication suivante qui le rattrape — et une
 * republication n'enregistre la sienne que si aucune mutation ne s'est
 * glissée pendant ses envois. Changer de relais change l'empreinte, donc
 * republie : le nouveau relais n'a rien accepté.
 */
internal class DictionarySyncCoordinator(
    private val memoire: SyncMemoire,
    private val destination: () -> String?,
    private val envoyer: suspend (path: String, payload: JSONObject, label: String, readTimeoutMs: Int) -> Boolean,
    scope: CoroutineScope,
    private val horloge: () -> Long = System::currentTimeMillis,
    private val journal: (String) -> Unit = {},
) {
    // Tenu brièvement, jamais pendant un envoi : il garde la file, l'empreinte
    // et le compteur de mutations cohérents entre le fil du magasin et le fil
    // d'envoi.
    private val verrou = Any()

    // Nombre de mutations inscrites depuis le lancement : une republication
    // n'enregistre son empreinte que si rien n'a bougé pendant ses envois.
    private var generation = 0L

    private val travaux = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (travail in travaux) {
                try {
                    travail()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    journal("Travail de synchronisation abandonné (${e.javaClass.simpleName})")
                }
            }
        }
    }

    /** Un mot appris : la pierre tombale qui l'attendait encore est oubliée, puis il part à son tour. */
    fun ajouter(paire: Pair<String, String>) {
        synchronized(verrou) {
            val enAttente = lireFile()
            val sans = PendingTombstones.retirer(enAttente, paire)
            if (sans.size != enAttente.size) ecrireFile(sans)
            invaliderEmpreinte()
        }
        travaux.trySend {
            envoyer(DICTIONARY_PATH, SyncPayloads.dictionaryEntry(paire.first, paire.second), "Mot #ajout", READ_TIMEOUT_MS)
        }
    }

    /** Un mot supprimé : mis en file d'abord, envoyé ensuite, à son tour. */
    fun retirer(paire: Pair<String, String>) {
        synchronized(verrou) {
            ecrireFile(PendingTombstones.ajouter(lireFile(), paire, horloge()))
            invaliderEmpreinte()
        }
        travaux.trySend { rejouer { emptySet() } }
    }

    /**
     * Au démarrage du magasin : rejoue les pierres tombales en attente, puis
     * republie le dictionnaire s'il diffère de ce que ce relais a accepté.
     * [dictionnaire] est lu quand le travail part, deux fois : pour savoir
     * quelles pierres tombales sont redevenues vivantes, puis pour ce qui est
     * republié.
     */
    fun synchroniserAuDemarrage(dictionnaire: () -> List<EntreeDictionnaire>) {
        travaux.trySend {
            rejouer { dictionnaire().map { it.entendu.trim() to it.remplacerPar.trim() }.toSet() }
            republierSiChange(dictionnaire())
        }
    }

    /** Attend que tout ce qui a été demandé jusqu'ici soit parti ou ait échoué (tests). */
    internal suspend fun attendreLaFin() {
        val fin = CompletableDeferred<Unit>()
        travaux.trySend { fin.complete(Unit) }
        fin.await()
    }

    private suspend fun republierSiChange(entrees: List<EntreeDictionnaire>) {
        val cible = destination() ?: return
        val lots = SyncPayloads.dictionaryBatches(entrees)
        if (lots.isEmpty()) return
        val signature = SyncPayloads.dictionarySignature(entrees, cible)
        val depart = synchronized(verrou) {
            if (memoire.lire(KEY_DICTIONARY_SIGNATURE) == signature) null else generation
        } ?: return
        lots.forEachIndexed { index, lot ->
            // Empreinte non enregistrée : tout repartira au prochain démarrage.
            if (!envoyer(DICTIONARY_PATH, lot, "lot ${index + 1}/${lots.size} du dictionnaire", BATCH_READ_TIMEOUT_MS)) return
        }
        synchronized(verrou) {
            // Une mutation pendant les envois a son propre travail derrière
            // celui-ci; l'empreinte reste effacée jusqu'à la republication
            // suivante, qui portera son résultat.
            if (generation == depart) memoire.ecrire(KEY_DICTIONARY_SIGNATURE, signature)
        }
    }

    /**
     * Envoie les pierres tombales en attente, dans l'ordre; s'arrête au premier
     * échec (le relais est probablement injoignable, inutile d'insister). Une
     * paire redevenue vivante localement ([exclure], lu sous le verrou) ou
     * périmée est retirée de la file sans être envoyée; la politique elle-même
     * est celle de [PendingTombstones].
     *
     * Les envoyées sont ensuite retirées de la file **courante** — pas
     * remplacées par l'instantané du départ, qui effacerait une suppression
     * inscrite pendant les envois.
     */
    private suspend fun rejouer(exclure: () -> Set<Pair<String, String>>) {
        val maintenant = horloge()
        val aRejouer = synchronized(verrou) {
            val enAttente = lireFile()
            // Le dictionnaire vivant est lu ici, sous le verrou : une mutation
            // est soit entièrement avant (déjà hors du vivant et en file), soit
            // entièrement après (son travail suivra celui-ci).
            val gardees = PendingTombstones.aRejouer(enAttente, exclure(), maintenant)
            if (gardees.size != enAttente.size) ecrireFile(gardees)
            gardees
        }
        val envoyees = mutableListOf<TombaleEnAttente>()
        for (tombale in aRejouer) {
            val ok = envoyer(
                DICTIONARY_PATH,
                SyncPayloads.dictionaryTombstone(tombale.entendu, tombale.remplacerPar),
                "Mot #retrait",
                READ_TIMEOUT_MS,
            )
            if (!ok) break
            envoyees += tombale
        }
        if (envoyees.isNotEmpty()) {
            synchronized(verrou) {
                val courante = lireFile()
                val restantes = PendingTombstones.restantes(courante, envoyees)
                if (restantes.size != courante.size) ecrireFile(restantes)
            }
        }
    }

    /** Sous [verrou]. Une file illisible est signalée une fois, puis abandonnée. */
    private fun lireFile(): List<TombaleEnAttente> =
        PendingTombstones.decode(memoire.lire(KEY_PENDING_TOMBSTONES)) ?: run {
            journal("File de pierres tombales illisible : abandonnée, le desktop peut garder des mots supprimés ici")
            memoire.effacer(KEY_PENDING_TOMBSTONES)
            emptyList()
        }

    /** Sous [verrou]. */
    private fun ecrireFile(file: List<TombaleEnAttente>) {
        memoire.ecrire(KEY_PENDING_TOMBSTONES, PendingTombstones.encode(file))
    }

    /** Sous [verrou]. Le dictionnaire vient de changer : ce que le relais a accepté ne le décrit plus. */
    private fun invaliderEmpreinte() {
        generation++
        if (memoire.lire(KEY_DICTIONARY_SIGNATURE) != null) memoire.effacer(KEY_DICTIONARY_SIGNATURE)
    }

    internal companion object {
        const val DICTIONARY_PATH = "/api/sync/dictionary"
        const val KEY_DICTIONARY_SIGNATURE = "dictionary_signature"
        const val KEY_PENDING_TOMBSTONES = "pending_tombstones"
        const val READ_TIMEOUT_MS = 15_000

        /** Le relais s'accorde 30 s par lot (`maxDuration`); on attend un peu au-delà. */
        const val BATCH_READ_TIMEOUT_MS = 35_000
    }
}
