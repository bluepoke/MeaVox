// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.engine

/** JNI surface of the native `IntegratedLoudness` (#44). Every handle from [create] must be [release]d. Use [NativeLoudnessMeter]. */
internal object NativeLoudness {
    init {
        System.loadLibrary("meavox_engine")
    }

    external fun create(sampleRate: Int): Long
    /** Adds [frames] interleaved frames of [channels] channels from frame [offsetFrames] of [interleaved]. */
    external fun add(handle: Long, interleaved: FloatArray, offsetFrames: Int, frames: Int, channels: Int)
    /** The integrated loudness of what was added in LUFS, or NaN if nothing is measurable. */
    external fun integrated(handle: Long): Double
    external fun release(handle: Long)
}
