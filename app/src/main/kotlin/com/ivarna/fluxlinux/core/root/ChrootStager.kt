package com.ivarna.fluxlinux.core.root

import android.content.Context
import android.util.Base64
import android.util.Log

/**
 * Chunked file writer for [RootShell.captureInChroot].
 *
 * Every root command is delivered through `su -c <one string>` — a **single**
 * argv element, and Linux rejects any argument above `MAX_ARG_STRLEN`
 * (128 KiB; verified: a 200 KB argument fails with `E2BIG`). Art sets and
 * guest scripts are far bigger, so they are written in bounded chunks:
 * `printf '%s' '<base64 slice>' | base64 -d >> <path>`.
 *
 * Chunks are cut on 4-char base64 boundaries so each slice decodes on its own.
 */
object ChrootStager {

    private const val TAG = "ChrootStager"

    /**
     * Raw content characters per invocation. The slice is base64 text already,
     * and [RootShell.captureInChroot] base64-wraps the whole command again, so
     * the final argv element grows ~1.8x: 60 KB -> ~110 KB (under the cap).
     */
    private const val CONTENT_CHUNK = 60_000

    private const val STEP_TIMEOUT_MS = 30_000L

    /**
     * Write `files` (destPath -> content) into the chroot. Targets are
     * truncated first, then appended chunk by chunk. Returns false when an
     * invocation reports a non-zero exit (best effort; missing helper, no root
     * or SELinux problems surface as false).
     */
    fun writeFiles(
        ctx: Context,
        chrootPath: String,
        files: List<Pair<String, String>>,
        user: String = "root"
    ): Boolean {
        if (files.isEmpty()) return true
        try {
            if (!truncateTargets(ctx, chrootPath, files, user)) return false
            for ((path, content) in files) {
                if (!appendContent(ctx, chrootPath, path, content, user)) return false
            }
            return true
        } catch (e: Exception) {
            Log.w(TAG, "stage failed at $chrootPath: ${e.message}")
            return false
        }
    }

    /** One small invocation: mkdir -p each parent dir + truncate each target. */
    private fun truncateTargets(
        ctx: Context,
        chrootPath: String,
        files: List<Pair<String, String>>,
        user: String
    ): Boolean {
        val sb = StringBuilder("set +e\n")
        for ((path, _) in files) {
            val q = shellQuote(path)
            sb.append("mkdir -p \"\$(dirname ").append(q).append(")\" 2>/dev/null || true\n")
            sb.append(": > ").append(q).append(" 2>/dev/null || true\n")
        }
        sb.append("exit 0\n")
        val res = RootShell.captureInChroot(
            sb.toString(), user = user, chrootPath = chrootPath,
            timeoutMs = STEP_TIMEOUT_MS, context = ctx
        )
        return res.exitCode == 0
    }

    /** Appends one file in [CONTENT_CHUNK]-sized base64 slices. */
    private fun appendContent(
        ctx: Context,
        chrootPath: String,
        path: String,
        content: String,
        user: String
    ): Boolean {
        val b64 = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val q = shellQuote(path)
        var index = 0
        while (index < b64.length) {
            var end = minOf(index + CONTENT_CHUNK, b64.length)
            end -= (end - index) % 4 // keep every slice independently decodable
            if (end <= index) end = minOf(index + 4, b64.length)
            val slice = b64.substring(index, end)
            val cmd = "printf '%s' '${slice}' | base64 -d >> $q 2>/dev/null; exit 0\n"
            val res = RootShell.captureInChroot(
                cmd, user = user, chrootPath = chrootPath,
                timeoutMs = STEP_TIMEOUT_MS, context = ctx
            )
            if (res.exitCode != 0) return false
            index = end
        }
        return true
    }

    /** Minimal single-quote shell quoting for paths (no newlines expected). */
    private fun shellQuote(path: String): String =
        "'" + path.replace("'", "'\\''") + "'"
}
