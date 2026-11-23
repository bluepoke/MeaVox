// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import eu.schicht8.meavox.core.mix.Mix
import eu.schicht8.meavox.core.mix.MixId
import eu.schicht8.meavox.core.mix.MixRepository
import eu.schicht8.meavox.core.mix.MixSettings
import eu.schicht8.meavox.core.mix.VocalEffects
import eu.schicht8.meavox.core.model.SeparationModelId
import eu.schicht8.meavox.core.model.SourceId
import eu.schicht8.meavox.core.model.TakeId
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.session.BackingAudio
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Row of the `mixes` table (#14). A Mix goes when its Take or its Track goes. */
@Entity(
    tableName = "mixes",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["source", "source_track_id"],
            childColumns = ["source", "source_track_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(entity = TakeEntity::class, parentColumns = ["id"], childColumns = ["take_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("source", "source_track_id"), Index("take_id")],
)
internal data class MixEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "source_track_id") val sourceTrackId: String,
    @ColumnInfo(name = "take_id") val takeId: String,
    @ColumnInfo(name = "name") val name: String,
    /** The Separation Model of the Instrumental, or null for the Original Audio. */
    @ColumnInfo(name = "instrumental_model") val instrumentalModel: String?,
    @ColumnInfo(name = "backing_db") val backingDb: Float,
    @ColumnInfo(name = "vocal_db") val vocalDb: Float,
    @ColumnInfo(name = "vocal_nudge_ms") val vocalNudgeMs: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** The reverb preset (version 15, #16): `room` or `hall`; null for Dry, as for Mixes from before. */
    @ColumnInfo(name = "reverb") val reverb: String? = null,
    @ColumnInfo(name = "compression", defaultValue = "0") val compression: Boolean = false,
    @ColumnInfo(name = "high_pass", defaultValue = "0") val highPass: Boolean = false,
    /** The Guide Vocal level, 0..1 (version 15, #16). */
    @ColumnInfo(name = "guide_vocal_level", defaultValue = "0") val guideVocalLevel: Float = 0f,
)

internal fun Mix.toEntity() = MixEntity(
    id = id.value,
    source = track.source.value,
    sourceTrackId = track.sourceTrackId,
    takeId = take.value,
    name = name,
    instrumentalModel = (backing as? BackingAudio.Instrumental)?.model?.value,
    backingDb = settings.backingDb,
    vocalDb = settings.vocalDb,
    vocalNudgeMs = settings.vocalNudgeMs,
    createdAt = createdAt,
    reverb = settings.effects.reverb.toColumn(),
    compression = settings.effects.compression,
    highPass = settings.effects.highPass,
    guideVocalLevel = settings.guideVocalLevel,
)

internal fun MixEntity.toMix() = Mix(
    id = MixId(id),
    track = TrackId(SourceId(source), sourceTrackId),
    take = TakeId(takeId),
    name = name,
    backing = instrumentalModel?.let { BackingAudio.Instrumental(SeparationModelId(it)) } ?: BackingAudio.OriginalAudio,
    // Clamped: a row is never refused for a value out of today's range.
    settings = MixSettings.clamped(
        backingDb,
        vocalDb,
        vocalNudgeMs,
        VocalEffects(reverbFromColumn(reverb), compression, highPass),
        guideVocalLevel,
    ),
    createdAt = createdAt,
)

/** Stored as stable strings so enum renames don't corrupt data; Dry as null. */
private fun VocalEffects.Reverb.toColumn(): String? = when (this) {
    VocalEffects.Reverb.Dry -> null
    VocalEffects.Reverb.Room -> "room"
    VocalEffects.Reverb.Hall -> "hall"
}

/** A preset from a later version that this one doesn't know plays Dry. */
private fun reverbFromColumn(value: String?): VocalEffects.Reverb = when (value) {
    "room" -> VocalEffects.Reverb.Room
    "hall" -> VocalEffects.Reverb.Hall
    else -> VocalEffects.Reverb.Dry
}

@Dao
internal interface MixDao {
    @Query("SELECT * FROM mixes WHERE source = :source AND source_track_id = :sourceTrackId ORDER BY created_at, rowid")
    fun observe(source: String, sourceTrackId: String): Flow<List<MixEntity>>

    @Query("SELECT * FROM mixes WHERE id = :id")
    suspend fun get(id: String): MixEntity?

    @Insert
    suspend fun insert(mix: MixEntity)

    @Update
    suspend fun update(mix: MixEntity)

    @Query("DELETE FROM mixes WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM mixes WHERE take_id = :takeId")
    suspend fun deleteForTake(takeId: String)

    @Query("DELETE FROM mixes WHERE source = :source AND source_track_id = :sourceTrackId")
    suspend fun deleteAll(source: String, sourceTrackId: String)
}

/** [MixRepository] on Room. */
class RoomMixRepository(database: MeaVoxDatabase) : MixRepository {
    private val dao = database.mixes()

    override fun observe(track: TrackId): Flow<List<Mix>> =
        dao.observe(track.source.value, track.sourceTrackId).map { rows -> rows.map { it.toMix() } }

    override suspend fun get(id: MixId): Mix? = dao.get(id.value)?.toMix()

    override suspend fun add(mix: Mix) = dao.insert(mix.toEntity())

    override suspend fun update(mix: Mix) = dao.update(mix.toEntity())

    override suspend fun delete(id: MixId) = dao.delete(id.value)

    override suspend fun deleteForTake(take: TakeId) = dao.deleteForTake(take.value)

    override suspend fun deleteAll(track: TrackId) = dao.deleteAll(track.source.value, track.sourceTrackId)
}
