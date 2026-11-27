// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core

import eu.schicht8.meavox.core.library.ImportLocalFile
import eu.schicht8.meavox.core.library.TrackStorage
import eu.schicht8.meavox.core.mix.Mixes
import eu.schicht8.meavox.core.model.FailureKind
import eu.schicht8.meavox.core.model.FailureReason
import eu.schicht8.meavox.core.model.PipelineStep
import eu.schicht8.meavox.core.model.SeparationModelId
import eu.schicht8.meavox.core.model.StemsInUse
import eu.schicht8.meavox.core.model.Track
import eu.schicht8.meavox.core.model.TrackPipelineState
import eu.schicht8.meavox.core.pipeline.SeparateTrack
import eu.schicht8.meavox.core.pipeline.TrackPipeline
import eu.schicht8.meavox.core.pipeline.VocalFreeToggle
import eu.schicht8.meavox.core.port.SeparationFailed
import eu.schicht8.meavox.core.port.SeparationProgress
import eu.schicht8.meavox.core.session.BackingAudio
import eu.schicht8.meavox.core.session.Sessions
import eu.schicht8.meavox.core.session.backingAudio
import eu.schicht8.meavox.core.testing.FakeAudioEngine
import eu.schicht8.meavox.core.testing.FakeCoverArtLoader
import eu.schicht8.meavox.core.testing.FakeLocalFile
import eu.schicht8.meavox.core.testing.FakeLoudnessMeter
import eu.schicht8.meavox.core.testing.FakePipelineJobs
import eu.schicht8.meavox.core.testing.FakeSeparator
import eu.schicht8.meavox.core.testing.InMemoryMixRepository
import eu.schicht8.meavox.core.testing.InMemorySettingsRepository
import eu.schicht8.meavox.core.testing.InMemoryTakeRepository
import eu.schicht8.meavox.core.testing.InMemoryTrackFiles
import eu.schicht8.meavox.core.testing.InMemoryTrackRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** Re-separating a Ready Track keeps its previous Instrumental playing until the new one is there (#37, ADR 22). */
class ReseparationTest {
    private val tracks = InMemoryTrackRepository()
    private val files = InMemoryTrackFiles()
    private val jobs = FakePipelineJobs()
    private val separator = FakeSeparator()
    private val separate = SeparateTrack(tracks, files, separator)
    private val pipeline = TrackPipeline(tracks, jobs, unpickedModel = FAST)
    private val takes = InMemoryTakeRepository()
    private val mixRepository = InMemoryMixRepository()
    private val storage = TrackStorage(tracks, takes, mixRepository, files, jobs, MODELS)

    @Test
    fun `while a Ready Track is re-separated with another model, the Instrumental it was Ready with plays`() = runTest {
        val track = readyWithFast()

        pipeline.reseparate(track.id, BEST)

        val now = tracks.get(track.id)!!
        assertEquals(TrackPipelineState.Separating(BEST, previous = FAST), now.pipeline)
        assertEquals(BackingAudio.Instrumental(FAST), now.backingAudio())
        assertEquals(BEST, now.vocalRemovalModel)
        assertEquals(FakePipelineJobs.Separation(track.id, BEST), jobs.separations.last())
    }

    @Test
    fun `the previous Instrumental plays on while the new model reports progress, and while it waits to try again`() = runTest {
        val track = readyWithFast()
        pipeline.reseparate(track.id, BEST)
        val progress = SeparationProgress(SeparationProgress.Stage.Separating, 0.42f)
        separator.progress = listOf(progress)
        val playedMeanwhile = mutableListOf<SeparationModelId?>()
        separator.afterEachProgress = {
            runCurrent()
            playedMeanwhile += tracks.get(track.id)?.instrumentalModel
        }
        separator.failWith = SeparationFailed(FailureReason(FailureKind.Unexpected), retryable = true)

        assertEquals(SeparateTrack.Outcome.RetryLater, separate(track.id, BEST))

        assertEquals(listOf<SeparationModelId?>(FAST), playedMeanwhile)
        assertEquals(TrackPipelineState.Separating(BEST, progress, previous = FAST), tracks.get(track.id)?.pipeline)
    }

    @Test
    fun `when the new model fails, the previous Instrumental plays again, and the failure can be retried`() = runTest {
        val track = readyWithFast()
        pipeline.reseparate(track.id, BEST)
        separator.failWith = SeparationFailed(OUT_OF_MEMORY, retryable = false)

        assertEquals(SeparateTrack.Outcome.Failed, separate(track.id, BEST))

        val failed = tracks.get(track.id)!!
        assertEquals(TrackPipelineState.Failed(PipelineStep.Separating, OUT_OF_MEMORY, previous = FAST), failed.pipeline)
        assertEquals(BackingAudio.Instrumental(FAST), failed.backingAudio())

        pipeline.retry(track.id)

        assertEquals(TrackPipelineState.Separating(BEST, previous = FAST), tracks.get(track.id)?.pipeline)
        assertEquals(FakePipelineJobs.Separation(track.id, BEST), jobs.separations.last())
        separator.failWith = null
        assertEquals(SeparateTrack.Outcome.Ready, separate(track.id, BEST))
        assertEquals(TrackPipelineState.Ready(BEST), tracks.get(track.id)?.pipeline)
    }

    @Test
    fun `when the new model finishes, the Session switches to its Instrumental, but not under a Take, which stays aligned`() = runTest {
        val track = readyWithFast()
        val engine = FakeAudioEngine(
            listOf(files.originalAudio(track.id), files.stems(track.id, FAST).instrumental, files.stems(track.id, BEST).instrumental)
                .associateWith { 180_000L },
        )
        val settings = InMemorySettingsRepository()
        val sessions = Sessions(tracks, takes, files, engine, settings, Mixes(tracks, takes, mixRepository, files, FakeLoudnessMeter()))
        val session = sessions.open(track.id, backgroundScope)
        pipeline.reseparate(track.id, BEST)
        runCurrent()
        assertEquals(BackingAudio.Instrumental(FAST), session.backing.value)
        session.seekTo(30_000)
        session.record()

        assertEquals(SeparateTrack.Outcome.Ready, separate(track.id, BEST))
        runCurrent()

        assertEquals(TrackPipelineState.Ready(BEST), tracks.get(track.id)?.pipeline)
        assertEquals(BackingAudio.Instrumental(FAST), session.backing.value)
        engine.advance(10_000)
        session.stop()
        val take = session.keep()
        assertEquals(BackingAudio.Instrumental(BEST), session.backing.value)
        assertTrue(session.play(take.id))
        assertEquals(30_000, engine.playback.value.positionMs)
        assertEquals(30_000, take.startPositionMs)
    }

    @Test
    fun `re-separating back to the model that plays meanwhile makes the Track Ready with it, without separating again`() = runTest {
        val running = readyWithFast()
        pipeline.reseparate(running.id, BEST)
        val failed = readyWithFast("other.mp3")
        pipeline.reseparate(failed.id, BEST)
        separator.failWith = SeparationFailed(OUT_OF_MEMORY, retryable = false)
        separate(failed.id, BEST)
        val queued = jobs.separations.toList()

        pipeline.reseparate(running.id, FAST)
        pipeline.reseparate(failed.id, FAST)

        assertEquals(TrackPipelineState.Ready(FAST), tracks.get(running.id)?.pipeline)
        assertEquals(TrackPipelineState.Ready(FAST), tracks.get(failed.id)?.pipeline)
        assertEquals(FAST, tracks.get(running.id)?.separationModel)
        // The running job is stopped; its checkpoint stays (#28).
        assertEquals(listOf(running.id), jobs.cancelledSeparations)
        assertEquals(queued, jobs.separations)
        // The stopped job can't write over the Track any more.
        separator.failWith = null
        assertEquals(SeparateTrack.Outcome.Skipped, separate(running.id, BEST))
        assertEquals(TrackPipelineState.Ready(FAST), tracks.get(running.id)?.pipeline)
    }

    @Test
    fun `re-separating with a third model while one runs keeps the Instrumental that plays`() = runTest {
        val track = readyWithFast()
        pipeline.reseparate(track.id, BEST)

        pipeline.reseparate(track.id, MEDIUM)

        assertEquals(TrackPipelineState.Separating(MEDIUM, previous = FAST), tracks.get(track.id)?.pipeline)
    }

    @Test
    fun `a first Separation, or stems that were deleted, leave nothing to play meanwhile`() = runTest {
        val first = ImportLocalFile(tracks, files, FakeCoverArtLoader())(FakeLocalFile(displayName = "a.mp3", content = "audio-a"))
        tracks.updatePipeline(first.id, TrackPipelineState.Separating(FAST, SeparationProgress(SeparationProgress.Stage.Separating, 0.5f)))
        val deleted = readyWithFast("b.mp3")
        tracks.updatePipeline(deleted.id, TrackPipelineState.StemsDeleted(FAST))

        pipeline.reseparate(first.id, BEST)
        pipeline.reseparate(deleted.id, BEST)

        assertEquals(TrackPipelineState.Separating(BEST), tracks.get(first.id)?.pipeline)
        assertEquals(TrackPipelineState.Separating(BEST), tracks.get(deleted.id)?.pipeline)
        assertEquals(BackingAudio.OriginalAudio, tracks.get(deleted.id)!!.backingAudio())
    }

    @Test
    fun `marking the Track Vocal-Free while it is re-separated still makes it Ready without stems`() = runTest {
        val track = readyWithFast()
        pipeline.reseparate(track.id, BEST)

        VocalFreeToggle(tracks, files, jobs, mixRepository, MODELS).mark(track.id)

        assertEquals(TrackPipelineState.Ready(model = null), tracks.get(track.id)?.pipeline)
        assertEquals(BackingAudio.OriginalAudio, tracks.get(track.id)!!.backingAudio())
    }

    @Test
    fun `the storage screen won't delete the stems that play while the Track is re-separated, or after that failed`() = runTest {
        val running = readyWithFast()
        pipeline.reseparate(running.id, BEST)
        val failed = readyWithFast("other.mp3")
        pipeline.reseparate(failed.id, BEST)
        separator.failWith = SeparationFailed(OUT_OF_MEMORY, retryable = false)
        separate(failed.id, BEST)

        assertEquals(StemsInUse.Playing, tracks.get(running.id)!!.stemsInUse(FAST))
        assertEquals(StemsInUse.BeingMade, tracks.get(running.id)!!.stemsInUse(BEST))
        assertEquals(StemsInUse.Playing, tracks.get(failed.id)!!.stemsInUse(FAST))
        assertNull(tracks.get(failed.id)!!.stemsInUse(BEST))
        assertFailsWith<IllegalStateException> { storage.deleteStems(running.id, FAST) }
        assertFailsWith<IllegalStateException> { storage.deleteStems(failed.id, FAST) }
        assertEquals(TrackPipelineState.Failed(PipelineStep.Separating, OUT_OF_MEMORY, previous = FAST), tracks.get(failed.id)?.pipeline)
        // Once it is Ready again, they can go as before (ADR 17).
        pipeline.reseparate(failed.id, FAST)
        assertNull(tracks.get(failed.id)!!.stemsInUse(FAST))
        storage.deleteStems(failed.id, FAST)
        assertEquals(TrackPipelineState.StemsDeleted(FAST), tracks.get(failed.id)?.pipeline)
    }

    private suspend fun readyWithFast(name: String = "song.mp3"): Track {
        val track = ImportLocalFile(tracks, files, FakeCoverArtLoader())(FakeLocalFile(displayName = name, content = "audio-$name"))
        return tracks.updatePipeline(track.id, TrackPipelineState.Ready(FAST))
    }

    private companion object {
        val FAST = SeparationModelId("fast")
        val MEDIUM = SeparationModelId("medium")
        val BEST = SeparationModelId("best")
        val MODELS = listOf(FAST, MEDIUM, BEST)
        val OUT_OF_MEMORY = FailureReason(FailureKind.OutOfMemory)
    }
}
