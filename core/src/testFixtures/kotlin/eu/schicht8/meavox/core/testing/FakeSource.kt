// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.testing

import eu.schicht8.meavox.core.model.SourceId
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.port.ResolvedMedia
import eu.schicht8.meavox.core.port.Source
import eu.schicht8.meavox.core.port.SourceCandidate

/** In-memory [Source]: search matches the query against title and artist, case-insensitively. */
class FakeSource(override val id: SourceId) : Source {
    override val name: String get() = id.value

    private val catalogue = mutableListOf<SourceCandidate>()

    /** What [resolve] names the audio stream; change it to model the Source offering another format. */
    var audioKey = "fake-audio"

    /** Thrown by [resolve] while set. */
    var resolveFailure: Exception? = null

    fun add(
        sourceTrackId: String,
        title: String,
        artist: String? = null,
        durationMs: Long? = null,
        coverUrl: String? = null,
    ): SourceCandidate =
        SourceCandidate(TrackId(id, sourceTrackId), title, artist, durationMs, coverUrl = coverUrl).also { catalogue += it }

    override suspend fun search(query: String): List<SourceCandidate> =
        catalogue.filter { c ->
            c.title.contains(query, ignoreCase = true) || c.artist?.contains(query, ignoreCase = true) == true
        }

    /** Where [resolve] says the audio of [sourceTrackId] is. */
    fun audioUriOf(sourceTrackId: String) = "fake://${TrackId(id, sourceTrackId).key}/audio"

    /** The link that [lookUp] recognises for [sourceTrackId]. */
    fun urlOf(sourceTrackId: String) = "https://${id.value}.example/${sourceTrackId}"

    override suspend fun lookUp(url: String): SourceCandidate? =
        catalogue.firstOrNull { urlOf(it.id.sourceTrackId) == url }

    override suspend fun resolve(sourceTrackId: String): ResolvedMedia {
        resolveFailure?.let { throw it }
        require(catalogue.any { it.id.sourceTrackId == sourceTrackId }) { "unknown $sourceTrackId" }
        return ResolvedMedia(audioUri = audioUriOf(sourceTrackId), audioKey = audioKey, videoUri = null)
    }

    /** The source IDs [coverUrl] was asked for, in order. */
    val coversAskedFor = mutableListOf<String>()

    /** Thrown by [coverUrl] while set. */
    var coverFailure: Exception? = null

    override suspend fun coverUrl(sourceTrackId: String): String? {
        coversAskedFor += sourceTrackId
        coverFailure?.let { throw it }
        return requireNotNull(catalogue.firstOrNull { it.id.sourceTrackId == sourceTrackId }) { "unknown $sourceTrackId" }.coverUrl
    }
}
