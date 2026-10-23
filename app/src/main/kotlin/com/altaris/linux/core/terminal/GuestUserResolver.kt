package com.altaris.linux.core.terminal

import java.io.File

/**
 * Resolves the guest login user.
 *
 * New guests use `altaris` (uid 1000). A rootfs that was set up by an older
 * build — or a setup run that failed half-way — may still only carry the
 * legacy `flux` account, so the app resolves the name against the real passwd
 * before building any command and falls back to the old name.
 */
object GuestUserResolver {

    /** The names the guest may use, in preference order. */
    val GUEST_USERS = listOf("altaris", "flux")

    /** Reads `<rootfs>/etc/passwd`; null when the rootfs has no passwd yet. */
    fun passwdOf(rootfs: File?): List<List<String>>? {
        val file = rootfs?.let { File(it, "etc/passwd") } ?: return null
        if (!file.isFile) return null
        return runCatching {
            file.readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { it.split(":") }
                .filter { it.size >= 7 }
        }.getOrNull()
    }

    /**
     * The user to log in with: [preferred] when the guest has it, otherwise
     * the first other known guest user, otherwise [preferred] (so the error
     * stays meaningful instead of silently becoming root).
     */
    fun resolve(preferred: String, passwd: List<List<String>>?): String {
        if (preferred == "root") return "root"
        val entries = passwd.orEmpty()
        if (entries.isEmpty()) return preferred
        if (entries.any { it[0] == preferred }) return preferred
        GUEST_USERS.firstOrNull { name -> entries.any { it[0] == name } }?.let { return it }
        // No guest account at all (a rootfs whose setup stopped early): root
        // always exists, and a root shell beats a dead session.
        return if (entries.any { it[0] == "root" }) "root" else preferred
    }

    /** Convenience: resolve against the rootfs directory. */
    fun resolve(preferred: String, rootfs: File?): String =
        resolve(preferred, passwdOf(rootfs))

    /** The home directory of [user] in the guest, or a sensible default. */
    fun homeOf(user: String, passwd: List<List<String>>?): String {
        if (user == "root") return "/root"
        val home = passwd.orEmpty().firstOrNull { it[0] == user }?.get(5)
        return if (home.isNullOrBlank() || home == "/") "/home/$user" else home
    }
}
