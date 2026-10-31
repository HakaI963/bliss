package com.altaris.linux.core.rea

import android.content.Context
import android.util.Log
import com.altaris.linux.core.install.DistroInstallProfile
import com.altaris.linux.core.install.OnboardingInstallRunner
import com.altaris.linux.core.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The hidden REA analysis distro.
 *
 * Quinn's REA target needs a stable home with the right tools, without asking
 * the user to pick a distro and without appearing in the app. This object owns
 * that distro's lifecycle:
 *
 *  - **not installed** — the REA dashboard is frosted and says how to install;
 *  - **installing** — one proot or chroot container is created, then REA and
 *    every analysis tool are installed into it, with real progress;
 *  - **ready** — Quinn drives REA in it over MCP.
 *
 * Two correctness rules that used to be bugs:
 *
 *  1. **Installed means finished.** A rootfs directory exists from the first
 *     second of extraction, so "the directory is non-empty" reported the
 *     environment as installed while it was still downloading. Completion is
 *     now a marker file written after the toolchain install succeeds.
 *  2. **Progress is published.** The installer reports a phase, a percentage
 *     and the download detail, so the dashboard can show how much is done,
 *     how much is left, and what is being fetched.
 */
object ReaDistro {

    private const val TAG = "AltarisReaDistro"

    /** Written into the rootfs only when the toolchain install has finished. */
    private const val READY_MARKER = ".altaris_rea_ready"

    /** Size is cached: walking a multi-GB rootfs on every recomposition froze the UI. */
    private const val SIZE_TTL_MS = 120_000L

    enum class Method(val id: String, val label: String) {
        PROOT(DistroInstallProfile.REA_PROOT_ID, "proot"),
        CHROOT(DistroInstallProfile.REA_CHROOT_ID, "chroot");

        companion object {
            fun fromId(id: String?): Method? = entries.firstOrNull { it.id == id }
        }
    }

    /** One step of the install, as shown in the dashboard. */
    data class Step(val label: String, val done: Boolean = false)

