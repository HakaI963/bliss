package com.ivarna.fluxlinux

import android.app.Application
import android.content.Context
import android.util.Log
import com.ivarna.fluxlinux.core.clone.CloneEngine
import com.ivarna.fluxlinux.core.system.CrashLog
import com.ivarna.fluxlinux.core.terminal.ShellIntegration
import com.ivarna.fluxlinux.core.terminal.TermuxHostPaths
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.app.configuration.ClientConfiguration

class FluxApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // BlackBox clone engine: attach in EVERY process (main + :pN proxies + the
        // :black daemon) so the sandbox hooks are installed before the framework
        // starts running components in it.
        runCatching {
            BlackBoxCore.get().doAttachBaseContext(
                base,
                object : ClientConfiguration() {
                    override fun getHostPackageName(): String = base.packageName

                    // Keep the engine's splash path (most reliable first-launch
                    // flow); the splash itself is routed to the hidden clone display
                    // by the same BlissDisplay launch options, so nothing flashes on
                    // the real screen.
                    override fun isEnableLauncherActivity(): Boolean = true
                },
            )
        }.onFailure { Log.w("FluxApp", "clone engine attach failed", it) }
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
        runCatching { BlackBoxCore.get().doCreate() }
            .onFailure { Log.w("FluxApp", "clone engine create failed", it) }
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
