package com.altaris.linux.core.terminal

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File

/** Cancel handle for a streamed shell process. */
class ShellJob(private val process: Process) {
    @Volatile
    var cancelled: Boolean = false
        private set

    fun cancel() {
        cancelled = true
        try {
            process.destroyForcibly()
        } catch (_: Exception) {
        }
    }
}

/** Runs shell commands with proper environment, supporting both proot and chroot.
 *  Ported from termux-lib `ShellCommandRunner`. */
object ShellCommandRunner {

    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * Host ET_DYN tools (libbash.so / libproot.so) live under /data/app or /data/data.
     * Exec'd directly: the kernel resolves PT_INTERP (arm64 → linker64), and on
     * x86_64 hosts with NDK translation the native bridge engages only for direct
     * execs — prepending /system/bin/linker64 would invoke the x86_64 linker and
     * reject the arm64 ELF (EM_AARCH64 vs EM_X86_64).
     */
    private fun adjustHostCmd(cmd: Array<String>): Array<String> {
        return cmd
    }

    /**
     * Host commands run under the NDK translation layer on x86_64 devices; the
     * translation runner resolves the exec'd binary's argv[0] with realpath()
     * against the process CWD. Point the CWD at $PREFIX/bin so bare command
     * names (mkdir, cp, …) resolve to the prefix's applet symlinks.
     */
    private fun setHostCwd(pb: ProcessBuilder, cmd: Array<String>) {
        if (cmd.isEmpty()) return
        val exe = cmd[0]
        val underAppData =
            exe.startsWith("/data/data/") ||
                exe.startsWith("/data/app/") ||
                exe.startsWith("/data/user/")
        if (underAppData) {
            runCatching { pb.directory(File(TermuxHostPaths.BIN)) }
        }
    }

    /** Runs a command and returns its exit code. Output is consumed but not returned. */
    fun run(ctx: Context, cmd: Array<String>, envMap: Map<String, String>? = null): Int {
        val pb = ProcessBuilder(*adjustHostCmd(cmd)); setHostCwd(pb, cmd)
        applyEnvironment(ctx, pb, envMap)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val stream = proc.inputStream
        val buf = ByteArray(1024)
        while (stream.read(buf) != -1) { /* consume */ }
        return proc.waitFor()
    }

    /** Runs a command and returns combined stdout/stderr as a string (blocking). */
    fun runCapture(ctx: Context, cmd: Array<String>, envMap: Map<String, String>? = null): String {
        val pb = ProcessBuilder(*adjustHostCmd(cmd)); setHostCwd(pb, cmd)
        applyEnvironment(ctx, pb, envMap)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val text = proc.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        proc.waitFor()
        return text
    }

