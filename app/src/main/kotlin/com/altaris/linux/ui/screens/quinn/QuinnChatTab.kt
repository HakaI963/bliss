package com.altaris.linux.ui.screens.quinn

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import com.altaris.linux.core.quinn.AgentTarget
import com.altaris.linux.core.quinn.ContextBudget
import com.altaris.linux.core.quinn.CustomAgents
import com.altaris.linux.core.quinn.GuestFiles
import com.altaris.linux.core.quinn.QuinnBrain
import com.altaris.linux.core.quinn.QuinnEngine
import com.altaris.linux.core.quinn.QuinnModelConfig
import com.altaris.linux.core.quinn.QuinnPrefs
import com.altaris.linux.core.quinn.QuinnSession
import com.altaris.linux.core.quinn.ReaAnalysis
import com.altaris.linux.core.quinn.SlashCommands
import com.altaris.linux.core.quinn.TargetKind
import com.altaris.linux.core.quinn.Turn
import com.altaris.linux.core.quinn.TurnRole
import com.altaris.linux.core.rea.ReaDistro
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

/**
 * Quinn's chat.
 *
 * The screen is a normal chat: messages, an input, and a send button. What
 * makes it Quinn is the pill row above the input:
 *
 * ```
 *   [ agent name ][⌄]            [ model ][⌄]   [ ◯ ]   (send)
 *   [ + ]  [ what do you want to do?            ]
 * ```
 *
 *  - the main pill is the selected agent; the small pill hanging off its bottom
 *    edge expands the agent list, and picking an agent expands *its* running
 *    state and sessions to the right;
 *  - the right pill selects that agent's model;
 *  - the ring is the context estimate, and opens the breakdown;
 *  - `+` picks files of any type;
 *  - `/` suggests the agent's own commands, `//` suggests Quinn's.
 *
 * Quinn's own model is chosen in Settings, and the small button next to Send
 * opens that same config, so there is one place it lives.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun QuinnChatTab(
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val targets by QuinnEngine.targets.collectAsState()
    val scanning by QuinnEngine.scanning.collectAsState()
    val sessions by QuinnEngine.sessions.collectAsState()
    val order by QuinnEngine.order.collectAsState()
    val running by QuinnEngine.runningCount.collectAsState()
    val error by QuinnEngine.lastError.collectAsState()
    val reaState by ReaDistro.state.collectAsState()

    val saved = remember { QuinnPrefs.load(context) }
    var selectedKey by remember { mutableStateOf(saved.agentKey) }
    var activeSession by remember { mutableStateOf<String?>(saved.sessionId.ifBlank { null }) }
    var agentModel by remember { mutableStateOf(saved.agentModel) }
    var prompt by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<String>>(emptyList()) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var quinnAnswer by remember { mutableStateOf<Pair<String, String>?>(null) }
    var importing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        QuinnEngine.refreshTargets(context)
        ReaDistro.refresh(context)
    }

    // Restore the remembered thread once the targets and sessions are loaded.
    LaunchedEffect(order, targets) {
        if (activeSession != null && !sessions.containsKey(activeSession)) {
            activeSession = order.firstOrNull()
        }
        if (activeSession == null && order.isNotEmpty()) activeSession = order.firstOrNull()
    }

    // Remember the selection across restarts.
    LaunchedEffect(selectedKey, activeSession, agentModel) {
        QuinnPrefs.save(
            context,
            QuinnPrefs.Selection(
                agentKey = selectedKey,
                sessionId = activeSession.orEmpty(),
                agentModel = agentModel,
            )
        )
    }

    val session = activeSession?.let { sessions[it] }
    val target = targets.firstOrNull { it.key == selectedKey } ?: session?.target

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val distro = if (target?.kind == TargetKind.REA) {
                reaState.method?.id ?: QuinnEngine.HOST_DISTRO
            } else {
                target?.distroId ?: QuinnEngine.HOST_DISTRO
            }
            val imported = ArrayList<String>()
            for (uri in uris) {
                val name = displayName(context, uri)
                val file = withContext(Dispatchers.IO) {
                    GuestFiles.materialize(context, name) {
                        context.contentResolver.openInputStream(uri)
                    }
                } ?: continue
                val guestPath = withContext(Dispatchers.IO) {
                    GuestFiles.importInto(context, distro, file.absolutePath)
                }
                if (guestPath != null) imported.add(guestPath)
            }
            importing = false
            if (imported.isNotEmpty()) attachments = attachments + imported
        }
    }

    val draft = SlashCommands.parse(prompt)
    val suggestions = SlashCommands.suggestions(draft)
    val contextEstimate = remember(session, target) {
        val turns = session?.turns.orEmpty()
        ContextBudget.estimate(
            systemPrompt = "You are " + (target?.label ?: "Quinn") + " inside Altaris.",
            tools = SlashCommands.AGENT_DEFAULTS.joinToString(" ") { it.name + " " + it.description },
            skills = "",
            messages = turns.map { it.role.name to it.text },
            window = ContextBudget.windowFor(agentModel.ifBlank { session?.model.orEmpty() }),
        )
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (error != null) {
            PrismNote(error!!, color = MaterialTheme.colorScheme.error)
        }

        // Messages take all the space between the header and the composer.
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (session == null || session.turns.isEmpty()) {
                ChatEmpty(
                    accent = accent,
                    target = target,
                    targetCount = targets.size,
                    scanning = scanning,
                    reaReady = reaState.installed,
                )
            } else {
                ThreadView(session = session, accent = accent)
            }
        }

        // Quinn's answer to a // command, in its own small window above the
        // composer so it never mixes into the agent's thread.
        quinnAnswer?.let { (question, answer) ->
            QuinnAnswerPanel(
                accent = accent,
                question = question,
                answer = answer,
                onClose = { quinnAnswer = null },
            )
            Spacer(Modifier.height(Prism.ControlGap))
        }

        // Live command suggestions.
        if (suggestions.isNotEmpty() && draft != null && draft.typingName) {
            SuggestionStrip(
                accent = accent,
                commands = suggestions,
                onPick = { command ->
                    prompt = command.token + if (command.takesArgument) " " else ""
                },
            )
            Spacer(Modifier.height(6.dp))
        }

        if (attachments.isNotEmpty()) {
            AttachmentStrip(accent, attachments, onRemove = { attachments = attachments - it })
            Spacer(Modifier.height(6.dp))
        }

        // ---- pill row ------------------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AgentPill(
                accent = accent,
                target = target,
                running = session?.running == true,
                onOpenAgents = { sheet = Sheet.AGENTS },
                onOpenSessions = { sheet = Sheet.SESSIONS },
            )
            Spacer(Modifier.weight(1f))
            ModelPill(
                accent = accent,
                model = agentModel.ifBlank { session?.model.orEmpty() },
                onClick = { sheet = Sheet.AGENT_MODEL },
            )
            ContextRing(
                accent = accent,
                estimate = contextEstimate,
                onClick = { sheet = Sheet.CONTEXT },
            )
        }

        Spacer(Modifier.height(6.dp))

        // ---- composer ------------------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable(enabled = !importing) { picker.launch(arrayOf("*/*")) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Attach a file",
                    tint = if (importing) altarisMutedText() else accent,
                    modifier = Modifier.size(19.dp),
                )
            }
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                modifier = Modifier.weight(1f).heightIn(min = 44.dp, max = 150.dp),
                placeholder = {
                    Text(
                        if (target == null) "Pick an agent with the pill above…"
                        else "Message ${target.label} · / commands · // Quinn",
                        fontSize = 12.sp,
                    )
                },
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = RoundedCornerShape(22.dp),
            )
            // Quinn's own model, next to Send.
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable { sheet = Sheet.QUINN_MODEL },
                contentAlignment = Alignment.Center,
            ) {
                Text("Q", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = accent)
            }
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        if (prompt.isNotBlank() && running == 0 && target != null) accent
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .clickable(enabled = prompt.isNotBlank() && running == 0 && target != null) {
                        send(
                            context = context,
                            prompt = prompt,
                            draft = draft,
                            target = target,
                            attachments = attachments,
                            agentModel = agentModel,
                            scope = { activeSession = it },
                            onQuinnAnswer = { question, answer ->
                                quinnAnswer = question to answer
                            },
                            onClear = {
                                prompt = ""
                                attachments = emptyList()
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (prompt.isNotBlank() && running == 0 && target != null) Color.White
                    else altarisMutedText(),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }

    // ---- sheets ------------------------------------------------------------
    val activeSheet = sheet
    if (activeSheet != null) {
        ModalBottomSheet(
            onDismissRequest = { sheet = null },
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 600.dp)
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (activeSheet) {
                    Sheet.AGENTS -> AgentSheet(
                        accent = accent,
                        targets = targets,
                        selectedKey = target?.key.orEmpty(),
                        sessions = sessions,
                        onPick = { picked ->
                            selectedKey = picked.key
                            val opened = QuinnEngine.openSession(picked)
                            activeSession = opened.id
                            agentModel = opened.model.orEmpty()
                        },
                        onOpenSession = { id ->
                            activeSession = id
                            sheet = null
                        },
                        onAddCustom = { binary, distroId ->
                            CustomAgents.add(context, binary, distroId)
                            QuinnEngine.refreshTargets(context)
                        },
                        onRefresh = { QuinnEngine.refreshTargets(context) },
                    )
                    Sheet.SESSIONS -> SessionSheet(
                        accent = accent,
                        sessions = sessions,
                        order = order,
                        active = activeSession,
                        onPick = {
                            activeSession = it
                            sheet = null
                        },
                        onClose = { QuinnEngine.closeSession(it) },
                    )
                    Sheet.AGENT_MODEL -> AgentModelSheet(
                        accent = accent,
                        agentLabel = target?.label.orEmpty(),
                        current = agentModel,
                        onPick = { model ->
                            agentModel = model
                            session?.let { QuinnEngine.setModel(it.id, model) }
                            sheet = null
                        },
                    )
                    Sheet.QUINN_MODEL -> QuinnModelSheet(accent)
                    Sheet.CONTEXT -> ContextSheet(accent, contextEstimate)
                }
            }
        }
    }
}

