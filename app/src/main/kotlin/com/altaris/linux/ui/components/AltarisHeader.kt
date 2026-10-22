package com.altaris.linux.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
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
 * A flat strip with a hairline underneath — the reference's workspace
 * topbar. The mark sits in a filled tile, the wordmark and the live status
 * sit next to it, and the actions live on the right.
 */
@Composable
fun AltarisCommandBar(
    accent: Color,
    modifier: Modifier = Modifier,
    statusLabel: String,
    live: Boolean,
    onOpenPalette: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHostShell: (() -> Unit)? = null,
    taskButton: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(scheme.surface)
            .padding(horizontal = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(Prism.TopBarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AltarisMark(size = Prism.MarkSize)
            Spacer(Modifier.width(9.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "ALTARIS",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp,
                    color = scheme.onSurface,
                    maxLines = 1,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 1.dp),
                ) {
                    PrismOrb(active = live, accent = accent)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = statusLabel,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.6.sp,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            if (taskButton != null) {
                taskButton()
                Spacer(Modifier.width(6.dp))
            }
            if (onOpenHostShell != null) {
                BarButton(
                    icon = Icons.Default.Terminal,
                    contentDescription = "Host shell",
                    onClick = onOpenHostShell,
                )
                Spacer(Modifier.width(6.dp))
            }
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
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(scheme.outline)
        )
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
            .size(30.dp)
            .clip(shape)
            .background(if (pressed) scheme.onSurface.copy(alpha = 0.10f) else Color.Transparent)
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
            modifier = Modifier.size(15.dp),
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
                (fadeIn(tween(160)) + slideInVertically(
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
 * The Altaris mark: a prism that splits a beam — the stone the product is
 * named after. It sits in a filled tile so the wordmark has a strong anchor.
 */
@Composable
fun AltarisMark(
    modifier: Modifier = Modifier,
    size: Dp = Prism.MarkSize,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(7.dp)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(scheme.onSurface),
        contentAlignment = Alignment.Center,
    ) {
        val ink = scheme.background
        Canvas(modifier = Modifier.size(size * 0.62f)) {
            val w = this.size.width
            val h = this.size.height
            val path = Path().apply {
                moveTo(w * 0.12f, h * 0.80f)
                lineTo(w * 0.50f, h * 0.16f)
                lineTo(w * 0.88f, h * 0.80f)
                close()
            }
            drawPath(path, color = ink)
            drawLine(
                color = ink.copy(alpha = 0.55f),
                start = androidx.compose.ui.geometry.Offset(w * 0.04f, h * 0.50f),
                end = androidx.compose.ui.geometry.Offset(w * 0.40f, h * 0.44f),
                strokeWidth = h * 0.07f,
            )
        }
    }
}

/** A small chevron used by list rows. */
@Composable
fun PrismChevron(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.size(15.dp),
    )
}
