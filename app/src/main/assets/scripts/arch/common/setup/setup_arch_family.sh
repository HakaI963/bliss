#!/bin/sh

# Helpers live in altaris_guest_common.sh (prepended by altaris_install /
# familySetupPayload). Source it only when this file is run standalone.
if ! command -v _altaris_setup_pulse >/dev/null 2>&1; then
    _here=$(CDPATH= cd -- "$(dirname -- "$0")" 2>/dev/null && pwd)
    for _common in \
        "${_here}/altaris_guest_common.sh" \
        "${HOME:-}/altaris_guest_common.sh" \
        /tmp/altaris_guest_common.sh
    do
        if [ -f "$_common" ]; then
            # shellcheck source=/dev/null
            . "$_common"
            break
        fi
    done
fi

# setup_arch_family.sh — Arch Linux ARM (pacman, glibc).
# Never rewrite mirrors to Manjaro arm-stable or x86_64 Arch.
# Keep ALARM: Server = http://mirror.archlinuxarm.org/$arch/$repo
# HoldPkg stays pacman glibc. User alarm occupies uid 1000 — rename to altaris.

DISTRO_NAME="${1:-archlinux}"
echo "Altaris: Configuring ${DISTRO_NAME} (Arch Family)..."

if [ "$(id -u)" -ne 0 ]; then
    echo "Altaris: ERROR: must run as root inside the guest (got uid=$(id -u))."
    exit 1
fi

export SYSTEMD_OFFLINE=1
unset LC_ALL
export LANG=C

_altaris_ensure_tmp
_altaris_ensure_dns

if ! command -v pacman >/dev/null 2>&1; then
    echo "Altaris: ERROR: pacman not found"
    exit 1
fi

# POSIX rewrites — slim ALARM may not have GNU sed.
_altaris_comment_matching() {
    _cf="$1"
    _px="$2"
    [ -f "$_cf" ] || return 0
    _tmp="${_cf}.altarisnew.$$"
    while IFS= read -r _ln || [ -n "$_ln" ]; do
        case "$_ln" in
            "${_px}"*) printf '#%s\n' "$_ln" ;;
            *) printf '%s\n' "$_ln" ;;
        esac
    done < "$_cf" > "$_tmp" && mv -f "$_tmp" "$_cf"
}

_altaris_replace_matching() {
    _cf="$1"
    _px="$2"
    _nw="$3"
    [ -f "$_cf" ] || return 0
    _tmp="${_cf}.altarisnew.$$"
    _hit=0
    while IFS= read -r _ln || [ -n "$_ln" ]; do
        case "$_ln" in
            "${_px}"*)
                printf '%s\n' "$_nw"
                _hit=1
                ;;
            *) printf '%s\n' "$_ln" ;;
        esac
    done < "$_cf" > "$_tmp"
    [ "$_hit" = 1 ] || printf '%s\n' "$_nw" >> "$_tmp"
    mv -f "$_tmp" "$_cf"
}

_altaris_delete_matching() {
    _cf="$1"
    _px="$2"
    [ -f "$_cf" ] || return 0
    _tmp="${_cf}.altarisnew.$$"
    while IFS= read -r _ln || [ -n "$_ln" ]; do
        case "$_ln" in
            "${_px}"*) ;;
            *) printf '%s\n' "$_ln" ;;
        esac
    done < "$_cf" > "$_tmp" && mv -f "$_tmp" "$_cf"
}

# CheckSpace + Android bind mounts lie about free space (Manjaro lesson).
_altaris_disable_checkspace() {
    if grep -q '^CheckSpace' /etc/pacman.conf 2>/dev/null; then
        _altaris_comment_matching /etc/pacman.conf CheckSpace
        _altaris_log "CheckSpace disabled (Android bind mounts lie about free space)"
    fi
    mkdir -p /var/cache/pacman/pkg /var/lib/pacman
}

_altaris_disable_checkspace

# Pacman 7 on Android/proot: Landlock is missing and DownloadUser=alpm
# cannot switch sandbox user → "failed to synchronize all databases".
# Disable sandbox for this guest (same as legacy setup_arch_chroot.sh).
if ! grep -q '^DisableSandbox' /etc/pacman.conf 2>/dev/null; then
    echo 'DisableSandbox' >> /etc/pacman.conf
    _altaris_log "DisableSandbox enabled (Android kernel has no Landlock / alpm)"
