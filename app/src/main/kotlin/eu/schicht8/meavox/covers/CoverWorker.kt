// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.covers

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.schicht8.meavox.MeaVoxApp
import java.util.concurrent.TimeUnit

/**
 * Fetches the Cover Art that network Tracks are missing (#50, ADR 8), once there is a network. A Track
 * that couldn't be settled (no connection, an extractor broken for now) is retried with backoff, as
 * jobs are (ADR 10), and again at the next app start. Not part of the Track Pipeline.
 */
class CoverWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val container = (context.applicationContext as MeaVoxApp).container

    override suspend fun doWork(): Result =
        if (container.fetchMissingCovers() || runAttemptCount >= MAX_ATTEMPTS - 1) Result.success() else Result.retry()

    companion object {
        private const val NAME = "covers"
        private const val MAX_ATTEMPTS = 5

        /** Queues the job unless one is queued already. */
        fun queue(workManager: WorkManager) {
            val request = OneTimeWorkRequestBuilder<CoverWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            workManager.enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