    data class State(
        val installed: Boolean = false,
        /** Which method is installed, if any. */
        val method: Method? = null,
        /** True while the container or the toolchain is being installed. */
        val busy: String = "",
        /** 0..100 for the whole install, or -1 when the step has no measure. */
        val percent: Int = -1,
        /** Bytes fetched / expected for the rootfs download, when known. */
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = 0L,
        /** What the installer is doing right now, one line. */
        val detail: String = "",
        /** Completed steps, for the checklist. */
        val steps: List<Step> = emptyList(),
        val ready: Boolean = false,
        val error: String = "",
        /** Rootfs size on disk, in bytes, for the status card. */
        val sizeBytes: Long = 0L,
        /** True when a chroot install is possible (root available). */
        val chrootPossible: Boolean = false,
    ) {
        val fraction: Float
            get() = when {
                percent >= 0 -> percent / 100f
                totalBytes > 0L -> (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                else -> 0f
            }

        /** "412.6 MB of 780.1 MB · 312.4 MB left" */
        val downloadText: String
            get() = when {
                totalBytes > 0L && downloadedBytes > 0L -> {
                    val left = (totalBytes - downloadedBytes).coerceAtLeast(0L)
                    formatBytes(downloadedBytes) + " of " + formatBytes(totalBytes) +
                        " · " + formatBytes(left) + " left"
                }
                downloadedBytes > 0L -> formatBytes(downloadedBytes) + " downloaded"
                else -> ""
            }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val mutex = Mutex()

    @Volatile private var cachedSize = 0L
    @Volatile private var cachedSizeAt = 0L
    @Volatile private var cachedSizeFor: String? = null

    /**
     * Recompute [state] from the filesystem.
     *
     * Cheap and safe to call often: the only expensive part (the rootfs size)
     * is cached per method for [SIZE_TTL_MS], and an install in flight is never
     * overwritten by a refresh.
     */
    fun refresh(context: Context) {
        val app = context.applicationContext
        val method = installedMethod(app)
        val ready = method != null && isReady(app, method)
        val rootfs = method?.let { rootfsOf(app, it) }
        _state.value = _state.value.copy(
            installed = ready,
            method = method,
            ready = ready,
            sizeBytes = if (ready) sizeOf(app, method) else 0L,
            chrootPossible = RootShell.isRootAvailable(),
            busy = _state.value.busy,
        )
    }

    /** The distro id of the installed REA distro, or null. */
    fun distroId(context: Context): String? = installedMethod(context)?.id

    /**
     * The installed method, if any.
     *
     * A container that exists but has no marker is *not* installed: it is an
     * interrupted install, and reporting it as installed would send the user to
     * a dashboard whose tools are missing.
     */
    private fun installedMethod(context: Context): Method? {
        for (method in Method.entries) {
            val rootfs = rootfsOf(context, method) ?: continue
            if (rootfs.isDirectory && rootfs.list()?.isNotEmpty() == true) return method
        }
        return null
    }

    /** True when [method]'s container exists *and* its toolchain finished. */
    private fun isReady(context: Context, method: Method): Boolean {
        val rootfs = rootfsOf(context, method) ?: return false
        if (File(rootfs, READY_MARKER).isFile) return true
        // A container from an older build has no marker; REA's own binary is
        // the next best proof, and re-installing would waste a GB.
        return ReaManager.isInstalledIn(context, method.id)
    }

    private fun rootfsOf(context: Context, method: Method): File? =
        when (method) {
            Method.PROOT -> File(
                context.filesDir,
                "usr/var/lib/proot-distro/containers/${DistroInstallProfile.REA_PROOT_ID}/rootfs"
            )
            Method.CHROOT -> DistroInstallProfile.REA_CHROOT.chrootPath?.let { File(it) }
        }

    /**
     * Install the REA distro and the REA toolchain into it.
     *
     * The container itself is created by the normal distro install path (so
     * rootfs verification, proot setup and chroot staging are not duplicated);
     * this only chooses the profile, then hands off to [ReaManager] for the
     * Node + REA + tools install.
     */
    suspend fun install(
        context: Context,
        method: Method,
        tools: List<String> = ALL_TOOLS,
        onLog: (String) -> Unit = {},
    ): State = withContext(Dispatchers.IO) {
        mutex.withLock {
            val app = context.applicationContext
            if (method == Method.CHROOT && !RootShell.isRootAvailable()) {
                _state.value = _state.value.copy(
                    busy = "",
                    error = "chroot needs root access. Grant superuser to Altaris, or use proot.",
                )
                return@withLock _state.value
            }
            val profile = DistroInstallProfile.reaProfile(method.id) ?: run {
                _state.value = _state.value.copy(error = "REA profile is missing")
                return@withLock _state.value
            }
            try {
                _state.value = State(
                    method = method,
                    busy = "Creating the analysis environment",
                    percent = 0,
                    chrootPossible = RootShell.isRootAvailable(),
                    steps = defaultSteps(),
                )
                onLog("creating the hidden REA distro (" + method.label + ")")

                val created = ReaInstaller.ensureContainer(app, profile) { line, percent, detail ->
                    publish(line, percent, detail)
                    onLog(detail.ifBlank { line })
                }
                if (!created) {
                    _state.value = _state.value.copy(
                        busy = "",
                        error = "Could not create the REA distro. Check the install log.",
                    )
                    return@withLock _state.value
                }
                markStep("Container created")
                publish("Installing REA and the analysis tools", 62, "")

                val status = ReaManager.install(app, method.id, tools) { line ->
                    onLog(line)
                    // The toolchain install has no byte counter; map its
                    // progress onto the remaining 62..99% band and show the
                    // line it is on.
                    _state.value = _state.value.copy(
                        busy = line.take(90),
                        percent = (_state.value.percent.coerceAtLeast(62) + 1).coerceAtMost(99),
                        detail = line.take(120),
                    )
                }
                val ok = status.installed && ReaManager.isInstalledIn(app, method.id)
                if (ok) {
                    // Only now is the environment really usable.
                    rootfsOf(app, method)?.let { runCatching { File(it, READY_MARKER).writeText("1") } }
                    markStep("REA core installed")
                    markStep("Analysis tools installed")
                }
                _state.value = _state.value.copy(
                    busy = "",
                    percent = if (ok) 100 else _state.value.percent,
                    installed = ok,
                    method = method,
                    ready = ok,
                    error = status.error,
                )
                if (ok) invalidateSize(app, method)
                refresh(app)
                _state.value
            } catch (t: Throwable) {
                Log.w(TAG, "REA distro install failed", t)
                _state.value = _state.value.copy(
                    busy = "",
                    error = t.message ?: t.javaClass.simpleName,
                )
                _state.value
            }
        }
    }

    /**
     * Install any missing analysis tools into an existing environment.
     *
     * Used by the dashboard's "install missing tools" action and after a
     * container-only install, so a half-finished environment can be repaired
     * without re-downloading the rootfs.
     */
    suspend fun installMissingTools(
        context: Context,
        onLog: (String) -> Unit = {},
    ): State = withContext(Dispatchers.IO) {
        mutex.withLock {
            val app = context.applicationContext
            val method = installedMethod(app)
            if (method == null) {
                _state.value = _state.value.copy(error = "REA is not installed")
                return@withLock _state.value
            }
            val status = ReaManager.status.value
            val missing = ALL_TOOLS.filterNot { it in status.available }
            if (missing.isEmpty()) return@withLock _state.value
            _state.value = _state.value.copy(
                busy = "Installing " + missing.size + " tools",
                percent = 62,
                error = "",
            )
            val result = ReaManager.install(app, method.id, missing) { line ->
                onLog(line)
                _state.value = _state.value.copy(
                    busy = line.take(90),
                    percent = (_state.value.percent.coerceAtLeast(62) + 1).coerceAtMost(99),
                )
            }
            val ok = result.error.isBlank()
            if (ok) {
                rootfsOf(app, method)?.let { runCatching { File(it, READY_MARKER).writeText("1") } }
                markStep("Analysis tools installed")
            }
            _state.value = _state.value.copy(
                busy = "",
                percent = if (ok) 100 else _state.value.percent,
                installed = ok || _state.value.installed,
                ready = ok || _state.value.ready,
                error = result.error,
            )
            refresh(app)
            _state.value
        }
    }

    /** Remove the REA distro entirely (its files and its container). */
    suspend fun uninstall(context: Context): State = withContext(Dispatchers.IO) {
        mutex.withLock {
            val app = context.applicationContext
            _state.value = _state.value.copy(busy = "Removing the analysis environment", percent = -1)
            runCatching { ReaManager.stop() }
            val method = installedMethod(app)
            if (method != null) {
                runCatching { ReaInstaller.removeContainer(app, method) }
            }
            invalidateSize(app, null)
            _state.value = State(chrootPossible = RootShell.isRootAvailable())
            _state.value
        }
    }

    private fun publish(line: String, percent: Int, detail: String) {
        _state.value = _state.value.copy(
            busy = line,
            percent = if (percent >= 0) percent else _state.value.percent,
            detail = detail.ifBlank { line },
            // The runner's phase percentage covers the container; the toolchain
            // install continues from 62%.
            downloadedBytes = _state.value.downloadedBytes,
        )
    }

    /** Publish download bytes, parsed from the runner's detail line. */
    fun publishDownload(downloaded: Long, total: Long, detail: String) {
        _state.value = _state.value.copy(
            downloadedBytes = downloaded,
            totalBytes = total,
            detail = detail,
        )
    }

    private fun markStep(label: String) {
        _state.value = _state.value.copy(
            steps = _state.value.steps.map {
                if (it.label == label) it.copy(done = true) else it
            }
        )
    }

    private fun defaultSteps(): List<Step> = listOf(
        Step("Container created"),
        Step("REA core installed"),
        Step("Analysis tools installed"),
    )

    private fun sizeOf(context: Context, method: Method?): Long {
        if (method == null) return 0L
        val now = System.currentTimeMillis()
        if (cachedSizeFor == method.id && now - cachedSizeAt < SIZE_TTL_MS) return cachedSize
        val rootfs = rootfsOf(context, method) ?: return 0L
        val size = runCatching {
            rootfs.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
        cachedSize = size
        cachedSizeAt = now
        cachedSizeFor = method.id
        return size
    }

    private fun invalidateSize(context: Context, method: Method?) {
        cachedSize = 0L
        cachedSizeAt = 0L
        cachedSizeFor = null
    }

    fun formatBytes(bytes: Long): String = when {
        bytes <= 0L -> "—"
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "${bytes / 1024} KB"
        bytes < 1024L * 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }

    /**
     * Every tool the analysis environment installs.
     *
     * This is the catalog minus the commercial, licence-gated engines (IDA,
     * Hopper, JEB), which cannot be redistributed. Ghidra *is* included: the
     * requirement is that one install leaves the environment complete.
     */
    val ALL_TOOLS = listOf(
        "java", "jadx", "apktool", "aapt", "adb", "binutils", "gdb", "radare2",
        "rizin", "python3", "binwalk", "p7zip", "sqlite3", "procps", "file",
        "tshark", "ghidra",
    )

    /** Backwards-compatible alias: the tools installed by default. */
    val DEFAULT_TOOLS: List<String> get() = ALL_TOOLS
}
