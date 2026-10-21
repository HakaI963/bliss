package com.ivarna.fluxlinux.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Altaris backdrop: a quiet near-black canvas lit by three restrained aurora
 * blooms (mint, ice, violet). Static by design — the glass surfaces and press
 * feedback provide depth without a continuously animating background.
 */
@Composable
fun FolioAnimatedBackground(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Canvas(modifier = modifier.fillMaxSize()) {
        // Ink base with a slightly lifted lower-right corner.
        drawRect(
            brush = Brush.linearGradient(
                0f to colors.background,
                0.55f to colors.background,
                1f to colors.surface.copy(alpha = 0.92f),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            )
        )

        // Mint halo behind the top bar / hero content.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    colors.primary.copy(alpha = 0.13f),
                    colors.primary.copy(alpha = 0.035f),
                    Color.Transparent,
                ),
                center = Offset(size.width * 0.86f, size.height * 0.01f),
                radius = size.width * 1.05f,
            ),
            radius = size.width * 1.05f,
            center = Offset(size.width * 0.86f, size.height * 0.01f),
        )

        // Ice counter-light low on the left, kept below content contrast.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    colors.secondary.copy(alpha = 0.075f),
                    Color.Transparent,
                ),
                center = Offset(size.width * 0.02f, size.height * 0.76f),
                radius = size.width * 0.9f,
            ),
            radius = size.width * 0.9f,
            center = Offset(size.width * 0.02f, size.height * 0.76f),
        )

        // A restrained violet ribbon far below the fold.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    colors.tertiary.copy(alpha = 0.05f),
                    Color.Transparent,
                ),
                center = Offset(size.width * 0.72f, size.height * 1.04f),
                radius = size.width * 0.8f,
            ),
            radius = size.width * 0.8f,
            center = Offset(size.width * 0.72f, size.height * 1.04f),
        )

        // Edge vignette keeps the glass cards visually anchored.
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, colors.background.copy(alpha = 0.42f)),
                center = Offset(size.width * 0.5f, size.height * 0.42f),
                radius = size.maxDimension * 0.82f,
            )
        )
    }
}
