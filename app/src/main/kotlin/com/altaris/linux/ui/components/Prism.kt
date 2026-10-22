package com.altaris.linux.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altaris.linux.ui.theme.AltarisMotion
import com.altaris.linux.ui.theme.Prism

/**
 * Horizontal inset for row content. A card resets it to zero so its rows sit
 * on the card padding instead of adding a second gutter.
 */
val LocalPrismInset = staticCompositionLocalOf { Prism.Gutter }

/**
 * The Prism component set.
 *
 * Every control here has its own geometry — squircle tiles, slab buttons,
 * bento cards, a sliding segmented control — so the app never reads as one
 * flat list of rows again.
 */

/**
 * A large card with a tinted wash, a gradient hairline and a press lift.
 * The accent colours the wash, which is what makes each section look
 * different at a glance.
 */
@Composable
fun PrismCard(
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(Prism.CardRadius)
    val lift by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = AltarisMotion.effects,
        label = "cardLift",
    )
    val glow by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = AltarisMotion.effects,
        label = "cardGlow",
    )
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = 1f - 0.012f * lift
                scaleY = 1f - 0.012f * lift
                translationY = 2f * lift
            }
            .shadow(
                elevation = 6.dp + 14.dp * glow,
                shape = shape,
                clip = false,
                ambientColor = accent.copy(alpha = 0.35f + 0.45f * glow),
                spotColor = accent.copy(alpha = 0.35f + 0.45f * glow),
            )
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        accent.copy(alpha = 0.13f + 0.10f * glow),
                        scheme.surface.copy(alpha = 0.94f),
                        scheme.surfaceVariant.copy(alpha = 0.72f),
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(
                    listOf(
                        accent.copy(alpha = 0.42f),
                        Color.White.copy(alpha = 0.14f),
                        accent.copy(alpha = 0.20f),
                    )
                ),
                shape = shape,
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                }
            )
            .padding(Prism.Gutter),
        content = { CompositionLocalProvider(LocalPrismInset provides 0.dp) { content() } },
    )
}

/**
 * A list row with a leading accent chip. Pressing it slides the row and
 * bleeds the accent in from the leading edge.
 */
@Composable
fun PrismRow(
    title: String,
    accent: Color,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    divider: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shift by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = AltarisMotion.fastSpatial,
        label = "rowShift",
    )
    val scheme = MaterialTheme.colorScheme
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = 4f * shift }
                .drawBehind {
                    if (shift > 0.01f) {
                        drawRect(
                            brush = Brush.horizontalGradient(
                                listOf(
                                    accent.copy(alpha = 0.22f * shift),
                                    accent.copy(alpha = 0.0f),
                                ),
                                endX = size.width * 0.55f,
                            )
                        )
                    }
                }
                .then(
                    if (onClick != null) {
                        Modifier.clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClick = onClick,
                        )
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = LocalPrismInset.current, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(13.dp))
            } else {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(scheme.surfaceVariant)
                        .border(
                            width = 1.dp,
                            color = scheme.outline,
                            shape = RoundedCornerShape(12.dp),
                        )
                )
                Spacer(Modifier.width(13.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.1).sp,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = scheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(10.dp))
                trailing()
            } else if (onClick != null) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant.copy(alpha = 0.65f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (divider) {
            Box(
                modifier = Modifier
                    .padding(start = LocalPrismInset.current)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Transparent,
                                scheme.outlineVariant.copy(alpha = 0.8f),
                                Color.Transparent,
                            )
                        )
                    )
            )
        }
    }
}

/**
 * The primary action: a slab with a sweeping gradient, an optional leading
 * glyph in a chip and a specular sheen that drifts across the surface.
 */
@Composable
fun PrismButton(
    text: String,
    accent: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(Prism.TileRadius)
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.965f else 1f,
        animationSpec = AltarisMotion.effects,
        label = "buttonScale",
    )
    val glow by animateFloatAsState(
        targetValue = if (pressed && enabled) 1f else 0f,
        animationSpec = AltarisMotion.effects,
        label = "buttonGlow",
    )
    val transition = rememberInfiniteTransition()
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
    )
    Box(
        modifier = modifier
            .height(52.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(
                elevation = 8.dp + 16.dp * glow,
                shape = shape,
                clip = false,
                ambientColor = accent.copy(alpha = 0.45f),
                spotColor = accent.copy(alpha = 0.45f),
            )
            .clip(shape)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        accent,
                        accent.copy(alpha = 0.72f),
                        accent.copy(alpha = 0.92f),
                    ),
                    startX = -220f + 520f * sweep,
                    endX = 120f + 520f * sweep,
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.42f),
                        Color.White.copy(alpha = 0.08f),
                    )
                ),
                shape = shape,
            )
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = palette.onAction,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.width(11.dp))
            }
            Text(
                text = text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.onAction,
                maxLines = 1,
            )
        }
    }
}

