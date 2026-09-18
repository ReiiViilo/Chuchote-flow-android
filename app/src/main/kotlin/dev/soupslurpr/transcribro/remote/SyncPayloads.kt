package dev.soupslurpr.transcribro.remote

import dev.soupslurpr.transcribro.memory.EntreeDictionnaire
import org.json.JSONArray
import org.json.JSONObject

/**
 * Les charges utiles envoyées au cerveau commun, construites sans Android ni
 * réseau pour être vérifiées sur la JVM : c'est un contrat inter-dépôts
 * (`Chuchote-Flow/server/app/api/sync/`), et la seule frontière par laquelle
 * du texte personnel quitte l'appareil hors transcription.
 */
object SyncPayloads {

    /** Le relais refuse plus de 500 entrées par envoi. */
    const val MAX_DICTIONARY_ENTRIES_PER_REQUEST = 500

    fun dictation(
        localId: Long,
        createdAtMs: Long,
        rawText: String?,
        finalText: String,
        durationMs: Long?,
        source: String?,
    ): JSONObject = JSONObject().put(
        "dictations",
        JSONArray().put(
            JSONObject()
                .put("device", "android")
                .put("device_local_id", localId.toString())
                .put("created_at", isoUtc(createdAtMs))
                .put("raw_text", rawText ?: JSONObject.NULL)
                .put("final_text", finalText)
                .put("duration_ms", durationMs ?: JSONObject.NULL)
                .put("source", source ?: JSONObject.NULL),
        ),
    )

    fun dictionaryEntry(entendu: String, remplacerPar: String): JSONObject =
        JSONObject().put("entries", JSONArray().put(entryJson(entendu, remplacerPar, deleted = false)))

    /**
     * Une suppression locale devient une pierre tombale : la clé de conflit du
     * relais est la paire (entendu, remplacement), donc les deux sont portés.
     */
    fun dictionaryTombstone(entendu: String, remplacerPar: String): JSONObject =
        JSONObject().put("entries", JSONArray().put(entryJson(entendu, remplacerPar, deleted = true)))

    /**
     * Tout le dictionnaire, découpé en lots d'au plus
     * [MAX_DICTIONARY_ENTRIES_PER_REQUEST] entrées — le relais n'en accepte
     * pas davantage par envoi. Vide s'il n'y a rien à envoyer.
     */
    fun dictionaryBatches(entrees: List<EntreeDictionnaire>): List<JSONObject> =
        entrees
            .filter { it.entendu.isNotBlank() }
            .chunked(MAX_DICTIONARY_ENTRIES_PER_REQUEST)
            .map { lot ->
                val array = JSONArray()
                lot.forEach { array.put(entryJson(it.entendu, it.remplacerPar, deleted = false)) }
                JSONObject().put("entries", array)
            }

    /**
     * Empreinte du dictionnaire tel qu'il serait envoyé : sert à ne pas
     * republier à chaque démarrage un dictionnaire qui n'a pas changé. Les
     * deux champs sont séparés par un caractère nul — écrit en échappement,
     * jamais en octet brut, sinon Git classe le fichier en binaire et son diff
     * disparaît des revues — parce qu'une espace laisserait `("a b","c")` et
     * `("a","b c")` produire la même empreinte.
     */
    fun dictionarySignature(entrees: List<EntreeDictionnaire>): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        entrees.asSequence()
            .filter { it.entendu.isNotBlank() }
            .map { "${it.entendu.trim()}\u0000${it.remplacerPar.trim()}\n" }
            .sorted()
            .forEach { digest.update(it.toByteArray(Charsets.UTF_8)) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun entryJson(entendu: String, remplacerPar: String, deleted: Boolean): JSONObject =
        JSONObject()
            .put("heard", entendu.trim())
            .put("replace_with", remplacerPar.trim())
            .put("deleted", deleted)

    fun isoUtc(epochMs: Long): String = java.time.Instant.ofEpochMilli(epochMs).toString()
}
