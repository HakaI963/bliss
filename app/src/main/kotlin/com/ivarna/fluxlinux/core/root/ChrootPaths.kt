package com.ivarna.fluxlinux.core.root

/**
 * On-device chroot SSOT paths + helper identity.
 * Shared by [com.ivarna.fluxlinux.core.terminal.ChrootCommandBuilder] and
 * [RootShell] without an import cycle (RootShell = su only, never imports
 * ChrootCommandBuilder — plan §2.5).
 */
object ChrootPaths {
    /** Folio-owned root tree on device (all root-requiring artifacts live here). */
    const val FOLIO_ROOT = "/data/local/folio"

    /** Distro rootfs trees, one subdir per distro. */
    const val CHROOTS_DIR = "$FOLIO_ROOT/chroots"

    /** Deployed helpers: chroot helper, resolver, generated launchers. */
    const val HELPERS_DIR = "$FOLIO_ROOT/helpers"

    /** Transient staging for scripts pushed via `su`/base64. */
    const val STAGING_DIR = "$FOLIO_ROOT/staging"

    /** Pinned BusyBox binaries. */
    const val BUSYBOX_DIR = "$FOLIO_ROOT/busybox"

    /** Debian 13 chroot rootfs (historical default). */
    const val DEBIAN_CHROOT_PATH = "$CHROOTS_DIR/debian"

    /** Alpine 3.24 chroot rootfs (coexists with Debian). */
    const val ALPINE_CHROOT_PATH = "$CHROOTS_DIR/alpine"

    const val FEDORA_CHROOT_PATH = "$CHROOTS_DIR/fedora"
    const val VOID_CHROOT_PATH = "$CHROOTS_DIR/void"
    const val OPENSUSE_CHROOT_PATH = "$CHROOTS_DIR/opensuse"

    const val DEEPIN_CHROOT_PATH = "$CHROOTS_DIR/deepin"
    const val CHIMERA_CHROOT_PATH = "$CHROOTS_DIR/chimera"
    const val MANJARO_CHROOT_PATH = "$CHROOTS_DIR/manjaro"

    const val UBUNTU_CHROOT_PATH = "$CHROOTS_DIR/ubuntu"
    const val KALI_CHROOT_PATH = "$CHROOTS_DIR/kali"
    const val PARROT_CHROOT_PATH = "$CHROOTS_DIR/parrot"
    const val ARCH_CHROOT_PATH = "$CHROOTS_DIR/archlinux"

    /**
     * Default chroot path for unscoped APIs (settings / legacy).
     * Prefer [DEBIAN_CHROOT_PATH] / [ALPINE_CHROOT_PATH] or profile.chrootPath.
     */
    const val CHROOT_PATH = DEBIAN_CHROOT_PATH

    /** On-device SSOT helper (assets/scripts/chroot/fluxlinux_chroot.sh). */
    const val CHROOT_HELPER = "$HELPERS_DIR/fluxlinux_chroot.sh"
    const val CHROOT_HELPER_ASSET = "scripts/chroot/fluxlinux_chroot.sh"
    /** Must match first `# fluxlinux-chroot vN` line in the asset. */
    const val CHROOT_HELPER_VERSION = "fluxlinux-chroot v2.11"

    /** Session executable — must be a system binary (SELinux blocks exec of app-data scripts). */
    const val SESSION_EXEC = "/system/bin/sh"

    fun pathForDistro(distroId: String): String = when (distroId) {
        "alpine_chroot" -> ALPINE_CHROOT_PATH
        "debian13_chroot", "debian_chroot" -> DEBIAN_CHROOT_PATH
        "fedora_chroot" -> FEDORA_CHROOT_PATH
        "void_chroot" -> VOID_CHROOT_PATH
        "opensuse_chroot" -> OPENSUSE_CHROOT_PATH
        "deepin_chroot" -> DEEPIN_CHROOT_PATH
        "chimera_chroot" -> CHIMERA_CHROOT_PATH
        "manjaro_chroot" -> MANJARO_CHROOT_PATH
        "ubuntu_chroot" -> UBUNTU_CHROOT_PATH
        "kali_chroot" -> KALI_CHROOT_PATH
        "parrot_chroot" -> PARROT_CHROOT_PATH
        "archlinux_chroot" -> ARCH_CHROOT_PATH
        else -> CHROOT_PATH
    }
}
