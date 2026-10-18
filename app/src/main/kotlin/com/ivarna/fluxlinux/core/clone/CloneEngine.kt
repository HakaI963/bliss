package com.ivarna.fluxlinux.core.clone

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.ivarna.fluxlinux.core.service.CloneSessionService
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Bliss clone space — shell-based (ShadowAuto model), no embedded engine.
 *
 * Apps are installed for an isolated Android user ("Bliss", created with
 * `pm create-user` through Shizuku/root) so they never show up in the phone's
 * launcher and keep their own data. They run on a hidden app-owned virtual
 * display; shell privileges (Shizuku UID has INTERNAL_SYSTEM_WINDOW) let `am
 * start --user N --display D` put any app on it, and `input -d D` drives it.
 * The phone screen stays free the whole time.
 */
object CloneEngine {

    private const val TAG = "BlissClone"
    private const val PREFS = "bliss_clone_cfg"
    private const val CLONE_USER_NAME = "Bliss"

    /** Resolution presets offered in Configure (portrait). */
    val RES_PRESETS: List<Pair<Int, Int>> = listOf(
        540 to 1170,
        720 to 1560,
        900 to 1950,
    )

    /** DPI choices in Configure; higher = smaller UI inside the clone display. */
    val DPI_PRESETS = listOf(280, 320, 360, 420, 480)

    data class Frame(val bitmap: Bitmap, val seq: Long)

    /** An app cloned into the Bliss space (the only thing the Apps tab shows). */
    data class CloneApp(
        val packageName: String,
        val label: String,
        val version: String,
        val icon: Bitmap?,
        val component: String?,
    )

    /** Apps already installed on the phone — only for Tools -> Clone -> App. */
    data class DeviceApp(val packageName: String, val label: String, val icon: Bitmap?)

    val apps = MutableStateFlow<List<CloneApp>>(emptyList())
    val deviceApps = MutableStateFlow<List<DeviceApp>>(emptyList())
    val frame = MutableStateFlow<Frame?>(null)
    val running = MutableStateFlow<String?>(null)
    val status = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val touchBackend = MutableStateFlow(ShellRunner.Backend.NONE)
    val cloneUserId = MutableStateFlow(0)
    val displayW = MutableStateFlow(720)
    val displayH = MutableStateFlow(1560)
    val logs = MutableStateFlow<List<String>>(emptyList())

