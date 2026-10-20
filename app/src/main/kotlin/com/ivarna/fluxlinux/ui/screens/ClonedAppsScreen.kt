package com.ivarna.fluxlinux.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ivarna.fluxlinux.core.clone.HeadlessCloneEngine
import com.ivarna.fluxlinux.core.clone.HeadlessCloneEngine.CloneApp
import com.ivarna.fluxlinux.core.clone.HeadlessCloneEngine.Quality
import com.ivarna.fluxlinux.ui.components.QCard
import com.ivarna.fluxlinux.ui.components.QEmptyState
import com.ivarna.fluxlinux.ui.components.QIconTile
import com.ivarna.fluxlinux.ui.components.QPill
import com.ivarna.fluxlinux.ui.components.QPrimaryButton
import com.ivarna.fluxlinux.ui.components.QRow
import com.ivarna.fluxlinux.ui.components.QScreenTitle
import com.ivarna.fluxlinux.ui.components.QSectionHeader
import com.ivarna.fluxlinux.ui.components.QTextField
import com.ivarna.fluxlinux.ui.components.QToggleRow
import com.ivarna.fluxlinux.ui.components.Quartz
import com.ivarna.fluxlinux.ui.theme.fluxMutedText
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.max
import kotlin.math.roundToInt

private enum class AppsSection { PREFERENCE, INSTALLED }

private data class CloneGroup(
    val key: String,
    val label: String,
    val clones: List<CloneApp>,
)

private data class ViewerSession(
    val clone: CloneApp,
    val width: Int,
    val height: Int,
    val fps: Int,
    val touch: Boolean,
)

/**
 * Apps tab: a compact Shizuku preference page and folders for hidden clones.
 * The live viewer is intentionally chrome-free; system back closes it.
 */
