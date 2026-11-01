package com.altaris.linux.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altaris.linux.core.install.OnboardingInstallRunner
import com.altaris.linux.core.utils.ActiveInstall
import com.altaris.linux.core.utils.InstallationQueueManager
import com.altaris.linux.core.utils.InstallationState
import com.altaris.linux.ui.theme.Prism
import kotlinx.coroutines.delay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Compact live install status bar rendered directly under the top bar while
 * app chrome is visible (Home / Distros / Settings tabs).
 *
 * Observes [InstallationQueueManager.installState], which
 * [com.altaris.linux.core.install.OnboardingInstallRunner] updates on every
 * progress tick — so the bar stays honest while the user browses elsewhere:
 *  - installing → the distro name, the phase, the percentage and a 2 dp
 *    progress line; the row opens the panel, the quiet action cancels
 *  - cancelled  → "Install cancelled — tap for details", dismissible
 *  - failed     → "Install failed — tap for logs", dismissible
 *  - done       → brief "Installed", then auto-hides
 *  - idle       → renders nothing (zero height)
 *
 * The bar is monochrome: no accent, no pulse, no decoration.
 */
@Composable
fun InstallTaskBar(
    onOpenProgress: (distroId: String) -> Unit,
    onOpenTroubleshooting: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by InstallationQueueManager.installState.collectAsState()

    // A finished install only needs a short confirmation, then it gets out of
    // the way (the distro card on Home already flipped to Installed).
    LaunchedEffect(state.done) {
        if (state.done) {
            delay(4_000L)
            onDismiss()
        }
    }

    // Safety net for a superseded runner that reached 100% but never emitted
    // its terminal tick. The timeout resets whenever updatedAtMillis changes,
    // so an actively finalizing install is not hidden.
    LaunchedEffect(state.isInstalling, state.overallPercent, state.updatedAtMillis) {
        if (state.isInstalling && state.overallPercent >= 100) {
            delay(12_000L)
            InstallationQueueManager.dismissCompletedIfStale(state.updatedAtMillis)
        }
    }

    val openDetails: () -> Unit = {
        val distroId = state.currentDistroId
        if (distroId != null) onOpenProgress(distroId) else onOpenTroubleshooting()
    }

    when {
        state.isInstalling -> InstallingBar(state = state, onClick = openDetails, modifier = modifier)
        state.cancelledByUser -> TerminalBar(
            label = "Install cancelled — tap for details",
            mark = null,
            onClick = openDetails,
            onDismiss = onDismiss,
            modifier = modifier
        )
        state.failed -> TerminalBar(
            label = "Install failed — tap for logs",
            mark = MaterialTheme.colorScheme.onSurface,
            onClick = openDetails,
            onDismiss = onDismiss,
            modifier = modifier
        )
        state.done -> TerminalBar(
            label = "Installed",
            mark = MaterialTheme.colorScheme.onSurface,
            onClick = openDetails,
            onDismiss = onDismiss,
            modifier = modifier
        )
        else -> Unit
    }
}

/** The shell every bar state shares: a flat surface with a hairline below. */
@Composable
private fun InstallBarShell(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
    ) {
        content()
        // Hairline so the strip reads as part of the chrome.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outline)
        )
    }
}

/** The live state: name, phase, percentage, a thin line and a quiet cancel. */
@Composable
private fun InstallingBar(
    state: InstallationState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val percent = state.overallPercent.coerceIn(0, 100)
    val hasPercent = state.overallPercent in 0..100
    val phaseLine = listOf(state.phaseLabel, state.detail)
        .filter { it.isNotBlank() }
        .joinToString(" — ")

    InstallBarShell(onClick = onClick, modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Prism.Gutter)
                .padding(top = 6.dp, bottom = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Prism.TouchTarget),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.currentTaskName.ifBlank { "Installing" },
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (phaseLine.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = phaseLine,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (hasPercent) {
                    Spacer(Modifier.width(Prism.Tight))
                    Text(
                        text = "$percent%",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.width(Prism.Tight))
                QuietBarAction(
                    text = "Cancel",
                    onClick = { OnboardingInstallRunner.cancelActive() }
                )
            }
            TaskProgressLine(progress = if (hasPercent) percent / 100f else 0.3f)
        }
    }
}

