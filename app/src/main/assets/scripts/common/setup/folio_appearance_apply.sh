#!/bin/sh
# folio_appearance_apply.sh — Bliss Linux guest appearance applier (v2).
#
# Reads <home>/.config/folio/appearance.env and applies the user's saved
# appearance choices inside the Linux guest:
#   - desktop wallpaper (image, solid color or random preset) + image style
#   - xfce4-terminal colors (terminalrc + xfce4-terminal xfconf channel)
#   - terminal font size 10/12/14 (keeps the configured font family)
#   - folio zsh prompt color (when the guest has the folio theme)
#   - accent selection color via ~/.config/gtk-3.0/gtk.css (best-effort)
#   - panel/dock opacity (solid/frosted/glass) + icon size S/M/L
#   - optional mpv video-wallpaper autostart entry (opt-in, reversible)
#   - login-time safety-net autostart entry for re-applying at desktop start
#
# Idempotent + hash-guarded: it re-applies only when appearance.env changes
# (marker: ~/.config/folio/.appearance.applied = FOLIO_APPEARANCE_APPLIED_<sum>).
# In random-wallpaper mode it re-picks a wallpaper on every run, but skips the
# other steps unless the env changed.
#
# Runs from: guest ~/.zshrc trigger, customization scripts (root), the chroot
# login helper (root, host-side), the login autostart entry and the app
# (proot via proot-distro / chroot via root shell, for live apply).
# Never fatal: every step is best-effort (`|| true`), no network, no packages.
#
# Env overrides (optional):
#   FOLIO_APPLY_HOME       guest home to configure (default: $HOME then /home/flux)
#   FOLIO_APPLY_ROOT       prefix for guest-absolute paths for host-side runs
#                          outside the chroot (file existence checks only; the
#                          shipped hooks run inside the chroot, so this is a
#                          debug/advanced escape hatch)
#   FOLIO_APPLY_FORCE      "1" = apply everything even when the hash is unchanged
#   FOLIO_APPLY_PRINT_PICK "1" = print FOLIO_APPEARANCE_PICK_IMAGE/…_STYLE lines
#                          (machine-readable; used by the app's live-apply path)

FOLIO_APPEARANCE_SCRIPT_VERSION=2

_fa_home="${FOLIO_APPLY_HOME:-${HOME:-/home/flux}}"
[ -d "$_fa_home" ] || exit 0

_fa_cfg="$_fa_home/.config/folio"
_fa_env="$_fa_cfg/appearance.env"
[ -f "$_fa_env" ] || exit 0

# Defaults guard for sourced keys (old env files → no new-option changes).
FLUX_WALLPAPER=""
FLUX_WALLPAPER_STYLE="zoom"
FLUX_WALLPAPER_SOLID="#0B0B0D"
FLUX_WALLPAPER_RANDOM="0"
FLUX_WALLPAPER_CHOICES=""
FLUX_WALLPAPER_DIM="0"
FLUX_ACCENT="mono"
FLUX_ACCENT_HEX=""
FLUX_TERM_FG=""
FLUX_TERM_BG=""
FLUX_TERM_CURSOR=""
FLUX_TERM_SELECTION=""
FLUX_TERM_FONT_SIZE=""
FLUX_PROMPT_COLOR=""
FLUX_PANEL_OPACITY=""
FLUX_ICON_SIZE=""
FLUX_VIDEO_WALLPAPER=""
# shellcheck disable=SC1090
. "$_fa_env" 2>/dev/null || true

# ── hash guard ───────────────────────────────────────────────────────────────
_fa_marker="$_fa_cfg/.appearance.applied"
_fa_sum=""
if command -v cksum >/dev/null 2>&1; then
    _fa_sum=$(cksum "$_fa_env" 2>/dev/null | tr -s ' ' '-')
fi
[ -n "$_fa_sum" ] || _fa_sum="nohash"
_fa_want="FOLIO_APPEARANCE_APPLIED_${FOLIO_APPEARANCE_SCRIPT_VERSION}_${_fa_sum}"

_fa_full=1
if [ "${FOLIO_APPLY_FORCE:-0}" != "1" ]; then
    _fa_cur=$(cat "$_fa_marker" 2>/dev/null || true)
    if [ "$_fa_cur" = "$_fa_want" ]; then
        _fa_full=0
        # Random mode still re-picks a wallpaper, but skips everything else.
        [ "$FLUX_WALLPAPER_RANDOM" = "1" ] || exit 0
    fi
