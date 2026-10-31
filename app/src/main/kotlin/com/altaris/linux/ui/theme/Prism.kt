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

    // ── Geometry (the reference workspace: small radii, 8 px rhythm) ───────
    /** Panel radius. */
    val PanelRadius: Dp = 8.dp

    /** Button / chip / nav radius. */
    val ButtonRadius: Dp = 6.dp

    /** Chip radius. */
    val ChipRadius: Dp = 6.dp

    /** Small tile radius. */
    val TileRadius: Dp = 8.dp

    /** Sheet radius. */
    val SheetRadius: Dp = 10.dp

    /** Panel inner padding. */
    val PanelPad: Dp = 14.dp

    /** Row horizontal padding. */
    val RowPadH: Dp = 12.dp

    /** Row vertical padding. */
    val RowPadV: Dp = 11.dp

    /** Screen gutter. */
    val Gutter: Dp = 16.dp

    /** Vertical rhythm between blocks. */
    val Stack: Dp = 14.dp

    /** Tight rhythm inside a block. */
    val Tight: Dp = 10.dp

    /** Gap between controls that sit side by side. */
    val ControlGap: Dp = 10.dp

    /** Minimum touch target. */
    val TouchTarget: Dp = 48.dp

    /** Button height — one size for every action, so rows never look ragged. */
    val ButtonHeight: Dp = 44.dp

    /** Horizontal padding inside a button. */
    val ButtonPadH: Dp = 16.dp

    /** Chip height — matches the text baseline of a row. */
    val ChipHeight: Dp = 32.dp

    /** Top bar height (the reference's workspace bar). */
    val TopBarHeight: Dp = 50.dp

    /** Bottom navigation height. */
    val NavBarHeight: Dp = 58.dp

    /** The mark tile in the top bar. */
    val MarkSize: Dp = 28.dp

    /** The home artwork keeps a cinematic 16:9-ish crop. */
    const val HeroAspect: Float = 1.85f
}

/**
 * The sections of the app. Each one owns an accent so the shell, the dock
 * and the screen content always read as one place.
 */
enum class AltarisSection {
    HOME,
    REA,
    DISTROS,
    APPS,
    TOOLS,
    TERMINAL,
    CLONE,
}

/**
 * Accent colour of a section.
 *
 * Altaris keeps one cool accent (cyan → blue) for live state and for the
 * analysis surfaces; the chrome itself stays ink-on-white so the terminal and
 * the clone display keep the visual weight. The REA analysis surface is the
 * one place where the accent is allowed to carry a whole screen.
 */
@Composable
fun sectionAccent(section: AltarisSection): Color = when (section) {
    AltarisSection.REA, AltarisSection.TOOLS -> LocalAltarisPalette.current.accent
    else -> LocalAltarisPalette.current.action
}

/** A cyan → blue ramp for a section accent. */
@Composable
fun sectionAccentBrush(section: AltarisSection): List<Color> {
    val palette = LocalAltarisPalette.current
    val accent = sectionAccent(section)
    return listOf(
        accent,
        if (section == AltarisSection.REA || section == AltarisSection.TOOLS) {
            palette.accentBright
        } else {
            accent.copy(alpha = 0.72f)
        },
        accent.copy(alpha = 0.18f),
    )
}

/** Neutral text colour that always reads on the current surface. */
@Composable
fun prismTextSecondary(): Color = MaterialTheme.colorScheme.onSurfaceVariant
