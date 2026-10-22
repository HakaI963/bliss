package com.altaris.linux.core.data

/**
 * In-app terminal component used for Distro Install/Run actions.
 * Single place so cards never call TermuxIntentFactory for these ids.
 */
enum class TerminalComponent(val method: String, val label: String) {
    /** Host + proot Debian sessions (no root). */
    TERMUX_ALTARIS_TERMINAL("proot", "Altaris Terminal"),

    /** Root + SSOT chroot helper. */
    CHROOT_ROOT_SHELL("chroot", "Root Shell")
}

/**
 * Map a distro id to its terminal component. The removed `termux` (Termux Native)
 * card has no component — any path reaching it is a product bug.
 */
fun terminalComponentFor(distroId: String): TerminalComponent {
    val profile = com.altaris.linux.core.install.DistroInstallProfile.forId(distroId)
        ?: throw IllegalArgumentException("unsupported install card: $distroId")
    return if (profile.method == "chroot") {
        TerminalComponent.CHROOT_ROOT_SHELL
    } else {
        TerminalComponent.TERMUX_ALTARIS_TERMINAL
    }
}
