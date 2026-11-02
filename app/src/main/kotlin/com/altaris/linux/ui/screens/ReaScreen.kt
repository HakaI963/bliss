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
import com.altaris.linux.core.rea.AgentWork
import com.altaris.linux.core.rea.BinaryChunks
import com.altaris.linux.core.rea.ChunkRow
import com.altaris.linux.core.rea.ChunkState
import com.altaris.linux.core.rea.Findings
import com.altaris.linux.core.rea.ReaAssetTools
import com.altaris.linux.core.rea.ReaBridge
import com.altaris.linux.core.rea.ReaDebugger
import com.altaris.linux.core.rea.ReaDiagnosis
import com.altaris.linux.core.rea.ReaDistro
import com.altaris.linux.core.rea.ReaManager
import com.altaris.linux.core.rea.ReaToolInstaller
import com.altaris.linux.ui.components.PrismCard
import com.altaris.linux.ui.components.PrismChip
import com.altaris.linux.ui.components.PrismNote
import com.altaris.linux.ui.components.PrismOrb
import com.altaris.linux.ui.components.PrismRow
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
    DEBUGGER("Debugger"),
    OTHER("Other"),
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

    // The server is always running: there is no Start button. Refresh the truth,
    // ensure the server, and keep retrying while it is down. It also keeps
    // watching after a successful start, so an install that finishes while this
    // screen is open brings the server up without any interaction.
    LaunchedEffect(Unit) {
        var first = true
        while (true) {
            withContext(Dispatchers.IO) {
                // The first read is deep: it is what proves the environment is
                // really there and fills the cache the polls then read. Every
                // later tick is shallow, so a poll never walks the rootfs or
                // forks a root shell.
                ReaDistro.refresh(context, deep = first)
                first = false
                if (ReaDistro.state.value.ready) ReaBridge.ensureRunning(context)
            }
            // Poll faster while waiting for an install, slower when healthy.
            val healthy = ReaBridge.state.value.running
            kotlinx.coroutines.delay(if (healthy) 15_000L else 4_000L)
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
            // The gate above is driven by the cached state. If the cache is
            // cold or stale, the screen would sit on "not installed" for a
            // chroot that is actually there — which is exactly what it did.
            // The poll above does one deep read when the screen opens; this
            // makes that read happen again on demand instead of asking the
            // user to leave and come back.
            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) { ReaDistro.refresh(context, deep = true) }
            }
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
                ReaTab.OVERVIEW -> OverviewTab(accent, distro, bridge)
                ReaTab.FUNCTIONS -> FunctionsTab(accent)
                ReaTab.OBJDUMP -> OutputTab(accent, "Objdump", objdumpKinds)
                ReaTab.FIDELITY -> FidelityTab(accent)
                ReaTab.DEBUGGER -> DebuggerTab(accent)
                ReaTab.OTHER -> OtherTab(accent)
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
                    (distro.target?.label ?: "no distro"),
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
 * Overview — the working surface.
 *
 * Modelled on the supplied reference, with its parts renamed to REA:
 *
 *  - **Binary map** (where the reference has a plate heatmap): the artifact cut
 *    into chunks drawn as boxes. Each chunk carries the state of the work done
 *    on it — untouched, in progress, verified, mismatch — and the colours drift
 *    so the map reads as live rather than as a static picture.
 *  - **Success rate** (the reference's side bar): one short bar per row of
 *    chunks, shaded by how much of that row is resolved.
 *  - **Agents** (where the reference has hit categories): one column per agent,
 *    side by side, with a bar for how much of its work is done.
 *  - **Recent activity** (the reference's recent logs): dot-only rows.
 *
 * The three bands share the height by weight, so no band can push another off
 * the bottom — which is what the previous layout did.
 */
@Composable
private fun OverviewTab(
    accent: Color,
    distro: ReaDistro.State,
    bridge: ReaBridge.State,
) {
    val calls by ReaBridge.calls.collectAsState()
    val logs by ReaBridge.logs.collectAsState()
    val chunks = remember(calls) { BinaryChunks.build(calls) }

    // Three bands that share the screen by weight, so nothing can push another
    // band off the bottom. The previous version gave the map weight(1f) inside a
    // plain Row, which made it swallow the whole column and shoved Agents and
    // Activity out of the screen.
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().weight(1.5f),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            // The map.
            PrismCard(accent = accent, modifier = Modifier.weight(1f).fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Binary map", fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text(
                        chunks.rows.size.toString() + " rows",
                        fontSize = 8.sp,
                        color = altarisMutedText(),
                    )
                }
                Spacer(Modifier.height(3.dp))
                BinaryChunkMap(
                    accent = accent,
                    chunks = chunks,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                Spacer(Modifier.height(2.dp))
                ChunkLegend(accent)
            }
            // Success rate per row: short bars, not a full-height rod.
            PrismCard(accent = accent, modifier = Modifier.width(66.dp).fillMaxSize()) {
                Text(
                    "Success rate",
                    fontSize = 7.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Spacer(Modifier.height(3.dp))
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.5.dp),
                ) {
                    chunks.rows.forEach { row ->
                        SuccessRateRow(accent, row, Modifier.fillMaxWidth().weight(1f))
                    }
                }
            }
        }

        // Agents on the left, the server's status on the right, split evenly.
        //
        // Status used to be its own tab, which meant the two things you check
        // together — who is connected and what the server is doing — were a tab
        // apart. Both fit here, so the tab is gone and its slot is the debugger.
        Row(
            modifier = Modifier.fillMaxWidth().weight(0.75f),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            AgentsCard(accent, calls, Modifier.weight(1f).fillMaxSize())
            ServerStatusCard(accent, distro, bridge, calls, Modifier.weight(1f).fillMaxSize())
        }

        // Recent activity.
        PrismCard(accent = accent, modifier = Modifier.weight(1f).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Recent activity", fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(logs.size.toString(), fontSize = 8.sp, color = altarisMutedText())
            }
            Spacer(Modifier.height(3.dp))
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (logs.isEmpty()) {
                    Text("no activity", fontSize = 8.sp, color = altarisMutedText())
                }
                logs.take(40).forEach { line -> DotRow(line, accent) }
            }
        }
    }
}

