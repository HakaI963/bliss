package com.altaris.linux.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altaris.linux.core.rea.Findings
import com.altaris.linux.core.rea.ReaBridge
import com.altaris.linux.core.rea.ReaDistro
import com.altaris.linux.core.rea.ReaManager
import com.altaris.linux.core.rea.ReaToolInstaller
import com.altaris.linux.ui.components.PrismButton
import com.altaris.linux.ui.components.PrismCard
import com.altaris.linux.ui.components.PrismChip
import com.altaris.linux.ui.components.PrismNote
import com.altaris.linux.ui.components.PrismOrb
import com.altaris.linux.ui.components.PrismSecondaryButton
import com.altaris.linux.ui.theme.AltarisSection
import com.altaris.linux.ui.theme.Prism
import com.altaris.linux.ui.theme.altarisMutedText
import com.altaris.linux.ui.theme.sectionAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The REA sub-tabs. */
private enum class ReaTab(val label: String) {
    OVERVIEW("Overview"),
    FUNCTIONS("Functions"),
    COMPARE("Compare"),
    STATUS("Status"),
    FILES("Files"),
}

/**
 * REA — the analysis surface.
 *
 * This screen only *observes*. REA runs as an MCP server that the agents inside
 * the distros connect to; nothing here starts an analysis, and there is no model
 * or API key anywhere in the app. What the screen shows is the bridge's record
 * of the calls the agent actually made.
 *
 * Layout follows the supplied reference: a live status pill, a row of large
 * counters, a meta row, a tab bar, then one card that fills the remaining
 * height, with the log strip at the bottom on Overview. Nothing overflows the
 * screen: every frame is sized to what is left rather than given a fixed height.
 */
@Composable
fun ReaScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val accent = sectionAccent(AltarisSection.REA)
    val distro by ReaDistro.state.collectAsState()
    val bridge by ReaBridge.state.collectAsState()
    var tab by remember { mutableStateOf(ReaTab.OVERVIEW) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(refresh) {
        withContext(Dispatchers.IO) {
            ReaDistro.refresh(context)
            val id = ReaDistro.distroId(context)
            if (id != null && ReaBridge.state.value.running.not()) {
                ReaBridge.start(context, id)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Prism.Gutter)
            .padding(top = 6.dp),
    ) {
        if (!distro.installed) {
            NotInstalled(accent)
            return@Column
        }

        StatusPill(accent, distro, bridge)

        Spacer(Modifier.height(8.dp))
        CounterRow(accent, bridge)

        Spacer(Modifier.height(8.dp))
        MetaRow(accent, distro, bridge)

        Spacer(Modifier.height(8.dp))
        TabBar(accent, tab) { tab = it }

        Spacer(Modifier.height(8.dp))
        // One card fills whatever height is left, so the log strip can stay
        // pinned without ever pushing content off-screen.
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                ReaTab.OVERVIEW -> OverviewTab(accent, bridge)
                ReaTab.FUNCTIONS -> FunctionsTab(accent, bridge)
                ReaTab.COMPARE -> CompareTab(accent, bridge)
                ReaTab.STATUS -> StatusTab(accent, distro, bridge) { refresh++ }
                ReaTab.FILES -> ReaFilesTab(accent)
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

// ------------------------------------------------------------------- chrome

@Composable
private fun StatusPill(accent: Color, distro: ReaDistro.State, bridge: ReaBridge.State) {
    val live = bridge.calls.value.any { it.running }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Prism.PanelRadius))
            .background(accent.copy(alpha = 0.10f))
            .border(1.dp, accent.copy(alpha = 0.30f), RoundedCornerShape(Prism.PanelRadius))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PrismOrb(active = bridge.running || live, accent = accent)
        Spacer(Modifier.width(7.dp))
        Text(
            when {
                live -> "Analysis running"
                bridge.running -> "MCP server live"
                else -> "MCP server stopped"
            },
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = accent,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "· " + bridge.toolCount + " tools · " + (distro.method?.label ?: "proot"),
            fontSize = 9.5.sp,
            color = altarisMutedText(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** The reference's big counters. */
@Composable
private fun CounterRow(accent: Color, bridge: ReaBridge.State) {
    val calls = bridge.calls.collectAsState().value
    val functions = calls.sumOf { call -> call.findings.count { it.kind == Findings.Row.Kind.FUNCTION } }
    val bytes = calls.sumOf { it.bytes }
    val diffs = calls.sumOf { it.differentBytes }
    PrismCard(accent = accent) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Prism.Stack),
        ) {
            BigCounter(functions.toString(), "Functions", accent, Modifier.weight(1f))
            BigCounter(ReaBridge.formatBytes(bytes), "Bytes compared", accent, Modifier.weight(1f))
            BigCounter(diffs.toString(), "Differences", accent, Modifier.weight(1f))
            BigCounter(calls.size.toString(), "Tool calls", accent, Modifier.weight(1f))
        }
    }
}

@Composable
private fun BigCounter(value: String, label: String, accent: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            value,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.5).sp,
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            label.uppercase(),
            fontSize = 8.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.6.sp,
            color = altarisMutedText(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MetaRow(accent: Color, distro: ReaDistro.State, bridge: ReaBridge.State) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MetaCell("Method", distro.method?.label?.uppercase() ?: "—", accent, Modifier.weight(1f))
        MetaCell("Tools", bridge.toolCount.toString(), accent, Modifier.weight(1f))
        MetaCell("Agents", bridge.connections.toString(), accent, Modifier.weight(1f))
        MetaCell(
            "Size",
            ReaDistro.formatBytes(distro.sizeBytes),
            accent,
            Modifier.weight(1.2f),
        )
    }
}

