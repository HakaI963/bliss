package com.ivarna.fluxlinux.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Shared motion tokens — one source of truth for durations and stagger pacing.
 * Deliberately short and tween-based so the app stays snappy on low-end devices.
 */
object FolioMotion {
    /** Full-screen route push/pop. */
    const val ScreenMs = 240
    /** Bottom-tab crossfade + rise. */
    const val TabMs = 200
    /** Small UI swaps (status labels, chips). */
    const val FastMs = 160
    /** Per-item entrance delay step (staggered lists/sections). */
    const val StaggerStepMs = 30L
    /** Only the first N indices stagger; later items share the last delay. */
    const val StaggerMaxIndex = 10
    /** How long after first composition lazy items keep animating in. */
    const val StaggerWindowMs = 650L
    /** Press feedback scale for rows/pills/cards. */
    const val PressScale = 0.98f
}

/**
 * True only while a screen's entrance window is open. Pass it to [staggerIn]
 * so items composed on the first frame cascade in, while lazy items scrolled
 * into view after the window appear straight at rest (no endless list motion).
 */
@Composable
fun rememberStaggerEntrance(): Boolean {
    if (isScreenshotRun()) return false
    var entering by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(FolioMotion.StaggerWindowMs)
        entering = false
    }
    return entering
}

/**
 * True while rendering under Paparazzi (CI screenshots) or in a Compose
 * preview: entrance animations never advance there, so content must start at
 * rest instead of staying at alpha 0.
 */
@Composable
private fun isScreenshotRun(): Boolean =
    LocalInspectionMode.current ||
        System.getProperty("paparazzi.test.record") != null ||
        System.getProperty("paparazzi.test.verify") != null

/**
 * One-shot stagger progress for [index]: 0 = not revealed yet, 1 = at rest.
 * Starts at 1 when [entrance] is already false (list recycling after the
 * entrance window), so recycled items never replay the animation.
 */
@Composable
fun rememberStaggerProgress(index: Int, entrance: Boolean): Float {
    if (isScreenshotRun()) return 1f
    var shown by remember { mutableStateOf(!entrance) }
    LaunchedEffect(entrance, index) {
        if (entrance) {
            delay(index.coerceIn(0, FolioMotion.StaggerMaxIndex) * FolioMotion.StaggerStepMs)
            shown = true
        }
    }
    return animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(
            durationMillis = FolioMotion.ScreenMs,
            easing = FastOutSlowInEasing
        ),
        label = "stagger"
    ).value
}

/**
 * Entrance animation: fade + rise, delayed by [index] steps of
 * [FolioMotion.StaggerStepMs]. One-shot per composition; see [rememberStaggerEntrance].
 */
@Composable
fun Modifier.staggerIn(
    index: Int,
    entrance: Boolean = true,
    rise: Dp = 14.dp
): Modifier {
    val progress = rememberStaggerProgress(index, entrance)
    return graphicsLayer {
        alpha = progress
        translationY = (1f - progress) * rise.toPx()
    }
}
