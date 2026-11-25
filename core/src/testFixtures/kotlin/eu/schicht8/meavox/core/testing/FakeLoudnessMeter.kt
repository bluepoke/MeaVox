// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.testing

import eu.schicht8.meavox.core.model.AudioFile
import eu.schicht8.meavox.core.port.LoudnessMeter
import java.io.IOException

/**
 * Reports the integrated loudness set per file in [loudness] (LUFS), whatever span is asked for;
 * a file without one has no measurable loudness, and one in [unreadable] can't be read. Records
 * what was measured.
 */
class FakeLoudnessMeter : LoudnessMeter {
    data class Measured(val audio: AudioFile, val fromFrame: Long, val toFrame: Long)

    val loudness = mutableMapOf<AudioFile, Float>()
    val measured = mutableListOf<Measured>()
    val unreadable = mutableSetOf<AudioFile>()

    override suspend fun integratedLoudness(audio: AudioFile, fromFrame: Long, toFrame: Long): Float? {
        measured += Measured(audio, fromFrame, toFrame)
        if (audio in unreadable) throw IOException("can't read ${audio.path}")
        return loudness[audio]
    }
}
