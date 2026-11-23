// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Every Room migration step `n → n+1` (#26), with Room's migration test support. A database at version `n` gets a real row in each
 * of its tables, is migrated to `n+1` and validated against the exported schema `n+1` (tables,
 * columns, defaults, keys, indices), and every row comes back with the values it had.
 *
 * The versions are the exported schemas in `data/schemas/`, so a later migration is tested as soon
 * as its schema is exported. Runs on the JVM with Robolectric's SQLite, so CI runs it with `test`.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
// The newest SDK this Robolectric emulates; any SDK from minSdk up has the same SQLite behaviour for Room.
@Config(sdk = [35])
class MigrationTest(private val from: Int) {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MeaVoxDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migratesToTheNextVersionKeepingEveryRow() {
        val tables = schema(from).tables()
        helper.createDatabase(DATABASE_NAME, from).use { db ->
            for (table in tables.inInsertOrder()) db.insert(table)
        }

        helper.runMigrationsAndValidate(DATABASE_NAME, from + 1, true).use { db ->
            for (table in tables) {
                assertEquals("rows of ${table.name} after $from → ${from + 1}", listOf(table.row()), db.rows(table))
            }
        }
    }

    /** A column of a schema: its name and its SQLite affinity (`TEXT`, `INTEGER`, `REAL`). */
    private data class Column(val name: String, val affinity: String)

    /** One table of a schema: its columns, and the tables its foreign keys point to. */
    private data class Table(val name: String, val columns: List<Column>, val parents: List<String>) {
        /** The columns as a SQL list. */
        val columnList: String get() = columns.joinToString { "`${it.name}`" }

        /** A real row: plausible values by column name, so foreign keys between the tables match. */
        fun row(): List<Any?> = columns.map { VALUES["$name.${it.name}"] ?: VALUES[it.name] ?: fallback(it) }

        private fun fallback(column: Column): Any = when (column.affinity) {
            "INTEGER" -> 1L
            "REAL" -> 0.5
            else -> "$name.${column.name}"
        }
    }

    private fun JSONObject.tables(): List<Table> {
        val entities = getJSONObject("database").getJSONArray("entities")
        return (0 until entities.length()).map { i ->
            val entity = entities.getJSONObject(i)
            val fields = entity.getJSONArray("fields")
            val keys = entity.optJSONArray("foreignKeys") ?: JSONArray()
            Table(
                name = entity.getString("tableName"),
                columns = (0 until fields.length()).map { fields.getJSONObject(it).let { f -> Column(f.getString("columnName"), f.getString("affinity")) } },
                parents = (0 until keys.length()).map { keys.getJSONObject(it).getString("table") },
            )
        }
    }

    /** Parents before the tables whose foreign keys point to them. */
    private fun List<Table>.inInsertOrder(): List<Table> {
        val ordered = mutableListOf<Table>()
        while (ordered.size < size) {
            val next = filter { it !in ordered && it.parents.all { parent -> ordered.any { o -> o.name == parent } } }
            check(next.isNotEmpty()) { "foreign keys form a cycle in ${map { it.name }}" }
            ordered += next
        }
        return ordered
    }

    private fun SupportSQLiteDatabase.insert(table: Table) {
        val placeholders = table.columns.joinToString { "?" }
        execSQL("INSERT INTO `${table.name}` (${table.columnList}) VALUES ($placeholders)", table.row().toTypedArray())
    }

    /** The rows of [table], reading only the columns it had before the migration. */
    private fun SupportSQLiteDatabase.rows(table: Table): List<List<Any?>> =
        query("SELECT ${table.columnList} FROM `${table.name}`").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        table.columns.mapIndexed { i, column ->
                            when {
                                cursor.isNull(i) -> null
                                column.affinity == "INTEGER" -> cursor.getLong(i)
                                column.affinity == "REAL" -> cursor.getDouble(i)
                                else -> cursor.getString(i)
                            }
                        },
                    )
                }
            }
        }

    companion object {
        private const val DATABASE_NAME = "migration-test"
        private val SCHEMAS = File("schemas/${MeaVoxDatabase::class.java.name}")

        private fun schema(version: Int) = JSONObject(File(SCHEMAS, "$version.json").readText())

        /** Every exported version but the last: each is migrated to the next. */
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} → next")
        fun versions(): List<Array<Any>> {
            // Gradle runs unit tests in the module's directory.
            check(SCHEMAS.isDirectory) { "no exported schemas in ${SCHEMAS.absolutePath}" }
            val versions = SCHEMAS.listFiles().orEmpty().mapNotNull { it.nameWithoutExtension.toIntOrNull() }.sorted()
            check(versions.size > 1 && versions == (1..versions.last()).toList()) { "a schema is missing among $versions" }
            return versions.dropLast(1).map { arrayOf(it) }
        }

        /**
         * Real values for the app's columns, consistent across tables: the Track `youtube:abc` with
         * a Take `t1` and its Mix `m1`, by column or by `table.column`. Columns not listed get a value of
         * their affinity.
         */
        private val VALUES: Map<String, Any> = mapOf(
            "mixes.id" to "m1",
            "mixes.name" to "Mix 1",
            "source" to "youtube",
            "source_track_id" to "abc",
            "title" to "In the Air Tonight",
            "artist" to "Phil Collins",
            "duration_ms" to 293_000L,
            "pipeline_state" to "ready:spleeter-2stems",
            "added_at" to 1_700_000_000_000L,
            "pipeline_progress" to "separating:0.5",
            "pipeline_failure" to "the server answered HTTP 503",
            "pipeline_failure_kind" to "server_busy",
            "pipeline_failure_service" to "YouTube",
            "last_session_at" to 1_700_000_100_000L,
            "vocal_free" to 0L,
            "backing_choice" to "original_audio",
            "separation_model" to "uvr-mdx-karaoke",
            "last_transpose" to -2L,
            "id" to "t1",
            "name" to "Take 1",
            "start_frame" to 1_440_000L,
            "frames" to 480_000L,
            "sample_rate" to 48_000L,
            "output_route" to "wired",
            "reported_latency_frames" to 4_800L,
            "transpose" to 2L,
            "recorded_at" to 1_700_000_200_000L,
            "key" to "countdown_enabled",
            "value" to "true",
            "status" to "confirmed",
            "query_artist" to "Phil Collins",
            "query_title" to "In the Air Tonight",
            "query_duration_ms" to 293_000L,
            "provider_item_id" to "7385506",
            "plain" to "I can feel it coming in the air tonight",
            "synced" to "[00:01.00] I can feel it coming in the air tonight",
            "failure" to "LRCLIB answered HTTP 400",
            "failure_kind" to "server_error",
            "failure_service" to "LRCLIB",
            "offset_ms" to 250L,
            "take_id" to "t1",
            "instrumental_model" to "spleeter-2stems",
            "backing_db" to -12.0,
            "vocal_db" to 3.0,
            "vocal_nudge_ms" to 25L,
            "created_at" to 1_700_000_300_000L,
            "reverb" to "room",
            "compression" to 1L,
            "high_pass" to 1L,
            "guide_vocal_level" to 0.25,
        )
    }
}
