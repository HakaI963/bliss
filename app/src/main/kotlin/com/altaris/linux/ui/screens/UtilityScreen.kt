package com.altaris.linux.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altaris.linux.ui.theme.Prism
import com.altaris.linux.ui.theme.altarisMutedText

/**
 * Utility: the things that are not a distro.
 *
 * Apps and Files used to be two separate dock destinations, which meant the
 * dock had a slot for "clone an app" and no slot for "look at a file". They are
 * both tools you reach for while working, so they live here as sub-tabs and the
 * freed dock slot goes to something that earns it.
 */
@Composable
fun UtilityScreen(
    modifier: Modifier = Modifier,
    embeddedInBottomNav: Boolean = false,
) {
    val accent = MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = if (embeddedInBottomNav) Prism.Gutter else 0.dp)
            .padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Spacer(Modifier.height(1.dp))
        // Files moved to the top bar as its own full-screen destination: a
        // dual-pane manager needs the whole screen, and a sub-tab gave it a
        // strip. Utility is apps only now.
        ClonedAppsScreen()
    }
}
