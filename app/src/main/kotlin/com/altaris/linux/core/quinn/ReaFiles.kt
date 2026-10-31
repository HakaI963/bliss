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
 * The listing is produced by a shell `find` inside the guest rather than by
 * walking the rootfs from the app: a chroot lives under `/data/local`, which the
 * app process cannot read.
 */
object ReaFiles {

    data class Entry(
        val name: String,
        val path: String,
        val dir: Boolean,
        val size: Long,
        val modified: Long,
    )

    /** Absolute path of the REA distro root inside the guest. */
    const val ROOT = "/"

    /**
     * List [path] inside the REA distro. Null distro means REA is not
     * installed, which the caller renders as the empty state.
     */
    suspend fun list(context: Context, path: String): Result<List<Entry>> =
        withContext(Dispatchers.IO) {
            val distroId = ReaDistro.distroId(context)
                ?: return@withContext Result.failure(IllegalStateException("REA is not installed"))
            val safe = normalize(path)
            // -printf keeps one field per line and avoids parsing `ls` output.
            // Every `$` is escaped for Kotlin, so the guest shell expands the
            // variables at run time instead of Kotlin interpolating them here.
            val script = buildString {
                append("set +e\n")
                append("cd ").append(GuestExec.shellQuote(safe)).append(" 2>/dev/null || exit 3\n")
                append("for f in * .[!.]*; do\n")
                append("  [ -e \"\$f\" ] || continue\n")
                append("  if [ -d \"\$f\" ]; then t=d; else t=f; fi\n")
                append("  s=\$(wc -c < \"\$f\" 2>/dev/null | tr -d ' ')\n")
                append("  m=\$(date -r \"\$f\" +%s 2>/dev/null || echo 0)\n")
                append("  printf '%s\\t%s\\t%s\\t%s\\n' \"\$t\" \"\$s\" \"\$m\" \"\$f\"\n")
                append("done\n")
            }
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
                if (name == "." || name == "..") continue
                entries.add(
                    Entry(
                        name = name,
                        path = joinPath(safe, name),
                        dir = parts[0] == "d",
                        size = parts[1].toLongOrNull() ?: 0L,
                        modified = parts[2].toLongOrNull() ?: 0L,
                    )
                )
            }
            Result.success(
                entries.sortedWith(
                    compareByDescending<Entry> { it.dir }.thenBy { it.name.lowercase() }
                )
            )
        }

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
        withContext(Dispatchers.IO) {
            val distroId = ReaDistro.distroId(context)
                ?: return@withContext Result.failure(IllegalStateException("REA is not installed"))
            val result = GuestExec.run(
                context,
                distroId,
                "mkdir -p " + GuestExec.shellQuote(normalize(path)),
                timeoutMs = 30_000L,
            )
            if (result.ok) Result.success(Unit) else Result.failure(
                IllegalStateException(result.tail(3).ifBlank { "cannot create the folder" })
            )
        }

    /** Delete a file or directory tree. The caller confirms first. */
    suspend fun delete(context: Context, path: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val distroId = ReaDistro.distroId(context)
                ?: return@withContext Result.failure(IllegalStateException("REA is not installed"))
            val safe = normalize(path)
            // Refuse to delete the root: the button exists for files, and a
            // stray empty path must never wipe the whole environment.
            if (safe == "/" || safe.isBlank()) {
                return@withContext Result.failure(
                    IllegalStateException("refusing to delete the root of the REA distro")
                )
            }
            val result = GuestExec.run(
                context,
                distroId,
                "rm -rf " + GuestExec.shellQuote(safe),
                timeoutMs = 120_000L,
            )
            if (result.ok) Result.success(Unit) else Result.failure(
                IllegalStateException(result.tail(3).ifBlank { "cannot delete $safe" })
            )
        }

    /** Copy a file from shared storage into the REA distro (for analysis targets). */
    suspend fun importFromShared(context: Context, hostPath: String, into: String): Result<String> =
        withContext(Dispatchers.IO) {
            val distroId = ReaDistro.distroId(context)
                ?: return@withContext Result.failure(IllegalStateException("REA is not installed"))
            val source = File(hostPath)
            if (!source.isFile) {
                return@withContext Result.failure(IllegalStateException("not a file: $hostPath"))
            }
            val target = joinPath(normalize(into), source.name)
            val script = "mkdir -p " + GuestExec.shellQuote(into) + " && cp -f " +
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

    private fun normalize(path: String): String {
        var clean = path.trim().ifBlank { "/" }
        if (!clean.startsWith("/")) clean = "/$clean"
        // Collapse `//`, and reject `..` outright: the manager never needs it
        // and it is the one way out of the REA rootfs.
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

    private fun joinPath(dir: String, name: String): String =
        if (dir == "/") "/$name" else "$dir/$name"

    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "json", "xml", "yml", "yaml", "toml", "ini", "conf", "cfg",
        "sh", "bash", "zsh", "py", "js", "ts", "kt", "java", "c", "h", "cpp",
        "rs", "go", "log", "csv", "html", "css", "properties", "gradle", "smali",
    )

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
}
