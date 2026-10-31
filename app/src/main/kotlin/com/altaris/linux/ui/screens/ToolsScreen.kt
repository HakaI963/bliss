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
import com.altaris.linux.core.control.AltarisCliInstaller
import com.altaris.linux.core.control.CloneControlService
import com.altaris.linux.core.control.ControlServer
import com.altaris.linux.core.rea.ReaCatalog
import com.altaris.linux.core.rea.ReaDistro
import com.altaris.linux.core.rea.ReaBridge
import com.altaris.linux.core.rea.ReaManager
import com.altaris.linux.core.rea.ReaToolInstaller
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
            ReaMcpCard(context, accent)
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
    val state by ReaDistro.state.collectAsState()
    var method by remember { mutableStateOf(ReaDistro.Method.PROOT) }

    // Re-read the truth while an install runs (and once when it ends), so the
    // chip flips to Installed without leaving the screen. The previous version
    // read the state once and stayed on "Install" after a successful install.
    LaunchedEffect(state.busy.isNotBlank()) {
        while (true) {
            withContext(Dispatchers.IO) { ReaDistro.refresh(context) }
            if (state.busy.isBlank()) break
            kotlinx.coroutines.delay(1500L)
        }
    }

    PrismSectionLabel("Reverse engineering", accent)
    PrismCard(accent = accent) {
        PrismRow(
            title = "REA analysis environment",
            subtitle = when {
                state.busy.isNotBlank() -> state.busy
                state.installed && state.ready ->
                    "Installed (" + (state.method?.label ?: "proot") + ") · served over MCP"
                state.installed -> "Container present · REA core missing, tap Reinstall"
                else -> "Not installed"
            },
            accent = accent,
            leading = { PrismOrb(active = state.installed && state.ready, accent = accent) },
            trailing = {
                PrismChip(
                    when {
                        state.busy.isNotBlank() && state.percent >= 0 -> "${state.percent}%"
                        state.installed && state.ready -> "Installed"
                        state.installed -> "Incomplete"
                        else -> "Missing"
                    },
                    accent,
                    selected = state.installed,
                )
            },
        )

        // Live progress: the install is a multi-GB download, so it must never
        // look stuck.
        if (state.busy.isNotBlank()) {
            Spacer(Modifier.height(Prism.Tight))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(state.fraction.coerceIn(0f, 1f))
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(accent)
                )
            }
            if (state.downloadText.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                PrismNote(state.downloadText)
            }
            if (state.detail.isNotBlank()) {
                PrismNote(state.detail, fontSize = 10.sp)
            }
        }

        Spacer(Modifier.height(Prism.Tight))
        PrismNote(
            "REA runs inside its own private distro that Altaris creates here, so it " +
                "never touches your distros. Agents reach it over MCP. Every analysis " +
                "tool is installed with it. Pick how it runs:"
        )
        Spacer(Modifier.height(Prism.Tight))
        Row(horizontalArrangement = Arrangement.spacedBy(Prism.ControlGap)) {
            PrismChip(
                "proot · no root",
                accent,
                selected = method == ReaDistro.Method.PROOT,
                onClick = { method = ReaDistro.Method.PROOT },
            )
            PrismChip(
                "chroot · needs root",
                accent,
                selected = method == ReaDistro.Method.CHROOT,
                onClick = { method = ReaDistro.Method.CHROOT },
            )
        }
        if (method == ReaDistro.Method.CHROOT && !state.chrootPossible) {
            Spacer(Modifier.height(Prism.Tight))
            PrismNote(
                "Root is not available, so chroot cannot be used. proot works without it.",
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(Prism.Stack))
        PrismActions {
            PrismButton(
                text = when {
                    state.busy.isNotBlank() -> "Installing…"
                    state.installed -> "Reinstall analysis environment"
                    else -> "Install analysis environment"
                },
                accent = accent,
                icon = Icons.Default.PlayArrow,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.busy.isBlank(),
            ) {
                scope.launch { ReaDistro.install(context, method) }
            }
            if (state.installed) {
                PrismSecondaryButton(
                    text = "Install missing tools",
                    accent = accent,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.busy.isBlank(),
                ) {
                    scope.launch { ReaDistro.installMissingTools(context) }
                }
                PrismSecondaryButton(
                    text = "Remove analysis environment",
                    accent = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.busy.isBlank(),
                ) {
                    scope.launch { ReaDistro.uninstall(context) }
                }
            }
        }
        if (state.error.isNotBlank()) {
            Spacer(Modifier.height(Prism.Tight))
            PrismNote(state.error, color = MaterialTheme.colorScheme.error)
        }
        if (state.log.isNotBlank()) {
            Spacer(Modifier.height(Prism.Tight))
            Text(
                "Install log",
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = altarisMutedText(),
            )
            Spacer(Modifier.height(3.dp))
            CodeBlock(state.log.takeLast(3000))
        }
    }
}

