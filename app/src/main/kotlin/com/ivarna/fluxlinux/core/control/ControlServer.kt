package com.ivarna.fluxlinux.core.control

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import com.ivarna.fluxlinux.core.clone.HeadlessCloneEngine
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Localhost control plane for clones. One HTTP listener serves both the CLI
 * (`/install`, `/launch`, `/tap`, …) and the MCP JSON-RPC endpoint (`/mcp`).
 *
 * It binds to 127.0.0.1 only: proot/chroot guests share the Android loopback,
 * and no LAN device can reach it. A foreground service keeps it alive.
 */
object ControlServer {

    const val PORT = 47831
    const val BASE_URL = "http://127.0.0.1:$PORT"
    private const val TAG = "AltarisControl"

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var appContext: Context? = null
    @Volatile private var startError: String? = null

    fun start(context: Context) {
        appContext = context.applicationContext
        if (!running.compareAndSet(false, true)) return
        startError = null
        acceptThread = thread(name = "bliss-control", isDaemon = true) {
            try {
                val socket = ServerSocket()
                socket.reuseAddress = true
                socket.bind(InetSocketAddress("127.0.0.1", PORT))
                serverSocket = socket
                android.util.Log.i(TAG, "control server listening on $BASE_URL")
                while (running.get()) {
                    val client = try {
                        socket.accept()
                    } catch (t: Throwable) {
                        if (running.get()) android.util.Log.w(TAG, "accept failed", t)
                        break
                    }
                    thread(name = "bliss-control-client", isDaemon = true) {
                        client.use { handle(it) }
                    }
                }
            } catch (t: Throwable) {
                startError = t.message ?: t.javaClass.simpleName
                android.util.Log.e(TAG, "control server failed", t)
            } finally {
                running.set(false)
                runCatching { serverSocket?.close() }
                serverSocket = null
            }
        }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
    }

    fun isRunning(): Boolean = running.get()
    fun error(): String? = startError

