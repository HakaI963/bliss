package com.altaris.linux.ui.screens.quinn

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** The two pages of the REA tab. */
private enum class ReaPage { ANALYSIS, FILES }

/**
 * The REA analysis surface.
 *
 * Information design follows the reference recording — a rail of sections, a
 * status block, stat cards and live step output — but stacked for a phone
 * instead of the recording's three desktop columns.
 *
 * The whole tab is gated on the hidden REA distro: when it is missing the page
 * is frosted and says so, because every control here needs it.
 */
@Composable
fun QuinnReaTab(
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var page by remember { mutableStateOf(ReaPage.ANALYSIS) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Prism.ControlGap),
        ) {
            PageChip("REA", accent, page == ReaPage.ANALYSIS) { page = ReaPage.ANALYSIS }
            PageChip("Files", accent, page == ReaPage.FILES) { page = ReaPage.FILES }
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
            .heightIn(min = Prism.ChipHeight)
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
    var tick by remember { mutableIntStateOf(0) }
    var config by remember { mutableStateOf(QuinnModelConfig.load(context)) }
    var distroState by remember { mutableStateOf(ReaDistro.state.value) }
    var prompt by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var fetchingModels by remember { mutableStateOf(false) }
    var modelError by remember { mutableStateOf<String?>(null) }

    val runs by ReaAnalysis.runs.collectAsState()
    val busy by ReaAnalysis.busy.collectAsState()
    val reaStatus by ReaManager.status.collectAsState()

    LaunchedEffect(tick) {
        distroState = withContext(Dispatchers.IO) {
            ReaDistro.refresh(context)
            ReaDistro.state.value
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(Prism.Stack),
        ) {
            // Brand block: "Altaris × REA", the recording's header idea.
            BrandHeader(accent, distroState, reaStatus.running)

            // Model + key: required before anything can run.
            ModelCard(
                accent = accent,
                config = config,
                fetching = fetchingModels,
                error = modelError,
                onConfig = { next ->
                    config = next
                    QuinnModelConfig.save(context, next)
                },
                onFetchModels = {
                    fetchingModels = true
                    modelError = null
                    scope.launch {
                        val fetched = withContext(Dispatchers.IO) { fetchModels(config) }
                        fetchingModels = false
                        if (fetched.isEmpty()) {
                            modelError = "No models returned. Check the base URL and key."
                        } else {
                            val next = config.copy(
                                models = fetched,
                                model = config.model.ifBlank { fetched.first() },
                            )
                            config = next
                            QuinnModelConfig.save(context, next)
                        }
                    }
                },
            )

            if (!distroState.installed) {
                NotInstalledCard(accent)
            } else {
                StatusCard(accent, distroState, reaStatus.toolCount, reaStatus.running)

                PrismSectionLabel("Run an analysis", accent)
                PrismCard(accent = accent) {
                    OutlinedTextField(
                        value = target,
                        onValueChange = { target = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Path inside the REA distro (optional)", fontSize = 12.sp) },
                        textStyle = MaterialTheme.typography.bodySmall,
                        singleLine = true,
                    )
                    Spacer(Modifier.height(Prism.Tight))
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 84.dp, max = 160.dp),
                        placeholder = { Text("What do you want to know about it?", fontSize = 12.sp) },
                        textStyle = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(Prism.ControlGap))
                    PrismButton(
                        text = if (busy) "Analysing…" else "Analyse",
                        accent = accent,
                        icon = Icons.AutoMirrored.Filled.Send,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = prompt.isNotBlank() && !busy && config.configured,
                        onClick = { ReaAnalysis.analyze(context, prompt, target) },
                    )
                    if (!config.configured) {
                        Spacer(Modifier.height(Prism.Tight))
                        PrismNote(
                            "Add a model and API key above first.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                runs.forEach { run -> RunCard(accent, run) }
            }
        }

        // Frosted gate when REA is missing: the page stays visible behind it so
        // the user can see what the tab will do.
        if (!distroState.installed) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .clip(RoundedCornerShape(Prism.PanelRadius))
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.55f))
                    .blur(10.dp),
            )
        }
    }
}

