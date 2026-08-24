package dev.soupslurpr.transcribro.recognitionservice.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Non-régression du bug « Input audio is too short » : une lecture partielle
 * d'AudioRecord sous 512 échantillons atteignait Silero telle quelle et
 * faisait échouer toute la dictée (observé sur SM-S721W le 24 août 2026 :
 * 8 dictées sur 18, toutes celles dont le total d'échantillons n'était pas
 * un multiple aligné, WAV pourtant sain à 92 % de parole).
 */
class VadWindowBufferTest {

    private companion object {
        const val MIN = 512
    }

    private fun samples(count: Int, from: Int = 0): ShortArray =
        ShortArray(count) { ((from + it) % 3000).toShort() }

    @Test
    fun `un bloc plein est restitue tel quel`() {
        val buffer = VadWindowBuffer(MIN)
        val block = samples(1280)

        val window = buffer.feed(block, block.size)

        assertArrayEquals(block, window)
        assertEquals(0, buffer.pendingSamples)
    }

    @Test
    fun `une lecture partielle sous le minimum est retenue puis resorbee`() {
        val buffer = VadWindowBuffer(MIN)

        // Le cas du terrain : un reliquat de 64 échantillons.
        assertNull(buffer.feed(samples(64), 64))
        assertEquals(64, buffer.pendingSamples)

        // Le bloc suivant emporte le reliquat en tête de fenêtre.
        val next = samples(1280, from = 64)
        val window = buffer.feed(next, next.size)

        assertEquals(64 + 1280, window!!.size)
        assertArrayEquals(samples(64), window.copyOf(64))
        assertArrayEquals(next, window.copyOfRange(64, window.size))
        assertEquals(0, buffer.pendingSamples)
    }

    @Test
    fun `aucune fenetre emise n'est jamais sous le minimum`() {
        val buffer = VadWindowBuffer(MIN)
        // Tailles adverses relevées sur l'appareil : 64, 128, 192, 320, 448.
        val reads = intArrayOf(1280, 64, 1280, 128, 192, 1280, 320, 448, 64)
        var emitted = 0L

        for (read in reads) {
            val window = buffer.feed(samples(read), read)
            if (window != null) {
                assertTrue(
                    "fenêtre de ${window.size} échantillons sous le minimum",
                    window.size >= MIN,
                )
                emitted += window.size
            }
        }

        // Conservation : tout échantillon est émis ou encore en attente.
        assertEquals(reads.sum().toLong(), emitted + buffer.pendingSamples)
    }

    @Test
    fun `des miettes successives s'accumulent jusqu'a former une fenetre`() {
        val buffer = VadWindowBuffer(MIN)

        repeat(7) { assertNull(buffer.feed(samples(64), 64)) }
        assertEquals(448, buffer.pendingSamples)

        val window = buffer.feed(samples(64), 64)

        assertEquals(512, window!!.size)
        assertEquals(0, buffer.pendingSamples)
    }

    @Test
    fun `seuls les count premiers echantillons du bloc sont pris`() {
        val buffer = VadWindowBuffer(MIN)
        val block = samples(1280)

        val window = buffer.feed(block, 600)

        assertEquals(600, window!!.size)
        assertArrayEquals(block.copyOf(600), window)
    }

    @Test
    fun `une lecture vide ne debloque rien sous le minimum`() {
        val buffer = VadWindowBuffer(MIN)

        assertNull(buffer.feed(samples(64), 64))
        assertNull(buffer.feed(samples(0), 0))
        assertEquals(64, buffer.pendingSamples)
    }

    @Test
    fun `reset vide le tampon`() {
        val buffer = VadWindowBuffer(MIN)

        assertNull(buffer.feed(samples(300), 300))
        buffer.reset()

        assertEquals(0, buffer.pendingSamples)
        // Après reset, aucun échantillon fantôme ne précède le bloc suivant.
        val block = samples(1280)
        assertArrayEquals(block, buffer.feed(block, block.size))
    }
}
