// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core

import eu.schicht8.meavox.core.library.RenameTrack
import eu.schicht8.meavox.core.mix.ExportMix
import eu.schicht8.meavox.core.mix.Mix
import eu.schicht8.meavox.core.mix.MixId
import eu.schicht8.meavox.core.mix.MixSettings
import eu.schicht8.meavox.core.mix.MixUnavailable
import eu.schicht8.meavox.core.model.OutputRoute
import eu.schicht8.meavox.core.model.SeparationModelId
import eu.schicht8.meavox.core.model.SourceId
import eu.schicht8.meavox.core.model.Take
import eu.schicht8.meavox.core.model.TakeId
import eu.schicht8.meavox.core.model.Track
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.model.TrackPipelineState
import eu.schicht8.meavox.core.model.Transpose
import eu.schicht8.meavox.core.port.ExportFormat
import eu.schicht8.meavox.core.port.ExportTags
import eu.schicht8.meavox.core.port.MixRender
import eu.schicht8.meavox.core.session.BackingAudio
import eu.schicht8.meavox.core.testing.FakeExportStore
import eu.schicht8.meavox.core.testing.FakeOfflineRenderer
import eu.schicht8.meavox.core.testing.InMemoryLyricsRepository
import eu.schicht8.meavox.core.testing.InMemoryMixRepository
import eu.schicht8.meavox.core.testing.InMemorySettingsRepository
import eu.schicht8.meavox.core.testing.InMemoryTakeRepository
import eu.schicht8.meavox.core.testing.InMemoryTrackFiles
import eu.schicht8.meavox.core.testing.InMemoryTrackRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExportMixTest {
    private val trackId = TrackId(SourceId("fake"), "v1")
    private val tracks = InMemoryTrackRepository()
    private val takes = InMemoryTakeRepository()
    private val mixes = InMemoryMixRepository()
    private val files = InMemoryTrackFiles()
    private val renderer = FakeOfflineRenderer()
    private val store = FakeExportStore()
    private val settings = InMemorySettingsRepository()
    private val export = ExportMix(tracks, takes, mixes, files, renderer, store, settings)

    // Sung from 0:30 for 10 s at +2, with the engine's latency estimate at 100 ms.
    private val take = Take(
        id = TakeId("t1"), track = trackId, name = "Take 1",
        startFrame = 1_440_000, frames = 480_000, sampleRate = 48_000,
        route = OutputRoute.Wired, reportedLatencyFrames = 4_800, transpose = Transpose(2), recordedAt = 0,
    )

    private suspend fun arrange(
        pipeline: TrackPipelineState,
        backing: BackingAudio,
        settings: MixSettings = MixSettings(backingDb = -12f, vocalDb = 3f, vocalNudgeMs = 25),
        vocalFree: Boolean = false,
        artist: String? = "Frank Sinatra",
    ): Mix {
        tracks.addIfAbsent(Track(trackId, "Fly Me to the Moon", artist, 180_000, pipeline, vocalFree = vocalFree))
        takes.add(take)
        return Mix(MixId("m1"), trackId, take.id, "Mix 1", backing, settings, createdAt = 0).also { mixes.add(it) }
    }

    @Test
    fun `an Export renders the Take's span over its Backing Audio with the Take's Transpose, calibration, Nudge and levels`() = runTest {
        val mix = arrange(TrackPipelineState.Ready(FAST), BackingAudio.Instrumental(FAST))
        val progress = mutableListOf<Float>()

        val exported = export.export(mix.id, ExportFormat.Wav(24)) { progress += it }

        // A Nudge of +25 ms moves the voice 1 200 frames later: 4 800 − 1 200 = 3 600 frames of alignment.
        // The Mix ends where Review would: once the last Take sample has sounded.
        assertEquals(
            MixRender(
                backing = files.stems(trackId, FAST).instrumental,
                take = files.take(trackId, take.id),
                sampleRate = 48_000,
                takeStartFrame = 1_440_000,
                alignmentFrames = 3_600,
                transpose = Transpose(2),
                backingGainDb = -12f,
                takeGainDb = 3f,
                fromFrame = 1_440_000,
                toFrame = 1_916_400,
            ),
            renderer.renders.single(),
        )
        val written = store.exports.single()
        assertEquals("Fly Me to the Moon (Mix 1)", written.title)
        assertEquals(ExportFormat.Wav(24), written.format)
        assertEquals(48_000, written.sampleRate)
        assertEquals(476_400, written.frames)
        assertEquals(476_400, written.framesWritten)
        assertTrue(written.committed)
        assertEquals("content://exports/0", exported.uri)
        assertEquals(1f, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> a <= b })
    }

    @Test
    fun `a Mix Export is tagged with the Track's artist featuring the singer's name`() = runTest {
        val mix = arrange(TrackPipelineState.Ready(FAST), BackingAudio.Instrumental(FAST))
        settings.setSingerName("Peter")

        export.export(mix.id, ExportFormat.Mp3())

        assertEquals(ExportTags("Fly Me to the Moon (Mix 1)", artist = "Frank Sinatra feat. Peter"), store.exports.single().tags)
        assertEquals(ExportFormat.Mp3(ExportFormat.Mp3.Bitrate.VbrV2), store.exports.single().format)
    }

    @Test
    fun `without a singer's name a Mix Export is tagged with the Track's artist alone`() = runTest {
        val mix = arrange(TrackPipelineState.Ready(FAST), BackingAudio.Instrumental(FAST))
        settings.setSingerName("  ")

        export.export(mix.id, ExportFormat.M4a)

        assertEquals("Frank Sinatra", store.exports.single().tags.artist)
    }

    @Test
    fun `a Mix of a Track without an artist is tagged with the singer's name alone`() = runTest {
        val mix = arrange(TrackPipelineState.Ready(FAST), BackingAudio.Instrumental(FAST), artist = null)
        settings.setSingerName("Peter")

        export.export(mix.id, ExportFormat.Wav())

        assertEquals("Peter", store.exports.single().tags.artist)
    }

    @Test
    fun `a Mix Export after a rename is titled and tagged with the Track's new name`() = runTest {
        val mix = arrange(TrackPipelineState.Ready(FAST), BackingAudio.Instrumental(FAST))
        settings.setSingerName("Peter")
        RenameTrack(tracks, InMemoryLyricsRepository())(trackId, title = "Fly Me", artist = "Sinatra")

        export.export(mix.id, ExportFormat.Wav())

        assertEquals(ExportTags("Fly Me (Mix 1)", artist = "Sinatra feat. Peter"), store.exports.single().tags)
    }

    @Test
    fun `a Mix over the Original Audio renders the Original Audio`() = runTest {
        val mix = arrange(TrackPipelineState.Ready(FAST), BackingAudio.OriginalAudio)

        export.export(mix.id, ExportFormat.Wav())

        assertEquals(files.originalAudio(trackId), renderer.renders.single().backing)
    }

    @Test
    fun `cancelling an Export removes the unfinished file`() = runTest {
        val mix = arrange(TrackPipelineState.Ready(FAST), BackingAudio.Instrumental(FAST))
        renderer.pauseAfterFirstBlock = true

        val job = launch { export.export(mix.id, ExportFormat.Wav()) }
        runCurrent()
        job.cancel()
        runCurrent()

        val written = store.exports.single()
        assertTrue(written.aborted)
        assertFalse(written.committed)
    }

    @Test
    fun `a Mix can't be exported while its Instrumental is being made again`() = runTest {
        val mix = arrange(TrackPipelineState.StemsDeleted(FAST), BackingAudio.Instrumental(FAST))

        assertFailsWith<MixUnavailable> { export.export(mix.id, ExportFormat.Wav()) }
        assertEquals(emptyList(), store.exports)
    }

    @Test
    fun `a Mix whose Instrumental is gone after re-separation switches to the new Instrumental`() = runTest {
        val mix = arrange(TrackPipelineState.Ready(BEST), BackingAudio.Instrumental(FAST))

        export.export(mix.id, ExportFormat.Wav())

        assertEquals(files.stems(trackId, BEST).instrumental, renderer.renders.single().backing)
        assertEquals(BackingAudio.Instrumental(BEST), mixes.get(mix.id)?.backing)
    }

    @Test
    fun `a Mix keeps its Instrumental after re-separation while those stems are still stored`() = runTest {
        val mix = arrange(TrackPipelineState.Ready(BEST), BackingAudio.Instrumental(FAST))
        files.store(files.stems(trackId, FAST).instrumental, 30_000_000)

        export.export(mix.id, ExportFormat.Wav())

        assertEquals(files.stems(trackId, FAST).instrumental, renderer.renders.single().backing)
        assertEquals(BackingAudio.Instrumental(FAST), mixes.get(mix.id)?.backing)
    }

    private companion object {
        val FAST = SeparationModelId("fast")
        val BEST = SeparationModelId("best")
    }
}
