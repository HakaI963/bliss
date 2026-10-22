package com.altaris.linux.core.install

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.altaris.linux.core.gpu.GpuDriverInstaller
import com.altaris.linux.core.root.RootShell
import com.altaris.linux.core.service.BaseInstallService
import com.altaris.linux.core.terminal.GpuAccelDetector
import com.altaris.linux.core.terminal.HostCommandBuilder
import com.altaris.linux.core.terminal.ShellCommandRunner
import com.altaris.linux.core.terminal.TerminalLauncher
import com.altaris.linux.core.terminal.TermuxHostPaths
import com.altaris.linux.core.utils.InstallationQueueManager
import com.altaris.linux.core.utils.StateManager
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Runs the simplified onboarding / base install with phase progress + live log.
 * Proot: prepareHost → altaris_install (family) → customization via proot-distro.
 * Chroot: root check → prepareHost → setup_*_chroot → family → customization in chroot.
 *
 * Distro identity from [DistroInstallProfile] (Debian + Alpine).
 *
 * Desktop selection ([DesktopChoice] set) is threaded to guest scripts as
 * `ALTARIS_DESKTOPS="xfce kde"` (canonical order; `none` when empty) plus the
 * legacy single-value gates `ALTARIS_DESKTOP` / `ALTARIS_DESKTOP_ENV` (first
 * selection or `none`). Any combination runs both desktop chains; empty =
 * terminal-only. Live progress is mirrored into [InstallationQueueManager]
 * (task bar) and [live] (re-attaching the install screen).
 *
 * Cancel: generation token + destroy active process; never mutates StateManager after cancel.
 * Overlapping [start] cancels the previous run first (across runner instances too).
 */
class OnboardingInstallRunner(private val ctx: Context) {

    data class Progress(
        val phaseId: String,
        val phaseLabel: String,
        val phaseIndex: Int,
        val phaseCount: Int,
        val overallPercent: Int,
        val detail: String,
        val logLine: String? = null,
        val failed: Boolean = false,
        val finished: Boolean = false,
        val errorMessage: String? = null,
        /** Phase has no measurable progress — UI shows an indeterminate bar. */
        val indeterminate: Boolean = false
    )

    private val appCtx = ctx.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val cancelled = AtomicBoolean(false)
    private val generation = AtomicInteger(0)
    private val activeProcess = AtomicReference<Process?>(null)
    private val busy = AtomicBoolean(false)
    /** Throttle FGS notification updates (percent/label change only). */
    @Volatile private var lastNotifPercent: Int = -1
    @Volatile private var lastNotifLabel: String = ""
    /** Last overall percent from real phase updates (log lines must not thrash). */
    @Volatile private var lastOverallPercent: Int = 0
    /** Monotonic sub-phase fraction driven by download bytes / console markers. */
    @Volatile private var phaseFraction: Float = 0f
    /** Latest status detail (log lines must never overwrite it with the phase label). */
    @Volatile private var lastDetail: String = ""
    /** Current phase has no measurable progress — UI shows an indeterminate bar. */
    @Volatile private var lastIndeterminate: Boolean = false
    /** Guest console reported the install finished (strong completion marker). */
    @Volatile private var guestReportedDone: Boolean = false
    /** Teardown watchdog armed once per session (proot can hang after the final line). */
    @Volatile private var watchdogArmed: Boolean = false
    /** Session identity for live/queue publishing (set on start). */
    @Volatile private var sessionDistroId: String? = null
    @Volatile private var sessionTaskName: String = ""
    /** Rolling guest log of the session (mirrored for screen re-attach). */
    @Volatile private var sessionLog: String = ""

    fun cancel() {
        val wasBusy = busy.get()
        cancelled.set(true)
        activeProcess.getAndSet(null)?.destroyForcibly()
        if (!wasBusy) return
        // Superseded by a newer session: only our own process needs to die.
        val owner = activeInstance
        if (owner != null && owner !== this) return
        // User abort of a live session: the task bar reads the queue state, and a
        // plain failed-post would read as an error. Publish the explicit
        // cancelled state + a terminal live snapshot (re-opened/re-attached
        // screens show "Install cancelled" with Retry, not a stale progress bar).
        _live.value = LiveInstall(
            distroId = sessionDistroId ?: "",
            progress = Progress(
                phaseId = "CANCELLED",
                phaseLabel = "Cancelled",
                phaseIndex = 0,
                phaseCount = 1,
                overallPercent = 0,
                detail = "Install cancelled",
                failed = true,
                finished = true,
                errorMessage = "Install cancelled"
            ),
            logText = sessionLog,
            atMillis = System.currentTimeMillis()
        )
        InstallationQueueManager.cancelByUser()
        // Drop keep-alive when user aborts (also safe if FGS was never started)
        BaseInstallService.stop(appCtx)
    }

    fun isBusy(): Boolean = busy.get()

    /**
     * Start (or restart) install. If a previous job is still running (including
     * one owned by another screen's runner instance), it is cancelled and the
     * new job is queued on the single-thread executor.
     *
     * Starts [BaseInstallService] FGS immediately so the system does not kill the
     * process during long rootfs / package work.
     *
     * @param desktops requested base desktops; KDE is silently dropped when the
     *   distro's component data has no `kde_plasma` (see [DesktopInstallSelection]).
     *   Empty set = terminal-only.
     * @param extras optional plan extras; empty = today's defaults (hw-accel on).
     */
    fun start(
        distroId: String,
        theme: String = "dark",
        desktops: Set<DesktopChoice> = setOf(DesktopChoice.XFCE),
        extras: Set<InstallExtra> = emptySet(),
        onProgress: (Progress) -> Unit
    ) {
        // Invalidate any in-flight job before resetting cancel flag
        val gen = generation.incrementAndGet()
        cancelled.set(true)
        activeProcess.getAndSet(null)?.destroyForcibly()
        cancelled.set(false)
        lastOverallPercent = 0
        lastNotifPercent = -1
        lastNotifLabel = ""
        phaseFraction = 0f
        lastDetail = ""
        lastIndeterminate = false
        guestReportedDone = false
        watchdogArmed = false
        sessionLog = ""

        val profile = DistroInstallProfile.forId(distroId)
        if (profile == null) {
            busy.set(false)
            main.post {
                onProgress(
                    Progress(
                        phaseId = "ERR",
                        phaseLabel = "Failed",
                        phaseIndex = 0,
                        phaseCount = 1,
                        overallPercent = 0,
                        detail = "Unsupported distro: $distroId",
                        failed = true,
                        finished = true,
                        errorMessage = "Unsupported distro: $distroId"
                    )
                )
            }
            return
        }
        val method = profile.method
        val choiceSet = DesktopInstallSelection.sanitize(distroId, desktops)
        val phases = BaseDesktopInstallPlan.phasesFor(
            method, profile.displayName, choiceSet
        )
        sessionDistroId = distroId
        sessionTaskName =
            "${profile.displayName} · ${DesktopInstallSelection.summaryLabel(choiceSet)}"
        // Supersede a session owned by another runner instance (e.g. the user
        // kicked off a second install from a different screen while one ran).
        activeInstance?.let { other -> if (other !== this && other.isBusy()) other.cancel() }
        activeInstance = this
        busy.set(true)
        lastNotifPercent = -1
        lastNotifLabel = ""
        // FGS first — before any long work — so backgrounding mid-install is safe
        try {
            BaseInstallService.start(
                appCtx,
                title = "Altaris — Installing",
                text = sessionTaskName,
                percent = 0
            )
        } catch (e: Exception) {
            Log.e(TAG, "BaseInstallService.start failed", e)
        }
        // Instant feedback for the task bar (the first real phase follows on the
        // executor). Publishing here also clears any prior terminal snapshot.
        publishLive(
            Progress(
                phaseId = "INIT",
                phaseLabel = "Starting…",
                phaseIndex = 0,
                phaseCount = phases.size.coerceAtLeast(1),
                overallPercent = 0,
                detail = "Preparing install…"
            )
        )
        executor.execute {
            try {
                if (isStale(gen)) {
                    postCancelled(onProgress, phases)
                    return@execute
                }
                if (DesktopChoice.KDE in desktops && DesktopChoice.KDE !in choiceSet) {
                    log(
                        phases, 0, onProgress,
                        "KDE Plasma not available for this distro — installing " +
                            "${DesktopInstallSelection.summaryLabel(choiceSet)} instead"
                    )
                }
                if (method == "chroot") {
                    runChroot(distroId, theme, choiceSet, extras, phases, onProgress, gen)
                } else {
                    runProot(distroId, theme, choiceSet, extras, phases, onProgress, gen)
                }
            } catch (e: Exception) {
                if (!isStale(gen)) {
                    Log.e(TAG, "install failed", e)
                    postFail(onProgress, phases, e.message ?: "Unknown error")
                } else {
                    postCancelled(onProgress, phases)
                }
            } finally {
                if (generation.get() == gen) {
                    busy.set(false)
                    BaseInstallService.stop(appCtx)
                    if (activeInstance === this) activeInstance = null
                }
            }
        }
    }