fi
_altaris_comment_matching /etc/pacman.conf DownloadUser

# Keep ALARM mirrorlist. Do not write Manjaro arm-stable.
if [ ! -s /etc/pacman.d/mirrorlist ] || ! grep -q 'archlinuxarm' /etc/pacman.d/mirrorlist 2>/dev/null; then
    printf 'Server = http://mirror.archlinuxarm.org/$arch/$repo\n' > /etc/pacman.d/mirrorlist
fi

# pacman-key is the #1 landmine: /etc/pacman.d/gnupg is empty after slim.
_altaris_log "pacman-key --init (needs entropy — this can take a while)..."
mkdir -p /etc/pacman.d/gnupg
pacman-key --init || {
    _altaris_log "pacman-key --init failed once — retrying"
    if command -v rngd >/dev/null 2>&1; then
        rngd -r /dev/urandom 2>/dev/null &
        sleep 2
    fi
    pacman-key --init || true
}
_keyring_ok=0
if pacman-key --populate archlinuxarm 2>/dev/null; then
    _keyring_ok=1
fi
pacman-key --populate archlinux 2>/dev/null || true

if [ "$_keyring_ok" = 0 ]; then
    # Temporary SigLevel=Never, refresh keyring packages, restore Required
    # DatabaseOptional. NEVER leave SigLevel=Never permanent.
    _altaris_log "keyring populate failed — temporary SigLevel=Never keyring refresh"
    _had_sig=""
    if grep -q '^SigLevel' /etc/pacman.conf 2>/dev/null; then
        _had_sig="$(grep '^SigLevel' /etc/pacman.conf | head -1)"
    fi
    _altaris_replace_matching /etc/pacman.conf SigLevel 'SigLevel = Never'
    pacman -Sy --noconfirm archlinuxarm-keyring 2>/dev/null || true
    pacman -S --noconfirm --needed archlinux-keyring 2>/dev/null || true
    if [ -n "$_had_sig" ]; then
        _altaris_replace_matching /etc/pacman.conf 'SigLevel = Never' "$_had_sig"
    else
        _altaris_delete_matching /etc/pacman.conf 'SigLevel = Never'
        echo 'SigLevel = Required DatabaseOptional' >> /etc/pacman.conf
    fi
    pacman-key --populate archlinuxarm 2>/dev/null || true
    pacman-key --populate archlinux 2>/dev/null || true
fi

_altaris_ensure_groups

_altaris_log "pacman -Sy..."
if ! pacman -Sy --noconfirm; then
    echo "Altaris: pacman -Sy failed (repos/network)"
    exit 1
fi

# Resolve the desktop choice before base packages: Mesa/icon themes are a
# desktop-only payload and must not be pulled into a terminal base.
DESKTOPS="${ALTARIS_DESKTOPS:-${ALTARIS_DESKTOP:-${ALTARIS_DESKTOP_ENV:-xfce}}}"
DESKTOPS=$(printf '%s' "$DESKTOPS" | tr ',' ' ')
DESKTOPS=$(printf '%s' "$DESKTOPS" | tr -s '[:space:]' ' ')
case " $DESKTOPS " in *" xfce "*|*" xfce4 "*) _norm="xfce" ;; *) _norm="" ;; esac
case " $DESKTOPS " in *" kde "*) _norm="${_norm}${_norm:+ }kde" ;; esac
[ -n "$_norm" ] || _norm="none"
DESKTOPS="$_norm"
unset _norm

_altaris_log "Installing base packages..."
_BASE_PKGS="sudo git zsh python sed gzip dbus xz"
if [ "$DESKTOPS" != "none" ]; then
    _BASE_PKGS="$_BASE_PKGS ttf-dejavu adwaita-icon-theme mesa mesa-utils"
fi
if ! pacman -S --noconfirm --needed $_BASE_PKGS; then
    echo "Altaris: base package install failed"
    exit 1
fi
unset _BASE_PKGS

