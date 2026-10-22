package com.altaris.linux.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.altaris.linux.BuildConfig
import com.altaris.linux.core.root.RootShell
import com.altaris.linux.core.system.CrashLog
import com.altaris.linux.core.system.PhantomProcessFixer
import com.altaris.linux.core.system.SharedStorageAccess
import com.altaris.linux.core.utils.StateManager
import com.altaris.linux.core.utils.ThemeMode
import com.altaris.linux.core.utils.ThemePalette
import com.altaris.linux.ui.components.AltarisTitle
import com.altaris.linux.ui.components.PrismCard
import com.altaris.linux.ui.components.PrismIconTile
import com.altaris.linux.ui.components.PrismOrb
import com.altaris.linux.ui.components.PrismRow
import com.altaris.linux.ui.components.PrismSectionLabel
import com.altaris.linux.ui.components.PrismSegmented
import com.altaris.linux.ui.theme.AltarisPalettes
import com.altaris.linux.ui.theme.AltarisScheme
import com.altaris.linux.ui.theme.AltarisSection
import com.altaris.linux.ui.theme.Prism
import com.altaris.linux.ui.theme.accentColor
import com.altaris.linux.ui.theme.rememberStaggerEntrance
import com.altaris.linux.ui.theme.sectionAccent
import com.altaris.linux.ui.theme.staggerIn

