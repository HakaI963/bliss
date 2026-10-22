package com.altaris.linux.core.terminal

import android.content.Context
import android.system.Os
import android.util.Log
import com.altaris.linux.core.install.HostBootstrap
import com.altaris.linux.core.install.RootfsDownloader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Extracts the host `bootstrap.tar` into the app filesDir as the embedded host PREFIX,
 * then applies the package rewrite + host env SSOT.
 *
 * Local-first (same model as [RootfsDownloader]):
 *  1. APK `assets/bootstrap.tar` (both flavors package it)
 *  2. Verified `$HOME/bootstrap_<applicationId>.tar`
 *  3. GitHub release tag `rootfs` download (repair fallback when the asset is missing)
 *
 * Download only runs from [ensureExtracted], which is always user-initiated
 * (Initialize Host / install).
 *
 * Pass 2 hardening:
 *  - version marker (`home/.altarislinux/bootstrap.extracted`) + [isExtracted] checks BOTH
 *    marker and `libtermux-exec.so` — a lone file from a corrupt tree is NOT "extracted".
 *  - atomic-ish extract: write to `.bootstrap_extract_tmp/` then promote into filesDir.
 *  - [clearMarker] + [ensureExtracted](force=true) lets callers force a clean re-extract
 *    (e.g. after setup_termux validation failure).
 *
 * @param onProgress called from the background thread with (bytesDone, totalBytes, phaseLabel)
 */
object BootstrapInstaller {

    private const val TAG = "BootstrapInstaller"
    private const val ASSET_NAME = "bootstrap.tar"
    private const val TMP_DIR = ".bootstrap_extract_tmp"

    /** Bump when the extract/rewrite pipeline changes (invalidates old trees). */
    const val EXTRACT_VERSION = 4

    private const val MARKER_REL = "home/.altarislinux/bootstrap.extracted"

    fun extractMarker(ctx: Context): File =
        File(ctx.filesDir, MARKER_REL)

    fun clearMarker(ctx: Context): Boolean =
        extractMarker(ctx).delete()

    /**
     * True only when the marker exists AND the last-created host file is present.
     * Deliberately strict: `libtermux-exec.so` alone does not prove a complete tree.
     */
    fun isExtracted(ctx: Context): Boolean {
        if (!TermuxHostPaths.termuxExec(ctx).exists()) return false
        val marker = extractMarker(ctx)
        if (!marker.isFile) return false
        val content = marker.readText().trim()
        val (version, pkg) = content.split("|", limit = 2).let { it.getOrNull(0) to it.getOrNull(1) }
        return version == EXTRACT_VERSION.toString() && pkg == TermuxHostPaths.PACKAGE
    }

    fun markExtracted(ctx: Context) {
        val marker = extractMarker(ctx)
        marker.parentFile?.mkdirs()
        marker.writeText("$EXTRACT_VERSION|${TermuxHostPaths.PACKAGE}")
    }

