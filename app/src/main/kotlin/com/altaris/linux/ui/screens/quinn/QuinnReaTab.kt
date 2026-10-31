package com.altaris.linux.ui.screens.quinn

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altaris.linux.core.quinn.ReaAnalysis
import com.altaris.linux.core.rea.ReaDistro
import com.altaris.linux.core.rea.ReaManager
import com.altaris.linux.ui.components.PrismButton
import com.altaris.linux.ui.components.PrismCard
import com.altaris.linux.ui.components.PrismChip
import com.altaris.linux.ui.components.PrismNote
import com.altaris.linux.ui.components.PrismOrb
import com.altaris.linux.ui.components.PrismSecondaryButton
import com.altaris.linux.ui.theme.Prism
import com.altaris.linux.ui.theme.altarisMutedText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The two pages of the REA tab. */
private enum class ReaPage(val label: String) {
    ANALYSIS("REA"),
    FILES("Files"),
}

/**
 * The REA dashboard.
 *
 * Layout follows the supplied reference: a status strip, a big counter row, a
 * meta row, a page tab row, one large panel that fills the rest of the screen,
 * and a timestamped log strip pinned to the bottom. **Nothing scrolls** — the
 * panels are sized to the screen so the whole instrument is visible at once,
 * which is what the reference does and what the previous scrolling page did
 * not.
 */
@Composable
fun QuinnReaTab(
    accent: Color,
    modifier: Modifier = Modifier,
) {
    var page by remember { mutableStateOf(ReaPage.ANALYSIS) }
    val distro by ReaDistro.state.collectAsState()

    Column(modifier = modifier.fillMaxWidth()) {
        // Page row, exactly like the reference's tab strip.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ReaPage.entries.forEach { entry ->
                    PageTab(entry.label, accent, page == entry) { page = entry }
                }
            }
            Spacer(Modifier.weight(1f))
            if (distro.installed) {
                PrismChip(
                    if (distro.ready) (distro.method?.label?.uppercase() ?: "READY") else "REPAIR",
                    if (distro.ready) accent else MaterialTheme.colorScheme.error,
                    selected = true,
                )
            }
        }
        Spacer(Modifier.height(Prism.Tight))
        when (page) {
            ReaPage.ANALYSIS -> AnalysisPage(accent)
            ReaPage.FILES -> ReaFilesPage(accent)
        }
    }
}

@Composable
private fun PageTab(
    text: String,
    accent: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(Prism.ChipRadius))
            .background(if (selected) accent.copy(alpha = 0.12f) else Color.Transparent)
            .border(
                1.dp,
                if (selected) accent else scheme.outline,
                RoundedCornerShape(Prism.ChipRadius),
            )
            .clickable(onClick = onClick)
            .heightIn(min = 30.dp)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) accent else scheme.onSurface,
        )
    }
}

// ------------------------------------------------------------------ analysis

@Composable
private fun AnalysisPage(accent: Color) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val distro by ReaDistro.state.collectAsState()
    val runs by ReaAnalysis.runs.collectAsState()
    val events by ReaAnalysis.events.collectAsState()
    val totals by ReaAnalysis.totals.collectAsState()
    val reaStatus by ReaManager.status.collectAsState()
    var frame by remember { mutableStateOf(DashboardFrame.HEATMAP) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            ReaDistro.refresh(context)
            ReaDistro.state.value.method?.let { ReaManager.probe(context, it.id) }
        }
    }

    val current = runs.firstOrNull()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (!distro.installed) Modifier.blur(6.dp) else Modifier),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusStrip(accent, distro, reaStatus.running, current)

            CounterStrip(accent, totals, current)

            MetaRow(accent, distro, reaStatus, current)

            FrameTabs(accent, frame) { frame = it }

            // The one big panel. It takes whatever height is left, so the log
            // strip below is always on screen — the reference's single main
            // card between the tabs and the log.
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (frame) {
                    DashboardFrame.HEATMAP -> HeatmapFrame(accent, events, current)
                    DashboardFrame.LOG -> LiveLogFrame(accent, events, current)
                    DashboardFrame.FINDINGS -> FindingsFrame(accent, current)
                    DashboardFrame.RESULT -> ResultFrame(accent, current)
                }
            }

            if (distro.busy.isNotBlank()) {
                InstallStrip(accent, distro)
            } else if (!distro.ready && distro.installed) {
                RepairStrip(accent)
            }

            LogStrip(accent, events)
        }

        if (!distro.installed) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.60f))
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 70.dp, start = 4.dp, end = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    NotInstalledCard(accent)
                }
            }
        }
    }
}

