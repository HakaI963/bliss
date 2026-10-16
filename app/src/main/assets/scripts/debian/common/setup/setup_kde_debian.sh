#!/bin/bash
# setup_kde_debian.sh
# Base post-install configuration for KDE Plasma on Debian-based distros (PRoot / Chroot)
# Mirrors what setup_debian_family.sh does, but installs KDE Plasma instead of XFCE4
# Usage: setup_kde_debian.sh <distro_name> [uninstall]

# KDE applications on top of the base Plasma stack. Everything the session
# actually needs is in BASE_PKGS below — this component is self-sufficient and
# does NOT assume the XFCE component ran first (the user may pick KDE only).
#
# Package names are Debian trixie / Plasma 6 (verified against the trixie
# arm64 index — both the `debian` proot and `debian13_chroot` cards ship
# debian_13_rootfs.tar.xz):
#   spectacle      → kde-spectacle   (renamed in Plasma 6)
#   kwallet-pam    → libpam-kwallet5 (renamed in Plasma 6)
# A wrong name makes `apt install` exit 100, which aborts the whole component.
PKGS=(
    kde-standard
    kde-spectacle
    libpam-kwallet5
    qt5ct
    systemsettings
    plasma-discover
    kinfocenter
    okular
    gwenview
    kcalc
    kwrite
    kfind
    filelight
    partitionmanager
)

# Shared system stack the KDE session needs just like XFCE does (X server, VNC,
# D-Bus session bus, sudo for the flux user). Installed here too so a KDE-only
# install works even when setup_debian_family.sh never ran.
BASE_PKGS=(
    sudo
    xorg
    xauth
    dbus-x11
    tigervnc-standalone-server
    sddm
)

# Release-varying extras: installed best-effort so a package that only exists
# on some Debian releases (qt5-style-plugins: bookworm, dropped in trixie)
# can never fail the component.
EXTRA_PKGS=(
    qt5-style-plugins
)

# Teardown removes the KDE/Plasma packages only. xorg / dbus-x11 /
# tigervnc-standalone-server / sddm are shared with XFCE and stay installed.
PURGE_PKGS=(
    kde-standard
    kde-spectacle
    libpam-kwallet5
    qt5ct
    systemsettings
    plasma-discover
    kinfocenter
    okular
    gwenview
    kcalc
    kwrite
    kfind
    filelight
    partitionmanager
)

# ─── UNINSTALL MODE ──────────────────────────────────────────────────────
# Detect uninstall as either first or second argument (since DISTRO_NAME=$1 may come first).
if [ "$1" = "uninstall" ] || [ "$2" = "uninstall" ]; then
    DISTRO_NAME="${1:-debian}"
    [ "$1" = "uninstall" ] && DISTRO_NAME="debian"

    export DEBIAN_FRONTEND=noninteractive
    echo "Bliss: Uninstalling KDE Plasma Environment..."

    apt remove -y --purge "${PURGE_PKGS[@]}" 2>/dev/null || true
    apt autoremove -y 2>/dev/null || true

    # Remove KDE-specific marker and runtime env helper only.
    # Never remove /home/flux/.vnc — that file belongs to the XFCE component too.
    rm -f /home/flux/.fluxlinux/kde_installed
    rm -f /usr/local/lib/fluxlinux/flux_kde_env.sh

    echo "Bliss: KDE Plasma Environment Uninstalled."
    exit 0
fi
# ─── END UNINSTALL MODE ──────────────────────────────────────────────────

DISTRO_NAME="${1:-debian}"
LOG_FILE="/tmp/fluxlinux_kde_install.log"

# ── Error Handler ────────────────────────────────────────────────────────
# Never block on stdin: component sessions (GuestSessionFactory) attach a pty,
# so "[ -t 0 ]" is true and a bare `read` hangs the terminal tab forever and
# the install never reports completion. Pauses are opt-in (FLUX_INTERACTIVE=1).
_flux_pause() {
    if [ "${FLUX_INTERACTIVE:-0}" = "1" ] && [ -t 0 ]; then
        read -p "$1"
    fi
}

handle_error() {
    local STEP="$1"
    echo ""
    echo "❌ Bliss KDE Error: Script failed at step: $STEP"
    echo "──────────────────────────────────────────────────────"
    echo "Log saved to: $LOG_FILE"
    echo ""
    echo "Please copy the log content and send it to the developer:"
    echo "  Email: abhay02delhi@gmail.com"
    echo "  GitHub: https://github.com/abhay-byte/fluxlinux/issues"
    echo "──────────────────────────────────────────────────────"
    _flux_pause "Press Enter to acknowledge error and exit..."

    exit 1
}

# ── Required-package retry (flaky downloads abort xbps/dnf/apt mid-fetch) ──
# The shared helpers (_flux_retry_pkg in flux_guest_common.sh) are not
# prepended to this standalone component, so carry the same guarded helper.
if ! command -v _flux_pm_refresh >/dev/null 2>&1; then
    _flux_pm_refresh() {
        if command -v xbps-install >/dev/null 2>&1; then
            xbps-install -Sy
        elif command -v apk >/dev/null 2>&1; then
            apk update
        elif command -v apt-get >/dev/null 2>&1; then
            apt-get update
        elif command -v dnf5 >/dev/null 2>&1; then
            dnf5 -y makecache
        elif command -v dnf >/dev/null 2>&1; then
            dnf -y makecache
        elif command -v zypper >/dev/null 2>&1; then
            zypper --non-interactive --gpg-auto-import-keys refresh
        elif command -v pacman >/dev/null 2>&1; then
            pacman -Sy --noconfirm
        else
            return 1
        fi
    }
