package com.altaris.linux.ui.components

import android.graphics.RuntimeShader
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import com.altaris.linux.ui.theme.AltarisPalette
import com.altaris.linux.ui.theme.LocalAltarisPalette
import com.altaris.linux.ui.theme.isPaparazziRun

/**
 * Altaris backdrop: a slow, breathing aurora painted with AGSL.
 *
 * Three gaussian blobs drift across the screen and are summed additively; a
 * soft tonemap keeps the overlap from clipping to white, a vignette anchors
 * the glass surfaces and a whisper of grain hides the banding on the wide
 * gradients. The motion loops every 16 seconds, so it never feels busy.
 *
 * On API < 33 (and inside Paparazzi, where the shader cannot be built) the
 * backdrop degrades to the same palette rendered as a static gradient.
 */
private const val AURORA_AGSL = """
uniform float2 iResolution;
uniform float iTime;
uniform half3 iColorA;
uniform half3 iColorB;
uniform half3 iColorC;

float blob(float2 p, float2 c, float r) {
    float d = length(p - c) / r;
    return exp(-d * d);
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / iResolution;
    float2 p = (fragCoord - 0.5 * iResolution) / min(iResolution.x, iResolution.y);
    // 2*PI / 16s: the whole backdrop loops every sixteen seconds.
    float ph = iTime * 0.3926991;

    float a = blob(p, float2(cos(ph) * 0.60, sin(ph * 2.0) * 0.32 - 0.10), 0.90);
    float b = blob(p, float2(sin(ph + 1.70) * 0.52, cos(ph + 0.60) * 0.40 + 0.16), 1.05);
    float c = blob(p, float2(cos(ph * 3.0 + 3.10) * 0.70, sin(ph * 2.0 + 2.20) * 0.28 + 0.04), 0.76);

    half3 col = iColorA * half3(a);
    col += iColorB * half3(b);
    col += iColorC * half3(c);

    // Soft tonemap: overlapping blooms stay colourful instead of blowing out.
    col = col / (half3(1.0) + half3(0.35) * col);

    float vig = 1.0 - smoothstep(0.30, 1.25, length(uv - float2(0.5, 0.5)) * 1.70);
    col *= half3(mix(0.66, 1.0, vig));

    float grain = fract(sin(dot(fragCoord, float2(12.9898, 78.233)) + ph * 7.0) * 43758.5453);
    col += half3((grain - 0.5) * 0.012);

    return half4(clamp(col, half3(0.0), half3(1.0)), 1.0);
}
"""

/**
 * True when the animated shader can run: an AGSL runtime (API 33+), a real
 * device (not a screenshot run) and no inspection mode.
 */
private fun auroraSupported(inspection: Boolean): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        !isPaparazziRun() &&
        !inspection

/**
 * True when the user asked the system for less motion (animator scale 0).
 * The aurora then stays still so Altaris never fights the system setting.
 */
@Composable
private fun reducedMotion(): Boolean = runCatching {
    val context = LocalContext.current
    Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) == 0f
}.getOrDefault(false)

/**
 * The Altaris backdrop for the active identity. Uses the animated AGSL
 * aurora where possible and falls back to the static gradient otherwise.
 */
@Composable
fun AltarisBackdrop(
    modifier: Modifier = Modifier,
    palette: AltarisPalette = LocalAltarisPalette.current,
) {
    val inspection = LocalInspectionMode.current
    val animated = auroraSupported(inspection) && !reducedMotion()
    Box(modifier = modifier.background(palette.base)) {
        if (animated) {
            AnimatedAurora(palette = palette, modifier = Modifier.fillMaxSize())
        } else {
            AltarisStillBackdrop(palette = palette, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * The static "aurora still" — the same palette painted as a frozen gradient.
 * Used on API < 33, when the shader cannot be built, and for screenshots.
 */
@Composable
fun AltarisStillBackdrop(
    modifier: Modifier = Modifier,
    palette: AltarisPalette = LocalAltarisPalette.current,
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val stops = palette.heroStops
        val width = size.width
        val height = size.height
        // The light identity sits on paper, so its wash stays softer.
        val washAlpha = if (palette.isDark) 0.42f else 0.28f
        val bloomAlpha = if (palette.isDark) 0.34f else 0.24f

        // Ink base.
        drawRect(palette.base)

        // Wide aurora wash from the identity's hero gradient.
        drawRect(
            brush = Brush.linearGradient(
                colors = stops.map { it.copy(alpha = washAlpha) } + palette.base,
                start = Offset.Zero,
                end = Offset(width, height),
            )
        )

        // Three blooms, placed like the moving blobs of the shader.
        stops.take(3).forEachIndexed { index, color ->
            val center = Offset(
                x = width * (0.18f + 0.32f * index),
                y = height * (0.12f + 0.24f * ((index + 1) % 3)),
            )
            val radius = width * 0.78f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = bloomAlpha), Color.Transparent),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }

        // Vignette anchors the glass surfaces.
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, palette.base.copy(alpha = 0.55f)),
                center = Offset(width * 0.5f, height * 0.42f),
                radius = size.maxDimension * 0.85f,
            )
        )
    }
}

/**
 * The animated aurora. Everything that touches [RuntimeShader] lives in this
 * API 33+ function, so older devices never load the AGSL classes.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun AnimatedAurora(
    palette: AltarisPalette,
    modifier: Modifier = Modifier,
) {
    val shader = remember { runCatching { RuntimeShader(AURORA_AGSL) }.getOrNull() }
    if (shader == null) {
        // The shader did not compile on this device: keep the identity still.
        AltarisStillBackdrop(palette = palette, modifier = modifier)
    } else {
        // The shader class lives in its own file so API < 33 never loads it.
        AuroraShaderLayer.Draw(shader = shader, palette = palette, modifier = modifier)
    }
}
