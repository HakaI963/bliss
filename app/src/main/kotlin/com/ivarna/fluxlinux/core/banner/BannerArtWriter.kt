package com.ivarna.fluxlinux.core.banner

import android.content.Context
import android.util.Base64
import android.util.Log
import com.ivarna.fluxlinux.core.root.ChrootStager
import com.ivarna.fluxlinux.core.root.RootShell
import com.ivarna.fluxlinux.core.terminal.GuestZshrcRepair
import java.io.File

/**
 * Writes the launch-sticker payload (active art designs + `banner.sh` + rc
 * wiring) into every guest home:
 *
 * * proot rootfs: `filesDir/usr/var/lib/proot-distro/containers/<name>/rootfs/home/flux`
 * * host home: `filesDir/home`
 * * chroots: `/data/local/bliss/chroots/<name>/home/flux` — via the staged
 *   root helper ([RootShell.captureInChroot]), skipped when root is missing.
 *
 * All operations are best-effort: a missing asset set, an unwritable file or
 * an absent guest never throws out of [pushAll]. [repairRcFiles] dedupes
 * repeated sticker/appearance lines and wires bash (`.bashrc`, `.bash_profile`).
 */
object BannerArtWriter {

    private const val TAG = "BannerArtWriter"

    private const val STICKER_COMMENT =
        "# Altaris launch sticker (random anime banner; opt out FLUX_NO_BANNER=1)"
    private const val STICKER_LINE =
        "[ -r \"\$HOME/.config/bliss/banner.sh\" ] && . \"\$HOME/.config/bliss/banner.sh\""
    private const val BASHRC_LINE = "[ -f \"\$HOME/.bashrc\" ] && . \"\$HOME/.bashrc\""

    /**
     * Write the active art set + `banner.sh` + `.banner-rev` into
     * `<home>/.config/bliss/banner/`. `.txt` files outside the active set are
     * removed (only when the active set is non-empty, so an unreadable asset
     * set can never wipe a guest's designs); identical files are left
     * untouched. Returns true when the banner dir is usable afterwards.
     */
    fun writeIntoHome(ctx: Context, home: File): Boolean {
        val dir = File(home, ".config/bliss/banner")
        return try {
            dir.mkdirs()
            if (!dir.isDirectory) return false
            val (rev, arts) = BannerAssets.active(ctx)
            if (arts.isNotEmpty()) {
                val keep = arts.map { it.name }.toSet()
                dir.listFiles()?.forEach { f ->
                    if (f.isFile && f.name.endsWith(".txt") && f.name !in keep) {
                        f.delete()
                    }
                }
            }
            for (art in arts) {
                val f = File(dir, art.name)
                if (f.isFile && !f.canWrite()) continue
                val want = withTrailingNewline(art.text)
                if (sameText(f, want)) continue
                f.writeText(want)
            }
            val revMark = File(dir, ".banner-rev")
            val wantRev = rev + "\n"
            if (!sameText(revMark, wantRev) && (!revMark.exists() || revMark.canWrite())) {
                revMark.writeText(wantRev)
            }
            val sh = File(home, ".config/bliss/banner.sh")
            val wantSh = BannerAssets.bannerSh(ctx)
            if (wantSh.isNotEmpty() && !sameText(sh, wantSh) && (!sh.exists() || sh.canWrite())) {
                sh.parentFile?.mkdirs()
                sh.writeText(wantSh)
            }
            Log.i(TAG, "Banner assets ready at ${dir.absolutePath}")
            dir.isDirectory
        } catch (e: Exception) {
            Log.w(TAG, "banner write failed: ${e.message}")
            false
        }
    }

