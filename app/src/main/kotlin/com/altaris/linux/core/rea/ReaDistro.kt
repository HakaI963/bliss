package com.altaris.linux.core.rea

import android.content.Context
import android.util.Log
import com.altaris.linux.core.install.DistroInstallProfile
import com.altaris.linux.core.root.RootShell
import com.altaris.linux.core.terminal.DistroFsCache
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
 * Where REA lives: a distro the user already installed.
 *
 * REA used to own a hidden distro of its own, which meant a second multi-GB
 * rootfs download on top of whatever the user already had, and no way to use
 * the distro they had already set up. It is now installed *into* a distro the
 * user picks, so the toolchain (Node, JDK, Ghidra, JADX, …) lands in that
 * distro's normal filesystem.
 *
 * The object owns three things:
 *
 *  - **which** distro is the REA target (remembered across restarts);
 *  - **whether** REA is usable there (a stat, or `su` for a chroot);
 *  - **installing and removing** the toolchain, with real progress.
 *
 * Two correctness rules that used to be bugs:
 *
 *  1. **Installed means finished.** A rootfs directory exists from the first
 *     second of extraction, so "the directory is non-empty" reported the
 *     environment as installed while it was still downloading. Completion is
 *     now the REA binary itself, which is the same check the bridge uses
 *     before it execs it.
 *  2. **Progress is published.** The installer reports a phase, a percentage
 *     and the download detail, so the dashboard can show how much is done,
 *     how much is left, and what is being fetched.
 */
object ReaDistro {

    private const val TAG = "AltarisReaDistro"

    /** Preference holding the distro the MCP server runs in (see [select]). */
    private const val KEY_ACTIVE_TARGET = "active_target"

    /** Set once the containers REA used to own have been deleted. */
    private const val KEY_LEGACY_CLEARED = "legacy_containers_cleared"

    /** The proot container REA used to create for itself. */
    private const val LEGACY_PROOT_NAME = "altaris_rea"

    /** The chroot tree REA used to create for itself. */
    private const val LEGACY_CHROOT_PATH = "/data/local/altaris/chroots/rea"

    /** Size is cached: walking a multi-GB rootfs on every recomposition froze the UI. */
    private const val SIZE_TTL_MS = 120_000L

    /** How much install output is kept. */
    private const val MAX_LOG = 24_000

    /**
     * Minimum gap between two non-deep refreshes.
     *
     * Screens poll [refresh]; without this every poll re-ran the presence
     * checks (and, for a chroot, a root probe) even though nothing could have
     * changed. Two seconds keeps the UI honest and costs nothing.
     */
    private const val REFRESH_MIN_INTERVAL_MS = 2_000L

    /** One step of the install, as shown in the dashboard. */
    data class Step(val label: String, val done: Boolean = false)

    /**
     * A distro REA can be installed into.
     *
     * Both `id` and `label` come from the installed-distro catalog, so the
     * picker shows the same names the Distros screen does. [method] is the
     * install method the profile already uses ("proot" or "chroot").
     */
    data class Target(
        val id: String,
        val label: String,
        val method: String,
    ) {
        val isChroot: Boolean get() = method == "chroot"
    }

