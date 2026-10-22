package com.altaris.linux.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.altaris.linux.ui.theme.AltarisBackground
import com.altaris.linux.ui.theme.AltarisSwitchCheckedThumb
import com.altaris.linux.ui.theme.AltarisSwitchCheckedTrack
import com.altaris.linux.ui.theme.AltarisSwitchUncheckedBorder
import com.altaris.linux.ui.theme.AltarisSwitchUncheckedThumb
import com.altaris.linux.ui.theme.AltarisSwitchUncheckedTrack

/**
 * Monochrome frost switch — white track / dark thumb when on, dark grey when off.
 */
@Composable
fun AltarisSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val colors = if (isDark) {
        SwitchDefaults.colors(
            checkedThumbColor = AltarisSwitchCheckedThumb,
            checkedTrackColor = AltarisSwitchCheckedTrack,
            checkedBorderColor = AltarisSwitchCheckedTrack,
            uncheckedThumbColor = AltarisSwitchUncheckedThumb,
            uncheckedTrackColor = AltarisSwitchUncheckedTrack,
            uncheckedBorderColor = AltarisSwitchUncheckedBorder,
        )
    } else {
        SwitchDefaults.colors(
            checkedTrackColor = AltarisBackground,
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
