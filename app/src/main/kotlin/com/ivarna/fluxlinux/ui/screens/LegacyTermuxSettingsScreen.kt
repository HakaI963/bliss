package com.ivarna.fluxlinux.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.isGranted
import com.ivarna.fluxlinux.core.data.DistroRepository
import com.ivarna.fluxlinux.core.data.ScriptManager
import com.ivarna.fluxlinux.core.desktop.DesktopLauncher
import com.ivarna.fluxlinux.core.legacy.LegacyTermuxBridge
import com.ivarna.fluxlinux.core.legacy.LegacyTermuxStore
import com.ivarna.fluxlinux.core.utils.StateManager
import com.ivarna.fluxlinux.ui.components.FrostContainer
import com.ivarna.fluxlinux.ui.components.FrostDivider
import com.ivarna.fluxlinux.ui.components.FrostGroupTitle
import com.ivarna.fluxlinux.ui.components.FrostPill
import com.ivarna.fluxlinux.ui.screens.storage.DestructivePill
import com.ivarna.fluxlinux.ui.screens.storage.FrostNote
import com.ivarna.fluxlinux.ui.theme.TextMuted
import com.ivarna.fluxlinux.ui.theme.TextPrimary
import com.ivarna.fluxlinux.ui.theme.TextSecondary
import com.ivarna.fluxlinux.ui.theme.rememberStaggerEntrance
import com.ivarna.fluxlinux.ui.theme.staggerIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private const val TAG = "LegacyTermux"
private const val FIX_COMMAND =
    "mkdir -p ~/.termux && echo \"allow-external-apps = true\" >> ~/.termux/termux.properties && termux-reload-settings"

enum class LegacyPageState {
    IDLE,
    SCANNING,
    UNINSTALLING,
    TIMED_OUT
}

