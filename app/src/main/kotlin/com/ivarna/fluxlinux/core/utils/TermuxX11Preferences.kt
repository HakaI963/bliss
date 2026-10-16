package com.ivarna.fluxlinux.core.utils

import android.content.Context
import android.content.Intent
import android.preference.PreferenceManager
import android.util.Log

/**
 * Embedded Termux:X11 (Lorie) display prefs.
 *
 * Reads/writes the **same** default SharedPreferences keys the in-process X11
 * activity uses (`displayScale`, `fullscreen`, …). Changes broadcast
 * [ACTION_PREFERENCES_CHANGED] so a running display reloads immediately.
 */
@Suppress("DEPRECATION")
object TermuxX11Preferences {

    private const val TAG = "TermuxX11Prefs"
    const val ACTION_PREFERENCES_CHANGED = "com.termux.x11.ACTION_PREFERENCES_CHANGED"

    // Lorie preference keys (must match termux-x11 preferences.xml)
    private const val KEY_DISPLAY_SCALE = "displayScale"
    private const val KEY_FULLSCREEN = "fullscreen"
    private const val KEY_HIDE_CUTOUT = "hideCutout"
    private const val KEY_KEEP_SCREEN_ON = "keepScreenOn"
    private const val KEY_POINTER_CAPTURE = "pointerCapture"
    private const val KEY_SHOW_ADDITIONAL_KBD = "showAdditionalKbd"
    private const val KEY_SHOW_IME = "showIMEWhileExternalConnected"
    private const val KEY_PREFER_SCANCODES = "preferScancodes"
    private const val KEY_SCANCODE_WORKAROUND = "hardwareKbdScancodesWorkaround"
    private const val KEY_RESOLUTION_MODE = "displayResolutionMode"
    private const val KEY_DISPLAY_STRETCH = "displayStretch"
    private const val KEY_CLIPBOARD_ENABLE = "clipboardEnable"
    private const val KEY_TOUCH_MODE = "touchMode"
    private const val KEY_SCALE_TOUCHPAD = "scaleTouchpad"

    /**
     * Default X screen scale for fresh installs. `scaled` mode derives the X
     * resolution from the X11 view (`view * 100 / scale`), so the picture always
     * matches the device aspect ratio and fills the screen (see LorieView).
     * 120 keeps phone-sized UI legible without shrinking the workspace too much.
     */
    private const val DEFAULT_DISPLAY_SCALE = 120

    private fun prefs(context: Context) =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    // ── Display ──────────────────────────────────────────────────────────────

    fun getDisplayScale(context: Context): Int =
        prefs(context).getInt(KEY_DISPLAY_SCALE, 120)

    fun setDisplayScale(context: Context, scale: Int) {
        prefs(context).edit().putInt(KEY_DISPLAY_SCALE, scale.coerceIn(30, 300)).apply()
        notifyChanged(context, KEY_DISPLAY_SCALE)
    }

    /**
     * Fullscreen default is **true** for unset installs: the desktop was
     * letterboxed by the status/navigation bars in non-fullscreen mode (user
     * report: "not full screen, big black bezel"). Users who explicitly turned
     * it off keep their stored `false` — only the unset default changed.
     */
    fun getFullscreen(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FULLSCREEN, true)

