// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core

import eu.schicht8.meavox.core.export.BackingExportOptions
import eu.schicht8.meavox.core.export.BackingExportUnavailable
import eu.schicht8.meavox.core.export.ExportBacking
import eu.schicht8.meavox.core.library.RenameTrack
import eu.schicht8.meavox.core.model.BackingChoice
import eu.schicht8.meavox.core.model.FailureKind
import eu.schicht8.meavox.core.model.FailureReason
import eu.schicht8.meavox.core.model.PipelineStep
import eu.schicht8.meavox.core.model.SeparationModelId
import eu.schicht8.meavox.core.model.SourceId
import eu.schicht8.meavox.core.model.Track
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.model.TrackPipelineState
import eu.schicht8.meavox.core.model.Transpose
import eu.schicht8.meavox.core.port.ExportFormat
import eu.schicht8.meavox.core.port.ExportTags
import eu.schicht8.meavox.core.testing.FakeExportStore
import eu.schicht8.meavox.core.testing.FakeOfflineRenderer
import eu.schicht8.meavox.core.testing.InMemoryLyricsRepository
import eu.schicht8.meavox.core.testing.InMemoryTrackFiles
import eu.schicht8.meavox.core.testing.InMemoryTrackRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class ExportBackingTest {
    private val trackId = TrackId(SourceId("youtube"), "abc")
    private val tracks = InMemoryTrackRepository()
    private val files = InMemoryTrackFiles()
    private val instrumental = files.stems(trackId, FAST).instrumental
    private val original = files.originalAudio(trackId)
    private val renderer = FakeOfflineRenderer(
        mapOf(
            instrumental to FakeOfflineRenderer.Backing(frames = 8_820_000, sampleRate = 44_100),
            original to FakeOfflineRenderer.Backing(frames = 8_820_000, sampleRate = 44_100),
        ),
    )
    private val store = FakeExportStore()
    private val exportBacking = ExportBacking(tracks, files, renderer, store)

    private suspend fun addTrack(
        pipeline: TrackPipelineState,
        lastTranspose: Transpose = Transpose.None,
        vocalFree: Boolean = false,
        choice: BackingChoice = BackingChoice.Instrumental,
    ) = tracks.addIfAbsent(
        Track(
            trackId, "Fly Me to the Moon", "Frank Sinatra", 200_000, pipeline,
            vocalFree = vocalFree, backingChoice = choice, lastTranspose = lastTranspose,
        ),
    )

    @Test
    fun `a Downloaded Track offers only its Original Audio, at its Last Transpose`() = runTest {
        addTrack(TrackPipelineState.Downloaded, lastTranspose = Transpose(-2))

        assertEquals(
            BackingExportOptions(
                layers = listOf(BackingChoice.OriginalAudio),
                layer = BackingChoice.OriginalAudio,
                transpose = Transpose(-2),
                instrumentalToCome = true,
            ),
            exportBacking.options(trackId),
        )
    }

    @Test
    fun `a Ready Track offers its Instrumental too, starting at the Backing Audio it plays`() = runTest {
        addTrack(TrackPipelineState.Ready(FAST))
        assertEquals(
            BackingExportOptions(listOf(BackingChoice.Instrumental, BackingChoice.OriginalAudio), BackingChoice.Instrumental, Transpose.None, instrumentalToCome = false),
            exportBacking.options(trackId),
        )

        tracks.update(trackId) { it.copy(backingChoice = BackingChoice.OriginalAudio) }

        assertEquals(BackingChoice.OriginalAudio, exportBacking.options(trackId).layer)
    }

    @Test
    fun `the Instrumental isn't offered while Separation runs, failed or its stems were deleted`() = runTest {
        addTrack(TrackPipelineState.Separating(FAST))
        for (state in listOf(
            TrackPipelineState.Separating(FAST),
            TrackPipelineState.Failed(PipelineStep.Separating, FailureReason(FailureKind.OutOfMemory)),
            TrackPipelineState.StemsDeleted(FAST),
        )) {
            tracks.updatePipeline(trackId, state)

            assertEquals(listOf(BackingChoice.OriginalAudio), exportBacking.options(trackId).layers, "$state")
            assertTrue(exportBacking.options(trackId).instrumentalToCome, "$state")
        }
    }

    @Test
    fun `nothing can be exported before the Original Audio is stored`() = runTest {
        addTrack(TrackPipelineState.Downloading(0.3f))

        assertEquals(emptyList(), exportBacking.options(trackId).layers)
        val failure = assertFailsWith<BackingExportUnavailable> { exportBacking.export(trackId, BackingChoice.OriginalAudio, Transpose.None, ExportFormat.Wav()) }
        assertEquals(BackingExportUnavailable.Why.NotDownloaded, failure.why)
    }

    @Test
    fun `an export after a rename is titled and tagged with the new name`() = runTest {
        addTrack(TrackPipelineState.Ready(FAST))
        RenameTrack(tracks, InMemoryLyricsRepository())(trackId, title = "Fly Me", artist = "Sinatra")

        exportBacking.export(trackId, BackingChoice.Instrumental, Transpose(2), ExportFormat.Wav())

        assertEquals(ExportTags("Fly Me (Instrumental, +2)", artist = "Sinatra"), store.exports.single().tags)
    }

    @Test
    fun `exporting the Instrumental renders it whole at the chosen Transpose, tagged with the layer and the Transpose`() = runTest {
        addTrack(TrackPipelineState.Ready(FAST), lastTranspose = Transpose(1))
        val progress = mutableListOf<Float>()

        val exported = exportBacking.export(trackId, BackingChoice.Instrumental, Transpose(2), ExportFormat.Wav(24)) { progress += it }

        assertEquals(listOf(FakeOfflineRenderer.BackingRender(instrumental, Transpose(2))), renderer.backingRenders)
        val written = store.exports.single()
        assertEquals(ExportTags("Fly Me to the Moon (Instrumental, +2)", artist = "Frank Sinatra"), written.tags)
        assertEquals(ExportFormat.Wav(24), written.format)
        assertEquals(44_100, written.sampleRate)
        assertEquals(8_820_000, written.framesWritten)
        assertTrue(written.committed)
        assertEquals("content://exports/0", exported.uri)
        assertEquals(1f, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> a <= b })
        // The Transpose chosen for the export doesn't become the Last Transpose.
        assertEquals(Transpose(1), tracks.get(trackId)?.lastTranspose)
    }

    @Test
    fun `the Original Audio is tagged Original, with no Transpose suffix at 0`() = runTest {
        addTrack(TrackPipelineState.Ready(FAST))

        exportBacking.export(trackId, BackingChoice.OriginalAudio, Transpose(-1), ExportFormat.Wav())
        exportBacking.export(trackId, BackingChoice.OriginalAudio, Transpose.None, ExportFormat.Wav())

        assertEquals(listOf(original, original), renderer.backingRenders.map { it.backing })
        assertEquals(listOf("Fly Me to the Moon (Original, −1)", "Fly Me to the Moon (Original)"), store.exports.map { it.title })
    }

    @Test
    fun `the Original Audio of a Vocal-Free Track is tagged Instrumental`() = runTest {
        addTrack(TrackPipelineState.Ready(model = null), vocalFree = true)

        assertEquals(listOf(BackingChoice.OriginalAudio), exportBacking.options(trackId).layers)
        // It has no Instrumental, and won't get one (ADR 14).
        assertFalse(exportBacking.options(trackId).instrumentalToCome)
        exportBacking.export(trackId, BackingChoice.OriginalAudio, Transpose(3), ExportFormat.Wav())

        assertEquals(original, renderer.backingRenders.single().backing)
        assertEquals("Fly Me to the Moon (Instrumental, +3)", store.exports.single().title)
    }

    @Test
    fun `the Instrumental can't be exported before the Track is Ready with it`() = runTest {
        addTrack(TrackPipelineState.Separating(FAST))

        val failure = assertFailsWith<BackingExportUnavailable> { exportBacking.export(trackId, BackingChoice.Instrumental, Transpose.None, ExportFormat.Wav()) }
        assertEquals(BackingExportUnavailable.Why.InstrumentalNotReady, failure.why)
        assertEquals(emptyList(), store.exports)
    }

    @Test
    fun `cancelling a Backing Export removes the unfinished file`() = runTest {
        addTrack(TrackPipelineState.Ready(FAST))
        renderer.pauseAfterFirstBlock = true

        val job = launch { exportBacking.export(trackId, BackingChoice.Instrumental, Transpose.None, ExportFormat.Wav()) }
        runCurrent()
        job.cancel()
        runCurrent()

        assertTrue(store.exports.single().aborted)
        assertFalse(store.exports.single().committed)
    }

    private companion object {
        val FAST = SeparationModelId("fast")
    }
}
