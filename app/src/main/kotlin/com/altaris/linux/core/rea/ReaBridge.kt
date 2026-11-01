package com.altaris.linux.core.rea

import android.content.Context
import android.util.Log
import com.altaris.linux.core.install.DistroInstallProfile
import com.altaris.linux.core.root.RootShell
import com.altaris.linux.core.terminal.HostCommandBuilder
import com.altaris.linux.core.terminal.ShellCommandRunner
import com.altaris.linux.core.terminal.TermuxHostPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * REA as an MCP server, proxied by Altaris.
 *
 * REA ships a stdio MCP server (`rea mcp`) and has no network endpoint of its
 * own. An agent running inside a distro therefore cannot reach it by URL, and
 * even if it could, the app would see nothing of what it did.
 *
 * So Altaris owns the server and exposes it over HTTP at [ENDPOINT]:
 *
 *   agent (inside a distro)  ──HTTP──▶  Altaris bridge  ──stdio──▶  rea mcp
 *
 * The bridge is a transparent pass-through — `initialize`, `tools/list` and
 * `tools/call` behave exactly as the agent expects — and it **records every
 * call** (tool, arguments, result size, duration, error) into [events]. That
 * recording is what the REA tab renders: the tab shows what the agent really
 * did, live, without asking the agent to report anything.
 *
 * One instance per app process. [start] is idempotent.
 */
object ReaBridge {

    private const val TAG = "ReaBridge"

    /** Loopback port of the bridge. Distinct from the clone-control port. */
    const val PORT = 47832

    /** The URL an agent configures, on the default port. */
    const val ENDPOINT = "http://127.0.0.1:$PORT/mcp"

    /**
     * The URL actually listening.
     *
     * If the default port was taken, the server binds the next free one; an
     * agent configured with [ENDPOINT] would then connect to nothing, so the
     * config shown in Tools uses this instead.
     */
    val liveEndpoint: String
        get() {
            val bound = server?.boundPort ?: PORT
            return "http://127.0.0.1:" + bound + "/mcp"
        }

    /** One recorded tool call. */
    data class Call(
        val id: Long = nextId.incrementAndGet(),
        val tool: String,
        val arguments: String,
        val at: Long = System.currentTimeMillis(),
        /** Bytes of result text, or 0 while running. */
        val bytes: Long = 0L,
        val ms: Long = 0L,
        val running: Boolean = true,
        val error: String = "",
        /** Extracted rows (functions, sections, offsets) for the Functions tab. */
        val findings: List<Findings.Row> = emptyList(),
        /**
         * The first part of the result text, verbatim.
         *
         * The Decompile and Objdump tabs show real tool output, so the text has
         * to be kept. It is capped: a Ghidra dump can be megabytes and this is
         * for a phone screen, not for storage.
         */
        val preview: String = "",
        /** Set when the result reports a comparison. */
        val compared: Boolean? = null,
        val matchedBytes: Long = 0L,
        val differentBytes: Long = 0L,
        val totalBytes: Long = 0L,
    )

    /** A one-line entry for the log strip. */
    data class LogLine(val text: String, val at: Long, val level: Level = Level.INFO) {
        enum class Level { INFO, CALL, RESULT, ERROR }
    }

