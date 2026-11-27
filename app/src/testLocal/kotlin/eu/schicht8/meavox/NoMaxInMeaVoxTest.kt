// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox

import eu.schicht8.meavox.ui.licenses.ThirdPartyNotices
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Seam 2 of #21: MeaVox, the app for Google Play, never points at MeaVox Max or the network Sources,
 * so Play doesn't read it as a way around its policies. Checked are the texts the app shows (its
 * strings in every language and its third-party licenses) and its Play store listing.
 */
class NoMaxInMeaVoxTest {
    private val forbidden = listOf("MeaVox Max", "meavox.max", "NewPipe", "YouTube", "SoundCloud", "Bandcamp", "F-Droid")

    // Unit tests run in the app module's directory.
    private val res = File("src/main/res")
    private val listing = File("src/local/fastlane/metadata/android")
    private val licenseTexts = File(checkNotNull(System.getProperty("meavox.licenseTexts")) { "run through Gradle" })

    @Test
    fun `no string the app shows mentions them, in any language`() {
        val strings = res.listFiles { f -> f.name.startsWith("values") }.orEmpty()
            .mapNotNull { File(it, "strings.xml").takeIf(File::isFile) }
            .flatMap { file -> strings(file).map { (key, text) -> "${file.parentFile.name}/$key" to text } }
        assertTrue(strings.isNotEmpty())

        assertEquals(emptyList(), strings.filter { (_, text) -> mentions(text) })
    }

    @Test
    fun `the third-party licenses mention them nowhere, in the list or in the texts`() {
        val listed = ThirdPartyNotices.all.flatMap { listOfNotNull(it.name, it.license, it.url) }.filter(::mentions)
        val texts = licenseTexts.walkTopDown().filter { it.isFile && mentions(it.readText()) }.map { it.name }.toList()

        assertEquals(emptyList(), listed)
        assertEquals(emptyList(), texts)
    }

    @Test
    fun `the Play store listing is there, in English and German, and mentions them nowhere`() {
        for (locale in listOf("en-US", "de-DE")) {
            for (file in listOf("title.txt", "short_description.txt", "full_description.txt")) {
                assertTrue(File(listing, "$locale/$file").readText().isNotBlank(), "$locale/$file")
            }
        }
        val texts = listing.walkTopDown().filter { it.isFile && it.extension == "txt" }.toList()

        assertEquals(emptyList(), texts.filter { mentions(it.readText()) }.map { it.relativeTo(listing).path })
    }

    private fun mentions(text: String) = forbidden.any { text.contains(it, ignoreCase = true) }

    /** The string and plural texts of a strings.xml, by key; comments don't ship, so they don't count. */
    private fun strings(file: File): List<Pair<String, String>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val found = mutableListOf<Pair<String, String>>()
        for (tag in listOf("string", "item")) {
            val nodes = doc.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val element = nodes.item(i) as Element
                val key = element.getAttribute("name").ifEmpty { (element.parentNode as Element).getAttribute("name") }
                found += key to element.textContent
            }
        }
        return found
    }
}
