package com.ivarna.fluxlinux.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivarna.fluxlinux.core.data.Distro
import com.ivarna.fluxlinux.core.data.DistroComponent
import com.ivarna.fluxlinux.core.gpu.DriverRelease
import com.ivarna.fluxlinux.core.gpu.GpuDriverInstaller
import com.ivarna.fluxlinux.core.gpu.PanvkInstaller
import com.ivarna.fluxlinux.core.terminal.GpuAccelDetector
import com.ivarna.fluxlinux.core.theme.AppearanceStore
import com.ivarna.fluxlinux.core.utils.InstallationQueueManager
import com.ivarna.fluxlinux.core.utils.StateManager
import com.ivarna.fluxlinux.ui.components.FrostContainer
import com.ivarna.fluxlinux.ui.components.FrostDivider
import com.ivarna.fluxlinux.ui.components.FrostGroupTitle
import com.ivarna.fluxlinux.ui.components.FrostPill
import com.ivarna.fluxlinux.ui.components.GlassScaffold
import com.ivarna.fluxlinux.ui.theme.FolioDanger
import com.ivarna.fluxlinux.ui.theme.TextMuted
import com.ivarna.fluxlinux.ui.theme.TextPrimary
import com.ivarna.fluxlinux.ui.theme.TextSecondary
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

private val desktopHardwareIds = setOf(
    "xfce4_desktop",
    "customization",
    "kde_plasma",
    "kde_customization",
    "hw_accel"
)

