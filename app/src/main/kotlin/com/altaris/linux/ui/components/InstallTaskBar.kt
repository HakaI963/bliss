package com.altaris.linux.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altaris.linux.core.utils.InstallationQueueManager
import com.altaris.linux.core.utils.InstallationState
import com.altaris.linux.ui.theme.AltarisDanger
import com.altaris.linux.ui.theme.FrostHairline
import com.altaris.linux.ui.theme.StatusDot
import com.altaris.linux.ui.theme.TextMuted
import com.altaris.linux.ui.theme.TextPrimary
import com.altaris.linux.ui.theme.TextSecondary
import kotlinx.coroutines.delay

/**
 * Compact live install status bar rendered directly under the top bar while
 * app chrome is visible (Home / Distros / Settings tabs).
 *
 * Observes [InstallationQueueManager.installState], which
 * [com.altaris.linux.core.install.OnboardingInstallRunner] updates on every
 * progress tick — so the bar stays honest while the user browses elsewhere:
 *  - installing → pulsing progress line + phase + percent, tap opens the panel
 *  - cancelled  → "Install cancelled — tap for details", dismissible
 *  - failed     → "Install failed — tap for logs", dismissible
 *  - done       → brief "Installed ✓", then auto-hides
 *  - idle       → renders nothing (zero height)
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
            dotColor = TextMuted,
            textColor = TextSecondary,
            onClick = openDetails,
            onDismiss = onDismiss,
            modifier = modifier
        )
        state.failed -> TerminalBar(
            label = "Install failed — tap for logs",
            dotColor = AltarisDanger,
            textColor = AltarisDanger,
            onClick = openDetails,
            onDismiss = onDismiss,
            modifier = modifier
        )
        state.done -> TerminalBar(
            label = "Installed ✓",
            dotColor = StatusDot,
            textColor = TextPrimary,
            onClick = openDetails,
            onDismiss = onDismiss,
            modifier = modifier
        )
        else -> Unit
    }
}

@Composable
private fun InstallBarShell(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.92f))
            .clickable(onClick = onClick)
    ) {
        content()
        // Hairline so the strip reads as part of the chrome.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(FrostHairline)
        )
    }
}

@Composable
private fun InstallingBar(
    state: InstallationState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val percent = state.overallPercent.coerceIn(0, 100)
    val hasPercent = state.overallPercent in 0..100
    val pulse by rememberInfiniteTransition(label = "taskBarPulse").animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "taskBarPulseAlpha"
    )
    val phaseLine = listOf(state.phaseLabel, state.detail)
        .filter { it.isNotBlank() }
        .joinToString(" — ")

    InstallBarShell(onClick = onClick, modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(TextPrimary.copy(alpha = pulse))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Installing ${state.currentTaskName}",
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (hasPercent) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "$percent%",
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            // Shimmer track: unknown percent pulses an indeterminate sliver.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.08f))
            ) {
                val fraction = if (hasPercent) percent / 100f else 0.25f
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction.coerceIn(0.05f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(TextPrimary.copy(alpha = pulse))
                )
            }
            if (phaseLine.isNotBlank()) {
                Spacer(Modifier.height(5.dp))
                Text(
                    text = phaseLine,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun TerminalBar(
    label: String,
    dotColor: Color,
    textColor: Color,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    InstallBarShell(onClick = onClick, modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp)
                .height(36.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                color = textColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Dismiss install status",
                    tint = TextMuted,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
