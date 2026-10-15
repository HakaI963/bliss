package com.ivarna.fluxlinux.ui.screens.storage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * no giant red button.
 */
@Composable
fun DestructivePill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(50))
            .background(FrostSurface)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = FolioDanger)
    }
}
