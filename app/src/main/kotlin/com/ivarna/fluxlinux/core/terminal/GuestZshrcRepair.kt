package com.ivarna.fluxlinux.core.terminal

import android.content.Context
import android.util.Base64
import android.util.Log
import com.ivarna.fluxlinux.core.root.ChrootPaths
import com.ivarna.fluxlinux.core.root.RootShell
import java.io.File

/**
 * One-shot repair for guest `~/.zshrc` that hard-sources missing oh-my-zsh,
 * invokes missing `pokemon-colorscripts`, still uses the old colorful
 * `agnosterzak` theme, or lacks the Foila launch sticker (random colored
 * anime ASCII banner). Keeps the same optimized profile shape as
 * `setup_customization_debian.sh` / Alpine customization, but guards every
 * optional tool.
 *
 * Proot rootfs lives under the app filesDir (writable) → direct file writes.
 * Chroot is under `/data/local/folio` (root-only, SELinux) → the same repair
 * runs **inside the chroot as root** through the staged helper
 * ([RootShell.captureInChroot]); it is skipped silently when root is not
 * available, never fatal.
 *
 * Also **creates** a missing `.zshrc` when the home dir is writable (Alpine
 * installs that never wrote the Foila profile).
 */
object GuestZshrcRepair {

    private const val TAG = "GuestZshrcRepair"

    /**
     * Local repair revision. The cache key carries it (on top of
     * [GuestRepairCache.zshrcKey]) so the banner/theme sweep runs exactly once
     * on existing installs even though the shared ZSHRC_VERSION is unchanged.
     * Bump when [DEFENSIVE_ZSHRC] / banner content changes again.
     */
    private const val REPAIR_REVISION = 6

    /** Launch-sticker art revision (`~/.config/folio/banner/.banner-rev`).
     *  Bump with the guest scripts when the designs change again. */
    private const val BANNER_ART_REVISION = "3"

    /** Marker echoed by the root chroot repair payload on success. */
    private const val CHROOT_OK_MARKER = "FOLIO_ZSH_OK"

    /** Defensive zshrc — mirrors setup_customization_debian.sh (post-fix). */
    private val DEFENSIVE_ZSHRC = """
# Guest PATH only — never inherit host PREFIX/bin (nested proot glue errors).
export PATH="${'$'}HOME/.local/bin:/opt/nodejs/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

# Generate UTF-8 first so theme / pokemon always render.
if [ -x /usr/local/sbin/flux-ensure-locale ] && command -v sudo >/dev/null 2>&1; then
  sudo /usr/local/sbin/flux-ensure-locale 2>/dev/null || true
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
  echo "Foila: WARNING: no UTF-8 locale in locale -a; zsh staying LANG=C" >&2
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

# Launch sticker + `foila@<id>` header: ~/.config/folio/banner.sh (sourced at the end).
# pokemon-colorscripts stays installed but no longer auto-prints (old style).

export ZSH="${'$'}{ZSH:-${'$'}HOME/.oh-my-zsh}"
if [ -f "${'$'}ZSH/oh-my-zsh.sh" ]; then
  ZSH_THEME="folio"
  DISABLE_UPDATE_PROMPT=true
  DISABLE_AUTO_UPDATE=true
  ZSH_DISABLE_COMPFIX=true
  plugins=(git zsh-autosuggestions zsh-syntax-highlighting)
  source "${'$'}ZSH/oh-my-zsh.sh"
fi

# Foila launch sticker (random colored anime banner; opt out FLUX_NO_BANNER=1)
[ -r "${'$'}HOME/.config/folio/banner.sh" ] && . "${'$'}HOME/.config/folio/banner.sh"

# Foila Linux appearance (wallpaper/colors/terminal; hash-guarded inside).
[ -r "${'$'}HOME/.config/folio/appearance-apply.sh" ] && sh "${'$'}HOME/.config/folio/appearance-apply.sh" >/dev/null 2>&1 || true
""".trimStart()

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

    /** folio.zsh-theme content (monochrome prompt; replaces agnosterzak). */
    internal val FOLIO_THEME = """
# folio.zsh-theme — Foila's minimal monochrome prompt.
# White/gray on black only; ASCII-safe (no powerline glyphs, no ANSI colors 31-44).
# Segments:  time  foila  working-dir  input-char
PROMPT='%F{240}%D{%H:%M}%f %F{255}foila%f %F{250}%~%f %F{255}%#%f '
PROMPT2='%F{240}> %f'
""".trimStart()

