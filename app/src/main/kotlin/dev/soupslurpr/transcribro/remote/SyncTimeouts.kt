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
     * Tout envoi vers le relais attend **au-delà** de [RELAY_MAX_DURATION_MS]
     * — une seule entrée, une dictée, un lot : un délai côté client signifie
     * alors que le relais a lui aussi renoncé. Avec un délai plus court, un
     * ajout expiré ici pourrait encore s'écrire là-bas après la pierre
     * tombale envoyée ensuite, et ressusciter le mot : le fil unique ordonne
     * les requêtes, pas les écritures d'un relais qui répond en retard. Reste
     * la fenêtre d'une requête SQL déjà partie quand la fonction est tuée —
     * quelques millisecondes, à fermer un jour par des versions de mutation
     * côté relais (décision ouverte).
     */
    const val READ_TIMEOUT_MS = RELAY_MAX_DURATION_MS + 5_000

    /** Un lot de 500 entrées : même règle. */
    const val BATCH_READ_TIMEOUT_MS = RELAY_MAX_DURATION_MS + 5_000
}
