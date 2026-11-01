package com.altaris.linux.core.terminal

import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermission

/**
 * Makes a guest's home directory writable by the guest account.
 *
 * Why this exists: setup scripts ran as root and wrote into the home with the
 * wrong owner, so the account could not write its own dotfiles. The symptom is
 * unmistakable and shows up in every CLI that keeps state:
 *
 *   opencode → EACCES: permission denied, mkdir '/home/altaris/.local/share/opencode'
 *   mkdir -p ~/.local/share/opencode → Permission denied
 *   sudo chown -R "$USER" ~/.local          (fixes one directory)
 *   opencode → EACCES: permission denied, mkdir '/home/altaris/.cache/opencode'
 *
 * A user cannot be expected to chase that directory by directory. This walks
 * the home's top-level entries and the CLI state directories that matter, and
 * repairs only what is wrong, so it is cheap to run before a session.
 *
 * Proot has no real uid mapping: every file in the rootfs is owned by the app
 * uid on the host, and the guest sees the account as its mapped uid. Ownership
 * therefore has to be fixed in *guest* terms, which proot-distro does by
 * mapping the app uid to the login user. The one thing the app must guarantee
 * is that the tree is writable at all — a root-created file with mode 0644 and
 * no group/other write is unwritable no matter what the mapping says.
 */
object GuestHomeRepair {

    private const val TAG = "GuestHomeRepair"

    /** Directories that hold per-user state for the CLIs people actually run. */
    private val STATE_DIRS = listOf(
        ".local",
        ".local/share",
        ".local/state",
        ".local/bin",
        ".cache",
        ".config",
        ".npm",
        ".bun",
        ".cargo",
        ".rustup",
        ".local/share/opencode",
        ".cache/opencode",
        ".config/opencode",
        ".local/share/npm",
        ".config/npm",
    )

    /**
     * Ensure the guest home of [rootfs] is writable. Best-effort: a failure
     * here must never stop a session from opening.
     */
    fun ensureWritable(rootfs: File?) {
        if (rootfs == null || !rootfs.isDirectory) return
        runCatching {
            val passwd = GuestUserResolver.passwdOf(rootfs) ?: return
            val user = GuestUserResolver.resolve("altaris", passwd)
            if (user == "root") return
            val home = File(rootfs, GuestUserResolver.homeOf(user, passwd).trimStart('/'))
            if (!home.isDirectory) return
            repair(home)
        }.onFailure { Log.w(TAG, "home repair failed: ${it.message}") }
    }

    private fun repair(home: File) {
        // The home itself first: without a writable home nothing else matters.
        makeWritable(home, recursive = false)
        // Each top-level entry, then the known state trees. A root-created
        // directory is the usual culprit, and it is usually one level down.
        home.listFiles()?.forEach { entry ->
            makeWritable(entry, recursive = true)
        }
        for (rel in STATE_DIRS) {
            val dir = File(home, rel)
            if (dir.exists()) makeWritable(dir, recursive = true)
        }
    }

    /**
     * Add owner write and make sure directories are traversable.
     *
     * Mode bits are the portable part: the app cannot chown inside a rootfs to
     * a uid the guest will see, but it can make the file writable by everyone,
     * which is exactly what a single-user guest needs.
     */
    private fun makeWritable(file: File, recursive: Boolean) {
        runCatching {
            val path = file.toPath()
            if (Files.isSymbolicLink(path)) return@runCatching
            val perms = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
                .toMutableSet()
            var changed = false
            if (PosixFilePermission.OWNER_WRITE !in perms) {
                perms += PosixFilePermission.OWNER_WRITE
                changed = true
            }
            if (file.isDirectory) {
                for (p in listOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE,
                )) {
                    if (p !in perms) {
                        perms += p
                        changed = true
                    }
                }
            } else if (PosixFilePermission.OWNER_READ !in perms) {
                perms += PosixFilePermission.OWNER_READ
                changed = true
            }
            if (changed) {
                Files.setPosixFilePermissions(path, perms)
            }
        }.onFailure { e ->
            if (e !is IOException) throw e
        }
        if (!recursive) return
        val children = file.listFiles() ?: return
        for (child in children) {
            if (Files.isSymbolicLink(child.toPath())) continue
            makeWritable(child, recursive = true)
        }
    }
}
