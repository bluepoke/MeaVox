// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.separating

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import eu.schicht8.meavox.MeaVoxApp
import eu.schicht8.meavox.R
import eu.schicht8.meavox.core.model.SeparationModelId
import eu.schicht8.meavox.core.model.SourceId
import eu.schicht8.meavox.core.model.Track
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.model.TrackPipelineState
import eu.schicht8.meavox.core.pipeline.SeparateTrack
import eu.schicht8.meavox.core.port.SeparationProgress
import eu.schicht8.meavox.ui.percent
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Runs the Separating step of one Track as a foreground job with a progress notification
 * (ADR 10, ADR 15). Process death or a stop by the system re-runs it, and it continues from its
 * checkpoint.
 */
class SeparationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val container = (context.applicationContext as MeaVoxApp).container

    override suspend fun doWork(): Result = coroutineScope {
        val id = TrackId(
            SourceId(inputData.getString(SOURCE) ?: return@coroutineScope Result.failure()),
            inputData.getString(SOURCE_TRACK_ID) ?: return@coroutineScope Result.failure(),
        )
        val model = SeparationModelId(inputData.getString(MODEL) ?: return@coroutineScope Result.failure())
        val track = container.trackRepository.get(id) ?: return@coroutineScope Result.success()
        // A job queued behind one that already finished the Track has nothing to show.
        val waiting = track.pipeline == TrackPipelineState.Downloaded ||
            (track.pipeline as? TrackPipelineState.Separating)?.model == model
        val notifying = if (waiting) {
            showProgress(track)
            launch {
                container.trackRepository.observe(id).filterNotNull().distinctUntilChanged { a, b -> a.pipeline == b.pipeline && a.title == b.title }
                    .collect { showProgress(it) }
            }
        } else {
            null
        }
        val outcome = container.separateTrack(id, model)
        notifying?.cancel()
        when (outcome) {
            SeparateTrack.Outcome.Ready, SeparateTrack.Outcome.Failed, SeparateTrack.Outcome.Skipped -> Result.success()
            SeparateTrack.Outcome.RetryLater -> Result.retry()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null)

    private suspend fun showProgress(track: Track) {
        try {
            setForeground(foregroundInfo(track))
        } catch (e: IllegalStateException) {
            // Android 12+ may refuse a foreground service when the job starts in the background
            // (e.g. after process death). It then runs without one; if the system stops it, it
            // runs again later from its checkpoint.
        }
    }

    private fun foregroundInfo(track: Track?): ForegroundInfo {
        createChannel()
        val progress = (track?.pipeline as? TrackPipelineState.Separating)?.progress
        val context = applicationContext
        val text = when (progress?.stage) {
            SeparationProgress.Stage.DownloadingModel -> context.getString(R.string.notification_downloading_model)
            SeparationProgress.Stage.InstallingModel -> context.getString(R.string.notification_installing_model, percent(context.resources, progress.fraction))
            SeparationProgress.Stage.Separating -> context.getString(R.string.notification_separating, percent(context.resources, progress.fraction))
            null -> context.getString(R.string.notification_waiting_to_separate)
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(track?.title ?: context.getString(R.string.notification_separation_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, ((progress?.fraction ?: 0f) * 100).toInt(), progress == null)
            .build()
        // mediaProcessing exists from Android 15; dataSync before (ADR 15).
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        return ForegroundInfo(id.hashCode(), notification, type)
    }

    private fun createChannel() {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        // Created again each time, which keeps the channel's name in the user's current language.
        val name = applicationContext.getString(R.string.notification_channel_separation)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, name, NotificationManager.IMPORTANCE_LOW))
    }

    companion object {
        const val SOURCE = "source"
        const val SOURCE_TRACK_ID = "sourceTrackId"
        const val MODEL = "model"
        const val TAG = "separation"
        private const val CHANNEL = "separation"
    }
}
