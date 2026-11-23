// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.export

import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.round

/**
 * Writes PCM WAV (16- or 24-bit, ADR 8), with [tags] in an INFO list. With a length known up front
 * ([frames]) the stream never needs to seek. Without one, the header written first says 0 frames,
 * and [header] gives the real one, of the same size, to write over it once [finish]ed. Float samples
 * beyond ±1 are clipped; there is no dither.
 */
class WavEncoder(
    private val out: OutputStream,
    private val sampleRate: Int,
    private val channels: Int,
    private val bitsPerSample: Int,
    private val frames: Long?,
    private val tags: WavTags? = null,
) {
    private val bytesPerSample = bitsPerSample / 8
    private val scale = ((1 shl (bitsPerSample - 1)) - 1).toFloat()
    private val info = tags?.let(::infoList) ?: ByteArray(0)
    private var buffer = ByteArray(0)
    private var written = 0L

    init {
        require(bitsPerSample == 16 || bitsPerSample == 24) { "WAV is written at 16 or 24 bits, not $bitsPerSample" }
        require(channels > 0 && sampleRate > 0 && (frames == null || frames >= 0))
        out.write(header(frames ?: 0))
    }

    /** Appends [frames] frames from [interleaved] (`frames * channels` samples). */
    fun write(interleaved: FloatArray, frames: Int) {
        if (this.frames != null && written + frames > this.frames) throw IOException("more than the ${this.frames} frames announced")
        val samples = frames * channels
        val size = samples * bytesPerSample
        if (buffer.size < size) buffer = ByteArray(size)
        var at = 0
        for (i in 0 until samples) {
            // Half to even, so positive and negative samples round alike.
            val v = round(interleaved[i].coerceIn(-1f, 1f) * scale).toInt()
            buffer[at++] = v.toByte()
            buffer[at++] = (v shr 8).toByte()
            if (bytesPerSample == 3) buffer[at++] = (v shr 16).toByte()
        }
        out.write(buffer, 0, size)
        written += frames
    }

    /** Flushes the stream; fails if fewer frames were written than announced. */
    fun finish() {
        if (frames != null && written != frames) throw IOException("$written of $frames frames written")
        out.flush()
    }

    /** The header for the frames written so far: the real one of a WAV of unknown length. */
    fun header(): ByteArray = header(written)

    private fun header(frames: Long): ByteArray {
        val dataBytes = frames * channels * bytesPerSample
        val size = FMT_BYTES + info.size + 8
        if (dataBytes > UINT32_MAX - size) throw IOException("too long for a WAV file")
        val header = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        header.put(ascii("RIFF")).putInt((size - 8 + dataBytes).toInt())
        header.put(ascii("WAVE"))
        header.put(ascii("fmt ")).putInt(16)
        header.putShort(1) // PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(sampleRate * channels * bytesPerSample)
        header.putShort((channels * bytesPerSample).toShort())
        header.putShort(bitsPerSample.toShort())
        header.put(info)
        header.put(ascii("data")).putInt(dataBytes.toInt())
        return header.array()
    }

    private companion object {
        /** RIFF and WAVE (12 bytes) and the fmt chunk (24). */
        const val FMT_BYTES = 36
        const val UINT32_MAX = 0xFFFF_FFFFL

        fun ascii(id: String) = id.toByteArray(Charsets.US_ASCII)

        /** `LIST` `INFO` with INAM (title) and IART (artist), each NUL-terminated and padded to even. */
        fun infoList(tags: WavTags): ByteArray {
            val entries = listOfNotNull("INAM" to tags.title, tags.artist?.let { "IART" to it })
            val chunks = entries.map { (id, text) ->
                val bytes = text.toByteArray(Charsets.UTF_8) + 0
                val padded = bytes.size + bytes.size % 2
                ByteBuffer.allocate(8 + padded).order(ByteOrder.LITTLE_ENDIAN).put(ascii(id)).putInt(bytes.size).put(bytes).array()
            }
            val body = chunks.sumOf { it.size } + 4
            val list = ByteBuffer.allocate(8 + body).order(ByteOrder.LITTLE_ENDIAN).put(ascii("LIST")).putInt(body).put(ascii("INFO"))
            chunks.forEach { list.put(it) }
            return list.array()
        }
    }
}

/** What a WAV is tagged with (RIFF INFO: INAM, IART). */
data class WavTags(val title: String, val artist: String? = null)
