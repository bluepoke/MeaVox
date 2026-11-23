// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.ui

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import eu.schicht8.meavox.R
import eu.schicht8.meavox.core.model.PipelineStep
import eu.schicht8.meavox.core.model.Track
import eu.schicht8.meavox.core.model.TrackPipelineState
import eu.schicht8.meavox.core.port.SeparationProgress

/** User-facing name of a Track Pipeline state. */
@Composable
fun TrackPipelineState.label(): String = label(LocalResources.current)

fun TrackPipelineState.label(resources: Resources): String = when (this) {
    TrackPipelineState.Added -> resources.getString(R.string.pipeline_added)
    is TrackPipelineState.Downloading -> progress.let { p ->
        when {
            retryReason != null -> resources.getString(R.string.pipeline_download_paused)
            p != null -> resources.getString(R.string.pipeline_downloading, percent(resources, p))
            else -> resources.getString(R.string.pipeline_waiting_to_download)
        }
    }
    TrackPipelineState.Downloaded -> resources.getString(R.string.pipeline_downloaded)
    is TrackPipelineState.Separating -> progress.let { p ->
        when (p?.stage) {
            null -> resources.getString(R.string.pipeline_waiting_to_separate)
            SeparationProgress.Stage.DownloadingModel -> resources.getString(R.string.pipeline_downloading_model, percent(resources, p.fraction))
            SeparationProgress.Stage.InstallingModel -> resources.getString(R.string.pipeline_installing_model, percent(resources, p.fraction))
            SeparationProgress.Stage.Separating -> resources.getString(R.string.pipeline_separating, percent(resources, p.fraction))
        }
    }
    is TrackPipelineState.Ready -> resources.getString(R.string.pipeline_ready)
    is TrackPipelineState.StemsDeleted -> resources.getString(R.string.pipeline_stems_deleted)
    is TrackPipelineState.Failed -> when (step) {
        PipelineStep.Downloading -> resources.getString(R.string.pipeline_download_failed)
        PipelineStep.Separating -> resources.getString(R.string.pipeline_separation_failed)
    }
}

/** [fraction] (0..1) as a whole percentage, e.g. "42 %". */
fun percent(resources: Resources, fraction: Float): String = resources.getString(R.string.percent, (fraction * 100).toInt())

/** Secondary line for a Track: artist (if known), pipeline state, and whether it is Vocal-Free. */
@Composable
fun Track.subtitle(): String {
    val resources = LocalResources.current
    return listOfNotNull(artist, pipeline.label(resources), resources.getString(R.string.track_vocal_free).takeIf { vocalFree }).joinToString(" · ")
}
