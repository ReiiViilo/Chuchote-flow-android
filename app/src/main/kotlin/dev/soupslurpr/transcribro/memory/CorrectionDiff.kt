package dev.soupslurpr.transcribro.memory

/**
 * Compare le texte tel qu'il a été dicté au texte tel que l'utilisateur l'a
 * laissé, et en tire des propositions de corrections pour le dictionnaire.
 *
 * L'alignement se fait mot à mot par plus longue sous-séquence commune : ce
 * qui reste hors de l'alignement, ce sont les retouches. Une retouche courte
 * (« chichotte » devenu « Chuchote ») est une candidate ; les ajouts, les
 * suppressions et les réécritures longues sont ignorés — on ne veut apprendre
 * que les mots écorchés, pas les changements d'avis.
 */
object CorrectionDiff {

    data class Proposition(val entendu: String, val remplacerPar: String)

    fun proposer(avant: String, apres: String): List<Proposition> {
        if (avant.isBlank() || apres.isBlank() || avant == apres) return emptyList()

        val motsAvant = decouper(avant)
        val motsApres = decouper(apres)
        if (motsAvant.isEmpty() || motsApres.isEmpty()) return emptyList()
        if (motsAvant.size > MAX_MOTS || motsApres.size > MAX_MOTS) return emptyList()

        val propositions = mutableListOf<Proposition>()

        // Alignement par plus longue sous-séquence commune, sur le cœur des
        // mots (sans la ponctuation qui les borde).
        val lcs = Array(motsAvant.size + 1) { IntArray(motsApres.size + 1) }
        for (i in motsAvant.indices.reversed()) {
            for (j in motsApres.indices.reversed()) {
                lcs[i][j] = if (motsAvant[i].coeur == motsApres[j].coeur) {
                    lcs[i + 1][j + 1] + 1
                } else {
                    maxOf(lcs[i + 1][j], lcs[i][j + 1])
                }
            }
        }

        var i = 0
        var j = 0
        var debutI = 0
        var debutJ = 0
        while (i < motsAvant.size && j < motsApres.size) {
            if (motsAvant[i].coeur == motsApres[j].coeur) {
                retoucheEnProposition(
                    motsAvant.subList(debutI, i),
                    motsApres.subList(debutJ, j),
                )?.let(propositions::add)
                i++
                j++
                debutI = i
                debutJ = j
                continue
            }
            if (lcs[i + 1][j] >= lcs[i][j + 1]) i++ else j++
        }
        // La dernière substitution n'est suivie d'aucun mot commun qui
        // déclencherait le flush ci-dessus. Elle doit donc être traitée ici.
        retoucheEnProposition(
            motsAvant.subList(debutI, motsAvant.size),
            motsApres.subList(debutJ, motsApres.size),
        )?.let(propositions::add)

        return propositions.distinct().take(MAX_PROPOSITIONS)
    }

    private fun retoucheEnProposition(
        avant: List<Mot>,
        apres: List<Mot>,
    ): Proposition? {
        // Un ajout ou une suppression n'apprend rien sur la transcription.
        if (avant.isEmpty() || apres.isEmpty()) return null
        // Au-delà de quelques mots, c'est une réécriture, pas une correction.
        if (avant.size > MAX_MOTS_PAR_RETOUCHE || apres.size > MAX_MOTS_PAR_RETOUCHE) return null

        val entendu = avant.joinToString(" ") { it.coeur }
        val remplacerPar = apres.joinToString(" ") { it.coeur }

        if (entendu.length < LONGUEUR_MIN || remplacerPar.isEmpty()) return null
        // Un nombre corrigé est une retouche de contenu, pas d'orthographe.
        if (entendu.all { !it.isLetter() } || remplacerPar.all { !it.isLetter() }) return null

        if (entendu.equals(remplacerPar, ignoreCase = true)) {
            // Même mot à la casse près : n'apprendre que les majuscules
            // internes (« opop » → « OpOp »), pas la majuscule de début de
            // phrase que l'utilisateur ajuste sans penser au dictionnaire.
            val majusculeInterne = remplacerPar.drop(1).any { it.isUpperCase() } &&
                    entendu.drop(1).none { it.isUpperCase() }
            if (!majusculeInterne) return null
        }

        return Proposition(entendu, remplacerPar)
    }

    private data class Mot(val coeur: String)

    private fun decouper(texte: String): List<Mot> =
        texte.split(Regex("\\s+"))
            .map { Mot(it.trim { c -> !c.isLetterOrDigit() && c != '\'' && c != '-' }) }
            .filter { it.coeur.isNotEmpty() }

    private const val MAX_MOTS = 400
    private const val MAX_MOTS_PAR_RETOUCHE = 3
    private const val MAX_PROPOSITIONS = 3
    private const val LONGUEUR_MIN = 3
}
