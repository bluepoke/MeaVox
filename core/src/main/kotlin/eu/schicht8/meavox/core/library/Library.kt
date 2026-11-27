// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.library

import eu.schicht8.meavox.core.model.SeparationModelId
import eu.schicht8.meavox.core.model.Track
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.model.TrackPipelineState
import eu.schicht8.meavox.core.port.SourceCandidate
import eu.schicht8.meavox.core.session.TakeRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest

/** The user's collection of Tracks. */
class Library(private val tracks: TrackRepository, private val takes: TakeRepository, private val files: TrackFiles) {
    fun tracks(): Flow<List<Track>> = tracks.observeAll()

    /**
     * The Library's Tracks with their Take counts and which Cover Art they have, newest first, updated
     * as they change, also when a cover is stored (#49).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun entries(): Flow<List<LibraryEntry>> {
        val all = tracks.observeAll()
        // Looked up again only when a cover changed or Tracks came or went, not on every progress tick.
        val coverVersions = combine(files.coversChanged, all.map { list -> list.map { it.id } }.distinctUntilChanged()) { _, ids -> ids }
            .mapLatest { ids -> ids.associateWith { files.coverVersion(it) } }
        return combine(all, takes.observeCounts(), coverVersions) { list, counts, versions ->
            list.map { LibraryEntry(it, counts[it.id] ?: 0, versions[it.id]) }
        }
    }

    /** Whether a Track with [id] is in the Library, e.g. a search result added before. */
    suspend fun contains(id: TrackId): Boolean = tracks.get(id) != null

    /**
     * Adds a Source search result as a new Track in the [TrackPipelineState.Added] state, marked
     * [vocalFree] if asked, to be separated with [separationModel] once downloaded (ADR 19). If
     * the same Source item was added before, returns that Track unchanged (ADR 10, ADR 14).
     */
    suspend fun add(candidate: SourceCandidate, vocalFree: Boolean = false, separationModel: SeparationModelId? = null): Track {
        val track = Track(
            id = candidate.id,
            title = candidate.title,
            artist = candidate.artist,
            durationMs = candidate.durationMs,
            pipeline = TrackPipelineState.Added,
            vocalFree = vocalFree,
            separationModel = separationModel,
            coverUrl = candidate.coverUrl,
        )
        return tracks.addIfAbsent(track)
    }
}

/**
 * One Track as the Library lists it: the Track (with its Track Pipeline state and last Session), its
 * Take count, and which Cover Art it has ([TrackFiles.coverVersion]; null without one).
 */
data class LibraryEntry(val track: Track, val takeCount: Int, val coverVersion: Long? = null)