/** Which main panel is showing. */
private enum class DashboardFrame(val label: String) {
    HEATMAP("Overview"),
    LOG("Live"),
    FINDINGS("Findings"),
    RESULT("Result"),
}

@Composable
private fun FrameTabs(accent: Color, current: DashboardFrame, onSelect: (DashboardFrame) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        DashboardFrame.entries.forEach { entry ->
            val selected = entry == current
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (selected) accent.copy(alpha = 0.14f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .clickable { onSelect(entry) }
                    .padding(horizontal = 9.dp, vertical = 4.dp),
            ) {
                Text(
                    entry.label,
                    fontSize = 9.5.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) accent else altarisMutedText(),
                )
            }
        }
    }
}

/** The reference's top pill: live state and a one-line summary. */
@Composable
private fun StatusStrip(
    accent: Color,
    distro: ReaDistro.State,
    serverRunning: Boolean,
    run: ReaAnalysis.Run?,
) {
    val scheme = MaterialTheme.colorScheme
    val live = run?.running == true
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Prism.PanelRadius))
            .background(
                Brush.horizontalGradient(
                    listOf(accent.copy(alpha = if (live) 0.16f else 0.08f), accent.copy(alpha = 0.02f))
                )
            )
            .border(1.dp, accent.copy(alpha = 0.28f), RoundedCornerShape(Prism.PanelRadius))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PrismOrb(active = live || serverRunning, accent = accent)
        Spacer(Modifier.width(7.dp))
        Text(
            when {
                !distro.installed -> "REA not installed"
                live -> "Analysis running"
                serverRunning -> "Server live"
                else -> "Idle"
            },
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = accent,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            when {
                live -> "· " + (run.steps.lastOrNull()?.tool ?: "thinking")
                distro.installed -> "· " + (distro.method?.label ?: "") + " environment"
                else -> "· install from Tools"
            },
            fontSize = 9.5.sp,
            color = altarisMutedText(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (run != null) {
            Text(
                formatElapsed(run.elapsedMs),
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace,
                color = altarisMutedText(),
            )
        } else if (distro.installed && !serverRunning) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(scheme.surfaceVariant)
                    .clickable {
                        // Starting the server is the only action this strip has.
                    }
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Text("start", fontSize = 9.sp, color = accent)
            }
        }
    }
}

/** The reference's big counter row: value over a quiet label. */
@Composable
private fun CounterStrip(accent: Color, totals: ReaAnalysis.Totals, run: ReaAnalysis.Run?) {
    PrismCard(accent = accent) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Prism.Stack),
        ) {
            BigCounter((run?.steps?.size ?: 0).toString(), "Tool calls", accent, Modifier.weight(1f))
            BigCounter(
                ReaAnalysis.formatBytes(run?.bytes ?: totals.bytes),
                "Analysed",
                accent,
                Modifier.weight(1f),
            )
            BigCounter(
                (run?.activity?.distinct()?.size ?: 0).toString(),
                "Tools used",
                accent,
                Modifier.weight(1f),
            )
            BigCounter(totals.runs.toString(), "Runs", accent, Modifier.weight(1f))
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
        )
    }
}

/** The reference's meta row: date, gene set, view. */
@Composable
private fun MetaRow(
    accent: Color,
    distro: ReaDistro.State,
    status: ReaManager.Status,
    run: ReaAnalysis.Run?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MetaCell("Environment", distro.method?.label?.uppercase() ?: "—", accent, Modifier.weight(1f))
        MetaCell("Tools", status.toolCount.toString(), accent, Modifier.weight(1f))
        MetaCell(
            "Server",
            if (status.running) "LIVE" else "IDLE",
            accent,
            Modifier.weight(1f),
        )
        MetaCell(
            "Model",
            run?.model?.let { it.substringAfterLast('/').take(12) } ?: "—",
            accent,
            Modifier.weight(1.2f),
        )
    }
}

