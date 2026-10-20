package com.ivarna.fluxlinux.core.utils

import android.content.Context
import android.content.SharedPreferences

enum class ThemeMode {
    LIGHT,
    DARK,
    GRUVBOX,
    NORD,
    DRACULA,
    SOLARIZED,
    MONOKAI,
    SKY_BREEZE,
    LAVENDER_DREAM,
    MINT_FRESH,
    AMOLED_BLACK,
    SYSTEM
}

/**
 * Accent skins. Surfaces stay AOSP neutral in both light and dark; [label] is
 * the user-facing name in Settings.
 */
enum class ThemePalette(val label: String) {
    CARBON("Blue"),
    SLATE("Slate"),
    PLUM("Plum"),
    SAND("Sand")
}

class ThemePreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("theme_prefs", Context.MODE_PRIVATE)

    fun getThemeMode(): ThemeMode {
        val modeName = prefs.getString("theme_mode", ThemeMode.LIGHT.name)
        return try {
            ThemeMode.valueOf(modeName ?: ThemeMode.LIGHT.name)
        } catch (e: IllegalArgumentException) {
            ThemeMode.LIGHT
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString("theme_mode", mode.name).apply()
    }
}
