package com.ivarna.fluxlinux.core.control

import android.content.Context
import com.ivarna.fluxlinux.core.chroot.ChrootDetection
import com.ivarna.fluxlinux.core.chroot.ChrootInfoStore
import com.ivarna.fluxlinux.core.data.DistroRepository
import com.ivarna.fluxlinux.core.install.DistroInstallProfile
import com.ivarna.fluxlinux.core.terminal.TermuxHostPaths
import java.io.File

data class DetectedAgent(
    val name: String,
    val detail: String,
    val kind: String,
    val packageName: String? = null,
)

data class DistroAgentReport(
    val id: String,
    val name: String,
    val method: String,
    val agents: List<DetectedAgent>,
)

/**
 * Best-effort discovery for the agent runtimes people actually put on-device.
 * It scans launchable/inalled Android packages by package/label and the host
 * prefix binaries (opencode, claude, codex, pi, hermes, alpha, aider, gemini).
 */
object AgentDetector {
    private val names = listOf(
        "opencode", "claude", "codex", "hermes", "alpha", "pi", "aider",
        "cursor", "gemini", "copilot", "chatgpt",
    )

    @Suppress("DEPRECATION")
    fun scan(context: Context): List<DetectedAgent> {
        val found = LinkedHashMap<String, DetectedAgent>()
        val pm = context.packageManager
        runCatching {
            for (info in pm.getInstalledApplications(0)) {
                val pkg = info.packageName
                if (pkg == context.packageName || pkg.startsWith("com.nopiece.hclone.")) continue
                val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(pkg)
                val hay = "$pkg $label".lowercase()
                val hit = names.firstOrNull { name ->
                    when (name) {
                        "pi" -> label.equals("pi", true) || pkg.startsWith("ai.pi") ||
                            pkg.contains(".pi.") || pkg.contains("pi.ai")
                        else -> hay.contains(name)
                    }
                } ?: continue
                found["app:$pkg"] = DetectedAgent(
                    name = label,
                    detail = pkg,
                    kind = "app",
                    packageName = pkg,
                )
                if (hit.isNotBlank() && !label.lowercase().contains(hit)) {
                    // keep the matched runtime name visible when the app label is branded
                    found["app:$pkg"] = DetectedAgent(
                        name = "$label · $hit",
                        detail = pkg,
                        kind = "app",
                        packageName = pkg,
                    )
                }
            }
        }
        val bin = File(TermuxHostPaths.BIN)
        if (bin.isDirectory) scanBinDir(bin, found, "host cli")
        val hostHome = File(TermuxHostPaths.HOME)
        scanBinDir(File(hostHome, ".local/bin"), found, "host cli")
        scanBinDir(File(hostHome, ".opencode/bin"), found, "host cli")
        scanBinDir(File(hostHome, ".npm-global/bin"), found, "host cli")
        scanBinDir(File(hostHome, ".bun/bin"), found, "host cli")
        scanBinDir(File(hostHome, ".cargo/bin"), found, "host cli")
        val containers = File(context.filesDir, "usr/var/lib/proot-distro/containers")
        containers.listFiles()?.forEach { container ->
            val rootfs = File(container, "rootfs")
            if (!rootfs.isDirectory) return@forEach
            scanBinDir(File(rootfs, "usr/local/bin"), found, "guest cli")
            scanBinDir(File(rootfs, "usr/bin"), found, "guest cli")
            scanBinDir(File(rootfs, "root/.local/bin"), found, "guest cli")
            scanBinDir(File(rootfs, "root/.opencode/bin"), found, "guest cli")
            File(rootfs, "home").listFiles()?.forEach { home ->
                scanBinDir(File(home, ".local/bin"), found, "guest cli")
                scanBinDir(File(home, ".opencode/bin"), found, "guest cli")
            }
        }
        return found.values.sortedWith(compareBy({ it.kind }, { it.name.lowercase() }))
    }

    /**
     * Installed distros only, with the agents that exist in each rootfs.
     * This is the matrix shown in Tools: distro -> agent binaries.
     */
    fun scanDistros(context: Context): List<DistroAgentReport> {
        val reports = ArrayList<DistroAgentReport>()
        for (distro in DistroRepository.supportedDistros) {
            val profile = DistroInstallProfile.forId(distro.id) ?: continue
            val rootfs: File? = when (profile.method) {
                "chroot" -> profile.chrootPath?.let { File(it) }
                else -> if (profile.prootName.isBlank()) null
                else File(
                    context.filesDir,
                    "usr/var/lib/proot-distro/containers/${profile.prootName}/rootfs"
                )
            }
            val installed = when (profile.method) {
                "chroot" -> {
                    val path = profile.chrootPath
                    (path != null && ChrootDetection.isInstalled(path)) ||
                        ChrootInfoStore.cachedInstalled(context, profile.distroId)
                }
                else -> rootfs?.isDirectory == true
            }
            if (!installed || rootfs == null) continue
            val found = LinkedHashMap<String, DetectedAgent>()
            val dirs = listOf(
                "usr/local/bin", "usr/bin", "root/.local/bin", "root/.npm-global/bin",
                "root/.opencode/bin", "root/.claude/local", "root/.bun/bin",
                "root/.cargo/bin", "root/.local/share/pnpm",
            )
            for (dir in dirs) scanBinDir(File(rootfs, dir), found, "guest cli")
            File(rootfs, "home").listFiles()?.forEach { home ->
                scanBinDir(File(home, ".local/bin"), found, "guest cli")
                scanBinDir(File(home, ".npm-global/bin"), found, "guest cli")
                scanBinDir(File(home, ".opencode/bin"), found, "guest cli")
                scanBinDir(File(home, ".claude/local"), found, "guest cli")
                scanBinDir(File(home, ".bun/bin"), found, "guest cli")
                scanBinDir(File(home, ".cargo/bin"), found, "guest cli")
                scanBinDir(File(home, ".local/share/pnpm"), found, "guest cli")
            }
            reports.add(
                DistroAgentReport(
                    id = profile.distroId,
                    name = profile.displayName.ifBlank { distro.name },
                    method = profile.method,
                    agents = found.values.distinctBy { it.name }.sortedBy { it.name.lowercase() },
                )
            )
        }
        return reports.sortedBy { it.name.lowercase() }
    }

    private fun scanBinDir(dir: File, found: MutableMap<String, DetectedAgent>, kind: String) {
        if (!dir.isDirectory) return
        for (name in names) {
            val file = File(dir, name)
            if (file.isFile && file.canExecute()) {
                found["cli:$kind:${file.absolutePath}"] = DetectedAgent(
                    name = name,
                    detail = file.absolutePath,
                    kind = kind,
                )
            }
        }
    }
}
