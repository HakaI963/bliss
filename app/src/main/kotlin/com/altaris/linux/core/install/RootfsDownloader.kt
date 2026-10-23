package com.altaris.linux.core.install

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Kotlin-side rootfs downloader (D2): makes the selected distro's pinned rootfs
 * archive available under [destDir]/[DistroInstallProfile.rootfsFileName].
 *
 * Local-first (D7): a verified destination file or a verified local candidate
 * (same locations `altaris_install.sh` searches) short-circuits before any network
 * access. Only then does it GET [DistroInstallProfile.rootfsUrl] from the
 * GitHub release tag `rootfs` into `<name>.partial` with HTTP Range resume
 * (D5), SHA256 + minimum-size gates (D4), cancellation, progress and a
 * free-space precheck (D11). Because ISPs commonly block GitHub's release
 * asset redirect host (`release-assets.githubusercontent.com`), each download
 * falls back to ordered public GitHub mirror proxies when the origin fails
 * ([mirrorUrls]); a mirror that fails the SHA gate cannot poison the resume.
 *
 * Speed (B1): the first request sends `Range: bytes=<existing>-`. When the
 * server answers 206 with `Accept-Ranges: bytes`, a `Content-Range` that covers
 * the remainder, and more than [PARALLEL_MIN_REMAINING_BYTES] left, that body
 * becomes segment 0 and [PARALLEL_SEGMENTS]-1 more range requests run
 * concurrently into one `RandomAccessFile` (positional
 * `FileChannel.write(buf, position)`), each with its own retry/resume window.
 * Progress is the sum of segment bytes, throttled to ~150 ms. The classic
 * single-stream loop below is the fallback and stays byte-for-byte intact for
 * 200-only servers, missing range headers, unknown/short totals, small
 * remainders and any probe-like failure — no extra request is ever spent on
 * probing. Every path still ends in [finishVerified].
 *
 * JVM-testable: dest directory + [OkHttpClient] are injected; no Android
 * [android.content.Context] is required in the core API.
 */
object RootfsDownloader {

    private const val TAG = "RootfsDownloader"

    /** Free-space slack beyond the expected download size (D11). */
    const val FREE_SPACE_SLACK_BYTES = 8L * 1024L * 1024L

    /** Streaming buffer — a 127 MiB Manjaro archive is never loaded into memory. */
    private const val CHUNK_BYTES = 64 * 1024

    /** Concurrent segments + minimum remainder worth racing (B1). */
    private const val PARALLEL_SEGMENTS = 6
    private const val PARALLEL_MIN_REMAINING_BYTES = 16L * 1024L * 1024L

    /** Per-segment attempt budget; each attempt resumes at the bytes on disk. */
    private const val SEGMENT_RETRIES = 3

    /** Progress emit throttle — the UI callback reads the value directly. */
    private const val PROGRESS_INTERVAL_NANOS = 150_000_000L

    /** `Content-Range: bytes <start>-<end>/<total>` (total only, never `*`). */
    private val CONTENT_RANGE_RE = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""")

    /** GitHub release asset URL (`https://github.com/<owner>/<repo>/releases/download/...`). */
    private val GITHUB_RELEASE_RE =
        Regex("""https://github\.com/[^/]+/[^/]+/releases/download/.+""")

    /**
     * Ordered public GitHub download proxies. Release-asset redirects land on
     * `release-assets.githubusercontent.com`, which many ISPs block while
     * `github.com` itself resolves — each prefix is tried at most once per
     * download.
     */
    private val MIRROR_PREFIXES = listOf(
        // Fastest observed with large release assets; measured at 7–10 MB/s
        // while the origin was often throttled or hanging.
        "https://gh-proxy.com/",
        "https://ghfast.top/",
        "https://ghproxy.net/",
        "https://github.moeyy.xyz/",
        "https://gh.llkk.cc/"
    )

    data class Progress(
        val downloadedBytes: Long,
        /** Total expected bytes; -1 when unknown. */
        val totalBytes: Long
    )

