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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altaris.linux.core.quinn.AgentTarget
import com.altaris.linux.core.quinn.GuestFiles
import com.altaris.linux.core.quinn.QuinnEngine
import com.altaris.linux.core.quinn.QuinnModelConfig
import com.altaris.linux.core.quinn.QuinnSession
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
import com.altaris.linux.ui.components.PrismSectionLabel
import com.altaris.linux.ui.theme.Prism
import com.altaris.linux.ui.theme.altarisMutedText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Quinn's chat surface: pick agents, talk to one or several, keep every thread
 * separate.
 *
 * The composer is always present. The first version only rendered it once a
 * session existed, so a device with no detected agent showed an empty screen
 * with no way to do anything — the composer is the screen, not a footer of it.
 *
 * Everything is chosen from one control panel above the composer: which
 * agent(s), which thread, which model, and which files. The panel is a compact
 * row; tapping a segment opens a sheet with the full list.
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var activeSession by remember { mutableStateOf<String?>(null) }
    var prompt by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<String>>(emptyList()) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var config by remember { mutableStateOf(QuinnModelConfig.load(context)) }
    var importing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        QuinnEngine.refreshTargets(context)
        ReaDistro.refresh(context)
    }

    // Keep the visible thread valid when a session is closed.
    LaunchedEffect(order) {
        if (activeSession != null && !sessions.containsKey(activeSession)) {
            activeSession = order.firstOrNull()
        }
    }

    val session = activeSession?.let { sessions[it] }
    val effectiveTargets = selected.mapNotNull { key -> targets.firstOrNull { it.key == key } }

    // A real file picker: any type, several files at once. The picked stream is
    // written to the cache, then copied into the target guest.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val guestDistro = effectiveTargets.firstOrNull()?.distroId
                ?: session?.target?.distroId
                ?: QuinnEngine.HOST_DISTRO
            val isRea = effectiveTargets.any { it.kind == TargetKind.REA } ||
                session?.target?.kind == TargetKind.REA
            val distroForFiles = if (isRea) reaState.method?.id ?: guestDistro else guestDistro
            val imported = ArrayList<String>()
            for (uri in uris) {
                val name = displayName(context, uri)
                val file = withContext(Dispatchers.IO) {
                    GuestFiles.materialize(context, name) { context.contentResolver.openInputStream(uri) }
                }
                if (file == null) continue
                val guestPath = withContext(Dispatchers.IO) {
                    GuestFiles.importInto(context, distroForFiles, file.absolutePath)
                }
                if (guestPath != null) imported.add(guestPath)
            }
            importing = false
            if (imported.isNotEmpty()) attachments = attachments + imported
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (error != null) {
            PrismNote(error!!, color = MaterialTheme.colorScheme.error)
        }

        // The thread list is the only scrollable, weighted part; everything
        // else is pinned so the composer can never be pushed off-screen.
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (session == null || session.turns.isEmpty()) {
                EmptyThread(
                    accent = accent,
                    targetCount = targets.size,
                    reaReady = reaState.installed,
                    scanning = scanning,
                )
            } else {
                ThreadView(session = session, accent = accent)
            }
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
            Spacer(Modifier.height(Prism.ControlGap))
        }

        ControlPanel(
            accent = accent,
            targets = targets,
            selected = effectiveTargets,
            scanning = scanning,
            session = session,
            model = session?.model ?: config.model,
            attachmentCount = attachments.size,
            importing = importing,
            onAgents = { sheet = Sheet.AGENTS },
            onSessions = { sheet = Sheet.SESSIONS },
            onModel = { sheet = Sheet.MODEL },
            onAttach = { picker.launch(arrayOf("*/*")) },
            onRefresh = { QuinnEngine.refreshTargets(context) },
        )

        if (attachments.isNotEmpty()) {
            AttachmentStrip(
                accent = accent,
                paths = attachments,
                onRemove = { attachments = attachments - it },
            )
        }

        Spacer(Modifier.height(Prism.ControlGap))
        Composer(
            accent = accent,
            prompt = prompt,
            busy = running > 0,
            targetCount = effectiveTargets.size,
            canSend = effectiveTargets.isNotEmpty() || session != null,
            onPrompt = { prompt = it },
            onSend = {
                val fanTargets = effectiveTargets.ifEmpty {
                    listOfNotNull(session?.target)
                }
                val ids = QuinnEngine.send(
                    context = context,
                    prompt = prompt,
                    targets = fanTargets,
                    files = attachments,
                    model = session?.model ?: config.model.ifBlank { null },
                )
                if (ids.isNotEmpty()) {
                    activeSession = ids.first()
                    prompt = ""
                    attachments = emptyList()
                }
            },
        )
    }

    // ---- sheets -----------------------------------------------------------

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
                    .heightIn(max = 560.dp)
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (activeSheet) {
                    Sheet.AGENTS -> AgentSheet(
                        accent = accent,
                        targets = targets,
                        selected = selected,
                        onToggle = { target ->
                            selected = if (selected.contains(target.key)) selected - target.key
                            else selected + target.key
                        },
                        onOpen = { target ->
                            val opened = QuinnEngine.openSession(target)
                            activeSession = opened.id
                            sheet = null
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
                    Sheet.MODEL -> ModelSheet(
                        accent = accent,
                        config = config,
                        onConfig = { next ->
                            config = next
                            QuinnModelConfig.save(context, next)
                        },
                        onPick = { model ->
                            config = config.copy(model = model)
                            QuinnModelConfig.save(context, config)
                            session?.let { QuinnEngine.setModel(it.id, model) }
                            sheet = null
                        },
                    )
                }
            }
        }
    }
}

