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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
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

private val EditorPillShape = RoundedCornerShape(9.dp)

private val ModifierPayloads = listOf("CTRL", "ALT", "SHIFT", "FN")

/**
 * Shortcut-bar manager: bar scale, ordered key list (reorder / size / edit / delete),
 * add key + reset-to-defaults. Every mutation is persisted by the caller as it lands.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortcutBarEditorSheet(
    config: ShortcutBarConfig,
    onConfigChange: (ShortcutBarConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var editingKey by remember { mutableStateOf<TerminalShortcutKey?>(null) }
    var showKeyEditor by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    fun replaceKeys(keys: List<TerminalShortcutKey>) = onConfigChange(config.copy(keys = keys))

    fun moveKey(index: Int, delta: Int) {
        val target = index + delta
        if (index !in config.keys.indices || target !in config.keys.indices) return
        val keys = config.keys.toMutableList()
        val moved = keys.removeAt(index)
        keys.add(target, moved)
        replaceKeys(keys)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
                text = "Your keys",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = "Long-press a bar key for quick actions.",
                fontSize = 12.sp,
                color = TextSecondary,
                modifier = Modifier.padding(top = 2.dp)
            )

            SectionLabel("Size")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ShortcutBarScale.entries.forEach { scale ->
                    EditorPill(
                        label = scale.id,
                        selected = config.scale == scale,
                        modifier = Modifier.weight(1f)
                    ) { onConfigChange(config.copy(scale = scale)) }
                }
            }

            Spacer(Modifier.height(6.dp))
            config.keys.forEachIndexed { index, key ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = key.label.ifBlank { key.payload }.take(SHORTCUT_LABEL_MAX),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (key.fnRow) {
                                Spacer(Modifier.width(6.dp))
                                FnBadge()
                            }
                        }
                        Text(
                            text = describeKey(key),
                            fontSize = 10.sp,
                            color = TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    EditorPill(
                        label = key.size.id,
                        selected = false
                    ) { replaceKeys(cycleSize(config.keys, index)) }
                    IconAction(Icons.Default.KeyboardArrowUp, "Move earlier", enabled = index > 0) {
                        moveKey(index, -1)
                    }
                    IconAction(
                        Icons.Default.KeyboardArrowDown,
                        "Move later",
                        enabled = index < config.keys.lastIndex
                    ) {
                        moveKey(index, +1)
                    }
                    IconAction(Icons.Default.Edit, "Edit key") {
                        editingKey = key
                        showKeyEditor = true
                    }
                    IconAction(Icons.Default.Delete, "Remove key") {
                        replaceKeys(config.keys.filterIndexed { i, _ -> i != index })
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EditorPill(
                    label = "Add key",
                    selected = true,
                    modifier = Modifier.weight(1f)
                ) {
                    editingKey = null
                    showKeyEditor = true
                }
                EditorPill(
                    label = "Reset",
                    selected = false,
                    modifier = Modifier.weight(1f)
                ) { confirmReset = true }
            }
        }
    }

    if (showKeyEditor) {
        val editing = editingKey
        ShortcutKeyEditorSheet(
            initial = editing,
            onSave = { key ->
                val next = if (editing == null) {
                    config.keys + key
                } else {
                    config.keys.map { if (it.id == editing.id) key else it }
                }
                replaceKeys(next)
                showKeyEditor = false
                editingKey = null
            },
            onDismiss = {
                showKeyEditor = false
                editingKey = null
            }
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = { Text("Reset keys?", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = { Text("Restores the default keys and bar size.", fontSize = 13.sp, lineHeight = 19.sp) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReset = false
                        onConfigChange(defaultShortcutBarConfig())
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = AltarisDanger)
                ) {
                    Text("Reset", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmReset = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Custom-key creator / editor: label (≤ 8 chars), kind, per-kind payload, size and
 * Fn-row placement. Ids are preserved when editing, regenerated when adding.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortcutKeyEditorSheet(
    initial: TerminalShortcutKey?,
    onSave: (TerminalShortcutKey) -> Unit,
    onDismiss: () -> Unit
) {
    var label by remember { mutableStateOf(initial?.label.orEmpty()) }
    var kind by remember { mutableStateOf(initial?.kind ?: TerminalShortcutKind.SPECIAL) }
    var payload by remember {
        mutableStateOf(
            initial?.payload?.takeIf { it.isNotEmpty() } ?: when (initial?.kind) {
                null, TerminalShortcutKind.SPECIAL -> "ESC"
                TerminalShortcutKind.MODIFIER, TerminalShortcutKind.LOCK -> "CTRL"
                else -> ""
            }
        )
    }
    var size by remember { mutableStateOf(initial?.size ?: TerminalKeySize.M) }
    var ctrl by remember { mutableStateOf(initial?.ctrl == true) }
    var alt by remember { mutableStateOf(initial?.alt == true) }
    var shift by remember { mutableStateOf(initial?.shift == true) }
    var fnRow by remember { mutableStateOf(initial?.fnRow == true) }

    fun selectKind(next: TerminalShortcutKind) {
        if (next == kind) return
        kind = next
        payload = when (next) {
            TerminalShortcutKind.MODIFIER, TerminalShortcutKind.LOCK -> "CTRL"
            TerminalShortcutKind.SPECIAL -> "ESC"
            else -> ""
        }
    }

    val canSave = when (kind) {
        TerminalShortcutKind.MODIFIER, TerminalShortcutKind.LOCK -> payload in ModifierPayloads
        TerminalShortcutKind.SPECIAL -> TerminalKeyCodes.SPECIAL_KEY_CODES.containsKey(payload)
        TerminalShortcutKind.TEXT -> payload.isNotEmpty()
        TerminalShortcutKind.COMBO -> payload.length == 1 ||
            TerminalKeyCodes.SPECIAL_KEY_CODES.containsKey(payload)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = if (initial == null) "New key" else "Edit key",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            OutlinedTextField(
                value = label,
                onValueChange = { label = it.take(SHORTCUT_LABEL_MAX) },
                label = { Text("Label (max $SHORTCUT_LABEL_MAX)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            SectionLabel("Type")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    TerminalShortcutKind.MODIFIER,
                    TerminalShortcutKind.LOCK,
                    TerminalShortcutKind.SPECIAL,
                    TerminalShortcutKind.TEXT,
                    TerminalShortcutKind.COMBO
                ).forEach { candidate ->
                    EditorPill(
                        label = kindLabel(candidate),
                        selected = kind == candidate,
                        modifier = Modifier.weight(1f)
                    ) { selectKind(candidate) }
                }
            }

            when (kind) {
                TerminalShortcutKind.MODIFIER, TerminalShortcutKind.LOCK -> {
                    SectionLabel("Modifier")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ModifierPayloads.forEach { candidate ->
                            EditorPill(
                                label = candidate,
                                selected = payload == candidate,
                                modifier = Modifier.weight(1f)
                            ) { payload = candidate }
                        }
                    }
                    Text(
                        text = if (kind == TerminalShortcutKind.MODIFIER) {
                            "Tap to toggle. Long-press to lock."
                        } else {
                            "Stays on until tapped again."
                        },
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                }

                TerminalShortcutKind.SPECIAL -> {
                    SectionLabel("Key")
                    TerminalKeyCodes.SPECIAL_KEY_CODES.keys.toList().chunked(5).forEach { chunk ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            chunk.forEach { candidate ->
                                EditorPill(
                                    label = candidate,
                                    selected = payload == candidate,
                                    modifier = Modifier.weight(1f)
                                ) { payload = candidate }
                            }
                            repeat(5 - chunk.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }

                TerminalShortcutKind.TEXT -> {
                    SectionLabel("Text")
                    OutlinedTextField(
                        value = payload,
                        onValueChange = { payload = it.take(256) },
                        label = { Text("Sent to the shell") },
                        placeholder = { Text("e.g. ls -la") },
                        minLines = 1,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                TerminalShortcutKind.COMBO -> {
                    SectionLabel("Key")
                    OutlinedTextField(
                        value = payload,
                        onValueChange = { payload = it.take(1) },
                        label = { Text("One character or key name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    SectionLabel("Modifiers")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        EditorPill("CTRL", selected = ctrl, modifier = Modifier.weight(1f)) { ctrl = !ctrl }
                        EditorPill("ALT", selected = alt, modifier = Modifier.weight(1f)) { alt = !alt }
                        EditorPill("SHIFT", selected = shift, modifier = Modifier.weight(1f)) { shift = !shift }
                    }
                }
            }

            SectionLabel("Size")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TerminalKeySize.entries.forEach { candidate ->
                    EditorPill(
                        label = candidate.id,
                        selected = size == candidate,
                        modifier = Modifier.weight(1f)
                    ) { size = candidate }
                }
                EditorPill(
                    label = "Fn row",
                    selected = fnRow,
                    modifier = Modifier.weight(1.6f)
                ) { fnRow = !fnRow }
            }

            Button(
                onClick = {
                    onSave(
                        TerminalShortcutKey(
                            id = initial?.id ?: newShortcutKeyId(),
                            label = label.take(SHORTCUT_LABEL_MAX),
                            kind = kind,
                            payload = payload,
                            size = size,
                            ctrl = kind == TerminalShortcutKind.COMBO && ctrl,
                            alt = kind == TerminalShortcutKind.COMBO && alt,
                            shift = kind == TerminalShortcutKind.COMBO && shift,
                            fnRow = fnRow
                        )
                    )
                },
                enabled = canSave,
                colors = ButtonDefaults.buttonColors(
                    containerColor = TextPrimary,
                    contentColor = AltarisBackground,
                    disabledContainerColor = TextPrimary.copy(alpha = 0.3f),
                    disabledContentColor = AltarisBackground.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
            ) {
                Text(
                    text = if (initial == null) "Add key" else "Save",
                    fontWeight = FontWeight.Bold
                )
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
            ) {
                Text("Cancel")
            }
        }
    }
}

/**
 * Quick "one-line key" creator (kept for reuse by other surfaces; the tools
 * palette now routes "Add key" to the full [ShortcutKeyEditorSheet]).
 * Auto-picks the lightest kind that fits: Ctrl+char → COMBO, special name →
 * SPECIAL, anything else → TEXT written to the pty (optional trailing Enter).
 */
