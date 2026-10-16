package com.ivarna.fluxlinux.ui.terminal

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivarna.fluxlinux.R
import com.ivarna.fluxlinux.ui.theme.FrostHairline
import com.ivarna.fluxlinux.ui.theme.TextMuted
import com.ivarna.fluxlinux.ui.theme.TextPrimary
import com.ivarna.fluxlinux.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Resource-driven splash art rotation (original monochrome anime-ish sticker art,
 * hand-authored vectors — no third-party/copyrighted characters). Add a drawable
 * here to grow the rotation; the splash picks one at random per session open.
 */
val AnimeSplashArt: List<Int> = listOf(
    R.drawable.splash_art_catgirl,
    R.drawable.splash_art_mecha,
    R.drawable.splash_art_shonen,
    R.drawable.splash_art_kitsune,
    R.drawable.splash_art_chibi
)

/**
 * Launch-sticker overlay shown while the terminal first shows an active session.
 *
 * Pure-black backdrop + white/gray art + hairline frame (Frost style). Fades out
 * on its own after ~1.2s; a tap dismisses instantly. It is REMOVED by the caller
 * via [onFinished] once faded, so it never intercepts input afterwards.
 */
@Composable
fun TerminalSessionSplash(
    title: String,
    artRes: Int,
    onFinished: () -> Unit
) {
    val alpha = remember { Animatable(1f) }
    val scale = remember { Animatable(0.94f) }

    LaunchedEffect(Unit) {
        launch { scale.animateTo(1f, tween(durationMillis = 320, easing = FastOutSlowInEasing)) }
        delay(760)
        alpha.animateTo(0f, tween(durationMillis = 420, easing = LinearEasing))
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(alpha.value)
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onFinished() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
        ) {
            Box(
                modifier = Modifier
                    .size(132.dp)
                    .border(0.75.dp, FrostHairline, RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = artRes),
                    contentDescription = null,
                    modifier = Modifier.size(96.dp)
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = "Folio Linux",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                letterSpacing = 6.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "booting session…",
                fontSize = 11.sp,
                color = TextSecondary,
                letterSpacing = 1.5.sp
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = title,
                fontSize = 12.sp,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
