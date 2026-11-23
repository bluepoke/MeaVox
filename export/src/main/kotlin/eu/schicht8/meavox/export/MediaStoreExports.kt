// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.export

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import eu.schicht8.meavox.core.port.ExportFormat
import eu.schicht8.meavox.core.port.ExportStore
import eu.schicht8.meavox.core.port.ExportTags
import eu.schicht8.meavox.core.port.ExportWriter
import eu.schicht8.meavox.core.port.ExportedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * [ExportStore] in MediaStore's `Music/MeaVox/` (ADR 8). A file is pending, so other apps don't
 * see it, until it is complete. Needs no permission from Android 10 (the app's minimum).
 */
class MediaStoreExports(private val resolver: ContentResolver) : ExportStore {
    override suspend fun create(tags: ExportTags, format: ExportFormat, sampleRate: Int, frames: Long?): ExportWriter =
        // Not cancelled half-way: a row inserted but not handed back would stay pending behind the user.
        withContext(Dispatchers.IO + NonCancellable) {
            val fileType = FileType.of(format)
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "${fileName(tags.title)}.${fileType.extension}")
                put(MediaStore.Audio.Media.TITLE, tags.title)
                tags.artist?.let { put(MediaStore.Audio.Media.ARTIST, it) }
                put(MediaStore.Audio.Media.MIME_TYPE, fileType.mimeType)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/MeaVox")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: throw IOException("could not create an Export in Music/MeaVox")
            // "rw": headers and tags are written over once the length is known.
            var descriptor: ParcelFileDescriptor? = null
            try {
                descriptor = resolver.openFileDescriptor(uri, "rw") ?: throw IOException("could not open $uri")
                val file = FileOutputStream(descriptor.fileDescriptor)
                val encoder = when (format) {
                    is ExportFormat.Wav -> WavFileEncoder(file, format.bitsPerSample, sampleRate, frames, tags)
                    is ExportFormat.Mp3 -> Mp3Encoder(file.channel, sampleRate, format.bitrate, tags)
                    ExportFormat.M4a -> AacEncoder(file.channel, sampleRate, tags)
                }
                Writer(uri, descriptor, encoder, fileType.mimeType)
            } catch (e: Exception) {
                runCatching { descriptor?.close() }
                resolver.delete(uri, null, null)
                throw e
            }
        }

    private inner class Writer(
        private val uri: Uri,
        private val descriptor: ParcelFileDescriptor,
        private val encoder: PcmEncoder,
        private val mimeType: String,
    ) : ExportWriter {
        override fun write(interleaved: FloatArray, frames: Int) = encoder.write(interleaved, frames)

        private fun close() {
            runCatching { encoder.release() }
            runCatching { descriptor.close() }
        }

        override suspend fun commit(): ExportedFile = withContext(Dispatchers.IO) {
            encoder.finish()
            close()
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            // MediaStore may have renamed it, e.g. "Song (Mix 1) (1).mp3" next to an earlier Export.
            val name = resolver.query(uri, arrayOf(MediaStore.Audio.Media.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            ExportedFile(uri.toString(), name ?: uri.lastPathSegment.orEmpty(), mimeType)
        }

        override suspend fun abort() {
            withContext(Dispatchers.IO) {
                close()
                resolver.delete(uri, null, null)
            }
        }
    }

    /**
     * The file name extension and MIME type of a format: the type Android's MIME map gives the
     * extension, so MediaStore keeps the file name as it is.
     */
    private enum class FileType(val extension: String, val mimeType: String) {
        Wav("wav", "audio/x-wav"),
        Mp3("mp3", "audio/mpeg"),
        M4a("m4a", "audio/mp4"),
        ;

        companion object {
            fun of(format: ExportFormat): FileType = when (format) {
                is ExportFormat.Wav -> Wav
                is ExportFormat.Mp3 -> Mp3
                ExportFormat.M4a -> M4a
            }
        }
    }

    private companion object {
        /** [title] without characters file systems reject. */
        fun fileName(title: String): String =
            title.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_").trim().take(120).ifEmpty { "MeaVox" }
    }
}

/** [WavEncoder] on an Export's file: buffered, with the header written over at the end when the length wasn't known. */
private class WavFileEncoder(
    private val file: FileOutputStream,
    bitsPerSample: Int,
    sampleRate: Int,
    frames: Long?,
    tags: ExportTags,
) : PcmEncoder {
    private val stream = file.buffered(BUFFER_BYTES)
    private val encoder = WavEncoder(stream, sampleRate, channels = 2, bitsPerSample = bitsPerSample, frames = frames, tags = WavTags(tags.title, tags.artist))
    private val patchHeader = frames == null

    override fun write(interleaved: FloatArray, frames: Int) = encoder.write(interleaved, frames)

    override fun finish() {
        encoder.finish()
        if (patchHeader) file.channel.write(ByteBuffer.wrap(encoder.header()), 0)
    }

    private companion object {
        const val BUFFER_BYTES = 256 * 1024
    }
}
