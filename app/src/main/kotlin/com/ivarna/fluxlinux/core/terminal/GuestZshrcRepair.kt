package com.ivarna.fluxlinux.core.terminal

import android.content.Context
import android.util.Base64
import android.util.Log
import com.ivarna.fluxlinux.core.banner.BannerArtWriter
import com.ivarna.fluxlinux.core.banner.BannerAssets
import com.ivarna.fluxlinux.core.root.ChrootPaths
import com.ivarna.fluxlinux.core.root.RootShell
import java.io.File

/**
 * One-shot repair for guest `~/.zshrc` that hard-sources missing oh-my-zsh,
 * invokes missing `pokemon-colorscripts`, still uses the old colorful
 * `agnosterzak` theme, or lacks the Altaris launch sticker (random colored
 * anime ASCII banner). Keeps the same optimized profile shape as
 * `setup_customization_debian.sh` / Alpine customization, but guards every
 * optional tool.
 *
 * Proot rootfs lives under the app filesDir (writable) → direct file writes.
 * Chroot is under `/data/local/bliss` (root-only, SELinux) → the same repair
 * runs **inside the chroot as root** through the staged helper
 * ([RootShell.captureInChroot]); it is skipped silently when root is not
 * available, never fatal.
 *
 * Also **creates** a missing `.zshrc` when the home dir is writable (Alpine
 * installs that never wrote the Altaris profile).
 */
object GuestZshrcRepair {

    private const val TAG = "GuestZshrcRepair"

    /**
     * Local repair revision. The cache key carries it (on top of
     * [GuestRepairCache.zshrcKey]) so the banner/theme sweep runs exactly once
     * on existing installs even though the shared ZSHRC_VERSION is unchanged.
     * Bump when [DEFENSIVE_ZSHRC] / banner content changes again.
     * r12: system-wide plugin paths + first-run package fallback so Parrot,
     * Fedora, Void and other families match Alpine/Debian.
     */
    private const val REPAIR_REVISION = 13

    /** Marker echoed by the root chroot repair payload on success. */
    internal const val CHROOT_OK_MARKER = "FOLIO_ZSH_OK"

    /** Substring identifying the [BLISS_SHELL_BLOCK] marker pair. */
    private const val SHELL_MARKER = "altaris shell integration v2"

    /**
     * Marker-guarded zsh shell integration: live syntax coloring
     * (zsh-syntax-highlighting: green resolvable / red unknown) and inline
     * history ghost text (zsh-autosuggestions).
     *
     * Loaded via OMZ's `plugins=(git zsh-autosuggestions zsh-syntax-highlighting)`
     * when OMZ is present (syntax-highlighting last); the direct guarded sources
     * are the fallback for bare/custom profiles. `ZSH_AUTOSUGGEST_HIGHLIGHT_STYLE`
     * is set both before OMZ (plugin load time) and here (idempotent).
     *
     * Right arrow / End already accept the suggestion by default
     * (`ZSH_AUTOSUGGEST_ACCEPT_WIDGETS=(forward-char end-of-line ...)`, verified
     * against zsh-autosuggestions v0.7.1 sources + README), so no bindkey is set.
     */
    internal val BLISS_SHELL_BLOCK = """
# >>> altaris shell integration v2 >>>
# One profile for every distro: inline history ghost text + live green/red
# command validation. OMZ wins when present; system packages and the vendored
# /usr/local/share/altaris/zsh tree are the offline fallbacks.
# Right arrow / End accept the ghost suggestion (plugin default, no bindkey).
ZSH_AUTOSUGGEST_HIGHLIGHT_STYLE='fg=244'
_altaris_source() {
  [ -r "${'$'}1" ] || return 0
  case "${'$'}1" in
    *zsh-autosuggestions*) (( ${'$'}{+functions[_zsh_autosuggest_start]} )) && return 0 ;;
    *zsh-syntax-highlighting*) (( ${'$'}{+ZSH_HIGHLIGHT_VERSION} )) && return 0 ;;
  esac
  source "${'$'}1"
}
for _altaris_p in \
  "${'$'}{ZSH:-${'$'}HOME/.oh-my-zsh}/custom/plugins/zsh-autosuggestions/zsh-autosuggestions.zsh" \
  "${'$'}HOME/.oh-my-zsh/custom/plugins/zsh-autosuggestions/zsh-autosuggestions.zsh" \
  /usr/share/zsh-autosuggestions/zsh-autosuggestions.zsh \
  /usr/share/zsh/plugins/zsh-autosuggestions/zsh-autosuggestions.zsh \
  /usr/share/zsh/plugins/zsh-autosuggestions/zsh-autosuggestions.plugin.zsh \
  /usr/local/share/zsh-autosuggestions/zsh-autosuggestions.zsh \
  /usr/local/share/altaris/zsh/zsh-autosuggestions/zsh-autosuggestions.zsh; do
  _altaris_source "${'$'}_altaris_p"
done
for _altaris_p in \
  "${'$'}{ZSH:-${'$'}HOME/.oh-my-zsh}/custom/plugins/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh" \
  "${'$'}HOME/.oh-my-zsh/custom/plugins/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh" \
  /usr/share/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh \
  /usr/share/zsh/plugins/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh \
  /usr/share/zsh-syntax-highlighting/zsh-syntax-highlighting.plugin.zsh \
  /usr/local/share/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh \
  /usr/local/share/altaris/zsh/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh; do
  _altaris_source "${'$'}_altaris_p"
done
unset _altaris_p
unset -f _altaris_source 2>/dev/null || true
if [ -z "${'$'}{ZSH_THEME:-}" ] || [ "${'$'}{ZSH_THEME:-}" = "bliss" ]; then
  PROMPT='%F{79}altaris%f%F{244}@%m%f %F{110}%~%f %F{255}%#%f '
fi
# Existing installs: one bounded package attempt in the background, then the
# next shell loads the plugins. New installs get them during family setup.
if [ ! -r /usr/share/zsh-autosuggestions/zsh-autosuggestions.zsh ] \
    && [ ! -r "${'$'}HOME/.oh-my-zsh/custom/plugins/zsh-autosuggestions/zsh-autosuggestions.zsh" ] \
    && [ ! -e "${'$'}HOME/.config/altaris/.shell-bootstrap" ]; then
  mkdir -p "${'$'}HOME/.config/altaris" 2>/dev/null && touch "${'$'}HOME/.config/altaris/.shell-bootstrap" 2>/dev/null || true
  ( if command -v apt-get >/dev/null 2>&1; then
      sudo env DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends zsh-autosuggestions zsh-syntax-highlighting
    elif command -v dnf >/dev/null 2>&1; then
      sudo dnf install -y zsh-autosuggestions zsh-syntax-highlighting
    elif command -v pacman >/dev/null 2>&1; then
      sudo pacman -S --noconfirm --needed zsh-autosuggestions zsh-syntax-highlighting
    elif command -v apk >/dev/null 2>&1; then
      sudo apk add --no-cache zsh-autosuggestions zsh-syntax-highlighting
    elif command -v zypper >/dev/null 2>&1; then
      sudo zypper --non-interactive install zsh-autosuggestions zsh-syntax-highlighting
    elif command -v xbps-install >/dev/null 2>&1; then
      sudo xbps-install -y zsh-autosuggestions zsh-syntax-highlighting
    fi ) >/dev/null 2>&1 &
fi
# <<< altaris shell integration v2 <<<
""".trimStart()