    private fun isStale(gen: Int): Boolean =
        cancelled.get() || generation.get() != gen

    /** Phase index by [Phase.id] — never a hardcoded numeric index (D10). */
    private fun phaseIdx(phases: List<BaseDesktopInstallPlan.Phase>, id: String): Int =
        phases.indexOfFirst { it.id == id }.also { i ->
            require(i >= 0) { "install plan missing phase '$id'" }
        }

    private fun abortIfCancelled(
        gen: Int,
        phases: List<BaseDesktopInstallPlan.Phase>,
        onProgress: (Progress) -> Unit
    ): Boolean {
        if (!isStale(gen)) return false
        postCancelled(onProgress, phases)
        return true
    }

    private fun runProot(
        distroId: String,
        theme: String,
        desktops: Set<DesktopChoice>,
        extras: Set<InstallExtra>,
        phases: List<BaseDesktopInstallPlan.Phase>,
        onProgress: (Progress) -> Unit,
        gen: Int
    ) {
        val xfceSelected = DesktopChoice.XFCE in desktops
        val kdeSelected = DesktopChoice.KDE in desktops
        val hostIdx = phaseIdx(phases, "HOST")
        val dlIdx = phaseIdx(phases, "DL")
        val rootfsIdx = phaseIdx(phases, "ROOTFS")
        val customIdx = phaseIdx(phases, "CUSTOM")

        enter(phases, hostIdx, onProgress, "Extracting bootstrap + deploying scripts…", indeterminate = true)
        if (abortIfCancelled(gen, phases, onProgress)) return
        var lastHostPhase = ""
        val hostOk = TerminalLauncher.prepareHostBlocking(appCtx, forceHostSetup = false) { done, total, phase ->
            if (isStale(gen)) return@prepareHostBlocking
            val frac = if (total > 0) done.toFloat() / total else 0f
            updateFraction(phases, hostIdx, frac, onProgress, phase, indeterminate = total <= 0)
            if (phase.isNotBlank() && phase != lastHostPhase) {
                lastHostPhase = phase
                log(phases, hostIdx, onProgress, phase)
            }
        }
        if (abortIfCancelled(gen, phases, onProgress)) return
        if (!hostOk) {
            postFail(onProgress, phases, "Host bootstrap failed")
            return
        }
        completePhase(phases, hostIdx, onProgress, "Host ready")

        val profile = DistroInstallProfile.require(distroId)
        enter(
            phases, dlIdx, onProgress,
            "Downloading ${profile.displayName} rootfs…",
            indeterminate = true
        )
        if (abortIfCancelled(gen, phases, onProgress)) return
        val dlOk = downloadRootfs(profile, phases, dlIdx, onProgress, gen)
        if (abortIfCancelled(gen, phases, onProgress)) return
        if (!dlOk) {
            postFail(
                onProgress, phases,
                "Rootfs download failed — place ${profile.rootfsFileName} in the app " +
                    "home directory (${TermuxHostPaths.HOME}) or retry online"
            )
            return
        }
        completePhase(phases, dlIdx, onProgress, "Rootfs ready (${profile.rootfsFileName})")

        enter(
            phases, rootfsIdx, onProgress,
            "Extracting rootfs…",
            indeterminate = true
        )
        if (abortIfCancelled(gen, phases, onProgress)) return
        val bash = TermuxHostPaths.libBash(appCtx).absolutePath
        val installScript = TermuxHostPaths.hostScript(appCtx, "altaris_install.sh").absolutePath
        val setupB64 = BaseDesktopInstallPlan.familySetupB64(
            appCtx, theme, distroId, desktops
        )
        val desktopsEnv = DesktopInstallSelection.envValue(desktops)
        val firstEnv = DesktopInstallSelection.firstEnvValue(desktops)
        val env = HostCommandBuilder.envMap(appCtx, forceHostSetup = false, includeTerm = false).apply {
            put("PYTHONUNBUFFERED", "1")
            // ALTARIS_DESKTOPS contract — family scripts gate desktop packages on
            // the set; ALTARIS_DESKTOP/_ENV stay the legacy single-value gates.
            put("ALTARIS_DESKTOPS", desktopsEnv)
            put("ALTARIS_DESKTOP", firstEnv)
            put("ALTARIS_DESKTOP_ENV", firstEnv)
            put("ALTARIS_ROOTFS_PATH", "${TermuxHostPaths.HOME}/${profile.rootfsFileName}")
            put("ALTARIS_ROOTFS_NAME", profile.rootfsFileName)
            put("ALTARIS_ROOTFS_SHA256", profile.rootfsSha256)
            put("ALTARIS_ROOTFS_URL", profile.rootfsUrl)
        }
        // Never exec $PREFIX/bin/* as argv0 — W^X (targetSdk 36) only allows
        // nativeLibraryDir (libbash.so / libproot.so). stdbuf lives under PREFIX
        // and fails with EACCES (error 13). Stream lines as the pipe delivers them.
        val (exitInstall, _) = ShellCommandRunner.runCaptureExit(
            appCtx,
            arrayOf(bash, installScript, profile.prootName, setupB64),
            env,
            processHolder = activeProcess,
            onLine = { line ->
                if (!isStale(gen) && line.isNotBlank()) log(phases, rootfsIdx, onProgress, line)
            }
        )
        if (abortIfCancelled(gen, phases, onProgress)) return
        // The guest's final success marker wins over a teardown kill (proot can
        // hang after the last line; see armTeardownWatchdog).
        if (exitInstall != 0 && !guestReportedDone) {
            postFail(onProgress, phases, "${profile.displayName} install failed (exit $exitInstall)")
            return
        }
        if (!TerminalLauncher.isProotInstalled(appCtx, profile.prootName)) {
            postFail(onProgress, phases, "${profile.displayName} rootfs missing after install")
            return
        }
        if (xfceSelected && !isProotXfceInstalled(appCtx, profile.prootName)) {
            postFail(
                onProgress, phases,
                "XFCE not found after install (startxfce4 missing). Retry setup."
            )
            return
        }
        if (kdeSelected) {
            val kdeScript = DesktopInstallSelection.componentScriptName(
                distroId, DesktopInstallSelection.COMPONENT_KDE_PLASMA
            )
            if (kdeScript == null) {
                postFail(onProgress, phases, "KDE Plasma component missing for $distroId")
                return
            }
            log(phases, rootfsIdx, onProgress, "Installing KDE Plasma desktop…")
            val kdeOk = runProotGuestScript(
                phases, rootfsIdx, onProgress, gen,
                prootName = profile.prootName,
                scriptAssetPath = kdeScript,
                envPrefix = "ALTARIS_DESKTOPS=${DesktopChoice.KDE.envValue} " +
                    "ALTARIS_DESKTOP=${DesktopChoice.KDE.envValue}"
            )
            if (abortIfCancelled(gen, phases, onProgress)) return
            if (!kdeOk || !isProotKdeInstalled(appCtx, profile.prootName)) {
                postFail(
                    onProgress, phases,
                    "KDE Plasma not found after install (startplasma-x11 missing). Retry."
                )
                return
            }
        }
        if (desktops.isEmpty()) {
            log(phases, rootfsIdx, onProgress, "Terminal base install — no desktop packages")
        }
        completePhase(
            phases, rootfsIdx, onProgress,
            when {
                desktops.isEmpty() ->
                    "${profile.displayName} rootfs installed (terminal base)"
                xfceSelected && kdeSelected ->
                    "${profile.displayName} + XFCE + KDE Plasma installed"
                kdeSelected -> "${profile.displayName} + KDE Plasma installed"
                else -> "${profile.displayName} + XFCE installed"
            }
        )

        enter(
            phases, customIdx, onProgress,
            when {
                desktops.isEmpty() -> "No desktop customization (terminal only)"
                xfceSelected && kdeSelected -> "XFCE4 + KDE theming, wallpapers, fonts…"
                kdeSelected -> "KDE theming, wallpapers, Zsh…"
                else -> "Themes, wallpapers, fonts…"
            },
            indeterminate = true
        )
        if (abortIfCancelled(gen, phases, onProgress)) return
        var xfceCustomOk = false
        var kdeCustomOk = false
        if (xfceSelected) {
            // Native host extract into proot rootfs (avoids proot thrashing on Papirus).
            // Skip guest re-extract when this succeeds or assets are already present.
            log(phases, customIdx, onProgress, "Staging XFCE theme/icons on host (native tar)…")
            val hostThemeOk = try {
                ProotXfceAssetInstaller.install(appCtx, theme, profile.prootName) { line ->
                    if (!isStale(gen) && line.isNotBlank()) log(phases, customIdx, onProgress, line)
                }
            } catch (e: Exception) {
                log(phases, customIdx, onProgress, "Host theme stage error: ${e.message}")
                false
            }
            if (abortIfCancelled(gen, phases, onProgress)) return
            log(phases, customIdx, onProgress, "Staging Oh My Zsh on host (avoids proot hang)…")
            val hostOmzOk = try {
                ProotZshBootstrap.install(appCtx, profile.prootName) { line ->
                    if (!isStale(gen) && line.isNotBlank()) log(phases, customIdx, onProgress, line)
                }
            } catch (e: Exception) {
                log(phases, customIdx, onProgress, "Host Oh My Zsh stage error: ${e.message}")
                false
            }
            if (abortIfCancelled(gen, phases, onProgress)) return
            val skipAssets = if (hostThemeOk) "1" else "0"
            val skipOmz = if (hostOmzOk) "1" else "0"
            xfceCustomOk = runProotGuestScript(
                phases, customIdx, onProgress, gen,
                prootName = profile.prootName,
                scriptAssetPath = profile.customizationScript,
                envPrefix = "ALTARIS_THEME=$theme " +
                    "ALTARIS_SKIP_THEME_ICONS=$skipAssets " +
                    "ALTARIS_SKIP_OMZ=$skipOmz " +
                    "ALTARIS_SKIP_POKEMON=0 " +
                    "ALTARIS_ASSET_DIR=/tmp/altaris_xfce_assets"
            )
            if (abortIfCancelled(gen, phases, onProgress)) return
            if (!xfceCustomOk) {
                log(
                    phases, customIdx, onProgress,
                    "XFCE customization failed or partial — you can retry from Distro Settings"
                )
            }
        }
        if (kdeSelected) {
            // KDE customization downloads its own theme/icons/OMZ (no host staging).
            val kdeCustomScript = DesktopInstallSelection.componentScriptName(
                distroId, DesktopInstallSelection.COMPONENT_KDE_CUSTOMIZATION
            )
            if (kdeCustomScript == null) {
                log(phases, customIdx, onProgress, "KDE customization component missing — skipped")
            } else {
                kdeCustomOk = runProotGuestScript(
                    phases, customIdx, onProgress, gen,
                    prootName = profile.prootName,
                    scriptAssetPath = kdeCustomScript,
                    envPrefix = "ALTARIS_THEME=$theme " +
                        "ALTARIS_DESKTOPS=${DesktopChoice.KDE.envValue} " +
                        "ALTARIS_DESKTOP=${DesktopChoice.KDE.envValue}"
                )
                if (abortIfCancelled(gen, phases, onProgress)) return
                if (!kdeCustomOk) {
                    log(
                        phases, customIdx, onProgress,
                        "KDE customization failed or partial — you can retry from Distro Settings"
                    )
                }
            }
        }
        if (desktops.isEmpty()) {
            log(phases, customIdx, onProgress, "Terminal-only install — desktop customization skipped")
        }

        if (abortIfCancelled(gen, phases, onProgress)) return
        val hwOk = if (hwAccelWanted(desktops, extras)) {
            runHwAccelIfPresent(
                profile, method = "proot", phases, customIdx, onProgress, gen, chrootPath = null
            )
        } else {
            log(
                phases, customIdx, onProgress,
                if (desktops.isEmpty()) "Hardware acceleration skipped (terminal only)"
                else "Hardware acceleration skipped (user choice)"
            )
            false
        }

        completePhase(
            phases, customIdx, onProgress,
            when {
                desktops.isEmpty() -> "Terminal base ready"
                xfceSelected && kdeSelected -> "XFCE4 + KDE customization done"
                kdeSelected -> "KDE customization done"
                else -> "Customization done"
            }
        )

        if (abortIfCancelled(gen, phases, onProgress)) return
        StateManager.setDistroInstalled(appCtx, distroId, true)
        if (xfceSelected) {
            StateManager.setComponentInstalled(
                appCtx, distroId, DesktopInstallSelection.COMPONENT_XFCE, true
            )
            if (xfceCustomOk) {
                StateManager.setComponentInstalled(
                    appCtx, distroId, DesktopInstallSelection.COMPONENT_CUSTOMIZATION, true
                )
            }
        }
        if (kdeSelected) {
            StateManager.setComponentInstalled(
                appCtx, distroId, DesktopInstallSelection.COMPONENT_KDE_PLASMA, true
            )
            if (kdeCustomOk) {
                StateManager.setComponentInstalled(
                    appCtx, distroId, DesktopInstallSelection.COMPONENT_KDE_CUSTOMIZATION, true
                )
            }
        }
        if (hwOk) {
            StateManager.setComponentInstalled(appCtx, distroId, "hw_accel", true)
        }
        StateManager.triggerRefresh()
        postSuccess(onProgress, phases)
    }

