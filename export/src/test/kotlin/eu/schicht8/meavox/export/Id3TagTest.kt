// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.export

import eu.schicht8.meavox.core.port.ExportTags
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

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
    private fun parse(tag: ByteArray): Map<String, String> {
        val buffer = ByteBuffer.wrap(tag)
        assertEquals("ID3", String(tag, 0, 3, Charsets.ISO_8859_1))
        assertEquals(4, tag[3].toInt(), "major version")
        assertEquals(0, tag[4].toInt(), "revision")
        assertEquals(0, tag[5].toInt(), "flags")
        val size = (6..9).fold(0) { acc, i -> (acc shl 7) or (tag[i].toInt() and 0x7F) }
        assertEquals(tag.size - 10, size, "tag size (syncsafe) counts everything after the header")
        buffer.position(10)
        val frames = mutableMapOf<String, String>()
        while (buffer.remaining() >= 10) {
            val id = ByteArray(4).also(buffer::get).toString(Charsets.ISO_8859_1)
            val frameSize = syncsafe(buffer)
            buffer.short // flags
            if (id == "\u0000\u0000\u0000\u0000") break // padding
            val body = ByteArray(frameSize).also(buffer::get)
            frames[id] = when (body[0].toInt()) {
                0 -> String(body, 1, body.size - 1, Charsets.ISO_8859_1)
                1 -> String(body, 1, body.size - 1, Charsets.UTF_16) // with a BOM
                3 -> String(body, 1, body.size - 1, Charsets.UTF_8)
                else -> error("encoding ${body[0]} isn't ID3v2.4")
            }.trimEnd('\u0000')
        }
        return frames
    }
}
