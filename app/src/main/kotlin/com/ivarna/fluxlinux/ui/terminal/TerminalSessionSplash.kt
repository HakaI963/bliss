package com.ivarna.fluxlinux.ui.terminal

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivarna.fluxlinux.R
import com.ivarna.fluxlinux.ui.theme.TextMuted
import com.ivarna.fluxlinux.ui.theme.TextPrimary
import com.ivarna.fluxlinux.ui.theme.TextSecondary
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Resource-driven splash art rotation (original colorful anime-style sticker
 * art, hand-authored vector — no third-party/copyrighted characters). Add a
 * drawable here to grow the rotation; the splash picks one at random per
 * session open. Keep in sync with the launch-sticker set in the guest scripts.
 */
val AnimeSplashArt: List<Int> = listOf(
    R.drawable.splash_art_catgirl,
    R.drawable.splash_art_mecha,
    R.drawable.splash_art_shonen,
    R.drawable.splash_art_kitsune,
    R.drawable.splash_art_magical,
    R.drawable.splash_art_chibi
)

/** Accent glow color behind each character (matches its palette). */
private fun splashGlow(artRes: Int): Color = when (artRes) {
    R.drawable.splash_art_catgirl -> Color(0xFFFF7BD5)
    R.drawable.splash_art_mecha -> Color(0xFF40E0FF)
    R.drawable.splash_art_shonen -> Color(0xFF3F8CFF)
    R.drawable.splash_art_kitsune -> Color(0xFFFFA24A)
    R.drawable.splash_art_magical -> Color(0xFFB77BFF)
    else -> Color(0xFF34C96B)
}

/**
 * Launch-sticker overlay shown while the terminal first shows an active session.
 *
 * Black backdrop + large colorful art (~180dp) over a soft accent glow, with a
 * gentle float and twinkling sparkles (light infinite transitions). Fades out
 * on its own after ~1.2s; a tap dismisses instantly. The caller REMOVES this
 * overlay via [onFinished], so it never intercepts input afterwards.
 */
@Composable
fun TerminalSessionSplash(
    title: String,
    artRes: Int,
    onFinished: () -> Unit
) {
    val alpha = remember { Animatable(1f) }
    val scale = remember { Animatable(0.94f) }
    var finished by remember { mutableStateOf(false) }

    val transition = rememberInfiniteTransition(label = "splash")
    val float by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "float"
    )
    val twinkle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "twinkle"
    )

    val glow = splashGlow(artRes)

    LaunchedEffect(Unit) {
        launch { scale.animateTo(1f, tween(durationMillis = 340, easing = FastOutSlowInEasing)) }
        delay(860)
        alpha.animateTo(0f, tween(durationMillis = 420, easing = LinearEasing))
        if (!finished) {
            finished = true
            onFinished()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(alpha.value)
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (!finished) {
                    finished = true
                    onFinished()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
        ) {
            Box(contentAlignment = Alignment.Center) {
                // Soft colored radial glow behind the character.
                Box(
                    modifier = Modifier
                        .size(232.dp)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(glow.copy(alpha = 0.30f), Color.Transparent)
                            ),
                            shape = CircleShape
                        )
                )
                // Twinkling sparkles around the art.
                Canvas(Modifier.size(224.dp)) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val radius = size.minDimension * 0.45f
                    val sparks = 7
                    for (i in 0 until sparks) {
                        val angle = i * 2.39996 + 0.6
                        val ring = if (i % 2 == 0) 1f else 0.82f
                        val px = cx + (cos(angle) * radius * ring).toFloat()
                        val py = cy + (sin(angle) * radius * ring).toFloat()
                        val phase = twinkle.toDouble() * 2.0 * PI + i * 0.9
                        val pulse = abs(sin(phase)).toFloat()
                        val r = (2.2f + 2.4f * (i % 3)) * density
                        drawCircle(
                            color = glow.copy(alpha = 0.20f + 0.65f * pulse),
                            radius = r,
                            center = Offset(px, py)
                        )
                    }
                }
                // The character (~180dp; vector = crisp at any size).
                Image(
                    painter = painterResource(id = artRes),
                    contentDescription = null,
                    modifier = Modifier
                        .size(182.dp)
                        .graphicsLayer { translationY = (float - 0.5f) * 12f }
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = "Bliss",
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                letterSpacing = 6.sp
            )
            Spacer(Modifier.height(7.dp))
            Text(
                text = title,
                fontSize = 12.sp,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "booting session…",
                fontSize = 10.sp,
                color = TextMuted,
                letterSpacing = 1.2.sp
            )
        }
    }
}
