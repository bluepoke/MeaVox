// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import eu.schicht8.meavox.core.model.SeparationModelId
import eu.schicht8.meavox.core.session.SettingsRepository
import eu.schicht8.meavox.separating.ModelChoices
import eu.schicht8.meavox.separating.ModelOption
import eu.schicht8.meavox.ui.models.ModelPicker
import kotlinx.coroutines.launch

/** Settings: the Default Separation Model (ADR 19) and the singer's name, which Mix Exports are tagged with (#15). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(choices: ModelChoices, settings: SettingsRepository) {
    var options by remember { mutableStateOf<List<ModelOption>>(emptyList()) }
    var default by remember { mutableStateOf<SeparationModelId?>(null) }
    var singer by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(choices) {
        options = choices.options()
        default = choices.default.get()
    }
    LaunchedEffect(settings) {
        singer = settings.singerName().orEmpty()
    }
    val scope = rememberCoroutineScope()
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Default vocal removal", style = MaterialTheme.typography.titleMedium)
            Text(
                "Pre-selected when you add a song or re-separate one; you can pick another there. " +
                    "Changing it doesn't touch songs already added. A model is downloaded the first time it is used.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ModelPicker(options, default, onSelect = { model ->
                default = model
                scope.launch { choices.default.set(model) }
            }, durationMs = null)
            Spacer(Modifier.height(16.dp))
            Text("Your name", style = MaterialTheme.typography.titleMedium)
            Text(
                "Exported Mixes name you as the singer next to the artist: “<artist> feat. <your name>”.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            singer?.let { name ->
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        singer = it
                        scope.launch { settings.setSingerName(it) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                )
            }
        }
    }
}
