// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.ui.lyrics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import eu.schicht8.meavox.core.lyrics.MatchLyrics
import eu.schicht8.meavox.core.lyrics.pageText
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.port.LyricsCandidate
import eu.schicht8.meavox.core.port.LyricsQuery
import eu.schicht8.meavox.core.port.LyricsUnavailable
import eu.schicht8.meavox.ui.UiText
import eu.schicht8.meavox.ui.resolve
import eu.schicht8.meavox.ui.text
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Searching LRCLIB by hand and picking the Lyrics for a Track (ADR 6). */
class LyricsSearchViewModel(private val id: TrackId, private val match: MatchLyrics) : ViewModel() {
    private val _artist = MutableStateFlow("")
    val artist: StateFlow<String> = _artist
    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title
    private val _results = MutableStateFlow<List<LyricsCandidate>?>(null)
    /** Null until a search finished. */
    val results: StateFlow<List<LyricsCandidate>?> = _results
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching
    private val _error = MutableStateFlow<UiText?>(null)
    val error: StateFlow<UiText?> = _error
    private var duration: Long? = null

    init {
        // Starts with the last search (or the cleaned title) and shows its results at once.
        viewModelScope.launch {
            match.searchQuery(id)?.let {
                _artist.value = it.artist.orEmpty()
                _title.value = it.title
                duration = it.durationMs
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
        if (title.isEmpty() || _searching.value) return
        _searching.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                _results.value = match.search(LyricsQuery(_artist.value.trim().ifEmpty { null }, title, duration))
            } catch (e: LyricsUnavailable) {
                _error.value = e.reason.text()
            } finally {
                _searching.value = false
            }
        }
    }

    fun choose(candidate: LyricsCandidate, then: () -> Unit) {
        viewModelScope.launch {
            match.choose(id, candidate)
            then()
        }
    }
}

@Composable
fun LyricsSearchRoute(id: TrackId, match: MatchLyrics, onChosen: () -> Unit) {
    val viewModel: LyricsSearchViewModel = viewModel(factory = viewModelFactory { initializer { LyricsSearchViewModel(id, match) } })
    val artist by viewModel.artist.collectAsStateWithLifecycle()
    val title by viewModel.title.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    LyricsSearchScreen(
        artist, title, results, searching, error,
        onArtist = viewModel::setArtist,
        onTitle = viewModel::setTitle,
        onSearch = viewModel::search,
        onChoose = { viewModel.choose(it, onChosen) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsSearchScreen(
    artist: String,
    title: String,
    results: List<LyricsCandidate>?,
    searching: Boolean,
    error: UiText?,
    onArtist: (String) -> Unit,
    onTitle: (String) -> Unit,
    onSearch: () -> Unit,
    onChoose: (LyricsCandidate) -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.lyrics_search_title)) }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val search = KeyboardActions(onSearch = { onSearch() })
            val options = KeyboardOptions(imeAction = ImeAction.Search)
            OutlinedTextField(artist, onArtist, label = { Text(stringResource(R.string.lyrics_search_artist)) }, singleLine = true, keyboardOptions = options, keyboardActions = search, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(title, onTitle, label = { Text(stringResource(R.string.lyrics_search_song_title)) }, singleLine = true, keyboardOptions = options, keyboardActions = search, modifier = Modifier.fillMaxWidth())
            Button(onClick = onSearch, enabled = title.isNotBlank() && !searching) { Text(stringResource(R.string.action_search)) }
            when {
                searching -> LinearProgressIndicator(Modifier.fillMaxWidth())
                error != null -> Text(stringResource(R.string.lyrics_search_failed, error.resolve()), color = MaterialTheme.colorScheme.error)
                results?.isEmpty() == true -> Text(stringResource(R.string.lyrics_search_nothing_found))
            }
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(results.orEmpty(), key = { it.providerItemId }) { candidate ->
                    ListItem(
                        headlineContent = { Text(candidate.name()) },
                        supportingContent = {
                            Column {
                                Text(candidate.details(), style = MaterialTheme.typography.labelMedium)
                                Text(candidate.pageText(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        },
                        modifier = Modifier.clickable { onChoose(candidate) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