/** Which bottom sheet is open. */
private enum class Sheet { AGENTS, SESSIONS, AGENT_MODEL, QUINN_MODEL, CONTEXT }

/** Send a message, or answer a Quinn command. */
private fun send(
    context: Context,
    prompt: String,
    draft: SlashCommands.Draft?,
    target: AgentTarget?,
    attachments: List<String>,
    agentModel: String,
    scope: (String) -> Unit,
    onQuinnAnswer: (String, String) -> Unit,
    onClear: () -> Unit,
) {
    // `//` never reaches the agent: Quinn answers it and the agent's work is
    // untouched.
    if (draft != null && draft.quinn) {
        val question = prompt.trim()
        onClear()
        kotlinx.coroutines.MainScope().launch {
            val answer = QuinnBrain.ask(context, draft.name, draft.argument)
                ?: "No answer."
            onQuinnAnswer(question, answer)
        }
        return
    }
    if (target == null) return
    val ids = QuinnEngine.send(
        context = context,
        prompt = prompt,
        targets = listOf(target),
        files = attachments,
        model = agentModel.ifBlank { null },
    )
    if (ids.isNotEmpty()) {
        scope(ids.first())
        onClear()
    }
}

/** The main agent pill, with the small expander hanging off its bottom edge. */
@Composable
private fun AgentPill(
    accent: Color,
    target: AgentTarget?,
    running: Boolean,
    onOpenAgents: () -> Unit,
    onOpenSessions: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(Prism.ChipRadius))
                .background(scheme.surfaceVariant)
                .border(1.dp, scheme.outline, RoundedCornerShape(Prism.ChipRadius))
                .clickable(onClick = onOpenAgents)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PrismOrb(active = running, accent = accent)
            Spacer(Modifier.width(6.dp))
            Column {
                Text(
                    target?.label ?: "No agent",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    color = if (target != null) scheme.onSurface else altarisMutedText(),
                )
                Text(
                    target?.distroName ?: "tap to pick",
                    fontSize = 8.5.sp,
                    color = altarisMutedText(),
                    maxLines = 1,
                )
            }
        }
        // The small pill: a third of the width, offset a couple of px from the
        // left edge, hanging below the main pill.
        Row(
            modifier = Modifier
                .padding(start = 3.dp, top = 1.dp)
                .width(34.dp)
                .height(11.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(accent.copy(alpha = 0.16f))
                .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(3.dp))
                .clickable(onClick = onOpenAgents),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text("▾", fontSize = 7.sp, color = accent)
        }
        // A hair to the right of the small pill: this session's own expander.
        Row(
            modifier = Modifier
                .padding(start = 39.dp, top = 1.dp)
                .width(22.dp)
                .height(11.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(scheme.surfaceVariant)
                .border(1.dp, scheme.outline, RoundedCornerShape(3.dp))
                .clickable(onClick = onOpenSessions),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text("▸", fontSize = 7.sp, color = altarisMutedText())
        }
    }
}

