// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.schicht8.meavox.core.export.backingExportTitle
import eu.schicht8.meavox.core.model.BackingChoice
import eu.schicht8.meavox.core.model.Track
import eu.schicht8.meavox.core.model.Transpose
import eu.schicht8.meavox.core.port.ExportFormat

/** The entry to a Backing Export on the Track's screen, once the Original Audio is stored (#24). */
@Composable
internal fun BackingExportRow(onOpen: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Backing track", style = rowLabel())
            Text(
                "Export the backing alone, in your key, e.g. for a live performance.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        NeutralOutlinedButton("Export", onOpen, Modifier.height(44.dp))
    }
}

/** Callbacks of the Backing Export sheet. */
internal data class BackingExportActions(
    val onLayer: (BackingChoice) -> Unit,
    val onTranspose: (Transpose) -> Unit,
    val onExport: (ExportFormat) -> Unit,
    val onCancelExport: () -> Unit,
    val onClose: () -> Unit,
)

/**
 * A Backing Export (#24): the Instrumental or the Original Audio, whole, at a Transpose that starts at
 * the Track's Last Transpose. Changing it here is for this export only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackingExportSheet(dialog: BackingExportDialog, track: Track, actions: BackingExportActions) {
    ModalBottomSheet(onDismissRequest = actions.onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Export backing track", style = MaterialTheme.typography.titleLarge)
                Text(
                    "The whole song without your voice, in the key you choose. Tempo stays the same.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val layers = dialog.options.layers
            if (layers.size > 1) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    layers.forEachIndexed { index, layer ->
                        Choice(layer.label(), dialog.layer == layer, index = index, count = layers.size) { actions.onLayer(layer) }
                    }
                }
            } else if (dialog.options.instrumentalToCome) {
                Text(
                    "The instrumental can be exported once vocal removal is done; the original audio can now.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ExportTransposeControl(dialog.transpose, track.lastTranspose, actions.onTranspose)
            Text(
                "Saved as “${backingExportTitle(track, dialog.layer, dialog.transpose)}”",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ExportSection(dialog.export, actions.onExport, actions.onCancelExport)
        }
    }
}

/** The Transpose of this export only; the Track's Last Transpose stays. */
@Composable
private fun ExportTransposeControl(transpose: Transpose, lastTranspose: Transpose, onChange: (Transpose) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Transpose", Modifier.weight(1f), style = rowLabel())
            StepButton("−", "Transpose down", enabled = transpose.semitones > Transpose.MIN) { onChange(Transpose(transpose.semitones - 1)) }
            Text(
                transpose.label(),
                Modifier.width(40.dp),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
            StepButton("+", "Transpose up", enabled = transpose.semitones < Transpose.MAX) { onChange(Transpose(transpose.semitones + 1)) }
        }
        if (transpose != lastTranspose) {
            Text(
                "For this export only; you keep singing at ${lastTranspose.label()}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun BackingChoice.label(): String = when (this) {
    BackingChoice.Instrumental -> "Instrumental"
    BackingChoice.OriginalAudio -> "Original"
}
