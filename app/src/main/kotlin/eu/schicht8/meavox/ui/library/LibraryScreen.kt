// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.ui.library

import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import eu.schicht8.meavox.R
import eu.schicht8.meavox.core.library.Library
import eu.schicht8.meavox.core.library.LibraryEntry
import eu.schicht8.meavox.core.library.RenameTrack
import eu.schicht8.meavox.core.model.Track
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.covers.CoverImages
import eu.schicht8.meavox.importing.Importer
import eu.schicht8.meavox.ui.covers.CoverTile
import eu.schicht8.meavox.ui.subtitle
import eu.schicht8.meavox.ui.theme.MeaVoxButtonShape
import eu.schicht8.meavox.ui.theme.MeaVoxTheme

@Composable
fun LibraryRoute(
    library: Library,
    covers: CoverImages,
    renameTrack: RenameTrack,
    importer: Importer,
    /** A file was picked: the import sheet asks how to add it (ADR 19). */
    onPicked: (Uri) -> Unit,
    onOpen: (TrackId) -> Unit,
    onStorage: () -> Unit,
    onSettings: () -> Unit,
    onSearch: (() -> Unit)? = null,
) {
    val viewModel: LibraryViewModel = viewModel(factory = viewModelFactory { initializer { LibraryViewModel(library, renameTrack) } })
    val state by viewModel.state.collectAsStateWithLifecycle()
    val importing by importer.importing.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPicked(uri)
    }
    LibraryScreen(
        state = state,
        covers = covers,
        importing = importing > 0,
        onImport = { picker.launch(arrayOf("audio/*", "video/*")) },
        onOpen = onOpen,
        onRename = viewModel::rename,
        onStorage = onStorage,
        onSettings = onSettings,
        onSearch = onSearch,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    /** Where the rows' covers come from; null shows placeholders only (previews). */
    covers: CoverImages? = null,
    importing: Boolean = false,
    onImport: () -> Unit = {},
    onOpen: (TrackId) -> Unit = {},
    /** Renames a Track: its title (never blank) and its artist (null clears it). */
    onRename: (TrackId, String, String?) -> Unit = { _, _, _ -> },
    onStorage: () -> Unit = {},
    onSettings: () -> Unit = {},
    /** Opens the search; null when the app has no network Sources (local flavor). */
    onSearch: (() -> Unit)? = null,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_title)) },
                actions = {
                    if (onSearch != null) TextButton(onClick = onSearch) { Text(stringResource(R.string.search_title)) }
                    TextButton(onClick = onStorage) { Text(stringResource(R.string.storage_title)) }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { menu = true }) { Text("⋮") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings_title)) },
                                onClick = {
                                    menu = false
                                    onSettings()
                                },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = onImport) { Text(stringResource(R.string.library_import_file)) } },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (state) {
                LibraryUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is LibraryUiState.Loaded ->
                    if (state.entries.isEmpty()) EmptyLibrary() else TrackList(state.entries, covers, onOpen, onRename)
            }
        }
    }
}

@Composable
private fun EmptyLibrary() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.library_empty), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.library_empty_hint),
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun TrackList(
    entries: List<LibraryEntry>,
    covers: CoverImages?,
    onOpen: (TrackId) -> Unit,
    onRename: (TrackId, String, String?) -> Unit,
) {
    var renaming by remember { mutableStateOf<Track?>(null) }
    // Bottom padding keeps the last row clear of the floating button.
    LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
        items(entries, key = { it.track.id.key }) { entry ->
            var menu by remember { mutableStateOf(false) }
            Box {
                TrackRow(entry, covers, onClick = { onOpen(entry.track.id) }, onLongClick = { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_rename)) },
                        onClick = {
                            menu = false
                            renaming = entry.track
                        },
                    )
                }
            }
        }
    }
    renaming?.let { track ->
        RenameTrackDialog(
            track = track,
            onRename = { title, artist ->
                onRename(track.id, title, artist)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
}

@Composable
private fun TrackRow(entry: LibraryEntry, covers: CoverImages?, onClick: () -> Unit, onLongClick: () -> Unit) {
    ListItem(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        leadingContent = { CoverTile(entry.track.id, entry.coverVersion, covers, COVER_SIZE) },
        headlineContent = { Text(entry.track.title) },
        supportingContent = {
            Column {
                Text(entry.track.subtitle())
                Text(
                    "${takeCount(entry.takeCount)} · ${lastSung(entry.track.lastSessionAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

/** The size of a Library row's cover (#49). */
private val COVER_SIZE = 56.dp

/** Edits a Track's title and artist; Rename stays disabled while the title is blank. */
@Composable
private fun RenameTrackDialog(track: Track, onRename: (title: String, artist: String?) -> Unit, onDismiss: () -> Unit) {
    var title by remember(track.id) { mutableStateOf(track.title) }
    var artist by remember(track.id) { mutableStateOf(track.artist.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_rename_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.lyrics_search_song_title)) }, singleLine = true)
                OutlinedTextField(value = artist, onValueChange = { artist = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.lyrics_search_artist)) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { onRename(title, artist) }, enabled = title.isNotBlank(), shape = MeaVoxButtonShape) { Text(stringResource(R.string.action_rename)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, shape = MeaVoxButtonShape) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun takeCount(count: Int): String =
    if (count == 0) stringResource(R.string.library_no_takes) else pluralStringResource(R.plurals.library_takes, count, count)

@Composable
private fun lastSung(at: Long?): String =
    if (at == null) {
        stringResource(R.string.library_not_sung)
    } else {
        // Android words it to stand alone ("Yesterday", "Vor 3 Tagen"); here it follows a verb.
        val locale = LocalConfiguration.current.locales[0]
        val ago = DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        stringResource(R.string.library_sung, ago.toString().replaceFirstChar { it.lowercase(locale) })
    }

@Preview
@Composable
private fun EmptyLibraryPreview() {
    MeaVoxTheme { LibraryScreen(LibraryUiState.Loaded(emptyList())) }
}