    /** Defensive zshrc — mirrors setup_customization_debian.sh (post-fix). */
    private val DEFENSIVE_ZSHRC = """
# Guest PATH only — never inherit host PREFIX/bin (nested proot glue errors).
export PATH="${'$'}HOME/.local/bin:/opt/nodejs/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

# Generate UTF-8 first so theme / pokemon always render.
if [ -x /usr/local/sbin/bliss-ensure-locale ] && command -v sudo >/dev/null 2>&1; then
  sudo /usr/local/sbin/bliss-ensure-locale 2>/dev/null || true
fi
_have=${'$'}(locale -a 2>/dev/null || true)
_pick=""
for _c in en_US.UTF-8 en_US.utf8 C.UTF-8 C.utf8; do
  echo "${'$'}_have" | grep -qxFi "${'$'}_c" && { _pick="${'$'}_c"; break; }
done
if [ -n "${'$'}_pick" ]; then
  export LANG="${'$'}_pick" LC_ALL="${'$'}_pick"
else
  unset LC_ALL
  export LANG=C
  echo "Altaris: WARNING: no UTF-8 locale in locale -a; zsh staying LANG=C" >&2
fi
unset _have _c _pick
export PYTHONIOENCODING=UTF-8

# Host PROOT_TMP_DIR is not writable as guest uid; use guest /tmp.
unset PROOT_TMP_DIR
export TMPDIR="${'$'}{TMPDIR:-/tmp}"

# Fix XDG_RUNTIME_DIR (not set in PRoot/chroot — no systemd-logind)
export XDG_RUNTIME_DIR="${'$'}{XDG_RUNTIME_DIR:-/tmp}"

# proot does not implement tcsetpgrp — zsh job control would ENOSYS and the
# shell can get SIGTTIN-killed. Disable MONITOR (job control) for the guest.
setopt no_monitor

# Launch sticker + `altaris@<id>` header: ~/.config/bliss/banner.sh (sourced at the end).
# pokemon-colorscripts stays installed but no longer auto-prints (old style).

export ZSH="${'$'}{ZSH:-${'$'}HOME/.oh-my-zsh}"
if [ -f "${'$'}ZSH/oh-my-zsh.sh" ]; then
  ZSH_THEME="bliss"
  DISABLE_UPDATE_PROMPT=true
  DISABLE_AUTO_UPDATE=true
  ZSH_DISABLE_COMPFIX=true
  # Subtle ghost text for zsh-autosuggestions (set before OMZ loads it).
  ZSH_AUTOSUGGEST_HIGHLIGHT_STYLE='fg=240'
  plugins=(git zsh-autosuggestions zsh-syntax-highlighting)
  source "${'$'}ZSH/oh-my-zsh.sh"
fi

# Altaris launch sticker (random truecolor anime banner; opt out FLUX_NO_BANNER=1)
[ -r "${'$'}HOME/.config/bliss/banner.sh" ] && . "${'$'}HOME/.config/bliss/banner.sh"

# Altaris Linux appearance (wallpaper/colors/terminal; hash-guarded inside).
[ -r "${'$'}HOME/.config/bliss/appearance-apply.sh" ] && sh "${'$'}HOME/.config/bliss/appearance-apply.sh" >/dev/null 2>&1 || true
""".trimStart() + "\n" + BLISS_SHELL_BLOCK

