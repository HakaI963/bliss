package com.altaris.linux.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The Altaris identities.
 *
 * Altaris is a terminal and agent tool: the interface stays monochrome and
 * precise, and the content (terminal output, clone display) provides the
 * visual interest. Every identity is therefore neutral — near-black greys
 * for the dark ones, warm paper for the light one — with no decorative
 * colour anywhere in the chrome.
 */
enum class AltarisScheme(
    val label: String,
    val tagline: String,
    val isDark: Boolean,
) {
    GRAPHITE("Graphite", "Restrained monochrome, dark", true),
    MIDNIGHT_SLATE("Midnight Slate", "Technical, understated, soft black", true),
    WARM_NEUTRAL("Warm Neutral", "Calm, refined, editorial", false),
}

/**
 * The tokens of one identity. The names follow the element they paint:
 * background, sidebar, panels, borders, text and the primary action.
 */
data class AltarisPalette(
    val isDark: Boolean,
    /** App background. */
    val base: Color,
    /** Sidebar / dock surface. */
    val surface: Color,
    /** Raised panels and cards. */
    val panel: Color,
    /** Hairlines and dividers. */
    val border: Color,
    /** Primary text. */
    val onSurface: Color,
    /** Secondary text. */
    val onSurfaceVariant: Color,
    /** Primary action (button fill). */
    val action: Color,
    /** Text on the primary action. */
    val onAction: Color,
    /** Destructive / error colour — the only chromatic token in the system. */
    val error: Color = Color(0xFFE5484D),
) {
    /** A soft fill used for hovered / selected surfaces. */
    val surfaceRaised: Color get() = if (isDark) Color(0xFF1E1E1E) else Color(0xFFFAFAF7)
}

/** The resolved identities. */
object AltarisPalettes {

    /** Palette for [scheme]. */
    fun of(scheme: AltarisScheme): AltarisPalette = when (scheme) {
        AltarisScheme.GRAPHITE -> Graphite
        AltarisScheme.MIDNIGHT_SLATE -> MidnightSlate
        AltarisScheme.WARM_NEUTRAL -> WarmNeutral
    }

    /**
     * Graphite — dark premium. Restrained, monochrome, precise: white buttons,
     * subtle borders, no decorative colour.
     */
    val Graphite = AltarisPalette(
        isDark = true,
        base = Color(0xFF0D0D0D),
        surface = Color(0xFF111111),
        panel = Color(0xFF171717),
        border = Color(0xFF292929),
        onSurface = Color(0xFFF5F5F5),
        onSurfaceVariant = Color(0xFF929292),
        action = Color(0xFFF5F5F5),
        onAction = Color(0xFF111111),
    )

    /**
     * Warm Neutral — light premium. Calm, refined, editorial: a polished
     * professional workspace rather than a traditional terminal.
     */
    val WarmNeutral = AltarisPalette(
        isDark = false,
        base = Color(0xFFF7F7F5),
        surface = Color(0xFFF0F0ED),
        panel = Color(0xFFFFFFFF),
        border = Color(0xFFE5E5E0),
        onSurface = Color(0xFF171717),
        onSurfaceVariant = Color(0xFF737373),
        action = Color(0xFF202020),
        onAction = Color(0xFFFFFFFF),
    )

    /**
     * Midnight Slate — technical premium. Slightly softer than pure black,
     * with enough separation to distinguish panels without looking busy.
     */
    val MidnightSlate = AltarisPalette(
        isDark = true,
        base = Color(0xFF101114),
        surface = Color(0xFF141519),
        panel = Color(0xFF191B20),
        border = Color(0xFF2A2C32),
        onSurface = Color(0xFFEDEEF0),
        onSurfaceVariant = Color(0xFF9699A2),
        action = Color(0xFFEDEEF0),
        onAction = Color(0xFF101114),
    )
}