# Desktop contract: ALTARIS_DESKTOPS (space/comma separated) = xfce | kde | none;
# legacy ALTARIS_DESKTOP / ALTARIS_DESKTOP_ENV remain valid single values. Unset/empty
# → xfce (backward compat). Normalized to the canonical "xfce kde" order;
# unknown tokens are ignored and "none" installs neither desktop.
DESKTOPS="${ALTARIS_DESKTOPS:-${ALTARIS_DESKTOP:-${ALTARIS_DESKTOP_ENV:-xfce}}}"
DESKTOPS=$(printf '%s' "$DESKTOPS" | tr ',' ' ')
DESKTOPS=$(printf '%s' "$DESKTOPS" | tr -s '[:space:]' ' ')
DESKTOPS="${DESKTOPS# }"
DESKTOPS="${DESKTOPS% }"
case " $DESKTOPS " in *" xfce "*|*" xfce4 "*) _norm="xfce" ;; *) _norm="" ;; esac
case " $DESKTOPS " in *" kde "*) _norm="${_norm}${_norm:+ }kde" ;; esac
[ -n "$_norm" ] || _norm="none"
DESKTOPS="$_norm"
unset _norm
if [ -z "${DESKTOPS##*xfce*}" ]; then
    _altaris_log "Installing XFCE packages (targeted — no xfce4 group first)..."
    if ! _altaris_retry_pkg pacman -S --noconfirm --needed \
        xfce4-session xfce4-panel xfce4-settings xfce4-terminal \
        xfdesktop xfwm4 thunar; then
        echo "Altaris: XFCE install failed after 3 tries — check your network, then tap Retry Setup."
        exit 1
    fi

    if ! command -v startxfce4 >/dev/null 2>&1 && [ ! -e /usr/bin/startxfce4 ]; then
        _altaris_log "startxfce4 missing — falling back to xfce4 group"
        pacman -S --noconfirm --needed xfce4 || true
    fi
    # Phone niceties: Whisker panel menu + screenshot tool (best-effort).
    pacman -S --noconfirm --needed xfce4-whiskermenu-plugin xfce4-screenshooter 2>/dev/null || true
    # Phone status indicators: battery + network-load panel plugins
    # (best-effort; the panel writer adds them only when the .so exists).
    pacman -S --noconfirm --needed xfce4-battery-plugin xfce4-netload-plugin 2>/dev/null || true
fi

_altaris_ensure_dbus

# User alarm occupies uid 1000. Prefer rename so home/files stay.
if id alarm >/dev/null 2>&1 && ! id altaris >/dev/null 2>&1; then
    _altaris_log "Renaming alarm → altaris (uid 1000)"
    usermod -l altaris -d /home/flux -m alarm 2>/dev/null \
        || { userdel -r alarm 2>/dev/null || true; }
fi
# Chroot can leave both (usermod -l no-op + useradd flux=1001). One uid 1000.
if id alarm >/dev/null 2>&1 && id altaris >/dev/null 2>&1; then
    _altaris_log "Removing leftover alarm (altaris already exists)"
    userdel -r alarm 2>/dev/null || userdel alarm 2>/dev/null || true
fi
if id altaris >/dev/null 2>&1 && ! id -u 1000 >/dev/null 2>&1; then
    usermod -u 1000 -d /home/flux altaris 2>/dev/null || true
fi

if [ -z "${DESKTOPS##*xfce*}" ]; then
    _altaris_require_startxfce4
fi
_altaris_ensure_user
echo "flux:flux" | chpasswd 2>/dev/null || true
if command -v usermod >/dev/null 2>&1; then
    usermod -aG wheel,audio,video,input,users altaris 2>/dev/null || true
fi
_altaris_ensure_sudo
_altaris_ensure_home
_altaris_setup_pulse
_altaris_ensure_en_us_locale || exit 1
_altaris_write_gpu_mode virgl
_altaris_fix_pm_writable

# Phone-compact XFCE defaults (30px mono top panel, DPI 96, wallpaper,
# Home-only desktop icons, shortcuts). Helper ships in altaris_guest_common.sh;
# guarded so a standalone run without it is a no-op and never fails install.
if [ -z "${DESKTOPS##*xfce*}" ] && command -v _altaris_xfce_compact_defaults >/dev/null 2>&1; then
    _altaris_xfce_compact_defaults || true
fi


# Altaris universal interactive shell: zsh suggestions, syntax colors, sticker.
if command -v _altaris_setup_altaris_shell >/dev/null 2>&1; then
    _altaris_setup_altaris_shell || true
fi

echo "Altaris: ${DISTRO_NAME} Arch setup complete!"
exit 0
