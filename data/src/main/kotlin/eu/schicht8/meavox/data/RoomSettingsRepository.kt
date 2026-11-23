// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import eu.schicht8.meavox.core.model.InputDevice
import eu.schicht8.meavox.core.model.OutputPath
import eu.schicht8.meavox.core.model.OutputRoute
import eu.schicht8.meavox.core.model.SeparationModelId
import eu.schicht8.meavox.core.session.SettingsRepository

/** One user setting as a key/value row; keys are stable strings. */
@Entity(tableName = "settings")
internal data class SettingEntity(
    @PrimaryKey @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "value") val value: String,
)

@Dao
internal interface SettingsDao {
    @Query("SELECT value FROM settings WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Upsert
    suspend fun put(setting: SettingEntity)
}

/** [SettingsRepository] on Room. */
class RoomSettingsRepository(database: MeaVoxDatabase) : SettingsRepository {
    private val dao = database.settings()

    override suspend fun countdownEnabled(): Boolean = dao.get(COUNTDOWN)?.toBooleanStrictOrNull() ?: false

    override suspend fun setCountdownEnabled(enabled: Boolean) = dao.put(SettingEntity(COUNTDOWN, enabled.toString()))

    override suspend fun reviewBackingDb(): Float? = dao.get(REVIEW_BACKING)?.toFloatOrNull()

    override suspend fun setReviewBackingDb(gainDb: Float) = dao.put(SettingEntity(REVIEW_BACKING, gainDb.toString()))

    override suspend fun inputGainDb(device: InputDevice): Float = dao.get(gainKey(device))?.toFloatOrNull() ?: 0f

    override suspend fun setInputGainDb(device: InputDevice, gainDb: Float) =
        dao.put(SettingEntity(gainKey(device), gainDb.toString()))

    override suspend fun latencyCorrectionMs(route: OutputRoute, path: OutputPath): Int? =
        dao.get(correctionKey(route, path))?.toIntOrNull()

    override suspend fun setLatencyCorrectionMs(route: OutputRoute, path: OutputPath, ms: Int) =
        dao.put(SettingEntity(correctionKey(route, path), ms.toString()))

    override suspend fun monitoringLevel(): Float? = dao.get(MONITORING_LEVEL)?.toFloatOrNull()

    override suspend fun setMonitoringLevel(level: Float) = dao.put(SettingEntity(MONITORING_LEVEL, level.toString()))

    override suspend fun defaultSeparationModel(): SeparationModelId? = dao.get(DEFAULT_SEPARATION_MODEL)?.let(::SeparationModelId)

    override suspend fun setDefaultSeparationModel(model: SeparationModelId) =
        dao.put(SettingEntity(DEFAULT_SEPARATION_MODEL, model.value))

    override suspend fun notificationPermissionAsked(): Boolean =
        dao.get(NOTIFICATION_PERMISSION_ASKED)?.toBooleanStrictOrNull() ?: false

    override suspend fun setNotificationPermissionAsked() = dao.put(SettingEntity(NOTIFICATION_PERMISSION_ASKED, true.toString()))

    override suspend fun singerName(): String? = dao.get(SINGER_NAME)?.takeIf { it.isNotBlank() }

    // Cleared as an empty value, so no delete is needed.
    override suspend fun setSingerName(name: String?) = dao.put(SettingEntity(SINGER_NAME, name?.trim().orEmpty()))

    private fun gainKey(device: InputDevice): String = "input_gain_db:" + when (device) {
        InputDevice.BuiltIn -> "built_in"
        InputDevice.Wired -> "wired"
        InputDevice.Usb -> "usb"
        InputDevice.Bluetooth -> "bluetooth"
        InputDevice.Other -> "other"
    }

    /** e.g. `latency_correction_ms:wired:low_latency`. */
    private fun correctionKey(route: OutputRoute, path: OutputPath): String = "latency_correction_ms:${route.toColumn()}:" + when (path) {
        OutputPath.Normal -> "normal"
        OutputPath.LowLatency -> "low_latency"
    }

    private companion object {
        const val COUNTDOWN = "countdown_enabled"
        const val MONITORING_LEVEL = "monitoring_level"
        const val REVIEW_BACKING = "review_backing_db"
        const val DEFAULT_SEPARATION_MODEL = "default_separation_model"
        const val NOTIFICATION_PERMISSION_ASKED = "notification_permission_asked"
        const val SINGER_NAME = "singer_name"
    }
}
