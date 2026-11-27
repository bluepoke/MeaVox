// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.library

import eu.schicht8.meavox.core.model.CoverArt
import eu.schicht8.meavox.core.model.CoverChoice
import eu.schicht8.meavox.core.model.FailureKind
import eu.schicht8.meavox.core.model.Track
import eu.schicht8.meavox.core.port.CoverArtLoader
import eu.schicht8.meavox.core.port.MediaUnavailable
import eu.schicht8.meavox.core.port.NoPicture
import eu.schicht8.meavox.core.port.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.io.IOException

/**
 * Cover Art for Tracks of a network Source that have none (#50): mostly those added before #42,
 * which kept no picture, and those whose picture couldn't be downloaded with the audio. Where the
 * picture is comes from the Track, or else from its Source; the picture is stored as if it had come
 * with the download. A Track is looked up for good once it has a cover, or once it's certain there
 * is none: its item is gone, it has no picture, or the picture isn't there. Anything else (no
 * connection, an extractor that is broken for now) is tried again on a later run. A cover the user
 * picked or removed (#51) is left alone. Never touches the Track Pipeline.
 */
class FetchMissingCovers(
    private val tracks: TrackRepository,
    private val files: TrackFiles,
    private val sources: List<Source>,
    private val covers: CoverArtLoader,
) {
    /** Returns whether every Track is settled; false if one is worth trying again later. */
    suspend operator fun invoke(): Boolean {
        var settled = true
        for (track in tracks.observeAll().first()) {
            // A Track still downloading gets its picture with the download (#42).
            if (track.coverLookedUp || track.coverChoice != CoverChoice.Automatic || !track.hasOriginalAudio) continue
            if (files.cover(track.id) != null) continue
            if (!lookUp(track)) settled = false
        }
        return settled
    }

    /** Looks up [track]'s picture once; returns whether it is settled (see the class). */
    internal suspend fun lookUp(track: Track): Boolean {
        val source = sources.firstOrNull { it.id == track.id.source } ?: return true
        val done = try {
            fetch(track, source)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: MediaUnavailable) {
            e.reason.kind == FailureKind.TrackUnavailable // the item is gone; anything else may pass
        } catch (_: NoPicture) {
            true
        } catch (_: IOException) {
            false
        }
        if (done) tracks.update(track.id) { it.copy(coverLookedUp = true) }
        return done
    }

    /** Stores the Track's picture; a picture the Track kept but which is gone is looked up again. */
    private suspend fun fetch(track: Track, source: Source) {
        track.coverUrl?.let { kept ->
            try {
                store(track, covers.download(kept))
                return
            } catch (_: NoPicture) {
                // The Source may have moved it: ask below.
            }
        }
        val url = source.coverUrl(track.id.sourceTrackId) ?: return
        if (url != track.coverUrl) tracks.update(track.id) { it.copy(coverUrl = url) }
        store(track, covers.download(url))
    }

    /** Stores [cover] unless the user picked or removed one meanwhile (#51). */
    private suspend fun store(track: Track, cover: CoverArt) {
        if (tracks.get(track.id)?.coverChoice == CoverChoice.Automatic) files.storeCover(track.id, cover)
    }
}
