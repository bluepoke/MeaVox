// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.coverart

import eu.schicht8.meavox.core.model.FailureKind
import eu.schicht8.meavox.core.model.FailureReason
import eu.schicht8.meavox.core.port.CoverCandidate
import eu.schicht8.meavox.core.port.CoverSearch
import eu.schicht8.meavox.core.port.CoverSearchUnavailable
import eu.schicht8.meavox.core.port.RecordingLookup
import eu.schicht8.meavox.core.port.RecordingLookupUnavailable
import eu.schicht8.meavox.core.port.RecordingMatch
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * MusicBrainz (https://musicbrainz.org), free, open and keyless: the [CoverSearch] of #51, which
 * searches release groups (albums and singles) by title and artist, with their front covers from the
 * Cover Art Archive (https://coverartarchive.org); and the [RecordingLookup] of #52, which finds a
 * song's recordings to check the title and artist suggested when it is added. Requests name the app in
 * the User-Agent and reach MusicBrainz at most once a second, both kinds together, as it asks; a request
 * that comes sooner waits. [now] is a monotonic clock in milliseconds.
 */
class MusicBrainzClient(
    /** See [userAgent]. */
    private val userAgent: String,
    private val http: Http = UrlHttp,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) : CoverSearch, RecordingLookup {
    class Response(val status: Int, val body: String)

    fun interface Http {
        /** One GET; throws [IOException] if there is no answer at all. */
        fun get(url: String, userAgent: String): Response
    }

    override suspend fun search(title: String, artist: String?): List<CoverCandidate> =
        get("release-group", query("releasegroup", title, artist), SEARCH_LIMIT, ::CoverSearchUnavailable) { json ->
            json["release-groups"]?.jsonArray.orEmpty().mapNotNull { candidate(it.jsonObject) }
        }

    override suspend fun recordings(title: String, artist: String?): List<RecordingMatch> =
        get("recording", query("recording", title, artist), RECORDING_LIMIT, ::RecordingLookupUnavailable) { json ->
            json["recordings"]?.jsonArray.orEmpty().mapNotNull { recording(it.jsonObject) }
        }

    /** A Lucene query for [title] in [field], and the artist if given. */
    private fun query(field: String, title: String, artist: String?): String = buildList {
        add("$field:${phrase(title)}")
        artist?.takeIf { it.isNotBlank() }?.let { add("artist:${phrase(it)}") }
    }.joinToString(" AND ")

    /**
     * Searches [entity] with [query], spaced from the request before, and reads the answer with [read].
     * When MusicBrainz can't be asked or doesn't answer usably, throws what [unavailable] makes of why.
     */
    private suspend fun <T> get(
        entity: String,
        query: String,
        limit: Int,
        unavailable: (FailureReason, Throwable?) -> Exception,
        read: (JsonObject) -> T,
    ): T {
        val params = listOf("query" to query, "fmt" to "json", "limit" to "$limit")
            .joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }
        val response = try {
            spaced { runInterruptible(Dispatchers.IO) { http.get("$BASE/ws/2/$entity?$params", userAgent) } }
        } catch (e: IOException) {
            throw unavailable(FailureReason(FailureKind.ServiceUnreachable, e.message, NAME), e)
        }
        when {
            response.status == 200 -> Unit
            response.status == 429 || response.status >= 500 ->
                throw unavailable(FailureReason(FailureKind.ServerBusy, "${response.status}", NAME), null)
            else -> throw unavailable(FailureReason(FailureKind.ServerError, "${response.status}", NAME), null)
        }
        return try {
            read(Json.parseToJsonElement(response.body).jsonObject)
        } catch (e: IllegalArgumentException) {
            throw unavailable(FailureReason(FailureKind.AnswerUnreadable, e.message, NAME), e)
        }
    }

    private val turn = Mutex()
    private var lastRequestAt: Long? = null

    /**
     * Starts [request] no sooner than [SPACING_MS] after the one before started. Only the start waits
     * its turn: a slow answer doesn't hold up the next request.
     */
    private suspend fun <T> spaced(request: suspend () -> T): T {
        turn.withLock {
            lastRequestAt?.let { last -> delay(last + SPACING_MS - now()) }
            lastRequestAt = now()
        }
        return request()
    }

    private fun candidate(group: JsonObject): CoverCandidate? {
        val id = group.string("id") ?: return null
        val title = group.string("title") ?: return null
        val artist = artistCredit(group)
        val cover = "$COVER_ART_ARCHIVE/release-group/$id/front"
        return CoverCandidate(
            id = id,
            title = title,
            artist = artist,
            year = group.string("first-release-date")?.take(4)?.toIntOrNull(),
            previewUrl = "$cover-250",
            imageUrls = listOf("$cover-1200", cover),
        )
    }

    private fun recording(item: JsonObject): RecordingMatch? {
        val title = item.string("title") ?: return null
        val score = item["score"]?.jsonPrimitive?.intOrNull ?: 0
        return RecordingMatch(title, artistCredit(item), score)
    }

    /** The artist credit as MusicBrainz shows it: every artist's credited name and the phrase that joins it to the next. */
    private fun artistCredit(item: JsonObject): String? = item["artist-credit"]?.jsonArray?.joinToString("") { credit ->
        val c = credit.jsonObject
        (c.string("name") ?: "") + (c.string("joinphrase") ?: "")
    }?.ifBlank { null }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    /** [text] as a Lucene phrase: in quotes, with quotes and backslashes in it escaped. */
    private fun phrase(text: String) = "\"" + text.trim().replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** [Http] over `HttpURLConnection`. */
    object UrlHttp : Http {
        override fun get(url: String, userAgent: String): Response {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", userAgent)
                connection.setRequestProperty("Accept", "application/json")
                val status = connection.responseCode
                val body = if (status == 200) connection.inputStream.use { it.readBytes().decodeToString() } else ""
                return Response(status, body)
            } finally {
                connection.disconnect()
            }
        }
    }

    companion object {
        private const val BASE = "https://musicbrainz.org"
        private const val COVER_ART_ARCHIVE = "https://coverartarchive.org"
        private const val SEARCH_LIMIT = 25
        private const val RECORDING_LIMIT = 10

        /** MusicBrainz asks for at most one request a second (its rate-limiting rules). */
        private const val SPACING_MS = 1_000L

        /** The service's name in failure reasons. */
        private const val NAME = "MusicBrainz"

        /** The User-Agent for app version [version]: name, version and the project's homepage, as MusicBrainz asks. */
        fun userAgent(version: String) = "MeaVox/$version ( https://github.com/bluepoke/MeaVox )"
    }
}
