package com.ivarna.fluxlinux.core.control

import android.graphics.BitmapFactory
import android.util.Base64
import com.ivarna.fluxlinux.core.clone.HeadlessCloneEngine
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal MCP (JSON-RPC 2.0) surface over the local control server.
 * Tool results are ordinary text/image content blocks; no SDK dependency.
 */
object McpProtocol {

    fun handle(body: String): JSONObject {
        val request = try {
            JSONObject(body)
        } catch (_: Throwable) {
            return error(null, -32700, "Parse error")
        }
        val id = request.opt("id")
        val method = request.optString("method")
        if (id == null) {
            // Notifications (initialized, cancelled) need no response body.
            return JSONObject().put("jsonrpc", "2.0")
        }
        return when (method) {
            "initialize" -> result(
                id,
                JSONObject()
                    .put("protocolVersion", request.optJSONObject("params")?.optString("protocolVersion")
                        ?.takeIf { it.isNotBlank() } ?: "2024-11-05")
                    .put(
                        "capabilities",
                        JSONObject().put("tools", JSONObject())
                    )
                    .put(
                        "serverInfo",
                        JSONObject().put("name", "bliss-clone-control").put("version", "1.0.0")
                    )
            )
            "ping" -> result(id, JSONObject())
            "tools/list" -> result(id, JSONObject().put("tools", tools()))
            "tools/call" -> {
                val params = request.optJSONObject("params")
                val name = params?.optString("name").orEmpty()
                val args = params?.optJSONObject("arguments") ?: JSONObject()
                try {
                    result(id, callTool(name, args))
                } catch (t: Throwable) {
                    result(
                        id,
                        JSONObject()
                            .put("isError", true)
                            .put(
                                "content",
                                JSONArray().put(
                                    JSONObject().put("type", "text")
                                        .put("text", t.message ?: t.javaClass.simpleName)
                                )
                            )
                    )
                }
            }
            else -> error(id, -32601, "Method not found: $method")
        }
    }

    private fun tools(): JSONArray = JSONArray().apply {
        put(tool("bliss_status", "Get shell/display/clone engine status.", JSONObject()))
        put(tool("bliss_clones", "List installed clones.", JSONObject()))
        put(
            tool(
                "bliss_install_apk",
                "Install an APK as an isolated clone. Use a shared-storage path or direct URL.",
                schema(
                    "path" to "string: /sdcard/Download/app.apk (optional)",
                    "url" to "string: direct APK URL (optional)",
                    "label" to "string: display label (optional)"
                )
            )
        )
        put(
            tool(
                "bliss_uninstall_clone",
                "Uninstall a clone package.",
                schema("package" to "string: clone package")
            )
        )
        put(
            tool(
                "bliss_launch_clone",
                "Launch a clone on the virtual display.",
                schema(
                    "package" to "string: clone package",
                    "width" to "integer: optional display width",
                    "height" to "integer: optional display height",
                    "fps" to "integer: optional frame rate"
                )
            )
        )
        put(
            tool(
                "bliss_stop_clone",
                "Stop a clone and release the display.",
                schema("package" to "string: clone package")
            )
        )
        put(
            tool(
                "bliss_screenshot",
                "Capture the current virtual display as a JPEG image.",
                schema("width" to "integer: optional max width, default 900")
            )
        )
        put(
            tool(
                "bliss_record_start",
                "Record the virtual display to an MP4 file.",
                schema("fps" to "integer: optional 2-15, default 6", "width" to "integer: optional max width, default 720")
            )
        )
        put(
            tool(
                "bliss_record_stop",
                "Stop the active MP4 recording.",
                JSONObject()
            )
        )
        put(
            tool(
                "bliss_record_status",
                "Get active recording state and file path.",
                JSONObject()
            )
        )
        put(
            tool(
                "bliss_logs",
                "Read recent logcat lines for a clone.",
                schema("package" to "string: clone package", "lines" to "integer: optional, default 300")
            )
        )
        put(
            tool(
                "bliss_tap",
                "Tap the virtual display at pixel coordinates.",
                schema("x" to "integer", "y" to "integer")
            )
        )
        put(
            tool(
                "bliss_swipe",
                "Swipe on the virtual display.",
                schema(
                    "x1" to "integer", "y1" to "integer", "x2" to "integer",
                    "y2" to "integer", "ms" to "integer: optional, default 260"
                )
            )
        )
        put(
            tool(
                "bliss_text",
                "Type text into the virtual display.",
                schema("text" to "string")
            )
        )
        put(
            tool(
                "bliss_key",
                "Press a key by name or Android keycode (back, home, enter, tab, esc, recents).",
                schema("key" to "string")
            )
        )
        put(
            tool(
                "bliss_ocr",
                "Capture the display and run on-device OCR. Returns text and bounding boxes.",
                schema("width" to "integer: optional max width, default 1000")
            )
        )
    }

