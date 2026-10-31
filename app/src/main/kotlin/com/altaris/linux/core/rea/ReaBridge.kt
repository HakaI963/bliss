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

    /** The URL an agent configures. */
    const val ENDPOINT = "http://127.0.0.1:$PORT/mcp"

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
        val id = distroId.ifBlank { ReaDistro.distroId(app).orEmpty() }
        if (id.isBlank()) {
            _state.value = _state.value.copy(
                running = false,
                error = "REA is not installed. Install it from Tools → Reverse engineering.",
            )
            return@withContext _state.value
        }
        _state.value = _state.value.copy(busy = "Starting REA", error = "", distroId = id)
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
            val http = BridgeServer(app, stdio)
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

        private fun initialize() {
            val params = JSONObject()
                .put("protocolVersion", "2024-11-05")
                .put("capabilities", JSONObject())
                .put(
                    "clientInfo",
                    JSONObject().put("name", "altaris-bridge").put("version", "1.0.0")
                )
            request("initialize", params, 120_000L)
            notify("notifications/initialized", null)
        }

        /** The tool catalog as MCP objects, for pass-through to a client. */
        fun listToolsJson(): List<JSONObject> {
            val out = ArrayList<JSONObject>()
            var cursor: String? = null
            var guard = 0
            do {
                val params = cursor?.let { JSONObject().put("cursor", it) }
                val result = runCatching { request("tools/list", params, 60_000L) }.getOrNull()
                    ?: break
                result.optJSONArray("tools")?.let { tools ->
                    for (i in 0 until tools.length()) {
                        tools.optJSONObject(i)?.let { out.add(it) }
                    }
                }
                cursor = result.optString("nextCursor").takeIf { it.isNotBlank() }
                guard += 1
            } while (cursor != null && guard < 50)
            return out
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

    /** The config block shown in Tools, for one client flavour. */
    fun configJson(): String = """
        {
          "mcpServers": {
            "rea": {
              "type": "http",
              "url": "$ENDPOINT"
            }
          }
        }
    """.trimIndent()
}
