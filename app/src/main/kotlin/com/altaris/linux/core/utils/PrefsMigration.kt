package com.altaris.linux.core.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Moves the app's preference files to their Altaris names.
 *
 * The product was renamed from Flux/Bliss to Altaris, and the preference file
 * names changed with it. Without this pass an upgrade would look like a fresh
 * install: onboarding would run again, clone settings and the guest shell
 * markers would be lost. The legacy files are copied once and then left
 * untouched.
 */
object PrefsMigration {

    private const val TAG = "AltarisPrefs"

    /** Legacy file name → current file name. */
    private val MAPPING = mapOf(
        "fluxlinux_state" to "altaris_state",
        "bliss_clone_ai" to "altaris_clone_ai",
        "bliss_clone_cfg" to "altaris_clone_cfg",
        "bliss_clone_settings" to "altaris_clone_settings",
        "bliss_shell_integration" to "altaris_shell_integration",
        "flux_guest_repairs" to "altaris_guest_repairs",
        "flux_terminal_prefs" to "altaris_terminal_prefs",
    )

    /** Copies every legacy preference into its new home. Safe to call often. */
    fun migrate(context: Context) {
        runCatching {
            MAPPING.forEach { (legacy, current) ->
                val source = context.getSharedPreferences(legacy, Context.MODE_PRIVATE)
                if (source.all.isEmpty()) return@forEach
                val target = context.getSharedPreferences(current, Context.MODE_PRIVATE)
                if (target.all.isNotEmpty()) return@forEach
                val editor = target.edit()
                source.all.forEach { (key, value) -> put(editor, key, value) }
                editor.apply()
                Log.i(TAG, "migrated $legacy -> $current (${source.all.size} keys)")
            }
        }.onFailure { Log.w(TAG, "preference migration failed", it) }
    }

    /** Writes one value with the type it was stored as. */
    private fun put(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is String -> editor.putString(key, value)
            is Set<*> -> {
                @Suppress("UNCHECKED_CAST")
                editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
            else -> Unit
        }
    }
}
