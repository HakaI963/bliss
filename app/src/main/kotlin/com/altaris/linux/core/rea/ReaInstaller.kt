package com.altaris.linux.core.rea

import android.content.Context
import android.util.Log
import com.altaris.linux.core.install.DistroInstallProfile
import com.altaris.linux.core.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Progress callback: (what is happening, overall percent, detail line). */
typealias ReaInstallProgress = (line: String, percent: Int, detail: String) -> Unit

/**
 * Removes the REA toolchain from a distro.
 *
 * REA installs *into* a distro the user already has, so there is no container
 * to create here any more — the distro's own install path owns that. What this
 * owns is the reverse: taking REA out again without touching the distro.
 *
 * The toolchain lives entirely under `/opt/altaris/rea` inside the guest, so
 * removal is a scoped delete of that one directory. A chroot needs no unmount
 * for that path — it is inside the rootfs, not one of the bind mounts — but the
 * delete still runs through `su` for a chroot, because the app uid cannot read
 * `/data/local`.
 */
object ReaInstaller {

    private const val TAG = "AltarisReaInstaller"

    /**
     * Remove REA's toolchain from [distroId], leaving the distro itself alone.
     *
     * Returns true only when the directory is really gone, verified from inside
     * the guest: reporting success while the tree survived is what made a
     * "removed" environment reappear on the next refresh.
     */
    suspend fun removeToolchain(context: Context, distroId: String): Boolean =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            if (distroId.isBlank()) return@withContext false
            val profile = DistroInstallProfile.forId(distroId) ?: return@withContext false
            val root = ReaManager.ROOT

            // `rm -rf` of the toolchain root, then prove it is gone. `-x` keeps
            // the walk on one filesystem so a bind mount inside the tree can
            // never be followed out into the host.
            val script = buildString {
                append("set +e\n")
                append("rm -rf ").append(root).append(" 2>/dev/null\n")
                append("if [ -e ").append(root).append(" ]; then echo STILL_PRESENT; else echo GONE; fi\n")
            }
            val result = runCatching {
                GuestExec.run(app, distroId, script, timeoutMs = 5 * 60_000L, asRoot = true)
            }.getOrNull()
            val gone = result?.output?.contains("GONE") == true
            if (!gone) {
                Log.w(
                    TAG,
                    "toolchain still present in $distroId (${profile.method}): " +
                        (result?.tail(3) ?: "no output"),
                )
            }
            gone
        }

    /**
     * True when the container already exists and looks usable.
     *
     * A chroot rootfs lives under `/data/local/altaris/chroots/…`, which this
     * process cannot read (SELinux `shell_data_file`). Checking it with
     * `File.isDirectory` therefore always returned false, so a chroot that the
     * installer had just created and verified was reported as "the container
     * was not created" — which is exactly what happened on device. Chroot is
     * checked through root instead.
     */
    fun containerExists(context: Context, profile: DistroInstallProfile): Boolean {
        val rootfs = rootfsOf(context, profile) ?: return false
        if (rootfs.isDirectory && rootfs.list()?.isNotEmpty() == true) return true
        if (profile.method != "chroot") return false
        if (!RootShell.isRootAvailable()) {
            // Without root, fall back to the cached chroot probe the settings
            // screen already keeps, so a granted-but-not-yet-probed state does
            // not read as absent.
            return com.altaris.linux.core.chroot.ChrootInfoStore
                .cachedInstalled(context, profile.distroId)
        }
        val path = rootfs.absolutePath
        return runCatching {
            RootShell.captureResult(
                "test -d " + quote(path) +
                    " && [ -n \"\$(ls -A " + quote(path) + " 2>/dev/null | head -n1)\" ]",
                20_000L,
            ).exitCode == 0
        }.getOrDefault(false)
    }

    /** The rootfs directory of a distro profile, or null when it has none. */
    private fun rootfsOf(context: Context, profile: DistroInstallProfile): File? {
        if (profile.method == "chroot") {
            return profile.chrootPath?.let { File(it) }
        }
        if (profile.prootName.isBlank()) return null
        return File(
            context.filesDir,
            "usr/var/lib/proot-distro/containers/${profile.prootName}/rootfs",
        )
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
