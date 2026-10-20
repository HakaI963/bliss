package com.ivarna.fluxlinux.ui.screens

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivarna.fluxlinux.core.clone.HeadlessCloneEngine
import com.ivarna.fluxlinux.ui.components.QCard
import com.ivarna.fluxlinux.ui.components.QDivider
import com.ivarna.fluxlinux.ui.components.QEmptyState
import com.ivarna.fluxlinux.ui.components.QIconTile
import com.ivarna.fluxlinux.ui.components.QPrimaryButton
import com.ivarna.fluxlinux.ui.components.QRow
import com.ivarna.fluxlinux.ui.components.QScreenTitle
import com.ivarna.fluxlinux.ui.components.QSectionHeader
import com.ivarna.fluxlinux.ui.components.QTextField
import com.ivarna.fluxlinux.ui.components.Quartz
import com.ivarna.fluxlinux.ui.theme.fluxMutedText
import com.ivarna.fluxlinux.ui.theme.rememberStaggerEntrance
import com.ivarna.fluxlinux.ui.theme.staggerIn

private enum class ToolsPage { HUB, CLONE }

@Composable
fun ToolsScreen(onOpenApps: () -> Unit) {
    var page by rememberSaveable { mutableStateOf(ToolsPage.HUB) }
    BackHandler(enabled = page == ToolsPage.CLONE) { page = ToolsPage.HUB }
    when (page) {
        ToolsPage.HUB -> ToolsHub(
            onOpenClone = { page = ToolsPage.CLONE },
            onOpenApps = onOpenApps,
        )
        ToolsPage.CLONE -> CloneScreen(onOpenApps = onOpenApps, onBack = { page = ToolsPage.HUB })
    }
}

// ------------------------------------------------------------------ hub

@Composable
private fun ToolsHub(onOpenClone: () -> Unit, onOpenApps: () -> Unit) {
    val entrance = rememberStaggerEntrance()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Quartz.ScreenPad, vertical = Quartz.Gap),
        verticalArrangement = Arrangement.spacedBy(Quartz.Gap),
    ) {
        QScreenTitle("Tools", "Clone apps and open them on the virtual display.", modifier = Modifier.staggerIn(0, entrance))
        QSectionHeader("Features", modifier = Modifier.staggerIn(0, entrance))
        QCard(modifier = Modifier.staggerIn(1, entrance)) {
            QRow(
                title = "Clone apps",
                subtitle = "Pick an APK or clone an app already installed on this phone.",
                onClick = onOpenClone,
                divider = true,
            )
            QRow(
                title = "Cloned apps & display",
                subtitle = "Live preview, tap/swipe input, stop and remove clones.",
                onClick = onOpenApps,
            )
        }
        QSectionHeader("How it works", modifier = Modifier.staggerIn(2, entrance))
        QCard(modifier = Modifier.staggerIn(2, entrance)) {
            Text(
                "Bliss repackages the app under a new package name and signature, installs it " +
                    "with its launcher hidden, then starts it on a virtual display owned by the " +
                    "Shizuku shell service. The clone gets its own empty data directory; the " +
                    "original app is untouched. Apps that verify their own signature or package " +
                    "identity may refuse to run.",
                fontSize = 11.5.sp,
                color = fluxMutedText(),
                modifier = Modifier.padding(horizontal = Quartz.CardPad + 2.dp, vertical = 9.dp),
            )
        }
        Spacer(Modifier.height(96.dp))
    }
}

// ------------------------------------------------------------------ clone

