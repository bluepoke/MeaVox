// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.ui.models

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import eu.schicht8.meavox.core.lyrics.CleanTitle

/**
 * The import sheet's Title and Artist fields (#52): they start with a suggestion, and a better one that
 * comes later (a file's tags, then MusicBrainz) fills the fields the user hasn't edited, as long as the
 * title is still untouched: once the user changed it, a suggestion is about another song. With
 * [tagsToCome], adding waits for a file's tags, which come first if it has them.
 */
class NameFields(suggestion: CleanTitle, tagsToCome: Boolean = false) {
    var title by mutableStateOf(suggestion.title)
        private set
    var artist by mutableStateOf(suggestion.artist.orEmpty())
        private set
    private var titleEdited = false
    private var artistEdited = false
    private var waitingForTags by mutableStateOf(tagsToCome)

    fun editTitle(value: String) {
        title = value
        titleEdited = true
    }

    fun editArtist(value: String) {
        artist = value
        artistEdited = true
    }

    /** A later, better suggestion; it doesn't touch what the user typed. */
    fun suggest(better: CleanTitle) {
        if (titleEdited) return
        title = better.title
        if (!artistEdited) artist = better.artist.orEmpty()
    }

    /** The suggestion from a file's tags (or its name again, without tags); adding needn't wait any more. */
    fun tagsRead(suggestion: CleanTitle) {
        suggest(suggestion)
        waitingForTags = false
    }

    /** A Track needs a title, and a file's tags are read first. */
    val canAdd: Boolean get() = title.isNotBlank() && !waitingForTags

    /** The title the Track is added with. */
    val addedTitle: String get() = title.trim()

    /** The artist the Track is added with; none for an empty field. */
    val addedArtist: String? get() = artist.trim().ifEmpty { null }
}
