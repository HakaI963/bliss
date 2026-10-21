package com.ivarna.fluxlinux.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivarna.fluxlinux.core.control.AgentDetector
import com.ivarna.fluxlinux.core.control.BlissCliInstaller
import com.ivarna.fluxlinux.core.control.CloneControlService
import com.ivarna.fluxlinux.core.control.CloneRecorder
import com.ivarna.fluxlinux.core.control.ControlServer
import com.ivarna.fluxlinux.core.control.DetectedAgent
import com.ivarna.fluxlinux.ui.components.QCard
import com.ivarna.fluxlinux.ui.components.QDivider
import com.ivarna.fluxlinux.ui.components.QPrimaryButton
import com.ivarna.fluxlinux.ui.components.QRow
import com.ivarna.fluxlinux.ui.components.QScreenTitle
import com.ivarna.fluxlinux.ui.components.QSecondaryButton
import com.ivarna.fluxlinux.ui.components.QSectionHeader
import com.ivarna.fluxlinux.ui.components.QStatusRow
import com.ivarna.fluxlinux.ui.components.Quartz
import com.ivarna.fluxlinux.ui.theme.fluxMutedText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private enum class ToolsPage { HOME, CONTROL, AGENTS, CLI, MCP, AUTOMATION, RECORDING }

private const val MCP_CONFIG = """{
  "mcp": {
    "bliss": {
      "type": "remote",
      "url": "http://127.0.0.1:47831/mcp",
      "enabled": true
    }
  }
}"""

private const val CLI_HELP = """bliss status
bliss clones
bliss apps
bliss list
bliss ps
bliss install /sdcard/Download/app.apk [label]
bliss install https://example.com/app.apk [label]
bliss uninstall <clone-package>
bliss launch <clone-package> [width height fps]
bliss stop <clone-package>
bliss shot [out.jpg]
bliss logs <clone-package> [lines]
bliss ocr
bliss tap <x> <y>
bliss swipe <x1> <y1> <x2> <y2> [ms]
bliss text <text...>
bliss key <back|home|enter|tab|esc|recents|keycode>
bliss record start [fps] [width]
bliss record stop
bliss record status"""

private const val AGENT_BRIEF = """You are controlling Bliss clones through MCP.
Endpoint: http://127.0.0.1:47831/mcp

Protocol:
1. initialize
2. tools/list
3. tools/call

Rules:
- Call bliss_status and bliss_clones first.
- Use bliss_install_apk with path=/sdcard/Download/app.apk or a URL.
- Use bliss_launch_clone once; then use bliss_screenshot/bliss_ocr to see.
- Input is in virtual-display pixels: bliss_tap, bliss_swipe, bliss_text, bliss_key.
- Use bliss_logs after a crash; use bliss_stop_clone when done.
- Screenshots and OCR are available even when the viewer is not open.
- The same operations are available in any distro terminal as `bliss ...`.

If a task needs visual confirmation, take a screenshot after every action group.
The UI action feed in the clone viewer shows your taps, swipes, text and keys."""

@Composable
fun ToolsScreen() {
    val context = LocalContext.current
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
        ToolsPage.HOME -> ToolsHome(onOpen = { page = it })
        ToolsPage.CONTROL -> ToolsDetail("Control server", "HTTP, CLI and MCP endpoints", page, { page = ToolsPage.HOME }) {
            ControlCard(context, running, error) { running = ControlServer.isRunning() }
        }
        ToolsPage.AGENTS -> ToolsDetail("AI agents", "Detect installed runtimes and copy the MCP brief", page, { page = ToolsPage.HOME }) {
            AgentsCard(context)
        }
        ToolsPage.CLI -> ToolsDetail("Clone CLI", "Commands installed in the host and every distro", page, { page = ToolsPage.HOME }) {
            CliCard(context)
        }
        ToolsPage.MCP -> ToolsDetail("MCP tools", "What an agent can do", page, { page = ToolsPage.HOME }) {
            McpCard(context)
        }
        ToolsPage.AUTOMATION -> ToolsDetail("OCR & input", "See the display, then tap what it sees", page, { page = ToolsPage.HOME }) {
            AutomationCard(context)
        }
        ToolsPage.RECORDING -> ToolsDetail("Recording", "Capture the virtual display to MP4", page, { page = ToolsPage.HOME }) {
            RecordingCard()
        }
    }
}

