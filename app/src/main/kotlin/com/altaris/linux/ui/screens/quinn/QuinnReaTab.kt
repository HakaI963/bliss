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
import com.altaris.linux.core.quinn.QuinnModelConfig
import com.altaris.linux.core.quinn.ReaAnalysis
import com.altaris.linux.core.rea.ReaDistro
import com.altaris.linux.core.rea.ReaManager
import com.altaris.linux.ui.components.PrismButton
import com.altaris.linux.ui.components.PrismCard
import com.altaris.linux.ui.components.PrismChip
import com.altaris.linux.ui.components.PrismNote
import com.altaris.linux.ui.components.PrismOrb
import com.altaris.linux.ui.components.PrismSecondaryButton
import com.altaris.linux.ui.components.PrismSectionLabel
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
 * The REA analysis surface.
 *
 * This page has **no prompt box**: a run is started from Quinn's chat, where
 * you attach a file and pick REA as the agent. What lives here is the live
 * instrument — what REA is doing right now, the counters, the activity strip,
 * the tool-call ticker and the result — plus the model the environment runs on.
 *
 * Information design follows the reference recording (brand block, big
 * counters, an exhibit rail, an animated activity strip, a proof panel), stacked
 * for a phone instead of three desktop columns.
 */
@Composable
fun QuinnReaTab(
    accent: Color,
    modifier: Modifier = Modifier,
) {
    var page by remember { mutableStateOf(ReaPage.ANALYSIS) }
    val distro by ReaDistro.state.collectAsState()

    Column(modifier = modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ReaPage.entries.forEach { entry ->
                PageChip(entry.label, accent, page == entry) { page = entry }
            }
            Spacer(Modifier.weight(1f))
            if (distro.installed) {
                PrismChip(
                    distro.method?.label?.uppercase() ?: "READY",
                    accent,
                    selected = true,
                )
            }
        }
        Spacer(Modifier.height(Prism.Stack))
        when (page) {
            ReaPage.ANALYSIS -> AnalysisPage(accent)
            ReaPage.FILES -> ReaFilesPage(accent)
        }
    }
}

@Composable
private fun PageChip(
    text: String,
    accent: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Prism.ChipRadius)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.12f) else Color.Transparent)
            .border(1.dp, if (selected) accent else scheme.outline, shape)
            .clickable(onClick = onClick)
            .heightIn(min = 30.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
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
    val busy by ReaAnalysis.busy.collectAsState()
    val reaStatus by ReaManager.status.collectAsState()
    var showModel by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            ReaDistro.refresh(context)
            if (ReaDistro.state.value.installed) ReaManager.probe(context, ReaDistro.distroId(context).orEmpty())
        }
    }

    val current = runs.firstOrNull()

    Box(modifier = Modifier.fillMaxWidth()) {
        // The content layer is what gets blurred: a blur on a flat fill is a
        // no-op, which is why the first "frosted" gate looked like a plain card.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .then(if (!distro.installed) Modifier.blur(6.dp) else Modifier),
            verticalArrangement = Arrangement.spacedBy(Prism.Stack),
        ) {
            BrandHeader(accent, distro, reaStatus.running, current)

            if (!distro.installed) {
                // Rendered behind the gate; the gate is drawn on top below.
                DashboardPlaceholder(accent)
            } else {
                if (distro.busy.isNotBlank()) {
                    InstallProgress(accent, distro)
                }

                CounterRow(accent, totals, current)

                ActivityStrip(accent, events, busy)

                if (current != null) {
                    LiveRun(accent, current)
                } else {
                    IdleCard(accent)
                }

                EnvironmentCard(
                    accent = accent,
                    distro = distro,
                    toolCount = reaStatus.toolCount,
                    serverRunning = reaStatus.running,
                    onStart = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                ReaManager.start(context, ReaDistro.distroId(context).orEmpty())
                            }
                        }
                    },
                    onInstallMissing = {
                        scope.launch { ReaDistro.installMissingTools(context) }
                    },
                    busy = distro.busy.isNotBlank(),
                )

                ModelSummary(accent, onOpen = { showModel = !showModel })
                if (showModel) ModelEditor(accent)

                if (events.isNotEmpty()) {
                    PrismSectionLabel("Activity", accent)
                    PrismCard(accent = accent) {
                        events.take(14).forEach { event -> EventRow(event, accent) }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        // Frosted gate: a translucent scrim over the blurred content, with the
        // message and the install pointer on top.
        if (!distro.installed) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.62f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 90.dp, start = 4.dp, end = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    NotInstalledCard(accent)
                }
            }
        }
    }
}