fi

# ── helpers ──────────────────────────────────────────────────────────────────
_fa_log() { echo "Bliss Linux appearance: $*"; }

# On-disk path for a guest-absolute path (host-side chroot runs prefix the root).
_fa_guest_path() {
    if [ -n "${FOLIO_APPLY_ROOT:-}" ] && [ "${1#/}" != "$1" ]; then
        printf '%s%s' "$FOLIO_APPLY_ROOT" "$1"
    else
        printf '%s' "$1"
    fi
}

# Escape a value for a sed replacement using '#' as the delimiter.
_fa_esc() { printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/&/\\&/g' -e 's/#/\\#/g'; }

# Escape text for XML attribute values.
_fa_xml_esc() {
    printf '%s' "$1" | sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g' -e 's/"/\&quot;/g'
}

# Solid-color rgba doubles: prints 4 space-separated doubles ("R G B 1").
_fa_solid_doubles() {
    _h="${1#\#}"
    case "$_h" in
        ??????) ;;
        *) return 1 ;;
    esac
    case "$_h" in
        *[!0-9a-fA-F]*) return 1 ;;
    esac
    _r=$(( 0x${_h%????} ))
    _g=$(( 0x$(printf '%s' "${_h#??}" | cut -c1-2) ))
    _b=$(( 0x$(printf '%s' "${_h#????}" | cut -c1-2) ))
    command -v awk >/dev/null 2>&1 || return 1
    awk -v r="$_r" -v g="$_g" -v b="$_b" \
        'BEGIN { printf "%.8f %.8f %.8f 1", r/255, g/255, b/255 }'
}

# Replace (or append) KEY=VALUE in a terminalrc-style file (portable, no sed -i).
_fa_rc_set() {
    _f="$1"; _k="$2"; _v="$3"
    [ -f "$_f" ] || printf '[Configuration]\n' > "$_f" 2>/dev/null || return 0
    if command -v awk >/dev/null 2>&1; then
        awk -v k="$_k" -v v="$_v" '
            BEGIN { done = 0 }
            index($0, k "=") == 1 { print k "=" v; done = 1; next }
            { print }
            END { if (!done) print k "=" v }
        ' "$_f" > "$_f.fa.$$" 2>/dev/null && mv "$_f.fa.$$" "$_f" 2>/dev/null || rm -f "$_f.fa.$$"
    elif grep -q "^${_k}=" "$_f" 2>/dev/null; then
        _fa_log "terminalrc: cannot update $_k (awk missing)"
    else
        printf '%s=%s\n' "$_k" "$_v" >> "$_f" 2>/dev/null || true
    fi
}

# Replace or append a simple single-line <property/> in an xfconf channel XML.
# Keeps every other key intact (the shipped compact defaults write the same
# channels, so this must merge, never clobber). Best-effort; never fatal.
#   $1 file  $2 property name  $3 type  $4 raw value  $5 channel name (new file)
_fa_xfconf_set() {
    _x="$1"; _k="$2"; _t="$3"; _v="$4"; _ch="$5"
    command -v awk >/dev/null 2>&1 || return 0
    mkdir -p "$(dirname "$_x")" 2>/dev/null || true
    if [ ! -f "$_x" ]; then
        printf '<?xml version="1.0" encoding="UTF-8"?>\n<channel name="%s" version="1.0">\n</channel>\n' "$_ch" > "$_x" 2>/dev/null || return 0
    fi
    _ve=$(_fa_xml_esc "$_v")
    awk -v k="$_k" -v t="$_t" -v v="$_ve" '
        BEGIN { done = 0 }
        {
            if (!done && index($0, "<property name=\"" k "\"") > 0 && index($0, "value=") > 0) {
                sub(/type="[^"]*"/, "type=\"" t "\"")
                sub(/value="[^"]*"/, "value=\"" v "\"")
                done = 1
            }
            if (!done && index($0, "</channel>") > 0) {
                print "  <property name=\"" k "\" type=\"" t "\" value=\"" v "\"/>"
                done = 1
            }
            print
        }
    ' "$_x" > "$_x.fa.$$" 2>/dev/null && mv "$_x.fa.$$" "$_x" 2>/dev/null \
        || rm -f "$_x.fa.$$"
    return 0
}

