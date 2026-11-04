package com.altaris.linux.core.files

import android.content.Context
import android.util.Log
import com.altaris.linux.core.install.DistroInstallProfile
import com.altaris.linux.core.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The filesystem behind the Utility → Files browser.
 *
 * Three kinds of place are browsable, and they are not the same thing:
 *
 *  - **host** — `/sdcard`, the app's own directories, and (with root) `/data`,
 *    `/system`. Some of it is readable as the app uid, some only through `su`.
 *  - **proot guest** — a distro rootfs under `filesDir/usr/var/lib/proot-distro`.
 *    Plain files as far as this process is concerned.
 *  - **chroot guest** — a tree under `/data/local`, which is `shell_data_file`
 *    under SELinux and **unreadable** to the app uid. Every operation there has
 *    to go through root, which is why it cannot share the proot path.
 *
 * That last difference is the whole reason this is one object with two backends
 * instead of a plain `File` walk: a `File.list()` on a chroot path returns null
 * and would render an installed chroot as an empty directory.
 */
object FileBrowser {

    private const val TAG = "AltarisFiles"

    /** A place the browser can open. */
    data class Place(
        val id: String,
        val label: String,
        /** Absolute path on this device. */
        val path: String,
        val kind: Kind,
        /** True when every operation must go through root. */
        val rootOnly: Boolean,
    ) {
        enum class Kind(val label: String) {
            HOST("Host"),
            PROOT("proot"),
            CHROOT("chroot"),
        }
    }

    /** One directory entry. */
    data class Entry(
        val name: String,
        val path: String,
        val dir: Boolean,
        val size: Long,
        /** Epoch millis, or 0 when the backend could not report it. */
        val modified: Long,
        val mode: String = "",
        /** True when this process cannot read it without root. */
        val needsRoot: Boolean = false,
    ) {
        /** Lowercase extension, or "" for none. */
        val ext: String get() = name.substringAfterLast('.', "").lowercase()
    }

    /** A directory listing plus what it cost to get it. */
    data class Listing(
        val path: String,
        val entries: List<Entry> = emptyList(),
        val error: String = "",
        val usedRoot: Boolean = false,
    )

    // ------------------------------------------------------------------ places

    /**
     * Every place the browser can open.
     *
     * Only what is really there: a distro that is not installed must not appear
     * as an empty folder. Chroot entries are gated on root because without it
     * the path cannot be read at all.
     */
    fun places(context: Context): List<Place> {
        val app = context.applicationContext
        val out = ArrayList<Place>()

        // Host storage first: it is what people open most.
        addIfReadable(out, "sdcard", "Internal storage", "/sdcard", Place.Kind.HOST, false)
        addIfReadable(out, "download", "Downloads", "/sdcard/Download", Place.Kind.HOST, false)
        addIfReadable(out, "app", "Altaris data", app.filesDir.absolutePath, Place.Kind.HOST, false)

        // Full device access, not just the download folder.
        //
        // Only /sdcard, Downloads and the app's own data were listed, so the
        // browser could not reach anything else — no DCIM, no other apps'
        // storage, no system partition. These are the real roots.
        addIfReadable(out, "storage", "All storage", "/storage", Place.Kind.HOST, false)
        addIfReadable(out, "data", "App data (/data)", "/data", Place.Kind.HOST, true)
        addIfReadable(out, "system", "System (/system)", "/system", Place.Kind.HOST, true)
        addIfReadable(out, "vendor", "Vendor (/vendor)", "/vendor", Place.Kind.HOST, true)
        addIfReadable(out, "etc", "Config (/etc)", "/etc", Place.Kind.HOST, true)

        // The whole filesystem, last: with root this is the only entry that can
        // reach everything else.
        val root = RootShell.isRootAvailable()
        if (root) {
            out.add(Place("root", "Root /", "/", Place.Kind.HOST, true))
        }

        // Installed distros, proot and chroot.
        val containers = File(app.filesDir, "usr/var/lib/proot-distro/containers")
        containers.listFiles().orEmpty().sortedBy { it.name }.forEach { dir ->
            val rootfs = File(dir, "rootfs")
            if (!rootfs.isDirectory) return@forEach
            val profile = DistroInstallProfile.forId(dir.name)
            out.add(
                Place(
                    id = "proot:" + dir.name,
                    label = (profile?.displayName ?: dir.name) + " (proot)",
                    path = rootfs.absolutePath,
                    kind = Place.Kind.PROOT,
                    rootOnly = false,
                )
            )
        }
        if (root) {
            val chroots = File("/data/local/altaris/chroots")
            val names = runCatching {
                RootShell.captureResult(
                    "ls -1 " + quote(chroots.absolutePath) + " 2>/dev/null",
                    20_000L,
                ).stdout.lines().map { it.trim() }.filter { it.isNotBlank() }
            }.getOrDefault(emptyList())
            names.forEach { name ->
                val profile = DistroInstallProfile.forId(name) ?: DistroInstallProfile.forId(name + "_chroot")
                out.add(
                    Place(
                        id = "chroot:" + name,
                        label = (profile?.displayName ?: name) + " (chroot)",
                        path = chroots.absolutePath + "/" + name,
                        kind = Place.Kind.CHROOT,
                        rootOnly = true,
                    )
                )
            }
        }
        return out
    }

