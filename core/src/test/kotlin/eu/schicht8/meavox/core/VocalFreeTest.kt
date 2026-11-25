// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core

import eu.schicht8.meavox.core.mix.Mix
import eu.schicht8.meavox.core.mix.MixId
import eu.schicht8.meavox.core.mix.MixSettings
import eu.schicht8.meavox.core.mix.Mixes
import eu.schicht8.meavox.core.model.FailureKind
import eu.schicht8.meavox.core.model.FailureReason
import eu.schicht8.meavox.core.model.PipelineStep
import eu.schicht8.meavox.core.model.SeparationModelId
import eu.schicht8.meavox.core.model.SourceId
import eu.schicht8.meavox.core.model.TakeId
import eu.schicht8.meavox.core.model.Track
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.model.TrackPipelineState
import eu.schicht8.meavox.core.pipeline.SeparateTrack
import eu.schicht8.meavox.core.pipeline.TrackPipeline
import eu.schicht8.meavox.core.pipeline.VocalFreeToggle
import eu.schicht8.meavox.core.port.SeparationProgress
import eu.schicht8.meavox.core.session.BackingAudio
import eu.schicht8.meavox.core.session.Sessions
import eu.schicht8.meavox.core.session.backingAudio
import eu.schicht8.meavox.core.testing.FakeAudioEngine
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class VocalFreeTest {
    private val tracks = InMemoryTrackRepository()
    private val files = InMemoryTrackFiles()
    private val jobs = FakePipelineJobs()
    private val mixes = InMemoryMixRepository()
    private val vocalFree = VocalFreeToggle(tracks, files, jobs, mixes, models = listOf(FAST, BEST))
    private val pipeline = TrackPipeline(tracks, jobs, unpickedModel = FAST)
    private val id = TrackId(SourceId.Local, "abc")

    private suspend fun addTrack(pipeline: TrackPipelineState, vocalFree: Boolean = false) =
        tracks.addIfAbsent(Track(id, "Fly Me to the Moon (Karaoke)", null, 148_000, pipeline, vocalFree = vocalFree))

    private fun TestScope.startPipeline() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { pipeline.run() }
    }

    @Test
    fun `marking a Separating Track cancels the Separation, drops its partial stems and makes it Ready`() = runTest {
        addTrack(TrackPipelineState.Separating(FAST, SeparationProgress(SeparationProgress.Stage.Separating, 0.4f)))
        files.store(files.separationCheckpoint(id, FAST), 20_000_000)

        vocalFree.mark(id)

        assertEquals(listOf(id), jobs.cancelledSeparations)
        assertEquals(0, files.sizeOf(files.separationCheckpoint(id, FAST)))
        assertEquals(TrackPipelineState.Ready(model = null), tracks.get(id)?.pipeline)
        assertEquals(true, tracks.get(id)?.vocalFree)
    }

    @Test
    fun `marking a Track whose Separation failed makes it Ready and drops what the Separation left`() = runTest {
        addTrack(TrackPipelineState.Failed(PipelineStep.Separating, FailureReason(FailureKind.OutOfMemory)))
        files.store(files.separationCheckpoint(id, FAST), 20_000_000)

        vocalFree.mark(id)

        assertEquals(TrackPipelineState.Ready(model = null), tracks.get(id)?.pipeline)
        assertEquals(emptyList(), jobs.cancelledSeparations)
        assertEquals(0, files.sizeOf(files.separationCheckpoint(id, FAST)))
    }

    @Test
    fun `marking a Track with stems names the space freed, then deletes them`() = runTest {
        addTrack(TrackPipelineState.Ready(FAST))
        files.store(files.stems(id, FAST).instrumental, 30_000_000)
        files.store(files.stems(id, FAST).vocalStem, 25_000_000)

        assertEquals(55_000_000, vocalFree.stemBytes(id))
        vocalFree.mark(id)

        assertEquals(TrackPipelineState.Ready(model = null), tracks.get(id)?.pipeline)
        assertEquals(0, files.sizeOf(files.stems(id, FAST).instrumental))
        assertEquals(0, vocalFree.stemBytes(id))
    }

    @Test
    fun `marking a Track whose stems were deleted makes it Ready, and opening it then needs no stems`() = runTest {
        addTrack(TrackPipelineState.StemsDeleted(FAST))
        val engine = FakeAudioEngine()

        vocalFree.mark(id)
        Sessions(tracks, InMemoryTakeRepository(), files, engine, InMemorySettingsRepository(), Mixes(tracks, InMemoryTakeRepository(), InMemoryMixRepository(), files, FakeLoudnessMeter())).open(id, backgroundScope)

        assertEquals(TrackPipelineState.Ready(model = null), tracks.get(id)?.pipeline)
        assertEquals(files.originalAudio(id), engine.playback.value.loaded)
        assertEquals(emptyList(), jobs.separations)
    }

    @Test
    fun `marking a Track before its download finishes skips its Separation`() = runTest {
        addTrack(TrackPipelineState.Added)
        vocalFree.mark(id)
        startPipeline()

        tracks.updatePipeline(id, TrackPipelineState.Downloaded)

        assertEquals(TrackPipelineState.Ready(model = null), tracks.get(id)?.pipeline)
        assertEquals(emptyList(), jobs.separations)
    }

    @Test
    fun `unmarking a Ready Track separates it with the model picked for it, then it plays the Instrumental`() = runTest {
        addTrack(TrackPipelineState.Ready(model = null), vocalFree = true)

        vocalFree.unmark(id, BEST)

        assertEquals(false, tracks.get(id)?.vocalFree)
        assertEquals(TrackPipelineState.Separating(BEST), tracks.get(id)?.pipeline)
        assertEquals(listOf(FakePipelineJobs.Separation(id, BEST)), jobs.separations)
        assertEquals(BackingAudio.OriginalAudio, tracks.get(id)!!.backingAudio())

        SeparateTrack(tracks, files, FakeSeparator())(id, BEST) // the queued job runs

        assertEquals(TrackPipelineState.Ready(BEST), tracks.get(id)?.pipeline)
        assertEquals(BackingAudio.Instrumental(BEST), tracks.get(id)!!.backingAudio())
    }

    @Test
    fun `stems a cancelled Separation hadn't stopped writing are not deleted once the Track was unmarked again`() = runTest {
        addTrack(TrackPipelineState.Separating(FAST))
        // The user turns the toggle off again while marking still waits for the old job to stop.
        jobs.onCancelSeparation = {
            vocalFree.unmark(id, FAST)
            files.store(files.separationCheckpoint(id, FAST), 5_000_000) // the new Separation's work
        }

        vocalFree.mark(id)

        assertEquals(TrackPipelineState.Separating(FAST), tracks.get(id)?.pipeline)
        assertEquals(5_000_000, files.sizeOf(files.separationCheckpoint(id, FAST)))
    }

    @Test
    fun `marking a Track switches its Mixes on the Instrumental to the Original Audio, which keeps them aligned`() = runTest {
        addTrack(TrackPipelineState.Ready(FAST))
        mixes.add(mix("m1", BackingAudio.Instrumental(FAST)))
        mixes.add(mix("m2", BackingAudio.OriginalAudio))

        vocalFree.mark(id)

        assertEquals(listOf(BackingAudio.OriginalAudio, BackingAudio.OriginalAudio), mixes.observe(id).first().map { it.backing })
        assertEquals(listOf(MIX_SETTINGS, MIX_SETTINGS), mixes.observe(id).first().map { it.settings })
    }

    @Test
    fun `unmarking a Track leaves its Mixes on the Original Audio`() = runTest {
        addTrack(TrackPipelineState.Ready(model = null), vocalFree = true)
        mixes.add(mix("m1", BackingAudio.OriginalAudio))

        vocalFree.unmark(id, FAST)
        SeparateTrack(tracks, files, FakeSeparator())(id, FAST)

        assertEquals(BackingAudio.OriginalAudio, mixes.get(MixId("m1"))?.backing)
    }

    private fun mix(id: String, backing: BackingAudio) =
        Mix(MixId(id), this.id, TakeId("t1"), "Mix $id", backing, MIX_SETTINGS, createdAt = id.hashCode().toLong())

    private companion object {
        val MIX_SETTINGS = MixSettings(backingDb = -20f, vocalDb = 2f, vocalNudgeMs = 10)
        val FAST = SeparationModelId("fast")
        val BEST = SeparationModelId("best")
    }
}
