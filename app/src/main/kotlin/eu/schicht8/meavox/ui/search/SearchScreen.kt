// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import eu.schicht8.meavox.R
import eu.schicht8.meavox.core.library.Library
import eu.schicht8.meavox.core.model.SeparationModelId
import eu.schicht8.meavox.core.model.SourceId
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.port.MediaUnavailable
import eu.schicht8.meavox.core.port.Source
import eu.schicht8.meavox.core.port.SourceCandidate
import eu.schicht8.meavox.separating.ModelChoices
import eu.schicht8.meavox.ui.UiText
import eu.schicht8.meavox.ui.formatTime
import eu.schicht8.meavox.ui.models.ImportChoice
import eu.schicht8.meavox.ui.models.ImportOptions
import eu.schicht8.meavox.ui.models.importLabel
import eu.schicht8.meavox.ui.models.rememberImportChoice
import eu.schicht8.meavox.ui.resolve
import eu.schicht8.meavox.ui.text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Searching the network Sources, previewing a result and adding it as a Track (ADR 1). */
class SearchViewModel(private val sources: List<Source>, private val library: Library) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query
    private val _selected = MutableStateFlow(setOfNotNull(sources.firstOrNull()?.id))
    /** The Sources to search; the first one alone at the start. */
    val selected: StateFlow<Set<SourceId>> = _selected
    private val _results = MutableStateFlow<List<SourceCandidate>?>(null)
    val results: StateFlow<List<SourceCandidate>?> = _results
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching
    private val _errors = MutableStateFlow<List<UiText>>(emptyList())
    /** One line per Source that couldn't be searched. */
    val errors: StateFlow<List<UiText>> = _errors
    private val _preview = MutableStateFlow<SourceCandidate?>(null)
    val preview: StateFlow<SourceCandidate?> = _preview
    val player = PreviewPlayer()

    fun setQuery(value: String) {
        _query.value = value
    }

    fun toggle(source: SourceId) {
        val now = _selected.value
        _selected.value = if (source in now) (now - source).ifEmpty { now } else now + source
    }

    fun search() {
        val query = _query.value.trim()
        if (query.isEmpty() || _searching.value) return
        _searching.value = true
        viewModelScope.launch {
            val chosen = sources.filter { it.id in _selected.value }
            val outcomes = chosen.map { source ->
                async { runCatching { source.search(query) }.onFailure { if (it is CancellationException) throw it } }
            }.awaitAll()
            _results.value = outcomes.flatMap { it.getOrDefault(emptyList()) }
            _errors.value = outcomes.zip(chosen).mapNotNull { (outcome, source) ->
                outcome.exceptionOrNull()?.let { UiText.of(R.string.search_failed_at, source.name, it.reasonText()) }
            }
            _searching.value = false
        }
    }

    private var resolving: Job? = null

    /**
     * A result was tapped: one added before opens its Track as it is, without the import options
     * (as a shared link to it does, ADR 14); any other opens its preview.
     */
    fun choose(candidate: SourceCandidate, open: (TrackId) -> Unit) {
        viewModelScope.launch {
            if (library.contains(candidate.id)) open(candidate.id) else preview(candidate)
        }
    }

    /** Opens the preview of [candidate] and starts streaming its audio. */
    fun preview(candidate: SourceCandidate) {
        resolving?.cancel() // an earlier preview still resolving must not start playing
        player.stop()
        _preview.value = candidate
        resolving = viewModelScope.launch {
            val source = sources.first { it.id == candidate.id.source }
            try {
                val uri = source.resolve(candidate.id.sourceTrackId).audioUri
                if (_preview.value == candidate) player.play(uri)
            } catch (e: MediaUnavailable) {
                _errors.value = listOf(UiText.of(R.string.search_failed_at, source.name, e.reason.text()))
                player.stop()
            }
        }
    }

    fun closePreview() {
        resolving?.cancel()
        player.stop()
        _preview.value = null
    }

    /** The app went to the background: a preview doesn't play on there. */
    fun onBackground() = player.pause()

    /**
     * Adds [candidate], marked Vocal-Free if [vocalFree] (ADR 14), else to be separated with [model]
     * (ADR 19); an existing Track opens unchanged.
     */
    fun add(candidate: SourceCandidate, vocalFree: Boolean, model: SeparationModelId?, then: (TrackId) -> Unit) {
        resolving?.cancel()
        player.stop()
        viewModelScope.launch { then(library.add(candidate, vocalFree, model).id) }
    }

    override fun onCleared() = player.stop()
}

