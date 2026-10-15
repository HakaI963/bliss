package com.ivarna.fluxlinux.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivarna.fluxlinux.ui.theme.FolioFrostBorder
import com.ivarna.fluxlinux.ui.theme.FrostSurface
import com.ivarna.fluxlinux.ui.theme.TextMuted
import com.ivarna.fluxlinux.ui.theme.TextPrimary
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild

enum class BottomTab(val title: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    DISTROS("Distros", Icons.Default.List),
    TERMINAL("Terminal", Icons.Default.Terminal),
    SETTINGS("Settings", Icons.Default.Settings)
}

private val BottomNavItems = listOf(BottomTab.HOME, BottomTab.DISTROS, BottomTab.TERMINAL, BottomTab.SETTINGS)

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
            .padding(horizontal = 24.dp, vertical = 24.dp)
            .fillMaxWidth()
            .height(72.dp)
            .shadow(elevation = 8.dp, shape = RoundedCornerShape(percent = 50))
            .clip(RoundedCornerShape(percent = 50))
            .background(FrostSurface)
            .hazeChild(state = hazeState) {
                backgroundColor = blurBackgroundColor
                blurRadius = 100.dp
                noiseFactor = 0.1f
            }
            .border(border = androidx.compose.foundation.BorderStroke(1.dp, FolioFrostBorder), shape = RoundedCornerShape(percent = 50))
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            BottomNavItems.forEach { tab ->
                val selected = selectedTab == tab
                val pillOffset by animateDpAsState(
                    if (selected) 4.dp else 0.dp,
                    animationSpec = spring(dampingRatio = 0.78f, stiffness = 380f),
                    label = "pill"
                )
                FrostTabPill(
                    selected = selected,
                    onClick = { onTabSelected(tab) },
                    icon = {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.title,
                            tint = if (selected) TextPrimary else TextMuted,
                            modifier = Modifier.padding(top = pillOffset)
                        )
                    },
                    label = {
                        Text(
                            text = tab.title,
                            fontSize = 11.sp,
                            color = if (selected) TextPrimary else TextMuted
                        )
                    }
                )
            }
        }
    }
}
