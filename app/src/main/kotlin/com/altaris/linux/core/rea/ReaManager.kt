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

    /**
     * Bumped whenever the agent-facing configuration changes.
     *
     * Everything Altaris writes into the guest — the MCP registration, the
     * skill, the guide, the Ghidra and jadx pins — used to be applied once at
     * install time. A user who already had REA therefore never received any of
     * it: the new tool, the fixed config shape, the corrected guide. This is
     * the version of that configuration. [ensureAgentConfig] compares it with
     * what is in the guest and re-applies everything when it differs, so an
     * upgrade is enough and nobody has to reinstall.
     *
     * Change this whenever INSTRUCTIONS, ReaKnowledge, the MCP shape, the
     * Ghidra pin or the jadx pin changes.
     */
    const val CONFIG_VERSION = "2026.11.04-1"

    /** Where the applied configuration version is recorded inside the guest. */
    const val CONFIG_STAMP = ROOT + "/.config-version"
    const val MCP_URL = "http://127.0.0.1:47831/mcp"

    /** Ghidra headless analyzer, installed on demand into the distro. */
    const val GHIDRA_ROOT = ROOT + "/ghidra"
    const val GHIDRA_SCRIPTS = ROOT + "/scripts"
    const val GHIDRA_OUT = ROOT + "/ghidra-out"
    const val GHIDRA_TAG = "Ghidra_12.1.4_build"
    const val GHIDRA_ASSET = "ghidra_12.1.4_PUBLIC_20260921.zip"

    /**
     * SHA-256 of [GHIDRA_ASSET], from the NSA release page.
     *
     * A 570 MB binary installed as root deserves an integrity check: a
     * truncated download used to be caught only by a size floor, which an
     * attacker in the middle cannot pass but a silent bit-flip inside a
     * complete file can.
     */
    const val GHIDRA_SHA256 = "ddac49f903da9d5bac833e5cc79395098b9c33cfd3279be5f31bd00387d2d4db"

    /**
     * The Ghidra release REA supports.
     *
     * REA gates its Ghidra provider on this version and rejects anything else,
     * so an older install is not merely unconfigured — every decompile through
     * REA fails. The probe reports a mismatched tree as missing, which makes
     * Tools offer the upgrade instead of silently keeping the old one.
     */
    const val GHIDRA_REQUIRED = "12.1.4"

    /**
     * The jadx bridge REA uses for the whole Android/Java lane.
     *
     * REA does not ship or fetch it: it refuses every Android operation —
     * inspect_android_package, search_android_classes, decompile_all,
     * decode_android_resources — until REA_JADX_MCP_JAR points at a jar it
     * accepts. The installed `jadx` CLI is a different program and does not
     * satisfy it.
     *
     * The version is pinned deliberately. REA checks both the bridge's
     * reported version and the release version, so a newer jar fails with the
     * same "not configured" error as a missing one — which is a confusing way
     * to say "wrong version". v0.7.1 is the one it accepts.
     */
    const val JADX_MCP_URL =
        "https://github.com/1013503897/jadx-headless-mcp/releases/download/v0.7.1/" +
            "jadx-headless-mcp-0.7.1-all.jar"
    const val JADX_MCP_FILE = "jadx-headless-mcp-0.7.1-all.jar"
    const val JADX_MCP_BYTES = 42_619_858L
    const val JADX_MCP_SHA256 =
        "6e5eacf500b64292bfb73c49797c1958f6ee44646e43e868039ae7feb573ff75"

    /** Where the bridge jar lives once installed. */
    const val JADX_MCP_JAR = ROOT + "/tools/jadx-mcp/" + JADX_MCP_FILE

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

    /**
     * True when REA's own binary exists in [distroId].
     *
     * This is the difference between "the container exists" and "REA can run".
     * The MCP bridge needs it because `exec $REA_BIN` on a missing file reports
     * only `not found`, which says nothing about the real problem: the core
     * install never completed.
     */
    suspend fun coreInstalled(ctx: Context, distroId: String): Boolean =
        withContext(Dispatchers.IO) {
            if (distroId.isBlank()) return@withContext false
            val profile = DistroInstallProfile.forId(distroId) ?: return@withContext false
            if (profile.method == "chroot") {
                if (!RootShell.isRootAvailable()) return@withContext false
                val path = profile.chrootPath ?: return@withContext false
                // Must run INSIDE the chroot. `captureResult` runs on the host,
                // where /opt/altaris/rea does not exist, so this always reported
                // "REA core is not installed" for a chroot that had it.
                return@withContext runCatching {
                    RootShell.captureInChroot(
                        cmd = "test -x " + REA_BIN,
                        user = "root",
                        chrootPath = path,
                        timeoutMs = 20_000L,
                        context = ctx.applicationContext,
                    ).exitCode == 0
                }.getOrDefault(false)
            }
            if (profile.prootName.isBlank()) return@withContext false
            val rootfs = File(
                ctx.filesDir,
                "usr/var/lib/proot-distro/containers/${profile.prootName}/rootfs"
            )
            File(rootfs, REA_BIN.removePrefix("/")).isFile
        }

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
        // The release REA supports, not just any install: REA rejects other
        // versions at the provider level, so an 11.x tree left in place would
        // look installed while every decompile through REA failed.
        "if [ -x " + GHIDRA_ROOT + "/support/analyzeHeadless ] && " +
            "grep -q '^application.version=" + GHIDRA_REQUIRED + "\$' " +
            GHIDRA_ROOT + "/Ghidra/application.properties 2>/dev/null; then " +
            "echo bin:ghidra=1; else echo bin:ghidra=0; fi",
        // No prebuilt ARM64 decompiler ships with Ghidra; it is compiled on
        // device during install. Without it only disassembly works.
        "if [ -x " + GHIDRA_ROOT + "/Ghidra/Features/Decompiler/os/linux_arm_64/decompile ]; then " +
            "echo bin:ghidra-decompiler=1; else echo bin:ghidra-decompiler=0; fi",
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
            // Decide from the filesystem, not from in-memory state.
            //
            // This used to be `_status.distroId == distroId && _status.installed`,
            // which is false whenever the app was restarted (the flow resets)
            // — so a tree the user already had was treated as absent and the
            // frida-module repair inside the "already installed" branch never
            // ran. That is why Node's frida package stayed missing.
            val alreadyInstalled = runCatching {
                GuestExec.run(
                    app,
                    distroId,
                    "test -x " + REA_BIN + " && echo YES || echo NO",
                    timeoutMs = 30_000L,
                ).output.contains("YES")
            }.getOrDefault(false)
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
                // Repair what an older build's install left out. The core is
                // skipped when REA is present, so a missing piece — the frida
                // module the hook tools import — would otherwise never arrive
                // without a full uninstall and reinstall.
                ensureFridaModule(app, distroId) { line(it) }
                ensureJadxBridge(app, distroId) { line(it) }
            } else {
                update { it.copy(busy = "Installing REA core") }
                line("installing Node " + NODE_VERSION + " + rea-agents " + REA_VERSION)
                val core = GuestExec.run(app, distroId, coreScript(), timeoutMs = 45 * 60_000L) { line(it) }
                if (core.ok) {
                    line("REA core ready")
                    // A fresh install needs the bridge too; the "already
                    // installed" branch above only covers upgrades.
                    ensureJadxBridge(app, distroId) { line(it) }
                } else {
                    coreError = core.tail(8).ifBlank { "REA core install failed" }
                    line("ERROR: REA core install failed (tools below are still attempted)")
                }
            }

            // Bring the agent-facing configuration up to date even when the
            // core was already installed. This is what makes an app update
            // reach an existing distro: the MCP block, the skill, the guide
            // and the pins are re-applied when their version changes.
            update { it.copy(busy = "Syncing agent configuration") }
            ensureAgentConfig(app, distroId) { line(it) }

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

    /**
     * Install exactly one tool, without taking the whole-install lock.
     *
     * [ReaToolInstaller] runs several tools at once — archive downloads are
     * independent and the requirement is that they overlap. Routing each one
     * through [install] serialised them on its `mutex`, so "three downloads in
     * parallel" actually ran one at a time and the queue looked frozen while
     * seventeen tools waited their turn.
     *
     * Callers own the sequencing that matters: the package lane is serialised
     * because apt/dnf/pacman take a real database lock, and the archive lane is
     * not. Helpers and the core are staged once by [prepare] before this runs.
     */
    suspend fun installTool(
        ctx: Context,
        distroId: String,
        id: String,
        manager: String,
        onLog: (String) -> Unit = {},
    ): ToolOutcome = withContext(Dispatchers.IO) {
        val dep = ReaCatalog.byId(id)
            ?: return@withContext ToolOutcome(id, false, "unknown tool: $id")
        installDependency(ctx.applicationContext, distroId, dep, manager, onLog)
    }

    /**
     * Stage everything the per-tool installs share: helper scripts, the package
     * manager name, and the REA core.
     *
     * Serialised on [mutex], because two of these at once would race on the
     * script directory and on Node.
     */
    suspend fun prepare(
        ctx: Context,
        distroId: String,
        onLog: (String) -> Unit = {},
    ): Status = withContext(Dispatchers.IO) {
        mutex.withLock {
            val app = ctx.applicationContext
            if (distroId.isBlank()) {
                update { it.copy(busy = "", error = "Install a distro first") }
                return@withLock _status.value
            }
            // Same rule as [install]: probe the filesystem. In-memory state is
            // empty after an app restart, which used to make an existing REA
            // look absent and skip the frida-module repair.
            val alreadyInstalled = runCatching {
                GuestExec.run(
                    app,
                    distroId,
                    "test -x " + REA_BIN + " && echo YES || echo NO",
                    timeoutMs = 30_000L,
                ).output.contains("YES")
            }.getOrDefault(false)
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

            update { it.copy(busy = "Staging helpers") }
            if (!stageHelpers(app, distroId) { line(it) }) {
                line("warning: helper scripts could not be staged; installs may fail")
            }
            val manager = detectPackageManager(app, distroId)
            update { it.copy(packageManager = manager) }
            line("distro " + distroId + " · package manager: " + manager.ifBlank { "unknown" })

            if (alreadyInstalled) {
                line("REA core already installed in " + distroId)
                // Same repair as in [install]: an installed core is skipped, so
                // a piece an older build left out would never be added.
                ensureFridaModule(app, distroId) { line(it) }
                ensureJadxBridge(app, distroId) { line(it) }
            } else {
                update { it.copy(busy = "Installing REA core") }
                line("installing Node " + NODE_VERSION + " + rea-agents " + REA_VERSION)
                val core = GuestExec.run(app, distroId, coreScript(), timeoutMs = 45 * 60_000L) { line(it) }
                if (core.ok) {
                    line("REA core ready")
                } else {
                    val coreError = core.tail(8).ifBlank { "REA core install failed" }
                    line("ERROR: REA core install failed (tools below are still attempted)")
                    update { it.copy(error = coreError) }
                }
            }
            // Same sync as install(): prepare is what runs when the server is
            // ensured, so this is the path that keeps an already-installed
            // tree current after an app update.
            update { it.copy(busy = "Syncing agent configuration") }
            ensureAgentConfig(app, distroId) { line(it) }

            update { it.copy(busy = "") }
            probe(app, distroId)
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
                GHIDRA_SHA256,
            )
            // ROOT is an absolute path: quoting it directly. shellPath() would
            // prepend the scripts directory and produce
            // /opt/altaris/rea/scripts//opt/altaris/rea, which is why the zip
            // landed somewhere unzip could not find.
            val script = "sh " + shellPath("ghidra-install.sh") + " " +
                GuestExec.shellQuote(ROOT) + " " +
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
                        .lastOrNull {
                            it.startsWith("ok:") || it.startsWith("ERROR") ||
                                it.startsWith("already installed")
                        }
                        .orEmpty()
                    ToolOutcome(
                        dep.id,
                        result.ok,
                        if (result.ok) {
                            when {
                                detail.startsWith("already installed") -> "already installed"
                                else -> detail.removePrefix("ok:").trim()
                            }
                        } else {
                            detail.removePrefix("ERROR:").trim().ifBlank { "install failed" }
                        },
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
            .lastOrNull {
                it.startsWith("ok:") || it.startsWith("ERROR") ||
                    it.startsWith("already installed")
            }
            .orEmpty()
        return ToolOutcome(
            dep.id,
            result.ok,
            if (result.ok) {
                when {
                    detail.startsWith("already installed") -> "already installed"
                    else -> detail.removePrefix("ok:").trim()
                }
            } else {
                detail.removePrefix("ERROR:").trim().ifBlank { "archive install failed" }
            },
        )
    }

    // -------------------------------------------------------- raw packages

    /**
     * Install bare package names through the distro's own package manager.
     *
     * The asset toolkit is a list of formats, not of catalog entries: exiftool
     * is `libimage-exiftool-perl` on Debian and `perl-Image-ExifTool` on Fedora.
     * Routing them through [ReaCatalog] would mean one catalog entry per package
     * name per distro for no benefit, so this installs the names directly with
     * the same lock discipline: the package manager takes a real database lock,
     * so it is serialised on [mutex].
     */
    suspend fun installPackages(
        ctx: Context,
        distroId: String,
        packages: List<String>,
        onLog: (String) -> Unit = {},
    ): Status = withContext(Dispatchers.IO) {
        mutex.withLock {
            val app = ctx.applicationContext
            if (distroId.isBlank() || packages.isEmpty()) {
                return@withLock _status.value
            }
            val manager = detectPackageManager(app, distroId)
            if (manager.isBlank() || manager == "none") {
                update { it.copy(error = "no supported package manager in this distro") }
                return@withLock _status.value
            }
            update { it.copy(busy = "Installing " + packages.size + " packages", error = "") }
            val logBuffer = StringBuilder()
            fun line(text: String) {
                synchronized(logBuffer) {
                    logBuffer.append(text).append('\n')
                    if (logBuffer.length > 12_000) logBuffer.delete(0, logBuffer.length - 10_000)
                }
                onLog(text)
                update { it.copy(log = synchronized(logBuffer) { logBuffer.toString() }) }
            }
            if (!stageHelpers(app, distroId) { line(it) }) {
                line("warning: helper scripts could not be staged; installs may fail")
            }
            val script = "sh " + shellPath("install-tool.sh") + " " +
                GuestExec.shellQuote(manager) + " " + GuestExec.shellQuote("") + " " +
                GuestExec.shellQuote(packages.joinToString(","))
            val result = GuestExec.run(app, distroId, script, timeoutMs = 30 * 60_000L) { line(it) }
            update {
                it.copy(
                    busy = "",
                    error = if (result.ok) "" else result.tail(4).ifBlank { "install failed" },
                )
            }
            _status.value
        }
    }

    /**
     * A one-shot install command for [packages] on [manager].
     *
     * Deliberately simpler than [installPackages]: that path stages helper
     * scripts and drives the full progress model, which is right for the tool
     * catalog but wrong for "make sure pip exists" — a single call, inside
     * another install, where a second progress reporter would fight the first.
     *
     * `--no-install-recommends` and the quiet flags keep the output small, and
     * every branch updates the index first because a fresh rootfs has none.
     */
    fun packageInstallScript(manager: String, packages: List<String>): String {
        if (packages.isEmpty()) return "true"
        val list = packages.joinToString(" ")
        // Sourced from the staged helper so the lock discipline is the same
        // one the tool installers use. A one-shot install runs while the tool
        // lane may also be installing, and the second apt used to fail with
        // "Could not get lock" instead of waiting.
        val lock = "ALTARIS_PKG_SH=" + shellPath("pkg-lock.sh") + "\n" +
            "if [ -r \"\$ALTARIS_PKG_SH\" ]; then . \"\$ALTARIS_PKG_SH\"; fi\n" +
            "command -v pkg_run >/dev/null 2>&1 || pkg_run() { " +
            "if command -v timeout >/dev/null 2>&1; then timeout -k 30 1800 \"\$@\"; " +
            "else \"\$@\"; fi; }\n"
        return when (manager) {
            "apt" -> "set +e\nexport DEBIAN_FRONTEND=noninteractive\n" + lock +
                "pkg_run apt-get update -qq >/dev/null 2>&1\n" +
                "pkg_run apt-get install -y -qq --no-install-recommends $list 2>&1 | tail -20\n"
            "apk" -> "set +e\n" + lock + "pkg_run apk update >/dev/null 2>&1\n" +
                "pkg_run apk add --no-cache $list 2>&1 | tail -20\n"
            "dnf" -> "set +e\n" + lock + "pkg_run dnf install -y -q $list 2>&1 | tail -20\n"
            "pacman" -> "set +e\n" + lock + "pkg_run pacman -Sy --noconfirm --needed $list 2>&1 | tail -20\n"
            else -> "echo \"no supported package manager\""
        }
    }

    // ------------------------------------------------------------ debugger

    /** What the debugger backend can do here. */
    data class Backend(
        val available: Boolean = false,
        val reason: String = "",
        val runningApps: List<String> = emptyList(),
    )

    /** Result of an attach: the host PID when it worked. */
    data class Attach(val pid: Int? = null, val output: String = "")

    /**
     * Probe the debugger backend and list the clone's running apps.
     *
     * A debugger inside the distro cannot see this device's processes by their
     * host PID, so the probe also reports which packages are running. Those
     * names are what the user actually attaches to; the PID translation happens
     * at attach time.
     */
    suspend fun probeBackend(ctx: Context, distroId: String): Backend =
        withContext(Dispatchers.IO) {
            if (distroId.isBlank()) return@withContext Backend()
            val script = buildString {
                append("set +e\n")
                append("command -v gdb >/dev/null 2>&1 && echo gdb=1 || echo gdb=0\n")
                append("command -v rizin >/dev/null 2>&1 && echo rizin=1 || echo rizin=0\n")
            }
            val probe = GuestExec.run(ctx.applicationContext, distroId, script, timeoutMs = 30_000L)
            val hasGdb = probe.output.lineSequence().any { it.trim() == "gdb=1" }
            val hasRizin = probe.output.lineSequence().any { it.trim() == "rizin=1" }
            if (!hasGdb && !hasRizin) {
                return@withContext Backend(
                    available = false,
                    reason = "No debugger in " + distroId +
                        ". Install gdb from Tools → Reverse engineering.",
                )
            }
            Backend(
                available = true,
                runningApps = runCatching { runningPackages() }.getOrDefault(emptyList()),
            )
        }

    /** Packages with a live process, newest first. Best effort. */
    /**
     * The **clones** that are running right now.
     *
     * Not every process on the device. The first version listed whatever /proc
     * showed, which on a Xiaomi phone is a wall of system services —
     * `com.miui.aod`, `com.xiaomi.aicr`, `com.google.android.webview` — and the
     * clone was lost in it. The registry of clones is the only correct source:
     * an app you did not clone is not something this debugger should offer.
     */
    private fun runningPackages(): List<String> {
        // Read the clone registry directly rather than the engine's StateFlow:
        // the flow is only filled once the engine is connected, and the
        // debugger must be able to list clones before anything is launched.
        val clones = runCatching { clonePackages() }.getOrDefault(emptyList())
        if (clones.isEmpty()) return emptyList()
        // Only the ones with a live process; a clone that is installed but not
        // running cannot be attached to.
        val live = processTable().map { it.second }.toSet()
        return clones.filter { it in live }.distinct()
    }

    /**
     * Every process as (pid, argv0).
     *
     * Read through **root**, not directly. Android hides other apps' `/proc`
     * entries from the app uid, so a direct read sees only this app and its
     * own service — which is exactly what the first version reported: it
     * listed `com.altaris.linux` and nothing else, and then failed to attach to
     * the clone that was running the whole time. Root is required to attach
     * anyway, so using it to find the PID is not an extra privilege.
     */
    private fun processTable(): List<Pair<Int, String>> {
        // `ps -A -o PID,NAME` is the cheapest portable form, but the NAME column
        // is truncated on some toolbox builds, so argv[0] from /proc is used
        // when it is readable.
        val fromProc = procTableDirect()
        if (fromProc.isNotEmpty() && RootShell.isRootAvailable()) {
            val viaRoot = procTableThroughRoot()
            if (viaRoot.isNotEmpty()) return viaRoot
        }
        return fromProc
    }

    /** Direct /proc read: only sees processes this uid may inspect. */
    private fun procTableDirect(): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        File("/proc").listFiles()?.forEach { dir ->
            val pid = dir.name.toIntOrNull() ?: return@forEach
            if (pid <= 0) return@forEach
            val argv0 = runCatching { File(dir, "cmdline").readBytes() }.getOrNull()
                ?.let { firstArg(it) }
                .orEmpty()
            if (argv0.isNotBlank()) out.add(pid to argv0)
        }
        return out
    }

    /** The same, through `su`, which can see every process. */
    private fun procTableThroughRoot(): List<Pair<Int, String>> {
        val result = runCatching {
            RootShell.captureResult(
                "for d in /proc/[0-9]*; do " +
                    "a=\$(tr '\\0' '\\n' < \"\$d/cmdline\" 2>/dev/null | head -n1); " +
                    "[ -n \"\$a\" ] && echo \"\${d##*/}\t\$a\"; done",
                30_000L,
            )
        }.getOrNull() ?: return emptyList()
        return result.stdout.lines().mapNotNull { line ->
            val pid = line.substringBefore('\t').toIntOrNull() ?: return@mapNotNull null
            val name = line.substringAfter('\t', "").trim()
            if (name.isBlank()) null else pid to name
        }
    }

    /**
     * argv[0] from a raw cmdline.
     *
     * The NUL bytes are separators between arguments, not padding. Trimming
     * them from the ends only left the whole command line — arguments included
     * — as the "name", which is why the app list showed a shell command and a
     * `su` invocation as if they were apps.
     */
    private fun firstArg(raw: ByteArray): String =
        String(raw, Charsets.UTF_8).substringBefore('\u0000').trim()

    /** A package name, not a shell command or a path. */
    private fun isPackageName(name: String): Boolean =
        name.contains('.') && !name.contains(' ') && !name.startsWith("/") &&
            !name.contains('\t')

    /**
     * Package names from the clone registry.
     *
     * `/data/local/tmp/altaris/clones.json` is root-owned, so it is read
     * through root when possible and falls back to the engine's cached list.
     */
    private fun clonePackages(): List<String> {
        val fromEngine = runCatching {
            com.altaris.linux.core.clone.HeadlessCloneEngine.clones.value.map { it.packageName }
        }.getOrDefault(emptyList())
        if (fromEngine.isNotEmpty()) return fromEngine
        if (!RootShell.isRootAvailable()) return emptyList()
        val raw = runCatching {
            RootShell.captureResult(
                "cat /data/local/tmp/altaris/clones.json 2>/dev/null",
                20_000L,
            ).stdout
        }.getOrNull().orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = org.json.JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.optString("package")
                    ?.takeIf { it.isNotBlank() }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Attach to [target], a package name or a host PID.
     *
     * The attach runs **on the host**, through frida-server, because a
     * debugger inside the distro has its own PID namespace: the host PID the
     * clone reports does not exist there. That is why an attach that looked
     * correct did nothing at all.
     *
     * gdb is still offered as a fallback for the case where frida-server is not
     * installed, but it is invoked the same way — on the host side — so it can
     * see the process.
     */
    suspend fun attachDebugger(ctx: Context, distroId: String, target: String): Attach =
        withContext(Dispatchers.IO) {
            // Refuse anything that is not a clone before doing any work. This
            // is the guard that stops an agent attaching to the user's real app.
            val packageName = target.trim().substringBefore(':')
            if (ReaDistro.isCloneOnly(ctx) && !isClone(packageName)) {
                return@withContext Attach(pid = null, output = notACloneReason(target))
            }
            // If the clone is installed but not running, start it. An agent that
            // is told to inspect an app should not have to be told to launch it
            // first, and "not running" is not a useful answer when starting it
            // is one call away.
            var pid = resolveClonePid(ctx, target)
            if (pid == null) {
                pid = launchAndWaitFor(packageName)
            }
            if (pid == null) {
                return@withContext Attach(
                    pid = null,
                    output = "No running process matches \"" + target + "\". " +
                        "It is not installed as a clone, or it failed to start.",
                )
            }
            // Gadget first when it is the only option: with no root there is
            // no server, and a clone built with the gadget listens on its own
            // port. `frida -H` reaches it exactly like a server.
            if (!ReaFrida.isServerRunning() && FridaGadget.isCached(ctx)) {
                val viaGadget = runCatching {
                    RootShell.captureResult(
                        "frida -H 127.0.0.1:" + GADGET_PORT + " -p " + pid +
                            " -q -e 'console.log(Process.arch)' 2>&1 | head -20",
                        60_000L,
                    )
                }.getOrNull()
                if (viaGadget != null && viaGadget.exitCode == 0 &&
                    viaGadget.stdout.isNotBlank()
                ) {
                    return@withContext Attach(pid = pid, output = viaGadget.stdout)
                }
            }
            // Preferred: frida on the host. Start it for this session rather
            // than keeping a resident root process alive all day — that idle
            // server is what drained the battery.
            if (!ReaFrida.isServerRunning()) {
                runCatching { ReaFrida.ensureRunningForSession() }
            }
            ReaFrida.touch()
            if (ReaFrida.isServerRunning()) {
                val result = runCatching {
                    RootShell.captureResult(
                        "frida -H 127.0.0.1:" + ReaFrida.PORT + " -p " + pid +
                            " -q -e 'console.log(Process.arch); " +
                            "console.log(JSON.stringify(Process.enumerateModules().slice(0,8).map(m=>m.name)))' " +
                            "2>&1 | head -40",
                        90_000L,
                    )
                }.getOrNull()
                val out = result?.stdout.orEmpty()
                if (result != null && result.exitCode == 0 && out.isNotBlank()) {
                    return@withContext Attach(pid = pid, output = out)
                }
                // Fall through to gdb rather than reporting a bare failure.
            }
            val script = buildString {
                append("set +e\n")
                append("gdb -q -batch -p ").append(pid)
                append(" -ex 'info proc mappings' -ex 'info registers' -ex detach 2>&1\n")
            }
            val result = GuestExec.run(
                ctx.applicationContext,
                distroId,
                script,
                timeoutMs = 60_000L,
                asRoot = true,
            )
            Attach(
                pid = if (result.output.contains("0x")) pid else null,
                output = result.tail(40),
            )
        }

    /** The port a Gadget-instrumented clone listens on (see [FridaGadget]). */
    private const val GADGET_PORT = 27043

    /**
     * Start [target] as a clone and wait for its process to appear.
     *
     * The debugger used to refuse when the app was not running, which read as
     * "the app is not there" even though it was installed and one launch away.
     * Starting it is the obvious repair, and the wait is bounded so a crash on
     * startup is reported rather than hanging.
     */
    private suspend fun launchAndWaitFor(target: String): Int? {
        val packageName = target.trim().substringBefore(':')
        if (packageName.isBlank() || !packageName.contains('.')) return null
        val launched = runCatching {
            com.altaris.linux.core.clone.HeadlessCloneEngine.apiLaunch(packageName)
        }.getOrNull()
        if (launched == null) return null
        // Poll for the process; a cold start is a few seconds, not instant.
        repeat(20) {
            kotlinx.coroutines.delay(500L)
            resolvePid(packageName)?.let { return it }
        }
        return null
    }

    /** Detach whatever the debugger holds. */
    suspend fun detachDebugger(ctx: Context, distroId: String): String =
        withContext(Dispatchers.IO) {
            val output = GuestExec.run(
                ctx.applicationContext,
                distroId,
                "set +e; pkill -INT gdb 2>/dev/null; echo detached",
                timeoutMs = 20_000L,
            ).tail(10)
            // A detach ends the session, so the root daemon has no reason to
            // stay resident. The idle watchdog covers a client that never
            // detaches; this covers the one that does.
            runCatching { ReaFrida.stopServer() }
            output
        }

    /**
     * Run one console command against the attached process.
     *
     * Frida first: a script is evaluated in the target's own JS runtime, which
     * is what makes runtime reads and patching possible. When only gdb is
     * available the command is passed to it on the host, so it can see the
     * process — a gdb inside the distro cannot.
     */
    suspend fun debuggerCommand(
        ctx: Context,
        distroId: String,
        command: String,
    ): GuestExec.Result = withContext(Dispatchers.IO) {
        val pid = _attachedPid
        if (pid == null) {
            return@withContext GuestExec.Result(-1, "Not attached.")
        }
        // Never inject into anything but a clone.
        //
        // frida-server runs as root, so it will happily attach to a system
        // process if a command names one — and injecting into SystemUI or a
        // running system app is what made the device crash: the agent crashed
        // the UI process, screenshots and downloads stopped, and the whole
        // phone looked broken. The PID is checked on every command, not only at
        // attach, because a script can enumerate and spawn other processes.
        if (!isAttachedPidAClone(pid)) {
            _attachedPid = null
            return@withContext GuestExec.Result(
                -1,
                "Refusing to run a command against pid " + pid +
                    ": it is not a clone. The debugger only touches apps running " +
                    "in a clone, never a real app on this device.",
            )
        }
        ReaFrida.touch()
        if (ReaFrida.isServerRunning()) {
            // A `$` prefix means "this is JavaScript"; anything else is a gdb
            // command, so the console can speak both without a mode switch.
            if (command.startsWith("$")) {
                val js = command.removePrefix("$").trim()
                val result = runCatching {
                    RootShell.captureResult(
                        "frida -H 127.0.0.1:" + ReaFrida.PORT + " -p " + pid +
                            " -q -e " + GuestExec.shellQuote(js) + " 2>&1 | head -200",
                        90_000L,
                    )
                }.getOrNull()
                return@withContext GuestExec.Result(
                    result?.exitCode ?: -1,
                    result?.stdout.orEmpty(),
                )
            }
        }
        val script = "set +e; gdb -q -batch -p " + pid +
            " -ex " + GuestExec.shellQuote(command) + " -ex detach 2>&1"
        GuestExec.run(ctx.applicationContext, distroId, script, timeoutMs = 60_000L, asRoot = true)
    }

    @Volatile
    private var _attachedPid: Int? = null

    /**
     * True when [pid] is a process belonging to a clone.
     *
     * The attach path already refuses a non-clone target, but the *server* is
     * root and will inject into whatever it is pointed at. Checking the live
     * PID on every command is what stops a script — or a stale attach — from
     * touching SystemUI, the launcher, or any real app on the device. Those
     * injections are not recoverable: the process crashes, and with it go
     * screenshots, downloads and the UI.
     */
    private fun isAttachedPidAClone(pid: Int): Boolean {
        if (pid <= 0) return false
        // Which package does this PID belong to?
        val name = runCatching {
            val raw = RootShell.captureResult(
                "cat /proc/" + pid + "/cmdline 2>/dev/null",
                10_000L,
            ).stdout
            firstArg(raw.toByteArray(Charsets.UTF_8))
        }.getOrNull().orEmpty()
        if (name.isBlank() || !isPackageName(name)) return false
        val pkg = name.substringBefore(':')
        // A clone, by registry or by the engine's prefix.
        if (clonePackages().any { it == pkg }) return true
        return pkg.startsWith(CLONE_PACKAGE_PREFIX)
    }

    /** Prefix the clone engine gives every clone it installs. */
    private const val CLONE_PACKAGE_PREFIX = "com.altaris.apps."

    /** Record the attached PID, so the console commands reach the same process. */
    fun rememberAttachedPid(pid: Int?) {
        _attachedPid = pid
    }

    /**
     * Resolve [target] to a host PID.
     *
     * A bare number is taken as a PID. Anything else is matched against the
     * `cmdline` of every live process, which is how a package name becomes the
     * process the clone actually runs.
     */
    private fun resolvePid(target: String): Int? {
        val value = target.trim()
        if (value.isEmpty()) return null
        value.toIntOrNull()?.let { pid ->
            return if (File("/proc/$pid").isDirectory) pid else null
        }
        // Match argv[0] exactly, then a sub-process of the same package
        // (`pkg:remote`). The previous version compared the whole command line,
        // arguments included, so an app whose argv[0] was the package name
        // never matched.
        val table = processTable()
        table.firstOrNull { it.second == value }?.let { return it.first }
        table.firstOrNull { it.second.startsWith(value + ":") }?.let { return it.first }
        return null
    }

    /**
     * Resolve [target] to a PID, refusing anything that is not a clone.
     *
     * This is the guard that was missing. Root can attach to any process on the
     * device, so an agent told to inspect "Anime Chicken" could attach to the
     * real installation in the user's app drawer rather than the clone — and
     * `resolvePid` alone happily returned that PID because it only matched a
     * name against the process table.
     *
     * A clone is identified by its presence in the clone registry, not by a
     * name pattern: `com.altaris.apps.` is the prefix the engine generates, but
     * the registry is the authoritative list and it also covers clones created
     * before the prefix was fixed.
     */
    private fun resolveClonePid(ctx: Context, target: String): Int? {
        val value = target.trim().substringBefore(':')
        if (value.isEmpty()) return null
        // A raw PID is never acceptable: a number says nothing about what the
        // process is, and "attach to pid 1234" is exactly the wrong-app case.
        if (value.toIntOrNull() != null) return null
        if (ReaDistro.isCloneOnly(ctx) && !isClone(value)) return null
        return resolvePid(value)
    }

    /** True when [packageName] is a clone this app created. */
    private fun isClone(packageName: String): Boolean =
        clonePackages().any { it == packageName }

    /** The reason a target was rejected, for the agent and the UI. */
    private fun notACloneReason(target: String): String =
        "\"" + target + "\" is not a clone. The debugger only attaches to apps " +
            "running in the clone — not to the real app on this device. Clone it " +
            "first (altaris_installed_apps then altaris_clone), or switch off " +
            "\"Clones only\" in Tools → Reverse engineering to allow real apps."

    /** Absolute in-guest path of a staged REA helper script. */
    private fun shellPath(name: String): String = GuestExec.shellQuote(GHIDRA_SCRIPTS + "/" + name)

    /**
     * The same, for callers outside this object.
     *
     * The asset toolkit installs through the same staged helper scripts, and
     * duplicating the path here would drift the moment either side moves.
     */
    internal fun shellPathForAsset(name: String): String = shellPath(name)

    /**
     * The shell command that starts `rea mcp` inside the guest.
     *
     * One definition, because both MCP instances need the same environment and
     * they had drifted: the stdio server got GHIDRA_INSTALL_DIR and the bridge
     * — the one an agent actually connects to — did not. REA reads the value
     * at startup and reports every decompiler engine as unconfigured without
     * it, which is exactly the "provider_unavailable, all four engines
     * rejected" the agent saw.
     */
    fun mcpLaunchCommand(): String =
        "PATH=" + PROBE_PATH + ":\$PATH; export PATH; " +
            "REA_MCP_INPUT_SCHEMA_PROFILE=\${REA_MCP_INPUT_SCHEMA_PROFILE:-compact}; " +
            "export REA_MCP_INPUT_SCHEMA_PROFILE; " +
            "GHIDRA_INSTALL_DIR=" + GHIDRA_ROOT + "; export GHIDRA_INSTALL_DIR; " +
            // Only when the bridge is actually there: REA treats the variable
            // as authoritative, and pointing it at a missing file produces a
            // different, worse failure than leaving it unset.
            "if [ -f " + JADX_MCP_JAR + " ]; then " +
            "REA_JADX_MCP_JAR=" + JADX_MCP_JAR + "; export REA_JADX_MCP_JAR; fi; " +
            "exec " + REA_BIN + " mcp"

    /**
     * Install the jadx bridge on demand, returning an error or "".
     *
     * Public because the agent needs to trigger it: REA reports
     * capability_unavailable for the whole Android lane until this exists, and
     * asking a human to run a shell command is not an answer. Safe to call
     * repeatedly — the script verifies what is already there.
     */
    suspend fun installJadxBridge(
        ctx: Context,
        distroId: String,
        onLog: (String) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        ensureJadxBridge(ctx.applicationContext, distroId, onLog)
        val present = runCatching {
            GuestExec.run(
                ctx.applicationContext,
                distroId,
                "test -s " + JADX_MCP_JAR + " && echo YES || echo NO",
                timeoutMs = 30_000L,
            ).output.contains("YES")
        }.getOrDefault(false)
        if (present) "" else "the jadx bridge is still missing after the install"
    }

    /**
     * Install the jadx bridge the Android lane needs.
     *
     * REA refuses every Android/Java operation — inspect_android_package,
     * search_android_classes, decompile_all, decode_android_resources — until
     * REA_JADX_MCP_JAR points at a jar it accepts, and it does not fetch one
     * itself. Without this the agent can analyse native libraries and nothing
     * else, which is exactly the wall it reported.
     *
     * Pinned to v0.7.1 and verified by size and SHA-256: REA checks the
     * bridge's version as well as the release, so a newer jar fails with the
     * same "not configured" error and looks like a missing file.
     */
    private suspend fun ensureJadxBridge(
        app: Context,
        distroId: String,
        line: (String) -> Unit,
    ) {
        val script = "sh " + shellPath("rea-jadx-mcp.sh") + " " +
            GuestExec.shellQuote(ROOT) + " " +
            GuestExec.shellQuote(JADX_MCP_URL) + " " +
            GuestExec.shellQuote(JADX_MCP_FILE) + " " +
            JADX_MCP_BYTES + " " +
            GuestExec.shellQuote(JADX_MCP_SHA256)
        val result = GuestExec.run(app, distroId, script, timeoutMs = 30 * 60_000L)
        for (raw in result.output.lines()) {
            val text = raw.trim()
            // curl's progress meter is noise in an install log.
            if (text.isNotEmpty() && !text.contains("% Total")) line(text)
        }
        if (!result.ok) {
            line("warning: the jadx bridge did not install; REA's Android tools will report capability_unavailable")
        }
    }

    private fun coreScript(): String = "sh " + shellPath("rea-core-install.sh") + " " +
        GuestExec.shellQuote(ROOT) + " " + GuestExec.shellQuote(NODE_VERSION) + " " +
        GuestExec.shellQuote(REA_VERSION)

    /**
     * Make sure the Node `frida` module is importable in REA's tree.
     *
     * REA's hook tools import it, npm does not always lay it down, and an
     * installed core is never re-run — so a tree created before this was part
     * of the install would keep failing with capability_unavailable forever.
     * The script is idempotent: it exits immediately when the module imports,
     * so calling it on every prepare costs one node invocation.
     *
     * The build toolchain comes first. `frida` is a native binding; when npm
     * cannot find a prebuild it falls back to compiling, which needs a
     * compiler, make and python3 — and a minimal rootfs has none of them,
     * so the install fails silently and the module stays absent.
     *
     * Called from [install] and [prepare] **while they hold [mutex]**, so it
     * must not call [installPackages]: that takes the same mutex, and Kotlin's
     * Mutex is not reentrant. Doing so deadlocked the whole install at 0% with
     * the button stuck on "Working…", which is what "Reinstall is stuck" was.
     * The packages are installed through [GuestExec] directly instead.
     */
    private suspend fun ensureFridaModule(
        app: Context,
        distroId: String,
        line: (String) -> Unit,
    ) {
        val manager = detectPackageManager(app, distroId)
        if (manager.isNotBlank() && manager != "none") {
            val toolchain = when (manager) {
                "apt" -> listOf("build-essential", "python3")
                "apk" -> listOf("build-base", "python3")
                "dnf" -> listOf("gcc-c++", "make", "python3")
                "pacman" -> listOf("base-devel", "python")
                else -> emptyList()
            }
            if (toolchain.isNotEmpty()) {
                line("installing the frida module build tools")
                runCatching {
                    GuestExec.run(
                        app,
                        distroId,
                        packageInstallScript(manager, toolchain),
                        timeoutMs = 15 * 60_000L,
                    ) { line(it) }
                }
            }
        }
        val script = "sh " + shellPath("rea-frida-module.sh") + " " + GuestExec.shellQuote(ROOT)
        val result = GuestExec.run(app, distroId, script, timeoutMs = 10 * 60_000L)
        for (raw in result.output.lines()) {
            val text = raw.trim()
            if (text.isNotEmpty()) line(text)
        }
        if (!result.ok) {
            line("warning: REA's runtime-hook tools may report capability_unavailable until the frida module installs")
        }
    }

    // --------------------------------------------------------- auto-config

    /**
     * Bring the agent-facing configuration up to [CONFIG_VERSION].
     *
     * One stamp decides everything. When it matches, this is a single `cat`
     * and returns immediately — no shell per launch, no rewrite of a file the
     * user may have edited. When it does not match, every piece is re-applied
     * in one guest run:
     *
     *  - the MCP registration for each installed agent client;
     *  - the skill and the guide, so the method an agent reads matches the
     *    tools this build actually has;
     *  - Ghidra, if the installed tree is not [GHIDRA_REQUIRED] (REA rejects
     *    the whole Ghidra provider otherwise);
     *  - the jadx bridge, if it is missing or fails its size check.
     *
     * It runs on REA prepare and install, and from the REA screen when the
     * server is ensured — so an app update is enough for an existing distro.
     * Ghidra is a ~450 MB download, so it is only attempted when the probe
     * says it is wrong, and a failure there is reported rather than fatal:
     * the rest of the configuration is still worth applying.
     */
    suspend fun ensureAgentConfig(
        ctx: Context,
        distroId: String,
        onLog: (String) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        val stamp = runCatching {
            GuestExec.run(
                app,
                distroId,
                "cat " + CONFIG_STAMP + " 2>/dev/null || echo -",
                timeoutMs = 20_000L,
            ).output.trim()
        }.getOrDefault("-")
        if (stamp == CONFIG_VERSION) return@withContext ""

        onLog("syncing agent configuration to " + CONFIG_VERSION)

        // 1. The MCP registration and the skill. Both are files in the guest,
        //    so they go in one run rather than one shell per file.
        val sync = buildString {
            append("set +e\n")
            append("mkdir -p ").append(ROOT).append("\n")
            // The skill: what an agent loads when it needs the method.
            append("mkdir -p ").append(ROOT).append("/skill\n")
            append("cat > ").append(ROOT).append("/skill/SKILL.md <<'ALTARIS_SKILL_EOF'\n")
            append(ReaKnowledge.GUIDE.trimEnd()).append("\n")
            append("ALTARIS_SKILL_EOF\n")
            // The guide as a standalone file too, for clients that read a
            // resource rather than a skill.
            append("cat > ").append(ROOT).append("/skill/guide.md <<'ALTARIS_GUIDE_EOF'\n")
            append(ReaKnowledge.GUIDE.trimEnd()).append("\n")
            append("ALTARIS_GUIDE_EOF\n")
            // The MCP registration, as its own script so it can be re-run.
            append("cat > ").append(ROOT).append("/agent-config.mjs <<'ALTARIS_REA_EOF'\n")
            append(AGENT_JS.trim()).append("\n")
            append("ALTARIS_REA_EOF\n")
            append("NODE=").append(NODE_BIN).append("\n")
            append("if [ ! -x \"\$NODE\" ]; then NODE=\$(command -v node); fi\n")
            append("[ -n \"\$NODE\" ] && \"\$NODE\" ").append(ROOT)
                .append("/agent-config.mjs ").append(MCP_URL).append("\n")
            append("echo ").append(CONFIG_VERSION).append(" > ").append(CONFIG_STAMP).append("\n")
        }
        val result = GuestExec.run(app, distroId, sync, timeoutMs = 180_000L) { onLog(it) }
        if (!result.ok) {
            // Do not write the stamp on failure: the next attempt must retry.
            return@withContext result.tail(4).ifBlank { "agent configuration failed" }
        }

        // 2. The pins. Both are cheap probes; Ghidra is only downloaded when
        //    the probe says it is wrong.
        val probe = runCatching {
            GuestExec.run(
                app,
                distroId,
                "if [ -x " + GHIDRA_ROOT + "/support/analyzeHeadless ] && " +
                    "grep -q '^application.version=" + GHIDRA_REQUIRED + "\$' " +
                    GHIDRA_ROOT + "/Ghidra/application.properties 2>/dev/null; then " +
                    "echo ghidra=1; else echo ghidra=0; fi\n" +
                    "if [ -s " + JADX_MCP_JAR + " ]; then echo jadx=1; else echo jadx=0; fi",
                timeoutMs = 30_000L,
            ).output
        }.getOrDefault("")
        val ghidraOk = probe.lineSequence().any { it.trim() == "ghidra=1" }
        val jadxOk = probe.lineSequence().any { it.trim() == "jadx=1" }

        if (!ghidraOk) {
            onLog("Ghidra " + GHIDRA_REQUIRED + " is not installed; REA's Ghidra provider stays unavailable")
        }
        if (!jadxOk) {
            // The jadx bridge is 42 MB and the Android lane needs it. It is
            // safe to call: the script verifies an existing jar first.
            ensureJadxBridge(app, distroId, onLog)
        }
        ""
    }

    /**
     * The version of the agent configuration the guest currently has.
     *
     * Read by Tools so it can say "up to date" or offer the sync, instead of
     * leaving the user to guess whether a new build changed anything.
     */
    suspend fun configVersion(ctx: Context, distroId: String): String = withContext(Dispatchers.IO) {
        runCatching {
            GuestExec.run(
                ctx.applicationContext,
                distroId,
                "cat " + CONFIG_STAMP + " 2>/dev/null || echo -",
                timeoutMs = 20_000L,
            ).output.trim()
        }.getOrDefault("-")
    }

    // ------------------------------------------------------------- staging

    private fun readAsset(ctx: Context, name: String): String =
        ctx.applicationContext.assets.open(name).bufferedReader().use { it.readText() }

    /** Scripts that are copied verbatim into the guest. */
    private val VERBATIM_SCRIPTS = listOf(
        "pkg-lock.sh",
        "rea-core-install.sh",
        "rea-frida-module.sh",
        "rea-jadx-mcp.sh",
        "install-tool.sh",
        "install-archive.sh",
        "ghidra-install.sh",
        "ghidra-decompiler-build.sh",
        "DumpInfo.java",
        "LowMemoryAnalysis.java",
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
        /** "low" skips the memory-heavy analyzers; "full" runs everything. */
        mode: String = "low",
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
        val script = "GHIDRA_ANALYSIS=" + GuestExec.shellQuote(
            if (mode.equals("full", ignoreCase = true)) "full" else "low",
        ) + " sh " + shellPath("ghidra-analyze.sh") + " " +
            GuestExec.shellQuote(target) + " " + max
        // Ghidra on a large binary is minutes, not seconds: a 23 MB object is a
        // cold import plus full analysis, and the JVM alone takes tens of
        // seconds to start. The caller's timeout must not be shorter than the
        // work, or it reports a timeout for an analysis that was still running.
        // Stream the analyzer's own lines into the REA screen as they arrive.
        // A Ghidra import is minutes of real work that prints each stage; a
        // spinner with no output is indistinguishable from a hang.
        ReaBridge.note("Ghidra (" + mode + "): " + target.substringAfterLast('/'))
        val result = GuestExec.run(ctx, distroId, script, timeoutMs = 45 * 60_000L) { line ->
            ReaBridge.note(line)
        }
        JSONObject()
            .put("ok", result.ok)
            .put("target", target)
            .put("mode", if (mode.equals("full", ignoreCase = true)) "full" else "low")
            .put("exit", result.exit)
            .put("output", result.output.takeLast(8000))
            .put(
                "error",
                when {
                    result.ok -> ""
                    result.exit == -2 ->
                        "Ghidra did not finish within 45 minutes. Large binaries " +
                            "can exceed that; retry, or analyse a single function " +
                            "with REA's decompiler instead of the whole file."
                    else -> result.tail(6)
                },
            )
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
    /**
     * Copy a clone's APKs **and its native libraries** to shared storage.
     *
     * The libraries matter as much as the APKs: an app's real logic is often in
     * `lib/arm64-v8a/libil2cpp.so`, and a runtime question ("what does this
     * function return") cannot be answered from the DEX alone. Leaving them out
     * meant an agent that wanted to inspect native code had nothing to open and
     * went looking around the filesystem instead.
     *
     * The library directory is resolved through PackageManager, which knows it
     * exactly; guessing `/data/app/.../lib` from the APK path is not reliable
     * across split installs and app updates.
     */
    suspend fun exportCloneApk(ctx: Context, packageName: String): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val safe = packageName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val nativeDir = runCatching {
                ctx.applicationContext.packageManager
                    .getApplicationInfo(packageName, 0)
                    .nativeLibraryDir
                    .orEmpty()
            }.getOrDefault("")
            val script = buildString {
                append("dir=/sdcard/Download/Altaris/rea\n")
                append("mkdir -p \"\$dir\" || exit 3\n")
                append("i=0\n")
                append("for p in \$(pm path ").append(GuestExec.shellQuote(packageName))
                append(" | sed 's/^package://'); do\n")
                append("  i=\$((i+1))\n")
                append("  out=\"\$dir/").append(safe).append("-\$i.apk\"\n")
                append("  cp -f \"\$p\" \"\$out\" || exit 4\n")
                append("  echo \"\$out\"\n")
                append("done\n")
                append("[ \"\$i\" -gt 0 ] || { echo 'package not installed'; exit 5; }\n")
                if (nativeDir.isNotBlank()) {
                    append("libs=\"\$dir/").append(safe).append("-libs\"\n")
                    append("mkdir -p \"\$libs\" || exit 6\n")
                    append("cp -f ").append(GuestExec.shellQuote(nativeDir))
                    append("/*.so \"\$libs/\" 2>/dev/null\n")
                    // Print what was copied, so the agent gets paths rather than
                    // having to list the directory again.
                    append("for f in \"\$libs\"/*.so; do [ -f \"\$f\" ] && echo \"\$f\"; done\n")
                }
            }
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