    private fun runChroot(
        distroId: String,
        theme: String,
        desktops: Set<DesktopChoice>,
        extras: Set<InstallExtra>,
        phases: List<BaseDesktopInstallPlan.Phase>,
        onProgress: (Progress) -> Unit,
        gen: Int
    ) {
        val xfceSelected = DesktopChoice.XFCE in desktops
        val kdeSelected = DesktopChoice.KDE in desktops
        val r0Idx = phaseIdx(phases, "R0")
        val hostIdx = phaseIdx(phases, "HOST")
        val dlIdx = phaseIdx(phases, "DL")
        val rootfsIdx = phaseIdx(phases, "ROOTFS")
        val xfceIdx = phaseIdx(phases, "XFCE")
        val customIdx = phaseIdx(phases, "CUSTOM")

        enter(phases, r0Idx, onProgress, "Probing KernelSU / Magisk…")
        if (abortIfCancelled(gen, phases, onProgress)) return
        if (!RootShell.isRootAvailable()) {
            postFail(
                onProgress, phases,
                "Root not available. Grant superuser to Altaris, then retry."
            )
            return
        }
        completePhase(phases, r0Idx, onProgress, "Root OK")
        RootShell.ensureBusyBoxResolver(appCtx)
        val resolvedBb = RootShell.resolveBusyBox()

        enter(phases, hostIdx, onProgress, "Extracting bootstrap + deploying scripts…", indeterminate = true)
        if (abortIfCancelled(gen, phases, onProgress)) return
        var lastHostPhase = ""
        val hostOk = TerminalLauncher.prepareHostBlocking(appCtx, forceHostSetup = false) { done, total, phase ->
            if (isStale(gen)) return@prepareHostBlocking
            val frac = if (total > 0) done.toFloat() / total else 0f
            updateFraction(phases, hostIdx, frac, onProgress, phase, indeterminate = total <= 0)
            if (phase.isNotBlank() && phase != lastHostPhase) {
                lastHostPhase = phase
                log(phases, hostIdx, onProgress, phase)
            }
        }
        if (abortIfCancelled(gen, phases, onProgress)) return
        if (!hostOk) {
            postFail(onProgress, phases, "Host bootstrap failed")
            return
        }
        completePhase(phases, hostIdx, onProgress, "Host ready")

        val profile = DistroInstallProfile.require(distroId)

        // DL after HOST only — R0 stays first so non-rooted devices fail
        // before any download (R6).
        enter(
            phases, dlIdx, onProgress,
            "Downloading ${profile.displayName} rootfs…",
            indeterminate = true
        )
        if (abortIfCancelled(gen, phases, onProgress)) return
        val dlOk = downloadRootfs(profile, phases, dlIdx, onProgress, gen)
        if (abortIfCancelled(gen, phases, onProgress)) return
        if (!dlOk) {
            postFail(
                onProgress, phases,
                "Rootfs download failed — place ${profile.rootfsFileName} in the app " +
                    "home directory (${TermuxHostPaths.HOME}) or retry online"
            )
            return
        }
        completePhase(phases, dlIdx, onProgress, "Rootfs ready (${profile.rootfsFileName})")

        val chrootPath = profile.chrootPath
            ?: com.altaris.linux.core.root.ChrootPaths.CHROOT_PATH
        val setupAsset = profile.chrootSetupAsset
            ?: "scripts/chroot/setup_debian13_chroot.sh"
        val setupName = File(setupAsset).name

        enter(
            phases, rootfsIdx, onProgress,
            "Extracting chroot rootfs (may take several minutes)…",
            indeterminate = true
        )
        if (abortIfCancelled(gen, phases, onProgress)) return
        val staged = RootShell.stageAsset(appCtx, setupAsset)
            ?: TermuxHostPaths.hostScript(appCtx, setupName).absolutePath
        val envHome = TermuxHostPaths.HOME
        // Do not wrap with $PREFIX/bin/stdbuf — host W^X denies exec from app data.
        val label = profile.distroId.removeSuffix("_chroot")
        val bbExport = if (!resolvedBb.isNullOrEmpty()) "export ALTARIS_BB='$resolvedBb'; " else ""
        val rootCmd =
            bbExport +
            "export ALTARIS_ROOTFS_PATH='$envHome/${profile.rootfsFileName}'; " +
                "export ALTARIS_ROOTFS_NAME='${profile.rootfsFileName}'; " +
                "export ALTARIS_ROOTFS_SHA256='${profile.rootfsSha256}'; " +
                "export ALTARIS_ROOTFS_URL='${profile.rootfsUrl}'; " +
                "export ALTARIS_CHROOT='$chrootPath'; " +
                "export ALTARIS_DISTRO_LABEL='$label'; " +
                "export ALTARIS_DESKTOPS='${DesktopInstallSelection.envValue(desktops)}'; " +
                "export ALTARIS_DESKTOP='${DesktopInstallSelection.firstEnvValue(desktops)}'; " +
                "export ALTARIS_DESKTOP_ENV='${DesktopInstallSelection.firstEnvValue(desktops)}'; " +
                "export TERMUX_APP__PACKAGE_NAME='${TermuxHostPaths.PACKAGE}'; " +
                "export TERMUX__HOME='$envHome'; " +
                "export PYTHONUNBUFFERED=1; " +
                "sh '$staged'"
        val rootResult = RootShell.captureResult(
            rootCmd,
            timeoutMs = 0L,
            onLine = { line ->
                if (!isStale(gen) && line.isNotBlank()) log(phases, rootfsIdx, onProgress, line)
            },
            processHolder = activeProcess
        )
        if (abortIfCancelled(gen, phases, onProgress)) return
        if (rootResult.exitCode != 0) {
            postFail(onProgress, phases, "Chroot install failed (exit ${rootResult.exitCode})")
            return
        }
        // App SELinux cannot see /data/local/tmp — always re-probe as root after install.
        TerminalLauncher.invalidateChrootInstalledCache()
        val installed = TerminalLauncher.isChrootInstalled(chrootPath)
        if (!installed) {
            log(phases, rootfsIdx, onProgress, "Root probe: chroot missing at $chrootPath")
            postFail(
                onProgress, phases,
                "Chroot rootfs missing after install (check root grant + $chrootPath)"
            )
            return
        }
        log(phases, rootfsIdx, onProgress, "Chroot rootfs verified (root probe)")
        completePhase(phases, rootfsIdx, onProgress, "Chroot rootfs ready")

        enter(
            phases, xfceIdx, onProgress,
            when {
                desktops.isEmpty() -> "Configuring terminal base…"
                xfceSelected && kdeSelected -> "Installing XFCE + KDE Plasma packages…"
                kdeSelected -> "Installing KDE Plasma packages…"
                else -> "Installing XFCE packages…"
            },
            indeterminate = true
        )
        if (abortIfCancelled(gen, phases, onProgress)) return
        val familyPayload = BaseDesktopInstallPlan.familySetupPayload(
            appCtx, theme, distroId, desktops
        )
        val familyExit = runChrootGuestBlocking(
            familyPayload, user = "root", phases, xfceIdx, onProgress, chrootPath
        )
        if (abortIfCancelled(gen, phases, onProgress)) return
        if (familyExit != 0) {
            val setupLabel = when {
                desktops.isEmpty() -> "Base system"
                xfceSelected && kdeSelected -> "XFCE + KDE base"
                kdeSelected -> "KDE base"
                else -> "XFCE"
            }
            postFail(onProgress, phases, "$setupLabel setup failed (exit $familyExit)")
            return
        }
        if (xfceSelected && !TerminalLauncher.isChrootXfceInstalled(chrootPath)) {
            postFail(
                onProgress, phases,
                "XFCE not found after family setup (startxfce4 missing). Retry."
            )
            return
        }
        if (kdeSelected) {
            val kdePayload = BaseDesktopInstallPlan.kdePlasmaPayload(appCtx, theme, distroId)
            if (kdePayload == null) {
                postFail(onProgress, phases, "KDE Plasma component missing for $distroId")
                return
            }
            val kdeExit = runChrootGuestBlocking(
                kdePayload, user = "root", phases, xfceIdx, onProgress, chrootPath
            )
            if (abortIfCancelled(gen, phases, onProgress)) return
            if (kdeExit != 0) {
                postFail(onProgress, phases, "KDE Plasma setup failed (exit $kdeExit)")
                return
            }
            if (!isChrootKdeInstalled(chrootPath)) {
                postFail(
                    onProgress, phases,
                    "KDE Plasma not found after setup (startplasma-x11 missing). Retry."
                )
                return
            }
        }
        if (desktops.isEmpty()) {
            log(phases, xfceIdx, onProgress, "Terminal base install — no desktop packages")
        }
        completePhase(
            phases, xfceIdx, onProgress,
            when {
                desktops.isEmpty() -> "Base system ready"
                xfceSelected && kdeSelected -> "XFCE + KDE Plasma installed"
                kdeSelected -> "KDE Plasma installed"
                else -> "XFCE installed"
            }
        )

        enter(
            phases, customIdx, onProgress,
            when {
                desktops.isEmpty() -> "No desktop customization (terminal only)"
                xfceSelected && kdeSelected -> "XFCE4 + KDE theming, wallpapers, fonts…"
                kdeSelected -> "KDE theming, wallpapers, Zsh…"
                else -> "Themes, wallpapers, fonts…"
            },
            indeterminate = true
        )
        if (abortIfCancelled(gen, phases, onProgress)) return
        var xfceCustomOk = false
        var kdeCustomOk = false
        if (xfceSelected) {
            // Stage theme/icon/cursor/wallpaper into the chroot guest /tmp (root copy).
            // The customization script extracts from /tmp/altaris_xfce_assets with guest
            // tar; icons have no download fallback, so this staging is mandatory.
            log(phases, customIdx, onProgress, "Staging XFCE theme/icons into chroot…")
            try {
                com.altaris.linux.core.install.ProotXfceAssetInstaller.installToChroot(
                    appCtx, theme, chrootPath
                ) { line ->
                    if (!isStale(gen) && line.isNotBlank()) log(phases, customIdx, onProgress, line)
                }
            } catch (e: Exception) {
                log(phases, customIdx, onProgress, "chroot theme stage error: ${e.message}")
            }
            val customPayload = BaseDesktopInstallPlan.customizationPayload(
                appCtx, theme, distroId, DesktopChoice.XFCE
            )
            val customExit = runChrootGuestBlocking(
                customPayload, user = "root", phases, customIdx, onProgress, chrootPath
            )
            if (abortIfCancelled(gen, phases, onProgress)) return
            if (customExit != 0) {
                log(phases, customIdx, onProgress, "XFCE customization failed or partial (exit $customExit)")
            }
            xfceCustomOk = customExit == 0
        }
        if (kdeSelected) {
            // KDE customization downloads its own theme/icons/OMZ — no host staging.
            val customPayload = BaseDesktopInstallPlan.customizationPayload(
                appCtx, theme, distroId, DesktopChoice.KDE
            )
            val customExit = runChrootGuestBlocking(
                customPayload, user = "root", phases, customIdx, onProgress, chrootPath
            )
            if (abortIfCancelled(gen, phases, onProgress)) return
            if (customExit != 0) {
                log(phases, customIdx, onProgress, "KDE customization failed or partial (exit $customExit)")
            }
            kdeCustomOk = customExit == 0
        }
        if (desktops.isEmpty()) {
            log(phases, customIdx, onProgress, "Terminal-only install — desktop customization skipped")
        }

        if (abortIfCancelled(gen, phases, onProgress)) return
        val hwOk = if (hwAccelWanted(desktops, extras)) {
            runHwAccelIfPresent(
                profile, method = "chroot", phases, customIdx, onProgress, gen, chrootPath = chrootPath
            )
        } else {
            log(
                phases, customIdx, onProgress,
                if (desktops.isEmpty()) "Hardware acceleration skipped (terminal only)"
                else "Hardware acceleration skipped (user choice)"
            )
            false
        }

        completePhase(
            phases, customIdx, onProgress,
            when {
                desktops.isEmpty() -> "Terminal base ready"
                xfceSelected && kdeSelected -> "XFCE4 + KDE customization done"
                kdeSelected -> "KDE customization done"
                else -> "Customization done"
            }
        )

        if (abortIfCancelled(gen, phases, onProgress)) return
        StateManager.setDistroInstalled(appCtx, distroId, true)
        if (xfceSelected) {
            StateManager.setComponentInstalled(
                appCtx, distroId, DesktopInstallSelection.COMPONENT_XFCE, true
            )
            if (xfceCustomOk) {
                StateManager.setComponentInstalled(
                    appCtx, distroId, DesktopInstallSelection.COMPONENT_CUSTOMIZATION, true
                )
            }
        }
        if (kdeSelected) {
            StateManager.setComponentInstalled(
                appCtx, distroId, DesktopInstallSelection.COMPONENT_KDE_PLASMA, true
            )
            if (kdeCustomOk) {
                StateManager.setComponentInstalled(
                    appCtx, distroId, DesktopInstallSelection.COMPONENT_KDE_CUSTOMIZATION, true
                )
            }
        }
        if (hwOk) {
            StateManager.setComponentInstalled(appCtx, distroId, "hw_accel", true)
        }
        StateManager.triggerRefresh()
        postSuccess(onProgress, phases)
    }

