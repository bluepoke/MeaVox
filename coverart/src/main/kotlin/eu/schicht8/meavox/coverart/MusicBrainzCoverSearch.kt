// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.coverart

import eu.schicht8.meavox.core.model.FailureKind
import eu.schicht8.meavox.core.model.FailureReason
import eu.schicht8.meavox.core.port.CoverCandidate
import eu.schicht8.meavox.core.port.CoverSearch
import eu.schicht8.meavox.core.port.CoverSearchUnavailable
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * [CoverSearch] on MusicBrainz (https://musicbrainz.org), free, open and keyless (#51): it searches
 * release groups (albums and singles) by title and artist, and their front covers come from the Cover
 * Art Archive (https://coverartarchive.org). Requests name the app in the User-Agent and reach
 * MusicBrainz at most once a second, as it asks; a search that comes sooner waits. [now] is a
 * monotonic clock in milliseconds.
 */
class MusicBrainzCoverSearch(
    /** See [userAgent]. */
    private val userAgent: String,
    private val http: Http = UrlHttp,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) : CoverSearch {
    class Response(val status: Int, val body: String)

    fun interface Http {
        /** One GET; throws [IOException] if there is no answer at all. */
        fun get(url: String, userAgent: String): Response
    }

    override suspend fun search(title: String, artist: String?): List<CoverCandidate> {
        val query = buildList {
            add("releasegroup:${phrase(title)}")
            artist?.takeIf { it.isNotBlank() }?.let { add("artist:${phrase(it)}") }
        }.joinToString(" AND ")
        val params = listOf("query" to query, "fmt" to "json", "limit" to "$LIMIT")
            .joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }
        val response = try {
            spaced { runInterruptible(Dispatchers.IO) { http.get("$BASE/ws/2/release-group?$params", userAgent) } }
        } catch (e: IOException) {
            throw CoverSearchUnavailable(FailureReason(FailureKind.ServiceUnreachable, e.message, NAME), e)
        }
        when {
            response.status == 200 -> Unit
            response.status == 429 || response.status >= 500 ->
                throw CoverSearchUnavailable(FailureReason(FailureKind.ServerBusy, "${response.status}", NAME))
            else -> throw CoverSearchUnavailable(FailureReason(FailureKind.ServerError, "${response.status}", NAME))
        }
        return try {
            Json.parseToJsonElement(response.body).jsonObject["release-groups"]?.jsonArray.orEmpty().mapNotNull { candidate(it.jsonObject) }
        } catch (e: IllegalArgumentException) {
            throw CoverSearchUnavailable(FailureReason(FailureKind.AnswerUnreadable, e.message, NAME), e)
        }
    }

    private val turn = Mutex()
    private var lastRequestAt: Long? = null

    /** Runs [request] no sooner than [SPACING_MS] after the one before, one at a time. */
    private suspend fun <T> spaced(request: suspend () -> T): T = turn.withLock {
        lastRequestAt?.let { last -> delay(last + SPACING_MS - now()) }
        lastRequestAt = now()
        request()
    }

    private fun candidate(group: JsonObject): CoverCandidate? {
        val id = group.string("id") ?: return null
        val title = group.string("title") ?: return null
        val artist = group["artist-credit"]?.jsonArray?.joinToString("") { credit ->
            val c = credit.jsonObject
            (c.string("name") ?: "") + (c.string("joinphrase") ?: "")
        }?.ifBlank { null }
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
        private const val LIMIT = 25

        /** MusicBrainz asks for at most one request a second (its rate-limiting rules). */
        private const val SPACING_MS = 1_000L

        /** The service's name in failure reasons. */
        private const val NAME = "MusicBrainz"

        /** The User-Agent for app version [version]: name, version and the project's homepage, as MusicBrainz asks. */
        fun userAgent(version: String) = "MeaVox/$version ( https://github.com/bluepoke/MeaVox )"
    }
}
