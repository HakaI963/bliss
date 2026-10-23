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

val LocalAltarisPalette = staticCompositionLocalOf { AltarisPalettes.Graphite }

/**
 * Builds the Material3 scheme for an identity. The identity owns every
 * colour; there is no separate accent skin any more.
 */
private fun colorSchemeFor(palette: AltarisPalette): ColorScheme {
    val onError = if (palette.isDark) Color(0xFF3A0907) else Color.White
    val onErrorContainer = if (palette.isDark) Color(0xFFFFD9D6) else Color(0xFF410E0B)
    val inverseSurface = if (palette.isDark) palette.onSurface.copy(alpha = 0.92f) else palette.base
    val inverseOnSurface = if (palette.isDark) palette.base else palette.onSurface
    val scrim = Color.Black

    // One identity, one palette: the dark and light variants of the Material
    // scheme carry exactly the same tokens.
    return darkColorScheme(
        primary = palette.action,
        onPrimary = palette.onAction,
        primaryContainer = palette.surfaceRaised,
        onPrimaryContainer = palette.onSurface,
        secondary = palette.onSurfaceVariant,
        onSecondary = palette.base,
        secondaryContainer = palette.surfaceRaised,
        onSecondaryContainer = palette.onSurface,
        tertiary = palette.onSurfaceVariant,
        onTertiary = palette.base,
        tertiaryContainer = palette.surfaceRaised,
        onTertiaryContainer = palette.onSurface,
        background = palette.base,
        onBackground = palette.onSurface,
        surface = palette.surface,
        onSurface = palette.onSurface,
        surfaceVariant = palette.panel,
        onSurfaceVariant = palette.onSurfaceVariant,
        outline = palette.border,
        outlineVariant = palette.border,
        inverseSurface = inverseSurface,
        inverseOnSurface = inverseOnSurface,
        error = palette.error,
        onError = onError,
        errorContainer = palette.error.copy(alpha = 0.16f),
        onErrorContainer = onErrorContainer,
        scrim = scrim,
    )
}

/**
 * Mirror the active identity into the legacy Frost/Quartz tokens. Keeping
 * these observable means older screens (and the few custom-painted surfaces)
 * follow the selected scheme without a screen-by-screen rewrite.
 */
private fun applyLegacyTokens(palette: AltarisPalette) {
    val primary = palette.action
    val onPrimary = palette.onAction
    AltarisBackground = palette.base
    AltarisSurface = palette.surface
    AltarisCard = palette.panel
    FrostSurface = palette.surface
    FrostSurfaceElevated = palette.panel
    FrostHairline = palette.border
    FrostTopSheen = palette.onSurface.copy(alpha = 0.10f)
    TextPrimary = palette.onSurface
    TextSecondary = palette.onSurfaceVariant
    TextMuted = palette.border
    PrimaryAction = primary
    OnPrimaryAction = onPrimary
    StatusDot = primary
    AltarisDanger = palette.error
    AltarisDangerContainer = palette.error.copy(alpha = 0.16f)
    AltarisSwitchCheckedTrack = primary
    AltarisSwitchCheckedThumb = onPrimary
    AltarisSwitchUncheckedTrack = palette.panel
    AltarisSwitchUncheckedThumb = palette.border
    AltarisSwitchUncheckedBorder = palette.border
}

/**
 * The app theme.
 *
 * [scheme] picks the identity (surfaces, glass, gradients). Passing the
 * default value means "follow the persisted choice", so the Settings picker
 * can switch identities without a restart. Passing a scheme overrides the
 * persisted choice for that composition.
 */
@Composable
fun AltarisTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    dynamicColor: Boolean = false,
    scheme: AltarisScheme? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    remember(context) {
        runCatching { StateManager.getScheme(context) }
            .getOrDefault(AltarisScheme.GRAPHITE)
    }
    val persistedScheme by StateManager.scheme.collectAsState()

    // An explicit scheme wins; otherwise the persisted one does.
    val activeScheme = scheme ?: persistedScheme
    val palette = remember(activeScheme) {
        AltarisPalettes.of(activeScheme)
    }
    // The identity is the light/dark decision. ThemeMode is kept for old
    // callers, but a Warm Neutral app must not keep white status-bar icons
    // just because the legacy preference still says "dark".
    val darkTheme = palette.isDark
    val colorScheme = remember(palette) {
        colorSchemeFor(palette)
    }

    val view = LocalView.current
    SideEffect {
        applyLegacyTokens(palette)
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        window.statusBarColor = colorScheme.background.toArgb()
        window.navigationBarColor = colorScheme.background.toArgb()
        // The window background is the app background: routes that do not
        // paint their own surface (Settings, troubleshooting, …) must still
        // follow the active identity.
        window.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(colorScheme.background.toArgb())
        )
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

/**
 * Legacy entry point — kept so existing call sites keep compiling.
 * Prefer [AltarisTheme].
 */
@Composable
fun AltarisAppTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    dynamicColor: Boolean = false,
    scheme: AltarisScheme? = null,
    content: @Composable () -> Unit
) {
    AltarisTheme(
        themeMode = themeMode,
        dynamicColor = dynamicColor,
        scheme = scheme,
        content = content
    )
}
