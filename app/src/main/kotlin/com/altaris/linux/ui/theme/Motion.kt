package com.altaris.linux.ui.theme

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
 * Timing tokens — one source of truth for durations and stagger pacing.
 * Deliberately short and tween-based so the app stays snappy on low-end devices.
 */
object AltarisTimings {
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
        delay(AltarisMotion.StaggerWindowMs)
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
internal fun isScreenshotRun(): Boolean =
    LocalInspectionMode.current || isPaparazziRun()

/**
 * True inside a Paparazzi run (JVM screenshots). Paparazzi marks the JVM with
 * "paparazzi.test.record"/"paparazzi.test.verify"; the class lookup is a
 * belt-and-braces fallback for runners that do not set them.
 */
internal fun isPaparazziRun(): Boolean =
    System.getProperty("paparazzi.test.record") != null ||
        System.getProperty("paparazzi.test.verify") != null ||
        paparazziOnClasspath

/** Cached once: the Paparazzi runtime only exists in screenshot test runs. */
private val paparazziOnClasspath: Boolean by lazy {
    runCatching { Class.forName("app.cash.paparazzi.Paparazzi") }.isSuccess
}

/**
 * Altaris spring tokens. Springs keep the UI feeling physical without the
 * long tail of a tween: layout shifts settle quickly, effects snap.
 */
object AltarisMotion {
    /** Layout moves (cards, panels, lists). */
    val spatial: SpringSpec<Float> = spring(dampingRatio = 0.8f, stiffness = 380f)
    /** Effects (press, glow, ripple). */
    val effects: SpringSpec<Float> = spring(dampingRatio = 1.0f, stiffness = 1600f)
    /** Fast spatial (chips, tabs, pills). */
    val fastSpatial: SpringSpec<Float> = spring(dampingRatio = 0.6f, stiffness = 800f)
    /** Slow spatial (hero panels, sheets, large reveals). */
    val slowSpatial: SpringSpec<Float> = spring(dampingRatio = 0.8f, stiffness = 200f)
}

/**
 * Press feedback for any tappable surface: the node shrinks slightly while
 * the pointer is held and springs back on release. The scale is applied in
 * the draw phase, so children are never recomposed.
 */
@Composable
fun Modifier.pressScale(
    minScale: Float = 0.96f,
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) minScale else 1f,
        animationSpec = AltarisMotion.effects,
        label = "pressScale",
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            enabled = enabled,
            interactionSource = interaction,
            indication = null,
            onClick = onClick,
        )
}

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
            delay(index.coerceIn(0, AltarisMotion.StaggerMaxIndex) * AltarisMotion.StaggerStepMs)
            shown = true
        }
    }
    return animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = AltarisMotion.spatial,
        label = "stagger"
    ).value
}

/**
 * Entrance animation: fade + rise, delayed by [index] steps of
 * [AltarisMotion.StaggerStepMs]. One-shot per composition; see [rememberStaggerEntrance].
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
