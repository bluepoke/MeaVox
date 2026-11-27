// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.port

import eu.schicht8.meavox.core.model.SourceId
import eu.schicht8.meavox.core.model.TrackId

/**
 * A provider of songs: search → candidates → resolve to media (GLOSSARY: Source, ADR 1).
 * One instance per service (YouTube, SoundCloud, …); see [SourceId].
 */
interface Source {
    val id: SourceId

    /** The service's name as users know it, e.g. "YouTube". */
    val name: String

    suspend fun search(query: String): List<SourceCandidate>

    /** The item a link (shared into the app) points to, or null if the link isn't this Source's. */
    suspend fun lookUp(url: String): SourceCandidate?

    /** Where the media for [sourceTrackId] can be read from right now (stream URL or file). */
    suspend fun resolve(sourceTrackId: String): ResolvedMedia
}

/** One search result of a [Source]. */
data class SourceCandidate(
    val id: TrackId,
    val title: String,
    /** Who published it (e.g. the channel); often, but not always, the artist. */
    val artist: String?,
    val durationMs: Long?,
    /** A small picture of the item, for a list row. */
    val thumbnailUrl: String? = null,
    /** The largest picture of the item there is, which becomes the Track's Cover Art (#42). */
    val coverUrl: String? = null,
)

/**
 * Readable locations of a candidate's media, as URIs the platform layer understands. Stream URIs
 * may expire; [audioKey] names the audio stream itself (format and quality), so a download
 * resumed with a freshly resolved URI can tell whether it is still the same file.
 */
data class ResolvedMedia(val audioUri: String, val audioKey: String, val videoUri: String?)
