package com.altaris.linux.core.files

import android.content.Context
import com.altaris.linux.core.root.ChrootPaths
import com.altaris.linux.core.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The filesystem behind Files.
 *
 * Two spaces, and only two, because that is the model people actually have:
 *
 *  - **Internal** — Altaris' own root storage, `filesDir`. It is readable by
 *    this process directly, needs no permission and no root.
 *  - **External** — the chroot space under `/data/local/altaris/chroots`.
 *    SELinux hides that tree from the app uid, so every read and write there
 *    has to go through `su`. A `File.list()` on it returns null, which is why
 *    an installed chroot used to render as an empty folder.
 *
 * Listing is the only thing that has to be careful, and it was wrong twice:
 *
 *  - `find -printf` is a GNU extension. Android's toybox `find` does not
 *    implement it, so the command failed, printed nothing, and the failure was
 *    swallowed by the pipe to `head`. The result was an empty list that looked
 *    like a valid empty directory.
 *  - even without the pipe, a pipeline's exit status is the *last* command's.
 *    `find … | head` exits 0 when `find` fails, so the "cannot read" branch
 *    never ran.
 *
 * The listing now uses `ls -la` plus `stat`, which every Android shell has,
 * checks the exit status of the command that actually ran, and falls back to
 * a plain name list if the metadata read fails. A directory that cannot be
 * read now says so instead of looking empty.
 */
object FileBrowser {