    /**
     * Patch or create guest flux `.zshrc` when needed.
     * Safe to call on every session open — no-ops when already fixed.
     *
     * @param method `proot` | `chroot`
     * @param distroId card id (`debian`, `alpine`, `alpine_chroot`, …); resolves
     *   the correct rootfs / chroot path.
     */
    private val ALPINE_APK_WRAPPER = """

# Alpine/Chimera: package manager needs root. NOPASSWD sudo is configured for flux.
if command -v apk >/dev/null 2>&1 && command -v sudo >/dev/null 2>&1; then
  apk() { command sudo apk "${'$'}@"; }
fi
""".trimStart()

    private val GLIBC_PM_WRAPPER = """

# Guest package managers need root. NOPASSWD sudo is configured for flux.
if command -v sudo >/dev/null 2>&1; then
  if command -v apt-get >/dev/null 2>&1; then
    apt-get() { command sudo apt-get "${'$'}@"; }
  fi
  if command -v apt >/dev/null 2>&1; then
    apt() { command sudo apt "${'$'}@"; }
  fi
  if command -v pacman >/dev/null 2>&1; then
    pacman() { command sudo pacman "${'$'}@"; }
  fi
  if command -v dnf >/dev/null 2>&1; then
    dnf() { command sudo dnf "${'$'}@"; }
  fi
  if command -v dnf5 >/dev/null 2>&1; then
    dnf5() { command sudo dnf5 "${'$'}@"; }
  fi
  if command -v xbps-install >/dev/null 2>&1; then
    xbps-install() { command sudo xbps-install "${'$'}@"; }
  fi
  if command -v zypper >/dev/null 2>&1; then
    zypper() { command sudo zypper "${'$'}@"; }
  fi
fi
""".trimStart()

    /** bliss.zsh-theme content (monochrome prompt; replaces agnosterzak). */
    internal val FOLIO_THEME = """
# altaris.zsh-theme — Altaris minimal prompt.
# White/gray on black only; ASCII-safe (no powerline glyphs, no ANSI colors 31-44).
# Segments:  time  altaris  working-dir  input-char
PROMPT='%F{240}%D{%H:%M}%f %F{79}altaris%f %F{250}%~%f %F{255}%#%f '
PROMPT2='%F{240}> %f'
""".trimStart()

    /** .zshrc tail that sources the sticker (idempotent append target). */
    internal val BANNER_SOURCE_BLOCK = """
# Altaris launch sticker (random truecolor anime banner; opt out FLUX_NO_BANNER=1)
[ -r "${'$'}HOME/.config/bliss/banner.sh" ] && . "${'$'}HOME/.config/bliss/banner.sh"
""".trimStart()

    /**
     * .zshrc tail that applies the staged Altaris Linux appearance (idempotent
     * append target for existing profiles; the guest script hash-guards itself).
     */
    internal val APPEARANCE_SOURCE_BLOCK = """
# Altaris Linux appearance (wallpaper/colors/terminal; hash-guarded inside).
[ -r "${'$'}HOME/.config/bliss/appearance-apply.sh" ] && sh "${'$'}HOME/.config/bliss/appearance-apply.sh" >/dev/null 2>&1 || true
""".trimStart()

