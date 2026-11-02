package com.altaris.linux.core.rea

import android.util.Log
import org.tukaani.xz.XZInputStream
import java.io.File

/**
 * Unpacks an `.xz` release archive.
 *
 * Every Frida artifact — `frida-server`, `libfrida-gadget.so` — is published
 * xz-compressed. The first version tried `xz` and then `python3` on the host:
 *
 *  - Android ships **no `xz` binary**, and
 *  - there is **no `python3` on the host PATH** (it lives inside a distro, not
 *    on the device).
 *
 * Both branches therefore failed on every device, and the failure surfaced as
 * "cannot decompress", which looked like a corrupt download. It was never a
 * download problem.
 *
 * The decoder is now in the app (`org.tukaani:xz`, pure Java), so this works
 * with no external tool, no root and no distro.
 */
object XzArchive {

    private const val TAG = "AltarisXz"

    /** Streams are small; anything this large is a real binary, not a stub. */
    private const val MIN_PLAUSIBLE_BYTES = 100_000L

    /**
     * Decompress [source] into [target].
     *
     * Writes to a temporary file and renames only on success, so a truncated or
     * corrupt archive never leaves a half-written binary that later looks
     * installed but cannot execute.
     */
    fun decompress(source: File, target: File, minBytes: Long = MIN_PLAUSIBLE_BYTES): Boolean {
        if (!source.isFile || source.length() == 0L) {
            Log.w(TAG, "archive is missing or empty: ${source.absolutePath}")
            return false
        }
        val temporary = File(target.parentFile, target.name + ".part")
        return try {
            target.parentFile?.mkdirs()
            XZInputStream(source.inputStream().buffered()).use { input ->
                temporary.outputStream().buffered().use { output ->
                    input.copyTo(output, 64 * 1024)
                }
            }
            val size = temporary.length()
            if (size < minBytes) {
                // A valid xz stream can still hold an error page or a stub;
                // size is the cheapest check that it is the binary we wanted.
                Log.w(TAG, "decompressed only $size bytes, expected at least $minBytes")
                temporary.delete()
                return false
            }
            if (target.exists() && !target.delete()) {
                Log.w(TAG, "cannot replace ${target.absolutePath}")
                temporary.delete()
                return false
            }
            if (!temporary.renameTo(target)) {
                Log.w(TAG, "cannot move the decompressed file into place")
                temporary.delete()
                return false
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "decompression failed", t)
            temporary.delete()
            false
        }
    }
}
