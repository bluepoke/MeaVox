// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.ui.lyrics

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import eu.schicht8.meavox.R
import eu.schicht8.meavox.core.port.LyricsCandidate
import eu.schicht8.meavox.ui.formatTime

/** "Synced" (works in the Karaoke View too) or "Plain" (Page View only), ADR 6. */
@Composable
fun LyricsCandidate.kind(): String = stringResource(if (synced != null) R.string.lyrics_synced else R.string.lyrics_plain)

/** "Artist – Title" of a Lyrics match. */
fun LyricsCandidate.name(): String = listOfNotNull(artist, title).joinToString(" – ")

/** "Synced · 4:18": what the match is, for comparing it with the Track. */
@Composable
fun LyricsCandidate.details(): String = listOfNotNull(kind(), durationMs?.let(::formatTime)).joinToString(" · ")