private fun isUninstallable(c: DistroComponent): Boolean {
    if (c.isMandatory || c.comingSoon) return false
    if (c.id == "xfce4_desktop") return false
    if (c.id == "customization" && c.scriptName.contains("termux")) return false
    if (c.id == "kde_customization" && c.scriptName.contains("termux")) return false
    return true
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class)
@Composable
fun DistroSettingsScreen(
    distro: Distro,
    onBack: () -> Unit,
    onInstallComponent: (DistroComponent, Map<String, String>) -> Unit,
    onUninstallComponent: (DistroComponent) -> Unit = {},
    onUninstallDistro: () -> Unit,
    onReinstallDistro: () -> Unit,
    onNavigateToStart: (() -> Unit)? = null,
    onStartActivity: (android.content.Intent) -> Unit,
    onLaunchXfce: (() -> Unit)? = null,
    onStopXfce: (() -> Unit)? = null,
    onLaunchKde: (() -> Unit)? = null,
    onStopKde: (() -> Unit)? = null,
    onLaunchCli: (() -> Unit)? = null,
    hazeState: HazeState
) {
    @Suppress("UNUSED_PARAMETER")
    val _keep1 = onNavigateToStart
    @Suppress("UNUSED_PARAMETER")
    val _keep2 = onStartActivity
    @Suppress("UNUSED_PARAMETER")
    val _keep3 = onLaunchXfce
    @Suppress("UNUSED_PARAMETER")
    val _keep4 = onStopXfce
    @Suppress("UNUSED_PARAMETER")
    val _keep5 = onLaunchKde
    @Suppress("UNUSED_PARAMETER")
    val _keep6 = onStopKde
    @Suppress("UNUSED_PARAMETER")
    val _keep7 = onLaunchCli

    val context = LocalContext.current
    val installState by InstallationQueueManager.installState.collectAsState()
    val refreshKey by StateManager.refreshTrigger.collectAsState()
    var showUninstallDialog by remember { mutableStateOf(false) }
    var pendingUninstallComponent by remember { mutableStateOf<DistroComponent?>(null) }

    // Honest re-run subtitle: the base reinstall follows whichever desktop this
    // distro actually carries (XFCE4 / KDE Plasma), not a hardcoded "XFCE".
    val reinstallSubtitle = remember(distro.id, refreshKey) {
        when {
            StateManager.isComponentInstalled(context, distro.id, "kde_plasma") ->
                "Rootfs + KDE Plasma + customization"
            StateManager.isComponentInstalled(context, distro.id, "xfce4_desktop") ->
                "Rootfs + XFCE + customization"
            else -> "Rootfs + desktop + customization"
        }
    }

    var showThemeSheet by remember { mutableStateOf(false) }
    var showGpuSheet by remember { mutableStateOf(false) }
    var activeComponent by remember { mutableStateOf<DistroComponent?>(null) }

    // Local appearance-editor flag: this file owns the whole flow (no routes).
    var showAppearanceEditor by remember { mutableStateOf(false) }
    // Bumped when the editor closes so the row summary re-reads the saved picks.
    var appearanceRefresh by remember { mutableStateOf(0) }

    // Summary of the saved Folio Linux appearance for this distro.
    val appearanceSummary = remember(distro.id, refreshKey, appearanceRefresh) {
        AppearanceStore.summary(context, distro.id)
    }

    var selectedTheme by remember { mutableStateOf("dark") }
    var selectedGpu by remember { mutableStateOf("auto") }

    var expandedIds by remember { mutableStateOf(setOf<String>()) }

    val themeSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val gpuSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(Unit) {
        if (!InstallationQueueManager.hasPending() && InstallationQueueManager.currentTask == null) {
            InstallationQueueManager.clear()
        }
    }

    // Group components: Desktop & hardware vs Optional. Backend unchanged —
    // only the visual grouping differs.
    val (desktopHardware, optional) = remember(distro) {
        distro.components.partition { it.id in desktopHardwareIds || it.isMandatory }
    }

    fun handleComponentAction(component: DistroComponent) {
        when (component.id) {
            "customization" -> {
                activeComponent = component
                showThemeSheet = true
            }
            "hw_accel" -> {
                activeComponent = component
                showGpuSheet = true
            }
            "kde_customization" -> {
                activeComponent = component
                showThemeSheet = true
            }
            else -> onInstallComponent(component, emptyMap())
        }
    }

    // Appearance editor replaces the whole screen content (local state only).
    if (showAppearanceEditor) {
        DistroAppearanceScreen(
            distro = distro,
            hazeState = hazeState,
            onBack = {
                showAppearanceEditor = false
                appearanceRefresh++
            }
        )
        return
    }

    GlassScaffold(
        hazeState = hazeState,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Manage ${distro.name}",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent
                ),
                modifier = Modifier.hazeChild(state = hazeState, shape = androidx.compose.ui.graphics.RectangleShape, style = HazeMaterials.thin())
            )
        },
        content = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(top = 16.dp, bottom = 48.dp)
            ) {
                // Compact distro header
                item {
                    FrostContainer {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (distro.iconRes != null) {
                                    Icon(
                                        painter = androidx.compose.ui.res.painterResource(id = distro.iconRes),
                                        contentDescription = null,
                                        tint = Color.Unspecified,
                                        modifier = Modifier.size(30.dp)
                                    )
                                } else {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                                }
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    distro.name,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(TextPrimary)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "Installed & ready",
                                        fontSize = 12.sp,
                                        color = TextMuted
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // ─── Actions ───
                item {
                    FrostGroupTitle("Actions")
                    FrostContainer {
                        // Re-run installation script — compact pill, no full-width button
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onReinstallDistro() }
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Re-run installation script", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                                Text(reinstallSubtitle, fontSize = 12.sp, color = TextMuted, maxLines = 1)
                            }
                            FrostPill(text = "Re-run", onClick = onReinstallDistro)
                        }
                        FrostDivider()
                        // Uninstall distribution — destructive text action
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showUninstallDialog = true }
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null,
                                tint = FolioDanger,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Uninstall distribution", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = FolioDanger)
                                Text("Remove all files for ${distro.name}", fontSize = 12.sp, color = TextMuted, maxLines = 1)
                            }
                            Text(
                                text = "Uninstall",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = FolioDanger,
                                modifier = Modifier
                                    .clickable { showUninstallDialog = true }
                                    .padding(horizontal = 8.dp, vertical = 8.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // ─── Appearance (wallpaper / accent / terminal / video) ───
                item {
                    FrostGroupTitle("Appearance")
                    FrostContainer {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showAppearanceEditor = true }
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Wallpaper, colors & terminal",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = TextPrimary
                                )
                                Text(
                                    appearanceSummary,
                                    fontSize = 12.sp,
                                    color = TextMuted,
                                    maxLines = 1
                                )
                            }
                            Icon(
                                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // ─── Desktop & hardware ───
                if (desktopHardware.isNotEmpty()) {
                    item {
                        FrostGroupTitle("Desktop & hardware")
                        FrostContainer {
                            desktopHardware.forEachIndexed { index, component ->
                                val isInstalled = remember(component.id, distro.id, refreshKey) {
                                    StateManager.isComponentInstalled(context, distro.id, component.id)
                                }
                                val details = componentDetailsMap[component.id]
                                val expanded = expandedIds.contains(component.id)
                                ComponentSheetRow(
                                    component = component,
                                    isInstalled = isInstalled,
                                    isGlobalInstalling = installState.isInstalling,
                                    details = details,
                                    expanded = expanded,
                                    onToggleExpand = {
                                        expandedIds = if (expanded) expandedIds - component.id else expandedIds + component.id
                                    },
                                    onAction = { handleComponentAction(component) },
                                    onUninstall = { pendingUninstallComponent = component }
                                )
                                if (index < desktopHardware.lastIndex) FrostDivider()
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }

                // ─── Optional components ───
                if (optional.isNotEmpty()) {
                    item {
                        FrostGroupTitle("Optional components")
                        FrostContainer {
                            optional.forEachIndexed { index, component ->
                                val isInstalled = remember(component.id, distro.id, refreshKey) {
                                    StateManager.isComponentInstalled(context, distro.id, component.id)
                                }
                                val details = componentDetailsMap[component.id]
                                val expanded = expandedIds.contains(component.id)
                                ComponentSheetRow(
                                    component = component,
                                    isInstalled = isInstalled,
                                    isGlobalInstalling = installState.isInstalling,
                                    details = details,
                                    expanded = expanded,
                                    onToggleExpand = {
                                        expandedIds = if (expanded) expandedIds - component.id else expandedIds + component.id
                                    },
                                    onAction = { handleComponentAction(component) },
                                    onUninstall = { pendingUninstallComponent = component }
                                )
                                if (index < optional.lastIndex) FrostDivider()
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }

                item { Spacer(modifier = Modifier.height(32.dp)) }
            }
        }
    )

    // --- Component uninstall confirmation (logic unchanged) ---
    if (pendingUninstallComponent != null) {
        val comp = pendingUninstallComponent!!
        GlassDialog(onDismiss = { pendingUninstallComponent = null }) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = FolioDanger,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Uninstall ${comp.name}?",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "This will remove ${comp.name} (${comp.sizeEstimate}) and all its data. " +
                        "You can re-install it later from this screen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { pendingUninstallComponent = null },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier.weight(1f)
                    ) { Text("Cancel") }
                    Button(
                        onClick = {
                            val toUninstall = pendingUninstallComponent
                            pendingUninstallComponent = null
                            if (toUninstall != null) onUninstallComponent(toUninstall)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = FolioDanger,
                            contentColor = Color.White
                        ),
                        modifier = Modifier.weight(1f)
                    ) { Text("Uninstall") }
                }
            }
        }
    }

    // --- Whole-distro uninstall confirmation (logic unchanged) ---
    if (showUninstallDialog) {
        GlassDialog(onDismiss = { showUninstallDialog = false }) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = FolioDanger,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Uninstall ${distro.name}?",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "This will remove all data and files for ${distro.name}. This action cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { showUninstallDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            // Embedded uninstall only: chroot → SSOT uninstall script
                            // via Root Shell; proot → proot-distro remove.
                            onUninstallDistro()
                            showUninstallDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = FolioDanger, contentColor = Color.White),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Uninstall")
                    }
                }
            }
        }
    }

    // --- Customize Desktop bottom sheet (was dialog; backend identical) ---
    if (showThemeSheet && activeComponent != null) {
        ModalBottomSheet(
            onDismissRequest = { showThemeSheet = false },
            sheetState = themeSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text("Customize desktop", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Theme applied by the setup script (FLUX_THEME).", fontSize = 13.sp, color = TextMuted)
                Spacer(modifier = Modifier.height(16.dp))
                SheetRadioRow(
                    name = "Dark mode",
                    desc = "Sleek default",
                    selected = selectedTheme == "dark",
                    onSelect = { selectedTheme = "dark" }
                )
                SheetRadioRow(
                    name = "Light mode",
                    desc = "Bright desktop",
                    selected = selectedTheme == "light",
                    onSelect = { selectedTheme = "light" }
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Cancel",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextSecondary,
                        modifier = Modifier
                            .clickable { showThemeSheet = false }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FrostPill(
                        text = "Apply theme",
                        primary = true,
                        onClick = {
                            val comp = activeComponent
                            showThemeSheet = false
                            if (comp != null) {
                                // KDE customization needs to know it is KDE so the
                                // guest script picks the KDE config set.
                                val desktopEnv = if (comp.id == "kde_customization") {
                                    "FLUX_DESKTOP" to "kde"
                                } else {
                                    "FLUX_DESKTOP" to "xfce"
                                }
                                onInstallComponent(
                                    comp,
                                    mapOf("FLUX_THEME" to selectedTheme, desktopEnv)
                                )
                            }
                        }
                    )
                }
            }
        }
    }

    // --- Hardware acceleration bottom sheet (was dialog; GpuDetector logic identical) ---
    if (showGpuSheet && activeComponent != null) {
        ModalBottomSheet(
            onDismissRequest = { showGpuSheet = false },
            sheetState = gpuSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text("Hardware acceleration", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Renderer passed as FLUX_GPU + vendor hint.", fontSize = 13.sp, color = TextMuted)
                Spacer(modifier = Modifier.height(16.dp))
                SheetRadioRow(
                    name = "Auto detect",
                    desc = "Snapdragon (Turnip) or VirGL",
                    selected = selectedGpu == "auto",
                    onSelect = { selectedGpu = "auto" }
                )
                SheetRadioRow(
                    name = "VirGL",
                    desc = "Compatible with most devices",
                    selected = selectedGpu == "virgl",
                    onSelect = { selectedGpu = "virgl" }
                )
                SheetRadioRow(
                    name = "Turnip / Zink",
                    desc = "High performance for Adreno",
                    selected = selectedGpu == "turnip",
                    onSelect = { selectedGpu = "turnip" }
                )
                SheetRadioRow(
                    name = "Force re-detect",
                    desc = "Ask interactively during install",
                    selected = selectedGpu == "ask",
                    onSelect = { selectedGpu = "ask" }
                )

                val adreno = remember { GpuAccelDetector.detect().mode == GpuAccelDetector.MODE_TURNIP }
                val waiting by DriverRelease.waiting.collectAsState()
                val driverPending = GpuDriverInstaller.pendingDistro(context) != null
                val elig = remember { PanvkInstaller.current(GpuAccelDetector.detect().signals) }
                val panvk = elig is PanvkInstaller.Elig.Ok && PanvkInstaller.glibc(distro.id)
                if (adreno || panvk) {
                    val name = if (adreno) "Turnip" else "PanVK"
                    val inst = if (adreno) GpuDriverInstaller.installedVersion(context, distro.id)
                        else PanvkInstaller.installedVersion(context, distro.id)
                    val latest = if (adreno) GpuDriverInstaller.latestVersion(context)
                        else PanvkInstaller.latestVersion(context)
                    val upd = if (adreno) GpuDriverInstaller.updateAvailable(context, distro.id)
                        else PanvkInstaller.updateAvailable(context, distro.id)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "GPU driver ($name): installed ${inst ?: "none"} · latest ${latest ?: "unknown"}" +
                            when {
                                waiting -> "\nWaiting for internet…"
                                driverPending -> "\nNo internet — driver not installed. Software rendering until you retry."
                                upd && inst != null -> "\nUpdate available."
                                else -> ""
                            },
                        fontSize = 12.sp,
                        color = TextMuted
                    )
                } else if (elig is PanvkInstaller.Elig.Skip) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("GPU driver (PanVK): ${elig.msg}", fontSize = 12.sp, color = TextMuted)
                } else if (elig is PanvkInstaller.Elig.Ok) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "GPU driver (PanVK): needs a glibc distro (${distro.id} is musl) — software rendering stays",
                        fontSize = 12.sp,
                        color = TextMuted
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Cancel",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextSecondary,
                        modifier = Modifier
                            .clickable { showGpuSheet = false }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FrostPill(
                        text = if ((adreno || panvk) && driverPending) "Retry" else "Apply",
                        primary = true,
                        onClick = {
                            val comp = activeComponent
                            showGpuSheet = false
                            if (comp != null) {
                                val det = GpuAccelDetector.detect()
                                GpuAccelDetector.persist(context, det)
                                val mode = GpuAccelDetector.resolveFluxGpu(selectedGpu)
                                onInstallComponent(
                                    comp,
                                    mapOf(
                                        "FLUX_GPU" to mode,
                                        "FLUX_GPU_VENDOR" to det.vendorHint
                                    )
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun GlassDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val cs = MaterialTheme.colorScheme
        val dark = cs.surface.luminance() < 0.5f
        Box(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .clip(RoundedCornerShape(28.dp))
                .background(
                    Brush.linearGradient(
                        colors = if (dark) listOf(
                            Color(0xFF1E1E1E).copy(alpha = 0.95f),
                            Color(0xFF121212).copy(alpha = 0.98f)
                        ) else listOf(
                            cs.surface.copy(alpha = 0.98f),
                            cs.surface.copy(alpha = 0.98f)
                        )
                    )
                )
                .border(
                    BorderStroke(1.dp, Brush.verticalGradient(
                        listOf(
                            (if (dark) Color.White else cs.onSurface).copy(alpha = 0.15f),
                            Color.Transparent
                        )
                    )),
                    RoundedCornerShape(28.dp)
                )
        ) {
            CompositionLocalProvider(LocalContentColor provides cs.onSurface) { content() }
        }
    }
}

/** Bottom-sheet radio row: title + desc with trailing radio, no giant buttons. */
@Composable
private fun SheetRadioRow(
    name: String,
    desc: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onSelect() }
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = if (selected) TextPrimary else TextPrimary
            )
            Text(desc, fontSize = 12.sp, color = TextMuted)
        }
        RadioButton(
            selected = selected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(selectedColor = TextPrimary)
        )
    }
}

