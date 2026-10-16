package com.ivarna.fluxlinux.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.system.Os
import android.system.OsConstants
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ivarna.fluxlinux.R
import com.ivarna.fluxlinux.core.terminal.FluxTerminalSessionManager
import com.ivarna.fluxlinux.core.terminal.FluxTerminalSessionManager.SessionOpenResult
import com.ivarna.fluxlinux.core.utils.TerminalPreferences
import com.ivarna.fluxlinux.ui.terminal.AnimeSplashArt
import com.ivarna.fluxlinux.ui.terminal.ShortcutBarActions
import com.ivarna.fluxlinux.ui.terminal.ShortcutBarConfig
import com.ivarna.fluxlinux.ui.terminal.ShortcutBarEditorSheet
import com.ivarna.fluxlinux.ui.terminal.ShortcutBarStore
import com.ivarna.fluxlinux.ui.terminal.ShortcutKeyEditorSheet
import com.ivarna.fluxlinux.ui.terminal.TerminalExtraKeys
import com.ivarna.fluxlinux.ui.terminal.TerminalKeyInjector
import com.ivarna.fluxlinux.ui.terminal.TerminalModifierState
import com.ivarna.fluxlinux.ui.terminal.TerminalNewSessionPanel
import com.ivarna.fluxlinux.ui.terminal.TerminalSessionSplash
import com.ivarna.fluxlinux.ui.terminal.TerminalShortcutKey
import com.ivarna.fluxlinux.ui.terminal.TerminalToolSelector
import com.ivarna.fluxlinux.ui.terminal.TerminalToolsSheet
import com.ivarna.fluxlinux.ui.terminal.newShortcutKeyId
import com.ivarna.fluxlinux.ui.theme.FolioBackground
import com.ivarna.fluxlinux.ui.theme.FrostHairline
import com.ivarna.fluxlinux.ui.theme.FrostSurface
import com.ivarna.fluxlinux.ui.theme.TextPrimary
import com.ivarna.fluxlinux.ui.theme.TextSecondary
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * In-app terminal with multi-session tabs + nativecode-style tool selector grid
 * + full ExtraKeys toolbar (injectKey / ModifierState lock).
 *
 * Empty state = compact proot/chroot session rows (`TerminalToolSelector`); active state =
 * interactive `TerminalView` (focus + IME + clipboard) with a compact, fully
 * configurable ExtraKeys toolbar. Plan: docs/plans/terminal-grid-extrakeys-interactive.md.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    onBack: (() -> Unit)? = null,
    embeddedInBottomNav: Boolean = false
) {
    val context = LocalContext.current
    val activeIndex by FluxTerminalSessionManager.activeIndex.collectAsState()
    val sessionRevision by FluxTerminalSessionManager.revision.collectAsState()
    val titles = remember(sessionRevision) { FluxTerminalSessionManager.titles() }
    val sessions = remember(sessionRevision) { FluxTerminalSessionManager.sessions() }

    // Modifier state shared between TerminalViewClient (read side) and ExtraKeys (UI).
    val modState = remember { TerminalModifierState() }
    var fontSize by remember {
        mutableIntStateOf(TerminalPreferences.getFontSize(context))
    }
    var showExtraKeys by remember {
        mutableStateOf(TerminalPreferences.isExtraKeysEnabled(context))
    }
    var terminalViewRef by remember { mutableStateOf<TerminalView?>(null) }
    // "+" opens an anchored in-place panel (no modal sheet → no dismiss/tap race).
    var showNewSessionPanel by remember { mutableStateOf(false) }
    var showToolsSheet by remember { mutableStateOf(false) }
    var shortcutConfig by remember { mutableStateOf(ShortcutBarStore.load(context)) }
    var showShortcutEditor by remember { mutableStateOf(false) }
    var showShortcutKeyEditor by remember { mutableStateOf(false) }
    var editingShortcutKey by remember { mutableStateOf<TerminalShortcutKey?>(null) }
    // Launch-sticker splash: shown when the screen opens with a live session and
    // whenever a NEW session lands — not on plain switches between existing tabs.
    var splashArtRes by remember { mutableIntStateOf(0) }
    var splashTitle by remember { mutableStateOf("") }
    // Session count at the last splash; -1 forces the splash on first show.
    var splashBudget by remember { mutableIntStateOf(-1) }
    // Avoid SIGWINCH spam on every Compose recomposition — only when the emulator's
    // cols/rows or the attached pid actually change (R2: pinch zoom changes cols/rows
    // without a view-size change, so pixel width/height is NOT a valid guard key).
    val lastWinchKey = remember { intArrayOf(-1, -1, -1) } // cols, rows, pid
    val tabListState = rememberLazyListState()

    // Reload prefs when returning from Settings (Terminal / X11 detail pages).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                fontSize = TerminalPreferences.getFontSize(context)
                showExtraKeys = TerminalPreferences.isExtraKeysEnabled(context)
                shortcutConfig = ShortcutBarStore.load(context)
                terminalViewRef?.setTextSize(fontSize)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Back closes the panel first; the panel also collapses itself once a session
    // lands (and whenever no session remains — the empty-state selector is right there).
    BackHandler(enabled = showNewSessionPanel) { showNewSessionPanel = false }
    LaunchedEffect(sessions.isEmpty()) {
        if (sessions.isEmpty()) showNewSessionPanel = false
    }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    /** Single pty write path for TEXT keys + clipboard paste (bracketed-aware). */
    fun sendText(text: String) {
        if (text.isEmpty()) return
        val session = FluxTerminalSessionManager.activeSession?.session
        if (session == null) {
            toast("No active session")
            return
        }
        try {
            session.emulator?.paste(text) ?: session.write(text)
        } catch (_: Exception) {
            toast("Write failed")
        }
    }

    fun openCard(
        type: String,
        title: String,
        method: String,
        distroId: String? = null,
        onOpened: (() -> Unit)? = null
    ) {
        // The "+" panel and the empty-state selector always create a NEW tab
        // (reuseExisting = false) — multiple shells of one distro are allowed.
        // The Home "Shell"/"Open" paths keep the default reuse behavior instead.
        if (method == "host") {
            // R4: gate on host ready — prepare when missing, toast "Host not ready".
            FluxTerminalSessionManager.openHostShellAfterReady(
                context, title, reuseExisting = false
            ) { result ->
                when (result) {
                    SessionOpenResult.OPENED -> onOpened?.invoke()
                    SessionOpenResult.MAX_TABS -> toast("Tab limit reached (${FluxTerminalSessionManager.MAX_TABS})")
                    SessionOpenResult.HOST_NOT_READY -> toast("Host not ready — check Settings")
                    else -> toast("Host shell failed to open")
                }
            }
            return
        }
        FluxTerminalSessionManager.openSessionAfterHost(
            context,
            type = type,
            title = title,
            method = method,
            distroId = distroId,
            reuseExisting = false,
            onResult = { result ->
                when (result) {
                    SessionOpenResult.OPENED -> onOpened?.invoke()
                    SessionOpenResult.MAX_TABS -> toast("Tab limit reached (${FluxTerminalSessionManager.MAX_TABS})")
                    SessionOpenResult.HOST_PREPARE_FAILED -> toast("Host bootstrap not ready — check Settings")
                    SessionOpenResult.OPEN_FAILED ->
                        toast(if (method == "chroot") "Chroot session failed to open" else "Session failed to open")
                    SessionOpenResult.HOST_NOT_READY -> toast("Host not ready — check Settings")
                }
            }
        )
    }

    /** Nativecode `forceTerminalResize` parity — post to view; SIGWINCH on cols/rows change. */
    fun forceTerminalResize(tv: TerminalView) {
        tv.post {
            try {
                if (tv.width <= 0 || tv.height <= 0) return@post
                tv.updateSize()
                tv.onScreenUpdated()
                val session = tv.currentSession ?: return@post
                val emulator = session.emulator ?: return@post
                if (!session.isRunning) return@post
                val cols = emulator.mColumns
                val rows = emulator.mRows
                val pid = session.pid
                if (pid > 0 &&
                    (cols != lastWinchKey[0] || rows != lastWinchKey[1] || pid != lastWinchKey[2])
                ) {
                    lastWinchKey[0] = cols
                    lastWinchKey[1] = rows
                    lastWinchKey[2] = pid
                    runCatching { Os.kill(pid, OsConstants.SIGWINCH) }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun injectKey(key: String, ctrl: Boolean = false) {
        TerminalKeyInjector.injectKey(terminalViewRef, key, ctrl, false, false)
    }

    /** Paste via the emulator when available (bracketed-paste aware), else raw pty write. */
    fun pasteFromClipboard() {
        val text = try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        } catch (_: Exception) {
            null
        }
        if (text.isNullOrEmpty()) {
            toast("Clipboard is empty")
            return
        }
        if (text.length > 100_000) {
            toast("Clipboard too large to paste")
            return
        }
        sendText(text)
    }

    /** Copy the scrollback tail (transcript) to the clipboard — "Copy screen" tool. */
    fun copyTranscript() {
        val text = try {
            FluxTerminalSessionManager.activeSession?.session?.emulator?.screen
                ?.getTranscriptText()
        } catch (_: Exception) {
            null
        }
        if (text.isNullOrBlank()) {
            toast("Nothing to copy")
            return
        }
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("terminal", text.take(200_000)))
            toast("Copied")
        } catch (_: Exception) {
            toast("Copy failed")
        }
    }

    fun applyFontSize(requested: Int) {
        val next = requested.coerceIn(TerminalPreferences.FONT_MIN, TerminalPreferences.FONT_MAX)
        if (next == fontSize) return
        fontSize = next
        TerminalPreferences.setFontSize(context, next)
        terminalViewRef?.setTextSize(next)
        terminalViewRef?.let { forceTerminalResize(it) }
    }

    fun toggleExtraKeys() {
        val next = !showExtraKeys
        showExtraKeys = next
        TerminalPreferences.setExtraKeysEnabled(context, next)
        terminalViewRef?.let { forceTerminalResize(it) }
    }

    fun updateShortcutConfig(config: ShortcutBarConfig) {
        shortcutConfig = config
        ShortcutBarStore.save(context, config)
    }

    // Splash trigger: entry into a live terminal (0→n) or a newly opened session
    // (n→n+1). Plain tab switches keep the same count and stay silent.
    LaunchedEffect(sessions.size, activeIndex) {
        if (sessions.isNotEmpty() && splashBudget < sessions.size) {
            splashBudget = sessions.size
            splashArtRes = AnimeSplashArt.random()
            splashTitle = FluxTerminalSessionManager.activeSession?.title
                ?: titles.getOrNull(activeIndex)
                ?: "session"
        }
    }

    // Keep the active tab visible in the session strip.
    LaunchedEffect(activeIndex, sessions.size) {
        if (activeIndex in sessions.indices) {
            tabListState.animateScrollToItem(activeIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(
                WindowInsets.ime.union(WindowInsets.navigationBars)
                    .only(WindowInsetsSides.Bottom)
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                FilledTonalIconButton(
                    onClick = {
                        terminalViewRef?.let { com.ivarna.fluxlinux.ui.terminal.hideIme(it) }
                        onBack.invoke()
                    },
                    modifier = Modifier.size(36.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Text(
                text = "Terminal",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 8.dp)
            )
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = { toggleExtraKeys() },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = if (showExtraKeys) Icons.Default.Keyboard else Icons.Default.KeyboardHide,
                    contentDescription = if (showExtraKeys) "Hide extra keys" else "Show extra keys",
                    tint = if (showExtraKeys) TextPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(
                onClick = { showToolsSheet = true },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Build,
                    contentDescription = "Terminal tools",
                    tint = TextPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(
                onClick = { showNewSessionPanel = !showNewSessionPanel },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = if (showNewSessionPanel) Icons.Default.Close else Icons.Default.Add,
                    contentDescription = if (showNewSessionPanel) "Close new session panel" else "New session",
                    tint = TextPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // ── Session strip: pill tabs (icon + name, integrated close) ─────────
        if (sessions.isNotEmpty()) {
            LazyRow(
                state = tabListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                contentPadding = PaddingValues(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    count = sessions.size,
                    key = { i -> "tab-$i" }
                ) { i ->
                    val managed = sessions[i]
                    TerminalSessionTab(
                        label = compactSessionLabel(managed.title, managed.type, managed.method),
                        iconRes = managed.iconRes ?: R.drawable.ic_terminal,
                        selected = i == activeIndex,
                        onSelect = { FluxTerminalSessionManager.switchSession(i) },
                        onClose = { FluxTerminalSessionManager.closeSession(context, i) }
                    )
                }
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (activeIndex >= 0) {
                AndroidView(
                    factory = { ctx ->
                        TerminalView(ctx, null).apply {
                            setTextSize(fontSize)
                            try {
                                val tf = Typeface.createFromAsset(ctx.assets, "fonts/font.ttf")
                                setTypeface(tf)
                            } catch (_: Exception) {
                            }
                            setTerminalViewClient(object : TerminalViewClient {
                                override fun onScale(scale: Float): Float {
                                    if (scale < 0.9f || scale > 1.1f) {
                                        val next = (fontSize * scale).toInt().coerceIn(
                                            TerminalPreferences.FONT_MIN,
                                            TerminalPreferences.FONT_MAX
                                        )
                                        fontSize = next
                                        TerminalPreferences.setFontSize(ctx, next)
                                        setTextSize(next)
                                        return 1.0f
                                    }
                                    return scale
                                }

                                // T3: tap → focus + IME (SHOW_FORCED fallback for OEMs).
                                override fun onSingleTapUp(e: android.view.MotionEvent) {
                                    requestFocus()
                                    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                    if (!imm.showSoftInput(this@apply, InputMethodManager.SHOW_IMPLICIT)) {
                                        imm.showSoftInput(this@apply, InputMethodManager.SHOW_FORCED)
                                    }
                                }

                                override fun shouldBackButtonBeMappedToEscape(): Boolean = false
                                override fun shouldEnforceCharBasedInput(): Boolean = false
                                override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
                                override fun isTerminalViewSelected(): Boolean = true
                                override fun copyModeChanged(active: Boolean) {}
                                // Let the view handle keys — never swallow (nativecode parity).
                                override fun onKeyDown(keyCode: Int, event: KeyEvent, session: com.termux.terminal.TerminalSession): Boolean = false
                                override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = false
                                override fun onLongPress(e: android.view.MotionEvent): Boolean = false
                                // Modifiers read from shared lockable state (ModifierState parity).
                                override fun readControlKey(): Boolean = modState.readCtrl(true)
                                override fun readAltKey(): Boolean = modState.readAlt(true)
                                override fun readShiftKey(): Boolean = modState.readShift(true)
                                override fun readFnKey(): Boolean = modState.readFn(true)
                                override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: com.termux.terminal.TerminalSession): Boolean = false
                                override fun onEmulatorSet() {}
                                override fun logError(tag: String, message: String) {}
                                override fun logWarn(tag: String, message: String) {}
                                override fun logInfo(tag: String, message: String) {}
                                override fun logDebug(tag: String, message: String) {}
                                override fun logVerbose(tag: String, message: String) {}
                                override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
                                override fun logStackTrace(tag: String, e: Exception) {}
                            })
                            terminalViewRef = this
                        }
                    },
                    update = { view ->
                        terminalViewRef = view
                        // Keep text size in sync with global prefs (settings page / pinch).
                        view.setTextSize(fontSize)
                        // T3: focus parity — re-attach + focus on every recompose/switch so a
                        // freshly opened or switched session never shows a blank/dead view.
                        view.isFocusable = true
                        view.isFocusableInTouchMode = true
                        // T3/proot-opt-01: only re-attach + re-focus when the session
                        // actually changed (new tab / switched tab). Typing, font-size,
                        // extra-keys and other recompositions must NOT re-attach the
                        // same TerminalSession or steal focus every frame.
                        val activeTerminalSession = FluxTerminalSessionManager.activeSession?.session
                        if (activeTerminalSession == null || view.currentSession != activeTerminalSession) {
                            FluxTerminalSessionManager.attachView(view)
                            view.requestFocus()
                        }
                        // T5: resize + SIGWINCH only on real size/pid change.
                        forceTerminalResize(view)
                    },
                    modifier = Modifier.fillMaxSize()
                )
                DisposableEffect(Unit) {
                    onDispose {
                        terminalViewRef?.let { com.ivarna.fluxlinux.ui.terminal.hideIme(it) }
                        FluxTerminalSessionManager.detachView()
                        terminalViewRef = null
                    }
                }
            } else {
                // Empty state: installed-only session rows with the original icons.
                TerminalToolSelector(
                    onOpen = { type, title, method, distroId ->
                        openCard(type, title, method, distroId)
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Launch-sticker splash (random anime art per open); removed once faded
            // or tapped, so it never intercepts input afterwards.
            if (splashArtRes != 0) {
                TerminalSessionSplash(
                    title = splashTitle,
                    artRes = splashArtRes,
                    onFinished = { splashArtRes = 0 }
                )
            }

            // ── "+" new-session panel: anchored dropdown, dismiss via ×/back/scrim ──
            if (showNewSessionPanel) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { showNewSessionPanel = false }
                )
                TerminalNewSessionPanel(
                    onOpen = { type, title, method, distroId ->
                        openCard(type, title, method, distroId) {
                            showNewSessionPanel = false
                        }
                    },
                    onDismiss = { showNewSessionPanel = false },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        // ExtraKeys toolbar: nativecode inject path (TerminalView.onKeyDown / inputCodePoint).
        if (activeIndex >= 0 && showExtraKeys) {
            TerminalExtraKeys(
                modState = modState,
                terminalView = { terminalViewRef },
                config = shortcutConfig,
                modifier = Modifier.fillMaxWidth(),
                onSendText = { sendText(it) },
                actions = ShortcutBarActions(
                    onEditKey = { key ->
                        editingShortcutKey = key
                        showShortcutKeyEditor = true
                    },
                    onDuplicateKey = { key ->
                        val index = shortcutConfig.keys.indexOfFirst { it.id == key.id }
                        val copy = key.copy(id = newShortcutKeyId())
                        val keys = shortcutConfig.keys.toMutableList().apply {
                            if (index >= 0) add(index + 1, copy) else add(copy)
                        }
                        updateShortcutConfig(shortcutConfig.copy(keys = keys))
                    },
                    onRemoveKey = { key ->
                        updateShortcutConfig(
                            shortcutConfig.copy(keys = shortcutConfig.keys.filterNot { it.id == key.id })
                        )
                    },
                    onEditBar = { showShortcutEditor = true }
                )
            )
        }
    }

    // Tools sheet — QoL actions bound to the existing inject/session APIs.
    if (showToolsSheet) {
        val activeManaged = FluxTerminalSessionManager.activeSession
        TerminalToolsSheet(
            sessionTitle = activeManaged?.title,
            hasSession = activeIndex >= 0 && activeManaged != null,
            sessionCount = sessions.size,
            extraKeysVisible = showExtraKeys,
            fontSize = fontSize,
            canRestart = FluxTerminalSessionManager.isRestartable(activeManaged),
            fontMin = TerminalPreferences.FONT_MIN,
            fontMax = TerminalPreferences.FONT_MAX,
            onDismissRequest = { showToolsSheet = false },
            onPaste = { pasteFromClipboard() },
            onCopyScreen = { copyTranscript() },
            onInjectKey = { key, ctrl -> injectKey(key, ctrl) },
            onRestart = {
                val idx = activeIndex
                if (idx >= 0) {
                    FluxTerminalSessionManager.restartSession(context, idx) { result ->
                        if (result != SessionOpenResult.OPENED) toast("Session restart failed")
                    }
                }
            },
            onFontSizeChange = { delta -> applyFontSize(fontSize + delta) },
            onToggleExtraKeys = { toggleExtraKeys() },
            onCloseSession = {
                val idx = activeIndex
                if (idx >= 0) FluxTerminalSessionManager.closeSession(context, idx)
            },
            onCloseAllSessions = { FluxTerminalSessionManager.closeAll(context) },
            onAddCustomKey = {
                showToolsSheet = false
                editingShortcutKey = null
                showShortcutKeyEditor = true
            },
            onEditShortcutBar = {
                showToolsSheet = false
                showShortcutEditor = true
            }
        )
    }

    // Shortcut bar manager (order / size / edit / delete / reset + live persistence).
    if (showShortcutEditor) {
        ShortcutBarEditorSheet(
            config = shortcutConfig,
            onConfigChange = { updateShortcutConfig(it) },
            onDismiss = { showShortcutEditor = false }
        )
    }

    // Custom key creator (from the bar editor, the bar's long-press menu, or tools).
    if (showShortcutKeyEditor) {
        ShortcutKeyEditorSheet(
            initial = editingShortcutKey,
            onSave = { key ->
                val editing = editingShortcutKey
                val keys = if (editing == null) {
                    shortcutConfig.keys + key
                } else {
                    shortcutConfig.keys.map { if (it.id == editing.id) key else it }
                }
                updateShortcutConfig(shortcutConfig.copy(keys = keys))
                showShortcutKeyEditor = false
                editingShortcutKey = null
            },
            onDismiss = {
                showShortcutKeyEditor = false
                editingShortcutKey = null
            }
        )
    }
}

/**
 * One session tab: frosted pill, white inverted when active (dock parity), spring
 * animated; integrated × with a >= 24dp touch target.
 */
@Composable
private fun TerminalSessionTab(
    label: String,
    iconRes: Int,
    selected: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit
) {
    val shape = RoundedCornerShape(percent = 50)
    val background by animateColorAsState(
        targetValue = if (selected) TextPrimary else FrostSurface,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 380f),
        label = "sessionTabBackground"
    )
    val foreground by animateColorAsState(
        targetValue = if (selected) FolioBackground else TextSecondary,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 380f),
        label = "sessionTabForeground"
    )
    val edge by animateColorAsState(
        targetValue = if (selected) TextPrimary else FrostHairline,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 380f),
        label = "sessionTabEdge"
    )

    Row(
        modifier = Modifier
            .height(38.dp)
            .clip(shape)
            .background(background)
            .border(BorderStroke(0.75.dp, edge), shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onSelect
            )
            .padding(start = 9.dp, end = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(id = iconRes),
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = foreground,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 110.dp)
        )
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close $label",
                tint = foreground,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/** "Alpine Shell Rooted" → "Alpine root", "Alpine Chroot Shell" → "Alpine chroot". */
private fun compactSessionLabel(title: String, type: String, method: String): String {
    val base = title
        .replace(" Shell Rooted", "")
        .replace(" Rooted", "")
        .replace(" Chroot Shell", "")
        .replace(" Chroot", "")
        .replace(" Shell", "")
        .trim()
        .ifBlank { "session" }
    val suffix = when {
        type == "shell-root" -> " root"
        method == "chroot" -> " chroot"
        else -> ""
    }
    val label = base + suffix
    return if (label.length <= 16) label else label.take(15) + "…"
}
