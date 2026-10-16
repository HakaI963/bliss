package com.ivarna.fluxlinux.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivarna.fluxlinux.core.data.Distro
import com.ivarna.fluxlinux.core.theme.AppearanceStore
import com.ivarna.fluxlinux.ui.components.FrostContainer
import com.ivarna.fluxlinux.ui.components.FrostDivider
import com.ivarna.fluxlinux.ui.components.FrostGroupTitle
import com.ivarna.fluxlinux.ui.components.FrostPill
import com.ivarna.fluxlinux.ui.components.FluxSwitch
import com.ivarna.fluxlinux.ui.components.GlassScaffold
import com.ivarna.fluxlinux.ui.theme.FolioDanger
import com.ivarna.fluxlinux.ui.theme.TextMuted
import com.ivarna.fluxlinux.ui.theme.TextPrimary
import com.ivarna.fluxlinux.ui.theme.TextSecondary
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Folio Linux appearance editor for one distro: wallpaper (presets / custom
 * image / solid / random), desktop accent, terminal colors and an optional
 * mpv-based video wallpaper.
 *
 * Rendering only — all state + staging lives in [AppearanceStore]; the guest
 * applies the staged config on the next session (guest script is hash-guarded).
 * Monochrome frost chrome; swatches are the only colored elements.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class)
