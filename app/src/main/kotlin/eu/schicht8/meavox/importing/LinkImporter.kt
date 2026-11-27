// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.importing

import androidx.annotation.StringRes
import eu.schicht8.meavox.R
import eu.schicht8.meavox.core.library.Library
import eu.schicht8.meavox.core.library.LookUpSharedLink
import eu.schicht8.meavox.core.library.TrackRepository
import eu.schicht8.meavox.core.model.FailureReason
import eu.schicht8.meavox.core.model.SeparationModelId
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.port.MediaUnavailable
import eu.schicht8.meavox.core.port.SourceCandidate
import eu.schicht8.meavox.ui.UiText
import eu.schicht8.meavox.ui.text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Adds the songs behind links shared into the app (network flavor), outliving any screen: the link
 * is looked up, the import sheet asks how to add it (ADR 19), then it is added like a search result.
 */
class LinkImporter(
    private val scope: CoroutineScope,
    private val lookUpSharedLink: LookUpSharedLink,
    private val tracks: TrackRepository,
    private val library: Library,
) {
    sealed interface Result {
        /** The song is new: the import sheet asks how to add it. */
        data class Found(val candidate: SourceCandidate) : Result

        /** The song was added now, or before (it opens unchanged). */
        data class Added(val id: TrackId) : Result

        /** No Source knows the link, or it holds no link at all. */
        data object Unsupported : Result

        data class Failed(val reason: UiText) : Result
    }

    // Buffered until collected: a share that starts the app arrives before the screen listens.
    private val _results = Channel<Result>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val results: Flow<Result> = _results.receiveAsFlow()

    /** Looks up [text] as shared, e.g. "Check this out https://youtu.be/…": the first link in it counts. */
    fun lookUp(text: String) {
        val url = link.find(text)?.value?.trimEnd(*TRAILING_PUNCTUATION) ?: return run { _results.trySend(Result.Unsupported) }
        report(R.string.link_cant_open) {
            val candidate = lookUpSharedLink(url) ?: return@report Result.Unsupported
            // Added before: it opens as it is, without asking again.
            if (tracks.get(candidate.id) != null) Result.Added(candidate.id) else Result.Found(candidate)
        }
    }

    /**
     * Adds [candidate] as the import sheet asked: with [title] and [artist] (#52), marked [vocalFree]
     * (ADR 14), or to be separated with [model] (ADR 19).
     */
    fun add(candidate: SourceCandidate, title: String, artist: String?, vocalFree: Boolean, model: SeparationModelId?) {
        report(R.string.link_cant_add) { Result.Added(library.add(candidate, vocalFree, model, title, artist).id) }
    }

    private fun report(@StringRes failure: Int, block: suspend () -> Result) {
        scope.launch {
            val result = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val why = if (e is MediaUnavailable) e.reason else FailureReason.unexpected(e)
                Result.Failed(UiText.of(R.string.failure_with_detail, UiText.of(failure), why.text()))
            }
            _results.send(result)
        }
    }

    private companion object {
        val link = Regex("""https?://\S+""")

        /** Punctuation that ends a sentence around a link rather than the link itself. */
        val TRAILING_PUNCTUATION = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '"', '\'', '»', '“', '”')
    }
}