    data class State(
        /**
         * True when REA is installed in the selected target and usable: the
         * `rea` binary is present. This is the same check [ReaBridge] makes
         * before exec, so the screen can never claim "Installed" while the
         * server fails with `not found`.
         */
        val installed: Boolean = false,
        /** The distro REA is installed in, if any. */
        val target: Target? = null,
        /** Every distro the user could install REA into. */
        val targets: List<Target> = emptyList(),
        /** True while the toolchain is being installed. */
        val busy: String = "",
        /** 0..100 for the whole install, or -1 when the step has no measure. */
        val percent: Int = -1,
        /** Bytes fetched / expected for a download, when known. */
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
         * Everything the install printed, newest last.
         *
         * A chroot failure used to be completely silent: the screen showed
         * "needs root" or nothing at all and there was no way to see why.
         * Every line now lands here.
         */
        val log: String = "",
        /** True when [target] is usable right now. */
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
     * The toolchain is a multi-gigabyte, many-minute job. It used to be
     * launched from the screen's `rememberCoroutineScope()`, so leaving the
     * screen (or the tab, or rotating) cancelled it with "the coroutine scope
     * left the composition" and the work died half way. It runs on an
     * app-lifetime scope now and only publishes progress to [state], so
     * navigating away is safe and the result is still there when you come
     * back.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var installJob: Job? = null

    /** True while an install is running in this process. */
    fun isInstalling(): Boolean = installJob?.isActive == true

    @Volatile private var cachedSize = 0L
    @Volatile private var cachedSizeAt = 0L
    @Volatile private var cachedSizeFor: String? = null

    /** Last full refresh, so a poll loop cannot re-run the expensive probes. */
    @Volatile private var lastRefreshAt = 0L

    /**
     * Last known "REA core is present" per distro id.
     *
     * Only a deep refresh writes it, because a chroot check runs `su`. A
     * shallow poll reads it, so the dashboard keeps reporting an installed
     * chroot without forking a root shell every few seconds.
     */
    private val readyCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    // ------------------------------------------------------------------ legacy

    /**
     * Delete the containers REA used to own.
     *
     * REA once had a private distro of its own (`altaris_rea` /
     * `altaris_rea_chroot`). Now it installs into a distro the user already
     * has, so those two are dead weight: several hundred MB each, invisible in
     * every list, and impossible to remove once the code that created them is
     * gone. They are deleted once, on first launch after the change, before any
     * target is offered.
     *
     * The old chroot must be unmounted before it is deleted — its /dev, /proc
     * and /sys are bind mounts of the host, and a plain delete walks through
     * them into the live system. That is why the removal goes through the same
     * script the Distros screen uses rather than `deleteRecursively`.
     */
    fun removeLegacyContainers(context: Context) {
        val app = context.applicationContext
        if (prefs(app).getBoolean(KEY_LEGACY_CLEARED, false)) return
        // Off the caller's thread: this can delete hundreds of MB.
        scope.launch {
            runCatching {
                val names = listOf(LEGACY_PROOT_NAME)
                var allGone = true
                for (name in names) {
                    val rootfs = File(
                        app.filesDir,
                        "usr/var/lib/proot-distro/containers/$name/rootfs",
                    )
                    if (!rootfs.exists()) continue
                    Log.i(TAG, "removing the legacy REA container at ${rootfs.absolutePath}")
                    val gone = runCatching { rootfs.deleteRecursively() && !rootfs.exists() }
                        .getOrDefault(false)
                    if (!gone) allGone = false
                }
                // The chroot lives under /data/local, which this process cannot
                // read; the script unmounts it first and refuses if a mount
                // cannot be released.
                val legacyChroot = LEGACY_CHROOT_PATH
                val chrootPresent = runCatching {
                    if (!RootShell.isRootAvailable()) {
                        File(legacyChroot).exists()
                    } else {
                        RootShell.captureResult(
                            "test -e " + quote(legacyChroot) + " && echo PRESENT || echo GONE",
                            20_000L,
                        ).stdout.contains("PRESENT")
                    }
                }.getOrDefault(false)
                if (chrootPresent) {
                    Log.i(TAG, "removing the legacy REA chroot at $legacyChroot")
                    val staged = RootShell.stageAsset(app, "scripts/chroot/remove-chroot.sh")
                    if (staged != null) {
                        val result = RootShell.captureResult(
                            "sh " + quote(staged) + " " + quote(legacyChroot),
                            timeoutMs = 10 * 60_000L,
                        )
                        if (result.exitCode != 0) allGone = false
                    } else {
                        allGone = false
                    }
                }
                // Only remember the cleanup when it really finished, so a
                // failure retries next launch instead of silently leaking.
                if (allGone) {
                    prefs(app).edit().putBoolean(KEY_LEGACY_CLEARED, true).apply()
                    Log.i(TAG, "legacy REA containers cleared")
                }
            }.onFailure { Log.w(TAG, "legacy REA cleanup failed", it) }
        }
    }

    // ------------------------------------------------------------------ target

    /**
     * Every distro REA can be installed into: the distros the user actually
     * has, never a distro Altaris keeps for itself.
     *
     * Both methods are offered because both work: proot needs no root, chroot
     * is faster when root is granted. The user picks, and the choice is
     * remembered.
     */
    fun targets(context: Context): List<Target> {
        val app = context.applicationContext
        val installed = runCatching {
            DistroFsCache.get(app).installed
        }.getOrDefault(emptySet())
        return installed
            .filterNot { DistroInstallProfile.isHidden(it) }
            .filter { DistroInstallProfile.forId(it) != null }
            .mapNotNull { id ->
                val profile = DistroInstallProfile.forId(id) ?: return@mapNotNull null
                // A chroot is only usable when root is available.
                if (profile.method == "chroot" && !RootShell.isRootAvailable()) {
                    return@mapNotNull null
                }
                Target(
                    id = id,
                    label = profile.displayName.ifBlank { id },
                    method = profile.method,
                )
            }
            .sortedWith(compareBy({ it.method != "proot" }, { it.label.lowercase() }))
    }

    /**
     * The distro the MCP server uses.
     *
     * The remembered choice wins when it still exists. Otherwise the first
     * target that already has REA, then the first target at all, so a fresh
     * install lands somewhere sensible instead of nowhere.
     */
    fun activeTarget(context: Context): Target? {
        val app = context.applicationContext
        val all = targets(app)
        if (all.isEmpty()) return null
        val remembered = prefs(app).getString(KEY_ACTIVE_TARGET, null)
        all.firstOrNull { it.id == remembered }?.let { return it }
        all.firstOrNull { ReaManager.isInstalledIn(app, it.id) }?.let { return it }
        return all.firstOrNull { it.method == "proot" } ?: all.first()
    }

    /** The distro id REA is installed in, or null. */
    fun distroId(context: Context): String? = activeTarget(context)?.id

    /**
     * Make [target] the distro the MCP server uses, and remember the choice.
     *
     * Nothing is downloaded: this only changes where the server runs.
     */
    fun select(context: Context, target: Target) {
        val app = context.applicationContext
        prefs(app).edit().putString(KEY_ACTIVE_TARGET, target.id).apply()
        refresh(app, deep = true)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(
        "altaris_rea_settings", Context.MODE_PRIVATE,
    )

    // ----------------------------------------------------------- clone-only

    /** Preference: the debugger may only ever touch clones. */
    private const val KEY_CLONE_ONLY = "debug_clone_only"

    /**
     * True when the debugger is restricted to clones.
     *
     * **On by default, and it should stay on.** A clone is a separate package
     * with its own UID, and root can attach to *any* process on the device —
     * including the user's real apps. Without this restriction an agent that
     * was told "attach to <app>" could attach to the real installation instead
     * of the clone, which is both wrong and invasive. The switch exists only so
     * a developer can deliberately opt out.
     */
    fun isCloneOnly(context: Context): Boolean =
        prefs(context.applicationContext).getBoolean(KEY_CLONE_ONLY, true)

    /** Turn the clone-only restriction on or off. */
    fun setCloneOnly(context: Context, enabled: Boolean) {
        prefs(context.applicationContext).edit()
            .putBoolean(KEY_CLONE_ONLY, enabled)
            .apply()
    }

    // ----------------------------------------------------------------- refresh

    /**
     * Recompute [state] from the filesystem.
     *
     * **Cheap by construction.** The screens call this on a poll loop, and it
     * used to be anything but cheap:
     *
     *  - every call walked the whole rootfs to compute [State.sizeBytes], which
     *    is tens of thousands of `stat` calls over several GB, and
     *  - every call ran `su` once or twice (chroot presence, chroot readiness),
     *    and each `su` forks a root shell.
     *
     * On a phone that is enough to make the whole app stutter while any screen
     * with a REA poll is open, because the work competes with the UI thread for
     * the CPU and the storage. So:
     *
     *  - the size is only recomputed when its cache is older than [SIZE_TTL_MS]
     *    *and* the caller asked for a deep refresh;
     *  - a refresh within [REFRESH_MIN_INTERVAL_MS] of the previous one reuses
     *    the last state, which is what a poll wants;
     *  - root work is only attempted when the cached probe says root is there,
     *    and never blocks a caller on a cold cache.
     *
     * @param deep re-probe the slow facts (size, chroot via root). Pass true
     *   after an install or a removal, not from a poll.
     */
    fun refresh(context: Context, deep: Boolean = false) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        // A poll within the interval is a no-op: the state cannot have changed
        // in a way this cheap path would see anyway.
        if (!deep && now - lastRefreshAt < REFRESH_MIN_INTERVAL_MS) return

        val rootKnown = RootShell.peekSuInvocation() != null
        val all = targets(app)
        val target = chooseTarget(app, all)
        val ready = target != null && isReady(app, target, deep && rootKnown)
        lastRefreshAt = now
        _state.value = _state.value.copy(
            installed = ready,
            target = target,
            targets = all,
            ready = ready,
            activeReady = ready,
            // Never walk the rootfs unless asked: this is the expensive one.
            sizeBytes = if (target != null && deep) sizeOf(app, target) else _state.value.sizeBytes,
            // Only a *known* answer may override the last one. isRootAvailable()
            // reads the cached probe, so on a cold start it is false even on a
            // device that has granted root — which used to hide the chroot
            // option until something else happened to probe.
            chrootPossible = if (rootKnown) {
                RootShell.isRootAvailable()
            } else {
                _state.value.chrootPossible
            },
            busy = _state.value.busy,
        )
    }

    private fun chooseTarget(context: Context, all: List<Target>): Target? {
        if (all.isEmpty()) return null
        val remembered = prefs(context).getString(KEY_ACTIVE_TARGET, null)
        all.firstOrNull { it.id == remembered }?.let { return it }
        all.firstOrNull { ReaManager.isInstalledIn(context, it.id) }?.let { return it }
        return all.firstOrNull { it.method == "proot" } ?: all.first()
    }

    /**
     * True when REA's core is really installed in [target].
     *
     * Deliberately the *same* check [ReaBridge] uses before it execs the
     * binary, so the screen can never claim "Installed" while the server fails
     * with `not found`.
     */
    private fun isReady(context: Context, target: Target, probeRoot: Boolean): Boolean {
        // A proot check is one `File.isFile`: always cheap, always accurate.
        if (!target.isChroot) return ReaManager.isInstalledIn(context, target.id)
        // A chroot check runs `su`. Only do it on a deep read; otherwise reuse
        // the last answer, so a poll never forks a root shell.
        if (!probeRoot || !RootShell.isRootAvailable()) {
            return readyCache[target.id]
                ?: (_state.value.target?.id == target.id && _state.value.ready)
        }
        val ready = runCatching { ReaManager.isInstalledIn(context, target.id) }
            .getOrDefault(false)
        readyCache[target.id] = ready
        return ready
    }

    /**
     * Mark the environment installed after a successful install.
     *
     * The installer knows it worked; without this the screen waited for the
     * next poll and the button stayed on "Install" after the work was done.
     */
    fun markReady(target: Target) {
        _state.value = _state.value.copy(
            installed = true,
            ready = true,
            activeReady = true,
            target = target,
            busy = "",
            percent = 100,
            error = "",
        )
    }

    private fun rootfsOf(context: Context, target: Target): File? =
        rootfsOf(context, DistroInstallProfile.forId(target.id))

    /** The rootfs directory of a distro profile, or null when it has none. */
    private fun rootfsOf(
        context: Context,
        profile: DistroInstallProfile?,
    ): File? {
        if (profile == null) return null
        if (profile.method == "chroot") {
            return profile.chrootPath?.let { File(it) }
        }
        if (profile.prootName.isBlank()) return null
        return File(
            context.filesDir,
            "usr/var/lib/proot-distro/containers/${profile.prootName}/rootfs",
        )
    }

    // ----------------------------------------------------------------- install

    /**
     * Start installing REA into [target] and return immediately.
     *
     * Safe to call from a click handler: the work runs on [scope], not on the
     * caller's, so it survives the screen that started it. Calling it while an
     * install is already running is a no-op.
     */
    fun install(
        context: Context,
        target: Target,
        tools: List<String> = ALL_TOOLS,
        onLog: (String) -> Unit = {},
    ) {
        if (isInstalling()) {
            Log.i(TAG, "install already running, ignoring duplicate start")
            return
        }
        val app = context.applicationContext
        installJob = scope.launch {
            runCatching { installBlocking(app, target, tools, onLog) }
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
        target: Target,
        tools: List<String> = ALL_TOOLS,
        onLog: (String) -> Unit = {},
    ): State = withContext(Dispatchers.IO) {
        mutex.withLock {
            val app = context.applicationContext
            val profile = DistroInstallProfile.forId(target.id)
            if (profile == null) {
                _state.value = _state.value.copy(
                    busy = "",
                    error = "This distro cannot host REA: ${target.label}",
                )
                return@withLock _state.value
            }
            if (profile.method == "chroot") {
                // `isRootAvailable()` only reads the cached probe result, so on
                // a cold start it reported "no root" and aborted — which looked
                // like chroot simply not working. Force one real probe first,
                // and say exactly what failed.
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
            // The container must already exist: REA installs *into* a distro the
            // user has, it never downloads a rootfs of its own.
            val rootfs = rootfsOf(app, profile)
            if (rootfs == null || !containerPresent(app, target)) {
                _state.value = _state.value.copy(
                    busy = "",
                    error = "Install " + target.label + " first, then install REA into it.",
                )
                return@withLock _state.value
            }
            try {
                _state.value = State(
                    target = target,
                    targets = targets(app),
                    busy = "Installing the REA toolchain",
                    percent = 0,
                    chrootPossible = RootShell.isRootAvailable(),
                    steps = defaultSteps(),
                    log = "",
                )
                appendLog("installing REA into " + target.label + " (" + profile.method + ")")
                onLog("installing REA into " + target.label + " (" + profile.method + ")")

                val status = ReaManager.install(app, target.id, tools) { line ->
                    onLog(line)
                    // Real progress: the manager reports which tool of how
                    // many, so each completed tool moves the bar by a real
                    // fraction instead of one invented percent per log line.
                    val st = ReaManager.status.value
                    val toolFraction = if (st.stepTotal > 0) {
                        st.stepFraction.coerceIn(0f, 1f) * 99f
                    } else {
                        _state.value.percent.toFloat().coerceAtLeast(0f)
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
                            Step("REA core", done = statusReady()),
                            Step(
                                "Analysis tools (" + st.step + "/" + st.stepTotal + ")",
                                done = st.stepTotal > 0 && st.step >= st.stepTotal,
                            ),
                        ),
                    )
                }
                val ok = status.installed && ReaManager.isInstalledIn(app, target.id)
                if (ok) {
                    // A finished install becomes the one the server uses, and
                    // that choice is remembered so it survives a restart.
                    prefs(app).edit().putString(KEY_ACTIVE_TARGET, target.id).apply()
                    markReady(target)
                    markStep("REA core installed")
                    markStep("Analysis tools installed")
                }
                _state.value = _state.value.copy(
                    busy = "",
                    percent = if (ok) 100 else _state.value.percent,
                    installed = ok,
                    target = target,
                    ready = ok,
                    error = status.error,
                )
                if (ok) invalidateSize()
                refresh(app, deep = true)
                _state.value
            } catch (c: kotlinx.coroutines.CancellationException) {
                // Only happens when the process is going away: the install now
                // runs on an app-lifetime scope, so navigation cannot cancel it.
                Log.i(TAG, "REA install cancelled")
                appendLog("install cancelled")
                _state.value = _state.value.copy(busy = "")
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "REA install failed", t)
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
     * Start a tool repair and return immediately. Same lifetime rule as
     * [install].
     *
     * Used by the dashboard's "install missing tools" action, so a
     * half-finished environment can be repaired without reinstalling anything.
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
            val target = activeTarget(app)
            if (target == null) {
                _state.value = _state.value.copy(error = "Install a distro first")
                return@withLock _state.value
            }
            val status = ReaManager.status.value
            val missing = ALL_TOOLS.filterNot { it in status.available }
            if (missing.isEmpty()) return@withLock _state.value
            _state.value = _state.value.copy(
                busy = "Installing " + missing.size + " tools",
                percent = 0,
                error = "",
            )
            val result = ReaManager.install(app, target.id, missing) { line ->
                onLog(line)
                _state.value = _state.value.copy(
                    busy = line.take(90),
                    percent = (_state.value.percent.coerceAtLeast(0) + 1).coerceAtMost(99),
                )
            }
            val ok = result.error.isBlank()
            if (ok) markStep("Analysis tools installed")
            _state.value = _state.value.copy(
                busy = "",
                percent = if (ok) 100 else _state.value.percent,
                installed = ok || _state.value.installed,
                ready = ok || _state.value.ready,
                error = result.error,
            )
            refresh(app, deep = true)
            _state.value
        }
    }

    // --------------------------------------------------------------- uninstall

    /**
     * Remove the REA toolchain from the selected distro.
     *
     * The distro itself is **not** touched: it is the user's, not REA's. Only
     * `/opt/altaris/rea` inside it is removed. That is the whole difference
     * from the old behaviour, where uninstall deleted a dedicated container.
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
            val target = activeTarget(app)
            if (target == null) {
                _state.value = _state.value.copy(error = "No distro to remove REA from")
                return@withLock _state.value
            }
            _state.value = _state.value.copy(
                busy = "Removing the REA toolchain",
                percent = -1,
                error = "",
                log = "",
            )
            appendLog("removing the REA toolchain from " + target.label)
            // Stop everything that could hold the chroot's files open BEFORE
            // deleting: the MCP bridge and its guest process. A live process
            // inside the chroot is what makes a removal fail.
            runCatching { ReaBridge.stop() }
                .onFailure { Log.w(TAG, "bridge stop failed", it) }
            appendLog("stopped the MCP bridge")
            runCatching { ReaManager.stop() }
                .onFailure { Log.w(TAG, "REA stop failed", it) }
            appendLog("stopped the REA process")

            val removed = runCatching { ReaInstaller.removeToolchain(app, target.id) }
                .onFailure {
                    Log.w(TAG, "toolchain removal failed", it)
                    appendLog("ERROR: " + (it.message ?: "removal failed"))
                }
                .getOrDefault(false)
            appendLog(
                if (removed) "REA toolchain removed from " + target.label
                else "ERROR: the REA toolchain could not be removed"
            )
            invalidateSize()
            readyCache.remove(target.id)

            if (removed) {
                prefs(app).edit().remove(KEY_ACTIVE_TARGET).apply()
                _state.value = State(
                    chrootPossible = RootShell.isRootAvailable(),
                    targets = targets(app),
                )
            } else {
                // Never pretend it is gone: a failed removal must not look like
                // a success, or the environment reappears on the next refresh.
                _state.value = _state.value.copy(
                    busy = "",
                    error = "Could not remove the REA toolchain. " +
                        "The log below names what blocked it.",
                    chrootPossible = RootShell.isRootAvailable(),
                )
                refresh(app, deep = true)
            }
            _state.value
        }
    }

    // ------------------------------------------------------------------ helper

    /** True when the distro's container exists on disk. */
    private fun containerPresent(context: Context, target: Target): Boolean {
        val profile = DistroInstallProfile.forId(target.id) ?: return false
        val rootfs = rootfsOf(context, profile) ?: return false
        if (rootfs.isDirectory && rootfs.list()?.isNotEmpty() == true) return true
        if (!target.isChroot) return false
        // Never fork `su` for a poll. The cached chroot probe is good enough to
        // *display* the state; a deep refresh asks root for the truth.
        if (!RootShell.isRootAvailable()) {
            return runCatching {
                com.altaris.linux.core.chroot.ChrootInfoStore.cachedInstalled(context, target.id)
            }.getOrDefault(false)
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

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

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
            downloadedBytes = _state.value.downloadedBytes,
        )
    }

    /** Publish download bytes, parsed from a runner's detail line. */
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
        Step("REA core installed"),
        Step("Analysis tools installed"),
    )

    private fun sizeOf(context: Context, target: Target?): Long {
        if (target == null) return 0L
        val now = System.currentTimeMillis()
        if (cachedSizeFor == target.id && now - cachedSizeAt < SIZE_TTL_MS) return cachedSize
        val rootfs = rootfsOf(context, target) ?: return 0L
        val size = runCatching {
            rootfs.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
        cachedSize = size
        cachedSizeAt = now
        cachedSizeFor = target.id
        return size
    }

    private fun invalidateSize() {
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
