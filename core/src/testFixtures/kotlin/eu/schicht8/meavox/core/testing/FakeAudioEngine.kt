// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.testing

import eu.schicht8.meavox.core.mix.VocalEffects
import eu.schicht8.meavox.core.model.AudioFile
import eu.schicht8.meavox.core.model.InputDevice
import eu.schicht8.meavox.core.model.OutputRoute
import eu.schicht8.meavox.core.model.Transpose
import eu.schicht8.meavox.core.port.AudioEngine
import eu.schicht8.meavox.core.port.CapturedTake
import eu.schicht8.meavox.core.port.InputLevel
import eu.schicht8.meavox.core.port.LoadId
import eu.schicht8.meavox.core.port.Playback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * State-only [AudioEngine]: tracks the loaded file, transport, position and the held Take audio.
 * Durations are given per file. Time passes only through [advance]. The timeline runs at
 * [SAMPLE_RATE], so 1 ms is 48 frames.
 */
class FakeAudioEngine(private val durationsMs: Map<AudioFile, Long> = emptyMap()) : AudioEngine {
    private val state = MutableStateFlow(Playback.Idle)
    override val playback: StateFlow<Playback> = state

    private val inputState = MutableStateFlow(InputLevel.Off)
    override val input: StateFlow<InputLevel> = inputState

    private var current = LoadId(0)

    /** Whether [beginSession] is in effect. */
    var inSession = false
        private set

    /** What [enableMicrophone] reports: false simulates a missing permission or device. */
    var microphoneAvailable = true

    /** The microphone that [enableMicrophone] opens. */
    var inputDevice = InputDevice.BuiltIn

    var outputRoute = OutputRoute.Wired
    var reportedLatencyFrames = 4_800L

    /** The last value passed to [setInputGain]. */
    var inputGainDb: Float? = null
        private set

    /** The last value passed to [setReviewBackingGain]. */
    var reviewBackingGainDb: Float? = null
        private set

    override fun setReviewBackingGain(gainDb: Float) {
        reviewBackingGainDb = gainDb
    }

    /** The last value passed to [setTakeGain]. */
    var takeGainDb: Float = 0f
        private set

    override fun setTakeGain(gainDb: Float) {
        takeGainDb = gainDb
    }

    override fun setTakeAlignment(alignmentFrames: Long) {
        if (state.value.playingTake) lastAlignmentFrames = alignmentFrames
    }

    /** Take audio written by [writeTake], by file. */
    val writtenTakes = mutableMapOf<AudioFile, CapturedTake>()

    /** The held Take audio and the [playTake] alignment last used. */
    var heldTake: CapturedTake? = null
        private set
    var lastAlignmentFrames: Long? = null
        private set

    private var recordingFromMs: Long? = null

    override suspend fun load(audio: AudioFile, onProgress: (Float) -> Unit): LoadId {
        listOf(0f, 0.5f, 1f).forEach(onProgress)
        heldTake = null
        recordingFromMs = null
        state.value = Playback(audio, playing = false, positionMs = 0, durationMs = durationsMs[audio] ?: 0)
        current = LoadId(current.value + 1)
        return current
    }

    override fun unload(load: LoadId) {
        if (load == current) {
            state.value = Playback.Idle
            heldTake = null
        }
    }

    override fun play() {
        state.update { check(it.loaded != null) { "nothing loaded" }; it.copy(playing = true) }
    }

    override fun pause() {
        if (state.value.recording) finishRecording()
        state.update { it.copy(playing = false, playingTake = false) }
    }

    /** The last value passed to [setTranspose]. */
    var playbackTranspose = Transpose.None
        private set

    private var recordingTranspose = Transpose.None
    private var takeTranspose = Transpose.None

    /** The Transpose the Backing Audio plays at right now. */
    val audibleTranspose: Transpose
        get() = when {
            state.value.recording -> recordingTranspose
            state.value.playingTake -> takeTranspose
            else -> playbackTranspose
        }

    override fun setTranspose(transpose: Transpose) {
        playbackTranspose = transpose
    }

    override fun seekTo(positionMs: Long) {
        state.update { it.copy(positionMs = positionMs.coerceIn(0, it.durationMs)) }
    }

    override fun beginSession() {
        inSession = true
    }

