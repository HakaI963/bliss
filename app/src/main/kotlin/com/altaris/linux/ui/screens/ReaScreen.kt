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
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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

/** The REA sub-tabs. Six, equal width, filling the row. */
private enum class ReaTab(val label: String) {
    OVERVIEW("Overview"),
    FUNCTIONS("Functions"),
    OBJDUMP("Objdump"),
    FIDELITY("Fidelity"),
    STATUS("Status"),
    FILES("Files"),
}

/**
 * REA — the analysis surface.
 *
 * This screen only observes. REA runs as an MCP server that the agents connect
 * to; there is no model or key in the app. Everything shown is the bridge's
 * record of the calls an agent actually made.
 *
 * Layout follows the reference: a compact status strip, a row of large
 * counters, a full-bleed tab row, then content made of **small frames** rather
 * than a few stretched ones. Nothing is left to scroll off the bottom.
 */
@Composable
fun ReaScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val accent = sectionAccent(AltarisSection.REA)
    val distro by ReaDistro.state.collectAsState()
    val bridge by ReaBridge.state.collectAsState()
    var tab by remember { mutableStateOf(ReaTab.OVERVIEW) }

    LaunchedEffect(distro.installed, distro.ready) {
        withContext(Dispatchers.IO) {
            ReaDistro.refresh(context)
            // Start the server only when REA can actually run, so the screen
            // never shows a shell "not found" as the first thing you see.
            val id = ReaDistro.distroId(context)
            if (id != null && ReaDistro.state.value.ready && !ReaBridge.state.value.running) {
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

        StatusStrip(accent, distro, bridge)
        Spacer(Modifier.height(6.dp))
        CounterRow(accent)
        Spacer(Modifier.height(6.dp))
        TabRow(accent, tab) { tab = it }
        Spacer(Modifier.height(6.dp))

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                ReaTab.OVERVIEW -> OverviewTab(accent, bridge)
                ReaTab.FUNCTIONS -> FunctionsTab(accent)
                ReaTab.OBJDUMP -> OutputTab(accent, "Objdump", objdumpKinds)
                ReaTab.FIDELITY -> FidelityTab(accent)
                ReaTab.STATUS -> StatusTab(accent, distro, bridge)
                ReaTab.FILES -> ReaFilesTab(accent)
            }
        }
    }
}

// ------------------------------------------------------------------- chrome

/**
 * The status strip.
 *
 * When the server cannot start, the reason is shown here in red — not hidden in
 * another tab. The previous build reported only "stopped", which is how a
 * missing REA core looked like a dead button.
 */
