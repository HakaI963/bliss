package com.altaris.linux.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.altaris.linux.ui.theme.AltarisPalette
import com.altaris.linux.ui.theme.LocalAltarisPalette

/**
 * The Altaris backdrop.
 *
 * Altaris is a terminal and agent tool, so the background stays quiet: the
 * identity's base colour with one soft light source in the upper half. No
 * animation, no gradients that fight the content.
 */
@Composable
fun PrismBackdrop(
    modifier: Modifier = Modifier,
    palette: AltarisPalette = LocalAltarisPalette.current,
) {
    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(palette.base)
            // A single soft light source keeps the flat surface from feeling
            // dead without introducing a second colour.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        palette.panel.copy(alpha = if (palette.isDark) 0.85f else 1f),
                        Color.Transparent,
                    ),
                    center = Offset(size.width * 0.5f, -size.height * 0.08f),
                    radius = size.width * 1.15f,
                ),
                radius = size.width * 1.15f,
                center = Offset(size.width * 0.5f, -size.height * 0.08f),
            )
        }
    }
}
