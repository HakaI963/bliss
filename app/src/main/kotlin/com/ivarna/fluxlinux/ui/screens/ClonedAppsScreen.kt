package com.ivarna.fluxlinux.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import com.ivarna.fluxlinux.core.clone.CloneAi
import com.ivarna.fluxlinux.core.clone.CloneEngine
import com.ivarna.fluxlinux.core.clone.ShellRunner
import com.ivarna.fluxlinux.ui.components.QCard
import com.ivarna.fluxlinux.ui.components.QEmptyState
import com.ivarna.fluxlinux.ui.components.QLogPane
import com.ivarna.fluxlinux.ui.components.QPill
import com.ivarna.fluxlinux.ui.components.QPrimaryButton
import com.ivarna.fluxlinux.ui.components.QRow
import com.ivarna.fluxlinux.ui.components.QScreenTitle
import com.ivarna.fluxlinux.ui.components.QStatusRow
import com.ivarna.fluxlinux.ui.components.QTextField
import com.ivarna.fluxlinux.ui.components.Quartz
import com.ivarna.fluxlinux.ui.theme.fluxMutedText
import com.ivarna.fluxlinux.ui.theme.rememberStaggerEntrance
import com.ivarna.fluxlinux.ui.theme.staggerIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class AppTab { DISPLAY, AI, CONFIGURE, LOGS }

/**
 * Apps tab — only apps cloned into the Bliss space (add them in Tools -> Clone).
 * Tapping one runs it on the hidden display and opens its live window.
 */
