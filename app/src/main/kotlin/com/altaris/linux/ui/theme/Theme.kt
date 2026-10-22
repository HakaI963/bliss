package com.altaris.linux.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.altaris.linux.core.utils.StateManager
import com.altaris.linux.core.utils.ThemeMode
import com.altaris.linux.core.utils.ThemePalette

// ── Legacy accent skins ────────────────────────────────────────────────────
// The accent picker still works: it tints the primary role of the active
// identity. "Scheme" (the default) leaves the identity's own colours alone.
private val Mint = Color(0xFF7CF5C8)
private val MintContainer = Color(0xFF0C2B24)
private val Ice = Color(0xFF86E6FF)
private val IceContainer = Color(0xFF0A2730)
private val Violet = Color(0xFFC7B8FF)
private val VioletContainer = Color(0xFF211B3A)
private val Amber = Color(0xFFFFD08A)
private val AmberContainer = Color(0xFF332412)

private val DayPrimary = Color(0xFF0F766E)

private fun accentFor(palette: ThemePalette, dark: Boolean): Color = when (palette) {
    ThemePalette.CARBON -> if (dark) Mint else DayPrimary
    ThemePalette.SLATE -> if (dark) Ice else Color(0xFF0E6F8A)
    ThemePalette.PLUM -> if (dark) Violet else Color(0xFF6B52A8)
    ThemePalette.SAND -> if (dark) Amber else Color(0xFF8A5D20)
    ThemePalette.SCHEME -> Color.Unspecified
}

private fun accentContainerFor(palette: ThemePalette, dark: Boolean): Color = when (palette) {
    ThemePalette.CARBON -> if (dark) MintContainer else Color(0xFFD4F3EA)
    ThemePalette.SLATE -> if (dark) IceContainer else Color(0xFFD6EFF5)
    ThemePalette.PLUM -> if (dark) VioletContainer else Color(0xFFE9E2FF)
    ThemePalette.SAND -> if (dark) AmberContainer else Color(0xFFF7E8CC)
    ThemePalette.SCHEME -> Color.Unspecified
}

private fun onAccentContainerFor(palette: ThemePalette, dark: Boolean): Color = when (palette) {
    ThemePalette.CARBON -> if (dark) Color(0xFFB9FFE4) else Color(0xFF053D37)
    ThemePalette.SLATE -> if (dark) Color(0xFFC6F2FF) else Color(0xFF003743)
    ThemePalette.PLUM -> if (dark) Color(0xFFEDE5FF) else Color(0xFF2B1B56)
    ThemePalette.SAND -> if (dark) Color(0xFFFFE7BD) else Color(0xFF422B09)
    ThemePalette.SCHEME -> Color.Unspecified
}

private fun onAccentFor(palette: ThemePalette, dark: Boolean): Color = when (palette) {
    ThemePalette.CARBON -> if (dark) Color(0xFF032019) else Color.White
    ThemePalette.SLATE -> if (dark) Color(0xFF001F28) else Color.White
    ThemePalette.PLUM -> if (dark) Color(0xFF160E2D) else Color.White
    ThemePalette.SAND -> if (dark) Color(0xFF261706) else Color.White
    ThemePalette.SCHEME -> Color.Unspecified
}

/**
 * The resolved identity of the current theme. Every Altaris surface reads its
 * colours from here, so a scheme change restyles the whole app at once.
 */
val LocalAltarisPalette = staticCompositionLocalOf {
    AltarisPalettes.of(AltarisScheme.IRIDESCENT, dark = true)
}

/**
 * Builds the Material3 scheme for an identity. The optional accent skin only
 * replaces the primary role (and its container), exactly like the pre-scheme
 * picker did.
 */
