package com.altaris.linux.ui.screens

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
import com.altaris.linux.core.control.AgentDetector
import com.altaris.linux.core.control.AltarisCliInstaller
import com.altaris.linux.core.control.CloneControlService
import com.altaris.linux.core.control.ControlServer
import com.altaris.linux.core.control.DetectedAgent
import com.altaris.linux.core.control.DistroAgentReport
import com.altaris.linux.ui.components.QCard
import com.altaris.linux.ui.components.QDivider
import com.altaris.linux.ui.components.QPrimaryButton
import com.altaris.linux.ui.components.QRow
import com.altaris.linux.ui.components.QScreenTitle
import com.altaris.linux.ui.components.QSecondaryButton
import com.altaris.linux.ui.components.QSectionHeader
import com.altaris.linux.ui.components.QStatusRow
import com.altaris.linux.ui.components.Quartz
import com.altaris.linux.ui.theme.altarisMutedText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private enum class ToolsPage { HOME, CLONE }

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
- The same operations are available in any distro terminal as `altaris ...` (or the legacy `altaris` alias).

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
        ToolsPage.HOME -> ToolsHome(onOpen = { page = ToolsPage.CLONE })
        ToolsPage.CLONE -> ToolsDetail(
            "Clone",
            "Server, MCP, CLI, desktop agents and OCR/input",
            { page = ToolsPage.HOME },
        ) {
            ControlCard(context, running, error) { running = ControlServer.isRunning() }
            AgentsCard(context)
            McpCard(context)
            CliCard(context)
            AutomationCard(context)
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
        QSectionHeader("Folder")
        QCard {
            CategoryRow(
                "Clone",
                "Server/API · MCP · CLI · desktop agent scan · OCR/input",
                Icons.Default.Build,
            ) { onOpen(ToolsPage.CLONE) }
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
                Text(subtitle, fontSize = 11.5.sp, color = altarisMutedText())
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
                Text(error ?: "Loopback only · 127.0.0.1:${ControlServer.PORT}", fontSize = 11.5.sp, color = altarisMutedText())
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
        QStatusRow("Guest CLI", "altaris · altaris alias")
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
                        runCatching { AltarisCliInstaller.installAll(context) }
                            .onSuccess { syncNote = "CLI synced" }
                            .onFailure { syncNote = it.message ?: "CLI sync failed" }
                        syncing = false
                    }.start()
                },
            )
        }
        if (syncNote != null) Text(syncNote!!, fontSize = 11.5.sp, color = altarisMutedText(), modifier = Modifier.padding(start = Quartz.CardPad, bottom = 10.dp))
    }
}

