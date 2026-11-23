// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.mix

import eu.schicht8.meavox.core.model.Track
import eu.schicht8.meavox.core.session.BackingAudio

/**
 * Mix-time presets for the Take (GLOSSARY: Vocal Effects, #16): a reverb, gentle compression and a
 * high-pass filter. They are heard in the Mix Preview and rendered into an Export, never applied to
 * Monitoring or Review. [None] leaves the Take as it was recorded.
 */
data class VocalEffects(
    val reverb: Reverb = Reverb.Dry,
    val compression: Boolean = false,
    val highPass: Boolean = false,
) {
    /** The reverb presets, with how long each rings on after the Take's last sample: the Mix runs on that much. */
    enum class Reverb(val tailMs: Int) {
        Dry(0),
        Room(1_000),
        Hall(2_500),
    }

    /** Frames the reverb rings on after the Take's last sample, at [sampleRate]: the Mix runs on that much. */
    fun tailFrames(sampleRate: Int): Long = reverb.tailMs.toLong() * sampleRate / 1000

    companion object {
        val None = VocalEffects()
    }
}

/** Why a Mix can't blend in the Guide Vocal. */
enum class GuideVocalUnavailable {
    /** A Vocal-Free Track has no Vocal Stem (ADR 14). */
    VocalFree,

    /** The Original Audio has the vocals already. */
    OverOriginalAudio,
}

/**
 * Whether a Mix of this Track over [backing] can blend in the Guide Vocal: only over an Instrumental,
 * whose Vocal Stem is the Guide Vocal; null if it can.
 */
fun Track.guideVocalUnavailable(backing: BackingAudio): GuideVocalUnavailable? = when {
    vocalFree -> GuideVocalUnavailable.VocalFree
    backing == BackingAudio.OriginalAudio -> GuideVocalUnavailable.OverOriginalAudio
    else -> null
}
