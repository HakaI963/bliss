package com.altaris.linux.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altaris.linux.ui.theme.Prism

/**
 * The home banner.
 *
 * A wide panel at the top of Home that carries the anime artwork — the one
 * piece of colour in the app — next to the wordmark and a short line of
 * context. The artwork can be changed in Settings.
 */
@Composable
fun HomeHero(
    artRes: Int,
    modifier: Modifier = Modifier,
    title: String = "Altaris",
    subtitle: String = "Your Linux environments",
    trailing: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Prism.CardRadius)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(scheme.surface)
            .border(1.dp, scheme.outline, shape)
            .padding(start = Prism.Gutter, end = 10.dp, top = Prism.Gutter, bottom = Prism.Gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title.uppercase(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.6.sp,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = subtitle,
                fontSize = 21.sp,
                lineHeight = 26.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
                color = scheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            if (trailing != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    trailing()
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        // The artwork sits in its own well so it never touches the text.
        Box(
            modifier = Modifier
                .size(104.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            scheme.surfaceVariant,
                            scheme.surface,
                        )
                    )
                )
                .border(1.dp, scheme.outline, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(artRes),
                contentDescription = "Altaris artwork",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(18.dp)),
            )
        }
    }
}

/**
 * A small artwork well used by the Settings picker.
 */
@Composable
fun ArtworkThumb(
    artRes: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .size(64.dp)
            .clip(shape)
            .background(scheme.surfaceVariant)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) scheme.onSurface else scheme.outline,
                shape = shape,
            )
            .clickable(
                interactionSource = androidx.compose.runtime.remember {
                    androidx.compose.foundation.interaction.MutableInteractionSource()
                },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(artRes),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(48.dp),
        )
    }
}

/** The anime artwork the home banner can show. */
val ArtworkThumbs: List<Int> = listOf(
    com.altaris.linux.R.drawable.splash_art_catgirl,
    com.altaris.linux.R.drawable.splash_art_mecha,
    com.altaris.linux.R.drawable.splash_art_shonen,
    com.altaris.linux.R.drawable.splash_art_kitsune,
    com.altaris.linux.R.drawable.splash_art_magical,
    com.altaris.linux.R.drawable.splash_art_chibi,
)
