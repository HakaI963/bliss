package com.altaris.linux.core.theme

import android.content.Context
import android.util.Log
import com.altaris.linux.core.desktop.DesktopLauncher
import com.altaris.linux.core.desktop.DesktopSession
import com.altaris.linux.core.desktop.DesktopSessionQuery
import com.altaris.linux.core.root.RootShell
import com.altaris.linux.core.terminal.HostCommandBuilder
import com.altaris.linux.core.terminal.ShellCommandRunner
import com.altaris.linux.core.terminal.TermuxHostPaths
import java.util.concurrent.atomic.AtomicReference

/**
 * Live application of the staged Altaris appearance to a *running* desktop.
 *
 * The guest applier (`~/.config/altaris/appearance-apply.sh`) writes the XML/rc
 * files; this object additionally pokes the running session so the change is
 * visible immediately:
 *  - xfconf live values for every existing xfce4-desktop backdrop property
 *    (D-Bus session address discovered from the running session's processes),
 *  - `xfdesktop --reload` (fallback `pkill -x xfdesktop`, respawned by
 *    xfce4-session) so the backdrop repaints,
 *  - `xfce4-panel -r` so panel opacity/icon-size changes repaint.
 *
 * Proot: runs the guest command through the host-side `proot-distro login`
 * (same approach as [com.altaris.linux.core.install.OnboardingInstallRunner]).
 * Chroot: runs it through [RootShell.captureInChroot] as root.
 *
 * Everything is fail-safe: the caller keeps the honest "applies when the
 * desktop next starts" status when live apply cannot run.
 */
object AppearanceLiveApply {

    private const val TAG = "AppearanceLiveApply"
    private const val LIVE_OK = "ALTARIS_APPEARANCE_LIVE_OK"
    private const val LIVE_NO_SCRIPT = "ALTARIS_APPEARANCE_NO_SCRIPT"
    private const val PROOT_TIMEOUT_MS = 75_000L
    private const val CHROOT_TIMEOUT_MS = 75_000L

    enum class State { APPLIED, FAILED, SKIPPED }

    data class Outcome(val state: State, val detail: String? = null)

    /** The running desktop session for [distroId], or null. */
    fun sessionFor(ctx: Context, distroId: String): DesktopSession? {
        val app = ctx.applicationContext
        return try {
            DesktopSessionQuery.current(app, DesktopLauncher.uiState.value)
                ?.takeIf { it.distroId == distroId }
        } catch (e: Exception) {
            Log.w(TAG, "session probe failed: ${e.message}")
            null
        }
    }

    /** Live apply inside a proot distro (blocking; background thread only). */
    fun applyProot(ctx: Context, prootName: String): Outcome {
        if (prootName.isBlank()) return Outcome(State.FAILED, "unknown proot distro")
        val app = ctx.applicationContext
        return try {
            val bash = TermuxHostPaths.libBash(app).absolutePath
            val login = "exec python ${TermuxHostPaths.PROOT_DISTRO} login $prootName " +
                "--shared-tmp -- /bin/sh -c ${shellQuote(GUEST_LIVE_CMD)}"
            val env = HostCommandBuilder.envMap(app, includeTerm = false).apply {
                put("PYTHONUNBUFFERED", "1")
            }
            val holder = AtomicReference<Process?>()
            val watchdog = Thread {
                try {
                    Thread.sleep(PROOT_TIMEOUT_MS)
                    holder.get()?.destroyForcibly()
                } catch (_: InterruptedException) {
                }
            }.apply { isDaemon = true; start() }
            val (exit, out) = try {
                ShellCommandRunner.runCaptureExit(
                    app, arrayOf(bash, "-c", login), env, processHolder = holder
                )
            } finally {
                watchdog.interrupt()
            }
            when {
                out.contains(LIVE_NO_SCRIPT) -> Outcome(State.FAILED, "applier not staged")
                exit == 0 && out.contains(LIVE_OK) -> Outcome(State.APPLIED)
                else -> Outcome(State.FAILED, out.trim().takeLast(400))
            }
        } catch (e: Exception) {
            Log.w(TAG, "proot live apply failed: ${e.message}")
            Outcome(State.FAILED, e.message)
        }
    }

