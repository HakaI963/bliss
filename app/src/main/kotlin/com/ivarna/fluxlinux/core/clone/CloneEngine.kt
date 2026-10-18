package com.ivarna.fluxlinux.core.clone

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import top.niunaijun.blackbox.BlackBoxCore
import com.ivarna.fluxlinux.core.service.CloneSessionService
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Bliss clone space.
 *
 * Apps are installed into the embedded BlackBox engine and live entirely inside
 * Bliss (their files never appear in the normal launcher). They run on a hidden
 * private virtual display owned by this process; the Apps tab shows live frames
 * of that display and injects touch through Shizuku/root.
 *
 * display.id handoff: BlackBox's launch paths run in other processes, so the
 * current clone display id is published as filesDir/clone/display.id and read by
 * the patched engine (top.niunaijun.blackbox.bliss.BlissDisplay).
 */
object CloneEngine {

    const val USER_ID = 0
    private const val TAG = "BlissClone"
    private const val DISPLAY_FILE_REL = "clone/display.id"
    private const val PREFS = "bliss_clone_cfg"

    /** Resolution presets offered in Apps > Configure. */
    val RES_PRESETS: List<Pair<Int, Int>> = listOf(
        540 to 1170,
        720 to 1560,
        900 to 1950,
    )

    data class Frame(val bitmap: Bitmap, val seq: Long)

    data class CloneApp(
        val packageName: String,
        val label: String,
        val version: String,
        val icon: Bitmap?,
    )

    val apps = MutableStateFlow<List<CloneApp>>(emptyList())
    val frame = MutableStateFlow<Frame?>(null)
    val running = MutableStateFlow<String?>(null)
    val status = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val touchBackend = MutableStateFlow(ShellRunner.Backend.NONE)
    val displayW = MutableStateFlow(720)
    val displayH = MutableStateFlow(1560)
    val logs = MutableStateFlow<List<String>>(emptyList())

    // Configure tab (persisted in SharedPreferences).
    val displayFps = MutableStateFlow(15)
    val displayResPreset = MutableStateFlow(1)
    val displayDpi = MutableStateFlow(280)

    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var frameThread: HandlerThread? = null
    private var displayId = 0
    private var lastFrameAt = 0L
    private val buffers = arrayOfNulls<Bitmap>(2)
    private var flip = false
    private var seq = 0L
    private var initialized = false

    // ---------------------------------------------------------------- lifecycle

    fun init(app: Application) {
        if (initialized) return
        initialized = true
        appContext = app.applicationContext
        touchBackend.value = ShellRunner.backend()
        if (!isMainProcess(app)) return
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { p ->
            displayFps.value = p.getInt("fps", 15).coerceIn(5, 60)
            displayResPreset.value = p.getInt("res", 1).coerceIn(0, RES_PRESETS.size - 1)
            displayDpi.value = p.getInt("dpi", 280)
            val preset = RES_PRESETS[displayResPreset.value]
            displayW.value = preset.first
            displayH.value = preset.second
        }
        // No clone display can survive a process restart: a stale id would send
        // launches to a dead display (and the system would fall back to the real
        // screen), so always start clean. If an id was left behind by a killed
        // process, also stop any clone app it may have left running.
        val hadStaleDisplay = displayIdFile().exists()
        clearDisplayIdFile()
        if (hadStaleDisplay) {
            scope.launch {
                SystemClock.sleep(6000)
                runCatching { BlackBoxCore.get().getInstalledApplications(0, USER_ID) }
                    .getOrDefault(emptyList())
                    .forEach { ai ->
                        runCatching { BlackBoxCore.get().stopPackage(ai.packageName, USER_ID) }
                    }
            }
        }
        log("Clone engine initialised (fps=${displayFps.value}, res=${displayW.value}x${displayH.value})")
        // Agent surface: CLI + MCP server in host prefix and every proot distro,
        // plus the file-bridge watcher those tools talk to.
        CloneAgentDeployer.ensureInstalled(app)
        CloneBridge.start(app)
        refresh()
    }

