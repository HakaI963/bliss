package com.altaris.linux.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import com.altaris.linux.core.utils.InstallationQueueManager
import com.altaris.linux.core.utils.InstallationState
import com.altaris.linux.ui.theme.Prism
import kotlinx.coroutines.delay

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
            mark = MaterialTheme.colorScheme.error,
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
