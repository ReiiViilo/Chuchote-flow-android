package dev.soupslurpr.transcribro.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Les vecteurs de substitution sont les mêmes que ceux de `dictionary.rs`
 * (desktop, module `tests`), garde de longueur comprise : une divergence ici
 * est une divergence entre les deux surfaces, à corriger des deux côtés.
 */
class DictionnaireSubstitutionTest {

    private fun entrees(vararg paires: Pair<String, String>): List<EntreeDictionnaire> =
        paires.mapIndexed { index, (entendu, remplacerPar) ->
            EntreeDictionnaire(id = index.toLong() + 1, entendu = entendu, remplacerPar = remplacerPar)
        }

    private fun appliquer(texte: String, vararg paires: Pair<String, String>): String =
        DictionnaireSubstitution.appliquer(texte, entrees(*paires))

    // ------------------------------------------------------------------
    // Vecteurs communs avec le desktop
    // ------------------------------------------------------------------

    @Test
    fun `remplace en respectant les frontieres de mots`() {
        assertEquals(
            "Faire un test en disant OpOp conseil.",
            appliquer("Faire un test en disant hop hop conseil.", "hop hop" to "OpOp"),
        )
        // Collé à d'autres lettres : ce n'est pas le mot, on n'y touche pas.
        assertEquals("le shop hop hopper", appliquer("le shop hop hopper", "hop hop" to "OpOp"))
        assertEquals("chop hop", appliquer("chop hop", "hop hop" to "OpOp"))
        // Un candidat rejeté (« chop hop ») ne doit pas avaler l'occurrence
        // valide qui commence en son milieu. Même vecteur côté desktop.
        assertEquals("chop OpOp", appliquer("chop hop hop", "hop hop" to "OpOp"))
    }

    @Test
    fun `conserve la capitale initiale`() {
        // Le remplacement commence déjà par une majuscule : rendu tel quel.
        assertEquals("Test OpOp Conseil", appliquer("Test Hop Hop Conseil", "hop hop" to "OpOp"))
        // Remplacement minuscule sur une occurrence capitalisée : on capitalise.
        assertEquals("Chuchote parle", appliquer("Chuchotte parle", "chuchotte" to "chuchote"))
        assertEquals("je chuchote", appliquer("je chuchotte", "chuchotte" to "chuchote"))
    }

    @Test
    fun `traite toutes les occurrences et les accents`() {
        assertEquals("OpOp puis OpOp", appliquer("hop hop puis hop hop", "hop hop" to "OpOp"))
        // Une lettre accentuée reste un caractère de mot : pas de coupure.
        assertEquals("préhop hop", appliquer("préhop hop", "hop hop" to "OpOp"))
        // Un rejet sur une lettre multi-octets ne doit pas couper un char
        // (vecteur desktop, où la reprise se fait en octets UTF-8).
        assertEquals("éhop OpOp", appliquer("éhop hop hop", "hop hop" to "OpOp"))
    }

    @Test
    fun `ignore les entrees vides`() {
        assertEquals("mot et hop", appliquer("mot et hop", "" to "OpOp", "  " to "x", "mot" to ""))
    }

    @Test
    fun `une forme entendue trop courte ne declenche jamais de substitution`() {
        // « op » apparaît dans la parole ordinaire : l'appliquer transformerait
        // n'importe quel « op » isolé en correction fantôme. Même vecteur que
        // `ignore_les_formes_trop_courtes` côté desktop.
        val texte = "un op de plus, au vu de tout"
        assertEquals(texte, appliquer(texte, "op" to "OpOp", "au" to "OpOp"))
        // Trois caractères de cœur suffisent, même entourés d'espaces.
        assertEquals("OpOp là", appliquer("hop là", " hop " to "OpOp"))
    }

    // ------------------------------------------------------------------
    // Garde-fous propres à Android
    // ------------------------------------------------------------------

    @Test
    fun `la garde mesure le coeur du mot comme l apprentissage`() {
        fun applicable(entendu: String) =
            DictionnaireSubstitution.estSubstitutionApplicable(EntreeDictionnaire(1, entendu, "x"))
        assertFalse(applicable("op"))
        assertFalse(applicable("IA"))
        assertFalse(applicable("(ia)")) // la ponctuation qui borde ne compte pas
        assertTrue(applicable("S.F")) // la ponctuation interne fait partie du cœur
        assertTrue(applicable("n°1"))
        assertTrue(applicable("hop"))
        assertEquals("S.F", DictionnaireSubstitution.coeur(" (S.F.) "))
        assertEquals("l'op", DictionnaireSubstitution.coeur("l'op,"))
    }

    @Test
    fun `journalise chaque entree qui a modifie le texte avec son nombre d occurrences`() {
        val vues = mutableListOf<Pair<Long, Int>>()
        val resultat = DictionnaireSubstitution.appliquer(
            "hop hop puis hop hop et rien d'autre",
            entrees("hop hop" to "OpOp", "absent" to "x"),
        ) { entree, occurrences -> vues += entree.id to occurrences }
        assertEquals("OpOp puis OpOp et rien d'autre", resultat)
        assertEquals(listOf(1L to 2), vues)
    }

    @Test
    fun `la meme liste d entrees est compilee une fois puis reutilisee`() {
        // Deux listes égales mais distinctes sont deux compilations; la même
        // instance, appelée deux fois, n'en est qu'une — le résultat ne doit
        // pas dépendre du cache.
        val liste = entrees("hop hop" to "OpOp")
        assertEquals("OpOp", DictionnaireSubstitution.appliquer("hop hop", liste))
        assertEquals("OpOp !", DictionnaireSubstitution.appliquer("Hop hop !", liste))
        assertEquals("OpOp", DictionnaireSubstitution.appliquer("hop hop", entrees("hop hop" to "OpOp")))
    }

    // ------------------------------------------------------------------
    // Vocabulaire soufflé au modèle distant
    // ------------------------------------------------------------------

    @Test
    fun `les cibles de substitution ne sont plus soufflees au modele`() {
        // « OpOp » est garanti par la substitution : le souffler en plus ne
        // ferait que l'exposer aux recopies hors contexte.
        assertEquals(
            "",
            DictionnaireSubstitution.vocabulairePourBiais(entrees("hop hop" to "OpOp"), 600),
        )
    }

    @Test
    fun `seules les entrees de vocabulaire forment la liste`() {
        assertEquals(
            "Chuchote Flow, Rosemarie Records",
            DictionnaireSubstitution.vocabulairePourBiais(
                entrees(
                    "Chuchote Flow" to "",
                    "hop hop" to "OpOp",
                    "Rosemarie Records" to "",
                    "chuchote flow" to "", // doublon à la casse près
                    "   " to "",
                ),
                600,
            ),
        )
    }

    @Test
    fun `la borne de caracteres laisse un mot de cote plutot que de le couper`() {
        assertEquals(
            "Alpha, Bravo",
            DictionnaireSubstitution.vocabulairePourBiais(
                entrees("Alpha" to "", "Bravo" to "", "Charlie" to ""),
                "Alpha, Bravo".length,
            ),
        )
        assertEquals("", DictionnaireSubstitution.vocabulairePourBiais(entrees("Alpha" to ""), 2))
    }
}
