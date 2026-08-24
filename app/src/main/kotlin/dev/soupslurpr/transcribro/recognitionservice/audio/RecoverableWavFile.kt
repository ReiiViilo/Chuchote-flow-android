package dev.soupslurpr.transcribro.recognitionservice.audio

import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Transfère une ressource au propriétaire final ou la ferme sur tout échec. */
internal object OwnedResourceInitialization {
    fun <T : Closeable, R> initialize(
        resource: T,
        initialize: (T) -> R,
    ): R = try {
        initialize(resource)
    } catch (failure: Throwable) {
        try {
            resource.close()
        } catch (closeFailure: Throwable) {
            if (closeFailure !== failure) failure.addSuppressed(closeFailure)
        }
        throw failure
    }
}

internal data class WavInfo(
    val sampleRate: Int,
    val totalSamples: Long,
    val dataSizeBytes: Long,
)

private val MAX_CANONICAL_WAV_DATA_SIZE =
    (UInt.MAX_VALUE.toLong() - 36L) and -2L

/** Garantit que `36 + dataSize` reste représentable dans l'en-tête RIFF. */
internal fun requireCanonicalWavDataSize(dataSize: Long): Long {
    require(dataSize >= 0L && dataSize % 2L == 0L) {
        "Le payload PCM doit être positif et aligné sur 16 bits"
    }
    require(dataSize <= MAX_CANONICAL_WAV_DATA_SIZE) {
        "Le payload WAV dépasse la capacité RIFF 32 bits"
    }
    return dataSize
}

/**
 * WAV écrit progressivement dans `<nom>.wav.part`.
 *
 * L'en-tête existe dès le premier octet audio. Une fin normale le corrige et
 * renomme atomiquement le fichier; après une mort de processus,
 * [recoverIfNeeded] reconstruit les tailles depuis les octets réellement
 * présents. Aucun tampon audio complet ne réside dans le tas Java.
 */
