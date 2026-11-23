// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.pipeline

import eu.schicht8.meavox.core.library.TrackFiles
import eu.schicht8.meavox.core.library.TrackRepository
import eu.schicht8.meavox.core.model.FailureKind
import eu.schicht8.meavox.core.model.FailureReason
import eu.schicht8.meavox.core.model.PipelineStep
import eu.schicht8.meavox.core.model.SeparationModelId
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.model.TrackPipelineState
import eu.schicht8.meavox.core.port.SeparationFailed
import eu.schicht8.meavox.core.port.SeparationProgress
import eu.schicht8.meavox.core.port.Separator
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The Separating step of the Track Pipeline (ADR 10, ADR 15). One instance serves every job of the
 * process: it runs one Separation per Track at a time.
 */
class SeparateTrack(
    private val tracks: TrackRepository,
    private val files: TrackFiles,
    private val separator: Separator,
) {
    /** Held by the Separation of each Track while it runs, whatever its model. */
    private val running = ConcurrentHashMap<TrackId, Mutex>()

    enum class Outcome {
        /** The stems are stored and the Track is Ready. */
        Ready,

        /** The Track is Failed; running the step again won't help until the user acts. */
        Failed,

        /** The Track stays Separating; run the step again later, it continues from its checkpoint. */
        RetryLater,

        /** The Track doesn't wait for this Separation (any more); nothing was done. */
        Skipped,
    }

    /**
     * Separates the Track with [model] if it is waiting for that: Downloaded, or Separating with
     * [model]. A job that runs after the Track moved on (e.g. one queued behind a job that
     * already finished it) changes nothing, and neither does one whose Track moves on while it
     * runs (e.g. marked Vocal-Free before the job stopped, ADR 14): every state it writes is
     * written only while the Track still waits for this Separation.
     *
     * It starts only once any other Separation of the Track has stopped (#28): a job replaced by a
     * model switch may take a while to stop after it is cancelled, and two Separations at once
     * could run out of memory, or write the same checkpoint if both have the same model (a job
     * of an earlier version and its replacement).
     */
    suspend operator fun invoke(id: TrackId, model: SeparationModelId): Outcome =
        running.computeIfAbsent(id) { Mutex() }.withLock { separate(id, model) }

    private suspend fun separate(id: TrackId, model: SeparationModelId): Outcome = coroutineScope {
        when (val state = tracks.get(id)?.pipeline) {
            TrackPipelineState.Ready(model) -> return@coroutineScope Outcome.Ready
            is TrackPipelineState.Failed -> return@coroutineScope Outcome.Failed
            TrackPipelineState.Downloaded -> Unit
            is TrackPipelineState.Separating -> if (state.model != model) return@coroutineScope Outcome.Skipped
            else -> return@coroutineScope Outcome.Skipped
        }
        if (!writeWhileWaiting(id, model) { previous -> TrackPipelineState.Separating(model, previous = previous) }) return@coroutineScope Outcome.Skipped
        // The Separator reports from any thread, sometimes many times a second (e.g. per block of a
        // download). Only the latest progress in whole percent is stored: the flow drops repeats,
        // so each stage writes the Track (and updates the notification) at most about 100 times.
        val progress = MutableStateFlow<SeparationProgress?>(null)
        val storing = launch {
            progress.filterNotNull().collect { reported -> writeWhileWaiting(id, model) { previous -> TrackPipelineState.Separating(model, reported, previous) } }
        }
        val failure = try {
            separator.separate(
                originalAudio = files.originalAudio(id),
                model = model,
                into = files.stems(id, model),
                checkpoint = files.separationCheckpoint(id, model),
                onProgress = { progress.value = it.inWholePercent() },
            )
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Errors too: a Separation that runs out of memory must end Failed, not stay
            // Separating and be queued again on every start.
            e
        } finally {
            storing.cancelAndJoin()
        }
        val outcome = when {
            failure == null -> Outcome.Ready
            failure is SeparationFailed && failure.retryable -> Outcome.RetryLater
            else -> Outcome.Failed
        }
        val written = writeWhileWaiting(id, model) { previous ->
            when (outcome) {
                Outcome.Ready -> TrackPipelineState.Ready(model)
                // Keep showing how far it got; the next run continues from the checkpoint.
                Outcome.RetryLater -> TrackPipelineState.Separating(model, progress.value, previous)
                // A failed re-separation leaves the previous Instrumental playing (ADR 22).
                else -> TrackPipelineState.Failed(PipelineStep.Separating, checkNotNull(failure).toReason(), previous)
            }
        }
        if (written) outcome else Outcome.Skipped
    }

    /**
     * Stores the [state] made from the model whose Instrumental plays meanwhile (see
     * [TrackPipelineState.Separating.previous]) if the Track still waits for Separation with [model]
     * (Downloaded, or Separating with it, and not Vocal-Free); returns whether it did.
     */
    private suspend fun writeWhileWaiting(
        id: TrackId,
        model: SeparationModelId,
        state: (previous: SeparationModelId?) -> TrackPipelineState,
    ): Boolean {
        var written = false
        tracks.update(id) { track ->
            val pipeline = track.pipeline
            val waiting = pipeline == TrackPipelineState.Downloaded || (pipeline is TrackPipelineState.Separating && pipeline.model == model)
            written = waiting && !track.vocalFree
            if (written) track.copy(pipeline = state((pipeline as? TrackPipelineState.Separating)?.previous)) else null
        }
        return written
    }
}

private fun Throwable.toReason(): FailureReason = when (this) {
    is OutOfMemoryError -> FailureReason(FailureKind.OutOfMemory)
    is SeparationFailed -> reason
    else -> FailureReason.unexpected(this)
}

private fun SeparationProgress.inWholePercent() = copy(fraction = floor(fraction * 100) / 100)
