package com.ivarna.fluxlinux.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Bliss Midnight backdrop: a near-black wash, one soft mint signal bloom and
 * a static technical grid. No drifting blobs, no continuous animation.
 */
@Composable
fun FolioAnimatedBackground(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val step = with(LocalDensity.current) { 28.dp.toPx() }
    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(
            brush = Brush.verticalGradient(
                0f to colors.background,
                0.55f to colors.background,
                1f to colors.surface.copy(alpha = 0.86f),
                endY = size.height,
            )
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    colors.primary.copy(alpha = 0.10f),
                    colors.primary.copy(alpha = 0.025f),
                    colors.background.copy(alpha = 0f),
                ),
                center = Offset(size.width * 0.82f, size.height * 0.06f),
                radius = size.width * 0.95f,
            ),
            radius = size.width * 0.95f,
            center = Offset(size.width * 0.82f, size.height * 0.06f),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    colors.secondary.copy(alpha = 0.055f),
                    colors.background.copy(alpha = 0f),
                ),
                center = Offset(size.width * 0.08f, size.height * 0.86f),
                radius = size.width * 0.8f,
            ),
            radius = size.width * 0.8f,
            center = Offset(size.width * 0.08f, size.height * 0.86f),
        )
        val line = colors.primary.copy(alpha = 0.035f)
        var x = 0f
        while (x < size.width) {
            drawLine(line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            x += step
        }
        var y = 0f
        while (y < size.height) {
            drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            y += step
        }
    }
}
