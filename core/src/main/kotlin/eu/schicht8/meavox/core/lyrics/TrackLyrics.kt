// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.lyrics

import eu.schicht8.meavox.core.model.FailureReason
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.port.LyricsCandidate
import eu.schicht8.meavox.core.port.LyricsQuery
import kotlinx.coroutines.flow.Flow

/** Where a Track's Lyrics stand (ADR 6). A Track without any yet has none of these. */
sealed interface TrackLyrics {
    /** A search is queued or running. */
    data object Searching : TrackLyrics

    /** The best match for [query]; shown already, until the user confirms it or picks another. */
    data class Suggested(val lyrics: LyricsCandidate, val query: LyricsQuery) : TrackLyrics

    /** The user confirmed or picked these Lyrics. */
    data class Confirmed(val lyrics: LyricsCandidate) : TrackLyrics

    /** Nothing matched [query]; the user can search manually. */
    data class NotFound(val query: LyricsQuery) : TrackLyrics

    /** The search failed for a [reason] that retrying won't fix; the user can search manually. */
    data class Failed(val reason: FailureReason) : TrackLyrics
}

/** The Lyrics to show: the suggested or confirmed match, if there is one. */
fun TrackLyrics?.candidate(): LyricsCandidate? = when (this) {
    is TrackLyrics.Suggested -> lyrics
    is TrackLyrics.Confirmed -> lyrics
    else -> null
}

/** Persistence of each Track's Lyrics. Implemented by the data layer (Room); faked in tests. */
interface LyricsRepository {
    fun observe(track: TrackId): Flow<TrackLyrics?>

    /** Every Track that has Lyrics state, updated as it changes. */
    fun observeAll(): Flow<Map<TrackId, TrackLyrics>>

    suspend fun get(track: TrackId): TrackLyrics?

    suspend fun set(track: TrackId, lyrics: TrackLyrics)

    /** Stores [lyrics] unless the Track has Lyrics state already; returns whether it did. Must be atomic. */
    suspend fun addIfAbsent(track: TrackId, lyrics: TrackLyrics): Boolean

    /** The Track's Lyrics Offset in ms (0 until set), updated as it changes. Independent of [set]. */
    fun observeOffset(track: TrackId): Flow<Long>

    suspend fun setOffset(track: TrackId, offsetMs: Long)
}
