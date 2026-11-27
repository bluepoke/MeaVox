// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.coverart

import eu.schicht8.meavox.core.model.FailureKind
import eu.schicht8.meavox.core.port.CoverCandidate
import eu.schicht8.meavox.core.port.CoverSearchUnavailable
import eu.schicht8.meavox.core.port.RecordingMatch
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest

/**
 * The MusicBrainz client: the cover search of #51 (with the Cover Art Archive) and the recording lookup
 * of #52, against responses recorded from musicbrainz.org (`src/test/resources/musicbrainz/`).
 */
class MusicBrainzClientTest {
    private class RecordedHttp(private val status: Int = 200, private val fixture: String? = null, private val fail: IOException? = null) :
        MusicBrainzClient.Http {
        val requests = mutableListOf<Pair<String, String>>()

        override fun get(url: String, userAgent: String): MusicBrainzClient.Response {
            requests += url to userAgent
            fail?.let { throw it }
            val body = fixture?.let { checkNotNull(javaClass.getResource("/musicbrainz/$it")).readText() } ?: ""
            return MusicBrainzClient.Response(status, body)
        }
    }

    private fun params(url: String) = URI(url).rawQuery.split('&').associate {
        val (k, v) = it.split('=', limit = 2)
        k to URLDecoder.decode(v, Charsets.UTF_8)
    }

    private fun search(http: RecordedHttp) = MusicBrainzClient(MusicBrainzClient.userAgent("0.1.0"), http, now = { 0L })

    @Test
    fun `searches release groups by title and artist and identifies the app`() = runTest {
        val http = RecordedHttp(fixture = "release-groups-bad.json")

        search(http).search("Bad", "Michael Jackson")

        val (url, userAgent) = http.requests.single()
        assertEquals("https://musicbrainz.org/ws/2/release-group", url.substringBefore('?'))
        assertEquals(
            mapOf("query" to "releasegroup:\"Bad\" AND artist:\"Michael Jackson\"", "fmt" to "json", "limit" to "25"),
            params(url),
        )
        // The form MusicBrainz suggests: "Application name/<version> ( contact-url )".
        assertEquals("MeaVox/0.1.0 ( https://github.com/bluepoke/MeaVox )", userAgent)
    }

    @Test
    fun `each release group becomes a candidate with its Cover Art Archive front cover`() = runTest {
        val found = search(RecordedHttp(fixture = "release-groups-bad.json")).search("Bad", "Michael Jackson")

        assertEquals(10, found.size)
        assertEquals(
            CoverCandidate(
                id = "a5711a77-42d1-3f4c-830c-e27a96f0800f",
                title = "Bad",
                artist = "Michael Jackson",
                year = 1987,
                previewUrl = "https://coverartarchive.org/release-group/a5711a77-42d1-3f4c-830c-e27a96f0800f/front-250",
                imageUrls = listOf(
                    "https://coverartarchive.org/release-group/a5711a77-42d1-3f4c-830c-e27a96f0800f/front-1200",
                    "https://coverartarchive.org/release-group/a5711a77-42d1-3f4c-830c-e27a96f0800f/front",
                ),
            ),
            found[2],
        )
    }

    @Test
    fun `without an artist only the title is searched, and quotes in it stay part of the phrase`() = runTest {
        val http = RecordedHttp(fixture = "release-groups-none.json")

        search(http).search("Say \"Hello\"", null)
        search(http).search("Bad", "  ")

        assertEquals("releasegroup:\"Say \\\"Hello\\\"\"", params(http.requests[0].first)["query"])
        assertEquals("releasegroup:\"Bad\"", params(http.requests[1].first)["query"])
    }

    @Test
    fun `no match is an empty list`() = runTest {
        assertEquals(emptyList(), search(RecordedHttp(fixture = "release-groups-none.json")).search("Nothing like this", null))
    }

    @Test
    fun `an artist credit of several artists is joined as MusicBrainz shows it, and a missing date is no year`() = runTest {
        val found = search(RecordedHttp(fixture = "release-groups-joined.json")).search("Under Pressure", "Queen")

        assertEquals("Queen & David Bowie", found.single().artist)
        assertEquals(null, found.single().year)
    }

    @Test
    fun `no connection, a busy server and an answer that can't be read are told apart`() = runTest {
        val offline = assertFailsWith<CoverSearchUnavailable> { search(RecordedHttp(fail = IOException("no route"))).search("Bad", null) }
        val busy = assertFailsWith<CoverSearchUnavailable> { search(RecordedHttp(status = 503)).search("Bad", null) }
        val broken = assertFailsWith<CoverSearchUnavailable> { search(RecordedHttp(fixture = "../musicbrainz/not-json.txt")).search("Bad", null) }

        assertEquals(FailureKind.ServiceUnreachable, offline.reason.kind)
        assertEquals(FailureKind.ServerBusy, busy.reason.kind)
        assertEquals(FailureKind.AnswerUnreadable, broken.reason.kind)
        assertEquals("MusicBrainz", offline.reason.service)
    }

    @Test
    fun `searches right after each other reach MusicBrainz at most once a second`() = runTest {
        val sentAt = mutableListOf<Long>()
        val http = MusicBrainzClient.Http { _, _ ->
            sentAt += testScheduler.currentTime
            MusicBrainzClient.Response(200, """{"release-groups":[]}""")
        }
        val search = MusicBrainzClient(MusicBrainzClient.userAgent("0.1.0"), http, now = { testScheduler.currentTime })

        search.search("Bad", null)
        search.search("Thriller", null)
        advanceTimeBy(2_500)
        search.search("Beat It", null)
        val together = List(2) { async { search.search("Smooth Criminal", null) } }
        together.awaitAll()

        assertEquals(listOf(0L, 1_000L, 3_500L, 4_500L, 5_500L), sentAt)
    }

    @Test
    fun `looks up recordings by title and artist, each with its artist credit and score`() = runTest {
        val http = RecordedHttp(fixture = "recordings-in-the-air-tonight.json")

        val found = search(http).recordings("In The Air Tonight", "Phil Collins")

        val (url, userAgent) = http.requests.single()
        assertEquals("https://musicbrainz.org/ws/2/recording", url.substringBefore('?'))
        assertEquals(
            mapOf("query" to "recording:\"In The Air Tonight\" AND artist:\"Phil Collins\"", "fmt" to "json", "limit" to "10"),
            params(url),
        )
        assertEquals("MeaVox/0.1.0 ( https://github.com/bluepoke/MeaVox )", userAgent)
        assertEquals(10, found.size)
        assertEquals(RecordingMatch("In the Air Tonight", "Phil Collins", 100), found.first())
    }

    @Test
    fun `the cover search and the recording lookup share the one request a second`() = runTest {
        val sentAt = mutableListOf<Long>()
        val http = MusicBrainzClient.Http { url, _ ->
            sentAt += testScheduler.currentTime
            MusicBrainzClient.Response(200, if ("/recording" in url) """{"recordings":[]}""" else """{"release-groups":[]}""")
        }
        val client = MusicBrainzClient(MusicBrainzClient.userAgent("0.1.0"), http, now = { testScheduler.currentTime })

        client.search("Bad", null)
        client.recordings("Bad", "Michael Jackson")
        client.search("Thriller", null)

        assertEquals(listOf(0L, 1_000L, 2_000L), sentAt)
    }
}
