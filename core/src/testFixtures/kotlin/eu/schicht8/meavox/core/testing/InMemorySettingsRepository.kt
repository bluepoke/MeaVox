// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.testing

import eu.schicht8.meavox.core.model.InputDevice
import eu.schicht8.meavox.core.model.OutputPath
import eu.schicht8.meavox.core.model.OutputRoute
import eu.schicht8.meavox.core.model.SeparationModelId
import eu.schicht8.meavox.core.session.SettingsRepository

class InMemorySettingsRepository : SettingsRepository {
    private var countdown = false
    private var reviewBackingDb: Float? = null

    override suspend fun reviewBackingDb(): Float? = reviewBackingDb

    override suspend fun setReviewBackingDb(gainDb: Float) {
        reviewBackingDb = gainDb
    }
    private val gains = mutableMapOf<InputDevice, Float>()

    override suspend fun countdownEnabled(): Boolean = countdown

    override suspend fun setCountdownEnabled(enabled: Boolean) {
        countdown = enabled
    }

    override suspend fun inputGainDb(device: InputDevice): Float = gains[device] ?: 0f

    override suspend fun setInputGainDb(device: InputDevice, gainDb: Float) {
        gains[device] = gainDb
    }

    private val corrections = mutableMapOf<Pair<OutputRoute, OutputPath>, Int>()

    override suspend fun latencyCorrectionMs(route: OutputRoute, path: OutputPath): Int? = corrections[route to path]

    override suspend fun setLatencyCorrectionMs(route: OutputRoute, path: OutputPath, ms: Int) {
        corrections[route to path] = ms
    }

    private var monitoringLevel: Float? = null

    override suspend fun monitoringLevel(): Float? = monitoringLevel

    override suspend fun setMonitoringLevel(level: Float) {
        monitoringLevel = level
    }

    private var defaultSeparationModel: SeparationModelId? = null

    override suspend fun defaultSeparationModel(): SeparationModelId? = defaultSeparationModel

    override suspend fun setDefaultSeparationModel(model: SeparationModelId) {
        defaultSeparationModel = model
    }

    private var notificationPermissionAsked = false

    override suspend fun notificationPermissionAsked(): Boolean = notificationPermissionAsked

    override suspend fun setNotificationPermissionAsked() {
        notificationPermissionAsked = true
    }

    private var singerName: String? = null

    override suspend fun singerName(): String? = singerName

    override suspend fun setSingerName(name: String?) {
        singerName = name?.trim()?.takeIf { it.isNotEmpty() }
    }
}
