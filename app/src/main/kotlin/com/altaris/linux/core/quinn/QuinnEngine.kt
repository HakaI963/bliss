package com.altaris.linux.core.quinn

import android.content.Context
import android.util.Log
import com.altaris.linux.core.control.AgentDetector
import com.altaris.linux.core.control.DetectedAgent
import com.altaris.linux.core.control.DistroAgentReport
import com.altaris.linux.core.rea.GuestExec
import com.altaris.linux.core.rea.ReaDistro
import com.altaris.linux.core.terminal.HostCommandBuilder
import com.altaris.linux.core.terminal.ShellCommandRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Quinn's engine: discovery plus one process per (distro, agent) turn.
 *
 * Design rules that matter:
 *  - Quinn is a *master of agents*, not an agent. It never holds a model key
 *    for its own reasoning; every agent turn runs in the agent you already
 *    have. The one exception is the REA target, which is a tool server with no
 *    model of its own and therefore uses the configured endpoint.
 *  - Discovery is live, not a fixed list: install an agent in a distro and the
 *    next scan picks it up with no registration step.
 *  - Each turn is its own process keyed by the session's agent-session-id, so
 *    two agents never share state and a fan-out cannot cross wires.
 *  - A turn runs on [Dispatchers.IO]; the UI observes [sessions] only.
 */
object QuinnEngine {

    private const val TAG = "Quinn"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Every live thread, keyed by [QuinnSession.id]. */
    private val _sessions = MutableStateFlow<Map<String, QuinnSession>>(emptyMap())
    val sessions: StateFlow<Map<String, QuinnSession>> = _sessions.asStateFlow()

    /** Every target Quinn can reach right now (agents + REA). */
    private val _targets = MutableStateFlow<List<AgentTarget>>(emptyList())
    val targets: StateFlow<List<AgentTarget>> = _targets.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _runningCount = MutableStateFlow(0)
    /** How many turns are in flight right now, across every agent. */
    val runningCount: StateFlow<Int> = _runningCount.asStateFlow()

    /** Ordered list of session ids, newest first, for the session list. */
    private val _order = MutableStateFlow<List<String>>(emptyList())
    val order: StateFlow<List<String>> = _order.asStateFlow()

    // ------------------------------------------------------------------ discovery

    /**
     * Rescan every installed distro for agent binaries.
     *
     * Uses the same [AgentDetector] the Tools screen shows, so the two can
     * never disagree about what is installed. REA is appended as a target of
     * its own when the analysis environment is ready.
     */
    fun refreshTargets(context: Context) {
        if (!_scanning.compareAndSet(false, true)) return
        scope.launch {
            try {
                val reports: List<DistroAgentReport> = AgentDetector.scanDistros(context)
                val found = ArrayList<AgentTarget>()
                for (report in reports) {
                    for (agent in report.agents) {
                        found.add(toTarget(report, agent))
                    }
                }
                // Host agents are reachable too, as their own "distro".
                for (agent in AgentDetector.scan(context).filter { it.kind == "host cli" }) {
                    found.add(
                        AgentTarget(
                            distroId = HOST_DISTRO,
                            distroName = "Host",
                            method = "host",
                            binary = agent.name,
                            label = AgentCatalog.forBinary(agent.name).label,
                            headless = AgentCatalog.isHeadless(agent.name),
                        )
                    )
                }
                reaTarget(context)?.let { found.add(it) }
                _targets.value = found
                    .distinctBy { it.key }
                    .sortedWith(compareBy({ it.kind.ordinal }, { it.distroName.lowercase() }, { it.label.lowercase() }))
                _lastError.value = null
            } catch (t: Throwable) {
                Log.w(TAG, "agent scan failed", t)
                _lastError.value = t.message ?: t.javaClass.simpleName
            } finally {
                _scanning.value = false
            }
        }
    }

    /**
     * The REA target, when the analysis environment is ready.
     *
     * It is presented exactly like an agent in the picker: that is the whole
     * point of "select a file, select REA, run".
     */
    private fun reaTarget(context: Context): AgentTarget? {
        ReaDistro.refresh(context)
        val state = ReaDistro.state.value
        if (!state.installed) return null
        return AgentTarget(
            distroId = state.method?.id ?: return null,
            distroName = "Analysis",
            method = state.method?.label ?: "proot",
            binary = AgentCatalog.REA_BINARY,
            label = "REA",
            headless = true,
            kind = TargetKind.REA,
        )
    }

    private fun toTarget(report: DistroAgentReport, agent: DetectedAgent) = AgentTarget(
        distroId = report.id,
        distroName = report.name,
        method = report.method,
        binary = agent.name,
        label = AgentCatalog.forBinary(agent.name).label,
        headless = AgentCatalog.isHeadless(agent.name),
    )

    // ------------------------------------------------------------------ sessions

