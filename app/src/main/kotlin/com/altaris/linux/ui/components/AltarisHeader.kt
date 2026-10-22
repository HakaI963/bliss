package com.altaris.linux.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altaris.linux.ui.theme.Prism

/**
 * The top bar.
 *
 * A flat 46 dp strip with a hairline underneath — the reference's workspace
 * topbar. The wordmark, the live status and two actions.
 */
@Composable
fun AltarisCommandBar(
    accent: Color,
    modifier: Modifier = Modifier,
    statusLabel: String,
    live: Boolean,
    onOpenPalette: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Prism.TopBarHeight)
            .background(scheme.surface)
            .border(1.dp, scheme.outline, RoundedCornerShape(0.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AltarisMark(accent = accent, size = 18.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            text = "ALTARIS",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.6.sp,
            color = scheme.onSurface,
        )
        Spacer(Modifier.width(10.dp))
        PrismOrb(active = live, accent = accent)
        Spacer(Modifier.width(6.dp))
        Text(
            text = statusLabel,
            fontSize = 11.sp,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        BarButton(
            icon = Icons.Default.Search,
            contentDescription = "Jump to…",
            onClick = onOpenPalette,
        )
        Spacer(Modifier.width(6.dp))
        BarButton(
            icon = Icons.Default.Settings,
            contentDescription = "Settings",
            onClick = onOpenSettings,
        )
        Spacer(Modifier.width(10.dp))
    }
}

/** A small square action in the top bar. */
@Composable
private fun BarButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(Prism.ButtonRadius)
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(shape)
            .background(if (pressed) scheme.onSurface.copy(alpha = 0.08f) else Color.Transparent)
            .border(1.dp, scheme.outline, shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
    }
}

/**
 * The screen title: a large display line with a short accent rule, the
 * reference's panel title at a larger scale.
 */
@Composable
fun AltarisTitle(
    title: String,
    subtitle: String?,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        AnimatedContent(
            targetState = title,
            transitionSpec = {
                (fadeIn(tween(160)) + androidx.compose.animation.slideInVertically(
                    animationSpec = spring(dampingRatio = 0.9f, stiffness = 600f),
                    initialOffsetY = { it / 4 },
                )) togetherWith fadeOut(tween(100))
            },
            label = "titleSwap",
        ) { value ->
            Text(
                text = value,
                fontSize = 20.sp,
                lineHeight = 25.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.3).sp,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = subtitle,
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * The Altaris mark: a triangle that splits a beam — the prism the product
 * is named after.
 */
@Composable
fun AltarisMark(
    accent: Color,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val inkSoft = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.18f, h * 0.82f)
            lineTo(w * 0.50f, h * 0.14f)
            lineTo(w * 0.82f, h * 0.82f)
            close()
        }
        drawPath(path, color = ink)
        drawLine(
            color = inkSoft,
            start = androidx.compose.ui.geometry.Offset(w * 0.02f, h * 0.46f),
            end = androidx.compose.ui.geometry.Offset(w * 0.44f, h * 0.42f),
            strokeWidth = h * 0.07f,
        )
    }
}