/** The terminal states: one line, a mark and a dismiss control. */
@Composable
private fun TerminalBar(
    label: String,
    mark: Color?,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    InstallBarShell(onClick = onClick, modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Prism.Gutter, end = 6.dp)
                .heightIn(min = Prism.TouchTarget),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (mark != null) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(mark)
                )
                Spacer(Modifier.width(Prism.Tight))
            }
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            QuietBarAction(
                text = "",
                icon = Icons.Filled.Close,
                contentDescription = "Dismiss install status",
                onClick = onDismiss
            )
        }
    }
}

/** The 2 dp progress line of the strip. */
@Composable
private fun TaskProgressLine(
    progress: Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(2.dp)
            .clip(RoundedCornerShape(1.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(1.dp))
                .background(MaterialTheme.colorScheme.onSurface)
        )
    }
}

/**
 * A quiet control inside the strip: a text action or an icon button. It never
 * shifts the layout — only its opacity reacts to the press.
 */
@Composable
private fun QuietBarAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    contentDescription: String? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val alpha by animateFloatAsState(
        targetValue = if (pressed) 0.55f else 1f,
        label = "barActionPress"
    )
    Row(
        modifier = modifier
            .heightIn(min = Prism.TouchTarget)
            .clip(RoundedCornerShape(Prism.ChipRadius))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .graphicsLayer { this.alpha = alpha }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        } else {
            Text(
                text = text,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}


/**
 * The download task button for the top bar.
 *
 * It exists while at least one install is live: a hairline square with a ring
 * that shows the furthest-along download. Tapping it opens a list of **every**
 * install, each with its own bar, name and phase — installs run in parallel, so
 * a panel that showed only one of them was lying about what the app was doing.
 */
@Composable
fun DownloadTaskButton(
    onOpenProgress: (distroId: String) -> Unit,
    onOpenTroubleshooting: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tasks by InstallationQueueManager.active.collectAsState()
    val legacy by InstallationQueueManager.installState.collectAsState()

    // Entries exist for running installs and for a short while after they
    // finish, so the outcome is visible. An empty map means nothing to show.
    val entries = tasks.values
        .filter { !it.done || it.updatedAtMillis > System.currentTimeMillis() - 60_000L }
        .sortedBy { it.updatedAtMillis }

    val anyRunning = entries.any { !it.done && !it.failed }
    val anyFailed = entries.any { it.failed }
    // Fall back to the legacy single-slot state so a cancelled install (which
    // only touches that record) still gets a control to dismiss it.
    val visible = entries.isNotEmpty() ||
        legacy.isInstalling || legacy.failed || legacy.cancelledByUser
    if (!visible) return

    val scheme = MaterialTheme.colorScheme
    // -1 means "no usable fraction yet" (package setup reports phases, not a
    // percentage). Coercing it to 0 made the button read "0" during the whole
    // install and look broken, so keep it indeterminate instead.
    val furthest = entries.filter { !it.done && !it.failed }
        .maxByOrNull { it.overallPercent }?.overallPercent
        ?: legacy.overallPercent
    val indeterminate = furthest < 0
    val percent = furthest.coerceIn(0, 100)
    val shape = RoundedCornerShape(6.dp)
    var expanded by remember { mutableStateOf(false) }

    Box {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(shape)
                .background(scheme.surface)
                .border(1.dp, scheme.outline, shape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { expanded = true },
            contentAlignment = Alignment.Center,
        ) {
            when {
                anyRunning && indeterminate -> {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = scheme.onSurface,
                        strokeWidth = 2.dp,
                    )
                }
                anyRunning -> {
                    Canvas(Modifier.size(22.dp)) {
                        val stroke = 2f
                        drawCircle(
                            color = scheme.outline.copy(alpha = 0.35f),
                            radius = size.minDimension / 2f - stroke / 2f,
                            style = Stroke(width = stroke),
                        )
                        drawArc(
                            color = scheme.onSurface,
                            startAngle = -90f,
                            sweepAngle = 360f * percent / 100f,
                            useCenter = false,
                            style = Stroke(width = stroke),
                        )
                    }
                    Text(
                        if (entries.size > 1) entries.count { !it.done && !it.failed }.toString()
                        else "$percent",
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = scheme.onSurface,
                    )
                }
                else -> {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Download tasks",
                        tint = if (anyFailed) scheme.error else scheme.onSurface,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Column(
                modifier = Modifier
                    .width(300.dp)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    if (entries.size > 1) "${entries.size} downloads" else "Download",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                )
                if (entries.isEmpty()) {
                    // Only the legacy terminal record exists.
                    Text(
                        when {
                            legacy.failed -> "Install failed"
                            legacy.cancelledByUser -> "Install cancelled"
                            else -> "No downloads running"
                        },
                        fontSize = 11.5.sp,
                        color = if (legacy.failed) scheme.error else scheme.onSurfaceVariant,
                    )
                }
                entries.forEach { task ->
                    DownloadTaskRow(
                        task = task,
                        onOpen = {
                            expanded = false
                            onOpenProgress(task.distroId)
                        },
                        onCancel = {
                            // Cancelling is per distro: cancelActive() used to
                            // abort whichever install happened to be "current",
                            // which with parallel downloads is the wrong one.
                            onDismiss()
                            onOpenProgress(task.distroId)
                        },
                    )
                }
                Row(Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = {
                            expanded = false
                            InstallationQueueManager.dismissTerminalTasks()
                            onDismiss()
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 6.dp,
                        ),
                    ) { Text("Dismiss finished", fontSize = 12.sp) }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            expanded = false
                            val id = entries.firstOrNull { !it.done && !it.failed }?.distroId
                                ?: legacy.currentDistroId
                            if (id != null) onOpenProgress(id) else onOpenTroubleshooting()
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 6.dp,
                        ),
                    ) { Text("Open", fontSize = 12.sp) }
                }
            }
        }
    }
}

