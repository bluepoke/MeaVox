// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.schicht8.meavox.core.model.AudioFile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * Seam 2 (#44): the loudness measurement on FLAC files as Takes and stems are stored, against
 * reference signals of known loudness (EBU Tech 3341): a 997 Hz sine at −23 dBFS on both channels
 * reads −23 LUFS.
 */
@RunWith(AndroidJUnit4::class)
class NativeLoudnessMeterTest {
    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "loudness-test").apply { mkdirs() }
    private val meter = NativeLoudnessMeter()

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

    private fun measure(audio: AudioFile, from: Long = 0, to: Long = Long.MAX_VALUE) = runBlocking { meter.integratedLoudness(audio, from, to) }

    @Test
    fun aStereoSineAtMinus23DbfsReadsMinus23Lufs() {
        val sine = flac("stereo", 2, 20 * RATE) { sine(-23.0, it) }

        assertEquals(-23f, checkNotNull(measure(sine)), 0.1f)
    }

    @Test
    fun aMonoTakeCountsOnBothChannelsAsItPlaysInAMix() {
        val take = flac("take", 1, 20 * RATE) { sine(-23.0, it) }

        assertEquals(-23f, checkNotNull(measure(take)), 0.1f)
    }

    @Test
    fun onlyTheAskedSpanIsMeasured() {
        // −33 dBFS for 10 s, then −18 dBFS for 10 s.
        val backing = flac("backing", 2, 20 * RATE) { sine(if (it < 10 * RATE) -33.0 else -18.0, it) }

        assertEquals(-33f, checkNotNull(measure(backing, 0, 10L * RATE)), 0.1f)
        assertEquals(-18f, checkNotNull(measure(backing, 10L * RATE, 20L * RATE)), 0.1f)
    }

    @Test
    fun gatingLeavesQuietPartsOut() {
        // EBU Tech 3341, case 3: −36 / −23 / −36 dBFS for 10 / 60 / 10 s reads −23 LUFS.
        val signal = flac("gated", 2, 80 * RATE) { sine(if (it in 10 * RATE until 70 * RATE) -23.0 else -36.0, it) }

        assertEquals(-23f, checkNotNull(measure(signal)), 0.1f)
    }

    @Test
    fun silenceAndAudioShorterThanOneGatingBlockHaveNoMeasurableLoudness() {
        assertNull(measure(flac("silent", 1, 5 * RATE) { 0f }))
        assertNull(measure(flac("short", 1, RATE * 3 / 10) { sine(-10.0, it) }))
    }

    private companion object {
        const val RATE = 48_000

        /** A 997 Hz sine whose peak is at [dbfs]. */
        fun sine(dbfs: Double, frame: Int): Float = (10.0.pow(dbfs / 20) * sin(2 * PI * 997 * frame / RATE)).toFloat()
    }
}
