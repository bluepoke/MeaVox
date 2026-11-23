// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.port

import eu.schicht8.meavox.core.model.AppDirectory
import eu.schicht8.meavox.core.model.AudioFile
import eu.schicht8.meavox.core.model.FailureReason
import eu.schicht8.meavox.core.model.SeparationModelId

/** On-device Separation of Original Audio into Instrumental and Vocal Stem (ADR 2, ADR 3). */
interface Separator {
    /**
     * Separates [originalAudio] with [model] and writes the result to [into]. The stems are
     * sample-aligned with, and as long as, the input. [onProgress] may be called on any thread.
     *
     * Work done so far is kept in [checkpoint], so a call for the same audio and model after
     * cancellation or process death continues where the previous one stopped (ADR 15). The
     * directory is removed once the stems are stored.
     */
    suspend fun separate(
        originalAudio: AudioFile,
        model: SeparationModelId,
        into: Stems,
        checkpoint: AppDirectory,
        onProgress: (SeparationProgress) -> Unit = {},
    )
}

/** How far a Separation is: which [stage] it is in, and how much of that stage is done (0..1). */
data class SeparationProgress(val stage: Stage, val fraction: Float) {
    enum class Stage {
        /** The Separation Model's files are being downloaded (ADR 15). */
        DownloadingModel,

        /** The downloaded model is being verified and unpacked. */
        InstallingModel,

        /** The model runs over the Original Audio. */
        Separating,
    }
}

/**
 * Separation didn't finish. [retryable] failures (e.g. no network for the model download) may
 * succeed if the same Separation runs again later; others need the user to act.
 */
class SeparationFailed(val reason: FailureReason, val retryable: Boolean, cause: Throwable? = null) : Exception(reason.toString(), cause)

/** Where the Instrumental and Vocal Stem of one Separation are stored. */
data class Stems(val instrumental: AudioFile, val vocalStem: AudioFile)
