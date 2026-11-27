// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.export

import eu.schicht8.meavox.core.model.CoverArt
import eu.schicht8.meavox.core.port.ExportTags
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.io.path.createTempFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The container writers of #15: the MP4 file an M4A Export's AAC frames go into, read back by a minimal box parser. */
class M4aWriterTest {
    private val file: File = createTempFile("export", ".m4a").toFile()

    @AfterTest
    fun cleanUp() {
        file.delete()
    }

    // Three AAC frames of 1 024 samples each, and the AudioSpecificConfig of AAC-LC, 48 kHz, stereo.
    private val samples = listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6, 7, 8), byteArrayOf(9))
    private val config = byteArrayOf(0x11, 0x90.toByte())

    private fun write(tags: ExportTags = ExportTags("Fly Me (Mix 1)", "Frank Sinatra feat. Peter")): Box {
        RandomAccessFile(file, "rw").use { raf ->
            val writer = M4aWriter(raf.channel, sampleRate = 48_000, channels = 2, tags = tags)
            samples.forEach { writer.writeSample(ByteBuffer.wrap(it)) }
            writer.finish(config)
        }
        return Box("file", ByteBuffer.wrap(file.readBytes()), 0)
    }

    @Test
    fun `the file is an M4A of the AAC frames as they were written`() {
        val root = write()

        assertEquals(listOf("ftyp", "mdat", "moov"), root.children().map { it.type })
        assertEquals("M4A ", root.child("ftyp").body.ascii(4))
        val stbl = root.path("moov", "trak", "mdia", "minf", "stbl")
        val sizes = stbl.child("stsz").body.apply { position(8) }.let { b -> List(b.int) { b.int } }
        assertEquals(listOf(3, 5, 1), sizes)
        // One chunk holding every frame, where they start in the file.
        val stco = stbl.child("stco").body.apply { position(4) }
        assertEquals(1, stco.int)
        val offset = stco.int
        assertContentEquals(samples.reduce(ByteArray::plus), file.readBytes().copyOfRange(offset, offset + 9))
        assertEquals(listOf(1, 3, 1), stbl.child("stsc").body.apply { position(4) }.let { b -> List(b.int * 3) { b.int } })
    }

    @Test
    fun `its length is 1 024 samples per frame at the sample rate`() {
        val root = write()

        val stts = root.path("moov", "trak", "mdia", "minf", "stbl", "stts").body.apply { position(4) }
        assertEquals(listOf(1, 3, 1024), listOf(stts.int, stts.int, stts.int))
        val mdhd = root.path("moov", "trak", "mdia", "mdhd").body.apply { position(12) }
        assertEquals(48_000 to 3_072, mdhd.int to mdhd.int)
        val mvhd = root.path("moov", "mvhd").body.apply { position(12) }
        assertEquals(48_000 to 3_072, mvhd.int to mvhd.int)
        assertEquals(3_072, root.path("moov", "trak", "tkhd").body.apply { position(20) }.int)
    }

    @Test
    fun `the sample entry describes stereo AAC at the sample rate, with the decoder config`() {
        val mp4a = write().path("moov", "trak", "mdia", "minf", "stbl", "stsd", "mp4a")

        val fields = mp4a.body.apply { position(16) }
        assertEquals(2, fields.short.toInt(), "channels")
        assertEquals(16, fields.short.toInt(), "sample size")
        fields.position(24)
        assertEquals(48_000, fields.int ushr 16, "sample rate (16.16)")
        val esds = mp4a.child("esds").body.duplicate().apply { position(4) }
        val es = descriptor(esds, tag = 0x03)
        es.position(3) // ES ID, flags
        val decoderConfig = descriptor(es, tag = 0x04)
        assertEquals(0x40, decoderConfig.get().toInt(), "MPEG-4 audio")
        assertEquals(0x15, decoderConfig.get().toInt(), "audio stream")
        decoderConfig.position(13)
        val specific = descriptor(decoderConfig, tag = 0x05)
        assertContentEquals(config, ByteArray(specific.remaining()).also(specific::get))
    }

    /** The body of the descriptor (ISO/IEC 14496-1) at [b]'s position, which must have [tag]; [b] moves past it. */
    private fun descriptor(b: ByteBuffer, tag: Int): ByteBuffer {
        assertEquals(tag, b.get().toInt() and 0xFF, "descriptor tag")
        var size = 0
        do {
            val next = b.get().toInt() and 0xFF
            size = (size shl 7) or (next and 0x7F)
        } while (next and 0x80 != 0)
        return b.slice().apply { limit(size) }.also { b.position(b.position() + size) }
    }

    @Test
    fun `the title and the artist are iTunes metadata`() {
        val meta = write(ExportTags("Für Elise – 夜に駆ける", "Björk")).path("moov", "udta", "meta")

        assertEquals("mdir", meta.child("hdlr").body.apply { position(8) }.ascii(4))
        val ilst = meta.child("ilst")
        assertEquals("Für Elise – 夜に駆ける", ilst.child("©nam").text())
        assertEquals("Björk", ilst.child("©ART").text())
    }

    @Test
    fun `without an artist there is no artist item`() {
        val ilst = write(ExportTags("Song")).path("moov", "udta", "meta", "ilst")

        assertEquals("Song", ilst.child("©nam").text())
        assertNull(ilst.children().find { it.type == "©ART" })
    }

    @Test
    fun `the cover is a covr item holding the JPEG`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        val ilst = write(ExportTags("Song", "Artist", CoverArt(jpeg))).path("moov", "udta", "meta", "ilst")

        val data = ilst.path("covr", "data").body.duplicate()
        assertEquals(13, data.int, "JPEG data")
        assertEquals(0, data.int, "locale")
        assertContentEquals(jpeg, ByteArray(data.remaining()).also(data::get))
        assertEquals("Song", ilst.child("©nam").text(), "the text items stay")
    }

    @Test
    fun `without a cover there is no covr item`() {
        val ilst = write(ExportTags("Song", "Artist")).path("moov", "udta", "meta", "ilst")

        assertNull(ilst.children().find { it.type == "covr" })
    }

    @Test
    fun `an Export with no frames is an empty but well-formed file`() {
        RandomAccessFile(file, "rw").use { raf -> M4aWriter(raf.channel, 44_100, 2, ExportTags("Silence")).finish(config) }
        val root = Box("file", ByteBuffer.wrap(file.readBytes()), 0)

        assertEquals(listOf("ftyp", "mdat", "moov"), root.children().map { it.type })
        assertEquals(0, root.path("moov", "mvhd").body.apply { position(16) }.int)
    }

    /** One MP4 box: [type] and its [body] after the header, with [skip] bytes of fields before any children. */
    private class Box(val type: String, val body: ByteBuffer, private val skip: Int) {
        fun children(): List<Box> {
            val b = body.duplicate().apply { position(skip) }
            val boxes = mutableListOf<Box>()
            while (b.remaining() >= 8) {
                val size = b.int
                val type = ByteArray(4).also(b::get).toString(Charsets.ISO_8859_1)
                check(size >= 8 && size - 8 <= b.remaining()) { "box $type of $size bytes doesn't fit" }
                val content = b.slice().apply { limit(size - 8) }
                boxes += Box(type, content, CHILDREN_AFTER[type] ?: 0)
                b.position(b.position() + size - 8)
            }
            check(!b.hasRemaining()) { "${b.remaining()} stray bytes in $type" }
            return boxes
        }

        fun child(type: String): Box = children().singleOrNull { it.type == type } ?: error("no single $type in ${this.type}")

        fun path(vararg types: String): Box = types.fold(this) { box, t -> box.child(t) }

        /** The UTF-8 text of an iTunes item's data box. */
        fun text(): String {
            val data = child("data").body.duplicate()
            assertEquals(1, data.int, "UTF-8 data")
            data.int // locale
            return ByteArray(data.remaining()).also(data::get).toString(Charsets.UTF_8)
        }
    }

    private fun ByteBuffer.ascii(n: Int): String = ByteArray(n).also { duplicate().get(it) }.toString(Charsets.ISO_8859_1)

    private companion object {
        /** Bytes of fields before the children of the boxes that have both; boxes not listed hold either. */
        val CHILDREN_AFTER = mapOf(
            "file" to 0, "moov" to 0, "trak" to 0, "mdia" to 0, "minf" to 0, "stbl" to 0, "udta" to 0, "ilst" to 0,
            "meta" to 4, "stsd" to 8, "mp4a" to 28, "©nam" to 0, "©ART" to 0, "covr" to 0,
        )
    }
}
