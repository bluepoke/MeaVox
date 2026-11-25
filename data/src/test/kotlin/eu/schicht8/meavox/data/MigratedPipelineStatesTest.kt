// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.data

import eu.schicht8.meavox.core.model.FailureKind
import eu.schicht8.meavox.core.model.FailureReason
import eu.schicht8.meavox.core.model.PipelineStep
import eu.schicht8.meavox.core.model.SeparationModelId
import eu.schicht8.meavox.core.model.SourceId
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.model.TrackPipelineState
import eu.schicht8.meavox.core.port.SeparationProgress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Every Track Pipeline state a database version could store (#46): a database at [from] gets one Track
 * in each state as the app of that version wrote it, is migrated to the current version and read back
 * through [RoomTrackRepository], which must make the same state of it.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
// The newest SDK this Robolectric emulates, as in MigrationTest.
@Config(sdk = [35])
class MigratedPipelineStatesTest(private val from: Int) {
    @get:Rule
    val helper = migrationTestHelper()

    @Test
    fun everyStoredStateReadsAsTheSameTrackPipelineState() = runBlocking<Unit> {
        val stored = STORED_STATES.filter { from in it.versions }
        val database = helper.openFromVersion(from) {
            stored.forEachIndexed { i, state -> insert("tracks", *trackColumns(i), *state.columns()) }
        }
        val tracks = RoomTrackRepository(database)

        stored.forEachIndexed { i, state ->
            assertEquals(state.expected, tracks.get(trackId(i))?.pipeline, "pipeline of a Track stored at version $from as ${state.columns().toList()}")
        }
        assertEquals(stored.size, tracks.observeAll().first().size, "Tracks read back from version $from")
    }

    /**
     * The pipeline columns of one Track as the app wrote them, in [versions]. A column is left out when
     * it is null, so a state only names the columns its versions had.
     */
    private class StoredState(
        val versions: IntRange,
        val expected: TrackPipelineState,
        val pipelineState: String,
        val progress: String? = null,
        val failure: String? = null,
        val failureKind: String? = null,
        val failureService: String? = null,
        val previousModel: String? = null,
    ) {
        fun columns(): Row = listOfNotNull(
            "pipeline_state" to pipelineState,
            progress?.let { "pipeline_progress" to it },
            failure?.let { "pipeline_failure" to it },
            failureKind?.let { "pipeline_failure_kind" to it },
            failureService?.let { "pipeline_failure_service" to it },
            previousModel?.let { "pipeline_previous_model" to it },
        ).toTypedArray()
    }

    private fun trackId(i: Int) = TrackId(SourceId("youtube"), "track-$i")

    /** The columns every version's `tracks` table has. */
    private fun trackColumns(i: Int): Row = arrayOf(
        "source" to "youtube",
        "source_track_id" to "track-$i",
        "title" to "Track $i",
        "artist" to "Phil Collins",
        "duration_ms" to 293_000L,
        "added_at" to 1_700_000_000_000L + i,
    )

    companion object {
        private val CURRENT = exportedVersions().last()
        private val MODEL = SeparationModelId("spleeter-2stems")
        private val OTHER_MODEL = SeparationModelId("uvr-mdx-karaoke")

        /**
         * Each way a version stored a pipeline state, by the versions whose app wrote it so: from the version
         * that brought the state or its columns, until the version that wrote it differently. Failure reasons
         * were English text until version 14 (#43), and a Track waits to be re-separated with the model whose
         * Instrumental plays meanwhile from version 16 (#37).
         */
        private val STORED_STATES = listOf(
            // Version 1 (#4, #5): Tracks added and imported.
            StoredState(1..CURRENT, TrackPipelineState.Added, "added"),
            StoredState(1..CURRENT, TrackPipelineState.Downloaded, "downloaded"),
            // Version 2 (#8): Ready by the Separation Model of its stems, which the app could write before it separated (#7).
            StoredState(2..CURRENT, TrackPipelineState.Ready(MODEL), "ready:spleeter-2stems"),
            // Version 3 (#7): Separation with its progress, and failures.
            StoredState(3..CURRENT, TrackPipelineState.Separating(MODEL), "separating:spleeter-2stems"),
            StoredState(
                3..CURRENT,
                TrackPipelineState.Separating(MODEL, SeparationProgress(SeparationProgress.Stage.DownloadingModel, 0.1f)),
                "separating:spleeter-2stems",
                progress = "downloading_model:0.1",
            ),
            StoredState(
                3..CURRENT,
                TrackPipelineState.Separating(MODEL, SeparationProgress(SeparationProgress.Stage.InstallingModel, 0.9f)),
                "separating:spleeter-2stems",
                progress = "installing_model:0.9",
            ),
            StoredState(
                3..CURRENT,
                TrackPipelineState.Separating(MODEL, SeparationProgress(SeparationProgress.Stage.Separating, 0.42f)),
                "separating:spleeter-2stems",
                progress = "separating:0.42",
            ),
            // A reason stored as text before #43 reads as an unexpected failure with that text, so it shows as before.
            StoredState(
                3..13,
                TrackPipelineState.Failed(PipelineStep.Downloading, FailureReason(FailureKind.Unexpected, "the server answered HTTP 403")),
                "failed:downloading",
                failure = "the server answered HTTP 403",
            ),
            StoredState(
                3..13,
                TrackPipelineState.Failed(PipelineStep.Separating, FailureReason(FailureKind.Unexpected, "out of memory")),
                "failed:separating",
                failure = "out of memory",
            ),
            // Version 6 (#6): resumable downloads, with the tries that failed and why one waits to retry.
            StoredState(6..CURRENT, TrackPipelineState.Downloading(), "downloading", progress = "downloading::0"),
            StoredState(
                6..13,
                TrackPipelineState.Downloading(0.5f, FailureReason(FailureKind.Unexpected, "the connection broke off"), 2),
                "downloading",
                progress = "downloading:0.5:2",
                failure = "the connection broke off",
            ),
            // Version 8 (#20): stems deleted to free space.
            StoredState(8..CURRENT, TrackPipelineState.StemsDeleted(MODEL), "stems_deleted:spleeter-2stems"),
            // Version 9 (#25): a Vocal-Free Track is Ready without stems.
            StoredState(9..CURRENT, TrackPipelineState.Ready(model = null), "ready"),
            // Version 14 (#43): failure reasons as a kind, a detail and a service.
            StoredState(
                14..CURRENT,
                TrackPipelineState.Failed(PipelineStep.Downloading, FailureReason(FailureKind.ServerBusy, "503", "YouTube")),
                "failed:downloading",
                failure = "503",
                failureKind = "server_busy",
                failureService = "YouTube",
            ),
            StoredState(
                14..CURRENT,
                TrackPipelineState.Failed(PipelineStep.Separating, FailureReason(FailureKind.OutOfMemory)),
                "failed:separating",
                failureKind = "out_of_memory",
            ),
            StoredState(
                14..CURRENT,
                TrackPipelineState.Downloading(0.5f, FailureReason(FailureKind.ConnectionBroke, service = "YouTube"), 2),
                "downloading",
                progress = "downloading:0.5:2",
                failureKind = "connection_broke",
                failureService = "YouTube",
            ),
            // Version 16 (#37): re-separating, or failed to, while the previous model's Instrumental plays.
            StoredState(
                16..CURRENT,
                TrackPipelineState.Separating(OTHER_MODEL, previous = MODEL),
                "separating:uvr-mdx-karaoke",
                previousModel = "spleeter-2stems",
            ),
            StoredState(
                16..CURRENT,
                TrackPipelineState.Failed(PipelineStep.Separating, FailureReason(FailureKind.ModelDownloadFailed), MODEL),
                "failed:separating",
                failureKind = "model_download_failed",
                previousModel = "spleeter-2stems",
            ),
        )

        /** Every exported version but the current one: each is migrated to it. */
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "from version {0}")
        fun versions(): List<Array<Any>> = exportedVersions().dropLast(1).map { arrayOf(it) }
    }
}
