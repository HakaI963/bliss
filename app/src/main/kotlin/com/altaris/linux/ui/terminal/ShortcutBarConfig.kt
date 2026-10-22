package com.altaris.linux.ui.terminal

import android.content.Context
import com.altaris.linux.core.utils.TerminalPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Configurable shortcut-bar model + persistence ("bottom shortcut menu").
 *
 * Existing users have no stored JSON → [defaultShortcutBarConfig] reproduces the
 * pre-config bar 1:1 (CTRL ALT SHIFT ESC TAB / arrows ENT ⌫ Fn + Fn strip).
 * Missing, unreadable or corrupt payloads also fall back to defaults — the decode
 * path never throws into the UI.
 */
enum class TerminalShortcutKind { MODIFIER, LOCK, SPECIAL, TEXT, COMBO }

/** Key size: display height/padding boost + share of row width while flowing. */
enum class TerminalKeySize(val id: String, val factor: Float) {
    S("S", 0.72f),
    M("M", 1.0f),
    L("L", 1.4f);

    companion object {
        fun fromId(id: String?): TerminalKeySize = entries.firstOrNull { it.id == id } ?: M
    }
}

/** Global bar scale — multiplies key height, label size and padding. */
enum class ShortcutBarScale(val id: String, val factor: Float) {
    S("S", 0.86f),
    M("M", 1.0f),
    L("L", 1.28f);

    companion object {
        fun fromId(id: String?): ShortcutBarScale = entries.firstOrNull { it.id == id } ?: M
    }
}

/**
 * One configurable key.
 *
 * MODIFIER/LOCK payloads: CTRL | ALT | SHIFT | FN (toggle/lock against
 * [TerminalModifierState]). SPECIAL: a [TerminalKeyCodes] name. TEXT: a raw string
 * written to the pty. COMBO: one char / special name + [ctrl]/[alt]/[shift].
 * [fnRow] keys live in the scrollable Fn strip; everything else flows under the bar.
 */
data class TerminalShortcutKey(
    val id: String,
    val label: String = "",
    val kind: TerminalShortcutKind = TerminalShortcutKind.SPECIAL,
    val payload: String = "",
    val size: TerminalKeySize = TerminalKeySize.M,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val fnRow: Boolean = false
)

data class ShortcutBarConfig(
    val keys: List<TerminalShortcutKey>,
    val scale: ShortcutBarScale = ShortcutBarScale.M
)

const val SHORTCUT_LABEL_MAX = 8
private const val SHORTCUT_PAYLOAD_MAX = 256
private const val SHORTCUT_KEYS_MAX: Int = 64

fun defaultShortcutBarConfig(): ShortcutBarConfig =
    ShortcutBarConfig(keys = defaultShortcutKeys(), scale = ShortcutBarScale.M)

/** Defaults mirror the pre-config ExtraKeys bar: 5+7 main row split, Fn strip below. */
fun defaultShortcutKeys(): List<TerminalShortcutKey> = buildList {
    add(TerminalShortcutKey("mod-ctrl", "CTRL", TerminalShortcutKind.MODIFIER, "CTRL", TerminalKeySize.M))
    add(TerminalShortcutKey("mod-alt", "ALT", TerminalShortcutKind.MODIFIER, "ALT", TerminalKeySize.M))
    add(TerminalShortcutKey("mod-shift", "SHIFT", TerminalShortcutKind.MODIFIER, "SHIFT", TerminalKeySize.M))
    add(TerminalShortcutKey("key-esc", "ESC", TerminalShortcutKind.SPECIAL, "ESC", TerminalKeySize.M))
    add(TerminalShortcutKey("key-tab", "TAB", TerminalShortcutKind.SPECIAL, "TAB", TerminalKeySize.M))
    // Blank label + known special payload renders the original arrow/backspace art.
    add(TerminalShortcutKey("key-left", "", TerminalShortcutKind.SPECIAL, "LEFT", TerminalKeySize.S))
    add(TerminalShortcutKey("key-up", "", TerminalShortcutKind.SPECIAL, "UP", TerminalKeySize.S))
    add(TerminalShortcutKey("key-down", "", TerminalShortcutKind.SPECIAL, "DOWN", TerminalKeySize.S))
    add(TerminalShortcutKey("key-right", "", TerminalShortcutKind.SPECIAL, "RIGHT", TerminalKeySize.S))
    add(TerminalShortcutKey("key-enter", "ENT", TerminalShortcutKind.SPECIAL, "ENTER", TerminalKeySize.S))
    add(TerminalShortcutKey("key-bksp", "", TerminalShortcutKind.SPECIAL, "BKSP", TerminalKeySize.S))
    add(TerminalShortcutKey("mod-fn", "Fn", TerminalShortcutKind.MODIFIER, "FN", TerminalKeySize.S))
    (1..12).forEach { n ->
        add(TerminalShortcutKey("f$n", "F$n", TerminalShortcutKind.SPECIAL, "F$n", TerminalKeySize.S, fnRow = true))
    }
    add(TerminalShortcutKey("key-ins", "INS", TerminalShortcutKind.SPECIAL, "INS", TerminalKeySize.S, fnRow = true))
    add(TerminalShortcutKey("key-del", "DEL", TerminalShortcutKind.SPECIAL, "DEL", TerminalKeySize.S, fnRow = true))
    listOf(
        "sym-slash" to "/",
        "sym-pipe" to "|",
        "sym-tilde" to "~",
        "sym-dash" to "-",
        "sym-underscore" to "_",
        "sym-backslash" to "\\"
    ).forEach { (id, symbol) ->
        add(TerminalShortcutKey(id, symbol, TerminalShortcutKind.COMBO, symbol, TerminalKeySize.S, fnRow = true))
    }
}

