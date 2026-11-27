// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.library

import eu.schicht8.meavox.core.port.CoverArtLoader
import eu.schicht8.meavox.core.session.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Crops the Cover Art stored before #50 to squares, once: since then every picture is cropped as it
 * is stored, but a video's picture kept by #42 is 16:9. A cover that can't be decoded stays as it is.
 */
class SquareCovers(
    private val tracks: TrackRepository,
    private val files: TrackFiles,
    private val covers: CoverArtLoader,
    private val settings: SettingsRepository,
) {
    suspend operator fun invoke() {
        if (settings.coversSquared()) return
        for (track in tracks.observeAll().first()) {
            val cover = files.cover(track.id) ?: continue
            try {
                files.storeCover(track.id, covers.square(cover))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Left as it was: still a picture an Export can carry.
            }
        }
        settings.setCoversSquared()
    }
}