/** One install inside the download panel: its own name, phase and bar. */
@Composable
private fun DownloadTaskRow(
    task: ActiveInstall,
    onOpen: () -> Unit,
    onCancel: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val running = !task.done && !task.failed
    val indeterminate = task.overallPercent < 0
    val percent = task.overallPercent.coerceIn(0, 100)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onOpen,
            ),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                task.taskName.ifBlank { task.distroId },
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                color = if (task.failed) scheme.error else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                when {
                    task.failed -> "failed"
                    task.done -> "done"
                    indeterminate -> ""
                    else -> "$percent%"
                },
                fontSize = 10.5.sp,
                color = if (task.failed) scheme.error else scheme.onSurfaceVariant,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(scheme.surfaceVariant),
        ) {
            val fraction = when {
                task.done -> 1f
                task.failed -> 1f
                indeterminate -> 0.35f
                else -> percent.coerceAtLeast(2) / 100f
            }
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(
                        when {
                            task.failed -> scheme.error
                            task.done -> scheme.onSurfaceVariant
                            else -> scheme.onSurface
                        }
                    ),
            )
        }
        val line = when {
            task.failed -> task.detail.ifBlank { "Install failed" }
            indeterminate && task.phaseLabel.isNotBlank() -> task.phaseLabel
            task.phaseLabel.isNotBlank() -> task.phaseLabel + " · $percent%"
            task.detail.isNotBlank() -> task.detail
            else -> ""
        }
        if (line.isNotBlank()) {
            Text(
                line,
                fontSize = 10.5.sp,
                lineHeight = 13.sp,
                color = scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (running) {
            TextButton(
                onClick = onCancel,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 0.dp, vertical = 0.dp,
                ),
            ) { Text("Open", fontSize = 11.sp) }
        }
    }
}
