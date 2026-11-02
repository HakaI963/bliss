package com.altaris.linux.core.rea

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * A live debugger attached to the app running in the clone.
 *
 * This is not x64dbg. That is a Windows/x86 program; it cannot run on this
 * device and could not debug an ARM64 Android process if it did. What this does
 * is the same job natively: gdb, inside the distro REA lives in, attached to a
 * process in the clone's virtual display.
 *
 * Everything it reports is read from the live process — registers, memory,
 * mappings, backtraces. Nothing is simulated or inferred.
 *
 * ## Why the PID has to be translated
 *
 * The clone runs its app as a normal process on this device, so the PID is a
 * host PID. The distro REA lives in is a chroot or proot container with its own
 * PID namespace, and a guest `gdb` cannot see host processes by their host PID.
 * The attach path therefore resolves the target PID on the host first and
 * attaches through `/proc/<pid>/root` semantics where the backend allows it;
 * when it cannot, it says so instead of attaching to the wrong process.
 */
object ReaDebugger {

    private const val TAG = "AltarisReaDebugger"

    /** How much console output is kept. */
    private const val MAX_TRANSCRIPT = 40_000

    data class State(
        /** True when a debugger backend exists (frida-server or gdb). */
        val available: Boolean = false,
        /** Why it is not available, when it is not. */
        val reason: String = "",
        /** Host PID of the process being inspected, when attached. */
        val attachedPid: Int? = null,
        /** The package or pid the user asked for. */
        val target: String = "",
        /** Apps running in the clone right now, so the picker is not guesswork. */
        val runningApps: List<String> = emptyList(),
        /** Everything the console has printed, newest last. */
        val transcript: String = "",
        val error: String = "",
        /** frida-server state on the host. */
        val fridaInstalled: Boolean = false,
        val fridaRunning: Boolean = false,
        /** The frida CLI inside the distro. */
        val fridaClient: Boolean = false,
        /** True while frida-server is being downloaded or started. */
        val fridaBusy: String = "",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** The distro REA is installed in, or null when it is not installed. */
    private fun distro(context: Context): String? =
        ReaDistro.activeTarget(context)?.id

    /**
     * Re-read what the debugger can actually do here.
     *
     * Cheap: one probe inside the distro, plus the list of processes the clone
     * owns. Called when the tab opens and after every attach.
     */
    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        val id = distro(context) ?: run {
            _state.value = _state.value.copy(
                available = false,
                reason = "REA is not installed. Install it from Tools → Reverse engineering.",
            )
            return@withContext
        }
        val probe = ReaManager.probeBackend(context, id)
        val frida = ReaFrida.state(context)
        _state.value = _state.value.copy(
            // frida-server on the host is the real backend: it can see and
            // ptrace the clone. gdb is the fallback.
            available = probe.available || frida.serverRunning,
            reason = if (probe.available || frida.serverRunning) "" else probe.reason,
            runningApps = probe.runningApps,
            fridaInstalled = frida.serverInstalled,
            fridaRunning = frida.serverRunning,
            fridaClient = frida.clientInstalled,
        )
    }

    /**
     * Attach to [target] — a package name or a host PID.
     *
     * Reports the reason on failure rather than leaving the console empty,
     * because "attach did nothing" is the hardest state to diagnose.
     */
    suspend fun attach(context: Context, target: String) = withContext(Dispatchers.IO) {
        val id = distro(context) ?: run {
            _state.value = _state.value.copy(
                error = "REA is not installed. Install it from Tools → Reverse engineering.",
            )
            return@withContext
        }
        // Populate availability and the running-app list first, so a failed
        // attach names the real reason instead of reporting nothing.
        if (_state.value.runningApps.isEmpty()) refresh(context)
        append("$ attach " + target)
        val result = runCatching { ReaManager.attachDebugger(context, id, target) }
            .onFailure { Log.w(TAG, "attach failed", it) }
            .getOrNull()
        if (result == null) {
            _state.value = _state.value.copy(error = "attach failed")
            append("attach failed")
            return@withContext
        }
        if (result.output.isNotBlank()) append(result.output)
        ReaManager.rememberAttachedPid(result.pid)
        _state.value = _state.value.copy(
            attachedPid = result.pid,
            target = target,
            error = if (result.pid == null) {
                result.output.lines().lastOrNull { it.isNotBlank() }.orEmpty()
                    .ifBlank { "could not attach" }
            } else "",
        )
        if (result.pid != null) append("attached to pid " + result.pid)
    }