    /**
     * Keep the guest rc files tidy and wired:
     *
     * * `.zshrc` / `.bashrc` (when present): dedupe repeated `banner.sh` /
     *   `appearance-apply.sh` lines (first occurrence wins) and ensure the
     *   sticker source line is present.
     * * bash: ensure `.bash_profile` exists (or references `.bashrc`).
     */
    fun repairRcFiles(home: File) {
        try {
            val zshrc = File(home, ".zshrc")
            val bashrc = File(home, ".bashrc")
            if (zshrc.isFile) repairRcFile(zshrc)
            if (bashrc.isFile) {
                repairRcFile(bashrc)
            } else if (home.isDirectory && home.canWrite()) {
                // Bash login with no .bashrc would never show the sticker.
                try {
                    bashrc.writeText(
                        "# Altaris bash profile\n" + STICKER_COMMENT + "\n" + STICKER_LINE + "\n"
                    )
                    Log.i(TAG, "Created .bashrc at ${bashrc.absolutePath}")
                } catch (e: Exception) {
                    Log.w(TAG, "bashrc create failed: ${e.message}")
                }
            }
            ensureBashProfile(home)
        } catch (e: Exception) {
            Log.w(TAG, "rc repair failed: ${e.message}")
        }
    }

    /**
     * Repair one chroot: rc dedupe/wiring + `banner.sh` + the active art set +
     * `.banner-rev`. Large content goes through [ChrootStager] because a single
     * `su -c` argument is capped (~128 KB) by the kernel; only small
     * setup/finalize commands stay inline. [extraSetup] lets GuestZshrcRepair
     * splice its non-banner repairs into the setup step.
     *
     * The active art set is never deleted up-front: files are written first,
     * then the finalize step prunes names missing from `.keep` and writes the
     * revision marker — a failed push leaves the previous set usable.
     *
     * Returns true when the finalize step ran (its stdout carries the
     * [GuestZshrcRepair.CHROOT_OK_MARKER]).
     */
    fun pushChroot(ctx: Context, chrootPath: String, extraSetup: String = ""): Boolean {
        val d = "\$"
        val marker = GuestZshrcRepair.CHROOT_OK_MARKER
        val (rev, arts) = BannerAssets.active(ctx)

        // 1) setup: dirs, rc wiring/dedupe, caller extras. No deletions yet.
        val setup = StringBuilder()
        setup.append("set +e\n")
        setup.append("H=/home/flux\n")
        setup.append("[ -d \"${d}H\" ] || { echo $marker; exit 0; }\n")
        setup.append("_put() { printf '%s' \"${d}2\" | base64 -d > \"${d}1\" 2>/dev/null || true; }\n")
        setup.append("mkdir -p \"${d}H/.config/bliss/banner\" 2>/dev/null || true\n")
        setup.append("for _rc in \"${d}H/.zshrc\" \"${d}H/.bashrc\"; do\n")
        setup.append("  [ -f \"${d}_rc\" ] || continue\n")
        setup.append("  awk '/banner\\.sh/ { if (b++) next } /appearance-apply\\.sh/ { if (a++) next } { print }' ")
            .append("\"${d}_rc\" > \"${d}_rc.bliss.${d}${d}\" 2>/dev/null && ")
            .append("mv -f \"${d}_rc.bliss.${d}${d}\" \"${d}_rc\" 2>/dev/null || ")
            .append("rm -f \"${d}_rc.bliss.${d}${d}\" 2>/dev/null || true\n")
        setup.append("  if ! grep -q 'bliss/banner.sh' \"${d}_rc\" 2>/dev/null; then\n")
        setup.append("    printf '\\n# Altaris launch sticker (random anime banner; opt out FLUX_NO_BANNER=1)\\n' ")
            .append(">> \"${d}_rc\" 2>/dev/null || true\n")
        setup.append("    printf '[ -r \"${d}HOME/.config/bliss/banner.sh\" ] && . \"${d}HOME/.config/bliss/banner.sh\"\\n' ")
            .append(">> \"${d}_rc\" 2>/dev/null || true\n")
        setup.append("  fi\n")
        setup.append("done\n")
        setup.append("if [ ! -f \"${d}H/.bashrc\" ]; then\n")
        setup.append("  printf '# Altaris bash profile\\n' > \"${d}H/.bashrc\" 2>/dev/null || true\n")
        setup.append("  printf '# Altaris launch sticker (random anime banner; opt out FLUX_NO_BANNER=1)\\n' ")
            .append(">> \"${d}H/.bashrc\" 2>/dev/null || true\n")
        setup.append("  printf '[ -r \"${d}HOME/.config/bliss/banner.sh\" ] && . \"${d}HOME/.config/bliss/banner.sh\"\\n' ")
            .append(">> \"${d}H/.bashrc\" 2>/dev/null || true\n")
        setup.append("fi\n")
        setup.append("if [ ! -f \"${d}H/.zprofile\" ]; then\n")
        setup.append("  printf '%s\\n' '[[ -o interactive ]] || { [ -f \"${d}HOME/.zshrc\" ] && . \"${d}HOME/.zshrc\"; }' ")
            .append("> \"${d}H/.zprofile\" 2>/dev/null || true\n")
        setup.append("fi\n")
        setup.append(extraSetup)
        setup.append("exit 0\n")
        val setupRes = RootShell.captureInChroot(
            setup.toString(), user = "root", chrootPath = chrootPath,
            timeoutMs = 60_000L, context = ctx
        )
        // Missing /home/flux prints the marker from the bail line.
        if (setupRes.stdout.contains(marker)) return true
        if (setupRes.exitCode != 0) return false

        // 2) content: banner.sh + .keep manifest + every active art (chunked).
        val sh = BannerAssets.bannerSh(ctx)
        val files = ArrayList<Pair<String, String>>(arts.size + 2)
        if (sh.isNotEmpty()) files.add("/home/flux/.config/bliss/banner.sh" to sh)
        if (arts.isNotEmpty()) {
            files.add(
                "/home/flux/.config/bliss/banner/.keep" to
                    arts.joinToString("\n", postfix = "\n") { it.name }
            )
        }
        for (art in arts) {
            files.add(
                "/home/flux/.config/bliss/banner/${art.name}" to withTrailingNewline(art.text)
            )
        }
        if (!ChrootStager.writeFiles(ctx, chrootPath, files)) return false

        // 3) finalize: prune stale arts via .keep, write rev, fix ownership.
        val fin = StringBuilder()
        fin.append("set +e\n")
        fin.append("H=/home/flux\n")
        fin.append("[ -d \"${d}H\" ] || { echo $marker; exit 0; }\n")
        fin.append("if [ -s \"${d}H/.config/bliss/banner/.keep\" ]; then\n")
        fin.append("  for _f in \"${d}H/.config/bliss/banner/\"*.txt; do\n")
        fin.append("    [ -f \"${d}_f\" ] || continue\n")
        fin.append("    grep -qxF \"${d}(basename \"${d}_f\")\" \"${d}H/.config/bliss/banner/.keep\" 2>/dev/null || rm -f \"${d}_f\" 2>/dev/null || true\n")
        fin.append("  done\n")
        fin.append("fi\n")
        fin.append("printf '%s\\n' '").append(rev).append("'")
            .append(" > \"${d}H/.config/bliss/banner/.banner-rev\" 2>/dev/null || true\n")
        fin.append("chown -R --reference=\"${d}H\" \"${d}H/.config/bliss\" 2>/dev/null || true\n")
        fin.append("chown --reference=\"${d}H\" \"${d}H/.zshrc\" \"${d}H/.bashrc\" ")
            .append("\"${d}H/.bash_profile\" \"${d}H/.zprofile\" 2>/dev/null || true\n")
        fin.append("echo $marker\n")
        val finRes = RootShell.captureInChroot(
            fin.toString(), user = "root", chrootPath = chrootPath,
            timeoutMs = 60_000L, context = ctx
        )
        return finRes.stdout.contains(marker)
    }

