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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LightMode
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivarna.fluxlinux.core.data.Distro
import com.ivarna.fluxlinux.core.install.DistroInstallProfile
import com.ivarna.fluxlinux.core.install.OnboardingInstallRunner
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
 * Thin install wizard: XFCE + customization only (no feature modules).
 * Uses [OnboardingInstallRunner] for both proot and chroot.
 * Grouped-sheet layout: Appearance / Includes / Actions with compact pills.
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
        runner.start(distro.id, selectedTheme) { progress ->
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
                Toast.makeText(context, "Base desktop installed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { runner.cancel() }
    }

    val profile = DistroInstallProfile.forId(distro.id)
    val isChroot = distro.id.contains("chroot")
    val rootfsLine = when {
        distro.id.startsWith("alpine") ->
            "Installs Alpine 3.24 minirootfs, XFCE4 (apk), and Flux customization. "
        distro.id.startsWith("fedora") ->
            "Installs Fedora 44 rootfs, XFCE4 (dnf), Mesa, and Flux customization. "
        distro.id.startsWith("void") ->
            "Installs Void Linux rootfs, XFCE4 (xbps), Mesa, and Flux customization. "
        distro.id.startsWith("opensuse") ->
            "Installs openSUSE Tumbleweed rootfs, XFCE4 (zypper), Mesa, and Flux customization. "
        else ->
            "Installs ${profile?.displayName ?: distro.name} rootfs, XFCE4, and Flux customization. "
    } + "App modules can be added later from Distro Settings."

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
        add(Icons.Default.CheckCircle to "XFCE4 desktop")
        if (distro.id.startsWith("fedora") || distro.id.startsWith("void") || distro.id.startsWith("opensuse")) {
            add(Icons.Default.CheckCircle to "Mesa / VirGL hardware acceleration")
        }
        add(Icons.Default.CheckCircle to "Flux theme / wallpapers / fonts")
        if (isChroot) add(Icons.Default.CheckCircle to "Requires root (KernelSU / Magisk)")
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
                            if (phase == InstallPhase.RUNNING && !failed) {
                                runner.cancel()
                            }
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

                FrostGroupTitle("Appearance")
                FrostContainer {
                    ThemeSheetRow(
                        icon = Icons.Default.DarkMode,
                        title = "Dark",
                        subtitle = "Sleek default",
                        selected = selectedTheme == "dark",
                        onSelect = { selectedTheme = "dark" }
                    )
                    FrostDivider()
                    ThemeSheetRow(
                        icon = Icons.Default.LightMode,
                        title = "Light",
                        subtitle = "Bright desktop",
                        selected = selectedTheme == "light",
                        onSelect = { selectedTheme = "light" }
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
                                if (isChroot) "Install base desktop (Root)" else "Install base desktop",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary
                            )
                            Text(
                                "XFCE + theme (${selectedTheme})",
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
                onDone = onInstallComplete,
                isDone = phase == InstallPhase.DONE
            )
        }
    }
}

@Composable
private fun ThemeSheetRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            Text(subtitle, fontSize = 12.sp, color = TextMuted)
        }
        RadioButton(
            selected = selected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(selectedColor = TextPrimary)
        )
    }
}

private enum class InstallPhase { OPTIONS, RUNNING, DONE }
