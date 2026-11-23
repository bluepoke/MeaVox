// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.port

import eu.schicht8.meavox.core.mix.VocalEffects
import eu.schicht8.meavox.core.model.AudioFile
import eu.schicht8.meavox.core.model.InputDevice
import eu.schicht8.meavox.core.model.OutputRoute
import eu.schicht8.meavox.core.model.Transpose
import kotlinx.coroutines.flow.StateFlow

/**
 * The native Audio Engine as the domain sees it (ADR 4, ADR 13). It plays one loaded Backing
 * Audio file. In a Session it also records the microphone on the same timeline and plays a Take
 * back against the Backing Audio, each at its own Transpose (ADR 5). Monitoring is added by a
 * later slice.
 */
interface AudioEngine {
    val playback: StateFlow<Playback>

    /**
     * Prepares [audio] for playback, paused at the start. Returns as soon as playback can start;
     * the rest may still be prepared in the background (see [Playback.bufferedMs]).
     * [onProgress] receives 0..1 until the whole file is prepared; it may be called on any thread.
     * Drops any Take audio the engine holds.
     */
    suspend fun load(audio: AudioFile, onProgress: (Float) -> Unit = {}): LoadId

    /**
     * Stops playback, cancels background preparation and releases the audio of [load] — unless a
     * newer [load] call has replaced it, in which case nothing happens.
     */
    fun unload(load: LoadId)

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    /**
     * Transpose of plain playback of the Backing Audio, tempo unchanged (ADR 5). Applies at once
     * while playing, without restarting. Recording and Take playback keep their own Transpose.
     */
    fun setTranspose(transpose: Transpose)

    // --- Session face (ADR 4, ADR 9) ---

    /**
     * Switches to Session audio (streams that keep running, microphone) until [endSession]. The loaded audio stays loaded,
     * and later [load] calls stay in Session audio.
     */
    fun beginSession()

    /** Returns to plain, untransposed playback, closing the microphone and dropping any Take audio. */
    fun endSession()

    /** Microphone level and device while it is open (see [enableMicrophone]). */
    val input: StateFlow<InputLevel>

    /**
     * Opens the microphone for metering and recording during the Session. Needs the platform's
     * microphone permission. Returns false if the microphone can't be opened.
     */
    suspend fun enableMicrophone(): Boolean

    /** Digital gain applied to the microphone before metering and recording, in dB. */
    fun setInputGain(gainDb: Float)

    /**
     * Plays the Backing Audio from [fromMs] at [transpose] and records the microphone on the same
     * timeline, until [stopRecording] or the end of the Backing Audio. [setTranspose] doesn't
     * change the Transpose of a running recording. Replaces any Take audio held.
     * Returns once [playback] shows the recording, or false if it can't start (no microphone,
     * nothing loaded).
     */
    suspend fun startRecording(fromMs: Long, transpose: Transpose): Boolean

    /**
     * Stops a recording that is still running and returns what was captured, or null if
     * nothing was. The engine holds the captured audio as its Take audio until [clearTake],
     * [load], another recording or [loadTake].
     */
    suspend fun stopRecording(): CapturedTake?

    /** Writes the held Take audio to [file] as mono FLAC (ADR 8). */
    suspend fun writeTake(file: AudioFile)

    /**
     * Makes a stored Take the held Take audio, starting at [startFrame] on the timeline.
     * Returns false if it can't be read or doesn't match the loaded Backing Audio's sample rate.
     */
    suspend fun loadTake(file: AudioFile, startFrame: Long): Boolean

    /**
     * Plays the held Take audio together with the Backing Audio at the Take's [transpose], from the
     * Take's Start Position until the Take ends. [setTranspose] doesn't change it meanwhile.
     * Take sample i sounds at timeline position start + i − [alignmentFrames].
     * Returns once [playback] shows it playing, or false if no Take audio is held.
     */
    suspend fun playTake(alignmentFrames: Long, transpose: Transpose): Boolean

    /**
     * Gain of the Backing Audio, in dB, while the held Take audio plays ([playTake]), so the
     * voice can be heard. Plain playback and recording are not affected; the Take never changes.
     */
    fun setReviewBackingGain(gainDb: Float)

    /**
     * Gain of the held Take audio, in dB, while it plays ([playTake]); 0 dB until set. The Mix Preview
     * sets its vocal level; Review plays the Take as recorded.
     */
    fun setTakeGain(gainDb: Float)

    /**
     * Changes the alignment of Take playback while it runs, as passed to [playTake], e.g. when the
     * Vocal Nudge of a Mix being previewed changes.
     */
    fun setTakeAlignment(alignmentFrames: Long)

    /** Drops the held Take audio, stopping its playback. */
    fun clearTake()

    /**
     * Vocal Effects on the held Take audio while it plays ([playTake]), applied live; none until set.
     * The Mix Preview sets its Mix's (#16); Review plays the Take as recorded. With a reverb, Take
     * playback runs on for its tail ([VocalEffects.Reverb.tailMs]) after the Take ends.
     */
    fun setVocalEffects(effects: VocalEffects)

    /**
     * Blends [file], a Vocal Stem sample-aligned with the loaded Instrumental, into the Backing Audio
     * as the Guide Vocal, before it is transposed, at the level [setGuideVocalLevel] sets. Returns
     * once it is decoded past [fromFrame], or false if it can't be read or its rate doesn't match.
     */
    suspend fun loadGuideVocal(file: AudioFile, fromFrame: Long): Boolean

    /** Level of the Guide Vocal against the Backing Audio, 0..1 (see [eu.schicht8.meavox.core.mix.MixSettings.guideVocalLevel]); 0 until set. */
    fun setGuideVocalLevel(level: Float)

    /** Drops the Guide Vocal. */
    fun clearGuideVocal()
}

/** Identifies one [AudioEngine.load] call, so an old owner can't unload a newer load. */
@JvmInline
value class LoadId(val value: Long)

data class Playback(
    val loaded: AudioFile?,
    /** Audio is running: plain playback, recording or Take playback. */
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    /** How far from the start the audio is ready; equals [durationMs] once fully prepared. */
    val bufferedMs: Long = durationMs,
    /** The microphone is being recorded (implies [playing]). */
    val recording: Boolean = false,
    /** The held Take audio is playing along (implies [playing]). */
    val playingTake: Boolean = false,
) {
    companion object {
        val Idle = Playback(loaded = null, playing = false, positionMs = 0, durationMs = 0)
    }
}

/**
 * Take audio as captured by [AudioEngine.stopRecording]. Frame values are on the Session
 * timeline at [sampleRate]. Sample i was recorded while timeline frame [startFrame] + i was
 * being sent to the output, so it is late by about [reportedLatencyFrames] (ADR 13).
 */
data class CapturedTake(
    val startFrame: Long,
    val frames: Long,
    val sampleRate: Int,
    val route: OutputRoute,
    /** The engine's round-trip estimate (output + input latency) when recording started. */
    val reportedLatencyFrames: Long,
) {
    val startPositionMs: Long get() = startFrame * 1000 / sampleRate
    val durationMs: Long get() = frames * 1000 / sampleRate
}

/** The open microphone as seen by the meter (ADR 9). */
data class InputLevel(
    /** The microphone is open (see [AudioEngine.enableMicrophone]). */
    val enabled: Boolean,
    val device: InputDevice?,
    /** Recent peak after gain, 0..1 (full scale), decaying. */
    val peak: Float,
    /** The input reached full scale within the last moment. */
    val clipping: Boolean,
) {
    companion object {
        val Off = InputLevel(enabled = false, device = null, peak = 0f, clipping = false)
    }
}
