// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core

import eu.schicht8.meavox.core.library.ImportLocalFile
import eu.schicht8.meavox.core.model.FailureKind
import eu.schicht8.meavox.core.model.FailureReason
import eu.schicht8.meavox.core.model.PipelineStep
import eu.schicht8.meavox.core.model.SeparationModelId
import eu.schicht8.meavox.core.model.TrackPipelineState
import eu.schicht8.meavox.core.pipeline.SeparateTrack
import eu.schicht8.meavox.core.pipeline.TrackPipeline
import eu.schicht8.meavox.core.pipeline.VocalFreeToggle
import eu.schicht8.meavox.core.port.SeparationFailed
import eu.schicht8.meavox.core.port.SeparationProgress
import eu.schicht8.meavox.core.testing.FakeLocalFile
import eu.schicht8.meavox.core.testing.FakePipelineJobs
import eu.schicht8.meavox.core.testing.FakeSeparator
import eu.schicht8.meavox.core.testing.InMemoryMixRepository
import eu.schicht8.meavox.core.testing.InMemoryTrackFiles
import eu.schicht8.meavox.core.testing.InMemoryTrackRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

class SeparateTrackTest {
    private val tracks = InMemoryTrackRepository()
    private val files = InMemoryTrackFiles()
    private val separator = FakeSeparator()
    private val import = ImportLocalFile(tracks, files)
    private val separate = SeparateTrack(tracks, files, separator)
    private val fast = SeparationModelId("fast")

    @Test
    fun `separating a Downloaded Track stores its stems for the model and makes it Ready`() = runTest {
        val track = import(FakeLocalFile(displayName = "song.mp3", content = "audio-1"))

        separate(track.id, fast)

        val expected = FakeSeparator.Request(
            originalAudio = files.originalAudio(track.id),
            model = fast,
            into = files.stems(track.id, fast),
            checkpoint = files.separationCheckpoint(track.id, fast),
        )
        assertEquals(listOf(expected), separator.requests)
        assertEquals(TrackPipelineState.Ready(fast), tracks.get(track.id)?.pipeline)
    }

    @Test
    fun `while separating the Track shows the progress the Separator reports`() = runTest {
        val track = import(FakeLocalFile(displayName = "song.mp3", content = "audio-1"))
        val reported = listOf(
            SeparationProgress(SeparationProgress.Stage.DownloadingModel, 0.5f),
            SeparationProgress(SeparationProgress.Stage.Separating, 0.25f),
        )
        separator.progress = reported
        val shown = mutableListOf<TrackPipelineState>()
        separator.afterEachProgress = { progress ->
            shown += withTimeout(1_000) {
                tracks.observe(track.id).map { it?.pipeline }.first { it == TrackPipelineState.Separating(fast, progress) }!!
            }
        }

        separate(track.id, fast)

        assertEquals<List<TrackPipelineState>>(reported.map { TrackPipelineState.Separating(fast, it) }, shown)
        assertEquals(TrackPipelineState.Ready(fast), tracks.get(track.id)?.pipeline)
    }

    @Test
    fun `a Separation that cannot succeed leaves the Track Failed with the reason`() = runTest {
        val track = import(FakeLocalFile(displayName = "song.mp3", content = "audio-1"))
        separator.failWith = SeparationFailed(UNDECODABLE, retryable = false)

        val outcome = separate(track.id, fast)

        assertEquals(SeparateTrack.Outcome.Failed, outcome)
        assertEquals(
            TrackPipelineState.Failed(PipelineStep.Separating, UNDECODABLE),
            tracks.get(track.id)?.pipeline,
        )
    }

    @Test
    fun `a Separation that may succeed later keeps the Track Separating and asks to retry`() = runTest {
        val track = import(FakeLocalFile(displayName = "song.mp3", content = "audio-1"))
        val lastProgress = SeparationProgress(SeparationProgress.Stage.DownloadingModel, 0.4f)
        separator.progress = listOf(lastProgress)
        separator.failWith = SeparationFailed(FailureReason(FailureKind.ModelDownloadFailed, "network unreachable"), retryable = true)

        val outcome = separate(track.id, fast)

        assertEquals(SeparateTrack.Outcome.RetryLater, outcome)
        assertEquals(TrackPipelineState.Separating(fast, lastProgress), tracks.get(track.id)?.pipeline)
    }

    @Test
    fun `a Track already Ready with the model is not separated again`() = runTest {
        val track = import(FakeLocalFile(displayName = "song.mp3", content = "audio-1"))
        separate(track.id, fast)

        val outcome = separate(track.id, fast) // e.g. the job ran again after process death

        assertEquals(SeparateTrack.Outcome.Ready, outcome)
        assertEquals(1, separator.requests.size)
        assertEquals(TrackPipelineState.Ready(fast), tracks.get(track.id)?.pipeline)
    }

