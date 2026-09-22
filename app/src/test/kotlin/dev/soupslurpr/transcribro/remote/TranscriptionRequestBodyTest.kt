package dev.soupslurpr.transcribro.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionRequestBodyTest {

    private val audio = byteArrayOf(0x52, 0x49, 0x46, 0x46) // « RIFF »

    private fun texte(body: ByteArray) = String(body, Charsets.UTF_8)

    @Test
    fun `le vocabulaire part dans le champ prompt, en utf-8`() {
        val body = TranscriptionRequestBody.build(audio, "fr", "Chuchote Flow, été")
        val corps = texte(body)
        assertTrue(corps.contains("name=\"file\"; filename=\"dictee.wav\""))
        assertTrue(corps.contains("name=\"language\"\r\n\r\nfr\r\n"))
        assertTrue(corps.contains("name=\"prompt\"\r\n\r\nChuchote Flow, été\r\n"))
        assertTrue(corps.endsWith("--${TranscriptionRequestBody.BOUNDARY}--\r\n"))
        // « é » occupe deux octets : rien n'a été tronqué à un octet.
        assertTrue(body.toList().windowed(2).any { it == listOf(0xC3.toByte(), 0xA9.toByte()) })
    }

    @Test
    fun `sans vocabulaire, aucun champ prompt et aucune temperature`() {
        val corps = texte(TranscriptionRequestBody.build(audio, "fr", ""))
        assertFalse(corps.contains("name=\"prompt\""))
        assertFalse(corps.contains("name=\"temperature\""))
        assertEquals(2, Regex("Content-Disposition").findAll(corps).count())
    }

    @Test
    fun `l audio est transmis tel quel entre ses en-tetes`() {
        val body = TranscriptionRequestBody.build(audio, "fr", "")
        val corps = texte(body)
        val debut = corps.indexOf("Content-Type: audio/wav\r\n\r\n") + "Content-Type: audio/wav\r\n\r\n".length
        assertEquals("RIFF", corps.substring(debut, debut + 4))
    }
}
