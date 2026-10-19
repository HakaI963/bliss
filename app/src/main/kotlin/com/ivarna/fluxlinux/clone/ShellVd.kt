package com.ivarna.fluxlinux.clone

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import com.ivarna.fluxlinux.BuildConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import rikka.shizuku.Shizuku

/**
 * App-side client for the shell-side clone display ([ShellVdService], Shizuku
 * UserService, uid 2000).
 *
 * Everything that needs shell privileges (the virtual display, input
 * injection, launching clone apps, JPEG frames) happens inside the user
 * service; this object only talks binder and exposes Compose-friendly state.
 *
 * Threading contract:
 *  - [ensure] MUST be called from a background thread. It blocks up to
 *    [BIND_TIMEOUT_MS] waiting for the user service, and Shizuku delivers
 *    `ServiceConnection` callbacks on the main looper, so calling it from the
 *    main thread would deadlock, not connect. It fails fast with an error
 *    string instead.
 *  - Gestures block for their duration ([longPress] >= 450 ms, [swipe] >= 120
 *    ms) and should also be called off the main thread.
 *  - Everything else is safe from any thread; state flows are updated from
 *    background threads and are safe to collect from Compose.
 *
 * Typical integration order:
 *  1. `ShellVd.ensure(app, width, height, dpi, fps)`   (background thread)
 *  2. `ShellVd.startFrames()`                          (optional, for the UI mirror)
 *  3. `ShellVd.launch(pkg, userId)` / `ShellVd.tap(...)` / ...
 *  4. `ShellVd.stopFrames()` (mirror off) and/or `ShellVd.kill(pkg, userId)`
 *  5. `ShellVd.release()` when leaving the clone space (kills the shell-side
 *     process again; the next `ensure()` binds a fresh one).
 */
object ShellVd {

    private const val TAG = "ShellVd"
    private const val BIND_TIMEOUT_MS = 10_000L
    private const val MAX_LOGS = 200
    private const val DEFAULT_JPEG_QUALITY = 70
    private const val DEFAULT_MAX_FRAME_WIDTH = 720
    private const val DEFAULT_FPS = 15

    /** Latest decoded frame of the clone display (null before the first frame or after [release]). */
    val frames = MutableStateFlow<Bitmap?>(null)

    /** Display id owned by the shell service; 0 means "no display". */
    val displayId = MutableStateFlow(0)

    /** Recent client + service log lines (newest last, capped at [MAX_LOGS]). */
    val logs = MutableStateFlow<List<String>>(emptyList())

    /** Package most recently launched on the clone display, null = none. */
    val running = MutableStateFlow<String?>(null)

    /** Monotonic counter incremented for every decoded frame. */
    val framesSeq = MutableStateFlow(0L)

    private val lock = Any()
    @Volatile private var service: IShellVd? = null
    private var userServiceArgs: Shizuku.UserServiceArgs? = null
    private var connectLatch: CountDownLatch? = null

    private var frameFps = DEFAULT_FPS
    private var frameMaxWidth = DEFAULT_MAX_FRAME_WIDTH
    private var frameQuality = DEFAULT_JPEG_QUALITY

    // Staged installs (pm over binder; no stdin piping — the streamed `pm
    // install -S` transport was unreliable on-device).
    private var stageDir = "/data/local/tmp/bliss-stage"
    private var uploadChunk = 256 * 1024


    /** H.264 access units from the shell encoder are decoded here. */
    private val videoDecoder = VideoDecoder()

    private val callback: IShellVdCallback = object : IShellVdCallback.Stub() {
        override fun onVideoSample(
            data: ByteArray?,
            ptsUs: Long,
            keyframe: Boolean,
            format: Boolean,
            width: Int,
            height: Int,
        ) {
            if (data == null || data.isEmpty()) return
            videoDecoder.feed(data, keyframe, format, width, height)
        }

        override fun onLog(line: String?) {
            if (line != null) addLog(line)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val remote = try {
                if (binder == null) null else IShellVd.Stub.asInterface(binder)
            } catch (t: Throwable) {
                Log.w(TAG, "asInterface failed", t)
                null
            }
            synchronized(lock) {
                service = remote
                val latch = connectLatch
                connectLatch = null
                latch?.countDown()
            }
            addLog(if (remote != null) "service connected" else "service connected but binder unusable")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(lock) {
                service = null
                val latch = connectLatch
                connectLatch = null
                latch?.countDown()
            }
            displayId.value = 0
            addLog("service disconnected")
        }
    }

    // ------------------------------------------------------------- lifecycle

