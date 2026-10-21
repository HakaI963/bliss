package com.ivarna.fluxlinux.core.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
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
 * Nodes are addressed by their path in the tree ("r.0.2"), not by a platform
 * id: the platform id APIs changed across releases, while a path stays valid
 * for as long as the tree itself does.
 *
 * Every entry point is safe to call from a binder thread and never throws:
 * failures come back as a JSON error payload.
 */
class AltarisAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AltarisA11y"

        /** Max tree depth walked for a snapshot. */
        private const val MAX_DEPTH = 40

        /** Path of the root node. */
        private const val ROOT_PATH = "r"

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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // The tree is read on demand; events only tell us the app changed.
    }

    override fun onInterrupt() {
        // No spoken feedback is produced by this service.
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
    fun snapshot(
        displayId: Int,
        maxNodes: Int = 600,
        packageFilter: String? = null,
    ): String = runCatching {
        val limit = maxNodes.coerceIn(1, 2000)
        val wanted = packageFilter?.takeIf { it.isNotBlank() }
        // The engine display id and the framework display id are different
        // numbering, so the clone is located by its package first and only
        // then by display.
        val everything = windowsFor(-1)
        var pool = windowsFor(displayId)
        if (wanted != null) {
            val matching = pool.filter { windowPackage(it) == wanted }
            pool = if (matching.isNotEmpty()) {
                matching
            } else {
                everything.filter { windowPackage(it) == wanted }
            }
            if (pool.isEmpty()) {
                // Never fall back to another app's window: the caller asked
                // for one package and would get somebody else's tree.
                return@runCatching JSONObject()
                    .put("ok", false)
                    .put("display", displayId)
                    .put("source", "accessibility")
                    .put("error", "no window for package $wanted")
                    .put("candidates", candidates(everything))
                    .toString()
            }
        }
        if (pool.isEmpty()) pool = everything
        if (pool.isEmpty()) {
            return@runCatching JSONObject()
                .put("ok", true)
                .put("display", displayId)
                .put("source", "accessibility")
                .put("windows", 0)
                .put("nodes", JSONArray())
                .toString()
        }
        val window = pickAppWindow(pool)
        val root = window?.window?.root
        JSONObject()
            .put("ok", true)
            .put("display", window?.displayId ?: displayId)
            .put("source", "accessibility")
            .put("windows", pool.size)
            .put("package", root?.packageName?.toString().orEmpty())
            .put("candidates", candidates(everything))
            .put(
                "nodes",
                if (root == null) JSONArray() else walk(root, limit, window?.displayId ?: displayId),
            )
            .toString()
    }.getOrElse { errorJson("snapshot failed: ${it.message}") }

    /**
     * Framework display id of the window owned by [packageName], or -1.
     * The clone engine numbers displays on its own, so gestures have to be
     * aimed with the framework id the accessibility service reports.
     */
    fun displayIdFor(packageName: String): Int {
        if (packageName.isBlank()) return -1
        return windowsFor(-1).firstOrNull { windowPackage(it) == packageName }?.displayId ?: -1
    }

    /** Every window the service can see, for diagnostics. */
    private fun candidates(windows: List<WindowRef>): JSONArray {
        val out = JSONArray()
        windows.forEach { ref ->
            out.put(
                JSONObject()
                    .put("display", ref.displayId)
                    .put("package", windowPackage(ref))
                    .put("focused", runCatching { ref.window.isFocused }.getOrDefault(false)),
            )
        }
        return out
    }

    /** Package that owns a window, or an empty string. */
    private fun windowPackage(ref: WindowRef): String =
        runCatching { ref.window.root?.packageName?.toString() }.getOrNull().orEmpty()

    /** Click the node at [path] (paths are only valid inside one snapshot). */
    fun click(path: String): Boolean =
        runCatching { performClick(path).optBoolean("ok", false) }.getOrDefault(false)

    /** Detailed click result (JSON) used by the app-side bridge. */
    fun clickDetailed(path: String): String =
        runCatching { performClick(path).toString() }.getOrElse { errorJson("click failed: ${it.message}") }

    /** Set the text of an editable node. */
    fun setText(path: String, text: String): Boolean =
        runCatching { performSetText(path, text).optBoolean("ok", false) }.getOrDefault(false)

    /** Detailed set-text result (JSON) used by the app-side bridge. */
    fun setTextDetailed(path: String, text: String): String = runCatching {
        performSetText(path, text).toString()
    }.getOrElse { errorJson("set text failed: ${it.message}") }

    /** Scroll a node forward or backward. */
    fun scroll(path: String, forward: Boolean): Boolean =
        runCatching { performScroll(path, forward).optBoolean("ok", false) }.getOrDefault(false)

    /** Detailed scroll result (JSON) used by the app-side bridge. */
    fun scrollDetailed(path: String, forward: Boolean): String = runCatching {
        performScroll(path, forward).toString()
    }.getOrElse { errorJson("scroll failed: ${it.message}") }

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

    private fun performClick(path: String): JSONObject {
        val node = resolveNode(path)
            ?: return JSONObject()
                .put("ok", false)
                .put("nodeId", path)
                .put("error", "node $path not found — the tree changed, read it again")
        val performed = runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }.getOrDefault(false)
        if (performed) {
            return JSONObject().put("ok", true).put("nodeId", path).put("method", "action_click")
        }
        val bounds = boundsInScreen(node)
        val payload = JSONObject()
            .put("ok", false)
            .put("nodeId", path)
            .put("reason", "not_clickable")
            .put("error", "node is not clickable")
        if (bounds != null) {
            payload.put("bounds", rectJson(bounds))
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
                        .put("nodeId", path)
                        .put("method", "engine_tap")
                        .put("x", cx)
                        .put("y", cy)
                }
            }
        }
        return payload
    }

    private fun performSetText(path: String, text: String): JSONObject {
        val node = resolveNode(path)
            ?: return JSONObject()
                .put("ok", false)
                .put("nodeId", path)
                .put("error", "node $path not found — the tree changed, read it again")
        if (!node.isEditable) {
            return JSONObject()
                .put("ok", false)
                .put("nodeId", path)
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
            .put("nodeId", path)
            .put("text", text)
            .apply { if (!done) put("error", "set-text action was rejected") }
    }

    private fun performScroll(path: String, forward: Boolean): JSONObject {
        val node = resolveNode(path)
            ?: return JSONObject()
                .put("ok", false)
                .put("nodeId", path)
                .put("error", "node $path not found — the tree changed, read it again")
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        val done = runCatching { node.performAction(action) }.getOrDefault(false)
        return JSONObject()
            .put("ok", done)
            .put("nodeId", path)
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
    private fun pickAppWindow(windows: List<WindowRef>): WindowRef? {
        if (windows.isEmpty()) return null
        val apps = windows.filter { it.window.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val pool = apps.ifEmpty { windows }
        pool.firstOrNull { runCatching { it.window.isFocused }.getOrDefault(false) }
            ?.let { return it }
        return pool.maxByOrNull { ref ->
            val root = runCatching { ref.window.root }.getOrNull() ?: return@maxByOrNull 0
            val bounds = boundsInScreen(root) ?: return@maxByOrNull 0
            bounds.width() * bounds.height()
        }
    }

    /** Breadth first walk so parents are always listed before their children. */
    private fun walk(root: AccessibilityNodeInfo, maxNodes: Int, displayId: Int): JSONArray {
        val out = JSONArray()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val paths = ArrayDeque<String>()
        queue.addLast(root)
        paths.addLast("$displayId:$ROOT_PATH")
        while (queue.isNotEmpty() && out.length() < maxNodes) {
            val node = queue.removeFirst()
            val path = paths.removeFirst()
            val depth = path.count { it == '.' }
            out.put(nodeJson(node, path, depth))
            if (depth >= MAX_DEPTH) continue
            val count = runCatching { node.childCount }.getOrDefault(0)
            for (i in 0 until count) {
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                queue.addLast(child)
                paths.addLast("$path.$i")
            }
        }
        return out
    }

    private fun nodeJson(node: AccessibilityNodeInfo, path: String, depth: Int): JSONObject {
        val screen = boundsInScreen(node)
        val window = boundsInWindow(node)
        return JSONObject()
            .put("id", path)
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

    /** Screen bounds of a node. The no-argument getter is gone on API 34+. */
    private fun boundsInScreen(node: AccessibilityNodeInfo): Rect? {
        val rect = Rect()
        val ok = runCatching {
            node.getBoundsInScreen(rect)
            true
        }.getOrDefault(false)
        return if (ok) rect else null
    }

    /** Window bounds of a node. */
    private fun boundsInWindow(node: AccessibilityNodeInfo): Rect? {
        val rect = Rect()
        val ok = runCatching {
            node.getBoundsInWindow(rect)
            true
        }.getOrDefault(false)
        return if (ok) rect else null
    }

    private fun rectJson(rect: Rect?): JSONArray {
        if (rect == null) return JSONArray()
        return JSONArray().put(rect.left).put(rect.top).put(rect.right).put(rect.bottom)
    }

    private fun actionIds(node: AccessibilityNodeInfo): JSONArray {
        val ids = JSONArray()
        val declared: List<Int> = runCatching {
            node.actionList.map { action -> action.id }
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

    /**
     * Paths are only valid inside one snapshot, so always re-resolve them.
     *
     * A path carries the display it came from ("79:r.0.2"): the app window
     * and the clone window live on different displays and have different
     * trees, so the node has to be looked up in the right one.
     */
    private fun resolveNode(path: String): AccessibilityNodeInfo? {
        val trimmed = path.trim()
        val separator = trimmed.indexOf(':')
        if (separator <= 0) return null
        val displayId = trimmed.substring(0, separator).toIntOrNull() ?: return null
        val nodePath = trimmed.substring(separator + 1)
        val parts = nodePath.split('.').filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.first() != ROOT_PATH) return null
        val windows = windowsFor(displayId).ifEmpty { windowsFor(-1) }
        for (window in windows) {
            val root = runCatching { window.window.root }.getOrNull() ?: continue
            val node = resolveIn(root, parts.drop(1)) ?: continue
            return node
        }
        return null
    }

    /** Walks the child indexes of a node path. */
    private fun resolveIn(root: AccessibilityNodeInfo, indexes: List<String>): AccessibilityNodeInfo? {
        var node = root
        for (index in indexes) {
            val position = index.toIntOrNull() ?: return null
            val count = runCatching { node.childCount }.getOrDefault(0)
            if (position < 0 || position >= count) return null
            node = runCatching { node.getChild(position) }.getOrNull() ?: return null
        }
        return node
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
