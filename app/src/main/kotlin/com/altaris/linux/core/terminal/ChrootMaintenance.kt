package com.altaris.linux.core.terminal

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.altaris.linux.core.control.AltarisCliInstaller
import com.altaris.linux.core.root.RootShell
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Deferred root-only maintenance for chroot guests (CLI install, profile and
 * banner repair).
 *
 * These passes use several `su` calls and can take minutes on a cold install.
 * Running them before the session is added made the terminal appear dead and,
 * because session prep used a single shared executor, held back every other
 * distro (proot included). They now run after the terminal is open, on their
 * own daemon thread, deduplicated per distro.
 */
object ChrootMaintenance {

    private const val TAG = "ChrootMaintenance"
    private const val MIN_INTERVAL_MS = 10 * 60_000L

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "altaris-chroot-maintenance").apply { isDaemon = true }
    }

    /** distroId -> elapsedRealtime of the last scheduled pass. */
    private val lastScheduled = ConcurrentHashMap<String, Long>()

    /**
     * Queue a maintenance pass for [distroId]. Never blocks the caller and never
     * prompts for root on its own: a chroot session is only reachable after the
     * user granted superuser, and [RootShell.warmChrootRuntimeAsync] handles the
     * helper staging separately.
     */
    fun schedule(ctx: Context, distroId: String?) {
        if (distroId.isNullOrBlank()) return
        val now = SystemClock.elapsedRealtime()
        val previous = lastScheduled[distroId]
        if (previous != null && now - previous < MIN_INTERVAL_MS) return

        // Cheap guards first: burning the 10-minute window before them left a
        // failed pass blocking maintenance for a whole interval.
        val app = ctx.applicationContext ?: return
        // Cache read only — never probe. isRootAvailable() here used to fire a
        // fresh su prompt from a background pass, exactly what the comment below
        // promised not to do. A cold cache means the terminal's own su will ask,
        // and the next open lands in the cached branch.
        if (RootShell.peekSuInvocation() == null) return
        lastScheduled[distroId] = now

        executor.execute {
            try {
                AltarisCliInstaller.ensure(app, "chroot", distroId)
                GuestZshrcRepair.repairIfNeeded(app, "chroot", distroId)
            } catch (e: Exception) {
                Log.w(TAG, "chroot maintenance failed for $distroId", e)
            }
        }
    }

    /** Test hook. */
    internal fun resetForTest() = lastScheduled.clear()
}