@Composable
private fun BrandHeader(
    accent: Color,
    distro: ReaDistro.State,
    running: Boolean,
    run: ReaAnalysis.Run?,
) {
    val scheme = MaterialTheme.colorScheme
    val pulse = rememberInfiniteTransition(label = "brandPulse")
    val glow by pulse.animateFloat(
        initialValue = 0.10f,
        targetValue = 0.26f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "brandGlow",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Prism.PanelRadius))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        accent.copy(alpha = if (running) glow else 0.10f),
                        accent.copy(alpha = 0.03f),
                    )
                )
            )
            .border(1.dp, accent.copy(alpha = 0.30f), RoundedCornerShape(Prism.PanelRadius))
            .padding(Prism.PanelPad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PrismOrb(active = running || run?.running == true, accent = accent)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "ALTARIS × REA",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.4.sp,
                color = accent,
            )
            Text(
                when {
                    !distro.installed -> "Analysis environment not installed"
                    run?.running == true -> "Analysing · " + (run.steps.lastOrNull()?.tool ?: "thinking")
                    running -> "Server live · waiting for a run"
                    else -> "Server idle"
                },
                fontSize = 11.sp,
                color = altarisMutedText(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                if (distro.installed) "READY" else "OFFLINE",
                fontSize = 9.5.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
                color = if (distro.installed) accent else scheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(Prism.ChipRadius))
                    .background(
                        if (distro.installed) accent.copy(alpha = 0.14f)
                        else scheme.surfaceVariant
                    )
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
            if (run != null) {
                Text(
                    formatElapsed(run.elapsedMs),
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = altarisMutedText(),
                )
            }
        }
    }
}

/** The recording's big counters, driven by real numbers. */
@Composable
private fun CounterRow(accent: Color, totals: ReaAnalysis.Totals, run: ReaAnalysis.Run?) {
    PrismCard(accent = accent) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Prism.Stack),
        ) {
            BigCounter(
                value = (run?.steps?.size ?: 0).toString(),
                label = "tool calls",
                accent = accent,
                modifier = Modifier.weight(1f),
            )
            BigCounter(
                value = ReaAnalysis.formatBytes(run?.bytes ?: totals.bytes),
                label = "analysed",
                accent = accent,
                modifier = Modifier.weight(1f),
            )
            BigCounter(
                value = totals.runs.toString(),
                label = "runs",
                accent = accent,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BigCounter(
    value: String,
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            value,
            fontSize = 21.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.6).sp,
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            label.uppercase(),
            fontSize = 8.5.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.7.sp,
            color = altarisMutedText(),
            maxLines = 1,
        )
    }
}

/**
 * The animated activity strip.
 *
 * Each bar is a tool call or event; the newest bar grows while the run is
 * live. This is the recording's "agents on the diff" heat strip, fed by real
 * events instead of decoration.
 */
