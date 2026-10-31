package com.altaris.linux.core.control

import android.content.Context
import android.util.Log
import com.altaris.linux.core.chroot.ChrootDetection
import com.altaris.linux.core.chroot.ChrootInfoStore
import com.altaris.linux.core.install.DistroInstallProfile
import com.altaris.linux.core.root.RootShell
import com.altaris.linux.core.terminal.DistroFsCache
import com.altaris.linux.core.terminal.TermuxHostPaths
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
 * Discovery for agent CLIs installed in the host prefix or in any proot/chroot
 * distro.
 *
 * Three rules, each of which used to be a bug:
 *
 *  1. **No allowlist.** Any executable in an agent bin directory is a candidate
 *     unless it is a known system/coreutil name. A fixed list of eleven names
 *     silently hid every agent the list did not know about, which is the worst
 *     possible failure for a "master of your agents".
 *  2. **Chroot is read through root.** A chroot rootfs lives under
 *     `/data/local/…`, which this process cannot read (SELinux
 *     `shell_data_file`). A plain `File.list()` therefore returns nothing and
 *     every chroot agent looked missing. When direct listing fails, the same
 *     directories are listed through [RootShell].
 *  3. **Named instances are their own distro.** A `debian-work` install has its
 *     own container; resolving it through the stock `debian` profile looked at
 *     the wrong rootfs. Installed ids come from [DistroFsCache], so every
 *     instance is scanned where it actually lives.
 */
object AgentDetector {

    private const val TAG = "AgentDetector"

    /**
     * Directories (relative to a rootfs, or to the host prefix) where an agent
     * CLI is conventionally installed.
     */
    private val AGENT_DIRS = listOf(
        "usr/local/bin",
        "usr/bin",
        "usr/local/sbin",
        "root/.local/bin",
        "root/.npm-global/bin",
        "root/.opencode/bin",
        "root/.claude/local",
        "root/.bun/bin",
        "root/.cargo/bin",
        "root/.local/share/pnpm",
        "root/.local/share/npm/bin",
        "root/bin",
        "bin",
    )

    /** Per-user agent directories, relative to a home directory. */
    private val HOME_AGENT_DIRS = listOf(
        ".local/bin",
        ".npm-global/bin",
        ".opencode/bin",
        ".claude/local",
        ".bun/bin",
        ".cargo/bin",
        ".local/share/pnpm",
        ".local/share/npm/bin",
        "bin",
    )

