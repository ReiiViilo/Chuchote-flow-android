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
 * vers une cible donnée, vrai si le relais a accepté, faux sans bruit s'il
 * n'est pas consenti) et le relais du moment (`destination`, nul sans relais
 * configuré).
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
 * écriture locale — sauf pour la suppression, qui appelle **avant** d'effacer
 * la ligne : la pierre tombale est ainsi durable avant que la suppression le
 * soit, et une mort du processus entre les deux laisse au pire une pierre
 * tombale pour un mot encore vivant, que le rejeu du démarrage écarte. Ce qui
 * doit survivre à une mort du processus — la pierre tombale, l'invalidation
 * de l'empreinte — est inscrit **sur ce fil-là**, avant la mise en file, et
 * non dans le travail qui attendra son tour derrière un lot de cinq cents.
 *
 * Un travail de rejeu n'envoie que les pierres tombales inscrites **au plus
 * tard** quand il a été demandé : le fil peut être occupé pendant qu'un mot
 * est supprimé, réappris, puis supprimé de nouveau, et le rejeu de la première
 * suppression ne doit pas consommer la pierre tombale de la seconde — l'ajout
 * qui les sépare partirait ensuite, et le mot resterait vivant sur le relais.
 * La republication, elle, lit le dictionnaire au moment où elle part, pas au
 * moment où elle a été demandée : une suppression faite entre les deux est
 * déjà hors du dictionnaire et déjà en file, et son propre travail suit.
 *
 * Le prix du fil unique : relais injoignable, chaque travail attend son délai
 * de connexion avant de céder la place, et ce qui est demandé ensuite — un
 * ajout, la republication — attend derrière. Assumé : rien n'est perdu, tout
 * repart au démarrage suivant, et l'ordre vaut plus que la latence d'un envoi
 * best-effort.
 *
 * L'empreinte enregistrée après une republication signifie « le relais
 * [destination] a accepté exactement ce dictionnaire ». Toute mutation
 * l'efface avant même de tenter son envoi — un ajout manqué hors ligne n'a
 * pas de file, c'est la republication suivante qui le rattrape — et une
 * republication n'enregistre la sienne que si aucune mutation ne s'est
 * glissée pendant ses envois. Ses lots visent tous le relais lu à son départ,
 * même si l'adresse change entre deux : l'empreinte dit vrai de ce relais-là,
 * et le nouveau, qui n'a rien accepté, sera servi au démarrage suivant.
 * Changer de relais change l'empreinte, donc republie.
 */