    private fun isMainProcess(ctx: Context): Boolean {
        val name = if (Build.VERSION.SDK_INT >= 28) {
            runCatching { Application.getProcessName() }.getOrDefault("")
        } else {
            runCatching { File("/proc/self/cmdline").readText().trim('\u0000', ' ', '\n') }
                .getOrDefault("")
        }
        return name.isEmpty() || name == ctx.packageName
    }

    // ---------------------------------------------------------------- logs

    /** Newest-first ring buffer shown in Apps > Logs (and fed by the agent bridge). */
    fun log(msg: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        logs.value = (listOf("$stamp  $msg") + logs.value).take(300)
        Log.d(TAG, msg)
    }

    // ---------------------------------------------------------------- apps list

    fun refresh() {
        scope.launch { refreshInternal() }
    }

    private suspend fun refreshInternal(retries: Int = 3) {
        val ctx = appContext ?: return
        var list = emptyList<android.content.pm.ApplicationInfo>()
        for (attempt in 0 until retries) {
            list = runCatching { BlackBoxCore.get().getInstalledApplications(0, USER_ID) }
                .getOrDefault(emptyList())
            if (list.isNotEmpty() || attempt == retries - 1) break
            SystemClock.sleep(450)
        }
        val meta = loadMeta()
        val out = list
            .filter { it.packageName != ctx.packageName }
            .map { ai ->
                val m = meta.optJSONObject(ai.packageName)
                CloneApp(
                    packageName = ai.packageName,
                    label = m?.optString("label")?.takeIf { it.isNotBlank() }
                        ?: runCatching { ai.loadLabel(ctx.packageManager).toString() }
                            .getOrDefault(ai.packageName),
                    version = m?.optString("version").orEmpty(),
                    icon = loadIcon(m?.optString("icon")),
                )
            }
            .sortedBy { it.label.lowercase(Locale.ROOT) }
        apps.value = out
    }

    fun statusText(): String? = status.value

    // ---------------------------------------------------------------- install

    fun installUri(uri: Uri) {
        val ctx = appContext ?: return
        scope.launch {
            busy.value = true
            status.value = "Copying APK…"
            log("Install: copying picked file")
            var dest: File? = null
            try {
                val dir = File(ctx.cacheDir, "clone-inbox").apply { mkdirs() }
                dest = File(dir, "install-" + System.currentTimeMillis() + ".apk")
                val input = ctx.contentResolver.openInputStream(uri)
                if (input == null) {
                    status.value = "Could not read the selected file"
                    log("Install failed: cannot read picked file")
                    return@launch
                }
                input.use { ins -> FileOutputStream(dest).use { ins.copyTo(it) } }
                status.value = "Installing into the clone space…"
                val err = installBlocking(dest)
                status.value = err ?: "Installed"
                log(if (err == null) "Install ok: ${dest.name}" else "Install failed: $err")
                if (err == null) refreshInternal()
            } catch (t: Throwable) {
                Log.w(TAG, "install from uri failed", t)
                status.value = t.message ?: "Install failed"
                log("Install failed: ${t.message}")
            } finally {
                runCatching { dest?.delete() }
                busy.value = false
            }
        }
    }

    fun installFromUrl(url: String) {
        val ctx = appContext ?: return
        scope.launch {
            busy.value = true
            status.value = "Downloading APK…"
            log("Downloading APK: $url")
            var dest: File? = null
            try {
                val dir = File(ctx.cacheDir, "clone-inbox").apply { mkdirs() }
                dest = File(dir, "download-" + System.currentTimeMillis() + ".apk")
                val conn = (URL(url.trim()).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 120_000
                    instanceFollowRedirects = true
                }
                conn.inputStream.use { ins -> FileOutputStream(dest).use { ins.copyTo(it) } }
                runCatching { conn.disconnect() }
                status.value = "Installing into the clone space…"
                val err = installBlocking(dest)
                status.value = err ?: "Installed"
                log(if (err == null) "Install ok: ${dest.name}" else "Install failed: $err")
                if (err == null) refreshInternal()
            } catch (t: Throwable) {
                Log.w(TAG, "install from url failed", t)
                status.value = t.message ?: "Download failed"
                log("Download failed: ${t.message}")
            } finally {
                runCatching { dest?.delete() }
                busy.value = false
            }
        }
    }

