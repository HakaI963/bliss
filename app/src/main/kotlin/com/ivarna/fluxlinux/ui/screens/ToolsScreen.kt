package com.ivarna.fluxlinux.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import top.niunaijun.blackbox.BlackBoxCore

private enum class ToolsPage { HOME, CLONE }

/**
 * Tools tab: hub for Bliss-only extras. First tool: the clone space, where APKs
 * are installed inside Bliss and run on a hidden virtual display.
 */
@Composable
fun ToolsScreen() {
    var page by remember { mutableStateOf(ToolsPage.HOME) }
    val entrance = rememberStaggerEntrance()
    when (page) {
        ToolsPage.HOME -> ToolsHome(entrance) { page = ToolsPage.CLONE }
        ToolsPage.CLONE -> ClonePage(entrance) { page = ToolsPage.HOME }
    }
}

@Composable
private fun ToolsHome(entrance: Boolean, onOpenClone: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.staggerIn(0, entrance)) {
            Text(
                "Tools",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Extras that run entirely inside Bliss.",
                fontSize = 13.sp,
                color = fluxMutedText(),
            )
        }

        GlassSettingCard(
            modifier = Modifier
                .staggerIn(1, entrance)
                .fillMaxWidth(),
            onClick = onOpenClone,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Clone App",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "Install apps into a space inside Bliss and run them in a small window on a hidden display. " +
                            "Nothing is installed into your phone and nothing leaves this window.",
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                        color = fluxMutedText(),
                    )
                }
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = fluxMutedText(),
                )
            }
        }

        GlassSettingCard(modifier = Modifier.staggerIn(2, entrance)) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    "More tools soon",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "Agent helpers, screenshots and UI dumps for the clone space land here next.",
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    color = fluxMutedText(),
                )
            }
        }

        Spacer(Modifier.height(110.dp))
    }
}

@Composable
private fun ClonePage(entrance: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val apps by CloneEngine.apps.collectAsState()
    val frameState by CloneEngine.frame.collectAsState()
    val running by CloneEngine.running.collectAsState()
    val status by CloneEngine.status.collectAsState()
    val busy by CloneEngine.busy.collectAsState()
    val backend by CloneEngine.touchBackend.collectAsState()
    val dw by CloneEngine.displayW.collectAsState()
    val dh by CloneEngine.displayH.collectAsState()

    var url by remember { mutableStateOf("") }
    var playerVisible by remember { mutableStateOf(false) }
    var textInput by remember { mutableStateOf("") }
    var refreshTick by remember { mutableIntStateOf(0) }
    val servicesReady = remember(refreshTick, running) {
        runCatching { BlackBoxCore.get().areServicesAvailable() }.getOrDefault(false)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { CloneEngine.installUri(it) }
    }

    LaunchedEffect(running) {
        if (running != null) playerVisible = true
    }

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
            Column {
                Text(
                    "Clone App",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "Apps that live inside Bliss",
                    fontSize = 12.5.sp,
                    color = fluxMutedText(),
                )
            }
        }

        GlassSettingCard(modifier = Modifier.staggerIn(1, entrance).fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Clone space",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                StatusRow("Engine", if (servicesReady) "Ready" else "Starting…")
                StatusRow("Display", if (running != null) "$dw × $dh · hidden" else "idle")
                StatusRow("Touch control", backend.label)
                if (backend == ShellRunner.Backend.NONE) {
                    Text(
                        "Touch injection needs Shizuku (shell) or root. Install the Shizuku app, start it " +
                            "via wireless debugging, then enable it here — once.",
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = fluxMutedText(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { requestTouch(context) { CloneEngine.touchBackend.value = ShellRunner.backend() } }) {
                            Text("Enable touch")
                        }
                        TextButton(onClick = {
                            CloneEngine.touchBackend.value = ShellRunner.backend()
                            refreshTick++
                        }) {
                            Text("Re-check")
                        }
                    }
                }
            }
        }

        GlassSettingCard(modifier = Modifier.staggerIn(2, entrance).fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
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

        if (running != null) {
            val runningApp = apps.firstOrNull { it.packageName == running }
            val label = runningApp?.label ?: running
            if (playerVisible) {
                PlayerCard(
                    entrance = entrance,
                    label = label.orEmpty(),
                    dw = dw,
                    dh = dh,
                    frameState = frameState,
                    textInput = textInput,
                    onTextInput = { textInput = it },
                    onSendText = {
                        CloneEngine.text(textInput)
                        textInput = ""
                    },
                    onClose = { playerVisible = false },
                    onKill = {
                        running?.let { CloneEngine.kill(it) }
                        playerVisible = false
                    },
                )
            } else {
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
                            )
                            Text(
                                "Window closed — the app keeps running.",
                                fontSize = 11.5.sp,
                                color = fluxMutedText(),
                            )
                        }
                        FilledTonalButton(onClick = { playerVisible = true }) {
                            Text("Show window")
                        }
                    }
                }
            }
        }

        GlassSettingCard(modifier = Modifier.staggerIn(3, entrance).fillMaxWidth()) {
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
                        "Clone apps",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    IconButton(onClick = {
                        CloneEngine.refresh()
                        refreshTick++
                    }) {
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
                        AppRow(
                            app = app,
                            onOpen = { CloneEngine.launch(app.packageName) },
                            onKill = { CloneEngine.kill(app.packageName) },
                            onUninstall = { CloneEngine.uninstall(app.packageName) },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(110.dp))
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

@Composable
private fun AppRow(
    app: CloneEngine.CloneApp,
    onOpen: () -> Unit,
    onKill: () -> Unit,
    onUninstall: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
        IconButton(onClick = onKill) {
            Icon(Icons.Default.Close, contentDescription = "Kill", tint = fluxMutedText())
        }
        IconButton(onClick = onUninstall) {
            Icon(Icons.Default.Delete, contentDescription = "Uninstall", tint = fluxMutedText())
        }
    }
}

@Composable
private fun PlayerCard(
    entrance: Boolean,
    label: String,
    dw: Int,
    dh: Int,
    frameState: CloneEngine.Frame?,
    textInput: String,
    onTextInput: (String) -> Unit,
    onSendText: () -> Unit,
    onClose: () -> Unit,
    onKill: () -> Unit,
) {
    GlassSettingCard(modifier = Modifier.staggerIn(3, entrance).fillMaxWidth()) {
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
                        "Window · $label",
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
                TextButton(onClick = onClose) { Text("Close") }
                TextButton(onClick = onKill) {
                    Text("Kill", color = MaterialTheme.colorScheme.error)
                }
            }

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
                IconButton(onClick = onSendText, enabled = textInput.isNotEmpty()) {
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

private fun requestTouch(context: Context, onDone: () -> Unit) {
    when {
        PhantomProcessFixer.shizukuRunning() -> PhantomProcessFixer.requestShizuku { onDone() }
        PhantomProcessFixer.rootAvailable() -> onDone()
        else -> Toast.makeText(
            context,
            "Start the Shizuku app first (it runs via wireless debugging), then try again.",
            Toast.LENGTH_LONG,
        ).show()
    }
}
