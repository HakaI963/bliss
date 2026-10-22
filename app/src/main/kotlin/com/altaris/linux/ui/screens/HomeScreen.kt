package com.altaris.linux.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.altaris.linux.ui.components.ActiveDesktopCard
import com.altaris.linux.ui.components.QCard
import com.altaris.linux.ui.components.QDivider
import com.altaris.linux.ui.components.QEmptyState
import com.altaris.linux.ui.components.QIconTile
import com.altaris.linux.ui.components.QPrimaryButton
import com.altaris.linux.ui.components.QRow
import com.altaris.linux.ui.components.QScreenTitle
import com.altaris.linux.ui.components.QSectionHeader
import com.altaris.linux.ui.components.Quartz
import com.altaris.linux.ui.components.isChrootCard
import com.altaris.linux.ui.theme.altarisMutedText
import com.altaris.linux.ui.theme.rememberStaggerEntrance
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
    onBrowseDistros: () -> Unit = {}
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
    val installedDistros = remember(refreshKey.value) {
        DistroRepository.sortForDistroPage(
            DistroRepository.supportedDistros.filter {
                TerminalLauncher.isDistroInstalledOnFs(context, it.id)
            }
        )
    }

    // PRoot / Chroot split (backend logic preserved; UI groups below).
    val prootInstalled = installedDistros.filter { !it.isChrootCard() }
    val chrootInstalled = installedDistros.filter { it.isChrootCard() }
    var rootAvailable by remember { mutableStateOf(false) }

    fun probeRoot(force: Boolean = false) {
        RootShell.probeRootAvailable(forceClearCache = force) { ok ->
            rootAvailable = ok
        }
    }
    LaunchedEffect(Unit) { probeRoot() }
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

    // Shared entrance clock: sections cascade in on first composition only;
    // rows restored/added later (session start, install finish) appear at rest.
    val entrance = rememberStaggerEntrance()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Quartz.ScreenPad,
            end = Quartz.ScreenPad,
            top = 10.dp,
            bottom = 110.dp
        ),
        verticalArrangement = Arrangement.spacedBy(Quartz.Gap)
    ) {
        item(key = "title") {
            QScreenTitle(
                "Home",
                "Your Linux environments.",
                Modifier
                    .fillMaxWidth()
                    .staggerIn(0, entrance)
            )
        }

        // ── Active desktop card (single session, top of the list) ──
        if (session != null) {
            item(key = "active-desktop") {
                ActiveDesktopCard(
                    session = session,
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
                        .staggerIn(1, entrance)
                )
            }
        }

        if (installedDistros.isEmpty()) {
            item(key = "env-empty") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .staggerIn(2, entrance)
                ) {
                    QSectionHeader("Environments")
                    Spacer(Modifier.height(6.dp))
                    QCard {
                        QEmptyState(
                            "No environments installed",
                            "Install a distro to open shells and desktops."
                        )
                        Column(
                            modifier = Modifier.padding(horizontal = Quartz.CardPad, vertical = 4.dp)
                        ) {
                            QPrimaryButton("Browse Distros", onClick = onBrowseDistros)
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        } else {
            if (prootInstalled.isNotEmpty()) {
                item(key = "proot-title") {
                    QSectionHeader(
                        "PRoot · ${prootInstalled.size}",
                        Modifier
                            .fillMaxWidth()
                            .staggerIn(2, entrance)
                    )
                }
                item(key = "proot-group") {
                    QCard(Modifier.staggerIn(3, entrance)) {
                        prootInstalled.forEachIndexed { index, distro ->
                            InstalledEnvRow(
                                distro = distro,
                                isChroot = false,
                                rootAvailable = rootAvailable,
                                session = session,
                                desktopUi = desktopUi,
                                onShell = {
                                    val needsRoot = distro.isChrootCard()
                                    if (needsRoot && !rootAvailable) {
                                        android.widget.Toast.makeText(
                                            context,
                                            "Grant superuser to Altaris first",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        onOpenTerminal(distro.id, false)
                                    }
                                },
                                onOpenLaunch = {
                                    if (session != null && session.distroId != distro.id) {
                                        android.widget.Toast.makeText(
                                            context,
                                            "Stop ${session.distroName} ${session.type} first",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        distroToLaunch.value = distro
                                    }
                                },
                                onLaunchXfce = { launchDesktopFromRow(distro, isChrootRow = false, kde = false) },
                                onLaunchKde = { launchDesktopFromRow(distro, isChrootRow = false, kde = true) },
                                onSettings = { onNavigateToSettings(distro) },
                                divider = index < prootInstalled.lastIndex
                            )
                        }
                    }
                }
            }
            if (chrootInstalled.isNotEmpty()) {
                item(key = "chroot-title") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .staggerIn(4, entrance)
                    ) {
                        QSectionHeader("Chroot · ${chrootInstalled.size}")
                        if (!rootAvailable) {
                            Text(
                                "Installed, but no root given to the app. Grant superuser to start these guests.",
                                color = altarisMutedText(),
                                fontSize = 11.sp,
                                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                            )
                        }
                    }
                }
                item(key = "chroot-group") {
                    QCard(Modifier.staggerIn(5, entrance)) {
                        chrootInstalled.forEachIndexed { index, distro ->
                            InstalledEnvRow(
                                distro = distro,
                                isChroot = true,
                                rootAvailable = rootAvailable,
                                session = session,
                                desktopUi = desktopUi,
                                onShell = {
                                    if (!rootAvailable) {
                                        android.widget.Toast.makeText(
                                            context,
                                            "Grant superuser to Altaris first",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        onOpenTerminal(distro.id, false)
                                    }
                                },
                                onOpenLaunch = {
                                    if (!rootAvailable) {
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
                                },
                                onLaunchXfce = { launchDesktopFromRow(distro, isChrootRow = true, kde = false) },
                                onLaunchKde = { launchDesktopFromRow(distro, isChrootRow = true, kde = true) },
                                onSettings = { onNavigateToSettings(distro) },
                                divider = index < chrootInstalled.lastIndex
                            )
                        }
                    }
                }
            }
            // Per-row distro ids (logs availability still honours the host log file).
            item(key = "env-footnote") {
                val anyLogs = com.altaris.linux.core.desktop.GuiDesktopLog.hasContent(context)
                if (anyLogs && session == null) {
                    TextButton(
                        onClick = { showDesktopLogs = true },
                        modifier = Modifier.staggerIn(6, entrance),
                        colors = ButtonDefaults.textButtonColors(contentColor = altarisMutedText())
                    ) {
                        Text("View last desktop log", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
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
                shape = Quartz.CardShape,
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
                            .padding(horizontal = Quartz.CardPad + 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (distro.iconRes != null) {
                            Image(
                                painter = painterResource(id = distro.iconRes),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(Quartz.TileShape)
                            )
                        } else {
                            QIconTile(bitmap = null, fallback = Icons.Filled.PlayArrow, size = 34.dp)
                        }
                        Spacer(Modifier.width(10.dp))
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
                        onClick = {
                            onOpenTerminal(distro.id, false)
                            distroToLaunch.value = null
                        }
                    )

                    QDivider(Modifier.padding(horizontal = Quartz.CardPad + 2.dp))

                    LaunchModeAction(
                        icon = Icons.Filled.AdminPanelSettings,
                        label = "Open Root Shell",
                        subtitle = "Privileged session",
                        onClick = {
                            onOpenTerminal(distro.id, true)
                            distroToLaunch.value = null
                        }
                    )

                    if (llmInstalled && modelInstalled) {
                        QDivider(Modifier.padding(horizontal = Quartz.CardPad + 2.dp))
                        LaunchModeAction(
                            icon = Icons.Filled.Psychology,
                            label = "Run Qwen2.5-1.5B",
                            subtitle = "In-app model session",
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
                        QDivider(Modifier.padding(horizontal = Quartz.CardPad + 2.dp))
                        LaunchModeAction(
                            icon = Icons.Filled.DesktopWindows,
                            label = if (isCurrentSessionXfce) "Open XFCE4" else "Launch XFCE4",
                            subtitle = if (isCurrentSessionXfce) "Running on :0" else "Graphical desktop",
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
                        QDivider(Modifier.padding(horizontal = Quartz.CardPad + 2.dp))
                        LaunchModeAction(
                            icon = Icons.Filled.Widgets,
                            label = if (isCurrentSessionKde) "Open KDE Plasma" else "Launch KDE Plasma",
                            subtitle = if (isCurrentSessionKde) "Running on :0" else "GPU mode picker",
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
                        QDivider(Modifier.padding(horizontal = Quartz.CardPad + 2.dp))
                        LaunchModeAction(
                            icon = Icons.Filled.Stop,
                            label = if (runningType == "kde") "Stop KDE Plasma" else "Stop XFCE4",
                            subtitle = "End desktop session",
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
            distro = distro,
            onDismiss = { showKdeGpuPicker.value = null },
            onSelectHardware = { launchKde(null) },
            onSelectSoftware = { launchKde("software") }
        )
    }
}

// ─── Altaris installed environment row ─────────────────────────────────────────
// Quartz row: 32 dp tile + name + env-type muted + Shell/XFCE/KDE chips + gear.
// Row tap opens the launch-mode dialog (shell / root / desktop); Shell chip is a
// quick Shell open; XFCE/KDE chips launch the desktop directly (KDE via the GPU
// picker) and hide while this distro owns the active session, so the top
// active-desktop card stays the single source of Open/Stop.
@Composable
private fun InstalledEnvRow(
    distro: Distro,
    isChroot: Boolean,
    rootAvailable: Boolean,
    session: DesktopSession?,
    desktopUi: DesktopLauncher.UiState,
    onShell: () -> Unit,
    onOpenLaunch: () -> Unit,
    onLaunchXfce: () -> Unit,
    onLaunchKde: () -> Unit,
    onSettings: () -> Unit,
    divider: Boolean = false
) {
    val context = LocalContext.current
    val desktopForThis = desktopUi.distroId == null || desktopUi.distroId == distro.id
    val isStarting = desktopForThis && desktopUi.phase == DesktopLauncher.Phase.Starting
    val isRunning = StateManager.isGuiRunning(context, distro.id) ||
        (desktopForThis && desktopUi.phase == DesktopLauncher.Phase.Running)
    val statusSuffix = when {
        isChroot && !rootAvailable -> " · needs root"
        isStarting -> " · starting"
        isRunning -> " · running"
        else -> ""
    }

    // Desktop launch chips: only when the component is installed for this distro
    // and the distro does not already own the active session (the top card owns
    // open/stop then — no duplicate launch buttons).
    val desktopActiveForThis = session != null && session.distroId == distro.id
    val showXfceLaunch = !desktopActiveForThis &&
        StateManager.isComponentInstalled(context, distro.id, "xfce4_desktop")
    val showKdeLaunch = !desktopActiveForThis &&
        StateManager.isComponentInstalled(context, distro.id, "kde_plasma")

    QRow(
        title = distro.name.removeSuffix(" (Rooted)"),
        subtitle = (if (isChroot) "Chroot" else "PRoot") + statusSuffix,
        leading = {
            if (distro.iconRes != null) {
                Image(
                    painter = painterResource(id = distro.iconRes),
                    contentDescription = null,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(Quartz.TileShape)
                )
            } else {
                QIconTile(bitmap = null, fallback = Icons.Filled.Terminal, size = 32.dp)
            }
        },
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                EnvActionChip("Shell", onClick = onShell, accent = true)
                if (showXfceLaunch) {
                    EnvActionChip("XFCE", onClick = onLaunchXfce)
                }
                if (showKdeLaunch) {
                    EnvActionChip("KDE", onClick = onLaunchKde)
                }
                IconButton(
                    onClick = onSettings,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Distro settings",
                        tint = altarisMutedText(),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        },
        onClick = onOpenLaunch,
        divider = divider
    )
}

// Compact row-action chip: 10.5 sp label, tonal fill, no glow. Shell uses the
// theme primary so the single quickest action stays the most prominent one.
@Composable
private fun EnvActionChip(
    label: String,
    onClick: () -> Unit,
    accent: Boolean = false
) {
    val bg = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (accent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        label,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.SemiBold,
        color = fg,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp)
    )
}

// ─── Desktop start/stop log dialog (compact) ─────────────────────────────────
@Composable
private fun DesktopLogsDialog(
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
            shape = Quartz.CardShape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            border = BorderStroke(
                0.75.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
            )
        ) {
            Column(Modifier.padding(Quartz.ScreenPad)) {
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
                QPrimaryButton(
                    "Open X11",
                    onClick = onOpenX11,
                    enabled = phase != DesktopLauncher.Phase.Idle
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

// ─── Launch mode action row (compact Quartz row) ─────────────────────────────
@Composable
private fun LaunchModeAction(
    icon: ImageVector,
    label: String,
    subtitle: String,
    onClick: () -> Unit,
    danger: Boolean = false
) {
    val accent = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Quartz.CardPad + 2.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(Quartz.TileShape)
                .background(
                    if (danger) MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (danger) MaterialTheme.colorScheme.error else altarisMutedText(),
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                fontSize = Quartz.RowTitleSize,
                fontWeight = FontWeight.Medium,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                fontSize = Quartz.RowSubSize,
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
            shape = Quartz.CardShape,
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
                        .padding(horizontal = Quartz.CardPad + 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    QIconTile(bitmap = null, fallback = Icons.Filled.Widgets, size = 34.dp)
                    Spacer(Modifier.width(10.dp))
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
                        .padding(horizontal = Quartz.CardPad + 2.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GpuOptionCard(
                        modifier = Modifier.fillMaxWidth(),
                        title = "Hardware",
                        subtitle = "Installed GPU driver",
                        description = "Turnip / PanVK / VirGL, whichever is installed. Best performance.",
                        badgeText = driverBadge,
                        onClick = onSelectHardware
                    )
                    GpuOptionCard(
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
                        .padding(horizontal = Quartz.CardPad + 2.dp),
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
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    description: String,
    badgeText: String?,
    onClick: () -> Unit
) {
    QCard(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = Quartz.CardPad, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(Quartz.TileShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.DesktopWindows,
                    contentDescription = null,
                    tint = altarisMutedText(),
                    modifier = Modifier.size(17.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    fontSize = Quartz.RowSubSize,
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
