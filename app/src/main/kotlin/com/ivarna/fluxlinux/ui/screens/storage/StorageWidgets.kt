package com.ivarna.fluxlinux.ui.screens.storage

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivarna.fluxlinux.ui.components.folioSpring
import com.ivarna.fluxlinux.ui.components.folioSpringAny
import com.ivarna.fluxlinux.ui.theme.FolioDanger
import com.ivarna.fluxlinux.ui.theme.FrostSurface
import com.ivarna.fluxlinux.ui.theme.TextMuted
import com.ivarna.fluxlinux.ui.theme.TextPrimary
import com.ivarna.fluxlinux.ui.theme.TextSecondary

@Composable
fun MetaRow(label: String, trailing: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label.uppercase(),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = TextSecondary,
            modifier = Modifier.weight(1f)
        )
        trailing()
    }
}

@Composable
fun StatusBadge(text: String, ok: Boolean) {
    val fg = if (ok) TextPrimary else TextMuted
    Text(
        text,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        color = fg,
        modifier = Modifier
            .background(
                FrostSurface,
                RoundedCornerShape(50)
            )
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

/**
 * Muted frost note — replaces colored banners. Must be hosted inside a FrostContainer.
 */
@Composable
fun FrostNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = TextMuted,
        modifier = modifier.padding(horizontal = 18.dp, vertical = 12.dp)
    )
}

/**
 * Destructive pill action (kill / uninstall). Frost surface with danger-tinted label,
 * no giant red button. Press state: scale + faint fill, matching FrostPill.
 */
@Composable
fun DestructivePill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.97f else 1f,
        animationSpec = folioSpring(),
        label = "destructivePillPress"
    )
    val fill by animateColorAsState(
        targetValue = if (pressed && enabled) Color.White.copy(alpha = 0.09f) else FrostSurface,
        animationSpec = folioSpringAny(),
        label = "destructivePillFill"
    )
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.4f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(50))
            .background(fill)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = FolioDanger)
    }
}
