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
# Degraded standalone run without the shared helpers: plain install (no retry).
command -v _flux_retry_pkg >/dev/null 2>&1 || _flux_retry_pkg() { "$@"; }

# setup_rpm_family.sh — Rocky Linux 9 / CentOS Stream 9 base + XFCE (proot).
# ONE shared RPM-family (dnf/EPEL) setup for both EL9 cards; the os-release ID
# below only picks the log wording. Must run as root inside the guest.

DISTRO_NAME="${1:-}"
if [ -z "$DISTRO_NAME" ] && [ -r /etc/os-release ]; then
    DISTRO_NAME=$( ( . /etc/os-release 2>/dev/null; printf '%s' "${PRETTY_NAME:-}" ) )
fi
[ -n "$DISTRO_NAME" ] || DISTRO_NAME="RPM Linux"

echo "Bliss: Configuring ${DISTRO_NAME} (RPM Family)..."

if [ "$(id -u)" -ne 0 ]; then
    echo "Bliss: ERROR: must run as root inside the guest (got uid=$(id -u))."
    exit 1
fi

# os-release ID: rocky | centos (Stream 9). Unknown IDs only warn — the setup
# below is stock EL9 and stays safe on relatives (RHEL / AlmaLinux / etc.).
OS_ID=""
if [ -r /etc/os-release ]; then
    OS_ID=$( ( . /etc/os-release 2>/dev/null; printf '%s' "${ID:-}" ) )
fi
case "$OS_ID" in
    rocky)
        echo "Bliss: Rocky Linux detected (EL9)"
        ;;
    centos)
        echo "Bliss: CentOS Stream detected (EL9)"
        ;;
    *)
        echo "Bliss: WARNING: unexpected os-release ID '${OS_ID:-unknown}' — continuing with the EL9 setup"
        ;;
esac

export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin${PATH:+:$PATH}"
unset LD_LIBRARY_PATH
unset LD_PRELOAD

_flux_ensure_tmp
_flux_ensure_dns
_flux_disable_guest_selinux

DNF="dnf"
if command -v dnf5 >/dev/null 2>&1; then
    DNF="dnf5"
fi
if ! command -v "$DNF" >/dev/null 2>&1; then
    echo "Bliss: ERROR: dnf/dnf5 not found"
    exit 1
fi

unset LC_ALL
export LANG=C
# Scriptlets (gtk-update-icon-cache, udev, systemd) hang under proot.
export SYSTEMD_OFFLINE=1
export SYSTEMD_SKIP_UNMOUNTS=1
DNF_OPTS="-y --setopt=install_weak_deps=False --setopt=tsflags=nodocs,noscripts"

# Container images omit the OpenSSL default bundle symlink. Restore before
# dnf talks to metalink HTTPS.
if [ ! -e /etc/pki/tls/cert.pem ] && [ -f /etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem ]; then
    mkdir -p /etc/pki/tls/certs /etc/ssl
    ln -sfn /etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem /etc/pki/tls/cert.pem
    ln -sfn /etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem /etc/pki/tls/certs/ca-bundle.crt
    ln -sfn /etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem /etc/ssl/cert.pem
fi

_flux_log "dnf makecache ($DNF)..."
_flux_retry_pkg $DNF -y --setopt=install_weak_deps=False makecache || {
    echo "Bliss: dnf makecache failed (network / repos)"
    exit 1
}

_flux_log "Installing base packages..."
_flux_retry_pkg $DNF $DNF_OPTS install \
    bash sudo shadow-utils util-linux passwd ca-certificates curl wget unzip tar \
    gzip xz tzdata \
    dbus \
    || {
        echo "Bliss: base package install failed"
        exit 1
    }
# dbus-daemon / dbus-x11 subpackages vary across EL9 images — best-effort.
$DNF $DNF_OPTS install dbus-daemon dbus-x11 2>/dev/null || true

_flux_ensure_dbus

# Resolve desktop choice before graphics so a terminal base skips Mesa/icons.
DESKTOPS="${FLUX_DESKTOPS:-${FLUX_DESKTOP:-${FLUX_DESKTOP_ENV:-xfce}}}"
DESKTOPS=$(printf '%s' "$DESKTOPS" | tr ',' ' ')
DESKTOPS=$(printf '%s' "$DESKTOPS" | tr -s '[:space:]' ' ')
case " $DESKTOPS " in *" xfce "*|*" xfce4 "*) _norm="xfce" ;; *) _norm="" ;; esac
case " $DESKTOPS " in *" kde "*) _norm="${_norm}${_norm:+ }kde" ;; esac
[ -n "$_norm" ] || _norm="none"
DESKTOPS="$_norm"
unset _norm

# Shared fonts, GTK schemas + Mesa — desktop-only payload.
if [ "$DESKTOPS" != "none" ]; then
    _flux_retry_pkg $DNF $DNF_OPTS install \
        adwaita-icon-theme dejavu-sans-fonts \
        gsettings-desktop-schemas \
        mesa-dri-drivers mesa-libGL mesa-libEGL \
        || {
            echo "Bliss: base graphics package install failed"
            exit 1
        }
fi

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

# KDE is not delivered for the EL9 cards (no kde_plasma component ships for
# Rocky / CentOS Stream). XFCE still installs when both were requested.
if [ -z "${DESKTOPS##*kde*}" ]; then
    echo "Bliss: KDE is not available for this distro — skipping"
fi

