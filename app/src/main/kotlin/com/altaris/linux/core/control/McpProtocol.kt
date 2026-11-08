package com.altaris.linux.core.control

import android.graphics.BitmapFactory
import android.util.Base64
import com.altaris.linux.core.a11y.A11yBridge
import com.altaris.linux.core.clone.HeadlessCloneEngine
import com.altaris.linux.core.rea.ReaManager
import com.altaris.linux.core.rea.ReaSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
                "altaris_plan",
                "Classify a request and return the tool sequence that answers it. Call this " +
                    "FIRST for anything non-trivial: it says whether the task is navigation, " +
                    "inspection, a UI test, APK analysis, reverse engineering, runtime " +
                    "debugging, clone testing or a multi-step investigation, and which tools " +
                    "to use in which order. A request to open or move through an app is " +
                    "navigation even when the app is a game.",
                schema(
                    "request" to "string: the user's request, verbatim",
                    "package" to "string: target package, when already known (optional)"
                )
            )
        )
        put(
            tool(
                "altaris_device_screenshot",
                "Capture the PHYSICAL device screen as a PNG file. This is not the clone " +
                    "display — use altaris_screenshot for a running clone. Returns the file " +
                    "path, width, height, display id and which backend was used. " +
                    "FLAG_SECURE windows are reported, never bypassed.",
                schema(
                    "display" to "integer: display id (default 0, the main screen)"
                )
            )
        )
        put(
            tool(
                "altaris_doctor",
                "What this build can actually do right now: device, root, Shizuku, " +
                    "accessibility, physical screenshot, clone engine, REA, analysis tools, " +
                    "the Frida Node binding, storage and image handoff. Each entry is " +
                    "AVAILABLE_AND_TESTED, AVAILABLE_NOT_TESTED, MISSING_DEPENDENCY, " +
                    "PERMISSION_REQUIRED, TARGET_UNAVAILABLE, TRANSPORT_UNAVAILABLE, " +
                    "UNSUPPORTED or TEMPORARY_FAILURE, with the fix when something is missing.",
                schema("deep" to "boolean: also read a real accessibility tree (default false)")
            )
        )
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
                "Uninstall a clone package. Accepts the package or the display id.",
                schema(
                    "package" to "string: optional clone package",
                    "display" to "integer: optional display id, resolved to its clone"
                )
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
                "Stop one clone and release only its own display; other clones keep running. " +
                    "Accepts the package or the display id.",
                schema(
                    "package" to "string: optional clone package",
                    "display" to "integer: optional display id, resolved to its clone"
                )
            )
        )
        put(
            tool(
                "altaris_screenshot",
                "Capture one virtual display as a JPEG image.",
                schema(
                    "width" to "integer: optional max width, default 900",
                    "package" to "string: optional clone package; defaults to the viewer's clone",
                    "display" to "integer: optional display id, resolved to its clone"
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
                    "package" to "string: optional clone package",
                    "display" to "integer: optional display id, resolved to its clone"
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
                "Read recent logcat lines for a clone. Address it by package, or by the " +
                    "display id you already have from altaris_sessions — you do not need " +
                    "to convert one to the other. Always returns a `lines` array, empty " +
                    "when there is nothing to show, so a caller can read it unconditionally.",
                schema(
                    "package" to "string: optional clone package",
                    "display" to "integer: optional display id, resolved to its clone",
                    "lines" to "integer: optional, default 300"
                )
            )
        )
        put(
            tool(
                "altaris_tap",
                "Tap one virtual display at pixel coordinates.",
                schema(
                    "x" to "integer", "y" to "integer",
                    "package" to "string: optional clone package; defaults to the viewer's clone",
                    "display" to "integer: optional display id, resolved to its clone"
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
                    "package" to "string: optional clone package",
                    "display" to "integer: optional display id, resolved to its clone"
                )
            )
        )
        put(
            tool(
                "altaris_text",
                "Type text into one virtual display.",
                schema(
                    "text" to "string",
                    "package" to "string: optional clone package",
                    "display" to "integer: optional display id, resolved to its clone"
                )
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
                    "package" to "string: optional clone package",
                    "display" to "integer: optional display id, resolved to its clone"
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
                "altaris_wait",
                "Wait between steps. Use this instead of setTimeout, sleep or a Promise " +
                    "timer: code-mode sandboxes have no timers, and a reference to one " +
                    "fails the whole execute call before the tool is even invoked. " +
                    "With only ms it waits that long; with until it polls the " +
                    "accessibility tree until an element's text or description matches " +
                    "and returns whether it appeared.",
                schema(
                    "ms" to "integer: optional fixed wait, default 500, capped at 15000",
                    "until" to "string: optional text or content description to wait for",
                    "timeoutMs" to "integer: optional poll limit for until, default 8000",
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
                "altaris_jadx_bridge_install",
                "Install the pinned jadx MCP bridge that REA's Android/Java lane " +
                    "requires (inspect_android_package, decompile_all, " +
                    "decode_android_resources and friends). It is downloaded, its " +
                    "size and SHA-256 verified, and REA is restarted so it picks up " +
                    "REA_JADX_MCP_JAR. Safe to call when already installed.",
                JSONObject()
            )
        )
        put(
            tool(
                "altaris_rea_tools",
                "List every REA tool currently published through this endpoint.",
                JSONObject()
            )
        )
        put(
            tool(
                "altaris_rea_schema",
                "The real input schema for one REA tool, plus a minimal example. Call this " +
                    "before altaris_rea_call for any tool you have not called in this " +
                    "session: REA validates strictly and nested arguments are objects " +
                    "discriminated by a \"kind\" field, not bare strings. With no name it " +
                    "returns the index of every REA tool.",
                schema("name" to "string: REA tool name (optional; omit for the index)")
            )
        )
        put(
            tool(
                "altaris_rea_call",
                "Call any REA tool by name (analyze APK, inspect binary, Android resources, live device, …). " +
                    "Arguments are checked against the tool's real schema first, so a shape " +
                    "mistake comes back as the exact field and what it expected instead of a " +
                    "generic invalid_request. `arguments` may be omitted for a tool that takes " +
                    "none.",
                schema(
                    "name" to "string: REA tool name from altaris_rea_tools",
                    "arguments" to "object: optional tool arguments, in the shape altaris_rea_schema returns",
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
                "Export a clone's APKs to /sdcard/Download/Altaris/rea so REA can analyze them. " +
                    "Accepts the package or the display id.",
                schema(
                    "package" to "string: optional clone package",
                    "display" to "integer: optional display id, resolved to its clone"
                )
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
                "Run Ghidra's headless analyzer on a file and return its function list. " +
                    "Defaults to low-memory mode, which skips the decompiler-based " +
                    "analyzers: names, addresses, structure and call graphs are " +
                    "unaffected, but parameter and return types are less precise. Use " +
                    "full mode only for a small binary — on a large one it is what " +
                    "exhausts the device's memory.",
                schema(
                    "distro" to "string: optional distro id",
                    "target" to "string: guest path to a binary, APK, or library",
                    "max" to "integer: max functions to return (default 300)",
                    "mode" to "string: low (default) or full"
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
        "altaris_rea_schema" to
            "One REA tool's real input schema and a minimal example. Call this before " +
            "altaris_rea_call; nested arguments are objects with a \"kind\" field.",
        "altaris_rea_call" to
            "Call any REA tool by name with its arguments.",
        "altaris_rea_clone_apk" to
            "Export a clone's APKs and native libraries for analysis.",
        "altaris_ghidra_analyze" to
            "Run Ghidra headless on a file and return its function list.",
    )

    /**
     * Build a tool definition.
     *
     * A property is required only when its description says neither
     * "optional" nor "default". The `default` half matters: `display` was
     * documented as "integer: display id (default 0, the main screen)" and was
     * therefore advertised as required, so every client that omitted it was
     * rejected by schema validation with `display: Missing key` — for a
     * parameter the tool has a sensible value for. An argument that has a
     * default can be omitted by definition; saying otherwise in the schema is
     * the bug.
     */
    private fun tool(name: String, description: String, properties: JSONObject): JSONObject {
        val required = JSONArray()
        val keys = properties.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = properties.optString(key)
            val omittable = value.contains("optional") ||
                value.contains("default", ignoreCase = true) ||
                value.contains("defaults to", ignoreCase = true)
            if (!omittable) required.put(key)
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
        "altaris_plan" -> {
            val request = args.optString("request").ifBlank {
                throw IllegalArgumentException("request is required")
            }
            val plan = TaskRouter.classify(request)
            textResult(planJson(plan, args.optString("package").takeIf { it.isNotBlank() }))
        }
        "altaris_device_screenshot" -> {
            val ctx = ControlServer.context() ?: throw IllegalStateException("App context unavailable")
            textResult(
                runBlocking {
                    withContext(Dispatchers.IO) {
                        com.altaris.linux.core.system.DeviceScreenshot.capture(
                            ctx,
                            args.optInt("display", 0),
                        )
                    }
                }
            )
        }
        "altaris_doctor" -> {
            val ctx = ControlServer.context() ?: throw IllegalStateException("App context unavailable")
            textResult(
                runBlocking { AltarisDoctor.report(ctx, args.optBoolean("deep", false)) }
            )
        }
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
            runBlocking {
                HeadlessCloneEngine.apiUninstall(
                    resolveClonePackage(args) ?: throw IllegalArgumentException(
                        "package is required: pass the clone's package, or the display id " +
                            "from altaris_sessions."
                    )
                )
            }
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
            runBlocking {
                HeadlessCloneEngine.apiStop(
                    resolveClonePackage(args) ?: throw IllegalArgumentException(
                        "package is required: pass the clone's package, or the display id " +
                            "from altaris_sessions."
                    )
                )
            }
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
        "altaris_logs" -> {
            val pkg = resolveClonePackage(args)
                ?: throw IllegalArgumentException(
                    "package is required: pass the clone's package, or the display id " +
                        "from altaris_sessions. altaris_clones lists the packages."
                )
            textResult(
                runBlocking {
                    HeadlessCloneEngine.apiLogs(pkg, args.optInt("lines", 300))
                }
            )
        }
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
            val pkg = optionalPackage(args) ?: HeadlessCloneEngine.activeClonePackage.value
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
        "altaris_wait" -> {
            val ctx = ControlServer.context() ?: throw IllegalStateException("App context unavailable")
            val until = args.optString("until").takeIf { it.isNotBlank() }
            val pkg = optionalPackage(args)
            val display = if (args.has("display")) {
                args.optInt("display")
            } else {
                HeadlessCloneEngine.displayIdFor(pkg).takeIf { it > 0 }
                    ?: HeadlessCloneEngine.displayId.value
            }
            if (until == null) {
                val ms = args.optInt("ms", 500).coerceIn(0, 15_000).toLong()
                Thread.sleep(ms)
                textResult(JSONObject().put("ok", true).put("waitedMs", ms))
            } else {
                val budget = args.optInt("timeoutMs", 8_000).coerceIn(250, 30_000).toLong()
                val poll = args.optInt("ms", 250).coerceIn(50, 2_000).toLong()
                val started = System.currentTimeMillis()
                var matched: String? = null
                var lastTree: JSONObject? = null
                var failure = ""
                while (System.currentTimeMillis() - started <= budget) {
                    val root = runCatching {
                        a11yObject(A11yBridge.tree(ctx, display, 600, pkg))
                    }.getOrNull()
                    if (root != null) {
                        lastTree = root
                        if (!root.optBoolean("ok", false)) {
                            // "not enabled" or "no display" cannot fix itself
                            // by waiting, so polling for the full budget only
                            // delays the same answer.
                            failure = root.optString("error")
                            break
                        }
                        val nodes = root.optJSONArray("nodes") ?: JSONArray()
                        for (i in 0 until nodes.length()) {
                            val node = nodes.optJSONObject(i) ?: continue
                            val label = node.optString("text")
                            val desc = node.optString("desc")
                            if (label.contains(until, true) || desc.contains(until, true)) {
                                matched = label.ifBlank { desc }
                                break
                            }
                        }
                    }
                    if (matched != null) break
                    Thread.sleep(poll)
                }
                val waited = System.currentTimeMillis() - started
                textResult(
                    JSONObject()
                        .put("ok", matched != null)
                        .put("found", matched != null)
                        .put("until", until)
                        .put("matched", matched ?: "")
                        .put("waitedMs", waited)
                        .put(
                            "error",
                            when {
                                matched != null -> ""
                                failure.isNotBlank() -> failure
                                else ->
                                    "no element matched \"$until\" within ${waited}ms; " +
                                        "read altaris_ui_tree to see what is on screen"
                            }
                        )
                        .put("display", lastTree?.optInt("display", display) ?: display)
                )
            }
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
        "altaris_jadx_bridge_install" -> {
            val ctx = ControlServer.context() ?: throw IllegalStateException("App context unavailable")
            val distro = reaTargetDistro(ctx)
                ?: throw IllegalStateException("no installed distro")
            val lines = StringBuilder()
            val result = runBlocking {
                ReaManager.installJadxBridge(ctx, distro) { lines.append(it).append('\n') }
            }
            // REA reads REA_JADX_MCP_JAR at startup, so a running server has to
            // be restarted before the Android lane works.
            var restarted = false
            if (result.isBlank()) {
                runBlocking { ReaManager.stop() }
                restarted = runBlocking { ReaManager.start(ctx, distro) }.running
            }
            textResult(
                JSONObject()
                    .put("ok", result.isBlank())
                    .put("installed", result.isBlank())
                    .put("restarted", restarted)
                    .put("log", lines.toString().takeLast(4000))
                    .put("error", result)
            )
        }
        "altaris_rea_tools" -> {
            ensureReaRunning() ?: throw IllegalStateException(
                "REA is not running and could not be started. Install it from " +
                    "Tools → Reverse engineering first."
            )
            val index = ReaManager.toolIndex()
            textResult(
                JSONObject()
                    .put("ok", true)
                    .put("count", index.length())
                    .put("tools", index)
                    .put(
                        "note",
                        "An index, not the full catalog. Call altaris_rea_schema " +
                            "with one name to get its real input schema and an example " +
                            "before calling it.",
                    )
            )
        }
        "altaris_rea_schema" -> {
            val name = args.optString("name").takeIf { it.isNotBlank() }
            if (name == null) {
                val index = ReaManager.toolIndex()
                textResult(
                    JSONObject().put("ok", true).put("count", index.length()).put("tools", index)
                )
            } else {
                val tool = ReaManager.toolDefinition(name)
                    ?: throw IllegalArgumentException(
                        "No REA tool named \"" + name + "\". Call altaris_rea_schema " +
                            "with no name to list all of them."
                    )
                val inputSchema = tool.optJSONObject("inputSchema")
                textResult(
                    JSONObject()
                        .put("ok", true)
                        .put("name", name)
                        .put("description", tool.optString("description"))
                        .put("inputSchema", inputSchema ?: JSONObject())
                        .put(
                            "required",
                            inputSchema?.optJSONArray("required") ?: JSONArray()
                        )
                        .put("example", ReaSchema.example(inputSchema) ?: JSONObject())
                        .put(
                            "note",
                            "Pass arguments exactly in this shape. Nested objects are " +
                                "discriminated by a \"kind\" field; a bare string where an " +
                                "object is expected is rejected.",
                        )
                )
            }
        }
        "altaris_rea_call" -> {
            // Accept the shapes an agent actually sends.
            //
            // The reported failure is `altaris_rea_call { name: "inspect_adb_client" }`
            // answered with "name is required" — a message that contradicts the
            // call it is rejecting and leaves nothing to act on. `tool` is the
            // field name REA's own CLI and several clients use, so it is
            // accepted as an alias rather than costing a round trip.
            val tool = args.optString("name").takeIf { it.isNotBlank() }
                ?: args.optString("tool").takeIf { it.isNotBlank() }
                ?: args.optJSONObject("arguments")?.optString("name")?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException(
                    "No REA tool name. Pass it as \"name\": " +
                        "altaris_rea_call { \"name\": \"inspect_artifact\", \"arguments\": {} }. " +
                        "Received keys: " + args.keys().asSequence().toList() +
                        ". Call altaris_rea_tools for the list of names."
                )
            val arguments = args.optJSONObject("arguments") ?: JSONObject()
            val timeout = args.optLong("timeoutMs", 600_000L).coerceIn(5_000L, 3_600_000L)
            // Start REA rather than telling the agent to.
            //
            // "REA is not running. Call altaris_rea_start first" is a dead end
            // for an agent that was told to analyse something: it is one call
            // away, exactly like launching a clone. An explicit start is still
            // available; this only removes the pointless failure.
            ensureReaRunning()
            // Check the arguments against the tool's real schema first.
            //
            // REA's own rejection is one generic sentence, which is why an
            // agent retries the same guess. This names the field and the shape
            // it wanted instead. A call that passes is forwarded unchanged —
            // the validator only ever reports a missing required key, a wrong
            // type, or an unknown top-level key.
            val definition = ReaManager.toolDefinition(tool)
                ?: throw IllegalArgumentException(
                    "REA has no tool named \"" + tool + "\". Call altaris_rea_tools " +
                        "for the list, or altaris_rea_schema for one tool's shape."
                )
            val schema = definition.optJSONObject("inputSchema")
            val problems = ReaSchema.validate(schema, arguments)
            // An expression-bodied function cannot `return`, so the rejection
            // is the value of this branch rather than an early exit.
            if (problems.isNotEmpty()) {
                val details = JSONArray()
                problems.forEach { details.put(it.toString()) }
                textResult(
                    JSONObject()
                        .put("ok", false)
                        .put("error", "Arguments do not match " + tool + "'s input schema.")
                        .put("tool", tool)
                        .put("problems", details)
                        .put("required", schema?.optJSONArray("required") ?: JSONArray())
                        .put("example", ReaSchema.example(schema) ?: JSONObject())
                        .put(
                            "hint",
                            "Call altaris_rea_schema { \"name\": \"" + tool + "\" } for " +
                                "the full shape. A nested object needs a \"kind\" field; " +
                                "it is not a bare string.",
                        )
                )
            } else {
                runBlocking { ReaManager.call(tool, arguments, timeout) }
            }
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
            val pkg = resolveClonePackage(args) ?: throw IllegalArgumentException(
                "package is required: pass the clone's package, or the display id " +
                    "from altaris_sessions."
            )
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
            val mode = args.optString("mode").takeIf { it.isNotBlank() } ?: "low"
            textResult(
                runBlocking { ReaManager.analyzeGhidra(ctx, distro, target, max, mode) }
            )
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

    /**
     * The clone a tool call is about, from either identifier.
     *
     * An agent reads `display_id` from `altaris_sessions` and then has a
     * display id but no package name — and every tool that acts on a clone
     * asked for the package. The reported failure is exactly that:
     *
     *   altaris_logs { "display": 2 }  ->  package: Missing key
     *
     * while `com.altaris.apps.c18c4ecce` was the right answer and already
     * known to the engine. Both identifiers name the same running clone, so
     * both are accepted and the engine does the translation.
     *
     * Returns null when neither identifies a clone, so the caller can say what
     * to do instead of guessing.
     */
    private fun resolveClonePackage(args: JSONObject): String? {
        args.optString("package").takeIf { it.isNotBlank() }?.let { return it }
        if (!args.has("display")) return null
        return HeadlessCloneEngine.packageForDisplay(args.optInt("display", -1))
    }

    /** Optional clone package: null means "the display the viewer is showing". */
    private fun optionalPackage(args: JSONObject): String? =
        args.optString("package").takeIf { it.isNotBlank() }
            ?: if (args.has("display")) {
                HeadlessCloneEngine.packageForDisplay(args.optInt("display", -1))
            } else {
                null
            }

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
        ## Classify the task before you act: altaris_plan

        `altaris_plan { "request": "<the user's request>" }` returns the task
        kind, whether the target is the physical device or a clone, and the
        tool sequence in order. Call it first for anything that is not a
        one-line question, then follow its steps.

        The kind is decided by the **verb and the intended outcome**, never by
        what sort of app the target is. That distinction is the fix for the
        refusals: a request to "open the game and go to the shop" was being
        read as autonomous gameplay and refused, when it is three taps and a
        screenshot.

        - Open an app, move through screens, reach a menu -> **navigation**.
          Accessibility tree and semantic taps. No Frida, no APK analysis.
        - Read what is on screen -> **inspection**. `altaris_ui_tree`, and
          `altaris_device_screenshot` for appearance.
        - Tap a button and confirm the result -> **UI test**. The confirmation
          step is what makes it a test rather than a tap.
        - Inspect an APK, its manifest or its classes -> **APK analysis** (REA).
        - Find the code behind a button -> **reverse engineering** (REA).
        - Observe a value while it runs -> **runtime debugging**.
        - Instrument a method -> **Frida**, and only when logs and static
          analysis cannot answer the question.
        - Test a build in isolation -> **clone testing**.

        A game is an app. Playing a level when the user asks is navigation.

        ## You have a device. It is this one.

        You are running inside Altaris on an Android device, and you can drive
        it. Never tell the user "no device is connected" or ask them to plug one
        in: there is no USB cable and no adb server, and there does not need to
        be. The tools below act on the phone you are already on.

        Every app you can reach is a clone — an isolated copy with its own
        package name, storage and virtual display. That is the sandbox you test
        in. You can install, launch, drive, screenshot and stop a clone
        yourself, with no user action.

        There is no folder to search. Do not look in /sdcard, /data or anywhere
        else for an app: `altaris_clones` lists the clones, `altaris_apps` lists
        the installed apps you can clone, and `altaris_launch_clone` starts one.

        ### Before you say something is impossible

        1. `altaris_plan` — what kind of task is this, and which tools answer it.
        2. `altaris_doctor` — what can this build actually do right now, and why
           not when something is missing.
        3. Then act on the tools that are available.

        Do not claim the physical device is unreachable: `altaris_ui_tree` reads
        its accessibility hierarchy, and `altaris_device_screenshot` captures its
        screen. Do not require Frida for navigation. Do not require adb for
        anything Shizuku already does. Do not stop an investigation because one
        optional backend is missing — continue with UI, logs and static analysis,
        and say which part you could not do and why.

        A missing capability is not a failed task. It is one sentence in the
        report.

        ### The test loop

        When the user gives you an APK or asks you to check something an app
        does, this is the whole cycle. Do it yourself; do not ask for help.

        1. `altaris_install_apk { "path": "/sdcard/Download/foo.apk" }` — install
           it as a clone. If it is already installed, `altaris_clones` shows it
           and you skip this step.
        2. `altaris_launch_clone { "package": "<pkg>" }` — start it on its own
           virtual display. "Not running" is never a reason to stop.
        3. Drive and observe it: `altaris_screenshot`, `altaris_ui_tree`,
           `altaris_tap`, `altaris_swipe`, `altaris_text`, `altaris_ui_tap_text`,
           `altaris_logs`. These are real input and real output on the running
           clone, not a simulation.
        4. `altaris_stop_clone { "package": "<pkg>" }` — stop it when you are
           done testing. Release the display; do not leave it running.

        ### The clone is not an adb device

        A clone is an isolated copy running on this phone. It has **no adb
        serial**, so REA's device tools cannot see it:

            read_adb_logcat { "serial": "RZCT409JL8T" }
            -> unsupported_target: adb: device 'RZCT409JL8T' not found

        That serial is the *phone*, not the clone, and there is no adb server
        here. The clone's logcat is `altaris_logs`, which reads it through the
        clone engine and needs no adb at all. Reach for `altaris_logs`,
        `altaris_ui_tree`, `altaris_screenshot` and `altaris_debug_attach` for
        anything about a clone; use REA for the artifact on disk. Calling
        `list_adb_devices`, then `inspect_adb_client`, costs three round trips
        to learn what this paragraph says.

        Both identifiers work: pass `package`, or pass the `display` you read
        from `altaris_sessions`. Do not try to join the two lists by hand.

        ### Clean up after yourself

        A clone is a full installed app. Leaving one behind costs the user disk
        and a launcher entry they did not ask for, so the loop has a last step:

        - Finished with a clone you installed for this task? Remove it with
          `altaris_uninstall_clone { "package": "<pkg>" }`.
        - A clone the **user** already had is theirs. Stop it if you started it,
          but do not uninstall it unless they ask.
        - Keep at most what you are actively testing. If you installed several
          builds while iterating, uninstall the older ones.

        State the package you installed, what you saw, and that you stopped or
        removed it. Do not silently leave clones running.

        ## Reverse engineering is REA's job, not yours

        For ANY question about a binary, APK, DEX, native library, firmware
        image, archive, game asset, or what code does at runtime, use REA. Do
        not reason about the file from its name, do not read bytes yourself, do
        not guess, and do not shell out to objdump/strings/readelf when REA has
        a tool for it.

        Start here, every time:

        1. `altaris_rea_status` — is REA installed and running.
        2. `altaris_rea_tools` — the index of REA's ~229 tools: name, one-line
           summary, required arguments. Find the one that matches the question.
        3. `altaris_rea_schema { "name": "<tool>" }` — that tool's **real input
           schema** and a minimal example. Do this before the first call to any
           tool you have not already used in this session.
        4. `altaris_rea_call { "name": "<tool>", "arguments": { ... } }` — with
           arguments in exactly the shape the schema showed.
        5. `altaris_rea_clone_apk` — export a clone's APKs and .so files, which
           returns the paths to analyse.
        6. `altaris_debug_attach` — for runtime behaviour (what a method
           returns, what an address holds, what the process maps).

        These calls start REA themselves; you do not need to start it first.
        If `altaris_rea_status` says it is not installed, say so and stop —
        do not fall back to reading the file by hand.

        ### Runtime work: use the debugger tools, not the frida CLI

        Do not shell out to `frida`, `frida-ps` or `frida-trace`. Those are
        REA's own tools' job and the debugger's job, and a hand-rolled command
        fails in a way that looks like a broken install:

            frida-ps --remote 192.168.145.1:27042
            -> frida-ps: error: unrecognized arguments: 192.168.145.1:27042

            frida -H localhost com.example.app /tmp/script.js
            -> Failed to spawn: unable to connect to remote frida-server

        Both are wrong in the same way. `--remote` is not a frida-ps flag (it
        is `-H host:port`), and the server is on **127.0.0.1:27042**, not on
        the proot gateway address. The Frida CLI also lives *inside the
        distro*, not on the host, so a host command finds nothing.

        Use `altaris_debug_attach { "target": "<pkg or pid>" }` — it starts
        the clone, starts frida-server if needed, and attaches. Then
        `altaris_debug_command` with a `$` prefix for JavaScript:

            altaris_debug_command { "command": "$ Process.arch" }

        Anything without the `$` is a gdb command. `altaris_debug_detach`
        ends the session. If you need REA's own Frida tools, they are on the
        `rea` server under their real names — `list_frida_devices`,
        `start_frida_session` — and they reach the same server.

        ### Two servers, two namespaces

        `altaris` (this one) has the `altaris_*` tools: clones, UI, screenshots,
        the debugger and the REA lifecycle. `rea` is a separate server with
        REA's own ~229 tools under their **bare names** — `open_binary`,
        `inspect_artifact`, `search_strings`, `inspect_analysis_view`.

        Do not call an `altaris_*` tool on the `rea` server or the reverse: the
        name will not exist and the round trip is wasted. If you are on `rea`,
        read `rea://guide`. If you are here, use `altaris_rea_call`.

        REA's MCP tool names use **underscores**. The dashed form
        (`inspect-artifact`) is REA's CLI and no MCP client accepts it. If a name
        is rejected as unknown, retry it with underscores before concluding the
        capability is missing. There is no `rea_tools`, `list_tools` or `help`
        tool on the `rea` server: its catalog *is* `tools/list`.

        ### REA's arguments are not strings

        The mistake that wastes the most calls: a nested argument is an
        **object discriminated by a `kind` field**, not a name. If you write

            inspect_analysis_view { "source": "jadx", "view": "decompiled" }

        you get `source: expected object, view: expected object`, because that
        is not the shape. The real one is

            inspect_analysis_view {
              "source": { "kind": "retained-evidence", "evidence_id": "ev_..." },
              "view":   { "kind": "summary" }
            }

        Read `altaris_rea_schema` first and copy its example. Every REA tool
        rejects unknown keys, so do not add fields the schema does not list.

        ### Evidence ids: use the parent, not the receipt

        A summary reply carries **two** ids and they are not interchangeable.
        Verified against REA 6.8.0:

            "evidence_id"                     <- the summary receipt itself
            "normalized_result": {
              "parent_evidence_id": "ev_..."  <- the record you can page
            }

        `inspect_analysis_view` rejects the receipt id with
        `unsupported_target` ("Parent Evidence operation inspect_analysis_view
        does not support analysis views"), so always use
        `normalized_result.parent_evidence_id`.

        An `ev_...` id is also only valid for the REA session that produced it.
        Never reuse one from earlier, from another tool, or from a previous run.

        If REA answers `evidence_integrity_mismatch`, the id is stale or
        belongs to another source. Do not retry it. Re-run the tool that
        produced the evidence and take the parent id from its fresh reply, or
        pass the Evidence inline through
        `source: { "kind": "inline", "evidence": ... }`.

        ### When a call is rejected

        `altaris_rea_call` checks your arguments against the tool's real schema
        and names the field it objected to. Read that, fix that field, and call
        once more. Do not repeat an unchanged call: REA's own message is generic
        and will not change either.

        If an REA Android tool answers `capability_unavailable` naming
        REA_JADX_MCP_JAR, call `altaris_jadx_bridge_install`. REA does not ship
        the jadx bridge and will not fetch it, so that whole lane — manifest,
        DEX classes, decompiled Java, resources — is blocked until it is
        installed. The tool downloads the pinned, checksum-verified release and
        restarts REA so it reads the variable. Do not try to install it by hand
        and do not guess at tracker names when it is missing.

        REA calls can take minutes: a cold Ghidra import plus decompile of a
        23 MB binary legitimately runs for several minutes. Call it, wait for
        the result, and do not assume it failed because it is slow. Do not
        retry the same call while one is still running.

        ## Ghidra: low-memory mode is the default, and the reason matters

        `altaris_ghidra_analyze` defaults to `"mode": "low"`. That is not a
        limitation to work around — it is what keeps the device alive.

        Low mode disables Decompiler Parameter ID and the other analyzers whose
        working set is many times the binary. On a phone, running them took the
        JVM heap to its ceiling and the kernel OOM killer took the app and the
        launcher with it: the whole device froze. Names, addresses, sections,
        symbols and the call graph are unaffected. What is less precise is
        parameter and return types.

        Use low mode. Ask for `"mode": "full"` only on a small binary, and only
        when parameter-level detail is the actual question.

        The wrapper refuses rather than risks the device: it sizes the heap from
        MemAvailable, holds 768 MB back for Android, caps at 2 GB, and returns
        an error if the target is more than half the heap. If it refuses, the
        answer is "close other apps" or "analyse a smaller file" — not to retry
        the same call. Two Ghidra analyses cannot run at once; the second is
        refused with an explicit message.

        ## Configuration updates itself

        The MCP registration, the skill, the guide and the Ghidra/jadx pins are
        re-applied automatically when their version changes — you do not need to
        ask the user to reinstall REA after an app update, and you should not
        edit the agent config files yourself.

        ## Working the UI

        `altaris_ui_tree` returns the real view hierarchy — text, resource ids,
        bounds, clickability — in one call, and `altaris_ui_click` /
        `altaris_ui_type` / `altaris_ui_scroll` act on its nodes. That is the
        fast path. `altaris_ocr` and `altaris_screenshot` are the fallback for
        a canvas, a game, or a view with no nodes; they cost a capture and a
        recognition per step. `altaris_tap` / `altaris_swipe` / `altaris_key`
        take raw coordinates and are the last resort. When a step fails,
        re-read the tree instead of guessing a coordinate.

        ### Waiting: use `altaris_wait`, never a timer

        The environment that runs your tool calls has **no `setTimeout`, no
        `sleep`, no `delay` and no `Promise` timer**. Referencing one throws
        `ReferenceError: Unknown identifier 'setTimeout'` and the whole call
        fails before the tool is invoked — so the tap never happens, and it
        looks like a tool problem when it is not.

        Use the tool instead:

            altaris_wait { "ms": 800 }             // fixed pause
            altaris_wait { "until": "Settings" }   // poll until it appears

        The second form is what to use after a tap: it waits until the element
        you expect is on screen and returns `found: false` with a next step
        when it never appears. A fixed sleep cannot tell you that.

        ### One tool call per step, not a program

        The code body is **one expression, with no `await`, no timer and no
        tag**. Call the tool, read its reply, then decide the next call.

        Three wrappers fail, and each is the wrapper's fault, not the tool's:

        - **A closing tag or fence inside the body.** The harness answers
          `SyntaxError: Unterminated regular expression. Code must be plain
          JavaScript written as a program body.` Do not emit `</result_code>`,
          an opening tag, or a fence. A `<` reads as a comparison or a regex
          and the parser cannot recover.
        - **Top-level `await`.** The body is a plain program, so `await` is a
          syntax error. It is also unnecessary: a call returns its value
          directly. A bare call is the correct form, and `JSON.stringify(...)`
          around it is the only wrapper worth adding.
        - **`setTimeout`, `sleep` or a `Promise` timer.** There are none. Use
          `altaris_wait`.

        Every reply is shaped so one call is enough — `lines` is always an
        array, `found` is always present, and a failure carries `error` without
        changing the shape. Nothing needs a defensive wrapper.

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
        // Apply the current agent configuration before the server comes up.
        // This is the path an agent's first call takes, so it is the one place
        // that guarantees an app update reaches a distro the user installed
        // months ago. It is a single `cat` when the stamp already matches.
        runCatching { runBlocking { ReaManager.ensureAgentConfig(ctx, distro) } }
        return runCatching { runBlocking { ReaManager.start(ctx, distro) } }.getOrNull()
            ?.takeIf { it.running }
    }

    /**
     * A [TaskRouter.Plan] as the agent reads it.
     *
     * The wording matters as much as the fields. `refused` is always false
     * unless no tool covers the request, and the guidance says so explicitly —
     * the refusals this replaces were not "the tool is missing", they were
     * "the agent decided the request was something else".
     */
    private fun planJson(plan: TaskRouter.Plan, overridePackage: String?): JSONObject {
        val steps = JSONArray()
        plan.steps.forEachIndexed { index, step ->
            steps.put(
                JSONObject()
                    .put("order", index + 1)
                    .put("tool", step.tool)
                    .put("why", step.why)
                    .put("optional", step.optional)
            )
        }
        val signals = JSONArray()
        plan.signals.forEach { signals.put(it) }
        val degraded = JSONArray()
        plan.degradedBy.forEach { degraded.put(it) }
        return JSONObject()
            .put("ok", true)
            .put("task", plan.kind.label)
            .put("target", plan.target.label)
            .put("package", overridePackage ?: plan.packageName.orEmpty())
            .put("steps", steps)
            .put("signals", signals)
            .put("refused", plan.blocked)
            .put("reason", plan.blockedReason)
            .put(
                "guidance",
                if (plan.blocked) {
                    plan.blockedReason
                } else {
                    plan.kind.rule + " Follow the steps in order; skip a step marked " +
                        "optional if its capability is missing and continue with the rest."
                },
            )
            .put(
                "canProceed",
                !plan.blocked,
            )
            .put("degradedBy", degraded)
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
            .put("configVersion", ReaManager.CONFIG_VERSION)
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
