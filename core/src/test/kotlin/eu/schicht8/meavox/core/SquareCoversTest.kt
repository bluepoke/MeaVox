// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core

import eu.schicht8.meavox.core.library.SquareCovers
import eu.schicht8.meavox.core.model.CoverArt
import eu.schicht8.meavox.core.model.SourceId
import eu.schicht8.meavox.core.model.Track
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.model.TrackPipelineState
import eu.schicht8.meavox.core.testing.FakeCoverArtLoader
import eu.schicht8.meavox.core.testing.InMemorySettingsRepository
import eu.schicht8.meavox.core.testing.InMemoryTrackFiles
import eu.schicht8.meavox.core.testing.InMemoryTrackRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Seam 2 of #50: Cover Art is square (centre-cropped) from now on, and covers stored before, such as
 * a video's 16:9 thumbnail, are cropped once.
 */
class SquareCoversTest {
    private val tracks = InMemoryTrackRepository()
    private val files = InMemoryTrackFiles()
    private val covers = FakeCoverArtLoader()
    private val settings = InMemorySettingsRepository()
    private val square = SquareCovers(tracks, files, covers, settings)

    private suspend fun track(id: String) =
        tracks.addIfAbsent(Track(TrackId(SourceId("youtube"), id), id, null, null, TrackPipelineState.Ready(null)))

    @Test
    fun `covers stored before are cropped to squares once, and Tracks without one are left alone`() = runTest {
        val wide = track("last-christmas")
        files.storeCover(wide.id, CoverArt(byteArrayOf(16, 9)))
        val without = track("bad")

        square()

        assertEquals(CoverArt(byteArrayOf(FakeCoverArtLoader.SQUARED, 16, 9)), files.cover(wide.id))
        assertNull(files.cover(without.id))
    }

    @Test
    fun `a second run crops nothing again`() = runTest {
        val wide = track("last-christmas")
        files.storeCover(wide.id, CoverArt(byteArrayOf(16, 9)))
        square()
        val once = files.cover(wide.id)

        square()

        assertEquals(once, files.cover(wide.id))
        assertEquals(CoverArt(byteArrayOf(FakeCoverArtLoader.SQUARED, 16, 9)), once)
    }
}