@Composable
private fun MetaCell(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, fontSize = 7.5.sp, color = altarisMutedText(), maxLines = 1)
        Text(
            value,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Medium,
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TabBar(accent: Color, current: ReaTab, onSelect: (ReaTab) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        ReaTab.entries.forEach { entry ->
            val selected = entry == current
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (selected) accent else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onSelect(entry) }
                    .padding(horizontal = 11.dp, vertical = 5.dp),
            ) {
                Text(
                    entry.label,
                    fontSize = 10.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) Color.White else altarisMutedText(),
                )
            }
        }
    }
}

// ----------------------------------------------------------------- overview

@Composable
private fun OverviewTab(accent: Color, bridge: ReaBridge.State) {
    val calls = bridge.calls.collectAsState().value
    val logs = bridge.logs.collectAsState().value
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PrismCard(accent = accent, modifier = Modifier.weight(1f).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Activity", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(
                    if (bridge.running) "live" else "idle",
                    fontSize = 8.5.sp,
                    color = if (bridge.running) accent else altarisMutedText(),
                )
            }
            Spacer(Modifier.height(6.dp))
            ActivityGrid(accent, calls, Modifier.weight(1f).fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    calls.count { !it.running && it.error.isBlank() }.toString() + " ok",
                    fontSize = 8.5.sp,
                    color = altarisMutedText(),
                )
                Text(
                    calls.count { it.error.isNotBlank() }.toString() + " failed",
                    fontSize = 8.5.sp,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    calls.firstOrNull()?.let { ReaBridge.formatBytes(it.bytes) } ?: "no calls",
                    fontSize = 8.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = altarisMutedText(),
                )
            }
        }
        // The log strip is part of Overview, exactly like the reference.
        PrismCard(accent = accent, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Recent logs", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("${logs.size}", fontSize = 8.5.sp, color = altarisMutedText())
            }
            Spacer(Modifier.height(4.dp))
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 108.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (logs.isEmpty()) {
                    Text("No activity yet", fontSize = 9.sp, color = altarisMutedText())
                }
                logs.take(40).forEach { line -> LogLineRow(line, accent) }
            }
        }
    }
}