# Wallpaper image style string -> xfce4-desktop integer.
_fa_style_int() {
    case "${1:-zoom}" in
        stretched) printf '3' ;;
        centered)  printf '1' ;;
        zoom|*)    printf '5' ;;
    esac
}

# ── wallpaper ────────────────────────────────────────────────────────────────
_fa_wallpaper_pick() {
    # Echoes the wallpaper path to use ("" = solid color).
    if [ "$FLUX_WALLPAPER_RANDOM" = "1" ] && [ -n "${FLUX_WALLPAPER_CHOICES:-}" ]; then
        _n=0
        for _c in $FLUX_WALLPAPER_CHOICES; do _n=$((_n + 1)); done
        [ "$_n" -gt 0 ] 2>/dev/null || { printf '%s' ""; return 0; }
        _pick=1
        if [ -n "${RANDOM:-}" ]; then
            _pick=$(( (RANDOM % _n) + 1 ))
        fi
        _i=0
        for _c in $FLUX_WALLPAPER_CHOICES; do
            _i=$((_i + 1))
            if [ "$_i" = "$_pick" ]; then printf '%s' "$_c"; return 0; fi
        done
        printf '%s' ""
        return 0
    fi
    printf '%s' "$FLUX_WALLPAPER"
}

# Full xfce4-desktop.xml write (same monitor set as the compact defaults).
_fa_write_desktop_full() {
    _xml="$_fa_home/.config/xfce4/xfconf/xfce-perchannel-xml/xfce4-desktop.xml"
    _wp="$1"
    _style="$2"
    _solid="$3"
    mkdir -p "$(dirname "$_xml")" 2>/dev/null || return 0
    _rgba=$(_fa_solid_doubles "$_solid" 2>/dev/null || true)
    [ -n "$_rgba" ] || _rgba="0.04313725 0.04705882 0.05490196 1"
    _r1=$(printf '%s' "$_rgba" | cut -d' ' -f1)
    _g1=$(printf '%s' "$_rgba" | cut -d' ' -f2)
    _b1=$(printf '%s' "$_rgba" | cut -d' ' -f3)
    {
        printf '<?xml version="1.0" encoding="UTF-8"?>\n'
        printf '<channel name="xfce4-desktop" version="1.0">\n'
        printf '  <property name="backdrop" type="empty">\n'
        printf '    <property name="screen0" type="empty">\n'
        for _m in monitor0 monitor1 monitorVNC-0 monitorbuiltin builtin \
                  monitorHDMI-A-0 monitorVirtual-0 monitorVirtual-1 monitorVirtual1; do
            printf '      <property name="%s" type="empty">\n' "$_m"
            printf '        <property name="workspace0" type="empty">\n'
            printf '          <property name="last-image" type="string" value="%s"/>\n' "$_wp"
            printf '          <property name="image-style" type="int" value="%s"/>\n' "$_style"
            printf '          <property name="color-style" type="int" value="0"/>\n'
            printf '          <property name="rgba1" type="array">\n'
            printf '            <value type="double" value="%s"/>\n' "$_r1"
            printf '            <value type="double" value="%s"/>\n' "$_g1"
            printf '            <value type="double" value="%s"/>\n' "$_b1"
            printf '            <value type="double" value="1"/>\n'
            printf '          </property>\n'
            printf '          <property name="rgba2" type="array">\n'
            printf '            <value type="double" value="%s"/>\n' "$_r1"
            printf '            <value type="double" value="%s"/>\n' "$_g1"
            printf '            <value type="double" value="%s"/>\n' "$_b1"
            printf '            <value type="double" value="1"/>\n'
            printf '          </property>\n'
            printf '        </property>\n'
            printf '      </property>\n'
        done
        printf '    </property>\n'
        printf '  </property>\n'
        printf '  <property name="desktop-icons" type="empty">\n'
        printf '    <property name="style" type="int" value="2"/>\n'
        printf '    <property name="file-icons" type="empty">\n'
        printf '      <property name="show-home" type="bool" value="true"/>\n'
        printf '      <property name="show-filesystem" type="bool" value="false"/>\n'
        printf '      <property name="show-trash" type="bool" value="false"/>\n'
        printf '      <property name="show-removable" type="bool" value="false"/>\n'
        printf '    </property>\n'
        printf '  </property>\n'
        printf '</channel>\n'
    } > "$_xml.fa.$$" 2>/dev/null && mv "$_xml.fa.$$" "$_xml" 2>/dev/null \
        || rm -f "$_xml.fa.$$"
}

