// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.importing

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import eu.schicht8.meavox.core.library.SuggestTitle
import eu.schicht8.meavox.core.lyrics.CleanTitle
import eu.schicht8.meavox.core.port.FileTags
import eu.schicht8.meavox.core.port.SourceCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the import sheet suggests as title and artist (#52): a [first] guess at once; for a file, the
 * guess [fromTags] gives once its tags are read; and [checked], the MusicBrainz recording that is
 * clearly the same song, if any.
 */
class TitleSuggestion(
    val first: CleanTitle,
    val fromTags: (suspend () -> CleanTitle)?,
    val checked: suspend (CleanTitle) -> CleanTitle?,
)

/** Builds the import sheet's suggestions from [suggest], reading a picked file's tags from [context]. */
class TitleSuggestions(private val context: Context, private val suggest: SuggestTitle) {
    /** For a search result or a shared link: the cleaned title and channel, then MusicBrainz. */
    fun forSource(candidate: SourceCandidate): TitleSuggestion =
        TitleSuggestion(suggest.forSource(candidate), fromTags = null, checked = suggest::checked)

    /** For a picked or shared file: its cleaned name, then its tags if it has them, then MusicBrainz. */
    fun forFile(uri: Uri, displayName: String?): TitleSuggestion = TitleSuggestion(
        suggest.forFile(displayName, null),
        fromTags = { suggest.forFile(displayName, tags(uri)) },
        checked = suggest::checked,
    )

    /** The title and artist [uri]'s file carries, if it can be read and has any. */
    private suspend fun tags(uri: Uri): FileTags? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            FileTags(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
            )
        } catch (_: RuntimeException) {
            null // a file the retriever can't read: its name will do
        } finally {
            retriever.release()
        }
    }
}
