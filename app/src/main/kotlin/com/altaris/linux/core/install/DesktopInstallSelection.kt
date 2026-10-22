package com.altaris.linux.core.install

import com.altaris.linux.core.data.DistroComponent
import com.altaris.linux.core.data.DistroRepository

/**
 * Desktop environment to install with a distro base install.
 *
 * [envValue] is the single-value `ALTARIS_DESKTOP` contract handed to guest setup
 * scripts: `xfce` (default — preserves current behavior) | `kde` | `none`.
 * Multi-desktop installs are expressed as a `Set<DesktopChoice>` and thread
 * through as `ALTARIS_DESKTOPS` (space-separated, canonical xfce→kde order).
 */
enum class DesktopChoice(val envValue: String) {
    /** Rootfs + shell only — desktop packages are skipped. */
    NONE("none"),

    /** XFCE4 desktop (today's default). */
    XFCE("xfce"),

    /** KDE Plasma — offered only where the distro data carries `kde_plasma`. */
    KDE("kde");

    companion object {
        /** Lenient parse; unknown values fall back to [XFCE] (today's behavior). */
        fun fromEnvValue(value: String?): DesktopChoice =
            values().firstOrNull { it.envValue == value } ?: XFCE
    }
}

/**
 * Optional install extras. An empty set means today's defaults (hardware
 * acceleration runs whenever the distro profile ships an hw-accel script).
 */
enum class InstallExtra {
    /** Skip the guest hw-accel (Turnip / VirGL) step. */
    SKIP_HW_ACCEL
}

/**
 * Data-driven desktop → component resolution over [DistroRepository].
 *
 * The repository stays the SSOT: the picker offers KDE only when the selected
 * distro really carries a `kde_plasma` component (Debian family today), and
 * the runner resolves component scripts by id — never by hardcoded paths.
 * Selections are sets so "XFCE + KDE" installs run both component chains.
 */
object DesktopInstallSelection {

    const val COMPONENT_XFCE = "xfce4_desktop"
    const val COMPONENT_CUSTOMIZATION = "customization"
    const val COMPONENT_KDE_PLASMA = "kde_plasma"
    const val COMPONENT_KDE_CUSTOMIZATION = "kde_customization"

    /** Canonical ALTARIS_DESKTOPS order: XFCE first, then KDE. */
    val CANONICAL_ORDER: List<DesktopChoice> = listOf(DesktopChoice.XFCE, DesktopChoice.KDE)

    /** True when [distroId] carries a usable KDE Plasma component. */
    fun kdePlasmaAvailable(distroId: String): Boolean =
        distroComponents(distroId).any { it.id == COMPONENT_KDE_PLASMA && !it.comingSoon }

    /** Selection set normalized to canonical order; [DesktopChoice.NONE] dropped. */
    fun canonical(choices: Set<DesktopChoice>): Set<DesktopChoice> =
        CANONICAL_ORDER.filterTo(LinkedHashSet()) { it in choices }

    /** Canonical selection with KDE dropped when the distro lacks `kde_plasma`. */
    fun sanitize(distroId: String, choices: Set<DesktopChoice>): Set<DesktopChoice> {
        val canonical = canonical(choices)
        return if (DesktopChoice.KDE in canonical && !kdePlasmaAvailable(distroId)) {
            canonical - DesktopChoice.KDE
        } else {
            canonical
        }
    }

    /** `ALTARIS_DESKTOPS` contract value: `"xfce kde"` (canonical) or `"none"`. */
    fun envValue(choices: Set<DesktopChoice>): String =
        canonical(choices).joinToString(" ") { it.envValue }
            .ifEmpty { DesktopChoice.NONE.envValue }

    /** First selected desktop (legacy `ALTARIS_DESKTOP` gate) or `"none"`. */
    fun firstEnvValue(choices: Set<DesktopChoice>): String =
        canonical(choices).firstOrNull()?.envValue ?: DesktopChoice.NONE.envValue

    /** Legacy single-value env parse: `none` → empty set, `kde`/`xfce` → one desktop. */
    fun fromSingleEnvValue(value: String?): Set<DesktopChoice> = when (value) {
        DesktopChoice.NONE.envValue -> emptySet()
        DesktopChoice.KDE.envValue -> setOf(DesktopChoice.KDE)
        else -> setOf(DesktopChoice.XFCE)
    }

    /** Short copy label: `"XFCE4 + KDE Plasma"` … or `"Terminal base"`. */
    fun summaryLabel(choices: Set<DesktopChoice>): String {
        val canonical = canonical(choices)
        if (canonical.isEmpty()) return "Terminal base"
        return canonical.joinToString(" + ") { choice ->
            when (choice) {
                DesktopChoice.KDE -> "KDE Plasma"
                else -> "XFCE4"
            }
        }
    }

    /** Desktop components (install order) for a single [choice] on [distroId]. */
    fun componentsFor(distroId: String, choice: DesktopChoice): List<DistroComponent> {
        val ids = when (choice) {
            DesktopChoice.NONE -> emptyList()
            DesktopChoice.XFCE -> listOf(COMPONENT_XFCE)
            DesktopChoice.KDE -> listOf(COMPONENT_KDE_PLASMA, COMPONENT_KDE_CUSTOMIZATION)
        }
        if (ids.isEmpty()) return emptyList()
        val byId = distroComponents(distroId)
            .filter { !it.comingSoon }
            .associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    /** Desktop components for every selected desktop (canonical order). */
    fun componentsFor(distroId: String, choices: Set<DesktopChoice>): List<DistroComponent> =
        canonical(choices).flatMap { componentsFor(distroId, it) }

    /** Component script asset path (under `scripts/`) for [componentId], if present. */
    fun componentScriptName(distroId: String, componentId: String): String? =
        distroComponents(distroId)
            .firstOrNull { it.id == componentId && !it.comingSoon }
            ?.scriptName

    /** KDE falls back to XFCE when the distro has no `kde_plasma` component. */
    fun sanitize(distroId: String, choice: DesktopChoice): DesktopChoice =
        if (choice == DesktopChoice.KDE && !kdePlasmaAvailable(distroId)) {
            DesktopChoice.XFCE
        } else {
            choice
        }

    private fun distroComponents(distroId: String): List<DistroComponent> =
        DistroRepository.supportedDistros.firstOrNull { it.id == distroId }?.components
            ?: emptyList()
}
