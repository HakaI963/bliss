package com.ivarna.fluxlinux.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivarna.fluxlinux.core.clone.HeadlessCloneEngine
import com.ivarna.fluxlinux.ui.components.QCard
import com.ivarna.fluxlinux.ui.components.QEmptyState
import com.ivarna.fluxlinux.ui.components.QIconTile
import com.ivarna.fluxlinux.ui.components.QLogPane
import com.ivarna.fluxlinux.ui.components.QPrimaryButton
import com.ivarna.fluxlinux.ui.components.QRow
import com.ivarna.fluxlinux.ui.components.QScreenTitle
import com.ivarna.fluxlinux.ui.components.QSectionHeader
import com.ivarna.fluxlinux.ui.components.QTextField
import com.ivarna.fluxlinux.ui.components.Quartz
import com.ivarna.fluxlinux.ui.theme.fluxMutedText
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Apps tab: the Shizuku shell engine, a live virtual display, and the
 * repackaged clones that run on it. Nothing here creates an Android user or
 * profile; the clone is an ordinary user-0 install with its launcher hidden.
 */
@Composable
fun ClonedAppsScreen(onOpenTools: () -> Unit = {}) {
    val context = LocalContext.current
    val shizukuInstalled by HeadlessCloneEngine.shizukuInstalled.collectAsState()
    val shizukuRunning by HeadlessCloneEngine.shizukuRunning.collectAsState()
    val permission by HeadlessCloneEngine.hasPermission.collectAsState()
    val connected by HeadlessCloneEngine.connected.collectAsState()
    val version by HeadlessCloneEngine.shizukuVersion.collectAsState()
    val displayReady by HeadlessCloneEngine.displayReady.collectAsState()
    val displayId by HeadlessCloneEngine.displayId.collectAsState()
    val displayWidth by HeadlessCloneEngine.displayWidth.collectAsState()
    val displayHeight by HeadlessCloneEngine.displayHeight.collectAsState()
    val displayDpi by HeadlessCloneEngine.displayDpi.collectAsState()
    val displayState by HeadlessCloneEngine.displayState.collectAsState()
    val displayFrames by HeadlessCloneEngine.displayFrames.collectAsState()
    val inputReady by HeadlessCloneEngine.inputReady.collectAsState()
    val preview by HeadlessCloneEngine.preview.collectAsState()
    val previewError by HeadlessCloneEngine.previewError.collectAsState()
    val clones by HeadlessCloneEngine.clones.collectAsState()
    val busy by HeadlessCloneEngine.busy.collectAsState()
    val operation by HeadlessCloneEngine.operation.collectAsState()
    val notice by HeadlessCloneEngine.notice.collectAsState()
    val lastError by HeadlessCloneEngine.lastError.collectAsState()
    val logs by HeadlessCloneEngine.logs.collectAsState()

    var autoDisplayTried by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<String?>(null) }
    var textToSend by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        HeadlessCloneEngine.attach(context)
        HeadlessCloneEngine.bootstrap()
        HeadlessCloneEngine.refreshClones()
    }

    // Create the default display once the shell engine connects. The user can
    // still release it and create another later.
    LaunchedEffect(connected) {
        if (connected && !autoDisplayTried) {
            autoDisplayTried = true
            if (!displayReady) HeadlessCloneEngine.createDisplay()
        }
    }

    // Keep the preview alive while this tab is visible.
    LaunchedEffect(connected, displayReady) {
        if (!connected || !displayReady) return@LaunchedEffect
        while (isActive) {
            HeadlessCloneEngine.capturePreview()
            delay(500)
        }
    }

    DisposableEffect(Unit) {
        onDispose { HeadlessCloneEngine.clearPreview() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Quartz.ScreenPad, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(Quartz.Gap),
    ) {
        QScreenTitle(
            "Apps",
            "Clones run on a private virtual display through Shizuku. No extra user or profile.",
        )

        if (busy || operation != null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            operation?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }
        }
        notice?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }
        lastError?.let {
            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }

        QSectionHeader("Shell engine")
        QCard {
            Column(
                modifier = Modifier.padding(horizontal = Quartz.CardPad + 2.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                val status = when {
                    !shizukuInstalled -> "Shizuku is not installed"
                    !shizukuRunning -> "Shizuku is installed, but its service is not running"
                    !permission -> "Shizuku permission is not granted"
                    !connected -> "Shizuku is ready; shell engine is not connected"
                    else -> "Shell engine connected · Shizuku v$version"
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (connected) Color(0xFF5BD69A)
                                else if (shizukuRunning && permission) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error
                            )
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        status,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    "The display and clone commands run as the shell user through Shizuku. " +
                        "Install Shizuku, start its service, then grant Bliss permission.",
                    fontSize = 11.sp,
                    color = fluxMutedText(),
                )
                when {
                    !shizukuInstalled -> QPrimaryButton("Get Shizuku") {
                        HeadlessCloneEngine.openShizukuDownload()
                    }
                    !shizukuRunning -> QPrimaryButton("Open Shizuku") {
                        HeadlessCloneEngine.openShizukuManager()
                    }
                    !permission -> QPrimaryButton("Grant Shizuku permission") {
                        HeadlessCloneEngine.requestPermission()
                    }
                    !connected -> QPrimaryButton("Start shell engine") {
                        HeadlessCloneEngine.startService()
                    }
                    else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QPrimaryButton("Stop engine", Modifier.weight(1f)) {
                            HeadlessCloneEngine.stopService()
                        }
                        QPrimaryButton("Refresh", Modifier.weight(1f)) {
                            HeadlessCloneEngine.refreshState()
                            HeadlessCloneEngine.refreshClones()
                        }
                    }
                }
            }
        }

        QSectionHeader("Virtual display")
        QCard {
            Column(
                modifier = Modifier.padding(horizontal = Quartz.CardPad + 2.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DisplayPreview(
                    ready = displayReady,
                    connected = connected,
                    bitmap = preview,
                    width = displayWidth,
                    height = displayHeight,
                    error = previewError,
                )
                Text(
                    if (displayReady) {
                        "Display #$displayId · ${displayState.ifBlank { "?" }} · " +
                            "${displayWidth}×$displayHeight @${displayDpi}dpi · $displayFrames frames · " +
                            "input ${if (inputReady) "ready" else "unavailable"}"
                    } else {
                        "No virtual display yet. Create one to run clones headlessly."
                    },
                    fontSize = 11.sp,
                    color = fluxMutedText(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (displayReady) {
                        QPrimaryButton("Release display", Modifier.weight(1f), enabled = connected) {
                            HeadlessCloneEngine.releaseDisplay()
                        }
                    } else {
                        QPrimaryButton("Create display", Modifier.weight(1f), enabled = connected) {
                            HeadlessCloneEngine.createDisplay(displayWidth, displayHeight, displayDpi)
                        }
                    }
                    QPrimaryButton(
                        "Swipe up",
                        Modifier.weight(1f),
                        enabled = displayReady,
                    ) {
                        HeadlessCloneEngine.swipe(
                            displayWidth / 2,
                            (displayHeight * 3) / 4,
                            displayWidth / 2,
                            displayHeight / 4,
                            280,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QPrimaryButton("Back", Modifier.weight(1f), enabled = displayReady) {
                        HeadlessCloneEngine.key(4)
                    }
                    QPrimaryButton("Home", Modifier.weight(1f), enabled = displayReady) {
                        HeadlessCloneEngine.key(3)
                    }
                    QPrimaryButton("Recents", Modifier.weight(1f), enabled = displayReady) {
                        HeadlessCloneEngine.key(187)
                    }
                }
                QTextField(
                    value = textToSend,
                    onValueChange = { textToSend = it },
                    placeholder = "Type on the clone display",
                )
                QPrimaryButton(
                    "Send text",
                    enabled = displayReady && textToSend.isNotBlank(),
                ) {
                    HeadlessCloneEngine.text(textToSend)
                    textToSend = ""
                }
            }
        }

        QSectionHeader("Clones")
        QCard {
            if (clones.isEmpty()) {
                QEmptyState(
                    "No clones yet",
                    "Add an installed app or pick an APK in Tools → Clone apps.",
                )
                QPrimaryButton("Add a clone", Modifier.padding(horizontal = Quartz.CardPad + 2.dp)) {
                    onOpenTools()
                }
            } else {
                clones.forEachIndexed { index, clone ->
                    Column {
                        QRow(
                            title = clone.label,
                            subtitle = "${clone.originalPackage.ifBlank { clone.packageName }} · ${clone.status}",
                            leading = {
                                QIconTile(
                                    bitmap = clone.icon,
                                    fallback = Icons.Default.Android,
                                    size = 34.dp,
                                )
                            },
                            trailing = {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = fluxMutedText(),
                                    modifier = Modifier.size(17.dp),
                                )
                            },
                            onClick = { HeadlessCloneEngine.launchClone(clone.packageName) },
                            divider = false,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 46.dp, end = Quartz.CardPad + 2.dp, bottom = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            TextButton(
                                enabled = connected && !busy,
                                onClick = { HeadlessCloneEngine.launchClone(clone.packageName) },
                            ) { Text("Open", fontSize = 12.sp) }
                            TextButton(
                                enabled = connected,
                                onClick = { HeadlessCloneEngine.killClone(clone.packageName) },
                            ) { Text("Stop", fontSize = 12.sp) }
                            TextButton(
                                enabled = connected,
                                onClick = { removeTarget = clone.packageName },
                            ) {
                                Text(
                                    "Delete",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        if (index < clones.lastIndex) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 46.dp)
                                    .height(0.75.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            )
                        }
                    }
                }
                QPrimaryButton("Add another clone", Modifier.padding(horizontal = Quartz.CardPad + 2.dp)) {
                    onOpenTools()
                }
            }
        }

        removeTarget?.let { target ->
            QCard {
                Column(
                    modifier = Modifier.padding(horizontal = Quartz.CardPad + 2.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Uninstall this clone?",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "The clone package and its data are removed. The original app is untouched.",
                        fontSize = 11.sp,
                        color = fluxMutedText(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QPrimaryButton("Cancel", Modifier.weight(1f)) { removeTarget = null }
                        QPrimaryButton("Uninstall", Modifier.weight(1f)) {
                            removeTarget = null
                            HeadlessCloneEngine.deleteClone(target)
                        }
                    }
                }
            }
        }

        QSectionHeader("Engine log")
        QCard {
            QLogPane(lines = logs.takeLast(80), maxHeight = 190.dp)
        }
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun DisplayPreview(
    ready: Boolean,
    connected: Boolean,
    bitmap: android.graphics.Bitmap?,
    width: Int,
    height: Int,
    error: String?,
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val safeWidth = width.coerceAtLeast(1)
    val safeHeight = height.coerceAtLeast(1)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(safeWidth.toFloat() / safeHeight.toFloat())
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black)
            .onSizeChanged { boxSize = it }
            .pointerInput(ready, boxSize, safeWidth, safeHeight) {
                if (!ready || boxSize.width <= 0 || boxSize.height <= 0) return@pointerInput
                detectTapGestures { offset ->
                    val x = (offset.x / boxSize.width * safeWidth).toInt()
                        .coerceIn(0, safeWidth - 1)
                    val y = (offset.y / boxSize.height * safeHeight).toInt()
                        .coerceIn(0, safeHeight - 1)
                    HeadlessCloneEngine.tap(x, y)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null && ready) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Virtual display preview",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else if (!connected) {
            Text(
                "Connect the shell engine to start the display",
                fontSize = 11.5.sp,
                color = Color.White.copy(alpha = 0.65f),
                modifier = Modifier.padding(20.dp),
            )
        } else if (!ready) {
            Text(
                "No display",
                fontSize = 11.5.sp,
                color = Color.White.copy(alpha = 0.65f),
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = Color.White.copy(alpha = 0.7f),
                )
                Text(
                    error?.takeIf { it.isNotBlank() } ?: "Waiting for the first frame…",
                    fontSize = 10.5.sp,
                    color = Color.White.copy(alpha = 0.65f),
                )
            }
        }
    }
}