# Surgical merge: keep existing monitor layout / icon prefs, update the image
# and style. Never clobbers other keys (desktop-icons, menu, cycle, …).
_fa_merge_desktop() {
    _xml="$_fa_home/.config/xfce4/xfconf/xfce-perchannel-xml/xfce4-desktop.xml"
    _wp_esc=$(_fa_esc "$1")
    sed -e "s#\(<property name=\"last-image\" type=\"string\" value=\"\)[^\"]*#\1${_wp_esc}#g" \
        -e "s#\(<property name=\"image-style\" type=\"int\" value=\"\)[0-9]*#\1$2#g" \
        "$_xml" > "$_xml.fa.$$" 2>/dev/null && mv "$_xml.fa.$$" "$_xml" 2>/dev/null \
        || rm -f "$_xml.fa.$$"
}

_fa_apply_wallpaper() {
    _xml="$_fa_home/.config/xfce4/xfconf/xfce-perchannel-xml/xfce4-desktop.xml"
    _wp=$(_fa_wallpaper_pick)
    _fa_mode="preset"
    if [ -n "$_wp" ] && [ ! -f "$(_fa_guest_path "$_wp")" ]; then
        _fa_log "wallpaper missing: $_wp (keeping current)"
        return 0
    fi
    case "$FLUX_WALLPAPER_RANDOM" in
        1) _fa_mode="random" ;;
    esac
    if [ -n "$_wp" ]; then
        case "$_wp" in
            */wallpaper-dim.[a-zA-Z]*|*/wallpaper.[a-zA-Z]*) _fa_mode="custom image" ;;
        esac
    else
        _fa_mode="solid color"
    fi
    _style=$(_fa_style_int "$FLUX_WALLPAPER_STYLE")
    # Solid color (no image): image-style 0 so the rgba color is visible.
    [ -n "$_wp" ] || _style=0
    if [ "${FOLIO_APPLY_PRINT_PICK:-0}" = "1" ]; then
        printf 'FOLIO_APPEARANCE_PICK_IMAGE=%s\n' "$_wp"
        printf 'FOLIO_APPEARANCE_PICK_STYLE=%s\n' "$_style"
    fi
    if [ -n "$_wp" ] && [ -f "$_xml" ] && grep -q 'last-image' "$_xml" 2>/dev/null; then
        _fa_merge_desktop "$_wp" "$_style"
    else
        _fa_write_desktop_full "$_wp" "$_style" "$FLUX_WALLPAPER_SOLID"
    fi
}

# ── terminal colors + font ───────────────────────────────────────────────────
# Modern xfce4-terminal (0.9+/1.x) reads the xfce4-terminal xfconf channel;
# terminalrc is kept for older guests. Both are written (merge, never clobber).
_fa_apply_terminal() {
    _rc="$_fa_home/.config/xfce4/terminal/terminalrc"
    _xf="$_fa_home/.config/xfce4/xfconf/xfce-perchannel-xml/xfce4-terminal.xml"
    if [ -n "$FLUX_TERM_FG" ] && [ -n "$FLUX_TERM_BG" ]; then
        mkdir -p "$(dirname "$_rc")" 2>/dev/null || true
        _fa_rc_set "$_rc" ColorForeground "$FLUX_TERM_FG"
        _fa_rc_set "$_rc" ColorBackground "$FLUX_TERM_BG"
        [ -n "$FLUX_TERM_CURSOR" ] && _fa_rc_set "$_rc" ColorCursor "$FLUX_TERM_CURSOR"
        [ -n "$FLUX_TERM_SELECTION" ] && _fa_rc_set "$_rc" ColorSelection "$FLUX_TERM_SELECTION"
        _fa_xfconf_set "$_xf" "color-foreground" "string" "$FLUX_TERM_FG" "xfce4-terminal"
        _fa_xfconf_set "$_xf" "color-background" "string" "$FLUX_TERM_BG" "xfce4-terminal"
        [ -n "$FLUX_TERM_CURSOR" ] && _fa_xfconf_set "$_xf" "color-cursor" "string" "$FLUX_TERM_CURSOR" "xfce4-terminal"
        [ -n "$FLUX_TERM_SELECTION" ] && _fa_xfconf_set "$_xf" "color-selection" "string" "$FLUX_TERM_SELECTION" "xfce4-terminal"
        _fa_log "terminal colors updated"
    fi
}

