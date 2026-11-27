// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.pipeline

import eu.schicht8.meavox.core.library.TrackFiles
import eu.schicht8.meavox.core.library.TrackRepository
import eu.schicht8.meavox.core.model.CoverChoice
import eu.schicht8.meavox.core.model.FailureKind
import eu.schicht8.meavox.core.model.FailureReason
import eu.schicht8.meavox.core.model.PipelineStep
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.model.TrackPipelineState
import eu.schicht8.meavox.core.port.CoverArtLoader
import eu.schicht8.meavox.core.port.MediaDownloader
import eu.schicht8.meavox.core.port.MediaUnavailable
import eu.schicht8.meavox.core.port.Source
import kotlin.math.floor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * The Downloading step of the Track Pipeline: a Source's audio becomes the Original Audio (ADR 10).
 * Failures that may pass (no network, an expired link) are retried; after [maxFailedAttempts] of
 * them the Track ends Failed, keeping the bytes so far for a manual retry. Only failed tries count,
 * not runs cut short by process death. Once the Track is Downloaded, the Source's picture becomes its
 * Cover Art (#42); without it the Track is Downloaded all the same.
 */
class DownloadTrack(
    private val tracks: TrackRepository,
    private val files: TrackFiles,
    private val sources: List<Source>,
    private val downloader: MediaDownloader,
    private val covers: CoverArtLoader,
    private val maxFailedAttempts: Int = 5,
) {
    enum class Outcome {
        /** The Track is Downloaded or Failed (or was already). */
        Done,

        /** The Track stays Downloading; run the step again later, it continues after the bytes it has. */
        RetryLater,
    }

    /** Downloads the Track unless it is past this step, so running it again is harmless. */
    suspend operator fun invoke(id: TrackId): Outcome = coroutineScope {
        val before = when (val state = tracks.get(id)?.pipeline) {
            TrackPipelineState.Added -> TrackPipelineState.Downloading()
            is TrackPipelineState.Downloading -> state
            else -> return@coroutineScope Outcome.Done
        }
        // Keeps showing how far an earlier try got; the reason it stopped no longer applies.
        tracks.updatePipeline(id, before.copy(retryReason = null))
        // Reported per block of the download; only the latest progress in whole percent is stored.
        val progress = MutableStateFlow<Float?>(null)
        val storing = launch {
            progress.filterNotNull().collect { tracks.updatePipeline(id, before.copy(progress = it, retryReason = null)) }
        }
        val failure = try {
            val source = sources.firstOrNull { it.id == id.source }
                ?: throw MediaUnavailable(FailureReason(FailureKind.SourceNotInApp, id.source.value), retryable = false)
            val media = source.resolve(id.sourceTrackId)
            val part = files.downloadPart(id, media.audioKey)
            // A part of another stream (the Source changed what it offers) can't be continued.
            files.discardOtherDownloads(id, keep = part)
            downloader.download(media.audioUri, part) { done, total ->
                if (total != null && total > 0) progress.value = floor(done * 100f / total) / 100f
            }
            files.commitDownload(id, part)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e
        } finally {
            storing.cancelAndJoin()
        }
        val reason = failure?.let { if (it is MediaUnavailable) it.reason else FailureReason.unexpected(it) }
        val failedAttempts = before.failedAttempts + 1
        when {
            failure == null -> {
                tracks.updatePipeline(id, TrackPipelineState.Downloaded)
                // After the audio is safe: a run cut short now only loses the picture.
                downloadCover(id)
                Outcome.Done
            }
            failure is MediaUnavailable && failure.retryable && failedAttempts < maxFailedAttempts -> {
                val waiting = TrackPipelineState.Downloading(progress.value ?: before.progress, reason, failedAttempts)
                tracks.updatePipeline(id, waiting)
                Outcome.RetryLater
            }
            else -> {
                tracks.updatePipeline(id, TrackPipelineState.Failed(PipelineStep.Downloading, checkNotNull(reason)))
                Outcome.Done
            }
        }
    }

    /** Stores the Source's picture as the Track's Cover Art, if it has one that can be downloaded. */
    private suspend fun downloadCover(id: TrackId) {
        val track = tracks.get(id) ?: return
        if (track.coverChoice != CoverChoice.Automatic) return // picked or removed by the user (#51)
        val url = track.coverUrl ?: return
        try {
            val cover = covers.download(url)
            // Picked or removed while it downloaded (#51): the user's choice wins.
            if (tracks.get(id)?.coverChoice == CoverChoice.Automatic) files.storeCover(id, cover)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // An Export without a cover beats a Track that can't be sung.
        }
    }
}
