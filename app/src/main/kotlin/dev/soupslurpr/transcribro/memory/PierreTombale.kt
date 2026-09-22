package dev.soupslurpr.transcribro.memory

/**
 * Ce qu'une suppression annonce au relais. La table `dictionnaire` n'impose
 * pas l'unicité de la paire (entendu, remplacement) — deux lignes identiques
 * peuvent coexister —, mais la clé du relais est la paire : une pierre
 * tombale n'est posée que pour la **dernière** ligne d'une paire, sinon
 * supprimer un doublon effacerait au relais un mot que l'appareil applique
 * encore.
 */
internal object PierreTombale {
    /**
     * La paire à annoncer pour la suppression de la ligne [id] — les deux
     * termes débarrassés de leurs espaces, comme le relais les garde —, ou
     * nulle si une autre ligne porte encore la même paire, ou si [id] est
     * inconnu.
     */
    fun aAnnoncer(entrees: List<EntreeDictionnaire>, id: Long): Pair<String, String>? {
        val entree = entrees.firstOrNull { it.id == id } ?: return null
        val paire = entree.paire()
        val portee = entrees.any { it.id != id && it.paire() == paire }
        return if (portee) null else paire
    }

    private fun EntreeDictionnaire.paire() = entendu.trim() to remplacerPar.trim()
}