@Composable
fun ClonedAppsScreen() {
    val context = LocalContext.current
    val shizukuInstalled by HeadlessCloneEngine.shizukuInstalled.collectAsState()
    val shizukuRunning by HeadlessCloneEngine.shizukuRunning.collectAsState()
    val permission by HeadlessCloneEngine.hasPermission.collectAsState()
    val connected by HeadlessCloneEngine.connected.collectAsState()
    val version by HeadlessCloneEngine.shizukuVersion.collectAsState()
    val clones by HeadlessCloneEngine.clones.collectAsState()
    val deviceApps by HeadlessCloneEngine.deviceApps.collectAsState()
    val busy by HeadlessCloneEngine.busy.collectAsState()
    val operation by HeadlessCloneEngine.operation.collectAsState()
    val notice by HeadlessCloneEngine.notice.collectAsState()
    val lastError by HeadlessCloneEngine.lastError.collectAsState()
    val displayFps by HeadlessCloneEngine.displayFps.collectAsState()
    val quality by HeadlessCloneEngine.quality.collectAsState()
    val touchEnabled by HeadlessCloneEngine.touchEnabled.collectAsState()
    val maxFps = remember(context) { maxRefreshRate(context) }
    val fpsValue = displayFps.coerceIn(10, maxFps)

    var section by rememberSaveable { mutableStateOf(AppsSection.INSTALLED) }
    var groupKey by rememberSaveable { mutableStateOf<String?>(null) }
    var viewer by remember { mutableStateOf<ViewerSession?>(null) }
    var logsClone by remember { mutableStateOf<CloneApp?>(null) }
    var customiseClone by remember { mutableStateOf<CloneApp?>(null) }
    var showInstalledPicker by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { HeadlessCloneEngine.cloneFromApk(it) }
    }

    LaunchedEffect(Unit) {
        HeadlessCloneEngine.attach(context)
        HeadlessCloneEngine.bootstrap()
        HeadlessCloneEngine.refreshClones()
    }
    LaunchedEffect(connected) {
        if (connected) {
            HeadlessCloneEngine.refreshClones()
            HeadlessCloneEngine.refreshDeviceApps()
        }
    }

    val groups = remember(clones) {
        clones.groupBy { it.originalPackage.ifBlank { it.packageName } }
            .map { (key, items) ->
                CloneGroup(
                    key = key,
                    label = items.firstOrNull()?.originalLabel
                        ?.ifBlank { items.firstOrNull()?.label.orEmpty() }
                        ?: key,
                    clones = items.sortedBy { it.label.lowercase() },
                )
            }
            .sortedBy { it.label.lowercase() }
    }
    val selectedGroup = groups.firstOrNull { it.key == groupKey }

    LaunchedEffect(groups, groupKey) {
        if (groupKey != null && selectedGroup == null) groupKey = null
    }

    BackHandler(enabled = groupKey != null) { groupKey = null }

    fun openViewer(clone: CloneApp) {
        if (!connected) {
            HeadlessCloneEngine.startService()
            return
        }
        val perApp = HeadlessCloneEngine.perAppDisplay(clone.packageName)
        val landscape = if (perApp.customSize) {
            perApp.width > perApp.height
        } else {
            cloneUsesLandscape(context, clone)
        }
        val defaultSize = defaultDisplaySize(context, landscape, quality)
        val width = if (perApp.customSize) perApp.width else defaultSize.first
        val height = if (perApp.customSize) perApp.height else defaultSize.second
        val fps = (perApp.fps.takeIf { it > 0 } ?: fpsValue)
            .coerceIn(10, maxFps.coerceAtLeast(10))
        HeadlessCloneEngine.clearPreview()
        HeadlessCloneEngine.launchClone(clone.packageName, width, height, fps)
        viewer = ViewerSession(
            clone = clone,
            width = width,
            height = height,
            fps = fps,
            touch = touchEnabled,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Quartz.ScreenPad)
            .padding(top = 8.dp),
    ) {
        QScreenTitle(
            "Apps",
            if (connected) "Shizuku engine connected · v$version"
            else "Clone and run apps on a private virtual display.",
        )
        Spacer(Modifier.height(8.dp))
        SectionSwitch(
            selected = section,
            onSelect = {
                section = it
                if (it == AppsSection.INSTALLED) {
                    HeadlessCloneEngine.refreshClones()
                } else {
                    groupKey = null
                }
            },
        )
        if (busy) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        }
        operation?.let {
            Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
        }
        val message = lastError ?: notice
        if (!message.isNullOrBlank()) {
            Text(
                message,
                fontSize = 11.sp,
                color = if (lastError != null) MaterialTheme.colorScheme.error else fluxMutedText(),
                maxLines = 2,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
        Spacer(Modifier.height(8.dp))

        when {
            section == AppsSection.PREFERENCE -> PreferenceSection(
                modifier = Modifier.weight(1f),
                shizukuInstalled = shizukuInstalled,
                shizukuRunning = shizukuRunning,
                permission = permission,
                connected = connected,
                version = version,
                busy = busy,
                maxFps = maxFps,
                fps = fpsValue,
                quality = quality,
                touchEnabled = touchEnabled,
                onGetShizuku = { HeadlessCloneEngine.openShizukuDownload() },
                onOpenShizuku = { HeadlessCloneEngine.openShizukuManager() },
                onGrant = { HeadlessCloneEngine.requestPermission() },
                onStart = { HeadlessCloneEngine.startService() },
                onRefresh = {
                    HeadlessCloneEngine.refreshState()
                    HeadlessCloneEngine.refreshClones()
                },
                onApk = {
                    picker.launch(
                        arrayOf(
                            "application/vnd.android.package-archive",
                            "application/octet-stream",
                            "*/*",
                        )
                    )
                },
                onInstalled = {
                    HeadlessCloneEngine.refreshDeviceApps()
                    showInstalledPicker = true
                },
                onFps = { HeadlessCloneEngine.setDisplayFps(it) },
                onQuality = { HeadlessCloneEngine.setQuality(it) },
                onTouch = { HeadlessCloneEngine.setTouchEnabled(it) },
            )

            selectedGroup == null -> InstalledFolders(
                modifier = Modifier.weight(1f),
                groups = groups,
                connected = connected,
                onOpenGroup = { groupKey = it.key },
                onOpenPreference = { section = AppsSection.PREFERENCE },
            )

            else -> CloneFolder(
                modifier = Modifier.weight(1f),
                group = selectedGroup,
                connected = connected,
                onBack = { groupKey = null },
                onView = ::openViewer,
                onLogs = {
                    HeadlessCloneEngine.clearCloneLogs()
                    logsClone = it
                },
                onCustomise = { customiseClone = it },
            )
        }
    }

    viewer?.let { session ->
        LiveViewer(
            session = session,
            onClose = { viewer = null },
        )
    }

    logsClone?.let { clone ->
        CloneLogsDialog(
            clone = clone,
            connected = connected,
            onClose = { logsClone = null },
        )
    }

    customiseClone?.let { clone ->
        CustomiseDialog(
            clone = clone,
            maxFps = maxFps,
            defaultFps = fpsValue,
            onSave = { width, height, fps ->
                HeadlessCloneEngine.savePerAppDisplay(clone.packageName, width, height, fps)
                customiseClone = null
            },
            onUninstall = {
                HeadlessCloneEngine.deleteClone(clone.packageName)
                customiseClone = null
            },
            onClose = { customiseClone = null },
        )
    }

    if (showInstalledPicker) {
        InstalledAppPicker(
            apps = deviceApps,
            onPick = { app ->
                showInstalledPicker = false
                HeadlessCloneEngine.cloneFromInstalled(app.packageName, app.label)
            },
            onClose = { showInstalledPicker = false },
        )
    }
}

