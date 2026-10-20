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
 * Accent skins. Surfaces stay Midnight/Day in every mode; [label] is the
 * user-facing name in Settings. The enum keys are persisted, so they are not
 * renamed even when the visible label changes.
 */
enum class ThemePalette(val label: String) {
    CARBON("Mint"),
    SLATE("Glacier"),
    PLUM("Violet"),
    SAND("Amber")
}

class ThemePreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("theme_prefs", Context.MODE_PRIVATE)

    fun getThemeMode(): ThemeMode {
        val modeName = prefs.getString("theme_mode", ThemeMode.DARK.name)
        return try {
            ThemeMode.valueOf(modeName ?: ThemeMode.DARK.name)
        } catch (e: IllegalArgumentException) {
            ThemeMode.DARK
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString("theme_mode", mode.name).apply()
    }
}
