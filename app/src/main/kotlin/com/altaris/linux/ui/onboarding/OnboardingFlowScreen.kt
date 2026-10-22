package com.altaris.linux.ui.onboarding

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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.altaris.linux.core.data.Distro
import com.altaris.linux.core.data.DistroRepository
import com.altaris.linux.core.install.HostBootstrap
import com.altaris.linux.core.install.OnboardingInstallRunner
import com.altaris.linux.core.root.RootShell
import com.altaris.linux.core.system.SharedStorageAccess
import com.altaris.linux.core.terminal.TerminalLauncher
import com.altaris.linux.core.utils.StateManager
import com.altaris.linux.ui.components.AltarisMark
import com.altaris.linux.ui.components.MethodTab
import com.altaris.linux.ui.components.PrismButton
import com.altaris.linux.ui.components.PrismCard
import com.altaris.linux.ui.components.PrismChip
import com.altaris.linux.ui.components.PrismRow
import com.altaris.linux.ui.components.PrismSegmented
import com.altaris.linux.ui.components.isChrootCard
import com.altaris.linux.ui.theme.AltarisSection
import com.altaris.linux.ui.theme.Prism
import com.altaris.linux.ui.theme.rememberStaggerEntrance
import com.altaris.linux.ui.theme.sectionAccent
import com.altaris.linux.ui.theme.staggerIn

private enum class OnboardStep { Welcome, Consent, HostSetup, Ready, DistroPick, Options, Storage, Running, Done }

/**
 * Full first-run onboarding.
 *
 * The flow is unchanged — Welcome → Disclosure → Host Setup → Ready, with the
 * optional install branch (Distro → Options → Storage → Install) — but the
 * screen is rebuilt around the Prism language: a full-bleed hero with the
 * artwork and the wordmark, one editorial line, the step content and a single
 * pinned action row (one primary button plus one quiet text button).
 *
 * Every state variable, callback and side effect of the previous
 * implementation is preserved: the host bootstrap still runs with its progress
 * and retry, the runner still owns the distro install, and the finish paths
 * are unchanged.
 */
