package com.ivarna.fluxlinux.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivarna.fluxlinux.core.data.Distro
import com.ivarna.fluxlinux.core.install.DesktopChoice
import com.ivarna.fluxlinux.core.install.DesktopInstallSelection
import com.ivarna.fluxlinux.core.install.DistroInstallProfile
import com.ivarna.fluxlinux.core.install.InstallExtra
import com.ivarna.fluxlinux.core.install.OnboardingInstallRunner
import com.ivarna.fluxlinux.ui.components.FluxSwitch
import com.ivarna.fluxlinux.ui.components.FrostContainer
import com.ivarna.fluxlinux.ui.components.FrostDivider
import com.ivarna.fluxlinux.ui.components.FrostGroupTitle
import com.ivarna.fluxlinux.ui.components.FrostPill
import com.ivarna.fluxlinux.ui.components.GlassScaffold
import com.ivarna.fluxlinux.ui.install.InstallProgressPanel
import com.ivarna.fluxlinux.ui.theme.TextMuted
import com.ivarna.fluxlinux.ui.theme.TextPrimary
import com.ivarna.fluxlinux.ui.theme.TextSecondary
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/**
 * Thin install wizard: desktop toggles (XFCE4 / KDE Plasma — any combination,
 * none = terminal-only) + theme + optional hardware acceleration (no feature
 * modules). Uses [OnboardingInstallRunner] for both proot and chroot.
 * Grouped-sheet layout: Desktop / Extras / Appearance / Includes / Actions with
 * compact pills.
 *
 * Back navigation is allowed while an install runs: the runner + foreground
 * service continue in the background, the in-app task bar takes over, and a
 * re-entered screen adopts the live session (see [OnboardingInstallRunner.live]).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class)
@Composable
fun InstallConfigScreen(
    distro: Distro,
    onBack: () -> Unit,
    onInstallComplete: () -> Unit,
    hazeState: HazeState
) {
    val context = LocalContext.current
    val appCtx = context.applicationContext
    var selectedTheme by remember { mutableStateOf("dark") }
    var selectedDesktops by remember(distro.id) { mutableStateOf(setOf(DesktopChoice.XFCE)) }
    var hwAccel by remember(distro.id) { mutableStateOf(true) }
    var phase by remember { mutableStateOf(InstallPhase.OPTIONS) }
    var percent by remember { mutableIntStateOf(0) }
    var phaseLabel by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf("") }
    var logText by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showLog by remember { mutableStateOf(true) }
    val runner = remember { OnboardingInstallRunner(appCtx) }

    fun startInstall() {
        // Generation cancel inside runner; single instance avoids parallel jobs
        failed = false
        errorMessage = null
        percent = 0
        phaseLabel = "Starting…"
        detail = ""
        logText = ""
        phase = InstallPhase.RUNNING
        val desktops = selectedDesktops
        val extras: Set<InstallExtra> =
            if (hwAccel) emptySet() else setOf(InstallExtra.SKIP_HW_ACCEL)
        runner.start(
            distroId = distro.id,
            theme = selectedTheme,
            desktops = desktops,
            extras = extras
        ) { progress ->
            percent = progress.overallPercent
            phaseLabel = progress.phaseLabel
            detail = progress.detail
            progress.logLine?.let { line ->
                logText = (logText + line + "\n").takeLast(12_000)
            }
            if (progress.failed) {
                failed = true
                errorMessage = progress.errorMessage ?: progress.detail
            }
            if (progress.finished && !progress.failed) {
                phase = InstallPhase.DONE
                val toast = when {
                    desktops.isEmpty() -> "Terminal base installed"
                    desktops.size == 1 && DesktopChoice.XFCE in desktops -> "XFCE4 installed"
                    desktops.size == 1 -> "KDE Plasma installed"
                    else -> "XFCE4 + KDE Plasma installed"
                }
                Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // Leaving mid-install leaves it running in the background (FGS + task
            // bar take over); only idle sessions are torn down here.
            if (!(phase == InstallPhase.RUNNING && !failed)) {
                runner.cancel()
            }
        }
    }

    // Adopt a background session for this distro (task-bar tap or a return to
    // this screen while the runner keeps working). Fresh finished sessions are
    // still shown so "Install complete" / failure logs survive navigation.
    val liveInstall by OnboardingInstallRunner.live.collectAsState()
    LaunchedEffect(liveInstall, distro.id) {
        val live = liveInstall ?: return@LaunchedEffect
        if (live.distroId != distro.id) return@LaunchedEffect
        if (live.progress.finished &&
            System.currentTimeMillis() - live.atMillis > LIVE_ATTACH_WINDOW_MS
        ) {
            return@LaunchedEffect
        }
        val p = live.progress
        percent = p.overallPercent
        phaseLabel = p.phaseLabel
        detail = p.detail
        logText = live.logText
        failed = p.failed
        if (p.failed) errorMessage = p.errorMessage ?: p.detail
        phase = if (p.finished && !p.failed) InstallPhase.DONE else InstallPhase.RUNNING
    }

    val profile = DistroInstallProfile.forId(distro.id)
    val isChroot = distro.id.contains("chroot")
    val kdeAvailable = remember(distro.id) {
        DesktopInstallSelection.kdePlasmaAvailable(distro.id)
    }
    val xfceSelected = DesktopChoice.XFCE in selectedDesktops
    val kdeSelected = DesktopChoice.KDE in selectedDesktops
    val terminalOnly = selectedDesktops.isEmpty()

    val baseLine = when {
        distro.id.startsWith("alpine") -> "Alpine 3.24 minirootfs"
        distro.id.startsWith("fedora") -> "Fedora 44 container rootfs"
        distro.id.startsWith("void") -> "Void Linux (glibc aarch64) rootfs"
        distro.id.startsWith("opensuse") -> "openSUSE Tumbleweed rootfs"
        else -> "${profile?.displayName ?: distro.name} rootfs"
    }
    val xfceManagerNote = when {
        distro.id.startsWith("alpine") -> " (apk)"
        distro.id.startsWith("fedora") -> " (dnf)"
        distro.id.startsWith("void") -> " (xbps)"
        distro.id.startsWith("opensuse") -> " (zypper)"
        else -> ""
    }
    val rootfsLine = buildString {
        append("Installs $baseLine")
        if (xfceSelected) append(", XFCE4$xfceManagerNote")
        if (kdeSelected) append(", KDE Plasma")
        if (terminalOnly) {
            append(" as a terminal base (no desktop). ")
        } else {
            append(", and Folio Linux theming. ")
        }
        append("App modules can be added later from Distro Settings.")
    }

    val includes: List<Pair<ImageVector, String>> = buildList {
        add(
            Icons.Default.Download to when {
                distro.id.startsWith("alpine") -> "Alpine 3.24 minirootfs (~4 MB + apk packages)"
                distro.id.startsWith("fedora") -> "Fedora 44 container rootfs"
                distro.id.startsWith("void") -> "Void Linux (glibc aarch64) rootfs"
                distro.id.startsWith("opensuse") -> "openSUSE Tumbleweed rootfs"
                else -> "${profile?.displayName ?: distro.name} rootfs"
            }
        )
        when {
            terminalOnly ->
                add(Icons.Default.CheckCircle to "Terminal base — no desktop packages")
            xfceSelected && kdeSelected -> {
                add(Icons.Default.CheckCircle to "XFCE4 desktop")
                add(Icons.Default.CheckCircle to "KDE Plasma desktop")
            }
            xfceSelected -> add(Icons.Default.CheckCircle to "XFCE4 desktop")
            else -> add(Icons.Default.CheckCircle to "KDE Plasma desktop")
        }
        if (!terminalOnly && hwAccel && profile?.hwAccelScript != null) {
            add(Icons.Default.CheckCircle to "Mesa / VirGL hardware acceleration")
        }
        if (!terminalOnly) {
            add(
                Icons.Default.CheckCircle to when {
                    xfceSelected && kdeSelected ->
                        "XFCE4 + KDE Plasma theming / wallpapers / Zsh"
                    kdeSelected -> "KDE Plasma theming / wallpapers / Zsh"
                    else -> "Folio Linux theme / wallpapers / fonts"
                }
            )
        }
    }

    val actionsTitle = when {
        isChroot && terminalOnly -> "Install terminal base (Root)"
        isChroot -> "Install base desktop (Root)"
        terminalOnly -> "Install terminal base"
        else -> "Install base desktop"
    }
    val actionsSubtitle = when {
        terminalOnly -> "Terminal base — no desktop"
        xfceSelected && kdeSelected -> "XFCE4 + KDE Plasma + theme ($selectedTheme)"
        xfceSelected -> "XFCE4 + theme ($selectedTheme)"
        else -> "KDE Plasma + theme ($selectedTheme)"
    }

    GlassScaffold(
        hazeState = hazeState,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        when (phase) {
                            InstallPhase.OPTIONS -> "Install ${distro.name}"
                            InstallPhase.RUNNING -> if (failed) "Install failed" else "Installing…"
                            InstallPhase.DONE -> "Install complete"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            // Back never cancels: the install keeps running in the
                            // background (task bar + FGS), cancellation is explicit
                            // via the progress panel's "Cancel install".
                            onBack()
                        }
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
                modifier = Modifier.hazeChild(
                    state = hazeState,
                    shape = androidx.compose.ui.graphics.RectangleShape,
                    style = HazeMaterials.thin()
                )
            )
        }
    ) {
        when (phase) {
            InstallPhase.OPTIONS -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(top = 16.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    rootfsLine,
                    fontSize = 13.sp,
                    color = TextMuted,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                )

                FrostGroupTitle("Desktop")
                FrostContainer {
                    // Base line is read-only: rootfs + shell are always installed.
                    InfoSheetRow(
                        icon = Icons.Default.Terminal,
                        title = "Terminal base (always included)",
                        subtitle = "Rootfs + shell — desktops below are optional"
                    )
                    FrostDivider()
                    ToggleSheetRow(
                        icon = Icons.Default.Palette,
                        title = "XFCE4 desktop",
                        subtitle = "Lightweight classic desktop",
                        checked = xfceSelected,
                        onCheckedChange = { on ->
                            selectedDesktops = if (on) {
                                selectedDesktops + DesktopChoice.XFCE
                            } else {
                                selectedDesktops - DesktopChoice.XFCE
                            }
                        }
                    )
                    if (kdeAvailable) {
                        FrostDivider()
                        ToggleSheetRow(
                            icon = Icons.Default.Brush,
                            title = "KDE Plasma",
                            subtitle = "Feature-rich, highly customizable desktop",
                            checked = kdeSelected,
                            onCheckedChange = { on ->
                                selectedDesktops = if (on) {
                                    selectedDesktops + DesktopChoice.KDE
                                } else {
                                    selectedDesktops - DesktopChoice.KDE
                                }
                            }
                        )
                    }
                    FrostDivider()
                    InfoSheetRow(
                        icon = Icons.Default.CheckCircle,
                        title = if (terminalOnly) "Terminal only" else "Selected desktops install together",
                        subtitle = if (terminalOnly) {
                            "No desktop packages — toggle one above to add a desktop."
                        } else {
                            "Any combination is fine; XFCE4 and KDE Plasma can coexist."
                        }
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))

                FrostGroupTitle("Extras")
                FrostContainer {
                    SwitchSheetRow(
                        icon = Icons.Default.Speed,
                        title = "Hardware acceleration",
                        subtitle = if (terminalOnly) {
                            "Not applicable to terminal-only installs"
                        } else {
                            "Installs GPU drivers (Turnip / VirGL) when supported"
                        },
                        checked = hwAccel && !terminalOnly,
                        enabled = !terminalOnly,
                        onCheckedChange = { hwAccel = it }
                    )
                    if (isChroot) {
                        FrostDivider()
                        InfoSheetRow(
                            icon = Icons.Default.Info,
                            title = "Root required",
                            subtitle = "Grant superuser (KernelSU / Magisk) when prompted."
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))

                FrostGroupTitle("Appearance")
                FrostContainer {
                    ThemeSheetRow(
                        icon = Icons.Default.DarkMode,
                        title = "Dark",
                        subtitle = "Sleek default",
                        selected = selectedTheme == "dark",
                        onSelect = { selectedTheme = "dark" },
                        enabled = !terminalOnly
                    )
                    FrostDivider()
                    ThemeSheetRow(
                        icon = Icons.Default.LightMode,
                        title = "Light",
                        subtitle = "Bright desktop",
                        selected = selectedTheme == "light",
                        onSelect = { selectedTheme = "light" },
                        enabled = !terminalOnly
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))

                FrostGroupTitle("Includes")
                FrostContainer {
                    includes.forEachIndexed { index, (icon, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Text(
                                text = label,
                                fontSize = 13.sp,
                                color = TextSecondary,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (index < includes.lastIndex) FrostDivider()
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))

                FrostGroupTitle("Actions")
                FrostContainer {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { startInstall() }
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                actionsTitle,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary
                            )
                            Text(
                                actionsSubtitle,
                                fontSize = 12.sp,
                                color = TextMuted,
                                maxLines = 1
                            )
                        }
                        FrostPill(text = "Install", primary = true, onClick = { startInstall() })
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
            InstallPhase.RUNNING, InstallPhase.DONE -> InstallProgressPanel(
                percent = percent,
                phaseLabel = phaseLabel,
                detail = detail,
                logText = logText,
                failed = failed,
                errorMessage = errorMessage,
                showLog = showLog,
                onToggleLog = { showLog = !showLog },
                onRetry = { startInstall() },
                onDone = {
                    OnboardingInstallRunner.clearLive(distro.id)
                    onInstallComplete()
                },
                onCancel = {
                    // Cancel the live session, whichever runner instance owns it.
                    OnboardingInstallRunner.cancelActive()
                },
                isDone = phase == InstallPhase.DONE
            )
        }
    }
}

/** Finished live sessions older than this are not re-attached on entry. */
private const val LIVE_ATTACH_WINDOW_MS = 5 * 60 * 1000L