@Composable
private fun ActivityStrip(accent: Color, events: List<ReaAnalysis.Event>, busy: Boolean) {
    val transition = rememberInfiniteTransition(label = "strip")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "stripPhase",
    )
    val scheme = MaterialTheme.colorScheme
    // Oldest → newest, so the strip reads left to right like a timeline.
    val bars = events.take(28).reversed()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(Prism.PanelRadius))
            .background(scheme.surfaceVariant)
            .border(1.dp, scheme.outline, RoundedCornerShape(Prism.PanelRadius))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val count = 28
            val gap = 3.dp.toPx()
            val barWidth = ((size.width - gap * (count - 1)) / count).coerceAtLeast(1f)
            for (i in 0 until count) {
                val index = bars.size - count + i
                val event = if (index >= 0 && index < bars.size) bars[index] else null
                // A settled height encodes the event kind; a live run adds a
                // travelling highlight so the strip is never static.
                val base = when (event?.kind) {
                    ReaAnalysis.Event.Kind.TOOL -> 0.85f
                    ReaAnalysis.Event.Kind.RESULT -> 0.55f
                    ReaAnalysis.Event.Kind.ERROR -> 0.95f
                    ReaAnalysis.Event.Kind.INFO -> 0.35f
                    null -> 0.12f
                }
                val travel = if (busy) {
                    val distance = kotlin.math.abs(((i.toFloat() / count) - phase))
                    1f - distance.coerceIn(0f, 1f)
                } else 0f
                val height = size.height * (base * 0.72f + travel * 0.28f)
                val color = when (event?.kind) {
                    ReaAnalysis.Event.Kind.ERROR -> scheme.error.copy(alpha = 0.8f)
                    null -> scheme.outline.copy(alpha = 0.5f)
                    else -> accent.copy(alpha = 0.30f + base * 0.55f)
                }
                drawRoundRect(
                    color = color,
                    topLeft = Offset(i * (barWidth + gap), size.height - height),
                    size = Size(barWidth, height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5f, 1.5f),
                )
            }
        }
    }
}

/** The current run: steps as they happen, then the answer. */
@Composable
private fun LiveRun(accent: Color, run: ReaAnalysis.Run) {
    PrismSectionLabel("Current run", accent)
    PrismCard(accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrismOrb(active = run.running, accent = accent)
            Spacer(Modifier.width(8.dp))
            Text(
                run.prompt,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (run.files.isNotEmpty()) {
            Spacer(Modifier.height(3.dp))
            Text(
                run.files.joinToString("  ") { it.substringAfterLast('/') },
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = altarisMutedText(),
                maxLines = 2,
            )
        }
        if (run.steps.isNotEmpty()) {
            Spacer(Modifier.height(Prism.Tight))
            run.steps.forEach { step -> StepRow(step, accent) }
        }
        if (run.answer.isNotBlank()) {
            Spacer(Modifier.height(Prism.Tight))
            PrismDividerThin()
            Spacer(Modifier.height(Prism.Tight))
            SelectionContainer {
                Text(
                    run.answer,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        if (run.error.isNotBlank()) {
            Spacer(Modifier.height(Prism.Tight))
            PrismNote(run.error, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun StepRow(step: ReaAnalysis.Step, accent: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val dot = when {
            step.running -> accent
            step.error.isNotBlank() -> MaterialTheme.colorScheme.error
            else -> accent.copy(alpha = 0.40f)
        }
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(dot)
        )
        Spacer(Modifier.width(7.dp))
        Text(
            step.tool,
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
            color = if (step.running) accent else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            when {
                step.running -> "running…"
                step.error.isNotBlank() -> "failed"
                else -> "${step.ms} ms · ${ReaAnalysis.formatBytes(step.result.length.toLong())}"
            },
            fontSize = 9.sp,
            color = if (step.error.isNotBlank()) MaterialTheme.colorScheme.error
            else altarisMutedText(),
        )
    }
}

@Composable
private fun PrismDividerThin() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline)
    )
}

@Composable
private fun IdleCard(accent: Color) {
    PrismCard(accent = accent) {
        Text("Waiting for a run", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        PrismNote(
            "Start an analysis from Quinn's Chat tab: attach an APK, binary, " +
                "archive or database, pick REA as the agent, and send. Every tool call " +
                "appears here as it happens."
        )
    }
}

/** Shown behind the frost: the shape of the dashboard, without live data. */
@Composable
private fun DashboardPlaceholder(accent: Color) {
    PrismCard(accent = accent) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Prism.Stack),
        ) {
            BigCounter("—", "tool calls", accent, Modifier.weight(1f))
            BigCounter("—", "analysed", accent, Modifier.weight(1f))
            BigCounter("—", "runs", accent, Modifier.weight(1f))
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(Prism.PanelRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(Prism.PanelRadius))
    )
    PrismCard(accent = accent) {
        Text("Waiting for a run", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        PrismNote("Tool calls, counters and results appear here.")
    }
}

/** The install checklist with real progress and the download breakdown. */
@Composable
private fun InstallProgress(accent: Color, distro: ReaDistro.State) {
    PrismSectionLabel("Installing", accent)
    PrismCard(accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrismOrb(active = true, accent = accent)
            Spacer(Modifier.width(8.dp))
            Text(
                distro.busy,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                modifier = Modifier.weight(1f),
            )
            if (distro.percent >= 0) {
                Text(
                    "${distro.percent}%",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                )
            }
        }
        Spacer(Modifier.height(Prism.Tight))
        val fraction by animateFloatAsState(
            targetValue = distro.fraction,
            animationSpec = tween(400),
            label = "installFraction",
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(accent)
            )
        }
        if (distro.downloadText.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                distro.downloadText,
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                color = altarisMutedText(),
            )
        }
        if (distro.detail.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                distro.detail,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = altarisMutedText(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(Prism.Tight))
        distro.steps.forEach { step ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(
                            if (step.done) accent else MaterialTheme.colorScheme.outline
                        )
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    step.label,
                    fontSize = 10.5.sp,
                    color = if (step.done) MaterialTheme.colorScheme.onSurface else altarisMutedText(),
                )
            }
        }
    }
}