    /**
     * Launch-sticker designs (256-color, original/generic anime-ish ASCII;
     * no emoji/astral chars).
     * Installed at `~/.config/folio/banner/<name>.txt`; one is picked at random
     * per interactive shell open by [BANNER_SH].
     */
    internal val BANNER_FILES: List<Pair<String, String>> = listOf(
        "01-catgirl.txt" to """
\033[38;5;225m       ╱╲              ╱╲\033[0m
\033[38;5;218m      ╱  ╲▄▄▄▄▄▄▄▄▄▄▄╱  ╲         nyah~\033[0m
\033[38;5;212m     ╭╯ ░░░░░░░░░░░░░░░ ╰╮\033[0m
\033[38;5;211m     │  ▄███▀▀   ▀▀███▄  │\033[0m
\033[38;5;207m     │ ▐█████▌   ▐█████▌ │\033[0m
\033[38;5;206m     │  ▀▀▀▀▀     ▀▀▀▀▀  │\033[0m
\033[38;5;205m     │       ╭─────╮       │   =^.^=\033[0m
\033[38;5;200m      ╲      ╰─────╯      ╱\033[0m
\033[38;5;199m       ╰──────╮ ╭──────╯\033[0m
\033[38;5;164m        ╰─────╯ ╰─────╯\033[0m
\033[38;5;163m         ║▀▀▀▀▀▀▀▀▀║\033[0m
\033[38;5;225m         ╰─────────╯\033[0m
""".trim('\n'),
        "02-mecha.txt" to """
\033[38;5;159m         _|_\033[0m
\033[38;5;123m        ╱   ╲                UNIT-07\033[0m
\033[38;5;117m    ╔═══╧═╤═╧═══╗\033[0m
\033[38;5;111m    ║ ▄▄▄▄▄▄▄▄▄▄▄ ║\033[0m
\033[38;5;75m    ║ █▀▀▀▀▀▀▀▀▀█ ║   ▐▌ ▐▌\033[0m
\033[38;5;69m    ║ █ ▀▀▀▀▀▀▀ █ ║   ▐▌ ▐▌\033[0m
\033[38;5;63m    ╚═╦═══════╦═╝\033[0m
\033[38;5;39m       ║ ▒▒▒▒▒▒▒ ║\033[0m
\033[38;5;33m      ╔╩═══════╩╗\033[0m
\033[38;5;27m      ║  ▄▄▄▄▄  ║\033[0m
\033[38;5;159m      ╚═════════╝\033[0m
""".trim('\n'),
        "03-kitsune.txt" to """
\033[38;5;223m         ╱╲          ╱╲\033[0m
\033[38;5;222m        ╱  ╲________╱  ╲      kon!\033[0m
\033[38;5;221m       ╭╯▄▄▄▄▄▄▄▄▄▄▄▄▄╰╮\033[0m
\033[38;5;220m       │ ▄▀▀▀▀▀▀▀▀▀▀▀▄ │\033[0m
\033[38;5;215m       │ ███▄   ▄███ │    . o O\033[0m
\033[38;5;214m       │ ██▀     ▀██ │\033[0m
\033[38;5;208m       │  ▀▄▄ ▄ ▄▄▀  │\033[0m
\033[38;5;202m        ╲    ╰───╯    ╱\033[0m
\033[38;5;166m         ╰──╮     ╭──╯\033[0m
\033[38;5;223m     (((     ╰───╯     )))\033[0m
""".trim('\n'),
        "04-shonen.txt" to """
\033[38;5;33m        ╱▔▔▔▔▔▔▔▔▔╲         FIGHT!\033[0m
\033[38;5;39m      ╱▔▔▔▔▔▔▔▔▔▔▔▔▔╲\033[0m
\033[38;5;45m     │  ╱╲ ╱╲ ╱╲ ╱╲  │       /!\033[0m
\033[38;5;75m     │  ████   ████  │\033[0m
\033[38;5;111m     │  ██▒█   █▒██  │      o O\033[0m
\033[38;5;117m     │   ▀▀     ▀▀   │\033[0m
\033[38;5;153m     │     ╭───╮     │\033[0m
\033[38;5;159m      ╲    ╰───╯    ╱\033[0m
\033[38;5;195m       ╰─────╮╭─────╯\033[0m
\033[38;5;231m        ╰────╯╰────╯\033[0m
\033[38;5;33m       ╔═╗        ╔═╗\033[0m
\033[38;5;39m       ╚═╝        ╚═╝\033[0m
""".trim('\n'),
        "05-chibi-star.txt" to """
\033[38;5;157m       *        .        *\033[0m
\033[38;5;121m          ╭───────╮\033[0m
\033[38;5;86m     .   ╱ ▄▄▄▄▄▄▄ ╲   .\033[0m
\033[38;5;85m        │  ██   ██  │        *\033[0m
\033[38;5;84m     *  │  ██   ██  │\033[0m
\033[38;5;79m        │   ╰───╯   │   .\033[0m
\033[38;5;78m     .   ╲  ╰───╯  ╱\033[0m
\033[38;5;45m          ╰───────╯        *\033[0m
\033[38;5;51m       *       .       *\033[0m
""".trim('\n'),
        "06-robot-kitty.txt" to """
\033[38;5;252m        ╱▔▔╲____╱▔▔╲\033[0m
\033[38;5;249m       │ ▓▓│    │▓▓ │        beep\033[0m
\033[38;5;245m       ╰──╯▄▄▄▄╰──╯\033[0m
\033[38;5;117m      ╭───╮ ▀▀ ╭───╮        boop\033[0m
\033[38;5;111m      │  ▄▄▄▄▄▄▄▄▄▄▄  │\033[0m
\033[38;5;75m      │ ██▀▀     ▀▀██ │\033[0m
\033[38;5;74m      │ ▀▀         ▀▀ │\033[0m
\033[38;5;68m      │   ▄▄▄▄▄▄▄▄▄   │\033[0m
\033[38;5;37m      ╰─╮ ▒▒▒▒▒▒▒ ╭─╯\033[0m
\033[38;5;252m        ╰─────────╯\033[0m
""".trim('\n'),
        "07-magical.txt" to """
\033[38;5;183m    *       ╱╲        *      *\033[0m
\033[38;5;177m         ╭──╯  ╰──╮\033[0m
\033[38;5;141m    *    │ ░░░░░░░ │      *\033[0m
\033[38;5;135m         │ ██▀ ▀██ │   HENSHIN!\033[0m
\033[38;5;171m         │ ▀   ▀  │\033[0m
\033[38;5;207m         │  ╰───╯  │   *\033[0m
\033[38;5;213m    *     ╲ ▄▄▄▄▄ ╱      *\033[0m
\033[38;5;219m           ╰─────╯    ╱╲\033[0m
\033[38;5;183m     *        ╱─────╱  ╲   *\033[0m
\033[38;5;177m              ╲─────╲  ╱\033[0m
\033[38;5;141m                 ╰───╯\033[0m
""".trim('\n'),
        "08-samurai.txt" to """
\033[38;5;17m          ╱╲╱╲\033[0m
\033[38;5;18m         ╱  ╰╯  ╲           kabuto\033[0m
\033[38;5;19m        ╭╯▄▄▄▄▄▄▄▄▄╰╮\033[0m
\033[38;5;61m        │ ▄▄▄▄▄▄▄▄▄ │\033[0m
\033[38;5;67m       ╭╯ ██▄ ▄██ ╰╮\033[0m
\033[38;5;104m       │  ███ ███  │        -  -\033[0m
\033[38;5;146m       │   ▀   ▀   │\033[0m
\033[38;5;195m       │  ╭─────╮  │\033[0m
\033[38;5;231m        ╲ ╰─────╯ ╱\033[0m
\033[38;5;17m         ╰───────╯\033[0m
\033[38;5;18m          ││   ││\033[0m
""".trim('\n'),
    )

