// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core

import eu.schicht8.meavox.core.library.SuggestTitle
import eu.schicht8.meavox.core.lyrics.CleanTitle
import eu.schicht8.meavox.core.model.SourceId
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.port.FileTags
import eu.schicht8.meavox.core.port.RecordingLookup
import eu.schicht8.meavox.core.port.RecordingMatch
import eu.schicht8.meavox.core.port.SourceCandidate
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Seam 2 of #52: what the import sheet suggests as title and artist: cleaned at once, and replaced by a
 * MusicBrainz recording only when it is clearly the same song.
 */
class SuggestTitleTest {
    private class FakeLookup(private val matches: List<RecordingMatch> = emptyList(), private val fail: Exception? = null) : RecordingLookup {
        val asked = mutableListOf<Pair<String, String?>>()

        override suspend fun recordings(title: String, artist: String?): List<RecordingMatch> {
            asked += title to artist
            fail?.let { throw it }
            return matches
        }
    }

    private fun suggest(lookup: RecordingLookup = FakeLookup()) = SuggestTitle(lookup)

    @Test
    fun `a music video's title and channel are cleaned into song and artist`() {
        val candidate = SourceCandidate(
            TrackId(SourceId("youtube"), "x"),
            "Phil Collins - In The Air Tonight (Official Music Video) [HD Upgrade]",
            "PhilCollinsVEVO",
            null,
        )

        assertEquals(CleanTitle("Phil Collins", "In The Air Tonight"), suggest().forSource(candidate))
    }

    @Test
    fun `a file's own tags come first, its cleaned name otherwise`() {
        assertEquals(CleanTitle("Frank Sinatra", "My Way"), suggest().forFile("02 - my_way.mp3", FileTags("My Way", "Frank Sinatra")))
        assertEquals(CleanTitle(null, "my way"), suggest().forFile("02 - my_way.mp3", null))
        assertEquals(CleanTitle("Frank Sinatra", "My Way"), suggest().forFile("Frank Sinatra - My Way (Official Audio).m4a", FileTags(" ", null)))
        assertEquals(CleanTitle(null, "Untitled"), suggest().forFile(null, null))
    }

    @Test
    fun `a recording that is clearly the same song gives its spelling and the artist's proper name`() = runTest {
        val lookup = FakeLookup(listOf(RecordingMatch("In the Air Tonight", "Phil Collins", 100)))

        val checked = suggest(lookup).checked(CleanTitle("phil collins", "in the air tonight"))

        assertEquals(CleanTitle("Phil Collins", "In the Air Tonight"), checked)
        assertEquals(listOf<Pair<String, String?>>("in the air tonight" to "phil collins"), lookup.asked)
    }

    @Test
    fun `a weak match, another title or another artist changes nothing`() = runTest {
        val clean = CleanTitle("Wham!", "Last Christmas")

        assertNull(suggest(FakeLookup(listOf(RecordingMatch("Last Christmas", "Wham!", 70)))).checked(clean))
        assertNull(suggest(FakeLookup(listOf(RecordingMatch("Last Christmas (Pudding mix)", "Wham!", 100)))).checked(clean))
        assertNull(suggest(FakeLookup(listOf(RecordingMatch("Last Christmas", "Taylor Swift", 100)))).checked(clean))
    }

    @Test
    fun `spelling, accents and punctuation don't count as another title or artist`() = runTest {
        val lookup = FakeLookup(listOf(RecordingMatch("Für Elise", "Björk", 95)))

        assertEquals(CleanTitle("Björk", "Für Elise"), suggest(lookup).checked(CleanTitle("BJORK", "fur elise!")))
    }

    @Test
    fun `without an artist, a match counts only when every match of that title has the same artist`() = runTest {
        val one = FakeLookup(listOf(RecordingMatch("Bohemian Rhapsody", "Queen", 100), RecordingMatch("Bohemian Rhapsody", "Queen", 98)))
        val several = FakeLookup(listOf(RecordingMatch("My Way", "Frank Sinatra", 100), RecordingMatch("My Way", "Elvis Presley", 100)))

        assertEquals(CleanTitle("Queen", "Bohemian Rhapsody"), suggest(one).checked(CleanTitle(null, "bohemian rhapsody")))
        assertNull(suggest(several).checked(CleanTitle(null, "My Way")))
    }

    @Test
    fun `offline or without any match the cleaned suggestion stays`() = runTest {
        assertNull(suggest(FakeLookup(fail = IOException("offline"))).checked(CleanTitle("Wham!", "Last Christmas")))
        assertNull(suggest(FakeLookup()).checked(CleanTitle("Wham!", "Last Christmas")))
    }
}
