// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.session

import eu.schicht8.meavox.core.model.InputDevice
import eu.schicht8.meavox.core.model.OutputPath
import eu.schicht8.meavox.core.model.OutputRoute
import eu.schicht8.meavox.core.model.SeparationModelId

/** User settings that outlive a Session. Implemented by the data layer; faked in tests. */
interface SettingsRepository {
    /** Whether recording starts after a countdown (ADR 9). Off until the user turns it on. */
    suspend fun countdownEnabled(): Boolean

    suspend fun setCountdownEnabled(enabled: Boolean)

    /** Level of the Backing Audio while a Take plays back, in dB; null until the user sets it. */
    suspend fun reviewBackingDb(): Float?

    suspend fun setReviewBackingDb(gainDb: Float)

    /** Input gain for one kind of microphone in dB (ADR 9); 0 until set. */
    suspend fun inputGainDb(device: InputDevice): Float

    suspend fun setInputGainDb(device: InputDevice, gainDb: Float)

    /**
     * The Latency Correction of one output route and path in ms (#9), set by ear in Review; null until it was
     * set, or a Take on them was kept.
     */
    suspend fun latencyCorrectionMs(route: OutputRoute, path: OutputPath): Int?

    suspend fun setLatencyCorrectionMs(route: OutputRoute, path: OutputPath, ms: Int)

    /** Level of Monitoring, 0..1 (#9); null until set. Monitoring itself always starts off. */
    suspend fun monitoringLevel(): Float?

    suspend fun setMonitoringLevel(level: Float)

    /** The Default Separation Model the user chose (ADR 19); null until chosen. See [eu.schicht8.meavox.core.pipeline.DefaultSeparationModel]. */
    suspend fun defaultSeparationModel(): SeparationModelId?

    suspend fun setDefaultSeparationModel(model: SeparationModelId)

    /** Whether the user was asked for the notification permission (#27); false until asked. See [eu.schicht8.meavox.core.pipeline.NotificationPrompt]. */
    suspend fun notificationPermissionAsked(): Boolean

    suspend fun setNotificationPermissionAsked()

    /** Whether the Cover Art stored before #50 was cropped to squares (see [eu.schicht8.meavox.core.library.SquareCovers]); false until it was. */
    suspend fun coversSquared(): Boolean

    suspend fun setCoversSquared()

    /** The singer's name, which Mix Exports are tagged with ("<artist> feat. <name>", #15); null until set. */
    suspend fun singerName(): String?

    /** Sets the singer's name; null or blank clears it. */
    suspend fun setSingerName(name: String?)
}
