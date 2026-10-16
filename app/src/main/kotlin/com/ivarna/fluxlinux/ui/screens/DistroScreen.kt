package com.ivarna.fluxlinux.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.ivarna.fluxlinux.core.data.Distro
import com.ivarna.fluxlinux.core.data.DistroRepository
import com.ivarna.fluxlinux.core.terminal.TerminalLauncher
import com.ivarna.fluxlinux.core.utils.StateManager
import com.ivarna.fluxlinux.ui.components.FrostPill
import com.ivarna.fluxlinux.ui.components.MethodTab
import com.ivarna.fluxlinux.ui.theme.FolioFrostBorder
import com.ivarna.fluxlinux.ui.theme.FrostHairline
import com.ivarna.fluxlinux.ui.theme.FrostSurface
import com.ivarna.fluxlinux.ui.theme.StatusDot
import com.ivarna.fluxlinux.ui.theme.TextMuted
import com.ivarna.fluxlinux.ui.theme.TextPrimary
import com.ivarna.fluxlinux.ui.theme.TextSecondary
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
    if (listOf("kali", "parrot", "backbox").any { id.contains(it) }) return true
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

// PRoot|Chroot sliding glass switcher — restyled MethodTabs to monochrome.
// Monochrome container + sliding pill White 10% with spring damping 0.78 stiffness 380.
@Composable
private fun MonochromeMethodSwitcher(
    selected: MethodTab,
    onSelected: (MethodTab) -> Unit,
    prootCount: Int? = null,
    chrootCount: Int? = null,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(14.dp)
    val pillShape = RoundedCornerShape(11.dp)
    val targetBias = if (selected == MethodTab.PROOT) -1f else 1f
    val bias by animateFloatAsState(
        targetValue = targetBias,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 380f),
        label = "methodSwitcherBias"
    )
    val prootText = if (prootCount != null) "PRoot  $prootCount" else "PRoot"
    val chrootText = if (chrootCount != null) "Chroot  $chrootCount" else "Chroot"
    val activeColor by animateColorAsState(
        targetValue = Color.White,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 380f),
        label = "methodActiveColor"
    )
    val idleColor by animateColorAsState(
        targetValue = Color.White.copy(alpha = 0.55f),
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 380f),
        label = "methodIdleColor"
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(46.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
    ) {
        // Sliding glass pill — White 10%
        Box(
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .padding(3.dp)
                .align(BiasAlignment(bias, 0f))
                .fillMaxHeight()
                .clip(pillShape)
                .background(Color.White.copy(alpha = 0.1f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), pillShape)
        )
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(pillShape)
                    .clickable { onSelected(MethodTab.PROOT) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = prootText,
                    color = if (selected == MethodTab.PROOT) activeColor else idleColor,
                    fontWeight = if (selected == MethodTab.PROOT) FontWeight.Bold else FontWeight.SemiBold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(pillShape)
                    .clickable { onSelected(MethodTab.CHROOT) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = chrootText,
                    color = if (selected == MethodTab.CHROOT) activeColor else idleColor,
                    fontWeight = if (selected == MethodTab.CHROOT) FontWeight.Bold else FontWeight.SemiBold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun CategoryFilterRow(
    selected: DistroCategory,
    onSelected: (DistroCategory) -> Unit,
    modifier: Modifier = Modifier
) {
    val scroll = rememberScrollState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DistroCategory.values().forEach { category ->
            val isSelected = category == selected
            val chipShape = RoundedCornerShape(20.dp)
            // Animated selection: background/border colors + a slightly taller
            // selected chip keep the tap feel tactile without new libraries.
            val bg by animateColorAsState(
                targetValue = if (isSelected) Color.White.copy(alpha = 0.12f)
                else Color.White.copy(alpha = 0.04f),
                animationSpec = spring(dampingRatio = 0.78f, stiffness = 380f),
                label = "chipBg"
            )
            val edge by animateColorAsState(
                targetValue = if (isSelected) Color.White.copy(alpha = 0.22f)
                else Color.White.copy(alpha = 0.1f),
                animationSpec = spring(dampingRatio = 0.78f, stiffness = 380f),
                label = "chipEdge"
            )
            val labelColor by animateColorAsState(
                targetValue = if (isSelected) TextPrimary else Color.White.copy(alpha = 0.6f),
                animationSpec = spring(dampingRatio = 0.78f, stiffness = 380f),
                label = "chipLabel"
            )
            val vPad by animateDpAsState(
                targetValue = if (isSelected) 9.dp else 8.dp,
                animationSpec = spring(dampingRatio = 0.78f, stiffness = 380f),
                label = "chipVPad"
            )
            Box(
                modifier = Modifier
                    .clip(chipShape)
                    .background(bg)
                    .border(1.dp, edge, chipShape)
                    .clickable { onSelected(category) }
                    .padding(horizontal = 14.dp, vertical = vPad),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = category.label,
                    color = labelColor,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 12.sp,
                    maxLines = 1
                )
            }
        }
    }
}

/** Collapsing page title — subtle parallax as the list scrolls under the sticky controls. */
@Composable
private fun DistroTitleBanner(collapse: Float) {
    val alpha by animateFloatAsState(
        targetValue = 1f - 0.55f * collapse,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 420f),
        label = "titleAlpha"
    )
    val scale by animateFloatAsState(
        targetValue = 1f - 0.05f * collapse,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 420f),
        label = "titleScale"
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                translationY = -collapse * 18f
            }
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Available Distros",
            color = MaterialTheme.colorScheme.secondary,
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "Pick a Linux environment — PRoot without root, or Chroot for full speed",
            color = TextMuted,
            fontSize = 12.sp,
            maxLines = 2
        )
        Spacer(modifier = Modifier.height(10.dp))
    }
}

