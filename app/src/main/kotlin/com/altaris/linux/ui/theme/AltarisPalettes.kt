package com.altaris.linux.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The Altaris identity.
 *
 * Altaris is a terminal and agent tool, so the interface stays precise and
 * mostly monochrome. The current identity is a **light** workspace: white
 * surfaces, near-black text, and one cool accent (cyan → blue) used for
 * actions, live state and the REA analysis surface.
 *
 * There is one identity on purpose. The old dark themes (Graphite, Midnight
 * Slate) and the ThemeMode picker were removed: a single fixed identity means
 * every screen, dialog and snapshot is designed and reviewed against the same
 * contrast, instead of each one drifting between two palettes.
 */
enum class AltarisScheme(
    val label: String,
    val tagline: String,
    val isDark: Boolean,
) {
    DAYLIGHT("Daylight", "White workspace, ink text, cyan accent", false),
}

/**
 * The tokens of the identity. The names follow the element they paint:
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
    /** Destructive / error colour. */
    val error: Color = Color(0xFFC81E2B),
    /**
     * The cool accent: cyan through blue. Used for live state, the REA
     * analysis surface and small emphasis — never as a large fill.
     */
    val accent: Color = Color(0xFF0E7490),
    /** A lighter step of [accent], for gradients and soft badges. */
    val accentBright: Color = Color(0xFF22B8CF),
    /** The deepest step, for text on a light accent fill. */
    val accentDeep: Color = Color(0xFF075985),
) {
    /** A soft fill used for hovered / selected surfaces. */
    val surfaceRaised: Color get() = if (isDark) Color(0xFF1E1E1E) else Color(0xFFF7F9FA)

    /** A very quiet accent wash, for selected rows and live badges. */
    val accentWash: Color get() = accent.copy(alpha = if (isDark) 0.20f else 0.10f)
}

/** The resolved identity. */
object AltarisPalettes {

    /** Palette for [scheme]. */
    fun of(scheme: AltarisScheme): AltarisPalette = when (scheme) {
        AltarisScheme.DAYLIGHT -> Daylight
    }

    /**
     * Daylight — the Altaris workspace.
     *
     * White page, a slightly cooler panel so cards separate without shadows,
     * near-black ink, and a cyan→blue accent that reads as "live" next to the
     * terminal's dark surface.
     */
    val Daylight = AltarisPalette(
        isDark = false,
        base = Color(0xFFFCFCFC),
        surface = Color(0xFFF4F6F7),
        panel = Color(0xFFFFFFFF),
        border = Color(0xFFE3E7EA),
        onSurface = Color(0xFF121517),
        onSurfaceVariant = Color(0xFF6B7378),
        action = Color(0xFF121517),
        onAction = Color(0xFFFFFFFF),
    )

    /** Backwards-compatible alias: old code asked for the Graphite identity. */
    @Deprecated("There is one identity now; use Daylight.", ReplaceWith("Daylight"))
    val Graphite: AltarisPalette get() = Daylight

    /** Backwards-compatible alias. */
    @Deprecated("There is one identity now; use Daylight.", ReplaceWith("Daylight"))
    val MidnightSlate: AltarisPalette get() = Daylight

    /** Backwards-compatible alias. */
    @Deprecated("There is one identity now; use Daylight.", ReplaceWith("Daylight"))
    val WarmNeutral: AltarisPalette get() = Daylight
}