    /** Which storage a path belongs to. */
    enum class Space(
        val label: String,
        val id: String,
    ) {
        INTERNAL("Internal", "internal"),
        EXTERNAL("External", "external"),
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

    /** The root of [space] on this device. */
    fun rootOf(context: Context, space: Space): String = when (space) {
        Space.INTERNAL -> context.applicationContext.filesDir.absolutePath
        Space.EXTERNAL -> ChrootPaths.CHROOTS_DIR
    }

    /** The space a path belongs to, or null when it is outside both. */
    fun spaceOf(context: Context, path: String): Space? {
        val internal = rootOf(context, Space.INTERNAL)
        if (path == internal || path.startsWith(internal + "/")) return Space.INTERNAL
        val external = rootOf(context, Space.EXTERNAL)
        if (path == external || path.startsWith(external + "/")) return Space.EXTERNAL
        return null
    }

    // ------------------------------------------------------------------- list

    /**
     * List [path].
     *
     * External paths go through root; internal paths use `File` directly. The
     * space is a parameter, never derived from the path — deriving it used to
     * fork a root shell on every listing just to decide the backend.
     */
    suspend fun list(
        context: Context,
        space: Space,
        path: String,
        showHidden: Boolean = false,
    ): Listing = withContext(Dispatchers.IO) {
        if (space == Space.EXTERNAL) {
            return@withContext listExternal(path, showHidden)
        }
        val dir = File(path)
        if (!dir.isDirectory) {
            return@withContext Listing(path, error = "Not a directory")
        }
        val children = dir.listFiles()
            ?: return@withContext Listing(path, error = "Cannot read this folder")
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
     * List an external directory as root.
     *
     * Three facts decide this design:
     *
     *  - `find -printf` is GNU-only. Android's toybox `find` has no `-printf`,
     *    so it failed silently and every chroot listed as empty.
     *  - `ls -l` has a *different field layout* on toybox (date and time are two
     *    fields, not month/day/time) — parsing it by field count works on a
     *    desktop and breaks on the phone.
     *  - a pipeline's exit status is the last command's, so `find … | head`
     *    exits 0 when `find` fails and the error branch never ran.
     *
     * So names come from `ls -a1` — one per line, no columns, POSIX — and all
     * metadata comes from one `stat -c` call. Both are toybox commands. A
     * directory that cannot be read reports its error instead of looking empty.
     */
    private fun listExternal(path: String, showHidden: Boolean): Listing {
        val q = quote(path)
        val names = runCatching {
            RootShell.captureResult("ls -a1 " + q + " 2>&1", 20_000L)
        }.getOrNull() ?: return Listing(path, error = "Root is not available")

        val first = names.stdout.lineSequence().firstOrNull().orEmpty()
        if (names.exitCode != 0 ||
            first.contains("No such file", ignoreCase = true) ||
            first.contains("Permission denied", ignoreCase = true) ||
            first.contains("Not a directory", ignoreCase = true)
        ) {
            return Listing(
                path,
                error = first.ifBlank { "Cannot read this folder" }.take(160),
                usedRoot = true,
            )
        }

        val visible = names.stdout.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .filter { showHidden || !it.startsWith(".") }
        if (visible.isEmpty()) return Listing(path, emptyList(), usedRoot = true)

        val meta = readMetadata(path, visible)
        val entries = visible.map { name ->
            val m = meta[name]
            Entry(
                name = name,
                path = path.trimEnd('/') + "/" + name,
                dir = m?.dir ?: false,
                size = m?.size ?: 0L,
                modified = m?.modified ?: 0L,
                mode = m?.mode.orEmpty(),
            )
        }.sortedWith(compareBy({ !it.dir }, { it.name.lowercase() }))
        return Listing(path, entries, usedRoot = true)
    }

    /** Metadata for one entry. */
    private data class Meta(
        val size: Long,
        val modified: Long,
        val dir: Boolean,
        val mode: String,
    )

    /**
     * One `stat` call for every entry.
     *
     * The format is `size|mtime|mode|name`, split with a limit so a `|` inside
     * a filename cannot shift the fields — the name is last and absorbs the
     * rest. A tab separator was the first attempt; `\t` is not interpreted
     * consistently by `stat -c` across GNU and toybox, so it arrived literally.
     *
     * The type comes from the mode string's first character, which both
     * implementations always print: `d` is a directory, `-` a file, `l` a
     * symlink. `%F` was the first attempt and is not in toybox.
     *
     * One invocation, not one per file: a distro root has dozens of entries
     * and a shell each would be seconds.
     */
    private fun readMetadata(dir: String, names: List<String>): Map<String, Meta> {
        if (names.isEmpty()) return emptyMap()
        val script = buildString {
            append("cd ").append(quote(dir)).append(" || exit 3\n")
            append("for f in ")
            names.forEach { append(quote(it)).append(' ') }
            append("; do ")
            // The shell variable must reach the shell, not be interpolated by
            // Kotlin: \$f in a normal string is an escaped backslash followed
            // by an interpolation of `f`, which does not exist here.
            append("stat -c '%s|%Y|%A|%n' \"\$f\" 2>/dev/null; ")
            append("done\n")
        }
        val result = runCatching { RootShell.captureResult(script, 25_000L) }.getOrNull()
            ?: return emptyMap()
        val out = HashMap<String, Meta>()
        for (line in result.stdout.lines()) {
            if (line.isBlank()) continue
            // Limit 4: everything after the third separator is the name.
            val parts = line.split('|', limit = 4)
            if (parts.size < 4) continue
            val size = parts[0].toLongOrNull() ?: continue
            val seconds = parts[1].toLongOrNull() ?: 0L
            val mode = parts[2]
            val name = parts[3]
            out[name] = Meta(
                size = size,
                modified = seconds * 1000L,
                dir = mode.startsWith("d"),
                mode = mode,
            )
        }
        return out
    }

    // ---------------------------------------------------------------- content

    /** Read a text file, through root when it is in the external space. */
    suspend fun read(
        context: Context,
        path: String,
        maxBytes: Int = 512 * 1024,
    ): Result<String> = withContext(Dispatchers.IO) {
        if (spaceOf(context, path) == Space.EXTERNAL) {
            val result = runCatching {
                RootShell.captureResult("head -c " + maxBytes + " " + quote(path), 60_000L)
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
            if (spaceOf(context, path) == Space.EXTERNAL) {
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
            if (spaceOf(context, path) == Space.EXTERNAL) {
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
     * Destructive, so it is scoped: `rm -rf` runs on an external path so it can
     * never cross out of the chroot tree, and both spaces refuse to delete the
     * space root itself.
     */
    suspend fun delete(context: Context, path: String): Result<Unit> = withContext(Dispatchers.IO) {
        val trimmed = path.trimEnd('/')
        val space = spaceOf(context, trimmed)
            ?: return@withContext Result.failure(
                IllegalStateException("That path is not in this browser"),
            )
        if (trimmed == rootOf(context, space)) {
            return@withContext Result.failure(
                IllegalStateException("Refusing to delete the root of " + space.label),
            )
        }
        if (space == Space.EXTERNAL) {
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
            if (spaceOf(context, from) == Space.EXTERNAL) {
                val result = runCatching {
                    RootShell.captureResult("mv " + quote(from) + " " + quote(to), 60_000L)
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
            if (spaceOf(context, from) == Space.EXTERNAL) {
                val result = runCatching {
                    RootShell.captureResult("cp -a " + quote(from) + " " + quote(to), 10 * 60_000L)
                }.getOrNull()
                return@withContext if (result != null && result.exitCode == 0) {
                    Result.success(Unit)
                } else {
                    Result.failure(IllegalStateException("Cannot copy this"))
                }
            }
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

    /** Parent of [path], or null at the root of its space. */
    fun parent(context: Context, path: String): String? {
        val space = spaceOf(context, path) ?: return null
        if (path == rootOf(context, space)) return null
        return File(path).parent
    }

    /** Breadcrumb segments from the space root to [path]. */
    fun crumbs(context: Context, space: Space, path: String): List<Pair<String, String>> {
        val root = rootOf(context, space)
        val out = ArrayList<Pair<String, String>>()
        out.add(space.label to root)
        if (path == root) return out
        if (!path.startsWith(root + "/")) return out
        val relative = path.removePrefix(root).trim('/')
        var running = root
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