    /**
     * Hardware acceleration runs for desktop installs unless the user opted out
     * ([InstallExtra.SKIP_HW_ACCEL]); terminal-only installs never run it.
     */
    private fun hwAccelWanted(desktops: Set<DesktopChoice>, extras: Set<InstallExtra>): Boolean =
        desktops.isNotEmpty() && InstallExtra.SKIP_HW_ACCEL !in extras

    /**
     * Run the guest hw-accel installer with host-detected ALTARIS_GPU.
     * Failure is logged and does not fail onboarding (family first-paint is VirGL).
     */
    private fun runHwAccelIfPresent(
        profile: DistroInstallProfile,
        method: String,
        phases: List<BaseDesktopInstallPlan.Phase>,
        phaseIndex: Int,
        onProgress: (Progress) -> Unit,
        gen: Int,
        chrootPath: String?
    ): Boolean {
        val script = profile.hwAccelScript ?: return false
        val gpu = GpuAccelDetector.detect()
        GpuAccelDetector.persist(appCtx, gpu)
        log(
            phases, phaseIndex, onProgress,
            "Hardware acceleration: mode=${gpu.mode} vendor=${gpu.vendorHint}"
        )
        // Adreno: app picks the stable Turnip release (empty map elsewhere).
        val driverEnv = GpuDriverInstaller.guestEnv(appCtx, profile.distroId)
        (driverEnv["ALTARIS_TURNIP_MSG"] ?: driverEnv["ALTARIS_PANVK_MSG"])?.let { log(phases, phaseIndex, onProgress, it) }
        val ok = try {
            if (method == "chroot") {
                val path = chrootPath
                    ?: com.altaris.linux.core.root.ChrootPaths.CHROOT_PATH
                val payload = BaseDesktopInstallPlan.hwAccelPayload(
                    appCtx, profile.distroId, gpu.mode, gpu.vendorHint, driverEnv
                )
                runChrootGuestBlocking(payload, user = "root", phases, phaseIndex, onProgress, path) == 0
            } else {
                stageAltarisGpuCommon()
                runProotGuestScript(
                    phases, phaseIndex, onProgress, gen,
                    prootName = profile.prootName,
                    scriptAssetPath = script,
                    envPrefix = "ALTARIS_GPU=${gpu.mode} ALTARIS_GPU_VENDOR=${gpu.vendorHint}" +
                        driverEnv.entries.joinToString("") { entry ->
                            val sanitized = entry.value.replace("'", "")
                            " ${entry.key}='$sanitized'"
                        }
                )
            }
        } catch (e: Exception) {
            log(phases, phaseIndex, onProgress, "Hardware acceleration error: ${e.message}")
            false
        }
        if (driverEnv.isNotEmpty()) {
            GpuDriverInstaller.refreshInstalled(
                appCtx, profile.distroId, profile.prootName, method == "chroot"
            )
        }
        if (GpuDriverInstaller.pendingDistro(appCtx) != null) {
            GpuDriverInstaller.onPending?.invoke()
        }
        if (ok) {
            log(phases, phaseIndex, onProgress, "Hardware acceleration installed (${gpu.mode})")
        } else {
            log(
                phases, phaseIndex, onProgress,
                "Hardware acceleration skipped or failed — VirGL first-paint remains"
            )
        }
        return ok
    }

