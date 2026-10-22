package com.altaris.linux.core.control

import android.graphics.BitmapFactory
import android.util.Base64
import com.altaris.linux.core.a11y.A11yBridge
import com.altaris.linux.core.clone.HeadlessCloneEngine
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
                        JSONObject().put("name", "altaris-clone-control").put("version", "1.0.0")
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
        put(tool("altaris_status", "Get shell/display/clone engine status.", JSONObject()))
        put(tool("altaris_clones", "List installed clones with package, created and last-opened metadata.", JSONObject()))
        put(tool("altaris_apps", "List installed source apps available for cloning.", JSONObject()))
        put(
            tool(
                "altaris_install_apk",
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
                "altaris_uninstall_clone",
                "Uninstall a clone package.",
                schema("package" to "string: clone package")
            )
        )
        put(
            tool(
                "altaris_launch_clone",
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
                "altaris_stop_clone",
                "Stop a clone and release the display.",
                schema("package" to "string: clone package")
            )
        )
        put(
            tool(
                "altaris_screenshot",
                "Capture the current virtual display as a JPEG image.",
                schema("width" to "integer: optional max width, default 900")
            )
        )
        put(
            tool(
                "altaris_record_start",
                "Record the virtual display to an MP4 file.",
                schema("fps" to "integer: optional 2-15, default 6", "width" to "integer: optional max width, default 720")
            )
        )
        put(
            tool(
                "altaris_record_stop",
                "Stop the active MP4 recording.",
                JSONObject()
            )
        )
        put(
            tool(
                "altaris_record_status",
                "Get active recording state and file path.",
                JSONObject()
            )
        )
        put(
            tool(
                "altaris_logs",
                "Read recent logcat lines for a clone.",
                schema("package" to "string: clone package", "lines" to "integer: optional, default 300")
            )
        )
        put(
            tool(
                "altaris_tap",
                "Tap the virtual display at pixel coordinates.",
                schema("x" to "integer", "y" to "integer")
            )
        )
        put(
            tool(
                "altaris_swipe",
                "Swipe on the virtual display.",
                schema(
                    "x1" to "integer", "y1" to "integer", "x2" to "integer",
                    "y2" to "integer", "ms" to "integer: optional, default 260"
                )
            )
        )
        put(
            tool(
                "altaris_text",
                "Type text into the virtual display.",
                schema("text" to "string")
            )
        )
        put(
            tool(
                "altaris_key",
                "Press a key by name or Android keycode (back, home, enter, tab, esc, recents).",
                schema("key" to "string")
            )
        )
        put(
            tool(
                "altaris_ocr",
                "Capture the display and run on-device OCR. Returns text and bounding boxes.",
                schema("width" to "integer: optional max width, default 1000")
            )
        )
        put(
            tool(
                "altaris_ui_tree",
                "Read the UI node tree of the virtual display through the accessibility service.",
                schema(
                    "display" to "integer: optional display id, default the running clone display",
                    "max" to "integer: optional max nodes, default 600"
                )
            )
        )
        put(
            tool(
                "altaris_ui_click",
                "Click a UI node by the id from the last altaris_ui_tree snapshot.",
                schema("nodeId" to "string: node id (path) from altaris_ui_tree")
            )
        )
        put(
            tool(
                "altaris_ui_type",
                "Type text into an editable UI node.",
                schema("nodeId" to "string: node id (path) from altaris_ui_tree", "text" to "string: text to enter")
            )
        )
        put(
            tool(
                "altaris_ui_scroll",
                "Scroll a UI node forward or backward.",
                schema(
                    "nodeId" to "string: node id (path) from altaris_ui_tree",
                    "forward" to "boolean: optional, default true"
                )
            )
        )
        put(
            tool(
                "altaris_ui_tap_text",
                "Tap the first element whose text or content description matches.",
                schema(
                    "text" to "string: text to match",
                    "exact" to "boolean: optional, default false",
                    "display" to "integer: optional display id"
                )
            )
        )
        put(
            tool(
                "altaris_ui_enable",
                "Enable the Altaris accessibility service (needs root).",
                JSONObject()
            )
        )
        put(
            tool(
                "altaris_ui_display",
                "Framework display id of the running clone (needed for gestures).",
                schema("package" to "string: optional clone package, default the running clone")
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
        "altaris_status" -> textResult(runBlocking { HeadlessCloneEngine.apiStatus() })
        "altaris_clones" -> textResult(runBlocking { HeadlessCloneEngine.apiClones() })
        "altaris_apps" -> textResult(runBlocking { HeadlessCloneEngine.apiApps() })
        "altaris_install_apk" -> textResult(
            runBlocking {
                HeadlessCloneEngine.apiInstall(
                    args.optString("path").takeIf { it.isNotBlank() },
                    args.optString("url").takeIf { it.isNotBlank() },
                    args.optString("label").takeIf { it.isNotBlank() },
                )
            }
        )
        "altaris_uninstall_clone" -> textResult(
            runBlocking { HeadlessCloneEngine.apiUninstall(requirePackage(args)) }
        )
        "altaris_launch_clone" -> textResult(
            runBlocking {
                HeadlessCloneEngine.apiLaunch(
                    requirePackage(args),
                    args.optInt("width", 0),
                    args.optInt("height", 0),
                    args.optInt("fps", 0),
                )
            }
        )
        "altaris_stop_clone" -> textResult(
            runBlocking { HeadlessCloneEngine.apiStop(requirePackage(args)) }
        )
        "altaris_screenshot" -> {
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
        "altaris_record_start" -> textResult(
            CloneRecorder.start(
                ControlServer.context()
                    ?: throw IllegalStateException("App context unavailable"),
                args.optInt("fps", 6),
                args.optInt("width", 720),
            )
        )
        "altaris_record_stop" -> textResult(CloneRecorder.stop())
        "altaris_record_status" -> textResult(CloneRecorder.status())
        "altaris_logs" -> textResult(
            runBlocking {
                HeadlessCloneEngine.apiLogs(
                    requirePackage(args),
                    args.optInt("lines", 300),
                )
            }
        )
        "altaris_tap" -> textResult(
            runBlocking { HeadlessCloneEngine.apiTap(args.optInt("x"), args.optInt("y")) }
        )
        "altaris_swipe" -> textResult(
            runBlocking {
                HeadlessCloneEngine.apiSwipe(
                    args.optInt("x1"), args.optInt("y1"),
                    args.optInt("x2"), args.optInt("y2"),
                    args.optInt("ms", 260),
                )
            }
        )
        "altaris_text" -> textResult(
            runBlocking { HeadlessCloneEngine.apiText(args.optString("text")) }
        )
        "altaris_key" -> {
            val code = keyCode(args.optString("key")) ?: throw IllegalArgumentException("Unknown key")
            textResult(runBlocking { HeadlessCloneEngine.apiKey(code) })
        }
        "altaris_ocr" -> {
            val width = args.optInt("width", 1000).coerceIn(200, 2160)
            val bytes = runBlocking { HeadlessCloneEngine.apiCapture(width, 92) }
                ?: throw IllegalStateException("No display frame")
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IllegalStateException("Frame decode failed")
            textResult(runBlocking { OcrEngine.recognize(bitmap) })
        }
        "altaris_ui_tree" -> {
            val ctx = ControlServer.context() ?: throw IllegalStateException("App context unavailable")
            val display = args.optInt("display", HeadlessCloneEngine.displayId.value)
            val max = args.optInt("max", 600).coerceIn(1, 2000)
            val pkg = args.optString("package").takeIf { it.isNotBlank() }
                ?: HeadlessCloneEngine.activeClonePackage.value
            textResult(a11yObject(A11yBridge.tree(ctx, display, max, pkg)))
        }
        "altaris_ui_click" -> {
            val ctx = ControlServer.context() ?: throw IllegalStateException("App context unavailable")
            textResult(a11yObject(A11yBridge.click(ctx, args.opt("nodeId")?.toString().orEmpty())))
        }
        "altaris_ui_type" -> {
            val ctx = ControlServer.context() ?: throw IllegalStateException("App context unavailable")
            textResult(a11yObject(A11yBridge.setText(ctx, args.opt("nodeId")?.toString().orEmpty(), args.optString("text"))))
        }
        "altaris_ui_scroll" -> {
            val ctx = ControlServer.context() ?: throw IllegalStateException("App context unavailable")
            textResult(
                a11yObject(A11yBridge.scroll(ctx, args.opt("nodeId")?.toString().orEmpty(), args.optBoolean("forward", true)))
            )
        }
        "altaris_ui_tap_text" -> {
            val ctx = ControlServer.context() ?: throw IllegalStateException("App context unavailable")
            val display = args.optInt("display", HeadlessCloneEngine.displayId.value)
            textResult(
                a11yObject(A11yBridge.tapText(ctx, display, args.optString("text"), args.optBoolean("exact", false)))
            )
        }
        "altaris_ui_display" -> {
            val ctx = ControlServer.context() ?: throw IllegalStateException("App context unavailable")
            val pkg = args.optString("package").takeIf { it.isNotBlank() }
                ?: HeadlessCloneEngine.activeClonePackage.value
            val display = if (pkg.isNullOrBlank()) -1 else A11yBridge.displayIdFor(ctx, pkg)
            textResult(JSONObject().put("ok", display >= 0).put("display", display).put("package", pkg))
        }
        "altaris_ui_enable" -> {
            val ctx = ControlServer.context() ?: throw IllegalStateException("App context unavailable")
            textResult(a11yObject(A11yBridge.ensureEnabled(ctx)))
        }
        else -> throw IllegalArgumentException("Unknown tool: $name")
    }

    /** Parse a bridge reply, never throws. */
    private fun a11yObject(raw: String): JSONObject {
        val obj = runCatching { JSONObject(raw) }.getOrElse {
            JSONObject().put("ok", false).put("error", it.message ?: it.javaClass.simpleName)
        }
        if (!obj.optBoolean("ok", false)) {
            val error = obj.optString("error")
            if (error.contains("not running") || error.contains("not enabled")) {
                obj.put("error", "accessibility service not enabled")
                    .put("hint", "POST /uiauto/enable (or call altaris_ui_enable)")
            }
        }
        return obj
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