/**
 * Grouped-sheet component row: icon + title/subtitle, compact pill action,
 * chevron expands the included-packages list. Uninstall is a compact
 * destructive text action inside the expanded area.
 */
@Composable
private fun ComponentSheetRow(
    component: DistroComponent,
    isInstalled: Boolean,
    isGlobalInstalling: Boolean = false,
    details: ComponentDetail?,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onAction: () -> Unit,
    onUninstall: (() -> Unit)? = null
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !component.comingSoon) {
                    if (details != null) onToggleExpand() else onAction()
                }
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (details != null) {
                Icon(
                    imageVector = details.icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (component.comingSoon) TextMuted else TextSecondary
                )
                Spacer(modifier = Modifier.width(14.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = component.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (component.comingSoon) TextMuted else TextPrimary,
                        maxLines = 1
                    )
                    if (isInstalled && !component.comingSoon) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(TextPrimary.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Installed", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (component.comingSoon) "Coming soon" else "${component.sizeEstimate} · ${component.description}",
                    fontSize = 12.sp,
                    color = TextMuted,
                    maxLines = if (expanded) Int.MAX_VALUE else 1
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (!component.comingSoon) {
                if (isGlobalInstalling) {
                    Text("Busy…", fontSize = 13.sp, color = TextMuted)
                } else {
                    FrostPill(
                        text = if (isInstalled) "Re-run" else "Install",
                        primary = !isInstalled,
                        onClick = onAction
                    )
                }
            } else {
                Text("Soon", fontSize = 12.sp, color = TextMuted)
            }
            if (details != null && !component.comingSoon) {
                IconButton(onClick = onToggleExpand, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            } else if (details == null) {
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            if (details != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 52.dp, end = 18.dp, bottom = 14.dp)
                ) {
                    Text(
                        text = "Included packages",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    details.packages.forEach { (pkg, size) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "• $pkg",
                                fontSize = 12.sp,
                                color = TextSecondary,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = size,
                                fontSize = 12.sp,
                                color = TextMuted,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    // Compact uninstall text action (mandatory / base components excluded
                    // by the same rule as before: hw_accel, xfce4_desktop, termux
                    // customizations have no uninstall branch — tapping would re-install).
                    if (isInstalled && isUninstallable(component) && onUninstall != null && !isGlobalInstalling) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Uninstall ${component.name}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = FolioDanger,
                            modifier = Modifier
                                .clickable { onUninstall() }
                                .padding(vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}
