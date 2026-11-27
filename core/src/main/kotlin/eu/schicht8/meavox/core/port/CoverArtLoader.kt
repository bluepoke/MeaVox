// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.port

import eu.schicht8.meavox.core.model.AudioFile
import eu.schicht8.meavox.core.model.CoverArt

/**
 * Gets a Track's Cover Art (#42), always as a square JPEG of a size Exports can carry (#50), whatever
 * format and shape the picture came in: from the Source, or from the file a Track was imported from.
 */
interface CoverArtLoader {
    /**
     * The picture at [url]. Throws [NoPicture] if there is none (gone, or not a picture), and an
     * [java.io.IOException] if it can't be downloaded now.
     */
    suspend fun download(url: String): CoverArt

    /** The picture [audio] embeds (e.g. an MP3's ID3 front cover), or null if it has none. */
    suspend fun embeddedIn(audio: AudioFile): CoverArt?

    /** [cover] cropped to a centred square, as every picture is now (#50); for covers stored before. Throws if it can't be decoded. */
    suspend fun square(cover: CoverArt): CoverArt
}

/** There is no picture to be had there, and asking again won't change that (#50). */
class NoPicture(message: String) : Exception(message)
