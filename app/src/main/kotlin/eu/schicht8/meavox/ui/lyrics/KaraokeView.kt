// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.ui.lyrics

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.schicht8.meavox.R
import eu.schicht8.meavox.core.lyrics.Karaoke
import eu.schicht8.meavox.core.lyrics.LrcLine
import eu.schicht8.meavox.core.port.Playback
import eu.schicht8.meavox.ui.theme.MeaVoxTheme
import java.util.Locale
import kotlin.math.abs

/**
 * The Karaoke View (GLOSSARY): synced Lyrics that follow the Audio Engine position. The current
 * line is highlighted (semibold, larger, gold); the list shows [VISIBLE_LYRICS_LINES] lines and keeps the current
 * one second from the top, so the singer sees what comes next. Word progress within
 * the line isn't drawn: on device, a colour running through the words misled more than it helped.
 */
@Composable
fun KaraokeView(
    lines: List<LrcLine>,
    playback: Playback,
    offsetMs: Long,
    onNudgeOffset: (Long) -> Unit,
    onResetOffset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val position = Karaoke.at(lines, playback.positionMs, offsetMs)
    val list = rememberLazyListState()
    val style = MeaVoxTheme.type.lyric
    // One line plus its spacing: the line already sung stays visible above the current one.
    val lead = with(LocalDensity.current) { (style.lineHeight.toDp() + LINE_SPACING).roundToPx() }
    LaunchedEffect(position.line) {
        // Before the first line (e.g. after seeking back to the intro) the list goes to the top.
        list.glideTo(maxOf(position.line, 0), lead = if (position.line >= 0) lead else 0)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OffsetControls(offsetMs, onNudgeOffset, onResetOffset)
        LazyColumn(
            state = list,
            modifier = Modifier.height(visibleLyricsHeight(style, LINE_SPACING)).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 240.dp),
            verticalArrangement = Arrangement.spacedBy(LINE_SPACING),
        ) {
            itemsIndexed(lines) { index, line ->
                val current = index == position.line
                // Enlarged by drawing, not by a bigger font: a font change would change the line's
                // height and shove every line below it at once.
                val scale by animateFloatAsState(if (current) CURRENT_SCALE else 1f, tween(GLIDE_MS), label = "scale")
                // Gold for the current line; lines already sung and lines further ahead fade.
                val color by animateColorAsState(
                    when {
                        current -> MaterialTheme.colorScheme.primary
                        index < position.line - 1 || index > position.line + 2 -> MeaVoxTheme.colors.inkFaint
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    tween(GLIDE_MS),
                    label = "color",
                )
                Text(
                    text = line.text,
                    style = style,
                    fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                    color = color,
                    modifier = Modifier
                        .fillMaxWidth(1 / CURRENT_SCALE) // room for the enlarged line
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(0f, 0.5f)
                        },
                )
            }
        }
    }
}

/** Nudges the Lyrics Offset; "+" shows the Lyrics later. */
@Composable
private fun OffsetControls(offsetMs: Long, onNudge: (Long) -> Unit, onReset: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.lyrics_offset, formatOffset(offsetMs)),
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 4.dp),
        )
        for (step in listOf(-1_000L, -100L, 100L, 1_000L)) {
            OffsetStep(formatOffset(step)) { onNudge(step) }
        }
        TextButton(
            onClick = onReset,
            enabled = offsetMs != 0L,
            shape = MaterialTheme.shapes.extraSmall,
            contentPadding = PaddingValues(horizontal = 6.dp),
            modifier = Modifier.height(28.dp),
        ) { Text(stringResource(R.string.action_reset), style = MaterialTheme.typography.labelMedium) }
    }
}

/**
 * A small outlined step button, 28 dp high (the touch target stays 48 dp). Not a Material
 * button: those keep a 58 dp minimum width, and five would not fit beside the offset.
 */
@Composable
private fun OffsetStep(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraSmall,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.height(28.dp),
    ) {
        Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

private fun formatOffset(ms: Long): String =
    (if (ms > 0) "+" else if (ms < 0) "−" else "±") + String.format(Locale.getDefault(), "%.1f s", abs(ms) / 1000.0)

/**
 * Scrolls [index] to [lead] px below the top in one slow, eased movement. `animateScrollToItem`
 * jumps when the line isn't laid out yet and moves too abruptly for reading along.
 */
private suspend fun LazyListState.glideTo(index: Int, lead: Int) {
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (item == null) {
        scrollToItem(index, -lead) // far away (a seek): no point in gliding across the song
        return
    }
    animateScrollBy((item.offset - lead).toFloat(), tween(GLIDE_MS, easing = FastOutSlowInEasing))
}

private val LINE_SPACING = 14.dp
private const val GLIDE_MS = 600
private const val CURRENT_SCALE = 1.15f
