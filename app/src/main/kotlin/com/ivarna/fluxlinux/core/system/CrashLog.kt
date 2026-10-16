package com.ivarna.fluxlinux.core.system

import android.content.Context
import android.os.Build
import android.util.Log
import com.ivarna.fluxlinux.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foila's local crash recorder.
 *
 * Installed as the process-wide uncaught-exception handler from [FluxApp.onCreate],
 * before MIUI's reporter takes the process. Every uncaught exception is written
 * verbatim to `filesDir/crashlog/crash-<timestamp>.txt`, then handed to the previous
 * default handler so the system (MIUI) crash dialog and normal process death still
 * happen exactly as before.
 *
 * The directory is capped at [MAX_FILES] newest reports so a crash loop can never
 * grow unbounded. Settings -> Crash logs lists/reads/copies them for sharing.
 */
object CrashLog {

    private const val TAG = "Foila"
    private const val DIR_NAME = "crashlog"
    private const val PREFIX = "crash-"
    private const val SUFFIX = ".txt"
    private const val MAX_FILES = 20

    /** Directory holding the reports (created lazily on first write). */
    fun dir(context: Context): File = File(context.applicationContext.filesDir, DIR_NAME)

    /**
     * Chain our recorder in front of the existing default handler. Called once per
     * process from [FluxApp.onCreate]; safe to call again (config changes cannot
     * happen for Application, but re-install just re-chains harmlessly).
     */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // The crash path must never throw on its own.
            try {
                write(app, thread, error)
            } catch (t: Throwable) {
                Log.e(TAG, "failed to persist crash log", t)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Write one report. Blocking file IO — only ever runs on the crashing thread. */
    @Synchronized
    fun write(context: Context, thread: Thread, error: Throwable) {
        val d = dir(context)
        if (!d.exists() && !d.mkdirs()) {
            Log.e(TAG, "cannot create ${d.absolutePath}")
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val file = File(d, "$PREFIX$stamp$SUFFIX")
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        file.writeText(
            buildString {
                appendLine("Foila Linux crash report")
                appendLine("time: ${Date()}")
                appendLine("version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine(
                    "device: ${Build.MANUFACTURER} ${Build.MODEL} / " +
                        "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
                )
                appendLine("thread: ${thread.name} (id=${thread.id})")
                appendLine("----")
                append(trace)
            }
        )
        Log.e(TAG, "crash log written: ${file.absolutePath}", error)
        trim(d)
    }

    /** Stored reports, newest first (timestamped names sort lexicographically). */
    fun files(context: Context): List<File> =
        dir(context).listFiles { f ->
            f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX)
        }?.sortedByDescending { it.name } ?: emptyList()

    /** Raw trace text; empty string when unreadable. */
    fun read(file: File): String = runCatching { file.readText() }.getOrDefault("")

    fun deleteAll(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    /** Keep only the [MAX_FILES] newest reports. */
    private fun trim(d: File) {
        val all = d.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) }
            ?.sortedBy { it.name } ?: return
        var excess = all.size - MAX_FILES
        for (f in all) {
            if (excess <= 0) break
            if (f.delete()) excess--
        }
    }
}
