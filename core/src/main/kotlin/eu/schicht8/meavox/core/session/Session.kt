// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.session

import eu.schicht8.meavox.core.library.TrackFiles
import eu.schicht8.meavox.core.library.TrackRepository
import eu.schicht8.meavox.core.mix.GuideVocalUnavailable
import eu.schicht8.meavox.core.mix.Mix
import eu.schicht8.meavox.core.mix.MixId
import eu.schicht8.meavox.core.mix.MixParts
import eu.schicht8.meavox.core.mix.MixSettings
import eu.schicht8.meavox.core.mix.Mixes
import eu.schicht8.meavox.core.mix.VocalEffects
import eu.schicht8.meavox.core.mix.alignmentFrames
import eu.schicht8.meavox.core.mix.fileOf
import eu.schicht8.meavox.core.mix.guideVocalUnavailable
import eu.schicht8.meavox.core.model.AudioFile
import eu.schicht8.meavox.core.model.BackingChoice
import eu.schicht8.meavox.core.model.OutputPath
import eu.schicht8.meavox.core.model.OutputRoute
import eu.schicht8.meavox.core.model.SeparationModelId
import eu.schicht8.meavox.core.model.Take
import eu.schicht8.meavox.core.model.TakeId
import eu.schicht8.meavox.core.model.Track
import eu.schicht8.meavox.core.model.Transpose
import eu.schicht8.meavox.core.model.latencyFrames
import eu.schicht8.meavox.core.port.AudioEngine
import eu.schicht8.meavox.core.port.CapturedTake
import eu.schicht8.meavox.core.port.InputLevel
import eu.schicht8.meavox.core.port.LoadId
import eu.schicht8.meavox.core.port.OutputStatus
import eu.schicht8.meavox.core.port.Playback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Singing over one Track (GLOSSARY: Session, ADR 9): playback of the Backing Audio, recording
 * Takes from any Start Position, Review with Keep / Discard / Record again, and the Track's
 * kept Takes. Create with [Sessions.open]; call [close] when leaving.
 */