/** The selected agent's model. */
@Composable
private fun ModelPill(accent: Color, model: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Prism.ChipRadius))
            .background(scheme.surfaceVariant)
            .border(1.dp, scheme.outline, RoundedCornerShape(Prism.ChipRadius))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            QuinnModelConfig.shortName(model).ifBlank { "model" }.take(14),
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (model.isBlank()) altarisMutedText() else scheme.onSurface,
        )
        Text(" ▾", fontSize = 7.sp, color = altarisMutedText())
    }
}

/** The context ring: a small circle that fills as the window is used. */
@Composable
private fun ContextRing(accent: Color, estimate: ContextBudget.Estimate, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 2.5.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = scheme.outline,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke),
            )
            drawArc(
                color = if (estimate.fraction > 0.85f) scheme.error else accent,
                startAngle = -90f,
                sweepAngle = 360f * estimate.fraction,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke),
            )
        }
        Text(
            "${(estimate.fraction * 100).toInt()}%",
            fontSize = 7.sp,
            fontWeight = FontWeight.Medium,
            color = altarisMutedText(),
        )
    }
}

@Composable
private fun SuggestionStrip(
    accent: Color,
    commands: List<SlashCommands.Command>,
    onPick: (SlashCommands.Command) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        commands.take(10).forEach { command ->
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(Prism.ChipRadius))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        1.dp,
                        if (command.quinn) accent.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(Prism.ChipRadius),
                    )
                    .clickable { onPick(command) }
                    .padding(horizontal = 9.dp, vertical = 5.dp),
            ) {
                Text(
                    command.token,
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = if (command.quinn) accent else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    command.description.take(34),
                    fontSize = 8.5.sp,
                    color = altarisMutedText(),
                    maxLines = 1,
                )
            }
        }
    }
}

