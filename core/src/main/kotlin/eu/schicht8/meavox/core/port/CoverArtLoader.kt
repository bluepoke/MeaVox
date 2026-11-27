// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.port

import eu.schicht8.meavox.core.model.AudioFile
import eu.schicht8.meavox.core.model.CoverArt

/**
 * Gets a Track's Cover Art (#42), always as a JPEG of a size Exports can carry, whatever format the
 * picture came in: from the Source, or from the file a Track was imported from.
 */
interface CoverArtLoader {
    /** The picture at [url]. Throws if it can't be downloaded or decoded. */
    suspend fun download(url: String): CoverArt

    /** The picture [audio] embeds (e.g. an MP3's ID3 front cover), or null if it has none. */
    suspend fun embeddedIn(audio: AudioFile): CoverArt?
}
