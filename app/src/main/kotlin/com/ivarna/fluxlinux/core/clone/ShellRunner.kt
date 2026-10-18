package com.ivarna.fluxlinux.core.clone

import android.content.pm.PackageManager
import com.ivarna.fluxlinux.core.utils.RootUtils
import rikka.shizuku.Shizuku

/**
 * Privileged shell for the clone space.
 *
 * Backend order: Shizuku (shell uid, user-granted) -> root (su) -> none.
 * Everything the clone feature needs at shell level (touch injection into the
 * hidden clone display, key events, text) goes through here, so the same code
 * works on a stock non-rooted phone with Shizuku, or on a rooted one.
 */
object ShellRunner {

    enum class Backend(val label: String) {
        SHIZUKU("Shizuku (shell)"),
        ROOT("Root (su)"),
        NONE("Not available"),
    }

    data class Result(val code: Int, val stdout: String, val stderr: String) {
        val ok: Boolean get() = code == 0
    }

    fun backend(): Backend = when {
        shizukuReady() -> Backend.SHIZUKU
        RootUtils.isRootAvailable() -> Backend.ROOT
        else -> Backend.NONE
    }

    fun shizukuReady(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun exec(vararg args: String): Result {
        if (args.isEmpty()) return Result(-1, "", "empty command")
        if (shizukuReady()) {
            shizukuExec(args)?.let { return it }
        }
        if (RootUtils.isRootAvailable()) return rootExec(args)
        return Result(-1, "", "no shell backend")
    }

    // ponytail: Shizuku.newProcess is hidden in the public 13.x API; reflection keeps
    // us off a custom AIDL UserService (same trick as PhantomProcessFixer).
    private fun shizukuExec(args: Array<out String>): Result? = try {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        ).apply { isAccessible = true }
        val p = m.invoke(null, arrayOf(*args), null, null) as Process
        p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        Result(p.waitFor(), out.trim(), err.trim())
    } catch (t: Throwable) {
        null
    }

    private fun rootExec(args: Array<out String>): Result {
        val cmd = args.joinToString(" ") { quote(it) }
        val r = RootUtils.runRootCommand(cmd)
        return Result(if (r.isSuccess) 0 else -1, r.output, r.error)
    }

    private fun quote(s: String): String =
        if (s.isNotEmpty() && s.all { it.isLetterOrDigit() || it in "_-./:@=+,%^" }) s
        else "'" + s.replace("'", "'\\''") + "'"
}
