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
    fun `inspect rejette tout format autre que pcm entier`() {
        val finalFile = validWav("format-non-pcm.wav")
        writeLittleEndianShort(finalFile, offset = 20, value = 3)

        assertThrows(IllegalArgumentException::class.java) {
            RecoverableWavFile.inspect(finalFile)
        }
    }

    @Test
    fun `inspect exige les chunks fmt et data canoniques`() {
        val invalidFmt = validWav("chunk-fmt.wav")
        writeAscii(invalidFmt, offset = 12, value = "JUNK")
        val invalidFmtSize = validWav("taille-fmt.wav")
        writeLittleEndianInt(invalidFmtSize, offset = 16, value = 18)
        val invalidData = validWav("chunk-data.wav")
        writeAscii(invalidData, offset = 36, value = "JUNK")

        listOf(invalidFmt, invalidFmtSize, invalidData).forEach { file ->
            assertThrows(file.name, IllegalArgumentException::class.java) {
                RecoverableWavFile.inspect(file)
            }
        }
    }

    @Test
    fun `inspect exige un alignement et un debit pcm coherents`() {
        val invalidAlignment = validWav("alignement.wav")
        writeLittleEndianShort(invalidAlignment, offset = 32, value = 4)
        val invalidByteRate = validWav("debit.wav")
        writeLittleEndianInt(invalidByteRate, offset = 28, value = 123)

        listOf(invalidAlignment, invalidByteRate).forEach { file ->
            assertThrows(file.name, IllegalArgumentException::class.java) {
                RecoverableWavFile.inspect(file)
            }
        }
    }

    @Test
    fun `inspect exige des tailles riff et data egales aux octets reels`() {
        val invalidRiffSize = validWav("taille-riff.wav")
        writeLittleEndianInt(invalidRiffSize, offset = 4, value = 36)
        val invalidDataSize = validWav("taille-data.wav")
        writeLittleEndianInt(invalidDataSize, offset = 40, value = 0)
        val trailingByte = validWav("octet-orphelin.wav").apply {
            appendBytes(byteArrayOf(7))
        }

        listOf(invalidRiffSize, invalidDataSize, trailingByte).forEach { file ->
            assertThrows(file.name, IllegalArgumentException::class.java) {
                RecoverableWavFile.inspect(file)
            }
        }
    }

    @Test
    fun `inspect accepte le wav pcm canonique vide`() {
        val finalFile = validWav("vide.wav", samples = shortArrayOf())

        val info = RecoverableWavFile.inspect(finalFile)

        assertEquals(0L, info.totalSamples)
        assertEquals(0L, info.dataSizeBytes)
    }

    @Test
    fun `la limite du payload garantit que la taille riff tient sur 32 bits`() {
        val largestEvenPayload = (UInt.MAX_VALUE.toLong() - 36L) and -2L

        assertEquals(0L, requireCanonicalWavDataSize(0L))
        assertEquals(largestEvenPayload, requireCanonicalWavDataSize(largestEvenPayload))
        assertThrows(IllegalArgumentException::class.java) {
            requireCanonicalWavDataSize(largestEvenPayload + 2L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireCanonicalWavDataSize(-2L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireCanonicalWavDataSize(3L)
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

    private fun validWav(
        name: String,
        samples: ShortArray = shortArrayOf(1, 2, 3, 4),
    ): File {
        val finalFile = File(temporaryFolder.root, name)
        RecoverableWavFile.create(finalFile, sampleRate = 16_000).use { writer ->
            writer.append(samples)
            writer.finalizeRecording()
        }
        return finalFile
    }

    private fun writeAscii(file: File, offset: Long, value: String) {
        RandomAccessFile(file, "rw").use { randomAccess ->
            randomAccess.seek(offset)
            randomAccess.write(value.toByteArray(Charsets.US_ASCII))
        }
    }

    private fun writeLittleEndianInt(file: File, offset: Long, value: Long) {
        RandomAccessFile(file, "rw").use { randomAccess ->
            randomAccess.seek(offset)
            repeat(4) { byteIndex ->
                randomAccess.write(((value ushr (byteIndex * 8)) and 0xFF).toInt())
            }
        }
    }

    private fun writeLittleEndianShort(file: File, offset: Long, value: Int) {
        RandomAccessFile(file, "rw").use { randomAccess ->
            randomAccess.seek(offset)
            randomAccess.write(value and 0xFF)
            randomAccess.write((value ushr 8) and 0xFF)
        }
    }
}