# Terminal font size: rewrite only the trailing size of the existing FontName so
# the guest-chosen family (JetBrainsMono Nerd Font Mono, DejaVu, …) is kept.
_fa_apply_term_font() {
    case "${FLUX_TERM_FONT_SIZE:-}" in
        10|11|12|13|14|15|16) ;;
        *) return 0 ;;
    esac
    _rc="$_fa_home/.config/xfce4/terminal/terminalrc"
    _xf="$_fa_home/.config/xfce4/xfconf/xfce-perchannel-xml/xfce4-terminal.xml"
    _cur=$(sed -n 's/^FontName=//p' "$_rc" 2>/dev/null | head -n 1)
    if [ -z "$_cur" ] && [ -f "$_xf" ]; then
        # Modern guests keep the font only in the xfce4-terminal channel.
        _cur=$(sed -n 's/.*<property name="font-name"[^>]*value="\([^"]*\)".*/\1/p' "$_xf" 2>/dev/null | head -n 1)
    fi
    _fam=$(printf '%s' "$_cur" | sed 's/ [0-9][0-9]*$//')
    [ -n "$_fam" ] || _fam="Monospace"
    mkdir -p "$(dirname "$_rc")" 2>/dev/null || true
    _fa_rc_set "$_rc" FontUseSystem "FALSE"
    _fa_rc_set "$_rc" FontName "$_fam $FLUX_TERM_FONT_SIZE"
    _fa_xfconf_set "$_xf" "font-use-system" "bool" "false" "xfce4-terminal"
    _fa_xfconf_set "$_xf" "font-name" "string" "$_fam $FLUX_TERM_FONT_SIZE" "xfce4-terminal"
    _fa_log "terminal font size: $FLUX_TERM_FONT_SIZE"
}

_fa_apply_prompt() {
    [ -n "$FLUX_PROMPT_COLOR" ] || return 0
    case "$FLUX_PROMPT_COLOR" in *[!0-9]*) return 0 ;; esac
    _t="$_fa_home/.oh-my-zsh/custom/themes/folio.zsh-theme"
    [ -f "$_t" ] || return 0
    command -v awk >/dev/null 2>&1 || return 0
    _line="PROMPT='%F{240}%D{%H:%M}%f %F{${FLUX_PROMPT_COLOR}}bliss%f %F{250}%~%f %F{${FLUX_PROMPT_COLOR}}%#%f '"
    awk -v l="$_line" '/^PROMPT=/ { print l; next } { print }' "$_t" \
        > "$_t.fa.$$" 2>/dev/null && mv "$_t.fa.$$" "$_t" 2>/dev/null \
        || rm -f "$_t.fa.$$"
}

# gtk.css accent (managed block; removals keep the rest of the file intact).
_fa_apply_accent() {
    _css="$_fa_home/.config/gtk-3.0/gtk.css"
    mkdir -p "$(dirname "$_css")" 2>/dev/null || true
    if [ -f "$_css" ] && command -v awk >/dev/null 2>&1; then
        awk '
            $0 == "/* folio-accent:start */" { skip = 1; next }
            $0 == "/* folio-accent:end */" { skip = 0; next }
            skip != 1 { print }
        ' "$_css" > "$_css.fa.$$" 2>/dev/null && mv "$_css.fa.$$" "$_css" 2>/dev/null \
            || rm -f "$_css.fa.$$"
    fi
    _hex="$FLUX_ACCENT_HEX"
    [ -n "$_hex" ] || return 0
    case "$_hex" in
        \#[0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F]) ;;
        *) return 0 ;;
    esac
    {
        printf '/* folio-accent:start */\n'
        printf '@define-color theme_selected_bg_color %s;\n' "$_hex"
        printf '@define-color theme_selected_fg_color #09090B;\n'
        printf '@define-color accent_color %s;\n' "$_hex"
        printf '@define-color accent_bg_color %s;\n' "$_hex"
        printf '/* folio-accent:end */\n'
    } >> "$_css" 2>/dev/null || true
    _fa_log "accent: $FLUX_ACCENT ($_hex)"
}

