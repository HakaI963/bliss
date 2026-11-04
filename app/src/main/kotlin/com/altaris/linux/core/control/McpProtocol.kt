package com.altaris.linux.core.control

import android.graphics.BitmapFactory
import android.util.Base64
import com.altaris.linux.core.a11y.A11yBridge
import com.altaris.linux.core.clone.HeadlessCloneEngine
import com.altaris.linux.core.rea.ReaManager
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
                    // The rules that make the difference between an agent that
                    // works here and one that spends its budget on screenshots.
                    .put("instructions", INSTRUCTIONS)
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
        put(
            tool(
                "altaris_sessions",
                "List every running clone and its virtual display. Each launch owns its own " +
                    "display, so several clones (and several agents) can run at the same time.",
                JSONObject()
            )
        )
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
                "altaris_identity",
                "The email address the user allows you to use when a site asks you to " +
                    "sign in. Returns { email } or an empty string when the user has not " +
                    "set one. Use it only for sign-in forms; never invent an address, and " +
                    "never send it anywhere else.",
                JSONObject()
            )
        )
        put(
            tool(
                "altaris_launch_clone",
                "Launch a clone on its own virtual display. Other running clones are not touched " +
                    "and keep their displays, so different agents can drive different clones at once.",
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
                "Stop one clone and release only its own display; other clones keep running.",
                schema("package" to "string: clone package")
            )
        )
        put(
            tool(
                "altaris_screenshot",
                "Capture one virtual display as a JPEG image.",
                schema(
                    "width" to "integer: optional max width, default 900",
                    "package" to "string: optional clone package; defaults to the viewer's clone"
                )
            )
        )
        put(
            tool(
                "altaris_record_start",
                "Record the virtual display to an MP4 file.",
                schema(
                    "fps" to "integer: optional 2-15, default 6",
                    "width" to "integer: optional max width, default 720",
                    "package" to "string: optional clone package"
                )
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
                "Tap one virtual display at pixel coordinates.",
                schema(
                    "x" to "integer", "y" to "integer",
                    "package" to "string: optional clone package; defaults to the viewer's clone"
                )
            )
        )
        put(
            tool(
                "altaris_swipe",
                "Swipe on one virtual display.",
                schema(
                    "x1" to "integer", "y1" to "integer", "x2" to "integer",
                    "y2" to "integer", "ms" to "integer: optional, default 260",
                    "package" to "string: optional clone package"
                )
            )
        )
        put(
            tool(
                "altaris_text",
                "Type text into one virtual display.",
                schema("text" to "string", "package" to "string: optional clone package")
            )
        )
        put(
            tool(
                "altaris_key",
                "Press a key by name or Android keycode (back, home, enter, tab, esc, recents).",
                schema("key" to "string", "package" to "string: optional clone package")
            )
        )
        put(
            tool(
                "altaris_ocr",
                "Capture one display and run on-device OCR. Returns text and bounding boxes.",
                schema(
                    "width" to "integer: optional max width, default 1000",
                    "package" to "string: optional clone package"
                )
            )
        )
        put(
            tool(
                "altaris_ui_tree",
                "Read the UI node tree of one virtual display through the accessibility service.",
                schema(
                    "package" to "string: optional clone package",
                    "display" to "integer: optional display id, default the clone's display",
                    "max" to "integer: optional max nodes, default 600"
                )
            )
        )
        put(
            tool(
                "altaris_ui_click",
                "Click a UI node by the id from the last altaris_ui_tree snapshot.",
                schema(
                    "nodeId" to "string: node id (path) from altaris_ui_tree",
                    "package" to "string: optional clone package"
                )
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
                    "package" to "string: optional clone package",
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
        put(
            tool(
                "altaris_rea_status",
                "REA (reverse engineering) install and server status for a distro.",
                schema("distro" to "string: optional distro id")
            )
        )
        put(
            tool(
                "altaris_rea_install",
                "Install Node 22 + rea-agents inside a distro, optionally with external analysis tools.",
                schema(
                    "distro" to "string: optional distro id",
                    "tools" to "array: optional ids: jadx, apktool, aapt, adb, java, binutils, gdb, rizin, python3, binwalk, p7zip, tshark, sqlite3, file"
                )
            )
        )
        put(
            tool(
                "altaris_rea_start",
                "Start the REA MCP server inside the distro and publish its tools here.",
                schema("distro" to "string: optional distro id")
            )
        )
        put(tool("altaris_rea_stop", "Stop the REA MCP server.", JSONObject()))
        put(
            tool(
                "altaris_rea_tools",
                "List every REA tool currently published through this endpoint.",
                JSONObject()
            )
        )
        put(
            tool(
                "altaris_rea_call",
                "Call any REA tool by name (analyze APK, inspect binary, Android resources, live device, …).",
                schema(
                    "name" to "string: REA tool name from altaris_rea_tools",
                    "arguments" to "object: tool arguments",
                    "timeoutMs" to "integer: optional, default 600000"
                )
            )
        )
        put(
            tool(
                "altaris_rea_agents",
                "Register this MCP endpoint in every AI agent config found inside the distro.",
                schema("distro" to "string: optional distro id")
            )
        )
        put(
            tool(
                "altaris_rea_clone_apk",
                "Export a clone's APKs to /sdcard/Download/Altaris/rea so REA can analyze them.",
                schema("package" to "string: clone package")
            )
        )
        put(
            tool(
                "altaris_debug_attach",
                "Attach the live debugger to a clone. Accepts a package name or a pid, " +
                    "and starts the clone itself when it is not running — you do not " +
                    "need to launch it first. Registers, memory, mappings and " +
                    "backtraces are read from the running process, so this answers " +
                    "runtime questions that a screenshot cannot.",
                schema(
                    "target" to "string: clone package name or host pid",
                )
            )
        )
        put(
            tool(
                "altaris_debug_command",
                "Run one gdb command against the attached process and return its output. " +
                    "Examples: 'info registers', 'info proc mappings', 'bt', " +
                    "'x/16gx 0x7f12340000', 'break *0x...'. Attach first.",
                schema(
                    "command" to "string: gdb command, passed verbatim",
                )
            )
        )
        put(tool("altaris_frida_install", "Install and start frida-server on the host and the frida CLI in the REA distro. Needed once, before runtime instrumentation. Requires root.", JSONObject()))
        put(tool("altaris_frida_stop", "Stop frida-server.", JSONObject()))
        put(tool("altaris_gadget_prepare", "Download Frida Gadget so new clones are debuggable without root. Root attaches from outside; Gadget runs inside the clone.", JSONObject()))
        put(tool("altaris_debug_state", "Debugger state: attached pid, target, backend, and the transcript.", JSONObject()))
        put(tool("altaris_debug_detach", "Detach the debugger and leave the cloned app running.", JSONObject()))
        put(
            tool(
                "altaris_ghidra_analyze",
                "Run Ghidra's headless analyzer on a file inside the distro and return its function list.",
                schema(
                    "distro" to "string: optional distro id",
                    "target" to "string: guest path to a binary, APK, or library",
                    "max" to "integer: max functions to return (default 300)"
                )
            )
        )
        // REA's tools are advertised even when the server is not up yet.
        //
        // They used to be listed only when `cachedTools()` was non-null, which
        // requires a running server. So an agent that connected before REA was
        // started saw no REA tools at all — and then never used REA, even
        // though it was installed and one call away. That is why it "forgot"
        // REA existed: the tool list it was given genuinely did not contain it.
        //
        // The live catalog is used when it exists. When it does not, a static
        // set of entry points is advertised instead, so the agent knows REA is
        // there and can start it. This is a tool list, so it must not block on
        // starting a server.
        val reaTools = ReaManager.cachedTools()
        if (reaTools != null) {
            for (i in 0 until reaTools.length()) {
                val definition = reaTools.optJSONObject(i) ?: continue
                put(
                    JSONObject(definition.toString())
                        .put("description", "[REA] " + definition.optString("description"))
                )
            }
        } else {
            for ((name, description) in REA_ENTRY_POINTS) {
                put(tool(name, "[REA] " + description, JSONObject()))
            }
        }
    }

    /**
     * REA entry points advertised when the live catalog is not loaded yet.
     *
     * The agent needs to know REA exists before it is running, or it will
     * analyse a binary with nothing at all. These are the calls that matter for
     * a reverse-engineering task, and each one starts REA itself.
     */
    private val REA_ENTRY_POINTS = listOf(
        "altaris_rea_status" to
            "REA status. Call this first for any binary, APK, DEX or native-library task.",
        "altaris_rea_start" to
            "Start the REA reverse-engineering server. Most REA tools need it.",
        "altaris_rea_tools" to
            "List every REA tool available once the server is up.",
        "altaris_rea_call" to
            "Call any REA tool by name with its arguments.",
        "altaris_rea_clone_apk" to
            "Export a clone's APKs and native libraries for analysis.",
        "altaris_ghidra_analyze" to
            "Run Ghidra headless on a file and return its function list.",
    )

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
        "altaris_identity" -> {
            val ctx = ControlServer.context() ?: throw IllegalStateException("App context unavailable")
            val email = com.altaris.linux.core.agent.AgentEmail.get(ctx)
            textResult(
                JSONObject()
                    .put("ok", true)
                    .put("email", email)
                    .put(
                        "note",
                        if (email.isBlank())
                            "The user has not set an email. Do not invent one: ask, or stop."
                        else
                            "Use this only in sign-in forms. It is not a password."
                    )
            )
        }
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
        "altaris_sessions" -> textResult(runBlocking { HeadlessCloneEngine.apiSessions() })
        "altaris_screenshot" -> {
            val bytes = runBlocking {
                HeadlessCloneEngine.apiCapture(
                    args.optInt("width", 900).coerceIn(200, 2160),
                    90,
                    5_000,
                    optionalPackage(args),
                )
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
                optionalPackage(args),
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
            runBlocking {
                HeadlessCloneEngine.apiTap(
                    args.optInt("x"),
                    args.optInt("y"),
                    optionalPackage(args),
                )
            }
        )
        "altaris_swipe" -> textResult(
            runBlocking {
                HeadlessCloneEngine.apiSwipe(
                    args.optInt("x1"), args.optInt("y1"),
                    args.optInt("x2"), args.optInt("y2"),
                    args.optInt("ms", 260),
                    optionalPackage(args),
                )
            }
        )
        "altaris_text" -> textResult(
            runBlocking {
                HeadlessCloneEngine.apiText(args.optString("text"), optionalPackage(args))
            }
        )
        "altaris_key" -> {
            val code = keyCode(args.optString("key")) ?: throw IllegalArgumentException("Unknown key")
            textResult(runBlocking { HeadlessCloneEngine.apiKey(code, optionalPackage(args)) })
        }
        "altaris_ocr" -> {
            val width = args.optInt("width", 1000).coerceIn(200, 2160)
            val bytes = runBlocking {
                HeadlessCloneEngine.apiCapture(width, 92, 5_000, optionalPackage(args))
            } ?: throw IllegalStateException("No display frame")
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IllegalStateException("Frame decode failed")
            textResult(runBlocking { OcrEngine.recognize(bitmap) })
        }
        "altaris_ui_tree" -> {
            val ctx = ControlServer.context() ?: throw IllegalStateException("App context unavailable")
            val pkg = args.optString("package").takeIf { it.isNotBlank() }
                ?: HeadlessCloneEngine.activeClonePackage.value
            val display = if (args.has("display")) {
                args.optInt("display")
            } else {
                HeadlessCloneEngine.displayIdFor(pkg).takeIf { it > 0 }
                    ?: HeadlessCloneEngine.displayId.value
            }
            val max = args.optInt("max", 600).coerceIn(1, 2000)
            textResult(a11yObject(A11yBridge.tree(ctx, display, max, pkg)))
        }
        "altaris_ui_click" -> {
            val ctx = ControlServer.context() ?: throw IllegalStateException("App context unavailable")
            textResult(
                a11yObject(
                    A11yBridge.click(
                        ctx,
                        args.opt("nodeId")?.toString().orEmpty(),
                        optionalPackage(args),
                    )
                )
            )
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
            val pkg = optionalPackage(args)
            val display = if (args.has("display")) {
                args.optInt("display")
            } else {
                HeadlessCloneEngine.displayIdFor(pkg).takeIf { it > 0 }
                    ?: HeadlessCloneEngine.displayId.value
            }
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
        "altaris_rea_status" -> {
            val ctx = ControlServer.context() ?: throw IllegalStateException("App context unavailable")
            val distro = args.optString("distro").takeIf { it.isNotBlank() }
                ?: reaTargetDistro(ctx)
            if (distro == null) {
                textResult(JSONObject().put("ok", false).put("error", "no installed distro"))
            } else {
                textResult(reaStatusJson(runBlocking { ReaManager.probe(ctx, distro) }))
            }
        }
        "altaris_rea_install" -> {
            val ctx = ControlServer.context() ?: throw IllegalStateException("App context unavailable")
            val distro = args.optString("distro").takeIf { it.isNotBlank() }
                ?: reaTargetDistro(ctx)
                ?: throw IllegalStateException("no installed distro")
            val requested = args.optJSONArray("tools")
            val deps = if (requested == null) emptyList<String>() else
                (0 until requested.length()).mapNotNull {
                    requested.optString(it).takeIf { id -> id.isNotBlank() }
                }
            textResult(reaStatusJson(runBlocking { ReaManager.install(ctx, distro, deps) }))
        }
        "altaris_rea_start" -> {
            val ctx = ControlServer.context() ?: throw IllegalStateException("App context unavailable")
            val distro = args.optString("distro").takeIf { it.isNotBlank() }
                ?: reaTargetDistro(ctx)
                ?: throw IllegalStateException("no installed distro")
            textResult(reaStatusJson(runBlocking { ReaManager.start(ctx, distro) }))
        }
        "altaris_rea_stop" -> textResult(reaStatusJson(runBlocking { ReaManager.stop() }))
        "altaris_rea_tools" -> {
            val tools = runBlocking { ensureReaRunning() }?.let { ReaManager.cachedTools() }
                ?: throw IllegalStateException(
                    "REA is not running and could not be started. Install it from " +
                        "Tools → Reverse engineering first."
                )
            textResult(JSONObject().put("ok", true).put("count", tools.length()).put("tools", tools))
        }
        "altaris_rea_call" -> {
            val tool = args.optString("name").takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("name is required")
            val arguments = args.optJSONObject("arguments") ?: JSONObject()
            val timeout = args.optLong("timeoutMs", 600_000L).coerceIn(5_000L, 3_600_000L)
            // Start REA rather than telling the agent to.
            //
            // "REA is not running. Call altaris_rea_start first" is a dead end
            // for an agent that was told to analyse something: it is one call
            // away, exactly like launching a clone. An explicit start is still
            // available; this only removes the pointless failure.
            ensureReaRunning()
            runBlocking { ReaManager.call(tool, arguments, timeout) }
        }
        "altaris_rea_agents" -> {
            val ctx = ControlServer.context() ?: throw IllegalStateException("App context unavailable")
            val distro = args.optString("distro").takeIf { it.isNotBlank() }
                ?: reaTargetDistro(ctx)
                ?: throw IllegalStateException("no installed distro")
            textResult(reaStatusJson(runBlocking { ReaManager.registerAgents(ctx, distro) }))
        }
        "altaris_rea_clone_apk" -> {
            val ctx = ControlServer.context() ?: throw IllegalStateException("App context unavailable")
            val pkg = requirePackage(args)
            val (exit, out) = runBlocking { ReaManager.exportCloneApk(ctx, pkg) }
            textResult(
                JSONObject()
                    .put("ok", exit == 0)
                    .put("package", pkg)
                    .put("paths", JSONArray().apply {
                        out.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { put(it) }
                    })
                    .put("error", if (exit == 0) "" else out.takeLast(400))
            )
        }
        "altaris_debug_attach" -> {
            val ctx = ControlServer.context() ?: throw IllegalStateException("App context unavailable")
            val target = args.optString("target").takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("target is required")
            runBlocking { com.altaris.linux.core.rea.ReaDebugger.attach(ctx, target) }
            textResult(debugStateJson())
        }
        "altaris_debug_command" -> {
            val ctx = ControlServer.context() ?: throw IllegalStateException("App context unavailable")
            val command = args.optString("command").takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("command is required")
            runBlocking { com.altaris.linux.core.rea.ReaDebugger.run(ctx, command) }
            textResult(debugStateJson())
        }
        "altaris_frida_install" -> {
            val ctx = ControlServer.context() ?: throw IllegalStateException("App context unavailable")
            runBlocking { com.altaris.linux.core.rea.ReaDebugger.installFrida(ctx) }
            textResult(debugStateJson())
        }
        "altaris_gadget_prepare" -> {
            val ctx = ControlServer.context() ?: throw IllegalStateException("App context unavailable")
            runBlocking { com.altaris.linux.core.rea.ReaDebugger.prepareGadget(ctx) }
            textResult(debugStateJson())
        }
        "altaris_frida_stop" -> {
            val ctx = ControlServer.context() ?: throw IllegalStateException("App context unavailable")
            runBlocking { com.altaris.linux.core.rea.ReaDebugger.stopFrida(ctx) }
            textResult(debugStateJson())
        }
        "altaris_debug_state" -> textResult(debugStateJson())
        "altaris_debug_detach" -> {
            val ctx = ControlServer.context() ?: throw IllegalStateException("App context unavailable")
            runBlocking { com.altaris.linux.core.rea.ReaDebugger.detach(ctx) }
            textResult(debugStateJson())
        }
        "altaris_ghidra_analyze" -> {
            val ctx = ControlServer.context() ?: throw IllegalStateException("App context unavailable")
            val distro = args.optString("distro").takeIf { it.isNotBlank() }
                ?: reaTargetDistro(ctx)
                ?: throw IllegalStateException("no installed distro")
            val target = args.optString("target").takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("target path is required")
            val max = args.optInt("max", 300).coerceIn(10, 5000)
            textResult(runBlocking { ReaManager.analyzeGhidra(ctx, distro, target, max) })
        }
        else -> {
            if (!ReaManager.isReaTool(name)) {
                throw IllegalArgumentException("Unknown tool: " + name)
            }
            runBlocking {
                ReaManager.call(
                    name,
                    args,
                    args.optLong("timeoutMs", 600_000L).coerceIn(5_000L, 3_600_000L),
                )
            }
        }
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

    /** Optional clone package: null means "the display the viewer is showing". */
    private fun optionalPackage(args: JSONObject): String? =
        args.optString("package").takeIf { it.isNotBlank() }

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

    /**
     * The distro REA is installed in, as the user chose it.
     *
     * Every REA control tool used to fall back to `defaultDistro()`, which is
     * "debian, else the first installed distro" — so an agent could silently
     * run REA against a distro that does not have it. The remembered target is
     * the only correct default.
     */
    /**
     * What an agent needs to know before it calls anything.
     *
     * Two facts it cannot learn from the tool list: there is no physical
     * device here, and the screen-based tools are the slow path.
     */
    private val INSTRUCTIONS = """
        Every app you can reach is a clone on this device. There is no separate
        physical device, and no folder to search. Do not look in /sdcard, /data
        or anywhere else for an app: `altaris_clones` lists them and
        `altaris_launch_clone` starts one.

        "Not running" is never a reason to stop. Launch the clone, then act on
        it.

        ## Reverse engineering is REA's job, not yours

        For ANY question about a binary, APK, DEX, native library, firmware
        image, archive, game asset, or what code does at runtime, use REA. Do
        not reason about the file from its name, do not read bytes yourself, do
        not guess, and do not shell out to objdump/strings/readelf when REA has
        a tool for it.

        Start here, every time:

        1. `altaris_rea_status` — is REA installed and running.
        2. `altaris_rea_tools` — the real catalog. REA exposes ~229 tools; call
           `altaris_rea_call { "name": "<tool>", "arguments": { ... } }` with
           the one that matches the question.
        3. `altaris_rea_clone_apk` — export a clone's APKs and .so files, which
           returns the paths to analyse.
        4. `altaris_debug_attach` — for runtime behaviour (what a method
           returns, what an address holds, what the process maps).

        These calls start REA themselves; you do not need to start it first.
        If `altaris_rea_status` says it is not installed, say so and stop —
        do not fall back to reading the file by hand.

        REA calls can take minutes: a cold Ghidra import plus decompile of a
        23 MB binary legitimately runs for several minutes. Call it, wait for
        the result, and do not assume it failed because it is slow. Do not
        retry the same call while one is still running.

        ## Working the UI

        `altaris_ui_tree` returns the real view hierarchy — text, resource ids,
        bounds, clickability — in one call, and `altaris_ui_click` /
        `altaris_ui_type` / `altaris_ui_scroll` act on its nodes. That is the
        fast path. `altaris_ocr` and `altaris_screenshot` are the fallback for
        a canvas, a game, or a view with no nodes; they cost a capture and a
        recognition per step. `altaris_tap` / `altaris_swipe` / `altaris_key`
        take raw coordinates and are the last resort. When a step fails,
        re-read the tree instead of guessing a coordinate.

        ## Signing in to a site

        The browser you drive is Chromium, on the clone's display. When a site
        asks for an email, call `altaris_identity` and use the address it
        returns. If it returns an empty string, the user has not set one: ask
        them, or stop and say why. Never invent an address, and never put it
        anywhere except a sign-in form.
    """.trimIndent()

    /** The debugger state as an agent can read it. */
    private fun debugStateJson(): JSONObject {
        val state = com.altaris.linux.core.rea.ReaDebugger.state.value
        return JSONObject()
            .put("ok", state.attachedPid != null)
            .put("available", state.available)
            .put("attachedPid", state.attachedPid ?: JSONObject.NULL)
            .put("target", state.target)
            .put("runningApps", JSONArray().apply { state.runningApps.forEach { put(it) } })
            .put("error", state.error)
            .put("fridaInstalled", state.fridaInstalled)
            .put("fridaRunning", state.fridaRunning)
            .put("fridaClient", state.fridaClient)
            .put("gadgetReady", state.gadgetReady)
            .put("transcript", state.transcript.takeLast(8000))
    }

    private fun reaTargetDistro(ctx: android.content.Context): String? =
        com.altaris.linux.core.rea.ReaDistro.activeTarget(ctx)?.id

    /**
     * Make sure REA's stdio server is up, starting it when it is not.
     *
     * An agent told to analyse something should not have to be told to start
     * the server first — it is one call away, and the alternative is the dead
     * end "REA is not running. Call altaris_rea_start first", which reads as
     * "this does not work here". Returns the status, or null when there is no
     * distro to start it in.
     */
    private fun ensureReaRunning(): ReaManager.Status? {
        val ctx = ControlServer.context() ?: return null
        if (ReaManager.status.value.running) return ReaManager.status.value
        val distro = reaTargetDistro(ctx) ?: return null
        return runCatching { runBlocking { ReaManager.start(ctx, distro) } }.getOrNull()
            ?.takeIf { it.running }
    }

    private fun reaStatusJson(status: ReaManager.Status): JSONObject {
        val available = JSONArray()
        status.available.sorted().forEach { available.put(it) }
        return JSONObject()
            .put("ok", status.error.isBlank())
            .put("distro", status.distroId)
            .put("method", status.method)
            .put("installed", status.installed)
            .put("running", status.running)
            .put("node", status.nodeVersion)
            .put("rea", status.reaVersion)
            .put("packageManager", status.packageManager)
            .put("tools", status.toolCount)
            .put("available", available)
            .put("busy", status.busy)
            .put("error", status.error)
            .put("log", status.log.takeLast(4000))
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
