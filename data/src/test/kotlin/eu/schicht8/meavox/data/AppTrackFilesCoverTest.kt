// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.data

import eu.schicht8.meavox.core.model.CoverArt
import eu.schicht8.meavox.core.model.SourceId
import eu.schicht8.meavox.core.model.TrackId
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Seam 2 of #49: the stored Cover Art tells a new picture from the old one, and says when it changed. */
class AppTrackFilesCoverTest {
    private val root = Files.createTempDirectory("tracks").toFile()
    private val files = AppTrackFiles(root)
    private val track = TrackId(SourceId("youtube"), "abc")

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `a Track has no cover version until a cover is stored, and a new one each time one is`() = runBlocking<Unit> {
        assertNull(files.coverVersion(track))

        val versions = (1..5).map { i ->
            files.storeCover(track, CoverArt(byteArrayOf(i.toByte())))
            assertNotNull(files.coverVersion(track))
        }

        assertEquals(versions.size, versions.toSet().size, "every cover stored, even in the same millisecond, is a new version: $versions")
        assertEquals(CoverArt(byteArrayOf(5)), files.cover(track))
    }

    @Test
    fun `deleting the Track removes its cover version`() = runBlocking<Unit> {
        files.storeCover(track, CoverArt(byteArrayOf(1)))

        files.deleteAll(track)

        assertNull(files.coverVersion(track))
    }

    @Test
    fun `storing a cover and deleting a Track are announced`() = runBlocking<Unit> {
        val before = files.coversChanged.value

        files.storeCover(track, CoverArt(byteArrayOf(1)))
        val stored = files.coversChanged.value
        files.deleteAll(track)

        assertNotEquals(before, stored)
        assertNotEquals(stored, files.coversChanged.value)
    }

    @Test
    fun `a cover stored by an earlier process keeps its version`() = runBlocking<Unit> {
        files.storeCover(track, CoverArt(byteArrayOf(1)))
        val version = files.coverVersion(track)

        assertEquals(version, AppTrackFiles(root).coverVersion(track))
        assertNotEquals(null, version)
    }
}