    /** `~/.config/folio/banner.sh` — random sticker + minimal `foila@<id>` line. */
    internal val BANNER_SH = """
# folio-banner.sh — Foila launch sticker. Colored 256-color ASCII art (no
# emoji), offline, random design per interactive shell. Opt out FLUX_NO_BANNER=1.
folio-banner-art() {
  # Prints one randomly picked design from ~/.config/folio/banner/*.txt.
  # The designs carry literal \033 256-color escapes -> rendered with printf %b.
  setopt localoptions nullglob 2>/dev/null || true
  local -a _bans
  _bans=( "${'$'}HOME"/.config/folio/banner/*.txt )
  [[ -f "${'$'}{_bans[1]}" ]] || return 0
  local _bi=${'$'}(( RANDOM % ${'$'}{#_bans} + 1 ))
  case "${'$'}(cat "${'$'}{_bans[${'$'}_bi]}" 2>/dev/null || true)" in
    *'\033'*) printf '%b\n' "${'$'}(cat "${'$'}{_bans[${'$'}_bi]}")" ;;
    *)         cat "${'$'}{_bans[${'$'}_bi]}" 2>/dev/null || true ;;
  esac
  unset _bans _bi
}
folio-banner() {
  [ "${'$'}{FLUX_NO_BANNER:-0}" = "1" ] && return 0
  case ${'$'}- in *i*) ;; *) return 0;; esac
  folio-banner-art
  # Header: exactly one line, "foila@<id>" (distro id only — no host,
  # version or time). ID= from /etc/os-release; fallbacks: first word of
  # PRETTY_NAME (lowercased), then "linux".
  local _fid=""
  if [ -r /etc/os-release ]; then
    _fid="${'$'}(sed -n 's/^ID=//p' /etc/os-release 2>/dev/null | head -n 1 | tr -d '"' | tr '[:upper:]' '[:lower:]')"
    if [ -z "${'$'}_fid" ]; then
      _fid="${'$'}(sed -n 's/^PRETTY_NAME=//p' /etc/os-release 2>/dev/null | head -n 1 | tr -d '"' | awk '{print ${'$'}1}' | tr '[:upper:]' '[:lower:]')"
    fi
  fi
  [ -n "${'$'}_fid" ] || _fid="linux"
  printf '%s\n' "foila@${'$'}_fid"
  unset _fid
}
folio-banner
""".trimStart()

