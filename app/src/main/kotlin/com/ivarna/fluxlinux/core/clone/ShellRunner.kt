package com.ivarna.fluxlinux.core.clone

import android.content.pm.PackageManager
import com.ivarna.fluxlinux.core.utils.RootUtils
import rikka.shizuku.Shizuku
import java.io.File
import java.io.InputStream

/**
 * Privileged shell for the clone space.
 *
 * Backend order: Shizuku (shell uid, user-granted) -> root (su) -> none.
 * Everything the clone feature needs at shell level (creating the isolated
 * clone user, installing APKs for it and starting apps in that user) goes
 * through here, so the same code works on a stock non-rooted phone with
 * Shizuku, or on a rooted one.
 */
object ShellRunner {

    enum class Backend(val label: String) {
        SHIZUKU("Shizuku (shell)"),
        ROOT("Root (su)"),
        NONE("Not available"),
    }

    data class Result(val code: Int, val stdout: String, val stderr: String) {
        val ok: Boolean get() = code == 0
        val combined: String get() = (stdout + "\n" + stderr).trim()
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

    /**
     * Run a command as shell with [stdin] piped in (used by `pm install -S`).
     * Shizuku only — the root path copies files to a shell-readable location
     * instead (root can read the app's private files directly).
     */
    fun execWithStdin(stdin: File, vararg args: String): Result {
        if (args.isEmpty()) return Result(-1, "", "empty command")
        val p = shizukuProcess(*args)
            ?: return Result(-1, "", "stdin install needs Shizuku")
        val out = StringBuilder()
        val err = StringBuilder()
        val outThread = pump(p.inputStream, out)
        val errThread = pump(p.errorStream, err)
        try {
            stdin.inputStream().use { ins ->
                p.outputStream.use { os -> ins.copyTo(os, 128 * 1024) }
            }
        } catch (t: Throwable) {
            runCatching { p.destroy() }
            return Result(-1, out.toString(), "stdin write failed: ${t.message}")
        }
        val code = runCatching { p.waitFor() }.getOrDefault(-1)
        runCatching { outThread.join(2000) }
        runCatching { errThread.join(2000) }
        return Result(code, out.toString().trim(), err.toString().trim())
    }

    /**
     * Copy [file] to [remote] through a shell process. Used for clone installs:
     * the app's private APK is streamed into /data/local/tmp, then `pm install`
     * reads the staged file (this avoids pm's stdin transport, which is flaky
     * on some Shizuku builds).
     */
    fun copyToShell(file: File, remote: String): Result {
        val p = shizukuProcess("sh", "-c", "cat > " + quote(remote))
            ?: return Result(-1, "", "copy needs Shizuku")
        val out = StringBuilder()
        val err = StringBuilder()
        val outThread = pump(p.inputStream, out)
        val errThread = pump(p.errorStream, err)
        try {
            file.inputStream().use { ins ->
                p.outputStream.use { os -> ins.copyTo(os, 128 * 1024) }
            }
        } catch (t: Throwable) {
            runCatching { p.destroy() }
            return Result(-1, out.toString(), "copy failed: ${t.message}")
        }
        val code = runCatching { p.waitFor() }.getOrDefault(-1)
        runCatching { outThread.join(2000) }
        runCatching { errThread.join(2000) }
        return Result(code, out.toString().trim(), err.toString().trim())
    }

    private fun pump(stream: InputStream, into: StringBuilder): Thread {
        val t = Thread {
            runCatching {
                stream.bufferedReader().use { reader ->
                    val buf = CharArray(8 * 1024)
                    while (true) {
                        val n = reader.read(buf)
                        if (n <= 0) break
                        synchronized(into) { into.append(buf, 0, n) }
                    }
                }
            }
        }
        t.isDaemon = true
        t.start()
        return t
    }

    // ponytail: Shizuku.newProcess is hidden in the public 13.x API; reflection keeps
    // us off a custom AIDL UserService (same trick as PhantomProcessFixer).
    private fun shizukuProcess(vararg args: String): Process? = try {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        ).apply { isAccessible = true }
        m.invoke(null, arrayOf(*args), null, null) as Process
    } catch (t: Throwable) {
        null
    }

    private fun shizukuExec(args: Array<out String>): Result? = try {
        val p = shizukuProcess(*args) ?: return null
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

    /** Run a command as root (used for staging files root can read). */
    fun execRoot(vararg args: String): Result = rootExec(args)

    private fun quote(s: String): String =
        if (s.isNotEmpty() && s.all { it.isLetterOrDigit() || it in "_-./:@=+,%^" }) s
        else "'" + s.replace("'", "'\\''") + "'"
}
