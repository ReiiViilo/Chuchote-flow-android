package dev.soupslurpr.transcribro.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class Dictee(
    val id: Long,
    val texte: String,
    /** Moment de la dictée, en millisecondes depuis l'époque Unix. */
    val creeLe: Long,
    /** Délai vécu entre la validation et le texte, en millisecondes. */
    val dureeMs: Long? = null,
    /** Chemin de transcription : « relais », « local » ou « mixte ». */
    val source: String? = null,
)

/**
 * Une entrée du dictionnaire personnel.
 *
 * Avec [remplacerPar] vide, c'est un mot que la transcription doit connaître
 * (un prénom, un nom d'entreprise…) : il sert à guider le modèle. Avec
 * [remplacerPar] rempli, c'est une correction automatique : chaque fois que
 * [entendu] apparaît dans une transcription, il est remplacé.
 */
data class EntreeDictionnaire(
    val id: Long,
    val entendu: String,
    val remplacerPar: String,
)

/**
 * Mémoire de Chuchote Flow : l'historique des dictées et le dictionnaire
 * personnel. Tout vit dans une base SQLite sur l'appareil — rien n'est
 * envoyé nulle part.
 */
class ChuchoteStore private constructor(context: Context) {

    private val db = Db(context)

    // Un seul fil pour toutes les écritures : SQLite les sérialise de toute
    // façon, autant éviter d'ouvrir des transactions concurrentes.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(Dispatchers.IO.limitedParallelism(1))

    private val _dictees = MutableStateFlow<List<Dictee>>(emptyList())
    val dictees: StateFlow<List<Dictee>> = _dictees.asStateFlow()

    private val _dictionnaire = MutableStateFlow<List<EntreeDictionnaire>>(emptyList())
    val dictionnaire: StateFlow<List<EntreeDictionnaire>> = _dictionnaire.asStateFlow()

    init {
        scope.launch {
            rechargerDictees()
            rechargerDictionnaire()
        }
    }

    // ------------------------------------------------------------------
    // Historique
    // ------------------------------------------------------------------

    fun ajouterDictee(texte: String, dureeMs: Long? = null, source: String? = null) {
        val propre = texte.trim()
        if (propre.isEmpty()) return
        scope.launch {
            db.writableDatabase.insert("dictees", null, ContentValues().apply {
                put("texte", propre)
                put("cree_le", System.currentTimeMillis())
                dureeMs?.takeIf { it > 0 }?.let { put("duree_ms", it) }
                source?.let { put("source", it) }
            })
            // Garder l'historique borné : au-delà, les plus vieilles dictées
            // partent en silence.
            db.writableDatabase.execSQL(
                "DELETE FROM dictees WHERE id NOT IN " +
                        "(SELECT id FROM dictees ORDER BY id DESC LIMIT $MAX_DICTEES)"
            )
            rechargerDictees()
        }
    }

    fun supprimerDictee(id: Long) {
        scope.launch {
            db.writableDatabase.delete("dictees", "id = ?", arrayOf(id.toString()))
            rechargerDictees()
        }
    }

    fun effacerHistorique() {
        scope.launch {
            db.writableDatabase.delete("dictees", null, null)
            rechargerDictees()
        }
    }

    // ------------------------------------------------------------------
    // Dictionnaire
    // ------------------------------------------------------------------

    fun ajouterEntree(entendu: String, remplacerPar: String) {
        val mot = entendu.trim()
        if (mot.isEmpty()) return
        scope.launch {
            db.writableDatabase.insert("dictionnaire", null, ContentValues().apply {
                put("entendu", mot)
                put("remplacer_par", remplacerPar.trim())
            })
            rechargerDictionnaire()
        }
    }

    fun supprimerEntree(id: Long) {
        scope.launch {
            db.writableDatabase.delete("dictionnaire", "id = ?", arrayOf(id.toString()))
            rechargerDictionnaire()
        }
    }

