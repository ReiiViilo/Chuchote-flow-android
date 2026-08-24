package dev.soupslurpr.transcribro.recognitionservice.audio

import java.io.File

/**
 * Traduit les références SQLite stables vers les deux emplacements audio
 * privés connus de l'application, sans jamais élargir la frontière à tout le
 * stockage interne.
 */
internal class PrivateAudioPathResolver(
    noBackupFilesDir: File,
    filesDir: File,
    private val canonicalize: (File) -> File = { it.canonicalFile },
) {
    private data class PrivateRoot(
        val persistedPrefix: String,
        val directory: File,
    )

    private val roots = listOf(
        PrivateRoot(
            persistedPrefix = NO_BACKUP_PREFIX,
            directory = canonicalize(File(noBackupFilesDir, AUDIO_DIRECTORY)),
        ),
        PrivateRoot(
            persistedPrefix = FILES_PREFIX,
            directory = canonicalize(File(filesDir, AUDIO_DIRECTORY)),
        ),
    )

    val preferredDirectory: File
        get() = roots.first().directory

    fun resolve(persistedPath: String?): File? {
        if (persistedPath.isNullOrBlank()) return null
        if (containsTraversalSegment(persistedPath)) return null
        return try {
            val relativeRoot = roots.firstOrNull { root ->
                persistedPath.startsWith("${root.persistedPrefix}/$AUDIO_DIRECTORY/")
            }
            val candidate = if (relativeRoot != null) {
                val prefix = "${relativeRoot.persistedPrefix}/$AUDIO_DIRECTORY/"
                canonicalize(File(relativeRoot.directory, persistedPath.removePrefix(prefix)))
            } else {
                val historical = File(persistedPath)
                if (!historical.isAbsolute) return null
                canonicalize(historical)
            }
            candidate.takeIf { rootContaining(it) != null }
        } catch (_: Exception) {
            null
        }
    }

    fun requirePrivateWav(file: File): File {
        require(!containsTraversalSegment(file.path)) {
            "Le chemin audio ne doit contenir aucun segment de traversal"
        }
        val canonical = canonicalize(file)
        require(rootContaining(canonical) != null) {
            "Le fichier audio doit rester dans le stockage privé de Chuchote Flow"
        }
        return canonical
    }

    private fun rootContaining(file: File): PrivateRoot? {
        if (!file.extension.equals("wav", ignoreCase = true)) return null
        val path = file.toPath()
        return roots.firstOrNull { root ->
            path != root.directory.toPath() && path.startsWith(root.directory.toPath())
        }
    }

    private fun containsTraversalSegment(path: String): Boolean =
        path.split('/', '\\').any { it == "." || it == ".." }

    private companion object {
        const val AUDIO_DIRECTORY = "dictations"
        const val NO_BACKUP_PREFIX = "no_backup"
        const val FILES_PREFIX = "files"
    }
}

/** Mutation SQLite minimale permise lorsqu'un ancien WAV est retrouvé. */
internal data class HistoricalAudioRepair(
    val durationMs: Long,
)

/**
 * Réévalue uniquement les faux diagnostics d'absence audio connus. L'inspection
 * est en lecture seule : la récupération des fichiers `.part` ne fait pas
 * partie de cette compatibilité historique.
 */
internal object HistoricalAudioRehabilitation {
    private val repairableErrors = setOf("audio_missing", "retry_audio_missing")

    fun evaluate(
        errorCode: String?,
        persistedPath: String?,
        resolver: PrivateAudioPathResolver,
        expectedSampleRate: Int = 16_000,
        inspectFile: (File) -> WavInfo = { RecoverableWavFile.inspect(it) },
    ): HistoricalAudioRepair? {
        if (errorCode !in repairableErrors) return null
        val audio = resolver.resolve(persistedPath) ?: return null
        val info = try {
            inspectFile(audio)
        } catch (_: Exception) {
            return null
        }
        if (info.sampleRate != expectedSampleRate || info.totalSamples <= 0) return null
        return HistoricalAudioRepair(
            durationMs = info.totalSamples * 1_000L / info.sampleRate,
        )
    }
}
