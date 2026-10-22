package com.altaris.linux.ui.screens

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altaris.linux.core.data.Distro
import com.altaris.linux.core.install.DesktopChoice
import com.altaris.linux.core.install.DesktopInstallSelection
import com.altaris.linux.core.install.DistroInstallProfile
import com.altaris.linux.core.install.InstallExtra
import com.altaris.linux.core.install.OnboardingInstallRunner
import com.altaris.linux.ui.components.AltarisSwitch
import com.altaris.linux.ui.components.GlassScaffold
import com.altaris.linux.ui.components.PrismButton
import com.altaris.linux.ui.components.PrismCard
import com.altaris.linux.ui.components.PrismChip
import com.altaris.linux.ui.components.PrismRow
import com.altaris.linux.ui.components.PrismSectionLabel
import com.altaris.linux.ui.components.PrismSegmented
import com.altaris.linux.ui.install.InstallProgressPanel
import com.altaris.linux.ui.theme.AltarisSection
import com.altaris.linux.ui.theme.Prism
import com.altaris.linux.ui.theme.rememberStaggerEntrance
import com.altaris.linux.ui.theme.sectionAccent
import com.altaris.linux.ui.theme.staggerIn
import dev.chrisbanes.haze.HazeState

/**
 * Thin install wizard: desktop toggles (XFCE4 / KDE Plasma — any combination,
 * none = terminal-only) + theme + optional hardware acceleration (no feature
 * modules). Uses [OnboardingInstallRunner] for both proot and chroot.
 *
 * The sheet is rebuilt in the Prism language: cards, rows, a segmented theme
 * control and one pinned action row. The installer itself is untouched.
 *
 * Back navigation is allowed while an install runs: the runner + foreground
 * service continue in the background, the in-app task bar takes over, and a
 * re-entered screen adopts the live session (see [OnboardingInstallRunner.live]).
 */
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
    var progressIndeterminate by remember { mutableStateOf(false) }
    val runner = remember { OnboardingInstallRunner(appCtx) }

    fun startInstall() {
        // Generation cancel inside runner; single instance avoids parallel jobs
        failed = false
        errorMessage = null
        percent = 0
        phaseLabel = "Starting…"
        detail = ""
        logText = ""
        progressIndeterminate = false
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
            progressIndeterminate = progress.indeterminate
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
        progressIndeterminate = p.indeterminate
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

    val accent = sectionAccent(AltarisSection.DISTROS)
    val entrance = rememberStaggerEntrance()

    // The size each optional module pulls in — factual, straight from the
    // component catalogue.
    val componentSizes = remember(distro.id) {
        distro.components.associate { it.id to it.sizeEstimate }
    }

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
            append(", and Altaris theming. ")
        }
        append("App modules can be added later from Distro Settings.")
    }

    val includes: List<Triple<ImageVector, String, String?>> = buildList {
        add(
            Triple(
                Icons.Default.Download,
                when {
                    distro.id.startsWith("alpine") -> "Alpine 3.24 minirootfs (~4 MB + apk packages)"
                    distro.id.startsWith("fedora") -> "Fedora 44 container rootfs"
                    distro.id.startsWith("void") -> "Void Linux (glibc aarch64) rootfs"
                    distro.id.startsWith("opensuse") -> "openSUSE Tumbleweed rootfs"
                    else -> "${profile?.displayName ?: distro.name} rootfs"
                },
                null
            )
        )
        when {
            terminalOnly ->
                add(Triple(Icons.Default.CheckCircle, "Terminal base — no desktop packages", null))
            xfceSelected && kdeSelected -> {
                add(Triple(Icons.Default.CheckCircle, "XFCE4 desktop", componentSizes["xfce4_desktop"]))
                add(Triple(Icons.Default.CheckCircle, "KDE Plasma desktop", componentSizes["kde_plasma"]))
            }
            xfceSelected -> add(Triple(Icons.Default.CheckCircle, "XFCE4 desktop", componentSizes["xfce4_desktop"]))
            else -> add(Triple(Icons.Default.CheckCircle, "KDE Plasma desktop", componentSizes["kde_plasma"]))
        }
        if (!terminalOnly && hwAccel && profile?.hwAccelScript != null) {
            add(
                Triple(
                    Icons.Default.CheckCircle,
                    "Mesa / VirGL hardware acceleration",
                    componentSizes["hw_accel"]
                )
            )
        }
        if (!terminalOnly) {
            add(
                Triple(
                    Icons.Default.CheckCircle,
                    when {
                        xfceSelected && kdeSelected ->
                            "XFCE4 + KDE Plasma theming / wallpapers / Zsh"
                        kdeSelected -> "KDE Plasma theming / wallpapers / Zsh"
                        else -> "Altaris theme / wallpapers / fonts"
                    },
                    if (kdeSelected && xfceSelected) {
                        componentSizes["kde_customization"] ?: componentSizes["customization"]
                    } else if (kdeSelected) {
                        componentSizes["kde_customization"]
                    } else {
                        componentSizes["customization"]
                    }
                )
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
            WizardTopBar(
                title = when (phase) {
                    InstallPhase.OPTIONS -> "Install ${distro.name}"
                    InstallPhase.RUNNING -> if (failed) "Install failed" else "Installing…"
                    InstallPhase.DONE -> "Install complete"
                },
                subtitle = when (phase) {
                    InstallPhase.OPTIONS -> if (isChroot) "Chroot guest" else "PRoot guest"
                    InstallPhase.RUNNING -> phaseLabel.ifBlank { "Working…" }
                    InstallPhase.DONE -> "Base desktop installed"
                },
                onBack = onBack
            )
        }
    ) {
        when (phase) {
            InstallPhase.OPTIONS -> Column(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = Prism.Gutter)
                        .padding(top = Prism.Stack, bottom = Prism.Stack),
                    verticalArrangement = Arrangement.spacedBy(Prism.Stack)
                ) {
                    // Summary of what the install pulls in.
                    PrismCard(
                        accent = accent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .staggerIn(0, entrance)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (distro.iconRes != null) {
                                Image(
                                    painter = painterResource(id = distro.iconRes),
                                    contentDescription = "${distro.name} logo",
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                )
                                Spacer(Modifier.width(13.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = distro.name,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = rootfsLine,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.width(Prism.Tight))
                            PrismChip(
                                text = if (isChroot) "CHROOT" else "PROOT",
                                accent = accent
                            )
                        }
                    }

                    // Desktop selection.
                    PrismSectionLabel(
                        text = "Desktop",
                        accent = accent,
                        modifier = Modifier.staggerIn(1, entrance)
                    )
                    PrismCard(
                        accent = accent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .staggerIn(1, entrance)
                    ) {
                        PrismRow(
                            title = "Terminal base (always included)",
                            subtitle = "Rootfs + shell — desktops below are optional",
                            accent = accent,
                            leading = { OptionIcon(Icons.Default.Terminal) },
                            divider = true
                        )
                        PrismRow(
                            title = "XFCE4 desktop",
                            subtitle = "Lightweight classic desktop",
                            accent = accent,
                            leading = { OptionIcon(Icons.Default.Palette) },
                            trailing = {
                                AltarisSwitch(
                                    checked = xfceSelected,
                                    onCheckedChange = { on ->
                                        selectedDesktops = if (on) {
                                            selectedDesktops + DesktopChoice.XFCE
                                        } else {
                                            selectedDesktops - DesktopChoice.XFCE
                                        }
                                    }
                                )
                            },
                            onClick = {
                                selectedDesktops = if (xfceSelected) {
                                    selectedDesktops - DesktopChoice.XFCE
                                } else {
                                    selectedDesktops + DesktopChoice.XFCE
                                }
                            },
                            divider = kdeAvailable
                        )
                        if (kdeAvailable) {
                            PrismRow(
                                title = "KDE Plasma",
                                subtitle = "Feature-rich, highly customizable desktop",
                                accent = accent,
                                leading = { OptionIcon(Icons.Default.Brush) },
                                trailing = {
                                    AltarisSwitch(
                                        checked = kdeSelected,
                                        onCheckedChange = { on ->
                                            selectedDesktops = if (on) {
                                                selectedDesktops + DesktopChoice.KDE
                                            } else {
                                                selectedDesktops - DesktopChoice.KDE
                                            }
                                        }
                                    )
                                },
                                onClick = {
                                    selectedDesktops = if (kdeSelected) {
                                        selectedDesktops - DesktopChoice.KDE
                                    } else {
                                        selectedDesktops + DesktopChoice.KDE
                                    }
                                },
                                divider = true
                            )
                        }
                        PrismRow(
                            title = if (terminalOnly) "Terminal only" else "Selected desktops install together",
                            subtitle = if (terminalOnly) {
                                "No desktop packages — toggle one above to add a desktop."
                            } else {
                                "Any combination is fine; XFCE4 and KDE Plasma can coexist."
                            },
                            accent = accent,
                            leading = { OptionIcon(Icons.Default.CheckCircle) }
                        )
                    }

                    // Extras.
                    PrismSectionLabel(
                        text = "Extras",
                        accent = accent,
                        modifier = Modifier.staggerIn(2, entrance)
                    )
                    PrismCard(
                        accent = accent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .staggerIn(2, entrance)
                    ) {
                        PrismRow(
                            title = "Hardware acceleration",
                            subtitle = if (terminalOnly) {
                                "Not applicable to terminal-only installs"
                            } else {
                                "Installs GPU drivers (Turnip / VirGL) when supported"
                            },
                            accent = accent,
                            leading = { OptionIcon(Icons.Default.Speed) },
                            trailing = {
                                AltarisSwitch(
                                    checked = hwAccel && !terminalOnly,
                                    enabled = !terminalOnly,
                                    onCheckedChange = { hwAccel = it }
                                )
                            },
                            divider = isChroot
                        )
                        if (isChroot) {
                            PrismRow(
                                title = "Root required",
                                subtitle = "Grant superuser (KernelSU / Magisk) when prompted.",
                                accent = accent,
                                leading = { OptionIcon(Icons.Default.Info) }
                            )
                        }
                    }

                    // Appearance.
                    PrismSectionLabel(
                        text = "Appearance",
                        accent = accent,
                        modifier = Modifier.staggerIn(3, entrance)
                    )
                    PrismCard(
                        accent = accent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .staggerIn(3, entrance)
                    ) {
                        PrismSegmented(
                            options = listOf("Dark", "Light"),
                            selectedIndex = if (selectedTheme == "dark") 0 else 1,
                            accent = accent,
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    alpha = if (terminalOnly) 0.45f else 1f
                                },
                            onSelect = { index ->
                                if (!terminalOnly) {
                                    selectedTheme = if (index == 0) "dark" else "light"
                                }
                            }
                        )
                        if (terminalOnly) {
                            Spacer(Modifier.height(Prism.Tight))
                            Text(
                                text = "The theme applies to desktop installs only.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // What the install brings in.
                    PrismSectionLabel(
                        text = "Includes",
                        accent = accent,
                        modifier = Modifier.staggerIn(4, entrance)
                    )
                    PrismCard(
                        accent = accent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .staggerIn(4, entrance)
                    ) {
                        includes.forEachIndexed { index, (_, label, size) ->
                            IncludeRow(
                                label = label,
                                size = size,
                                divider = index < includes.lastIndex
                            )
                        }
                    }
                }

                // The single action row, pinned above the gesture inset.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outline)
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = Prism.Gutter, vertical = Prism.Tight),
                        verticalArrangement = Arrangement.spacedBy(Prism.Tight)
                    ) {
                        Text(
                            text = actionsSubtitle,
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        PrismButton(
                            text = actionsTitle,
                            accent = accent,
                            icon = Icons.Default.Download,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { startInstall() }
                        )
                    }
                }
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
                isDone = phase == InstallPhase.DONE,
                indeterminate = progressIndeterminate
            )
        }
    }
}