    /**
     * Create (or reuse) the thread for [target].
     *
     * Reuse is per (target, model) so switching the model in the composer gives
     * a fresh thread instead of silently continuing the old model's session.
     */
    fun openSession(target: AgentTarget): QuinnSession {
        val existing = _sessions.value.values.firstOrNull { it.target.key == target.key }
        if (existing != null) return existing
        val session = QuinnSession(target = target)
        _sessions.value = _sessions.value + (session.id to session)
        _order.value = listOf(session.id) + _order.value
        return session
    }

    fun closeSession(id: String) {
        _sessions.value = _sessions.value - id
        _order.value = _order.value - id
    }

    fun setModel(id: String, model: String?) {
        update(id) { it.copy(model = model) }
    }

    /**
     * Send [prompt] to [targets] — one independent turn per target.
     *
     * Returns the session id per target so the UI can show the replies in
     * their own threads. With more than one target this is a fan-out: each
     * agent gets its own process, its own session id and its own messages.
     */
    fun send(
        context: Context,
        prompt: String,
        targets: List<AgentTarget>,
        files: List<String> = emptyList(),
        model: String? = null,
    ): List<String> {
        if (prompt.isBlank() || targets.isEmpty()) return emptyList()
        val ids = ArrayList<String>()
        for (target in targets.distinctBy { it.key }) {
            val session = openSession(target)
            ids.add(session.id)
            runTurn(context.applicationContext, session.id, prompt, files, model ?: session.model)
        }
        return ids
    }

    /**
     * Run one turn for one session.
     *
     * The user turn is appended immediately; the agent reply is a streaming
     * turn updated from the process output, so the UI shows progress instead
     * of a frozen thread.
     */
    private fun runTurn(
        context: Context,
        sessionId: String,
        prompt: String,
        files: List<String>,
        model: String?,
    ) {
        val session = _sessions.value[sessionId] ?: return
        // The REA target is not a CLI: it drives REA's MCP tools through the
        // configured model, and its steps land in the same thread.
        if (session.target.isRea) {
            runReaTurn(context, sessionId, prompt, files)
            return
        }
        val profile = AgentCatalog.forBinary(session.target.binary)
        val command = AgentCommand.build(
            profile = profile,
            prompt = prompt,
            session = session.agentSessionId,
            model = model,
            files = files,
        )
        // The user turn and an empty streaming turn land together, so the
        // thread never shows the prompt without a pending reply.
        appendTurns(
            sessionId,
            Turn(TurnRole.USER, prompt, files = files),
            Turn(TurnRole.AGENT, "", streaming = true),
        )
        bumpRunning(1)
        scope.launch {
            val output = StringBuilder()
            try {
                val result = runAgent(
                    context = context,
                    target = session.target,
                    script = wrap(profile, command),
                    onLine = { line ->
                        if (line.isNotBlank()) {
                            output.append(line).append('\n')
                            replaceStreaming(sessionId, output.toString())
                        }
                    },
                )
                val text = output.toString().trim().ifBlank { result.output.trim() }
                finishStreaming(
                    sessionId,
                    if (result.ok) text.ifBlank { "(the agent returned no output)" }
                    else "Agent exited ${result.exit}:\n" + result.tail(12),
                    error = if (result.ok) null else "exit ${result.exit}",
                )
            } catch (t: Throwable) {
                Log.w(TAG, "turn failed", t)
                finishStreaming(sessionId, t.message ?: "Turn failed", error = t.message ?: "error")
            } finally {
                bumpRunning(-1)
            }
        }
    }

    /**
     * One REA turn.
     *
     * The analysis engine publishes its own run/step state; this mirrors it
     * into the chat thread so the conversation reads like any other agent, and
     * the REA dashboard shows the same run live.
     */
    private fun runReaTurn(
        context: Context,
        sessionId: String,
        prompt: String,
        files: List<String>,
    ) {
        appendTurns(
            sessionId,
            Turn(TurnRole.USER, prompt, files = files),
            Turn(TurnRole.AGENT, "", streaming = true),
        )
        bumpRunning(1)
        ReaAnalysis.analyze(context, prompt, files)
        scope.launch {
            try {
                // Mirror the newest run's state into this thread until it ends.
                var lastAnswer = ""
                var lastError = ""
                var steps = 0
                while (true) {
                    val run = ReaAnalysis.runs.value.firstOrNull { it.prompt == prompt }
                    if (run == null) break
                    steps = run.steps.size
                    val pending = run.steps.lastOrNull { it.running }?.tool
                    val progress = if (pending != null) "calling $pending…"
                    else if (run.running) "thinking…" else run.answer
                    if (progress.isNotBlank()) replaceStreaming(sessionId, progress, run.steps)
                    lastAnswer = run.answer
                    lastError = run.error
                    if (!run.running) break
                    kotlinx.coroutines.delay(400L)
                }
                finishStreaming(
                    sessionId,
                    lastAnswer.ifBlank {
                        if (lastError.isNotBlank()) lastError else "(the analysis returned no answer)"
                    },
                    error = lastError.ifBlank { null },
                    steps = ReaAnalysis.runs.value.firstOrNull { it.prompt == prompt }?.steps.orEmpty(),
                )
            } catch (t: Throwable) {
                Log.w(TAG, "rea turn failed", t)
                finishStreaming(sessionId, t.message ?: "Analysis failed", error = "error")
            } finally {
                bumpRunning(-1)
            }
        }
    }

