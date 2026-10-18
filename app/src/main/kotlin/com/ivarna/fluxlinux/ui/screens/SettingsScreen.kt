package com.ivarna.fluxlinux.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.ivarna.fluxlinux.ui.components.FrostContainer
import com.ivarna.fluxlinux.ui.components.FrostDivider
import com.ivarna.fluxlinux.ui.components.FrostRow
import com.ivarna.fluxlinux.ui.components.FrostTabPill
import com.ivarna.fluxlinux.ui.components.folioSpring
import com.ivarna.fluxlinux.ui.theme.FolioFrostBorder
import com.ivarna.fluxlinux.ui.theme.FolioWordmarkStyle
import com.ivarna.fluxlinux.ui.theme.FrostHairline
import com.ivarna.fluxlinux.ui.theme.StatusDot
import com.ivarna.fluxlinux.ui.theme.TextMuted
import com.ivarna.fluxlinux.ui.theme.TextPrimary
import com.ivarna.fluxlinux.ui.theme.TextSecondary
import com.ivarna.fluxlinux.ui.theme.accentColor
import com.ivarna.fluxlinux.ui.theme.rememberStaggerEntrance
import com.ivarna.fluxlinux.ui.theme.rememberStaggerProgress
import com.ivarna.fluxlinux.ui.theme.staggerIn

/**
 * Settings hub: hero wordmark card + frosted groups with icon-plate rows.
 * Motion: staggered entrance (30ms steps, 240ms each), press-scale rows,
 * animated section rules. Every row keeps its original callback/route.
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
        val entrance = rememberStaggerEntrance()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            SettingsHero(
                versionName = BuildConfig.VERSION_NAME,
                rootAvailable = rootAvailable,
                storageDeclared = storageDeclared,
                storageGranted = storageGranted,
                onRootClick = { onNavigateToRootCheck?.invoke() },
                onStorageClick = { SharedStorageAccess.openSettings(context) },
                modifier = Modifier.staggerIn(0, entrance).padding(top = 12.dp)
            )

            // 1 — Theme
            SettingsSection(
                title = "Theme",
                headerIndex = 1,
                containerIndex = 2,
                entrance = entrance
            ) {
                ThemePalettePicker(
                    current = themePalette,
                    onSelect = { StateManager.setThemePalette(context, it) }
                )
            }

            // 2 — Environment & Host
            SettingsSection(
                title = "Environment & Host",
                headerIndex = 3,
                containerIndex = 4,
                entrance = entrance
            ) {
                if (storageDeclared) {
                    FrostRow(
                        icon = Icons.Default.SdStorage,
                        title = "Shared storage",
                        subtitle = if (storageGranted) "Allowed: /sdcard and USB drives in guests"
                        else "Not allowed: tap, then enable All files access",
                        onClick = { SharedStorageAccess.openSettings(context) }
                    )
                    FrostDivider(startInset = 68.dp)
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
                    FrostDivider(startInset = 68.dp)
                }
                FrostRow(
                    icon = Icons.Default.History,
                    title = "Legacy Termux",
                    subtitle = "Leftover installs from app versions ≤ v1.8.0",
                    onClick = { onNavigateToLegacyTermuxSettings?.invoke() }
                )
            }

            // 3 — Interface & Session
            SettingsSection(
                title = "Interface & Session",
                headerIndex = 5,
                containerIndex = 6,
                entrance = entrance
            ) {
                FrostRow(
                    icon = Icons.Default.Terminal,
                    title = "Terminal",
                    subtitle = "Font zoom, extra keys, guest shell",
                    onClick = { onNavigateToTerminalSettings?.invoke() }
                )
                FrostDivider(startInset = 68.dp)
                FrostRow(
                    icon = Icons.Default.DisplaySettings,
                    title = "X11 Display",
                    subtitle = "Scale, fullscreen, embedded X11 input",
                    onClick = { onNavigateToX11Settings?.invoke() }
                )
                FrostDivider(startInset = 68.dp)
                FrostRow(
                    icon = Icons.Default.VolumeUp,
                    title = "Audio",
                    subtitle = "PulseAudio status, start, logs",
                    onClick = { onNavigateToAudioSettings?.invoke() }
                )
            }

            // 4 — Containers
            SettingsSection(
                title = "Containers",
                headerIndex = 7,
                containerIndex = 8,
                entrance = entrance
            ) {
                FrostRow(
                    icon = Icons.Default.Folder,
                    title = "PRoot",
                    subtitle = "Installed containers, app-storage size",
                    onClick = { onNavigateToProotSettings?.invoke() }
                )
                FrostDivider(startInset = 68.dp)
                FrostRow(
                    icon = Icons.Default.Storage,
                    title = "Chroot",
                    subtitle = "Installed roots, size, orphan cleanup",
                    onClick = { onNavigateToChrootSettings?.invoke() }
                )
            }

            // 5 — System & About
            SettingsSection(
                title = "System & About",
                headerIndex = 9,
                containerIndex = 10,
                entrance = entrance
            ) {
                if (onNavigateToOnboarding != null) {
                    FrostRow(
                        icon = Icons.Default.School,
                        title = "Show onboarding",
                        subtitle = "Replay the first-run tour",
                        onClick = { onNavigateToOnboarding() }
                    )
                    FrostDivider(startInset = 68.dp)
                }
                if (onNavigateToTroubleshooting != null) {
                    FrostRow(
                        icon = Icons.Default.Build,
                        title = "Troubleshoot",
                        subtitle = "Diagnose common issues",
                        onClick = { onNavigateToTroubleshooting() }
                    )
                    FrostDivider(startInset = 68.dp)
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
                    FrostDivider(startInset = 68.dp)
                }
                FrostRow(
                    icon = Icons.Default.Person,
                    title = "About Bliss",
                    subtitle = "Author no_piece",
                    onClick = {},
                    chevron = false
                )
                FrostDivider(startInset = 68.dp)
                FrostRow(
                    icon = Icons.Default.Tag,
                    title = "Version",
                    subtitle = "v${BuildConfig.VERSION_NAME}",
                    onClick = {},
                    chevron = false
                )
                FrostDivider(startInset = 68.dp)
                FrostRow(
                    icon = Icons.Default.MenuBook,
                    title = "Documentation",
                    subtitle = "Setup guides and tutorials",
                    onClick = { openUrl(context, "https://github.com/abhay-byte/fluxlinux#readme") }
                )
            }

            // Clears the floating bottom dock (64dp pill + 16dp margins = 96dp
            // footprint; the content viewport consumes the navigation-bar inset,
            // so 110dp always leaves the last row tappable).
            Spacer(Modifier.height(110.dp))
        }
    }

    if (showPhantom) {
        com.ivarna.fluxlinux.ui.components.PhantomProcessDialog(warning = false) { showPhantom = false }
    }
}

/**
 * Hero: "Bliss" wordmark, build-version chip, and a live device-status
 * line reusing existing reads only (root probe + shared-storage grant).
 */
