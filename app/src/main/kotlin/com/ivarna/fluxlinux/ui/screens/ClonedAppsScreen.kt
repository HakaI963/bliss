package com.ivarna.fluxlinux.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivarna.fluxlinux.core.clone.CloneEngine
import com.ivarna.fluxlinux.core.clone.ShellRunner
import com.ivarna.fluxlinux.ui.components.GlassSettingCard
import com.ivarna.fluxlinux.ui.theme.fluxMutedText
import com.ivarna.fluxlinux.ui.theme.rememberStaggerEntrance
import com.ivarna.fluxlinux.ui.theme.staggerIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.niunaijun.blackbox.BlackBoxCore

private enum class AppsFilter { INSTALLED, CLONED }
private enum class AppTab { DISPLAY, CONFIGURE, LOGS }

/** One row in the Apps tab, from either source. */
private data class AppRef(
    val packageName: String,
    val label: String,
    val version: String,
    val isSystem: Boolean,
    val icon: Bitmap?,
)

/**
 * Apps tab: everything you can run on the hidden display.
 * - Installed: real apps on the phone (run via Shizuku/root, data untouched).
 * - Cloned: APKs installed inside Bliss (managed in Tools).
 * Tapping an app runs it and opens its display; Configure and Logs sit on top.
 */
@Composable
fun ClonedAppsScreen() {
    val systemApps by CloneEngine.systemApps.collectAsState()
    val cloneApps by CloneEngine.apps.collectAsState()
    val running by CloneEngine.running.collectAsState()
    val status by CloneEngine.status.collectAsState()

    var filter by remember { mutableStateOf(AppsFilter.INSTALLED) }
    var selected by remember { mutableStateOf<AppRef?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        CloneEngine.refreshSystemApps()
        CloneEngine.refresh()
    }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(4000)
            notice = null
        }
    }

    val apps: List<AppRef> = when (filter) {
        AppsFilter.INSTALLED -> systemApps.map {
            AppRef(it.packageName, it.label, "", true, it.icon)
        }
        AppsFilter.CLONED -> cloneApps.map {
            AppRef(it.packageName, it.label, it.version, false, it.icon)
        }
    }

    val sel = selected
    if (sel == null) {
        AppsList(
            filter = filter,
            onFilter = { filter = it },
            apps = apps,
            running = running,
            notice = notice,
            status = status,
            onOpen = { app ->
                val err = if (app.isSystem) CloneEngine.launchSystem(app.packageName)
                else run { CloneEngine.launch(app.packageName); null }
                if (err != null) notice = err
                selected = app
            },
            onShowRunning = { pkg ->
                val fromList = apps.firstOrNull { it.packageName == pkg }
                selected = fromList ?: AppRef(pkg, pkg, "", false, null)
            },
        )
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
private fun AppsList(
    filter: AppsFilter,
    onFilter: (AppsFilter) -> Unit,
    apps: List<AppRef>,
    running: String?,
    notice: String?,
    status: String?,
    onOpen: (AppRef) -> Unit,
    onShowRunning: (String) -> Unit,
) {
    val entrance = rememberStaggerEntrance()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.staggerIn(0, entrance)) {
            Text(
                "Apps",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                "Runs on a hidden display — your screen stays free.",
                fontSize = 11.5.sp,
                color = fluxMutedText(),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.staggerIn(1, entrance)) {
            FilterPill("Installed", filter == AppsFilter.INSTALLED) { onFilter(AppsFilter.INSTALLED) }
            FilterPill("Cloned", filter == AppsFilter.CLONED) { onFilter(AppsFilter.CLONED) }
        }

        if (notice != null) {
            Text(
                notice,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }

        if (running != null) {
            GlassSettingCard(modifier = Modifier.fillMaxWidth()) {
               Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(RoundedCornerShape(50))
                            .background(com.ivarna.fluxlinux.ui.theme.StatusDot),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Running · ${apps.firstOrNull { it.packageName == running }?.label ?: running}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = { onShowRunning(running) }) { Text("Show", fontSize = 12.sp) }
                }
            }
        }

        if (status != null) {
            Text(status, fontSize = 11.5.sp, color = fluxMutedText())
        }

        GlassSettingCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                if (apps.isEmpty()) {
                    Text(
                        if (filter == AppsFilter.CLONED)
                            "No cloned apps yet — install one in Tools."
                        else
                            "No installed apps found.",
                        fontSize = 12.sp,
                        color = fluxMutedText(),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                } else {
                    apps.forEach { app ->
                        AppRow(app) { onOpen(app) }
                    }
                }
            }
        }

        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        label,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@Composable
