@file:OptIn(ExperimentalFoundationApi::class)

package com.ivarna.fluxlinux.ui.terminal

import android.view.KeyEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivarna.fluxlinux.R
import com.ivarna.fluxlinux.ui.theme.FolioBackground
import com.ivarna.fluxlinux.ui.theme.FrostHairline
import com.ivarna.fluxlinux.ui.theme.FrostSurface
import com.ivarna.fluxlinux.ui.theme.TextPrimary
import com.ivarna.fluxlinux.ui.theme.TextSecondary
import com.termux.view.TerminalView

/**
 * Toggle + long-press-lock modifier state (nativecode `ModifierState` parity).
 *
 * A modifier stays active until consumed by a key press unless it is locked
 * (long-press); locked modifiers survive consumption and clear on the next tap.
 * Compose `mutableStateOf` fields drive toolbar UI refresh automatically.
 */
class TerminalModifierState {
    var ctrlActive by mutableStateOf(false)
    var ctrlLocked by mutableStateOf(false)
    var altActive by mutableStateOf(false)
    var altLocked by mutableStateOf(false)
    var shiftActive by mutableStateOf(false)
    var shiftLocked by mutableStateOf(false)

    fun readCtrl(autoReadSetFalse: Boolean = true): Boolean {
        if (!ctrlActive) return false
        if (autoReadSetFalse && !ctrlLocked) ctrlActive = false
        return true
    }

    fun readAlt(autoReadSetFalse: Boolean = true): Boolean {
        if (!altActive) return false
        if (autoReadSetFalse && !altLocked) altActive = false
        return true
    }

    fun readShift(autoReadSetFalse: Boolean = true): Boolean {
        if (!shiftActive) return false
        if (autoReadSetFalse && !shiftLocked) shiftActive = false
        return true
    }

    /** Fn is a UI-only row toggle (nativecode returns false here). */
    fun readFn(autoReadSetFalse: Boolean = true): Boolean = false

    fun toggleCtrl() {
        if (ctrlLocked) {
            ctrlActive = false
            ctrlLocked = false
        } else {
            ctrlActive = !ctrlActive
            if (!ctrlActive) ctrlLocked = false
        }
    }

    fun lockCtrl() {
        ctrlActive = true
        ctrlLocked = true
    }

    fun toggleAlt() {
        if (altLocked) {
            altActive = false
            altLocked = false
        } else {
            altActive = !altActive
            if (!altActive) altLocked = false
        }
    }

    fun lockAlt() {
        altActive = true
        altLocked = true
    }

    fun toggleShift() {
        if (shiftLocked) {
            shiftActive = false
            shiftLocked = false
        } else {
            shiftActive = !shiftActive
            if (!shiftActive) shiftLocked = false
        }
    }

    fun lockShift() {
        shiftActive = true
        shiftLocked = true
    }

    /** Clear one-shot modifiers after a key is injected (locked ones survive). */
    fun consumeModifiers() {
        if (!ctrlLocked) ctrlActive = false
        if (!altLocked) altActive = false
        if (!shiftLocked) shiftActive = false
    }
}

