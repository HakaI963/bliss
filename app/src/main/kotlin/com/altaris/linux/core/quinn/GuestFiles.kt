package com.altaris.linux.core.quinn

import android.content.Context
import com.altaris.linux.core.rea.GuestExec
import com.altaris.linux.core.rea.ReaDistro
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Moves a file the user picked on the phone into a guest filesystem.
 *
 * Android gives us a `content://` URI, not a path, and a distro cannot read
 * `/sdcard` (it is not mounted in the REA environment and is a different
 * mount namespace inside proot). So an attachment is copied twice:
 *
 *  1. the picker's stream is written to the app's cache (a real file path the
 *     app can hand to the host shell), then
 *  2. that file is copied into the guest's inbox.
 *
 * The guest path is what gets passed to the agent or to REA, which is why this
 * returns it rather than the URI.
 */
object GuestFiles {

    /** Where attachments land inside a guest. */
    const val INBOX = "/root/altaris-inbox"

    /**
     * Copy [hostPath] into [distroId]'s inbox.
     *
     * @return the guest path, or null when the copy failed.
     */
    suspend fun importInto(
        context: Context,
        distroId: String,
        hostPath: String,
        inbox: String = INBOX,
    ): String? = withContext(Dispatchers.IO) {
        val source = File(hostPath)
        if (!source.isFile) return@withContext null
        val target = "$inbox/${source.name}"
        val script = "mkdir -p " + GuestExec.shellQuote(inbox) + " && cp -f " +
            GuestExec.shellQuote(hostPath) + " " + GuestExec.shellQuote(target)
        val result = GuestExec.run(context, distroId, script, timeoutMs = 120_000L)
        if (result.ok) target else null
    }

    /**
     * Copy a picked file into the REA environment.
     *
     * REA is its own hidden distro, so the target distro id comes from
     * [ReaDistro] rather than from a session.
     */
    suspend fun importIntoRea(context: Context, hostPath: String): String? {
        val distroId = ReaDistro.distroId(context) ?: return null
        return importInto(context, distroId, hostPath)
    }

    /**
     * Write a picker stream to the app cache and return the real path.
     *
     * The file keeps its display name so the agent (and the user) can tell
     * attachments apart; a numeric suffix is added on collision.
     */
    fun materialize(context: Context, name: String, open: () -> java.io.InputStream?): File? {
        val dir = File(context.cacheDir, "quinn-attachments").apply { mkdirs() }
        val safeName = name.ifBlank { "attachment" }
            .replace('/', '_')
            .replace('\u0000', '_')
            .take(120)
        var target = File(dir, safeName)
        var counter = 1
        while (target.exists()) {
            val stem = safeName.substringBeforeLast('.', safeName)
            val ext = safeName.substringAfterLast('.', "")
            target = File(dir, if (ext.isBlank()) "$stem-$counter" else "$stem-$counter.$ext")
            counter += 1
            if (counter > 500) break
        }
        return runCatching {
            val input = open() ?: return null
            input.use { source ->
                target.outputStream().use { sink -> source.copyTo(sink) }
            }
            target
        }.getOrNull()
    }

    /** Remove everything written by [materialize]; called when a thread is closed. */
    fun clearCache(context: Context) {
        runCatching { File(context.cacheDir, "quinn-attachments").deleteRecursively() }
    }
}