if [ -z "${DESKTOPS##*xfce*}" ]; then
    _flux_log "Installing XFCE4 (EPEL)..."
    # EPEL carries the Xfce stack on EL9. Retry the repo package so a flaky
    # link cannot abort the desktop install.
    if ! _flux_retry_pkg $DNF $DNF_OPTS install epel-release; then
        echo "Bliss: WARNING: epel-release install failed — XFCE packages may be unavailable"
    fi
    # Import the EPEL key explicitly: scriptlets are skipped (tsflags=noscripts)
    # and dnf only auto-imports keys with -y on some versions.
    for _key in /etc/pki/rpm-gpg/RPM-GPG-KEY-EPEL-9 /etc/pki/rpm-gpg/RPM-GPG-KEY-EPEL-10; do
        [ -f "$_key" ] && rpm --import "$_key" 2>/dev/null || true
    done
    unset _key
    # CRB (CodeReady Builder) provides Xfce dependencies on EL9: disabled by
    # default on Rocky, enabled by default on CentOS Stream. The command is
    # idempotent and tolerant (missing plugin / repo id → no-op).
    _flux_retry_pkg $DNF $DNF_OPTS install dnf-plugins-core 2>/dev/null || true
    $DNF config-manager --set-enabled crb 2>/dev/null || true

    XFCE_OK=0
    if _flux_retry_pkg $DNF $DNF_OPTS groupinstall "Xfce"; then
        XFCE_OK=1
    fi
    if [ "$XFCE_OK" -ne 1 ]; then
        echo "Bliss: Xfce group unavailable — trying the @xfce group id"
        if _flux_retry_pkg $DNF $DNF_OPTS install @xfce; then
            XFCE_OK=1
        fi
    fi
    if [ "$XFCE_OK" -ne 1 ]; then
        echo "Bliss: Xfce group unavailable — installing the explicit package set"
        if _flux_retry_pkg $DNF $DNF_OPTS install \
            xfce4-session xfce4-panel xfce4-settings xfce4-terminal \
            xfdesktop xfwm4 thunar; then
            XFCE_OK=1
        fi
    fi
    if [ "$XFCE_OK" -ne 1 ]; then
        echo "Bliss: XFCE package install failed after 3 tries — check your network, then tap Retry Setup."
        exit 1
    fi
    unset XFCE_OK
    # Phone niceties: Whisker panel menu + screenshot tool (best-effort).
    $DNF $DNF_OPTS install xfce4-whiskermenu-plugin xfce4-screenshooter 2>/dev/null || true
    # Phone status indicators: battery + network-load panel plugins
    # (best-effort; the panel writer adds them only when the .so exists).
    $DNF $DNF_OPTS install xfce4-battery-plugin xfce4-netload-plugin 2>/dev/null || true
fi
if [ "$DESKTOPS" != "none" ]; then
$DNF $DNF_OPTS install mesa-vulkan-drivers 2>/dev/null || true
$DNF $DNF_OPTS install gdk-pixbuf2-modules librsvg2 2>/dev/null || true

# Rebuild caches skipped by tsflags=noscripts; replace bwrap so GTK/glycin
# SVG/PNG loaders work under proot (no user namespaces).
gdk-pixbuf-query-loaders-64 --update-cache 2>/dev/null \
    || gdk-pixbuf-query-loaders --update-cache 2>/dev/null || true
command -v glib-compile-schemas >/dev/null 2>&1 && \
    glib-compile-schemas /usr/share/glib-2.0/schemas 2>/dev/null || true
command -v update-mime-database >/dev/null 2>&1 && update-mime-database /usr/share/mime 2>/dev/null || true
if [ -x /usr/bin/bwrap ] && [ ! -e /usr/bin/bwrap.real ] && \
   ! grep -q 'Bliss proot' /usr/bin/bwrap 2>/dev/null; then
    mv /usr/bin/bwrap /usr/bin/bwrap.real 2>/dev/null || true
fi
cat > /usr/bin/bwrap <<'BWRAP_EOF'
#!/bin/sh
# Bliss: exec the real glycin loader, not an earlier --ro-bind source.
while [ $# -gt 0 ]; do
  case "$1" in
    /usr/libexec/glycin-loaders/*|/usr/lib/glycin-loaders/*|/usr/bin/true|/bin/true)
      if [ -f "$1" ] && [ -x "$1" ]; then
        exec "$@"
      fi
      ;;
  esac
  shift
done
echo "bwrap-shim: no command" >&2
exit 127
BWRAP_EOF
chmod 755 /usr/bin/bwrap
if [ -f /tmp/bwrap-proot-shim ] && [ "$(stat -c %s /tmp/bwrap-proot-shim 2>/dev/null || echo 0)" -gt 20000 ]; then
    cp -f /tmp/bwrap-proot-shim /usr/bin/bwrap
    chmod 755 /usr/bin/bwrap
fi
fi

# Container image ships only C.utf8. Launchers export en_US.UTF-8.
$DNF $DNF_OPTS install glibc-langpack-en || true
if ! ls /usr/lib/locale 2>/dev/null | grep -qi 'en_US'; then
    localedef -i en_US -f UTF-8 en_US.UTF-8 || true
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

# Terminal-base polish shared with the other families. The helper ships in the
# customization scripts, not in this payload — guarded no-op when absent.
if command -v _flux_write_folio_banner >/dev/null 2>&1; then
    _flux_write_folio_banner /home/flux 2>/dev/null || true
    _flux_write_folio_banner /root 2>/dev/null || true
fi

# Phone-compact XFCE defaults (30px mono top panel, DPI 96, wallpaper,
# Home-only desktop icons, shortcuts). Helper ships in bliss_guest_common.sh;
# guarded so a standalone run without it is a no-op and never fails install.
if [ -z "${DESKTOPS##*xfce*}" ] && command -v _flux_xfce_compact_defaults >/dev/null 2>&1; then
    _flux_xfce_compact_defaults || true
fi

echo "Bliss: ${DISTRO_NAME} RPM setup complete!"
exit 0
