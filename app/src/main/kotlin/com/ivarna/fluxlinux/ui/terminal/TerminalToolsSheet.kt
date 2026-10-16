package com.ivarna.fluxlinux.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivarna.fluxlinux.ui.theme.FolioBackground
import com.ivarna.fluxlinux.ui.theme.FolioDanger
import com.ivarna.fluxlinux.ui.theme.FrostHairline
import com.ivarna.fluxlinux.ui.theme.FrostSurface
import com.ivarna.fluxlinux.ui.theme.TextMuted
import com.ivarna.fluxlinux.ui.theme.TextPrimary
import com.ivarna.fluxlinux.ui.theme.TextSecondary

// Monochrome frost metrics shared by every tile — 48dp tall keeps >= 44dp touch targets.
private val ToolTileShape = RoundedCornerShape(12.dp)
private val ToolEdgeWidth = 0.75.dp
private val ToolTileHeight = 48.dp

/** Destructive actions that must ask before running (session processes are killed). */
private enum class TerminalToolConfirm { SESSION, ALL }

/**
 * Quality-of-life "tools" bottom sheet for the interactive terminal.
 *
 * Monochrome frost surface (FrostSurface tiles + hairline borders, no color);
 * every action is wired to existing APIs by TerminalScreen — this composable is
 * presentation + confirm dialogs only. Keys go through the same
 * [TerminalKeyInjector] path as the ExtraKeys toolbar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalToolsSheet(
    sessionTitle: String?,
    hasSession: Boolean,
    sessionCount: Int,
    extraKeysVisible: Boolean,
    fontSize: Int,
    canRestart: Boolean,
    fontMin: Int,
    fontMax: Int,
    onDismissRequest: () -> Unit,
    onPaste: () -> Unit,
    onCopyScreen: () -> Unit,
    onCtrlC: () -> Unit,
    onCtrlD: () -> Unit,
    onSendTab: () -> Unit,
    onSendEscape: () -> Unit,
    onClearScreen: () -> Unit,
    onRestart: () -> Unit,
    onFontSizeChange: (Int) -> Unit,
    onToggleExtraKeys: () -> Unit,
    onCloseSession: () -> Unit,
    onCloseAllSessions: () -> Unit
) {
    var confirm by remember { mutableStateOf<TerminalToolConfirm?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "Terminal tools",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = sessionTitle?.takeIf { it.isNotBlank() }?.let { "Session · $it" }
                    ?: "No active session",
                fontSize = 12.sp,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )

            // ── Quick keys (same inject path as the ExtraKeys toolbar) ───────
            ToolSectionLabel("QUICK KEYS")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ToolTile("Ctrl C", onClick = onCtrlC, enabled = hasSession, modifier = Modifier.weight(1f))
                ToolTile("Ctrl D", onClick = onCtrlD, enabled = hasSession, modifier = Modifier.weight(1f))
                ToolTile("TAB", onClick = onSendTab, enabled = hasSession, modifier = Modifier.weight(1f))
                ToolTile("ESC", onClick = onSendEscape, enabled = hasSession, modifier = Modifier.weight(1f))
            }

            // ── Clipboard + screen buffer ────────────────────────────────────
            ToolSectionLabel("CLIPBOARD & SCREEN")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ToolTile(
                    "Paste",
                    icon = Icons.Default.ContentPaste,
                    onClick = onPaste,
                    enabled = hasSession,
                    modifier = Modifier.weight(1f)
                )
                ToolTile(
                    "Copy screen",
                    icon = Icons.Default.ContentCopy,
                    onClick = onCopyScreen,
                    enabled = hasSession,
                    modifier = Modifier.weight(1f)
                )
                ToolTile(
                    "Clear",
                    icon = Icons.Default.Terminal,
                    onClick = onClearScreen,
                    enabled = hasSession,
                    modifier = Modifier.weight(1f)
                )
            }

            // ── Session lifecycle ────────────────────────────────────────────
            ToolSectionLabel("SESSION")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ToolTile(
                    "Restart",
                    icon = Icons.Default.Refresh,
                    onClick = onRestart,
                    enabled = canRestart
                )
                ToolTile(
                    if (extraKeysVisible) "Hide keys" else "Show keys",
                    icon = if (extraKeysVisible) Icons.Default.KeyboardHide else Icons.Default.Keyboard,
                    onClick = onToggleExtraKeys,
                    active = extraKeysVisible,
                    modifier = Modifier.weight(1f)
                )
            }

            // Font zoom — same step/clamp/storage as TerminalSettingsScreen.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ToolTile(
                    "A-",
                    onClick = { onFontSizeChange(-2) },
                    enabled = fontSize > fontMin,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .weight(1.4f)
                        .height(ToolTileHeight)
                        .clip(ToolTileShape)
                        .background(FrostSurface)
                        .border(ToolEdgeWidth, FrostHairline, ToolTileShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "$fontSize pt",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondary
                    )
                }
                ToolTile(
                    "A+",
                    onClick = { onFontSizeChange(2) },
                    enabled = fontSize < fontMax,
                    modifier = Modifier.weight(1f)
                )
            }

            // ── Destructive (confirm dialogs below) ──────────────────────────
            ToolSectionLabel("CLOSE")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ToolTile(
                    "Close session",
                    icon = Icons.Default.Close,
                    onClick = { confirm = TerminalToolConfirm.SESSION },
                    enabled = hasSession,
                    danger = true,
                    modifier = Modifier.weight(1f)
                )
                ToolTile(
                    "Close all",
                    icon = Icons.Default.Delete,
                    onClick = { confirm = TerminalToolConfirm.ALL },
                    enabled = sessionCount > 0,
                    danger = true,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    when (confirm) {
        TerminalToolConfirm.SESSION -> TerminalConfirmDialog(
            title = "Close session?",
            message = "This kills the running process in “" +
                (sessionTitle?.takeIf { it.isNotBlank() } ?: "this session") + "”.",
            confirmLabel = "Close",
            onConfirm = {
                confirm = null
                onCloseSession()
            },
            onDismiss = { confirm = null }
        )
        TerminalToolConfirm.ALL -> TerminalConfirmDialog(
            title = "Close all sessions?",
            message = "This kills every running terminal process ($sessionCount tab" +
                (if (sessionCount == 1) "" else "s") + ").",
            confirmLabel = "Close all",
            onConfirm = {
                confirm = null
                onCloseAllSessions()
            },
            onDismiss = { confirm = null }
        )
        null -> Unit
    }
}

/** Small caps section label — matches the monochrome frost grouping style. */
@Composable
private fun ToolSectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = TextMuted,
        letterSpacing = 1.4.sp,
        modifier = Modifier.padding(start = 2.dp, top = 16.dp, bottom = 8.dp)
    )
}

/**
 * Frost action tile: FrostSurface + hairline, white pill when active.
 * Disabled = muted text + dimmed fill; danger = FolioDanger text (still no color fill).
 */
@Composable
private fun ToolTile(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    active: Boolean = false,
    danger: Boolean = false
) {
    val background = when {
        active -> TextPrimary
        enabled -> FrostSurface
        else -> FrostSurface.copy(alpha = 0.4f)
    }
    val foreground = when {
        active -> FolioBackground
        !enabled -> TextMuted
        danger -> FolioDanger
        else -> TextPrimary
    }

    Row(
        modifier = modifier
            .height(ToolTileHeight)
            .clip(ToolTileShape)
            .background(background)
            .border(ToolEdgeWidth, FrostHairline, ToolTileShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = foreground,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = foreground,
            maxLines = 1
        )
    }
}

/** Monochrome confirm dialog for kill-actions (session processes cannot be undone). */
@Composable
private fun TerminalConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        },
        text = {
            Text(message, fontSize = 13.sp, lineHeight = 19.sp)
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = FolioDanger)
            ) {
                Text(confirmLabel, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
            ) {
                Text("Cancel")
            }
        }
    )
}
