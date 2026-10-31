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
 * `bliss` remains as a compatibility alias. The helper is a thin HTTP client
 * for [ControlServer]; it does not need root or the app's UID inside the guest.
 *
 * It also installs [UrlOpener]'s guest shim: a distro has no browser, so
 * `xdg-open` / `sensible-browser` / `$BROWSER` used to fail silently and the
 * device-auth links printed by AI CLIs and `gh auth login` could not be
 * opened at all.
 */
object AltarisCliInstaller {
    private const val TAG = "AltarisCli"
    private const val ASSET = "scripts/altaris-cli.sh"
    private const val OPEN_ASSET = "scripts/altaris-open.sh"
    private const val PRIMARY = "altaris"
    /** Legacy command name kept as an alias so old scripts keep working. */
    private const val COMPAT = "bliss"

    /**
     * Marker file name for one helper payload.
     *
     * Content-addressed on purpose: a fixed `v6` marker meant a chroot guest
     * that already had the CLI was skipped forever, so `altaris` never gained
     * new commands (like `altaris open`). A changed asset produces a new
     * marker and the file is rewritten.
     */
    private fun markerName(asset: String): String =
        ".altaris-cli-" + Integer.toHexString(asset.hashCode())

    /** Command name of the URL shim. */
    const val OPEN_CMD = "altaris-open"

    /**
     * Names a CLI may look up to open a link. Every one of them becomes a copy
     * of the shim, so `xdg-open`, `sensible-browser` and friends work without
     * the guest shipping a browser.
     */
    private val OPEN_ALIASES = listOf(
        "xdg-open",
        "sensible-browser",
        "x-www-browser",
        "www-browser",
        "termux-open",
        "termux-open-url",
    )

    fun ensureHost(ctx: Context) {
        runCatching {
            val bin = File(TermuxHostPaths.BIN)
            if (!bin.isDirectory) return
            val cli = readAsset(ctx, ASSET) ?: return
            val open = readAsset(ctx, OPEN_ASSET) ?: return
            writeAsset(File(bin, PRIMARY), null, cli)
            writeOpenShim(bin, marker = null, asset = open)
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
        val bin = File(rootfs, "usr/local/bin")
        val cli = readAsset(ctx, ASSET) ?: return
        val open = readAsset(ctx, OPEN_ASSET) ?: return
        writeAsset(File(bin, PRIMARY), File(rootfs, markerName(cli)), cli)
        writeOpenShim(bin, File(rootfs, markerName(open)), open)
    }

    private fun ensureChroot(ctx: Context, profile: DistroInstallProfile) {
        val root = profile.chrootPath?.takeIf { it.isNotBlank() } ?: return
        if (!RootShell.isRootAvailable()) return
        val script = readAsset(ctx, ASSET) ?: return
        val open = readAsset(ctx, OPEN_ASSET) ?: return
        val cliMarker = "$root/${markerName(script)}"
        val openMarker = "$root/${markerName(open)}"
        val b64 = Base64.encodeToString(script.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val openB64 = Base64.encodeToString(open.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        // One su pass for both helpers; each part is marker-guarded so a guest
        // that already has the CLI still gets the URL shim.
        val cmd = buildString {
            append("mkdir -p '$root/usr/local/bin' && ")
            append("if [ ! -f '$cliMarker' ]; then ")
            append("printf '%s' '$b64' | base64 -d > '$root/usr/local/bin/$PRIMARY' && ")
            append("chmod 755 '$root/usr/local/bin/$PRIMARY' && ")
            append("cp -f '$root/usr/local/bin/$PRIMARY' '$root/usr/local/bin/$COMPAT' && ")
            append("chmod 755 '$root/usr/local/bin/$COMPAT' && ")
            append("touch '$cliMarker'; ")
            append("fi && ")
            append("if [ ! -f '$openMarker' ]; then ")
            append("printf '%s' '$openB64' | base64 -d > '$root/usr/local/bin/$OPEN_CMD' && ")
            append("chmod 755 '$root/usr/local/bin/$OPEN_CMD' && ")
            for (alias in OPEN_ALIASES) {
                append("cp -f '$root/usr/local/bin/$OPEN_CMD' '$root/usr/local/bin/$alias' && ")
                append("chmod 755 '$root/usr/local/bin/$alias' && ")
            }
            append("touch '$openMarker'; ")
            append("fi")
        }
        val result = RootShell.captureResult(cmd, 20_000)
        if (result.exitCode != 0) {
            Log.w(TAG, "chroot CLI write failed: ${result.exitCode} ${result.stdout.take(200)}")
        }
    }

    /**
     * Install the URL shim + its aliases into [bin].
     *
     * [marker] guards a whole directory of writes: when the marker exists the
     * files are left alone (the marker name is derived from the payload, so a
     * changed shim installs again). The host prefix has no marker (its writes
     * are cheap and must track the app version exactly).
     */
    private fun writeOpenShim(bin: File, marker: File?, asset: String) {
        val script = if (marker == null) {
            asset.replaceFirst("#!/bin/sh", "#!/system/bin/sh")
        } else {
            asset
        }
        val targets = buildList {
            add(File(bin, OPEN_CMD))
            OPEN_ALIASES.forEach { add(File(bin, it)) }
        }
        if (marker != null && marker.isFile &&
            targets.all { it.isFile && it.length() == script.length.toLong() }
        ) {
            return
        }
        bin.mkdirs()
        for (target in targets) {
            runCatching {
                target.writeText(script)
                target.setExecutable(true, false)
            }.onFailure { Log.w(TAG, "cannot install ${target.name}", it) }
        }
        if (marker != null) runCatching { marker.writeText("1") }
    }

    private fun writeAsset(target: File, marker: File?, asset: String) {
        // Android has no /bin/sh; the host prefix runs on /system/bin/sh.
        // Guests keep the POSIX shebang (their /bin/sh is real).
        val script = if (marker == null) {
            asset.replaceFirst("#!/bin/sh", "#!/system/bin/sh")
        } else {
            asset
        }
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

    private fun readAsset(ctx: Context, name: String): String? = runCatching {
        ctx.assets.open(name).bufferedReader().use { it.readText() }
    }.getOrNull()
}
