package com.ivarna.fluxlinux.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.ivarna.fluxlinux.core.utils.ThemePalette

// Bliss monochrome frost palette — matte deep carbon, no neon, no beige.
val FolioBackground = Color(0xFF09090B)
val FolioSurface = Color(0xFF101013)
val FolioCard = Color(0xFF18181B)
val FrostSurface = Color(0xFFFFFFFF).copy(alpha = 0.05f)
val FrostSurfaceElevated = Color(0xFFFFFFFF).copy(alpha = 0.09f)
val FrostHairline = Color(0xFFFFFFFF).copy(alpha = 0.06f)
// Soft top sheen for frosted panels: fades from a lit edge to nothing.
val FrostTopSheen = Color(0xFFFFFFFF).copy(alpha = 0.05f)
val FolioFrostBorder: Brush = Brush.verticalGradient(
    listOf(Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.035f))
)
val TextPrimary = Color(0xFFF4F4F5)
val TextSecondary = Color(0xFFA1A1AA)
val TextMuted = Color(0xFF52525B)
val PrimaryAction = Color(0xFFF4F4F5)
val OnPrimaryAction = Color(0xFF09090B)
val StatusDot = Color(0xFF22C55E)
val FolioDanger = Color(0xFFE5484D)
val FolioDangerContainer = Color(0xFF7F1D1D)

// Frost switch tokens
val FolioSwitchCheckedTrack = TextPrimary
val FolioSwitchCheckedThumb = FolioBackground
val FolioSwitchUncheckedTrack = Color(0xFF27272A)
val FolioSwitchUncheckedThumb = Color(0xFFE4E4E7)
val FolioSwitchUncheckedBorder = Color(0xFF52525B)

// ── Theme palette tokens (dark schemes) ───────────────────────────────────
// Each palette swaps only these slots; text / hairline / danger tokens stay
// shared so every scheme has the same frost structure. CARBON is the frost
// set above and the default — its values are unchanged.
// SLATE — cool blue-gray, muted.
val SlateBackground = Color(0xFF0B0E12)
val SlateSurface = Color(0xFF11151B)
val SlateSurfaceVariant = Color(0xFF1A2028)
val SlatePrimary = Color(0xFFA8C5E0)
val SlateOutlineVariant = Color(0xFF232B35)
// PLUM — muted violet-gray.
val PlumBackground = Color(0xFF0F0C13)
val PlumSurface = Color(0xFF16121C)
val PlumSurfaceVariant = Color(0xFF1F1926)
val PlumPrimary = Color(0xFFC9B8E8)
val PlumOutlineVariant = Color(0xFF2A2333)
// SAND — warm graphite.
val SandBackground = Color(0xFF13110E)
val SandSurface = Color(0xFF1A1713)
val SandSurfaceVariant = Color(0xFF241F19)
val SandPrimary = Color(0xFFD9C7A8)
val SandOutlineVariant = Color(0xFF2E2820)

/** Swatch dot for the palette picker: each palette's primary accent. */
fun ThemePalette.accentColor(): Color = when (this) {
    ThemePalette.CARBON -> PrimaryAction
    ThemePalette.SLATE -> SlatePrimary
    ThemePalette.PLUM -> PlumPrimary
    ThemePalette.SAND -> SandPrimary
}

// Deprecated Flux aliases — mapped to monochrome so legacy call sites keep compiling.
// Do not use in new Bliss UI.
@Deprecated("Use TextPrimary", ReplaceWith("TextPrimary"))
val FluxCream = TextPrimary
@Deprecated("Use TextPrimary", ReplaceWith("TextPrimary"))
val FluxCreamPrimary = TextPrimary
@Deprecated("Use TextPrimary", ReplaceWith("TextPrimary"))
val BrandCream = TextPrimary
@Deprecated("Use FolioBackground", ReplaceWith("FolioBackground"))
val FluxDarkGrey = FolioBackground
@Deprecated("Use FolioBackground", ReplaceWith("FolioBackground"))
val FluxDarkSurface = FolioBackground
@Deprecated("Use TextSecondary", ReplaceWith("TextSecondary"))
val FluxAccentCyan = TextSecondary
@Deprecated("Use TextSecondary", ReplaceWith("TextSecondary"))
val FluxAccentMagenta = TextSecondary
@Deprecated("Use FolioBackground", ReplaceWith("FolioBackground"))
val FluxBackgroundStart = FolioBackground
@Deprecated("Use FolioBackground", ReplaceWith("FolioBackground"))
val FluxBackgroundMid = FolioBackground
@Deprecated("Use FolioBackground", ReplaceWith("FolioBackground"))
val FluxBackgroundEnd = FolioBackground
@Deprecated("Use FrostSurface", ReplaceWith("FrostSurface"))
val GlassWhiteHigh = FrostSurface
@Deprecated("Use FrostSurface", ReplaceWith("FrostSurface"))
val GlassWhiteMedium = FrostSurface
@Deprecated("Use FrostSurface", ReplaceWith("FrostSurface"))
val GlassWhiteLow = FrostSurface
@Deprecated("Use FrostHairline", ReplaceWith("FrostHairline"))
val GlassBorder = FrostHairline
@Deprecated("Use TextPrimary", ReplaceWith("TextPrimary"))
val TextWhite = TextPrimary
@Deprecated("Use TextSecondary", ReplaceWith("TextSecondary"))
val TextGrey = TextSecondary
@Deprecated("Use TextSecondary", ReplaceWith("TextSecondary"))
val Seed = TextSecondary
@Deprecated("Use TextSecondary", ReplaceWith("TextSecondary"))
val FluxBodyMuted = TextSecondary
@Deprecated("Use FrostHairline", ReplaceWith("FrostHairline"))
val FluxHairline = FrostHairline
@Deprecated("Use FolioSurface", ReplaceWith("FolioSurface"))
val FluxCardFill = FolioSurface
@Deprecated("Use FolioDanger", ReplaceWith("FolioDanger"))
val FluxDanger = FolioDanger
@Deprecated("Use FolioSwitchCheckedTrack", ReplaceWith("FolioSwitchCheckedTrack"))
val FluxSwitchCheckedTrack = FolioSwitchCheckedTrack
@Deprecated("Use FolioSwitchCheckedThumb", ReplaceWith("FolioSwitchCheckedThumb"))
val FluxSwitchCheckedThumb = FolioSwitchCheckedThumb
@Deprecated("Use FolioSwitchUncheckedTrack", ReplaceWith("FolioSwitchUncheckedTrack"))
val FluxSwitchUncheckedTrack = FolioSwitchUncheckedTrack
@Deprecated("Use FolioSwitchUncheckedThumb", ReplaceWith("FolioSwitchUncheckedThumb"))
val FluxSwitchUncheckedThumb = FolioSwitchUncheckedThumb
@Deprecated("Use FolioSwitchUncheckedBorder", ReplaceWith("FolioSwitchUncheckedBorder"))
val FluxSwitchUncheckedBorder = FolioSwitchUncheckedBorder

@Composable
fun fluxMutedText(): Color {
    return if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        TextSecondary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
}