/**
 * REA as an MCP server.
 *
 * Altaris hosts the server and proxies REA's stdio process, so an agent inside a
 * distro connects by URL and the app sees every call it makes. The endpoint and
 * the copy-paste config are shown here; nothing is written into an agent's
 * config automatically.
 */
@Composable
private fun ReaMcpCard(context: Context, accent: Color) {
    val scope = rememberCoroutineScope()
    val bridge by ReaBridge.state.collectAsState()
    val distro by ReaDistro.state.collectAsState()
    var copied by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val config = remember { ReaBridge.configJson() }

    PrismSectionLabel("MCP server", accent)
    PrismCard(accent = accent) {
        PrismRow(
            title = "REA endpoint",
            subtitle = if (bridge.running) {
                bridge.toolCount.toString() + " tools · " + bridge.connections +
                    " agent connections"
            } else {
                "Stopped. Start it to let an agent connect."
            },
            accent = accent,
            leading = { PrismOrb(active = bridge.running, accent = accent) },
            trailing = {
                PrismChip(
                    if (bridge.running) "Live" else "Stopped",
                    accent,
                    selected = bridge.running,
                )
            },
        )
        Spacer(Modifier.height(Prism.Tight))
        Text(
            ReaBridge.ENDPOINT,
            fontSize = 10.5.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            color = accent,
        )
        Spacer(Modifier.height(Prism.Tight))
        PrismNote(
            "REA has no network endpoint of its own: Altaris runs it and proxies it " +
                "here, so an agent inside any distro can reach it at this URL and the REA " +
                "tab can show what it is doing. Add this to the agent's MCP config:"
        )
        Spacer(Modifier.height(Prism.Tight))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Prism.PanelRadius))
                .background(MaterialTheme.colorScheme.background)
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outline,
                    RoundedCornerShape(Prism.PanelRadius),
                )
                .padding(Prism.Tight),
        ) {
            Text(
                config,
                fontSize = 9.5.sp,
                lineHeight = 13.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(Prism.ControlGap))
        PrismActions {
            PrismSecondaryButton(
                text = if (copied) "Copied" else "Copy config",
                accent = accent,
                modifier = Modifier.fillMaxWidth(),
            ) {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(config))
                copied = true
            }
            PrismButton(
                text = if (bridge.running) "Stop server" else "Start server",
                accent = accent,
                icon = Icons.Default.PlayArrow,
                modifier = Modifier.fillMaxWidth(),
                enabled = distro.installed,
            ) {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        if (bridge.running) {
                            ReaBridge.stop()
                        } else {
                            ReaDistro.distroId(context)?.let { ReaBridge.start(context, it) }
                        }
                    }
                }
            }
        }
        if (bridge.error.isNotBlank()) {
            Spacer(Modifier.height(Prism.Tight))
            PrismNote(bridge.error, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ReaToolsCard(context: Context, accent: Color) {
    val scope = rememberCoroutineScope()
    val status by ReaManager.status.collectAsState()
    val manager = status.packageManager
    val busy = status.busy.isNotBlank()
    // Tools live in the hidden REA distro, not in a distro the user picks.
    val reaDistro = ReaDistro.state.collectAsState().value
    val toolInstall by ReaToolInstaller.state.collectAsState()

    val installable = ReaCatalog.DEPENDENCIES.filter { dep ->
        dep.kind != "license" && dep.id !in status.available
    }

    PrismSectionLabel("Analysis tools", accent)
    PrismCard(accent = accent) {
        PrismNote(
            when {
                !reaDistro.installed ->
                    "Install the analysis environment above first; the tools go into it."
                manager.isBlank() ->
                    "Detecting the package manager in the analysis environment…"
                else ->
                    "Installed through $manager inside the REA environment. Each tool is " +
                        "installed on its own, so one unavailable package never blocks the others."
            }
        )
        Spacer(Modifier.height(Prism.Tight))
        ReaCatalog.DEPENDENCIES.forEach { dep ->
            val installed = dep.id in status.available
            val license = dep.kind == "license"
            val supported = manager.isBlank() ||
                dep.kind != "package" ||
                ReaCatalog.packagedFor(dep, manager)
            // No package on this distro, but a release archive exists: the
            // install still works, it just downloads from upstream.
            val archive = manager.isNotBlank() && ReaCatalog.usesArchive(dep, manager)
            PrismRow(
                title = dep.label,
                subtitle = when {
                    license -> dep.note.ifBlank { "Needs a license; not bundled." }
                    installed && dep.kind == "ghidra" &&
                        !status.available.contains("ghidra-decompiler") ->
                        "Installed · ARM64 decompiler missing (function lists and strings work)"
                    installed -> "Installed"
                    !supported -> "Not packaged for $manager — install it manually"
                    archive -> "Not in $manager — installs from the official release"
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
                            archive -> "Download"
                            else -> "Install"
                        },
                        accent,
                        selected = installed,
                        onClick = when {
                            license || installed || busy || !supported -> null
                            else -> ({
                                val target = reaDistro.method?.id ?: status.distroId
                                scope.launch {
                                    ReaManager.install(context, target, listOf(dep.id))
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
                text = if (toolInstall.running) "Installing…" else "Install all missing tools",
                accent = accent,
                icon = Icons.Default.PlayArrow,
                modifier = Modifier.fillMaxWidth(),
                enabled = !toolInstall.running && reaDistro.installed,
            ) {
                scope.launch {
                    val ids = installable
                        .filter { manager.isBlank() || it.kind != "package" || ReaCatalog.packagedFor(it, manager) }
                        .map { it.id }
                    val target = reaDistro.method?.id ?: status.distroId
                    if (ids.isNotEmpty()) ReaToolInstaller.install(context, target, ids)
                }
            }
        }

        // One bar per tool, so a long Ghidra download never hides the rest.
        if (toolInstall.tools.isNotEmpty()) {
            Spacer(Modifier.height(Prism.Tight))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Progress", fontSize = 10.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                PrismNote(toolInstall.summary)
            }
            toolInstall.tools.forEach { tool ->
                ToolProgressRow(tool, accent)
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

/**
 * One tool's install progress: a bar plus its state, so a parallel install of
 * eighteen tools is readable at a glance instead of one scrolling log.
 */
@Composable
private fun ToolProgressRow(tool: ReaToolInstaller.ToolState, accent: Color) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            tool.label,
            fontSize = 9.5.sp,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.width(84.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(scheme.outline.copy(alpha = 0.35f))
        ) {
            val fraction = when (tool.status) {
                ReaToolInstaller.ToolState.Status.DONE -> 1f
                ReaToolInstaller.ToolState.Status.FAILED -> 1f
                else -> if (tool.percent >= 0) tool.percent / 100f else 0f
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(4.dp)
                    .background(
                        when (tool.status) {
                            ReaToolInstaller.ToolState.Status.FAILED -> scheme.error
                            ReaToolInstaller.ToolState.Status.DONE -> accent.copy(alpha = 0.55f)
                            else -> accent
                        }
                    )
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            when (tool.status) {
                ReaToolInstaller.ToolState.Status.QUEUED -> "queued"
                ReaToolInstaller.ToolState.Status.RUNNING ->
                    if (tool.percent >= 0) tool.percent.toString() + "%" else "…"
                ReaToolInstaller.ToolState.Status.DONE -> "done"
                ReaToolInstaller.ToolState.Status.FAILED -> "failed"
                ReaToolInstaller.ToolState.Status.SKIPPED -> "skipped"
            },
            fontSize = 8.5.sp,
            color = when (tool.status) {
                ReaToolInstaller.ToolState.Status.FAILED -> scheme.error
                ReaToolInstaller.ToolState.Status.DONE -> altarisMutedText()
                else -> accent
            },
            modifier = Modifier.width(40.dp),
        )
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
    val reaDistro = ReaDistro.state.collectAsState().value

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
            enabled = target.isNotBlank() && ghidraReady && !analyzing && reaDistro.installed,
        ) {
            analyzing = true
            output = ""
            scope.launch {
                try {
                    val distro = reaDistro.method?.id ?: status.distroId
                    val res = ReaManager.analyzeGhidra(context, distro, target, 300)
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
