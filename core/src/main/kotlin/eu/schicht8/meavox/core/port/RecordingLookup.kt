// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.port

import eu.schicht8.meavox.core.model.FailureReason

/** Where a song's proper title and artist are looked up when it is added (#52): MusicBrainz recordings. */
interface RecordingLookup {
    /**
     * Recordings matching [title] (and [artist], if given), best first.
     * @throws RecordingLookupUnavailable when the lookup can't be asked or doesn't answer usably.
     */
    suspend fun recordings(title: String, artist: String?): List<RecordingMatch>
}

/** The recording lookup couldn't answer, because of [reason]. */
class RecordingLookupUnavailable(val reason: FailureReason, cause: Throwable? = null) : Exception(reason.toString(), cause)

/** One recording the lookup found, with how well it matched the question (0..100). */
data class RecordingMatch(val title: String, val artist: String?, val score: Int)