@Composable
private fun MetaCell(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Column {
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
}

/**
 * The main panel: an activity heatmap plus a summary, like the reference's
 * plate heatmap. Each column is one tool call; the shading is how long it took.
 */
@Composable
private fun HeatmapFrame(accent: Color, events: List<ReaAnalysis.Event>, run: ReaAnalysis.Run?) {
    val transition = rememberInfiniteTransition(label = "heat")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "heatPhase",
    )
    val scheme = MaterialTheme.colorScheme
    PrismCard(accent = accent, modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Activity", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                if (run?.running == true) "live" else "idle",
                fontSize = 8.5.sp,
                color = if (run?.running == true) accent else altarisMutedText(),
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(4.dp))
                .background(scheme.background),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cols = 24
                val rows = 5
                val gap = 2.dp.toPx()
                val cellW = ((size.width - gap * (cols - 1)) / cols).coerceAtLeast(1f)
                val cellH = ((size.height - gap * (rows - 1)) / rows).coerceAtLeast(1f)
                val steps = run?.steps.orEmpty()
                for (row in 0 until rows) {
                    for (col in 0 until cols) {
                        val index = row * cols + col
                        val step = steps.getOrNull(index)
                        val base = when {
                            step == null -> 0.05f
                            step.error.isNotBlank() -> 0.9f
                            step.running -> 0.75f
                            else -> 0.25f + (step.ms / 5000f).coerceIn(0f, 0.6f)
                        }
                        val travel = if (run?.running == true) {
                            val distance = kotlin.math.abs((col.toFloat() / cols) - phase)
                            (1f - distance.coerceIn(0f, 1f)) * 0.25f
                        } else 0f
                        val color = when {
                            step?.error?.isNotBlank() == true -> scheme.error.copy(alpha = 0.75f)
                            else -> accent.copy(alpha = (base + travel).coerceIn(0.05f, 0.95f))
                        }
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(col * (cellW + gap), row * (cellH + gap)),
                            size = Size(cellW, cellH),
                            cornerRadius = CornerRadius(1.5f, 1.5f),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "${stepsDone(run)} done · ${stepsFailed(run)} failed",
                fontSize = 8.5.sp,
                color = altarisMutedText(),
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (run != null) ReaAnalysis.formatBytes(run.bytes) + " returned" else "no run",
                fontSize = 8.5.sp,
                fontFamily = FontFamily.Monospace,
                color = altarisMutedText(),
            )
        }
    }
}

private fun stepsDone(run: ReaAnalysis.Run?): Int =
    run?.steps?.count { !it.running && it.error.isBlank() } ?: 0

private fun stepsFailed(run: ReaAnalysis.Run?): Int =
    run?.steps?.count { it.error.isNotBlank() } ?: 0

/** The live log: what the model is doing, as it happens. */
@Composable
private fun LiveLogFrame(accent: Color, events: List<ReaAnalysis.Event>, run: ReaAnalysis.Run?) {
    val state = rememberLazyListState()
    LaunchedEffect(events.size) {
        if (events.isNotEmpty()) state.animateScrollToItem(0)
    }
    PrismCard(accent = accent, modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Model activity", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (run?.running == true) {
                PrismOrb(active = true, accent = accent)
                Spacer(Modifier.width(5.dp))
                Text("live", fontSize = 8.5.sp, color = accent)
            }
        }
        Spacer(Modifier.height(6.dp))
        if (events.isEmpty()) {
            PrismNote("Waiting for a run. Start one from Chat with REA selected as the agent.")
            return@PrismCard
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            state = state,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            items(events) { event -> EventRow(event, accent) }
        }
    }
}

