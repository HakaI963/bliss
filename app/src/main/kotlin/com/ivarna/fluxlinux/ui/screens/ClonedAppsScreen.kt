package com.ivarna.fluxlinux.ui.screens

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.view.Display
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivarna.fluxlinux.core.a11y.A11yBridge
import com.ivarna.fluxlinux.core.a11y.A11yNode
import com.ivarna.fluxlinux.core.a11y.A11yNodes
import com.ivarna.fluxlinux.core.clone.HeadlessCloneEngine
import com.ivarna.fluxlinux.core.clone.HeadlessCloneEngine.CloneApp
import com.ivarna.fluxlinux.core.clone.HeadlessCloneEngine.Quality
import com.ivarna.fluxlinux.ui.components.QCard
import com.ivarna.fluxlinux.ui.components.QDivider
import com.ivarna.fluxlinux.ui.components.QEmptyState
import com.ivarna.fluxlinux.ui.components.QIconTile
import com.ivarna.fluxlinux.ui.components.QPill
import com.ivarna.fluxlinux.ui.components.QPrimaryButton
import com.ivarna.fluxlinux.ui.components.QRow
import com.ivarna.fluxlinux.ui.components.QScreenTitle
import com.ivarna.fluxlinux.ui.components.QSectionHeader
import com.ivarna.fluxlinux.ui.components.QSecondaryButton
import com.ivarna.fluxlinux.ui.components.QTextField
import com.ivarna.fluxlinux.ui.components.QToggleRow
import com.ivarna.fluxlinux.ui.components.QToolButton
import com.ivarna.fluxlinux.ui.components.Quartz
import com.ivarna.fluxlinux.ui.theme.fluxMutedText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.isActive
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.json.JSONObject

private enum class AppsSection { INSTALLED, RUNNING, PREFERENCE }

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

/** Viewer/folder state survives tab changes; a running clone must not be relaunched. */
private object CloneUiState {
    val section = mutableStateOf(AppsSection.INSTALLED)
    val groupKey = mutableStateOf<String?>(null)
    val viewing = mutableStateOf<ViewerSession?>(null)
}

/**
 * Apps tab. Two compact sections: Preference (Shizuku + clone/display options)
 * and Installed (source-app folders). Opening View stays inside this tab:
 * the small live display sits on top, live logs fill the space below it.
 */
