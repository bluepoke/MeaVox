// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.newpipe

import eu.schicht8.meavox.core.model.SourceId
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.port.SourceCandidate
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/** NewPipe's search results and stream infos as Source candidates. */
internal object Candidates {
    /** Thumbnails at least this tall look sharp in a list row; smaller ones only as a last resort. */
    private const val THUMBNAIL_MIN_HEIGHT = 160

    fun from(item: StreamInfoItem, service: StreamingService, source: SourceId) =
        candidate(item.url, item.name, item.uploaderName, item.duration, item.thumbnails, service, source)

    fun from(info: StreamInfo, service: StreamingService, source: SourceId) =
        candidate(info.url, info.name, info.uploaderName, info.duration, info.thumbnails, service, source)

    /** [durationSeconds] is -1 or 0 when the service doesn't say. */
    private fun candidate(
        url: String,
        title: String,
        uploader: String?,
        durationSeconds: Long,
        thumbnails: List<Image>,
        service: StreamingService,
        source: SourceId,
    ) = SourceCandidate(
        id = TrackId(source, service.streamLHFactory.getId(url)),
        title = title,
        artist = uploader?.ifBlank { null },
        durationMs = durationSeconds.takeIf { it > 0 }?.times(1000),
        thumbnailUrl = thumbnail(thumbnails),
        coverUrl = thumbnails.maxByOrNull { it.height }?.url,
    )

    /** The smallest thumbnail that is big enough, else the biggest there is. */
    private fun thumbnail(images: List<Image>): String? {
        val byHeight = images.sortedBy { it.height }
        return (byHeight.firstOrNull { it.height >= THUMBNAIL_MIN_HEIGHT } ?: byHeight.lastOrNull())?.url
    }
}
