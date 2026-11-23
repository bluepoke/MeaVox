// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.engine

import eu.schicht8.meavox.core.mix.VocalEffects

/** JNI surface of the native `PlaybackEngine`. Every call takes the handle from [create]. Use [OboeAudioEngine]. */
internal object NativePlayback {
    init {
        System.loadLibrary("meavox_engine")
    }

    /** Values of [transport], mirroring the native `Transport` enum. */
    const val IDLE = 0
    const val PLAYING = 1
    const val RECORDING = 2
    const val PLAYING_TAKE = 3

    /** The engine is never destroyed; it lives as long as the process. */
    external fun create(): Long
    external fun beginLoad(handle: Long, channels: Int, sampleRate: Int, estimatedFrames: Long)
    /** Returns false once the reserved capacity is exhausted: stop decoding. */
    external fun appendLoad(handle: Long, interleaved: FloatArray, frames: Int): Boolean
    external fun endLoad(handle: Long)
    external fun discardLoad(handle: Long)
    external fun play(handle: Long): Boolean
    external fun pause(handle: Long)
    external fun seekTo(handle: Long, frame: Long)
    /** Transpose of plain playback in semitones, applied live (ADR 5). */
    external fun setTranspose(handle: Long, semitones: Int)
    external fun transport(handle: Long): Int
    external fun isPlaying(handle: Long): Boolean
    external fun positionFrames(handle: Long): Long
    external fun durationFrames(handle: Long): Long
    external fun decodedFrames(handle: Long): Long
    external fun sampleRate(handle: Long): Int

    // Session
    external fun setSessionMode(handle: Long, enabled: Boolean)
    external fun enableInput(handle: Long): Boolean
    external fun inputEnabled(handle: Long): Boolean
    external fun setInputGain(handle: Long, linear: Float)
    external fun setReviewBackingGain(handle: Long, linear: Float)
    external fun setTakeGain(handle: Long, linear: Float)
    external fun setTakeAlignment(handle: Long, alignmentFrames: Long)
    external fun inputPeak(handle: Long): Float
    external fun clipCount(handle: Long): Long
    external fun outputDeviceId(handle: Long): Int
    external fun inputDeviceId(handle: Long): Int
    external fun startRecording(handle: Long, fromFrame: Long, transpose: Int): Boolean
    external fun stopRecording(handle: Long)
    external fun takeStartFrame(handle: Long): Long
    external fun takeFrames(handle: Long): Long
    external fun takeReportedLatencyFrames(handle: Long): Long
    external fun takeXRunCorrectionFrames(handle: Long): Long
    external fun playTake(handle: Long, alignmentFrames: Long, transpose: Int): Boolean
    external fun clearTake(handle: Long)
    external fun beginTakeLoad(handle: Long, startFrame: Long, sampleRate: Int, estimatedFrames: Long): Boolean
    external fun appendTakeLoad(handle: Long, interleaved: FloatArray, frames: Int, channels: Int)
    external fun writeTakeFlac(handle: Long, path: String): Boolean

    // Mix Preview (#16). [reverb] is [REVERB_DRY], [REVERB_ROOM] or [REVERB_HALL].
    external fun setVocalEffects(handle: Long, reverb: Int, compression: Boolean, highPass: Boolean, tailFrames: Long)
    external fun beginGuideLoad(handle: Long, channels: Int, sampleRate: Int): Boolean
    /** Returns false once the reserved capacity is exhausted: stop decoding. */
    external fun appendGuideLoad(handle: Long, interleaved: FloatArray, frames: Int): Boolean
    external fun clearGuide(handle: Long)
    external fun setGuideLevel(handle: Long, level: Float)
    external fun guideDecodedFrames(handle: Long): Long

    /** Values of the native `Reverb` enum. */
    const val REVERB_DRY = 0
    const val REVERB_ROOM = 1
    const val REVERB_HALL = 2
}

/** The reverb preset as the native engine names it. */
internal fun VocalEffects.Reverb.native(): Int = when (this) {
    VocalEffects.Reverb.Dry -> NativePlayback.REVERB_DRY
    VocalEffects.Reverb.Room -> NativePlayback.REVERB_ROOM
    VocalEffects.Reverb.Hall -> NativePlayback.REVERB_HALL
}