/** Nativecode `SPECIAL_KEY_CODES` parity — toolbar key name → Android keycode. */
object TerminalKeyCodes {
    val SPECIAL_KEY_CODES: Map<String, Int> = mapOf(
        "ESC" to KeyEvent.KEYCODE_ESCAPE,
        "TAB" to KeyEvent.KEYCODE_TAB,
        "ENTER" to KeyEvent.KEYCODE_ENTER,
        "BKSP" to KeyEvent.KEYCODE_DEL,
        "DEL" to KeyEvent.KEYCODE_FORWARD_DEL,
        "UP" to KeyEvent.KEYCODE_DPAD_UP,
        "DOWN" to KeyEvent.KEYCODE_DPAD_DOWN,
        "LEFT" to KeyEvent.KEYCODE_DPAD_LEFT,
        "RIGHT" to KeyEvent.KEYCODE_DPAD_RIGHT,
        "HOME" to KeyEvent.KEYCODE_MOVE_HOME,
        "END" to KeyEvent.KEYCODE_MOVE_END,
        "PGUP" to KeyEvent.KEYCODE_PAGE_UP,
        "PGDN" to KeyEvent.KEYCODE_PAGE_DOWN,
        "INS" to KeyEvent.KEYCODE_INSERT,
        "F1" to KeyEvent.KEYCODE_F1,
        "F2" to KeyEvent.KEYCODE_F2,
        "F3" to KeyEvent.KEYCODE_F3,
        "F4" to KeyEvent.KEYCODE_F4,
        "F5" to KeyEvent.KEYCODE_F5,
        "F6" to KeyEvent.KEYCODE_F6,
        "F7" to KeyEvent.KEYCODE_F7,
        "F8" to KeyEvent.KEYCODE_F8,
        "F9" to KeyEvent.KEYCODE_F9,
        "F10" to KeyEvent.KEYCODE_F10,
        "F11" to KeyEvent.KEYCODE_F11,
        "F12" to KeyEvent.KEYCODE_F12
    )
}

/**
 * Nativecode `injectKey` parity — special keys go through `TerminalView.onKeyDown /
 * onKeyUp` with meta flags; printable symbols go through `inputCodePoint`. This is
 * the ONLY toolbar inject path (the old `session.write("\u001b…")` / dispatch path is
 * gone — plan §2.3 / §6 PR-T4).
 */
object TerminalKeyInjector {
    fun injectKey(tv: TerminalView?, key: String, ctrl: Boolean, alt: Boolean, shift: Boolean) {
        tv ?: return
        val keyCode = TerminalKeyCodes.SPECIAL_KEY_CODES[key]
        if (keyCode != null) {
            var meta = 0
            if (ctrl) meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            if (alt) meta = meta or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
            if (shift) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
            val evDown = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, meta)
            tv.onKeyDown(keyCode, evDown)
            val evUp = KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, meta)
            tv.onKeyUp(keyCode, evUp)
        } else {
            key.codePoints().forEach { cp -> tv.inputCodePoint(cp, ctrl, alt) }
        }
    }
}

/** Compact frost key metrics shared by every toolbar key. */
private val KeyShape = RoundedCornerShape(9.dp)
private val KeyBaseHeight = 34.dp
private val MinKeyWidth = 40.dp
private const val KeyGapDp = 4
/** One M-sized key = 1 unit; rows wrap once the next key would pass this budget. */
private const val RowCapacityUnits = 5.2f

/** Long-press action-menu callbacks for configurable bar keys. */
data class ShortcutBarActions(
    val onEditKey: (TerminalShortcutKey) -> Unit = {},
    val onDuplicateKey: (TerminalShortcutKey) -> Unit = {},
    val onRemoveKey: (TerminalShortcutKey) -> Unit = {},
    val onEditBar: () -> Unit = {}
)

/**
 * Configurable special-keys toolbar, Foila frost edition.
 *
 * Renders [ShortcutBarConfig.keys]: main keys flow into weighted rows (defaults
 * mirror the classic CTRL ALT SHIFT ESC TAB / arrows ENT ⌫ Fn layout), Fn-flagged
 * keys live in a scrollable strip toggled by the Fn key. Input still goes
 * exclusively through [TerminalKeyInjector.injectKey] — special keys via
 * `TerminalView.onKeyDown/onKeyUp`, printable keys via `inputCodePoint`, TEXT keys
 * via [onSendText] (pty paste/write). Long-press a modifier = lock (unchanged);
 * long-press any other key = quick actions (edit / duplicate / remove / edit bar).
 */
