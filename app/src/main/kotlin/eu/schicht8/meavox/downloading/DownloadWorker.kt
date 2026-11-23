// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.downloading

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import eu.schicht8.meavox.MeaVoxApp
import eu.schicht8.meavox.R
import eu.schicht8.meavox.core.model.SourceId
import eu.schicht8.meavox.core.model.Track
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.model.TrackPipelineState
import eu.schicht8.meavox.core.pipeline.DownloadTrack
import eu.schicht8.meavox.ui.percent
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Runs the Downloading step of one Track as a foreground job with a progress notification
 * (ADR 10). Process death or a stop by the system re-runs it, continuing after the bytes it has;
 * DownloadTrack decides when failed attempts are enough to give up.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val container = (context.applicationContext as MeaVoxApp).container

    override suspend fun doWork(): Result = coroutineScope {
        val id = TrackId(
            SourceId(inputData.getString(SOURCE) ?: return@coroutineScope Result.failure()),
            inputData.getString(SOURCE_TRACK_ID) ?: return@coroutineScope Result.failure(),
        )
        val track = container.trackRepository.get(id) ?: return@coroutineScope Result.success()
        val waiting = track.pipeline == TrackPipelineState.Added || track.pipeline is TrackPipelineState.Downloading
        val notifying = if (waiting) {
            showProgress(track)
            launch {
                container.trackRepository.observe(id).filterNotNull().distinctUntilChanged { a, b -> a.pipeline == b.pipeline && a.title == b.title }
                    .collect { showProgress(it) }
            }
        } else {
            null
        }
        val outcome = container.downloadTrack(id)
        notifying?.cancel()
        when (outcome) {
            DownloadTrack.Outcome.Done -> Result.success()
            DownloadTrack.Outcome.RetryLater -> Result.retry()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null)

    private suspend fun showProgress(track: Track) {
        try {
            setForeground(foregroundInfo(track))
        } catch (e: IllegalStateException) {
            // Android 12+ may refuse a foreground service for a job started in the background; it
            // then runs without one and continues later if the system stops it.
        }
    }

    private fun foregroundInfo(track: Track?): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        // Created again each time, which keeps the channel's name in the user's current language.
        val name = applicationContext.getString(R.string.notification_channel_downloads)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, name, NotificationManager.IMPORTANCE_LOW))
        val progress = (track?.pipeline as? TrackPipelineState.Downloading)?.progress
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(track?.title ?: applicationContext.getString(R.string.notification_downloading_title))
            .setContentText(
                if (progress == null) {
                    applicationContext.getString(R.string.notification_waiting_to_download)
                } else {
                    applicationContext.getString(R.string.notification_downloading, percent(applicationContext.resources, progress))
                },
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, ((progress ?: 0f) * 100).toInt(), progress == null)
            .build()
        return ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val SOURCE = "source"
        const val SOURCE_TRACK_ID = "sourceTrackId"
        private const val CHANNEL = "downloads"
    }
}
