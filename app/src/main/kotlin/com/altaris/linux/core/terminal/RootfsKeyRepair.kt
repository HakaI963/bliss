package com.altaris.linux.core.terminal

import android.content.Context
import android.os.Process
import android.util.Log
import com.altaris.linux.core.system.ShizukuShell
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.locks.ReentrantLock

/**
 * Repairs a proot rootfs that was **moved** from the legacy package id.
 *
 * Android file-based encryption wraps each file (and each symlink target) with
 * the owning app's data key. The old `LegacyDataMigration` moved the tree, so
 * symlinks kept the old package's key: the new app gets EACCES when it follows
 * them. A loader that cannot read `/usr/lib/libreadline.so.8` reports missing
 * readline symbols, and `/bin/sh`/`/usr/bin/env` (absolute symlinks) fail the
 * same way — the "all distros are broken" state after the rename.
 *
 * Regular files are copied back by root (root writes are readable by the app);
 * symlinks are recreated **by the app itself** (root-created links keep the
 * wrong key). Root only reads the link targets and removes the old links.
 * Runs once per rootfs, guarded by a marker under filesDir.
 */
object RootfsKeyRepair {

    private const val TAG = "RootfsKeyRepair"
    private const val MARKER_DIR = ".rootfs-key-repair"

    /** Serializes the app-start pass and a terminal-open pass. */
    private val repairLock = ReentrantLock()

    fun ensureRepaired(ctx: Context, rootfs: File?) {
        if (rootfs == null || !rootfs.isDirectory) return
        // Fast path: the app-start pass already finished this container.
        if (marker(ctx, rootfs).isFile) return
        // Never queue a terminal open behind a long repair (Shizuku rootfs
        // passes can take minutes). The app-start thread keeps working; if it
        // is busy now, this session proceeds and the next open sees the marker.
        if (!repairLock.tryLock()) {
            Log.i(TAG, "repair for ${rootfs.name} already running; not blocking session open")
            return
        }
        try {
            if (marker(ctx, rootfs).isFile) return
            if (!ShizukuShell.available()) return
            runCatching { repair(ctx.applicationContext, rootfs) }
                .onFailure { Log.w(TAG, "repair ${rootfs.name} failed: ${it.message}") }
        } finally {
            repairLock.unlock()
        }
    }

