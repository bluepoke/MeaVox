// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.data

import eu.schicht8.meavox.core.lyrics.TrackLyrics
import eu.schicht8.meavox.core.mix.Mix
import eu.schicht8.meavox.core.mix.MixId
import eu.schicht8.meavox.core.mix.MixSettings
import eu.schicht8.meavox.core.mix.VocalEffects
import eu.schicht8.meavox.core.model.BackingChoice
import eu.schicht8.meavox.core.model.FailureKind
import eu.schicht8.meavox.core.model.FailureReason
import eu.schicht8.meavox.core.model.InputDevice
import eu.schicht8.meavox.core.model.OutputPath
import eu.schicht8.meavox.core.model.OutputRoute
import eu.schicht8.meavox.core.model.PipelineStep
import eu.schicht8.meavox.core.model.SeparationModelId
import eu.schicht8.meavox.core.model.SourceId
import eu.schicht8.meavox.core.model.Take
import eu.schicht8.meavox.core.model.TakeId
import eu.schicht8.meavox.core.model.Track
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.model.TrackPipelineState
import eu.schicht8.meavox.core.model.Transpose
import eu.schicht8.meavox.core.port.LyricsCandidate
import eu.schicht8.meavox.core.port.LyricsQuery
import eu.schicht8.meavox.core.session.BackingAudio
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Old databases read back through the repositories after all migrations (#46): from version 1 with
 * Tracks, from version 6 with Takes, settings, Lyrics and Lyrics Offsets, and from version 12 with
 * Mixes. The rows are written with SQL as the app of that version wrote them. What came after reads as
 * it means for data from before: each column a later version added at its default, as the app sees it.
 *
 * The app of version 1 wrote only Added and Downloaded Tracks: Ready (`ready:<model>`) came with
 * version 2 (#8). [MigratedPipelineStatesTest] reads every state from each version that could store it.
 */
@RunWith(RobolectricTestRunner::class)
// The newest SDK this Robolectric emulates, as in MigrationTest.
@Config(sdk = [35])
class MigratedDatabaseTest {
    @get:Rule
    val helper = migrationTestHelper()

    @Test
    fun aVersion1LibraryReadsBackWithEveryLaterTrackColumnAtItsDefault() = runBlocking<Unit> {
        val database = helper.openFromVersion(1) {
            insert("tracks", *trackRow(ADDED, "added", addedAt = 1_700_000_000_000L))
            insert("tracks", *trackRow(IMPORTED, "downloaded", addedAt = 1_700_000_100_000L, artist = null, durationMs = null))
        }
        val tracks = RoomTrackRepository(database).observeAll().first()

        assertEquals(
            listOf(
                Track(IMPORTED, "In the Air Tonight", null, null, TrackPipelineState.Downloaded),
                Track(ADDED, "In the Air Tonight", "Phil Collins", 293_000L, TrackPipelineState.Added),
            ),
            tracks,
            "the Tracks, newest first, with every later column as for a new Track",
        )
        for (track in tracks) assertTrackColumnsFromBefore(track, from = 1)

        // The tables added later are empty for these Tracks.
        assertEquals(emptyMap(), RoomTakeRepository(database).observeCounts().first(), "a Track from version 1 has no Takes")
        assertNull(RoomLyricsRepository(database).get(ADDED), "a Track from version 1 has no Lyrics yet")
        assertEquals(0L, RoomLyricsRepository(database).observeOffset(ADDED).first(), "a Track from version 1 has no Lyrics Offset")
        assertEquals(emptyList(), RoomMixRepository(database).observe(ADDED).first(), "a Track from version 1 has no Mixes")
    }

    @Test
    fun aVersion6DatabaseReadsBackItsTakesSettingsLyricsAndOffsets() = runBlocking<Unit> {
        val database = helper.openFromVersion(6) {
            insert("tracks", *trackRow(SEPARATED, "ready:spleeter-2stems"))
            insert("tracks", *trackRow(FAILED, "failed:downloading"), "pipeline_failure" to "the server answered HTTP 403")
            insert("tracks", *trackRow(NOT_FOUND, "downloaded"))
            insert("takes", *takeRow(TAKE, SEPARATED))
            insert("settings", "key" to "countdown_enabled", "value" to "true")
            insert("settings", "key" to "review_backing_db", "value" to "-15.0")
            insert("settings", "key" to "input_gain_db:wired", "value" to "6.0")
            insert(
                "lyrics",
                *lyricsRow(SEPARATED, "confirmed"),
                "provider_item_id" to "7385506",
                "artist" to "Phil Collins",
                "title" to "In the Air Tonight",
                "duration_ms" to 293_000L,
                "plain" to "I can feel it coming in the air tonight",
                "synced" to "[00:01.00] I can feel it coming in the air tonight",
            )
            insert("lyrics", *lyricsRow(FAILED, "failed"), "failure" to "LRCLIB answered HTTP 500")
            insert(
                "lyrics",
                *lyricsRow(NOT_FOUND, "not_found"),
                "query_artist" to "Phil Collins",
                "query_title" to "In the Air Tonight",
                "query_duration_ms" to 293_000L,
            )
            insert("lyrics_offsets", "source" to "youtube", "source_track_id" to SEPARATED.sourceTrackId, "offset_ms" to 250L)
        }

        val tracks = RoomTrackRepository(database)
        for (track in tracks.observeAll().first()) assertTrackColumnsFromBefore(track, from = 6)
        assertEquals(
            TrackPipelineState.Failed(
                PipelineStep.Downloading,
                FailureReason(FailureKind.Unexpected, "the server answered HTTP 403"),
            ),
            tracks.get(FAILED)?.pipeline,
            "a failure reason stored as text before #43 is still shown as that text",
        )

        val take = checkNotNull(RoomTakeRepository(database).get(TAKE))
        assertEquals(
            Take(TAKE, SEPARATED, "Take 1", 1_440_000L, 480_000L, 48_000, OutputRoute.Wired, 4_800L, Transpose.None, 1_700_000_200_000L),
            take,
            "the Take as it was recorded",
        )
        assertEquals(Transpose.None, take.transpose, "a Take from before Transpose (#10) was sung untransposed")
        assertEquals(0, take.latencyCorrectionMs, "a Take from before the Latency Correction (#9) was kept with none")
        assertEquals(take.reportedLatencyFrames, take.latencyFrames, "so it is aligned by the engine's estimate alone")
        assertEquals(mapOf(SEPARATED to 1), RoomTakeRepository(database).observeCounts().first(), "Takes per Track")

        val settings = RoomSettingsRepository(database)
        assertTrue(settings.countdownEnabled(), "the countdown stays on")
        assertEquals(-15f, settings.reviewBackingDb(), "the Review Balance stays")
        assertEquals(6f, settings.inputGainDb(InputDevice.Wired), "the wired microphone's input gain stays")
        assertEquals(0f, settings.inputGainDb(InputDevice.BuiltIn), "a microphone without a stored gain gets none")
        assertNull(settings.monitoringLevel(), "no Monitoring level was set: the app's default applies")
        assertNull(settings.defaultSeparationModel(), "no Default Separation Model was chosen: the catalogue's default applies")
        for (route in OutputRoute.entries) {
            for (path in OutputPath.entries) {
                assertNull(settings.latencyCorrectionMs(route, path), "no Latency Correction was set by ear for $route, $path")
            }
        }
        assertFalse(settings.notificationPermissionAsked(), "the notification permission was not asked for yet")
        assertNull(settings.singerName(), "no singer name was given")

        val lyrics = RoomLyricsRepository(database)
        assertEquals(
            TrackLyrics.Confirmed(
                LyricsCandidate(
                    "7385506",
                    "Phil Collins",
                    "In the Air Tonight",
                    293_000L,
                    "I can feel it coming in the air tonight",
                    "[00:01.00] I can feel it coming in the air tonight",
                ),
            ),
            lyrics.get(SEPARATED),
            "confirmed Lyrics",
        )
        assertEquals(
            TrackLyrics.Failed(FailureReason(FailureKind.Unexpected, "LRCLIB answered HTTP 500")),
            lyrics.get(FAILED),
            "a Lyrics failure stored as text before #43 is still shown as that text",
        )
        assertEquals(TrackLyrics.NotFound(LyricsQuery("Phil Collins", "In the Air Tonight", 293_000L)), lyrics.get(NOT_FOUND), "Lyrics not found")
        assertEquals(3, lyrics.observeAll().first().size, "Lyrics of every Track")
        assertEquals(250L, lyrics.observeOffset(SEPARATED).first(), "the Lyrics Offset stays")
        assertEquals(0L, lyrics.observeOffset(FAILED).first(), "a Track without a Lyrics Offset has none")
    }

    @Test
    fun aVersion12DatabaseReadsBackItsMixesWithoutVocalEffectsOrGuideVocal() = runBlocking<Unit> {
        val database = helper.openFromVersion(12) {
            insert(
                "tracks",
                *trackRow(SEPARATED, "ready:spleeter-2stems"),
                "last_session_at" to 1_700_000_150_000L,
                "vocal_free" to 0L,
                "backing_choice" to "original_audio",
                "separation_model" to "spleeter-2stems",
            )
            insert("takes", *takeRow(TAKE, SEPARATED), "transpose" to -2L)
            insert("mixes", *mixRow(OVER_INSTRUMENTAL, instrumentalModel = "spleeter-2stems", createdAt = 1_700_000_300_000L))
            insert("mixes", *mixRow(OVER_ORIGINAL, instrumentalModel = null, createdAt = 1_700_000_400_000L))
        }

        val track = checkNotNull(RoomTrackRepository(database).get(SEPARATED))
        assertEquals(
            Track(
                SEPARATED,
                "In the Air Tonight",
                "Phil Collins",
                293_000L,
                TrackPipelineState.Ready(MODEL),
                lastSessionAt = 1_700_000_150_000L,
                backingChoice = BackingChoice.OriginalAudio,
                separationModel = MODEL,
            ),
            track,
            "the Track with what version 12 stored",
        )
        assertTrackColumnsFromBefore(track, from = 12)

        val take = checkNotNull(RoomTakeRepository(database).get(TAKE))
        assertEquals(Transpose.clamped(-2), take.transpose, "the Take keeps its Transpose")
        assertEquals(0, take.latencyCorrectionMs, "a Take from before the Latency Correction (#9) was kept with none")

        val mixes = RoomMixRepository(database)
        assertEquals(
            listOf(
                Mix(OVER_INSTRUMENTAL, SEPARATED, TAKE, "Mix 1", BackingAudio.Instrumental(MODEL), MixSettings(-12f, 3f, 25), 1_700_000_300_000L),
                Mix(OVER_ORIGINAL, SEPARATED, TAKE, "Mix 1", BackingAudio.OriginalAudio, MixSettings(-12f, 3f, 25), 1_700_000_400_000L),
            ),
            mixes.observe(SEPARATED).first(),
            "the Track's Mixes, oldest first",
        )
        for (mix in mixes.observe(SEPARATED).first()) {
            assertEquals(VocalEffects.None, mix.settings.effects, "a Mix from before Vocal Effects (#16) has none: the Take plays as recorded")
            assertEquals(0f, mix.settings.guideVocalLevel, "a Mix from before the Guide Vocal (#16) blends none of it in")
        }
    }

    /**
     * The Track columns added after version [from] are at their defaults, as the app sees them for data
     * from before: as for a Track added new, without anything the user did since.
     */
    private fun assertTrackColumnsFromBefore(track: Track, from: Int) {
        val what = "a Track from version $from"
        if (from < 8) assertNull(track.lastSessionAt, "$what was never sung (#20)")
        if (from < 9) assertFalse(track.vocalFree, "$what is not Vocal-Free (#25)")
        if (from < 10) assertEquals(BackingChoice.Instrumental, track.backingChoice, "$what sings over the Instrumental, as a new Track (#34)")
        if (from < 11) assertNull(track.separationModel, "$what has no picked Separation Model, so it gets the Default Separation Model (#17)")
        if (from < 13) assertEquals(Transpose.None, track.lastTranspose, "$what has no Last Transpose: a Session starts untransposed (#23)")
        if (from < 16) {
            assertEquals(
                (track.pipeline as? TrackPipelineState.Ready)?.model,
                track.instrumentalModel,
                "$what plays only the Instrumental it is Ready with, no previous one (#37)",
            )
        }
        if (from < 18) assertNull(track.coverUrl, "$what has no picture to download, so its Exports have no Cover Art (#42)")
        if (from < 19) assertFalse(track.coverLookedUp, "$what hasn't had its missing cover looked up yet (#50)")
    }

    private fun trackRow(
        id: TrackId,
        state: String,
        addedAt: Long = 1_700_000_000_000L,
        artist: String? = "Phil Collins",
        durationMs: Long? = 293_000L,
    ): Row = arrayOf(
        "source" to id.source.value,
        "source_track_id" to id.sourceTrackId,
        "title" to "In the Air Tonight",
        "artist" to artist,
        "duration_ms" to durationMs,
        "pipeline_state" to state,
        "added_at" to addedAt,
    )

    /** A Take as version 6 stored it: before its Transpose (version 7) and Latency Correction (version 17). */
    private fun takeRow(id: TakeId, track: TrackId): Row = arrayOf(
        "id" to id.value,
        "source" to track.source.value,
        "source_track_id" to track.sourceTrackId,
        "name" to "Take 1",
        "start_frame" to 1_440_000L,
        "frames" to 480_000L,
        "sample_rate" to 48_000L,
        "output_route" to "wired",
        "reported_latency_frames" to 4_800L,
        "recorded_at" to 1_700_000_200_000L,
    )

    private fun lyricsRow(track: TrackId, status: String): Row =
        arrayOf("source" to track.source.value, "source_track_id" to track.sourceTrackId, "status" to status)

    /** A Mix as version 12 stored it: before its Vocal Effects and Guide Vocal level (version 15). */
    private fun mixRow(id: MixId, instrumentalModel: String?, createdAt: Long): Row = arrayOf(
        "id" to id.value,
        "source" to SEPARATED.source.value,
        "source_track_id" to SEPARATED.sourceTrackId,
        "take_id" to TAKE.value,
        "name" to "Mix 1",
        "instrumental_model" to instrumentalModel,
        "backing_db" to -12.0,
        "vocal_db" to 3.0,
        "vocal_nudge_ms" to 25L,
        "created_at" to createdAt,
    )

    private companion object {
        val MODEL = SeparationModelId("spleeter-2stems")
        val ADDED = TrackId(SourceId("youtube"), "added")
        val IMPORTED = TrackId(SourceId.Local, "3f9a1c")
        val SEPARATED = TrackId(SourceId("youtube"), "separated")
        val FAILED = TrackId(SourceId("youtube"), "failed")
        val NOT_FOUND = TrackId(SourceId("youtube"), "lyrics-not-found")
        val TAKE = TakeId("t1")
        val OVER_INSTRUMENTAL = MixId("m1")
        val OVER_ORIGINAL = MixId("m2")
    }
}
