package com.altaris.linux.core.rea

import android.content.Context
import android.util.Log
import com.altaris.linux.core.install.DesktopChoice
import com.altaris.linux.core.install.DistroInstallProfile
import com.altaris.linux.core.install.OnboardingInstallRunner
import com.altaris.linux.core.root.RootShell
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Creates and removes the container that hosts the hidden REA distro.
 *
 * The heavy lifting (rootfs download and verification, proot extraction,
 * chroot staging, family setup) already exists in [OnboardingInstallRunner].
 * This adapter exists so the REA install does not fork that logic — it just
 * picks the hidden profile, asks for no desktop, and waits for the run to
 * finish.
 */
object ReaInstaller {

    private const val TAG = "AltarisReaInstaller"

    /** True when the container already exists and looks usable. */
    fun containerExists(context: Context, profile: DistroInstallProfile): Boolean {
        val rootfs = rootfsOf(context, profile) ?: return false
        return rootfs.isDirectory && rootfs.list()?.isNotEmpty() == true
    }

    /**
     * Install the container if it is missing. Returns true when it is usable
     * afterwards (already present counts as success).
     */
    suspend fun ensureContainer(
        context: Context,
        profile: DistroInstallProfile,
        onLog: (String) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        if (containerExists(context, profile)) {
            onLog("analysis environment already exists")
            return@withContext true
        }
        if (profile.method == "chroot" && !RootShell.isRootAvailable()) {
            onLog("ERROR: chroot needs root access")
            return@withContext false
        }
        onLog("downloading and unpacking the base system (this takes a while)")
        val done = CompletableDeferred<Boolean>()
        val runner = OnboardingInstallRunner(context.applicationContext)
        // Terminal-only: the analysis environment never needs a desktop, and
        // skipping XFCE/KDE removes hundreds of packages and their downloads.
        runner.start(
            distroId = profile.distroId,
            desktops = setOf(DesktopChoice.NONE),
        ) { progress ->
            progress.logLine?.takeIf { it.isNotBlank() }?.let(onLog)
            if (progress.failed) {
                progress.errorMessage?.let { onLog("ERROR: $it") }
                done.complete(false)
            } else if (progress.finished) {
                done.complete(true)
            }
        }
        // The rootfs is a few hundred MB; give the download a generous bound
        // instead of hanging the install screen forever on a dead network.
        val finished = withTimeoutOrNull(INSTALL_TIMEOUT_MS) { done.await() } ?: false
        if (!finished) {
            onLog("ERROR: the analysis environment did not finish installing")
            runCatching { runner.cancelActive() }
            return@withContext false
        }
        val ok = containerExists(context, profile)
        if (!ok) onLog("ERROR: the container was not created")
        ok
    }

    /** Remove the container for [method], including its rootfs. */
    fun removeContainer(context: Context, method: ReaDistro.Method): Boolean {
        val profile = DistroInstallProfile.reaProfile(method.id) ?: return false
        val rootfs = rootfsOf(context, profile) ?: return false
        Log.i(TAG, "removing REA container at ${rootfs.absolutePath}")
        return runCatching {
            if (profile.method == "chroot") {
                // A chroot lives under /data/local and needs root to delete.
                val result = RootShell.captureResult(
                    "rm -rf " + quote(rootfs.absolutePath),
                    60_000L,
                )
                result.exitCode == 0
            } else {
                rootfs.deleteRecursively()
            }
        }.getOrDefault(false)
    }

    private fun rootfsOf(context: Context, profile: DistroInstallProfile): File? =
        when (profile.method) {
            "chroot" -> profile.chrootPath?.let { File(it) }
            else -> {
                if (profile.prootName.isBlank()) null
                else File(
                    context.filesDir,
                    "usr/var/lib/proot-distro/containers/${profile.prootName}/rootfs"
                )
            }
        }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** Rootfs downloads are large; 45 minutes covers a slow phone connection. */
    private const val INSTALL_TIMEOUT_MS = 45 * 60_000L
}
