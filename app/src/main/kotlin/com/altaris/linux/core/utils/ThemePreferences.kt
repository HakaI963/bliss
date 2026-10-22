package com.altaris.linux.core.utils

import android.content.Context
import android.content.SharedPreferences
import com.altaris.linux.ui.theme.AltarisScheme

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


class ThemePreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("theme_prefs", Context.MODE_PRIVATE)

    /**
     * v3.16.0 is a visual reset (Midnight). Older installs may have persisted
     * the old Light default; migrate once so the new UI is actually visible.
     * Choosing Day in Settings afterwards is preserved.
     */
    fun migrateToMidnight() {
        if (prefs.getInt("theme_rev", 0) < 1) {
            prefs.edit()
                .putString("theme_mode", ThemeMode.DARK.name)
                .putInt("theme_rev", 1)
                .apply()
        }
    }

    fun getThemeMode(): ThemeMode {
        val modeName = prefs.getString("theme_mode", ThemeMode.DARK.name)
        return try {
            ThemeMode.valueOf(modeName ?: ThemeMode.DARK.name)
        } catch (e: IllegalArgumentException) {
            ThemeMode.DARK
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit()
            .putString("theme_mode", mode.name)
            .putInt("theme_rev", 1)
            .apply()
    }

    /**
     * The active Altaris identity. Persisted under "scheme"; unknown values
     * fall back to the default identity so a downgrade never breaks the UI.
     */
    fun getScheme(): AltarisScheme {
        val name = prefs.getString("scheme", AltarisScheme.GRAPHITE.name)
        return try {
            AltarisScheme.valueOf(name ?: AltarisScheme.GRAPHITE.name)
        } catch (e: IllegalArgumentException) {
            AltarisScheme.GRAPHITE
        }
    }

    fun setScheme(scheme: AltarisScheme) {
        prefs.edit()
            .putString("scheme", scheme.name)
            .apply()
    }
}
