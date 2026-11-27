// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.data

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
import eu.schicht8.meavox.core.port.SeparationProgress
import kotlin.test.Test
import kotlin.test.assertEquals

class TrackEntityTest {
    private val model = SeparationModelId("spleeter-2stems")
    private val previous = SeparationModelId("uvr-mdx-karaoke")

    private fun track(state: TrackPipelineState) =
        Track(TrackId(SourceId("youtube"), "abc"), "Song", "Artist", 1_000, state)

    @Test
    fun `every Track Pipeline state is stored and read back unchanged`() {
        val states = listOf(
            TrackPipelineState.Added,
            TrackPipelineState.Downloading(),
            TrackPipelineState.Downloading(0.42f),
            TrackPipelineState.Downloading(0.42f, retryReason = FailureReason(FailureKind.ConnectionBroke, "reset by peer"), failedAttempts = 2),
            TrackPipelineState.Downloading(null, retryReason = FailureReason(FailureKind.ServiceUnreachable, service = "YouTube"), failedAttempts = 1),
            TrackPipelineState.Downloaded,
            TrackPipelineState.Separating(model),
            TrackPipelineState.Separating(model, SeparationProgress(SeparationProgress.Stage.DownloadingModel, 0.25f)),
            TrackPipelineState.Separating(model, SeparationProgress(SeparationProgress.Stage.InstallingModel, 0.75f)),
            TrackPipelineState.Separating(model, SeparationProgress(SeparationProgress.Stage.Separating, 0.5f)),
            TrackPipelineState.Ready(model),
            TrackPipelineState.StemsDeleted(model),
            TrackPipelineState.Ready(model = null),
            TrackPipelineState.Failed(PipelineStep.Separating, FailureReason(FailureKind.AudioUndecodable, "no decoder for audio/x-weird")),
            TrackPipelineState.Failed(PipelineStep.Downloading, FailureReason(FailureKind.ServerError, "404")),
            // Re-separating with another model while the previous one's Instrumental plays (ADR 22).
            TrackPipelineState.Separating(model, previous = previous),
            TrackPipelineState.Separating(model, SeparationProgress(SeparationProgress.Stage.Separating, 0.5f), previous = previous),
            TrackPipelineState.Failed(PipelineStep.Separating, FailureReason(FailureKind.OutOfMemory), previous = previous),
        )
        for (state in states) {
            assertEquals(track(state), track(state).toEntity(addedAt = 1).toTrack(), "$state")
        }
    }

    @Test
    fun `every kind of failure reason is stored and read back, with its detail and service`() {
        for (kind in FailureKind.entries) {
            val reason = FailureReason(kind, detail = "detail of $kind", service = "YouTube")
            val state = TrackPipelineState.Failed(PipelineStep.Downloading, reason)

            assertEquals(track(state), track(state).toEntity(addedAt = 1).toTrack(), "$kind")
        }
    }

    @Test
    fun `a failure reason stored as text before #43 is shown as it was`() {
        val earlier = track(TrackPipelineState.Ready(model)).toEntity(addedAt = 1)
            .copy(pipelineState = "failed:downloading", pipelineFailure = "the server answered HTTP 404")
        val waiting = track(TrackPipelineState.Ready(model)).toEntity(addedAt = 1)
            .copy(pipelineState = "downloading", pipelineProgress = "downloading:0.5:1", pipelineFailure = "the download link expired")

        assertEquals(
            TrackPipelineState.Failed(PipelineStep.Downloading, FailureReason(FailureKind.Unexpected, "the server answered HTTP 404")),
            earlier.toTrack().pipeline,
        )
        assertEquals(
            TrackPipelineState.Downloading(0.5f, FailureReason(FailureKind.Unexpected, "the download link expired"), failedAttempts = 1),
            waiting.toTrack().pipeline,
        )
    }

    @Test
    fun `the Backing Audio choice is stored and read back`() {
        for (choice in BackingChoice.entries) {
            val track = track(TrackPipelineState.Ready(model)).copy(backingChoice = choice)

            assertEquals(track, track.toEntity(addedAt = 1).toTrack(), "$choice")
        }
    }

    @Test
    fun `the Separation Model picked for a Track is stored and read back, and may be unset`() {
        for (picked in listOf(SeparationModelId("uvr-mdx-inst-hq4"), null)) {
            val track = track(TrackPipelineState.Added).copy(separationModel = picked)

            assertEquals(track, track.toEntity(addedAt = 1).toTrack(), "$picked")
        }
    }

    @Test
    fun `states stored by earlier versions still read`() {
        val earlier = track(TrackPipelineState.Ready(model)).toEntity(addedAt = 1)
            .copy(pipelineState = "ready:fast", pipelineProgress = null, pipelineFailure = null)

        assertEquals(TrackPipelineState.Ready(SeparationModelId("fast")), earlier.toTrack().pipeline)
    }

    @Test
    fun `the Last Transpose is stored and read back`() {
        for (semitones in listOf(-6, -1, 0, 3, 6)) {
            val track = track(TrackPipelineState.Ready(model)).copy(lastTranspose = Transpose(semitones))

            assertEquals(track, track.toEntity(addedAt = 1).toTrack(), "$semitones")
        }
    }

    @Test
    fun `where the Cover Art comes from is stored and read back, and may be unset`() {
        for (url in listOf("https://i.ytimg.com/vi/abc/maxresdefault.jpg", null)) {
            val track = track(TrackPipelineState.Added).copy(coverUrl = url)

            assertEquals(track, track.toEntity(addedAt = 1).toTrack(), "$url")
        }
    }
}
