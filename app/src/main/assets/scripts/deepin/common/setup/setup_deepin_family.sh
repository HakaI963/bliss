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

# setup_deepin_family.sh — Deepin 25 (crimson/beige) XFCE (proot + chroot).
# Debian-LIKE, not Debian: keep the beige/crimson sources, NEVER add debian.org.
# Never install dde-*/deepin-desktop-environment*/Treeland.

DISTRO_NAME="${1:-deepin}"
echo "Altaris: Configuring ${DISTRO_NAME} (Deepin Family)..."

if [ "$(id -u)" -ne 0 ]; then
    echo "Altaris: ERROR: must run as root inside the guest (got uid=$(id -u))."
    exit 1
fi

export DEBIAN_FRONTEND=noninteractive
export DEBCONF_NONINTERACTIVE_SEEN=true
export SYSTEMD_OFFLINE=1
unset LC_ALL
export LANG=C

_altaris_ensure_tmp
_altaris_ensure_dns
_altaris_disable_guest_selinux

if ! command -v apt-get >/dev/null 2>&1; then
    echo "Altaris: ERROR: apt-get not found"
    exit 1
fi

# proot/chroot apt sandbox (copy Debian lesson) — _apt user probe breaks under proot.
mkdir -p /etc/apt/apt.conf.d
printf 'APT::Sandbox::User "root";\nDPkg::Use-Pty "false";\n' \
    > /etc/apt/apt.conf.d/99altaris-nosandbox

_altaris_log "apt-get update..."
if ! apt-get update; then
    _altaris_log "apt-get update failed once — retrying (beige/crimson repos only)"
    apt-get update || {
        echo "Altaris: apt-get update failed (repos/network)"
        exit 1
    }
fi

_altaris_log "Installing base packages..."
apt-get install -y --no-install-recommends \
    bash sudo passwd adduser ca-certificates curl wget unzip tar xz-utils \
    git zsh python3 \
    dbus dbus-x11 || {
        echo "Altaris: base package install failed"
        exit 1
    }

_altaris_ensure_dbus

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
    _altaris_log "Installing XFCE4 (targeted list — no xfce4 metapackage first)..."
    if ! _altaris_retry_pkg apt-get install -y --no-install-recommends \
        xfce4-session xfce4-panel xfce4-settings xfce4-terminal \
        xfdesktop4 xfwm4 thunar \
        adwaita-icon-theme fonts-dejavu-core \
        libgl1-mesa-dri libegl1 mesa-utils; then
        _altaris_log "targeted XFCE install failed — falling back to xfce4 metapackage"
        _altaris_retry_pkg apt-get install -y --no-install-recommends xfce4 || {
            echo "Altaris: XFCE install failed after 3 tries — check your network, then tap Retry Setup."
            exit 1
        }
    fi
    # Phone niceties: Whisker panel menu + screenshot tool (best-effort).
    apt-get install -y --no-install-recommends \
        xfce4-whiskermenu-plugin xfce4-screenshooter 2>/dev/null || true
    # Phone status indicators: battery + network-load panel plugins
    # (best-effort; the panel writer adds them only when the .so exists).
    DEBIAN_FRONTEND=noninteractive apt install -y --no-install-recommends \
        xfce4-battery-plugin xfce4-netload-plugin 2>/dev/null || true
else
    # Desktop-only graphics payload: keep terminal-only installs lean.
    if [ "$DESKTOPS" != "none" ]; then
        apt-get install -y --no-install-recommends \
            adwaita-icon-theme fonts-dejavu-core \
            libgl1-mesa-dri libegl1 mesa-utils 2>/dev/null || true
    fi
fi

_altaris_ensure_en_us_locale || exit 1

if [ -z "${DESKTOPS##*xfce*}" ]; then
    _altaris_require_startxfce4
fi
_altaris_ensure_groups
_altaris_ensure_user
_altaris_ensure_sudo
_altaris_ensure_home
_altaris_setup_pulse
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

echo "Altaris: ${DISTRO_NAME} Deepin setup complete!"
exit 0