/** A squircle icon button with an accent tinted glass fill. */
@Composable
fun PrismIconTile(
    icon: ImageVector,
    accent: Color,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = Prism.TouchTarget,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(Prism.TileRadius)
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.93f else 1f,
        animationSpec = AltarisMotion.effects,
        label = "tileScale",
    )
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        accent.copy(alpha = 0.20f),
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(
                        accent.copy(alpha = 0.46f),
                        accent.copy(alpha = 0.10f),
                    )
                ),
                shape = shape,
            )
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
            modifier = Modifier.size((size.value * 0.42f).dp),
        )
    }
}

/** A small selectable chip. */
@Composable
fun PrismChip(
    text: String,
    accent: Color,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = AltarisMotion.effects,
        label = "chipScale",
    )
    val fill by animateColorAsState(
        targetValue = if (selected) accent.copy(alpha = 0.26f) else Color.Transparent,
        animationSpec = tween(180),
        label = "chipFill",
    )
    val border by animateColorAsState(
        targetValue = if (selected) accent.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = tween(180),
        label = "chipBorder",
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(180),
        label = "chipText",
    )
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(Prism.ChipRadius))
            .background(fill)
            .border(1.dp, border, RoundedCornerShape(Prism.ChipRadius))
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 13.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            fontSize = 12.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = textColor,
            maxLines = 1,
        )
    }
}

/**
 * A segmented control with a sliding thumb. The thumb follows the selection
 * with a spring, which reads as one moving object rather than three states.
 */
@Composable
fun PrismSegmented(
    options: List<String>,
    selectedIndex: Int,
    accent: Color,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    var rowWidth by remember { mutableIntStateOf(0) }
    val count = options.size.coerceAtLeast(1)
    val thumbWidth = if (rowWidth > 0) rowWidth / count else 0
    val offsetX by animateFloatAsState(
        targetValue = (selectedIndex.coerceIn(0, count - 1) * thumbWidth).toFloat(),
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 520f),
        label = "segmentThumb",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(Prism.ChipRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f))
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                RoundedCornerShape(Prism.ChipRadius),
            )
            .onSizeChanged { rowWidth = it.width },
    ) {
        if (thumbWidth > 0) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(offsetX.toInt(), 0) }
                    .size(width = thumbWidth.dp, height = 42.dp)
                    .padding(3.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(accent.copy(alpha = 0.55f), accent.copy(alpha = 0.22f))
                        )
                    )
                    .border(
                        1.dp,
                        accent.copy(alpha = 0.45f),
                        RoundedCornerShape(12.dp),
                    )
            )
        }
        Row(modifier = Modifier.fillMaxWidth().height(44.dp)) {
            options.forEachIndexed { index, option ->
                val selected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .height(44.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = option,
                        fontSize = 12.5.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** A pulsing status orb: a core dot with a breathing halo. */
@Composable
fun PrismOrb(
    active: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition()
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
    )
    Box(modifier = modifier.size(18.dp), contentAlignment = Alignment.Center) {
        if (active) {
            Box(
                modifier = Modifier
                    .size((10f + 7f * pulse).dp)
                    .clip(RoundedCornerShape(50))
                    .background(accent.copy(alpha = 0.22f * (1f - pulse) + 0.08f))
            )
        }
        Box(
            modifier = Modifier
                .size(if (active) 8.dp else 6.dp)
                .clip(RoundedCornerShape(50))
                .background(if (active) accent else MaterialTheme.colorScheme.outline)
        )
    }
}

/** A section label with a short accent rule instead of a plain caption. */
@Composable
fun PrismSectionLabel(
    text: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 16.dp, height = 3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent)
        )
        Text(
            text = text.uppercase(),
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
