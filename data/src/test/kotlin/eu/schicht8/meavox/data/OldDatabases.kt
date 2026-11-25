// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

// Old databases for the migration tests (#26, #46). A database file is made at an old version from
// its exported schema and filled with SQL as the app of that version wrote its rows. Then the app's
// own MeaVoxDatabase opens it, which migrates it to the current version.

/** The file name the app's database has; [MeaVoxDatabase.create] opens it. */
private const val APP_DATABASE_NAME = "meavox.db"

/** A row as column names and values, for [insert]. */
internal typealias Row = Array<Pair<String, Any?>>

/** Room's migration test support for [MeaVoxDatabase], as a JUnit 4 rule. */
internal fun migrationTestHelper() = MigrationTestHelper(
    InstrumentationRegistry.getInstrumentation(),
    MeaVoxDatabase::class.java,
    emptyList(),
    FrameworkSQLiteOpenHelperFactory(),
)

/** A database at [version] with the rows [fill] writes, opened (and so migrated) as the app opens it. */
internal fun MigrationTestHelper.openFromVersion(version: Int, fill: SupportSQLiteDatabase.() -> Unit): MeaVoxDatabase {
    createDatabase(APP_DATABASE_NAME, version).use { it.fill() }
    return MeaVoxDatabase.create(InstrumentationRegistry.getInstrumentation().targetContext).also(::closeWhenFinished)
}

/** Inserts a row into [table], with only the columns [values] names: those the table had at that version. */
internal fun SupportSQLiteDatabase.insert(table: String, vararg values: Pair<String, Any?>) {
    val columns = values.joinToString { "`${it.first}`" }
    val placeholders = values.joinToString { "?" }
    execSQL("INSERT INTO `$table` ($columns) VALUES ($placeholders)", values.map { it.second }.toTypedArray())
}

/** Where Room exports each version's schema. Gradle runs unit tests in the module's directory. */
internal val EXPORTED_SCHEMAS = File("schemas/${MeaVoxDatabase::class.java.name}")

/** The versions with an exported schema, oldest first, with none missing; the last is the current one. */
internal fun exportedVersions(): List<Int> {
    check(EXPORTED_SCHEMAS.isDirectory) { "no exported schemas in ${EXPORTED_SCHEMAS.absolutePath}" }
    val versions = EXPORTED_SCHEMAS.listFiles().orEmpty().mapNotNull { it.nameWithoutExtension.toIntOrNull() }.sorted()
    check(versions.size > 1 && versions == (1..versions.last()).toList()) { "a schema is missing among $versions" }
    return versions
}
