package com.altaris.linux.ui.screens.quinn

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altaris.linux.core.quinn.AgentTarget
import com.altaris.linux.core.quinn.QuinnEngine
import com.altaris.linux.core.quinn.QuinnSession
import com.altaris.linux.core.quinn.Turn
import com.altaris.linux.core.quinn.TurnRole
import com.altaris.linux.ui.components.PrismButton
import com.altaris.linux.ui.components.PrismCard
import com.altaris.linux.ui.components.PrismChip
import com.altaris.linux.ui.components.PrismNote
import com.altaris.linux.ui.components.PrismOrb
import com.altaris.linux.ui.components.PrismSecondaryButton
import com.altaris.linux.ui.components.PrismSectionLabel
import com.altaris.linux.ui.theme.Prism
import com.altaris.linux.ui.theme.altarisMutedText

/**
 * Quinn's chat surface: pick agents, talk to one or several, keep every thread
 * separate.
 *
 * Layout follows the phone, not a desktop: the agent strip scrolls
 * horizontally, the thread is the body, and the composer is pinned to the
 * bottom. Selecting more than one agent turns the send into a fan-out; each
 * agent gets its own thread and its own reply, which is what keeps them from
 * interfering.
 */
@Composable
fun QuinnChatTab(
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val targets by QuinnEngine.targets.collectAsState()
    val scanning by QuinnEngine.scanning.collectAsState()
    val sessions by QuinnEngine.sessions.collectAsState()
    val order by QuinnEngine.order.collectAsState()
    val running by QuinnEngine.runningCount.collectAsState()
    val error by QuinnEngine.lastError.collectAsState()

    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var activeSession by remember { mutableStateOf<String?>(null) }
    var prompt by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(Unit) { QuinnEngine.refreshTargets(context) }

    // Keep the visible thread valid when a session is closed.
    LaunchedEffect(order) {
        if (activeSession != null && !sessions.containsKey(activeSession)) {
            activeSession = order.firstOrNull()
        }
        if (activeSession == null) activeSession = order.firstOrNull()
    }

    val session = activeSession?.let { sessions[it] }

    Column(modifier = modifier.fillMaxWidth()) {
        AgentStrip(
            accent = accent,
            targets = targets,
            scanning = scanning,
            selected = selected,
            running = running,
            onRefresh = { QuinnEngine.refreshTargets(context) },
            onToggle = { target ->
                selected = if (selected.contains(target.key)) selected - target.key
                else selected + target.key
            },
            onOpen = { target ->
                val opened = QuinnEngine.openSession(target)
                activeSession = opened.id
            },
        )

        if (error != null) {
            PrismNote(error!!, color = MaterialTheme.colorScheme.error)
        }

        if (order.isNotEmpty()) {
            SessionStrip(
                accent = accent,
                sessions = sessions,
                order = order,
                active = activeSession,
                onPick = { activeSession = it },
                onClose = { QuinnEngine.closeSession(it) },
            )
        }

        Box(modifier = Modifier.fillMaxWidth().height(Prism.Stack))

        if (session == null) {
            EmptyThread(accent)
        } else {
            ThreadView(
                session = session,
                accent = accent,
                modifier = Modifier.weight(1f),
            )
            Composer(
                accent = accent,
                prompt = prompt,
                attachments = attachments,
                busy = running > 0,
                selectedCount = selected.size,
                onPrompt = { prompt = it },
                onAttach = { path -> attachments = attachments + path },
                onRemoveAttachment = { attachments = attachments - it },
                onSend = {
                    val fanTargets = QuinnEngine.targets.value.filter { it.key in selected }
                        .ifEmpty { listOfNotNull(session.target) }
                    val ids = QuinnEngine.send(
                        context = context,
                        prompt = prompt,
                        targets = fanTargets,
                        files = attachments,
                    )
                    if (ids.isNotEmpty()) {
                        activeSession = ids.first()
                        prompt = ""
                        attachments = emptyList()
                    }
                },
            )
        }
    }
}

