package com.altaris.linux.ui.install

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altaris.linux.ui.components.PrismButton
import com.altaris.linux.ui.components.PrismCard
import com.altaris.linux.ui.theme.AltarisSection
import com.altaris.linux.ui.theme.Prism
import com.altaris.linux.ui.theme.sectionAccent

/**
 * Shared install progress UI for the Distros wizard.
 *
 * Monochrome by design: one card carries the phase, the percentage and a
 * thin rounded progress line; the console keeps its monospace pane; the
 * actions are one primary button plus one quiet text button.
 *
 * [onCancel] renders an explicit "Cancel install" action while running — back
 * navigation leaves the install running in the background (task bar takes
 * over), so cancelling must be a deliberate tap.
 */
@Composable
fun InstallProgressPanel(
    percent: Int,
    phaseLabel: String,
    detail: String,
    logText: String,
    failed: Boolean,
    errorMessage: String?,
    showLog: Boolean,
    onToggleLog: () -> Unit,
    onRetry: () -> Unit,
    onBack: (() -> Unit)? = null,
    onDone: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    isDone: Boolean = false,
    /** No measurable percent — the bar shows a quiet unknown sliver. */
    indeterminate: Boolean = false,
    modifier: Modifier = Modifier
) {
    val logScroll = rememberScrollState()
    LaunchedEffect(logText) {
        if (logText.isNotEmpty()) {
            logScroll.animateScrollTo(logScroll.maxValue)
        }
    }

    val accent = sectionAccent(AltarisSection.DISTROS)
    val scheme = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(horizontal = Prism.Gutter)
            .padding(top = Prism.Gutter, bottom = Prism.Gutter)
    ) {
        Text(
            text = when {
                failed -> "Setup failed"
                isDone -> "Install complete"
                else -> "Setting up environment"
            },
            fontSize = 24.sp,
            lineHeight = 29.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.4).sp,
            color = scheme.onBackground
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = when {
                failed -> "An error occurred during execution"
                isDone -> "All base packages and configs are installed"
                else -> "Downloading rootfs & configuring packages"
            },
            fontSize = 13.sp,
            color = scheme.onSurfaceVariant
        )

        Spacer(Modifier.height(Prism.Stack))

        // Progress card: phase, percentage and the thin progress line.
        PrismCard(accent = accent, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (phaseLabel.isNotBlank()) phaseLabel else "Working…",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(Prism.Tight))
                Text(
                    text = if (indeterminate && !failed && !isDone) "—" else "$percent%",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (failed) scheme.error else scheme.onSurface
                )
            }

            Spacer(Modifier.height(Prism.Stack))

            ProgressLine(
                progress = when {
                    failed -> 1f
                    isDone -> 1f
                    indeterminate -> 0.3f
                    else -> percent / 100f
                },
                fill = if (failed) scheme.error else scheme.onSurface
            )

            if (detail.isNotBlank()) {
                Spacer(Modifier.height(Prism.Tight))
                Text(
                    text = detail,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (failed && errorMessage != null) {
                Spacer(Modifier.height(Prism.Tight))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = scheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(Prism.Tight))
                    Text(
                        text = errorMessage,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = scheme.error
                    )
                }
            }

            if (isDone) {
                Spacer(Modifier.height(Prism.Tight))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = scheme.onSurface,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(Prism.Tight))
                    Text(
                        text = "The guest is ready to launch.",
                        fontSize = 12.sp,
                        color = scheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(Prism.Stack))

        // Console toggle + the live log pane.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Terminal output",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurfaceVariant
            )
            QuietAction(
                text = if (showLog) "Hide console" else "Show console",
                onClick = onToggleLog
            )
        }

        Spacer(Modifier.height(Prism.Tight))

        if (showLog) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Prism.TileRadius))
                    .background(scheme.surfaceVariant)
                    .border(
                        width = 1.dp,
                        color = scheme.outlineVariant,
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
                    color = scheme.onSurfaceVariant
                )
            }
        } else {
            Spacer(Modifier.weight(1f))
        }

        // Background continues after leaving: cancelling needs an explicit tap.
        // Only shown when the host screen supports leave-in-background (onCancel
        // wired); onboarding keeps its back-cancels behavior and passes null.
        if (!failed && !isDone && onCancel != null) {
            Spacer(Modifier.height(Prism.Tight))
            Text(
                text = "Going back keeps this install running — watch the live " +
                    "install bar at the top of the app.",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(Prism.Stack))

        // Actions: one primary button plus one quiet text button.
        when {
            failed -> {
                PrismButton(
                    text = "Retry setup",
                    accent = accent,
                    icon = Icons.Filled.Refresh,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onRetry
                )
                if (onBack != null) {
                    Spacer(Modifier.height(Prism.Tight))
                    QuietAction(
                        text = "Back to configuration",
                        onClick = onBack,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            isDone && onDone != null -> {
                PrismButton(
                    text = "Continue",
                    accent = accent,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onDone
                )
            }

            !isDone && onCancel != null -> {
                QuietAction(
                    text = "Cancel install",
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
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

/** A quiet text button — the secondary action of a screen. */
@Composable
private fun QuietAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
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
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 10.dp, vertical = 12.dp)
    )
}

/** Shared dark/light theme selectable card for install wizards. */
@Composable
fun InstallThemePickRow(
    id: String,
    title: String,
    desc: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Prism.TouchTarget)
            .clip(RoundedCornerShape(Prism.TileRadius))
            .background(
                if (selected) scheme.onSurface.copy(alpha = 0.10f) else scheme.surface
            )
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) scheme.onSurface.copy(alpha = 0.55f) else scheme.outlineVariant,
                shape = RoundedCornerShape(Prism.TileRadius)
            )
            .clickable(onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(if (selected) scheme.onSurface else Color.Transparent)
                .border(
                    width = 1.dp,
                    color = if (selected) scheme.onSurface else scheme.outline,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = scheme.surface,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = scheme.onSurface
            )
            Text(
                text = desc,
                fontSize = 12.sp,
                color = scheme.onSurfaceVariant
            )
        }
    }
}
