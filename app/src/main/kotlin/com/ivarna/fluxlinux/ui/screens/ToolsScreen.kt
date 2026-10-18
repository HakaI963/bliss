package com.ivarna.fluxlinux.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivarna.fluxlinux.core.clone.CloneEngine
import com.ivarna.fluxlinux.core.clone.ShellRunner
import com.ivarna.fluxlinux.core.system.PhantomProcessFixer
import com.ivarna.fluxlinux.core.terminal.ShellIntegration
import com.ivarna.fluxlinux.ui.components.QCard
import com.ivarna.fluxlinux.ui.components.QEmptyState
import com.ivarna.fluxlinux.ui.components.QIconTile
import com.ivarna.fluxlinux.ui.components.QLogPane
import com.ivarna.fluxlinux.ui.components.QPill
import com.ivarna.fluxlinux.ui.components.QPrimaryButton
import com.ivarna.fluxlinux.ui.components.QRow
import com.ivarna.fluxlinux.ui.components.QScreenTitle
import com.ivarna.fluxlinux.ui.components.QSectionHeader
import com.ivarna.fluxlinux.ui.components.QStatusRow
import com.ivarna.fluxlinux.ui.components.QTextField
import com.ivarna.fluxlinux.ui.components.QToggleRow
import com.ivarna.fluxlinux.ui.components.Quartz
import com.ivarna.fluxlinux.ui.theme.fluxMutedText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ToolsPage { HUB, CLONE, DISPLAY, SETUP, DIAGNOSTICS }

/**
 * Tools tab — a small hub with one page per topic so new features can be added
 * without crowding a single long scroll: Clone (device app / APK / link /
 * installed), Display, Setup, Diagnostics.
 */
@Composable
fun ToolsScreen(onOpenApps: () -> Unit) {
    var page by remember { mutableStateOf(ToolsPage.HUB) }
    when (page) {
        ToolsPage.HUB -> ToolsHub(onOpen = { page = it })
        ToolsPage.CLONE -> ClonePage(onBack = { page = ToolsPage.HUB }, onOpenApps = onOpenApps)
        ToolsPage.DISPLAY -> DisplayPage(onBack = { page = ToolsPage.HUB })
        ToolsPage.SETUP -> SetupPage(onBack = { page = ToolsPage.HUB })
        ToolsPage.DIAGNOSTICS -> DiagnosticsPage(onBack = { page = ToolsPage.HUB })
    }
}

@Composable
private fun ToolsHub(onOpen: (ToolsPage) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Quartz.ScreenPad, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(Quartz.Gap),
    ) {
        QScreenTitle("Tools", "Clone space, display and diagnostics.")
        QCard {
            QRow(
                "Clone",
                subtitle = "Clone an installed app, pick an APK, paste a link, manage clones",
                onClick = { onOpen(ToolsPage.CLONE) },
                divider = true,
            )
            QRow(
                "Display",
                subtitle = "FPS, resolution and DPI of the hidden display",
                onClick = { onOpen(ToolsPage.DISPLAY) },
                divider = true,
            )
            QRow(
                "Setup",
                subtitle = "Shell access (Shizuku or root) and the clone space user",
                onClick = { onOpen(ToolsPage.SETUP) },
                divider = true,
            )
            QRow(
                "Diagnostics",
                subtitle = "Status, logs and shell integration report",
                onClick = { onOpen(ToolsPage.DIAGNOSTICS) },
            )
        }
        Text(
            "Agent:  clone help   ·   MCP:  clone mcp-config   (in any distro)",
            fontSize = 10.5.sp,
            color = fluxMutedText(),
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun PageScaffold(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Quartz.ScreenPad, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(Quartz.Gap),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
            QScreenTitle(title, subtitle)
        }
        content()
        Spacer(Modifier.height(96.dp))
    }
}

// ---------------------------------------------------------------- clone page