@Composable
fun OnboardingFlowScreen(
    onFinished: () -> Unit,
    onOpenTerminal: () -> Unit = {},
    onStartDesktop: (distroId: String) -> Unit = {}
) {
    val context = LocalContext.current
    var step by remember { mutableStateOf(OnboardStep.Welcome) }
    var selectedDistroId by remember { mutableStateOf("debian") }
    var theme by remember { mutableStateOf("dark") }

    var percent by remember { mutableIntStateOf(0) }
    var phaseLabel by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf("") }
    var logText by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showLog by remember { mutableStateOf(true) }
    var progressIndeterminate by remember { mutableStateOf(false) }

    val runner = remember { OnboardingInstallRunner(context.applicationContext) }
    DisposableEffect(Unit) {
        onDispose { runner.cancel() }
    }

    fun startInstall() {
        step = OnboardStep.Running
        percent = 0
        phaseLabel = "Starting…"
        detail = ""
        logText = ""
        failed = false
        errorMessage = null
        progressIndeterminate = false
        runner.start(selectedDistroId, theme) { p ->
            percent = p.overallPercent
            phaseLabel = p.phaseLabel
            detail = p.detail
            progressIndeterminate = p.indeterminate
            p.logLine?.let { line ->
                logText = (logText + line + "\n").takeLast(12_000)
            }
            if (p.finished) {
                if (p.failed) {
                    failed = true
                    errorMessage = p.errorMessage
                } else {
                    step = OnboardStep.Done
                }
            }
        }
    }

    // The identity is monochrome: the section accent is the neutral action
    // colour, so every step reads as one system.
    val accent = sectionAccent(AltarisSection.HOME)
    val artRes = remember { StateManager.getHomeArt(context) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        when (step) {
            OnboardStep.Welcome -> WelcomePage(
                artRes = artRes,
                accent = accent,
                onNext = { step = OnboardStep.Consent }
            )
            OnboardStep.Consent -> ConsentPage(
                artRes = artRes,
                accent = accent,
                onBack = { step = OnboardStep.Welcome },
                onNext = { step = OnboardStep.HostSetup }
            )
            OnboardStep.HostSetup -> HostSetupPage(
                artRes = artRes,
                accent = accent,
                onBack = { step = OnboardStep.Consent },
                onNext = { step = OnboardStep.Ready }
            )
            OnboardStep.Ready -> ReadyPage(
                artRes = artRes,
                accent = accent,
                onBack = { step = OnboardStep.HostSetup },
                onInstallDistro = { step = OnboardStep.DistroPick },
                onLater = onFinished
            )
            OnboardStep.DistroPick -> DistroPickPage(
                artRes = artRes,
                accent = accent,
                selectedId = selectedDistroId,
                onSelect = { selectedDistroId = it },
                onBack = { step = OnboardStep.Ready },
                onNext = { step = OnboardStep.Options }
            )
            OnboardStep.Options -> OptionsPage(
                artRes = artRes,
                accent = accent,
                distroId = selectedDistroId,
                theme = theme,
                onTheme = { theme = it },
                onBack = { step = OnboardStep.DistroPick },
                onInstall = {
                    if (SharedStorageAccess.declared(context) && !SharedStorageAccess.granted()) {
                        step = OnboardStep.Storage
                    } else {
                        startInstall()
                    }
                }
            )
            OnboardStep.Storage -> StoragePage(
                artRes = artRes,
                accent = accent,
                onBack = { step = OnboardStep.Options },
                onNext = { startInstall() }
            )
            OnboardStep.Running -> RunningPage(
                artRes = artRes,
                accent = accent,
                percent = percent,
                phaseLabel = phaseLabel,
                detail = detail,
                logText = logText,
                failed = failed,
                errorMessage = errorMessage,
                showLog = showLog,
                indeterminate = progressIndeterminate,
                onToggleLog = { showLog = !showLog },
                onRetry = { startInstall() },
                onBack = {
                    runner.cancel()
                    step = OnboardStep.Options
                }
            )
            OnboardStep.Done -> DonePage(
                artRes = artRes,
                accent = accent,
                distroId = selectedDistroId,
                onHome = onFinished,
                onTerminal = onOpenTerminal,
                onDesktop = { onStartDesktop(selectedDistroId) }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SHELL
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The onboarding shell: a full-bleed hero, one editorial line, the step
 * content and a single action row pinned above the gesture inset.
 */
@Composable
private fun OnboardingShell(
    artRes: Int,
    accent: Color,
    stepLabel: String,
    title: String,
    editorial: String,
    entrance: Boolean,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    primaryText: String,
    primaryEnabled: Boolean = true,
    primaryIcon: ImageVector? = null,
    onPrimary: () -> Unit,
    secondaryText: String? = null,
    onSecondary: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxSize()) {
        OnboardingHero(
            artRes = artRes,
            accent = accent,
            modifier = Modifier.staggerIn(0, entrance)
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Prism.Gutter)
                .padding(top = Prism.Gutter, bottom = Prism.Stack),
            verticalArrangement = Arrangement.spacedBy(Prism.Stack)
        ) {
            Column(modifier = Modifier.staggerIn(1, entrance)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onBack != null) {
                        BackAffordance(onClick = onBack)
                        Spacer(Modifier.width(Prism.Tight))
                    }
                    Text(
                        text = stepLabel.uppercase(),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = title,
                    fontSize = 24.sp,
                    lineHeight = 29.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = editorial,
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(modifier = Modifier.staggerIn(2, entrance)) {
                content()
            }
        }

        OnboardingActionRow(
            accent = accent,
            primaryText = primaryText,
            primaryEnabled = primaryEnabled,
            primaryIcon = primaryIcon,
            onPrimary = onPrimary,
            secondaryText = secondaryText,
            onSecondary = onSecondary,
            modifier = Modifier.staggerIn(3, entrance)
        )
    }
}

/**
 * The full-bleed hero: the artwork in a large, calm well with the Altaris
 * mark and wordmark beside it.
 */
@Composable
private fun OnboardingHero(
    artRes: Int,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(scheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = Prism.Gutter, vertical = Prism.Gutter),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(124.dp)
                    .clip(RoundedCornerShape(Prism.TileRadius))
                    .background(scheme.surfaceVariant)
                    .border(1.dp, scheme.outline, RoundedCornerShape(Prism.TileRadius)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(artRes),
                    contentDescription = "Altaris artwork",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(112.dp)
                )
            }
            Spacer(Modifier.width(Prism.Gutter))
            Column(modifier = Modifier.weight(1f)) {
                AltarisMark(size = 24.dp)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "ALTARIS",
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.4.sp,
                    color = scheme.onSurface
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = "LINUX ON ANDROID",
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 2.sp,
                    color = scheme.onSurfaceVariant
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(scheme.outline)
        )
    }
}

