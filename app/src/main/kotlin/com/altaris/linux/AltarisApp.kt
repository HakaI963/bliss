package com.altaris.linux

import android.app.Application
import android.util.Log
import com.altaris.linux.core.clone.HeadlessCloneEngine
import com.altaris.linux.core.system.CrashLog
import com.altaris.linux.core.terminal.ShellIntegration
import com.altaris.linux.core.terminal.TermuxHostPaths

class AltarisApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Capture uncaught exceptions to filesDir/crashlog BEFORE anything else can
        // throw, so MIUI's "keeps stopping" reports are backed by a readable local
        // trace (Settings -> Crash logs). Delegates to the previous handler, so the
        // system crash dialog is unchanged.
        CrashLog.install(this)
        // The application id changed; move the old package's data into place
        // (root, off the main thread) so containers and prefs survive.
        com.altaris.linux.core.utils.LegacyDataMigration.start(this)
        // The product was renamed; carry the old preference files over so an
        // upgrade does not look like a fresh install.
        com.altaris.linux.core.utils.PrefsMigration.migrate(this)
        // Hydrate the home artwork flows before the first screen composes, so
        // a custom banner survives a cold start even if Settings is the first
        // screen opened.
        runCatching { com.altaris.linux.core.utils.StateManager.getHomeArt(this) }
        runCatching {
            com.altaris.linux.core.utils.StateManager.getCustomHomeArt(this)
        }
        // Named distro instances (same distro installed more than once under
        // separate container names). In-memory registry for profile resolution.
        runCatching { com.altaris.linux.core.install.DistroInstances.init(this) }
            .onFailure { Log.w("AltarisApp", "instance registry load failed", it) }
        if (!isMainProcess()) {
            // Shizuku re-creates this Application inside its :shell user-service
            // process. Host bootstrap/terminal setup must never run there.
            return
        }
        // nativeLibraryDir moves on every app update; fix host env before ANY host script runs.
        runCatching { TermuxHostPaths.refreshHostEnvIfStale(filesDir, this) }
            .onFailure { Log.w("AltarisApp", "host env refresh failed", it) }
        // Some bootstrap snapshots ship libcurl without its HTTP/3 runtime; the
        // CLI sources curl from this prefix inside every guest, so repair it once.
        runCatching { com.altaris.linux.core.terminal.HostScriptDeployer.overlayHostRuntime(this) }
            .onFailure { Log.w("AltarisApp", "host libcurl repair failed", it) }
        // Remove leftovers from the retired in-app virtual-app engine. The
        // Shizuku clone engine is initialised lazily when the Apps/Tools UI opens.
        HeadlessCloneEngine.onAppCreate(this)
        HeadlessCloneEngine.attach(this)
        // Start the shell engine once; it reconnects on its own when Shizuku
        // comes back, so the Apps screen never needs a manual start.
        runCatching { com.altaris.linux.core.clone.CloneEngineAutoStart.start(this) }
            .onFailure { Log.w("AltarisApp", "clone engine auto-start failed", it) }
        // Localhost CLI/MCP control plane. It is process-scoped: no permanent
        // foreground notification, while the service is best-effort keep-alive.
        com.altaris.linux.core.control.CloneControlService.clearLegacyNotification(this)
        runCatching { com.altaris.linux.core.control.CloneControlService.start(this) }
            .onFailure { Log.w("AltarisApp", "control service start failed", it) }
        runCatching { com.altaris.linux.core.control.ControlServer.start(this) }
            .onFailure { Log.w("AltarisApp", "control server start failed", it) }
        // Vendored ble.sh: bash syntax coloring + inline history suggestions
        // (host shell + every installed guest distro). Version-stamped and
        // idempotent, so this is a stat-only pass after the first run; keep it
        // off the main thread (file copies + one optional su pass).
        Thread {
            // The clone viewer drives apps through the accessibility service.
            // Enable it without asking: Shizuku first, then root, and only
            // then a one-time hint (autoEnable keeps that off the main thread).
            runCatching { com.altaris.linux.core.a11y.A11yAutoEnable.autoEnable(this) }
            runCatching { com.altaris.linux.core.terminal.DistroFsCache.warm(this) }
            // Rebrand legacy flux guests once per launch (also done lazily
            // before a terminal is opened, in ProotCommandBuilder).
            runCatching {
                val containers = java.io.File(
                    filesDir,
                    "usr/var/lib/proot-distro/containers",
                )
                containers.listFiles().orEmpty().forEach { dir ->
                    val rootfs = java.io.File(dir, "rootfs")
                    com.altaris.linux.core.terminal.RootfsKeyRepair
                        .ensureRepaired(this, rootfs)
                    com.altaris.linux.core.terminal.GuestUserMigration
                        .ensureAltaris(rootfs)
                }
            }
            runCatching { ShellIntegration.ensureInstalled(this) }
                .onFailure { Log.w("AltarisApp", "shell integration install failed", it) }
            runCatching { com.altaris.linux.core.control.AltarisCliInstaller.installAll(this) }
                .onFailure { Log.w("AltarisApp", "altaris CLI install failed", it) }
        }.apply {
            isDaemon = true
            name = "altaris-shell-integration"
        }.start()
    }

    private fun isMainProcess(): Boolean {
        // UID is the reliable discriminator: Shizuku's user service runs as
        // shell/root, while the real app process runs as the package UID.
        if (android.os.Process.myUid() != applicationInfo.uid) return false
        val process = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                Application.getProcessName()
            } else {
                java.io.File("/proc/self/cmdline").readText()
                    .trim('\u0000', ' ', '\n')
            }
        }.getOrDefault("")
        return process.isEmpty() || process == packageName
    }
}
