// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core

import eu.schicht8.meavox.core.library.ImportLocalFile
import eu.schicht8.meavox.core.library.Library
import eu.schicht8.meavox.core.library.TrackRepository
import eu.schicht8.meavox.core.mix.Mixes
import eu.schicht8.meavox.core.model.FailureKind
import eu.schicht8.meavox.core.model.FailureReason
import eu.schicht8.meavox.core.model.PipelineStep
import eu.schicht8.meavox.core.model.SeparationModelId
import eu.schicht8.meavox.core.model.SourceId
import eu.schicht8.meavox.core.model.Track
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.model.TrackPipelineState
import eu.schicht8.meavox.core.pipeline.DefaultSeparationModel
import eu.schicht8.meavox.core.pipeline.TrackPipeline
import eu.schicht8.meavox.core.pipeline.VocalFreeToggle
import eu.schicht8.meavox.core.port.SeparationProgress
import eu.schicht8.meavox.core.session.BackingAudio
import eu.schicht8.meavox.core.session.Sessions
import eu.schicht8.meavox.core.testing.FakeAudioEngine
import eu.schicht8.meavox.core.testing.FakeCoverArtLoader
import eu.schicht8.meavox.core.testing.FakeLocalFile
import eu.schicht8.meavox.core.testing.FakeLoudnessMeter
import eu.schicht8.meavox.core.testing.FakePipelineJobs
import eu.schicht8.meavox.core.testing.FakeSource
import eu.schicht8.meavox.core.testing.InMemoryMixRepository
import eu.schicht8.meavox.core.testing.InMemorySettingsRepository
import eu.schicht8.meavox.core.testing.InMemoryTakeRepository
import eu.schicht8.meavox.core.testing.InMemoryTrackFiles
import eu.schicht8.meavox.core.testing.InMemoryTrackRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class TrackPipelineTest {
    private val tracks = InMemoryTrackRepository()
    private val files = InMemoryTrackFiles()
    private val jobs = FakePipelineJobs()
    private val import = ImportLocalFile(tracks, files, FakeCoverArtLoader())
    private val fast = SeparationModelId("fast")
    private val youtube = SourceId("youtube")
    private val medium = SeparationModelId("medium")
    private val best = SeparationModelId("best")
    private val settings = InMemorySettingsRepository()
    private val defaultModel = DefaultSeparationModel(settings, listOf(fast, medium, best), fallback = fast)

    /** Runs once, after the Track Pipeline saw a Library change and before it updates a Track. */
    private var beforeUpdate: (suspend () -> Unit)? = null
    private val pipeline = TrackPipeline(
        object : TrackRepository by tracks {
            override suspend fun update(id: TrackId, change: (Track) -> Track?): Track? {
                beforeUpdate?.also { beforeUpdate = null }?.invoke()
                return tracks.update(id, change)
            }
        },
        jobs,
        unpickedModel = fast,
        downloadFrom = setOf(youtube),
    )

    private fun TestScope.startPipeline() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { pipeline.run() }
    }

    @Test
    fun `a Downloaded Track is queued for Separation with the default model`() = runTest {
        startPipeline()

        val track = import(FakeLocalFile(displayName = "song.mp3", content = "audio-1"))

        assertEquals(listOf(FakePipelineJobs.Separation(track.id, fast)), jobs.separations)
        assertEquals(TrackPipelineState.Separating(fast), tracks.get(track.id)?.pipeline)
    }

    @Test
    fun `a Downloaded Track is separated with the model chosen when it was added, not the default`() = runTest {
        defaultModel.set(medium)
        startPipeline()

        val track = import(FakeLocalFile(displayName = "song.mp3", content = "audio-1"), separationModel = best)

        assertEquals(listOf(FakePipelineJobs.Separation(track.id, best)), jobs.separations)
        assertEquals(TrackPipelineState.Separating(best), tracks.get(track.id)?.pipeline)
    }

    @Test
    fun `a Track without a picked model is separated with Fast, whatever the Default Separation Model is`() = runTest {
        // Best may not be downloaded, and setting the default asks no download consent (ADR 19).
        defaultModel.set(best)
        startPipeline()

        // E.g. added before models could be picked.
        val track = import(FakeLocalFile(displayName = "song.mp3", content = "audio-1"))

        assertEquals(TrackPipelineState.Separating(fast), tracks.get(track.id)?.pipeline)
        assertEquals(listOf(FakePipelineJobs.Separation(track.id, fast)), jobs.separations)
    }

    @Test
    fun `at start a Track left Separating by an earlier process is queued again once with its model`() = runTest {
        val interrupted = import(FakeLocalFile(displayName = "a.mp3", content = "audio-a"))
        tracks.updatePipeline(interrupted.id, TrackPipelineState.Separating(best, SeparationProgress(SeparationProgress.Stage.Separating, 0.3f)))
        val ready = import(FakeLocalFile(displayName = "b.mp3", content = "audio-b"))
        tracks.updatePipeline(ready.id, TrackPipelineState.Ready(fast))
        val failed = import(FakeLocalFile(displayName = "c.mp3", content = "audio-c"))
        tracks.updatePipeline(failed.id, TrackPipelineState.Failed(PipelineStep.Separating, FailureReason(FailureKind.AudioUndecodable)))

        startPipeline()
        // The running job reports progress, which changes the Track again.
        tracks.updatePipeline(interrupted.id, TrackPipelineState.Separating(best, SeparationProgress(SeparationProgress.Stage.Separating, 0.4f)))

        assertEquals(listOf(FakePipelineJobs.Separation(interrupted.id, best)), jobs.separations)
        assertEquals(TrackPipelineState.Ready(fast), tracks.get(ready.id)?.pipeline)
        assertEquals(TrackPipelineState.Failed(PipelineStep.Separating, FailureReason(FailureKind.AudioUndecodable)), tracks.get(failed.id)?.pipeline)
    }

    @Test
    fun `retrying the failed Separation of a Track without a picked model uses Fast too`() = runTest {
        defaultModel.set(best)
        startPipeline()
        val track = import(FakeLocalFile(displayName = "song.mp3", content = "audio-1"))
        tracks.updatePipeline(track.id, TrackPipelineState.Failed(PipelineStep.Separating, FailureReason(FailureKind.OutOfMemory)))

        pipeline.retry(track.id)

        assertEquals(List(2) { FakePipelineJobs.Separation(track.id, fast) }, jobs.separations)
        assertEquals(TrackPipelineState.Separating(fast), tracks.get(track.id)?.pipeline)
    }

    @Test
    fun `an added Track of a network Source is queued for download, an unfinished local import is not`() = runTest {
        startPipeline()

        val found = tracks.addIfAbsent(Track(TrackId(youtube, "beat-it"), "Beat It", null, null, TrackPipelineState.Added))
        val unfinishedImport = tracks.addIfAbsent(Track(TrackId(SourceId.Local, "abc"), "Song", null, null, TrackPipelineState.Added))

        assertEquals(listOf(found.id), jobs.downloads)
        assertEquals(TrackPipelineState.Downloading(), tracks.get(found.id)?.pipeline)
        assertEquals(TrackPipelineState.Added, tracks.get(unfinishedImport.id)?.pipeline)
    }

    @Test
    fun `at start an interrupted download is queued again once, and a failed one can be retried`() = runTest {
        val interrupted = tracks.addIfAbsent(Track(TrackId(youtube, "a"), "A", null, null, TrackPipelineState.Downloading(0.4f)))
        val failed = tracks.addIfAbsent(
            Track(TrackId(youtube, "b"), "B", null, null, TrackPipelineState.Failed(PipelineStep.Downloading, FailureReason(FailureKind.ConnectionBroke))),
        )

        startPipeline()
        tracks.updatePipeline(interrupted.id, TrackPipelineState.Downloading(0.5f)) // its job reports progress
        pipeline.retry(failed.id)

        assertEquals(listOf(interrupted.id, failed.id), jobs.downloads)
        assertEquals(TrackPipelineState.Downloading(), tracks.get(failed.id)?.pipeline)
    }

    @Test
    fun `a Track whose stems were deleted is separated again with its model only once it is opened`() = runTest {
        val track = import(FakeLocalFile(displayName = "song.mp3", content = "audio-1"))
        tracks.updatePipeline(track.id, TrackPipelineState.StemsDeleted(best))
        startPipeline()
        assertEquals(emptyList(), jobs.separations)
        val engine = FakeAudioEngine()
        val sessions = Sessions(tracks, InMemoryTakeRepository(), files, engine, InMemorySettingsRepository(), Mixes(tracks, InMemoryTakeRepository(), InMemoryMixRepository(), files, FakeLoudnessMeter()))

        val session = sessions.open(track.id, backgroundScope)

        // The Original Audio plays until the stems are back.
        assertEquals(BackingAudio.OriginalAudio, session.backing.value)
        assertEquals(files.originalAudio(track.id), engine.playback.value.loaded)
        assertEquals(TrackPipelineState.Separating(best), tracks.get(track.id)?.pipeline)
        assertEquals(listOf(FakePipelineJobs.Separation(track.id, best)), jobs.separations)
    }

    @Test
    fun `a file imported Vocal-Free goes Downloaded to Ready without Separating`() = runTest {
        startPipeline()

        val track = import(FakeLocalFile(displayName = "karaoke.mp3", content = "audio-1"), vocalFree = true)

        assertEquals(TrackPipelineState.Ready(model = null), tracks.get(track.id)?.pipeline)
        assertEquals(true, tracks.get(track.id)?.vocalFree)
        assertEquals(emptyList(), jobs.separations)
    }

    @Test
    fun `a search result added Vocal-Free is downloaded and then becomes Ready without Separating`() = runTest {
        startPipeline()
        val library = Library(tracks, InMemoryTakeRepository(), InMemoryTrackFiles())
        val source = FakeSource(youtube).apply { add(sourceTrackId = "karaoke", title = "Fly Me to the Moon (Karaoke)") }

        val track = library.add(source.search("karaoke").single(), vocalFree = true)
        assertEquals(listOf(track.id), jobs.downloads)
        tracks.updatePipeline(track.id, TrackPipelineState.Downloaded) // the download finished

        assertEquals(TrackPipelineState.Ready(model = null), tracks.get(track.id)?.pipeline)
        assertEquals(emptyList(), jobs.separations)
    }

    @Test
    fun `re-separating with another model while one runs replaces the running Separation`() = runTest {
        startPipeline()
        val track = import(FakeLocalFile(displayName = "song.mp3", content = "audio-1"))
        tracks.updatePipeline(track.id, TrackPipelineState.Separating(fast, SeparationProgress(SeparationProgress.Stage.Separating, 0.4f)))
        // While the running job is being stopped, the Track already waits for the new model, so the
        // stopping job can't write over it, and the new job isn't queued yet, so it isn't cancelled too.
        jobs.onCancelSeparation = {
            assertEquals(TrackPipelineState.Separating(best), tracks.get(track.id)?.pipeline)
            assertEquals(listOf(FakePipelineJobs.Separation(track.id, fast)), jobs.separations)
        }

        pipeline.reseparate(track.id, best)

        assertEquals(listOf(track.id), jobs.cancelledSeparations)
        assertEquals(listOf(FakePipelineJobs.Separation(track.id, fast), FakePipelineJobs.Separation(track.id, best)), jobs.separations)
        assertEquals(TrackPipelineState.Separating(best), tracks.get(track.id)?.pipeline)
    }

    @Test
    fun `re-separating with the model that is running keeps the running Separation`() = runTest {
        startPipeline()
        val track = import(FakeLocalFile(displayName = "song.mp3", content = "audio-1"))
        val running = TrackPipelineState.Separating(fast, SeparationProgress(SeparationProgress.Stage.Separating, 0.4f))
        tracks.updatePipeline(track.id, running)

        pipeline.reseparate(track.id, fast)

        assertEquals(emptyList(), jobs.cancelledSeparations)
        assertEquals(listOf(FakePipelineJobs.Separation(track.id, fast)), jobs.separations)
        assertEquals(running, tracks.get(track.id)?.pipeline)
    }

    @Test
    fun `a Track whose stems were deleted or whose Separation failed can be re-separated with another model`() = runTest {
        startPipeline()
        val deleted = import(FakeLocalFile(displayName = "a.mp3", content = "audio-a"))
        tracks.updatePipeline(deleted.id, TrackPipelineState.StemsDeleted(fast))
        val failed = import(FakeLocalFile(displayName = "b.mp3", content = "audio-b"))
        tracks.updatePipeline(failed.id, TrackPipelineState.Failed(PipelineStep.Separating, FailureReason(FailureKind.OutOfMemory)))

        pipeline.reseparate(deleted.id, medium)
        pipeline.reseparate(failed.id, medium)

        assertEquals(TrackPipelineState.Separating(medium), tracks.get(deleted.id)?.pipeline)
        assertEquals(TrackPipelineState.Separating(medium), tracks.get(failed.id)?.pipeline)
    }

    @Test
    fun `retrying a failed re-separation uses the model it was re-separated with`() = runTest {
        startPipeline()
        val track = import(FakeLocalFile(displayName = "song.mp3", content = "audio-1"))
        tracks.updatePipeline(track.id, TrackPipelineState.Ready(fast))
        pipeline.reseparate(track.id, best)
        tracks.updatePipeline(track.id, TrackPipelineState.Failed(PipelineStep.Separating, FailureReason(FailureKind.OutOfMemory)))

        pipeline.retry(track.id)

        assertEquals(TrackPipelineState.Separating(best), tracks.get(track.id)?.pipeline)
    }

    @Test
    fun `re-separating with the model a Track is Ready with, or a Vocal-Free Track, changes nothing`() = runTest {
        startPipeline()
        val ready = import(FakeLocalFile(displayName = "a.mp3", content = "audio-a"))
        tracks.updatePipeline(ready.id, TrackPipelineState.Ready(fast))
        val vocalFree = import(FakeLocalFile(displayName = "karaoke.mp3", content = "audio-b"), vocalFree = true)
        val queued = jobs.separations.toList()

        pipeline.reseparate(ready.id, fast)
        pipeline.reseparate(vocalFree.id, best)

        assertEquals(TrackPipelineState.Ready(fast), tracks.get(ready.id)?.pipeline)
        assertEquals(TrackPipelineState.Ready(model = null), tracks.get(vocalFree.id)?.pipeline)
        assertEquals(queued, jobs.separations)
    }

    @Test
    fun `adding an existing Track again with the Vocal-Free option leaves it unchanged`() = runTest {
        startPipeline()
        val file = FakeLocalFile(displayName = "song.mp3", content = "audio-1")
        val first = import(file)
        val library = Library(tracks, InMemoryTakeRepository(), InMemoryTrackFiles())
        val source = FakeSource(youtube).apply { add(sourceTrackId = "v1", title = "Song") }
        val found = library.add(source.search("song").single())

        val again = import(file, vocalFree = true)
        val foundAgain = library.add(source.search("song").single(), vocalFree = true)

        assertEquals(false, again.vocalFree)
        assertEquals(TrackPipelineState.Separating(fast), tracks.get(first.id)?.pipeline)
        assertEquals(false, foundAgain.vocalFree)
        assertEquals(false, tracks.get(found.id)?.vocalFree)
    }

    @Test
    fun `a Track unmarked Vocal-Free while it becomes Downloaded is separated with the model picked when unmarking`() = runTest {
        val track = import(FakeLocalFile(displayName = "karaoke.mp3", content = "audio-1"), vocalFree = true)
        tracks.updatePipeline(track.id, TrackPipelineState.Added) // its import is still finishing
        val vocalFree = VocalFreeToggle(tracks, files, jobs, InMemoryMixRepository(), listOf(fast, medium, best))
        // The user unmarks it, picking Best, after the Track Pipeline saw it Vocal-Free and Downloaded.
        beforeUpdate = { vocalFree.unmark(track.id, best) }
        startPipeline()

        tracks.updatePipeline(track.id, TrackPipelineState.Downloaded)

        assertEquals(TrackPipelineState.Separating(best), tracks.get(track.id)?.pipeline)
        assertEquals(listOf(FakePipelineJobs.Separation(track.id, best)), jobs.separations)
    }

    @Test
    fun `changing the Default Separation Model leaves existing Tracks as they are`() = runTest {
        startPipeline()
        val ready = import(FakeLocalFile(displayName = "one.mp3", content = "audio-1"))
        tracks.updatePipeline(ready.id, TrackPipelineState.Ready(fast))
        val separating = import(FakeLocalFile(displayName = "two.mp3", content = "audio-2"))
        val queued = jobs.separations.toList()

        defaultModel.set(best)
        // The Library changes again (Separation progress), so the Track Pipeline looks at every Track anew.
        tracks.updatePipeline(separating.id, TrackPipelineState.Separating(fast, SeparationProgress(SeparationProgress.Stage.Separating, 0.5f)))

        assertEquals(TrackPipelineState.Ready(fast), tracks.get(ready.id)?.pipeline)
        assertEquals(fast, tracks.get(separating.id)?.vocalRemovalModel)
        assertEquals(queued, jobs.separations)
    }

    @Test
    fun `re-separating a Track leaves its Takes unchanged, and they play over the new Instrumental`() = runTest {
        startPipeline()
        val track = import(FakeLocalFile(displayName = "song.mp3", content = "audio-1"))
        tracks.updatePipeline(track.id, TrackPipelineState.Ready(fast))
        val takes = InMemoryTakeRepository()
        val engine = FakeAudioEngine(
            listOf(files.originalAudio(track.id), files.stems(track.id, fast).instrumental, files.stems(track.id, best).instrumental)
                .associateWith { 180_000L },
        )
        val settings = InMemorySettingsRepository()
        val sessions = Sessions(tracks, takes, files, engine, settings, Mixes(tracks, takes, InMemoryMixRepository(), files, FakeLoudnessMeter()))
        val session = sessions.open(track.id, backgroundScope)
        session.seekTo(30_000)
        session.record()
        engine.advance(10_000)
        session.stop()
        val take = session.keep()

        pipeline.reseparate(track.id, best)
        tracks.updatePipeline(track.id, TrackPipelineState.Ready(best)) // the job finished
        runCurrent()

        assertEquals(listOf(take), takes.observe(track.id).first())
        assertEquals(BackingAudio.Instrumental(best), session.backing.value)
        assertTrue(session.play(take.id))
        assertEquals(30_000, engine.playback.value.positionMs)
    }
}