/**
 * Settings hub — Prism layout.
 *
 * Header: back tile + screen title. Sections: Device status, Color scheme,
 * Appearance, Environment & Host, Interface & Session, Containers, System &
 * About — all as PrismCard groups under PrismSectionLabel captions. Motion:
 * the shared staggered entrance only. Every row keeps its original
 * callback/route; nothing was dropped.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    permissionState: PermissionState,
    onStartService: (Intent) -> Unit,
    onStartActivity: (Intent) -> Unit,
    onNavigateToOnboarding: (() -> Unit)? = null,
    onNavigateToTroubleshooting: (() -> Unit)? = null,
    onNavigateToRootCheck: (() -> Unit)? = null,
    onNavigateToTerminalSettings: (() -> Unit)? = null,
    onNavigateToX11Settings: (() -> Unit)? = null,
    onNavigateToAudioSettings: (() -> Unit)? = null,
    onNavigateToChrootSettings: (() -> Unit)? = null,
    onNavigateToProotSettings: (() -> Unit)? = null,
    onNavigateToLegacyTermuxSettings: (() -> Unit)? = null,
    onNavigateToCrashLogs: (() -> Unit)? = null,
    onThemeChanged: ((ThemeMode) -> Unit)? = null,
    currentTheme: ThemeMode = ThemeMode.DARK
) {
    // Keep unused params so call sites stay stable (permission / theme hooks).
    @Suppress("UNUSED_PARAMETER")
    val _permissionState = permissionState
    @Suppress("UNUSED_PARAMETER")
    val _onStartService = onStartService
    @Suppress("UNUSED_PARAMETER")
    val _onStartActivity = onStartActivity

    val context = LocalContext.current

    // Theme palette (persisted via StateManager; applied by AltarisTheme).
    val themePalette by StateManager.themePalette.collectAsState()

    // Colour scheme (persisted via StateManager; applied by AltarisTheme).
    val scheme by StateManager.scheme.collectAsState()

    // The swatches follow the active mode, like the surfaces do.
    val darkMode = when (currentTheme) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        else -> isSystemInDarkTheme()
    }

    // Shared-storage grant refreshes on resume (backend preserved).
    val storageDeclared = SharedStorageAccess.declared(context)
    var lifecycleKey by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) lifecycleKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val storageGranted = remember(lifecycleKey, storageDeclared) {
        // OEM builds (MIUI) can throw SecurityException from the PackageManager /
        // Environment storage queries — a settings row must never crash the screen.
        if (storageDeclared) runCatching { SharedStorageAccess.granted() }.getOrDefault(false) else false
    }
    val storageActive = storageDeclared && storageGranted

    // Reuse the existing async probe (same one Home uses); callback is main-posted.
    var rootAvailable by remember { mutableStateOf(false) }
    LaunchedEffect(lifecycleKey) {
        RootShell.probeRootAvailable(forceClearCache = lifecycleKey > 0) { ok ->
            rootAvailable = ok
        }
    }

    val phantomApplicable = PhantomProcessFixer.applicable()
    var showPhantom by remember { mutableStateOf(false) }
    val phantomState = remember(showPhantom) {
        runCatching { PhantomProcessFixer.state(context) }
            .getOrDefault(PhantomProcessFixer.State.UNKNOWN)
    }

    // Newest-first crash reports written by CrashLog (0 -> empty state).
    val crashLogCount = remember(lifecycleKey) { CrashLog.files(context).size }

    // Settings share the Tools accent: they are part of the same section.
    val accent = sectionAccent(AltarisSection.TOOLS)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Prism.Gutter, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(Prism.Stack),
    ) {
        val entrance = rememberStaggerEntrance()

        // Compact header: back tile + screen title (version rides the subtitle).
        Row(
            modifier = Modifier.staggerIn(0, entrance),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PrismIconTile(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                accent = accent,
                contentDescription = "Back",
                onClick = onBack
            )
            Spacer(Modifier.width(10.dp))
            AltarisTitle(
                "Settings",
                "Altaris v${BuildConfig.VERSION_NAME} · Linux environments, on-device",
                accent,
                Modifier.weight(1f)
            )
        }

        // Device — live root probe + shared-storage grant (was the hero card).
        SettingsGroup("Device", accent, Modifier.staggerIn(1, entrance)) {
            PrismRow(
                title = "Root access",
                subtitle = if (rootAvailable) "Root available" else "No root · PRoot mode",
                accent = accent,
                leading = { PrismOrb(active = rootAvailable, accent = accent) },
                onClick = { onNavigateToRootCheck?.invoke() },
                divider = true,
            )
            PrismRow(
                title = "Shared storage",
                subtitle = if (storageActive) "Allowed: /sdcard and USB drives in guests"
                else "Not allowed: tap, then enable All files access",
                accent = accent,
                leading = { PrismOrb(active = storageActive, accent = accent) },
                onClick = { SharedStorageAccess.openSettings(context) },
            )
        }

        // Identity — the five Altaris schemes. Picking one restyles the app.
        SettingsGroup("Color scheme", accent, Modifier.staggerIn(2, entrance)) {
            AltarisScheme.entries.forEachIndexed { index, entry ->
                SchemeRow(
                    scheme = entry,
                    dark = darkMode,
                    selected = entry == scheme,
                    accent = accent,
                    onClick = { StateManager.setScheme(context, entry) },
                    divider = index < AltarisScheme.entries.lastIndex
                )
            }
        }

        // Appearance — light/dark mode and accent. Surfaces stay neutral.
        SettingsGroup("Appearance", accent, Modifier.staggerIn(3, entrance)) {
            ThemeModeRow(
                current = currentTheme,
                accent = accent,
                onSelect = { onThemeChanged?.invoke(it) },
            )
            ThemePaletteRow(
                current = themePalette,
                accent = accent,
                onSelect = { StateManager.setThemePalette(context, it) },
            )
        }

        // Environment & Host
        SettingsGroup("Environment & Host", accent, Modifier.staggerIn(4, entrance)) {
            if (storageDeclared) {
                PrismRow(
                    title = "Shared storage",
                    subtitle = if (storageGranted) "Allowed: /sdcard and USB drives in guests"
                    else "Not allowed: tap, then enable All files access",
                    accent = accent,
                    onClick = { SharedStorageAccess.openSettings(context) },
                    divider = true,
                )
            }
            if (phantomApplicable) {
                PrismRow(
                    title = "Process killer fix",
                    subtitle = "Phantom killer: " + when (phantomState) {
                        PhantomProcessFixer.State.DISABLED -> "Disabled"
                        PhantomProcessFixer.State.ENABLED -> "Enabled — tap to fix"
                        else -> "Unknown — tap to fix"
                    },
                    accent = accent,
                    onClick = { showPhantom = true },
                    divider = true,
                )
            }
            PrismRow(
                title = "Legacy Termux",
                subtitle = "Leftover installs from app versions ≤ v1.8.0",
                accent = accent,
                onClick = { onNavigateToLegacyTermuxSettings?.invoke() },
            )
        }

        // Interface & Session
        SettingsGroup("Interface & Session", accent, Modifier.staggerIn(5, entrance)) {
            PrismRow(
                title = "Terminal",
                subtitle = "Font zoom, extra keys, guest shell",
                accent = accent,
                onClick = { onNavigateToTerminalSettings?.invoke() },
                divider = true,
            )
            PrismRow(
                title = "X11 Display",
                subtitle = "Scale, fullscreen, embedded X11 input",
                accent = accent,
                onClick = { onNavigateToX11Settings?.invoke() },
                divider = true,
            )
            PrismRow(
                title = "Audio",
                subtitle = "PulseAudio status, start, logs",
                accent = accent,
                onClick = { onNavigateToAudioSettings?.invoke() },
            )
        }

        // Containers
        SettingsGroup("Containers", accent, Modifier.staggerIn(6, entrance)) {
            PrismRow(
                title = "PRoot",
                subtitle = "Installed containers, app-storage size",
                accent = accent,
                onClick = { onNavigateToProotSettings?.invoke() },
                divider = true,
            )
            PrismRow(
                title = "Chroot",
                subtitle = "Installed roots, size, orphan cleanup",
                accent = accent,
                onClick = { onNavigateToChrootSettings?.invoke() },
            )
        }

        // System & About
        SettingsGroup("System & About", accent, Modifier.staggerIn(7, entrance)) {
            if (onNavigateToOnboarding != null) {
                PrismRow(
                    title = "Show onboarding",
                    subtitle = "Replay the first-run tour",
                    accent = accent,
                    onClick = { onNavigateToOnboarding() },
                    divider = true,
                )
            }
            if (onNavigateToTroubleshooting != null) {
                PrismRow(
                    title = "Troubleshoot",
                    subtitle = "Diagnose common issues",
                    accent = accent,
                    onClick = { onNavigateToTroubleshooting() },
                    divider = true,
                )
            }
            if (onNavigateToCrashLogs != null) {
                PrismRow(
                    title = "Crash logs",
                    subtitle = when (crashLogCount) {
                        0 -> "No crash logs"
                        1 -> "1 saved report"
                        else -> "$crashLogCount saved reports"
                    },
                    accent = accent,
                    onClick = { onNavigateToCrashLogs() },
                    divider = true,
                )
            }
            PrismRow(
                title = "About Altaris",
                subtitle = "Author no_piece",
                accent = accent,
                divider = true,
            )
            PrismRow(
                title = "Version",
                subtitle = "v${BuildConfig.VERSION_NAME}",
                accent = accent,
                divider = true,
            )
            PrismRow(
                title = "Documentation",
                subtitle = "Setup guides and tutorials",
                accent = accent,
                onClick = { openUrl(context, "https://github.com/HakaI963/bliss#readme") },
            )
        }

        // Clears the floating bottom dock (64dp pill + 16dp margins = 96dp
        // footprint; the content viewport consumes the navigation-bar inset,
        // so 110dp always leaves the last row tappable).
        Spacer(Modifier.height(110.dp))
    }

    if (showPhantom) {
        com.altaris.linux.ui.components.PhantomProcessDialog(warning = false) { showPhantom = false }
    }
}

/** A captioned group of settings rows. */
@Composable
private fun SettingsGroup(
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Prism.Tight)
    ) {
        PrismSectionLabel(label, accent)
        PrismCard(accent = accent) { content() }
    }
}

