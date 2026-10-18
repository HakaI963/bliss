package com.ivarna.fluxlinux.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
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

/**
 * Tools tab: clone-space management + setup + diagnostics.
 * The app list itself lives in the Apps tab; this screen owns installing,
 * permissions and the engine log.
 */
@Composable
fun ToolsScreen(onOpenApps: () -> Unit) {
    val context = LocalContext.current
    val entrance = rememberStaggerEntrance()
    val busy by CloneEngine.busy.collectAsState()
    val status by CloneEngine.status.collectAsState()
    val backend by CloneEngine.touchBackend.collectAsState()
    val running by CloneEngine.running.collectAsState()
    val compat by CloneEngine.cloneOnMainScreen.collectAsState()
    val cloneApps by CloneEngine.apps.collectAsState()
    var url by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    val overlayGranted = remember(tick) { Settings.canDrawOverlays(context) }
    val engineReady = remember(tick, running) {
        runCatching { BlackBoxCore.get().areServicesAvailable() }.getOrDefault(false)
    }
    val engineTail = remember(tick) { CloneEngine.engineLogTail(12) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { CloneEngine.installUri(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.staggerIn(0, entrance)) {
            Text(
                "Tools",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                "Clone space setup & diagnostics.",
                fontSize = 11.5.sp,
                color = fluxMutedText(),
            )
        }

        // ---------------------------------------------------------- clone space
        GlassSettingCard(modifier = Modifier.staggerIn(1, entrance).fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Clone space",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "APKs installed here live inside Bliss only — your launcher never sees them.",
                    fontSize = 11.5.sp,
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
                    textStyle = TextStyle(fontSize = 13.sp),
                    label = { Text("or APK link", fontSize = 12.sp) },
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
                    Text(it, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.primary)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${cloneApps.size} cloned app${if (cloneApps.size == 1) "" else "s"}",
                        fontSize = 12.sp,
                        color = fluxMutedText(),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onOpenApps) { Text("Open Apps", fontSize = 12.sp) }
                }
            }
        }

        // ---------------------------------------------------------- setup
        GlassSettingCard(modifier = Modifier.staggerIn(2, entrance).fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Setup",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                StatusRow("Touch / launches", backend.label)
                if (backend == ShellRunner.Backend.NONE) {
                    Text(
                        "Shizuku gives shell access without root: install the Shizuku app, start it via " +
                            "wireless debugging, then enable here. Root works too.",
                        fontSize = 11.sp,
                        color = fluxMutedText(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { requestTouch(context) { CloneEngine.touchBackend.value = ShellRunner.backend() } }) {
                            Text("Enable", fontSize = 12.sp)
                        }
                        TextButton(onClick = {
                            CloneEngine.touchBackend.value = ShellRunner.backend()
                            tick++
                        }) {
                            Text("Re-check", fontSize = 12.sp)
                        }
                    }
                }
                StatusRow("Overlay permission", if (overlayGranted) "Granted" else "Not granted")
                if (!overlayGranted) {
                    Text(
                        "Needed so clone apps can start while Bliss is in the background.",
                        fontSize = 11.sp,
                        color = fluxMutedText(),
                    )
                    TextButton(onClick = { openOverlaySettings(context) }) {
                        Text("Grant overlay permission", fontSize = 12.sp)
                    }
                }
                TextButton(onClick = { openMiuiPermission(context) }) {
                    Text("MIUI: background pop-up permission", fontSize = 12.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Compatibility: main screen",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "Launch clones on the phone screen instead of the hidden display (diagnostics).",
                            fontSize = 11.sp,
                            color = fluxMutedText(),
                        )
                    }
                    Switch(checked = compat, onCheckedChange = { CloneEngine.setCloneOnMainScreen(it) })
                }
            }
        }

        // ---------------------------------------------------------- diagnostics
        GlassSettingCard(modifier = Modifier.staggerIn(3, entrance).fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Diagnostics",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { tick++ }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = fluxMutedText())
                    }
                }
                StatusRow("Engine", if (engineReady) "Ready" else "Starting…")
                StatusRow("Display", if (CloneEngine.currentDisplayId() > 0) "running" else "idle")
                StatusRow("Running", running ?: "—")

                Text(
                    "Engine log",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = fluxMutedText(),
                    modifier = Modifier.padding(top = 4.dp),
                )
                Column(modifier = Modifier.heightIn(max = 170.dp).verticalScroll(rememberScrollState())) {
                    if (engineTail.isEmpty()) {
                        Text(
                            "(empty — nothing tried to launch yet)",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = fluxMutedText(),
                        )
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

        Text(
            "Agent:  clone help  /  MCP:  clone mcp-config  (in any distro)",
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
            color = fluxMutedText(),
            modifier = Modifier.padding(horizontal = 2.dp),
        )

        Spacer(Modifier.height(96.dp))
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

private fun requestTouch(context: Context, onDone: () -> Unit) {
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

private fun openOverlaySettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + context.packageName),
            ),
        )
    }
}

private fun openMiuiPermission(context: Context) {
    val tries = listOf(
        Intent("miui.intent.action.APP_PERM_EDITOR")
            .setClassName(
                "com.miui.securitycenter",
                "com.miui.permcenter.permissions.PermissionsEditorActivity",
            )
            .putExtra("extra_pkgname", context.packageName),
        Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", context.packageName),
    )
    for (i in tries) {
        if (runCatching { context.startActivity(i) }.isSuccess) return
    }
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ),
        )
    }
}
