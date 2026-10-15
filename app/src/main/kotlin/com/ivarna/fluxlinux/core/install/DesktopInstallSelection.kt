package com.ivarna.fluxlinux.core.install

import com.ivarna.fluxlinux.core.data.DistroComponent
import com.ivarna.fluxlinux.core.data.DistroRepository

/**
 * Desktop environment to install with a distro base install.
 *
 * [envValue] is the `FLUX_DESKTOP` contract handed to guest setup scripts:
 * `xfce` (default — preserves current behavior) | `kde` | `none`.
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
 */
object DesktopInstallSelection {

    const val COMPONENT_XFCE = "xfce4_desktop"
    const val COMPONENT_CUSTOMIZATION = "customization"
    const val COMPONENT_KDE_PLASMA = "kde_plasma"
    const val COMPONENT_KDE_CUSTOMIZATION = "kde_customization"

    /** True when [distroId] carries a usable KDE Plasma component. */
    fun kdePlasmaAvailable(distroId: String): Boolean =
        distroComponents(distroId).any { it.id == COMPONENT_KDE_PLASMA && !it.comingSoon }

    /** Desktop components (install order) for [choice] on [distroId]. */
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