// ---------------------------------------------------------------- sections

@Composable
private fun SectionSwitch(selected: AppsSection, onSelect: (AppsSection) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        SectionSwitchItem(
            "Preference",
            selected == AppsSection.PREFERENCE,
            Modifier.weight(1f),
        ) { onSelect(AppsSection.PREFERENCE) }
        SectionSwitchItem(
            "Installed",
            selected == AppsSection.INSTALLED,
            Modifier.weight(1f),
        ) { onSelect(AppsSection.INSTALLED) }
    }
}

@Composable
private fun SectionSwitchItem(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 12.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.onSurface else fluxMutedText(),
        )
    }
}

@Composable
private fun PreferenceSection(
    modifier: Modifier,
    shizukuInstalled: Boolean,
    shizukuRunning: Boolean,
    permission: Boolean,
    connected: Boolean,
    version: Int,
    busy: Boolean,
    maxFps: Int,
    fps: Int,
    quality: Quality,
    touchEnabled: Boolean,
    onGetShizuku: () -> Unit,
    onOpenShizuku: () -> Unit,
    onGrant: () -> Unit,
    onStart: () -> Unit,
    onRefresh: () -> Unit,
    onApk: () -> Unit,
    onInstalled: () -> Unit,
    onFps: (Int) -> Unit,
    onQuality: (Quality) -> Unit,
    onTouch: (Boolean) -> Unit,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        QSectionHeader("Shizuku")
        QCard {
            Column(
                modifier = Modifier.padding(horizontal = Quartz.CardPad + 2.dp, vertical = 7.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(
                                when {
                                    connected -> Color(0xFF5BD69A)
                                    shizukuRunning && permission -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.error
                                }
                            )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            !shizukuInstalled -> "Shizuku is not installed"
                            !shizukuRunning -> "Shizuku service is stopped"
                            !permission -> "Shizuku permission is not granted"
                            !connected -> "Shell engine is not connected"
                            else -> "Shell engine connected · v$version"
                        },
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRefresh) {
                        Text("Refresh", fontSize = 11.sp)
                    }
                }
                Text(
                    "The clone display and package commands run as the shell user through Shizuku.",
                    fontSize = 11.sp,
                    color = fluxMutedText(),
                )
                when {
                    !shizukuInstalled -> QPrimaryButton("Get Shizuku") { onGetShizuku() }
                    !shizukuRunning -> QPrimaryButton("Open Shizuku") { onOpenShizuku() }
                    !permission -> QPrimaryButton("Grant Shizuku permission") { onGrant() }
                    !connected -> QPrimaryButton("Start shell engine") { onStart() }
                }
            }
        }

        QSectionHeader("Clone")
        QCard {
            QRow(
                title = "From APK file",
                subtitle = "Choose a single .apk stored on this phone.",
                leading = { QIconTile(bitmap = null, fallback = Icons.Default.Android, size = 32.dp) },
                onClick = { if (connected && !busy) onApk() },
                divider = true,
            )
            QRow(
                title = "From installed app",
                subtitle = "Copy an app already installed on this phone.",
                leading = { QIconTile(bitmap = null, fallback = Icons.Default.Android, size = 32.dp) },
                onClick = { if (connected && !busy) onInstalled() },
            )
            if (!connected) {
                Text(
                    "Start the shell engine before creating a clone.",
                    fontSize = 10.5.sp,
                    color = fluxMutedText(),
                    modifier = Modifier.padding(horizontal = Quartz.CardPad + 2.dp, vertical = 4.dp),
                )
            }
        }

        QSectionHeader("Display")
        QCard {
            Column(
                modifier = Modifier.padding(horizontal = Quartz.CardPad + 2.dp, vertical = 7.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Frame rate",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    Text("$fps fps", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    Text("  max $maxFps", fontSize = 10.5.sp, color = fluxMutedText())
                }
                Slider(
                    value = fps.toFloat(),
                    onValueChange = { onFps(it.roundToInt()) },
                    valueRange = 10f..maxFps.toFloat(),
                    modifier = Modifier.fillMaxWidth().height(32.dp),
                )
                Text(
                    "10 fps uses less CPU. The top of the range follows this display.",
                    fontSize = 10.5.sp,
                    color = fluxMutedText(),
                )
                Spacer(Modifier.height(3.dp))
                Text("Quality", fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Quality.entries.forEach { entry ->
                        QPill(entry.label, quality == entry) { onQuality(entry) }
                    }
                }
                Text(
                    "Quality scales the virtual resolution and stream sharpness.",
                    fontSize = 10.5.sp,
                    color = fluxMutedText(),
                )
                Spacer(Modifier.height(2.dp))
            }
            QToggleRow(
                title = "Touch input",
                subtitle = "Allow taps and swipes on the clone display.",
                checked = touchEnabled,
                onCheckedChange = onTouch,
            )
        }
    }
}

