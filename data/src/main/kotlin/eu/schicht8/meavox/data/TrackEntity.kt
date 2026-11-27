// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.data

import androidx.room.ColumnInfo
import androidx.room.Entity
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

/** Row of the `tracks` table. Primary key is the Track identity (ADR 10). */
@Entity(tableName = "tracks", primaryKeys = ["source", "source_track_id"])
internal data class TrackEntity(
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "source_track_id") val sourceTrackId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "artist") val artist: String?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    @ColumnInfo(name = "pipeline_state") val pipelineState: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    /** Progress of a running step, e.g. `separating:0.42` (version 3, #7) or `downloading:0.42:1` (#6). */
    @ColumnInfo(name = "pipeline_progress") val pipelineProgress: String? = null,
    /**
     * Why the Track is Failed (version 3, #7), or why a download waits to retry (#6): the reason's
     * detail since version 14, the whole reason as English text before (see [FailureColumns]).
     */
    @ColumnInfo(name = "pipeline_failure") val pipelineFailure: String? = null,
    /** The kind of that reason as a stable code (version 14, #43); null for rows from before. */
    @ColumnInfo(name = "pipeline_failure_kind") val pipelineFailureKind: String? = null,
    /** The Source or service the reason names (version 14, #43). */
    @ColumnInfo(name = "pipeline_failure_service") val pipelineFailureService: String? = null,
    /** When a Take of the Track last started recording (version 8, #20; since #34 not on opening). */
    @ColumnInfo(name = "last_session_at") val lastSessionAt: Long? = null,
    /** The Track is Vocal-Free (version 9, #25). */
    @ColumnInfo(name = "vocal_free", defaultValue = "0") val vocalFree: Boolean = false,
    /** The Backing Audio the user chose (version 10, #34): `instrumental` or `original_audio`. */
    @ColumnInfo(name = "backing_choice", defaultValue = INSTRUMENTAL) val backingChoice: String = INSTRUMENTAL,
    /** The Separation Model picked for the Track (version 11, #17); null for Tracks added before. */
    @ColumnInfo(name = "separation_model") val separationModel: String? = null,
    /** The Last Transpose in semitones (version 13, #23); 0 for Tracks from before. */
    @ColumnInfo(name = "last_transpose", defaultValue = "0") val lastTranspose: Int = 0,
    /**
     * While the Track is re-separated, or after that failed, the Separation Model whose Instrumental
     * plays meanwhile (version 16, #37, ADR 22); null otherwise.
     */
    @ColumnInfo(name = "pipeline_previous_model") val pipelinePreviousModel: String? = null,
    /** Where the Source's picture of the Track is, downloaded as its Cover Art (version 18, #42); null for Tracks from before. */
    @ColumnInfo(name = "cover_url") val coverUrl: String? = null,
    /** The Track's missing Cover Art was looked up after the fact (version 19, #50); false for Tracks from before. */
    @ColumnInfo(name = "cover_looked_up", defaultValue = "0") val coverLookedUp: Boolean = false,
)

internal fun Track.toEntity(addedAt: Long): TrackEntity {
    val pipelineColumns = pipeline.toColumns()
    return TrackEntity(
        source = id.source.value,
        sourceTrackId = id.sourceTrackId,
        title = title,
        artist = artist,
        durationMs = durationMs,
        pipelineState = pipelineColumns.state,
        addedAt = addedAt,
        pipelineProgress = pipelineColumns.progress,
        pipelineFailure = pipelineColumns.failure.detail,
        pipelineFailureKind = pipelineColumns.failure.kind,
        pipelineFailureService = pipelineColumns.failure.service,
        lastSessionAt = lastSessionAt,
        vocalFree = vocalFree,
        backingChoice = backingChoice.toColumn(),
        separationModel = separationModel?.value,
        lastTranspose = lastTranspose.semitones,
        pipelinePreviousModel = pipelineColumns.previous,
        coverUrl = coverUrl,
        coverLookedUp = coverLookedUp,
    )
}

internal fun TrackEntity.toTrack() = Track(
    id = TrackId(SourceId(source), sourceTrackId),
    title = title,
    artist = artist,
    durationMs = durationMs,
    pipeline = PipelineColumns(
        pipelineState,
        pipelineProgress,
        FailureColumns(kind = pipelineFailureKind, detail = pipelineFailure, service = pipelineFailureService),
        pipelinePreviousModel,
    ).toState(),
    lastSessionAt = lastSessionAt,
    vocalFree = vocalFree,
    backingChoice = backingChoiceFromColumn(backingChoice),
    separationModel = separationModel?.let(::SeparationModelId),
    lastTranspose = Transpose.clamped(lastTranspose),
    coverUrl = coverUrl,
    coverLookedUp = coverLookedUp,
)

private fun BackingChoice.toColumn(): String = when (this) {
    BackingChoice.Instrumental -> INSTRUMENTAL
    BackingChoice.OriginalAudio -> ORIGINAL_AUDIO
}

