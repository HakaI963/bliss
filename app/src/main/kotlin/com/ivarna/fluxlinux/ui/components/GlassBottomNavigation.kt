package com.ivarna.fluxlinux.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivarna.fluxlinux.ui.theme.FolioBackground
import com.ivarna.fluxlinux.ui.theme.FolioFrostBorder
import com.ivarna.fluxlinux.ui.theme.FrostSurface
import com.ivarna.fluxlinux.ui.theme.TextMuted
import com.ivarna.fluxlinux.ui.theme.TextPrimary
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild

enum class BottomTab(val title: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    DISTROS("Distros", Icons.Default.List),
    APPS("Apps", Icons.Default.Android),
    TOOLS("Tools", Icons.Default.Build),
    TERMINAL("Terminal", Icons.Default.Terminal),
    SETTINGS("Settings", Icons.Default.Settings)
}

/** Dock slots, evenly distributed. Terminal is reached from Home. */
private val BottomNavItems = listOf(BottomTab.HOME, BottomTab.DISTROS, BottomTab.APPS, BottomTab.TOOLS)

private val NavHeight = 62.dp
private val NavMargin = 12.dp
private val NavEdgeWidth = 0.75.dp
private val NavPillShape = RoundedCornerShape(percent = 50)
private val NavItemShape = RoundedCornerShape(50)

/**
 * Floating dock: icon + label per tab, labels always visible. The selected tab
 * gets an inverted pill behind its icon; everything else stays quiet.
 */
@Composable
fun GlassBottomNavigation(
    selectedTab: BottomTab,
    onTabSelected: (BottomTab) -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier
) {
    val surfaceColor = MaterialTheme.colorScheme.surface
    val blurBackgroundColor = remember(surfaceColor) {
        surfaceColor.copy(alpha = 0.7f)
    }

    Box(
        modifier = modifier
            .padding(horizontal = NavMargin, vertical = NavMargin)
            .fillMaxWidth()
            .height(NavHeight)
            .shadow(elevation = 8.dp, shape = NavPillShape)
            .clip(NavPillShape)
            .background(FrostSurface)
            .hazeChild(state = hazeState) {
                backgroundColor = blurBackgroundColor
                blurRadius = 100.dp
                noiseFactor = 0.1f
            }
            .border(border = BorderStroke(NavEdgeWidth, FolioFrostBorder), shape = NavPillShape)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BottomNavItems.forEach { tab ->
                NavItem(
                    tab = tab,
                    selected = selectedTab == tab,
                    onClick = { onTabSelected(tab) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** One dock slot: icon pill above an always-visible label. */
@Composable
private fun NavItem(
    tab: BottomTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val iconTint by animateColorAsState(
        targetValue = if (selected) FolioBackground else TextMuted,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 380f),
        label = "navIconTint"
    )
    val labelColor by animateColorAsState(
        targetValue = if (selected) TextPrimary else TextMuted,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 380f),
        label = "navLabelColor"
    )
    val pillColor by animateColorAsState(
        targetValue = if (selected) TextPrimary else Color.Transparent,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 380f),
        label = "navPillColor"
    )
    val pillSize by animateDpAsState(
        targetValue = if (selected) 32.dp else 28.dp,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 380f),
        label = "navPillSize"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(NavItemShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(pillSize)
                    .clip(NavItemShape)
                    .background(pillColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = tab.icon,
                    contentDescription = tab.title,
                    tint = iconTint,
                    modifier = Modifier.size(19.dp)
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = tab.title,
                fontSize = 10.5.sp,
                lineHeight = 11.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = labelColor,
                maxLines = 1
            )
        }
    }
}
