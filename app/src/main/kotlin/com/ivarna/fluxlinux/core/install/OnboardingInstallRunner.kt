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
import com.ivarna.fluxlinux.core.utils.StateManager
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs the simplified onboarding / base install with phase progress + live log.
 * Proot: prepareHost → flux_install (family) → customization via proot-distro.
 * Chroot: root check → prepareHost → setup_*_chroot → family → customization in chroot.
 *
 * Distro identity from [DistroInstallProfile] (Debian + Alpine).
 *
 * Desktop choice ([DesktopChoice]) is threaded to guest scripts as
 * `FLUX_DESKTOP=xfce|kde|none` (exported in the base/family + chroot rootfs env)
 * and drives which desktop components run: XFCE keeps today's family +
 * customization path, KDE runs the distro's `kde_plasma` + `kde_customization`
 * components (data-driven, Debian family only), NONE skips desktop packages.
 *
 * Cancel: generation token + destroy active process; never mutates StateManager after cancel.
 * Overlapping [start] cancels the previous run first.
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

    fun cancel() {
        cancelled.set(true)
        activeProcess.getAndSet(null)?.destroyForcibly()
        // Drop keep-alive when user aborts (also safe if FGS was never started)
        BaseInstallService.stop(appCtx)
    }

    fun isBusy(): Boolean = busy.get()

    /**
     * Start (or restart) install. If a previous job is still running, it is cancelled
     * and the new job is queued on the single-thread executor.
     *
     * Starts [BaseInstallService] FGS immediately so the system does not kill the
     * process during long rootfs / package work.
     *
     * @param desktop requested base desktop; KDE silently falls back to XFCE when
     *   the distro's component data has no `kde_plasma` (see [DesktopInstallSelection]).
     * @param extras optional plan extras; empty = today's defaults (hw-accel on).
     */
    fun start(
        distroId: String,
        theme: String = "dark",
        desktop: DesktopChoice = DesktopChoice.XFCE,
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
        val desktopChoice = DesktopInstallSelection.sanitize(distroId, desktop)
        val phases = BaseDesktopInstallPlan.phasesFor(
            method, profile.displayName, desktopChoice.envValue
        )
        busy.set(true)
        lastNotifPercent = -1
        lastNotifLabel = ""
        // FGS first — before any long work — so backgrounding mid-install is safe
        try {
            BaseInstallService.start(
                appCtx,
                title = "Folio Linux — Installing",
                text = "${profile.displayName} base desktop",
                percent = 0
            )
        } catch (e: Exception) {
            Log.e(TAG, "BaseInstallService.start failed", e)
        }
        executor.execute {
            try {
                if (isStale(gen)) {
                    postCancelled(onProgress, phases)
                    return@execute
                }
                if (desktopChoice != desktop) {
                    log(phases, 0, onProgress, "KDE Plasma not available for this distro — using XFCE4")
                }
                if (method == "chroot") {
                    runChroot(distroId, theme, desktopChoice, extras, phases, onProgress, gen)
                } else {
                    runProot(distroId, theme, desktopChoice, extras, phases, onProgress, gen)
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
        desktop: DesktopChoice,
        extras: Set<InstallExtra>,
        phases: List<BaseDesktopInstallPlan.Phase>,
        onProgress: (Progress) -> Unit,
        gen: Int
    ) {
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
        val installScript = TermuxHostPaths.hostScript(appCtx, "flux_install.sh").absolutePath
        val setupB64 = BaseDesktopInstallPlan.familySetupB64(
            appCtx, theme, distroId, desktop.envValue
        )
        val env = HostCommandBuilder.envMap(appCtx, forceHostSetup = false, includeTerm = false).apply {
            put("PYTHONUNBUFFERED", "1")
            // FLUX_DESKTOP contract — family scripts gate desktop packages on it.
            put("FLUX_DESKTOP", desktop.envValue)
            put("FLUX_DESKTOP_ENV", desktop.envValue)
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
        when (desktop) {
            DesktopChoice.XFCE -> {
                if (!isProotXfceInstalled(appCtx, profile.prootName)) {
                    postFail(
                        onProgress, phases,
                        "XFCE not found after install (startxfce4 missing). Retry setup."
                    )
                    return
                }
            }
            DesktopChoice.KDE -> {
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
                    envPrefix = "FLUX_DESKTOP=${DesktopChoice.KDE.envValue}"
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
            DesktopChoice.NONE -> {
                log(phases, rootfsIdx, onProgress, "Terminal base install — no desktop packages")
            }
        }
        completePhase(
            phases, rootfsIdx, onProgress,
            when (desktop) {
                DesktopChoice.XFCE -> "${profile.displayName} + XFCE installed"
                DesktopChoice.KDE -> "${profile.displayName} + KDE Plasma installed"
                DesktopChoice.NONE -> "${profile.displayName} rootfs installed (terminal base)"
            }
        )

        enter(
            phases, customIdx, onProgress,
            when (desktop) {
                DesktopChoice.XFCE -> "Themes, wallpapers, fonts…"
                DesktopChoice.KDE -> "KDE theming, wallpapers, Zsh…"
                DesktopChoice.NONE -> "No desktop customization (terminal only)"
            }
        )
        if (abortIfCancelled(gen, phases, onProgress)) return
        var customOk = false
        when (desktop) {
            DesktopChoice.XFCE -> {
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
                customOk = runProotGuestScript(
                    phases, customIdx, onProgress, gen,
                    prootName = profile.prootName,
                    scriptAssetPath = profile.customizationScript,
                    envPrefix = "FLUX_THEME=$theme " +
                        "FLUX_SKIP_THEME_ICONS=$skipAssets " +
                        "FLUX_SKIP_OMZ=$skipOmz " +
                        "FLUX_SKIP_POKEMON=0 " +
                        "FLUX_ASSET_DIR=/tmp/flux_xfce_assets"
                )
                if (abortIfCancelled(gen, phases, onProgress)) return
                if (!customOk) {
                    log(phases, customIdx, onProgress, "Customization failed or partial — you can retry from Distro Settings")
                }
            }
            DesktopChoice.KDE -> {
                // KDE customization downloads its own theme/icons/OMZ (no host staging).
                val kdeCustomScript = DesktopInstallSelection.componentScriptName(
                    distroId, DesktopInstallSelection.COMPONENT_KDE_CUSTOMIZATION
                )
                if (kdeCustomScript == null) {
                    log(phases, customIdx, onProgress, "KDE customization component missing — skipped")
                } else {
                    customOk = runProotGuestScript(
                        phases, customIdx, onProgress, gen,
                        prootName = profile.prootName,
                        scriptAssetPath = kdeCustomScript,
                        envPrefix = "FLUX_THEME=$theme FLUX_DESKTOP=${DesktopChoice.KDE.envValue}"
                    )
                    if (abortIfCancelled(gen, phases, onProgress)) return
                    if (!customOk) {
                        log(
                            phases, customIdx, onProgress,
                            "KDE customization failed or partial — you can retry from Distro Settings"
                        )
                    }
                }
            }
            DesktopChoice.NONE -> {
                log(phases, customIdx, onProgress, "Terminal-only install — desktop customization skipped")
            }
        }

        if (abortIfCancelled(gen, phases, onProgress)) return
        val hwOk = if (hwAccelWanted(desktop, extras)) {
            runHwAccelIfPresent(
                profile, method = "proot", phases, customIdx, onProgress, gen, chrootPath = null
            )
        } else {
            log(
                phases, customIdx, onProgress,
                if (desktop == DesktopChoice.NONE) "Hardware acceleration skipped (terminal only)"
                else "Hardware acceleration skipped (user choice)"
            )
            false
        }

        completePhase(
            phases, customIdx, onProgress,
            when (desktop) {
                DesktopChoice.XFCE -> "Customization done"
                DesktopChoice.KDE -> "KDE customization done"
                DesktopChoice.NONE -> "Terminal base ready"
            }
        )

        if (abortIfCancelled(gen, phases, onProgress)) return
        StateManager.setDistroInstalled(appCtx, distroId, true)
        when (desktop) {
            DesktopChoice.XFCE -> {
                StateManager.setComponentInstalled(
                    appCtx, distroId, DesktopInstallSelection.COMPONENT_XFCE, true
                )
                if (customOk) {
                    StateManager.setComponentInstalled(
                        appCtx, distroId, DesktopInstallSelection.COMPONENT_CUSTOMIZATION, true
                    )
                }
            }
            DesktopChoice.KDE -> {
                StateManager.setComponentInstalled(
                    appCtx, distroId, DesktopInstallSelection.COMPONENT_KDE_PLASMA, true
                )
                if (customOk) {
                    StateManager.setComponentInstalled(
                        appCtx, distroId, DesktopInstallSelection.COMPONENT_KDE_CUSTOMIZATION, true
                    )
                }
            }
            DesktopChoice.NONE -> Unit
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
        desktop: DesktopChoice,
        extras: Set<InstallExtra>,
        phases: List<BaseDesktopInstallPlan.Phase>,
        onProgress: (Progress) -> Unit,
        gen: Int
    ) {
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
                "Root not available. Grant superuser to Folio Linux, then retry."
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
                "export FLUX_DESKTOP='${desktop.envValue}'; " +
                "export FLUX_DESKTOP_ENV='${desktop.envValue}'; " +
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
            when (desktop) {
                DesktopChoice.XFCE -> "Installing XFCE packages…"
                DesktopChoice.KDE -> "Installing KDE Plasma packages…"
                DesktopChoice.NONE -> "Configuring terminal base…"
            }
        )
        if (abortIfCancelled(gen, phases, onProgress)) return
        val familyPayload = BaseDesktopInstallPlan.familySetupPayload(
            appCtx, theme, distroId, desktop.envValue
        )
        val familyExit = runChrootGuestBlocking(
            familyPayload, user = "root", phases, xfceIdx, onProgress, chrootPath
        )
        if (abortIfCancelled(gen, phases, onProgress)) return
        if (familyExit != 0) {
            val setupLabel = when (desktop) {
                DesktopChoice.XFCE -> "XFCE"
                DesktopChoice.KDE -> "KDE base"
                DesktopChoice.NONE -> "Base system"
            }
            postFail(onProgress, phases, "$setupLabel setup failed (exit $familyExit)")
            return
        }
        when (desktop) {
            DesktopChoice.XFCE -> {
                if (!TerminalLauncher.isChrootXfceInstalled(chrootPath)) {
                    postFail(
                        onProgress, phases,
                        "XFCE not found after family setup (startxfce4 missing). Retry."
                    )
                    return
                }
            }
            DesktopChoice.KDE -> {
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
            DesktopChoice.NONE -> {
                log(phases, xfceIdx, onProgress, "Terminal base install — no desktop packages")
            }
        }
        completePhase(
            phases, xfceIdx, onProgress,
            when (desktop) {
                DesktopChoice.XFCE -> "XFCE installed"
                DesktopChoice.KDE -> "KDE Plasma installed"
                DesktopChoice.NONE -> "Base system ready"
            }
        )

        enter(
            phases, customIdx, onProgress,
            when (desktop) {
                DesktopChoice.XFCE -> "Themes, wallpapers, fonts…"
                DesktopChoice.KDE -> "KDE theming, wallpapers, Zsh…"
                DesktopChoice.NONE -> "No desktop customization (terminal only)"
            }
        )
        if (abortIfCancelled(gen, phases, onProgress)) return
        var customOk = false
        when (desktop) {
            DesktopChoice.XFCE -> {
                // Stage theme/icon/cursor/wallpaper into the chroot guest /tmp (root copy).
                // The customization script extracts from /tmp/flux_xfce_assets with guest
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
                val customPayload = BaseDesktopInstallPlan.customizationPayload(appCtx, theme, distroId)
                val customExit = runChrootGuestBlocking(
                    customPayload, user = "root", phases, customIdx, onProgress, chrootPath
                )
                if (abortIfCancelled(gen, phases, onProgress)) return
                if (customExit != 0) {
                    log(phases, customIdx, onProgress, "Customization failed or partial (exit $customExit)")
                }
                customOk = customExit == 0
            }
            DesktopChoice.KDE -> {
                // KDE customization downloads its own theme/icons/OMZ — no host staging.
                val customPayload = BaseDesktopInstallPlan.customizationPayload(
                    appCtx, theme, distroId, desktop.envValue
                )
                val customExit = runChrootGuestBlocking(
                    customPayload, user = "root", phases, customIdx, onProgress, chrootPath
                )
                if (abortIfCancelled(gen, phases, onProgress)) return
                if (customExit != 0) {
                    log(phases, customIdx, onProgress, "KDE customization failed or partial (exit $customExit)")
                }
                customOk = customExit == 0
            }
            DesktopChoice.NONE -> {
                log(phases, customIdx, onProgress, "Terminal-only install — desktop customization skipped")
            }
        }

        if (abortIfCancelled(gen, phases, onProgress)) return
        val hwOk = if (hwAccelWanted(desktop, extras)) {
            runHwAccelIfPresent(
                profile, method = "chroot", phases, customIdx, onProgress, gen, chrootPath = chrootPath
            )
        } else {
            log(
                phases, customIdx, onProgress,
                if (desktop == DesktopChoice.NONE) "Hardware acceleration skipped (terminal only)"
                else "Hardware acceleration skipped (user choice)"
            )
            false
        }

        completePhase(
            phases, customIdx, onProgress,
            when (desktop) {
                DesktopChoice.XFCE -> "Customization done"
                DesktopChoice.KDE -> "KDE customization done"
                DesktopChoice.NONE -> "Terminal base ready"
            }
        )

        if (abortIfCancelled(gen, phases, onProgress)) return
        StateManager.setDistroInstalled(appCtx, distroId, true)
        when (desktop) {
            DesktopChoice.XFCE -> {
                StateManager.setComponentInstalled(
                    appCtx, distroId, DesktopInstallSelection.COMPONENT_XFCE, true
                )
                if (customOk) {
                    StateManager.setComponentInstalled(
                        appCtx, distroId, DesktopInstallSelection.COMPONENT_CUSTOMIZATION, true
                    )
                }
            }
            DesktopChoice.KDE -> {
                StateManager.setComponentInstalled(
                    appCtx, distroId, DesktopInstallSelection.COMPONENT_KDE_PLASMA, true
                )
                if (customOk) {
                    StateManager.setComponentInstalled(
                        appCtx, distroId, DesktopInstallSelection.COMPONENT_KDE_CUSTOMIZATION, true
                    )
                }
            }
            DesktopChoice.NONE -> Unit
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
    private fun hwAccelWanted(desktop: DesktopChoice, extras: Set<InstallExtra>): Boolean =
        desktop != DesktopChoice.NONE && InstallExtra.SKIP_HW_ACCEL !in extras

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
        val dest = File(tmp, "flux_gpu_common.sh")
        appCtx.assets.open("scripts/common/setup/flux_gpu_common.sh").use { input ->
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
        // captureInChroot already base64-wraps once via fluxlinux_chroot b64.
        // Nested echo|base64 is redundant and bloated host su -c strings.
        // guest_b64 prefers /bin/bash when present, else /bin/sh (Alpine minirootfs).
        val body = "export PYTHONUNBUFFERED=1\n$scriptBody"
        val result = RootShell.captureInChroot(
            body,
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
        message: String
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
            )
        )
    }

    private fun postCancelled(
        onProgress: (Progress) -> Unit,
        phases: List<BaseDesktopInstallPlan.Phase>
    ) {
        postFail(onProgress, phases, "Install cancelled")
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

    private fun post(onProgress: (Progress) -> Unit, progress: Progress) {
        main.post {
            onProgress(progress)
            updateInstallNotification(progress)
        }
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
                title = "Folio Linux — $label",
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
    }
}
