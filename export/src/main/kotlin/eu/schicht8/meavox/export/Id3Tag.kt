// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.export

import eu.schicht8.meavox.core.port.ExportTags
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * The ID3v2.4 tag an MP3 Export starts with (ADR 8): TIT2 (title) and TPE1 (artist), in UTF-8. Not
 * v2.3: its UTF-16 frames that hold only Latin-1 characters are collapsed to 8 bits by Android, whose
 * charset detector then garbles the other frames (seen on Android 14).
 */
internal fun id3Tag(tags: ExportTags): ByteArray {
    val frames = ByteArrayOutputStream()
    frames.write(textFrame("TIT2", tags.title))
    tags.artist?.let { frames.write(textFrame("TPE1", it)) }
    val body = frames.toByteArray()
    return ByteBuffer.allocate(HEADER_BYTES + body.size)
        .put("ID3".toByteArray(Charsets.ISO_8859_1))
        .put(4).put(0) // v2.4.0
        .put(0) // no flags
        .put(syncsafe(body.size))
        .put(body)
        .array()
}

private fun textFrame(id: String, text: String): ByteArray {
    val encoded = text.toByteArray(Charsets.UTF_8)
    return ByteBuffer.allocate(HEADER_BYTES + 1 + encoded.size)
        .put(id.toByteArray(Charsets.ISO_8859_1))
        .put(syncsafe(1 + encoded.size)) // v2.4 frame sizes are syncsafe too
        .putShort(0) // no flags
        .put(UTF_8)
        .put(encoded)
        .array()
}

/** A size as four 7-bit bytes, so no byte of it looks like an MPEG sync. */
private fun syncsafe(size: Int): ByteArray {
    require(size < 1 shl 28) { "$size bytes are too many for ID3" }
    return ByteArray(4) { i -> ((size shr (7 * (3 - i))) and 0x7F).toByte() }
}

/** The tag header and a frame header are both 10 bytes. */
private const val HEADER_BYTES = 10
private const val UTF_8: Byte = 3
