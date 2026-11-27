// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.ui.models

import eu.schicht8.meavox.core.lyrics.CleanTitle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Seam 4 of #52: the import sheet's title and artist fields, and a suggestion that comes later. */
class NameFieldsTest {
    @Test
    fun `the fields start with the suggestion`() {
        val fields = NameFields(CleanTitle("Phil Collins", "In The Air Tonight"))

        assertEquals("In The Air Tonight", fields.title)
        assertEquals("Phil Collins", fields.artist)
    }

    @Test
    fun `a later suggestion fills the fields the user hasn't edited, and leaves the edited ones`() {
        val fields = NameFields(CleanTitle("phil collins", "in the air tonight"))
        fields.editArtist("Phil Collins & Band")

        fields.suggest(CleanTitle("Phil Collins", "In the Air Tonight"))

        assertEquals("In the Air Tonight", fields.title)
        assertEquals("Phil Collins & Band", fields.artist)
    }

    @Test
    fun `once the user changed the title, a later suggestion is about another song and changes nothing`() {
        val fields = NameFields(CleanTitle("Phil Collins", "in the air tonight"))
        fields.editTitle("Another Day in Paradise")

        fields.suggest(CleanTitle("Phil Collins (UK)", "In the Air Tonight"))

        assertEquals("Another Day in Paradise", fields.title)
        assertEquals("Phil Collins", fields.artist)
    }

    @Test
    fun `adding waits until the file's tags are read, if they are still to come`() {
        val fields = NameFields(CleanTitle(null, "my way"), tagsToCome = true)
        assertFalse(fields.canAdd)

        fields.tagsRead(CleanTitle("Frank Sinatra", "My Way"))

        assertTrue(fields.canAdd)
        assertEquals("My Way", fields.title)
    }

    @Test
    fun `a field edited back to its suggestion still counts as edited`() {
        val fields = NameFields(CleanTitle(null, "My Way"))
        fields.editArtist("x")
        fields.editArtist("")

        fields.suggest(CleanTitle("Frank Sinatra", "My Way"))

        assertEquals("", fields.artist)
    }

    @Test
    fun `adding needs a title, and takes the names trimmed, an empty artist as none`() {
        val fields = NameFields(CleanTitle(null, "My Way"))
        fields.editTitle("   ")
        assertFalse(fields.canAdd)

        fields.editTitle("  My Way ")
        fields.editArtist("  ")

        assertTrue(fields.canAdd)
        assertEquals("My Way", fields.addedTitle)
        assertNull(fields.addedArtist)
    }
}
