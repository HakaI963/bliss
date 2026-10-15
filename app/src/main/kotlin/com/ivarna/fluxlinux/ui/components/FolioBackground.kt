package com.ivarna.fluxlinux.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.ivarna.fluxlinux.ui.theme.FolioBackground

// Ambient light pool alphas — deliberately whisper-quiet (0.03–0.06).
private const val AmbientTopAlpha = 0.055f
private const val AmbientSideAlpha = 0.04f
private const val AmbientBaseAlpha = 0.035f

/**
 * Folio's ambient backdrop: a pure carbon [FolioBackground] canvas with three
 * very faint white light pools drifting on long, staggered cycles.
 *
 * Purely decorative — it installs no pointer input of any kind, so it never
 * consumes touches when layered behind or over interactive content.
 */
@Composable
fun FolioAnimatedBackground(modifier: Modifier = Modifier) {
    val ambient = rememberInfiniteTransition(label = "folioAmbient")
    val driftTop by ambient.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 24_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ambientTop"
    )
    val driftSide by ambient.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 32_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ambientSide"
    )
    val driftBase by ambient.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 40_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ambientBase"
    )

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(FolioBackground)
    ) {
        drawAmbientPools(top = driftTop, side = driftSide, base = driftBase)
    }
}

private fun DrawScope.drawAmbientPools(top: Float, side: Float, base: Float) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    val span = maxOf(w, h)

    // Top light: broad, faintest of all, sliding gently across the upper third.
    lightPool(
        center = Offset(w * (0.30f + 0.07f * top), h * (0.08f + 0.045f * side)),
        radius = span * 0.80f,
        alpha = AmbientTopAlpha
    )
    // Side wash: soft light hugging the right edge, mid-height.
    lightPool(
        center = Offset(w * (0.90f - 0.05f * side), h * (0.50f + 0.07f * base)),
        radius = span * 0.62f,
        alpha = AmbientSideAlpha
    )
    // Base ember: low ambient rising from the bottom left.
    lightPool(
        center = Offset(w * (0.25f + 0.08f * base), h * (0.95f - 0.04f * top)),
        radius = span * 0.68f,
        alpha = AmbientBaseAlpha
    )
}

private fun DrawScope.lightPool(center: Offset, radius: Float, alpha: Float) {
    if (radius <= 0f || alpha <= 0f) return
    // Fade to white-with-zero-alpha (not Color.Transparent) to avoid a grey fringe.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.White.copy(alpha = alpha), Color.White.copy(alpha = 0f)),
            center = center,
            radius = radius
        ),
        radius = radius,
        center = center
    )
}