    /**
     * OkHttp with redirects (GitHub 302 → objects.githubusercontent.com), 30 s
     * connect, 60 s read/write timeouts and **no overall call timeout**
     * (Manjaro is ~127 MiB on potentially slow mobile links).
     */
    val defaultClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun DistroInstallProfile.toPinnedArchive(): PinnedReleaseArchive =
        PinnedReleaseArchive(
            fileName = rootfsFileName,
            sha256 = rootfsSha256,
            url = rootfsUrl,
            minBytes = rootfsMinBytes
        )

    /**
     * True when `destDir/rootfsFileName` exists, is larger than
     * [DistroInstallProfile.rootfsMinBytes], and its SHA256 matches
     * [DistroInstallProfile.rootfsSha256].
     */
    fun isDeployed(destDir: File, profile: DistroInstallProfile): Boolean =
        isDeployed(destDir, profile.toPinnedArchive())

    fun isDeployed(destDir: File, archive: PinnedReleaseArchive): Boolean =
        isValid(File(destDir, archive.fileName), archive)

    /**
     * Guarantee a verified rootfs archive in [destDir].
     *
     * 1. verified destination → true (zero HTTP requests)
     * 2. first verified local candidate (same concept as `altaris_install.sh`)
     *    copied into the destination → true (zero HTTP requests)
     * 3. otherwise OkHttp GET with Range resume into `<name>.partial`,
     *    verify SHA + size, then atomic rename to the final filename. The
     *    origin URL is tried first, then each public GitHub mirror in order;
     *    mirror candidates are skipped after their first hard failure.
     *
     * Cancellation and gate failures keep the `.partial` file (D5) so a later
     * call can resume. A downloaded file failing the SHA/min-size gate is
     * deleted (D4). Between mirrors the partial is kept on transport failures
     * (identical bytes) but deleted after a gate failure so a bad mirror
     * cannot poison the next candidate's resume.
     */
    fun ensurePresent(
        destDir: File,
        profile: DistroInstallProfile,
        client: OkHttpClient = defaultClient,
        isCancelled: () -> Boolean = { false },
        onProgress: (Progress) -> Unit = {}
    ): Boolean = ensurePresent(
        destDir,
        profile.toPinnedArchive(),
        client,
        isCancelled,
        onProgress,
        extraLocalCandidates = localCandidates(destDir, profile)
    )

    fun ensurePresent(
        destDir: File,
        archive: PinnedReleaseArchive,
        client: OkHttpClient = defaultClient,
        isCancelled: () -> Boolean = { false },
        onProgress: (Progress) -> Unit = {},
        extraLocalCandidates: List<File> = emptyList()
    ): Boolean {
        destDir.mkdirs()
        val dest = File(destDir, archive.fileName)

        // 1. Verified destination — done, no network.
        if (isValid(dest, archive)) {
            Log.i(TAG, "Archive already deployed and verified: ${dest.absolutePath}")
            return true
        }

        // 2. Local candidates (D7). $HOME/rootfs/, proot cache, Download dirs.
        val candidates = extraLocalCandidates.ifEmpty {
            listOf(
                File(destDir, archive.fileName),
                File(destDir, "rootfs/${archive.fileName}"),
                File("/sdcard/Download/${archive.fileName}"),
                File("/storage/emulated/0/Download/${archive.fileName}")
            )
        }
        for (candidate in candidates) {
            if (!candidate.isFile) continue
            if (candidate.absolutePath == dest.absolutePath) continue
            if (isCancelled()) return false
            if (!isValid(candidate, archive)) {
                Log.w(TAG, "Candidate fails gate, skipped: ${candidate.absolutePath}")
                continue
            }
            Log.i(TAG, "Copying verified local candidate ${candidate.absolutePath} → ${dest.absolutePath}")
            if (!copyWithCancel(candidate, dest, isCancelled)) return false
            if (isValid(dest, archive)) {
                Log.i(TAG, "Archive from local candidate: ${dest.absolutePath}")
                return true
            }
            Log.w(TAG, "Copied candidate failed gate — deleting ${dest.absolutePath}")
            dest.delete()
        }

        if (isCancelled()) return false

        // 3. Network download with Range resume, trying the public GitHub
        // mirrors first and the origin URL last (release-assets is often
        // throttled while the proxies run at full speed). A SHA gate failure
        // deletes the `.partial` before the next candidate so a bad mirror
        // cannot poison the resume; transport failures keep it (all mirrors
        // serve identical bytes).
        // On the final candidate the partial is kept (D5 resume semantics).
        // Cancellation stops the whole loop immediately.
        val sources = mirrorUrls(archive.url)
        for ((index, sourceUrl) in sources.withIndex()) {
            if (isCancelled()) return false
            if (index > 0) Log.w(TAG, "download source failed, trying next: $sourceUrl")
            when (download(destDir, dest, archive, sourceUrl, client, isCancelled, onProgress)) {
                DownloadAttempt.OK -> return true
                DownloadAttempt.GATE_FAILED -> {
                    // Between mirrors only: a wrong mirror must not poison the
                    // resume, but the last candidate keeps D5 semantics.
                    if (index < sources.size - 1) {
                        Log.w(TAG, "SHA gate failed — deleting partial before next mirror")
                        File(destDir, archive.fileName + ".partial").delete()
                    }
                }
                DownloadAttempt.FAILED -> Unit
            }
        }
        return false
    }