@Composable
fun ClonedAppsScreen() {
    val apps by CloneEngine.apps.collectAsState()
    val running by CloneEngine.running.collectAsState()
    val status by CloneEngine.status.collectAsState()

    var selected by remember { mutableStateOf<CloneEngine.CloneApp?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { CloneEngine.refresh() }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(4000)
            notice = null
        }
    }

    val banner = notice ?: status
    val sel = selected
    if (sel == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Quartz.ScreenPad, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(Quartz.Gap),
        ) {
            val entrance = rememberStaggerEntrance()
            QScreenTitle(
                "Apps",
                "Cloned inside Bliss — runs on a hidden display.",
                modifier = Modifier.staggerIn(0, entrance),
            )

            if (banner != null) {
                Text(banner, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }

            if (running != null) {
                val runApp = apps.firstOrNull { it.packageName == running }
                QCard(modifier = Modifier.staggerIn(1, entrance)) {
                    QRow(
                        title = "Running · ${runApp?.label ?: running}",
                        subtitle = "On the hidden display",
                        leading = {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                        },
                        trailing = {
                            TextButton(onClick = {
                                selected = runApp ?: CloneEngine.CloneApp(
                                    packageName = running!!,
                                    label = running!!,
                                    version = "",
                                    icon = null,
                                    component = null,
                                )
                            }) { Text("Show", fontSize = 12.sp) }
                        },
                    )
                }
            }

            QCard(modifier = Modifier.staggerIn(2, entrance)) {
                if (apps.isEmpty()) {
                    QEmptyState(
                        "No cloned apps yet",
                        "Add one in Tools → Clone (clone an installed app, pick an APK, or paste a link).",
                    )
                } else {
                    apps.forEach { app ->
                        QRow(
                            title = app.label,
                            subtitle = if (app.version.isBlank()) app.packageName
                            else "${app.packageName} · v${app.version}",
                            leading = {
                                com.ivarna.fluxlinux.ui.components.QIconTile(
                                    bitmap = app.icon,
                                    size = 34.dp,
                                )
                            },
                            trailing = {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = "Run",
                                    tint = fluxMutedText(),
                                    modifier = Modifier.size(17.dp),
                                )
                            },
                            onClick = {
                                CloneEngine.launch(app.packageName)
                                selected = app
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(96.dp))
        }
    } else {
        AppDetail(
            app = sel,
            onBack = { selected = null },
            onKilled = { label ->
                notice = "Killed $label"
                selected = null
            },
            onNotice = { notice = it },
        )
    }
}

@Composable
private fun AppDetail(
    app: CloneEngine.CloneApp,
    onBack: () -> Unit,
    onKilled: (String) -> Unit,
    onNotice: (String) -> Unit,
) {
    var tab by remember { mutableStateOf(AppTab.DISPLAY) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Quartz.ScreenPad, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(Quartz.Gap),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(6.dp))
            com.ivarna.fluxlinux.ui.components.QIconTile(bitmap = app.icon, size = 32.dp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    app.label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                )
                Text(
                    app.packageName,
                    fontSize = 10.5.sp,
                    color = fluxMutedText(),
                    maxLines = 1,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QPill("Display", tab == AppTab.DISPLAY) { tab = AppTab.DISPLAY }
            QPill("AI", tab == AppTab.AI) { tab = AppTab.AI }
            QPill("Configure", tab == AppTab.CONFIGURE) { tab = AppTab.CONFIGURE }
            QPill("Logs", tab == AppTab.LOGS) { tab = AppTab.LOGS }
        }

        when (tab) {
            AppTab.DISPLAY -> DisplaySection(app, onKilled, onNotice)
            AppTab.AI -> AiSection(app, onNotice)
            AppTab.CONFIGURE -> ConfigureSection(app, onNotice)
            AppTab.LOGS -> LogsSection()
        }

        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun DisplaySection(
    app: CloneEngine.CloneApp,
    onKilled: (String) -> Unit,
    onNotice: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val frameState by CloneEngine.frame.collectAsState()
    val running by CloneEngine.running.collectAsState()
    val dw by CloneEngine.displayW.collectAsState()
    val dh by CloneEngine.displayH.collectAsState()
    val vdProbe by CloneEngine.vdProbe.collectAsState()
    var textInput by remember { mutableStateOf("") }
    val isRunning = running == app.packageName

    LaunchedEffect(app.packageName) { CloneEngine.probeDisplay() }

    QCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Quartz.CardPad),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (isRunning) "Live · ${dw}×$dh" else "Not running",
                    fontSize = 11.5.sp,
                    color = fluxMutedText(),
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { CloneEngine.killNowBlocking(app.packageName) }
                            onKilled(app.label)
                        }
                    },
                ) {
                    Text("Kill", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }

            if (!isRunning) {
                QPrimaryButton("Run app") { CloneEngine.launch(app.packageName) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        vdProbe ?: "Virtual display: checking…",
                        fontSize = 11.sp,
                        color = fluxMutedText(),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { CloneEngine.probeDisplay() }) {
                        Text("Check", fontSize = 12.sp)
                    }
                }
            } else {
                Screen(dw, dh, frameState?.bitmap)

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    QPill("Back", false) { CloneEngine.key("BACK") }
                    QPill("Home", false) { CloneEngine.key("HOME") }
                    QPill("Enter", false) { CloneEngine.key("ENTER") }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    QTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        placeholder = "Type into the app",
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        CloneEngine.text(textInput)
                        textInput = ""
                    }, enabled = textInput.isNotEmpty()) {
                        Icon(Icons.Default.Send, contentDescription = "Send text", modifier = Modifier.size(20.dp))
                    }
                }
                Text(
                    "Tap and swipe the window directly, or use the buttons.",
                    fontSize = 10.5.sp,
                    color = fluxMutedText(),
                )
            }
        }
    }
}

