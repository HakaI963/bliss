package com.ivarna.fluxlinux.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import com.ivarna.fluxlinux.core.utils.ThemePalette

// ── Altaris Midnight palette ──────────────────────────────────────────────────
// Observable top-level tokens keep legacy Frost/Quartz call sites in sync with
// the active theme. New code should use MaterialTheme.colorScheme directly.

var FolioBackground by mutableStateOf(Color(0xFF04070A))
    internal set
var FolioSurface by mutableStateOf(Color(0xFF091116))
    internal set
var FolioCard by mutableStateOf(Color(0xFF0F1A20))
    internal set
var FrostSurface by mutableStateOf(Color(0xFF091116))
    internal set
var FrostSurfaceElevated by mutableStateOf(Color(0xFF0F1A20))
    internal set
var FrostHairline by mutableStateOf(Color(0xFF172B27))
    internal set
var FrostTopSheen by mutableStateOf(Color.Transparent)
    internal set

var TextPrimary by mutableStateOf(Color(0xFFE7F3EF))
    internal set
var TextSecondary by mutableStateOf(Color(0xFF91AFA7))
    internal set
var TextMuted by mutableStateOf(Color(0xFF607A73))
    internal set
var PrimaryAction by mutableStateOf(Color(0xFF7CF5C8))
    internal set
var OnPrimaryAction by mutableStateOf(Color(0xFF032019))
    internal set
var StatusDot by mutableStateOf(Color(0xFF7CF5C8))
    internal set
var FolioDanger by mutableStateOf(Color(0xFFFF8A80))
    internal set
var FolioDangerContainer by mutableStateOf(Color(0xFF4A1512))
    internal set

// Frost switch tokens, kept for the existing switch call sites.
var FolioSwitchCheckedTrack by mutableStateOf(Color(0xFF7CF5C8))
    internal set
var FolioSwitchCheckedThumb by mutableStateOf(Color(0xFF032019))
    internal set
var FolioSwitchUncheckedTrack by mutableStateOf(Color(0xFF0F1A20))
    internal set
var FolioSwitchUncheckedThumb by mutableStateOf(Color(0xFF27423B))
    internal set
var FolioSwitchUncheckedBorder by mutableStateOf(Color(0xFF27423B))
    internal set

/** Hairline used by the legacy glass containers; now a flat hairline. */
val FolioFrostBorder: Brush
    get() = SolidColor(FrostHairline)

// ── Accent palette ──────────────────────────────────────────────────────────
// Surfaces stay Midnight/Day. These accents only colour primary controls.
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
}

@Composable
fun fluxMutedText(): Color = MaterialTheme.colorScheme.onSurfaceVariant

// Deprecated Flux aliases — mapped to the active neutral so legacy call sites
// keep compiling without pinning themselves to a dark theme.
@Deprecated("Use TextPrimary", ReplaceWith("TextPrimary"))
val FluxCream: Color get() = TextPrimary
@Deprecated("Use TextPrimary", ReplaceWith("TextPrimary"))
val FluxCreamPrimary: Color get() = TextPrimary
@Deprecated("Use TextPrimary", ReplaceWith("TextPrimary"))
val BrandCream: Color get() = TextPrimary
@Deprecated("Use FolioBackground", ReplaceWith("FolioBackground"))
val FluxDarkGrey: Color get() = FolioBackground
@Deprecated("Use FolioBackground", ReplaceWith("FolioBackground"))
val FluxDarkSurface: Color get() = FolioBackground
@Deprecated("Use TextSecondary", ReplaceWith("TextSecondary"))
val FluxAccentCyan: Color get() = TextSecondary
@Deprecated("Use TextSecondary", ReplaceWith("TextSecondary"))
val FluxAccentMagenta: Color get() = TextSecondary
@Deprecated("Use FolioBackground", ReplaceWith("FolioBackground"))
val FluxBackgroundStart: Color get() = FolioBackground
@Deprecated("Use FolioBackground", ReplaceWith("FolioBackground"))
val FluxBackgroundMid: Color get() = FolioBackground
@Deprecated("Use FolioBackground", ReplaceWith("FolioBackground"))
val FluxBackgroundEnd: Color get() = FolioBackground
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
val FluxBodyMuted: Color get() = TextSecondary
@Deprecated("Use FrostHairline", ReplaceWith("FrostHairline"))
val FluxHairline: Color get() = FrostHairline
@Deprecated("Use FolioSurface", ReplaceWith("FolioSurface"))
val FluxCardFill: Color get() = FolioSurface
@Deprecated("Use FolioDanger", ReplaceWith("FolioDanger"))
val FluxDanger: Color get() = FolioDanger
@Deprecated("Use FolioSwitchCheckedTrack", ReplaceWith("FolioSwitchCheckedTrack"))
val FluxSwitchCheckedTrack: Color get() = FolioSwitchCheckedTrack
@Deprecated("Use FolioSwitchCheckedThumb", ReplaceWith("FolioSwitchCheckedThumb"))
val FluxSwitchCheckedThumb: Color get() = FolioSwitchCheckedThumb
@Deprecated("Use FolioSwitchUncheckedTrack", ReplaceWith("FolioSwitchUncheckedTrack"))
val FluxSwitchUncheckedTrack: Color get() = FolioSwitchUncheckedTrack
@Deprecated("Use FolioSwitchUncheckedThumb", ReplaceWith("FolioSwitchUncheckedThumb"))
val FluxSwitchUncheckedThumb: Color get() = FolioSwitchUncheckedThumb
@Deprecated("Use FolioSwitchUncheckedBorder", ReplaceWith("FolioSwitchUncheckedBorder"))
val FluxSwitchUncheckedBorder: Color get() = FolioSwitchUncheckedBorder
