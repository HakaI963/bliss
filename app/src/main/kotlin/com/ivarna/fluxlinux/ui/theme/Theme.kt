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

private val AospBlue = Color(0xFF0B57D0)
private val AospBlueDark = Color(0xFFA8C7FA)

private fun accentFor(palette: ThemePalette, dark: Boolean): Color = when (palette) {
    ThemePalette.CARBON -> if (dark) AospBlueDark else AospBlue
    ThemePalette.SLATE -> if (dark) Color(0xFFB8CFEA) else SlatePrimary
    ThemePalette.PLUM -> if (dark) Color(0xFFD5C4F0) else PlumPrimary
    ThemePalette.SAND -> if (dark) Color(0xFFE6D3B8) else SandPrimary
}

private fun accentContainerFor(palette: ThemePalette, dark: Boolean): Color = when (palette) {
    ThemePalette.CARBON -> if (dark) Color(0xFF0842A0) else Color(0xFFD3E3FD)
    ThemePalette.SLATE -> if (dark) Color(0xFF25384D) else Color(0xFFD7E4F2)
    ThemePalette.PLUM -> if (dark) Color(0xFF3F2F57) else Color(0xFFEADDFF)
    ThemePalette.SAND -> if (dark) Color(0xFF4A3928) else Color(0xFFF1E2CC)
}

private fun onAccentContainerFor(palette: ThemePalette, dark: Boolean): Color = when (palette) {
    ThemePalette.CARBON -> if (dark) Color(0xFFD3E3FD) else Color(0xFF041E49)
    ThemePalette.SLATE -> if (dark) Color(0xFFD7E4F2) else Color(0xFF102A43)
    ThemePalette.PLUM -> if (dark) Color(0xFFEADDFF) else Color(0xFF2B1B40)
    ThemePalette.SAND -> if (dark) Color(0xFFF1E2CC) else Color(0xFF3A2A18)
}

private fun lightSchemeFor(palette: ThemePalette): ColorScheme {
    val accent = accentFor(palette, dark = false)
    return lightColorScheme(
        primary = accent,
        onPrimary = Color.White,
        primaryContainer = accentContainerFor(palette, dark = false),
        onPrimaryContainer = onAccentContainerFor(palette, dark = false),
        secondary = accent,
        onSecondary = Color.White,
        secondaryContainer = accentContainerFor(palette, dark = false),
        onSecondaryContainer = onAccentContainerFor(palette, dark = false),
        tertiary = Color(0xFF00639B),
        onTertiary = Color.White,
        background = Color(0xFFF7F8FA),
        onBackground = Color(0xFF1F1F1F),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF1F1F1F),
        surfaceVariant = Color(0xFFF0F2F5),
        onSurfaceVariant = Color(0xFF5F6368),
        outline = Color(0xFF747775),
        outlineVariant = Color(0xFFDADCE0),
        inverseSurface = Color(0xFF303134),
        inverseOnSurface = Color(0xFFF1F3F4),
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
        onPrimary = Color(0xFF062E6F),
        primaryContainer = accentContainerFor(palette, dark = true),
        onPrimaryContainer = onAccentContainerFor(palette, dark = true),
        secondary = accent,
        onSecondary = Color(0xFF062E6F),
        secondaryContainer = accentContainerFor(palette, dark = true),
        onSecondaryContainer = onAccentContainerFor(palette, dark = true),
        tertiary = Color(0xFF7FCFFF),
        onTertiary = Color(0xFF003354),
        background = Color(0xFF111318),
        onBackground = Color(0xFFE3E3E3),
        surface = Color(0xFF1A1C1E),
        onSurface = Color(0xFFE3E3E3),
        surfaceVariant = Color(0xFF2B2F33),
        onSurfaceVariant = Color(0xFFC4C7C5),
        outline = Color(0xFF8E918F),
        outlineVariant = Color(0xFF44474A),
        inverseSurface = Color(0xFFE3E3E3),
        inverseOnSurface = Color(0xFF303134),
        error = Color(0xFFF2B8B5),
        onError = Color(0xFF601410),
        errorContainer = Color(0xFF8C1D18),
        onErrorContainer = Color(0xFFF9DEDC),
        scrim = Color(0xFF000000),
    )
}

/**
 * Mirror the active scheme into the legacy Frost/Quartz tokens. Keeping these
 * observable means older screens (and the few custom-painted surfaces) follow
 * Light/Dark and the accent picker without a screen-by-screen rewrite.
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
    FolioSwitchUncheckedTrack = if (dark) scheme.surfaceVariant else Color(0xFFE8EAED)
    FolioSwitchUncheckedThumb = scheme.outline
    FolioSwitchUncheckedBorder = scheme.outline
    StatusDot = if (dark) Color(0xFF81C995) else Color(0xFF1E8E3E)
}

@Composable
fun FolioTheme(
    themeMode: ThemeMode = ThemeMode.LIGHT,
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
    themeMode: ThemeMode = ThemeMode.LIGHT,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    FolioTheme(themeMode = themeMode, dynamicColor = false, content = content)
}
