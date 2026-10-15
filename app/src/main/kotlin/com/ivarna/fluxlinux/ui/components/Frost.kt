package com.ivarna.fluxlinux.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivarna.fluxlinux.ui.theme.FrostHairline
import com.ivarna.fluxlinux.ui.theme.FrostSurface
import com.ivarna.fluxlinux.ui.theme.FrostTopSheen
import com.ivarna.fluxlinux.ui.theme.FolioFrostBorder
import com.ivarna.fluxlinux.ui.theme.TextMuted
import com.ivarna.fluxlinux.ui.theme.TextPrimary
import com.ivarna.fluxlinux.ui.theme.TextSecondary

// Frost edge system: 0.75dp outer hairlines, 0.5dp inner dividers, full-round pills.
private val FrostEdgeWidth = 0.75.dp
private val FrostPillShape = RoundedCornerShape(50)

fun folioSpring() = spring<Float>(dampingRatio = 0.78f, stiffness = 380f)

@Composable
fun folioPressedScale(): Pair<Modifier, () -> Unit> {
    var pressed by remember { mutableStateOf(false) }
    val scale = if (pressed) 0.98f else 1f
    val mod = Modifier
        .graphicsLayer(scaleX = scale, scaleY = scale)
        .pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    pressed = true
                    tryAwaitRelease()
                    pressed = false
                }
            )
        }
    return mod to { pressed = false }
}

@Composable
fun FrostContainer(
    modifier: Modifier = Modifier,
    corner: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(corner))
            .background(FrostSurface)
            .border(FrostEdgeWidth, FolioFrostBorder, RoundedCornerShape(corner))
            .drawBehind {
                // Soft inner sheen: the glass reads as lit from above.
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to FrostTopSheen,
                        1f to Color.White.copy(alpha = 0f),
                        endY = 28.dp.toPx()
                    )
                )
            }
            .padding(vertical = 4.dp),
        content = content
    )
}

@Composable
fun FrostRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    chevron: Boolean = true,
    titleColor: Color = TextPrimary,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = titleColor)
            Text(subtitle, fontSize = 12.sp, color = TextMuted, maxLines = 1)
        }
        if (chevron) {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun FrostDivider() {
    HorizontalDivider(
        color = FrostHairline,
        thickness = 0.5.dp,
        modifier = Modifier.padding(horizontal = 18.dp)
    )
}

@Composable
fun FrostGroupTitle(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = TextSecondary,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp, top = 8.dp)
    )
}

@Composable
fun FrostPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
) {
    val bg = if (primary) TextPrimary else FrostSurface
    val fg = if (primary) Color(0xFF09090B) else TextPrimary
    Box(
        modifier = modifier
            .clip(FrostPillShape)
            .background(bg)
            .then(
                if (primary) Modifier
                else Modifier.border(FrostEdgeWidth, FolioFrostBorder, FrostPillShape)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = fg)
    }
}

@Composable
fun RowScope.FrostTabPill(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    label: @Composable () -> Unit,
) {
    val bg by animateColorAsState(
        if (selected) Color.White.copy(alpha = 0.10f) else Color.Transparent,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 380f),
        label = "pill"
    )
    val edge by animateColorAsState(
        if (selected) FrostHairline else Color.Transparent,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 380f),
        label = "pillEdge"
    )
    Box(
        modifier = Modifier
            .weight(1f)
            .clip(FrostPillShape)
            .background(bg)
            .border(FrostEdgeWidth, edge, FrostPillShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            icon()
            label()
        }
    }
}