    /**
     * System binaries that are never agents.
     *
     * Deliberately generous: a false negative only means one extra row in the
     * picker, while a false positive here would hide a real agent — so this is
     * an exact-name list of coreutils and shell built-ins, not a prefix rule.
     */
    private val SYSTEM_BINARIES = setOf(
        // coreutils / shell
        "sh", "bash", "dash", "zsh", "fish", "ash", "csh", "tcsh", "ksh", "rbash",
        "ls", "cat", "cp", "mv", "rm", "ln", "echo", "printf", "pwd", "true", "false",
        "test", "[", "expr", "seq", "yes", "sleep", "env", "id", "whoami", "groups",
        "chmod", "chown", "chgrp", "mkdir", "rmdir", "touch", "stat", "dd", "sync",
        "head", "tail", "sort", "uniq", "wc", "cut", "tr", "sed", "awk", "grep",
        "egrep", "fgrep", "find", "xargs", "tee", "split", "paste", "join", "comm",
        "diff", "cmp", "patch", "tar", "gzip", "gunzip", "zcat", "xz", "unxz",
        "bzip2", "unzip", "zip", "cpio", "basename", "dirname", "realpath",
        "readlink", "mktemp", "install", "du", "df", "mount", "umount", "su", "sudo",
        "login", "passwd", "useradd", "usermod", "groupadd", "chsh", "date",
        "cal", "hostname", "uname", "arch", "nproc", "free", "uptime", "ps", "top",
        "kill", "killall", "pkill", "nice", "renice", "timeout", "time", "watch",
        "screen", "tmux", "nohup", "setsid", "stty", "tty", "clear", "reset",
        "less", "more", "vi", "vim", "nvim", "nano", "ed", "emacs", "man", "info",
        "which", "whereis", "type", "command", "hash", "alias", "unalias", "set",
        "unset", "export", "read", "source", "eval", "exec", "exit", "return",
        "shift", "trap", "wait", "jobs", "bg", "fg", "history", "ulimit", "umask",
        "printf", "getopt", "logger", "dmesg", "sysctl", "modprobe", "insmod",
        "rmmod", "lsmod", "iptables", "ip", "ifconfig", "route", "ping", "ping6",
        "curl", "wget", "nc", "netcat", "telnet", "ssh", "scp", "sftp", "rsync",
        "ftp", "dig", "nslookup", "host", "ss", "netstat", "lsof", "strace",
        "ltrace", "gdb", "readelf", "objdump", "nm", "strings", "strip", "ar",
        "as", "ld", "make", "cmake", "ninja", "gcc", "g++", "cc", "clang",
        "clang++", "ldd", "file", "xxd", "hexdump", "od", "base64", "md5sum",
        "sha1sum", "sha256sum", "sha512sum", "cksum", "gpg", "openssl", "tar",
        // language runtimes and their tooling
        "python", "python2", "python3", "pip", "pip3", "node", "nodejs", "npm",
        "npx", "yarn", "pnpm", "bun", "deno", "ruby", "gem", "bundle", "perl",
        "php", "java", "javac", "jar", "javadoc", "jshell", "keytool", "mvn",
        "gradle", "kotlin", "kotlinc", "go", "gofmt", "rustc", "cargo", "rustup",
        "dotnet", "mono", "lua", "luajit", "R", "Rscript", "sqlite3", "psql",
        "mysql", "redis-cli", "jq", "yq", "git", "gh", "svn", "hg", "mercurial",
        // system services / package managers
        "apt", "apt-get", "apt-cache", "apt-key", "dpkg", "apk", "dnf", "yum",
        "rpm", "pacman", "zypper", "emerge", "nix", "nix-env", "snap", "flatpak",
        "systemctl", "service", "init", "busybox", "toybox", "proot",
        "proot-distro", "termux-info", "termux-setup-storage", "altaris",
        "altaris-open", "altaris-cli", "xdg-open", "sensible-browser",
        "dbus-launch", "dbus-daemon", "Xvnc", "Xvfb", "startxfce4", "xfce4-session",
        "pulseaudio", "pipewire", "alsactl", "logcat", "am", "pm", "cmd", "dumpsys",
        "input", "settings", "svc", "getprop", "setprop", "wm", "monkey",
        "tesseract", "ffmpeg", "ffprobe", "convert", "magick", "identify",
        "jadx", "jadx-gui", "apktool", "aapt", "aapt2", "d8", "dx", "zipalign",
        "apksigner", "keytool", "adb", "fastboot", "ghidra", "analyzeHeadless",
        "rizin", "radare2", "r2", "rabin2", "rasm2", "rahash2", "binwalk",
        "7z", "7za", "7zr", "p7zip", "sqlmap", "nmap", "tcpdump", "aircrack-ng",
        "metasploit", "msfconsole", "wireshark", "tshark", "objcopy", "patchelf",
        "upx", "dexdump", "baksmali", "smali", "apkid", "frida", "frida-ps",
        "frida-server", "objection", "mitmproxy", "mitmdump", "mitmweb",
    )

    /** Host-prefix binaries, for agents running outside any distro. */
    fun scan(context: Context): List<DetectedAgent> {
        val found = LinkedHashMap<String, DetectedAgent>()
        val hostDirs = ArrayList<File>()
        hostDirs.add(File(TermuxHostPaths.BIN))
        val home = File(TermuxHostPaths.HOME)
        for (dir in HOME_AGENT_DIRS) hostDirs.add(File(home, dir))
        for (dir in hostDirs) scanDir(dir, found, "host cli")
        return found.values.sortedWith(compareBy({ it.kind }, { it.name.lowercase() }))
    }

    /**
     * Every installed distro (including named instances), with the agent CLIs
     * found in its rootfs.
     *
     * Hidden distros are skipped: the REA analysis environment is not a user
     * distro and must never appear in the agent matrix or in Quinn's picker.
     */
    fun scanDistros(context: Context): List<DistroAgentReport> {
        val app = context.applicationContext
        val reports = ArrayList<DistroAgentReport>()
        val installed = runCatching { DistroFsCache.get(app).installed }
            .getOrDefault(emptySet())
        for (distroId in installed.sorted()) {
            if (DistroInstallProfile.isHidden(distroId)) continue
            val profile = DistroInstallProfile.forId(distroId) ?: continue
            val found = LinkedHashMap<String, DetectedAgent>()
            if (profile.method == "chroot") {
                val path = profile.chrootPath ?: continue
                if (!chrootPresent(app, profile.distroId, path)) continue
                scanChroot(path, found)
            } else {
                if (profile.prootName.isBlank()) continue
                val rootfs = File(
                    app.filesDir,
                    "usr/var/lib/proot-distro/containers/${profile.prootName}/rootfs"
                )
                if (!rootfs.isDirectory) continue
                scanRootfs(rootfs, found)
            }
            reports.add(
                DistroAgentReport(
                    id = profile.distroId,
                    name = profile.displayName.ifBlank { distroId },
                    method = profile.method,
                    agents = found.values.distinctBy { it.name }.sortedBy { it.name.lowercase() },
                )
            )
        }
        return reports.sortedBy { it.name.lowercase() }
    }

