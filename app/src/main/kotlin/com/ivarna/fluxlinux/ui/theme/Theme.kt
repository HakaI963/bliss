package com.ivarna.fluxlinux.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.ivarna.fluxlinux.core.utils.StateManager
import com.ivarna.fluxlinux.core.utils.ThemeMode
import com.ivarna.fluxlinux.core.utils.ThemePalette

// Bliss Midnight — the reference look: near-black glass, mint/teal signal,
// icy text and hairline borders. Day is the same layout with paper surfaces.
private val Mint = Color(0xFF79F2C4)
private val MintContainer = Color(0xFF0D2A23)
private val Ice = Color(0xFF7DDFF3)
private val IceContainer = Color(0xFF0A2730)
private val Violet = Color(0xFFC7B8FF)
private val VioletContainer = Color(0xFF211B3A)
private val Amber = Color(0xFFFFD08A)
private val AmberContainer = Color(0xFF332412)

private val NightBg = Color(0xFF05080B)
private val NightSurface = Color(0xFF0A1014)
private val NightSurfaceVariant = Color(0xFF101A1F)
private val NightText = Color(0xFFE7F3EF)
private val NightMuted = Color(0xFF8CA8A1)
private val NightOutline = Color(0xFF243B37)
private val NightOutlineSoft = Color(0xFF172825)

private val DayBg = Color(0xFFF2F6F5)
private val DaySurface = Color(0xFFFFFFFF)
private val DaySurfaceVariant = Color(0xFFEAF0EE)
private val DayText = Color(0xFF14201D)
private val DayMuted = Color(0xFF5D726C)
private val DayOutline = Color(0xFFC8D6D1)
private val DayOutlineSoft = Color(0xFFDDE7E3)
private val DayPrimary = Color(0xFF0F766E)

private fun accentFor(palette: ThemePalette, dark: Boolean): Color = when (palette) {
    ThemePalette.CARBON -> if (dark) Mint else DayPrimary
    ThemePalette.SLATE -> if (dark) Ice else Color(0xFF0E6F8A)
    ThemePalette.PLUM -> if (dark) Violet else Color(0xFF6B52A8)
    ThemePalette.SAND -> if (dark) Amber else Color(0xFF8A5D20)
}

private fun accentContainerFor(palette: ThemePalette, dark: Boolean): Color = when (palette) {
    ThemePalette.CARBON -> if (dark) MintContainer else Color(0xFFD4F3EA)
    ThemePalette.SLATE -> if (dark) IceContainer else Color(0xFFD6EFF5)
    ThemePalette.PLUM -> if (dark) VioletContainer else Color(0xFFE9E2FF)
    ThemePalette.SAND -> if (dark) AmberContainer else Color(0xFFF7E8CC)
}

private fun onAccentContainerFor(palette: ThemePalette, dark: Boolean): Color = when (palette) {
    ThemePalette.CARBON -> if (dark) Color(0xFFB9FFE4) else Color(0xFF053D37)
    ThemePalette.SLATE -> if (dark) Color(0xFFC6F2FF) else Color(0xFF003743)
    ThemePalette.PLUM -> if (dark) Color(0xFFEDE5FF) else Color(0xFF2B1B56)
    ThemePalette.SAND -> if (dark) Color(0xFFFFE7BD) else Color(0xFF422B09)
}

private fun onAccentFor(palette: ThemePalette, dark: Boolean): Color = when (palette) {
    ThemePalette.CARBON -> if (dark) Color(0xFF032019) else Color.White
    ThemePalette.SLATE -> if (dark) Color(0xFF001F28) else Color.White
    ThemePalette.PLUM -> if (dark) Color(0xFF160E2D) else Color.White
    ThemePalette.SAND -> if (dark) Color(0xFF261706) else Color.White
}