/** Which bottom sheet is open. */
private enum class Sheet { AGENTS, SESSIONS, MODEL }

/** The compact always-visible control row above the composer. */
@Composable
private fun ControlPanel(
    accent: Color,
    targets: List<AgentTarget>,
    selected: List<AgentTarget>,
    scanning: Boolean,
    session: QuinnSession?,
    model: String,
    attachmentCount: Int,
    importing: Boolean,
    onAgents: () -> Unit,
    onSessions: () -> Unit,
    onModel: () -> Unit,
    onAttach: () -> Unit,
    onRefresh: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Segment(
            accent = accent,
            label = when {
                selected.size > 1 -> "${selected.size} agents"
                selected.size == 1 -> selected.first().label
                session != null -> session.target.label
                scanning -> "Scanning…"
                else -> "Pick agent"
            },
            detail = when {
                selected.size > 1 -> "fan-out"
                selected.size == 1 -> selected.first().distroName
                session != null -> session.target.distroName
                targets.isEmpty() -> "${targets.size} found"
                else -> "${targets.size} found"
            },
            highlighted = selected.isNotEmpty(),
            onClick = onAgents,
        )
        Segment(
            accent = accent,
            label = session?.displayTitle?.take(18) ?: "New thread",
            detail = "session",
            highlighted = session != null,
            onClick = onSessions,
        )
        Segment(
            accent = accent,
            label = model.ifBlank { "No model" }.let { QuinnModelConfig.shortName(it) }.take(16),
            detail = "model",
            highlighted = model.isNotBlank(),
            onClick = onModel,
        )
        Segment(
            accent = accent,
            label = if (importing) "Adding…" else "Attach",
            detail = if (attachmentCount > 0) "$attachmentCount file(s)" else "file / image",
            highlighted = attachmentCount > 0,
            onClick = onAttach,
            leading = {
                Icon(
                    Icons.Default.AttachFile,
                    contentDescription = null,
                    tint = if (attachmentCount > 0) accent else scheme.onSurfaceVariant,
                    modifier = Modifier.size(13.dp),
                )
            },
        )
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(Prism.ChipRadius))
                .border(1.dp, scheme.outline, RoundedCornerShape(Prism.ChipRadius))
                .clickable(onClick = onRefresh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = "Rescan agents",
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

/** One segment of the control panel: a label, a quiet caption, a chevron. */
@Composable
private fun Segment(
    accent: Color,
    label: String,
    detail: String,
    highlighted: Boolean,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Prism.ChipRadius)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(if (highlighted) accent.copy(alpha = 0.10f) else scheme.surfaceVariant)
            .border(1.dp, if (highlighted) accent.copy(alpha = 0.45f) else scheme.outline, shape)
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(5.dp))
        }
        Column {
            Text(
                label,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                color = if (highlighted) accent else scheme.onSurface,
            )
            Text(detail, fontSize = 8.5.sp, maxLines = 1, color = altarisMutedText())
        }
        Icon(
            Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = altarisMutedText(),
            modifier = Modifier.size(13.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AgentSheet(
    accent: Color,
    targets: List<AgentTarget>,
    selected: Set<String>,
    onToggle: (AgentTarget) -> Unit,
    onOpen: (AgentTarget) -> Unit,
    onRefresh: () -> Unit,
) {
    Text("Agents", fontSize = 16.sp, fontWeight = FontWeight.Medium)
    Text(
        "Tap to open a thread, or select several and one message goes to all of " +
            "them — each in its own thread.",
        fontSize = 11.5.sp,
        lineHeight = 15.sp,
        color = altarisMutedText(),
    )
    if (targets.isEmpty()) {
        PrismNote(
            "No agent found. Quinn scans every installed distro, including chroot " +
                "and named installs. Install an agent (or open a distro once so its " +
                "files are readable), then tap Rescan."
        )
        PrismSecondaryButton(
            text = "Rescan",
            accent = accent,
            icon = Icons.Default.Refresh,
            modifier = Modifier.fillMaxWidth(),
            onClick = onRefresh,
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(targets, key = { it.key }) { target ->
            val picked = target.key in selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Prism.ChipRadius))
                    .background(
                        if (picked) accent.copy(alpha = 0.10f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .border(
                        1.dp,
                        if (picked) accent.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(Prism.ChipRadius),
                    )
                    .combinedClickable(
                        onClick = { onOpen(target) },
                        onLongClick = { onToggle(target) },
                    )
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PrismOrb(active = picked, accent = accent)
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        target.label,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                    Text(
                        target.distroName +
                            when {
                                target.kind == TargetKind.REA -> " · analysis"
                                target.headless -> " · headless"
                                else -> " · terminal"
                            },
                        fontSize = 10.sp,
                        color = altarisMutedText(),
                        maxLines = 1,
                    )
                }
                PrismChip(
                    if (picked) "Selected" else "Select",
                    accent,
                    selected = picked,
                    onClick = { onToggle(target) },
                )
            }
        }
    }
}

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
        PrismNote("No thread yet. Pick an agent and send a message.")
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

@Composable
private fun ModelSheet(
    accent: Color,
    config: QuinnModelConfig.Config,
    onConfig: (QuinnModelConfig.Config) -> Unit,
    onPick: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var fetching by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }

    Text("Model", fontSize = 16.sp, fontWeight = FontWeight.Medium)
    Text(
        "The endpoint Quinn uses to drive REA. REA itself has no model.",
        fontSize = 11.5.sp,
        lineHeight = 15.sp,
        color = altarisMutedText(),
    )
    OutlinedTextField(
        value = config.baseUrl,
        onValueChange = { onConfig(config.copy(baseUrl = it)) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Base URL", fontSize = 11.sp) },
        placeholder = { Text("https://integrate.api.nvidia.com/v1", fontSize = 11.sp) },
        textStyle = MaterialTheme.typography.bodySmall,
        singleLine = true,
    )
    OutlinedTextField(
        value = config.apiKey,
        onValueChange = { onConfig(config.copy(apiKey = it)) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("API key", fontSize = 11.sp) },
        placeholder = { Text("stored on this device only", fontSize = 11.sp) },
        textStyle = MaterialTheme.typography.bodySmall,
        singleLine = true,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(Prism.ControlGap)) {
        PrismSecondaryButton(
            text = if (fetching) "Fetching…" else "Fetch models",
            accent = accent,
            icon = Icons.Default.Refresh,
            modifier = Modifier.weight(1f),
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
                    onConfig(next)
                    val fetched = withContext(Dispatchers.IO) { fetchModels(next) }
                    fetching = false
                    if (fetched.isEmpty()) {
                        message = "No models returned. Check the URL and key."
                    } else {
                        onConfig(next.copy(models = fetched, model = next.model.ifBlank { fetched.first() }))
                        message = "${fetched.size} models"
                    }
                }
            },
        )
    }
    if (config.models.isNotEmpty()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search ${config.models.size} models", fontSize = 11.sp) },
            textStyle = MaterialTheme.typography.bodySmall,
            singleLine = true,
        )
        val shown = QuinnModelConfig.search(config.models, query)
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            items(shown.take(200), key = { it }) { model ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Prism.ChipRadius))
                        .background(
                            if (model == config.model) accent.copy(alpha = 0.12f)
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
                    if (model == config.model) {
                        Text("selected", fontSize = 9.sp, color = accent)
                    }
                }
            }
        }
    } else {
        PrismNote("Fetch the list to choose a model.")
    }
    if (message != null) PrismNote(message!!)
}

