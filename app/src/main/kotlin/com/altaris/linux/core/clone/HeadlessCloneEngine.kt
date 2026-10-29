package com.altaris.linux.core.clone

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.altaris.linux.clone.engine.EngineClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * UI-side façade for the Shizuku shell engine. The engine lives in the shell
 * (uid 2000) user-service process; this object only forwards commands, parses
 * its JSON replies and exposes Compose-friendly state.
 *
 * Clones are repackaged, signed, installed hidden in user 0, and launched on a
 * virtual display owned by the shell process. There is no embedded virtual-app
 * runtime and no secondary Android user/profile.
 *
 * ## Concurrent clones
 *
 * Every launch gets its own virtual display, addressed by a *session key*: the
 * clone package for clones, [KEY_PRIMARY] for an installed app opened ad hoc.
 * Launching one clone never releases another clone's display, and the machine
 * API (`apiCapture`, `apiTap`, …) takes a package name so two agents can drive
 * two clones at the same time without fighting over "the" display.
 * [activeClonePackage] is only the clone the viewer is looking at.
 */
object HeadlessCloneEngine {

    private const val TAG = "AltarisClone"
    private const val CLONE_PREFIX = "com.altaris.apps."
    private const val LEGACY_CLONE_PREFIX = "com.nopiece.hclone."

    /** Session key used for an installed app opened without a clone. */
    const val KEY_PRIMARY = SessionKeys.PRIMARY

    /** The shell engine refuses more than this many live displays. */
    const val MAX_SESSIONS = 6

    data class DeviceApp(
        val packageName: String,
        val label: String,
        val icon: Bitmap?,
    )

    data class CloneApp(
        val packageName: String,
        val originalPackage: String,
        val originalLabel: String,
        val label: String,
        val activity: String,
        val source: String,
        val status: String,
        val orientation: Int,
        val icon: Bitmap?,
        val createdAt: Long = 0L,
        val lastOpened: Long = 0L,
        val parts: Int = 0,
    )

    /** One live virtual display and the clone that owns it. */
    data class Session(
        val key: String,
        val packageName: String?,
        val displayId: Int,
        val width: Int,
        val height: Int,
        val fps: Int,
        val frames: Long = 0L,
        val viewer: Boolean = false,
    )

    enum class Quality(val key: String, val label: String, val jpeg: Int) {
        LOW("low", "Low", 55),
        MEDIUM("medium", "Medium", 78),
        HIGH("high", "High", 94);

        companion object {
            fun from(value: String?): Quality =
                entries.firstOrNull { it.key == value } ?: MEDIUM
        }
    }

    data class PerAppDisplay(
        val width: Int,
        val height: Int,
        val fps: Int,
    ) {
        val customSize: Boolean get() = width > 0 && height > 0
    }

    // Shizuku / shell service
    val shizukuInstalled = MutableStateFlow(false)
    val shizukuRunning = MutableStateFlow(false)
    val hasPermission = MutableStateFlow(false)
    val connected = MutableStateFlow(false)
    val shizukuVersion = MutableStateFlow(-1)

    // Display
    val displayReady = MutableStateFlow(false)
    val displayId = MutableStateFlow(-1)
    val displayWidth = MutableStateFlow(720)
    val displayHeight = MutableStateFlow(1280)
    val displayDpi = MutableStateFlow(320)
    val displayState = MutableStateFlow("")
    val displayFrames = MutableStateFlow(0L)
    val inputReady = MutableStateFlow(false)
    val displayRotation = MutableStateFlow(-1)
    val preview = MutableStateFlow<Bitmap?>(null)
    val previewError = MutableStateFlow<String?>(null)
    /** Live displays, keyed by clone package (or [KEY_PRIMARY]). */
    val sessions = MutableStateFlow<Map<String, Session>>(emptyMap())
    /** Clone package the viewer is showing, if any. */
    val activeClonePackage = MutableStateFlow<String?>(null)
    /** Recent user/agent actions, newest last. Shown in the viewer. */
    val actionLog = MutableStateFlow<List<String>>(emptyList())
    private val clearedLogLines = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    fun noteAction(action: String, source: String = "user") {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        actionLog.value = (actionLog.value + "$stamp  $source  $action").takeLast(80)
    }

    fun clearActionLog() { actionLog.value = emptyList() }

    /** Session key the viewer is attached to. */
    fun viewerKey(): String = activeClonePackage.value ?: KEY_PRIMARY

    /** Resolve a package name to its session key, falling back to the viewer. */
    private fun keyOf(packageName: String?): String =
        SessionKeys.resolve(packageName, activeClonePackage.value)

    // User defaults
    val displayFps = MutableStateFlow(60)
    val quality = MutableStateFlow(Quality.MEDIUM)
    val touchEnabled = MutableStateFlow(true)

    // Lists
    val clones = MutableStateFlow<List<CloneApp>>(emptyList())
    val deviceApps = MutableStateFlow<List<DeviceApp>>(emptyList())

    // Operation / messages / logs
    val busy = MutableStateFlow(false)
    val operation = MutableStateFlow<String?>(null)
    val notice = MutableStateFlow<String?>(null)
    val lastError = MutableStateFlow<String?>(null)
    val logs = MutableStateFlow<List<String>>(emptyList())
    val cloneLogs = MutableStateFlow<List<String>>(emptyList())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val previewInFlight = AtomicBoolean(false)
    private val statusInFlight = AtomicBoolean(false)
    private val clonesInFlight = AtomicBoolean(false)
    private val deviceAppsInFlight = AtomicBoolean(false)
    private val logsInFlight = AtomicBoolean(false)
    private val operationInFlight = AtomicBoolean(false)
    /** Serialises display creation so two launches cannot race a key. */
    private val displayLock = Any()
    private val localLogs = ArrayList<String>()
    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null
    private var client: EngineClient? = null
    private var listenerAdded = false
    private var bootstrapAttempted = false

    // ---------------------------------------------------------------- lifecycle

    /** Cheap startup cleanup for the retired virtual-app engine. No Shizuku call. */
    fun onAppCreate(context: Context) {
        if (!isAndroidRuntime) return
        val app = context.applicationContext
        Thread {
            runCatching { deleteRetiredEngineFiles(app) }
                .onFailure { Log.w(TAG, "legacy cleanup failed", it) }
            runCatching { deleteLegacyNotificationChannels(app) }
                .onFailure { Log.w(TAG, "legacy channel cleanup failed", it) }
        }.apply {
            isDaemon = true
            name = "altaris-legacy-cleanup"
        }.start()
        localLog("Altaris headless clone engine ready")
    }