/** Finished live sessions older than this are not re-attached on entry. */
private const val LIVE_ATTACH_WINDOW_MS = 5 * 60 * 1000L

/** The wizard header: a back control, the title and the current phase. */
@Composable
private fun WizardTopBar(
    title: String,
    subtitle: String,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = Prism.Gutter, vertical = Prism.Tight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BackControl(onClick = onBack)
        Spacer(Modifier.width(Prism.Tight))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** The back control of the wizard header — a 48 dp target, no layout shift. */
@Composable
private fun BackControl(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val alpha by animateFloatAsState(
        targetValue = if (pressed) 0.55f else 1f,
        label = "wizardBack"
    )
    Box(
        modifier = Modifier
            .size(Prism.TouchTarget)
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .graphicsLayer { this.alpha = alpha },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp)
        )
    }
}

/** A monochrome icon plate used as the leading slot of an option row. */
@Composable
private fun OptionIcon(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * One line of the manifest: what the install brings in and how much it
 * weighs. The size stays in the secondary colour.
 */
@Composable
private fun IncludeRow(
    label: String,
    size: String?,
    divider: Boolean
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OptionIcon(Icons.Default.CheckCircle)
            Spacer(Modifier.width(13.dp))
            Text(
                text = label,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (!size.isNullOrBlank()) {
                Spacer(Modifier.width(Prism.Tight))
                Text(
                    text = size,
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        if (divider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
        }
    }
}

private enum class InstallPhase { OPTIONS, RUNNING, DONE }