/** Fit-to-screen live window: no scrolling, gestures map 1:1. */
@Composable
private fun Screen(dw: Int, dh: Int, bitmap: android.graphics.Bitmap?) {
    if (dw <= 0 || dh <= 0) return
    val screenH = LocalConfiguration.current.screenHeightDp
    val boxH = (screenH * 0.34f).dp.coerceIn(200.dp, 320.dp)
    var inner by remember { mutableStateOf(IntSize.Zero) }

    Box(modifier = Modifier.fillMaxWidth().height(boxH), contentAlignment = Alignment.Center) {
        BoxWithConstraints {
            val aspect = dw.toFloat() / dh.toFloat().coerceAtLeast(1f)
            val w = minOf(maxWidth, maxHeight * aspect)
            val h = w / aspect
            Box(
                modifier = Modifier
                    .size(w, h)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black)
                    .onSizeChanged { inner = it }
                    .pointerInput(dw, dh, inner) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val start = down.position
                            var pos = down.position
                            var moved = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.positionChanged()) {
                                    moved = true
                                    pos = change.position
                                    change.consume()
                                }
                                if (!change.pressed) {
                                    val sx = if (inner.width > 0) dw.toFloat() / inner.width else 1f
                                    val sy = if (inner.height > 0) dh.toFloat() / inner.height else 1f
                                    val dist = (pos - start).getDistance()
                                    if (!moved || dist < 24f) {
                                        CloneEngine.tap(start.x * sx, start.y * sy)
                                    } else {
                                        CloneEngine.swipe(start.x * sx, start.y * sy, pos.x * sx, pos.y * sy)
                                    }
                                    break
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds,
                    )
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

@Composable
private fun AiSection(app: CloneEngine.CloneApp, onNotice: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val running by CloneAi.running.collectAsState()
    val step by CloneAi.step.collectAsState()
    val maxSteps by CloneAi.maxSteps.collectAsState()
    val status by CloneAi.status.collectAsState()
    val events by CloneAi.events.collectAsState()
    val configured by CloneAi.configured.collectAsState()
    val lastAction by CloneAi.lastAction.collectAsState()
    val ocrLines by CloneAi.ocrLineCount.collectAsState()
    val epoch by CloneAi.configEpoch.collectAsState()
    val backend by CloneEngine.touchBackend.collectAsState()

    val initial = remember(epoch) { CloneAi.config() }
    var showSettings by remember(epoch) { mutableStateOf(!initial.usable) }
    var baseUrl by remember(epoch) { mutableStateOf(initial.baseUrl) }
    var apiKey by remember(epoch) { mutableStateOf(initial.apiKey) }
    var model by remember(epoch) { mutableStateOf(initial.model) }
    var vision by remember(epoch) { mutableStateOf(initial.vision) }
    var steps by remember(epoch) { mutableStateOf(initial.maxSteps) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var goal by remember(app.packageName) { mutableStateOf("") }

    QCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Quartz.CardPad),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(
                "AI control",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "ShadowAuto-style loop: screenshot → OCR → one model action → tap/swipe/type on the clone display. The phone screen is never touched.",
                fontSize = 11.sp,
                color = fluxMutedText(),
            )

            if (!showSettings) {
                val cfg = CloneAi.config()
                QStatusRow("Model", cfg.model.ifBlank { "not set" })
                QStatusRow("Endpoint", cfg.baseUrl.ifBlank { "not set" })
                QStatusRow("Vision", if (cfg.vision) "on (screenshot sent)" else "off (OCR only)")
                TextButton(onClick = { showSettings = true }) {
                    Text("Change AI settings", fontSize = 12.sp)
                }
            } else {
                QTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    placeholder = "https://api.openai.com/v1",
                    label = "API base URL",
                )
                QTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    placeholder = "sk-… (leave empty for local endpoints)",
                    label = "API key",
                    secret = true,
                )
                QTextField(
                    value = model,
                    onValueChange = { model = it },
                    placeholder = "gpt-4o-mini",
                    label = "Model",
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QPill(if (vision) "Vision: on" else "Vision: off", vision) { vision = !vision }
                    Text(
                        "only multimodal models",
                        fontSize = 10.5.sp,
                        color = fluxMutedText(),
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Max steps", fontSize = 11.sp, color = fluxMutedText())
                    listOf(15, 30, 45).forEach { value ->
                        QPill(value.toString(), steps == value) { steps = value }
                    }
                }
                QPrimaryButton("Save AI settings") {
                    CloneAi.saveConfig(baseUrl, apiKey, model, vision, steps)
                    showSettings = false
                    testResult = null
                    onNotice("AI settings saved")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        enabled = !testing,
                        onClick = {
                            testing = true
                            testResult = null
                            scope.launch {
                                testResult = CloneAi.testConnection(baseUrl, apiKey, model)
                                testing = false
                            }
                        },
                    ) { Text(if (testing) "Testing…" else "Test connection", fontSize = 12.sp) }
                    if (testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 1.5.dp,
                        )
                    }
                }
                testResult?.let {
                    Text(it, fontSize = 11.sp, color = fluxMutedText())
                }
            }
        }
    }

    QCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Quartz.CardPad),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            QTextField(
                value = goal,
                onValueChange = { goal = it },
                placeholder = "e.g. Open Settings, turn on Wi-Fi, then open the browser and search for cats",
                label = "Goal for the AI",
                singleLine = false,
                minLines = 3,
            )
            if (running) {
                QPrimaryButton("Stop AI") { CloneAi.stop() }
            } else {
                QPrimaryButton(
                    "Run AI",
                    enabled = configured && goal.isNotBlank() && backend != ShellRunner.Backend.NONE,
                ) { CloneAi.start(app.packageName, goal) }
            }
            if (backend == ShellRunner.Backend.NONE) {
                Text(
                    "Shizuku (or root) is needed — enable it in Tools → Clone.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!configured) {
                Text(
                    "Set the API endpoint and model above before running.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            status?.let {
                Text(it, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.primary)
            }
            QStatusRow("Step", "$step / $maxSteps")
            QStatusRow("Shell", backend.label)
            QStatusRow("OCR lines", ocrLines.toString())
            if (lastAction.isNotBlank()) {
                QStatusRow("Last action", lastAction)
            }
        }
    }

    QCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Quartz.CardPad + 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "AI activity",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { CloneAi.clearEvents() },
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "Clear AI log",
                        tint = fluxMutedText(),
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
            QLogPane(events, maxHeight = 240.dp, newestFirst = true)
        }
    }
}

