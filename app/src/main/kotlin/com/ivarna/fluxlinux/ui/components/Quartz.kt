package com.ivarna.fluxlinux.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivarna.fluxlinux.ui.theme.fluxMutedText

/**
 * Quartz — Altaris compact component layer.
 *
 * Rules: 20 dp screen padding, layered midnight surfaces with a light-catcher
 * edge, 58 dp list rows, 40 dp controls, mint section labels, 22 dp cards.
 */
object Quartz {
    val ScreenPad = 20.dp
    val Gap = 14.dp
    val RowGap = 8.dp
    val CardPad = 18.dp
    val CardShape = RoundedCornerShape(22.dp)
    val ButtonShape = RoundedCornerShape(14.dp)
    val PillShape = RoundedCornerShape(50)
    val TileShape = RoundedCornerShape(14.dp)

    val TitleSize = 28.sp
    val SectionSize = 10.5.sp
    val RowTitleSize = 14.5.sp
    val RowSubSize = 12.sp
    val BodySize = 13.sp
    val MonoSize = 10.sp
}

@Composable
fun QScreenTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            title,
            fontSize = Quartz.TitleSize,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.4).sp,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(3.dp))
            Text(
                subtitle,
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                color = fluxMutedText(),
            )
        }
    }
}

@Composable
fun QSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp),
        fontSize = Quartz.SectionSize,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.25.sp,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
fun QCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = Quartz.CardShape
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        colors.surface.copy(alpha = 0.98f),
                        colors.surfaceVariant.copy(alpha = 0.62f),
                    ),
                    start = Offset.Zero,
                    end = Offset(900f, 1400f),
                )
            )
            .border(1.dp, colors.outlineVariant.copy(alpha = 0.92f), shape)
            .drawWithContent {
                drawContent()
                // Light-catcher edge: a single mint highlight, strongest at the
                // leading corner, fading before the card midpoint.
                drawLine(
                    brush = Brush.horizontalGradient(
                        listOf(
                            colors.primary.copy(alpha = 0.34f),
                            colors.primary.copy(alpha = 0.08f),
                            Color.Transparent,
                        ),
                        0f,
                        size.width,
                    ),
                    start = Offset.Zero,
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .padding(vertical = 2.dp),
        content = content,
    )
}

@Composable
fun QDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(0.75.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f)),
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
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && onClick != null) 0.985f else 1f,
        animationSpec = folioSpring(),
        label = "rowPress",
    )
    val pressFill by animateColorAsState(
        targetValue = if (pressed && onClick != null) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(150),
        label = "rowFill",
    )
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 58.dp)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .background(pressFill)
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
                Spacer(Modifier.width(14.dp))
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
                        lineHeight = 16.sp,
                        color = fluxMutedText(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(10.dp))
                trailing()
            } else if (onClick != null) {
                QChevron()
            }
        }
        if (divider) {
            QDivider(Modifier.padding(start = if (leading != null) 64.dp else Quartz.CardPad))
        }
    }
}

@Composable
fun QChevron(tint: Color = MaterialTheme.colorScheme.outline) {
    Icon(
        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(20.dp),
    )
}

@Composable
fun QPill(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    val edge = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    else MaterialTheme.colorScheme.outlineVariant
    Text(
        text,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.Medium,
        color = fg,
        maxLines = 1,
        modifier = modifier
            .clip(Quartz.PillShape)
            .background(bg)
            .border(1.dp, edge, Quartz.PillShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp),
    )
}

@Composable
fun QStatusRow(label: String, value: String, valueColor: Color? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Quartz.CardPad, vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = Quartz.BodySize, color = fluxMutedText())
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
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.97f else 1f,
        animationSpec = folioSpring(),
        label = "primaryPress",
    )
    Button(
        onClick = onClick,
        modifier = modifier
            .height(40.dp)
            .defaultMinSize(minWidth = 88.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        enabled = enabled,
        shape = Quartz.ButtonShape,
        contentPadding = PaddingValues(horizontal = 16.dp),
        interactionSource = interaction,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
        ),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
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
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.97f else 1f,
        animationSpec = folioSpring(),
        label = "secondaryPress",
    )
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .height(40.dp)
            .defaultMinSize(minWidth = 88.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        enabled = enabled,
        shape = Quartz.ButtonShape,
        contentPadding = PaddingValues(horizontal = 16.dp),
        border = BorderStroke(
            1.dp,
            if (pressed) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
            else MaterialTheme.colorScheme.outline,
        ),
        interactionSource = interaction,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.primary,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/**
 * Compact 36 dp control used by the clone viewer and other tool rows.
 * It keeps a visible label so the action is never ambiguous.
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
    val container = when {
        destructive -> MaterialTheme.colorScheme.errorContainer
        active -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when {
        destructive -> MaterialTheme.colorScheme.onErrorContainer
        active -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.96f else 1f,
        animationSpec = folioSpring(),
        label = "toolPress",
    )
    Row(
        modifier = modifier
            .height(36.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            .border(
                1.dp,
                if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(12.dp),
            )
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = content.copy(alpha = if (enabled) 1f else 0.45f),
            modifier = Modifier.size(15.dp),
        )
        Text(
            text,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
            color = content.copy(alpha = if (enabled) 1f else 0.45f),
            maxLines = 1,
        )
    }
}

@Composable
fun QIconTile(
    bitmap: Bitmap?,
    modifier: Modifier = Modifier,
    fallback: ImageVector = Icons.Default.Android,
    size: Dp = 38.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(Quartz.TileShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                fallback,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size / 2),
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
            .heightIn(min = 58.dp)
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
                Text(subtitle, fontSize = Quartz.RowSubSize, color = fluxMutedText())
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
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
        textStyle = TextStyle(fontSize = 14.sp),
        label = label?.let { { Text(it, fontSize = 12.sp) } },
        placeholder = { Text(placeholder, fontSize = 13.sp, color = fluxMutedText()) },
        shape = RoundedCornerShape(12.dp),
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
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.7f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        if (shown.isEmpty()) {
            Text(
                "(nothing yet)",
                fontSize = Quartz.MonoSize,
                fontFamily = FontFamily.Monospace,
                color = fluxMutedText(),
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
            .padding(horizontal = Quartz.CardPad, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            title,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            subtitle,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = fluxMutedText(),
            textAlign = TextAlign.Center,
        )
    }
}