    fun repairIfNeeded(ctx: Context, method: String, distroId: String? = null) {
        // Versioned cache (proot-opt-01): after one successful verification pass
        // per distro, later session opens skip the file reads + regex sweeps.
        // The local revision suffix re-runs the sweep once for installs that
        // already completed the v2 pass (no banner / old theme back then).
        val cacheKey = GuestRepairCache.zshrcKey(distroId ?: method) + "_r$REPAIR_REVISION"
        val prefs = ctx.getSharedPreferences(GuestRepairCache.PREFS, Context.MODE_PRIVATE)
        if (method == "chroot") {
            // Root-only tree (SELinux) → the repair runs as root in the chroot.
            // Cached after the first root pass; the login helper keeps patching
            // the profile afterwards.
            if (prefs.getBoolean(cacheKey, false)) return
            if (repairChrootAsRoot(ctx, distroId)) {
                prefs.edit().putBoolean(cacheKey, true).apply()
            }
            return
        }
        val zshrc = resolveZshrc(ctx, method, distroId) ?: return
        if (prefs.getBoolean(cacheKey, false)) {
            // Banner freshness: quality / NSFW changes (and the art
            // revision) must reach existing guests even when the profile
            // pass is cached. O(1) when unchanged: rev file first, only
            // then `banner.sh`.
            zshrc.parentFile?.let { home ->
                if (bannerRefreshNeeded(ctx, home)) {
                    BannerArtWriter.writeIntoHome(ctx, home)
                    BannerArtWriter.repairRcFiles(home)
                }
            }
            // Cheap re-verify so a reinstall / wiped profile still gets fixed:
            // the banner source line / shell-integration marker are the hooks.
            val wired = try {
                if (!zshrc.isFile) false
                else {
                    val text = zshrc.readText()
                    text.contains("banner.sh") && text.contains("appearance-apply.sh") &&
                        text.contains(SHELL_MARKER)
                }
            } catch (_: Exception) {
                false
            }
            if (wired) return
        }
        val rootfs = zshrc.parentFile?.parentFile?.parentFile // home/flux/.zshrc → rootfs
        val isApkGuest = isApkGuest(distroId)
        val needsPmWrap = !isApkGuest && isGlibcGuest(distroId)
        try {
            if (zshrc.isFile) {
                val text = zshrc.readText()
                if (needsRepair(text)) {
                    if (!zshrc.canWrite()) {
                        Log.w(TAG, "zshrc needs repair but not writable: ${zshrc.absolutePath}")
                    } else {
                        zshrc.writeText(profileFor(isApkGuest, needsPmWrap))
                        Log.i(TAG, "Repaired defensive .zshrc at ${zshrc.absolutePath}")
                    }
                } else if (isApkGuest && !text.contains("apk() {") && zshrc.canWrite()) {
                    zshrc.appendText("\n$ALPINE_APK_WRAPPER")
                    Log.i(TAG, "Appended apk() wrapper to ${zshrc.absolutePath}")
                } else if (needsPmWrap && !text.contains("dnf() {") &&
                    !text.contains("xbps-install() {") &&
                    !text.contains("zypper() {") &&
                    !text.contains("apt-get() {") &&
                    !text.contains("pacman() {") &&
                    zshrc.canWrite()
                ) {
                    zshrc.appendText("\n$GLIBC_PM_WRAPPER")
                    Log.i(TAG, "Appended glibc PM wrappers to ${zshrc.absolutePath}")
                }
                // Gentle path for otherwise-healthy profiles: append the Altaris
                // Linux appearance trigger only (the guest script self-guards).
                if (!needsRepair(text) && !text.contains("appearance-apply.sh") &&
                    zshrc.canWrite()
                ) {
                    zshrc.appendText("\n$APPEARANCE_SOURCE_BLOCK")
                    Log.i(TAG, "Appended appearance trigger to ${zshrc.absolutePath}")
                }
                // zsh shell integration (syntax coloring + autosuggestions):
                // append once to healthy custom profiles; never rewrite them.
                if (!needsRepair(text) && !text.contains(SHELL_MARKER) &&
                    zshrc.canWrite()
                ) {
                    zshrc.appendText("\n$BLISS_SHELL_BLOCK")
                    Log.i(TAG, "Appended shell integration block to ${zshrc.absolutePath}")
                }
            } else {
                // Create missing Altaris profile (Alpine used to never write .zshrc)
                val home = zshrc.parentFile ?: return
                if (!home.isDirectory) return
                if (!home.canWrite() && !zshrc.canWrite()) {
                    Log.w(TAG, "cannot create .zshrc (home not writable): ${home.absolutePath}")
                } else {
                    zshrc.writeText(profileFor(isApkGuest, needsPmWrap))
                    Log.i(TAG, "Created defensive .zshrc at ${zshrc.absolutePath}")
                }
            }
            // Monochrome folio prompt theme + random anime launch sticker.
            zshrc.parentFile?.let { home ->
                removeLegacyThemes(home)
                writeFolioTheme(home)
                writeBannerFiles(ctx, home)
            }
            ensureZprofile(zshrc.parentFile)
            // Prefer zsh login shell when binary exists (Alpine often left /bin/bash)
            if (rootfs != null) {
                ensureFluxLoginShellZsh(rootfs)
                writeLocaleProfile(rootfs)
                writeEnsureLocaleScript(rootfs)
                writeColorProfile(rootfs)
                writeBashPrompt(zshrc.parentFile)
            }
            // Cache only after a completed pass (exception above leaves it unset).
            prefs.edit().putBoolean(cacheKey, true).apply()
        } catch (e: Exception) {
            Log.w(TAG, "zshrc repair failed: ${e.message}")
        }
    }

    /**
     * Old colorful OMZ theme shipped by earlier Altaris builds — remove the file
     * (the `.zshrc` rewrite/sed switches the reference to `bliss`).
     */
    internal fun removeLegacyThemes(home: File?) {
        if (home == null) return
        try {
            File(home, ".oh-my-zsh/custom/themes/agnosterzak.zsh-theme").delete()
        } catch (e: Exception) {
            Log.w(TAG, "legacy theme remove failed: ${e.message}")
        }
    }

