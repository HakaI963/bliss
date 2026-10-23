package com.altaris.linux

import android.Manifest
import com.altaris.linux.core.gpu.DriverRelease
import com.altaris.linux.core.gpu.GpuDriverInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberPermissionState
import com.altaris.linux.ui.components.BottomTab
import com.altaris.linux.ui.components.AltarisCommandBar
import com.altaris.linux.ui.components.AltarisDock
import com.altaris.linux.ui.components.CommandPaletteOverlay
import com.altaris.linux.ui.components.DockDestination
import com.altaris.linux.ui.components.PaletteAction
import com.altaris.linux.ui.components.GlassScaffold
import com.altaris.linux.ui.components.InstallTaskBar
import com.altaris.linux.ui.theme.AltarisTheme
import com.altaris.linux.core.utils.StateManager
import com.altaris.linux.core.utils.ThemeMode
import com.altaris.linux.core.utils.ThemePreferences
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeChild
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi

// Screen navigation enum
enum class Screen {
    ONBOARDING,
    PREREQUISITES,
    HOME,
    SETTINGS,
    SETTINGS_TERMINAL,
    SETTINGS_X11,
    SETTINGS_AUDIO,
    SETTINGS_CHROOT,
    SETTINGS_CHROOT_DETAIL,
    SETTINGS_PROOT,
    SETTINGS_PROOT_DETAIL,
    SETTINGS_LEGACY_TERMUX,
    TROUBLESHOOTING,
    ROOT_ACCESS,
    CRASH_LOGS,
    INSTALL_WIZARD,
    DISTRO_SETTINGS,
    TERMINAL
}

