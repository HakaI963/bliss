package com.ivarna.fluxlinux.ui.screens

import android.widget.Toast
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.ivarna.fluxlinux.core.chroot.ChrootInfoStore
import com.ivarna.fluxlinux.core.chroot.ChrootKillCoordinator
import com.ivarna.fluxlinux.core.chroot.ChrootSettingsModel
import com.ivarna.fluxlinux.core.chroot.GuestStorageCatalog
import com.ivarna.fluxlinux.core.data.DistroRepository
import com.ivarna.fluxlinux.core.root.ChrootPaths
import com.ivarna.fluxlinux.ui.components.FrostContainer
import com.ivarna.fluxlinux.ui.components.FrostDivider
import com.ivarna.fluxlinux.ui.components.FrostGroupTitle
import com.ivarna.fluxlinux.ui.components.FrostPill
import com.ivarna.fluxlinux.ui.screens.storage.DestructivePill
import com.ivarna.fluxlinux.ui.screens.storage.FrostNote
import com.ivarna.fluxlinux.ui.screens.storage.MetaRow
import com.ivarna.fluxlinux.ui.screens.storage.StatusBadge
import com.ivarna.fluxlinux.ui.theme.TextMuted
import com.ivarna.fluxlinux.ui.theme.TextPrimary
import com.ivarna.fluxlinux.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Parameterized detail screen for a single chroot distribution or "__all_chroot__" aggregate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChrootStorageDetailScreen(
    distroId: String,
    onBack: () -> Unit,
    onNavigateToInstall: (() -> Unit)? = null
) {
    val isUniversal = distroId == GuestStorageCatalog.ALL_CHROOT_ID
    val distro = if (!isUniversal) {
        DistroRepository.supportedDistros.firstOrNull { it.id == distroId }
    } else null

    val resolvedPath = if (!isUniversal) {
        GuestStorageCatalog.chrootPathOrNull(distroId)
    } else null

    // If target distro disappeared or invalid id, pop to list
    if (!isUniversal && (distro == null || resolvedPath == null)) {
        LaunchedEffect(distroId) {
            onBack()
        }
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val path = resolvedPath ?: ChrootPaths.CHROOT_PATH
    val allPaths = remember { GuestStorageCatalog.KNOWN_CHROOT_PATHS.toList().sorted() }
    val installableIds = remember { GuestStorageCatalog.installableChroots().map { it.id } }

    val initialPage = remember(distroId) {
        if (isUniversal) {
            val cachedBytes = ChrootInfoStore.cachedBytes(context, GuestStorageCatalog.ALL_CHROOT_ID)
            val cachedProc = ChrootInfoStore.cachedProcCount(context, GuestStorageCatalog.ALL_CHROOT_ID)
            ChrootSettingsModel.PageSnapshot(
                size = ChrootSettingsModel.SizeUi(
                    rootOk = true,
                    installed = true,
                    markerOk = true,
                    dirExists = true,
                    bytes = cachedBytes,
                    dimmedCache = cachedBytes != null,
                    hint = "Tap refresh to measure"
                ),
                proc = ChrootSettingsModel.ProcUi(
                    rootOk = true,
                    count = cachedProc,
                    processes = emptyList(),
                    hint = if (cachedProc >= 0) "Cached proc count" else "Tap scan for all chroot processes"
                )
            )
        } else {
            ChrootSettingsModel.loadCached(context, distroId = distroId, path = path)
        }
    }

    var sizeUi by remember { mutableStateOf(initialPage.size) }
    var procUi by remember { mutableStateOf(initialPage.proc) }
    var busy by remember { mutableStateOf(false) }
    var showKillConfirm by remember { mutableStateOf(false) }
    val killCoordinator = remember { ChrootKillCoordinator() }
    var killProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    fun refreshAll(force: Boolean = true) {
        if (busy || killCoordinator.isBusy) return
        busy = true
        sizeUi = sizeUi.copy(measuring = true, hint = "Checking root · measuring rootfs…")
        procUi = procUi.copy(scanning = true, hint = "Scanning /proc across roots…")
        scope.launch {
            try {
                val snap = withContext(Dispatchers.IO) {
                    if (isUniversal) {
                        val p = ChrootSettingsModel.refreshUniversal(context, installableIds)
                        val proc = ChrootSettingsModel.scanAllProcesses(context, allPaths)
                        ChrootSettingsModel.PageSnapshot(p.size, proc)
                    } else {
                        ChrootSettingsModel.refreshPage(context, distroId = distroId, path = path, force = force)
                    }
                }
                sizeUi = snap.size
                procUi = snap.proc
            } finally {
                busy = false
            }
        }
    }

    fun refreshSizeOnly() {
        if (busy || killCoordinator.isBusy) return
        busy = true
        sizeUi = sizeUi.copy(measuring = true, hint = "Checking root · measuring rootfs…")
        scope.launch {
            try {
                val s = withContext(Dispatchers.IO) {
                    if (isUniversal) {
                        ChrootSettingsModel.refreshUniversal(context, installableIds).size
                    } else {
                        ChrootSettingsModel.refreshSize(context, distroId = distroId, path = path, forceClearSu = true)
                    }
                }
                sizeUi = s
            } finally {
                busy = false
            }
        }
    }

    fun scanProcs() {
        if (busy || killCoordinator.isBusy) return
        busy = true
        procUi = procUi.copy(scanning = true, hint = "Scanning /proc for chroot root…")
        scope.launch {
            try {
                val p = withContext(Dispatchers.IO) {
                    if (isUniversal) {
                        ChrootSettingsModel.scanAllProcesses(context, allPaths)
                    } else {
                        ChrootSettingsModel.refreshProcesses(context, distroId = distroId, path = path, forceClearSu = true)
                    }
                }
                procUi = p
            } finally {
                busy = false
            }
        }
    }

    fun killAll() {
        val session = killCoordinator.startSession() ?: return
        busy = true
        killProgress = null
        procUi = procUi.copy(killing = true, scanning = true, hint = "Sending SIGKILL…")
        scope.launch {
            try {
                val p = withContext(Dispatchers.IO) {
                    if (isUniversal) {
                        ChrootSettingsModel.killAllProcesses(
                            context,
                            paths = allPaths,
                            isCancelled = { session.isCancelled },
                            onProgress = { cur, tot ->
                                killProgress = cur to tot
                            }
                        )
                    } else {
                        ChrootSettingsModel.killAllProcesses(context, path = path)
                    }
                }
                procUi = p
                Toast.makeText(
                    context,
                    p.statusLine ?: p.hint,
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                killCoordinator.endSession(session)
                busy = false
                killProgress = null
            }
        }
    }

    fun cancelKill() {
        if (killCoordinator.requestCancel()) {
            procUi = procUi.copy(hint = "Cancelling kill… waiting for current path")
        }
    }

    LaunchedEffect(distroId) {
        refreshAll(force = false)
    }

    val pageTitle = if (isUniversal) "All chroots" else distro!!.name
    val pageSubtitle = if (isUniversal) {
        "// Root-level Linux — outside app storage"
    } else {
        "// Root-level ${distro!!.name} — outside app storage"
    }

    if (showKillConfirm) {
        AlertDialog(
            onDismissRequest = { showKillConfirm = false },
            title = { Text(if (isUniversal) "Kill all chroot processes?" else "Kill ${distro?.name ?: "chroot"} processes?") },
            text = {
                if (isUniversal) {
                    Text(
                        ChrootSettingsModel.confirmKillCopy(allPaths) +
                            if (procUi.count > 0) "\n\nDetected: ${procUi.count} process(es)." else ""
                    )
                } else {
                    Text(
                        "Sends SIGKILL to every process whose root is $path.\n\n" +
                            "Open chroot shells and guest daemons will die. Host Android processes " +
                            "are not targeted.\n\nRootfs and mounts stay." +
                            if (procUi.count > 0) "\n\nDetected: ${procUi.count} process(es)." else ""
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showKillConfirm = false
                        killAll()
                    }
                ) { Text("Kill all", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showKillConfirm = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        pageTitle,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent
                )
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
                FrostNote("Rootfs is not removed when you uninstall the app. Free space here.")
            }

            // ── Storage group ─────────────────────────────────────────────
            FrostGroupTitle("Storage")
            FrostContainer {
                MetaRow("Status") {
                    val installedCount = remember(sizeUi) {
                        GuestStorageCatalog.installableChroots().count {
                            ChrootInfoStore.cachedInstalled(context, it.id)
                        }
                    }
                    val statusText = if (isUniversal) {
                        "$installedCount INSTALLED"
                    } else {
                        ChrootSettingsModel.resolveStatus(
                            installed = sizeUi.installed,
                            dirExists = sizeUi.dirExists
                        )
                    }
                    StatusBadge(
                        text = statusText,
                        ok = if (isUniversal) installedCount > 0 else sizeUi.installed
                    )
                }
                if (sizeUi.rootOk || sizeUi.measuring) {
                    FrostDivider()
                    MetaRow("Root access") {
                        StatusBadge(
                            text = when {
                                sizeUi.measuring -> "CHECKING"
                                sizeUi.rootOk -> "GRANTED"
                                else -> "DENIED"
                            },
                            ok = sizeUi.rootOk && !sizeUi.measuring
                        )
                    }
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
                            "LINUX STORAGE",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = TextSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { refreshSizeOnly() },
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
                    val (v, u) = ChrootInfoStore.formatStorageBytes(sizeUi.bytes)
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
                        if (isUniversal) "HOST PATHS" else "HOST PATH",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(4.dp))
                    if (isUniversal) {
                        allPaths.forEach { p ->
                            Text(
                                "• $p",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextMuted
                            )
                        }
                    } else {
                        Text(
                            path,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextSecondary
                        )
                    }
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
                            text = "Install chroot",
                            onClick = { onNavigateToInstall() },
                            primary = true
                        )
                    }
                }
            }

            // ── Processes group ───────────────────────────────────────────
            FrostGroupTitle("Processes")
            FrostContainer {
                FrostNote(
                    if (isUniversal) {
                        "Orphans survive app close across all roots. Kill before uninstall if stuck."
                    } else {
                        "Orphans survive app close (unlike proot). Kill before uninstall if stuck."
                    }
                )

                FrostDivider()
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
                            "CHROOT PROCESSES",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = TextSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { scanProcs() }, enabled = !busy) {
                            Text("Scan", color = TextSecondary, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (procUi.scanning || procUi.killing) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = TextSecondary,
                            trackColor = TextSecondary.copy(alpha = 0.2f)
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            if (procUi.count < 0) "—" else procUi.count.toString(),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            color = TextPrimary
                        )
                        Text(
                            " running",
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextSecondary,
                            modifier = Modifier.padding(bottom = 2.dp, start = 6.dp)
                        )
                    }
                    Text(
                        if (isUniversal) "roots=${allPaths.size} known · scan all catalog paths" else "root=$path",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextMuted
                    )
                    Text(
                        procUi.hint,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextMuted,
                        modifier = Modifier.padding(top = 4.dp)
                    )

                    if (procUi.processes.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "SAMPLE",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = TextSecondary
                        )
                        procUi.processes.take(5).forEach { p ->
                            Text(
                                "${p.pid}  ${p.comm}",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextSecondary
                            )
                        }
                        val more = procUi.processes.size - 5
                        if (more > 0) {
                            Text(
                                "+$more more",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextMuted
                            )
                        }
                    }
                }

                FrostDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isOperationBusy = busy || killCoordinator.isBusy
                    val killEnabled = ChrootSettingsModel.isKillEnabled(
                        rootOk = procUi.rootOk,
                        busy = isOperationBusy
                    )
                    val killText = if (killProgress != null) {
                        "Killing (${killProgress!!.first}/${killProgress!!.second})…"
                    } else if (procUi.killing) {
                        "Killing…"
                    } else if (isUniversal) {
                        "Kill all across roots"
                    } else {
                        "Kill all chroot processes"
                    }
                    DestructivePill(
                        text = killText,
                        onClick = { showKillConfirm = true },
                        enabled = killEnabled
                    )
                    if (procUi.killing && isUniversal) {
                        val isCancelling =
                            killCoordinator.state == ChrootKillCoordinator.State.CANCEL_REQUESTED
                        FrostPill(
                            text = if (isCancelling) "Cancelling…" else "Cancel",
                            onClick = { cancelKill() },
                            modifier = Modifier.alpha(if (isCancelling) 0.5f else 1f)
                        )
                    }
                }

                procUi.statusLine?.let {
                    FrostNote(it)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                FrostPill(
                    text = if (busy) "Refreshing…" else "Refresh all",
                    onClick = { refreshAll(force = true) },
                    modifier = Modifier.alpha(if (busy) 0.5f else 1f)
                )
            }

            Spacer(Modifier.height(110.dp))
        }
    }
}
