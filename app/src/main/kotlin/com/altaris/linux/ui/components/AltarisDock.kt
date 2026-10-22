package com.altaris.linux.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altaris.linux.ui.theme.AltarisMotion
import com.altaris.linux.ui.theme.Prism

/**
 * The screens the shell can show. The dock exposes four of them; the rest
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

/**
 * The destinations of the dock.
 */
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
 * The Altaris dock.
 *
 * A flat capsule with a hairline border, four destinations and one active
 * marker that slides between them. Pressing an item nudges its neighbours
 * aside, so the dock reacts as one object.
 */
@Composable
fun AltarisDock(
    selected: DockDestination,
    modifier: Modifier = Modifier,
    onSelect: (DockDestination) -> Unit,
) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val shape = RoundedCornerShape(Prism.DockRadius)
    var rowWidth by remember { mutableIntStateOf(0) }
    var pressedIndex by remember { mutableStateOf(-1) }
    val slotWidth = if (rowWidth > 0) rowWidth / DockSlots.size else 0
    val activeIndex = DockSlots.indexOfFirst { it == selected }
    val thumbOffset by animateFloatAsState(
        targetValue = (activeIndex.coerceAtLeast(0) * slotWidth).toFloat(),
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 460f),
        label = "dockThumb",
    )
    val scheme = MaterialTheme.colorScheme

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(Prism.DockHeight),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // The sliding active marker, drawn under the items.
        if (slotWidth > 0 && activeIndex >= 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset { IntOffset(thumbOffset.toInt(), 0) }
                    .size(width = slotWidth.dp, height = (Prism.DockHeight - 16.dp))
                    .padding(horizontal = 10.dp)
                    .clip(RoundedCornerShape(Prism.DockRadius))
                    .background(scheme.onSurface.copy(alpha = 0.10f))
            )
        }

        // The dock body: a flat capsule with a hairline border.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(scheme.surface)
                .border(1.dp, scheme.outline, shape)
                .padding(horizontal = 6.dp, vertical = 8.dp)
                .onSizeChanged { rowWidth = it.width },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DockSlots.forEachIndexed { index, destination ->
                DockItem(
                    destination = destination,
                    selected = destination == selected,
                    pressed = pressedIndex == index,
                    nudge = neighbourNudge(index, pressedIndex),
                    onPressChange = { active -> pressedIndex = if (active) index else -1 },
                    onClick = {
                        haptics.performHapticFeedback(
                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove
                        )
                        onSelect(destination)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** How far a neighbour is pushed aside while an item is held. */
private fun neighbourNudge(index: Int, pressedIndex: Int): Float {
    if (pressedIndex < 0 || index == pressedIndex) return 0f
    val distance = kotlin.math.abs(index - pressedIndex)
    if (distance != 1) return 0f
    return if (index < pressedIndex) -1f else 1f
}

@Composable
private fun DockItem(
    destination: DockDestination,
    selected: Boolean,
    pressed: Boolean,
    nudge: Float,
    onPressChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.9f else if (selected) 1.04f else 1f,
        animationSpec = AltarisMotion.effects,
        label = "dockItemScale",
    )
    val shift by animateFloatAsState(
        targetValue = if (isPressed) nudge * 5f else 0f,
        animationSpec = AltarisMotion.fastSpatial,
        label = "dockItemNudge",
    )
    val lift by animateFloatAsState(
        targetValue = if (selected) -2f else 0f,
        animationSpec = AltarisMotion.spatial,
        label = "dockItemLift",
    )
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = shift
                translationY = lift
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onPressChange(true)
                        tryAwaitRelease()
                        onPressChange(false)
                    }
                )
            }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Icon(
            imageVector = destination.icon,
            contentDescription = destination.title,
            tint = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = destination.title,
            fontSize = 9.5.sp,
            letterSpacing = 0.2.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
