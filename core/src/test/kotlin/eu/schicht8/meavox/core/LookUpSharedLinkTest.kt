// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core

import eu.schicht8.meavox.core.library.Library
import eu.schicht8.meavox.core.library.LookUpSharedLink
import eu.schicht8.meavox.core.model.SourceId
import eu.schicht8.meavox.core.testing.FakeSource
import eu.schicht8.meavox.core.testing.InMemoryTakeRepository
import eu.schicht8.meavox.core.testing.InMemoryTrackFiles
import eu.schicht8.meavox.core.testing.InMemoryTrackRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LookUpSharedLinkTest {
    private val tracks = InMemoryTrackRepository()
    private val library = Library(tracks, InMemoryTakeRepository(), InMemoryTrackFiles())
    private val youtube = FakeSource(SourceId("youtube"))
    private val soundcloud = FakeSource(SourceId("soundcloud"))
    private val lookUp = LookUpSharedLink(listOf(youtube, soundcloud))

    @Test
    fun `a shared link finds the item of the Source it belongs to, to be added like a search result`() = runTest {
        val song = soundcloud.add("rolling", "Rolling in the Deep", artist = "Adele", durationMs = 228_000)

        val found = lookUp(soundcloud.urlOf("rolling"))

        assertEquals(song, found)
        // Nothing is added until the user confirms the import sheet.
        assertEquals(emptyList(), library.tracks().first())
    }

    @Test
    fun `a link no Source knows finds nothing`() = runTest {
        assertNull(lookUp("https://example.com/some/page"))
    }
}
