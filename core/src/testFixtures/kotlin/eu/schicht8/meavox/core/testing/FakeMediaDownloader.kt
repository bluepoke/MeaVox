// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.testing

import eu.schicht8.meavox.core.model.AudioFile
import eu.schicht8.meavox.core.model.FailureKind
import eu.schicht8.meavox.core.model.FailureReason
import eu.schicht8.meavox.core.port.MediaDownloader
import eu.schicht8.meavox.core.port.MediaUnavailable

/** Serves in-memory content and writes it into [files]' download parts, continuing after what they hold. */
class FakeMediaDownloader(private val files: InMemoryTrackFiles) : MediaDownloader {
    private val content = mutableMapOf<String, ByteArray>()

    /** Bytes done reported in order, before the download finishes; by default only the end. */
    var progressSteps: List<Long>? = null

    /** While set, a download stops with a network error once the part holds this many bytes. */
    var failAfterBytes: Int? = null

    /** Runs when a download starts, before any bytes, e.g. to observe the Track then. */
    var beforeDownload: suspend () -> Unit = {}

    /** Where each download started: 0, or the bytes an interrupted one left. */
    val startedAt = mutableListOf<Long>()

    /** Runs after each reported step (its index), e.g. to observe the Track meanwhile. */
    var afterEachProgress: suspend (step: Int) -> Unit = {}

    fun serve(uri: String, text: String) {
        content[uri] = text.encodeToByteArray()
    }

    override suspend fun download(uri: String, into: AudioFile, onProgress: (Long, Long?) -> Unit) {
        val bytes = checkNotNull(content[uri]) { "nothing served at $uri" }
        beforeDownload()
        val done = files.partContent(into).size
        startedAt += done.toLong()
        failAfterBytes?.let { limit ->
            files.appendToPart(into, bytes.copyOfRange(done, maxOf(done, minOf(limit, bytes.size))))
            for (bytesDone in progressSteps.orEmpty()) onProgress(bytesDone, bytes.size.toLong())
            throw MediaUnavailable(FailureReason(FailureKind.ConnectionBroke), retryable = true)
        }
        files.appendToPart(into, bytes.copyOfRange(done, bytes.size))
        val total = bytes.size.toLong()
        for ((step, bytesDone) in (progressSteps ?: listOf(total)).withIndex()) {
            onProgress(bytesDone, total)
            afterEachProgress(step)
        }
    }
}
