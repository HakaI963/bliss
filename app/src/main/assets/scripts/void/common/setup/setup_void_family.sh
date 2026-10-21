#!/bin/sh

# Helpers live in bliss_guest_common.sh (prepended by bliss_install /
# familySetupPayload). Source it only when this file is run standalone.
if ! command -v _flux_setup_pulse >/dev/null 2>&1; then
    _here=$(CDPATH= cd -- "$(dirname -- "$0")" 2>/dev/null && pwd)
    for _common in \
        "${_here}/bliss_guest_common.sh" \
        "${HOME:-}/bliss_guest_common.sh" \
        /tmp/bliss_guest_common.sh
    do
        if [ -f "$_common" ]; then
            # shellcheck source=/dev/null
            . "$_common"
            break
        fi
    done
fi

# setup_void_family.sh — Void Linux glibc aarch64 XFCE (proot + chroot). xbps.

DISTRO_NAME="${1:-void}"
echo "Bliss: Configuring ${DISTRO_NAME} (Void Family)..."

if [ "$(id -u)" -ne 0 ]; then
    echo "Bliss: ERROR: must run as root inside the guest (got uid=$(id -u))."
    exit 1
fi


_flux_ensure_tmp
_flux_ensure_dns

if ! command -v xbps-install >/dev/null 2>&1; then
    echo "Bliss: ERROR: xbps-install not found"
    exit 1
fi

# Never use musl repos on this glibc aarch64 rootfs
mkdir -p /usr/share/xbps.d /etc/xbps.d
if [ ! -s /usr/share/xbps.d/xbps-arch.conf ]; then
    echo "architecture=aarch64" > /usr/share/xbps.d/xbps-arch.conf
fi
if ! grep -q 'current/aarch64' /usr/share/xbps.d/00-repository-main.conf 2>/dev/null \
    && ! grep -q 'current/aarch64' /etc/xbps.d/*.conf 2>/dev/null; then
    echo "repository=https://repo-default.voidlinux.org/current/aarch64" \
        > /usr/share/xbps.d/00-repository-main.conf
fi

unset LC_ALL
export LANG=C
export SYSTEMD_OFFLINE=1

_flux_log "xbps-install sync..."
xbps-install -Sy || {
    echo "Bliss: xbps sync failed (network / repos)"
    exit 1
}
# Rolling Void often requires updating xbps itself before any other transaction.
# Do NOT -Syu the whole system (huge/slow under proot).
_flux_log "Updating xbps package manager if needed..."
xbps-install -yu xbps || true

_flux_log "Installing base packages..."
# Ignore already-installed names (xbps treats them as errors if mixed).
for _p in bash sudo shadow ca-certificates curl wget unzip dbus; do
    xbps-install -y "$_p" 2>/dev/null || true
done
xbps-install -y dbus-x11 2>/dev/null || true

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
    # Minimal XFCE only — do NOT install the `xfce4` metapackage (pulls ffmpeg,
    # gstreamer plugins, parole, … hundreds of extras under proot/chroot).
    _flux_log "Installing XFCE4 (minimal session)..."
    if ! _flux_retry_pkg xbps-install -y \
        xfce4-session xfce4-panel xfce4-settings xfce4-terminal \
        xfdesktop xfwm4; then
        echo "Bliss: XFCE package install failed after 3 tries — check your network, then tap Retry Setup."
        exit 1
    fi
    xbps-install -y Thunar 2>/dev/null || xbps-install -y thunar 2>/dev/null || true
    # Phone niceties: Whisker panel menu + screenshot tool (best-effort).
    xbps-install -y xfce4-whiskermenu-plugin xfce4-screenshooter 2>/dev/null || true
    # Phone status indicators: battery + network-load panel plugins
    # (best-effort; the panel writer adds them only when the .so exists).
    xbps-install -y xfce4-battery-plugin xfce4-netload-plugin 2>/dev/null || true
fi
# Shared fonts + Mesa — desktop-only payload (best-effort).
if [ "$DESKTOPS" != "none" ]; then
    xbps-install -y adwaita-icon-theme dejavu-fonts-ttf mesa mesa-dri 2>/dev/null || true
fi

if ! ls /usr/lib/locale 2>/dev/null | grep -qi 'en_US'; then
    xbps-install -y glibc-locales 2>/dev/null || true
fi
printf 'LANG=en_US.UTF-8\n' > /etc/locale.conf
if command -v _flux_ensure_en_us_locale >/dev/null 2>&1; then
    _flux_ensure_en_us_locale || exit 1
fi

if [ -z "${DESKTOPS##*xfce*}" ]; then
    _flux_require_startxfce4
fi
_flux_ensure_user
_flux_ensure_sudo
_flux_ensure_home
_flux_setup_pulse
_flux_write_gpu_mode virgl
_flux_fix_pm_writable

# Phone-compact XFCE defaults (30px mono top panel, DPI 96, wallpaper,
# Home-only desktop icons, shortcuts). Helper ships in bliss_guest_common.sh;
# guarded so a standalone run without it is a no-op and never fails install.
if [ -z "${DESKTOPS##*xfce*}" ] && command -v _flux_xfce_compact_defaults >/dev/null 2>&1; then
    _flux_xfce_compact_defaults || true
fi

echo "Bliss: ${DISTRO_NAME} Void setup complete!"
exit 0
