// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.library

import eu.schicht8.meavox.core.lyrics.CleanTitle
import eu.schicht8.meavox.core.lyrics.TitleCleaner
import eu.schicht8.meavox.core.port.FileTags
import eu.schicht8.meavox.core.port.RecordingLookup
import eu.schicht8.meavox.core.port.RecordingMatch
import eu.schicht8.meavox.core.port.SourceCandidate
import java.text.Normalizer
import kotlinx.coroutines.CancellationException

/**
 * What the import sheet suggests as a song's title and artist (#52): cleaned at once, offline, from what
 * the Source or the file says; then checked against MusicBrainz, whose recording replaces it only when
 * it is clearly the same song.
 */
class SuggestTitle(private val lookup: RecordingLookup) {
    /** A Source item's title and artist (e.g. a music video's title and channel), cleaned. */
    fun forSource(candidate: SourceCandidate): CleanTitle = TitleCleaner.clean(candidate.title, candidate.artist)

    /**
     * A file's: its own [tags] if they have a title, otherwise its [displayName] without the extension,
     * a leading track number and underscores, cleaned.
     */
    fun forFile(displayName: String?, tags: FileTags?): CleanTitle {
        val tagged = tags?.title?.trim()?.ifEmpty { null }
        if (tagged != null) return CleanTitle(tags.artist?.trim()?.ifEmpty { null }, tagged)
        val name = displayName?.substringBeforeLast('.')?.replace('_', ' ')?.replace(trackNumber, "")?.trim()?.ifEmpty { null }
            ?: return CleanTitle(null, UNTITLED)
        return TitleCleaner.clean(name, null)
    }

    /**
     * The recording that is clearly [suggestion]'s song, as MusicBrainz spells it, or null to keep the
     * suggestion: no match, a weak one, another title, another artist, or no answer at all. Without an
     * artist, a match counts only when every match of that title is by the same artist.
     */
    suspend fun checked(suggestion: CleanTitle): CleanTitle? {
        val matches = try {
            lookup.recordings(suggestion.title, suggestion.artist)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        val title = normalized(suggestion.title)
        val sameTitle = matches.filter { it.score >= MIN_SCORE && normalized(it.title) == title }
        val match: RecordingMatch = if (suggestion.artist != null) {
            val artist = normalized(suggestion.artist)
            sameTitle.firstOrNull { it.artist != null && normalized(it.artist) == artist }
        } else {
            sameTitle.firstOrNull()?.takeIf { first -> sameTitle.all { it.artist != null && normalized(it.artist) == normalized(first.artist.orEmpty()) } }
        } ?: return null
        return CleanTitle(match.artist, match.title)
    }

    /** Letters and digits only, lower case, without accents: "Für Elise!" and "fur elise" are the same. */
    private fun normalized(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(marks, "").lowercase().filter { it.isLetterOrDigit() }

    private companion object {
        const val MIN_SCORE = 90
        const val UNTITLED = "Untitled"
        val trackNumber = Regex("""^\d{1,3}\s*[-.)]?\s+""")
        val marks = Regex("""\p{Mn}+""")
    }
}
