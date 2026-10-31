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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altaris.linux.core.rea.ReaDistro
import com.altaris.linux.ui.components.PrismOrb
import com.altaris.linux.ui.screens.quinn.QuinnChatTab
import com.altaris.linux.ui.screens.quinn.QuinnReaTab
import com.altaris.linux.ui.theme.AltarisSection
import com.altaris.linux.ui.theme.Prism
import com.altaris.linux.ui.theme.altarisMutedText
import com.altaris.linux.ui.theme.sectionAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Quinn's two surfaces. */
private enum class QuinnTab(val label: String) {
    CHAT("Chat"),
    REA("REA"),
}

/**
 * Quinn — the AI surface.
 *
 * Two sub-tabs instead of a page title: **Chat** talks to every coding agent
 * installed in every distro, and **REA** is the analysis environment. The
 * header is a compact identity row plus the tab switch, so the content gets the
 * screen.
 */
@Composable
fun QuinnScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val accent = sectionAccent(AltarisSection.QUINN)
    var tab by remember { mutableStateOf(QuinnTab.CHAT) }
    var rea by remember { mutableStateOf(ReaDistro.state.value) }

    LaunchedEffect(Unit) {
        rea = withContext(Dispatchers.IO) {
            ReaDistro.refresh(context)
            ReaDistro.state.value
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Prism.Gutter)
            .padding(top = 8.dp),
    ) {
        QuinnHeader(accent, tab, rea.installed) { tab = it }
        Spacer(Modifier.height(Prism.Stack))
        when (tab) {
            QuinnTab.CHAT -> QuinnChatTab(accent, Modifier.weight(1f))
            QuinnTab.REA -> QuinnReaTab(accent, Modifier.weight(1f))
        }
    }
}

@Composable
private fun QuinnHeader(
    accent: Color,
    current: QuinnTab,
    reaInstalled: Boolean,
    onSelect: (QuinnTab) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PrismOrb(active = true, accent = accent)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "QUINN",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.4.sp,
                color = accent,
            )
            Text(
                if (reaInstalled) "Master of your agents · REA ready"
                else "Master of your agents · REA not installed",
                fontSize = 10.sp,
                color = altarisMutedText(),
                maxLines = 1,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            QuinnTab.entries.forEach { entry ->
                HeaderTab(
                    label = entry.label,
                    accent = accent,
                    selected = entry == current,
                    onClick = { onSelect(entry) },
                )
            }
        }
    }
}

@Composable
private fun HeaderTab(
    label: String,
    accent: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Prism.ChipRadius)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.12f) else Color.Transparent)
            .border(1.dp, if (selected) accent else scheme.outline, shape)
            .clickable(onClick = onClick)
            .heightIn(min = Prism.ChipHeight)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 11.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) accent else scheme.onSurface,
        )
    }
}