internal class RecoverableWavFile private constructor(
    private val finalFile: File,
    private val sampleRate: Int,
    private val partFile: File,
    private var file: RandomAccessFile?,
) : Closeable {

    val samplesWritten: Long
        get() = ((file?.length() ?: partFile.length()) - HEADER_SIZE)
            .coerceAtLeast(0) / BYTES_PER_SAMPLE

    fun append(samples: ShortArray, count: Int = samples.size) {
        require(count in 0..samples.size) { "Nombre d'échantillons invalide: $count" }
        if (count == 0) return
        val target = checkNotNull(file) { "Enregistrement déjà fermé" }
        val bytes = ByteArray(count * BYTES_PER_SAMPLE.toInt())
        var outputIndex = 0
        for (index in 0 until count) {
            val value = samples[index].toInt()
            bytes[outputIndex++] = (value and 0xFF).toByte()
            bytes[outputIndex++] = ((value ushr 8) and 0xFF).toByte()
        }
        target.write(bytes)
    }

    fun finalizeRecording(): File {
        if (finalFile.exists() && !partFile.exists()) {
            closeHandle()
            return finalFile
        }
        val target = checkNotNull(file) { "Enregistrement déjà fermé sans fichier final" }
        patchHeader(target, sampleRate)
        target.fd.sync()
        closeHandle()
        moveAtomically(partFile, finalFile)
        return finalFile
    }

    override fun close() {
        closeHandle()
    }

    /** Simule une mort de processus dans les tests : aucun en-tête corrigé. */
    internal fun closeWithoutFinalizingForTest() {
        closeHandle()
    }

    private fun closeHandle() {
        file?.close()
        file = null
    }

    companion object {
        const val DEFAULT_MAX_READ_SAMPLES = 480_000
        private const val HEADER_SIZE = 44L
        private const val BYTES_PER_SAMPLE = 2L
        private val recoveryLock = Any()

        fun create(finalFile: File, sampleRate: Int): RecoverableWavFile {
            require(sampleRate > 0) { "Fréquence invalide" }
            finalFile.parentFile?.mkdirs()
            val partFile = File("${finalFile.path}.part")
            require(!finalFile.exists()) { "Le WAV final existe déjà: ${finalFile.name}" }
            require(!partFile.exists()) { "Le WAV partiel existe déjà: ${partFile.name}" }

            val randomAccess = RandomAccessFile(partFile, "rw")
            return OwnedResourceInitialization.initialize(randomAccess) { ownedFile ->
                writeHeader(ownedFile, sampleRate, dataSize = 0)
                ownedFile.seek(HEADER_SIZE)
                RecoverableWavFile(finalFile, sampleRate, partFile, ownedFile)
            }
        }

        fun recoverIfNeeded(finalFile: File): File? = synchronized(recoveryLock) {
            if (finalFile.exists()) return@synchronized finalFile
            val partFile = File("${finalFile.path}.part")
            if (!partFile.exists() || partFile.length() <= HEADER_SIZE) {
                return@synchronized null
            }

            RandomAccessFile(partFile, "rw").use { randomAccess ->
                val sampleRate = readLittleEndianInt(randomAccess, 24)
                require(sampleRate > 0) { "WAV partiel sans fréquence valide" }
                patchHeader(randomAccess, sampleRate)
                randomAccess.fd.sync()
            }
            moveAtomically(partFile, finalFile)
            finalFile
        }

        fun inspect(file: File): WavInfo {
            RandomAccessFile(file, "r").use { randomAccess ->
                val fileSize = randomAccess.length()
                require(fileSize >= HEADER_SIZE) { "Fichier WAV trop court" }
                require(fileSize - 8 <= UInt.MAX_VALUE.toLong()) { "WAV supérieur à 4 Gio" }
                require(readAscii(randomAccess, 0, 4) == "RIFF") { "Signature RIFF absente" }
                require(readAscii(randomAccess, 8, 4) == "WAVE") { "Signature WAVE absente" }
                require(readAscii(randomAccess, 12, 4) == "fmt ") { "Chunk fmt absent" }
                require(readUnsignedLittleEndianInt(randomAccess, 16) == 16L) {
                    "Le chunk fmt PCM canonique doit mesurer 16 octets"
                }
                require(readLittleEndianShort(randomAccess, 20) == 1) {
                    "Le WAV doit utiliser le format PCM entier"
                }

                val channels = readLittleEndianShort(randomAccess, 22)
                require(channels == 1) { "Le WAV doit être mono" }

                val sampleRate = readLittleEndianInt(randomAccess, 24)
                require(sampleRate > 0) { "Le WAV doit avoir une fréquence positive" }
                require(
                    readUnsignedLittleEndianInt(randomAccess, 28) ==
                            sampleRate.toLong() * BYTES_PER_SAMPLE,
                ) { "Débit PCM incohérent" }
                require(readLittleEndianShort(randomAccess, 32).toLong() == BYTES_PER_SAMPLE) {
                    "Alignement PCM incohérent"
                }
                require(readLittleEndianShort(randomAccess, 34) == 16) {
                    "Le WAV doit être PCM 16 bits"
                }
                require(readAscii(randomAccess, 36, 4) == "data") { "Chunk data absent" }

                val actualDataSize = fileSize - HEADER_SIZE
                require(actualDataSize % BYTES_PER_SAMPLE == 0L) {
                    "Le payload PCM 16 bits doit contenir des échantillons complets"
                }
                val declaredDataSize = readUnsignedLittleEndianInt(randomAccess, 40)
                require(declaredDataSize == actualDataSize) {
                    "Taille data incohérente"
                }
                val declaredRiffSize = readUnsignedLittleEndianInt(randomAccess, 4)
                require(declaredRiffSize == fileSize - 8) {
                    "Taille RIFF incohérente"
                }
                return WavInfo(
                    sampleRate = sampleRate,
                    totalSamples = actualDataSize / BYTES_PER_SAMPLE,
                    dataSizeBytes = actualDataSize,
                )
            }
        }

        fun readSamples(
            file: File,
            segment: AudioSegment,
            maxSamples: Int = DEFAULT_MAX_READ_SAMPLES,
        ): ShortArray {
            val info = inspect(file)
            require(segment.startSample >= 0) { "Début de segment négatif" }
            require(segment.endSampleExclusive <= info.totalSamples) { "Segment hors du WAV" }
            require(segment.endSampleExclusive > segment.startSample) { "Segment vide" }
            require(segment.sampleCount <= maxSamples) {
                "Segment trop grand (${segment.sampleCount} > $maxSamples)"
            }

            val count = segment.sampleCount.toInt()
            val bytes = ByteArray(count * BYTES_PER_SAMPLE.toInt())
            RandomAccessFile(file, "r").use { randomAccess ->
                randomAccess.seek(HEADER_SIZE + segment.startSample * BYTES_PER_SAMPLE)
                randomAccess.readFully(bytes)
            }

            return ShortArray(count) { index ->
                val offset = index * 2
                val low = bytes[offset].toInt() and 0xFF
                val high = bytes[offset + 1].toInt() and 0xFF
                ((high shl 8) or low).toShort()
            }
        }

        private fun patchHeader(file: RandomAccessFile, sampleRate: Int) {
            val actualDataSize = (file.length() - HEADER_SIZE)
                .coerceAtLeast(0)
                .let { it - (it % BYTES_PER_SAMPLE) }
            requireCanonicalWavDataSize(actualDataSize)
            file.setLength(HEADER_SIZE + actualDataSize)
            writeHeader(file, sampleRate, actualDataSize)
            file.seek(HEADER_SIZE + actualDataSize)
        }

        private fun writeHeader(file: RandomAccessFile, sampleRate: Int, dataSize: Long) {
            file.seek(0)
            file.writeBytes("RIFF")
            writeLittleEndianInt(file, 36L + dataSize)
            file.writeBytes("WAVE")
            file.writeBytes("fmt ")
            writeLittleEndianInt(file, 16)
            writeLittleEndianShort(file, 1)
            writeLittleEndianShort(file, 1)
            writeLittleEndianInt(file, sampleRate.toLong())
            writeLittleEndianInt(file, sampleRate.toLong() * BYTES_PER_SAMPLE)
            writeLittleEndianShort(file, BYTES_PER_SAMPLE.toInt())
            writeLittleEndianShort(file, 16)
            file.writeBytes("data")
            writeLittleEndianInt(file, dataSize)
        }

        private fun writeLittleEndianInt(file: RandomAccessFile, value: Long) {
            file.write((value and 0xFF).toInt())
            file.write(((value ushr 8) and 0xFF).toInt())
            file.write(((value ushr 16) and 0xFF).toInt())
            file.write(((value ushr 24) and 0xFF).toInt())
        }

        private fun writeLittleEndianShort(file: RandomAccessFile, value: Int) {
            file.write(value and 0xFF)
            file.write((value ushr 8) and 0xFF)
        }

        private fun readLittleEndianInt(file: RandomAccessFile, offset: Long): Int {
            file.seek(offset)
            val b0 = file.read()
            val b1 = file.read()
            val b2 = file.read()
            val b3 = file.read()
            if (b3 < 0) throw EOFException()
            return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        }

        private fun readUnsignedLittleEndianInt(file: RandomAccessFile, offset: Long): Long =
            readLittleEndianInt(file, offset).toLong() and UInt.MAX_VALUE.toLong()

        private fun readLittleEndianShort(file: RandomAccessFile, offset: Long): Int {
            file.seek(offset)
            val b0 = file.read()
            val b1 = file.read()
            if (b1 < 0) throw EOFException()
            return b0 or (b1 shl 8)
        }

        private fun readAscii(file: RandomAccessFile, offset: Long, count: Int): String {
            file.seek(offset)
            val bytes = ByteArray(count)
            file.readFully(bytes)
            return bytes.toString(Charsets.US_ASCII)
        }

        private fun moveAtomically(source: File, target: File) {
            target.parentFile?.mkdirs()
            try {
                Files.move(
                    source.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    source.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        }
    }
}