/** Quinn's answer, in its own frame so it never mixes into the agent's thread. */
@Composable
private fun QuinnAnswerPanel(
    accent: Color,
    question: String,
    answer: String,
    onClose: () -> Unit,
) {
    PrismCard(accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrismOrb(active = true, accent = accent)
            Spacer(Modifier.width(7.dp))
            Text(
                question,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Default.Close,
                contentDescription = "Close",
                tint = altarisMutedText(),
                modifier = Modifier.size(18.dp).clickable(onClick = onClose),
            )
        }
        Spacer(Modifier.height(6.dp))
        SelectionContainer {
            Text(answer, fontSize = 11.5.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun AttachmentStrip(accent: Color, paths: List<String>, onRemove: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        paths.forEach { path ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(Prism.ChipRadius))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(Prism.ChipRadius))
                    .clickable { onRemove(path) }
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    path.substringAfterLast('/'),
                    fontSize = 10.sp,
                    maxLines = 1,
                    modifier = Modifier.width(110.dp),
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
}

/** The agent window: agents on the left, the picked agent's state on the right. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AgentSheet(
    accent: Color,
    targets: List<AgentTarget>,
    selectedKey: String,
    sessions: Map<String, QuinnSession>,
    onPick: (AgentTarget) -> Unit,
    onOpenSession: (String) -> Unit,
    onAddCustom: (String, String) -> Unit,
    onRefresh: () -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    var customName by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(selectedKey) }
    var filter by remember { mutableStateOf("") }

    Text("Agents", fontSize = 16.sp, fontWeight = FontWeight.Medium)
    Text(
        "Pick an agent to chat with. Its running state and sessions appear beside it.",
        fontSize = 11.5.sp,
        lineHeight = 15.sp,
        color = altarisMutedText(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PrismChip("Rescan", accent, onClick = onRefresh)
        PrismChip(if (adding) "Cancel" else "Add agent", accent, onClick = { adding = !adding })
    }
    if (adding) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = customName,
                onValueChange = { customName = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("binary name, e.g. kimi", fontSize = 11.sp) },
                textStyle = MaterialTheme.typography.bodySmall,
                singleLine = true,
            )
            PrismButton(
                text = "Add",
                accent = accent,
                enabled = customName.isNotBlank(),
                onClick = {
                    onAddCustom(customName, "")
                    customName = ""
                    adding = false
                },
            )
        }
        PrismNote(
            "Added names are remembered. The agent must already be installed in a distro."
        )
    }

    if (targets.isEmpty()) {
        PrismNote(
            "No agent found. Quinn looks in the usual agent directories (npm, pip, " +
                "cargo, .local/bin) in every distro, including chroot and named installs. " +
                "If yours lives elsewhere, add it by name above."
        )
        return
    }

    OutlinedTextField(
        value = filter,
        onValueChange = { filter = it },
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Filter ${targets.size} agents", fontSize = 11.sp) },
        textStyle = MaterialTheme.typography.bodySmall,
        singleLine = true,
    )

    val shown = targets.filter {
        filter.isBlank() ||
            it.label.contains(filter, ignoreCase = true) ||
            it.distroName.contains(filter, ignoreCase = true)
    }
    val focusedTarget = shown.firstOrNull { it.key == focused } ?: shown.firstOrNull()

    Row(
        modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Left: the agent list.
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            items(shown, key = { it.key }) { item ->
                val picked = item.key == focusedTarget?.key
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Prism.ChipRadius))
                        .background(
                            if (picked) accent.copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .border(
                            1.dp,
                            if (picked) accent.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(Prism.ChipRadius),
                        )
                        .clickable { focused = item.key }
                        .padding(horizontal = 9.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PrismOrb(
                        active = sessions.values.any { it.target.key == item.key && it.running },
                        accent = accent,
                    )
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.label,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            item.distroName,
                            fontSize = 8.5.sp,
                            color = altarisMutedText(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        // Right: the focused agent's state and sessions.
        if (focusedTarget != null) {
            val agentSessions = sessions.values.filter { it.target.key == focusedTarget.key }
            Column(
                modifier = Modifier.weight(1f).verticalScrollSafe(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    focusedTarget.label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    focusedTarget.distroName + " · " +
                        when {
                            focusedTarget.kind == TargetKind.REA -> "analysis"
                            focusedTarget.headless -> "headless"
                            else -> "terminal"
                        },
                    fontSize = 9.sp,
                    color = altarisMutedText(),
                )
                val busy = agentSessions.any { it.running }
                Text(
                    if (busy) "running" else if (agentSessions.isEmpty()) "idle" else "idle · ${agentSessions.size} sessions",
                    fontSize = 9.5.sp,
                    color = if (busy) accent else altarisMutedText(),
                )
                Spacer(Modifier.height(2.dp))
                if (agentSessions.isEmpty()) {
                    Text("No session yet", fontSize = 9.5.sp, color = altarisMutedText())
                } else {
                    agentSessions.take(6).forEach { s ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { onOpenSession(s.id) }
                                .padding(horizontal = 6.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PrismOrb(active = s.running, accent = accent)
                            Spacer(Modifier.width(5.dp))
                            Text(
                                s.displayTitle.take(18),
                                fontSize = 9.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                PrismButton(
                    text = "Chat with " + focusedTarget.label.take(12),
                    accent = accent,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onPick(focusedTarget) },
                )
            }
        }
    }
}

/** Local helper: a scrollable column that never grows past its slot. */
@Composable
private fun Modifier.verticalScrollSafe(): Modifier =
    this.then(
        androidx.compose.foundation.verticalScroll(rememberScrollState())
    )

@Composable
private fun SessionSheet(
    accent: Color,
    sessions: Map<String, QuinnSession>,
    order: List<String>,
    active: String?,
    onPick: (String) -> Unit,
    onClose: (String) -> Unit,
) {
    Text("Sessions", fontSize = 16.sp, fontWeight = FontWeight.Medium)
    if (order.isEmpty()) {
        PrismNote("No session yet. Pick an agent and send a message.")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(order, key = { it }) { id ->
            val session = sessions[id] ?: return@items
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Prism.ChipRadius))
                    .background(
                        if (id == active) accent.copy(alpha = 0.10f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .clickable { onPick(id) }
                    .padding(start = 12.dp, end = 6.dp, top = 9.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (session.running) {
                    PrismOrb(active = true, accent = accent)
                    Spacer(Modifier.width(8.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        session.displayTitle,
                        fontSize = 12.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        session.target.displayName + " · ${session.turns.size} messages",
                        fontSize = 10.sp,
                        color = altarisMutedText(),
                        maxLines = 1,
                    )
                }
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close",
                    tint = altarisMutedText(),
                    modifier = Modifier
                        .size(26.dp)
                        .clickable { onClose(id) }
                        .padding(6.dp),
                )
            }
        }
    }
}

/**
 * The agent's model.
 *
 * The list comes from the same endpoint the agents themselves are configured
 * with where possible; when the agent does not expose one, the Quinn model list
 * is used as the source of names and the value is passed to the agent's
 * `--model` flag.
 */
@Composable
private fun AgentModelSheet(
    accent: Color,
    agentLabel: String,
    current: String,
    onPick: (String) -> Unit,
) {
    val context = LocalContext.current
    val config = remember { QuinnModelConfig.load(context) }
    var query by remember { mutableStateOf("") }
    Text(
        if (agentLabel.isBlank()) "Agent model" else "$agentLabel model",
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
    )
    Text(
        "Passed to the agent as --model. Leave it empty to use the agent's own default.",
        fontSize = 11.5.sp,
        lineHeight = 15.sp,
        color = altarisMutedText(),
    )
    PrismChip(
        "Use the agent's default",
        accent,
        selected = current.isBlank(),
        onClick = { onPick("") },
    )
    if (config.models.isEmpty()) {
        PrismNote("Fetch the model list in Settings → Quinn AI to choose one here.")
        return
    }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Search ${config.models.size} models", fontSize = 11.sp) },
        textStyle = MaterialTheme.typography.bodySmall,
        singleLine = true,
    )
    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        items(QuinnModelConfig.search(config.models, query).take(300), key = { it }) { model ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Prism.ChipRadius))
                    .background(
                        if (model == current) accent.copy(alpha = 0.12f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .clickable { onPick(model) }
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
                if (model == current) Text("selected", fontSize = 9.sp, color = accent)
            }
        }
    }
}

