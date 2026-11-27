// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.ui.covers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import eu.schicht8.meavox.R
import eu.schicht8.meavox.core.library.ChooseCover
import eu.schicht8.meavox.core.model.SourceId
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.port.CoverCandidate
import eu.schicht8.meavox.core.port.CoverSearch
import eu.schicht8.meavox.core.port.CoverSearchUnavailable
import eu.schicht8.meavox.core.port.NoPicture
import eu.schicht8.meavox.ui.UiText
import eu.schicht8.meavox.ui.resolve
import eu.schicht8.meavox.ui.search.RemoteImage
import eu.schicht8.meavox.ui.text
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Searching MusicBrainz for a Track's Cover Art by hand and picking one (#51). */
class CoverSearchViewModel(private val id: TrackId, private val search: CoverSearch, private val choose: ChooseCover) : ViewModel() {
    private val _artist = MutableStateFlow("")
    val artist: StateFlow<String> = _artist
    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title
    private val _results = MutableStateFlow<List<CoverCandidate>?>(null)
    /** Null until a search finished. */
    val results: StateFlow<List<CoverCandidate>?> = _results
    private val _busy = MutableStateFlow(false)
    /** A search runs, or a picked cover is being stored. */
    val busy: StateFlow<Boolean> = _busy
    private val _error = MutableStateFlow<UiText?>(null)
    val error: StateFlow<UiText?> = _error

    init {
        // Starts with the Track's cleaned title and artist, and shows the results at once.
        viewModelScope.launch {
            choose.searchTerms(id)?.let {
                _artist.value = it.artist.orEmpty()
                _title.value = it.title
                search()
            }
        }
    }

    fun setArtist(value: String) {
        _artist.value = value
    }

    fun setTitle(value: String) {
        _title.value = value
    }

    fun search() {
        val title = _title.value.trim()
        if (title.isEmpty() || _busy.value) return
        whileBusy {
            try {
                _results.value = search.search(title, _artist.value.trim().ifEmpty { null })
            } catch (e: CoverSearchUnavailable) {
                _error.value = UiText.of(R.string.cover_search_failed, e.reason.text())
            }
        }
    }

    fun pick(candidate: CoverCandidate, then: () -> Unit) = whileBusy {
        try {
            choose.pick(id, candidate)
            then()
        } catch (_: NoPicture) {
            _error.value = UiText.of(R.string.cover_no_picture)
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            _error.value = UiText.of(R.string.cover_download_failed)
        }
    }

    fun useAutomatic(then: () -> Unit) = whileBusy {
        changing {
            when (choose.useAutomatic(id)) {
                ChooseCover.Outcome.Done -> then()
                ChooseCover.Outcome.SourceUnreachable -> _error.value = UiText.of(R.string.cover_source_unreachable)
            }
        }
    }

    fun remove(then: () -> Unit) = whileBusy {
        changing {
            choose.remove(id)
            then()
        }
    }

    /** Runs [change], showing that the cover couldn't be changed if it fails. */
    private suspend fun changing(change: suspend () -> Unit) {
        try {
            change()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _error.value = UiText.of(R.string.cover_change_failed)
        }
    }

    /** Runs [block] while [busy], after clearing the last error; does nothing while busy. */
    private fun whileBusy(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                block()
            } finally {
                _busy.value = false
            }
        }
    }
}

@Composable
fun CoverSearchRoute(id: TrackId, search: CoverSearch, choose: ChooseCover, onDone: () -> Unit) {
    val viewModel: CoverSearchViewModel = viewModel(factory = viewModelFactory { initializer { CoverSearchViewModel(id, search, choose) } })
    val artist by viewModel.artist.collectAsStateWithLifecycle()
    val title by viewModel.title.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    CoverSearchScreen(
        artist, title, results, busy, error,
        fromFile = id.source == SourceId.Local,
        actions = CoverSearchActions(
            onArtist = viewModel::setArtist,
            onTitle = viewModel::setTitle,
            onSearch = viewModel::search,
            onPick = { viewModel.pick(it, onDone) },
            onUseAutomatic = { viewModel.useAutomatic(onDone) },
            onRemove = { viewModel.remove(onDone) },
        ),
    )
}

class CoverSearchActions(
    val onArtist: (String) -> Unit,
    val onTitle: (String) -> Unit,
    val onSearch: () -> Unit,
    val onPick: (CoverCandidate) -> Unit,
    val onUseAutomatic: () -> Unit,
    val onRemove: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoverSearchScreen(
    artist: String,
    title: String,
    results: List<CoverCandidate>?,
    busy: Boolean,
    error: UiText?,
    /** The Track was imported, so its automatic cover is the file's picture, not the Source's. */
    fromFile: Boolean,
    actions: CoverSearchActions,
) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.cover_search_title)) }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val search = KeyboardActions(onSearch = { actions.onSearch() })
            val options = KeyboardOptions(imeAction = ImeAction.Search)
            OutlinedTextField(
                artist, actions.onArtist, label = { Text(stringResource(R.string.lyrics_search_artist)) },
                singleLine = true, keyboardOptions = options, keyboardActions = search, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                title, actions.onTitle, label = { Text(stringResource(R.string.lyrics_search_song_title)) },
                singleLine = true, keyboardOptions = options, keyboardActions = search, modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions.onSearch, enabled = title.isNotBlank() && !busy) { Text(stringResource(R.string.action_search)) }
                TextButton(onClick = actions.onUseAutomatic, enabled = !busy) {
                    Text(stringResource(if (fromFile) R.string.cover_use_file else R.string.cover_use_source))
                }
                TextButton(onClick = actions.onRemove, enabled = !busy) { Text(stringResource(R.string.cover_remove)) }
            }
            when {
                busy -> LinearProgressIndicator(Modifier.fillMaxWidth())
                error != null -> Text(error.resolve(), color = MaterialTheme.colorScheme.error)
                results?.isEmpty() == true -> Text(stringResource(R.string.cover_search_nothing_found))
            }
            Text(
                stringResource(R.string.cover_search_source),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyVerticalGrid(columns = GridCells.Adaptive(140.dp), modifier = Modifier.weight(1f)) {
                items(results.orEmpty(), key = { it.id }) { candidate ->
                    Column(
                        Modifier.padding(4.dp).clickable(enabled = !busy) { actions.onPick(candidate) }.padding(4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        RemoteImage(candidate.previewUrl, Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.small))
                        Text(candidate.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(candidate.artist, candidate.year?.toString()).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
