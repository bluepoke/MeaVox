// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.port

import eu.schicht8.meavox.core.mix.VocalEffects
import eu.schicht8.meavox.core.model.AudioFile
import eu.schicht8.meavox.core.model.Transpose

/**
 * The Audio Engine's Offline Render (GLOSSARY, spec #1, ADR 7): a Mix, or Backing Audio alone for a
 * Backing Export, into PCM. It uses the same DSP and parameters as real-time playback (Review, the
 * Mix Preview, a Session at a Transpose), so an Export sounds like them.
 */
interface OfflineRenderer {
    /**
     * Renders timeline frames [MixRender.fromFrame] until [MixRender.toFrame] as interleaved stereo
     * float at [MixRender.sampleRate] into [sink], in order, then returns. Cancellable.
     * @throws java.io.IOException if a file can't be read or its sample rate doesn't match.
     */
    suspend fun render(render: MixRender, sink: PcmSink)

    /**
     * Renders all of [backing], from its start to its end, at [transpose] (tempo unchanged, ADR 5)
     * as interleaved stereo float at its own sample rate into [sink], then returns how many frames
     * were rendered: as many as [backing] has. Cancellable.
     * @throws java.io.IOException if the file can't be read.
     */
    suspend fun renderBacking(backing: AudioFile, transpose: Transpose, sink: PcmSink): Long

    /**
     * The sample rate [audio] decodes at, and so the rate a Backing Export of it has.
     * @throws java.io.IOException if the file can't be read.
     */
    suspend fun sampleRate(audio: AudioFile): Int
}

/**
 * One Offline Render of a Mix. Frames are on the Session timeline at [sampleRate]. Take sample i
 * sounds at timeline frame [takeStartFrame] + i − [alignmentFrames], as in [AudioEngine.playTake],
 * through [effects]. The Backing Audio plays at [transpose] (ADR 5), with [guideVocal] (a Vocal Stem)
 * blended in at [guideVocalLevel] before it is transposed, so the Guide Vocal is in the same key.
 */
data class MixRender(
    val backing: AudioFile,
    val take: AudioFile,
    val sampleRate: Int,
    val takeStartFrame: Long,
    val alignmentFrames: Long,
    val transpose: Transpose,
    val backingGainDb: Float,
    val takeGainDb: Float,
    val fromFrame: Long,
    val toFrame: Long,
    val effects: VocalEffects = VocalEffects.None,
    val guideVocal: AudioFile? = null,
    val guideVocalLevel: Float = 0f,
) {
    val frames: Long get() = toFrame - fromFrame
}

/** Receives rendered audio: interleaved stereo float frames, in order. */
fun interface PcmSink {
    /** [interleaved] holds `frames * 2` valid samples; it may be reused after the call. */
    fun write(interleaved: FloatArray, frames: Int)
}
