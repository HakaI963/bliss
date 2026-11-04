package com.altaris.linux.core.rea

import android.content.Context
import android.util.Log
import com.altaris.linux.core.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Frida, for runtime instrumentation and injection.
 *
 * Two halves, and they run in different places:
 *
 *  - **frida-server** runs on the **host**, as root. It is the thing that
 *    actually attaches to the clone's process, so it must be able to see the
 *    host PID and be able to ptrace it.
 *  - **the frida CLI** (`frida`, `frida-trace`, `frida-ps`) runs inside the REA
 *    distro and talks to that server over loopback, which proot and chroot
 *    share with the host.
 *
 * That split is not incidental. A `gdb` or `frida` *inside* the distro has its
 * own PID namespace: the host PID the clone reports does not exist there, which
 * is why an attach that looked correct silently debugged nothing. Anything that
 * has to touch the real process runs on the host side.
 *
 * frida-server is a single static ARM64 binary from the official release, so
 * installing it is one download and one chmod — no package manager involved.
 */
object ReaFrida {

    private const val TAG = "AltarisReaFrida"

    /** Pinned so a release cannot silently change the binary under us. */
    const val VERSION = "17.25.1"

    private const val SERVER_URL =
        "https://github.com/frida/frida/releases/download/$VERSION/frida-server-$VERSION-android-arm64.xz"

    /** Where the server lives on the host. Root-owned, outside the app sandbox. */
    const val HOST_DIR = "/data/local/altaris/frida"

    const val HOST_BIN = HOST_DIR + "/frida-server"

    /** The port the CLI and the server meet on. */
    const val PORT = 27042

    /** What state the runtime is in. */
    data class State(
        val serverInstalled: Boolean = false,
        val serverRunning: Boolean = false,
        /** True when the CLI is available inside the distro. */
        val clientInstalled: Boolean = false,
        val version: String = "",
        val error: String = "",
    )

    /**
     * True when frida-server is running on the host.
     *
     * Checked through root: the process belongs to root and its socket is not
     * readable by the app uid.
     */
    fun isServerRunning(): Boolean = runCatching {
        RootShell.captureResult(
            "pidof frida-server >/dev/null 2>&1 && echo RUNNING || echo STOPPED",
            15_000L,
        ).stdout.contains("RUNNING")
    }.getOrDefault(false)

    /** True when the binary exists on the host. */
    fun isServerInstalled(): Boolean = runCatching {
        RootShell.captureResult(
            "test -x " + HOST_BIN + " && echo YES || echo NO",
            15_000L,
        ).stdout.contains("YES")
    }.getOrDefault(false)