    private fun chrootPresent(context: Context, distroId: String, path: String): Boolean =
        ChrootDetection.isInstalled(path) || ChrootInfoStore.cachedInstalled(context, distroId)

    /** Scan a readable proot rootfs. */
    private fun scanRootfs(rootfs: File, found: MutableMap<String, DetectedAgent>) {
        for (dir in AGENT_DIRS) scanDir(File(rootfs, dir), found, "guest cli")
        File(rootfs, "root").let { scanDir(it, found, "guest cli", HOME_AGENT_DIRS) }
        File(rootfs, "home").listFiles()?.forEach { home ->
            scanDir(home, found, "guest cli", HOME_AGENT_DIRS)
        }
    }

    /**
     * Scan a chroot rootfs through root.
     *
     * One `find` covers every candidate directory, so this is a single `su`
     * round trip rather than one per directory. If root is unavailable the
     * scan yields nothing rather than reporting a misleading "no agents".
     */
    private fun scanChroot(path: String, found: MutableMap<String, DetectedAgent>) {
        if (!RootShell.isRootAvailable()) {
            Log.w(TAG, "chroot scan skipped: root unavailable for $path")
            return
        }
        val dirs = ArrayList<String>()
        for (dir in AGENT_DIRS) dirs.add("$path/$dir")
        // Per-user homes: one level deep, matching the proot scan.
        dirs.add("$path/root")
        val findExpr = buildString {
            append("for d in")
            for (dir in dirs) append(' ').append(quote(dir))
            append("; do [ -d \"\$d\" ] && find \"\$d\" -maxdepth 1 -type f -perm -u+x -printf '%p\\n' 2>/dev/null; done; ")
            append("for h in ")
            append(quote("$path/home")).append("/*; do ")
            append("[ -d \"\$h\" ] || continue; ")
            append("for d in ")
            for (dir in HOME_AGENT_DIRS) append(quote(dir)).append(' ')
            append("; do [ -d \"\$h/\$d\" ] && find \"\$h/\$d\" -maxdepth 1 -type f -perm -u+x -printf '%p\\n' 2>/dev/null; done; ")
            append("done")
        }
        val result = runCatching { RootShell.captureResult(findExpr, 45_000L) }
            .getOrElse { return }
        if (result.exitCode != 0 || result.stdout.isBlank()) return
        for (line in result.stdout.lines()) {
            val absolute = line.trim()
            if (absolute.isBlank()) continue
            val name = absolute.substringAfterLast('/')
            if (!isAgentName(name)) continue
            found["cli:guest cli:$absolute"] = DetectedAgent(
                name = name,
                detail = absolute,
                kind = "guest cli",
            )
        }
    }

    /** Scan [dir] for agent executables; optionally scan sub-directories of [dir]. */
    private fun scanDir(
        dir: File,
        found: MutableMap<String, DetectedAgent>,
        kind: String,
        subDirs: List<String>? = null,
    ) {
        if (subDirs != null) {
            for (sub in subDirs) scanDir(File(dir, sub), found, kind)
            return
        }
        if (!dir.isDirectory) return
        val entries = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (file in entries) {
            val name = file.name
            if (!isAgentName(name)) continue
            val executable = runCatching { file.isFile && file.canExecute() }.getOrDefault(false)
            if (!executable) continue
            found["cli:$kind:${file.absolutePath}"] = DetectedAgent(
                name = name,
                detail = file.absolutePath,
                kind = kind,
            )
        }
    }

    /**
     * True when [name] could be an agent CLI.
     *
     * Excludes known system binaries and the usual packaging noise (shared
     * libraries, version-suffixed runtime binaries) while accepting anything
     * unknown — a new agent must appear without a code change.
     */
    private fun isAgentName(name: String): Boolean {
        if (name.isBlank()) return false
        if (name.startsWith(".")) return false
        if (name.endsWith(".so") || name.contains(".so.")) return false
        if (name.endsWith(".pyc") || name.endsWith(".h") || name.endsWith(".a")) return false
        if (name.contains("-config")) return false
        if (SYSTEM_BINARIES.contains(name)) return false
        // Version-suffixed runtime binaries (`python3.12`, `node18`) are not agents.
        val stem = name.trimEnd { it.isDigit() || it == '.' }
        if (stem != name && SYSTEM_BINARIES.contains(stem)) return false
        return true
    }

    /**
     * Test seam for [isAgentName].
     *
     * The name rule is the part that decides whether a new agent shows up at
     * all, so it is asserted without a device; the directory walk around it
     * only needs a filesystem.
     */
    internal fun isAgentNameForTest(name: String): Boolean = isAgentName(name)

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
