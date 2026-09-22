package dev.soupslurpr.transcribro.remote

import dev.soupslurpr.transcribro.memory.EntreeDictionnaire
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * La mémoire durable de la synchronisation — `chuchote_sync` sur l'appareil
 * ([PreferencesSyncMemoire]), une table en test. Trois clés, lues et écrites
 * sous le verrou du coordinateur et nulle part ailleurs. Contrat d'une
 * écriture ou d'un effacement : vrai = fait, sur disque comme pour la lecture
 * suivante; faux = rien n'a changé, ni sur disque ni pour la lecture
 * suivante. Le coordinateur en dépend : ce qu'il tient pour « la file » est
 * ce que `lire` rend.
 */
internal interface SyncMemoire {
    fun lire(cle: String): String?

    /** Vrai si la valeur est écrite; faux si rien n'a changé. */
    fun ecrire(cle: String, valeur: String): Boolean

    /** Vrai si la clé est effacée; faux si rien n'a changé. */
    fun effacer(cle: String): Boolean
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
 * glissée pendant ses envois **et** qu'aucune pierre tombale n'est en file :
 * une suppression en suspens (ligne pas encore effacée, envoi pas encore
 * passé) n'est pas décrite par ce qui vient d'être publié, et une empreinte
 * posée quand même laisserait, après une mort du processus avant le `DELETE`,
 * le mot vivant ici et mort au relais sans rien pour les réconcilier — sans
 * empreinte, le démarrage suivant republie. Ses lots visent tous le relais lu à son départ,
 * et **s'arrêtent** si ce relais n'est plus celui configuré entre deux
 * requêtes — coupé, jeton tourné, adresse changée : rien de plus ne part vers
 * l'ancien, l'empreinte n'est pas inscrite, et le nouveau, qui n'a rien
 * accepté, sera servi au démarrage suivant. Même arrêt pour une série de
 * pierres tombales, qui restent en file. Changer de relais change
 * l'empreinte, donc republie.
 *
 * Une pierre tombale qui n'a pas pu être inscrite — l'écriture des
 * préférences a échoué — est envoyée aussitôt, une seule fois, depuis la
 * mémoire : l'appelant est prévenu, la suppression locale a lieu quand même
 * (la vérité locale ne dépend pas du relais), et si cet envoi échoue le
 * relais peut garder le mot.
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
    // Suit la file **durable** : une écriture refusée ne la modifie pas — sauf
    // dans `retirer`, où une pierre tombale non inscrite n'a pas de génération.
    private val inscriptions = mutableMapOf<Pair<String, String>, Long>()

    // Sous [verrou] : les paires réapprises dont la pierre tombale n'a pas pu
    // être retirée de la file (écriture refusée). Tout rejeu les tient pour
    // vivantes — elles ne partent pas —, jusqu'à ce qu'une écriture de la
    // file réussisse sans elles.
    private val reapprises = mutableSetOf<Pair<String, String>>()