/** 36dp rounded icon chip shared by the option rows. */
@Composable
private fun SheetIconChip(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                RoundedCornerShape(10.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun ThemeSheetRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    onSelect: () -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onSelect() }
            .padding(horizontal = 18.dp, vertical = 12.dp)
            .alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SheetIconChip(icon)
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            Text(subtitle, fontSize = 12.sp, color = TextMuted)
        }
        RadioButton(
            selected = selected,
            onClick = onSelect,
            enabled = enabled,
            colors = RadioButtonDefaults.colors(selectedColor = TextPrimary)
        )
    }
}

@Composable
private fun SwitchSheetRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp)
            .alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SheetIconChip(icon)
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            Text(subtitle, fontSize = 12.sp, color = TextMuted)
        }
        Spacer(modifier = Modifier.width(12.dp))
        FluxSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

/** Multi-select desktop toggle: whole row flips the option, not just the switch. */
@Composable
private fun ToggleSheetRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SheetIconChip(icon)
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            Text(subtitle, fontSize = 12.sp, color = TextMuted)
        }
        Spacer(modifier = Modifier.width(12.dp))
        FluxSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun InfoSheetRow(icon: ImageVector, title: String, subtitle: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SheetIconChip(icon)
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            Text(subtitle, fontSize = 12.sp, color = TextMuted)
        }
    }
}

private enum class InstallPhase { OPTIONS, RUNNING, DONE }