    /**
     * Blocking capture with exit code (call from bg thread).
     *
     * When [onLine] is set, each stdout/stderr line is delivered as it arrives
     * (same thread as the caller) so install UIs can show live logs.
     * Note: do not wrap with $PREFIX/bin/stdbuf — W^X (targetSdk 36) denies
     * exec from app-data prefix (EACCES). Host argv0 must be nativeLibraryDir.
     */
    fun runCaptureExit(
        ctx: Context,
        cmd: Array<String>,
        envMap: Map<String, String>? = null,
        /** Optional: store the live Process so callers can destroyForcibly on cancel. */
        processHolder: java.util.concurrent.atomic.AtomicReference<Process?>? = null,
        /** Optional: live line callback (bg thread). */
        onLine: ((String) -> Unit)? = null,
        /**
         * Kill the process after this many milliseconds; 0 waits forever.
         *
         * Callers passed a timeout all along, but this function had no such
         * parameter, so it was dropped. A hung guest command — an `apt-get`
         * waiting on a lock, a network read with no deadline — therefore ran
         * until the app was killed, and if it was root it kept the apt lock
         * after the app died. Exit code -2 means it timed out.
         */
        timeoutMs: Long = 0L
    ): Pair<Int, String> {
        val pb = ProcessBuilder(*adjustHostCmd(cmd)); setHostCwd(pb, cmd)
        applyEnvironment(ctx, pb, envMap)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        processHolder?.set(proc)
        return try {
            // Close stdin so interactive scripts (`read -p "Press Enter…"`) get EOF
            // instead of hanging forever under onboarding / non-TTY ProcessBuilder.
            try {
                proc.outputStream.close()
            } catch (_: Exception) {
            }
            // Read on a separate thread. Reading inline and then waiting makes
            // the timeout useless: the read blocks until the process exits.
            val sb = StringBuilder()
            val reader = Thread {
                try {
                    proc.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                        var line: String?
                        while (r.readLine().also { line = it } != null) {
                            val l = line ?: continue
                            synchronized(sb) { sb.append(l).append('\n') }
                            try {
                                onLine?.invoke(l)
                            } catch (_: Exception) {
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }.apply { isDaemon = true; start() }

            val finished = try {
                if (timeoutMs > 0L) {
                    proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                } else {
                    proc.waitFor()
                    true
                }
            } catch (_: InterruptedException) {
                proc.destroyForcibly()
                false
            }
            if (!finished) {
                proc.destroyForcibly()
                try {
                    reader.join(1_000L)
                } catch (_: Exception) {
                }
                val partial = synchronized(sb) { sb.toString() }
                return -2 to partial
            }
            try {
                reader.join(5_000L)
            } catch (_: Exception) {
            }
            val exit = runCatching { proc.exitValue() }.getOrDefault(-1)
            exit to synchronized(sb) { sb.toString() }
        } finally {
            processHolder?.compareAndSet(proc, null)
        }
    }

    /** Runs a command and streams each output line to [onLine] on the main thread. */
    fun runStreamed(
        ctx: Context,
        cmd: Array<String>,
        envMap: Map<String, String>? = null,
        onLine: (line: String) -> Unit,
        onDone: (exitCode: Int) -> Unit
    ) {
        runStreamedCancelable(ctx, cmd, envMap, onLine, onDone)
    }

    /**
     * Streamed run with cancel handle. [onLine]/[onDone] post to main thread.
     * Process starts on a worker thread; cancel is safe before/after start.
     */
    fun runStreamedCancelable(
        ctx: Context,
        cmd: Array<String>,
        envMap: Map<String, String>? = null,
        onLine: (line: String) -> Unit,
        onDone: (exitCode: Int) -> Unit
    ): ShellJob {
        val pb = ProcessBuilder(*adjustHostCmd(cmd)); setHostCwd(pb, cmd)
        applyEnvironment(ctx, pb, envMap)
        pb.redirectErrorStream(true)

        // Placeholder process; real Process attached once started
        val holder = arrayOfNulls<Process>(1)
        val job = ShellJob(object : Process() {
            override fun getOutputStream() = java.io.ByteArrayOutputStream()
            override fun getInputStream() = java.io.ByteArrayInputStream(ByteArray(0))
            override fun getErrorStream() = java.io.ByteArrayInputStream(ByteArray(0))
            override fun waitFor(): Int = -1
            override fun exitValue(): Int = -1
            override fun destroy() {
                holder[0]?.destroyForcibly()
            }
            override fun destroyForcibly(): Process {
                holder[0]?.destroyForcibly()
                return this
            }
            override fun isAlive(): Boolean = holder[0]?.isAlive == true
        })

        Thread {
            val proc = try {
                if (job.cancelled) {
                    mainHandler.post { onDone(-1) }
                    return@Thread
                }
                pb.start().also { holder[0] = it }
            } catch (_: Exception) {
                mainHandler.post { onDone(-1) }
                return@Thread
            }
            try {
                val reader = proc.inputStream.bufferedReader(Charsets.UTF_8)
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (job.cancelled) break
                    val l = line ?: continue
                    mainHandler.post { onLine(l) }
                }
            } catch (_: Exception) {
            }
            val exit = try {
                if (job.cancelled) {
                    try { proc.destroyForcibly() } catch (_: Exception) {}
                    -1
                } else {
                    proc.waitFor()
                }
            } catch (_: Exception) {
                -1
            }
            mainHandler.post { onDone(exit) }
        }.start()

        return job
    }

    private fun applyEnvironment(ctx: Context, pb: ProcessBuilder, envMap: Map<String, String>?) {
        val env = pb.environment()
        HostCommandBuilder.applyTo(ctx, pb, forceHostSetup = false)
        env["GIT_TERMINAL_PROMPT"] = "0"

        // Override with envMap if provided (e.g. chroot-specific values)
        envMap?.forEach { (k, v) ->
            env[k] = v
        }
    }
}
