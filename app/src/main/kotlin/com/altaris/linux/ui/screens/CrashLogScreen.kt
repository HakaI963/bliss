package com.altaris.linux.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altaris.linux.core.system.CrashLog
import com.altaris.linux.ui.components.FrostContainer
import com.altaris.linux.ui.components.FrostDivider
import com.altaris.linux.ui.components.FrostGroupTitle
import com.altaris.linux.ui.components.FrostRow
import com.altaris.linux.ui.theme.TextMuted
import com.altaris.linux.ui.theme.TextPrimary
import com.altaris.linux.ui.theme.rememberStaggerEntrance
import com.altaris.linux.ui.theme.staggerIn
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash logs viewer (Settings -> Crash logs).
 *
 * List mode shows every report written to `filesDir/crashlog` by [CrashLog]
 * (time, file name, size). Tapping a row opens the raw trace in a monospace,
 * scrollable, selectable view with a copy button; the top bar can delete all
 * reports. Reports are only ever written by the uncaught-exception handler.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrashLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var files by remember { mutableStateOf(CrashLog.files(context)) }
    var openFile by remember { mutableStateOf<File?>(null) }
    val open = openFile

    // Staggered list entrance (30ms steps) — visual only.
    val entrance = rememberStaggerEntrance()

    // Detail first closes the trace; the parent BackHandler leaves the screen.
    BackHandler(enabled = open != null) { openFile = null }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            com.altaris.linux.ui.components.AltarisSubHeader(
                title = if (open == null) "Crash logs" else prettyTime(open),
                onBack = { if (open != null) openFile = null else onBack() },
                actions = {
                    if (open == null && files.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                CrashLog.deleteAll(context)
                                files = CrashLog.files(context)
                                Toast.makeText(context, "Crash logs deleted", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete all crash logs",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(19.dp),
                            )
                        }
                    }
                },
            )
        }
    ) { innerPadding ->
        if (open == null) {
            if (files.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.staggerIn(0, entrance)
                    ) {
                        Icon(
                            imageVector = Icons.Default.BugReport,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "No crash logs",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextMuted
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Altaris saves a report here if it ever crashes.",
                            fontSize = 12.sp,
                            color = TextMuted
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    // 110dp keeps the last row clear of the floating bottom dock
                    // (same reserve as Home/Distro/Settings content).
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = 4.dp, bottom = 110.dp
                    )
                ) {
                    item { FrostGroupTitle("Saved reports", Modifier.staggerIn(0, entrance)) }
                    item {
                        FrostContainer(modifier = Modifier.staggerIn(1, entrance)) {
                            files.forEachIndexed { index, file ->
                                FrostRow(
                                    icon = Icons.Default.BugReport,
                                    title = prettyTime(file),
                                    subtitle = "${file.name} · ${prettySize(file.length())}",
                                    onClick = { openFile = file }
                                )
                                if (index < files.lastIndex) FrostDivider()
                            }
                        }
                    }
                    item {
                        Text(
                            text = "Reports are stored in the app's private crashlog/ folder. " +
                                "Copy a trace and share it with the developer.",
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = TextMuted,
                            modifier = Modifier
                                .staggerIn(2, entrance)
                                .padding(start = 4.dp, top = 12.dp, end = 4.dp)
                        )
                    }
                }
            }
        } else {
            val trace = remember(open) { CrashLog.read(open) }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(12.dp)
            ) {
                SelectionContainer(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    Text(
                        text = trace,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = TextPrimary,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    )
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { copyTrace(context, trace) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Copy trace", fontWeight = FontWeight.SemiBold, color = TextPrimary)
                }
            }
        }
    }
}

private fun copyTrace(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("Altaris crash log", text))
    Toast.makeText(context, "Crash log copied", Toast.LENGTH_SHORT).show()
}

private fun prettyTime(file: File): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(file.lastModified()))

private fun prettySize(bytes: Long): String = when {
    bytes >= 1_048_576 -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
