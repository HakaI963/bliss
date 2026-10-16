#!/bin/sh

# Helpers live in flux_guest_common.sh (prepended by flux_install /
# familySetupPayload). Source it only when this file is run standalone.
if ! command -v _flux_setup_pulse >/dev/null 2>&1; then
    _here=$(CDPATH= cd -- "$(dirname -- "$0")" 2>/dev/null && pwd)
    for _common in \
        "${_here}/flux_guest_common.sh" \
        "${HOME:-}/flux_guest_common.sh" \
        /tmp/flux_guest_common.sh
    do
        if [ -f "$_common" ]; then
            # shellcheck source=/dev/null
            . "$_common"
            break
        fi
    done
fi

# setup_ubuntu_family.sh — Ubuntu 26.04 LTS (Resolute) XFCE (proot + chroot).
# Debian-LIKE, not Debian: rewrite mirrors to ports.ubuntu.com, NEVER add debian.org.
# Never install ubuntu-desktop / do-release-upgrade / PPAs.

DISTRO_NAME="${1:-ubuntu}"
echo "Bliss: Configuring ${DISTRO_NAME} (Ubuntu Family)..."

if [ "$(id -u)" -ne 0 ]; then
    echo "Bliss: ERROR: must run as root inside the guest (got uid=$(id -u))."
    exit 1
fi

unset LC_ALL
export LANG=C

_flux_ensure_tmp
_flux_ensure_dns
_flux_disable_guest_selinux
_flux_apt_prep

if ! command -v apt-get >/dev/null 2>&1; then
    echo "Bliss: ERROR: apt-get not found"
    exit 1
fi

# Rewrite amd64 archive/security URIs to Ubuntu Ports (this rootfs is arm64).
_flux_rewrite_ubuntu_mirrors() {
    _flux_log "Rewriting Ubuntu sources to ports.ubuntu.com/ubuntu-ports"
    for f in /etc/apt/sources.list /etc/apt/sources.list.d/*; do
        [ -f "$f" ] || continue
        _tmp="${f}.fluxnew.$$"
        # POSIX: no GNU sed required (uutils + gnu-coreutils both present, stay portable).
        while IFS= read -r _ln || [ -n "$_ln" ]; do
            _ln=$(printf '%s' "$_ln" | sed \
                -e 's|http://archive.ubuntu.com/ubuntu/|http://ports.ubuntu.com/ubuntu-ports|g' \
                -e 's|http://archive.ubuntu.com/ubuntu|http://ports.ubuntu.com/ubuntu-ports|g' \
                -e 's|http://security.ubuntu.com/ubuntu/|http://ports.ubuntu.com/ubuntu-ports|g' \
                -e 's|http://security.ubuntu.com/ubuntu|http://ports.ubuntu.com/ubuntu-ports|g' \
                -e 's|https://archive.ubuntu.com/ubuntu/|http://ports.ubuntu.com/ubuntu-ports|g' \
                -e 's|https://security.ubuntu.com/ubuntu/|http://ports.ubuntu.com/ubuntu-ports|g')
            printf '%s\n' "$_ln"
        done < "$f" > "$_tmp" && mv -f "$_tmp" "$f"
    done
}

_flux_rewrite_ubuntu_mirrors

_flux_log "apt-get update..."
if ! apt-get update; then
    _flux_log "apt-get update failed once — retrying (ports.ubuntu.com only)"
    apt-get update || {
        echo "Bliss: apt-get update failed (repos/network)"
        exit 1
    }
fi

_flux_log "Installing base packages..."
apt-get install -y --no-install-recommends \
    bash sudo passwd adduser ca-certificates curl wget unzip tar xz-utils \
    git zsh python3 locales \
    dbus dbus-x11 || {
        echo "Bliss: base package install failed"
        exit 1
    }

_flux_ensure_dbus

# Desktop contract: FLUX_DESKTOPS (space/comma separated) = xfce | kde | none;
# legacy FLUX_DESKTOP / FLUX_DESKTOP_ENV remain valid single values. Unset/empty
# → xfce (backward compat). Normalized to the canonical "xfce kde" order;
# unknown tokens are ignored and "none" installs neither desktop.
DESKTOPS="${FLUX_DESKTOPS:-${FLUX_DESKTOP:-${FLUX_DESKTOP_ENV:-xfce}}}"
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
    _flux_log "Installing XFCE4 (targeted list — no xfce4 / ubuntu-desktop metapackage first)..."
    if ! _flux_retry_pkg apt-get install -y --no-install-recommends \
        xfce4-session xfce4-panel xfce4-settings xfce4-terminal \
        xfdesktop4 xfwm4 thunar \
        adwaita-icon-theme fonts-dejavu-core \
        libgl1-mesa-dri libegl1 mesa-utils; then
        _flux_log "targeted XFCE install failed — falling back to xfce4 metapackage"
        _flux_retry_pkg apt-get install -y --no-install-recommends xfce4 || {
            echo "Bliss: XFCE install failed after 3 tries — check your network, then tap Retry Setup."
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
    # Shared fonts + Mesa for non-XFCE desktops (best-effort).
    apt-get install -y --no-install-recommends \
        adwaita-icon-theme fonts-dejavu-core \
        libgl1-mesa-dri libegl1 mesa-utils 2>/dev/null || true
fi

_flux_ensure_en_us_locale || exit 1
if [ -z "${DESKTOPS##*xfce*}" ]; then
    _flux_require_startxfce4
fi
_flux_ensure_groups
_flux_ensure_user
_flux_ensure_sudo
_flux_ensure_home
_flux_setup_pulse
_flux_write_gpu_mode virgl
_flux_fix_pm_writable

# Phone-compact XFCE defaults (30px mono top panel, DPI 96, wallpaper,
# Home-only desktop icons, shortcuts). Helper ships in flux_guest_common.sh;
# guarded so a standalone run without it is a no-op and never fails install.
if [ -z "${DESKTOPS##*xfce*}" ] && command -v _flux_xfce_compact_defaults >/dev/null 2>&1; then
    _flux_xfce_compact_defaults || true
fi

echo "Bliss: ${DISTRO_NAME} Ubuntu setup complete!"
exit 0
