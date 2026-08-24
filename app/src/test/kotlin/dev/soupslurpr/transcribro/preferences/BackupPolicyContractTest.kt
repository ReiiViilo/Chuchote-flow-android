package dev.soupslurpr.transcribro.preferences

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * Contrat de confidentialité pur pour les deux générations de règles Android.
 *
 * Le test lit les sources XML plutôt qu'un état Android simulé : il garantit
 * que le bearer token ne redevient pas éligible à Auto Backup ou au transfert
 * d'appareil à la suite d'une modification de ressources.
 */
class BackupPolicyContractTest {
    @Test
    fun `legacy backup excludes remote bearer token`() {
        val document = parseResource("backup_rules.xml")

        assertExclude(
            root = document.documentElement,
            domain = "sharedpref",
            path = "remote_transcription.xml",
        )
    }

    @Test
    fun `cloud backup and device transfer exclude remote bearer token`() {
        val document = parseResource("data_extraction_rules.xml")

        assertExclude(
            root = requireSection(document, "cloud-backup"),
            domain = "sharedpref",
            path = "remote_transcription.xml",
        )
        assertExclude(
            root = requireSection(document, "device-transfer"),
            domain = "sharedpref",
            path = "remote_transcription.xml",
        )
    }

    private fun parseResource(name: String): Document {
        val candidates = listOf(
            File("src/main/res/xml/$name"),
            File("app/src/main/res/xml/$name"),
        )
        val source = candidates.firstOrNull(File::isFile)
        assertNotNull("Ressource XML introuvable: $name", source)
        return DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(requireNotNull(source))
    }

    private fun requireSection(document: Document, name: String): Element {
        val section = document.getElementsByTagName(name).item(0) as? Element
        assertNotNull("Section XML absente: $name", section)
        return requireNotNull(section)
    }

    private fun assertExclude(root: Element, domain: String, path: String) {
        val excludes = root.getElementsByTagName("exclude")
        val found = (0 until excludes.length)
            .mapNotNull { index -> excludes.item(index) as? Element }
            .any { element ->
                element.getAttribute("domain") == domain &&
                    element.getAttribute("path") == path
            }
        assertTrue(
            "Exclusion absente sous <${root.tagName}>: domain=$domain path=$path",
            found,
        )
    }
}