    private fun stageAltarisGpuCommon() {
        val tmp = File(TermuxHostPaths.TMPDIR)
        if (!tmp.exists()) tmp.mkdirs()
        val dest = File(tmp, "altaris_gpu_common.sh")
        appCtx.assets.open("scripts/common/setup/altaris_gpu_common.sh").use { input ->
            dest.outputStream().use { input.copyTo(it) }
        }
        dest.setExecutable(true)
    }

    private fun runProotGuestScript(
        phases: List<BaseDesktopInstallPlan.Phase>,
        phaseIndex: Int,
        onProgress: (Progress) -> Unit,
        gen: Int,
        prootName: String,
        scriptAssetPath: String,
        envPrefix: String
    ): Boolean {
        return try {
            val tmp = File(TermuxHostPaths.TMPDIR)
            if (!tmp.exists()) tmp.mkdirs()
            val name = File(scriptAssetPath).name
            val dest = File(tmp, name)
            appCtx.assets.open("scripts/$scriptAssetPath").use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
            dest.setExecutable(true)
            val bash = TermuxHostPaths.libBash(appCtx).absolutePath
            // Prefer bash if present (Debian); Alpine family installs bash before customization.
            // libbash.so only — never prefix stdbuf (W^X EACCES on $PREFIX/bin)
            val guest =
                "env $envPrefix PYTHONUNBUFFERED=1 " +
                    "sh -c 'if [ -x /bin/bash ]; then exec /bin/bash /tmp/$name; " +
                    "else exec /bin/sh /tmp/$name; fi'"
            val cmd = arrayOf(
                bash, "-c",
                "exec python ${TermuxHostPaths.PROOT_DISTRO} login $prootName --shared-tmp -- $guest"
            )
            val env = HostCommandBuilder.envMap(appCtx, includeTerm = false).apply {
                put("PYTHONUNBUFFERED", "1")
            }
            val (exit, _) = ShellCommandRunner.runCaptureExit(
                appCtx, cmd, env,
                processHolder = activeProcess,
                onLine = { line ->
                    if (!isStale(gen) && line.isNotBlank()) {
                        log(phases, phaseIndex, onProgress, line)
                    }
                }
            )
            if (isStale(gen)) return false
            exit == 0
        } catch (e: Exception) {
            if (!isStale(gen)) {
                log(phases, phaseIndex, onProgress, "Guest script error: ${e.message}")
            }
            false
        }
    }

