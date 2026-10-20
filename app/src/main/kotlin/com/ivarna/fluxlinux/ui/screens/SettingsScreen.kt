package com.ivarna.fluxlinux.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.ivarna.fluxlinux.BuildConfig
import com.ivarna.fluxlinux.core.root.RootShell
import com.ivarna.fluxlinux.core.system.CrashLog
import com.ivarna.fluxlinux.core.system.PhantomProcessFixer
import com.ivarna.fluxlinux.core.system.SharedStorageAccess
import com.ivarna.fluxlinux.core.utils.StateManager
import com.ivarna.fluxlinux.core.utils.ThemeMode
import com.ivarna.fluxlinux.core.utils.ThemePalette
import com.ivarna.fluxlinux.ui.components.QCard
import com.ivarna.fluxlinux.ui.components.QDivider
import com.ivarna.fluxlinux.ui.components.QPill
import com.ivarna.fluxlinux.ui.components.QRow
import com.ivarna.fluxlinux.ui.components.QScreenTitle
import com.ivarna.fluxlinux.ui.components.QSectionHeader
import com.ivarna.fluxlinux.ui.components.Quartz
import com.ivarna.fluxlinux.ui.theme.accentColor
import com.ivarna.fluxlinux.ui.theme.fluxMutedText
import com.ivarna.fluxlinux.ui.theme.rememberStaggerEntrance
import com.ivarna.fluxlinux.ui.theme.staggerIn

/**
 * Settings hub — compact Quartz layout.
 *
 * Header: back arrow + title. Sections: Device status, Theme, Environment &
 * Host, Interface & Session, Containers, System & About — all as QCard groups
 * with QSectionHeader labels. Motion: the shared staggered entrance only.
 * Every row keeps its original callback/route; nothing was dropped.
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

    // Theme palette (persisted via StateManager; applied by FluxLinuxTheme).
    val themePalette by StateManager.themePalette.collectAsState()

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Quartz.ScreenPad, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(Quartz.Gap),
    ) {
        val entrance = rememberStaggerEntrance()

        // Compact header: back arrow + screen title (version rides the subtitle).
        Row(
            modifier = Modifier.staggerIn(0, entrance),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
            QScreenTitle("Settings", "Bliss v${BuildConfig.VERSION_NAME} · Linux environments, on-device")
        }

        // Device — live root probe + shared-storage grant (was the hero card).
        QSectionHeader("Device", modifier = Modifier.staggerIn(1, entrance))
        QCard(modifier = Modifier.staggerIn(1, entrance)) {
            QRow(
                title = "Root access",
                subtitle = if (rootAvailable) "Root available" else "No root · PRoot mode",
                leading = { QDot(active = rootAvailable) },
                onClick = { onNavigateToRootCheck?.invoke() },
                divider = true,
            )
            QRow(
                title = "Shared storage",
                subtitle = if (storageActive) "Allowed: /sdcard and USB drives in guests"
                else "Not allowed: tap, then enable All files access",
                leading = { QDot(active = storageActive) },
                onClick = { SharedStorageAccess.openSettings(context) },
            )
        }

        // Appearance — light/dark mode and accent. Surfaces stay neutral.
        QSectionHeader("Appearance", modifier = Modifier.staggerIn(2, entrance))
        QCard(modifier = Modifier.staggerIn(2, entrance)) {
            ThemeModeRow(
                current = currentTheme,
                onSelect = { onThemeChanged?.invoke(it) },
            )
            QDivider(Modifier.padding(start = Quartz.CardPad))
            ThemePaletteRow(
                current = themePalette,
                onSelect = { StateManager.setThemePalette(context, it) },
            )
        }

        // Environment & Host
        QSectionHeader("Environment & Host", modifier = Modifier.staggerIn(3, entrance))
        QCard(modifier = Modifier.staggerIn(3, entrance)) {
            if (storageDeclared) {
                QRow(
                    title = "Shared storage",
                    subtitle = if (storageGranted) "Allowed: /sdcard and USB drives in guests"
                    else "Not allowed: tap, then enable All files access",
                    onClick = { SharedStorageAccess.openSettings(context) },
                    divider = true,
                )
            }
            if (phantomApplicable) {
                QRow(
                    title = "Process killer fix",
                    subtitle = "Phantom killer: " + when (phantomState) {
                        PhantomProcessFixer.State.DISABLED -> "Disabled"
                        PhantomProcessFixer.State.ENABLED -> "Enabled — tap to fix"
                        else -> "Unknown — tap to fix"
                    },
                    onClick = { showPhantom = true },
                    divider = true,
                )
            }
            QRow(
                title = "Legacy Termux",
                subtitle = "Leftover installs from app versions ≤ v1.8.0",
                onClick = { onNavigateToLegacyTermuxSettings?.invoke() },
            )
        }

        // Interface & Session
        QSectionHeader("Interface & Session", modifier = Modifier.staggerIn(4, entrance))
        QCard(modifier = Modifier.staggerIn(4, entrance)) {
            QRow(
                title = "Terminal",
                subtitle = "Font zoom, extra keys, guest shell",
                onClick = { onNavigateToTerminalSettings?.invoke() },
                divider = true,
            )
            QRow(
                title = "X11 Display",
                subtitle = "Scale, fullscreen, embedded X11 input",
                onClick = { onNavigateToX11Settings?.invoke() },
                divider = true,
            )
            QRow(
                title = "Audio",
                subtitle = "PulseAudio status, start, logs",
                onClick = { onNavigateToAudioSettings?.invoke() },
            )
        }

        // Containers
        QSectionHeader("Containers", modifier = Modifier.staggerIn(5, entrance))
        QCard(modifier = Modifier.staggerIn(5, entrance)) {
            QRow(
                title = "PRoot",
                subtitle = "Installed containers, app-storage size",
                onClick = { onNavigateToProotSettings?.invoke() },
                divider = true,
            )
            QRow(
                title = "Chroot",
                subtitle = "Installed roots, size, orphan cleanup",
                onClick = { onNavigateToChrootSettings?.invoke() },
            )
        }

        // System & About
        QSectionHeader("System & About", modifier = Modifier.staggerIn(6, entrance))
        QCard(modifier = Modifier.staggerIn(6, entrance)) {
            if (onNavigateToOnboarding != null) {
                QRow(
                    title = "Show onboarding",
                    subtitle = "Replay the first-run tour",
                    onClick = { onNavigateToOnboarding() },
                    divider = true,
                )
            }
            if (onNavigateToTroubleshooting != null) {
                QRow(
                    title = "Troubleshoot",
                    subtitle = "Diagnose common issues",
                    onClick = { onNavigateToTroubleshooting() },
                    divider = true,
                )
            }
            if (onNavigateToCrashLogs != null) {
                QRow(
                    title = "Crash logs",
                    subtitle = when (crashLogCount) {
                        0 -> "No crash logs"
                        1 -> "1 saved report"
                        else -> "$crashLogCount saved reports"
                    },
                    onClick = { onNavigateToCrashLogs() },
                    divider = true,
                )
            }
            QRow(
                title = "About Bliss",
                subtitle = "Author no_piece",
                divider = true,
            )
            QRow(
                title = "Version",
                subtitle = "v${BuildConfig.VERSION_NAME}",
                divider = true,
            )
            QRow(
                title = "Documentation",
                subtitle = "Setup guides and tutorials",
                onClick = { openUrl(context, "https://github.com/HakaI963/bliss#readme") },
            )
        }

        // Clears the floating bottom dock (64dp pill + 16dp margins = 96dp
        // footprint; the content viewport consumes the navigation-bar inset,
        // so 110dp always leaves the last row tappable).
        Spacer(Modifier.height(110.dp))
    }

    if (showPhantom) {
        com.ivarna.fluxlinux.ui.components.PhantomProcessDialog(warning = false) { showPhantom = false }
    }
}

/** Tiny status dot (7 dp): primary when active, muted when not. */
@Composable
private fun QDot(active: Boolean) {
    Box(
        modifier = Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(
                if (active) MaterialTheme.colorScheme.primary
                else fluxMutedText().copy(alpha = 0.4f)
            ),
    )
}

