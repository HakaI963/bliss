package com.altaris.linux.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import com.altaris.linux.core.clone.HeadlessCloneEngine
import com.altaris.linux.core.control.AgentDetector
import com.altaris.linux.core.control.AltarisCliInstaller
import com.altaris.linux.core.control.CloneControlService
import com.altaris.linux.core.control.ControlServer
import com.altaris.linux.core.control.DetectedAgent
import com.altaris.linux.core.control.DistroAgentReport
import com.altaris.linux.core.rea.ReaCatalog
import com.altaris.linux.core.rea.ReaManager
import com.altaris.linux.ui.components.AltarisTitle
import com.altaris.linux.ui.components.PrismActions
import com.altaris.linux.ui.components.PrismButton
import com.altaris.linux.ui.components.PrismCard
import com.altaris.linux.ui.components.PrismChip
import com.altaris.linux.ui.components.PrismIconTile
import com.altaris.linux.ui.components.PrismNote
import com.altaris.linux.ui.components.PrismOrb
import com.altaris.linux.ui.components.PrismRow
import com.altaris.linux.ui.components.PrismSecondaryButton
import com.altaris.linux.ui.components.PrismSectionLabel
import com.altaris.linux.ui.theme.AltarisSection
import com.altaris.linux.ui.theme.Prism
import com.altaris.linux.ui.theme.altarisMutedText
import com.altaris.linux.ui.theme.sectionAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
altaris sessions
altaris clones
altaris apps
altaris list
altaris ps
altaris install /sdcard/Download/app.apk [label]
altaris install https://example.com/app.apk [label]
altaris uninstall <clone-package>
altaris launch <clone-package> [width height fps]
altaris stop <clone-package>
altaris shot [out.jpg] [--package pkg]
altaris logs <clone-package> [lines]
altaris ocr [out.json] [--package pkg]
altaris tap <x> <y> [--package pkg]
altaris swipe <x1> <y1> <x2> <y2> [ms] [--package pkg]
altaris text <text...> [--package pkg]
altaris key <back|home|enter|tab|esc|recents|keycode> [--package pkg]
altaris record start [fps] [width] [--package pkg]
altaris record stop
altaris record status
altaris open <url>"""

private const val AGENT_BRIEF = """You are controlling Altaris clones through MCP.
Endpoint: http://127.0.0.1:47831/mcp

Protocol:
1. initialize
2. tools/list
3. tools/call

Rules:
- Call altaris_status and altaris_sessions first. Every launch gets its own
  virtual display, so several clones can run at the same time.
- Use altaris_install_apk with path=/sdcard/Download/app.apk or a URL.
- Use altaris_launch_clone with the clone package; it never stops another
  clone that is already running.
- Pass package=<clone package> to altaris_screenshot, altaris_ocr, altaris_tap,
  altaris_swipe, altaris_text and altaris_key so you drive your own display,
  not whichever clone ran last.
- Use altaris_logs after a crash; use altaris_stop_clone when done. Stopping a
  clone only releases that clone's display.
- Screenshots and OCR are available even when the viewer is not open.
- The same operations are available in any distro terminal as `altaris ...`.
- `altaris open <url>` opens a link in the phone browser. Guest shells have no
  browser, so xdg-open / sensible-browser / ${'$'}BROWSER already point at it; use
  it for sign-in links that a CLI prints instead of expecting a desktop browser.

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
            SessionsCard(context, accent)
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
            ReaToolsCard(context, accent)
            ReaGhidraCard(context, accent)
            ReaLogCard(accent)
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
                size = 36.dp,
                onClick = onBack
            )
            Spacer(Modifier.width(12.dp))
            AltarisTitle(title, subtitle, accent, Modifier.weight(1f))
        }
        content()
    }
}

// --------------------------------------------------------------------- clone

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
            modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
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
        Spacer(Modifier.height(Prism.Stack))
        PrismActions {
            if (!running) {
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
        }
        Spacer(Modifier.height(Prism.Stack))
        PrismActions {
            PrismButton(
                text = "Copy MCP config",
                accent = accent,
                icon = Icons.Default.ContentCopy,
                modifier = Modifier.fillMaxWidth(),
                onClick = { copy(context, MCP_CONFIG, "MCP config copied") }
            )
            PrismSecondaryButton(
                text = if (syncing) "Syncing CLI…" else "Sync guest CLI",
                accent = accent,
                modifier = Modifier.fillMaxWidth(),
                enabled = !syncing,
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
            Spacer(Modifier.height(Prism.Tight))
            PrismNote(syncNote!!)
        }
    }
}

