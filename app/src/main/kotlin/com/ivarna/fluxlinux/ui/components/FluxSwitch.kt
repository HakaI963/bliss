package com.ivarna.fluxlinux.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.ivarna.fluxlinux.ui.theme.FolioBackground
import com.ivarna.fluxlinux.ui.theme.FolioSwitchCheckedThumb
import com.ivarna.fluxlinux.ui.theme.FolioSwitchCheckedTrack
import com.ivarna.fluxlinux.ui.theme.FolioSwitchUncheckedBorder
import com.ivarna.fluxlinux.ui.theme.FolioSwitchUncheckedThumb
import com.ivarna.fluxlinux.ui.theme.FolioSwitchUncheckedTrack

/**
 * Monochrome frost switch — white track / dark thumb when on, dark grey when off.
 */
@Composable
fun FluxSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val colors = if (isDark) {
        SwitchDefaults.colors(
            checkedThumbColor = FolioSwitchCheckedThumb,
            checkedTrackColor = FolioSwitchCheckedTrack,
            checkedBorderColor = FolioSwitchCheckedTrack,
            uncheckedThumbColor = FolioSwitchUncheckedThumb,
            uncheckedTrackColor = FolioSwitchUncheckedTrack,
            uncheckedBorderColor = FolioSwitchUncheckedBorder,
        )
    } else {
        SwitchDefaults.colors(
            checkedTrackColor = FolioBackground,
            checkedThumbColor = Color.White
        )
    }

    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = colors,
        enabled = enabled,
        modifier = modifier
    )
}
