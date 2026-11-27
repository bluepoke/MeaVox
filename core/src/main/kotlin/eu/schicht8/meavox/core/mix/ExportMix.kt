// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.mix

import eu.schicht8.meavox.core.export.export
import eu.schicht8.meavox.core.library.TrackFiles
import eu.schicht8.meavox.core.library.TrackRepository
import eu.schicht8.meavox.core.port.ExportFormat
import eu.schicht8.meavox.core.port.ExportStore
import eu.schicht8.meavox.core.port.ExportTags
import eu.schicht8.meavox.core.port.ExportedFile
import eu.schicht8.meavox.core.port.OfflineRenderer
import eu.schicht8.meavox.core.session.SettingsRepository
import eu.schicht8.meavox.core.session.TakeRepository

/**
 * Export of a Mix (GLOSSARY: Export, ADR 8): the Offline Render of its Take's span, written to
 * `Music/MeaVox/`. The render uses the same DSP as Review and the Mix Preview. It is tagged with the
 * Track's artist featuring the singer, whose name is a setting, and carries the Track's Cover Art.
 */
class ExportMix(
    private val tracks: TrackRepository,
    private val takes: TakeRepository,
    private val mixes: MixRepository,
    private val files: TrackFiles,
    private val renderer: OfflineRenderer,
    private val store: ExportStore,
    private val settings: SettingsRepository,
) {
    /**
     * Renders and stores the Mix in [format]; [onProgress] receives 0..1 as it goes. Cancelling
     * removes the unfinished file.
     * @throws MixUnavailable if the Mix can't be rendered now (e.g. its Instrumental is being made again).
     */
    suspend fun export(id: MixId, format: ExportFormat, onProgress: (Float) -> Unit = {}): ExportedFile {
        val parts = resolveMix(id, mixes, tracks, takes, files)
        val render = parts.render(files)
        val tags = ExportTags(
            "${parts.track.title} (${parts.mix.name})",
            mixArtist(parts.track.artist, settings.singerName()),
            files.cover(parts.track.id),
        )
        return store.export(tags, format, render.sampleRate, render.frames, onProgress = onProgress) { sink ->
            renderer.render(render, sink)
        }
    }
}

/** The artist a Mix is tagged with: "<artist> feat. <singer>", or whichever of the two is known. */
private fun mixArtist(artist: String?, singer: String?): String? {
    val original = artist?.trim()?.takeIf { it.isNotEmpty() }
    val featured = singer?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        original != null && featured != null -> "$original feat. $featured"
        else -> original ?: featured
    }
}
