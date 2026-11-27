// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core

import eu.schicht8.meavox.core.library.ChooseCover
import eu.schicht8.meavox.core.library.FetchMissingCovers
import eu.schicht8.meavox.core.model.CoverArt
import eu.schicht8.meavox.core.model.CoverChoice
import eu.schicht8.meavox.core.model.SourceId
import eu.schicht8.meavox.core.model.Track
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.model.TrackPipelineState
import eu.schicht8.meavox.core.pipeline.DownloadTrack
import eu.schicht8.meavox.core.port.CoverCandidate
import eu.schicht8.meavox.core.testing.FakeCoverArtLoader
import eu.schicht8.meavox.core.testing.FakeMediaDownloader
import eu.schicht8.meavox.core.testing.FakeSource
import eu.schicht8.meavox.core.testing.InMemoryTrackFiles
import eu.schicht8.meavox.core.testing.InMemoryTrackRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Seam 3 of #51: a cover the user picked in the search, or removed, is theirs: no cover from the
 * Source or the imported file replaces it, until they go back to that one.
 */
class ChooseCoverTest {
    private val tracks = InMemoryTrackRepository()
    private val files = InMemoryTrackFiles()
    private val covers = FakeCoverArtLoader()
    private val youtube = FakeSource(SourceId("youtube"))
    private val choose = ChooseCover(tracks, files, listOf(youtube), covers)

    private val picked = CoverArt(byteArrayOf(4, 2))
    private val candidate = CoverCandidate(
        id = "a5711a77",
        title = "Bad",
        artist = "Michael Jackson",
        year = 1987,
        previewUrl = "https://caa.example/a5711a77/front-250",
        imageUrls = listOf("https://caa.example/a5711a77/front-1200", "https://caa.example/a5711a77/front"),
    )

    private suspend fun networkTrack(pipeline: TrackPipelineState = TrackPipelineState.Ready(null), coverUrl: String? = null): Track {
        youtube.add("bad", "Bad", coverUrl = "https://img.example/bad.jpg")
        return tracks.addIfAbsent(Track(TrackId(youtube.id, "bad"), "Bad", "Michael Jackson", null, pipeline, coverUrl = coverUrl))
    }

    @Test
    fun `picking a search result stores its best picture there is as the Track's Cover Art`() = runTest {
        val track = networkTrack()
        files.storeCover(track.id, CoverArt(byteArrayOf(1)))
        covers.serve("https://caa.example/a5711a77/front", picked) // no 1200 px version

        choose.pick(track.id, candidate)

        assertEquals(picked, files.cover(track.id))
        assertEquals(CoverChoice.Picked, tracks.get(track.id)?.coverChoice)
    }

    @Test
    fun `a picked cover is never replaced by the Source's picture, neither after a download nor by the job for missing covers`() = runTest {
        val track = networkTrack(TrackPipelineState.Added)
        covers.serve("https://caa.example/a5711a77/front-1200", picked)
        covers.serve("https://img.example/bad.jpg", CoverArt(byteArrayOf(9)))
        choose.pick(track.id, candidate)
        val downloader = FakeMediaDownloader(files).apply { serve(youtube.audioUriOf("bad"), "audio") }

        DownloadTrack(tracks, files, listOf(youtube), downloader, covers)(track.id)
        FetchMissingCovers(tracks, files, listOf(youtube), covers)()

        assertEquals(picked, files.cover(track.id))
    }

    @Test
    fun `a removed cover stays removed`() = runTest {
        val track = networkTrack()
        files.storeCover(track.id, CoverArt(byteArrayOf(1)))
        covers.serve("https://img.example/bad.jpg", CoverArt(byteArrayOf(9)))

        choose.remove(track.id)
        FetchMissingCovers(tracks, files, listOf(youtube), covers)()

        assertNull(files.cover(track.id))
        assertEquals(CoverChoice.Removed, tracks.get(track.id)?.coverChoice)
    }

    @Test
    fun `going back gives a network Track its Source's picture again`() = runTest {
        val track = networkTrack(coverUrl = "https://img.example/bad.jpg")
        val fromSource = CoverArt(byteArrayOf(9))
        covers.serve("https://img.example/bad.jpg", fromSource)
        covers.serve("https://caa.example/a5711a77/front-1200", picked)
        choose.pick(track.id, candidate)

        val outcome = choose.useAutomatic(track.id)

        assertEquals(ChooseCover.Outcome.Done, outcome)
        assertEquals(fromSource, files.cover(track.id))
        assertEquals(CoverChoice.Automatic, tracks.get(track.id)?.coverChoice)
    }

    @Test
    fun `going back gives an imported Track the picture its file embeds, or none`() = runTest {
        val withPicture = tracks.addIfAbsent(Track(TrackId(SourceId.Local, "a"), "A", null, null, TrackPipelineState.Ready(null)))
        val without = tracks.addIfAbsent(Track(TrackId(SourceId.Local, "b"), "B", null, null, TrackPipelineState.Ready(null)))
        val embedded = CoverArt(byteArrayOf(7))
        covers.embed(files.originalAudio(withPicture.id), embedded)
        covers.serve("https://caa.example/a5711a77/front-1200", picked)
        choose.pick(withPicture.id, candidate)
        choose.pick(without.id, candidate)

        choose.useAutomatic(withPicture.id)
        choose.useAutomatic(without.id)

        assertEquals(embedded, files.cover(withPicture.id))
        assertNull(files.cover(without.id))
    }

    @Test
    fun `going back while the Source can't be reached keeps the picked cover, and says so`() = runTest {
        val track = networkTrack(coverUrl = "https://img.example/bad.jpg")
        covers.serve("https://caa.example/a5711a77/front-1200", picked)
        choose.pick(track.id, candidate)
        covers.unreachable += "https://img.example/bad.jpg"

        val outcome = choose.useAutomatic(track.id)

        assertEquals(ChooseCover.Outcome.SourceUnreachable, outcome)
        assertEquals(picked, files.cover(track.id))
        assertEquals(CoverChoice.Picked, tracks.get(track.id)?.coverChoice)
    }

    @Test
    fun `going back to a Source without a picture leaves the Track without a cover`() = runTest {
        youtube.add("bad", "Bad")
        val track = tracks.addIfAbsent(Track(TrackId(youtube.id, "bad"), "Bad", null, null, TrackPipelineState.Ready(null)))
        covers.serve("https://caa.example/a5711a77/front-1200", picked)
        choose.pick(track.id, candidate)

        val outcome = choose.useAutomatic(track.id)

        assertEquals(ChooseCover.Outcome.Done, outcome)
        assertNull(files.cover(track.id))
    }
}
