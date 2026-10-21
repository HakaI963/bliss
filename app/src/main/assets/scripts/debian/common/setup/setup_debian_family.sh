#!/bin/bash
# setup_debian_family.sh
# Generic Post-install configuration for Debian-based Distros (Debian, Ubuntu, Kali, etc.)

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

DISTRO_NAME="${1:-debian}"

echo "Bliss: Configuring ${DISTRO_NAME} (Debian Family)..."

# 1. Update and Install Core Packages
export DEBIAN_FRONTEND=noninteractive
apt update -y || exit 1
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

# tigervnc/dbus-x11 are GUI infrastructure. A terminal base installs only
# the shell/user plumbing, which removes a multi-hundred-MB download.
if [ "$DESKTOPS" = "none" ]; then
    apt install -y --no-install-recommends sudo || exit 1
else
    apt install -y sudo dbus-x11 tigervnc-standalone-server || exit 1
fi
if [ -z "${DESKTOPS##*xfce*}" ]; then
    # Targeted list + --no-install-recommends: the xfce4 goodies metapackage
    # pulls document viewers, games and extra daemons we do not ship.
    if ! _flux_retry_pkg apt-get install -y --no-install-recommends \
        xfce4-session xfce4-panel xfce4-settings xfce4-terminal \
        xfdesktop4 xfwm4 thunar \
        adwaita-icon-theme fonts-dejavu-core \
        libgl1-mesa-dri libegl1 mesa-utils; then
        echo "Bliss: XFCE package install failed after 3 tries — check your network, then tap Retry Setup."
        exit 1
    fi
    # Phone niceties: Whisker panel menu + screenshot tool (best-effort).
    DEBIAN_FRONTEND=noninteractive apt install -y --no-install-recommends \
        xfce4-whiskermenu-plugin xfce4-screenshooter 2>/dev/null || true
    # Phone status indicators: battery + network-load panel plugins
    # (best-effort; the panel writer adds them only when the .so exists).
    DEBIAN_FRONTEND=noninteractive apt install -y --no-install-recommends \
        xfce4-battery-plugin xfce4-netload-plugin 2>/dev/null || true
fi
# Named Pulse *client* only — do not treat xfce4 as the audio path.
if [ "$DESKTOPS" != "none" ]; then
    DEBIAN_FRONTEND=noninteractive apt install -y --no-install-recommends \
        libpulse0 pulseaudio-utils || true
    if [ -z "${DESKTOPS##*xfce*}" ]; then
        DEBIAN_FRONTEND=noninteractive apt install -y --no-install-recommends \
            xfce4-pulseaudio-plugin || true
    fi

    # Stock Mesa for first XFCE paint. hw-accel may replace this with Turnip later.
    DEBIAN_FRONTEND=noninteractive apt install -y --no-install-recommends \
        mesa-utils libgl1-mesa-dri libegl1 mesa-vulkan-drivers \
        || DEBIAN_FRONTEND=noninteractive apt install -y --no-install-recommends \
            mesa-utils libgl1-mesa-dri libegl1 \
        || true
fi
[ -d /etc/fluxlinux ] && [ ! -e /etc/bliss ] && mv /etc/fluxlinux /etc/bliss 2>/dev/null || true
mkdir -p /etc/bliss
printf '%s\n' virgl > /etc/bliss/gpu_mode
echo "Bliss: gpu_mode=virgl (first-paint; hw-accel may upgrade)"

# 2. Create User 'flux'
if ! id "flux" &>/dev/null; then
    useradd -m -s /bin/bash flux
    echo "flux:flux" | chpasswd
    usermod -aG sudo flux
fi

# 3. Configure Sudo
echo "flux ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/flux

# 4. Configure VNC for User (session command follows FLUX_DESKTOPS)
mkdir -p /home/flux/.vnc
_xs_de="startxfce4"
if [ -z "${DESKTOPS##*kde*}" ] && [ -n "${DESKTOPS##*xfce*}" ]; then
    _xs_de="startplasma-x11"
fi
cat > /home/flux/.vnc/xstartup <<EOF
#!/bin/bash
export PULSE_SERVER=tcp:127.0.0.1
xrdb \$HOME/.Xresources
exec $_xs_de
EOF
unset _xs_de
chmod +x /home/flux/.vnc/xstartup
chown -R flux:flux /home/flux/.vnc

if command -v _flux_write_pulse_client >/dev/null 2>&1; then
    _flux_write_pulse_client
else
    mkdir -p /etc/profile.d /etc/pulse/client.conf.d
    printf 'export PULSE_SERVER=tcp:127.0.0.1\n' > /etc/profile.d/flux-pulse.sh
    chmod 644 /etc/profile.d/flux-pulse.sh 2>/dev/null || true
    if [ -f /etc/environment ]; then
        if grep -q '^PULSE_SERVER=' /etc/environment 2>/dev/null; then
            sed -i 's|^PULSE_SERVER=.*|PULSE_SERVER=tcp:127.0.0.1|' /etc/environment
        else
            printf 'PULSE_SERVER=tcp:127.0.0.1\n' >> /etc/environment
        fi
    else
        printf 'PULSE_SERVER=tcp:127.0.0.1\n' > /etc/environment
    fi
    cat > /etc/pulse/client.conf.d/99-bliss.conf <<'EOF'
default-server = tcp:127.0.0.1
autospawn = no
EOF
    if [ -f /etc/pulse/client.conf ]; then
        if grep -qE '^;?[[:space:]]*default-server' /etc/pulse/client.conf 2>/dev/null; then
            sed -i 's/^;*[[:space:]]*default-server.*/default-server = tcp:127.0.0.1/' /etc/pulse/client.conf
        else
            printf '\ndefault-server = tcp:127.0.0.1\n' >> /etc/pulse/client.conf
        fi
        if grep -qE '^;?[[:space:]]*autospawn' /etc/pulse/client.conf 2>/dev/null; then
            sed -i 's/^;*[[:space:]]*autospawn.*/autospawn = no/' /etc/pulse/client.conf
        else
            printf 'autospawn = no\n' >> /etc/pulse/client.conf
        fi
    fi
fi

# Phone-compact XFCE defaults (30px mono top panel, DPI 96, wallpaper,
# Home-only desktop icons, shortcuts). Helper ships in bliss_guest_common.sh;
# guarded so a standalone run without it is a no-op and never fails install.
if [ -z "${DESKTOPS##*xfce*}" ] && command -v _flux_xfce_compact_defaults >/dev/null 2>&1; then
    _flux_xfce_compact_defaults || true
fi

echo "Bliss: ${DISTRO_NAME} Setup Complete!"
