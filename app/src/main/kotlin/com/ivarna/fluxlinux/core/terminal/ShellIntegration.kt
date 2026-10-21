package com.ivarna.fluxlinux.core.terminal

import android.content.Context
import android.util.Log
import com.ivarna.fluxlinux.BuildConfig
import com.ivarna.fluxlinux.core.install.ProotXfceAssetInstaller
import com.ivarna.fluxlinux.core.root.ChrootPaths
import com.ivarna.fluxlinux.core.utils.RootUtils
import java.io.File

/**
 * Vendored ble.sh integration for every **interactive bash** session.
 *
 * ble.sh is a pure-Bash line editor that gives bash what
 * zsh-autosuggestions / zsh-syntax-highlighting give zsh:
 *  - live syntax coloring (green = resolvable command, red = unknown/half-typed),
 *  - inline history suggestion after the cursor (Right arrow / End accepts),
 *  - ble.sh's completion menu (Tab).
 *
 * The released v0.4 artifact is **not** a single self-contained file: the
 * generated `ble.sh` loads runtime data from `lib/` and `keymap/` next to
 * itself. The whole release tree is vendored under `assets/shell/` and copied
 * as a directory; [ShellIntegrationTargets.isShareCurrent] rejects partial
 * trees so a lone `ble.sh` can never masquerade as an install.
 *
 * Wiring (marker-guarded, idempotent, never clobbers user content):
 *  - host prefix: `<filesDir>/usr/share/bliss/` + a block in the generated
 *    host `~/.bashrc` (see [TermuxHostPaths]).
 *  - PRoot guests: `<rootfs>/usr/local/share/bliss/` +
 *    `<rootfs>/etc/profile.d/bliss-shell.sh` (login shells) + the distro's
 *    system bashrc + `~/.bashrc` for root and every home user (interactive
 *    non-login bash) — see [ShellIntegrationTargets]. The pure rootfs logic is
 *    shared with the chroot installer below.
 *  - chroot guests (`/data/local/bliss`, root-only): the same tree is staged
 *    under `<filesDir>/clone-stage/` and applied by one `su` helper pass over
 *    `<filesDir>/shell-integration-report.txt`. The pass is stamped per app
 *    version but only when at least one chroot was actually wired, so a
 *    chroot installed after the first app start still gets wired on the next
 *    start (round-12 fix: previously a pass with zero chroots stamped itself
 *    "done" and Alpine never got `/etc/bash/bashrc` — its bash is built with
 *    `-DSYS_BASHRC=/etc/bash/bashrc`, not `/etc/bash.bashrc`).
 *
 * [reportLines] exposes the result (host + every PRoot rootfs + every chroot)
 * for the Tools > Diagnostics UI; chroot lines come from the persisted report
 * because `/data/local/bliss` is not readable by the app.
 *
 * IMPORTANT (verified in a pty): `source ble.sh 2>/dev/null` disables the
 * line editor in v0.4.0-devel3 (stderr must stay a tty while sourcing).
 * Never redirect stderr on the `source` line.
 *
 * [ensureInstalled] is cheap + idempotent (version stamp + stat checks), safe
 * to call on every app start from a background thread.
 */
object ShellIntegration {

    private const val TAG = "ShellIntegration"

    /** Assets root of the vendored runtime: `ble.sh` + `lib/` + `keymap/`. */
    private const val ASSET_DIR = "shell"

    /** Host `$PREFIX` share dir (relative to filesDir). */
    private const val HOST_SHARE_REL = "usr/share/bliss"

    // Shared spec (pure JVM) — constants and rootfs wiring rules live in
    // ShellIntegrationTargets so ShellIntegration.kt and the POSIX chroot
    // installer script cannot drift apart.
    private const val CONF_NAME = ShellIntegrationTargets.CONF_NAME
    private const val STAMP_NAME = ShellIntegrationTargets.STAMP_NAME
    private const val RC_BLOCK_STAGE_NAME = ShellIntegrationTargets.RC_BLOCK_STAGE_NAME
    private const val BLESH_RELEASE = ShellIntegrationTargets.BLESH_RELEASE
    private const val MIN_BLE_SH_BYTES = ShellIntegrationTargets.MIN_BLE_SH_BYTES
    private val BLE_CONF = ShellIntegrationTargets.BLE_CONF
    private val GUEST_RC_BLOCK = ShellIntegrationTargets.GUEST_RC_BLOCK

