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
        // The product was renamed; carry the old preference files over so an
        // upgrade does not look like a fresh install.
        com.altaris.linux.core.utils.PrefsMigration.migrate(this)
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