/** The horizontal agent picker: every distro × agent Quinn can reach. */
@Composable
private fun AgentStrip(
    accent: androidx.compose.ui.graphics.Color,
    targets: List<AgentTarget>,
    scanning: Boolean,
    selected: Set<String>,
    running: Int,
    onRefresh: () -> Unit,
    onToggle: (AgentTarget) -> Unit,
    onOpen: (AgentTarget) -> Unit,
) {
    PrismSectionLabel("Agents", accent)
    PrismCard(accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrismOrb(active = running > 0, accent = accent)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (targets.isEmpty()) "No agents found"
                    else "${targets.size} agents · ${targets.map { it.distroName }.distinct().size} distros",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    when {
                        running > 0 -> "$running turn${if (running == 1) "" else "s"} running"
                        scanning -> "Scanning distros…"
                        selected.isEmpty() -> "Tap to select · long-press to open a thread"
                        else -> "${selected.size} selected for the next message"
                    },
                    fontSize = 11.sp,
                    color = altarisMutedText(),
                )
            }
            PrismChip("Refresh", accent, onClick = onRefresh)
        }
        if (targets.isNotEmpty()) {
            Spacer(Modifier.height(Prism.Tight))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Prism.ControlGap),
            ) {
                targets.forEach { target ->
                    AgentPill(
                        target = target,
                        accent = accent,
                        selected = target.key in selected,
                        onClick = { onOpen(target) },
                        onLongClick = { onToggle(target) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentPill(
    target: AgentTarget,
    accent: androidx.compose.ui.graphics.Color,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Prism.ChipRadius)
    Column(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.12f) else scheme.surfaceVariant)
            .border(1.dp, if (selected) accent else scheme.outline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            target.label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
        Text(
            target.distroName + if (target.headless) "" else " · pty",
            fontSize = 9.5.sp,
            color = altarisMutedText(),
            maxLines = 1,
        )
    }
}

/** One chip per live thread, so several conversations stay reachable. */
@Composable
private fun SessionStrip(
    accent: androidx.compose.ui.graphics.Color,
    sessions: Map<String, QuinnSession>,
    order: List<String>,
    active: String?,
    onPick: (String) -> Unit,
    onClose: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Prism.ControlGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        order.forEach { id ->
            val session = sessions[id] ?: return@forEach
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(Prism.ChipRadius))
                    .background(
                        if (id == active) accent.copy(alpha = 0.14f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .border(
                        1.dp,
                        if (id == active) accent else MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(Prism.ChipRadius),
                    )
                    .clickable { onPick(id) }
                    .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (session.running) {
                    PrismOrb(active = true, accent = accent)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    session.displayTitle,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(120.dp),
                )
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close session",
                    tint = altarisMutedText(),
                    modifier = Modifier
                        .size(22.dp)
                        .clickable { onClose(id) }
                        .padding(4.dp),
                )
            }
        }
    }
}

/** The message list for one thread. */
@Composable
private fun ThreadView(
    session: QuinnSession,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    val state = rememberLazyListState()
    LaunchedEffect(session.turns.size, session.turns.lastOrNull()?.text) {
        if (session.turns.isNotEmpty()) state.animateScrollToItem(session.turns.lastIndex)
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = state,
        verticalArrangement = Arrangement.spacedBy(Prism.Tight),
    ) {
        items(session.turns) { turn -> TurnBubble(turn, accent) }
    }
}

@Composable
private fun TurnBubble(turn: Turn, accent: androidx.compose.ui.graphics.Color) {
    val scheme = MaterialTheme.colorScheme
    val mine = turn.role == TurnRole.USER
    val shape = RoundedCornerShape(Prism.PanelRadius)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(shape)
                .background(if (mine) accent.copy(alpha = 0.10f) else scheme.surfaceVariant)
                .border(1.dp, if (mine) accent.copy(alpha = 0.35f) else scheme.outline, shape)
                .padding(Prism.Tight),
        ) {
            if (turn.files.isNotEmpty()) {
                Text(
                    "attached: " + turn.files.joinToString(", ") { it.substringAfterLast('/') },
                    fontSize = 10.sp,
                    color = altarisMutedText(),
                    maxLines = 2,
                )
                Spacer(Modifier.height(4.dp))
            }
            SelectionContainer {
                Text(
                    turn.text.ifBlank { if (turn.streaming) "…" else "" },
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    fontFamily = if (turn.role == TurnRole.AGENT) FontFamily.Monospace else null,
                    color = if (turn.error != null) scheme.error else scheme.onSurface,
                )
            }
            if (turn.streaming) {
                Spacer(Modifier.height(4.dp))
                Text("working…", fontSize = 10.sp, color = accent)
            }
        }
    }
}

@Composable
private fun Composer(
    accent: androidx.compose.ui.graphics.Color,
    prompt: String,
    attachments: List<String>,
    busy: Boolean,
    selectedCount: Int,
    onPrompt: (String) -> Unit,
    onAttach: (String) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onSend: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = Prism.Tight)) {
        if (attachments.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                attachments.forEach { path ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(Prism.ChipRadius))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onRemoveAttachment(path) }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            path.substringAfterLast('/'),
                            fontSize = 10.sp,
                            maxLines = 1,
                            modifier = Modifier.width(120.dp),
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remove",
                            modifier = Modifier.size(12.dp),
                            tint = altarisMutedText(),
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        OutlinedTextField(
            value = prompt,
            onValueChange = onPrompt,
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp, max = 160.dp),
            placeholder = {
                Text(
                    if (selectedCount > 1) "Message $selectedCount agents…"
                    else "Ask, command, or paste a path…",
                    fontSize = 12.5.sp,
                )
            },
            textStyle = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(Prism.ControlGap))
        Row(horizontalArrangement = Arrangement.spacedBy(Prism.ControlGap)) {
            PrismSecondaryButton(
                text = "Attach",
                accent = accent,
                icon = Icons.Default.AttachFile,
                modifier = Modifier.weight(1f),
                onClick = { onAttach("/sdcard/Download") },
            )
            PrismButton(
                text = if (busy) "Running…" else "Send",
                accent = accent,
                icon = Icons.AutoMirrored.Filled.Send,
                modifier = Modifier.weight(1f),
                enabled = prompt.isNotBlank() && !busy,
                onClick = onSend,
            )
        }
    }
}

@Composable
private fun EmptyThread(accent: androidx.compose.ui.graphics.Color) {
    PrismCard(accent = accent) {
        Text("No thread open", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        PrismNote(
            "Pick an agent above to open a thread. Long-press agents to select " +
                "several, then one message goes to all of them — each in its own " +
                "thread, so their replies never mix."
        )
    }
}
