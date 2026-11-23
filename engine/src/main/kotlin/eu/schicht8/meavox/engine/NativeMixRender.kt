// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.engine

/** JNI surface of the native `OfflineMixRender`. Every handle from [create] must be [release]d. Use [NativeOfflineRenderer]. */
internal object NativeMixRender {
    init {
        System.loadLibrary("meavox_engine")
    }

    /** As `to`: render until the Backing Audio ends (the native `kUntilEnd`). */
    const val UNTIL_END = Long.MAX_VALUE

    /**
     * Gains are linear; Take sample j sounds at timeline frame takeStart + j − alignment, through the
     * Vocal Effects ([reverb] as in [NativePlayback.REVERB_DRY]). [guideLevel] is the Guide Vocal's, 0..1.
     */
    external fun create(
        sampleRate: Int,
        from: Long,
        to: Long,
        takeStart: Long,
        alignment: Long,
        transpose: Int,
        backingGain: Float,
        takeGain: Float,
        reverb: Int,
        compression: Boolean,
        highPass: Boolean,
        guideLevel: Float,
    ): Long
    /** Guide Vocal frames, fed before the Backing Audio frames of the same index. */
    external fun appendGuide(handle: Long, interleaved: FloatArray, frames: Int, channels: Int)
    external fun appendTake(handle: Long, interleaved: FloatArray, frames: Int, channels: Int)
    external fun appendBacking(handle: Long, interleaved: FloatArray, frames: Int, channels: Int)
    external fun endBacking(handle: Long)
    external fun needsBacking(handle: Long): Boolean
    /** Moves up to [maxFrames] interleaved stereo frames into [out]; returns how many. */
    external fun drain(handle: Long, out: FloatArray, maxFrames: Int): Int
    external fun release(handle: Long)
}
