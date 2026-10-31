package com.altaris.linux.core.quinn

import android.content.Context

/**
 * The chat's remembered selection.
 *
 * Losing the picked agent, session and model on every app restart made the
 * screen feel like it reset itself. This stores exactly the selection — never
 * message content, which stays in memory.
 */
object QuinnPrefs {

    private const val PREFS = "altaris_quinn_prefs"
    private const val KEY_AGENT = "selected_agent"
    private const val KEY_SESSION = "active_session"
    private const val KEY_AGENT_MODEL = "agent_model"

    data class Selection(
        /** Target key (`distro/binary`) of the last agent, or blank. */
        val agentKey: String = "",
        /** Session id of the last open thread, or blank. */
        val sessionId: String = "",
        /** Model chosen for the agent (distinct from Quinn's own model). */
        val agentModel: String = "",
    )

    fun load(context: Context): Selection {
        val p = prefs(context)
        return Selection(
            agentKey = p.getString(KEY_AGENT, "").orEmpty(),
            sessionId = p.getString(KEY_SESSION, "").orEmpty(),
            agentModel = p.getString(KEY_AGENT_MODEL, "").orEmpty(),
        )
    }

    fun save(context: Context, selection: Selection) {
        prefs(context).edit()
            .putString(KEY_AGENT, selection.agentKey)
            .putString(KEY_SESSION, selection.sessionId)
            .putString(KEY_AGENT_MODEL, selection.agentModel)
            .apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
