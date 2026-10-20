package com.ivarna.fluxlinux.core.clone

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.util.Log
import com.ivarna.fluxlinux.clone.ShellVd
import com.ivarna.fluxlinux.BuildConfig
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Bliss clone space — shell-side (ShadowAuto model), nothing runs in the app.
 *
 * The hidden virtual display is created and owned by the **shell process**
 * (Shizuku UserService, uid 2000). The shell also injects input into that
 * display and starts apps on it. The app only streams frames in and sends
 * commands out, so an activity can never appear on the phone screen and input
 * can never land there either.
 *
 * Cloned apps are installed for an isolated Android user ("Bliss") so they
 * never show up in the phone's launcher and keep their own data.
 */
object CloneEngine {

    /** Serializes display ensure across probe/launch/heal callers. */
    private val ensureMutex = Mutex()

    private const val TAG = "BlissClone"

    init {
        // The shell service streams its own log lines here live (placements,
        // pump heartbeat, reject reasons) so the pane shows its side directly.
        ShellVd.logSink = { line -> shellLine(line)?.let { log("  shell: $it") } }
    }

    /** Recent shell lines already shown, so ring re-dumps do not duplicate. */
    private val shownShellLines = java.util.LinkedHashSet<String>()

    private fun shellLine(line: String): String? {
        synchronized(shownShellLines) {
            if (!shownShellLines.add(line)) {
                return null
            }
            while (shownShellLines.size > 400) {
                val it = shownShellLines.iterator()
                if (it.hasNext()) {
                    it.next()
                    it.remove()
                }
            }
        }
        return line
    }

    /** Dumps unseen service log lines into the pane, previous dumps excluded. */
    fun logShellTail(limit: Int) {
        for (line in ShellVd.serviceLogTail(limit) ?: emptyList()) {
            shellLine(line)?.let { log("  shell: $it") }
        }
    }
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

    /** Apps already installed on the phone — only for the agent's clone_device. */
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
    val displayFps = MutableStateFlow(10)
    val displayResPreset = MutableStateFlow(1)
    val displayDpi = MutableStateFlow(360)

    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var initialized = false

    // ---------------------------------------------------------------- lifecycle

    fun init(app: Application) {
        if (initialized) return
        initialized = true
        appContext = app.applicationContext
        touchBackend.value = ShellRunner.backend()
        surfacePreviousCrash(app)
        if (!isMainProcess(app)) return
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { p ->
            displayFps.value = p.getInt("fps", 10).coerceIn(5, 30)
            displayResPreset.value = p.getInt("res", 1).coerceIn(0, RES_PRESETS.size - 1)
            displayDpi.value = p.getInt("dpi", 360)
            cloneUserId.value = p.getInt("clone_user", 0)
            val preset = RES_PRESETS[displayResPreset.value]
            displayW.value = preset.first
            displayH.value = preset.second
        }
        // Mirror the shell-side frames into the UI state. Nothing is rendered
        // or injected by the app process, so input can never leak to the real
        // screen.
        scope.launch {
            ShellVd.frames.collect { bmp ->
                frame.value = bmp?.let { Frame(it, ShellVd.framesSeq.value) }
            }
        }
        log("Clone space ready (fps=${displayFps.value}, res=${displayW.value}x${displayH.value}, backend=${touchBackend.value.label})")
        // In-app AI agent: observe the live display (OCR / optional vision),
        // ask the configured model for one action, inject it into the clone
        // display. No MCP server, no external bridge.
        CloneAi.init(app)
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

    /** Newest-first ring buffer shown in Apps > Logs. */
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

    /** Tail of the persisted clone log (Diagnostics / Logs tab). */
    fun engineLogTail(maxLines: Int = 120): List<String> {
        val f = logFile()
        if (!f.isFile) return emptyList()
        return runCatching { f.readLines().takeLast(maxLines) }.getOrDefault(emptyList())
    }

    /** Adds the newest persisted app crash to the pane, if any. */
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

    /** Wipes the activity pane (Logs card clear icon). */
    fun clearLogs() {
        logs.value = emptyList()
        runCatching { logFile().delete() }
    }

    // ---------------------------------------------------------------- shell + user

    fun refreshShell() {
        touchBackend.value = ShellRunner.backend()
    }

    private fun requireShell(): String? =
        if (ShellRunner.backend() == ShellRunner.Backend.NONE) {
            "Shizuku (or root) is needed — enable it, then try again"
        } else {
            null
        }

    /** True when the isolated clone user exists. */
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

    // ---------------------------------------------------------------- device apps (agent API)

    fun refreshDeviceApps() {
        scope.launch {
            deviceApps.value = loadDeviceAppsBlocking()
        }
    }

    /** Launchable apps installed on the phone (used only by the agent's clone_device). */
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
        val kindErr = kindError(classifyApk(file), file)
        if (kindErr != null) return kindErr
        val (uid, err) = targetUserBlocking()
        if (err != null) return err
        val name = file.name.lowercase(Locale.ROOT)
        val kind = classifyApk(file)
        return if (kind == ApkKind.SPLIT ||
            (kind != ApkKind.SINGLE &&
                (name.endsWith(".apks") || name.endsWith(".xapk") || name.endsWith(".apkm")))
        ) {
            installSplitZipBlocking(file, uid)
        } else {
            installSingleBlocking(file, uid)
        }
    }

