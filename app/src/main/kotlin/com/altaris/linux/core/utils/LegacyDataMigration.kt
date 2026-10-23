package com.altaris.linux.core.utils

import android.content.Context
import android.util.Log
import com.altaris.linux.core.root.RootShell
import java.io.File

/**
 * One-shot move of the pre-rename package data.
 *
 * The app id changed from `com.nopiece.bliss` to `com.altaris.linux`; Android
 * gives the new package an empty data dir, so the containers, the host
 * bootstrap and the preferences would all look gone after the upgrade.
 * Renaming the old directory into place (same filesystem, instant) and then
 * handing it to the new uid keeps everything — root is required because the
 * two packages run as different uids.
 *
 * Runs off the main thread, is idempotent, and quietly gives up when root is
 * unavailable or the new install already has data of its own.
 */
object LegacyDataMigration {

    private const val TAG = "LegacyDataMigration"
    private const val OLD_PACKAGE = "com.nopiece.bliss"
    private const val MARKER = ".legacy-data-checked"

    fun start(context: Context) {
        val app = context.applicationContext
        if (app.packageName != "com.altaris.linux") return
        val thread = Thread {
            runCatching { migrate(app) }
                .onFailure { Log.w(TAG, "legacy data migration failed", it) }
        }
        thread.isDaemon = true
        thread.name = "altaris-legacy-data"
        thread.start()
    }

    private fun migrate(app: Context) {
        val files = app.filesDir
        val marker = File(files, MARKER)
        if (marker.exists()) return
        val oldFiles = File("/data/data/$OLD_PACKAGE/files")
        if (!oldFiles.isDirectory) {
            mark(marker)
            return
        }
        // A prefix of our own means this install is already in use; never
        // overwrite it with older data.
        if (File(files, "usr").exists() || File(files, "home").exists()) {
            mark(marker)
            return
        }
        if (!RootShell.isRootAvailable()) {
            mark(marker)
            return
        }
        val uid = android.os.Process.myUid()
        val cmd = """
            OLD=/data/data/$OLD_PACKAGE
            NEW=/data/data/${app.packageName}
            if [ -d "${'$'}OLD/files" ] && [ ! -e "${'$'}NEW/files/usr" ]; then
                rm -rf "${'$'}NEW/files"
                mv "${'$'}OLD/files" "${'$'}NEW/files" || exit 3
                chown -R $uid:$uid "${'$'}NEW/files" || exit 4
                # SELinux labels carry the old app's categories; without this
                # the new uid can see the files but not open them.
                restorecon -R "${'$'}NEW/files" 2>/dev/null || true
            fi
            mkdir -p "${'$'}NEW/shared_prefs"
            for f in "${'$'}OLD"/shared_prefs/*.xml; do
                [ -f "${'$'}f" ] || continue
                b=${'$'}(basename "${'$'}f")
                [ -f "${'$'}NEW/shared_prefs/${'$'}b" ] || cp "${'$'}f" "${'$'}NEW/shared_prefs/${'$'}b"
            done
            if [ -d "${'$'}OLD/databases" ] && [ ! -e "${'$'}NEW/databases" ]; then
                cp -a "${'$'}OLD/databases" "${'$'}NEW/databases" 2>/dev/null
            fi
            chown -R $uid:$uid "${'$'}NEW/shared_prefs" 2>/dev/null
            restorecon -R "${'$'}NEW/shared_prefs" 2>/dev/null || true
            exit 0
        """.trimIndent()
        val code = RootShell.executeSync(cmd, timeoutMs = 10 * 60_000L)
        Log.i(TAG, "migration exit=$code")
        if (code == 0) mark(marker)
    }

    private fun mark(marker: File) {
        runCatching {
            marker.parentFile?.mkdirs()
            marker.createNewFile()
        }
    }
}