    /**
     * Download and install frida-server on the host.
     *
     * One binary, xz-compressed. Downloaded to the app's own cache (which is
     * writable without root), then moved into place as root. The move happens
     * through `su` because `/data/local/altaris` is not writable by the app.
     */
    suspend fun installServer(context: Context): State = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        if (!RootShell.isRootAvailable()) {
            return@withContext State(
                error = "frida-server needs root: it attaches to another app's process.",
            )
        }
        val cache = File(app.cacheDir, "frida-server-$VERSION.xz")
        val binary = File(app.cacheDir, "frida-server-$VERSION")
        try {
            if (!binary.isFile || binary.length() < 1_000_000) {
                if (!cache.isFile || cache.length() < 500_000) {
                    download(SERVER_URL, cache)
                }
                // Decompress here rather than in the guest: xz is not in every
                // distro, and the host always has the app's own code to do it.
                val ok = decompressXz(cache, binary)
                if (!ok) {
                    // Say what actually happened: a failed *download* and a
                    // failed *decode* used to produce the same message, which
                    // sent the diagnosis in the wrong direction.
                    return@withContext State(
                        error = "frida-server: downloaded " + cache.length() +
                            " bytes, decompressed " +
                            (if (binary.isFile) binary.length().toString() else "0") +
                            " — the archive is not a valid xz stream",
                    )
                }
            }
            // Copy in, then fix ownership and mode through root.
            val staged = RootShell.captureResult(
                "mkdir -p " + HOST_DIR + " && cp -f " + quote(binary.absolutePath) +
                    " " + HOST_BIN + " && chmod 755 " + HOST_BIN +
                    " && " + HOST_BIN + " --version 2>&1 | head -n1",
                120_000L,
            )
            val version = staged.stdout.lines().firstOrNull { it.contains(".") }?.trim().orEmpty()
            if (staged.exitCode != 0) {
                return@withContext State(
                    error = staged.stdout.takeLast(300).ifBlank { "cannot install frida-server" },
                )
            }
            Log.i(TAG, "frida-server installed: $version")
            State(serverInstalled = true, version = version)
        } catch (t: Throwable) {
            Log.w(TAG, "frida-server install failed", t)
            State(error = t.message ?: t.javaClass.simpleName)
        } finally {
            runCatching { cache.delete() }
        }
    }

    /**
     * Start frida-server on the host.
     *
     * Bound to loopback so the distro's CLI can reach it without exposing a
     * debugging port to the network.
     */
    suspend fun startServer(): State = withContext(Dispatchers.IO) {
        if (!isServerInstalled()) return@withContext State(error = "frida-server is not installed")
        if (isServerRunning()) return@withContext State(serverInstalled = true, serverRunning = true)
        val result = runCatching {
            RootShell.captureResult(
                "cd " + HOST_DIR + " && (" + HOST_BIN +
                    " -l 127.0.0.1:" + PORT + " >/dev/null 2>&1 &) && sleep 2 && " +
                    "pidof frida-server >/dev/null 2>&1 && echo STARTED || echo FAILED",
                60_000L,
            )
        }.getOrNull()
        val started = result?.stdout?.contains("STARTED") == true
        State(
            serverInstalled = true,
            serverRunning = started,
            error = if (started) "" else "frida-server did not start",
        )
    }

    /**
     * The frida-server command, matched exactly.
     *
     * `pkill -f frida-server` is a substring match over every command line —
     * and REA's own argv contains that string (its launch command names the
     * server binary), as do shells that merely mention it. Stopping frida
     * killed REA mid-analysis and the user's own shell with it. An anchored
     * match against the binary name only ever hits the server itself.
     */
    private const val SERVER_NAME = "frida-server"
    private const val KILL_SCRIPT =
        "for p in /proc/[0-9]*; do c=$(cat \"\$p/comm\" 2>/dev/null); " +
            "[ \"\$c\" = " + SERVER_NAME + " ] && kill -TERM \"\${p#/proc/}\" 2>/dev/null; done"

    /** Stop frida-server. */
    suspend fun stopServer(): State = withContext(Dispatchers.IO) {
        runCatching {
            RootShell.captureResult(KILL_SCRIPT + "; echo done", 30_000L)
        }
        lastUseAt = 0L
        State(serverInstalled = isServerInstalled(), serverRunning = false)
    }

    /** Last time a tool touched the server, for the idle watchdog. */
    @Volatile
    private var lastUseAt = 0L

    /**
     * How long the server may sit unused before it is stopped.
     *
     * frida-server is a resident root process that keeps a loopback socket and
     * a ptrace-capable agent in memory. Leaving it up permanently is what made
     * the battery drain: it is pure idle cost between sessions.
     */
    private const val IDLE_STOP_MS = 90_000L

    @Volatile
    private var watchdog: Thread? = null

    /** Record that the server was just used, so the watchdog resets. */
    fun touch() {
        lastUseAt = System.currentTimeMillis()
    }

    /**
     * Start the server for one session and arm the idle watchdog.
     *
     * Called from an attach rather than from the UI, so the server only exists
     * while an agent is actually debugging.
     */
    suspend fun ensureRunningForSession(): State {
        val state = startServer()
        touch()
        armWatchdog()
        return state
    }

    private fun armWatchdog() {
        if (watchdog?.isAlive == true) return
        watchdog = Thread {
            // Polls cheaply; the process check only runs when the deadline has
            // actually passed, so this is not a busy loop.
            while (true) {
                Thread.sleep(15_000L)
                val used = lastUseAt
                if (used == 0L) break
                if (System.currentTimeMillis() - used < IDLE_STOP_MS) continue
                Log.i(TAG, "frida-server idle, stopping")
                runCatching {
                    RootShell.captureResult(KILL_SCRIPT, 20_000L)
                }
                lastUseAt = 0L
                break
            }
        }.apply {
            isDaemon = true
            name = "frida-idle"
            start()
        }
    }

    /**
     * Install the frida CLI inside the distro.
     *
     * The CLI is a Python package, so it works on any architecture and does not
     * need a per-distro binary.
     */
    suspend fun installClient(context: Context, distroId: String): State =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val manager = ReaManager.detectPackageManager(app, distroId)
            val log = StringBuilder()

            fun run(script: String, timeoutMs: Long = 20 * 60_000L): GuestExec.Result? =
                runCatching { GuestExec.run(app, distroId, script, timeoutMs = timeoutMs) }
                    .getOrNull()
                    .also { log.append(it?.output.orEmpty()) }

            // Only a command check: the result is the answer, not a log line.
            //
            // This used to run through [run], so every probe appended its own
            // "CLIENT_MISSING" to the log — and the failure message reported
            // the *last* line, which was always that word. The real pip error
            // was thrown away, which is why the report said only
            // "tried: pip. Last line: CLIENT_MISSING".
            fun present(): Boolean =
                runCatching {
                    GuestExec.run(
                        app,
                        distroId,
                        "command -v frida >/dev/null 2>&1 && echo CLIENT_OK || echo CLIENT_MISSING",
                        60_000L,
                    )
                }.getOrNull()?.output?.contains("CLIENT_OK") == true

            // pip, installing it first if the distro ships python3 without it.
            //
            // "No module named pip" is normal on a minimal rootfs: python3 is
            // there, pip is a separate package. The first version called
            // `python3 -m pip` and reported the whole thing as a failure
            // without ever asking the distro to provide pip.
            val hasPip = run("python3 -m pip --version >/dev/null 2>&1 && echo PIP || echo NOPIP",
                60_000L)?.output?.contains("PIP") == true
            if (!hasPip && manager.isNotBlank() && manager != "none") {
                val pkg = when (manager) {
                    "apt" -> "python3-pip"
                    "apk" -> "py3-pip"
                    "dnf" -> "python3-pip"
                    "pacman" -> "python-pip"
                    else -> ""
                }
                if (pkg.isNotBlank()) {
                    log.append("installing $pkg via $manager\n")
                    run(ReaManager.packageInstallScript(manager, listOf(pkg)), 15 * 60_000L)
                }
            }
            if (present()) {
                return@withContext State(clientInstalled = true)
            }

            // frida-tools is an sdist with no build-system table, so pip takes
            // its legacy path and needs setuptools and wheel. A minimal Debian
            // rootfs has neither — python3-pip no longer pulls them — and the
            // install then fails with a message that names neither package.
            // They are cheap and needed for any legacy sdist, so provide them.
            if (manager.isNotBlank() && manager != "none") {
                val buildPkgs = when (manager) {
                    "apt" -> listOf("python3-setuptools", "python3-wheel")
                    "apk" -> listOf("py3-setuptools", "py3-wheel")
                    "dnf" -> listOf("python3-setuptools", "python3-wheel")
                    "pacman" -> listOf("python-setuptools", "python-wheel")
                    else -> emptyList()
                }
                if (buildPkgs.isNotEmpty()) {
                    run(
                        ReaManager.packageInstallScript(manager, buildPkgs),
                        15 * 60_000L,
                    )
                }
            }
            run("python3 -m pip install --break-system-packages --root-user-action=ignore -q frida-tools 2>&1 | tail -8")
            if (present()) {
                return@withContext State(clientInstalled = true)
            }
            // The CLI is only a wrapper around the binding; install the binding
            // alone when the tools package cannot be built. `frida -H host:port`
            // is then reached through `python3 -m frida_tools` if that exists,
            // but the binary check above needs the console script, so a bare
            // binding is reported honestly as missing rather than pretended.
            run("python3 -m pip install --break-system-packages --root-user-action=ignore -q frida 2>&1 | tail -5")
            if (present()) {
                return@withContext State(clientInstalled = true)
            }

            // 2. The distro's own package, when pip cannot be made to work.
            //    Alpine and Fedora ship frida-tools; Debian does not, so this is
            //    a fallback rather than the first choice.
            val distroPackage = when (manager) {
                "apk" -> "frida-tools"
                "dnf" -> "frida-tools"
                "pacman" -> "frida"
                else -> ""
            }
            if (distroPackage.isNotBlank()) {
                log.append("trying $distroPackage from $manager\n")
                run(ReaManager.packageInstallScript(manager, listOf(distroPackage)), 15 * 60_000L)
                if (present()) {
                    return@withContext State(clientInstalled = true)
                }
            }

            // Name what was tried, and show pip's own error.
            //
            // "Last line" used to be the last line of the whole log — which the
            // probe itself appended ("CLIENT_MISSING"), so the real cause was
            // always overwritten by the word that meant "not found yet".
            val tried = buildList {
                add("pip")
                if (distroPackage.isNotBlank()) add(distroPackage)
            }.joinToString(" or ")
            val reason = log.toString().lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .filterNot { it == "CLIENT_MISSING" || it == "CLIENT_OK" }
                .lastOrNull { line ->
                    line.contains("ERROR", ignoreCase = true) ||
                        line.contains("error:", ignoreCase = true) ||
                        line.contains("No module", ignoreCase = true) ||
                        line.contains("Could not", ignoreCase = true)
                }
                .orEmpty()
            State(
                clientInstalled = false,
                error = "could not install the frida CLI in " + distroId +
                    " (tried: " + tried + ")." +
                    (if (reason.isBlank()) "" else " Reason: " + reason.take(200)),
            )
        }

    /** Everything the debugger tab needs to report. */
    suspend fun state(context: Context): State = withContext(Dispatchers.IO) {
        val target = ReaDistro.activeTarget(context)
        val client = target?.let { probeClient(context, it.id) } ?: false
        State(
            serverInstalled = isServerInstalled(),
            serverRunning = isServerRunning(),
            clientInstalled = client,
            version = VERSION,
            // A missing CLI is the usual reason an attach does nothing, so the
            // reason travels with the state instead of only into the log.
            error = if (target != null && !client) {
                "the frida CLI is not installed in " + target.label +
                    " — tap Install on the Frida row to add it"
            } else {
                ""
            },
        )
    }

    /** True when the CLI resolves inside the distro. */
    private suspend fun probeClient(context: Context, distroId: String): Boolean =
        runCatching {
            GuestExec.run(
                context.applicationContext,
                distroId,
                "command -v frida >/dev/null 2>&1 && echo YES || echo NO",
                timeoutMs = 30_000L,
            )
        }.getOrNull()?.output?.contains("YES") == true

    // ------------------------------------------------------------- downloads

    private fun download(url: String, target: File) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "altaris")
        }
        try {
            if (connection.responseCode != 200) {
                throw IllegalStateException("HTTP " + connection.responseCode + " for " + url)
            }
            connection.inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Decompress the server binary.
     *
     * Delegates to [XzArchive]: the previous version shelled out to `xz` and
     * then `python3`, neither of which exists on an Android host, so it failed
     * on every device and reported it as "cannot decompress".
     */
    private fun decompressXz(source: File, target: File): Boolean =
        XzArchive.decompress(source, target)

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