/** One cell per tool call; shading is how long it took. */
@Composable
private fun ActivityGrid(accent: Color, calls: List<ReaBridge.Call>, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "grid")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "gridPhase",
    )
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(scheme.background),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cols = 20
            val rows = 4
            val gap = 2.dp.toPx()
            val cellW = ((size.width - gap * (cols - 1)) / cols).coerceAtLeast(1f)
            val cellH = ((size.height - gap * (rows - 1)) / rows).coerceAtLeast(1f)
            val ordered = calls.reversed()
            for (row in 0 until rows) {
                for (col in 0 until cols) {
                    val index = row * cols + col
                    val call = ordered.getOrNull(index)
                    val base = when {
                        call == null -> 0.06f
                        call.error.isNotBlank() -> 0.85f
                        call.running -> 0.7f
                        else -> 0.25f + (call.ms / 4000f).coerceIn(0f, 0.55f)
                    }
                    val travel = if (call?.running == true) {
                        val distance = kotlin.math.abs((col.toFloat() / cols) - phase)
                        (1f - distance.coerceIn(0f, 1f)) * 0.25f
                    } else 0f
                    drawRoundRect(
                        color = if (call?.error?.isNotBlank() == true) {
                            scheme.error.copy(alpha = 0.7f)
                        } else {
                            accent.copy(alpha = (base + travel).coerceIn(0.06f, 0.95f))
                        },
                        topLeft = Offset(col * (cellW + gap), row * (cellH + gap)),
                        size = Size(cellW, cellH),
                        cornerRadius = CornerRadius(1.5f, 1.5f),
                    )
                }
            }
        }
    }
}

@Composable
private fun LogLineRow(line: ReaBridge.LogLine, accent: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            formatClock(line.at),
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            color = altarisMutedText(),
            modifier = Modifier.width(50.dp),
        )
        Text(
            when (line.level) {
                ReaBridge.LogLine.Level.CALL -> "▸"
                ReaBridge.LogLine.Level.RESULT -> "✓"
                ReaBridge.LogLine.Level.ERROR -> "✕"
                ReaBridge.LogLine.Level.INFO -> "·"
            },
            fontSize = 8.sp,
            color = when (line.level) {
                ReaBridge.LogLine.Level.ERROR -> MaterialTheme.colorScheme.error
                ReaBridge.LogLine.Level.CALL -> accent
                else -> altarisMutedText()
            },
            modifier = Modifier.width(10.dp),
        )
        Text(
            line.text,
            fontSize = 8.5.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (line.level == ReaBridge.LogLine.Level.ERROR) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

// ---------------------------------------------------------------- functions

@Composable
private fun FunctionsTab(accent: Color, bridge: ReaBridge.State) {
    val calls = bridge.calls.collectAsState().value
    var selected by remember { mutableStateOf<Findings.Row?>(null) }
    var filter by remember { mutableStateOf("") }

    // Every recognised row across every call, newest first.
    val rows = remember(calls) {
        calls.flatMap { call -> call.findings.map { call.tool to it } }
    }
    val shown = if (filter.isBlank()) rows else rows.filter {
        it.second.name.contains(filter, ignoreCase = true)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        PrismCard(accent = accent, modifier = Modifier.weight(1f).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Functions", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(6.dp))
                Text("${rows.size}", fontSize = 9.sp, color = altarisMutedText())
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(5.dp))
            androidx.compose.material3.OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Filter by name", fontSize = 10.sp) },
                textStyle = MaterialTheme.typography.bodySmall,
                singleLine = true,
            )
            Spacer(Modifier.height(5.dp))
            if (shown.isEmpty()) {
                PrismNote(
                    "No functions yet. They appear as the agent's REA tools return them " +
                        "(decompile, inspect-elf-notes, inspect-managed-members)."
                )
                return@PrismCard
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                items(shown.take(400)) { (tool, row) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(3.dp))
                            .clickable { selected = row }
                            .padding(horizontal = 4.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(accent.copy(alpha = 0.55f))
                        )
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                row.name,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                row.kind.label + if (row.detail.isBlank()) "" else " · " + row.detail,
                                fontSize = 8.sp,
                                color = altarisMutedText(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            row.offset.ifBlank { tool.take(14) },
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            color = altarisMutedText(),
                        )
                    }
                }
            }
        }
    }

    // Detail as a pop-up, not an expanding row: the list keeps its height.
    selected?.let { row ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { selected = null },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { selected = null }) {
                    Text("Close", color = accent)
                }
            },
            title = {
                Text(row.name, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DetailLine("Kind", row.kind.label, accent)
                    if (row.offset.isNotBlank()) DetailLine("Offset", row.offset, accent)
                    if (row.size > 0) {
                        DetailLine("Size", ReaDistro.formatBytes(row.size), accent)
                    }
                    if (row.detail.isNotBlank()) DetailLine("Detail", row.detail, accent)
                }
            },
        )
    }
}