@Composable
private fun EnvironmentCard(
    accent: Color,
    distro: ReaDistro.State,
    toolCount: Int,
    serverRunning: Boolean,
    onStart: () -> Unit,
    onInstallMissing: () -> Unit,
    busy: Boolean,
) {
    PrismSectionLabel("Environment", accent)
    PrismCard(accent = accent) {
        Row(horizontalArrangement = Arrangement.spacedBy(Prism.Stack)) {
            SmallStat("Method", distro.method?.label?.uppercase() ?: "—", accent)
            SmallStat("Size", ReaDistro.formatBytes(distro.sizeBytes), accent)
            SmallStat("Tools", toolCount.toString(), accent)
            SmallStat("Server", if (serverRunning) "LIVE" else "IDLE", accent)
        }
        Spacer(Modifier.height(Prism.Tight))
        Row(horizontalArrangement = Arrangement.spacedBy(Prism.ControlGap)) {
            PrismSecondaryButton(
                text = if (serverRunning) "Server running" else "Start server",
                accent = accent,
                icon = Icons.Default.PlayArrow,
                modifier = Modifier.weight(1f),
                enabled = !serverRunning && !busy,
                onClick = onStart,
            )
            PrismSecondaryButton(
                text = "Install missing tools",
                accent = accent,
                modifier = Modifier.weight(1f),
                enabled = !busy,
                onClick = onInstallMissing,
            )
        }
        if (distro.error.isNotBlank()) {
            Spacer(Modifier.height(Prism.Tight))
            PrismNote(distro.error, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun SmallStat(label: String, value: String, accent: Color) {
    Column {
        Text(
            value,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = accent,
            maxLines = 1,
        )
        Text(label.uppercase(), fontSize = 8.5.sp, letterSpacing = 0.6.sp, color = altarisMutedText())
    }
}

@Composable
private fun ModelSummary(accent: Color, onOpen: () -> Unit) {
    val context = LocalContext.current
    var config by remember { mutableStateOf(QuinnModelConfig.load(context)) }
    LaunchedEffect(Unit) { config = QuinnModelConfig.load(context) }
    PrismSectionLabel("Model", accent)
    PrismCard(accent = accent, onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrismOrb(active = config.configured, accent = accent)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    config.model.ifBlank { "No model selected" },
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        !config.configured -> "Tap to add a URL and API key"
                        config.models.isEmpty() -> "Tap to fetch the model list"
                        else -> "${config.models.size} models available"
                    },
                    fontSize = 10.sp,
                    color = altarisMutedText(),
                    maxLines = 1,
                )
            }
            Text("Manage", fontSize = 11.sp, color = accent)
        }
    }
}

