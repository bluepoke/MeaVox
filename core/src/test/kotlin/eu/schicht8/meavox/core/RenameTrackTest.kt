// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core

import eu.schicht8.meavox.core.library.RenameTrack
import eu.schicht8.meavox.core.lyrics.LyricsPipeline
import eu.schicht8.meavox.core.lyrics.TrackLyrics
import eu.schicht8.meavox.core.model.FailureKind
import eu.schicht8.meavox.core.model.FailureReason
import eu.schicht8.meavox.core.model.SourceId
import eu.schicht8.meavox.core.model.Track
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.model.TrackPipelineState
import eu.schicht8.meavox.core.port.LyricsCandidate
import eu.schicht8.meavox.core.port.LyricsQuery
import eu.schicht8.meavox.core.testing.FakePipelineJobs
import eu.schicht8.meavox.core.testing.InMemoryLyricsRepository
import eu.schicht8.meavox.core.testing.InMemoryTrackRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class RenameTrackTest {
    private val tracks = InMemoryTrackRepository()
    private val lyrics = InMemoryLyricsRepository()
    private val jobs = FakePipelineJobs()
    private val rename = RenameTrack(tracks, lyrics)

    private fun TestScope.startLyricsPipeline() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { LyricsPipeline(tracks, lyrics, jobs).run() }
    }

    private suspend fun track(title: String = "Michael Jackson - Beat It (Official Video) [4K]", artist: String? = "MJ Vevo") =
        tracks.addIfAbsent(Track(TrackId(SourceId("youtube"), "v1"), title, artist, 258_000, TrackPipelineState.Added))

    @Test
    fun `a renamed Track has the new title and artist`() = runTest {
        val track = track()

        rename(track.id, title = "Beat It", artist = "Michael Jackson")

        val renamed = tracks.get(track.id)!!
        assertEquals("Beat It", renamed.title)
        assertEquals("Michael Jackson", renamed.artist)
    }

    @Test
    fun `the title and artist are trimmed, and a blank artist clears it`() = runTest {
        val track = track()

        rename(track.id, title = "  Beat It ", artist = "   ")

        val renamed = tracks.get(track.id)!!
        assertEquals("Beat It", renamed.title)
        assertEquals(null, renamed.artist)
    }

    @Test
    fun `a blank title is refused and the Track keeps its name`() = runTest {
        val track = track()

        assertFailsWith<IllegalArgumentException> { rename(track.id, title = "  ", artist = "Michael Jackson") }

        assertEquals(track, tracks.get(track.id))
    }

    @Test
    fun `Lyrics that weren't found are searched again with the new name`() = runTest {
        startLyricsPipeline()
        val track = track()
        lyrics.set(track.id, TrackLyrics.NotFound(LyricsQuery("MJ Vevo", "Michael Jackson - Beat It", 258_000)))

        rename(track.id, title = "Beat It", artist = "Michael Jackson")

        assertEquals(TrackLyrics.Searching, lyrics.get(track.id))
        assertEquals(listOf(track.id, track.id), jobs.lyricsFetches) // once when added, once after the rename
    }

    @Test
    fun `Lyrics whose search failed are searched again with the new name`() = runTest {
        val track = track()
        lyrics.set(track.id, TrackLyrics.Failed(FailureReason(FailureKind.ServiceUnreachable, service = "LRCLIB")))

        rename(track.id, title = "Beat It", artist = "Michael Jackson")

        assertEquals(TrackLyrics.Searching, lyrics.get(track.id))
    }

    @Test
    fun `suggested or confirmed Lyrics stay as they are`() = runTest {
        val beatIt = LyricsCandidate("1", "Michael Jackson", "Beat It", 258_000, "They told him", null)
        val suggested = TrackLyrics.Suggested(beatIt, LyricsQuery("Michael Jackson", "Beat It", 258_000))
        for (kept in listOf(suggested, TrackLyrics.Confirmed(beatIt))) {
            val track = track()
            lyrics.set(track.id, kept)

            rename(track.id, title = "Beat It (live)", artist = "Michael Jackson")

            assertEquals(kept, lyrics.get(track.id))
        }
    }
}
