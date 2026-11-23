// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.port

import eu.schicht8.meavox.core.model.FailureReason

/** A web source of Lyrics, e.g. LRCLIB (ADR 6). */
interface LyricsProvider {
    /** @throws LyricsUnavailable when the provider can't be asked or doesn't answer usably. */
    suspend fun search(query: LyricsQuery): List<LyricsCandidate>
}

/**
 * The provider couldn't answer. [retryable] failures (no network, server busy) may work if the
 * same search runs again later; others won't.
 */
class LyricsUnavailable(val reason: FailureReason, val retryable: Boolean, cause: Throwable? = null) : Exception(reason.toString(), cause)

data class LyricsQuery(val artist: String?, val title: String, val durationMs: Long?)

/**
 * One Lyrics match as delivered by the provider. [plain] is untimed text; [synced] is raw LRC
 * (line-synced, possibly enhanced). Either may be missing, not both.
 */
data class LyricsCandidate(
    val providerItemId: String,
    val artist: String?,
    val title: String,
    val durationMs: Long?,
    val plain: String?,
    val synced: String?,
) {
    init {
        require(plain != null || synced != null) { "a LyricsCandidate needs plain or synced Lyrics" }
    }
}
