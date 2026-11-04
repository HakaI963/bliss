package com.altaris.linux.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates from the project's own GitHub releases.
 *
 * The app ships its whole toolchain — REA, Ghidra, the asset tools — but the
 * app itself had no way to update: you had to notice a new release yourself and
 * sideload it. This checks the release feed, compares the tag against the
 * installed version, downloads the APK and hands it to the package installer.
 *
 * It is deliberately explicit rather than silent: it checks, it says what it
 * found, and the install is one tap. Silently replacing an app that holds a
 * user's distros and clones is not something to do behind their back.
 */
object AppUpdater {

    private const val TAG = "AltarisUpdater"

    private const val OWNER = "HakaI963"
    private const val REPO = "bliss"
    private const val API = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    /** The APK asset name the release workflow publishes. */
    private const val APK_ASSET = "app-altaris-debug.apk"

    data class Release(
        val tag: String,
        val notes: String,
        val apkUrl: String,
        val size: Long,
        val publishedAt: String,
    )

    /** What the check found. */
    sealed interface Result {
        /** Nothing newer than [current]. */
        data class UpToDate(val current: String) : Result
        data class Available(val release: Release, val current: String) : Result
        data class Failed(val reason: String) : Result
    }

    /**
     * True when [remote] is newer than [current].
     *
     * Both are the release tag (`v1.3.0-<sha>`) and the installed version name.
     * A plain string comparison would call `v1.10.0` older than `v1.9.0`, so the
     * numeric parts are compared in order and only then the suffix.
     */
    fun isNewer(remote: String, current: String): Boolean {
        val a = numbers(remote)
        val b = numbers(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        // Same numbers: only a different suffix means a different build.
        //
        // The tag carries a leading "v" and the installed version does not, so
        // comparing the raw strings reported an update on every check — a tag
        // of "v1.3.0" never equals "1.3.0". Normalise before comparing.
        return normalise(remote) != normalise(current)
    }

    /** Drop the "v" and surrounding space, so a tag and a version compare. */
    private fun normalise(version: String): String =
        version.trim().removePrefix("v").removePrefix("V").trim()

    private fun numbers(version: String): List<Int> =
        Regex("\\d+").findAll(version).mapNotNull { it.value.toIntOrNull() }.toList()

    /** Ask GitHub for the newest release. Blocking; call on IO. */
    suspend fun check(context: Context, current: String): Result = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(API).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Altaris/" + current)
            }
            try {
                if (conn.responseCode != 200) {
                    return@withContext Result.Failed("GitHub answered " + conn.responseCode)
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val tag = json.optString("tag_name").ifBlank {
                    return@withContext Result.Failed("the release has no tag")
                }
                if (!isNewer(tag, current)) return@withContext Result.UpToDate(current)

                val assets = json.optJSONArray("assets")
                var url = ""
                var size = 0L
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        if (asset.optString("name") == APK_ASSET) {
                            url = asset.optString("browser_download_url")
                            size = asset.optLong("size", 0L)
                            break
                        }
                    }
                }
                if (url.isBlank()) {
                    return@withContext Result.Failed(
                        "release " + tag + " has no " + APK_ASSET + " asset"
                    )
                }
                Result.Available(
                    Release(
                        tag = tag,
                        notes = json.optString("body").take(4000),
                        apkUrl = url,
                        size = size,
                        publishedAt = json.optString("published_at"),
                    ),
                    current,
                )
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "update check failed", t)
            Result.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    /** Where a downloaded APK is kept. */
    fun apkFile(context: Context, tag: String): File =
        File(context.applicationContext.cacheDir, "update-" + tag + ".apk")

    /**
     * Download the release APK. Blocking; call on IO.
     *
     * Resumes rather than restarting: a dropped phone connection is normal and
     * a 180 MB APK is not worth fetching twice.
     */
    suspend fun download(
        context: Context,
        release: Release,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File? = withContext(Dispatchers.IO) {
        val target = apkFile(context, release.tag)
        if (target.isFile && target.length() > 1_000_000 &&
            (release.size <= 0L || target.length() == release.size)
        ) {
            return@withContext target
        }
        val part = File(target.absolutePath + ".part")
        var have = if (part.isFile) part.length() else 0L
        try {
            val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "Altaris/update")
                if (have > 0L) setRequestProperty("Range", "bytes=" + have + "-")
            }
            try {
                // A server that ignores Range starts from zero; start over too.
                if (have > 0L && conn.responseCode == 200) {
                    part.delete()
                    have = 0L
                }
                if (conn.responseCode !in listOf(200, 206)) {
                    Log.w(TAG, "download answered " + conn.responseCode)
                    return@withContext null
                }
                val total = release.size.takeIf { it > 0L }
                    ?: conn.contentLengthLong.takeIf { it > 0L }
                    ?: 0L
                conn.inputStream.use { input ->
                    java.io.FileOutputStream(part, have > 0L).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        var done = have
                        while (input.read(buf).also { read = it } > 0) {
                            out.write(buf, 0, read)
                            done += read
                            onProgress(done, total)
                        }
                    }
                }
                if (total > 0L && part.length() != total) {
                    Log.w(TAG, "download incomplete: " + part.length() + "/" + total)
                    return@withContext null
                }
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) return@withContext null
                target
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "download failed", t)
            null
        }
    }

    /**
     * Hand the downloaded APK to the system installer.
     *
     * Uses a FileProvider URI, because a `file://` URI is rejected on modern
     * Android. The caller must have declared the install-packages permission
     * and the user must allow this app to install packages.
     */
    fun install(context: Context, apk: File): Boolean = runCatching {
        val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(
                context,
                context.packageName + ".updates",
                apk,
            )
        } else {
            Uri.fromFile(apk)
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.onFailure { Log.w(TAG, "cannot launch the installer", it) }.getOrDefault(false)
}
