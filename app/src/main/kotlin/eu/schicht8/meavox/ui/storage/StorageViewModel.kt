// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.ui.storage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.schicht8.meavox.core.library.TrackStorage
import eu.schicht8.meavox.core.library.TrackUsage
import eu.schicht8.meavox.core.model.SeparationModelId
import eu.schicht8.meavox.core.model.Take
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.ui.UiText
import eu.schicht8.meavox.ui.text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The storage screen (ADR 8): usage per Track, measured again after each deletion. */
class StorageViewModel(private val storage: TrackStorage) : ViewModel() {
    private val _usage = MutableStateFlow<List<TrackUsage>?>(null)

    /** Every Track's usage, largest first; null while measuring for the first time. */
    val usage: StateFlow<List<TrackUsage>?> = _usage

    private val _message = MutableStateFlow<UiText?>(null)

    /** A one-off message for the user (shown once, then [messageShown]). */
    val message: StateFlow<UiText?> = _message

    init {
        runAndMeasure {}
    }

    fun deleteStems(track: TrackId, model: SeparationModelId) = runAndMeasure { storage.deleteStems(track, model) }

    fun deleteTake(take: Take) = runAndMeasure { storage.deleteTake(take) }

    fun deleteTrack(track: TrackId) = runAndMeasure { storage.deleteTrack(track) }

    fun messageShown() {
        _message.value = null
    }

    /** Runs [block], shows its error if it fails, then measures the usage again. */
    private fun runAndMeasure(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = e.text()
            }
            _usage.value = storage.usage()
        }
    }
}
