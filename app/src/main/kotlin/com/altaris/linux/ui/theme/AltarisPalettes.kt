package com.altaris.linux.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The five Altaris identities. Each scheme owns its surfaces, its glass
 * recipe and its gradient stops; [label] and [tagline] are what the Settings
 * picker shows. The enum names are persisted, so they are never renamed.
 */
enum class AltarisScheme(val label: String, val tagline: String) {
    AURORA_NEON("Aurora Neon", "Electric cyan · violet · magenta"),
    LIQUID_GLASS("Liquid Glass", "Vivid gradient behind frosted glass"),
    SOLAR_FLARE("Solar Flare", "Ember orange · gold · pink"),
    MINT_PRISM("Mint Prism", "Saturated teal, refreshed"),
    IRIDESCENT("Iridescent", "Holographic pastels with sheen"),
}

/**
 * Everything the Altaris skin needs for one identity in one mode. The
 * Material3 roles are derived from this (see Theme.kt), the glass surfaces
 * and the aurora backdrop read it directly.
 */
data class AltarisPalette(
    val isDark: Boolean,
    val base: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val surfaceVariant: Color,
    val glassFillTop: Color,
    val glassFillBottom: Color,
    val glassStroke: Color,
    val glassHighlight: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val outlineVariant: Color,
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    /** Wide backdrop gradient (four stops). */
    val heroStops: List<Color>,
    /** Tight accent gradient used by primary actions (three stops). */
    val accentStops: List<Color>,
)

/** Frosted-glass recipe; the light variant sits on a tinted paper surface. */
private data class GlassRecipe(
    val fillTop: Color,
    val fillBottom: Color,
    val stroke: Color,
    val highlight: Color,
)

private val GlassDark = GlassRecipe(
    fillTop = Color.White.copy(alpha = 0.12f),
    fillBottom = Color.White.copy(alpha = 0.05f),
    stroke = Color.White.copy(alpha = 0.22f),
    highlight = Color.White.copy(alpha = 0.26f),
)

private val GlassLight = GlassRecipe(
    fillTop = Color.White.copy(alpha = 0.70f),
    fillBottom = Color.White.copy(alpha = 0.92f),
    stroke = Color(0xFFFFFFFF),
    highlight = Color.White.copy(alpha = 0.85f),
)

/**
 * Resolved palettes for every identity in both modes.
 */
object AltarisPalettes {

    /** Palette for [scheme] in the requested mode. */
    fun of(scheme: AltarisScheme, dark: Boolean): AltarisPalette = when (scheme) {
        AltarisScheme.AURORA_NEON -> if (dark) auroraNeonDark() else auroraNeonLight()
        AltarisScheme.LIQUID_GLASS -> if (dark) liquidGlassDark() else liquidGlassLight()
        AltarisScheme.SOLAR_FLARE -> if (dark) solarFlareDark() else solarFlareLight()
        AltarisScheme.MINT_PRISM -> if (dark) mintPrismDark() else mintPrismLight()
        AltarisScheme.IRIDESCENT -> if (dark) iridescentDark() else iridescentLight()
    }

    // ── Aurora Neon ────────────────────────────────────────────────────────
    private fun auroraNeonDark() = AltarisPalette(
        isDark = true,
        base = Color(0xFF070A1C),
        surface = Color(0xFF0E1430),
        surfaceElevated = Color(0xFF161E42),
        surfaceVariant = Color(0xFF161E42),
        glassFillTop = GlassDark.fillTop,
        glassFillBottom = GlassDark.fillBottom,
        glassStroke = GlassDark.stroke,
        glassHighlight = GlassDark.highlight,
        primary = Color(0xFF5EE7FF),
        onPrimary = Color(0xFF00202B),
        primaryContainer = Color(0xFF5EE7FF).copy(alpha = 0.22f),
        onPrimaryContainer = Color(0xFFF2F5FF),
        secondary = Color(0xFFA78BFA),
        onSecondary = Color(0xFF12002E),
        tertiary = Color(0xFFFF5FD2),
        onTertiary = Color(0xFF3A0018),
        onSurface = Color(0xFFF2F5FF),
        onSurfaceVariant = Color(0xFFA9B2D6),
        outline = Color(0xFF2A3358),
        outlineVariant = Color(0xFF1B2440),
        error = Color(0xFFFF8A80),
        onError = Color(0xFF3A0907),
        errorContainer = Color(0xFF4A1512),
        heroStops = listOf(
            Color(0xFF22D3EE),
            Color(0xFF6366F1),
            Color(0xFFA855F7),
            Color(0xFFF472B6),
        ),
        accentStops = listOf(
            Color(0xFF5EE7FF),
            Color(0xFFA78BFA),
            Color(0xFFFF5FD2),
        ),
    )