private fun lightSchemeFor(palette: ThemePalette): ColorScheme {
    val accent = accentFor(palette, dark = false)
    return lightColorScheme(
        primary = accent,
        onPrimary = onAccentFor(palette, false),
        primaryContainer = accentContainerFor(palette, false),
        onPrimaryContainer = onAccentContainerFor(palette, false),
        secondary = Color(0xFF1E6B63),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFD4F3EA),
        onSecondaryContainer = Color(0xFF053D37),
        tertiary = Color(0xFF3C6478),
        onTertiary = Color.White,
        background = DayBg,
        onBackground = DayText,
        surface = DaySurface,
        onSurface = DayText,
        surfaceVariant = DaySurfaceVariant,
        onSurfaceVariant = DayMuted,
        outline = DayOutline,
        outlineVariant = DayOutlineSoft,
        inverseSurface = Color(0xFF1B2925),
        inverseOnSurface = Color(0xFFEAF3F0),
        error = Color(0xFFB3261E),
        onError = Color.White,
        errorContainer = Color(0xFFF9DEDC),
        onErrorContainer = Color(0xFF410E0B),
        scrim = Color(0xFF000000),
    )
}

private fun darkSchemeFor(palette: ThemePalette): ColorScheme {
    val accent = accentFor(palette, dark = true)
    return darkColorScheme(
        primary = accent,
        onPrimary = onAccentFor(palette, true),
        primaryContainer = accentContainerFor(palette, true),
        onPrimaryContainer = onAccentContainerFor(palette, true),
        secondary = Ice,
        onSecondary = Color(0xFF00252E),
        secondaryContainer = IceContainer,
        onSecondaryContainer = Color(0xFFC6F2FF),
        tertiary = Color(0xFFA8C7FF),
        onTertiary = Color(0xFF002E6A),
        background = NightBg,
        onBackground = NightText,
        surface = NightSurface,
        onSurface = NightText,
        surfaceVariant = NightSurfaceVariant,
        onSurfaceVariant = NightMuted,
        outline = NightOutline,
        outlineVariant = NightOutlineSoft,
        inverseSurface = NightText,
        inverseOnSurface = NightBg,
        error = Color(0xFFFF8A80),
        onError = Color(0xFF3A0907),
        errorContainer = Color(0xFF4A1512),
        onErrorContainer = Color(0xFFFFD9D6),
        scrim = Color(0xFF000000),
    )
}

/**
 * Mirror the active scheme into the legacy Frost/Quartz tokens. Keeping these
 * observable means older screens (and the few custom-painted surfaces) follow
 * Midnight/Day and the accent picker without a screen-by-screen rewrite.
 */
private fun applyLegacyTokens(scheme: ColorScheme, dark: Boolean) {
    FolioBackground = scheme.background
    FolioSurface = scheme.surface
    FolioCard = scheme.surfaceVariant
    FrostSurface = scheme.surface
    FrostSurfaceElevated = scheme.surfaceVariant
    FrostHairline = scheme.outlineVariant
    FrostTopSheen = Color.Transparent
    TextPrimary = scheme.onBackground
    TextSecondary = scheme.onSurfaceVariant
    TextMuted = scheme.outline
    PrimaryAction = scheme.primary
    OnPrimaryAction = scheme.onPrimary
    FolioDanger = scheme.error
    FolioDangerContainer = scheme.errorContainer
    FolioSwitchCheckedTrack = scheme.primary
    FolioSwitchCheckedThumb = scheme.onPrimary
    FolioSwitchUncheckedTrack = scheme.surfaceVariant
    FolioSwitchUncheckedThumb = scheme.outline
    FolioSwitchUncheckedBorder = scheme.outline
    StatusDot = scheme.primary
}

@Composable
fun FolioTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    remember(context) { StateManager.getThemePalette(context) }
    val palette by StateManager.themePalette.collectAsState()

    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        else -> isSystemInDarkTheme()
    }
    val colorScheme = if (darkTheme) darkSchemeFor(palette) else lightSchemeFor(palette)

    val view = LocalView.current
    SideEffect {
        applyLegacyTokens(colorScheme, darkTheme)
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        window.statusBarColor = colorScheme.background.toArgb()
        window.navigationBarColor = colorScheme.background.toArgb()
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

/** Legacy entry point — preserved so existing call sites keep compiling. */
@Composable
fun FluxLinuxTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    FolioTheme(themeMode = themeMode, dynamicColor = false, content = content)
}
