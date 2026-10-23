package com.altaris.linux.core.terminal

import android.util.Log
import java.io.File
import java.nio.file.Files

/**
 * Rebrands a legacy `flux` guest account to `altaris`.
 *
 * Older builds created the guest user as `flux` (and pointed its home at
 * `/home/flux`). The app now logs in as `altaris`; this migration renames the
 * account in passwd/shadow/group/sudoers, moves the home directory to
 * `/home/altaris`, and keeps `/home/flux` as a symlink so every path written
 * by the setup scripts and the desktop still resolves.
 *
 * It runs before a proot session is built, so an existing container is fixed
 * the first time it is opened — no reinstall and no root required (the files
 * live under the app's own data directory).
 */
object GuestUserMigration {

    private const val TAG = "GuestUserMigration"
    private const val LEGACY = "flux"
    private const val PRIMARY = "altaris"
    private const val LEGACY_HOME = "/home/$LEGACY"
    private const val PRIMARY_HOME = "/home/$PRIMARY"

    fun ensureAltaris(rootfs: File?) {
        if (rootfs == null || !rootfs.isDirectory) return
        runCatching { migrate(rootfs) }
            .onFailure { Log.w(TAG, "could not migrate ${rootfs.path}: ${it.message}") }
    }

    private fun migrate(rootfs: File) {
        val passwdFile = File(rootfs, "etc/passwd")
        if (!passwdFile.isFile) return
        val passwd = passwdFile.readLines()
        val hasPrimary = passwd.any { it.startsWith("$PRIMARY:") }
        val hasLegacy = passwd.any { it.startsWith("$LEGACY:") }
        if (!hasPrimary && !hasLegacy) return

        // 1. Rename the account in the identity databases.
        if (!hasPrimary && hasLegacy) {
            renameAccount(rootfs, passwdFile)
        }

        // 2. Make /home/altaris the real home and keep /home/flux as a link.
        moveHome(rootfs)

        // 3. Point the primary account at the new home.
        val updated = passwdFile.readLines().map { line ->
            if (!line.startsWith("$PRIMARY:")) return@map line
            val fields = line.split(":").toMutableList()
            if (fields.size >= 7 &&
                (fields[5].isEmpty() || fields[5] == "/" || fields[5] == LEGACY_HOME)
            ) {
                fields[5] = PRIMARY_HOME
            }
            fields.joinToString(":")
        }
        passwdFile.writeText(updated.joinToString("\n", postfix = "\n"))

        // 4. sudo/doas for the new name.
        ensureSudo(rootfs)
    }

    private fun renameAccount(rootfs: File, passwdFile: File) {
        // passwd: rename the login name and the home field.
        val passwd = passwdFile.readLines().map { line ->
            if (!line.startsWith("$LEGACY:")) return@map line
            val fields = line.split(":").toMutableList()
            fields[0] = PRIMARY
            if (fields.size >= 7 && fields[5] == LEGACY_HOME) fields[5] = PRIMARY_HOME
            fields.joinToString(":")
        }
        passwdFile.writeText(passwd.joinToString("\n", postfix = "\n"))

        // shadow / gshadow / group: rename the first field and group members.
        for (name in listOf("shadow", "gshadow")) {
            renameSimple(File(rootfs, "etc/$name"), legacyAsFirstField = true)
        }
        renameSimple(File(rootfs, "etc/group"), legacyAsFirstField = true, replaceMembers = true)

        // sudoers: the old file name is part of the legacy identity.
        val oldSudoers = File(rootfs, "etc/sudoers.d/$LEGACY")
        if (oldSudoers.isFile) {
            val text = oldSudoers.readText().replace(Regex("(?m)^$LEGACY\\b"), PRIMARY)
            val newSudoers = File(rootfs, "etc/sudoers.d/$PRIMARY")
            newSudoers.writeText(text)
            runCatching { newSudoers.setReadable(false, false) }
            runCatching { newSudoers.setReadable(true, true) }
            oldSudoers.delete()
        }
        // doas.conf (Chimera): "permit nopass flux" -> altaris
        val doas = File(rootfs, "etc/doas.conf")
        if (doas.isFile) {
            doas.writeText(
                doas.readText().replace(Regex("(?m)(permit\\s+\\S+\\s+)$LEGACY\\b"), "$1$PRIMARY")
            )
        }
    }

    private fun renameSimple(
        file: File,
        legacyAsFirstField: Boolean,
        replaceMembers: Boolean = false,
    ) {
        if (!file.isFile) return
        val text = file.readLines().joinToString("\n") { line ->
            var out = line
            if (legacyAsFirstField && out.startsWith("$LEGACY:")) {
                out = PRIMARY + out.removePrefix(LEGACY)
            }
            if (replaceMembers) {
                // "wheel:x:10:flux,someone" -> "wheel:x:10:altaris,someone"
                val fields = out.split(":").toMutableList()
                if (fields.size >= 4 && fields[3].split(",").contains(LEGACY)) {
                    fields[3] = fields[3].split(",").joinToString(",") {
                        if (it == LEGACY) PRIMARY else it
                    }
                    out = fields.joinToString(":")
                }
            }
            out
        }
        file.writeText(text + if (text.endsWith("\n")) "" else "\n")
    }

    private fun moveHome(rootfs: File) {
        val legacy = File(rootfs, LEGACY_HOME.removePrefix("/"))
        val primary = File(rootfs, PRIMARY_HOME.removePrefix("/"))
        // A previous alias setup may have made /home/altaris a symlink to
        // /home/flux. Drop the link so the real directory can take its place.
        if (Files.isSymbolicLink(primary.toPath())) {
            runCatching { Files.delete(primary.toPath()) }
        }
        if (legacy.isDirectory && !primary.exists()) {
            legacy.renameTo(primary)
        }
        if (primary.isDirectory && !legacy.exists()) {
            runCatching { Files.createSymbolicLink(legacy.toPath(), File(PRIMARY_HOME).toPath()) }
        }
    }

    private fun ensureSudo(rootfs: File) {
        val dir = File(rootfs, "etc/sudoers.d")
        if (!dir.isDirectory) return
        val target = File(dir, PRIMARY)
        val line = "$PRIMARY ALL=(ALL) NOPASSWD:ALL\n"
        if (!target.isFile || !target.readText().contains(PRIMARY)) {
            target.writeText(line)
        }
        runCatching { target.setReadable(false, false) }
        runCatching { target.setReadable(true, true) }
        val doas = File(rootfs, "etc/doas.conf")
        if (doas.isFile && !doas.readText().contains(PRIMARY)) {
            doas.appendText("permit nopass $PRIMARY\n")
        }
    }
}
