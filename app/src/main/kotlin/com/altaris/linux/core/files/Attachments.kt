package com.altaris.linux.core.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Files handed to an agent from the terminal.
 *
 * The terminal is where an agent actually runs, so "send this to the agent"
 * means two things: put the bytes somewhere the agent can open, and say where.
 *
 * The destination is `/sdcard/Download/Altaris/attach/`, which is shared
 * storage. That is deliberate — it is the one path visible from the host, from
 * a proot guest and from a chroot guest, so an attachment works regardless of
 * which shell the agent is running in. A path inside the app's private data
 * would be invisible to two of the three.
 */
object Attachments {

    private const val TAG = "AltarisAttach"

    /** Where attachments land, as the guest sees it. */
    const val GUEST_DIR = "/sdcard/Download/Altaris/attach"

    /** The host path behind [GUEST_DIR]. */
    private const val HOST_DIR = "/sdcard/Download/Altaris/attach"

    data class Saved(
        /** The path the agent should open. */
        val guestPath: String,
        val name: String,
        val bytes: Long,
        val mime: String,
    )

    /**
     * Copy [uri] into shared storage and return where it landed.
     *
     * The display name comes from the provider, not from the URI, because a
     * document URI's last segment is an opaque id like `msf:1000000031` and
     * would reach the agent as a meaningless filename.
     */
    suspend fun save(
        context: Context,
        uri: Uri,
        fallbackName: String = "attachment",
    ): Result<Saved> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.applicationContext.contentResolver
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            var name = displayName(context, uri) ?: fallbackName
            name = sanitise(name)
            if (name.isBlank()) name = fallbackName

            // Shared storage first: it is the one path the host shell, a
            // proot guest and a chroot guest can all read. If the device
            // refuses it (all-files access not granted), fall back to the
            // app's own cache — the host shell can still read that, and a
            // failed copy is worse than a path only one shell sees.
            val dir = writableDir(context)
                ?: throw IllegalStateException(
                    "No writable folder for the attachment. Grant Altaris " +
                        "all-files access in Settings, then try again."
                )
            // Never overwrite: two files with the same name are common when
            // picking from a chat app, and silently replacing the first one
            // would make the agent read the wrong bytes.
            var target = File(dir, name)
            var counter = 1
            while (target.exists()) {
                target = File(dir, uniqueName(name, counter))
                counter++
            }
            val stream = resolver.openInputStream(uri)
                ?: throw IllegalStateException("Cannot open that file")
            stream.use { input ->
                target.outputStream().use { out -> input.copyTo(out, 128 * 1024) }
            }
            if (target.length() == 0L) {
                target.delete()
                throw IllegalStateException("That file is empty")
            }
            // The path handed to the shell must be the one the file is
            // actually at: shared storage normally, the app cache when
            // all-files access was refused.
            val guestPath = if (dir.absolutePath == HOST_DIR) {
                GUEST_DIR + "/" + target.name
            } else {
                target.absolutePath
            }
            Saved(
                guestPath = guestPath,
                name = target.name,
                bytes = target.length(),
                mime = mime,
            )
        }.onFailure { Log.w(TAG, "cannot save attachment", it) }
    }

    /**
     * The line to type into the shell, as if the file had been pasted.
     *
     * A bare path, quoted only if it needs it. Nothing else: no "I attached a
     * file", no size, no instruction. This goes into the agent's prompt box the
     * same way a typed path would, so the user writes the question themselves
     * and the agent sees a normal path — which is what it already knows how to
     * read. The previous version composed a whole sentence and sent it, which
     * took the message out of the user's hands.
     */
    fun reference(saved: Saved): String =
        if (saved.guestPath.any { it.isWhitespace() || it in "\"'()[]{}&;|<>*?\$`" }) {
            "'" + saved.guestPath.replace("'", "'\\''") + "'"
        } else {
            saved.guestPath
        }

    /**
     * The first folder the attachment can actually be written to.
     *
     * Returns null when none works, so the caller can say why instead of
     * throwing a bare "cannot create" after the user already picked a file.
     */
    private fun writableDir(context: Context): File? {
        val candidates = listOf(
            File(HOST_DIR),
            File(context.applicationContext.cacheDir, "attach"),
        )
        for (dir in candidates) {
            if (dir.isDirectory) return dir
            if (dir.mkdirs()) return dir
            Log.w(TAG, "cannot create " + dir.absolutePath)
        }
        return null
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.applicationContext.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()

    /** Strip path separators and control characters; keep the extension. */
    private fun sanitise(name: String): String =
        name.replace('/', '_')
            .replace('\\', '_')
            .replace("\u0000", "")
            .filter { it.code >= 32 }
            .trim()
            .take(120)

    private fun uniqueName(name: String, counter: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) {
            name.substring(0, dot) + "-" + counter + name.substring(dot)
        } else {
            name + "-" + counter
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> bytes.toString() + " B"
        bytes < 1024 * 1024 -> (bytes / 1024).toString() + " KB"
        else -> (bytes / (1024 * 1024)).toString() + " MB"
    }
}
