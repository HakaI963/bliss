package com.ivarna.fluxlinux.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivarna.fluxlinux.core.clone.CloneEngine
import com.ivarna.fluxlinux.core.clone.ShellRunner
import com.ivarna.fluxlinux.core.system.PhantomProcessFixer
import com.ivarna.fluxlinux.ui.components.QCard
import com.ivarna.fluxlinux.ui.components.QChevron
import com.ivarna.fluxlinux.ui.components.QDivider
import com.ivarna.fluxlinux.ui.components.QEmptyState
import com.ivarna.fluxlinux.ui.components.QIconTile
import com.ivarna.fluxlinux.ui.components.QPrimaryButton
import com.ivarna.fluxlinux.ui.components.QRow
import com.ivarna.fluxlinux.ui.components.QScreenTitle
import com.ivarna.fluxlinux.ui.components.QSectionHeader
import com.ivarna.fluxlinux.ui.components.QStatusRow
import com.ivarna.fluxlinux.ui.components.QTextField
import com.ivarna.fluxlinux.ui.components.Quartz
import com.ivarna.fluxlinux.ui.theme.fluxMutedText
import com.ivarna.fluxlinux.ui.theme.rememberStaggerEntrance
import com.ivarna.fluxlinux.ui.theme.staggerIn

/**
 * Tools tab — a hub. "Clone apps" opens the clone sub-page where every way to
 * add apps lives (APK file, link, or clone from this phone) together with the
 * list of what was added. Per-app display/settings live in the Apps tab.
 */
private enum class ToolsPage { HUB, CLONE }

@Composable
fun ToolsScreen(onOpenApps: () -> Unit) {
    var page by rememberSaveable { mutableStateOf(ToolsPage.HUB) }
    BackHandler(enabled = page == ToolsPage.CLONE) { page = ToolsPage.HUB }
    when (page) {
        ToolsPage.HUB -> ToolsHub(onOpenClone = { page = ToolsPage.CLONE })
        ToolsPage.CLONE -> CloneScreen(onOpenApps = onOpenApps, onBack = { page = ToolsPage.HUB })
    }
}

// ------------------------------------------------------------------ hub

@Composable
private fun ToolsHub(onOpenClone: () -> Unit) {
    val entrance = rememberStaggerEntrance()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Quartz.ScreenPad, vertical = Quartz.Gap),
        verticalArrangement = Arrangement.spacedBy(Quartz.Gap),
    ) {
        QScreenTitle("Tools", "Clone and manage apps.", modifier = Modifier.staggerIn(0, entrance))
        QSectionHeader("Features", modifier = Modifier.staggerIn(0, entrance))
        QCard(modifier = Modifier.staggerIn(0, entrance)) {
            QRow(
                title = "Clone apps",
                subtitle = "Add an APK, a link, or clone what is already on this phone.",
                onClick = onOpenClone,
                trailing = { QChevron() },
            )
        }
        Spacer(Modifier.height(96.dp))
    }
}

// ------------------------------------------------------------------ clone (all options)

