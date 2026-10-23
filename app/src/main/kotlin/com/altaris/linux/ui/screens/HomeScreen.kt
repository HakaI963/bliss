package com.altaris.linux.ui.screens

import android.content.ClipData
import kotlinx.coroutines.withContext
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.altaris.linux.core.data.Distro
import com.altaris.linux.core.data.DistroRepository
import com.altaris.linux.core.data.ScriptManager
import com.altaris.linux.core.desktop.DesktopLauncher
import com.altaris.linux.core.desktop.DesktopSession
import com.altaris.linux.core.desktop.DesktopSessionQuery
import com.altaris.linux.core.root.RootShell
import com.altaris.linux.core.terminal.TerminalLauncher
import com.altaris.linux.core.utils.StateManager
import com.altaris.linux.ui.components.AltarisTitle
import com.altaris.linux.ui.components.HomeHero
import com.altaris.linux.ui.components.PrismChip
import com.altaris.linux.ui.components.PrismButton
import com.altaris.linux.ui.components.PrismCard
import com.altaris.linux.ui.components.PrismChip
import com.altaris.linux.ui.components.PrismIconTile
import com.altaris.linux.ui.components.PrismDivider
import com.altaris.linux.ui.components.PrismRow
import com.altaris.linux.ui.components.PrismStatRow
import com.altaris.linux.ui.components.PrismSectionLabel
import com.altaris.linux.ui.components.PrismOrb
import com.altaris.linux.ui.components.QDivider
import com.altaris.linux.ui.components.isChrootCard
import com.altaris.linux.ui.theme.AltarisSection
import com.altaris.linux.ui.theme.Prism
import com.altaris.linux.ui.theme.altarisMutedText
import com.altaris.linux.ui.theme.rememberStaggerEntrance
import com.altaris.linux.ui.theme.sectionAccent
import com.altaris.linux.ui.theme.staggerIn
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.launch

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun HomeScreen(
    permissionState: PermissionState,
    hazeState: HazeState,
    scriptRefreshTrigger: Int = 0,
    onStartService: (android.content.Intent) -> Unit,
    onStartActivity: (android.content.Intent) -> Unit,
    onNavigateToInstall: (com.altaris.linux.core.data.Distro) -> Unit,
    onNavigateToSettings: (com.altaris.linux.core.data.Distro) -> Unit,
    onOpenTerminal: (distroId: String, root: Boolean) -> Unit = { _, _ -> },
    /** Switch bottom-nav to Terminal after an in-app session is opened (e.g. Qwen). */
    onShowTerminal: () -> Unit = {},
    /** Open the Distros tab; empty-state "Browse Distros" button. */
    onBrowseDistros: () -> Unit = {},
    /** Open a shell on the Android host itself (no container). */
    onOpenHostShell: () -> Unit = {}
) {
    @Suppress("UNUSED_PARAMETER")
    val _permissionState = permissionState
    @Suppress("UNUSED_PARAMETER")
    val _hazeState = hazeState
    @Suppress("UNUSED_PARAMETER")
    val _onStartService = onStartService
    @Suppress("UNUSED_PARAMETER")
    val _onStartActivity = onStartActivity
    @Suppress("UNUSED_PARAMETER")
    val _onNavigateToInstall = onNavigateToInstall

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // State for Launch Popup
    val distroToLaunch = remember { mutableStateOf<com.altaris.linux.core.data.Distro?>(null) }
    // State for KDE GPU mode picker (sub-dialog)
    val showKdeGpuPicker = remember { mutableStateOf<com.altaris.linux.core.data.Distro?>(null) }
    // Live desktop start/stop logs
    val desktopUi by DesktopLauncher.uiState.collectAsState()
    var showDesktopLogs by remember { mutableStateOf(false) }
    // Only auto-open on a *new* tick (start/fail). Do NOT open when Home is
    // re-entered while tick is already > 0 from a prior start.
    var lastSeenAutoShowTick by remember { mutableIntStateOf(-1) }
    LaunchedEffect(desktopUi.autoShowLogsTick) {
        val tick = desktopUi.autoShowLogsTick
        if (lastSeenAutoShowTick < 0) {
            lastSeenAutoShowTick = tick
            return@LaunchedEffect
        }
        if (tick > lastSeenAutoShowTick) {
            lastSeenAutoShowTick = tick
            showDesktopLogs = true
        }
    }

    // Refresh key to trigger recomposition
    val refreshKey = remember { mutableStateOf(0) }

    // React to external refresh trigger (from MainActivity)
    LaunchedEffect(scriptRefreshTrigger) {
        if (scriptRefreshTrigger > 0) {
            refreshKey.value++
        }
    }
    // Recompose cards when desktop phase changes
    LaunchedEffect(desktopUi.phase, desktopUi.displayReady) {
        refreshKey.value++
    }

    val stateRefresh by StateManager.refreshTrigger.collectAsState()
    val session = remember(desktopUi, stateRefresh, refreshKey.value) {
        DesktopSessionQuery.current(context, desktopUi)
    }

    // Trigger initial refresh on mount
    LaunchedEffect(Unit) {
        refreshKey.value++
    }

    // Installed Distros Detection — filesystem truth: a stale
    // "installed" pref without a rootfs on disk must show Install, not a broken shell.
    // The probe is shared with the Distros tab and painted from the last
    // snapshot first, so Home never flashes "No environments yet" while it
    // re-checks (which took seconds on a chroot install).
    val fsSnapshot by androidx.compose.runtime.produceState(
        initialValue = com.altaris.linux.core.terminal.DistroFsCache.peek(),
        refreshKey.value,
    ) {
        if (value == null ||
            value?.generation != com.altaris.linux.core.terminal.DistroFsCache.currentGeneration
        ) {
            value = withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.altaris.linux.core.terminal.DistroFsCache.get(context)
            }
        }
    }
    val installedDistros = remember(fsSnapshot) {
        val ids = fsSnapshot?.installed.orEmpty()
        DistroRepository.sortForDistroPage(
            DistroRepository.supportedDistros.filter { it.id in ids }
        )
    }

    // PRoot guests lead the bento; the method itself is shown on every tile.
    val prootInstalled = installedDistros.filter { !it.isChrootCard() }
    var rootAvailable by remember { mutableStateOf(false) }
    val shizukuRunning by com.altaris.linux.core.clone.HeadlessCloneEngine
        .shizukuRunning.collectAsState()

    fun probeRoot(force: Boolean = false) {
        RootShell.probeRootAvailable(forceClearCache = force) { ok ->
            rootAvailable = ok
        }
    }
    LaunchedEffect(Unit) {
        probeRoot()
        com.altaris.linux.core.clone.HeadlessCloneEngine.refreshState()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                probeRoot(force = true)
                // Drop a "desktop running" card whose X server died with the app.
                coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    DesktopSessionQuery.reconcileStale(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun copyDesktopLog() {
        val text = if (desktopUi.logText.isNotBlank()) {
            desktopUi.logText
        } else {
            DesktopLauncher.readLog(context)
        }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("desktop_log", text))
        android.widget.Toast.makeText(context, "Log copied", android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * Direct desktop launch from an environment-row chip. Same flow as the launch
     * dialog: XFCE starts via DesktopLauncher; KDE opens the GPU picker first.
     * Single-session guard: an active desktop is never duplicated.
     */
    fun launchDesktopFromRow(distro: Distro, isChrootRow: Boolean, kde: Boolean) {
        if (isChrootRow && !rootAvailable) {
            android.widget.Toast.makeText(
                context,
                "Grant superuser to Altaris first",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (session != null && session.distroId != distro.id) {
            android.widget.Toast.makeText(
                context,
                "Stop ${session.distroName} ${session.type} first",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        // Active session for this distro: the top active-desktop card owns Open/Stop.
        if (session != null) return
        if (DesktopLauncher.isSessionActive()) {
            android.widget.Toast.makeText(
                context,
                "Stop ${session?.distroName ?: "active desktop"} first",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (kde) {
            showKdeGpuPicker.value = distro
        } else {
            try {
                DesktopLauncher.start(context, distro.id) { ok ->
                    refreshKey.value++
                    if (!ok) showDesktopLogs = true
                }
                showDesktopLogs = true
            } catch (e: Exception) {
                android.widget.Toast.makeText(
                    context,
                    "Launch failed: ${e.message}",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /** Shell action of a container tile: chroot guests need root first. */
    fun openShell(distro: Distro) {
        if (distro.isChrootCard() && !rootAvailable) {
            android.widget.Toast.makeText(
                context,
                "Grant superuser to Altaris first",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        } else {
            onOpenTerminal(distro.id, false)
        }
    }

    /** Tile tap: opens the launch-mode sheet (shell / root / desktop). */
    fun openLaunchSheet(distro: Distro) {
        if (distro.isChrootCard() && !rootAvailable) {
            android.widget.Toast.makeText(
                context,
                "Grant superuser to Altaris first",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        } else if (session != null && session.distroId != distro.id) {
            android.widget.Toast.makeText(
                context,
                "Stop ${session.distroName} ${session.type} first",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        } else {
            distroToLaunch.value = distro
        }
    }

    // The section accent colours every surface of this screen.
    val accent = sectionAccent(AltarisSection.HOME)
    // The banner artwork, changeable in Settings.
    val homeArt by StateManager.homeArt.collectAsState()
    LaunchedEffect(Unit) { StateManager.getHomeArt(context) }

    // Bento order: the container that owns the running desktop leads, then the
    // first PRoot guest, then the rest.
    val heroDistro = session?.distroId?.let { id -> installedDistros.firstOrNull { it.id == id } }
        ?: prootInstalled.firstOrNull()
        ?: installedDistros.firstOrNull()
    val bentoDistros = installedDistros.filter { it.id != heroDistro?.id }

    // Shared entrance clock: sections cascade in on first composition only;
    // rows restored/added later (session start, install finish) appear at rest.
    val entrance = rememberStaggerEntrance()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Prism.Gutter,
            end = Prism.Gutter,
            top = 10.dp,
            bottom = 110.dp
        ),
        verticalArrangement = Arrangement.spacedBy(Prism.Stack)
    ) {
        item(key = "title") {
            // The scene: the anime artwork that carries the identity.
            HomeHero(
                artRes = homeArt,
                title = "ALTARIS",
                subtitle = "LINUX ON DEVICE",
                modifier = Modifier
                    .fillMaxWidth()
                    .staggerIn(0, entrance),
            )
        }

        // ── Active desktop card (single session, top of the list) ──
        if (session != null) {
            item(key = "active-desktop") {
                ActiveSessionCard(
                    session = session,
                    accent = accent,
                    onOpen = { DesktopLauncher.reopenDisplay(context) },
                    onStop = {
                        try {
                            DesktopLauncher.stop(context, session.distroId) {
                                refreshKey.value++
                            }
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(context, "Stop failed: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    onLogs = { copyDesktopLog() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .staggerIn(2, entrance)
                )
            }
        }

        // ── Status strip: what the device is doing right now ──
        item(key = "status") {
            PrismCard(
                accent = accent,
                modifier = Modifier
                    .fillMaxWidth()
                    .staggerIn(1, entrance)
            ) {
                PrismStatRow(
                    stats = listOf(
                        installedDistros.size.toString() to "Containers",
                        (if (session != null) 1 else 0).toString() to "Running",
                        (if (rootAvailable) "Yes" else "No") to "Root",
                        (if (shizukuRunning) "Yes" else "No") to "Shizuku"
                    )
                )
            }
        }

        // ── Containers ──
        item(key = "containers-label") {
            PrismSectionLabel(
                "Containers",
                accent,
                Modifier
                    .fillMaxWidth()
                    .staggerIn(2, entrance)
            )
        }

        if (fsSnapshot == null) {
            item(key = "env-checking") {
                PrismCard(
                    accent = accent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .staggerIn(3, entrance)
                ) {
                    Text(
                        "Checking environments…",
                        fontSize = 12.sp,
                        color = altarisMutedText(),
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
            }
        } else if (installedDistros.isEmpty()) {
            item(key = "env-empty") {
                PrismCard(
                    accent = accent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .staggerIn(3, entrance)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "No environments yet",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Install a distro to open shells and desktops.",
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                            color = altarisMutedText()
                        )
                        Spacer(Modifier.height(12.dp))
                        PrismButton(
                            text = "Browse Distros",
                            accent = accent,
                            icon = Icons.Filled.Layers,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onBrowseDistros() }
                        )
                    }
                }
            }
        } else {
            item(key = "containers-card") {
                PrismCard(
                    accent = accent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .staggerIn(3, entrance)
                ) {
                    Column {
                        installedDistros.forEachIndexed { index, distro ->
                            val isChroot = distro.isChrootCard()
                            val (suffix, running) = envStatus(context, distro, isChroot, rootAvailable, desktopUi)
                            val (showXfce, showKde) = envDesktopFlags(context, distro, session)
                            ContainerRow(
                                distro = distro,
                                running = running,
                                statusText = (if (isChroot) "Chroot" else "PRoot") + suffix,
                                showXfce = showXfce,
                                showKde = showKde,
                                onOpen = { openLaunchSheet(distro) },
                                onShell = { openShell(distro) },
                                onLaunchXfce = {
                                    launchDesktopFromRow(distro, isChrootRow = isChroot, kde = false)
                                },
                                onLaunchKde = {
                                    launchDesktopFromRow(distro, isChrootRow = isChroot, kde = true)
                                },
                                onSettings = { onNavigateToSettings(distro) },
                                divider = index < installedDistros.lastIndex
                            )
                        }
                    }
                }
            }
        }

        // ── Host shell: a shell on the Android host itself ──
        item(key = "host-shell") {
            PrismCard(
                accent = accent,
                modifier = Modifier
                    .fillMaxWidth()
                    .staggerIn(4, entrance)
            ) {
                PrismRow(
                    title = "Host shell",
                    subtitle = "A shell on the Android host — no container",
                    accent = accent,
                    leading = {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(Prism.ButtonRadius))
                                .background(MaterialTheme.colorScheme.surface)
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outline,
                                    RoundedCornerShape(Prism.ButtonRadius)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Terminal,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    },
                    trailing = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp)
                        )
                    },
                    onClick = { onOpenHostShell() }
                )
            }
        }
    }

    // Launch mode dialog (shell / root / desktop)
    if (distroToLaunch.value != null) {
        val distro = distroToLaunch.value!!
        val llmInstalled = StateManager.isComponentInstalled(context, distro.id, "vulkan_llamacpp")
        val modelInstalled = StateManager.isComponentInstalled(context, distro.id, "qwen25_model")
        val xfceInstalled = StateManager.isComponentInstalled(context, distro.id, "xfce4_desktop")
        val kdeInstalled = StateManager.isComponentInstalled(context, distro.id, "kde_plasma")
        val isGuiRunning = StateManager.isGuiRunning(context, distro.id)
        val runningType = StateManager.getGuiRunningType(context, distro.id)

        androidx.compose.ui.window.Dialog(
            onDismissRequest = { distroToLaunch.value = null },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false
            )
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.92f),
                shape = RoundedCornerShape(Prism.SheetRadius),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                border = BorderStroke(
                    0.75.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                )
            ) {
                Column(modifier = Modifier.padding(vertical = 10.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Prism.Gutter),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DistroTileIcon(distro, accent, 38.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Start ${distro.name}",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text("Choose launch mode", fontSize = 11.sp, color = altarisMutedText())
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Shell — in-app terminal (no external Termux)
                    LaunchModeAction(
                        icon = Icons.Filled.Terminal,
                        label = "Open Shell",
                        subtitle = "User session",
                        accent = accent,
                        onClick = {
                            onOpenTerminal(distro.id, false)
                            distroToLaunch.value = null
                        }
                    )

                    QDivider(Modifier.padding(horizontal = Prism.Gutter))

                    LaunchModeAction(
                        icon = Icons.Filled.AdminPanelSettings,
                        label = "Open Root Shell",
                        subtitle = "Privileged session",
                        accent = accent,
                        onClick = {
                            onOpenTerminal(distro.id, true)
                            distroToLaunch.value = null
                        }
                    )

                    if (llmInstalled && modelInstalled) {
                        QDivider(Modifier.padding(horizontal = Prism.Gutter))
                        LaunchModeAction(
                            icon = Icons.Filled.Psychology,
                            label = "Run Qwen2.5-1.5B",
                            subtitle = "In-app model session",
                            accent = accent,
                            onClick = {
                                val scriptManager = ScriptManager(context)
                                val scriptContent = scriptManager.getScriptContent(
                                    "debian/common/addon/launch_qwen25.sh"
                                )
                                val scriptB64 = android.util.Base64.encodeToString(
                                    scriptContent.toByteArray(),
                                    android.util.Base64.NO_WRAP
                                )
                                val payload = "echo '$scriptB64' | base64 -d | bash"
                                com.altaris.linux.core.terminal.AltarisTerminalSessionManager.openSessionAfterHost(
                                    context,
                                    type = "shell",
                                    title = "Qwen2.5-1.5B",
                                    shellCmd = payload,
                                    method = com.altaris.linux.core.data.terminalComponentFor(distro.id).method,
                                    onResult = { result ->
                                        if (result == com.altaris.linux.core.terminal.AltarisTerminalSessionManager.SessionOpenResult.OPENED) {
                                            onShowTerminal()
                                        }
                                    }
                                )
                                distroToLaunch.value = null
                            }
                        )
                    }

                    val isCurrentSessionXfce = session != null && session.distroId == distro.id && session.type == DesktopSession.Type.XFCE4
                    // Only offer what is actually installed (or already running).
                    if (xfceInstalled || isCurrentSessionXfce) {
                        QDivider(Modifier.padding(horizontal = Prism.Gutter))
                        LaunchModeAction(
                            icon = Icons.Filled.DesktopWindows,
                            label = if (isCurrentSessionXfce) "Open XFCE4" else "Launch XFCE4",
                            subtitle = if (isCurrentSessionXfce) "Running on :0" else "Graphical desktop",
                            accent = accent,
                            onClick = {
                                if (session != null && session.distroId != distro.id) {
                                    android.widget.Toast.makeText(
                                        context,
                                        "Stop ${session.distroName} ${session.type} first",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                } else if (isCurrentSessionXfce) {
                                    DesktopLauncher.reopenDisplay(context)
                                    distroToLaunch.value = null
                                } else {
                                    try {
                                        DesktopLauncher.start(context, distro.id) { ok ->
                                            refreshKey.value++
                                            if (!ok) showDesktopLogs = true
                                        }
                                        showDesktopLogs = true
                                        distroToLaunch.value = null
                                    } catch (e: Exception) {
                                        android.widget.Toast.makeText(
                                            context,
                                            "Launch failed: ${e.message}",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        )
                    }

                    val isCurrentSessionKde = session != null && session.distroId == distro.id && session.type == DesktopSession.Type.KDE
                    if (kdeInstalled || isCurrentSessionKde) {
                        QDivider(Modifier.padding(horizontal = Prism.Gutter))
                        LaunchModeAction(
                            icon = Icons.Filled.Widgets,
                            label = if (isCurrentSessionKde) "Open KDE Plasma" else "Launch KDE Plasma",
                            subtitle = if (isCurrentSessionKde) "Running on :0" else "GPU mode picker",
                            accent = accent,
                            onClick = {
                                if (session != null && session.distroId != distro.id) {
                                    android.widget.Toast.makeText(
                                        context,
                                        "Stop ${session.distroName} ${session.type} first",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                } else if (isCurrentSessionKde) {
                                    DesktopLauncher.reopenDisplay(context)
                                    distroToLaunch.value = null
                                } else if (session != null || DesktopLauncher.isSessionActive()) {
                                    android.widget.Toast.makeText(
                                        context,
                                        "Stop ${session?.distroName ?: "active desktop"} ${session?.type ?: ""} first",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    showKdeGpuPicker.value = distro
                                }
                            }
                        )
                    }

                    if (isGuiRunning) {
                        QDivider(Modifier.padding(horizontal = Prism.Gutter))
                        LaunchModeAction(
                            icon = Icons.Filled.Stop,
                            label = if (runningType == "kde") "Stop KDE Plasma" else "Stop XFCE4",
                            subtitle = "End desktop session",
                            accent = accent,
                            danger = true,
                            onClick = {
                                DesktopLauncher.stop(context, distro.id)
                                distroToLaunch.value = null
                            }
                        )
                    }

                    Spacer(Modifier.height(6.dp))

                    TextButton(
                        onClick = { distroToLaunch.value = null },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Cancel", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    // ─── Graphical Desktop live logs ───
    if (showDesktopLogs) {
        DesktopLogsDialog(
            accent = accent,
            logText = if (desktopUi.logText.isNotBlank()) {
                desktopUi.logText
            } else {
                DesktopLauncher.readLog(context)
            },
            phase = desktopUi.phase,
            onDismiss = { showDesktopLogs = false },
            onOpenX11 = {
                DesktopLauncher.reopenDisplay(context)
            },
            onCopy = { copyDesktopLog() }
        )
    }

    // ─── KDE GPU Mode Picker Sub-Dialog ───
    if (showKdeGpuPicker.value != null) {
        val distro = showKdeGpuPicker.value!!
        val launchKde = { gpu: String? ->
            val curr = DesktopSessionQuery.current(context, DesktopLauncher.uiState.value)
            if (curr != null || DesktopLauncher.isSessionActive()) {
                if (curr?.distroId == distro.id && curr.type == DesktopSession.Type.KDE) {
                    DesktopLauncher.reopenDisplay(context)
                } else {
                    android.widget.Toast.makeText(
                        context,
                        "Stop ${curr?.distroName ?: "active desktop"} first",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                DesktopLauncher.start(context, distro.id, "kde", gpu) { ok ->
                    refreshKey.value++
                    if (!ok) showDesktopLogs = true
                }
            }
            showKdeGpuPicker.value = null
            distroToLaunch.value = null
        }
        KdeGpuPickerDialog(
            accent = accent,
            distro = distro,
            onDismiss = { showKdeGpuPicker.value = null },
            onSelectHardware = { launchKde(null) },
            onSelectSoftware = { launchKde("software") }
        )
    }
}

// ─── Quick actions ───────────────────────────────────────────────────────────
// Three squircle tiles: the terminal tab, a shell in the primary container and
// the distro browser. They call the callbacks the old rows called.
// ─── Active desktop session ──────────────────────────────────────────────────
// The running desktop owns Open/Stop; the bento below never duplicates it.
@Composable
private fun ActiveSessionCard(
    session: DesktopSession,
    accent: Color,
    onOpen: () -> Unit,
    onStop: () -> Unit,
    onLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isRunning = session.phase == DesktopSession.Phase.Running
    val deName = if (session.type == DesktopSession.Type.KDE) "KDE Plasma" else "XFCE4"
    val titleText = if (isRunning) {
        "${session.distroName} desktop is running"
    } else {
        "Starting ${session.distroName} desktop…"
    }
    val subtitleText = "$deName on :0 · ${if (isRunning) "running" else "starting"}"

    PrismCard(accent = accent, modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AccentIconBox(Icons.Filled.DesktopWindows, accent, 42.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    titleText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    subtitleText,
                    fontSize = 12.sp,
                    color = altarisMutedText(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            PrismOrb(active = isRunning, accent = accent)
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(Prism.Tight)) {
            PrismButton(
                text = "Open X11",
                accent = accent,
                icon = Icons.Filled.PlayArrow,
                modifier = Modifier.weight(1f),
                onClick = onOpen
            )
            PrismIconTile(
                icon = Icons.Filled.ContentCopy,
                accent = accent,
                contentDescription = "Copy log",
                size = 52.dp,
                onClick = onLogs
            )
            PrismIconTile(
                icon = Icons.Filled.Stop,
                accent = accent,
                contentDescription = "Stop desktop",
                size = 52.dp,
                onClick = onStop
            )
        }
    }
}

// ─── Container hero tile ─────────────────────────────────────────────────────
// The container the user most likely wants: big name, method, and every
// action the old environment row offered.
/**
 * One container in the list: the logo, the name, the method and the actions.
 */
@Composable
private fun ContainerRow(
    distro: Distro,
    running: Boolean,
    statusText: String,
    showXfce: Boolean,
    showKde: Boolean,
    onOpen: () -> Unit,
    onShell: () -> Unit,
    onLaunchXfce: () -> Unit,
    onLaunchKde: () -> Unit,
    onSettings: () -> Unit,
    divider: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val logoShape = RoundedCornerShape(Prism.ButtonRadius)
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Prism.ButtonRadius))
                .clickable(onClick = onOpen)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(logoShape)
                    .background(scheme.surface)
                    .border(1.dp, scheme.outline, logoShape),
                contentAlignment = Alignment.Center
            ) {
                if (distro.iconRes != null) {
                    Image(
                        painter = painterResource(id = distro.iconRes),
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Terminal,
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    distro.name.removeSuffix(" (Rooted)"),
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    statusText,
                    fontSize = 11.sp,
                    color = altarisMutedText(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (running) {
                PrismOrb(active = true, accent = Color.Transparent)
                Spacer(Modifier.width(8.dp))
            }
            RowAction(
                icon = Icons.Filled.Terminal,
                contentDescription = "Open shell",
                onClick = onShell
            )
            if (showXfce) {
                RowAction(
                    icon = Icons.Filled.DesktopWindows,
                    contentDescription = "Launch XFCE4",
                    onClick = onLaunchXfce
                )
            }
            if (showKde) {
                RowAction(
                    icon = Icons.Filled.Widgets,
                    contentDescription = "Launch KDE Plasma",
                    onClick = onLaunchKde
                )
            }
            RowAction(
                icon = Icons.Filled.Settings,
                contentDescription = "Distro settings",
                onClick = onSettings
            )
        }
        if (divider) {
            PrismDivider(Modifier.padding(start = 44.dp))
        }
    }
}

/** A small bordered action inside a row. */
@Composable
private fun RowAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(Prism.ButtonRadius)
    Box(
        modifier = Modifier
            .padding(start = 6.dp)
            .size(28.dp)
            .clip(shape)
            .background(if (pressed) scheme.onSurface.copy(alpha = 0.08f) else Color.Transparent)
            .border(1.dp, scheme.outline, shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp)
        )
    }
}

// ─── Shared bits ─────────────────────────────────────────────────────────────

/** Status suffix of a container tile: needs root, starting or running. */
private fun envStatus(
    context: Context,
    distro: Distro,
    isChroot: Boolean,
    rootAvailable: Boolean,
    desktopUi: DesktopLauncher.UiState
): Pair<String, Boolean> {
    val desktopForThis = desktopUi.distroId == null || desktopUi.distroId == distro.id
    val isStarting = desktopForThis && desktopUi.phase == DesktopLauncher.Phase.Starting
    val isRunning = StateManager.isGuiRunning(context, distro.id) ||
        (desktopForThis && desktopUi.phase == DesktopLauncher.Phase.Running)
    val suffix = when {
        isChroot && !rootAvailable -> " · needs root"
        isStarting -> " · starting"
        isRunning -> " · running"
        else -> ""
    }
    return suffix to isRunning
}

/**
 * Desktop launch tiles: only when the component is installed for this distro
 * and the distro does not already own the active session (the active card owns
 * open/stop then — no duplicate launch buttons).
 */
private fun envDesktopFlags(
    context: Context,
    distro: Distro,
    session: DesktopSession?
): Pair<Boolean, Boolean> {
    val desktopActiveForThis = session != null && session.distroId == distro.id
    val xfce = !desktopActiveForThis &&
        StateManager.isComponentInstalled(context, distro.id, "xfce4_desktop")
    val kde = !desktopActiveForThis &&
        StateManager.isComponentInstalled(context, distro.id, "kde_plasma")
    return xfce to kde
}

/** A squared icon plate with a hairline — no tint, no glow. */
@Composable
private fun AccentIconBox(
    icon: ImageVector,
    accent: Color,
    size: Dp
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(Prism.ButtonRadius))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outline,
                RoundedCornerShape(Prism.ButtonRadius)
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(size * 0.46f)
        )
    }
}

/** The distro logo (or a shell glyph) in a hairline plate. */
@Composable
private fun DistroTileIcon(
    distro: Distro,
    accent: Color,
    size: Dp
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(Prism.ButtonRadius))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outline,
                RoundedCornerShape(Prism.ButtonRadius)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (distro.iconRes != null) {
            Image(
                painter = painterResource(id = distro.iconRes),
                contentDescription = null,
                modifier = Modifier.size(size * 0.62f)
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Terminal,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(size * 0.44f)
            )
        }
    }
}

// ─── Desktop start/stop log dialog (compact) ─────────────────────────────────
@Composable
private fun DesktopLogsDialog(
    accent: Color,
    logText: String,
    phase: DesktopLauncher.Phase,
    onDismiss: () -> Unit,
    onOpenX11: () -> Unit,
    onCopy: () -> Unit
) {
    val scroll = rememberScrollState()
    LaunchedEffect(logText) {
        scroll.animateScrollTo(scroll.maxValue)
    }

    val phaseLabel = when (phase) {
        DesktopLauncher.Phase.Starting -> "Starting…"
        DesktopLauncher.Phase.Running -> "Running"
        DesktopLauncher.Phase.Idle -> "Idle"
    }
    val phaseIcon = when (phase) {
        DesktopLauncher.Phase.Starting -> Icons.Filled.Sync
        DesktopLauncher.Phase.Running -> Icons.Filled.PlayArrow
        DesktopLauncher.Phase.Idle -> Icons.Filled.Schedule
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.75f),
            shape = RoundedCornerShape(Prism.SheetRadius),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            border = BorderStroke(
                0.75.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
            )
        ) {
            Column(Modifier.padding(Prism.Gutter)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Desktop log",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = phaseIcon,
                                contentDescription = null,
                                tint = altarisMutedText(),
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(phaseLabel, fontSize = 11.sp, color = altarisMutedText())
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close",
                            tint = altarisMutedText(),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                        .padding(10.dp)
                ) {
                    Text(
                        text = logText.ifBlank { "Waiting for desktop output…" },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scroll)
                    )
                }
                Spacer(Modifier.height(10.dp))
                PrismButton(
                    text = "Open X11",
                    accent = accent,
                    icon = Icons.Filled.PlayArrow,
                    enabled = phase != DesktopLauncher.Phase.Idle,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onOpenX11
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = onCopy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Copy Log", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ─── Launch mode action row (compact) ───────────────────────────────────────
@Composable
private fun LaunchModeAction(
    icon: ImageVector,
    label: String,
    subtitle: String,
    accent: Color,
    onClick: () -> Unit,
    danger: Boolean = false
) {
    val tint = if (danger) MaterialTheme.colorScheme.error else accent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Prism.Gutter, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(Prism.TileRadius))
                .background(tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(17.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Medium,
                color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                fontSize = 12.sp,
                color = altarisMutedText(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ─── KDE GPU Picker Dialog (compact) ─────────────────────────────────────────
@Composable
private fun KdeGpuPickerDialog(
    accent: Color,
    distro: com.altaris.linux.core.data.Distro,
    onDismiss: () -> Unit,
    onSelectHardware: () -> Unit,
    onSelectSoftware: () -> Unit
) {
    val ctx = LocalContext.current
    val driverBadge = remember(distro.id) {
        com.altaris.linux.core.gpu.GpuDriverInstaller.badge(ctx, distro.id)
    }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            shape = RoundedCornerShape(Prism.SheetRadius),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            border = BorderStroke(
                0.75.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
            )
        ) {
            Column(modifier = Modifier.padding(vertical = 12.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Prism.Gutter),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AccentIconBox(Icons.Filled.Widgets, accent, 38.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "KDE Plasma",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text("Choose GPU renderer", fontSize = 11.sp, color = altarisMutedText())
                    }
                }

                Spacer(Modifier.height(10.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Prism.Gutter),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GpuOptionCard(
                        accent = accent,
                        modifier = Modifier.fillMaxWidth(),
                        title = "Hardware",
                        subtitle = "Installed GPU driver",
                        description = "Turnip / PanVK / VirGL, whichever is installed. Best performance.",
                        badgeText = driverBadge,
                        onClick = onSelectHardware
                    )
                    GpuOptionCard(
                        accent = accent,
                        modifier = Modifier.fillMaxWidth(),
                        title = "Software",
                        subtitle = "LLVMpipe",
                        description = "CPU-only. No GPU required. Works on all devices.",
                        badgeText = "Safe Fallback",
                        onClick = onSelectSoftware
                    )
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Prism.Gutter)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                        tint = altarisMutedText(),
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Hardware needs a GPU driver installed. Software works on any device (slower).",
                        fontSize = 11.sp,
                        color = altarisMutedText(),
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(6.dp))

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Cancel", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ─── GPU option row inside the KDE picker dialog ─────────────────────────────
@Composable
private fun GpuOptionCard(
    accent: Color,
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    description: String,
    badgeText: String?,
    onClick: () -> Unit
) {
    PrismCard(accent = accent, modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AccentIconBox(Icons.Filled.DesktopWindows, accent, 34.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (badgeText != null) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            badgeText,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = altarisMutedText(),
                            maxLines = 1,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = altarisMutedText(),
                    maxLines = 1
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    description,
                    fontSize = 11.sp,
                    color = altarisMutedText(),
                    lineHeight = 14.sp
                )
            }
        }
    }
}
