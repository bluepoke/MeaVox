// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.schicht8.meavox.core.model.AudioFile
import eu.schicht8.meavox.core.model.Transpose
import eu.schicht8.meavox.core.port.MixRender
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/**
 * Seam 2 (#44): the true-peak limiter on a Mix's sum in the Offline Render, which the Mix Preview
 * shares. It keeps a loud Mix at its ceiling (−1 dBTP), and leaves a Mix below it exactly as it was, at
 * the same frames. A Backing Export isn't limited.
 */
@RunWith(AndroidJUnit4::class)
class MixLimiterTest {
    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "mix-limiter-test").apply { mkdirs() }
    private val renderer = NativeOfflineRenderer()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun flac(name: String, channels: Int, frames: Int, sample: (frame: Int) -> Float): AudioFile {
        val file = File(dir, "$name.flac")
        FlacFiles.writer(file, channels, RATE).use { writer ->
            val block = FloatArray(4096 * channels)
            var done = 0
            while (done < frames) {
                val n = minOf(4096, frames - done)
                for (i in 0 until n) for (c in 0 until channels) block[i * channels + c] = sample(done + i)
                writer.write(block, n)
                done += n
            }
            writer.finish()
        }
        return AudioFile(file.path)
    }

    private fun render(backing: AudioFile, take: AudioFile, from: Long, to: Long, backingDb: Float, takeDb: Float): FloatArray {
        val out = ArrayList<Float>()
        runBlocking {
            renderer.render(MixRender(backing, take, RATE, from, 0, Transpose.None, backingDb, takeDb, from, to)) { interleaved, frames ->
                for (i in 0 until frames * 2) out += interleaved[i]
            }
        }
        return out.toFloatArray()
    }

    @Test
    fun aLoudMixIsKeptAtTheCeiling() {
        // A mastered song near full scale and a loud voice over it, phrases every 100 ms: far beyond full scale summed.
        val backing = flac("backing", 2, 2 * RATE) { 0.94f * tone(997.0, it) }
        val take = flac("take", 1, RATE) { 0.5f * tone(997.0, it + RATE) * if ((it / 4_800) % 2 == 0) 1f else 0.2f }

        val out = render(backing, take, from = RATE.toLong(), to = 2L * RATE, backingDb = 0f, takeDb = 12f)

        assertEquals(RATE, out.size / 2)
        val ceiling = 10.0.pow(-1.0 / 20)
        val samplePeak = out.maxOf { abs(it) }.toDouble()
        assertTrue("sample peak ${dbfs(samplePeak)} dBFS", samplePeak <= ceiling + 1e-4)
        val truePeak = dbfs(truePeak(out))
        assertTrue("true peak $truePeak dBTP", truePeak <= -0.5)
    }

    @Test
    fun aMixBelowTheCeilingIsLeftExactlyAsItWasAtTheSameFrames() {
        val backing = flac("backing", 2, 3 * RATE) { 0.3f * tone(440.0, it) }
        val take = flac("take", 1, RATE) { if (it == 12_000) 0.4f else 0.2f * tone(997.0, it) }
        val from = RATE / 2

        val out = render(backing, take, from = from.toLong(), to = from + RATE.toLong(), backingDb = 0f, takeDb = 0f)

        assertEquals(RATE, out.size / 2)
        for (i in 0 until RATE) {
            val take = if (i == 12_000) 0.4f else 0.2f * tone(997.0, i)
            assertEquals("frame $i", 0.3f * tone(440.0, from + i) + take, out[i * 2], 1e-5f)
        }
    }

    @Test
    fun aBackingExportIsNotLimited() {
        // Its true peak is above the ceiling, as a mastered song's often is.
        val backing = flac("backing", 2, 2 * RATE) { 0.99f * tone(997.0, it) }
        val out = ArrayList<Float>()

        runBlocking { renderer.renderBacking(backing, Transpose.None) { interleaved, n -> for (i in 0 until n * 2) out += interleaved[i] } }

        assertEquals(2 * RATE, out.size / 2)
        for (i in 0 until 2 * RATE) assertEquals("frame $i", 0.99f * tone(997.0, i), out[i * 2], 1e-5f)
    }

    private companion object {
        const val RATE = 48_000

        fun tone(hz: Double, frame: Int): Float = sin(2 * PI * hz * frame / RATE).toFloat()

        fun dbfs(linear: Double) = 20 * log10(linear)

        /** Windowed-sinc taps for samples i − 31 … i + 32, at each eighth of the way from sample i to the next. */
        val OVERSAMPLING_TAPS: Array<DoubleArray> = Array(8) { p ->
            DoubleArray(64) { k ->
                val t = (k - 31) - p / 8.0
                val sinc = if (t == 0.0) 1.0 else sin(PI * t) / (PI * t)
                sinc * (0.5 + 0.5 * cos(PI * t / 33))
            }
        }

        /** The true peak of interleaved stereo [out], from 8× oversampling with a long windowed sinc. */
        fun truePeak(out: FloatArray): Double {
            val frames = out.size / 2
            var peak = 0.0
            for (c in 0 until 2) for (i in 31 until frames - 32) for (taps in OVERSAMPLING_TAPS) {
                var v = 0.0
                for (k in 0 until 64) v += out[(i - 31 + k) * 2 + c] * taps[k]
                peak = maxOf(peak, abs(v))
            }
            return peak
        }
    }
}
