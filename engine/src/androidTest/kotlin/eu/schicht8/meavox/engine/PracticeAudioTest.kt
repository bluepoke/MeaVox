// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Seam 2 of #18: the Practice Loop and the Guide Vocal in free playback, on the Audio Engine's native
 * PlaybackEngine without device streams, its audio callback run by the test, asserted on the PCM it plays.
 */
@RunWith(AndroidJUnit4::class)
class PracticeAudioTest {
    private val handle = NativePlayback.createHeadless()

    @After
    fun destroy() {
        NativePlayback.destroyHeadless(handle)
    }

    /** Loads [seconds] of stereo Backing Audio, its frames from [sample]. */
    private fun load(seconds: Int, sample: (Int) -> Float) {
        val frames = seconds * RATE
        NativePlayback.beginLoad(handle, 2, RATE, frames.toLong())
        NativePlayback.appendLoad(handle, interleaved(frames, sample), frames)
        NativePlayback.endLoad(handle)
    }

    private fun loadGuide(seconds: Int, sample: (Int) -> Float) {
        val frames = seconds * RATE
        assertTrue(NativePlayback.beginGuideLoad(handle, 2, RATE))
        NativePlayback.appendGuideLoad(handle, interleaved(frames, sample), frames)
    }

    /** Runs the audio callback for [frames], in bursts as a device asks for them; interleaved stereo. */
    private fun render(frames: Int): FloatArray {
        val out = FloatArray(frames * 2)
        val burst = FloatArray(BURST * 2)
        var done = 0
        while (done < frames) {
            val n = minOf(BURST, frames - done)
            NativePlayback.renderHeadless(handle, burst, n)
            burst.copyInto(out, done * 2, 0, n * 2)
            done += n
        }
        return out
    }

    @Test
    fun theLoopGoesFromBStraightBackToAWithoutAGapOrAClick() {
        load(4) { i -> 0.5f * tone(440.0, i) }
        // B (2.09 s) is at another phase of the tone than A (1 s): a cut there would click.
        val a = RATE
        val b = 2 * RATE + 4_320
        NativePlayback.setPracticeLoop(handle, 1_000, 2_090)
        NativePlayback.seekTo(handle, a.toLong())
        assertTrue(NativePlayback.play(handle))

        val out = render(3 * (b - a))

        // Each pass plays A…B as it is, apart from the last few milliseconds before B.
        val len = b - a
        for (pass in 0 until 3) {
            for (j in 0 until len - RATE / 50) {
                assertEquals("pass $pass, frame $j", 0.5f * tone(440.0, a + j), out[(pass * len + j) * 2], 1e-6f)
            }
        }
        // A tone this loud moves at most 0.029 a frame; a cut would jump up to 1.
        assertTrue("largest step ${largestStep(out)}", largestStep(out) < 0.04f)
        assertEquals(NativePlayback.PLAYING, NativePlayback.transport(handle))
        assertTrue(NativePlayback.positionFrames(handle) in a.toLong() until b.toLong())
    }

    @Test
    fun aTransposedLoopRunsOnWithoutAGap() {
        load(4) { i -> 0.5f * tone(440.0, i) }
        val a = RATE
        val b = 2 * RATE + 4_320
        NativePlayback.setPracticeLoop(handle, 1_000, 2_090)
        NativePlayback.setTranspose(handle, 3)
        NativePlayback.seekTo(handle, a.toLong())
        assertTrue(NativePlayback.play(handle))

        val out = render(3 * (b - a))

        // After the stretcher has settled, every 10 ms sound like the tone, across both wraps.
        val expected = 0.5 / sqrt(2.0)
        for (from in RATE / 5 until out.size / 2 - RATE / 100 step RATE / 100) {
            val level = rms(out, from, from + RATE / 100)
            assertTrue("level $level at frame $from", level > 0.6 * expected)
        }
        val measured = frequency(out, RATE / 5, out.size / 2)
        val wanted = 440.0 * 2.0.pow(3 / 12.0)
        assertTrue("$measured Hz, expected $wanted Hz", abs(measured / wanted - 1) < 0.01)
    }

    @Test
    fun playbackFromPastBPlaysStraightOn() {
        load(4) { i -> 0.5f * tone(440.0, i) }
        NativePlayback.setPracticeLoop(handle, 1_000, 2_000)
        NativePlayback.seekTo(handle, 2L * RATE + RATE / 2)
        assertTrue(NativePlayback.play(handle))

        render(RATE)

        assertEquals(3L * RATE + RATE / 2, NativePlayback.positionFrames(handle))
    }

    @Test
    fun theLoopStaysAtItsTimesWhenAFileAtAnotherRateIsLoaded() {
        NativePlayback.setPracticeLoop(handle, 1_000, 2_000)
        val rate = 44_100
        NativePlayback.beginLoad(handle, 2, rate, 4L * rate)
        NativePlayback.appendLoad(handle, FloatArray(4 * rate * 2) { 0.1f }, 4 * rate)
        NativePlayback.endLoad(handle)
        NativePlayback.seekTo(handle, rate.toLong())
        assertTrue(NativePlayback.play(handle))

        render(rate + rate / 4)

        assertEquals(rate + rate / 4L, NativePlayback.positionFrames(handle))
    }

    @Test
    fun theGuideVocalJoinsFreePlaybackTransposedWithIt() {
        load(4) { 0f }
        loadGuide(4) { i -> 0.4f * tone(440.0, i) }
        NativePlayback.setGuideLevel(handle, 0.5f)
        NativePlayback.setTranspose(handle, 3)
        assertTrue(NativePlayback.play(handle))

        val out = render(3 * RATE)

        val measured = frequency(out, RATE / 2, 3 * RATE - RATE / 2)
        val wanted = 440.0 * 2.0.pow(3 / 12.0)
        assertTrue("$measured Hz, expected $wanted Hz", abs(measured / wanted - 1) < 0.01)
        val level = rms(out, RATE / 2, 3 * RATE - RATE / 2)
        assertEquals(0.5 * 0.4 / sqrt(2.0), level, 0.02)
    }

    private companion object {
        const val RATE = 48_000
        const val BURST = 192

        fun tone(hz: Double, frame: Int): Float = sin(2 * PI * hz * frame / RATE).toFloat()

        fun interleaved(frames: Int, sample: (Int) -> Float) = FloatArray(frames * 2) { sample(it / 2) }

        /** The largest change of the left channel from one frame to the next. */
        fun largestStep(out: FloatArray): Float = (1 until out.size / 2).maxOf { abs(out[it * 2] - out[(it - 1) * 2]) }

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
            return crossings * RATE / (last - first)
        }
    }
}
