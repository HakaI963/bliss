package com.altaris.linux.core.quinn

/**
 * Slash commands, for the agent and for Quinn.
 *
 * Two namespaces share the composer:
 *  - `/cmd`  — the selected agent's own commands (`/btw`, `/goal`, …), read
 *              live from the agent's config directories when it has any;
 *  - `//cmd` — Quinn's commands about Altaris itself, which Quinn answers from
 *              the live app state (distros, agents, what they are doing).
 *
 * Pure data so the parsing and filtering can be tested without a device.
 */
object SlashCommands {

    /** A command offered in the composer. */
    data class Command(
        val name: String,
        val description: String,
        /** Quinn namespace when true, agent namespace when false. */
        val quinn: Boolean = false,
        /** True when the command takes an argument. */
        val takesArgument: Boolean = false,
    ) {
        /** What the user types to invoke it. */
        val token: String get() = if (quinn) "//$name" else "/$name"
    }

    /**
     * Quinn's built-in commands.
     *
     * Every one of these is answered from live app state, not from a guess:
     * that is the whole point of `//` being separate from the agent's `/`.
     */
    val QUINN: List<Command> = listOf(
        Command("help", "What Quinn can do, and what each command shows", quinn = true),
        Command("btw", "Ask Quinn a side question without interrupting the agent", quinn = true, takesArgument = true),
        Command("status", "What every agent is doing right now", quinn = true),
        Command("distros", "Installed distros, size and method", quinn = true),
        Command("agents", "Every agent Quinn can reach, and where", quinn = true),
        Command("files", "Browse a distro's files", quinn = true, takesArgument = true),
        Command("rea", "REA state: environment, tools, model", quinn = true),
        Command("model", "Which model Quinn itself is using", quinn = true),
        Command("context", "What is filling this conversation's context", quinn = true),
    )

    /**
     * Fallback agent commands, used when the agent's own list cannot be read.
     *
     * These are the common ones across Claude Code, Codex and opencode; an
     * agent that exposes a real list overrides this entirely.
     */
    val AGENT_DEFAULTS: List<Command> = listOf(
        Command("btw", "Add a side note to the agent's context", takesArgument = true),
        Command("goal", "Restate what this session is trying to achieve", takesArgument = true),
        Command("clear", "Start a fresh session with this agent"),
        Command("compact", "Compress the conversation to free context"),
        Command("model", "Switch this agent's model", takesArgument = true),
        Command("undo", "Revert the agent's last change"),
        Command("review", "Review the current diff", takesArgument = true),
    )

    /**
     * What the composer is currently typing, if it is a command.
     *
     * Returns null when the text is not a command, so a normal message that
     * happens to contain a slash is never hijacked.
     */
    data class Draft(
        val quinn: Boolean,
        /** The command name without slashes. */
        val name: String,
        /** Everything after the command name. */
        val argument: String,
    ) {
        /** True when the user has typed a name but not yet a space. */
        val typingName: Boolean get() = !argument.contains(' ') && argument.isBlank()
    }

    /**
     * Parse [text] as a command draft.
     *
     * `//x` is Quinn, `/x` is the agent. A bare `/` or `//` is not a command
     * yet (the user may be about to type one), so it parses with an empty name.
     */
    fun parse(text: String): Draft? {
        val trimmed = text.trimStart()
        if (trimmed.startsWith("//")) {
            val rest = trimmed.removePrefix("//")
            return Draft(
                quinn = true,
                name = rest.substringBefore(' '),
                argument = rest.substringAfter(' ', ""),
            )
        }
        if (trimmed.startsWith("/")) {
            val rest = trimmed.removePrefix("/")
            return Draft(
                quinn = false,
                name = rest.substringBefore(' '),
                argument = rest.substringAfter(' ', ""),
            )
        }
        return null
    }

    /**
     * Commands to suggest for [draft].
     *
     * Matches on the name prefix, so typing `/go` offers `/goal` and typing
     * `//st` offers `//status`. An exact match still appears, because the user
     * may be about to type an argument.
     */
    fun suggestions(draft: Draft?, agentCommands: List<Command> = AGENT_DEFAULTS): List<Command> {
        if (draft == null) return emptyList()
        val pool = if (draft.quinn) QUINN else agentCommands
        val prefix = draft.name.lowercase()
        return pool.filter { it.name.lowercase().startsWith(prefix) }
            .sortedBy { it.name }
    }

    /** True when the text is a complete command invocation (name + argument). */
    fun isInvocation(draft: Draft?): Boolean =
        draft != null && draft.name.isNotBlank() && draft.argument.isNotBlank()
}
