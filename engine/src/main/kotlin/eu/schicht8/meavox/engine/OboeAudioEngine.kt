// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.engine

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import eu.schicht8.meavox.core.mix.VocalEffects
import eu.schicht8.meavox.core.model.AudioFile
import eu.schicht8.meavox.core.model.InputDevice
import eu.schicht8.meavox.core.model.OutputPath
import eu.schicht8.meavox.core.model.OutputRoute
import eu.schicht8.meavox.core.model.Transpose
import eu.schicht8.meavox.core.port.AudioEngine
import eu.schicht8.meavox.core.port.CapturedTake
import eu.schicht8.meavox.core.port.InputLevel
import eu.schicht8.meavox.core.port.LoadId
import eu.schicht8.meavox.core.port.OutputStatus
import eu.schicht8.meavox.core.port.Playback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.pow

/**
 * [AudioEngine] on the native Oboe `PlaybackEngine`.
 *
 * Loading is progressive: Android's decoders run at only ~8× real time on weak devices (each
 * small MP3 frame is a round trip to the codec process), so the file keeps decoding in the
 * background while it already plays. [load] returns after [START_BUFFER_MS] are decoded.
 * The decoded audio is held in memory (one copy; see known limitations in the README).
 *
 * In a Session the native streams run continuously (microphone once enabled; ADR 16),
 * and [playback] and [input] are published every [POSITION_INTERVAL_MS].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OboeAudioEngine(context: Context) : AudioEngine {
    private val handle = NativePlayback.create()
    private val routes = AudioRoutes(context.getSystemService(AudioManager::class.java))
    private val state = MutableStateFlow(Playback.Idle)
    override val playback: StateFlow<Playback> = state

    private val inputState = MutableStateFlow(InputLevel.Off)
    override val input: StateFlow<InputLevel> = inputState

    private val outputState = MutableStateFlow(OutputStatus.Off)
    override val output: StateFlow<OutputStatus> = outputState

    // Native control calls are serialised on one thread; decoding runs on IO.
    private val control = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + control)
    private val loads = Mutex() // guards the fields below; loads and unloads never overlap
    private var decodeJob: Job? = null
    private var generation = 0L
    private var currentLoad: LoadId? = null

    // Confined to [control].
    private var ticker: Job? = null
    private var inSession = false
    /** Route of the recording whose Take audio is held; null when the held audio isn't a fresh capture. */
    private var captureRoute: OutputRoute? = null
    private var capturePath = OutputPath.Normal
    private val outputRoutes = mutableMapOf<Int, OutputRoute>()
    private var reportedLatencyMs: Int? = null
    private var latencyRefreshIn = 0
    private var lastOutputDevice = 0
    private var lastMonitoring = false
    private var lastInputEnabled = false
    private var lastClipCount = 0L
    private var lastClipAt = Long.MIN_VALUE
    private val inputDevices = mutableMapOf<Int, InputDevice>()

    override suspend fun load(audio: AudioFile, onProgress: (Float) -> Unit): LoadId {
        // A Guide Vocal belongs to the Backing Audio it was loaded for.
        withContext(control) {
            guideJob?.cancelAndJoin()
            guideJob = null
        }
        val playable = CompletableDeferred<Unit>()
        val job: Job
        val id: LoadId
        loads.withLock {
            stopDecodingLocked()
            id = LoadId(++generation)
            currentLoad = id
            job = scope.launch(Dispatchers.IO) { decode(audio, onProgress, playable) }
            decodeJob = job
        }
        try {
            playable.await()
        } catch (e: CancellationException) {
            job.cancel() // this load's own decode; a newer load may already own decodeJob
            throw e
        }
        withContext(control) { publish(loaded = audio) }
        return id
    }

    override fun unload(load: LoadId) {
        scope.launch {
            loads.withLock {
                // An old owner (e.g. a screen torn down after the next one opened) must not
                // unload a newer load.
                if (load != currentLoad) return@withLock
                stopDecodingLocked()
                NativePlayback.discardLoad(handle)
                currentLoad = null
            }
        }
    }

    /** Cancels a background decode and resets the published state. Call with [loads] held. */
    private suspend fun stopDecodingLocked() {
        decodeJob?.cancelAndJoin()
        decodeJob = null
        withContext(control) {
            if (!inSession) stopTicker()
            captureRoute = null
            state.value = Playback.Idle
        }
    }

    private suspend fun decode(file: AudioFile, onProgress: (Float) -> Unit, playable: CompletableDeferred<Unit>) {
        val started = System.nanoTime()
        try {
            try {
                runInterruptible { AudioDecoder.decode(file.path, DecodeSink(file, onProgress, playable, started)) }
            } catch (e: CancellationException) {
                throw e // also an Exception: must not be mistaken for a decode error below
            } catch (e: CapacityExhausted) {
                Log.w(TAG, "${file.path} is longer than expected; playing the first part only")
            } catch (e: Exception) {
                if (!playable.isCompleted) throw e
                // Playback already started: keep what decoded instead of pulling the audio away.
                Log.w(TAG, "decoding ${file.path} failed part-way; playing the decoded part", e)
            }
            NativePlayback.endLoad(handle)
            Log.i(TAG, "decoded fully in ${(System.nanoTime() - started) / 1_000_000} ms")
            playable.complete(Unit) // short files: playable once complete
            onProgress(1f)
            withContext(control) { publish() }
        } catch (e: Throwable) {
            NativePlayback.discardLoad(handle) // free a half-decoded song, also on cancellation
            playable.completeExceptionally(e)
            if (e is CancellationException) throw e
            Log.w(TAG, "could not decode ${file.path}", e)
            withContext(control) { state.value = Playback.Idle }
        }
    }

    /** Feeds decoded PCM into the native engine and signals [playable] after [START_BUFFER_MS]. */
    private inner class DecodeSink(
        private val file: AudioFile,
        private val onProgress: (Float) -> Unit,
        private val playable: CompletableDeferred<Unit>,
        private val started: Long,
    ) : AudioDecoder.Sink {
        private var startFrames = Long.MAX_VALUE // known once the decoder reports the sample rate

        override fun begin(channels: Int, sampleRate: Int, estimatedFrames: Long) {
            NativePlayback.beginLoad(handle, channels, sampleRate, estimatedFrames)
            startFrames = sampleRate.toLong() * START_BUFFER_MS / 1000
            Log.i(TAG, "decoding ${file.path}: $channels ch, $sampleRate Hz")
        }

        override fun append(interleaved: FloatArray, frames: Int) {
            if (!NativePlayback.appendLoad(handle, interleaved, frames)) throw CapacityExhausted()
            if (!playable.isCompleted && NativePlayback.decodedFrames(handle) >= startFrames) {
                Log.i(TAG, "playable after ${(System.nanoTime() - started) / 1_000_000} ms")
                playable.complete(Unit)
            }
        }

        override fun progress(fraction: Float) {
            onProgress(fraction)
            if (playable.isCompleted) scope.launch { publish() } // update bufferedMs
        }
    }

    /** The audio is longer than the reserved capacity (wrong duration in the container). */
    private class CapacityExhausted : RuntimeException()

    override fun play() {
        scope.launch {
            if (NativePlayback.play(handle)) startTicker()
            publish()
        }
    }

    override fun pause() {
        scope.launch {
            NativePlayback.pause(handle)
            if (!inSession) stopTicker()
            publish()
        }
    }

    override fun seekTo(positionMs: Long) {
        scope.launch {
            val rate = NativePlayback.sampleRate(handle)
            if (rate > 0) NativePlayback.seekTo(handle, positionMs * rate / 1000)
            publish()
        }
    }

    override fun setTranspose(transpose: Transpose) {
        scope.launch { NativePlayback.setTranspose(handle, transpose.semitones) }
    }

    // --- Session face ---

    override fun setPracticeLoop(startMs: Long, endMs: Long) {
        scope.launch { NativePlayback.setPracticeLoop(handle, startMs, endMs) }
    }

    override fun clearPracticeLoop() {
        scope.launch { NativePlayback.clearPracticeLoop(handle) }
    }

    override suspend fun currentOutputRoute(): OutputRoute = withContext(control) {
        routes.output(NativePlayback.outputDeviceId(handle))
    }

    override fun beginSession() {
        scope.launch {
            inSession = true
            NativePlayback.setSessionMode(handle, true)
            startTicker()
            publish()
        }
    }

    override fun endSession() {
        scope.launch {
            inSession = false
            captureRoute = null
            NativePlayback.setSessionMode(handle, false)
            stopTicker()
            publish()
        }
    }

    override suspend fun enableMicrophone(): Boolean = withContext(control) {
        val enabled = NativePlayback.enableInput(handle)
        publish()
        enabled
    }

    override suspend fun setMonitoring(enabled: Boolean): Boolean = withContext(control) {
        val on = NativePlayback.setMonitoring(handle, enabled)
        publish()
        on
    }

    override fun setMonitoringLevel(level: Float) {
        scope.launch { NativePlayback.setMonitoringLevel(handle, level) }
    }

    override fun setInputGain(gainDb: Float) {
        val linear = 10f.pow(gainDb / 20f)
        scope.launch { NativePlayback.setInputGain(handle, linear) }
    }

    override fun setReviewBackingGain(gainDb: Float) {
        val linear = 10f.pow(gainDb / 20f)
        scope.launch { NativePlayback.setReviewBackingGain(handle, linear) }
    }

    override fun setTakeGain(gainDb: Float) {
        val linear = 10f.pow(gainDb / 20f)
        scope.launch { NativePlayback.setTakeGain(handle, linear) }
    }

    override fun setTakeAlignment(alignmentFrames: Long) {
        scope.launch { NativePlayback.setTakeAlignment(handle, alignmentFrames) }
    }

    override suspend fun startRecording(fromMs: Long, transpose: Transpose): Boolean = withContext(control) {
        val rate = NativePlayback.sampleRate(handle)
        if (rate <= 0) return@withContext false
        val started = NativePlayback.startRecording(handle, fromMs * rate / 1000, transpose.semitones)
        captureRoute = if (started) routes.output(NativePlayback.outputDeviceId(handle)) else null
        capturePath = pathOf(NativePlayback.monitoring(handle))
        if (started) startTicker()
        publish()
        started
    }

    override suspend fun stopRecording(): CapturedTake? = withContext(control) {
        NativePlayback.stopRecording(handle)
        publish()
        val route = captureRoute ?: return@withContext null
        val frames = NativePlayback.takeFrames(handle)
        if (frames <= 0) return@withContext null
        val corrected = NativePlayback.takeXRunCorrectionFrames(handle)
        if (corrected > 0) Log.i(TAG, "Take: $corrected frames corrected after output xruns")
        CapturedTake(
            startFrame = NativePlayback.takeStartFrame(handle),
            frames = frames,
            sampleRate = NativePlayback.sampleRate(handle),
            route = route,
            reportedLatencyFrames = NativePlayback.takeReportedLatencyFrames(handle),
            path = capturePath,
        )
    }

    override suspend fun writeTake(file: AudioFile) = withContext(Dispatchers.IO) {
        val target = File(file.path)
        target.parentFile?.mkdirs()
        // Write next to the target, then rename: a Take file is either complete or absent.
        val partial = File(target.path + ".part")
        if (!NativePlayback.writeTakeFlac(handle, partial.path)) {
            partial.delete()
            throw IOException("could not write ${target.path}")
        }
        if (!partial.renameTo(target)) {
            partial.delete()
            throw IOException("could not move ${partial.path} to ${target.path}")
        }
    }

    override suspend fun loadTake(file: AudioFile, startFrame: Long): Boolean {
        withContext(control) { captureRoute = null }
        return try {
            runInterruptible(Dispatchers.IO) { AudioDecoder.decode(file.path, TakeSink(startFrame)) }
            NativePlayback.takeFrames(handle) > 0
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "could not load Take ${file.path}", e)
            withContext(control) { NativePlayback.clearTake(handle) }
            false
        }
    }

    /** Feeds a decoded stored Take into the native engine's Take audio. */
    private inner class TakeSink(private val startFrame: Long) : AudioDecoder.Sink {
        private var channels = 1

        override fun begin(channels: Int, sampleRate: Int, estimatedFrames: Long) {
            this.channels = channels
            if (!NativePlayback.beginTakeLoad(handle, startFrame, sampleRate, estimatedFrames)) {
                throw UnsupportedAudioException("Take at $sampleRate Hz doesn't fit the Backing Audio")
            }
        }

        override fun append(interleaved: FloatArray, frames: Int) {
            NativePlayback.appendTakeLoad(handle, interleaved, frames, channels)
        }
    }

    override suspend fun playTake(alignmentFrames: Long, transpose: Transpose): Boolean = withContext(control) {
        val started = NativePlayback.playTake(handle, alignmentFrames, transpose.semitones)
        if (started) startTicker()
        publish()
        started
    }

    override fun clearTake() {
        scope.launch {
            captureRoute = null
            NativePlayback.clearTake(handle)
            publish()
        }
    }

    // --- Mix Preview (#16) ---

    /** Decoding of the Guide Vocal; confined to [control]. */
    private var guideJob: Job? = null

    override fun setVocalEffects(effects: VocalEffects) {
        scope.launch {
            val tail = effects.tailFrames(NativePlayback.sampleRate(handle))
            NativePlayback.setVocalEffects(handle, effects.reverb.native(), effects.compression, effects.highPass, tail)
        }
    }

    override suspend fun loadGuideVocal(file: AudioFile, fromMs: Long): Boolean {
        val ready = CompletableDeferred<Boolean>()
        // On [control], so it runs after a clearGuideVocal() called before it.
        withContext(control) {
            guideJob?.cancelAndJoin()
            guideJob = scope.launch(Dispatchers.IO) {
                try {
                    // The stems are FLAC; read exactly as the Offline Render reads them.
                    FlacFiles.reader(File(file.path)).use { reader ->
                        if (!NativePlayback.beginGuideLoad(handle, reader.channels, reader.sampleRate)) {
                            ready.complete(false)
                            return@launch
                        }
                        val wanted = (fromMs + START_BUFFER_MS) * reader.sampleRate / 1000
                        val block = FloatArray(GUIDE_BLOCK_FRAMES * reader.channels)
                        while (isActive) {
                            val n = reader.read(block, GUIDE_BLOCK_FRAMES)
                            if (n == 0 || !NativePlayback.appendGuideLoad(handle, block, n)) break
                            if (!ready.isCompleted && NativePlayback.guideDecodedFrames(handle) >= wanted) ready.complete(true)
                        }
                    }
                    ready.complete(true)
                } catch (e: CancellationException) {
                    ready.complete(false)
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "could not load the Guide Vocal ${file.path}", e)
                    ready.complete(false)
                }
            }
        }
        return ready.await()
    }

    override fun setGuideVocalLevel(level: Float) {
        scope.launch { NativePlayback.setGuideLevel(handle, level.coerceIn(0f, 1f)) }
    }

    override fun clearGuideVocal() {
        scope.launch {
            guideJob?.cancelAndJoin()
            guideJob = null
            NativePlayback.clearGuide(handle)
        }
    }

    // --- Publishing ---

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                delay(POSITION_INTERVAL_MS)
                if (!inSession && !NativePlayback.isPlaying(handle)) {
                    // Reached the end, or a route change paused it: stop the output stream too.
                    NativePlayback.pause(handle)
                    publish()
                    break
                }
                publish()
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun publish(loaded: AudioFile? = state.value.loaded) {
        val rate = NativePlayback.sampleRate(handle)
        fun ms(frames: Long) = if (rate > 0) frames * 1000 / rate else 0
        val transport = NativePlayback.transport(handle)
        state.value = Playback(
            loaded = loaded,
            playing = transport != NativePlayback.IDLE,
            positionMs = ms(NativePlayback.positionFrames(handle)),
            durationMs = ms(NativePlayback.durationFrames(handle)),
            bufferedMs = ms(NativePlayback.decodedFrames(handle)),
            recording = transport == NativePlayback.RECORDING,
            playingTake = transport == NativePlayback.PLAYING_TAKE,
        )
        publishInput()
        publishOutput()
    }

    private fun publishOutput() {
        if (!inSession) {
            outputState.value = OutputStatus.Off
            return
        }
        val deviceId = NativePlayback.outputDeviceId(handle)
        val monitoring = NativePlayback.monitoring(handle)
        // The platform's estimate settles once the streams run: refresh it now and then, and at once
        // when the route or the output path changes.
        val inputEnabled = NativePlayback.inputEnabled(handle)
        if (deviceId != lastOutputDevice || monitoring != lastMonitoring || inputEnabled != lastInputEnabled) latencyRefreshIn = 0
        lastOutputDevice = deviceId
        lastMonitoring = monitoring
        lastInputEnabled = inputEnabled
        if (--latencyRefreshIn <= 0) {
            val rate = NativePlayback.sampleRate(handle)
            val frames = if (inputEnabled) NativePlayback.reportedLatencyFrames(handle) else 0
            reportedLatencyMs = if (frames > 0 && rate > 0) (frames * 1000 / rate).toInt() else null
            // Freshly started streams often can't report yet: ask again soon until they can.
            latencyRefreshIn = if (reportedLatencyMs == null && inputEnabled) 2 else LATENCY_REFRESH_TICKS
        }
        outputState.value = OutputStatus(
            // AudioManager lookups are binder calls: resolve each device ID once.
            route = if (deviceId == 0) null else outputRoutes.getOrPut(deviceId) { routes.output(deviceId) },
            path = pathOf(monitoring),
            monitoring = monitoring,
            reportedLatencyMs = if (inputEnabled) reportedLatencyMs else null,
        )
    }

    private fun pathOf(monitoring: Boolean) = if (monitoring) OutputPath.LowLatency else OutputPath.Normal

    private fun publishInput() {
        if (!inSession || !NativePlayback.inputEnabled(handle)) {
            inputState.value = InputLevel.Off
            return
        }
        val clips = NativePlayback.clipCount(handle)
        val now = SystemClock.elapsedRealtime()
        if (clips != lastClipCount) {
            lastClipCount = clips
            lastClipAt = now
        }
        val deviceId = NativePlayback.inputDeviceId(handle)
        inputState.value = InputLevel(
            enabled = true,
            // AudioManager lookups are binder calls: resolve each device ID once.
            device = inputDevices.getOrPut(deviceId) { routes.input(deviceId) },
            peak = NativePlayback.inputPeak(handle).coerceIn(0f, 1f),
            clipping = lastClipAt != Long.MIN_VALUE && now - lastClipAt < CLIP_HOLD_MS,
        )
    }

    private companion object {
        const val POSITION_INTERVAL_MS = 50L
        const val START_BUFFER_MS = 2_000L
        const val CLIP_HOLD_MS = 1_500L

        /** About once a second at [POSITION_INTERVAL_MS]. */
        const val LATENCY_REFRESH_TICKS = 20
        const val GUIDE_BLOCK_FRAMES = 8192
        const val TAG = "MeaVoxEngine"
    }
}