@Composable
private fun AgentsCard(context: Context) {
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

    QSectionHeader("AI agents by installed distro")
    QCard {
        Column(Modifier.padding(horizontal = Quartz.CardPad, vertical = 12.dp)) {
            Text(
                "Checks the host and every installed proot/chroot distro for OpenCode, Pi, Alpha, Hermes, Claude, Codex, Aider, Gemini and similar. Each distro gets its own MCP button.",
                fontSize = 12.sp,
                color = altarisMutedText(),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QPrimaryButton("Scan installed distros", icon = Icons.Default.Refresh) { tick++ }
                QSecondaryButton("Copy agent brief", icon = Icons.Default.ContentCopy) {
                    copy(context, AGENT_BRIEF, "Agent brief copied")
                }
            }
        }
        QDivider()
        if (!scanned) {
            Text("Scanning distro filesystems…", fontSize = 12.sp, color = altarisMutedText(), modifier = Modifier.padding(Quartz.CardPad))
        } else if (reports.isEmpty()) {
            Text("No installed proot/chroot distros found.", fontSize = 12.sp, color = altarisMutedText(), modifier = Modifier.padding(Quartz.CardPad))
        } else {
            reports.forEachIndexed { index, report ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Quartz.CardPad, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(report.name, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${report.method} · ${report.agents.size} agent${if (report.agents.size == 1) "" else "s"}",
                            fontSize = 11.sp,
                            color = altarisMutedText(),
                        )
                    }
                    QSecondaryButton("MCP", icon = Icons.Default.ContentCopy) {
                        copy(context, MCP_CONFIG, "${report.name} MCP config copied")
                    }
                }
                if (report.agents.isEmpty()) {
                    Text(
                        "No supported agent binary found",
                        fontSize = 10.5.sp,
                        color = altarisMutedText(),
                        modifier = Modifier.padding(start = Quartz.CardPad + 4.dp, end = Quartz.CardPad, bottom = 8.dp),
                    )
                } else {
                    report.agents.forEach { agent ->
                        Text(
                            "• ${agent.name}  ${agent.detail}",
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().padding(
                                start = Quartz.CardPad + 4.dp,
                                end = Quartz.CardPad,
                                bottom = 3.dp,
                            ),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
                if (index != reports.lastIndex) QDivider()
            }
        }
    }

    Spacer(Modifier.height(6.dp))
    QSectionHeader("Host desktop agents")
    QCard {
        if (!scanned) {
            Text("Scanning…", fontSize = 12.sp, color = altarisMutedText(), modifier = Modifier.padding(Quartz.CardPad))
        } else if (hostAgents.isEmpty()) {
            Text("No desktop agent binaries found in the host prefix.", fontSize = 12.sp, color = altarisMutedText(), modifier = Modifier.padding(Quartz.CardPad))
        } else {
            hostAgents.forEachIndexed { index, agent ->
                QStatusRow(
                    "${agent.name}  ·  ${agent.kind}",
                    agent.detail,
                    MaterialTheme.colorScheme.primary,
                )
                if (index != hostAgents.lastIndex) QDivider()
            }
        }
    }

    Spacer(Modifier.height(6.dp))
    QSectionHeader("Agent brief")
    QCard { CodeBlock(AGENT_BRIEF) }
}

@Composable
private fun CliCard(context: Context) {
    QSectionHeader("Commands")
    QCard {
        Text("Installed as `altaris` (with a `altaris` compatibility alias) in the host and every proot/chroot guest.", fontSize = 12.sp, color = altarisMutedText(), modifier = Modifier.padding(horizontal = Quartz.CardPad, vertical = 10.dp))
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
            "altaris_status · clones · apps" to "Observe engine, clones and installed apps",
            "altaris_install_apk · uninstall_clone" to "Add or remove an APK clone",
            "altaris_launch_clone · stop_clone" to "Control a background clone",
            "altaris_screenshot · ocr" to "See the display and read text boxes",
            "altaris_tap · swipe · text · key" to "Drive the virtual display",
            "altaris_logs" to "Diagnose crashes and display state",
        )
        tools.forEachIndexed { i, (t, d) ->
            Column(Modifier.fillMaxWidth().padding(horizontal = Quartz.CardPad, vertical = 9.dp)) {
                Text(t, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                Text(d, fontSize = 11.5.sp, color = altarisMutedText())
            }
            if (i != tools.lastIndex) QDivider()
        }
    }
}

@Composable
private fun AutomationCard(context: Context) {
    QSectionHeader("How an agent sees")
    QCard {
        Text("`altaris_ocr` / GET /ocr captures the virtual display and returns text plus boxes with centre coordinates. Use the centre with `altaris_tap`.", fontSize = 12.sp, color = altarisMutedText(), modifier = Modifier.padding(horizontal = Quartz.CardPad, vertical = 12.dp))
        CodeBlock("altaris shot /sdcard/Download/state.jpg\naltaris ocr /sdcard/Download/state.json\naltaris tap 540 1200\naltaris swipe 540 1600 540 500 280\naltaris text hello\naltaris key back")
        Row(Modifier.padding(horizontal = Quartz.CardPad, vertical = 10.dp)) {
            QSecondaryButton("Copy examples", icon = Icons.Default.ContentCopy) { copy(context, "altaris shot /sdcard/Download/state.jpg\naltaris ocr\naltaris tap 540 1200", "Examples copied") }
        }
    }
    Spacer(Modifier.height(8.dp))
    QSectionHeader("Action feed")
    QCard {
        Text("The clone viewer shows an ACTIONS strip with the agent's taps, swipes, text, keys and launches as they happen.", fontSize = 12.sp, color = altarisMutedText(), modifier = Modifier.padding(Quartz.CardPad))
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
    clipboard.setPrimaryClip(ClipData.newPlainText("Altaris", text))
    Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
}
