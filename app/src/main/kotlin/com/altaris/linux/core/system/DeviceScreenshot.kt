package com.altaris.linux.core.system

import android.content.Context
import android.os.Build
import android.util.Log
import com.altaris.linux.core.a11y.A11yBridge
import org.json.JSONObject
import java.io.File

/**
 * Screenshot of the **physical** display.
 *
 * `altaris_screenshot` only ever captured a clone's virtual display, because
 * it went through the clone engine. The phone's own screen had no path at all,
 * which is why the agent saw "No display frame" when it tried to look at the
 * device it was actually running on.
 *
 * Two backends, in order:
 *
 *  1. **Accessibility** — `AccessibilityService.takeScreenshot`, API 30+. No
 *     root, no shell, already bound, and the only path that respects
 *     FLAG_SECURE: a protected window comes back blank instead of leaking.
 *  2. **Shizuku `screencap`** — for API 26–29, or when the service is not
 *     bound. Runs as the Shizuku uid, writes a PNG, then the file is moved into
 *     the app's cache so the caller gets a path it can always read.
 *
 * The result is always a file path, never a base64 blob: the image handoff in
 * OpenCode reads files, and pushing a 4 MB string through MCP is waste.
 */
object DeviceScreenshot {

    private const val TAG = "AltarisDeviceShot"

    /** Where captures live. Cache: they are disposable. */
    private fun dir(context: Context): File =
        File(context.applicationContext.cacheDir, "device-shots").apply { mkdirs() }

    /**
     * Capture [displayId] to a PNG.
     *
     * Blocking; call from IO. Never throws — failures are JSON.
     */
    fun capture(context: Context, displayId: Int = 0): JSONObject {
        val target = File(dir(context), "device-" + System.currentTimeMillis() + ".png")

        // 1. Accessibility. Only worth trying where the API exists.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val raw = runCatching { A11yBridge.screenshot(context, displayId, target.absolutePath) }
                .getOrNull()
            val obj = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (obj?.optBoolean("ok") == true) {
                return obj.put("backend", "accessibility")
            }
            val why = obj?.optString("error").orEmpty().ifBlank { "accessibility capture unavailable" }
            Log.i(TAG, "accessibility capture failed: $why — trying Shizuku")
            // A secure window is a deliberate platform protection: do not
            // fall through to a shell capture and pretend it worked.
            if (why.contains("FLAG_SECURE", ignoreCase = true) ||
                why.contains("secure window", ignoreCase = true)
            ) {
                return JSONObject()
                    .put("ok", false)
                    .put("error", why)
                    .put("hint", "The foreground window is protected by FLAG_SECURE. " +
                        "Android does not allow any screenshot of it, and this will not " +
                        "attempt to bypass that.")
            }
        }

        // 2. Shizuku.
        val viaShell = captureWithShizuku(context, displayId, target)
        if (viaShell != null) return viaShell

        return JSONObject()
            .put("ok", false)
            .put("error", "No physical screenshot backend is available.")
            .put(
                "hint",
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    "Enable the Altaris accessibility service (altaris_ui_enable), or " +
                        "start Shizuku and grant Altaris permission."
                } else {
                    "This Android version has no accessibility screenshot API; start " +
                        "Shizuku and grant Altaris permission."
                },
            )
    }

    /**
     * `screencap -p` as the Shizuku uid.
     *
     * The shell writes to a path it owns, then the bytes are copied into the
     * app cache — a file written by the shell is not readable by the app on
     * every Android version, and returning a path the caller cannot open is
     * worse than returning an error.
     */
    private fun captureWithShizuku(context: Context, displayId: Int, target: File): JSONObject? {
        if (!ShizukuShell.available()) return null
        val tmp = "/data/local/tmp/altaris-shot-${System.currentTimeMillis()}.png"
        val displayArg = if (displayId > 0) "-d $displayId" else ""
        val run = ShizukuShell.run(
            "screencap -p $displayArg $tmp && chmod 644 $tmp",
            timeoutMs = 15_000L,
        )
        if (!run.ok) {
            Log.w(TAG, "shizuku screencap failed: ${run.error}")
            return JSONObject()
                .put("ok", false)
                .put("error", "Shizuku screencap failed: " + run.error.ifBlank { "exit ${run.exitCode}" })
                .put("backend", "shizuku")
        }
        // Copy it out as the shell. Reading the shell's file directly is not
        // permitted on every Android version, and returning a path the caller
        // cannot open is worse than returning an error.
        val copy = ShizukuShell.run(
            "cp $tmp " + target.absolutePath + " && chmod 644 " + target.absolutePath,
            timeoutMs = 20_000L,
        )
        ShizukuShell.run("rm -f $tmp", timeoutMs = 5_000L)
        if (!copy.ok || !target.isFile || target.length() == 0L) {
            return JSONObject()
                .put("ok", false)
                .put("error", "screencap produced no readable file: " +
                    copy.error.ifBlank { "exit ${copy.exitCode}" })
                .put("backend", "shizuku")
        }
        val size = pngSize(target)
        return JSONObject()
            .put("ok", true)
            .put("file", target.absolutePath)
            .put("bytes", target.length())
            .put("display", displayId)
            .put("backend", "shizuku")
            .put("at", System.currentTimeMillis())
            .apply {
                if (size != null) {
                    put("width", size.first)
                    put("height", size.second)
                }
            }
    }

    /** PNG dimensions, read from the IHDR chunk. Returns null when not a PNG. */
    fun pngSize(file: File): Pair<Int, Int>? = runCatching {
        file.inputStream().use { input ->
            val header = ByteArray(24)
            if (input.read(header) < 24) return@use null
            // 89 50 4E 47 0D 0A 1A 0A then IHDR: length, "IHDR", width, height
            if (header[0] != 0x89.toByte() || header[1] != 0x50.toByte()) return@use null
            fun int(at: Int): Int =
                ((header[at].toInt() and 0xFF) shl 24) or
                    ((header[at + 1].toInt() and 0xFF) shl 16) or
                    ((header[at + 2].toInt() and 0xFF) shl 8) or
                    (header[at + 3].toInt() and 0xFF)
            int(16) to int(20)
        }
    }.getOrNull()
}
