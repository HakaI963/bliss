package com.ivarna.fluxlinux.core.control

import android.content.Context
import com.ivarna.fluxlinux.core.terminal.TermuxHostPaths
import java.io.File

data class DetectedAgent(
    val name: String,
    val detail: String,
    val kind: String,
    val packageName: String? = null,
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
        if (bin.isDirectory) {
            for (name in names) {
                val f = File(bin, name)
                if (f.isFile && f.canExecute()) {
                    found["cli:$name"] = DetectedAgent(
                        name = name,
                        detail = f.absolutePath,
                        kind = "cli",
                    )
                }
            }
        }
        return found.values.sortedWith(compareBy({ it.kind }, { it.name.lowercase() }))
    }
}
