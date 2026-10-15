@file:OptIn(ExperimentalFoundationApi::class)

package com.ivarna.fluxlinux.ui.terminal

import android.view.KeyEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
private val KeyHeight = 36.dp

/**
 * Nativecode-style special-keys toolbar, Folio frost edition.
 *
 * Row 1 (always): CTRL · ALT · SHIFT · ESC · TAB — lockable modifiers + core keys.
 * Row 2 (always): ← ↑ ↓ → · ENT · BKSP · Fn — arrows, enter/backspace, Fn toggle.
 * Row 3 (Fn on): scrollable F1–F12 + INS/DEL + symbols `/\|~-_\`.
 *
 * Keys are monochrome frosted pills (FrostSurface, hairline border, 9dp radius);
 * pressed or active modifiers render as a white pill with carbon text. Input still
 * goes exclusively through [TerminalKeyInjector.injectKey] — special keys via
 * `TerminalView.onKeyDown/onKeyUp`, printable symbols via `inputCodePoint`.
 */
@Composable
fun TerminalExtraKeys(
    modState: TerminalModifierState,
    terminalView: () -> TerminalView?,
    modifier: Modifier = Modifier
) {
    val fnRowScroll = rememberScrollState()
    var fnVisible by remember { mutableStateOf(false) }
    // Compact key width for the scrollable Fn row; >= 40dp so keys stay tappable.
    val keyWidth: Dp = with(LocalDensity.current) {
        (LocalConfiguration.current.screenWidthDp / 9).coerceIn(40, 56).dp
    }

    fun press(key: String) {
        TerminalKeyInjector.injectKey(
            terminalView(),
            key,
            modState.ctrlActive,
            modState.altActive,
            modState.shiftActive
        )
        modState.consumeModifiers()
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
            // ── Row 1: lockable modifiers + core keys ───────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(KeyHeight),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TerminalKey(
                    label = "CTRL",
                    active = modState.ctrlActive,
                    locked = modState.ctrlLocked,
                    onClick = { modState.toggleCtrl() },
                    onLongClick = { modState.lockCtrl() },
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
                TerminalKey(
                    label = "ALT",
                    active = modState.altActive,
                    locked = modState.altLocked,
                    onClick = { modState.toggleAlt() },
                    onLongClick = { modState.lockAlt() },
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
                TerminalKey(
                    label = "SHIFT",
                    active = modState.shiftActive,
                    locked = modState.shiftLocked,
                    onClick = { modState.toggleShift() },
                    onLongClick = { modState.lockShift() },
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
                TerminalKey(
                    label = "ESC",
                    onClick = { press("ESC") },
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
                TerminalKey(
                    label = "TAB",
                    onClick = { press("TAB") },
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
            }

            // ── Row 2: arrows + ENT / BKSP + Fn ─────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(KeyHeight),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TerminalKey(
                    painter = painterResource(R.drawable.ic_arrow_left),
                    contentDescription = "Arrow left",
                    onClick = { press("LEFT") },
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
                TerminalKey(
                    painter = painterResource(R.drawable.ic_arrow_up),
                    contentDescription = "Arrow up",
                    onClick = { press("UP") },
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
                TerminalKey(
                    painter = painterResource(R.drawable.ic_arrow_down),
                    contentDescription = "Arrow down",
                    onClick = { press("DOWN") },
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
                TerminalKey(
                    painter = painterResource(R.drawable.ic_arrow_right),
                    contentDescription = "Arrow right",
                    onClick = { press("RIGHT") },
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
                TerminalKey(
                    label = "ENT",
                    onClick = { press("ENTER") },
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
                TerminalKey(
                    painter = painterResource(R.drawable.ic_backspace),
                    contentDescription = "Backspace",
                    onClick = { press("BKSP") },
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
                TerminalKey(
                    label = "Fn",
                    active = fnVisible,
                    onClick = { fnVisible = !fnVisible },
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
            }

            // ── Row 3: F1–F12 + INS/DEL + symbols (toggle via Fn) ───────────
            if (fnVisible) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(fnRowScroll),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    (1..12).forEach { n ->
                        TerminalKey(
                            label = "F$n",
                            onClick = { press("F$n") },
                            modifier = Modifier.width(keyWidth).height(KeyHeight)
                        )
                    }
                    listOf("INS", "DEL").forEach { key ->
                        TerminalKey(
                            label = key,
                            onClick = { press(key) },
                            modifier = Modifier.width(keyWidth).height(KeyHeight)
                        )
                    }
                    listOf("/", "|", "~", "-", "_", "\\").forEach { sym ->
                        TerminalKey(
                            label = sym,
                            onClick = { press(sym) },
                            modifier = Modifier.width(keyWidth).height(KeyHeight)
                        )
                    }
                }
            }
        }
    }
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
                fontSize = 10.sp,
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
