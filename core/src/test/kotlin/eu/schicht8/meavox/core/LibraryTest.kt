// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core

import eu.schicht8.meavox.core.library.Library
import eu.schicht8.meavox.core.library.LibraryEntry
import eu.schicht8.meavox.core.library.RenameTrack
import eu.schicht8.meavox.core.mix.Mixes
import eu.schicht8.meavox.core.model.OutputRoute
import eu.schicht8.meavox.core.model.SeparationModelId
import eu.schicht8.meavox.core.model.SourceId
import eu.schicht8.meavox.core.model.Take
import eu.schicht8.meavox.core.model.TakeId
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.model.TrackPipelineState
import eu.schicht8.meavox.core.model.Transpose
import eu.schicht8.meavox.core.session.Sessions
import eu.schicht8.meavox.core.testing.FakeAudioEngine
import eu.schicht8.meavox.core.testing.FakeLoudnessMeter
import eu.schicht8.meavox.core.testing.FakeSource
import eu.schicht8.meavox.core.testing.InMemoryLyricsRepository
import eu.schicht8.meavox.core.testing.InMemoryMixRepository
import eu.schicht8.meavox.core.testing.InMemorySettingsRepository
import eu.schicht8.meavox.core.testing.InMemoryTakeRepository
import eu.schicht8.meavox.core.testing.InMemoryTrackFiles
import eu.schicht8.meavox.core.testing.InMemoryTrackRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryTest {
    private val tracks = InMemoryTrackRepository()
    private val takes = InMemoryTakeRepository()
    private val library = Library(tracks, takes)
    private val source = FakeSource(SourceId("fake")).apply {
        add(sourceTrackId = "v1", title = "Fly Me to the Moon", artist = "Frank Sinatra", durationMs = 148_000)
    }

    @Test
    fun `adding a search result keeps the Separation Model chosen for it`() = runTest {
        val best = SeparationModelId("best")

        val track = library.add(source.search("fly me").single(), separationModel = best)

        assertEquals(best, tracks.get(track.id)?.separationModel)
    }

    @Test
    fun `adding a search result keeps where its Cover Art is, to download it with the Track`() = runTest {
        val candidate = source.add("v2", "My Way", coverUrl = "https://img.example/v2/large.jpg")

        val track = library.add(candidate)

        assertEquals("https://img.example/v2/large.jpg", tracks.get(track.id)?.coverUrl)
    }

    @Test
    fun `a new Library has no Tracks`() = runTest {
        assertEquals(emptyList(), library.tracks().first())
    }

    @Test
    fun `adding a search result puts it into the Library as an Added Track`() = runTest {
        val candidate = source.search("fly me").single()

        library.add(candidate)

        val track = library.tracks().first().single()
        assertEquals("Fly Me to the Moon", track.title)
        assertEquals("Frank Sinatra", track.artist)
        assertEquals(148_000, track.durationMs)
        assertEquals(TrackPipelineState.Added, track.pipeline)
    }

    @Test
    fun `adding the same Source item again returns the existing Track instead of a duplicate`() = runTest {
        val candidate = source.search("sinatra").single()
        val first = library.add(candidate)

        val second = library.add(candidate.copy(title = "Fly Me to the Moon (Remastered)"))

        assertEquals(first, second)
        assertEquals(listOf(first), library.tracks().first())
    }

    @Test
    fun `adding a renamed Track's Source item again keeps the user's name`() = runTest {
        val candidate = source.search("fly me").single()
        val track = library.add(candidate)
        RenameTrack(tracks, InMemoryLyricsRepository())(track.id, title = "Fly Me (rehearsal key)", artist = null)

        library.add(candidate)

        val again = tracks.get(track.id)!!
        assertEquals("Fly Me (rehearsal key)", again.title)
        assertEquals(null, again.artist)
    }

    @Test
    fun `each entry shows the Track Pipeline state, its Take count and when it was last sung`() = runTest {
        val sung = library.add(source.search("fly me").single())
        val other = library.add(source.add(sourceTrackId = "v2", title = "My Way", artist = "Frank Sinatra", durationMs = 275_000))
        tracks.updatePipeline(sung.id, TrackPipelineState.Ready(SeparationModelId("fast")))
        takes.add(take("t1", sung.id))
        takes.add(take("t2", sung.id))
        val files = InMemoryTrackFiles()
        val sessions = Sessions(tracks, takes, files, FakeAudioEngine(), InMemorySettingsRepository(), Mixes(tracks, takes, InMemoryMixRepository(), files, FakeLoudnessMeter()), clock = { SUNG_AT })

        sessions.open(sung.id, backgroundScope).apply {
            record() // sung: a Take started recording
            stop()
            discard()
            close()
        }

        val entries = library.entries().first().associateBy { it.track.id }
        assertEquals(LibraryEntry(tracks.get(sung.id)!!, takeCount = 2), entries[sung.id])
        assertEquals(TrackPipelineState.Ready(SeparationModelId("fast")), entries[sung.id]?.track?.pipeline)
        assertEquals(SUNG_AT, entries[sung.id]?.track?.lastSessionAt)
        assertEquals(0, entries[other.id]?.takeCount)
        assertEquals(null, entries[other.id]?.track?.lastSessionAt)
    }

    private fun take(id: String, track: TrackId) =
        Take(TakeId(id), track, id, 0, 48_000, 48_000, OutputRoute.Wired, 0, Transpose.None, recordedAt = 0)

    private companion object {
        const val SUNG_AT = 1_700_000_000_000L
    }
}