    private fun runChrootGuestBlocking(
        scriptBody: String,
        user: String,
        phases: List<BaseDesktopInstallPlan.Phase>,
        phaseIndex: Int,
        onProgress: (Progress) -> Unit,
        chrootPath: String = com.altaris.linux.core.root.ChrootPaths.CHROOT_PATH
    ): Int {
        // A single `su -c` argument is capped (~128 KB) by the kernel, and the
        // component scripts (with the embedded sticker art) are far bigger, so
        // the body is staged into the chroot in chunks first and executed by
        // path. `guest_b64` prefers /bin/bash when present, else /bin/sh.
        val dest = "/.altaris-guest-step.sh"
        val body = "export PYTHONUNBUFFERED=1\n$scriptBody"
        val staged = com.altaris.linux.core.root.ChrootStager.writeFiles(
            appCtx, chrootPath, listOf(dest to body), user = "root"
        )
        if (!staged) {
            log(phases, phaseIndex, onProgress, "guest script staging failed (no root or chroot busy)")
            return -1
        }
        val result = RootShell.captureInChroot(
            "sh $dest; _rc=\$?; rm -f $dest 2>/dev/null; exit \$_rc",
            user = user,
            context = appCtx,
            chrootPath = chrootPath,
            timeoutMs = 0L,
            onLine = { line ->
                if (line.isNotBlank()) log(phases, phaseIndex, onProgress, line)
            },
            processHolder = activeProcess
        )
        return result.exitCode
    }

    /**
     * Download the distro rootfs with live byte/speed progress in the DL phase.
     * Unknown totals fall back to an indeterminate detail — the bar is only
     * shown as determinate when the byte count is actually known.
     */
    private fun downloadRootfs(
        profile: DistroInstallProfile,
        phases: List<BaseDesktopInstallPlan.Phase>,
        index: Int,
        onProgress: (Progress) -> Unit,
        gen: Int
    ): Boolean {
        val destDir = TermuxHostPaths.homeDir(appCtx)
        var lastBytes = 0L
        var lastAt = 0L
        return RootfsDownloader.ensurePresent(
            destDir, profile, RootfsDownloader.defaultClient,
            isCancelled = { isStale(gen) }
        ) { p ->
            if (isStale(gen)) return@ensurePresent
            val now = System.currentTimeMillis()
            val speed = if (lastAt > 0L) {
                val dt = (now - lastAt).coerceAtLeast(1L)
                (p.downloadedBytes - lastBytes).coerceAtLeast(0L) * 1000.0 / dt
            } else {
                0.0
            }
            lastBytes = p.downloadedBytes
            lastAt = now
            val speedText = if (speed >= 51_200.0) " · ${fmtMb(speed)}/s" else ""
            if (p.totalBytes > 0L) {
                updateFraction(
                    phases, index,
                    p.downloadedBytes.toFloat() / p.totalBytes.toFloat(),
                    onProgress,
                    "${fmtMb(p.downloadedBytes.toDouble())} / " +
                        "${fmtMb(p.totalBytes.toDouble())} MB$speedText"
                )
            } else {
                updateFraction(
                    phases, index, 0f, onProgress,
                    "${fmtMb(p.downloadedBytes.toDouble())} MB received$speedText",
                    indeterminate = true
                )
            }
        }
    }