object ShortcutBarCodec {

    private const val VERSION = 1

    fun encode(config: ShortcutBarConfig): String {
        val root = JSONObject()
        root.put("v", VERSION)
        root.put("scale", config.scale.id)
        val keys = JSONArray()
        config.keys.forEach { key ->
            val o = JSONObject()
            o.put("id", key.id)
            o.put("label", key.label)
            o.put("kind", key.kind.name)
            o.put("payload", key.payload)
            o.put("size", key.size.id)
            if (key.ctrl) o.put("ctrl", true)
            if (key.alt) o.put("alt", true)
            if (key.shift) o.put("shift", true)
            if (key.fnRow) o.put("fn", true)
            keys.put(o)
        }
        root.put("keys", keys)
        return root.toString()
    }

    /** Never throws: bad JSON / bad rows / zero valid keys fall back to defaults. */
    fun decode(json: String?): ShortcutBarConfig {
        if (json.isNullOrBlank()) return defaultShortcutBarConfig()
        val parsed = runCatching { parse(json) }.getOrNull() ?: return defaultShortcutBarConfig()
        return if (parsed.keys.isEmpty()) defaultShortcutBarConfig() else parsed
    }

    private fun parse(json: String): ShortcutBarConfig {
        val root = JSONObject(json)
        val scale = ShortcutBarScale.fromId(root.optString("scale"))
        val array = root.optJSONArray("keys") ?: return defaultShortcutBarConfig()
        val keys = ArrayList<TerminalShortcutKey>()
        val seenIds = HashSet<String>()
        for (i in 0 until array.length()) {
            if (keys.size >= SHORTCUT_KEYS_MAX) break
            val o = array.optJSONObject(i) ?: continue
            val kind = TerminalShortcutKind.entries.firstOrNull { it.name == o.optString("kind") }
                ?: continue
            val payload = o.optString("payload").take(SHORTCUT_PAYLOAD_MAX)
            if (payload.isEmpty()) continue
            var id = o.optString("id").ifBlank { "k$i" }
            if (!seenIds.add(id)) {
                id = "k$i-${payload.hashCode()}"
                seenIds.add(id)
            }
            keys.add(
                TerminalShortcutKey(
                    id = id,
                    label = o.optString("label").take(SHORTCUT_LABEL_MAX),
                    kind = kind,
                    payload = payload,
                    size = TerminalKeySize.fromId(o.optString("size")),
                    ctrl = o.optBoolean("ctrl", false),
                    alt = o.optBoolean("alt", false),
                    shift = o.optBoolean("shift", false),
                    fnRow = o.optBoolean("fn", false)
                )
            )
        }
        return ShortcutBarConfig(keys = keys, scale = scale)
    }
}

object ShortcutBarStore {

    fun load(context: Context): ShortcutBarConfig =
        ShortcutBarCodec.decode(TerminalPreferences.getShortcutBarJson(context))

    fun save(context: Context, config: ShortcutBarConfig) {
        TerminalPreferences.setShortcutBarJson(context, ShortcutBarCodec.encode(config))
    }
}

/** New unique key id for config edits (duplicate / add). */
fun newShortcutKeyId(): String = "k${System.nanoTime().toString(36)}"
