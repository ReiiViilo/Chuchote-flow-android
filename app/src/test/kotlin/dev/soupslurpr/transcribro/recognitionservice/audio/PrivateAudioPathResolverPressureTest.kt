package dev.soupslurpr.transcribro.recognitionservice.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

class PrivateAudioPathResolverPressureTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `slash et backslash ne permettent aucun traversal`() {
        val roots = roots("traversal")
        val resolver = roots.resolver()
        val attempts = listOf(
            "no_backup/dictations/../escape.wav",
            "no_backup/dictations/./inside.wav",
            "no_backup/dictations/sub/../../escape.wav",
            "no_backup/dictations/sub\\..\\escape.wav",
            "files/dictations/../escape.wav",
            "files/dictations/sub\\..\\escape.wav",
            "no_backup\\dictations\\..\\escape.wav",
            File(roots.noBackup, "dictations/sub/../escape.wav").absolutePath,
            File(roots.files, "dictations/sub\\..\\escape.wav").absolutePath,
        )

        attempts.forEach { attempt ->
            assertNull("Traversal accepte: $attempt", resolver.resolve(attempt))
        }
    }

    @Test
    fun `siblings et chemins absolus hors racines sont refuses`() {
        val roots = roots("outside")
        val resolver = roots.resolver()
        val noBackupSibling = File(
            roots.noBackup.parentFile,
            "${roots.noBackup.name}-sibling/dictations/escape.wav",
        )
        val filesSibling = File(
            roots.files.parentFile,
            "${roots.files.name}-sibling/dictations/escape.wav",
        )
        val outside = File(temporaryFolder.root, "outside/escape.wav")

        listOf(noBackupSibling, filesSibling, outside).forEach { attempt ->
            assertNull(attempt.path, resolver.resolve(attempt.absolutePath))
            assertThrows(IllegalArgumentException::class.java) {
                resolver.requirePrivateWav(attempt)
            }
        }
    }

    @Test
    fun `seule extension wav sans sensibilite a la casse est admise`() {
        val roots = roots("extensions")
        val resolver = roots.resolver()

        listOf("voix.wav", "voix.WAV", "voix.WaV").forEach { name ->
            val candidate = File(roots.noBackup, "dictations/$name")
            assertEquals(candidate.canonicalFile, resolver.resolve(candidate.absolutePath))
            assertEquals(candidate.canonicalFile, resolver.requirePrivateWav(candidate))
        }

        listOf("voix.mp3", "voix.wave", "voix.wav.part", "voix").forEach { name ->
            val candidate = File(roots.files, "dictations/$name")
            assertNull(name, resolver.resolve(candidate.absolutePath))
            assertThrows(IllegalArgumentException::class.java) {
                resolver.requirePrivateWav(candidate)
            }
        }
    }

    @Test
    fun `references typees restent bornees apres deplacement des racines`() {
        val oldRoots = roots("typed-old")
        val movedRoots = roots("typed-moved")
        val moved = movedRoots.resolver()

        assertEquals(
            File(movedRoots.noBackup, "dictations/nested/current.wav").canonicalFile,
            moved.resolve("no_backup/dictations/nested/current.wav"),
        )
        assertEquals(
            File(movedRoots.files, "dictations/nested/legacy.WAV").canonicalFile,
            moved.resolve("files/dictations/nested/legacy.WAV"),
        )
        assertNull(moved.resolve("dictations/ambiguous.wav"))
        assertNull(moved.resolve("unknown/dictations/ambiguous.wav"))
        assertNull(moved.resolve("NO_BACKUP/dictations/case-sensitive.wav"))
        assertNull(moved.resolve(File(oldRoots.noBackup, "dictations/old.wav").absolutePath))
    }

    @Test
    fun `racines identiques ou imbriquees ne permettent pas de remonter`() {
        val sharedBase = temporaryFolder.newFolder("collision-shared")
        val sameRootResolver = PrivateAudioPathResolver(sharedBase, sharedBase)
        val sameRootAudio = File(sharedBase, "dictations/same.wav")
        assertEquals(sameRootAudio.canonicalFile, sameRootResolver.resolve("files/dictations/same.wav"))

        val outer = temporaryFolder.newFolder("nested-outer")
        val innerFiles = File(outer, "dictations/inner-files").apply { mkdirs() }
        val nestedResolver = PrivateAudioPathResolver(outer, innerFiles)
        val nestedAudio = File(innerFiles, "dictations/nested.wav")
        assertEquals(nestedAudio.canonicalFile, nestedResolver.resolve("files/dictations/nested.wav"))
        assertNull(nestedResolver.resolve("files/dictations/../../../escape.wav"))
        assertNull(nestedResolver.resolve(File(outer.parentFile, "escape.wav").absolutePath))
    }

    @Test
    fun `un wav inexistant est resolu seulement sous une racine privee`() {
        val roots = roots("missing")
        val resolver = roots.resolver()
        val missingInside = File(roots.noBackup, "dictations/missing.wav")
        val missingOutside = File(temporaryFolder.root, "missing-outside.wav")

        assertEquals(missingInside.canonicalFile, resolver.resolve(missingInside.absolutePath))
        assertEquals(
            missingInside.canonicalFile,
            resolver.resolve("no_backup/dictations/missing.wav"),
        )
        assertNull(resolver.resolve(missingOutside.absolutePath))
    }

    @Test
    fun `exception de canonicalisation est contenue mais OOM remonte`() {
        val roots = roots("canonical-errors")
        val resolver = roots.resolver()

        assertNull(resolver.resolve("no_backup/dictations/invalid\u0000.wav"))

        val ioFailure = object : File("synthetic.wav") {
            override fun getCanonicalFile(): File = throw IOException("canonical failure")
        }
        assertThrows(IOException::class.java) {
            resolver.requirePrivateWav(ioFailure)
        }

        val oomFailure = object : File("synthetic.wav") {
            override fun getCanonicalFile(): File = throw OutOfMemoryError("canonical OOM")
        }
        assertThrows(OutOfMemoryError::class.java) {
            resolver.requirePrivateWav(oomFailure)
        }
    }

    @Test
    fun `une cible canonique exterieure est toujours refusee sans dependre des symlinks`() {
        val noBackup = temporaryFolder.newFolder("redirect-no-backup")
        val files = temporaryFolder.newFolder("redirect-files")
        val outside = File(temporaryFolder.newFolder("redirect-outside"), "escaped.wav")
            .canonicalFile
        val lexicalCandidate = File(noBackup, "dictations/link/escaped.wav").absoluteFile
        val resolver = PrivateAudioPathResolver(
            noBackupFilesDir = noBackup,
            filesDir = files,
            canonicalize = { candidate ->
                if (candidate.name == "escaped.wav" && candidate.parentFile?.name == "link") {
                    outside
                } else {
                    candidate.canonicalFile
                }
            },
        )

        assertNull(resolver.resolve(lexicalCandidate.path))
        assertNull(resolver.resolve("no_backup/dictations/link/escaped.wav"))
        assertThrows(IllegalArgumentException::class.java) {
            resolver.requirePrivateWav(lexicalCandidate)
        }
    }

    @Test
    fun `un symlink enfant ne peut pas sortir de la racine si la plateforme le permet`() {
        val roots = roots("symlink")
        val resolver = roots.resolver()
        val audioDirectory = File(roots.noBackup, "dictations").apply { mkdirs() }
        val outsideDirectory = temporaryFolder.newFolder("symlink-outside")
        val link = File(audioDirectory, "escape-link")

        try {
            Files.createSymbolicLink(link.toPath(), outsideDirectory.toPath())
        } catch (failure: Exception) {
            assumeNoException("Symlink non disponible dans cet environnement", failure)
        }

        val escaped = File(link, "escaped.wav")
        assertNull(resolver.resolve(escaped.absolutePath))
        assertNull(resolver.resolve("no_backup/dictations/escape-link/escaped.wav"))
        assertThrows(IllegalArgumentException::class.java) {
            resolver.requirePrivateWav(escaped)
        }
    }

    private fun roots(name: String): TestRoots = TestRoots(
        noBackup = temporaryFolder.newFolder("$name-no-backup"),
        files = temporaryFolder.newFolder("$name-files"),
    )

    private data class TestRoots(
        val noBackup: File,
        val files: File,
    ) {
        fun resolver(): PrivateAudioPathResolver = PrivateAudioPathResolver(noBackup, files)
    }
}
