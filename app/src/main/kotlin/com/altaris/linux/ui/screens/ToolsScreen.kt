package com.altaris.linux.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altaris.linux.core.control.AgentDetector
import com.altaris.linux.core.control.AltarisCliInstaller
import com.altaris.linux.core.control.CloneControlService
import com.altaris.linux.core.control.ControlServer
import com.altaris.linux.core.control.DetectedAgent
import com.altaris.linux.core.control.DistroAgentReport
import com.altaris.linux.core.rea.ReaCatalog
import com.altaris.linux.core.rea.ReaManager
import com.altaris.linux.ui.components.AltarisTitle
import com.altaris.linux.ui.components.PrismButton
import com.altaris.linux.ui.components.PrismCard
import com.altaris.linux.ui.components.PrismChip
import com.altaris.linux.ui.components.PrismIconTile
import com.altaris.linux.ui.components.PrismOrb
import com.altaris.linux.ui.components.PrismRow
import com.altaris.linux.ui.components.PrismSectionLabel
import com.altaris.linux.ui.theme.AltarisSection
import com.altaris.linux.ui.theme.Prism
import com.altaris.linux.ui.theme.altarisMutedText
import com.altaris.linux.ui.theme.sectionAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.altaris.linux.core.utils.TerminalPreferences
import androidx.compose.foundation.interaction.MutableInteractionSource

private enum class ToolsPage { HOME, CLONE, REA }

private const val MCP_CONFIG = """{
  "mcp": {
    "altaris": {
      "type": "remote",
      "url": "http://127.0.0.1:47831/mcp",
      "enabled": true
    }
  }
}"""

private const val CLI_HELP = """altaris status
altaris clones
altaris apps
altaris list
altaris ps
altaris install /sdcard/Download/app.apk [label]
altaris install https://example.com/app.apk [label]
altaris uninstall <clone-package>
altaris launch <clone-package> [width height fps]
altaris stop <clone-package>
altaris shot [out.jpg]
altaris logs <clone-package> [lines]
altaris ocr
altaris tap <x> <y>
altaris swipe <x1> <y1> <x2> <y2> [ms]
altaris text <text...>
altaris key <back|home|enter|tab|esc|recents|keycode>
altaris record start [fps] [width]
altaris record stop
altaris record status"""

private const val AGENT_BRIEF = """You are controlling Altaris clones through MCP.
Endpoint: http://127.0.0.1:47831/mcp

Protocol:
1. initialize
2. tools/list
3. tools/call

Rules:
- Call altaris_status and altaris_clones first.
- Use altaris_install_apk with path=/sdcard/Download/app.apk or a URL.
- Use altaris_launch_clone once; then use altaris_screenshot/altaris_ocr to see.
- Input is in virtual-display pixels: altaris_tap, altaris_swipe, altaris_text, altaris_key.
- Use altaris_logs after a crash; use altaris_stop_clone when done.
- Screenshots and OCR are available even when the viewer is not open.
- The same operations are available in any distro terminal as `altaris ...` (or the legacy `bliss` alias).

If a task needs visual confirmation, take a screenshot after every action group.
The UI action feed in the clone viewer shows your taps, swipes, text and keys."""

@Composable
fun ToolsScreen() {
    val context = LocalContext.current
    val accent = sectionAccent(AltarisSection.TOOLS)
    var page by remember { mutableStateOf(ToolsPage.HOME) }
    var running by remember { mutableStateOf(ControlServer.isRunning()) }
    var error by remember { mutableStateOf(ControlServer.error()) }

    LaunchedEffect(Unit) {
        while (true) {
            running = ControlServer.isRunning()
            error = ControlServer.error()
            delay(1800)
        }
    }

    BackHandler(enabled = page != ToolsPage.HOME) { page = ToolsPage.HOME }

    when (page) {
        ToolsPage.HOME -> ToolsHome(
            accent = accent,
            onOpen = { page = it }
        )
        ToolsPage.CLONE -> ToolsDetail(
            accent = accent,
            title = "Clone",
            subtitle = "Server, MCP, CLI, desktop agents and OCR/input",
            onBack = { page = ToolsPage.HOME },
        ) {
            ControlCard(context, accent, running, error) { running = ControlServer.isRunning() }
            AgentsCard(context, accent)
            McpCard(context, accent)
            CliCard(context, accent)
            AutomationCard(context, accent)
        }
        ToolsPage.REA -> ToolsDetail(
            accent = accent,
            title = "Reverse engineering",
            subtitle = "REA tools, live analysis, agent MCP",
            onBack = { page = ToolsPage.HOME },
        ) {
            ReaCard(context, accent)
            ReaGhidraCard(context, accent)
            ReaToolsCard(context, accent)
        }
    }
}