    /**
     * Binds the user service when needed and creates (or recreates) the clone
     * display at `width x height @ dpi`. `fps` is remembered for later
     * [startFrames] calls.
     *
     * Must run on a background thread. Returns null on success, otherwise a
     * human-readable error string (Shizuku not running, permission missing,
     * bind timeout, display creation failure).
     */
    fun ensure(app: Context, width: Int, height: Int, dpi: Int, fps: Int): String? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return "ShellVd.ensure() must be called from a background thread"
        }
        val context = app.applicationContext ?: app
        val bindError = bindIfNeeded(context)
        if (bindError != null) return bindError
        val remote = service ?: return "ShellVd user service unavailable"
        return try {
            val id = remote.createDisplay(width, height, dpi)
            if (id <= 0) {
                val message = "createDisplay failed (id=$id)"
                addLog(message)
                return message
            }
            displayId.value = id
            frameFps = fps.coerceIn(1, 30)
            frameMaxWidth = width.coerceIn(120, 4000).coerceAtMost(DEFAULT_MAX_FRAME_WIDTH)
            addLog("display $id created (${width}x$height dpi=$dpi)")
            null
        } catch (t: Throwable) {
            remoteFailure("createDisplay", t)
        }
    }

    /** Starts the JPEG frame stream of the current display. Returns null on success. */
    fun startFrames(
        maxWidth: Int = frameMaxWidth,
        quality: Int = frameQuality,
        fps: Int = frameFps,
    ): String? {
        val remote = service ?: return "ShellVd user service unavailable"
        val widthCap = maxWidth.coerceIn(120, 4000)
        val jpegQuality = quality.coerceIn(10, 100)
        val frameRate = fps.coerceIn(1, 30)
        frameMaxWidth = widthCap
        frameQuality = jpegQuality
        frameFps = frameRate
        return try {
            remote.startFrames(callback, widthCap, jpegQuality, frameRate)
            null
        } catch (t: Throwable) {
            remoteFailure("startFrames", t)
        }
    }

    /** Stops the frame stream (the display and its apps keep running). Returns null on success. */
    fun stopFrames(): String? {
        val remote = service ?: return "ShellVd user service unavailable"
        return try {
            remote.stopFrames()
            null
        } catch (t: Throwable) {
            remoteFailure("stopFrames", t)
        }
    }

    /** Releases the virtual display but keeps the user service bound. Returns null on success. */
    fun releaseDisplay(): String? {
        val remote = service ?: return "ShellVd user service unavailable"
        return try {
            remote.releaseDisplay()
            frames.value = null
            displayId.value = 0
            null
        } catch (t: Throwable) {
            remoteFailure("releaseDisplay", t)
        }
    }

    // ----------------------------------------------------------------- input

    fun tap(x: Int, y: Int): String? = send("tap") { it.tap(x, y) }

    fun longPress(x: Int, y: Int, ms: Int): String? = send("longPress") { it.longPress(x, y, ms) }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int): String? =
        send("swipe") { it.swipe(x1, y1, x2, y2, ms) }

    fun key(keyCode: Int): String? = send("key") { it.key(keyCode) }

    fun text(value: String): String? = send("text") { it.text(value) }

    // ------------------------------------------------------------------ apps

    /** `am force-stop` + `am start --display <clone>`; sets [running]. Returns null on success. */
    fun launch(pkg: String, userId: Int): String? {
        val remote = service ?: return "ShellVd user service unavailable"
        return try {
            val output = remote.launch(pkg, userId).orEmpty()
            if (output.isEmpty()) {
                running.value = pkg
                addLog("launched $pkg (user $userId)")
                null
            } else {
                addLog("launch $pkg failed: $output")
                output
            }
        } catch (t: Throwable) {
            remoteFailure("launch", t)
        }
    }

    /** `am force-stop` (optionally `--user N`). Returns null on success. */
    fun kill(pkg: String, userId: Int): String? {
        val remote = service ?: return "ShellVd user service unavailable"
        return try {
            val output = remote.kill(pkg, userId).orEmpty()
            if (output.isEmpty()) {
                if (running.value == pkg) running.value = null
                addLog("killed $pkg (user $userId)")
                null
            } else {
                addLog("kill $pkg failed: $output")
                output
            }
        } catch (t: Throwable) {
            remoteFailure("kill", t)
        }
    }

    // ------------------------------------------------------- staged installs

    /**
     * Binds the user service only (no display). Blocking; call off the main
     * thread. Returns null once the user service is attached, otherwise a
     * human-readable error string ([bindIfNeeded] result).
     */
    fun ensureService(app: Context): String? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return "ShellVd.ensureService() must be called from a background thread"
        }
        val context = app.applicationContext ?: app
        return bindIfNeeded(context)
    }

    /**
     * Remote staged path for a payload named [remoteName] — the exact path
     * [upload] stages into; build the [pmInstall] argument list from it.
     */
    fun stagePath(remoteName: String): String {
        val name = (remoteName.trim().ifBlank { System.currentTimeMillis().toString() + ".apk" })
            .substringAfterLast('/')
        return "$stageDir/$name"
    }

    /**
     * Uploads a local APK file to /data/local/tmp over binder (chunked), so
     * the shell service writes the bytes itself (no stdin). The stream is
     * closed promptly after upload; the caller owns the file. Returns null on
     * success.
     */
    fun upload(local: java.io.File, remoteName: String): String? {
        val remote = service ?: return "ShellVd user service unavailable"
        val remotePath = stagePath(remoteName)
        return try {
            java.io.FileInputStream(local).use { input ->
                val chunk = ByteArray(uploadChunk)
                var offset = 0L
                while (true) {
                    val read = input.read(chunk)
                    if (read <= 0) break
                    val payload = if (read == chunk.size) chunk else chunk.copyOf(read)
                    val written = remote.stageWrite(remotePath, offset, payload)
                    if (written < 0) {
                        val message = "stageWrite failed at offset $offset ($remotePath)"
                        addLog(message)
                        return message
                    }
                    offset += written
                }
                addLog("staged $remotePath ($offset bytes)")
                null
            }
        } catch (t: Throwable) {
            remoteFailure("upload", t)
        }
    }

    /**
     * Runs one shell `pm install` over binder for the staged paths. Returns
     * null on SUCCESS (the service saw "Success"), otherwise the pm output.
     */
    fun pmInstall(remotePaths: List<String>, userId: Int): String? {
        val remote = service ?: return "ShellVd user service unavailable"
        return try {
            val text = remote.pmInstall(remotePaths.toTypedArray(), userId)
            if (text == null || text.isEmpty()) {
                null
            } else {
                addLog("pm install failed: $text")
                text
            }
        } catch (t: Throwable) {
            remoteFailure("pmInstall", t)
        }
    }

    /** Best-effort deletion of staged payloads; every failure is ignored. */
    fun deleteStage(remotePaths: List<String>) {
        val remote = service
        if (remote == null || remotePaths.isEmpty()) {
            return
        }
        try {
            remote.deleteStage(remotePaths.toTypedArray())
        } catch (t: Throwable) {
            addLog("deleteStage failed: ${t.message ?: t.javaClass.name}")
        }
    }

    // ------------------------------------------------------------------ misc

    /** Shell-side `key=value` snapshot (displayId/width/height/dpi/frames/pumping/logs); "" when unbound. */
    fun info(): String {
        val remote = service ?: return ""
        return try {
            remote.info().orEmpty()
        } catch (t: Throwable) {
            remoteFailure("info", t)
        }
    }

    /** True while the user service is bound. */
    fun isReady(): Boolean = service != null

    /**
     * Tears everything down: stops frames, releases the display and unbinds
     * (removes) the user service so the shell-side process exits. Safe to call
     * repeatedly. The next [ensure] binds a fresh service.
     */
    fun release() {
        val remote = service
        if (remote != null) {
            runCatching { remote.stopFrames() }
            runCatching { remote.releaseDisplay() }
        }
        val args = userServiceArgs
        if (args != null && remote != null) {
            runCatching { Shizuku.unbindUserService(args, connection, true) }
                .onFailure { addLog("unbind failed: ${it.message ?: it.javaClass.simpleName}") }
        }
        synchronized(lock) {
            service = null
            connectLatch = null
        }
        displayId.value = 0
        running.value = null
        frames.value = null
        videoDecoder.release()
        addLog("released")
    }

    // -------------------------------------------------------------- internals

    private fun bindIfNeeded(app: Context): String? {
        val latch = synchronized(lock) {
            if (service != null) return null
            if (!Shizuku.pingBinder()) return "Shizuku is not running"
            val permission = try {
                Shizuku.checkSelfPermission()
            } catch (t: Throwable) {
                return "Shizuku permission check failed: ${t.message ?: t.javaClass.simpleName}"
            }
            if (permission != PackageManager.PERMISSION_GRANTED) {
                return "Shizuku permission not granted"
            }
            val existing = connectLatch
            if (existing != null) {
                existing
            } else {
                val args = userServiceArgs ?: Shizuku.UserServiceArgs(
                    ComponentName(app.packageName, ShellVdService::class.java.name)
                )
                    .daemon(false)
                    .processNameSuffix("shellvd")
                    .version(BuildConfig.VERSION_CODE)
                    .debuggable(false)
                    .also { userServiceArgs = it }
                val created = CountDownLatch(1)
                connectLatch = created
                try {
                    Shizuku.bindUserService(args, connection)
                } catch (t: Throwable) {
                    connectLatch = null
                    return "bindUserService failed: ${t.message ?: t.javaClass.simpleName}"
                }
                created
            }
        }
        val connected = try {
            latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        synchronized(lock) {
            if (service != null) return null
            if (connectLatch === latch) connectLatch = null
        }
        return if (connected) {
            "ShellVd user service failed to attach"
        } else {
            "ShellVd user service start timed out"
        }
    }

    private inline fun send(op: String, block: (IShellVd) -> Boolean): String? {
        val remote = service ?: return "ShellVd user service unavailable"
        return try {
            if (block(remote)) null else "$op rejected by shell service"
        } catch (t: Throwable) {
            remoteFailure(op, t)
        }
    }

    private fun remoteFailure(op: String, t: Throwable): String {
        val name = t.javaClass.name
        addLog("$op failed: ${t.message ?: name}")
        if (t is RemoteException || name.contains("DeadObject")) {
            synchronized(lock) {
                service = null
                connectLatch = null
            }
            displayId.value = 0
        }
        return "$op failed: ${t.message ?: name}"
    }

    /** Display id the service currently owns; -1/0 when none or unbound. Off-main only. */
    fun remoteDisplayId(): Int {
        val remote = service ?: return -1
        return try {
            remote.displayId()
        } catch (t: Throwable) {
            -1
        }
    }

    /** Adopt a service-owned display id (survives app restarts, service keeps it). */
    fun adoptDisplayId(id: Int) {
        if (id > 0) displayId.value = id
    }

    /** Pins the invisible keep-screen-on stub activity on the current display. */
    fun startKeepAliveStub(appId: String) {
        val component = "$appId/com.ivarna.fluxlinux.clone.StubActivity"
        send("keepAlive") { it.startKeepAlive(component, 0); true }?.let { addLog("keepAlive: $it") }
    }

    /**
     * Snapshot of the service-side log (real shell-side errors live there:
     * shell context init, createVirtualDisplay SecurityException, etc.).
     * Returns null when the service is not bound.
     */
    fun serviceLogTail(limit: Int): List<String>? {
        val remote = service ?: return null
        return try {
            remote.logs()?.takeLast(limit.coerceIn(1, 200))
        } catch (t: Throwable) {
            null
        }
    }

    /** Service-side lines (frame pump, placements, rejects) forwarded to the pane. */
    var logSink: ((String) -> Unit)? = null

    private fun addLog(line: String) {
        Log.d(TAG, line)
        synchronized(lock) {
            logs.value = (logs.value + line).takeLast(MAX_LOGS)
        }
        try {
            logSink?.invoke(line)
        } catch (ignored: Throwable) {
        }
    }

    /**
     * Decodes the shell encoder's H.264 access units into the live Bitmap
     * stream: MediaCodec AVC decoder -> ImageReader (YUV_420_888) -> NV21 ->
     * JPEG -> Bitmap. Conversion runs on a dedicated thread; samples arriving
     * while a conversion is in flight are dropped unless they are
     * configuration or keyframes, keeping the oneway callback cheap.
     */
    private class VideoDecoder {
        private val decoderLock = Any()
        private var codec: MediaCodec? = null
        private var reader: ImageReader? = null
        private var imageThread: HandlerThread? = null
        private var convertExecutor: ExecutorService? = null
        private var width = 0
        private var height = 0
        private val converting = java.util.concurrent.atomic.AtomicBoolean(false)

        private var lastAcceptedAt = 0L

        fun feed(data: ByteArray, keyframe: Boolean, format: Boolean, w: Int, h: Int) {
            try {
                val c = ensureCodec(w, h) ?: return
                if (!format) {
                    // Throttle to ~10 conversions/second: full-res YUV->Bitmap
                    // every frame would flood the allocator and OOM the client.
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastAcceptedAt < 90 && (converting.get() || !keyframe)) return
                    lastAcceptedAt = now
                }
                if (converting.get() && !format && !keyframe) return
                val index = c.dequeueInputBuffer(0)
                if (index < 0) return
                val buffer = c.getInputBuffer(index) ?: return
                buffer.clear()
                if (data.size > buffer.capacity()) return
                buffer.put(data)
                var flags = 0
                if (format) flags = flags or MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                if (keyframe) flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                c.queueInputBuffer(index, 0, data.size, System.nanoTime() / 1000, flags)
                drainOutput(c)
            } catch (t: Throwable) {
                Log.w(TAG, "video feed failed", t)
            }
        }

        private fun drainOutput(c: MediaCodec) {
            val info = MediaCodec.BufferInfo()
            for (i in 0 until 8) {
                val index = c.dequeueOutputBuffer(info, 0)
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
                if (index >= 0) c.releaseOutputBuffer(index, true)
                if (index < 0) return
            }
        }

        private fun ensureCodec(w: Int, h: Int): MediaCodec? {
            synchronized(decoderLock) {
                codec?.let { return it }
                if (w <= 0 || h <= 0) return null
                width = w
                height = h
                val thread = HandlerThread("ShellVdDec")
                thread.start()
                imageThread = thread
                val r = ImageReader.newInstance(w, h, ImageFormat.YUV_420_888, 2)
                r.setOnImageAvailableListener({ rdr -> onImage(rdr) }, Handler(thread.looper))
                reader = r
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
                val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                c.configure(format, r.surface, null, 0)
                c.start()
                convertExecutor = Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "ShellVd-yuv").apply { isDaemon = true }
                }
                codec = c
                ShellVd.addLog("decoder started ${w}x$h")
                return c
            }
        }

        private fun onImage(rdr: ImageReader) {
            try {
                val image = try {
                    rdr.acquireLatestImage()
                } catch (t: Throwable) {
                    null
                } ?: return
                if (!converting.compareAndSet(false, true)) {
                    try {
                        image.close()
                    } catch (ignored: Throwable) {
                    }
                    return
                }
                val ex = convertExecutor
                if (ex == null) {
                    converting.set(false)
                    try {
                        image.close()
                    } catch (ignored: Throwable) {
                    }
                    return
                }
                try {
                    ex.execute {
                        try {
                            val jpeg = yuvToJpeg(image, 85)
                            if (jpeg != null) {
                                val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                                if (bitmap != null) {
                                    ShellVd.frames.value = bitmap
                                    ShellVd.framesSeq.value += 1L
                                }
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "frame convert failed", t)
                        } finally {
                            try {
                                image.close()
                            } catch (ignored: Throwable) {
                            }
                            converting.set(false)
                        }
                    }
                } catch (t: Throwable) {
                    // RejectedExecutionException etc.: never let the executor edge
                    // escape the ImageReader callback as an uncaught throwable.
                    converting.set(false)
                    try {
                        image.close()
                    } catch (ignored: Throwable) {
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "onImage failed", t)
            }
        }

        private fun yuvToJpeg(image: Image, quality: Int): ByteArray? {
            val imgW = image.width
            val imgH = image.height
            val planes = image.planes
            val y = planes[0]
            val u = planes[1]
            val v = planes[2]
            val nv21 = ByteArray(imgW * imgH * 3 / 2)
            val yBuffer = y.buffer
            val yRowStride = y.rowStride
            val yPixelStride = y.pixelStride
            var pos = 0
            for (row in 0 until imgH) {
                var col = 0
                while (col < imgW) {
                    nv21[pos++] = yBuffer.get(row * yRowStride + col * yPixelStride)
                    col++
                }
            }
            val uBuffer = u.buffer
            val vBuffer = v.buffer
            val uRowStride = u.rowStride
            val vRowStride = v.rowStride
            val uPixelStride = u.pixelStride
            val vPixelStride = v.pixelStride
            val chromaHeight = imgH / 2
            val chromaWidth = imgW / 2
            for (row in 0 until chromaHeight) {
                var col = 0
                while (col < chromaWidth) {
                    nv21[pos++] = vBuffer.get(row * vRowStride + col * vPixelStride)
                    nv21[pos++] = uBuffer.get(row * uRowStride + col * uPixelStride)
                    col++
                }
            }
            val yuv = YuvImage(nv21, ImageFormat.NV21, imgW, imgH, null)
            val out = java.io.ByteArrayOutputStream()
            if (!yuv.compressToJpeg(Rect(0, 0, imgW, imgH), quality, out)) return null
            return out.toByteArray()
        }

        fun release() {
            synchronized(decoderLock) {
                try {
                    codec?.stop()
                } catch (ignored: Throwable) {
                }
                try {
                    codec?.release()
                } catch (ignored: Throwable) {
                }
                codec = null
                try {
                    reader?.close()
                } catch (ignored: Throwable) {
                }
                reader = null
                convertExecutor?.shutdownNow()
                convertExecutor = null
                imageThread?.quitSafely()
                imageThread = null
            }
        }
    }
}