    private fun addIfReadable(
        out: MutableList<Place>,
        id: String,
        label: String,
        path: String,
        kind: Place.Kind,
        rootOnly: Boolean,
    ) {
        if (File(path).isDirectory) {
            out.add(Place(id, label, path, kind, rootOnly))
        }
    }

    /** Find the place a path belongs to, so the kind survives navigation. */
    fun placeFor(context: Context, path: String): Place? =
        places(context).filter { path == it.path || path.startsWith(it.path + "/") }
            .maxByOrNull { it.path.length }

    // ------------------------------------------------------------------- list

    /**
     * List [path].
     *
     * A chroot path is listed through root in one `find`; everything else uses
     * `File`. The output format is `type\tsize\tmtime\tmode\tname`, with the name
     * last so a name containing a tab cannot shift the other fields.
     */
    suspend fun list(context: Context, path: String, showHidden: Boolean = false): Listing =
        withContext(Dispatchers.IO) {
            val place = placeFor(context, path)
            if (place != null && place.kind == Place.Kind.CHROOT) {
                return@withContext listThroughRoot(path, showHidden)
            }
            val dir = File(path)
            if (!dir.isDirectory) {
                return@withContext Listing(path, error = "Not a directory")
            }
            val children = dir.listFiles()
            if (children == null) {
                // Unreadable as this uid. If root can reach it, use root rather
                // than showing an empty folder.
                if (RootShell.isRootAvailable()) return@withContext listThroughRoot(path, showHidden)
                return@withContext Listing(path, error = "Permission denied")
            }
            val entries = children
                .filter { showHidden || !it.name.startsWith(".") }
                .map { f ->
                    Entry(
                        name = f.name,
                        path = f.absolutePath,
                        dir = f.isDirectory,
                        size = if (f.isFile) f.length() else 0L,
                        modified = f.lastModified(),
                        mode = if (f.canRead()) "r" else "",
                        needsRoot = !f.canRead(),
                    )
                }
                .sortedWith(compareBy({ !it.dir }, { it.name.lowercase() }))
            Listing(path, entries)
        }

