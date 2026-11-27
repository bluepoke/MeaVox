// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The MeaVox palette, taken from the launcher icon (docs/icon/launcher-icon.svg):
 * gold from the note and the microphone ring, cool greys from the microphone,
 * near-black from the icon background. One dark theme; see the MeaVox design system.
 */
internal object MeaVoxPalette {
    val Bg = Color(0xFF09090B)
    val Surface = Color(0xFF141417)
    val SurfaceRaised = Color(0xFF2A2A2F)
    val Line = Color(0xFF3A3B40)
    val Ink = Color(0xFFE3E5E8)
    val InkMuted = Color(0xFFA3A8AF)
    val InkFaint = Color(0xFF7D828A)
    val Gold = Color(0xFFE8B93E)
    val GoldSoft = Color(0xFF3D3115)
    val GoldDeep = Color(0xFFC8941F)
    val OnGold = Color(0xFF141109)
    val Record = Color(0xFFEF5350)
    /** The Play button: a green between Gold and Record in lightness, so the three transport colours sit together. */
    val Play = Color(0xFF5FC48A)
    val RecordSoft = Color(0xFF3D2020)
}

/**
 * Material 3 roles. Contrast (WCAG): Ink on every surface ≥ 11:1, InkMuted ≥ 5.9:1,
 * Gold as text ≥ 7.7:1, OnGold on Gold 10:1, Record as text ≥ 5.2:1 on Bg and Surface, OnGold on Play 8.8:1.
 * Line (outlineVariant) is decoration only; control borders use outline (InkMuted).
 */
internal val MeaVoxColorScheme: ColorScheme = with(MeaVoxPalette) {
    darkColorScheme(
        primary = Gold,
        onPrimary = OnGold,
        primaryContainer = GoldSoft,
        onPrimaryContainer = Gold,
        inversePrimary = GoldDeep,
        // Selected FilterChips and SegmentedButtons use the secondary container: muted gold.
        secondary = InkMuted,
        onSecondary = Bg,
        secondaryContainer = GoldSoft,
        onSecondaryContainer = Gold,
        tertiary = GoldDeep,
        onTertiary = OnGold,
        tertiaryContainer = GoldSoft,
        onTertiaryContainer = Gold,
        background = Bg,
        onBackground = Ink,
        surface = Bg,
        onSurface = Ink,
        surfaceVariant = SurfaceRaised,
        onSurfaceVariant = InkMuted,
        // No gold cast on elevated surfaces: layers come from the surface containers below.
        surfaceTint = Bg,
        inverseSurface = Ink,
        inverseOnSurface = Bg,
        error = Record,
        onError = OnGold,
        errorContainer = RecordSoft,
        onErrorContainer = Ink,
        outline = InkMuted,
        outlineVariant = Line,
        scrim = Color.Black,
        surfaceDim = Bg,
        surfaceBright = SurfaceRaised,
        surfaceContainerLowest = Bg,
        surfaceContainerLow = Surface,
        surfaceContainer = Surface,
        surfaceContainerHigh = SurfaceRaised,
        surfaceContainerHighest = SurfaceRaised,
    )
}

/** Colours Material 3 has no role for. Read them with `MeaVoxTheme.colors`. */
@Immutable
data class MeaVoxExtraColors(
    /** Recording only: Record and Stop buttons, "● Recording", the meter's clipping, "Record again". */
    val record: Color,
    /** Text and icons on [record]. */
    val onRecord: Color,
    /** Unlit segments in the level meter's clipping zone. Decoration only. */
    val recordSoft: Color,
    /** Lyrics lines further from the current line (5.1:1 on Bg). Not for controls. */
    val inkFaint: Color,
    /** Pressed primary button; track under gold progress. */
    val goldDeep: Color,
    /** The Play button of free playback (its Pause is gold). */
    val play: Color,
    /** Text and icons on [play]. */
    val onPlay: Color,
)

internal val MeaVoxExtraColorsDark = with(MeaVoxPalette) {
    MeaVoxExtraColors(
        record = Record,
        onRecord = OnGold,
        recordSoft = RecordSoft,
        inkFaint = InkFaint,
        goldDeep = GoldDeep,
        play = Play,
        onPlay = OnGold,
    )
}
