// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.export

import eu.schicht8.meavox.core.library.TrackFiles
import eu.schicht8.meavox.core.library.TrackRepository
import eu.schicht8.meavox.core.mix.fileOf
import eu.schicht8.meavox.core.model.BackingChoice
import eu.schicht8.meavox.core.model.Track
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.model.Transpose
import eu.schicht8.meavox.core.port.ExportFormat
import eu.schicht8.meavox.core.port.ExportStore
import eu.schicht8.meavox.core.port.ExportTags
import eu.schicht8.meavox.core.port.ExportedFile
import eu.schicht8.meavox.core.port.OfflineRenderer
import eu.schicht8.meavox.core.session.BackingAudio

/**
 * Backing Export (GLOSSARY, #24): the Track's Instrumental (the one it uses now) or its Original
 * Audio, alone and full-length, at a Transpose chosen for the export only, through the Offline Render.
 */
class ExportBacking(
    private val tracks: TrackRepository,
    private val files: TrackFiles,
    private val renderer: OfflineRenderer,
    private val store: ExportStore,
) {
    /**
     * What the export dialog offers for the Track now: the Instrumental once the Track is Ready with
     * one, the Original Audio once it is stored (ADR 10); none before. It starts at the Backing Audio
     * the Track plays and at its Last Transpose.
     */
    suspend fun options(id: TrackId): BackingExportOptions {
        val track = tracks.get(id) ?: return BackingExportOptions(emptyList(), BackingChoice.OriginalAudio, Transpose.None, false)
        val layers = BackingChoice.entries.filter { track.offers(it) }
        val layer = track.backingChoice.takeIf { it in layers } ?: BackingChoice.OriginalAudio
        // A Vocal-Free Track never gets an Instrumental (ADR 14).
        return BackingExportOptions(layers, layer, track.lastTranspose, instrumentalToCome = !track.vocalFree && track.instrumentalModel == null)
    }

    /**
     * Renders [layer] of the Track whole at [transpose] and stores it in [format], tagged with the
     * Track's artist and its title with the layer and Transpose ("Title (Instrumental, +2)").
     * [onProgress] receives 0..1. Cancelling removes the unfinished file. The Last Transpose stays.
     * @throws BackingExportUnavailable if the Track doesn't offer [layer] now.
     */
    suspend fun export(
        id: TrackId,
        layer: BackingChoice,
        transpose: Transpose,
        format: ExportFormat,
        onProgress: (Float) -> Unit = {},
    ): ExportedFile {
        val track = tracks.get(id) ?: throw BackingExportUnavailable(BackingExportUnavailable.Why.TrackDeleted)
        if (!track.offers(layer)) {
            throw BackingExportUnavailable(
                if (layer == BackingChoice.Instrumental) BackingExportUnavailable.Why.InstrumentalNotReady else BackingExportUnavailable.Why.NotDownloaded,
            )
        }
        val backing = files.fileOf(
            id,
            when (layer) {
                BackingChoice.Instrumental -> BackingAudio.Instrumental(checkNotNull(track.instrumentalModel))
                BackingChoice.OriginalAudio -> BackingAudio.OriginalAudio
            },
        )
        val rate = renderer.sampleRate(backing)
        val estimated = track.durationMs?.let { it * rate / 1000 }
        val tags = ExportTags(backingExportTitle(track, layer, transpose), track.artist)
        return store.export(tags, format, rate, frames = null, estimatedFrames = estimated, onProgress = onProgress) { sink ->
            renderer.renderBacking(backing, transpose, sink)
        }
    }

    private fun Track.offers(layer: BackingChoice): Boolean = when (layer) {
        BackingChoice.Instrumental -> instrumentalModel != null
        BackingChoice.OriginalAudio -> hasOriginalAudio
    }
}

/**
 * What a Backing Export can be made of now ([layers], GLOSSARY: Backing Audio), and what the dialog
 * starts at. [instrumentalToCome]: the Track has no Instrumental yet but will once vocal removal is done.
 */
data class BackingExportOptions(
    val layers: List<BackingChoice>,
    val layer: BackingChoice,
    val transpose: Transpose,
    val instrumentalToCome: Boolean,
)

/**
 * The title of a Backing Export: the Track's title with the layer and the Transpose, e.g.
 * "Title (Instrumental, +2)" or "Title (Original, −1)", without a Transpose at 0. A Vocal-Free
 * Track's Original Audio is called Instrumental: "Original" means with the artist's vocals (ADR 14).
 */
fun backingExportTitle(track: Track, layer: BackingChoice, transpose: Transpose): String {
    val name = if (layer == BackingChoice.Instrumental || track.vocalFree) "Instrumental" else "Original"
    val key = when {
        transpose.semitones > 0 -> ", +${transpose.semitones}"
        transpose.semitones < 0 -> ", −${-transpose.semitones}"
        else -> ""
    }
    return "${track.title} ($name$key)"
}

/** A Backing Export of that layer can't be made now, because of [why]; the UI words it (#43). */
class BackingExportUnavailable(val why: Why) : IllegalStateException(why.name) {
    enum class Why {
        TrackDeleted,

        /** The Track isn't Ready with an Instrumental. */
        InstrumentalNotReady,

        /** The Original Audio isn't stored yet. */
        NotDownloaded,
    }
}
