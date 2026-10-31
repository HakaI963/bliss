package com.altaris.linux.core.rea

import android.content.Context
import android.util.Log
import com.altaris.linux.core.control.ControlServer
import com.altaris.linux.core.install.DistroInstallProfile
import com.altaris.linux.core.root.RootShell
import com.altaris.linux.core.terminal.DistroFsCache
import com.altaris.linux.core.terminal.HostCommandBuilder
import com.altaris.linux.core.terminal.ShellCommandRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Install, run and publish REA inside an Altaris distro.
 *
 * Layout under the guest (root-owned, writable by proot's fake root):
 *   /opt/altaris/rea/node         Node 22 runtime when the distro ships an old one
 *   /opt/altaris/rea/npm          npm prefix holding `rea-agents`
 *   /opt/altaris/rea/jdk          Temurin 21 when the distro has no JDK 21+
 *   /opt/altaris/rea/scripts      helper installers + Ghidra scripts
 *   /opt/altaris/rea/ghidra       Ghidra headless release
 *   /opt/altaris/rea/rea.log      stdio server log
 *
 * The installers themselves are shipped as shell assets and executed with
 * arguments instead of being generated as Kotlin strings: a failed tool then
 * reports the package manager's own words, and every step can be re-run by hand
 * inside the distro for debugging.
 *
 * The stdio process is owned by [ReaStdio]; this object only orchestrates.
 */
object ReaManager {

    const val ROOT = "/opt/altaris/rea"
    const val NODE_BIN = ROOT + "/node/bin/node"
    const val REA_BIN = ROOT + "/npm/bin/rea"
    const val LOG = ROOT + "/rea.log"
    const val NODE_VERSION = "v22.23.4"
    const val REA_VERSION = "6.8.0"
    const val MCP_URL = "http://127.0.0.1:47831/mcp"

    /** Ghidra headless analyzer, installed on demand into the distro. */
    const val GHIDRA_ROOT = ROOT + "/ghidra"
    const val GHIDRA_SCRIPTS = ROOT + "/scripts"
    const val GHIDRA_OUT = ROOT + "/ghidra-out"
    const val GHIDRA_TAG = "Ghidra_11.4.3_build"
    const val GHIDRA_ASSET = "ghidra_11.4.3_PUBLIC_20251203.zip"

    /**
     * Launchers for tools installed from release archives (JADX, Apktool).
     *
     * This directory is prepended to PATH by [PROBE_PATH] and by every REA
     * run, so an archive install resolves exactly like a packaged one. It sits
     * beside the archives, never inside them: the archive installer replaces
     * its own directory on a reinstall.
     */
    const val ARCHIVE_BIN = ReaCatalog.ARCHIVE_ROOT + "/bin"

    /** PATH prefix used by probes and analysis so archive tools are found. */
    const val PROBE_PATH = ARCHIVE_BIN + ":" + ROOT + "/node/bin"

    /**
     * Ghidra 11.4.3 requires Java 21+ (application.java.min=21) and its
     * launcher refuses a JRE: LaunchSupport checks for a javac executable.
     * 12.2 moved the minimum to Java 25, which no phone distro packages yet,
     * so 11.4.3 is the newest release that runs on-device.
     */
    // Always resolves to the newest GA Temurin 21 arm64 (glibc). The arch
    // segment is "aarch64" — "arm64" 404s on the Adoptium API. Alpine keeps the
    // openjdk21-jdk package instead; Temurin has no musl build.
    const val JDK_TEMURIN =
        "https://api.adoptium.net/v3/binary/latest/21/ga/linux/aarch64/jdk/hotspot/normal/eclipse"

    /** Result of installing one catalog entry. */
    data class ToolOutcome(
        val id: String,
        val ok: Boolean,
        val detail: String = "",
    )

    data class Status(
        val distroId: String = "",
        val method: String = "",
        val installed: Boolean = false,
        val running: Boolean = false,
        val nodeVersion: String = "",
        val reaVersion: String = "",
        val packageManager: String = "",
        val toolCount: Int = 0,
        val available: Set<String> = emptySet(),
        val busy: String = "",
        val log: String = "",
        val error: String = "",
        val outcomes: List<ToolOutcome> = emptyList(),
        /**
         * Real progress through the tool list, not a fabricated ramp.
         *
         * The REA install is long (Node, then ~18 tools, then a 450 MB Ghidra),
         * so the dashboard needs to say *which* tool it is on and how many are
         * left instead of a bar that never moves.
         */
        val step: Int = 0,
        val stepTotal: Int = 0,
        val stepLabel: String = "",
        val stepDone: List<String> = emptyList(),
        val stepFailed: List<String> = emptyList(),
    ) {
        /** Fraction of the tool phase, 0..1. */
        val stepFraction: Float
            get() = if (stepTotal <= 0) 0f else step.toFloat() / stepTotal
    }

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    private val mutex = Mutex()

    @Volatile private var active: ReaStdio? = null
    @Volatile private var toolsCache: JSONArray? = null

    /** Installed distro ids, cheapest source the app already maintains. */
    /**
     * Distros the user may install REA into.
     *
     * The hidden analysis distro is filtered out: it already has REA, and
     * offering it in the picker would expose a distro the app must not list.
     */
    suspend fun installedDistros(ctx: Context): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            DistroFsCache.get(ctx.applicationContext).installed
                .filterNot { DistroInstallProfile.isHidden(it) }
                .sorted()
        }.getOrDefault(emptyList())
    }

    fun defaultDistro(ids: List<String>): String? =
        ids.firstOrNull { it == "debian" } ?: ids.firstOrNull()

    /**
     * True when REA's core (Node + the `rea` binary) is present in [distroId].
     *
     * A stat check, not a probe: the REA tab asks this on every recomposition
     * to decide between "not installed" and the live dashboard, so it must not
     * spawn a guest process.
     */
    fun isInstalledIn(ctx: Context, distroId: String): Boolean {
        if (distroId.isBlank()) return false
        val profile = DistroInstallProfile.forId(distroId) ?: return false
        // A chroot lives under /data/local, which this process cannot read:
        // stat'ing it is always a false negative. Ask root instead.
        if (profile.method == "chroot") {
            val path = profile.chrootPath ?: return false
            if (!RootShell.isRootAvailable()) {
                return com.altaris.linux.core.chroot.ChrootInfoStore
                    .cachedInstalled(ctx, distroId)
            }
            return runCatching {
                RootShell.captureResult(
                    "test -x " + quote(path + REA_BIN),
                    20_000L,
                ).exitCode == 0
            }.getOrDefault(false)
        }
        val rootfs: File = if (profile.prootName.isBlank()) return false
        else File(
            ctx.filesDir,
            "usr/var/lib/proot-distro/containers/${profile.prootName}/rootfs"
        )
        val rea = File(rootfs, REA_BIN.removePrefix("/"))
        return rea.isFile
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun cachedTools(): JSONArray? = toolsCache

    fun isReaTool(name: String): Boolean {
        val tools = toolsCache ?: return false
        for (i in 0 until tools.length()) {
            if (tools.optJSONObject(i)?.optString("name") == name) return true
        }
        return false
    }

    // ------------------------------------------------------------- probe

    /** Every probe binary the catalog can check, plus the ones agents ask for. */
    private fun probeNames(): List<String> {
        val names = LinkedHashSet<String>()
        for (dep in ReaCatalog.DEPENDENCIES) names.addAll(ReaCatalog.probesFor(dep))
        names.addAll(listOf("java", "node", "npm", "apktool", "adb", "ghidra"))
        return names.toList()
    }

    private fun probeScript(): String = listOf(
        // Archive-installed tools live outside the distro PATH; put their bin
        // first so `command -v jadx` finds them exactly like a packaged one.
        "PATH=" + PROBE_PATH + ":\$PATH; export PATH",
        "echo arch=\$(uname -m)",
        "if [ -x " + REA_BIN + " ]; then echo rea=\$(PATH=" + ROOT + "/node/bin:\$PATH " + REA_BIN + " --version 2>/dev/null | head -n1); else echo rea=; fi",
        "if [ -x " + NODE_BIN + " ]; then echo node=\$(" + NODE_BIN + " -v 2>/dev/null); " +
            "elif command -v node >/dev/null 2>&1; then echo node=\$(node -v 2>/dev/null); else echo node=; fi",
        "if command -v apt-get >/dev/null 2>&1; then echo pm=apt; " +
            "elif command -v apk >/dev/null 2>&1; then echo pm=apk; " +
            "elif command -v dnf >/dev/null 2>&1; then echo pm=dnf; " +
            "elif command -v pacman >/dev/null 2>&1; then echo pm=pacman; else echo pm=none; fi",
        "for b in " + probeNames().joinToString(" ") + "; do " +
            "if command -v \$b >/dev/null 2>&1; then echo bin:\$b=1; else echo bin:\$b=0; fi; done",
        "if [ -x " + GHIDRA_ROOT + "/support/analyzeHeadless ]; then echo bin:ghidra=1; else echo bin:ghidra=0; fi",
        // No prebuilt ARM64 decompiler ships with Ghidra; it is compiled on
        // device during install. Without it only disassembly works.
        "if [ -x " + GHIDRA_ROOT + "/Ghidra/Features/Decompiler/os/linux_arm_64/decompile ]; then " +
            "echo bin:ghidra-decompiler=1; else echo bin:ghidra-decompiler=0; fi",
        // The Temurin JDK fallback lives outside PATH, so command -v java would
        // miss it and the UI would show Java as missing while Ghidra works.
        "if [ -x " + ROOT + "/jdk/bin/java ]; then echo bin:java=1; fi",
    ).joinToString("\n")

    /** Map raw probe binaries to the catalog ids the UI installs. */
    private fun availableIds(bins: Set<String>): Set<String> {
        val ids = LinkedHashSet<String>()
        for (dep in ReaCatalog.DEPENDENCIES) {
            if (dep.kind == "license") continue
            val present = if (dep.kind == "ghidra") {
                bins.contains("ghidra")
            } else {
                ReaCatalog.probesFor(dep).any { bins.contains(it) }
            }
            if (present) ids.add(dep.id)
        }
        return ids
    }

    suspend fun probe(ctx: Context, distroId: String): Status = withContext(Dispatchers.IO) {
        val result = GuestExec.run(ctx, distroId, probeScript(), timeoutMs = 90_000L)
        val values = HashMap<String, String>()
        val bins = HashSet<String>()
        for (line in result.output.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("bin:")) {
                val name = trimmed.removePrefix("bin:").substringBefore('=')
                if (trimmed.endsWith("=1")) bins.add(name)
            } else if (trimmed.contains('=')) {
                values[trimmed.substringBefore('=')] = trimmed.substringAfter('=').trim()
            }
        }
        val reaVersion = values["rea"].orEmpty()
        val current = _status.value
        val next = current.copy(
            distroId = distroId,
            method = GuestExec.methodOf(distroId),
            installed = reaVersion.isNotBlank(),
            running = active?.isAlive == true,
            nodeVersion = values["node"].orEmpty(),
            reaVersion = reaVersion,
            packageManager = values["pm"].orEmpty(),
            available = bins + availableIds(bins),
            toolCount = toolsCache?.length() ?: 0,
            error = if (result.exit == 0) "" else (current.error.ifBlank { result.tail(3) }),
        )
        _status.value = next
        next
    }

    /** Package manager as seen inside the guest; empty when none is supported. */
    suspend fun detectPackageManager(ctx: Context, distroId: String): String = withContext(Dispatchers.IO) {
        val result = GuestExec.run(
            ctx,
            distroId,
            "if command -v apt-get >/dev/null 2>&1; then echo apt; " +
                "elif command -v apk >/dev/null 2>&1; then echo apk; " +
                "elif command -v dnf >/dev/null 2>&1; then echo dnf; " +
                "elif command -v pacman >/dev/null 2>&1; then echo pacman; else echo none; fi",
            timeoutMs = 30_000L,
        )
        result.output.lineSequence().map { it.trim() }
            .firstOrNull { it in ReaCatalog.MANAGERS || it == "none" }
            .orEmpty()
    }

    // ------------------------------------------------------------- install

    /**
     * Installs the REA core (once per distro) and then [dependencies].
     *
     * A failing core install no longer stops the tool installs and vice versa:
     * each step is reported on its own in [Status.outcomes], because on a phone
     * distro one missing package must not hide the nine that would have worked.
     */
    suspend fun install(
        ctx: Context,
        distroId: String,
        dependencies: List<String> = emptyList(),
        onLog: (String) -> Unit = {},
    ): Status = withContext(Dispatchers.IO) {
        mutex.withLock {
            val app = ctx.applicationContext
            if (distroId.isBlank()) {
                update { it.copy(busy = "", error = "Install a distro first") }
                return@withLock _status.value
            }
            // Must be read before the status copy below: that copy rewrites
            // distroId, which would make a fresh distro look installed.
            val alreadyInstalled = _status.value.distroId == distroId && _status.value.installed
            update {
                it.copy(
                    distroId = distroId,
                    method = GuestExec.methodOf(distroId),
                    busy = "Preparing REA",
                    log = "",
                    error = "",
                    outcomes = emptyList(),
                )
            }
            val logBuffer = StringBuilder()
            fun line(text: String) {
                synchronized(logBuffer) {
                    logBuffer.append(text).append('\n')
                    if (logBuffer.length > 24_000) logBuffer.delete(0, logBuffer.length - 20_000)
                }
                onLog(text)
                update { it.copy(log = synchronized(logBuffer) { logBuffer.toString() }) }
            }

            val outcomes = ArrayList<ToolOutcome>()

            update { it.copy(busy = "Staging helpers") }
            if (!stageHelpers(app, distroId) { line(it) }) {
                line("warning: helper scripts could not be staged; installs may fail")
            }

            // The package manager is detected here, not reused from a previous
            // distro: installing on Alpine with an "apt" left over from Debian
            // was silently skipping every tool.
            val manager = detectPackageManager(app, distroId)
            update { it.copy(packageManager = manager) }
            line("distro " + distroId + " · package manager: " + manager.ifBlank { "unknown" })

            var coreError = ""
            if (alreadyInstalled) {
                line("REA core already installed in " + distroId)
            } else {
                update { it.copy(busy = "Installing REA core") }
                line("installing Node " + NODE_VERSION + " + rea-agents " + REA_VERSION)
                val core = GuestExec.run(app, distroId, coreScript(), timeoutMs = 45 * 60_000L) { line(it) }
                if (core.ok) {
                    line("REA core ready")
                } else {
                    coreError = core.tail(8).ifBlank { "REA core install failed" }
                    line("ERROR: REA core install failed (tools below are still attempted)")
                }
            }

            val requested = dependencies.mapNotNull { ReaCatalog.byId(it) }
            if (requested.isNotEmpty()) {
                update {
                    it.copy(
                        busy = "Installing tools",
                        step = 0,
                        stepTotal = requested.size,
                        stepLabel = requested.first().label,
                        stepDone = emptyList(),
                        stepFailed = emptyList(),
                    )
                }
            }
            for ((index, dep) in requested.withIndex()) {
                update {
                    it.copy(
                        busy = "Installing " + dep.label + " (" + (index + 1) + "/" + requested.size + ")",
                        step = index,
                        stepTotal = requested.size,
                        stepLabel = dep.label,
                    )
                }
                val outcome = installDependency(app, distroId, dep, manager) { line(it) }
                outcomes.add(outcome)
                update {
                    it.copy(
                        step = index + 1,
                        stepDone = if (outcome.ok) it.stepDone + dep.label else it.stepDone,
                        stepFailed = if (outcome.ok) it.stepFailed else it.stepFailed + dep.label,
                    )
                }
                line(
                    (if (outcome.ok) "ok: " else "FAILED: ") + dep.label +
                        if (outcome.detail.isBlank()) "" else " — " + outcome.detail
                )
            }

            update {
                it.copy(
                    busy = "",
                    outcomes = outcomes,
                    step = outcomes.size,
                    stepLabel = "",
                )
            }
            probe(app, distroId)

            val failure = coreError.ifBlank {
                outcomes.firstOrNull { !it.ok }?.let { "Failed: " + ReaCatalog.byId(it.id)?.label.orEmpty().ifBlank { it.id } }
                    .orEmpty()
            }
            if (failure.isNotBlank()) update { it.copy(error = failure) }
            _status.value
        }
    }

    private suspend fun installDependency(
        app: Context,
        distroId: String,
        dep: ReaDependency,
        manager: String,
        line: (String) -> Unit,
    ): ToolOutcome = when (dep.kind) {
        "license" -> ToolOutcome(dep.id, false, dep.note.ifBlank { "license required" })
        "ghidra" -> {
            line("installing Ghidra (headless analyzer) — this is a ~450 MB download")
            val args = listOf(
                GHIDRA_ROOT,
                GHIDRA_TAG,
                GHIDRA_ASSET,
                JDK_TEMURIN,
                GHIDRA_SCRIPTS + "/ghidra-decompiler-build.sh",
            )
            val script = "sh " + shellPath("ghidra-install.sh") + " " + shellPath(ROOT) + " " +
                args.joinToString(" ") { GuestExec.shellQuote(it) }
            val result = GuestExec.run(app, distroId, script, timeoutMs = 90 * 60_000L) { line(it) }
            ToolOutcome(
                dep.id,
                result.ok,
                if (result.ok) "installed" else result.tail(4).ifBlank { "Ghidra install failed" },
            )
        }
        else -> {
            val batches = ReaCatalog.variantsFor(dep, manager)
            when {
                manager.isBlank() || manager == "none" ->
                    ToolOutcome(dep.id, false, "no supported package manager in this distro")
                // No package on this distro: install the release archive. Fedora
                // ships neither jadx nor apktool, so "try another dnf name" was
                // never going to work there.
                batches.isEmpty() && dep.archive != null ->
                    installFromArchive(app, distroId, dep, manager, dep.archive, line)
                batches.isEmpty() ->
                    ToolOutcome(
                        dep.id,
                        false,
                        "not packaged for " + manager + " and has no release archive; install it manually",
                    )
                else -> {
                    line(
                        "installing " + dep.label + " via " + manager + ": " +
                            batches.joinToString(" | ") { it.joinToString(" ") }
                    )
                    val probes = ReaCatalog.probesFor(dep).joinToString(",")
                    val script = "sh " + shellPath("install-tool.sh") + " " +
                        GuestExec.shellQuote(manager) + " " + GuestExec.shellQuote(probes) + " " +
                        batches.joinToString(" ") { GuestExec.shellQuote(it.joinToString(",")) }
                    val result = GuestExec.run(app, distroId, script, timeoutMs = 30 * 60_000L) { line(it) }
                    val detail = result.output.lines()
                        .map { it.trim() }
                        .lastOrNull { it.startsWith("ok:") || it.startsWith("ERROR") }
                        .orEmpty()
                    ToolOutcome(
                        dep.id,
                        result.ok,
                        if (result.ok) detail.removePrefix("ok:").trim()
                        else detail.removePrefix("ERROR:").trim().ifBlank { "install failed" },
                    )
                }
            }
        }
    }

    /**
     * Install [dep] from its release archive because no package manager on the
     * distro ships it.
     *
     * A bare jar (Apktool) has no launcher of its own, so the wrapper is written
     * here — into `<archive>/../bin`, which [PROBE_PATH] puts on PATH, and never
     * into the archive directory the installer may replace.
     */
    private suspend fun installFromArchive(
        app: Context,
        distroId: String,
        dep: ReaDependency,
        manager: String,
        archive: ReaArchive,
        line: (String) -> Unit,
    ): ToolOutcome {
        line(
            "not packaged for " + manager + " — installing " + dep.label +
                " from the official release (" + archive.fileName + ")"
        )
        val probes = ReaCatalog.probesFor(dep).joinToString(",")
        val launcher = archive.launcher

        // Stage the wrapper before the installer runs: the installer only
        // validates it, so a failure is reported before a 70 MB download.
        if (launcher.isBlank()) {
            val wrapperName = ReaCatalog.probesFor(dep).firstOrNull().orEmpty()
            if (wrapperName.isBlank()) {
                return ToolOutcome(dep.id, false, "no probe binary name for the wrapper")
            }
            val body = archive.wrapper.trim().replace("\r\n", "\n")
            if (body.isBlank()) {
                return ToolOutcome(dep.id, false, "archive has no launcher and no wrapper")
            }
            val stage = buildString {
                append("set +e\n")
                append("mkdir -p ").append(ARCHIVE_BIN).append("\n")
                append("cat > ").append(ARCHIVE_BIN).append('/').append(wrapperName)
                append(" <<'ALTARIS_WRAPPER_EOF'\n")
                append(body).append('\n')
                append("ALTARIS_WRAPPER_EOF\n")
                append("chmod +x ").append(ARCHIVE_BIN).append('/').append(wrapperName).append('\n')
            }
            val staged = GuestExec.run(app, distroId, stage, timeoutMs = 60_000L) { line(it) }
            if (!staged.ok) {
                return ToolOutcome(dep.id, false, staged.tail(3).ifBlank { "cannot write the launcher" })
            }
        }

        val script = "sh " + shellPath("install-archive.sh") + " " +
            GuestExec.shellQuote(archive.url) + " " +
            GuestExec.shellQuote(archive.fileName) + " " +
            GuestExec.shellQuote(archive.dir) + " " +
            GuestExec.shellQuote(probes) + " " +
            GuestExec.shellQuote(launcher)
        val result = GuestExec.run(app, distroId, script, timeoutMs = 30 * 60_000L) { line(it) }
        val detail = result.output.lines()
            .map { it.trim() }
            .lastOrNull { it.startsWith("ok:") || it.startsWith("ERROR") }
            .orEmpty()
        return ToolOutcome(
            dep.id,
            result.ok,
            if (result.ok) detail.removePrefix("ok:").trim()
            else detail.removePrefix("ERROR:").trim().ifBlank { "archive install failed" },
        )
    }

    /** Absolute in-guest path of a staged REA helper script. */
    private fun shellPath(name: String): String = GuestExec.shellQuote(GHIDRA_SCRIPTS + "/" + name)

    private fun coreScript(): String = "sh " + shellPath("rea-core-install.sh") + " " +
        GuestExec.shellQuote(ROOT) + " " + GuestExec.shellQuote(NODE_VERSION) + " " +
        GuestExec.shellQuote(REA_VERSION)

    // ------------------------------------------------------------- staging

    private fun readAsset(ctx: Context, name: String): String =
        ctx.applicationContext.assets.open(name).bufferedReader().use { it.readText() }

    /** Scripts that are copied verbatim into the guest. */
    private val VERBATIM_SCRIPTS = listOf(
        "rea-core-install.sh",
        "install-tool.sh",
        "install-archive.sh",
        "ghidra-install.sh",
        "ghidra-decompiler-build.sh",
        "DumpInfo.java",
    )

    /**
     * Write the helper scripts into `$ROOT/scripts` inside the guest.
     *
     * Everything lives under the REA root (never inside the Ghidra tree, which
     * the installer deletes and re-creates) and the heredocs are quoted so the
     * guest shell expands the variables when the script runs, not now.
     */
    private suspend fun stageHelpers(
        ctx: Context,
        distroId: String,
        line: (String) -> Unit = {},
    ): Boolean {
        val app = ctx.applicationContext
        val body = StringBuilder()
        body.append("set +e\n")
        body.append("mkdir -p ").append(GHIDRA_SCRIPTS).append(' ').append(GHIDRA_OUT).append("\n")
        for (name in VERBATIM_SCRIPTS) {
            val content = runCatching { readAsset(app, "scripts/rea/" + name) }.getOrNull()
            if (content == null) {
                line("warning: asset scripts/rea/" + name + " is missing")
                continue
            }
            body.append("cat > ").append(GHIDRA_SCRIPTS).append('/').append(name)
                .append(" <<'ALTARIS_SCRIPT_EOF'\n")
            body.append(content.trimEnd()).append('\n')
            body.append("ALTARIS_SCRIPT_EOF\n")
        }
        // The analyzer wrapper has its roots baked in: it is executed directly,
        // not sourced, so nothing in the guest can shadow the paths.
        val wrapper = runCatching { readAsset(app, "scripts/rea/ghidra-analyze.sh") }.getOrNull()
        if (wrapper != null) {
            body.append("cat > ").append(GHIDRA_SCRIPTS).append("/ghidra-analyze.sh <<'ALTARIS_SCRIPT_EOF'\n")
            body.append(
                wrapper
                    .replace("__GHIDRA_ROOT__", GHIDRA_ROOT)
                    .replace("__REA_ROOT__", ROOT)
                    .trimEnd()
            ).append('\n')
            body.append("ALTARIS_SCRIPT_EOF\n")
        }
        body.append("chmod +x ").append(GHIDRA_SCRIPTS).append("/*.sh 2>/dev/null\n")
        body.append("ls ").append(GHIDRA_SCRIPTS).append("\n")
        val result = GuestExec.run(app, distroId, body.toString(), timeoutMs = 120_000L)
        if (!result.ok) line("stage: " + result.tail(3))
        return result.ok
    }

    // ------------------------------------------------------------- ghidra

    /**
     * Headless Ghidra analysis of [target] (a path inside the guest). Imports the
     * file, runs the default analyzers, then a GhidraScript dumps every function
     * (name + entry address) to a file the wrapper prints. Works on ARM64; the
     * GUI is never used.
     */
    suspend fun analyzeGhidra(
        ctx: Context,
        distroId: String,
        target: String,
        max: Int = 300,
    ): JSONObject = withContext(Dispatchers.IO) {
        val present = GuestExec.run(
            ctx,
            distroId,
            "if [ -x \"" + GHIDRA_ROOT + "/support/analyzeHeadless\" ]; then echo yes; else echo no; fi",
            timeoutMs = 30_000L,
        )
        if (!present.output.contains("yes")) {
            return@withContext JSONObject()
                .put("ok", false)
                .put("error", "Ghidra is not installed. Install it from Tools > Reverse engineering first.")
        }
        stageHelpers(ctx, distroId)
        // JAVA_HOME and headless flags are resolved inside the wrapper, so the
        // guest's own stale JAVA_HOME cannot point Ghidra at a JRE.
        val script = "sh " + shellPath("ghidra-analyze.sh") + " " +
            GuestExec.shellQuote(target) + " " + max
        val result = GuestExec.run(ctx, distroId, script, timeoutMs = 45 * 60_000L)
        JSONObject()
            .put("ok", result.ok)
            .put("target", target)
            .put("exit", result.exit)
            .put("output", result.output.takeLast(8000))
            .put("error", if (result.ok) "" else result.tail(6))
    }

    // ------------------------------------------------------------- server

    suspend fun start(ctx: Context, distroId: String): Status = withContext(Dispatchers.IO) {
        mutex.withLock {
            update { it.copy(distroId = distroId, method = GuestExec.methodOf(distroId), busy = "Starting REA", error = "") }
            active?.stop()
            active = null
            toolsCache = null
            // Agents in the distro reach REA through this endpoint, so it has
            // to be listening before the catalog is published.
            runCatching { ControlServer.start(ctx.applicationContext) }
            // A previous app process may have died without reaping its child.
            // Kill any stale server before opening the new stdio pipe.
            runCatching {
                GuestExec.run(
                    ctx.applicationContext,
                    distroId,
                    "pkill -f 'rea mcp' >/dev/null 2>&1 || true",
                    timeoutMs = 20_000L,
                )
            }
            val client = ReaStdio(ctx.applicationContext, distroId)
            try {
                client.start()
                val tools = client.listTools()
                active = client
                toolsCache = tools
                update {
                    it.copy(
                        busy = "",
                        running = true,
                        toolCount = tools.length(),
                        installed = true,
                        error = "",
                    )
                }
            } catch (t: Throwable) {
                runCatching { client.stop() }
                Log.w(TAG, "REA start failed", t)
                update { it.copy(busy = "", running = false, error = t.message ?: t.javaClass.simpleName) }
            }
            _status.value
        }
    }

    suspend fun stop(): Status = withContext(Dispatchers.IO) {
        mutex.withLock {
            active?.stop()
            active = null
            toolsCache = null
            update { it.copy(running = false, toolCount = 0, busy = "") }
            _status.value
        }
    }

    /**
     * Tool catalog for [distroId], starting REA first when it is not running.
     *
     * The REA screen needs the catalog to show what the environment offers;
     * without this it would have to know the REA lifecycle, which belongs here.
     */
    suspend fun probeTools(ctx: Context, distroId: String): JSONArray = withContext(Dispatchers.IO) {
        if (active?.isAlive != true) {
            runCatching { start(ctx, distroId) }
        }
        toolsCache ?: JSONArray()
    }

    suspend fun call(name: String, arguments: JSONObject, timeoutMs: Long = 600_000L): JSONObject =
        withContext(Dispatchers.IO) {
            val client = active ?: throw IllegalStateException(
                "REA is not running. Call altaris_rea_start first (Tools > Reverse engineering)."
            )
            client.callTool(name, arguments, timeoutMs)
        }

    // ------------------------------------------------------------- agents

    suspend fun registerAgents(ctx: Context, distroId: String): Status = withContext(Dispatchers.IO) {
        mutex.withLock {
            update { it.copy(busy = "Registering MCP", error = "") }
            val script = agentScript()
            val result = GuestExec.run(ctx.applicationContext, distroId, script, timeoutMs = 120_000L)
            update {
                it.copy(
                    busy = "",
                    error = if (result.ok) "" else result.tail(6),
                    log = if (result.ok) result.output.takeLast(2_000) else it.log,
                )
            }
            _status.value
        }
    }

    private fun agentScript(): String = listOf(
        "set -e",
        "BASE=" + ROOT,
        "mkdir -p \"\$BASE\"",
        "cat > \"\$BASE/agent-config.mjs\" <<'ALTARIS_REA_EOF'",
        AGENT_JS.trim(),
        "ALTARIS_REA_EOF",
        "NODE=" + NODE_BIN,
        "if [ ! -x \"\$NODE\" ]; then NODE=\$(command -v node); fi",
        "[ -n \"\$NODE\" ] || { echo 'ERROR: no node runtime; install REA first'; exit 5; }",
        "\"\$NODE\" \"\$BASE/agent-config.mjs\" " + MCP_URL,
    ).joinToString("\n")

    // ------------------------------------------------------------- clones

    /** Copy every APK of [packageName] to shared storage so the guest can read it. */
    suspend fun exportCloneApk(ctx: Context, packageName: String): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val safe = packageName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val script = listOf(
                "dir=/sdcard/Download/Altaris/rea",
                "mkdir -p \"\$dir\" || exit 3",
                "i=0",
                "for p in \$(pm path " + GuestExec.shellQuote(packageName) + " | sed 's/^package://'); do",
                "  i=\$((i+1))",
                "  out=\"\$dir/" + safe + "-\$i.apk\"",
                "  cp -f \"\$p\" \"\$out\" || exit 4",
                "  echo \"\$out\"",
                "done",
                "[ \"\$i\" -gt 0 ] || { echo 'package not installed'; exit 5; }",
            ).joinToString("\n")
            val app = ctx.applicationContext
            val env = HostCommandBuilder.envMap(app, includeTerm = false)
            val (exit, out) = ShellCommandRunner.runCaptureExit(
                app,
                arrayOf("/system/bin/sh", "-c", script),
                env,
            )
            if (exit == 0) return@withContext exit to out
            val su = com.altaris.linux.core.root.RootShell.captureResult(script, 60_000L)
            su.exitCode to su.stdout
        }

    // ------------------------------------------------------------- helpers

    private fun update(block: (Status) -> Status) {
        _status.value = block(_status.value)
    }

    private const val TAG = "AltarisRea"

    private val AGENT_JS = """
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';

const url = process.argv[2] || 'http://127.0.0.1:47831/mcp';
const homes = new Set();
try { homes.add(os.homedir()); } catch (e) {}
try {
  if (process.getuid && process.getuid() === 0) {
    homes.add('/root');
    for (const name of fs.readdirSync('/home')) homes.add(path.join('/home', name));
  }
} catch (e) {}

function readJson(file) {
  try {
    const value = JSON.parse(fs.readFileSync(file, 'utf8'));
    return value && typeof value === 'object' && !Array.isArray(value) ? value : {};
  } catch (e) { return {}; }
}
function writeJson(file, obj) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(obj, null, 2) + '\n');
}

const written = [];
for (const home of homes) {
  const targets = [];
  if (fs.existsSync(path.join(home, '.claude')) || fs.existsSync(path.join(home, '.claude.json'))) {
    targets.push(['.claude.json', (o) => { o.mcpServers = o.mcpServers || {}; o.mcpServers['altaris'] = { type: 'http', url: url }; }]);
  }
  if (fs.existsSync(path.join(home, '.config/opencode')) || fs.existsSync(path.join(home, '.opencode'))) {
    targets.push(['.config/opencode/opencode.json', (o) => { o.mcp = o.mcp || {}; o.mcp['altaris'] = { type: 'remote', url: url, enabled: true }; }]);
  }
  if (fs.existsSync(path.join(home, '.gemini'))) {
    targets.push(['.gemini/settings.json', (o) => { o.mcpServers = o.mcpServers || {}; o.mcpServers['altaris'] = { httpUrl: url }; }]);
  }
  if (fs.existsSync(path.join(home, '.cursor'))) {
    targets.push(['.cursor/mcp.json', (o) => { o.mcpServers = o.mcpServers || {}; o.mcpServers['altaris'] = { url: url }; }]);
  }
  if (fs.existsSync(path.join(home, '.kimi-code'))) {
    targets.push(['.kimi-code/mcp.json', (o) => { o.mcpServers = o.mcpServers || {}; o.mcpServers['altaris'] = { url: url }; }]);
  }
  for (const [rel, apply] of targets) {
    const file = path.join(home, rel);
    const obj = readJson(file);
    apply(obj);
    writeJson(file, obj);
    written.push(file);
  }
  const codexDir = path.join(home, '.codex');
  if (fs.existsSync(codexDir)) {
    const file = path.join(codexDir, 'config.toml');
    let text = '';
    try { text = fs.readFileSync(file, 'utf8'); } catch (e) {}
    if (text.indexOf('[mcp_servers.altaris]') < 0) {
      fs.appendFileSync(file, '\n[mcp_servers.altaris]\nurl = "' + url + '"\n');
      written.push(file);
    }
  }
}
console.log(JSON.stringify({ ok: true, written: written }, null, 2));
""".trimIndent()
}
