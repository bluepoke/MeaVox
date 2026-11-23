// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.downloading

import eu.schicht8.meavox.core.model.AudioFile
import eu.schicht8.meavox.core.model.FailureKind
import eu.schicht8.meavox.core.model.FailureReason
import eu.schicht8.meavox.core.port.MediaDownloader
import eu.schicht8.meavox.core.port.MediaUnavailable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * [MediaDownloader] over HTTP byte ranges: the file is fetched in blocks appended to the part file,
 * so an interrupted download continues after the bytes it has. Blocks also keep YouTube from
 * throttling one long request.
 */
class HttpMediaDownloader : MediaDownloader {
    override suspend fun download(uri: String, into: AudioFile, onProgress: (Long, Long?) -> Unit): Unit = runInterruptible(Dispatchers.IO) {
        val file = File(into.path)
        file.parentFile?.mkdirs()
        var done = file.length()
        var total: Long? = null
        try {
            while (total == null || done < total) {
                val connection = URI(uri).toURL().openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 20_000
                    connection.readTimeout = 30_000
                    connection.setRequestProperty("Range", "bytes=$done-${done + BLOCK - 1}")
                    when (val status = connection.responseCode) {
                        206 -> {
                            val range = connection.getHeaderField("Content-Range") // "bytes 1048576-2097151/3843202"
                            total = range?.substringAfter('/')?.toLongOrNull()
                            val start = range?.substringAfter("bytes ")?.substringBefore('-')?.toLongOrNull()
                            if (start != done) {
                                // Not the bytes asked for: the part can't be trusted; start over.
                                file.writeBytes(ByteArray(0))
                                done = 0
                                continue
                            }
                        }
                        200 -> {
                            // The server ignores ranges: the body is the whole file, from the start.
                            done = 0
                            total = connection.contentLengthLong.takeIf { it > 0 }
                            file.writeBytes(ByteArray(0))
                        }
                        416 -> {
                            // Asked past the end: complete if the part is exactly the file, else start over.
                            val size = connection.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()
                            if (size != null && size == done && done > 0) return@runInterruptible
                            if (done == 0L) throw MediaUnavailable(FailureReason(FailureKind.NoData), retryable = false)
                            file.writeBytes(ByteArray(0))
                            done = 0
                            continue
                        }
                        // Stream links expire after some hours; resolving the song again gives a new one.
                        403, 410 -> throw MediaUnavailable(FailureReason(FailureKind.LinkExpired), retryable = true)
                        else -> {
                            val busy = status == 429 || status >= 500
                            throw MediaUnavailable(FailureReason(if (busy) FailureKind.ServerBusy else FailureKind.ServerError, "$status"), retryable = busy)
                        }
                    }
                    val before = done
                    FileOutputStream(file, true).use { out ->
                        connection.inputStream.use { input ->
                            val buffer = ByteArray(1 shl 16)
                            while (true) {
                                if (Thread.interrupted()) throw InterruptedException("download cancelled")
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                done += n
                                onProgress(done, total)
                            }
                        }
                    }
                    if (connection.responseCode == 200 || done == before) break // whole file, or nothing more
                } finally {
                    connection.disconnect()
                }
            }
        } catch (e: IOException) {
            throw MediaUnavailable(FailureReason(FailureKind.ConnectionBroke, e.message), retryable = true, e)
        }
        val expected = total
        if (expected != null && done < expected) throw MediaUnavailable(FailureReason(FailureKind.DownloadIncomplete), retryable = true)
    }

    private companion object {
        const val BLOCK = 1L shl 20
    }
}
