package com.ivarna.fluxlinux.core.clone

import android.app.Application
import android.content.Context
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import top.niunaijun.blackbox.BlackBoxCore
import com.ivarna.fluxlinux.core.service.CloneSessionService
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Bliss clone space.
 *
 * Apps are installed into the embedded BlackBox engine and live entirely inside
 * Bliss (their files never appear in the normal launcher). They run on a hidden
 * private virtual display owned by this process; the Tools > Clone window shows
 * live frames of that display and injects touch through Shizuku/root.
 *
 * display.id handoff: BlackBox's launch paths run in other processes, so the
 * current clone display id is published as filesDir/clone/display.id and read by
 * the patched engine (top.niunaijun.blackbox.bliss.BlissDisplay).
 */
object CloneEngine {

    const val USER_ID = 0
    private const val TAG = "BlissClone"
    private const val DISPLAY_FILE_REL = "clone/display.id"
    private const val FRAME_INTERVAL_MS = 66L

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

    private var appContext: Context? = null
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
            var dest: File? = null
            try {
                val dir = File(ctx.cacheDir, "clone-inbox").apply { mkdirs() }
                dest = File(dir, "install-" + System.currentTimeMillis() + ".apk")
                val input = ctx.contentResolver.openInputStream(uri)
                if (input == null) {
                    status.value = "Could not read the selected file"
                    return@launch
                }
                input.use { ins -> FileOutputStream(dest).use { ins.copyTo(it) } }
                status.value = "Installing into the clone space…"
                val err = installBlocking(dest)
                status.value = err ?: "Installed"
                if (err == null) {
                    refreshInternal()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "install from uri failed", t)
                status.value = t.message ?: "Install failed"
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
                if (err == null) {
                    refreshInternal()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "install from url failed", t)
                status.value = t.message ?: "Download failed"
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
            if (running.value == packageName) {
                running.value = null
                appContext?.let { CloneSessionService.stop(it) }
            }
            status.value = "Killed $packageName"
        }
    }

    // ---------------------------------------------------------------- display

    /** Creates the hidden clone display on first use. Main thread only. */
    @Synchronized
    fun ensureDisplay(): Boolean {
        val ctx = appContext ?: return false
        if (vd != null && reader != null) return true
        return try {
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val metrics = ctx.resources.displayMetrics
            val w = 720
            val rawH = 720f * metrics.heightPixels / metrics.widthPixels
            val h = ((rawH / 2f).roundToInt() * 2).coerceIn(1000, 2400)
            displayW.value = w
            displayH.value = h

            val ht = HandlerThread("bliss-clone-frames").also { it.start() }
            frameThread = ht
            val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
            r.setOnImageAvailableListener({ ir -> onFrame(ir) }, Handler(ht.looper))
            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            val d = dm.createVirtualDisplay("bliss-clone", w, h, 280, r.surface, flags)
            reader = r
            vd = d
            displayId = d.display.displayId
            Log.i(TAG, "clone display created: id=$displayId ${w}x$h")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "create clone display failed", t)
            status.value = "Could not create the clone display: ${t.message}"
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
            if (now - lastFrameAt < FRAME_INTERVAL_MS) return
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
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { reader?.close() }
            reader = null
            runCatching { vd?.release() }
            vd = null
            frameThread?.quitSafely()
            frameThread = null
            frame.value = null
            displayId = 0
        }, 500)
    }

    fun currentDisplayId(): Int = displayId

    // ---------------------------------------------------------------- launch

    fun launch(packageName: String) {
        if (!ensureDisplay()) return
        writeDisplayIdFile()
        val ok = runCatching { BlackBoxCore.get().launchApk(packageName, USER_ID) }
            .getOrDefault(false)
        if (ok) {
            running.value = packageName
            status.value = null
            appContext?.let { CloneSessionService.start(it) }
        } else {
            status.value = "Launch failed — Kill it, then Open again"
        }
    }

    // ---------------------------------------------------------------- input

    private fun displayArgs(): Array<String>? {
        val id = displayId
        if (id <= 0) return null
        return arrayOf("input", "-d", id.toString())
    }

    fun tap(x: Float, y: Float) {
        val base = displayArgs() ?: return
        val xi = x.roundToInt().coerceAtLeast(0)
        val yi = y.roundToInt().coerceAtLeast(0)
        scope.launch { ShellRunner.exec(*base, "tap", xi.toString(), yi.toString()) }
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int = 300) {
        val base = displayArgs() ?: return
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
        scope.launch { ShellRunner.exec(*base, "keyevent", keyCode) }
    }

    fun text(value: String) {
        if (value.isEmpty()) return
        val base = displayArgs() ?: return
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