@Composable
private fun ToolsHome(onOpen: (ToolsPage) -> Unit) {
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Quartz.ScreenPad)
            .padding(top = 14.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        QScreenTitle("Tools", "Folders for clone automation, agents and local control.")
        Spacer(Modifier.height(8.dp))
        QSectionHeader("Folders")
        QCard {
            CategoryRow("Control server", "Server status, endpoints, MCP config", Icons.Default.Settings) { onOpen(ToolsPage.CONTROL) }
            QDivider()
            CategoryRow("AI agents", "Auto-detect installed agents + agent brief", Icons.Default.Android) { onOpen(ToolsPage.AGENTS) }
            QDivider()
            CategoryRow("Clone CLI", "bliss commands for every distro", Icons.Default.Terminal) { onOpen(ToolsPage.CLI) }
            QDivider()
            CategoryRow("MCP tools", "Tool names, schemas and results", Icons.Default.Build) { onOpen(ToolsPage.MCP) }
            QDivider()
            CategoryRow("OCR & input", "Screenshots, OCR boxes, taps and swipes", Icons.Default.List) { onOpen(ToolsPage.AUTOMATION) }
            QDivider()
            CategoryRow("Recording", "MP4 capture of the virtual display", Icons.Default.PlayArrow) { onOpen(ToolsPage.RECORDING) }
        }
    }
}

@Composable
private fun CategoryRow(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    QRow(
        title = title,
        subtitle = subtitle,
        leading = {
            Box(
                Modifier.size(36.dp).clip(Quartz.TileShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
        },
        onClick = onClick,
    )
}

@Composable
private fun ToolsDetail(
    title: String,
    subtitle: String,
    page: ToolsPage,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Quartz.ScreenPad)
            .padding(top = 8.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                Text(subtitle, fontSize = 11.5.sp, color = fluxMutedText())
            }
        }
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun ControlCard(context: Context, running: Boolean, error: String?, onStarted: () -> Unit) {
    var syncing by remember { mutableStateOf(false) }
    var syncNote by remember { mutableStateOf<String?>(null) }
    QSectionHeader("Control plane")
    QCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Quartz.CardPad, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(9.dp).clip(RoundedCornerShape(50))
                    .background(if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            )
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (running) "Control server online" else "Control server offline",
                    fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold,
                )
                Text(error ?: "Loopback only · 127.0.0.1:${ControlServer.PORT}", fontSize = 11.5.sp, color = fluxMutedText())
            }
        }
        if (!running) {
            QPrimaryButton(
                "Start control server", icon = Icons.Default.PlayArrow,
                onClick = {
                    runCatching { CloneControlService.start(context) }
                    runCatching { ControlServer.start(context) }
                    onStarted()
                },
                modifier = Modifier.padding(horizontal = Quartz.CardPad, vertical = 4.dp),
            )
        }
        QDivider()
        QStatusRow("HTTP API", ControlServer.BASE_URL)
        QStatusRow("MCP", "${ControlServer.BASE_URL}/mcp")
        QStatusRow("Guest CLI", "bliss")
        QDivider()
        Row(
            modifier = Modifier.padding(horizontal = Quartz.CardPad, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QPrimaryButton("Copy MCP config", icon = Icons.Default.ContentCopy) { copy(context, MCP_CONFIG, "MCP config copied") }
            QSecondaryButton(
                text = if (syncing) "Syncing…" else "Sync CLI",
                icon = Icons.Default.Refresh, enabled = !syncing,
                onClick = {
                    syncing = true
                    Thread {
                        runCatching { BlissCliInstaller.installAll(context) }
                            .onSuccess { syncNote = "CLI synced" }
                            .onFailure { syncNote = it.message ?: "CLI sync failed" }
                        syncing = false
                    }.start()
                },
            )
        }
        if (syncNote != null) Text(syncNote!!, fontSize = 11.5.sp, color = fluxMutedText(), modifier = Modifier.padding(start = Quartz.CardPad, bottom = 10.dp))
    }
}

