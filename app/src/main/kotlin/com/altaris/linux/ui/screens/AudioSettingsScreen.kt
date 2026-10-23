package com.altaris.linux.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.animateContentSize
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
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altaris.linux.ui.components.AltarisSubHeader
import com.altaris.linux.core.terminal.PulseHost
import com.altaris.linux.ui.components.GlassSettingCard
import com.altaris.linux.ui.theme.AltarisBackground
import com.altaris.linux.ui.theme.TextPrimary
import com.altaris.linux.ui.theme.TextSecondary
import com.altaris.linux.ui.theme.altarisMutedText
import com.altaris.linux.ui.theme.rememberStaggerEntrance
import com.altaris.linux.ui.theme.staggerIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Host PulseAudio status / start / restart / logs.
 * Guests stay clients (`PULSE_SERVER=tcp:127.0.0.1`).
 * Monochrome frost: status + buttons in TextPrimary/TextSecondary greys only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioSettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<PulseHost.Status?>(null) }
    var busy by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf("") }
    var showLogs by remember { mutableStateOf(false) }

    fun loadStatus(startIfStopped: Boolean = false) {
        if (busy) return
        busy = true
        scope.launch {
            val (s, log) = withContext(Dispatchers.IO) {
                var q = PulseHost.query(context)
                // prepareHost no longer starts Pulse; this screen does it on open.
                if (startIfStopped && !q.running) {
                    PulseHost.ensureStarted(context)
                    q = PulseHost.query(context)
                }
                q to PulseHost.readLog(context)
            }
            status = s
            logText = log
            busy = false
        }
    }

    fun runAction(
        label: String,
        toastOf: ((String) -> String)? = null,
        block: (Context) -> String
    ) {
        if (busy) return
        busy = true
        scope.launch {
            val (s, log, toast) = withContext(Dispatchers.IO) {
                val out = block(context)
                Triple(
                    PulseHost.query(context),
                    PulseHost.readLog(context),
                    toastOf?.invoke(out) ?: label
                )
            }
            status = s
            logText = log
            showLogs = true
            busy = false
            Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) { loadStatus(startIfStopped = true) }

    // Staggered card entrance (30ms steps) — visual only.
    val entrance = rememberStaggerEntrance()

    val running = status?.running == true
    val healthy = status?.healthy == true
    val statusColor = when {
        status == null -> TextSecondary
        healthy -> TextPrimary
        running -> TextSecondary
        else -> TextSecondary.copy(alpha = 0.6f)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AltarisSubHeader(
                title = "Audio",
                onBack = onBack,
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Host PulseAudio in the Altaris prefix. Guests connect with PULSE_SERVER=tcp:127.0.0.1. Stopping the desktop does not stop this service.",
                fontSize = 13.sp,
                color = altarisMutedText(),
                modifier = Modifier.staggerIn(0, entrance)
            )

            GlassSettingCard(modifier = Modifier.staggerIn(1, entrance)) {
                Column(Modifier.padding(20.dp).fillMaxWidth()) {
                    Text(
                        "Host server",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (busy && status == null) "Checking…" else (status?.label ?: "Checking…"),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        status?.detail ?: "Querying pulseaudio --check and pactl info…",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { loadStatus() },
                            enabled = !busy,
                            modifier = Modifier.weight(1f).height(48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = TextSecondary
                            )
                        ) {
                            Text("Refresh", fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = {
                                if (running) {
                                    runAction("Pulse restarted") { PulseHost.restart(it) }
                                } else {
                                    runAction("Pulse started") { PulseHost.start(it) }
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.weight(1f).height(48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = TextPrimary,
                                contentColor = AltarisBackground
                            )
                        ) {
                            Text(
                                if (running) "Restart" else "Start",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            GlassSettingCard(
                modifier = Modifier
                    .staggerIn(2, entrance)
                    .animateContentSize()
            ) {
                Column(Modifier.padding(20.dp).fillMaxWidth()) {
                    Text(
                        "Logs",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Supervisor and status output from this app (start_pulse_host.sh).",
                        fontSize = 13.sp,
                        color = altarisMutedText()
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showLogs = !showLogs },
                            modifier = Modifier.weight(1f).height(48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = TextSecondary
                            )
                        ) {
                            Text(if (showLogs) "Hide logs" else "View logs", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = {
                                val clip = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as ClipboardManager
                                clip.setPrimaryClip(
                                    ClipData.newPlainText("Pulse log", logText.ifBlank { "(empty)" })
                                )
                                Toast.makeText(context, "Log copied", Toast.LENGTH_SHORT).show()
                            },
                            enabled = logText.isNotBlank(),
                            modifier = Modifier.weight(1f).height(48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = TextSecondary
                            )
                        ) {
                            Text("Copy", fontWeight = FontWeight.Bold)
                        }
                    }
                    if (showLogs) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = logText.ifBlank { "(no log yet — tap Refresh or Start)" },
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextPrimary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 160.dp, max = 360.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(4.dp)
                        )
                    }
                }
            }

            GlassSettingCard(modifier = Modifier.staggerIn(3, entrance)) {
                Column(Modifier.padding(20.dp).fillMaxWidth()) {
                    Text(
                        "Guest clients",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Writes PULSE_SERVER=tcp:127.0.0.1 and installs pactl in already-installed PRoots. Chroots need root. Does not start a guest Pulse daemon.",
                        fontSize = 13.sp,
                        color = altarisMutedText()
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            runAction(
                                "Guest audio repaired",
                                PulseHost::repairToast
                            ) { PulseHost.repairGuests(it) }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = TextSecondary
                        )
                    ) {
                        Text("Repair guests", fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height(110.dp))
        }
    }
}
