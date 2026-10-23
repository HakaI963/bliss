package com.altaris.linux.core.utils

import android.content.Context
import kotlinx.coroutines.flow.asStateFlow
import com.altaris.linux.core.terminal.GuestLoginShell

/**
 * Global terminal UI prefs (font zoom + ExtraKeys toolbar + guest login shell).
 * Mirrors nativecode-ai `pref_show_extra_keys` / font size behaviour.
 */
object TerminalPreferences {

    /** Bumped whenever a look preference changes, so open surfaces recompose. */
    private val _lookRevision = kotlinx.coroutines.flow.MutableStateFlow(0)
    val lookRevision: kotlinx.coroutines.flow.StateFlow<Int> = _lookRevision.asStateFlow()

    private const val PREFS_NAME = "altaris_terminal_prefs"
    private const val KEY_FONT_SIZE = "font_size"
    private const val KEY_SHOW_EXTRA_KEYS = "show_extra_keys"
    private const val KEY_GUEST_LOGIN_SHELL = "guest_login_shell"
    private const val KEY_LEGACY_PROOT_LAUNCHER = "legacy_proot_launcher"
    private const val KEY_SHORTCUT_BAR = "shortcut_bar_config"
    private const val KEY_BANNER_QUALITY = "banner_quality"
    private const val KEY_AUTO_FULLSCREEN = "auto_fullscreen"
    private const val KEY_BUTTON_COLOR = "button_color"
    private const val KEY_TEXT_COLOR = "text_color"

    const val FONT_MIN = 10
    const val FONT_MAX = 48
    const val FONT_DEFAULT = 24

    /** Launch-sticker quality levels: L1 24 cols … L5 54 cols. */
    private const val BANNER_QUALITY_MIN = 1
    private const val BANNER_QUALITY_MAX = 5
    private const val BANNER_QUALITY_DEFAULT = 2


    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getFontSize(context: Context): Int =
        prefs(context).getInt(KEY_FONT_SIZE, FONT_DEFAULT).coerceIn(FONT_MIN, FONT_MAX)

    fun setFontSize(context: Context, size: Int) {
        prefs(context).edit()
            .putInt(KEY_FONT_SIZE, size.coerceIn(FONT_MIN, FONT_MAX))
            .apply()
    }

    fun isExtraKeysEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_EXTRA_KEYS, true)

    fun setExtraKeysEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_EXTRA_KEYS, enabled).apply()
    }

    fun getGuestLoginShell(context: Context): GuestLoginShell =
        GuestLoginShell.fromId(
            prefs(context).getString(KEY_GUEST_LOGIN_SHELL, null)
        )

    fun setGuestLoginShell(context: Context, shell: GuestLoginShell) {
        prefs(context).edit()
            .putString(KEY_GUEST_LOGIN_SHELL, shell.id)
            .apply()
    }

    /** Auto-fullscreen: new terminals open with system bars hidden. */
    fun isAutoFullscreen(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_FULLSCREEN, false)

    fun setAutoFullscreen(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_FULLSCREEN, on).apply()
    }

    /** true → open proot terminals via python `proot-distro login` (old path). */
    fun isLegacyProotLauncher(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LEGACY_PROOT_LAUNCHER, false)

    fun setLegacyProotLauncher(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LEGACY_PROOT_LAUNCHER, enabled).apply()
    }

    /**
     * Raw JSON of the configurable shortcut bar (see `ui/terminal/ShortcutBarConfig`).
     * Null when never saved — callers decode to defaults; a null write removes the
     * value so the next load is a clean migration to defaults again.
     */
    fun getShortcutBarJson(context: Context): String? =
        prefs(context).getString(KEY_SHORTCUT_BAR, null)

    fun setShortcutBarJson(context: Context, json: String?) {
        prefs(context).edit().apply {
            if (json == null) remove(KEY_SHORTCUT_BAR) else putString(KEY_SHORTCUT_BAR, json)
        }.apply()
    }

    fun preferZsh(context: Context): Boolean =
        getGuestLoginShell(context) == GuestLoginShell.ZSH

    // ── Launch sticker (banner) ─────────────────────────────────────────────

    /** Banner quality level L1..L5 (default L2 = 40 cols), clamped. */
    fun getBannerQuality(context: Context): Int =
        prefs(context).getInt(KEY_BANNER_QUALITY, BANNER_QUALITY_DEFAULT)
            .coerceIn(BANNER_QUALITY_MIN, BANNER_QUALITY_MAX)

    fun setBannerQuality(context: Context, q: Int) {
        prefs(context).edit()
            .putInt(KEY_BANNER_QUALITY, q.coerceIn(BANNER_QUALITY_MIN, BANNER_QUALITY_MAX))
            .apply()
    }

    /**
     * The key/button colour of the terminal's extra keys.
     * 0 = follow the terminal's own dark surface (the default).
     */
    fun getButtonColor(context: Context): Int =
        prefs(context).getInt(KEY_BUTTON_COLOR, 0)

    fun setButtonColor(context: Context, argb: Int) {
        prefs(context).edit().putInt(KEY_BUTTON_COLOR, argb).apply()
        _lookRevision.value += 1
    }

    /**
     * The label colour of the terminal's extra keys and chrome.
     * 0 = follow the terminal's own text colour (the default).
     */
    fun getTextColor(context: Context): Int =
        prefs(context).getInt(KEY_TEXT_COLOR, 0)

    fun setTextColor(context: Context, argb: Int) {
        prefs(context).edit().putInt(KEY_TEXT_COLOR, argb).apply()
        _lookRevision.value += 1
    }
}