private fun backingChoiceFromColumn(value: String): BackingChoice = when (value) {
    INSTRUMENTAL -> BackingChoice.Instrumental
    ORIGINAL_AUDIO -> BackingChoice.OriginalAudio
    else -> error("unknown Backing Audio choice '$value'")
}

/** The pipeline columns of a row. Stored as stable strings so enum/class renames don't corrupt data. */
internal data class PipelineColumns(
    val state: String,
    val progress: String? = null,
    val failure: FailureColumns = FailureColumns(null, null, null),
    /** The model whose Instrumental plays while the Track is re-separated (ADR 22). */
    val previous: String? = null,
)

internal fun TrackPipelineState.toColumns(): PipelineColumns = when (this) {
    TrackPipelineState.Added -> PipelineColumns("added")
    // progress `downloading:<fraction or empty>:<failed attempts>`, failure the reason it waits to retry
    is TrackPipelineState.Downloading ->
        PipelineColumns("downloading", progress = "downloading:${progress ?: ""}:$failedAttempts", failure = retryReason.toColumns())
    TrackPipelineState.Downloaded -> PipelineColumns("downloaded")
    is TrackPipelineState.Separating ->
        PipelineColumns("$SEPARATING_PREFIX${model.value}", progress = progress?.toColumn(), previous = previous?.value)
    // A Vocal-Free Track is Ready without stems (ADR 14): plain `ready`.
    is TrackPipelineState.Ready -> PipelineColumns(model?.let { "$READY_PREFIX${it.value}" } ?: READY)
    is TrackPipelineState.StemsDeleted -> PipelineColumns("$STEMS_DELETED_PREFIX${model.value}")
    is TrackPipelineState.Failed ->
        PipelineColumns("$FAILED_PREFIX${step.toColumn()}", failure = reason.toColumns(), previous = previous?.value)
}

private fun PipelineColumns.toState(): TrackPipelineState = when {
    state == "added" -> TrackPipelineState.Added
    state == "downloading" -> {
        val parts = progress?.split(':').orEmpty()
        TrackPipelineState.Downloading(
            progress = parts.getOrNull(1)?.toFloatOrNull(),
            retryReason = failure.toReason(),
            failedAttempts = parts.getOrNull(2)?.toIntOrNull() ?: 0,
        )
    }
    state == "downloaded" -> TrackPipelineState.Downloaded
    state.startsWith(SEPARATING_PREFIX) ->
        TrackPipelineState.Separating(
            SeparationModelId(state.removePrefix(SEPARATING_PREFIX)),
            progress?.let(::progressFromColumn),
            previous?.let(::SeparationModelId),
        )
    state == READY -> TrackPipelineState.Ready(model = null)
    state.startsWith(READY_PREFIX) -> TrackPipelineState.Ready(SeparationModelId(state.removePrefix(READY_PREFIX)))
    state.startsWith(STEMS_DELETED_PREFIX) ->
        TrackPipelineState.StemsDeleted(SeparationModelId(state.removePrefix(STEMS_DELETED_PREFIX)))
    state.startsWith(FAILED_PREFIX) ->
        TrackPipelineState.Failed(
            stepFromColumn(state.removePrefix(FAILED_PREFIX)),
            failure.toReason() ?: FailureReason(FailureKind.Unexpected),
            previous?.let(::SeparationModelId),
        )
    else -> error("unknown pipeline state '$state'")
}

private fun SeparationProgress.toColumn(): String = when (stage) {
    SeparationProgress.Stage.DownloadingModel -> "downloading_model"
    SeparationProgress.Stage.InstallingModel -> "installing_model"
    SeparationProgress.Stage.Separating -> "separating"
} + ":$fraction"

private fun progressFromColumn(value: String): SeparationProgress {
    val stage = when (value.substringBefore(':')) {
        "downloading_model" -> SeparationProgress.Stage.DownloadingModel
        "installing_model" -> SeparationProgress.Stage.InstallingModel
        "separating" -> SeparationProgress.Stage.Separating
        else -> error("unknown progress '$value'")
    }
    return SeparationProgress(stage, value.substringAfter(':').toFloat())
}

private fun PipelineStep.toColumn(): String = when (this) {
    PipelineStep.Downloading -> "downloading"
    PipelineStep.Separating -> "separating"
}

private fun stepFromColumn(value: String): PipelineStep = when (value) {
    "downloading" -> PipelineStep.Downloading
    "separating" -> PipelineStep.Separating
    else -> error("unknown pipeline step '$value'")
}

private const val INSTRUMENTAL = "instrumental"
private const val ORIGINAL_AUDIO = "original_audio"
private const val SEPARATING_PREFIX = "separating:"
private const val READY = "ready"
private const val READY_PREFIX = "ready:"
private const val FAILED_PREFIX = "failed:"
private const val STEMS_DELETED_PREFIX = "stems_deleted:"
