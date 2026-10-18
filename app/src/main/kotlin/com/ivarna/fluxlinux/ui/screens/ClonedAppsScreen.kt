package com.ivarna.fluxlinux.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivarna.fluxlinux.core.clone.CloneEngine
import com.ivarna.fluxlinux.core.clone.ShellRunner
import com.ivarna.fluxlinux.core.system.PhantomProcessFixer
import com.ivarna.fluxlinux.ui.components.GlassSettingCard
import com.ivarna.fluxlinux.ui.theme.fluxMutedText
import com.ivarna.fluxlinux.ui.theme.rememberStaggerEntrance
import com.ivarna.fluxlinux.ui.theme.staggerIn
import kotlin.math.roundToInt
import top.niunaijun.blackbox.BlackBoxCore

private enum class CloneTab(val label: String) {
    CONFIGURE("Configure"),
    DISPLAY("Display"),
    LOGS("Logs"),
}

/**
 * Apps dock tab: the clone space. Lists the APKs installed inside Bliss (they run
 * on a hidden virtual display) and opens a detail view with Configure / Display /
 * Logs tabs for the selected app.
 */
@Composable
fun ClonedAppsScreen() {
    val entrance = rememberStaggerEntrance()
    val apps by CloneEngine.apps.collectAsState()
    val running by CloneEngine.running.collectAsState()
    var selectedPkg by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(CloneTab.CONFIGURE) }

    val selected = apps.firstOrNull { it.packageName == selectedPkg }

    if (selected == null) {
        CloneAppList(
            entrance = entrance,
            apps = apps,
            running = running,
            onSelect = { pkg ->
                selectedPkg = pkg
                tab = CloneTab.CONFIGURE
            },
            onOpen = { pkg ->
                CloneEngine.launch(pkg)
                selectedPkg = pkg
                tab = CloneTab.DISPLAY
            },
            onShowRunning = { pkg ->
                selectedPkg = pkg
                tab = CloneTab.DISPLAY
            },
        )
    } else {
        CloneAppDetail(
            entrance = entrance,
            app = selected,
            tab = tab,
            onTab = { tab = it },
            onBack = { selectedPkg = null },
        )
    }
}

