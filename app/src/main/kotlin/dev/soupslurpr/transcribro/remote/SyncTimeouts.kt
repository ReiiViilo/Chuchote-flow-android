package dev.soupslurpr.transcribro.remote

/**
 * Les délais du transport vers `api/sync/` — les mêmes pour les dictées et
 * le dictionnaire, possédés ici plutôt que par l'une des deux politiques.
 */
internal object SyncTimeouts {
    const val CONNECT_TIMEOUT_MS = 10_000

    /** Un envoi d'une seule entrée ou d'une dictée. */
    const val READ_TIMEOUT_MS = 15_000

    /** Un lot de 500 entrées : le relais s'accorde 30 s (`maxDuration`); on attend un peu au-delà. */
    const val BATCH_READ_TIMEOUT_MS = 35_000
}
