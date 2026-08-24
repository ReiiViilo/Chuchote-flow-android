package dev.soupslurpr.transcribro.recognitionservice.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HistoricalAudioRehabilitationTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `un wav historique valide repare erreur et duree sans changer le fichier`() {
        val noBackup = temporaryFolder.newFolder("no_backup")
        val files = temporaryFolder.newFolder("files")
        val audio = File(files, "dictations/historique.wav")
        val writer = RecoverableWavFile.create(audio, sampleRate = 16_000)
        writer.append(ShortArray(16_000) { it.toShort() })
        writer.finalizeRecording()
        val bytesBefore = audio.readBytes()
        val modifiedBefore = audio.lastModified()
        val resolver = PrivateAudioPathResolver(noBackup, files)

        val repair = HistoricalAudioRehabilitation.evaluate(
            errorCode = "audio_missing",
            persistedPath = audio.absolutePath,
            resolver = resolver,
        )

        assertNotNull(repair)
        assertEquals(1_000L, repair?.durationMs)
        assertEquals(bytesBefore.toList(), audio.readBytes().toList())
        assertEquals(modifiedBefore, audio.lastModified())
    }

    @Test
    fun `seuls les deux faux diagnostics d absence sont rehabilites`() {
        val noBackup = temporaryFolder.newFolder("errors-no-backup")
        val files = temporaryFolder.newFolder("errors-files")
        val audio = File(noBackup, "dictations/valid.wav")
        val resolver = PrivateAudioPathResolver(noBackup, files)
        val inspect: (File) -> WavInfo = {
            WavInfo(sampleRate = 16_000, totalSamples = 8_000, dataSizeBytes = 16_000)
        }

        assertEquals(
            500L,
            HistoricalAudioRehabilitation.evaluate(
                "retry_audio_missing",
                audio.absolutePath,
                resolver,
                inspectFile = inspect,
            )?.durationMs,
        )
        assertNull(
            HistoricalAudioRehabilitation.evaluate(
                "retry_audio_invalid",
                audio.absolutePath,
                resolver,
                inspectFile = inspect,
            ),
        )
        assertNull(
            HistoricalAudioRehabilitation.evaluate(
                "process_interrupted",
                audio.absolutePath,
                resolver,
                inspectFile = inspect,
            ),
        )
    }

    @Test
    fun `un wav absent corrompu ou incompatible ne change aucun diagnostic`() {
        val noBackup = temporaryFolder.newFolder("invalid-no-backup")
        val files = temporaryFolder.newFolder("invalid-files")
        val resolver = PrivateAudioPathResolver(noBackup, files)
        val missing = File(files, "dictations/missing.wav")
        val corrupt = File(files, "dictations/corrupt.wav").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }

        assertNull(
            HistoricalAudioRehabilitation.evaluate(
                "audio_missing",
                missing.absolutePath,
                resolver,
            ),
        )
        assertNull(
            HistoricalAudioRehabilitation.evaluate(
                "audio_missing",
                corrupt.absolutePath,
                resolver,
            ),
        )
        assertNull(
            HistoricalAudioRehabilitation.evaluate(
                "audio_missing",
                corrupt.absolutePath,
                resolver,
                inspectFile = {
                    WavInfo(sampleRate = 48_000, totalSamples = 48_000, dataSizeBytes = 96_000)
                },
            ),
        )
    }

    @Test
    fun `un manque de memoire pendant l inspection n est pas maquille`() {
        val noBackup = temporaryFolder.newFolder("oom-no-backup")
        val files = temporaryFolder.newFolder("oom-files")
        val audio = File(files, "dictations/oom.wav")
        val resolver = PrivateAudioPathResolver(noBackup, files)

        assertThrows(OutOfMemoryError::class.java) {
            HistoricalAudioRehabilitation.evaluate(
                "audio_missing",
                audio.absolutePath,
                resolver,
                inspectFile = { throw OutOfMemoryError("memoire") },
            )
        }
    }
}
