package com.altaris.linux.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.altaris.linux.core.chroot.ChrootDetection
import com.altaris.linux.core.chroot.ChrootInfoStore
import com.altaris.linux.core.chroot.ChrootSettingsModel
import com.altaris.linux.core.chroot.GuestStorageCatalog
import com.altaris.linux.core.data.Distro
import com.altaris.linux.core.data.DistroRepository
import com.altaris.linux.core.utils.StateManager
import com.altaris.linux.ui.components.FrostContainer
import com.altaris.linux.ui.components.FrostDivider
import com.altaris.linux.ui.components.FrostGroupTitle
import com.altaris.linux.ui.components.FrostPill
import com.altaris.linux.ui.components.FrostRow
import com.altaris.linux.ui.screens.storage.FrostNote
import com.altaris.linux.ui.screens.storage.StatusBadge
import com.altaris.linux.ui.theme.TextMuted
import com.altaris.linux.ui.theme.TextPrimary
import com.altaris.linux.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Chroot storage list screen: renders all chroots aggregate row and installed chroots.
 * Two-phase loading: initial paint from cache/TTL, followed by sequential IO Job.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChrootStorageListScreen(
    onBack: () -> Unit,
    onSelectDistro: (distroId: String) -> Unit,
    onNavigateToDistros: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val stateRefresh by StateManager.refreshTrigger.collectAsState()
    var lifecycleRefreshKey by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                lifecycleRefreshKey++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val catalogDistros = remember { GuestStorageCatalog.installableChroots() }
    val sortedCatalog = remember(catalogDistros) {
        DistroRepository.sortForDistroPage(catalogDistros)
    }

    // Dynamic installed state and live sizes map
    val liveSizes = remember { mutableStateMapOf<String, Long?>() }
    val installedSet = remember { mutableStateMapOf<String, Boolean>() }
    var liveMeasuring by remember { mutableStateOf(false) }

    // Phase 1 cache read
    val initialRows = remember(lifecycleRefreshKey, stateRefresh) {
        sortedCatalog.filter { d ->
            val path = GuestStorageCatalog.chrootPathOrNull(d.id) ?: return@filter false
            ChrootDetection.isInstalled(path) || ChrootInfoStore.cachedInstalled(context, d.id)
        }
    }

    // Initialize map from cache if not already populated
    LaunchedEffect(initialRows) {
        initialRows.forEach { d ->
            installedSet[d.id] = true
            if (!liveSizes.containsKey(d.id)) {
                liveSizes[d.id] = ChrootInfoStore.cachedBytes(context, d.id)
            }
        }
    }

    // Phase 2 sequential IO Job
    fun runSequentialProbe() {
        if (liveMeasuring) return
        liveMeasuring = true
        scope.launch {
            val results = withContext(Dispatchers.IO) {
                val ids = sortedCatalog.map { it.id }
                ChrootSettingsModel.refreshInstalledSizes(context, ids, forceClearSuOnce = true)
            }
            installedSet.clear()
            results.forEach { (id, sizeUi) ->
                if (sizeUi.installed) {
                    installedSet[id] = true
                    liveSizes[id] = sizeUi.bytes
                } else {
                    installedSet[id] = false
                    liveSizes.remove(id)
                }
            }
            liveMeasuring = false
        }
    }

    LaunchedEffect(lifecycleRefreshKey, stateRefresh) {
        runSequentialProbe()
    }

    val installedDistros = sortedCatalog.filter { d ->
        installedSet[d.id] == true || (installedSet[d.id] == null && initialRows.any { it.id == d.id })
    }

    val totalBytes: Long? = run {
        val measured = installedDistros.mapNotNull { d -> liveSizes[d.id] ?: ChrootInfoStore.cachedBytes(context, d.id) }
        if (measured.isEmpty()) null else measured.sum()
    }

    val totalProcCount: Int = run {
        val cached = ChrootInfoStore.cachedProcCount(context, GuestStorageCatalog.ALL_CHROOT_ID)
        if (cached >= 0) {
            cached
        } else {
            val perIdCounts = installedDistros.map { d -> ChrootInfoStore.cachedProcCount(context, d.id) }
            if (perIdCounts.any { it >= 0 }) {
                perIdCounts.filter { it >= 0 }.sum()
            } else {
                -1
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Chroot",
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
                "// Root-level Linux — outside app storage",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = TextMuted
            )

            FrostContainer {
                FrostNote("Rootfs is not removed when you uninstall the app. Free space here.")
            }

            // Universal row (always present)
            FrostContainer {
                val (sizeVal, sizeUnit) = ChrootInfoStore.formatStorageBytes(totalBytes)
                val sizeStr = if (sizeVal == "—") "—" else "$sizeVal $sizeUnit"
                val procStr = if (totalProcCount >= 0) "$totalProcCount running" else "— running"
                FrostRow(
                    icon = Icons.Default.Storage,
                    title = "All chroots",
                    subtitle = "${installedDistros.size} installed · $sizeStr · $procStr",
                    onClick = { onSelectDistro(GuestStorageCatalog.ALL_CHROOT_ID) }
                )
            }

            // Installed rows or empty CTA
            if (installedDistros.isNotEmpty()) {
                FrostGroupTitle("Installed roots")
                FrostContainer {
                    installedDistros.forEachIndexed { index, distro ->
                        if (index > 0) FrostDivider()
                        InstalledChrootRow(
                            distro = distro,
                            bytes = liveSizes[distro.id] ?: ChrootInfoStore.cachedBytes(context, distro.id),
                            onClick = { onSelectDistro(distro.id) }
                        )
                    }
                }
            } else {
                FrostContainer {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "No chroot installed",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextPrimary
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Root-level Linux lives outside app storage.\nSwitch to the Chroot tab.",
                            fontSize = 12.sp,
                            color = TextMuted,
                            lineHeight = 16.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(14.dp))
                        FrostPill(
                            text = "Install from Distros",
                            onClick = onNavigateToDistros,
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
                    text = if (liveMeasuring) "Refreshing…" else "Refresh sizes",
                    onClick = { runSequentialProbe() },
                    modifier = Modifier.alpha(if (liveMeasuring) 0.5f else 1f)
                )
            }

            Spacer(Modifier.height(110.dp))
        }
    }
}

@Composable
private fun InstalledChrootRow(
    distro: Distro,
    bytes: Long?,
    onClick: () -> Unit
) {
    val path = GuestStorageCatalog.chrootPathOrNull(distro.id) ?: ""
    val (v, u) = ChrootInfoStore.formatStorageBytes(bytes)
    val sizeStr = if (v == "—") "—" else "$v $u"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    distro.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                StatusBadge(text = "INSTALLED", ok = true)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                sizeStr,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = TextSecondary
            )
            Text(
                path,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = TextMuted,
                maxLines = 1
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = "View detail",
            tint = TextMuted
        )
    }
}
