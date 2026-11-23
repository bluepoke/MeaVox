// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.port

import eu.schicht8.meavox.core.model.AudioFile
import eu.schicht8.meavox.core.model.FailureReason

/** Downloads a Source's media stream into app storage (ADR 10). Implemented over HTTP; faked in tests. */
interface MediaDownloader {
    /**
     * Downloads [uri] into [into]. If [into] already holds the first bytes (an interrupted
     * download), continues after them. [onProgress] receives the bytes done and the total, if known.
     */
    suspend fun download(uri: String, into: AudioFile, onProgress: (done: Long, total: Long?) -> Unit = { _, _ -> })
}

/**
 * A Source's media can't be resolved or downloaded. [retryable] failures (no network, server
 * busy, an expired stream URL) may work if the step runs again later; others (removed video,
 * region lock) won't.
 */
class MediaUnavailable(val reason: FailureReason, val retryable: Boolean, cause: Throwable? = null) : Exception(reason.toString(), cause)