    /**
     * Run [script] where the agent lives.
     *
     * Host agents are not inside a distro, so [GuestExec] cannot reach them:
     * they run through the host prefix the same way every other host script
     * does. Everything else goes through the distro's proot/chroot login.
     */
    private fun runAgent(
        context: Context,
        target: AgentTarget,
        script: String,
        onLine: (String) -> Unit,
    ): GuestExec.Result {
        if (target.distroId != HOST_DISTRO) {
            return GuestExec.run(
                ctx = context,
                distroId = target.distroId,
                script = script,
                timeoutMs = TURN_TIMEOUT_MS,
                // Distro agents run as the guest user, not root: an agent that
                // writes files as root leaves a rootfs the user cannot clean up.
                asRoot = false,
                onLine = onLine,
            )
        }
        val env = HostCommandBuilder.envMap(context, includeTerm = false)
        val (exit, out) = ShellCommandRunner.runCaptureExit(
            context,
            arrayOf("/system/bin/sh", "-c", script),
            env,
            onLine = onLine,
        )
        return GuestExec.Result(exit, out)
    }

    /**
     * Wrap the agent command so it runs in a stable working directory with the
     * agent's expected environment.
     */
    private fun wrap(profile: AgentProfile, command: String): String {
        val env = profile.env.entries.joinToString(" ") { (k, v) ->
            "export $k=" + GuestExec.shellQuote(v) + "; "
        }
        return "set +e; " + env + "cd \"\${HOME:-/root}\" 2>/dev/null || cd /; " +
            "export PATH=\"\$HOME/.local/bin:\$HOME/.npm-global/bin:/usr/local/bin:\$PATH\"; " +
            "exec " + command
    }

    private fun appendTurns(id: String, vararg turns: Turn) {
        update(id) { it.copy(turns = it.turns + turns) }
    }

    private fun replaceStreaming(id: String, text: String, steps: List<ReaAnalysis.Step>? = null) {
        update(id) { session ->
            val turns = session.turns.toMutableList()
            val index = turns.indexOfLast { it.role == TurnRole.AGENT && it.streaming }
            if (index >= 0) {
                turns[index] = turns[index].copy(
                    text = text,
                    steps = steps ?: turns[index].steps,
                )
            }
            session.copy(turns = turns)
        }
    }

    private fun finishStreaming(
        id: String,
        text: String,
        error: String?,
        steps: List<ReaAnalysis.Step> = emptyList(),
    ) {
        update(id) { session ->
            val turns = session.turns.toMutableList()
            val index = turns.indexOfLast { it.role == TurnRole.AGENT && it.streaming }
            if (index >= 0) {
                turns[index] = turns[index].copy(
                    text = text,
                    streaming = false,
                    error = error,
                    steps = if (steps.isNotEmpty()) steps else turns[index].steps,
                )
            }
            session.copy(turns = turns, running = false)
        }
    }

    private fun update(id: String, block: (QuinnSession) -> QuinnSession) {
        val current = _sessions.value[id] ?: return
        _sessions.value = _sessions.value + (id to block(current))
    }

    private fun bumpRunning(delta: Int) {
        // The session list is the source of truth: a turn is running exactly
        // while its reply is still streaming.
        _sessions.value = _sessions.value.mapValues { (_, session) ->
            val pending = session.turns.any { it.streaming }
            if (session.running == pending) session else session.copy(running = pending)
        }
        _runningCount.value = _sessions.value.values.count { it.running }
    }

    /** Distro id used for agents in the Android host prefix. */
    const val HOST_DISTRO = "host"

    private const val TURN_TIMEOUT_MS = 30 * 60_000L

    /** Test seam: build the exact command a turn would run. */
    internal fun commandFor(
        target: AgentTarget,
        prompt: String,
        model: String? = null,
        files: List<String> = emptyList(),
        agentSessionId: String = "session",
    ): String = AgentCommand.build(
        profile = AgentCatalog.forBinary(target.binary),
        prompt = prompt,
        session = agentSessionId,
        model = model,
        files = files,
    )

    /** Clear everything; used when the last distro is removed. */
    fun reset() {
        _sessions.value = emptyMap()
        _order.value = emptyList()
        _targets.value = emptyList()
        _runningCount.value = 0
        _lastError.value = null
    }

    /** Suspend-friendly scan for callers that need the result. */
    suspend fun scanNow(context: Context): List<AgentTarget> = withContext(Dispatchers.IO) {
        refreshTargets(context)
        _targets.value
    }
}
