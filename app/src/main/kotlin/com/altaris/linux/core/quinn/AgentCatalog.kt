package com.altaris.linux.core.quinn

/**
 * How one coding agent is driven from Quinn.
 *
 * Every agent has a different headless interface, so there is no universal
 * command: opencode takes `run -s <session> -m <model> -f <file>`, Claude takes
 * `-p`, Codex takes `exec`, Gemini takes `-p`, Aider takes `--message`. This
 * describes the shape each one needs instead of hardcoding one agent into the
 * UI.
 *
 * [kind] picks the strategy:
 *  - [Kind.HEADLESS] — a one-shot non-interactive command whose stdout is the
 *    reply. Structured, reliable, but only works for agents we know.
 *  - [Kind.PTY] — type into an interactive session. Works for anything,
 *    including unknown agents, at the cost of scraping the terminal.
 *
 * A profile is pure data so the whole adapter table is unit tested without a
 * device.
 */
data class AgentProfile(
    /** Binary name as it appears on PATH inside the distro. */
    val binary: String,
    val label: String,
    /** HEADLESS when we know the interface, PTY for the generic fallback. */
    val kind: Kind = Kind.PTY,
    /** Argument template for HEADLESS agents; see [AgentCommand]. */
    val template: String = "",
    /** True when this agent keeps named sessions we can resume. */
    val resumable: Boolean = false,
    /** True when the agent accepts file attachments on the command line. */
    val attachments: Boolean = false,
    /** Extra environment the agent needs to run non-interactively. */
    val env: Map<String, String> = emptyMap(),
    /** Human note shown when the agent cannot be driven automatically. */
    val note: String = "",
) {
    enum class Kind { HEADLESS, PTY }
}

/**
 * The known agents, in the order Quinn lists them.
 *
 * Adding an agent is one entry here plus (for HEADLESS) its template — no
 * screen changes. An agent that is installed but absent from this table is
 * still usable: Quinn falls back to [AgentProfile.Kind.PTY].
 */
object AgentCatalog {

    /**
     * Argument templates. Tokens:
     *  - `{prompt}`  the message text (shell-quoted by the caller)
     *  - `{session}` the session id, or nothing when the agent has no session
     *  - `{model}`   the model, or nothing when the user did not pick one
     *  - `{file}`    one `--file <path>` style attachment group per file
     *
     * A template is only ever built with shell-quoted values, never by
     * concatenating user text into a command.
     */
    val PROFILES: List<AgentProfile> = listOf(
        AgentProfile(
            binary = "opencode",
            label = "OpenCode",
            kind = AgentProfile.Kind.HEADLESS,
            // `opencode run` prints the assistant reply on stdout; -s resumes a
            // named session, which is what keeps Quinn's threads stable.
            template = "run --standalone {session} {model} {file} --format json -- {prompt}",
            resumable = true,
            attachments = true,
        ),
        AgentProfile(
            binary = "claude",
            label = "Claude Code",
            kind = AgentProfile.Kind.HEADLESS,
            template = "-p {prompt} {session} {model}",
            resumable = true,
            note = "Session resume uses --resume <id>; attachments go through the prompt.",
        ),
        AgentProfile(
            binary = "codex",
            label = "Codex",
            kind = AgentProfile.Kind.HEADLESS,
            template = "exec {model} -- {prompt}",
            resumable = false,
            note = "Codex has no named sessions on the CLI; each prompt is independent.",
        ),
        AgentProfile(
            binary = "gemini",
            label = "Gemini CLI",
            kind = AgentProfile.Kind.HEADLESS,
            template = "-p {prompt} {model}",
            resumable = false,
        ),
        AgentProfile(
            binary = "aider",
            label = "Aider",
            kind = AgentProfile.Kind.HEADLESS,
            template = "--message {prompt} --yes {model}",
            resumable = false,
            attachments = true,
        ),
        AgentProfile(binary = "hermes", label = "Hermes"),
        AgentProfile(binary = "alpha", label = "Alpha"),
        AgentProfile(binary = "pi", label = "Pi"),
        AgentProfile(binary = "cursor-agent", label = "Cursor Agent"),
        AgentProfile(binary = "copilot", label = "Copilot CLI"),
    )

    /** Profile for [binary], or a PTY fallback so unknown agents still work. */
    fun forBinary(binary: String): AgentProfile =
        PROFILES.firstOrNull { it.binary == binary }
            ?: AgentProfile(binary = binary, label = binary)

    /** True when Quinn can drive this binary without scraping a terminal. */
    fun isHeadless(binary: String): Boolean = forBinary(binary).kind == AgentProfile.Kind.HEADLESS
}

/**
 * Builds the argv for one Quinn turn.
 *
 * Kept separate from process launching so the exact command a turn produces can
 * be asserted in tests — the part that silently breaks when an agent changes
 * its flags.
 */
object AgentCommand {

    /**
     * Build the shell command for [profile].
     *
     * @param prompt the user's message
     * @param session a stable session id, or null when the agent has none
     * @param model the model id, or null to use the agent's default
     * @param files absolute paths inside the guest
     */
    fun build(
        profile: AgentProfile,
        prompt: String,
        session: String? = null,
        model: String? = null,
        files: List<String> = emptyList(),
    ): String {
        if (profile.kind != AgentProfile.Kind.HEADLESS || profile.template.isBlank()) {
            return ptyCommand(profile.binary)
        }
        val sessionArg = if (profile.resumable && !session.isNullOrBlank()) {
            sessionFlag(profile.binary, session)
        } else {
            ""
        }
        val modelArg = if (!model.isNullOrBlank()) modelFlag(profile.binary, model) else ""
        val fileArg = if (profile.attachments && files.isNotEmpty()) {
            files.joinToString(" ") { " --file " + quote(it) }
        } else {
            ""
        }
        return profile.binary + " " + profile.template
            .replace("{prompt}", quote(prompt))
            .replace("{session}", sessionArg)
            .replace("{model}", modelArg)
            .replace("{file}", fileArg)
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /** The plain command that opens the agent's interactive session. */
    fun ptyCommand(binary: String): String = binary

    private fun sessionFlag(binary: String, session: String): String = when (binary) {
        "claude" -> "--resume " + quote(session)
        else -> "--session " + quote(session)
    }

    private fun modelFlag(binary: String, model: String): String = when (binary) {
        "claude" -> "--model " + quote(model)
        "codex" -> "--model " + quote(model)
        "aider" -> "--model " + quote(model)
        else -> "--model " + quote(model)
    }

    /**
     * POSIX single-quote. Everything is quoted, including the model and the
     * session id: they come from the network (a fetched model list) and must
     * never be able to break out of the command.
     */
    fun quote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
