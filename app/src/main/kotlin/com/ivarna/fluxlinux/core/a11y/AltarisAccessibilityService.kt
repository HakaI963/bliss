package com.ivarna.fluxlinux.core.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Altaris accessibility service — the real UI tree of the virtual display that
 * hosts the running clone.
 *
 * The service lives in its own `:a11y` process: enabling or disabling an
 * accessibility service restarts its host process, and that must never kill
 * the main Altaris process. The app talks to it synchronously through
 * [AltarisA11yProvider], which lives in the same process.
 *
 * Every entry point is safe to call from a binder thread and never throws:
 * failures come back as a JSON error payload.
 */
class AltarisAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AltarisA11y"

        /** Max tree depth walked for a snapshot. */
        private const val MAX_DEPTH = 40

        /** Fully qualified class name of the service (source package is stable). */
        const val CLASS_NAME = "com.ivarna.fluxlinux.core.a11y.AltarisAccessibilityService"

        /** Running instance, or null while the system has not bound the service. */
        @Volatile
        var instance: AltarisAccessibilityService? = null

        /** Component string used by the settings provider: applicationId + class. */
        fun componentFor(packageName: String): String = "$packageName/$CLASS_NAME"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val info = serviceInfo ?: AccessibilityServiceInfo()
        // Without FLAG_RETRIEVE_INTERACTIVE_WINDOWS getWindows*() stays empty.
        info.flags = info.flags or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        serviceInfo = info
        Log.i(TAG, "accessibility service connected")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    // ---------------------------------------------------------------- API

    /**
     * JSON status of the service: `{"connected":true,"serviceRunning":true,"windows":n}`.
     */
    fun status(): String = runCatching {
        JSONObject()
            .put("connected", true)
            .put("serviceRunning", true)
            .put("windows", windowsFor(-1).size)
            .toString()
    }.getOrElse { errorJson("status failed: ${it.message}") }

    /**
     * JSON snapshot of the UI tree of [displayId].
     *
     * Shape: `{"ok":true,"display":id,"source":"accessibility","windows":n,"nodes":[...]}`.
     */
    fun snapshot(displayId: Int, maxNodes: Int = 600): String = runCatching {
        val limit = maxNodes.coerceIn(1, 2000)
        var windows = windowsFor(displayId)
        // The engine display id can lag behind the framework id; fall back to
        // every display so a running clone is never invisible.
        if (windows.isEmpty() && displayId >= 0) windows = windowsFor(-1)
        if (windows.isEmpty()) {
            return@runCatching JSONObject()
                .put("ok", true)
                .put("display", displayId)
                .put("source", "accessibility")
                .put("windows", 0)
                .put("nodes", JSONArray())
                .toString()
        }
        val root = pickAppWindow(windows)?.root
        JSONObject()
            .put("ok", true)
            .put("display", displayId)
            .put("source", "accessibility")
            .put("windows", windows.size)
            .put("package", root?.packageName?.toString().orEmpty())
            .put("nodes", if (root == null) JSONArray() else walk(root, limit))
            .toString()
    }.getOrElse { errorJson("snapshot failed: ${it.message}") }

    /** Click the node with [nodeId] (ids are only valid inside one snapshot). */
    fun click(nodeId: Long): Boolean =
        runCatching { performClick(nodeId).optBoolean("ok", false) }.getOrDefault(false)

    /** Detailed click result (JSON) used by the app-side bridge. */
    fun clickDetailed(nodeId: Long): String =
        runCatching { performClick(nodeId).toString() }.getOrElse { errorJson("click failed: ${it.message}") }

    /** Set the text of an editable node. */
    fun setText(nodeId: Long, text: String): Boolean =
        runCatching { performSetText(nodeId, text).optBoolean("ok", false) }.getOrDefault(false)

    /** Detailed set-text result (JSON) used by the app-side bridge. */
    fun setTextDetailed(nodeId: Long, text: String): String =
        runCatching { performSetText(nodeId, text).toString() }
            .getOrElse { errorJson("set text failed: ${it.message}") }

    /** Scroll a node forward or backward. */
    fun scroll(nodeId: Long, forward: Boolean): Boolean =
        runCatching { performScroll(nodeId, forward).optBoolean("ok", false) }.getOrDefault(false)

    /** Detailed scroll result (JSON) used by the app-side bridge. */
    fun scrollDetailed(nodeId: Long, forward: Boolean): String =
        runCatching { performScroll(nodeId, forward).toString() }
            .getOrElse { errorJson("scroll failed: ${it.message}") }

    /** Dispatch a single stroke gesture on [displayId]. */
    fun gesture(displayId: Int, x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean =
        runCatching { performGesture(displayId, x1, y1, x2, y2, ms) }.getOrDefault(false)

    /** Detailed gesture result (JSON) used by the app-side bridge. */
    fun gestureDetailed(
        displayId: Int,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        ms: Long,
    ): String = runCatching {
        val ok = performGesture(displayId, x1, y1, x2, y2, ms)
        JSONObject()
            .put("ok", ok)
            .put("display", displayId)
            .put("x1", x1).put("y1", y1).put("x2", x2).put("y2", y2).put("ms", ms)
            .apply { if (!ok) put("error", "gesture was not completed") }
            .toString()
    }.getOrElse { errorJson("gesture failed: ${it.message}") }

    // ---------------------------------------------------------------- actions

    private fun performClick(nodeId: Long): JSONObject {
        val node = resolveNode(nodeId)
            ?: return JSONObject()
                .put("ok", false)
                .put("nodeId", nodeId)
                .put("error", "node $nodeId not found — the tree changed, read it again")
        val performed = runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }.getOrDefault(false)
        if (performed) {
            return JSONObject().put("ok", true).put("nodeId", nodeId).put("method", "action_click")
        }
        val bounds = runCatching { node.getBoundsInScreen() }.getOrNull()
        val payload = JSONObject()
            .put("ok", false)
            .put("nodeId", nodeId)
            .put("reason", "not_clickable")
            .put("error", "node is not clickable")
        if (bounds != null) {
            payload.put(
                "bounds",
                JSONArray().put(bounds.left).put(bounds.top).put(bounds.right).put(bounds.bottom),
            )
            // The clone engine input path lives in the app process; it is only
            // reachable here when the service runs in that same process.
            val fallback = A11yEngineFallback.tap
            if (fallback != null) {
                val cx = (bounds.left + bounds.right) / 2
                val cy = (bounds.top + bounds.bottom) / 2
                val tapped = runCatching { fallback(cx, cy) }.getOrDefault(false)
                if (tapped) {
                    return JSONObject()
                        .put("ok", true)
                        .put("nodeId", nodeId)
                        .put("method", "engine_tap")
                        .put("x", cx)
                        .put("y", cy)
                }
            }
        }
        return payload
    }

    private fun performSetText(nodeId: Long, text: String): JSONObject {
        val node = resolveNode(nodeId)
            ?: return JSONObject()
                .put("ok", false)
                .put("nodeId", nodeId)
                .put("error", "node $nodeId not found — the tree changed, read it again")
        if (!node.isEditable) {
            return JSONObject()
                .put("ok", false)
                .put("nodeId", nodeId)
                .put("reason", "not_editable")
                .put("error", "node is not editable")
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val done = runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }.getOrDefault(false)
        return JSONObject()
            .put("ok", done)
            .put("nodeId", nodeId)
            .put("text", text)
            .apply { if (!done) put("error", "set-text action was rejected") }
    }

    private fun performScroll(nodeId: Long, forward: Boolean): JSONObject {
        val node = resolveNode(nodeId)
            ?: return JSONObject()
                .put("ok", false)
                .put("nodeId", nodeId)
                .put("error", "node $nodeId not found — the tree changed, read it again")
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        val done = runCatching { node.performAction(action) }.getOrDefault(false)
        return JSONObject()
            .put("ok", done)
            .put("nodeId", nodeId)
            .put("forward", forward)
            .apply { if (!done) put("error", "scroll action was rejected") }
    }

    private fun performGesture(
        displayId: Int,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        ms: Long,
    ): Boolean {
        val duration = ms.coerceIn(1L, 5_000L)
        val latch = CountDownLatch(1)
        var completed = false
        Handler(Looper.getMainLooper()).post {
            try {
                val path = Path().apply {
                    moveTo(x1, y1)
                    // A zero length stroke is rejected by the framework.
                    if (x1 == x2 && y1 == y2) lineTo(x1 + 1f, y1) else lineTo(x2, y2)
                }
                val stroke = GestureDescription.StrokeDescription(path, 0, duration)
                val builder = GestureDescription.Builder().addStroke(stroke)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    builder.setDisplayId(displayId)
                }
                dispatchGesture(
                    builder.build(),
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            completed = true
                            latch.countDown()
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            completed = false
                            latch.countDown()
                        }
                    },
                    null,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "gesture failed: ${t.message}")
                latch.countDown()
            }
        }
        latch.await(3, TimeUnit.SECONDS)
        return completed
    }

    // ---------------------------------------------------------------- tree

    private fun windowsFor(displayId: Int): List<WindowRef> {
        val out = ArrayList<WindowRef>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val all = runCatching { getWindowsOnAllDisplays() }.getOrNull()
            if (all != null) {
                for (i in 0 until all.size()) {
                    val id = all.keyAt(i)
                    if (displayId >= 0 && id != displayId) continue
                    val list = runCatching { all.valueAt(i) }.getOrNull() ?: continue
                    list.forEach { out.add(WindowRef(id, it)) }
                }
            }
        } else {
            runCatching { getWindows() }.getOrNull()?.forEach {
                out.add(WindowRef(Display.DEFAULT_DISPLAY, it))
            }
        }
        return out.filter { isUsableWindow(it.window) }
    }

    private fun isUsableWindow(window: AccessibilityWindowInfo): Boolean {
        val type = runCatching { window.type }.getOrNull() ?: return false
        if (type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) return false
        if (type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return false
        return true
    }

    /** Prefer the focused application window, then the largest visible one. */
    private fun pickAppWindow(windows: List<WindowRef>): AccessibilityWindowInfo? {
        if (windows.isEmpty()) return null
        val apps = windows.filter { it.window.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val pool = apps.ifEmpty { windows }
        pool.firstOrNull { runCatching { it.window.isFocused }.getOrDefault(false) }
            ?.let { return it.window }
        return pool.maxByOrNull { ref ->
            val root = runCatching { ref.window.root }.getOrNull() ?: return@maxByOrNull 0
            val bounds = runCatching { root.getBoundsInScreen() }.getOrNull() ?: return@maxByOrNull 0
            bounds.width() * bounds.height()
        }?.window
    }

    /** Breadth first walk so parents are always listed before their children. */
    private fun walk(root: AccessibilityNodeInfo, maxNodes: Int): JSONArray {
        val out = JSONArray()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.addLast(root to 0)
        while (queue.isNotEmpty() && out.length() < maxNodes) {
            val (node, depth) = queue.removeFirst()
            out.put(nodeJson(node, depth))
            if (depth >= MAX_DEPTH) continue
            val count = runCatching { node.childCount }.getOrDefault(0)
            for (i in 0 until count) {
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                queue.addLast(child to depth + 1)
            }
        }
        return out
    }

    private fun nodeJson(node: AccessibilityNodeInfo, depth: Int): JSONObject {
        val screen = runCatching { node.getBoundsInScreen() }.getOrNull()
        val window = runCatching { node.getBoundsInWindow() }.getOrNull()
        return JSONObject()
            .put("id", node.getId().toLong())
            .put("class", node.className?.toString().orEmpty())
            .put("text", node.text?.toString().orEmpty())
            .put("desc", node.contentDescription?.toString().orEmpty())
            .put("package", node.packageName?.toString().orEmpty())
            .put("bounds", rectJson(screen))
            .put("winBounds", rectJson(window))
            .put("clickable", runCatching { node.isClickable }.getOrDefault(false))
            .put("editable", runCatching { node.isEditable }.getOrDefault(false))
            .put("scrollable", runCatching { node.isScrollable }.getOrDefault(false))
            .put("focusable", runCatching { node.isFocusable }.getOrDefault(false))
            .put("enabled", runCatching { node.isEnabled }.getOrDefault(false))
            .put("visible", runCatching { node.isVisibleToUser }.getOrDefault(false))
            .put("checked", runCatching { node.isChecked }.getOrDefault(false))
            .put("actions", actionIds(node))
            .put("childCount", runCatching { node.childCount }.getOrDefault(0))
            .put("depth", depth)
    }

    private fun rectJson(rect: android.graphics.Rect?): JSONArray {
        if (rect == null) return JSONArray()
        return JSONArray().put(rect.left).put(rect.top).put(rect.right).put(rect.bottom)
    }

    private fun actionIds(node: AccessibilityNodeInfo): JSONArray {
        val ids = JSONArray()
        val declared: List<Int> = runCatching {
            node.getActions().map { action -> action.id }
        }.getOrDefault(emptyList())
        declared.forEach { ids.put(it) }
        if (declared.isEmpty()) {
            // Fall back to the flags when the app did not publish actions.
            if (runCatching { node.isClickable }.getOrDefault(false)) {
                ids.put(AccessibilityNodeInfo.ACTION_CLICK)
            }
            if (runCatching { node.isEditable }.getOrDefault(false)) {
                ids.put(AccessibilityNodeInfo.ACTION_SET_TEXT)
            }
            if (runCatching { node.isScrollable }.getOrDefault(false)) {
                ids.put(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                ids.put(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            }
            if (runCatching { node.isFocusable }.getOrDefault(false)) {
                ids.put(AccessibilityNodeInfo.ACTION_FOCUS)
            }
        }
        return ids
    }

    /** Node ids are only valid inside one snapshot, so always re-resolve them. */
    private fun resolveNode(nodeId: Long): AccessibilityNodeInfo? {
        val windows = windowsFor(-1)
        val root = pickAppWindow(windows)?.root ?: return null
        return findById(root, nodeId, 0)
    }

    private fun findById(node: AccessibilityNodeInfo, nodeId: Long, depth: Int): AccessibilityNodeInfo? {
        if (depth > MAX_DEPTH) return null
        if (runCatching { node.getId().toLong() }.getOrDefault(-1L) == nodeId) return node
        val count = runCatching { node.childCount }.getOrDefault(0)
        for (i in 0 until count) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            val found = findById(child, nodeId, depth + 1)
            if (found != null) return found
        }
        return null
    }

    private fun errorJson(message: String): String =
        JSONObject().put("ok", false).put("error", message).toString()

    private data class WindowRef(val displayId: Int, val window: AccessibilityWindowInfo)
}

/**
 * Optional tap fallback for the accessibility service.
 *
 * The clone engine and its input injection live in the app process while the
 * service runs in `:a11y`, so the hook stays null inside the service process.
 * The app-side bridge ([A11yBridge]) performs the same fallback tap there.
 */
object A11yEngineFallback {

    /** Set by the app process; receives display-relative pixel coordinates. */
    @Volatile
    var tap: ((Int, Int) -> Boolean)? = null
}
