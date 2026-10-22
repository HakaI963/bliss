package com.altaris.linux.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import kotlinx.coroutines.withContext
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.altaris.linux.core.data.Distro
import com.altaris.linux.core.data.DistroRepository
import com.altaris.linux.core.terminal.TerminalLauncher
import com.altaris.linux.core.utils.StateManager
import com.altaris.linux.ui.components.AltarisTitle
import com.altaris.linux.ui.components.MethodTab
import com.altaris.linux.ui.components.PrismButton
import com.altaris.linux.ui.components.PrismCard
import com.altaris.linux.ui.components.PrismChip
import com.altaris.linux.ui.components.PrismRow
import com.altaris.linux.ui.components.PrismSectionLabel
import com.altaris.linux.ui.components.PrismSegmented
import com.altaris.linux.ui.theme.AltarisSection
import com.altaris.linux.ui.theme.Prism
import com.altaris.linux.ui.theme.altarisMutedText
import com.altaris.linux.ui.theme.rememberStaggerEntrance
import com.altaris.linux.ui.theme.sectionAccent
import com.altaris.linux.ui.theme.staggerIn
import dev.chrisbanes.haze.HazeState

private enum class DistroCategory(val label: String) {
    ALL("All"),
    RECOMMENDED("Recommended"),
    LIGHTWEIGHT("Lightweight"),
    SECURITY("Security"),
    DESKTOP_ROLLING("Desktop/Rolling")
}

private fun isRecommendedDistro(distro: Distro): Boolean {
    val id = distro.id.lowercase()
    return listOf("debian", "ubuntu", "archlinux", "fedora").any { id.contains(it) }
}

private fun isLightweightDistro(distro: Distro): Boolean {
    val id = distro.id.lowercase()
    val desc = distro.description.lowercase()
    if (listOf("alpine", "chimera", "void").any { id.contains(it) }) return true
    return desc.contains("lightweight") || desc.contains("musl") || desc.contains("apk")
}

private fun isSecurityDistro(distro: Distro): Boolean {
    val id = distro.id.lowercase()
    if (listOf("kali", "parrot").any { id.contains(it) }) return true
    return distro.components.any {
        it.id.contains("cybersec", ignoreCase = true) ||
            it.name.contains("cybersec", ignoreCase = true) ||
            it.id.contains("cyber", ignoreCase = true)
    }
}

private fun isDesktopRollingDistro(distro: Distro): Boolean {
    val id = distro.id.lowercase()
    val desc = distro.description.lowercase()
    if (desc.contains("rolling") || desc.contains("tumbleweed") || desc.contains("xfce")) return true
    return listOf("kali", "parrot", "archlinux", "opensuse", "manjaro").any { id.contains(it) }
}

private fun Distro.matchesCategory(category: DistroCategory): Boolean = when (category) {
    DistroCategory.ALL -> true
    DistroCategory.RECOMMENDED -> isRecommendedDistro(this)
    DistroCategory.LIGHTWEIGHT -> isLightweightDistro(this)
    DistroCategory.SECURITY -> isSecurityDistro(this)
    DistroCategory.DESKTOP_ROLLING -> isDesktopRollingDistro(this)
}

/**
 * Small factual badges derived from the existing category flags — never new
 * data. Capped at two so cards stay calm.
 */
private fun distroBadgeLabels(distro: Distro): List<String> = buildList {
    if (isRecommendedDistro(distro)) add("Recommended")
    if (isLightweightDistro(distro)) add("Lightweight")
    if (isSecurityDistro(distro)) add("Security")
    if (isDesktopRollingDistro(distro)) add("Rolling")
}.take(2)