@Composable
private fun CloneAppList(
    entrance: Boolean,
    apps: List<CloneEngine.CloneApp>,
    running: String?,
    onSelect: (String) -> Unit,
    onOpen: (String) -> Unit,
    onShowRunning: (String) -> Unit,
) {
    val context = LocalContext.current
    val status by CloneEngine.status.collectAsState()
    val busy by CloneEngine.busy.collectAsState()
    val backend by CloneEngine.touchBackend.collectAsState()

    var url by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { CloneEngine.installUri(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.staggerIn(0, entrance)) {
            Text(
                "Clone apps",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Apps that live inside Bliss — hidden display, nothing on your real screen.",
                fontSize = 13.sp,
                color = fluxMutedText(),
            )
        }

        GlassSettingCard(modifier = Modifier.staggerIn(1, entrance).fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Install into the clone space",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "Stored inside Bliss only — your normal launcher never sees it.",
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    color = fluxMutedText(),
                )
                Button(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                ) {
                    Text("Choose APK file")
                }
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Or APK link (https://…)") },
                )
                Button(
                    onClick = {
                        if (url.isNotBlank()) {
                            CloneEngine.installFromUrl(url)
                            url = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy && url.isNotBlank(),
                ) {
                    Text("Download & install")
                }
                if (busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                status?.let {
                    Text(
                        it,
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        if (backend == ShellRunner.Backend.NONE) {
            GlassSettingCard(modifier = Modifier.staggerIn(2, entrance).fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Touch needs Shizuku (shell) or root — one-time setup.",
                        fontSize = 12.5.sp,
                        lineHeight = 17.sp,
                        color = fluxMutedText(),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            when {
                                PhantomProcessFixer.shizukuRunning() ->
                                    PhantomProcessFixer.requestShizuku {
                                        CloneEngine.touchBackend.value = ShellRunner.backend()
                                    }
                                PhantomProcessFixer.rootAvailable() ->
                                    CloneEngine.touchBackend.value = ShellRunner.backend()
                                else -> Toast.makeText(
                                    context,
                                    "Start the Shizuku app first (it runs via wireless debugging), then try again.",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        },
                    ) {
                        Text("Enable touch", fontSize = 12.sp)
                    }
                }
            }
        }

        val runningPkg = running
        if (runningPkg != null) {
            val label = apps.firstOrNull { it.packageName == runningPkg }?.label ?: runningPkg
            GlassSettingCard(modifier = Modifier.staggerIn(3, entrance).fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Android,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Running · $label",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "On the hidden display.",
                            fontSize = 11.5.sp,
                            color = fluxMutedText(),
                        )
                    }
                    FilledTonalButton(onClick = { onShowRunning(runningPkg) }) {
                        Text("Show", fontSize = 12.sp)
                    }
                }
            }
        }

        GlassSettingCard(modifier = Modifier.staggerIn(4, entrance).fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "Installed apps",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    IconButton(onClick = { CloneEngine.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = fluxMutedText())
                    }
                }
                if (apps.isEmpty()) {
                    Text(
                        "Nothing yet — install an APK above.",
                        fontSize = 12.5.sp,
                        color = fluxMutedText(),
                    )
                } else {
                    apps.forEach { app ->
                        CloneAppRow(
                            app = app,
                            onSelect = { onSelect(app.packageName) },
                            onOpen = { onOpen(app.packageName) },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(110.dp))
    }
}

@Composable
private fun CloneAppDetail(
    entrance: Boolean,
    app: CloneEngine.CloneApp,
    tab: CloneTab,
    onTab: (CloneTab) -> Unit,
    onBack: () -> Unit,
) {
    var windowVisible by remember(app.packageName) { mutableStateOf(true) }
    var textInput by remember(app.packageName) { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.staggerIn(0, entrance),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Spacer(Modifier.width(4.dp))
            CloneAppIcon(app)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    app.label,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (app.version.isBlank()) app.packageName else "${app.packageName} · ${app.version}",
                    fontSize = 11.5.sp,
                    color = fluxMutedText(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .staggerIn(1, entrance),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CloneTab.values().forEach { option ->
                SelectPill(
                    label = option.label,
                    selected = option == tab,
                    modifier = Modifier.weight(1f),
                    onClick = { onTab(option) },
                )
            }
        }

        when (tab) {
            CloneTab.CONFIGURE -> ConfigureTab(entrance)
            CloneTab.DISPLAY -> DisplayTab(
                entrance = entrance,
                app = app,
                windowVisible = windowVisible,
                textInput = textInput,
                onWindowVisible = { windowVisible = it },
                onTextInput = { textInput = it },
            )
            CloneTab.LOGS -> LogsTab(entrance)
        }

        Spacer(Modifier.height(110.dp))
    }
}

@Composable
private fun ConfigureTab(entrance: Boolean) {
    val fps by CloneEngine.displayFps.collectAsState()
    val preset by CloneEngine.displayResPreset.collectAsState()
    val dpi by CloneEngine.displayDpi.collectAsState()
    val dw by CloneEngine.displayW.collectAsState()
    val dh by CloneEngine.displayH.collectAsState()
    val backend by CloneEngine.touchBackend.collectAsState()
    val running by CloneEngine.running.collectAsState()
    val servicesReady = remember(running) {
        runCatching { BlackBoxCore.get().areServicesAvailable() }.getOrDefault(false)
    }

    GlassSettingCard(modifier = Modifier.staggerIn(2, entrance).fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Frame rate", fontSize = 13.sp, color = fluxMutedText())
                Text(
                    "FPS: $fps",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Slider(
                value = fps.toFloat(),
                onValueChange = { CloneEngine.displayFps.value = it.roundToInt().coerceIn(5, 60) },
                valueRange = 5f..60f,
                steps = 54,
            )

            Text("Resolution", fontSize = 13.sp, color = fluxMutedText())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CloneEngine.RES_PRESETS.forEachIndexed { index, (w, h) ->
                    SelectPill(
                        label = "$w×$h",
                        selected = index == preset,
                        onClick = { CloneEngine.displayResPreset.value = index },
                    )
                }
            }

            Text("DPI", fontSize = 13.sp, color = fluxMutedText())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(240, 280, 320, 360).forEach { option ->
                    SelectPill(
                        label = "$option",
                        selected = option == dpi,
                        onClick = { CloneEngine.displayDpi.value = option },
                    )
                }
            }

            Button(
                onClick = { CloneEngine.applyDisplayConfig() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Apply & restart display")
            }
            Text(
                "Restarts the hidden display; the running app is relaunched.",
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                color = fluxMutedText(),
            )

            StatusRow("Engine", if (servicesReady) "Ready" else "Starting…")
            StatusRow("Display", "$dw×$dh · hidden")
            StatusRow("Touch control", backend.label)
        }
    }
}

@Composable
private fun DisplayTab(
    entrance: Boolean,
    app: CloneEngine.CloneApp,
    windowVisible: Boolean,
    textInput: String,
    onWindowVisible: (Boolean) -> Unit,
    onTextInput: (String) -> Unit,
) {
    val frameState by CloneEngine.frame.collectAsState()
    val dw by CloneEngine.displayW.collectAsState()
    val dh by CloneEngine.displayH.collectAsState()

    GlassSettingCard(modifier = Modifier.staggerIn(2, entrance).fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Window · ${app.label}",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "$dw × $dh · hidden display",
                        fontSize = 11.sp,
                        color = fluxMutedText(),
                    )
                }
                TextButton(onClick = { onWindowVisible(false) }) { Text("Close") }
                TextButton(onClick = { CloneEngine.kill(app.packageName) }) {
                    Text("Kill", color = MaterialTheme.colorScheme.error)
                }
            }

            if (windowVisible) {
                var boxSize by remember { mutableStateOf(IntSize.Zero) }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(dw.toFloat() / dh.toFloat())
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black)
                        .onSizeChanged { boxSize = it }
                        .pointerInput(dw, dh, boxSize) {
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
                                        val sx = if (boxSize.width > 0) dw.toFloat() / boxSize.width else 1f
                                        val sy = if (boxSize.height > 0) dh.toFloat() / boxSize.height else 1f
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
                    val f = frameState
                    if (f != null) {
                        Image(
                            bitmap = f.bitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.FillBounds,
                        )
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                }
            } else {
                Text(
                    "Window closed — the app keeps running.",
                    fontSize = 12.5.sp,
                    color = fluxMutedText(),
                )
                FilledTonalButton(onClick = { onWindowVisible(true) }) {
                    Text("Show window", fontSize = 12.sp)
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(onClick = { CloneEngine.key("BACK") }) { Text("Back", fontSize = 12.sp) }
                FilledTonalButton(onClick = { CloneEngine.key("HOME") }) { Text("Home", fontSize = 12.sp) }
                FilledTonalButton(onClick = { CloneEngine.key("ENTER") }) { Text("Enter", fontSize = 12.sp) }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = textInput,
                    onValueChange = onTextInput,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("Type into the app") },
                )
                IconButton(
                    onClick = {
                        CloneEngine.text(textInput)
                        onTextInput("")
                    },
                    enabled = textInput.isNotEmpty(),
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send text")
                }
            }

            Text(
                "Runs on a hidden display — nothing shows outside this window. Close keeps it running; Kill stops it.",
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                color = fluxMutedText(),
            )
        }
    }
}