    /** Blocking; null on success, else an error message. */
    private fun installBlocking(file: File): String? {
        val ctx = appContext ?: return "no context"
        val name = file.name.lowercase(Locale.ROOT)
        if (name.endsWith(".apks") || name.endsWith(".xapk") || name.endsWith(".apkm")) {
            return "Split APK sets (.apks / .xapk / .apkm) land in the next build — use a single .apk for now"
        }
        return try {
            val pm = ctx.packageManager
            val archive = pm.getPackageArchiveInfo(file.absolutePath, 0)
            val res = BlackBoxCore.get().installPackageAsUser(file, USER_ID)
            if (!res.success) {
                res.msg ?: "install failed"
            } else {
                archive?.let { pi ->
                    val ai = pi.applicationInfo
                    if (ai != null) {
                        ai.sourceDir = file.absolutePath
                        ai.publicSourceDir = file.absolutePath
                        val label = runCatching { pm.getApplicationLabel(ai).toString() }
                            .getOrDefault(pi.packageName)
                        val icon = runCatching {
                            drawableToBitmap(pm.getApplicationIcon(ai), 144, 144)
                        }.getOrNull()
                        saveMeta(pi.packageName, label, pi.versionName.orEmpty(), icon)
                    }
                }
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "install failed", t)
            t.message ?: "install failed"
        }
    }

    fun uninstall(packageName: String) {
        scope.launch {
            runCatching { BlackBoxCore.get().uninstallPackageAsUser(packageName, USER_ID) }
            log("Uninstalled $packageName")
            removeMeta(packageName)
            if (running.value == packageName) {
                running.value = null
                appContext?.let { CloneSessionService.stop(it) }
            }
            refreshInternal()
        }
    }

    fun kill(packageName: String) {
        scope.launch {
            runCatching { BlackBoxCore.get().stopPackage(packageName, USER_ID) }
            log("Killed $packageName")
            if (running.value == packageName) {
                running.value = null
                appContext?.let { CloneSessionService.stop(it) }
            }
            status.value = "Killed $packageName"
        }
    }

    // ---------------------------------------------------------------- display

    fun frameIntervalMs(): Long = (1000L / displayFps.value.coerceIn(5, 60)).coerceAtLeast(16L)

