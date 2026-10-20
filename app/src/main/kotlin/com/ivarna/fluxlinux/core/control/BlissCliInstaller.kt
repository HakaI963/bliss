package com.ivarna.fluxlinux.core.control

import android.content.Context
import android.util.Base64
import android.util.Log
import com.ivarna.fluxlinux.core.data.DistroRepository
import com.ivarna.fluxlinux.core.install.DistroInstallProfile
import com.ivarna.fluxlinux.core.root.RootShell
import com.ivarna.fluxlinux.core.terminal.TermuxHostPaths
import java.io.File

/**
 * Installs the `bliss` helper into the host prefix and every guest rootfs.
 * The helper is a thin curl/wget client for [ControlServer]; it does not need
 * root or the app's UID inside the guest.
 */
object BlissCliInstaller {
    private const val TAG = "BlissCli"
    private const val MARKER = ".bliss-cli-v3"
    private const val ASSET = "scripts/bliss-cli.sh"

    fun ensureHost(ctx: Context) {
        runCatching {
            val bin = File(TermuxHostPaths.BIN)
            if (!bin.isDirectory) return
            val target = File(bin, "bliss")
            writeAsset(ctx, target, null)
        }.onFailure { Log.w(TAG, "host CLI install failed", it) }
    }

    fun ensure(ctx: Context, method: String, distroId: String?) {
        if (distroId.isNullOrBlank()) return
        val profile = DistroInstallProfile.forId(distroId) ?: return
        runCatching {
            if (method == "chroot") ensureChroot(ctx, profile) else ensureProot(ctx, profile)
        }.onFailure { Log.w(TAG, "guest CLI install failed: $distroId/$method", it) }
    }

    /** Best-effort pass over every installed proot root and every visible chroot. */
    fun installAll(ctx: Context) {
        ensureHost(ctx)
        for (distro in DistroRepository.supportedDistros) {
            val profile = DistroInstallProfile.forId(distro.id) ?: continue
            runCatching {
                // Do not probe su during app startup. Chroot CLI installation
                // happens when the user actually opens that guest terminal.
                if (profile.method != "chroot") ensureProot(ctx, profile)
            }.onFailure { Log.w(TAG, "CLI sync failed: ${distro.id}", it) }
        }
    }

    private fun ensureProot(ctx: Context, profile: DistroInstallProfile) {
        if (profile.prootName.isBlank()) return
        val rootfs = File(
            ctx.filesDir,
            "usr/var/lib/proot-distro/containers/${profile.prootName}/rootfs"
        )
        if (!rootfs.isDirectory) return
        val target = File(rootfs, "usr/local/bin/bliss")
        writeAsset(ctx, target, File(rootfs, MARKER))
    }

    private fun ensureChroot(ctx: Context, profile: DistroInstallProfile) {
        val root = profile.chrootPath?.takeIf { it.isNotBlank() } ?: return
        if (!RootShell.isRootAvailable()) return
        val marker = "$root/$MARKER"
        val existing = RootShell.capture("test -f '$marker' && echo yes || echo no", 4_000)
        if (existing.trim() == "yes") return
        val script = readAsset(ctx) ?: return
        val b64 = Base64.encodeToString(script.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val cmd = buildString {
            append("mkdir -p '$root/usr/local/bin' && ")
            append("printf '%s' '$b64' | base64 -d > '$root/usr/local/bin/bliss' && ")
            append("chmod 755 '$root/usr/local/bin/bliss' && ")
            append("touch '$marker'")
        }
        val result = RootShell.captureResult(cmd, 15_000)
        if (result.exitCode != 0) {
            Log.w(TAG, "chroot CLI write failed: ${result.exitCode} ${result.stdout.take(200)}")
        }
    }

    private fun writeAsset(ctx: Context, target: File, marker: File?) {
        val script = readAsset(ctx) ?: return
        if (marker != null && marker.isFile && target.isFile &&
            target.length() == script.length.toLong()
        ) {
            return
        }
        target.parentFile?.mkdirs()
        target.writeText(script)
        runCatching { target.setExecutable(true, false) }
        if (marker != null) runCatching { marker.writeText("3") }
    }

    private fun readAsset(ctx: Context): String? = runCatching {
        ctx.assets.open(ASSET).bufferedReader().use { it.readText() }
    }.getOrNull()
}