fi
if ! command -v _flux_retry_pkg >/dev/null 2>&1; then
    _flux_retry_pkg() {
        _retry_tries=3
        _retry_n=1
        _retry_rc=0
        while [ "$_retry_n" -le "$_retry_tries" ]; do
            if "$@"; then
                _retry_rc=0
                break
            else
                _retry_rc=$?
            fi
            [ "$_retry_n" -lt "$_retry_tries" ] || break
            echo "Bliss: Package download failed — retrying ($((_retry_n + 1))/$_retry_tries)…"
            _flux_pm_refresh >/dev/null 2>&1 || true
            _retry_n=$((_retry_n + 1))
        done
        unset _retry_tries _retry_n
        return "$_retry_rc"
    }
fi

# Setup scripts must run as root inside proot/chroot (apt/dpkg).
if [ "$(id -u)" -ne 0 ]; then
    echo "Bliss: ERROR: must run as root inside the guest (got uid=$(id -u))."
    echo "Bliss: Component sessions should use --user root; re-run from Distro Settings."
    handle_error "Not Root"
fi

echo "Bliss: Configuring ${DISTRO_NAME} with KDE Plasma..."

# Redirect all output to log
exec > >(tee -a "$LOG_FILE") 2>&1

export DEBIAN_FRONTEND=noninteractive

# 1. Update and install KDE Plasma core + the shared base stack
echo "[1/7] Updating package lists..."
apt update -y || handle_error "apt update"

echo "[2/7] Installing KDE Plasma desktop (full suite)..."
# kde-standard: meta-package for the complete KDE Plasma 6 suite on Debian Trixie
# Includes: plasmashell, kwin, systemsettings, dolphin, konsole, kate, ark,
#           gwenview, okular, spectacle, kcalc, kscreen, kinfocenter, and more.
# Recommends stay enabled — that is what makes the desktop actually usable.
if ! _flux_retry_pkg apt install -y "${PKGS[@]}" "${BASE_PKGS[@]}"; then
    echo "Bliss: Package install failed after 3 tries — check your network, then tap Retry Setup."
    handle_error "KDE Plasma package installation"
fi

# 2. Install useful KDE apps + optional packages (non-critical, failures are tolerated)
echo "[3/7] Installing KDE extras..."
apt install -y --no-install-recommends "${EXTRA_PKGS[@]}" 2>/dev/null || true

# 3. Create user 'flux' (idempotent — setup_debian_family.sh may have made it)
echo "[4/7] Creating user flux..."
if ! id "flux" &>/dev/null; then
    useradd -m -s /bin/bash flux || handle_error "Create user flux"
    echo "flux:flux" | chpasswd
    usermod -aG sudo flux 2>/dev/null || true
fi

# 4. Configure Sudo NOPASSWD (mkdir first — /etc/sudoers.d needs the sudo pkg,
#    and a KDE-only install may not have created it yet). rm -f keeps re-runs of
#    this component working (sudoers.d files are mode 0440 → unwritable).
echo "[5/7] Configuring sudo..."
mkdir -p /etc/sudoers.d || handle_error "Configure sudo"
rm -f /etc/sudoers.d/flux 2>/dev/null || true
echo "flux ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/flux || handle_error "Configure sudo"
chmod 440 /etc/sudoers.d/flux 2>/dev/null || true

# 5. Configure VNC for KDE (xstartup)
echo "[6/7] Setting up KDE session launcher..."
mkdir -p /home/flux/.vnc
cat > /home/flux/.vnc/xstartup << 'VNCEOF'
#!/bin/bash
export PULSE_SERVER=127.0.0.1
export DBUS_SESSION_BUS_ADDRESS=$(dbus-launch --print-address)
xrdb $HOME/.Xresources 2>/dev/null
exec startplasma-x11
VNCEOF
chmod +x /home/flux/.vnc/xstartup
chown -R flux:flux /home/flux/.vnc

# Compositing + effects off system-wide by default (XFCE parity; GPU accel path).
# Per-user config is kept in sync at session start (flux_kde_env.sh).
mkdir -p /etc/xdg
[ -e /etc/xdg/kwinrc ] || cat > /etc/xdg/kwinrc << 'KWEOF'
[Compositing]
Enabled=false

[Plugins]
blurEnabled=false
contrastEnabled=false
slideEnabled=false
translucencyEnabled=false
kwin4_effect_translucencyEnabled=false
KWEOF
[ -e /etc/xdg/kdeglobals ] || printf '[KDE]\nAnimationDurationFactor=0\n' > /etc/xdg/kdeglobals

# 6. Disable SDDM (we use Termux:X11, not a display manager)
systemctl disable sddm 2>/dev/null || true

# 7. Mark as KDE-capable environment
echo "[7/7] Marking installation complete..."
mkdir -p /home/flux/.fluxlinux
touch /home/flux/.fluxlinux/kde_installed
chown -R flux:flux /home/flux/.fluxlinux

echo ""
echo "✅ Bliss: KDE Plasma base setup for ${DISTRO_NAME} complete!"
echo "Log saved at: $LOG_FILE"


exit 0