@Composable
private fun ToolsHome(
    accent: Color,
    onOpen: (ToolsPage) -> Unit
) {
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Prism.Gutter)
            .padding(top = 10.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(Prism.Stack),
    ) {
        AltarisTitle(
            "Tools",
            "Folders for clone automation, agents and local control.",
            accent
        )
        PrismSectionLabel("Folder", accent)
        PrismCard(accent = accent) {
            CategoryRow(
                "Clone",
                "Server/API · MCP · CLI · desktop agent scan · OCR/input",
                Icons.Default.Build,
                accent,
            ) { onOpen(ToolsPage.CLONE) }
            CategoryRow(
                "Reverse engineering",
                "REA · APK/native/firmware analysis · live device · agent MCP",
                Icons.Default.Search,
                accent,
            ) { onOpen(ToolsPage.REA) }
        }
    }
}

@Composable
private fun CategoryRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit
) {
    PrismRow(
        title = title,
        subtitle = subtitle,
        accent = accent,
        leading = {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(Prism.TileRadius))
                    .background(accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp)
                )
            }
        },
        onClick = onClick,
    )
}

@Composable
private fun ToolsDetail(
    accent: Color,
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Prism.Gutter)
            .padding(top = 10.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(Prism.Stack),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrismIconTile(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                accent = accent,
                contentDescription = "Back",
                onClick = onBack
            )
            Spacer(Modifier.width(10.dp))
            AltarisTitle(title, subtitle, accent, Modifier.weight(1f))
        }
        content()
    }
}