    fun endpoint(): String = BASE_URL
    fun context(): Context? = appContext

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 30_000
            val input = BufferedInputStream(socket.getInputStream())
            val headerBytes = ByteArrayOutputStream()
            var tail = 0
            while (true) {
                val b = input.read()
                if (b < 0) return
                headerBytes.write(b)
                tail = (tail shl 8) or b
                if (tail == 0x0d0a0d0a) break
                if (headerBytes.size() > 64 * 1024) return
            }
            val headerText = headerBytes.toString(Charsets.UTF_8.name())
            val lines = headerText.split("\r\n")
            if (lines.isEmpty()) return
            val requestLine = lines.first().split(" ")
            if (requestLine.size < 2) return
            val method = requestLine[0].uppercase()
            val target = requestLine[1]
            val path = target.substringBefore('?')
            val query = parseQuery(target.substringAfter('?', ""))
            val headers = lines.drop(1)
                .mapNotNull { line ->
                    val i = line.indexOf(':')
                    if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
                }
                .toMap()
            val declaredLength = headers["content-length"]?.toLongOrNull() ?: 0L
            if (path == "/install-upload") {
                val response = try {
                    handleUpload(method, input, declaredLength, headers)
                } catch (t: Throwable) {
                    android.util.Log.w(TAG, "upload failed", t)
                    json(500, JSONObject().put("ok", false).put("error", t.message ?: t.javaClass.simpleName))
                }
                write(socket.getOutputStream(), response)
                return
            }
            val contentLength = declaredLength.coerceIn(0L, 32L * 1024 * 1024).toInt()
            val body = if (contentLength > 0) {
                val bytes = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(bytes, read, contentLength - read)
                    if (n <= 0) break
                    read += n
                }
                String(bytes, 0, read, Charsets.UTF_8)
            } else ""

            val response = try {
                route(method, path, query, body)
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "route failed: $path", t)
                json(500, JSONObject().put("ok", false).put("error", t.message ?: t.javaClass.simpleName))
            }
            write(socket.getOutputStream(), response)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "client failed", t)
        }
    }

    private fun handleUpload(
        method: String,
        input: BufferedInputStream,
        length: Long,
        headers: Map<String, String>,
    ): Response {
        if (method != "POST") {
            return json(405, JSONObject().put("ok", false).put("error", "Method not allowed"))
        }
        if (length <= 0L) {
            return json(400, JSONObject().put("ok", false).put("error", "Missing Content-Length"))
        }
        if (length > 512L * 1024 * 1024) {
            return json(413, JSONObject().put("ok", false).put("error", "APK exceeds 512 MB"))
        }
        val ctx = appContext
            ?: return json(503, JSONObject().put("ok", false).put("error", "App context is not ready"))
        val dir = File(ctx.cacheDir, "apk-uploads").apply { mkdirs() }
        val requested = (headers["x-altaris-filename"] ?: headers["x-bliss-filename"])
            ?.substringAfterLast('/')?.substringAfterLast('\\')
            ?.take(80)?.ifBlank { "upload.apk" } ?: "upload.apk"
        val safeName = requested.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "upload.apk" }
        val file = File(dir, "upload-${System.currentTimeMillis()}-$safeName")
        try {
            BufferedOutputStream(FileOutputStream(file)).use { out ->
                val buffer = ByteArray(128 * 1024)
                var remaining = length
                while (remaining > 0L) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    remaining -= read
                }
                out.flush()
                if (remaining != 0L) {
                    return json(400, JSONObject().put("ok", false).put("error", "Upload ended early"))
                }
            }
            val label = (headers["x-altaris-label"] ?: headers["x-bliss-label"])?.take(120)?.trim()?.takeIf { it.isNotEmpty() }
            val result = runBlocking {
                HeadlessCloneEngine.apiInstall(path = file.absolutePath, url = null, label = label)
            }
            return json(200, result)
        } finally {
            runCatching { file.delete() }
        }
    }

    private fun route(method: String, path: String, query: Map<String, String>, body: String): Response {
        if (method == "OPTIONS") return Response(204, "text/plain", ByteArray(0))
        if (method == "GET" && (path == "/" || path == "/help")) {
            return jsonText(
                200,
                """
                Altaris control API
                  GET  /health
                  GET  /status
                  GET  /clones
                  GET  /apps
                  GET  /screenshot?width=900&quality=90
                  GET  /ocr?width=900
                  GET  /logs?package=com.nopiece.hclone.x&lines=300
                  POST /install        {"path":"/sdcard/Download/app.apk"} or {"url":"https://…"}
                  POST /install-upload  raw APK bytes; headers X-Altaris-Filename / X-Altaris-Label
                  POST /uninstall      {"package":"com.nopiece.hclone.x"}
                  POST /launch         {"package":"com.nopiece.hclone.x","width":720,"height":1280,"fps":60}
                  POST /stop           {"package":"com.nopiece.hclone.x"}
                  POST /tap            {"x":100,"y":200}
                  POST /swipe          {"x1":100,"y1":300,"x2":100,"y2":900,"ms":260}
                  POST /text           {"text":"hello"}
                  POST /key            {"key":"back"}
                  GET  /record/status
                  POST /record/start   {"fps":6,"width":720}
                  POST /record/stop
                  POST /mcp            JSON-RPC 2.0 (MCP tools)
                """.trimIndent()
            )
        }
        if (method == "GET" && path == "/health") {
            return json(
                200,
                JSONObject()
                    .put("ok", true)
                    .put("service", "bliss-control")
                    .put("version", 1)
                    .put("port", PORT)
                    .put("running", isRunning())
                    .put("error", startError ?: JSONObject.NULL)
            )
        }
        if (method == "GET" && path == "/status") {
            return json(200, runBlocking { HeadlessCloneEngine.apiStatus() })
        }
        if (method == "GET" && path == "/clones") {
            return json(200, runBlocking { HeadlessCloneEngine.apiClones() })
        }
        if (method == "GET" && path == "/apps") {
            return json(200, runBlocking { HeadlessCloneEngine.apiApps() })
        }
        if (method == "GET" && path == "/screenshot") {
            val width = query["width"]?.toIntOrNull()?.coerceIn(200, 2160) ?: 900
            val quality = query["quality"]?.toIntOrNull()?.coerceIn(30, 100) ?: 90
            val bytes = runBlocking { HeadlessCloneEngine.apiCapture(width, quality) }
                ?: return json(503, JSONObject().put("ok", false).put("error", "No display frame"))
            return Response(200, "image/jpeg", bytes)
        }
        if (method == "GET" && path == "/ocr") {
            val width = query["width"]?.toIntOrNull()?.coerceIn(200, 2160) ?: 1000
            return runBlocking {
                val bytes = HeadlessCloneEngine.apiCapture(width, 92)
                    ?: return@runBlocking json(503, JSONObject().put("ok", false).put("error", "No display frame"))
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: return@runBlocking json(503, JSONObject().put("ok", false).put("error", "Frame decode failed"))
                json(200, OcrEngine.recognize(bitmap))
            }
        }
        if (method == "GET" && path == "/record/status") {
            return json(200, CloneRecorder.status())
        }
        if (method == "GET" && path == "/logs") {
            val pkg = query["package"].orEmpty()
            if (pkg.isBlank()) return json(400, JSONObject().put("ok", false).put("error", "package is required"))
            val lines = query["lines"]?.toIntOrNull() ?: 300
            return json(200, runBlocking { HeadlessCloneEngine.apiLogs(pkg, lines) })
        }
        if (method == "POST" && path == "/mcp") {
            val reply = McpProtocol.handle(body)
            return json(200, reply)
        }
        if (method != "POST") {
            return json(405, JSONObject().put("ok", false).put("error", "Method not allowed"))
        }
        val obj = try {
            if (body.isBlank()) JSONObject() else JSONObject(body)
        } catch (_: Throwable) {
            return json(400, JSONObject().put("ok", false).put("error", "Body is not valid JSON"))
        }
        return when (path) {
            "/install" -> json(
                200,
                runBlocking {
                    HeadlessCloneEngine.apiInstall(
                        path = obj.optString("path").takeIf { it.isNotBlank() },
                        url = obj.optString("url").takeIf { it.isNotBlank() },
                        label = obj.optString("label").takeIf { it.isNotBlank() },
                    )
                }
            )
            "/uninstall" -> {
                val pkg = obj.optString("package")
                if (pkg.isBlank()) json(400, JSONObject().put("ok", false).put("error", "package is required"))
                else json(200, runBlocking { HeadlessCloneEngine.apiUninstall(pkg) })
            }
            "/launch" -> {
                val pkg = obj.optString("package")
                if (pkg.isBlank()) json(400, JSONObject().put("ok", false).put("error", "package is required"))
                else json(
                    200,
                    runBlocking {
                        HeadlessCloneEngine.apiLaunch(
                            pkg,
                            obj.optInt("width", 0),
                            obj.optInt("height", 0),
                            obj.optInt("fps", 0),
                        )
                    }
                )
            }
            "/stop" -> {
                val pkg = obj.optString("package")
                if (pkg.isBlank()) json(400, JSONObject().put("ok", false).put("error", "package is required"))
                else json(200, runBlocking { HeadlessCloneEngine.apiStop(pkg) })
            }
            "/tap" -> json(
                200,
                runBlocking { HeadlessCloneEngine.apiTap(obj.optInt("x"), obj.optInt("y")) }
            )
            "/swipe" -> json(
                200,
                runBlocking {
                    HeadlessCloneEngine.apiSwipe(
                        obj.optInt("x1"),
                        obj.optInt("y1"),
                        obj.optInt("x2"),
                        obj.optInt("y2"),
                        obj.optInt("ms", 260),
                    )
                }
            )
            "/text" -> json(
                200,
                runBlocking { HeadlessCloneEngine.apiText(obj.optString("text")) }
            )
            "/key" -> {
                val code = keyCode(obj.optString("key").ifBlank { obj.optString("code") })
                if (code == null) json(400, JSONObject().put("ok", false).put("error", "Unknown key"))
                else json(200, runBlocking { HeadlessCloneEngine.apiKey(code) })
            }
            "/release" -> json(200, runBlocking { HeadlessCloneEngine.apiReleaseDisplay() })
            "/record/start" -> {
                val ctx = appContext
                if (ctx == null) json(500, JSONObject().put("ok", false).put("error", "App context unavailable"))
                else json(200, CloneRecorder.start(ctx, obj.optInt("fps", 6), obj.optInt("width", 720)))
            }
            "/record/stop" -> json(200, CloneRecorder.stop())
            else -> json(404, JSONObject().put("ok", false).put("error", "Unknown endpoint"))
        }
    }

    private fun keyCode(value: String): Int? {
        value.trim().lowercase().toIntOrNull()?.let { return it }
        return when (value.trim().lowercase()) {
            "back" -> 4
            "home" -> 3
            "recents", "app_switch" -> 187
            "enter" -> 66
            "tab" -> 61
            "escape", "esc" -> 111
            "delete", "del" -> 67
            "space" -> 62
            "up" -> 19
            "down" -> 20
            "left" -> 21
            "right" -> 22
            "power" -> 26
            "volume_up" -> 24
            "volume_down" -> 25
            "menu" -> 82
            "search" -> 84
            else -> null
        }
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.split('&').mapNotNull { pair ->
            if (pair.isBlank()) return@mapNotNull null
            val key = URLDecoder.decode(pair.substringBefore('='), "UTF-8")
            val value = URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            key to value
        }.toMap()
    }

    private fun json(code: Int, obj: JSONObject): Response =
        Response(code, "application/json; charset=utf-8", obj.toString().toByteArray(Charsets.UTF_8))

    private fun jsonText(code: Int, text: String): Response =
        Response(code, "text/plain; charset=utf-8", text.toByteArray(Charsets.UTF_8))

    private fun write(out: OutputStream, response: Response) {
        val reason = when (response.code) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            500 -> "Internal Server Error"
            503 -> "Service Unavailable"
            else -> "OK"
        }
        val header = buildString {
            append("HTTP/1.1 ${response.code} $reason\r\n")
            append("Content-Type: ${response.contentType}\r\n")
            append("Content-Length: ${response.body.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: Content-Type, Authorization\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write(response.body)
        out.flush()
    }

    private data class Response(val code: Int, val contentType: String, val body: ByteArray)

    /** Useful for MCP resources: a data URL for the current frame. */
    fun currentFrameDataUrl(maxWidth: Int = 900): String? = runBlocking {
        val bytes = HeadlessCloneEngine.apiCapture(maxWidth, 90) ?: return@runBlocking null
        "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
