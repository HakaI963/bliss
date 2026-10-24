package com.altaris.linux.core.rea

import android.content.Context
import android.util.Log
import com.altaris.linux.core.control.ControlServer
import com.altaris.linux.core.terminal.DistroFsCache
import com.altaris.linux.core.terminal.HostCommandBuilder
import com.altaris.linux.core.terminal.ShellCommandRunner
import com.altaris.linux.core.terminal.TermuxHostPaths
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
 *   /opt/altaris/rea/node      Node 22 runtime when the distro ships an old one
 *   /opt/altaris/rea/npm       npm prefix holding `rea-agents`
 *   /opt/altaris/rea/rea.log   stdio server log
 *
 * The stdio process is owned by [ReaStdio]; this object only orchestrates.
 */
object ReaManager {

    const val ROOT = "/opt/altaris/rea"
    const val NODE_BIN = ROOT + "/node/bin/node"
    const val REA_BIN = ROOT + "/npm/bin/rea"
    const val LOG = ROOT + "/rea.log"
    const val NODE_VERSION = "v22.23.4"
    const val REA_VERSION = "6.7.0"
    const val MCP_URL = "http://127.0.0.1:47831/mcp"

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
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    private val mutex = Mutex()

    @Volatile private var active: ReaStdio? = null
    @Volatile private var toolsCache: JSONArray? = null

    /** Installed distro ids, cheapest source the app already maintains. */
    suspend fun installedDistros(ctx: Context): List<String> = withContext(Dispatchers.IO) {
        runCatching { DistroFsCache.get(ctx.applicationContext).installed.sorted() }
            .getOrDefault(emptyList())
    }

    fun defaultDistro(ids: List<String>): String? =
        ids.firstOrNull { it == "debian" } ?: ids.firstOrNull()

    fun cachedTools(): JSONArray? = toolsCache

    fun isReaTool(name: String): Boolean {
        val tools = toolsCache ?: return false
        for (i in 0 until tools.length()) {
            if (tools.optJSONObject(i)?.optString("name") == name) return true
        }
        return false
    }

    // ------------------------------------------------------------- probe

    suspend fun probe(ctx: Context, distroId: String): Status = withContext(Dispatchers.IO) {
        val script = listOf(
            "echo arch=\$(uname -m)",
            "if [ -x " + REA_BIN + " ]; then echo rea=\$(PATH=" + ROOT + "/node/bin:\$PATH " + REA_BIN + " --version 2>/dev/null | head -n1); else echo rea=; fi",
            "if [ -x " + NODE_BIN + " ]; then echo node=\$(" + NODE_BIN + " -v 2>/dev/null); " +
                "elif command -v node >/dev/null 2>&1; then echo node=\$(node -v 2>/dev/null); else echo node=; fi",
            "if command -v apt-get >/dev/null 2>&1; then echo pm=apt; " +
                "elif command -v apk >/dev/null 2>&1; then echo pm=apk; " +
                "elif command -v dnf >/dev/null 2>&1; then echo pm=dnf; " +
                "elif command -v pacman >/dev/null 2>&1; then echo pm=pacman; else echo pm=none; fi",
            "for b in jadx apktool aapt adb objdump gdb rizin python3 binwalk 7z tshark sqlite3 file java; do " +
                "if command -v \$b >/dev/null 2>&1; then echo bin:\$b=1; else echo bin:\$b=0; fi; done",
        ).joinToString("\n")

        val result = GuestExec.run(ctx, distroId, script, timeoutMs = 60_000L)
        val values = HashMap<String, String>()
        val available = HashSet<String>()
        for (line in result.output.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("bin:")) {
                val name = trimmed.removePrefix("bin:").substringBefore('=')
                if (trimmed.endsWith("=1")) available.add(name)
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
            available = available,
            toolCount = toolsCache?.length() ?: 0,
            error = if (result.exit == 0) "" else result.tail(3),
        )
        _status.value = next
        next
    }

    // ------------------------------------------------------------- install

    suspend fun install(
        ctx: Context,
        distroId: String,
        dependencies: List<String> = emptyList(),
        onLog: (String) -> Unit = {},
    ): Status = withContext(Dispatchers.IO) {
        mutex.withLock {
            val app = ctx.applicationContext
            // Must be read before the status copy below: that copy rewrites
            // distroId, which would make a fresh distro look installed.
            val alreadyInstalled = _status.value.distroId == distroId && _status.value.installed
            update { it.copy(distroId = distroId, method = GuestExec.methodOf(distroId), busy = "Installing REA", log = "", error = "") }
            val log = StringBuilder()
            fun line(text: String) {
                synchronized(log) {
                    log.append(text).append('\n')
                    if (log.length > 24_000) log.delete(0, log.length - 20_000)
                }
                onLog(text)
                update { it.copy(log = synchronized(log) { log.toString() }) }
            }

            val needsCore = !alreadyInstalled
            if (needsCore) {
                val core = installScript()
                val coreResult = GuestExec.run(app, distroId, core, timeoutMs = 45 * 60_000L) { line(it) }
                if (!coreResult.ok) {
                    val failure = coreResult.tail(12)
                    update { it.copy(busy = "", error = "REA install failed", log = failure) }
                    return@withLock _status.value
                }
            } else {
                line("REA already installed in " + distroId)
            }

            for (id in dependencies) {
                val dep = ReaCatalog.byId(id) ?: continue
                if (dep.desktopOnly) {
                    line("skip " + dep.label + ": desktop-only (" + dep.note + ")")
                    continue
                }
                val manager = _status.value.packageManager
                val packages = ReaCatalog.packagesFor(dep, manager)
                if (packages.isNullOrEmpty()) {
                    line("skip " + dep.label + ": not packaged for " + manager)
                    continue
                }
                line("install " + dep.label + " via " + manager + ": " + packages.joinToString(" "))
                val depResult = GuestExec.run(
                    app,
                    distroId,
                    dependencyScript(manager, packages),
                    timeoutMs = 20 * 60_000L,
                ) { line(it) }
                if (!depResult.ok) {
                    line("warning: " + dep.label + " failed: " + depResult.tail(4))
                }
            }

            update { it.copy(busy = "") }
            probe(app, distroId)
        }
    }