    private const val PREFS = "bliss_shell_integration"
    private const val KEY_CHROOT = "chroot_installed"

    /**
     * Bump to force one extra chroot pass on existing installs (wiring
     * generation — round-12 added `/etc/bash/bashrc` + `~/.bashrc` targets, so
     * installs stamped by the round-11 wiring must be re-wired at least once).
     */
    private const val CHROOT_WIRING_GEN = 2

    /** Persisted chroot pass result — read (never written) by [reportLines]. */
    private const val REPORT_NAME = "shell-integration-report.txt"

    /** Staging dir for the one-shot root pass. */
    private const val STAGE_REL = "clone-stage/bliss-shell"

    /** Staged runtime subtree inside [STAGE_REL]. */
    private const val STAGE_BLE = "blesh"

    /** All chroot rootfs paths shipped by the app. */
    private val CHROOT_ROOTS = listOf(
        ChrootPaths.DEBIAN_CHROOT_PATH,
        ChrootPaths.ALPINE_CHROOT_PATH,
        ChrootPaths.FEDORA_CHROOT_PATH,
        ChrootPaths.VOID_CHROOT_PATH,
        ChrootPaths.OPENSUSE_CHROOT_PATH,
        ChrootPaths.DEEPIN_CHROOT_PATH,
        ChrootPaths.CHIMERA_CHROOT_PATH,
        ChrootPaths.MANJARO_CHROOT_PATH,
        ChrootPaths.UBUNTU_CHROOT_PATH,
        ChrootPaths.KALI_CHROOT_PATH,
        ChrootPaths.PARROT_CHROOT_PATH,
        ChrootPaths.ARCH_CHROOT_PATH
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Install / refresh the ble.sh integration everywhere it can go.
     * Cheap when already installed; invokes `su` only for the chroot pass
     * (once per app version, or once more until at least one chroot is wired).
     * Background thread only (blocking file I/O).
     */
    @Synchronized
    fun ensureInstalled(app: Context) {
        val ctx = app.applicationContext
        try {
            ensureHostPrefix(ctx)
        } catch (e: Exception) {
            Log.w(TAG, "host ble.sh install failed: ${e.message}")
        }
        try {
            ensureProotGuests(ctx)
        } catch (e: Exception) {
            Log.w(TAG, "proot ble.sh install failed: ${e.message}")
        }
        try {
            ensureChrootGuests(ctx)
        } catch (e: Exception) {
            Log.w(TAG, "chroot ble.sh install failed: ${e.message}")
        }
    }

    /**
     * Live report for the Tools > Diagnostics UI — one line per target:
     *
     *  - `host: ble.sh ok, rc wired (<filesDir>/home/.bashrc)`
     *  - `proot alpine: ble.sh ok, rc wired (/etc/profile.d/bliss-shell.sh, …)`
     *  - `chroot alpine: ble.sh ok, rc wired (/etc/bash/bashrc, /root/.bashrc)`
     *  - `chroot pass: skipped (no root)` / `chroot pass: not run` /
     *    `chroot <name>: missing rootfs` / `…: error: <detail>`
     *
     * Host + PRoot lines are computed live (stat checks only); chroot lines
     * are read from the persisted `su` pass result — the chroot tree lives
     * under `/data/local/bliss` and is not readable by the app process.
     */
    fun reportLines(app: Context): List<String> {
        val ctx = app.applicationContext
        val out = ArrayList<String>()
        try {
            out.addAll(hostReportLines(ctx))
        } catch (e: Exception) {
            out.add("host: check failed: ${e.message}")
        }
        try {
            out.addAll(prootReportLines(ctx))
        } catch (e: Exception) {
            out.add("proot: check failed: ${e.message}")
        }
        try {
            out.addAll(chrootReportLines(ctx))
        } catch (e: Exception) {
            out.add("chroot pass: check failed: ${e.message}")
        }
        return out
    }

    /** Host prefix share dir: `<filesDir>/usr/share/bliss`. */
    internal fun hostShareDir(filesDir: File): File = File(filesDir, HOST_SHARE_REL)

    // ─────────────────────────────────────────────────────────────────────────
    // Host prefix
    // ─────────────────────────────────────────────────────────────────────────

    private fun ensureHostPrefix(ctx: Context) {
        val share = hostShareDir(ctx.filesDir)
        ensureShareTree(ctx, share)
        // ble.sh prefers $HOME/.cache/blesh when it exists; create it so the
        // cache never has to live inside the (potentially read-only) prefix.
        File(ctx.filesDir, "home/.cache").mkdirs()
        Log.i(TAG, "host ble.sh ready at ${share.absolutePath}")
    }

    private fun ensureShareTree(ctx: Context, share: File) {
        if (!ShellIntegrationTargets.isShareCurrent(share)) {
            copyAssetTree(ctx, ASSET_DIR, share)
            ShellIntegrationTargets.writeStamp(share)
            val bleSh = File(share, "ble.sh")
            if (bleSh.length() < MIN_BLE_SH_BYTES) {
                Log.w(TAG, "vendored ble.sh looks truncated: ${bleSh.length()} bytes")
            }
        }
        ShellIntegrationTargets.writeTextIfDifferent(File(share, CONF_NAME), BLE_CONF)
        // Fallback cache used when $HOME/.cache/blesh is unavailable: ble.sh
        // writes $_ble_base/cache.d/$UID — it must be writable by the shell.
        File(share, "cache.d").mkdirs()
        runCatching {
            share.setReadable(true, false)
            share.setExecutable(true, false)
            File(share, "cache.d").setReadable(true, false)
            File(share, "cache.d").setWritable(true, false)
            File(share, "cache.d").setExecutable(true, false)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRoot guests
    // ─────────────────────────────────────────────────────────────────────────

    private fun ensureProotGuests(ctx: Context) {
        val hostShare = hostShareDir(ctx.filesDir)
        for ((name, rootfs) in prootRoots(ctx)) {
            try {
                if (!File(rootfs, "etc").isDirectory && !File(rootfs, "usr").isDirectory) continue
                // Pure installer: stages the full release tree from the host
                // share (stamp-guarded) and wires profile.d + system bashrc +
                // ~/.bashrc for root and every home user.
                val lines = ShellIntegrationTargets.installIntoRoot(rootfs, hostShare)
                Log.i(TAG, "proot $name: ${lines.joinToString(" | ")}")
            } catch (e: Exception) {
                Log.w(TAG, "proot guest install failed ${rootfs.absolutePath}: ${e.message}")
            }
        }
    }

    /** Existing proot-distro container rootfs trees (both known layouts): name to rootfs. */
    private fun prootRoots(ctx: Context): List<Pair<String, File>> {
        val prootDistro = File(ctx.filesDir, "usr/var/lib/proot-distro")
        if (!prootDistro.isDirectory) return emptyList()
        val seen = HashSet<String>()
        val roots = ArrayList<Pair<String, File>>()
        File(prootDistro, "containers").listFiles()?.forEach { container ->
            if (!container.isDirectory) return@forEach
            // SSOT builder for the container rootfs path.
            val rootfs = ProotXfceAssetInstaller.prootRootfs(ctx, container.name)
            if (rootfs.isDirectory && seen.add(canonicalKey(rootfs))) {
                roots.add(container.name to rootfs)
            }
        }
        // Alternate/legacy layout: installed-rootfs/<name> → rootfs.
        File(prootDistro, "installed-rootfs").listFiles()?.forEach { rootfs ->
            if (rootfs.isDirectory && seen.add(canonicalKey(rootfs))) {
                roots.add(rootfs.name to rootfs)
            }
        }
        return roots
    }

    private fun canonicalKey(file: File): String = try {
        file.canonicalPath
    } catch (_: Exception) {
        file.absolutePath
    }

    private fun hostReportLines(ctx: Context): List<String> {
        val share = hostShareDir(ctx.filesDir)
        val bleSh = File(share, "ble.sh")
        val rc = TermuxHostPaths.hostShellRc(ctx)
        val rcWired = try {
            rc.isFile && rc.readText().contains("share/bliss/ble.sh")
        } catch (_: Exception) {
            false
        }
        return listOf(
            when {
                !bleSh.isFile || bleSh.length() < MIN_BLE_SH_BYTES ->
                    "host: ble.sh missing (${bleSh.absolutePath})"
                rcWired -> "host: ble.sh ok, rc wired (${rc.absolutePath})"
                else -> "host: ble.sh ok, rc missing (${rc.absolutePath})"
            }
        )
    }

    private fun prootReportLines(ctx: Context): List<String> {
        val roots = prootRoots(ctx)
        if (roots.isEmpty()) return listOf("proot: no rootfs installed")
        return roots.map { (name, rootfs) ->
            "proot $name: " + ShellIntegrationTargets.statusLines(rootfs).joinToString(" | ")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Chroot guests (root-only tree; staged via su)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Stage the runtime + helper script under `<filesDir>/clone-stage/` and run
     * one `su` pass over every chroot rootfs that exists. Root-unavailable is
     * recorded in the report file and retried next start. The pass stamps
     * itself done for the app version only when at least one chroot was
     * actually wired with no per-root errors.
     */
    private fun ensureChrootGuests(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "${BuildConfig.VERSION_CODE}:$CHROOT_WIRING_GEN"
        if (prefs.getString(KEY_CHROOT, "") == key) return
        if (!RootUtils.isRootAvailable()) {
            writeChrootReport(ctx, listOf("chroot pass: skipped (no root)"))
            Log.i(TAG, "no root — chroot shell integration skipped")
            return
        }
        val stage = File(ctx.filesDir, STAGE_REL)
        try {
            stage.mkdirs()
            copyAssetTree(ctx, ASSET_DIR, File(stage, STAGE_BLE))
            ShellIntegrationTargets.writeTextIfDifferent(
                File(stage, "$STAGE_BLE/$CONF_NAME"), BLE_CONF
            )
            ShellIntegrationTargets.writeStamp(File(stage, STAGE_BLE))
            ShellIntegrationTargets.writeTextIfDifferent(
                File(stage, RC_BLOCK_STAGE_NAME), GUEST_RC_BLOCK
            )
            ShellIntegrationTargets.writeTextIfDifferent(
                File(stage, "install.sh"), CHROOT_INSTALL_SCRIPT
            )
            val install = File(stage, "install.sh")
            val rootArgs = CHROOT_ROOTS.joinToString(" ") { "'$it'" }
            val cmd = "sh '${install.absolutePath}' '${stage.absolutePath}' $rootArgs"
            val result = RootUtils.runRootCommand(cmd)
            val lines = result.output.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("chroot ") }
                .toList()
            if (lines.isEmpty()) {
                val detail = result.error.ifBlank { "exit 0 with no per-root output" }
                writeChrootReport(ctx, listOf("chroot pass: error: ${detail.take(200)}"))
                Log.w(TAG, "chroot ble.sh install produced no report: $detail")
            } else {
                writeChrootReport(ctx, lines)
                val wired = lines.count { it.contains("ble.sh ok") }
                val errors = lines.count { it.contains("error:") }
                if (result.isSuccess && errors == 0 && wired > 0) {
                    prefs.edit().putString(KEY_CHROOT, key).apply()
                    Log.i(TAG, "chroot ble.sh integration applied ($wired root(s) wired)")
                } else {
                    Log.w(TAG, "chroot ble.sh integration incomplete (wired=$wired errors=$errors); will retry")
                }
            }
        } catch (e: Exception) {
            writeChrootReport(ctx, listOf("chroot pass: error: ${e.message}"))
            Log.w(TAG, "chroot ble.sh install failed: ${e.message}")
        } finally {
            runCatching { stage.deleteRecursively() }
        }
    }

    /** Persisted `<filesDir>/shell-integration-report.txt` (version-stamped). */
    private fun writeChrootReport(ctx: Context, lines: List<String>) {
        val text = buildString {
            append("# chroot pass — app ").append(BuildConfig.VERSION_CODE)
            append(", ble ").append(BLESH_RELEASE).append('\n')
            for (line in lines) append(line).append('\n')
        }
        runCatching { File(ctx.filesDir, REPORT_NAME).writeText(text) }
    }

    private fun chrootReportLines(ctx: Context): List<String> {
        val lines = try {
            File(ctx.filesDir, REPORT_NAME).readLines()
        } catch (_: Exception) {
            null
        } ?: return listOf("chroot pass: not run")
        val body = lines.filter { it.isNotBlank() && !it.startsWith("#") }
        return body.ifEmpty { listOf("chroot pass: not run") }
    }

    /**
     * POSIX sh helper executed as root (Android toybox shell) by
     * [ensureChrootGuests]. Mirrors [ShellIntegrationTargets] exactly:
     * whole-tree copy, `/etc/profile.d/bliss-shell.sh`, `/etc/bash/bashrc`
     * (Alpine / Void), `/etc/bash.bashrc` (Debian family), `/etc/bashrc`
     * (Fedora family), and `~/.bashrc` for root + every home user.
     */
    private val CHROOT_INSTALL_SCRIPT = """
        |#!/bin/sh
        |# Altaris chroot shell integration installer (root). Generated by ShellIntegration.kt.
        |# POSIX sh (Android toybox sh, dash, busybox ash). Idempotent: marker-guarded
        |# appends only — user rc content is never rewritten or reordered.
        |# Usage: sh install.sh <stage-dir> <rootfs> [rootfs...]
        |# One line per rootfs on stdout:
        |#   chroot <name>: ble.sh ok, rc wired (/etc/profile.d/bliss-shell.sh, ...)
        |#   chroot <name>: missing rootfs
        |#   chroot <name>: error: <detail>
        |stage=${'$'}1
        |shift
        |[ -n "${'$'}stage" ] && [ -d "${'$'}stage/blesh" ] || exit 1
        |block="${'$'}stage/rc-block.sh"
        |[ -f "${'$'}block" ] || exit 1
        |failed=0
        |wired=
        |
        |add_wired() {
        |  if [ -n "${'$'}wired" ]; then
        |    wired="${'$'}wired, ${'$'}1"
        |  else
        |    wired="${'$'}1"
        |  fi
        |}
        |
        |# Append the marker block to FILE unless it is already there.
        |append_marker() {
        |  _f=${'$'}1
        |  grep -q 'bliss shell integration v1' "${'$'}_f" 2>/dev/null && return 0
        |  if [ -s "${'$'}_f" ] && [ -n "${'$'}(tail -c 1 "${'$'}_f" 2>/dev/null)" ]; then
        |    printf '\n' >> "${'$'}_f" 2>/dev/null || return 1
        |  fi
        |  cat "${'$'}block" >> "${'$'}_f" 2>/dev/null || return 1
        |  return 0
        |}
        |
        |# Wire one rc FILE. ${'$'}2 = 1 creates a missing file (with a minimal comment).
        |# Returns 0 when the block is present afterwards.
        |wire_rc() {
        |  _f=${'$'}1
        |  if [ ! -f "${'$'}_f" ]; then
        |    [ "${'$'}2" = "1" ] || return 1
        |    mkdir -p "${'$'}{_f%/*}" 2>/dev/null || return 1
        |    printf '# Created by Altaris shell integration (ble.sh).\n' > "${'$'}_f" 2>/dev/null || return 1
        |  fi
        |  append_marker "${'$'}_f" || return 1
        |  chmod 644 "${'$'}_f" 2>/dev/null
        |  return 0
        |}
        |
        |for root in "${'$'}@"; do
        |  name=${'$'}{root##*/}
        |  if [ ! -d "${'$'}root/etc" ]; then
        |    printf 'chroot %s: missing rootfs\n' "${'$'}name"
        |    continue
        |  fi
        |  share=${'$'}root/usr/local/share/bliss
        |  mkdir -p "${'$'}share" 2>/dev/null || {
        |    printf 'chroot %s: error: cannot create %s\n' "${'$'}name" "${'$'}share"
        |    failed=1
        |    continue
        |  }
        |  # Whole release tree: ble.sh is not self-contained (lib/ + keymap/ required).
        |  # Skip the copy when the guest already carries the current stamp.
        |  stamp="${'$'}share/.bliss-shell-version"
        |  srcstamp="${'$'}stage/blesh/.bliss-shell-version"
        |  if [ -f "${'$'}stamp" ] && [ -f "${'$'}srcstamp" ] && [ -f "${'$'}share/ble.sh" ] && \
        |     [ -d "${'$'}share/lib" ] && [ -d "${'$'}share/keymap" ] && \
        |     [ "${'$'}(cat "${'$'}stamp" 2>/dev/null)" = "${'$'}(cat "${'$'}srcstamp" 2>/dev/null)" ]; then
        |    :
        |  elif ! cp -rf "${'$'}stage/blesh/." "${'$'}share/" 2>/dev/null; then
        |    printf 'chroot %s: error: cannot copy ble.sh tree to %s\n' "${'$'}name" "${'$'}share"
        |    failed=1
        |    continue
        |  fi
        |  chmod 755 "${'$'}share" 2>/dev/null
        |  chmod 644 "${'$'}share/ble.sh" "${'$'}share/bliss-ble-conf.sh" 2>/dev/null
        |  chmod -R a+rX "${'$'}share/lib" "${'$'}share/keymap" 2>/dev/null
        |  mkdir -p "${'$'}share/cache.d" 2>/dev/null
        |  chmod 1777 "${'$'}share/cache.d" 2>/dev/null
        |
        |  wired=
        |  # 1. Login shells: Alpine and Debian /etc/profile source /etc/profile.d/*.sh.
        |  mkdir -p "${'$'}root/etc/profile.d" 2>/dev/null
        |  if wire_rc "${'$'}root/etc/profile.d/bliss-shell.sh" 1; then
        |    chmod 755 "${'$'}root/etc/profile.d/bliss-shell.sh" 2>/dev/null
        |    add_wired /etc/profile.d/bliss-shell.sh
        |  fi
        |  # 2. Alpine / Void: bash SYS_BASHRC=/etc/bash/bashrc (interactive non-login;
        |  #    Alpine's login path reaches it via /etc/profile.d/00-bashrc.sh).
        |  if [ -f "${'$'}root/etc/bash/bashrc" ] || \
        |     { [ -f "${'$'}root/etc/alpine-release" ] && { [ -f "${'$'}root/bin/bash" ] || [ -f "${'$'}root/usr/bin/bash" ]; }; }; then
        |    wire_rc "${'$'}root/etc/bash/bashrc" 1 && add_wired /etc/bash/bashrc
        |  fi
        |  # 3. Debian family: /etc/bash.bashrc (also sourced by Debian's /etc/profile).
        |  if [ -f "${'$'}root/etc/bash.bashrc" ] || [ -f "${'$'}root/etc/debian_version" ]; then
        |    wire_rc "${'$'}root/etc/bash.bashrc" 1 && add_wired /etc/bash.bashrc
        |  fi
        |  # 4. Fedora / RHEL / openSUSE family: /etc/bashrc when the distro ships one.
        |  if [ -f "${'$'}root/etc/bashrc" ]; then
        |    wire_rc "${'$'}root/etc/bashrc" 0 && add_wired /etc/bashrc
        |  fi
        |  # 5. ~/.bashrc: read by every interactive non-login bash (root + home users).
        |  if [ -d "${'$'}root/root" ]; then
        |    wire_rc "${'$'}root/root/.bashrc" 1 && add_wired /root/.bashrc
        |    mkdir -p "${'$'}root/root/.cache" 2>/dev/null
        |  fi
        |  for home in "${'$'}root"/home/*; do
        |    [ -d "${'$'}home" ] || continue
        |    case "${'$'}{home##*/}" in .*) continue ;; esac
        |    if wire_rc "${'$'}home/.bashrc" 1; then
        |      add_wired "/home/${'$'}{home##*/}/.bashrc"
        |      chown --reference="${'$'}home" "${'$'}home/.bashrc" 2>/dev/null || chown 1000:1000 "${'$'}home/.bashrc" 2>/dev/null || true
        |    fi
        |    mkdir -p "${'$'}home/.cache" 2>/dev/null
        |    chown --reference="${'$'}home" "${'$'}home/.cache" 2>/dev/null || chown 1000:1000 "${'$'}home/.cache" 2>/dev/null || true
        |  done
        |
        |  if [ -n "${'$'}wired" ]; then
        |    printf 'chroot %s: ble.sh ok, rc wired (%s)\n' "${'$'}name" "${'$'}wired"
        |  else
        |    printf 'chroot %s: error: ble.sh staged but no rc file could be wired\n' "${'$'}name"
        |    failed=1
        |  fi
        |done
        |exit ${'$'}failed
        |""".trimMargin()

    // ─────────────────────────────────────────────────────────────────────────
    // Shared helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Recursively copy `assets/<assetDir>` into [destDir]. */
    private fun copyAssetTree(ctx: Context, assetDir: String, destDir: File) {
        val children = ctx.assets.list(assetDir) ?: return
        for (name in children) {
            val assetPath = "$assetDir/$name"
            val nested = ctx.assets.list(assetPath)
            if (!nested.isNullOrEmpty()) {
                copyAssetTree(ctx, assetPath, File(destDir, name))
            } else {
                val dest = File(destDir, name)
                dest.parentFile?.mkdirs()
                ctx.assets.open(assetPath).use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }
}
