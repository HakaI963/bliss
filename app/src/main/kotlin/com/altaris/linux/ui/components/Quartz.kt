package com.altaris.linux.ui.components

import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altaris.linux.ui.theme.altarisMutedText

/**
 * Quartz — the compact component layer.
 *
 * Flat by rule: one hairline border, 6–8 dp radii, panel surfaces, small
 * type. No gradients, no glows, no shadows and no endless animations.
 */
object Quartz {
    val ScreenPad = 16.dp
    val Gap = 12.dp
    val RowGap = 8.dp
    val CardPad = 14.dp
    val CardShape = RoundedCornerShape(8.dp)
    val ButtonShape = RoundedCornerShape(6.dp)
    val PillShape = RoundedCornerShape(6.dp)
    val TileShape = RoundedCornerShape(6.dp)

    val TitleSize = 20.sp
    val SectionSize = 10.sp
    val RowTitleSize = 13.sp
    val RowSubSize = 11.5.sp
    val BodySize = 12.sp
    val MonoSize = 10.sp
}

@Composable
fun QScreenTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            title,
            fontSize = Quartz.TitleSize,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.3).sp,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(3.dp))
            Text(
                subtitle,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                color = altarisMutedText(),
            )
        }
    }
}

@Composable
fun QSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(start = 2.dp, top = 10.dp, bottom = 2.dp),
        fontSize = Quartz.SectionSize,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.7.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun QCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = Quartz.CardShape
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .padding(vertical = 2.dp),
        content = content,
    )
}

@Composable
fun QDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline),
    )
}

@Composable
fun QRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    divider: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill by animateFloatAsState(
        targetValue = if (pressed && onClick != null) 0.05f else 0f,
        animationSpec = spring(stiffness = 1400f),
        label = "rowFill",
    )
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = fill))
                .let {
                    if (onClick != null) {
                        it.clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClick = onClick,
                        )
                    } else {
                        it
                    }
                }
                .padding(horizontal = Quartz.CardPad, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(9.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = Quartz.RowTitleSize,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        fontSize = Quartz.RowSubSize,
                        lineHeight = 15.sp,
                        color = altarisMutedText(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            } else if (onClick != null) {
                QChevron()
            }
        }
        if (divider) {
            QDivider(Modifier.padding(start = if (leading != null) 50.dp else Quartz.CardPad))
        }
    }
}

@Composable
fun QChevron(tint: Color = MaterialTheme.colorScheme.outline) {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(16.dp),
    )
}

@Composable
fun QPill(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val bg = if (selected) scheme.onSurface else Color.Transparent
    val fg = if (selected) scheme.background else scheme.onSurfaceVariant
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = fg,
        maxLines = 1,
        modifier = modifier
            .clip(Quartz.PillShape)
            .background(bg)
            .border(1.dp, if (selected) bg else scheme.outline, Quartz.PillShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
fun QStatusRow(label: String, value: String, valueColor: Color? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Quartz.CardPad, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = Quartz.BodySize, color = altarisMutedText())
        Text(
            value,
            fontSize = Quartz.BodySize,
            fontWeight = FontWeight.Medium,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun QPrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = spring(stiffness = 1400f),
        label = "primaryFill",
    )
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .height(38.dp)
            .graphicsLayer { alpha = if (enabled) fill else 0.45f }
            .clip(Quartz.ButtonShape)
            .background(scheme.onSurface)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = scheme.background, modifier = Modifier.size(15.dp))
        }
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = scheme.background,
            maxLines = 1,
        )
    }
}

@Composable
fun QSecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill by animateFloatAsState(
        targetValue = if (pressed) 0.06f else 0f,
        animationSpec = spring(stiffness = 1400f),
        label = "secondaryFill",
    )
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .height(38.dp)
            .clip(Quartz.ButtonShape)
            .background(scheme.onSurface.copy(alpha = fill))
            .border(1.dp, scheme.outline, Quartz.ButtonShape)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = scheme.onSurface.copy(alpha = if (enabled) 1f else 0.45f),
                modifier = Modifier.size(15.dp),
            )
        }
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = scheme.onSurface.copy(alpha = if (enabled) 1f else 0.45f),
            maxLines = 1,
        )
    }
}

/**
 * Compact 36 dp control used by the clone viewer and other tool rows.
 */
@Composable
fun QToolButton(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val container = when {
        active && enabled -> scheme.onSurface
        else -> Color.Transparent
    }
    val content = when {
        !enabled -> scheme.onSurfaceVariant.copy(alpha = 0.45f)
        active -> scheme.background
        destructive -> scheme.error
        else -> scheme.onSurfaceVariant
    }
    Row(
        modifier = modifier
            .height(32.dp)
            .clip(Quartz.ButtonShape)
            .background(container)
            .border(
                1.dp,
                if (active && enabled) container else scheme.outline,
                Quartz.ButtonShape,
            )
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
        Text(
            text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = content,
            maxLines = 1,
        )
    }
}

@Composable
fun QIconTile(
    bitmap: Bitmap?,
    modifier: Modifier = Modifier,
    fallback: ImageVector = Icons.Default.Android,
    size: Dp = 34.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(Quartz.TileShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, Quartz.TileShape),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(size - 8.dp),
            )
        } else {
            Icon(
                fallback,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size * 0.5f),
            )
        }
    }
}

@Composable
fun QToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = Quartz.CardPad, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontSize = Quartz.RowTitleSize,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(subtitle, fontSize = Quartz.RowSubSize, color = altarisMutedText())
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.background,
                checkedTrackColor = MaterialTheme.colorScheme.onSurface,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surface,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline,
            ),
        )
    }
}

@Composable
fun QTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    singleLine: Boolean = true,
    secret: Boolean = false,
    minLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        minLines = minLines,
        visualTransformation = if (secret) {
            androidx.compose.ui.text.input.PasswordVisualTransformation()
        } else {
            androidx.compose.ui.text.input.VisualTransformation.None
        },
        textStyle = TextStyle(fontSize = 13.sp),
        label = label?.let { { Text(it, fontSize = 11.sp) } },
        placeholder = { Text(placeholder, fontSize = 12.sp, color = altarisMutedText()) },
        shape = Quartz.ButtonShape,
    )
}

@Composable
fun QLogPane(
    lines: List<String>,
    maxHeight: Dp = 160.dp,
    newestFirst: Boolean = true,
) {
    val shown = if (newestFirst) lines.asReversed() else lines
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .clip(Quartz.ButtonShape)
            .background(MaterialTheme.colorScheme.background)
            .border(1.dp, MaterialTheme.colorScheme.outline, Quartz.ButtonShape)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 9.dp, vertical = 7.dp),
    ) {
        if (shown.isEmpty()) {
            Text(
                "(nothing yet)",
                fontSize = Quartz.MonoSize,
                fontFamily = FontFamily.Monospace,
                color = altarisMutedText(),
            )
        } else {
            shown.forEach { line ->
                Text(
                    line,
                    fontSize = Quartz.MonoSize,
                    lineHeight = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun QEmptyState(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Quartz.CardPad, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            title,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            subtitle,
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
            color = altarisMutedText(),
            textAlign = TextAlign.Center,
        )
    }
}