# ── panel/dock opacity + icon size ───────────────────────────────────────────
# Patches ONLY the shipped panel-1 (top bar) / panel-2 (dock) blocks of
# xfce4-panel.xml; every other key (plugins, positions, per-panel layout) is
# preserved. Opacity edits the 4th rgba double of each background-rgba array;
# icon size also scales the panel heights so nothing clips.
_fa_apply_panel() {
    _px="$_fa_home/.config/xfce4/xfconf/xfce-perchannel-xml/xfce4-panel.xml"
    [ -f "$_px" ] || return 0
    command -v awk >/dev/null 2>&1 || return 0
    _alpha=""
    case "${FLUX_PANEL_OPACITY:-}" in
        solid)         _alpha="1.0" ;;
        frosted|frost) _alpha="0.9" ;;
        glass)         _alpha="0.7" ;;
        *)             _alpha="" ;;
    esac
    _i1=""; _i2=""; _s1=""; _s2=""
    case "${FLUX_ICON_SIZE:-}" in
        s|S) _i1="14"; _i2="28"; _s1="26"; _s2="56" ;;
        m|M) _i1="16"; _i2="32"; _s1="28"; _s2="64" ;;
        l|L) _i1="18"; _i2="36"; _s1="32"; _s2="72" ;;
        *)   ;;
    esac
    [ -n "$_alpha$_i1" ] || return 0
    awk -v alpha="$_alpha" -v i1="$_i1" -v i2="$_i2" -v s1="$_s1" -v s2="$_s2" '
        BEGIN { pid = ""; ina = 0; n = 0 }
        {
            if ($0 ~ /<property name="background-rgba" type="array">/) {
                ina = 1; n = 0; print; next
            }
            if (ina) {
                if ($0 ~ /<value type="double"/) {
                    _rest = $0; _out = ""
                    while (match(_rest, /<value type="double" value="[^"]*"\/>/)) {
                        n++
                        _tok = substr(_rest, RSTART, RLENGTH)
                        if (n == 4 && alpha != "") sub(/value="[^"]*"/, "value=\"" alpha "\"", _tok)
                        _out = _out substr(_rest, 1, RSTART - 1) _tok
                        _rest = substr(_rest, RSTART + RLENGTH)
                    }
                    print _out _rest
                    if ($0 ~ /<\/property>/) ina = 0
                    next
                }
                print
                if ($0 ~ /<\/property>/) ina = 0
                next
            }
            if (match($0, /name="panel-[0-9]+"/)) {
                pid = substr($0, RSTART + 6, RLENGTH - 7)
            }
            if (pid == "panel-1" && i1 != "" && $0 ~ /<property name="icon-size" type="uint"/) {
                sub(/value="[0-9]+"/, "value=\"" i1 "\"")
            } else if (pid == "panel-2" && i2 != "" && $0 ~ /<property name="icon-size" type="uint"/) {
                sub(/value="[0-9]+"/, "value=\"" i2 "\"")
            } else if (pid == "panel-1" && s1 != "" && $0 ~ /<property name="size" type="uint"/) {
                sub(/value="[0-9]+"/, "value=\"" s1 "\"")
            } else if (pid == "panel-2" && s2 != "" && $0 ~ /<property name="size" type="uint"/) {
                sub(/value="[0-9]+"/, "value=\"" s2 "\"")
            }
            print
        }
    ' "$_px" > "$_px.fa.$$" 2>/dev/null && mv "$_px.fa.$$" "$_px" 2>/dev/null \
        || rm -f "$_px.fa.$$"
    _fa_log "panel opacity: ${FLUX_PANEL_OPACITY:-unchanged}, icons: ${FLUX_ICON_SIZE:-unchanged}"
}