    private fun repair(ctx: Context, rootfs: File) {
        val key = keyOf(rootfs)
        val manifest = File(ctx.cacheDir, "altaris-symlinks-$key.tsv")
        // A previous pass removed the links but the app died before recreating
        // them. The manifest is the source of truth until it is consumed.
        if (manifest.isFile && manifest.length() > 0L) {
            if (recreateFromManifest(manifest, rootfs)) {
                runCatching { manifest.delete() }
                markDone(ctx, rootfs)
            }
            return
        }
        val broken = hasBrokenSymlinks(rootfs)
        // The key problem is per-tree: when the links are readable the regular
        // files are too. Avoid a full read() sweep of a fresh, large rootfs.
        val unreadable = if (broken) unreadableFiles(rootfs) else emptyList()
        if (!broken && unreadable.isEmpty()) {
            markDone(ctx, rootfs)
            return
        }
        Log.i(TAG, "repairing ${rootfs.name}: brokenLinks=$broken unreadable=${unreadable.size}")

        if (unreadable.isNotEmpty()) {
            val list = File(ctx.cacheDir, "altaris-unreadable-$key.txt")
            list.writeText(unreadable.joinToString("\n") { it.absolutePath })
            val uid = Process.myUid()
            val script = buildString {
                append("while IFS= read -r f; do ")
                append("[ -f \"\$f\" ] || continue; ")
                append("cp -p \"\$f\" \"\$f.reenc\" 2>/dev/null && ")
                append("mv -f \"\$f.reenc\" \"\$f\" 2>/dev/null; ")
                append("chown $uid:$uid \"\$f\" 2>/dev/null; ")
                append("done < '${list.absolutePath}'")
            }
            val result = ShizukuShell.run(script, 120_000L)
            if (!result.ok) {
                Log.w(TAG, "file re-copy failed: ${result.error}")
            }
            runCatching { list.delete() }
        }

        if (!broken) {
            markDone(ctx, rootfs)
            return
        }

        if (!manifest.isFile || manifest.length() == 0L) {
            // Root reads every target (it still holds the key) and removes the
            // links. The app recreates them below with its own key.
            val uid = Process.myUid()
            val script = buildString {
                append("M='${manifest.absolutePath}'; R='${rootfs.absolutePath}'; ")
                append(": > \"\$M\"; ")
                append("find \"\$R\" -type l 2>/dev/null | while IFS= read -r l; do ")
                append("t=\$(readlink \"\$l\" 2>/dev/null) || continue; ")
                append("printf '%s\\t%s\\n' \"\$l\" \"\$t\" >> \"\$M\"; done; ")
                append("find \"\$R\" -type l -exec rm -f {} + 2>/dev/null; ")
                append("chown $uid:$uid \"\$M\" 2>/dev/null; chmod 600 \"\$M\" 2>/dev/null")
            }
            val result = ShizukuShell.run(script, 120_000L)
            if (!result.ok) {
                Log.w(TAG, "symlink manifest failed: ${result.error}")
                return
            }
            if (!manifest.isFile || manifest.length() == 0L) {
                // Root could not enumerate the links; retry next launch.
                Log.w(TAG, "empty symlink manifest; leaving rootfs untouched")
                return
            }
        }

        if (recreateFromManifest(manifest, rootfs)) {
            runCatching { manifest.delete() }
            markDone(ctx, rootfs)
        }
    }

    /** @return true when every link in the manifest now exists. */
    private fun recreateFromManifest(manifest: File, rootfs: File): Boolean {
        var recreated = 0
        var failed = 0
        manifest.forEachLine { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@forEachLine
            val path = line.substring(0, tab)
            val target = line.substring(tab + 1)
            if (target.isEmpty()) return@forEachLine
            val link = Paths.get(path)
            if (!link.startsWith(rootfs.toPath())) return@forEachLine
            if (Files.isSymbolicLink(link)) return@forEachLine
            runCatching {
                Files.createSymbolicLink(link, Paths.get(target))
                recreated++
            }.onFailure { failed++ }
        }
        Log.i(TAG, "symlinks recreated=$recreated failed=$failed")
        return failed == 0
    }

    /** Every container rootfs is named `rootfs`; use the container dir. */
    private fun keyOf(rootfs: File): String =
        rootfs.parentFile?.name?.ifBlank { null } ?: rootfs.name

    private fun marker(ctx: Context, rootfs: File): File =
        File(File(ctx.filesDir, MARKER_DIR), keyOf(rootfs))

    private fun markDone(ctx: Context, rootfs: File) {
        runCatching {
            val m = marker(ctx, rootfs)
            m.parentFile?.mkdirs()
            m.writeText("1")
        }
    }

    private fun hasBrokenSymlinks(rootfs: File): Boolean {
        var broken = false
        runCatching {
            Files.walkFileTree(rootfs.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isSymbolicLink) {
                        val target = runCatching { Files.readSymbolicLink(file).toString() }
                            .getOrNull()
                        if (target.isNullOrEmpty()) {
                            broken = true
                            return FileVisitResult.TERMINATE
                        }
                    }
                    return FileVisitResult.CONTINUE
                }
            })
        }
        return broken
    }

    private fun unreadableFiles(rootfs: File): List<File> {
        val out = ArrayList<File>()
        runCatching {
            Files.walkFileTree(rootfs.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) {
                        val f = file.toFile()
                        if (!f.canRead()) out.add(f)
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult =
                    FileVisitResult.CONTINUE
            })
        }
        return out
    }
}
