// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.ui.models

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import eu.schicht8.meavox.R
import eu.schicht8.meavox.importing.TitleSuggestion

/**
 * The fields for a song being added (#52), starting with [suggestion]'s first guess, then a file's tags,
 * then the MusicBrainz check, each filling what the user hasn't edited (see [NameFields]). Adding never
 * waits for MusicBrainz. Each new [key] starts afresh.
 */
@Composable
fun rememberNameFields(key: Any, suggestion: TitleSuggestion): NameFields {
    val fields = remember(key) { NameFields(suggestion.first, tagsToCome = suggestion.fromTags != null) }
    LaunchedEffect(key) {
        val local = suggestion.fromTags?.invoke()?.also(fields::tagsRead) ?: suggestion.first
        suggestion.checked(local)?.let(fields::suggest)
    }
    return fields
}

/** The editable Title and Artist of a song being added (#52). */
@Composable
fun NameInputs(fields: NameFields) {
    val words = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next)
    OutlinedTextField(
        fields.title,
        fields::editTitle,
        label = { Text(stringResource(R.string.import_title)) },
        singleLine = true,
        isError = !fields.canAdd,
        keyboardOptions = words,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        fields.artist,
        fields::editArtist,
        label = { Text(stringResource(R.string.import_artist)) },
        singleLine = true,
        keyboardOptions = words.copy(imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )
}