    @Test
    fun `a Separation that runs out of memory leaves the Track Failed instead of stuck Separating`() = runTest {
        val track = import(FakeLocalFile(displayName = "song.mp3", content = "audio-1"))
        separator.failWith = OutOfMemoryError("Failed to allocate 536870912 bytes")

        val outcome = separate(track.id, fast)

        assertEquals(SeparateTrack.Outcome.Failed, outcome)
        assertEquals(
            TrackPipelineState.Failed(PipelineStep.Separating, FailureReason(FailureKind.OutOfMemory)),
            tracks.get(track.id)?.pipeline,
        )
    }

    @Test
    fun `a job that runs after the Track's Separation already failed leaves it Failed`() = runTest {
        val track = import(FakeLocalFile(displayName = "song.mp3", content = "audio-1"))
        val failed = TrackPipelineState.Failed(PipelineStep.Separating, FailureReason(FailureKind.AudioUndecodable))
        tracks.updatePipeline(track.id, failed)

        val outcome = separate(track.id, fast) // e.g. queued behind the job that failed

        assertEquals(SeparateTrack.Outcome.Failed, outcome)
        assertEquals(emptyList(), separator.requests)
        assertEquals(failed, tracks.get(track.id)?.pipeline)
    }

    @Test
    fun `the Track shows progress in whole percent, however finely the Separator reports it`() = runTest {
        val track = import(FakeLocalFile(displayName = "song.mp3", content = "audio-1"))
        separator.progress = listOf(SeparationProgress(SeparationProgress.Stage.DownloadingModel, 0.4267f))
        val expected = TrackPipelineState.Separating(fast, SeparationProgress(SeparationProgress.Stage.DownloadingModel, 0.42f))
        var shown: TrackPipelineState? = null
        separator.afterEachProgress = {
            shown = withTimeout(1_000) { tracks.observe(track.id).map { it?.pipeline }.first { it is TrackPipelineState.Separating && it.progress != null } }
        }

        separate(track.id, fast)

        assertEquals(expected, shown)
    }

    @Test
    fun `a Separation still running when its Track is marked Vocal-Free writes nothing over it`() = runTest {
        val track = import(FakeLocalFile(displayName = "karaoke.mp3", content = "audio-1"))
        val vocalFree = VocalFreeToggle(tracks, files, FakePipelineJobs(), InMemoryMixRepository(), models = listOf(fast))
        separator.progress = listOf(0.2f, 0.5f, 0.9f).map { SeparationProgress(SeparationProgress.Stage.Separating, it) }
        // The user marks the Track while the job is at 20 %; the job doesn't stop at once.
        separator.afterEachProgress = { if (it.fraction == 0.2f) vocalFree.mark(track.id) }

        val outcome = separate(track.id, fast)

        assertEquals(SeparateTrack.Outcome.Skipped, outcome)
        assertEquals(TrackPipelineState.Ready(model = null), tracks.get(track.id)?.pipeline)
    }

    @Test
    fun `a Separation still running when its Track is re-separated with another model writes nothing over it`() = runTest {
        val best = SeparationModelId("best")
        val track = import(FakeLocalFile(displayName = "song.mp3", content = "audio-1"))
        val pipeline = TrackPipeline(tracks, FakePipelineJobs(), unpickedModel = fast)
        separator.progress = listOf(0.2f, 0.5f, 0.9f).map { SeparationProgress(SeparationProgress.Stage.Separating, it) }
        // The user picks Best while Fast is at 20 %; the Fast job doesn't stop at once.
        separator.afterEachProgress = { if (it.fraction == 0.2f) pipeline.reseparate(track.id, best) }

        val outcome = separate(track.id, fast)

        assertEquals(SeparateTrack.Outcome.Skipped, outcome)
        assertEquals(TrackPipelineState.Separating(best), tracks.get(track.id)?.pipeline)
    }

    @Test
    fun `after a model switch the new Separation waits until the replaced one has stopped`() = runTest {
        val best = SeparationModelId("best")
        val track = import(FakeLocalFile(displayName = "song.mp3", content = "audio-1"))
        val pipeline = TrackPipeline(tracks, FakePipelineJobs(), unpickedModel = fast)
        // The Fast job is slow to stop: it is stuck in its current block until released.
        val fastStops = CompletableDeferred<Unit>()
        separator.afterEachProgress = { if (separator.requests.last().model == fast) fastStops.await() }
        val fastJob = async { separate(track.id, fast) }
        runCurrent()

        pipeline.reseparate(track.id, best)
        val bestJob = async { separate(track.id, best) }
        runCurrent()
        // Two Separations of one Track never run at once.
        assertEquals(listOf(fast), separator.requests.map { it.model })

        fastStops.complete(Unit)

        assertEquals(SeparateTrack.Outcome.Skipped, fastJob.await())
        assertEquals(SeparateTrack.Outcome.Ready, bestJob.await())
        assertEquals(listOf(fast, best), separator.requests.map { it.model })
        assertEquals(TrackPipelineState.Ready(best), tracks.get(track.id)?.pipeline)
    }

    private companion object {
        val UNDECODABLE = FailureReason(FailureKind.AudioUndecodable, "no decoder for audio/x-weird")
    }
}
