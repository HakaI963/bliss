package com.altaris.linux.core.terminal

import android.content.Context

/** Entry point for building terminal commands.
 *  Delegates to ProotCommandBuilder or ChrootCommandBuilder based on method.
 *  Ported from termux-lib `LinuxCommandBuilder`. */
object LinuxCommandBuilder {

    /**
     * UI-default isolation method. DEPRECATED for product/card actions —
     * card paths must pass `method` explicitly from
     * `terminalComponentFor(distroId).method` (plan §2.6). Kept only as a
     * convenience default for the shared Terminal page tool selector.
     */
    @Deprecated("Pass method explicitly from terminalComponentFor(distroId).method for card actions")
    var currentMethod = "proot"

    /**
     * Guest user for session type.
     * - shell-root / component → root (apt/dpkg & setup scripts)
     * - interactive shell / default → altaris
     */
    fun sessionUserForType(type: String): String =
        when (type) {
            "shell-root", "component" -> "root"
            else -> "altaris"
        }

    fun build(
        ctx: Context,
        shellCmd: String,
        user: String = "altaris",
        useSharedTmp: Boolean = true,
        method: String = currentMethod,
        distroId: String? = null,
        loginShell: GuestLoginShell? = null
    ): Pair<Array<String>, HashMap<String, String>> {
        val profile = distroId?.let {
            com.altaris.linux.core.install.DistroInstallProfile.forId(it)
        }
        return when (method) {
            "chroot" -> ChrootCommandBuilder.build(
                ctx,
                shellCmd,
                // Chroot images still carry the legacy flux account and the
                // root-side start scripts address it by name. Keep the alias
                // there until the chroot setup migrates too.
                if (user == "altaris") "flux" else user,
                chrootPath = profile?.chrootPath
                    ?: com.altaris.linux.core.root.ChrootPaths.CHROOT_PATH,
                loginShell = loginShell
            )
            else -> ProotCommandBuilder.build(
                ctx,
                shellCmd,
                user,
                useSharedTmp,
                distro = profile?.prootName ?: "debian",
                loginShell = loginShell ?: GuestLoginShell.DEFAULT
            )
        }
    }
}