internal class DictionarySyncCoordinator(
    private val memoire: SyncMemoire,
    private val destination: () -> RemoteRequestTarget?,
    private val envoyer: suspend (
        cible: RemoteRequestTarget,
        path: String,
        payload: JSONObject,
        label: String,
        readTimeoutMs: Int,
    ) -> Boolean,
    scope: CoroutineScope,
    private val horloge: () -> Long = System::currentTimeMillis,
    private val journal: (String) -> Unit = {},
) {
    // Tenu brièvement, jamais pendant un envoi : il garde la file, l'empreinte
    // et le compteur de mutations cohérents entre le fil du magasin et le fil
    // d'envoi.
    private val verrou = Any()

    // Nombre de mutations inscrites depuis le lancement : une republication
    // n'enregistre son empreinte que si rien n'a bougé pendant ses envois, et
    // un rejeu n'envoie que ce qui était inscrit quand il a été demandé.
    private var generation = 0L

    // Sous [verrou] : pour chaque paire en file, la génération à laquelle sa
    // pierre tombale a été inscrite dans ce processus. Une pierre tombale
    // héritée d'un processus précédent n'y figure pas et part toujours.
    private val inscriptions = mutableMapOf<Pair<String, String>, Long>()

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
        demander {
            val cible = destination() ?: return@demander
            envoyer(cible, DICTIONARY_PATH, SyncPayloads.dictionaryEntry(paire.first, paire.second), "Mot #ajout", SyncTimeouts.READ_TIMEOUT_MS)
        }
    }

    /**
     * Un mot supprimé : mis en file d'abord, envoyé ensuite, à son tour — et
     * seulement lui, pas une pierre tombale inscrite après cette demande.
     */
    fun retirer(paire: Pair<String, String>) {
        val borne = synchronized(verrou) {
            ecrireFile(PendingTombstones.ajouter(lireFile(), paire, horloge()))
            invaliderEmpreinte()
            inscriptions[paire] = generation
            generation
        }
        demander { rejouer(borne) { emptySet() } }
    }

    /**
     * Au démarrage du magasin : rejoue les pierres tombales en attente, puis
     * republie le dictionnaire s'il diffère de ce que ce relais a accepté.
     * [dictionnaire] est lu quand le travail part, deux fois : pour savoir
     * quelles pierres tombales sont redevenues vivantes, puis pour ce qui est
     * republié.
     */
    fun synchroniserAuDemarrage(dictionnaire: () -> List<EntreeDictionnaire>) {
        val borne = synchronized(verrou) { generation }
        demander {
            rejouer(borne) { dictionnaire().map { it.entendu.trim() to it.remplacerPar.trim() }.toSet() }
            republierSiChange(dictionnaire)
        }
    }

    /** Attend que tout ce qui a été demandé jusqu'ici soit parti ou ait échoué (tests). */
    internal suspend fun attendreLaFin() {
        val fin = CompletableDeferred<Unit>()
        demander { fin.complete(Unit) }
        fin.await()
    }

    /**
     * Met un travail en file. Le canal n'est jamais fermé et sa capacité est
     * illimitée : un refus ne peut venir que d'une portée déjà annulée, auquel
     * cas le travail ne partira pas — sa trace durable, elle, est déjà
     * inscrite, et le démarrage suivant le rattrape.
     */
    private fun demander(travail: suspend () -> Unit) {
        if (travaux.trySend(travail).isFailure) {
            journal("Travail de synchronisation refusé : fil d'envoi arrêté")
        }
    }

    private suspend fun republierSiChange(dictionnaire: () -> List<EntreeDictionnaire>) {
        // Une seule cible pour tous les lots et pour l'empreinte : ce que
        // l'empreinte affirme d'un relais doit lui être arrivé en entier.
        val cible = destination() ?: return
        // La génération est relevée **avant** de lire le dictionnaire : une
        // mutation glissée entre la lecture et la fin des envois — y compris
        // avant le premier — laisse l'empreinte effacée. Son propre travail
        // suit celui-ci, et la republication suivante portera son résultat.
        val depart = synchronized(verrou) { generation }
        val entrees = dictionnaire()
        val lots = SyncPayloads.dictionaryBatches(entrees)
        if (lots.isEmpty()) return
        val signature = SyncPayloads.dictionarySignature(entrees, cible.baseUrl)
        val dejaAcceptee = synchronized(verrou) { memoire.lire(KEY_DICTIONARY_SIGNATURE) == signature }
        if (dejaAcceptee) return
        lots.forEachIndexed { index, lot ->
            // Empreinte non enregistrée : tout repartira au prochain démarrage.
            if (!envoyer(cible, DICTIONARY_PATH, lot, "lot ${index + 1}/${lots.size} du dictionnaire", SyncTimeouts.BATCH_READ_TIMEOUT_MS)) return
        }
        synchronized(verrou) {
            if (generation == depart) memoire.ecrire(KEY_DICTIONARY_SIGNATURE, signature)
        }
    }

    /**
     * Envoie les pierres tombales en attente inscrites au plus tard à la
     * génération [borne], dans l'ordre; s'arrête au premier échec (le relais
     * est probablement injoignable, inutile d'insister). Une paire redevenue
     * vivante localement ([exclure], lu sous le verrou) ou périmée est retirée
     * de la file sans être envoyée; la politique elle-même est celle de
     * [PendingTombstones].
     *
     * Les envoyées sont ensuite retirées de la file **courante** — pas
     * remplacées par l'instantané du départ, qui effacerait une suppression
     * inscrite pendant les envois.
     */
    private suspend fun rejouer(borne: Long, exclure: () -> Set<Pair<String, String>>) {
        val maintenant = horloge()
        val aRejouer = synchronized(verrou) {
            val enAttente = lireFile()
            // Le dictionnaire vivant est lu ici, sous le verrou : une mutation
            // est soit entièrement avant (déjà hors du vivant et en file), soit
            // entièrement après (son travail suivra celui-ci).
            val gardees = PendingTombstones.aRejouer(enAttente, exclure(), maintenant)
            if (gardees.size != enAttente.size) ecrireFile(gardees)
            gardees.filter { (inscriptions[it.paire] ?: 0L) <= borne }
        }
        if (aRejouer.isEmpty()) return
        val cible = destination() ?: return
        val envoyees = mutableListOf<TombaleEnAttente>()
        for (tombale in aRejouer) {
            val ok = envoyer(
                cible,
                DICTIONARY_PATH,
                SyncPayloads.dictionaryTombstone(tombale.entendu, tombale.remplacerPar),
                "Mot #retrait",
                SyncTimeouts.READ_TIMEOUT_MS,
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
            inscriptions.clear()
            emptyList()
        }

    /** Sous [verrou]. Les inscriptions suivent la file : une paire sortie n'a plus de génération. */
    private fun ecrireFile(file: List<TombaleEnAttente>) {
        memoire.ecrire(KEY_PENDING_TOMBSTONES, PendingTombstones.encode(file))
        val presentes = file.map { it.paire }.toSet()
        inscriptions.keys.retainAll(presentes)
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
    }
}