    /**
     * List a directory as root.
     *
     * One `find -maxdepth 1` prints everything in a single call. `%f` gives the
     * bare name, so no path parsing is needed and a name with a space is safe.
     */
    private fun listThroughRoot(path: String, showHidden: Boolean): Listing {
        val q = quote(path)
        val script = "find " + q + " -mindepth 1 -maxdepth 1 " +
            "-printf '%y\\t%s\\t%T@\\t%M\\t%f\\n' 2>/dev/null | head -n 4000"
        val result = runCatching { RootShell.captureResult(script, 45_000L) }.getOrNull()
            ?: return Listing(path, error = "root unavailable")
        if (result.exitCode != 0 && result.stdout.isBlank()) {
            return Listing(path, error = "Cannot read this directory", usedRoot = true)
        }
        val entries = result.stdout.lines().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val parts = line.split('\t')
            if (parts.size < 5) return@mapNotNull null
            val name = parts[4]
            if (!showHidden && name.startsWith(".")) return@mapNotNull null
            val dir = parts[0] == "d"
            Entry(
                name = name,
                path = path.trimEnd('/') + "/" + name,
                dir = dir,
                size = parts[1].toLongOrNull() ?: 0L,
                // `%T@` is "seconds.fraction"; keep the seconds.
                modified = (parts[2].toDoubleOrNull()?.toLong() ?: 0L) * 1000L,
                mode = parts[3],
            )
        }.sortedWith(compareBy({ !it.dir }, { it.name.lowercase() }))
        return Listing(path, entries, usedRoot = true)
    }

    // ---------------------------------------------------------------- content

    /** Read a text file, through root when it is a chroot path. */
    suspend fun read(context: Context, path: String, maxBytes: Int = 512 * 1024): Result<String> =
        withContext(Dispatchers.IO) {
            val place = placeFor(context, path)
            if (place != null && place.kind == Place.Kind.CHROOT) {
                val result = runCatching {
                    RootShell.captureResult(
                        "head -c " + maxBytes + " " + quote(path),
                        60_000L,
                    )
                }.getOrNull()
                return@withContext if (result != null && result.exitCode == 0) {
                    Result.success(result.stdout)
                } else {
                    Result.failure(IllegalStateException("Cannot read this file"))
                }
            }
            runCatching { File(path).readText().take(maxBytes) }
        }

    /** Write a text file. */
    suspend fun write(context: Context, path: String, body: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val place = placeFor(context, path)
            if (place != null && place.kind == Place.Kind.CHROOT) {
                // The body goes through a heredoc with a quoted delimiter, so
                // nothing in the file is expanded by the shell.
                val script = buildString {
                    append("cat > ").append(quote(path)).append(" <<'ALTARIS_FILE_EOF'\n")
                    append(body)
                    if (!body.endsWith("\n")) append('\n')
                    append("ALTARIS_FILE_EOF\n")
                }
                val result = runCatching { RootShell.captureResult(script, 60_000L) }.getOrNull()
                return@withContext if (result != null && result.exitCode == 0) {
                    Result.success(Unit)
                } else {
                    Result.failure(IllegalStateException("Cannot write this file"))
                }
            }
            runCatching { File(path).writeText(body) }
        }

    /** Create an empty file. */
    suspend fun createFile(context: Context, path: String): Result<Unit> =
        write(context, path, "")

    /** Create a directory. */
    suspend fun createDirectory(context: Context, path: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val place = placeFor(context, path)
            if (place != null && place.kind == Place.Kind.CHROOT) {
                val result = runCatching {
                    RootShell.captureResult("mkdir -p " + quote(path), 30_000L)
                }.getOrNull()
                return@withContext if (result != null && result.exitCode == 0) {
                    Result.success(Unit)
                } else {
                    Result.failure(IllegalStateException("Cannot create this directory"))
                }
            }
            runCatching {
                if (!File(path).mkdirs()) throw IllegalStateException("mkdir failed")
            }
        }

    /**
     * Delete [path].
     *
     * Destructive, so it is scoped: `rm -rf` runs with `-x` on a chroot so it
     * can never cross a bind mount out of the tree, and the host/proot path
     * refuses to delete a root-level directory.
     */
    suspend fun delete(context: Context, path: String): Result<Unit> = withContext(Dispatchers.IO) {
        val trimmed = path.trimEnd('/')
        if (trimmed.count { it == '/' } <= 1) {
            return@withContext Result.failure(
                IllegalStateException("Refusing to delete a top-level directory"),
            )
        }
        val place = placeFor(context, trimmed)
        if (place != null && place.kind == Place.Kind.CHROOT) {
            val result = runCatching {
                RootShell.captureResult("rm -rf " + quote(trimmed), 5 * 60_000L)
            }.getOrNull()
            return@withContext if (result != null && result.exitCode == 0) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("Cannot delete this"))
            }
        }
        runCatching {
            val target = File(trimmed)
            if (!target.deleteRecursively()) throw IllegalStateException("delete failed")
        }
    }

    /** Rename [from] to [to] (same directory). */
    suspend fun rename(context: Context, from: String, to: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val place = placeFor(context, from)
            if (place != null && place.kind == Place.Kind.CHROOT) {
                val result = runCatching {
                    RootShell.captureResult(
                        "mv " + quote(from) + " " + quote(to),
                        60_000L,
                    )
                }.getOrNull()
                return@withContext if (result != null && result.exitCode == 0) {
                    Result.success(Unit)
                } else {
                    Result.failure(IllegalStateException("Cannot rename this"))
                }
            }
            runCatching {
                if (!File(from).renameTo(File(to))) {
                    throw IllegalStateException("rename failed")
                }
            }
        }

    /** Copy [from] to [to]. */
    suspend fun copy(context: Context, from: String, to: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching<Unit> {
                val source = File(from)
                val target = File(to)
                if (source.isDirectory) {
                    source.copyRecursively(target, overwrite = false)
                } else {
                    target.parentFile?.mkdirs()
                    source.copyTo(target, overwrite = false)
                }
                Unit
            }
        }

    // ------------------------------------------------------------------ paths

    /** Parent of [path], or null at the top of its place. */
    fun parent(context: Context, path: String): String? {
        val place = placeFor(context, path) ?: return null
        if (path == place.path) return null
        return File(path).parent
    }

    /** Breadcrumb segments from the place root to [path]. */
    fun crumbs(context: Context, path: String): List<Pair<String, String>> {
        val place = placeFor(context, path) ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        out.add(place.label to place.path)
        if (path == place.path) return out
        val relative = path.removePrefix(place.path).trim('/')
        var running = place.path
        relative.split('/').filter { it.isNotBlank() }.forEach { segment ->
            running = running.trimEnd('/') + "/" + segment
            out.add(segment to running)
        }
        return out
    }

    /**
     * True when a file is small enough and looks like text.
     *
     * Reading a 2 GB binary into a Compose string is how the app runs out of
     * memory, so the viewer refuses rather than trying.
     */
    fun isViewable(entry: Entry): Boolean =
        !entry.dir && entry.size in 1..(2L * 1024 * 1024)

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** The default directory when the tab opens. */
    fun defaultPath(context: Context): String {
        val places = places(context)
        // Internal storage, not the download folder: the browser used to open
        // in Downloads, which made it look like only downloads were reachable.
        return places.firstOrNull { it.id == "sdcard" }?.path
            ?: places.firstOrNull { it.id == "storage" }?.path
            ?: places.firstOrNull { it.id == "download" }?.path
            ?: places.firstOrNull()?.path
            ?: context.filesDir.absolutePath
    }

    /** How the listing is ordered. */
    enum class Sort(val label: String) {
        NAME("Name"),
        SIZE("Size"),
        DATE("Date"),
    }

    /** Order a listing: directories first, then the chosen key. */
    fun sorted(entries: List<Entry>, by: Sort, descending: Boolean = false): List<Entry> {
        val comparator: Comparator<Entry> = when (by) {
            Sort.NAME -> compareBy { it.name.lowercase() }
            Sort.SIZE -> compareBy { it.size }
            Sort.DATE -> compareBy { it.modified }
        }
        val ordered = entries.sortedWith(comparator)
        val result = if (descending) ordered.reversed() else ordered
        // Directories stay on top in name order; that is what every file
        // manager does and what makes a deep tree navigable.
        return result.sortedByDescending { it.dir }
    }

    /** Filter a listing by a substring of the name, case-insensitively. */
    fun filtered(entries: List<Entry>, query: String): List<Entry> {
        val q = query.trim()
        if (q.isEmpty()) return entries
        return entries.filter { it.name.contains(q, ignoreCase = true) }
    }
}
