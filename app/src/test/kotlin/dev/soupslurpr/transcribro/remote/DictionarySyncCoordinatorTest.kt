package dev.soupslurpr.transcribro.remote

import dev.soupslurpr.transcribro.memory.EntreeDictionnaire
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Les entrelacements que le relais ne pardonne pas (dernier-écrit-gagne) :
 * chaque scénario est celui d'un constat de la revue externe du 19 septembre
 * 2026, rejoué avec un relais factice qu'on peut bloquer ou mettre en panne.
 */
class DictionarySyncCoordinatorTest {

    private class Memoire : SyncMemoire {
        private val table = mutableMapOf<String, String>()

        /** Le disque refuse toute écriture (préférences non durables). */
        @Volatile
        var ecritureRefusee = false

        @Synchronized
        override fun lire(cle: String): String? = table[cle]

        @Synchronized
        override fun ecrire(cle: String, valeur: String): Boolean {
            if (ecritureRefusee) return false
            table[cle] = valeur
            return true
        }

        @Synchronized
        override fun effacer(cle: String): Boolean {
            if (ecritureRefusee) return false
            table.remove(cle)
            return true
        }

        val empreinte: String? get() = lire(DictionarySyncCoordinator.KEY_DICTIONARY_SIGNATURE)
        val file: List<TombaleEnAttente>
            get() = PendingTombstones.decode(lire(DictionarySyncCoordinator.KEY_PENDING_TOMBSTONES)).orEmpty()
    }

    /**
     * Ce que le relais a reçu, dans l'ordre, sous la forme
     * `étiquette[entendu→remplacement:état …]`, et l'adresse visée par chaque envoi accepté.
     */
    private class Relais {
        val recus = CopyOnWriteArrayList<String>()
        val cibles = CopyOnWriteArrayList<String>()

        @Volatile
        var enPanne = false

        /** Le prochain envoi lève au lieu de répondre (un bogue de transport, pas un refus). */
        @Volatile
        var leveUneFois = false

        @Volatile
        private var porte: Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>? = null

        /** Le prochain envoi signalera qu'il est en vol (première valeur) puis attendra la seconde. */
        fun bloquerLeProchainEnvoi(): Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>> =
            (CompletableDeferred<Unit>() to CompletableDeferred<Unit>()).also { porte = it }

