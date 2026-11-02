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
                    return@withContext State(error = "cannot decompress frida-server")
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

    /** Stop frida-server. */
    suspend fun stopServer(): State = withContext(Dispatchers.IO) {
        runCatching {
            RootShell.captureResult("pkill -f frida-server 2>/dev/null; echo done", 30_000L)
        }
        State(serverInstalled = isServerInstalled(), serverRunning = false)
    }

    /**
     * Install the frida CLI inside the distro.
     *
     * The CLI is a Python package, so it works on any architecture and does not
     * need a per-distro binary.
     */
    suspend fun installClient(context: Context, distroId: String): State =
        withContext(Dispatchers.IO) {
            val script = "set +e\n" +
                "python3 -m pip install --break-system-packages -q frida-tools 2>&1 | tail -5\n" +
                "command -v frida >/dev/null 2>&1 && echo CLIENT_OK || echo CLIENT_MISSING\n"
            val result = runCatching {
                GuestExec.run(context.applicationContext, distroId, script, timeoutMs = 20 * 60_000L)
            }.getOrNull()
            val ok = result?.output?.contains("CLIENT_OK") == true
            State(
                clientInstalled = ok,
                error = if (ok) "" else result?.tail(4).orEmpty().ifBlank { "pip install failed" },
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
     * Decompress an .xz file.
     *
     * Android has no public XZ API, so this shells out to `xz` on the host when
     * it exists and falls back to Python's `lzma`, which is in every distro and
     * needs no package. Doing it here rather than in the guest keeps the
     * install independent of what the distro ships.
     */
    private fun decompressXz(source: File, target: File): Boolean {
        // 1. xz on the host, if the ROM has it.
        runCatching {
            val p = ProcessBuilder("xz", "-dc", source.absolutePath)
                .redirectErrorStream(false)
                .start()
            p.inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }
            p.waitFor()
        }.onFailure { Log.i(TAG, "host xz unavailable: ${it.message}") }
        if (target.isFile && target.length() > 1_000_000) return true

        // 2. Python's lzma, which is always present where the app can run it.
        return runCatching {
            val p = ProcessBuilder(
                "sh", "-c",
                "python3 -c 'import lzma,sys; " +
                    "open(sys.argv[2],\"wb\").write(lzma.open(sys.argv[1],\"rb\").read())' " +
                    quote(source.absolutePath) + " " + quote(target.absolutePath),
            ).start()
            p.waitFor()
            target.isFile && target.length() > 1_000_000
        }.getOrDefault(false)
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