private fun colorSchemeFor(palette: AltarisPalette, accent: ThemePalette): ColorScheme {
    val useAccent = accent != ThemePalette.SCHEME
    val primary = if (useAccent) accentFor(accent, palette.isDark) else palette.primary
    val onPrimary = if (useAccent) onAccentFor(accent, palette.isDark) else palette.onPrimary
    val primaryContainer =
        if (useAccent) accentContainerFor(accent, palette.isDark) else palette.primaryContainer
    val onPrimaryContainer =
        if (useAccent) onAccentContainerFor(accent, palette.isDark) else palette.onPrimaryContainer
    val tintAlpha = if (palette.isDark) 0.22f else 0.14f
    val secondaryContainer = palette.secondary.copy(alpha = tintAlpha)
    val tertiaryContainer = palette.tertiary.copy(alpha = tintAlpha)
    val onError = if (palette.isDark) Color(0xFF3A0907) else Color.White
    val onErrorContainer = if (palette.isDark) Color(0xFFFFD9D6) else Color(0xFF410E0B)
    val inverseSurface = if (palette.isDark) palette.onSurface.copy(alpha = 0.92f) else palette.base
    val inverseOnSurface = if (palette.isDark) palette.base else palette.onSurface
    val scrim = Color.Black

    return if (palette.isDark) {
        darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = palette.secondary,
            onSecondary = palette.onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = palette.onSurface,
            tertiary = palette.tertiary,
            onTertiary = palette.onTertiary,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = palette.onSurface,
            background = palette.base,
            onBackground = palette.onSurface,
            surface = palette.surface,
            onSurface = palette.onSurface,
            surfaceVariant = palette.surfaceVariant,
            onSurfaceVariant = palette.onSurfaceVariant,
            outline = palette.outline,
            outlineVariant = palette.outlineVariant,
            inverseSurface = inverseSurface,
            inverseOnSurface = inverseOnSurface,
            error = palette.error,
            onError = onError,
            errorContainer = palette.errorContainer,
            onErrorContainer = onErrorContainer,
            scrim = scrim,
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = palette.secondary,
            onSecondary = palette.onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = palette.onSurface,
            tertiary = palette.tertiary,
            onTertiary = palette.onTertiary,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = palette.onSurface,
            background = palette.base,
            onBackground = palette.onSurface,
            surface = palette.surface,
            onSurface = palette.onSurface,
            surfaceVariant = palette.surfaceVariant,
            onSurfaceVariant = palette.onSurfaceVariant,
            outline = palette.outline,
            outlineVariant = palette.outlineVariant,
            inverseSurface = inverseSurface,
            inverseOnSurface = inverseOnSurface,
            error = palette.error,
            onError = onError,
            errorContainer = palette.errorContainer,
            onErrorContainer = onErrorContainer,
            scrim = scrim,
        )
    }
}

/**
 * Mirror the active identity into the legacy Frost/Quartz tokens. Keeping
 * these observable means older screens (and the few custom-painted surfaces)
 * follow the selected scheme without a screen-by-screen rewrite.
 */
private fun applyLegacyTokens(palette: AltarisPalette, accent: ThemePalette) {
    val useAccent = accent != ThemePalette.SCHEME
    val primary = if (useAccent) accentFor(accent, palette.isDark) else palette.primary
    val onPrimary = if (useAccent) onAccentFor(accent, palette.isDark) else palette.onPrimary
    AltarisBackground = palette.base
    AltarisSurface = palette.surface
    AltarisCard = palette.surfaceVariant
    FrostSurface = palette.surface
    FrostSurfaceElevated = palette.surfaceElevated
    FrostHairline = palette.outlineVariant
    FrostTopSheen = palette.glassHighlight
    TextPrimary = palette.onSurface
    TextSecondary = palette.onSurfaceVariant
    TextMuted = palette.outline
    PrimaryAction = primary
    OnPrimaryAction = onPrimary
    StatusDot = primary
    AltarisDanger = palette.error
    AltarisDangerContainer = palette.errorContainer
    AltarisSwitchCheckedTrack = primary
    AltarisSwitchCheckedThumb = onPrimary
    AltarisSwitchUncheckedTrack = palette.surfaceVariant
    AltarisSwitchUncheckedThumb = palette.outline
    AltarisSwitchUncheckedBorder = palette.outline
    HeroGradientStops = palette.heroStops
    AccentGradientStops = palette.accentStops
}

/**
 * The app theme.
 *
 * [scheme] picks the identity (surfaces, glass, gradients). Passing the
 * default value means "follow the persisted choice", so the Settings picker
 * can switch identities without a restart. [accent] is the optional legacy
 * accent skin; null follows the persisted choice.
 */
@Composable
fun AltarisTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    dynamicColor: Boolean = false,
    scheme: AltarisScheme? = null,
    accent: ThemePalette? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    remember(context) {
        runCatching { StateManager.getScheme(context) }
            .getOrDefault(AltarisScheme.IRIDESCENT)
    }
    remember(context) {
        runCatching { StateManager.getThemePalette(context) }
            .getOrDefault(ThemePalette.SCHEME)
    }
    val persistedScheme by StateManager.scheme.collectAsState()
    val persistedAccent by StateManager.themePalette.collectAsState()

    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        else -> isSystemInDarkTheme()
    }
    // An explicit scheme wins; otherwise the persisted one does.
    val activeScheme = scheme ?: persistedScheme
    val activeAccent = accent ?: persistedAccent
    val palette = remember(activeScheme, darkTheme) {
        AltarisPalettes.of(activeScheme, darkTheme)
    }
    val colorScheme = remember(palette, activeAccent) {
        colorSchemeFor(palette, activeAccent)
    }

    val view = LocalView.current
    SideEffect {
        applyLegacyTokens(palette, activeAccent)
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        window.statusBarColor = colorScheme.background.toArgb()
        window.navigationBarColor = colorScheme.background.toArgb()
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalAltarisPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}

/** Legacy entry point — preserved so existing call sites keep compiling. */
@Composable
fun AltarisTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    dynamicColor: Boolean = false,
    scheme: AltarisScheme? = null,
    accent: ThemePalette? = null,
    content: @Composable () -> Unit
) {
    AltarisTheme(
        themeMode = themeMode,
        dynamicColor = dynamicColor,
        scheme = scheme,
        accent = accent,
        content = content
    )
}