@Composable
fun SearchRoute(sources: List<Source>, library: Library, choices: ModelChoices, onAdded: (TrackId) -> Unit) {
    val viewModel: SearchViewModel = viewModel(factory = viewModelFactory { initializer { SearchViewModel(sources, library) } })
    val query by viewModel.query.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()
    val errors by viewModel.errors.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val playing by viewModel.player.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onBackground() }
    SearchScreen(
        sources = sources,
        query = query,
        selected = selected,
        results = results,
        searching = searching,
        errors = errors,
        onQuery = viewModel::setQuery,
        onToggleSource = viewModel::toggle,
        onSearch = viewModel::search,
        onChoose = { viewModel.choose(it, onAdded) },
    )
    preview?.let { candidate ->
        PreviewSheet(
            candidate = candidate,
            sourceName = sources.nameOf(candidate),
            player = playing,
            onTogglePlay = viewModel.player::toggle,
            choice = rememberImportChoice(choices, key = candidate),
            onAdd = { vocalFree, model -> viewModel.add(candidate, vocalFree, model, onAdded) },
            onDismiss = viewModel::closePreview,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    sources: List<Source>,
    query: String,
    selected: Set<SourceId>,
    results: List<SourceCandidate>?,
    searching: Boolean,
    errors: List<UiText>,
    onQuery: (String) -> Unit,
    onToggleSource: (SourceId) -> Unit,
    onSearch: () -> Unit,
    /** A result was tapped: its preview opens, or its Track if it is in the Library already. */
    onChoose: (SourceCandidate) -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.search_title)) }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                label = { Text(stringResource(R.string.search_query)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                for (source in sources) {
                    FilterChip(source.id in selected, { onToggleSource(source.id) }, label = { Text(source.name) })
                }
                Button(onClick = onSearch, enabled = query.isNotBlank() && !searching) { Text(stringResource(R.string.action_search)) }
            }
            if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
            for (error in errors) Text(error.resolve(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (results?.isEmpty() == true && errors.isEmpty()) Text(stringResource(R.string.search_nothing_found))
            LazyColumn(Modifier.weight(1f)) {
                items(results.orEmpty(), key = { it.id.key }) { candidate ->
                    ListItem(
                        leadingContent = { RemoteImage(candidate.thumbnailUrl, Modifier.width(96.dp).aspectRatio(16f / 9f)) },
                        headlineContent = { Text(candidate.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(candidate.details(sources.nameOf(candidate)), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.clickable { onChoose(candidate) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/** "Uploader · 4:18 · YouTube" */
private fun SourceCandidate.details(sourceName: String?): String =
    listOfNotNull(artist, durationMs?.let(::formatTime), sourceName).joinToString(" · ")

private fun List<Source>.nameOf(candidate: SourceCandidate): String? = firstOrNull { it.id == candidate.id.source }?.name

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewSheet(
    candidate: SourceCandidate,
    sourceName: String?,
    player: PreviewPlayer.State,
    onTogglePlay: () -> Unit,
    /** The import options (ADR 14, ADR 19): "No vocals" and the model, pre-selected to the default. */
    choice: ImportChoice,
    onAdd: (vocalFree: Boolean, model: SeparationModelId?) -> Unit,
    onDismiss: () -> Unit,
) {
    // Fully open: the import options and the add button show without dragging the sheet up.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RemoteImage(candidate.thumbnailUrl, Modifier.height(160.dp).aspectRatio(16f / 9f))
            Text(candidate.title, style = MaterialTheme.typography.titleMedium)
            Text(
                candidate.details(sourceName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (player) {
                PreviewPlayer.State.Idle, PreviewPlayer.State.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                PreviewPlayer.State.Failed -> Text(stringResource(R.string.search_preview_unplayable), color = MaterialTheme.colorScheme.error)
                else -> Unit
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onTogglePlay,
                    enabled = player == PreviewPlayer.State.Playing || player == PreviewPlayer.State.Paused,
                ) { Text(stringResource(if (player == PreviewPlayer.State.Playing) R.string.action_pause else R.string.action_play)) }
            }
            ImportOptions(choice, candidate.durationMs)
            // Adding with the defaults stays one tap (ADR 19).
            Button(onClick = { onAdd(choice.vocalFree, choice.model.takeUnless { choice.vocalFree }) }, enabled = choice.loaded) {
                Text(importLabel(choice))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Why a search failed: the Source's reason, or the error's own message. */
private fun Throwable.reasonText(): UiText = if (this is MediaUnavailable) reason.text() else text()
