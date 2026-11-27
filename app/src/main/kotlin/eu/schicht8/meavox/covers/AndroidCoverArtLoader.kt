// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.covers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import eu.schicht8.meavox.core.model.AudioFile
import eu.schicht8.meavox.core.model.CoverArt
import eu.schicht8.meavox.core.port.CoverArtLoader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * [CoverArtLoader] over HTTP and the platform's image decoders (#42). Every picture is decoded and
 * written again as a JPEG of at most [MAX_EDGE] pixels on its longer side: an MP4 `covr` item only
 * takes JPEG or PNG, Sources also serve WebP, and an Export shouldn't carry a picture bigger than a
 * player shows.
 */
class AndroidCoverArtLoader : CoverArtLoader {
    override suspend fun download(url: String): CoverArt = runInterruptible(Dispatchers.IO) {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        val bytes = try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            val status = connection.responseCode
            if (status != 200) throw IOException("HTTP $status for the picture")
            connection.inputStream.use { it.readAtMost(MAX_DOWNLOAD_BYTES) }
        } finally {
            connection.disconnect()
        }
        toJpeg(bytes) ?: throw IOException("the picture at $url can't be decoded")
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

    /** [bytes] as a JPEG no larger than [MAX_EDGE], or null if they aren't a picture or don't fit in memory. */
    private fun toJpeg(bytes: ByteArray): CoverArt? = try {
        encodeJpeg(bytes)
    } catch (_: OutOfMemoryError) {
        null // Cover Art is best-effort; a picture too big to decode must not take the app down.
    }

    private fun encodeJpeg(bytes: ByteArray): CoverArt? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // Decoded at a power-of-two fraction first, so a huge picture never takes a huge bitmap.
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val scale = MAX_EDGE.toFloat() / max(decoded.width, decoded.height)
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).roundToInt(), (decoded.height * scale).roundToInt(), true)
        } else {
            decoded
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