@Composable
private fun ConfigureSection(app: CloneEngine.CloneApp, onNotice: (String) -> Unit) {
    val fps by CloneEngine.displayFps.collectAsState()
    val res by CloneEngine.displayResPreset.collectAsState()
    val dpi by CloneEngine.displayDpi.collectAsState()
    val backend by CloneEngine.touchBackend.collectAsState()
    val cloneUser by CloneEngine.cloneUserId.collectAsState()

    QCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Quartz.CardPad),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Display · fps $fps",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Slider(
                value = fps.toFloat(),
                onValueChange = { CloneEngine.displayFps.value = it.toInt().coerceIn(5, 60) },
                valueRange = 5f..60f,
                steps = 54,
            )

            Text("Resolution", fontSize = 11.5.sp, color = fluxMutedText())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CloneEngine.RES_PRESETS.forEachIndexed { i, preset ->
                    QPill("${preset.first}×${preset.second}", res == i) {
                        CloneEngine.displayResPreset.value = i
                    }
                }
            }

            Text("DPI · higher = smaller UI", fontSize = 11.5.sp, color = fluxMutedText())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CloneEngine.DPI_PRESETS.forEach { value ->
                    QPill(value.toString(), dpi == value) {
                        CloneEngine.displayDpi.value = value
                    }
                }
            }

            QPrimaryButton("Apply & restart display") {
                CloneEngine.applyDisplayConfig()
                onNotice("Display restarted")
            }

            QStatusRow("Clone space", if (cloneUser > 0) "user $cloneUser" else "not created")
            QStatusRow("Shell", backend.label)
            QStatusRow("App", app.packageName)
        }
    }
}

@Composable
private fun LogsSection() {
    var tick by remember { mutableIntStateOf(0) }
    val logs by CloneEngine.logs.collectAsState()
    val tail = remember(tick, logs.size) { CloneEngine.engineLogTail(80) }
    val clipboard = LocalClipboardManager.current

    QCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Quartz.CardPad + 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Activity",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { tick++ }, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        tint = fluxMutedText(),
                        modifier = Modifier.size(17.dp),
                    )
                }
                IconButton(
                    onClick = {
                        CloneEngine.clearLogs()
                    },
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "Clear log",
                        tint = fluxMutedText(),
                        modifier = Modifier.size(17.dp),
                    )
                }
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString((tail.ifEmpty { logs }).joinToString("\n")))
                    },
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy log",
                        tint = fluxMutedText(),
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
            QLogPane(tail.ifEmpty { logs }, maxHeight = 260.dp, newestFirst = false)
        }
    }
}
