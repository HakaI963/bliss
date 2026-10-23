package com.altaris.linux.core.clone

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Brings the Shizuku shell engine up once per app process.
 *
 * The engine used to require a manual "Start shell engine" tap after every
 * app launch. It now connects as soon as Shizuku is running and the app is
 * authorised, and reconnects when Shizuku's server comes back — no foreground
 * keep-alive and no polling: the user service lives exactly as long as the
 * Shizuku server does.
 */
object CloneEngineAutoStart {

    private const val TAG = "CloneEngineAutoStart"
    private const val SHIZUKU_PERMISSION = "moe.shizuku.manager.permission.API_V23"

    @Volatile
    private var requested = false

    @Volatile
    private var requestedAt = 0L

    @Volatile
    private var listenerInstalled = false

    /** Idempotent: attach the engine and start it when Shizuku is usable. */
    fun start(context: Context) {
        val app = context.applicationContext
        cleanupOrphanServices()
        runCatching { HeadlessCloneEngine.attach(app) }
            .onFailure { Log.w(TAG, "engine attach failed", it) }
        installBinderListener(app)
        tryStart()
    }

    /**
     * A previous app process may leave its bound user service behind when it is
     * force-stopped; those are the `<pkg>:shell` processes reparented to init.
     * Remove them before binding a new one — the engine must not outlive the
     * app in the background, and two engines would fight over the display.
     */
    private fun cleanupOrphanServices() {
        val usable = runCatching {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!usable) return
        runCatching {
            com.altaris.linux.core.system.ShizukuShell.run(
                "for p in \$(pidof com.altaris.linux:shell 2>/dev/null); do kill -9 \$p; done",
                5_000L,
            )
        }.onFailure { Log.w(TAG, "orphan engine cleanup failed", it) }
    }

    /** Called by the Apps screen too, so a late permission still connects. */
    fun retry(context: Context) {
        runCatching { HeadlessCloneEngine.attach(context.applicationContext) }
        // A start that never landed may be retried, but not while the previous
        // bind is still in flight.
        if (!requested || System.currentTimeMillis() - requestedAt > 15_000L) {
            requested = false
        }
        tryStart()
    }

    private fun tryStart() {
        if (requested) return
        val usable = runCatching {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!usable) return
        requested = true
        requestedAt = System.currentTimeMillis()
        runCatching { HeadlessCloneEngine.startService() }
            .onFailure {
                requested = false
                Log.w(TAG, "engine start failed", it)
            }
    }

    private fun installBinderListener(app: Context) {
        if (listenerInstalled) return
        listenerInstalled = true
        runCatching {
            Shizuku.addBinderReceivedListenerSticky {
                // A fresh server invalidates the previous service; try once more.
                requested = false
                tryStart()
            }
        }.onFailure {
            listenerInstalled = false
            Log.w(TAG, "Shizuku binder listener failed", it)
        }
    }
}
