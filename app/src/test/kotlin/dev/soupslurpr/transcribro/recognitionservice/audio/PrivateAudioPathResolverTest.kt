package dev.soupslurpr.transcribro.recognitionservice.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PrivateAudioPathResolverTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `une reference relative typee reste resoluble apres deplacement des racines`() {
        val noBackup = temporaryFolder.newFolder("no_backup")
        val files = temporaryFolder.newFolder("files")
        val audio = File(noBackup, "dictations/nouveau.wav").apply {
            parentFile?.mkdirs()
        }
        val resolver = PrivateAudioPathResolver(noBackup, files)

        val persisted = "no_backup/dictations/nouveau.wav"
        val relocatedNoBackup = temporaryFolder.newFolder("relocated", "no_backup")
        val relocatedFiles = temporaryFolder.newFolder("relocated-files")
        val relocatedResolver = PrivateAudioPathResolver(relocatedNoBackup, relocatedFiles)

        assertEquals("no_backup/dictations/nouveau.wav", persisted)
        assertEquals(audio.canonicalFile, resolver.resolve(persisted))
        assertEquals(
            File(relocatedNoBackup, "dictations/nouveau.wav").canonicalFile,
            relocatedResolver.resolve(persisted),
        )
    }

    @Test
    fun `les chemins absolus historiques des deux racines privees restent lisibles`() {
        val noBackup = temporaryFolder.newFolder("historical-no-backup")
        val files = temporaryFolder.newFolder("historical-files")
        val current = File(noBackup, "dictations/current.wav").apply {
            parentFile?.mkdirs()
        }
        val legacy = File(files, "dictations/legacy.wav").apply {
            parentFile?.mkdirs()
        }
        val resolver = PrivateAudioPathResolver(noBackup, files)

        assertEquals(current.canonicalFile, resolver.resolve(current.absolutePath))
        assertEquals(legacy.canonicalFile, resolver.resolve(legacy.absolutePath))
        assertEquals(legacy.canonicalFile, resolver.resolve("files/dictations/legacy.wav"))
    }

    @Test
    fun `les references ambigues ou hors stockage audio prive sont refusees`() {
        val noBackup = temporaryFolder.newFolder("safe-no-backup")
        val files = temporaryFolder.newFolder("safe-files")
        val resolver = PrivateAudioPathResolver(noBackup, files)
        val prefixSibling = File(
            noBackup.parentFile,
            "${noBackup.name}-sibling/dictations/escape.wav",
        )
        val outside = File(temporaryFolder.root, "outside.wav")

        assertNull(resolver.resolve("no_backup/dictations/sub/../inside.wav"))
        assertNull(
            resolver.resolve(
                File(noBackup, "dictations/sub/../inside.wav").absolutePath,
            ),
        )
        assertNull(resolver.resolve(prefixSibling.absolutePath))
        assertNull(resolver.resolve(outside.absolutePath))
        assertNull(resolver.resolve("dictations/relative.wav"))
        assertNull(resolver.resolve("no_backup/dictations/not-a-wav.mp3"))
        assertThrows(IllegalArgumentException::class.java) {
            resolver.requirePrivateWav(outside)
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolver.requirePrivateWav(File(noBackup, "dictations/not-a-wav.mp3"))
        }
    }
}
