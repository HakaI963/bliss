package com.ivarna.fluxlinux.ui.screens

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivarna.fluxlinux.core.banner.BannerArtWriter
import com.ivarna.fluxlinux.core.banner.NsfwArtFetcher
import com.ivarna.fluxlinux.core.terminal.GuestLoginShell
import com.ivarna.fluxlinux.core.utils.TerminalPreferences
import com.ivarna.fluxlinux.ui.components.FluxSwitch
import com.ivarna.fluxlinux.ui.components.GlassSettingCard
import com.ivarna.fluxlinux.ui.theme.FolioSwitchUncheckedTrack
import com.ivarna.fluxlinux.ui.theme.TextPrimary
import com.ivarna.fluxlinux.ui.theme.fluxMutedText
import com.ivarna.fluxlinux.ui.theme.rememberStaggerEntrance
import com.ivarna.fluxlinux.ui.theme.staggerIn
import kotlin.math.roundToInt

/**
 * Terminal font zoom + ExtraKeys toolbar + guest login shell (nativecode settings parity).
 * Monochrome frost: no accent neon, greys only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalSettingsScreen(
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    var fontSize by remember {
        mutableIntStateOf(TerminalPreferences.getFontSize(context))
    }
    var showExtraKeys by remember {
        mutableStateOf(TerminalPreferences.isExtraKeysEnabled(context))
    }
    var guestShellZsh by remember {
        mutableStateOf(TerminalPreferences.preferZsh(context))
    }
    var legacyProot by remember {
        mutableStateOf(TerminalPreferences.isLegacyProotLauncher(context))
    }
    var bannerQuality by remember {
        mutableIntStateOf(TerminalPreferences.getBannerQuality(context))
    }
    var nsfwEnabled by remember {
        mutableStateOf(TerminalPreferences.isNsfwEnabled(context))
    }
    var nsfwLevel by remember {
        mutableIntStateOf(TerminalPreferences.getNsfwLevel(context))
    }
    var nsfwApiUrl by remember {
        mutableStateOf(TerminalPreferences.getNsfwApiUrl(context))
    }
    var nsfwApiKey by remember {
        mutableStateOf(TerminalPreferences.getNsfwApiKey(context))
    }
    var nsfwCached by remember {
        mutableIntStateOf(NsfwArtFetcher.cachedCount(context))
    }
    var nsfwStatus by remember { mutableStateOf<String?>(null) }
    // Consent gate: the NSFW switch only flips after the 18+ dialog is confirmed.
    var showNsfwConfirm by remember { mutableStateOf(false) }

    // Staggered card entrance (30ms steps) — visual only.
    val entrance = rememberStaggerEntrance()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Terminal",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
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
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            GlassSettingCard(modifier = Modifier.staggerIn(0, entrance)) {
                Column(Modifier.padding(20.dp).fillMaxWidth()) {
                    Text(
                        "Global terminal zoom",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Font size for the embedded terminal (pinch zoom also updates this).",
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = fluxMutedText()
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val canDecrease = fontSize > TerminalPreferences.FONT_MIN
                        IconButton(
                            onClick = {
                                val next = (fontSize - 2).coerceAtLeast(TerminalPreferences.FONT_MIN)
                                fontSize = next
                                TerminalPreferences.setFontSize(context, next)
                            },
                            enabled = canDecrease,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            colors = IconButtonDefaults.iconButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = "Smaller")
                        }
                        Text(
                            "${fontSize} pt",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val canIncrease = fontSize < TerminalPreferences.FONT_MAX
                        IconButton(
                            onClick = {
                                val next = (fontSize + 2).coerceAtMost(TerminalPreferences.FONT_MAX)
                                fontSize = next
                                TerminalPreferences.setFontSize(context, next)
                            },
                            enabled = canIncrease,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            colors = IconButtonDefaults.iconButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Larger")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Range ${TerminalPreferences.FONT_MIN}–${TerminalPreferences.FONT_MAX}",
                        fontSize = 12.sp,
                        color = fluxMutedText(),
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }

            GlassSettingCard(modifier = Modifier.staggerIn(1, entrance)) {
                Row(
                    modifier = Modifier
                        .padding(20.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Extra keyboard rows",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Show CTRL, ALT, ESC, arrows and symbols under the terminal.",
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            color = fluxMutedText()
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    FluxSwitch(
                        checked = showExtraKeys,
                        onCheckedChange = {
                            showExtraKeys = it
                            TerminalPreferences.setExtraKeysEnabled(context, it)
                        }
                    )
                }
            }

            GlassSettingCard(modifier = Modifier.staggerIn(2, entrance)) {
                Row(
                    modifier = Modifier
                        .padding(20.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Guest login shell",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "On = zsh, off = bash. New proot/chroot Terminal sessions only; live tabs keep their shell.",
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            color = fluxMutedText()
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (guestShellZsh) "zsh" else "bash",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    FluxSwitch(
                        checked = guestShellZsh,
                        onCheckedChange = { on ->
                            guestShellZsh = on
                            TerminalPreferences.setGuestLoginShell(
                                context,
                                if (on) GuestLoginShell.ZSH else GuestLoginShell.BASH
                            )
                        },
                        modifier = Modifier.semantics {
                            contentDescription =
                                "Guest login shell: ${if (guestShellZsh) "zsh" else "bash"}"
                        }
                    )
                }
            }

            GlassSettingCard(modifier = Modifier.staggerIn(3, entrance)) {
                Row(
                    modifier = Modifier
                        .padding(20.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Legacy proot launcher",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Open proot terminals through proot-distro (python). Slower; use only if the default launch fails.",
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            color = fluxMutedText()
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    FluxSwitch(
                        checked = legacyProot,
                        onCheckedChange = {
                            legacyProot = it
                            TerminalPreferences.setLegacyProotLauncher(context, it)
                        },
                        modifier = Modifier.semantics {
                            contentDescription = "Legacy proot launcher"
                        }
                    )
                }
            }

            GlassSettingCard(modifier = Modifier.staggerIn(4, entrance)) {
                Column(Modifier.padding(20.dp).fillMaxWidth()) {
                    Text(
                        "Banner art",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Size and detail of the random anime sticker printed by new terminal shells. Applies to every distro.",
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = fluxMutedText()
                    )
                    Spacer(Modifier.height(16.dp))
                    Slider(
                        value = bannerQuality.toFloat(),
                        onValueChange = { bannerQuality = it.roundToInt() },
                        onValueChangeFinished = {
                            TerminalPreferences.setBannerQuality(context, bannerQuality)
                            BannerArtWriter.refreshAsync(context)
                        },
                        valueRange = 1f..5f,
                        steps = 3,
                        colors = SliderDefaults.colors(
                            thumbColor = TextPrimary,
                            activeTrackColor = TextPrimary,
                            inactiveTrackColor = FolioSwitchUncheckedTrack
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        bannerQualityLabel(bannerQuality),
                        fontSize = 12.sp,
                        color = fluxMutedText()
                    )
                }
            }

            GlassSettingCard(modifier = Modifier.staggerIn(5, entrance)) {
                Column(Modifier.padding(20.dp).fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "NSFW banner art",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "18+ only. Replaces the sticker with adult art fetched from a web API. Off by default; turn it off anytime.",
                                fontSize = 13.sp,
                                lineHeight = 20.sp,
                                color = fluxMutedText()
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        FluxSwitch(
                            checked = nsfwEnabled,
                            onCheckedChange = { on ->
                                if (on) {
                                    // Consent gate: persist only after "I am 18+ — enable".
                                    showNsfwConfirm = true
                                } else {
                                    nsfwEnabled = false
                                    nsfwStatus = null
                                    nsfwCached = 0
                                    TerminalPreferences.setNsfwEnabled(context, false)
                                    NsfwArtFetcher.clear(context)
                                    BannerArtWriter.refreshAsync(context)
                                }
                            },
                            modifier = Modifier.semantics {
                                contentDescription = "NSFW banner art"
                            }
                        )
                    }

                    if (nsfwEnabled) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Level",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Slider(
                            value = nsfwLevel.toFloat(),
                            onValueChange = { nsfwLevel = it.roundToInt() },
                            onValueChangeFinished = {
                                TerminalPreferences.setNsfwLevel(context, nsfwLevel)
                                NsfwArtFetcher.refreshAsync(context) { n ->
                                    nsfwCached = n
                                    nsfwStatus = "$n designs cached"
                                }
                                BannerArtWriter.refreshAsync(context)
                            },
                            valueRange = 1f..4f,
                            steps = 2,
                            colors = SliderDefaults.colors(
                                thumbColor = TextPrimary,
                                activeTrackColor = TextPrimary,
                                inactiveTrackColor = FolioSwitchUncheckedTrack
                            )
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            nsfwLevelLabel(nsfwLevel),
                            fontSize = 12.sp,
                            color = fluxMutedText()
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = nsfwApiUrl,
                            onValueChange = {
                                nsfwApiUrl = it
                                TerminalPreferences.setNsfwApiUrl(context, it)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("API URL template (optional)") },
                            placeholder = { Text("https://your-api.example/img?level={level}") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = nsfwApiKey,
                            onValueChange = {
                                nsfwApiKey = it
                                TerminalPreferences.setNsfwApiKey(context, it)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("API key (optional)") },
                            placeholder = { Text("Paste your key") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        TextButton(
                            onClick = {
                                NsfwArtFetcher.refreshAsync(context) { n ->
                                    nsfwCached = n
                                    nsfwStatus = "$n designs cached"
                                }
                                BannerArtWriter.refreshAsync(context)
                            }
                        ) {
                            Text(
                                "Refresh art now",
                                color = MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "$nsfwCached designs cached — " +
                                (if (nsfwApiUrl.isBlank()) "built-in source" else "custom API"),
                            fontSize = 12.sp,
                            color = fluxMutedText()
                        )
                        nsfwStatus?.let { status ->
                            Text(
                                status,
                                fontSize = 12.sp,
                                color = fluxMutedText()
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "{level} is replaced by minimal/moderate/fanservice/full in your URL.",
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = fluxMutedText()
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Saved choices apply on the next refresh; terminal tabs pick them up automatically.",
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = fluxMutedText()
                        )
                    }
                }
            }

            Spacer(Modifier.height(110.dp))
        }
    }

    // 18+ consent gate: the switch stays off until this dialog is confirmed.
    if (showNsfwConfirm) {
        AlertDialog(
            onDismissRequest = { showNsfwConfirm = false },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface,
            title = {
                Text(
                    "Enable NSFW banner art?",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    "18+ adult content fetched from a third-party API source (the built-in " +
                        "source or your own API URL below). It is shown only in the terminal " +
                        "sticker and can be turned off anytime.",
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showNsfwConfirm = false
                        nsfwEnabled = true
                        TerminalPreferences.setNsfwEnabled(context, true)
                        NsfwArtFetcher.refreshAsync(context) { n ->
                            nsfwCached = n
                            nsfwStatus = "$n designs cached"
                        }
                        BannerArtWriter.refreshAsync(context)
                    }
                ) {
                    Text(
                        "I am 18+ — enable",
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showNsfwConfirm = false }) {
                    Text(
                        "Cancel",
                        color = MaterialTheme.colorScheme.secondary,
                        fontSize = 13.sp
                    )
                }
            }
        )
    }
}

/** Banner-art slider stops: label + sticker width in columns (quality 1..5). */
private val BannerQualityStops = listOf(
    "Pixelated" to 24,
    "Small" to 32,
    "Medium" to 40,
    "Large" to 48,
    "Max" to 54
)

/** NSFW level labels, index 0 = level 1. */
private val NsfwLevelLabels = listOf("Minimal", "Moderate", "Fan service", "Full")

private fun bannerQualityLabel(quality: Int): String {
    val (name, columns) = BannerQualityStops[quality.coerceIn(1, BannerQualityStops.size) - 1]
    return "$name — $columns columns"
}

private fun nsfwLevelLabel(level: Int): String =
    NsfwLevelLabels[level.coerceIn(1, NsfwLevelLabels.size) - 1]