@Composable
private fun SettingsHero(
    versionName: String,
    rootAvailable: Boolean,
    storageDeclared: Boolean,
    storageGranted: Boolean,
    onRootClick: () -> Unit,
    onStorageClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val storageActive = storageDeclared && storageGranted
    FrostContainer(modifier = modifier, corner = 20.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(0.75.dp, FolioFrostBorder, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = null,
                    tint = TextPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Bliss",
                    style = FolioWordmarkStyle,
                    color = TextPrimary
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Linux environments, on-device",
                    fontSize = 12.sp,
                    color = TextMuted,
                    maxLines = 1
                )
            }
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.07f))
                    .border(0.75.dp, FolioFrostBorder, RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = "v$versionName",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextSecondary,
                    maxLines = 1
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .height(0.5.dp)
                .background(FrostHairline)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HeroStatusChip(
                label = if (rootAvailable) "Root ready" else "No root · PRoot",
                active = rootAvailable,
                statusDescription = if (rootAvailable) {
                    "Device status: root available. Opens root access."
                } else {
                    "Device status: no root, PRoot mode. Opens root access."
                },
                onClick = onRootClick
            )
            HeroStatusChip(
                label = if (storageActive) "Storage allowed" else "Storage off",
                active = storageActive,
                statusDescription = if (storageActive) {
                    "Device status: shared storage allowed. Opens storage settings."
                } else {
                    "Device status: shared storage off. Opens storage settings."
                },
                onClick = onStorageClick
            )
        }
    }
}

@Composable
private fun HeroStatusChip(
    label: String,
    active: Boolean,
    statusDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = folioSpring(),
        label = "heroChipPress"
    )
    Row(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.05f))
            .border(0.75.dp, FolioFrostBorder, RoundedCornerShape(50))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .semantics(mergeDescendants = true) { contentDescription = statusDescription }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (active) StatusDot else TextMuted)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = TextSecondary,
            maxLines = 1
        )
    }
}

/**
 * One settings group: animated header (fade + rule) followed by the frosted
 * container, both on the shared stagger clock.
 */
@Composable
private fun SettingsSection(
    title: String,
    headerIndex: Int,
    containerIndex: Int,
    entrance: Boolean,
    rows: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        SettingsSectionHeader(title, headerIndex, entrance)
        FrostContainer(
            modifier = Modifier
                .staggerIn(containerIndex, entrance)
                .animateContentSize()
        ) {
            rows()
        }
    }
}

@Composable
private fun SettingsSectionHeader(
    text: String,
    index: Int,
    entrance: Boolean,
    modifier: Modifier = Modifier
) {
    val progress = rememberStaggerProgress(index, entrance)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = progress }
            .padding(start = 8.dp, end = 8.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp,
            color = TextSecondary,
            maxLines = 1
        )
        Spacer(Modifier.width(10.dp))
        // Hairline draws in with the header (alpha rides the same progress).
        Box(
            modifier = Modifier
                .weight(1f)
                .height(0.5.dp)
                .graphicsLayer { alpha = 0.6f * progress }
                .background(FrostHairline)
        )
    }
}

/**
 * Compact palette picker: one swatch pill per palette (primary-color dot +
 * label). Selection writes through StateManager; FluxLinuxTheme observes the
 * same flow, so the whole UI re-skins without leaving Settings.
 */
@Composable
private fun ThemePalettePicker(
    current: ThemePalette,
    onSelect: (ThemePalette) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = "Palette",
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
            modifier = Modifier.padding(start = 6.dp)
        )
        Text(
            text = "Dark interface accent and surfaces",
            fontSize = 12.sp,
            color = TextMuted,
            maxLines = 1,
            modifier = Modifier.padding(start = 6.dp, top = 1.dp)
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ThemePalette.entries.forEach { palette ->
                FrostTabPill(
                    selected = palette == current,
                    onClick = { onSelect(palette) },
                    icon = {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(palette.accentColor())
                        )
                    }
                ) {
                    Text(
                        text = palette.label,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (palette == current) TextPrimary else TextSecondary,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        Log.e("Settings", "Error opening URL", e)
    }
}