@Composable
private fun LogsTab(entrance: Boolean) {
    val logs by CloneEngine.logs.collectAsState()

    GlassSettingCard(modifier = Modifier.staggerIn(2, entrance).fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${logs.size} entries",
                    fontSize = 12.sp,
                    color = fluxMutedText(),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { CloneEngine.refresh() }) { Text("Refresh") }
            }
            if (logs.isEmpty()) {
                Text(
                    "No log entries yet.",
                    fontSize = 12.sp,
                    color = fluxMutedText(),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    reverseLayout = false,
                ) {
                    items(logs) { line ->
                        Text(
                            line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = fluxMutedText(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CloneAppIcon(app: CloneEngine.CloneApp) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(12.dp))
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
            )
        }
    }
}

@Composable
private fun CloneAppRow(
    app: CloneEngine.CloneApp,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CloneAppIcon(app)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                app.label,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (app.version.isBlank()) app.packageName else "${app.packageName} · ${app.version}",
                fontSize = 11.sp,
                color = fluxMutedText(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        FilledTonalButton(onClick = onOpen) {
            Text("Open", fontSize = 12.sp)
        }
    }
}

@Composable
private fun SelectPill(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 12.5.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            color = if (selected) MaterialTheme.colorScheme.primary else fluxMutedText(),
            maxLines = 1,
        )
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp, color = fluxMutedText())
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    }
}
