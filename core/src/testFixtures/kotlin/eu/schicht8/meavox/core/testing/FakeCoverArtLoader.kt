// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.testing

import eu.schicht8.meavox.core.model.AudioFile
import eu.schicht8.meavox.core.model.CoverArt
import eu.schicht8.meavox.core.port.CoverArtLoader
import eu.schicht8.meavox.core.port.NoPicture
import java.io.IOException

/**
 * Serves pictures from memory: at a URL, or embedded in an audio file. Anything else isn't there,
 * except URLs in [unreachable], which can't be downloaded now.
 */
class FakeCoverArtLoader : CoverArtLoader {
    private val atUrl = mutableMapOf<String, CoverArt>()
    private val embedded = mutableMapOf<AudioFile, CoverArt>()

    /** URLs [download] was asked for, in order. */
    val downloaded = mutableListOf<String>()

    /** URLs whose download fails as if offline. */
    val unreachable = mutableSetOf<String>()

    /** Runs when [download] starts, e.g. to observe the Track then. */
    var beforeDownload: suspend () -> Unit = {}

    fun serve(url: String, cover: CoverArt) {
        atUrl[url] = cover
    }

    fun embed(audio: AudioFile, cover: CoverArt) {
        embedded[audio] = cover
    }

    override suspend fun download(url: String): CoverArt {
        downloaded += url
        beforeDownload()
        if (url in unreachable) throw IOException("can't reach $url")
        return atUrl[url] ?: throw NoPicture("no picture at $url")
    }

    override suspend fun embeddedIn(audio: AudioFile): CoverArt? = embedded[audio]

    /** Marks a picture as cropped by putting [SQUARED] in front of its bytes. */
    override suspend fun square(cover: CoverArt): CoverArt = CoverArt(byteArrayOf(SQUARED) + cover.jpeg)

    companion object {
        const val SQUARED: Byte = 0x51
    }
}
