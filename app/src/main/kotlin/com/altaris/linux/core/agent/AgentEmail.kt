package com.altaris.linux.core.agent

import android.content.Context

/**
 * The email address the agent may use to sign in to a site.
 *
 * Stored on the device only, in the app's private preferences — never uploaded,
 * never written into an agent config, and never logged. The agent is told it
 * exists and what it is; it is not a password, and there is deliberately no
 * password field here.
 *
 * Why this exists at all: an agent driving a browser hits sign-in walls, and
 * without an address it either stops or invents one. Giving it the user's own
 * address is the honest option, and it is the user's to withhold.
 */
object AgentEmail {

    private const val PREFS = "altaris_agent_identity"
    private const val KEY_EMAIL = "email"

    fun get(context: Context): String =
        prefs(context).getString(KEY_EMAIL, "").orEmpty().trim()

    fun set(context: Context, email: String) {
        val value = email.trim()
        prefs(context).edit().apply {
            if (value.isBlank()) remove(KEY_EMAIL) else putString(KEY_EMAIL, value)
        }.apply()
    }

    fun clear(context: Context) = set(context, "")

    /**
     * True when [value] looks like an address.
     *
     * Deliberately loose: one `@` with text either side and a dot in the domain.
     * A stricter check would reject valid addresses and there is nothing to
     * protect here — the value is the user's own.
     */
    fun looksValid(value: String): Boolean {
        val v = value.trim()
        if (v.isEmpty()) return true // clearing is always allowed
        val at = v.indexOf('@')
        if (at <= 0 || at != v.lastIndexOf('@')) return false
        val domain = v.substring(at + 1)
        return domain.contains('.') && !domain.startsWith(".") && !domain.endsWith(".")
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
