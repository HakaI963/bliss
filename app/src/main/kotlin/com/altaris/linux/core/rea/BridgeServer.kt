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
    private val session: ReaBridge.Session,
) {
    private val pool = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "rea-bridge").apply { isDaemon = true }
    }
    @Volatile private var socket: ServerSocket? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        val server = ServerSocket(ReaBridge.PORT, 16, InetAddress.getByName("127.0.0.1"))
        socket = server
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
                respond(socket.getOutputStream(), 200, response)
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
     * Forward one JSON-RPC message and record tool calls.
     *
     * `tools/call` is the only method that is instrumented: it is the one that
     * represents real analysis work. Everything else passes straight through so
     * the agent sees a normal MCP server.
     */
    private fun handle(request: JSONObject): String {
        val id = request.opt("id")
        val method = request.optString("method")
        val params = request.optJSONObject("params")

        if (method == "initialize") {
            ReaBridge.noteConnection()
        }
        if (method == "tools/call") {
            val name = params?.optString("name").orEmpty()
            val arguments = params?.optJSONObject("arguments")?.toString() ?: "{}"
            val call = ReaBridge.beginCall(name, arguments)
            val started = System.currentTimeMillis()
            return try {
                val result = session.callTool(name, JSONObject(arguments), TOOL_TIMEOUT_MS)
                val text = result.toString()
                ReaBridge.finishCall(call.id, text, "", System.currentTimeMillis() - started)
                JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", id)
                    .put("result", result)
                    .toString()
            } catch (t: Throwable) {
                val message = t.message ?: t.javaClass.simpleName
                ReaBridge.finishCall(
                    call.id,
                    "",
                    message,
                    System.currentTimeMillis() - started,
                )
                errorResponse(id, message)
            }
        }

        // Pass everything else through untouched.
        return try {
            val result = when (method) {
                "tools/list" -> JSONObject().put("tools", JSONArray(toolList()))
                "ping" -> JSONObject()
                else -> JSONObject()
            }
            JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result).toString()
        } catch (t: Throwable) {
            errorResponse(id, t.message ?: "bridge error")
        }
    }

    /** The tool catalog, in the shape MCP expects. */
    private fun toolList(): List<JSONObject> = session.listToolsJson()

    private fun errorResponse(id: Any?, message: String): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", -32000).put("message", message))
            .toString()

    private fun respond(out: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 ").append(code).append(" ")
            append(if (code == 204) "No Content" else "OK").append("\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: *\r\n")
            append("Connection: close\r\n\r\n")
        }
        runCatching {
            out.write(header.toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
        }
    }

    private companion object {
        const val TAG = "ReaBridgeServer"
        const val TOOL_TIMEOUT_MS = 30 * 60_000L
    }
}
