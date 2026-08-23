package dev.soupslurpr.transcribro.recognitionservice.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RecoverableWavFileTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `finaliser produit un wav valide lisible par plages`() {
        val finalFile = File(temporaryFolder.root, "dictee.wav")
        val writer = RecoverableWavFile.create(finalFile, sampleRate = 16_000)
        writer.append(shortArrayOf(-32_768, -12, 0, 12, 32_767), count = 5)

        val completed = writer.finalizeRecording()
        val info = RecoverableWavFile.inspect(completed)

        assertEquals(finalFile.canonicalFile, completed.canonicalFile)
        assertFalse(File("${finalFile.path}.part").exists())
        assertEquals(16_000, info.sampleRate)
        assertEquals(5, info.totalSamples)
        assertEquals(10, info.dataSizeBytes)
        assertArrayEquals(
            shortArrayOf(-12, 0, 12),
            RecoverableWavFile.readSamples(completed, AudioSegment(1, 4)),
        )
    }

    @Test
    fun `un fichier partiel est repare apres interruption de processus`() {
        val finalFile = File(temporaryFolder.root, "interrompue.wav")
        val writer = RecoverableWavFile.create(finalFile, sampleRate = 16_000)
        writer.append(shortArrayOf(1, 2, 3, 4), count = 4)
        writer.closeWithoutFinalizingForTest()

        val partFile = File("${finalFile.path}.part")
        assertTrue(partFile.exists())

        val recovered = RecoverableWavFile.recoverIfNeeded(finalFile)

        assertEquals(finalFile.canonicalFile, recovered?.canonicalFile)
        assertFalse(partFile.exists())
        assertEquals(4, RecoverableWavFile.inspect(finalFile).totalSamples)
        assertArrayEquals(
            shortArrayOf(1, 2, 3, 4),
            RecoverableWavFile.readSamples(finalFile, AudioSegment(0, 4)),
        )
    }

    @Test
    fun `la taille wav est corrigee depuis les octets reellement presents`() {
        val finalFile = File(temporaryFolder.root, "header-incomplet.wav")
        val writer = RecoverableWavFile.create(finalFile, sampleRate = 16_000)
        writer.append(shortArrayOf(9, 8, 7), count = 3)
        writer.closeWithoutFinalizingForTest()

        val partFile = File("${finalFile.path}.part")
        RandomAccessFile(partFile, "rw").use { file ->
            file.seek(4)
            file.writeInt(0)
            file.seek(40)
            file.writeInt(0)
        }

        RecoverableWavFile.recoverIfNeeded(finalFile)

        assertEquals(3, RecoverableWavFile.inspect(finalFile).totalSamples)
    }

    @Test
    fun `des reprises concurrentes convergent vers le meme wav final`() {
        val finalFile = File(temporaryFolder.root, "concurrente.wav")
        val writer = RecoverableWavFile.create(finalFile, sampleRate = 16_000)
        writer.append(shortArrayOf(1, 2, 3, 4), count = 4)
        writer.closeWithoutFinalizingForTest()

        val workers = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val recovered = (1..8).map {
                workers.submit<File?> {
                    start.await()
                    RecoverableWavFile.recoverIfNeeded(finalFile)
                }
            }
            start.countDown()

            recovered.forEach { future ->
                assertEquals(finalFile.canonicalFile, future.get(5, TimeUnit.SECONDS)?.canonicalFile)
            }
        } finally {
            workers.shutdownNow()
        }

        assertFalse(File("${finalFile.path}.part").exists())
        assertEquals(4, RecoverableWavFile.inspect(finalFile).totalSamples)
    }

    @Test
    fun `inspect rejette une frequence nulle`() {
        val finalFile = File(temporaryFolder.root, "frequence-nulle.wav")
        RecoverableWavFile.create(finalFile, sampleRate = 16_000).use { writer ->
            writer.append(shortArrayOf(7), count = 1)
            writer.finalizeRecording()
        }
        RandomAccessFile(finalFile, "rw").use { file ->
            file.seek(24)
            repeat(4) { file.write(0) }
        }

        assertThrows(IllegalArgumentException::class.java) {
            RecoverableWavFile.inspect(finalFile)
        }
    }

    @Test
    fun `une initialisation wav qui echoue ferme le handle et preserve la cause`() {
        var closed = false
        val initializationFailure = IOException("header")
        val resource = java.io.Closeable {
            closed = true
            throw IOException("close")
        }

        val thrown = assertThrows(IOException::class.java) {
            OwnedResourceInitialization.initialize(resource) {
                throw initializationFailure
            }
        }

        assertTrue(closed)
        assertSame(initializationFailure, thrown)
        assertEquals("close", thrown.suppressed.single().message)
    }
}