    /**
     * Applique les corrections automatiques du dictionnaire à une
     * transcription. La casse de la première lettre est préservée : « shishot »
     * en début de phrase devient « Chuchote » même si la correction est écrite
     * en minuscules.
     */
    fun appliquerCorrections(texte: String): String {
        var resultat = texte
        for (entree in _dictionnaire.value) {
            if (entree.remplacerPar.isEmpty()) continue
            // Frontières de mot écrites à la main : le \b de java.util.regex
            // ne connaît que l'ASCII, ce qui casserait les mots accentués.
            val regex = Regex(
                "(?iu)(?<![\\p{L}\\p{N}])${Regex.escape(entree.entendu)}(?![\\p{L}\\p{N}])"
            )
            resultat = regex.replace(resultat) { correspondance ->
                val brut = entree.remplacerPar
                if (correspondance.value.first().isUpperCase() && brut.first().isLowerCase()) {
                    brut.replaceFirstChar { it.uppercase() }
                } else {
                    brut
                }
            }
        }
        return resultat
    }

    /**
     * Les mots que le modèle de transcription doit connaître, prêts à servir
     * de « prompt » Whisper : les mots simples du dictionnaire et les formes
     * corrigées des remplacements. Vide quand le dictionnaire l'est.
     */
    fun motsPourBiais(): String {
        val mots = _dictionnaire.value
            .map { it.remplacerPar.ifEmpty { it.entendu } }
            .distinct()
        if (mots.isEmpty()) return ""
        return mots.joinToString(", ").take(MAX_BIAIS_CARACTERES)
    }

    // ------------------------------------------------------------------

    private fun rechargerDictees() {
        val liste = mutableListOf<Dictee>()
        db.readableDatabase.rawQuery(
            "SELECT id, texte, cree_le, duree_ms, source FROM dictees ORDER BY id DESC", null
        ).use { curseur ->
            while (curseur.moveToNext()) {
                liste.add(
                    Dictee(
                        id = curseur.getLong(0),
                        texte = curseur.getString(1),
                        creeLe = curseur.getLong(2),
                        dureeMs = if (curseur.isNull(3)) null else curseur.getLong(3),
                        source = if (curseur.isNull(4)) null else curseur.getString(4),
                    )
                )
            }
        }
        _dictees.value = liste
    }

    private fun rechargerDictionnaire() {
        val liste = mutableListOf<EntreeDictionnaire>()
        db.readableDatabase.rawQuery(
            "SELECT id, entendu, remplacer_par FROM dictionnaire ORDER BY entendu COLLATE NOCASE", null
        ).use { curseur ->
            while (curseur.moveToNext()) {
                liste.add(
                    EntreeDictionnaire(curseur.getLong(0), curseur.getString(1), curseur.getString(2))
                )
            }
        }
        _dictionnaire.value = liste
    }

    private class Db(context: Context) : SQLiteOpenHelper(context, "chuchote.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE dictees (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "texte TEXT NOT NULL, " +
                        "cree_le INTEGER NOT NULL, " +
                        "duree_ms INTEGER, " +
                        "source TEXT)"
            )
            db.execSQL(
                "CREATE TABLE dictionnaire (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "entendu TEXT NOT NULL, " +
                        "remplacer_par TEXT NOT NULL DEFAULT '')"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE dictees ADD COLUMN duree_ms INTEGER")
                db.execSQL("ALTER TABLE dictees ADD COLUMN source TEXT")
            }
        }
    }

    companion object {
        private const val MAX_DICTEES = 500

        // Le prompt Whisper est limité à ~224 jetons ; au-delà il est tronqué
        // par le modèle de toute façon.
        private const val MAX_BIAIS_CARACTERES = 600

        @Volatile
        private var instance: ChuchoteStore? = null

        fun get(context: Context): ChuchoteStore =
            instance ?: synchronized(this) {
                instance ?: ChuchoteStore(context.applicationContext).also { instance = it }
            }
    }
}