    override fun endSession() {
        inSession = false
        heldTake = null
        playbackTranspose = Transpose.None
        inputState.value = InputLevel.Off
        state.update { it.copy(playing = false, recording = false, playingTake = false) }
    }

    override suspend fun enableMicrophone(): Boolean {
        check(inSession) { "no Session" }
        if (!microphoneAvailable) return false
        inputState.value = InputLevel(enabled = true, device = inputDevice, peak = 0f, clipping = false)
        return true
    }

    override fun setInputGain(gainDb: Float) {
        inputGainDb = gainDb
    }

    override suspend fun startRecording(fromMs: Long, transpose: Transpose): Boolean {
        if (!inputState.value.enabled || state.value.loaded == null) return false
        heldTake = null
        recordingTranspose = transpose
        val from = fromMs.coerceIn(0, state.value.durationMs)
        recordingFromMs = from
        state.update { it.copy(playing = true, recording = true, playingTake = false, positionMs = from) }
        return true
    }

    override suspend fun stopRecording(): CapturedTake? {
        if (state.value.recording) finishRecording()
        return heldTake
    }

    private fun finishRecording() {
        val from = recordingFromMs ?: return
        recordingFromMs = null
        val frames = (state.value.positionMs - from) * FRAMES_PER_MS
        heldTake = if (frames > 0) {
            CapturedTake(from * FRAMES_PER_MS, frames, SAMPLE_RATE, outputRoute, reportedLatencyFrames)
        } else {
            null
        }
        state.update { it.copy(playing = false, recording = false) }
    }

    override suspend fun writeTake(file: AudioFile) {
        writtenTakes[file] = checkNotNull(heldTake) { "no Take audio held" }
    }

    override suspend fun loadTake(file: AudioFile, startFrame: Long): Boolean {
        val stored = writtenTakes[file] ?: return false
        heldTake = stored.copy(startFrame = startFrame)
        return true
    }

    override suspend fun playTake(alignmentFrames: Long, transpose: Transpose): Boolean {
        val take = heldTake ?: return false
        lastAlignmentFrames = alignmentFrames
        takeTranspose = transpose
        state.update {
            it.copy(playing = true, playingTake = true, recording = false, positionMs = take.startFrame / FRAMES_PER_MS)
        }
        return true
    }

    override fun clearTake() {
        heldTake = null
        state.update { if (it.playingTake) it.copy(playing = false, playingTake = false) else it }
    }

    /** The Vocal Effects held Take audio plays with. */
    var vocalEffects = VocalEffects.None
        private set

    override fun setVocalEffects(effects: VocalEffects) {
        vocalEffects = effects
    }

    /** The Vocal Stem blended in as the Guide Vocal, if one is loaded. */
    var guideVocal: AudioFile? = null
        private set
    var guideVocalLevel = 0f
        private set

    override suspend fun loadGuideVocal(file: AudioFile, fromFrame: Long): Boolean {
        guideVocal = file
        return true
    }

    override fun setGuideVocalLevel(level: Float) {
        guideVocalLevel = level
    }

    override fun clearGuideVocal() {
        guideVocal = null
    }

    /** Simulates playback time passing; recording and Take playback stop at their ends. */
    fun advance(ms: Long) {
        val now = state.value
        if (!now.playing) return
        val position = (now.positionMs + ms).coerceAtMost(now.durationMs)
        state.update { it.copy(positionMs = position) }
        // Take playback runs on for the reverb's tail.
        val takeEndMs = heldTake?.let { (it.startFrame + it.frames) / FRAMES_PER_MS + vocalEffects.reverb.tailMs }
        when {
            position >= now.durationMs -> {
                if (now.recording) finishRecording()
                state.update { it.copy(playing = false, playingTake = false) }
            }
            now.playingTake && takeEndMs != null && position >= takeEndMs ->
                state.update { it.copy(playing = false, playingTake = false) }
        }
    }

    /** Simulates the microphone level the meter reports. */
    fun setInputLevel(peak: Float, clipping: Boolean = false) {
        inputState.update { it.copy(peak = peak, clipping = clipping) }
    }

    /** Simulates plugging in another microphone. */
    fun switchInputDevice(device: InputDevice) {
        inputDevice = device
        inputState.update { if (it.enabled) it.copy(device = device) else it }
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val FRAMES_PER_MS = SAMPLE_RATE / 1000L
    }
}
