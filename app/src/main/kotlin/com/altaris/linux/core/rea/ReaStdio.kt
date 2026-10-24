package com.altaris.linux.core.rea

import android.content.Context
import android.util.Log
import com.altaris.linux.core.install.DistroInstallProfile
import com.altaris.linux.core.root.RootShell
import com.altaris.linux.core.terminal.HostCommandBuilder
import com.altaris.linux.core.terminal.TermuxHostPaths
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

/**
 * Owns the single `rea mcp` stdio child running inside the guest and speaks
 * JSON-RPC to it.
 *
 * REA's HTTP transport serves one client at a time, so exposing it directly
 * would break as soon as Altaris and an in-distro agent both connected. Keeping
 * one stdio process owned by the app and re-publishing its catalog over the
 * Altaris MCP endpoint gives every agent the full REA surface without a second
 * port or a second session.
 */
class ReaStdio(private val ctx: Context, val distroId: String) {

    private val ids = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, LinkedBlockingQueue<JSONObject>>()
    private val writeLock = Any()
    private val callLock = Any()

    @Volatile private var process: Process? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var reader: Thread? = null
    @Volatile private var drain: Thread? = null
    @Volatile var lastError: String? = null
        private set

    val isAlive: Boolean get() = process?.isAlive == true

    fun start() {
        if (isAlive) return
        val profile = DistroInstallProfile.forId(distroId)
            ?: throw IllegalStateException("unknown distro: " + distroId)
        val log = File(ctx.filesDir, "rea-mcp.log")
        // The rea shim is `#!/usr/bin/env node`, so node must be on PATH even
        // when the distro had no Node 22 and we installed our own copy.
        val command = "PATH=" + ReaManager.ROOT + "/node/bin:\$PATH; export PATH; exec " +
            ReaManager.REA_BIN + " mcp"
        val proc = if (profile.method == "chroot") {
            val path = profile.chrootPath
                ?: throw IllegalStateException("chroot path missing for " + distroId)
            RootShell.startInChroot(command, "root", path, ctx)
                ?: throw IllegalStateException("cannot start su for chroot")
        } else {
            val bash = TermuxHostPaths.libBash(ctx).absolutePath
            val login = "exec python " + TermuxHostPaths.PROOT_DISTRO +
                " login " + GuestExec.shellQuote(profile.prootName) +
                " --user root --shared-tmp -- /bin/sh -c " + GuestExec.shellQuote(command)
            val pb = ProcessBuilder(bash, "-c", login)
            HostCommandBuilder.applyTo(ctx, pb)
            pb.start()
        }
        process = proc
        writer = BufferedWriter(OutputStreamWriter(proc.outputStream, Charsets.UTF_8))
        reader = Thread({ readLoop(proc) }, "altaris-rea-reader").apply {
            isDaemon = true
            start()
        }
        drain = Thread({ drainLoop(proc, log) }, "altaris-rea-stderr").apply {
            isDaemon = true
            start()
        }
        initialize()
    }

    private fun initialize() {
        val params = JSONObject()
            .put("protocolVersion", "2024-11-05")
            .put("capabilities", JSONObject())
            .put(
                "clientInfo",
                JSONObject().put("name", "altaris").put("version", "1.1.0")
            )
        request("initialize", params, 120_000L)
        notify("notifications/initialized", null)
    }

    /** Full catalog, following MCP pagination until the server stops returning a cursor. */
    fun listTools(): org.json.JSONArray {
        val all = org.json.JSONArray()
        var cursor: String? = null
        var guard = 0
        do {
            val params = cursor?.let { JSONObject().put("cursor", it) }
            val result = request("tools/list", params, 120_000L)
            val page = result.optJSONArray("tools")
            if (page != null) {
                for (i in 0 until page.length()) all.put(page.get(i))
            }
            cursor = result.optString("nextCursor").takeIf { it.isNotBlank() }
            guard += 1
        } while (cursor != null && guard < 50)
        return all
    }

    fun callTool(name: String, arguments: JSONObject, timeoutMs: Long): JSONObject {
        val params = JSONObject().put("name", name).put("arguments", arguments)
        return synchronized(callLock) { request("tools/call", params, timeoutMs) }
    }

    fun request(method: String, params: JSONObject?, timeoutMs: Long): JSONObject {
        val proc = process ?: throw IllegalStateException("REA is not running")
        if (!proc.isAlive) throw IllegalStateException(lastError ?: "REA process exited")
        val id = ids.getAndIncrement()
        val queue = LinkedBlockingQueue<JSONObject>()
        pending[id] = queue
        try {
            val frame = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("method", method)
            if (params != null) frame.put("params", params)
            write(frame)
            val reply = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
                ?: throw IllegalStateException(
                    "REA did not answer " + method + " within " + (timeoutMs / 1000) + "s"
                )
            reply.optJSONObject("error")?.let {
                throw IllegalStateException(it.optString("message", "REA error"))
            }
            return reply.optJSONObject("result") ?: JSONObject()
        } finally {
            pending.remove(id)
        }
    }

    fun notify(method: String, params: JSONObject?) {
        val frame = JSONObject().put("jsonrpc", "2.0").put("method", method)
        if (params != null) frame.put("params", params)
        write(frame)
    }

    private fun write(frame: JSONObject) {
        synchronized(writeLock) {
            val out = writer ?: throw IllegalStateException("REA stdin closed")
            out.write(frame.toString())
            out.write("\n")
            out.flush()
        }
    }

    private fun readLoop(proc: Process) {
        try {
            BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val text = line.trim()
                    if (text.isEmpty() || !text.startsWith("{")) continue
                    val frame = try {
                        JSONObject(text)
                    } catch (_: Throwable) {
                        continue
                    }
                    val id = frame.optInt("id", -1)
                    if (id >= 0) pending[id]?.offer(frame)
                }
            }
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
        } finally {
            val exit = try {
                proc.waitFor(2, TimeUnit.SECONDS)
            } catch (_: Throwable) {
                false
            }
            if (lastError == null && !exit) lastError = "REA stream closed"
            failPending(lastError ?: "REA stream closed")
            Log.i(TAG, "rea stdio closed: " + (lastError ?: "eof"))
        }
    }

    private fun drainLoop(proc: Process, log: File) {
        try {
            proc.errorStream?.bufferedReader(Charsets.UTF_8)?.use { err ->
                log.parentFile?.mkdirs()
                log.appendText("")
                while (true) {
                    val line = err.readLine() ?: break
                    runCatching { log.appendText(line + "\n") }
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun failPending(message: String) {
        val keys = pending.keys.toList()
        for (key in keys) {
            pending[key]?.offer(
                JSONObject().put(
                    "error",
                    JSONObject().put("code", -32000).put("message", message)
                )
            )
        }
    }

    fun stop() {
        val proc = process ?: return
        process = null
        runCatching { writer?.close() }
        runCatching { proc.destroy() }
        runCatching {
            if (!proc.waitFor(3, TimeUnit.SECONDS)) proc.destroyForcibly()
        }
        runCatching { reader?.interrupt() }
        runCatching { drain?.interrupt() }
        writer = null
        failPending("REA stopped")
    }

    private companion object {
        const val TAG = "AltarisRea"
    }
}
