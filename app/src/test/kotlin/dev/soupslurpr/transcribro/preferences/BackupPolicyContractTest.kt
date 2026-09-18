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
 * que ni le bearer token ni la file de synchronisation (`chuchote_sync`, qui
 * porte des mots du dictionnaire personnel en attente de suppression) ne
 * redeviennent éligibles à Auto Backup ou au transfert d'appareil à la suite
 * d'une modification de ressources.
 */
class BackupPolicyContractTest {
    @Test
    fun `legacy backup excludes remote bearer token and sync queue`() {
        val document = parseResource("backup_rules.xml")

        SECRET_PREFERENCES.forEach { path ->
            assertExclude(root = document.documentElement, domain = "sharedpref", path = path)
        }
    }

    @Test
    fun `cloud backup and device transfer exclude remote bearer token and sync queue`() {
        val document = parseResource("data_extraction_rules.xml")

        listOf("cloud-backup", "device-transfer").forEach { section ->
            SECRET_PREFERENCES.forEach { path ->
                assertExclude(root = requireSection(document, section), domain = "sharedpref", path = path)
            }
        }
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

    private companion object {
        val SECRET_PREFERENCES = listOf("remote_transcription.xml", "chuchote_sync.xml")
    }
}