class MainActivity : ComponentActivity() {

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleScriptCallback(intent)
        if (intent.getStringExtra("EXTRA_TARGET_PAGE") == "terminal") {
            // FGS notification tap → jump to in-app terminal (bottom-nav tab)
            setIntent(intent)
            lifecycleScope.launch { }
            requestScreenFromIntent(Screen.HOME)
            requestTabFromIntent(BottomTab.TERMINAL)
        } else if (intent.getStringExtra("EXTRA_TARGET_PAGE") == "home" || intent.getStringExtra("target_page") == "home") {
            setIntent(intent)
            requestScreenFromIntent(Screen.HOME)
            requestTabFromIntent(BottomTab.HOME)
        }
    }

    /**
     * External intent requests (FGS notification tap, deep links) queue here and
     * are drained once by the composition. One-shot request channels keep the
     * write direction single: the request writes the queue, the composition
     * reads it and updates the real snapshot state. Nothing mirrors state back
     * out, so no recomposition loop can form.
     */
    private val pendingTabRequest = kotlinx.coroutines.flow.MutableSharedFlow<BottomTab>(replay = 1)
    private val pendingScreenRequest = kotlinx.coroutines.flow.MutableSharedFlow<Screen>(replay = 1)

    /** Queue a tab switch from outside Compose. */
    private fun requestTabFromIntent(tab: BottomTab) {
        pendingTabRequest.tryEmit(tab)
    }

    /** Queue a screen switch from outside Compose. */
    private fun requestScreenFromIntent(screen: Screen) {
        pendingScreenRequest.tryEmit(screen)
    }

    private fun handleScriptCallback(intent: android.content.Intent) {
        android.util.Log.d("Altaris", "handleScriptCallback called with action: ${intent.action}, data: ${intent.data}")
        // Handle Deep Link: altaris://callback?result=success&name=setup_termux
        if (intent.action == android.content.Intent.ACTION_VIEW && intent.data?.scheme == "altaris") {
            val uri = intent.data
            val result = uri?.getQueryParameter("result")
            val scriptName = uri?.getQueryParameter("name") ?: "unknown"
            val installedComponents = uri?.getQueryParameter("components") // Legacy
            
            android.util.Log.d("Altaris", "Deep Link received: result=$result, scriptName=$scriptName")
            
            if (scriptName.startsWith("legacy_termux_")) {
                com.altaris.linux.core.legacy.LegacyTermuxCallbacks.handle(this, result, scriptName, uri)
                consumeCallbackIntent()
                return
            }

            if (result == "success") {
                 // Check Queue first
                 val queueManager = com.altaris.linux.core.utils.InstallationQueueManager
                 val currentTask = queueManager.currentTask
                 
                 // If the callback matches the current task, proceed queue.
                 // Strict match: the script's callback name must equal the current task's id.
                 // The previous `|| scriptName == "base_install"` fallback was redundant
                 // (base install tasks have id="base_install") AND allowed the base-install
                 // callback to be attributed to a stale/different task (e.g. a debian
                 // component task left over from a previous screen), which would set state
                 // for the wrong distro.
                 if (currentTask != null && scriptName == currentTask.id) {
                     android.widget.Toast.makeText(this, "Task '${currentTask.name}' Complete. Proceeding...", android.widget.Toast.LENGTH_SHORT).show()
                     
                     if (currentTask.type == com.altaris.linux.core.utils.TaskType.BASE_INSTALL) {
                         // Mark Distro Installed on Base Success
                         // We assume we know the distro ID from state or we just mark currently selected?
                         // Ideally we pass distro ID in callback, or use 'selectedDistro' variable if available?
                         // MainActivity is recreated? No, usually singleTop/SingleTask. 'selectedDistro' state might receive it.
                         // But for safety, we rely on 'distro_install_ID' naming convention if possible, but our base script has generic name.
                         // Let's rely on 'base_install' result means 'selectedDistro' (which should be saved/restored if activity died).
                         // Actually, 'selectedDistro' is inside setContent scope.
                         // We need a class-level property or StateManager access.
                         // For now, let's assume 'selectedDistro' is unavailable here and we rely on user manually refreshing or existing state?
                         // NO, we MUST update StateManager.
                         // FIX: Let's extract Distro ID from the Task if possible.
                         // But Task struct doesn't have distroId yet.
                         // We'll trust the processNextInstallTask logic to handle the flow.
                         // Marking distro installed:
                         // We can iterate Distros and match ID? Or just skip marking if ID not known?
                         // The Base Script DOES NOT include ID in "name=base_install". 
                         // However, InstallQueueManager is singleton. We can store context there?
                         // Let's just process queue. The user will see "Installed" in UI eventually.
                     }
                     
                     // Mark Component as Installed in StateManager
                     val distroId = currentTask.distroId
                     if (currentTask.type == com.altaris.linux.core.utils.TaskType.COMPONENT) {
                         if (currentTask.isUninstall) {
                             // Uninstall completion: clear the "installed" flag
                             StateManager.setComponentInstalled(this, distroId, currentTask.id, false)
                             android.widget.Toast.makeText(this, "${currentTask.name.replace("Uninstall ", "")} Uninstalled 🗑️", android.widget.Toast.LENGTH_LONG).show()
                         } else {
                             StateManager.setComponentInstalled(this, distroId, currentTask.id, true)
                         }
                     }
                     // Force State Update
                     StateManager.triggerRefresh()
                 } else {
                     // Legacy / Standalone handling
                     if (scriptName.startsWith("distro_install_")) {
                         val distroId = scriptName.removePrefix("distro_install_")
                         StateManager.setDistroInstalled(this, distroId, true)
                         android.widget.Toast.makeText(this, "$distroId Installed! ✅", android.widget.Toast.LENGTH_LONG).show()
                     } else if (scriptName.startsWith("distro_uninstall_")) {
                         val distroId = scriptName.removePrefix("distro_uninstall_")
                         com.altaris.linux.core.terminal.TerminalLauncher
                             .refreshInstalledAfterUninstall(this, distroId)
                         android.widget.Toast.makeText(this, "$distroId Uninstalled! 🗑️", android.widget.Toast.LENGTH_LONG).show()
                     } else {
                         // Generic Script
                         StateManager.setScriptStatus(this, scriptName, true)
                         android.widget.Toast.makeText(this, "Script '$scriptName' details saved.", android.widget.Toast.LENGTH_SHORT).show()
                     }
                 }
                 
                 // Process Next
                 processNextInstallTask()
                 
            } else {
                 // Component failed
                 android.widget.Toast.makeText(this, "Task '$scriptName' failed! ❌", android.widget.Toast.LENGTH_LONG).show()
                 com.altaris.linux.core.utils.InstallationQueueManager.clear() // Stop queue on failure
            }
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not — FGS still works, but FGS notification visibility
           improves on Android 13+ when POST_NOTIFICATIONS is granted. */ }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED) return
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Component chain after a successful base install (M1): runs the wizard's
     * selected components sequentially in the parent distro's terminal component.
     * Each step only advances after the previous session exits 0 (B3 gate).
     */
    private fun runComponentChain(
        distro: com.altaris.linux.core.data.Distro,
        components: List<com.altaris.linux.core.data.DistroComponent>,
        theme: String,
        gpu: String
    ) {
        if (components.isEmpty()) return
        runComponentStep(distro, components, 0, theme, gpu)
    }

    private fun isCustomizationComponent(component: com.altaris.linux.core.data.DistroComponent): Boolean =
        component.id == "customization" || component.id == "kde_customization" ||
            component.scriptName.contains("setup_customization")

    /**
     * Load a component script and prepend altaris_gpu_common.sh for hw_accel.
     * [extraEnv] is already resolved (no ALTARIS_GPU=auto).
     */
    private fun loadGuestComponentScript(
        ctx: android.content.Context,
        component: com.altaris.linux.core.data.DistroComponent,
        extraEnv: Map<String, String>
    ): String? {
        val scriptManager = com.altaris.linux.core.data.ScriptManager(ctx)
        val envBlock = extraEnv.entries.joinToString("\n") {
            "export ${it.key}=\"${it.value}\""
        }
        val base = scriptManager.getScriptContent(component.scriptName)
        val common = if (component.id == "hw_accel") {
            runCatching {
                scriptManager.getScriptContent("common/setup/altaris_gpu_common.sh")
            }.getOrDefault("")
        } else {
            ""
        }
        return buildString {
            if (extraEnv.isNotEmpty()) append(envBlock).append("\n\n")
            if (common.isNotBlank()) append(common).append("\n\n")
            append(base)
        }
    }

    /**
     * Host-stage theme/icons + Oh My Zsh into the proot rootfs before guest
     * customization runs. Distro Settings used to skip this (only onboarding
     * did), so guest hung on missing git / corrupt OMZ rm. Merges skip flags
     * into [extraEnv]. Safe to call off the main thread.
     *
     * [distroId] selects the proot container (`debian`, `alpine`, …). Defaults
     * to debian only when the id is unknown — never stage Alpine assets into
     * the Debian tree (or vice versa).
     */
    private fun stageCustomizationHostEnv(
        activity: android.content.Context,
        extraEnv: Map<String, String>,
        distroId: String = "debian"
    ): Map<String, String> {
        val theme = extraEnv["ALTARIS_THEME"] ?: "dark"
        val merged = extraEnv.toMutableMap()
        // KDE customization ships its own Breeze/Papirus theming and ignores the
        // XFCE theme assets + ALTARIS_SKIP_THEME_ICONS, so staging them is pure
        // waste (the Oh My Zsh stage below stays useful for both desktops).
        val isKde = merged["ALTARIS_DESKTOP"] == "kde"
        val profile = com.altaris.linux.core.install.DistroInstallProfile.forId(distroId)
        // Chroot cards stage into the chroot guest /tmp (root copy) and let the
        // guest extract. Never target the sibling proot container, and never
        // set ALTARIS_SKIP_THEME_ICONS for a chroot — the guest must extract.
        if (profile?.method == "chroot") {
            if (!isKde) {
                val chrootPath = profile.chrootPath
                    ?: com.altaris.linux.core.root.ChrootPaths.CHROOT_PATH
                try {
                    com.altaris.linux.core.install.ProotXfceAssetInstaller.installToChroot(
                        activity, theme, chrootPath
                    ) { line ->
                        android.util.Log.i("Altaris", "Chroot theme ($chrootPath): $line")
                    }
                } catch (e: Exception) {
                    android.util.Log.w("Altaris", "Chroot theme stage failed", e)
                }
            }
            // OMZ + pokemon are guest-side for pure chroot (no host git).
            // Do not set ALTARIS_SKIP_OMZ / ALTARIS_SKIP_POKEMON — script defaults try.
            return merged
        }
        val prootName = profile?.prootName?.takeIf { it.isNotBlank() }
            ?: distroId.removeSuffix("_chroot").ifBlank { "debian" }
        if (!isKde) {
            try {
                val themeOk = com.altaris.linux.core.install.ProotXfceAssetInstaller.install(
                    activity, theme, prootName
                ) { line ->
                    android.util.Log.i("Altaris", "Host theme ($prootName): $line")
                }
                if (themeOk) merged["ALTARIS_SKIP_THEME_ICONS"] = "1"
            } catch (e: Exception) {
                android.util.Log.w("Altaris", "Host theme stage failed", e)
            }
        }
        try {
            val omzOk = com.altaris.linux.core.install.ProotZshBootstrap.install(
                activity, prootName
            ) { line ->
                android.util.Log.i("Altaris", "Host OMZ ($prootName): $line")
            }
            // Only skip guest OMZ when *this* rootfs actually has oh-my-zsh.sh
            if (omzOk) merged["ALTARIS_SKIP_OMZ"] = "1"
        } catch (e: Exception) {
            android.util.Log.w("Altaris", "Host OMZ stage failed", e)
        }
        // Try pokemon on Distro Settings re-run (60s guest clone; skip only if caller set 1).
        if (!merged.containsKey("ALTARIS_SKIP_POKEMON")) {
            merged["ALTARIS_SKIP_POKEMON"] = "0"
        }
        return merged
    }

    private fun runComponentStep(
        distro: com.altaris.linux.core.data.Distro,
        components: List<com.altaris.linux.core.data.DistroComponent>,
        index: Int,
        theme: String,
        gpu: String
    ) {
        if (index >= components.size) return
        val component = components[index]
        val baseEnv = if (component.id == "hw_accel") {
            val det = com.altaris.linux.core.terminal.GpuAccelDetector.detect()
            com.altaris.linux.core.terminal.GpuAccelDetector.persist(this, det)
            val mode = com.altaris.linux.core.terminal.GpuAccelDetector.resolveAltarisGpu(gpu)
            mapOf("ALTARIS_GPU" to mode, "ALTARIS_GPU_VENDOR" to det.vendorHint)
        } else {
            mapOf("ALTARIS_THEME" to theme)
        }
        fun openWith(extraEnv: Map<String, String>) {
            val scriptContent = try {
                loadGuestComponentScript(this, component, extraEnv)
            } catch (e: Exception) {
                android.util.Log.e("Altaris", "Failed to load ${component.scriptName}", e)
                null
            }
            if (scriptContent == null) {
                runComponentStep(distro, components, index + 1, theme, gpu)
                return
            }
            val opened = com.altaris.linux.core.terminal.AltarisTerminalSessionManager.openComponentSession(
                this,
                distro = distro,
                scriptContent = scriptContent,
                title = component.name,
                extraEnv = extraEnv,
                isUninstall = false,
                onFinished = {
                    com.altaris.linux.core.utils.StateManager.setComponentInstalled(
                        this, distro.id, component.id, true
                    )
                    if (component.id == "hw_accel") refreshDriverMarker(distro)
                    com.altaris.linux.core.utils.StateManager.triggerRefresh()
                    runComponentStep(distro, components, index + 1, theme, gpu)
                }
            )
            if (!opened) {
                android.widget.Toast.makeText(
                    this, "Max tabs reached — ${component.name} skipped (retry in Distro Settings)",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                runComponentStep(distro, components, index + 1, theme, gpu)
            }
        }
        if (isCustomizationComponent(component)) {
            Thread {
                val env = stageCustomizationHostEnv(this, baseEnv, distro.id)
                runOnUiThread { openWith(env) }
            }.start()
        } else if (component.id == "hw_accel" && baseEnv["ALTARIS_GPU"] in listOf("turnip", "panvk")) {
            Thread {
                val env = baseEnv + GpuDriverInstaller.guestEnv(this, distro.id)
                runOnUiThread {
                    if (GpuDriverInstaller.pendingDistro(this) != null) armDriverResume()
                    openWith(env)
                }
            }.start()
        } else {
            openWith(baseEnv)
        }
    }

    /**
     * Embedded component install/uninstall: run the component script inside the
     * guest via the parent distro's terminal component (proot session or chroot
     * SSOT helper). Marks state when the session finishes.
     */
    private fun runEmbeddedComponent(
        activity: MainActivity,
        distro: com.altaris.linux.core.data.Distro,
        component: com.altaris.linux.core.data.DistroComponent,
        extraEnv: Map<String, String>,
        isUninstall: Boolean,
        onOpenTerminalScreen: () -> Unit = {}
    ) {
        com.altaris.linux.core.terminal.TerminalLauncher.prepareHost(activity) { ok ->
            if (!ok) {
                android.widget.Toast.makeText(
                    activity, "Host bootstrap not ready — check Settings",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return@prepareHost
            }
            fun openWith(env: Map<String, String>) {
                val scriptContent = try {
                    loadGuestComponentScript(activity, component, env)
                } catch (e: Exception) {
                    android.util.Log.e("Altaris", "Failed to load ${component.scriptName}", e)
                    null
                }
                if (scriptContent == null) return
                val title = (if (isUninstall) "Uninstall " else "Install ") + component.name
                val opened = com.altaris.linux.core.terminal.AltarisTerminalSessionManager.openComponentSession(
                    activity,
                    distro = distro,
                    scriptContent = scriptContent,
                    title = title,
                    extraEnv = env,
                    isUninstall = isUninstall,
                    onFinished = {
                        com.altaris.linux.core.utils.StateManager.setComponentInstalled(
                            activity, distro.id, component.id, !isUninstall
                        )
                        if (!isUninstall && component.id == "hw_accel") refreshDriverMarker(distro)
                        com.altaris.linux.core.utils.StateManager.triggerRefresh()
                    }
                )
                if (opened) {
                    onOpenTerminalScreen()
                }
            }
            if (!isUninstall && component.id == "hw_accel") {
                // Adreno: pick the stable Turnip release (network) off the main thread.
                Thread {
                    val merged = extraEnv.toMutableMap()
                    val raw = extraEnv["ALTARIS_GPU"]
                    val det = com.altaris.linux.core.terminal.GpuAccelDetector.detect()
                    com.altaris.linux.core.terminal.GpuAccelDetector.persist(activity, det)
                    merged["ALTARIS_GPU"] =
                        com.altaris.linux.core.terminal.GpuAccelDetector.resolveAltarisGpu(raw)
                    if (!merged.containsKey("ALTARIS_GPU_VENDOR")) {
                        merged["ALTARIS_GPU_VENDOR"] = det.vendorHint
                    }
                    if (merged["ALTARIS_GPU"] in listOf("turnip", "panvk")) {
                        merged.putAll(GpuDriverInstaller.guestEnv(activity, distro.id))
                    }
                    activity.runOnUiThread {
                        if (GpuDriverInstaller.pendingDistro(activity) != null) armDriverResume()
                        openWith(merged)
                    }
                }.start()
                return@prepareHost
            }
            val resolvedEnv = extraEnv
            if (!isUninstall && isCustomizationComponent(component)) {
                android.widget.Toast.makeText(
                    activity, "Preparing themes & Oh My Zsh on host…",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                Thread {
                    val env = stageCustomizationHostEnv(activity, extraEnv, distro.id)
                    activity.runOnUiThread { openWith(env) }
                }.start()
            } else {
                openWith(resolvedEnv)
            }
        }
    }

    /**
     * Legacy queue runner: the clipboard→Termux flow was replaced by in-app
     * terminal sessions. Kept as a no-op that clears any stale queue state.
     */
    private fun processNextInstallTask() {
        com.altaris.linux.core.utils.InstallationQueueManager.clear()
    }

    private fun consumeCallbackIntent() {
        setIntent(android.content.Intent(this, MainActivity::class.java).apply { action = android.content.Intent.ACTION_MAIN })
    }

    private fun dispatchLegacyTermuxCallback(intent: android.content.Intent?) {
        if (intent?.action == android.content.Intent.ACTION_VIEW && intent.data?.scheme == "altaris") {
            val uri = intent.data
            val scriptName = uri?.getQueryParameter("name") ?: return
            if (scriptName.startsWith("legacy_termux_")) {
                val result = uri.getQueryParameter("result")
                com.altaris.linux.core.legacy.LegacyTermuxCallbacks.handle(this, result, scriptName, uri)
                consumeCallbackIntent()
            }
        }
    }

    /** Update installed-driver prefs from the guest marker (off main thread). */
    private fun refreshDriverMarker(distro: com.altaris.linux.core.data.Distro) {
        val p = com.altaris.linux.core.install.DistroInstallProfile.forId(distro.id) ?: return
        Thread {
            GpuDriverInstaller.refreshInstalled(this, distro.id, p.prootName, p.method == "chroot")
        }.start()
    }

    /** Offline install pending: wait for internet (app open), then resume it. */
    private fun armDriverResume() {
        val id = GpuDriverInstaller.pendingDistro(this) ?: return
        if (DriverRelease.online(this)) return
        android.widget.Toast.makeText(
            this,
            "No internet — GPU driver not installed. Linux will use software rendering until you retry (Distro Settings > Hardware Acceleration > Retry). Waiting for internet…",
            android.widget.Toast.LENGTH_LONG
        ).show()
        DriverRelease.armResume(this) {
            runOnUiThread {
                val d = com.altaris.linux.core.data.DistroRepository.supportedDistros
                    .find { it.id == id } ?: return@runOnUiThread
                val c = d.components.find { it.id == "hw_accel" } ?: return@runOnUiThread
                runEmbeddedComponent(this, d, c, mapOf("ALTARIS_GPU" to "auto"), false)
            }
        }
    }

    override fun onDestroy() {
        GpuDriverInstaller.onPending = null
        DriverRelease.disarm(this)
        super.onDestroy()
    }

    @OptIn(ExperimentalPermissionsApi::class, ExperimentalHazeMaterialsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GpuDriverInstaller.onPending = { runOnUiThread { armDriverResume() } }
        armDriverResume()
        if (savedInstanceState == null) {
            dispatchLegacyTermuxCallback(intent)
        }
        requestNotificationPermissionIfNeeded()
        setContent {
            // Midnight is the default. Day and System are explicit user choices.
            val themePreferences = remember { ThemePreferences(this@MainActivity) }
            remember { themePreferences.migrateToMidnight(); true }
            var currentThemeMode by remember {
                mutableStateOf(
                    when (val stored = themePreferences.getThemeMode()) {
                        ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.SYSTEM -> stored
                        else -> ThemeMode.DARK
                    }
                )
            }

            AltarisTheme(themeMode = currentThemeMode) {
                val onboardingComplete = StateManager.isOnboardingComplete(this@MainActivity)
                val showPhantomPrompt by com.altaris.linux.core.system.PhantomProcessFixer.promptVisible.collectAsState()
                if (showPhantomPrompt) {
                    com.altaris.linux.ui.components.PhantomProcessDialog(warning = true) {
                        com.altaris.linux.core.system.PhantomProcessFixer.promptVisible.value = false
                    }
                }

                // Permission State (Lifted for Settings and Home access)
                val permissionState = rememberPermissionState(
                    permission = "com.termux.permission.RUN_COMMAND"
                )

                // ── Navigation state ──────────────────────────────────────────
                // Compose snapshot state is the single source of truth for the
                // tab. External requests (FGS notification tap, deep links) go
                // through one-shot request channels (pendingTabRequest /
                // pendingScreenRequest) that are drained exactly once below.
                // Nothing ever mirrors tab state back out — the old
                // ref.round-trip (write ref → collect → LaunchedEffect → write
                // state) formed a feedback loop a tab swap could never settle
                // out of, which crashed Home → any other tab.
                var currentScreen by remember {
                    mutableStateOf(if (onboardingComplete) Screen.HOME else Screen.ONBOARDING)
                }
                var currentTab by remember { mutableStateOf(BottomTab.HOME) }

                // The command palette is the new navigation surface: one
                // searchable list that jumps to screens and actions.
                var paletteOpen by remember { mutableStateOf(false) }


                // Drain external intent requests (one-shot; request side never
                // reads tab state back, so no loop can form).
                LaunchedEffect(Unit) {
                    pendingTabRequest.collect { tab -> if (tab != currentTab) currentTab = tab }
                }
                LaunchedEffect(Unit) {
                    pendingScreenRequest.collect { screen -> if (screen != currentScreen) currentScreen = screen }
                }

                /** Open the Terminal page (bottom-nav hidden, full height). */
                // A shell on the Android host itself (no container).
                val openHostShell: () -> Unit = {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "Preparing the host shell…",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    com.altaris.linux.core.terminal.AltarisTerminalSessionManager
                        .openHostShellAfterReady(
                            this@MainActivity,
                            title = "Host Shell",
                            onResult = { result ->
                                when (result) {
                                    com.altaris.linux.core.terminal.AltarisTerminalSessionManager
                                        .SessionOpenResult.OPENED -> currentScreen = Screen.TERMINAL
                                    com.altaris.linux.core.terminal.AltarisTerminalSessionManager
                                        .SessionOpenResult.MAX_TABS ->
                                        android.widget.Toast.makeText(
                                            this@MainActivity,
                                            "Tab limit reached",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    else -> android.widget.Toast.makeText(
                                        this@MainActivity,
                                        "Host shell is not ready — check Settings",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        )
                }

                // The terminal is a full-screen route with its own dark chrome
                // and a back button — it is not a navigation tab, so the user
                // can never get stranded in it.
                val openTerminalTab: () -> Unit = {
                    currentScreen = Screen.TERMINAL
                }

                BackHandler {
                    if (currentScreen == Screen.SETTINGS_CHROOT_DETAIL) {
                        currentScreen = Screen.SETTINGS_CHROOT
                    } else if (currentScreen == Screen.SETTINGS_PROOT_DETAIL) {
                        currentScreen = Screen.SETTINGS_PROOT
                    } else if (currentScreen == Screen.SETTINGS_TERMINAL
                        || currentScreen == Screen.SETTINGS_X11
                        || currentScreen == Screen.SETTINGS_AUDIO
                        || currentScreen == Screen.SETTINGS_CHROOT
                        || currentScreen == Screen.SETTINGS_PROOT
                        || currentScreen == Screen.SETTINGS_LEGACY_TERMUX
                        || currentScreen == Screen.TROUBLESHOOTING
                        || currentScreen == Screen.ROOT_ACCESS
                        || currentScreen == Screen.CRASH_LOGS) {
                        currentScreen = Screen.SETTINGS
                    } else if (currentScreen == Screen.TERMINAL) {
                        com.altaris.linux.ui.terminal.hideIme(window.decorView)
                        currentScreen = Screen.HOME
                    } else if (currentScreen == Screen.SETTINGS
                        || currentScreen == Screen.DISTRO_SETTINGS
                        || currentScreen == Screen.INSTALL_WIZARD) {
                        currentScreen = Screen.HOME
                        currentTab = BottomTab.HOME
                    } else if (currentScreen == Screen.ONBOARDING || currentScreen == Screen.PREREQUISITES) {
                        finish()
                    } else if (currentScreen == Screen.HOME && currentTab != BottomTab.HOME) {
                        if (currentTab == BottomTab.TERMINAL) {
                            com.altaris.linux.ui.terminal.hideIme(window.decorView)
                        }
                        currentTab = BottomTab.HOME
                    } else {
                        finish()
                    }
                }
                
                // Selected Distro for Wizard/Settings
                var selectedDistro by remember { mutableStateOf<com.altaris.linux.core.data.Distro?>(null) }
                // Selected target id for Chroot/PRoot storage detail
                var storageTargetId by remember { mutableStateOf<String?>(null) }
                
                // Refresh key to force UI update on resume
                // Collected from StateManager for remote triggers too
                val refreshKey by com.altaris.linux.core.utils.StateManager.refreshTrigger.collectAsState()
                
                // ALSO react to Lifecycle
                var lifecycleRefreshKey by remember { mutableStateOf(0) }
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            lifecycleRefreshKey++
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }
                
                // Helpers for service/activity
                val onStartServiceStub: (android.content.Intent) -> Unit = { intent ->
                    try { startService(intent) } catch (e: Exception) { android.util.Log.e("Altaris", "StartService failed", e) }
                }
                val onStartActivityStub: (android.content.Intent) -> Unit = { intent ->
                    try { startActivity(intent) } catch (e: Exception) { android.util.Log.e("Altaris", "StartActivity failed", e) }
                }
                
                // Navigation Callbacks
                val onNavigateToInstall: (com.altaris.linux.core.data.Distro) -> Unit = { distro ->
                    selectedDistro = distro
                    currentScreen = Screen.INSTALL_WIZARD
                }
                val onNavigateToDistroSettings: (com.altaris.linux.core.data.Distro) -> Unit = { distro ->
                    selectedDistro = distro
                    currentScreen = Screen.DISTRO_SETTINGS
                }

                // ── In-app terminal actions (termux-altaris-terminal / chroot-root-shell) ──
                val onOpenTerminal: (String, Boolean) -> Unit = { distroId, root ->
                    val method = try {
                        com.altaris.linux.core.data.terminalComponentFor(distroId).method
                    } catch (_: Exception) {
                        "proot"
                    }
                    val type = if (root) "shell-root" else "shell"
                    val label = com.altaris.linux.core.install.DistroInstallProfile
                        .forId(distroId)?.displayName
                        ?: distroId
                    val title = when (method) {
                        "chroot" -> if (root) "$label Root Shell" else "$label Shell"
                        else -> if (root) "$label Shell (root)" else "$label Shell"
                    }
                    com.altaris.linux.core.terminal.AltarisTerminalSessionManager.openSessionAfterHost(
                        this@MainActivity,
                        type = type,
                        title = title,
                        method = method,
                        distroId = distroId,
                        onResult = { result ->
                            if (result == com.altaris.linux.core.terminal.AltarisTerminalSessionManager.SessionOpenResult.OPENED) {
                                openTerminalTab()
                            } else {
                                val message = when (result) {
                                    com.altaris.linux.core.terminal.AltarisTerminalSessionManager.SessionOpenResult.HOST_PREPARE_FAILED ->
                                        "Host bootstrap not ready — check Settings"
                                    com.altaris.linux.core.terminal.AltarisTerminalSessionManager.SessionOpenResult.HOST_NOT_READY ->
                                        "Host not ready — check Settings"
                                    com.altaris.linux.core.terminal.AltarisTerminalSessionManager.SessionOpenResult.MAX_TABS ->
                                        "Tab limit reached"
                                    else -> "Session failed to open — try again"
                                }
                                android.widget.Toast.makeText(
                                    this@MainActivity,
                                    message,
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    )
                }

                val startEmbeddedInstall: (com.altaris.linux.core.data.Distro, String?, () -> Unit) -> Unit =
                    { distro, setupB64, onBaseInstalled ->
                        com.altaris.linux.core.terminal.TerminalLauncher.prepareHost(
                            this@MainActivity,
                            onDone = { ok ->
                                if (!ok) {
                                    android.widget.Toast.makeText(
                                        this@MainActivity,
                                        "Host bootstrap failed to extract",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                    return@prepareHost
                                }
                                val opened = com.altaris.linux.core.terminal.AltarisTerminalSessionManager
                                    .openInstallSession(
                                        this@MainActivity,
                                        distro,
                                        setupB64,
                                        onFinished = {
                                            // B3: fires only when the install session exited 0
                                            com.altaris.linux.core.utils.StateManager.setDistroInstalled(
                                                this@MainActivity, distro.id, true
                                            )
                                            com.altaris.linux.core.utils.StateManager.triggerRefresh()
                                            onBaseInstalled()
                                        }
                                    )
                                if (opened) {
                                    openTerminalTab()
                                }
                            }
                        )
                    }

                
                @Composable
                fun MainScreenContent(
                    tab: BottomTab,
                    hazeState: HazeState
                ) {
                    // Tab motion: fade + a short horizontal carry; the Terminal tab
                    // is a fade-only AndroidView surface. The dock springs on its own.
                    AnimatedContent(
                        targetState = tab,
                        transitionSpec = { altarisTabTransition() },
                        label = "altarisTab",
                        modifier = Modifier.fillMaxSize()
                    ) { activeTab ->
                        when (activeTab) {
                            BottomTab.HOME -> {
                                com.altaris.linux.ui.screens.HomeScreen(
                                    permissionState = permissionState,
                                    hazeState = hazeState,
                                    scriptRefreshTrigger = refreshKey + lifecycleRefreshKey,
                                    onStartService = onStartServiceStub,
                                    onStartActivity = onStartActivityStub,
                                    // Pass navigation callbacks
                                    onNavigateToInstall = onNavigateToInstall,
                                    onNavigateToSettings = onNavigateToDistroSettings,
                                    onOpenTerminal = onOpenTerminal,
                                    onShowTerminal = openTerminalTab,
                                    onOpenHostShell = openHostShell
                                )
                            }
                            BottomTab.DISTROS -> {
                                com.altaris.linux.ui.screens.DistroScreen(
                                    permissionState = permissionState,
                                    hazeState = hazeState,
                                    onStartService = onStartServiceStub,
                                    onStartActivity = onStartActivityStub,
                                    onNavigateToInstall = onNavigateToInstall
                                )
                            }
                            BottomTab.APPS -> {
                                com.altaris.linux.ui.screens.ClonedAppsScreen()
                            }
                            BottomTab.TOOLS -> {
                                com.altaris.linux.ui.screens.ToolsScreen()
                            }
                            BottomTab.TERMINAL -> {
                                com.altaris.linux.ui.screens.TerminalScreen(
                                    onBack = { currentTab = BottomTab.HOME },
                                    embeddedInBottomNav = true
                                )
                            }
                            BottomTab.SETTINGS -> {
                                com.altaris.linux.ui.screens.SettingsScreen(
                                    onBack = { currentTab = BottomTab.HOME },
                                    permissionState = permissionState,
                                    onStartService = onStartServiceStub,
                                    onStartActivity = onStartActivityStub,
                                    onNavigateToOnboarding = {
                                        StateManager.setOnboardingComplete(this@MainActivity, false)
                                        currentScreen = Screen.ONBOARDING
                                    },
                                    onNavigateToTroubleshooting = { currentScreen = Screen.TROUBLESHOOTING },
                                    onNavigateToRootCheck = { currentScreen = Screen.ROOT_ACCESS },
                                    onNavigateToCrashLogs = { currentScreen = Screen.CRASH_LOGS },
                                    onNavigateToTerminalSettings = { currentScreen = Screen.SETTINGS_TERMINAL },
                                    onNavigateToX11Settings = { currentScreen = Screen.SETTINGS_X11 },
                                    onNavigateToAudioSettings = { currentScreen = Screen.SETTINGS_AUDIO },
                                    onNavigateToChrootSettings = { currentScreen = Screen.SETTINGS_CHROOT },
                                    onNavigateToProotSettings = { currentScreen = Screen.SETTINGS_PROOT },
                                    onNavigateToLegacyTermuxSettings = { currentScreen = Screen.SETTINGS_LEGACY_TERMUX },
                                    currentTheme = currentThemeMode,
                                    onThemeChanged = { mode ->
                                        currentThemeMode = mode
                                        themePreferences.setThemeMode(mode)
                                    }
                                )
                            }
                        }
                    }
                }

                // The floating command bar: mark, status and the jump button.
                @Composable
                fun ShellCommandBar(
                    displayLive: Boolean,
                    cloneRunning: Boolean,
                    onOpenPalette: () -> Unit,
                    onOpenSettings: () -> Unit,
                    onOpenHostShell: () -> Unit,
                ) {
                    AltarisCommandBar(
                        accent = MaterialTheme.colorScheme.primary,
                        statusLabel = when {
                            cloneRunning -> "CLONE RUNNING"
                            displayLive -> "DISPLAY LIVE"
                            else -> "READY"
                        },
                        live = displayLive || cloneRunning,
                        onOpenPalette = onOpenPalette,
                        onOpenSettings = onOpenSettings,
                        onOpenHostShell = onOpenHostShell,
                        taskButton = {
                            com.altaris.linux.ui.components.DownloadTaskButton(
                                onOpenProgress = { distroId ->
                                    val target = com.altaris.linux.core.data.DistroRepository
                                        .supportedDistros
                                        .firstOrNull { it.id == distroId }
                                    if (target != null) {
                                        selectedDistro = target
                                        currentScreen = Screen.INSTALL_WIZARD
                                    } else {
                                        currentScreen = Screen.TROUBLESHOOTING
                                    }
                                },
                                onOpenTroubleshooting = {
                                    currentScreen = Screen.TROUBLESHOOTING
                                },
                                onDismiss = {
                                    com.altaris.linux.core.utils.InstallationQueueManager
                                        .dismissTerminalState()
                                },
                            )
                        },
                    )
                }

                // Show appropriate screen based on state
                AnimatedContent(
                    targetState = currentScreen,
                    transitionSpec = { altarisRouteTransition() },
                    label = "altarisRoute",
                    modifier = Modifier.fillMaxSize()
                ) { screen ->
                    when (screen) {
                        Screen.ONBOARDING -> {
                            com.altaris.linux.ui.onboarding.OnboardingFlowScreen(
                                onFinished = {
                                    StateManager.setOnboardingComplete(this@MainActivity, true)
                                    currentScreen = Screen.HOME
                                    currentTab = BottomTab.HOME
                                },
                                onOpenTerminal = {
                                    StateManager.setOnboardingComplete(this@MainActivity, true)
                                    openTerminalTab()
                                },
                                onStartDesktop = { distroId ->
                                    StateManager.setOnboardingComplete(this@MainActivity, true)
                                    currentScreen = Screen.HOME
                                    currentTab = BottomTab.HOME
                                    // GUI flags owned by DesktopLauncher
                                    com.altaris.linux.core.desktop.DesktopLauncher.start(
                                        this@MainActivity, distroId
                                    )
                                }
                            )
                        }
                        Screen.HOME -> {
                            val hazeState = remember { HazeState() }
                            val desktopUi by com.altaris.linux.core.desktop.DesktopLauncher.uiState.collectAsState()
                            val stateRefresh by com.altaris.linux.core.utils.StateManager.refreshTrigger.collectAsState()
                            val session = remember(desktopUi, stateRefresh) {
                                com.altaris.linux.core.desktop.DesktopSessionQuery.current(this@MainActivity, desktopUi)
                            }
                            val activeClone by com.altaris.linux.core.clone.HeadlessCloneEngine
                                .activeClonePackage.collectAsState()

                            // Upstream behaviour: the terminal owns the whole window.
                            // Both chrome pieces get out of its way — the top bar
                            // would otherwise steal status-bar padding (and with it
                            // the IME+navigation-bars double padding the terminal
                            // already applies), and the floating nav would sit on
                            // top of ExtraKeys. This is why the tab bar must never
                            // carry a Terminal item: it can't be selected from the
                            // bar, and the bar can't reliably hide while mounted.
                            // The chrome never disappears: the terminal is a
                            // route of its own, so the nav stays reachable.
                            val showChrome = true

                            // Terminal hides the IME when we leave it, or it stays
                            // up over the next tab's contents.
                            LaunchedEffect(currentTab) {
                                if (currentTab != BottomTab.TERMINAL) {
                                    com.altaris.linux.ui.terminal.hideIme(window.decorView)
                                }
                            }

                            GlassScaffold(
                                hazeState = hazeState,
                                // No per-tab blur toggle: the haze source must stay
                                // mounted while the TopBar/dock hazeChild nodes are up
                                // (see GlassScaffold) — leaving HOME used to unmount it
                                // mid-session, which is the tab-switch crash class.
                                topBar = {
                                    if (showChrome) {
                                        Column {
                                            // The bar draws through the status bar
                                            // area, the content sits below it.
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(MaterialTheme.colorScheme.surface)
                                                    .statusBarsPadding(),
                                            ) {
                                                ShellCommandBar(
                                                    displayLive = session != null,
                                                    cloneRunning = activeClone != null,
                                                    onOpenPalette = { paletteOpen = true },
                                                    onOpenSettings = { currentScreen = Screen.SETTINGS },
                                                    onOpenHostShell = openHostShell
                                                )
                                            }
                                        }
                                    }
                                },
                                bottomBar = {
                                    if (showChrome) {
                                        AltarisDock(
                                            selected = when (currentTab) {
                                                BottomTab.DISTROS -> DockDestination.DISTROS
                                                BottomTab.APPS -> DockDestination.APPS
                                                BottomTab.TOOLS -> DockDestination.TOOLS
                                                else -> DockDestination.HOME
                                            },
                                            onSelect = { destination ->
                                                currentTab = when (destination) {
                                                    DockDestination.HOME -> BottomTab.HOME
                                                    DockDestination.DISTROS -> BottomTab.DISTROS
                                                    DockDestination.APPS -> BottomTab.APPS
                                                    DockDestination.TOOLS -> BottomTab.TOOLS
                                                }
                                            },
                                        )
                                    }
                                }
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        // Overlap math. The floating nav is a
                                        // 64dp pill with 16dp top padding inside a
                                        // `navigationBarsPadding()` box, so its top
                                        // edge sits at navBarInset + 80dp over a
                                        // full-window coordinate space. The tab
                                        // screens reserve 110dp of bottom content
                                        // padding. Consuming the navigation-bar
                                        // inset here shifts the scrolling viewport
                                        // up by that same amount, so 110dp always
                                        // clears the nav (with ~30dp to spare) and
                                        // the last item can always be scrolled
                                        // above it.
                                        // The Terminal tab is excluded: it owns its
                                        // own ime∪navigationBars padding and must
                                        // keep the full window height.
                                        .then(
                                            if (showChrome) {
                                                Modifier.navigationBarsPadding()
                                            } else {
                                                Modifier
                                            }
                                        )
                                ) {
                                    MainScreenContent(
                                        tab = currentTab,
                                        hazeState = hazeState
                                    )
                                }
                            }
                        }
                        Screen.SETTINGS -> {
                            com.altaris.linux.ui.screens.SettingsScreen(
                                onBack = { currentScreen = Screen.HOME },
                                permissionState = permissionState,
                                onStartService = onStartServiceStub,
                                onStartActivity = onStartActivityStub,
                                onNavigateToOnboarding = {
                                    StateManager.setOnboardingComplete(this@MainActivity, false)
                                    currentScreen = Screen.ONBOARDING
                                },
                                onNavigateToTroubleshooting = { currentScreen = Screen.TROUBLESHOOTING },
                                onNavigateToRootCheck = { currentScreen = Screen.ROOT_ACCESS },
                                onNavigateToCrashLogs = { currentScreen = Screen.CRASH_LOGS },
                                onNavigateToTerminalSettings = {
                                    currentScreen = Screen.SETTINGS_TERMINAL
                                },
                                onNavigateToX11Settings = {
                                    currentScreen = Screen.SETTINGS_X11
                                },
                                onNavigateToAudioSettings = {
                                    currentScreen = Screen.SETTINGS_AUDIO
                                },
                                onNavigateToChrootSettings = {
                                    currentScreen = Screen.SETTINGS_CHROOT
                                },
                                onNavigateToProotSettings = {
                                    currentScreen = Screen.SETTINGS_PROOT
                                },
                                onNavigateToLegacyTermuxSettings = {
                                    currentScreen = Screen.SETTINGS_LEGACY_TERMUX
                                },
                                currentTheme = currentThemeMode,
                                onThemeChanged = { mode ->
                                    currentThemeMode = mode
                                    themePreferences.setThemeMode(mode)
                                }
                            )
                        }
                        Screen.SETTINGS_TERMINAL -> {
                            com.altaris.linux.ui.screens.TerminalSettingsScreen(
                                onBack = { currentScreen = Screen.SETTINGS }
                            )
                        }
                        Screen.SETTINGS_X11 -> {
                            com.altaris.linux.ui.screens.X11SettingsScreen(
                                onBack = { currentScreen = Screen.SETTINGS }
                            )
                        }
                        Screen.SETTINGS_AUDIO -> {
                            com.altaris.linux.ui.screens.AudioSettingsScreen(
                                onBack = { currentScreen = Screen.SETTINGS }
                            )
                        }
                        Screen.SETTINGS_CHROOT -> {
                            com.altaris.linux.ui.screens.ChrootStorageListScreen(
                                onBack = { currentScreen = Screen.SETTINGS },
                                onSelectDistro = { id ->
                                    storageTargetId = id
                                    currentScreen = Screen.SETTINGS_CHROOT_DETAIL
                                },
                                onNavigateToDistros = {
                                    currentScreen = Screen.HOME
                                    currentTab = BottomTab.DISTROS
                                }
                            )
                        }
                        Screen.SETTINGS_CHROOT_DETAIL -> {
                            val targetId = storageTargetId
                            if (targetId == null) {
                                LaunchedEffect(Unit) {
                                    currentScreen = Screen.SETTINGS_CHROOT
                                }
                            } else {
                                com.altaris.linux.ui.screens.ChrootStorageDetailScreen(
                                    distroId = targetId,
                                    onBack = { currentScreen = Screen.SETTINGS_CHROOT },
                                    onNavigateToInstall = {
                                        selectedDistro = com.altaris.linux.core.data.DistroRepository
                                            .supportedDistros
                                            .firstOrNull { it.id == targetId }
                                        currentScreen = if (selectedDistro != null) {
                                            Screen.INSTALL_WIZARD
                                        } else {
                                            Screen.SETTINGS_CHROOT
                                        }
                                    }
                                )
                            }
                        }
                        Screen.SETTINGS_PROOT -> {
                            com.altaris.linux.ui.screens.ProotStorageListScreen(
                                onBack = { currentScreen = Screen.SETTINGS },
                                onSelectDistro = { id ->
                                    storageTargetId = id
                                    currentScreen = Screen.SETTINGS_PROOT_DETAIL
                                },
                                onNavigateToDistros = {
                                    currentScreen = Screen.HOME
                                    currentTab = BottomTab.DISTROS
                                }
                            )
                        }
                        Screen.SETTINGS_PROOT_DETAIL -> {
                            val targetId = storageTargetId
                            if (targetId == null) {
                                LaunchedEffect(Unit) {
                                    currentScreen = Screen.SETTINGS_PROOT
                                }
                            } else {
                                com.altaris.linux.ui.screens.ProotStorageDetailScreen(
                                    distroId = targetId,
                                    onBack = { currentScreen = Screen.SETTINGS_PROOT },
                                    onNavigateToInstall = {
                                        selectedDistro = com.altaris.linux.core.data.DistroRepository
                                            .supportedDistros
                                            .firstOrNull { it.id == targetId }
                                        currentScreen = if (selectedDistro != null) {
                                            Screen.INSTALL_WIZARD
                                        } else {
                                            Screen.SETTINGS_PROOT
                                        }
                                    }
                                )
                            }
                        }
                        Screen.SETTINGS_LEGACY_TERMUX -> {
                            com.altaris.linux.ui.screens.LegacyTermuxSettingsScreen(
                                onBack = { currentScreen = Screen.SETTINGS },
                                permissionState = permissionState
                            )
                        }
                        Screen.TROUBLESHOOTING -> {
                            com.altaris.linux.ui.screens.TroubleshootingScreen(
                                onBack = { currentScreen = Screen.SETTINGS },
                                onOpenLogs = { currentScreen = Screen.CRASH_LOGS },
                                onOpenRoot = { currentScreen = Screen.ROOT_ACCESS },
                                onOpenSettings = { currentScreen = Screen.SETTINGS },
                            )
                        }
                        Screen.TERMINAL -> {
                            com.altaris.linux.ui.screens.TerminalScreen(
                                onBack = { currentScreen = Screen.HOME },
                                embeddedInBottomNav = false
                            )
                        }
                        Screen.PREREQUISITES -> {
                            // Legacy redirect route: bounce to Home without a
                            // during-composition state write (kept as an effect so
                            // AnimatedContent never sees a mid-frame target change).
                            LaunchedEffect(Unit) { currentScreen = Screen.HOME }
                        }
                        Screen.ROOT_ACCESS -> {
                            com.altaris.linux.ui.screens.RootAccessScreen(
                                onBack = { currentScreen = Screen.SETTINGS },
                                onEnableChroot = {
                                    android.widget.Toast.makeText(this@MainActivity, "Chroot Mode Enabled", android.widget.Toast.LENGTH_SHORT).show()
                                    currentScreen = Screen.SETTINGS
                                }
                            )
                        }
                        Screen.CRASH_LOGS -> {
                            com.altaris.linux.ui.screens.CrashLogScreen(
                                onBack = { currentScreen = Screen.SETTINGS }
                            )
                        }
                        Screen.INSTALL_WIZARD -> {
                             val hazeState = remember { HazeState() }
                             if (selectedDistro != null) {
                                 // Shared runner with onboarding: rootfs + XFCE + customization
                                 // for both proot and chroot (no feature modules).
                                 com.altaris.linux.ui.screens.InstallConfigScreen(
                                     distro = selectedDistro!!,
                                     onBack = { currentScreen = Screen.HOME },
                                     hazeState = hazeState,
                                     onInstallComplete = {
                                         currentScreen = Screen.HOME
                                         currentTab = BottomTab.HOME
                                     }
                                 )
                             } else {
                                 currentScreen = Screen.HOME
                             }
                        }
                        Screen.DISTRO_SETTINGS -> {
                             val hazeState = remember { HazeState() }
                             if (selectedDistro != null) {
                                  com.altaris.linux.ui.screens.DistroSettingsScreen(
                                      distro = selectedDistro!!,
                                      onBack = { currentScreen = Screen.HOME },
                                      hazeState = hazeState,
                                       onInstallComponent = { component, extraEnv ->
                                           // Embedded: run component script inside the guest via
                                           // the same terminal component as the parent distro.
                                           runEmbeddedComponent(
                                               this@MainActivity, selectedDistro!!, component, extraEnv,
                                               isUninstall = false, onOpenTerminalScreen = openTerminalTab
                                           )
                                       },
                                       onUninstallComponent = { component ->
                                           runEmbeddedComponent(
                                               this@MainActivity, selectedDistro!!, component, emptyMap(),
                                               isUninstall = true, onOpenTerminalScreen = openTerminalTab
                                           )
                                       },
                                       onReinstallDistro = {
                                           // Full base reinstall (rootfs + XFCE + customization)
                                           // via InstallConfig / OnboardingInstallRunner — not rootfs-only.
                                           currentScreen = Screen.INSTALL_WIZARD
                                       },
                                     onUninstallDistro = {
                                         // Embedded uninstall: proot → proot-distro remove in Altaris
                                         // Terminal; chroot → uninstall_debian13_chroot.sh in Root Shell.
                                         val distro = selectedDistro ?: return@DistroSettingsScreen
                                         com.altaris.linux.core.terminal.TerminalLauncher.prepareHost(
                                             this@MainActivity,
                                             onDone = { ok ->
                                                 if (!ok) return@prepareHost
                                                 val opened = com.altaris.linux.core.terminal.AltarisTerminalSessionManager
                                                     .openUninstallSession(this@MainActivity, distro)
                                                 if (opened) {
                                                     openTerminalTab()
                                                 }
                                             }
                                         )
                                     },
                                      onStartActivity = onStartActivityStub,
                                      onNavigateToStart = { /* Not used in Settings, but if needed */ },
                                      onLaunchXfce = {
                                          try {
                                              com.altaris.linux.core.desktop.DesktopLauncher.start(
                                                  this@MainActivity, selectedDistro!!.id
                                              )
                                          } catch (e: Exception) {
                                              android.util.Log.e("Altaris", "Launch XFCE4 failed", e)
                                          }
                                      },
                                      onStopXfce = {
                                          try {
                                              com.altaris.linux.core.desktop.DesktopLauncher.stop(
                                                  this@MainActivity, selectedDistro!!.id
                                              )
                                          } catch (e: Exception) {
                                              android.util.Log.e("Altaris", "Stop XFCE4 failed", e)
                                          }
                                      },
                                      onLaunchKde = {
                                          try {
                                              com.altaris.linux.core.desktop.DesktopLauncher.start(
                                                  this@MainActivity, selectedDistro!!.id, "kde"
                                              )
                                          } catch (e: Exception) {
                                              android.util.Log.e("Altaris", "Launch KDE failed", e)
                                          }
                                      },
                                      onStopKde = {
                                          try {
                                              com.altaris.linux.core.desktop.DesktopLauncher.stop(
                                                  this@MainActivity, selectedDistro!!.id
                                              )
                                          } catch (e: Exception) {
                                              android.util.Log.e("Altaris", "Stop KDE failed", e)
                                          }
                                      }
                                  )

                             } else {
                                 currentScreen = Screen.HOME
                             }
                        }
                    }
                }

                // The command palette floats above the whole shell.
                if (paletteOpen) {
                    CommandPaletteOverlay(
                        actions = buildList {
                            add(
                                PaletteAction(
                                    id = "home",
                                    title = "Home",
                                    subtitle = "Containers, status and the host shell",
                                    icon = Icons.Default.Home,
                                    accent = MaterialTheme.colorScheme.primary,
                                    onSelect = { currentTab = BottomTab.HOME },
                                )
                            )
                            add(
                                PaletteAction(
                                    id = "distros",
                                    title = "Distros",
                                    subtitle = "Install and manage containers",
                                    icon = Icons.Default.Layers,
                                    accent = MaterialTheme.colorScheme.secondary,
                                    onSelect = { currentTab = BottomTab.DISTROS },
                                )
                            )
                            add(
                                PaletteAction(
                                    id = "apps",
                                    title = "Apps",
                                    subtitle = "Clone and run Android apps",
                                    icon = Icons.Default.Apps,
                                    accent = MaterialTheme.colorScheme.tertiary,
                                    onSelect = { currentTab = BottomTab.APPS },
                                )
                            )
                            add(
                                PaletteAction(
                                    id = "tools",
                                    title = "Tools",
                                    subtitle = "Clone folder, MCP and agent actions",
                                    icon = Icons.Default.Tune,
                                    accent = MaterialTheme.colorScheme.primary,
                                    onSelect = { currentTab = BottomTab.TOOLS },
                                )
                            )
                            add(
                                PaletteAction(
                                    id = "terminal",
                                    title = "Host shell",
                                    subtitle = "A shell on the Android host",
                                    icon = Icons.Default.Terminal,
                                    accent = MaterialTheme.colorScheme.secondary,
                                    onSelect = { openHostShell() },
                                )
                            )
                            add(
                                PaletteAction(
                                    id = "settings",
                                    title = "Settings",
                                    subtitle = "Theme, storage, diagnostics",
                                    icon = Icons.Default.Settings,
                                    accent = MaterialTheme.colorScheme.tertiary,
                                    onSelect = { currentScreen = Screen.SETTINGS },
                                )
                            )
                        },
                        onDismiss = { paletteOpen = false },
                    )
                }
            }
        }
    }
}

// ── Altaris navigation motion ─────────────────────────────────────────────────
// Depth model: root routes (onboarding/home tabs) = 0, settings hubs = 1,
// their detail pages + desktop/install/terminal full-screen flows = 2. Push
// enters from the right, pop from the left (15% width + fade, 240ms); same-depth
// swaps crossfade with a small rise. AndroidView-heavy routes (terminal tab,
// install wizard) fade only so the surface never slides/clips mid-transition.

private const val AltarisNavMs = 240
private const val AltarisTabMs = 200

private val AltarisFadeOnlyRoutes = setOf(Screen.TERMINAL, Screen.INSTALL_WIZARD)

private fun Screen.navDepth(): Int = when (this) {
    Screen.ONBOARDING, Screen.PREREQUISITES, Screen.HOME -> 0
    Screen.SETTINGS_CHROOT_DETAIL, Screen.SETTINGS_PROOT_DETAIL,
    Screen.DISTRO_SETTINGS, Screen.INSTALL_WIZARD, Screen.TERMINAL -> 2
    else -> 1
}

private fun AnimatedContentTransitionScope<Screen>.altarisRouteTransition(): ContentTransform {
    val fadeOnly = initialState in AltarisFadeOnlyRoutes || targetState in AltarisFadeOnlyRoutes
    val depthDelta = targetState.navDepth() - initialState.navDepth()
    // Slides animate IntOffset specs; fades animate Float specs.
    val slideIn = tween<IntOffset>(AltarisNavMs, easing = FastOutSlowInEasing)
    val slideOut = tween<IntOffset>(AltarisNavMs - 60, easing = FastOutSlowInEasing)
    val fadeInSpec = tween<Float>(200, easing = FastOutSlowInEasing)
    val fadeOutSpec = tween<Float>(160, easing = FastOutSlowInEasing)
    val transform = when {
        // Leaving the terminal is instantaneous: its dark surface must not
        // cross-fade through the light identity's background.
        initialState == Screen.TERMINAL ->
            fadeIn(tween(0)) togetherWith fadeOut(tween(0))
        fadeOnly ->
            // The install wizard owns the whole window; a short fade reads as
            // one surface replacing another, with no horizontal movement.
            (fadeIn(tween(200, easing = FastOutSlowInEasing)) +
                slideInVertically(tween(240, easing = FastOutSlowInEasing)) { h -> h / 40 }) togetherWith
                (fadeOut(tween(150, easing = FastOutSlowInEasing)) +
                    slideOutVertically(tween(180, easing = FastOutSlowInEasing)) { h -> h / 40 })
        depthDelta > 0 ->
            (slideInHorizontally(slideIn) { w -> w * 3 / 20 } + fadeIn(fadeInSpec)) togetherWith
                (slideOutHorizontally(slideOut) { w -> -(w / 12) } + fadeOut(fadeOutSpec))
        depthDelta < 0 ->
            (slideInHorizontally(slideIn) { w -> -(w * 3 / 20) } + fadeIn(fadeInSpec)) togetherWith
                (slideOutHorizontally(slideOut) { w -> w / 8 } + fadeOut(fadeOutSpec))
        else ->
            (fadeIn(fadeInSpec) + slideInVertically(slideIn) { h -> h / 100 }) togetherWith
                (fadeOut(fadeOutSpec) + slideOutVertically(slideOut) { h -> -(h / 200) })
    }
    // Fade-only routes must not animate their bounds: with an open IME the
    // terminal measures shorter than the home screen, and interpolating that
    // size makes the incoming screen unfold from a corner. Clip and snap.
    return if (fadeOnly) {
        transform.using(SizeTransform(clip = true, sizeAnimationSpec = { _, _ -> tween(1) }))
    } else {
        // clip = false keeps terminal/X11 surface content from being clipped mid-slide.
        transform.using(SizeTransform(clip = false))
    }
}

private fun AnimatedContentTransitionScope<BottomTab>.altarisTabTransition(): ContentTransform {
    // One motion language for every dock destination: a short fade plus a
    // small horizontal carry so a switch is clearly felt, not just a
    // near-invisible scale. Distance is deliberately tiny (1/14 of the
    // width) — visible, but never a full page slide.
    val enterFade = tween<Float>(180, easing = FastOutSlowInEasing)
    val enterSlide = tween<Int>(230, easing = FastOutSlowInEasing)
    val exitFade = tween<Float>(120, easing = FastOutSlowInEasing)
    val exitSlide = tween<Int>(170, easing = FastOutSlowInEasing)
    return (fadeIn(enterFade) + slideInHorizontally(enterSlide) { width -> width / 14 }) togetherWith
        (fadeOut(exitFade) + slideOutHorizontally(exitSlide) { width -> -width / 22 }) using
        SizeTransform(clip = false)
}
