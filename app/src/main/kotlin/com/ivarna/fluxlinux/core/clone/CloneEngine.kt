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
import com.ivarna.fluxlinux.core.clone.ShellRunner
import com.ivarna.fluxlinux.core.utils.RootUtils
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Bliss app cloner — installs apps into an isolated Android user ("Bliss")
 * so they get their own data and never appear in the phone's launcher.
 *
 * No virtual display and no in-app automation: this is only clone management
 * (install / list / remove / stop) plus starting the cloned app in its user.
 */
object CloneEngine {

    private const val TAG = "BlissClone"
    private const val PREFS = "bliss_clone_cfg"
    private const val CLONE_USER_NAME = "Bliss"
    private const val STAGE_DIR = "/data/local/tmp/bliss-clone"

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
    val running = MutableStateFlow<String?>(null)
    val status = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val touchBackend = MutableStateFlow(ShellRunner.Backend.NONE)
    val cloneUserId = MutableStateFlow(0)
    val logs = MutableStateFlow<List<String>>(emptyList())

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
            cloneUserId.value = p.getInt("clone_user", 0)
        }
        log("App cloner ready (backend=${touchBackend.value.label})")
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
        // A started secondary user can still be locked. Activities from a
        // locked user never get a window, so the clone display stays black.
        val unlock = ShellRunner.exec("am", "unlock-user", uid.toString())
        val unlockOut = (unlock.stdout + " " + unlock.stderr).trim()
        if (unlockOut.isNotEmpty() && !unlockOut.lowercase(Locale.ROOT).contains("not locked")) {
            log("unlock-user $uid: ${unlockOut.take(160)}")
        }
        waitUnlocked(uid)
        return ok
    }

    private fun waitUnlocked(uid: Int) {
        repeat(8) { attempt ->
            val dump = try {
                ShellRunner.exec("dumpsys", "user").stdout
            } catch (ignored: Throwable) {
                ""
            }
            val marker = "UserInfo{$uid:"
            val at = dump.indexOf(marker)
            val text = if (at >= 0) {
                dump.substring(at, minOf(dump.length, at + 300))
                    .replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
            } else {
                ""
            }
            if (text.contains("UNLOCKED")) {
                if (attempt > 0) log("user $uid unlocked")
                log("user $uid: ${text.take(220)}")
                return
            }
            if (attempt > 0) Thread.sleep(200)
        }
        log("user $uid: still locked after unlock request")
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
        if (ShellRunner.backend() == ShellRunner.Backend.NONE) {
            return "Shizuku (or root) is needed to install into the clone user"
        }
        val dir = STAGE_DIR
        ShellRunner.exec("mkdir", "-p", dir)
        val remote = dir + "/clone-" + System.currentTimeMillis() + "-" +
            file.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val up = stageForInstall(file, remote)
        if (!up.ok) {
            return "could not stage APK: ${up.combined.ifBlank { "shell error" }.take(200)}"
        }
        return try {
            val res = ShellRunner.exec("pm", "install", "--user", uid.toString(), "-r", remote)
            val failure = installFailure(res)
            if (failure != null) failure else registerFromArchive(file)
        } finally {
            ShellRunner.exec("rm", "-f", remote)
        }
    }

    /**
     * Stage an APK where the shell can read it. Shizuku streams it through a
     * shell process; root copies it directly (root can read app-private files).
     */
    private fun stageForInstall(file: File, remote: String): ShellRunner.Result {
        if (ShellRunner.shizukuReady()) {
            val r = ShellRunner.copyToShell(file, remote)
            if (r.ok) return r
            log("Shizuku staging failed: ${r.combined.take(160)}")
        }
        if (RootUtils.isRootAvailable()) {
            return ShellRunner.execRoot("cp", file.absolutePath, remote)
        }
        return ShellRunner.Result(-1, "", "no shell backend")
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
        val remotes = ArrayList<String>(apks.size)
        try {
            ShellRunner.exec("mkdir", "-p", STAGE_DIR)
            for ((i, apk) in apks.withIndex()) {
                val remote = STAGE_DIR + "/split-" + i + "-" + apk.name
                val up = stageForInstall(apk, remote)
                if (!up.ok) {
                    ShellRunner.exec("pm", "install-abandon", session)
                    return "could not stage split: ${up.combined.take(160)}"
                }
                remotes.add(remote)
            }
            for ((i, apk) in apks.withIndex()) {
                val w = ShellRunner.exec(
                    "pm", "install-write", session, apk.name, remotes[i],
                )
                val failure = installFailure(w)
                if (failure != null) {
                    ShellRunner.exec("pm", "install-abandon", session)
                    return failure
                }
            }
            val commit = ShellRunner.exec("pm", "install-commit", session)
            val failure = installFailure(commit)
            if (failure != null) return failure
        } finally {
            for (remote in remotes) ShellRunner.exec("rm", "-f", remote)
        }
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

    fun uninstallNowBlocking(packageName: String): String? {
        val shellErr = requireShell()
        if (shellErr != null) return shellErr
        val uid = if (cloneUserId.value > 0) cloneUserId.value else 0
        val r = ShellRunner.exec("pm", "uninstall", "--user", uid.toString(), packageName)
        val out = r.combined
        if (out.contains("Failure", ignoreCase = true)) return out.take(200)
        if (!r.ok && !out.contains("Success", ignoreCase = true)) return out.ifBlank { "uninstall failed" }
        removeMeta(packageName)
        if (running.value == packageName) running.value = null
        return null
    }

    // ---------------------------------------------------------------- launch / stop

    /** Starts a cloned app in the isolated user. Returns null on success. */
    suspend fun launchNowBlocking(packageName: String): String? {
        val shellErr = requireShell()
        if (shellErr != null) return shellErr
        val (uid, err) = targetUserBlocking()
        if (err != null) return err
        val component = resolveComponentBlocking(packageName, uid)
            ?: return "no launcher activity for $packageName in user $uid"
        val r = ShellRunner.exec(
            "am", "start", "--user", uid.toString(), "-n", component,
        )
        val out = r.combined.trim()
        if (!r.ok || out.contains("Error", ignoreCase = true)) {
            log("Launch $packageName failed: ${out.take(200)}")
            return out.ifBlank { "am start failed" }.take(240)
        }
        running.value = packageName
        status.value = "Started $packageName in the clone user ($uid). Switch user to see it."
        log("Launch: $packageName (user $uid, component $component)")
        return null
    }

    fun launch(packageName: String) {
        scope.launch {
            val err = withContext(Dispatchers.IO) { launchNowBlocking(packageName) }
            if (err != null) status.value = err
        }
    }

    /** Switches the phone to the clone user so its apps are visible. */
    fun switchToCloneUser() {
        scope.launch {
            val uid = cloneUserId.value
            if (uid <= 0) {
                status.value = "Clone user is not created yet"
                return@launch
            }
            val r = withContext(Dispatchers.IO) {
                ShellRunner.exec("am", "switch-user", uid.toString())
            }
            val out = r.combined.trim()
            if (!r.ok || out.contains("Error", ignoreCase = true)) {
                status.value = out.ifBlank { "switch-user failed" }.take(200)
                log("switch-user $uid failed: ${out.take(160)}")
            } else {
                log("Switched to clone user $uid")
            }
        }
    }

    /** Resolves pkg/activity for a user (cmd package resolve-activity). */
    private fun resolveComponentBlocking(packageName: String, uid: Int): String? {
        val r = ShellRunner.exec(
            "cmd", "package", "query-activities", "--brief", "--user", uid.toString(), packageName,
        )
        val text = r.stdout.ifBlank { r.stderr }
        for (line in text.lineSequence()) {
            val value = line.trim()
            val slash = value.indexOf('/')
            if (slash > 0 && value.substring(0, slash).contains('.') && !value.contains(' ')) {
                return value
            }
        }
        // Fallback: the component cached at install time.
        return apps.value.firstOrNull { it.packageName == packageName }?.component
    }

    fun killNowBlocking(packageName: String): String? {
        val shellErr = requireShell()
        if (shellErr != null) return shellErr
        val uid = if (cloneUserId.value > 0) cloneUserId.value else 0
        val r = ShellRunner.exec("am", "force-stop", "--user", uid.toString(), packageName)
        val out = r.combined.trim()
        val err = if (!r.ok || out.contains("Error", ignoreCase = true)) {
            out.ifBlank { "force-stop failed" }.take(200)
        } else {
            null
        }
        log("Kill $packageName (user $uid): ${err ?: "ok"}")
        if (running.value == packageName) running.value = null
        return err
    }

    /** Blocking launch for the agent API. Returns null on success. */
    suspend fun launchNow(packageName: String): String? = launchNowBlocking(packageName)

    /** Blocking kill for the agent API. Returns null on success. */
    fun killNow(packageName: String): String? = killNowBlocking(packageName)

    /** Blocking uninstall for the agent API. Returns null on success. */
    fun uninstallNow(packageName: String): String? = uninstallNowBlocking(packageName)

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
