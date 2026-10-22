package com.altaris.linux.core.terminal

import android.content.Context
import com.altaris.linux.core.data.Distro
import com.altaris.linux.core.data.terminalComponentFor
import com.altaris.linux.core.install.DistroInstallProfile
import com.altaris.linux.core.root.BusyBoxPaths
import com.altaris.linux.core.root.ChrootPaths
import com.altaris.linux.core.root.RootShell

/**
 * Distro uninstall sessions.
 *  - proot: `proot-distro remove <name>` + marker cleanup + callback
 *  - chroot: root session running staged uninstall_*_chroot.sh
 */
object UninstallSessionFactory {

    fun openUninstallSession(
        ctx: Context,
        distro: Distro
    ): Boolean {
        val method = terminalComponentFor(distro.id).method
        val profile = DistroInstallProfile.forId(distro.id)
        val appCtx = ctx.applicationContext
        val onClosed: (Int) -> Unit = {
            TerminalLauncher.refreshInstalledAfterUninstall(appCtx, distro.id)
        }
        return when (method) {
            "chroot" -> {
                val asset = profile?.chrootUninstallAsset
                    ?: "scripts/chroot/uninstall_debian13_chroot.sh"
                val name = asset.substringAfterLast('/')
                RootShell.ensureBusyBoxResolver(ctx)
                val resolvedBb = RootShell.resolveBusyBox()
                val staged = RootShell.stageAsset(ctx, asset)
                    ?: TermuxHostPaths.hostScript(ctx, name).absolutePath
                val resolverStaged = RootShell.stageAsset(ctx, BusyBoxPaths.RESOLVER_ASSET)
                    ?: TermuxHostPaths.hostScript(ctx, "resolve_bb.sh").absolutePath
                val chrootPath = profile?.chrootPath.orEmpty()
                val tmpPath = "${ChrootPaths.STAGING_DIR}/$name"
                val stagedQ = staged.replace("'", "'\\''")
                val resolverQ = resolverStaged.replace("'", "'\\''")
                val chrootQ = chrootPath.replace("'", "'\\''")
                val bbExport = if (!resolvedBb.isNullOrEmpty()) "ALTARIS_BB='$resolvedBb' " else ""
                // Copy out of app-private storage (su often cannot read it),
                // then run with ALTARIS_CHROOT on the su command (su drops env).
                val inner =
                    "mkdir -p '${ChrootPaths.STAGING_DIR}' && cp -f '$stagedQ' '$tmpPath' && chmod 755 '$tmpPath' && " +
                        "{ cp -f '$resolverQ' '${BusyBoxPaths.RESOLVER_ON_DEVICE}' && " +
                        "chmod 755 '${BusyBoxPaths.RESOLVER_ON_DEVICE}' || true; } && " +
                        "${bbExport}ALTARIS_RESOLVE_BB='${BusyBoxPaths.RESOLVER_ON_DEVICE}' " +
                        "ALTARIS_CHROOT='$chrootQ' ALTARIS_DISTRO_ID='${distro.id}' " +
                        "sh '$tmpPath'"
                InstallSessionFactory.openRootInnerSession(
                    ctx,
                    inner,
                    title = "Uninstall ${distro.name}",
                    rootfsFileName = profile?.rootfsFileName
                        ?: DistroInstallProfile.DEBIAN_ROOTFS_NAME,
                    extraEnv = mapOf(
                        "ALTARIS_CHROOT" to chrootPath,
                        "ALTARIS_DISTRO_ID" to distro.id
                    ),
                    wrapWinch = false,
                    // uninstall_debian13_chroot.sh removes the shared helper.
                    onClosed = { RootShell.invalidateChrootHelper(); onClosed(it) }
                )
            }
            else -> {
                val removeName = profile?.prootName ?: distro.id
                val callback = "am start -a android.intent.action.VIEW -d " +
                    "\"altaris://callback?result=success&name=distro_uninstall_${distro.id}\""
                val cmd = buildString {
                    append("NAME=$removeName; ")
                    append("DIR=\"\$PREFIX/var/lib/proot-distro/containers/\$NAME\"; ")
                    append("proot-distro remove \$NAME; RC=\$?; ")
                    append("rm -rf \"\$DIR\" 2>/dev/null || true; ")
                    // Customization can leave root-owned fonts/icons; app-uid rm fails.
                    append("if [ -e \"\$DIR\" ]; then ")
                    append("if [ -x /system/bin/su ]; then /system/bin/su -c \"rm -rf \$DIR\" </dev/null; ")
                    append("elif command -v su >/dev/null 2>&1; then su -c \"rm -rf \$DIR\" </dev/null; fi; ")
                    append("fi; ")
                    append("rm -f \"\$HOME/.altarislinux_distro_${distro.id}_installed\" 2>/dev/null; ")
                    append("if [ ! -e \"\$DIR\" ]; then RC=0; fi; ")
                    append("if [ \$RC -eq 0 ]; then $callback; fi; exit \$RC")
                }
                InstallSessionFactory.openHostCommandSession(
                    ctx, cmd, title = "Uninstall ${distro.name}", onClosed = onClosed
                )
            }
        }
    }
}
