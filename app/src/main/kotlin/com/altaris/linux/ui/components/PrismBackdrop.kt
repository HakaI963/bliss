package com.altaris.linux.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import com.altaris.linux.ui.theme.AltarisPalette
import com.altaris.linux.ui.theme.LocalAltarisPalette

/**
 * The ambient scene behind the app.
 *
 * The dark identities get a faint dot lattice, the light one a line-drawn
 * tree on the left with a few leaves drifting down. Everything is drawn
 * from one animation clock, so the whole scene costs a single transition.
 */
@Composable
fun PrismBackdrop(
    modifier: Modifier = Modifier,
    palette: AltarisPalette = LocalAltarisPalette.current,
) {
    val clock = rememberInfiniteTransition(label = "ambient")
    val phase by clock.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 26000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ambientPhase",
    )

    Box(modifier = modifier.fillMaxSize().background(palette.base)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (palette.isDark) {
                drawLattice(palette.onSurface)
            } else {
                drawTree(palette.onSurface)
                drawLeaves(palette.onSurface, phase)
            }
        }
    }
}

/** A faint dot lattice: structure without noise. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLattice(ink: Color) {
    val step = 44.dp.toPx()
    var y = step
    while (y < size.height) {
        var x = step
        while (x < size.width) {
            drawCircle(
                color = ink.copy(alpha = 0.035f),
                radius = 1.1.dp.toPx(),
                center = Offset(x, y),
            )
            x += step
        }
        y += step
    }
}

/** A bare tree on the left edge, drawn as line work. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTree(ink: Color) {
    val w = size.width
    val h = size.height
    val stroke = 2.2.dp.toPx()
    val trunk = Path().apply {
        moveTo(w * 0.055f, h * 1.02f)
        cubicTo(w * 0.10f, h * 0.78f, w * 0.085f, h * 0.62f, w * 0.145f, h * 0.44f)
        cubicTo(w * 0.19f, h * 0.30f, w * 0.17f, h * 0.20f, w * 0.225f, h * 0.075f)
    }
    drawPath(path = trunk, color = ink.copy(alpha = 0.07f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke))

    val branches = listOf(
        // from-trunk, to
        floatArrayOf(w * 0.12f, h * 0.58f, w * 0.02f, h * 0.42f, w * 0.06f, h * 0.31f),
        floatArrayOf(w * 0.145f, h * 0.45f, w * 0.27f, h * 0.35f, w * 0.30f, h * 0.21f),
        floatArrayOf(w * 0.165f, h * 0.33f, w * 0.09f, h * 0.22f, w * 0.11f, h * 0.09f),
        floatArrayOf(w * 0.185f, h * 0.24f, w * 0.26f, h * 0.16f, w * 0.27f, h * 0.06f),
        floatArrayOf(w * 0.09f, h * 0.70f, w * 0.20f, h * 0.63f, w * 0.24f, h * 0.52f),
    )
    branches.forEach { b ->
        val path = Path().apply {
            moveTo(b[0], b[1])
            cubicTo(b[2], b[3], b[4], b[5], b[4] * 1.02f, b[5] * 0.98f)
        }
        drawPath(
            path = path,
            color = ink.copy(alpha = 0.06f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke * 0.7f),
        )
    }
}

/** A handful of leaves drifting down from the tree. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLeaves(ink: Color, phase: Float) {
    val count = 9
    for (i in 0 until count) {
        val seed = i / count.toFloat()
        // Each leaf owns a slice of the clock and repeats inside it.
        val local = ((phase + seed) % 1f)
        val fade = kotlin.math.sin(local * Math.PI).toFloat().coerceIn(0f, 1f)
        val startY = size.height * (0.06f + 0.10f * ((i * 37 % 11) / 11f))
        val y = startY + (size.height - startY) * local
        val sway = kotlin.math.sin((local * 6.0 + i).toFloat()) * (10f + 6f * (i % 3))
        val x = size.width * (0.03f + 0.22f * ((i * 53 % 17) / 17f)) + sway
        val scale = 0.7f + 0.5f * ((i * 29 % 7) / 7f)
        rotate(
            degrees = (i * 47f + local * 220f) % 360f,
            pivot = Offset(x, y),
        ) {
            translate(left = x, top = y) {
                val path = Path().apply {
                    moveTo(0f, 0f)
                    quadraticTo(5.4f * scale, -3.2f * scale, 10.4f * scale, 0f)
                    quadraticTo(5.4f * scale, 3.2f * scale, 0f, 0f)
                }
                drawPath(
                    path = path,
                    color = ink.copy(alpha = 0.11f * fade),
                    style = androidx.compose.ui.graphics.drawscope.Fill,
                )
            }
        }
    }
}