    private val travaux = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    // Le fil d'envoi. Sa fin réveille une attente acceptée avant sa mort.
    private val fil: Job = scope.launch {
        try {
            for (travail in travaux) {
                try {
                    travail()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Une `Error` (mémoire épuisée sur un lot de 500, par
                    // exemple) n'est pas rattrapée ici : le fil est un enfant
                    // de `scope.launch` sous `SupervisorJob`, elle remonte au
                    // gestionnaire d'exceptions non rattrapées et le processus
                    // tombe — assumé, plutôt que de continuer sur une JVM en
                    // détresse.
                    journal("Travail de synchronisation abandonné (${e.javaClass.simpleName})")
                }
            }
        } finally {
            // La portée est annulée : fermer le canal, pour qu'une demande
            // ultérieure soit refusée et journalisée plutôt qu'acceptée
            // dans le vide, et compter ce qui ne partira plus.
            travaux.close()
            var abandonnes = 0
            while (travaux.tryReceive().isSuccess) abandonnes++
            if (abandonnes > 0) journal("Fil d'envoi arrêté : $abandonnes travail(aux) en file ne partiront pas")
        }
    }

    /** Un mot appris : la pierre tombale qui l'attendait encore est oubliée, puis il part à son tour. */
    fun ajouter(paire: Pair<String, String>) {
        synchronized(verrou) {
            val enAttente = lireFile()
            val sans = PendingTombstones.retirer(enAttente, paire)
            if (sans.size != enAttente.size && !ecrireFile(sans)) {
                reapprises += paire
                journal("Pierre tombale d'un mot réappris non retirée de la file (écriture refusée) : tenue pour vivante")
            }
            invaliderEmpreinte()
            demander {
                val cible = destination() ?: return@demander
                envoyer(cible, DICTIONARY_PATH, SyncPayloads.dictionaryEntry(paire.first, paire.second), "Mot #ajout", SyncTimeouts.READ_TIMEOUT_MS)
            }
        }
    }

    /**
     * Un mot supprimé : mis en file d'abord, envoyé ensuite, à son tour — et
     * seulement lui, pas une pierre tombale inscrite après cette demande.
     * Vrai si la pierre tombale est durable; faux si elle n'a pas pu être
     * inscrite, auquel cas elle part aussitôt, une seule fois.
     */
    fun retirer(paire: Pair<String, String>): Boolean {
        val durable = synchronized(verrou) {
            invaliderEmpreinte()
            reapprises -= paire // supprimé de nouveau : sa pierre tombale compte
            if (!ecrireFile(PendingTombstones.ajouter(lireFile(), paire, horloge()))) {
                // Pas en file, donc pas de génération : elle part aussitôt, une fois.
                inscriptions.remove(paire)
                false
            } else {
                inscriptions[paire] = generation
                // Borne et mise en file sous le même verrou que l'inscription :
                // un rejeu demandé pendant cette section est mis en file
                // derrière celui-ci, avec une borne qui couvre la pierre tombale.
                val borne = generation
                demander { rejouer(borne) { emptySet() } }
                true
            }
        }
        if (!durable) {
            // Mise en file hors du verrou, et c'est sans conséquence : aucune
            // pierre tombale nouvelle n'entre en file — seule la file durable
            // est ordonnée.
            journal("Pierre tombale non inscrite (écriture des préférences refusée) : envoi immédiat seulement")
            demander {
                val cible = destination() ?: return@demander
                envoyer(cible, DICTIONARY_PATH, SyncPayloads.dictionaryTombstone(paire.first, paire.second), "Mot #retrait", SyncTimeouts.READ_TIMEOUT_MS)
            }
        }
        return durable
    }

    /**
     * Au démarrage du magasin : rejoue les pierres tombales en attente, puis
     * republie le dictionnaire s'il diffère de ce que ce relais a accepté.
     * [dictionnaire] est lu quand le travail part, deux fois : pour savoir
     * quelles pierres tombales sont redevenues vivantes, puis pour ce qui est
     * republié.
     */
    fun synchroniserAuDemarrage(dictionnaire: () -> List<EntreeDictionnaire>) {
        synchronized(verrou) {
            // Sous le verrou avec la borne, comme `retirer` : voir `rejouer`.
            val borne = generation
            demander {
                rejouer(borne) { dictionnaire().map { it.entendu.trim() to it.remplacerPar.trim() }.toSet() }
                republierSiChange(dictionnaire)
            }
        }
    }

    /**
     * Attend que tout ce qui a été demandé jusqu'ici soit parti ou ait échoué
     * — ou n'ait plus de fil pour partir (tests).
     */
    internal suspend fun attendreLaFin() {
        val fin = CompletableDeferred<Unit>()
        if (!demander { fin.complete(Unit) }) return
        // Acceptée, puis la portée meurt avant son tour : le fil jette ce qui
        // reste sans le compléter. Sa fin complète l'attente.
        val fermeture = fil.invokeOnCompletion { fin.complete(Unit) }
        try {
            fin.await()
        } finally {
            fermeture.dispose()
        }
    }

    /**
     * Met un travail en file. La capacité est illimitée et le canal n'est
     * fermé que par la mort de la portée : un refus (faux) signifie que le
     * fil d'envoi est arrêté et que le travail ne partira pas — sa trace
     * durable, elle, est déjà inscrite, et le démarrage suivant le rattrape.
     */
    private fun demander(travail: suspend () -> Unit): Boolean {
        val accepte = travaux.trySend(travail).isSuccess
        if (!accepte) journal("Travail de synchronisation refusé : fil d'envoi arrêté")
        return accepte
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
            if (!envoyerSiToujours(cible, DICTIONARY_PATH, lot, "lot ${index + 1}/${lots.size} du dictionnaire", SyncTimeouts.BATCH_READ_TIMEOUT_MS)) return
        }
        synchronized(verrou) {
            // Ni mutation pendant les envois, ni pierre tombale en file : sinon
            // ce que le relais a accepté ne décrit pas le dictionnaire, et
            // le démarrage suivant doit republier.
            if (generation != depart) return
            if (lireFile().isNotEmpty()) {
                journal("Empreinte non inscrite : une pierre tombale est en file, le dictionnaire repartira au prochain démarrage")
                return
            }
            if (!memoire.ecrire(KEY_DICTIONARY_SIGNATURE, signature)) {
                journal("Empreinte non inscrite : le dictionnaire repartira au prochain démarrage")
            }
        }
    }