    /** "12.3" — [bytes] rendered as MB with one decimal (US locale). */
    private fun fmtMb(bytes: Double): String =
        String.format(Locale.US, "%.1f", bytes / 1_048_576.0)

    // ── progress helpers ────────────────────────────────────────────────────

    private fun enter(
        phases: List<BaseDesktopInstallPlan.Phase>,
        index: Int,
        onProgress: (Progress) -> Unit,
        detail: String,
        indeterminate: Boolean = false
    ) {
        val p = phases[index]
        phaseFraction = 0f
        lastDetail = detail
        lastIndeterminate = indeterminate
        val pct = phasePercent(phases, index, 0f)
        lastOverallPercent = pct
        post(
            onProgress,
            Progress(
                phaseId = p.id,
                phaseLabel = p.label,
                phaseIndex = index,
                phaseCount = phases.size,
                overallPercent = pct,
                detail = detail,
                logLine = "── ${p.label}: $detail",
                indeterminate = indeterminate
            )
        )
    }

    private fun completePhase(
        phases: List<BaseDesktopInstallPlan.Phase>,
        index: Int,
        onProgress: (Progress) -> Unit,
        detail: String
    ) {
        val p = phases[index]
        phaseFraction = 1f
        lastDetail = detail
        lastIndeterminate = false
        val pct = phasePercent(phases, index, 1f)
        lastOverallPercent = pct
        post(
            onProgress,
            Progress(
                phaseId = p.id,
                phaseLabel = p.label,
                phaseIndex = index,
                phaseCount = phases.size,
                overallPercent = pct,
                detail = detail,
                logLine = "✓ $detail"
            )
        )
    }

    private fun updateFraction(
        phases: List<BaseDesktopInstallPlan.Phase>,
        index: Int,
        frac: Float,
        onProgress: (Progress) -> Unit,
        detail: String,
        indeterminate: Boolean = false
    ) {
        val p = phases[index]
        val clamped = frac.coerceIn(0f, 1f)
        if (clamped > phaseFraction) phaseFraction = clamped
        lastDetail = detail
        lastIndeterminate = indeterminate
        val pct = phasePercent(phases, index, phaseFraction)
        lastOverallPercent = pct
        post(
            onProgress,
            Progress(
                phaseId = p.id,
                phaseLabel = p.label,
                phaseIndex = index,
                phaseCount = phases.size,
                overallPercent = pct,
                detail = detail,
                indeterminate = indeterminate
            )
        )
    }

    private fun log(
        phases: List<BaseDesktopInstallPlan.Phase>,
        index: Int,
        onProgress: (Progress) -> Unit,
        line: String
    ) {
        val p = phases.getOrNull(index) ?: return
        // Console markers drive real in-phase progress so the bar can never sit
        // at the phase-entry percent while the guest is already done.
        val step = STEP_MARKER_RE.find(line)
        val strongDone = GUEST_DONE_RE.containsMatchIn(line)
        val softDone = GUEST_SETUP_DONE_RE.containsMatchIn(line)
        if (strongDone || softDone) {
            // Strong marker = the host install script is done. The weaker
            // "setup complete" family marker only ends the guest setup band —
            // desktop stages may still follow, so don't claim finishing yet.
            if (strongDone) {
                phaseFraction = 1f
                lastDetail = "Finishing up…"
                guestReportedDone = true
                armTeardownWatchdog()
            } else {
                if (phaseFraction < 0.9f) phaseFraction = 0.9f
                lastDetail = "Setting up packages…"
            }
            lastIndeterminate = false
        } else if (step != null) {
            val n = step.groupValues[1].toIntOrNull()
            val m = step.groupValues[2].toIntOrNull()
            if (n != null && m != null && m > 0 && n in 0..m) {
                val base = if (p.id == "ROOTFS") EXTRACT_BAND else 0f
                val frac = base + (1f - base) * (n.toFloat() / m.toFloat())
                if (frac > phaseFraction) phaseFraction = frac
                lastIndeterminate = false
                lastDetail = "Setting up packages… (step $n/$m)"
            }
        } else if (EXTRACT_DONE_RE.containsMatchIn(line)) {
            // Unpacking is over and guest setup started, but without step
            // markers there is still no measurable fraction — stay indeterminate.
            if (phaseFraction < EXTRACT_BAND) phaseFraction = EXTRACT_BAND
            lastDetail = "Setting up packages…"
        }
        val pct = phasePercent(phases, index, phaseFraction)
        lastOverallPercent = pct
        post(
            onProgress,
            Progress(
                phaseId = p.id,
                phaseLabel = p.label,
                phaseIndex = index,
                phaseCount = phases.size,
                overallPercent = pct,
                detail = lastDetail.ifBlank { p.label },
                logLine = line,
                indeterminate = lastIndeterminate
            )
        )
    }

    /**
     * proot-distro can print success and then hang on teardown. Once the guest
     * announced completion, give the process a short grace period and force it
     * down so the runner can finish and the UI flips to DONE.
     */
    private fun armTeardownWatchdog() {
        if (watchdogArmed) return
        val proc = activeProcess.get() ?: return
        watchdogArmed = true
        val gen = generation.get()
        main.postDelayed({
            watchdogArmed = false
            if (generation.get() == gen && guestReportedDone && proc.isAlive) {
                Log.w(TAG, "install reported success but process is still alive — forcing teardown")
                proc.destroyForcibly()
            }
        }, TEARDOWN_GRACE_MS)
    }

    /**
     * Monotonic percent: never below what has already been shown, so a
     * guest-announced completion can never be followed by a lower value.
     */
    private fun phasePercent(
        phases: List<BaseDesktopInstallPlan.Phase>,
        index: Int,
        fractionInPhase: Float
    ): Int {
        val computed = weightedPercent(phases, index, fractionInPhase)
        return maxOf(computed, lastOverallPercent).coerceIn(0, 100)
    }

    private fun postSuccess(
        onProgress: (Progress) -> Unit,
        phases: List<BaseDesktopInstallPlan.Phase>
    ) {
        val last = phases.last()
        lastOverallPercent = 100
        post(
            onProgress,
            Progress(
                phaseId = last.id,
                phaseLabel = "Complete",
                phaseIndex = phases.size - 1,
                phaseCount = phases.size,
                overallPercent = 100,
                detail = "Environment ready",
                finished = true
            )
        )
    }

    private fun postFail(
        onProgress: (Progress) -> Unit,
        phases: List<BaseDesktopInstallPlan.Phase>,
        message: String,
        publishShared: Boolean = true
    ) {
        val p = phases.firstOrNull()
        post(
            onProgress,
            Progress(
                phaseId = p?.id ?: "ERR",
                phaseLabel = "Failed",
                phaseIndex = 0,
                phaseCount = phases.size.coerceAtLeast(1),
                overallPercent = 0,
                detail = message,
                failed = true,
                finished = true,
                errorMessage = message,
                logLine = "ERROR: $message"
            ),
            publishShared = publishShared
        )
    }