class Session private constructor(
    val track: Track,
    private val scope: CoroutineScope,
    private val deps: Dependencies,
) {
    internal class Dependencies(
        val tracks: TrackRepository,
        val takes: TakeRepository,
        val files: TrackFiles,
        val engine: AudioEngine,
        val settings: SettingsRepository,
        val mixes: Mixes,
        val clock: () -> Long,
        val newTakeId: () -> TakeId,
    )

    private val engine get() = deps.engine

    // Serialises commands and reactions to the engine, so state changes never interleave.
    private val mutex = Mutex()

    // Reactions to the engine and the Track Pipeline; cancelled by close().
    private val observers = CoroutineScope(scope.coroutineContext + Job(scope.coroutineContext[Job]))

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state

    private val _backing = MutableStateFlow<BackingAudio>(BackingAudio.OriginalAudio)

    /**
     * What plays under the singer: the Track's [Track.backingChoice], but the Original Audio until
     * there is an Instrumental (see [backingAudio]).
     */
    val backing: StateFlow<BackingAudio> = _backing

    /**
     * What the Track wants to play now ([backingAudio]). While the Session is busy, or a Mix Preview
     * plays its own Backing Audio, it is loaded once the Session is [SessionState.Idle] again.
     */
    private var wantedBacking: BackingAudio = track.backingAudio()

    private var load: LoadId? = null

    val playback: StateFlow<Playback> get() = engine.playback

    /** The microphone meter: level, clipping and which microphone is in use. */
    val input: StateFlow<InputLevel> get() = engine.input

    private val _inputGainDb = MutableStateFlow(0f)

    /** Input gain of the microphone in use, in dB (remembered per kind of microphone). */
    val inputGainDb: StateFlow<Float> = _inputGainDb

    private val _countdownEnabled = MutableStateFlow(false)

    /** Whether recording starts after a [COUNTDOWN_SECONDS] countdown (ADR 9). */
    val countdownEnabled: StateFlow<Boolean> = _countdownEnabled

    /** The Track's kept Takes, oldest first. */
    val takes: Flow<List<Take>> get() = deps.takes.observe(track.id)

    // --- Playback ---

    /** Plays the Backing Audio from the current position (only while [SessionState.Idle]). */
    fun play() {
        if (_state.value == SessionState.Idle) engine.play()
    }

    /** Pauses free playback, or the playback of the Take in Review. */
    fun pause() {
        val state = _state.value
        if (state == SessionState.Idle || state is SessionState.Review) engine.pause()
    }

    /** Moves the position where playback and the next Take start. */
    fun seekTo(positionMs: Long) {
        if (_state.value == SessionState.Idle) engine.seekTo(positionMs)
    }

    /**
     * Stores the Backing Audio [choice] for the Track. During free playback it is heard at once,
     * at the same position; while a Take is recorded, reviewed or played back, once the Session is
     * Idle again. Returns false, and changes nothing, for the Instrumental while the Track has none.
     */
    suspend fun chooseBacking(choice: BackingChoice): Boolean {
        var chosen = false
        deps.tracks.update(track.id) { now ->
            chosen = choice == BackingChoice.OriginalAudio || now.instrumentalModel != null
            if (chosen) now.copy(backingChoice = choice) else null
        }
        // The Track observer in observe() loads it.
        return chosen
    }

    // --- Transpose (ADR 5) ---

    private val _transpose = MutableStateFlow(track.lastTranspose)

    /**
     * The Transpose control: what free playback plays at and the next Take is recorded at. It opens
     * at the Track's Last Transpose.
     */
    val transpose: StateFlow<Transpose> = _transpose

    /**
     * Sets the Transpose control; free playback follows at once. It is kept as the Track's Last
     * Transpose, whether or not a Take is recorded (#23). A Take being recorded or played back keeps
     * its own Transpose, and so do their Mixes (ADR 5). Ignored once the Session is closed: the
     * engine and the Track belong to the next one then.
     */
    suspend fun setTranspose(transpose: Transpose) {
        if (closed.get()) return
        _transpose.value = transpose
        engine.setTranspose(transpose)
        deps.tracks.update(track.id) { now ->
            // The control as it is now, so a write from an earlier change that lands late can't win.
            val latest = _transpose.value
            if (now.lastTranspose == latest) null else now.copy(lastTranspose = latest)
        }
    }

    // --- Practice Loop (#18, ADR 9) ---

    private val _practiceLoop = MutableStateFlow<PracticeLoop?>(null)

    /** The Practice Loop, once A is marked; it loops once B is marked too. Not kept after the Session. */
    val practiceLoop: StateFlow<PracticeLoop?> = _practiceLoop

    /**
     * Marks A at the current position (only while [SessionState.Idle]). A B less than
     * [MIN_LOOP_MS] after it is dropped.
     */
    suspend fun markLoopStart() = mutex.withLock {
        if (_state.value != SessionState.Idle) return@withLock
        val start = engine.playback.value.positionMs
        setPracticeLoopLocked(PracticeLoop(start, _practiceLoop.value?.endMs?.takeIf { it - start >= MIN_LOOP_MS }))
    }

    /**
     * Marks B at the current position (only while [SessionState.Idle]), and free playback goes on
     * from A. Returns false, and changes nothing, without an A or less than [MIN_LOOP_MS] after it.
     */
    suspend fun markLoopEnd(): Boolean = mutex.withLock {
        if (_state.value != SessionState.Idle) return@withLock false
        val start = _practiceLoop.value?.startMs ?: return@withLock false
        val end = engine.playback.value.positionMs
        if (end - start < MIN_LOOP_MS) return@withLock false
        setPracticeLoopLocked(PracticeLoop(start, end))
        engine.seekTo(start)
        true
    }

    /** Ends the Practice Loop: free playback plays on past B again. */
    suspend fun clearPracticeLoop() = mutex.withLock { setPracticeLoopLocked(null) }

    private fun setPracticeLoopLocked(loop: PracticeLoop?) {
        _practiceLoop.value = loop
        val end = loop?.endMs
        if (end != null) engine.setPracticeLoop(loop.startMs, end) else engine.clearPracticeLoop()
    }

    // --- Guide Vocal (#18, ADR 9) ---

    private val _guideVocalLevel = MutableStateFlow(0f)

    /**
     * The Guide Vocal in the headphones: the Vocal Stem of the Instrumental that plays, transposed
     * with it, from 0 (none, as every Session starts) to 1 (as in the Original Audio). Never recorded,
     * and silent while recording without headphones.
     */
    val guideVocalLevel: StateFlow<Float> = _guideVocalLevel

    /** The Track as the Session last saw it (Vocal-Free, Backing Audio choice). */
    private var latestTrack = track

    private val _guideVocalUnavailable = MutableStateFlow(track.guideVocalUnavailable(_backing.value))

    /** Why the Guide Vocal can't be heard now, if it can't: on a Vocal-Free Track, or over the Original Audio. */
    val guideVocalUnavailable: StateFlow<GuideVocalUnavailable?> = _guideVocalUnavailable

    /** Sets the Guide Vocal level; it is heard at once during free playback. */
    suspend fun setGuideVocalLevel(level: Float) = mutex.withLock {
        _guideVocalLevel.value = level.coerceIn(0f, 1f)
        applyGuideVocalLocked()
    }

    // --- Microphone ---

    /**
     * Opens the microphone for the meter. Needs the platform's microphone permission, which the
     * UI asks for only when the user first records. Returns false if it can't be opened.
     */
    suspend fun enableMicrophone(): Boolean = engine.input.value.enabled || engine.enableMicrophone()

    /** Sets the gain of the microphone in use and remembers it for that kind of microphone. */
    suspend fun setInputGain(gainDb: Float) {
        val clamped = gainDb.coerceIn(MIN_INPUT_GAIN_DB, MAX_INPUT_GAIN_DB)
        _inputGainDb.value = clamped
        engine.setInputGain(clamped)
        engine.input.value.device?.let { deps.settings.setInputGainDb(it, clamped) }
    }

    suspend fun setCountdownEnabled(enabled: Boolean) {
        _countdownEnabled.value = enabled
        deps.settings.setCountdownEnabled(enabled)
    }

    private val _reviewBackingDb = MutableStateFlow(DEFAULT_REVIEW_BACKING_DB)

    /**
     * Review Balance: level of the Backing Audio, in dB, while a Take plays back (Review and kept
     * Takes). Lower than the Take by default, because an unprocessed voice is far quieter than a
     * mastered song. Only for listening; the Mix sets the real balance (ADR 7).
     */
    val reviewBackingDb: StateFlow<Float> = _reviewBackingDb

    suspend fun setReviewBackingDb(gainDb: Float) {
        val clamped = gainDb.coerceIn(MIN_REVIEW_BACKING_DB, 0f)
        _reviewBackingDb.value = clamped
        engine.setReviewBackingGain(clamped)
        deps.settings.setReviewBackingDb(clamped)
    }

    // --- Recording and Review ---

    /**
     * Records a Take from the current position, its Start Position, after the countdown if it
     * is on. Returns once recording has started, or once [stop] ended the countdown.
     * @throws MicrophoneUnavailable if the microphone can't be opened.
     */
    suspend fun record() {
        val countdown = mutex.withLock {
            check(_state.value == SessionState.Idle) { "can't record in ${_state.value}" }
            startRecordingOrCountdownLocked(engine.playback.value.positionMs)
        } ?: return
        runCountdown(countdown)
    }

    /** Ends the countdown, stops recording (and goes to Review), or stops playing a kept Take or a Mix Preview. */
    suspend fun stop() = mutex.withLock {
        when (_state.value) {
            is SessionState.CountingDown -> becomeIdleLocked()
            is SessionState.Recording -> finishRecordingLocked()
            is SessionState.PlayingTake -> {
                engine.clearTake()
                becomeIdleLocked()
            }
            is SessionState.PreviewingMix -> endPreviewLocked()
            else -> Unit
        }
    }

    /** Plays the Take in Review against the Backing Audio again, at its Transpose, from its Start Position. */
    suspend fun replay() = mutex.withLock {
        val review = _state.value as? SessionState.Review ?: return@withLock
        engine.playTake(review.latencyFrames(), review.transpose)
    }

    // --- Latency Correction (#9, ADR 23) ---

    private val _latencyCorrection = MutableStateFlow<LatencyCorrection?>(null)

    /** The Latency Correction of the Take in Review's route and output path; null outside Review. */
    val latencyCorrection: StateFlow<LatencyCorrection?> = _latencyCorrection

    /**
     * Sets the Latency Correction by ear in Review, within ±[MAX_LATENCY_CORRECTION_MS]: the Take in Review
     * plays with it at once, and it is kept for its route and output path, so later Takes there are recorded
     * with it. Takes kept before keep their own.
     */
    suspend fun setLatencyCorrection(ms: Int) = mutex.withLock {
        val review = _state.value as? SessionState.Review ?: return@withLock
        val correction = _latencyCorrection.value ?: return@withLock
        val clamped = ms.coerceIn(-MAX_LATENCY_CORRECTION_MS, MAX_LATENCY_CORRECTION_MS)
        _latencyCorrection.value = correction.copy(ms = clamped, checked = true)
        storeCorrectionLocked(correction.route, correction.path, clamped)
        engine.setTakeAlignment(review.latencyFrames())
    }

    private suspend fun storeCorrectionLocked(route: OutputRoute, path: OutputPath, ms: Int) {
        deps.settings.setLatencyCorrectionMs(route, path, ms)
        corrections[route to path] = ms
        publishOutput()
    }

    private fun SessionState.Review.latencyFrames(): Long =
        latencyFrames(take.reportedLatencyFrames, _latencyCorrection.value?.ms ?: 0, take.sampleRate)

    // --- Output and Monitoring (#9, ADR 13, ADR 16) ---

    /** Stored Latency Corrections by route and output path, as far as the Session has needed them. */
    private val corrections = mutableMapOf<Pair<OutputRoute, OutputPath>, Int?>()

    private var monitoringLevel = DEFAULT_MONITORING_LEVEL

    private val _output = MutableStateFlow(outputOf(OutputStatus.Off))

    /** Where the Session's output goes, its round trip, and Monitoring. */
    val output: StateFlow<SessionOutput> = _output

    /**
     * Switches Monitoring on or off, in free playback only. It needs the microphone, and wired or USB headphones
     * ([SessionOutput.monitoringUnavailable]); switching reopens the output on another path (ADR 16), which
     * pauses playback. It always starts off, and a route change switches it off. Returns whether it is on.
     */
    suspend fun setMonitoring(enabled: Boolean): Boolean = mutex.withLock {
        val now = engine.output.value
        if (_state.value != SessionState.Idle || enabled == now.monitoring) return@withLock now.monitoring
        if (enabled && (now.route.monitoringUnavailable() != null || !enableMicrophone())) return@withLock false
        engine.setMonitoring(enabled).also { publishOutput() }
    }

    /** Sets the level of Monitoring, 0..1, kept for later Sessions. */
    suspend fun setMonitoringLevel(level: Float) {
        val clamped = level.coerceIn(0f, 1f)
        mutex.withLock {
            monitoringLevel = clamped
            applyMonitoringLocked()
            publishOutput()
        }
        deps.settings.setMonitoringLevel(clamped)
    }

    /**
     * Monitoring is heard in free playback (also paused, to set its level), in the countdown and while recording.
     * While a Take plays back, in Review (also paused), a kept Take or a Mix Preview, the singer listens to it.
     */
    private fun applyMonitoringLocked() {
        val heard = when (_state.value) {
            SessionState.Idle, is SessionState.CountingDown, is SessionState.Recording -> true
            is SessionState.Review, is SessionState.PlayingTake, is SessionState.PreviewingMix -> false
        }
        engine.setMonitoringLevel(if (heard) monitoringLevel else 0f)
    }

    private fun publishOutput(status: OutputStatus = engine.output.value) {
        _output.value = outputOf(status)
    }

    private fun outputOf(status: OutputStatus): SessionOutput {
        val correction = status.route?.let { corrections[it to status.path] }
        return SessionOutput(
            route = status.route,
            path = status.path,
            roundTripMs = status.reportedLatencyMs?.let { it + (correction ?: 0) },
            corrected = correction != null,
            monitoring = status.monitoring,
            monitoringLevel = monitoringLevel,
            monitoringUnavailable = status.route.monitoringUnavailable(),
        )
    }

    private suspend fun correctionOf(route: OutputRoute, path: OutputPath): Int? =
        corrections.getOrPut(route to path) { deps.settings.latencyCorrectionMs(route, path) }

    /**
     * Keeps the Take in Review: its audio is written as mono FLAC, then it is stored and listed,
     * with a default Mix over the Backing Audio it was sung over (#14). Returns the stored Take.
     */
    suspend fun keep(): Take = mutex.withLock {
        val review = _state.value as? SessionState.Review ?: error("no Take in Review")
        val captured = review.take
        val id = deps.newTakeId()
        // Audio first, then the row: a stored Take never points at a missing file.
        engine.writeTake(deps.files.take(track.id, id))
        val take = Take(
            id = id,
            track = track.id,
            name = nextTakeName(),
            startFrame = captured.startFrame,
            frames = captured.frames,
            sampleRate = captured.sampleRate,
            route = captured.route,
            reportedLatencyFrames = captured.reportedLatencyFrames,
            transpose = review.transpose,
            recordedAt = deps.clock(),
            latencyCorrectionMs = _latencyCorrection.value?.ms ?: 0,
        )
        // Kept as it sounded: that confirms the Latency Correction for the route and path.
        _latencyCorrection.value?.takeIf { !it.checked }?.let { storeCorrectionLocked(it.route, it.path, it.ms) }
        deps.takes.add(take)
        // The Backing Audio doesn't switch during Review, so this is what the Take was sung over.
        deps.mixes.add(take, _backing.value)
        leaveReviewLocked(captured)
        take
    }

    /** Throws away the Take in Review. */
    suspend fun discard() = mutex.withLock {
        val review = _state.value as? SessionState.Review ?: return@withLock
        leaveReviewLocked(review.take)
    }

    /** Throws away the Take in Review and records a new one from the same Start Position. */
    suspend fun recordAgain() {
        val countdown = mutex.withLock {
            val review = _state.value as? SessionState.Review ?: error("no Take in Review")
            engine.clearTake()
            // Picks up an Instrumental that became ready during Review before the next Take.
            becomeIdleLocked(review.take.startPositionMs)
            startRecordingOrCountdownLocked(review.take.startPositionMs)
        } ?: return
        runCountdown(countdown)
    }

    // --- Kept Takes ---

    /**
     * Plays a kept Take against the Backing Audio at the Take's Transpose, from its Start Position (only while
     * [SessionState.Idle]). Returns false if its audio can't be played.
     */
    suspend fun play(take: TakeId): Boolean = mutex.withLock {
        if (_state.value != SessionState.Idle) return@withLock false
        val stored = deps.takes.get(take) ?: return@withLock false
        engine.pause()
        if (!engine.loadTake(deps.files.take(track.id, stored.id), stored.startFrame)) return@withLock false
        _state.value = SessionState.PlayingTake(stored)
        applyGuideVocalLocked()
        applyMonitoringLocked()
        // With the Latency Correction it was kept with (#9).
        if (!engine.playTake(stored.latencyFrames, stored.transpose)) {
            becomeIdleLocked()
            return@withLock false
        }
        true
    }

    /** Renames a kept Take; blank names are ignored. */
    suspend fun rename(take: TakeId, name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) deps.takes.rename(take, trimmed)
    }

    /** Deletes a kept Take, its Mixes and its audio. */
    suspend fun delete(take: TakeId) = mutex.withLock {
        when (val state = _state.value) {
            is SessionState.PlayingTake -> if (state.take.id == take) {
                engine.clearTake()
                becomeIdleLocked()
            }
            is SessionState.PreviewingMix -> if (state.take.id == take) endPreviewLocked()
            else -> Unit
        }
        // Rows first, then the file: the list never shows a Take whose audio is gone.
        deps.mixes.deleteForTake(take)
        deps.takes.delete(take)
        deps.files.deleteTake(track.id, take)
    }

    // --- Mix Preview (#14) ---

    /**
     * Plays a Mix as it will be exported (only while [SessionState.Idle]): its Take over its own
     * Backing Audio at the Take's Transpose, with the Mix's levels and Vocal Nudge, from the Take's
     * Start Position until it ends. The Offline Render uses the same DSP. Afterwards the Session plays
     * its own Backing Audio again. Returns false if the Take's audio can't be played.
     * @throws eu.schicht8.meavox.core.mix.MixUnavailable if the Mix can't be played now.
     */
    suspend fun previewMix(id: MixId): Boolean = mutex.withLock {
        if (_state.value != SessionState.Idle) return@withLock false
        val parts = deps.mixes.resolve(id)
        engine.pause()
        // The Take is held in memory with the Backing Audio, so load the backing first: a load drops it.
        if (parts.backing != _backing.value) {
            loadBacking(parts.backing)
            // It decodes from the start: wait until it reaches the Take, or the preview starts over silence.
            val needed = parts.take.startPositionMs + PREVIEW_BUFFER_MS
            engine.playback.first { it.bufferedMs >= minOf(needed, it.durationMs) }
        }
        if (!engine.loadTake(deps.files.take(track.id, parts.take.id), parts.take.startFrame)) {
            becomeIdleLocked(parts.take.startPositionMs)
            return@withLock false
        }
        _state.value = SessionState.PreviewingMix(parts.mix, parts.take, parts.mix.settings)
        applyMonitoringLocked()
        previewParts = parts
        applyMixSettings(parts, parts.mix.settings)
        if (!engine.playTake(parts.alignmentFrames(), parts.take.transpose)) {
            endPreviewLocked()
            return@withLock false
        }
        true
    }

    /**
     * Mix Settings being edited, heard at once in the running preview; they are stored separately
     * ([eu.schicht8.meavox.core.mix.Mixes.updateSettings]).
     */
    suspend fun setPreviewSettings(settings: MixSettings) = mutex.withLock {
        val preview = _state.value as? SessionState.PreviewingMix ?: return@withLock
        _state.value = preview.copy(settings = settings)
        previewParts?.let { applyMixSettings(it, settings) }
        engine.setTakeAlignment(preview.take.alignmentFrames(settings))
    }

    /** The Mix being previewed, as resolved when the preview started. */
    private var previewParts: MixParts? = null

    /** The Guide Vocal the engine holds for the preview, if any. */
    private var guideVocal: AudioFile? = null

    /** The Mix's levels, Vocal Effects and Guide Vocal, heard at once; the Vocal Stem is loaded the first time it is wanted. */
    private suspend fun applyMixSettings(parts: MixParts, settings: MixSettings) {
        engine.setReviewBackingGain(settings.backingDb)
        engine.setTakeGain(settings.vocalDb)
        engine.setVocalEffects(settings.effects)
        val wanted = parts.guideVocal(deps.files, settings)
        if (wanted != null && wanted != guideVocal) {
            // Decoded far enough to play from where the preview is now (its start, or later when raised during it).
            val at = maxOf(parts.take.startPositionMs, engine.playback.value.positionMs)
            if (engine.loadGuideVocal(wanted, at)) guideVocal = wanted
        }
        engine.setGuideVocalLevel(if (wanted != null && wanted == guideVocal) settings.guideVocalLevel else 0f)
    }

    /** Back to free playback at the Take's Start Position, with the Review Balance and the Session's own Backing Audio. */
    private suspend fun endPreviewLocked() {
        val preview = _state.value as SessionState.PreviewingMix
        engine.clearTake()
        engine.setReviewBackingGain(_reviewBackingDb.value)
        engine.setTakeGain(0f)
        resetMixEffects()
        engine.seekTo(preview.take.startPositionMs)
        becomeIdleLocked(preview.take.startPositionMs)
    }

    /** Takes play as recorded again: no Vocal Effects, no Guide Vocal. */
    private fun resetMixEffects() {
        engine.setVocalEffects(VocalEffects.None)
        engine.setGuideVocalLevel(0f)
        engine.clearGuideVocal()
        guideVocal = null
        previewParts = null
    }

    private val closed = AtomicBoolean(false)

    /**
     * Leaves the Session: stops audio and returns the Audio Engine to plain playback. Does nothing
     * if it was closed already, e.g. by [Sessions.open] for the next Track.
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        observers.cancel()
        engine.endSession()
        load?.let(engine::unload)
    }

    // --- Internals ---

    /**
     * Starts recording from [from] now, or enters the countdown and returns it for [runCountdown].
     * The position is passed in: a seek just issued may not show in [playback] yet.
     */
    private suspend fun startRecordingOrCountdownLocked(from: Long): SessionState.CountingDown? {
        // Open the microphone before counting down, so a missing permission shows at once.
        if (!enableMicrophone()) throw MicrophoneUnavailable()
        engine.pause()
        engine.seekTo(from)
        if (!_countdownEnabled.value) {
            startRecordingLocked(from)
            return null
        }
        return SessionState.CountingDown(from, COUNTDOWN_SECONDS).also { _state.value = it }
    }

    private suspend fun runCountdown(start: SessionState.CountingDown) {
        var current = start
        while (true) {
            delay(1_000)
            mutex.withLock {
                // Identity, not equality: after stop() and a new record() a fresh countdown runs.
                if (_state.value !== current) return
                if (current.secondsLeft <= 1) {
                    startRecordingLocked(current.startPositionMs)
                    return
                }
                current = current.copy(secondsLeft = current.secondsLeft - 1)
                _state.value = current
            }
        }
    }

    /** Starts recording a Take at the Transpose the control is set to now. */
    private suspend fun startRecordingLocked(fromMs: Long) {
        val transpose = _transpose.value
        // Over anything but headphones, the microphone could pick the Guide Vocal up (#18).
        recordingOverHeadphones = engine.currentOutputRoute() in HEADPHONES
        if (!recordingOverHeadphones) engine.setGuideVocalLevel(0f)
        if (!engine.startRecording(fromMs, transpose)) {
            becomeIdleLocked()
            throw MicrophoneUnavailable()
        }
        _state.value = SessionState.Recording(fromMs, transpose)
        // "Last sung" in the Library: singing, not opening the Track to listen.
        deps.tracks.setLastSessionAt(track.id, deps.clock())
    }

    private suspend fun finishRecordingLocked() {
        val recording = _state.value as SessionState.Recording
        val take = engine.stopRecording()
        if (take == null) {
            becomeIdleLocked()
            return
        }
        val stored = correctionOf(take.route, take.path)
        _latencyCorrection.value = LatencyCorrection(take.route, take.path, ms = stored ?: 0, checked = stored != null)
        val review = SessionState.Review(take, recording.transpose)
        _state.value = review
        applyGuideVocalLocked()
        applyMonitoringLocked()
        engine.playTake(review.latencyFrames(), recording.transpose)
    }

    private suspend fun leaveReviewLocked(take: CapturedTake) {
        engine.clearTake()
        engine.seekTo(take.startPositionMs)
        becomeIdleLocked(take.startPositionMs)
    }

    /**
     * Enters [SessionState.Idle] and loads Backing Audio that became available meanwhile, at
     * [positionMs] if a seek to it was just issued (and may not show in [playback] yet).
     */
    private suspend fun becomeIdleLocked(positionMs: Long? = null) {
        _state.value = SessionState.Idle
        _latencyCorrection.value = null
        applyMonitoringLocked()
        val position = positionMs ?: engine.playback.value.positionMs
        switchBackingLocked(wantedBacking, position, resume = false)
        applyGuideVocalLocked(position)
    }

    /** Loads [backing] and continues at [positionMs]; the stems are sample-aligned with the Original Audio. */
    private suspend fun switchBackingLocked(backing: BackingAudio, positionMs: Long, resume: Boolean) {
        if (backing == _backing.value) return
        loadBacking(backing)
        engine.seekTo(positionMs)
        if (resume) engine.play()
    }

    /**
     * The Session's Guide Vocal as the Session is now, heard only where the singer listens without
     * recording it; a Mix Preview sets its own. The Vocal Stem is loaded the first time the level is
     * raised, and only while Idle, as decoding it holds up the Session; it stays held at level 0, and
     * goes when the Instrumental does.
     */
    private suspend fun applyGuideVocalLocked(positionMs: Long = engine.playback.value.positionMs) {
        val state = _state.value
        if (state is SessionState.PreviewingMix) return
        val stem = availableVocalStem()
        when {
            stem == null -> {
                if (guideVocal != null) engine.clearGuideVocal()
                guideVocal = null
            }
            stem != guideVocal && _guideVocalLevel.value > 0f && state == SessionState.Idle ->
                guideVocal = if (engine.loadGuideVocal(stem, positionMs)) stem else null
        }
        // Review and kept Takes play as recorded, without it (ADR 7).
        val audible = when (state) {
            SessionState.Idle, is SessionState.CountingDown -> true
            is SessionState.Recording -> recordingOverHeadphones
            else -> false
        }
        engine.setGuideVocalLevel(if (audible && stem != null && stem == guideVocal) _guideVocalLevel.value else 0f)
    }

    /** The Take being recorded goes out over headphones: the Guide Vocal may be heard meanwhile. */
    private var recordingOverHeadphones = false

    /** The Vocal Stem of the Instrumental that plays, if the Track can have a Guide Vocal now. */
    private fun availableVocalStem(): AudioFile? {
        val instrumental = _backing.value as? BackingAudio.Instrumental ?: return null
        if (latestTrack.guideVocalUnavailable(instrumental) != null) return null
        return deps.files.stems(track.id, instrumental.model).vocalStem
    }

    private suspend fun nextTakeName(): String {
        val names = deps.takes.observe(track.id).first().mapTo(HashSet()) { it.name }
        var n = names.size + 1
        while ("Take $n" in names) n++
        return "Take $n"
    }

    private suspend fun start(onProgress: (Float) -> Unit) {
        _countdownEnabled.value = deps.settings.countdownEnabled()
        _reviewBackingDb.value = deps.settings.reviewBackingDb() ?: DEFAULT_REVIEW_BACKING_DB
        engine.setReviewBackingGain(_reviewBackingDb.value)
        monitoringLevel = deps.settings.monitoringLevel() ?: DEFAULT_MONITORING_LEVEL
        applyMonitoringLocked()
        // A Session left during a Mix Preview may have left its vocal level and effects behind.
        engine.setTakeGain(0f)
        resetMixEffects()
        engine.beginSession()
        engine.setTranspose(_transpose.value)
        loadBacking(track.backingAudio(), onProgress)
        observe()
    }

    private suspend fun loadBacking(backing: BackingAudio, onProgress: (Float) -> Unit = {}) {
        load = engine.load(fileOf(backing), onProgress)
        // The engine dropped the Guide Vocal with the Backing Audio it belonged to.
        guideVocal = null
        _backing.value = backing
        _guideVocalUnavailable.value = latestTrack.guideVocalUnavailable(backing)
    }

    private fun observe() {
        observers.launch {
            // A recording also ends without stop(): at the end of the song, or when the output
            // route goes away. Either way the captured part goes to Review. Take playback ends
            // by itself at the end of the Take.
            engine.playback.collect { playback ->
                val state = _state.value
                if (state is SessionState.Recording && !playback.recording) {
                    mutex.withLock {
                        if (_state.value is SessionState.Recording && !engine.playback.value.recording) {
                            finishRecordingLocked()
                        }
                    }
                } else if (state is SessionState.PlayingTake && !playback.playingTake) {
                    mutex.withLock {
                        if (_state.value is SessionState.PlayingTake && !engine.playback.value.playingTake) {
                            engine.clearTake()
                            becomeIdleLocked()
                        }
                    }
                } else if (state is SessionState.PreviewingMix && !playback.playingTake) {
                    mutex.withLock {
                        if (_state.value is SessionState.PreviewingMix && !engine.playback.value.playingTake) endPreviewLocked()
                    }
                }
            }
        }
        observers.launch {
            // Separation finished (ADR 10), the user chose other Backing Audio or marked the Track
            // Vocal-Free: switch, but never under a Take that is being recorded, reviewed or played back.
            deps.tracks.observe(track.id).filterNotNull().distinctUntilChanged { a, b ->
                a.backingAudio() == b.backingAudio() && a.vocalFree == b.vocalFree
            }.collect { now ->
                mutex.withLock {
                    latestTrack = now
                    wantedBacking = now.backingAudio()
                    _guideVocalUnavailable.value = now.guideVocalUnavailable(_backing.value)
                    if (_state.value == SessionState.Idle) {
                        val playback = engine.playback.value
                        switchBackingLocked(wantedBacking, playback.positionMs, resume = playback.playing)
                        applyGuideVocalLocked(playback.positionMs)
                    }
                }
            }
        }
        observers.launch {
            // The route or path changed (headphones, Monitoring): show its round trip with its correction.
            engine.output.collect { status ->
                status.route?.let { correctionOf(it, status.path) }
                publishOutput(status)
            }
        }
        observers.launch {
            // Each kind of microphone keeps its own input gain (ADR 9).
            engine.input.map { it.device }.filterNotNull().distinctUntilChanged().collect { device ->
                val gain = deps.settings.inputGainDb(device)
                _inputGainDb.value = gain
                engine.setInputGain(gain)
            }
        }
    }

    private fun fileOf(backing: BackingAudio): AudioFile = deps.files.fileOf(track.id, backing)

    companion object {
        /** Length of the countdown before recording (time-based, ADR 9). */
        const val COUNTDOWN_SECONDS = 3
        const val MIN_INPUT_GAIN_DB = -12f
        const val MAX_INPUT_GAIN_DB = 24f
        const val DEFAULT_REVIEW_BACKING_DB = -20f
        const val MIN_REVIEW_BACKING_DB = -40f

        /** The largest Latency Correction either way (#9): more than Bluetooth's round trip. */
        const val MAX_LATENCY_CORRECTION_MS = 400

        const val DEFAULT_MONITORING_LEVEL = 0.5f

        /** The shortest Practice Loop. */
        const val MIN_LOOP_MS = 1_000L

        /** Backing Audio decoded past a Take's Start Position before its Mix Preview starts. */
        private const val PREVIEW_BUFFER_MS = 2_000L

        internal suspend fun open(track: Track, scope: CoroutineScope, deps: Dependencies, onProgress: (Float) -> Unit): Session =
            Session(track, scope, deps).also { it.start(onProgress) }
    }
}

