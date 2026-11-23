// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Seam 3 of #43: the German strings match the English ones. Lint's MissingTranslation finds a key
 * missing in German; this also finds placeholders that don't match, which would crash or garble the
 * text at run time, and German strings English no longer has.
 */
class TranslationsTest {
    private val english = strings("values")
    private val german = strings("values-de")

    @Test
    fun `every English text has a German one and German has no others`() {
        val translatable = english.filterValues { it.translatable }.keys
        assertEquals(translatable.sorted(), german.keys.sorted())
    }

    @Test
    fun `texts that aren't translated have no German version`() {
        val fixed = english.filterValues { !it.translatable }.keys
        assertTrue(fixed.isNotEmpty())
        assertEquals(emptySet(), fixed intersect german.keys)
    }

    @Test
    fun `each German text has the same placeholders as the English one`() {
        for ((key, de) in german) {
            val en = english[key] ?: continue
            for ((quantity, text) in de.texts) {
                // A plural's quantities differ between languages; "other" has every placeholder.
                val reference = en.texts[quantity] ?: en.texts.getValue("other")
                assertEquals(placeholders(reference), placeholders(text), "$key ($quantity): \"$text\"")
            }
        }
    }

    /** A string or plural: whether it is translated, and its text per quantity ("" for a plain string). */
    private class Entry(val translatable: Boolean, val texts: Map<String, String>)

    private fun strings(folder: String): Map<String, Entry> {
        val file = File("src/main/res/$folder/strings.xml")
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        val entries = mutableMapOf<String, Entry>()
        val nodes = root.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as? Element ?: continue
            val name = node.getAttribute("name")
            val translatable = node.getAttribute("translatable") != "false"
            entries[name] = when (node.tagName) {
                "string" -> Entry(translatable, mapOf("" to node.textContent))
                "plurals" -> {
                    val items = node.getElementsByTagName("item")
                    Entry(translatable, (0 until items.length).associate { j ->
                        val item = items.item(j) as Element
                        item.getAttribute("quantity") to item.textContent
                    })
                }
                else -> continue
            }
        }
        return entries
    }

    /** The format placeholders of [text], in order: `%1$s`, `%2$d`, `%%`. */
    private fun placeholders(text: String): List<String> = Regex("""%(\d+\$)?[sd%]""").findAll(text).map { it.value }.toList()
}
