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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import com.ivarna.fluxlinux.core.clone.CloneEngine
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

private enum class AppTab { DISPLAY, CONFIGURE, LOGS }

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
            QPill("Configure", tab == AppTab.CONFIGURE) { tab = AppTab.CONFIGURE }
            QPill("Logs", tab == AppTab.LOGS) { tab = AppTab.LOGS }
        }

        when (tab) {
            AppTab.DISPLAY -> DisplaySection(app, onKilled, onNotice)
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
    var textInput by remember { mutableStateOf("") }
    val isRunning = running == app.packageName

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
                QPrimaryButton("Open on hidden display") { CloneEngine.launch(app.packageName) }
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
            }
            QLogPane(tail.ifEmpty { logs }, maxHeight = 260.dp, newestFirst = false)
        }
    }
}
