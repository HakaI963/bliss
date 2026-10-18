package com.ivarna.fluxlinux.ui.screens

import android.widget.Toast
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
 * Tools tab — a single page: add an app with the file picker or a link, and
 * see everything that was added. Display and per-app settings live inside
 * each app in the Apps tab; tapping a row here jumps there.
 */
@Composable
fun ToolsScreen(onOpenApps: () -> Unit) {
    val context = LocalContext.current
    val busy by CloneEngine.busy.collectAsState()
    val status by CloneEngine.status.collectAsState()
    val backend by CloneEngine.touchBackend.collectAsState()
    val clones by CloneEngine.apps.collectAsState()
    var url by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { CloneEngine.refresh() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { CloneEngine.installUri(it) }
    }

    val entrance = rememberStaggerEntrance()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Quartz.ScreenPad, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(Quartz.Gap),
    ) {
        QScreenTitle(
            "Tools",
            "Add apps to the clone space.",
            modifier = Modifier.staggerIn(0, entrance),
        )

        QSectionHeader("Clone app", modifier = Modifier.staggerIn(1, entrance))
        QCard(modifier = Modifier.staggerIn(1, entrance)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Quartz.CardPad),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QPrimaryButton(
                    "Choose APK file",
                    onClick = { picker.launch(arrayOf("*/*")) },
                    enabled = !busy,
                )
                QTextField(
                    value = url,
                    onValueChange = { url = it },
                    placeholder = "https://…/app.apk",
                )
                QPrimaryButton(
                    "Install from link",
                    onClick = {
                        if (url.isNotBlank()) {
                            CloneEngine.installFromUrl(url)
                            url = ""
                        }
                    },
                    enabled = !busy && url.isNotBlank(),
                )
                if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                status?.let { Text(it, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.primary) }
            }
            if (backend == ShellRunner.Backend.NONE) {
                Column(
                    modifier = Modifier.padding(horizontal = Quartz.CardPad + 2.dp, vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        "Needs Shizuku (or root) — enable it, then retry.",
                        fontSize = 11.sp,
                        color = fluxMutedText(),
                    )
                    TextButton(onClick = { requestShell(context) { CloneEngine.refreshShell() } }) {
                        Text("Enable Shizuku", fontSize = 12.sp)
                    }
                }
            } else {
                QStatusRow("Shell", backend.label)
            }
        }

        QSectionHeader("Added apps", modifier = Modifier.staggerIn(2, entrance))
        QCard(modifier = Modifier.staggerIn(2, entrance)) {
            if (clones.isEmpty()) {
                QEmptyState("No apps added yet", "Pick an APK above or paste a link.")
            } else {
                clones.forEach { app ->
                    QRow(
                        title = app.label,
                        subtitle = if (app.version.isBlank()) app.packageName
                        else "${app.packageName} · v${app.version}",
                        leading = { QIconTile(bitmap = app.icon, size = 30.dp) },
                        trailing = {
                            TextButton(onClick = { CloneEngine.uninstall(app.packageName) }) {
                                Text("Remove", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                            }
                        },
                        onClick = onOpenApps,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Quartz.CardPad + 2.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${clones.size} app(s) · settings live in Apps → tap the app",
                        fontSize = 11.sp,
                        color = fluxMutedText(),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onOpenApps) { Text("Open Apps", fontSize = 12.sp) }
                }
            }
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