# ── video wallpaper (opt-in, experimental) ───────────────────────────────────
_fa_apply_video() {
    _df="$_fa_home/.config/autostart/folio-video-wallpaper.desktop"
    if [ -z "${FLUX_VIDEO_WALLPAPER:-}" ] || [ ! -f "$(_fa_guest_path "$FLUX_VIDEO_WALLPAPER")" ]; then
        rm -f "$_df" 2>/dev/null || true
        return 0
    fi
    if command -v mpv >/dev/null 2>&1; then
        mkdir -p "$(dirname "$_df")" 2>/dev/null || true
        {
            printf '[Desktop Entry]\n'
            printf 'Type=Application\n'
            printf 'Version=1.0\n'
            printf 'Name=Bliss Linux video wallpaper (experimental)\n'
            printf 'Comment=Loops a muted video as an overlay window (mpv); disable in Bliss Linux appearance settings.\n'
            printf 'Exec=mpv --loop-file --no-audio --no-osc --really-quiet --title=Bliss-video-wallpaper "%s"\n' "$FLUX_VIDEO_WALLPAPER"
            printf 'Terminal=false\n'
            printf 'X-GNOME-Autostart-enabled=true\n'
        } > "$_df" 2>/dev/null || true
        _fa_log "video wallpaper enabled (mpv autostart)"
    fi
    # mpv missing: keep any existing entry (the chroot host-side run has no
    # guest PATH; the in-guest apply performs the real check).
}

# ── login-time safety net ────────────────────────────────────────────────────
# Re-applies the staged config when the desktop starts, so existing installs
# (and the "staged but desktop already running" case) pick it up without a
# reinstall. Idempotent; removed only with the distro itself.
_fa_ensure_autostart() {
    _ad="$_fa_home/.config/autostart"
    mkdir -p "$_ad" 2>/dev/null || return 0
    cat > "$_ad/folio-appearance.desktop" <<EOF
[Desktop Entry]
Type=Application
Version=1.0
Name=Bliss Linux appearance
Comment=Applies your Bliss Linux wallpaper, colors and terminal settings at login.
Exec=sh $_fa_home/.config/folio/appearance-apply.sh
Terminal=false
StartupNotify=false
OnlyShowIn=XFCE;
X-GNOME-Autostart-enabled=true
EOF
}

# ── ownership (root runs: mirror the home owner; proot: app uid) ─────────────
_fa_fix_owner() {
    [ "$(id -u 2>/dev/null || echo 0)" = "0" ] || return 0
    _u=$(stat -c %u "$_fa_home" 2>/dev/null || stat -f %u "$_fa_home" 2>/dev/null || true)
    _g=$(stat -c %g "$_fa_home" 2>/dev/null || stat -f %g "$_fa_home" 2>/dev/null || true)
    [ -n "$_u" ] || return 0
    for _p in "$_fa_cfg" "$_fa_home/.local/share/folio" \
              "$_fa_home/.config/xfce4/terminal/terminalrc" \
              "$_fa_home/.config/xfce4/xfconf/xfce-perchannel-xml/xfce4-desktop.xml" \
              "$_fa_home/.config/xfce4/xfconf/xfce-perchannel-xml/xfce4-terminal.xml" \
              "$_fa_home/.config/xfce4/xfconf/xfce-perchannel-xml/xfce4-panel.xml" \
              "$_fa_home/.config/gtk-3.0/gtk.css" \
              "$_fa_home/.config/autostart/folio-appearance.desktop" \
              "$_fa_home/.config/autostart/folio-video-wallpaper.desktop" \
              "$_fa_home/.oh-my-zsh/custom/themes/folio.zsh-theme"; do
        [ -e "$_p" ] || continue
        chown -R "$_u:$_g" "$_p" 2>/dev/null || true
    done
}

# ── run ──────────────────────────────────────────────────────────────────────
_fa_apply_wallpaper

if [ "$_fa_full" = "1" ]; then
    _fa_apply_terminal
    _fa_apply_term_font
    _fa_apply_prompt
    _fa_apply_accent
    _fa_apply_panel
    _fa_apply_video
    _fa_ensure_autostart
fi

_fa_fix_owner

if [ "$_fa_full" = "1" ]; then
    printf '%s\n' "$_fa_want" > "$_fa_marker" 2>/dev/null || true
fi

_fa_log "applied (${_fa_mode:-preset}, style ${FLUX_WALLPAPER_STYLE:-zoom})"
exit 0