@Composable
private fun AgentsCard(context: Context) {
    var tick by remember { mutableIntStateOf(0) }
    var agents by remember { mutableStateOf<List<DetectedAgent>>(emptyList()) }
    var scanned by remember { mutableStateOf(false) }
    LaunchedEffect(tick) {
        agents = withContext(Dispatchers.IO) { AgentDetector.scan(context) }
        scanned = true
    }
    QSectionHeader("Auto-detect")
    QCard {
        Column(Modifier.padding(horizontal = Quartz.CardPad, vertical = 12.dp)) {
            Text("Scans Android packages and host binaries for OpenCode, Pi, Alpha, Hermes, Claude, Codex, Aider, Gemini and similar runtimes.", fontSize = 12.sp, color = fluxMutedText())
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QPrimaryButton("Detect agents", icon = Icons.Default.Refresh) { tick++ }
                QSecondaryButton("Copy agent brief", icon = Icons.Default.ContentCopy) { copy(context, AGENT_BRIEF, "Agent brief copied") }
            }
        }
        QDivider()
        if (!scanned) {
            Text("Scanning…", fontSize = 12.sp, color = fluxMutedText(), modifier = Modifier.padding(Quartz.CardPad))
        } else if (agents.isEmpty()) {
            Text("No agent apps or host binaries found. You can still connect any MCP client to the endpoint.", fontSize = 12.sp, color = fluxMutedText(), modifier = Modifier.padding(Quartz.CardPad))
        } else {
            agents.forEachIndexed { index, agent ->
                QStatusRow(
                    "${agent.name}  ·  ${agent.kind}",
                    agent.detail,
                    MaterialTheme.colorScheme.primary,
                )
                if (index != agents.lastIndex) QDivider()
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    QSectionHeader("Agent brief")
    QCard { CodeBlock(AGENT_BRIEF) }
}

@Composable
private fun CliCard(context: Context) {
    QSectionHeader("Commands")
    QCard {
        Text("Installed as `bliss` in the host and every proot/chroot guest.", fontSize = 12.sp, color = fluxMutedText(), modifier = Modifier.padding(horizontal = Quartz.CardPad, vertical = 10.dp))
        CodeBlock(CLI_HELP)
        Row(Modifier.padding(horizontal = Quartz.CardPad, vertical = 10.dp)) {
            QSecondaryButton("Copy commands", icon = Icons.Default.ContentCopy) { copy(context, CLI_HELP, "CLI copied") }
        }
    }
}

@Composable
private fun McpCard(context: Context) {
    QSectionHeader("MCP endpoint")
    QCard {
        QStatusRow("URL", "${ControlServer.BASE_URL}/mcp")
        QStatusRow("Transport", "JSON-RPC 2.0 over HTTP")
        QDivider()
        CodeBlock(MCP_CONFIG)
        Row(Modifier.padding(horizontal = Quartz.CardPad, vertical = 10.dp)) {
            QSecondaryButton("Copy config", icon = Icons.Default.ContentCopy) { copy(context, MCP_CONFIG, "MCP config copied") }
        }
    }
    Spacer(Modifier.height(8.dp))
    QSectionHeader("Tools")
    QCard {
        val tools = listOf(
            "bliss_status · clones · apps" to "Observe engine, clones and installed apps",
            "bliss_install_apk · uninstall_clone" to "Add or remove an APK clone",
            "bliss_launch_clone · stop_clone" to "Control a background clone",
            "bliss_screenshot · ocr" to "See the display and read text boxes",
            "bliss_tap · swipe · text · key" to "Drive the virtual display",
            "bliss_logs · record_start · record_stop" to "Diagnose and capture",
        )
        tools.forEachIndexed { i, (t, d) ->
            Column(Modifier.fillMaxWidth().padding(horizontal = Quartz.CardPad, vertical = 9.dp)) {
                Text(t, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                Text(d, fontSize = 11.5.sp, color = fluxMutedText())
            }
            if (i != tools.lastIndex) QDivider()
        }
    }
}

@Composable
private fun AutomationCard(context: Context) {
    QSectionHeader("How an agent sees")
    QCard {
        Text("`bliss_ocr` / GET /ocr captures the virtual display and returns text plus boxes with centre coordinates. Use the centre with `bliss_tap`.", fontSize = 12.sp, color = fluxMutedText(), modifier = Modifier.padding(horizontal = Quartz.CardPad, vertical = 12.dp))
        CodeBlock("bliss shot /sdcard/Download/state.jpg\nbliss ocr /sdcard/Download/state.json\nbliss tap 540 1200\nbliss swipe 540 1600 540 500 280\nbliss text hello\nbliss key back")
        Row(Modifier.padding(horizontal = Quartz.CardPad, vertical = 10.dp)) {
            QSecondaryButton("Copy examples", icon = Icons.Default.ContentCopy) { copy(context, "bliss shot /sdcard/Download/state.jpg\nbliss ocr\nbliss tap 540 1200", "Examples copied") }
        }
    }
    Spacer(Modifier.height(8.dp))
    QSectionHeader("Action feed")
    QCard {
        Text("The clone viewer shows an ACTIONS strip with the agent's taps, swipes, text, keys and launches as they happen.", fontSize = 12.sp, color = fluxMutedText(), modifier = Modifier.padding(Quartz.CardPad))
    }
}

@Composable
private fun RecordingCard() {
    var status by remember { mutableStateOf(CloneRecorder.status()) }
    LaunchedEffect(Unit) {
        while (true) { status = CloneRecorder.status(); delay(900) }
    }
    val recording = status.optBoolean("recording")
    QSectionHeader("MP4 capture")
    QCard {
        QStatusRow("State", if (recording) "recording" else "idle", if (recording) MaterialTheme.colorScheme.primary else null)
        QStatusRow("Frames", status.optInt("frames").toString())
        QStatusRow("Path", status.optString("path").ifBlank { "—" })
        if (status.optString("error").isNotBlank()) QStatusRow("Error", status.optString("error"), MaterialTheme.colorScheme.error)
        Row(Modifier.padding(horizontal = Quartz.CardPad, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QPrimaryButton("Start recording", icon = Icons.Default.PlayArrow, enabled = !recording) {
                val ctx = ControlServer.context()
                if (ctx != null) {
                    CloneRecorder.start(ctx, 6, 720)
                    status = CloneRecorder.status()
                }
            }
            QSecondaryButton("Stop", enabled = recording) {
                CloneRecorder.stop(); status = CloneRecorder.status()
            }
        }
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
                .padding(horizontal = Quartz.CardPad)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.72f))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

private fun copy(context: Context, text: String, toast: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Bliss", text))
    Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
}
