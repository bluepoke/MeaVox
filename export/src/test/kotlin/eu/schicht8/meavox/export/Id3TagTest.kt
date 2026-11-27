// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.export

import eu.schicht8.meavox.core.model.CoverArt
import eu.schicht8.meavox.core.port.ExportTags
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The container writers of #15: the ID3v2.4 tag an MP3 Export starts with, read back by a minimal parser. */
class Id3TagTest {
    @Test
    fun `the tag carries the title and the artist`() {
        val frames = parse(id3Tag(ExportTags("Fly Me to the Moon (Mix 1)", "Frank Sinatra feat. Peter")))

        assertEquals(mapOf("TIT2" to "Fly Me to the Moon (Mix 1)", "TPE1" to "Frank Sinatra feat. Peter"), frames)
    }

    @Test
    fun `text in any script survives`() {
        val frames = parse(id3Tag(ExportTags("Für Elise – 夜に駆ける", "Björk 🎤")))

        assertEquals(mapOf("TIT2" to "Für Elise – 夜に駆ける", "TPE1" to "Björk 🎤"), frames)
    }

    @Test
    fun `without an artist there is no artist frame`() {
        assertEquals(mapOf("TIT2" to "Song"), parse(id3Tag(ExportTags("Song"))))
    }

    @Test
    fun `text is UTF-8, which Android reads as it is, also next to text that would fit Latin-1`() {
        // Android collapses a UTF-16 frame of Latin-1 characters to 8 bits and lets a detector guess the
        // charset of every frame together, which garbled the title of an MP3 tagged like this one.
        val tag = id3Tag(ExportTags("Round Trip – Für Elise", "Björk feat. Peter"))

        assertEquals(listOf(3, 3), encodings(tag))
    }

    @Test
    fun `the cover is an attached picture, the front cover as JPEG`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 0, 0xFF.toByte(), 0xD9.toByte())
        val tag = id3Tag(ExportTags("Song", "Artist", CoverArt(jpeg)))

        val picture = checkNotNull(picture(tag))
        assertEquals("image/jpeg", picture.mimeType)
        assertEquals(3, picture.type, "front cover")
        assertEquals("", picture.description)
        assertContentEquals(jpeg, picture.data)
        assertEquals(mapOf("TIT2" to "Song", "TPE1" to "Artist"), parse(tag), "the text frames stay")
    }

    @Test
    fun `without a cover there is no picture`() {
        assertNull(picture(id3Tag(ExportTags("Song", "Artist"))))
    }

    private class Picture(val mimeType: String, val type: Int, val description: String, val data: ByteArray)

    /** The APIC frame of [tag], if it has one. */
    private fun picture(tag: ByteArray): Picture? {
        val body = frameBodies(tag)["APIC"] ?: return null
        assertEquals(3, body[0].toInt(), "UTF-8 description")
        val mimeEnd = (1 until body.size).first { body[it] == 0.toByte() }
        val mimeType = String(body, 1, mimeEnd - 1, Charsets.ISO_8859_1)
        val type = body[mimeEnd + 1].toInt()
        val descriptionEnd = (mimeEnd + 2 until body.size).first { body[it] == 0.toByte() }
        val description = String(body, mimeEnd + 2, descriptionEnd - mimeEnd - 2, Charsets.UTF_8)
        return Picture(mimeType, type, description, body.copyOfRange(descriptionEnd + 1, body.size))
    }

    /** The text encoding byte of each frame. */
    private fun encodings(tag: ByteArray): List<Int> {
        val buffer = ByteBuffer.wrap(tag).apply { position(10) }
        val found = mutableListOf<Int>()
        while (buffer.remaining() >= 10) {
            buffer.position(buffer.position() + 4)
            val size = syncsafe(buffer)
            buffer.short
            found += buffer.get(buffer.position()).toInt()
            buffer.position(buffer.position() + size)
        }
        return found
    }

    private fun syncsafe(b: ByteBuffer): Int = (0 until 4).fold(0) { acc, _ -> (acc shl 7) or (b.get().toInt() and 0x7F) }

    /** The text frames of an ID3v2.4 tag that is exactly [tag]'s size, by frame ID. */
    private fun parse(tag: ByteArray): Map<String, String> =
        frameBodies(tag).filterKeys { it.startsWith("T") }.mapValues { (_, body) ->
            when (body[0].toInt()) {
                0 -> String(body, 1, body.size - 1, Charsets.ISO_8859_1)
                1 -> String(body, 1, body.size - 1, Charsets.UTF_16) // with a BOM
                3 -> String(body, 1, body.size - 1, Charsets.UTF_8)
                else -> error("encoding ${body[0]} isn't ID3v2.4")
            }.trimEnd('\u0000')
        }

    /** The frames of an ID3v2.4 tag that is exactly [tag]'s size: their bodies by frame ID. */
    private fun frameBodies(tag: ByteArray): Map<String, ByteArray> {
        val buffer = ByteBuffer.wrap(tag)
        assertEquals("ID3", String(tag, 0, 3, Charsets.ISO_8859_1))
        assertEquals(4, tag[3].toInt(), "major version")
        assertEquals(0, tag[4].toInt(), "revision")
        assertEquals(0, tag[5].toInt(), "flags")
        val size = (6..9).fold(0) { acc, i -> (acc shl 7) or (tag[i].toInt() and 0x7F) }
        assertEquals(tag.size - 10, size, "tag size (syncsafe) counts everything after the header")
        buffer.position(10)
        val frames = mutableMapOf<String, ByteArray>()
        while (buffer.remaining() >= 10) {
            val id = ByteArray(4).also(buffer::get).toString(Charsets.ISO_8859_1)
            val frameSize = syncsafe(buffer)
            buffer.short // flags
            if (id == "\u0000\u0000\u0000\u0000") break // padding
            frames[id] = ByteArray(frameSize).also(buffer::get)
        }
        return frames
    }
}
