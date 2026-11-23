// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.export

import eu.schicht8.meavox.core.port.ExportFormat
import eu.schicht8.meavox.core.port.ExportTags
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Writes an MP3 through LAME (ADR 8) into [channel]: the ID3v2 tag with [tags], then the stream. LAME
 * begins it with an empty Xing/LAME tag frame, which [finish] writes over with the real one once the
 * length is known, so players get the duration (also of VBR) and the encoder delay.
 */
internal class Mp3Encoder(
    private val channel: FileChannel,
    sampleRate: Int,
    bitrate: ExportFormat.Mp3.Bitrate,
    tags: ExportTags,
) : PcmEncoder {
    private var lame = nativeOpen(sampleRate, bitrate == ExportFormat.Mp3.Bitrate.VbrV2)
    private val streamAt: Long
    private var out = ByteArray(0)

    init {
        if (lame == 0L) throw IOException("LAME can't encode MP3 at $sampleRate Hz")
        try {
            channel.writeFully(ByteBuffer.wrap(id3Tag(tags)))
        } catch (e: IOException) {
            release()
            throw e
        }
        streamAt = channel.position()
    }

    override fun write(interleaved: FloatArray, frames: Int) {
        // LAME's bound for one call: 1.25 bytes per frame and 7 200 more.
        val bound = frames + frames / 4 + 7200
        if (out.size < bound) out = ByteArray(bound)
        val n = nativeEncode(lame, interleaved, frames, out)
        if (n < 0) throw IOException("LAME failed to encode ($n)")
        channel.writeFully(ByteBuffer.wrap(out, 0, n))
    }

    override fun finish() {
        if (out.size < FLUSH_BYTES) out = ByteArray(FLUSH_BYTES)
        val n = nativeFlush(lame, out)
        if (n < 0) throw IOException("LAME failed to finish ($n)")
        channel.writeFully(ByteBuffer.wrap(out, 0, n))
        val size = nativeLameTag(lame, out)
        if (size > out.size) throw IOException("LAME tag of $size bytes")
        if (size > 0) channel.write(ByteBuffer.wrap(out, 0, size), streamAt)
        channel.force(false)
    }

    override fun release() {
        if (lame != 0L) nativeClose(lame)
        lame = 0L
    }

    private companion object {
        /** What [nativeFlush] may write at most, which LAME documents as 7 200 bytes. */
        const val FLUSH_BYTES = 7200

        init {
            System.loadLibrary("meavox_mp3")
        }

        @JvmStatic external fun nativeOpen(sampleRate: Int, vbr: Boolean): Long

        @JvmStatic external fun nativeEncode(lame: Long, interleaved: FloatArray, frames: Int, out: ByteArray): Int

        @JvmStatic external fun nativeFlush(lame: Long, out: ByteArray): Int

        @JvmStatic external fun nativeLameTag(lame: Long, out: ByteArray): Int

        @JvmStatic external fun nativeClose(lame: Long)
    }
}
