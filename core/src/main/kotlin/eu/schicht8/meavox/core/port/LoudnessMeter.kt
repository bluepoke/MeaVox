// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.port

import eu.schicht8.meavox.core.model.AudioFile

/**
 * The Audio Engine's loudness measurement (#44), on stored audio: integrated loudness after
 * ITU-R BS.1770 / EBU R128, K-weighted and gated, in LUFS. Gating leaves silence and the pauses
 * between phrases out. A mono file counts on both channels, as a Take plays in a Mix.
 */
interface LoudnessMeter {
    /**
     * The integrated loudness of [audio]'s frames [fromFrame] until [toFrame] (at the rate it decodes
     * at; past its end, as far as it goes), or null if nothing there is measurable: silence, or less
     * than one 400 ms gating block.
     * @throws java.io.IOException if the file can't be read.
     */
    suspend fun integratedLoudness(audio: AudioFile, fromFrame: Long = 0, toFrame: Long = Long.MAX_VALUE): Float?
}