    private fun postCancelled(
        onProgress: (Progress) -> Unit,
        phases: List<BaseDesktopInstallPlan.Phase>
    ) {
        // Stale-job cancellation must not clobber the shared task-bar state: the
        // canceller (cancel() or a newer start()) owns that write.
        postFail(onProgress, phases, "Install cancelled", publishShared = false)
    }

    private fun weightedPercent(
        phases: List<BaseDesktopInstallPlan.Phase>,
        currentIndex: Int,
        fractionInPhase: Float
    ): Int {
        val total = phases.sumOf { it.weight }.coerceAtLeast(1)
        var done = 0
        for (i in phases.indices) {
            if (i < currentIndex) done += phases[i].weight
            else if (i == currentIndex) done += (phases[i].weight * fractionInPhase).toInt()
        }
        return ((done * 100) / total).coerceIn(0, 100)
    }

    private fun post(
        onProgress: (Progress) -> Unit,
        progress: Progress,
        publishShared: Boolean = true
    ) {
        main.post {
            onProgress(progress)
            if (publishShared) publishLive(progress)
            updateInstallNotification(progress)
        }
    }

    /**
     * Mirror the freshest progress into the process-wide sources:
     * [InstallationQueueManager] (task bar) and [_live] (screen re-attach).
     * Called on the main thread from [post].
     */
    private fun publishLive(progress: Progress) {
        val distroId = sessionDistroId ?: return
        // A superseded instance (newer start() elsewhere) must never clobber the
        // shared task-bar/live state with its stale ticks. Its *terminal* tick
        // is still allowed through when it is the task the bar is showing, so
        // a completed/aborted runner cannot leave a 100% bar behind forever.
        if (activeInstance !== this) {
            val shared = InstallationQueueManager.installState.value
            val sameTask = shared.currentDistroId == distroId &&
                shared.currentTaskName == sessionTaskName
            if (!sameTask || !(progress.finished || progress.failed)) return
        }
        progress.logLine?.let { appendSessionLog(it) }
        _live.value = LiveInstall(
            distroId = distroId,
            progress = progress,
            logText = sessionLog,
            atMillis = System.currentTimeMillis()
        )
        InstallationQueueManager.updateActiveInstall(
            taskName = sessionTaskName.ifBlank { distroId },
            phaseLabel = progress.phaseLabel,
            // -1 = unknown/indeterminate (InstallTaskBar renders the shimmer).
            overallPercent = if (progress.indeterminate) -1 else progress.overallPercent,
            detail = progress.detail,
            distroId = distroId,
            failed = progress.failed,
            done = progress.finished && !progress.failed
        )
    }

    private fun appendSessionLog(line: String) {
        sessionLog = (sessionLog + line + "\n").takeLast(12_000)
    }

    private fun updateInstallNotification(progress: Progress) {
        val label = when {
            progress.failed -> "Install failed"
            progress.finished -> "Install complete"
            progress.phaseLabel.isNotBlank() -> progress.phaseLabel
            else -> "Installing…"
        }
        val detail = progress.detail.ifBlank { "Base desktop setup" }
        val percent = progress.overallPercent
        // Avoid spamming NotificationManager on every log line
        if (!progress.finished &&
            !progress.failed &&
            percent == lastNotifPercent &&
            label == lastNotifLabel
        ) {
            return
        }
        lastNotifPercent = percent
        lastNotifLabel = label
        try {
            BaseInstallService.update(
                appCtx,
                title = "Altaris — $label",
                text = detail,
                percent = percent
            )
        } catch (e: Exception) {
            Log.w(TAG, "FGS update failed: ${e.message}")
        }
    }

    private fun isProotXfceInstalled(ctx: Context, prootName: String = "debian"): Boolean {
        val root = File(ctx.filesDir, "usr/var/lib/proot-distro/containers/$prootName/rootfs")
        return File(root, "usr/bin/startxfce4").exists() ||
            File(root, "usr/sbin/startxfce4").exists()
    }

    private fun isProotKdeInstalled(ctx: Context, prootName: String = "debian"): Boolean {
        val root = File(ctx.filesDir, "usr/var/lib/proot-distro/containers/$prootName/rootfs")
        return File(root, "usr/bin/startplasma-x11").exists() ||
            File(root, "usr/bin/plasmashell").exists()
    }

    /** Mirror of ChrootDetection.isXfceInstalled for KDE Plasma — background thread only. */
    private fun isChrootKdeInstalled(
        chrootPath: String = com.altaris.linux.core.root.ChrootPaths.CHROOT_PATH
    ): Boolean {
        val startPlasma = "$chrootPath/usr/bin/startplasma-x11"
        val plasmaShell = "$chrootPath/usr/bin/plasmashell"
        if (File(startPlasma).exists() || File(plasmaShell).exists()) return true
        if (!RootShell.isRootAvailable()) return false
        // Avoid blocking the main thread
        if (Looper.myLooper() == Looper.getMainLooper()) return false
        return try {
            RootShell.capture(
                "if [ -e '$startPlasma' ] || [ -e '$plasmaShell' ]; " +
                    "then echo YES; else echo NO; fi",
                timeoutMs = 8_000L
            ).contains("YES")
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val TAG = "OnboardingInstall"

        /** Guest step markers: `(17/17) Installing…` from family scripts. */
        private val STEP_MARKER_RE =
            Regex("\\((\\d{1,4})\\s*/\\s*(\\d{1,4})\\)")

        /** Absolute end-of-install markers (host install script). */
        private val GUEST_DONE_RE =
            Regex("completed successfully|Configuration Complete", RegexOption.IGNORE_CASE)

        /** Family setup finished (host-side finish still pending). */
        private val GUEST_SETUP_DONE_RE =
            Regex("setup complete", RegexOption.IGNORE_CASE)

        /** Unpack finished / guest package setup starting. */
        private val EXTRACT_DONE_RE = Regex(
            "Running setup inside proot|Installing packages|Setting up packages",
            RegexOption.IGNORE_CASE
        )

        /** Fraction of the ROOTFS phase spent unpacking (rest = package setup). */
        private const val EXTRACT_BAND = 0.25f

        /** Grace after the guest announces success before a hung teardown is killed. */
        private const val TEARDOWN_GRACE_MS = 4_000L

        /**
         * Freshest snapshot of the active/last install. Lets the task bar route
         * back to a live panel and lets a re-created [InstallConfigScreen] adopt
         * the running session it no longer owns (the runner instance may be gone).
         */
        data class LiveInstall(
            val distroId: String,
            val progress: Progress,
            val logText: String,
            val atMillis: Long
        )

        private val _live = MutableStateFlow<LiveInstall?>(null)

        /** Observable live install snapshot (null when idle / cancelled). */
        val live: StateFlow<LiveInstall?> = _live.asStateFlow()

        /** The runner instance currently owning a session (single-flight). */
        @Volatile private var activeInstance: OnboardingInstallRunner? = null

        /** Drop a live snapshot (e.g. Done acknowledged for [distroId]). */
        fun clearLive(distroId: String? = null) {
            val current = _live.value
            if (distroId == null || current?.distroId == distroId) {
                _live.value = null
            }
        }

        /**
         * Cancel the process-wide active session regardless of which screen
         * instance owns it — an adopted (re-attached) progress panel must cancel
         * the real runner, not its own fresh, idle instance.
         */
        fun cancelActive() {
            activeInstance?.cancel()
        }
    }
}
