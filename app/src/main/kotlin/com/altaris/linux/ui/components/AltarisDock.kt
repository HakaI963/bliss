package com.altaris.linux.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altaris.linux.ui.theme.Prism

/**
 * The screens the shell can show. The nav bar exposes four of them; the rest
 * (terminal, settings) are reached through the command palette.
 */
enum class BottomTab(val title: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    DISTROS("Distros", Icons.Default.List),
    APPS("Apps", Icons.Default.Android),
    TOOLS("Tools", Icons.Default.Build),
    TERMINAL("Terminal", Icons.Default.Terminal),
    SETTINGS("Settings", Icons.Default.Settings),
}

/** The destinations of the navigation bar. */
enum class DockDestination(val title: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    DISTROS("Distros", Icons.Default.Layers),
    APPS("Apps", Icons.Default.Apps),
    TOOLS("Tools", Icons.Default.Tune),
}

private val DockSlots = listOf(
    DockDestination.HOME,
    DockDestination.DISTROS,
    DockDestination.APPS,
    DockDestination.TOOLS,
)

/**
 * The navigation bar.
 *
 * A flat strip with a hairline on top — the reference's mobile sidebar. The
 * active item gets a panel background and a border; pressing an item nudges
 * its neighbours aside.
 */
@Composable
fun AltarisDock(
    selected: DockDestination,
    modifier: Modifier = Modifier,
    onSelect: (DockDestination) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // One interaction source per item, owned by the bar: the bar therefore
    // knows which item is held without stealing the tap from the item.
    val interactions = DockSlots.map { remember { MutableInteractionSource() } }
    val pressed = interactions.map { it.collectIsPressedAsState().value }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Prism.NavBarHeight)
            .background(scheme.surface)
            .border(1.dp, scheme.outline, RoundedCornerShape(0.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DockSlots.forEachIndexed { index, destination ->
            NavItem(
                destination = destination,
                selected = destination == selected,
                held = pressed[index],
                nudge = neighbourNudge(index, pressed.indexOfFirst { it }),
                interaction = interactions[index],
                onClick = { onSelect(destination) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** How far a neighbour is pushed aside while an item is held. */
private fun neighbourNudge(index: Int, pressedIndex: Int): Float {
    if (pressedIndex < 0 || index == pressedIndex) return 0f
    if (kotlin.math.abs(index - pressedIndex) != 1) return 0f
    return if (index < pressedIndex) -1f else 1f
}

@Composable
private fun NavItem(
    destination: DockDestination,
    selected: Boolean,
    held: Boolean,
    nudge: Float,
    interaction: MutableInteractionSource,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val shift by animateFloatAsState(
        targetValue = if (held) nudge * 6f else 0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 700f),
        label = "navNudge",
    )
    val shape = RoundedCornerShape(Prism.ButtonRadius)
    Column(
        modifier = modifier
            .graphicsLayer { translationX = shift }
            .padding(horizontal = 6.dp, vertical = 7.dp)
            .clip(shape)
            .background(if (selected) scheme.surfaceVariant else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (selected) scheme.outline else Color.Transparent,
                shape = shape,
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = destination.icon,
            contentDescription = destination.title,
            tint = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = destination.title,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
