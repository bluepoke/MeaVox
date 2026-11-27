// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.library

import eu.schicht8.meavox.core.lyrics.CleanTitle
import eu.schicht8.meavox.core.lyrics.TitleCleaner
import eu.schicht8.meavox.core.model.CoverArt
import eu.schicht8.meavox.core.model.CoverChoice
import eu.schicht8.meavox.core.model.SourceId
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.port.CoverArtLoader
import eu.schicht8.meavox.core.port.CoverCandidate
import eu.schicht8.meavox.core.port.NoPicture
import eu.schicht8.meavox.core.port.Source
import kotlinx.coroutines.CancellationException

/**
 * The user's say over a Track's Cover Art (#51): a cover picked in the cover search, or none, is kept
 * from then on, and nothing found automatically replaces it; going back to the automatic one brings
 * the Source's picture, or the one the imported file embeds.
 */
class ChooseCover(
    private val tracks: TrackRepository,
    private val files: TrackFiles,
    sources: List<Source>,
    private val covers: CoverArtLoader,
) {
    private val missing = FetchMissingCovers(tracks, files, sources, covers)

    /** What the cover search starts with: the Track's title and artist, cleaned as for the Lyrics search. */
    suspend fun searchTerms(id: TrackId): CleanTitle? = tracks.get(id)?.let { TitleCleaner.clean(it.title, it.artist) }

    /**
     * Stores the best picture [candidate] has as the Track's Cover Art, and keeps it.
     * @throws NoPicture if the candidate has no picture after all.
     * @throws java.io.IOException if it can't be downloaded now.
     */
    suspend fun pick(id: TrackId, candidate: CoverCandidate) {
        var cover: CoverArt? = null
        for (url in candidate.imageUrls) {
            cover = try {
                covers.download(url)
            } catch (_: NoPicture) {
                continue
            }
            break
        }
        files.storeCover(id, cover ?: throw NoPicture("${candidate.title} has no picture"))
        tracks.update(id) { it.copy(coverChoice = CoverChoice.Picked) }
    }

    /** Removes the Track's Cover Art, and keeps it without one. */
    suspend fun remove(id: TrackId) {
        tracks.update(id) { it.copy(coverChoice = CoverChoice.Removed) }
        files.deleteCover(id)
    }

    enum class Outcome {
        /** The Track has its automatic cover now, or none if there is none. */
        Done,

        /** The Source couldn't be reached; the Track keeps the cover it had, and its choice. */
        SourceUnreachable,
    }

    /**
     * Goes back to the automatic Cover Art: the Source's picture (looked up again), or the picture the
     * imported file embeds; none if there is none. The cover the Track had stays until the automatic
     * one is certain, so a Source that can't be reached changes nothing.
     */
    suspend fun useAutomatic(id: TrackId): Outcome {
        val before = tracks.get(id) ?: return Outcome.Done
        val track = tracks.update(id) { it.copy(coverChoice = CoverChoice.Automatic, coverLookedUp = false) } ?: return Outcome.Done
        if (track.id.source == SourceId.Local) {
            val embedded = try {
                covers.embeddedIn(files.originalAudio(id))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null // no picture to be had from the file
            }
            if (embedded != null) files.storeCover(id, embedded) else files.deleteCover(id)
            return Outcome.Done
        }
        val version = files.coverVersion(id)
        if (!missing.lookUp(track)) {
            tracks.update(id) { it.copy(coverChoice = before.coverChoice, coverLookedUp = before.coverLookedUp) }
            return Outcome.SourceUnreachable
        }
        // Settled without storing a picture: the Source has none.
        if (files.coverVersion(id) == version) files.deleteCover(id)
        return Outcome.Done
    }
}