    /** Attach the UI to the Shizuku engine. Safe to call repeatedly. */
    fun attach(context: Context) {
        if (!isAndroidRuntime) return
        if (appContext != null) return
        appContext = context.applicationContext
        prefs = appContext?.getSharedPreferences("altaris_clone_settings", Context.MODE_PRIVATE)
        loadSettings()
        ensureClient()
        refreshState()
    }

    private fun loadSettings() {
        val store = prefs ?: return
        displayFps.value = store.getInt("fps", 60).coerceIn(10, 240)
        quality.value = Quality.from(store.getString("quality", null))
        touchEnabled.value = store.getBoolean("touch", true)
    }

    fun setDisplayFps(value: Int) {
        val fps = value.coerceIn(10, 240)
        displayFps.value = fps
        prefs?.edit()?.putInt("fps", fps)?.apply()
    }

    fun setQuality(value: Quality) {
        quality.value = value
        prefs?.edit()?.putString("quality", value.key)?.apply()
    }

    fun setTouchEnabled(enabled: Boolean) {
        touchEnabled.value = enabled
        prefs?.edit()?.putBoolean("touch", enabled)?.apply()
    }

    fun perAppDisplay(packageName: String): PerAppDisplay {
        val store = prefs ?: return PerAppDisplay(0, 0, 0)
        return PerAppDisplay(
            width = store.getInt("app.$packageName.width", 0),
            height = store.getInt("app.$packageName.height", 0),
            fps = store.getInt("app.$packageName.fps", 0),
        )
    }

    fun savePerAppDisplay(packageName: String, width: Int, height: Int, fps: Int) {
        prefs?.edit()
            ?.putInt("app.$packageName.width", width.coerceAtLeast(0))
            ?.putInt("app.$packageName.height", height.coerceAtLeast(0))
            ?.putInt("app.$packageName.fps", fps.coerceAtLeast(0))
            ?.apply()
    }

    fun clearPerAppDisplay(packageName: String) {
        prefs?.edit()
            ?.remove("app.$packageName.width")
            ?.remove("app.$packageName.height")
            ?.remove("app.$packageName.fps")
            ?.apply()
    }

    fun bootstrap() {
        if (!isAndroidRuntime) return
        val c = ensureClient() ?: return
        refreshState()
        if (!c.isManagerInstalled() || !c.isBinderAlive() || !c.hasPermission()) return
        if (c.isServiceConnected()) return
        if (bootstrapAttempted) return
        bootstrapAttempted = true
        val reason = c.startService()
        if (reason != null) {
            setNotice(reason)
        } else {
            setNotice("Starting shell engine…")
            scope.launch {
                repeat(12) {
                    delay(400)
                    refreshState()
                    if (c.isServiceConnected()) return@launch
                }
            }
        }
    }

    fun requestPermission() {
        val c = ensureClient() ?: return
        setNotice(c.requestPermission() ?: "Shizuku permission requested")
        refreshState()
    }

    fun startService() {
        val c = ensureClient() ?: return
        // Already bound: a no-op, and definitely not another "starting" notice.
        if (c.isServiceConnected()) {
            connected.value = true
            refreshState()
            return
        }
        val reason = c.startService()
        if (reason != null) {
            setNotice(reason)
            if (!c.isServiceConnected()) connected.value = false
        } else {
            setNotice("Starting shell engine…")
        }
        refreshState()
    }

    fun stopService() {
        val c = client ?: return
        c.stopService()
        preview.value = null
        displayReady.value = false
        sessions.value = emptyMap()
        activeClonePackage.value = null
        setNotice("Shell engine stopped")
        refreshState()
    }

    fun openShizukuManager() {
        val c = ensureClient() ?: return
        if (!c.openShizukuManager()) {
            setNotice("Shizuku app not found — install Shizuku first")
        }
    }