@Composable
private fun BrandHeader(
    accent: Color,
    distro: ReaDistro.State,
    running: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Prism.PanelRadius))
            .background(
                Brush.horizontalGradient(
                    listOf(accent.copy(alpha = 0.14f), accent.copy(alpha = 0.04f))
                )
            )
            .border(1.dp, accent.copy(alpha = 0.30f), RoundedCornerShape(Prism.PanelRadius))
            .padding(Prism.PanelPad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PrismOrb(active = running, accent = accent)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "ALTARIS × REA",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp,
                color = accent,
            )
            Text(
                when {
                    !distro.installed -> "Analysis environment not installed"
                    running -> "Reverse engineering · server live"
                    else -> "Reverse engineering · server stopped"
                },
                fontSize = 11.sp,
                color = altarisMutedText(),
            )
        }
        Text(
            if (distro.installed) "READY" else "OFFLINE",
            fontSize = 9.5.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            color = if (distro.installed) accent else scheme.onSurfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(Prism.ChipRadius))
                .background(if (distro.installed) accent.copy(alpha = 0.14f) else scheme.surfaceVariant)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ModelCard(
    accent: Color,
    config: QuinnModelConfig.Config,
    fetching: Boolean,
    error: String?,
    onConfig: (QuinnModelConfig.Config) -> Unit,
    onFetchModels: () -> Unit,
) {
    PrismSectionLabel("Model", accent)
    PrismCard(accent = accent) {
        OutlinedTextField(
            value = config.baseUrl,
            onValueChange = { onConfig(config.copy(baseUrl = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Base URL · https://api.openai.com/v1", fontSize = 12.sp) },
            textStyle = MaterialTheme.typography.bodySmall,
            singleLine = true,
        )
        Spacer(Modifier.height(Prism.Tight))
        OutlinedTextField(
            value = config.apiKey,
            onValueChange = { onConfig(config.copy(apiKey = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("API key (stored on this device only)", fontSize = 12.sp) },
            textStyle = MaterialTheme.typography.bodySmall,
            singleLine = true,
        )
        Spacer(Modifier.height(Prism.Tight))
        Row(horizontalArrangement = Arrangement.spacedBy(Prism.ControlGap)) {
            PrismSecondaryButton(
                text = if (fetching) "Fetching…" else "Fetch models",
                accent = accent,
                icon = Icons.Default.Refresh,
                modifier = Modifier.weight(1f),
                enabled = config.baseUrl.isNotBlank() && config.apiKey.isNotBlank() && !fetching,
                onClick = onFetchModels,
            )
            PrismChip(
                config.model.ifBlank { "No model" },
                accent,
                selected = config.model.isNotBlank(),
                modifier = Modifier.weight(1f),
            )
        }
        if (config.models.isNotEmpty()) {
            Spacer(Modifier.height(Prism.Tight))
            Row(
                modifier = Modifier.fillMaxWidth()
                    .horizontalScrollCompat(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                config.models.take(24).forEach { model ->
                    PrismChip(
                        model,
                        accent,
                        selected = model == config.model,
                        onClick = { onConfig(config.copy(model = model)) },
                    )
                }
            }
        }
        if (error != null) {
            Spacer(Modifier.height(Prism.Tight))
            PrismNote(error, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Local helper so the model row scrolls without importing the modifier twice. */
@Composable
private fun Modifier.horizontalScrollCompat(): Modifier =
    this.then(androidx.compose.foundation.horizontalScroll(rememberScrollState()))

@Composable
private fun NotInstalledCard(accent: Color) {
    PrismCard(accent = accent) {
        Text("REA is not installed", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        PrismNote(
            "Quinn runs analysis inside a private distro it creates for itself, so " +
                "nothing is installed into your own distros and no MCP link is needed. " +
                "Install it once from Tools → Reverse engineering, where you can pick " +
                "proot (no root) or chroot (needs root)."
        )
    }
}

@Composable
private fun StatusCard(
    accent: Color,
    distro: ReaDistro.State,
    toolCount: Int,
    running: Boolean,
) {
    PrismSectionLabel("Environment", accent)
    PrismCard(accent = accent) {
        Row(horizontalArrangement = Arrangement.spacedBy(Prism.Stack)) {
            Stat("Method", distro.method?.label?.uppercase() ?: "—", accent)
            Stat("Size", formatBytes(distro.sizeBytes), accent)
            Stat("Tools", toolCount.toString(), accent)
            Stat("Server", if (running) "LIVE" else "IDLE", accent)
        }
        if (distro.error.isNotBlank()) {
            Spacer(Modifier.height(Prism.Tight))
            PrismNote(distro.error, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun Stat(label: String, value: String, accent: Color) {
    Column {
        Text(
            value,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = accent,
            maxLines = 1,
        )
        Text(label.uppercase(), fontSize = 9.sp, letterSpacing = 0.6.sp, color = altarisMutedText())
    }
}

@Composable
private fun RunCard(accent: Color, run: ReaAnalysis.Run) {
    PrismCard(accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrismOrb(active = run.running, accent = accent)
            Spacer(Modifier.width(8.dp))
            Text(
                run.prompt,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (run.model.isNotBlank()) {
                PrismChip(run.model.take(18), accent)
            }
        }
        if (run.target.isNotBlank()) {
            Text(
                run.target,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = altarisMutedText(),
                maxLines = 1,
            )
        }
        if (run.steps.isNotEmpty()) {
            Spacer(Modifier.height(Prism.Tight))
            run.steps.forEach { step ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        step.tool,
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = accent,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (step.error.isNotBlank()) "failed" else "${step.ms} ms",
                        fontSize = 9.5.sp,
                        color = if (step.error.isNotBlank()) MaterialTheme.colorScheme.error
                        else altarisMutedText(),
                    )
                }
            }
        }
        if (run.answer.isNotBlank()) {
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

// ---------------------------------------------------------------------- files

@Composable
private fun ReaFilesPage(accent: Color) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val distro = ReaDistro.state.value
    var path by remember { mutableStateOf("/root") }
    var entries by remember { mutableStateOf<List<com.altaris.linux.core.quinn.ReaFiles.Entry>>(emptyList()) }
    var note by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    fun load(target: String) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { com.altaris.linux.core.quinn.ReaFiles.list(context, target) }
            result.onSuccess {
                entries = it
                path = target
                note = null
            }.onFailure { note = it.message }
        }
    }

    LaunchedEffect(Unit) { if (distro.installed) load(path) }

    if (!distro.installed) {
        NotInstalledCard(accent)
        return
    }

    PrismSectionLabel("REA files", accent)
    PrismCard(accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Folder, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                path,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            PrismChip("Up", accent, onClick = {
                val parent = path.substringBeforeLast('/', "/").ifBlank { "/" }
                if (parent != path) load(parent)
            })
        }
        Spacer(Modifier.height(Prism.Tight))
        if (entries.isEmpty()) {
            PrismNote("Empty folder")
        } else {
            entries.take(200).forEach { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (entry.dir) load(entry.path)
                            else scope.launch {
                                val read = withContext(Dispatchers.IO) {
                                    com.altaris.linux.core.quinn.ReaFiles.read(context, entry.path)
                                }
                                read.onSuccess { editing = entry.path to it }
                                    .onFailure { note = it.message }
                            }
                        }
                        .padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (entry.dir) "▸ ${entry.name}" else entry.name,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (!entry.dir) {
                        Text(formatBytes(entry.size), fontSize = 9.5.sp, color = altarisMutedText())
                        Spacer(Modifier.width(8.dp))
                    }
                    PrismChip("Del", accent, onClick = { pendingDelete = entry.path })
                }
            }
        }
        if (note != null) {
            Spacer(Modifier.height(Prism.Tight))
            PrismNote(note!!, color = MaterialTheme.colorScheme.error)
        }
    }

    // Editor: read a file, edit it, write it back.
    editing?.let { (filePath, content) ->
        var draft by remember(filePath) { mutableStateOf(content) }
        PrismCard(accent = accent) {
            Text(filePath, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
            Spacer(Modifier.height(Prism.Tight))
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp),
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
                                note = "Saved $filePath"
                                editing = null
                                load(path)
                            }.onFailure { note = it.message }
                        }
                    },
                )
            }
        }
    }

    // Delete confirmation: the only destructive action in the tab.
    pendingDelete?.let { target ->
        PrismCard(accent = accent) {
            Text("Delete this?", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                target,
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
                                com.altaris.linux.core.quinn.ReaFiles.delete(context, target)
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

// -------------------------------------------------------------------- helpers

/** Fetch `<base>/models` with the user's key. Empty list means failure. */
private fun fetchModels(config: QuinnModelConfig.Config): List<String> {
    if (config.baseUrl.isBlank() || config.apiKey.isBlank()) return emptyList()
    val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    val request = Request.Builder()
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

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "—"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${bytes / 1024} KB"
    bytes < 1024L * 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
    else -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}