/** The pinned action row: one primary button and one quiet text button. */
@Composable
private fun OnboardingActionRow(
    accent: Color,
    primaryText: String,
    primaryEnabled: Boolean,
    primaryIcon: ImageVector?,
    onPrimary: () -> Unit,
    secondaryText: String?,
    onSecondary: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outline)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Prism.Gutter, vertical = Prism.Tight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Prism.Tight)
        ) {
            if (secondaryText != null && onSecondary != null) {
                QuietAction(text = secondaryText, onClick = onSecondary)
            }
            PrismButton(
                text = primaryText,
                accent = accent,
                icon = primaryIcon,
                enabled = primaryEnabled,
                modifier = Modifier.weight(1f),
                onClick = onPrimary
            )
        }
    }
}

/** A quiet text button — the secondary action of the row. */
@Composable
private fun QuietAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val alpha by animateFloatAsState(
        targetValue = if (pressed) 0.55f else 1f,
        label = "quietPress"
    )
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = modifier
            .heightIn(min = Prism.TouchTarget)
            .clip(RoundedCornerShape(Prism.ChipRadius))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .graphicsLayer { this.alpha = alpha }
            .padding(horizontal = 10.dp, vertical = 12.dp)
    )
}

/** The compact back control used by the steps that keep a history. */
@Composable
private fun BackAffordance(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val alpha by animateFloatAsState(
        targetValue = if (pressed) 0.55f else 1f,
        label = "backPress"
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
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SHARED PIECES
// ─────────────────────────────────────────────────────────────────────────────

/** A monochrome icon plate used as the leading slot of a row. */
@Composable
private fun RowIcon(icon: ImageVector, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
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
 * The progress treatment of the app: a thin, rounded, monochrome line with a
 * quiet track.
 */
@Composable
private fun ProgressLine(
    progress: Float,
    modifier: Modifier = Modifier,
    fill: Color = MaterialTheme.colorScheme.onSurface,
    track: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(2.dp)
            .clip(RoundedCornerShape(1.dp))
            .background(track)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(1.dp))
                .background(fill)
        )
    }
}

/** The consent control of the download notice. */
@Composable
private fun ConsentTile(checked: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .size(28.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (checked) scheme.onSurface else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (checked) scheme.onSurface else scheme.outline,
                shape = RoundedCornerShape(9.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = scheme.surface,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/** The consent row: a check tile plus the notice body. */
@Composable
private fun ConsentRow(
    consented: Boolean,
    body: String,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val alpha by animateFloatAsState(
        targetValue = if (pressed) 0.7f else 1f,
        label = "consentPress"
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Prism.TileRadius))
            .background(if (consented) scheme.onSurface.copy(alpha = 0.06f) else scheme.surface)
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(Prism.TileRadius))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onToggle
            )
            .graphicsLayer { this.alpha = alpha }
            .padding(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        ConsentTile(checked = consented)
        Spacer(Modifier.width(12.dp))
        Text(
            text = body,
            fontSize = 12.5.sp,
            lineHeight = 17.sp,
            color = scheme.onSurface
        )
    }
}