    /** .zshrc tail that sources the sticker (idempotent append target). */
    internal val BANNER_SOURCE_BLOCK = """
# Foila launch sticker (random colored anime banner; opt out FLUX_NO_BANNER=1)
[ -r "${'$'}HOME/.config/folio/banner.sh" ] && . "${'$'}HOME/.config/folio/banner.sh"
""".trimStart()

    /**
     * .zshrc tail that applies the staged Foila Linux appearance (idempotent
     * append target for existing profiles; the guest script hash-guards itself).
     */
    internal val APPEARANCE_SOURCE_BLOCK = """
# Foila Linux appearance (wallpaper/colors/terminal; hash-guarded inside).
[ -r "${'$'}HOME/.config/folio/appearance-apply.sh" ] && sh "${'$'}HOME/.config/folio/appearance-apply.sh" >/dev/null 2>&1 || true
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
            // Cheap re-verify so a reinstall / wiped profile still gets fixed:
            // the banner source line in .zshrc is the single marker.
            val wired = try {
                if (!zshrc.isFile) false
                else {
                    val text = zshrc.readText()
                    text.contains("folio/banner.sh") && text.contains("appearance-apply.sh")
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
                // Gentle path for otherwise-healthy profiles: append the Foila
                // Linux appearance trigger only (the guest script self-guards).
                if (!needsRepair(text) && !text.contains("appearance-apply.sh") &&
                    zshrc.canWrite()
                ) {
                    zshrc.appendText("\n$APPEARANCE_SOURCE_BLOCK")
                    Log.i(TAG, "Appended appearance trigger to ${zshrc.absolutePath}")
                }
            } else {
                // Create missing Foila profile (Alpine used to never write .zshrc)
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
                writeBannerFiles(home)
            }
            ensureZprofile(zshrc.parentFile)
            // Prefer zsh login shell when binary exists (Alpine often left /bin/bash)
            if (rootfs != null) {
                ensureFluxLoginShellZsh(rootfs)
                writeLocaleProfile(rootfs)
                writeEnsureLocaleScript(rootfs)
            }
            // Cache only after a completed pass (exception above leaves it unset).
            prefs.edit().putBoolean(cacheKey, true).apply()
        } catch (e: Exception) {
            Log.w(TAG, "zshrc repair failed: ${e.message}")
        }
    }

    /**
     * Old colorful OMZ theme shipped by earlier Foila builds — remove the file
     * (the `.zshrc` rewrite/sed switches the reference to `folio`).
     */
    internal fun removeLegacyThemes(home: File?) {
        if (home == null) return
        try {
            File(home, ".oh-my-zsh/custom/themes/agnosterzak.zsh-theme").delete()
        } catch (e: Exception) {
            Log.w(TAG, "legacy theme remove failed: ${e.message}")
        }
    }

    /** Write the monochrome `folio` prompt theme (used by ZSH_THEME=folio). */
    internal fun writeFolioTheme(home: File?) {
        if (home == null || !File(home, ".oh-my-zsh").isDirectory) return
        val theme = File(home, ".oh-my-zsh/custom/themes/folio.zsh-theme")
        try {
            theme.parentFile?.mkdirs()
            if (theme.isFile && !theme.canWrite()) return
            theme.writeText(FOLIO_THEME)
            Log.i(TAG, "Wrote folio prompt theme at ${theme.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "folio theme write failed: ${e.message}")
        }
    }

    /** Install the launch sticker: the banner text designs + `banner.sh`. */
    internal fun writeBannerFiles(home: File?) {
        if (home == null || !home.isDirectory) return
        val dir = File(home, ".config/folio/banner")
        try {
            dir.mkdirs()
            if (!dir.isDirectory) return
            for ((name, art) in BANNER_FILES) {
                val f = File(dir, name)
                if (f.isFile && !f.canWrite()) continue
                val want = art + "\n"
                if (f.isFile && f.readText() == want) continue
                f.writeText(want)
            }
            val revMark = File(dir, ".banner-rev")
            if (!revMark.isFile || revMark.readText().trim() != BANNER_ART_REVISION) {
                revMark.writeText(BANNER_ART_REVISION + "\n")
            }
            val sh = File(home, ".config/folio/banner.sh")
            if (!sh.isFile || (sh.canWrite() && sh.readText() != BANNER_SH)) {
                if (!sh.isFile || sh.canWrite()) sh.writeText(BANNER_SH)
            }
            Log.i(TAG, "Banner assets ready at ${dir.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "banner write failed: ${e.message}")
        }
    }

    /**
     * Chroot variant: `/data/local/folio` is root-only, so the same repair runs
     * inside the chroot as root through the staged helper
     * ([RootShell.captureInChroot]). True when the success marker came back.
     */
    private fun repairChrootAsRoot(ctx: Context, distroId: String?): Boolean {
        val root = if (distroId != null) {
            ChrootPaths.pathForDistro(distroId)
        } else {
            ChrootPaths.CHROOT_PATH
        }
        return try {
            val res = RootShell.captureInChroot(
                chrootRepairScript(),
                user = "root",
                chrootPath = root,
                timeoutMs = 20_000L,
                context = ctx
            )
            val ok = res.stdout.contains(CHROOT_OK_MARKER)
            if (ok) {
                Log.i(TAG, "Repaired chroot guest profile at $root")
            } else {
                Log.w(TAG, "chroot zshrc repair marker missing (exit ${res.exitCode})")
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
     * POSIX shell payload executed as root **inside** the chroot. All content is
     * base64-encoded, so no quoting/heredoc hazards; every step is best-effort
     * (`|| true`) and the marker always prints so a partially repaired guest is
     * still cached.
     */
    internal fun chrootRepairScript(): String {
        val D = "\$"
        val sb = StringBuilder()
        sb.append("set +e\n")
        sb.append("H=/home/flux\n")
        sb.append("[ -d \"${D}H\" ] || { echo $CHROOT_OK_MARKER; exit 0; }\n")
        sb.append("_put() { printf '%s' \"${D}2\" | base64 -d > \"${D}1\" 2>/dev/null || true; }\n")
        sb.append("mkdir -p \"${D}H/.config/folio/banner\" 2>/dev/null || true\n")
        sb.append("_put \"${D}H/.config/folio/banner.sh\" '").append(b64(BANNER_SH)).append("'\n")
        for ((name, art) in BANNER_FILES) {
            sb.append("_put \"${D}H/.config/folio/banner/$name\" '")
                .append(b64(art + "\n"))
                .append("'\n")
        }
        sb.append("printf '%s\\n' ").append(BANNER_ART_REVISION)
            .append(" > \"${D}H/.config/folio/banner/.banner-rev\" 2>/dev/null || true\n")
        sb.append("if [ -d \"${D}H/.oh-my-zsh\" ]; then\n")
        sb.append("  mkdir -p \"${D}H/.oh-my-zsh/custom/themes\" 2>/dev/null || true\n")
        sb.append("  rm -f \"${D}H/.oh-my-zsh/custom/themes/agnosterzak.zsh-theme\" 2>/dev/null || true\n")
        sb.append("  _put \"${D}H/.oh-my-zsh/custom/themes/folio.zsh-theme\" '").append(b64(FOLIO_THEME)).append("'\n")
        sb.append("fi\n")
        sb.append("if [ -f \"${D}H/.zshrc\" ]; then\n")
        sb.append("  grep -q 'ZSH_THEME=\"agnosterzak\"' \"${D}H/.zshrc\" 2>/dev/null && ")
            .append("sed -i 's|ZSH_THEME=\"agnosterzak\"|ZSH_THEME=\"folio\"|' \"${D}H/.zshrc\" 2>/dev/null || true\n")
        sb.append("  if ! grep -q 'folio/banner.sh' \"${D}H/.zshrc\" 2>/dev/null; then\n")
        sb.append("    printf '\\n# Foila launch sticker (random colored anime banner; opt out FLUX_NO_BANNER=1)\\n'\n")
        sb.append("    printf '[ -r \"${D}HOME/.config/folio/banner.sh\" ] && . \"${D}HOME/.config/folio/banner.sh\"\\n'\n")
        sb.append("  fi >> \"${D}H/.zshrc\" 2>/dev/null || true\n")
        sb.append("  if ! grep -q 'appearance-apply.sh' \"${D}H/.zshrc\" 2>/dev/null; then\n")
        sb.append("    printf '\\n# Foila Linux appearance (wallpaper/colors/terminal; hash-guarded inside).\\n'\n")
        sb.append("    printf '[ -r \"${D}HOME/.config/folio/appearance-apply.sh\" ] && sh \"${D}HOME/.config/folio/appearance-apply.sh\" >/dev/null 2>&1 || true\\n'\n")
        sb.append("  fi >> \"${D}H/.zshrc\" 2>/dev/null || true\n")
        sb.append("fi\n")
        sb.append("chown -R --reference=\"${D}H\" \"${D}H/.config/folio\" 2>/dev/null || true\n")
        sb.append("echo $CHROOT_OK_MARKER\n")
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
  echo "Foila: WARNING: no UTF-8 locale in locale -a; zsh staying LANG=C" >&2
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
echo "Foila: ERROR: no UTF-8 locale after generation (locale -a has neither en_US.utf8 nor C.utf8)" >&2
exit 1
""".trimStart()

    internal fun writeEnsureLocaleScript(rootfs: File) {
        val dest = File(rootfs, "usr/local/sbin/flux-ensure-locale")
        try {
            dest.parentFile?.mkdirs()
            if (dest.isFile && !dest.canWrite()) return
            dest.writeText(ENSURE_LOCALE_SH)
            dest.setExecutable(true, false)
            Log.i(TAG, "Wrote ${dest.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "flux-ensure-locale write failed: ${e.message}")
        }
    }

    /** Existing guests: replace flux-locale.sh that blindly exports C.UTF-8. */
    internal fun writeLocaleProfile(rootfs: File) {
        val dest = File(rootfs, "etc/profile.d/flux-locale.sh")
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
        // Colorful remote theme (pre-Foila builds) → switch to the mono theme.
        if (text.contains("ZSH_THEME=\"agnosterzak\"")) return true
        // Older profiles never print ZSH_THEME=folio after OMZ loads.
        if (!text.contains("ZSH_THEME=\"folio\"")) return true
        // Launch sticker (random anime banner + info block) not wired yet.
        if (!text.contains("folio/banner.sh")) return true
        return false
    }
}
