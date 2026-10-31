package com.altaris.linux.core.quinn

import java.util.UUID

/** What kind of thing a Quinn target is. */
enum class TargetKind {
    /** A coding agent CLI inside a distro or the host prefix. */
    AGENT,

    /**
     * The built-in REA analysis environment.
     *
     * It is a target like any other in the chat: you attach a file, pick REA,
     * and send. The difference is internal — an agent target runs the agent's
     * CLI, while this one drives REA's MCP tools through the configured model.
     */
    REA,
}

/** One (distro, agent) pair Quinn can talk to. */
data class AgentTarget(
    /** Distro id, e.g. `debian` or `fedora_chroot`. */
    val distroId: String,
    val distroName: String,
    val method: String,
    /** Binary name, e.g. `opencode`. */
    val binary: String,
    val label: String,
    val headless: Boolean,
    val kind: TargetKind = TargetKind.AGENT,
) {
    /** Stable identity: the same agent in two distros is two targets. */
    val key: String get() = "$distroId/$binary"

    val displayName: String get() = "$label · $distroName"

    val isRea: Boolean get() = kind == TargetKind.REA
}

/** Who wrote one message. */
enum class TurnRole { USER, AGENT, SYSTEM }

/** One message in a Quinn thread. */
data class Turn(
    val role: TurnRole,
    val text: String,
    val at: Long = System.currentTimeMillis(),
    /** Attachment paths shown under the message. */
    val files: List<String> = emptyList(),
    /** True while the agent is still producing this message. */
    val streaming: Boolean = false,
    /** Non-null when the turn failed; shown in the bubble. */
    val error: String? = null,
    /** Tool steps, for a REA turn. Empty for a plain agent reply. */
    val steps: List<ReaAnalysis.Step> = emptyList(),
)

/**
 * One conversation with one agent in one distro.
 *
 * Sessions are per target, which is what keeps several agents from interfering:
 * two sessions never share a working directory, a session id or a process.
 */
data class QuinnSession(
    val id: String = UUID.randomUUID().toString(),
    val target: AgentTarget,
    val turns: List<Turn> = emptyList(),
    /** Session id passed to the agent for resume, stable for the thread. */
    val agentSessionId: String = UUID.randomUUID().toString(),
    val model: String? = null,
    val running: Boolean = false,
    val title: String = "",
) {
    /** The thread title: the first user message, trimmed. */
    val displayTitle: String
        get() = title.ifBlank {
            turns.firstOrNull { it.role == TurnRole.USER }?.text
                ?.trim()?.replace('\n', ' ')?.take(40)
                ?.ifBlank { null }
                ?: target.displayName
        }

    /** True when this thread is the REA analysis target. */
    val isRea: Boolean get() = target.isRea
}

/**
 * Splits a fan-out request into the per-target messages it should send.
 *
 * Pure, so the routing rule — "one prompt, several agents, no crosstalk" — is
 * testable without a device.
 */
object FanOut {

    /**
     * Targets to send [prompt] to.
     *
     * An explicit selection wins. With no selection Quinn uses the session's
     * own target, so a normal send never becomes a broadcast by accident.
     */
    fun targetsFor(
        prompt: String,
        selected: List<AgentTarget>,
        current: AgentTarget?,
    ): List<AgentTarget> = when {
        prompt.isBlank() -> emptyList()
        selected.isNotEmpty() -> selected.distinctBy { it.key }
        current != null -> listOf(current)
        else -> emptyList()
    }
}