@OptIn(ExperimentalPermissionsApi::class, ExperimentalFoundationApi::class)
@Composable
fun DistroScreen(
    permissionState: PermissionState,
    hazeState: HazeState,
    onStartService: (android.content.Intent) -> Unit,
    onStartActivity: (android.content.Intent) -> Unit,
    onNavigateToInstall: (com.altaris.linux.core.data.Distro) -> Unit
) {
    val context = LocalContext.current

    val stateRefresh by StateManager.refreshTrigger.collectAsState()

    // Refresh mechanism to check install status
    val refreshKey = remember { mutableStateOf(0) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                refreshKey.value++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Installed state = filesystem truth (plan P4-T13); the browse page now keeps
    // installed cards visible with a chip, so a stale pref can never hide a card
    // whose rootfs is actually gone.
    // The probe touches the filesystem: keep it off the main thread, or the
    // first frame of this screen stalls.
    val installedDistroIds by androidx.compose.runtime.produceState(
        initialValue = emptySet<String>(),
        refreshKey.value,
        stateRefresh,
    ) {
        value = withContext(kotlinx.coroutines.Dispatchers.IO) {
            DistroRepository.supportedDistros
                .filter { TerminalLauncher.isDistroInstalledOnFs(context, it.id) }
                .map { it.id }
                .toSet()
        }
    }

    var methodTab by remember { mutableStateOf(MethodTab.PROOT) }
    var selectedCategory by remember { mutableStateOf(DistroCategory.ALL) }
    // Backend filtering/sort logic (kept): method filter + sortForDistroPage.
    val methodDistros = remember(methodTab) {
        DistroRepository.sortForDistroPage(
            DistroRepository.supportedDistros.filter { distro ->
                if (methodTab == MethodTab.CHROOT) distro.chrootSupported else distro.prootSupported
            }
        )
    }
    val prootCount = DistroRepository.supportedDistros.count { it.prootSupported }
    val chrootCount = DistroRepository.supportedDistros.count { it.chrootSupported }

    // Local category derivation from id/name/description (+ cybersec components).
    val filteredDistros = remember(methodDistros, selectedCategory) {
        if (selectedCategory == DistroCategory.ALL) methodDistros
        else methodDistros.filter { it.matchesCategory(selectedCategory) }
    }
    val methodLabel = if (methodTab == MethodTab.CHROOT) "chroot" else "PRoot"

    // The section accent colours the filters, the cards and the rows.
    val accent = sectionAccent(AltarisSection.DISTROS)

    // Shared entrance clock: cards cascade in once; items scrolled into view
    // after the ~650ms window appear at rest (no endless list animation).
    val entrance = rememberStaggerEntrance()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Prism.Gutter,
            end = Prism.Gutter,
            top = 10.dp,
            bottom = 110.dp
        ),
        verticalArrangement = Arrangement.spacedBy(Prism.Stack)
    ) {
        item(key = "title") {
            AltarisTitle(
                "Distros",
                "Pick a Linux environment",
                accent,
                Modifier
                    .fillMaxWidth()
                    .staggerIn(0, entrance)
            )
        }

        // Sticky controls: method + category filters stay reachable while the
        // list scrolls (near-opaque scrim keeps text legible).
        stickyHeader(key = "controls") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.97f))
                    .padding(top = 8.dp)
            ) {
                PrismSegmented(
                    options = listOf("PRoot · $prootCount", "Chroot · $chrootCount"),
                    selectedIndex = if (methodTab == MethodTab.PROOT) 0 else 1,
                    accent = accent,
                    modifier = Modifier.fillMaxWidth(),
                    onSelect = { index ->
                        methodTab = if (index == 0) MethodTab.PROOT else MethodTab.CHROOT
                    }
                )
                Spacer(Modifier.height(Prism.Tight))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Prism.Tight)
                ) {
                    DistroCategory.values().forEach { category ->
                        PrismChip(
                            text = category.label,
                            accent = accent,
                            selected = category == selectedCategory,
                            onClick = { selectedCategory = category }
                        )
                    }
                }
                Spacer(Modifier.height(Prism.Tight))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                )
            }
        }

        if (filteredDistros.isEmpty()) {
            item(key = "empty") {
                PrismCard(
                    accent = accent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .staggerIn(2, entrance)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (selectedCategory != DistroCategory.ALL) {
                                "Nothing matches this filter"
                            } else {
                                "No $methodLabel distros available"
                            },
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (selectedCategory != DistroCategory.ALL) {
                                "No $methodLabel distro in ${selectedCategory.label}. Try another category."
                            } else {
                                "Switch the method toggle above to browse other environments."
                            },
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = altarisMutedText()
                        )
                        if (selectedCategory != DistroCategory.ALL) {
                            Spacer(Modifier.height(14.dp))
                            PrismButton(
                                text = "Show all",
                                accent = accent,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { selectedCategory = DistroCategory.ALL }
                            )
                        }
                    }
                }
            }
        } else {
            // One PrismCard group holds the whole browse list; every distro is
            // a PrismRow entry inside it.
            item(key = "distro-label") {
                PrismSectionLabel(
                    text = if (selectedCategory == DistroCategory.ALL) {
                        "All distributions · ${filteredDistros.size}"
                    } else {
                        "${selectedCategory.label} · ${filteredDistros.size}"
                    },
                    accent = accent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .staggerIn(1, entrance)
                )
            }
            item(key = "distro-group") {
                PrismCard(
                    accent = accent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .staggerIn(2, entrance)
                ) {
                    filteredDistros.forEachIndexed { index, distro ->
                        DistroRow(
                            distro = distro,
                            accent = accent,
                            installed = installedDistroIds.contains(distro.id),
                            onClick = if (distro.comingSoon) {
                                { }
                            } else {
                                { onNavigateToInstall(distro) }
                            },
                            divider = index < filteredDistros.lastIndex,
                            modifier = Modifier.staggerIn(index, entrance)
                        )
                    }
                }
            }
        }
    }
}

// ─── Browse entry: logo, name + blurb + badges, status tag ───────────────────
@Composable
private fun DistroRow(
    distro: Distro,
    accent: Color,
    installed: Boolean,
    onClick: () -> Unit,
    divider: Boolean,
    modifier: Modifier = Modifier
) {
    val badges = remember(distro.id) { distroBadgeLabels(distro) }
    val subtitle = buildString {
        append(distro.description)
        if (badges.isNotEmpty()) {
            append(" · ")
            append(badges.joinToString(" · "))
        }
    }

    PrismRow(
        title = distro.name,
        subtitle = subtitle,
        accent = accent,
        modifier = modifier,
        leading = {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(Prism.TileRadius))
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                if (distro.iconRes != null) {
                    Image(
                        painter = painterResource(id = distro.iconRes),
                        contentDescription = "${distro.name} logo",
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Text(
                        distro.name.firstOrNull()?.uppercase() ?: "?",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = accent
                    )
                }
            }
        },
        trailing = {
            when {
                distro.comingSoon -> StatusTag("Soon", accent)
                installed -> StatusTag("Installed", accent, dot = true)
                else -> InstallAffordance()
            }
        },
        onClick = onClick,
        divider = divider
    )
}

/** The download entry point of a browse row: a quiet label and a chevron. */
@Composable
private fun InstallAffordance() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = "Install",
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(16.dp)
        )
    }
}

// Tiny status chip (Soon / Installed) used on browse rows.
@Composable
private fun StatusTag(text: String, accent: Color, dot: Boolean = false) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Prism.ChipRadius))
            .background(accent.copy(alpha = 0.14f))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (dot) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(accent)
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = accent,
            maxLines = 1
        )
    }
}
