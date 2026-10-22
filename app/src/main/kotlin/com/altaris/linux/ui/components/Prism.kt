package com.altaris.linux.ui.components

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altaris.linux.ui.theme.Prism

/**
 * The Altaris component set.
 *
 * The look follows the reference workspace: flat panels on the app
 * background, a single hairline border, 6–8 dp radii, small type and no
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
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = fg,
            maxLines = 1,
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

/** A small selectable chip. */
@Composable
fun PrismChip(
    text: String,
    accent: Color,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Prism.ButtonRadius)
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
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = fg,
        maxLines = 1,
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
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/**
 * A segmented control: a bordered strip whose active segment is filled with
 * the text colour, like the reference's `.view-tabs`.
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
    var rowWidth by remember { mutableIntStateOf(0) }
    val count = options.size.coerceAtLeast(1)
    val thumbWidth = if (rowWidth > 0) rowWidth / count else 0
    val offsetX by androidx.compose.animation.core.animateFloatAsState(
        targetValue = (selectedIndex.coerceIn(0, count - 1) * thumbWidth).toFloat(),
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 520f),
        label = "segmentThumb",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(Prism.PanelRadius))
            .background(scheme.surface)
            .border(1.dp, scheme.outline, RoundedCornerShape(Prism.PanelRadius))
            .onSizeChanged { rowWidth = it.width },
    ) {
        if (thumbWidth > 0) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(offsetX.toInt(), 0) }
                    .size(width = thumbWidth.dp, height = 34.dp)
                    .padding(horizontal = 4.dp, vertical = 3.dp)
                    .clip(RoundedCornerShape(Prism.ButtonRadius))
                    .background(scheme.onSurface)
            )
        }
        Row(modifier = Modifier.fillMaxWidth().height(36.dp)) {
            options.forEachIndexed { index, option ->
                val selected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .height(36.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = option,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (selected) scheme.background else scheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** A status dot — the reference's `.status-dot`. */
@Composable
fun PrismOrb(
    active: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.size(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    if (active) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
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
