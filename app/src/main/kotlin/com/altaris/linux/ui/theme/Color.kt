package com.altaris.linux.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import com.altaris.linux.core.utils.ThemePalette

// ── Altaris palette ─────────────────────────────────────────────────────────
// Observable top-level tokens keep legacy Frost/Quartz call sites in sync with
// the active identity. New code should use MaterialTheme.colorScheme directly.

var AltarisBackground by mutableStateOf(Color(0xFF0E0F13))
    internal set
var AltarisSurface by mutableStateOf(Color(0xFF191B22))
    internal set
var AltarisCard by mutableStateOf(Color(0xFF23262F))
    internal set
var FrostSurface by mutableStateOf(Color(0xFF191B22))
    internal set
var FrostSurfaceElevated by mutableStateOf(Color(0xFF23262F))
    internal set
var FrostHairline by mutableStateOf(Color(0xFF22252C))
    internal set
var FrostTopSheen by mutableStateOf(Color.White.copy(alpha = 0.26f))
    internal set

var TextPrimary by mutableStateOf(Color(0xFFFAFAFF))
    internal set
var TextSecondary by mutableStateOf(Color(0xFFA9A9B8))
    internal set
var TextMuted by mutableStateOf(Color(0xFF2E313C))
    internal set
var PrimaryAction by mutableStateOf(Color(0xFFE9E7FF))
    internal set
var OnPrimaryAction by mutableStateOf(Color(0xFF16141F))
    internal set
var StatusDot by mutableStateOf(Color(0xFFE9E7FF))
    internal set
var AltarisDanger by mutableStateOf(Color(0xFFFF8A80))
    internal set
var AltarisDangerContainer by mutableStateOf(Color(0xFF4A1512))
    internal set

// Frost switch tokens, kept for the existing switch call sites.
var AltarisSwitchCheckedTrack by mutableStateOf(Color(0xFFE9E7FF))
    internal set
var AltarisSwitchCheckedThumb by mutableStateOf(Color(0xFF16141F))
    internal set
var AltarisSwitchUncheckedTrack by mutableStateOf(Color(0xFF23262F))
    internal set
var AltarisSwitchUncheckedThumb by mutableStateOf(Color(0xFF2E313C))
    internal set
var AltarisSwitchUncheckedBorder by mutableStateOf(Color(0xFF2E313C))
    internal set

/**
 * Gradient stops of the active identity. Custom-painted surfaces (hero
 * panels, the aurora backdrop) read these instead of hard-coding colours.
 */
var HeroGradientStops by mutableStateOf(
    listOf(
        Color(0xFFFFD6F5),
        Color(0xFFD9C8FF),
        Color(0xFFBFE9FF),
        Color(0xFFC9FFE6),
    )
)
    internal set

/** Tight accent gradient (three stops) used by primary actions. */
var AccentGradientStops by mutableStateOf(
    listOf(
        Color(0xFFE9E7FF),
        Color(0xFF9BE7FF),
        Color(0xFFFFC0F0),
    )
)
    internal set

/** Hairline used by the legacy glass containers; now a flat hairline. */
val AltarisFrostBorder: Brush
    get() = SolidColor(FrostHairline)

/**
 * Gradient hairline used by the Altaris surfaces: a quiet sheen that runs
 * across the edge of the card instead of a flat border.
 */
fun altarisHairlineBrush(palette: AltarisPalette): Brush = Brush.horizontalGradient(
    listOf(
        palette.outlineVariant,
        if (palette.isDark) {
            palette.glassHighlight.copy(alpha = 0.32f)
        } else {
            palette.outline.copy(alpha = 0.45f)
        },
        palette.outlineVariant,
    )
)

/** Specular sheen painted along the top edge of the glass surfaces. */
fun altarisSheenBrush(palette: AltarisPalette): Brush = Brush.horizontalGradient(
    listOf(
        Color.Transparent,
        palette.glassHighlight,
        Color.Transparent,
    )
)

// ── Accent palette ──────────────────────────────────────────────────────────
// The identity owns the surfaces; these accents only colour primary controls.
val SlatePrimary = Color(0xFF0E6F8A)
val PlumPrimary = Color(0xFF6B52A8)
val SandPrimary = Color(0xFF8A5D20)
val CarbonPrimary = Color(0xFF7CF5C8)

/** Swatch dot for the accent picker. */
fun ThemePalette.accentColor(): Color = when (this) {
    ThemePalette.CARBON -> CarbonPrimary
    ThemePalette.SLATE -> SlatePrimary
    ThemePalette.PLUM -> PlumPrimary
    ThemePalette.SAND -> SandPrimary
    // "Scheme" follows the active identity, so the swatch mirrors it.
    ThemePalette.SCHEME -> PrimaryAction
}

