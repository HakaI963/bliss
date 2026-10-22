package com.altaris.linux.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor

// ── Altaris palette ─────────────────────────────────────────────────────────
// Observable top-level tokens keep legacy Frost/Quartz call sites in sync with
// the active identity. New code should use MaterialTheme.colorScheme directly.

var AltarisBackground by mutableStateOf(Color(0xFF0D0D0D))
    internal set
var AltarisSurface by mutableStateOf(Color(0xFF111111))
    internal set
var AltarisCard by mutableStateOf(Color(0xFF171717))
    internal set
var FrostSurface by mutableStateOf(Color(0xFF111111))
    internal set
var FrostSurfaceElevated by mutableStateOf(Color(0xFF171717))
    internal set
var FrostHairline by mutableStateOf(Color(0xFF292929))
    internal set
var FrostTopSheen by mutableStateOf(Color.White.copy(alpha = 0.26f))
    internal set

var TextPrimary by mutableStateOf(Color(0xFFF5F5F5))
    internal set
var TextSecondary by mutableStateOf(Color(0xFF929292))
    internal set
var TextMuted by mutableStateOf(Color(0xFF2E313C))
    internal set
var PrimaryAction by mutableStateOf(Color(0xFFF5F5F5))
    internal set
var OnPrimaryAction by mutableStateOf(Color(0xFF111111))
    internal set
var StatusDot by mutableStateOf(Color(0xFFF5F5F5))
    internal set
var AltarisDanger by mutableStateOf(Color(0xFFFF8A80))
    internal set
var AltarisDangerContainer by mutableStateOf(Color(0xFF4A1512))
    internal set

// Frost switch tokens, kept for the existing switch call sites.
var AltarisSwitchCheckedTrack by mutableStateOf(Color(0xFFF5F5F5))
    internal set
var AltarisSwitchCheckedThumb by mutableStateOf(Color(0xFF111111))
    internal set
var AltarisSwitchUncheckedTrack by mutableStateOf(Color(0xFF171717))
    internal set
var AltarisSwitchUncheckedThumb by mutableStateOf(Color(0xFF2E313C))
    internal set
var AltarisSwitchUncheckedBorder by mutableStateOf(Color(0xFF2E313C))
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
        palette.border,
        if (palette.isDark) {
            palette.onSurface.copy(alpha = 0.22f)
        } else {
            palette.border.copy(alpha = 0.9f)
        },
        palette.border,
    )
)

/** Specular sheen painted along the top edge of the glass surfaces. */
fun altarisSheenBrush(palette: AltarisPalette): Brush = Brush.horizontalGradient(
    listOf(
        Color.Transparent,
        palette.onSurface.copy(alpha = if (palette.isDark) 0.10f else 0.06f),
        Color.Transparent,
    )
)



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
