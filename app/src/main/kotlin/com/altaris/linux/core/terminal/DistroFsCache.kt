package com.altaris.linux.core.terminal

import android.content.Context
import com.altaris.linux.core.data.DistroRepository

/**
 * Process-wide cache of "which distros exist on disk".
 *
 * The Distros tab used to probe every rootfs on each entry (14 filesystem
 * checks, some behind a root shell for chroot), which made the tab visibly
 * slower than Home/Apps. The probe is now done once and reused until an
 * install/uninstall bumps the generation — the tab opens instantly and the
 * first launch warms the cache in the background.
 *
 * All methods are safe to call from any thread.
 */
object DistroFsCache {

    data class Snapshot(
        val installed: Set<String>,
        val unfinished: Set<String>,
        val generation: Int,
    )

    @Volatile
    private var generation = 0

    @Volatile
    private var snapshot: Snapshot? = null

    /** Current generation; a snapshot with a lower one is stale. */
    val currentGeneration: Int get() = generation

    /**
     * Mark the cached snapshot stale. It is kept so screens can paint the last
     * known state instantly (stale-while-revalidate) instead of flashing an
     * empty list while the probe runs.
     */
    fun invalidate() {
        generation += 1
    }

    /** The last snapshot, fresh or stale, or null before the first probe. */
    fun peek(): Snapshot? = snapshot

    /** The last snapshot only when it matches [generation]. */
    fun peekFresh(): Snapshot? = snapshot?.takeIf { it.generation == generation }

    /** Probe (or reuse) the installed/unfinished sets. Blocking — IO only. */
    fun get(context: Context): Snapshot {
        val cached = peekFresh()
        if (cached != null) return cached
        return synchronized(this) {
            val again = peekFresh()
            if (again != null) {
                again
            } else {
                val gen = generation
                val distros = DistroRepository.supportedDistros
                val installed = distros
                    .filter { TerminalLauncher.isDistroInstalledOnFs(context, it.id) }
                    .map { it.id }
                    .toSet()
                val unfinished = distros
                    .filter { TerminalLauncher.isDistroUnfinished(context, it.id) }
                    .map { it.id }
                    .toSet()
                Snapshot(installed, unfinished, gen).also { snapshot = it }
            }
        }
    }

    /** Cheap background warm-up, called once from the Application. */
    fun warm(context: Context) {
        runCatching { get(context.applicationContext) }
    }
}
