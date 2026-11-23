// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.ui.lyrics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.schicht8.meavox.R
import eu.schicht8.meavox.core.lyrics.TrackLyrics
import eu.schicht8.meavox.core.lyrics.pageText
import eu.schicht8.meavox.ui.theme.MeaVoxTheme

/**
 * The Page View (GLOSSARY): the Lyrics as whole, readable verses that the singer scrolls. Works
 * with plain and synced Lyrics alike. About [VISIBLE_LYRICS_LINES] lines tall.
 */
@Composable
fun PageView(lyrics: TrackLyrics?, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier.height(visibleLyricsHeight(MeaVoxTheme.type.lyricPage)).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when (lyrics) {
            null, TrackLyrics.Searching -> Text(stringResource(R.string.lyrics_searching), color = muted)
            is TrackLyrics.NotFound, is TrackLyrics.Failed ->
                Text(stringResource(R.string.lyrics_none_yet), color = muted)
            is TrackLyrics.Suggested -> Page(lyrics.lyrics.pageText(), stringResource(R.string.lyrics_suggested_source, lyrics.lyrics.name(), lyrics.lyrics.kind()))
            is TrackLyrics.Confirmed -> Page(lyrics.lyrics.pageText(), stringResource(R.string.lyrics_source, lyrics.lyrics.name(), lyrics.lyrics.kind()))
        }
    }
}

@Composable
private fun Page(text: String, caption: String) {
    val stanzas = remember(text) { text.split(Regex("""\n\s*\n""")) }
    Text(caption, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    for (stanza in stanzas) {
        Text(stanza.trim(), style = MeaVoxTheme.type.lyricPage, color = MaterialTheme.colorScheme.onSurface)
    }
}
