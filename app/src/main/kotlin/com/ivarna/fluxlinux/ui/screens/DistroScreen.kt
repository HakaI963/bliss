package com.ivarna.fluxlinux.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.ivarna.fluxlinux.core.data.Distro
import com.ivarna.fluxlinux.core.data.DistroRepository
import com.ivarna.fluxlinux.core.utils.StateManager
import com.ivarna.fluxlinux.ui.components.CompactDistroCard
import com.ivarna.fluxlinux.ui.components.MethodTab
import com.ivarna.fluxlinux.ui.components.MethodTabs
import dev.chrisbanes.haze.HazeState

// Keep existing MethodTabs for compat — restyled below to monochrome sliding glass.

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

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(42.dp)
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
                    color = if (selected == MethodTab.PROOT) Color.White else Color.White.copy(alpha = 0.55f),
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
                    color = if (selected == MethodTab.CHROOT) Color.White else Color.White.copy(alpha = 0.55f),
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
            Box(
                modifier = Modifier
                    .clip(chipShape)
                    .background(
                        if (isSelected) Color.White.copy(alpha = 0.1f)
                        else Color.White.copy(alpha = 0.04f)
                    )
                    .border(
                        1.dp,
                        if (isSelected) Color.White.copy(alpha = 0.18f)
                        else Color.White.copy(alpha = 0.1f),
                        chipShape
                    )
                    .clickable { onSelected(category) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = category.label,
                    color = if (isSelected) Color.White else Color.White.copy(alpha = 0.6f),
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 12.sp,
                    maxLines = 1
                )
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
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

    // Distro List — installed state = filesystem truth (plan P4-T13); stale
    // prefs without a rootfs on disk keep the card available for Install.
    val installedDistroIds = remember(refreshKey.value, stateRefresh) {
        DistroRepository.supportedDistros
            .filter { com.ivarna.fluxlinux.core.terminal.TerminalLauncher.isDistroInstalledOnFs(context, it.id) }
            .map { it.id }
            .toSet()
    }

    val availableDistros = DistroRepository.supportedDistros.filter {
        !installedDistroIds.contains(it.id)
    }

    var methodTab by remember { mutableStateOf(MethodTab.PROOT) }
    var selectedCategory by remember { mutableStateOf(DistroCategory.ALL) }
    // Backend filtering/sort logic (kept): method filter + sortForDistroPage.
    val visibleDistros = DistroRepository.sortForDistroPage(
        availableDistros.filter { distro ->
            if (methodTab == MethodTab.CHROOT) distro.chrootSupported else distro.prootSupported
        }
    )
    val prootCount = availableDistros.count { it.prootSupported }
    val chrootCount = availableDistros.count { it.chrootSupported }

    // Local category derivation from id/name/description (+ cybersec components).
    val filteredDistros = remember(visibleDistros, selectedCategory) {
        if (selectedCategory == DistroCategory.ALL) visibleDistros
        else visibleDistros.filter { it.matchesCategory(selectedCategory) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(bottom = 110.dp)
    ) {
        item(key = "title") {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Available Distros",
                    color = MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(10.dp))
            }
        }

        item(key = "tabs") {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Restyled MethodTabs: monochrome sliding glass switcher.
                MonochromeMethodSwitcher(
                    selected = methodTab,
                    onSelected = { methodTab = it },
                    prootCount = prootCount,
                    chrootCount = chrootCount
                )
                Spacer(modifier = Modifier.height(10.dp))
            }
        }

        item(key = "categories") {
            Column {
                CategoryFilterRow(
                    selected = selectedCategory,
                    onSelected = { selectedCategory = it }
                )
                Spacer(modifier = Modifier.height(10.dp))
            }
        }

        if (filteredDistros.isEmpty()) {
            item(key = "empty") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (availableDistros.isEmpty()) {
                            "All available distros are installed!"
                        } else if (selectedCategory != DistroCategory.ALL) {
                            "No distros in ${selectedCategory.label}"
                        } else if (methodTab == MethodTab.CHROOT) {
                            "No chroot distros left to install"
                        } else {
                            "No PRoot distros left to install"
                        },
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        } else {
            items(
                items = filteredDistros,
                key = { it.id },
                contentType = { if (it.comingSoon) "soon" else "full" }
            ) { distro ->
                // Frost row — row tap = onNavigateToInstall (no giant Install button).
                if (distro.comingSoon) {
                    CompactDistroCard(distro = distro)
                } else {
                    Box(
                        modifier = Modifier.clickable { onNavigateToInstall(distro) }
                    ) {
                        CompactDistroCard(distro = distro)
                    }
                }
            }
        }
    }
}
