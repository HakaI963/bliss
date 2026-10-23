package com.altaris.linux.core.a11y

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.altaris.linux.core.root.RootShell
import com.altaris.linux.core.system.ShizukuShell
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Auto-enable for the Altaris accessibility service.
 *
 * Android only binds an accessibility service that is listed in the
 * `enabled_accessibility_services` secure setting, and only a privileged uid
 * may write that setting. Three outcomes are possible, tried in this order:
 *
 *  1. **Shizuku** — the Shizuku server runs as shell (uid 2000) or root
 *     (uid 0) and may write secure settings. This is the reliable path on
 *     devices where the app itself has no superuser grant (KernelSU).
 *  2. **Root** — the classic `su` path (KernelSU / Magisk).
 *  3. **Manual** — nothing is written. The user gets a one-time, non-blocking
 *     hint to enable the service in the Android settings; the app keeps
 *     working without it.
 *
 * Everything here is idempotent: the current setting is read first and only
 * written when the component is missing, so a repeated run changes nothing.
 * No function throws — a failure is reported as an [Outcome].
 */
object A11yAutoEnable {

    private const val TAG = "AltarisA11yEnable"

    /** Secure settings owned by the platform. */
    private const val SETTING_SERVICES = "enabled_accessibility_services"
    private const val SETTING_ENABLED = "accessibility_enabled"

    private const val PREFS = "altaris_a11y"
    private const val PREF_MANUAL_HINT = "manual_hint_shown"

    /** Hint shown when the user has to enable the service by hand. */
    const val MANUAL_HINT =
        "Altaris needs its accessibility service to control cloned apps. " +
            "Enable it in Settings → Accessibility → Altaris."

    /** Error text used when no privileged path was available. */
    const val MANUAL_ERROR =
        "neither Shizuku nor root is available — enable the accessibility service in Android settings"

    /** How the service was enabled (or why it was not). */
    enum class Method { SHIZUKU, ROOT, MANUAL }

    /** Result of one enable attempt. */
    data class Outcome(
        val ok: Boolean,
        val method: Method,
        val component: String,
        val enabledServices: String = "",
        val accessibilityEnabled: Boolean = false,
        val error: String? = null,
        val hint: String? = null,
    )