    /** Live apply inside a chroot distro (blocking; background thread only). */
    fun applyChroot(ctx: Context, chrootPath: String): Outcome {
        if (chrootPath.isBlank()) return Outcome(State.FAILED, "unknown chroot path")
        return try {
            val res = RootShell.captureInChroot(
                GUEST_LIVE_CMD,
                user = "root",
                chrootPath = chrootPath,
                timeoutMs = CHROOT_TIMEOUT_MS,
                context = ctx.applicationContext
            )
            when {
                res.stdout.contains(LIVE_NO_SCRIPT) -> Outcome(State.FAILED, "applier not staged")
                res.stdout.contains(LIVE_OK) -> Outcome(State.APPLIED)
                else -> Outcome(State.FAILED, res.stdout.trim().takeLast(400))
            }
        } catch (e: Exception) {
            Log.w(TAG, "chroot live apply failed: ${e.message}")
            Outcome(State.FAILED, e.message)
        }
    }

    /** Single-quote a string for a POSIX shell command. */
    internal fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    /**
     * Guest-side live command (POSIX sh; runs inside the distro). Kept as one
     * constant so it is single-sourced/testable. Steps:
     *  1. run the staged applier with FORCE + machine-readable pick output,
     *  2. set the picked wallpaper/style on every live xfconf backdrop path
     *     (needs the session D-Bus address; discovered from /proc),
     *  3. reload xfdesktop and restart xfce4-panel (best-effort).
     */
    internal val GUEST_LIVE_CMD: String = """
        _fa_sc=/home/flux/.config/altaris/appearance-apply.sh
        if [ ! -r "${'$'}_fa_sc" ]; then echo ALTARIS_APPEARANCE_NO_SCRIPT; exit 0; fi
        _fa_out=${'$'}(ALTARIS_APPLY_HOME=/home/flux ALTARIS_APPLY_FORCE=1 ALTARIS_APPLY_PRINT_PICK=1 /bin/sh "${'$'}_fa_sc" 2>/dev/null || true)
        _fa_wp=${'$'}(printf '%s\n' "${'$'}_fa_out" | sed -n 's/^ALTARIS_APPEARANCE_PICK_IMAGE=//p' | head -n 1)
        _fa_st=${'$'}(printf '%s\n' "${'$'}_fa_out" | sed -n 's/^ALTARIS_APPEARANCE_PICK_STYLE=//p' | head -n 1)
        export DISPLAY=:0
        _fa_dbus=""
        for _fa_c in xfdesktop xfconfd xfce4-session; do
          _fa_pid=""
          if command -v pidof >/dev/null 2>&1; then
            _fa_pid=${'$'}(pidof "${'$'}_fa_c" 2>/dev/null | awk '{print ${'$'}1}')
          fi
          if [ -z "${'$'}_fa_pid" ] && command -v pgrep >/dev/null 2>&1; then
            _fa_pid=${'$'}(pgrep -x "${'$'}_fa_c" 2>/dev/null | head -n 1)
          fi
          [ -n "${'$'}_fa_pid" ] || continue
          [ -r "/proc/${'$'}_fa_pid/environ" ] || continue
          _fa_dbus=${'$'}(tr '\000' '\n' < "/proc/${'$'}_fa_pid/environ" 2>/dev/null | sed -n 's/^DBUS_SESSION_BUS_ADDRESS=//p' | head -n 1)
          [ -n "${'$'}_fa_dbus" ] && break
        done
        [ -n "${'$'}_fa_dbus" ] && export DBUS_SESSION_BUS_ADDRESS="${'$'}_fa_dbus"
        if command -v xfconf-query >/dev/null 2>&1; then
          for _fa_p in ${'$'}(xfconf-query -c xfce4-desktop -l 2>/dev/null | grep '/last-image${'$'}' || true); do
            if [ -n "${'$'}_fa_wp" ]; then
              xfconf-query -c xfce4-desktop -p "${'$'}_fa_p" -s "${'$'}_fa_wp" >/dev/null 2>&1 || true
            fi
            if [ -n "${'$'}_fa_st" ]; then
              xfconf-query -c xfce4-desktop -p "${'$'}{_fa_p%/last-image}/image-style" -s "${'$'}_fa_st" >/dev/null 2>&1 || true
            fi
          done
        fi
        if command -v xfdesktop >/dev/null 2>&1; then
          xfdesktop --reload >/dev/null 2>&1 || pkill -x xfdesktop >/dev/null 2>&1 || true
        fi
        if command -v xfce4-panel >/dev/null 2>&1; then
          xfce4-panel -r >/dev/null 2>&1 || true
        fi
        echo ALTARIS_APPEARANCE_LIVE_OK
    """.trimIndent()
}
