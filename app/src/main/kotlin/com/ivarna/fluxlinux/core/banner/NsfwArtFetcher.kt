package com.ivarna.fluxlinux.core.banner

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ivarna.fluxlinux.core.utils.TerminalPreferences
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlin.random.Random

/**
 * Optional NSFW banner art fetcher (off by default; gated by
 * `TerminalPreferences.isNsfwEnabled`).
 *
 * Downloads source images from a random-image web API, renders them with
 * [HalfBlockRenderer] at the current banner quality and keeps both stages in
 * the app cache so `BannerAssets.nsfw` only ever reads files:
 *
 * ```
 * cacheDir/banner-nsfw/
 *   src/NN-<sha1-8>.img      raw source bytes, at most 10
 *   l1/NN-<sha1-8>.txt       24-col ANSI art (quality 1)
 *   l2/... l3/... l4/... l5/  32 / 40 / 48 / 54 cols
 * ```
 *
 * The default source APIs per level are purrbot (levels 1/3/4) and nekos.life
 * (level 2); a custom URL template from preferences overrides them and may use
 * `{level}` (`minimal|moderate|fanservice|full`) and `{rand}` placeholders.
 * All network work happens off the main thread and never throws; after fresh
 * renders the art set is pushed into the guests via [BannerArtWriter.refreshAsync].
 */
object NsfwArtFetcher {

    private const val TAG = "NsfwArtFetcher"

    // Default APIs used when the URL template preference is blank.
    private const val DEFAULT_URL_LEVEL_1 = "https://api.purrbot.site/v2/img/sfw/neko/img" // Minimal
    private const val DEFAULT_URL_LEVEL_2 = "https://nekos.life/api/v2/img/lewd" // Moderate
    private const val DEFAULT_URL_LEVEL_3 = "https://api.purrbot.site/v2/img/nsfw/neko/img" // Fan service
    private const val DEFAULT_URL_LEVEL_4 = "https://api.purrbot.site/v2/img/nsfw/neko/img" // Full

    /** `{level}` template replacements, indexed by level - 1. */
    private val LEVEL_TOKENS = listOf("minimal", "moderate", "fanservice", "full")

    private const val MAX_SOURCES = 10
    private const val MAX_TRIES = 10
    private const val MAX_CONSECUTIVE_FAILURES = 3
    private const val REFRESH_BUDGET_MS = 90_000L
    private const val PAUSE_MS = 450L
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val MAX_RESPONSE_BYTES = 12 * 1024 * 1024
    private const val USER_AGENT = "Bliss/1.0 (Android)"
    private const val HEX = "0123456789abcdef"

    private val URL_REGEX = Regex("https?://[^\\s\"'\\\\]+")
    private val IMAGE_PATH_REGEX = Regex("\\.(png|jpe?g|webp|gif)$", RegexOption.IGNORE_CASE)

    /** Serializes refreshes so concurrent taps cannot race the cache. */
    private val refreshLock = Any()

    /** Number of rendered `.txt` arts cached at the current banner quality. */
    fun cachedCount(ctx: Context): Int {
        val quality = TerminalPreferences.getBannerQuality(ctx).coerceIn(1, 5)
        return levelDir(ctx, quality)
            .listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?.size ?: 0
    }

    /** Wipe the whole `banner-nsfw` cache (sources + every quality level). */
    fun clear(ctx: Context) {
        val removed = rootDir(ctx).deleteRecursively()
        Log.d(TAG, "cache cleared (removed=$removed)")
    }