@Composable
private fun CloneScreen(onOpenApps: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val busy by CloneEngine.busy.collectAsState()
    val status by CloneEngine.status.collectAsState()
    val backend by CloneEngine.touchBackend.collectAsState()
    val clones by CloneEngine.apps.collectAsState()
    val deviceApps by CloneEngine.deviceApps.collectAsState()
    var url by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) {
        CloneEngine.refresh()
        CloneEngine.refreshDeviceApps()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { CloneEngine.installUri(it) }
    }

    val entrance = rememberStaggerEntrance()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Quartz.ScreenPad, vertical = Quartz.Gap),
        verticalArrangement = Arrangement.spacedBy(Quartz.Gap),
    ) {
        QScreenTitle("Clone", "Every way to add apps to the clone space.", modifier = Modifier.staggerIn(0, entrance))

        if (backend == ShellRunner.Backend.NONE) {
            QCard(modifier = Modifier.staggerIn(0, entrance)) {
                    Text(
                        "Needs Shizuku (or root) — enable it, then retry.",
                        fontSize = 11.sp,
                        color = fluxMutedText(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { requestShell(context) { CloneEngine.refreshShell() } }) {
                            Text("Enable Shizuku", fontSize = 12.sp)
                        }
                    }
            }
        }

        // 1) Add: APK file
        QSectionHeader("APK file", modifier = Modifier.staggerIn(1, entrance))
        QCard(modifier = Modifier.staggerIn(1, entrance)) {
            QPrimaryButton(
                text = "Choose APK file",
                enabled = !busy,
            ) { picker.launch(arrayOf("*/*")) }
        }

        // 2) Add: link
        QSectionHeader("Website link", modifier = Modifier.staggerIn(1, entrance))
        QCard(modifier = Modifier.staggerIn(1, entrance)) {
            QTextField(
                value = url,
                onValueChange = { url = it },
                placeholder = "direct .apk / .xapk link",
            )
            QPrimaryButton(
                text = "Install from link",
                enabled = !busy && url.isNotBlank(),
            ) { CloneEngine.installFromUrl(url) }
        }

        // 3) Add: clone from this phone
        QSectionHeader("From this phone", modifier = Modifier.staggerIn(2, entrance))
        QCard(modifier = Modifier.staggerIn(2, entrance)) {
            if (deviceApps.isEmpty()) {
                    Text(
                        "Loading installed apps…",
                        fontSize = 12.sp,
                        color = fluxMutedText(),
                        modifier = Modifier.padding(horizontal = Quartz.CardPad + 2.dp, vertical = 8.dp),
                    )
                } else {
                    deviceApps.forEachIndexed { index, app ->
                        QRow(
                            title = app.label,
                            subtitle = app.packageName,
                            leading = { QIconTile(bitmap = app.icon, fallback = Icons.Default.Android, size = 30.dp) },
                            trailing = {
                                TextButton(
                                    enabled = !busy,
                                    onClick = { CloneEngine.cloneFromDevice(app.packageName) },
                                ) { Text("Clone", fontSize = 12.sp) }
                            },
                            divider = index < deviceApps.size - 1,
                        )
                    }
                }
        }

        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        status?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }

        // Result: everything added lives here; per-app settings are in Apps.
        QSectionHeader("Added apps", modifier = Modifier.staggerIn(3, entrance))
        QCard(modifier = Modifier.staggerIn(3, entrance)) {
            if (clones.isEmpty()) {
                    QEmptyState("No apps added yet", "Pick an APK, paste a link, or clone from this phone.")
                } else {
                    clones.forEachIndexed { index, app ->
                        QRow(
                            title = app.label,
                            subtitle = app.packageName + if (app.version.isNotBlank()) " · v" + app.version else "",
                            leading = { QIconTile(bitmap = app.icon, fallback = Icons.Default.Android, size = 30.dp) },
                            trailing = {
                                TextButton(
                                    enabled = !busy,
                                    onClick = { CloneEngine.uninstall(app.packageName) },
                                ) { Text("Remove", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                            },
                            onClick = onOpenApps,
                            divider = index < clones.size - 1,
                        )
                    }
                    QDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Quartz.CardPad, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${clones.size} app(s) · settings inside the app (Apps tab)",
                            fontSize = 11.5.sp,
                            color = fluxMutedText(),
                        )
                        TextButton(onClick = onOpenApps) { Text("Open Apps", fontSize = 12.sp) }
                    }
                }
        }

        if (backend != ShellRunner.Backend.NONE) {
            QStatusRow("Shell", backend.label)
        }

        Spacer(Modifier.height(96.dp))
    }
}

private fun requestShell(context: android.content.Context, onDone: () -> Unit) {
    when {
        PhantomProcessFixer.shizukuRunning() -> PhantomProcessFixer.requestShizuku { onDone() }
        PhantomProcessFixer.rootAvailable() -> onDone()
        else -> Toast.makeText(
            context,
            "Start the Shizuku app first (wireless debugging), then try again.",
            Toast.LENGTH_LONG,
        ).show()
    }
}
