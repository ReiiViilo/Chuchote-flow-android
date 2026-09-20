package dev.soupslurpr.transcribro.remote

/**
 * Les délais du transport vers `api/sync/` — les mêmes pour les dictées et
 * le dictionnaire, possédés ici plutôt que par l'une des deux politiques.
 */
internal object SyncTimeouts {
    const val CONNECT_TIMEOUT_MS = 10_000

    /**
     * Ce que le relais s'accorde par requête (`maxDuration = 30` sur les
     * routes `api/sync/` de `Chuchote-Flow/server`) : au-delà, la plateforme
     * tue la fonction et l'écriture n'aura pas lieu.
     */
    const val RELAY_MAX_DURATION_MS = 30_000

    /**
     * Ce qui, autour du budget du relais, n'est pas compté dedans : le budget
     * ne court qu'à l'entrée dans la fonction, le délai de lecture dès l'envoi
     * du corps. Entre les deux, le démarrage à froid de la plateforme, le
     * routage et TLS; après, le retour de la réponse. Un démarrage à froid de
     * plusieurs secondes suivi d'une fonction qui consomme tout son budget
     * dépasserait une marge de quelques secondes.
     */
    const val COLD_START_MARGIN_MS = 15_000

    /**
     * Tout envoi vers le relais attend **au-delà** de [RELAY_MAX_DURATION_MS]
     * plus [COLD_START_MARGIN_MS] — une seule entrée, une dictée, un lot : un
     * délai côté client signifie alors que le relais a lui aussi renoncé.
     * Avec un délai plus court, un ajout expiré ici pourrait encore s'écrire
     * là-bas après la pierre tombale envoyée ensuite, et ressusciter le mot :
     * le fil unique ordonne les requêtes, pas les écritures d'un relais qui
     * répond en retard. Le délai n'est payé que sur un relais muet, et une
     * série s'arrête au premier échec.
     *
     * Ce qu'aucun délai ne ferme : une requête SQL déjà partie quand la
     * fonction est tuée, et une panne de transport après l'envoi du corps
     * (connexion coupée avant la réponse), tenue pour un échec aussitôt alors
     * que le relais peut encore écrire. Versions de mutation côté relais,
     * tranche 1 (D-006, décision d'Olivier du 19 septembre 2026).
     */
    const val READ_TIMEOUT_MS = RELAY_MAX_DURATION_MS + COLD_START_MARGIN_MS

    /** Un lot de 500 entrées : même règle. */
    const val BATCH_READ_TIMEOUT_MS = RELAY_MAX_DURATION_MS + COLD_START_MARGIN_MS
}