    data class State(
        val running: Boolean = false,
        val busy: String = "",
        val error: String = "",
        val toolCount: Int = 0,
        val distroId: String = "",
        /** How many agents have connected since the bridge started. */
        val connections: Int = 0,
        val startedAt: Long = 0L,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _calls = MutableStateFlow<List<Call>>(emptyList())
    /** Newest first. */
    val calls: StateFlow<List<Call>> = _calls.asStateFlow()

    private val _logs = MutableStateFlow<List<LogLine>>(emptyList())
    /** Newest first, capped. */
    val logs: StateFlow<List<LogLine>> = _logs.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** How much result text is kept for the Decompile/Objdump views. */
    private const val PREVIEW_CHARS = 20_000
    private val nextId = AtomicLong(0L)

    @Volatile private var session: Session? = null
    @Volatile private var server: BridgeServer? = null

    // ---------------------------------------------------------------- lifecycle

    /**
     * Start the REA stdio server and the HTTP bridge.
     *
     * @param distroId the REA distro (hidden); resolved from [ReaDistro] when blank.
     */
    suspend fun start(context: Context, distroId: String = ""): State = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        if (session?.isAlive == true && server != null) return@withContext _state.value
        // A session whose process died (guest reboot, OOM kill) must be dropped
        // before a new one is created, or the old reader thread keeps a dead
        // queue and every request times out.
        if (session?.isAlive == false) {
            runCatching { server?.stop() }
            server = null
            runCatching { session?.stop() }
            session = null
        }
        val id = distroId.ifBlank { ReaDistro.distroId(app).orEmpty() }
        if (id.isBlank()) {
            _state.value = _state.value.copy(
                running = false,
                error = "REA is not installed. Install it from Tools → Reverse engineering.",
            )
            return@withContext _state.value
        }
        _state.value = _state.value.copy(busy = "Starting REA", error = "", distroId = id)
        // Pre-flight. Without this the failure surfaces as
        // `/bin/sh: exec: /opt/altaris/rea/npm/bin/rea: not found`, which does
        // not say that the core install never finished.
        if (!ReaManager.coreInstalled(app, id)) {
            val message = "REA core is not installed in " + id +
                ". Install it from Tools → Reverse engineering first."
            _state.value = _state.value.copy(running = false, busy = "", error = message)
            log(message, LogLine.Level.ERROR)
            return@withContext _state.value
        }
        // The core is there, so clear any previous "not installed" message
        // before trying; otherwise the screen keeps showing a stale reason.
        _state.value = _state.value.copy(error = "")
        log("starting REA MCP server", LogLine.Level.INFO)
        try {
            val stdio = Session(app, id)
            stdio.start()
            val tools = stdio.listTools()
            session = stdio
            _state.value = _state.value.copy(
                running = true,
                busy = "",
                toolCount = tools,
                startedAt = System.currentTimeMillis(),
                error = "",
            )
            log("REA ready · $tools tools", LogLine.Level.INFO)
            val http = BridgeServer(app) { session }
            http.start()
            server = http
            _state.value = _state.value.copy(busy = "")
            _state.value
        } catch (t: Throwable) {
            Log.w(TAG, "REA bridge failed to start", t)
            runCatching { session?.stop() }
            session = null
            _state.value = _state.value.copy(
                running = false,
                busy = "",
                error = t.message ?: t.javaClass.simpleName,
            )
            log("failed: " + (t.message ?: "unknown"), LogLine.Level.ERROR)
            _state.value
        }
    }

    /**
     * Make sure the server is up, starting it when needed.
     *
     * The server is meant to always run — there is no Start button — so every
     * place that needs it calls this instead of owning the lifecycle. It is a
     * no-op when the server is already alive and REA is installed.
     */
    suspend fun ensureRunning(context: Context): State = withContext(Dispatchers.IO) {
        if (session?.isAlive == true && server != null) {
            // The user may have switched proot ⇄ chroot while the bridge was up.
            // Serve the selected method instead of quietly keeping the old one.
            val wanted = ReaDistro.distroId(context)
            if (!wanted.isNullOrBlank() && wanted != _state.value.distroId) {
                log("switching to " + wanted, LogLine.Level.INFO)
                runCatching { server?.stop() }
                server = null
                runCatching { session?.stop() }
                session = null
            } else {
                return@withContext _state.value
            }
        }
        val id = ReaDistro.distroId(context) ?: return@withContext _state.value.copy(
            running = false,
            error = "REA is not installed. Install it from Tools → Reverse engineering.",
        )
        start(context, id)
    }

    /**
     * True when the last start attempt failed because the core is missing.
     *
     * The screen uses this to decide whether to keep retrying or to wait for an
     * install, instead of hammering a check that cannot succeed yet.
     */
    fun waitingForCore(): Boolean {
        val state = _state.value
        return !state.running && state.error.contains("core is not installed", ignoreCase = true)
    }

    /**
     * Restart the session after its guest process died.
     *
     * The MCP client keeps its URL; only the process behind it is replaced.
     * `initialize` is re-sent by the client on a fresh connection, and because
     * the bridge now forwards it, the handshake is real.
     */
    // No @Synchronized: it cannot annotate a suspend function. A restart is
    // idempotent anyway — starting an already-running session returns early.
    suspend fun restart(context: Context): State = withContext(Dispatchers.IO) {
        log("restarting the REA process", LogLine.Level.INFO)
        runCatching { session?.stop() }
        session = null
        val id = ReaDistro.distroId(context) ?: _state.value.distroId
        if (id.isBlank()) {
            _state.value = _state.value.copy(
                running = false,
                error = "REA is not installed.",
            )
            return@withContext _state.value
        }
        start(context, id)
    }

