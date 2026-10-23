package com.altaris.linux.ui.terminal

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import com.altaris.linux.core.utils.TerminalPreferences
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
import com.altaris.linux.ui.theme.AltarisBackground
import com.altaris.linux.ui.theme.AltarisDanger
import com.altaris.linux.ui.theme.FrostHairline
import com.altaris.linux.ui.theme.FrostSurface
import com.altaris.linux.ui.theme.TextMuted
import com.altaris.linux.ui.theme.TextPrimary
import com.altaris.linux.ui.theme.TextSecondary

// Compact frost chips — 40dp keeps a comfortable touch target for a quick palette.
private val ToolTileShape = RoundedCornerShape(11.dp)
private val ToolEdgeWidth = 0.75.dp
private val ToolTileHeight = 40.dp

/** Destructive actions that must ask before running (session processes are killed). */
private enum class TerminalToolConfirm { SESSION, ALL }

/** One key-grid entry: label + TerminalKeyInjector payload (+ ctrl for control combos). */
private data class ToolKeySpec(val label: String, val key: String, val ctrl: Boolean = false)

/**
 * Quick tool palette for the interactive terminal ("tools" button).
 *
 * Small key grid (Ctrl+C/D/Z/L, Tab, Esc), clipboard actions and session actions.
 * Every key action routes through the same [TerminalKeyInjector] path as the
 * ExtraKeys bar (TerminalScreen wires [onInjectKey]) — this composable is
 * presentation + confirm dialogs only.
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
    onInjectKey: (String, Boolean) -> Unit,
    onRestart: () -> Unit,
    onFontSizeChange: (Int) -> Unit,
    onToggleExtraKeys: () -> Unit,
    onCloseSession: () -> Unit,
    onCloseAllSessions: () -> Unit,
    onAddCustomKey: () -> Unit,
    onEditShortcutBar: () -> Unit
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
                .heightIn(max = 480.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp)
                .padding(bottom = 20.dp)
        ) {
            Text(
                text = "Tools",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = sessionTitle?.takeIf { it.isNotBlank() } ?: "No session open",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp)
            )

            // ── Keys: quick sends + keys-bar editing ─────────────────────────
            ToolSectionLabel("Keys")
            ToolKeyGrid(
                specs = listOf(
                    ToolKeySpec("Ctrl C", "c", ctrl = true),
                    ToolKeySpec("Ctrl D", "d", ctrl = true),
                    ToolKeySpec("Ctrl Z", "z", ctrl = true),
                    ToolKeySpec("Ctrl L", "l", ctrl = true),
                    ToolKeySpec("Tab", "TAB"),
                    ToolKeySpec("Esc", "ESC")
                ),
                perRow = 3,
                enabled = hasSession,
                onInjectKey = onInjectKey
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ToolTile(
                    label = "Add key",
                    icon = Icons.Default.Add,
                    onClick = onAddCustomKey,
                    modifier = Modifier.weight(1f)
                )
                ToolTile(
                    label = "Edit keys",
                    icon = Icons.Default.Tune,
                    onClick = onEditShortcutBar,
                    modifier = Modifier.weight(1f)
                )
            }

            // ── Clipboard: paste, copy the transcript, clear the screen (Ctrl+L) ──
            ToolSectionLabel("Clipboard")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ToolTile(
                    label = "Paste",
                    icon = Icons.Default.ContentPaste,
                    onClick = onPaste,
                    enabled = hasSession,
                    modifier = Modifier.weight(1f)
                )
                ToolTile(
                    label = "Copy",
                    icon = Icons.Default.ContentCopy,
                    onClick = onCopyScreen,
                    enabled = hasSession,
                    modifier = Modifier.weight(1f)
                )
                ToolTile(
                    label = "Clear",
                    icon = Icons.Default.Terminal,
                    onClick = { onInjectKey("l", true) },
                    enabled = hasSession,
                    modifier = Modifier.weight(1f)
                )
            }

            // ── Key colours: the extra keys are dark by default; let the
            // user choose their own graphite and label shades here.
            ToolSectionLabel("Key colours")
            Text(
                text = "The shell stays dark in every identity. Pick the key background and label colour.",
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            TerminalKeyColorRow(
                label = "Background",
                isText = false,
            )
            Spacer(Modifier.height(6.dp))
            TerminalKeyColorRow(
                label = "Label",
                isText = true,
            )
            Spacer(Modifier.height(10.dp))

            // ── Session: restart, keys bar, font size, close ─────────────────
            ToolSectionLabel("Session")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ToolTile(
                    label = "Restart",
                    icon = Icons.Default.Refresh,
                    onClick = onRestart,
                    enabled = canRestart,
                    modifier = Modifier.weight(1f)
                )
                ToolTile(
                    label = if (extraKeysVisible) "Hide keys" else "Show keys",
                    icon = if (extraKeysVisible) Icons.Default.KeyboardHide else Icons.Default.Keyboard,
                    onClick = onToggleExtraKeys,
                    active = extraKeysVisible,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ToolTile(
                    label = "Font −",
                    onClick = { onFontSizeChange(-2) },
                    enabled = fontSize > fontMin,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .weight(1.2f)
                        .height(ToolTileHeight)
                        .clip(ToolTileShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(ToolEdgeWidth, MaterialTheme.colorScheme.outline, ToolTileShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "$fontSize pt",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ToolTile(
                    label = "Font +",
                    onClick = { onFontSizeChange(2) },
                    enabled = fontSize < fontMax,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ToolTile(
                    label = "Close",
                    icon = Icons.Default.Close,
                    onClick = { confirm = TerminalToolConfirm.SESSION },
                    enabled = hasSession,
                    danger = true,
                    modifier = Modifier.weight(1f)
                )
                ToolTile(
                    label = "Close all",
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
            message = "Its running process will stop.",
            confirmLabel = "Close",
            onConfirm = {
                confirm = null
                onCloseSession()
            },
            onDismiss = { confirm = null }
        )
        TerminalToolConfirm.ALL -> TerminalConfirmDialog(
            title = "Close all sessions?",
            message = "All running processes will stop.",
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

/** Compact key grid: chunks [specs] into rows of [perRow], equal-width chips. */
@Composable
private fun ToolKeyGrid(
    specs: List<ToolKeySpec>,
    perRow: Int,
    enabled: Boolean,
    onInjectKey: (String, Boolean) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        specs.chunked(perRow).forEach { chunk ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                chunk.forEach { spec ->
                    ToolTile(
                        label = spec.label,
                        onClick = { onInjectKey(spec.key, spec.ctrl) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(perRow - chunk.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** Compact section label matching the monochrome frost grouping style. */
@Composable
private fun ToolSectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(start = 2.dp, top = 12.dp, bottom = 6.dp)
    )
}

/**
 * Frost action chip: MaterialTheme.colorScheme.surfaceVariant + hairline, white pill when active.
 * Disabled = muted text + dimmed fill; danger = AltarisDanger text (still no color fill).
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
        active -> MaterialTheme.colorScheme.onSurface
        enabled -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    }
    val foreground = when {
        active -> MaterialTheme.colorScheme.background
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
        danger -> AltarisDanger
        else -> MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = modifier
            .height(ToolTileHeight)
            .clip(ToolTileShape)
            .background(background)
            .border(ToolEdgeWidth, MaterialTheme.colorScheme.outline, ToolTileShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp),
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
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        },
        text = {
            Text(message, fontSize = 13.sp, lineHeight = 19.sp)
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = AltarisDanger)
            ) {
                Text(confirmLabel, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
            ) {
                Text("Cancel")
            }
        }
    )
}


/** The extra-key background shades, from the automatic default to graphite. */
private val KeyBackgroundSwatches = listOf(
    0,
    0xFF14161A.toInt(),
    0xFF191D22.toInt(),
    0xFF20262C.toInt(),
    0xFF2A3138.toInt(),
    0xFF0E1012.toInt(),
)

/** The extra-key label shades. */
private val KeyTextSwatches = listOf(
    0,
    0xFFECECE7.toInt(),
    0xFFC9D3CE.toInt(),
    0xFFA7B4AE.toInt(),
    0xFFFFFFFF.toInt(),
    0xFFEFE6D6.toInt(),
)

/** One row of colour swatches backed by [TerminalPreferences]. */
@Composable
private fun TerminalKeyColorRow(label: String, isText: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var selected by remember {
        mutableStateOf(
            if (isText) TerminalPreferences.getTextColor(context)
            else TerminalPreferences.getButtonColor(context)
        )
    }
    val swatches = if (isText) KeyTextSwatches else KeyBackgroundSwatches
    val scheme = MaterialTheme.colorScheme

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp,
            color = scheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.padding(top = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            swatches.forEach { argb ->
                val auto = argb == 0
                val isSelected = selected == argb
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            when {
                                auto -> androidx.compose.ui.graphics.Color(0xFF191D22)
                                else -> androidx.compose.ui.graphics.Color(argb)
                            }
                        )
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) scheme.onSurface else scheme.outline,
                            shape = RoundedCornerShape(6.dp),
                        )
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                        ) {
                            selected = argb
                            if (isText) TerminalPreferences.setTextColor(context, argb)
                            else TerminalPreferences.setButtonColor(context, argb)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (auto) {
                        Text(
                            "A",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = androidx.compose.ui.graphics.Color(0xFFECECE7),
                        )
                    }
                }
            }
        }
    }
}