/**
 * What a Session on the Track plays: its [Track.backingChoice] once it has an Instrumental, else
 * the Original Audio. A Vocal-Free Track has none, so it always sings over its Original Audio (ADR 14).
 */
fun Track.backingAudio(): BackingAudio {
    val model = instrumentalModel
    return if (model != null && backingChoice == BackingChoice.Instrumental) BackingAudio.Instrumental(model) else BackingAudio.OriginalAudio
}

/**
 * An A–B section of the Track that free playback repeats, without recording (GLOSSARY: Practice
 * Loop). Until B is marked, [endMs] is null and nothing loops.
 */
data class PracticeLoop(val startMs: Long, val endMs: Long? = null)

/** What plays under the singer in a Session (GLOSSARY: Backing Audio). */
sealed interface BackingAudio {
    data object OriginalAudio : BackingAudio

    /** The Instrumental that Separation with [model] produced. */
    data class Instrumental(val model: SeparationModelId) : BackingAudio
}

/** Where a Session is in its workflow (ADR 9). */
sealed interface SessionState {
    /** Free playback: play, pause and seek to choose the next Start Position. */
    data object Idle : SessionState

    /** Recording from [startPositionMs] starts in [secondsLeft] seconds. */
    data class CountingDown(val startPositionMs: Long, val secondsLeft: Int) : SessionState

    /** A Take is being recorded from [startPositionMs], sung at [transpose]. */
    data class Recording(val startPositionMs: Long, val transpose: Transpose) : SessionState

    /**
     * A just-recorded [take], sung at [transpose], not kept yet: Keep, Discard or Record again
     * (GLOSSARY: Review).
     */
    data class Review(val take: CapturedTake, val transpose: Transpose) : SessionState

    /** A kept [take] plays against the Backing Audio. */
    data class PlayingTake(val take: Take) : SessionState

    /** [mix] plays as it will be exported, with [settings] being edited (#14). */
    data class PreviewingMix(val mix: Mix, val take: Take, val settings: MixSettings) : SessionState
}

/** The microphone can't be opened (no permission or no input device). */
class MicrophoneUnavailable : IllegalStateException("the microphone is not available")