    private fun tool(name: String, description: String, properties: JSONObject): JSONObject {
        val required = JSONArray()
        val keys = properties.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = properties.optString(key)
            if (!value.contains("optional")) required.put(key)
        }
        return JSONObject()
            .put("name", name)
            .put("description", description)
            .put(
                "inputSchema",
                JSONObject()
                    .put("type", "object")
                    .put("properties", properties)
                    .put("required", required)
                    .put("additionalProperties", false)
            )
    }

    /** Properties are JSON-Schema objects, not strings; helper keeps definitions terse. */
    private fun schema(vararg entries: Pair<String, String>): JSONObject {
        val props = JSONObject()
        for ((name, spec) in entries) {
            val type = when {
                spec.startsWith("integer") -> "integer"
                spec.startsWith("number") -> "number"
                spec.startsWith("boolean") -> "boolean"
                spec.startsWith("object") -> "object"
                spec.startsWith("array") -> "array"
                else -> "string"
            }
            val desc = spec.substringAfter(':', spec).trim()
            props.put(name, JSONObject().put("type", type).put("description", desc))
        }
        return props
    }

    private fun callTool(name: String, args: JSONObject): JSONObject = when (name) {
        "bliss_status" -> textResult(runBlocking { HeadlessCloneEngine.apiStatus() })
        "bliss_clones" -> textResult(runBlocking { HeadlessCloneEngine.apiClones() })
        "bliss_install_apk" -> textResult(
            runBlocking {
                HeadlessCloneEngine.apiInstall(
                    args.optString("path").takeIf { it.isNotBlank() },
                    args.optString("url").takeIf { it.isNotBlank() },
                    args.optString("label").takeIf { it.isNotBlank() },
                )
            }
        )
        "bliss_uninstall_clone" -> textResult(
            runBlocking { HeadlessCloneEngine.apiUninstall(requirePackage(args)) }
        )
        "bliss_launch_clone" -> textResult(
            runBlocking {
                HeadlessCloneEngine.apiLaunch(
                    requirePackage(args),
                    args.optInt("width", 0),
                    args.optInt("height", 0),
                    args.optInt("fps", 0),
                )
            }
        )
        "bliss_stop_clone" -> textResult(
            runBlocking { HeadlessCloneEngine.apiStop(requirePackage(args)) }
        )
        "bliss_screenshot" -> {
            val bytes = runBlocking {
                HeadlessCloneEngine.apiCapture(args.optInt("width", 900).coerceIn(200, 2160), 90)
            } ?: throw IllegalStateException("No display frame")
            JSONObject()
                .put("isError", false)
                .put(
                    "content",
                    JSONArray().put(
                        JSONObject()
                            .put("type", "image")
                            .put("mimeType", "image/jpeg")
                            .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    )
                )
        }
        "bliss_record_start" -> textResult(
            CloneRecorder.start(
                ControlServer.context()
                    ?: throw IllegalStateException("App context unavailable"),
                args.optInt("fps", 6),
                args.optInt("width", 720),
            )
        )
        "bliss_record_stop" -> textResult(CloneRecorder.stop())
        "bliss_record_status" -> textResult(CloneRecorder.status())
        "bliss_logs" -> textResult(
            runBlocking {
                HeadlessCloneEngine.apiLogs(
                    requirePackage(args),
                    args.optInt("lines", 300),
                )
            }
        )
        "bliss_tap" -> textResult(
            runBlocking { HeadlessCloneEngine.apiTap(args.optInt("x"), args.optInt("y")) }
        )
        "bliss_swipe" -> textResult(
            runBlocking {
                HeadlessCloneEngine.apiSwipe(
                    args.optInt("x1"), args.optInt("y1"),
                    args.optInt("x2"), args.optInt("y2"),
                    args.optInt("ms", 260),
                )
            }
        )
        "bliss_text" -> textResult(
            runBlocking { HeadlessCloneEngine.apiText(args.optString("text")) }
        )
        "bliss_key" -> {
            val code = keyCode(args.optString("key")) ?: throw IllegalArgumentException("Unknown key")
            textResult(runBlocking { HeadlessCloneEngine.apiKey(code) })
        }
        "bliss_ocr" -> {
            val width = args.optInt("width", 1000).coerceIn(200, 2160)
            val bytes = runBlocking { HeadlessCloneEngine.apiCapture(width, 92) }
                ?: throw IllegalStateException("No display frame")
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IllegalStateException("Frame decode failed")
            textResult(runBlocking { OcrEngine.recognize(bitmap) })
        }
        else -> throw IllegalArgumentException("Unknown tool: $name")
    }

    private fun requirePackage(args: JSONObject): String =
        args.optString("package").takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("package is required")

    private fun keyCode(value: String): Int? {
        value.trim().toIntOrNull()?.let { return it }
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
            "menu" -> 82
            "search" -> 84
            else -> null
        }
    }

    private fun textResult(obj: JSONObject): JSONObject =
        JSONObject()
            .put("isError", !obj.optBoolean("ok", true))
            .put(
                "content",
                JSONArray().put(JSONObject().put("type", "text").put("text", obj.toString()))
            )

    private fun result(id: Any?, value: JSONObject): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", value)

    private fun error(id: Any?, code: Int, message: String): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", id)
            .put("error", JSONObject().put("code", code).put("message", message))
}