@Composable
fun TerminalExtraKeys(
    modState: TerminalModifierState,
    terminalView: () -> TerminalView?,
    config: ShortcutBarConfig,
    modifier: Modifier = Modifier,
    onSendText: (String) -> Unit = {},
    actions: ShortcutBarActions = ShortcutBarActions()
) {
    var fnVisible by remember { mutableStateOf(false) }
    var menuKeyId by remember { mutableStateOf<String?>(null) }
    val fnScroll = rememberScrollState()
    val mainKeys = config.keys.filter { !it.fnRow }
    val fnKeys = config.keys.filter { it.fnRow }
    val rows = shortcutBarRows(mainKeys)
    val keyHeight = KeyBaseHeight * config.scale.factor
    val labelSize = (10f * config.scale.factor).sp
    val fnKeyWidth = (46f * config.scale.factor).dp

    fun inject(key: TerminalShortcutKey, ctrl: Boolean, alt: Boolean, shift: Boolean) {
        TerminalKeyInjector.injectKey(terminalView(), key.payload, ctrl, alt, shift)
        modState.consumeModifiers()
    }

    fun activate(key: TerminalShortcutKey) {
        when (key.kind) {
            TerminalShortcutKind.MODIFIER -> when (key.payload) {
                "CTRL" -> modState.toggleCtrl()
                "ALT" -> modState.toggleAlt()
                "SHIFT" -> modState.toggleShift()
                "FN" -> fnVisible = !fnVisible
                else -> Unit
            }
            TerminalShortcutKind.LOCK -> when (key.payload) {
                "CTRL" -> if (modState.ctrlLocked) modState.toggleCtrl() else modState.lockCtrl()
                "ALT" -> if (modState.altLocked) modState.toggleAlt() else modState.lockAlt()
                "SHIFT" -> if (modState.shiftLocked) modState.toggleShift() else modState.lockShift()
                "FN" -> fnVisible = !fnVisible
                else -> Unit
            }
            // Explicit combo flags OR the live modifier locks — never a bypass of
            // the current ctrl/alt/shift semantics.
            TerminalShortcutKind.SPECIAL, TerminalShortcutKind.COMBO -> inject(
                key,
                ctrl = key.ctrl || modState.ctrlActive,
                alt = key.alt || modState.altActive,
                shift = key.shift || modState.shiftActive
            )
            TerminalShortcutKind.TEXT -> onSendText(key.payload)
        }
    }

    fun onLongPress(key: TerminalShortcutKey) {
        if (key.kind == TerminalShortcutKind.MODIFIER) {
            when (key.payload) {
                "CTRL" -> {
                    modState.lockCtrl()
                    return
                }
                "ALT" -> {
                    modState.lockAlt()
                    return
                }
                "SHIFT" -> {
                    modState.lockShift()
                    return
                }
            }
        }
        menuKeyId = key.id
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            // TerminalScreen's parent already consumes these bottom insets, so this
            // is a no-op there; kept so the bar never underlaps the system nav or
            // the keyboard if it is ever hosted without that padding.
            .imePadding()
            .navigationBarsPadding()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(FrostHairline)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.92f))
                .padding(horizontal = 6.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            rows.forEach { row ->
                ShortcutBarRow(
                    keys = row,
                    keyHeight = keyHeight,
                    labelSize = labelSize,
                    modState = modState,
                    fnVisible = fnVisible,
                    menuKeyId = menuKeyId,
                    onTapKey = { activate(it) },
                    onLongPressKey = { onLongPress(it) },
                    onCloseMenu = { menuKeyId = null },
                    actions = actions
                )
            }

            if (fnVisible && fnKeys.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(fnScroll),
                    horizontalArrangement = Arrangement.spacedBy(KeyGapDp.dp)
                ) {
                    fnKeys.forEach { key ->
                        ShortcutBarKeyItem(
                            key = key,
                            active = isKeyActive(key, modState, fnVisible),
                            locked = isKeyLocked(key, modState),
                            menuOpen = menuKeyId == key.id,
                            labelSize = labelSize,
                            modifier = Modifier.width(fnKeyWidth).height(keyHeight),
                            onTap = { activate(key) },
                            onLongPress = { onLongPress(key) },
                            onMenuDismiss = { menuKeyId = null },
                            actions = actions
                        )
                    }
                }
            }

            if (mainKeys.isEmpty() && fnKeys.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(keyHeight)
                        .clip(KeyShape)
                        .border(1.dp, FrostHairline, KeyShape)
                        .clickable { actions.onEditBar() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No keys — tap to edit",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

/** Greedy wrap: consecutive keys flow into rows until the unit budget is spent. */
private fun shortcutBarRows(keys: List<TerminalShortcutKey>): List<List<TerminalShortcutKey>> {
    val rows = ArrayList<MutableList<TerminalShortcutKey>>()
    var units = 0f
    keys.forEach { key ->
        val factor = key.size.factor
        if (rows.isEmpty() || units + factor > RowCapacityUnits + 0.001f) {
            rows.add(ArrayList())
            units = 0f
        }
        rows.last().add(key)
        units += factor
    }
    return rows
}

@Composable
private fun ShortcutBarRow(
    keys: List<TerminalShortcutKey>,
    keyHeight: Dp,
    labelSize: TextUnit,
    modState: TerminalModifierState,
    fnVisible: Boolean,
    menuKeyId: String?,
    onTapKey: (TerminalShortcutKey) -> Unit,
    onLongPressKey: (TerminalShortcutKey) -> Unit,
    onCloseMenu: () -> Unit,
    actions: ShortcutBarActions
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val gap = KeyGapDp.dp
        val totalUnits = keys.fold(0f) { acc, key -> acc + key.size.factor }.coerceAtLeast(0.01f)
        val widthFor: (TerminalShortcutKey) -> Dp = { key ->
            ((maxWidth - gap * (keys.size - 1)) * (key.size.factor / totalUnits)).coerceAtLeast(0.dp)
        }
        // Wide rows split the available width by size; when even the minimum
        // touch width would not fit, the row becomes horizontally scrollable.
        val scrolling = keys.isEmpty() || keys.any { widthFor(it) < MinKeyWidth }
        Row(
            modifier = if (scrolling) {
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            } else {
                Modifier.fillMaxWidth()
            },
            horizontalArrangement = Arrangement.spacedBy(gap)
        ) {
            keys.forEach { key ->
                ShortcutBarKeyItem(
                    key = key,
                    active = isKeyActive(key, modState, fnVisible),
                    locked = isKeyLocked(key, modState),
                    menuOpen = menuKeyId == key.id,
                    labelSize = labelSize,
                    modifier = Modifier
                        .width(if (scrolling) MinKeyWidth else widthFor(key))
                        .height(keyHeight),
                    onTap = { onTapKey(key) },
                    onLongPress = { onLongPressKey(key) },
                    onMenuDismiss = onCloseMenu,
                    actions = actions
                )
            }
        }
    }
}

@Composable
private fun ShortcutBarKeyItem(
    key: TerminalShortcutKey,
    active: Boolean,
    locked: Boolean,
    menuOpen: Boolean,
    labelSize: TextUnit,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onMenuDismiss: () -> Unit,
    actions: ShortcutBarActions
) {
    Box(modifier = modifier) {
        val painter = shortcutKeyPainter(key)
        TerminalKey(
            label = if (painter == null) key.displayLabel() else null,
            painter = painter,
            contentDescription = key.payload,
            active = active,
            locked = locked,
            labelSize = labelSize,
            onClick = onTap,
            onLongClick = onLongPress,
            modifier = Modifier.fillMaxSize()
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = onMenuDismiss) {
            ShortcutMenuEntry("Edit key", Icons.Default.Edit) {
                onMenuDismiss()
                actions.onEditKey(key)
            }
            ShortcutMenuEntry("Duplicate", Icons.Default.ContentCopy) {
                onMenuDismiss()
                actions.onDuplicateKey(key)
            }
            ShortcutMenuEntry("Remove", Icons.Default.Delete) {
                onMenuDismiss()
                actions.onRemoveKey(key)
            }
            ShortcutMenuEntry("Edit keys", Icons.Default.Build) {
                onMenuDismiss()
                actions.onEditBar()
            }
        }
    }
}

@Composable
private fun ShortcutMenuEntry(label: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text = label, fontSize = 13.sp, color = TextPrimary, maxLines = 1) },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(16.dp)
            )
        },
        onClick = onClick
    )
}

