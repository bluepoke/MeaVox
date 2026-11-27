// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core

import eu.schicht8.meavox.core.library.Library
import eu.schicht8.meavox.core.model.CoverArt
import eu.schicht8.meavox.core.model.FailureKind
import eu.schicht8.meavox.core.model.FailureReason
import eu.schicht8.meavox.core.model.PipelineStep
import eu.schicht8.meavox.core.model.SourceId
import eu.schicht8.meavox.core.model.TrackPipelineState
import eu.schicht8.meavox.core.pipeline.DownloadTrack
import eu.schicht8.meavox.core.port.MediaUnavailable
import eu.schicht8.meavox.core.testing.FakeCoverArtLoader
import eu.schicht8.meavox.core.testing.FakeMediaDownloader
import eu.schicht8.meavox.core.testing.FakeSource
import eu.schicht8.meavox.core.testing.InMemoryTrackFiles
import eu.schicht8.meavox.core.testing.InMemoryTakeRepository
import eu.schicht8.meavox.core.testing.InMemoryTrackRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadTrackTest {
    private val tracks = InMemoryTrackRepository()
    private val files = InMemoryTrackFiles()
    private val youtube = FakeSource(SourceId("youtube"))
    private val downloader = FakeMediaDownloader(files)
    private val covers = FakeCoverArtLoader()
    private val download = DownloadTrack(tracks, files, listOf(youtube), downloader, covers)
    private val library = Library(tracks, InMemoryTakeRepository())

    @Test
    fun `an added Track's audio is downloaded and kept as its Original Audio`() = runTest {
        val candidate = youtube.add("beat-it", "Beat It", artist = "Michael Jackson")
        downloader.serve(youtube.audioUriOf("beat-it"), "the audio of Beat It")
        val track = library.add(candidate)

        val outcome = download(track.id)

        assertEquals(DownloadTrack.Outcome.Done, outcome)
        assertEquals(TrackPipelineState.Downloaded, tracks.get(track.id)?.pipeline)
        assertEquals("the audio of Beat It", files.originalAudioContent(track.id)?.decodeToString())
    }

    @Test
    fun `the Source's picture is downloaded with the audio and kept as the Track's Cover Art`() = runTest {
        val track = library.add(youtube.add("beat-it", "Beat It", coverUrl = "https://img.example/beat-it.webp"))
        downloader.serve(youtube.audioUriOf("beat-it"), "the audio of Beat It")
        val cover = CoverArt(byteArrayOf(1, 2, 3))
        covers.serve("https://img.example/beat-it.webp", cover)

        download(track.id)

        assertEquals(cover, files.cover(track.id))
    }

    @Test
    fun `the Track is Downloaded before its picture is fetched, so a slow picture never holds up the audio`() = runTest {
        val track = library.add(youtube.add("beat-it", "Beat It", coverUrl = "https://img.example/beat-it.jpg"))
        downloader.serve(youtube.audioUriOf("beat-it"), "the audio of Beat It")
        covers.serve("https://img.example/beat-it.jpg", CoverArt(byteArrayOf(1)))
        var whileFetching: TrackPipelineState? = null
        covers.beforeDownload = { whileFetching = tracks.get(track.id)?.pipeline }

        download(track.id)

        assertEquals(TrackPipelineState.Downloaded, whileFetching)
    }

    @Test
    fun `a picture that can't be downloaded leaves the Track without Cover Art, but Downloaded`() = runTest {
        val track = library.add(youtube.add("beat-it", "Beat It", coverUrl = "https://img.example/gone.jpg"))
        downloader.serve(youtube.audioUriOf("beat-it"), "the audio of Beat It")

        val outcome = download(track.id)

        assertEquals(DownloadTrack.Outcome.Done, outcome)
        assertEquals(TrackPipelineState.Downloaded, tracks.get(track.id)?.pipeline)
        assertNull(files.cover(track.id))
    }

    @Test
    fun `a Track without a picture is downloaded without Cover Art`() = runTest {
        val track = library.add(youtube.add("beat-it", "Beat It"))
        downloader.serve(youtube.audioUriOf("beat-it"), "the audio of Beat It")

        download(track.id)

        assertEquals(TrackPipelineState.Downloaded, tracks.get(track.id)?.pipeline)
        assertEquals(emptyList(), covers.downloaded)
        assertNull(files.cover(track.id))
    }

    @Test
    fun `a download that fails doesn't fetch the picture yet`() = runTest {
        val track = library.add(youtube.add("beat-it", "Beat It", coverUrl = "https://img.example/beat-it.jpg"))
        downloader.serve(youtube.audioUriOf("beat-it"), "0123456789")
        downloader.failAfterBytes = 4

        download(track.id)

        assertEquals(emptyList(), covers.downloaded)
    }

    @Test
    fun `while downloading the Track shows the progress in whole percent`() = runTest {
        val track = library.add(youtube.add("beat-it", "Beat It"))
        downloader.serve(youtube.audioUriOf("beat-it"), "x".repeat(1_000))
        downloader.progressSteps = listOf(0L, 333L, 1_000L)
        val expected = listOf(TrackPipelineState.Downloading(0f), TrackPipelineState.Downloading(0.33f), TrackPipelineState.Downloading(1f))
        val shown = mutableListOf<TrackPipelineState?>()
        downloader.afterEachProgress = { step ->
            shown += withTimeout(1_000) { tracks.observe(track.id).map { it?.pipeline }.first { it == expected[step] } }
        }

        download(track.id)

        assertEquals<List<TrackPipelineState?>>(expected, shown)
    }

    @Test
    fun `an interrupted download is retried later and continues after the bytes it has`() = runTest {
        val track = library.add(youtube.add("beat-it", "Beat It"))
        downloader.serve(youtube.audioUriOf("beat-it"), "0123456789")
        downloader.failAfterBytes = 4

        val first = download(track.id)
        downloader.failAfterBytes = null
        val second = download(track.id)

        assertEquals(DownloadTrack.Outcome.RetryLater, first)
        assertEquals(DownloadTrack.Outcome.Done, second)
        assertEquals(listOf(0L, 4L), downloader.startedAt)
        assertEquals("0123456789", files.originalAudioContent(track.id)?.decodeToString())
    }

    @Test
    fun `when the Source now offers a different audio stream, the download starts over`() = runTest {
        val track = library.add(youtube.add("beat-it", "Beat It"))
        downloader.serve(youtube.audioUriOf("beat-it"), "0123456789")
        downloader.failAfterBytes = 4
        download(track.id)
        val oldPart = files.downloadPart(track.id, "fake-audio")
        youtube.audioKey = "another-format"
        downloader.failAfterBytes = null

        download(track.id)

        assertEquals(listOf(0L, 0L), downloader.startedAt)
        assertEquals(0, files.partContent(oldPart).size)
        assertEquals("0123456789", files.originalAudioContent(track.id)?.decodeToString())
    }

    @Test
    fun `a song the Source can't give leaves the Track Failed with the reason`() = runTest {
        val track = library.add(youtube.add("gone", "Removed video"))
        youtube.resolveFailure = MediaUnavailable(FailureReason(FailureKind.TrackUnavailable, "This video has been removed by the uploader", service = "YouTube"), retryable = false)

        val outcome = download(track.id)

        assertEquals(DownloadTrack.Outcome.Done, outcome)
        assertEquals(
            TrackPipelineState.Failed(PipelineStep.Downloading, FailureReason(FailureKind.TrackUnavailable, "This video has been removed by the uploader", service = "YouTube")),
            tracks.get(track.id)?.pipeline,
        )
    }

    @Test
    fun `a download that keeps failing ends Failed after five failed attempts, keeping the bytes for a manual retry`() = runTest {
        val track = library.add(youtube.add("beat-it", "Beat It"))
        downloader.serve(youtube.audioUriOf("beat-it"), "0123456789")
        downloader.failAfterBytes = 4

        val outcomes = List(5) { download(track.id) }

        assertEquals(List(4) { DownloadTrack.Outcome.RetryLater } + DownloadTrack.Outcome.Done, outcomes)
        assertEquals(TrackPipelineState.Failed(PipelineStep.Downloading, CONNECTION_BROKE), tracks.get(track.id)?.pipeline)
        assertEquals("0123", files.partContent(files.downloadPart(track.id, "fake-audio")).decodeToString())
    }

    @Test
    fun `while waiting to retry the Track shows how far it got and why it stopped`() = runTest {
        val track = library.add(youtube.add("beat-it", "Beat It"))
        downloader.serve(youtube.audioUriOf("beat-it"), "0123456789")
        downloader.progressSteps = listOf(4L)
        downloader.failAfterBytes = 4

        download(track.id)

        assertEquals(TrackPipelineState.Downloading(0.4f, retryReason = CONNECTION_BROKE, failedAttempts = 1), tracks.get(track.id)?.pipeline)
    }

    @Test
    fun `a resumed download keeps showing its progress until new bytes arrive`() = runTest {
        val track = library.add(youtube.add("beat-it", "Beat It"))
        downloader.serve(youtube.audioUriOf("beat-it"), "0123456789")
        downloader.progressSteps = listOf(4L)
        downloader.failAfterBytes = 4
        download(track.id)
        downloader.failAfterBytes = null
        downloader.progressSteps = null
        var atStart: TrackPipelineState? = null
        downloader.beforeDownload = { atStart = tracks.get(track.id)?.pipeline }

        download(track.id)

        assertEquals(TrackPipelineState.Downloading(0.4f, retryReason = null, failedAttempts = 1), atStart)
    }

    @Test
    fun `a Track from a Source this app doesn't have ends Failed naming the Source`() = runTest {
        val soundcloud = FakeSource(SourceId("soundcloud"))
        val track = library.add(soundcloud.add("song", "Song"))

        download(track.id)

        assertEquals(
            TrackPipelineState.Failed(PipelineStep.Downloading, FailureReason(FailureKind.SourceNotInApp, "soundcloud")),
            tracks.get(track.id)?.pipeline,
        )
    }

    @Test
    fun `an unexpected error ends the download Failed`() = runTest {
        val track = library.add(youtube.add("beat-it", "Beat It"))
        youtube.resolveFailure = IllegalStateException("extractor broke")

        download(track.id)

        assertEquals(TrackPipelineState.Failed(PipelineStep.Downloading, FailureReason(FailureKind.Unexpected, "extractor broke")), tracks.get(track.id)?.pipeline)
    }

    @Test
    fun `a Track already downloaded is not downloaded again`() = runTest {
        val track = library.add(youtube.add("beat-it", "Beat It"))
        downloader.serve(youtube.audioUriOf("beat-it"), "0123456789")
        download(track.id)

        download(track.id) // e.g. the job ran again after process death

        assertEquals(listOf(0L), downloader.startedAt)
        assertEquals(TrackPipelineState.Downloaded, tracks.get(track.id)?.pipeline)
    }

    private companion object {
        /** What the fake downloader fails with. */
        val CONNECTION_BROKE = FailureReason(FailureKind.ConnectionBroke)
    }
}
