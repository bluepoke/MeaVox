// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.covers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import eu.schicht8.meavox.core.library.TrackFiles
import eu.schicht8.meavox.core.model.TrackId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Cover Art the Library's rows show (#49), decoded off the main thread and scaled to the size a
 * row shows it, and kept while there is room, by Track and [TrackFiles.coverVersion], so a new cover
 * is decoded anew.
 */
class LibraryCovers(private val files: TrackFiles) {
    // About 100 rows of 56 dp covers at a high density (a 168 px square is ~110 KB).
    private val cache = object : LruCache<String, ImageBitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    /** [track]'s cover [version] as decoded before, or null if it isn't decoded yet. */
    fun cached(track: TrackId, version: Long): ImageBitmap? = cache.get(key(track, version))

    /** [track]'s cover [version] at about [sizePx] square, or null if it has none or can't be decoded. */
    suspend fun load(track: TrackId, version: Long, sizePx: Int): ImageBitmap? {
        cached(track, version)?.let { return it }
        val jpeg = files.cover(track)?.jpeg ?: return null
        val bitmap = withContext(Dispatchers.Default) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
            var sample = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= sizePx) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return@withContext null
            // Exactly the row's size, so the cache holds as many rows as it says.
            val scaled = if (decoded.width > sizePx) Bitmap.createScaledBitmap(decoded, sizePx, sizePx, true) else decoded
            if (scaled !== decoded) decoded.recycle()
            scaled.asImageBitmap()
        } ?: return null
        cache.put(key(track, version), bitmap)
        return bitmap
    }

    private fun key(track: TrackId, version: Long) = "${track.key}#$version"

    private companion object {
        const val CACHE_BYTES = 12 shl 20
    }
}
