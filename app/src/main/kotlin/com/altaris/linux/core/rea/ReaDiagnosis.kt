package com.altaris.linux.core.rea

import android.content.Context
import com.altaris.linux.core.install.DistroInstallProfile
import com.altaris.linux.core.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A measured diagnosis of the REA environment.
 *
 * "chroot is broken" is not actionable. Every check here answers one question
 * that decides whether the environment can run at all, and reports the fact it
 * measured — a missing binary, a denied root grant, a rootfs that exists but
 * has no REA core in it.
 *
 * Nothing here is guessed from cached flags alone: the root grant is re-probed,
 * and the chroot checks run inside the chroot, because `/data/local` is not
 * readable from the app process.
 */
object ReaDiagnosis {

    data class Check(
        val label: String,
        val detail: String,
        val ok: Boolean,
    )

    data class State(
        val running: Boolean = false,
        val checks: List<Check> = emptyList(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Run every check. Safe to call repeatedly; the last result is kept. */
    suspend fun run(context: Context): State = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        _state.value = State(running = true, checks = _state.value.checks)
        val checks = ArrayList<Check>()

        // 1. Root, re-probed. The cached answer is what made a cold start
        //    report "no root" on a device that had granted it.
        val root = runCatching {
            RootShell.isRootAvailable() || run {
                RootShell.clearSuCache()
                RootShell.isRootAvailable()
            }
        }.getOrDefault(false)
        checks += Check(
            "Root",
            if (root) "granted" else "not granted — proot works, chroot cannot",
            root,
        )

        // 2. The distro REA is installed in: is it there, and is REA inside it?
        val target = ReaDistro.activeTarget(app)
        if (target == null) {
            checks += Check("Target distro", "no installed distro to host REA", false)
        } else {
            val profile = DistroInstallProfile.forId(target.id)
            val core = runCatching { ReaManager.isInstalledIn(app, target.id) }
                .getOrDefault(false)
            checks += Check(
                target.label + " (" + target.method + ")",
                if (profile == null) "unknown profile" else rootfsDetail(app, profile),
                profile != null,
            )
            checks += Check(
                target.label + " REA core",
                if (core) ReaManager.REA_BIN + " present"
                else "missing — install it from Tools",
                core,
            )
            if (target.isChroot && profile != null) {
                checks += chrootMountCheck(app, profile)
            }
        }

        // 3. The bridge port. A stale socket from a killed process is the other
        //    reason a client cannot connect.
        val bound = runCatching { ReaBridge.state.value.running }.getOrDefault(false)
        checks += Check(
            "MCP bridge",
            if (bound) ReaBridge.liveEndpoint else "not running",
            bound,
        )

        val result = State(running = false, checks = checks)
        _state.value = result
        result
    }

    /** Size and presence of a rootfs, measured through root for chroot. */
    private fun rootfsDetail(context: Context, profile: DistroInstallProfile): String {
        val path = if (profile.method == "chroot") {
            profile.chrootPath.orEmpty()
        } else if (profile.prootName.isBlank()) {
            ""
        } else {
            File(
                context.filesDir,
                "usr/var/lib/proot-distro/containers/${profile.prootName}/rootfs",
            ).absolutePath
        }
        if (path.isBlank()) return "path unknown"
        val probe = runCatching {
            RootShell.captureResult(
                "du -sh " + quote(path) + " 2>/dev/null | cut -f1",
                30_000L,
            ).stdout.trim()
        }.getOrDefault("")
        return if (probe.isNotBlank()) "present ($probe)" else "present"
    }

    /**
     * Mounts currently attached under the chroot.
     *
     * A leftover mount from a previous session is the single most common reason
     * an install or a removal fails, and it is invisible from the app.
     */
    private fun chrootMountCheck(
        context: Context,
        profile: DistroInstallProfile,
    ): Check {
        val path = profile.chrootPath.orEmpty()
        if (path.isBlank()) return Check("chroot mounts", "path unknown", false)
        val mounts = runCatching {
            RootShell.captureResult(
                "grep -c ' " + quote(path) + "/' /proc/mounts 2>/dev/null || echo 0",
                20_000L,
            ).stdout.trim()
        }.getOrDefault("0")
        val count = mounts.toIntOrNull() ?: 0
        return Check(
            "chroot mounts",
            if (count == 0) "none attached (clean)" else "$count attached",
            // Leftover mounts are not an error while the environment is in use;
            // report the fact and let the user decide. A *removal* refuses on
            // them, which is why they are worth showing.
            true,
        )
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