/**
 * One identity in the scheme list: a live gradient swatch, the label and the
 * tagline. Tapping it persists the choice, which restyles the app at once.
 */
@Composable
private fun SchemeRow(
    scheme: AltarisScheme,
    dark: Boolean,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    divider: Boolean = false,
    modifier: Modifier = Modifier
) {
    val palette = remember(scheme, dark) { AltarisPalettes.of(scheme, dark) }
    val shape = RoundedCornerShape(12.dp)
    PrismRow(
        title = scheme.label,
        subtitle = scheme.tagline,
        accent = accent,
        modifier = modifier,
        leading = {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(shape)
                    .background(Brush.linearGradient(palette.heroStops))
                    .border(1.dp, palette.onSurface.copy(alpha = 0.18f), shape)
            )
        },
        trailing = {
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = accent,
                    modifier = Modifier.size(18.dp)
                )
            }
        },
        onClick = onClick,
        divider = divider
    )
}

/**
 * Theme mode row: Dark / Light / System. The choice is persisted by
 * MainActivity through ThemePreferences.
 */
@Composable
private fun ThemeModeRow(
    current: ThemeMode,
    accent: Color,
    onSelect: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val normalized = when (current) {
        ThemeMode.DARK -> ThemeMode.DARK
        ThemeMode.SYSTEM -> ThemeMode.SYSTEM
        else -> ThemeMode.LIGHT
    }
    Column(modifier = modifier.fillMaxWidth()) {
        PrismRow(
            title = "Theme",
            subtitle = "Dark is the default. Light is the same layout on paper surfaces.",
            accent = accent
        )
        Spacer(Modifier.height(Prism.Tight))
        PrismSegmented(
            options = listOf("Dark", "Light", "System"),
            selectedIndex = when (normalized) {
                ThemeMode.DARK -> 0
                ThemeMode.LIGHT -> 1
                else -> 2
            },
            accent = accent,
            modifier = Modifier.fillMaxWidth(),
            onSelect = { index ->
                onSelect(
                    when (index) {
                        0 -> ThemeMode.DARK
                        1 -> ThemeMode.LIGHT
                        else -> ThemeMode.SYSTEM
                    }
                )
            }
        )
    }
}

/**
 * Accent row: four compact choices. Surfaces remain AOSP neutral.
 */
@Composable
private fun ThemePaletteRow(
    current: ThemePalette,
    accent: Color,
    onSelect: (ThemePalette) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        PrismRow(
            title = "Accent color",
            subtitle = "Scheme follows the selected identity; the others tint primary controls.",
            accent = accent
        )
        Spacer(Modifier.height(Prism.Tight))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ThemePalette.entries.forEach { palette ->
                PalettePill(
                    label = palette.label,
                    dotColor = palette.accentColor(),
                    selected = palette == current,
                    accent = accent,
                    onClick = { onSelect(palette) }
                )
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun PalettePill(
    label: String,
    dotColor: Color,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(Prism.ChipRadius)
    Row(
        modifier = modifier
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.20f) else Color.Transparent)
            .border(
                1.dp,
                if (selected) accent.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant,
                shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        Log.e("Settings", "Error opening URL", e)
    }
}