        suspend fun envoyer(cible: RemoteRequestTarget, path: String, payload: JSONObject, label: String, readTimeoutMs: Int): Boolean {
            assertEquals("/api/sync/dictionary", path)
            porte?.let { (enVol, liberer) ->
                porte = null
                enVol.complete(Unit)
                liberer.await()
            }
            if (leveUneFois) {
                leveUneFois = false
                throw IllegalStateException("transport cassé")
            }
            if (enPanne) return false
            cibles += cible.baseUrl
            val entries = payload.getJSONArray("entries")
            recus += (0 until entries.length()).joinToString(" ", prefix = "$label[", postfix = "]") { index ->
                val entry = entries.getJSONObject(index)
                val etat = if (entry.getBoolean("deleted")) "retrait" else "vivant"
                "${entry.getString("heard")}→${entry.getString("replace_with")}:$etat"
            }
            return true
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun arreter() {
        scope.cancel()
    }

    private fun entree(id: Long, entendu: String, remplacerPar: String) = EntreeDictionnaire(id, entendu, remplacerPar)

    private fun coordinateur(memoire: Memoire, relais: Relais, destination: () -> String? = { "https://relais.test" }) =
        DictionarySyncCoordinator(
            memoire = memoire,
            destination = { destination()?.let { RemoteRequestTarget(baseUrl = it, token = "jeton") } },
            envoyer = { cible, path, payload, label, timeout -> relais.envoyer(cible, path, payload, label, timeout) },
            scope = scope,
        )

    private suspend fun DictionarySyncCoordinator.attendre() = withTimeout(10_000) { attendreLaFin() }

    @Test
    fun `une suppression pendant la republication de demarrage part apres le lot et reste la derniere`() = runBlocking {
        val memoire = Memoire()
        val relais = Relais()
        val dictionnaire = AtomicReference(listOf(entree(1, "alpha", "Alpha"), entree(2, "beta", "Beta")))
        val coordinateur = coordinateur(memoire, relais)
        val (enVol, liberer) = relais.bloquerLeProchainEnvoi()

        coordinateur.synchroniserAuDemarrage { dictionnaire.get() }
        withTimeout(10_000) { enVol.await() } // le lot, avec beta vivant, est en vol
        // L'ordre du magasin : la pierre tombale d'abord, la ligne ensuite.
        coordinateur.retirer("beta" to "Beta")
        dictionnaire.set(dictionnaire.get().filter { it.entendu != "beta" })
        liberer.complete(Unit)
        coordinateur.attendre()

        assertEquals(
            listOf(
                "lot 1/1 du dictionnaire[alpha→Alpha:vivant beta→Beta:vivant]",
                "Mot #retrait[beta→Beta:retrait]",
            ),
            relais.recus,
        )
        assertTrue(memoire.file.isEmpty())
        // Une mutation pendant les envois : l'empreinte de ce lot ne décrit
        // plus le dictionnaire, elle reste effacée.
        assertNull(memoire.empreinte)
    }

    @Test
    fun `la republication lit le dictionnaire quand elle part, pas quand elle est demandee`() = runBlocking {
        val memoire = Memoire()
        val relais = Relais()
        val dictionnaire = AtomicReference(listOf(entree(1, "vrai", "Vrai"), entree(2, "wapiti", "Wapiti")))
        val coordinateur = coordinateur(memoire, relais)
        val (enVol, liberer) = relais.bloquerLeProchainEnvoi()

        coordinateur.ajouter("xylo" to "Xylo") // occupe le fil d'envoi
        withTimeout(10_000) { enVol.await() }
        coordinateur.synchroniserAuDemarrage { dictionnaire.get() } // demandé avec wapiti vivant
        // Supprimé avant que le démarrage parte, dans l'ordre du magasin :
        // la pierre tombale d'abord, la ligne ensuite.
        coordinateur.retirer("wapiti" to "Wapiti")
        dictionnaire.set(dictionnaire.get().filter { it.entendu != "wapiti" })
        liberer.complete(Unit)
        coordinateur.attendre()

        // Le lot ne porte plus wapiti. Sa pierre tombale, inscrite après la
        // demande de démarrage, n'est pas prise par le rejeu de celui-ci :
        // elle part par son propre travail, derrière le lot — qui ne peut
        // donc pas la dépasser.
        assertEquals(
            listOf(
                "Mot #ajout[xylo→Xylo:vivant]",
                "lot 1/1 du dictionnaire[vrai→Vrai:vivant]",
                "Mot #retrait[wapiti→Wapiti:retrait]",
            ),
            relais.recus,
        )
        assertTrue(memoire.file.isEmpty())
        // Pas d'empreinte : la pierre tombale de wapiti était encore en file
        // quand le lot s'est achevé. Le lot était pourtant juste; la règle ne
        // le sait pas et préfère une republication de plus au démarrage
        // suivant (voir « un rejeu de demarrage n elague pas… »).
        assertNull(memoire.empreinte)
    }

    @Test
    fun `un ajout manque hors ligne est rattrape par la republication suivante`() = runBlocking {
        val memoire = Memoire()
        val relais = Relais()
        val dictionnaire = AtomicReference(listOf(entree(1, "echo", "Écho")))
        val coordinateur = coordinateur(memoire, relais)

        coordinateur.synchroniserAuDemarrage { dictionnaire.get() }
        coordinateur.attendre()
        assertNotNull(memoire.empreinte)

        coordinateur.retirer("echo" to "Écho") // en ligne : la pierre tombale part
        dictionnaire.set(emptyList())
        coordinateur.attendre()

        relais.enPanne = true
        dictionnaire.set(listOf(entree(2, "echo", "Écho")))
        coordinateur.ajouter("echo" to "Écho") // hors ligne : l'ajout échoue et n'a pas de file
        coordinateur.attendre()
        assertNull("l'empreinte, effacée par la suppression, reste effacée après l'ajout manqué", memoire.empreinte)

        relais.enPanne = false
        coordinateur.synchroniserAuDemarrage { dictionnaire.get() }
        coordinateur.attendre()

        assertEquals(
            listOf(
                "lot 1/1 du dictionnaire[echo→Écho:vivant]",
                "Mot #retrait[echo→Écho:retrait]",
                "lot 1/1 du dictionnaire[echo→Écho:vivant]",
            ),
            relais.recus,
        )
        assertNotNull(memoire.empreinte)
    }

    @Test
    fun `changer de relais republie, et un demarrage sans changement ne renvoie rien`() = runBlocking {
        val memoire = Memoire()
        val relais = Relais()
        val cible = AtomicReference("https://a.test")
        val dictionnaire = AtomicReference(listOf(entree(1, "foxtrot", "Foxtrot")))
        val coordinateur = coordinateur(memoire, relais) { cible.get() }

        coordinateur.synchroniserAuDemarrage { dictionnaire.get() }
        coordinateur.attendre()
        coordinateur.synchroniserAuDemarrage { dictionnaire.get() }
        coordinateur.attendre()
        assertEquals(1, relais.recus.size)

        cible.set("https://b.test")
        coordinateur.synchroniserAuDemarrage { dictionnaire.get() }
        coordinateur.attendre()
        assertEquals(2, relais.recus.size)
        assertTrue(relais.recus.all { it == "lot 1/1 du dictionnaire[foxtrot→Foxtrot:vivant]" })
    }

    @Test
    fun `un lot refuse laisse l empreinte vide et tout repart au demarrage suivant`() = runBlocking {
        val memoire = Memoire()
        val relais = Relais()
        val dictionnaire = AtomicReference(listOf(entree(1, "golf", "Golf")))
        val coordinateur = coordinateur(memoire, relais)

        relais.enPanne = true
        coordinateur.synchroniserAuDemarrage { dictionnaire.get() }
        coordinateur.attendre()
        assertNull(memoire.empreinte)
        assertTrue(relais.recus.isEmpty())

        relais.enPanne = false
        coordinateur.synchroniserAuDemarrage { dictionnaire.get() }
        coordinateur.attendre()
        assertEquals(listOf("lot 1/1 du dictionnaire[golf→Golf:vivant]"), relais.recus)
        assertNotNull(memoire.empreinte)
    }

    @Test
    fun `un rejeu en file ne consomme pas une pierre tombale inscrite apres lui`() = runBlocking {
        val memoire = Memoire()
        val relais = Relais()
        val coordinateur = coordinateur(memoire, relais)
        val (enVol, liberer) = relais.bloquerLeProchainEnvoi()

        coordinateur.ajouter("kilo" to "Kilo") // occupe le fil d'envoi
        withTimeout(10_000) { enVol.await() }
        // Pendant ce temps : supprimé, réappris, supprimé de nouveau.
        coordinateur.retirer("kilo" to "Kilo")
        coordinateur.ajouter("kilo" to "Kilo")
        coordinateur.retirer("kilo" to "Kilo")
        liberer.complete(Unit)
        coordinateur.attendre()

        // Le premier rejeu ne trouve plus sa pierre tombale (le réapprentissage
        // l'a retirée) et ne doit pas prendre celle de la seconde suppression :
        // sinon l'ajout partirait en dernier et le mot resterait vivant.
        assertEquals(
            listOf(
                "Mot #ajout[kilo→Kilo:vivant]",
                "Mot #ajout[kilo→Kilo:vivant]",
                "Mot #retrait[kilo→Kilo:retrait]",
            ),
            relais.recus,
        )
        assertTrue(memoire.file.isEmpty())
    }

    @Test
    fun `une pierre tombale inscrite avant l envoi survit a une mort du processus`() = runBlocking {
        val memoire = Memoire()
        val relais = Relais()
        relais.enPanne = true
        val premier = coordinateur(memoire, relais)

        premier.retirer("lima" to "Lima") // inscrite sur le fil appelant, l'envoi échoue
        premier.attendre()
        assertTrue(relais.recus.isEmpty())
        assertEquals(listOf("lima" to "Lima"), memoire.file.map { it.paire })

        // Nouveau processus : même mémoire durable, coordinateur neuf, relais revenu.
        relais.enPanne = false
        val second = coordinateur(memoire, relais)
        second.synchroniserAuDemarrage { emptyList() }
        second.attendre()

        assertEquals(listOf("Mot #retrait[lima→Lima:retrait]"), relais.recus)
        assertTrue(memoire.file.isEmpty())
    }

    @Test
    fun `changer ou couper le relais entre deux lots arrete la republication sans empreinte`() = runBlocking {
        val memoire = Memoire()
        val relais = Relais()
        val cible = AtomicReference<String?>("https://a.test")
        val entrees = (1..501).map { entree(it.toLong(), "mot $it", "Mot $it") }
        val coordinateur = coordinateur(memoire, relais) { cible.get() }
        var porte = relais.bloquerLeProchainEnvoi()

        coordinateur.synchroniserAuDemarrage { entrees }
        withTimeout(10_000) { porte.first.await() } // le premier lot est en vol vers A
        cible.set("https://b.test")
        porte.second.complete(Unit)
        coordinateur.attendre()

        // Le lot en vol arrive à A; le second ne part ni vers A (plus
        // configuré) ni vers B (l'empreinte dirait vrai de personne).
        assertEquals(listOf("https://a.test"), relais.cibles)
        assertNull(memoire.empreinte)

        // B n'a rien accepté : le démarrage suivant republie tout vers lui.
        coordinateur.synchroniserAuDemarrage { entrees }
        coordinateur.attendre()
        assertEquals(listOf("https://a.test", "https://b.test", "https://b.test"), relais.cibles)
        assertEquals(SyncPayloads.dictionarySignature(entrees, "https://b.test"), memoire.empreinte)

        // Relais coupé pendant les envois : même arrêt, rien de plus ne part.
        coordinateur.ajouter("mot 0" to "Mot 0")
        coordinateur.attendre()
        porte = relais.bloquerLeProchainEnvoi()
        coordinateur.synchroniserAuDemarrage { entrees }
        withTimeout(10_000) { porte.first.await() }
        cible.set(null)
        porte.second.complete(Unit)
        coordinateur.attendre()
        assertEquals(listOf("https://a.test", "https://b.test", "https://b.test", "https://b.test", "https://b.test"), relais.cibles)
        assertNull(memoire.empreinte)
    }

    @Test
    fun `un rejeu de demarrage n elague pas la pierre tombale d une suppression en cours`() = runBlocking {
        val memoire = Memoire()
        val relais = Relais()
        // Le magasin inscrit la pierre tombale avant d'effacer la ligne : entre
        // les deux, la paire est encore dans le dictionnaire vivant.
        val dictionnaire = AtomicReference(listOf(entree(1, "oscar", "Oscar"), entree(2, "papa", "Papa")))
        val coordinateur = coordinateur(memoire, relais)
        val (enVol, liberer) = relais.bloquerLeProchainEnvoi()

        coordinateur.ajouter("quebec" to "Québec") // occupe le fil d'envoi
        withTimeout(10_000) { enVol.await() }
        coordinateur.synchroniserAuDemarrage { dictionnaire.get() } // demandé avant la suppression
        coordinateur.retirer("papa" to "Papa") // inscrite; la ligne n'est pas encore effacée
        liberer.complete(Unit)
        coordinateur.attendre()
        dictionnaire.set(dictionnaire.get().filter { it.entendu != "papa" }) // la ligne l'est maintenant

        // Le rejeu du démarrage voit « papa » vivant mais sa pierre tombale
        // est plus récente que lui : ni envoyée ni élaguée. Le lot part avec
        // « papa » (encore vivant à ce moment), puis la pierre tombale, en
        // dernier — le relais finit sans « papa ».
        assertEquals(
            listOf(
                "Mot #ajout[quebec→Québec:vivant]",
                "lot 1/1 du dictionnaire[oscar→Oscar:vivant papa→Papa:vivant]",
                "Mot #retrait[papa→Papa:retrait]",
            ),
            relais.recus,
        )
        assertTrue(memoire.file.isEmpty())
        // L'empreinte n'est pas inscrite : ce lot décrivait « papa » vivant
        // pendant que sa suppression était en suspens. Si le processus meurt
        // avant que la ligne soit effacée, le démarrage suivant republie
        // « papa » — vivant des deux côtés, plutôt que mort au relais et
        // vivant ici sans rien pour les réconcilier.
        assertNull(memoire.empreinte)
        val avantLaMort = listOf(entree(1, "oscar", "Oscar"), entree(2, "papa", "Papa"))
        val second = coordinateur(memoire, relais)
        second.synchroniserAuDemarrage { avantLaMort }
        second.attendre()
        assertEquals("lot 1/1 du dictionnaire[oscar→Oscar:vivant papa→Papa:vivant]", relais.recus.last())
        assertNotNull(memoire.empreinte)
    }

    @Test
    fun `changer puis couper le relais entre deux pierres tombales arrete la serie et garde la file`() = runBlocking {
        val memoire = Memoire()
        val relais = Relais()
        val cible = AtomicReference<String?>("https://a.test")
        val coordinateur = coordinateur(memoire, relais) { cible.get() }

        relais.enPanne = true
        coordinateur.retirer("victor" to "Victor")
        coordinateur.retirer("whisky" to "Whisky")
        coordinateur.attendre()
        assertEquals(listOf("victor" to "Victor", "whisky" to "Whisky"), memoire.file.map { it.paire })

        // Relais revenu : le rejeu envoie « victor », l'adresse change pendant
        // qu'il est en vol — « whisky » ne part ni vers A ni vers B.
        relais.enPanne = false
        var porte = relais.bloquerLeProchainEnvoi()
        coordinateur.synchroniserAuDemarrage { emptyList() }
        withTimeout(10_000) { porte.first.await() }
        cible.set("https://b.test")
        porte.second.complete(Unit)
        coordinateur.attendre()
        assertEquals(listOf("Mot #retrait[victor→Victor:retrait]"), relais.recus)
        assertEquals(listOf("https://a.test"), relais.cibles)
        assertEquals(listOf("whisky" to "Whisky"), memoire.file.map { it.paire })
        assertNull(memoire.empreinte)

        // Relais coupé pendant qu'un rejeu part : « whisky », déjà en vol,
        // arrive; « xray », derrière lui, ne part pas et la file le garde.
        // (« xray » est inscrit relais en panne, sinon il partirait aussitôt.)
        relais.enPanne = true
        coordinateur.retirer("xray" to "Xray")
        coordinateur.attendre()
        assertEquals(listOf("whisky" to "Whisky", "xray" to "Xray"), memoire.file.map { it.paire })
        relais.enPanne = false
        porte = relais.bloquerLeProchainEnvoi()
        coordinateur.synchroniserAuDemarrage { emptyList() }
        withTimeout(10_000) { porte.first.await() }
        cible.set(null)
        porte.second.complete(Unit)
        coordinateur.attendre()
        assertEquals(
            listOf("Mot #retrait[victor→Victor:retrait]", "Mot #retrait[whisky→Whisky:retrait]"),
            relais.recus,
        )
        assertEquals(listOf("xray" to "Xray"), memoire.file.map { it.paire })

        // B, configuré de nouveau, reçoit ce qui restait.
        cible.set("https://b.test")
        coordinateur.synchroniserAuDemarrage { emptyList() }
        coordinateur.attendre()
        assertTrue(memoire.file.isEmpty())
        assertEquals(listOf("https://a.test", "https://b.test", "https://b.test"), relais.cibles)
    }

    @Test
    fun `un mot reappris dont la pierre tombale n a pu etre retiree de la file n est pas rejoue`() = runBlocking {
        val memoire = Memoire()
        val relais = Relais()
        val journal = CopyOnWriteArrayList<String>()
        val coordinateur = DictionarySyncCoordinator(
            memoire = memoire,
            destination = { RemoteRequestTarget(baseUrl = "https://relais.test", token = "jeton") },
            envoyer = { cible, path, payload, label, timeout -> relais.envoyer(cible, path, payload, label, timeout) },
            scope = scope,
            journal = { journal += it },
        )

        relais.enPanne = true
        coordinateur.retirer("yankee" to "Yankee") // en file, l'envoi échoue
        coordinateur.attendre()
        memoire.ecritureRefusee = true
        coordinateur.ajouter("yankee" to "Yankee") // réappris : la file ne peut pas être réécrite
        coordinateur.attendre()
        assertEquals(listOf("yankee" to "Yankee"), memoire.file.map { it.paire })
        assertTrue(journal.any { it.startsWith("Pierre tombale d'un mot réappris non retirée") })

        // Disque et relais revenus : une autre suppression rejoue la file —
        // sans la pierre tombale du mot vivant, qui en est élaguée.
        memoire.ecritureRefusee = false
        relais.enPanne = false
        coordinateur.retirer("zoulou" to "Zoulou")
        coordinateur.attendre()
        assertEquals(listOf("Mot #retrait[zoulou→Zoulou:retrait]"), relais.recus)
        assertTrue(memoire.file.isEmpty())
    }

    @Test
    fun `une pierre tombale non inscrite part aussitot et la suppression est signalee`() = runBlocking {
        val memoire = Memoire()
        val relais = Relais()
        val journal = CopyOnWriteArrayList<String>()
        val coordinateur = DictionarySyncCoordinator(
            memoire = memoire,
            destination = { RemoteRequestTarget(baseUrl = "https://relais.test", token = "jeton") },
            envoyer = { cible, path, payload, label, timeout -> relais.envoyer(cible, path, payload, label, timeout) },
            scope = scope,
            journal = { journal += it },
        )

        memoire.ecritureRefusee = true
        val durable = coordinateur.retirer("romeo" to "Roméo")
        coordinateur.attendre()

        assertTrue(!durable)
        assertEquals(listOf("Mot #retrait[romeo→Roméo:retrait]"), relais.recus)
        assertTrue(memoire.file.isEmpty())
        assertTrue(journal.any { it.startsWith("Pierre tombale non inscrite") })
    }

    @Test
    fun `un envoi qui leve ne tue pas le fil d envoi`() = runBlocking {
        val memoire = Memoire()
        val relais = Relais()
        val journal = CopyOnWriteArrayList<String>()
        val coordinateur = DictionarySyncCoordinator(
            memoire = memoire,
            destination = { RemoteRequestTarget(baseUrl = "https://relais.test", token = "jeton") },
            envoyer = { cible, path, payload, label, timeout -> relais.envoyer(cible, path, payload, label, timeout) },
            scope = scope,
            journal = { journal += it },
        )

        relais.leveUneFois = true
        coordinateur.ajouter("mike" to "Mike") // le transport lève
        coordinateur.ajouter("november" to "November") // le suivant doit partir quand même
        coordinateur.attendre()

        assertEquals(listOf("Mot #ajout[november→November:vivant]"), relais.recus)
        assertEquals(listOf("Travail de synchronisation abandonné (IllegalStateException)"), journal)
    }

    @Test
    fun `une portee morte refuse les travaux et le dit`() = runBlocking {
        val memoire = Memoire()
        val relais = Relais()
        val journal = CopyOnWriteArrayList<String>()
        val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinateur = DictionarySyncCoordinator(
            memoire = memoire,
            destination = { RemoteRequestTarget(baseUrl = "https://relais.test", token = "jeton") },
            envoyer = { cible, path, payload, label, timeout -> relais.envoyer(cible, path, payload, label, timeout) },
            scope = portee,
            journal = { journal += it },
        )
        val (enVol, liberer) = relais.bloquerLeProchainEnvoi()

        coordinateur.ajouter("sierra" to "Sierra") // occupe le fil d'envoi
        withTimeout(10_000) { enVol.await() }
        coordinateur.ajouter("tango" to "Tango") // en file derrière
        portee.cancel() // le processus s'arrête : l'envoi en vol est interrompu
        liberer.complete(Unit)
        withTimeout(10_000) { portee.coroutineContext[Job]!!.join() }
        assertTrue(coordinateur.retirer("uniform" to "Uniform")) // durable, mais personne pour l'envoyer

        assertTrue(relais.recus.isEmpty())
        assertEquals(listOf("uniform" to "Uniform"), memoire.file.map { it.paire })
        assertTrue(journal.any { it.startsWith("Fil d'envoi arrêté : 1 travail(aux)") })
        assertTrue(journal.any { it == "Travail de synchronisation refusé : fil d'envoi arrêté" })
        coordinateur.attendre() // ne pend pas : plus de fil, rien à attendre
    }

    @Test
    fun `sans relais configure rien ne part et l empreinte reste vide`() = runBlocking {
        val memoire = Memoire()
        val relais = Relais()
        val coordinateur = coordinateur(memoire, relais) { null }

        coordinateur.synchroniserAuDemarrage { listOf(entree(1, "hotel", "Hôtel")) }
        coordinateur.attendre()

        assertTrue(relais.recus.isEmpty())
        assertNull(memoire.empreinte)
    }
}