/** The install console: a quiet monospace pane with the runner output. */
@Composable
private fun ConsolePane(
    logText: String,
    modifier: Modifier = Modifier
) {
    val logScroll = rememberScrollState()
    LaunchedEffect(logText) {
        if (logText.isNotEmpty()) {
            logScroll.animateScrollTo(logScroll.maxValue)
        }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp, max = 240.dp)
            .clip(RoundedCornerShape(Prism.TileRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(Prism.TileRadius)
            )
            .padding(12.dp)
            .verticalScroll(logScroll)
    ) {
        Text(
            text = logText.ifBlank { "Initializing installation stream…" },
            fontSize = 11.sp,
            lineHeight = 16.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 1. WELCOME
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun WelcomePage(
    artRes: Int,
    accent: Color,
    onNext: () -> Unit
) {
    val entrance = rememberStaggerEntrance()

    OnboardingShell(
        artRes = artRes,
        accent = accent,
        stepLabel = "Welcome",
        title = "A full Linux desktop, on your phone",
        editorial = "Full Linux desktop on Android — no Termux app required.",
        entrance = entrance,
        primaryText = "Get Started",
        onPrimary = onNext
    ) {
        PrismCard(accent = accent, modifier = Modifier.fillMaxWidth()) {
            PrismRow(
                title = "PRoot & Chroot",
                subtitle = "Run without root in PRoot mode, or at full native speed with Chroot on rooted devices.",
                accent = accent,
                leading = { RowIcon(Icons.Filled.Storage) },
                divider = true
            )
            PrismRow(
                title = "XFCE4 desktop",
                subtitle = "A pre-configured desktop with Altaris themes, icons, wallpapers, and fonts.",
                accent = accent,
                leading = { RowIcon(Icons.Filled.DesktopWindows) },
                divider = true
            )
            PrismRow(
                title = "Embedded terminal & X11",
                subtitle = "Hardware-accelerated X11 display and a multi-tab terminal with Oh My Zsh integration.",
                accent = accent,
                leading = { RowIcon(Icons.Filled.Terminal) }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 2. CONSENT (DOWNLOAD NOTICE)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ConsentPage(
    artRes: Int,
    accent: Color,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    val context = LocalContext.current
    var downloadConsent by remember { mutableStateOf(false) }
    val entrance = rememberStaggerEntrance()

    val downloadsHost = HostBootstrap.downloadsFromRelease(context.packageName)
    val body = if (downloadsHost) {
        "I understand this setup downloads Linux system images (host bootstrap and distro rootfs) from GitHub Releases. Those files are not bundled in the F-Droid APK."
    } else {
        "I understand this setup downloads the selected Linux distro rootfs from GitHub Releases. That archive is not in the base APK."
    }

    OnboardingShell(
        artRes = artRes,
        accent = accent,
        stepLabel = "External downloads",
        title = "What Altaris downloads",
        editorial = "The APK stays small: the Linux images are fetched from GitHub Releases when you continue.",
        entrance = entrance,
        primaryText = "Continue",
        primaryEnabled = downloadConsent,
        onPrimary = onNext,
        secondaryText = "Back",
        onSecondary = onBack
    ) {
        PrismCard(accent = accent, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RowIcon(Icons.Filled.CloudDownload)
                Spacer(Modifier.width(13.dp))
                Text(
                    text = "F-Droid & package notice",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(Prism.Stack))
            Text(
                text = "The F-Droid APK is intentionally lightweight and does not bundle large Linux OS archives. To provide a complete Linux environment, Altaris downloads the required system components directly from GitHub Releases upon continuation:",
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        PrismCard(accent = accent, modifier = Modifier.fillMaxWidth()) {
            PrismRow(
                title = "Host prefix",
                subtitle = "~124 MiB",
                accent = accent,
                leading = { RowIcon(Icons.Filled.Memory) },
                divider = true
            )
            PrismRow(
                title = "Distro rootfs",
                subtitle = "~80-150 MiB",
                accent = accent,
                leading = { RowIcon(Icons.Filled.Layers) }
            )
        }

        ConsentRow(
            consented = downloadConsent,
            body = body,
            onToggle = { downloadConsent = !downloadConsent }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 3. HOST SETUP
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HostSetupPage(
    artRes: Int,
    accent: Color,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    val context = LocalContext.current
    var bootstrapDone by remember { mutableStateOf(TerminalLauncher.isHostSetupDone(context)) }
    var initializing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var statusText by remember { mutableStateOf("") }
    var errorDetail by remember { mutableStateOf<String?>(null) }
    val entrance = rememberStaggerEntrance()

    fun startInit() {
        if (initializing) return
        initializing = true
        failed = false
        errorDetail = null
        statusText = "Extracting host bootstrap…"
        TerminalLauncher.prepareHost(
            context,
            progress = { done, total, phase ->
                statusText = phase
                if (total > 0) progress = (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)
            },
            onDone = { ok ->
                initializing = false
                bootstrapDone = TerminalLauncher.isHostSetupDone(context)
                failed = !bootstrapDone
                errorDetail = if (failed) TerminalLauncher.lastPrepareError else null
                statusText = if (bootstrapDone) "Host environment ready."
                else "Setup failed — check storage space and network, then retry."
            }
        )
    }

    OnboardingShell(
        artRes = artRes,
        accent = accent,
        stepLabel = "Host environment",
        title = "Prepare the embedded prefix",
        editorial = "Initialize the local embedded Linux prefix, PulseAudio server, and terminal toolchain. Nothing else is downloaded until you choose a distro.",
        entrance = entrance,
        primaryText = if (bootstrapDone) "Continue" else "Initialize",
        primaryEnabled = !initializing,
        onPrimary = {
            if (bootstrapDone) onNext() else startInit()
        },
        secondaryText = "Back",
        onSecondary = onBack
    ) {
        PrismCard(accent = accent, modifier = Modifier.fillMaxWidth()) {
            PrismRow(
                title = "Embedded host prefix",
                subtitle = if (bootstrapDone) "Initialized & ready" else "Requires setup",
                accent = accent,
                leading = {
                    RowIcon(if (bootstrapDone) Icons.Filled.CheckCircle else Icons.Filled.Memory)
                },
                trailing = {
                    PrismChip(
                        text = when {
                            bootstrapDone -> "READY"
                            initializing -> "EXTRACTING"
                            failed -> "RETRY"
                            else -> "PENDING"
                        },
                        accent = accent,
                        selected = bootstrapDone
                    )
                }
            )

            if (initializing) {
                Spacer(Modifier.height(Prism.Stack))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = statusText.ifBlank { "Extracting bootstrap…" },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(Prism.Tight))
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.height(Prism.Tight))
                ProgressLine(progress = progress)
            } else if (!bootstrapDone) {
                Spacer(Modifier.height(Prism.Stack))
                Text(
                    text = if (failed) "Setup failed — check storage space and network, then retry."
                    else "Tap below to extract the core userland files and start the embedded runtime.",
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (failed && errorDetail != null) {
                    Spacer(Modifier.height(Prism.Tight))
                    Text(
                        text = errorDetail!!,
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                Spacer(Modifier.height(Prism.Stack))
                Text(
                    text = "Host environment is extracted and operational. PulseAudio and internal session services are active.",
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 4. READY
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ReadyPage(
    artRes: Int,
    accent: Color,
    onBack: () -> Unit,
    onInstallDistro: () -> Unit,
    onLater: () -> Unit
) {
    val entrance = rememberStaggerEntrance()

    OnboardingShell(
        artRes = artRes,
        accent = accent,
        stepLabel = "Ready",
        title = "Altaris is ready",
        editorial = "The host environment is set up. Install a distro now, or add one whenever you want from the Distros tab.",
        entrance = entrance,
        onBack = onBack,
        primaryText = "Install a distro now",
        primaryIcon = Icons.Filled.Download,
        onPrimary = onInstallDistro,
        secondaryText = "Later",
        onSecondary = onLater
    ) {
        PrismCard(accent = accent, modifier = Modifier.fillMaxWidth()) {
            PrismRow(
                title = "Host environment is already initialized",
                accent = accent,
                leading = { RowIcon(Icons.Filled.Memory) },
                divider = true
            )
            PrismRow(
                title = "Distros download only when you choose to install one",
                accent = accent,
                leading = { RowIcon(Icons.Filled.Download) }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 5. DISTRO SELECTION
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DistroPickPage(
    artRes: Int,
    accent: Color,
    selectedId: String,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    var methodTab by remember { mutableStateOf(MethodTab.PROOT) }
    var rootAvailable by remember { mutableStateOf(false) }
    var probingRoot by remember { mutableStateOf(false) }
    var showRootHint by remember { mutableStateOf(false) }
    val entrance = rememberStaggerEntrance()

    fun applyTab(tab: MethodTab) {
        methodTab = tab
        val next = DistroRepository.installableVariant(selectedId, chroot = tab == MethodTab.CHROOT)
        if (next != null && next.id != selectedId) onSelect(next.id)
    }

    fun probeRoot(openChrootIfGranted: Boolean) {
        probingRoot = true
        RootShell.probeRootAvailable(forceClearCache = openChrootIfGranted) { ok ->
            rootAvailable = ok
            probingRoot = false
            if (ok) {
                showRootHint = false
                if (openChrootIfGranted) applyTab(MethodTab.CHROOT)
            } else if (openChrootIfGranted) {
                showRootHint = true
                if (methodTab == MethodTab.CHROOT) applyTab(MethodTab.PROOT)
            }
        }
    }

    LaunchedEffect(Unit) { probeRoot(openChrootIfGranted = false) }

    val forTab = DistroRepository.supportedDistros.filter { distro ->
        if (methodTab == MethodTab.CHROOT) distro.chrootSupported else distro.prootSupported
    }
    val installable = forTab.filter { !it.comingSoon }
    val comingSoon = forTab.filter { it.comingSoon }.sortedBy { it.name }

    OnboardingShell(
        artRes = artRes,
        accent = accent,
        stepLabel = "Distributions",
        title = "Choose a distribution",
        editorial = "Every guest runs in its own namespace — pick the one you want to install first.",
        entrance = entrance,
        primaryText = "Continue",
        primaryEnabled = installable.any { it.id == selectedId },
        onPrimary = onNext,
        secondaryText = "Back",
        onSecondary = onBack
    ) {
        PrismSegmented(
            options = listOf("PRoot", "Chroot"),
            selectedIndex = if (methodTab == MethodTab.PROOT) 0 else 1,
            accent = accent,
            modifier = Modifier.fillMaxWidth(),
            onSelect = { index ->
                val tab = if (index == 0) MethodTab.PROOT else MethodTab.CHROOT
                if (tab == MethodTab.CHROOT && !rootAvailable) {
                    showRootHint = true
                    probeRoot(openChrootIfGranted = true)
                } else {
                    applyTab(tab)
                }
            }
        )

        if (showRootHint && !rootAvailable) {
            NoticeCard(
                icon = Icons.Filled.Warning,
                text = if (probingRoot) "Checking superuser access…"
                else "Grant superuser to Altaris in Magisk/KernelSU, then tap Chroot again."
            )
        }

        PrismCard(accent = accent, modifier = Modifier.fillMaxWidth()) {
            installable.forEachIndexed { index, distro ->
                DistroPickRow(
                    distro = distro,
                    selected = selectedId == distro.id,
                    accent = accent,
                    divider = index < installable.lastIndex,
                    onClick = { onSelect(distro.id) }
                )
            }
        }

        if (comingSoon.isNotEmpty()) {
            PrismCard(accent = accent, modifier = Modifier.fillMaxWidth()) {
                comingSoon.forEachIndexed { index, distro ->
                    PrismRow(
                        title = distro.name,
                        subtitle = distro.description,
                        accent = accent,
                        leading = { DistroMark(distro) },
                        trailing = { PrismChip(text = "Soon", accent = accent) },
                        divider = index < comingSoon.lastIndex
                    )
                }
            }
        }
    }
}

/** The distro logo plate used in the picker rows. */
@Composable
private fun DistroMark(distro: Distro, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .size(34.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.surfaceVariant)
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (distro.iconRes != null) {
            Image(
                painter = painterResource(distro.iconRes),
                contentDescription = "${distro.name} logo",
                modifier = Modifier.size(20.dp)
            )
        } else {
            Text(
                text = distro.name.firstOrNull()?.uppercase() ?: "?",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurfaceVariant
            )
        }
    }
}

/** A selectable distro row: the selected one carries a check, the rest a method chip. */
@Composable
private fun DistroPickRow(
    distro: Distro,
    selected: Boolean,
    accent: Color,
    divider: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    PrismRow(
        title = distro.name,
        subtitle = distro.description,
        accent = accent,
        leading = {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (selected) scheme.onSurface.copy(alpha = 0.14f) else scheme.surfaceVariant
                    )
                    .border(
                        width = 1.dp,
                        color = if (selected) scheme.onSurface.copy(alpha = 0.55f) else scheme.outlineVariant,
                        shape = RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (distro.iconRes != null) {
                    Image(
                        painter = painterResource(distro.iconRes),
                        contentDescription = "${distro.name} logo",
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Text(
                        text = distro.name.firstOrNull()?.uppercase() ?: "?",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurfaceVariant
                    )
                }
            }
        },
        trailing = {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(scheme.onSurface),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = scheme.surface,
                        modifier = Modifier.size(13.dp)
                    )
                }
            } else {
                PrismChip(
                    text = if (distro.chrootSupported) "CHROOT" else "PROOT",
                    accent = accent
                )
            }
        },
        onClick = onClick,
        divider = divider
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// 6. OPTIONS
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun OptionsPage(
    artRes: Int,
    accent: Color,
    distroId: String,
    theme: String,
    onTheme: (String) -> Unit,
    onBack: () -> Unit,
    onInstall: () -> Unit
) {
    val distro = DistroRepository.supportedDistros.find { it.id == distroId }
    val distroIcon = distro?.iconRes
    var downloadConsent by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val entrance = rememberStaggerEntrance()

    val consentBody = if (HostBootstrap.downloadsFromRelease(context.packageName)) {
        "I understand this setup downloads Linux system images (host bootstrap and distro rootfs) from GitHub Releases. Those files are not bundled in the F-Droid APK."
    } else {
        "I understand this setup downloads the selected Linux distro rootfs from GitHub Releases. That archive is not in the base APK."
    }

    val items = listOf(
        when {
            distroId.startsWith("alpine") -> "Alpine 3.24 minirootfs"
            distroId.startsWith("fedora") -> "Fedora 44 container rootfs"
            distroId.startsWith("rocky") -> "Rocky Linux 9 container rootfs"
            distroId.startsWith("centos") -> "CentOS Stream 9 container rootfs"
            distroId.startsWith("void") -> "Void Linux (glibc aarch64) rootfs"
            distroId.startsWith("opensuse") -> "openSUSE Tumbleweed rootfs"
            else -> "Debian 13 rootfs"
        },
        "XFCE4 lightweight desktop environment",
        if (distroId.startsWith("fedora") || distroId.startsWith("rocky") ||
            distroId.startsWith("centos") || distroId.startsWith("void") ||
            distroId.startsWith("opensuse")
        )
            "Mesa / VirGL 3D hardware acceleration" else null,
        "Altaris themes, wallpapers, and Nerd fonts",
        "Embedded X11 display server + Zsh terminal"
    ).filterNotNull()

    OnboardingShell(
        artRes = artRes,
        accent = accent,
        stepLabel = "Options",
        title = "Desktop options",
        editorial = "The rootfs and the desktop stack are downloaded together — the theme decides how the guest looks.",
        entrance = entrance,
        primaryText = "Install now",
        primaryEnabled = downloadConsent,
        primaryIcon = Icons.Filled.Download,
        onPrimary = onInstall,
        secondaryText = "Back",
        onSecondary = onBack
    ) {
        PrismCard(accent = accent, modifier = Modifier.fillMaxWidth()) {
            PrismRow(
                title = distro?.name ?: distroId,
                subtitle = "Base XFCE4 desktop with Altaris customizations",
                accent = accent,
                leading = {
                    if (distroIcon != null) {
                        Image(
                            painter = painterResource(distroIcon),
                            contentDescription = null,
                            modifier = Modifier.size(26.dp)
                        )
                    } else {
                        RowIcon(Icons.Filled.Layers)
                    }
                }
            )
        }

        PrismCard(accent = accent, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Appearance theme",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Prism.Tight))
            PrismSegmented(
                options = listOf("Dark", "Light"),
                selectedIndex = if (theme == "dark") 0 else 1,
                accent = accent,
                modifier = Modifier.fillMaxWidth(),
                onSelect = { index -> onTheme(if (index == 0) "dark" else "light") }
            )
        }

        PrismCard(accent = accent, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RowIcon(Icons.Filled.Layers)
                Spacer(Modifier.width(13.dp))
                Text(
                    text = "Package manifest included",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(Prism.Stack))
            items.forEach { item ->
                Row(
                    modifier = Modifier.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                    Spacer(Modifier.width(Prism.Tight))
                    Text(
                        text = item,
                        fontSize = 12.5.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (distroId.contains("chroot")) {
            NoticeCard(
                icon = Icons.Filled.Warning,
                text = "Rooted path: Magisk, KernelSU, or APatch superuser is required for Chroot guest execution."
            )
        }

        ConsentRow(
            consented = downloadConsent,
            body = consentBody,
            onToggle = { downloadConsent = !downloadConsent }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 7. STORAGE ACCESS
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StoragePage(
    artRes: Int,
    accent: Color,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    val context = LocalContext.current
    var resumeKey by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumeKey++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val granted = remember(resumeKey) { SharedStorageAccess.granted() }
    val entrance = rememberStaggerEntrance()

    OnboardingShell(
        artRes = artRes,
        accent = accent,
        stepLabel = "File access",
        title = "Open your files from Linux",
        editorial = "Your Linux distros can read and write your phone's storage, so you can open and save files from Linux apps.",
        entrance = entrance,
        onBack = onBack,
        primaryText = if (granted) "Continue" else "Grant access",
        primaryIcon = if (granted) null else Icons.Filled.FolderShared,
        onPrimary = if (granted) onNext else { { SharedStorageAccess.openSettings(context) } },
        secondaryText = if (granted) null else "Skip for now",
        onSecondary = if (granted) null else onNext
    ) {
        PrismCard(accent = accent, modifier = Modifier.fillMaxWidth()) {
            PrismRow(
                title = "Open files from Linux apps",
                accent = accent,
                leading = { RowIcon(Icons.Filled.FolderOpen) },
                divider = true
            )
            PrismRow(
                title = "Save to Downloads and Documents",
                accent = accent,
                leading = { RowIcon(Icons.Filled.Save) },
                divider = true
            )
            PrismRow(
                title = "Use USB drives and SD cards",
                accent = accent,
                leading = { RowIcon(Icons.Filled.Usb) }
            )
        }

        if (granted) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Prism.TileRadius))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(Prism.TileRadius)
                    )
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(Prism.Tight))
                Text(
                    text = "Access granted",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        } else {
            Text(
                text = "You can change this later in Settings > Shared storage.",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 8. INSTALL PROGRESS
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun RunningPage(
    artRes: Int,
    accent: Color,
    percent: Int,
    phaseLabel: String,
    detail: String,
    logText: String,
    failed: Boolean,
    errorMessage: String?,
    showLog: Boolean,
    indeterminate: Boolean,
    onToggleLog: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit
) {
    val entrance = rememberStaggerEntrance()

    OnboardingShell(
        artRes = artRes,
        accent = accent,
        stepLabel = "Installing",
        title = if (failed) "Setup failed" else "Setting up environment",
        editorial = if (failed) "An error occurred during execution — retry or go back to the options."
        else "Downloading the rootfs and configuring the guest packages.",
        entrance = entrance,
        primaryText = if (failed) "Retry setup" else "Installing…",
        primaryEnabled = failed,
        primaryIcon = if (failed) Icons.Filled.Refresh else null,
        onPrimary = onRetry,
        secondaryText = "Back to options",
        onSecondary = onBack
    ) {
        PrismCard(accent = accent, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = phaseLabel.ifBlank { "Working…" },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(Prism.Tight))
                Text(
                    text = if (indeterminate && !failed) "—" else "$percent%",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(Prism.Stack))
            ProgressLine(
                progress = when {
                    failed -> 1f
                    indeterminate -> 0.3f
                    else -> percent / 100f
                },
                fill = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(Prism.Tight))
                Text(
                    text = detail,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (failed && errorMessage != null) {
                Spacer(Modifier.height(Prism.Tight))
                Text(
                    text = errorMessage,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Terminal output",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            QuietAction(
                text = if (showLog) "Hide console" else "Show console",
                onClick = onToggleLog
            )
        }

        if (showLog) {
            ConsolePane(logText = logText)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 9. DONE
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DonePage(
    artRes: Int,
    accent: Color,
    distroId: String,
    onHome: () -> Unit,
    onTerminal: () -> Unit,
    onDesktop: () -> Unit
) {
    val distro = DistroRepository.supportedDistros.find { it.id == distroId }
    val distroIcon = distro?.iconRes
    val displayName = distro?.name?.removeSuffix(" (Rooted)") ?: distroId
    val isChroot = distro?.isChrootCard() == true
    val entrance = rememberStaggerEntrance()

    OnboardingShell(
        artRes = artRes,
        accent = accent,
        stepLabel = "Complete",
        title = "You're ready",
        editorial = "$displayName base desktop is installed. Launch your graphical session or open an interactive shell.",
        entrance = entrance,
        primaryText = "Start desktop",
        primaryIcon = Icons.Filled.DesktopWindows,
        onPrimary = onDesktop,
        secondaryText = "Open terminal",
        onSecondary = onTerminal
    ) {
        PrismCard(accent = accent, modifier = Modifier.fillMaxWidth()) {
            PrismRow(
                title = displayName,
                subtitle = if (isChroot) "Chroot guest" else "PRoot guest",
                accent = accent,
                leading = {
                    if (distroIcon != null) {
                        Image(
                            painter = painterResource(distroIcon),
                            contentDescription = displayName,
                            modifier = Modifier.size(24.dp)
                        )
                    } else {
                        RowIcon(Icons.Filled.CheckCircle)
                    }
                },
                trailing = {
                    PrismChip(
                        text = if (isChroot) "CHROOT GUEST" else "PROOT GUEST",
                        accent = accent,
                        selected = true
                    )
                }
            )
        }

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            QuietAction(text = "Go to dashboard", onClick = onHome)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// NOTICE CARD
// ─────────────────────────────────────────────────────────────────────────────

/** A quiet notice — used for the root hint and the chroot requirement. */
@Composable
private fun NoticeCard(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Prism.TileRadius))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(Prism.TileRadius)
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(Prism.Tight))
        Text(
            text = text,
            fontSize = 12.5.sp,
            lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
