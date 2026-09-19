package dev.soupslurpr.transcribro.remote

import dev.soupslurpr.transcribro.memory.EntreeDictionnaire
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

        @Synchronized
        override fun lire(cle: String): String? = table[cle]

        @Synchronized
        override fun ecrire(cle: String, valeur: String) {
            table[cle] = valeur
        }

        @Synchronized
        override fun effacer(cle: String) {
            table.remove(cle)
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
        dictionnaire.set(dictionnaire.get().filter { it.entendu != "beta" })
        coordinateur.retirer("beta" to "Beta")
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
        dictionnaire.set(dictionnaire.get().filter { it.entendu != "wapiti" })
        coordinateur.retirer("wapiti" to "Wapiti") // supprimé avant que le démarrage parte
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
        assertNotNull(memoire.empreinte)
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

        dictionnaire.set(emptyList())
        coordinateur.retirer("echo" to "Écho") // en ligne : la pierre tombale part
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
    fun `une republication en plusieurs lots vise le relais lu a son depart`() = runBlocking {
        val memoire = Memoire()
        val relais = Relais()
        val cible = AtomicReference("https://a.test")
        val entrees = (1..501).map { entree(it.toLong(), "mot $it", "Mot $it") }
        val coordinateur = coordinateur(memoire, relais) { cible.get() }
        val (enVol, liberer) = relais.bloquerLeProchainEnvoi()

        coordinateur.synchroniserAuDemarrage { entrees }
        withTimeout(10_000) { enVol.await() } // le premier lot est en vol vers A
        cible.set("https://b.test")
        liberer.complete(Unit)
        coordinateur.attendre()

        // Les deux lots vont à A, et l'empreinte enregistrée est celle de A.
        assertEquals(listOf("https://a.test", "https://a.test"), relais.cibles)
        assertEquals(2, relais.recus.size)
        assertEquals(SyncPayloads.dictionarySignature(entrees, "https://a.test"), memoire.empreinte)

        // B n'a rien accepté : le démarrage suivant republie vers lui.
        coordinateur.synchroniserAuDemarrage { entrees }
        coordinateur.attendre()
        assertEquals(listOf("https://a.test", "https://a.test", "https://b.test", "https://b.test"), relais.cibles)
        assertEquals(SyncPayloads.dictionarySignature(entrees, "https://b.test"), memoire.empreinte)
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
