// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import eu.schicht8.meavox.core.model.OutputRoute
import eu.schicht8.meavox.core.model.SourceId
import eu.schicht8.meavox.core.model.Take
import eu.schicht8.meavox.core.model.TakeId
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.model.Transpose

/** Row of the `takes` table. A Take belongs to one Track and goes when the Track goes. */
@Entity(
    tableName = "takes",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["source", "source_track_id"],
            childColumns = ["source", "source_track_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("source", "source_track_id")],
)
internal data class TakeEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "source_track_id") val sourceTrackId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "start_frame") val startFrame: Long,
    @ColumnInfo(name = "frames") val frames: Long,
    @ColumnInfo(name = "sample_rate") val sampleRate: Int,
    @ColumnInfo(name = "output_route") val outputRoute: String,
    @ColumnInfo(name = "reported_latency_frames") val reportedLatencyFrames: Long,
    // Takes from before Transpose (#10) were all sung untransposed.
    @ColumnInfo(name = "transpose", defaultValue = "0") val transpose: Int,
    @ColumnInfo(name = "recorded_at") val recordedAt: Long,
    /** The Latency Correction it was kept with (version 17, #9); Takes from before have none. */
    @ColumnInfo(name = "latency_correction_ms", defaultValue = "0") val latencyCorrectionMs: Int = 0,
)

internal fun Take.toEntity() = TakeEntity(
    id = id.value,
    source = track.source.value,
    sourceTrackId = track.sourceTrackId,
    name = name,
    startFrame = startFrame,
    frames = frames,
    sampleRate = sampleRate,
    outputRoute = route.toColumn(),
    reportedLatencyFrames = reportedLatencyFrames,
    transpose = transpose.semitones,
    recordedAt = recordedAt,
    latencyCorrectionMs = latencyCorrectionMs,
)

internal fun TakeEntity.toTake() = Take(
    id = TakeId(id),
    track = TrackId(SourceId(source), sourceTrackId),
    name = name,
    startFrame = startFrame,
    frames = frames,
    sampleRate = sampleRate,
    route = outputRouteFromColumn(outputRoute),
    reportedLatencyFrames = reportedLatencyFrames,
    transpose = Transpose.clamped(transpose),
    recordedAt = recordedAt,
    latencyCorrectionMs = latencyCorrectionMs,
)

// Stored as stable strings so enum renames don't corrupt data.
internal fun OutputRoute.toColumn(): String = when (this) {
    OutputRoute.Speaker -> "speaker"
    OutputRoute.Wired -> "wired"
    OutputRoute.Usb -> "usb"
    OutputRoute.Bluetooth -> "bluetooth"
    OutputRoute.Other -> "other"
}

private fun outputRouteFromColumn(value: String): OutputRoute = when (value) {
    "speaker" -> OutputRoute.Speaker
    "wired" -> OutputRoute.Wired
    "usb" -> OutputRoute.Usb
    "bluetooth" -> OutputRoute.Bluetooth
    else -> OutputRoute.Other
}
