package com.ivarna.fluxlinux.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivarna.fluxlinux.core.clone.CloneEngine
import com.ivarna.fluxlinux.ui.components.GlassSettingCard
import com.ivarna.fluxlinux.ui.theme.fluxMutedText
import com.ivarna.fluxlinux.ui.theme.rememberStaggerEntrance
import com.ivarna.fluxlinux.ui.theme.staggerIn
import top.niunaijun.blackbox.BlackBoxCore

/**
 * Tools tab: hub for Bliss-only extras. The clone space lives in its own Apps
 * dock tab (one tap away); this screen keeps the agent/engine overview.
 */
@Composable
fun ToolsScreen(onOpenApps: () -> Unit) {
    val entrance = rememberStaggerEntrance()
    val logs by CloneEngine.logs.collectAsState()
    val backend by CloneEngine.touchBackend.collectAsState()
    val running by CloneEngine.running.collectAsState()
    val dw by CloneEngine.displayW.collectAsState()
    val dh by CloneEngine.displayH.collectAsState()
    val engineReady = remember(running) {
        runCatching { BlackBoxCore.get().areServicesAvailable() }.getOrDefault(false)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.staggerIn(0, entrance)) {
            Text(
                "Tools",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Extras that run entirely inside Bliss.",
                fontSize = 13.sp,
                color = fluxMutedText(),
            )
        }

        GlassSettingCard(
            modifier = Modifier
                .staggerIn(1, entrance)
                .fillMaxWidth(),
            onClick = onOpenApps,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Clone apps → Apps tab",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "Install apps into Bliss, run them on a hidden display, and drive them " +
                            "from a distro with the clone CLI or an AI agent (MCP).",
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                        color = fluxMutedText(),
                    )
                }
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = fluxMutedText(),
                )
            }
        }

        GlassSettingCard(modifier = Modifier.staggerIn(2, entrance).fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(6.dp)),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "Agent control (no root)",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "In any distro:  clone help  — install / launch / screenshot / ocr / tap. " +
                            "For OpenCode or Claude:  clone mcp-config  adds the bliss-clone MCP server.",
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                        color = fluxMutedText(),
                    )
                }
            }
        }

        GlassSettingCard(modifier = Modifier.staggerIn(3, entrance).fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Engine",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                StatusRow("Core", if (engineReady) "Ready" else "Starting…")
                StatusRow("Display", if (running != null) "$dw × $dh · hidden" else "idle")
                StatusRow("Touch control", backend.label)
                Spacer(Modifier.height(2.dp))
                Text(
                    "Recent activity",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = fluxMutedText(),
                )
                if (logs.isEmpty()) {
                    Text("No activity yet.", fontSize = 11.5.sp, color = fluxMutedText())
                } else {
                    logs.take(6).forEach { line ->
                        Text(
                            line,
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = fluxMutedText(),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(110.dp))
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp, color = fluxMutedText())
        Text(
            value,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