@Composable
private fun ClonePage(onBack: () -> Unit, onOpenApps: () -> Unit) {
    val context = LocalContext.current
    val busy by CloneEngine.busy.collectAsState()
    val status by CloneEngine.status.collectAsState()
    val deviceApps by CloneEngine.deviceApps.collectAsState()
    val clones by CloneEngine.apps.collectAsState()
    var url by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        CloneEngine.refreshDeviceApps()
        CloneEngine.refresh()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { CloneEngine.installUri(it) }
    }

    PageScaffold("Clone", "Apps live inside Bliss only.", onBack) {
        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        status?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }

        QSectionHeader("From this phone")
        QCard {
            QTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = "Search installed apps",
                modifier = Modifier.padding(horizontal = Quartz.CardPad, vertical = 6.dp),
            )
            val filtered = deviceApps
                .filter {
                    search.isBlank() ||
                        it.label.contains(search, true) ||
                        it.packageName.contains(search, true)
                }
                .take(60)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (filtered.isEmpty()) {
                    QEmptyState("Nothing found", "Apps installed on the phone appear here.")
                } else {
                    filtered.forEach { app ->
                        QRow(
                            title = app.label,
                            subtitle = app.packageName,
                            leading = { QIconTile(bitmap = app.icon, size = 30.dp) },
                            trailing = {
                                TextButton(
                                    onClick = { CloneEngine.cloneFromDevice(app.packageName) },
                                    enabled = !busy,
                                ) { Text("Clone", fontSize = 12.sp) }
                            },
                        )
                    }
                }
            }
        }

        QSectionHeader("APK file")
        QCard {
            Column(modifier = Modifier.padding(Quartz.CardPad), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Install an APK (or an .apks/.xapk/.apkm split set) into the Bliss space.",
                    fontSize = 11.5.sp,
                    color = fluxMutedText(),
                )
                QPrimaryButton("Choose APK file", onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy)
            }
        }

        QSectionHeader("From a link")
        QCard {
            Column(modifier = Modifier.padding(Quartz.CardPad), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                QTextField(value = url, onValueChange = { url = it }, placeholder = "https://…/app.apk")
                QPrimaryButton(
                    "Download & install",
                    onClick = {
                        if (url.isNotBlank()) {
                            CloneEngine.installFromUrl(url)
                            url = ""
                        }
                    },
                    enabled = !busy && url.isNotBlank(),
                )
            }
        }

        QSectionHeader("Cloned apps")
        QCard {
            if (clones.isEmpty()) {
                QEmptyState("No clones yet", "Clone something above — it shows up in the Apps tab.")
            } else {
                clones.forEach { app ->
                    QRow(
                        title = app.label,
                        subtitle = app.packageName,
                        leading = { QIconTile(bitmap = app.icon, size = 30.dp) },
                        trailing = {
                            TextButton(
                                onClick = { CloneEngine.uninstall(app.packageName) },
                                enabled = !busy,
                            ) {
                                Text("Uninstall", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                            }
                        },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Quartz.CardPad, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${clones.size} clone${if (clones.size == 1) "" else "s"}",
                        fontSize = 12.sp,
                        color = fluxMutedText(),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onOpenApps) { Text("Open Apps", fontSize = 12.sp) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- display page

@Composable
private fun DisplayPage(onBack: () -> Unit) {
    val fps by CloneEngine.displayFps.collectAsState()
    val res by CloneEngine.displayResPreset.collectAsState()
    val dpi by CloneEngine.displayDpi.collectAsState()
    val dw by CloneEngine.displayW.collectAsState()
    val dh by CloneEngine.displayH.collectAsState()
    val frameState by CloneEngine.frame.collectAsState()

    PageScaffold("Display", "Hidden display tuning.", onBack) {
        QCard {
            Column(modifier = Modifier.padding(Quartz.CardPad), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Frame rate · $fps fps",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Slider(
                    value = fps.toFloat(),
                    onValueChange = { CloneEngine.displayFps.value = it.toInt().coerceIn(5, 60) },
                    valueRange = 5f..60f,
                    steps = 54,
                )
                Text("Resolution", fontSize = 11.5.sp, color = fluxMutedText())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CloneEngine.RES_PRESETS.forEachIndexed { i, preset ->
                        QPill("${preset.first}×${preset.second}", res == i) {
                            CloneEngine.displayResPreset.value = i
                        }
                    }
                }
                Text("DPI · higher = smaller UI", fontSize = 11.5.sp, color = fluxMutedText())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CloneEngine.DPI_PRESETS.forEach { value ->
                        QPill(value.toString(), dpi == value) {
                            CloneEngine.displayDpi.value = value
                        }
                    }
                }
                QPrimaryButton("Apply & restart display") { CloneEngine.applyDisplayConfig() }
                QStatusRow("Current", "${dw}×$dh · ${dpi} dpi")
                QStatusRow("Frames", (frameState?.seq ?: 0).toString())
            }
        }
    }
}

// ---------------------------------------------------------------- setup page

@Composable
private fun SetupPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backend by CloneEngine.touchBackend.collectAsState()
    val cloneUser by CloneEngine.cloneUserId.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var fallback by remember(tick) { mutableStateOf(CloneEngine.cloneFallbackMain()) }

    PageScaffold("Setup", "Shell access and the clone space.", onBack) {
        QSectionHeader("Shell access")
        QCard {
            QStatusRow("Backend", backend.label)
            if (backend == ShellRunner.Backend.NONE) {
                Column(modifier = Modifier.padding(horizontal = Quartz.CardPad), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Shizuku gives shell access without root. Start Shizuku (wireless debugging), then enable it here. Root works too.",
                        fontSize = 11.sp,
                        color = fluxMutedText(),
                    )
                    QPrimaryButton("Enable Shizuku", onClick = { requestShell(context) { CloneEngine.refreshShell() } })
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Quartz.CardPad, vertical = 2.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = {
                    CloneEngine.refreshShell()
                    tick++
                }) { Text("Re-check", fontSize = 12.sp) }
            }
        }

        QSectionHeader("Clone space")
        QCard {
            QStatusRow("Isolated user", CloneEngine.cloneSpaceLabel(), if (cloneUser > 0) MaterialTheme.colorScheme.primary else null)
            Column(modifier = Modifier.padding(horizontal = Quartz.CardPad), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Cloned apps are installed for a separate Android user, so they never appear in your launcher and keep their own data.",
                    fontSize = 11.sp,
                    color = fluxMutedText(),
                )
                QPrimaryButton(
                    if (cloneUser > 0) "Restart clone space user" else "Create clone space now",
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            val (_, err) = withContext(Dispatchers.IO) { CloneEngine.ensureCloneUserBlocking() }
                            message = err ?: "Clone space ready (user ${CloneEngine.cloneUserId.value})"
                            CloneEngine.refreshShell()
                            busy = false
                            tick++
                        }
                    },
                )
                message?.let { Text(it, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.primary) }
            }
        }

        QSectionHeader("Fallback")
        QCard {
            QToggleRow(
                "Install into the main user if needed",
                subtitle = "Only when the isolated user can't be created — those apps appear in your launcher.",
                checked = fallback,
                onCheckedChange = {
                    fallback = it
                    CloneEngine.setCloneFallbackMain(it)
                },
            )
        }
    }
}

