// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.covers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import eu.schicht8.meavox.core.model.AudioFile
import eu.schicht8.meavox.core.model.CoverArt
import eu.schicht8.meavox.core.port.CoverArtLoader
import eu.schicht8.meavox.core.port.NoPicture
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * [CoverArtLoader] over HTTP and the platform's image decoders (#42). Every picture is decoded,
 * cropped to a centred square (#50: players and the Library show covers square, a video's thumbnail
 * is 16:9) and written again as a JPEG of at most [MAX_EDGE] pixels a side: an MP4 `covr` item only
 * takes JPEG or PNG, Sources also serve WebP, and an Export shouldn't carry a picture bigger than a
 * player shows.
 */
class AndroidCoverArtLoader : CoverArtLoader {
    override suspend fun download(url: String): CoverArt = runInterruptible(Dispatchers.IO) {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        val bytes = try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            when (val status = connection.responseCode) {
                200 -> Unit
                404, 410 -> throw NoPicture("no picture at $url (HTTP $status)")
                else -> throw IOException("HTTP $status for the picture")
            }
            connection.inputStream.use { it.readAtMost(MAX_DOWNLOAD_BYTES) }
        } finally {
            connection.disconnect()
        }
        toJpeg(bytes) ?: throw NoPicture("the picture at $url can't be decoded")
    }

    override suspend fun embeddedIn(audio: AudioFile): CoverArt? = runInterruptible(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(audio.path)
            retriever.embeddedPicture?.let(::toJpeg)
        } catch (_: RuntimeException) {
            null // a file the retriever can't read has no picture it could give
        } finally {
            retriever.release()
        }
    }

    override suspend fun square(cover: CoverArt): CoverArt = runInterruptible(Dispatchers.IO) {
        val bytes = cover.jpeg
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        // Already square and small enough (e.g. an MP3's front cover): not encoded once more.
        if (bounds.outWidth > 0 && bounds.outWidth == bounds.outHeight && bounds.outWidth <= MAX_EDGE) return@runInterruptible cover
        toJpeg(bytes) ?: throw NoPicture("the stored cover can't be decoded")
    }

    /** [bytes] as a square JPEG no larger than [MAX_EDGE], or null if they aren't a picture or don't fit in memory. */
    private fun toJpeg(bytes: ByteArray): CoverArt? = try {
        encodeJpeg(bytes)
    } catch (_: OutOfMemoryError) {
        null // Cover Art is best-effort; a picture too big to decode must not take the app down.
    }

    private fun encodeJpeg(bytes: ByteArray): CoverArt? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // Decoded at a power-of-two fraction first, so a huge picture never takes a huge bitmap; the
        // shorter side is what the square keeps.
        var sample = 1
        while (min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val side = min(decoded.width, decoded.height)
        val size = min(side, MAX_EDGE)
        // The centred square, scaled to [size] in one step.
        val left = (decoded.width - side) / 2
        val top = (decoded.height - side) / 2
        val crop = Rect(left, top, left + side, top + side)
        val bitmap = if (crop.left == 0 && crop.top == 0 && size == side) {
            decoded
        } else {
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { square ->
                Canvas(square).drawBitmap(
                    decoded, crop, Rect(0, 0, size, size), Paint(Paint.FILTER_BITMAP_FLAG),
                )
            }
        }
        try {
            val out = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) return null
            return CoverArt(out.toByteArray())
        } finally {
            if (bitmap !== decoded) bitmap.recycle()
            decoded.recycle()
        }
    }

    private fun java.io.InputStream.readAtMost(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1 shl 16)
        while (true) {
            if (Thread.interrupted()) throw InterruptedException("cover download cancelled")
            val n = read(buffer)
            if (n < 0) return out.toByteArray()
            out.write(buffer, 0, n)
            if (out.size() > limit) throw IOException("the picture is larger than $limit bytes")
        }
    }

    private companion object {
        const val MAX_EDGE = 1000
        const val JPEG_QUALITY = 90

        /** More than any thumbnail a Source serves. */
        const val MAX_DOWNLOAD_BYTES = 10 shl 20
    }
}
