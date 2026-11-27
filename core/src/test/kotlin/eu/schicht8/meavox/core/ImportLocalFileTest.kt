// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core

import eu.schicht8.meavox.core.library.ImportLocalFile
import eu.schicht8.meavox.core.library.Library
import eu.schicht8.meavox.core.library.RenameTrack
import eu.schicht8.meavox.core.model.CoverArt
import eu.schicht8.meavox.core.model.SeparationModelId
import eu.schicht8.meavox.core.model.SourceId
import eu.schicht8.meavox.core.model.Track
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.model.TrackPipelineState
import eu.schicht8.meavox.core.testing.FakeCoverArtLoader
import eu.schicht8.meavox.core.testing.FakeLocalFile
import eu.schicht8.meavox.core.testing.InMemoryLyricsRepository
import eu.schicht8.meavox.core.testing.InMemoryTrackFiles
import eu.schicht8.meavox.core.testing.InMemoryTakeRepository
import eu.schicht8.meavox.core.testing.InMemoryTrackRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class ImportLocalFileTest {
    private val tracks = InMemoryTrackRepository()
    private val files = InMemoryTrackFiles()
    private val covers = FakeCoverArtLoader()
    private val import = ImportLocalFile(tracks, files, covers)
    private val library = Library(tracks, InMemoryTakeRepository())

    @Test
    fun `the picture an imported file embeds becomes the Track's Cover Art`() = runTest {
        val file = FakeLocalFile(displayName = "My Way.mp3", content = "audio-1")
        val id = TrackId(SourceId.Local, sha256("audio-1"))
        val cover = CoverArt(byteArrayOf(9, 8, 7))
        covers.embed(files.originalAudio(id), cover)

        val track = import(file)

        assertEquals(id, track.id)
        assertEquals(cover, files.cover(track.id))
    }

    @Test
    fun `a file without a picture is imported without Cover Art`() = runTest {
        val track = import(FakeLocalFile(displayName = "My Way.mp3", content = "audio-1"))

        assertEquals(TrackPipelineState.Downloaded, track.pipeline)
        assertNull(files.cover(track.id))
    }

    @Test
    fun `importing a file adds a Downloaded Track from the Local File Source named after the file`() = runTest {
        val track = import(FakeLocalFile(displayName = "My Way (demo).mp3", content = "audio-1"))

        assertEquals(SourceId.Local, track.id.source)
        assertEquals("My Way (demo)", track.title)
        assertEquals(TrackPipelineState.Downloaded, track.pipeline)
        assertEquals(listOf(track), library.tracks().first())
    }

    @Test
    fun `an imported file keeps the Separation Model chosen for it`() = runTest {
        val best = SeparationModelId("best")

        val track = import(FakeLocalFile(displayName = "song.mp3", content = "audio-1"), separationModel = best)

        assertEquals(best, tracks.get(track.id)?.separationModel)
    }

    @Test
    fun `re-importing the same content opens the existing Track instead of adding a duplicate`() = runTest {
        val first = import(FakeLocalFile(displayName = "take-five.flac", content = "audio-1"))

        val again = import(FakeLocalFile(displayName = "Take Five (copy).flac", content = "audio-1"))

        assertEquals(first, again)
        assertEquals(listOf(first), library.tracks().first())
        assertEquals(emptySet(), files.pendingStaged)
    }

    @Test
    fun `re-importing a renamed Track's file keeps the user's name`() = runTest {
        val track = import(FakeLocalFile(displayName = "take-five.flac", content = "audio-1"))
        RenameTrack(tracks, InMemoryLyricsRepository())(track.id, title = "Take Five", artist = "Dave Brubeck")

        import(FakeLocalFile(displayName = "take-five.flac", content = "audio-1"))

        val again = tracks.get(track.id)!!
        assertEquals("Take Five", again.title)
        assertEquals("Dave Brubeck", again.artist)
    }

    @Test
    fun `files with the same name but different content become different Tracks`() = runTest {
        val a = import(FakeLocalFile(displayName = "song.mp3", content = "audio-1"))
        val b = import(FakeLocalFile(displayName = "song.mp3", content = "audio-2"))

        assertNotEquals(a.id, b.id)
        assertEquals(2, library.tracks().first().size)
    }

    @Test
    fun `re-importing completes an import that was interrupted before the file was stored`() = runTest {
        val content = "audio-1"
        val interrupted = tracks.addIfAbsent(
            Track(TrackId(SourceId.Local, sha256(content)), "lost", null, null, TrackPipelineState.Added),
        )

        val track = import(FakeLocalFile(displayName = "found.wav", content = content))

        assertEquals(interrupted.id, track.id)
        assertEquals(TrackPipelineState.Downloaded, track.pipeline)
        assertEquals(content, files.originalAudioContent(track.id)?.decodeToString())
    }

    private fun sha256(text: String) =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
