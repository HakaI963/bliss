package com.ivarna.fluxlinux.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.ivarna.fluxlinux.BuildConfig
import com.ivarna.fluxlinux.core.system.CrashLog
import com.ivarna.fluxlinux.core.system.PhantomProcessFixer
import com.ivarna.fluxlinux.core.system.SharedStorageAccess
import com.ivarna.fluxlinux.core.utils.ThemeMode
import com.ivarna.fluxlinux.ui.components.FrostContainer
import com.ivarna.fluxlinux.ui.components.FrostDivider
import com.ivarna.fluxlinux.ui.components.FrostGroupTitle
import com.ivarna.fluxlinux.ui.components.FrostRow

/**
 * Settings hub: 4 frosted groups in shared FrostContainer style.
 * No giant buttons, no bitmap logos — monochrome rows with hairline dividers.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
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
    currentTheme: ThemeMode = ThemeMode.SYSTEM
) {
    // Keep unused params so call sites stay stable (permission / theme hooks).
    @Suppress("UNUSED_PARAMETER")
    val _permissionState = permissionState
    @Suppress("UNUSED_PARAMETER")
    val _onStartService = onStartService
    @Suppress("UNUSED_PARAMETER")
    val _onStartActivity = onStartActivity
    @Suppress("UNUSED_PARAMETER")
    val _onThemeChanged = onThemeChanged
    @Suppress("UNUSED_PARAMETER")
    val _currentTheme = currentTheme
    @Suppress("UNUSED_PARAMETER")
    val _onNavigateToRootCheck = onNavigateToRootCheck

    val context = LocalContext.current

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

    val phantomApplicable = PhantomProcessFixer.applicable()
    var showPhantom by remember { mutableStateOf(false) }
    val phantomState = remember(showPhantom) {
        runCatching { PhantomProcessFixer.state(context) }
            .getOrDefault(PhantomProcessFixer.State.UNKNOWN)
    }

    // Newest-first crash reports written by CrashLog (0 -> empty state).
    val crashLogCount = remember(lifecycleKey) { CrashLog.files(context).size }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Settings",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 1 — Environment & Host
            FrostGroupTitle("Environment & Host")
            FrostContainer {
                if (storageDeclared) {
                    FrostRow(
                        icon = Icons.Default.SdStorage,
                        title = "Shared storage",
                        subtitle = if (storageGranted) "Allowed: /sdcard and USB drives in guests"
                        else "Not allowed: tap, then enable All files access",
                        onClick = { SharedStorageAccess.openSettings(context) }
                    )
                    FrostDivider()
                }
                if (phantomApplicable) {
                    FrostRow(
                        icon = Icons.Default.Warning,
                        title = "Process killer fix",
                        subtitle = "Phantom killer: " + when (phantomState) {
                            PhantomProcessFixer.State.DISABLED -> "Disabled"
                            PhantomProcessFixer.State.ENABLED -> "Enabled — tap to fix"
                            else -> "Unknown — tap to fix"
                        },
                        onClick = { showPhantom = true }
                    )
                    FrostDivider()
                }
                FrostRow(
                    icon = Icons.Default.History,
                    title = "Legacy Termux",
                    subtitle = "Leftover installs from Folio ≤ v1.8.0",
                    onClick = { onNavigateToLegacyTermuxSettings?.invoke() }
                )
            }

            // 2 — Interface & Session
            FrostGroupTitle("Interface & Session")
            FrostContainer {
                FrostRow(
                    icon = Icons.Default.Terminal,
                    title = "Terminal",
                    subtitle = "Font zoom, extra keys, guest shell",
                    onClick = { onNavigateToTerminalSettings?.invoke() }
                )
                FrostDivider()
                FrostRow(
                    icon = Icons.Default.DisplaySettings,
                    title = "X11 Display",
                    subtitle = "Scale, fullscreen, embedded X11 input",
                    onClick = { onNavigateToX11Settings?.invoke() }
                )
                FrostDivider()
                FrostRow(
                    icon = Icons.Default.VolumeUp,
                    title = "Audio",
                    subtitle = "PulseAudio status, start, logs",
                    onClick = { onNavigateToAudioSettings?.invoke() }
                )
            }

            // 3 — Containers
            FrostGroupTitle("Containers")
            FrostContainer {
                FrostRow(
                    icon = Icons.Default.Folder,
                    title = "PRoot",
                    subtitle = "Installed containers, app-storage size",
                    onClick = { onNavigateToProotSettings?.invoke() }
                )
                FrostDivider()
                FrostRow(
                    icon = Icons.Default.Storage,
                    title = "Chroot",
                    subtitle = "Installed roots, size, orphan cleanup",
                    onClick = { onNavigateToChrootSettings?.invoke() }
                )
            }

            // 4 — System & About
            FrostGroupTitle("System & About")
            FrostContainer {
                if (onNavigateToOnboarding != null) {
                    FrostRow(
                        icon = Icons.Default.School,
                        title = "Show onboarding",
                        subtitle = "Replay the first-run tour",
                        onClick = { onNavigateToOnboarding() }
                    )
                    FrostDivider()
                }
                if (onNavigateToTroubleshooting != null) {
                    FrostRow(
                        icon = Icons.Default.Build,
                        title = "Troubleshoot",
                        subtitle = "Diagnose common issues",
                        onClick = { onNavigateToTroubleshooting() }
                    )
                    FrostDivider()
                }
                if (onNavigateToCrashLogs != null) {
                    FrostRow(
                        icon = Icons.Default.BugReport,
                        title = "Crash logs",
                        subtitle = when (crashLogCount) {
                            0 -> "No crash logs"
                            1 -> "1 saved report"
                            else -> "$crashLogCount saved reports"
                        },
                        onClick = { onNavigateToCrashLogs() }
                    )
                    FrostDivider()
                }
                FrostRow(
                    icon = Icons.Default.Person,
                    title = "About Folio",
                    subtitle = "Author no_piece",
                    onClick = {},
                    chevron = false
                )
                FrostDivider()
                FrostRow(
                    icon = Icons.Default.Tag,
                    title = "Version",
                    subtitle = "v${BuildConfig.VERSION_NAME}",
                    onClick = {},
                    chevron = false
                )
                FrostDivider()
                FrostRow(
                    icon = Icons.Default.MenuBook,
                    title = "Documentation",
                    subtitle = "Setup guides and tutorials",
                    onClick = { openUrl(context, "https://github.com/abhay-byte/fluxlinux#readme") }
                )
            }

            // Clears the floating bottom dock (64dp pill + 16dp margins = 80dp
            // footprint over the inset-consuming content viewport) with spare air,
            // matching Home/Distro's 110dp reserve.
            Spacer(Modifier.height(110.dp))
        }
    }

    if (showPhantom) {
        com.ivarna.fluxlinux.ui.components.PhantomProcessDialog(warning = false) { showPhantom = false }
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        Log.e("Settings", "Error opening URL", e)
    }
}