    private fun installScript(): String = listOf(
        "set -e",
        "BASE=" + ROOT,
        "mkdir -p \"\$BASE\"",
        "ARCH=\$(uname -m)",
        "if ! command -v curl >/dev/null 2>&1 && ! command -v wget >/dev/null 2>&1; then " +
            "if command -v apt-get >/dev/null 2>&1; then apt-get update -qq && apt-get install -y --no-install-recommends curl ca-certificates; " +
            "elif command -v apk >/dev/null 2>&1; then apk add --no-cache curl ca-certificates; fi; fi",
        "case \"\$ARCH\" in aarch64|arm64) NARCH=arm64;; x86_64|amd64) NARCH=x64;; *) echo \"unsupported arch: \$ARCH\"; exit 3;; esac",
        "NODE=\"\"",
        "NPM=\"\"",
        "if [ -x \"\$BASE/node/bin/node\" ] && [ -x \"\$BASE/node/bin/npm\" ]; then NODE=\"\$BASE/node/bin/node\"; NPM=\"\$BASE/node/bin/npm\"; fi",
        "if [ -z \"\$NODE\" ] && command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1; then V=\$(node -v 2>/dev/null | sed 's/^v//' | cut -d. -f1); if [ -n \"\$V\" ] && [ \"\$V\" -ge 22 ]; then NODE=\$(command -v node); NPM=\$(command -v npm); fi; fi",
        "if [ -z \"\$NODE\" ] && [ -f /etc/alpine-release ]; then apk add --no-cache nodejs npm >/dev/null 2>&1 || true; if command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1; then V=\$(node -v 2>/dev/null | sed 's/^v//' | cut -d. -f1); if [ -n \"\$V\" ] && [ \"\$V\" -ge 22 ]; then NODE=\$(command -v node); NPM=\$(command -v npm); fi; fi; fi",
        "if [ -z \"\$NODE\" ]; then if [ -f /etc/alpine-release ]; then URL=\"https://unofficial-builds.nodejs.org/download/release/" + NODE_VERSION + "/node-" + NODE_VERSION + "-linux-\$NARCH-musl.tar.gz\"; else URL=\"https://nodejs.org/dist/" + NODE_VERSION + "/node-" + NODE_VERSION + "-linux-\$NARCH.tar.gz\"; fi; echo \"downloading \$URL\"; if command -v curl >/dev/null 2>&1; then curl -fsSL \"\$URL\" -o /tmp/altaris-node.tar.gz; else wget -qO /tmp/altaris-node.tar.gz \"\$URL\"; fi; rm -rf \"\$BASE/node\" \"\$BASE/node.tmp\"; mkdir -p \"\$BASE/node.tmp\"; tar -xzf /tmp/altaris-node.tar.gz -C \"\$BASE/node.tmp\" --strip-components=1; mv \"\$BASE/node.tmp\" \"\$BASE/node\"; NODE=\"\$BASE/node/bin/node\"; NPM=\"\$BASE/node/bin/npm\"; fi",
        "\"\$NODE\" -v",
        "export PATH=\"\$(dirname \"\$NODE\"):\$PATH\"",
        "export npm_config_cache=\"\$BASE/.npm\"",
        "mkdir -p \"\$BASE/npm\"",
        "\"\$NPM\" install -g rea-agents@" + REA_VERSION + " --prefix \"\$BASE/npm\" --no-audit --no-fund --loglevel=error",
        "\"\$BASE/npm/bin/rea\" --version",
        "touch \"\$BASE/.installed\"",
    ).joinToString("\n")

    private fun dependencyScript(manager: String, packages: List<String>): String {
        val pkgs = packages.joinToString(" ") { GuestExec.shellQuote(it) }
        return when (manager) {
            "apt" -> "export DEBIAN_FRONTEND=noninteractive\napt-get update -qq\napt-get install -y --no-install-recommends " + pkgs
            "apk" -> "apk add --no-cache " + pkgs
            "dnf" -> "dnf install -y " + pkgs
            "pacman" -> "pacman -Sy --noconfirm " + pkgs
            else -> "echo 'no supported package manager'; exit 4"
        }
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
            val rootScript = listOf(
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
            val su = com.altaris.linux.core.root.RootShell.captureResult(rootScript, 60_000L)
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
