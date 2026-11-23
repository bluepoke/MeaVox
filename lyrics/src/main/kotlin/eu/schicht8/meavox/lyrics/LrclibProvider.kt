// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.lyrics

import eu.schicht8.meavox.core.model.FailureKind
import eu.schicht8.meavox.core.model.FailureReason
import eu.schicht8.meavox.core.port.LyricsCandidate
import eu.schicht8.meavox.core.port.LyricsProvider
import eu.schicht8.meavox.core.port.LyricsQuery
import eu.schicht8.meavox.core.port.LyricsUnavailable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * [LyricsProvider] on LRCLIB (https://lrclib.net), free and keyless (ADR 6). Searches by track
 * and artist name; the duration only ranks the results (see `LyricsRanking`). Requests name the
 * app in the User-Agent, as LRCLIB asks.
 */
class LrclibProvider(
    /** See [userAgent]. */
    private val userAgent: String,
    private val http: Http = UrlHttp,
) : LyricsProvider {
    class Response(val status: Int, val body: String)

    fun interface Http {
        /** One GET; throws [IOException] if there is no answer at all. */
        fun get(url: String, userAgent: String): Response
    }

    override suspend fun search(query: LyricsQuery): List<LyricsCandidate> {
        val params = buildList {
            add("track_name" to query.title)
            query.artist?.let { add("artist_name" to it) }
        }.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }
        val response = try {
            runInterruptible(Dispatchers.IO) { http.get("$BASE/api/search?$params", userAgent) }
        } catch (e: IOException) {
            throw LyricsUnavailable(FailureReason(FailureKind.ServiceUnreachable, e.message, NAME), retryable = true, e)
        }
        when {
            response.status == 200 -> Unit
            response.status == 429 || response.status >= 500 ->
                throw LyricsUnavailable(FailureReason(FailureKind.ServerBusy, "${response.status}", NAME), retryable = true)
            else -> throw LyricsUnavailable(FailureReason(FailureKind.ServerError, "${response.status}", NAME), retryable = false)
        }
        return try {
            Json.parseToJsonElement(response.body).jsonArray.mapNotNull { candidate(it.jsonObject) }
        } catch (e: IllegalArgumentException) { // includes kotlinx.serialization's SerializationException
            throw LyricsUnavailable(FailureReason(FailureKind.AnswerUnreadable, e.message, NAME), retryable = false, e)
        }
    }

    /** One search result, or null for instrumentals and results without any Lyrics. */
    private fun candidate(item: JsonObject): LyricsCandidate? {
        fun text(key: String) = (item[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        if ((item["instrumental"] as? JsonPrimitive)?.booleanOrNull == true) return null
        val plain = text("plainLyrics")
        val synced = text("syncedLyrics")
        if (plain == null && synced == null) return null
        return LyricsCandidate(
            providerItemId = text("id") ?: return null,
            artist = text("artistName"),
            title = text("trackName") ?: return null,
            durationMs = (item["duration"] as? JsonPrimitive)?.doubleOrNull?.let { (it * 1000).roundToLong() },
            plain = plain,
            synced = synced,
        )
    }

    /** [Http] over `HttpURLConnection` (LRCLIB answers directly, without redirects). */
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
        private const val BASE = "https://lrclib.net"

        /** The provider's name in failure reasons. */
        private const val NAME = "LRCLIB"

        /** The User-Agent for app version [version]: name, version and the project's homepage. */
        fun userAgent(version: String) = "MeaVox/$version (https://github.com/bluepoke/MeaVox)"
    }
}
