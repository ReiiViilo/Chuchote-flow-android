package dev.soupslurpr.transcribro.memory

/**
 * Le dictionnaire personnel appliqué à un texte, et le vocabulaire soufflé au
 * modèle distant — en Kotlin pur, sans Android, pour être testé sur la JVM.
 * Les vecteurs de substitution sont les mêmes que ceux de `dictionary.rs`
 * côté desktop; la garde de longueur y est répliquée, pour qu'une entrée
 * inerte ici le soit aussi là-bas une fois synchronisée. La parité est
 * exacte sur le texte latin, accents compris; hors du plan multilingue de
 * base (emoji, écritures rares) les deux implémentations divergent à la
 * marge — `\p{L}\p{N}` et `isLetterOrDigit` ici, `is_alphanumeric` là-bas —
 * sans conséquence sur une dictée en français.
 *
 * Deux natures d'entrée coexistent, et ce fichier tient à les séparer :
 *
 * - **Substitution** (`remplacerPar` non vide) : après toute transcription,
 *   locale ou distante, la forme entendue est remplacée mot pour mot. C'est
 *   déterministe et vérifiable.
 * - **Vocabulaire** (`remplacerPar` vide) : le mot est soufflé au modèle
 *   distant pour orienter la reconnaissance. C'est probabiliste, et c'est ce
 *   qui peut faire apparaître le mot là où il n'a rien à faire : un modèle
 *   peut recopier le vocabulaire qu'on lui souffle (hypothèse de travail, non
 *   mesurée dans ce dépôt — voir `BUGS_HISTORY.md`, 2026-09-15). Les cibles de
 *   substitution ne sont donc plus soufflées : la substitution les garantit
 *   déjà, sans ce risque. Qui veut orienter le modèle vers un mot l'ajoute
 *   comme entrée de vocabulaire, en connaissance de cause.
 */
object DictionnaireSubstitution {

    /**
     * Longueur minimale du cœur d'une forme entendue pour qu'elle déclenche
     * une substitution. Même mesure et même seuil qu'à l'apprentissage
     * (`CorrectionDiff`) : le cœur est le mot débarrassé de la ponctuation qui
     * le borde, apostrophes et traits d'union compris. Une forme de deux
     * caractères — « op », « au », « eu » — apparaît partout dans la parole
     * ordinaire et transformerait le moindre mot en correction fantôme. Le
     * revers assumé : un sigle de deux lettres (« IA ») ne peut pas être une
     * substitution; l'écran Dictionnaire le signale.
     */
    const val LONGUEUR_MIN_ENTENDU = 3

    private class Compilee(val entree: EntreeDictionnaire, val regex: Regex)

    // Les regex sont compilées une fois par liste d'entrées : la liste publiée
    // par le magasin ne change d'identité qu'au rechargement, alors que la
    // substitution est appelée à chaque segment partiel d'une dictée.
    @Volatile
    private var cache: Pair<List<EntreeDictionnaire>, List<Compilee>>? = null

    private fun compiler(entrees: List<EntreeDictionnaire>): List<Compilee> {
        cache?.let { (source, compilees) -> if (source === entrees) return compilees }
        val compilees = entrees
            .filter(::estSubstitutionApplicable)
            .map { entree ->
                Compilee(
                    entree,
                    Regex(
                        "(?iu)(?<![\\p{L}\\p{N}])${Regex.escape(entree.entendu.trim())}(?![\\p{L}\\p{N}])",
                    ),
                )
            }
        cache = entrees to compilees
        return compilees
    }

    /**
     * Applique toutes les substitutions à [texte]. [journal] est appelé pour
     * chaque entrée ayant réellement modifié le texte, avec le nombre
     * d'occurrences — c'est ce qui permet de retrouver l'entrée responsable
     * d'une correction inattendue, sans jamais journaliser de texte.
     */
    fun appliquer(
        texte: String,
        entrees: List<EntreeDictionnaire>,
        journal: ((entree: EntreeDictionnaire, occurrences: Int) -> Unit)? = null,
    ): String {
        var resultat = texte
        for (compilee in compiler(entrees)) {
            var occurrences = 0
            resultat = compilee.regex.replace(resultat) { correspondance ->
                occurrences++
                val brut = compilee.entree.remplacerPar.trim()
                if (
                    correspondance.value.first().isUpperCase() &&
                    brut.firstOrNull()?.isLowerCase() == true
                ) {
                    brut.replaceFirstChar { it.uppercase() }
                } else {
                    brut
                }
            }
            if (occurrences > 0) journal?.invoke(compilee.entree, occurrences)
        }
        return resultat
    }

    /** Une substitution ne s'applique que si elle est complète et assez longue. */
    fun estSubstitutionApplicable(entree: EntreeDictionnaire): Boolean {
        if (entree.remplacerPar.isBlank()) return false
        // Des points de code, pas des unités UTF-16 : un caractère hors du
        // plan de base compte pour un, comme côté desktop.
        val coeur = coeur(entree.entendu)
        return coeur.codePointCount(0, coeur.length) >= LONGUEUR_MIN_ENTENDU
    }

    /** Le cœur d'un mot, comme `CorrectionDiff` le découpe. */
    fun coeur(mot: String): String =
        mot.trim().trim { c -> !c.isLetterOrDigit() && c != '\'' && c != '-' }

    /**
     * Le vocabulaire à souffler au modèle distant : les seules entrées de
     * vocabulaire, dédoublonnées à la casse près, en liste séparée par des
     * virgules — la forme que la documentation du fournisseur recommande.
     * Vide s'il n'y a rien à souffler. [maxCaracteres] borne la liste; un mot
     * n'est jamais coupé en deux, il est laissé de côté.
     */
    fun vocabulairePourBiais(
        entrees: List<EntreeDictionnaire>,
        maxCaracteres: Int,
    ): String {
        val mots = entrees.asSequence()
            .filter { it.remplacerPar.isBlank() }
            .map { it.entendu.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .toList()

        val retenus = mutableListOf<String>()
        var longueur = 0
        for (mot in mots) {
            val ajout = if (retenus.isEmpty()) mot.length else SEPARATEUR.length + mot.length
            if (longueur + ajout > maxCaracteres) break
            retenus += mot
            longueur += ajout
        }
        return retenus.joinToString(SEPARATEUR)
    }

    private const val SEPARATEUR = ", "
}