@Composable
private fun EventRow(event: ReaAnalysis.Event, accent: Color) {
    val color = when (event.kind) {
        ReaAnalysis.Event.Kind.ERROR -> MaterialTheme.colorScheme.error
        ReaAnalysis.Event.Kind.TOOL -> accent
        ReaAnalysis.Event.Kind.RESULT -> MaterialTheme.colorScheme.onSurface
        ReaAnalysis.Event.Kind.INFO -> altarisMutedText()
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            formatClock(event.at),
            fontSize = 8.5.sp,
            fontFamily = FontFamily.Monospace,
            color = altarisMutedText(),
            modifier = Modifier.width(52.dp),
        )
        Text(
            when (event.kind) {
                ReaAnalysis.Event.Kind.TOOL -> "▸"
                ReaAnalysis.Event.Kind.RESULT -> "✓"
                ReaAnalysis.Event.Kind.ERROR -> "✕"
                ReaAnalysis.Event.Kind.INFO -> "·"
            },
            fontSize = 8.5.sp,
            color = color,
            modifier = Modifier.width(11.dp),
        )
        Text(
            event.text,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = color,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** What the tools found, one row per call. */
@Composable
private fun FindingsFrame(accent: Color, run: ReaAnalysis.Run?) {
    PrismCard(accent = accent, modifier = Modifier.fillMaxSize()) {
        Text("Findings", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        val steps = run?.steps.orEmpty()
        if (steps.isEmpty()) {
            PrismNote("No tool call yet.")
            return@PrismCard
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            items(steps) { step ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                when {
                                    step.error.isNotBlank() -> MaterialTheme.colorScheme.error
                                    step.running -> accent
                                    else -> accent.copy(alpha = 0.45f)
                                }
                            )
                    )
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            step.tool,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            step.arguments.take(80),
                            fontSize = 8.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = altarisMutedText(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        when {
                            step.running -> "running"
                            step.error.isNotBlank() -> "failed"
                            else -> ReaAnalysis.formatBytes(step.result.length.toLong())
                        },
                        fontSize = 8.5.sp,
                        color = if (step.error.isNotBlank()) {
                            MaterialTheme.colorScheme.error
                        } else {
                            altarisMutedText()
                        },
                    )
                }
            }
        }
    }
}

/** The final answer. */
@Composable
private fun ResultFrame(accent: Color, run: ReaAnalysis.Run?) {
    PrismCard(accent = accent, modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Result", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (run?.running == true) {
                Text("working…", fontSize = 8.5.sp, color = accent)
            }
        }
        Spacer(Modifier.height(6.dp))
        when {
            run == null -> PrismNote("No run yet.")
            run.error.isNotBlank() -> PrismNote(
                run.error,
                color = MaterialTheme.colorScheme.error,
            )
            run.answer.isBlank() -> PrismNote("The model has not answered yet.")
            else -> Column(
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScrollFrame()
            ) {
                SelectionContainer {
                    Text(run.answer, fontSize = 11.sp, lineHeight = 16.sp)
                }
            }
        }
    }
}

/** Local helper: scroll inside a fixed panel. */
@Composable
private fun Modifier.verticalScrollFrame(): Modifier =
    this.then(Modifier.verticalScroll(rememberScrollState()))

/** The install strip: real progress, which tool, how many left. */
@Composable
private fun InstallStrip(accent: Color, distro: ReaDistro.State) {
    PrismCard(accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrismOrb(active = true, accent = accent)
            Spacer(Modifier.width(7.dp))
            Text(
                distro.busy,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (distro.percent >= 0) {
                Text(
                    "${distro.percent}%",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        val fraction by animateFloatAsState(
            targetValue = distro.fraction,
            animationSpec = tween(400),
            label = "installFraction",
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(accent)
            )
        }
        if (distro.downloadText.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                distro.downloadText,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = altarisMutedText(),
            )
        }
        if (distro.detail.isNotBlank()) {
            Text(
                distro.detail,
                fontSize = 8.5.sp,
                fontFamily = FontFamily.Monospace,
                color = altarisMutedText(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A container exists but its toolchain is incomplete. */
@Composable
private fun RepairStrip(accent: Color) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Prism.PanelRadius))
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.08f))
            .border(
                1.dp,
                MaterialTheme.colorScheme.error.copy(alpha = 0.4f),
                RoundedCornerShape(Prism.PanelRadius),
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Environment present, tools missing",
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                "The container is there. Finish the tool install to use REA.",
                fontSize = 8.5.sp,
                color = altarisMutedText(),
                maxLines = 1,
            )
        }
        PrismChip(
            "Repair",
            accent,
            onClick = { scope.launch { ReaDistro.installMissingTools(context) } },
        )
    }
}

