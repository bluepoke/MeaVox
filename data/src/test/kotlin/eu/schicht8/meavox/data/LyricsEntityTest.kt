// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.data

import eu.schicht8.meavox.core.lyrics.TrackLyrics
import eu.schicht8.meavox.core.model.FailureKind
import eu.schicht8.meavox.core.model.FailureReason
import eu.schicht8.meavox.core.model.SourceId
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.port.LyricsCandidate
import eu.schicht8.meavox.core.port.LyricsQuery
import kotlin.test.Test
import kotlin.test.assertEquals

class LyricsEntityTest {
    private val track = TrackId(SourceId("local"), "abc")
    private val lyrics = LyricsCandidate("7385506", "Michael Jackson", "Beat It", 298_000, "They told him", "[01:00.77] They told him")
    private val query = LyricsQuery("Michael Jackson", "Beat It", 258_000)

    @Test
    fun `every Lyrics state is stored and read back unchanged`() {
        val states = listOf(
            TrackLyrics.Searching,
            TrackLyrics.Suggested(lyrics, query),
            TrackLyrics.Suggested(lyrics.copy(artist = null, durationMs = null, synced = null), query.copy(artist = null, durationMs = null)),
            TrackLyrics.Confirmed(lyrics.copy(plain = null)),
            TrackLyrics.NotFound(query),
            TrackLyrics.Failed(FailureReason(FailureKind.ServerError, "400", service = "LRCLIB")),
            TrackLyrics.Failed(FailureReason(FailureKind.Unexpected)),
        )
        for (state in states) {
            val row = state.toEntity(track)
            assertEquals(track, row.trackId())
            assertEquals(state, row.toLyrics(), "$state")
        }
    }

    @Test
    fun `a failure reason stored as text before #43 is shown as it was`() {
        val earlier = TrackLyrics.Searching.toEntity(track).copy(status = "failed", failure = "LRCLIB answered HTTP 400")

        assertEquals(TrackLyrics.Failed(FailureReason(FailureKind.Unexpected, "LRCLIB answered HTTP 400")), earlier.toLyrics())
    }
}
