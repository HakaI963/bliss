package com.ivarna.fluxlinux.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

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
    themeMode: com.ivarna.fluxlinux.core.utils.ThemeMode = com.ivarna.fluxlinux.core.utils.ThemeMode.DARK,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        com.ivarna.fluxlinux.core.utils.ThemeMode.LIGHT -> false
        com.ivarna.fluxlinux.core.utils.ThemeMode.DARK -> true
        else -> isSystemInDarkTheme()
    }
    val colorScheme = if (darkTheme) FolioDarkScheme else FolioLightScheme

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
    themeMode: com.ivarna.fluxlinux.core.utils.ThemeMode = com.ivarna.fluxlinux.core.utils.ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    FolioTheme(themeMode = themeMode, dynamicColor = false, content = content)
}
