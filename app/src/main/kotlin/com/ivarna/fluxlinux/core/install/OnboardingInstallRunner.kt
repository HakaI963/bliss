package com.ivarna.fluxlinux.core.install

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ivarna.fluxlinux.core.gpu.GpuDriverInstaller
import com.ivarna.fluxlinux.core.root.RootShell
import com.ivarna.fluxlinux.core.service.BaseInstallService
import com.ivarna.fluxlinux.core.terminal.GpuAccelDetector
import com.ivarna.fluxlinux.core.terminal.HostCommandBuilder
import com.ivarna.fluxlinux.core.terminal.ShellCommandRunner
import com.ivarna.fluxlinux.core.terminal.TerminalLauncher
import com.ivarna.fluxlinux.core.terminal.TermuxHostPaths
import com.ivarna.fluxlinux.core.utils.InstallationQueueManager
import com.ivarna.fluxlinux.core.utils.StateManager
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Runs the simplified onboarding / base install with phase progress + live log.
 * Proot: prepareHost → bliss_install (family) → customization via proot-distro.
 * Chroot: root check → prepareHost → setup_*_chroot → family → customization in chroot.
 *
 * Distro identity from [DistroInstallProfile] (Debian + Alpine).
 *
 * Desktop selection ([DesktopChoice] set) is threaded to guest scripts as
 * `FLUX_DESKTOPS="xfce kde"` (canonical order; `none` when empty) plus the
 * legacy single-value gates `FLUX_DESKTOP` / `FLUX_DESKTOP_ENV` (first
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
        val errorMessage: String? = null
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
                title = "Bliss — Installing",
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

        enter(phases, hostIdx, onProgress, "Extracting bootstrap + deploying scripts…")
        if (abortIfCancelled(gen, phases, onProgress)) return
        var lastHostPhase = ""
        val hostOk = TerminalLauncher.prepareHostBlocking(appCtx, forceHostSetup = false) { done, total, phase ->
            if (isStale(gen)) return@prepareHostBlocking
            val frac = if (total > 0) done.toFloat() / total else 0f
            updateFraction(phases, hostIdx, frac, onProgress, phase)
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
        enter(phases, dlIdx, onProgress, "Downloading ${profile.displayName} rootfs…")
        if (abortIfCancelled(gen, phases, onProgress)) return
        val destDir = TermuxHostPaths.homeDir(appCtx)
        val dlOk = RootfsDownloader.ensurePresent(
            destDir, profile, RootfsDownloader.defaultClient,
            isCancelled = { isStale(gen) }
        ) { p ->
            if (isStale(gen)) return@ensurePresent
            val frac = if (p.totalBytes > 0) p.downloadedBytes.toFloat() / p.totalBytes else 0f
            updateFraction(
                phases, dlIdx, frac, onProgress,
                "Downloaded ${p.downloadedBytes / 1_048_576} / " +
                    "${p.totalBytes.coerceAtLeast(0) / 1_048_576} MiB"
            )
        }
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

        enter(phases, rootfsIdx, onProgress, "Installing ${profile.displayName} via proot-distro…")
        if (abortIfCancelled(gen, phases, onProgress)) return
        val bash = TermuxHostPaths.libBash(appCtx).absolutePath
        val installScript = TermuxHostPaths.hostScript(appCtx, "bliss_install.sh").absolutePath
        val setupB64 = BaseDesktopInstallPlan.familySetupB64(
            appCtx, theme, distroId, desktops
        )
        val desktopsEnv = DesktopInstallSelection.envValue(desktops)
        val firstEnv = DesktopInstallSelection.firstEnvValue(desktops)
        val env = HostCommandBuilder.envMap(appCtx, forceHostSetup = false, includeTerm = false).apply {
            put("PYTHONUNBUFFERED", "1")
            // FLUX_DESKTOPS contract — family scripts gate desktop packages on
            // the set; FLUX_DESKTOP/_ENV stay the legacy single-value gates.
            put("FLUX_DESKTOPS", desktopsEnv)
            put("FLUX_DESKTOP", firstEnv)
            put("FLUX_DESKTOP_ENV", firstEnv)
            put("FLUX_ROOTFS_PATH", "${TermuxHostPaths.HOME}/${profile.rootfsFileName}")
            put("FLUX_ROOTFS_NAME", profile.rootfsFileName)
            put("FLUX_ROOTFS_SHA256", profile.rootfsSha256)
            put("FLUX_ROOTFS_URL", profile.rootfsUrl)
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
        if (exitInstall != 0) {
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
                envPrefix = "FLUX_DESKTOPS=${DesktopChoice.KDE.envValue} " +
                    "FLUX_DESKTOP=${DesktopChoice.KDE.envValue}"
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
            }
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
                envPrefix = "FLUX_THEME=$theme " +
                    "FLUX_SKIP_THEME_ICONS=$skipAssets " +
                    "FLUX_SKIP_OMZ=$skipOmz " +
                    "FLUX_SKIP_POKEMON=0 " +
                    "FLUX_ASSET_DIR=/tmp/bliss_xfce_assets"
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
                    envPrefix = "FLUX_THEME=$theme " +
                        "FLUX_DESKTOPS=${DesktopChoice.KDE.envValue} " +
                        "FLUX_DESKTOP=${DesktopChoice.KDE.envValue}"
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
                "Root not available. Grant superuser to Bliss, then retry."
            )
            return
        }
        completePhase(phases, r0Idx, onProgress, "Root OK")
        RootShell.ensureBusyBoxResolver(appCtx)
        val resolvedBb = RootShell.resolveBusyBox()

        enter(phases, hostIdx, onProgress, "Extracting bootstrap + deploying scripts…")
        if (abortIfCancelled(gen, phases, onProgress)) return
        var lastHostPhase = ""
        val hostOk = TerminalLauncher.prepareHostBlocking(appCtx, forceHostSetup = false) { done, total, phase ->
            if (isStale(gen)) return@prepareHostBlocking
            val frac = if (total > 0) done.toFloat() / total else 0f
            updateFraction(phases, hostIdx, frac, onProgress, phase)
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
        enter(phases, dlIdx, onProgress, "Downloading ${profile.displayName} rootfs…")
        if (abortIfCancelled(gen, phases, onProgress)) return
        val destDir = TermuxHostPaths.homeDir(appCtx)
        val dlOk = RootfsDownloader.ensurePresent(
            destDir, profile, RootfsDownloader.defaultClient,
            isCancelled = { isStale(gen) }
        ) { p ->
            if (isStale(gen)) return@ensurePresent
            val frac = if (p.totalBytes > 0) p.downloadedBytes.toFloat() / p.totalBytes else 0f
            updateFraction(
                phases, dlIdx, frac, onProgress,
                "Downloaded ${p.downloadedBytes / 1_048_576} / " +
                    "${p.totalBytes.coerceAtLeast(0) / 1_048_576} MiB"
            )
        }
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
            ?: com.ivarna.fluxlinux.core.root.ChrootPaths.CHROOT_PATH
        val setupAsset = profile.chrootSetupAsset
            ?: "scripts/chroot/setup_debian13_chroot.sh"
        val setupName = File(setupAsset).name

        enter(phases, rootfsIdx, onProgress, "Extracting chroot rootfs (may take several minutes)…")
        if (abortIfCancelled(gen, phases, onProgress)) return
        val staged = RootShell.stageAsset(appCtx, setupAsset)
            ?: TermuxHostPaths.hostScript(appCtx, setupName).absolutePath
        val envHome = TermuxHostPaths.HOME
        // Do not wrap with $PREFIX/bin/stdbuf — host W^X denies exec from app data.
        val label = profile.distroId.removeSuffix("_chroot")
        val bbExport = if (!resolvedBb.isNullOrEmpty()) "export FLUX_BB='$resolvedBb'; " else ""
        val rootCmd =
            bbExport +
            "export FLUX_ROOTFS_PATH='$envHome/${profile.rootfsFileName}'; " +
                "export FLUX_ROOTFS_NAME='${profile.rootfsFileName}'; " +
                "export FLUX_ROOTFS_SHA256='${profile.rootfsSha256}'; " +
                "export FLUX_ROOTFS_URL='${profile.rootfsUrl}'; " +
                "export FLUX_CHROOT='$chrootPath'; " +
                "export FLUX_DISTRO_LABEL='$label'; " +
                "export FLUX_DESKTOPS='${DesktopInstallSelection.envValue(desktops)}'; " +
                "export FLUX_DESKTOP='${DesktopInstallSelection.firstEnvValue(desktops)}'; " +
                "export FLUX_DESKTOP_ENV='${DesktopInstallSelection.firstEnvValue(desktops)}'; " +
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
            }
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
            }
        )
        if (abortIfCancelled(gen, phases, onProgress)) return
        var xfceCustomOk = false
        var kdeCustomOk = false
        if (xfceSelected) {
            // Stage theme/icon/cursor/wallpaper into the chroot guest /tmp (root copy).
            // The customization script extracts from /tmp/bliss_xfce_assets with guest
            // tar; icons have no download fallback, so this staging is mandatory.
            log(phases, customIdx, onProgress, "Staging XFCE theme/icons into chroot…")
            try {
                com.ivarna.fluxlinux.core.install.ProotXfceAssetInstaller.installToChroot(
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
     * Run the guest hw-accel installer with host-detected FLUX_GPU.
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
        (driverEnv["FLUX_TURNIP_MSG"] ?: driverEnv["FLUX_PANVK_MSG"])?.let { log(phases, phaseIndex, onProgress, it) }
        val ok = try {
            if (method == "chroot") {
                val path = chrootPath
                    ?: com.ivarna.fluxlinux.core.root.ChrootPaths.CHROOT_PATH
                val payload = BaseDesktopInstallPlan.hwAccelPayload(
                    appCtx, profile.distroId, gpu.mode, gpu.vendorHint, driverEnv
                )
                runChrootGuestBlocking(payload, user = "root", phases, phaseIndex, onProgress, path) == 0
            } else {
                stageFluxGpuCommon()
                runProotGuestScript(
                    phases, phaseIndex, onProgress, gen,
                    prootName = profile.prootName,
                    scriptAssetPath = script,
                    envPrefix = "FLUX_GPU=${gpu.mode} FLUX_GPU_VENDOR=${gpu.vendorHint}" +
                        driverEnv.entries.joinToString("") {
                            " ${it.key}='${it.value.replace("'", "")}'"
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

    private fun stageFluxGpuCommon() {
        val tmp = File(TermuxHostPaths.TMPDIR)
        if (!tmp.exists()) tmp.mkdirs()
        val dest = File(tmp, "bliss_gpu_common.sh")
        appCtx.assets.open("scripts/common/setup/bliss_gpu_common.sh").use { input ->
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
        chrootPath: String = com.ivarna.fluxlinux.core.root.ChrootPaths.CHROOT_PATH
    ): Int {
        // A single `su -c` argument is capped (~128 KB) by the kernel, and the
        // component scripts (with the embedded sticker art) are far bigger, so
        // the body is staged into the chroot in chunks first and executed by
        // path. `guest_b64` prefers /bin/bash when present, else /bin/sh.
        val dest = "/.bliss-guest-step.sh"
        val body = "export PYTHONUNBUFFERED=1\n$scriptBody"
        val staged = com.ivarna.fluxlinux.core.root.ChrootStager.writeFiles(
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

    // ── progress helpers ────────────────────────────────────────────────────

    private fun enter(
        phases: List<BaseDesktopInstallPlan.Phase>,
        index: Int,
        onProgress: (Progress) -> Unit,
        detail: String
    ) {
        val p = phases[index]
        val pct = weightedPercent(phases, index, 0f)
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
                logLine = "── ${p.label}: $detail"
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
        val pct = weightedPercent(phases, index, 1f)
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
        detail: String
    ) {
        val p = phases[index]
        val pct = weightedPercent(phases, index, frac.coerceIn(0f, 1f))
        lastOverallPercent = pct
        post(
            onProgress,
            Progress(
                phaseId = p.id,
                phaseLabel = p.label,
                phaseIndex = index,
                phaseCount = phases.size,
                overallPercent = pct,
                detail = detail
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
        // Keep prior overall percent — pure log lines must not snap the bar mid-phase.
        post(
            onProgress,
            Progress(
                phaseId = p.id,
                phaseLabel = p.label,
                phaseIndex = index,
                phaseCount = phases.size,
                overallPercent = lastOverallPercent.coerceIn(0, 100),
                detail = p.label,
                logLine = line
            )
        )
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
        // shared task-bar/live state with its stale ticks.
        if (activeInstance !== this) return
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
            overallPercent = progress.overallPercent,
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
                title = "Bliss — $label",
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
        chrootPath: String = com.ivarna.fluxlinux.core.root.ChrootPaths.CHROOT_PATH
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