enum class LegacyTimeoutType {
    NONE,
    LIST,
    UNINSTALL
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun LegacyTermuxSettingsScreen(
    onBack: () -> Unit,
    permissionState: PermissionState
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scriptManager = remember { ScriptManager(context) }

    var pageState by remember { mutableStateOf(LegacyPageState.IDLE) }
    var activeJobId by remember { mutableLongStateOf(0L) }
    var lastTimeoutType by remember { mutableStateOf(LegacyTimeoutType.NONE) }
    var uninstallTargetId by remember { mutableStateOf<String?>(null) }
    var confirmUninstallRow by remember { mutableStateOf<LegacyTermuxStore.Row?>(null) }
    var showPermissionHelpDialog by remember { mutableStateOf(false) }

    // Lifecycle observer to re-read package state on resume
    var lifecycleKey by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                lifecycleKey++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val termuxInstalled = remember(lifecycleKey) { LegacyTermuxBridge.isTermuxInstalled(context) }
    val termuxVersion = remember(lifecycleKey) { LegacyTermuxBridge.termuxVersionName(context) }
    val termuxVersionOk = remember(lifecycleKey) { LegacyTermuxBridge.isTermuxVersionOk(context) }
    val termuxX11Installed = remember(lifecycleKey) { LegacyTermuxBridge.isTermuxX11Installed(context) }
    val runCommandGranted = remember(lifecycleKey, permissionState.status) {
        LegacyTermuxBridge.hasRunCommandPermission(context) || permissionState.status.isGranted
    }

    val refreshTrigger by StateManager.refreshTrigger.collectAsState()
    val scan = remember(refreshTrigger, lifecycleKey) { LegacyTermuxStore.load(context) }
    val isPingOk = remember(refreshTrigger, lifecycleKey) { LegacyTermuxStore.isPingOk(context) }

    val embeddedDesktopActive = remember(refreshTrigger, lifecycleKey) {
        isEmbeddedDesktopActive(context)
    }

    val isBusy = pageState == LegacyPageState.SCANNING || pageState == LegacyPageState.UNINSTALLING
    val canInteract = termuxInstalled && termuxVersionOk && runCommandGranted && !isBusy

    // Staggered entrance for the header/prereq blocks (30ms steps).
    val entrance = rememberStaggerEntrance()

    fun triggerScan() {
        if (!termuxInstalled || !termuxVersionOk || !runCommandGranted || isBusy) return

        try {
            val script = scriptManager.getScriptContent("legacy-termux/list_proot.sh")
            val b64 = Base64.encodeToString(script.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val spec = LegacyTermuxBridge.buildListSpec(b64)
            val intent = LegacyTermuxBridge.toIntent(spec)
            val launched = LegacyTermuxBridge.startSafely(context, intent)
            if (launched) {
                val jobId = System.currentTimeMillis()
                activeJobId = jobId
                pageState = LegacyPageState.SCANNING
                lastTimeoutType = LegacyTimeoutType.NONE
                coroutineScope.launch {
                    delay(15_000L)
                    if (pageState == LegacyPageState.SCANNING && activeJobId == jobId) {
                        pageState = LegacyPageState.TIMED_OUT
                        lastTimeoutType = LegacyTimeoutType.LIST
                    }
                }
            } else {
                Toast.makeText(context, "Failed to start Termux scan", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Scan trigger failed", e)
            Toast.makeText(context, "Scan error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // Auto-scan on enter / resume whenever gates are satisfied
    LaunchedEffect(lifecycleKey, termuxInstalled, termuxVersionOk, runCommandGranted) {
        if (termuxInstalled && termuxVersionOk && runCommandGranted && pageState == LegacyPageState.IDLE) {
            triggerScan()
        }
    }

    // Track handled scan updates so initial composition does not clear SCANNING
    var lastHandledScanMs by remember { mutableLongStateOf(scan.scannedAtMs) }
    var lastHandledError by remember { mutableStateOf(scan.error) }
    var lastHandledRows by remember { mutableStateOf(scan.rows) }
    var lastHandledActionMs by remember { mutableLongStateOf(scan.lastActionMs) }

    LaunchedEffect(scan.scannedAtMs, scan.error, scan.rows, scan.lastActionMs) {
        val scanChanged = scan.scannedAtMs != lastHandledScanMs || scan.error != lastHandledError
        val rowsChanged = scan.rows != lastHandledRows
        val actionChanged = scan.lastActionMs != lastHandledActionMs

        if (scanChanged || rowsChanged || actionChanged) {
            lastHandledScanMs = scan.scannedAtMs
            lastHandledError = scan.error
            lastHandledRows = scan.rows
            lastHandledActionMs = scan.lastActionMs

            // Any applied callback response (list/uninstall success or error) returns page to IDLE
            if (pageState == LegacyPageState.SCANNING || pageState == LegacyPageState.TIMED_OUT || pageState == LegacyPageState.UNINSTALLING) {
                pageState = LegacyPageState.IDLE
                uninstallTargetId = null
                lastTimeoutType = LegacyTimeoutType.NONE
                activeJobId = 0L
            }
        }
    }

    fun triggerOpenTerminal(row: LegacyTermuxStore.Row) {
        if (!termuxInstalled || !termuxVersionOk || !runCommandGranted || isBusy) return
        if (row.id == "termux") return

        try {
            val script = scriptManager.getScriptContent("legacy-termux/login_proot.sh")
            val b64 = Base64.encodeToString(script.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val spec = LegacyTermuxBridge.buildLoginSpec(b64, row.id) ?: return
            val intent = LegacyTermuxBridge.toIntent(spec)
            val launched = LegacyTermuxBridge.startSafely(context, intent)
            if (launched) {
                val launchIntent = context.packageManager.getLaunchIntentForPackage("com.termux")
                if (launchIntent != null) {
                    context.startActivity(launchIntent)
                }
            } else {
                Toast.makeText(context, "Failed to launch Termux session", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Login failed", e)
            Toast.makeText(context, "Login error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun triggerStartDisplay(row: LegacyTermuxStore.Row) {
        if (!termuxInstalled || !termuxVersionOk || !runCommandGranted || isBusy) return
        if (row.id == "termux") return

        if (embeddedDesktopActive) {
            Toast.makeText(context, "Stop the Bliss desktop first.", Toast.LENGTH_LONG).show()
            return
        }
        if (!termuxX11Installed) {
            Toast.makeText(context, "Termux:X11 is not installed", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val script = scriptManager.getScriptContent("legacy-termux/start_display.sh")
            val b64 = Base64.encodeToString(script.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val spec = LegacyTermuxBridge.buildStartDisplaySpec(b64, row.id) ?: return
            val intent = LegacyTermuxBridge.toIntent(spec)
            val launched = LegacyTermuxBridge.startSafely(context, intent)
            if (!launched) {
                Toast.makeText(context, "Failed to start leftover display", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Start display failed", e)
            Toast.makeText(context, "Start display error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun triggerStopDisplay(row: LegacyTermuxStore.Row) {
        if (!termuxInstalled || !termuxVersionOk || !runCommandGranted || isBusy) return
        if (row.id == "termux") return

        try {
            val script = scriptManager.getScriptContent("legacy-termux/stop_display.sh")
            val b64 = Base64.encodeToString(script.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val spec = LegacyTermuxBridge.buildStopDisplaySpec(b64, row.id) ?: return
            val intent = LegacyTermuxBridge.toIntent(spec)
            val launched = LegacyTermuxBridge.startSafely(context, intent)
            if (!launched) {
                Toast.makeText(context, "Failed to stop leftover display", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Stop display failed", e)
            Toast.makeText(context, "Stop display error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun triggerUninstall(row: LegacyTermuxStore.Row) {
        if (!termuxInstalled || !termuxVersionOk || !runCommandGranted || isBusy) return
        if (row.id == "termux") return

        val targetId = row.id
        try {
            val script = scriptManager.getScriptContent("legacy-termux/uninstall_proot.sh")
            val b64 = Base64.encodeToString(script.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val spec = LegacyTermuxBridge.buildUninstallSpec(b64, targetId) ?: return
            val intent = LegacyTermuxBridge.toIntent(spec)
            val launched = LegacyTermuxBridge.startSafely(context, intent)
            if (launched) {
                val jobId = System.currentTimeMillis()
                activeJobId = jobId
                pageState = LegacyPageState.UNINSTALLING
                uninstallTargetId = targetId
                lastTimeoutType = LegacyTimeoutType.NONE
                coroutineScope.launch {
                    delay(20_000L)
                    if (pageState == LegacyPageState.UNINSTALLING && activeJobId == jobId) {
                        pageState = LegacyPageState.TIMED_OUT
                        lastTimeoutType = LegacyTimeoutType.UNINSTALL
                        Toast.makeText(context, "Termux did not confirm removal; tap Scan.", Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                Toast.makeText(context, "Failed to request Termux uninstall", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Uninstall trigger failed", e)
            Toast.makeText(context, "Uninstall error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun copyAndOpenTermux() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Termux Fix", FIX_COMMAND))
        val launchIntent = context.packageManager.getLaunchIntentForPackage("com.termux")
        if (launchIntent != null) {
            context.startActivity(launchIntent)
            Toast.makeText(context, "Command copied! Paste in Termux", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(context, "Termux not found!", Toast.LENGTH_SHORT).show()
        }
    }

    fun triggerPing() {
        if (!termuxInstalled || !termuxVersionOk || !runCommandGranted || isBusy) return

        try {
            val spec = LegacyTermuxBridge.buildPingSpec()
            val intent = LegacyTermuxBridge.toIntent(spec)
            val launched = LegacyTermuxBridge.startSafely(context, intent)
            if (launched) {
                Toast.makeText(context, "Ping sent to Termux", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Ping failed to start", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ping failed", e)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Legacy Termux",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { triggerScan() },
                        enabled = !isBusy && termuxInstalled && termuxVersionOk && runCommandGranted
                    ) {
                        if (pageState == LegacyPageState.SCANNING) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.secondary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Scan",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header note
            Column(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .staggerIn(0, entrance)
            ) {
                Text(
                    "Leftovers live in com.termux — not app storage",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "These are containers inside the Termux app, not Settings → PRoot.",
                    fontSize = 12.sp,
                    color = TextMuted
                )
            }

            // Prerequisites group
            FrostGroupTitle("Prerequisites", Modifier.staggerIn(1, entrance))
            FrostContainer(modifier = Modifier.staggerIn(2, entrance)) {
                // Row 1: Termux App + Version
                PrereqRow(
                    title = "Termux App",
                    statusText = when {
                        !termuxInstalled -> "Not installed"
                        !termuxVersionOk -> "v${termuxVersion ?: "unknown"} (< v${LegacyTermuxBridge.MIN_TERMUX_VERSION})"
                        else -> "v${termuxVersion} · ready"
                    },
                    isOk = termuxInstalled && termuxVersionOk,
                    ctaText = when {
                        !termuxInstalled -> "Download"
                        !termuxVersionOk -> "Update"
                        else -> null
                    },
                    onCta = {
                        openUrl(context, "https://f-droid.org/packages/com.termux/")
                    }
                )
                if (termuxInstalled && !termuxVersionOk) {
                    FrostDivider()
                    FrostNote("Play Store version will not work. Install v0.118.3 or newer from F-Droid or GitHub releases.")
                }

                FrostDivider()
                // Row 2: Termux:X11
                PrereqRow(
                    title = "Termux:X11",
                    statusText = if (termuxX11Installed) "Installed · ready" else "Not installed",
                    isOk = termuxX11Installed,
                    ctaText = if (!termuxX11Installed) "Get X11" else null,
                    onCta = {
                        openUrl(context, "https://github.com/termux/termux-x11/releases")
                    }
                )

                FrostDivider()
                // Row 3: RUN_COMMAND
                PrereqRow(
                    title = "RUN_COMMAND",
                    statusText = if (runCommandGranted) "Granted" else if (!termuxInstalled) "Termux not installed" else "Denied",
                    isOk = runCommandGranted,
                    ctaText = if (!runCommandGranted) "Grant" else null,
                    onCta = {
                        if (!termuxInstalled) {
                            Toast.makeText(context, "Install Termux first to grant RUN_COMMAND permission", Toast.LENGTH_LONG).show()
                            openUrl(context, "https://f-droid.org/packages/com.termux/")
                        } else {
                            try {
                                permissionState.launchPermissionRequest()
                            } catch (e: Exception) {
                                Log.e(TAG, "launchPermissionRequest failed", e)
                            }
                            showPermissionHelpDialog = true
                        }
                    }
                )

                FrostDivider()
                // Row 4: allow-external-apps
                val isConfigured = isPingOk || (scan.scannedAtMs > 0 && scan.error == null)
                PrereqRow(
                    title = "allow-external-apps",
                    statusText = if (isConfigured) "Configured" else "Unknown · needs fix",
                    isOk = isConfigured,
                    ctaText = null,
                    onCta = null
                )

                // Fix note if not configured or if timed out
                AnimatedVisibility(
                    visible = !isConfigured || pageState == LegacyPageState.TIMED_OUT
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        FrostDivider()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "Fix: allow-external-apps = true",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextSecondary
                            )
                            Text(
                                FIX_COMMAND,
                                color = TextMuted,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FrostPill(
                                    text = "Copy & Open Termux",
                                    onClick = { copyAndOpenTermux() },
                                    primary = true
                                )
                                FrostPill(
                                    text = "Ping",
                                    onClick = { triggerPing() },
                                    modifier = Modifier.alpha(if (canInteract) 1f else 0.4f)
                                )
                            }
                        }
                    }
                }
            }

            // Timed out note
            AnimatedVisibility(visible = pageState == LegacyPageState.TIMED_OUT) {
                FrostContainer {
                    FrostNote(
                        if (lastTimeoutType == LegacyTimeoutType.UNINSTALL) {
                            "Termux did not confirm removal of '${uninstallTargetId ?: "container"}'; tap Scan to check if it was removed."
                        } else {
                            "Termux did not respond. Enable allow-external-apps = true and grant RUN_COMMAND."
                        }
                    )
                }
            }

            // Status / error message
            AnimatedVisibility(visible = scan.error != null) {
                FrostContainer {
                    FrostNote(scan.error ?: "")
                }
            }

            // Discovered leftovers header
            val leftoversTitle = if (scan.scannedAtMs > 0) {
                val ageMinutes = (System.currentTimeMillis() - scan.scannedAtMs) / 60000
                if (ageMinutes <= 0) "Leftover containers · just scanned"
                else "Leftover containers · scanned ${ageMinutes}m ago"
            } else {
                "Leftover containers"
            }
            FrostGroupTitle(leftoversTitle)

            // Rows list or empty states
            if (!termuxInstalled) {
                FrostContainer {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.History,
                            contentDescription = null,
                            tint = TextSecondary.copy(alpha = 0.6f),
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            "Termux not installed",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextPrimary
                        )
                        Text(
                            "The Termux app is not installed. Leftover containers from app versions ≤ v1.8.0 lived there.",
                            fontSize = 12.sp,
                            color = TextMuted,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else if (scan.rows.isEmpty() && scan.scannedAtMs > 0 && scan.error == null) {
                FrostContainer {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            "No leftovers found",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextPrimary
                        )
                        Text(
                            "No leftover PRoot containers found in Termux.",
                            fontSize = 12.sp,
                            color = TextMuted,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                val isScanFreshForUninstall = (System.currentTimeMillis() - scan.scannedAtMs) < 30 * 60 * 1000L

                scan.rows.forEach { row ->
                    LeftoverRowCard(
                        row = row,
                        isBusy = isBusy,
                        isUninstallActive = pageState == LegacyPageState.UNINSTALLING && uninstallTargetId == row.id,
                        termuxX11Installed = termuxX11Installed,
                        embeddedDesktopActive = embeddedDesktopActive,
                        canInteract = canInteract,
                        canUninstall = isScanFreshForUninstall && canInteract,
                        onLoginTerminal = { triggerOpenTerminal(row) },
                        onStartDisplay = { triggerStartDisplay(row) },
                        onStopDisplay = { triggerStopDisplay(row) },
                        onUninstall = {
                            if (!isBusy) {
                                confirmUninstallRow = row
                            }
                        }
                    )
                }
            }

            // Bottom scan pill
            if (termuxInstalled && termuxVersionOk && runCommandGranted) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FrostPill(
                        text = if (pageState == LegacyPageState.SCANNING) "Scanning…" else "Scan leftovers",
                        onClick = { triggerScan() },
                        primary = true,
                        modifier = Modifier.alpha(if (isBusy) 0.5f else 1f)
                    )
                }
            }

            Spacer(Modifier.height(110.dp))
        }
    }

    // Uninstall Confirmation Dialog
    val targetToConfirm = confirmUninstallRow
    if (targetToConfirm != null) {
        val pathText = if (!targetToConfirm.hostPath.isNullOrBlank()) {
            "\n\nPath: ${targetToConfirm.hostPath}"
        } else ""

        AlertDialog(
            onDismissRequest = {
                if (!isBusy) confirmUninstallRow = null
            },
            title = {
                Text(
                    if (targetToConfirm.id == "debian") "Delete Legacy Debian Container?" else "Delete Termux Container?",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            },
            text = {
                val promptText = if (targetToConfirm.id == "debian") {
                    "Delete the legacy Termux container 'debian' (installed by app versions ≤ v1.8)?"
                } else {
                    "Delete the Termux proot-distro container '${targetToConfirm.id}' (not installed by app versions ≤ v1.8)?"
                }
                Text(
                    "$promptText$pathText\n\nThis removes the container from Termux and does not touch Bliss app-storage (Settings → PRoot).",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val toRemove = targetToConfirm
                        confirmUninstallRow = null
                        if (!isBusy) {
                            triggerUninstall(toRemove)
                        }
                    },
                    enabled = !isBusy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("Uninstall", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmUninstallRow = null },
                    enabled = !isBusy
                ) {
                    Text("Cancel", color = MaterialTheme.colorScheme.secondary)
                }
            }
        )
    }

    // Permission Help Dialog (Android 11+ / MIUI custom dangerous permission guidance)
    if (showPermissionHelpDialog && !runCommandGranted) {
        AlertDialog(
            onDismissRequest = { showPermissionHelpDialog = false },
            title = {
                Text(
                    "Grant RUN_COMMAND",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "On modern Android, custom permissions like RUN_COMMAND must be enabled manually in App Info → Permissions → Additional Permissions / All Permissions.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Or grant via ADB:\npm grant ${context.packageName} com.termux.permission.RUN_COMMAND",
                        fontSize = 11.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPermissionHelpDialog = false
                        try {
                            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to open app settings", e)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary
                    )
                ) {
                    Text("Open App Info", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionHelpDialog = false }) {
                    Text("Dismiss", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }
}

@Composable
private fun PrereqRow(
    title: String,
    statusText: String,
    isOk: Boolean,
    ctaText: String?,
    onCta: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            Text(
                statusText,
                fontSize = 12.sp,
                color = if (isOk) TextSecondary else TextMuted
            )
        }
        if (ctaText != null && onCta != null) {
            Spacer(Modifier.width(8.dp))
            FrostPill(text = ctaText, onClick = onCta)
        }
    }
}

@Composable
private fun LeftoverRowCard(
    row: LegacyTermuxStore.Row,
    isBusy: Boolean,
    isUninstallActive: Boolean,
    termuxX11Installed: Boolean,
    embeddedDesktopActive: Boolean,
    canInteract: Boolean,
    canUninstall: Boolean,
    onLoginTerminal: () -> Unit,
    onStartDisplay: () -> Unit,
    onStopDisplay: () -> Unit,
    onUninstall: () -> Unit
) {
    val isDebianLeftover = row.id == "debian"
    val isNativeTermux = row.id == "termux"

    FrostContainer {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(TextSecondary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.History,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            row.id,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextPrimary
                        )
                        Text(
                            formatBytes(row.bytes),
                            fontSize = 12.sp,
                            color = TextMuted
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = when {
                        isDebianLeftover -> "v1.8 LEFTOVER"
                        isNativeTermux -> "NATIVE"
                        else -> "OTHER TERMUX CONTAINER"
                    },
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (isDebianLeftover) TextSecondary else TextMuted,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(TextSecondary.copy(alpha = 0.10f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }

            // Path line
            if (!row.hostPath.isNullOrBlank()) {
                Text(
                    row.hostPath,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextMuted
                )
            }

            // Muted note (conflict / native / start guidance)
            Text(
                when {
                    embeddedDesktopActive -> "Stop the Bliss desktop first. Leftover Termux:X11 and embedded XFCE should not run together (Pulse TCP 127.0.0.1:4713)."
                    isNativeTermux -> "Termux native container management is not supported."
                    isDebianLeftover -> "Start: XFCE in Termux:X11 (v1.8 protocol). KDE leftovers are not supported."
                    else -> "Termux container (not installed by app versions ≤ v1.8). Start: XFCE in Termux:X11 if installed inside container."
                },
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = TextMuted
            )

            // Pill actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FrostPill(
                    text = "Open terminal",
                    onClick = onLoginTerminal,
                    modifier = Modifier
                        .weight(1f)
                        .alpha(if (canInteract && !isNativeTermux) 1f else 0.4f)
                )
                FrostPill(
                    text = "Start display",
                    onClick = onStartDisplay,
                    modifier = Modifier
                        .weight(1f)
                        .alpha(if (canInteract && termuxX11Installed && !embeddedDesktopActive && !isNativeTermux) 1f else 0.4f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FrostPill(
                    text = "Stop display",
                    onClick = onStopDisplay,
                    modifier = Modifier
                        .weight(1f)
                        .alpha(if (canInteract && !isNativeTermux) 1f else 0.4f)
                )
                DestructivePill(
                    text = if (isUninstallActive) "Removing…" else "Uninstall",
                    onClick = onUninstall,
                    enabled = canUninstall && !isNativeTermux && !isBusy,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

private fun isEmbeddedDesktopActive(ctx: Context): Boolean {
    return DesktopLauncher.isSessionActive() ||
        DistroRepository.supportedDistros.any {
            StateManager.isGuiRunning(ctx, it.id)
        }
}

private fun formatBytes(bytes: Long?): String {
    if (bytes == null || bytes <= 0L) return "Size unknown"
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    if (gb >= 1.0) return String.format(Locale.US, "%.1f GB", gb)
    val mb = bytes / (1024.0 * 1024.0)
    if (mb >= 1.0) return String.format(Locale.US, "%.1f MB", mb)
    val kb = bytes / 1024.0
    return String.format(Locale.US, "%.1f KB", kb)
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        Log.e(TAG, "Error opening URL: $url", e)
    }
}