    /**
     * Extract the bootstrap if needed; otherwise re-apply SSOT rewrite + host env.
     *
     * @param force when true, always re-extract (used by recovery paths)
     * @return true when the PREFIX is complete and marked
     *
     * **Container safety (B2):** any existing `usr/var/lib/proot-distro` tree
     * (installed Debian guest) is moved aside before the prefix is replaced and
     * restored afterwards — a re-extract NEVER wipes an installed proot guest.
     */
    fun ensureExtracted(
        ctx: Context,
        force: Boolean = false,
        onProgress: (done: Long, total: Long, phase: String) -> Unit = { _, _, _ -> },
        onError: (String) -> Unit = {}
    ): Boolean {
        if (!force && isExtracted(ctx)) {
            // Happy path (proot-opt-01): the prefix is already extracted AND the
            // package rewrite / host env were applied when markExtracted() ran.
            // Re-walking the whole prefix tree here on every terminal open is the
            // multi-second startup stall — skip it. Old trees predating the W^X
            // rewrite carry an older EXTRACT_VERSION marker → fail isExtracted →
            // full re-extract path below re-applies applyPackageToExtractedPrefix.
            return true
        }
        return try {
            val tmpDir = File(ctx.filesDir, TMP_DIR)
            tmpDir.deleteRecursively()
            File(tmpDir, "usr/tmp").mkdirs()
            File(tmpDir, "usr/etc").mkdirs()
            File(ctx.filesDir, "home").mkdirs()

            val source = resolveBootstrapSource(ctx, onProgress) ?: run {
                onError(
                    "host bootstrap missing — place ${HostBootstrap.forApplicationId(ctx.packageName).fileName} " +
                        "in the app home directory or allow the GitHub download"
                )
                throw IllegalStateException(
                    "host bootstrap missing — place ${HostBootstrap.forApplicationId(ctx.packageName).fileName} " +
                        "in the app home directory or allow the GitHub download"
                )
            }
            onProgress(0L, source.totalBytes, "Extracting host bootstrap")
            source.stream.use { input ->
                extractTarStream(
                    input,
                    tmpDir,
                    totalBytes = source.totalBytes,
                    onProgress = onProgress
                )
            }
            onProgress(source.totalBytes, source.totalBytes, "Finalizing host prefix")

            // Preserve installed proot containers (usr/var/lib/proot-distro).
            val preserved = preserveProotDistro(ctx)

            // Promote tmp → filesDir (replace any partial/corrupt usr tree).
            val tmpUsr = File(tmpDir, "usr")
            val dstUsr = File(ctx.filesDir, "usr")
            if (!File(tmpUsr, "lib/libtermux-exec.so").isFile) {
                throw IllegalStateException("extract missing usr/lib/libtermux-exec.so")
            }
            if (dstUsr.exists()) dstUsr.deleteRecursively()
            if (!tmpUsr.renameTo(dstUsr)) {
                // Same-FS rename should not fail; fall back to copy.
                tmpUsr.copyRecursively(dstUsr, overwrite = true)
                tmpUsr.deleteRecursively()
            }
            tmpDir.deleteRecursively()

            if (preserved != null) {
                val target = File(dstUsr, "var/lib/proot-distro")
                target.parentFile?.mkdirs()
                if (!preserved.renameTo(target)) {
                    Log.w(TAG, "preserve: renameTo failed, copying proot-distro back")
                    preserved.copyRecursively(target, overwrite = true)
                    preserved.deleteRecursively()
                }
                Log.i(TAG, "preserved installed proot-distro containers across re-extract")
            }

            val rewritten = TermuxHostPaths.applyPackageToExtractedPrefix(ctx.filesDir, ctx)
            // W^X / mode safety net: re-assert owner-exec on every host tool dir
            // with a REAL chmod (File.setExecutable is a no-op on some Android 16
            // trees, and chmod 755 in setup alone did not heal on-device exit=126).
            repairHostExecPermissions(ctx.filesDir)
            markExtracted(ctx)
            Log.i(TAG, "bootstrap extract done; rewrote $rewritten residual stock paths")
            true
        } catch (e: Exception) {
            Log.e(TAG, "bootstrap extract failed", e)
            onError("Extract failed: ${e.message}")
            clearMarker(ctx)
            File(ctx.filesDir, TMP_DIR).deleteRecursively()
            false
        }
    }

    private data class BootstrapSource(val stream: InputStream, val totalBytes: Long)

    /**
     * APK asset first (zenithblue / older builds), then a SHA-pinned file under
     * `$HOME`, then GitHub `rootfs` download. Network is never used when a
     * verified local copy exists.
     */
    private fun resolveBootstrapSource(
        ctx: Context,
        onProgress: (done: Long, total: Long, phase: String) -> Unit
    ): BootstrapSource? {
        try {
            val total = try {
                ctx.assets.openFd(ASSET_NAME).use { it.length }
            } catch (_: Exception) {
                0L
            }
            val stream = ctx.assets.open(ASSET_NAME)
            Log.i(TAG, "Using APK asset $ASSET_NAME ($total bytes)")
            return BootstrapSource(stream, total)
        } catch (_: Exception) {
            Log.i(TAG, "APK has no $ASSET_NAME — resolving release archive")
        }

        val pin = HostBootstrap.forApplicationId(ctx.packageName)
        val destDir = TermuxHostPaths.homeDir(ctx)
        val ok = RootfsDownloader.ensurePresent(
            destDir,
            pin,
            isCancelled = { false },
            onProgress = { p ->
                onProgress(
                    p.downloadedBytes,
                    p.totalBytes,
                    "Downloading host bootstrap from GitHub"
                )
            }
        )
        if (!ok) {
            Log.e(TAG, "Host bootstrap download failed for ${pin.fileName}")
            return null
        }
        val file = File(destDir, pin.fileName)
        return BootstrapSource(file.inputStream(), file.length())
    }