@Composable
private fun StatusStrip(accent: Color, distro: ReaDistro.State, bridge: ReaBridge.State) {
    val calls by ReaBridge.calls.collectAsState()
    val live = calls.any { it.running }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Prism.PanelRadius))
            .background(accent.copy(alpha = 0.09f))
            .border(1.dp, accent.copy(alpha = 0.28f), RoundedCornerShape(Prism.PanelRadius))
            .padding(horizontal = 9.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrismOrb(active = bridge.running || live, accent = accent)
            Spacer(Modifier.width(6.dp))
            Text(
                when {
                    !distro.ready -> "REA core missing"
                    live -> "Analysis running"
                    bridge.running -> "MCP live"
                    else -> "MCP stopped"
                },
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (!distro.ready) MaterialTheme.colorScheme.error else accent,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                "· " + bridge.toolCount + " tools · " + bridge.connections + " agents · " +
                    (distro.method?.label ?: "proot"),
                fontSize = 9.sp,
                color = altarisMutedText(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        val problem = bridge.error.ifBlank {
            if (!distro.ready) "Install the REA core from Tools → Reverse engineering." else ""
        }
        if (problem.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                problem,
                fontSize = 8.5.sp,
                lineHeight = 11.sp,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CounterRow(accent: Color) {
    val calls by ReaBridge.calls.collectAsState()
    val functions = calls.sumOf { c -> c.findings.count { it.kind == Findings.Row.Kind.FUNCTION } }
    val compared = calls.sumOf { it.bytes }
    val diffs = calls.sumOf { it.differentBytes }
    PrismCard(accent = accent) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Prism.Stack),
        ) {
            BigCounter(functions.toString(), "Functions", accent, Modifier.weight(1f))
            BigCounter(ReaBridge.formatBytes(compared), "Bytes compared", accent, Modifier.weight(1f))
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
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.4).sp,
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            label.uppercase(),
            fontSize = 7.5.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.5.sp,
            color = altarisMutedText(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The tab row.
 *
 * Equal weight so the row reaches both edges. It scrolls only as a fallback for
 * very narrow screens; on a normal phone all six fit.
 */
@Composable
private fun TabRow(accent: Color, current: ReaTab, onSelect: (ReaTab) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        ReaTab.entries.forEach { entry ->
            val selected = entry == current
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (selected) accent else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onSelect(entry) }
                    .padding(vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    entry.label,
                    fontSize = 8.5.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) Color.White else altarisMutedText(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A small frame used throughout the grid. */
@Composable
private fun MiniFrame(
    title: String,
    accent: Color,
    modifier: Modifier = Modifier,
    trailing: String = "",
    content: @Composable ColumnScope.() -> Unit,
) {
    PrismCard(accent = accent, modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (trailing.isNotBlank()) {
                Text(
                    trailing,
                    fontSize = 8.sp,
                    color = altarisMutedText(),
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        content()
    }
}

// ----------------------------------------------------------------- overview

/**
 * Overview: a grid of small live frames.
 *
 * The centre of gravity is the two things that show *work happening*: the
 * activity grid (one cell per call) and the live result feed, where the text an
 * agent is getting back scrolls as it arrives. The log strip sits at the bottom
 * inside the screen.
 */
@Composable
private fun OverviewTab(accent: Color, bridge: ReaBridge.State) {
    val calls by ReaBridge.calls.collectAsState()
    val logs by ReaBridge.logs.collectAsState()
    val current = calls.firstOrNull { it.running } ?: calls.firstOrNull()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            MiniFrame(
                title = "Activity",
                accent = accent,
                modifier = Modifier.weight(1.35f).height(84.dp),
                trailing = if (bridge.running) "live" else "idle",
            ) {
                ActivityGrid(accent, calls, Modifier.fillMaxWidth().weight(1f))
            }
            MiniFrame(
                title = "Now",
                accent = accent,
                modifier = Modifier.weight(1f).height(84.dp),
                trailing = current?.tool?.take(12) ?: "",
            ) {
                if (current == null) {
                    Text("idle", fontSize = 8.5.sp, color = altarisMutedText())
                } else {
                    Text(
                        current.tool,
                        fontSize = 8.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (current.running) "running" else "${current.ms} ms",
                        fontSize = 8.sp,
                        color = altarisMutedText(),
                    )
                    Text(
                        ReaBridge.formatBytes(current.bytes),
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        color = altarisMutedText(),
                    )
                }
            }
        }

        // The live output frame: real tool text as it comes back.
        MiniFrame(
            title = "Live output",
            accent = accent,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            trailing = current?.tool ?: "",
        ) {
            val preview = current?.preview.orEmpty()
            if (preview.isBlank()) {
                Text(
                    "Nothing yet. When an agent calls a REA tool, its output appears here.",
                    fontSize = 8.5.sp,
                    lineHeight = 11.sp,
                    color = altarisMutedText(),
                )
            } else {
                SelectionContainer {
                    Text(
                        preview.takeLast(1600),
                        fontSize = 8.sp,
                        lineHeight = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 80,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // The log strip, inside the screen.
        MiniFrame(
            title = "Logs",
            accent = accent,
            modifier = Modifier.height(84.dp).fillMaxWidth(),
            trailing = logs.size.toString(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                if (logs.isEmpty()) {
                    Text("no activity", fontSize = 8.sp, color = altarisMutedText())
                }
                logs.take(30).forEach { LogLineRow(it, accent) }
            }
        }
    }
}

/** One cell per tool call; shading is duration. */
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
    Box(modifier = modifier.clip(RoundedCornerShape(3.dp)).background(scheme.background)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cols = 18
            val rows = 3
            val gap = 1.5.dp.toPx()
            val cellW = ((size.width - gap * (cols - 1)) / cols).coerceAtLeast(1f)
            val cellH = ((size.height - gap * (rows - 1)) / rows).coerceAtLeast(1f)
            val ordered = calls.reversed()
            for (row in 0 until rows) {
                for (col in 0 until cols) {
                    val call = ordered.getOrNull(row * cols + col)
                    val base = when {
                        call == null -> 0.06f
                        call.error.isNotBlank() -> 0.85f
                        call.running -> 0.7f
                        else -> 0.25f + (call.ms / 4000f).coerceIn(0f, 0.55f)
                    }
                    val travel = if (call?.running == true) {
                        val d = kotlin.math.abs((col.toFloat() / cols) - phase)
                        (1f - d.coerceIn(0f, 1f)) * 0.25f
                    } else 0f
                    drawRoundRect(
                        color = if (call?.error?.isNotBlank() == true) {
                            scheme.error.copy(alpha = 0.7f)
                        } else {
                            accent.copy(alpha = (base + travel).coerceIn(0.06f, 0.95f))
                        },
                        topLeft = Offset(col * (cellW + gap), row * (cellH + gap)),
                        size = Size(cellW, cellH),
                        cornerRadius = CornerRadius(1f, 1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun LogLineRow(line: ReaBridge.LogLine, accent: Color) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            formatClock(line.at),
            fontSize = 7.5.sp,
            fontFamily = FontFamily.Monospace,
            color = altarisMutedText(),
            modifier = Modifier.width(44.dp),
        )
        Text(
            when (line.level) {
                ReaBridge.LogLine.Level.CALL -> "▸"
                ReaBridge.LogLine.Level.RESULT -> "✓"
                ReaBridge.LogLine.Level.ERROR -> "✕"
                ReaBridge.LogLine.Level.INFO -> "·"
            },
            fontSize = 7.5.sp,
            color = when (line.level) {
                ReaBridge.LogLine.Level.ERROR -> MaterialTheme.colorScheme.error
                ReaBridge.LogLine.Level.CALL -> accent
                else -> altarisMutedText()
            },
            modifier = Modifier.width(9.dp),
        )
        Text(
            line.text,
            fontSize = 8.sp,
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
private fun FunctionsTab(accent: Color) {
    val calls by ReaBridge.calls.collectAsState()
    var selected by remember { mutableStateOf<Findings.Row?>(null) }
    var filter by remember { mutableStateOf("") }
    val rows = remember(calls) {
        calls.flatMap { call -> call.findings.map { call.tool to it } }
    }
    val shown = if (filter.isBlank()) rows else rows.filter {
        it.second.name.contains(filter, ignoreCase = true)
    }

    PrismCard(accent = accent, modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Functions", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(5.dp))
            Text(rows.size.toString(), fontSize = 8.5.sp, color = altarisMutedText())
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(4.dp))
        androidx.compose.material3.OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            modifier = Modifier.fillMaxWidth().height(40.dp),
            placeholder = { Text("filter", fontSize = 9.sp) },
            textStyle = MaterialTheme.typography.bodySmall,
            singleLine = true,
        )
        Spacer(Modifier.height(4.dp))
        if (shown.isEmpty()) {
            PrismNote(
                "No functions yet. They appear as the agent's REA tools return them.",
            )
            return@PrismCard
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            items(shown.take(500)) { (tool, row) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(3.dp))
                        .clickable { selected = row }
                        .padding(horizontal = 3.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(3.dp)
                            .clip(CircleShape)
                            .background(accent.copy(alpha = 0.55f))
                    )
                    Spacer(Modifier.width(5.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            row.name,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            row.kind.label + if (row.detail.isBlank()) "" else " · " + row.detail,
                            fontSize = 7.5.sp,
                            color = altarisMutedText(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        row.offset.ifBlank { tool.take(12) },
                        fontSize = 7.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = altarisMutedText(),
                    )
                }
            }
        }
    }

    selected?.let { row ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { selected = null },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { selected = null }) {
                    Text("Close", color = accent)
                }
            },
            title = { Text(row.name, fontSize = 12.sp, fontFamily = FontFamily.Monospace) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    DetailLine("Kind", row.kind.label, accent)
                    if (row.offset.isNotBlank()) DetailLine("Offset", row.offset, accent)
                    if (row.size > 0) DetailLine("Size", ReaDistro.formatBytes(row.size), accent)
                    if (row.detail.isNotBlank()) DetailLine("Detail", row.detail, accent)
                }
            },
        )
    }
}

// ------------------------------------------------------- decompile/objdump

/**
 * Tools whose raw output belongs in the Objdump tab.
 *
 * Decompiler output is included: it is the same kind of thing to read (a wall
 * of generated text with addresses), and the user's tab list has no separate
 * Decompile tab.
 */
private val objdumpKinds = listOf(
    "objdump", "elf", "disassemble", "assembly", "section", "symbol",
    "decompile", "java", "smali", "il", "source",
)

/**
 * A real-output tab.
 *
 * Shows the verbatim text of the most recent matching tool call, so this is the
 * agent's actual decompiler or disassembler output — not a summary the app
 * invented.
 */
@Composable
private fun OutputTab(
    accent: Color,
    title: String,
    toolHints: List<String>,
) {
    val calls by ReaBridge.calls.collectAsState()
    val matching = calls.filter { call ->
        call.preview.isNotBlank() && toolHints.any { call.tool.lowercase().contains(it) }
    }
    var picked by remember(calls) { mutableStateOf(0) }
    val current = matching.getOrNull(picked)

    PrismCard(accent = accent, modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(5.dp))
            Text("${matching.size}", fontSize = 8.5.sp, color = altarisMutedText())
            Spacer(Modifier.weight(1f))
            if (matching.size > 1) {
                Text(
                    "◀ ${picked + 1}/${matching.size} ▶",
                    fontSize = 8.5.sp,
                    color = accent,
                    modifier = Modifier.clickable {
                        picked = (picked + 1) % matching.size
                    },
                )
            }
        }
        if (current == null) {
            Spacer(Modifier.height(4.dp))
            PrismNote(
                "No " + title.lowercase() + " output yet. It appears when an agent runs a " +
                    "REA tool that produces it.",
            )
            return@PrismCard
        }
        Spacer(Modifier.height(2.dp))
        Text(
            current.tool + " · " + ReaBridge.formatBytes(current.bytes) + " · " + current.ms + " ms",
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            color = altarisMutedText(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        SelectionContainer {
            Text(
                current.preview,
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                fontSize = 8.sp,
                lineHeight = 10.5.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

// ----------------------------------------------------------------- fidelity

@Composable
private fun FidelityTab(accent: Color) {
    val calls by ReaBridge.calls.collectAsState()
    val comparison = calls.firstOrNull { it.compared != null || it.differentBytes > 0 }

    PrismCard(accent = accent, modifier = Modifier.fillMaxSize()) {
        Text("Fidelity", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        if (comparison == null) {
            PrismNote(
                "No comparison yet. When an agent compares a rebuilt binary against the " +
                    "original, the byte match appears here.",
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
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(9.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
        ) {
            if (total > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(matched.toFloat() / total)
                        .height(9.dp)
                        .background(accent)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            comparison.tool + " · " + ReaBridge.formatBytes(total) + " total",
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            color = altarisMutedText(),
        )
        Spacer(Modifier.height(6.dp))
        val diffs = comparison.findings.filter { it.kind == Findings.Row.Kind.DIFF }
        if (diffs.isEmpty()) {
            PrismNote("No differing ranges were reported.")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                items(diffs.take(400)) { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            row.offset.ifBlank { "—" },
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.width(58.dp),
                        )
                        Text(
                            row.name,
                            fontSize = 8.5.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            row.detail,
                            fontSize = 8.sp,
                            color = altarisMutedText(),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------- status

@Composable
private fun StatusTab(accent: Color, distro: ReaDistro.State, bridge: ReaBridge.State) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tools by ReaToolInstaller.state.collectAsState()
    val status by ReaManager.status.collectAsState()
    val calls by ReaBridge.calls.collectAsState()
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(tick) {
        withContext(Dispatchers.IO) { ReaDistro.refresh(context) }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        MiniFrame("Server", accent, trailing = if (bridge.running) "live" else "stopped") {
            DetailLine("Endpoint", ReaBridge.ENDPOINT, accent)
            DetailLine("Tools", bridge.toolCount.toString() + " advertised", accent)
            DetailLine("Agents", bridge.connections.toString() + " connected", accent)
            DetailLine("Calls", calls.size.toString() + " recorded", accent)
            DetailLine("REA", status.reaVersion.ifBlank { "—" }, accent)
            DetailLine("Node", status.nodeVersion.ifBlank { "—" }, accent)
            DetailLine("Distro", distro.method?.label ?: "—", accent)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                PrismSecondaryButton(
                    text = if (bridge.running) "Restart" else "Start",
                    accent = accent,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                ReaBridge.stop()
                                ReaDistro.distroId(context)?.let { ReaBridge.start(context, it) }
                            }
                            tick++
                        }
                    },
                )
                PrismSecondaryButton(
                    text = "Install tools",
                    accent = accent,
                    modifier = Modifier.weight(1f),
                    enabled = !tools.running,
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val id = ReaDistro.distroId(context) ?: return@withContext
                                ReaToolInstaller.install(context, id, ReaDistro.ALL_TOOLS)
                            }
                            tick++
                        }
                    },
                )
            }
        }

        MiniFrame("Run", accent, trailing = if (calls.any { it.running }) "live" else "idle") {
            val active = calls.firstOrNull { it.running }
            DetailLine("Tool", active?.tool ?: "—", accent)
            DetailLine("Started", active?.let { formatClock(it.at) } ?: "—", accent)
            DetailLine("Elapsed", formatDuration(calls.sumOf { it.ms }), accent)
            DetailLine(
                "Work",
                calls.count { !it.running }.toString() + " / " + calls.size + " finished",
                accent,
            )
            DetailLine("Received", ReaBridge.formatBytes(calls.sumOf { it.bytes }), accent)
        }

        if (tools.tools.isNotEmpty()) {
            MiniFrame("Tool installs", accent, trailing = tools.summary) {
                tools.tools.forEach { ToolBar(it, accent) }
            }
        }
    }
}

@Composable
private fun ToolBar(tool: ReaToolInstaller.ToolState, accent: Color) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            tool.label,
            fontSize = 8.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(70.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(3.dp)
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
                    .height(3.dp)
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
            fontSize = 7.5.sp,
            color = when (tool.status) {
                ReaToolInstaller.ToolState.Status.FAILED -> scheme.error
                ReaToolInstaller.ToolState.Status.DONE -> altarisMutedText()
                else -> accent
            },
            modifier = Modifier.width(34.dp),
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

    PrismCard(accent = accent, modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val segments = path.trim('/').split('/').filter { it.isNotBlank() }
                Crumb("/", accent) { load("/") }
                segments.forEachIndexed { index, segment ->
                    Text("/", fontSize = 8.sp, color = altarisMutedText())
                    val target = "/" + segments.take(index + 1).joinToString("/")
                    Crumb(segment, accent) { load(target) }
                }
            }
            PrismChip("New", accent, onClick = {
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
        Spacer(Modifier.height(3.dp))
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            items(entries.take(400)) { entry ->
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
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        entry.name,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (entry.dir) "dir" else ReaDistro.formatBytes(entry.size),
                        fontSize = 8.sp,
                        color = altarisMutedText(),
                    )
                    Spacer(Modifier.width(5.dp))
                    PrismChip("Del", MaterialTheme.colorScheme.error, onClick = {
                        pendingDelete = entry
                    })
                }
            }
        }
        if (note != null) {
            Spacer(Modifier.height(3.dp))
            PrismNote(note!!, color = MaterialTheme.colorScheme.error)
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
            title = { Text(filePath, fontSize = 10.sp, fontFamily = FontFamily.Monospace) },
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
            title = { Text("Delete this?", fontSize = 12.sp) },
            text = {
                Text(
                    entry.path + if (entry.dir) " (and everything in it)" else "",
                    fontSize = 9.sp,
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
        fontSize = 8.sp,
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
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            PrismCard(accent = accent) {
                Row(horizontalArrangement = Arrangement.spacedBy(Prism.Stack)) {
                    BigCounter("—", "Functions", accent, Modifier.weight(1f))
                    BigCounter("—", "Bytes compared", accent, Modifier.weight(1f))
                    BigCounter("—", "Differences", accent, Modifier.weight(1f))
                    BigCounter("—", "Tool calls", accent, Modifier.weight(1f))
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .clip(RoundedCornerShape(Prism.PanelRadius))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PrismCard(accent = accent) {
                Text("REA is not installed", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(5.dp))
                PrismNote(
                    "Install it from Tools → Reverse engineering. Pick proot (no root) or " +
                        "chroot (needs root); every analysis tool installs with it. Agents then " +
                        "connect to it over MCP and this screen shows what they do."
                )
            }
        }
    }
}

// ------------------------------------------------------------------ helpers

@Composable
private fun DetailLine(label: String, value: String, accent: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            fontSize = 8.sp,
            color = altarisMutedText(),
            modifier = Modifier.width(50.dp),
        )
        Text(
            value,
            fontSize = 8.5.sp,
            fontFamily = FontFamily.Monospace,
            color = accent,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

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
