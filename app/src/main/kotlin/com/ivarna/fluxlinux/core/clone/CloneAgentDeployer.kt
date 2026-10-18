package com.ivarna.fluxlinux.core.clone

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Deploys the agent surface into the host prefix and every installed proot
 * distro:
 *   - `clone`            POSIX-sh CLI (status/install/launch/screenshot/tap/...)
 *   - `bliss-clone-mcp`  stdio MCP server for AI agents (OpenCode etc.)
 *
 * Proot sessions additionally bind `<filesDir>/clone` at `/bliss`, so the tools
 * reach the bridge at a stable path (`/bliss/bridge`) no matter the shell.
 * Idempotent + content-compared, safe to call on every app start.
 */
object CloneAgentDeployer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    fun ensureInstalled(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        scope.launch {
            try {
                val cli = readAsset(app, "agent/clone") ?: return@launch
                val mcp = readAsset(app, "agent/bliss-clone-mcp") ?: return@launch
                var writes = 0

                // 1) Host shell prefix (filesDir/usr/bin is in the host PATH).
                val hostBin = File(app.filesDir, "usr/bin")
                if (hostBin.isDirectory || hostBin.mkdirs()) {
                    if (writeIfChanged(File(hostBin, "clone"), cli)) writes++
                    if (writeIfChanged(File(hostBin, "bliss-clone-mcp"), mcp)) writes++
                }

                // 2) Proot rootfs (installed-rootfs layout + containers layout).
                val roots = LinkedHashSet<File>()
                File(app.filesDir, "usr/var/lib/proot-distro/installed-rootfs")
                    .listFiles()?.forEach { if (it.isDirectory) roots.add(it) }
                File(app.filesDir, "usr/var/lib/proot-distro/containers")
                    .listFiles()?.forEach { c ->
                        val r = File(c, "rootfs")
                        if (r.isDirectory) roots.add(r)
                    }

                for (root in roots) {
                    if (!root.isDirectory) continue
                    val bin = File(root, "usr/local/bin")
                    if (bin.isDirectory || bin.mkdirs()) {
                        if (writeIfChanged(File(bin, "clone"), cli)) writes++
                        if (writeIfChanged(File(bin, "bliss-clone-mcp"), mcp)) writes++
                        // Bind target for the bridge (proot --bind needs it to exist).
                        File(root, "bliss").mkdirs()
                        File(root, "bliss/bridge").mkdirs()
                    }
                }

                if (writes > 0) {
                    CloneEngine.log("Agent tools deployed ($writes file(s), ${roots.size} distro(s))")
                }
                deployToChroots(app, cli, mcp)
            } catch (t: Throwable) {
                CloneEngine.log("Agent deploy failed: ${t.message}")
            }
        }
    }

    /**
     * Best-effort chroot deployment: stage the tools where root can read them,
     * then let root copy them into every chroot rootfs and bind the bridge at
     * /bliss (chroot guests run as real root, so the CLI finds /bliss/bridge).
     */
    private fun deployToChroots(app: Context, cli: String, mcp: String) {
        if (!com.ivarna.fluxlinux.core.utils.RootUtils.isRootAvailable()) return
        try {
            val stage = File(app.filesDir, "clone/stage")
            stage.mkdirs()
            val stageCli = File(stage, "clone").apply {
                writeText(cli)
                setExecutable(true, false)
            }
            val stageMcp = File(stage, "bliss-clone-mcp").apply {
                writeText(mcp)
                setExecutable(true, false)
            }
            val cloneDir = File(app.filesDir, "clone").absolutePath
            val script = buildString {
                append("for d in /data/local/bliss/chroots/*; do\n")
                append("  [ -d \"\$d/usr\" ] || continue\n")
                append("  mkdir -p \"\$d/usr/local/bin\" \"\$d/bliss\" 2>/dev/null || continue\n")
                append("  cp -f '${stageCli.absolutePath}' \"\$d/usr/local/bin/clone\" 2>/dev/null && chmod 755 \"\$d/usr/local/bin/clone\"\n")
                append("  cp -f '${stageMcp.absolutePath}' \"\$d/usr/local/bin/bliss-clone-mcp\" 2>/dev/null && chmod 755 \"\$d/usr/local/bin/bliss-clone-mcp\"\n")
                append("  grep -q \" \$d/bliss \" /proc/mounts 2>/dev/null || mount --bind '$cloneDir' \"\$d/bliss\" 2>/dev/null || true\n")
                append("done\n")
                append("exit 0\n")
            }
            val r = com.ivarna.fluxlinux.core.utils.RootUtils.runRootCommand(script)
            CloneEngine.log(
                if (r.isSuccess) "Agent tools deployed to chroot distros"
                else "Chroot deploy note: ${r.error.take(80)}",
            )
        } catch (t: Throwable) {
            CloneEngine.log("Chroot deploy failed: ${t.message}")
        }
    }

    private fun readAsset(ctx: Context, path: String): String? =
        runCatching { ctx.assets.open(path).bufferedReader().use { it.readText() } }.getOrNull()

    /** Writes only when content changed; always keeps the exec bit. */
    private fun writeIfChanged(f: File, content: String): Boolean = try {
        if (f.isFile && f.readText() == content) {
            f.setExecutable(true, false)
            false
        } else {
            f.parentFile?.mkdirs()
            f.writeText(content)
            f.setExecutable(true, false)
            true
        }
    } catch (t: Throwable) {
        false
    }
}
