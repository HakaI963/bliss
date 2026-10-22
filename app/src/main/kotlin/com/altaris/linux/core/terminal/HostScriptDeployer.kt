package com.altaris.linux.core.terminal

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Deploys host + distro shell scripts from assets into `$HOME` and the
 * termux-x11 loader APK.
 *
 * Host readiness (D6) = scripts + loader + bootstrap only. Rootfs archives are
 * **not** owned here anymore — [com.altaris.linux.core.install.RootfsDownloader]
 * fetches the selected distro's rootfs on demand from the GitHub release tag
 * `rootfs`. Ivarna also fetches `bootstrap_<applicationId>.tar` from that tag
 * (see [com.altaris.linux.core.install.HostBootstrap]).
 *
 * Fail-closed: a missing [HostScript.required] asset or loader makes
 * [deployScripts] return false so [TerminalLauncher.prepareHost] fails.
 */
object HostScriptDeployer {

    private const val TAG = "HostScriptDeployer"

    /**
     * Deploy marker (proot-opt-01): `home/.altarislinux/scripts.deployed` holds the
     * current [DEPLOY_VERSION]. Bump the version whenever the script list or any
     * deployed asset changes so existing installs re-deploy on the next cold run.
     * A current marker makes [deployScripts] a no-op — no 30-script asset copy,
     * no pulse overlay, no loader churn — on successive warm sessions.
     * v2: script assets renamed (altaris_install.sh / altaris_guest_common.sh / …).
     */
    internal const val DEPLOY_VERSION = 2
    private const val MARKER_REL = "home/.altarislinux/scripts.deployed"

    /** Marker file: content is the deployed [DEPLOY_VERSION]. */
    internal fun deployMarker(ctx: Context): File = File(ctx.filesDir, MARKER_REL)