    /** One mirror's network outcome — only [GATE_FAILED] may not resume. */
    private enum class DownloadAttempt { OK, FAILED, GATE_FAILED }

    /**
     * Download candidates for [url]: each public GitHub proxy prefix + [url]
     * first, then the origin URL last, when [url] is a GitHub release asset
     * (`https://github.com/<owner>/<repo>/releases/download/...`).
     * Non-release URLs return `listOf(url)`. Pure and unit-testable.
     */
    internal fun mirrorUrls(url: String): List<String> {
        if (!GITHUB_RELEASE_RE.matches(url)) return listOf(url)
        return MIRROR_PREFIXES.map { prefix -> prefix + url } + url
    }

    // ── network ─────────────────────────────────────────────────────────────

    private fun download(
        destDir: File,
        dest: File,
        archive: PinnedReleaseArchive,
        sourceUrl: String,
        client: OkHttpClient,
        isCancelled: () -> Boolean,
        onProgress: (Progress) -> Unit
    ): DownloadAttempt {
        val partial = File(destDir, archive.fileName + ".partial")

        // Parallel segmented path (B1). The classic first request doubles as the
        // probe: a 206 whose headers advertise byte ranges and a known total
        // makes its body segment 0 while PARALLEL_SEGMENTS-1 more range requests
        // run concurrently (no extra request is ever sent when ranges are
        // unsupported — that response just streams single-threaded as before).
        // `Range: bytes=<existing>-` is sent even for fresh downloads so a
        // range-capable server can answer 206 with `Content-Range`.
        repeat(2) { attempt ->
            val existing = if (partial.isFile) partial.length() else 0L
            val request = Request.Builder()
                .url(sourceUrl)
                .apply {
                    // Fresh download (attempt 0) still sends Range: bytes=0- so a
                    // range-capable server answers 206 and the parallel path can
                    // engage. The retry after a 416 is a clean full GET (old D5
                    // behavior) — the partial is gone, so nothing to resume.
                    if (existing > 0L || attempt == 0) {
                        header("Range", "bytes=$existing-")
                    }
                }
                .build()

            val response = try {
                client.newCall(request).execute()
            } catch (e: IOException) {
                Log.e(TAG, "Rootfs GET failed: ${e.message}")
                return DownloadAttempt.FAILED
            }

            response.use { resp ->
                when {
                    resp.code == 416 -> {
                        // Server says the partial is past EOF — delete and
                        // restart with a clean full GET.
                        Log.w(TAG, "416 Range Not Satisfiable — restarting download")
                        partial.delete()
                        if (attempt >= 1) {
                            Log.e(TAG, "Second 416 — giving up")
                            return DownloadAttempt.FAILED
                        }
                        return@use
                    }
                    resp.code == 206 -> {
                        // Append to existing partial. If the server actually
                        // restarted the payload (200 behavior under 206), the
                        // SHA gate below deletes it — no corrupt append (D5).
                        val body = resp.body ?: return DownloadAttempt.FAILED
                        val contentLength = body.contentLength()
                        val total = if (contentLength > 0L) existing + contentLength else -1L
                        // Parallel upgrade (B1): the open-ended 206 covers the
                        // whole remainder and its headers prove range support.
                        // Its body becomes segment 0 (the surplus is dropped);
                        // segments 1..5 are fetched concurrently.
                        if (contentLength > PARALLEL_MIN_REMAINING_BYTES &&
                            "bytes".equals(resp.header("Accept-Ranges"), ignoreCase = true) &&
                            rangeCoversRemainder(
                                resp.header("Content-Range"), existing, contentLength
                            )
                        ) {
                            if (!checkFreeSpace(destDir, contentLength, archive.minBytes)) {
                                return DownloadAttempt.FAILED
                            }
                            Log.i(
                                TAG,
                                "parallel download: $PARALLEL_SEGMENTS segments, " +
                                    "total=$total existing=$existing"
                            )
                            return downloadParallel(
                                dest, partial, archive, sourceUrl, client, isCancelled, onProgress,
                                total, existing, body
                            )
                        }
                        val remaining = if (contentLength > 0L) contentLength else -1L
                        if (!checkFreeSpace(destDir, remaining, archive.minBytes)) {
                            return DownloadAttempt.FAILED
                        }
                        val ok = streamBody(body, partial, append = true, total, isCancelled, onProgress)
                        if (!ok || isCancelled()) return DownloadAttempt.FAILED
                        return finishVerified(dest, partial, archive)
                    }
                    resp.code == 200 -> {
                        // Full response — truncate/restart, never append a
                        // stale partial (D5).
                        val body = resp.body ?: return DownloadAttempt.FAILED
                        val contentLength = body.contentLength()
                        if (!checkFreeSpace(destDir, contentLength, archive.minBytes)) {
                            return DownloadAttempt.FAILED
                        }
                        val ok = streamBody(body, partial, append = false, contentLength, isCancelled, onProgress)
                        if (!ok || isCancelled()) return DownloadAttempt.FAILED
                        return finishVerified(dest, partial, archive)
                    }
                    else -> {
                        Log.e(TAG, "Rootfs download HTTP ${resp.code}")
                        return DownloadAttempt.FAILED
                    }
                }
            }
        }
        return DownloadAttempt.FAILED
    }

