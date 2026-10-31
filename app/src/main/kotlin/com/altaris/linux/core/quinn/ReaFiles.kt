package com.altaris.linux.core.quinn

import android.content.Context
import com.altaris.linux.core.rea.GuestExec
import com.altaris.linux.core.rea.ReaDistro
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * File manager for the hidden REA distro.
 *
 * Scope is deliberate: this reaches the REA container's rootfs and nothing
 * else. Shared storage is not mounted here, so a bad path cannot delete a
 * photo or another distro's rootfs. Every mutating call takes the distro id from
 * [ReaDistro], never from the UI.
 *
 * **Performance.** The first version looped in the guest and ran `wc -c` plus
 * `date -r` for every entry — two subprocesses per file, so a directory with
 * 500 entries spawned ~1000 processes and took seconds. A single `find
 * -printf` now produces type, size, mtime and name in one pass, which is the
 * difference between an instant listing and a frozen screen.
 */
object ReaFiles {

    data class Entry(
        val name: String,
        val path: String,
        val dir: Boolean,
        val size: Long,
        val modified: Long,
    ) {
        /** Lowercase extension, or "" for none. */
        val ext: String get() = name.substringAfterLast('.', "").lowercase()
    }

    /** Absolute path of the REA distro root inside the guest. */
    const val ROOT = "/"

    /** Directory the file manager opens on. */
    const val DEFAULT_PATH = "/root"

    /**
     * List [path] inside the REA distro.
     *
     * One `find` produces `type\tsize\tmtime\tname` per entry. `-maxdepth 1`
     * keeps it to the immediate children, and `%f` gives the bare name so no
     * path parsing is needed.
     */
    suspend fun list(context: Context, path: String): Result<List<Entry>> =
        withContext(Dispatchers.IO) {
            val distroId = ReaDistro.distroId(context)
                ?: return@withContext Result.failure(IllegalStateException("REA is not installed"))
            val safe = normalize(path)
            if (safe == ROOT) return@withContext Result.success(topLevel())
            val script = listingScript(safe)
            val result = GuestExec.run(context, distroId, script, timeoutMs = 30_000L)
            if (!result.ok) {
                return@withContext Result.failure(
                    IllegalStateException(result.tail(3).ifBlank { "cannot list $safe" })
                )
            }
            val entries = ArrayList<Entry>()
            for (line in result.output.lines()) {
                if (line.isBlank()) continue
                val parts = line.split('\t')
                if (parts.size < 4) continue
                val name = parts[3]
                if (name.isBlank() || name == "." || name == "..") continue
                entries.add(
                    Entry(
                        name = name,
                        path = joinPath(safe, name),
                        dir = parts[0] == "d",
                        size = parts[1].toLongOrNull() ?: 0L,
                        // %T@ is epoch seconds with a fractional part.
                        modified = parts[2].substringBefore('.').toLongOrNull() ?: 0L,
                    )
                )
            }
            Result.success(sort(entries))
        }

    /**
     * The root listing.
     *
     * A distro root is mostly system directories the user never edits, so the
     * manager shows the meaningful ones instead of 20 kernel pseudo-files.
     */
    private fun topLevel(): List<Entry> = listOf(
        Entry("root", "/root", true, 0L, 0L),
        Entry("home", "/home", true, 0L, 0L),
        Entry("tmp", "/tmp", true, 0L, 0L),
        Entry("sdcard", "/sdcard", true, 0L, 0L),
        Entry("opt", "/opt", true, 0L, 0L),
        Entry("usr", "/usr", true, 0L, 0L),
        Entry("etc", "/etc", true, 0L, 0L),
        Entry("var", "/var", true, 0L, 0L),
    )

    /**
     * The listing script for [path].
     *
     * One `find -printf` pass produces type, size, mtime and name per entry.
     * The first implementation looped and ran `wc -c` and `date -r` per entry,
     * which is two subprocesses per file — the reason a large directory froze
     * the page.
     */
    internal fun listingScript(path: String): String =
        "find " + GuestExec.shellQuote(path) +
            " -mindepth 1 -maxdepth 1 -printf '%y\\t%s\\t%T@\\t%f\\n' 2>/dev/null"

