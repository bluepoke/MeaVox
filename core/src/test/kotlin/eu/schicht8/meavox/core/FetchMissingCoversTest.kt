// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core

import eu.schicht8.meavox.core.library.FetchMissingCovers
import eu.schicht8.meavox.core.model.CoverArt
import eu.schicht8.meavox.core.model.FailureKind
import eu.schicht8.meavox.core.model.FailureReason
import eu.schicht8.meavox.core.model.SourceId
import eu.schicht8.meavox.core.model.Track
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.model.TrackPipelineState
import eu.schicht8.meavox.core.port.MediaUnavailable
import eu.schicht8.meavox.core.testing.FakeCoverArtLoader
import eu.schicht8.meavox.core.testing.FakeSource
import eu.schicht8.meavox.core.testing.InMemoryTrackFiles
import eu.schicht8.meavox.core.testing.InMemoryTrackRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Seam 1 of #50: Tracks of a network Source added before #42 have no Cover Art; their Source is asked
 * for the item's picture once, and it is stored as if it had come with the download.
 */
class FetchMissingCoversTest {
    private val tracks = InMemoryTrackRepository()
    private val files = InMemoryTrackFiles()
    private val covers = FakeCoverArtLoader()
    private val youtube = FakeSource(SourceId("youtube"))
    private val fetch = FetchMissingCovers(tracks, files, listOf(youtube), covers)

    /** A Track as #42 found it: downloaded, without a picture to download. */
    private suspend fun addedBefore42(sourceTrackId: String, pipeline: TrackPipelineState = TrackPipelineState.Ready(null)): Track =
        tracks.addIfAbsent(Track(TrackId(youtube.id, sourceTrackId), "Bad", "Michael Jackson", 247_000, pipeline))

    @Test
    fun `a Track added before #42 gets its video's picture as Cover Art, kept as where its cover comes from`() = runTest {
        youtube.add("bad", "Bad", coverUrl = "https://img.example/bad/hq720.jpg")
        val track = addedBefore42("bad")
        val cover = CoverArt(byteArrayOf(1, 2, 3))
        covers.serve("https://img.example/bad/hq720.jpg", cover)

        fetch()

        assertEquals(cover, files.cover(track.id))
        assertEquals("https://img.example/bad/hq720.jpg", tracks.get(track.id)?.coverUrl)
    }

    @Test
    fun `only network Tracks with stored audio and no Cover Art are looked up`() = runTest {
        for (id in listOf("has-cover", "downloading", "bad")) youtube.add(id, id, coverUrl = "https://img.example/$id.jpg")
        covers.serve("https://img.example/bad.jpg", CoverArt(byteArrayOf(1)))
        val withCover = addedBefore42("has-cover")
        files.storeCover(withCover.id, CoverArt(byteArrayOf(9)))
        addedBefore42("downloading", TrackPipelineState.Downloading(0.5f))
        addedBefore42("bad")
        val imported = tracks.addIfAbsent(Track(TrackId(SourceId.Local, "abc"), "My Way", null, null, TrackPipelineState.Downloaded))

        fetch()

        assertEquals(listOf("bad"), youtube.coversAskedFor)
        assertEquals(CoverArt(byteArrayOf(9)), files.cover(withCover.id))
        assertNull(files.cover(imported.id))
    }

    @Test
    fun `a Track whose item has no picture, or is gone, isn't asked about again`() = runTest {
        youtube.add("no-picture", "No picture")
        addedBefore42("no-picture")
        fetch()
        youtube.add("gone", "Gone")
        addedBefore42("gone")
        youtube.coverFailure = MediaUnavailable(FailureReason(FailureKind.TrackUnavailable), retryable = false)
        fetch()
        youtube.coverFailure = null
        youtube.coversAskedFor.clear()

        val settled = fetch()

        assertEquals(emptyList(), youtube.coversAskedFor)
        assertTrue(settled)
    }

    @Test
    fun `a picture that isn't there isn't tried again`() = runTest {
        youtube.add("bad", "Bad", coverUrl = "https://img.example/gone.jpg")
        addedBefore42("bad")
        fetch()
        youtube.coversAskedFor.clear()

        fetch()

        assertEquals(emptyList(), youtube.coversAskedFor)
        assertEquals(listOf("https://img.example/gone.jpg"), covers.downloaded)
    }

    @Test
    fun `a picture that can't be downloaded now is tried again, and the run says so`() = runTest {
        youtube.add("bad", "Bad", coverUrl = "https://img.example/bad.jpg")
        val track = addedBefore42("bad")
        covers.unreachable += "https://img.example/bad.jpg"
        val settled = fetch()
        covers.unreachable.clear()
        covers.serve("https://img.example/bad.jpg", CoverArt(byteArrayOf(1)))

        fetch()

        assertFalse(settled)
        assertEquals(CoverArt(byteArrayOf(1)), files.cover(track.id))
    }

    @Test
    fun `without a connection, or while the Source's extractor is broken, the Source is asked again next time`() = runTest {
        youtube.add("bad", "Bad", coverUrl = "https://img.example/bad.jpg")
        val track = addedBefore42("bad")
        covers.serve("https://img.example/bad.jpg", CoverArt(byteArrayOf(1)))
        youtube.coverFailure = MediaUnavailable(FailureReason(FailureKind.ServiceUnreachable), retryable = true)
        assertFalse(fetch())
        youtube.coverFailure = MediaUnavailable(FailureReason(FailureKind.ServiceChanged), retryable = false)
        assertFalse(fetch())
        youtube.coverFailure = null

        fetch()

        assertEquals(CoverArt(byteArrayOf(1)), files.cover(track.id))
    }

    @Test
    fun `an item whose audio can't be downloaded still gives its picture`() = runTest {
        youtube.add("bad", "Bad", coverUrl = "https://img.example/bad.jpg")
        val track = addedBefore42("bad")
        covers.serve("https://img.example/bad.jpg", CoverArt(byteArrayOf(1)))
        youtube.resolveFailure = MediaUnavailable(FailureReason(FailureKind.NoDownloadableAudio), retryable = false)

        fetch()

        assertEquals(CoverArt(byteArrayOf(1)), files.cover(track.id))
    }

    @Test
    fun `a Track that kept where its picture is, but couldn't download it, is downloaded from there without asking the Source`() = runTest {
        youtube.add("bad", "Bad")
        val track = tracks.addIfAbsent(
            Track(TrackId(youtube.id, "bad"), "Bad", null, null, TrackPipelineState.Downloaded, coverUrl = "https://img.example/kept.jpg"),
        )
        covers.serve("https://img.example/kept.jpg", CoverArt(byteArrayOf(7)))

        fetch()

        assertEquals(CoverArt(byteArrayOf(7)), files.cover(track.id))
        assertEquals(emptyList(), youtube.coversAskedFor)
    }

    @Test
    fun `a kept picture that is no longer there is looked up at the Source again`() = runTest {
        youtube.add("bad", "Bad", coverUrl = "https://img.example/new.jpg")
        val track = tracks.addIfAbsent(
            Track(TrackId(youtube.id, "bad"), "Bad", null, null, TrackPipelineState.Downloaded, coverUrl = "https://img.example/old.jpg"),
        )
        covers.serve("https://img.example/new.jpg", CoverArt(byteArrayOf(8)))

        fetch()

        assertEquals(CoverArt(byteArrayOf(8)), files.cover(track.id))
        assertEquals("https://img.example/new.jpg", tracks.get(track.id)?.coverUrl)
    }
}