    /**
     * Non-blocking hint for the UI: set while the service still has to be
     * enabled by hand, cleared as soon as the service runs.
     */
    val manualHint = MutableStateFlow<String?>(null)

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "altaris-a11y-enable").apply { isDaemon = true }
    }
    private val inFlight = AtomicBoolean(false)

    @Volatile
    private var watchingShizuku = false

    @Volatile
    private var binderListener: Shizuku.OnBinderReceivedListener? = null

    @Volatile
    private var permissionListener: Shizuku.OnRequestPermissionResultListener? = null

    // Lazy so JVM unit tests never touch Looper unless a callback path runs.
    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    // ---------------------------------------------------------------- API

    /**
     * Component string of the accessibility service for the running app.
     *
     * The package part is the *application id* of the installed flavour
     * (`com.nopiece.bliss` for the Altaris flavour, `com.ivarna.fluxlinux`
     * for F-Droid), the class part is the stable source class name — so the
     * string stays correct when the application id changes.
     */
    fun componentFor(context: Context): String =
        ComponentName(context.packageName, AltarisAccessibilityService.CLASS_NAME)
            .flattenToShortString()

    /**
     * Enable the accessibility service. Blocking — call from a worker thread.
     *
     * Tries Shizuku, then root, and finally reports that the user has to
     * enable the service by hand. Never throws.
     */
    fun ensureEnabled(context: Context): Outcome {
        val component = componentFor(context)
        return try {
            if (!serviceDeclared(context)) {
                // The manifest does not carry the service — writing a
                // component string the platform does not know is worse than
                // doing nothing at all.
                return Outcome(
                    ok = false,
                    method = Method.MANUAL,
                    component = component,
                    error = "the accessibility service is not declared in the manifest",
                )
            }
            // Shizuku first: it does not need a superuser grant for the app.
            val shizuku = enableViaShizuku(component, context)
            if (shizuku != null) return shizuku
            val root = enableViaRoot(component, context)
            if (root != null) return root
            Outcome(
                ok = false,
                method = Method.MANUAL,
                component = component,
                error = MANUAL_ERROR,
                hint = MANUAL_HINT,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "ensureEnabled failed", t)
            Outcome(
                ok = false,
                method = Method.MANUAL,
                component = component,
                error = t.message ?: t.javaClass.simpleName,
                hint = MANUAL_HINT,
            )
        }
    }

    /**
     * Enable the service in the background.
     *
     * [onResult] is called on the main thread. A call while another one is
     * still running is ignored, so this is safe to fire from anywhere.
     */
    fun autoEnable(context: Context, onResult: ((Outcome) -> Unit)? = null) {
        val app = context.applicationContext
        if (!inFlight.compareAndSet(false, true)) return
        executor.execute {
            val outcome = try {
                ensureEnabled(app)
            } catch (t: Throwable) {
                Log.w(TAG, "autoEnable failed", t)
                Outcome(
                    ok = false,
                    method = Method.MANUAL,
                    component = componentFor(app),
                    error = t.message ?: t.javaClass.simpleName,
                    hint = MANUAL_HINT,
                )
            }
            inFlight.set(false)
            when {
                outcome.ok -> manualHint.value = null
                // The user may have enabled the service by hand already.
                serviceRunning(app) -> manualHint.value = null
                outcome.hint != null -> {
                    manualHint.value = outcome.hint
                    showHintOnce(app, outcome.hint)
                }
            }
            // Shizuku may still be starting up — try again when it shows up.
            if (!outcome.ok && !ShizukuShell.running()) watchForShizuku(app)
            onResult?.let { callback -> mainHandler.post { callback(outcome) } }
        }
    }

    /**
     * True when the platform already bound the accessibility service.
     *
     * The service lives in the `:a11y` process, so the app asks the bridge
     * provider whether the instance is there.
     */
    fun serviceRunning(context: Context): Boolean {
        val raw = runCatching { A11yBridge.status(context) }.getOrDefault("{}")
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return false
        return obj.optBoolean("serviceRunning", false)
    }

    // ---------------------------------------------------------------- internals

    /** True when the manifest really declares the service we are about to write. */
    private fun serviceDeclared(context: Context): Boolean = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getServiceInfo(
            ComponentName(context.packageName, AltarisAccessibilityService.CLASS_NAME),
            0,
        )
        true
    }.getOrDefault(false)

    /** Write the secure settings through the Shizuku server. Null when unusable. */
    private fun enableViaShizuku(component: String, context: Context): Outcome? {
        if (!ShizukuShell.available()) return null
        val uid = ShizukuShell.uid()
        // Only shell (2000) and root (0) may write secure settings; an app
        // uid cannot, so there is no point in trying.
        if (uid != -1 &&
            uid != android.os.Process.SHELL_UID &&
            uid != android.os.Process.ROOT_UID
        ) {
            Log.w(TAG, "Shizuku runs as uid $uid — secure settings are out of reach")
            return null
        }
        val shell = object : SecureSettingsShell {
            override fun get(key: String): String? {
                val result = ShizukuShell.run("settings get secure $key")
                if (!result.ok) return null
                return lastLine(result.stdout)
            }

            override fun put(key: String, value: String): Boolean =
                ShizukuShell.run("settings put secure $key ${quote(value)}").ok
        }
        return applySettings(shell, component, Method.SHIZUKU, context)
    }

    /** Write the secure settings as root. Null when root is unusable. */
    private fun enableViaRoot(component: String, context: Context): Outcome? {
        if (!RootShell.isRootAvailable()) return null
        val shell = object : SecureSettingsShell {
            override fun get(key: String): String? {
                val result = RootShell.captureResult("settings get secure $key", 8_000L)
                if (result.exitCode != 0) return null
                return lastLine(result.stdout)
            }

            override fun put(key: String, value: String): Boolean =
                RootShell.captureResult(
                    "settings put secure $key ${quote(value)}",
                    8_000L,
                ).exitCode == 0
        }
        return applySettings(shell, component, Method.ROOT, context)
    }

    /**
     * Merge [component] into the enabled-services list and switch the
     * accessibility setting on. Null when the shell could not do it.
     */
    private fun applySettings(
        shell: SecureSettingsShell,
        component: String,
        method: Method,
        context: Context,
    ): Outcome? {
        val current = shell.get(SETTING_SERVICES) ?: return null
        val original = current.split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "null" }
        val list = original.toMutableList()
        val alreadyListed = list.contains(component)
        if (!alreadyListed) {
            list.add(component)
            if (!shell.put(SETTING_SERVICES, list.joinToString(":"))) return null
        }
        if (shell.get(SETTING_ENABLED) != "1") {
            if (!shell.put(SETTING_ENABLED, "1")) return null
        }
        // The platform may veto the write — read it back before claiming success.
        var after = shell.get(SETTING_SERVICES) ?: return null
        if (!after.split(':').any { it.trim() == component }) return null

        // An app update kills the bound service, and Android then keeps it in
        // "Crashed services" — re-writing the same setting does not rebind it.
        // Toggle the component once so the platform starts it again.
        if (!serviceRunning(context)) {
            val without = original.filter { it != component }
            shell.put(SETTING_SERVICES, without.joinToString(":"))
            Thread.sleep(180)
            shell.put(SETTING_SERVICES, list.joinToString(":"))
            Thread.sleep(450)
            after = shell.get(SETTING_SERVICES) ?: after
        }

        return Outcome(
            ok = true,
            method = method,
            component = component,
            enabledServices = after,
            accessibilityEnabled = shell.get(SETTING_ENABLED) == "1",
        )
    }

    /** Last non-empty line of a shell reply. */
    private fun lastLine(text: String): String =
        text.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty()

    /** Single-quote a value for the shell. */
    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** One toast per install — the hint must not nag. */
    private fun showHintOnce(context: Context, hint: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREF_MANUAL_HINT, false)) return
        prefs.edit().putBoolean(PREF_MANUAL_HINT, true).apply()
        mainHandler.post {
            runCatching { Toast.makeText(context, hint, Toast.LENGTH_LONG).show() }
                .onFailure { Log.w(TAG, "hint toast failed", it) }
        }
    }

    /** Retry the auto-enable once Shizuku is up *and* authorised. */
    private fun watchForShizuku(context: Context) {
        if (watchingShizuku) return
        // Nothing to wait for when the shell is already usable.
        if (ShizukuShell.running() && ShizukuShell.granted()) return
        watchingShizuku = true
        val binder = object : Shizuku.OnBinderReceivedListener {
            override fun onBinderReceived() {
                stopWatchingShizuku()
                autoEnable(context)
            }
        }
        val permission = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                stopWatchingShizuku()
                autoEnable(context)
            }
        }
        binderListener = binder
        permissionListener = permission
        val binderOk = runCatching {
            Shizuku.addBinderReceivedListenerSticky(binder)
        }.isSuccess
        val permissionOk = runCatching {
            Shizuku.addRequestPermissionResultListener(permission)
        }.isSuccess
        if (!binderOk && !permissionOk) stopWatchingShizuku()
    }

    private fun stopWatchingShizuku() {
        binderListener?.let { runCatching { Shizuku.removeBinderReceivedListener(it) } }
        permissionListener?.let {
            runCatching { Shizuku.removeRequestPermissionResultListener(it) }
        }
        binderListener = null
        permissionListener = null
        watchingShizuku = false
    }

    /** A privileged shell that can read and write secure settings. */
    private interface SecureSettingsShell {
        /** Current value, or null when the read failed. */
        fun get(key: String): String?

        /** Write a value; true when the platform accepted it. */
        fun put(key: String, value: String): Boolean
    }
}
