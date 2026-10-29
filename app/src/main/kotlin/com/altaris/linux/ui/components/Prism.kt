package com.altaris.linux.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altaris.linux.ui.theme.Prism
import kotlin.math.roundToInt
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat

/**
 * The Altaris component set.
 *
 * The look follows the reference workspace: flat panels on the app
 * background, a single hairline border, 6–10 dp radii, small type and no
 * gradients, tints or glass. Colour is only ever used for text and for the
 * primary action.
 */

/**
 * A flat panel: the reference's `.panel` — panel background, one hairline
 * border and an 8 dp radius. No shadow, no gradient.
 */
@Composable
fun PrismCard(
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Prism.PanelRadius)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(scheme.surfaceVariant)
            .border(1.dp, scheme.outline, shape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                }
            )
            .padding(Prism.PanelPad),
        content = content,
    )
}

/** A hairline rule inside a panel. */
@Composable
fun PrismDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline)
    )
}

/**
 * A list row: flat, with a hairline divider and a hover surface. The
 * reference's `.nav-item` / list row.
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
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (pressed) scheme.onSurface.copy(alpha = 0.05f) else Color.Transparent)
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
                .padding(horizontal = Prism.RowPadH, vertical = Prism.RowPadV),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(9.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-0.1).sp,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
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
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
        if (divider) {
            Box(
                modifier = Modifier
                    .padding(start = Prism.RowPadH)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(scheme.outline)
            )
        }
    }
}

/**
 * The primary action: a filled button in the text colour, exactly like the
 * reference's `.btn-primary`.
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
    PrismAction(
        text = text,
        icon = icon,
        enabled = enabled,
        filled = true,
        modifier = modifier,
        onClick = onClick,
    )
}

/**
 * The secondary action: transparent with a hairline border, like the
 * reference's `.btn-secondary`.
 */
@Composable
fun PrismSecondaryButton(
    text: String,
    accent: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    PrismAction(
        text = text,
        icon = icon,
        enabled = enabled,
        filled = false,
        modifier = modifier,
        onClick = onClick,
    )
}

@Composable
private fun PrismAction(
    text: String,
    icon: ImageVector?,
    enabled: Boolean,
    filled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(Prism.ButtonRadius)
    val bg = when {
        !enabled -> scheme.surfaceVariant
        filled -> scheme.onSurface
        else -> Color.Transparent
    }
    val fg = when {
        !enabled -> scheme.onSurfaceVariant
        filled -> scheme.background
        else -> scheme.onSurface
    }
    Row(
        modifier = modifier
            .clip(shape)
            .background(if (pressed) bg.copy(alpha = 0.85f) else bg)
            .border(
                width = 1.dp,
                color = if (filled) bg else scheme.outline,
                shape = shape,
            )
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .heightIn(min = Prism.ButtonHeight)
            .padding(horizontal = Prism.ButtonPadH, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(15.dp),
            )
        }
        Text(
            text = text,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A small square icon button with a hairline border. */
@Composable
fun PrismIconTile(
    icon: ImageVector,
    accent: Color,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 30.dp,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(Prism.ButtonRadius)
    Box(
        modifier = modifier
            .size(size)
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
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

/**
 * A small selectable chip — the reference's `.chip`. The active chip is
 * filled with the text colour, the rest are outlined.
 */
@Composable
fun PrismChip(
    text: String,
    accent: Color,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Prism.ChipRadius)
    val bg by animateColorAsState(
        targetValue = if (selected) scheme.onSurface else Color.Transparent,
        animationSpec = tween(140),
        label = "chipBg",
    )
    val fg by animateColorAsState(
        targetValue = if (selected) scheme.background else scheme.onSurfaceVariant,
        animationSpec = tween(140),
        label = "chipFg",
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, if (selected) bg else scheme.outline, shape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                }
            )
            .heightIn(min = Prism.ChipHeight)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = fg,
            maxLines = 1,
        )
    }
}

/**
 * A segmented control: a bordered strip whose active segment is a raised
 * plate, like the reference's `.view-tabs`.
 */