    private fun auroraNeonLight() = AltarisPalette(
        isDark = false,
        base = Color(0xFFF4F2FF),
        surface = Color(0xFFFFFFFF),
        surfaceElevated = Color(0xFFF7F5FF),
        surfaceVariant = Color(0xFFF7F5FF),
        glassFillTop = GlassLight.fillTop,
        glassFillBottom = GlassLight.fillBottom,
        glassStroke = GlassLight.stroke,
        glassHighlight = GlassLight.highlight,
        primary = Color(0xFF5B4BD6),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF5B4BD6).copy(alpha = 0.14f),
        onPrimaryContainer = Color(0xFF141033),
        secondary = Color(0xFF0E7490),
        onSecondary = Color(0xFFFFFFFF),
        tertiary = Color(0xFFDB2777),
        onTertiary = Color(0xFFFFFFFF),
        onSurface = Color(0xFF141033),
        onSurfaceVariant = Color(0xFF4B4A66),
        outline = Color(0xFFC9C4EA),
        outlineVariant = Color(0xFFE4E0F7),
        error = Color(0xFFB3261E),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFF9DEDC),
        heroStops = listOf(
            Color(0xFFA78BFA),
            Color(0xFF818CF8),
            Color(0xFF22D3EE),
            Color(0xFFF472B6),
        ),
        accentStops = listOf(
            Color(0xFF5B4BD6),
            Color(0xFF0E7490),
            Color(0xFFDB2777),
        ),
    )

    // ── Liquid Glass ───────────────────────────────────────────────────────
    private fun liquidGlassDark() = AltarisPalette(
        isDark = true,
        base = Color(0xFF0B0D1A),
        surface = Color(0xFF141827),
        surfaceElevated = Color(0xFF1E2438),
        surfaceVariant = Color(0xFF1E2438),
        glassFillTop = GlassDark.fillTop,
        glassFillBottom = GlassDark.fillBottom,
        glassStroke = GlassDark.stroke,
        glassHighlight = GlassDark.highlight,
        primary = Color(0xFF8B7CFF),
        onPrimary = Color(0xFF12002E),
        primaryContainer = Color(0xFF8B7CFF).copy(alpha = 0.22f),
        onPrimaryContainer = Color(0xFFF6F7FF),
        secondary = Color(0xFF31D9F2),
        onSecondary = Color(0xFF00202B),
        tertiary = Color(0xFFFF7A59),
        onTertiary = Color(0xFF3A1200),
        onSurface = Color(0xFFF6F7FF),
        onSurfaceVariant = Color(0xFFA8AECD),
        outline = Color(0xFF2C3350),
        outlineVariant = Color(0xFF1D2438),
        error = Color(0xFFFF8A80),
        onError = Color(0xFF3A0907),
        errorContainer = Color(0xFF4A1512),
        heroStops = listOf(
            Color(0xFF7C3AED),
            Color(0xFF2563EB),
            Color(0xFF06B6D4),
            Color(0xFFF472B6),
        ),
        accentStops = listOf(
            Color(0xFF8B7CFF),
            Color(0xFF31D9F2),
            Color(0xFFFF7A59),
        ),
    )

    private fun liquidGlassLight() = AltarisPalette(
        isDark = false,
        base = Color(0xFFEEF1FB),
        surface = Color(0xFFFFFFFF),
        surfaceElevated = Color(0xFFF5F7FF),
        surfaceVariant = Color(0xFFF5F7FF),
        glassFillTop = GlassLight.fillTop,
        glassFillBottom = GlassLight.fillBottom,
        glassStroke = GlassLight.stroke,
        glassHighlight = GlassLight.highlight,
        primary = Color(0xFF5B4BD6),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF5B4BD6).copy(alpha = 0.14f),
        onPrimaryContainer = Color(0xFF0E1020),
        secondary = Color(0xFF0E7490),
        onSecondary = Color(0xFFFFFFFF),
        tertiary = Color(0xFFDC4C2C),
        onTertiary = Color(0xFFFFFFFF),
        onSurface = Color(0xFF0E1020),
        onSurfaceVariant = Color(0xFF4A4E63),
        outline = Color(0xFFC7CEE6),
        outlineVariant = Color(0xFFE2E7F4),
        error = Color(0xFFB3261E),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFF9DEDC),
        heroStops = listOf(
            Color(0xFF7C3AED),
            Color(0xFF2563EB),
            Color(0xFF06B6D4),
            Color(0xFFF472B6),
        ),
        accentStops = listOf(
            Color(0xFF5B4BD6),
            Color(0xFF0E7490),
            Color(0xFFDC4C2C),
        ),
    )

    // ── Solar Flare ────────────────────────────────────────────────────────
    private fun solarFlareDark() = AltarisPalette(
        isDark = true,
        base = Color(0xFF160F19),
        surface = Color(0xFF221729),
        surfaceElevated = Color(0xFF2E1F35),
        surfaceVariant = Color(0xFF2E1F35),
        glassFillTop = GlassDark.fillTop,
        glassFillBottom = GlassDark.fillBottom,
        glassStroke = GlassDark.stroke,
        glassHighlight = GlassDark.highlight,
        primary = Color(0xFFFF7A3D),
        onPrimary = Color(0xFF3A1200),
        primaryContainer = Color(0xFFFF7A3D).copy(alpha = 0.22f),
        onPrimaryContainer = Color(0xFFFDF3F7),
        secondary = Color(0xFFFFC53D),
        onSecondary = Color(0xFF261706),
        tertiary = Color(0xFFFF4D8D),
        onTertiary = Color(0xFF3A0018),
        onSurface = Color(0xFFFDF3F7),
        onSurfaceVariant = Color(0xFFC3AEBC),
        outline = Color(0xFF3B2A45),
        outlineVariant = Color(0xFF271A2C),
        error = Color(0xFFFF8A80),
        onError = Color(0xFF3A0907),
        errorContainer = Color(0xFF4A1512),
        heroStops = listOf(
            Color(0xFF7A2E4D),
            Color(0xFFE2543A),
            Color(0xFFFF9E3D),
            Color(0xFFFFD98A),
        ),
        accentStops = listOf(
            Color(0xFFFF7A3D),
            Color(0xFFFFC53D),
            Color(0xFFFF4D8D),
        ),
    )

    private fun solarFlareLight() = AltarisPalette(
        isDark = false,
        base = Color(0xFFFBF4EE),
        surface = Color(0xFFFFFFFF),
        surfaceElevated = Color(0xFFFFF8F3),
        surfaceVariant = Color(0xFFFFF8F3),
        glassFillTop = GlassLight.fillTop,
        glassFillBottom = GlassLight.fillBottom,
        glassStroke = GlassLight.stroke,
        glassHighlight = GlassLight.highlight,
        primary = Color(0xFFD1442A),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD1442A).copy(alpha = 0.14f),
        onPrimaryContainer = Color(0xFF241019),
        secondary = Color(0xFFA16207),
        onSecondary = Color(0xFFFFFFFF),
        tertiary = Color(0xFFBE185D),
        onTertiary = Color(0xFFFFFFFF),
        onSurface = Color(0xFF241019),
        onSurfaceVariant = Color(0xFF5C4A50),
        outline = Color(0xFFEAD6C9),
        outlineVariant = Color(0xFFF3E6DC),
        error = Color(0xFFB3261E),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFF9DEDC),
        heroStops = listOf(
            Color(0xFFFF9E3D),
            Color(0xFFE2543A),
            Color(0xFFFFD98A),
            Color(0xFFFF4D8D),
        ),
        accentStops = listOf(
            Color(0xFFD1442A),
            Color(0xFFA16207),
            Color(0xFFBE185D),
        ),
    )

    // ── Mint Prism ─────────────────────────────────────────────────────────
    private fun mintPrismDark() = AltarisPalette(
        isDark = true,
        base = Color(0xFF03201F),
        surface = Color(0xFF062C2B),
        surfaceElevated = Color(0xFF0B3B39),
        surfaceVariant = Color(0xFF0B3B39),
        glassFillTop = GlassDark.fillTop,
        glassFillBottom = GlassDark.fillBottom,
        glassStroke = GlassDark.stroke,
        glassHighlight = GlassDark.highlight,
        primary = Color(0xFF3FF0C8),
        onPrimary = Color(0xFF00201C),
        primaryContainer = Color(0xFF3FF0C8).copy(alpha = 0.22f),
        onPrimaryContainer = Color(0xFFEDFFFB),
        secondary = Color(0xFF35C4FF),
        onSecondary = Color(0xFF00202B),
        tertiary = Color(0xFF9B7BFF),
        onTertiary = Color(0xFF160E2D),
        onSurface = Color(0xFFEDFFFB),
        onSurfaceVariant = Color(0xFF9CC9C6),
        outline = Color(0xFF14484A),
        outlineVariant = Color(0xFF0C3534),
        error = Color(0xFFFF8A80),
        onError = Color(0xFF3A0907),
        errorContainer = Color(0xFF4A1512),
        heroStops = listOf(
            Color(0xFF00F5C8),
            Color(0xFF00C2FF),
            Color(0xFF7B61FF),
            Color(0xFFFF5FA2),
        ),
        accentStops = listOf(
            Color(0xFF3FF0C8),
            Color(0xFF35C4FF),
            Color(0xFF9B7BFF),
        ),
    )

    private fun mintPrismLight() = AltarisPalette(
        isDark = false,
        base = Color(0xFFEDFFFA),
        surface = Color(0xFFFFFFFF),
        surfaceElevated = Color(0xFFF4FFFC),
        surfaceVariant = Color(0xFFF4FFFC),
        glassFillTop = GlassLight.fillTop,
        glassFillBottom = GlassLight.fillBottom,
        glassStroke = GlassLight.stroke,
        glassHighlight = GlassLight.highlight,
        primary = Color(0xFF0A7A5E),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF0A7A5E).copy(alpha = 0.14f),
        onPrimaryContainer = Color(0xFF05201D),
        secondary = Color(0xFF0369A1),
        onSecondary = Color(0xFFFFFFFF),
        tertiary = Color(0xFF6D28D9),
        onTertiary = Color(0xFFFFFFFF),
        onSurface = Color(0xFF05201D),
        onSurfaceVariant = Color(0xFF3C5A54),
        outline = Color(0xFFBFE3D8),
        outlineVariant = Color(0xFFDCF2EA),
        error = Color(0xFFB3261E),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFF9DEDC),
        heroStops = listOf(
            Color(0xFF5EEAD4),
            Color(0xFF38BDF8),
            Color(0xFFA78BFA),
            Color(0xFFF472B6),
        ),
        accentStops = listOf(
            Color(0xFF0A7A5E),
            Color(0xFF0369A1),
            Color(0xFF6D28D9),
        ),
    )

    // ── Iridescent ─────────────────────────────────────────────────────────
    private fun iridescentDark() = AltarisPalette(
        isDark = true,
        base = Color(0xFF0E0F13),
        surface = Color(0xFF191B22),
        surfaceElevated = Color(0xFF23262F),
        surfaceVariant = Color(0xFF23262F),
        glassFillTop = GlassDark.fillTop,
        glassFillBottom = GlassDark.fillBottom,
        glassStroke = GlassDark.stroke,
        glassHighlight = GlassDark.highlight,
        primary = Color(0xFFE9E7FF),
        onPrimary = Color(0xFF16141F),
        primaryContainer = Color(0xFFE9E7FF).copy(alpha = 0.22f),
        onPrimaryContainer = Color(0xFFFAFAFF),
        secondary = Color(0xFF9BE7FF),
        onSecondary = Color(0xFF00202B),
        tertiary = Color(0xFFFFC0F0),
        onTertiary = Color(0xFF3A0018),
        onSurface = Color(0xFFFAFAFF),
        onSurfaceVariant = Color(0xFFA9A9B8),
        outline = Color(0xFF2E313C),
        outlineVariant = Color(0xFF22252C),
        error = Color(0xFFFF8A80),
        onError = Color(0xFF3A0907),
        errorContainer = Color(0xFF4A1512),
        heroStops = listOf(
            Color(0xFFFFD6F5),
            Color(0xFFD9C8FF),
            Color(0xFFBFE9FF),
            Color(0xFFC9FFE6),
        ),
        accentStops = listOf(
            Color(0xFFE9E7FF),
            Color(0xFF9BE7FF),
            Color(0xFFFFC0F0),
        ),
    )

    private fun iridescentLight() = AltarisPalette(
        isDark = false,
        base = Color(0xFFF5F4FC),
        surface = Color(0xFFFFFFFF),
        surfaceElevated = Color(0xFFFAF9FF),
        surfaceVariant = Color(0xFFFAF9FF),
        glassFillTop = GlassLight.fillTop,
        glassFillBottom = GlassLight.fillBottom,
        glassStroke = GlassLight.stroke,
        glassHighlight = GlassLight.highlight,
        primary = Color(0xFF4C3FBF),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF4C3FBF).copy(alpha = 0.14f),
        onPrimaryContainer = Color(0xFF16141F),
        secondary = Color(0xFF0369A1),
        onSecondary = Color(0xFFFFFFFF),
        tertiary = Color(0xFFA21CAF),
        onTertiary = Color(0xFFFFFFFF),
        onSurface = Color(0xFF16141F),
        onSurfaceVariant = Color(0xFF4E4C5C),
        outline = Color(0xFFD6D3EA),
        outlineVariant = Color(0xFFE9E6F6),
        error = Color(0xFFB3261E),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFF9DEDC),
        heroStops = listOf(
            Color(0xFFD9C8FF),
            Color(0xFFBFE9FF),
            Color(0xFFFFD6F5),
            Color(0xFFC9FFE6),
        ),
        accentStops = listOf(
            Color(0xFF4C3FBF),
            Color(0xFF0369A1),
            Color(0xFFA21CAF),
        ),
    )
}