/**
 * The binary map.
 *
 * Rows of boxes, one box per chunk of the artifact. A chunk's colour is its
 * state, and the shading breathes so a live run is visibly moving. Rows are
 * labelled on the left like the reference's A–H.
 */
@Composable
private fun BinaryChunkMap(
    accent: Color,
    chunks: BinaryChunks.Map,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "chunks")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "chunkPulse",
    )
    val scheme = MaterialTheme.colorScheme

    Row(modifier = modifier) {
        // Row labels.
        Column(
            modifier = Modifier.width(12.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            chunks.rows.forEach { row ->
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(row.label, fontSize = 6.5.sp, color = altarisMutedText())
                }
            }
        }
        Spacer(Modifier.width(3.dp))
        Column(
            modifier = Modifier.weight(1f).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            chunks.rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    row.chunks.forEach { chunk ->
                        // A live sweep: chunks lighten in a moving band.
                        val wave = if (chunk.state == ChunkState.PROCESSING) {
                            0.15f + 0.25f * kotlin.math.abs(
                                kotlin.math.sin((pulse + chunk.index * 0.15f) * Math.PI).toFloat()
                            )
                        } else {
                            0f
                        }
                        val base = chunk.state.alpha
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .clip(RoundedCornerShape(1.5.dp))
                                .background(
                                    when (chunk.state) {
                                        ChunkState.UNTOUCHED ->
                                            scheme.outline.copy(alpha = 0.22f)
                                        ChunkState.PROCESSING ->
                                            accent.copy(alpha = (base + wave).coerceIn(0f, 0.9f))
                                        ChunkState.VERIFIED ->
                                            accent.copy(alpha = 0.16f)
                                        ChunkState.MISMATCH ->
                                            scheme.error.copy(alpha = (base + wave).coerceIn(0f, 0.9f))
                                        ChunkState.FAILED ->
                                            scheme.error.copy(alpha = 0.85f)
                                    }
                                )
                        )
                    }
                }
            }
        }
    }
}

/**
 * One row of the success-rate rail.
 *
 * A short bar plus its percentage, not a full-height rod: the rail is a summary
 * of eight rows, so each entry is a compact row of its own.
 */
