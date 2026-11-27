// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.ui.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import eu.schicht8.meavox.R
import eu.schicht8.meavox.core.model.SeparationModelId
import eu.schicht8.meavox.core.session.SettingsRepository
import eu.schicht8.meavox.separating.ModelChoices
import eu.schicht8.meavox.separating.ModelOption
import eu.schicht8.meavox.ui.models.ModelPicker
import kotlinx.coroutines.launch

/**
 * Settings: the Default Separation Model (ADR 19), the singer's name, which Mix Exports are tagged with
 * (#15), and the way to the third-party licenses (#21).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(choices: ModelChoices, settings: SettingsRepository, onLicenses: () -> Unit) {
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
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.settings_default_model), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.settings_default_model_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ModelPicker(options, default, onSelect = { model ->
                default = model
                scope.launch { choices.default.set(model) }
            }, durationMs = null)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.settings_your_name), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.settings_your_name_hint),
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
                    placeholder = { Text(stringResource(R.string.settings_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                )
            }
            Spacer(Modifier.height(16.dp))
            Column(Modifier.fillMaxWidth().clickable(onClick = onLicenses).padding(vertical = 8.dp)) {
                Text(stringResource(R.string.settings_licenses), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.settings_licenses_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
