// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core

import eu.schicht8.meavox.core.lyrics.FetchLyrics
import eu.schicht8.meavox.core.lyrics.TrackLyrics
import eu.schicht8.meavox.core.model.FailureKind
import eu.schicht8.meavox.core.model.FailureReason
import eu.schicht8.meavox.core.model.SourceId
import eu.schicht8.meavox.core.model.Track
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.model.TrackPipelineState
import eu.schicht8.meavox.core.port.LyricsCandidate
import eu.schicht8.meavox.core.port.LyricsQuery
import eu.schicht8.meavox.core.port.LyricsUnavailable
import eu.schicht8.meavox.core.testing.FakeLyricsProvider
import eu.schicht8.meavox.core.testing.InMemoryLyricsRepository
import eu.schicht8.meavox.core.testing.InMemoryTrackRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class FetchLyricsTest {
    private val tracks = InMemoryTrackRepository()
    private val lyrics = InMemoryLyricsRepository()
    private val provider = FakeLyricsProvider()
    private val fetch = FetchLyrics(tracks, lyrics, provider)

    private suspend fun track(title: String, artist: String? = null, durationMs: Long? = null) = tracks.addIfAbsent(
        Track(TrackId(SourceId("youtube"), title.hashCode().toString()), title, artist, durationMs, TrackPipelineState.Added),
    )

    private fun candidate(id: String, title: String, durationMs: Long? = 258_000, synced: Boolean = true) = LyricsCandidate(
        providerItemId = id,
        artist = "Michael Jackson",
        title = title,
        durationMs = durationMs,
        plain = "They told him don't you ever come around here",
        synced = if (synced) "[00:21.50] They told him don't you ever come around here" else null,
    )

    @Test
    fun `Lyrics are searched by the cleaned title and the match is suggested`() = runTest {
        val beatIt = candidate("1", "Beat It")
        provider.add(beatIt)
        val track = track("Michael Jackson - Beat It (Official Video)", durationMs = 258_000)

        fetch(track.id)

        val query = LyricsQuery("Michael Jackson", "Beat It", 258_000)
        assertEquals(listOf(query), provider.queries)
        assertEquals(TrackLyrics.Suggested(beatIt, query), lyrics.get(track.id))
    }

    @Test
    fun `the match closest in duration is suggested, synced before plain`() = runTest {
        val otherVersion = candidate("1", "Beat It", durationMs = 301_000) // e.g. a live version
        val plainOnly = candidate("2", "Beat It", durationMs = 258_000, synced = false)
        val synced = candidate("3", "Beat It", durationMs = 259_000)
        listOf(otherVersion, plainOnly, synced).forEach(provider::add)
        val track = track("Michael Jackson - Beat It", durationMs = 258_000)

        fetch(track.id)

        assertEquals(synced, (lyrics.get(track.id) as TrackLyrics.Suggested).lyrics)
    }

    @Test
    fun `when nothing matches with the artist, the title alone is searched`() = runTest {
        val beatIt = candidate("1", "Beat It")
        provider.add(beatIt)
        val track = track("Beat It (Official Video)", artist = "80s Hits Channel")

        fetch(track.id)

        val query = LyricsQuery("80s Hits Channel", "Beat It", null)
        assertEquals(listOf(query, query.copy(artist = null)), provider.queries)
        assertEquals(TrackLyrics.Suggested(beatIt, query), lyrics.get(track.id))
    }

    @Test
    fun `Lyrics nobody has are NotFound, with the query for a manual search`() = runTest {
        val track = track("Keimzeit - Kling Klang")

        fetch(track.id)

        assertEquals(TrackLyrics.NotFound(LyricsQuery("Keimzeit", "Kling Klang", null)), lyrics.get(track.id))
    }

    @Test
    fun `a search that may work later keeps the Lyrics Searching and asks to retry`() = runTest {
        val track = track("Michael Jackson - Beat It")
        lyrics.set(track.id, TrackLyrics.Searching)
        provider.failWith = LyricsUnavailable(FailureReason(FailureKind.ServiceUnreachable, "no network", service = "LRCLIB"), retryable = true)

        val outcome = fetch(track.id)

        assertEquals(FetchLyrics.Outcome.RetryLater, outcome)
        assertEquals(TrackLyrics.Searching, lyrics.get(track.id))
    }

    @Test
    fun `a search that cannot succeed leaves the Lyrics Failed with the reason`() = runTest {
        val track = track("Michael Jackson - Beat It")
        provider.failWith = LyricsUnavailable(FailureReason(FailureKind.ServerError, "400", service = "LRCLIB"), retryable = false)

        val outcome = fetch(track.id)

        assertEquals(FetchLyrics.Outcome.Done, outcome)
        assertEquals(TrackLyrics.Failed(FailureReason(FailureKind.ServerError, "400", service = "LRCLIB")), lyrics.get(track.id))
    }

    @Test
    fun `Lyrics the user already confirmed are not searched again`() = runTest {
        val track = track("Michael Jackson - Beat It")
        val confirmed = TrackLyrics.Confirmed(candidate("9", "Beat It"))
        lyrics.set(track.id, confirmed)

        fetch(track.id) // e.g. the job ran again after process death

        assertEquals(emptyList(), provider.queries)
        assertEquals(confirmed, lyrics.get(track.id))
    }

    @Test
    fun `the right artist beats a cover of the same length`() = runTest {
        val cover = candidate("1", "Beat It", durationMs = 258_000).copy(artist = "Michael Jackson Tribute Band")
        val original = candidate("2", "Beat It", durationMs = 264_000) // the video is a few seconds longer
        provider.add(cover)
        provider.add(original)
        val track = track("Michael Jackson - Beat It (Official Video)", durationMs = 258_000)

        fetch(track.id)

        assertEquals(original, (lyrics.get(track.id) as TrackLyrics.Suggested).lyrics)
    }

    @Test
    fun `a search that keeps failing ends Failed at its last attempt instead of Searching forever`() = runTest {
        val track = track("Michael Jackson - Beat It")
        lyrics.set(track.id, TrackLyrics.Searching)
        provider.failWith = LyricsUnavailable(FailureReason(FailureKind.ServerBusy, "503", service = "LRCLIB"), retryable = true)

        val outcome = fetch(track.id, lastAttempt = true)

        assertEquals(FetchLyrics.Outcome.Done, outcome)
        assertEquals(TrackLyrics.Failed(FailureReason(FailureKind.ServerBusy, "503", service = "LRCLIB")), lyrics.get(track.id))
    }

    @Test
    fun `an unexpected error ends the search Failed`() = runTest {
        val track = track("Michael Jackson - Beat It")
        provider.failWith = IllegalStateException("bug in the provider")

        val outcome = fetch(track.id)

        assertEquals(FetchLyrics.Outcome.Done, outcome)
        assertEquals(TrackLyrics.Failed(FailureReason(FailureKind.Unexpected, "bug in the provider")), lyrics.get(track.id))
    }
}
