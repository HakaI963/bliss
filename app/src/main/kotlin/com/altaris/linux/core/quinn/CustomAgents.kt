package com.altaris.linux.core.quinn

import android.content.Context

/**
 * Agent binaries the user added by hand.
 *
 * Detection is deliberately narrow (a curated list plus agent-shaped names in
 * agent directories), because scanning all of `/usr/bin` filled the picker with
 * distribution plumbing. That precision means an unusual agent can be missed,
 * so the user can name it once and it is remembered across restarts.
 *
 * Stored in SharedPreferences: this is configuration, not a secret.
 */
object CustomAgents {

    private const val PREFS = "altaris_quinn_custom_agents"
    private const val KEY = "binaries"

    /** One entry: the binary name, and an optional distro to scope it to. */
    data class Entry(val binary: String, val distroId: String = "") {
        /** Stable key for de-duplication. */
        val key: String get() = if (distroId.isBlank()) binary else "$distroId/$binary"
    }

    fun load(context: Context): List<Entry> {
        val raw = prefs(context).getStringSet(KEY, emptySet()).orEmpty()
        return raw.mapNotNull { line ->
            val parts = line.split('\u001f')
            val binary = parts.firstOrNull()?.trim().orEmpty()
            if (binary.isBlank()) null
            else Entry(binary, parts.getOrNull(1)?.trim().orEmpty())
        }.sortedBy { it.binary.lowercase() }
    }

    /** Add [binary], optionally scoped to [distroId]. Blank input is ignored. */
    fun add(context: Context, binary: String, distroId: String = ""): Boolean {
        val clean = binary.trim()
            .removePrefix("/")
            .substringAfterLast('/')
            .trim()
        if (clean.isBlank()) return false
        val current = load(context)
        val entry = Entry(clean, distroId.trim())
        if (current.any { it.key == entry.key }) return false
        prefs(context).edit()
            .putStringSet(KEY, (current.map { it.key } + entry.key).toSet())
            .apply()
        return true
    }

    fun remove(context: Context, entry: Entry) {
        val remaining = load(context).filterNot { it.key == entry.key }.map { it.key }.toSet()
        prefs(context).edit().putStringSet(KEY, remaining).apply()
    }

    /** True when [binary] was added by hand (used for the "custom" badge). */
    fun isCustom(context: Context, binary: String, distroId: String): Boolean =
        load(context).any { it.binary == binary && (it.distroId.isBlank() || it.distroId == distroId) }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
