// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.export

import eu.schicht8.meavox.core.port.ExportTags
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Writes AAC frames into an M4A file (ADR 8) with the title, the artist and the Cover Art (#42) as
 * iTunes metadata, which the platform's MediaMuxer can't write. The frames stream into `mdat` as they
 * come; `moov`, with the sample table, follows at the end, since only then are their number and sizes
 * known. All frames form one chunk. Each frame holds [SAMPLES_PER_FRAME] samples per channel (AAC-LC).
 */
internal class M4aWriter(
    private val channel: FileChannel,
    private val sampleRate: Int,
    private val channels: Int,
    private val tags: ExportTags,
) {
    private val mdatAt: Long
    private var sizes = IntArray(1024)
    private var count = 0
    private var bytes = 0L

    init {
        require(sampleRate in 1..0xFFFF) { "an MP4 sample entry can't hold a rate of $sampleRate Hz" }
        val ftyp = boxBytes("ftyp") {
            ascii("M4A ")
            writeInt(0)
            ascii("M4A "); ascii("mp42"); ascii("isom")
        }
        channel.writeFully(ByteBuffer.wrap(ftyp))
        mdatAt = channel.position()
        // The size is written over in [finish].
        channel.writeFully(ByteBuffer.allocate(8).putInt(8).put(fourCc("mdat")).also { it.flip() })
    }

    /** Appends one AAC frame (an access unit without ADTS header), from its position to its limit. */
    fun writeSample(frame: ByteBuffer) {
        val size = frame.remaining()
        if (count == sizes.size) sizes = sizes.copyOf(count * 2)
        sizes[count++] = size
        bytes += size
        channel.writeFully(frame)
    }

    /** Writes the sample table and the tags, with the encoder's AudioSpecificConfig [decoderConfig]. */
    fun finish(decoderConfig: ByteArray) {
        val mdatSize = 8 + bytes
        if (mdatAt + mdatSize > UINT32_MAX) throw IOException("too long for an M4A file")
        channel.writeFully(ByteBuffer.wrap(moov(decoderConfig, firstSampleAt = mdatAt + 8)))
        channel.write(ByteBuffer.allocate(4).putInt(mdatSize.toInt()).also { it.flip() }, mdatAt)
        channel.force(false)
    }

    private fun moov(decoderConfig: ByteArray, firstSampleAt: Long): ByteArray {
        val duration = count.toLong() * SAMPLES_PER_FRAME
        if (duration > UINT32_MAX) throw IOException("too long for an M4A file")
        val seconds = duration.toDouble() / sampleRate
        val averageBitrate = if (seconds > 0) (bytes * 8 / seconds).toLong().coerceAtMost(UINT32_MAX) else 0L
        val largest = sizes.take(count).maxOrNull() ?: 0
        return boxBytes("moov") {
            fullBox("mvhd") {
                writeInt(0); writeInt(0) // created, modified
                writeInt(sampleRate)
                writeInt(duration.toInt())
                writeInt(0x0001_0000) // rate 1.0
                writeShort(0x0100) // volume 1.0
                write(ByteArray(10))
                matrix()
                write(ByteArray(24))
                writeInt(2) // next track ID
            }
            box("trak") {
                fullBox("tkhd", flags = 0x7) { // enabled, in movie, in preview
                    writeInt(0); writeInt(0)
                    writeInt(1) // track ID
                    writeInt(0)
                    writeInt(duration.toInt())
                    write(ByteArray(8))
                    writeShort(0); writeShort(0) // layer, alternate group
                    writeShort(0x0100) // volume 1.0
                    writeShort(0)
                    matrix()
                    writeInt(0); writeInt(0) // width, height
                }
                box("mdia") {
                    fullBox("mdhd") {
                        writeInt(0); writeInt(0)
                        writeInt(sampleRate)
                        writeInt(duration.toInt())
                        writeShort(UNDETERMINED_LANGUAGE)
                        writeShort(0)
                    }
                    handler("soun", "SoundHandler")
                    box("minf") {
                        fullBox("smhd") { writeInt(0) }
                        box("dinf") {
                            fullBox("dref") {
                                writeInt(1)
                                fullBox("url ", flags = 1) {} // the media is in this file
                            }
                        }
                        box("stbl") {
                            fullBox("stsd") {
                                writeInt(1)
                                box("mp4a") {
                                    write(ByteArray(6))
                                    writeShort(1) // data reference
                                    write(ByteArray(8))
                                    writeShort(channels)
                                    writeShort(16)
                                    writeShort(0); writeShort(0)
                                    writeInt(sampleRate shl 16)
                                    esds(decoderConfig, largest, averageBitrate)
                                }
                            }
                            fullBox("stts") {
                                if (count > 0) {
                                    writeInt(1); writeInt(count); writeInt(SAMPLES_PER_FRAME)
                                } else {
                                    writeInt(0)
                                }
                            }
                            fullBox("stsc") {
                                if (count > 0) {
                                    writeInt(1); writeInt(1); writeInt(count); writeInt(1)
                                } else {
                                    writeInt(0)
                                }
                            }
                            fullBox("stsz") {
                                writeInt(0) // sizes differ
                                writeInt(count)
                                for (i in 0 until count) writeInt(sizes[i])
                            }
                            fullBox("stco") {
                                if (count > 0) {
                                    writeInt(1); writeInt(firstSampleAt.toInt())
                                } else {
                                    writeInt(0)
                                }
                            }
                        }
                    }
                }
            }
            box("udta") {
                fullBox("meta") {
                    handler("mdir", "", reserved = "appl")
                    box("ilst") {
                        item("©nam", tags.title)
                        tags.artist?.let { item("©ART", it) }
                        tags.cover?.let { cover(it.jpeg) }
                    }
                }
            }
        }
    }

    /** The MPEG-4 elementary stream descriptor (ISO/IEC 14496-1) of an AAC stream. */
    private fun DataOutputStream.esds(decoderConfig: ByteArray, largestFrame: Int, averageBitrate: Long) = fullBox("esds") {
        val specific = descriptor(0x05, decoderConfig)
        val config = descriptor(
            0x04,
            ByteBuffer.allocate(13)
                .put(0x40) // MPEG-4 audio
                .put(0x15) // audio stream, upstream 0, reserved 1
                .put((largestFrame shr 16).toByte()).putShort(largestFrame.toShort()) // buffer size, 24 bits
                .putInt((largestFrame.toLong() * 8 * sampleRate / SAMPLES_PER_FRAME).coerceAtMost(UINT32_MAX).toInt()) // max bitrate
                .putInt(averageBitrate.toInt())
                .array() + specific,
        )
        val sl = descriptor(0x06, byteArrayOf(0x02)) // predefined for MP4 files
        write(descriptor(0x03, byteArrayOf(0, 1, 0) + config + sl)) // ES ID 1, no flags
    }

    /** A descriptor with its size in the four-byte form, which every reader takes. */
    private fun descriptor(tag: Int, body: ByteArray): ByteArray {
        val n = body.size
        return byteArrayOf(
            tag.toByte(),
            (0x80 or (n shr 21 and 0x7F)).toByte(),
            (0x80 or (n shr 14 and 0x7F)).toByte(),
            (0x80 or (n shr 7 and 0x7F)).toByte(),
            (n and 0x7F).toByte(),
        ) + body
    }

    private fun DataOutputStream.handler(type: String, name: String, reserved: String? = null) = fullBox("hdlr") {
        writeInt(0)
        ascii(type)
        if (reserved != null) ascii(reserved) else writeInt(0)
        writeInt(0); writeInt(0)
        write(name.toByteArray(Charsets.UTF_8)); write(0)
    }

    /** An iTunes metadata item holding UTF-8 text. */
    private fun DataOutputStream.item(type: String, text: String) = box(type) {
        box("data") {
            writeInt(1) // UTF-8
            writeInt(0) // no locale
            write(text.toByteArray(Charsets.UTF_8))
        }
    }

    /** The cover item holding a JPEG picture. */
    private fun DataOutputStream.cover(jpeg: ByteArray) = box("covr") {
        box("data") {
            writeInt(13) // JPEG
            writeInt(0) // no locale
            write(jpeg)
        }
    }

    private fun DataOutputStream.matrix() {
        intArrayOf(0x0001_0000, 0, 0, 0, 0x0001_0000, 0, 0, 0, 0x4000_0000).forEach(::writeInt)
    }

    private fun DataOutputStream.ascii(text: String) = write(fourCc(text))

    private fun DataOutputStream.box(type: String, body: DataOutputStream.() -> Unit) = write(boxBytes(type, body))

    private fun DataOutputStream.fullBox(type: String, flags: Int = 0, body: DataOutputStream.() -> Unit) =
        write(boxBytes(type) { writeInt(flags); body() }) // version 0

    private fun boxBytes(type: String, body: DataOutputStream.() -> Unit): ByteArray {
        val content = ByteArrayOutputStream().also { DataOutputStream(it).body() }.toByteArray()
        return ByteBuffer.allocate(8 + content.size).putInt(8 + content.size).put(fourCc(type)).put(content).array()
    }

    /** A box type or brand: four characters, "©" as the one byte 0xA9. */
    private fun fourCc(text: String): ByteArray = text.toByteArray(Charsets.ISO_8859_1).also { check(it.size == 4) }

    companion object {
        /** Samples per channel in one AAC-LC frame. */
        const val SAMPLES_PER_FRAME = 1024

        private const val UINT32_MAX = 0xFFFF_FFFFL

        /** "und", packed as three 5-bit letters. */
        private const val UNDETERMINED_LANGUAGE = 0x55C4
    }
}