@Composable
fun PrismSegmented(
    options: List<String>,
    selectedIndex: Int,
    accent: Color,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    var rowWidthPx by remember { mutableIntStateOf(0) }
    val count = options.size.coerceAtLeast(1)
    // The strip is measured in pixels; the thumb has to be laid out in dp,
    // otherwise it overflows the segment it belongs to.
    val thumbWidth = with(density) { (rowWidthPx.toDp() / count) }
    val offsetX by androidx.compose.animation.core.animateDpAsState(
        targetValue = thumbWidth * selectedIndex.coerceIn(0, count - 1),
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 620f),
        label = "segmentThumb",
    )
    val inset = 3.dp
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(RoundedCornerShape(Prism.PanelRadius))
            .background(scheme.surface)
            .border(1.dp, scheme.outline, RoundedCornerShape(Prism.PanelRadius))
            .onSizeChanged { rowWidthPx = it.width },
    ) {
        if (rowWidthPx > 0) {
            Box(
                modifier = Modifier
                    // offsetX is a Dp; the offset lambda works in pixels.
                    .offset { IntOffset(with(density) { offsetX.toPx() }.roundToInt(), 0) }
                    .padding(horizontal = inset, vertical = inset)
                    .width(thumbWidth - inset * 2)
                    .height(34.dp - inset * 2)
                    .clip(RoundedCornerShape(Prism.ButtonRadius))
                    .background(scheme.surfaceVariant)
                    .border(1.dp, scheme.outline, RoundedCornerShape(Prism.ButtonRadius))
            )
        }
        Row(modifier = Modifier.fillMaxWidth().height(34.dp)) {
            options.forEachIndexed { index, option ->
                val selected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .height(34.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = option,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** A status dot — the reference's `.status-dot`. It breathes while live. */
@Composable
fun PrismOrb(
    active: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val pulse = rememberInfiniteTransition(label = "orbPulse")
    val halo by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "orbHalo",
    )
    val haloScale = if (active) 0.6f + halo * 1.4f else 0f
    Box(
        modifier = modifier.size(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (active) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .graphicsLayer {
                        scaleX = haloScale
                        scaleY = haloScale
                        alpha = (1f - halo) * 0.45f
                    }
                    .clip(RoundedCornerShape(50))
                    .background(scheme.onSurface)
            )
        }
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    if (active) {
                        scheme.onSurface
                    } else {
                        scheme.onSurfaceVariant.copy(alpha = 0.5f)
                    }
                )
        )
    }
}

/** A small uppercase group label. */
@Composable
fun PrismSectionLabel(
    text: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        modifier = modifier.padding(horizontal = 2.dp, vertical = 4.dp),
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * A compact statistic: a big value over a quiet label. Used in the status
 * strip on Home.
 */
@Composable
fun PrismStat(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            text = value,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.3).sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Text(
            text = label.uppercase(),
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.7.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** A row of statistics separated by hairlines. */
@Composable
fun PrismStatRow(
    stats: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        stats.forEachIndexed { index, (value, label) ->
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(26.dp)
                        .background(scheme.outline)
                )
                Spacer(Modifier.width(12.dp))
            }
            PrismStat(value = value, label = label)
            if (index < stats.lastIndex) Spacer(Modifier.weight(1f))
        }
    }
}

/**
 * A stack of full-width actions. Every tools screen uses it so actions keep the
 * same height and the same 10 dp rhythm instead of sitting flush against the
 * row above them.
 */
@Composable
fun PrismActions(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Prism.ControlGap),
        content = content,
    )
}

/** A quiet paragraph under a control: hints, error text, install results. */
@Composable
fun PrismNote(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth().padding(horizontal = 2.dp),
        fontSize = 11.5.sp,
        lineHeight = 16.sp,
        color = color,
    )
}

/**
 * A labelled block inside a panel: an uppercase label with real breathing room
 * above it, so consecutive sections never look glued together.
 */
@Composable
fun PrismGroup(
    label: String?,
    accent: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Prism.Tight),
    ) {
        if (!label.isNullOrBlank()) PrismSectionLabel(label, accent)
        content()
    }
}