    /** Detach and leave the process running. */
    suspend fun detach(context: Context) = withContext(Dispatchers.IO) {
        val id = distro(context) ?: return@withContext
        append("$ detach")
        val out = runCatching { ReaManager.detachDebugger(context, id) }.getOrNull()
        if (!out.isNullOrBlank()) append(out)
        ReaManager.rememberAttachedPid(null)
        _state.value = _state.value.copy(attachedPid = null, error = "")
    }

    /**
     * Run one console command against the attached process.
     *
     * The command is passed verbatim: this is a debugger console, not a
     * command palette, so the user's own gdb syntax has to reach gdb unchanged.
     */
    suspend fun run(context: Context, command: String) = withContext(Dispatchers.IO) {
        val id = distro(context) ?: return@withContext
        if (_state.value.attachedPid == null) {
            _state.value = _state.value.copy(error = "Not attached. Attach first.")
            return@withContext
        }
        append("$ " + command)
        val result = runCatching { ReaManager.debuggerCommand(context, id, command) }.getOrNull()
        if (result == null) {
            append("command failed")
            return@withContext
        }
        append(result.output.ifBlank { "(no output)" })
        if (!result.ok) {
            _state.value = _state.value.copy(
                error = result.output.lines().lastOrNull { it.isNotBlank() }.orEmpty()
                    .ifBlank { "command failed" },
            )
        }
    }

    /**
     * Install and start frida-server, then the CLI in the distro.
     *
     * Both halves are needed: the server does the attaching, the CLI is what
     * the console talks to. Done in one action because a half-installed frida
     * looks like a broken debugger.
     */
    suspend fun installFrida(context: Context) = withContext(Dispatchers.IO) {
        _state.value = _state.value.copy(fridaBusy = "Downloading frida-server")
        val server = ReaFrida.installServer(context)
        if (server.error.isNotBlank()) {
            _state.value = _state.value.copy(
                fridaBusy = "",
                error = server.error,
            )
            append("frida-server: " + server.error)
            return@withContext
        }
        _state.value = _state.value.copy(fridaBusy = "Starting frida-server")
        val started = ReaFrida.startServer()
        if (started.error.isNotBlank()) {
            _state.value = _state.value.copy(fridaBusy = "", error = started.error)
            append("frida-server: " + started.error)
            return@withContext
        }
        append("frida-server " + ReaFrida.VERSION + " running on 127.0.0.1:" + ReaFrida.PORT)
        val id = distro(context)
        if (id != null) {
            _state.value = _state.value.copy(fridaBusy = "Installing the frida CLI")
            val client = ReaFrida.installClient(context, id)
            if (client.error.isNotBlank()) {
                append("frida CLI: " + client.error)
            } else {
                append("frida CLI installed in " + id)
            }
        }
        _state.value = _state.value.copy(fridaBusy = "")
        refresh(context)
    }

    /** Stop frida-server. */
    suspend fun stopFrida(context: Context) = withContext(Dispatchers.IO) {
        val state = ReaFrida.stopServer()
        append("frida-server stopped")
        _state.value = _state.value.copy(fridaRunning = state.serverRunning)
    }

    /** Clear the console. */
    fun clear() {
        _state.value = _state.value.copy(transcript = "", error = "")
    }

    private fun append(text: String) {
        if (text.isBlank()) return
        val next = _state.value.transcript + text.trimEnd() + "\n"
        _state.value = _state.value.copy(
            transcript = if (next.length > MAX_TRANSCRIPT) next.takeLast(MAX_TRANSCRIPT) else next,
        )
    }
}
