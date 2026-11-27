// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox

import android.app.Application
import kotlinx.coroutines.launch

class MeaVoxApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Downloaded Tracks move on to Separation, also in a process started only for a job.
        container.appScope.launch {
            container.stopLegacySeparations()
            container.trackPipeline.run()
        }
        // Every Track gets its Lyrics searched, whatever the Track Pipeline is doing.
        container.appScope.launch { container.lyricsPipeline.run() }
        // Files a job wrote after its Track was deleted (#20).
        container.appScope.launch { container.trackStorage.removeOrphanedFiles() }
        // Square covers, also for the ones stored before #50; then the covers older network Tracks lack.
        container.appScope.launch {
            container.squareCovers()
            container.queueMissingCovers()
        }
    }
}
