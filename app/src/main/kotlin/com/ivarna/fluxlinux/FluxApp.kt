package com.ivarna.fluxlinux

import android.app.Application
import android.util.Log
import com.ivarna.fluxlinux.core.system.CrashLog
import com.ivarna.fluxlinux.core.terminal.TermuxHostPaths

class FluxApp : Application() {
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
    }
}
