package com.ivarna.fluxlinux.clone

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

    // Latest-only JPEG decode queue: the oneway callback must return fast, so
    // frames are decoded on a single background thread and older pending
    // frames are dropped instead of queueing up.
    private val frameLock = Any()
    private var pendingJpeg: ByteArray? = null
    private var decoding = false
    private val frameExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ShellVd-frames").apply { isDaemon = true }
    }

    private val callback: IShellVdCallback = object : IShellVdCallback.Stub() {
        override fun onFrame(jpeg: ByteArray?, width: Int, height: Int) {
            if (jpeg == null || jpeg.isEmpty()) return
            offerJpeg(jpeg)
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
        synchronized(frameLock) {
            pendingJpeg = null
        }
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

    private fun offerJpeg(jpeg: ByteArray) {
        var start = false
        synchronized(frameLock) {
            pendingJpeg = jpeg
            if (!decoding) {
                decoding = true
                start = true
            }
        }
        if (start) {
            try {
                frameExecutor.execute(decodeLoop)
            } catch (t: Throwable) {
                synchronized(frameLock) {
                    decoding = false
                }
            }
        }
    }

    private val decodeLoop = Runnable {
        while (true) {
            val jpeg: ByteArray? = synchronized(frameLock) {
                val next = pendingJpeg
                pendingJpeg = null
                if (next == null) decoding = false
                next
            }
            if (jpeg == null) return@Runnable
            decodeFrame(jpeg)
        }
    }

    private fun decodeFrame(jpeg: ByteArray) {
        val bitmap = try {
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        } catch (t: Throwable) {
            Log.w(TAG, "frame decode failed", t)
            null
        }
        if (bitmap != null) {
            frames.value = bitmap
            framesSeq.value += 1L
        }
    }
}
