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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altaris.linux.ui.theme.AltarisMotion
import com.altaris.linux.ui.theme.Prism

/**
 * The floating command bar.
 *
 * A glass pill that holds the Altaris mark, a jump-to button, the live
 * status orb and settings. It floats above the content instead of being a
 * solid strip, so the backdrop stays visible.
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
    val shape = RoundedCornerShape(Prism.HeaderRadius)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 14.dp,
                shape = shape,
                clip = false,
                ambientColor = accent.copy(alpha = 0.22f),
                spotColor = accent.copy(alpha = 0.22f),
            )
            .clip(shape)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.86f),
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(
                    listOf(
                        accent.copy(alpha = 0.30f),
                        Color.White.copy(alpha = 0.12f),
                        accent.copy(alpha = 0.16f),
                    )
                ),
                shape = shape,
            )
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AltarisMark(accent = accent)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = "ALTARIS",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.4.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = statusLabel,
                fontSize = 9.5.sp,
                letterSpacing = 1.1.sp,
                fontWeight = FontWeight.Medium,
                color = if (live) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        PrismOrb(active = live, accent = accent)
        Spacer(Modifier.width(4.dp))
        HeaderButton(
            icon = Icons.Default.Search,
            accent = accent,
            contentDescription = "Jump to…",
            onClick = onOpenPalette,
        )
        Spacer(Modifier.width(6.dp))
        HeaderButton(
            icon = Icons.Default.Settings,
            accent = accent,
            contentDescription = "Settings",
            onClick = onOpenSettings,
        )
    }
}

/** A compact squircle button for the command bar. */
@Composable
private fun HeaderButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = AltarisMotion.effects,
        label = "headerButtonScale",
    )
    Box(
        modifier = Modifier
            .size(38.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(14.dp))
            .background(accent.copy(alpha = if (pressed) 0.26f else 0.14f))
            .border(1.dp, accent.copy(alpha = 0.32f), RoundedCornerShape(14.dp))
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
            tint = accent,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * The screen title: a large display line with the section accent and a
 * subtitle. It animates whenever the section changes.
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
                (fadeIn(tween(220)) + androidx.compose.animation.slideInVertically(
                    animationSpec = spring(dampingRatio = 0.85f, stiffness = 420f),
                    initialOffsetY = { it / 3 },
                )) togetherWith fadeOut(tween(140))
            },
            label = "titleSwap",
        ) { value ->
            Text(
                text = value,
                fontSize = 30.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.8).sp,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(width = 22.dp, height = 3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent)
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.width(9.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The Altaris mark: a triangle that splits a beam into three colours —
 * the prism the product is named after.
 */
@Composable
fun AltarisMark(
    accent: Color,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 34.dp,
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.18f, h * 0.80f)
            lineTo(w * 0.50f, h * 0.16f)
            lineTo(w * 0.82f, h * 0.80f)
            close()
        }
        drawPath(
            path = path,
            brush = Brush.linearGradient(
                listOf(accent, accent.copy(alpha = 0.35f)),
                start = androidx.compose.ui.geometry.Offset(0f, 0f),
                end = androidx.compose.ui.geometry.Offset(w, h),
            ),
        )
        // Refracted beam.
        drawLine(
            color = Color.White.copy(alpha = 0.85f),
            start = androidx.compose.ui.geometry.Offset(w * 0.06f, h * 0.52f),
            end = androidx.compose.ui.geometry.Offset(w * 0.46f, h * 0.46f),
            strokeWidth = h * 0.055f,
        )
        drawLine(
            color = Color.White.copy(alpha = 0.55f),
            start = androidx.compose.ui.geometry.Offset(w * 0.52f, h * 0.48f),
            end = androidx.compose.ui.geometry.Offset(w * 0.96f, h * 0.34f),
            strokeWidth = h * 0.045f,
        )
        drawLine(
            color = accent.copy(alpha = 0.9f),
            start = androidx.compose.ui.geometry.Offset(w * 0.52f, h * 0.50f),
            end = androidx.compose.ui.geometry.Offset(w * 0.96f, h * 0.62f),
            strokeWidth = h * 0.045f,
        )
    }
}
