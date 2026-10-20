package com.ivarna.fluxlinux.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivarna.fluxlinux.core.clone.CloneEngine
import com.ivarna.fluxlinux.ui.components.QCard
import com.ivarna.fluxlinux.ui.components.QEmptyState
import com.ivarna.fluxlinux.ui.components.QIconTile
import com.ivarna.fluxlinux.ui.components.QLogPane
import com.ivarna.fluxlinux.ui.components.QPrimaryButton
import com.ivarna.fluxlinux.ui.components.QRow
import com.ivarna.fluxlinux.ui.components.QScreenTitle
import com.ivarna.fluxlinux.ui.components.Quartz
import com.ivarna.fluxlinux.ui.theme.fluxMutedText
import com.ivarna.fluxlinux.ui.theme.rememberStaggerEntrance
import com.ivarna.fluxlinux.ui.theme.staggerIn
import kotlinx.coroutines.delay

/**
 * Apps tab — clones run inside Bliss through the in-app virtual app engine.
 * No Android user, no profile, no virtual display.
 */
@Composable
fun ClonedAppsScreen() {
    val apps by CloneEngine.apps.collectAsState()
    val status by CloneEngine.status.collectAsState()

    var selected by remember { mutableStateOf<CloneEngine.CloneApp?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { CloneEngine.refresh() }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(4000)
            notice = null
        }
    }

    val banner = notice ?: status
    val sel = selected
    if (sel == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Quartz.ScreenPad, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(Quartz.Gap),
        ) {
            val entrance = rememberStaggerEntrance()
            QScreenTitle(
                "Apps",
                "Cloned apps run inside Bliss — no extra user or profile.",
                modifier = Modifier.staggerIn(0, entrance),
            )

            if (banner != null) {
                Text(banner, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }

            QCard(modifier = Modifier.staggerIn(1, entrance)) {
                if (apps.isEmpty()) {
                    QEmptyState(
                        "No cloned apps yet",
                        "Add one in Tools → Clone apps (pick an APK, paste a link, or clone from this phone).",
                    )
                } else {
                    apps.forEach { app ->
                        QRow(
                            title = app.label,
                            subtitle = if (app.version.isBlank()) app.packageName
                            else "${app.packageName} · v${app.version}",
                            leading = { QIconTile(bitmap = app.icon, size = 34.dp) },
                            trailing = {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = fluxMutedText(),
                                    modifier = Modifier.size(17.dp),
                                )
                            },
                            onClick = { selected = app },
                        )
                    }
                }
            }

            QCard(modifier = Modifier.staggerIn(2, entrance)) {
                Column(
                    modifier = Modifier.padding(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "In-app engine",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    Text(
                        "Cloned apps are installed inside Bliss with their own data. " +
                            "Opening one shows it in this app; your phone's launcher and users are untouched.",
                        fontSize = 11.sp,
                        color = fluxMutedText(),
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(96.dp))
        }
    } else {
        AppDetail(
            app = sel,
            onBack = { selected = null },
            onNotice = { notice = it },
        )
    }
}

@Composable
private fun AppDetail(
    app: CloneEngine.CloneApp,
    onBack: () -> Unit,
    onNotice: (String) -> Unit,
) {
    val logs by CloneEngine.logs.collectAsState()
    var confirmUninstall by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Quartz.ScreenPad, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(Quartz.Gap),
    ) {
        QRow(
            title = app.label,
            subtitle = app.packageName + if (app.version.isBlank()) "" else " · v${app.version}",
            leading = { QIconTile(bitmap = app.icon, size = 42.dp) },
            trailing = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = fluxMutedText(),
                    )
                }
            },
        )

        QCard {
            Column(
                modifier = Modifier.padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QPrimaryButton("Open inside Bliss") {
                    CloneEngine.launch(app.packageName)
                    onNotice("Starting ${app.label}…")
                }
                Text(
                    "Runs in the in-app engine, on this screen. No separate Android user or profile.",
                    fontSize = 11.sp,
                    color = fluxMutedText(),
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }

        QCard {
            Column(
                modifier = Modifier.padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QPrimaryButton("Stop app") {
                    CloneEngine.kill(app.packageName)
                    onNotice("Stopping ${app.label}…")
                }
                QPrimaryButton(
                    if (confirmUninstall) "Tap again to uninstall" else "Uninstall",
                ) {
                    if (!confirmUninstall) {
                        confirmUninstall = true
                    } else {
                        CloneEngine.uninstall(app.packageName)
                        onNotice("Uninstalling ${app.label}…")
                        onBack()
                    }
                }
                if (confirmUninstall) {
                    Text(
                        "This removes the cloned copy and its data from the in-app engine. " +
                            "The app on your phone is not affected.",
                        fontSize = 11.sp,
                        color = fluxMutedText(),
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }

        QCard {
            Column(
                modifier = Modifier.padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "Recent activity",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = Quartz.CardPad),
                )
                QLogPane(lines = logs.take(60), maxHeight = 180.dp)
            }
        }
        Spacer(Modifier.height(80.dp))
    }
}
