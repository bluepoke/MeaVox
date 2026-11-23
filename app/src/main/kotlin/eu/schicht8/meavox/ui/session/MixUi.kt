// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.ui.session

import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.schicht8.meavox.R
import eu.schicht8.meavox.core.mix.Mix
import eu.schicht8.meavox.core.mix.MixId
import eu.schicht8.meavox.core.mix.MixSettings
import eu.schicht8.meavox.core.model.BackingChoice
import eu.schicht8.meavox.core.model.Take
import eu.schicht8.meavox.core.model.Track
import eu.schicht8.meavox.core.port.ExportFormat
import eu.schicht8.meavox.core.port.Playback
import eu.schicht8.meavox.core.session.BackingAudio
import eu.schicht8.meavox.core.session.SessionState
import eu.schicht8.meavox.ui.formatTime
import eu.schicht8.meavox.ui.percent
import eu.schicht8.meavox.ui.resolve
import eu.schicht8.meavox.ui.theme.MeaVoxButtonShape
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** The Track's Mixes (#14): one per kept Take to start with, more from a Take's "New Mix". */
@Composable
internal fun MixesSection(mixes: List<Mix>, takes: List<Take>, onEdit: (Mix) -> Unit, onDelete: (MixId) -> Unit) {
    var deleting by remember { mutableStateOf<Mix?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.mix_list, mixes.size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (mixes.isEmpty()) {
            Text(
                stringResource(R.string.mix_list_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        mixes.forEach { mix ->
            Panel(spacing = 6.dp, verticalPadding = 12.dp) {
                Text(mix.name, style = rowLabel())
                Text(
                    listOf(takes.firstOrNull { it.id == mix.take }?.name ?: stringResource(R.string.mix_take), mix.backing.label(), mix.settings.summary()).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.offset(x = (-8).dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TakeAction(stringResource(R.string.mix_edit_and_export), MaterialTheme.colorScheme.primary) { onEdit(mix) }
                    TakeAction(stringResource(R.string.action_delete), MaterialTheme.colorScheme.onSurface) { deleting = mix }
                }
            }
        }
    }
    deleting?.let { mix ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.storage_delete_take_title, mix.name)) },
            text = { Text(stringResource(R.string.mix_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(mix.id)
                    deleting = null
                }, shape = MeaVoxButtonShape) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }, shape = MeaVoxButtonShape) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** The Transport while a Mix Preview plays. */
@Composable
internal fun PreviewingMixPanel(state: SessionState.PreviewingMix, playback: Playback, onStop: () -> Unit) {
    Panel(spacing = 12.dp) {
        Text(stringResource(R.string.mix_previewing, state.mix.name), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.mix_position, formatTime(playback.positionMs - state.take.startPositionMs), formatTime(state.take.durationMs)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        NeutralOutlinedButton(stringResource(R.string.action_stop), onStop, Modifier.height(44.dp))
    }
}

/** Callbacks of the Mix Editor. */
internal data class MixEditorActions(
    val onSettings: (MixSettings) -> Unit,
    val onSettingsDone: () -> Unit,
    val onBacking: (BackingChoice) -> Unit,
    val onPreview: () -> Unit,
    val onStop: () -> Unit,
    val onExport: (ExportFormat) -> Unit,
    val onCancelExport: () -> Unit,
    val onClose: () -> Unit,
)

/**
 * The Mix Editor (#14): Backing Audio, levels and Vocal Nudge, a preview that sounds as the Export
 * will, and the WAV Export with progress, Cancel and the share sheet. The Take is never changed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MixEditorSheet(
    editor: MixEditor,
    mix: Mix,
    take: Take?,
    track: Track,
    state: SessionState,
    playback: Playback,
    actions: MixEditorActions,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = actions.onClose, sheetState = sheet) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(mix.name, style = MaterialTheme.typography.titleLarge)
                take?.let {
                    Text(
                        stringResource(R.string.mix_take_details, it.name, formatTime(it.startPositionMs), formatTime(it.durationMs), it.transpose.label()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // A Vocal-Free Track has no Instrumental (ADR 14).
            if (!track.vocalFree) MixBackingRow(mix.backing, available = track.instrumentalModel != null, actions.onBacking)
            val settings = editor.settings
            LevelSlider(stringResource(R.string.session_backing), settings.backingDb, MixSettings.MIN_BACKING_DB..0f) {
                actions.onSettings(settings.copy(backingDb = it))
            }
            LevelSlider(stringResource(R.string.mix_vocal), settings.vocalDb, MixSettings.MIN_VOCAL_DB..MixSettings.MAX_VOCAL_DB) {
                actions.onSettings(settings.copy(vocalDb = it))
            }
            NudgeControl(settings.vocalNudgeMs) { actions.onSettings(settings.copy(vocalNudgeMs = it)) }
            // Stored once they stop changing; previewing or exporting stores them too.
            StoreWhenSettled(settings, actions.onSettingsDone)
            val previewing = state is SessionState.PreviewingMix && state.mix.id == mix.id
            if (previewing) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NeutralOutlinedButton(stringResource(R.string.action_stop), actions.onStop, Modifier.height(44.dp))
                    take?.let {
                        Text(
                            stringResource(R.string.mix_position, formatTime(playback.positionMs - it.startPositionMs), formatTime(it.durationMs)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                NeutralOutlinedButton(stringResource(R.string.mix_preview), actions.onPreview, Modifier.height(44.dp))
                if (state != SessionState.Idle && state !is SessionState.PreviewingMix) {
                    Text(
                        stringResource(R.string.mix_preview_later),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            ExportSection(editor.export, actions.onExport, actions.onCancelExport)
        }
    }
}

/** Instrumental | Original Audio for this Mix only; the Track's own choice is not changed. */
@Composable
private fun MixBackingRow(backing: BackingAudio, available: Boolean, onChoose: (BackingChoice) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.mix_backing_audio), style = rowLabel())
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Choice(stringResource(R.string.backing_instrumental), backing is BackingAudio.Instrumental, index = 0, count = 2, enabled = available) {
                onChoose(BackingChoice.Instrumental)
            }
            Choice(stringResource(R.string.backing_original_short), backing == BackingAudio.OriginalAudio, index = 1, count = 2) { onChoose(BackingChoice.OriginalAudio) }
        }
        if (!available && backing == BackingAudio.OriginalAudio) {
            Text(
                stringResource(R.string.session_instrumental_later),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A level in whole dB. */
@Composable
private fun LevelSlider(label: String, db: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = rowLabel())
            Text(formatDb(db), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = db,
            onValueChange = { onChange(it.roundToInt().toFloat().coerceIn(range)) },
            valueRange = range,
            colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.secondaryContainer),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Vocal Nudge: ±200 ms in 5 ms steps on top of Latency Calibration; later is positive. */
@Composable
private fun NudgeControl(nudgeMs: Int, onChange: (Int) -> Unit) {
    val max = MixSettings.MAX_NUDGE_MS
    fun step(delta: Int) = onChange((nudgeMs + delta).coerceIn(-max, max))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.mix_vocal_nudge), Modifier.weight(1f), style = rowLabel())
            StepButton("−", stringResource(R.string.mix_vocal_earlier), enabled = nudgeMs > -max) { step(-NUDGE_STEP_MS) }
            Text(
                nudgeLabel(nudgeMs),
                Modifier.width(72.dp),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
            StepButton("+", stringResource(R.string.mix_vocal_later), enabled = nudgeMs < max) { step(NUDGE_STEP_MS) }
        }
        Slider(
            value = nudgeMs.toFloat(),
            onValueChange = { onChange(((it / NUDGE_STEP_MS).roundToInt() * NUDGE_STEP_MS).coerceIn(-max, max)) },
            valueRange = -max.toFloat()..max.toFloat(),
            colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.secondaryContainer),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.mix_vocal_nudge_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Calls [onDone] shortly after [settings] stop changing, so a slider drag is stored once. */
@Composable
private fun StoreWhenSettled(settings: MixSettings, onDone: () -> Unit) {
    LaunchedEffect(settings) {
        delay(SAVE_DELAY_MS)
        onDone()
    }
}

/**
 * WAV, 16-bit by default or 24-bit, to Music/MeaVox, then shared if the user wants (ADR 8): for a
 * Mix and for a Backing Export alike.
 */
@Composable
internal fun ExportSection(export: ExportProgress?, onExport: (ExportFormat) -> Unit, onCancel: () -> Unit) {
    var kind by remember { mutableStateOf(FormatKind.Mp3) }
    var bits by remember { mutableIntStateOf(16) }
    var mp3Bitrate by remember { mutableStateOf(ExportFormat.Mp3.Bitrate.VbrV2) }
    val context = LocalContext.current
    Panel(spacing = 10.dp) {
        Text(stringResource(R.string.export), style = rowLabel())
        when (export) {
            is ExportProgress.Running -> {
                Text(stringResource(R.string.export_running, percent(LocalResources.current, export.progress)), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { export.progress }, modifier = Modifier.fillMaxWidth())
                NeutralOutlinedButton(stringResource(R.string.action_cancel), onCancel, Modifier.height(44.dp))
            }
            else -> {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    FormatKind.entries.forEachIndexed { index, it ->
                        Choice(stringResource(it.label), kind == it, index = index, count = FormatKind.entries.size) { kind = it }
                    }
                }
                when (kind) {
                    FormatKind.Mp3 -> SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        Choice(stringResource(R.string.format_mp3_vbr_v2), mp3Bitrate == ExportFormat.Mp3.Bitrate.VbrV2, index = 0, count = 2) {
                            mp3Bitrate = ExportFormat.Mp3.Bitrate.VbrV2
                        }
                        Choice(stringResource(R.string.format_mp3_cbr_320), mp3Bitrate == ExportFormat.Mp3.Bitrate.Cbr320, index = 1, count = 2) {
                            mp3Bitrate = ExportFormat.Mp3.Bitrate.Cbr320
                        }
                    }
                    FormatKind.M4a -> Unit
                    FormatKind.Wav -> SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        Choice(stringResource(R.string.format_wav_16), bits == 16, index = 0, count = 2) { bits = 16 }
                        Choice(stringResource(R.string.format_wav_24), bits == 24, index = 1, count = 2) { bits = 24 }
                    }
                }
                Text(stringResource(kind.hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val format = when (kind) {
                    FormatKind.Mp3 -> ExportFormat.Mp3(mp3Bitrate)
                    FormatKind.M4a -> ExportFormat.M4a
                    FormatKind.Wav -> ExportFormat.Wav(bits)
                }
                Button(onClick = { onExport(format) }, shape = MeaVoxButtonShape, modifier = Modifier.fillMaxWidth().height(44.dp)) {
                    Text(stringResource(R.string.export), fontWeight = FontWeight.Bold)
                }
                when (export) {
                    is ExportProgress.Done -> {
                        Text(
                            stringResource(R.string.export_saved_to, "Music/MeaVox/${export.file.displayName}"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        NeutralOutlinedButton(stringResource(R.string.export_share), {
                            val send = Intent(Intent.ACTION_SEND)
                                .setType(export.file.mimeType)
                                .putExtra(Intent.EXTRA_STREAM, Uri.parse(export.file.uri))
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            context.startActivity(Intent.createChooser(send, export.file.displayName))
                        }, Modifier.height(44.dp))
                    }
                    is ExportProgress.Failed -> Text(
                        stringResource(R.string.export_failed, export.reason.resolve()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> Unit
                }
            }
        }
    }
}

/** The formats the export offers (ADR 8), MP3 first: it plays everywhere. */
private enum class FormatKind(@param:StringRes val label: Int, @param:StringRes val hint: Int) {
    Mp3(R.string.format_mp3, R.string.format_mp3_hint),
    M4a(R.string.format_m4a, R.string.format_m4a_hint),
    Wav(R.string.format_wav, R.string.format_wav_hint),
}

@Composable
private fun BackingAudio.label(): String = stringResource(
    when (this) {
        BackingAudio.OriginalAudio -> R.string.backing_original_short
        is BackingAudio.Instrumental -> R.string.backing_instrumental
    },
)

@Composable
private fun MixSettings.summary(): String =
    stringResource(R.string.mix_settings_summary, formatDb(backingDb), formatDb(vocalDb), nudgeLabel(vocalNudgeMs))

private fun nudgeLabel(ms: Int): String = when {
    ms > 0 -> "+$ms ms"
    ms < 0 -> "−${-ms} ms"
    else -> "0 ms"
}

private const val NUDGE_STEP_MS = 5
private const val SAVE_DELAY_MS = 500L