@Composable
fun altarisMutedText(): Color = MaterialTheme.colorScheme.onSurfaceVariant

// Deprecated Altaris aliases — mapped to the active neutral so legacy call sites
// keep compiling without pinning themselves to a dark theme.
@Deprecated("Use TextPrimary", ReplaceWith("TextPrimary"))
val AltarisCream: Color get() = TextPrimary
@Deprecated("Use TextPrimary", ReplaceWith("TextPrimary"))
val AltarisCreamPrimary: Color get() = TextPrimary
@Deprecated("Use TextPrimary", ReplaceWith("TextPrimary"))
val BrandCream: Color get() = TextPrimary
@Deprecated("Use AltarisBackground", ReplaceWith("AltarisBackground"))
val AltarisDarkGrey: Color get() = AltarisBackground
@Deprecated("Use AltarisBackground", ReplaceWith("AltarisBackground"))
val AltarisDarkSurface: Color get() = AltarisBackground
@Deprecated("Use TextSecondary", ReplaceWith("TextSecondary"))
val AltarisAccentCyan: Color get() = TextSecondary
@Deprecated("Use TextSecondary", ReplaceWith("TextSecondary"))
val AltarisAccentMagenta: Color get() = TextSecondary
@Deprecated("Use AltarisBackground", ReplaceWith("AltarisBackground"))
val AltarisBackgroundStart: Color get() = AltarisBackground
@Deprecated("Use AltarisBackground", ReplaceWith("AltarisBackground"))
val AltarisBackgroundMid: Color get() = AltarisBackground
@Deprecated("Use AltarisBackground", ReplaceWith("AltarisBackground"))
val AltarisBackgroundEnd: Color get() = AltarisBackground
@Deprecated("Use FrostSurface", ReplaceWith("FrostSurface"))
val GlassWhiteHigh: Color get() = FrostSurface
@Deprecated("Use FrostSurface", ReplaceWith("FrostSurface"))
val GlassWhiteMedium: Color get() = FrostSurface
@Deprecated("Use FrostSurface", ReplaceWith("FrostSurface"))
val GlassWhiteLow: Color get() = FrostSurface
@Deprecated("Use FrostHairline", ReplaceWith("FrostHairline"))
val GlassBorder: Color get() = FrostHairline
@Deprecated("Use TextPrimary", ReplaceWith("TextPrimary"))
val TextWhite: Color get() = TextPrimary
@Deprecated("Use TextSecondary", ReplaceWith("TextSecondary"))
val TextGrey: Color get() = TextSecondary
@Deprecated("Use TextSecondary", ReplaceWith("TextSecondary"))
val Seed: Color get() = TextSecondary
@Deprecated("Use TextSecondary", ReplaceWith("TextSecondary"))
val AltarisBodyMuted: Color get() = TextSecondary
@Deprecated("Use FrostHairline", ReplaceWith("FrostHairline"))
val AltarisHairline: Color get() = FrostHairline
@Deprecated("Use AltarisSurface", ReplaceWith("AltarisSurface"))
val AltarisCardFill: Color get() = AltarisSurface
@Deprecated("Use AltarisDanger", ReplaceWith("AltarisDanger"))
val AltarisDanger: Color get() = AltarisDanger
@Deprecated("Use AltarisSwitchCheckedTrack", ReplaceWith("AltarisSwitchCheckedTrack"))
val AltarisSwitchCheckedTrack: Color get() = AltarisSwitchCheckedTrack
@Deprecated("Use AltarisSwitchCheckedThumb", ReplaceWith("AltarisSwitchCheckedThumb"))
val AltarisSwitchCheckedThumb: Color get() = AltarisSwitchCheckedThumb
@Deprecated("Use AltarisSwitchUncheckedTrack", ReplaceWith("AltarisSwitchUncheckedTrack"))
val AltarisSwitchUncheckedTrack: Color get() = AltarisSwitchUncheckedTrack
@Deprecated("Use AltarisSwitchUncheckedThumb", ReplaceWith("AltarisSwitchUncheckedThumb"))
val AltarisSwitchUncheckedThumb: Color get() = AltarisSwitchUncheckedThumb
@Deprecated("Use AltarisSwitchUncheckedBorder", ReplaceWith("AltarisSwitchUncheckedBorder"))
val AltarisSwitchUncheckedBorder: Color get() = AltarisSwitchUncheckedBorder
