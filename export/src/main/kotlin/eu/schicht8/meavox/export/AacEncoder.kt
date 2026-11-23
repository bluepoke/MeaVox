// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.export

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import eu.schicht8.meavox.core.port.ExportTags
import java.io.IOException
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.roundToInt

/**
 * Writes AAC-LC at [BITRATE] through the platform's MediaCodec encoder (ADR 8) into an M4A file
 * with [tags] ([M4aWriter]). The encoder is fed 16-bit PCM; AAC adds a little priming and padding
 * at the ends, as AAC always does.
 */
internal class AacEncoder(channel: FileChannel, private val sampleRate: Int, tags: ExportTags) : PcmEncoder {
    private val m4a = M4aWriter(channel, sampleRate, CHANNELS, tags)
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val info = MediaCodec.BufferInfo()
    private var decoderConfig: ByteArray? = null
    private var framesQueued = 0L
    private var pcm = ShortArray(0)
    private var released = false

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
        }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) {
            release()
            throw IOException("this device can't encode AAC at $sampleRate Hz", e)
        }
    }

    override fun write(interleaved: FloatArray, frames: Int) {
        val samples = frames * CHANNELS
        if (pcm.size < samples) pcm = ShortArray(samples)
        for (i in 0 until samples) pcm[i] = (interleaved[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt().toShort()
        var at = 0
        while (at < samples) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) {
                drain(untilEnd = false)
                continue
            }
            val buffer = codec.getInputBuffer(index)!!.order(ByteOrder.nativeOrder())
            val n = minOf(samples - at, buffer.remaining() / BYTES_PER_FRAME * CHANNELS)
            buffer.asShortBuffer().put(pcm, at, n)
            codec.queueInputBuffer(index, 0, n * 2, framesQueued * 1_000_000 / sampleRate, 0)
            framesQueued += n / CHANNELS
            at += n
            drain(untilEnd = false)
        }
    }

    override fun finish() {
        while (true) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                codec.queueInputBuffer(index, 0, 0, framesQueued * 1_000_000 / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                break
            }
            drain(untilEnd = false)
        }
        drain(untilEnd = true)
        m4a.finish(decoderConfig ?: throw IOException("the AAC encoder gave no decoder config"))
    }

    /** Moves the encoded frames into the file: those ready now, or [untilEnd] of the stream. */
    private fun drain(untilEnd: Boolean) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (untilEnd) TIMEOUT_US else 0)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                    codec.outputFormat.getByteBuffer(CSD)?.let { decoderConfig = ByteArray(it.remaining()).also(it::get) }
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEnd) return
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)!!
                    buffer.position(info.offset).limit(info.offset + info.size)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        if (decoderConfig == null) decoderConfig = ByteArray(info.size).also(buffer::get)
                    } else if (info.size > 0) {
                        m4a.writeSample(buffer)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    override fun release() {
        if (released) return
        released = true
        runCatching { codec.stop() }
        codec.release()
    }

    private companion object {
        const val CHANNELS = 2
        const val BYTES_PER_FRAME = 2 * CHANNELS
        const val BITRATE = 256_000
        const val TIMEOUT_US = 10_000L
        const val CSD = "csd-0"
    }
}