@Composable
private fun ModelEditor(accent: Color) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(QuinnModelConfig.load(context)) }
    var fetching by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }

    PrismCard(accent = accent) {
        androidx.compose.material3.OutlinedTextField(
            value = config.baseUrl,
            onValueChange = {
                config = config.copy(baseUrl = it)
                QuinnModelConfig.save(context, config)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Base URL", fontSize = 10.sp) },
            placeholder = { Text("https://integrate.api.nvidia.com/v1", fontSize = 11.sp) },
            textStyle = MaterialTheme.typography.bodySmall,
            singleLine = true,
        )
        Spacer(Modifier.height(Prism.Tight))
        androidx.compose.material3.OutlinedTextField(
            value = config.apiKey,
            onValueChange = {
                config = config.copy(apiKey = it)
                QuinnModelConfig.save(context, config)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("API key", fontSize = 10.sp) },
            textStyle = MaterialTheme.typography.bodySmall,
            singleLine = true,
        )
        Spacer(Modifier.height(Prism.ControlGap))
        PrismButton(
            text = if (fetching) "Fetching…" else "Fetch models",
            accent = accent,
            icon = Icons.Default.Refresh,
            modifier = Modifier.fillMaxWidth(),
            enabled = !fetching && config.baseUrl.isNotBlank() && config.apiKey.isNotBlank(),
            onClick = {
                fetching = true
                message = null
                scope.launch {
                    val normalized = QuinnModelConfig.normalizeBaseUrl(config.baseUrl)
                    if (normalized == null) {
                        fetching = false
                        message = "That base URL is not http(s)."
                        return@launch
                    }
                    val next = config.copy(baseUrl = normalized)
                    val fetched = withContext(Dispatchers.IO) { fetchModels(next) }
                    fetching = false
                    if (fetched.isEmpty()) {
                        message = "No models returned. Check the URL and key."
                    } else {
                        config = next.copy(
                            models = fetched,
                            model = next.model.ifBlank { fetched.first() },
                        )
                        QuinnModelConfig.save(context, config)
                        message = "${fetched.size} models"
                    }
                }
            },
        )
        if (config.models.isNotEmpty()) {
            Spacer(Modifier.height(Prism.Tight))
            androidx.compose.material3.OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search ${config.models.size} models", fontSize = 11.sp) },
                textStyle = MaterialTheme.typography.bodySmall,
                singleLine = true,
            )
            Spacer(Modifier.height(6.dp))
            val shown = QuinnModelConfig.search(config.models, query)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                shown.take(300).forEach { model ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Prism.ChipRadius))
                            .background(
                                if (model == config.model) accent.copy(alpha = 0.12f)
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable {
                                config = config.copy(model = model)
                                QuinnModelConfig.save(context, config)
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            model,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (model == config.model) {
                            Text("selected", fontSize = 9.sp, color = accent)
                        }
                    }
                }
            }
        } else {
            Spacer(Modifier.height(Prism.Tight))
            PrismNote("Fetch the list to choose a model.")
        }
        if (message != null) {
            Spacer(Modifier.height(Prism.Tight))
            PrismNote(message!!)
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
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when (event.kind) {
                ReaAnalysis.Event.Kind.TOOL -> "▸"
                ReaAnalysis.Event.Kind.RESULT -> "✓"
                ReaAnalysis.Event.Kind.ERROR -> "✕"
                ReaAnalysis.Event.Kind.INFO -> "·"
            },
            fontSize = 9.sp,
            color = color,
            modifier = Modifier.width(12.dp),
        )
        Text(
            event.text,
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
    var entries by remember { mutableStateOf<List<com.altaris.linux.core.quinn.ReaFiles.Entry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingDelete by remember { mutableStateOf<com.altaris.linux.core.quinn.ReaFiles.Entry?>(null) }
    var renaming by remember { mutableStateOf<com.altaris.linux.core.quinn.ReaFiles.Entry?>(null) }

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

    // Breadcrumb: every segment is tappable, so getting back up is one tap.
    PrismCard(accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Folder,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val segments = path.trim('/').split('/').filter { it.isNotBlank() }
                BreadcrumbSegment("/", accent) { load("/") }
                segments.forEachIndexed { index, segment ->
                    Text("/", fontSize = 11.sp, color = altarisMutedText())
                    val target = "/" + segments.take(index + 1).joinToString("/")
                    BreadcrumbSegment(segment, accent) { load(target) }
                }
            }
            if (loading) {
                Text("loading…", fontSize = 9.5.sp, color = altarisMutedText())
            }
        }
        Spacer(Modifier.height(Prism.Tight))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PrismChip("New folder", accent, onClick = {
                scope.launch {
                    val target = "$path/untitled"
                    val result = withContext(Dispatchers.IO) {
                        com.altaris.linux.core.quinn.ReaFiles.mkdir(context, target)
                    }
                    result.onSuccess { load(path) }.onFailure { note = it.message }
                }
            })
            PrismChip("New file", accent, onClick = {
                scope.launch {
                    val target = "$path/untitled.txt"
                    val result = withContext(Dispatchers.IO) {
                        com.altaris.linux.core.quinn.ReaFiles.createFile(context, target)
                    }
                    result.onSuccess {
                        editing = target to ""
                        load(path)
                    }.onFailure { note = it.message }
                }
            })
        }
    }

    PrismCard(accent = accent) {
        if (entries.isEmpty() && !loading) {
            PrismNote("Empty folder")
        } else {
            entries.take(300).forEach { entry ->
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
            Spacer(Modifier.height(Prism.Tight))
            PrismNote(note!!, color = MaterialTheme.colorScheme.error)
        }
    }

    // Editor.
    editing?.let { (filePath, content) ->
        var draft by remember(filePath) { mutableStateOf(content) }
        PrismCard(accent = accent) {
            Text(
                filePath,
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(Prism.Tight))
            androidx.compose.material3.OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 340.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            Spacer(Modifier.height(Prism.ControlGap))
            Row(horizontalArrangement = Arrangement.spacedBy(Prism.ControlGap)) {
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

    // Rename.
    renaming?.let { entry ->
        var name by remember(entry.path) { mutableStateOf(entry.name) }
        PrismCard(accent = accent) {
            Text("Rename", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(Prism.Tight))
            androidx.compose.material3.OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                singleLine = true,
            )
            Spacer(Modifier.height(Prism.ControlGap))
            Row(horizontalArrangement = Arrangement.spacedBy(Prism.ControlGap)) {
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
                        val target = com.altaris.linux.core.quinn.ReaFiles.parentOf(entry.path)
                            .let { if (it == "/") "/$name" else "$it/$name" }
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                com.altaris.linux.core.quinn.ReaFiles.rename(context, entry.path, target)
                            }
                            renaming = null
                            result.onSuccess { load(path) }.onFailure { note = it.message }
                        }
                    },
                )
            }
        }
    }

    // Delete confirmation: the only destructive action in the tab.
    pendingDelete?.let { entry ->
        PrismCard(accent = accent) {
            Text("Delete this?", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text(
                entry.path + if (entry.dir) " (and everything in it)" else "",
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                color = altarisMutedText(),
                maxLines = 2,
            )
            Spacer(Modifier.height(Prism.ControlGap))
            Row(horizontalArrangement = Arrangement.spacedBy(Prism.ControlGap)) {
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
                            val result = withContext(Dispatchers.IO) {
                                com.altaris.linux.core.quinn.ReaFiles.delete(context, entry.path)
                            }
                            pendingDelete = null
                            result.onSuccess {
                                note = "Deleted"
                                load(path)
                            }.onFailure { note = it.message }
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
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        color = accent,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 2.dp),
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
                .padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        if (entry.dir) accent else accent.copy(alpha = 0.35f)
                    )
            )
            Spacer(Modifier.width(8.dp))
            Text(
                entry.name,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (entry.dir) "dir" else ReaDistro.formatBytes(entry.size),
                fontSize = 9.5.sp,
                color = altarisMutedText(),
            )
            Spacer(Modifier.width(8.dp))
            PrismChip("···", accent, onClick = { expanded = !expanded })
        }
        if (expanded) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 13.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (!entry.dir) {
                    PrismChip("Open", accent, onClick = onOpen)
                }
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

/** Fetch `<base>/models` with the user's key. Empty list means failure. */
private fun fetchModels(config: QuinnModelConfig.Config): List<String> {
    if (config.baseUrl.isBlank() || config.apiKey.isBlank()) return emptyList()
    val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    val request = okhttp3.Request.Builder()
        .url(config.modelsUrl)
        .header("Authorization", "Bearer " + config.apiKey)
        .get()
        .build()
    return runCatching {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            QuinnModelConfig.parseModels(response.body?.string().orEmpty())
        }
    }.getOrDefault(emptyList())
}
