package com.altaris.linux.core.rea

import android.content.Context
import android.util.Log
import com.altaris.linux.core.install.DistroInstallProfile
import com.altaris.linux.core.root.RootShell
import com.altaris.linux.core.terminal.HostCommandBuilder
import com.altaris.linux.core.terminal.ShellCommandRunner
import com.altaris.linux.core.terminal.TermuxHostPaths

/**
 * Runs a shell script inside an installed distro, for both proot and chroot.
 *
 * There is no shared guest-exec abstraction in the app: sessions go through
 * [com.altaris.linux.core.terminal.GuestSessionFactory], one-shot chroot work
 * through [RootShell.captureInChroot], and proot work through
 * `proot-distro login`. REA needs all three shapes, so this object is the thin
 * adapter it uses instead of duplicating the rules at every call site.
 *
 * Blocking. Call from Dispatchers.IO only.
 */
object GuestExec {

    data class Result(val exit: Int, val output: String) {
        val ok: Boolean get() = exit == 0
        fun tail(lines: Int = 30): String = output.trim().lines().takeLast(lines).joinToString("\n")
    }

    fun methodOf(distroId: String): String =
        DistroInstallProfile.forId(distroId)?.method ?: "proot"

    fun run(
        ctx: Context,
        distroId: String,
        script: String,
        timeoutMs: Long = 120_000L,
        asRoot: Boolean = true,
        onLine: ((String) -> Unit)? = null,
    ): Result {
        val profile = DistroInstallProfile.forId(distroId)
            ?: return Result(-1, "unknown distro: " + distroId)
        if (profile.method == "chroot") {
            val path = profile.chrootPath
                ?: return Result(-1, "chroot path missing for " + distroId)
            val captured = RootShell.captureInChroot(
                cmd = script,
                user = if (asRoot) "root" else "altaris",
                chrootPath = path,
                timeoutMs = timeoutMs,
                context = ctx,
                onLine = onLine,
            )
            return Result(captured.exitCode, captured.stdout)
        }
        return runProot(ctx, profile, script, asRoot, timeoutMs, onLine)
    }

    private fun runProot(
        ctx: Context,
        profile: DistroInstallProfile,
        script: String,
        asRoot: Boolean,
        timeoutMs: Long,
        onLine: ((String) -> Unit)?,
    ): Result {
        val app = ctx.applicationContext
        return try {
            val bash = TermuxHostPaths.libBash(app).absolutePath
            val user = if (asRoot) " --user root" else ""
            val login = "exec python " + TermuxHostPaths.PROOT_DISTRO +
                " login " + shellQuote(profile.prootName) + user +
                " --shared-tmp -- /bin/sh -c " + shellQuote(script)
            val env = HostCommandBuilder.envMap(app, includeTerm = false)
            val (exit, out) = ShellCommandRunner.runCaptureExit(
                app,
                arrayOf(bash, "-c", login),
                env,
                onLine = onLine,
            )
            Result(exit, out)
        } catch (t: Throwable) {
            Log.w(TAG, "proot exec failed", t)
            Result(-1, t.message ?: t.javaClass.simpleName)
        }
    }

    /** POSIX single-quote escaping; the only safe form for arbitrary scripts. */
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private const val TAG = "AltarisRea"
}