private fun AppRow(app: AppRef, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                app.label,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (app.version.isBlank()) app.packageName else "${app.packageName} · ${app.version}",
                fontSize = 10.5.sp,
                color = fluxMutedText(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Default.PlayArrow,
            contentDescription = "Run",
            tint = fluxMutedText(),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun AppIcon(app: AppRef, size: Int = 36) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val icon = app.icon
        if (icon != null) {
            Image(
                bitmap = icon.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                Icons.Default.Android,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size((size / 2).dp),
            )
        }
    }
}

@Composable
private fun AppDetail(
    app: AppRef,
    onBack: () -> Unit,
    onKilled: (String) -> Unit,
    onNotice: (String) -> Unit,
) {
    var tab by remember { mutableStateOf(AppTab.DISPLAY) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            AppIcon(app, size = 34)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    app.label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    app.packageName,
                    fontSize = 10.5.sp,
                    color = fluxMutedText(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterPill("Display", tab == AppTab.DISPLAY) { tab = AppTab.DISPLAY }
            FilterPill("Configure", tab == AppTab.CONFIGURE) { tab = AppTab.CONFIGURE }
            FilterPill("Logs", tab == AppTab.LOGS) { tab = AppTab.LOGS }
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
private fun DisplaySection(app: AppRef, onKilled: (String) -> Unit, onNotice: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val frameState by CloneEngine.frame.collectAsState()
    val running by CloneEngine.running.collectAsState()
    val dw by CloneEngine.displayW.collectAsState()
    val dh by CloneEngine.displayH.collectAsState()
    val compat by CloneEngine.cloneOnMainScreen.collectAsState()
    var textInput by remember { mutableStateOf("") }

    val isRunning = running == app.packageName

    GlassSettingCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
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
                            val err = withContext(Dispatchers.IO) {
                                if (app.isSystem) CloneEngine.killSystem(app.packageName) else null
                            }
                            if (!app.isSystem) CloneEngine.kill(app.packageName)
                            if (err != null) onNotice("Kill failed: $err") else onKilled(app.label)
                        }
                    },
                ) {
                    Text("Kill", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }

            if (!isRunning) {
                Button(
                    onClick = {
                        val err = if (app.isSystem) CloneEngine.launchSystem(app.packageName)
                        else run { CloneEngine.launch(app.packageName); null }
                        if (err != null) onNotice(err)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (app.isSystem) "Run on hidden display" else "Open clone")
                }
                if (app.isSystem && !ShellRunner.shizukuReady() &&
                    !com.ivarna.fluxlinux.core.utils.RootUtils.isRootAvailable()
                ) {
                    Text(
                        "Installed apps need Shizuku (or root) — enable it in Tools.",
                        fontSize = 11.sp,
                        color = fluxMutedText(),
                    )
                }
            } else if (compat && !app.isSystem) {
                Text(
                    "Running on the phone screen (compatibility mode is on in Tools).",
                    fontSize = 11.5.sp,
                    color = fluxMutedText(),
                )
            } else {
                Screen(ScreenView(dw, dh, frameState))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(onClick = { CloneEngine.key("BACK") }) { Text("Back", fontSize = 12.sp) }
                    FilledTonalButton(onClick = { CloneEngine.key("HOME") }) { Text("Home", fontSize = 12.sp) }
                    FilledTonalButton(onClick = { CloneEngine.key("ENTER") }) { Text("Enter", fontSize = 12.sp) }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                        placeholder = { Text("Type into the app", fontSize = 12.sp) },
                    )
                    IconButton(onClick = {
                        CloneEngine.text(textInput)
                        textInput = ""
                    }, enabled = textInput.isNotEmpty()) {
                        Icon(Icons.Default.Send, contentDescription = "Send text")
                    }
                }
            }
        }
    }
}