    /** Creates the hidden clone display on first use. Main thread only. */
    @Synchronized
    fun ensureDisplay(): Boolean {
        val ctx = appContext ?: return false
        if (vd != null && reader != null) return true
        return try {
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val preset = RES_PRESETS[displayResPreset.value.coerceIn(0, RES_PRESETS.size - 1)]
            val w = preset.first
            val h = preset.second
            displayW.value = w
            displayH.value = h

            val ht = HandlerThread("bliss-clone-frames").also { it.start() }
            frameThread = ht
            val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
            r.setOnImageAvailableListener({ ir -> onFrame(ir) }, Handler(ht.looper))
            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            val d = dm.createVirtualDisplay(
                "bliss-clone", w, h, displayDpi.value.coerceIn(120, 640),
                r.surface, flags,
            )
            reader = r
            vd = d
            displayId = d.display.displayId
            log("Display created: id=$displayId ${w}x$h dpi=${displayDpi.value} fps=${displayFps.value}")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "create clone display failed", t)
            status.value = "Could not create the clone display: ${t.message}"
            log("Display failed: ${t.message}")
            false
        }
    }

    private fun onFrame(ir: ImageReader) {
        val img: Image? = try {
            ir.acquireLatestImage()
        } catch (t: Throwable) {
            null
        }
        if (img == null) return
        try {
            val now = SystemClock.uptimeMillis()
            if (now - lastFrameAt < frameIntervalMs()) return
            lastFrameAt = now
            val w = img.width
            val h = img.height
            val plane = img.planes[0]
            val buf = plane.buffer
            if (plane.pixelStride != 4 || plane.rowStride != w * 4) return
            val slot = if (flip) 1 else 0
            flip = !flip
            val bmp = buffers[slot]
                ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { buffers[slot] = it }
            buf.rewind()
            bmp.copyPixelsFromBuffer(buf)
            seq++
            frame.value = Frame(bmp, seq)
        } catch (t: Throwable) {
            Log.w(TAG, "frame conversion failed", t)
        } finally {
            runCatching { img.close() }
        }
    }

    /** Synchronous display teardown (frame thread + reader + VirtualDisplay). */
    private fun closeDisplay() {
        runCatching { reader?.close() }
        reader = null
        runCatching { vd?.release() }
        vd = null
        frameThread?.quitSafely()
        frameThread = null
        frame.value = null
        displayId = 0
    }

    fun releaseAll() {
        val pkg = running.value
        running.value = null
        clearDisplayIdFile()
        appContext?.let { CloneSessionService.stop(it) }
        if (pkg != null) {
            runCatching { BlackBoxCore.get().stopPackage(pkg, USER_ID) }
        }
        // Give AMS a beat to finish the tasks before the display goes away, so a
        // live task can never migrate to the user's real screen.
        Handler(Looper.getMainLooper()).postDelayed({ closeDisplay() }, 500)
    }

    fun currentDisplayId(): Int = displayId

    // ---------------------------------------------------------------- configure

    /** Persists the Configure tab values and restarts the display (relaunches the running app). */
    fun applyDisplayConfig() {
        val ctx = appContext ?: return
        prefs?.edit()
            ?.putInt("fps", displayFps.value.coerceIn(5, 60))
            ?.putInt("res", displayResPreset.value.coerceIn(0, RES_PRESETS.size - 1))
            ?.putInt("dpi", displayDpi.value.coerceIn(120, 640))
            ?.apply()
        scope.launch {
            val pkg = running.value
            log("Applying display config (fps=${displayFps.value}, res=${RES_PRESETS[displayResPreset.value.coerceIn(0, 2)]}, dpi=${displayDpi.value})")
            if (pkg != null) {
                runCatching { BlackBoxCore.get().stopPackage(pkg, USER_ID) }
                running.value = null
            }
            kotlinx.coroutines.withContext(Dispatchers.Main) { closeDisplay() }
            delay(700)
            if (pkg != null) {
                launchNow(pkg)
            }
        }
    }

    // ---------------------------------------------------------------- launch

    fun launch(packageName: String) {
        if (!ensureDisplay()) return
        log("Launch requested: $packageName")
        scope.launch {
            runCatching { BlackBoxCore.get().ensureBlackProcessInitialized() }
                .onFailure { log("Daemon init warn: ${it.message}") }
        }
        writeDisplayIdFile()
        val ok = runCatching { BlackBoxCore.get().launchApk(packageName, USER_ID) }
            .getOrDefault(false)
        if (ok) {
            running.value = packageName
            status.value = null
            appContext?.let { CloneSessionService.start(it) }
            log("Launch accepted by engine, waiting for first frame…")
            // Watchdog: tell the user when the engine accepted the launch but
            // nothing ever rendered on the hidden display.
            val seqAtLaunch = frame.value?.seq ?: -1L
            scope.launch {
                delay(15_000)
                if (running.value == packageName && (frame.value?.seq ?: -1L) == seqAtLaunch) {
                    log("No frames after 15s — engine may still be starting; try Kill then Open")
                    status.value = "No picture yet — see Logs; try Kill then Open"
                }
            }
        } else {
            status.value = "Launch failed — Kill it, then Open again"
            log("Launch rejected by engine for $packageName")
        }
    }

    // ---------------------------------------------------------------- input

    private fun displayArgs(): Array<String>? {
        val id = displayId
        if (id <= 0) return null
        return arrayOf("input", "-d", id.toString())
    }

    fun tap(x: Float, y: Float) {
        val base = displayArgs() ?: run {
            log("Tap ignored: no display")
            return
        }
        val xi = x.roundToInt().coerceAtLeast(0)
        val yi = y.roundToInt().coerceAtLeast(0)
        log("Tap $xi,$yi")
        scope.launch { ShellRunner.exec(*base, "tap", xi.toString(), yi.toString()) }
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int = 300) {
        val base = displayArgs() ?: return
        log("Swipe ${x1.roundToInt()},${y1.roundToInt()} → ${x2.roundToInt()},${y2.roundToInt()}")
        scope.launch {
            ShellRunner.exec(
                *base,
                "swipe",
                x1.roundToInt().toString(),
                y1.roundToInt().toString(),
                x2.roundToInt().toString(),
                y2.roundToInt().toString(),
                durationMs.toString(),
            )
        }
    }

    fun key(keyCode: String) {
        val base = displayArgs() ?: return
        log("Key $keyCode")
        scope.launch { ShellRunner.exec(*base, "keyevent", keyCode) }
    }

    fun text(value: String) {
        if (value.isEmpty()) return
        val base = displayArgs() ?: return
        log("Text ${value.length} chars")
        // `input text` wants %s for spaces and has no escaping for exotic chars.
        val escaped = value.replace(" ", "%s")
        scope.launch { ShellRunner.exec(*base, "text", escaped) }
    }

    // ---------------------------------------------------------------- screenshot

    fun screenshotPng(): ByteArray? {
        val bmp = frame.value?.bitmap ?: return null
        return try {
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        } catch (t: Throwable) {
            null
        }
    }

    // ---------------------------------------------------------------- agent API (CLI / MCP bridge)

    /** Blocking install from a path (absolute, or relative to the clone bridge dir). */
    fun installApkNow(path: String): String? {
        val f = File(path)
        val real = if (f.isAbsolute) f else File(cloneDir(), path)
        if (!real.isFile) return "file not found: $path"
        log("Agent install: ${real.name} (${real.length()} bytes)")
        val err = installBlocking(real)
        if (err == null) scope.launch { refreshInternal() }
        return err
    }

    /** Blocking download + install (agent API). */
    fun installUrlNow(url: String): String? {
        val ctx = appContext ?: return "no context"
        val dest = File(ctx.cacheDir, "clone-inbox/agent-" + System.currentTimeMillis() + ".apk")
        return try {
            dest.parentFile?.mkdirs()
            val conn = (URL(url.trim()).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 300_000
                instanceFollowRedirects = true
            }
            conn.inputStream.use { ins -> FileOutputStream(dest).use { ins.copyTo(it) } }
            runCatching { conn.disconnect() }
            log("Agent download ok: ${dest.length()} bytes")
            installApkNow(dest.absolutePath)
        } catch (t: Throwable) {
            log("Agent download failed: ${t.message}")
            t.message ?: "download failed"
        } finally {
            runCatching { dest.delete() }
        }
    }

    /** Blocking launch for the agent API. Returns null on success. */
    fun launchNow(packageName: String): String? {
        val displayOk = runCatching {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withContext(Dispatchers.Main) { ensureDisplay() }
            }
        }.getOrDefault(false)
        if (!displayOk) return "clone display unavailable"
        writeDisplayIdFile()
        runCatching { BlackBoxCore.get().ensureBlackProcessInitialized() }
        val ok = runCatching {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    BlackBoxCore.get().launchApk(packageName, USER_ID)
                }
            }
        }.getOrDefault(false)
        if (!ok) {
            log("Agent launch rejected: $packageName")
            return "launch failed for $packageName"
        }
        running.value = packageName
        status.value = null
        appContext?.let { CloneSessionService.start(it) }
        log("Agent launch: $packageName")
        return null
    }

    /** Blocking kill for the agent API. Returns null on success. */
    fun killNow(packageName: String): String? {
        val r = runCatching { BlackBoxCore.get().stopPackage(packageName, USER_ID) }
        if (r.isFailure) return r.exceptionOrNull()?.message ?: "kill failed"
        log("Agent kill: $packageName")
        if (running.value == packageName) {
            running.value = null
            appContext?.let { CloneSessionService.stop(it) }
        }
        return null
    }

    /** Blocking uninstall for the agent API. Returns null on success. */
    fun uninstallNow(packageName: String): String? {
        val r = runCatching { BlackBoxCore.get().uninstallPackageAsUser(packageName, USER_ID) }
        if (r.isFailure) return r.exceptionOrNull()?.message ?: "uninstall failed"
        log("Agent uninstall: $packageName")
        removeMeta(packageName)
        if (running.value == packageName) {
            running.value = null
            appContext?.let { CloneSessionService.stop(it) }
        }
        scope.launch { refreshInternal() }
        return null
    }

    /** Synchronous input injection for the agent API (input -d <display> ...). */
    fun inputSync(args: List<String>): Boolean {
        val base = displayArgs() ?: return false
        val r = ShellRunner.exec(*base, *args.toTypedArray())
        log("Agent input ${args.joinToString(" ")} -> ${if (r.ok) "ok" else "failed"}")
        return r.ok
    }

    /** status payload for the agent bridge (key=value lines). */
    fun statusLines(): String {
        val engine = runCatching { BlackBoxCore.get().areServicesAvailable() }.getOrDefault(false)
        return buildString {
            append("engine=").append(engine)
            append("\ndisplay=").append(displayId)
            append("\nsize=").append(displayW.value).append("x").append(displayH.value)
            append("\nfps=").append(displayFps.value)
            append("\nrunning=").append(running.value ?: "-")
            append("\napps=").append(apps.value.size)
            append("\nframes=").append(frame.value?.seq ?: 0)
            append("\ntouch=").append(touchBackend.value.name.lowercase(Locale.ROOT))
        }
    }

    /** list payload for the agent bridge (pkg|label|version lines). */
    fun listLines(): String =
        apps.value.joinToString("\n") { "${it.packageName}|${it.label}|${it.version.ifEmpty { "-" }}" }

    /** Latest raw frame (agent API + UI). */
    fun latestFrameBitmap(): Bitmap? = frame.value?.bitmap

    // ---------------------------------------------------------------- meta store

    private fun cloneDir(): File = File(appContext?.filesDir ?: File("/"), "clone")

    private fun metaFile(): File = File(cloneDir(), "meta.json")

    private fun iconsDir(): File = File(cloneDir(), "icons")

    private fun loadMeta(): JSONObject =
        runCatching { JSONObject(metaFile().readText()) }.getOrDefault(JSONObject())

    private fun saveMeta(pkg: String, label: String, version: String, icon: Bitmap?) {
        runCatching {
            val root = loadMeta()
            val entry = JSONObject()
            entry.put("label", label)
            entry.put("version", version)
            if (icon != null) {
                iconsDir().mkdirs()
                val f = File(iconsDir(), "$pkg.png")
                FileOutputStream(f).use { os -> icon.compress(Bitmap.CompressFormat.PNG, 100, os) }
                entry.put("icon", f.absolutePath)
            }
            root.put(pkg, entry)
            metaFile().parentFile?.mkdirs()
            metaFile().writeText(root.toString())
        }
    }

    private fun removeMeta(pkg: String) {
        runCatching {
            val root = loadMeta()
            root.remove(pkg)
            metaFile().writeText(root.toString())
            File(iconsDir(), "$pkg.png").delete()
        }
    }

    private fun loadIcon(path: String?): Bitmap? {
        if (path.isNullOrBlank()) return null
        val f = File(path)
        if (!f.exists()) return null
        return runCatching { android.graphics.BitmapFactory.decodeFile(path) }.getOrNull()
    }

    private fun drawableToBitmap(d: Drawable?, w: Int, h: Int): Bitmap? {
        if (d == null) return null
        if (d is BitmapDrawable && d.bitmap != null) {
            return runCatching { Bitmap.createScaledBitmap(d.bitmap, w, h, true) }.getOrNull()
        }
        return runCatching {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            d.setBounds(0, 0, w, h)
            d.draw(c)
            bmp
        }.getOrNull()
    }

    // ---------------------------------------------------------------- display id file

    private fun displayIdFile(): File = File(cloneDir(), "display.id")

    private fun writeDisplayIdFile() {
        runCatching {
            cloneDir().mkdirs()
            displayIdFile().writeText(displayId.toString())
        }
    }

    private fun clearDisplayIdFile() {
        runCatching { displayIdFile().delete() }
    }
}