@Composable
private fun SuccessRateRow(accent: Color, row: ChunkRow, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            row.label,
            fontSize = 6.5.sp,
            color = altarisMutedText(),
            modifier = Modifier.width(7.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(scheme.outline.copy(alpha = 0.20f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(row.rate.coerceIn(0f, 1f))
                    .height(4.dp)
                    .background(
                        if (row.failed > 0) scheme.error.copy(alpha = 0.70f)
                        else accent.copy(alpha = 0.35f + row.rate * 0.5f)
                    )
            )
        }
        Text(
            (row.rate * 100).toInt().toString(),
            fontSize = 6.sp,
            fontFamily = FontFamily.Monospace,
            color = altarisMutedText(),
            modifier = Modifier.width(14.dp),
        )
    }
}

@Composable
private fun ChunkLegend(accent: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        LegendDot("untouched", MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
        LegendDot("working", accent)
        LegendDot("verified", accent.copy(alpha = 0.30f))
        LegendDot("mismatch", MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(4.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(3.dp))
        Text(label, fontSize = 6.5.sp, color = altarisMutedText(), maxLines = 1)
    }
}

/**
 * Agents, side by side.
 *
 * The reference lays its categories out in a row, not stacked, so each agent
 * gets its own small column: name, a bar for how much of its work is done, and
 * the count. Up to four fit; more wrap onto a second line.
 */
@Composable
private fun AgentsCard(
    accent: Color,
    calls: List<ReaBridge.Call>,
    modifier: Modifier = Modifier,
) {
    val agents = remember(calls) { BinaryChunks.agents(calls) }
    PrismCard(accent = accent, modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Agents", fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(agents.size.toString(), fontSize = 8.sp, color = altarisMutedText())
        }
        Spacer(Modifier.height(3.dp))
        if (agents.isEmpty()) {
            Text("No agent connected yet", fontSize = 8.sp, color = altarisMutedText())
            return@PrismCard
        }
        // Two rows of columns: four across, then the rest below.
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            agents.chunked(4).take(2).forEach { line ->
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    line.forEach { agent ->
                        AgentColumn(accent, agent, Modifier.weight(1f).fillMaxSize())
                    }
                    // Keep the last line's columns the same width.
                    repeat(4 - line.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** One agent: name, done bar, count. */
@Composable
private fun AgentColumn(accent: Color, agent: AgentWork, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = modifier) {
        Text(
            agent.label,
            fontSize = 7.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(scheme.outline.copy(alpha = 0.22f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(agent.done.coerceIn(0f, 1f))
                    .height(4.dp)
                    .background(if (agent.failed > 0) scheme.error else accent)
            )
        }
        Spacer(Modifier.height(1.dp))
        Text(
            (agent.done * 100).toInt().toString() + "% · " + agent.calls,
            fontSize = 6.5.sp,
            fontFamily = FontFamily.Monospace,
            color = altarisMutedText(),
            maxLines = 1,
        )
    }
}

/** Dot-only activity rows, like the reference's recent logs. */
@Composable
private fun DotRow(line: ReaBridge.LogLine, accent: Color) {
    val color = when (line.level) {
        ReaBridge.LogLine.Level.ERROR -> MaterialTheme.colorScheme.error
        ReaBridge.LogLine.Level.CALL -> accent
        ReaBridge.LogLine.Level.RESULT -> accent.copy(alpha = 0.45f)
        ReaBridge.LogLine.Level.INFO -> MaterialTheme.colorScheme.outline
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(4.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(
            formatClock(line.at),
            fontSize = 7.5.sp,
            fontFamily = FontFamily.Monospace,
            color = altarisMutedText(),
            modifier = Modifier.width(44.dp),
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
    // Only real rows from real tool results.
    //
    // Every row here is a function, symbol or section REA actually returned
    // from the binary. Nothing is synthesised: when no tool has reported
    // anything, the tab says so instead of inventing plausible-looking rows.
    val rows = remember(calls) {
        calls.flatMap { call -> call.findings.map { call.tool to it } }
    }
    val shown = if (filter.isBlank()) rows else rows.filter {
        it.second.name.contains(filter, ignoreCase = true) ||
            it.second.offset.contains(filter, ignoreCase = true)
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
                "No functions yet. Ask the agent to open a binary — the name and " +
                    "address of every function REA finds in the file appear here. " +
                    "Nothing is shown until a tool has read the binary.",
            )
            return@PrismCard
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            items(shown.take(500)) { (_, row) ->
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
                    // The offset column shows an address or nothing. It used
                    // to fall back to the tool name, which read as an address
                    // and was not one.
                    Text(
                        row.offset,
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

/**
 * The server's state, beside the agent list.
 *
 * This was its own tab. It is now the right half of the Agents row in
 * Overview, because "who is connected" and "what is the server doing" are one
 * question, and a tab between them was pure navigation cost.
 */
@Composable
private fun ServerStatusCard(
    accent: Color,
    distro: ReaDistro.State,
    bridge: ReaBridge.State,
    calls: List<ReaBridge.Call>,
    modifier: Modifier = Modifier,
) {
    val status by ReaManager.status.collectAsState()
    PrismCard(accent = accent, modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Status", fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                if (bridge.running) "live" else "stopped",
                fontSize = 8.sp,
                color = if (bridge.running) accent else MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(3.dp))
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            DetailLine("Endpoint", ReaBridge.ENDPOINT, accent)
            DetailLine("Tools", bridge.toolCount.toString(), accent)
            DetailLine("Agents", bridge.connections.toString(), accent)
            DetailLine("Calls", calls.size.toString(), accent)
            DetailLine("REA", status.reaVersion.ifBlank { "—" }, accent)
            DetailLine("Node", status.nodeVersion.ifBlank { "—" }, accent)
            DetailLine("Distro", distro.target?.label ?: "—", accent)
        }
    }
}

// ------------------------------------------------------------------ debugger

/**
 * Live inspection of the app running in the clone.
 *
 * Not x64dbg: that is a Windows/x86 program and cannot run here. What this is
 * is the same job done natively — a console attached to the clone's process
 * through gdb (and Frida when it is available), inside the distro REA lives
 * in. Registers, memory, disassembly at an address, breakpoints, and module
 * bases are all real reads from the running process.
 */
@Composable
private fun DebuggerTab(accent: Color) {
    val context = LocalContext.current
    val session by ReaDebugger.state.collectAsState()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { ReaDebugger.refresh(context) }
    }

    PrismCard(accent = accent, modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Live debugger", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                session.attachedPid?.let { "pid " + it } ?: "not attached",
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                color = if (session.attachedPid != null) accent else altarisMutedText(),
            )
        }
        Spacer(Modifier.height(4.dp))
        PrismNote(
            "The agent drives this. It attaches to the app running in the clone and " +
                "reads registers, memory, mappings and backtraces from the live " +
                "process — so it answers runtime questions instead of guessing from " +
                "screenshots. The console below is the same session, if you want to " +
                "look or step in yourself. Attaching needs root.",
            fontSize = 9.sp,
        )
        Spacer(Modifier.height(3.dp))
        PrismNote(
            "Agent tools: altaris_debug_attach · altaris_debug_command · " +
                "altaris_debug_state · altaris_debug_detach",
            fontSize = 8.sp,
            color = altarisMutedText(),
        )
        Spacer(Modifier.height(4.dp))

        // Frida: the backend that can actually see and patch the clone.
        PrismRow(
            title = "Frida runtime",
            subtitle = when {
                session.fridaBusy.isNotBlank() -> session.fridaBusy
                session.fridaRunning && session.fridaClient ->
                    "Server on 127.0.0.1:" + com.altaris.linux.core.rea.ReaFrida.PORT +
                        " · CLI ready · injection and patching available"
                session.fridaRunning ->
                    "Server running · the CLI is missing in the distro"
                session.fridaInstalled -> "Installed, not running"
                else -> "Not installed · needed for runtime changes and injection"
            },
            accent = accent,
            leading = { PrismOrb(active = session.fridaRunning, accent = accent) },
            trailing = {
                PrismChip(
                    when {
                        session.fridaBusy.isNotBlank() -> "…"
                        session.fridaRunning -> "Stop"
                        else -> "Install"
                    },
                    accent,
                    selected = session.fridaRunning,
                    onClick = {
                        if (session.fridaBusy.isNotBlank()) return@PrismRow
                        if (session.fridaRunning) {
                            ReaDebugger.stopFrida(context)
                        } else {
                            ReaDebugger.installFrida(context)
                        }
                    },
                )
            },
        )
        Spacer(Modifier.height(4.dp))

        if (!session.available) {
            PrismNote(
                session.reason.ifBlank {
                    "No debugger backend yet. Install Frida above, or gdb from " +
                        "Tools → Reverse engineering."
                },
                color = MaterialTheme.colorScheme.error,
                fontSize = 9.sp,
            )
            return@PrismCard
        }

        // No attach control: the agent owns the session. This tab shows what
        // it is attached to and what it has read, so a person can follow along
        // without a second way to start a session the agent is not driving.
        PrismRow(
            title = if (session.attachedPid != null) "Attached" else "Waiting for the agent",
            subtitle = session.attachedPid?.let { "pid " + it } ?: "",
            accent = accent,
            leading = { PrismOrb(active = session.attachedPid != null, accent = accent) },
            trailing = {
                PrismChip(
                    if (session.attachedPid != null) "Live" else "Idle",
                    accent,
                    selected = session.attachedPid != null,
                )
            },
        )
        if (session.runningApps.isNotEmpty()) {
            Spacer(Modifier.height(3.dp))
            PrismNote("Running in the clone: " + session.runningApps.joinToString(", "), fontSize = 8.sp)
        }

        Spacer(Modifier.height(4.dp))
        // Quick actions: the reads that need a specific command syntax.
        Spacer(Modifier.height(4.dp))
        // Read-only transcript.
        //
        // This tab used to send gdb commands itself. That is wrong once the
        // agent owns the session: each command spawned a second `gdb -p` on
        // the same process, which is a different session with different state
        // and, at worst, a second ptrace attach. The transcript is the agent's
        // session, shown as it happens.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Transcript", fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (session.transcript.isNotBlank()) {
                PrismChip("Clear", accent) { ReaDebugger.clear() }
            }
        }
        Spacer(Modifier.height(2.dp))
        if (session.error.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            PrismNote(session.error, color = MaterialTheme.colorScheme.error, fontSize = 9.sp)
        }
        Spacer(Modifier.height(4.dp))
        if (session.transcript.isBlank()) {
            PrismNote(
                "Nothing yet. The agent attaches when it needs a runtime answer; " +
                    "everything it runs appears here.",
                fontSize = 9.sp,
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    session.transcript.takeLast(6000),
                    fontSize = 8.5.sp,
                    lineHeight = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

// --------------------------------------------------------------------- other

/**
 * Everything else an app or game can be reverse-engineered with.
 *
 * The main toolchain covers binaries and APKs. Games and apps ship their real
 * content in engine containers — Unity `.assets`/`.bundle`, Unreal `.pak`,
 * Godot `.pck` — and none of that is readable with objdump. This tab is the
 * second half: asset extractors, engine unpackers and format converters, each
 * with the command that actually works on it.
 */
@Composable
private fun OtherTab(accent: Color) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by ReaAssetTools.state.collectAsState()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { ReaAssetTools.refresh(context) }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        PrismCard(accent = accent) {
            Text("Asset & engine tools", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            PrismNote(
                "Installed into the same distro as REA. Each row shows the command " +
                    "that works on that format, so the agent — or you — has the " +
                    "right invocation without guessing.",
                fontSize = 9.sp,
            )
            Spacer(Modifier.height(4.dp))
            PrismSecondaryButton(
                text = if (state.running) "Installing…" else "Install all missing",
                accent = accent,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.running,
            ) {
                scope.launch {
                    withContext(Dispatchers.IO) { ReaAssetTools.installAll(context) }
                }
            }
        }

        ReaAssetTools.GROUPS.forEach { (group, tools) ->
            PrismCard(accent = accent) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(group, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    val done = tools.count { it.id in state.installed }
                    Text("$done / ${tools.size}", fontSize = 8.sp, color = altarisMutedText())
                }
                Spacer(Modifier.height(3.dp))
                tools.forEach { tool ->
                    val installed = tool.id in state.installed
                    val busy = state.busyId == tool.id
                    PrismRow(
                        title = tool.name,
                        subtitle = if (installed) {
                            tool.use + "\n" + tool.command
                        } else {
                            tool.use + " · " + tool.size
                        },
                        accent = accent,
                        divider = true,
                        leading = { PrismOrb(active = installed, accent = accent) },
                        trailing = {
                            PrismChip(
                                when {
                                    busy -> "…"
                                    installed -> "Ready"
                                    else -> "Install"
                                },
                                accent,
                                selected = installed,
                                onClick = if (busy || installed) {
                                    null
                                } else {
                                    ({
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                ReaAssetTools.install(context, tool)
                                            }
                                        }
                                    })
                                },
                            )
                        },
                    )
                }
            }
        }

        if (state.log.isNotBlank()) {
            PrismCard(accent = accent) {
                Text("Log", fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text(
                    state.log.takeLast(3000),
                    fontSize = 8.sp,
                    lineHeight = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
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
