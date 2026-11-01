package com.altaris.linux.core.rea

import android.content.Context
import android.util.Log
import com.altaris.linux.core.install.DistroInstallProfile
import com.altaris.linux.core.install.OnboardingInstallRunner
import com.altaris.linux.core.root.RootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The hidden REA analysis distro.
 *
 * The REA screen needs a stable home with the right tools, without asking
 * the user to pick a distro and without appearing in the app. This object owns
 * that distro's lifecycle:
 *
 *  - **not installed** — the REA dashboard is frosted and says how to install;
 *  - **installing** — one proot or chroot container is created, then REA and
 *    every analysis tool are installed into it, with real progress;
 *  - **ready** — the MCP bridge serves REA from it.
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

    /** Preference holding the method the MCP server uses (see [select]). */
    private const val KEY_ACTIVE_METHOD = "active_method"

    /** Size is cached: walking a multi-GB rootfs on every recomposition froze the UI. */
    private const val SIZE_TTL_MS = 120_000L

    /** How much install output is kept. */
    private const val MAX_LOG = 24_000

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
        /**
         * True when the environment is usable enough to open the dashboard:
         * a container exists. The toolchain may still be missing, which
         * [ready] reports separately so the UI can offer a repair instead of
         * hiding the whole page.
         */
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
        /**
         * Everything the container install printed, newest last.
         *
         * The container install runs through OnboardingInstallRunner, whose
         * output used to be discarded, so a chroot failure was completely
         * silent: the screen showed "needs root" or nothing at all and there was
         * no way to see why. Every line now lands here.
         */
        val log: String = "",
        /**
         * Every method whose container is present, in a stable order.
         *
         * Both can be installed at once: proot always works, chroot is faster
         * when root is available. [method] is the one the MCP server uses; the
         * user picks it explicitly and the choice is remembered.
         */
        val methods: Set<Method> = emptySet(),
        /** True when [method] is usable right now (its container is ready). */
        val activeReady: Boolean = false,
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

    /**
     * Scope for the install itself.
     *
     * The install is a multi-gigabyte, many-minute job. It used to be launched
     * from the screen's `rememberCoroutineScope()`, so leaving the screen (or
     * the tab, or rotating) cancelled it with "the coroutine scope left the
     * composition" and the work died half way. It runs on an app-lifetime scope
     * now and only publishes progress to [state], so navigating away is safe and
     * the result is still there when you come back.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var installJob: Job? = null

    /** True while an install is running in this process. */
    fun isInstalling(): Boolean = installJob?.isActive == true

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
        val present = presentMethods(app)
        val active = chooseActive(app, present)
        val ready = active != null && isReady(app, active)
        _state.value = _state.value.copy(
            // A present container opens the dashboard; [ready] drives the
            // "REA core missing" banner. Reporting a half-installed
            // environment as "not installed" was why a real chroot install
            // showed the frosted gate forever.
            installed = active != null,
            method = active,
            methods = present.toSet(),
            ready = ready,
            activeReady = ready,
            sizeBytes = if (active != null) sizeOf(app, active) else 0L,
            // Only a *known* answer may override the last one. isRootAvailable()
            // reads the cached probe, so on a cold start it is false even on a
            // device that has granted root — which used to hide the chroot
            // option until something else happened to probe.
            chrootPossible = if (RootShell.peekSuInvocation() != null) {
                RootShell.isRootAvailable()
            } else {
                _state.value.chrootPossible
            },
            busy = _state.value.busy,
        )
    }

    /** The distro id of the active REA distro, or null. */
    fun distroId(context: Context): String? = activeMethod(context)?.id

    /** Every method whose container is present, in a stable order. */
    fun installedMethods(context: Context): Set<Method> =
        presentMethods(context.applicationContext).toSet()

    /** True when [method]'s container is present. */
    fun isMethodInstalled(context: Context, method: Method): Boolean =
        containerPresent(context.applicationContext, method)

    /**
     * Make [method] the one the MCP server uses, and remember the choice.
     *
     * Both containers may exist. Rather than guessing (a previous version
     * preferred "whichever was ready first"), the user's choice is stored and
     * survives restarts. Returns the state after the switch.
     */
    fun select(context: Context, method: Method) {
        val app = context.applicationContext
        prefs(app).edit().putString(KEY_ACTIVE_METHOD, method.id).apply()
        refresh(app)
    }

    /** The method the MCP server should use, or null when nothing is installed. */
    fun activeMethod(context: Context): Method? =
        chooseActive(context.applicationContext, presentMethods(context.applicationContext))

    /**
     * The remembered method when it is installed, else the other one.
     *
     * Falls back instead of failing: uninstalling the active method while the
     * other is present must not leave REA unusable.
     */
    private fun chooseActive(context: Context, present: List<Method>): Method? {
        if (present.isEmpty()) return null
        val remembered = Method.fromId(prefs(context).getString(KEY_ACTIVE_METHOD, null))
        if (remembered != null && remembered in present) return remembered
        // No choice recorded yet: prefer a *ready* container, then proot, since
        // proot needs no root and is always usable.
        present.firstOrNull { isReady(context, it) }?.let { return it }
        return present.firstOrNull { it == Method.PROOT } ?: present.first()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(
        "altaris_rea_settings", Context.MODE_PRIVATE,
    )

    /**
     * Mark the environment installed after a successful install.
     *
     * The installer knows it worked; without this the screen waited for the next
     * poll and the button stayed on "Install" after the work was done.
     */
    fun markReady(method: Method) {
        _state.value = _state.value.copy(
            installed = true,
            ready = true,
            activeReady = true,
            method = method,
            methods = _state.value.methods + method,
            busy = "",
            percent = 100,
            error = "",
        )
    }

    /**
     * Every method whose container is present, in a stable order.
     *
     * Two bugs are fixed here:
     *
     *  1. **A chroot rootfs is unreadable from this process** (`/data/local/…`
     *     is `shell_data_file` under SELinux), so `File.list()` returned null
     *     and a perfectly good chroot REA was reported as "not installed".
     *     Presence is now checked through [RootShell] for chroot.
     *  2. The first non-empty container used to win, so a half-extracted proot
     *     container shadowed a complete chroot install. Selection is now a
     *     remembered user choice, with a readiness-aware fallback.
     */
    private fun presentMethods(context: Context): List<Method> =
        Method.entries.filter { containerPresent(context, it) }

    /**
     * True when [method]'s container exists on disk at all.
     *
     * For proot that is a readable directory. For chroot it has to go through
     * root: the app cannot see `/data/local`, so a direct check is always a
     * false negative.
     */
    private fun containerPresent(context: Context, method: Method): Boolean {
        val rootfs = rootfsOf(context, method) ?: return false
        if (rootfs.isDirectory && rootfs.list()?.isNotEmpty() == true) return true
        if (method != Method.CHROOT) return false
        if (!RootShell.isRootAvailable()) {
            // Fall back to the cached probe the chroot settings already keep.
            return com.altaris.linux.core.chroot.ChrootInfoStore
                .cachedInstalled(context, method.id)
        }
        val result = runCatching {
            RootShell.captureResult(
                "test -d " + quote(rootfs.absolutePath) +
                    " && [ \"\$(ls -A " + quote(rootfs.absolutePath) + " 2>/dev/null | head -n1)\" ]",
                20_000L,
            )
        }.getOrNull()
        return result != null && result.exitCode == 0
    }

    /**
     * True when REA's core is really installed.
     *
     * Deliberately the *same* check [ReaBridge] uses before it execs the binary,
     * so the screen can never claim "Installed" while the server fails with
     * `not found`. The marker file is an optimisation only: the binary itself is
     * the source of truth.
     */
    private fun isReady(context: Context, method: Method): Boolean =
        ReaManager.isInstalledIn(context, method.id)

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

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
    /**
     * Start an install and return immediately.
     *
     * Safe to call from a click handler: the work runs on [scope], not on the
     * caller's, so it survives the screen that started it. Calling it while an
     * install is already running is a no-op.
     */
    fun install(
        context: Context,
        method: Method,
        tools: List<String> = ALL_TOOLS,
        onLog: (String) -> Unit = {},
    ) {
        if (isInstalling()) {
            Log.i(TAG, "install already running, ignoring duplicate start")
            return
        }
        val app = context.applicationContext
        installJob = scope.launch {
            runCatching { installBlocking(app, method, tools, onLog) }
                .onFailure { Log.w(TAG, "install failed", it) }
        }
    }

    /**
     * The install itself. Blocking; runs on [scope].
     *
     * Kept public so a caller that genuinely wants to wait (tests, or a future
     * headless entry point) can.
     */
    suspend fun installBlocking(
        context: Context,
        method: Method,
        tools: List<String> = ALL_TOOLS,
        onLog: (String) -> Unit = {},
    ): State = withContext(Dispatchers.IO) {
        mutex.withLock {
            val app = context.applicationContext
            if (method == Method.CHROOT) {
                // `isRootAvailable()` only reads the cached probe result, so on a
                // cold start it reported "no root" and aborted before any
                // download — which looked like chroot simply not working. Force
                // one real probe first, and say exactly what failed.
                val root = runCatching {
                    RootShell.isRootAvailable() || run {
                        RootShell.clearSuCache()
                        RootShell.isRootAvailable()
                    }
                }.getOrDefault(false)
                if (!root) {
                    _state.value = _state.value.copy(
                        busy = "",
                        error = "chroot needs root. Open the root manager and grant " +
                            "superuser to Altaris, then retry. proot needs no root.",
                    )
                    return@withLock _state.value
                }
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
                    log = "",
                    // Keep the methods already installed: installing the second
                    // one must not hide the first.
                    methods = presentMethods(app).toSet(),
                )
                appendLog("creating the hidden REA distro (" + method.label + ")")
                onLog("creating the hidden REA distro (" + method.label + ")")

                val created = ReaInstaller.ensureContainer(app, profile) { line, percent, detail ->
                    publish(line, percent, detail)
                    onLog(detail.ifBlank { line })
                }
                if (!created) {
                    appendLog("ERROR: the container was not created")
                    _state.value = _state.value.copy(
                        busy = "",
                        error = "Could not create the REA distro. See the install log below.",
                    )
                    return@withLock _state.value
                }
                // The dashboard may open from here on: the container is real
                // even if the toolchain below still fails.
                _state.value = _state.value.copy(installed = true)
                markStep("Container created")
                publish("Installing REA and the analysis tools", 62, "")

                val status = ReaManager.install(app, method.id, tools) { line ->
                    onLog(line)
                    // Real progress: the manager reports which tool of how
                    // many. The container is done, so the toolchain owns
                    // 62..99% and each completed tool moves the bar by a real
                    // fraction instead of one invented percent per log line.
                    val st = ReaManager.status.value
                    val toolFraction = if (st.stepTotal > 0) {
                        62f + st.stepFraction.coerceIn(0f, 1f) * 37f
                    } else {
                        _state.value.percent.toFloat().coerceAtLeast(62f)
                    }
                    _state.value = _state.value.copy(
                        busy = line.take(90),
                        percent = toolFraction.toInt().coerceIn(0, 99),
                        detail = if (st.stepTotal > 0) {
                            st.stepLabel + " · " + st.step + "/" + st.stepTotal +
                                if (st.stepFailed.isEmpty()) ""
                                else " · " + st.stepFailed.size + " failed"
                        } else {
                            line.take(120)
                        },
                        steps = listOf(
                            Step("Container created", done = true),
                            Step(
                                "REA core",
                                done = statusReady(),
                            ),
                            Step(
                                "Analysis tools (" + st.step + "/" + st.stepTotal + ")",
                                done = st.stepTotal > 0 && st.step >= st.stepTotal,
                            ),
                        ),
                    )
                }
                val ok = status.installed && ReaManager.isInstalledIn(app, method.id)
                if (ok) {
                    // A finished install becomes the one the server uses, and
                    // that choice is remembered so it survives a restart.
                    prefs(app).edit().putString(KEY_ACTIVE_METHOD, method.id).apply()
                    markReady(method)
                    // Only now is the environment really usable.
                    rootfsOf(app, method)?.let { runCatching { File(it, READY_MARKER).writeText("1") } }
                    markStep("REA core installed")
                    markStep("Analysis tools installed")
                }
                _state.value = _state.value.copy(
                    busy = "",
                    percent = if (ok) 100 else _state.value.percent,
                    installed = true,
                    method = method,
                    ready = ok,
                    error = status.error,
                )
                if (ok) invalidateSize(app, method)
                refresh(app)
                _state.value
            } catch (c: kotlinx.coroutines.CancellationException) {
                // Only happens when the process is going away: the install now
                // runs on an app-lifetime scope, so navigation cannot cancel it.
                Log.i(TAG, "REA install cancelled")
                appendLog("install cancelled")
                _state.value = _state.value.copy(busy = "")
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "REA distro install failed", t)
                appendLog("ERROR: " + (t.message ?: t.javaClass.simpleName))
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
    /**
     * Start a tool repair and return immediately. Same lifetime rule as
     * [install].
     */
    fun installMissingTools(
        context: Context,
        onLog: (String) -> Unit = {},
    ) {
        if (isInstalling()) return
        val app = context.applicationContext
        installJob = scope.launch {
            runCatching { installMissingToolsBlocking(app, onLog) }
                .onFailure { Log.w(TAG, "tool repair failed", it) }
        }
    }

    suspend fun installMissingToolsBlocking(
        context: Context,
        onLog: (String) -> Unit = {},
    ): State = withContext(Dispatchers.IO) {
        mutex.withLock {
            val app = context.applicationContext
            val method = activeMethod(app)
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

    /**
     * Remove the REA environment entirely (its files and its containers).
     *
     * Both containers are removed when both are present, so a user who tried
     * proot and then chroot does not leave an invisible copy behind. The
     * removal only reports success when nothing is left.
     */
    fun uninstall(context: Context) {
        if (isInstalling()) return
        val app = context.applicationContext
        installJob = scope.launch {
            runCatching { uninstallBlocking(app) }
                .onFailure { Log.w(TAG, "uninstall failed", it) }
        }
    }

    suspend fun uninstallBlocking(context: Context): State = withContext(Dispatchers.IO) {
        mutex.withLock {
            val app = context.applicationContext
            _state.value = _state.value.copy(
                busy = "Removing the analysis environment",
                percent = -1,
                error = "",
                log = "",
            )
            appendLog("removing the analysis environment")
            // Stop everything that could hold the chroot's mounts open BEFORE
            // deleting: the MCP bridge, its guest process, and any REA session.
            // A live process inside the chroot is what makes an unmount fail,
            // and a failed unmount is what makes the delete walk into the host.
            runCatching { ReaBridge.stop() }
                .onFailure { Log.w(TAG, "bridge stop failed", it) }
            appendLog("stopped the MCP bridge")
            runCatching { ReaManager.stop() }
                .onFailure { Log.w(TAG, "REA stop failed", it) }
            appendLog("stopped the REA process")

            // Remove every container, not just the active one: a failed switch
            // used to leave the other behind with no way to see or delete it.
            val targets = presentMethods(app)
            if (targets.isEmpty()) {
                appendLog("no container was present")
            }
            var allRemoved = true
            for (method in targets) {
                appendLog("removing the " + method.label + " container")
                val removed = runCatching { ReaInstaller.removeContainer(app, method) }
                    .onFailure {
                        Log.w(TAG, "container removal failed: ${method.label}", it)
                        appendLog("ERROR: " + (it.message ?: "removal failed"))
                    }
                    .getOrDefault(false)
                appendLog(
                    if (removed) method.label + " container removed"
                    else "ERROR: the " + method.label + " container could not be removed"
                )
                if (!removed) allRemoved = false
            }
            invalidateSize(app, null)

            if (allRemoved) {
                // The remembered choice must not outlive the installs.
                prefs(app).edit().remove(KEY_ACTIVE_METHOD).apply()
                _state.value = State(chrootPossible = RootShell.isRootAvailable())
            } else {
                // Never pretend it is gone: the previous code reset the state
                // unconditionally, so a failed removal looked like a success and
                // the distro reappeared on the next refresh.
                _state.value = _state.value.copy(
                    busy = "",
                    error = "Could not remove the analysis environment. " +
                        "The log below names what blocked it.",
                    chrootPossible = RootShell.isRootAvailable(),
                )
                refresh(app)
            }
            _state.value
        }
    }

    private fun appendLog(text: String) {
        if (text.isBlank()) return
        val current = _state.value.log
        val next = (current + text.trimEnd() + "\n")
        _state.value = _state.value.copy(
            log = if (next.length > MAX_LOG) next.takeLast(MAX_LOG) else next
        )
    }

    /** Test seam: the log tail. */
    internal fun logTailForTest(): String = _state.value.log

    private fun publish(line: String, percent: Int, detail: String) {
        appendLog(detail.ifBlank { line })
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

    /** True when the REA binary itself is present (the core step). */
    private fun statusReady(): Boolean = ReaManager.status.value.installed

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
