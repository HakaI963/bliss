package com.ivarna.fluxlinux

import android.app.Application
import android.content.Context
import android.util.Log
import com.ivarna.fluxlinux.core.clone.CloneEngine
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.app.configuration.ClientConfiguration
import com.ivarna.fluxlinux.core.system.CrashLog
import com.ivarna.fluxlinux.core.terminal.ShellIntegration
import com.ivarna.fluxlinux.core.terminal.TermuxHostPaths

class FluxApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // In-app virtual app engine. Runs cloned APKs inside Bliss itself; this is
        // not an Android user/profile. Must be attached before any application code.
        runCatching {
            BlackBoxCore.get().doAttachBaseContext(base, object : ClientConfiguration() {
                override fun getHostPackageName(): String = base.packageName
                override fun isEnableLauncherActivity(): Boolean = false
            })
        }.onFailure { Log.e("FluxApp", "BlackBox attach failed", it) }
    }

    override fun onCreate() {
        super.onCreate()
        // Capture uncaught exceptions to filesDir/crashlog BEFORE anything else can
        // throw, so MIUI's "keeps stopping" reports are backed by a readable local
        // trace (Settings -> Crash logs). Delegates to the previous handler, so the
        // system crash dialog is unchanged.
        CrashLog.install(this)
        // nativeLibraryDir moves on every app update; fix host env before ANY host script runs.
        runCatching { TermuxHostPaths.refreshHostEnvIfStale(filesDir, this) }
            .onFailure { Log.w("FluxApp", "host env refresh failed", it) }
        // The engine is initialised on demand in the main process (opening Apps or
        // installing/launching a clone), so Bliss startup does not pay for it.
        // Proxy/daemon processes must initialise immediately.
        val proc = runCatching { Application.getProcessName() }.getOrDefault("")
        if (proc != packageName) {
            runCatching { BlackBoxCore.get().doCreate() }
                .onFailure { Log.e("FluxApp", "BlackBox create failed", it) }
        }
        CloneEngine.init(this)
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
}
