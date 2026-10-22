package com.altaris.linux.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altaris.linux.ui.theme.Prism
import kotlinx.coroutines.delay

/**
 * One entry of the command palette.
 */
data class PaletteAction(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val icon: ImageVector,
    val accent: Color,
    val onSelect: () -> Unit,
)

/**
 * The command palette — the new navigation.
 *
 * A single searchable surface that jumps to a screen, an action or a
 * running session. It replaces "look for the tab" with "type what you want".
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CommandPaletteOverlay(
    actions: List<PaletteAction>,
    onDismiss: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var query by remember { mutableStateOf("") }
    val scale = remember { Animatable(0.94f) }
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        alpha.animateTo(1f, tween(180))
        scale.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = 380f))
    }

    val filtered = remember(actions, query) {
        val needle = query.trim()
        if (needle.isEmpty()) {
            actions
        } else {
            actions.filter {
                it.title.contains(needle, ignoreCase = true) ||
                    it.subtitle?.contains(needle, ignoreCase = true) == true ||
                    it.id.contains(needle, ignoreCase = true)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f * alpha.value))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onDismiss() },
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 44.dp)
                .scale(scale.value)
                .clip(RoundedCornerShape(Prism.SheetRadius))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f),
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.20f),
                            Color.White.copy(alpha = 0.04f),
                        )
                    ),
                    shape = RoundedCornerShape(Prism.SheetRadius),
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { /* consume taps so the scrim does not close the palette */ }
                .padding(vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            text = "Jump to a screen, a tool or a session…",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                keyboard?.hide()
                                focusManager.clearFocus()
                                filtered.firstOrNull()?.let {
                                    it.onSelect()
                                    onDismiss()
                                }
                            }
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            keyboard?.hide()
                            focusManager.clearFocus()
                            onDismiss()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 26.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No match for “$query”",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                ) {
                    itemsIndexed(filtered) { index, action ->
                        PaletteRow(
                            action = action,
                            index = index,
                            onClick = {
                                keyboard?.hide()
                                focusManager.clearFocus()
                                action.onSelect()
                                onDismiss()
                            },
                        )
                    }
                }
            }
        }
    }
}

/** One palette entry: a tinted glyph, the label and a short hint. */
@Composable
private fun PaletteRow(
    action: PaletteAction,
    index: Int,
    onClick: () -> Unit,
) {
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay((index.coerceAtMost(8) * 26).toLong())
        reveal.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 620f))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayerAlpha(reveal.value)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.linearGradient(
                        listOf(action.accent, action.accent.copy(alpha = 0.42f))
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = action.icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(17.dp),
            )
        }
        Spacer(Modifier.width(13.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = action.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (!action.subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(1.dp))
                Text(
                    text = action.subtitle,
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(50))
                .background(action.accent.copy(alpha = 0.75f))
        )
    }
}

/** Fade + rise used by the palette rows. */
private fun Modifier.graphicsLayerAlpha(value: Float): Modifier =
    this.then(
        Modifier.graphicsLayer {
            alpha = value
            translationY = (1f - value) * 14f
        }
    )
