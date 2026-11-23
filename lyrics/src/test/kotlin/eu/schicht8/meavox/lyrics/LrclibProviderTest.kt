// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.lyrics

import eu.schicht8.meavox.core.model.FailureKind
import eu.schicht8.meavox.core.model.FailureReason
import eu.schicht8.meavox.core.port.LyricsQuery
import eu.schicht8.meavox.core.port.LyricsUnavailable
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** LRCLIB contract, against responses recorded from lrclib.net (`src/test/resources/lrclib/`). */
class LrclibProviderTest {
    private class RecordedHttp(private val status: Int = 200, private val fixture: String? = null, private val fail: IOException? = null) :
        LrclibProvider.Http {
        val requests = mutableListOf<Pair<String, String>>()

        override fun get(url: String, userAgent: String): LrclibProvider.Response {
            requests += url to userAgent
            fail?.let { throw it }
            val body = fixture?.let { checkNotNull(javaClass.getResource("/lrclib/$it")).readText() } ?: ""
            return LrclibProvider.Response(status, body)
        }
    }

    private fun params(url: String) = URI(url).rawQuery.split('&').associate {
        val (k, v) = it.split('=', limit = 2)
        k to URLDecoder.decode(v, Charsets.UTF_8)
    }

    @Test
    fun `searches by track and artist name and identifies the app`() = runTest {
        val http = RecordedHttp(fixture = "search-beat-it.json")

        LrclibProvider(LrclibProvider.userAgent("0.1.0"), http).search(LyricsQuery("Michael Jackson", "Beat It", 258_000))
        LrclibProvider(LrclibProvider.userAgent("0.1.0"), http).search(LyricsQuery(null, "Beat It & Co", null))

        val (url, userAgent) = http.requests[0]
        assertTrue(url.startsWith("https://lrclib.net/api/search?"), url)
        assertEquals(mapOf("track_name" to "Beat It", "artist_name" to "Michael Jackson"), params(url))
        assertEquals(mapOf("track_name" to "Beat It & Co"), params(http.requests[1].first))
        assertEquals("MeaVox/0.1.0 (https://github.com/bluepoke/MeaVox)", userAgent)
    }

    @Test
    fun `results become candidates with plain and synced Lyrics and the duration in ms`() = runTest {
        val candidates = LrclibProvider(LrclibProvider.userAgent("0.1.0"), RecordedHttp(fixture = "search-beat-it.json")).search(LyricsQuery("Michael Jackson", "Beat It", null))

        assertEquals(6, candidates.size)
        val first = candidates.first()
        assertEquals("7385506", first.providerItemId)
        assertEquals("Michael Jackson", first.artist)
        assertEquals("Beat It", first.title)
        assertEquals(298_000L, first.durationMs)
        assertTrue(first.plain!!.startsWith("They told him, \"Don't you ever come around here\""))
        assertTrue(first.synced!!.startsWith("[01:00.77] They told him"))
        assertEquals(listOf(298_000L, 258_000L, 258_000L, 258_000L, 254_000L, 258_000L), candidates.map { it.durationMs })
    }

    @Test
    fun `instrumental results and empty answers give no candidates`() = runTest {
        assertEquals(emptyList(), LrclibProvider(LrclibProvider.userAgent("0.1.0"), RecordedHttp(fixture = "search-sandstorm.json")).search(LyricsQuery("Darude", "Sandstorm", null)))
        assertEquals(emptyList(), LrclibProvider(LrclibProvider.userAgent("0.1.0"), RecordedHttp(fixture = "search-none.json")).search(LyricsQuery("Keimzeit", "Kling Klang Zzyzx", null)))
    }

    @Test
    fun `network trouble and busy servers are worth retrying, other errors are not`() = runTest {
        val query = LyricsQuery(null, "Beat It", null)
        val offline = assertFailsWith<LyricsUnavailable> { LrclibProvider(LrclibProvider.userAgent("0.1.0"), RecordedHttp(fail = IOException("no route"))).search(query) }
        val busy = assertFailsWith<LyricsUnavailable> { LrclibProvider(LrclibProvider.userAgent("0.1.0"), RecordedHttp(status = 503)).search(query) }
        val limited = assertFailsWith<LyricsUnavailable> { LrclibProvider(LrclibProvider.userAgent("0.1.0"), RecordedHttp(status = 429)).search(query) }
        val refused = assertFailsWith<LyricsUnavailable> { LrclibProvider(LrclibProvider.userAgent("0.1.0"), RecordedHttp(status = 400)).search(query) }

        assertTrue(offline.retryable && busy.retryable && limited.retryable)
        assertFalse(refused.retryable)
        assertEquals(FailureReason(FailureKind.ServiceUnreachable, "no route", "LRCLIB"), offline.reason)
        assertEquals(FailureReason(FailureKind.ServerBusy, "503", "LRCLIB"), busy.reason)
        assertEquals(FailureReason(FailureKind.ServerBusy, "429", "LRCLIB"), limited.reason)
        assertEquals(FailureReason(FailureKind.ServerError, "400", "LRCLIB"), refused.reason)
    }
}
