// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.model

import eu.schicht8.meavox.core.port.SeparationProgress

/** A song the user added (GLOSSARY: Track), identified by its Source and source ID (ADR 10). */
data class Track(
    val id: TrackId,
    val title: String,
    val artist: String?,
    val durationMs: Long?,
    val pipeline: TrackPipelineState,
    /** When a Take of the Track last started recording (epoch ms), "last sung"; null if it was never sung. */
    val lastSessionAt: Long? = null,
    /**
     * The Original Audio has no lead vocal, so the Track skips Separation and always sings over
     * its Original Audio (GLOSSARY: Vocal-Free, ADR 14). Set by the user.
     */
    val vocalFree: Boolean = false,
    /**
     * What the user wants to sing over (GLOSSARY: Backing Audio), kept per Track. The Instrumental
     * plays only while there is one (see [instrumentalModel]); the Original Audio plays meanwhile.
     */
    val backingChoice: BackingChoice = BackingChoice.Instrumental,
    /**
     * The Separation Model the user picked for the Track (ADR 19): when adding it, re-separating it,
     * or unmarking it Vocal-Free. A Track reaching Downloaded (also by a retry) is separated with
     * it. Null for Tracks added before models could be picked; they get the Default Separation Model.
     */
    val separationModel: SeparationModelId? = null,
    /**
     * The Transpose the Track's control was last set to in a Session, whether or not a Take was kept
     * (GLOSSARY: Last Transpose). A new Session for the Track starts at it. It never changes Takes, so
     * neither their Mixes.
     */
    val lastTranspose: Transpose = Transpose.None,
) {
    /**
     * The Separation Model whose Instrumental plays: the one the Track is Ready with, or while it is
     * re-separated with another model, or after that failed, the one it was Ready with before (ADR 22).
     * Null before the first Separation has finished, and always for a Vocal-Free Track (ADR 14).
     */
    val instrumentalModel: SeparationModelId?
        get() = when (pipeline) {
            is TrackPipelineState.Ready -> pipeline.model
            is TrackPipelineState.Separating -> pipeline.previous
            is TrackPipelineState.Failed -> pipeline.previous
            TrackPipelineState.Added, is TrackPipelineState.Downloading, TrackPipelineState.Downloaded,
            is TrackPipelineState.StemsDeleted -> null
        }

    /**
     * Why the Track's stems of [model] must not be deleted now, or null if they may be: they are being
     * made, or they are the previous Instrumental playing while the Track is re-separated (ADR 22).
     */
    fun stemsInUse(model: SeparationModelId): StemsInUse? = when {
        pipeline is TrackPipelineState.Separating && pipeline.model == model -> StemsInUse.BeingMade
        pipeline !is TrackPipelineState.Ready && instrumentalModel == model -> StemsInUse.Playing
        else -> null
    }

    /**
     * The Separation Model the Track's vocal removal uses: the one it is Ready with, the one it is
     * Separating with (also when re-separating, while an earlier model's stems may still be stored), or
     * the one whose deleted stems are made again when it is opened (ADR 17, ADR 19). Null before
     * Separation starts, after it failed, and always for a Vocal-Free Track.
     */
    val vocalRemovalModel: SeparationModelId?
        get() = when (pipeline) {
            is TrackPipelineState.Ready -> pipeline.model
            is TrackPipelineState.Separating -> pipeline.model
            is TrackPipelineState.StemsDeleted -> pipeline.model
            TrackPipelineState.Added, is TrackPipelineState.Downloading, TrackPipelineState.Downloaded,
            is TrackPipelineState.Failed -> null
        }

    /** The Original Audio is stored, so the Track can be played and sung (ADR 10). */
    val hasOriginalAudio: Boolean
        get() = when (pipeline) {
            TrackPipelineState.Added, is TrackPipelineState.Downloading -> false
            TrackPipelineState.Downloaded, is TrackPipelineState.Separating, is TrackPipelineState.Ready,
            is TrackPipelineState.StemsDeleted -> true
            is TrackPipelineState.Failed -> pipeline.step != PipelineStep.Downloading
        }
}

/** Why stems can't be deleted now (see [Track.stemsInUse]). */
enum class StemsInUse {
    /** Separation is writing them. */
    BeingMade,

    /** They are the previous Instrumental, playing until the new one is there (ADR 22). */
    Playing,
}

/** Which Backing Audio the user chose for a Track (GLOSSARY: Backing Audio). */
enum class BackingChoice {
    Instrumental,
    OriginalAudio,
}

/** Track identity: the Source plus that Source's own ID for the item (ADR 10). */
data class TrackId(val source: SourceId, val sourceTrackId: String) {
    /** Canonical string form `source:sourceTrackId` (ADR 10), e.g. `youtube:dQw4w9WgXcQ`. */
    val key: String get() = "${source.value}:$sourceTrackId"
}

/**
 * Stable name of one Source, e.g. `youtube`, `soundcloud` or `local`. Each service behind the
 * NewPipe adapter is its own Source, so the ID namespace is the service, not the library.
 */
@JvmInline
value class SourceId(val value: String) {
    companion object {
        /** Files imported from the device; their source ID is the content hash (ADR 10). */
        val Local = SourceId("local")
    }
}

/** Where a Track is in its processing (GLOSSARY: Track Pipeline, ADR 10). */
sealed interface TrackPipelineState {
    /** Known to the Library; nothing downloaded yet. */
    data object Added : TrackPipelineState

    /**
     * The Original Audio is being downloaded; [progress] (0..1) is null until it has started.
     * After a failure that may pass, [retryReason] says why it waits for its next try, and
     * [failedAttempts] counts the tries that failed so far.
     */
    data class Downloading(
        val progress: Float? = null,
        val retryReason: FailureReason? = null,
        val failedAttempts: Int = 0,
    ) : TrackPipelineState

    /** Original Audio is stored locally; a Session can start (ADR 10). */
    data object Downloaded : TrackPipelineState

    /**
     * Separation with [model] is queued or running; [progress] is null until it has started.
     * The Original Audio stays playable meanwhile. When a Ready Track is re-separated, [previous] is
     * the model it was Ready with: its stems are complete, and its Instrumental plays until the new
     * one is there (ADR 22).
     */
    data class Separating(
        val model: SeparationModelId,
        val progress: SeparationProgress? = null,
        val previous: SeparationModelId? = null,
    ) : TrackPipelineState

    /**
     * Every step the Track needs is done (ADR 14): Separation with [model] has finished and its
     * Instrumental and Vocal Stem are stored, or [model] is null for a Vocal-Free Track, which has none.
     */
    data class Ready(val model: SeparationModelId?) : TrackPipelineState

    /**
     * The stems Separation with [model] produced were deleted to free space (ADR 17). Separation
     * with [model] runs again when the Track is next opened; the Original Audio plays meanwhile.
     */
    data class StemsDeleted(val model: SeparationModelId) : TrackPipelineState

    /**
     * [step] stopped for a [reason] that retrying alone won't fix; the user can retry. A failed
     * re-separation keeps [previous], the model whose Instrumental plays on (ADR 22).
     */
    data class Failed(
        val step: PipelineStep,
        val reason: FailureReason,
        val previous: SeparationModelId? = null,
    ) : TrackPipelineState
}

/** A step of the Track Pipeline that can fail (ADR 10). */
enum class PipelineStep {
    Downloading,
    Separating,
}

/** Identifies one Separation Model of the catalogue (ADR 3). */
@JvmInline
value class SeparationModelId(val value: String)
