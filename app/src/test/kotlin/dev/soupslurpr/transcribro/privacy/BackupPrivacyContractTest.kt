package dev.soupslurpr.transcribro.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BackupPrivacyContractTest {

    @Test
    fun `historique et dictionnaire sont exclus du cloud et du transfert`() {
        val resourceRoot = listOf(
            File(System.getProperty("user.dir"), "src/main/res/xml"),
            File(System.getProperty("user.dir"), "app/src/main/res/xml"),
        ).first { it.isDirectory }

        val legacy = File(resourceRoot, "backup_rules.xml").readText()
        val modern = File(resourceRoot, "data_extraction_rules.xml").readText()
        val databaseExclusion = Regex(
            """<exclude\s+domain=[\"']database[\"']\s+path=[\"']\.[\"']\s*/>""",
        )

        assertTrue(databaseExclusion.containsMatchIn(legacy))
        assertEquals(2, databaseExclusion.findAll(modern).count())
    }
}
