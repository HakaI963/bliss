package com.altaris.linux.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altaris.linux.ui.theme.Prism

/**
 * The home artwork.
 *
 * A cinematic card that carries the scene as its wallpaper, with the word
 * over a short scrim so the text stays readable. The picture drifts very
 * slowly (a Ken Burns pan) — one transform, drawn on the GPU.
 */
@Composable
fun HomeHero(
    artRes: Int,
    modifier: Modifier = Modifier,
    title: String = "Altaris",
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Prism.PanelRadius)
    val drift = rememberInfiniteTransition(label = "heroDrift")
    val scale by drift.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 16000),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "heroScale",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(Prism.HeroAspect)
            .clip(shape)
            .background(scheme.surfaceVariant)
            .border(1.dp, scheme.outline, shape),
    ) {
        Image(
            painter = painterResource(artRes),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
        )
        // A scrim only where the text sits, so the picture stays clean.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.45f to Color.Black.copy(alpha = 0.06f),
                        1f to Color.Black.copy(alpha = 0.62f),
                    )
                )
        )
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.4.sp,
                    color = Color.White,
                    maxLines = 1,
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        fontSize = 10.5.sp,
                        letterSpacing = 0.4.sp,
                        color = Color.White.copy(alpha = 0.80f),
                        maxLines = 1,
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(10.dp))
                trailing()
            }
        }
    }
}

/** A small artwork well used by the Settings picker. */
@Composable
fun ArtworkThumb(
    artRes: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Prism.ButtonRadius)
    Box(
        modifier = modifier
            .size(58.dp)
            .clip(shape)
            .background(scheme.surfaceVariant)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) scheme.onSurface else scheme.outline,
                shape = shape,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(artRes),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(52.dp),
        )
    }
}

/** The scenes the home artwork can show. */
val ArtworkThumbs: List<Int> = listOf(
    com.altaris.linux.R.drawable.altaris_scene_1,
    com.altaris.linux.R.drawable.altaris_scene_2,
    com.altaris.linux.R.drawable.altaris_scene_3,
    com.altaris.linux.R.drawable.altaris_scene_4,
    com.altaris.linux.R.drawable.altaris_scene_5,
)