@Composable
private fun InstalledFolders(
    modifier: Modifier,
    groups: List<CloneGroup>,
    connected: Boolean,
    onOpenGroup: (CloneGroup) -> Unit,
    onOpenPreference: () -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 104.dp),
    ) {
        if (groups.isEmpty()) {
            item {
                QCard {
                    QEmptyState(
                        "No clones yet",
                        if (connected) "Open Preference to clone an APK or installed app."
                        else "Connect Shizuku in Preference to get started.",
                    )
                    QPrimaryButton(
                        "Open Preference",
                        Modifier.padding(horizontal = Quartz.CardPad + 2.dp),
                    ) { onOpenPreference() }
                }
            }
        } else {
            items(groups, key = { it.key }) { group ->
                QCard {
                    QRow(
                        title = group.label,
                        subtitle = folderSubtitle(group),
                        leading = {
                            QIconTile(
                                bitmap = group.clones.firstOrNull()?.icon,
                                fallback = Icons.Default.Android,
                                size = 36.dp,
                            )
                        },
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = fluxMutedText(),
                                    modifier = Modifier.size(17.dp),
                                )
                                    Spacer(Modifier.width(5.dp))
                                Text(
                                    group.clones.size.toString(),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = fluxMutedText(),
                                )
                            }
                        },
                        onClick = { onOpenGroup(group) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CloneFolder(
    modifier: Modifier,
    group: CloneGroup,
    connected: Boolean,
    onBack: () -> Unit,
    onView: (CloneApp) -> Unit,
    onLogs: (CloneApp) -> Unit,
    onCustomise: (CloneApp) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 104.dp),
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            ) {
                Text(
                    "Installed",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onBack)
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                )
                Text("  /  ", fontSize = 11.5.sp, color = fluxMutedText())
                Text(
                    group.label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
        items(group.clones, key = { it.packageName }) { clone ->
            QCard {
                QRow(
                    title = clone.label,
                    subtitle = "${clone.status} · ${clone.packageName.takeLast(8)}",
                    leading = {
                        QIconTile(
                            bitmap = clone.icon,
                            fallback = Icons.Default.Android,
                            size = 34.dp,
                        )
                    },
                    trailing = {
                        if (clone.status == "running") {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Color(0xFF5BD69A))
                            )
                        }
                    },
                )
                Row(
                    modifier = Modifier.padding(start = 46.dp, end = 6.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    TextButton(
                        enabled = connected,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 7.dp),
                        onClick = { onView(clone) },
                    ) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(15.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("View", fontSize = 12.sp)
                    }
                    TextButton(
                        enabled = connected,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 7.dp),
                        onClick = { onLogs(clone) },
                    ) {
                        Icon(Icons.Default.Description, null, Modifier.size(15.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("Logs", fontSize = 12.sp)
                    }
                    TextButton(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 7.dp),
                        onClick = { onCustomise(clone) },
                    ) {
                        Icon(Icons.Default.Tune, null, Modifier.size(15.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("Customise", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

private fun folderSubtitle(group: CloneGroup): String {
    val running = group.clones.count { it.status == "running" }
    return buildString {
        append(group.clones.size).append(if (group.clones.size == 1) " clone" else " clones")
        if (running > 0) append(" · ").append(running).append(" running")
        append(" · ").append(group.key)
    }
}

// ---------------------------------------------------------------- viewer

@Composable
private fun LiveViewer(session: ViewerSession, onClose: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val connected by HeadlessCloneEngine.connected.collectAsState()
    val displayReady by HeadlessCloneEngine.displayReady.collectAsState()
    val preview by HeadlessCloneEngine.preview.collectAsState()
    val previewError by HeadlessCloneEngine.previewError.collectAsState()
    val lastError by HeadlessCloneEngine.lastError.collectAsState()
    val view = LocalView.current

    DisposableEffect(activity, session.width, session.height) {
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = when {
            session.width > session.height -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            session.height > session.width -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        val dialogWindow = (view.parent as? DialogWindowProvider)?.window
        if (dialogWindow != null) {
            WindowCompat.setDecorFitsSystemWindows(dialogWindow, false)
            dialogWindow.statusBarColor = android.graphics.Color.TRANSPARENT
            dialogWindow.navigationBarColor = android.graphics.Color.BLACK
            WindowInsetsControllerCompat(dialogWindow, view).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        onDispose {
            if (dialogWindow != null) {
                WindowInsetsControllerCompat(dialogWindow, view).show(WindowInsetsCompat.Type.systemBars())
            }
            if (previous != null) activity?.requestedOrientation = previous
            HeadlessCloneEngine.clearPreview()
        }
    }

    LaunchedEffect(connected, displayReady, session.fps) {
        if (!connected) return@LaunchedEffect
        val frameDelay = (1000 / session.fps.coerceAtLeast(10)).coerceAtLeast(16).toLong()
        while (isActive) {
            if (displayReady) HeadlessCloneEngine.capturePreview()
            delay(frameDelay)
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val bitmap = preview
                if (bitmap != null) {
                    ViewerFrame(
                        bitmap = bitmap,
                        touchEnabled = session.touch,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(
                            Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = Color.White.copy(alpha = 0.8f),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            if (!connected) "Starting the shell engine…"
                            else previewError ?: lastError ?: "Starting ${session.clone.label}…",
                            fontSize = 11.5.sp,
                            color = Color.White.copy(alpha = 0.72f),
                            modifier = Modifier.padding(horizontal = 32.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerFrame(
    bitmap: Bitmap,
    touchEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val imageWidth = bitmap.width.coerceAtLeast(1)
    val imageHeight = bitmap.height.coerceAtLeast(1)

    fun toDisplay(position: Offset): Offset {
        if (boxSize.width <= 0 || boxSize.height <= 0) return Offset.Zero
        val scale = max(
            boxSize.width.toFloat() / imageWidth,
            boxSize.height.toFloat() / imageHeight,
        )
        val dx = (boxSize.width - imageWidth * scale) / 2f
        val dy = (boxSize.height - imageHeight * scale) / 2f
        return Offset(
            ((position.x - dx) / scale).coerceIn(0f, imageWidth - 1f),
            ((position.y - dy) / scale).coerceIn(0f, imageHeight - 1f),
        )
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            .onSizeChanged { boxSize = it }
            .pointerInput(touchEnabled, imageWidth, imageHeight, boxSize) {
                if (!touchEnabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var last = down.position
                    var total = Offset.Zero
                    var pressed = true
                    while (pressed) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        total += change.position - last
                        last = change.position
                        pressed = change.pressed
                        change.consume()
                    }
                    if (total.getDistance() < viewConfiguration.touchSlop) {
                        val point = toDisplay(down.position)
                        HeadlessCloneEngine.tap(point.x.toInt(), point.y.toInt())
                    } else {
                        val start = toDisplay(down.position)
                        val end = toDisplay(last)
                        HeadlessCloneEngine.swipe(
                            start.x.toInt(),
                            start.y.toInt(),
                            end.x.toInt(),
                            end.y.toInt(),
                            260,
                        )
                    }
                }
            },
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Live clone display",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}

// ---------------------------------------------------------------- logs

@Composable
private fun CloneLogsDialog(clone: CloneApp, connected: Boolean, onClose: () -> Unit) {
    val lines by HeadlessCloneEngine.cloneLogs.collectAsState()
    LaunchedEffect(clone.packageName, connected) {
        if (!connected) {
            HeadlessCloneEngine.clearCloneLogs()
            return@LaunchedEffect
        }
        while (isActive) {
            HeadlessCloneEngine.fetchCloneLogs(clone.packageName, 300)
            delay(1000)
        }
    }
    DisposableEffect(Unit) { onDispose { HeadlessCloneEngine.clearCloneLogs() } }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = Quartz.ScreenPad, vertical = 12.dp),
            ) {
                Text("Live logs", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    clone.label + " · " + clone.packageName,
                    fontSize = 11.sp,
                    color = fluxMutedText(),
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black)
                        .padding(10.dp),
                ) {
                    if (!connected) {
                        Text(
                            "Connect the shell engine to read live app logs.",
                            fontSize = 11.5.sp,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(lines.asReversed()) { line ->
                                Text(
                                    line,
                                    fontSize = 9.5.sp,
                                    lineHeight = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color.White.copy(alpha = 0.82f),
                                    modifier = Modifier.padding(bottom = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- customise

@Composable
private fun CustomiseDialog(
    clone: CloneApp,
    maxFps: Int,
    defaultFps: Int,
    onSave: (width: Int, height: Int, fps: Int) -> Unit,
    onUninstall: () -> Unit,
    onClose: () -> Unit,
) {
    val saved = remember(clone.packageName) {
        HeadlessCloneEngine.perAppDisplay(clone.packageName)
    }
    var widthText by remember { mutableStateOf(if (saved.customSize) saved.width.toString() else "") }
    var heightText by remember { mutableStateOf(if (saved.customSize) saved.height.toString() else "") }
    var useDefaultFps by remember { mutableStateOf(saved.fps <= 0) }
    var fps by remember {
        mutableStateOf((saved.fps.takeIf { it > 0 } ?: defaultFps).coerceIn(10, maxFps))
    }
    var confirmingDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun applyPreset(width: Int, height: Int) {
        widthText = width.toString()
        heightText = height.toString()
    }

    fun save() {
        val width = widthText.trim().toIntOrNull() ?: 0
        val height = heightText.trim().toIntOrNull() ?: 0
        if ((widthText.isNotBlank() || heightText.isNotBlank()) &&
            (width <= 0 || height <= 0)
        ) {
            error = "Enter both width and height."
            return
        }
        if (width > 2160 || height > 3840 || (width > 0 && width < 320) || (height > 0 && height < 480)) {
            error = "Use a resolution between 320×480 and 2160×3840."
            return
        }
        onSave(
            if (width > 0 && height > 0) width else 0,
            if (width > 0 && height > 0) height else 0,
            if (useDefaultFps) 0 else fps,
        )
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .clip(RoundedCornerShape(18.dp)),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(clone.label, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Only this clone changes. Leave resolution empty to follow the app.",
                        fontSize = 10.5.sp,
                        color = fluxMutedText(),
                    )

                    Text("Resolution", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        QPill("Auto", widthText.isBlank() && heightText.isBlank()) {
                            widthText = ""
                            heightText = ""
                        }
                        QPill("720×1280", widthText == "720" && heightText == "1280") {
                            applyPreset(720, 1280)
                        }
                        QPill("1080×1920", widthText == "1080" && heightText == "1920") {
                            applyPreset(1080, 1920)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        QPill("1280×720", widthText == "1280" && heightText == "720") {
                            applyPreset(1280, 720)
                        }
                        QPill("1920×1080", widthText == "1920" && heightText == "1080") {
                            applyPreset(1920, 1080)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QTextField(
                            value = widthText,
                            onValueChange = { widthText = it.filter(Char::isDigit); error = null },
                            placeholder = "Width",
                            modifier = Modifier.weight(1f),
                        )
                        QTextField(
                            value = heightText,
                            onValueChange = { heightText = it.filter(Char::isDigit); error = null },
                            placeholder = "Height",
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Frame rate", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (useDefaultFps) "default $defaultFps" else "$fps fps",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Slider(
                        value = fps.toFloat(),
                        onValueChange = { fps = it.roundToInt(); useDefaultFps = false },
                        valueRange = 10f..maxFps.toFloat(),
                        enabled = !useDefaultFps,
                        modifier = Modifier.fillMaxWidth().height(30.dp),
                    )
                    TextButton(
                        onClick = { useDefaultFps = true },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
                    ) {
                        Text(
                            if (useDefaultFps) "Using global frame rate" else "Use global frame rate",
                            fontSize = 11.sp,
                        )
                    }

                    error?.let {
                        Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { confirmingDelete = true },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                        ) {
                            Text("Uninstall", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onClose) { Text("Cancel", fontSize = 12.sp) }
                        Box(Modifier.width(96.dp)) {
                            QPrimaryButton("Save") { save() }
                        }
                    }

                    if (confirmingDelete) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                "Remove this clone and its data?",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(onClick = { confirmingDelete = false }) {
                                    Text("Keep", fontSize = 12.sp)
                                }
                                TextButton(onClick = onUninstall) {
                                    Text(
                                        "Uninstall",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- app picker

@Composable
private fun InstalledAppPicker(
    apps: List<HeadlessCloneEngine.DeviceApp>,
    onPick: (HeadlessCloneEngine.DeviceApp) -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(apps, query) {
        if (query.isBlank()) apps
        else apps.filter {
            it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .padding(14.dp)
                .clip(RoundedCornerShape(18.dp)),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxSize().padding(14.dp)) {
                Text("Choose an installed app", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    "The clone is hidden from your launcher and gets empty data.",
                    fontSize = 10.5.sp,
                    color = fluxMutedText(),
                )
                Spacer(Modifier.height(6.dp))
                QTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Search apps",
                )
                Spacer(Modifier.height(6.dp))
                if (apps.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Loading installed apps…", fontSize = 12.sp, color = fluxMutedText())
                    }
                } else if (filtered.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No matching apps", fontSize = 12.sp, color = fluxMutedText())
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(filtered, key = { it.packageName }) { app ->
                            QRow(
                                title = app.label,
                                subtitle = app.packageName,
                                leading = {
                                    QIconTile(
                                        bitmap = app.icon,
                                        fallback = Icons.Default.Android,
                                        size = 34.dp,
                                    )
                                },
                                onClick = { onPick(app) },
                                divider = true,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- helpers

private fun maxRefreshRate(context: Context): Int {
    return try {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val display = manager?.getDisplay(Display.DEFAULT_DISPLAY)
        val rate = display?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 60f
        rate.roundToInt().coerceIn(60, 240)
    } catch (t: Throwable) {
        60
    }
}

private fun defaultDisplaySize(
    context: Context,
    landscape: Boolean,
    quality: Quality,
): Pair<Int, Int> {
    val metrics = context.resources.displayMetrics
    var width = metrics.widthPixels.takeIf { it > 0 } ?: 1080
    var height = metrics.heightPixels.takeIf { it > 0 } ?: 2400
    if (landscape && width < height) {
        val swap = width
        width = height
        height = swap
    } else if (!landscape && width > height) {
        val swap = width
        width = height
        height = swap
    }
    val longSide = when (quality) {
        Quality.LOW -> 1280
        Quality.MEDIUM -> 1920
        Quality.HIGH -> 2400
    }
    val longest = max(width, height)
    if (longest > longSide) {
        val scale = longSide.toFloat() / longest
        width = (width * scale).roundToInt()
        height = (height * scale).roundToInt()
    }
    return width.coerceIn(320, 2160) to height.coerceIn(480, 3840)
}

private fun cloneUsesLandscape(context: Context, clone: CloneApp): Boolean {
    return when (clone.orientation) {
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE -> true

        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT -> false

        else -> context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