@Composable
private fun DetailLine(label: String, value: String, accent: Color) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            label,
            fontSize = 9.sp,
            color = altarisMutedText(),
            modifier = Modifier.width(56.dp),
        )
        Text(
            value,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = accent,
            modifier = Modifier.weight(1f),
        )
    }
}

// ------------------------------------------------------------------ compare

@Composable
private fun CompareTab(accent: Color, bridge: ReaBridge.State) {
    val calls = bridge.calls.collectAsState().value
    // The most recent comparison a tool reported.
    val comparison = calls.firstOrNull { it.compared != null || it.differentBytes > 0 }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PrismCard(accent = accent, modifier = Modifier.weight(1f).fillMaxWidth()) {
            Text("Original vs rebuild", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(5.dp))
            if (comparison == null) {
                PrismNote(
                    "No comparison yet. When the agent runs REA's compare-artifacts (or " +
                        "decompiles and rebuilds a binary), the byte comparison appears here."
                )
                return@PrismCard
            }
            val total = comparison.totalBytes.coerceAtLeast(
                comparison.matchedBytes + comparison.differentBytes
            )
            val matched = comparison.matchedBytes
            val different = comparison.differentBytes
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Prism.Stack),
            ) {
                BigCounter(ReaBridge.formatBytes(matched), "Matched", accent, Modifier.weight(1f))
                BigCounter(
                    ReaBridge.formatBytes(different),
                    "Different",
                    MaterialTheme.colorScheme.error,
                    Modifier.weight(1f),
                )
                BigCounter(
                    if (total > 0) ((matched * 100) / total).toString() + "%" else "—",
                    "Match",
                    accent,
                    Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            // A matched/differing bar, like the recording's byte strip.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            ) {
                if (total > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(matched.toFloat() / total)
                            .height(10.dp)
                            .background(accent)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(different.toFloat() / total)
                            .height(10.dp)
                            .background(MaterialTheme.colorScheme.error)
                            .then(Modifier)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                comparison.tool + " · " + ReaBridge.formatBytes(total) + " total",
                fontSize = 8.5.sp,
                fontFamily = FontFamily.Monospace,
                color = altarisMutedText(),
            )
            Spacer(Modifier.height(8.dp))
            val diffs = comparison.findings.filter { it.kind == Findings.Row.Kind.DIFF }
            if (diffs.isNotEmpty()) {
                Text("Differences", fontSize = 10.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(3.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    diffs.take(300).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                row.offset.ifBlank { "—" },
                                fontSize = 8.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.width(64.dp),
                            )
                            Text(
                                row.name,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                row.detail,
                                fontSize = 8.5.sp,
                                color = altarisMutedText(),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------- status

@Composable
private fun StatusTab(
    accent: Color,
    distro: ReaDistro.State,
    bridge: ReaBridge.State,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tools by ReaToolInstaller.state.collectAsState()
    val status by ReaManager.status.collectAsState()
    val calls = bridge.calls.collectAsState().value

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PrismCard(accent = accent) {
            Text("Environment", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(5.dp))
            DetailLine("Distro", distro.method?.label ?: "—", accent)
            DetailLine("REA", status.reaVersion.ifBlank { "not installed" }, accent)
            DetailLine("Node", status.nodeVersion.ifBlank { "—" }, accent)
            DetailLine("Server", if (bridge.running) "live on port " + ReaBridge.PORT else "stopped", accent)
            DetailLine("Tools", bridge.toolCount.toString() + " advertised", accent)
            DetailLine("Calls", calls.size.toString() + " recorded", accent)
            DetailLine("Path", ReaManager.ROOT, accent)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PrismSecondaryButton(
                    text = if (bridge.running) "Restart" else "Start",
                    accent = accent,
                    icon = Icons.Default.Refresh,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                ReaBridge.stop()
                                ReaDistro.distroId(context)?.let { ReaBridge.start(context, it) }
                            }
                            onRefresh()
                        }
                    },
                )
                PrismSecondaryButton(
                    text = "Install tools",
                    accent = accent,
                    icon = Icons.Default.PlayArrow,
                    modifier = Modifier.weight(1f),
                    enabled = !tools.running,
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val id = ReaDistro.distroId(context) ?: return@withContext
                                ReaToolInstaller.install(context, id, ReaDistro.ALL_TOOLS)
                            }
                        }
                    },
                )
            }
        }

        // One bar per tool, all visible at once.
        if (tools.tools.isNotEmpty()) {
            PrismCard(accent = accent) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tool installs", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text(tools.summary, fontSize = 8.5.sp, color = altarisMutedText())
                }
                Spacer(Modifier.height(5.dp))
                tools.tools.forEach { tool -> ToolBar(tool, accent) }
            }
        }

        PrismCard(accent = accent) {
            Text("Agent info", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(5.dp))
            val active = calls.firstOrNull { it.running }
            if (active == null) {
                PrismNote("No call in flight. The server is waiting for an agent.")
            } else {
                DetailLine("Tool", active.tool, accent)
                DetailLine("Started", formatClock(active.at), accent)
                DetailLine("Args", active.arguments.take(90), accent)
            }
            Spacer(Modifier.height(5.dp))
            val totalMs = calls.sumOf { it.ms }
            DetailLine("Time running", formatDuration(totalMs), accent)
            DetailLine(
                "Work",
                calls.count { !it.running }.toString() + " / " + calls.size + " finished",
                accent,
            )
            DetailLine(
                "Received",
                ReaBridge.formatBytes(calls.sumOf { it.bytes }),
                accent,
            )
        }
    }
}

