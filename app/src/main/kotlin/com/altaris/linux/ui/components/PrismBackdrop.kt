package com.altaris.linux.ui.components

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.altaris.linux.ui.theme.AltarisPalette
import com.altaris.linux.ui.theme.LocalAltarisPalette

/**
 * Tracks the last touch point so the backdrop can light up under the finger.
 */
class PrismSpotlight {
    var position by mutableStateOf<Offset?>(null)
        private set

    var strength by mutableFloatStateOf(0f)
        private set

    private var lastTouch = 0L

    /** Called for every pointer move inside the app. */
    fun onTouch(point: Offset) {
        position = point
        strength = 1f
        lastTouch = SystemClock.uptimeMillis()
    }

    /** Fades the light out when the user stops touching. */
    fun decay() {
        val idle = SystemClock.uptimeMillis() - lastTouch
        if (idle > 120L && strength > 0f) {
            strength = (strength - 0.035f).coerceAtLeast(0f)
        }
    }
}

/**
 * The Altaris backdrop.
 *
 * Three layers: the animated aurora (AGSL), a faint technical grid that
 * gives the canvas a sense of depth, and a touch spotlight that follows the
 * finger and fades away. The grid and the light are what make the surface
 * feel alive instead of painted.
 */
@Composable
fun PrismBackdrop(
    modifier: Modifier = Modifier,
    palette: AltarisPalette = LocalAltarisPalette.current,
    spotlight: PrismSpotlight? = null,
) {
    LaunchedEffect(spotlight) {
        if (spotlight == null) return@LaunchedEffect
        while (true) {
            spotlight.decay()
            kotlinx.coroutines.delay(16)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Layer 1 — the aurora.
        AltarisBackdrop(modifier = Modifier.fillMaxSize(), palette = palette)

        // Layer 2 — the technical grid.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val step = 34.dp.toPx()
            val line = palette.outline.copy(alpha = 0.16f)
            var x = 0f
            while (x < size.width) {
                drawLine(
                    color = line,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1f,
                )
                x += step
            }
            var y = 0f
            while (y < size.height) {
                drawLine(
                    color = line,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f,
                )
                y += step
            }
            // The grid fades out towards the edges so it never competes with
            // the content.
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color.Transparent, palette.base.copy(alpha = 0.55f)),
                    center = Offset(size.width * 0.5f, size.height * 0.34f),
                    radius = size.maxDimension * 0.92f,
                )
            )
        }

        // Layer 3 — the touch spotlight.
        if (spotlight != null) {
            val point = spotlight.position
            val strength = spotlight.strength
            if (point != null && strength > 0.01f) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val radius = size.minDimension * 0.85f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                palette.primary.copy(alpha = 0.16f * strength),
                                palette.secondary.copy(alpha = 0.07f * strength),
                                Color.Transparent,
                            ),
                            center = point,
                            radius = radius,
                        ),
                        radius = radius,
                        center = point,
                    )
                }
            }
        }
    }
}

/** Convenience for drawing a soft glow. */
internal fun DrawScope.softGlow(
    center: Offset,
    radius: Float,
    color: Color,
    alpha: Float,
) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = alpha), Color.Transparent),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}
