package com.ivarna.fluxlinux.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.ivarna.fluxlinux.core.control.BlissCliInstaller
import com.ivarna.fluxlinux.core.control.ControlServer
import com.ivarna.fluxlinux.ui.components.QCard
import com.ivarna.fluxlinux.ui.components.QDivider
import com.ivarna.fluxlinux.ui.components.QPrimaryButton
import com.ivarna.fluxlinux.ui.components.QScreenTitle
import com.ivarna.fluxlinux.ui.components.QSecondaryButton
import com.ivarna.fluxlinux.ui.components.QSectionHeader
import com.ivarna.fluxlinux.ui.components.QStatusRow
import com.ivarna.fluxlinux.ui.components.Quartz
import com.ivarna.fluxlinux.ui.components.fluxMutedText
import kotlinx.coroutines.delay

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

/**
 * Automation console. The clone controls themselves live in Apps; this page
 * exposes the machine interface (local HTTP, MCP, guest CLI, OCR/input).
 */
@Composable
fun ToolsScreen() {
    val context = LocalContext.current
    var running by remember { mutableStateOf(ControlServer.isRunning()) }
    var error by remember { mutableStateOf(ControlServer.error()) }
    var syncing by remember { mutableStateOf(false) }
    var syncNote by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            running = ControlServer.isRunning()
            error = ControlServer.error()
            delay(1800)
        }
    }

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Quartz.ScreenPad)
            .padding(top = 14.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        QScreenTitle(
            "Tools",
            "Local control for clones, distros and AI agents. Everything stays on this device."
        )
        Spacer(Modifier.height(8.dp))

        QSectionHeader("Control plane")
        QCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Quartz.CardPad, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (running) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                )
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (running) "Control server online" else "Control server offline",
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        error ?: "Loopback only · 127.0.0.1:${ControlServer.PORT}",
                        fontSize = 11.5.sp,
                        color = fluxMutedText(),
                        maxLines = 2
                    )
                }
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
                QPrimaryButton(
                    "Copy MCP config",
                    icon = Icons.Default.ContentCopy,
                    onClick = { copy(context, MCP_CONFIG, "MCP config copied") }
                )
                QSecondaryButton(
                    text = if (syncing) "Syncing…" else "Sync CLI",
                    icon = Icons.Default.Refresh,
                    enabled = !syncing,
                    onClick = {
                        syncing = true
                        syncNote = null
                        Thread {
                            runCatching { BlissCliInstaller.installAll(context) }
                                .onSuccess { syncNote = "CLI synced to host and installed distros" }
                                .onFailure { syncNote = it.message ?: "CLI sync failed" }
                            syncing = false
                        }.start()
                    }
                )
            }
            if (syncNote != null) {
                Text(
                    syncNote!!,
                    fontSize = 11.5.sp,
                    color = fluxMutedText(),
                    modifier = Modifier.padding(start = Quartz.CardPad, end = Quartz.CardPad, bottom = 12.dp)
                )
            }
        }

        QSectionHeader("In every distro")
        QCard {
            Text(
                "Open a terminal in any installed distro and run:",
                fontSize = 12.5.sp,
                color = fluxMutedText(),
                modifier = Modifier.padding(horizontal = Quartz.CardPad, vertical = 12.dp)
            )
            CodeBlock(CLI_HELP)
            Row(
                modifier = Modifier.padding(horizontal = Quartz.CardPad, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QSecondaryButton(
                    "Copy commands",
                    icon = Icons.Default.ContentCopy,
                    onClick = { copy(context, CLI_HELP, "CLI commands copied") }
                )
            }
        }

        QSectionHeader("MCP tools")
        QCard {
            val tools = listOf(
                "bliss_status · clones · logs" to "Observe engine and app state",
                "bliss_install_apk · uninstall_clone" to "Stage or remove an APK clone",
                "bliss_launch_clone · stop_clone" to "Control a background clone",
                "bliss_screenshot · ocr" to "See the display and read text",
                "bliss_tap · swipe · text · key" to "Drive the virtual display",
                "bliss_record_start · stop" to "Capture an MP4 of the display",
            )
            tools.forEachIndexed { index, (title, subtitle) ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Quartz.CardPad, vertical = 10.dp)
                ) {
                    Text(
                        title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(subtitle, fontSize = 11.5.sp, color = fluxMutedText())
                }
                if (index != tools.lastIndex) QDivider()
            }
        }

        QSectionHeader("Agent config")
        QCard {
            Text(
                "Point an MCP client at the local endpoint. The server is only bound to loopback.",
                fontSize = 12.5.sp,
                color = fluxMutedText(),
                modifier = Modifier.padding(horizontal = Quartz.CardPad, vertical = 12.dp)
            )
            CodeBlock(MCP_CONFIG)
            Row(
                modifier = Modifier.padding(horizontal = Quartz.CardPad, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QSecondaryButton(
                    "Copy config",
                    icon = Icons.Default.ContentCopy,
                    onClick = { copy(context, MCP_CONFIG, "MCP config copied") }
                )
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
                .padding(horizontal = 12.dp, vertical = 10.dp)
        )
    }
}

private fun copy(context: Context, text: String, toast: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Bliss", text))
    Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
}