/**
 * Theme mode row: Light / Dark / System. Light is the shipped default; the
 * choice is persisted by MainActivity through ThemePreferences.
 */
@Composable
private fun ThemeModeRow(
    current: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val normalized = when (current) {
        ThemeMode.DARK -> ThemeMode.DARK
        ThemeMode.SYSTEM -> ThemeMode.SYSTEM
        else -> ThemeMode.LIGHT
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Quartz.CardPad, vertical = 10.dp)
    ) {
        Text(
            text = "Theme",
            fontSize = Quartz.RowTitleSize,
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Midnight is the default. Day is the same layout on paper surfaces.",
            fontSize = Quartz.RowSubSize,
            color = fluxMutedText(),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QPill("Midnight", normalized == ThemeMode.DARK) { onSelect(ThemeMode.DARK) }
            QPill("Day", normalized == ThemeMode.LIGHT) { onSelect(ThemeMode.LIGHT) }
            QPill("System", normalized == ThemeMode.SYSTEM) { onSelect(ThemeMode.SYSTEM) }
        }
    }
}

/**
 * Accent row: four compact choices. Surfaces remain AOSP neutral.
 */
@Composable
private fun ThemePaletteRow(
    current: ThemePalette,
    onSelect: (ThemePalette) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Quartz.CardPad + 2.dp, vertical = 8.dp)
    ) {
        Text(
            text = "Accent color",
            fontSize = Quartz.RowTitleSize,
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Used for buttons, selected tabs and highlights.",
            fontSize = Quartz.RowSubSize,
            color = fluxMutedText(),
            maxLines = 1,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ThemePalette.entries.forEach { palette ->
                PalettePill(
                    label = palette.label,
                    dotColor = palette.accentColor(),
                    selected = palette == current,
                    onClick = { onSelect(palette) },
                )
            }
        }
    }
}

@Composable
private fun PalettePill(
    label: String,
    dotColor: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .clip(Quartz.PillShape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = label,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = fg,
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
