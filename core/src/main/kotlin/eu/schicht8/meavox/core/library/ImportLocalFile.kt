// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.library

import eu.schicht8.meavox.core.model.SeparationModelId
import eu.schicht8.meavox.core.model.SourceId
import eu.schicht8.meavox.core.model.Track
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.model.TrackPipelineState
import eu.schicht8.meavox.core.port.CoverArtLoader
import eu.schicht8.meavox.core.port.LocalFile
import kotlinx.coroutines.CancellationException

/**
 * Imports a picked or shared file as a Track of the Local File Source: the content is copied
 * into app storage as Original Audio, and the Track moves Added → Downloaded (ADR 8, ADR 10). A
 * picture the file embeds becomes the Track's Cover Art (#42).
 */
class ImportLocalFile(private val tracks: TrackRepository, private val files: TrackFiles, private val covers: CoverArtLoader) {
    /**
     * Imports [file] with the [title] and [artist] the user settled on (#52; by default its file name
     * and none), marked [vocalFree] if asked, to be separated with [separationModel] (ADR 19);
     * content imported before keeps its Track unchanged (ADR 14).
     */
    suspend operator fun invoke(
        file: LocalFile,
        vocalFree: Boolean = false,
        separationModel: SeparationModelId? = null,
        title: String? = null,
        artist: String? = null,
    ): Track {
        val staged = file.open().use { files.stage(it) }
        val id = TrackId(SourceId.Local, staged.sha256)
        val track = tracks.addIfAbsent(
            Track(
                id = id,
                title = title?.trim()?.ifEmpty { null } ?: titleFrom(file.displayName),
                artist = artist?.trim()?.ifEmpty { null },
                durationMs = null,
                pipeline = TrackPipelineState.Added,
                vocalFree = vocalFree,
                separationModel = separationModel,
            ),
        )
        // Same content imported before: keep that Track and its stored file untouched. Only a
        // Track still in Added (a previous import that never finished) gets its file now.
        if (track.pipeline != TrackPipelineState.Added) {
            files.discard(staged)
            return track
        }
        files.commitOriginalAudio(staged, id)
        storeEmbeddedCover(id)
        return tracks.updatePipeline(id, TrackPipelineState.Downloaded)
    }

    private suspend fun storeEmbeddedCover(id: TrackId) {
        try {
            covers.embeddedIn(files.originalAudio(id))?.let { files.storeCover(id, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A picture that can't be read leaves the Track without Cover Art, not without its audio.
        }
    }

    private fun titleFrom(displayName: String?): String =
        displayName?.substringBeforeLast('.')?.trim()?.ifEmpty { null } ?: "Untitled"
}