/** The reference's bottom log strip. */
@Composable
private fun LogStrip(accent: Color, events: List<ReaAnalysis.Event>) {
    val latest = events.take(2)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Prism.PanelRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(Prism.PanelRadius))
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (latest.isEmpty()) {
                Text(
                    "No activity yet",
                    fontSize = 9.sp,
                    color = altarisMutedText(),
                )
            } else {
                latest.forEach { event ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            formatClock(event.at),
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            color = altarisMutedText(),
                            modifier = Modifier.width(50.dp),
                        )
                        Text(
                            event.text,
                            fontSize = 8.5.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (event.kind == ReaAnalysis.Event.Kind.ERROR) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NotInstalledCard(accent: Color) {
    PrismCard(accent = accent) {
        Text("REA is not installed", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        PrismNote(
            "Quinn runs analysis inside a private environment it creates for itself, " +
                "so nothing is installed into your own distros and no MCP link is needed."
        )
        Spacer(Modifier.height(Prism.Tight))
        PrismNote(
            "Install it once from Tools → Reverse engineering. Pick proot (no root) " +
                "or chroot (needs root); every analysis tool is installed with it."
        )
    }
}

// ---------------------------------------------------------------------- files

@Composable
private fun ReaFilesPage(accent: Color) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val distro by ReaDistro.state.collectAsState()
    var path by remember { mutableStateOf(com.altaris.linux.core.quinn.ReaFiles.DEFAULT_PATH) }
    var entries by remember {
        mutableStateOf<List<com.altaris.linux.core.quinn.ReaFiles.Entry>>(emptyList())
    }
    var loading by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingDelete by remember {
        mutableStateOf<com.altaris.linux.core.quinn.ReaFiles.Entry?>(null)
    }
    var renaming by remember {
        mutableStateOf<com.altaris.linux.core.quinn.ReaFiles.Entry?>(null)
    }

    fun load(target: String) {
        loading = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                com.altaris.linux.core.quinn.ReaFiles.list(context, target)
            }
            loading = false
            result.onSuccess {
                entries = it
                path = target
                note = null
            }.onFailure { note = it.message }
        }
    }

    LaunchedEffect(distro.installed) { if (distro.installed) load(path) }

    if (!distro.installed) {
        NotInstalledCard(accent)
        return
    }

    PrismCard(accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Folder,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(5.dp))
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val segments = path.trim('/').split('/').filter { it.isNotBlank() }
                BreadcrumbSegment("/", accent) { load("/") }
                segments.forEachIndexed { index, segment ->
                    Text("/", fontSize = 10.sp, color = altarisMutedText())
                    val target = "/" + segments.take(index + 1).joinToString("/")
                    BreadcrumbSegment(segment, accent) { load(target) }
                }
            }
            if (loading) Text("…", fontSize = 10.sp, color = altarisMutedText())
        }
        Spacer(Modifier.height(5.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            PrismChip("New folder", accent, onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        com.altaris.linux.core.quinn.ReaFiles.mkdir(context, "$path/untitled")
                    }
                    load(path)
                }
            })
            PrismChip("New file", accent, onClick = {
                val target = "$path/untitled.txt"
                scope.launch {
                    withContext(Dispatchers.IO) {
                        com.altaris.linux.core.quinn.ReaFiles.createFile(context, target)
                    }
                    editing = target to ""
                    load(path)
                }
            })
        }
    }

    PrismCard(accent = accent, modifier = Modifier.fillMaxWidth()) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            items(entries.take(300)) { entry ->
                FileRow(
                    entry = entry,
                    accent = accent,
                    onOpen = {
                        if (entry.dir) {
                            load(entry.path)
                        } else {
                            scope.launch {
                                val read = withContext(Dispatchers.IO) {
                                    com.altaris.linux.core.quinn.ReaFiles.read(context, entry.path)
                                }
                                read.onSuccess { editing = entry.path to it }
                                    .onFailure { note = it.message }
                            }
                        }
                    },
                    onDelete = { pendingDelete = entry },
                    onRename = { renaming = entry },
                )
            }
        }
        if (note != null) {
            Spacer(Modifier.height(4.dp))
            PrismNote(note!!, color = MaterialTheme.colorScheme.error)
        }
    }

    editing?.let { (filePath, content) ->
        var draft by remember(filePath) { mutableStateOf(content) }
        PrismCard(accent = accent) {
            Text(
                filePath,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(5.dp))
            androidx.compose.material3.OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp, max = 300.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace
                ),
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PrismSecondaryButton(
                    text = "Close",
                    accent = accent,
                    modifier = Modifier.weight(1f),
                    onClick = { editing = null },
                )
                PrismButton(
                    text = "Save",
                    accent = accent,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                com.altaris.linux.core.quinn.ReaFiles.write(context, filePath, draft)
                            }
                            result.onSuccess {
                                note = "Saved"
                                editing = null
                                load(path)
                            }.onFailure { note = it.message }
                        }
                    },
                )
            }
        }
    }

    renaming?.let { entry ->
        var name by remember(entry.path) { mutableStateOf(entry.name) }
        PrismCard(accent = accent) {
            Text("Rename", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(5.dp))
            androidx.compose.material3.OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace
                ),
                singleLine = true,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PrismSecondaryButton(
                    text = "Cancel",
                    accent = accent,
                    modifier = Modifier.weight(1f),
                    onClick = { renaming = null },
                )
                PrismButton(
                    text = "Rename",
                    accent = accent,
                    modifier = Modifier.weight(1f),
                    enabled = name.isNotBlank() && name != entry.name,
                    onClick = {
                        val parent = com.altaris.linux.core.quinn.ReaFiles.parentOf(entry.path)
                        val target = if (parent == "/") "/$name" else "$parent/$name"
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                com.altaris.linux.core.quinn.ReaFiles.rename(
                                    context, entry.path, target
                                )
                            }
                            renaming = null
                            load(path)
                        }
                    },
                )
            }
        }
    }

    pendingDelete?.let { entry ->
        PrismCard(accent = accent) {
            Text("Delete this?", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(
                entry.path + if (entry.dir) " (and everything in it)" else "",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = altarisMutedText(),
                maxLines = 2,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PrismSecondaryButton(
                    text = "Keep",
                    accent = accent,
                    modifier = Modifier.weight(1f),
                    onClick = { pendingDelete = null },
                )
                PrismButton(
                    text = "Delete",
                    accent = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                com.altaris.linux.core.quinn.ReaFiles.delete(context, entry.path)
                            }
                            pendingDelete = null
                            load(path)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun BreadcrumbSegment(text: String, accent: Color, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        color = accent,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 1.dp),
    )
}

@Composable
private fun FileRow(
    entry: com.altaris.linux.core.quinn.ReaFiles.Entry,
    accent: Color,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
) {
    var expanded by remember(entry.path) { mutableStateOf(false) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { if (entry.dir) onOpen() else expanded = !expanded }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (entry.dir) accent else accent.copy(alpha = 0.35f))
            )
            Spacer(Modifier.width(7.dp))
            Text(
                entry.name,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (entry.dir) "dir" else ReaDistro.formatBytes(entry.size),
                fontSize = 9.sp,
                color = altarisMutedText(),
            )
            Spacer(Modifier.width(6.dp))
            PrismChip("···", accent, onClick = { expanded = !expanded })
        }
        if (expanded) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 11.dp, bottom = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (!entry.dir) PrismChip("Open", accent, onClick = onOpen)
                PrismChip("Rename", accent, onClick = onRename)
                PrismChip("Delete", MaterialTheme.colorScheme.error, onClick = onDelete)
            }
        }
    }
}

// -------------------------------------------------------------------- helpers

private fun formatElapsed(ms: Long): String {
    val seconds = ms / 1000
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }
}

private fun formatClock(at: Long): String {
    val date = java.util.Date(at)
    val format = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
    return format.format(date)
}