/** Default rows show the original arrow/backspace art when the label is empty. */
@Composable
private fun shortcutKeyPainter(key: TerminalShortcutKey): Painter? {
    if (key.kind != TerminalShortcutKind.SPECIAL || key.label.isNotEmpty()) return null
    val res = when (key.payload) {
        "LEFT" -> R.drawable.ic_arrow_left
        "UP" -> R.drawable.ic_arrow_up
        "DOWN" -> R.drawable.ic_arrow_down
        "RIGHT" -> R.drawable.ic_arrow_right
        "BKSP" -> R.drawable.ic_backspace
        else -> 0
    }
    return if (res != 0) painterResource(res) else null
}

private fun TerminalShortcutKey.displayLabel(): String =
    label.ifBlank { payload }.take(SHORTCUT_LABEL_MAX)

private fun isKeyActive(
    key: TerminalShortcutKey,
    modState: TerminalModifierState,
    fnVisible: Boolean
): Boolean = when (key.kind) {
    TerminalShortcutKind.MODIFIER -> when (key.payload) {
        "CTRL" -> modState.ctrlActive
        "ALT" -> modState.altActive
        "SHIFT" -> modState.shiftActive
        "FN" -> fnVisible
        else -> false
    }
    TerminalShortcutKind.LOCK -> when (key.payload) {
        "CTRL" -> modState.ctrlLocked
        "ALT" -> modState.altLocked
        "SHIFT" -> modState.shiftLocked
        else -> false
    }
    else -> false
}

