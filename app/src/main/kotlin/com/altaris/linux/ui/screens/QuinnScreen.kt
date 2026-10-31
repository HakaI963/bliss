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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
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
import com.altaris.linux.core.quinn.QuinnEngine
import com.altaris.linux.core.rea.ReaDistro
import com.altaris.linux.ui.components.PrismOrb
import com.altaris.linux.ui.screens.quinn.QuinnChatTab
import com.altaris.linux.ui.screens.quinn.QuinnReaTab
import com.altaris.linux.ui.theme.AltarisSection
import com.altaris.linux.ui.theme.Prism
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
 * A compact identity row and two sub-tabs, left to right: **Chat** talks to
 * every coding agent installed in every distro, and **REA** is the analysis
 * dashboard. The old descriptive subtitle ("your assistant for Altaris") is
 * gone; the header names the section and nothing else.
 */
@Composable
fun QuinnScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val accent = sectionAccent(AltarisSection.QUINN)
    var tab by remember { mutableStateOf(QuinnTab.CHAT) }
    val rea by ReaDistro.state.collectAsState()
    val targets by QuinnEngine.targets.collectAsState()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { ReaDistro.refresh(context) }
        QuinnEngine.refreshTargets(context)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Prism.Gutter)
            .padding(top = 8.dp),
    ) {
        QuinnHeader(accent, tab, rea.installed, targets.size) { tab = it }
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
    targetCount: Int,
    onSelect: (QuinnTab) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Tabs start at the left edge and read left to right.
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
        Spacer(Modifier.weight(1f))
        // A quiet live indicator: how many targets Quinn can reach, and whether
        // the analysis environment is ready.
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrismOrb(active = targetCount > 0, accent = accent)
            Spacer(Modifier.width(6.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "$targetCount agents",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (targetCount > 0) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (reaInstalled) "REA ready" else "REA off",
                    fontSize = 8.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            .heightIn(min = 32.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) accent else scheme.onSurface,
        )
    }
}
