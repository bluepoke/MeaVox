// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.export

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WavEncoderTest {
    private fun encode(bits: Int, frames: Long, vararg blocks: FloatArray): ByteArray {
        val out = ByteArrayOutputStream()
        val encoder = WavEncoder(out, sampleRate = 44_100, channels = 2, bitsPerSample = bits, frames = frames)
        blocks.forEach { encoder.write(it, it.size / 2) }
        encoder.finish()
        return out.toByteArray()
    }

    private fun ByteArray.le(): ByteBuffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)

    private fun ByteArray.ascii(at: Int) = String(copyOfRange(at, at + 4), Charsets.US_ASCII)

    @Test
    fun `a 16-bit WAV has a canonical PCM header for its length`() {
        val bytes = encode(16, 3, floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f))
        val le = bytes.le()

        assertEquals(44 + 12, bytes.size)
        assertEquals("RIFF", bytes.ascii(0))
        assertEquals(36 + 12, le.getInt(4))
        assertEquals("WAVE", bytes.ascii(8))
        assertEquals("fmt ", bytes.ascii(12))
        assertEquals(16, le.getInt(16)) // fmt chunk size
        assertEquals(1, le.getShort(20).toInt()) // PCM
        assertEquals(2, le.getShort(22).toInt()) // channels
        assertEquals(44_100, le.getInt(24))
        assertEquals(44_100 * 4, le.getInt(28)) // bytes per second
        assertEquals(4, le.getShort(32).toInt()) // block align
        assertEquals(16, le.getShort(34).toInt())
        assertEquals("data", bytes.ascii(36))
        assertEquals(12, le.getInt(40))
    }

    @Test
    fun `16-bit samples are rounded and clipped to full scale`() {
        val bytes = encode(16, 2, floatArrayOf(0.5f, -0.5f), floatArrayOf(1.5f, -1f))
        val le = bytes.le()

        assertEquals(listOf(16_384, -16_384, 32_767, -32_767), (0 until 4).map { le.getShort(44 + 2 * it).toInt() })
    }

    @Test
    fun `24-bit samples are three little-endian bytes each`() {
        val bytes = encode(24, 1, floatArrayOf(1f, -0.25f))
        val le = bytes.le()

        assertEquals(24, le.getShort(34).toInt())
        assertEquals(6, le.getShort(32).toInt())
        assertEquals(44_100 * 6, le.getInt(28))
        assertEquals(6, le.getInt(40))
        // 8 388 607 = 0x7FFFFF; −0.25 × 8 388 607 ≈ −2 097 152 = 0xE00000.
        assertContentEquals(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x7F, 0x00, 0x00, 0xE0.toByte()), bytes.copyOfRange(44, 50))
    }

    @Test
    fun `finishing with fewer frames than announced fails, so a short file is never kept`() {
        val encoder = WavEncoder(ByteArrayOutputStream(), sampleRate = 44_100, channels = 2, bitsPerSample = 16, frames = 10)
        encoder.write(FloatArray(8), 4)

        assertFailsWith<IOException> { encoder.finish() }
    }

    @Test
    fun `title and artist go into an INFO list before the audio`() {
        val out = ByteArrayOutputStream()
        val encoder = WavEncoder(out, 44_100, channels = 2, bitsPerSample = 16, frames = 1, tags = WavTags(title = "Song (Instrumental, +2)", artist = "Ab"))
        encoder.write(floatArrayOf(0f, 0f), 1)
        encoder.finish()
        val bytes = out.toByteArray()
        val le = bytes.le()

        // "Song (Instrumental, +2)" is 23 bytes, 24 with its NUL; "Ab" is 3 with its NUL, padded to 4.
        assertEquals("LIST", bytes.ascii(36))
        assertEquals(4 + 8 + 24 + 8 + 4, le.getInt(40))
        assertEquals("INFO", bytes.ascii(44))
        assertEquals("INAM", bytes.ascii(48))
        assertEquals(24, le.getInt(52))
        assertEquals("Song (Instrumental, +2)", String(bytes.copyOfRange(56, 79), Charsets.UTF_8))
        assertEquals(0, bytes[79].toInt())
        assertEquals("IART", bytes.ascii(80))
        assertEquals(3, le.getInt(84))
        assertEquals("Ab", String(bytes.copyOfRange(88, 90), Charsets.UTF_8))
        assertEquals("data", bytes.ascii(92))
        assertEquals(4, le.getInt(96))
        assertEquals(bytes.size - 8, le.getInt(4))
        assertEquals(100 + 4, bytes.size)
    }

    @Test
    fun `a WAV of unknown length gets its real header once it is finished`() {
        val out = ByteArrayOutputStream()
        val encoder = WavEncoder(out, 44_100, channels = 2, bitsPerSample = 16, frames = null)
        encoder.write(FloatArray(6), 3)
        encoder.finish()

        val header = encoder.header()

        assertEquals(44, header.size)
        assertEquals(36 + 12, header.le().getInt(4))
        assertEquals(12, header.le().getInt(40))
        // What was written first has the same size, so the real header goes over it.
        assertEquals(44 + 12, out.size())
    }
}
