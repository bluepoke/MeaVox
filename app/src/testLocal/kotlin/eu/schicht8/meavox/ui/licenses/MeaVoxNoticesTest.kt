// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.ui.licenses

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Seam 1 of #21 for MeaVox (Google Play): it lists, and carries the texts of, only what it contains. */
class MeaVoxNoticesTest {
    private val texts = File(checkNotNull(System.getProperty("meavox.licenseTexts")) { "run through Gradle" })

    @Test
    fun `MeaVox lists none of the NewPipe Sources' libraries`() {
        val names = ThirdPartyNotices.all.map { it.name }

        for (absent in listOf("NewPipeExtractor", "nanojson", "jsoup", "Mozilla Rhino", "Protocol Buffers (Java lite)", "JSR 305 annotations")) {
            assertTrue(absent !in names, "$absent in $names")
        }
    }

    @Test
    fun `MeaVox carries no license text only MeaVox Max needs`() {
        assertEquals(emptyList(), texts.walkTopDown().filter { it.isFile && it.relativeTo(texts).path.startsWith("max") }.toList())
    }
}