    /** Test seam: the exact listing command. */
    internal fun listingScriptForTest(path: String): String = listingScript(path)

    private fun sort(entries: List<Entry>): List<Entry> =
        entries.sortedWith(compareByDescending<Entry> { it.dir }.thenBy { it.name.lowercase() })

    /** Read a text file (bounded), for the editor. */
    suspend fun read(context: Context, path: String, maxBytes: Int = 512 * 1024): Result<String> =
        withContext(Dispatchers.IO) {
            val distroId = ReaDistro.distroId(context)
                ?: return@withContext Result.failure(IllegalStateException("REA is not installed"))
            val safe = normalize(path)
            val script = "head -c " + maxBytes + " " + GuestExec.shellQuote(safe)
            val result = GuestExec.run(context, distroId, script, timeoutMs = 30_000L)
            if (result.ok) Result.success(result.output) else Result.failure(
                IllegalStateException(result.tail(3).ifBlank { "cannot read $safe" })
            )
        }

    /** Write [content] to [path], creating parents. */
    suspend fun write(context: Context, path: String, content: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val distroId = ReaDistro.distroId(context)
                ?: return@withContext Result.failure(IllegalStateException("REA is not installed"))
            val safe = normalize(path)
            val dir = safe.substringBeforeLast('/', "/")
            val script = buildString {
                append("mkdir -p ").append(GuestExec.shellQuote(dir)).append(" && ")
                append("cat > ").append(GuestExec.shellQuote(safe))
                append(" <<'ALTARIS_FILE_EOF'\n")
                append(content).append('\n')
                append("ALTARIS_FILE_EOF\n")
            }
            val result = GuestExec.run(context, distroId, script, timeoutMs = 60_000L)
            if (result.ok) Result.success(Unit) else Result.failure(
                IllegalStateException(result.tail(3).ifBlank { "cannot write $safe" })
            )
        }

    /** Create a directory (and parents). */
    suspend fun mkdir(context: Context, path: String): Result<Unit> =
        run(context, "mkdir -p " + GuestExec.shellQuote(normalize(path)), "cannot create the folder")

    /** Create an empty file (and its parents). */
    suspend fun createFile(context: Context, path: String): Result<Unit> =
        run(
            context,
            "mkdir -p " + GuestExec.shellQuote(normalize(path).substringBeforeLast('/', "/")) +
                " && : > " + GuestExec.shellQuote(normalize(path)),
            "cannot create the file",
        )

    /** Rename or move [from] to [to]. */
    suspend fun rename(context: Context, from: String, to: String): Result<Unit> =
        run(
            context,
            "mkdir -p " + GuestExec.shellQuote(normalize(to).substringBeforeLast('/', "/")) +
                " && mv -f " + GuestExec.shellQuote(normalize(from)) + " " +
                GuestExec.shellQuote(normalize(to)),
            "cannot rename",
        )

    /** Copy [from] to [to], recursively for directories. */
    suspend fun copy(context: Context, from: String, to: String): Result<Unit> =
        run(
            context,
            "mkdir -p " + GuestExec.shellQuote(normalize(to).substringBeforeLast('/', "/")) +
                " && cp -a " + GuestExec.shellQuote(normalize(from)) + " " +
                GuestExec.shellQuote(normalize(to)),
            "cannot copy",
        )

    /**
     * Delete a file or directory tree.
     *
     * The caller confirms first. The root is refused: the button exists for
     * files, and a stray empty path must never wipe the environment.
     */
    suspend fun delete(context: Context, path: String): Result<Unit> {
        val safe = normalize(path)
        if (safe == ROOT || safe.isBlank()) {
            return Result.failure(
                IllegalStateException("refusing to delete the root of the REA distro")
            )
        }
        return run(context, "rm -rf " + GuestExec.shellQuote(safe), "cannot delete $safe")
    }

