package com.altaris.linux.core.system

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.altaris.linux.core.utils.RootUtils
import kotlinx.coroutines.flow.MutableStateFlow
import rikka.shizuku.Shizuku

/**
 * Android 12+ kills an app's background child processes (Phantom Process Killer, exit 137).
 * Detect it, and disable it via root, Shizuku (shell uid) or adb from a PC.
 */
object PhantomProcessFixer {

    enum class State { ENABLED, DISABLED, UNKNOWN }

    private const val KEY = "settings_enable_monitor_phantom_procs"
    private const val PREFS = "altaris_state"
    private const val PREF_HIDE = "phantom_warning_hidden"
    const val SHIZUKU_REQUEST_CODE = 4141

    /** Same commands for root, Shizuku and adb (adb gets an `adb shell` prefix). */
    val shellCommands = listOf(
        "/system/bin/device_config set_sync_disabled_for_tests persistent",
        "/system/bin/device_config put activity_manager max_phantom_processes 2147483647",
        "settings put global $KEY false"
    )

    val adbCommands: String get() = shellCommands.joinToString("\n") { "adb shell \"$it\"" }

    /** True when the OS has the phantom process monitor (Android 12+). */
    fun applicable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /**
     * Readable without root: only the Settings.Global flag (Android 12L+).
     * device_config needs shell, so Android 12 or an unset flag is UNKNOWN.
     */
    fun state(ctx: Context): State =
        when (Settings.Global.getString(ctx.contentResolver, KEY)) {
            "false", "0" -> State.DISABLED
            "true", "1" -> State.ENABLED
            else -> State.UNKNOWN
        }

    // ---- warning prompt ------------------------------------------------------------

    /** MainActivity shows the dialog while this is true. */
    val promptVisible = MutableStateFlow(false)
    @Volatile private var promptedThisRun = false

    fun warningHidden(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_HIDE, false)

    fun hideWarningForever(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_HIDE, true).apply()
    }

    /** Call when a terminal or desktop session starts. Shows the dialog once per app run. */
    fun maybePrompt(ctx: Context) {
        if (!applicable() || promptedThisRun || warningHidden(ctx)) return
        if (state(ctx) == State.DISABLED) return
        promptedThisRun = true
        promptVisible.value = true
    }

    // ---- root ----------------------------------------------------------------------

    fun rootAvailable(): Boolean = RootUtils.isRootAvailable()

    /** Blocking; run off the main thread. Returns null on success, else an error text. */
    fun applyRoot(): String? {
        for (c in shellCommands) {
            val r = RootUtils.runRootCommand(c)
            if (!r.isSuccess) return r.error.ifBlank { "root command failed" }
        }
        return null
    }

    // ---- Shizuku -------------------------------------------------------------------

    fun shizukuRunning(): Boolean = ShizukuShell.running()

    fun shizukuGranted(): Boolean = ShizukuShell.granted()

    /** Asks the Shizuku manager for permission; result arrives on [onResult] (main thread). */
    fun requestShizuku(onResult: (Boolean) -> Unit) {
        val l = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != SHIZUKU_REQUEST_CODE) return
                Shizuku.removeRequestPermissionResultListener(this)
                onResult(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        Shizuku.addRequestPermissionResultListener(l)
        Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
    }

    /**
     * Blocking; run off the main thread. Null on success, else an error text.
     * ponytail: Shizuku.newProcess is private in API 13, so reflection instead of an AIDL UserService;
     * if a future Shizuku drops it, switch to a UserService (keep rule in proguard-rules.pro).
     */
    fun applyShizuku(): String? = try {
        for (c in shellCommands) {
            val result = ShizukuShell.run(c)
            if (!result.ok) {
                return result.error.ifBlank { "command failed: $c" }
            }
        }
        null
    } catch (e: Exception) {
        (e.cause ?: e).message ?: "Shizuku failed"
    }
}

/**
 * Thin wrapper around the Shizuku server process API.
 *
 * Commands run as the Shizuku server uid (shell 2000 for an adb-started
 * server, root 0 for a su-started one). That uid may write secure settings,
 * which is what the accessibility-service auto-enable needs.
 *
 * ponytail: Shizuku.newProcess is private in API 13, so it is called by
 * reflection (keep rule in proguard-rules.pro); if a future Shizuku drops
 * it, switch to a UserService.
 */
object ShizukuShell {

    /** Result of one shell command. */
    data class Outcome(val exitCode: Int, val stdout: String, val stderr: String) {
        /** True when the command exited with 0. */
        val ok: Boolean get() = exitCode == 0

        /** Error text of a failed command (stderr first, stdout as fallback). */
        val error: String get() = stderr.ifBlank { stdout }
    }

    /** True when the Shizuku server is up (the binder answers). */
    fun running(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** True when the user granted this app the Shizuku permission. */
    fun granted(): Boolean =
        runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)

    /** True when the server is up *and* the permission was granted. */
    fun available(): Boolean = running() && granted()

    /**
     * Uid of the Shizuku server process, or -1 when it cannot be read.
     * Shell (2000) and root (0) may write secure settings.
     */
    fun uid(): Int = runCatching { Shizuku.getUid() }.getOrDefault(-1)

    /**
     * Runs [command] through `sh -c` on the Shizuku server. Blocking; run
     * off the main thread. Never throws — a failed run reports a non-zero
     * exit code.
     */
    fun run(command: String, timeoutMs: Long = 10_000L): Outcome {
        val method = newProcessMethod
            ?: return Outcome(-1, "", "Shizuku.newProcess is unavailable")
        val process = try {
            method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
        } catch (e: Exception) {
            val cause = e.cause ?: e
            return Outcome(-1, "", cause.message ?: "Shizuku failed")
        }
        return collect(process, command, timeoutMs)
    }

    /** `Shizuku.newProcess` is private in API 13 — reach it by reflection. */
    private val newProcessMethod: java.lang.reflect.Method? by lazy {
        runCatching {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
            ).apply { isAccessible = true }
        }.getOrNull()
    }

    /** Reads both pipes without dead-locking and waits for the exit code. */
    private fun collect(process: Process, command: String, timeoutMs: Long): Outcome {
        runCatching { process.outputStream.close() }
        var stdout = ""
        var stderr = ""
        val outReader = Thread({ stdout = readAll(process.inputStream) }, "shizuku-out")
        val errReader = Thread({ stderr = readAll(process.errorStream) }, "shizuku-err")
        outReader.isDaemon = true
        errReader.isDaemon = true
        outReader.start()
        errReader.start()
        var exit = -1
        val waiter = Thread({
            exit = try {
                process.waitFor()
            } catch (e: Exception) {
                -1
            }
        }, "shizuku-wait")
        waiter.isDaemon = true
        waiter.start()
        waiter.join(timeoutMs)
        if (waiter.isAlive) {
            runCatching { process.destroyForcibly() }
            return Outcome(
                -1,
                stdout,
                stderr.ifBlank { "command timed out after ${timeoutMs}ms: $command" },
            )
        }
        outReader.join(500L)
        errReader.join(500L)
        return Outcome(exit, stdout.trim(), stderr.trim())
    }

    private fun readAll(stream: java.io.InputStream): String = try {
        stream.bufferedReader().readText()
    } catch (_: Exception) {
        ""
    }
}
