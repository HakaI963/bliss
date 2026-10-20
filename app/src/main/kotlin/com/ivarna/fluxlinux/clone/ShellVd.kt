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
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import rikka.shizuku.Shizuku

/**
 * App-side client for the shell-side display owner ([ShellVdService], Shizuku
 * UserService, uid 2000).
 *
 * The virtual display, its capture path and the input injection are
 * ShadowAuto's implementation running inside that shell process (see
 * [ShadowDisplay], [ShadowInput]); this object is the binder half: it
 * creates the display, receives the still frames and forwards input.
 *
 * Threading contract:
 *  - [ensureService] MUST be called from a background thread: it blocks up to
 *    [BIND_TIMEOUT_MS] waiting for the user service, and Shizuku delivers
 *    `ServiceConnection` callbacks on the main looper, so calling it from the
 *    main thread would deadlock, not connect.
 *  - Everything else is safe from any thread; state flows are updated from
 *    background threads and are safe to collect from Compose.
 */
object ShellVd {

    private const val TAG = "ShellVd"
    private const val BIND_TIMEOUT_MS = 10_000L
    private const val MAX_LOGS = 200

    /** Latest frame of the clone display (null before the first frame). */
    val frames = MutableStateFlow<Bitmap?>(null)

    /** Display id owned by the shell service; 0 means "no display". */
    val displayId = MutableStateFlow(0)

    /** Recent client + service log lines (newest last, capped at [MAX_LOGS]). */
    val logs = MutableStateFlow<List<String>>(emptyList())

    /** Package most recently launched on the clone display, null = none. */
    val running = MutableStateFlow<String?>(null)

    /** Monotonic counter incremented for every received frame. */
    val framesSeq = MutableStateFlow(0L)

    private val lock = Any()
    @Volatile private var service: IShellVd? = null
    private var userServiceArgs: Shizuku.UserServiceArgs? = null
    private var connectLatch: CountDownLatch? = null

    // Staged installs (pm over binder; no stdin piping — the streamed `pm
    // install -S` transport was unreliable on-device).
    private var stageDir = "/data/local/tmp/bliss-stage"

    private val callback: IShellVdCallback = object : IShellVdCallback.Stub() {
        override fun onScreenshot(jpeg: ByteArray?, width: Int, height: Int) {
            if (jpeg == null || jpeg.isEmpty()) return
            try {
                val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
                frames.value = bitmap
                framesSeq.value += 1L
            } catch (t: Throwable) {
                Log.w(TAG, "frame decode failed", t)
            }
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
            if (remote != null) {
                try {
                    remote.attach(callback)
                } catch (ignored: Throwable) {
                }
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
            addLog("service disconnected")
        }
    }

    // ------------------------------------------------------------- lifecycle

    /**
     * Binds the user service only. Blocking; call off the main thread.
     * Returns null once the user service is attached, otherwise a
     * human-readable error string.
     */
    fun ensureService(app: Context): String? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return "ShellVd.ensureService() must be called from a background thread"
        }
        val context = app.applicationContext ?: app
        return bindIfNeeded(context)
    }

    /** Releases the display and drops the binder connection. */
    fun release() {
        try {
            service?.stopFrames()
            service?.releaseDisplay()
        } catch (ignored: Throwable) {
        }
        val args = userServiceArgs
        if (args != null) {
            try {
                Shizuku.unbindUserService(args, connection, true)
            } catch (ignored: Throwable) {
            }
        }
        synchronized(lock) {
            service = null
            connectLatch = null
        }
        running.value = null
        frames.value = null
        displayId.value = 0
        addLog("released")
    }

    // ---------------------------------------------------------------- display

    /**
     * Creates (or reuses) the clone display at `width x height @ dpi`.
     * Blocking; call off the main thread. Returns null on success, otherwise a
     * human-readable error string.
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
                val base = "createDisplay failed (id=$id)"
                addLog(base)
                // The shell service logs the real exception (context, flags,
                // SecurityException) right before returning -1; surface it.
                val reason = serviceLogTail(12)
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() && !it.contains(base) }
                    ?.takeLast(3)
                    ?.joinToString(" · ")
                val message = if (reason.isNullOrBlank()) base else "$base — $reason"
                addLog(message)
                return message
            }
            displayId.value = id
            addLog("display $id created (${width}x$height dpi=$dpi)")
            null
        } catch (t: Throwable) {
            remoteFailure("createDisplay", t)
        }
    }

    /** Display id the service owns; -1 when unbound. */
    fun remoteDisplayId(): Int {
        val remote = service ?: return -1
        return try {
            remote.displayId()
        } catch (t: Throwable) {
            -1
        }
    }

    /** Starts the still-frame stream. */
    fun startFrames(maxWidth: Int = 540, quality: Int = 80, fps: Int = 3): String? {
        val remote = service ?: return "ShellVd user service unavailable"
        return try {
            remote.startFrames(callback, maxWidth, quality, fps)
            null
        } catch (t: Throwable) {
            remoteFailure("startFrames", t)
        }
    }

    fun stopFrames(): String? {
        val remote = service ?: return "ShellVd user service unavailable"
        return try {
            remote.stopFrames()
            null
        } catch (t: Throwable) {
            remoteFailure("stopFrames", t)
        }
    }

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

    /**
     * Fresh JPEG of the clone display for AI observations. Blocking; call off
     * the main thread. Null when there is no display or no frame yet.
     */
    fun screenshot(maxWidth: Int, quality: Int): ByteArray? {
        val remote = service ?: return null
        return try {
            remote.screenshot(maxWidth, quality)
        } catch (t: Throwable) {
            remoteFailure("screenshot", t)
            null
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

    /** Launches a package on the clone display. Returns null on success. */
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

    /** Writes bytes into a shell-readable path. Returns the new offset, or -1. */
    fun stageWrite(path: String, offset: Long, data: ByteArray): Long {
        val remote = service ?: return -1L
        return try {
            remote.stageWrite(path, offset, data)
        } catch (t: Throwable) {
            remoteFailure("stageWrite", t)
            -1L
        }
    }

    // ------------------------------------------------------------------ apps

    /** `am force-stop` (optionally `--user N`). Returns null on success. */
    fun kill(pkg: String, userId: Int): String? {
        val remote = service ?: return "ShellVd user service unavailable"
        return try {
            val error = remote.kill(pkg, userId).orEmpty()
            if (error.isEmpty()) {
                if (running.value == pkg) running.value = null
                addLog("killed $pkg (user $userId)")
                null
            } else {
                addLog("kill $pkg failed: $error")
                error
            }
        } catch (t: Throwable) {
            remoteFailure("kill", t)
        }
    }

    // ------------------------------------------------------- staged installs

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
     * the shell service writes the bytes itself (no stdin). Returns null on
     * success.
     */
    fun upload(local: java.io.File, remoteName: String): String? {
        val remote = service ?: return "ShellVd user service unavailable"
        val remotePath = stagePath(remoteName)
        return try {
            java.io.FileInputStream(local).use { input ->
                val chunk = ByteArray(256 * 1024)
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
            if (text.isNullOrEmpty()) {
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

    /**
     * Snapshot of the service-side log (real shell-side errors live there:
     * engine start, staging failures, …). Returns null when not bound.
     */
    fun serviceLogTail(limit: Int): List<String>? {
        val remote = service ?: return null
        return try {
            remote.logs()?.takeLast(limit.coerceIn(1, 200))
        } catch (t: Throwable) {
            null
        }
    }

    /** Service-side lines forwarded to the pane. */
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
        }
        return "$op failed: ${t.message ?: name}"
    }
}
