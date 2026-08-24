package dev.soupslurpr.transcribro.overlay

/**
 * Les commandes Binder de SpeechRecognizer peuvent lever quand la connexion
 * disparaît. Elles restent donc confinées et les nettoyages indépendants.
 */
internal object RecognizerCommandBoundary {
    fun execute(
        command: () -> Unit,
        onFailure: (Exception) -> Unit = {},
    ): Boolean = try {
        command()
        true
    } catch (error: Exception) {
        try {
            onFailure(error)
        } catch (_: Exception) {
            // La récupération d'interface ne doit pas sortir du callback.
        }
        false
    }

    fun cleanup(
        cancel: () -> Unit,
        destroy: () -> Unit,
    ) {
        execute(cancel)
        execute(destroy)
    }
}
