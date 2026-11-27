// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.ui.licenses

import kotlin.test.Test
import kotlin.test.assertTrue

/** Seam 1 of #21 for MeaVox Max: it also lists the NewPipe Sources and every library they bring. */
class MeaVoxMaxNoticesTest {
    @Test
    fun `MeaVox Max lists NewPipeExtractor and its libraries`() {
        val names = ThirdPartyNotices.all.map { it.name }

        // NewPipeExtractor's runtime dependencies (./gradlew :app:dependencies, networkReleaseRuntimeClasspath).
        for (expected in listOf("NewPipeExtractor", "nanojson", "jsoup", "Mozilla Rhino", "Protocol Buffers (Java lite)", "JSR 305 annotations")) {
            assertTrue(expected in names, "$expected in $names")
        }
    }
}
