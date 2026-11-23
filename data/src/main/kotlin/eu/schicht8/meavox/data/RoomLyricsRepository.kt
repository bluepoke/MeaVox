// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import eu.schicht8.meavox.core.lyrics.LyricsRepository
import eu.schicht8.meavox.core.lyrics.TrackLyrics
import eu.schicht8.meavox.core.model.FailureKind
import eu.schicht8.meavox.core.model.FailureReason
import eu.schicht8.meavox.core.model.SourceId
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.port.LyricsCandidate
import eu.schicht8.meavox.core.port.LyricsQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Row of the `lyrics` table (version 4, #11; deleted with its Track from version 5): one per Track, keyed like `tracks`. Holds the
 * state, the query of the last search and the chosen Lyrics. Status values are stable strings.
 */
@Entity(
    tableName = "lyrics",
    primaryKeys = ["source", "source_track_id"],
    // The Lyrics go when the Track goes.
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["source", "source_track_id"],
            childColumns = ["source", "source_track_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class LyricsEntity(
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "source_track_id") val sourceTrackId: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "query_artist") val queryArtist: String? = null,
    @ColumnInfo(name = "query_title") val queryTitle: String? = null,
    @ColumnInfo(name = "query_duration_ms") val queryDurationMs: Long? = null,
    @ColumnInfo(name = "provider_item_id") val providerItemId: String? = null,
    @ColumnInfo(name = "artist") val artist: String? = null,
    @ColumnInfo(name = "title") val title: String? = null,
    @ColumnInfo(name = "duration_ms") val durationMs: Long? = null,
    @ColumnInfo(name = "plain") val plain: String? = null,
    @ColumnInfo(name = "synced") val synced: String? = null,
    /** The failure reason's detail since version 14, the whole reason as English text before (see [FailureColumns]). */
    @ColumnInfo(name = "failure") val failure: String? = null,
    /** The kind of that reason as a stable code (version 14, #43); null for rows from before. */
    @ColumnInfo(name = "failure_kind") val failureKind: String? = null,
    /** The service the reason names, e.g. LRCLIB (version 14, #43). */
    @ColumnInfo(name = "failure_service") val failureService: String? = null,
) {
    fun trackId() = TrackId(SourceId(source), sourceTrackId)
}

internal fun TrackLyrics.toEntity(track: TrackId): LyricsEntity {
    val row = LyricsEntity(track.source.value, track.sourceTrackId, status = "")
    return when (this) {
        TrackLyrics.Searching -> row.copy(status = SEARCHING)
        is TrackLyrics.Suggested -> row.copy(status = SUGGESTED).withQuery(query).withLyrics(lyrics)
        is TrackLyrics.Confirmed -> row.copy(status = CONFIRMED).withLyrics(lyrics)
        is TrackLyrics.NotFound -> row.copy(status = NOT_FOUND).withQuery(query)
        is TrackLyrics.Failed -> reason.toColumns().let { row.copy(status = FAILED, failure = it.detail, failureKind = it.kind, failureService = it.service) }
    }
}

internal fun LyricsEntity.toLyrics(): TrackLyrics = when (status) {
    SEARCHING -> TrackLyrics.Searching
    SUGGESTED -> TrackLyrics.Suggested(lyrics(), query())
    CONFIRMED -> TrackLyrics.Confirmed(lyrics())
    NOT_FOUND -> TrackLyrics.NotFound(query())
    FAILED -> TrackLyrics.Failed(FailureColumns(kind = failureKind, detail = failure, service = failureService).toReason() ?: FailureReason(FailureKind.Unexpected))
    else -> error("unknown lyrics status '$status'")
}

private fun LyricsEntity.withQuery(query: LyricsQuery) =
    copy(queryArtist = query.artist, queryTitle = query.title, queryDurationMs = query.durationMs)

private fun LyricsEntity.withLyrics(lyrics: LyricsCandidate) = copy(
    providerItemId = lyrics.providerItemId,
    artist = lyrics.artist,
    title = lyrics.title,
    durationMs = lyrics.durationMs,
    plain = lyrics.plain,
    synced = lyrics.synced,
)

private fun LyricsEntity.query() = LyricsQuery(queryArtist, checkNotNull(queryTitle), queryDurationMs)

private fun LyricsEntity.lyrics() =
    LyricsCandidate(checkNotNull(providerItemId), artist, checkNotNull(title), durationMs, plain, synced)

private const val SEARCHING = "searching"
private const val SUGGESTED = "suggested"
private const val CONFIRMED = "confirmed"
private const val NOT_FOUND = "not_found"
private const val FAILED = "failed"

/**
 * Row of the `lyrics_offsets` table (version 6, #12): a Track's Lyrics Offset. Its own table, so
 * choosing other Lyrics (which replaces the `lyrics` row) keeps it.
 */
@Entity(
    tableName = "lyrics_offsets",
    primaryKeys = ["source", "source_track_id"],
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["source", "source_track_id"],
            childColumns = ["source", "source_track_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class LyricsOffsetEntity(
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "source_track_id") val sourceTrackId: String,
    @ColumnInfo(name = "offset_ms") val offsetMs: Long,
)

@Dao
internal interface LyricsDao {
    @Query("SELECT * FROM lyrics WHERE source = :source AND source_track_id = :sourceTrackId")
    fun observe(source: String, sourceTrackId: String): Flow<LyricsEntity?>

    @Query("SELECT * FROM lyrics")
    fun observeAll(): Flow<List<LyricsEntity>>

    @Query("SELECT * FROM lyrics WHERE source = :source AND source_track_id = :sourceTrackId")
    suspend fun get(source: String, sourceTrackId: String): LyricsEntity?

    @Upsert
    suspend fun put(lyrics: LyricsEntity)

    /** Returns -1 if the Track has a row already. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(lyrics: LyricsEntity): Long

    @Query("SELECT offset_ms FROM lyrics_offsets WHERE source = :source AND source_track_id = :sourceTrackId")
    fun observeOffset(source: String, sourceTrackId: String): Flow<Long?>

    @Upsert
    suspend fun putOffset(offset: LyricsOffsetEntity)
}

/** [LyricsRepository] on Room. */
class RoomLyricsRepository(database: MeaVoxDatabase) : LyricsRepository {
    private val dao = database.lyrics()

    override fun observe(track: TrackId): Flow<TrackLyrics?> =
        dao.observe(track.source.value, track.sourceTrackId).map { it?.toLyrics() }

    override fun observeAll(): Flow<Map<TrackId, TrackLyrics>> =
        dao.observeAll().map { rows -> rows.associate { it.trackId() to it.toLyrics() } }

    override suspend fun get(track: TrackId): TrackLyrics? = dao.get(track.source.value, track.sourceTrackId)?.toLyrics()

    override suspend fun set(track: TrackId, lyrics: TrackLyrics) = dao.put(lyrics.toEntity(track))

    override suspend fun addIfAbsent(track: TrackId, lyrics: TrackLyrics): Boolean = dao.insertOrIgnore(lyrics.toEntity(track)) != -1L

    override fun observeOffset(track: TrackId): Flow<Long> =
        dao.observeOffset(track.source.value, track.sourceTrackId).map { it ?: 0L }

    override suspend fun setOffset(track: TrackId, offsetMs: Long) =
        dao.putOffset(LyricsOffsetEntity(track.source.value, track.sourceTrackId, offsetMs))
}
