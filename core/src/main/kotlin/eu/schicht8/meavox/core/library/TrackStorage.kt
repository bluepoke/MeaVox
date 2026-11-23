// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.library

import eu.schicht8.meavox.core.mix.MixRepository
import eu.schicht8.meavox.core.model.SeparationModelId
import eu.schicht8.meavox.core.model.Take
import eu.schicht8.meavox.core.model.Track
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.model.TrackPipelineState
import eu.schicht8.meavox.core.pipeline.PipelineJobs
import eu.schicht8.meavox.core.session.TakeRepository
import kotlinx.coroutines.flow.first

/**
 * The storage screen (ADR 8): what each Track takes up, and deleting stems, Takes or whole Tracks.
 * [models] are the Separation Models whose stems a Track may hold.
 */
class TrackStorage(
    private val tracks: TrackRepository,
    private val takes: TakeRepository,
    private val mixes: MixRepository,
    private val files: TrackFiles,
    private val jobs: PipelineJobs,
    private val models: List<SeparationModelId>,
) {
    /** Every Track's usage, largest first. */
    suspend fun usage(): List<TrackUsage> = tracks.observeAll().first().map { usage(it) }.sortedByDescending { it.totalBytes }

    /**
     * Deletes the Track's stems of [model]. If the Track is Ready with them, it becomes
     * [TrackPipelineState.StemsDeleted] and they are made again when the Track is next opened.
     * @throws IllegalStateException while they are in use (see [Track.stemsInUse]): being made, or
     * playing while the Track is re-separated (ADR 22).
     */
    suspend fun deleteStems(id: TrackId, model: SeparationModelId) {
        val track = checkNotNull(tracks.get(id)) { "no Track $id" }
        track.stemsInUse(model)?.let { error("the stems of ${model.value} are in use: $it") }
        // State first, then the files: nothing tries to play stems that are gone.
        if (track.pipeline == TrackPipelineState.Ready(model)) tracks.updatePipeline(id, TrackPipelineState.StemsDeleted(model))
        files.deleteStems(id, model)
    }

    /** Deletes a kept Take, its Mixes and its audio. */
    suspend fun deleteTake(take: Take) {
        // Rows first, then the file: the list never shows a Take whose audio is gone.
        mixes.deleteForTake(take.id)
        takes.delete(take.id)
        files.deleteTake(take.track, take.id)
    }

    /**
     * Deletes the Track and everything it owns: its jobs stop, then its Mixes, Takes, Lyrics and files go.
     * The UI asks for confirmation first.
     */
    suspend fun deleteTrack(id: TrackId) {
        // Jobs first, so they stop writing; rows before files, so nothing lists a Track whose
        // audio is gone. A job that still writes a moment later is cleared by removeOrphanedFiles().
        jobs.cancelAll(id)
        mixes.deleteAll(id)
        takes.deleteAll(id)
        tracks.delete(id)
        files.deleteAll(id)
    }

    /**
     * Removes files written by a job that hadn't stopped yet: those of Tracks no longer in the
     * Library (deleted), and stems of Vocal-Free Tracks (marked during Separation). Safe at any
     * time: a Track's row always exists before its files (imports and downloads add the Track first).
     */
    suspend fun removeOrphanedFiles() {
        val all = tracks.observeAll().first()
        files.deleteAllExcept(all.mapTo(HashSet()) { it.id })
        for (track in all.filter { it.vocalFree }) models.forEach { files.deleteStems(track.id, it) }
    }

    private suspend fun usage(track: Track): TrackUsage {
        // A Vocal-Free Track has no stems to show or regenerate (ADR 14); leftovers go at start.
        val stems = if (track.vocalFree) {
            emptyMap()
        } else {
            models.associateWith { files.sizeOfStems(track.id, it) + files.sizeOf(files.separationCheckpoint(track.id, it)) }
                .filterValues { it > 0 }
        }
        return TrackUsage(
            track = track,
            originalAudioBytes = files.sizeOf(files.originalAudio(track.id)),
            stemBytes = stems,
            takes = takes.observe(track.id).first().map { TakeUsage(it, files.sizeOf(files.take(track.id, it.id))) },
        )
    }
}

/** What one Track takes up, by category. */
data class TrackUsage(
    val track: Track,
    val originalAudioBytes: Long,
    /** Instrumental and Vocal Stem (and any unfinished Separation) per Separation Model that has some. */
    val stemBytes: Map<SeparationModelId, Long>,
    /** The Track's kept Takes, oldest first. */
    val takes: List<TakeUsage>,
) {
    val totalBytes: Long get() = originalAudioBytes + stemBytes.values.sum() + takes.sumOf { it.bytes }
}

data class TakeUsage(val take: Take, val bytes: Long)
