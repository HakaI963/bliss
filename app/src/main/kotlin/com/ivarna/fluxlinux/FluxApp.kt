package com.ivarna.fluxlinux

import android.app.Application
import android.util.Log
import com.ivarna.fluxlinux.core.clone.HeadlessCloneEngine
import com.ivarna.fluxlinux.core.system.CrashLog
import com.ivarna.fluxlinux.core.terminal.ShellIntegration
import com.ivarna.fluxlinux.core.terminal.TermuxHostPaths

class FluxApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Capture uncaught exceptions to filesDir/crashlog BEFORE anything else can
        // throw, so MIUI's "keeps stopping" reports are backed by a readable local
        // trace (Settings -> Crash logs). Delegates to the previous handler, so the
        // system crash dialog is unchanged.
        CrashLog.install(this)
        if (!isMainProcess()) {
            // Shizuku re-creates this Application inside its :shell user-service
            // process. Host bootstrap/terminal setup must never run there.
            return
        }
        // nativeLibraryDir moves on every app update; fix host env before ANY host script runs.
        runCatching { TermuxHostPaths.refreshHostEnvIfStale(filesDir, this) }
            .onFailure { Log.w("FluxApp", "host env refresh failed", it) }
        // Remove leftovers from the retired in-app virtual-app engine. The
        // Shizuku clone engine is initialised lazily when the Apps/Tools UI opens.
        HeadlessCloneEngine.onAppCreate(this)
        // Vendored ble.sh: bash syntax coloring + inline history suggestions
        // (host shell + every installed guest distro). Version-stamped and
        // idempotent, so this is a stat-only pass after the first run; keep it
        // off the main thread (file copies + one optional su pass).
        Thread {
            runCatching { ShellIntegration.ensureInstalled(this) }
                .onFailure { Log.w("FluxApp", "shell integration install failed", it) }
        }.apply {
            isDaemon = true
            name = "bliss-shell-integration"
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
