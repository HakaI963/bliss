package com.altaris.linux.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altaris.linux.ui.theme.Prism

/**
 * The screens the shell can show. The nav bar exposes five of them; the rest
 * (terminal, settings) are reached through the command palette.
 *
 * Tools is deliberately *not* in the dock: it is a maintenance surface, so it
 * lives in the top bar as a wrench, next to Settings.
 */
enum class BottomTab(val title: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    REA("REA", Icons.Default.Build),
    DISTROS("Distros", Icons.Default.List),
    APPS("Apps", Icons.Default.Android),
    TOOLS("Tools", Icons.Default.Build),
    TERMINAL("Terminal", Icons.Default.Terminal),
    SETTINGS("Settings", Icons.Default.Settings),
}

/** The destinations of the navigation bar. */
enum class DockDestination(val title: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    REA("REA", Icons.Default.Build),
    DISTROS("Distros", Icons.Default.Layers),
    APPS("Apps", Icons.Default.Apps),
}

private val DockSlots = listOf(
    DockDestination.HOME,
    DockDestination.REA,
    DockDestination.DISTROS,
    DockDestination.APPS,
)

/**
 * The navigation bar.
 *
 * A full-width strip with a hairline on top and a soft shadow, so it reads
 * as a separate layer above the content. The active destination sits on a
 * plate that slides between the items; holding an item nudges its
 * neighbours aside.
 */
@Composable
fun AltarisDock(
    selected: DockDestination,
    modifier: Modifier = Modifier,
    onSelect: (DockDestination) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    // One interaction source per item, owned by the bar: the bar therefore
    // knows which item is held without stealing the tap from the item.
    val interactions = DockSlots.map { remember { MutableInteractionSource() } }
    val pressed = interactions.map { it.collectIsPressedAsState().value }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .shadow(14.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.18f))
            .clip(shape)
            .background(scheme.surface),
    ) {
        val gap = 4.dp
        // The items are inset from the screen edge, so the plate never runs
        // into the bezel.
        val side = 10.dp
        val itemWidth = (maxWidth - side * 2 - gap * (DockSlots.size - 1)) / DockSlots.size
        // The plate lives in dp; the offset is applied in pixels, so the
        // conversion happens inside the offset lambda.
        val plateX by animateDpAsState(
            targetValue = side + (itemWidth + gap) *
                DockSlots.indexOf(selected).coerceAtLeast(0),
            animationSpec = spring(dampingRatio = 0.78f, stiffness = 520f),
            label = "plateX",
        )
        val indicatorShape = RoundedCornerShape(Prism.ButtonRadius)

        // The sliding plate.
        Box(
            modifier = Modifier
                .offset { IntOffset(plateX.toPx().toInt(), 0) }
                .padding(top = 7.dp, bottom = 7.dp)
                .width(itemWidth)
                .height(Prism.NavBarHeight - 14.dp)
                .clip(indicatorShape)
                .background(scheme.surfaceVariant)
                .border(1.dp, scheme.outline, indicatorShape)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(Prism.NavBarHeight)
                .padding(horizontal = side),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            DockSlots.forEachIndexed { index, destination ->
                NavItem(
                    destination = destination,
                    selected = destination == selected,
                    held = pressed[index],
                    nudge = neighbourNudge(index, pressed.indexOfFirst { it }),
                    interaction = interactions[index],
                    onClick = { onSelect(destination) },
                    modifier = Modifier
                        .width(itemWidth)
                        .padding(horizontal = 1.dp),
                )
            }
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
        targetValue = if (held) nudge * 5f else 0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 700f),
        label = "navNudge",
    )
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.06f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 600f),
        label = "navIconScale",
    )
    Column(
        modifier = modifier
            .graphicsLayer { translationX = shift }
            .fillMaxWidth()
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = destination.icon,
            contentDescription = destination.title,
            tint = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
            modifier = Modifier
                .size(19.dp)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                },
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = destination.title,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
