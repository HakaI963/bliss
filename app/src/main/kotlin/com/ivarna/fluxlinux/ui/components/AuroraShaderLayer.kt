package com.ivarna.fluxlinux.ui.components

import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.ivarna.fluxlinux.ui.theme.AltarisPalette

/**
 * The AGSL aurora layer.
 *
 * Everything that touches [RuntimeShader] lives in this class so the class
 * loader only resolves the AGSL classes on API 33+, where the runtime exists.
 * The backdrop component keeps itself free of shader references.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
object AuroraShaderLayer {

    /**
     * Draws the animated aurora. The uniforms are refreshed on every frame
     * from the draw phase, so the animation never triggers a recomposition.
     */
    @Composable
    fun Draw(
        shader: RuntimeShader,
        palette: AltarisPalette,
        modifier: Modifier = Modifier,
    ) {
        val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
        val clock = rememberAuroraClock()
        val stops = palette.heroStops
        Box(
            modifier = modifier.fillMaxSize().drawBehind {
                val colorA = stops.getOrElse(0) { palette.primary }
                val colorB = stops.getOrElse(1) { palette.secondary }
                val colorC = stops.getOrElse(2) { palette.tertiary }
                shader.setFloatUniform("iResolution", size.width, size.height)
                shader.setFloatUniform("iTime", clock.value)
                shader.setFloatUniform("iColorA", colorA.red, colorA.green, colorA.blue)
                shader.setFloatUniform("iColorB", colorB.red, colorB.green, colorB.blue)
                shader.setFloatUniform("iColorC", colorC.red, colorC.green, colorC.blue)
                paint.shader = shader
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint)
                }
            }
        )
    }

    /**
     * Seconds since the backdrop started. The value is only read in the draw
     * phase, so the animation never triggers a recomposition.
     */
    @Composable
    private fun rememberAuroraClock(): State<Float> = produceState(0f) {
        while (true) {
            withFrameMillis { millis -> value = millis / 1000f }
        }
    }
}
