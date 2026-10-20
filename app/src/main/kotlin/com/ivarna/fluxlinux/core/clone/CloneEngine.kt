package com.ivarna.fluxlinux.core.clone

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.util.Log
import top.niunaijun.blackbox.BlackBoxCore
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Bliss app cloner — BlackBox-style virtual apps that run inside Bliss itself.
 *
 * Installed APKs live in the in-app engine (no Android user, no profile, no
 * Shizuku user service). Launching a clone starts its proxy activity in this
 * app, on the normal screen.
 */
object CloneEngine {

    private const val TAG = "BlissClone"
    private const val USER_ID = 0

    data class CloneApp(
        val packageName: String,
        val label: String,
        val version: String,
        val icon: Bitmap?,
    )

    data class DeviceApp(val packageName: String, val label: String, val icon: Bitmap?)

    val apps = MutableStateFlow<List<CloneApp>>(emptyList())
    val deviceApps = MutableStateFlow<List<DeviceApp>>(emptyList())
    val running = MutableStateFlow<String?>(null)
    val status = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val logs = MutableStateFlow<List<String>>(emptyList())

    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var initialized = false

    // ---------------------------------------------------------------- lifecycle

    fun init(app: Application) {
        if (initialized) return
        initialized = true
        appContext = app.applicationContext
        // A previous build may have written a virtual-display id here; the engine
        // must always launch on the normal display now.
        clearLegacyDisplayTarget()
        surfacePreviousCrash(app)
        if (!isMainProcess(app)) return
        log("In-app app cloner ready (BlackBox engine)")
    }

    private fun clearLegacyDisplayTarget() {
        val dir = appContext?.filesDir ?: return
        runCatching { File(dir, "clone/display.id").delete() }
    }

    @Volatile private var engineReady = false