@Composable
fun ShortcutQuickAddDialog(
    onAdd: (TerminalShortcutKey) -> Unit,
    onDismiss: () -> Unit
) {
    var label by remember { mutableStateOf("") }
    var sequence by remember { mutableStateOf("") }
    var ctrl by remember { mutableStateOf(false) }
    var appendEnter by remember { mutableStateOf(false) }
    var fnRow by remember { mutableStateOf(false) }

    val trimmed = sequence.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = { Text("Add key", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(SHORTCUT_LABEL_MAX) },
                    label = { Text("Label (max $SHORTCUT_LABEL_MAX)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = sequence,
                    onValueChange = { sequence = it.take(128) },
                    label = { Text("Sequence") },
                    placeholder = { Text("e.g. ls -la") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EditorPill("Ctrl", selected = ctrl) { ctrl = !ctrl }
                    EditorPill("Append ⏎", selected = appendEnter) { appendEnter = !appendEnter }
                    EditorPill("Fn row", selected = fnRow) { fnRow = !fnRow }
                }
                Text(
                    text = "One char + Ctrl = combo. A key name (Esc, F5) = special key. "
                        + "Anything else is typed into the shell.",
                    fontSize = 11.sp,
                    color = TextMuted
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val special = TerminalKeyCodes.SPECIAL_KEY_CODES.containsKey(sequence.trim().uppercase())
                    val key = when {
                        ctrl && sequence.length == 1 -> TerminalShortcutKey(
                            id = newShortcutKeyId(),
                            label = label,
                            kind = TerminalShortcutKind.COMBO,
                            payload = sequence,
                            size = TerminalKeySize.M,
                            ctrl = true,
                            fnRow = fnRow
                        )
                        special -> TerminalShortcutKey(
                            id = newShortcutKeyId(),
                            label = label,
                            kind = TerminalShortcutKind.SPECIAL,
                            payload = sequence.trim().uppercase(),
                            size = TerminalKeySize.M,
                            fnRow = fnRow
                        )
                        else -> TerminalShortcutKey(
                            id = newShortcutKeyId(),
                            label = label,
                            kind = TerminalShortcutKind.TEXT,
                            payload = sequence + if (appendEnter) "\n" else "",
                            size = TerminalKeySize.M,
                            fnRow = fnRow
                        )
                    }
                    onAdd(key)
                },
                enabled = trimmed.isNotEmpty(),
                colors = ButtonDefaults.textButtonColors(contentColor = TextPrimary)
            ) {
                Text("Add", fontWeight = FontWeight.SemiBold)
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

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = TextMuted,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
    )
}

@Composable
private fun FnBadge() {
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(FrostSurface)
            .border(0.75.dp, FrostHairline, shape)
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(
            text = "Fn",
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = TextSecondary
        )
    }
}

@Composable
private fun EditorPill(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(30.dp)
            .clip(EditorPillShape)
            .background(if (selected) TextPrimary else FrostSurface)
            .border(0.75.dp, if (selected) TextPrimary else FrostHairline, EditorPillShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) AltarisBackground else TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun IconAction(
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    tint: Color = TextSecondary,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(30.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) tint else TextMuted,
            modifier = Modifier.size(16.dp)
        )
    }
}