/** Quinn's own model: a short pointer to Settings, plus the current value. */
@Composable
private fun QuinnModelSheet(accent: Color) {
    val context = LocalContext.current
    val config = remember { QuinnModelConfig.load(context) }
    Text("Quinn model", fontSize = 16.sp, fontWeight = FontWeight.Medium)
    Text(
        "Quinn's own brain. Used for // questions and for driving REA. " +
            "It lives in Settings → Quinn AI so there is one place to change it.",
        fontSize = 11.5.sp,
        lineHeight = 15.sp,
        color = altarisMutedText(),
    )
    PrismCard(accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrismOrb(active = config.configured, accent = accent)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    config.model.ifBlank { "No model selected" },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                Text(
                    if (config.configured) "configured" else "add a URL and key in Settings",
                    fontSize = 9.5.sp,
                    color = altarisMutedText(),
                )
            }
        }
    }
    PrismNote(
        "The selected agent keeps its own model — set it with the model pill in the chat."
    )
}

/** The context breakdown. */
@Composable
private fun ContextSheet(accent: Color, estimate: ContextBudget.Estimate) {
    Text("Context", fontSize = 16.sp, fontWeight = FontWeight.Medium)
    Text(
        "Estimated from character counts. The exact figure belongs to the model.",
        fontSize = 11.sp,
        color = altarisMutedText(),
    )
    PrismCard(accent = accent) {
        Text(
            estimate.total.toString() + " / " + estimate.window + " tokens",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            estimate.remaining.toString() + " left · " +
                (estimate.fraction * 100).toInt() + "% used",
            fontSize = 10.sp,
            color = altarisMutedText(),
        )
        Spacer(Modifier.height(Prism.Tight))
        if (estimate.parts.isEmpty()) {
            PrismNote("No messages yet.")
        }
        estimate.parts.forEach { part ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    part.label,
                    fontSize = 10.5.sp,
                    modifier = Modifier.width(70.dp),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(part.fraction.coerceIn(0f, 1f))
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(accent)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    part.tokens.toString(),
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = altarisMutedText(),
                    modifier = Modifier.width(52.dp),
                )
            }
        }
    }
}