/**
 * Richer browse card: 44dp frosted icon plate (logo color as a faint accent),
 * name + blurb, factual badges, and an Installed chip when the rootfs exists.
 */
@Composable
private fun DistroBrowseCard(
    distro: Distro,
    installed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "distroCardPress"
    )
    val badges = remember(distro.id) { distroBadgeLabels(distro) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(18.dp))
            .background(FrostSurface)
            .border(0.75.dp, FolioFrostBorder, RoundedCornerShape(18.dp))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 44dp frosted plate — distro logo colors only as a faint top accent.
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .border(0.75.dp, FolioFrostBorder, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(distro.color.copy(alpha = 0.18f), Color.Transparent)
                        )
                    )
            )
            if (distro.iconRes != null) {
                Image(
                    painter = painterResource(id = distro.iconRes),
                    contentDescription = "${distro.name} logo",
                    modifier = Modifier.size(30.dp)
                )
            } else {
                Text(
                    text = distro.name.firstOrNull()?.uppercase() ?: "?",
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = distro.name,
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = distro.description,
                color = TextSecondary,
                fontSize = 12.5.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 16.sp
            )
            if (badges.isNotEmpty()) {
                Spacer(modifier = Modifier.height(7.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    badges.forEach { badge ->
                        BadgePill(badge)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        when {
            distro.comingSoon -> BadgePill("Soon")
            installed -> InstalledChip()
            else -> Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = "Open install options for ${distro.name}",
                tint = TextMuted,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun BadgePill(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color.White.copy(alpha = 0.07f))
            .border(0.75.dp, FrostHairline, RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            color = TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
private fun InstalledChip() {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(StatusDot)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "Installed",
            color = TextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
private fun DistroEmptyState(
    title: String,
    subtitle: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(FrostSurface)
                .border(0.75.dp, FolioFrostBorder, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = title,
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = subtitle,
            color = TextMuted,
            fontSize = 12.5.sp,
            textAlign = TextAlign.Center,
            lineHeight = 17.sp
        )
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(16.dp))
            FrostPill(text = actionLabel, onClick = onAction)
        }
    }
}

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

    val listState = rememberLazyListState()
    // Shared entrance clock: cards cascade in once; items scrolled into view
    // after the ~650ms window appear at rest (no endless list animation).
    val entrance = rememberStaggerEntrance()
    // Parallax input for the title: 0 at rest, 1 once the controls pin themselves.
    val titleCollapse by remember {
        derivedStateOf {
            val offset = if (listState.firstVisibleItemIndex == 0) {
                listState.firstVisibleItemScrollOffset
            } else {
                400
            }
            (offset / 240f).coerceIn(0f, 1f)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(bottom = 110.dp)
    ) {
        item(key = "title") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .staggerIn(0, entrance)
            ) {
                DistroTitleBanner(collapse = titleCollapse)
            }
        }

        // Sticky controls: segmented switcher + category chips stay reachable
        // while the list scrolls (near-opaque scrim keeps text legible).
        stickyHeader(key = "controls") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.97f))
            ) {
                MonochromeMethodSwitcher(
                    selected = methodTab,
                    onSelected = { methodTab = it },
                    prootCount = prootCount,
                    chrootCount = chrootCount
                )
                Spacer(modifier = Modifier.height(10.dp))
                CategoryFilterRow(
                    selected = selectedCategory,
                    onSelected = { selectedCategory = it }
                )
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(FrostHairline)
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
        }

        if (filteredDistros.isEmpty()) {
            item(key = "empty") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .staggerIn(2, entrance)
                ) {
                    if (selectedCategory != DistroCategory.ALL) {
                        DistroEmptyState(
                            title = "Nothing matches this filter",
                            subtitle = "No $methodLabel distro in ${selectedCategory.label}. " +
                                "Try another category.",
                            actionLabel = "Show all",
                            onAction = { selectedCategory = DistroCategory.ALL }
                        )
                    } else {
                        DistroEmptyState(
                            title = "No $methodLabel distros available",
                            subtitle = "Switch the method toggle above to browse other " +
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
                val cardModifier = Modifier.staggerIn(index + 2, entrance)
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
