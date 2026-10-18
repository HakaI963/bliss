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

// CARBON — the original frost scheme; values deliberately unchanged.
private val FolioDarkScheme = darkColorScheme(
    primary = PrimaryAction,
    onPrimary = OnPrimaryAction,
    secondary = TextSecondary,
    onSecondary = FolioBackground,
    tertiary = TextSecondary,
    background = FolioBackground,
    onBackground = TextPrimary,
    surface = FolioSurface,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    surfaceVariant = FolioCard,
    outline = TextMuted,
    outlineVariant = FrostHairline,
    inverseSurface = TextPrimary,
    inverseOnSurface = FolioBackground,
    error = FolioDanger,
    onError = TextPrimary,
    primaryContainer = FrostSurfaceElevated,
    onPrimaryContainer = TextPrimary,
    secondaryContainer = FrostSurfaceElevated,
    onSecondaryContainer = TextPrimary,
)

// SLATE — cool blue-gray, muted. Structure mirrors FolioDarkScheme.
private val SlateDarkScheme = darkColorScheme(
    primary = SlatePrimary,
    onPrimary = SlateBackground,
    secondary = TextSecondary,
    onSecondary = SlateBackground,
    tertiary = TextSecondary,
    background = SlateBackground,
    onBackground = TextPrimary,
    surface = SlateSurface,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    surfaceVariant = SlateSurfaceVariant,
    outline = TextMuted,
    outlineVariant = SlateOutlineVariant,
    inverseSurface = TextPrimary,
    inverseOnSurface = SlateBackground,
    error = FolioDanger,
    onError = TextPrimary,
    primaryContainer = FrostSurfaceElevated,
    onPrimaryContainer = TextPrimary,
    secondaryContainer = FrostSurfaceElevated,
    onSecondaryContainer = TextPrimary,
)

// PLUM — muted violet-gray. Structure mirrors FolioDarkScheme.
private val PlumDarkScheme = darkColorScheme(
    primary = PlumPrimary,
    onPrimary = PlumBackground,
    secondary = TextSecondary,
    onSecondary = PlumBackground,
    tertiary = TextSecondary,
    background = PlumBackground,
    onBackground = TextPrimary,
    surface = PlumSurface,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    surfaceVariant = PlumSurfaceVariant,
    outline = TextMuted,
    outlineVariant = PlumOutlineVariant,
    inverseSurface = TextPrimary,
    inverseOnSurface = PlumBackground,
    error = FolioDanger,
    onError = TextPrimary,
    primaryContainer = FrostSurfaceElevated,
    onPrimaryContainer = TextPrimary,
    secondaryContainer = FrostSurfaceElevated,
    onSecondaryContainer = TextPrimary,
)

// SAND — warm graphite. Structure mirrors FolioDarkScheme.
private val SandDarkScheme = darkColorScheme(
    primary = SandPrimary,
    onPrimary = SandBackground,
    secondary = TextSecondary,
    onSecondary = SandBackground,
    tertiary = TextSecondary,
    background = SandBackground,
    onBackground = TextPrimary,
    surface = SandSurface,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    surfaceVariant = SandSurfaceVariant,
    outline = TextMuted,
    outlineVariant = SandOutlineVariant,
    inverseSurface = TextPrimary,
    inverseOnSurface = SandBackground,
    error = FolioDanger,
    onError = TextPrimary,
    primaryContainer = FrostSurfaceElevated,
    onPrimaryContainer = TextPrimary,
    secondaryContainer = FrostSurfaceElevated,
    onSecondaryContainer = TextPrimary,
)

/** Dark scheme for the selected palette. */
private fun darkSchemeFor(palette: ThemePalette): ColorScheme = when (palette) {
    ThemePalette.CARBON -> FolioDarkScheme
    ThemePalette.SLATE -> SlateDarkScheme
    ThemePalette.PLUM -> PlumDarkScheme
    ThemePalette.SAND -> SandDarkScheme
}

private val FolioLightScheme = lightColorScheme(
    primary = FolioBackground,
    onPrimary = TextPrimary,
    secondary = TextMuted,
    onSecondary = Color.White,
    tertiary = TextMuted,
    background = Color(0xFFFAFAFA),
    onBackground = FolioBackground,
    surface = Color.White,
    onSurface = FolioBackground,
    outline = Color(0xFFD4D4D8),
    outlineVariant = Color(0x14000000),
)

@Composable
fun FolioTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    // Read the palette inside the theme so MainActivity stays untouched.
    // Sync-hydrate the persisted value before the first collection so the
    // first drawn frame already uses the stored palette (CARBON default).
    remember(context) { StateManager.getThemePalette(context) }
    val palette by StateManager.themePalette.collectAsState()

    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        else -> isSystemInDarkTheme()
    }
    // Palette skins are dark-only; LIGHT keeps the shared light scheme.
    val colorScheme = if (darkTheme) darkSchemeFor(palette) else FolioLightScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

// Legacy entry point — preserved so existing call sites keep compiling.
@Composable
fun FluxLinuxTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    FolioTheme(themeMode = themeMode, dynamicColor = false, content = content)
}
