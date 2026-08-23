package dev.soupslurpr.transcribro.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AudioDeletionContractTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `la ligne ne peut pas etre supprimee si un fichier audio resiste`() {
        val wav = File(temporaryFolder.root, "dictee.wav").apply { writeBytes(byteArrayOf(1)) }
        val part = File("${wav.path}.part").apply { writeBytes(byteArrayOf(2)) }

        val filesConfirmedAbsent = deleteAudioPair(wav) { false }

        assertFalse(filesConfirmedAbsent)
        assertTrue(wav.exists())
        assertTrue(part.exists())
    }

    @Test
    fun `la ligne peut etre supprimee seulement apres disparition du wav et du part`() {
        val wav = File(temporaryFolder.root, "dictee.wav").apply { writeBytes(byteArrayOf(1)) }
        val part = File("${wav.path}.part").apply { writeBytes(byteArrayOf(2)) }

        val filesConfirmedAbsent = deleteAudioPair(wav)

        assertTrue(filesConfirmedAbsent)
        assertFalse(wav.exists())
        assertFalse(part.exists())
    }

    @Test
    fun `la recuperation traite une erreur ordinaire sans masquer un manque de memoire`() {
        assertNull(
            StoreAudioResolution.resolve<String> {
                throw java.io.IOException("wav illisible")
            },
        )
        assertThrows(OutOfMemoryError::class.java) {
            StoreAudioResolution.resolve<String> {
                throw OutOfMemoryError("memoire")
            }
        }
    }
}
