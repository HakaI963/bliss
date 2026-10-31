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
import java.util.concurrent.atomic.AtomicReference

/** Progress callback: (what is happening, overall percent, detail line). */
typealias ReaInstallProgress = (line: String, percent: Int, detail: String) -> Unit

/**
 * Creates and removes the container that hosts the hidden REA distro.
 *
 * The heavy lifting (rootfs download and verification, proot extraction,
 * chroot staging, family setup) already exists in [OnboardingInstallRunner].
 * This adapter exists so the REA install does not fork that logic — it just
 * picks the hidden profile, asks for no desktop, and forwards real progress.
 *
 * Progress is forwarded on three levels, because "installing" alone told the
 * user nothing:
 *  - [onLog] gets the installer's own line (what is happening now);
 *  - the percentage is the runner's overall phase percentage;
 *  - the download counters are parsed from the detail line and published to
 *    [ReaDistro], so the dashboard can show done / left / total.
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
        onProgress: ReaInstallProgress = { _, _, _ -> },
    ): Boolean = withContext(Dispatchers.IO) {
        if (containerExists(context, profile)) {
            onProgress("Analysis environment already exists", 62, "")
            return@withContext true
        }
        if (profile.method == "chroot" && !RootShell.isRootAvailable()) {
            onProgress("chroot needs root access", 0, "Root not available")
            return@withContext false
        }
        onProgress("Downloading and unpacking the base system", 0, "starting")
        val done = CompletableDeferred<Boolean>()
        val runner = OnboardingInstallRunner(context.applicationContext)
        val lastDetail = AtomicReference("")
        // Terminal-only: the analysis environment never needs a desktop, and
        // skipping XFCE/KDE removes hundreds of packages and their downloads.
        runner.start(
            distroId = profile.distroId,
            desktops = setOf(DesktopChoice.NONE),
        ) { progress: OnboardingInstallRunner.Progress ->
            val detail = progress.detail.orEmpty()
            if (detail.isNotBlank() && detail != lastDetail.get()) {
                lastDetail.set(detail)
                // The runner's DL phase reports "12.3 / 780.1 MB"; publish the
                // numbers so the dashboard can show what is left.
                parseDownload(detail)?.let { (done2, total) ->
                    ReaDistro.publishDownload(done2, total, detail)
                }
                onProgress(progress.phaseLabel, progress.overallPercent, detail)
            } else if (progress.logLine != null && progress.logLine!!.isNotBlank()) {
                onProgress(progress.phaseLabel, progress.overallPercent, progress.logLine!!)
            }
            if (progress.failed) {
                progress.errorMessage?.let { onProgress("Install failed", progress.overallPercent, it) }
                done.complete(false)
            } else if (progress.finished) {
                done.complete(true)
            }
        }
        // The rootfs is a few hundred MB; give the download a generous bound
        // instead of hanging the install screen forever on a dead network.
        val finished = withTimeoutOrNull(INSTALL_TIMEOUT_MS) { done.await() } ?: false
        if (!finished) {
            onProgress("The analysis environment did not finish installing", 0, "timed out")
            runCatching { OnboardingInstallRunner.cancelActive() }
            return@withContext false
        }
        val ok = containerExists(context, profile)
        if (!ok) onProgress("The container was not created", 0, "")
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

    /**
     * Parse the runner's download detail.
     *
     * The runner formats it as `"12.3 / 780.1 MB · 3.2 MB/s"` (see
     * `OnboardingInstallRunner.fmtMb`). Returns null when the line is not a
     * byte counter, so non-download phases are left alone.
     */
    internal fun parseDownload(detail: String): Pair<Long, Long>? {
        val match = MB_RE.find(detail) ?: return null
        val downloaded = match.groupValues[1].toDoubleOrNull() ?: return null
        val total = match.groupValues[2].toDoubleOrNull() ?: return null
        if (total <= 0.0) return null
        return (downloaded * MB).toLong() to (total * MB).toLong()
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

    private const val MB = 1_048_576.0

    /** `12.3 / 780.1 MB` — the runner's own download format. */
    private val MB_RE = Regex("""([\d.]+)\s*/\s*([\d.]+)\s*MB""")

    /** Rootfs downloads are large; 45 minutes covers a slow phone connection. */
    private const val INSTALL_TIMEOUT_MS = 45 * 60_000L
}