@Composable
private fun SessionsCard(context: Context, accent: Color) {
    val scope = rememberCoroutineScope()
    val sessions by HeadlessCloneEngine.sessions.collectAsState()
    PrismSectionLabel("Running clones", accent)
    PrismCard(accent = accent) {
        PrismNote(
            "Every launch gets its own virtual display, so several AI agents can run " +
                "different clones at the same time. Screenshots and input take a package " +
                "name and act only on that clone."
        )
        Spacer(Modifier.height(Prism.Tight))
        val list = sessions.values.toList()
        if (list.isEmpty()) {
            PrismNote("No clone is running.")
        } else {
            list.forEachIndexed { index, session ->
                PrismRow(
                    title = session.packageName ?: session.key,
                    subtitle = "display ${session.displayId} · " +
                        "${session.width}×${session.height} · ${session.fps} fps",
                    accent = accent,
                    divider = index != list.lastIndex,
                    trailing = {
                        PrismChip("Stop", accent, onClick = {
                            scope.launch {
                                HeadlessCloneEngine.apiStop(session.packageName ?: session.key)
                            }
                        })
                    },
                )
            }
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
        PrismNote(
            "Checks the host and every installed proot/chroot distro for OpenCode, Pi, " +
                "Alpha, Hermes, Claude, Codex, Aider, Gemini and similar. Each distro gets " +
                "its own MCP button."
        )
        Spacer(Modifier.height(Prism.Stack))
        PrismActions {
            PrismButton(
                text = "Scan installed distros",
                accent = accent,
                icon = Icons.Default.Refresh,
                modifier = Modifier.fillMaxWidth(),
                onClick = { tick++ }
            )
            PrismSecondaryButton(
                text = "Copy agent brief",
                accent = accent,
                modifier = Modifier.fillMaxWidth(),
                onClick = { copy(context, AGENT_BRIEF, "Agent brief copied") }
            )
        }
        Spacer(Modifier.height(Prism.Stack))
        if (!scanned) {
            PrismNote("Scanning distro filesystems…")
        } else if (reports.isEmpty()) {
            PrismNote("No installed proot/chroot distros found.")
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
                    divider = index != reports.lastIndex,
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
                                bottom = 4.dp,
                            ),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }

    PrismSectionLabel("Host desktop agents", accent)
    PrismCard(accent = accent) {
        if (!scanned) {
            PrismNote("Scanning…")
        } else if (hostAgents.isEmpty()) {
            PrismNote("No desktop agent binaries found in the host prefix.")
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

    PrismSectionLabel("Agent brief", accent)
    PrismCard(accent = accent) {
        CodeBlock(AGENT_BRIEF)
        Spacer(Modifier.height(Prism.Tight))
        PrismSecondaryButton(
            text = "Copy brief",
            accent = accent,
            icon = Icons.Default.ContentCopy,
            modifier = Modifier.fillMaxWidth(),
            onClick = { copy(context, AGENT_BRIEF, "Agent brief copied") }
        )
    }
}

@Composable
private fun CliCard(context: Context, accent: Color) {
    PrismSectionLabel("Commands", accent)
    PrismCard(accent = accent) {
        PrismNote(
            "Installed as `altaris` (with a `bliss` compatibility alias) in the host and " +
                "every proot/chroot guest."
        )
        Spacer(Modifier.height(Prism.Tight))
        CodeBlock(CLI_HELP)
        Spacer(Modifier.height(Prism.Tight))
        PrismButton(
            text = "Copy commands",
            accent = accent,
            icon = Icons.Default.ContentCopy,
            modifier = Modifier.fillMaxWidth(),
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
            modifier = Modifier.fillMaxWidth(),
            onClick = { copy(context, MCP_CONFIG, "MCP config copied") }
        )
    }
    PrismSectionLabel("Tools", accent)
    PrismCard(accent = accent) {
        val tools = listOf(
            "altaris_status · sessions" to "Engine state and every running clone",
            "altaris_clones · apps" to "Installed clones and installed apps",
            "altaris_install_apk · uninstall_clone" to "Add or remove an APK clone",
            "altaris_launch_clone · stop_clone" to "Start or stop one clone, others keep running",
            "altaris_screenshot · ocr" to "See one display and read its text boxes",
            "altaris_tap · swipe · text · key" to "Drive one display",
            "altaris_logs" to "Diagnose crashes and display state",
        )
        tools.forEachIndexed { i, (t, d) ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(
                    t,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
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
        PrismNote(
            "`altaris_ocr` / GET /ocr captures one virtual display and returns text plus " +
                "boxes with centre coordinates. Pass package=<clone> to pick the display; " +
                "without it the viewer's clone is used. Use the centre with `altaris_tap`."
        )
        Spacer(Modifier.height(Prism.Tight))
        CodeBlock(
            "altaris shot /sdcard/Download/state.jpg --package com.altaris.apps.x\n" +
                "altaris ocr /sdcard/Download/state.json --package com.altaris.apps.x\n" +
                "altaris tap 540 1200 --package com.altaris.apps.x\n" +
                "altaris swipe 540 1600 540 500 280\n" +
                "altaris text hello\n" +
                "altaris key back"
        )
        Spacer(Modifier.height(Prism.Tight))
        PrismButton(
            text = "Copy examples",
            accent = accent,
            icon = Icons.Default.ContentCopy,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                copy(
                    context,
                    "altaris sessions\naltaris shot /sdcard/Download/state.jpg --package pkg\naltaris ocr --package pkg\naltaris tap 540 1200 --package pkg",
                    "Examples copied"
                )
            }
        )
    }
    PrismSectionLabel("Action feed", accent)
    PrismCard(accent = accent) {
        PrismNote(
            "The clone viewer shows an ACTIONS strip with the agent's taps, swipes, text, " +
                "keys and launches as they happen."
        )
    }
}

// ----------------------------------------------------------------------- rea

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

    PrismSectionLabel("Reverse engineering", accent)
    PrismCard(accent = accent) {
        PrismRow(
            title = "REA",
            subtitle = when {
                status.busy.isNotBlank() -> status.busy
                status.running -> "Server running · ${status.toolCount} tools"
                status.installed -> "Installed · ${status.reaVersion} · Node ${status.nodeVersion}"
                selected.isBlank() -> "Install a distro first"
                else -> "Not installed in $selected"
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
            Spacer(Modifier.height(Prism.Tight))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Prism.ControlGap),
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
        Spacer(Modifier.height(Prism.Stack))
        PrismActions {
            PrismButton(
                text = if (status.installed) "Reinstall REA core" else "Install REA core",
                accent = accent,
                icon = Icons.Default.PlayArrow,
                modifier = Modifier.fillMaxWidth(),
                enabled = selected.isNotBlank() && status.busy.isBlank(),
            ) {
                scope.launch {
                    ReaManager.install(context, selected)
                    tick++
                }
            }
            // Full-width, one per row: "Register in agents" is long enough that
            // two half-width buttons truncate their labels on a phone.
            PrismButton(
                text = if (status.running) "Stop server" else "Start server",
                accent = accent,
                modifier = Modifier.fillMaxWidth(),
                enabled = status.installed && status.busy.isBlank(),
            ) {
                scope.launch {
                    if (status.running) ReaManager.stop()
                    else ReaManager.start(context, selected)
                }
            }
            PrismSecondaryButton(
                text = "Register MCP in installed agents",
                accent = accent,
                modifier = Modifier.fillMaxWidth(),
                enabled = status.installed && status.busy.isBlank(),
            ) {
                scope.launch { ReaManager.registerAgents(context, selected) }
            }
        }
        if (status.error.isNotBlank()) {
            Spacer(Modifier.height(Prism.Tight))
            PrismNote(status.error, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ReaToolsCard(context: Context, accent: Color) {
    val scope = rememberCoroutineScope()
    val status by ReaManager.status.collectAsState()
    val manager = status.packageManager
    val busy = status.busy.isNotBlank()

    val installable = ReaCatalog.DEPENDENCIES.filter { dep ->
        dep.kind != "license" && dep.id !in status.available
    }

    PrismSectionLabel("Analysis tools", accent)
    PrismCard(accent = accent) {
        PrismNote(
            if (manager.isBlank()) {
                "Pick a distro above. Package manager not detected yet."
            } else {
                "Installed through $manager inside the distro. Each tool is installed on its " +
                    "own, so one unavailable package no longer blocks the others."
            }
        )
        Spacer(Modifier.height(Prism.Tight))
        ReaCatalog.DEPENDENCIES.forEach { dep ->
            val installed = dep.id in status.available
            val license = dep.kind == "license"
            val supported = manager.isBlank() ||
                dep.kind != "package" ||
                ReaCatalog.packagedFor(dep, manager)
            PrismRow(
                title = dep.label,
                subtitle = when {
                    license -> dep.note.ifBlank { "Needs a license; not bundled." }
                    installed && dep.kind == "ghidra" &&
                        !status.available.contains("ghidra-decompiler") ->
                        "Installed · ARM64 decompiler missing (function lists and strings work)"
                    installed -> "Installed"
                    !supported -> "Not packaged for $manager — install it manually"
                    else -> dep.description
                },
                accent = accent,
                divider = true,
                trailing = {
                    PrismChip(
                        when {
                            license -> "License"
                            installed -> "Installed"
                            !supported -> "N/A"
                            else -> "Install"
                        },
                        accent,
                        selected = installed,
                        onClick = when {
                            license || installed || busy || !supported -> null
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
        if (installable.isNotEmpty()) {
            Spacer(Modifier.height(Prism.Stack))
            PrismButton(
                text = "Install all missing tools",
                accent = accent,
                icon = Icons.Default.PlayArrow,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && status.distroId.isNotBlank(),
            ) {
                scope.launch {
                    val ids = installable
                        .filter { it.kind != "ghidra" }
                        .filter { manager.isBlank() || it.kind != "package" || ReaCatalog.packagedFor(it, manager) }
                        .map { it.id }
                    if (ids.isNotEmpty()) ReaManager.install(context, status.distroId, ids)
                }
            }
        }
        if (status.outcomes.isNotEmpty()) {
            Spacer(Modifier.height(Prism.Tight))
            status.outcomes.forEach { outcome ->
                val label = ReaCatalog.byId(outcome.id)?.label ?: outcome.id
                val detail = outcome.detail.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()
                PrismNote(
                    (if (outcome.ok) "Installed " else "Failed ") + label + detail,
                    color = if (outcome.ok) altarisMutedText() else MaterialTheme.colorScheme.error,
                )
            }
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
    val ghidraReady = status.available.contains("ghidra")

    PrismSectionLabel("Ghidra analysis", accent)
    PrismCard(accent = accent) {
        PrismRow(
            title = "Headless analyzer",
            subtitle = when {
                !ghidraReady -> "Install Ghidra from the list above, then analyze a binary here."
                status.available.contains("ghidra-decompiler") ->
                    "Ready, with the ARM64 decompiler. The first analysis of a file is slow."
                else -> "Ready for function lists and strings; the ARM64 decompiler is not built."
            },
            accent = accent,
            trailing = {
                PrismChip(
                    if (analyzing) "Running" else "Idle",
                    accent,
                    selected = analyzing,
                )
            },
        )
        Spacer(Modifier.height(Prism.Stack))
        OutlinedTextField(
            value = target,
            onValueChange = { target = it },
            label = { Text("Guest path (binary / APK / library)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Prism.Tight))
        PrismButton(
            text = "Analyze with Ghidra",
            accent = accent,
            icon = Icons.Default.Search,
            modifier = Modifier.fillMaxWidth(),
            enabled = target.isNotBlank() && ghidraReady && !analyzing,
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
            Spacer(Modifier.height(Prism.Tight))
            CodeBlock(output.takeLast(3000))
        }
    }
}

@Composable
private fun ReaLogCard(accent: Color) {
    val context = LocalContext.current
    val status by ReaManager.status.collectAsState()
    if (status.log.isBlank()) return

    PrismSectionLabel("Install log", accent)
    PrismCard(accent = accent) {
        CodeBlock(status.log.takeLast(4000))
        Spacer(Modifier.height(Prism.Tight))
        PrismSecondaryButton(
            text = "Copy log",
            accent = accent,
            icon = Icons.Default.ContentCopy,
            modifier = Modifier.fillMaxWidth(),
            onClick = { copy(context, status.log, "Install log copied") }
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