    fun setFullscreen(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FULLSCREEN, enabled).apply()
        notifyChanged(context, KEY_FULLSCREEN)
    }

    /**
     * Cutout overlay follows the fullscreen default: edge-to-edge on fresh
     * installs (the display activity uses LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS),
     * stored value wins once the user touches the switch.
     */
    fun getHideCutout(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HIDE_CUTOUT, true)

    fun setHideCutout(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_HIDE_CUTOUT, enabled).apply()
        notifyChanged(context, KEY_HIDE_CUTOUT)
    }

    fun getKeepScreenOn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_KEEP_SCREEN_ON, true)

    fun setKeepScreenOn(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_KEEP_SCREEN_ON, enabled).apply()
        notifyChanged(context, KEY_KEEP_SCREEN_ON)
    }

    // ── Input ────────────────────────────────────────────────────────────────

    fun getCapturePointer(context: Context): Boolean =
        prefs(context).getBoolean(KEY_POINTER_CAPTURE, false)

    fun setCapturePointer(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_POINTER_CAPTURE, enabled).apply()
        notifyChanged(context, KEY_POINTER_CAPTURE)
    }

    fun getShowAdditionalKeyboard(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_ADDITIONAL_KBD, false)

    fun setShowAdditionalKeyboard(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_ADDITIONAL_KBD, enabled).apply()
        if (enabled) {
            prefs(context).edit().putBoolean("additionalKbdVisible", true).apply()
        }
        notifyChanged(context, KEY_SHOW_ADDITIONAL_KBD)
    }

    fun getShowIME(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_IME, true)

    fun setShowIME(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_IME, enabled).apply()
        notifyChanged(context, KEY_SHOW_IME)
    }

    fun getPreferScancodes(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PREFER_SCANCODES, false)

    fun setPreferScancodes(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PREFER_SCANCODES, enabled).apply()
        notifyChanged(context, KEY_PREFER_SCANCODES)
    }

    fun getScancodeWorkaround(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SCANCODE_WORKAROUND, true)

    fun setScancodeWorkaround(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SCANCODE_WORKAROUND, enabled).apply()
        notifyChanged(context, KEY_SCANCODE_WORKAROUND)
    }

    /**
     * Ensure common defaults exist and notify a running X11 activity.
     * Call before opening the display (DesktopLauncher / Open X11).
     *
     * Fresh installs default to a **fullscreen, edge-to-edge, aspect-matched**
     * display: `fullscreen` + `hideCutout` ON, `scaled` resolution (X screen
     * follows the view aspect → no letterbox) and `displayStretch` as a safety
     * net for frozen/mismatched dimensions. Only keys that were never written
     * are filled in — existing users keep their stored values.
     */
    fun applyToTermux(context: Context) {
        try {
            val p = prefs(context)
            val edit = p.edit()
            if (!p.contains(KEY_FULLSCREEN)) {
                edit.putBoolean(KEY_FULLSCREEN, true)
            }
            if (!p.contains(KEY_HIDE_CUTOUT)) {
                edit.putBoolean(KEY_HIDE_CUTOUT, true)
            }
            if (!p.contains(KEY_DISPLAY_SCALE)) {
                edit.putInt(KEY_DISPLAY_SCALE, DEFAULT_DISPLAY_SCALE)
            }
            if (!p.contains(KEY_RESOLUTION_MODE)) {
                edit.putString(KEY_RESOLUTION_MODE, "scaled")
            }
            if (!p.contains(KEY_DISPLAY_STRETCH)) {
                edit.putBoolean(KEY_DISPLAY_STRETCH, true)
            }
            if (!p.contains(KEY_CLIPBOARD_ENABLE)) {
                edit.putBoolean(KEY_CLIPBOARD_ENABLE, true)
            }
            if (!p.contains(KEY_TOUCH_MODE)) {
                edit.putString(KEY_TOUCH_MODE, "1")
            }
            if (!p.contains(KEY_SCALE_TOUCHPAD)) {
                edit.putBoolean(KEY_SCALE_TOUCHPAD, true)
            }
            edit.apply()
            notifyChanged(context, null)
            Log.d(TAG, "Notified embedded X11 of preference apply")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply preferences", e)
        }
    }

    /** Open full embedded Lorie preferences activity. */
    fun openTermuxX11Preferences(context: Context) {
        if (!EmbeddedX11.launchPreferences(context)) {
            Log.e(TAG, "Failed to open embedded X11 preferences")
        }
    }

    private fun notifyChanged(context: Context, key: String?) {
        try {
            val intent = Intent(ACTION_PREFERENCES_CHANGED).apply {
                putExtra("key", key ?: "")
                putExtra("fromBroadcast", true)
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Broadcast prefs changed failed: ${e.message}")
        }
    }
}