@Composable
private fun ThreadView(session: QuinnSession, accent: Color, modifier: Modifier = Modifier) {
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
private fun TurnBubble(turn: Turn, accent: Color) {
    val scheme = MaterialTheme.colorScheme
    val mine = turn.role == TurnRole.USER
    val shape = RoundedCornerShape(Prism.PanelRadius)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.94f)
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
            if (turn.steps.isNotEmpty()) {
                turn.steps.forEach { step ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    when {
                                        step.error.isNotBlank() -> scheme.error
                                        step.running -> accent
                                        else -> accent.copy(alpha = 0.45f)
                                    }
                                )
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            step.tool,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (step.running) accent else altarisMutedText(),
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            when {
                                step.running -> "running"
                                step.error.isNotBlank() -> "failed"
                                else -> "${step.ms} ms"
                            },
                            fontSize = 9.sp,
                            color = if (step.error.isNotBlank()) scheme.error else altarisMutedText(),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
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
                WorkingRow(accent)
            }
        }
    }
}

@Composable
private fun WorkingRow(accent: Color) {
    val transition = rememberInfiniteTransition(label = "working")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "workingAlpha",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        PrismOrb(active = true, accent = accent)
        Spacer(Modifier.width(6.dp))
        Text("working…", fontSize = 10.sp, color = accent.copy(alpha = alpha))
    }
}

