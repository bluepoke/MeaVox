// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.schicht8.meavox.core.mix.VocalEffects
import eu.schicht8.meavox.core.model.AudioFile
import eu.schicht8.meavox.core.model.Transpose
import eu.schicht8.meavox.core.port.MixRender
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Seam 3 of #16: the Offline Render of a Mix with Vocal Effects and a Guide Vocal, on synthetic
 * Takes and stems stored as FLAC, asserted on the rendered PCM.
 */
@RunWith(AndroidJUnit4::class)
class MixEffectsRenderTest {
    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "mix-effects-test").apply { mkdirs() }
    private val renderer = NativeOfflineRenderer()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun flac(name: String, channels: Int, frames: Int, sample: (frame: Int, channel: Int) -> Float): AudioFile {
        val file = File(dir, "$name.flac")
        FlacFiles.writer(file, channels, RATE).use { writer ->
            val block = FloatArray(4096 * channels)
            var done = 0
            while (done < frames) {
                val n = minOf(4096, frames - done)
                for (i in 0 until n) for (c in 0 until channels) block[i * channels + c] = sample(done + i, c)
                writer.write(block, n)
                done += n
            }
            writer.finish()
        }
        return AudioFile(file.path)
    }

    private fun silentBacking(seconds: Int) = flac("backing", 2, seconds * RATE) { _, _ -> 0f }

    private var takes = 0

    /** A mono Take of [seconds], each in a file of its own. */
    private fun take(seconds: Int, sample: (Int) -> Float) = flac("take${++takes}", 1, seconds * RATE) { i, _ -> sample(i) }

    /** Renders the Take from frame 0 over [backing] for [seconds] (plus the reverb's tail, as the Mix does). */
    private fun render(
        take: AudioFile,
        effects: VocalEffects = VocalEffects.None,
        seconds: Int = 2,
        backing: AudioFile = silentBacking(seconds + 4),
        guideVocal: AudioFile? = null,
        guideVocalLevel: Float = 0f,
        transpose: Transpose = Transpose.None,
    ): FloatArray {
        val to = seconds.toLong() * RATE + effects.tailFrames(RATE)
        val render = MixRender(
            backing, take, RATE, takeStartFrame = 0, alignmentFrames = 0, transpose = transpose, backingGainDb = 0f, takeGainDb = 0f,
            fromFrame = 0, toFrame = to, effects = effects, guideVocal = guideVocal, guideVocalLevel = guideVocalLevel,
        )
        val out = ArrayList<Float>()
        runBlocking { renderer.render(render) { interleaved, frames -> for (i in 0 until frames * 2) out += interleaved[i] } }
        return out.toFloatArray()
    }

    @Test
    fun aMixWithEffectsRendersTheSameEachTime() {
        val take = take(2) { i -> 0.6f * tone(220.0, i) * (if (i % 9_600 < 4_800) 1f else 0.1f) }
        val all = VocalEffects(VocalEffects.Reverb.Hall, compression = true, highPass = true)

        val first = render(take, all)
        val second = render(take, all)

        assertEquals(2 * RATE + 120_000, first.frames())
        assertArrayEquals(first, second, 0f)
    }

    @Test
    fun withoutEffectsTheTakeIsMixedAsItIs() {
        val take = take(1) { i -> 0.5f * tone(440.0, i) }

        val out = render(take, VocalEffects.None, seconds = 1)

        for (i in 0 until RATE) {
            assertEquals(0.5f * tone(440.0, i), out.left(i), 1e-4f)
            assertEquals(out.left(i), out.right(i), 0f)
        }
    }

    @Test
    fun theHighPassTakesOutRumbleAndKeepsTheVoice() {
        val rumble = take(2) { i -> 0.5f * tone(40.0, i) }
        val voice = take(2) { i -> 0.5f * tone(1_000.0, i) }
        val highPass = VocalEffects(highPass = true)

        val rumbleLeft = rms(render(rumble, highPass), from = RATE / 2, to = 2 * RATE) / rms(render(rumble), from = RATE / 2, to = 2 * RATE)
        val voiceLeft = rms(render(voice, highPass), from = RATE / 2, to = 2 * RATE) / rms(render(voice), from = RATE / 2, to = 2 * RATE)

        // 40 Hz is 1.3 octaves below 100 Hz: about −16 dB through a 2nd-order filter.
        assertTrue("40 Hz kept at $rumbleLeft", rumbleLeft < 0.25)
        assertTrue("1 kHz kept at $voiceLeft", voiceLeft > 0.97)
    }

    @Test
    fun compressionLowersLoudPassagesAndLiftsQuietOnes() {
        val loud = take(2) { i -> 0.9f * tone(440.0, i) }
        val quiet = take(2) { i -> 0.02f * tone(440.0, i) }
        val compression = VocalEffects(compression = true)

        val loudGain = rms(render(loud, compression), from = RATE / 2, to = 2 * RATE) / rms(render(loud), from = RATE / 2, to = 2 * RATE)
        val quietGain = rms(render(quiet, compression), from = RATE / 2, to = 2 * RATE) / rms(render(quiet), from = RATE / 2, to = 2 * RATE)

        // Far above the threshold: 3:1 plus 3 dB make-up is a cut of several dB; far below: just the make-up, +3 dB.
        assertTrue("loud passage at ×$loudGain", loudGain < 0.6)
        assertEquals(10.0.pow(3.0 / 20), quietGain, 0.02)
    }

    @Test
    fun aReverbRingsOnAfterTheVoiceAndTheHallLongerThanTheRoom() {
        val click = take(1) { i -> if (i == 1_000) 0.8f else 0f }

        val dry = render(click, VocalEffects.None, seconds = 1)
        val room = render(click, VocalEffects(VocalEffects.Reverb.Room), seconds = 1)
        val hall = render(click, VocalEffects(VocalEffects.Reverb.Hall), seconds = 1)

        // Nothing after the click when Dry; a stereo tail with a reverb.
        assertEquals(0.0, rms(dry, from = 2_000, to = RATE), 0.0)
        assertTrue(rms(room, from = 2_000, to = RATE / 2) > 1e-3)
        assertTrue((2_000 until RATE / 2).any { room.left(it) != room.right(it) })
        // Half a second to a second after the click, the Hall still rings louder than the Room.
        assertTrue(rms(hall, from = RATE / 2 + 1_000, to = RATE + 1_000) > 2 * rms(room, from = RATE / 2 + 1_000, to = RATE + 1_000))
        // The Mix runs on for the tail, which has faded by its end.
        assertEquals(RATE + 120_000, hall.frames())
        assertTrue(rms(hall, from = hall.frames() - 4_800, to = hall.frames()) < rms(hall, from = 2_000, to = 6_800) / 30)
    }

    @Test
    fun theGuideVocalJoinsTheBackingAtItsLevel() {
        val silentTake = take(2) { 0f }
        val backing = flac("instrumental", 2, 6 * RATE) { _, _ -> 0f }
        val vocalStem = flac("vocals", 2, 6 * RATE) { i, c -> if (i == 30_000 && c == 0) 0.8f else 0f }

        val out = render(silentTake, backing = backing, guideVocal = vocalStem, guideVocalLevel = 0.25f)
        val none = render(silentTake, backing = backing, guideVocal = vocalStem, guideVocalLevel = 0f)

        assertEquals(0.2f, out.left(30_000), 1e-4f)
        assertEquals(0f, out.right(30_000), 0f)
        assertEquals(0.0, rms(none, from = 0, to = none.frames()), 0.0)
    }

    @Test
    fun theGuideVocalIsTransposedWithTheBacking() {
        val silentTake = take(3) { 0f }
        val backing = flac("instrumental", 2, 7 * RATE) { _, _ -> 0f }
        val vocalStem = flac("vocals", 2, 7 * RATE) { i, _ -> 0.4f * tone(440.0, i) }

        val out = render(silentTake, seconds = 3, backing = backing, guideVocal = vocalStem, guideVocalLevel = 1f, transpose = Transpose(3))

        val expected = 440.0 * 2.0.pow(3 / 12.0)
        val measured = frequency(out, from = RATE / 2, to = 3 * RATE - RATE / 2)
        assertTrue("$measured Hz, expected $expected Hz", abs(measured / expected - 1) < 0.01)
    }

    private fun FloatArray.left(frame: Int) = this[frame * 2]

    private fun FloatArray.right(frame: Int) = this[frame * 2 + 1]

    private fun FloatArray.frames() = size / 2

    private companion object {
        const val RATE = 48_000

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
                val a = out[(i - 1) * 2]
                val b = out[i * 2]
                if (a < 0 && b >= 0) {
                    val at = i - 1 + a / (a - b).toDouble()
                    if (first < 0) first = at else crossings++
                    last = at
                }
            }
            return crossings * RATE / (last - first)
        }
    }
}
