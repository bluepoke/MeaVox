// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.port

import eu.schicht8.meavox.core.model.FailureReason

/** Where the user searches for a Track's Cover Art online (#51): MusicBrainz and the Cover Art Archive. */
interface CoverSearch {
    /**
     * Albums and singles matching [title] (and [artist], if given), best first.
     * @throws CoverSearchUnavailable when the search can't be asked or doesn't answer usably.
     */
    suspend fun search(title: String, artist: String?): List<CoverCandidate>
}

/** The search couldn't answer, because of [reason]; the UI words it (#43). */
class CoverSearchUnavailable(val reason: FailureReason, cause: Throwable? = null) : Exception(reason.toString(), cause)

/**
 * One album or single the search found, with where its front cover would be: [previewUrl] small, for
 * the list; [imageUrls] for the picked cover, best first. Whether it has a cover shows only when the
 * picture is loaded.
 */
data class CoverCandidate(
    /** The service's ID of the album or single. */
    val id: String,
    val title: String,
    val artist: String?,
    val year: Int?,
    val previewUrl: String,
    val imageUrls: List<String>,
)
