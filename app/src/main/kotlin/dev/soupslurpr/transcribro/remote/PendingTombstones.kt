package dev.soupslurpr.transcribro.remote

import org.json.JSONArray
import org.json.JSONObject

/**
 * Une suppression du dictionnaire que le relais n'a pas encore acceptée :
 * la paire retirée et le moment de la suppression.
 */
internal data class TombaleEnAttente(
    val entendu: String,
    val remplacerPar: String,
    val depuisMs: Long,
) {
    val paire: Pair<String, String> get() = entendu to remplacerPar
}

/**
 * La file des pierres tombales que le relais n'a pas encore acceptées, et la
 * politique qui la borne. Kotlin pur, vérifié sur la JVM; `SyncPusher` ne
 * fait qu'y lire et y écrire `SharedPreferences` (`chuchote_sync`, exclu des
 * sauvegardes Android comme le reste du dictionnaire).
 *
 * La file contient des mots du dictionnaire personnel : elle est donc bornée
 * des deux côtés. En taille ([PLAFOND], la plus ancienne cède la place) et en
 * âge ([PEREMPTION_MS]) : une pierre tombale trop vieille n'est plus envoyée,
 * parce que le relais date chaque réception et qu'une suppression d'il y a un
 * mois, rejouée aujourd'hui, effacerait un mot que l'autre appareil a pu
 * réapprendre entre-temps. Une suppression restée si longtemps en file sera
 * refaite en ligne si elle compte encore.
 *
 * Une file illisible — préférence corrompue, format d'une autre version —
 * vaut `null` : l'appelant la journalise et repart de zéro. Mieux vaut perdre
 * une suppression que bloquer le démarrage du magasin.
 */
internal object PendingTombstones {

    const val PLAFOND = 200
    const val PEREMPTION_MS = 30L * 24 * 60 * 60 * 1000

    /**
     * La file après une suppression : la paire, si elle attendait déjà, repart
     * avec la date d'aujourd'hui; au-delà du plafond, les plus anciennes sont
     * abandonnées.
     */
    fun ajouter(file: List<TombaleEnAttente>, paire: Pair<String, String>, maintenantMs: Long): List<TombaleEnAttente> =
        (file.filterNot { it.paire == paire } + TombaleEnAttente(paire.first, paire.second, maintenantMs))
            .takeLast(PLAFOND)

    /** La file sans la paire (réapprise, ou acceptée par le relais). */
    fun retirer(file: List<TombaleEnAttente>, paire: Pair<String, String>): List<TombaleEnAttente> =
        file.filterNot { it.paire == paire }

    /**
     * Ce qui vaut encore la peine d'être envoyé, dans l'ordre : ni les paires
     * redevenues vivantes localement ([exclure]), ni les périmées.
     */
    fun aRejouer(file: List<TombaleEnAttente>, exclure: Set<Pair<String, String>>, maintenantMs: Long): List<TombaleEnAttente> =
        file.filter { it.paire !in exclure && maintenantMs - it.depuisMs <= PEREMPTION_MS }

    /**
     * Ce qui reste en file après un rejeu : la file **courante** ([file], relue
     * après les envois) moins ce que le relais a accepté ([envoyees]). Le
     * retrait se fait à l'identité — paire et date — pour qu'une paire ajoutée
     * ou re-supprimée pendant les envois, donc datée autrement, reste en file.
     */
    fun restantes(file: List<TombaleEnAttente>, envoyees: Collection<TombaleEnAttente>): List<TombaleEnAttente> =
        file.filterNot { it in envoyees }

    fun encode(file: List<TombaleEnAttente>): String {
        val array = JSONArray()
        file.forEach {
            array.put(JSONObject().put("heard", it.entendu).put("replace_with", it.remplacerPar).put("since", it.depuisMs))
        }
        return array.toString()
    }

    /**
     * `null` si la file est illisible. Un élément sans date est daté de zéro :
     * il tombe à la prochaine péremption plutôt que d'être rejoué à l'aveugle.
     */
    fun decode(raw: String?): List<TombaleEnAttente>? {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val objet = array.optJSONObject(index) ?: return@mapNotNull null
                val entendu = objet.optString("heard")
                if (entendu.isEmpty()) {
                    null
                } else {
                    TombaleEnAttente(entendu, objet.optString("replace_with"), objet.optLong("since", 0L))
                }
            }.distinctBy { it.paire }
        }.getOrNull()
    }
}
