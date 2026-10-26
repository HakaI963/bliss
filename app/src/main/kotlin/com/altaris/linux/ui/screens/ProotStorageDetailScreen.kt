package com.altaris.linux.ui.screens

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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altaris.linux.ui.components.AltarisSubHeader
import com.altaris.linux.core.chroot.GuestStorageCatalog
import com.altaris.linux.core.data.DistroRepository
import com.altaris.linux.core.proot.ProotInfoStore
import com.altaris.linux.core.proot.ProotSettingsModel
import com.altaris.linux.ui.components.FrostContainer
import com.altaris.linux.ui.components.FrostDivider
import com.altaris.linux.ui.components.FrostGroupTitle
import com.altaris.linux.ui.components.FrostPill
import com.altaris.linux.ui.screens.storage.FrostNote
import com.altaris.linux.ui.screens.storage.MetaRow
import com.altaris.linux.ui.screens.storage.StatusBadge
import com.altaris.linux.ui.theme.TextMuted
import com.altaris.linux.ui.theme.TextPrimary
import com.altaris.linux.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Parameterized detail screen for PRoot container storage (individual or universal).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProotStorageDetailScreen(
    distroId: String,
    onBack: () -> Unit,
    onNavigateToInstall: (() -> Unit)? = null
) {
    val isUniversal = distroId == GuestStorageCatalog.ALL_PROOT_ID
    val distro = if (!isUniversal) {
        DistroRepository.cardFor(distroId)
    } else null

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val containerPath = if (!isUniversal) {
        GuestStorageCatalog.prootContainerPath(context, distroId)
    } else {
        "${context.filesDir.absolutePath}/usr/var/lib/proot-distro/containers"
    }

    if (!isUniversal && (distro == null || containerPath == null)) {
        LaunchedEffect(distroId) {
            onBack()
        }
        return
    }

    val installableIds = remember { GuestStorageCatalog.installableProots().map { it.id } }

    val initialSizeUi = remember(distroId) {
        if (isUniversal) {
            val cachedBytes = ProotInfoStore.cachedBytes(context, GuestStorageCatalog.ALL_PROOT_ID)
            ProotSettingsModel.SizeUi(
                installed = true,
                dirExists = true,
                bytes = cachedBytes,
                dimmedCache = cachedBytes != null,
                hint = "Tap refresh to measure"
            )
        } else {
            ProotSettingsModel.loadCached(context, distroId)
        }
    }

    var sizeUi by remember { mutableStateOf(initialSizeUi) }
    var busy by remember { mutableStateOf(false) }

    fun refreshSize() {
        if (busy) return
        busy = true
        sizeUi = sizeUi.copy(measuring = true, hint = "Measuring container storage…")
        scope.launch {
            val s = withContext(Dispatchers.IO) {
                if (isUniversal) {
                    ProotSettingsModel.refreshUniversal(context, installableIds)
                } else {
                    ProotSettingsModel.refreshSize(context, distroId)
                }
            }
            sizeUi = s
            busy = false
        }
    }

    LaunchedEffect(distroId) {
        refreshSize()
    }

    val pageTitle = if (isUniversal) "All containers" else distro!!.name
    val pageSubtitle = if (isUniversal) {
        "// Userspace Linux — inside app storage"
    } else {
        "// Userspace ${distro!!.name} — inside app storage"
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AltarisSubHeader(
                title = pageTitle,
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
                pageSubtitle,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = TextMuted
            )

            FrostContainer {
                FrostNote("PRoot lives in app storage (container + rootfs) and is removed if you uninstall the app.")
            }

            FrostGroupTitle("Container storage")
            FrostContainer {
                MetaRow("Status") {
                    val statusText = when {
                        isUniversal -> if (sizeUi.installed) "ACTIVE" else "READY"
                        sizeUi.installed -> "INSTALLED"
                        sizeUi.dirExists -> "PRESENT"
                        else -> "NOT INSTALLED"
                    }
                    StatusBadge(
                        text = statusText,
                        ok = sizeUi.installed
                    )
                }

                FrostDivider()
                // Size block
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 12.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "PROOT STORAGE",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = TextSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { refreshSize() },
                            enabled = !busy
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Refresh size",
                                tint = TextSecondary
                            )
                        }
                    }
                    if (sizeUi.measuring || busy && sizeUi.bytes == null) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = TextSecondary,
                            trackColor = TextSecondary.copy(alpha = 0.2f)
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    val (v, u) = ProotInfoStore.formatStorageBytes(sizeUi.bytes)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            v,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            color = TextPrimary,
                            modifier = Modifier.alpha(if (sizeUi.dimmedCache) 0.7f else 1f)
                        )
                        if (u.isNotEmpty()) {
                            Text(
                                " $u",
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextSecondary,
                                modifier = Modifier.padding(bottom = 2.dp)
                            )
                        }
                    }
                    Text(
                        sizeUi.hint,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextMuted
                    )
                }

                FrostDivider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 12.dp)
                ) {
                    Text(
                        "CONTAINER PATH",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        containerPath ?: "",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextSecondary
                    )
                }

                if (!isUniversal && !sizeUi.installed && onNavigateToInstall != null) {
                    FrostDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        FrostPill(
                            text = "Install PRoot distro",
                            onClick = { onNavigateToInstall() },
                            primary = true
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                FrostPill(
                    text = if (busy) "Refreshing…" else "Refresh size",
                    onClick = { refreshSize() },
                    modifier = Modifier.alpha(if (busy) 0.5f else 1f)
                )
            }

            Spacer(Modifier.height(110.dp))
        }
    }
}