@Composable
private fun AttachmentStrip(
    accent: Color,
    paths: List<String>,
    onRemove: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        paths.forEach { path ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(Prism.ChipRadius))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        1.dp,
                        accent.copy(alpha = 0.35f),
                        RoundedCornerShape(Prism.ChipRadius),
                    )
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

/** One chip per live thread, so several conversations stay reachable. */
@Composable
private fun SessionStrip(
    accent: Color,
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
    accent: Color,
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
                TurnSteps(turn, accent)
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

/** Tool calls under a REA reply, newest last. */
@Composable
private fun TurnSteps(turn: Turn, accent: Color) {
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
                            step.error.isNotBlank() -> MaterialTheme.colorScheme.error
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
                color = if (step.error.isNotBlank()) MaterialTheme.colorScheme.error
                else altarisMutedText(),
            )
        }
    }
}

/** A breathing "working" indicator, so a long turn never looks frozen. */
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
        Text(
            "working…",
            fontSize = 10.sp,
            color = accent.copy(alpha = alpha),
            modifier = Modifier.graphicsLayer { this.alpha = 1f },
        )
    }
}

@Composable
private fun Composer(
    accent: Color,
    prompt: String,
    busy: Boolean,
    targetCount: Int,
    canSend: Boolean,
    onPrompt: (String) -> Unit,
    onSend: () -> Unit,
) {
    Row(verticalAlignment = Alignment.Bottom) {
        OutlinedTextField(
            value = prompt,
            onValueChange = onPrompt,
            modifier = Modifier.weight(1f).heightIn(min = 52.dp, max = 150.dp),
            placeholder = {
                Text(
                    when {
                        targetCount > 1 -> "Message $targetCount agents…"
                        !canSend -> "Pick an agent to start…"
                        else -> "Ask, command, or describe what to do…"
                    },
                    fontSize = 12.sp,
                )
            },
            textStyle = MaterialTheme.typography.bodyMedium,
            shape = RoundedCornerShape(Prism.PanelRadius),
        )
        Spacer(Modifier.width(Prism.ControlGap))
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(Prism.PanelRadius))
                .background(
                    if (prompt.isNotBlank() && !busy && canSend) accent
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .clickable(enabled = prompt.isNotBlank() && !busy && canSend, onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
                tint = if (prompt.isNotBlank() && !busy && canSend) Color.White
                else altarisMutedText(),
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

@Composable
private fun EmptyThread(
    accent: Color,
    targetCount: Int,
    reaReady: Boolean,
    scanning: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(top = Prism.Stack),
        verticalArrangement = Arrangement.spacedBy(Prism.Stack),
    ) {
        PrismCard(accent = accent) {
            Text(
                if (scanning) "Scanning your distros…" else "Quinn is ready",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            PrismNote(
                when {
                    scanning -> "Looking for agent CLIs in every installed distro."
                    targetCount == 0 ->
                        "No agent found yet. Install one in any distro — Quinn links it " +
                            "automatically. If it is in a chroot, open that distro once so " +
                            "root access lets Quinn read it."
                    else ->
                        "$targetCount targets available across your distros. Pick one above, " +
                            "attach files if you need them, and send. Select several agents to " +
                            "send one message to all of them at once."
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
                    "Pick REA as the agent, attach an APK, binary or archive, and ask " +
                        "about it. Quinn drives REA's tools and shows every call in the " +
                        "REA tab while it runs."
                )
            }
        }
    }
}

/** The display name behind a picked URI. */
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
