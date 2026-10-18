package com.ivarna.fluxlinux.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.ivarna.fluxlinux.core.data.Distro
import com.ivarna.fluxlinux.core.data.DistroRepository
import com.ivarna.fluxlinux.core.terminal.TerminalLauncher
import com.ivarna.fluxlinux.core.utils.StateManager
import com.ivarna.fluxlinux.ui.components.MethodTab
import com.ivarna.fluxlinux.ui.components.QCard
import com.ivarna.fluxlinux.ui.components.QChevron
import com.ivarna.fluxlinux.ui.components.QDivider
import com.ivarna.fluxlinux.ui.components.QEmptyState
import com.ivarna.fluxlinux.ui.components.QPill
import com.ivarna.fluxlinux.ui.components.QPrimaryButton
import com.ivarna.fluxlinux.ui.components.QScreenTitle
import com.ivarna.fluxlinux.ui.components.Quartz
import com.ivarna.fluxlinux.ui.theme.fluxMutedText
import com.ivarna.fluxlinux.ui.theme.rememberStaggerEntrance
import com.ivarna.fluxlinux.ui.theme.staggerIn
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
    onNavigateToInstall: (com.ivarna.fluxlinux.core.data.Distro) -> Unit
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
    val installedDistroIds = remember(refreshKey.value, stateRefresh) {
        DistroRepository.supportedDistros
            .filter { TerminalLauncher.isDistroInstalledOnFs(context, it.id) }
            .map { it.id }
            .toSet()
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

    // Shared entrance clock: cards cascade in once; items scrolled into view
    // after the ~650ms window appear at rest (no endless list animation).
    val entrance = rememberStaggerEntrance()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 10.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(Quartz.Gap)
    ) {
        item(key = "title") {
            QScreenTitle(
                "Distros",
                "Pick a Linux environment — PRoot without root, or Chroot for full speed.",
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Quartz.ScreenPad)
                    .staggerIn(0, entrance)
            )
        }

        // Sticky controls: method + category pills stay reachable while the
        // list scrolls (near-opaque scrim keeps text legible).
        stickyHeader(key = "controls") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.97f))
                    .padding(top = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Quartz.ScreenPad),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QPill(
                        "PRoot · $prootCount",
                        selected = methodTab == MethodTab.PROOT,
                        onClick = { methodTab = MethodTab.PROOT }
                    )
                    QPill(
                        "Chroot · $chrootCount",
                        selected = methodTab == MethodTab.CHROOT,
                        onClick = { methodTab = MethodTab.CHROOT }
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = Quartz.ScreenPad),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DistroCategory.values().forEach { category ->
                        QPill(
                            category.label,
                            selected = category == selectedCategory,
                            onClick = { selectedCategory = category }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                QDivider()
            }
        }

        if (filteredDistros.isEmpty()) {
            item(key = "empty") {
                QCard(
                    modifier = Modifier
                        .padding(horizontal = Quartz.ScreenPad)
                        .staggerIn(2, entrance)
                ) {
                    if (selectedCategory != DistroCategory.ALL) {
                        QEmptyState(
                            "Nothing matches this filter",
                            "No $methodLabel distro in ${selectedCategory.label}. " +
                                "Try another category."
                        )
                        Column(
                            modifier = Modifier.padding(horizontal = Quartz.CardPad, vertical = 4.dp)
                        ) {
                            QPrimaryButton(
                                "Show all",
                                onClick = { selectedCategory = DistroCategory.ALL }
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                    } else {
                        QEmptyState(
                            "No $methodLabel distros available",
                            "Switch the method toggle above to browse other " +
                                "environments."
                        )
                    }
                }
            }
        } else {
            itemsIndexed(
                items = filteredDistros,
                key = { _, distro -> distro.id },
                contentType = { _, distro -> if (distro.comingSoon) "soon" else "full" }
            ) { index, distro ->
                // Row tap keeps the existing destination contract: DistroScreen
                // taps route through onNavigateToInstall (MainActivity → INSTALL_WIZARD).
                val cardModifier = Modifier
                    .padding(horizontal = Quartz.ScreenPad)
                    .staggerIn(index + 2, entrance)
                if (distro.comingSoon) {
                    DistroBrowseCard(
                        distro = distro,
                        installed = false,
                        onClick = {},
                        modifier = cardModifier
                    )
                } else {
                    DistroBrowseCard(
                        distro = distro,
                        installed = installedDistroIds.contains(distro.id),
                        onClick = { onNavigateToInstall(distro) },
                        modifier = cardModifier
                    )
                }
            }
        }
    }
}

// ─── Compact browse card: 34 dp tile, name + blurb + badges, status tag ──────
@Composable
private fun DistroBrowseCard(
    distro: Distro,
    installed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val badges = remember(distro.id) { distroBadgeLabels(distro) }

    QCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = Quartz.CardPad + 2.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 34 dp frost tile — distro logo or its initial.
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(Quartz.TileShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (distro.iconRes != null) {
                    Image(
                        painter = painterResource(id = distro.iconRes),
                        contentDescription = "${distro.name} logo",
                        modifier = Modifier.size(22.dp)
                    )
                } else {
                    Text(
                        distro.name.firstOrNull()?.uppercase() ?: "?",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    distro.name,
                    fontSize = Quartz.RowTitleSize,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    distro.description,
                    fontSize = Quartz.RowSubSize,
                    color = fluxMutedText(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (badges.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        badges.joinToString(" · "),
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = fluxMutedText(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            when {
                distro.comingSoon -> StatusTag("Soon")
                installed -> StatusTag("Installed", dot = true)
                else -> QChevron()
            }
        }
    }
}

// Tiny status chip (Soon / Installed) used on browse cards.
@Composable
private fun StatusTag(text: String, dot: Boolean = false) {
    Row(
        modifier = Modifier
            .clip(Quartz.PillShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (dot) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(Quartz.PillShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}