    // Configure tab (persisted in SharedPreferences).
    val displayFps = MutableStateFlow(15)
    val displayResPreset = MutableStateFlow(1)
    val displayDpi = MutableStateFlow(360)

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
            displayDpi.value = p.getInt("dpi", 360)
            cloneUserId.value = p.getInt("clone_user", 0)
            val preset = RES_PRESETS[displayResPreset.value]
            displayW.value = preset.first
            displayH.value = preset.second
        }
        // No clone display can survive a process restart; a stale id would send
        // launches to a dead display, so always start clean.
        clearDisplayIdFile()
        log("Clone space ready (fps=${displayFps.value}, res=${displayW.value}x${displayH.value}, backend=${touchBackend.value.label})")
        // Agent surface: CLI + MCP server in host prefix and every proot distro,
        // plus the file-bridge watcher those tools talk to.
        CloneAgentDeployer.ensureInstalled(app)
        CloneBridge.start(app)
        refresh()
        refreshShell()
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

    /** Tail of the persisted clone log (Tools > Diagnostics). */
    fun engineLogTail(maxLines: Int = 120): List<String> {
        val f = logFile()
        if (!f.isFile) return emptyList()
        return runCatching { f.readLines().takeLast(maxLines) }.getOrDefault(emptyList())
    }

    // ---------------------------------------------------------------- shell + user

    fun refreshShell() {
        touchBackend.value = ShellRunner.backend()
    }

    private fun requireShell(): String? =
        if (ShellRunner.backend() == ShellRunner.Backend.NONE) {
            "Shizuku (or root) is needed — enable it in Tools → Setup"
        } else {
            null
        }

    /** True when the isolated clone user is available. */
    fun hasCloneUser(): Boolean = cloneUserId.value > 0

    fun cloneSpaceLabel(): String =
        if (cloneUserId.value > 0) "$CLONE_USER_NAME · user ${cloneUserId.value}" else "not created yet"

    /** When true, installs fall back to the main user if no clone user exists. */
    fun cloneFallbackMain(): Boolean = prefs?.getBoolean("fallback_main", false) ?: false

    fun setCloneFallbackMain(enabled: Boolean) {
        prefs?.edit()?.putBoolean("fallback_main", enabled)?.apply()
        log(if (enabled) "Clone fallback: main user allowed" else "Clone fallback: main user disabled")
    }

    private fun parseUsers(): List<Pair<Int, String>> =
        ShellRunner.exec("pm", "list", "users").stdout.lineSequence()
            .mapNotNull { line ->
                val m = Regex("""UserInfo\{(\d+):([^:}]*):""").find(line) ?: return@mapNotNull null
                m.groupValues[1].toIntOrNull()?.let { it to m.groupValues[2] }
            }
            .toList()

    private fun startUser(uid: Int): Boolean {
        val r = ShellRunner.exec("am", "start-user", "-w", uid.toString())
        val out = (r.stdout + " " + r.stderr).lowercase(Locale.ROOT)
        val ok = !out.contains("error") || out.contains("already")
        if (!ok) log("start-user $uid: ${r.combined.take(160)}")
        return ok
    }

    /**
     * Ensures the isolated "Bliss" Android user exists and is running.
     * Returns (userId, error): error != null is fatal unless the main-user
     * fallback is enabled (then the caller may retry with uid 0).
     */
    fun ensureCloneUserBlocking(): Pair<Int, String?> {
        val shellErr = requireShell()
        if (shellErr != null) return 0 to shellErr
        val cached = cloneUserId.value
        if (cached > 0 && startUser(cached)) return cached to null

        val existing = parseUsers().firstOrNull { it.second.equals(CLONE_USER_NAME, true) }
        if (existing != null) {
            cloneUserId.value = existing.first
            prefs?.edit()?.putInt("clone_user", existing.first)?.apply()
            return if (startUser(existing.first)) {
                existing.first to null
            } else {
                existing.first to "clone user ${existing.first} could not be started"
            }
        }

        val created = ShellRunner.exec("pm", "create-user", CLONE_USER_NAME)
        val id = Regex("""user id (\d+)""").find(created.combined)?.groupValues?.get(1)?.toIntOrNull()
        if (id == null) {
            val why = created.combined.ifBlank { "pm create-user failed" }
            log("User creation failed: ${why.take(200)}")
            return 0 to "Could not create the isolated clone user — ${why.take(160)}"
        }
        cloneUserId.value = id
        prefs?.edit()?.putInt("clone_user", id)?.apply()
        log("Clone user created: $CLONE_USER_NAME (id $id)")
        return if (startUser(id)) {
            id to null
        } else {
            id to "clone user $id created but could not be started"
        }
    }

    /** uid to install for: clone user, or 0 when the fallback is on. */
    private fun targetUserBlocking(): Pair<Int, String?> {
        val (uid, err) = ensureCloneUserBlocking()
        if (err == null) return uid to null
        if (cloneFallbackMain()) {
            log("Clone install fallback: using the main user ($err)")
            return 0 to null
        }
        return 0 to err
    }

    // ---------------------------------------------------------------- registry (clones only)

    fun refresh() {
        apps.value = loadRegistry()
            .sortedBy { it.label.lowercase(Locale.ROOT) }
    }

    private fun loadRegistry(): List<CloneApp> {
        val meta = loadMeta()
        val out = ArrayList<CloneApp>()
        val keys = meta.keys()
        while (keys.hasNext()) {
            val pkg = keys.next()
            val m = meta.optJSONObject(pkg) ?: continue
            out.add(
                CloneApp(
                    packageName = pkg,
                    label = m.optString("label").takeIf { it.isNotBlank() } ?: pkg,
                    version = m.optString("version").orEmpty(),
                    icon = loadIcon(m.optString("icon")),
                    component = m.optString("component").takeIf { it.isNotBlank() },
                )
            )
        }
        return out
    }

    // ---------------------------------------------------------------- device apps (Tools -> Clone -> App)

    fun refreshDeviceApps() {
        scope.launch {
            deviceApps.value = loadDeviceAppsBlocking()
        }
    }

    /** Launchable apps installed on the phone (used only to pick what to clone). */
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

    // ---------------------------------------------------------------- install (UI entry points)

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
                status.value = "Installing into the clone space…"
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
                val ext = if (clean.endsWith(".apks") || clean.endsWith(".xapk") || clean.endsWith(".apkm")) {
                    clean.substringAfterLast('.')
                } else {
                    "apk"
                }
                dest = File(dir, "download-" + System.currentTimeMillis() + "." + ext)
                downloadTo(url.trim(), dest)
                status.value = "Installing into the clone space…"
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

    /** Clone an app already installed on the phone into the Bliss space. */
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

    // ---------------------------------------------------------------- install (blocking core)

    /** Install a staged APK or split-set zip. Returns null on success. */
    fun installStagedBlocking(file: File): String? {
        val (uid, err) = targetUserBlocking()
        if (err != null) return err
        val name = file.name.lowercase(Locale.ROOT)
        return if (name.endsWith(".apks") || name.endsWith(".xapk") || name.endsWith(".apkm")) {
            installSplitZipBlocking(file, uid)
        } else {
            installSingleBlocking(file, uid)
        }
    }

    private fun installSingleBlocking(file: File, uid: Int): String? {
        if (!file.isFile) return "file not found: ${file.absolutePath}"
        val backend = ShellRunner.backend()
        val size = file.length()
        val res = when (backend) {
            ShellRunner.Backend.SHIZUKU -> ShellRunner.execWithStdin(
                file, "pm", "install", "--user", uid.toString(), "-r", "-S", size.toString(),
            )
            ShellRunner.Backend.ROOT -> {
                val tmp = "/data/local/tmp/bliss-clone/${file.name}"
                ShellRunner.exec("mkdir", "-p", "/data/local/tmp/bliss-clone")
                val cp = ShellRunner.exec("cp", file.absolutePath, tmp)
                if (!cp.ok) {
                    cp
                } else {
                    ShellRunner.exec("pm", "install", "--user", uid.toString(), "-r", tmp)
                        .also { ShellRunner.exec("rm", "-f", tmp) }
                }
            }
            ShellRunner.Backend.NONE -> ShellRunner.Result(-1, "", "no shell backend")
        }
        val failure = installFailure(res)
        if (failure != null) return failure
        return registerFromArchive(file)
    }

    /** .apks / .xapk / .apkm = zip with base.apk + split APKs. */
    private fun installSplitZipBlocking(zipFile: File, uid: Int): String? {
        val ctx = appContext ?: return "no context"
        val dir = File(ctx.cacheDir, "clone-splits").apply { mkdirs() }
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        val apks = ArrayList<File>()
        try {
            ZipInputStream(zipFile.inputStream().buffered()).use { zin ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    val n = entry.name
                    if (!n.endsWith(".apk", ignoreCase = true)) continue
                    val out = File(dir, n.substringAfterLast('/'))
                    out.outputStream().use { zin.copyTo(it) }
                    apks.add(out)
                }
            }
        } catch (t: Throwable) {
            return "could not read split set: ${t.message}"
        }
        if (apks.isEmpty()) return "split set contains no APKs"
        val base = apks.firstOrNull { it.name.equals("base.apk", true) } ?: apks.first()

        val create = ShellRunner.exec("pm", "install-create", "--user", uid.toString(), "-r")
        val session = Regex("""\[(\d+)]""").find(create.combined)?.groupValues?.get(1)
            ?: return "could not create install session: ${create.combined.take(160)}"
        for (apk in apks) {
            val w = if (ShellRunner.shizukuReady()) {
                ShellRunner.execWithStdin(
                    apk, "pm", "install-write", session, apk.name,
                )
            } else {
                ShellRunner.exec("pm", "install-write", session, apk.name, apk.absolutePath)
            }
            val failure = installFailure(w)
            if (failure != null) {
                ShellRunner.exec("pm", "install-abandon", session)
                return failure
            }
        }
        val commit = ShellRunner.exec("pm", "install-commit", session)
        val failure = installFailure(commit)
        if (failure != null) return failure
        return registerFromArchive(base)
    }

    /** Clone a phone-installed app (all its splits) into the clone space. */
    fun cloneFromDeviceBlocking(pkg: String): String? {
        val shellErr = requireShell()
        if (shellErr != null) return shellErr
        val (uid, err) = targetUserBlocking()
        if (err != null) return err
        val r = ShellRunner.exec("pm", "path", "--user", "0", pkg)
        val paths = r.stdout.lineSequence()
            .map { it.trim().removePrefix("package:") }
            .filter { it.isNotEmpty() }
            .toList()
        if (paths.isEmpty()) return "no APK path for $pkg"
        if (paths.size == 1) {
            val res = ShellRunner.exec("pm", "install", "--user", uid.toString(), "-r", paths[0])
            val failure = installFailure(res)
            if (failure != null) return failure
        } else {
            val create = ShellRunner.exec("pm", "install-create", "--user", uid.toString(), "-r")
            val session = Regex("""\[(\d+)]""").find(create.combined)?.groupValues?.get(1)
                ?: return "could not create install session: ${create.combined.take(160)}"
            for ((i, p) in paths.withIndex()) {
                val w = ShellRunner.exec("pm", "install-write", session, "split-$i.apk", p)
                val failure = installFailure(w)
                if (failure != null) {
                    ShellRunner.exec("pm", "install-abandon", session)
                    return failure
                }
            }
            val commit = ShellRunner.exec("pm", "install-commit", session)
            val failure = installFailure(commit)
            if (failure != null) return failure
        }
        // Metadata from the main-user install (same package).
        val ctx = appContext ?: return null
        val pm = ctx.packageManager
        runCatching {
            val ai = pm.getApplicationInfo(pkg, 0)
            val label = pm.getApplicationLabel(ai).toString()
            val icon = drawableToBitmap(pm.getApplicationIcon(ai), 144, 144)
            val comp = resolveLauncherComponent(pkg, uid)
            saveMeta(pkg, label, runCatching { pm.getPackageInfo(pkg, 0).versionName }.getOrNull().orEmpty(), icon, comp, uid)
        }
        return null
    }

    /** null when the install output means success, else a readable failure. */
    private fun installFailure(res: ShellRunner.Result): String? {
        val out = res.combined
        val failure = Regex("""Failure\s*\[?([^\]]*)]?""").find(out)?.groupValues?.get(1)?.trim()
        if (failure != null && failure.isNotEmpty()) return failure
        if (out.contains("Success", ignoreCase = true)) return null
        if (res.ok) return null
        return out.ifBlank { "install failed" }.take(240)
    }

    /** Read label / version / icon / launcher from a staged APK and register it. */
    private fun registerFromArchive(apk: File): String? {
        val ctx = appContext ?: return "no context"
        val pm = ctx.packageManager
        val info = runCatching {
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_ACTIVITIES)
        }.getOrNull() ?: return "installed, but the APK could not be read back"
        val ai = info.applicationInfo ?: return null
        ai.sourceDir = apk.absolutePath
        ai.publicSourceDir = apk.absolutePath
        val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(info.packageName)
        val icon = runCatching { drawableToBitmap(pm.getApplicationIcon(ai), 144, 144) }.getOrNull()
        val comp = resolveLauncherComponent(info.packageName, cloneUserId.value)
        saveMeta(info.packageName, label, info.versionName.orEmpty(), icon, comp, cloneUserId.value)
        return null
    }

    /** `cmd package query-activities --brief` for the clone user; main-user fallback. */
    private fun resolveLauncherComponent(pkg: String, uid: Int): String? {
        if (uid > 0) {
            val r = ShellRunner.exec(
                "cmd", "package", "query-activities", "--brief", "--user", uid.toString(),
                "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER",
            )
            val hit = r.stdout.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("$pkg/") }
            if (hit != null) return hit
        }
        return runCatching {
            appContext?.packageManager?.getLaunchIntentForPackage(pkg)?.component?.flattenToShortString()
        }.getOrNull()
    }

    // ---------------------------------------------------------------- uninstall / kill

    fun uninstall(packageName: String) {
        scope.launch {
            val err = withContext(Dispatchers.IO) { uninstallNowBlocking(packageName) }
            log(if (err == null) "Uninstalled $packageName" else "Uninstall failed: $err")
            if (err != null) status.value = err
            refresh()
        }
    }

    fun kill(packageName: String) {
        scope.launch {
            val err = withContext(Dispatchers.IO) { killNowBlocking(packageName) }
            status.value = err
        }
    }

    fun killNowBlocking(packageName: String): String? {
        val shellErr = requireShell()
        if (shellErr != null) return shellErr
        val uid = if (cloneUserId.value > 0) cloneUserId.value else 0
        val r = ShellRunner.exec("am", "force-stop", "--user", uid.toString(), packageName)
        log("Kill $packageName (user $uid): ${if (r.ok) "ok" else r.combined.take(120)}")
        if (running.value == packageName) {
            running.value = null
            appContext?.let { CloneSessionService.stop(it) }
        }
        return if (r.ok) null else r.combined.ifBlank { "kill failed" }
    }

    fun uninstallNowBlocking(packageName: String): String? {
        val shellErr = requireShell()
        if (shellErr != null) return shellErr
        val uid = if (cloneUserId.value > 0) cloneUserId.value else 0
        val r = ShellRunner.exec("pm", "uninstall", "--user", uid.toString(), packageName)
        val out = r.combined
        if (out.contains("Failure", ignoreCase = true)) return out.take(200)
        if (!r.ok && !out.contains("Success", ignoreCase = true)) return out.ifBlank { "uninstall failed" }
        removeMeta(packageName)
        if (running.value == packageName) {
            running.value = null
            appContext?.let { CloneSessionService.stop(it) }
        }
        return null
    }

    // ---------------------------------------------------------------- display

    fun frameIntervalMs(): Long = (1000L / displayFps.value.coerceIn(5, 60)).coerceAtLeast(16L)

    private fun hiddenDisplayFlag(name: String): Int = runCatching {
        DisplayManager::class.java.getField(name).getInt(null)
    }.getOrDefault(0)

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

            val base = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            val touch = hiddenDisplayFlag("VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH")
            val focus = hiddenDisplayFlag("VIRTUAL_DISPLAY_FLAG_OWN_FOCUS")
            val trusted = hiddenDisplayFlag("VIRTUAL_DISPLAY_FLAG_TRUSTED")
            // Same candidate ladder the shell-side reference implementation uses:
            // touch/focus/trusted flags make the display behave like a real
            // screen for the apps launched on it; fall back one step at a time.
            val candidates = listOf(
                base or touch or focus or trusted,
                base or touch or focus,
                base or touch,
                base,
            )
            var d: VirtualDisplay? = null
            var lastError: Throwable? = null
            for (flags in candidates) {
                try {
                    d = dm.createVirtualDisplay(
                        "bliss-clone", w, h, displayDpi.value.coerceIn(120, 640),
                        r.surface, flags,
                    )
                    if (d?.display != null) break
                } catch (t: Throwable) {
                    lastError = t
                }
            }
            if (d?.display == null) throw lastError ?: IllegalStateException("createVirtualDisplay returned null")
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
            scope.launch { runCatching { killNowBlocking(pkg) } }
        }
        // Give AMS a beat to finish the tasks before the display goes away, so a
        // live task can never migrate to the user's real screen.
        Handler(Looper.getMainLooper()).postDelayed({ closeDisplay() }, 500)
    }

    fun currentDisplayId(): Int = displayId

    // ---------------------------------------------------------------- configure

    /** Persists the Configure values and restarts the display (relaunches the running app). */
    fun applyDisplayConfig() {
        prefs?.edit()
            ?.putInt("fps", displayFps.value.coerceIn(5, 60))
            ?.putInt("res", displayResPreset.value.coerceIn(0, RES_PRESETS.size - 1))
            ?.putInt("dpi", displayDpi.value.coerceIn(120, 640))
            ?.apply()
        scope.launch {
            val pkg = running.value
            log("Applying display config (fps=${displayFps.value}, res=${RES_PRESETS[displayResPreset.value.coerceIn(0, RES_PRESETS.size - 1)]}, dpi=${displayDpi.value})")
            if (pkg != null) {
                runCatching { killNowBlocking(pkg) }
                running.value = null
            }
            withContext(Dispatchers.Main) { closeDisplay() }
            delay(700)
            if (pkg != null) {
                val err = withContext(Dispatchers.IO) { launchNowBlocking(pkg) }
                log(if (err == null) "Relaunched $pkg after display restart" else "Relaunch failed: $err")
            }
        }
    }

    // ---------------------------------------------------------------- launch

    fun launch(packageName: String) {
        scope.launch {
            val err = withContext(Dispatchers.IO) { launchNowBlocking(packageName) }
            if (err != null) status.value = err
        }
    }

    /** Blocking launch on the hidden display. Returns null on success. */
    fun launchNowBlocking(packageName: String): String? {
        val shellErr = requireShell()
        if (shellErr != null) return shellErr
        val (uid, err) = targetUserBlocking()
        if (err != null) return err
        val displayOk = runCatching {
            runBlocking { withContext(Dispatchers.Main) { ensureDisplay() } }
        }.getOrDefault(false)
        if (!displayOk) return "clone display unavailable"
        val id = currentDisplayId()
        if (id <= 0) return "no display id"
        val comp = resolveLauncherComponent(packageName, uid)
            ?: return "no launcher activity for $packageName"
        ShellRunner.exec("am", "force-stop", "--user", uid.toString(), packageName)
        val start = ShellRunner.exec(
            "am", "start", "--user", uid.toString(), "--display", id.toString(),
            "-f", "0x10008000", "-n", comp,
        )
        val out = start.combined
        if (out.contains("Error:", ignoreCase = true)) {
            log("Launch failed: ${out.take(240)}")
            return out.take(240)
        }
        running.value = packageName
        status.value = null
        appContext?.let { CloneSessionService.start(it) }
        log("Launch: $packageName (user $uid, display $id) → ${out.lineSequence().firstOrNull() ?: "ok"}")
        // Watchdog: report when nothing ever rendered on the hidden display.
        val seqAtLaunch = frame.value?.seq ?: -1L
        scope.launch {
            delay(20_000)
            if (running.value == packageName && (frame.value?.seq ?: -1L) == seqAtLaunch) {
                log("No frames after 20s for $packageName")
                status.value = "No picture yet — see Logs"
            }
        }
        return null
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
        // `input text` wants %s for spaces.
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

    /** Latest raw frame (agent API + UI). */
    fun latestFrameBitmap(): Bitmap? = frame.value?.bitmap

    // ---------------------------------------------------------------- agent API (CLI / MCP bridge)

    /** Blocking install from a path (absolute, or relative to the clone dir). */
    fun installApkNow(path: String): String? {
        val f = File(path)
        val real = if (f.isAbsolute) f else File(cloneDir(), path)
        if (!real.isFile) return "file not found: $path"
        log("Agent install: ${real.name} (${real.length()} bytes)")
        return installStagedBlocking(real)
    }

    /** Blocking download + install (agent API). */
    fun installUrlNow(url: String): String? {
        val ctx = appContext ?: return "no context"
        val clean = url.trim().substringBefore('?')
        val ext = if (clean.endsWith(".apks") || clean.endsWith(".xapk") || clean.endsWith(".apkm")) {
            clean.substringAfterLast('.')
        } else {
            "apk"
        }
        val dest = File(ctx.cacheDir, "clone-inbox/agent-" + System.currentTimeMillis() + "." + ext)
        return try {
            dest.parentFile?.mkdirs()
            downloadTo(url.trim(), dest)
            log("Agent download ok: ${dest.length()} bytes")
            installApkNow(dest.absolutePath)
        } catch (t: Throwable) {
            log("Agent download failed: ${t.message}")
            t.message ?: "download failed"
        } finally {
            runCatching { dest.delete() }
        }
    }

    /** Blocking clone-from-device for the agent API. */
    fun cloneDeviceNow(pkg: String): String? {
        log("Agent clone from device: $pkg")
        return cloneFromDeviceBlocking(pkg)
    }

    /** Blocking launch for the agent API. Returns null on success. */
    fun launchNow(packageName: String): String? = launchNowBlocking(packageName)

    /** Blocking kill for the agent API. Returns null on success. */
    fun killNow(packageName: String): String? = killNowBlocking(packageName)

    /** Blocking uninstall for the agent API. Returns null on success. */
    fun uninstallNow(packageName: String): String? = uninstallNowBlocking(packageName)

    /** Synchronous input injection for the agent API (input -d <display> ...). */
    fun inputSync(args: List<String>): Boolean {
        val base = displayArgs() ?: return false
        val r = ShellRunner.exec(*base, *args.toTypedArray())
        log("Agent input ${args.joinToString(" ")} -> ${if (r.ok) "ok" else "failed"}")
        return r.ok
    }

    /** status payload for the agent bridge (key=value lines). */
    fun statusLines(): String = buildString {
        append("backend=").append(touchBackend.value.name.lowercase(Locale.ROOT))
        append("\nclone_user=").append(if (cloneUserId.value > 0) cloneUserId.value.toString() else "-")
        append("\ndisplay=").append(displayId)
        append("\nsize=").append(displayW.value).append("x").append(displayH.value)
        append("\ndpi=").append(displayDpi.value)
        append("\nfps=").append(displayFps.value)
        append("\nrunning=").append(running.value ?: "-")
        append("\napps=").append(apps.value.size)
        append("\nframes=").append(frame.value?.seq ?: 0)
    }

    /** list payload for the agent bridge (pkg|label|version lines). */
    fun listLines(): String =
        apps.value.joinToString("\n") { "${it.packageName}|${it.label}|${it.version.ifEmpty { "-" }}" }

    // ---------------------------------------------------------------- meta store

    private fun cloneDir(): File = File(appContext?.filesDir ?: File("/"), "clone")

    private fun metaFile(): File = File(cloneDir(), "meta.json")

    private fun iconsDir(): File = File(cloneDir(), "icons")

    private fun loadMeta(): JSONObject =
        runCatching { JSONObject(metaFile().readText()) }.getOrDefault(JSONObject())

    private fun saveMeta(pkg: String, label: String, version: String, icon: Bitmap?, component: String?, uid: Int) {
        runCatching {
            val root = loadMeta()
            val entry = JSONObject()
            entry.put("label", label)
            entry.put("version", version)
            entry.put("user", uid)
            if (!component.isNullOrBlank()) entry.put("component", component)
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

    // ---------------------------------------------------------------- display id file

    private fun displayIdFile(): File = File(cloneDir(), "display.id")

    private fun clearDisplayIdFile() {
        runCatching { displayIdFile().delete() }
    }
}