    /** True when the guest process is gone but the bridge should still serve. */
    fun sessionDead(): Boolean = session?.isRunning() != true

    suspend fun stop(): State = withContext(Dispatchers.IO) {
        runCatching { server?.stop() }
        server = null
        runCatching { session?.stop() }
        session = null
        _state.value = _state.value.copy(running = false, toolCount = 0, busy = "")
        log("REA MCP server stopped", LogLine.Level.INFO)
        _state.value
    }

    /** Tool catalog for the dashboard, when the server is running. */
    suspend fun toolNames(): List<String> = withContext(Dispatchers.IO) {
        session?.toolNames().orEmpty()
    }

    /** Note that an agent opened a connection, for the status card. */
    internal fun noteConnection() {
        _state.value = _state.value.copy(connections = _state.value.connections + 1)
        log("agent connected", LogLine.Level.INFO)
    }

    /** Record a call before it runs. */
    internal fun beginCall(tool: String, arguments: String): Call {
        val call = Call(tool = tool, arguments = arguments)
        _calls.value = (listOf(call) + _calls.value).take(400)
        log(tool, LogLine.Level.CALL)
        return call
    }

    /** Record the outcome, and extract findings. */
    internal fun finishCall(id: Long, result: String, error: String, ms: Long) {
        val extracted = if (error.isBlank()) Findings.extract(result) else Findings.Extracted()
        _calls.value = _calls.value.map {
            if (it.id != id) it
            else it.copy(
                bytes = result.length.toLong(),
                ms = ms,
                running = false,
                error = error,
                findings = extracted.rows,
                preview = if (error.isBlank()) previewOf(result) else "",
                compared = extracted.compared,
                matchedBytes = extracted.matchedBytes,
                differentBytes = extracted.differentBytes,
                totalBytes = extracted.totalBytes,
            )
        }
        if (error.isBlank()) {
            log(
                "${
                    _calls.value.firstOrNull { it.id == id }?.tool ?: "tool"
                } → ${formatBytes(result.length.toLong())} in ${ms}ms",
                LogLine.Level.RESULT,
            )
        } else {
            log(error, LogLine.Level.ERROR)
        }
    }

    fun clearHistory() {
        _calls.value = emptyList()
        _logs.value = emptyList()
    }

    /**
     * The readable part of a tool result.
     *
     * MCP wraps output as `content: [{type:text, text:"…"}]`; that inner text is
     * what a human wants to read, so it is unwrapped before truncation.
     */
    private fun previewOf(raw: String): String {
        if (raw.isBlank()) return ""
        val json = runCatching { JSONObject(raw) }.getOrNull()
        val text = json?.optJSONArray("content")
            ?.let { content ->
                buildString {
                    for (i in 0 until content.length()) {
                        val part = content.optJSONObject(i)?.optString("text").orEmpty()
                        if (part.isNotBlank()) append(part).append('\n')
                    }
                }
            }
            ?.takeIf { it.isNotBlank() }
            ?: raw
        return text.take(PREVIEW_CHARS)
    }

    private fun log(text: String, level: LogLine.Level) {
        _logs.value = (listOf(LogLine(text.take(160), System.currentTimeMillis(), level)) + _logs.value)
            .take(300)
    }

    internal fun formatBytes(bytes: Long): String = when {
        bytes <= 0L -> "0 B"
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }

    // ------------------------------------------------------------------ session

    /**
     * The stdio session with `rea mcp`.
     *
     * Same launch rules as [ReaStdio]: the `rea` shim needs Node on PATH, the
     * archive tools are prepended, and the compact schema profile keeps the
     * advertised tool list small enough for a phone.
     */
    internal class Session(private val ctx: Context, private val distroId: String) {

        private val ids = AtomicInteger(1)
        private val pending = ConcurrentHashMap<Int, LinkedBlockingQueue<JSONObject>>()
        private val writeLock = Any()
        private val callLock = Any()

        @Volatile private var process: Process? = null
        @Volatile private var writer: BufferedWriter? = null
        @Volatile private var reader: Thread? = null
        @Volatile private var drain: Thread? = null
        @Volatile private var lastError: String? = null

        val isAlive: Boolean get() = process?.isAlive == true

        fun start() {
            if (isAlive) return
            val profile = DistroInstallProfile.forId(distroId)
                ?: throw IllegalStateException("unknown distro: $distroId")
            val command = "PATH=" + ReaManager.PROBE_PATH + ":\$PATH; export PATH; " +
                "REA_MCP_INPUT_SCHEMA_PROFILE=\${REA_MCP_INPUT_SCHEMA_PROFILE:-compact}; " +
                "export REA_MCP_INPUT_SCHEMA_PROFILE; " +
                "exec " + ReaManager.REA_BIN + " mcp"
            val proc = if (profile.method == "chroot") {
                val path = profile.chrootPath
                    ?: throw IllegalStateException("chroot path missing for $distroId")
                RootShell.startInChroot(command, "root", path, ctx)
                    ?: throw IllegalStateException("cannot start su for chroot")
            } else {
                val bash = TermuxHostPaths.libBash(ctx).absolutePath
                val login = "exec python " + TermuxHostPaths.PROOT_DISTRO +
                    " login " + GuestExec.shellQuote(profile.prootName) +
                    " --shared-tmp -- /bin/sh -c " + GuestExec.shellQuote(command)
                val env = HostCommandBuilder.envMap(ctx, includeTerm = false)
                val pb = ProcessBuilder(bash, "-c", login)
                pb.environment().putAll(env)
                pb.redirectErrorStream(false)
                pb.start()
            }
            process = proc
            writer = BufferedWriter(OutputStreamWriter(proc.outputStream, Charsets.UTF_8))
            // stderr carries REA's own diagnostics; keep the tail for errors.
            drain = Thread {
                runCatching {
                    BufferedReader(InputStreamReader(proc.errorStream, Charsets.UTF_8)).use { err ->
                        val tail = StringBuilder()
                        var line: String?
                        while (err.readLine().also { line = it } != null) {
                            tail.append(line).append('\n')
                            if (tail.length > 8000) tail.delete(0, tail.length - 6000)
                        }
                        lastError = tail.toString()
                    }
                }
            }.apply { isDaemon = true; start() }
            reader = Thread {
                runCatching {
                    BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).use { input ->
                        var line: String?
                        while (input.readLine().also { line = it } != null) {
                            val text = line!!.trim()
                            if (text.isEmpty() || !text.startsWith("{")) continue
                            val json = runCatching { JSONObject(text) }.getOrNull() ?: continue
                            val id = json.optInt("id", -1)
                            if (id >= 0) pending.remove(id)?.offer(json)
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
            initialize()
        }

        fun stop() {
            runCatching { process?.destroyForcibly() }
            process = null
            writer = null
            reader = null
            drain = null
            pending.clear()
        }

        /**
         * REA's own initialize reply, kept so the bridge can hand a real
         * handshake to a connecting client.
         *
         * The bridge must initialize REA once to use it at all, but a client
         * then sends its own `initialize` and validates the reply: it needs
         * `protocolVersion`, `capabilities` and `serverInfo`. Forwarding a
         * second initialize to an already-initialized REA is not allowed by the
         * spec, so the original reply is replayed to the client instead.
         */
        @Volatile private var handshake: JSONObject? = null

        fun handshake(): JSONObject? = handshake

        private fun initialize() {
            val params = JSONObject()
                // Offer a current version; REA answers with the one it speaks.
                .put("protocolVersion", "2025-06-18")
                .put(
                    "capabilities",
                    JSONObject().put("roots", JSONObject()).put("sampling", JSONObject())
                )
                .put(
                    "clientInfo",
                    JSONObject().put("name", "altaris-bridge").put("version", "1.0.0")
                )
            handshake = forward("initialize", params, 120_000L)
            notify("notifications/initialized", null)
        }

        /** Number of advertised tools, following pagination. */
        fun listTools(): Int {
            var total = 0
            var cursor: String? = null
            var guard = 0
            do {
                val params = cursor?.let { JSONObject().put("cursor", it) }
                val result = request("tools/list", params, 120_000L)
                total += result.optJSONArray("tools")?.length() ?: 0
                cursor = result.optString("nextCursor").takeIf { it.isNotBlank() }
                guard += 1
            } while (cursor != null && guard < 50)
            return total
        }

        fun toolNames(): List<String> {
            val out = ArrayList<String>()
            var cursor: String? = null
            var guard = 0
            do {
                val params = cursor?.let { JSONObject().put("cursor", it) }
                val result = runCatching { request("tools/list", params, 60_000L) }.getOrNull()
                    ?: break
                result.optJSONArray("tools")?.let { tools ->
                    for (i in 0 until tools.length()) {
                        val name = tools.optJSONObject(i)?.optString("name").orEmpty()
                        if (name.isNotBlank()) out.add(name)
                    }
                }
                cursor = result.optString("nextCursor").takeIf { it.isNotBlank() }
                guard += 1
            } while (cursor != null && guard < 50)
            return out
        }

        /** Call a tool. Throws when REA reports an error. */
        fun callTool(name: String, arguments: JSONObject, timeoutMs: Long): JSONObject {
            val params = JSONObject()
                .put("name", name)
                .put("arguments", arguments)
            return request("tools/call", params, timeoutMs)
        }

        /**
         * Forward one JSON-RPC request and return REA's **whole reply**.
         *
         * This is the pass-through the HTTP bridge needs. The old bridge
         * fabricated its own answers (initialize returned `{}`, tools/list
         * returned an empty array), so a real MCP client rejected it:
         *
         *   Invalid result for initialize: protocolVersion: expected string,
         *   received undefined
         *
         * Returning the reply verbatim — protocolVersion, capabilities,
         * serverInfo, instructions and all — is the only way the bridge can be
         * a real MCP server.
         */
        fun forward(method: String, params: JSONObject?, timeoutMs: Long): JSONObject =
            synchronized(callLock) {
                val out = writer ?: throw IllegalStateException("REA session is not running")
                val id = ids.getAndIncrement()
                val queue = LinkedBlockingQueue<JSONObject>()
                pending[id] = queue
                val message = JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", id)
                    .put("method", method)
                if (params != null) message.put("params", params)
                synchronized(writeLock) {
                    out.write(message.toString())
                    out.write("\n")
                    out.flush()
                }
                val reply = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
                    ?: throw IllegalStateException(
                        "REA did not answer $method" +
                            (lastError?.takeIf { it.isNotBlank() }?.let { ": " + it.take(300) } ?: "")
                    )
                pending.remove(id)
                reply
            }

        /** True when the guest process is still running. */
        fun isRunning(): Boolean = isAlive

        private fun request(method: String, params: JSONObject?, timeoutMs: Long): JSONObject =
            synchronized(callLock) {
                val out = writer ?: throw IllegalStateException("REA session is not running")
                val id = ids.getAndIncrement()
                val queue = LinkedBlockingQueue<JSONObject>()
                pending[id] = queue
                val message = JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", id)
                    .put("method", method)
                if (params != null) message.put("params", params)
                synchronized(writeLock) {
                    out.write(message.toString())
                    out.write("\n")
                    out.flush()
                }
                val reply = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
                    ?: throw IllegalStateException(
                        "REA did not answer $method" +
                            (lastError?.takeIf { it.isNotBlank() }?.let { ": " + it.take(300) } ?: "")
                    )
                pending.remove(id)
                val error = reply.optJSONObject("error")
                if (error != null) {
                    throw IllegalStateException(
                        error.optString("message").ifBlank { "REA error in $method" }
                    )
                }
                reply.optJSONObject("result") ?: JSONObject()
            }

        /** Forward a notification: no id, no reply expected. */
        fun notifyForward(method: String, params: JSONObject?) = notify(method, params)

        private fun notify(method: String, params: JSONObject?) {
            val out = writer ?: return
            val message = JSONObject().put("jsonrpc", "2.0").put("method", method)
            if (params != null) message.put("params", params)
            synchronized(writeLock) {
                out.write(message.toString())
                out.write("\n")
                out.flush()
            }
        }
    }

    /** Test seam: the endpoint an agent pastes into its config. */
    fun endpointForTest(): String = ENDPOINT

    /**
     * The config block shown in Tools, in the shape OpenCode v2 reads.
     *
     * Three details decide whether this works, and all three were wrong before:
     *
     *  - the key is `mcp.servers` (V2 does not place names directly under
     *    `mcp`), which was already right;
     *  - `type` must be **`remote`** for a URL server. `http` is not a value V2
     *    accepts, so the entry was rejected instead of connected;
     *  - `oauth` must be `false`. Remote servers default to OAuth, so V2 would
     *    try to discover an authorization server on our bridge, get nothing,
     *    and report the server as needing authentication.
     *
     * `enabled` is still omitted: V2 ignores it and opts out with
     * `disabled: true`.
     */
    fun configJson(): String = """
        {
          "$schema": "https://opencode.ai/config.json",
          "mcp": {
            "servers": {
              "rea": {
                "type": "remote",
                "url": "${liveEndpoint}",
                "oauth": false
              }
            }
          }
        }
    """.trimIndent()

    /**
     * The same server as a Claude/Codex-style `mcpServers` block.
     *
     * Those clients still use the flat form, so both are shown rather than
     * guessing which one the user needs.
     */
    fun configJsonLegacy(): String = """
        {
          "mcpServers": {
            "rea": {
              "type": "http",
              "url": "${liveEndpoint}"
            }
          }
        }
    """.trimIndent()

    /**
     * One agent client that can talk to the bridge, with the config it wants.
     *
     * Every entry is a client people actually run against an MCP server; the
     * shape differs because there is no shared standard for the outer key.
     * Showing the right one is the difference between "paste and it works" and
     * "paste and the server is silently ignored".
     */
    data class ClientConfig(
        /** Chip label, short enough for a row of them. */
        val label: String,
        /** Where this block goes, so the user knows which file to edit. */
        val where: String,
        /** The copy-paste text. */
        val body: String,
    )

    /** The clients offered in Tools → Reverse engineering. */
    fun clientConfigs(): List<ClientConfig> = listOf(
        ClientConfig(
            "OpenCode v2",
            "opencode.jsonc",
            configJson(),
        ),
        ClientConfig(
            "Claude Code",
            "claude mcp add --transport http rea " + liveEndpoint,
            """
                {
                  "mcpServers": {
                    "rea": {
                      "type": "http",
                      "url": "${liveEndpoint}"
                    }
                  }
                }
            """.trimIndent(),
        ),
        ClientConfig(
            "Codex",
            "~/.codex/config.toml",
            """
                [mcp_servers.rea]
                url = "${liveEndpoint}"
            """.trimIndent(),
        ),
        ClientConfig(
            "Cursor",
            ".cursor/mcp.json",
            configJsonLegacy(),
        ),
        ClientConfig(
            "Windsurf",
            "~/.codeium/windsurf/mcp_config.json",
            configJsonLegacy(),
        ),
        ClientConfig(
            "Cline",
            "cline_mcp_settings.json",
            configJsonLegacy(),
        ),
        ClientConfig(
            "Zed",
            "settings.json → context_servers",
            """
                {
                  "context_servers": {
                    "rea": {
                      "source": "custom",
                      "command": {
                        "path": "npx",
                        "args": ["-y", "mcp-remote", "${liveEndpoint}"]
                      }
                    }
                  }
                }
            """.trimIndent(),
        ),
        ClientConfig(
            "Continue",
            "config.yaml → mcpServers",
            """
                mcpServers:
                  - name: rea
                    type: streamable-http
                    url: "${liveEndpoint}"
            """.trimIndent(),
        ),
        ClientConfig(
            "Gemini CLI",
            "~/.gemini/settings.json",
            configJsonLegacy(),
        ),
        ClientConfig(
            "Qwen Code",
            "~/.qwen/settings.json",
            configJsonLegacy(),
        ),
        ClientConfig(
            "Kiro",
            "~/.kiro/settings/mcp.json",
            configJsonLegacy(),
        ),
        ClientConfig(
            "Amp",
            "amp mcp add rea " + liveEndpoint,
            configJsonLegacy(),
        ),
        ClientConfig(
            "Crush",
            "crush.json → mcp",
            configJsonLegacy(),
        ),
        ClientConfig(
            "Pi",
            "pi mcp config",
            configJsonLegacy(),
        ),
        ClientConfig(
            "Hermes",
            "hermes mcp add",
            configJsonLegacy(),
        ),
        ClientConfig(
            "Generic JSON",
            "any MCP client",
            configJsonLegacy(),
        ),
    )
}
