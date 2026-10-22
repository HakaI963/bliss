package com.altaris.linux.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Prism — the Altaris design language.
 *
 * Every surface in the app is built from these tokens: one geometry scale,
 * one spacing rhythm and one accent per section, so the shell, the screens
 * and the components always agree on shape and colour.
 */
object Prism {

    // ── Geometry ──────────────────────────────────────────────────────────
    /** Floating dock height. */
    val DockHeight: Dp = 70.dp

    /** Dock capsule radius (half of the height gives a pill). */
    val DockRadius: Dp = 34.dp

    /** Raised command button inside the dock. */
    val CommandSize: Dp = 58.dp

    /** Dock icon tile. */
    val TileSize: Dp = 42.dp

    /** Large card radius. */
    val CardRadius: Dp = 26.dp

    /** Small card / tile radius. */
    val TileRadius: Dp = 20.dp

    /** Chip radius. */
    val ChipRadius: Dp = 15.dp

    /** Sheet radius. */
    val SheetRadius: Dp = 30.dp

    /** Floating header radius. */
    val HeaderRadius: Dp = 24.dp

    // ── Spacing (4/8dp rhythm) ────────────────────────────────────────────
    /** Screen gutter. */
    val Gutter: Dp = 20.dp

    /** Vertical rhythm between blocks. */
    val Stack: Dp = 14.dp

    /** Tight rhythm inside a block. */
    val Tight: Dp = 8.dp

    /** Minimum touch target. */
    val TouchTarget: Dp = 48.dp
}

/**
 * The sections of the app. Each one owns an accent so the shell, the dock
 * and the screen content always read as one place.
 */
enum class AltarisSection {
    HOME,
    DISTROS,
    APPS,
    TOOLS,
    TERMINAL,
    CLONE,
}

/**
 * Accent colour of a section.
 *
 * Altaris is monochrome: every section shares the identity's action colour,
 * so the chrome never competes with the terminal or the clone display.
 */
@Composable
fun sectionAccent(section: AltarisSection): Color = LocalAltarisPalette.current.action

/** A neutral ramp for a section accent. */
@Composable
fun sectionAccentBrush(section: AltarisSection): List<Color> {
    val accent = sectionAccent(section)
    return listOf(
        accent,
        accent.copy(alpha = 0.72f),
        accent.copy(alpha = 0.18f),
    )
}

/** Neutral text colour that always reads on the current surface. */
@Composable
fun prismTextSecondary(): Color = MaterialTheme.colorScheme.onSurfaceVariant
