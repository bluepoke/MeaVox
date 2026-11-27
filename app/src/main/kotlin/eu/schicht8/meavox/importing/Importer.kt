// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.importing

import android.content.ContentResolver
import android.net.Uri
import eu.schicht8.meavox.core.library.ImportLocalFile
import eu.schicht8.meavox.core.library.TrackFiles
import eu.schicht8.meavox.core.model.SeparationModelId
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.ui.UiText
import eu.schicht8.meavox.ui.text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Runs imports of picked or shared files in the app-wide [scope], so leaving a screen doesn't
 * cancel them. Imports run one at a time, off the main thread. On start it first deletes
 * staging files of imports a previous process never finished.
 *
 * Not a WorkManager job (see ADR 10 note): the read grant for a picked/shared URI does not
 * survive the process, so a later retry could not read the file anyway.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Importer(
    private val scope: CoroutineScope,
    private val importLocalFile: ImportLocalFile,
    files: TrackFiles,
    private val resolver: ContentResolver,
) {
    sealed interface Result {
        /** The new Track, or the existing one for content imported before. */
        data class Imported(val id: TrackId) : Result
        data class Failed(val reason: UiText) : Result
    }

    private val queue = Dispatchers.IO.limitedParallelism(1)
    private val running = MutableStateFlow(0)
    // Buffered until collected, so results of imports that finish before the UI subscribes
    // (cold-start share, rotation) aren't lost. Each result is delivered once.
    private val _results = Channel<Result>(Channel.BUFFERED)

    val importing: StateFlow<Int> = running
    val results: Flow<Result> = _results.receiveAsFlow()

    init {
        scope.launch(queue) { files.discardAbandonedStaging() }
    }

    /**
     * Imports [uri] as the import sheet asked: with [title] and [artist] (#52), marked [vocalFree]
     * (ADR 14), or to be separated with [model] (ADR 19).
     */
    fun import(uri: Uri, title: String, artist: String?, vocalFree: Boolean, model: SeparationModelId?) {
        running.update { it + 1 }
        scope.launch(queue) {
            val result = try {
                Result.Imported(importLocalFile(UriLocalFile(resolver, uri), vocalFree, model, title, artist).id)
            } catch (e: Exception) {
                Result.Failed(e.text())
            } finally {
                running.update { it - 1 }
            }
            _results.send(result)
        }
    }
}