    /**
     * Background refresh on a daemon thread; [onDone] is posted on the main
     * thread with the number of arts available at the current quality.
     */
    fun refreshAsync(ctx: Context, onDone: ((Int) -> Unit)? = null) {
        val app = ctx.applicationContext
        val callback = onDone
        val mainHandler = Handler(Looper.getMainLooper())
        Thread({
            val count = try {
                doRefresh(app)
            } catch (t: Throwable) {
                Log.e(TAG, "refresh thread failed", t)
                0
            }
            if (callback != null) {
                mainHandler.post { callback(count) }
            }
        }, "bliss-nsfw-art").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * The actual refresh work — callable from a coroutine (or directly from the
     * [refreshAsync] thread body). Never throws; returns the number of arts
     * available at the current quality (freshly rendered or already cached).
     */
    suspend fun refresh(ctx: Context): Int = doRefresh(ctx.applicationContext)

    private fun doRefresh(ctx: Context): Int = synchronized(refreshLock) { doRefreshLocked(ctx) }

    private fun doRefreshLocked(ctx: Context): Int {
        return try {
            val level = TerminalPreferences.getNsfwLevel(ctx).coerceIn(1, 4)
            val quality = TerminalPreferences.getBannerQuality(ctx).coerceIn(1, 5)
            if (!TerminalPreferences.isNsfwEnabled(ctx)) {
                Log.d(TAG, "NSFW banner disabled; refresh skipped")
                return 0
            }
            val sourceDir = sourceDir(ctx)
            val outputDir = levelDir(ctx, quality)
            sourceDir.mkdirs()
            outputDir.mkdirs()
            deleteTempFiles(sourceDir)
            deleteTempFiles(outputDir)

            var sources = listSources(sourceDir)
            if (sources.size > MAX_SOURCES) {
                sources.drop(MAX_SOURCES).forEach { it.delete() }
                sources = listSources(sourceDir)
            }
            Log.d(TAG, "refresh start: level=$level quality=$quality sources=${sources.size}/$MAX_SOURCES")

            // 1. Top up the raw source cache (NN-<sha1-8>.img, at most 10).
            if (sources.size < MAX_SOURCES) {
                val template = TerminalPreferences.getNsfwApiUrl(ctx).orEmpty().trim()
                val apiKey = TerminalPreferences.getNsfwApiKey(ctx).orEmpty().trim()
                val seenSha = sources.mapTo(HashSet<String>()) { shaSuffix(it.name) }
                val seenUrls = HashSet<String>()
                val deadline = System.currentTimeMillis() + REFRESH_BUDGET_MS
                var nextIndex = firstFreeIndex(sources)
                var failures = 0
                var tries = 0
                while (tries < MAX_TRIES &&
                    failures < MAX_CONSECUTIVE_FAILURES &&
                    System.currentTimeMillis() < deadline &&
                    listSources(sourceDir).size < MAX_SOURCES
                ) {
                    tries++
                    val url = resolveSourceUrl(template, level)
                    if (url.isBlank() || !seenUrls.add(url)) {
                        Thread.sleep(PAUSE_MS)
                        continue
                    }
                    val bytes = fetchImageBytes(url, apiKey)
                    if (bytes == null || bytes.isEmpty()) {
                        failures++
                        Log.w(TAG, "fetch failed ($failures/$MAX_CONSECUTIVE_FAILURES): $url")
                    } else {
                        failures = 0
                        val sha = sha1Hex(bytes).take(8)
                        if (!seenSha.add(sha)) {
                            Log.d(TAG, "duplicate image skipped ($sha)")
                        } else {
                            val file = File(sourceDir, String.format(Locale.US, "%02d-%s.img", nextIndex, sha))
                            if (writeAtomic(file, bytes)) {
                                Log.d(TAG, "cached ${file.name} (${bytes.size} B)")
                            }
                            nextIndex = firstFreeIndex(listSources(sourceDir))
                        }
                    }
                    Thread.sleep(PAUSE_MS)
                }
            }

            // 2. Make sure every source has a rendered art at the current quality.
            val currentSources = listSources(sourceDir)
            val keep = HashSet<String>(currentSources.size)
            var rendered = 0
            var freshlyRendered = 0
            for (source in currentSources) {
                val target = File(outputDir, source.name.removeSuffix(".img") + ".txt")
                keep.add(target.name)
                if (target.isFile && target.length() > 0L) {
                    rendered++
                    continue
                }
                val art = HalfBlockRenderer.render(source.readBytes(), colsForQuality(quality))
                if (art.isNullOrEmpty()) {
                    Log.w(TAG, "render failed for ${source.name}")
                    continue
                }
                if (writeAtomic(target, art.toByteArray(Charsets.UTF_8))) {
                    rendered++
                    freshlyRendered++
                    Log.d(TAG, "rendered ${target.name} (${art.length} chars)")
                }
            }

            // 3. Drop renders whose source is gone (quality change / clear).
            outputDir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(".txt") && f.name !in keep) {
                    f.delete()
                }
            }

            // New arts mean the guests still carry the previous set — push now.
            // (The settings UI also pushes right after starting the fetch, but
            // that runs before the downloads/renders have completed.)
            if (freshlyRendered > 0) {
                BannerArtWriter.refreshAsync(ctx)
            }

            Log.d(TAG, "refresh done: $rendered art(s) available at quality $quality (fresh=$freshlyRendered)")
            rendered
        } catch (t: Throwable) {
            Log.e(TAG, "refresh failed", t)
            0
        }
    }

    /** Columns per banner quality: 1->24, 2->32, 3->40, 4->48, 5->54. */
    internal fun colsForQuality(quality: Int): Int = when (quality) {
        1 -> 24
        2 -> 32
        3 -> 40
        4 -> 48
        else -> 54
    }

    /** Built-in default URL for [level], or the custom template with token substitution. */
    private fun resolveSourceUrl(template: String, level: Int): String {
        if (template.isBlank()) {
            return when (level) {
                1 -> DEFAULT_URL_LEVEL_1
                2 -> DEFAULT_URL_LEVEL_2
                3 -> DEFAULT_URL_LEVEL_3
                else -> DEFAULT_URL_LEVEL_4
            }
        }
        return template
            .replace("{level}", LEVEL_TOKENS[level - 1])
            .replace("{rand}", Random.nextInt(0, 1_000_000).toString())
    }

    /**
     * GET [url]; image responses return their bytes, JSON/text responses are
     * scanned for the first image URL which is then fetched. Null on failure.
     */
    private fun fetchImageBytes(url: String, apiKey: String): ByteArray? {
        val first = readResponse(url, apiKey) ?: return null
        if (first.contentType.startsWith("image/") || looksLikeImage(first.body)) return first.body
        var text = String(first.body, Charsets.UTF_8)
        if (text.contains("\\/")) text = text.replace("\\/", "/") // escaped-slash JSON
        val imageUrl = findImageUrl(text)
        if (imageUrl == null) {
            Log.w(TAG, "no image URL in API response from $url")
            return null
        }
        if (imageUrl == url) return null
        val second = readResponse(imageUrl, apiKey) ?: return null
        return if (second.contentType.startsWith("image/") || looksLikeImage(second.body)) {
            second.body
        } else {
            Log.w(TAG, "resolved URL is not an image: $imageUrl")
            null
        }
    }

    private class Response(val contentType: String, val body: ByteArray)

    /** One HTTP GET with the Bliss UA, optional bearer key and size cap. */
    private fun readResponse(url: String, apiKey: String): Response? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json, image/*, */*")
                if (apiKey.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "HTTP $code for $url")
                return null
            }
            val contentType = (connection.contentType ?: "")
                .substringBefore(';').trim().lowercase(Locale.US)
            val body = connection.inputStream.use { readCapped(it, MAX_RESPONSE_BYTES) }
            if (body.isEmpty()) return null
            Response(contentType, body)
        } catch (t: Throwable) {
            Log.w(TAG, "request failed for $url: ${t.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** First `https?://` URL whose path ends in an image extension, else the first URL. */
    private fun findImageUrl(text: String): String? {
        val urls = URL_REGEX.findAll(text).map { it.value }.toList()
        if (urls.isEmpty()) return null
        val imageUrl = urls.firstOrNull { url ->
            IMAGE_PATH_REGEX.containsMatchIn(url.substringBefore('#').substringBefore('?'))
        }
        return imageUrl ?: urls.first()
    }

    /** Magic-byte sniffing for servers that omit/mislabel `Content-Type`. */
    private fun looksLikeImage(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        fun b(index: Int): Int = bytes[index].toInt() and 0xFF
        val jpeg = b(0) == 0xFF && b(1) == 0xD8 && b(2) == 0xFF
        val png = b(0) == 0x89 && b(1) == 0x50 && b(2) == 0x4E && b(3) == 0x47
        val gif = b(0) == 0x47 && b(1) == 0x49 && b(2) == 0x46 && b(3) == 0x38
        val webp = b(0) == 0x52 && b(1) == 0x49 && b(2) == 0x46 && b(3) == 0x46 &&
            b(8) == 0x57 && b(9) == 0x45 && b(10) == 0x42 && b(11) == 0x50
        return jpeg || png || gif || webp
    }

    private fun readCapped(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream(64 * 1024)
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw IOException("response exceeds $limit bytes")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun sha1Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val value = b.toInt() and 0xFF
            sb.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return sb.toString()
    }

    /** `NN-<sha1-8>.img` -> `<sha1-8>`. */
    private fun shaSuffix(fileName: String): String =
        fileName.substringAfter('-', "").removeSuffix(".img")

    private fun firstFreeIndex(sources: List<File>): Int {
        val used = sources.mapNotNull { it.name.substringBefore('-').toIntOrNull() }.toSet()
        var index = 0
        while (index in used) index++
        return index
    }

    private fun listSources(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".img") }?.sortedBy { it.name } ?: emptyList()

    /** Write via `.tmp` + rename so a half-written file is never picked up. */
    private fun writeAtomic(target: File, bytes: ByteArray): Boolean {
        val tmp = File(target.parentFile, target.name + ".tmp")
        return try {
            tmp.outputStream().use { it.write(bytes) }
            if (target.exists()) target.delete()
            if (tmp.renameTo(target)) {
                true
            } else {
                tmp.delete()
                false
            }
        } catch (t: Throwable) {
            tmp.delete()
            false
        }
    }

    private fun deleteTempFiles(dir: File) {
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".tmp")) f.delete()
        }
    }

    private fun rootDir(ctx: Context): File = File(ctx.applicationContext.cacheDir, "banner-nsfw")

    private fun sourceDir(ctx: Context): File = File(rootDir(ctx), "src")

    private fun levelDir(ctx: Context, quality: Int): File = File(rootDir(ctx), "l$quality")
}