    /**
     * Un envoi vers [cible], seulement si c'est encore le relais configuré :
     * une série d'envois — lots d'une republication, pierres tombales d'un
     * rejeu — s'arrête dès que le relais est coupé, son jeton tourné ou son
     * adresse changée. Ce qui était déjà en vol part; rien de plus.
     */
    private suspend fun envoyerSiToujours(
        cible: RemoteRequestTarget,
        path: String,
        payload: JSONObject,
        label: String,
        readTimeoutMs: Int,
    ): Boolean {
        if (destination() != cible) {
            journal("Relais changé ou coupé pendant les envois : série arrêtée")
            return false
        }
        return envoyer(cible, path, payload, label, readTimeoutMs)
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
            // Les pierres tombales inscrites après la demande de ce rejeu ne
            // sont ni envoyées ni élaguées : l'une d'elles peut appartenir à
            // une suppression dont la ligne n'est pas encore effacée — la
            // pierre tombale est inscrite avant —, donc dont la paire est
            // encore vivante; l'élaguer maintenant la perdrait pour toujours.
            // Son propre travail, derrière celui-ci, la prendra.
            val (anciennes, nouvelles) = enAttente.partition { (inscriptions[it.paire] ?: 0L) <= borne }
            // Le dictionnaire vivant est lu ici, sous le verrou. Ce qui tient :
            // une pierre tombale inscrite à une génération plus grande que la
            // borne appartient à une suppression demandée après ce rejeu, et
            // n'est pas jugée; et comme borne et mise en file sont prises sous
            // le même verrou que l'inscription, un rejeu demandé pendant une
            // suppression est mis en file derrière le travail de celle-ci,
            // avec une borne qui la couvre — sa pierre tombale part avant
            // d'être jugée. Ce qui reste entre les deux magasins : un rejeu
            // demandé après la suppression et parti avant son `DELETE` lirait
            // la paire vivante. Impossible sur l'appareil, où l'inscription et
            // le `DELETE` ont lieu sur le même fil sérialisé du magasin, sans
            // suspension entre eux; la boîte d'envoi transactionnelle
            // (tranche 1) rendra l'ordre atomique par construction.
            val gardees = PendingTombstones.aRejouer(anciennes, exclure() + reapprises, maintenant)
            if (gardees.size != anciennes.size && !ecrireFile(enAttente.filter { it in gardees || it in nouvelles })) {
                journal("File de pierres tombales non réécrite (écriture refusée) : élagage repris au prochain rejeu")
            }
            gardees
        }
        if (aRejouer.isEmpty()) return
        val cible = destination() ?: return
        val envoyees = mutableListOf<TombaleEnAttente>()
        for (tombale in aRejouer) {
            val ok = envoyerSiToujours(
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
                if (restantes.size != courante.size && !ecrireFile(restantes)) {
                    journal("File de pierres tombales non réécrite (écriture refusée) : des pierres tombales déjà acceptées repartiront")
                }
            }
        }
    }

    /** Sous [verrou]. Une file illisible est signalée une fois, puis abandonnée. */
    private fun lireFile(): List<TombaleEnAttente> =
        PendingTombstones.decode(memoire.lire(KEY_PENDING_TOMBSTONES)) ?: run {
            journal("File de pierres tombales illisible : abandonnée, le desktop peut garder des mots supprimés ici")
            memoire.effacer(KEY_PENDING_TOMBSTONES)
            inscriptions.clear()
            reapprises.clear()
            emptyList()
        }

    /**
     * Sous [verrou]. Vrai si la file est durable. Les inscriptions et les
     * paires réapprises suivent la file **durable** : une paire sortie n'a
     * plus de génération ni rien à compenser; une écriture refusée ne change
     * rien à ce qu'elles décrivent — à une exception près, dans `retirer` :
     * une pierre tombale qui n'a pas pu entrer en file n'a pas de génération,
     * elle part aussitôt hors de tout rejeu.
     */
    private fun ecrireFile(file: List<TombaleEnAttente>): Boolean {
        val durable = memoire.ecrire(KEY_PENDING_TOMBSTONES, PendingTombstones.encode(file))
        if (durable) {
            val presentes = file.map { it.paire }.toSet()
            inscriptions.keys.retainAll(presentes)
            reapprises.retainAll(presentes)
        }
        return durable
    }

    /** Sous [verrou]. Le dictionnaire vient de changer : ce que le relais a accepté ne le décrit plus. */
    private fun invaliderEmpreinte() {
        generation++
        if (memoire.lire(KEY_DICTIONARY_SIGNATURE) != null && !memoire.effacer(KEY_DICTIONARY_SIGNATURE)) {
            journal("Empreinte non effacée : une republication peut être sautée au prochain démarrage")
        }
    }

    internal companion object {
        const val DICTIONARY_PATH = "/api/sync/dictionary"
        const val KEY_DICTIONARY_SIGNATURE = "dictionary_signature"
        const val KEY_PENDING_TOMBSTONES = "pending_tombstones"
    }
}