// ---------------------------------------------------------------- diagnostics page

@Composable
private fun DiagnosticsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val backend by CloneEngine.touchBackend.collectAsState()
    val cloneUser by CloneEngine.cloneUserId.collectAsState()
    val running by CloneEngine.running.collectAsState()
    val frameState by CloneEngine.frame.collectAsState()
    val clones by CloneEngine.apps.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    val displayId = remember(tick, frameState) { CloneEngine.currentDisplayId() }
    val engineTail = remember(tick, frameState) { CloneEngine.engineLogTail(140) }
    val shellReport = remember(tick) {
        runCatching { ShellIntegration.reportLines(context) }.getOrDefault(emptyList())
    }

    PageScaffold("Diagnostics", "Status and logs.", onBack) {
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = {
                CloneEngine.refreshShell()
                tick++
            }) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp), tint = fluxMutedText())
                Spacer(Modifier.width(4.dp))
                Text("Refresh", fontSize = 12.sp)
            }
        }
        QCard {
            QStatusRow("Shell", backend.label)
            QStatusRow("Clone space", CloneEngine.cloneSpaceLabel())
            QStatusRow("Display", if (displayId > 0) "id $displayId · ${CloneEngine.displayW.value}×${CloneEngine.displayH.value}" else "idle")
            QStatusRow("Running", running ?: "—")
            QStatusRow("Clones", clones.size.toString())
        }
        QSectionHeader("Shell integration (colors + suggestions)")
        QCard {
            QLogPane(shellReport, maxHeight = 120.dp, newestFirst = false)
        }
        QSectionHeader("Clone log")
        QCard {
            QLogPane(engineTail, maxHeight = 240.dp)
        }
    }
}

private fun requestShell(context: android.content.Context, onDone: () -> Unit) {
    when {
        PhantomProcessFixer.shizukuRunning() -> PhantomProcessFixer.requestShizuku { onDone() }
        PhantomProcessFixer.rootAvailable() -> onDone()
        else -> Toast.makeText(
            context,
            "Start the Shizuku app first (wireless debugging), then try again.",
            Toast.LENGTH_LONG,
        ).show()
    }
}