@Composable
fun ClonedAppsScreen(startOnPreference: Boolean = false) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
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
    val displayReady by HeadlessCloneEngine.displayReady.collectAsState()
    val activeClonePackage by HeadlessCloneEngine.activeClonePackage.collectAsState()
    val quality by HeadlessCloneEngine.quality.collectAsState()
    val touchEnabled by HeadlessCloneEngine.touchEnabled.collectAsState()
    val maxFps = remember(context) { maxRefreshRate(context) }
    val fpsValue = displayFps.coerceIn(10, maxFps)

    var section by CloneUiState.section
    var groupKey by CloneUiState.groupKey
    var viewing by CloneUiState.viewing
    LaunchedEffect(Unit) {
        if (startOnPreference) CloneUiState.section.value = AppsSection.PREFERENCE
    }
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
    LaunchedEffect(clones) {
        val current = viewing ?: return@LaunchedEffect
        if (clones.isNotEmpty() && clones.none { it.packageName == current.clone.packageName }) {
            viewing = null
        }
    }
    LaunchedEffect(section, connected) {
        if (section != AppsSection.RUNNING || !connected) return@LaunchedEffect
        while (isActive) {
            HeadlessCloneEngine.refreshClones()
            delay(2500)
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
                    clones = items.sortedWith(
                        compareByDescending<CloneApp> { it.lastOpened }
                            .thenByDescending { it.createdAt }
                            .thenBy { it.label.lowercase() }
                    ),
                )
            }
            .sortedWith(
                compareByDescending<CloneGroup> {
                    it.clones.maxOfOrNull { clone -> clone.lastOpened } ?: 0L
                }.thenByDescending {
                    it.clones.maxOfOrNull { clone -> clone.createdAt } ?: 0L
                }.thenBy { it.label.lowercase() }
            )
    }
    val selectedGroup = groups.firstOrNull { it.key == groupKey }

    LaunchedEffect(groups, groupKey) {
        if (groupKey != null && selectedGroup == null) groupKey = null
    }

    /** Hide the viewer, keep the clone process and virtual display alive. */
    fun closeViewer() {
        if (viewing == null) return
        viewing = null
        HeadlessCloneEngine.leaveViewerRunning()
    }

    /** Stop the clone process and release the display. */
    fun stopViewer() {
        val pkg = viewing?.clone?.packageName ?: activeClonePackage
        if (pkg != null) HeadlessCloneEngine.stopClone(pkg)
        viewing = null
    }

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
        // The engine owns the display. If this package already owns it, never
        // call launch again — that would restart the app and lose agent state.
        val resumeLive = activeClonePackage == clone.packageName
        if (resumeLive) {
            viewing = ViewerSession(
                clone = clone, width = width, height = height, fps = fps, touch = touchEnabled,
            )
            return
        }
        if (!resumeLive) {
            HeadlessCloneEngine.clearPreview()
            HeadlessCloneEngine.clearCloneLogs()
            HeadlessCloneEngine.launchClone(clone.packageName, width, height, fps)
        }
        viewing = ViewerSession(
            clone = clone,
            width = width,
            height = height,
            fps = fps,
            touch = touchEnabled,
        )
    }

    BackHandler(enabled = viewing != null || groupKey != null) {
        if (viewing != null) closeViewer() else groupKey = null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Quartz.ScreenPad)
            .padding(top = 10.dp),
    ) {
        QScreenTitle(
            "Apps",
            if (connected) "Private clone display · shell connected"
            else "Run cloned apps on a private virtual display.",
        )
        Spacer(Modifier.height(8.dp))
        SectionSwitch(
            selected = section,
            runningCount = clones.count { it.status == "running" },
            onSelect = {
                // Switching between Installed / Running / Preference must
                // actually show that section. The clone keeps running.
                if (viewing != null) closeViewer()
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
            Text(
                it,
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
        val message = lastError ?: notice
        if (!message.isNullOrBlank()) {
            Text(
                message,
                fontSize = 11.5.sp,
                color = if (lastError != null) MaterialTheme.colorScheme.error else fluxMutedText(),
                maxLines = 2,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
        Spacer(Modifier.height(8.dp))

        when {
            viewing != null -> CloneViewPage(
                modifier = Modifier.weight(1f),
                session = viewing!!,
                onLeave = ::closeViewer,
                onStop = ::stopViewer,
                onPip = {
                    val session = viewing
                    if (session != null) {
                        val intent = Intent(context, com.ivarna.fluxlinux.ClonePipActivity::class.java)
                            .putExtra("width", session.width)
                            .putExtra("height", session.height)
                            .putExtra("fps", session.fps)
                        runCatching { context.startActivity(intent) }
                    }
                },
            )

            section == AppsSection.RUNNING -> RunningSection(
                modifier = Modifier.weight(1f),
                clones = clones.filter { it.status == "running" },
                connected = connected,
                onView = ::openViewer,
            )

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
                onCustomise = { customiseClone = it },
            )
        }
    }

    customiseClone?.let { clone ->
        CustomiseSheet(
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
private fun SectionSwitch(
    selected: AppsSection,
    runningCount: Int,
    onSelect: (AppsSection) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        SectionTab(
            label = "Installed", selected = selected == AppsSection.INSTALLED,
            modifier = Modifier.weight(1f),
        ) { onSelect(AppsSection.INSTALLED) }
        SectionTab(
            label = if (runningCount > 0) "Running $runningCount" else "Running",
            selected = selected == AppsSection.RUNNING,
            modifier = Modifier.weight(1f),
        ) { onSelect(AppsSection.RUNNING) }
        SectionTab(
            label = "Preference", selected = selected == AppsSection.PREFERENCE,
            modifier = Modifier.weight(1f),
        ) { onSelect(AppsSection.PREFERENCE) }
    }
}

@Composable
private fun SectionTab(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(9.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.surface
                else Color.Transparent
            )
            .then(
                if (selected) Modifier.border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(9.dp)
                ) else Modifier
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 12.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RunningSection(
    modifier: Modifier,
    clones: List<CloneApp>,
    connected: Boolean,
    onView: (CloneApp) -> Unit,
) {
    if (clones.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            QCard {
                QEmptyState(
                    "Nothing running",
                    "Open a clone from Installed, or launch one from an agent. Running clones stay here.",
                )
            }
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 108.dp),
    ) {
        items(clones, key = { it.packageName }) { clone ->
            QCard {
                QRow(
                    title = clone.label,
                    subtitle = clone.packageName,
                    leading = { QIconTile(clone.icon, size = 38.dp) },
                    trailing = {
                        Box(
                            Modifier.size(8.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    },
                )
                Text(
                    cloneMeta(clone),
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp,
                    color = fluxMutedText(),
                    modifier = Modifier.padding(
                        start = Quartz.CardPad, end = Quartz.CardPad, bottom = 5.dp,
                    ),
                )
                Row(
                    modifier = Modifier.padding(
                        start = Quartz.CardPad, end = Quartz.CardPad, bottom = 8.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QPrimaryButton(
                        "Resume",
                        enabled = connected,
                        icon = Icons.Default.PlayArrow,
                    ) { onView(clone) }
                    QSecondaryButton(
                        "Stop",
                        icon = Icons.Default.Stop,
                    ) { HeadlessCloneEngine.stopClone(clone.packageName) }
                    QSecondaryButton(
                        "Logs",
                        icon = Icons.Default.List,
                    ) { onView(clone) }
                }
            }
        }
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
            .padding(bottom = 108.dp),
    ) {
        QSectionHeader("Shizuku")
        QCard {
            Column(
                modifier = Modifier.padding(horizontal = Quartz.CardPad, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    connected -> MaterialTheme.colorScheme.primary
                                    shizukuRunning && permission -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.error
                                }
                            )
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        when {
                            !shizukuInstalled -> "Shizuku is not installed"
                            !shizukuRunning -> "Shizuku service is stopped"
                            !permission -> "Shizuku permission is not granted"
                            !connected -> "Shell engine is not connected"
                            else -> "Shell engine connected · v$version"
                        },
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = onRefresh,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text("Refresh", fontSize = 12.sp)
                    }
                }
                Text(
                    "Clone installs, display capture and input run through the Shizuku shell.",
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = fluxMutedText(),
                )
                when {
                    !shizukuInstalled -> QPrimaryButton("Get Shizuku") { onGetShizuku() }
                    !shizukuRunning -> QPrimaryButton("Open Shizuku") { onOpenShizuku() }
                    !permission -> QPrimaryButton("Grant permission") { onGrant() }
                    !connected -> QPrimaryButton("Start shell engine") { onStart() }
                }
            }
        }

        QSectionHeader("Clone")
        QCard {
            QRow(
                title = "From APK file",
                subtitle = "Pick a single .apk stored on this phone",
                leading = { QIconTile(bitmap = null, fallback = Icons.Default.Android, size = 34.dp) },
                onClick = { if (connected && !busy) onApk() },
                divider = true,
            )
            QRow(
                title = "From installed app",
                subtitle = "Copy an app already on this phone",
                leading = { QIconTile(bitmap = null, fallback = Icons.Default.Android, size = 34.dp) },
                onClick = { if (connected && !busy) onInstalled() },
            )
            if (!connected) {
                Text(
                    "Start the shell engine before creating a clone.",
                    fontSize = 11.5.sp,
                    color = fluxMutedText(),
                    modifier = Modifier.padding(horizontal = Quartz.CardPad, vertical = 8.dp),
                )
            }
        }

        QSectionHeader("Display")
        QCard {
            Column(
                modifier = Modifier.padding(horizontal = Quartz.CardPad, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Frame rate",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    Text("$fps fps", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.primary)
                    Text(" · max $maxFps", fontSize = 11.sp, color = fluxMutedText())
                }
                Slider(
                    value = fps.toFloat(),
                    onValueChange = { onFps(it.roundToInt()) },
                    valueRange = 10f..maxFps.toFloat(),
                    modifier = Modifier.fillMaxWidth().height(32.dp),
                )
                Text(
                    "10 fps saves battery. The upper end follows this display.",
                    fontSize = 11.5.sp,
                    color = fluxMutedText(),
                )
                Spacer(Modifier.height(4.dp))
                Text("Quality", fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Quality.entries.forEach { entry ->
                        QPill(entry.label, quality == entry) { onQuality(entry) }
                    }
                }
                Text(
                    "Scales the virtual resolution and stream sharpness.",
                    fontSize = 11.5.sp,
                    color = fluxMutedText(),
                )
            }
            QDivider()
            QToggleRow(
                title = "Touch input",
                subtitle = "Allow taps and swipes on the clone display",
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
        contentPadding = PaddingValues(bottom = 108.dp),
    ) {
        if (groups.isNotEmpty()) {
            item {
                Text(
                    "Recently used first · package, created and last-opened time",
                    fontSize = 10.sp,
                    color = fluxMutedText(),
                    modifier = Modifier.padding(start = 4.dp, bottom = 1.dp),
                )
            }
        }
        if (groups.isEmpty()) {
            item {
                QCard {
                    QEmptyState(
                        "No clones yet",
                        if (connected) "Open Preference to clone an APK or an installed app."
                        else "Connect Shizuku in Preference to get started.",
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Quartz.CardPad, vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        QPrimaryButton("Open Preference") { onOpenPreference() }
                    }
                    Spacer(Modifier.height(8.dp))
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
                                size = 38.dp,
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
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    group.clones.size.toString(),
                                    fontSize = 12.sp,
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
    onCustomise: (CloneApp) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 108.dp),
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back to Installed",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        group.label,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        folderSubtitle(group),
                        fontSize = 11.5.sp,
                        color = fluxMutedText(),
                    )
                }
            }
        }
        items(group.clones, key = { it.packageName }) { clone ->
            QCard {
                QRow(
                    title = clone.label,
                    subtitle = clone.packageName,
                    leading = {
                        QIconTile(
                            bitmap = clone.icon,
                            fallback = Icons.Default.Android,
                            size = 38.dp,
                        )
                    },
                    trailing = {
                        if (clone.status == "running") {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                    },
                )
                Text(
                    cloneMeta(clone),
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp,
                    color = fluxMutedText(),
                    modifier = Modifier.padding(
                        start = Quartz.CardPad, end = Quartz.CardPad, bottom = 5.dp,
                    ),
                )
                Row(
                    modifier = Modifier.padding(
                        start = Quartz.CardPad,
                        end = Quartz.CardPad,
                        bottom = 8.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QPrimaryButton(
                        if (clone.status == "running") "Resume" else "View",
                        enabled = connected,
                        icon = Icons.Default.PlayArrow,
                    ) { onView(clone) }
                    if (clone.status == "running") {
                        QSecondaryButton(
                            "Stop",
                            icon = Icons.Default.Stop,
                        ) { HeadlessCloneEngine.stopClone(clone.packageName) }
                    }
                    QSecondaryButton(
                        "Customise",
                        icon = Icons.Default.Tune,
                    ) { onCustomise(clone) }
                }
            }
        }
    }
}

private fun cloneMeta(clone: CloneApp): String {
    val created = clone.createdAt.takeIf { it > 0 }?.let {
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(it))
    } ?: "unknown"
    val opened = clone.lastOpened.takeIf { it > 0 }?.let {
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(it))
    } ?: "never"
    val parts = if (clone.parts > 0) " · ${clone.parts} parts" else ""
    return "${clone.status} · created $created · last $opened$parts"
}

private fun folderSubtitle(group: CloneGroup): String {
    val running = group.clones.count { it.status == "running" }
    return buildString {
        append(group.clones.size).append(if (group.clones.size == 1) " clone" else " clones")
        if (running > 0) append(" · ").append(running).append(" running")
    }
}

// ---------------------------------------------------------------- viewer

@Composable
private fun CloneViewPage(
    modifier: Modifier,
    session: ViewerSession,
    onLeave: () -> Unit,
    onStop: () -> Unit,
    onPip: () -> Unit,
) {
    val context = LocalContext.current
    val connected by HeadlessCloneEngine.connected.collectAsState()
    val displayReady by HeadlessCloneEngine.displayReady.collectAsState()
    val preview by HeadlessCloneEngine.preview.collectAsState()
    val previewError by HeadlessCloneEngine.previewError.collectAsState()
    val lastError by HeadlessCloneEngine.lastError.collectAsState()
    val lines by HeadlessCloneEngine.cloneLogs.collectAsState()
    val actions by HeadlessCloneEngine.actionLog.collectAsState()
    val displayId by HeadlessCloneEngine.displayId.collectAsState()
    var live by rememberSaveable { mutableStateOf(true) }
    var showLayout by rememberSaveable { mutableStateOf(false) }
    var showElements by rememberSaveable { mutableStateOf(false) }
    var split by rememberSaveable { mutableStateOf(0.58f) }
    var displayScale by rememberSaveable { mutableStateOf(1f) }
    val logState = rememberLazyListState()
    val actionState = rememberLazyListState()

    LaunchedEffect(lines.lastOrNull()) {
        // Follow the tail without an animation on every log tick: animating a
        // 300-line list at each 900 ms refresh visibly janks the viewer.
        if (lines.isNotEmpty()) logState.scrollToItem(lines.lastIndex)
    }
    LaunchedEffect(actions.lastOrNull()) {
        if (actions.isNotEmpty()) actionState.scrollToItem(minOf(actions.size, 40) - 1)
    }

    LaunchedEffect(connected, displayReady, session.fps, live) {
        if (!connected || !live) return@LaunchedEffect
        // The virtual display can run at 80–120 fps; the JPEG preview cannot.
        // Cap the UI stream at 24 fps so a high display rate does not starve
        // input/rendering and make the viewer feel heavy.
        val frameDelay = (1000 / session.fps.coerceIn(10, 24)).coerceAtLeast(42).toLong()
        while (isActive) {
            if (displayReady) HeadlessCloneEngine.capturePreview()
            delay(frameDelay)
        }
    }

    LaunchedEffect(session.clone.packageName, connected) {
        if (!connected) return@LaunchedEffect
        while (isActive) {
            HeadlessCloneEngine.fetchCloneLogs(session.clone.packageName, 300)
            delay(900)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 92.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(split.coerceIn(0.3f, 0.8f)),
            contentAlignment = Alignment.Center,
        ) {
            val aspect = preview?.let {
                it.width.toFloat() / it.height.coerceAtLeast(1).toFloat()
            } ?: if (session.width > 0 && session.height > 0) {
                session.width.toFloat() / session.height.toFloat()
            } else {
                9f / 16f
            }
            val maxWindowHeight = min(maxHeight.value, 330f)
            val baseWidth = min(maxWidth.value, maxWindowHeight * aspect)
            val windowWidth = (baseWidth * displayScale).coerceIn(160f, maxWidth.value)
            val windowHeight = windowWidth / aspect
            val shape = RoundedCornerShape(8.dp)

            Box(
                modifier = Modifier
                    .width(windowWidth.dp)
                    .height(windowHeight.dp)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
            ) {
                val bitmap = preview
                if (bitmap != null) {
                    ViewerFrame(
                        bitmap = bitmap,
                        touchEnabled = session.touch && live,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (!live) {
                        Text(
                            "Preview paused",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f))
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                                    RoundedCornerShape(50)
                                )
                                .padding(horizontal = 9.dp, vertical = 4.dp),
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(
                            Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            when {
                                !connected -> "Starting the shell engine…"
                                else -> previewError ?: lastError ?: "Starting ${session.clone.label}…"
                            },
                            fontSize = 11.5.sp,
                            lineHeight = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                }
                // Element tree of the clone display: tap by name, not by pixel.
                QToolButton(
                    "Elements",
                    Icons.Default.Widgets,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp),
                ) { showElements = true }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(13.dp)
                .pointerInput(Unit) {
                    detectDragGestures { _, dragAmount ->
                        split = (split + dragAmount.y / 650f).coerceIn(0.3f, 0.8f)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .width(42.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QToolButton(
                "Leave",
                Icons.Default.Home,
            ) { onLeave() }
            QToolButton(
                if (live) "Live" else "Paused",
                if (live) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                active = live,
            ) { live = !live }
            QToolButton("PiP", Icons.Default.PictureInPictureAlt) { onPip() }
            QToolButton("Layout", Icons.Default.Tune, active = showLayout) { showLayout = !showLayout }
            Spacer(Modifier.weight(1f))
            QToolButton(
                "Stop",
                Icons.Default.Stop,
                destructive = true,
            ) { onStop() }
        }

        if (showLayout) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(Quartz.CardShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, Quartz.CardShape)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Display height", fontSize = 11.sp, color = fluxMutedText())
                    Slider(
                        value = split, onValueChange = { split = it },
                        valueRange = 0.3f..0.8f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Text("${(split * 100).roundToInt()}%", fontSize = 10.5.sp, color = fluxMutedText())
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Display width ", fontSize = 11.sp, color = fluxMutedText())
                    Slider(
                        value = displayScale, onValueChange = { displayScale = it },
                        valueRange = 0.55f..1f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Text("${(displayScale * 100).roundToInt()}%", fontSize = 10.5.sp, color = fluxMutedText())
                }
                Text(
                    "Tap Layout again to close. Drag the display area with the height control.",
                    fontSize = 10.sp, color = fluxMutedText(), modifier = Modifier.padding(bottom = 5.dp),
                )
            }
        }

        ActionStrip(actions, actionState)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f - split.coerceIn(0.3f, 0.8f))
                .clip(Quartz.CardShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, Quartz.CardShape),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, top = 9.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Live logs",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (lines.isEmpty()) "waiting" else "${lines.size} lines",
                    fontSize = 11.sp,
                    color = fluxMutedText(),
                )
                IconButton(
                    onClick = { copyText(context, lines.joinToString("\n"), "Logs copied") },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(Icons.Default.ContentCopy, "Copy logs", tint = fluxMutedText(), modifier = Modifier.size(15.dp))
                }
                IconButton(
                    onClick = { HeadlessCloneEngine.clearCloneLogs() },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(Icons.Default.Clear, "Clear logs", tint = fluxMutedText(), modifier = Modifier.size(16.dp))
                }
            }
            HorizontalDivider(
                thickness = 0.75.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            if (lines.isEmpty()) {
                Box(
                    Modifier.weight(1f).fillMaxWidth().padding(14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (connected) "Waiting for app output…" else "Shell engine disconnected.",
                        fontSize = 12.sp,
                        color = fluxMutedText(),
                    )
                }
            } else {
                LazyColumn(
                    state = logState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    items(lines) { line ->
                        Text(
                            line,
                            fontSize = 9.5.sp,
                            lineHeight = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                }
            }
        }
    }

    if (showElements) {
        ElementsSheet(
            displayId = displayId,
            packageName = HeadlessCloneEngine.activeClonePackage.value,
            onClose = { showElements = false },
        )
    }
}

// ---------------------------------------------------------------- elements

/** Result of one accessibility tree read for the elements panel. */
private data class ElementsSnapshot(
    val running: Boolean,
    val nodes: List<A11yNode>,
    val note: String?,
)

/**
 * Element list of the virtual display. Tapping a row clicks that node through
 * the accessibility service, so a clone can be driven by name instead of by
 * pixel coordinates.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ElementsSheet(
    displayId: Int,
    packageName: String?,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Accessibility: checking…") }
    var running by remember { mutableStateOf(false) }
    var nodes by remember { mutableStateOf<List<A11yNode>>(emptyList()) }
    var note by remember { mutableStateOf<String?>(null) }
    var enableNote by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun refresh() {
        if (busy) return
        busy = true
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                loadElements(context, displayId, packageName)
            }
            running = loaded.running
            status = if (loaded.running) "Accessibility: ready" else "Accessibility: not enabled"
            nodes = loaded.nodes
            note = loaded.note
            busy = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    ModalBottomSheet(
        onDismissRequest = onClose,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Elements", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { refresh() },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text("Refresh", fontSize = 12.sp)
                }
            }
            Text(
                "Tap an element to open it in the clone.",
                fontSize = 11.5.sp,
                color = fluxMutedText(),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (running) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                        )
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    status,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                if (!running) {
                    QSecondaryButton("Enable") {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                A11yBridge.ensureEnabled(context)
                            }
                            val obj = runCatching { JSONObject(result) }.getOrNull()
                            if (obj?.optBoolean("ok", false) == true) {
                                enableNote = "Accessibility service enabled."
                                refresh()
                            } else {
                                val error = obj?.optString("error").orEmpty()
                                enableNote = if (error.contains("root", ignoreCase = true)) {
                                    "Root is unavailable — enable the service from Android settings."
                                } else {
                                    error.ifBlank { "The accessibility service could not be enabled." }
                                }
                            }
                        }
                    }
                }
            }
            (enableNote ?: note)?.let {
                Text(
                    it,
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp,
                    color = fluxMutedText(),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            when {
                nodes.isEmpty() && busy -> Box(
                    Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Reading elements…", fontSize = 12.sp, color = fluxMutedText())
                }

                nodes.isEmpty() -> Box(
                    Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (running) {
                            "No elements found. This app paints its UI on a canvas, so OCR and pixel taps are still used for it."
                        } else {
                            "The accessibility service is not running, so no elements can be read."
                        },
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = fluxMutedText(),
                    )
                }

                else -> LazyColumn(Modifier.fillMaxWidth()) {
                    itemsIndexed(nodes) { _, node ->
                        QRow(
                            title = node.label.ifBlank { node.type },
                            subtitle = elementSubtitle(node),
                            onClick = {
                                scope.launch {
                                    var result = withContext(Dispatchers.IO) {
                                        A11yBridge.click(context, node.id)
                                    }
                                    var obj = runCatching { JSONObject(result) }.getOrNull()
                                    // Node paths are only valid inside one tree.
                                    // When the clone changed underneath us, resolve
                                    // the same label again and retry once.
                                    if (obj?.optBoolean("ok", false) != true &&
                                        obj?.optString("error").orEmpty().contains("not found") &&
                                        node.label.isNotBlank()
                                    ) {
                                        val fresh = withContext(Dispatchers.IO) {
                                            loadElements(context, displayId, packageName)
                                        }
                                        val again = fresh.nodes.firstOrNull {
                                            it.label == node.label && it.id != node.id
                                        }
                                        if (again != null) {
                                            result = withContext(Dispatchers.IO) {
                                                A11yBridge.click(context, again.id)
                                            }
                                            obj = runCatching { JSONObject(result) }.getOrNull()
                                        }
                                    }
                                    if (obj?.optBoolean("ok", false) != true) {
                                        enableNote = obj?.optString("error").orEmpty()
                                            .ifBlank { "Tap failed" }
                                    }
                                    refresh()
                                }
                            },
                            divider = true,
                        )
                    }
                }
            }
        }
    }
}

/** Reads the accessibility tree of the virtual display. Blocking, IO only. */
private fun loadElements(
    context: Context,
    displayId: Int,
    packageName: String? = null,
): ElementsSnapshot {
    val status = runCatching { A11yBridge.status(context) }.getOrDefault("{}")
    val running = runCatching { JSONObject(status).optBoolean("serviceRunning", false) }.getOrDefault(false)
    if (!running) {
        return ElementsSnapshot(
            running = false,
            nodes = emptyList(),
            note = "Enable the service to tap app elements by name.",
        )
    }
    val tree = runCatching {
        A11yBridge.tree(context, displayId, 600, packageName)
    }.getOrDefault("{}")
    val all = A11yNodes.parse(tree)
    // Plain containers are noise: keep the elements a person would target —
    // anything with a label, plus everything clickable, editable or
    // scrollable — and order them by usefulness.
    val useful = all.filter { node ->
        node.label.isNotBlank() || node.clickable || node.editable || node.scrollable
    }
    val nodes = (if (useful.isEmpty()) all else useful)
        .sortedWith(
            compareByDescending<A11yNode> { it.label.isNotBlank() }
                .thenByDescending { it.clickable }
                .thenBy { it.depth },
        )
        .take(120)
    return ElementsSnapshot(
        running = true,
        nodes = nodes,
        note = if (all.isEmpty()) {
            "This app paints its UI on a canvas, so OCR and pixel taps are still used for it."
        } else {
            null
        },
    )
}

/** "TextView · 12,34 → 200,98" style summary for one element row. */
private fun elementSubtitle(node: A11yNode): String {
    val parts = ArrayList<String>(3)
    parts.add(node.type)
    if (node.boundsLabel.isNotEmpty()) parts.add(node.boundsLabel)
    if (node.clickable) parts.add("clickable")
    if (node.editable) parts.add("editable")
    if (node.scrollable) parts.add("scrollable")
    return parts.joinToString(" · ")
}

@Composable
private fun ActionStrip(actions: List<String>, state: LazyListState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(11.dp))
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "ACTIONS", fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp, color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(7.dp))
        if (actions.isEmpty()) {
            Text("No agent or touch actions yet", fontSize = 10.sp, color = fluxMutedText())
        } else {
            LazyRow(
                state = state,
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(actions.takeLast(40)) { action ->
                    val agent = action.contains("  agent  ")
                    Text(
                        action,
                        fontSize = 9.2.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (agent) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
            }
        }
        if (actions.isNotEmpty()) {
            IconButton(
                onClick = { HeadlessCloneEngine.clearActionLog() },
                modifier = Modifier.size(24.dp),
            ) {
                Icon(Icons.Default.Clear, "Clear actions", tint = fluxMutedText(), modifier = Modifier.size(13.dp))
            }
        }
    }
}

private fun copyText(context: Context, text: String, label: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Altaris", text))
    Toast.makeText(context, label, Toast.LENGTH_SHORT).show()
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
        val scale = min(
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
            contentScale = ContentScale.Fit,
        )
    }
}

// ---------------------------------------------------------------- customise

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomiseSheet(
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
        error = null
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
        if (width > 2160 || height > 3840 ||
            (width > 0 && width < 320) || (height > 0 && height < 480)
        ) {
            error = "Use a resolution between 320×480 and 2160×3840."
            return
        }
        onSave(
            if (width > 0 && height > 0) width else 0,
            if (width > 0 && height > 0) height else 0,
            if (useDefaultFps) 0 else fps,
        )
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Customise clone", fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Text(
                clone.label,
                fontSize = 12.sp,
                color = fluxMutedText(),
            )
            Text(
                "Only this clone changes. Leave resolution on Auto to follow the app.",
                fontSize = 11.5.sp,
                lineHeight = 15.sp,
                color = fluxMutedText(),
            )

            Text("Resolution", fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                QPill("Auto", widthText.isBlank() && heightText.isBlank()) {
                    widthText = ""
                    heightText = ""
                    error = null
                }
                QPill("720×1280", widthText == "720" && heightText == "1280") {
                    applyPreset(720, 1280)
                }
                QPill("1080×1920", widthText == "1080" && heightText == "1920") {
                    applyPreset(1080, 1920)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
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
                Text("Frame rate", fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text(
                    if (useDefaultFps) "Default · $defaultFps fps" else "$fps fps",
                    fontSize = 12.sp,
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
                contentPadding = PaddingValues(horizontal = 0.dp),
            ) {
                Text(
                    if (useDefaultFps) "Using the display default" else "Use the display default",
                    fontSize = 12.sp,
                )
            }

            error?.let {
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }

            HorizontalDivider(
                Modifier.padding(vertical = 4.dp),
                thickness = 0.75.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { confirmingDelete = true },
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) {
                    Text("Uninstall", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Cancel", fontSize = 12.5.sp) }
                QPrimaryButton("Save") { save() }
            }

            if (confirmingDelete) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "Remove this clone and its data?",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
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

// ---------------------------------------------------------------- app picker

@OptIn(ExperimentalMaterial3Api::class)
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

    ModalBottomSheet(
        onDismissRequest = onClose,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text("Choose an installed app", fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Text(
                "The clone is hidden from your launcher and gets empty data.",
                fontSize = 11.5.sp,
                color = fluxMutedText(),
            )
            Spacer(Modifier.height(10.dp))
            QTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search apps",
            )
            Spacer(Modifier.height(8.dp))
            when {
                apps.isEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Loading installed apps…", fontSize = 12.sp, color = fluxMutedText())
                }

                filtered.isEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("No matching apps", fontSize = 12.sp, color = fluxMutedText())
                }

                else -> LazyColumn(Modifier.fillMaxSize()) {
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


private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