@Composable
private fun ControlCard(
    context: Context,
    accent: Color,
    running: Boolean,
    error: String?,
    onStarted: () -> Unit
) {
    var syncing by remember { mutableStateOf(false) }
    var syncNote by remember { mutableStateOf<String?>(null) }
    PrismSectionLabel("Control plane", accent)
    PrismCard(accent = accent) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PrismOrb(active = running, accent = accent)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (running) "Control server online" else "Control server offline",
                    fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    error ?: "Loopback only · 127.0.0.1:${ControlServer.PORT}",
                    fontSize = 11.5.sp,
                    color = altarisMutedText()
                )
            }
        }
        if (!running) {
            Spacer(Modifier.height(12.dp))
            PrismButton(
                text = "Start control server",
                accent = accent,
                icon = Icons.Default.PlayArrow,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    runCatching { CloneControlService.start(context) }
                    runCatching { ControlServer.start(context) }
                    onStarted()
                }
            )
        }
        Spacer(Modifier.height(Prism.Tight))
        PrismRow(
            title = "HTTP API",
            subtitle = ControlServer.BASE_URL,
            accent = accent
        )
        PrismRow(
            title = "MCP",
            subtitle = "${ControlServer.BASE_URL}/mcp",
            accent = accent
        )
        PrismRow(
            title = "Guest CLI",
            subtitle = "altaris · bliss alias",
            accent = accent
        )
        Spacer(Modifier.height(Prism.Tight))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Prism.Tight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PrismButton(
                text = "Copy MCP config",
                accent = accent,
                icon = Icons.Default.ContentCopy,
                modifier = Modifier.weight(1f),
                onClick = { copy(context, MCP_CONFIG, "MCP config copied") }
            )
            PrismChip(
                text = if (syncing) "Syncing…" else "Sync CLI",
                accent = accent,
                onClick = {
                    if (!syncing) {
                        syncing = true
                        Thread {
                            runCatching { AltarisCliInstaller.installAll(context) }
                                .onSuccess { syncNote = "CLI synced" }
                                .onFailure { syncNote = it.message ?: "CLI sync failed" }
                            syncing = false
                        }.start()
                    }
                }
            )
        }
        if (syncNote != null) {
            Text(
                syncNote!!,
                fontSize = 11.5.sp,
                color = altarisMutedText(),
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun AgentsCard(context: Context, accent: Color) {
    var tick by remember { mutableIntStateOf(0) }
    var reports by remember { mutableStateOf<List<DistroAgentReport>>(emptyList()) }
    var hostAgents by remember { mutableStateOf<List<DetectedAgent>>(emptyList()) }
    var scanned by remember { mutableStateOf(false) }

    LaunchedEffect(tick) {
        val result = withContext(Dispatchers.IO) {
            val distros = AgentDetector.scanDistros(context)
            val host = AgentDetector.scan(context).filter { it.kind != "guest cli" }
            distros to host
        }
        reports = result.first
        hostAgents = result.second
        scanned = true
    }

    PrismSectionLabel("AI agents by installed distro", accent)
    PrismCard(accent = accent) {
        Text(
            "Checks the host and every installed proot/chroot distro for OpenCode, Pi, Alpha, Hermes, Claude, Codex, Aider, Gemini and similar. Each distro gets its own MCP button.",
            fontSize = 12.sp,
            color = altarisMutedText(),
        )
        Spacer(Modifier.height(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(Prism.Tight),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PrismButton(
                text = "Scan installed distros",
                accent = accent,
                icon = Icons.Default.Refresh,
                modifier = Modifier.weight(1f),
                onClick = { tick++ }
            )
            PrismChip(
                text = "Copy agent brief",
                accent = accent,
                onClick = { copy(context, AGENT_BRIEF, "Agent brief copied") }
            )
        }
        Spacer(Modifier.height(Prism.Tight))
        if (!scanned) {
            Text(
                "Scanning distro filesystems…",
                fontSize = 12.sp,
                color = altarisMutedText()
            )
        } else if (reports.isEmpty()) {
            Text(
                "No installed proot/chroot distros found.",
                fontSize = 12.sp,
                color = altarisMutedText()
            )
        } else {
            // The per-distro agent matrix: one row per distro, MCP button included.
            reports.forEachIndexed { index, report ->
                PrismRow(
                    title = report.name,
                    subtitle = "${report.method} · ${report.agents.size} agent${if (report.agents.size == 1) "" else "s"}",
                    accent = accent,
                    trailing = {
                        PrismChip(
                            text = "MCP",
                            accent = accent,
                            onClick = {
                                copy(context, MCP_CONFIG, "${report.name} MCP config copied")
                            }
                        )
                    },
                    divider = index != reports.lastIndex
                )
                if (report.agents.isEmpty()) {
                    Text(
                        "No supported agent binary found",
                        fontSize = 10.5.sp,
                        color = altarisMutedText(),
                        modifier = Modifier.padding(
                            start = Prism.Gutter * 2, end = Prism.Gutter, bottom = 8.dp,
                        ),
                    )
                } else {
                    report.agents.forEach { agent ->
                        Text(
                            "• ${agent.name}  ${agent.detail}",
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                            fontFamily = FontFamily.Monospace,
                            color = accent,
                            modifier = Modifier.fillMaxWidth().padding(
                                start = Prism.Gutter * 2,
                                end = Prism.Gutter,
                                bottom = 3.dp,
                            ),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }

    Spacer(Modifier.height(Prism.Stack))
    PrismSectionLabel("Host desktop agents", accent)
    PrismCard(accent = accent) {
        if (!scanned) {
            Text("Scanning…", fontSize = 12.sp, color = altarisMutedText())
        } else if (hostAgents.isEmpty()) {
            Text(
                "No desktop agent binaries found in the host prefix.",
                fontSize = 12.sp,
                color = altarisMutedText()
            )
        } else {
            hostAgents.forEachIndexed { index, agent ->
                PrismRow(
                    title = "${agent.name}  ·  ${agent.kind}",
                    subtitle = agent.detail,
                    accent = accent,
                    divider = index != hostAgents.lastIndex
                )
            }
        }
    }

    Spacer(Modifier.height(Prism.Stack))
    PrismSectionLabel("Agent brief", accent)
    PrismCard(accent = accent) { CodeBlock(AGENT_BRIEF) }
}

@Composable
private fun CliCard(context: Context, accent: Color) {
    PrismSectionLabel("Commands", accent)
    PrismCard(accent = accent) {
        Text(
            "Installed as `altaris` (with a `bliss` compatibility alias) in the host and every proot/chroot guest.",
            fontSize = 12.sp,
            color = altarisMutedText()
        )
        Spacer(Modifier.height(Prism.Tight))
        CodeBlock(CLI_HELP)
        Spacer(Modifier.height(Prism.Tight))
        PrismButton(
            text = "Copy commands",
            accent = accent,
            icon = Icons.Default.ContentCopy,
            onClick = { copy(context, CLI_HELP, "CLI copied") }
        )
    }
}

@Composable
private fun McpCard(context: Context, accent: Color) {
    PrismSectionLabel("MCP endpoint", accent)
    PrismCard(accent = accent) {
        PrismRow(
            title = "URL",
            subtitle = "${ControlServer.BASE_URL}/mcp",
            accent = accent
        )
        PrismRow(
            title = "Transport",
            subtitle = "JSON-RPC 2.0 over HTTP",
            accent = accent
        )
        Spacer(Modifier.height(Prism.Tight))
        CodeBlock(MCP_CONFIG)
        Spacer(Modifier.height(Prism.Tight))
        PrismButton(
            text = "Copy config",
            accent = accent,
            icon = Icons.Default.ContentCopy,
            onClick = { copy(context, MCP_CONFIG, "MCP config copied") }
        )
    }
    Spacer(Modifier.height(Prism.Stack))
    PrismSectionLabel("Tools", accent)
    PrismCard(accent = accent) {
        val tools = listOf(
            "altaris_status · clones · apps" to "Observe engine, clones and installed apps",
            "altaris_install_apk · uninstall_clone" to "Add or remove an APK clone",
            "altaris_launch_clone · stop_clone" to "Control a background clone",
            "altaris_screenshot · ocr" to "See the display and read text boxes",
            "altaris_tap · swipe · text · key" to "Drive the virtual display",
            "altaris_logs" to "Diagnose crashes and display state",
        )
        tools.forEachIndexed { i, (t, d) ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(
                    t,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(d, fontSize = 11.5.sp, color = altarisMutedText())
            }
            if (i != tools.lastIndex) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                )
            }
        }
    }
}

@Composable
private fun AutomationCard(context: Context, accent: Color) {
    PrismSectionLabel("How an agent sees", accent)
    PrismCard(accent = accent) {
        Text(
            "`altaris_ocr` / GET /ocr captures the virtual display and returns text plus boxes with centre coordinates. Use the centre with `altaris_tap`.",
            fontSize = 12.sp,
            color = altarisMutedText()
        )
        Spacer(Modifier.height(Prism.Tight))
        CodeBlock("altaris shot /sdcard/Download/state.jpg\naltaris ocr /sdcard/Download/state.json\naltaris tap 540 1200\naltaris swipe 540 1600 540 500 280\naltaris text hello\naltaris key back")
        Spacer(Modifier.height(Prism.Tight))
        PrismButton(
            text = "Copy examples",
            accent = accent,
            icon = Icons.Default.ContentCopy,
            onClick = {
                copy(
                    context,
                    "altaris shot /sdcard/Download/state.jpg\naltaris ocr\naltaris tap 540 1200",
                    "Examples copied"
                )
            }
        )
    }
    Spacer(Modifier.height(Prism.Stack))
    PrismSectionLabel("Action feed", accent)
    PrismCard(accent = accent) {
        Text(
            "The clone viewer shows an ACTIONS strip with the agent's taps, swipes, text, keys and launches as they happen.",
            fontSize = 12.sp,
            color = altarisMutedText()
        )
    }
}

@Composable
private fun CodeBlock(text: String) {
    SelectionContainer {
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.5.sp,
            lineHeight = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.72f))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

private fun copy(context: Context, text: String, toast: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Altaris", text))
    Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
}

@Composable
private fun ReaCard(context: Context, accent: Color) {
    val scope = rememberCoroutineScope()
    val status by ReaManager.status.collectAsState()
    var distros by remember { mutableStateOf<List<String>>(emptyList()) }
    var selected by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(tick) {
        val ids = ReaManager.installedDistros(context)
        distros = ids
        val pick = selected.takeIf { it.isNotBlank() && ids.contains(it) }
            ?: ReaManager.defaultDistro(ids).orEmpty()
        selected = pick
        if (pick.isNotBlank()) ReaManager.probe(context, pick)
    }

    PrismCard(accent = accent) {
        PrismSectionLabel("REA", accent)
        PrismRow(
            title = "Reverse Engineer Anything",
            subtitle = when {
                status.busy.isNotBlank() -> status.busy
                status.running -> "Running · " + status.toolCount + " tools · " + status.reaVersion
                status.installed -> "Installed · " + status.reaVersion + " · Node " + status.nodeVersion
                selected.isBlank() -> "Install a distro first"
                else -> "Not installed in " + selected
            },
            accent = accent,
            leading = { PrismOrb(active = status.running, accent = accent) },
            trailing = {
                PrismChip(
                    when {
                        status.running -> "Running"
                        status.installed -> "Stopped"
                        else -> "Missing"
                    },
                    accent,
                    selected = status.running,
                )
            },
        )
        if (distros.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                distros.forEach { id ->
                    PrismChip(
                        id,
                        accent,
                        selected = id == selected,
                        onClick = {
                            selected = id
                            scope.launch { ReaManager.probe(context, id) }
                        },
                    )
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PrismButton(
                if (status.installed) "Reinstall" else "Install REA",
                accent,
                icon = Icons.Default.PlayArrow,
                enabled = selected.isNotBlank() && status.busy.isBlank(),
            ) {
                scope.launch {
                    ReaManager.install(context, selected)
                    tick++
                }
            }
            PrismButton(
                if (status.running) "Stop" else "Start",
                accent,
                enabled = status.installed && status.busy.isBlank(),
            ) {
                scope.launch {
                    if (status.running) ReaManager.stop()
                    else ReaManager.start(context, selected)
                }
            }
        }
        PrismButton(
            "Register in agents",
            accent,
            enabled = status.installed && status.busy.isBlank(),
        ) {
            scope.launch { ReaManager.registerAgents(context, selected) }
        }
        if (status.error.isNotBlank()) {
            Text(
                status.error,
                color = MaterialTheme.colorScheme.error,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
        if (status.log.isNotBlank()) {
            CodeBlock(status.log.takeLast(2500))
        }
    }
}

@Composable
private fun ReaToolsCard(context: Context, accent: Color) {
    val scope = rememberCoroutineScope()
    val status by ReaManager.status.collectAsState()

    PrismCard(accent = accent) {
        PrismSectionLabel("Analysis tools", accent)
        ReaCatalog.DEPENDENCIES.forEach { dep ->
            val installed = dep.id in status.available
            PrismRow(
                title = dep.label,
                subtitle = when (dep.kind) {
                    "license" -> dep.note.ifBlank { "Needs a license; not bundled." }
                    else -> dep.description
                },
                accent = accent,
                divider = true,
                trailing = {
                    PrismChip(
                        when {
                            dep.kind == "license" -> "License"
                            installed -> "Installed"
                            else -> "Install"
                        },
                        accent,
                        selected = installed,
                        onClick = when {
                            dep.kind == "license" -> null
                            installed -> null
                            status.busy.isNotBlank() -> null
                            else -> ({
                                scope.launch {
                                    ReaManager.install(context, status.distroId, listOf(dep.id))
                                }
                            })
                        },
                    )
                },
            )
        }
    }
}


@Composable
private fun ReaGhidraCard(context: Context, accent: Color) {
    val scope = rememberCoroutineScope()
    val status by ReaManager.status.collectAsState()
    var target by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var analyzing by remember { mutableStateOf(false) }

    PrismCard(accent = accent) {
        PrismSectionLabel("Ghidra analysis", accent)
        PrismRow(
            title = "Headless decompiler",
            subtitle = if (status.available.contains("ghidra")) {
                "Ready. Runs on ARM64; first analysis is slow."
            } else {
                "Install Ghidra from the list below, then analyze a binary here."
            },
            accent = accent,
            trailing = {
                PrismChip(
                    if (analyzing) "Running" else "Analyze",
                    accent,
                    selected = analyzing,
                )
            },
        )
        OutlinedTextField(
            value = target,
            onValueChange = { target = it },
            label = { Text("Guest path (binary / APK / library)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        PrismButton(
            "Analyze with Ghidra",
            accent,
            icon = Icons.Default.Search,
            enabled = target.isNotBlank() && status.available.contains("ghidra") && !analyzing,
        ) {
            analyzing = true
            output = ""
            scope.launch {
                try {
                    val res = ReaManager.analyzeGhidra(context, status.distroId, target, 300)
                    output = (res.optString("output").ifBlank { res.optString("error") })
                        .ifBlank { "No output." }
                } catch (t: Throwable) {
                    output = t.message ?: t.javaClass.simpleName
                } finally {
                    analyzing = false
                }
            }
        }
        if (output.isNotBlank()) {
            CodeBlock(output.takeLast(3000))
        }
    }
}

