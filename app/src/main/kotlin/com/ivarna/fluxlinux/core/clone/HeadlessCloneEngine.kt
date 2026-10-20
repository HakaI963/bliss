package com.ivarna.fluxlinux.core.clone

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
import com.ivarna.fluxlinux.clone.engine.EngineClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
 */
object HeadlessCloneEngine {

    private const val TAG = "BlissClone"
    private const val CLONE_PREFIX = "com.nopiece.hclone."

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
            name = "bliss-legacy-cleanup"
        }.start()
        localLog("Bliss headless clone engine ready")
    }

    /** Attach the UI to the Shizuku engine. Safe to call repeatedly. */
    fun attach(context: Context) {
        if (!isAndroidRuntime) return
        if (appContext != null) return
        appContext = context.applicationContext
        prefs = appContext?.getSharedPreferences("bliss_clone_settings", Context.MODE_PRIVATE)
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
        val reason = c.startService()
        setNotice(reason ?: "Starting shell engine…")
        refreshState()
    }

    fun stopService() {
        val c = client ?: return
        c.stopService()
        preview.value = null
        displayReady.value = false
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

    fun createDisplay(
        width: Int = displayWidth.value,
        height: Int = displayHeight.value,
        dpi: Int = displayDpi.value,
        fps: Int = displayFps.value,
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
                val raw = c.createDisplay(w, h, d, f)
                val error = resultError(raw)
                if (error == null) {
                    setNotice("Virtual display ready")
                    parseStatus(c.status())
                } else {
                    fail(error)
                }
            } finally {
                operation.value = null
            }
        }
    }

    fun releaseDisplay() {
        val c = requireConnected() ?: return
        preview.value = null
        displayReady.value = false
        scope.launch {
            try {
                c.releaseDisplay()
                setNotice("Virtual display released")
                parseStatus(c.status())
            } catch (t: Throwable) {
                fail(t.message ?: "Release failed")
            }
        }
    }

    fun capturePreview() {
        val c = client ?: return
        if (!connected.value || !displayReady.value) return
        if (!previewInFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                val frame = c.capture(700, quality.value.jpeg)
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
        val saved = perAppDisplay(packageName)
        val w = width.takeIf { it > 0 } ?: saved.width
        val h = height.takeIf { it > 0 } ?: saved.height
        val f = fps.takeIf { it > 0 } ?: saved.fps.takeIf { it > 0 } ?: displayFps.value
        launchWithDisplay("Opening clone…", w, h, f) { c ->
            c.cloneLaunch(packageName)
        }
    }

    fun launchInstalledApp(packageName: String) {
        launchWithDisplay("Opening app on the display…", 0, 0, displayFps.value) { c ->
            c.launchApp(packageName, true)
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
                    if (line.isNotBlank()) lines.add(line)
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
        cloneLogs.value = emptyList()
    }

    fun killClone(packageName: String) {
        val c = requireConnected() ?: return
        scope.launch {
            try {
                val error = resultError(c.cloneKill(packageName))
                if (error != null) fail(error) else setNotice("App stopped")
                refreshClonesInternal(c)
            } catch (t: Throwable) {
                fail(t.message ?: "Stop failed")
            }
        }
    }

    fun deleteClone(packageName: String) {
        val c = requireConnected() ?: return
        runOperation("Removing clone…") {
            c.cloneDelete(packageName)
        }
        clearPerAppDisplay(packageName)
        clearCloneLogs()
    }

    // ---------------------------------------------------------------- input

    fun tap(x: Int, y: Int) {
        val c = client ?: return
        if (!connected.value || !displayReady.value) return
        scope.launch {
            runCatching { c.tap(x, y) }.onFailure { Log.w(TAG, "tap failed", it) }
        }
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 260) {
        val c = client ?: return
        if (!connected.value || !displayReady.value) return
        scope.launch {
            runCatching { c.swipe(x1, y1, x2, y2, durationMs) }
                .onFailure { Log.w(TAG, "swipe failed", it) }
        }
    }

    fun key(keyCode: Int) {
        val c = client ?: return
        if (!connected.value || !displayReady.value) return
        scope.launch {
            runCatching { c.key(keyCode) }.onFailure { Log.w(TAG, "key failed", it) }
        }
    }

    fun text(value: String) {
        val c = client ?: return
        if (!connected.value || !displayReady.value || value.isEmpty()) return
        scope.launch {
            runCatching { c.text(value) }.onFailure { Log.w(TAG, "text failed", it) }
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

    private fun launchWithDisplay(
        label: String,
        requestedWidth: Int,
        requestedHeight: Int,
        requestedFps: Int,
        command: (EngineClient) -> String,
    ) {
        val c = requireConnected() ?: return
        if (operationInFlight.get()) return
        operation.value = label
        scope.launch {
            try {
                val w = (requestedWidth.takeIf { it > 0 } ?: displayWidth.value).coerceIn(320, 2160)
                val h = (requestedHeight.takeIf { it > 0 } ?: displayHeight.value).coerceIn(480, 3840)
                val f = requestedFps.coerceIn(10, 240)
                // A display cannot be resized together with its capture surface.
                // Recreate it so per-app resolution/FPS always take effect.
                if (displayReady.value) {
                    c.releaseDisplay()
                    preview.value = null
                    displayReady.value = false
                }
                val created = c.createDisplay(w, h, displayDpi.value, f)
                val createError = resultError(created)
                if (createError != null) {
                    fail("Display failed: $createError")
                    return@launch
                }
                parseStatus(c.status())
                val error = resultError(command(c))
                if (error != null) {
                    fail(error)
                } else {
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
                        )
                    )
                }
                clones.value = list.sortedWith(
                    compareBy<CloneApp> { it.originalLabel.lowercase(Locale.ROOT) }
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

    private fun setNotice(text: String) {
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

    private fun downloadTo(url: String, destination: File) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 300_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Bliss/3.13")
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
        // Retired in-app engine paths. New clones live in /data/local/tmp/bliss
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
            "FluxLinuxCloneSession_v1",
            "${context.packageName}.blackbox_core",
            "blackbox_core",
        ).forEach { id -> runCatching { manager.deleteNotificationChannel(id) } }
    }

    private val isAndroidRuntime: Boolean
        get() = System.getProperty("java.vm.name")?.contains("Dalvik", ignoreCase = true) == true
}
