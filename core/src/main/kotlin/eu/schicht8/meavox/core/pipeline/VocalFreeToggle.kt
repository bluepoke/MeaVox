// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.pipeline

import eu.schicht8.meavox.core.library.TrackFiles
import eu.schicht8.meavox.core.library.TrackRepository
import eu.schicht8.meavox.core.library.sizeOfStems
import eu.schicht8.meavox.core.mix.MixRepository
import eu.schicht8.meavox.core.mix.switchToOriginalAudio
import eu.schicht8.meavox.core.model.PipelineStep
import eu.schicht8.meavox.core.model.SeparationModelId
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.model.TrackPipelineState

/**
 * The Vocal-Free toggle on the Track's screen (GLOSSARY: Vocal-Free, ADR 14). Marking skips or undoes
 * Separation; unmarking separates the Track with the model the user picks (ADR 19).
 * [models] are the Separation Models whose stems a Track may hold.
 */
class VocalFreeToggle(
    private val tracks: TrackRepository,
    private val files: TrackFiles,
    private val jobs: PipelineJobs,
    private val mixes: MixRepository,
    private val models: List<SeparationModelId>,
) {
    /**
     * Bytes that marking the Track would free: the stems of its Instrumental, also of the one used while
     * it is re-separated (ADR 22). The UI asks for confirmation when this isn't 0.
     */
    suspend fun stemBytes(id: TrackId): Long {
        val model = tracks.get(id)?.instrumentalModel ?: return 0
        return files.sizeOfStems(id, model)
    }

    /**
     * Marks the Track Vocal-Free. Separation is cancelled or undone: a Track that is Separating,
     * whose Separation failed, or that has (or had) stems becomes Ready without stems, and what
     * Separation left is deleted. A Track still to be downloaded skips Separation once it is
     * (see [TrackPipeline]). Mixes on the Instrumental switch to the Original Audio, which keeps them
     * aligned because stems are sample-aligned with it (ADR 14).
     */
    suspend fun mark(id: TrackId) {
        var before: TrackPipelineState? = null
        // One atomic change, so a Track Pipeline step can't slip in between the flag and the state.
        tracks.update(id) { track ->
            before = track.pipeline
            when {
                track.vocalFree -> null
                track.pipeline.separationStarted() -> track.copy(vocalFree = true, pipeline = TrackPipelineState.Ready(model = null))
                else -> track.copy(vocalFree = true)
            }
        } ?: return
        // Before the stems go, so no Mix points at a deleted Instrumental.
        mixes.switchToOriginalAudio(id)
        // State first, then jobs and files: a late Separation can no longer write over it (SeparateTrack).
        if (before is TrackPipelineState.Separating) jobs.cancelSeparation(id)
        // Unmarked again while waiting for the job to stop: the new Separation's files stay.
        if (tracks.get(id)?.vocalFree != true) return
        models.forEach { files.deleteStems(id, it) }
    }

    /**
     * Unmarks the Track, to be separated with [model] (the model picker opens with the Default
     * Separation Model, ADR 19): a Ready one goes back through Separating with it (ADR 14), one
     * still to be downloaded is separated with it once it is. Mixes keep the Original Audio until
     * the user switches them (ADR 14).
     */
    suspend fun unmark(id: TrackId, model: SeparationModelId) {
        val track = tracks.update(id) { track ->
            when {
                !track.vocalFree -> null
                track.pipeline == TrackPipelineState.Ready(model = null) ->
                    track.copy(vocalFree = false, separationModel = model, pipeline = TrackPipelineState.Separating(model))
                else -> track.copy(vocalFree = false, separationModel = model)
            }
        }
        // Queued here, not left to the Track Pipeline: it may never see the short Ready in between.
        if (track?.pipeline == TrackPipelineState.Separating(model)) jobs.separate(id, model)
    }

    /** Separation is running, failed or done: marking makes the Track Ready at once. */
    private fun TrackPipelineState.separationStarted(): Boolean = when (this) {
        is TrackPipelineState.Separating, is TrackPipelineState.Ready, is TrackPipelineState.StemsDeleted -> true
        is TrackPipelineState.Failed -> step == PipelineStep.Separating
        TrackPipelineState.Added, is TrackPipelineState.Downloading, TrackPipelineState.Downloaded -> false
    }
}