    /**
     * Copy a file from shared storage into the REA distro.
     *
     * The REA distro does not mount `/sdcard`, so the file is streamed in
     * through the host path the app can already read.
     */
    suspend fun importFromShared(context: Context, hostPath: String, into: String): Result<String> =
        withContext(Dispatchers.IO) {
            val distroId = ReaDistro.distroId(context)
                ?: return@withContext Result.failure(IllegalStateException("REA is not installed"))
            val source = File(hostPath)
            if (!source.isFile) {
                return@withContext Result.failure(IllegalStateException("not a file: $hostPath"))
            }
            val dir = normalize(into)
            val target = joinPath(dir, source.name)
            val script = "mkdir -p " + GuestExec.shellQuote(dir) + " && cp -f " +
                GuestExec.shellQuote(hostPath) + " " + GuestExec.shellQuote(target)
            val result = GuestExec.run(context, distroId, script, timeoutMs = 120_000L)
            if (result.ok) Result.success(target) else Result.failure(
                IllegalStateException(result.tail(3).ifBlank { "cannot import $hostPath" })
            )
        }

    /** True when [path] looks like something the editor should open. */
    fun isTextPath(path: String): Boolean {
        val name = path.substringAfterLast('/').lowercase()
        if (name.startsWith(".env")) return true
        val ext = name.substringAfterLast('.', "")
        return ext in TEXT_EXTENSIONS
    }

    /** True when [path] is an Android package or a native binary. */
    fun isBinaryPath(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in BINARY_EXTENSIONS
    }

    private suspend fun run(context: Context, script: String, failure: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val distroId = ReaDistro.distroId(context)
                ?: return@withContext Result.failure(IllegalStateException("REA is not installed"))
            val result = GuestExec.run(context, distroId, script, timeoutMs = 120_000L)
            if (result.ok) Result.success(Unit) else Result.failure(
                IllegalStateException(result.tail(3).ifBlank { failure })
            )
        }

    /**
     * Normalise [path] to an absolute guest path.
     *
     * `..` is resolved rather than rejected so "up one level" works, but it can
     * never escape the root because the result is always rebuilt from "/".
     */
    internal fun normalize(path: String): String {
        var clean = path.trim().ifBlank { ROOT }
        if (!clean.startsWith("/")) clean = "/$clean"
        val parts = ArrayList<String>()
        for (part in clean.split('/')) {
            when (part) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts.add(part)
            }
        }
        return "/" + parts.joinToString("/")
    }

    /** Parent of [path]; "/" for a top-level entry. */
    fun parentOf(path: String): String {
        val safe = normalize(path)
        if (safe == ROOT) return ROOT
        return safe.substringBeforeLast('/', "/").ifBlank { ROOT }
    }

    private fun joinPath(dir: String, name: String): String =
        if (dir == ROOT) "/$name" else "$dir/$name"

    /** Serialise a listing for the MCP/HTTP surface. */
    fun toJson(entries: List<Entry>): JSONArray {
        val array = JSONArray()
        for (entry in entries) {
            array.put(
                JSONObject()
                    .put("name", entry.name)
                    .put("path", entry.path)
                    .put("dir", entry.dir)
                    .put("size", entry.size)
                    .put("modified", entry.modified)
            )
        }
        return array
    }

    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "json", "xml", "yml", "yaml", "toml", "ini", "conf", "cfg",
        "sh", "bash", "zsh", "py", "js", "ts", "kt", "java", "c", "h", "cpp",
        "rs", "go", "log", "csv", "html", "css", "properties", "gradle", "smali",
        "smali2", "pro", "map", "gitignore", "env", "list", "mk", "cmake",
    )

    private val BINARY_EXTENSIONS = setOf(
        "apk", "apks", "xapk", "aab", "dex", "so", "elf", "bin", "img", "jar",
        "zip", "tar", "gz", "xz", "bz2", "7z", "iso", "wasm", "o", "a", "class",
    )
}
