// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Seam 2 of #9: Monitoring on the Audio Engine's native PlaybackEngine without device streams. The test runs its
 * audio callback with a tone as the microphone and asserts on the PCM it plays.
 */
@RunWith(AndroidJUnit4::class)
class MonitoringAudioTest {
    private val handle = NativePlayback.createHeadless()

    /** Microphone frames fed so far, so the tone runs on across callbacks. */
    private var micFrame = 0

    @Before
    fun openSession() {
        // Silent Backing Audio, so the output is what Monitoring adds.
        val frames = 6 * RATE
        NativePlayback.beginLoad(handle, 2, RATE, frames.toLong())
        NativePlayback.appendLoad(handle, FloatArray(frames * 2), frames)
        NativePlayback.endLoad(handle)
        NativePlayback.setSessionMode(handle, true)
        assertTrue(NativePlayback.enableInput(handle))
    }

    @After
    fun destroy() {
        NativePlayback.destroyHeadless(handle)
    }

    /** Runs the audio callback for [frames] in device-sized bursts, with the microphone singing [MIC_HZ]. */
    private fun render(frames: Int): FloatArray {
        val out = FloatArray(frames * 2)
        val burst = FloatArray(BURST * 2)
        val mic = FloatArray(BURST)
        var done = 0
        while (done < frames) {
            val n = minOf(BURST, frames - done)
            for (i in 0 until n) mic[i] = MIC_LEVEL * tone(MIC_HZ, micFrame++)
            NativePlayback.renderHeadlessWithInput(handle, burst, n, mic, n)
            burst.copyInto(out, done * 2, 0, n * 2)
            done += n
        }
        return out
    }

    @Test
    fun monitoringIsOffUntilSwitchedOn() {
        assertTrue(NativePlayback.play(handle))

        val out = render(RATE)

        assertEquals(0.0, rms(out, 0, RATE), 1e-6)
        assertEquals(false, NativePlayback.monitoring(handle))
    }

    @Test
    fun monitoringAddsTheDryMicrophoneAtItsLevelUntransposed() {
        assertTrue(NativePlayback.setMonitoring(handle, true))
        NativePlayback.setMonitoringLevel(handle, 0.5f)
        NativePlayback.setTranspose(handle, 3)

        // Heard while paused, to set the level, and in free playback.
        val paused = render(RATE / 2)
        assertTrue(NativePlayback.play(handle))
        val playing = render(RATE)

        val expected = 0.5 * MIC_LEVEL / sqrt(2.0)
        assertEquals(expected, rms(paused, RATE / 10, RATE / 2), 0.01)
        assertEquals(expected, rms(playing, 0, RATE), 0.01)
        // Both channels, and not transposed with the Backing Audio.
        assertEquals(playing[RATE], playing[RATE + 1], 1e-6f)
        assertEquals(MIC_HZ, frequency(playing, 0, RATE), MIC_HZ * 0.005)
    }

    @Test
    fun monitoringIsHeardWhileRecordingButNotWhileTheTakePlaysAndNeverInTheTake() {
        assertTrue(NativePlayback.setMonitoring(handle, true))
        NativePlayback.setMonitoringLevel(handle, 0.5f)
        assertTrue(NativePlayback.startRecording(handle, 0, 0))

        val recording = render(2 * RATE)
        NativePlayback.stopRecording(handle)
        NativePlayback.setTakeGain(handle, 1f)
        assertTrue(NativePlayback.playTake(handle, 0, 0))
        val playingTake = render(RATE)

        assertEquals(0.5 * MIC_LEVEL / sqrt(2.0), rms(recording, RATE / 2, 2 * RATE), 0.01)
        // The Take is the microphone alone, at its own level: Monitoring isn't added to it, nor heard meanwhile.
        assertEquals(MIC_LEVEL / sqrt(2.0), rms(playingTake, RATE / 10, RATE - RATE / 10), 0.01)
    }

    private companion object {
        const val RATE = 48_000
        const val BURST = 192
        const val MIC_HZ = 440.0
        const val MIC_LEVEL = 0.4f

        fun tone(hz: Double, frame: Int): Float = sin(2 * PI * hz * frame / RATE).toFloat()

        /** RMS of both channels over frames [from, to). */
        fun rms(out: FloatArray, from: Int, to: Int): Double {
            var sum = 0.0
            for (i in from * 2 until to * 2) sum += out[i].toDouble() * out[i]
            return sqrt(sum / ((to - from) * 2))
        }

        /** The left channel's frequency from its rising zero crossings over [from, to). */
        fun frequency(out: FloatArray, from: Int, to: Int): Double {
            var first = -1.0
            var last = -1.0
            var crossings = 0
            for (i in from + 1 until to) {
                val x = out[(i - 1) * 2]
                val y = out[i * 2]
                if (x < 0 && y >= 0) {
                    val at = i - 1 + x / (x - y).toDouble()
                    if (first < 0) first = at else crossings++
                    last = at
                }
            }
            return if (crossings == 0) 0.0 else crossings * RATE / (last - first)
        }
    }
}