    fun openShizukuDownload() {
        if (appContext == null) ensureClient()
        val ctx = appContext ?: return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }
            .onFailure { setNotice("No browser available to open the Shizuku download page") }
    }

    fun refreshState() {
        val c = ensureClient() ?: return
        shizukuInstalled.value = c.isManagerInstalled()
        shizukuRunning.value = c.isBinderAlive()
        hasPermission.value = c.hasPermission()
        connected.value = c.isServiceConnected()
        shizukuVersion.value = c.shizukuVersion()
        if (!connected.value) {
            displayReady.value = false
            inputReady.value = false
            sessions.value = emptyMap()
            return
        }
        if (!statusInFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                val raw = c.status()
                parseStatus(raw)
                if (c.isServiceConnected()) {
                    parseLogs(c.logTail(80))
                    refreshClonesInternal(c)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "status failed", t)
            } finally {
                statusInFlight.set(false)
            }
        }
    }

    // ---------------------------------------------------------------- display

    /**
     * Create (or recreate) the display of one session key.
     *
     * [width]/[height] of 0 keep the stored per-app size or the current default,
     * exactly like a launch does, so this is safe to call from the viewer.
     */
    fun createDisplay(
        width: Int = displayWidth.value,
        height: Int = displayHeight.value,
        dpi: Int = displayDpi.value,
        fps: Int = displayFps.value,
        key: String = viewerKey(),
    ) {
        val c = requireConnected() ?: return
        val w = width.coerceIn(320, 2160)
        val h = height.coerceIn(480, 3840)
        val d = dpi.coerceIn(120, 640)
        val f = fps.coerceIn(10, 240)
        displayWidth.value = w
        displayHeight.value = h
        displayDpi.value = d
        operation.value = "Creating virtual display…"
        scope.launch {
            try {
                synchronized(displayLock) {
                    val raw = c.createDisplay(key, w, h, d, f)
                    val error = resultError(raw)
                    if (error == null) {
                        setNotice("Virtual display ready")
                    } else {
                        fail(error)
                    }
                }
                parseStatus(c.status())
            } finally {
                operation.value = null
            }
        }
    }

    /** Release one session, or every session when [key] is empty. */
    fun releaseDisplay(key: String = "") {
        val c = requireConnected() ?: return
        preview.value = null
        displayReady.value = false
        if (key.isBlank() || key == activeClonePackage.value) {
            activeClonePackage.value = null
        }
        scope.launch {
            try {
                c.releaseDisplay(key)
                setNotice(if (key.isBlank()) "All virtual displays released" else "Display released")
                parseStatus(c.status())
            } catch (t: Throwable) {
                fail(t.message ?: "Release failed")
            }
        }
    }

    fun capturePreview() {
        val c = client ?: return
        if (!connected.value || !displayReady.value) return
        capturePreviewOf(viewerKey(), c)
    }

    /** Capture the display of one package (the viewer by default). */
    fun capturePreviewOf(packageName: String) {
        val c = client ?: return
        if (!connected.value) return
        capturePreviewOf(keyOf(packageName), c)
    }

    private fun capturePreviewOf(key: String, c: EngineClient) {
        if (!previewInFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                val frame = c.capture(key, 700, quality.value.jpeg)
                if (frame.ok()) {
                    val bitmap = BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size)
                    if (bitmap != null) {
                        preview.value = bitmap
                        previewError.value = null
                    } else {
                        previewError.value = "Could not decode the display frame"
                    }
                } else {
                    previewError.value = frame.error
                }
            } catch (t: Throwable) {
                previewError.value = t.message ?: "Frame capture failed"
            } finally {
                previewInFlight.set(false)
            }
        }
    }

    // ---------------------------------------------------------------- clone creation

    fun cloneFromInstalled(packageName: String, label: String?) {
        val c = requireConnected() ?: return
        val name = label?.trim()?.takeIf { it.isNotEmpty() } ?: packageName
        runOperation("Cloning $name…") {
            c.cloneFromInstalled(packageName, "$name (clone)")
        }
    }

    fun cloneFromApk(uri: Uri) {
        val c = requireConnected() ?: return
        val ctx = appContext ?: return
        runOperation("Preparing APK…") {
            val descriptor = ctx.contentResolver.openFileDescriptor(uri, "r")
                ?: return@runOperation "Cannot open the selected APK"
            descriptor.use { c.cloneFromApk(it, null) }
        }
    }

    fun cloneFromUrl(url: String) {
        val c = requireConnected() ?: return
        val ctx = appContext ?: return
        val clean = url.trim()
        if (clean.isEmpty()) {
            setNotice("Paste a direct APK link first")
            return
        }
        runOperation("Downloading APK…") {
            val dir = File(ctx.cacheDir, "clone-inbox").apply { mkdirs() }
            val file = File(dir, "download-" + System.currentTimeMillis() + ".apk")
            try {
                downloadTo(clean, file)
                operation.value = "Installing APK…"
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
                    c.cloneFromApk(it, null)
                }
            } finally {
                runCatching { file.delete() }
            }
        }
    }

    fun launchClone(packageName: String, width: Int = 0, height: Int = 0, fps: Int = 0) {
        if (sessions.value.containsKey(packageName)) {
            // Already running on its own display: just bring the viewer there.
            activeClonePackage.value = packageName
            setNotice("Already running; resuming view")
            val c = ensureClient()
            if (c != null) parseStatus(c.status())
            return
        }
        val saved = perAppDisplay(packageName)
        val w = width.takeIf { it > 0 } ?: saved.width
        val h = height.takeIf { it > 0 } ?: saved.height
        val f = fps.takeIf { it > 0 } ?: saved.fps.takeIf { it > 0 } ?: displayFps.value
        launchWithDisplay("Opening clone…", w, h, f, packageName, packageName) { c, key ->
            c.cloneLaunch(packageName, key)
        }
    }

    fun launchInstalledApp(packageName: String) {
        launchWithDisplay("Opening app on the display…", 0, 0, displayFps.value, packageName, KEY_PRIMARY) { c, key ->
            c.launchApp(packageName, key, true)
        }
    }

    fun fetchCloneLogs(packageName: String, maxLines: Int = 240) {
        val c = requireConnected() ?: return
        if (!logsInFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                val raw = c.cloneLogs(packageName, maxLines)
                val array = runCatching { JSONArray(raw) }.getOrNull()
                if (array == null) {
                    cloneLogs.value = listOf("Log reply was not valid JSON")
                    return@launch
                }
                val lines = ArrayList<String>(array.length())
                for (i in 0 until array.length()) {
                    val line = array.optString(i)
                    if (line.isNotBlank() && !clearedLogLines.contains(line)) lines.add(line)
                }
                cloneLogs.value = lines
            } catch (t: Throwable) {
                cloneLogs.value = listOf("Log read failed: ${t.message ?: t.javaClass.simpleName}")
            } finally {
                logsInFlight.set(false)
            }
        }
    }

    fun clearCloneLogs() {
        clearedLogLines.addAll(cloneLogs.value)
        if (clearedLogLines.size > 4000) {
            clearedLogLines.clear()
        }
        cloneLogs.value = emptyList()
    }

    /** Stop the clone process but keep its display for another launch. */
    fun killClone(packageName: String) {
        val c = requireConnected() ?: return
        scope.launch {
            try {
                val error = resultError(c.cloneKill(packageName))
                if (error != null) fail(error) else setNotice("App stopped")
                dropSession(packageName, c)
                refreshClonesInternal(c)
            } catch (t: Throwable) {
                fail(t.message ?: "Stop failed")
            }
        }
    }

    /** Stop the clone and release its display. Other clones keep running. */
    fun stopClone(packageName: String) {
        val c = requireConnected()
        if (c == null) {
            setNotice("Shell engine is not connected — reconnect in Preference, then stop again")
            return
        }
        scope.launch {
            try {
                val error = resultError(c.cloneKill(packageName))
                if (error != null) {
                    fail(error)
                } else {
                    setNotice("App stopped")
                }
                noteAction("stop $packageName")
                dropSession(packageName, c)
                // The first force-stop can race the display teardown. Kill once
                // more now that the display is gone, so no migrated task/process
                // can surface full-screen.
                runCatching { c.cloneKill(packageName) }
                preview.value = null
                previewError.value = null
                displayReady.value = false
                refreshClonesInternal(c)
                parseStatus(c.status())
            } catch (t: Throwable) {
                fail(t.message ?: "Stop failed")
            }
        }
    }

    /** Kill the clone and release only its own display. */
    private fun dropSession(packageName: String, c: EngineClient) {
        if (activeClonePackage.value == packageName) {
            activeClonePackage.value = null
            preview.value = null
            previewError.value = null
        }
        runCatching { c.releaseDisplay(packageName) }
        sessions.value = sessions.value - packageName
        if (sessions.value.isEmpty()) displayReady.value = false
    }

    /**
     * Reset the viewer after the clone was left running in the background.
     * The display and clone process are intentionally kept alive.
     */
    fun leaveViewerRunning() {
        previewError.value = null
    }

    fun deleteClone(packageName: String) {
        val c = requireConnected() ?: return
        runOperation("Removing clone…") {
            c.cloneDelete(packageName)
        }
        dropSession(packageName, c)
        clearPerAppDisplay(packageName)
        clearCloneLogs()
    }

    // ---------------------------------------------------------------- input

    fun tap(x: Int, y: Int, packageName: String? = null) {
        val c = client ?: return
        if (!connected.value || !displayReady.value) return
        noteAction("tap ($x, $y)")
        scope.launch {
            runCatching { c.tap(keyOf(packageName), x, y) }
                .onFailure { Log.w(TAG, "tap failed", it) }
        }
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 260, packageName: String? = null) {
        val c = client ?: return
        if (!connected.value || !displayReady.value) return
        noteAction("swipe ($x1,$y1) → ($x2,$y2)")
        scope.launch {
            runCatching { c.swipe(keyOf(packageName), x1, y1, x2, y2, durationMs) }
                .onFailure { Log.w(TAG, "swipe failed", it) }
        }
    }

    fun key(keyCode: Int, packageName: String? = null) {
        val c = client ?: return
        if (!connected.value || !displayReady.value) return
        noteAction("key $keyCode")
        scope.launch {
            runCatching { c.key(keyOf(packageName), keyCode) }
                .onFailure { Log.w(TAG, "key failed", it) }
        }
    }

    fun text(value: String, packageName: String? = null) {
        val c = client ?: return
        if (!connected.value || !displayReady.value || value.isEmpty()) return
        noteAction("text \"${value.take(60)}\"")
        scope.launch {
            runCatching { c.text(keyOf(packageName), value) }
                .onFailure { Log.w(TAG, "text failed", it) }
        }
    }

    // ---------------------------------------------------------------- lists

    fun refreshClones() {
        val c = client ?: return
        if (!connected.value) return
        refreshClonesInternal(c)
    }

    fun refreshDeviceApps() {
        if (!isAndroidRuntime) return
        val ctx = appContext ?: return
        if (!deviceAppsInFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                val pm = ctx.packageManager
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                @Suppress("DEPRECATION")
                val resolved = pm.queryIntentActivities(intent, 0)
                val result = resolved.asSequence()
                    .mapNotNull { it.activityInfo?.applicationInfo }
                    .filter { info ->
                        info.packageName != ctx.packageName &&
                            !info.packageName.startsWith(CLONE_PREFIX) &&
                            !info.packageName.startsWith(LEGACY_CLONE_PREFIX) &&
                            (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0
                    }
                    .distinctBy { it.packageName }
                    .map { info ->
                        DeviceApp(
                            packageName = info.packageName,
                            label = runCatching { pm.getApplicationLabel(info).toString() }
                                .getOrDefault(info.packageName),
                            icon = runCatching { drawableToBitmap(info.loadIcon(pm), 96) }.getOrNull(),
                        )
                    }
                    .sortedBy { it.label.lowercase(Locale.ROOT) }
                    .toList()
                deviceApps.value = result
            } catch (t: Throwable) {
                Log.w(TAG, "device app list failed", t)
            } finally {
                deviceAppsInFlight.set(false)
            }
        }
    }

    fun clearPreview() {
        preview.value = null
        previewError.value = null
    }

    // ---------------------------------------------------------------- control API

    /**
     * Machine-facing operations used by the local control server, the guest
     * `altaris` CLI and MCP. These suspend functions return raw JSON objects so
     * callers never have to scrape UI state.
     *
     * Every operation that touches a display takes an optional package name:
     * that is what makes concurrent agents possible. Without one, the viewer's
     * clone is used.
     */

    suspend fun apiStatus(): JSONObject = withContext(Dispatchers.IO) {
        val c = ensureClient()
        val base = JSONObject()
            .put("connected", c?.isServiceConnected() == true)
            .put("shizuku_installed", c?.isManagerInstalled() == true)
            .put("shizuku_running", c?.isBinderAlive() == true)
            .put("permission", c?.hasPermission() == true)
            .put("display_ready", displayReady.value)
            .put("display_id", displayId.value)
            .put("display_width", displayWidth.value)
            .put("display_height", displayHeight.value)
            .put("display_dpi", displayDpi.value)
            .put("display_fps", displayFps.value)
            .put("active_clone", activeClonePackage.value ?: JSONObject.NULL)
            .put("session_count", sessions.value.size)
            .put("sessions", sessionsJson())
        if (c?.isServiceConnected() == true) {
            runCatching {
                val raw = JSONObject(c.status())
                raw.keys().forEach { key -> base.put(key, raw.get(key)) }
            }
        }
        base
    }

    suspend fun apiSessions(): JSONObject = withContext(Dispatchers.IO) {
        val c = ensureClient()
        if (c != null && c.isServiceConnected()) {
            runCatching { parseStatus(c.status()) }
        }
        JSONObject()
            .put("ok", true)
            .put("count", sessions.value.size)
            .put("max", MAX_SESSIONS)
            .put("active_clone", activeClonePackage.value ?: JSONObject.NULL)
            .put("sessions", sessionsJson())
    }

    private fun sessionsJson(): JSONArray {
        val array = JSONArray()
        sessions.value.values.forEach { session ->
            array.put(
                JSONObject()
                    .put("package", session.key)
                    .put("display_id", session.displayId)
                    .put("width", session.width)
                    .put("height", session.height)
                    .put("fps", session.fps)
                    .put("frames", session.frames)
                    .put("viewer", session.viewer)
            )
        }
        return array
    }

    suspend fun apiApps(): JSONObject = withContext(Dispatchers.IO) {
        val array = JSONArray()
        deviceApps.value.forEach { app ->
            array.put(
                JSONObject()
                    .put("package", app.packageName)
                    .put("label", app.label)
            )
        }
        JSONObject().put("ok", true).put("apps", array)
    }

    suspend fun apiClones(): JSONObject = withContext(Dispatchers.IO) {
        val c = ensureClient()
        if (c == null || !c.isServiceConnected()) {
            return@withContext JSONObject().put("ok", false).put("error", "Shell engine is not connected")
        }
        val raw = try {
            c.cloneList()
        } catch (t: Throwable) {
            return@withContext JSONObject().put("ok", false)
                .put("error", t.message ?: "Clone list failed")
        }
        try {
            val parsed = JSONObject(raw)
            parsed.put("sessions", sessionsJson())
            parsed
        } catch (_: Throwable) {
            JSONObject().put("ok", false).put("error", "Clone list was not valid JSON")
        }
    }

    suspend fun apiInstall(path: String?, url: String?, label: String?): JSONObject =
        withContext(Dispatchers.IO) {
            val c = ensureClient()
            if (c == null || !c.isServiceConnected()) {
                return@withContext JSONObject().put("ok", false).put("error", "Shell engine is not connected")
            }
            val ctx = appContext
                ?: return@withContext JSONObject().put("ok", false).put("error", "App context is not ready")
            var downloaded: File? = null
            try {
                val cleanLabel = label?.trim()?.takeIf { name -> name.isNotEmpty() }
                val file = when {
                    !url.isNullOrBlank() -> {
                        val dir = File(ctx.cacheDir, "clone-inbox").apply { mkdirs() }
                        val f = File(dir, "api-" + System.currentTimeMillis() + ".apk")
                        downloadTo(url, f)
                        downloaded = f
                        f
                    }
                    !path.isNullOrBlank() -> {
                        resolveInstallFile(path.removePrefix("file://"), ctx)
                    }
                    else -> return@withContext JSONObject()
                        .put("ok", false)
                        .put("error", "Provide path or url")
                }

                // A path the app can read is fastest. When scoped storage or
                // an unreadable guest directory blocks us, hand the path to
                // the shell service, which runs as root/UID 2000 and knows the
                // common /sdcard, /emulated and chroot aliases.
                val raw = if (file.isFile && file.canRead() && file.length() >= 8L) {
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
                        c.cloneFromApk(it, cleanLabel)
                    }
                } else if (!path.isNullOrBlank()) {
                    val shellPath = path.removePrefix("file://")
                    c.cloneFromPath(shellPath, cleanLabel)
                } else {
                    return@withContext JSONObject()
                        .put("ok", false)
                        .put("error", "APK path is not readable: ${file.absolutePath}")
                }
                val result = runCatching { JSONObject(raw) }.getOrElse {
                    JSONObject().put("ok", false).put("error", "Engine returned invalid JSON")
                }
                if (result.optBoolean("ok", false)) {
                    noteAction("install ${file.name}", "agent")
                    refreshClonesInternal(c)
                }
                result
            } catch (t: Throwable) {
                JSONObject().put("ok", false).put("error", t.message ?: t.javaClass.simpleName)
            } finally {
                runCatching { downloaded?.delete() }
            }
        }

    suspend fun apiUninstall(packageName: String): JSONObject = withContext(Dispatchers.IO) {
        val c = ensureClient()
        if (c == null || !c.isServiceConnected()) {
            return@withContext JSONObject().put("ok", false).put("error", "Shell engine is not connected")
        }
        try {
            val error = resultError(c.cloneDelete(packageName))
            if (error == null) {
                dropSession(packageName, c)
                refreshClonesInternal(c)
                JSONObject().put("ok", true).put("detail", "Removed $packageName")
            } else {
                JSONObject().put("ok", false).put("error", error)
            }
        } catch (t: Throwable) {
            JSONObject().put("ok", false).put("error", t.message ?: "Uninstall failed")
        }
    }

    /**
     * Launch a clone on its own virtual display.
     *
     * Repeating the call for a running clone returns the existing session; other
     * clones are untouched, so N agents can hold N clones without stepping on
     * each other.
     */
    suspend fun apiLaunch(packageName: String, width: Int = 0, height: Int = 0, fps: Int = 0): JSONObject =
        withContext(Dispatchers.IO) {
            if (packageName.isBlank()) {
                return@withContext JSONObject().put("ok", false).put("error", "package is required")
            }
            val c = ensureClient()
            if (c == null || !c.isServiceConnected()) {
                return@withContext JSONObject().put("ok", false).put("error", "Shell engine is not connected")
            }
            val existing = sessions.value[packageName]
            if (existing != null) {
                // Already running: only move the *viewer* when nothing owns it.
                if (activeClonePackage.value == null) activeClonePackage.value = packageName
                parseStatus(c.status())
                return@withContext JSONObject()
                    .put("ok", true)
                    .put("already_running", true)
                    .put("package", packageName)
                    .put("display_id", existing.displayId)
                    .put("sessions", sessionsJson())
            }
            if (sessions.value.size >= MAX_SESSIONS) {
                return@withContext JSONObject().put("ok", false)
                    .put("error", "At most $MAX_SESSIONS clones can run at once; stop one first")
                    .put("sessions", sessionsJson())
            }
            val saved = perAppDisplay(packageName)
            val w = (width.takeIf { it > 0 } ?: saved.width.takeIf { it > 0 }
                ?: displayWidth.value).coerceIn(320, 2160)
            val h = (height.takeIf { it > 0 } ?: saved.height.takeIf { it > 0 }
                ?: displayHeight.value).coerceIn(480, 3840)
            val f = (fps.takeIf { it > 0 } ?: saved.fps.takeIf { it > 0 }
                ?: displayFps.value).coerceIn(10, 240)
            try {
                val created = synchronized(displayLock) {
                    c.createDisplay(packageName, w, h, displayDpi.value, f)
                }
                val createError = resultError(created)
                if (createError != null) {
                    return@withContext JSONObject().put("ok", false)
                        .put("error", "Display failed: $createError")
                }
                val display = runCatching { JSONObject(created).optInt("display_id", -1) }.getOrDefault(-1)
                // Do not steal the viewer: an agent launching clone B must not
                // move the clone a human is watching.
                if (activeClonePackage.value == null) activeClonePackage.value = packageName
                parseStatus(c.status())
                val launchError = resultError(c.cloneLaunch(packageName, packageName))
                if (launchError != null) {
                    runCatching { c.releaseDisplay(packageName) }
                    sessions.value = sessions.value - packageName
                    parseStatus(c.status())
                    return@withContext JSONObject().put("ok", false).put("error", launchError)
                }
                noteAction("launch $packageName", "agent")
                parseStatus(c.status())
                refreshClonesInternal(c)
                JSONObject()
                    .put("ok", true)
                    .put("package", packageName)
                    .put("width", w)
                    .put("height", h)
                    .put("fps", f)
                    .put("display_id", if (display > 0) display else sessions.value[packageName]?.displayId ?: -1)
                    .put("sessions", sessionsJson())
            } catch (t: Throwable) {
                JSONObject().put("ok", false).put("error", t.message ?: "Launch failed")
            }
        }

    /** Stop one clone and release only its display. */
    suspend fun apiStop(packageName: String): JSONObject = withContext(Dispatchers.IO) {
        if (packageName.isBlank()) {
            return@withContext JSONObject().put("ok", false).put("error", "package is required")
        }
        val c = ensureClient()
        if (c == null || !c.isServiceConnected()) {
            return@withContext JSONObject().put("ok", false).put("error", "Shell engine is not connected")
        }
        try {
            val error = resultError(c.cloneKill(packageName))
            dropSession(packageName, c)
            // Kill after the display is released: a slow first force-stop must
            // not leave a process that Android can migrate to the phone screen.
            runCatching { c.cloneKill(packageName) }
            noteAction("stop $packageName", "agent")
            if (error != null) {
                JSONObject().put("ok", false).put("error", error).put("sessions", sessionsJson())
            } else {
                refreshClonesInternal(c)
                parseStatus(c.status())
                JSONObject().put("ok", true)
                    .put("detail", "Stopped $packageName")
                    .put("sessions", sessionsJson())
            }
        } catch (t: Throwable) {
            JSONObject().put("ok", false).put("error", t.message ?: "Stop failed")
        }
    }

    suspend fun apiCapture(
        maxWidth: Int = 900,
        jpeg: Int = 90,
        timeoutMs: Int = 5_000,
        packageName: String? = null,
    ): ByteArray? =
        withContext(Dispatchers.IO) {
            val c = ensureClient() ?: return@withContext null
            if (!c.isServiceConnected()) return@withContext null
            val frame = runCatching {
                c.capture(keyOf(packageName), timeoutMs.coerceIn(500, 30_000), jpeg.coerceIn(30, 100))
            }.getOrNull()
            if (frame != null && frame.ok()) frame.jpeg else null
        }

    suspend fun apiLogs(packageName: String, maxLines: Int = 300): JSONObject =
        withContext(Dispatchers.IO) {
            val c = ensureClient()
            if (c == null || !c.isServiceConnected()) {
                return@withContext JSONObject().put("ok", false).put("error", "Shell engine is not connected")
            }
            val raw = try {
                c.cloneLogs(packageName, maxLines.coerceIn(20, 2000))
            } catch (t: Throwable) {
                return@withContext JSONObject().put("ok", false)
                    .put("error", t.message ?: "Log read failed")
            }
            try {
                val array = JSONArray(raw)
                JSONObject().put("ok", true).put("package", packageName).put("lines", array)
            } catch (_: Throwable) {
                JSONObject().put("ok", false).put("error", "Engine returned invalid log JSON")
            }
        }

    suspend fun apiTap(x: Int, y: Int, packageName: String? = null): JSONObject =
        withContext(Dispatchers.IO) {
            noteAction("tap ($x, $y)", "agent")
            val c = ensureClient()
            if (c == null || !c.isServiceConnected()) {
                return@withContext JSONObject().put("ok", false).put("error", "Shell engine is not connected")
            }
            val key = keyOf(packageName)
            runCatching { JSONObject(c.tap(key, x, y)).put("package", key) }
                .getOrElse { JSONObject().put("ok", false).put("error", it.message ?: "Tap failed") }
        }

    suspend fun apiSwipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int,
        packageName: String? = null,
    ): JSONObject =
        withContext(Dispatchers.IO) {
            noteAction("swipe ($x1,$y1) → ($x2,$y2)", "agent")
            val c = ensureClient()
            if (c == null || !c.isServiceConnected()) {
                return@withContext JSONObject().put("ok", false).put("error", "Shell engine is not connected")
            }
            val key = keyOf(packageName)
            runCatching {
                JSONObject(c.swipe(key, x1, y1, x2, y2, durationMs.coerceIn(30, 3000))).put("package", key)
            }.getOrElse { JSONObject().put("ok", false).put("error", it.message ?: "Swipe failed") }
        }

    suspend fun apiText(value: String, packageName: String? = null): JSONObject = withContext(Dispatchers.IO) {
        noteAction("text \"${value.take(60)}\"", "agent")
        val c = ensureClient()
        if (c == null || !c.isServiceConnected()) {
            return@withContext JSONObject().put("ok", false).put("error", "Shell engine is not connected")
        }
        val key = keyOf(packageName)
        runCatching { JSONObject(c.text(key, value)).put("package", key) }
            .getOrElse { JSONObject().put("ok", false).put("error", it.message ?: "Text failed") }
    }

    suspend fun apiKey(keyCode: Int, packageName: String? = null): JSONObject = withContext(Dispatchers.IO) {
        noteAction("key $keyCode", "agent")
        val c = ensureClient()
        if (c == null || !c.isServiceConnected()) {
            return@withContext JSONObject().put("ok", false).put("error", "Shell engine is not connected")
        }
        val key = keyOf(packageName)
        runCatching { JSONObject(c.key(key, keyCode)).put("package", key) }
            .getOrElse { JSONObject().put("ok", false).put("error", it.message ?: "Key failed") }
    }

    suspend fun apiReleaseDisplay(packageName: String? = null): JSONObject = withContext(Dispatchers.IO) {
        val c = ensureClient()
        if (c == null || !c.isServiceConnected()) {
            return@withContext JSONObject().put("ok", false).put("error", "Shell engine is not connected")
        }
        val key = packageName.orEmpty()
        val error = resultError(c.releaseDisplay(key))
        if (key.isBlank() || key == activeClonePackage.value) {
            activeClonePackage.value = null
            preview.value = null
        }
        if (key.isBlank()) {
            sessions.value = emptyMap()
            displayReady.value = false
        } else {
            sessions.value = sessions.value - key
        }
        parseStatus(c.status())
        if (error == null) JSONObject().put("ok", true).put("sessions", sessionsJson())
        else JSONObject().put("ok", false).put("error", error)
    }

    fun activeDisplayPackage(): String? = activeClonePackage.value

    /** Display id for a package, or the viewer's display when null. */
    fun displayIdFor(packageName: String? = null): Int =
        sessions.value[keyOf(packageName)]?.displayId
            ?: if (packageName.isNullOrBlank()) displayId.value else -1

    // ---------------------------------------------------------------- internals

    private fun ensureClient(): EngineClient? {
        val ctx = appContext ?: return client
        val c = client ?: EngineClient.get(ctx).also { client = it }
        if (!listenerAdded) {
            listenerAdded = true
            c.addListener(object : EngineClient.Listener {
                override fun onStateChanged() {
                    refreshState()
                }
            })
        }
        return c
    }

    private fun requireConnected(): EngineClient? {
        val c = ensureClient()
        if (c == null || !c.isServiceConnected()) {
            setNotice("Start the shell engine first (Apps → Shizuku)")
            return null
        }
        return c
    }

    /**
     * Create the display for [key] and run [command] on it.
     *
     * Only this key's display is recreated: a launch in one clone must not
     * destroy the display another clone (or another agent) is using.
     */
    private fun launchWithDisplay(
        label: String,
        requestedWidth: Int,
        requestedHeight: Int,
        requestedFps: Int,
        viewerPackage: String?,
        key: String,
        command: (EngineClient, String) -> String,
    ) {
        val c = requireConnected() ?: return
        if (operationInFlight.get()) return
        operation.value = label
        scope.launch {
            try {
                val w = (requestedWidth.takeIf { it > 0 } ?: displayWidth.value).coerceIn(320, 2160)
                val h = (requestedHeight.takeIf { it > 0 } ?: displayHeight.value).coerceIn(480, 3840)
                val f = requestedFps.coerceIn(10, 240)
                if (!sessions.value.containsKey(key) && sessions.value.size >= MAX_SESSIONS) {
                    fail("At most $MAX_SESSIONS clones can run at once; stop one first")
                    return@launch
                }
                // A display cannot be resized together with its capture surface.
                // Recreate only this session so per-app resolution/FPS applies.
                val created = synchronized(displayLock) {
                    c.createDisplay(key, w, h, displayDpi.value, f)
                }
                val createError = resultError(created)
                if (createError != null) {
                    fail("Display failed: $createError")
                    return@launch
                }
                activeClonePackage.value = viewerPackage
                parseStatus(c.status())
                val error = resultError(command(c, key))
                if (error != null) {
                    fail(error)
                } else {
                    if (viewerPackage != null) noteAction("launch $key")
                    setNotice("Launch requested")
                }
                refreshClonesInternal(c)
                parseStatus(c.status())
            } catch (t: Throwable) {
                fail(t.message ?: "Launch failed")
            } finally {
                operation.value = null
            }
        }
    }

    private fun runOperation(label: String, block: () -> String) {
        val c = requireConnected() ?: return
        if (!operationInFlight.compareAndSet(false, true)) return
        busy.value = true
        operation.value = label
        scope.launch {
            try {
                val raw = block()
                val error = resultError(raw)
                if (error != null) {
                    fail(error)
                } else {
                    val detail = runCatching { JSONObject(raw).optString("detail") }.getOrNull()
                    lastError.value = null
                    setNotice(detail?.takeIf { it.isNotBlank() } ?: "Done")
                    refreshClonesInternal(c)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "operation failed", t)
                fail(t.message ?: t.javaClass.simpleName)
            } finally {
                busy.value = false
                operation.value = null
                operationInFlight.set(false)
                refreshState()
            }
        }
    }

    private fun refreshClonesInternal(c: EngineClient) {
        if (!clonesInFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                val raw = c.cloneList()
                val root = runCatching { JSONObject(raw) }.getOrNull()
                if (root == null) {
                    Log.w(TAG, "clone list parse failed")
                    return@launch
                }
                if (root.has("error")) {
                    lastError.value = root.optString("error")
                    return@launch
                }
                val array = root.optJSONArray("clones") ?: JSONArray()
                val list = ArrayList<CloneApp>()
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val pkg = item.optString("package")
                    if (pkg.isBlank()) continue
                    val original = item.optString("original")
                    val activity = item.optString("activity")
                    val label = item.optString("label").ifBlank { pkg }
                    val originalLabel = runCatching {
                        val manager = appContext?.packageManager ?: return@runCatching ""
                        val info = manager.getApplicationInfo(original, 0)
                        manager.getApplicationLabel(info).toString()
                    }.getOrNull().orEmpty()
                    val orientation = runCatching {
                        val manager = appContext?.packageManager ?: return@runCatching ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        manager.getActivityInfo(ComponentName(pkg, activity), 0).screenOrientation
                    }.getOrDefault(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
                    list.add(
                        CloneApp(
                            packageName = pkg,
                            originalPackage = original,
                            originalLabel = originalLabel.ifBlank {
                                label.removeSuffix(" (clone)")
                            },
                            label = label,
                            activity = activity,
                            source = item.optString("source"),
                            status = item.optString("status", "installed"),
                            orientation = orientation,
                            icon = iconFor(original.ifBlank { pkg }),
                            createdAt = item.optLong("created", 0L),
                            lastOpened = item.optLong("last_opened", 0L),
                            parts = item.optInt("parts", 0),
                        )
                    )
                }
                clones.value = list.sortedWith(
                    compareByDescending<CloneApp> { it.lastOpened }
                        .thenByDescending { it.createdAt }
                        .thenBy { it.label.lowercase(Locale.ROOT) }
                )
            } catch (t: Throwable) {
                Log.w(TAG, "clone list failed", t)
            } finally {
                clonesInFlight.set(false)
            }
        }
    }

    private fun parseStatus(raw: String) {
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (obj.has("error")) {
            val error = obj.optString("error")
            if (error.isNotBlank()) lastError.value = error
        }
        displayReady.value = obj.optBoolean("display_ready", false)
        displayId.value = obj.optInt("display_id", -1)
        if (obj.optInt("display_width", 0) > 0) displayWidth.value = obj.optInt("display_width")
        if (obj.optInt("display_height", 0) > 0) displayHeight.value = obj.optInt("display_height")
        if (obj.optInt("display_dpi", 0) > 0) displayDpi.value = obj.optInt("display_dpi")
        displayState.value = obj.optString("display_state")
        displayFrames.value = obj.optLong("display_frames", 0L)
        displayRotation.value = obj.optInt("display_rotation", -1)
        inputReady.value = obj.optBoolean("input_ready", false)
        if (!displayReady.value) preview.value = null

        val array = obj.optJSONArray("sessions")
        if (array != null) {
            val map = LinkedHashMap<String, Session>()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val key = item.optString("key").ifBlank { item.optString("package") }
                if (key.isBlank()) continue
                map[key] = Session(
                    key = key,
                    packageName = key.takeIf { it.startsWith(CLONE_PREFIX) || it.startsWith(LEGACY_CLONE_PREFIX) },
                    displayId = item.optInt("display_id", -1),
                    width = item.optInt("width", 0),
                    height = item.optInt("height", 0),
                    fps = displayFps.value,
                    frames = item.optLong("frames", 0L),
                    viewer = item.optBoolean("viewer", false),
                )
            }
            sessions.value = map
            // Keep the legacy single-display fields pointed at the clone the
            // viewer owns, so a launch by an agent cannot make the UI viewer
            // report another clone's display.
            val viewed = activeClonePackage.value?.let { map[it] }
            if (viewed != null) {
                displayId.value = viewed.displayId
                displayReady.value = true
                if (viewed.width > 0) displayWidth.value = viewed.width
                if (viewed.height > 0) displayHeight.value = viewed.height
            }
        } else if (!displayReady.value) {
            sessions.value = emptyMap()
        }
    }

    private fun parseLogs(raw: String) {
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return
        val engineLines = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val line = array.optString(i)
            if (line.isNotBlank()) engineLines.add(line)
        }
        synchronized(localLogs) {
            logs.value = (engineLines + localLogs).takeLast(240)
        }
    }

    private fun resultError(raw: String): String? {
        val obj = runCatching { JSONObject(raw) }.getOrNull()
            ?: return "Engine returned an invalid reply"
        return if (obj.optBoolean("ok", false)) null
        else obj.optString("error").ifBlank { "Engine request failed" }
    }

    fun setNotice(text: String) {
        val value = text.trim()
        if (value.isEmpty()) return
        notice.value = value
        localLog(value)
        scope.launch {
            delay(6000)
            if (notice.value == value) notice.value = null
        }
    }

    private fun fail(message: String) {
        lastError.value = message
        setNotice(message)
        Log.w(TAG, message)
    }

    private fun localLog(message: String) {
        val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "  " + message
        synchronized(localLogs) {
            localLogs.add(line)
            while (localLogs.size > 120) localLogs.removeAt(0)
            logs.value = (logs.value + line).takeLast(240)
        }
    }

    private fun iconFor(packageName: String): Bitmap? {
        val ctx = appContext ?: return null
        if (packageName.isBlank()) return null
        return runCatching {
            val pm = ctx.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            drawableToBitmap(pm.getApplicationIcon(info), 96)
        }.getOrNull()
    }

    private fun drawableToBitmap(drawable: Drawable?, size: Int): Bitmap? {
        if (drawable == null) return null
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return runCatching {
                Bitmap.createScaledBitmap(drawable.bitmap, size, size, true)
            }.getOrNull()
        }
        return runCatching {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            bitmap
        }.getOrNull()
    }

    private fun resolveInstallFile(raw: String, ctx: Context): File {
        val clean = raw.trim()
        val candidates = ArrayList<File>()
        candidates.add(File(clean))
        val emulated = when {
            clean.startsWith("/emulated/") -> clean.removePrefix("/emulated/")
            clean.startsWith("/sdcard/") -> clean.removePrefix("/sdcard/")
            clean.startsWith("/storage/emulated/0/") -> clean.removePrefix("/storage/emulated/0/")
            else -> null
        }
        if (emulated != null) {
            candidates.add(File("/storage/emulated/0/$emulated"))
            candidates.add(File("/sdcard/$emulated"))
            candidates.add(File("/emulated/$emulated"))
        }
        if (clean.startsWith("/sdcard/")) {
            candidates.add(File("/storage/emulated/0/${clean.removePrefix("/sdcard/")}"))
        }
        if (clean.startsWith("/storage/emulated/0/")) {
            candidates.add(File("/sdcard/${clean.removePrefix("/storage/emulated/0/")}"))
        }
        return candidates.firstOrNull { it.isFile && it.canRead() } ?: candidates.first()
    }

    private fun downloadTo(url: String, destination: File) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 300_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Altaris/3.13")
            setRequestProperty(
                "Accept",
                "application/vnd.android.package-archive,application/octet-stream,*/*",
            )
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("Download failed (HTTP $code)")
            }
            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    input.copyTo(output, 128 * 1024)
                }
            }
            if (!destination.isFile || destination.length() < 8L) {
                throw IllegalStateException("Downloaded file is empty")
            }
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun deleteRetiredEngineFiles(context: Context) {
        // Retired in-app engine paths. New clones live in /data/local/tmp/altaris
        // (shell-owned) and are intentionally left alone.
        File(context.filesDir, "clone").deleteRecursively()
        File(context.filesDir, "blackbox").deleteRecursively()
        File(context.cacheDir, "blackbox").deleteRecursively()
        File(context.filesDir.parentFile ?: context.filesDir, "blackbox").deleteRecursively()
        context.getExternalFilesDir(null)?.let { File(it, "blackbox").deleteRecursively() }
        File(context.filesDir, "logs/clone.log").delete()
        File(context.filesDir, "logs/clone.old.log").delete()
    }

    private fun deleteLegacyNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        listOf(
            "AltarisCloneSession_v1",
            "${context.packageName}.blackbox_core",
            "blackbox_core",
        ).forEach { id -> runCatching { manager.deleteNotificationChannel(id) } }
    }

    private val isAndroidRuntime: Boolean
        get() = System.getProperty("java.vm.name")?.contains("Dalvik", ignoreCase = true) == true
}
