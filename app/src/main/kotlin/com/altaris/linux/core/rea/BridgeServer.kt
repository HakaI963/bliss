package com.altaris.linux.core.rea

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * The HTTP face of [ReaBridge].
 *
 * A deliberately small server: it speaks just enough MCP-over-HTTP for an agent
 * inside a distro to connect, forwards each request to the stdio session, and
 * records what happened. It binds to loopback only — an agent in proot/chroot
 * shares the host loopback, so nothing is exposed off-device.
 *
 * Recording happens here rather than in the session because this is the only
 * place that knows *which* tool call the agent made and how long it took from
 * the agent's point of view.
 */
internal class BridgeServer(
    private val context: Context,
    /**
     * Resolves the live session each time it is needed.
     *
     * The bridge used to hold a direct reference, so after a restart it kept
     * writing to the dead process and every call failed with "Stream closed".
     * Resolving per request means a restart is picked up immediately.
     */
    private val sessionRef: () -> ReaBridge.Session?,
) {
    private val session: ReaBridge.Session
        get() = sessionRef() ?: throw IllegalStateException(
            "REA is not running. The bridge will start it; retry in a moment."
        )
    private val pool = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "rea-bridge").apply { isDaemon = true }
    }
    @Volatile private var socket: ServerSocket? = null
    @Volatile private var running = false

    /** The port actually bound; may differ from [ReaBridge.PORT] after a fallback. */
    @Volatile var boundPort: Int = ReaBridge.PORT
        private set

    /**
     * Bind the loopback port.
     *
     * A previous process that was killed without closing its socket leaves the
     * port in TIME_WAIT, and a plain `ServerSocket(...)` then throws
     * `EADDRINUSE` — which surfaced as "bind failed: EADDRINUSE" and stopped the
     * server from ever starting. The socket is opened with SO_REUSEADDR (the
     * Android default is off) and, if the port is genuinely held by a live
     * socket, the failure is reported as a readable message rather than a raw
     * bind exception.
     */
    fun start() {
        if (running) return
        // Explicit type: the catch branch ends in `throw`, which makes the
        // try-expression's type Nothing, and the lambda below then cannot infer
        // the type of `server` (which is what broke the build).
        val server: ServerSocket = try {
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(
                java.net.InetSocketAddress(
                    InetAddress.getByName("127.0.0.1"),
                    ReaBridge.PORT,
                ),
                16,
            )
            s
        } catch (t: java.net.BindException) {
            // Port genuinely in use by something else: try the next few ports
            // rather than dying, and report which one won.
            var fallback: ServerSocket? = null
            for (offset in 1..8) {
                fallback = runCatching {
                    val s = ServerSocket()
                    s.reuseAddress = true
                    s.bind(
                        java.net.InetSocketAddress(
                            InetAddress.getByName("127.0.0.1"),
                            ReaBridge.PORT + offset,
                        ),
                        16,
                    )
                    s
                }.getOrNull()
                if (fallback != null) {
                    Log.w(TAG, "port ${ReaBridge.PORT} busy, using ${ReaBridge.PORT + offset}")
                    break
                }
            }
            fallback ?: throw IllegalStateException(
                "cannot bind an MCP port on 127.0.0.1: " + (t.message ?: "address in use"),
                t,
            )
        }
        socket = server
        boundPort = server.localPort
        running = true
        pool.execute {
            while (running) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                pool.execute { serve(client) }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
    }

    private fun serve(client: Socket) {
        client.use { socket ->
            runCatching {
                val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                // Read the request line and headers.
                val requestLine = input.readLine() ?: return
                var contentLength = 0
                var line: String?
                while (input.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                    if (line!!.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line!!.substringAfter(':').trim().toIntOrNull() ?: 0
                    }
                }
                val parts = requestLine.split(' ')
                val method = parts.getOrNull(0).orEmpty()
                val path = parts.getOrNull(1).orEmpty()

                if (method == "OPTIONS") {
                    respond(socket.getOutputStream(), 204, "")
                    return
                }
                if (method != "POST" || !path.startsWith("/mcp")) {
                    respond(
                        socket.getOutputStream(),
                        200,
                        JSONObject()
                            .put("name", "altaris-rea-bridge")
                            .put("endpoint", ReaBridge.ENDPOINT)
                            .put("status", if (running) "running" else "stopped")
                            .toString(),
                    )
                    return
                }
                val body = readBody(input, contentLength)
                if (body.isBlank()) {
                    respond(socket.getOutputStream(), 400, "{}")
                    return
                }
                val request = runCatching { JSONObject(body) }.getOrNull()
                if (request == null) {
                    respond(socket.getOutputStream(), 400, errorResponse(null, "invalid JSON"))
                    return
                }
                val response = handle(request)
                if (response.isBlank()) {
                    // Notification: acknowledged, no body, per JSON-RPC.
                    respond(socket.getOutputStream(), 202, "")
                } else {
                    respond(socket.getOutputStream(), 200, response)
                }
            }.onFailure { Log.w(TAG, "bridge request failed", it) }
        }
    }

    private fun readBody(input: BufferedReader, length: Int): String {
        if (length <= 0) return ""
        val buffer = CharArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buffer, read, length - read)
            if (n < 0) break
            read += n
        }
        return String(buffer, 0, read)
    }

    /**
     * Forward one JSON-RPC message to REA and return its reply verbatim.
     *
     * The bridge is a **proxy**, not a server of its own. An earlier version
     * answered some methods itself and returned empty objects for the rest, so a
     * real MCP client rejected the handshake:
     *
     *   Invalid result for initialize: protocolVersion: expected string,
     *   received undefined
     *
     * Now every method — initialize, tools/list, tools/call, ping, the resource
     * methods and the prompt methods — is passed to REA's stdio process and its
     * reply is returned unchanged, including `serverInfo`, `capabilities`,
     * `instructions` and the full tool schemas.
     *
     * Two additions on top of the pass-through:
     *  - `tools/call` is recorded, which is what the REA screen renders;
     *  - the REA guide is appended to `resources/list` and `prompts/list`, so an
     *    agent learns the working method from the server it is already talking
     *    to.
     *
     * Notifications (a message with no `id`) are forwarded without waiting for a
     * reply, as JSON-RPC requires.
     */
    private fun handle(request: JSONObject): String {
        val id = request.opt("id")
        val method = request.optString("method")
        val params = request.optJSONObject("params")
        val isNotification = id == null || id == JSONObject.NULL

        // A session whose guest process died cannot answer anything. Say so
        // plainly and let the caller restart, instead of failing with
        // "Stream closed" from a writer pointing at a dead pipe.
        if (!session.isRunning()) {
            if (isNotification) return ""
            return errorResponse(
                id,
                "REA process is not running. The bridge will restart it; retry in a moment.",
            )
        }

        // A client's initialize is answered with REA's real handshake, replayed
        // under the client's own id. REA has already been initialized by the
        // bridge, and the spec forbids initializing it twice, so this is the
        // only correct answer — and it carries protocolVersion, capabilities,
        // serverInfo and instructions exactly as REA sent them.
        if (method == "initialize") {
            ReaBridge.noteConnection()
            val real = session.handshake()
            if (real != null) {
                val replay = JSONObject(real.toString()).put("id", id ?: 0)
                // A client may ask for a version; if REA speaks it, say so.
                return replay.toString()
            }
            // No handshake cached yet: initialize the session now.
            return try {
                val fresh = session.forward("initialize", params, REQUEST_TIMEOUT_MS)
                JSONObject(fresh.toString()).put("id", id ?: 0).toString()
            } catch (t: Throwable) {
                errorResponse(id, t.message ?: "initialize failed")
            }
        }

        // Notifications must not produce a response body.
        if (isNotification) {
            runCatching { session.notifyForward(method, params) }
            return ""
        }

        // `server/discover` is part of the newer MCP revision, and REA speaks
        // the classic handshake only. A client probing for the new revision
        // (OpenCode's `protocol: "auto"`) must get a clean "not supported"
        // rather than a forwarded request REA cannot answer — that would hang
        // until the timeout and look like a dead server.
        if (method == "server/discover") {
            return errorResponse(
                id,
                "This server speaks the classic MCP handshake; use protocol 'legacy'.",
            )
        }

        // `ping` is a transport health check. Answering it here keeps a client
        // from holding the guest process open just to prove it is alive.
        if (method == "ping") {
            return JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("result", JSONObject())
                .toString()
        }

        if (method == "tools/call") {
            val name = params?.optString("name").orEmpty()
            val arguments = params?.optJSONObject("arguments")?.toString() ?: "{}"
            val call = ReaBridge.beginCall(name, arguments)
            val started = System.currentTimeMillis()
            return try {
                val reply = session.forward(method, params, TOOL_TIMEOUT_MS)
                val result = reply.optJSONObject("result")
                val error = reply.optJSONObject("error")
                if (error != null) {
                    ReaBridge.finishCall(
                        call.id, "", error.optString("message").ifBlank { "tool failed" },
                        System.currentTimeMillis() - started,
                    )
                } else {
                    ReaBridge.finishCall(
                        call.id,
                        result?.toString().orEmpty(),
                        "",
                        System.currentTimeMillis() - started,
                    )
                }
                // Return REA's reply, but under the *client's* id. REA's
                // reply carries the bridge's own correlation id, and a client
                // matches replies by id: handing back a foreign one makes it
                // log "Received a response for an unknown message ID" and wait
                // forever for the real answer. This was why every spec-
                // compliant client failed to connect while the endpoint itself
                // answered in milliseconds.
                withClientId(reply, id).toString()
            } catch (t: Throwable) {
                val message = t.message ?: t.javaClass.simpleName
                ReaBridge.finishCall(
                    call.id, "", message, System.currentTimeMillis() - started,
                )
                errorResponse(id, message)
            }
        }

        return try {
            val reply = session.forward(method, params, REQUEST_TIMEOUT_MS)
            withClientId(decorate(method, reply), id).toString()
        } catch (t: Throwable) {
            errorResponse(id, t.message ?: "bridge error")
        }
    }

    /**
     * Add the REA guide to the list replies.
     *
     * Everything else in the reply is left exactly as REA sent it; this only
     * appends one entry so an agent can discover the method document.
     */
    private fun decorate(method: String, reply: JSONObject): JSONObject {
        val result = reply.optJSONObject("result") ?: return reply
        when (method) {
            "resources/list" -> {
                val resources = result.optJSONArray("resources") ?: JSONArray()
                var present = false
                for (i in 0 until resources.length()) {
                    if (resources.optJSONObject(i)?.optString("uri") == ReaKnowledge.URI) {
                        present = true
                        break
                    }
                }
                if (!present) {
                    resources.put(
                        JSONObject()
                            .put("uri", ReaKnowledge.URI)
                            .put("name", "REA guide")
                            .put("description", "How to work with REA's tools on a real artifact")
                            .put("mimeType", "text/markdown")
                    )
                }
                result.put("resources", resources)
            }
            "resources/read" -> {
                val contents = result.optJSONArray("contents")
                if (contents == null) {
                    result.put(
                        "contents",
                        JSONArray().put(
                            JSONObject()
                                .put("uri", ReaKnowledge.URI)
                                .put("mimeType", "text/markdown")
                                .put("text", ReaKnowledge.GUIDE)
                        )
                    )
                }
            }
            "prompts/list" -> {
                val prompts = result.optJSONArray("prompts") ?: JSONArray()
                var present = false
                for (i in 0 until prompts.length()) {
                    if (prompts.optJSONObject(i)?.optString("name") == "rea-method") {
                        present = true
                        break
                    }
                }
                if (!present) {
                    prompts.put(
                        JSONObject()
                            .put("name", "rea-method")
                            .put("description", "How to approach a reverse-engineering task with REA")
                    )
                }
                result.put("prompts", prompts)
            }
        }
        return reply
    }

    /**
     * Put the client's request id on a reply that REA answered.
     *
     * The session correlates replies with its own counter, because several
     * bridge requests can be in flight and REA only knows about the id it was
     * given. That counter must never reach the client: JSON-RPC matches a
     * response to a request by id, and a client that sees `id: 11` for the
     * request it sent as `id: 999` discards the response and blocks until its
     * own timeout. The transport then reports "Request timed out" even though
     * the server answered correctly and immediately.
     */
    private fun withClientId(reply: JSONObject, id: Any?): JSONObject {
        if (id == null || id == JSONObject.NULL) return reply
        reply.put("id", id)
        return reply
    }

    private fun errorResponse(id: Any?, message: String): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", -32000).put("message", message))
            .toString()

    /**
     * Write one HTTP response.
     *
     * `Mcp-Session-Id` is required by the Streamable HTTP transport: a client
     * sends it back on every later request, and its absence was one of the
     * reasons a real client rejected this endpoint. The value is stable for the
     * lifetime of the bridge, because the bridge itself is the session.
     */
    private fun respond(out: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 ").append(code).append(" ")
            append(if (code == 204) "No Content" else "OK").append("\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Mcp-Session-Id: ").append(sessionId).append("\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: *\r\n")
            append("Access-Control-Expose-Headers: Mcp-Session-Id\r\n")
            append("Connection: close\r\n\r\n")
        }
        runCatching {
            out.write(header.toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
        }
    }

    /** Stable id for this bridge instance, sent as `Mcp-Session-Id`. */
    private val sessionId: String = java.util.UUID.randomUUID().toString()

    private companion object {
        const val TAG = "ReaBridgeServer"
        const val TOOL_TIMEOUT_MS = 30 * 60_000L
        const val REQUEST_TIMEOUT_MS = 5 * 60_000L
    }
}