@Composable
private fun CloneScreen(onOpenApps: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val busy by HeadlessCloneEngine.busy.collectAsState()
    val operation by HeadlessCloneEngine.operation.collectAsState()
    val notice by HeadlessCloneEngine.notice.collectAsState()
    val lastError by HeadlessCloneEngine.lastError.collectAsState()
    val connected by HeadlessCloneEngine.connected.collectAsState()
    val clones by HeadlessCloneEngine.clones.collectAsState()
    val deviceApps by HeadlessCloneEngine.deviceApps.collectAsState()
    var url by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) {
        HeadlessCloneEngine.attach(context)
        HeadlessCloneEngine.bootstrap()
        HeadlessCloneEngine.refreshDeviceApps()
        HeadlessCloneEngine.refreshClones()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { HeadlessCloneEngine.cloneFromApk(it) }
    }

    val filteredApps = deviceApps.filter {
        query.isBlank() ||
            it.label.contains(query, ignoreCase = true) ||
            it.packageName.contains(query, ignoreCase = true)
    }
    val entrance = rememberStaggerEntrance()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Quartz.ScreenPad, vertical = Quartz.Gap),
        verticalArrangement = Arrangement.spacedBy(Quartz.Gap),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                QScreenTitle(
                    "Clone apps",
                    "A new copy of an app, installed hidden and run on the display.",
                    modifier = Modifier.staggerIn(0, entrance),
                )
            }
            TextButton(onClick = onBack) { Text("Done", fontSize = 12.sp) }
        }

        if (!connected) {
            QCard(modifier = Modifier.staggerIn(0, entrance)) {
                Column(
                    modifier = Modifier.padding(horizontal = Quartz.CardPad + 2.dp, vertical = 9.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text(
                        "The shell engine is not connected",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "Open Apps, install/start Shizuku, grant permission, then start the shell engine.",
                        fontSize = 11.sp,
                        color = fluxMutedText(),
                    )
                    QPrimaryButton("Go to Apps") { onOpenApps() }
                }
            }
        }

        if (busy || operation != null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            operation?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }
        }
        notice?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }
        lastError?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }

        QSectionHeader("APK file", modifier = Modifier.staggerIn(1, entrance))
        QCard(modifier = Modifier.staggerIn(1, entrance)) {
            Column(
                modifier = Modifier.padding(horizontal = Quartz.CardPad + 2.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    "Pick a single .apk. Split bundles (.apks/.xapk/.apkm) must be cloned from " +
                        "the installed app instead.",
                    fontSize = 11.sp,
                    color = fluxMutedText(),
                )
                QPrimaryButton(
                    "Choose APK file",
                    enabled = connected && !busy,
                ) {
                    picker.launch(
                        arrayOf(
                            "application/vnd.android.package-archive",
                            "application/octet-stream",
                            "*/*",
                        )
                    )
                }
            }
        }

        QSectionHeader("Direct APK link", modifier = Modifier.staggerIn(1, entrance))
        QCard(modifier = Modifier.staggerIn(1, entrance)) {
            Column(
                modifier = Modifier.padding(horizontal = Quartz.CardPad + 2.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                QTextField(
                    value = url,
                    onValueChange = { url = it },
                    placeholder = "https://example.com/app.apk",
                )
                QPrimaryButton(
                    "Download and clone",
                    enabled = connected && !busy && url.isNotBlank(),
                ) {
                    HeadlessCloneEngine.cloneFromUrl(url)
                }
            }
        }

        QSectionHeader("From this phone", modifier = Modifier.staggerIn(2, entrance))
        QCard(modifier = Modifier.staggerIn(2, entrance)) {
            Column(
                modifier = Modifier.padding(horizontal = Quartz.CardPad + 2.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                QTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Search installed apps",
                )
                Text(
                    "${filteredApps.size} app(s) available",
                    fontSize = 10.5.sp,
                    color = fluxMutedText(),
                )
                if (filteredApps.isEmpty()) {
                    QEmptyState(
                        "No matching apps",
                        "Only launchable, user-installed apps are listed.",
                    )
                } else {
                    filteredApps.take(80).forEachIndexed { index, app ->
                        QRow(
                            title = app.label,
                            subtitle = app.packageName,
                            leading = {
                                QIconTile(
                                    bitmap = app.icon,
                                    fallback = Icons.Default.Android,
                                    size = 30.dp,
                                )
                            },
                            trailing = {
                                TextButton(
                                    enabled = connected && !busy,
                                    onClick = {
                                        HeadlessCloneEngine.cloneFromInstalled(app.packageName, app.label)
                                    },
                                ) { Text("Clone", fontSize = 12.sp) }
                            },
                            divider = index < filteredApps.take(80).lastIndex,
                        )
                    }
                }
            }
        }

        QSectionHeader("Added apps", modifier = Modifier.staggerIn(3, entrance))
        QCard(modifier = Modifier.staggerIn(3, entrance)) {
            if (clones.isEmpty()) {
                QEmptyState("No clones yet", "Pick an APK, paste a direct link, or clone an installed app.")
            } else {
                clones.forEachIndexed { index, app ->
                    QRow(
                        title = app.label,
                        subtitle = "${app.originalPackage.ifBlank { app.packageName }} · ${app.status}",
                        leading = {
                            QIconTile(
                                bitmap = app.icon,
                                fallback = Icons.Default.Android,
                                size = 30.dp,
                            )
                        },
                        trailing = {
                            Row {
                                TextButton(
                                    enabled = !busy,
                                    onClick = { HeadlessCloneEngine.launchClone(app.packageName) },
                                ) { Text("Open", fontSize = 12.sp) }
                                TextButton(
                                    enabled = !busy,
                                    onClick = { HeadlessCloneEngine.deleteClone(app.packageName) },
                                ) {
                                    Text(
                                        "Remove",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        },
                        divider = index < clones.lastIndex,
                    )
                }
                QDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Quartz.CardPad + 2.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${clones.size} clone(s) · live display in Apps",
                        fontSize = 11.sp,
                        color = fluxMutedText(),
                    )
                    TextButton(onClick = onOpenApps) { Text("Open Apps", fontSize = 12.sp) }
                }
            }
        }

        Spacer(Modifier.height(96.dp))
    }
}
