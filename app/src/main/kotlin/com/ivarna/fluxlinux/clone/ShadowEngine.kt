package com.ivarna.fluxlinux.clone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Client for the vendored ShadowAuto engine.
 *
 * The engine lives in the `shadow-shell` Gradle module (ShadowAuto's
 * android-shell, Apache-2.0) and is shipped inside the Bliss APK. At runtime
 * it is staged to a shell-readable path and started by the shell service as
 *
 * ```
 * CLASSPATH=<apk> app_process /system/bin com.silentauto.shell.Main --port=43110
 * ```
 *
 * exactly as ShadowAuto's README documents. It then speaks newline-delimited
 * JSON-RPC 2.0 on 127.0.0.1:43110 — this class is that client. Every display,
 * capture and input operation goes through the engine; Bliss no longer owns a
 * virtual display of its own.
 */
object ShadowEngine {
    private const val TAG = "ShadowEngine"

    /** Port the engine listens on (loopback only). */
    const val PORT = 43110

    /** Where the shell stages the engine APK (readable by uid 2000). */
    const val ENGINE_APK = "/data/local/tmp/bliss-shadow/silent-shell.apk"

    /** Asset name of the bundled engine APK. */
    const val ENGINE_ASSET = "shadow-shell.apk"

    /** Session id used by Bliss for its single clone display. */
    const val SESSION_ID = "bliss"

    @Volatile
    var lastError: String? = null
        private set

    private val lock = Any()
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var nextId = 1

    // ------------------------------------------------------------------ rpc

    /** One JSON-RPC call. Returns the result object, or null on any failure. */
    fun call(method: String, params: JSONObject? = null): JSONObject? {
        synchronized(lock) {
            try {
                if (!connect()) {
                    lastError = "engine not reachable on 127.0.0.1:$PORT"
                    return null
                }
                val id = nextId++
                val request = JSONObject()
                request.put("jsonrpc", "2.0")
                request.put("id", id)
                request.put("method", method)
                request.put("params", params ?: JSONObject())
                writer!!.write(request.toString())
                writer!!.write("\n")
                writer!!.flush()
                while (true) {
                    val line = reader!!.readLine() ?: throw java.io.IOException("engine closed the connection")
                    if (line.isBlank()) continue
                    val message = try {
                        JSONObject(line)
                    } catch (t: Throwable) {
                        continue
                    }
                    val idValue = message.opt("id")
                    if (idValue == null || idValue == JSONObject.NULL) continue // server push
                    if (idValue.toString() != id.toString()) continue
                    val error = message.optJSONObject("error")
                    if (error != null) {
                        lastError = error.optString("message", "engine error")
                        return null
                    }
                    lastError = null
                    return message.optJSONObject("result") ?: JSONObject()
                }
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
                Log.w(TAG, "rpc $method failed", t)
                closeQuietly()
                return null
            }
        }
    }

    private fun connect(): Boolean {
        val current = socket
        if (current != null && current.isConnected && !current.isClosed) return true
        closeQuietly()
        return try {
            val s = Socket()
            s.connect(InetSocketAddress("127.0.0.1", PORT), 3000)
            s.soTimeout = 20000
            socket = s
            writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
            reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            true
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            socket = null
            writer = null
            reader = null
            false
        }
    }

    private fun closeQuietly() {
        try {
            socket?.close()
        } catch (ignored: Throwable) {
        }
        socket = null
        writer = null
        reader = null
    }

    // ------------------------------------------------------------- lifecycle

    /** True when the engine answers a ping. */
    fun ping(): Boolean = call("ping") != null

    /**
     * Stages the bundled engine APK to a shell-readable path and starts it.
     * Returns null on success, otherwise the error text.
     */
    fun startEngine(context: Context): String? {
        val staged = stageEngine(context)
        if (staged != null) return staged
        val err = ShellVd.startEngine(ENGINE_APK, PORT)
        if (err != null && err.isNotBlank()) return err
        // Wait for the engine to accept connections.
        repeat(40) {
            if (ping()) return null
            Thread.sleep(250)
        }
        return "engine did not answer on 127.0.0.1:$PORT"
    }

    /** Copies the bundled engine APK into /data/local/tmp through the shell. */
    private fun stageEngine(context: Context): String? {
        return try {
            val bytes = context.assets.open(ENGINE_ASSET).use { it.readBytes() }
            var offset = 0L
            val chunk = 256 * 1024
            while (offset < bytes.size) {
                val end = minOf(bytes.size.toLong(), offset + chunk).toInt()
                val slice = bytes.copyOfRange(offset.toInt(), end)
                val written = ShellVd.stageWrite(ENGINE_APK, offset, slice)
                if (written < 0) return "stageWrite failed at $offset"
                offset = end.toLong()
            }
            null
        } catch (t: Throwable) {
            "staging engine failed: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    fun stopEngine() {
        ShellVd.stopEngine()
        synchronized(lock) { closeQuietly() }
    }

    // -------------------------------------------------------------- display

    /** Opens the clone display session. Returns the display id, or -1. */
    fun openSession(width: Int, height: Int): Int {
        val params = JSONObject()
        params.put("sessionId", SESSION_ID)
        params.put("width", width)
        params.put("height", height)
        val result = call("openSession", params) ?: return -1
        return result.optInt("displayId", -1)
    }

    fun closeSession() {
        val params = JSONObject()
        params.put("sessionId", SESSION_ID)
        call("closeSession", params)
    }

    /** Launches a package on the clone display. Returns null on success. */
    fun launchApp(packageName: String, userId: Int): String? {
        val params = JSONObject()
        params.put("sessionId", SESSION_ID)
        params.put("packageName", packageName)
        if (userId > 0) params.put("userId", userId)
        val result = call("launchApp", params) ?: return "launch failed: ${lastError ?: "no answer"}"
        if (result.has("error")) return "launch failed: ${result.optString("error")}"
        return null
    }

    /** One still frame of the clone display, or null. */
    fun screenshot(): Bitmap? {
        val params = JSONObject()
        params.put("sessionId", SESSION_ID)
        val result = call("screenshot", params) ?: return null
        val data = result.optString("data", "")
        if (data.isEmpty()) return null
        return try {
            val raw = Base64.decode(data, Base64.DEFAULT)
            val options = BitmapFactory.Options()
            options.inSampleSize = 2
            BitmapFactory.decodeByteArray(raw, 0, raw.size, options)
        } catch (t: Throwable) {
            Log.w(TAG, "screenshot decode failed", t)
            null
        }
    }

    // ---------------------------------------------------------------- input

    fun tap(x: Int, y: Int): Boolean {
        val params = JSONObject()
        params.put("sessionId", SESSION_ID)
        params.put("x", x)
        params.put("y", y)
        return call("tap", params) != null
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean {
        val params = JSONObject()
        params.put("sessionId", SESSION_ID)
        params.put("startX", x1)
        params.put("startY", y1)
        params.put("endX", x2)
        params.put("endY", y2)
        params.put("durationMs", durationMs)
        return call("swipe", params) != null
    }

    fun pressKey(key: String): Boolean {
        val params = JSONObject()
        params.put("sessionId", SESSION_ID)
        params.put("key", key)
        return call("pressKey", params) != null
    }

    fun inputText(text: String): Boolean {
        val params = JSONObject()
        params.put("sessionId", SESSION_ID)
        params.put("text", text)
        return call("inputText", params) != null
    }

    /** Engine log lines (already newline separated). */
    fun logs(): List<String> {
        val result = call("logs") ?: return emptyList()
        val array = result.optJSONArray("value") ?: return emptyList()
        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            out.add(array.optString(i))
        }
        return out
    }
}
