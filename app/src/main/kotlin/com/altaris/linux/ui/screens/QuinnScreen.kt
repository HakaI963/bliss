package com.altaris.linux.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altaris.linux.ui.components.AltarisTitle
import com.altaris.linux.ui.components.PrismNote
import com.altaris.linux.ui.components.PrismOrb
import com.altaris.linux.ui.theme.AltarisSection
import com.altaris.linux.ui.theme.Prism
import com.altaris.linux.ui.theme.altarisMutedText
import com.altaris.linux.ui.theme.sectionAccent

/**
 * Quinn — the AI surface.
 *
 * Intentionally empty for this build: the tab and its identity are in place so
 * the rest of the app (dock order, accent, palette entries) is final, and the
 * assistant lands here without moving anything again.
 *
 * The placeholder is a calm, centred mark rather than an empty list: a blank
 * screen with a spinner reads as a failure, and this is a deliberate state.
 */
@Composable
fun QuinnScreen(modifier: Modifier = Modifier) {
    val accent = sectionAccent(AltarisSection.QUINN)
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Prism.Gutter)
            .padding(top = 10.dp),
    ) {
        AltarisTitle(
            "Quinn",
            "Your assistant for Altaris",
            accent,
        )
        Spacer(Modifier.height(Prism.Stack))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Prism.Tight),
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(Prism.TileRadius * 2))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(Prism.TileRadius * 2),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    PrismOrb(active = false, accent = accent)
                }
                Spacer(Modifier.height(Prism.Tight))
                Text(
                    text = "Quinn is not here yet",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "This space is reserved for the assistant. It will answer questions " +
                        "about your distros, run installs and report what finished, using " +
                        "whichever coding agent you already have installed.",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = altarisMutedText(),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                Spacer(Modifier.height(Prism.Tight))
                PrismNote(
                    "Nothing to set up, and nothing running in the background.",
                    color = altarisMutedText(),
                )
            }
        }
    }
}