@Composable
fun DistroAppearanceScreen(
    distro: Distro,
    hazeState: HazeState,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var cfg by remember(distro.id) { mutableStateOf(AppearanceStore.load(context, distro.id)) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusOk by remember { mutableStateOf(true) }
    var applying by remember { mutableStateOf(false) }
    // null = probing, true/false = mpv availability in the guest.
    var mpvAvailable by remember(distro.id) { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(distro.id) {
        mpvAvailable = withContext(Dispatchers.IO) {
            AppearanceStore.isMpvAvailable(context, distro.id)
        }
    }

    val wallpaperPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val name = withContext(Dispatchers.IO) {
                    AppearanceStore.importWallpaper(context, uri)
                }
                if (name != null) {
                    cfg = cfg.copy(
                        wallpaperKind = AppearanceStore.KIND_CUSTOM,
                        wallpaperLocal = name
                    )
                    statusOk = true
                    status = "Image imported — tap Apply to stage it."
                } else {
                    statusOk = false
                    status = "Could not import that image (unsupported type or too large)."
                }
            }
        }
    }

    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val name = withContext(Dispatchers.IO) {
                    AppearanceStore.importVideo(context, uri)
                }
                if (name != null) {
                    cfg = cfg.copy(videoLocal = name, videoEnabled = true)
                    statusOk = true
                    status = "Video imported — tap Apply to stage it."
                } else {
                    statusOk = false
                    status = "Could not import that video (unsupported type or too large)."
                }
            }
        }
    }

    fun applyNow() {
        if (applying) return
        applying = true
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                AppearanceStore.applyAndStage(context, distro.id, cfg)
            }
            applying = false
            statusOk = outcome.ok
            status = outcome.message
        }
    }

    val randomMode = cfg.wallpaperKind == AppearanceStore.KIND_RANDOM
    val selectedRandomIds = remember(cfg.randomIds, cfg.wallpaperKind) {
        if (randomMode) AppearanceStore.randomIdsFor(cfg).toSet()
        else cfg.randomIds.toSet()
    }

    GlassScaffold(
        hazeState = hazeState,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Appearance",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                actions = {
                    Box(modifier = Modifier.padding(end = 12.dp)) {
                        FrostPill(
                            text = if (applying) "Applying…" else "Apply",
                            primary = true,
                            onClick = { applyNow() }
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent
                ),
                modifier = Modifier.hazeChild(
                    state = hazeState,
                    shape = androidx.compose.ui.graphics.RectangleShape,
                    style = HazeMaterials.thin()
                )
            )
        },
        content = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    top = 16.dp,
                    bottom = 48.dp
                )
            ) {
                // ─── Header ───
                item {
                    FrostContainer {
                        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                            Text(
                                "Folio Linux appearance",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Wallpaper, colors and terminal for ${distro.name}. " +
                                    "Staged for the distro — applies on next session.",
                                fontSize = 12.sp,
                                color = TextMuted
                            )
                            val s = status
                            if (s != null) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    s,
                                    fontSize = 12.sp,
                                    color = if (statusOk) TextSecondary else FolioDanger
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                // ─── Wallpaper ───
                item {
                    FrostGroupTitle("Wallpaper")
                    FrostContainer {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            val presets = AppearanceStore.WALLPAPER_PRESETS
                            presets.chunked(3).forEach { rowPresets ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    rowPresets.forEach { preset ->
                                        val selected = when {
                                            randomMode -> preset.id in selectedRandomIds
                                            cfg.wallpaperKind == AppearanceStore.KIND_ASSET ->
                                                cfg.wallpaperPreset == preset.id
                                            else -> false
                                        }
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(12.dp))
                                                .clickable {
                                                    if (randomMode) {
                                                        val next = if (preset.id in selectedRandomIds) {
                                                            selectedRandomIds - preset.id
                                                        } else {
                                                            selectedRandomIds + preset.id
                                                        }
                                                        cfg = cfg.copy(
                                                            randomIds = next.toList()
                                                                .sortedBy { id ->
                                                                    presets.indexOfFirst { it.id == id }
                                                                }
                                                        )
                                                    } else {
                                                        cfg = cfg.copy(
                                                            wallpaperKind = AppearanceStore.KIND_ASSET,
                                                            wallpaperPreset = preset.id
                                                        )
                                                    }
                                                }
                                                .padding(4.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .aspectRatio(0.5625f)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .border(
                                                        width = if (selected) 1.5.dp else 0.75.dp,
                                                        color = if (selected) TextPrimary else Color.White.copy(alpha = 0.10f),
                                                        shape = RoundedCornerShape(10.dp)
                                                    )
                                            ) {
                                                Image(
                                                    painter = painterResource(id = preset.thumbRes),
                                                    contentDescription = preset.label,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            }
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                preset.label,
                                                fontSize = 10.sp,
                                                color = if (selected) TextPrimary else TextMuted,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                    repeat(3 - rowPresets.size) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            }

                            FrostDivider()

                            // Random mode
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 6.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "Random from presets",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = TextPrimary
                                    )
                                    Text(
                                        if (randomMode) "Tapping a preset includes/excludes it"
                                        else "Rotates a preset on every apply",
                                        fontSize = 11.sp,
                                        color = TextMuted
                                    )
                                }
                                FluxSwitch(
                                    checked = randomMode,
                                    onCheckedChange = { on ->
                                        cfg = cfg.copy(
                                            wallpaperKind = if (on) AppearanceStore.KIND_RANDOM
                                            else AppearanceStore.KIND_ASSET,
                                            randomIds = if (on && cfg.randomIds.isEmpty()) {
                                                AppearanceStore.WALLPAPER_PRESETS.map { it.id }
                                            } else cfg.randomIds
                                        )
                                    }
                                )
                            }

                            FrostDivider()

                            // Pick a custom image
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        wallpaperPicker.launch(arrayOf("image/*"))
                                    }
                                    .padding(horizontal = 6.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = null,
                                    tint = TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "Pick image…",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = TextPrimary
                                    )
                                    Text(
                                        if (cfg.wallpaperKind == AppearanceStore.KIND_CUSTOM &&
                                            cfg.wallpaperLocal.isNotBlank()
                                        ) {
                                            "Using ${cfg.wallpaperLocal}"
                                        } else {
                                            "PNG, JPEG, WebP, GIF or BMP"
                                        },
                                        fontSize = 11.sp,
                                        color = TextMuted
                                    )
                                }
                                if (cfg.wallpaperKind == AppearanceStore.KIND_CUSTOM) {
                                    Text("Selected", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                }
                            }

                            FrostDivider()

                            // Solid color (no image)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        cfg = cfg.copy(wallpaperKind = AppearanceStore.KIND_NONE)
                                    }
                                    .padding(horizontal = 6.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "Solid color (no image)",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = TextPrimary
                                    )
                                    Text(
                                        "Carbon ${AppearanceStore.SOLID_COLOR}",
                                        fontSize = 11.sp,
                                        color = TextMuted
                                    )
                                }
                                if (cfg.wallpaperKind == AppearanceStore.KIND_NONE) {
                                    Text("Selected", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                }
                            }

                            FrostDivider()

                            // Image style
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 6.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Style",
                                    fontSize = 13.sp,
                                    color = TextMuted,
                                    modifier = Modifier.weight(1f)
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    StylePill(
                                        "Zoom",
                                        cfg.wallpaperStyle == AppearanceStore.STYLE_ZOOM,
                                        enabled = cfg.wallpaperKind != AppearanceStore.KIND_NONE
                                    ) {
                                        cfg = cfg.copy(wallpaperStyle = AppearanceStore.STYLE_ZOOM)
                                    }
                                    StylePill(
                                        "Stretch",
                                        cfg.wallpaperStyle == AppearanceStore.STYLE_STRETCHED,
                                        enabled = cfg.wallpaperKind != AppearanceStore.KIND_NONE
                                    ) {
                                        cfg = cfg.copy(wallpaperStyle = AppearanceStore.STYLE_STRETCHED)
                                    }
                                    StylePill(
                                        "Center",
                                        cfg.wallpaperStyle == AppearanceStore.STYLE_CENTERED,
                                        enabled = cfg.wallpaperKind != AppearanceStore.KIND_NONE
                                    ) {
                                        cfg = cfg.copy(wallpaperStyle = AppearanceStore.STYLE_CENTERED)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // ─── Colors ───
                item {
                    FrostGroupTitle("Colors")
                    FrostContainer {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                            Text(
                                "Desktop accent",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Tints the GTK selection color and the terminal cursor",
                                fontSize = 11.sp,
                                color = TextMuted
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                AppearanceStore.ACCENT_PRESETS.forEach { accent ->
                                    val selected = cfg.accent == accent.id
                                    val swatch = parseHexColor(accent.hex) ?: TextPrimary
                                    Column(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable { cfg = cfg.copy(accent = accent.id) }
                                            .padding(horizontal = 2.dp, vertical = 4.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(30.dp)
                                                .clip(CircleShape)
                                                .background(swatch)
                                                .border(
                                                    width = if (selected) 2.dp else 0.75.dp,
                                                    color = if (selected) TextPrimary else Color.White.copy(alpha = 0.18f),
                                                    shape = CircleShape
                                                )
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            accent.label,
                                            fontSize = 9.sp,
                                            color = if (selected) TextPrimary else TextMuted,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // ─── Terminal ───
                item {
                    FrostGroupTitle("Terminal")
                    FrostContainer {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                            Text(
                                "Text colors",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "xfce4-terminal foreground / background",
                                fontSize = 11.sp,
                                color = TextMuted
                            )
                            Spacer(Modifier.height(10.dp))
                            val schemes = AppearanceStore.TERMINAL_SCHEMES
                            schemes.chunked(2).forEach { rowSchemes ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    rowSchemes.forEach { scheme ->
                                        SchemeCard(
                                            label = scheme.label,
                                            fg = parseHexColor(scheme.fg) ?: TextPrimary,
                                            bg = parseHexColor(scheme.bg) ?: Color.Black,
                                            cursor = parseHexColor(scheme.cursor) ?: TextSecondary,
                                            selected = cfg.termScheme == scheme.id,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            cfg = cfg.copy(termScheme = scheme.id)
                                        }
                                    }
                                    if (rowSchemes.size == 1) Spacer(modifier = Modifier.weight(1f))
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                            SchemeCard(
                                label = "Custom",
                                fg = parseHexColor(AppearanceStore.normalizeHex(cfg.termFg) ?: "#F4F4F5") ?: TextPrimary,
                                bg = parseHexColor(AppearanceStore.normalizeHex(cfg.termBg) ?: "#09090B") ?: Color.Black,
                                cursor = parseHexColor(AppearanceStore.normalizeHex(cfg.termFg) ?: "#F4F4F5") ?: TextPrimary,
                                selected = cfg.termScheme == "custom",
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                cfg = cfg.copy(termScheme = "custom")
                            }
                            if (cfg.termScheme == "custom") {
                                Spacer(Modifier.height(10.dp))
                                ColorSwatchRow(
                                    label = "Custom text color",
                                    selectedHex = AppearanceStore.normalizeHex(cfg.termFg),
                                    onPick = { cfg = cfg.copy(termFg = it, termScheme = "custom") }
                                )
                                Spacer(Modifier.height(8.dp))
                                ColorSwatchRow(
                                    label = "Custom background color",
                                    selectedHex = AppearanceStore.normalizeHex(cfg.termBg),
                                    onPick = { cfg = cfg.copy(termBg = it, termScheme = "custom") }
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // ─── Video (gated on mpv) ───
                item {
                    FrostGroupTitle("Video")
                    FrostContainer {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "Video wallpaper",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = TextPrimary
                                    )
                                    Text(
                                        when (mpvAvailable) {
                                            null -> "Checking mpv…"
                                            false -> "Requires mpv (not installed)"
                                            true -> "Experimental — overlays the desktop"
                                        },
                                        fontSize = 11.sp,
                                        color = TextMuted
                                    )
                                }
                                FluxSwitch(
                                    checked = mpvAvailable == true && cfg.videoEnabled,
                                    enabled = mpvAvailable == true,
                                    onCheckedChange = { on -> cfg = cfg.copy(videoEnabled = on) }
                                )
                            }
                            if (mpvAvailable == true) {
                                Spacer(Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { videoPicker.launch(arrayOf("video/*")) }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = null,
                                        tint = TextSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "Pick video…",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = TextPrimary
                                        )
                                        Text(
                                            if (cfg.videoLocal.isNotBlank()) "Using ${cfg.videoLocal}"
                                            else "MP4, WebM, MKV or MOV",
                                            fontSize = 11.sp,
                                            color = TextMuted
                                        )
                                    }
                                }
                                Text(
                                    "Plays muted in a loop as an overlay window (mpv) — " +
                                        "not behind desktop icons. Turn off to remove the autostart entry.",
                                    fontSize = 11.sp,
                                    color = TextMuted
                                )
                            } else if (mpvAvailable == false) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Install mpv in ${distro.name} to enable video wallpapers.",
                                    fontSize = 11.sp,
                                    color = TextMuted
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // ─── Reset ───
                item {
                    FrostContainer {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    cfg = AppearanceStore.Config()
                                    statusOk = true
                                    status = "Defaults restored — tap Apply to stage them."
                                }
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Reset to defaults",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = TextPrimary
                                )
                                Text(
                                    "Folio Linux Dark · Mono · Folio Linux Mono",
                                    fontSize = 12.sp,
                                    color = TextMuted,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    )
}

@Composable
private fun StylePill(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    if (enabled) {
        FrostPill(text = label, primary = selected, onClick = onClick)
    } else {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .border(0.75.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(50))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextMuted)
        }
    }
}

/** Terminal scheme preview card: bg swatch + fg "Aa" + cursor block. */
@Composable
private fun SchemeCard(
    label: String,
    fg: Color,
    bg: Color,
    cursor: Color,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = if (selected) 1.5.dp else 0.75.dp,
                color = if (selected) TextPrimary else Color.White.copy(alpha = 0.10f),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(bg),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Aa", color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(14.dp)
                        .background(cursor)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            fontSize = 11.sp,
            color = if (selected) TextPrimary else TextSecondary,
            maxLines = 1
        )
    }
}

/** Small color swatch row (terminal custom FG/BG). */
@Composable
private fun ColorSwatchRow(
    label: String,
    selectedHex: String?,
    onPick: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 12.sp, color = TextMuted, modifier = Modifier.weight(1f))
            Text(selectedHex ?: "—", fontSize = 11.sp, color = TextSecondary)
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            AppearanceStore.CUSTOM_COLORS.forEach { hex ->
                val selected = selectedHex != null &&
                    selectedHex.equals(hex, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(parseHexColor(hex) ?: TextSecondary)
                        .border(
                            width = if (selected) 2.dp else 0.75.dp,
                            color = if (selected) TextPrimary else Color.White.copy(alpha = 0.18f),
                            shape = CircleShape
                        )
                        .clickable { onPick(hex) }
                )
            }
        }
    }
}

/** "#RRGGBB" → Color, null when malformed. Swatch-only helper. */
private fun parseHexColor(hex: String): Color? {
    val v = hex.trim().removePrefix("#")
    if (v.length != 6) return null
    return try {
        val rgb = v.toLong(16)
        Color(
            red = ((rgb shr 16) and 0xFF).toInt() / 255f,
            green = ((rgb shr 8) and 0xFF).toInt() / 255f,
            blue = (rgb and 0xFF).toInt() / 255f,
            alpha = 1f
        )
    } catch (_: Exception) {
        null
    }
}