    /**
     * Stream the response body into [partial], reporting progress and honoring
     * cancellation. Returns false on IO failure or cancellation (partial kept).
     */
    private fun streamBody(
        body: okhttp3.ResponseBody,
        partial: File,
        append: Boolean,
        totalBytes: Long,
        isCancelled: () -> Boolean,
        onProgress: (Progress) -> Unit
    ): Boolean {
        val started = if (append) partial.length() else 0L
        var downloaded = started
        try {
            FileOutputStream(partial, append).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(CHUNK_BYTES)
                    while (true) {
                        if (isCancelled()) {
                            Log.i(TAG, "Download cancelled — keeping partial ${partial.absolutePath}")
                            return false
                        }
                        val read = input.read(buf)
                        if (read == -1) break
                        out.write(buf, 0, read)
                        downloaded += read
                        onProgress(Progress(downloaded, totalBytes))
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Download stream failed — partial kept: ${e.message}")
            return false
        }
        return true
    }

    /**
     * One byte-range segment of the archive: absolute `[start, start + length)`.
     */
    private class Segment(val index: Int, val start: Long, val length: Long) {
        val endInclusive: Long get() = start + length - 1
    }

    /**
     * True when `Content-Range: bytes <start>-<end>/<total>` covers exactly
     * `[existing, existing + contentLength)`: the open-ended request returned
     * the whole remainder, so its body can serve as segment 0.
     */
    private fun rangeCoversRemainder(
        header: String?,
        existing: Long,
        contentLength: Long
    ): Boolean {
        if (header == null) return false
        val match = CONTENT_RANGE_RE.find(header.trim()) ?: return false
        val start = match.groupValues[1].toLongOrNull() ?: return false
        val end = match.groupValues[2].toLongOrNull() ?: return false
        val total = match.groupValues[3].toLongOrNull() ?: return false
        return start == existing && end == total - 1L &&
            total == existing + contentLength && total > existing
    }

    /**
     * Download the remaining `[existing, total)` bytes as [PARALLEL_SEGMENTS]
     * concurrent byte-range streams into one positional-write
     * [RandomAccessFile]. Segment 0 flows from [firstBody] — the already-open
     * 206 response of the first request — so no extra request is spent on the
     * probe; segments 1..[PARALLEL_SEGMENTS]-1 are Range GETs.
     *
     * - each worker retries up to [SEGMENT_RETRIES] times, resuming at the bytes
     *   already written for its segment (`Range: bytes=<segStart+written>-<segEnd>`);
     * - if the adopted segment-0 stream dies, its remainder is retried with a
     *   normal Range request;
     * - a permanent worker failure aborts and cancels every in-flight call;
     * - cancellation is polled per chunk in every worker, which then cancels all
     *   in-flight calls (stalled reads unblock via socket close);
     * - on any failure the partial is truncated to the longest contiguous
     *   downloaded prefix, so the existing `.partial` resume semantics hold.
     *
     * Success still funnels through [finishVerified] (size + SHA gate).
     */
    private fun downloadParallel(
        dest: File,
        partial: File,
        archive: PinnedReleaseArchive,
        sourceUrl: String,
        client: OkHttpClient,
        isCancelled: () -> Boolean,
        onProgress: (Progress) -> Unit,
        total: Long,
        existing: Long,
        firstBody: okhttp3.ResponseBody
    ): DownloadAttempt {
        val remaining = total - existing
        val base = remaining / PARALLEL_SEGMENTS
        val extra = remaining % PARALLEL_SEGMENTS
        val segments = ArrayList<Segment>(PARALLEL_SEGMENTS)
        var cursor = existing
        for (i in 0 until PARALLEL_SEGMENTS) {
            val length = base + if (i < extra) 1L else 0L
            segments.add(Segment(i, cursor, length))
            cursor += length
        }

        val written = AtomicLongArray(PARALLEL_SEGMENTS)
        val bytesDone = AtomicLong(existing)
        val aborted = AtomicBoolean(false)
        val lastEmit = AtomicLong(0L)
        val inFlight = Collections.synchronizedList(ArrayList<Call>())
        onProgress(Progress(existing, total))

        val raf = try {
            RandomAccessFile(partial, "rw")
        } catch (e: IOException) {
            Log.e(TAG, "parallel download: cannot open ${partial.absolutePath}: ${e.message}")
            return DownloadAttempt.FAILED
        }
        try {
            // Preallocate once the total is known; positional writes then never
            // race on file extension. A crash here leaves a full-size partial,
            // which the next run's 416 handling discards (no corrupt resume).
            raf.setLength(total)
        } catch (e: IOException) {
            Log.e(TAG, "parallel download: cannot preallocate ${partial.absolutePath}: ${e.message}")
            closeQuietly(raf)
            return DownloadAttempt.FAILED
        }

        val executor = Executors.newFixedThreadPool(PARALLEL_SEGMENTS)
        var ok = true
        try {
            val futures = ArrayList<Future<Boolean>>(PARALLEL_SEGMENTS - 1)
            for (i in 1 until PARALLEL_SEGMENTS) {
                val segment = segments[i]
                futures.add(
                    executor.submit(
                        Callable {
                            runSegment(
                                segment, raf.channel, sourceUrl, client, isCancelled,
                                aborted, inFlight, written, bytesDone, total, lastEmit, onProgress
                            )
                        }
                    )
                )
            }
            // Segment 0 streams from the response we already hold; only its
            // remainder needs a new Range request if the stream dies early.
            var segment0Ok = readBodyIntoSegment(
                segments[0], firstBody, raf.channel, isCancelled, aborted, inFlight,
                written, bytesDone, total, lastEmit, onProgress
            )
            if (!segment0Ok && !aborted.get() && !isCancelled()) {
                Log.i(TAG, "segment 0: adopted 206 body ended early — resuming via Range")
                segment0Ok = runSegment(
                    segments[0], raf.channel, sourceUrl, client, isCancelled,
                    aborted, inFlight, written, bytesDone, total, lastEmit, onProgress
                )
            }
            if (!segment0Ok) {
                ok = false
                aborted.set(true)
                cancelInFlight(inFlight)
            }
            for (future in futures) {
                val segmentOk = try {
                    future.get()
                } catch (e: Exception) {
                    false
                }
                if (!segmentOk) {
                    ok = false
                    aborted.set(true)
                    cancelInFlight(inFlight)
                }
            }
        } finally {
            executor.shutdownNow()
        }

        if (ok && !aborted.get() && !isCancelled()) {
            closeQuietly(raf)
            // Final tick — the throttle may have skipped the last chunk.
            onProgress(Progress(bytesDone.get(), total))
            return finishVerified(dest, partial, archive)
        }

        // Truncate to the contiguous prefix (segments fill in order, so this is
        // the first incomplete segment's start + its written bytes).
        var completed = 0
        while (completed < segments.size && written.get(completed) == segments[completed].length) {
            completed++
        }
        val watermark =
            if (completed >= segments.size) total
            else segments[completed].start + written.get(completed)
        try {
            raf.setLength(watermark)
        } catch (_: IOException) {
        }
        closeQuietly(raf)
        Log.w(TAG, "parallel download incomplete — partial truncated to $watermark for resume")
        return DownloadAttempt.FAILED
    }

    /**
     * Stream an already-open response body into one segment (the adopted 206
     * response). Returns true when the segment filled; a shorter read leaves the
     * remainder for [runSegment]'s Range retry.
     */
    @Suppress("LongParameterList")
    private fun readBodyIntoSegment(
        segment: Segment,
        body: okhttp3.ResponseBody,
        channel: FileChannel,
        isCancelled: () -> Boolean,
        aborted: AtomicBoolean,
        inFlight: MutableList<Call>,
        written: AtomicLongArray,
        bytesDone: AtomicLong,
        total: Long,
        lastEmit: AtomicLong,
        onProgress: (Progress) -> Unit
    ): Boolean {
        try {
            body.byteStream().use { input ->
                val buf = ByteArray(CHUNK_BYTES)
                while (true) {
                    if (isCancelled() || aborted.get()) {
                        if (isCancelled()) {
                            aborted.set(true)
                            cancelInFlight(inFlight)
                        }
                        return false
                    }
                    val position = segment.start + written.get(segment.index)
                    val left = segment.endInclusive - position + 1
                    if (left <= 0L) break
                    val want = minOf(buf.size.toLong(), left).toInt()
                    val read = input.read(buf, 0, want)
                    if (read == -1) break
                    writeFully(channel, ByteBuffer.wrap(buf, 0, read), position)
                    written.addAndGet(segment.index, read.toLong())
                    bytesDone.addAndGet(read.toLong())
                    maybeEmitProgress(bytesDone, total, lastEmit, onProgress)
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "segment 0: adopted body failed: ${e.message}")
        }
        return written.get(segment.index) >= segment.length
    }

    /**
     * Download one segment with up to [SEGMENT_RETRIES] attempts. Returns true
     * only when the segment is fully on disk; a permanent failure also aborts
     * and cancels every in-flight call.
     */
    @Suppress("LongParameterList")
    private fun runSegment(
        segment: Segment,
        channel: FileChannel,
        url: String,
        client: OkHttpClient,
        isCancelled: () -> Boolean,
        aborted: AtomicBoolean,
        inFlight: MutableList<Call>,
        written: AtomicLongArray,
        bytesDone: AtomicLong,
        total: Long,
        lastEmit: AtomicLong,
        onProgress: (Progress) -> Unit
    ): Boolean {
        var attempt = 0
        while (attempt < SEGMENT_RETRIES) {
            attempt++
            if (isCancelled()) {
                aborted.set(true)
                cancelInFlight(inFlight)
                return false
            }
            if (aborted.get()) return false
            val done = written.get(segment.index)
            if (done >= segment.length) return true
            val from = segment.start + done
            val request = Request.Builder()
                .url(url)
                .header("Range", "bytes=$from-${segment.endInclusive}")
                .build()
            val call = client.newCall(request)
            inFlight.add(call)
            try {
                call.execute().use { resp ->
                    if (resp.code != 206) {
                        Log.w(TAG, "segment ${segment.index}: HTTP ${resp.code}")
                    } else {
                        val body = resp.body
                        if (body != null) {
                            body.byteStream().use { input ->
                                val buf = ByteArray(CHUNK_BYTES)
                                while (true) {
                                    if (isCancelled() || aborted.get()) {
                                        if (isCancelled()) {
                                            aborted.set(true)
                                            cancelInFlight(inFlight)
                                        }
                                        return false
                                    }
                                    val position = segment.start + written.get(segment.index)
                                    val left = segment.endInclusive - position + 1
                                    if (left <= 0L) break
                                    val want = minOf(buf.size.toLong(), left).toInt()
                                    val read = input.read(buf, 0, want)
                                    if (read == -1) break
                                    writeFully(channel, ByteBuffer.wrap(buf, 0, read), position)
                                    written.addAndGet(segment.index, read.toLong())
                                    bytesDone.addAndGet(read.toLong())
                                    maybeEmitProgress(bytesDone, total, lastEmit, onProgress)
                                }
                            }
                        }
                        if (written.get(segment.index) >= segment.length) return true
                        Log.w(
                            TAG,
                            "segment ${segment.index}: short read " +
                                "(${written.get(segment.index)}/${segment.length})"
                        )
                    }
                }
            } catch (e: IOException) {
                if (aborted.get() || isCancelled()) return false
                Log.w(
                    TAG,
                    "segment ${segment.index}: ${e.message} (attempt $attempt/$SEGMENT_RETRIES)"
                )
            } finally {
                inFlight.remove(call)
            }
        }
        aborted.set(true)
        cancelInFlight(inFlight)
        Log.e(TAG, "segment ${segment.index}: giving up after $SEGMENT_RETRIES attempts")
        return false
    }

    /** Positional write loop — [FileChannel.write] may be short. */
    private fun writeFully(channel: FileChannel, buffer: ByteBuffer, position: Long) {
        var pos = position
        while (buffer.hasRemaining()) {
            pos += channel.write(buffer, pos)
        }
    }

    /** Throttled progress emit (~[PROGRESS_INTERVAL_NANOS]); cheap for the UI. */
    private fun maybeEmitProgress(
        bytesDone: AtomicLong,
        total: Long,
        lastEmit: AtomicLong,
        onProgress: (Progress) -> Unit
    ) {
        val now = System.nanoTime()
        val last = lastEmit.get()
        if (now - last < PROGRESS_INTERVAL_NANOS) return
        if (!lastEmit.compareAndSet(last, now)) return
        onProgress(Progress(bytesDone.get(), total))
    }

    private fun cancelInFlight(calls: MutableList<Call>) {
        synchronized(calls) {
            for (call in calls) {
                try {
                    call.cancel()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun closeQuietly(raf: RandomAccessFile) {
        try {
            raf.close()
        } catch (_: IOException) {
        }
    }

    /**
     * Gate the finished `.partial`: size > [PinnedReleaseArchive.minBytes]
     * and SHA256 == [PinnedReleaseArchive.sha256]. On success, atomically
     * rename to the final filename (D4/D5).
     */
    private fun finishVerified(
        dest: File,
        partial: File,
        archive: PinnedReleaseArchive
    ): DownloadAttempt {
        if (!isValid(partial, archive)) {
            // Delete the final output (D4). The partial is kept: a truncated
            // stream resumes via Range; a corrupt-complete partial self-heals
            // through the 416 restart path on the next attempt.
            Log.e(
                TAG,
                "Downloaded archive failed gate: size=${partial.length()} " +
                    "sha=${sha256(partial)} — keeping partial for resume"
            )
            dest.delete()
            return DownloadAttempt.GATE_FAILED
        }
        if (dest.exists()) dest.delete()
        if (!partial.renameTo(dest)) {
            // renameTo can fail across mount quirks; same-dir rename is atomic on
            // Android — fall back to copy+delete only when necessary.
            if (!partial.copyTo(dest, overwrite = true).isFile) {
                Log.e(TAG, "Failed to finalize ${dest.absolutePath}")
                partial.delete()
                return DownloadAttempt.FAILED
            }
            partial.delete()
        }
        Log.i(TAG, "Archive deployed: ${dest.absolutePath} (${dest.length()} bytes, SHA OK)")
        return DownloadAttempt.OK
    }

    // ── gates ───────────────────────────────────────────────────────────────

    internal fun isValid(file: File, profile: DistroInstallProfile): Boolean =
        isValid(file, profile.toPinnedArchive())

    internal fun isValid(file: File, archive: PinnedReleaseArchive): Boolean =
        file.isFile &&
            file.length() > archive.minBytes &&
            sha256(file) == archive.sha256

    /**
     * D11: free-space precheck after response headers.
     * [remaining] >= 0 (Content-Length known) → need `remaining + slack`;
     * unknown length → fall back to `rootfsMinBytes + slack`.
     */
    internal fun hasEnoughFreeSpace(
        usableSpace: Long,
        remaining: Long,
        rootfsMinBytes: Long
    ): Boolean =
        if (remaining >= 0L) {
            usableSpace >= remaining + FREE_SPACE_SLACK_BYTES
        } else {
            usableSpace >= rootfsMinBytes + FREE_SPACE_SLACK_BYTES
        }

    private fun checkFreeSpace(destDir: File, remaining: Long, rootfsMinBytes: Long): Boolean {
        val usable = destDir.usableSpace
        if (usable <= 0L) return true // cannot determine — let the write fail
        if (!hasEnoughFreeSpace(usable, remaining, rootfsMinBytes)) {
            Log.e(
                TAG,
                "Not enough free space: usable=$usable remaining=${if (remaining >= 0) remaining else "?"}"
            )
            return false
        }
        return true
    }

    // ── candidates ──────────────────────────────────────────────────────────

    /**
     * Local candidates in the same order `altaris_install.sh` searches (D7):
     * `$HOME/<name>`, `$HOME/rootfs/<name>`, proot cache, `/sdcard/Download/`,
     * emulated Download. Only the first *verified* candidate is used.
     */
    internal fun localCandidates(destDir: File, profile: DistroInstallProfile): List<File> {
        val name = profile.rootfsFileName
        val filesRoot = destDir.parentFile ?: destDir
        return listOf(
            File(destDir, name),
            File(destDir, "rootfs/$name"),
            File(filesRoot, "usr/var/lib/proot-distro/cache/rootfs/$name"),
            File("/sdcard/Download/$name"),
            File("/sdcard/Download/rootfs.tar.xz"),
            File("/sdcard/Download/rootfs.tar.gz"),
            File("/storage/emulated/0/Download/$name"),
            File("/storage/emulated/0/Download/rootfs.tar.xz"),
            File("/storage/emulated/0/Download/rootfs.tar.gz")
        )
    }

    private fun copyWithCancel(
        src: File,
        dest: File,
        isCancelled: () -> Boolean
    ): Boolean {
        try {
            if (dest.exists()) dest.delete()
            src.inputStream().use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(CHUNK_BYTES)
                    while (true) {
                        if (isCancelled()) return false
                        val read = input.read(buf)
                        if (read == -1) break
                        out.write(buf, 0, read)
                    }
                }
            }
            return true
        } catch (e: IOException) {
            Log.e(TAG, "Candidate copy failed: ${e.message}")
            dest.delete()
            return false
        }
    }

    /** SHA256 hex of [file] (empty string on failure). */
    fun sha256(file: File): String = try {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(CHUNK_BYTES)
            while (true) {
                val r = input.read(buf)
                if (r == -1) break
                md.update(buf, 0, r)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        Log.w(TAG, "sha256 failed: ${e.message}")
        ""
    }
}