    /** Write the monochrome `bliss` prompt theme (used by ZSH_THEME=folio). */
    internal fun writeFolioTheme(home: File?) {
        if (home == null || !File(home, ".oh-my-zsh").isDirectory) return
        val theme = File(home, ".oh-my-zsh/custom/themes/bliss.zsh-theme")
        try {
            theme.parentFile?.mkdirs()
            if (theme.isFile && !theme.canWrite()) return
            theme.writeText(FOLIO_THEME)
            Log.i(TAG, "Wrote bliss prompt theme at ${theme.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "bliss theme write failed: ${e.message}")
        }
    }

    /**
     * Install the launch sticker in one guest home: art designs +
     * `banner.sh` + rc wiring. Content, quality and NSFW selection live in
     * [BannerArtWriter] / [BannerAssets].
     */
    internal fun writeBannerFiles(ctx: Context, home: File?) {
        if (home == null || !home.isDirectory) return
        BannerArtWriter.writeIntoHome(ctx, home)
        BannerArtWriter.repairRcFiles(home)
    }

    /**
     * Cheap banner freshness probe for the cached repair pass: compare
     * `.banner-rev` first (one tiny file read), only then `banner.sh`.
     * Never throws.
     */
    private fun bannerRefreshNeeded(ctx: Context, home: File): Boolean {
        return try {
            val rev = File(home, ".config/bliss/banner/.banner-rev")
            val wantRev = BannerAssets.active(ctx).first
            if (!rev.isFile || rev.readText().trim() != wantRev) {
                true
            } else {
                val wantSh = BannerAssets.bannerSh(ctx)
                val sh = File(home, ".config/bliss/banner.sh")
                wantSh.isNotEmpty() && (!sh.isFile || sh.readText() != wantSh)
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Chroot variant: `/data/local/bliss` is root-only, so the repairs run
     * inside the chroot as root. [BannerArtWriter.pushChroot] chunks everything
     * (a single `su -c` argument is capped ~128 KB by the kernel — the old
     * one-shot payload silently failed for full art sets) and returns true when
     * the finalize marker came back.
     */
    private fun repairChrootAsRoot(ctx: Context, distroId: String?): Boolean {
        val root = if (distroId != null) {
            ChrootPaths.pathForDistro(distroId)
        } else {
            ChrootPaths.CHROOT_PATH
        }
        return try {
            val ok = BannerArtWriter.pushChroot(ctx, root, chrootExtraRepairs())
            if (ok) {
                Log.i(TAG, "Repaired chroot guest profile at $root")
            } else {
                Log.w(TAG, "chroot repair incomplete at $root")
            }
            ok
        } catch (e: Exception) {
            Log.w(TAG, "chroot zshrc repair failed: ${e.message}")
            false
        }
    }

    private fun b64(text: String): String =
        Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    /**
     * GZR-specific non-banner chroot repairs, spliced into the (small) setup
     * step of [BannerArtWriter.pushChroot] where `H` and `_put` are defined.
     * Interactive file content (theme, defensive `.zshrc`) is small enough to
     * stay inline; the art set is staged separately.
     */
    internal fun chrootExtraRepairs(): String {
        val sb = StringBuilder()
        sb.append("rm -f \"\$H/.oh-my-zsh/custom/themes/folio.zsh-theme\" 2>/dev/null || true\n")
        sb.append("rm -f \"\$H/.local/share/applications/folio-\"*.desktop 2>/dev/null || true\n")
        sb.append("[ -d \"\$H/.config/bliss\" ] && rm -rf \"\$H/.config/folio\" 2>/dev/null || true\n")
        sb.append("if [ ! -f \"\$H/.zshrc\" ]; then\n")
        sb.append("  _put \"\$H/.zshrc\" '").append(b64(DEFENSIVE_ZSHRC)).append("'\n")
        sb.append("fi\n")
        sb.append("if [ -d \"\$H/.oh-my-zsh\" ]; then\n")
        sb.append("  mkdir -p \"\$H/.oh-my-zsh/custom/themes\" 2>/dev/null || true\n")
        sb.append("  rm -f \"\$H/.oh-my-zsh/custom/themes/agnosterzak.zsh-theme\" 2>/dev/null || true\n")
        sb.append("  _put \"\$H/.oh-my-zsh/custom/themes/bliss.zsh-theme\" '")
            .append(b64(FOLIO_THEME)).append("'\n")
        sb.append("fi\n")
        sb.append("if [ -f \"\$H/.zshrc\" ]; then\n")
        sb.append("  grep -q 'ZSH_THEME=\"agnosterzak\"' \"\$H/.zshrc\" 2>/dev/null && ")
            .append("sed -i 's|ZSH_THEME=\"agnosterzak\"|ZSH_THEME=\"bliss\"|' \"\$H/.zshrc\" 2>/dev/null || true\n")
        sb.append("  sed -i 's|/.config/folio/|/.config/bliss/|g' \"\$H/.zshrc\" 2>/dev/null || true\n")
        sb.append("fi\n")
        // zsh shell integration (syntax coloring + autosuggestions): existing
        // chroot profiles get the marker block appended once, like proot.
        sb.append("if [ -f \"\$H/.zshrc\" ] && ! grep -q '").append(SHELL_MARKER)
            .append("' \"\$H/.zshrc\" 2>/dev/null; then\n")
        sb.append("  _put \"\$H/.zshrc.bliss-block\" '").append(b64(BLISS_SHELL_BLOCK)).append("'\n")
        sb.append("  printf '\\n' >> \"\$H/.zshrc\" 2>/dev/null || true\n")
        sb.append("  cat \"\$H/.zshrc.bliss-block\" >> \"\$H/.zshrc\" 2>/dev/null || true\n")
        sb.append("  rm -f \"\$H/.zshrc.bliss-block\" 2>/dev/null || true\n")
        sb.append("fi\n")
        // Debian's stock .bashrc resets PS1 after /etc/profile.d, so append the
        // Altaris prompt at the very end of the interactive bash profile too.
        sb.append("if [ -f \"\$H/.bashrc\" ] && ! grep -q 'altaris bash prompt v1' \"\$H/.bashrc\" 2>/dev/null; then\n")
        sb.append("  _put \"\$H/.bashrc.altaris-prompt\" '").append(b64(BASH_PROMPT_BLOCK)).append("'\n")
        sb.append("  printf '\\n' >> \"\$H/.bashrc\" 2>/dev/null || true\n")
        sb.append("  cat \"\$H/.bashrc.altaris-prompt\" >> \"\$H/.bashrc\" 2>/dev/null || true\n")
        sb.append("  rm -f \"\$H/.bashrc.altaris-prompt\" 2>/dev/null || true\n")
        sb.append("fi\n")
        sb.append("chown --reference=\"\$H\" \"\$H/.oh-my-zsh/custom/themes/bliss.zsh-theme\" 2>/dev/null || true\n")
        sb.append("chown --reference=\"\$H\" \"\$H/.zshrc\" \"\$H/.bashrc\" 2>/dev/null || true\n")
        return sb.toString()
    }

    internal val LOCALE_PROFILE = """
_have=${'$'}(locale -a 2>/dev/null || true)
_pick=""
for _c in en_US.UTF-8 en_US.utf8 C.UTF-8 C.utf8; do
  echo "${'$'}_have" | grep -qxFi "${'$'}_c" && { _pick="${'$'}_c"; break; }
done
if [ -n "${'$'}_pick" ]; then
  export LANG="${'$'}_pick" LC_ALL="${'$'}_pick"
else
  unset LC_ALL
  export LANG=C
  echo "Altaris: WARNING: no UTF-8 locale in locale -a; zsh staying LANG=C" >&2
fi
unset _have _c _pick
""".trimStart()

    internal val ENSURE_LOCALE_SH = """
#!/bin/sh
# Generate directory locales when locale-archive is empty (Manjaro/Arch proot).
[ "${'$'}(id -u)" = 0 ] || exit 1
if locale -a 2>/dev/null | grep -qxFi en_US.utf8 \
    || locale -a 2>/dev/null | grep -qxFi en_US.UTF-8; then
  printf 'LANG=en_US.UTF-8\n' > /etc/locale.conf
  exit 0
fi
if command -v pacman >/dev/null 2>&1; then
  command -v gzip >/dev/null 2>&1 || pacman -S --noconfirm gzip >/dev/null 2>&1 || true
  if [ ! -f /usr/share/i18n/locales/en_US ] \
      || { [ ! -f /usr/share/i18n/charmaps/UTF-8 ] \
           && [ ! -f /usr/share/i18n/charmaps/UTF-8.gz ]; }; then
    pacman -S --noconfirm glibc >/dev/null 2>&1 || true
  fi
fi
command -v localedef >/dev/null 2>&1 || exit 1
mkdir -p /usr/lib/locale
_map=/tmp/flux-UTF-8
if [ -f /usr/share/i18n/charmaps/UTF-8.gz ] && command -v gzip >/dev/null 2>&1; then
  gzip -dc /usr/share/i18n/charmaps/UTF-8.gz > "${'$'}_map" || true
elif [ -f /usr/share/i18n/charmaps/UTF-8 ]; then
  cp -f /usr/share/i18n/charmaps/UTF-8 "${'$'}_map" || true
fi
if [ -s "${'$'}_map" ]; then
  localedef --no-archive -c -i en_US -f "${'$'}_map" /usr/lib/locale/en_US.utf8 || true
  localedef --no-archive -c -i POSIX -f "${'$'}_map" /usr/lib/locale/C.utf8 || true
else
  localedef --no-archive -c -i en_US -f UTF-8 /usr/lib/locale/en_US.utf8 || true
fi
rm -f "${'$'}_map"
if locale -a 2>/dev/null | grep -qiE 'en_US\.(utf8|UTF-8)'; then
  printf 'LANG=en_US.UTF-8\n' > /etc/locale.conf
  exit 0
fi
if locale -a 2>/dev/null | grep -qiE 'C\.(utf8|UTF-8)'; then
  printf 'LANG=C.UTF-8\n' > /etc/locale.conf
  exit 0
fi
echo "Altaris: ERROR: no UTF-8 locale after generation (locale -a has neither en_US.utf8 nor C.utf8)" >&2
exit 1
""".trimStart()

    internal fun writeEnsureLocaleScript(rootfs: File) {
        val dest = File(rootfs, "usr/local/sbin/bliss-ensure-locale")
        try {
            dest.parentFile?.mkdirs()
            if (dest.isFile && !dest.canWrite()) return
            dest.writeText(ENSURE_LOCALE_SH)
            dest.setExecutable(true, false)
            Log.i(TAG, "Wrote ${dest.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "bliss-ensure-locale write failed: ${e.message}")
        }
    }

    /**
     * Colors for guests that never see our zsh theme: bash login shells
     * (Alpine without zsh) get a green user@host / blue dir prompt that turns
     * the marker red after a failed command, plus `ls --color`.
     * ash/busybox-only guests keep their plain prompt (escapes differ there).
     */
    /** Bash login prompt: Altaris identity + red marker after a failed command. */
    internal val BASH_PROMPT_BLOCK = """
# >>> altaris bash prompt v1 >>>
if [ -n "${'$'}BASH_VERSION" ] && [ -n "${'$'}PS1" ]; then
  _ALTARIS_PS1_OK='\[\e[38;5;79m\]altaris@\h\[\e[0m\]:\[\e[38;5;110m\]\w\[\e[0m\]\$ '
  _ALTARIS_PS1_FAIL='\[\e[38;5;79m\]altaris@\h\[\e[0m\]:\[\e[38;5;110m\]\w\[\e[0m\]\[\e[38;5;203m\]\$\[\e[0m\] '
  PROMPT_COMMAND='if [ "${'$'}?" = 0 ]; then PS1="${'$'}_ALTARIS_PS1_OK"; else PS1="${'$'}_ALTARIS_PS1_FAIL"; fi'
  PS1="${'$'}_ALTARIS_PS1_OK"
fi
# <<< altaris bash prompt v1 <<<
""".trimStart()

    internal val COLOR_PROFILE = """
# Altaris colors (bash): green user@host, blue dir; red marker on failure.
if [ -n "${'$'}BASH_VERSION" ] && [ -n "${'$'}PS1" ]; then
  alias ls='ls --color=auto' 2>/dev/null
  BLISS_PS1_OK='\[\e[38;5;79m\]altaris@\h\[\e[0m\]:\[\e[38;5;110m\]\w\[\e[0m\]\$ '
  BLISS_PS1_FAIL='\[\e[38;5;79m\]altaris@\h\[\e[0m\]:\[\e[38;5;110m\]\w\[\e[0m\]\[\e[38;5;203m\]\$\[\e[0m\] '
  PROMPT_COMMAND='[ "${'$'}?" = 0 ] && PS1="${'$'}BLISS_PS1_OK" || PS1="${'$'}BLISS_PS1_FAIL"'
  PS1="${'$'}BLISS_PS1_OK"
fi

# ls colors for every shell, ash included (busybox ls knows --color).
alias ls='ls --color=auto' 2>/dev/null
""".trimStart()

    internal fun writeColorProfile(rootfs: File) {
        val dest = File(rootfs, "etc/profile.d/bliss-colors.sh")
        try {
            dest.parentFile?.mkdirs()
            if (dest.isFile && !dest.canWrite()) return
            dest.writeText(COLOR_PROFILE)
            Log.i(TAG, "Wrote color profile at ${'$'}{dest.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "color profile write failed: ${'$'}{e.message}")
        }
    }

    /** Keep Debian's stock .bashrc from overwriting the Altaris prompt. */
    internal fun writeBashPrompt(home: File?) {
        if (home == null || !home.isDirectory) return
        val bashrc = File(home, ".bashrc")
        try {
            val text = if (bashrc.isFile) bashrc.readText() else ""
            if (text.contains("altaris bash prompt v1")) return
            if (!bashrc.isFile) {
                if (!home.canWrite()) return
                bashrc.writeText("# Altaris interactive shell\n")
            }
            if (!bashrc.canWrite()) return
            bashrc.appendText("\n" + BASH_PROMPT_BLOCK)
        } catch (e: Exception) {
            Log.w(TAG, "bash prompt write failed: ${'$'}{e.message}")
        }
    }

    /** Existing guests: replace bliss-locale.sh that blindly exports C.UTF-8. */
    internal fun writeLocaleProfile(rootfs: File) {
        val dest = File(rootfs, "etc/profile.d/bliss-locale.sh")
        try {
            dest.parentFile?.mkdirs()
            if (dest.isFile && !dest.canWrite()) return
            dest.writeText(LOCALE_PROFILE)
            Log.i(TAG, "Wrote locale profile at ${dest.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "locale profile write failed: ${e.message}")
        }
    }

    private fun profileFor(isApkGuest: Boolean, glibcPm: Boolean = false): String = when {
        isApkGuest -> DEFENSIVE_ZSHRC + "\n" + ALPINE_APK_WRAPPER
        glibcPm -> DEFENSIVE_ZSHRC + "\n" + GLIBC_PM_WRAPPER
        else -> DEFENSIVE_ZSHRC
    }

    private fun isGlibcGuest(distroId: String?): Boolean {
        val name = resolveProotName(distroId)
        return name == "fedora" || name == "void" || name == "opensuse" ||
            name == "deepin" || name == "manjaro" ||
            name == "ubuntu" || name == "kali" || name == "parrot" ||
            name == "archlinux"
    }

    /** apk-based guests: Alpine (v2) + Chimera (v3, musl). */
    private fun isApkGuest(distroId: String?): Boolean {
        val name = resolveProotName(distroId)
        return name == "alpine" || name == "chimera" ||
            (distroId?.contains("alpine", ignoreCase = true) == true) ||
            (distroId?.contains("chimera", ignoreCase = true) == true)
    }

    /** Login zsh (`su -` / `zsh -l`) does not read `.zshrc` unless also interactive. */
    internal fun ensureZprofile(home: File?) {
        if (home == null || !home.isDirectory) return
        val zprofile = File(home, ".zprofile")
        if (zprofile.isFile) return
        if (!home.canWrite() && !zprofile.canWrite()) return
        try {
            zprofile.writeText(
                "[[ -o interactive ]] || { [ -f \"\$HOME/.zshrc\" ] && . \"\$HOME/.zshrc\"; }\n"
            )
            Log.i(TAG, "Created .zprofile at ${zprofile.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "zprofile create failed: ${e.message}")
        }
    }

    /**
     * If guest has zsh and passwd still points flux at bash/sh, set /bin/zsh.
     * Proot rootfs under app filesDir is usually writable; chroot may skip.
     */
    internal fun ensureFluxLoginShellZsh(rootfs: File) {
        val zshBin = when {
            File(rootfs, "bin/zsh").isFile -> "/bin/zsh"
            File(rootfs, "usr/bin/zsh").isFile -> "/usr/bin/zsh"
            else -> return
        }
        val passwd = File(rootfs, "etc/passwd")
        if (!passwd.isFile || !passwd.canWrite()) return
        try {
            val lines = passwd.readLines()
            var changed = false
            val out = lines.map { line ->
                if (!line.startsWith("flux:")) return@map line
                val parts = line.split(":").toMutableList()
                if (parts.size < 7) return@map line
                val current = parts[6]
                if (current == zshBin || current.endsWith("/zsh")) return@map line
                parts[6] = zshBin
                changed = true
                parts.joinToString(":")
            }
            if (changed) {
                passwd.writeText(out.joinToString("\n") + "\n")
                Log.i(TAG, "Set flux login shell to $zshBin in ${passwd.absolutePath}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "passwd shell update failed: ${e.message}")
        }
    }

    internal fun resolveZshrc(ctx: Context, method: String, distroId: String?): File? {
        return when (method) {
            "chroot" -> {
                val root = when {
                    distroId != null -> ChrootPaths.pathForDistro(distroId)
                    else -> ChrootPaths.CHROOT_PATH
                }
                File(root, "home/flux/.zshrc")
            }
            "proot" -> {
                val prootName = resolveProotName(distroId)
                File(
                    ctx.filesDir,
                    "usr/var/lib/proot-distro/containers/$prootName/rootfs/home/flux/.zshrc"
                )
            }
            else -> null
        }
    }

    internal fun resolveProotName(distroId: String?): String {
        if (distroId.isNullOrBlank()) return "debian"
        return when (distroId) {
            "alpine", "alpine_chroot" -> "alpine"
            "debian", "debian13_chroot", "debian_chroot" -> "debian"
            else -> distroId.removeSuffix("_chroot").ifBlank { "debian" }
        }
    }

    /** True when profile hard-sources omz or always runs pokemon without guards. */
    internal fun needsRepair(text: String): Boolean {
        // String checks avoid Regex `$` = EOL pitfalls with shell `$ZSH`.
        val hardSourceOmz = text.lineSequence().any { line ->
            val t = line.trim()
            (t.startsWith("source ") || t.startsWith("source\t")) &&
                t.contains("oh-my-zsh.sh")
        }
        val guardedOmz = text.contains("[ -f") && text.contains("oh-my-zsh.sh")
        val hardPokemon = text.contains("pokemon-colorscripts") &&
            !text.contains("command -v pokemon-colorscripts")
        // Old template: unconditional source without existence guard.
        if (hardSourceOmz && !guardedOmz) return true
        if (hardPokemon) return true
        // Host TMPDIR/PROOT_TMP_DIR leaked → nested proot glue errors.
        if (!text.contains("unset PROOT_TMP_DIR")) return true
        // Missing job-control guard → zsh dies with "can't set tty pgrp" under
        // proot (tcsetpgrp ENOSYS → SIGTTIN → session SIGKILL).
        if (!text.contains("setopt no_monitor")) return true
        // Hard LANG=en_US.UTF-8 without locale -a fallback spam-fails on Fedora.
        if (!text.contains("locale -a")) return true
        // Old fallback exported C.UTF-8 even when that locale is missing
        // (Manjaro ARM) → setlocale + "prompt_segment: character not in range".
        if (text.contains("export LANG=C.UTF-8") && !text.contains("grep -qxFi")) return true
        // Bare `fastfetch` lists every Android bind mount under proot.
        if (text.contains("|| fastfetch 2>/dev/null")) return true
        // Theme / pokemon must not be gated on LC_ALL (user-visible shell).
        if (text.contains("ZSH_THEME=\"\"")) return true
        if (text.contains("[ -n \"\${LC_ALL:-}\" ] && command -v pokemon-colorscripts")) return true
        // Colorful remote theme (pre-Altaris builds) → switch to the mono theme.
        if (text.contains("ZSH_THEME=\"agnosterzak\"")) return true
        // Older profiles never print ZSH_THEME=folio after OMZ loads.
        if (!text.contains("ZSH_THEME=\"bliss\"")) return true
        // Launch sticker (random anime banner + info block) not wired yet.
        if (!text.contains("banner.sh")) return true
        return false
    }
}
