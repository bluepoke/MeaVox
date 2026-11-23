// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.export

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.schicht8.meavox.core.port.ExportFormat
import eu.schicht8.meavox.core.port.ExportTags
import eu.schicht8.meavox.core.port.ExportedFile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Adapter contract of #15 (spec #1, testing decisions): a short Export in each format, written through MediaStore like a real one and read
 * back with the platform's extractor, decoders and metadata reader: its length, channels, sample
 * rate, tags, and that it holds the tone that went in.
 */
@RunWith(AndroidJUnit4::class)
class MediaStoreExportsRoundTripTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val exports = MediaStoreExports(context.contentResolver)
    private val made = mutableListOf<Uri>()
    private val tags = ExportTags("Round Trip – Für Elise (Mix 1)", "Björk feat. Peter")

    @After
    fun cleanUp() {
        made.forEach { context.contentResolver.delete(it, null, null) }
    }

    @Test
    fun mp3AtVbrV2() {
        roundTrip(ExportFormat.Mp3(ExportFormat.Mp3.Bitrate.VbrV2), "mp3", "audio/mpeg")
    }

    @Test
    fun mp3At320kbps() {
        val file = roundTrip(ExportFormat.Mp3(ExportFormat.Mp3.Bitrate.Cbr320), "mp3", "audio/mpeg")
        // 320 kbps for two seconds, give or take the tag and the encoder's first and last frames.
        val bytes = context.contentResolver.openFileDescriptor(Uri.parse(file.uri), "r")!!.use { it.statSize }
        assertTrue("$bytes bytes", abs(bytes - 320_000 / 8 * SECONDS) < 10_000)
    }

    @Test
    fun mp3OfUnknownLength() {
        roundTrip(ExportFormat.Mp3(), "mp3", "audio/mpeg", knownLength = false)
    }

    @Test
    fun m4a() {
        roundTrip(ExportFormat.M4a, "m4a", "audio/mp4")
    }

    @Test
    fun m4aOfUnknownLength() {
        roundTrip(ExportFormat.M4a, "m4a", "audio/mp4", knownLength = false)
    }

    @Test
    fun m4aAt44100Hz() {
        roundTrip(ExportFormat.M4a, "m4a", "audio/mp4", rate = 44_100)
    }

    @Test
    fun wavStillWorks() {
        roundTrip(ExportFormat.Wav(16), "wav", "audio/x-wav", knownLength = false)
    }

    private fun roundTrip(
        format: ExportFormat,
        extension: String,
        mimeType: String,
        knownLength: Boolean = true,
        rate: Int = 48_000,
    ): ExportedFile = runBlocking {
        val frames = rate * SECONDS
        val writer = exports.create(tags, format, rate, if (knownLength) frames.toLong() else null)
        val block = FloatArray(BLOCK * 2)
        var done = 0
        while (done < frames) {
            val n = minOf(BLOCK, frames - done)
            for (i in 0 until n) {
                // A tone on the left and silence on the right, so a mono file can't pass as stereo.
                block[2 * i] = (0.5 * sin(2 * PI * TONE_HZ * (done + i) / rate)).toFloat()
                block[2 * i + 1] = 0f
            }
            writer.write(block, n)
            done += n
        }
        val file = writer.commit()
        val uri = Uri.parse(file.uri).also { made += it }

        assertTrue(file.displayName, file.displayName.endsWith(".$extension"))
        assertEquals(mimeType, file.mimeType)
        val pending = context.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.IS_PENDING), null, null, null)!!.use {
            it.moveToFirst()
            it.getInt(0)
        }
        assertEquals("visible to other apps", 0, pending)

        // The platform's metadata reader doesn't read a WAV's RIFF INFO list; WavEncoderTest covers it.
        if (format !is ExportFormat.Wav) {
            MediaMetadataRetriever().use { meta ->
                meta.setDataSource(context, uri)
                assertEquals(tags.title, meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE))
                assertEquals(tags.artist, meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST))
            }
        }

        val decoded = decode(uri)
        assertEquals(2, decoded.channels)
        assertEquals(rate, decoded.sampleRate)
        assertTrue("${decoded.durationUs} µs", abs(decoded.durationUs - SECONDS * 1_000_000L) < TOLERANCE_US)
        assertTrue("${decoded.frames} frames", abs(decoded.frames - frames) < rate * TOLERANCE_US / 1_000_000)
        // The tone (RMS 0.35) on the left, next to nothing on the right.
        assertTrue("left RMS ${decoded.leftRms}", decoded.leftRms in 0.3..0.4)
        assertTrue("right RMS ${decoded.rightRms}", decoded.rightRms < 0.02)
        file
    }

    private class Decoded(val channels: Int, val sampleRate: Int, val durationUs: Long, val frames: Int, val leftRms: Double, val rightRms: Double)

    /** Decodes the whole file with the platform's decoder. */
    private fun decode(uri: Uri): Decoded {
        val extractor = MediaExtractor().apply { setDataSource(context, uri, null) }
        try {
            assertEquals(1, extractor.trackCount)
            val format = extractor.getTrackFormat(0)
            extractor.selectTrack(0)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var frames = 0
            var left = 0.0
            var right = 0.0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val n = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                if (o >= 0) {
                    val pcm = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                    val n = info.size / 2 / channels
                    for (f in 0 until n) {
                        val l = pcm.get(f * channels) / 32768.0
                        val r = pcm.get(f * channels + 1) / 32768.0
                        left += l * l
                        right += r * r
                    }
                    frames += n
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            codec.stop()
            codec.release()
            return Decoded(
                channels = channels,
                sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                durationUs = format.getLong(MediaFormat.KEY_DURATION),
                frames = frames,
                leftRms = sqrt(left / frames),
                rightRms = sqrt(right / frames),
            )
        } finally {
            extractor.release()
        }
    }

    private companion object {
        const val SECONDS = 2
        const val BLOCK = 4096
        const val TONE_HZ = 440.0

        /** What encoders add at the start and end: a few frames of priming and padding. */
        const val TOLERANCE_US = 100_000L
        const val TIMEOUT_US = 10_000L
    }
}