private fun kindLabel(kind: TerminalShortcutKind): String = when (kind) {
    TerminalShortcutKind.MODIFIER -> "Mod"
    TerminalShortcutKind.LOCK -> "Lock"
    TerminalShortcutKind.SPECIAL -> "Key"
    TerminalShortcutKind.TEXT -> "Text"
    TerminalShortcutKind.COMBO -> "Combo"
}

private fun describeKey(key: TerminalShortcutKey): String = when (key.kind) {
    TerminalShortcutKind.MODIFIER -> "Modifier · ${key.payload}"
    TerminalShortcutKind.LOCK -> "Lock · ${key.payload}"
    TerminalShortcutKind.SPECIAL -> "Special · ${key.payload}"
    TerminalShortcutKind.TEXT -> "Text · ${key.payload.replace("\n", "⏎").take(24)}"
    TerminalShortcutKind.COMBO -> buildString {
        if (key.ctrl) append("ctrl ")
        if (key.alt) append("alt ")
        if (key.shift) append("shift ")
        append(key.payload)
    }
}

private fun cycleSize(
    keys: List<TerminalShortcutKey>,
    index: Int
): List<TerminalShortcutKey> {
    if (index !in keys.indices) return keys
    val current = keys[index].size
    val next = when (current) {
        TerminalKeySize.S -> TerminalKeySize.M
        TerminalKeySize.M -> TerminalKeySize.L
        TerminalKeySize.L -> TerminalKeySize.S
    }
    return keys.toMutableList().apply { this[index] = this[index].copy(size = next) }
}