/** Pre-resolved render state so the screen block stays cheap to recompose. */
private data class ScreenView(
    val dw: Int,
    val dh: Int,
    val frame: CloneEngine.Frame?,
)

@Composable
private fun Screen(state: ScreenView) {
    if (state.dw <= 0 || state.dh <= 0) return
    val screenH = LocalConfiguration.current.screenHeightDp
    val boxH = (screenH * 0.34f).dp.coerceIn(210.dp, 320.dp)
    var inner by remember { mutableStateOf(IntSize.Zero) }

    Box(modifier = Modifier.fillMaxWidth().height(boxH), contentAlignment = Alignment.Center) {
        BoxWithConstraints {
            val aspect = state.dw.toFloat() / state.dh.toFloat().coerceAtLeast(1f)
            val w = minOf(maxWidth, maxHeight * aspect)
            val h = w / aspect
            Box(
                modifier = Modifier
                    .size(w, h)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black)
                    .onSizeChanged { inner = it }
                    .pointerInput(state.dw, state.dh, inner) {
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
                                    val sx = if (inner.width > 0) state.dw.toFloat() / inner.width else 1f
                                    val sy = if (inner.height > 0) state.dh.toFloat() / inner.height else 1f
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
                val f = state.frame
                if (f != null) {
                    Image(
                        bitmap = f.bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds,
                    )
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

@Composable
private fun ConfigureSection(app: AppRef, onNotice: (String) -> Unit) {
    val fps by CloneEngine.displayFps.collectAsState()
    val res by CloneEngine.displayResPreset.collectAsState()
    val dpi by CloneEngine.displayDpi.collectAsState()
    val backend by CloneEngine.touchBackend.collectAsState()
    val engineReady = remember { runCatching { BlackBoxCore.get().areServicesAvailable() }.getOrDefault(false) }

    GlassSettingCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
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

            Text("Resolution", fontSize = 12.sp, color = fluxMutedText())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CloneEngine.RES_PRESETS.forEachIndexed { i, preset ->
                    FilterPill("${preset.first}×${preset.second}", res == i) {
                        CloneEngine.displayResPreset.value = i
                    }
                }
            }

            Text("DPI (higher = smaller UI)", fontSize = 12.sp, color = fluxMutedText())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CloneEngine.DPI_PRESETS.forEach { value ->
                    FilterPill(value.toString(), dpi == value) {
                        CloneEngine.displayDpi.value = value
                    }
                }
            }

            Button(
                onClick = {
                    CloneEngine.applyDisplayConfig()
                    onNotice("Display restarted")
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Apply & restart display")
            }

            StatusRow("Engine", if (engineReady) "Ready" else "Starting…")
            StatusRow("Touch", backend.label)
            StatusRow("Type", if (app.isSystem) "Installed app" else "Clone (inside Bliss)")
        }
    }
}

@Composable
private fun LogsSection() {
    var tick by remember { mutableIntStateOf(0) }
    val logs by CloneEngine.logs.collectAsState()
    val engineTail = remember(tick, logs.size) { CloneEngine.engineLogTail(60) }

    GlassSettingCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Activity (${logs.size})",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { tick++ }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = fluxMutedText())
                }
            }
            Column(modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                logs.take(60).forEach { line ->
                    Text(
                        line,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = fluxMutedText(),
                    )
                }
            }

            Text(
                "Engine (BlackBox)",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 6.dp),
            )
            Column(modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                if (engineTail.isEmpty()) {
                    Text("(no engine log yet)", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = fluxMutedText())
                } else {
                    engineTail.asReversed().forEach { line ->
                        Text(
                            line,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = fluxMutedText(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 12.sp, color = fluxMutedText())
        Text(
            value,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