    private fun installSingleBlocking(file: File, uid: Int): String? {
        if (!file.isFile) return "file not found: ${file.absolutePath}"
        val ctx = appContext
        // 1) Binder-staged install via the shell user service (no stdin piping).
        if (ctx != null) {
            val via = installViaUserService(ctx, listOf(file), uid)
            if (via == "#fallback") {
                // Service bind failed → legacy stdin path below.
            } else if (via == null) {
                return registerFromArchive(file) // installed
            } else {
                return via // pm gave an error
            }
        }
        // 2) Legacy path: stream over Shizuku stdin (kept for old Shizuku versions).
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

    /**
     * Uploads the APKs to /data/local/tmp over binder and runs one
     * `pm install --user N -r <paths...>` from the shell. Returns:
     * null = success, an install error string, or "#fallback" when the user
     * service is unavailable (caller retries the legacy stdin path).
     */
    private fun installViaUserService(ctx: Context, files: List<File>, uid: Int): String? {
        val bindErr = ShellVd.ensureService(ctx)
        if (bindErr != null) {
            log("Stage transport unavailable: $bindErr")
            return "#fallback"
        }
        val remotes = ArrayList<String>(files.size)
        try {
            for ((i, f) in files.withIndex()) {
                val name = "clone-" + System.currentTimeMillis() + "-" + i + "-" + f.name
                val err = ShellVd.upload(f, name)
                if (err != null) {
                    log("Stage upload failed: $err")
                    return "#fallback"
                }
                remotes.add(ShellVd.stagePath(name))
            }
            log("Staged ${files.size} apk(s) over binder, installing…")
            return ShellVd.pmInstall(remotes, uid)
        } finally {
            if (remotes.isNotEmpty()) ShellVd.deleteStage(remotes)
        }
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

        if (ctx != null) {
            // Binder-staged multi-path install (base order first).
            val ordered = apks.sortedBy { if (it == base) 0 else 1 }
            val r = installViaUserService(ctx, ordered, uid)
            if (r == null) return registerFromArchive(base)
            if (r != "#fallback") return r
        }
        val create = ShellRunner.exec("pm", "install-create", "--user", uid.toString(), "-r")
        val session = Regex("""\[(\d+)]""").find(create.combined)?.groupValues?.get(1)
            ?: return "could not create install session: ${create.combined.take(160)}"
        for (apk in apks) {
            val w = if (ShellRunner.shizukuReady()) {
                ShellRunner.execWithStdin(apk, "pm", "install-write", session, apk.name)
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
            saveMeta(pkg, label, runCatching { pm.getPackageInfo(pkg, 0).versionName }.getOrNull().orEmpty(), icon, null, uid)
        }
        return null
    }

    /** What the staged file actually is (content-based, not extension-based). */
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
        // A real APK carries resources.arsc; a bundle (.xapk/.apks) carries apk members.
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

    /** minSdk-safe bounded read (readNBytes is API 33+). */
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
            "That looks like a web page, not an app — open the page and copy the direct .apk/.xapk link"
        ApkKind.UNKNOWN ->
            "Not an app package (.apk / .xapk / .apks): ${file.name}"
        else -> null
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

    /** Read label / version / icon from a staged APK and register it. */
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
        saveMeta(info.packageName, label, info.versionName.orEmpty(), icon, null, cloneUserId.value)
        return null
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
        val err = ShellVd.kill(packageName, uid)
        log("Kill $packageName (user $uid): ${err ?: "ok"}")
        if (running.value == packageName) {
            running.value = null
            appContext?.let { CloneSessionService.stop(it) }
            // Don't keep the whole isolated user alive for nothing: it costs
            // battery and heat on a phone just sitting in a pocket.
            if (uid > 0) {
                scope.launch { runCatching { ShellRunner.exec("am", "stop-user", "-f", uid.toString()) } }
            }
        }
        return err
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

    /** Virtual display availability line for the Display tab. */
    val vdProbe = MutableStateFlow<String?>(null)
    private var probeRunning = false

    /** Display id owned by the shell service (0 = none). */
    private val engineDisplayId = MutableStateFlow(0)

    /** Probes the shell-side hidden display and returns a human verdict. */
    fun probeDisplay() {
        if (probeRunning) return
        probeRunning = true
        vdProbe.value = "Virtual display: checking…"
        scope.launch {
            vdProbe.value = withContext(Dispatchers.IO) { probeDisplayBlocking() }
            probeRunning = false
        }
    }

    private suspend fun probeDisplayBlocking(): String {
        val shellErr = requireShell()
        if (shellErr != null) return "Virtual display: unavailable — $shellErr"
        engineDisplayId.value.takeIf { it > 0 }?.let { return "Virtual display: available (id=$it)" }
        val ok = ensureDisplay()
        return if (ok) {
            "Virtual display: available (id=${engineDisplayId.value})"
        } else {
            val tail = ShellVd.serviceLogTail(4) ?: emptyList()
            val cause = tail.lastOrNull { it.isNotBlank() }?.let { " — " + it.trim() } ?: ""
            "Virtual display: NOT available$cause"
        }
    }

    // ---------------------------------------------------------------- display (shell-side)

    /**
     * Creates the hidden display inside the Shizuku (shell) process. Blocking —
     * always called from the engine's IO scope, never from the UI thread.
     * Serialized: concurrent probe/launch/heal callers must not create
     * displays in parallel (the device saw ids 19..22 appear in one second).
     */
    suspend fun ensureDisplay(): Boolean {
        val ctx = appContext ?: return false
        val preset = RES_PRESETS[displayResPreset.value.coerceIn(0, RES_PRESETS.size - 1)]
        displayW.value = preset.first
        displayH.value = preset.second
        ensureMutex.withLock {
            // Reuse a live display; re-creating on every probe/run leaks
            // display ids. Re-checked inside the lock.
            val remote = ShellVd.remoteDisplayId()
            if (remote > 0) {
                if (engineDisplayId.value != remote) engineDisplayId.value = remote
                return true
            }
            val err = withContext(Dispatchers.IO) {
                ShellVd.ensure(
                    ctx,
                    preset.first,
                    preset.second,
                    displayDpi.value.coerceIn(120, 640),
                    displayFps.value.coerceIn(5, 30),
                )
            }
            if (err != null) {
                status.value = err
                log("Display failed: $err")
                logShellTail(12)
                return false
            }
            engineDisplayId.value = ShellVd.displayId.value
            log("Display ready: id=${engineDisplayId.value} ${preset.first}x${preset.second} dpi=${displayDpi.value} fps=${displayFps.value}")
        }
        return true
    }

    fun currentDisplayId(): Int = ShellVd.displayId.value

    /** True when the shell service still owns a live clone display. */
    fun displayAlive(): Boolean = ShellVd.remoteDisplayId() > 0

    private suspend fun closeDisplay() {
        withContext(Dispatchers.IO) { ShellVd.stopFrames() }
        withContext(Dispatchers.IO) { ShellVd.releaseDisplay() }
        engineDisplayId.value = 0
        frame.value = null
    }

    fun releaseAll() {
        val pkg = running.value
        running.value = null
        appContext?.let { CloneSessionService.stop(it) }
        scope.launch {
            if (pkg != null) {
                runCatching { killNowBlocking(pkg) }
            }
            runCatching { closeDisplay() }
        }
    }

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
            runCatching { closeDisplay() }
            delay(700)
            if (pkg != null) {
                val err = launchNowBlocking(pkg)
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
    suspend fun launchNowBlocking(packageName: String): String? {
        val shellErr = requireShell()
        if (shellErr != null) return shellErr
        val (uid, err) = targetUserBlocking()
        if (err != null) return err
        if (!ensureDisplay()) return status.value ?: "hidden display unavailable"
        val launchErr = withContext(Dispatchers.IO) { ShellVd.launch(packageName, uid) }
        if (!launchErr.isNullOrEmpty()) {
            logShellTail(12)
        }
        if (launchErr != null) {
            log("Launch failed: ${launchErr.take(240)}")
            return launchErr.take(240)
        }
        running.value = packageName
        status.value = null
        appContext?.let { CloneSessionService.start(it) }
        withContext(Dispatchers.IO) { ShellVd.startFrames() }
        log("Launch: $packageName (user $uid, display ${ShellVd.displayId.value})")
        // Watchdog: when nothing ever rendered on the hidden display, dump the
        // service's own log tail (the shell-side truth) into the pane and
        // self-heal a shell service that lost (or never kept) its display.
        val seqAtLaunch = ShellVd.framesSeq.value
        scope.launch {
            delay(12_000)
            if (running.value != packageName || ShellVd.framesSeq.value != seqAtLaunch) return@launch
            val healed = withContext(Dispatchers.IO) { healLostDisplayNow(packageName) }
            log("No still frame after 12s for $packageName" + (healed?.let { " — $it" } ?: ""))
            logShellTail(15)
            status.value = "No picture yet — see Logs"
        }
        return null
    }

    /** Re-creates the display and re-launches once if the shell service lost it. */
    private suspend fun healLostDisplayNow(packageName: String): String? {
        if (ShellVd.remoteDisplayId() > 0) return null
        log("Hidden display lost (service restarted) — re-creating…")
        engineDisplayId.value = 0
        if (!ensureDisplay()) return "display re-creation failed"
        val (uid, err) = targetUserBlocking()
        if (err != null) return "relaunch failed: $err"
        val launchErr = withContext(Dispatchers.IO) { ShellVd.launch(packageName, uid) }
        if (!launchErr.isNullOrEmpty()) return "relaunch failed: $launchErr"
        withContext(Dispatchers.IO) { ShellVd.startFrames() }
        return "re-created and re-launched (display ${ShellVd.displayId.value})"
    }

    // ---------------------------------------------------------------- input (shell-side)

    /** Android key codes for the on-screen buttons (input never leaves the shell display). */
    private fun keyCodeOf(name: String): Int = when (name.uppercase(Locale.ROOT)) {
        "BACK" -> 4
        "HOME" -> 3
        "ENTER" -> 66
        "TAB" -> 61
        "SEARCH" -> 84
        "DEL", "DELETE" -> 67
        "APP_SWITCH", "RECENTS" -> 187
        else -> name.toIntOrNull() ?: 4
    }

    fun tap(x: Float, y: Float) {
        val xi = x.roundToInt().coerceAtLeast(0)
        val yi = y.roundToInt().coerceAtLeast(0)
        log("Tap $xi,$yi")
        scope.launch {
            val err = withContext(Dispatchers.IO) { ShellVd.tap(xi, yi) }
            if (err != null) log("Tap failed: $err")
        }
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int = 300) {
        log("Swipe ${x1.roundToInt()},${y1.roundToInt()} → ${x2.roundToInt()},${y2.roundToInt()}")
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                ShellVd.swipe(
                    x1.roundToInt(), y1.roundToInt(), x2.roundToInt(), y2.roundToInt(), durationMs,
                )
            }
            if (err != null) log("Swipe failed: $err")
        }
    }

    fun key(keyCode: String) {
        log("Key $keyCode")
        scope.launch {
            val err = withContext(Dispatchers.IO) { ShellVd.key(keyCodeOf(keyCode)) }
            if (err != null) log("Key failed: $err")
        }
    }

    /** Maps Bliss' key names to the engine's key names (or raw key codes). */
    private fun keyNameOf(name: String): String = when (name.uppercase(Locale.ROOT)) {
        "BACK" -> "back"
        "ENTER" -> "enter"
        "SEARCH" -> "search"
        "TAB" -> "tab"
        else -> keyCodeOf(name).toString()
    }

    fun text(value: String) {
        if (value.isEmpty()) return
        log("Text ${value.length} chars")
        scope.launch {
            val err = withContext(Dispatchers.IO) { ShellVd.text(value) }
            if (err != null) log("Text failed: $err")
        }
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

    // ------------------------------------------------------- install from agent

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
    suspend fun launchNow(packageName: String): String? = launchNowBlocking(packageName)

    /** Blocking kill for the agent API. Returns null on success. */
    fun killNow(packageName: String): String? = killNowBlocking(packageName)

    /** Blocking uninstall for the agent API. Returns null on success. */
    fun uninstallNow(packageName: String): String? = uninstallNowBlocking(packageName)

    // ------------------------------------------------------------- AI actions

    /** Synchronous tap on the clone display (AI agent). */
    fun tapSync(x: Int, y: Int): Boolean = ShellVd.tap(x, y) == null

    fun longPressSync(x: Int, y: Int, ms: Int): Boolean = ShellVd.longPress(x, y, ms) == null

    fun swipeSync(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int = 320): Boolean =
        ShellVd.swipe(x1, y1, x2, y2, ms) == null

    fun keySync(name: String): Boolean = ShellVd.key(keyCodeOf(name)) == null

    fun textSync(value: String): Boolean = ShellVd.text(value) == null

    /** Fresh full-resolution JPEG of the clone display (AI observations). */
    fun screenshotJpeg(maxWidth: Int = displayW.value, quality: Int = 85): ByteArray? =
        ShellVd.screenshot(maxWidth, quality)

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
}