    /**
     * Move `usr/var/lib/proot-distro` aside (same-FS rename) so a re-extract can
     * restore installed guests. Returns null when nothing to preserve.
     */
    private fun preserveProotDistro(ctx: Context): File? {
        val src = File(ctx.filesDir, "usr/var/lib/proot-distro")
        if (!src.exists()) return null
        val aside = File(ctx.filesDir, ".preserved_proot_distro")
        aside.deleteRecursively()
        return if (src.renameTo(aside)) aside else {
            // Rename failed (unlikely on same FS) — copy so we never lose the guest.
            aside.mkdirs()
            src.copyRecursively(aside, overwrite = true)
            aside
        }
    }

    /** Streaming tar extractor (POSIX ustar). Entries rooted at usr/ or data/data/<pkg>/files/. */
    private fun extractTarStream(
        inputStream: InputStream,
        targetDir: File,
        totalBytes: Long,
        onProgress: (done: Long, total: Long, phase: String) -> Unit
    ) {
        val buffer = ByteArray(512)
        val doneBytes = AtomicLong(0L)
        val stripPrefix = "data/data/${TermuxHostPaths.PACKAGE}/files/"

        fun report(bytes: Long) {
            if (totalBytes > 0L && bytes - doneBytes.get() >= 1024L * 1024L) {
                doneBytes.set(bytes)
                onProgress(bytes, totalBytes, "Extracting host bootstrap")
            }
        }

        var bytesReadTotal = 0L
        var pendingLongName: String? = null
        while (true) {
            var bytesRead = 0
            while (bytesRead < 512) {
                val r = inputStream.read(buffer, bytesRead, 512 - bytesRead)
                if (r == -1) break
                bytesRead += r
            }
            if (bytesRead < 512) break

            var allZero = true
            for (b in buffer) {
                if (b != 0.toByte()) { allZero = false; break }
            }
            if (allZero) break

            fun parseString(offset: Int, length: Int): String {
                var len = 0
                while (len < length && buffer[offset + len] != 0.toByte()) { len++ }
                return String(buffer, offset, len, Charsets.UTF_8)
            }

            var name: String
            val prefix = parseString(345, 155)
            if (pendingLongName != null) {
                name = pendingLongName!!
                pendingLongName = null
            } else {
                name = parseString(0, 100)
                if (prefix.isNotEmpty()) name = "$prefix/$name"
            }

            val sizeStr = parseString(124, 12).trim()
            val size = try { sizeStr.toLong(8) } catch (e: Exception) { 0L }
            val type = buffer[156].toInt().toChar()
            val linkName = parseString(157, 100)

            if (type == 'L') {
                val longNameBytes = ByteArray(size.toInt())
                var longNameRead = 0
                while (longNameRead < size) {
                    val r = inputStream.read(longNameBytes, longNameRead, (size - longNameRead).toInt())
                    if (r == -1) break
                    longNameRead += r
                }
                pendingLongName = String(longNameBytes, 0, longNameRead, Charsets.UTF_8).trimEnd('\u0000')
                val longNamePadding = ((512 - (size % 512)) % 512).toInt()
                if (longNamePadding > 0) skipBytes(inputStream, longNamePadding.toLong())
                continue
            }

            val relPath = name
                .replaceFirst("^$stripPrefix".toRegex(), "")
                .trimStart('/')
            if (relPath.isEmpty()) {
                val dataBlocks = Math.ceil(size.toDouble() / 512.0).toLong()
                skipBytes(inputStream, dataBlocks * 512L)
                continue
            }

            val outFile = File(targetDir, relPath)

            if (type == '5') {
                outFile.mkdirs()
            } else if (type == '2') {
                outFile.parentFile?.mkdirs()
                try {
                    val link = outFile.toPath()
                    val target = java.nio.file.Paths.get(linkName)
                    if (!(java.nio.file.Files.isSymbolicLink(link) &&
                                java.nio.file.Files.readSymbolicLink(link) == target)
                    ) {
                        // Replace stale/wrong entries with a real link — never
                        // overwrite with a copy (a copy loses +x and a later
                        // retry dies with EACCES exit=126 on this file).
                        java.nio.file.Files.deleteIfExists(link)
                        java.nio.file.Files.createSymbolicLink(link, target)
                    }
                } catch (e: Exception) {
                    try {
                        val linkTarget = File(outFile.parentFile, linkName)
                        if (linkTarget.exists() && linkTarget.isFile) {
                            linkTarget.copyTo(outFile, overwrite = true)
                            // Copies lose Unix modes — restore owner-exec (real chmod).
                            applyTarMode(outFile, 0b111_000_000)
                        }
                    } catch (_: Exception) {}
                }
            } else if (type == '0' || type == '\u0000') {
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { fos ->
                    var remaining = size
                    val copyBuf = ByteArray(8192)
                    while (remaining > 0) {
                        val toRead = Math.min(copyBuf.size.toLong(), remaining).toInt()
                        val r = inputStream.read(copyBuf, 0, toRead)
                        if (r == -1) break
                        fos.write(copyBuf, 0, r)
                        remaining -= r
                        bytesReadTotal += r
                        report(bytesReadTotal)
                    }
                }
                val padding = ((512 - (size % 512)) % 512).toInt()
                if (padding > 0) {
                    skipBytes(inputStream, padding.toLong())
                }
                val modeStr = parseString(100, 8).trim()
                try {
                    applyTarMode(outFile, modeStr.toInt(8))
                } catch (_: Exception) {}
            } else {
                val dataBlocks = Math.ceil(size.toDouble() / 512.0).toLong()
                skipBytes(inputStream, dataBlocks * 512L)
            }
        }
    }