    /** Initialise the in-app engine on first use (starts its daemon). */
    private fun ensureEngine(): String? {
        if (engineReady) return null
        synchronized(this) {
            if (engineReady) return null
            return try {
                BlackBoxCore.get().doCreate()
                engineReady = true
                null
            } catch (t: Throwable) {
                Log.e(TAG, "engine init failed", t)
                "Engine init failed: ${t.message ?: t.javaClass.simpleName}"
            }
        }
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

    fun log(msg: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        logs.value = (listOf("$stamp  $msg") + logs.value).take(300)
        Log.d(TAG, msg)
        appendLogFile("$stamp  $msg")
    }

    private fun logFile(): File = File(appContext?.filesDir ?: File("/"), "logs/clone.log")

    private fun appendLogFile(line: String) {
        runCatching {
            val f = logFile()
            f.parentFile?.mkdirs()
            if (f.length() > 256 * 1024) {
                val old = File(f.parentFile, "clone.old.log")
                old.delete()
                f.renameTo(old)
            }
            f.appendText(line + "\n")
        }
    }

    fun engineLogTail(maxLines: Int = 120): List<String> {
        val f = logFile()
        if (!f.isFile) return emptyList()
        return runCatching { f.readLines().takeLast(maxLines) }.getOrDefault(emptyList())
    }

    private fun surfacePreviousCrash(app: Application) {
        runCatching {
            val dir = com.ivarna.fluxlinux.core.system.CrashLog.dir(app)
            val latest = dir.listFiles()?.maxByOrNull { it.name } ?: return
            if (!latest.name.startsWith("crash-")) return
            val stack = latest.readLines().take(14)
            if (stack.isEmpty()) return
            log("--- PREVIOUS APP CRASH (${latest.name}) ---")
            for (line in stack) log(line.take(400))
            log("--- end previous app crash ---")
        }
    }

    fun clearLogs() {
        logs.value = emptyList()
        runCatching { logFile().delete() }
    }

    // ---------------------------------------------------------------- registry

    fun refresh() {
        // Unit/screenshot renders have no Application context; never start the
        // engine there.
        if (appContext == null) {
            apps.value = emptyList()
            return
        }
        val meta = loadMeta()
        val byPkg = LinkedHashMap<String, CloneApp>()
        val keys = meta.keys()
        while (keys.hasNext()) {
            val pkg = keys.next()
            val m = meta.optJSONObject(pkg) ?: continue
            byPkg[pkg] = CloneApp(
                packageName = pkg,
                label = m.optString("label").takeIf { it.isNotBlank() } ?: pkg,
                version = m.optString("version").orEmpty(),
                icon = loadIcon(m.optString("icon")),
            )
        }
        apps.value = byPkg.values.sortedBy { it.label.lowercase(Locale.ROOT) }
        scope.launch {
            if (ensureEngine() != null) return@launch
            runCatching {
                BlackBoxCore.get().getInstalledApplications(0, USER_ID).forEach { ai ->
                    val pkg = ai.packageName ?: return@forEach
                    if (!byPkg.containsKey(pkg)) {
                        byPkg[pkg] = CloneApp(
                            packageName = pkg,
                            label = runCatching {
                                ai.loadLabel(BlackBoxCore.getContext().packageManager).toString()
                            }.getOrDefault(pkg),
                            version = "",
                            icon = runCatching {
                                drawableToBitmap(
                                    ai.loadIcon(BlackBoxCore.getContext().packageManager), 144, 144,
                                )
                            }.getOrNull(),
                        )
                    }
                }
            }.onFailure { log("Engine list failed: ${it.message}") }
            apps.value = byPkg.values.sortedBy { it.label.lowercase(Locale.ROOT) }
        }
    }

    // ---------------------------------------------------------------- device apps

    fun refreshDeviceApps() {
        scope.launch { deviceApps.value = loadDeviceAppsBlocking() }
    }

    fun loadDeviceAppsBlocking(): List<DeviceApp> {
        val ctx = appContext ?: return emptyList()
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
        return list.asSequence()
            .filter { ri ->
                (ri.activityInfo.applicationInfo.flags and
                    android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0
            }
            .map { ri ->
                val ai = ri.activityInfo.applicationInfo
                DeviceApp(
                    packageName = ai.packageName,
                    label = runCatching { pm.getApplicationLabel(ai).toString() }
                        .getOrDefault(ai.packageName),
                    icon = runCatching { drawableToBitmap(ai.loadIcon(pm), 96, 96) }.getOrNull(),
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase(Locale.ROOT) }
            .toList()
    }

    fun deviceAppLines(): String {
        val list = loadDeviceAppsBlocking()
        deviceApps.value = list
        return list.joinToString("\n") { "${it.packageName}|${it.label}" }
    }

    // ---------------------------------------------------------------- install (UI)

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
                    return@launch
                }
                input.use { ins -> FileOutputStream(dest).use { ins.copyTo(it) } }
                status.value = "Installing in the app engine…"
                val err = installStagedBlocking(dest)
                status.value = err ?: "Installed"
                log(if (err == null) "Install ok: ${dest.name}" else "Install failed: $err")
                if (err == null) refresh()
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
                val clean = url.trim().substringBefore('?')
                val ext = if (clean.endsWith(".apks") || clean.endsWith(".xapk") ||
                    clean.endsWith(".apkm")
                ) {
                    clean.substringAfterLast('.')
                } else {
                    "apk"
                }
                dest = File(dir, "download-" + System.currentTimeMillis() + "." + ext)
                downloadTo(url.trim(), dest)
                status.value = "Installing in the app engine…"
                val err = installStagedBlocking(dest)
                status.value = err ?: "Installed"
                log(if (err == null) "Install ok: ${dest.name}" else "Install failed: $err")
                if (err == null) refresh()
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

    fun cloneFromDevice(pkg: String) {
        scope.launch {
            busy.value = true
            status.value = "Cloning $pkg…"
            val err = withContext(Dispatchers.IO) { cloneFromDeviceBlocking(pkg) }
            status.value = err ?: "Cloned $pkg"
            log(if (err == null) "Cloned from device: $pkg" else "Clone failed: $err")
            busy.value = false
            refresh()
        }
    }

    private fun downloadTo(url: String, dest: File) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 300_000
            instanceFollowRedirects = true
        }
        try {
            conn.inputStream.use { ins -> FileOutputStream(dest).use { ins.copyTo(it) } }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    // ---------------------------------------------------------------- install (core)

    /** Install a staged APK. Returns null on success. */
    fun installStagedBlocking(file: File): String? {
        if (!file.isFile) return "file not found: ${file.absolutePath}"
        val kind = classifyApk(file)
        if (kind == ApkKind.SPLIT) {
            return "Split bundles (.apks/.xapk/.apkm) are not supported by the in-app engine — use a single .apk"
        }
        val kindErr = kindError(kind, file)
        if (kindErr != null) return kindErr
        ensureEngine()?.let { return it }
        return try {
            val result = BlackBoxCore.get().installPackageAsUser(file, USER_ID)
            if (!result.success) {
                result.msg ?: "engine install failed"
            } else {
                log("Engine installed ${result.packageName}")
                registerFromArchive(file)
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "engine install failed", t)
            "engine install failed: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    fun cloneFromDeviceBlocking(pkg: String): String? {
        ensureEngine()?.let { return it }
        return try {
            val result = BlackBoxCore.get().installPackageAsUser(pkg, USER_ID)
            if (!result.success) {
                result.msg ?: "engine install failed"
            } else {
                registerFromSystem(pkg)
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "clone from device failed", t)
            "engine install failed: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    private enum class ApkKind { SINGLE, SPLIT, WEBPAGE, UNKNOWN }

    private fun classifyApk(file: File): ApkKind {
        if (!file.isFile || file.length() < 8L) return ApkKind.UNKNOWN
        val head = try {
            file.inputStream().use { readFully(it, 4) }
        } catch (t: Throwable) {
            return ApkKind.UNKNOWN
        }
        if (head.size < 4 || head[0] != 'P'.code.toByte() || head[1] != 'K'.code.toByte()) {
            val text = try {
                file.inputStream().use { readFully(it, 256) }.toString(Charsets.UTF_8).lowercase()
            } catch (t: Throwable) {
                ""
            }
            return if (text.contains("<!doctype html") || text.contains("<html") ||
                text.contains("{\"error\"") || text.contains("<title>")
            ) {
                ApkKind.WEBPAGE
            } else {
                ApkKind.UNKNOWN
            }
        }
        return try {
            java.util.zip.ZipFile(file).use { zip ->
                val names = zip.entries().asSequence().map { it.name.lowercase() }.toList()
                val single = names.contains("androidmanifest.xml") && names.any { it == "resources.arsc" }
                val bundle = names.any { it.endsWith(".apk") } || names.contains("manifest.json")
                when {
                    single -> ApkKind.SINGLE
                    bundle -> ApkKind.SPLIT
                    else -> ApkKind.SINGLE
                }
            }
        } catch (t: Throwable) {
            ApkKind.UNKNOWN
        }
    }

    private fun readFully(input: java.io.InputStream, max: Int): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        var got = 0
        while (got < max) {
            val n = input.read(chunk, 0, minOf(chunk.size, max - got))
            if (n <= 0) break
            buf.write(chunk, 0, n)
            got += n
        }
        return buf.toByteArray()
    }

    private fun kindError(kind: ApkKind, file: File): String? = when (kind) {
        ApkKind.WEBPAGE ->
            "That looks like a web page, not an app — copy the direct .apk link"
        ApkKind.UNKNOWN ->
            "Not an app package (.apk): ${file.name}"
        else -> null
    }

    /** Read label / version / icon from the staged APK and cache them. */
    private fun registerFromArchive(apk: File) {
        val ctx = appContext ?: return
        val pm = ctx.packageManager
        val info = runCatching {
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_ACTIVITIES)
        }.getOrNull() ?: return
        val ai = info.applicationInfo ?: return
        ai.sourceDir = apk.absolutePath
        ai.publicSourceDir = apk.absolutePath
        val label = runCatching { pm.getApplicationLabel(ai).toString() }
            .getOrDefault(info.packageName)
        val icon = runCatching { drawableToBitmap(pm.getApplicationIcon(ai), 144, 144) }.getOrNull()
        saveMeta(info.packageName, label, info.versionName.orEmpty(), icon)
    }

    private fun registerFromSystem(pkg: String) {
        val ctx = appContext ?: return
        val pm = ctx.packageManager
        runCatching {
            val ai = pm.getApplicationInfo(pkg, 0)
            val label = pm.getApplicationLabel(ai).toString()
            val icon = drawableToBitmap(pm.getApplicationIcon(ai), 144, 144)
            val version = pm.getPackageInfo(pkg, 0).versionName.orEmpty()
            saveMeta(pkg, label, version, icon)
        }
    }

    // ---------------------------------------------------------------- launch / stop

    /** Starts a clone inside Bliss. Returns null on success. */
    suspend fun launchNowBlocking(packageName: String): String? {
        clearLegacyDisplayTarget()
        ensureEngine()?.let { return it }
        return try {
            val ok = BlackBoxCore.get().launchApk(packageName, USER_ID)
            if (!ok) {
                log("Launch $packageName: engine returned false")
                "The engine could not start $packageName"
            } else {
                running.value = packageName
                status.value = "Starting $packageName inside Bliss…"
                log("Launch: $packageName (in-app engine)")
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "launch failed", t)
            "Launch failed: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    fun launch(packageName: String) {
        scope.launch {
            val err = withContext(Dispatchers.IO) { launchNowBlocking(packageName) }
            if (err != null) status.value = err
        }
    }

    fun killNowBlocking(packageName: String): String? {
        ensureEngine()?.let { return it }
        return try {
            BlackBoxCore.get().stopPackage(packageName, USER_ID)
            if (running.value == packageName) running.value = null
            log("Stopped $packageName")
            null
        } catch (t: Throwable) {
            Log.w(TAG, "kill failed", t)
            "Stop failed: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    fun kill(packageName: String) {
        scope.launch {
            val err = withContext(Dispatchers.IO) { killNowBlocking(packageName) }
            status.value = err
        }
    }

    fun uninstallNowBlocking(packageName: String): String? {
        ensureEngine()?.let { return it }
        return try {
            BlackBoxCore.get().uninstallPackageAsUser(packageName, USER_ID)
            removeMeta(packageName)
            if (running.value == packageName) running.value = null
            log("Uninstalled $packageName")
            null
        } catch (t: Throwable) {
            Log.w(TAG, "uninstall failed", t)
            "Uninstall failed: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    fun uninstall(packageName: String) {
        scope.launch {
            val err = withContext(Dispatchers.IO) { uninstallNowBlocking(packageName) }
            if (err != null) status.value = err
            log(if (err == null) "Uninstalled $packageName" else "Uninstall failed: $err")
            refresh()
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
        return runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
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
}