@Composable
private fun ToolBar(tool: ReaToolInstaller.ToolState, accent: Color) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            tool.label,
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(78.dp),
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
        Spacer(Modifier.width(5.dp))
        Text(
            when (tool.status) {
                ReaToolInstaller.ToolState.Status.QUEUED -> "queued"
                ReaToolInstaller.ToolState.Status.RUNNING ->
                    if (tool.percent >= 0) tool.percent.toString() + "%" else "…"
                ReaToolInstaller.ToolState.Status.DONE -> "done"
                ReaToolInstaller.ToolState.Status.FAILED -> "failed"
                ReaToolInstaller.ToolState.Status.SKIPPED -> "skipped"
            },
            fontSize = 8.sp,
            color = when (tool.status) {
                ReaToolInstaller.ToolState.Status.FAILED -> scheme.error
                ReaToolInstaller.ToolState.Status.DONE -> altarisMutedText()
                else -> accent
            },
            modifier = Modifier.width(38.dp),
        )
    }
}

// -------------------------------------------------------------------- files

@Composable
private fun ReaFilesTab(accent: Color) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf(com.altaris.linux.core.rea.ReaFiles.DEFAULT_PATH) }
    var entries by remember {
        mutableStateOf<List<com.altaris.linux.core.rea.ReaFiles.Entry>>(emptyList())
    }
    var note by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingDelete by remember {
        mutableStateOf<com.altaris.linux.core.rea.ReaFiles.Entry?>(null)
    }

    fun load(target: String) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                com.altaris.linux.core.rea.ReaFiles.list(context, target)
            }
            result.onSuccess {
                entries = it
                path = target
                note = null
            }.onFailure { note = it.message }
        }
    }

    LaunchedEffect(Unit) { load(path) }

    Column(modifier = Modifier.fillMaxSize()) {
        PrismCard(accent = accent, modifier = Modifier.weight(1f).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val segments = path.trim('/').split('/').filter { it.isNotBlank() }
                    Crumb("/", accent) { load("/") }
                    segments.forEachIndexed { index, segment ->
                        Text("/", fontSize = 9.sp, color = altarisMutedText())
                        val target = "/" + segments.take(index + 1).joinToString("/")
                        Crumb(segment, accent) { load(target) }
                    }
                }
                PrismChip("New file", accent, onClick = {
                    val target = "$path/untitled.txt"
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            com.altaris.linux.core.rea.ReaFiles.createFile(context, target)
                        }
                        editing = target to ""
                        load(path)
                    }
                })
            }
            Spacer(Modifier.height(4.dp))
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                items(entries.take(300)) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (entry.dir) load(entry.path) else scope.launch {
                                    val read = withContext(Dispatchers.IO) {
                                        com.altaris.linux.core.rea.ReaFiles.read(context, entry.path)
                                    }
                                    read.onSuccess { editing = entry.path to it }
                                        .onFailure { note = it.message }
                                }
                            }
                            .padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            entry.name,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (entry.dir) "dir" else ReaDistro.formatBytes(entry.size),
                            fontSize = 8.5.sp,
                            color = altarisMutedText(),
                        )
                        Spacer(Modifier.width(6.dp))
                        PrismChip("Del", MaterialTheme.colorScheme.error, onClick = {
                            pendingDelete = entry
                        })
                    }
                }
            }
            if (note != null) {
                Spacer(Modifier.height(4.dp))
                PrismNote(note!!, color = MaterialTheme.colorScheme.error)
            }
        }
    }

    editing?.let { (filePath, content) ->
        var draft by remember(filePath) { mutableStateOf(content) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editing = null },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            com.altaris.linux.core.rea.ReaFiles.write(context, filePath, draft)
                        }
                        editing = null
                        load(path)
                    }
                }) { Text("Save", color = accent) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { editing = null }) {
                    Text("Close")
                }
            },
            title = {
                Text(filePath, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                )
            },
        )
    }

    pendingDelete?.let { entry ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingDelete = null },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            com.altaris.linux.core.rea.ReaFiles.delete(context, entry.path)
                        }
                        pendingDelete = null
                        load(path)
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingDelete = null }) {
                    Text("Keep")
                }
            },
            title = { Text("Delete this?", fontSize = 13.sp) },
            text = {
                Text(
                    entry.path + if (entry.dir) " (and everything in it)" else "",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            },
        )
    }
}

@Composable
private fun Crumb(text: String, accent: Color, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 9.sp,
        fontFamily = FontFamily.Monospace,
        color = accent,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp),
    )
}

// ------------------------------------------------------------------- states

@Composable
private fun NotInstalled(accent: Color) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().blur(6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PrismCard(accent = accent) {
                Row(horizontalArrangement = Arrangement.spacedBy(Prism.Stack)) {
                    BigCounter("—", "Functions", accent, Modifier.weight(1f))
                    BigCounter("—", "Bytes compared", accent, Modifier.weight(1f))
                    BigCounter("—", "Differences", accent, Modifier.weight(1f))
                    BigCounter("—", "Tool calls", accent, Modifier.weight(1f))
                }
            }
            PrismCard(accent = accent) {
                Text("Activity", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PrismCard(accent = accent) {
                Text("REA is not installed", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                PrismNote(
                    "REA runs inside a private environment Altaris creates for itself, and " +
                        "is exposed to your agents as an MCP server. Install it from " +
                        "Tools → Reverse engineering: pick proot (no root) or chroot, and " +
                        "every analysis tool is installed with it."
                )
            }
        }
    }
}

// ------------------------------------------------------------------ helpers

private fun formatClock(at: Long): String =
    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(at))

private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }
}