    /** Version + install time: every app update (even same versionCode) redeploys scripts. */
    internal fun deployStamp(ctx: Context): String {
        val t = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime }
            .getOrDefault(0L)
        return "$DEPLOY_VERSION|$t"
    }

    internal fun isDeployed(ctx: Context): Boolean {
        val marker = deployMarker(ctx)
        return marker.isFile && marker.readText().trim() == deployStamp(ctx)
    }

    internal fun markDeployed(ctx: Context) {
        val marker = deployMarker(ctx)
        marker.parentFile?.mkdirs()
        marker.writeText(deployStamp(ctx))
    }

    /** Safe variant: a marker write failure must not fail an otherwise good deploy. */
    private fun markDeployedSafe(ctx: Context) {
        runCatching { markDeployed(ctx) }
    }

    private data class HostScript(
        val name: String,
        val assetPath: String,
        val required: Boolean = true
    )

    private val HOST_SCRIPTS: List<HostScript> = listOf(
        HostScript("setup_termux.sh", "scripts/host/setup_termux.sh"),
        HostScript("start_pulse_host.sh", "scripts/host/start_pulse_host.sh"),
        HostScript("repair_pulse_guests.sh", "scripts/host/repair_pulse_guests.sh"),
        HostScript("setup_pulse_guest.sh", "scripts/common/setup/setup_pulse_guest.sh"),
        HostScript("altaris_install.sh", "scripts/debian/proot/setup/altaris_install.sh"),
        // Debian guest
        HostScript("setup_debian_family.sh", "scripts/debian/common/setup/setup_debian_family.sh"),
        HostScript(
            "setup_customization_debian.sh",
            "scripts/debian/common/setup/setup_customization_debian.sh"
        ),
        HostScript(
            "setup_hw_accel_debian.sh",
            "scripts/debian/common/setup/setup_hw_accel_debian.sh",
            required = false
        ),
        // Alpine guest
        HostScript("setup_alpine_family.sh", "scripts/alpine/common/setup/setup_alpine_family.sh"),
        HostScript(
            "setup_customization_alpine.sh",
            "scripts/alpine/common/setup/setup_customization_alpine.sh"
        ),
        // Shared glibc-guest helpers (Fedora / Void / openSUSE)
        HostScript("altaris_guest_common.sh", "scripts/common/setup/altaris_guest_common.sh"),
        HostScript("altaris_gpu_common.sh", "scripts/common/setup/altaris_gpu_common.sh"),
        HostScript(
            "setup_customization_xfce.sh",
            "scripts/common/setup/setup_customization_xfce.sh"
        ),
        HostScript("setup_hw_accel_guest.sh", "scripts/common/setup/setup_hw_accel_guest.sh"),
        HostScript(
            "bwrap-proot-shim.sh",
            "scripts/common/setup/bwrap-proot-shim.sh",
            required = false
        ),
        HostScript(
            "bwrap-proot-shim",
            "scripts/common/setup/bwrap-proot-shim",
            required = false
        ),
        HostScript("setup_fedora_family.sh", "scripts/fedora/common/setup/setup_fedora_family.sh"),
        // RHEL-family (rpm) family script — shared by rocky / centos_stream.
        HostScript("setup_rpm_family.sh", "scripts/rpm/common/setup/setup_rpm_family.sh"),
        HostScript("setup_void_family.sh", "scripts/void/common/setup/setup_void_family.sh"),
        HostScript(
            "setup_opensuse_family.sh",
            "scripts/opensuse/common/setup/setup_opensuse_family.sh"
        ),
        // Deepin / Chimera / Manjaro guests
        HostScript(
            "setup_deepin_family.sh",
            "scripts/deepin/common/setup/setup_deepin_family.sh"
        ),
        HostScript(
            "setup_chimera_family.sh",
            "scripts/chimera/common/setup/setup_chimera_family.sh"
        ),
        HostScript(
            "setup_manjaro_family.sh",
            "scripts/manjaro/common/setup/setup_manjaro_family.sh"
        ),
        HostScript(
            "setup_ubuntu_family.sh",
            "scripts/ubuntu/common/setup/setup_ubuntu_family.sh"
        ),
        HostScript(
            "setup_kali_family.sh",
            "scripts/kali/common/setup/setup_kali_family.sh"
        ),
        HostScript(
            "setup_parrot_family.sh",
            "scripts/parrot/common/setup/setup_parrot_family.sh"
        ),
        HostScript(
            "setup_arch_family.sh",
            "scripts/arch/common/setup/setup_arch_family.sh"
        ),
        // EVP_md2 stub for TW libldap vs OpenSSL 3.5 (sudo/zypper)
        HostScript(
            "libevp_md2.so",
            "scripts/opensuse/common/libevp_md2.so",
            required = false
        ),
        // Chroot setup/uninstall
        HostScript("setup_debian13_chroot.sh", "scripts/chroot/setup_debian13_chroot.sh"),
        HostScript("uninstall_debian13_chroot.sh", "scripts/chroot/uninstall_debian13_chroot.sh"),
        HostScript("setup_alpine_chroot.sh", "scripts/chroot/setup_alpine_chroot.sh"),
        HostScript("uninstall_alpine_chroot.sh", "scripts/chroot/uninstall_alpine_chroot.sh"),
        HostScript("altaris_chroot.sh", "scripts/chroot/altaris_chroot.sh"),
        HostScript("resolve_bb.sh", "scripts/chroot/resolve_bb.sh"),
        HostScript("chroot_processes.sh", "scripts/chroot/chroot_processes.sh", required = false),
        HostScript("chroot_size.sh", "scripts/chroot/chroot_size.sh", required = false),
        // Desktop (proot)
        HostScript("start_gui.sh", "scripts/debian/proot/start/start_gui.sh"),
        HostScript("stop_gui.sh", "scripts/debian/proot/stop/stop_gui.sh"),
        // Desktop (chroot host wrappers + root guest)
        HostScript("start_gui_chroot.sh", "scripts/chroot/start_gui_chroot.sh"),
        HostScript("stop_gui_chroot.sh", "scripts/chroot/stop_gui_chroot.sh"),
        HostScript("start_debian13_gui.sh", "scripts/chroot/start_debian13_gui.sh"),
        HostScript("stop_debian13_gui.sh", "scripts/chroot/stop_debian13_gui.sh"),
        HostScript("start_alpine_gui.sh", "scripts/chroot/start_alpine_gui.sh"),
        HostScript("stop_alpine_gui.sh", "scripts/chroot/stop_alpine_gui.sh"),
        HostScript("setup_guest_chroot.sh", "scripts/chroot/setup_guest_chroot.sh"),
        HostScript("uninstall_guest_chroot.sh", "scripts/chroot/uninstall_guest_chroot.sh"),
        HostScript("start_guest_gui.sh", "scripts/chroot/start_guest_gui.sh"),
        HostScript("stop_guest_gui.sh", "scripts/chroot/stop_guest_gui.sh"),
    )

    /** Set when the last [deployScripts] run failed a required step. */
    internal var lastDeployFailure: String? = null

    /** Free bytes on the volume holding [dir], or -1 when unknown. */
    internal fun freeBytes(dir: File): Long = try {
        android.os.StatFs(dir.absolutePath).availableBytes
    } catch (_: Exception) {
        -1L
    }

    /**
     * Deploy host + distro scripts/assets into `$HOME` and the loader APK.
     * Idempotent: when the deploy marker is current and [force] is false the
     * whole loop is skipped (warm path). The package-path rewrite tree walk
     * (applyPackageToExtractedPrefix) was removed from here — it already runs
     * inside [BootstrapInstaller] on extraction, and re-walking usr/bin,
     * usr/etc, usr/share, python3.14 and include on every call is the startup
     * stall this optimization removes.
     *
     * Per-script copies are self-healing (target removed + one retry) and, on
     * EACCES-style failures, ownership is repaired through root once. The
     * first failure + free space land in [lastDeployFailure] so host-setup
     * errors name the exact file instead of a generic message.
     *
     * @param force when true, always rewrite assets and refresh the marker
     * @return false when any required deploy step fails (fail-closed contract)
     */
    fun deployScripts(ctx: Context, force: Boolean = false): Boolean {
        // Cheap (one small file) and must run even on the warm path: app updates move nativeLibraryDir.
        runCatching { TermuxHostPaths.refreshHostEnvIfStale(ctx.filesDir, ctx) }
        if (!force && isDeployed(ctx)) return true
        return try {
            val homeDir = File(ctx.filesDir, "home").also { it.mkdirs() }
            var ok = true
            var firstFailure: String? = null
            for (script in HOST_SCRIPTS) {
                val out = File(homeDir, script.name)
                val err = copyScriptAsset(ctx, script.assetPath, out)
                if (err != null) {
                    Log.w(TAG, "Script ${script.assetPath} deploy failed: $err")
                    if (firstFailure == null) firstFailure = "${script.name}: $err"
                    if (script.required) ok = false
                }
            }
            try {
                val termuxDir = File(homeDir, ".termux").also { it.mkdirs() }
                val fontOut = File(termuxDir, "font.ttf")
                if (!fontOut.isFile) {
                    ctx.assets.open("fonts/font.ttf").use { input ->
                        FileOutputStream(fontOut).use { input.copyTo(it) }
                    }
                }
            } catch (_: Exception) {
            }
            overlayPulseRuntime(ctx)
            overlayHostRuntime(ctx)
            // A stale/root-owned home can make every write fail with EACCES —
            // one owner fix + retry usually heals it (no-op without root).
            // Skipped when free space is critically low (storage is the issue).
            if (!ok) {
                val freeNow = freeBytes(ctx.filesDir)
                val storageSuspect = freeNow in 0 until (50L * 1024 * 1024)
                if (!storageSuspect && healHomeOwnership(ctx, homeDir)) {
                    Log.i(TAG, "retrying script deploy after ownership heal")
                    ok = true
                    firstFailure = null
                    for (script in HOST_SCRIPTS) {
                        val out = File(homeDir, script.name)
                        val err = copyScriptAsset(ctx, script.assetPath, out)
                        if (err != null) {
                            if (firstFailure == null) firstFailure = "${script.name}: $err"
                            if (script.required) ok = false
                        }
                    }
                }
            }
            val loaderOk = deployLoaderApk(ctx)
            if (!loaderOk) {
                Log.w(TAG, "loader.apk deploy failed — host not ready")
                if (firstFailure == null) firstFailure = "loader.apk: copy failed"
            }
            if (ok && loaderOk) {
                markDeployedSafe(ctx)
            }
            if (!ok || !loaderOk) {
                val freeMb = freeBytes(ctx.filesDir).takeIf { it >= 0 }?.let { it / (1024 * 1024) }
                lastDeployFailure = buildString {
                    append(firstFailure ?: "unknown step")
                    if (freeMb != null) append(" (free ${freeMb} MB)")
                }
                Log.w(TAG, "deployScripts failed: $lastDeployFailure")
            } else {
                lastDeployFailure = null
            }
            ok && loaderOk
        } catch (e: Exception) {
            lastDeployFailure = "${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "Failed to deploy scripts", e)
            false
        }
    }

    /**
     * Copy one asset to its deploy target. On failure the target is removed
     * (stale directory/symlink/root-owned leftover) and the copy retried once.
     * Returns null on success, else a short error string.
     */
    private fun copyScriptAsset(ctx: Context, assetPath: String, out: File): String? {
        fun attempt(): String? = try {
            ctx.assets.open(assetPath).use { input ->
                FileOutputStream(out).use { input.copyTo(it) }
            }
            runCatching { out.setExecutable(true, false) }
            if (!out.isFile || out.length() == 0L) "empty after copy" else null
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message}"
        }
        val first = attempt()
        if (first == null) return null
        runCatching {
            if (out.isDirectory) out.deleteRecursively() else out.delete()
        }
        return attempt() ?: null
    }

    /**
     * Best-effort `chown -R <appUid> files/home` through root, for homes left
     * root-owned by very old builds. Safe no-op without root.
     */
    private fun healHomeOwnership(ctx: Context, homeDir: File): Boolean {
        return try {
            val inv = com.altaris.linux.core.root.RootShell.resolveSuInvocation() ?: return false
            val uid = android.os.Process.myUid()
            val cmd = "chown -R $uid:$uid '${homeDir.absolutePath}' 2>/dev/null; " +
                "chmod -R u+rwX '${homeDir.absolutePath}' 2>/dev/null; true"
            val res = com.altaris.linux.core.root.RootShell.captureResult(cmd, 20_000L)
            res.exitCode == 0
        } catch (_: Exception) {
            false
        }
    }

    private val pulseRuntimeSos = listOf(
        "libsoxr.so",
        "libsoxr-lsr.so",
        "libandroid-execinfo.so",
        "libFLAC.so",
        "libmp3lame.so"
    )

    /**
     * Copy missing Pulse runtime `.so` from flavor assets without re-extracting
     * the bootstrap (preserves proot containers). Also drop default.pa.d TCP/AAudio.
     *
     * Always chmod 0755: [File.setExecutable] is a no-op on some Android 16
     * file trees, and a mode-600 `.so` cannot be mmap'd PROT_EXEC by
     * untrusted_app (root still can — looks like “missing runtime libs”).
     */
    fun overlayPulseRuntime(ctx: Context) {
        val libDir = File(ctx.filesDir, "usr/lib")
        libDir.mkdirs()
        for (name in pulseRuntimeSos) {
            val dest = File(libDir, name)
            if (!dest.isFile || dest.length() == 0L) {
                try {
                    ctx.assets.open("pulse-runtime/$name").use { input ->
                        FileOutputStream(dest).use { input.copyTo(it) }
                    }
                    Log.i(TAG, "overlaid pulse-runtime $name")
                } catch (e: Exception) {
                    Log.w(TAG, "pulse-runtime overlay missed $name", e)
                }
            }
            chmodPulseLib(dest)
        }
        val lame0 = File(libDir, "libmp3lame.so.0")
        if (!lame0.exists()) {
            try {
                java.nio.file.Files.createSymbolicLink(
                    lame0.toPath(),
                    java.nio.file.Paths.get("libmp3lame.so")
                )
            } catch (_: Exception) {
                try {
                    val src = File(libDir, "libmp3lame.so")
                    if (src.isFile) src.copyTo(lame0, overwrite = true)
                } catch (_: Exception) {
                }
            }
        }
        chmodPulseLib(lame0)
        val paDir = File(ctx.filesDir, "usr/etc/pulse/default.pa.d")
        paDir.mkdirs()
        try {
            File(paDir, "99-altaris.pa").writeText(
                ".nofail\n" +
                    "load-module module-aaudio-sink\n" +
                    "load-module module-sles-sink\n" +
                    "load-module module-native-protocol-tcp " +
                    "auth-ip-acl=127.0.0.1 auth-anonymous=1 listen=127.0.0.1\n"
            )
        } catch (e: Exception) {
            Log.w(TAG, "pulse config write failed: ${e.message}")
        }
    }

    /** 0755 so the untrusted_app linker can PROT_EXEC the overlay `.so`. */
    private val hostRuntimeSos = listOf(
        "libnghttp3.so",
        "libngtcp2.so",
        "libngtcp2_crypto_ossl.so",
    )

    /**
     * Repair the host bootstrap's libcurl stack. Some bootstrap snapshots ship
     * libcurl.so linked against HTTP/3 libraries that were not included, which
     * breaks `curl` in every distro whose PATH sees the host prefix first
     * ("libnghttp3.so not found"). Overlay the three MIT-licensed runtime
     * libraries without re-extracting the prefix, preserving all distros.
     */
    fun overlayHostRuntime(ctx: Context) {
        val libDir = File(ctx.filesDir, "usr/lib").also { it.mkdirs() }
        for (name in hostRuntimeSos) {
            val dest = File(libDir, name)
            if (!dest.isFile || dest.length() < 1024L) {
                try {
                    ctx.assets.open("host-runtime/$name").use { input ->
                        FileOutputStream(dest).use { input.copyTo(it) }
                    }
                    Log.i(TAG, "overlaid host runtime $name")
                } catch (e: Exception) {
                    Log.w(TAG, "host runtime overlay missed $name", e)
                }
            }
            chmodPulseLib(dest)
        }
    }

    internal fun chmodPulseLib(dest: File) {
        if (!dest.exists()) return
        try {
            Os.chmod(dest.absolutePath, 493)
        } catch (_: Exception) {
            dest.setReadable(true, true)
            dest.setExecutable(true, true)
        }
    }

    fun deployLoaderApk(ctx: Context): Boolean {
        return try {
            val dest = File(ctx.filesDir, "usr/libexec/termux-x11/loader.apk")
            if (dest.isFile && dest.length() > 0L) return true
            dest.parentFile?.mkdirs()
            ctx.assets.open("loader.apk").use { input ->
                FileOutputStream(dest).use { input.copyTo(it) }
            }
            dest.setReadOnly()
            Log.i(TAG, "Deployed loader.apk: ${dest.absolutePath}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "loader.apk deploy failed (GUI helpers will warn)", e)
            false
        }
    }
}
