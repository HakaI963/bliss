package com.ivarna.fluxlinux.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivarna.fluxlinux.core.data.DistroRepository
import com.ivarna.fluxlinux.core.data.Distro
import com.ivarna.fluxlinux.core.data.ScriptManager
import com.ivarna.fluxlinux.core.desktop.DesktopLauncher
import com.ivarna.fluxlinux.core.desktop.DesktopSession
import com.ivarna.fluxlinux.core.desktop.DesktopSessionQuery
import com.ivarna.fluxlinux.core.root.RootShell
import com.ivarna.fluxlinux.core.terminal.TerminalLauncher
import com.ivarna.fluxlinux.core.utils.StateManager
import com.ivarna.fluxlinux.ui.components.ActiveDesktopCard
import com.ivarna.fluxlinux.ui.components.FrostContainer
import com.ivarna.fluxlinux.ui.components.FrostDivider
import com.ivarna.fluxlinux.ui.components.FrostGroupTitle
import com.ivarna.fluxlinux.ui.components.FrostPill
import com.ivarna.fluxlinux.ui.components.isChrootCard
import com.ivarna.fluxlinux.ui.theme.FolioDanger
import com.ivarna.fluxlinux.ui.theme.FrostHairline
import com.ivarna.fluxlinux.ui.theme.FrostSurface
import com.ivarna.fluxlinux.ui.theme.TextMuted
import com.ivarna.fluxlinux.ui.theme.TextPrimary
import com.ivarna.fluxlinux.ui.theme.TextSecondary
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
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
    onNavigateToInstall: (com.ivarna.fluxlinux.core.data.Distro) -> Unit,
    onNavigateToSettings: (com.ivarna.fluxlinux.core.data.Distro) -> Unit,
    onOpenTerminal: (distroId: String, root: Boolean) -> Unit = { _, _ -> },
    /** Switch bottom-nav to Terminal after an in-app session is opened (e.g. Qwen). */
    onShowTerminal: () -> Unit = {},
    /** Open the Distros tab; empty-state "Browse Distros" pill. */
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
    val distroToLaunch = remember { mutableStateOf<com.ivarna.fluxlinux.core.data.Distro?>(null) }
    // State for KDE GPU mode picker (sub-dialog)
    val showKdeGpuPicker = remember { mutableStateOf<com.ivarna.fluxlinux.core.data.Distro?>(null) }
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
     * Direct desktop launch from an environment-row pill. Same flow as the launch
     * dialog: XFCE starts via DesktopLauncher; KDE opens the GPU picker first.
     * Single-session guard: an active desktop is never duplicated.
     */
    fun launchDesktopFromRow(distro: Distro, isChrootRow: Boolean, kde: Boolean) {
        if (isChrootRow && !rootAvailable) {
            android.widget.Toast.makeText(
                context,
                "Grant superuser to Folio first",
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

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── Active desktop card (top, single frost card) ──
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
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
        }

        item(key = "env-header") {
            Column(modifier = Modifier.fillMaxWidth()) {
                Spacer(modifier = Modifier.height(8.dp))
                FrostGroupTitle("Environments")
            }
        }

        if (installedDistros.isEmpty()) {
            item(key = "env-empty") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp, horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "No environments installed",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextMuted,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    FrostPill(
                        text = "Browse Distros",
                        onClick = onBrowseDistros
                    )
                }
            }
        } else {
            if (prootInstalled.isNotEmpty()) {
                item(key = "proot-title") {
                    FrostGroupTitle("PRoot · ${prootInstalled.size}")
                }
                item(key = "proot-group") {
                    FrostContainer {
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
                                            "Grant superuser to Folio first",
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
                                onSettings = { onNavigateToSettings(distro) }
                            )
                            if (index < prootInstalled.lastIndex) FrostDivider()
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
            if (chrootInstalled.isNotEmpty()) {
                item(key = "chroot-title") {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        FrostGroupTitle("Chroot · ${chrootInstalled.size}")
                        if (!rootAvailable) {
                            Text(
                                text = "Installed, but no root given to the app. Grant superuser to start these guests.",
                                color = TextSecondary,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                            )
                        }
                    }
                }
                item(key = "chroot-group") {
                    FrostContainer {
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
                                            "Grant superuser to Folio first",
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
                                            "Grant superuser to Folio first",
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
                                onSettings = { onNavigateToSettings(distro) }
                            )
                            if (index < chrootInstalled.lastIndex) FrostDivider()
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
            // Per-row distro ids (logs availability still honours the host log file).
            item(key = "env-footnote") {
                val anyLogs = com.ivarna.fluxlinux.core.desktop.GuiDesktopLog.hasContent(context)
                if (anyLogs && session == null) {
                    TextButton(onClick = { showDesktopLogs = true }) {
                        Text(
                            "View last desktop log",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
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
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                border = androidx.compose.foundation.BorderStroke(0.75.dp, FrostHairline)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(FrostSurface)
                            .border(
                                androidx.compose.foundation.BorderStroke(0.75.dp, FrostHairline),
                                RoundedCornerShape(20.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (distro.iconRes != null) {
                            Image(
                                painter = painterResource(id = distro.iconRes),
                                contentDescription = null,
                                modifier = Modifier.size(48.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Text(
                        text = "Start ${distro.name}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Choose launch mode",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted,
                        modifier = Modifier.padding(top = 4.dp)
                    )

                    Spacer(Modifier.height(22.dp))

                    // Shell — in-app terminal (no external Termux)
                    LaunchModeAction(
                        icon = Icons.Filled.Terminal,
                        label = "Open Shell",
                        subtitle = "User session",
                        primary = true,
                        onClick = {
                            onOpenTerminal(distro.id, false)
                            distroToLaunch.value = null
                        }
                    )

                    Spacer(Modifier.height(10.dp))

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
                        Spacer(Modifier.height(10.dp))
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
                                com.ivarna.fluxlinux.core.terminal.FluxTerminalSessionManager.openSessionAfterHost(
                                    context,
                                    type = "shell",
                                    title = "Qwen2.5-1.5B",
                                    shellCmd = payload,
                                    method = com.ivarna.fluxlinux.core.data.terminalComponentFor(distro.id).method,
                                    onResult = { result ->
                                        if (result == com.ivarna.fluxlinux.core.terminal.FluxTerminalSessionManager.SessionOpenResult.OPENED) {
                                            onShowTerminal()
                                        }
                                    }
                                )
                                distroToLaunch.value = null
                            }
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    val isCurrentSessionXfce = session != null && session.distroId == distro.id && session.type == DesktopSession.Type.XFCE4
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

                    Spacer(Modifier.height(10.dp))

                    val isCurrentSessionKde = session != null && session.distroId == distro.id && session.type == DesktopSession.Type.KDE
                    LaunchModeAction(
                        icon = Icons.Filled.Widgets,
                        label = if (isCurrentSessionKde) "Open KDE Plasma" else if (kdeInstalled) "Launch KDE Plasma" else "Launch KDE",
                        subtitle = if (isCurrentSessionKde) "Running on :0" else if (kdeInstalled) "GPU mode picker" else "Not installed",
                        enabled = true,
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
                            } else if (kdeInstalled) {
                                showKdeGpuPicker.value = distro
                            } else if (!kdeInstalled) {
                                android.widget.Toast.makeText(
                                    context,
                                    "Install KDE Plasma Desktop first from Settings.",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    )

                    if (isGuiRunning) {
                        Spacer(Modifier.height(10.dp))
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

                    Spacer(Modifier.height(8.dp))

                    TextButton(
                        onClick = { distroToLaunch.value = null },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = TextPrimary)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Cancel", fontWeight = FontWeight.SemiBold)
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

// ─── Folio installed environment row ─────────────────────────────────────────
// Grouped frost row: icon + name + env-type muted + Shell/desktop pills + gear.
// Row tap opens the launch-mode dialog (shell / root / desktop); Shell pill is a
// quick Shell open; XFCE/KDE pills launch the desktop directly (KDE via the GPU
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
    onSettings: () -> Unit
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

    // Desktop launch pills: only when the component is installed for this distro
    // and the distro does not already own the active session (the top card owns
    // open/stop then — no duplicate launch buttons).
    val desktopActiveForThis = session != null && session.distroId == distro.id
    val showXfceLaunch = !desktopActiveForThis &&
        StateManager.isComponentInstalled(context, distro.id, "xfce4_desktop")
    val showKdeLaunch = !desktopActiveForThis &&
        StateManager.isComponentInstalled(context, distro.id, "kde_plasma")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenLaunch)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (distro.iconRes != null) {
            Image(
                painter = painterResource(id = distro.iconRes),
                contentDescription = null,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(FrostSurface)
                    .border(0.75.dp, FrostHairline, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Terminal,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = distro.name.removeSuffix(" (Rooted)"),
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = (if (isChroot) "Chroot" else "PRoot") + statusSuffix,
                color = TextMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Trailing controls: compact pills + settings gear, evenly spaced.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Shell pill (white primary, compact)
            Button(
                onClick = onShell,
                colors = ButtonDefaults.buttonColors(
                    containerColor = TextPrimary,
                    contentColor = Color(0xFF09090B)
                ),
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.height(30.dp)
            ) {
                Text("Shell", fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
            }

            if (showXfceLaunch) {
                EnvDesktopPill(
                    icon = Icons.Filled.DesktopWindows,
                    label = "XFCE",
                    contentDescription = "Launch XFCE4 on ${distro.name}",
                    onClick = onLaunchXfce
                )
            }

            if (showKdeLaunch) {
                EnvDesktopPill(
                    icon = Icons.Filled.Widgets,
                    label = "KDE",
                    contentDescription = "Launch KDE Plasma on ${distro.name}",
                    onClick = onLaunchKde
                )
            }

            IconButton(
                onClick = onSettings,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Distro settings",
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

// Compact monochrome launch pill: frost surface, hairline border, small icon +
// label. Same height/pill shape as the Shell button; no fill, no glow.
@Composable
private fun EnvDesktopPill(
    icon: ImageVector,
    label: String,
    contentDescription: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(50))
            .background(FrostSurface)
            .border(0.75.dp, FrostHairline, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = TextSecondary,
            modifier = Modifier.size(13.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            maxLines = 1
        )
    }
}

// ─── Desktop start/stop log dialog (Folio monochrome) ────────────────────────
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
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.75f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            border = androidx.compose.foundation.BorderStroke(0.75.dp, FrostHairline)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DesktopWindows,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                "Graphical Desktop Log",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = TextPrimary
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = phaseIcon,
                                    contentDescription = null,
                                    tint = TextSecondary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    phaseLabel,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close",
                            tint = TextPrimary
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF101214))
                        .border(
                            androidx.compose.foundation.BorderStroke(0.75.dp, FrostHairline),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(12.dp)
                ) {
                    Text(
                        text = logText.ifBlank { "Waiting for desktop output…" },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = Color(0xFFE8EAED),
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scroll)
                    )
                }
                Spacer(Modifier.height(12.dp))
                // Primary white Open X11 + ghost Copy
                Button(
                    onClick = onOpenX11,
                    enabled = phase != DesktopLauncher.Phase.Idle,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TextPrimary,
                        contentColor = Color(0xFF09090B),
                        disabledContainerColor = TextPrimary.copy(alpha = 0.28f),
                        disabledContentColor = Color(0xFF09090B).copy(alpha = 0.45f)
                    ),
                    shape = RoundedCornerShape(50)
                ) {
                    Icon(
                        imageVector = Icons.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Open X11", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onCopy,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = TextPrimary
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, FrostHairline),
                    shape = RoundedCornerShape(50)
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Copy Log", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ─── Launch mode action row (Folio: white primary or frost ghost) ─────────────
@Composable
private fun LaunchModeAction(
    icon: ImageVector,
    label: String,
    subtitle: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true
) {
    if (primary) {
        Button(
            onClick = onClick,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = TextPrimary,
                contentColor = Color(0xFF09090B),
                disabledContainerColor = TextPrimary.copy(alpha = 0.35f),
                disabledContentColor = Color(0xFF09090B).copy(alpha = 0.5f)
            ),
            shape = RoundedCornerShape(16.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            modifier = Modifier.fillMaxWidth().height(64.dp)
        ) {
            LaunchModeActionContent(icon = icon, label = label, subtitle = subtitle, tint = Color(0xFF09090B))
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = if (danger) FolioDanger else TextPrimary
            ),
            border = androidx.compose.foundation.BorderStroke(0.75.dp, FrostHairline),
            shape = RoundedCornerShape(16.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            modifier = Modifier.fillMaxWidth().height(64.dp)
        ) {
            LaunchModeActionContent(
                icon = icon,
                label = label,
                subtitle = subtitle,
                tint = if (danger) FolioDanger else TextPrimary
            )
        }
    }
}

@Composable
private fun LaunchModeActionContent(
    icon: ImageVector,
    label: String,
    subtitle: String,
    tint: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(tint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(
                label,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = tint
            )
            Text(
                subtitle,
                fontSize = 11.sp,
                color = tint.copy(alpha = 0.72f)
            )
        }
    }
}

// ─── KDE GPU Picker Dialog (Folio monochrome) ─────────────────────────────────
@Composable
private fun KdeGpuPickerDialog(
    distro: com.ivarna.fluxlinux.core.data.Distro,
    onDismiss: () -> Unit,
    onSelectHardware: () -> Unit,
    onSelectSoftware: () -> Unit
) {
    val ctx = LocalContext.current
    val driverBadge = remember(distro.id) {
        com.ivarna.fluxlinux.core.gpu.GpuDriverInstaller.badge(ctx, distro.id)
    }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            border = androidx.compose.foundation.BorderStroke(0.75.dp, FrostHairline)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Widgets,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "KDE Plasma",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Choose GPU Renderer",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted
                )

                Spacer(modifier = Modifier.height(24.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
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

                Spacer(modifier = Modifier.height(20.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(FrostSurface)
                        .border(androidx.compose.foundation.BorderStroke(0.75.dp, FrostHairline), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Hardware needs a GPU driver installed. Software works on any device (slower).",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                TextButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = TextPrimary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Cancel", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ─── Reusable GPU Option Card (Folio frost, no neon) ───────────────────────────
@Composable
private fun GpuOptionCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    description: String,
    badgeText: String?,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(FrostSurface)
            .border(
                androidx.compose.foundation.BorderStroke(0.75.dp, FrostHairline),
                RoundedCornerShape(20.dp)
            )
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.DesktopWindows,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = TextPrimary
                    )
                    if (badgeText != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.White.copy(alpha = 0.08f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = badgeText,
                                fontSize = 9.sp,
                                color = TextSecondary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = TextSecondary,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = description,
                    fontSize = 11.sp,
                    color = TextMuted,
                    textAlign = TextAlign.Start,
                    lineHeight = 15.sp
                )
            }
        }
    }
}
