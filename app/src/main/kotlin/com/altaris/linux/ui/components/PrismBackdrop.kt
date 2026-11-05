package com.altaris.linux.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.altaris.linux.ui.theme.AltarisPalette
import com.altaris.linux.ui.theme.LocalAltarisPalette

/**
 * The ambient scene behind the app.
 *
 * It used to animate: a 26-second infinite transition drove a handful of
 * leaves across the light identity's tree. That single transition ran on every
 * screen, for the whole life of the process, and — because Compose animations
 * invalidate the frame — it kept the app from ever reaching a still frame.
 * Combined with the route transition, opening a file composed two screens
 * while a full-screen animation was already redrawing, which is the stall that
 * was reported as "opening a file freezes".
 *
 * A decorative background has no business costing a frame. Both layers are
 * now rasterised once and drawn as images. Nothing here animates.
 */
@Composable
fun PrismBackdrop(
    modifier: Modifier = Modifier,
    palette: AltarisPalette = LocalAltarisPalette.current,
) {
    // Rasterise once per identity. The lattice and the tree never change, and
    // the leaves are now fixed positions rather than a moving field.
    val layer = remember(palette.isDark, palette.onSurface, palette.base) {
        drawStaticLayer(palette)
    }
    Box(modifier = modifier.fillMaxSize().background(palette.base)) {
        if (layer != null) {
            androidx.compose.foundation.Image(
                bitmap = layer,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.FillBounds,
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (palette.isDark) drawLattice(palette.onSurface) else drawTree(palette.onSurface)
            }
        }
    }
}

/**
 * Rasterise the whole backdrop once.
 *
 * Returns null when the bitmap cannot be allocated, in which case the caller
 * falls back to drawing directly — a decorative background must never crash the
 * screen.
 */
private fun drawStaticLayer(palette: AltarisPalette): androidx.compose.ui.graphics.ImageBitmap? =
    runCatching {
        val width = 720
        val height = 1280
        val bitmap = androidx.compose.ui.graphics.ImageBitmap(width, height)
        val canvas = androidx.compose.ui.graphics.Canvas(bitmap)
        val scope = androidx.compose.ui.graphics.drawscope.CanvasDrawScope()
        scope.draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = canvas,
            size = androidx.compose.ui.geometry.Size(width.toFloat(), height.toFloat()),
        ) {
            if (palette.isDark) {
                drawLattice(palette.onSurface)
            } else {
                drawTree(palette.onSurface)
                drawLeaves(palette.onSurface)
            }
        }
        bitmap
    }.getOrNull()

/** A faint dot lattice: structure without noise. */
private fun DrawScope.drawLattice(ink: Color) {
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
private fun DrawScope.drawTree(ink: Color) {
    val w = size.width
    val h = size.height
    val stroke = 2.2.dp.toPx()
    val trunk = Path().apply {
        moveTo(w * 0.055f, h * 1.02f)
        cubicTo(w * 0.10f, h * 0.78f, w * 0.085f, h * 0.62f, w * 0.145f, h * 0.44f)
        cubicTo(w * 0.19f, h * 0.30f, w * 0.17f, h * 0.20f, w * 0.225f, h * 0.075f)
    }
    drawPath(path = trunk, color = ink.copy(alpha = 0.045f), style = Stroke(width = stroke))

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
            color = ink.copy(alpha = 0.038f),
            style = Stroke(width = stroke * 0.7f),
        )
    }
}

/**
 * A handful of leaves hanging near the tree.
 *
 * They used to drift on a shared clock. A decorative motion that never stops
 * is not worth a permanently invalidating frame, so they are placed once, at
 * deterministic offsets, and drawn into the cached layer.
 */
private fun DrawScope.drawLeaves(ink: Color) {
    val count = 9
    for (i in 0 until count) {
        val x = size.width * (0.03f + 0.22f * ((i * 53 % 17) / 17f)) +
            kotlin.math.sin(i.toFloat() * 1.7f) * 12f
        val y = size.height * (0.06f + 0.10f * ((i * 37 % 11) / 11f)) +
            size.height * 0.55f * ((i * 29 % 13) / 13f)
        val scale = 0.7f + 0.5f * ((i * 29 % 7) / 7f)
        val path = Path().apply {
            moveTo(0f, 0f)
            quadraticBezierTo(5.4f * scale, -3.2f * scale, 10.4f * scale, 0f)
            quadraticBezierTo(5.4f * scale, 3.2f * scale, 0f, 0f)
        }
        translate(left = x, top = y) {
            drawPath(
                path = path,
                color = ink.copy(alpha = 0.06f),
                style = Fill,
            )
        }
    }
}