    /**
     * Apply a tar header mode with a REAL chmod. [File.setExecutable] is a
     * documented no-op on some Android 16 file trees (see HostScriptDeployer),
     * which leaves extracted host tools mode 0600 -> execve fails with EACCES.
     */
    private fun applyTarMode(file: File, mode: Int) {
        if (mode <= 0) return
        try {
            Os.chmod(file.absolutePath, mode and 0x1FF)
        } catch (_: Throwable) {
            file.setReadable(true, false)
            file.setWritable(true, false)
            if ((mode and 73) != 0) file.setExecutable(true, false)
        }
    }

    /**
     * W^X / retry safety net (runs once per extraction, after the package
     * rewrite): owner-exec on every host tool dir. chmod follows file symlinks,
     * so `cp -> coreutils` etc. are repaired through their targets. Containers
     * (usr/var/lib/proot-distro) and usr/lib shared objects are never touched.
     */
    private fun repairHostExecPermissions(filesDir: File) {
        val execDirs = listOf(
            File(filesDir, "usr/bin"),
            File(filesDir, "usr/libexec"),
            File(filesDir, "usr/lib/apt/methods")
        )
        var fixed = 0
        for (root in execDirs) {
            if (!root.isDirectory) continue
            runCatching {
                root.walkTopDown().forEach { f ->
                    runCatching {
                        if (f.isDirectory) {
                            applyTarMode(f, 0b111_101_101) // 0755
                        } else if (f.isFile) {
                            applyTarMode(f, 0b111_000_000) // 0700
                            fixed++
                        }
                    }
                }
            }
        }
        val home = File(filesDir, "home")
        if (home.isDirectory) {
            runCatching {
                home.walkTopDown().forEach { f ->
                    runCatching {
                        if (f.isDirectory) applyTarMode(f, 0b111_101_101)
                        else if (f.isFile) applyTarMode(f, 0b111_000_000)
                    }
                }
            }
        }
        Log.i(TAG, "host exec repair: chmod applied to $fixed entries")
    }

    private fun skipBytes(inputStream: InputStream, count: Long) {
        var remaining = count
        val buf = ByteArray(8192)
        while (remaining > 0) {
            val toRead = Math.min(buf.size.toLong(), remaining).toInt()
            val r = inputStream.read(buf, 0, toRead)
            if (r == -1) break
            remaining -= r
        }
    }
}