@Composable
private fun ChatEmpty(
    accent: Color,
    target: AgentTarget?,
    targetCount: Int,
    scanning: Boolean,
    reaReady: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(top = Prism.Stack),
        verticalArrangement = Arrangement.spacedBy(Prism.Stack),
    ) {
        PrismCard(accent = accent) {
            Text(
                when {
                    scanning -> "Scanning your distros…"
                    target == null -> "Pick an agent to start"
                    else -> "Ready to talk to " + target.label
                },
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            PrismNote(
                when {
                    scanning -> "Looking for agent CLIs in every installed distro."
                    targetCount == 0 ->
                        "No agent found yet. Install one in a distro, or add it by name " +
                            "from the agent pill. Quinn links installed agents automatically."
                    else ->
                        "$targetCount agents across your distros. Use the agent pill to switch, " +
                            "the model pill for that agent's model, and + to attach any file. " +
                            "Type / for the agent's commands, // for Quinn's."
                }
            )
        }
        if (reaReady) {
            PrismCard(accent = accent) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PrismOrb(active = true, accent = accent)
                    Spacer(Modifier.width(8.dp))
                    Text("REA is ready", fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(4.dp))
                PrismNote(
                    "Pick REA as the agent and attach an APK, binary or archive. " +
                        "The REA tab shows every tool call live while it runs."
                )
            }
        }
    }
}

private fun displayName(context: Context, uri: Uri): String {
    val fromProvider = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else null
            }
    }.getOrNull()
    return fromProvider?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')
        ?: "attachment"
}
