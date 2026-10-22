package com.altaris.linux.core.a11y

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.altaris.linux.core.clone.HeadlessCloneEngine
import com.altaris.linux.core.root.RootShell
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * App-side client for the Altaris accessibility service.
 *
 * The service runs in the `:a11y` process and is reached through
 * [AltarisA11yProvider] with a synchronous `ContentResolver.call`. Every call
 * runs on a worker thread with a timeout guard and returns a JSON string;
 * failures never throw.
 *
 * All functions block — call them from a background thread.
 */
object A11yBridge {

    private const val TAG = "AltarisA11yBridge"

    /** Hard limit for one provider round trip. */
    private const val TIMEOUT_MS = 6_000L

    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "altaris-a11y-call").apply { isDaemon = true }
    }

    init {
        // The clone engine lives in the app process; expose its input path so
        // a service running in the same process can fall back to a tap.
        A11yEngineFallback.tap = { x, y -> engineTap(x, y) }
    }

    // ---------------------------------------------------------------- API

    /** Service status JSON: `{"connected":true,"serviceRunning":true,"windows":n}`. */
    fun status(context: Context): String = call(context, "status", null, null)

    /**
     * UI tree JSON of [displayId]:
     * `{"ok":true,"display":id,"source":"accessibility","nodes":[...]}`.
     */
    fun tree(
        context: Context,
        displayId: Int,
        maxNodes: Int = 600,
        packageName: String? = null,
    ): String =
        call(
            context,
            "tree",
            displayId.toString(),
            Bundle().apply {
                putInt("max", maxNodes.coerceIn(1, 2000))
                packageName?.takeIf { it.isNotBlank() }?.let { putString("package", it) }
            },
        )

    /**
     * Framework display id of the window owned by [packageName], or -1.
     * The clone engine numbers displays on its own; the accessibility service
     * reports the framework id, which gestures have to use.
     */
    fun displayIdFor(context: Context, packageName: String): Int {
        val raw = call(
            context,
            "display",
            null,
            Bundle().apply { putString("package", packageName) },
        )
        return runCatching { JSONObject(raw).optInt("display", -1) }.getOrDefault(-1)
    }

    /**
     * Click a node. Falls back to an engine tap at the node centre when the
     * node itself is not clickable.
     */
    fun click(context: Context, nodeId: String): String {
        val raw = call(context, "click", nodeId.toString(), null)
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return raw
        if (obj.optBoolean("ok", false)) return raw
        if (obj.optString("reason") != "not_clickable") return raw
        val bounds = obj.optJSONArray("bounds") ?: return raw
        val values = IntArray(4) { bounds.optInt(it) }
        val cx = (values[0] + values[2]) / 2
        val cy = (values[1] + values[3]) / 2
        if (!engineTap(cx, cy)) return raw
        return JSONObject()
            .put("ok", true)
            .put("nodeId", nodeId)
            .put("method", "engine_tap")
            .put("x", cx)
            .put("y", cy)
            .toString()
    }

    /** Type [text] into an editable node. */
    fun setText(context: Context, nodeId: String, text: String): String =
        call(
            context,
            "text",
            nodeId.toString(),
            Bundle().apply { putString("text", text) },
        )

    /** Scroll a node forward (true) or backward (false). */
    fun scroll(context: Context, nodeId: String, forward: Boolean): String =
        call(
            context,
            "scroll",
            nodeId.toString(),
            Bundle().apply { putBoolean("forward", forward) },
        )

    /** Dispatch a swipe gesture on [displayId]. */
    fun gesture(
        context: Context,
        displayId: Int,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        ms: Long,
    ): String = call(
        context,
        "gesture",
        null,
        Bundle().apply {
            putInt("display", displayId)
            putFloat("x1", x1)
            putFloat("y1", y1)
            putFloat("x2", x2)
            putFloat("y2", y2)
            putLong("ms", ms)
        },
    )

    /**
     * Tap the first element whose text or content description matches [text].
     * Matching is case-insensitive and partial unless [exact] is set.
     */
    fun tapText(context: Context, displayId: Int, text: String, exact: Boolean = false): String {
        val needle = text.trim()
        if (needle.isEmpty()) return errorJson("text is required")
        val tree = tree(context, displayId, 600)
        val root = runCatching { JSONObject(tree) }.getOrNull()
            ?: return errorJson("tree reply was not valid JSON")
        if (!root.optBoolean("ok", false)) return tree
        val nodes = root.optJSONArray("nodes") ?: JSONArray()
        for (i in 0 until nodes.length()) {
            val node = nodes.optJSONObject(i) ?: continue
            val label = node.optString("text")
            val desc = node.optString("desc")
            val hit = when {
                exact -> label.equals(needle, true) || desc.equals(needle, true)
                else -> label.contains(needle, true) || desc.contains(needle, true)
            }
            val nodeId = node.optString("id")
            if (!hit || nodeId.isBlank()) continue
            val clickResult = runCatching { JSONObject(click(context, nodeId)) }.getOrNull()
            val ok = clickResult?.optBoolean("ok", false) ?: false
            return JSONObject()
                .put("ok", ok)
                .put("nodeId", nodeId)
                .put("matched", label.ifBlank { desc })
                .put("text", needle)
                .apply {
                    if (!ok) {
                        put("error", clickResult?.optString("error").orEmpty().ifBlank { "click failed" })
                    }
                }
                .toString()
        }
        return errorJson("no element matched \"$needle\"")
    }

    /**
     * Enable the Altaris accessibility service with root (KernelSU / Magisk).
     *
     * Writes `enabled_accessibility_services` and `accessibility_enabled` for
     * the running application id, then verifies the setting. Returns JSON.
     */
    fun ensureEnabled(context: Context): String {
        val packageName = context.packageName
        val component = AltarisAccessibilityService.componentFor(packageName)
        return try {
            if (!RootShell.isRootAvailable()) {
                return errorJson("root not available — enable the service from Android settings instead")
            }
            val current = readSetting(SETTING_SERVICES)
            val list = current.split(':')
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != "null" }
                .toMutableList()
            if (!list.contains(component)) list.add(component)
            val value = list.joinToString(":")
            val put = RootShell.captureResult("settings put secure $SETTING_SERVICES '$value'", 8_000L)
            val on = RootShell.captureResult("settings put secure $SETTING_ENABLED 1", 8_000L)
            if (put.exitCode != 0 || on.exitCode != 0) {
                return errorJson(
                    "could not enable the accessibility service (exit ${put.exitCode}/${on.exitCode})",
                )
            }
            val after = readSetting(SETTING_SERVICES)
            if (!after.contains(component)) {
                return errorJson("service disabled by system — the setting did not stick")
            }
            JSONObject()
                .put("ok", true)
                .put("component", component)
                .put("accessibility_enabled", readSetting(SETTING_ENABLED) == "1")
                .put("enabled_services", after)
                .toString()
        } catch (t: Throwable) {
            Log.w(TAG, "ensureEnabled failed", t)
            errorJson(t.message ?: t.javaClass.simpleName)
        }
    }

    // ---------------------------------------------------------------- internals

    /** Runs one provider call with a timeout guard. */
    private fun call(context: Context, method: String, arg: String?, extras: Bundle?): String {
        return try {
            val future = executor.submit<String> {
                val uri = Uri.parse("content://${context.packageName}.a11y")
                val reply = context.contentResolver.call(uri, method, arg, extras)
                reply?.getString("json") ?: error("accessibility service returned no data")
            }
            future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            Log.w(TAG, "call $method failed: ${t.message}")
            errorJson(t.message ?: t.javaClass.simpleName)
        }
    }

    /** Tap through the clone engine input path (app process only). */
    private fun engineTap(x: Int, y: Int): Boolean {
        if (!HeadlessCloneEngine.connected.value || !HeadlessCloneEngine.displayReady.value) return false
        HeadlessCloneEngine.tap(x, y)
        return true
    }

    private fun readSetting(key: String): String =
        RootShell.capture("settings get secure $key", 5_000L)
            .lineSequence()
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() }
            .orEmpty()

    private fun errorJson(message: String): String =
        JSONObject().put("ok", false).put("error", message).toString()

    private const val SETTING_SERVICES = "enabled_accessibility_services"
    private const val SETTING_ENABLED = "accessibility_enabled"
}
