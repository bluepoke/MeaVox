// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.ui.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.schicht8.meavox.R
import eu.schicht8.meavox.core.model.SeparationModelId
import eu.schicht8.meavox.importing.TitleSuggestion
import eu.schicht8.meavox.separating.ModelChoices
import eu.schicht8.meavox.ui.theme.MeaVoxButtonShape

/**
 * The import sheet for a picked or shared file, or a shared link (ADR 19): what it is ([source], e.g.
 * the file name or the video's title), its editable title and artist with a [suggestion] (#52), the
 * "No vocals" option and the model picker. Confirming without changing anything adds it with the
 * suggestion and the Default Separation Model, in one tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportSheet(
    /** The pending import; each new one starts with the defaults. */
    key: Any,
    source: String,
    details: String?,
    durationMs: Long?,
    suggestion: TitleSuggestion,
    choices: ModelChoices,
    onAdd: (title: String, artist: String?, vocalFree: Boolean, model: SeparationModelId?) -> Unit,
    onDismiss: () -> Unit,
) {
    val choice = rememberImportChoice(choices, key)
    val names = rememberNameFields(key, suggestion)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(source, style = MaterialTheme.typography.titleMedium)
            details?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            NameInputs(names)
            ImportOptions(choice, durationMs)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onAdd(names.addedTitle, names.addedArtist, choice.vocalFree, choice.model.takeUnless { choice.vocalFree }) },
                    enabled = choice.loaded && names.canAdd,
                    shape = MeaVoxButtonShape,
                ) { Text(importLabel(choice)) }
                TextButton(onClick = onDismiss, shape = MeaVoxButtonShape) { Text(stringResource(R.string.action_cancel)) }
            }
            Spacer(Modifier.fillMaxWidth().height(16.dp))
        }
    }
}