private fun isKeyLocked(key: TerminalShortcutKey, modState: TerminalModifierState): Boolean =
    when (key.kind) {
        TerminalShortcutKind.MODIFIER, TerminalShortcutKind.LOCK -> when (key.payload) {
            "CTRL" -> modState.ctrlLocked
            "ALT" -> modState.altLocked
            "SHIFT" -> modState.shiftLocked
            else -> false
        }
        else -> false
    }

/**
 * Frost pill key: FrostSurface + hairline border, TextSecondary label.
 * Pressed / active = white pill with carbon text. A locked modifier
 * (long-press) gets a carbon ring + badge dot.
 */
@Composable
private fun TerminalKey(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    painter: Painter? = null,
    contentDescription: String? = null,
    active: Boolean = false,
    locked: Boolean = false,
    labelSize: TextUnit = 10.sp,
    onLongClick: (() -> Unit)? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val emphasized = active || pressed
    val background = when {
        active -> TextPrimary
        pressed -> TextPrimary.copy(alpha = 0.85f)
        else -> FrostSurface
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(KeyShape)
            .background(background)
            .border(
                width = if (locked) 2.dp else 1.dp,
                color = if (locked) FolioBackground else FrostHairline,
                shape = KeyShape
            )
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        if (painter != null) {
            Icon(
                painter = painter,
                contentDescription = contentDescription,
                tint = if (emphasized) FolioBackground else TextSecondary,
                modifier = Modifier.size(18.dp)
            )
        } else if (label != null) {
            Text(
                text = label,
                fontSize = labelSize,
                fontWeight = FontWeight.SemiBold,
                color = if (emphasized) FolioBackground else TextSecondary,
                maxLines = 1
            )
        }
        if (locked) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .size(5.dp)
                    .background(FolioBackground, CircleShape)
            )
        }
    }
}
