// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.pipeline

import eu.schicht8.meavox.core.library.TrackRepository
import eu.schicht8.meavox.core.model.PipelineStep
import eu.schicht8.meavox.core.model.SeparationModelId
import eu.schicht8.meavox.core.model.SourceId
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.model.TrackPipelineState

/**
 * Moves Tracks through the Track Pipeline (ADR 10): a Track added from one of the network Sources
 * in [downloadFrom] is queued for download, and every Track that reaches Downloaded is queued for
 * Separation with the model picked for it (ADR 19), or is Ready at once if it is Vocal-Free (ADR 14).
 * Local imports aren't downloaded: a local Track left in Added is an import that never finished,
 * and importing the file again completes it.
 *
 * A Track added before models could be picked has none; it is separated with [unpickedModel], which
 * the app sets to Fast. Not the Default Separation Model: Settings sets the default without download
 * consent, and the user never picked it for these Tracks (ADR 19, #36).
 */
class TrackPipeline(
    private val tracks: TrackRepository,
    private val jobs: PipelineJobs,
    private val unpickedModel: SeparationModelId,
    private val downloadFrom: Set<SourceId> = emptySet(),
) {
    /** Follows the Library until cancelled; runs for the lifetime of the app process. */
    suspend fun run() {
        // Tracks this run has queued a job for. Each Track is queued once per run: progress
        // updates change the Track many times while its job runs.
        val queued = mutableSetOf<TrackId>()
        tracks.observeAll().collect { all ->
            for (track in all) {
                when (val state = track.pipeline) {
                    TrackPipelineState.Added -> if (track.id.source in downloadFrom) {
                        tracks.updatePipeline(track.id, TrackPipelineState.Downloading())
                        queued += track.id
                        jobs.download(track.id)
                    }
                    // Left Downloading by an earlier process (or queued above): make sure its job exists.
                    is TrackPipelineState.Downloading -> if (queued.add(track.id)) jobs.download(track.id)
                    TrackPipelineState.Downloaded -> {
                        var model = unpickedModel
                        // One atomic step, so marking or unmarking the Track Vocal-Free meanwhile is
                        // never missed: unmarking picks the model, so it is read from the Track as it is now.
                        val next = tracks.update(track.id) { now ->
                            if (now.pipeline != TrackPipelineState.Downloaded) return@update null
                            model = now.separationModel ?: unpickedModel
                            // A Vocal-Free Track skips Separation (ADR 14).
                            now.copy(pipeline = if (now.vocalFree) TrackPipelineState.Ready(model = null) else TrackPipelineState.Separating(model))
                        }
                        if (next?.pipeline == TrackPipelineState.Separating(model)) {
                            queued += track.id
                            jobs.separate(track.id, model)
                        }
                    }
                    // Left Separating by an earlier process (or queued above): make sure its job exists.
                    is TrackPipelineState.Separating -> if (queued.add(track.id)) jobs.separate(track.id, state.model)
                    else -> queued -= track.id
                }
            }
        }
    }

    /**
     * Separates the Track again with [model] (ADR 19): any Track whose Original Audio is stored,
     * unless it is Vocal-Free (ADR 14) or already Ready or Separating with [model]. The Instrumental
     * that plays now keeps playing until the new one is there, or the Original Audio if there is
     * none (ADR 22). Takes stay aligned, since stems are sample-aligned with the Original Audio
     * (ADR 8). The previous model's stems stay until they are deleted on the storage screen.
     *
     * Re-separating with the model whose Instrumental plays meanwhile needs no Separation: its stems
     * are complete, so the Track is Ready with it at once.
     *
     * A running Separation is replaced, not queued behind (ADR 10, #28). Its checkpoint stays, so
     * separating with that model again continues where it stopped.
     */
    suspend fun reseparate(id: TrackId, model: SeparationModelId) {
        // State first: a replaced job writes only while the Track waits for its model (SeparateTrack).
        var before: TrackPipelineState? = null
        var next: TrackPipelineState? = null
        tracks.update(id) { track ->
            val now = track.pipeline
            before = now
            val playing = track.instrumentalModel
            next = when {
                track.vocalFree || !track.hasOriginalAudio -> null
                now == TrackPipelineState.Ready(model) || (now is TrackPipelineState.Separating && now.model == model) -> null
                model == playing -> TrackPipelineState.Ready(model)
                else -> TrackPipelineState.Separating(model, previous = playing)
            }
            // The model is kept on the Track too, so retrying a failed re-separation uses it.
            next?.let { track.copy(separationModel = model, pipeline = it) }
        }
        if (next == null) return
        // Then stop a replaced job, and only then queue the new one, which the cancel would stop
        // too. If the replaced job is slow to stop, the new one waits for it (SeparateTrack).
        if (before is TrackPipelineState.Separating) jobs.cancelSeparation(id)
        if (next is TrackPipelineState.Separating) jobs.separate(id, model)
    }

    /**
     * Runs a failed step again. The Track goes back to where that step starts, and [run] queues it
     * like a new one: Added for a download (continuing after the bytes it has), Downloaded for a
     * Separation (its Original Audio is still stored). A failed re-separation is re-separated with
     * the same model, so the previous Instrumental plays on meanwhile (ADR 22).
     */
    suspend fun retry(id: TrackId) {
        val track = tracks.get(id) ?: return
        val state = track.pipeline as? TrackPipelineState.Failed ?: return
        if (state.previous != null) return reseparate(id, track.separationModel ?: unpickedModel)
        val restart = when (state.step) {
            PipelineStep.Downloading -> TrackPipelineState.Added
            PipelineStep.Separating -> TrackPipelineState.Downloaded
        }
        tracks.updatePipeline(id, restart)
    }
}