    /**
     * Synchronous best-effort push of the active sticker into every guest:
     * all proot rootfs homes, the host home, and (when root is available)
     * every chroot through [RootShell.captureInChroot].
     */
    fun pushAll(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        try {
            val containers = File(app.filesDir, "usr/var/lib/proot-distro/containers")
            containers.listFiles()?.forEach { container ->
                val home = File(container, "rootfs/home/flux")
                if (home.isDirectory) {
                    try {
                        writeIntoHome(app, home)
                        repairRcFiles(home)
                    } catch (e: Exception) {
                        Log.w(TAG, "proot banner push failed (${home.absolutePath}): ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "proot banner push failed: ${e.message}")
        }
        try {
            val hostHome = File(app.filesDir, "home")
            if (hostHome.isDirectory) {
                writeIntoHome(app, hostHome)
                repairRcFiles(hostHome)
            }
        } catch (e: Exception) {
            Log.w(TAG, "host banner push failed: ${e.message}")
        }
        try {
            if (!RootShell.isRootAvailable()) return
            val out = RootShell.capture(
                "ls -d /data/local/bliss/chroots/*/home/flux 2>/dev/null"
            )
            out.lineSequence()
                .map { it.trim() }
                .filter { it.endsWith("/home/flux") && it.length > "/home/flux".length }
                .forEach { path ->
                    val chroot = path.removeSuffix("/home/flux")
                    try {
                        if (pushChroot(app, chroot)) {
                            Log.i(TAG, "Banner pushed to chroot $chroot")
                        } else {
                            Log.w(TAG, "chroot banner push incomplete ($chroot)")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "chroot banner push failed ($chroot): ${e.message}")
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "chroot banner push failed: ${e.message}")
        }
    }

    /** Push in the background (daemon thread, never throws). */
    fun refreshAsync(ctx: Context) {
        try {
            val app = ctx.applicationContext ?: ctx
            Thread {
                try {
                    pushAll(app)
                } catch (_: Exception) {
                }
            }.apply {
                name = "bliss-banner-push"
                isDaemon = true
            }.start()
        } catch (e: Exception) {
            Log.w(TAG, "banner refresh thread failed: ${e.message}")
        }
    }

    /**
     * `awk '/banner\.sh/ { if (b++) next } /appearance-apply\.sh/ { if (a++)
     * next } { print }'` equivalent: keep only the first line referencing
     * `banner.sh` and the first referencing `appearance-apply.sh`.
     */
    private fun dedupeRc(text: String): String {
        val lines = text.split("\n")
        val out = ArrayList<String>(lines.size)
        var sawBanner = false
        var sawAppearance = false
        for (line in lines) {
            if (line.contains("banner.sh")) {
                if (sawBanner) continue
                sawBanner = true
            }
            if (line.contains("appearance-apply.sh")) {
                if (sawAppearance) continue
                sawAppearance = true
            }
            out.add(line)
        }
        return out.joinToString("\n")
    }

    private fun repairRcFile(rc: File) {
        if (!rc.isFile || !rc.canWrite()) return
        val text = try {
            rc.readText()
        } catch (_: Exception) {
            return
        }
        var out = dedupeRc(text)
        if (out.lines().none { it.contains(".config/bliss/banner.sh") }) {
            out += "\n" + STICKER_COMMENT + "\n" + STICKER_LINE + "\n"
        }
        if (out == text) return
        try {
            rc.writeText(out)
            Log.i(TAG, "Wired launch sticker in ${rc.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "rc write failed: ${e.message}")
        }
    }

    private fun ensureBashProfile(home: File) {
        if (!home.isDirectory) return
        val profile = File(home, ".bash_profile")
        if (!profile.exists()) {
            if (!home.canWrite()) return
            try {
                profile.writeText(
                    "[ -f \"\$HOME/.profile\" ] && . \"\$HOME/.profile\"\n" + BASHRC_LINE + "\n"
                )
                Log.i(TAG, "Created .bash_profile at ${profile.absolutePath}")
            } catch (e: Exception) {
                Log.w(TAG, "bash_profile create failed: ${e.message}")
            }
            return
        }
        if (!profile.isFile || !profile.canWrite()) return
        val text = try {
            profile.readText()
        } catch (_: Exception) {
            return
        }
        if (text.contains(".bashrc")) return
        try {
            profile.writeText(text + "\n" + BASHRC_LINE + "\n")
            Log.i(TAG, "Wired .bashrc in ${profile.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "bash_profile write failed: ${e.message}")
        }
    }

    private fun withTrailingNewline(text: String): String =
        if (text.endsWith("\n")) text else text + "\n"

    private fun sameText(f: File, want: String): Boolean = try {
        f.isFile && f.readText() == want
    } catch (_: Exception) {
        false
    }

    private fun b64(text: String): String =
        Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
}
