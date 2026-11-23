// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.ui.models

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import eu.schicht8.meavox.R
import eu.schicht8.meavox.core.model.SeparationModelId
import eu.schicht8.meavox.separating.ModelChoices
import eu.schicht8.meavox.separating.ModelOption
import kotlin.math.roundToInt

/**
 * The model picker (ADR 15, ADR 19): one row per Separation Model with the expected duration for
 * the Track ([durationMs], if known), whether it keeps backing vocals, and its download size if it
 * isn't installed yet. [current] marks the model whose Instrumental the Track has.
 */
@Composable
fun ModelPicker(
    options: List<ModelOption>,
    selected: SeparationModelId?,
    onSelect: (SeparationModelId) -> Unit,
    durationMs: Long?,
    modifier: Modifier = Modifier,
    current: SeparationModelId? = null,
) {
    val context = LocalContext.current
    Column(modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (option in options) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(option.id == selected, role = Role.RadioButton) { onSelect(option.id) }
                    .padding(vertical = 4.dp),
            ) {
                RadioButton(selected = option.id == selected, onClick = null)
                Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (option.id == current) stringResource(R.string.model_current, option.name) else option.name, style = MaterialTheme.typography.bodyLarge)
                    val details = listOfNotNull(
                        expectedDuration(option, durationMs),
                        stringResource(if (option.keepsBackingVocals) R.string.model_keeps_backing_vocals else R.string.model_removes_backing_vocals),
                        option.downloadBytes?.let { stringResource(R.string.model_download_size, Formatter.formatShortFileSize(context, it)) },
                    )
                    Text(
                        details.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The download the selected model needs, as the consent the confirm button asks for; null if none is needed. */
@Composable
fun DownloadNote(option: ModelOption?) {
    val bytes = option?.downloadBytes ?: return
    Text(
        stringResource(R.string.model_download_note, option.name, Formatter.formatShortFileSize(LocalContext.current, bytes)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** What a model choice is confirmed for. */
enum class ConfirmAction(@param:StringRes val label: Int, @param:StringRes val withDownload: Int) {
    Add(R.string.confirm_add, R.string.confirm_download_and_add),
    Reseparate(R.string.confirm_reseparate, R.string.confirm_download_and_reseparate),
    RemoveVocals(R.string.confirm_remove_vocals, R.string.confirm_download_and_remove_vocals),
}

/** [action], asking for the download as well if [option] needs one: confirming is the consent. */
@Composable
fun confirmLabel(action: ConfirmAction, option: ModelOption?): String {
    val bytes = option?.downloadBytes ?: return stringResource(action.label)
    return stringResource(action.withDownload, Formatter.formatShortFileSize(LocalContext.current, bytes))
}

/**
 * "About 5 min for this song" from the Track's length, or relative to it when the length is unknown.
 * The factors were measured on the weakest device (ADR 15), so faster devices take less.
 */
@Composable
private fun expectedDuration(option: ModelOption, durationMs: Long?): String {
    if (durationMs == null) {
        val factor = option.realTimeFactor
        return if (factor < 1) {
            stringResource(R.string.model_faster_than_song)
        } else {
            stringResource(R.string.model_times_song_length, "%.1f".format(factor))
        }
    }
    val minutes = (durationMs * option.realTimeFactor / 60_000).roundToInt()
    return if (minutes < 1) stringResource(R.string.model_under_a_minute) else stringResource(R.string.model_about_minutes, minutes)
}

/** What an import sheet asks (ADR 14, ADR 19): whether the song has no vocals, and else which model removes them. */
@Stable
class ImportChoice(private val choices: ModelChoices) {
    var options by mutableStateOf<List<ModelOption>>(emptyList())
        private set
    var model by mutableStateOf<SeparationModelId?>(null)
    var vocalFree by mutableStateOf(false)

    /** Loaded once the options and the Default Separation Model are read; the confirm button waits for it. */
    val loaded: Boolean get() = model != null

    val selected: ModelOption? get() = options.firstOrNull { it.id == model }

    suspend fun load() {
        options = choices.options()
        if (model == null) model = choices.default.get()
    }
}

/**
 * The import choice for the song [key] stands for: it starts afresh (Default Separation Model, "No
 * vocals" unset) for each new song, even when one import sheet replaces another.
 */
@Composable
fun rememberImportChoice(choices: ModelChoices, key: Any): ImportChoice {
    val choice = remember(choices, key) { ImportChoice(choices) }
    LaunchedEffect(choice) { choice.load() }
    return choice
}

/** The "No vocals" option and, unless it is set, the model picker with the Default Separation Model pre-selected. */
@Composable
fun ImportOptions(choice: ImportChoice, durationMs: Long?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().toggleable(choice.vocalFree, role = Role.Checkbox) { choice.vocalFree = it },
        ) {
            Checkbox(checked = choice.vocalFree, onCheckedChange = null)
            Column(Modifier.padding(start = 12.dp)) {
                Text(stringResource(R.string.import_no_vocals), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.import_no_vocals_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // A Vocal-Free Track isn't separated, so there is no model to pick (ADR 19).
        if (!choice.vocalFree) {
            Text(stringResource(R.string.import_vocal_removal), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ModelPicker(choice.options, choice.model, { choice.model = it }, durationMs)
            DownloadNote(choice.selected)
        }
    }
}

/** The import sheet's confirm label: adding a Vocal-Free song needs no model download. */
@Composable
fun importLabel(choice: ImportChoice): String =
    if (choice.vocalFree) stringResource(R.string.confirm_add) else confirmLabel(ConfirmAction.Add, choice.selected)
