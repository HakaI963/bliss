package com.altaris.linux.core.control

import android.content.Context
import android.util.Base64
import android.util.Log
import com.altaris.linux.core.data.DistroRepository
import com.altaris.linux.core.install.DistroInstallProfile
import com.altaris.linux.core.root.RootShell
import com.altaris.linux.core.terminal.TermuxHostPaths
import java.io.File

/**
 * Installs the `altaris` helper into the host prefix and every guest rootfs.
 * `altaris` remains as a compatibility alias. The helper is a thin HTTP client
 * for [ControlServer]; it does not need root or the app's UID inside the guest.
 */
object AltarisCliInstaller {
    private const val TAG = "AltarisCli"
    private const val MARKER = ".altaris-cli-v6"
    private const val ASSET = "scripts/altaris-cli.sh"
    private const val PRIMARY = "altaris"
    /** Legacy command name kept as an alias so old scripts keep working. */
    private const val COMPAT = "bliss"

    fun ensureHost(ctx: Context) {
        runCatching {
            val bin = File(TermuxHostPaths.BIN)
            if (!bin.isDirectory) return
            val target = File(bin, PRIMARY)
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
        val target = File(rootfs, "usr/local/bin/$PRIMARY")
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
            append("printf '%s' '$b64' | base64 -d > '$root/usr/local/bin/$PRIMARY' && ")
            append("chmod 755 '$root/usr/local/bin/$PRIMARY' && ")
            append("cp -f '$root/usr/local/bin/$PRIMARY' '$root/usr/local/bin/$COMPAT' && ")
            append("chmod 755 '$root/usr/local/bin/$COMPAT' && ")
            append("touch '$marker'")
        }
        val result = RootShell.captureResult(cmd, 15_000)
        if (result.exitCode != 0) {
            Log.w(TAG, "chroot CLI write failed: ${result.exitCode} ${result.stdout.take(200)}")
        }
    }

    private fun writeAsset(ctx: Context, target: File, marker: File?) {
        val script = readAsset(ctx) ?: return
        val compat = File(target.parentFile, COMPAT)
        if (marker != null && marker.isFile && target.isFile && compat.isFile &&
            target.length() == script.length.toLong() && compat.length() == script.length.toLong()
        ) {
            return
        }
        target.parentFile?.mkdirs()
        target.writeText(script)
        compat.writeText(script)
        runCatching {
            target.setExecutable(true, false)
            compat.setExecutable(true, false)
        }
        if (marker != null) runCatching { marker.writeText("6") }
    }

    private fun readAsset(ctx: Context): String? = runCatching {
        ctx.assets.open(ASSET).bufferedReader().use { it.readText() }
    }.getOrNull()
}
