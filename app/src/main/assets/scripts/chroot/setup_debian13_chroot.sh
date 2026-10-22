#!/bin/sh

# setup_debian13_chroot.sh
# Installs a Debian 13 (Trixie) Chroot environment (Requires Root)
# Based on LinuxDroidMaster/Termux-Desktops Guide

# Global Variables
DEBIANPATH="/data/local/altaris/chroots/debian"
USERNAME="altaris"
[ -d /data/local/altaris ] && [ ! -e /data/local/altaris ] && mv /data/local/altaris /data/local/altaris 2>/dev/null || true

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

# Same pinned rootfs as proot path (app assets → deploy to $HOME)
PKG="${TERMUX_APP__PACKAGE_NAME:-com.ivarna.fluxlinux}"
APP_HOME="${TERMUX__HOME:-/data/data/${PKG}/files/home}"
APP_PREFIX="${TERMUX__PREFIX:-/data/data/${PKG}/files/usr}"
ROOTFS_NAME="debian_13_rootfs.tar.xz"
ROOTFS_URL="${ALTARIS_ROOTFS_URL:-https://github.com/HakaI963/altaris/releases/download/rootfs/debian_13_rootfs.tar.xz}"
ROOTFS_SHA256="${ALTARIS_ROOTFS_SHA256:-13e29f6099c3b805e84694507ede460c03886ffb364c03317272691cf84e6803}"

# Function to show progress message
progress() {
    printf "\033[1;36m[+] %s\033[0m\n" "$1"
}

# Function to show success message
success() {
    printf "\033[1;32m[✓] %s\033[0m\n" "$1"
}

# Function to show error message
error() {
    printf "\033[1;31m[!] %s\033[0m\n" "$1"
}

# Cleanup function
cleanup_mounts() {
    printf "\033[1;36m[+] Safety Check: Unmounting filesystems...\033[0m\n"
    $BB umount "$DEBIANPATH/sdcard" 2>/dev/null || true
    $BB umount "$DEBIANPATH/mnt/host-tmp" 2>/dev/null || true
    $BB umount "$DEBIANPATH/mnt/termux-tmp" 2>/dev/null || true
    $BB umount "$DEBIANPATH/dev/shm" 2>/dev/null || true
    $BB umount "$DEBIANPATH/dev/pts" 2>/dev/null || true
    $BB umount "$DEBIANPATH/proc" 2>/dev/null || true
    $BB umount "$DEBIANPATH/sys" 2>/dev/null || true
    $BB umount "$DEBIANPATH/dev" 2>/dev/null || true
    $BB umount "$DEBIANPATH/tmp" 2>/dev/null || true
    return 0
}

# Exit handler
goodbye() {
    error "Something went wrong."
    cleanup_mounts
    error "Exiting..."
    exit 1
}

# Resolve app-packaged / manual / download rootfs (same file as altaris_install.sh)
resolve_rootfs_archive() {
    ROOTFS_ARCHIVE=""

    if [ -n "${ALTARIS_ROOTFS_PATH:-}" ] && [ -f "$ALTARIS_ROOTFS_PATH" ] && [ -s "$ALTARIS_ROOTFS_PATH" ]; then
        ROOTFS_ARCHIVE="$ALTARIS_ROOTFS_PATH"
        progress "rootfs from ALTARIS_ROOTFS_PATH=$ROOTFS_ARCHIVE"
        return 0
    fi

    for candidate in \
        "$APP_HOME/$ROOTFS_NAME" \
        "$APP_HOME/rootfs/$ROOTFS_NAME" \
        "$APP_PREFIX/var/lib/proot-distro/cache/rootfs/$ROOTFS_NAME" \
        "/sdcard/Download/$ROOTFS_NAME" \
        "/sdcard/Download/rootfs.tar.xz" \
        "/storage/emulated/0/Download/$ROOTFS_NAME" \
        "/storage/emulated/0/Download/rootfs.tar.xz" \
        "$DEBIANPATH/$ROOTFS_NAME" \
        "$DEBIANPATH/rootfs.tar.xz"
    do
        if [ -f "$candidate" ] && [ -s "$candidate" ]; then
            ROOTFS_ARCHIVE="$candidate"
            progress "rootfs found: $ROOTFS_ARCHIVE"
            return 0
        fi
    done

    return 1
}

# Mirror fallback for GitHub release assets: ISPs commonly block the
# release-assets.githubusercontent.com redirect while github.com resolves.
root_download_urls() {
    echo "$1"
    case "$1" in
        https://github.com/*)
            echo "https://ghfast.top/$1"
            echo "https://gh-proxy.com/$1"
            echo "https://ghproxy.net/$1"
            ;;
    esac
}

# Download Helper (fallback only — prefer app-local archive)
download_file() {
    # $1=dir $2=filename $3=url
    progress "Downloading file..."
    if [ -e "$1/$2" ] && [ -s "$1/$2" ]; then
        printf "\033[1;33m[!] File already exists: %s\033[0m\n" "$2"
        printf "\033[1;33m[!] Skipping download...\033[0m\n"
        return 0
    fi
    mkdir -p "$1" 2>/dev/null || true
    for _url in $(root_download_urls "$3"); do
        if [ "$_url" != "$3" ]; then
            progress "Trying mirror: $_url"
        fi
        rm -f "$1/$2" 2>/dev/null || true
        if command -v wget >/dev/null 2>&1; then
            wget -O "$1/$2" "$_url"
            if [ $? -eq 0 ] && [ -s "$1/$2" ]; then
                success "File downloaded successfully: $2"
                return 0
            fi
            error "Standard wget failed: $2."
        fi
        progress "Trying Busybox wget..."
        $BB wget -O "$1/$2" "$_url"
        if [ $? -eq 0 ] && [ -s "$1/$2" ]; then
            success "File downloaded successfully (Fallback)"
            return 0
        fi
        rm -f "$1/$2" 2>/dev/null || true
    done
    goodbye
}

# Extraction Helper — archive path is $2 (or $1/rootfs.tar.xz)
extract_file() {
    # $1=dest root  $2=optional archive path
    _dest="$1"
    _archive="${2:-$_dest/rootfs.tar.xz}"
    progress "Extracting file from $_archive ..."
    if [ -f "$_dest/bin/bash" ] || [ -e "$_dest/bin/sh" ]; then
        printf "\033[1;33m[!] Rootfs appears populated: %s/bin\033[0m\n" "$_dest"
        printf "\033[1;33m[!] Skipping extraction...\033[0m\n"
        return 0
    fi
    if [ ! -f "$_archive" ] || [ ! -s "$_archive" ]; then
        error "Rootfs archive missing: $_archive"
        goodbye
    fi

    if tar xpvf "$_archive" -C "$_dest" --numeric-owner >/dev/null 2>&1; then
        success "Rootfs extracted successfully."
        return 0
    fi

    progress "Standard extract failed. Trying unxz pipe..."
    UNXZ_CMD="unxz"
    if ! command -v unxz >/dev/null 2>&1; then
        if "$BB" unxz --help >/dev/null 2>&1; then
            UNXZ_CMD="$BB unxz"
        else
            error "No 'unxz' tool found. Cannot extract .tar.xz file."
            goodbye
        fi
    fi

    if $UNXZ_CMD -c "$_archive" | tar xpv -C "$_dest" --numeric-owner >/dev/null 2>&1; then
         success "Rootfs extracted successfully (via unxz pipe)."
         return 0
    fi

    if tar xJvf "$_archive" -C "$_dest" --numeric-owner >/dev/null 2>&1; then
         success "Rootfs extracted successfully (Fallback flags)."
         return 0
    fi

    error "Extraction Failed! Your Busybox/Tar does not support XZ compression."
    goodbye
}


# Sticky disk-backed /tmp for apt (_apt). Host stages scripts at
# $DEBIANPATH/tmp (same path as chroot /tmp) — no tmpfs overlay.
# NEVER bind app files/usr/tmp onto /tmp (app_data perms/SELinux break mkstemp).
# Host app tmp is bridged at /mnt/host-tmp only.
ensure_chroot_tmp() {
    HOST_TMP="${APP_PREFIX}/tmp"
    mkdir -p "$DEBIANPATH/tmp" "$HOST_TMP" "$DEBIANPATH/mnt/host-tmp" "$DEBIANPATH/var/tmp"

    # Drop previous bad bind/tmpfs on /tmp if present
    if grep -q " $DEBIANPATH/tmp " /proc/mounts 2>/dev/null; then
        $BB umount "$DEBIANPATH/tmp" 2>/dev/null || $BB umount -l "$DEBIANPATH/tmp" 2>/dev/null || true
    fi

    chmod 1777 "$DEBIANPATH/tmp" 2>/dev/null || true
    chmod 1777 "$DEBIANPATH/var/tmp" 2>/dev/null || true
    progress "Sticky /tmp ready at $DEBIANPATH/tmp (mode 1777, no host bind)"

    chmod 1777 "$HOST_TMP" 2>/dev/null || true
    $BB mount --bind "$HOST_TMP" "$DEBIANPATH/mnt/host-tmp" 2>/dev/null || true

    if [ -f "$HOST_TMP/launch_tool.sh" ]; then
        cp -f "$HOST_TMP/launch_tool.sh" "$DEBIANPATH/tmp/launch_tool.sh" 2>/dev/null || true
        chmod 755 "$DEBIANPATH/tmp/launch_tool.sh" 2>/dev/null || true
    fi
}

# Main Configuration Logic
configure_debian_chroot() {
    progress "Configuring Debian chroot environment..."

    if [ ! -d "$DEBIANPATH" ]; then
        mkdir -p "$DEBIANPATH"
        [ $? -ne 0 ] && goodbye
    fi

    progress "Mounting filesystems..."
    # Soft remount: busybox often cannot resolve /data on Magisk/KSU (not fatal)
    # Prefer system mount — busybox often "can't find /data" on KSU (sudo stays nosuid)
    /system/bin/mount -o remount,dev,suid /data 2>/dev/null || $BB mount -o remount,dev,suid /data 2>/dev/null || $BB mount -o remount,dev,suid / 2>/dev/null || true

    $BB mount --bind /dev "$DEBIANPATH/dev" || /system/bin/mount --bind /dev "$DEBIANPATH/dev" || goodbye
    $BB mount --bind /sys "$DEBIANPATH/sys" || /system/bin/mount --bind /sys "$DEBIANPATH/sys" || goodbye
    $BB mount -t proc proc "$DEBIANPATH/proc" || /system/bin/mount -t proc proc "$DEBIANPATH/proc" || goodbye
    $BB mount -t devpts devpts "$DEBIANPATH/dev/pts" || /system/bin/mount -t devpts devpts "$DEBIANPATH/dev/pts" || goodbye

    mkdir -p "$DEBIANPATH/dev/shm"
    $BB mount -t tmpfs -o size=512M,mode=1777 tmpfs "$DEBIANPATH/dev/shm" || /system/bin/mount -t tmpfs -o size=512M,mode=1777 tmpfs "$DEBIANPATH/dev/shm" || goodbye

    # Debian /tmp must be sticky world-writable for apt (_apt).
    # Do NOT bind Termux files/usr/tmp here — app_data perms/SELinux break mkstemp.
    ensure_chroot_tmp

    mkdir -p "$DEBIANPATH/sdcard"
    $BB mount --bind /sdcard "$DEBIANPATH/sdcard" || /system/bin/mount --bind /sdcard "$DEBIANPATH/sdcard" || goodbye

    progress "Configuring Network and Groups..."
    $BB chroot "$DEBIANPATH" /bin/bash -c '
        export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
        export TMPDIR=/tmp
        rm -f /etc/resolv.conf
        echo "nameserver 8.8.8.8" > /etc/resolv.conf
        echo "127.0.0.1 localhost" > /etc/hosts

        groupadd -g 3003 aid_inet 2>/dev/null
        groupadd -g 3004 aid_net_raw 2>/dev/null
        groupadd -g 1003 aid_graphics 2>/dev/null

        usermod -g 3003 -G 3003,3004 -a _apt 2>/dev/null || true
        usermod -G 3003 -a root 2>/dev/null || true

        echo "Testing Network..."
        if ping -c 1 8.8.8.8 >/dev/null 2>&1; then
            echo " [OK] Network is working."
        else
            echo " [!] Network check failed. Apt might fail."
        fi
    ' || goodbye

    progress "Updating packages (apt update/upgrade)..."
    $BB chroot "$DEBIANPATH" /bin/bash -c '
        export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
        export TMPDIR=/tmp
        export DEBIAN_FRONTEND=noninteractive
        # apt (_apt) requires sticky world-writable /tmp
        chmod 1777 /tmp /var/tmp 2>/dev/null || true
        # Drop stale indexes from a previous failed signature pass
        rm -rf /var/lib/apt/lists/*
        mkdir -p /var/lib/apt/lists/partial
        chmod 755 /var/lib/apt/lists /var/lib/apt/lists/partial
        # Quick write probe (fails fast with a clear message)
        if ! su -s /bin/sh _apt -c "echo ok > /tmp/.apt_write_probe" 2>/dev/null; then
            # _apt may not exist yet on minimal rootfs — probe as nobody if present
            if ! touch /tmp/.apt_write_probe 2>/dev/null; then
                echo "[!] /tmp is not writable — apt will fail"
                exit 1
            fi
        fi
        rm -f /tmp/.apt_write_probe
        apt-get update -y
        apt-get upgrade -y
        apt-get install -y nano vim net-tools sudo git dbus-x11 wget unzip zsh
        apt-get install -y zsh-autosuggestions zsh-syntax-highlighting || true
    ' || goodbye

    progress "Creating User ($USERNAME)..."
    $BB chroot "$DEBIANPATH" /bin/bash -c "
        export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
        export TMPDIR=/tmp
        groupadd storage 2>/dev/null
        groupadd wheel 2>/dev/null
        id -u $USERNAME >/dev/null 2>&1 || useradd -m -g users -G wheel,audio,video,storage,aid_inet -s /bin/bash $USERNAME
        echo '$USERNAME:altaris' | chpasswd
    " || goodbye

    progress "Configuring Sudoers..."
    $BB chroot "$DEBIANPATH" /bin/bash -c "
        export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
        export TMPDIR=/tmp
        echo '$USERNAME ALL=(ALL:ALL) NOPASSWD:ALL' > /etc/sudoers.d/$USERNAME
        chmod 0440 /etc/sudoers.d/$USERNAME
    " || goodbye

    if [ -z "${DESKTOPS##*xfce*}" ]; then
        progress "Installing XFCE4..."
        $BB chroot "$DEBIANPATH" /bin/bash -c '
            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
            export TMPDIR=/tmp
            export DEBIAN_FRONTEND=noninteractive
            chmod 1777 /tmp /var/tmp 2>/dev/null || true
            _xf=1
            until apt-get install -y xfce4 xfce4-terminal; do
                if [ "$_xf" -ge 3 ]; then
                    echo "Altaris: XFCE package install failed after 3 tries — check your network, then tap Retry Setup."
                    exit 1
                fi
                echo "Altaris: Package download failed — retrying ($((_xf + 1))/3)…"
                apt-get update >/dev/null 2>&1 || true
                _xf=$((_xf + 1))
            done
            apt-get install -y --no-install-recommends xfce4-whiskermenu-plugin xfce4-screenshooter 2>/dev/null || true
        ' || goodbye
    else
        progress "Skipping XFCE4 install (ALTARIS_DESKTOPS=$DESKTOPS)"
    fi

    touch "$DEBIANPATH/.altaris_configured"
    success "Debian Environment Configured!"

    # Root-staged launchers live in the Altaris helpers dir (idempotent).
    mkdir -p /data/local/altaris/helpers

    # --- GENERATE GUI LAUNCH SCRIPT (thin → SSOT GUI / helper) ---
    LAUNCH_SCRIPT="/data/local/altaris/helpers/start_debian13.sh"
    progress "Creating thin GUI launch script at $LAUNCH_SCRIPT..."

    # Select the guest session command at generation time (xfce default; kde aware).
    DE_EXEC="startxfce4"
    if [ -z "${DESKTOPS##*kde*}" ] && [ -n "${DESKTOPS##*xfce*}" ]; then
        DE_EXEC="startplasma-x11"
    fi

    # Expand APP_PREFIX / PKG / DE_EXEC at write time (unquoted EOF); keep runtime vars escaped.
    cat <<EOF > "$LAUNCH_SCRIPT"
#!/system/bin/sh
# Compat one-shot GUI → prefer start_debian13_gui.sh (app SSOT) else helper mount --x11 + desktop session
GUI=/data/local/altaris/helpers/start_debian13_gui.sh
HELPER=/data/local/altaris/helpers/altaris_chroot.sh
DEBIANPATH="\${DEBIANPATH:-/data/local/altaris/chroots/debian}"
TARGET_PREFIX="\${TARGET_PREFIX:-$APP_PREFIX}"
USERNAME="\${USERNAME:-altaris}"
export ALTARIS_PACKAGE="$PKG"

if [ -f "\$GUI" ]; then
  exec sh "\$GUI"
fi

if [ ! -f "\$HELPER" ]; then
  echo "altaris_chroot.sh missing — open app chroot session or re-run setup" >&2
  exit 127
fi

export ALTARIS_CHROOT="\$DEBIANPATH"
export ALTARIS_PREFIX="\$TARGET_PREFIX"
export ALTARIS_HOST_TMP="\${TARGET_PREFIX}/tmp"
sh "\$HELPER" mount --x11 || true
sh "\$HELPER" sh --user root -- \\
  "killall -9 xfce4-session xfwm4 xfdesktop xfce4-panel plasmashell kwin_x11 startplasma-x11 plasma_session ksmserver dbus-launch dbus-daemon 2>/dev/null; true" \\
  >/dev/null 2>&1 || true
echo "Starting Debian 13 Chroot GUI (\$USERNAME) via SSOT helper..."
echo "NOTE: Prefer app Settings START (start_gui_chroot.sh) for Pulse/X11 host stack."
exec sh "\$HELPER" sh --user "\$USERNAME" -- \\
  'export DISPLAY=:0 PULSE_SERVER=tcp:127.0.0.1 XDG_RUNTIME_DIR=/tmp VTEST_SOCKET_NAME=/mnt/host-tmp/.virgl_test; xfconf-query -c xfwm4 -p /general/use_compositing -s false 2>/dev/null; exec dbus-launch --exit-with-session $DE_EXEC'
EOF
    chmod 755 "$LAUNCH_SCRIPT"
    success "GUI Launch script created (SSOT thin wrapper)."

    # Prefer asset SSOT stop when present under app home (redeployed by app)
    STOP_LAUNCHER="/data/local/altaris/helpers/stop_debian13_gui.sh"
    progress "Creating GUI Stop Script at $STOP_LAUNCHER..."
    APP_STOP="$APP_HOME/stop_debian13_gui.sh"
    if [ -f "$APP_STOP" ]; then
        cp -f "$APP_STOP" "$STOP_LAUNCHER"
        chmod +x "$STOP_LAUNCHER"
        success "GUI Stop Script copied from app assets: $STOP_LAUNCHER"
    else
    cat <<EOF > "$STOP_LAUNCHER"
#!/bin/sh
DEBIANPATH="/data/local/altaris/chroots/debian"
BB="$BB"
export LD_LIBRARY_PATH=$LD_LIBRARY_PATH
TARGET_TERMUX_PREFIX="$APP_PREFIX"

echo "Terminating Debian 13 Chroot GUI processes..."
\$BB chroot \$DEBIANPATH /bin/su - root -c "killall -9 xfce4-session xfwm4 xfdesktop xfce4-panel plasmashell kwin_x11 startplasma-x11 plasma_session ksmserver dbus-launch dbus-daemon" >/dev/null 2>&1

pkill -9 -f termux-x11 >/dev/null 2>&1
pkill -9 -f "app_process.*termux-x11" >/dev/null 2>&1
rm -rf \$TARGET_TERMUX_PREFIX/tmp/.X11-unix \$TARGET_TERMUX_PREFIX/tmp/.X0-lock 2>/dev/null
echo "Debian 13 GUI Stopped."
EOF
    chmod +x "$STOP_LAUNCHER"
    success "GUI Stop Script created: $STOP_LAUNCHER"
    fi

    # Also stage full root GUI launcher from app home if present (Settings SSOT)
    APP_START_GUI="$APP_HOME/start_debian13_gui.sh"
    if [ -f "$APP_START_GUI" ]; then
        cp -f "$APP_START_GUI" /data/local/altaris/helpers/start_debian13_gui.sh
        chmod +x /data/local/altaris/helpers/start_debian13_gui.sh
        success "Staged start_debian13_gui.sh from app assets"
    fi

    # --- SSOT helper + thin compat wrappers ---
    HELPER="/data/local/altaris/helpers/altaris_chroot.sh"
    progress "Installing chroot SSOT helper at $HELPER..."
    HELPER_SRC=""
    for cand in \
        "$APP_HOME/altaris_chroot.sh" \
        "$(dirname "$APP_HOME")/staged_scripts/altaris_chroot.sh" \
        "$(dirname "$0")/altaris_chroot.sh"
    do
        if [ -f "$cand" ]; then
            HELPER_SRC="$cand"
            break
        fi
    done
    if [ -n "$HELPER_SRC" ]; then
        cp -f "$HELPER_SRC" "$HELPER"
        chmod 755 "$HELPER"
        success "SSOT helper installed from $HELPER_SRC"
    else
        error "altaris_chroot.sh not found in app home/staged — app ensureHelperScript will stage on first session"
    fi

    ROOT_RUNNER="/data/local/altaris/helpers/run_debian13_root.sh"
    progress "Creating thin root runner → SSOT at $ROOT_RUNNER..."
    cat <<'EOF' > "$ROOT_RUNNER"
#!/system/bin/sh
# Compat wrapper → altaris_chroot.sh exec --user root
HELPER=/data/local/altaris/helpers/altaris_chroot.sh
if [ ! -f "$HELPER" ]; then
  echo "altaris_chroot.sh missing — open a chroot session once or re-run setup" >&2
  exit 127
fi
if [ "$#" -lt 1 ]; then
  echo "Usage: $0 <command> [args...]" >&2
  exit 1
fi
exec sh "$HELPER" exec --user root -- "$@"
EOF
    chmod 755 "$ROOT_RUNNER"
    success "Root runner created (SSOT wrapper)."

    CLI_SCRIPT="/data/local/altaris/helpers/enter_debian13.sh"
    progress "Creating thin CLI launcher at $CLI_SCRIPT..."
    cat <<'EOF' > "$CLI_SCRIPT"
#!/system/bin/sh
# Compat → altaris_chroot.sh login --user altaris
HELPER=/data/local/altaris/helpers/altaris_chroot.sh
[ -f "$HELPER" ] || { echo "altaris_chroot.sh missing" >&2; exit 127; }
echo "Entering Debian 13 Chroot (CLI)..."
exec sh "$HELPER" login --user altaris --shell zsh
EOF
    chmod 755 "$CLI_SCRIPT"
    success "CLI Launcher created: $CLI_SCRIPT"

    ROOT_CLI_SCRIPT="/data/local/altaris/helpers/enter_debian13_root.sh"
    progress "Creating thin root CLI launcher at $ROOT_CLI_SCRIPT..."
    cat <<'EOF' > "$ROOT_CLI_SCRIPT"
#!/system/bin/sh
# Compat → altaris_chroot.sh login --user root
HELPER=/data/local/altaris/helpers/altaris_chroot.sh
[ -f "$HELPER" ] || { echo "altaris_chroot.sh missing" >&2; exit 127; }
echo "Entering Debian 13 Chroot as ROOT..."
exec sh "$HELPER" login --user root --shell bash
EOF
    chmod 755 "$ROOT_CLI_SCRIPT"
    success "Root CLI Launcher created: $ROOT_CLI_SCRIPT"

    cleanup_mounts
    success "NativeCode: Chroot Setup Complete!"

    # Notify NativeCode app (non-fatal: often fails under pure root / no app context)
    am start -a android.intent.action.VIEW -d "altaris://callback?result=success&name=distro_install_debian13_chroot" >/dev/null 2>&1 || true
    return 0
}

main() {
    export LD_LIBRARY_PATH="${APP_PREFIX}/lib"

    if [ "$(id -u)" != "0" ]; then
        error "This script must be run as root. Exiting."
        exit 1
    fi

# resolve root BusyBox (manager built-in; NDK module not required)
_rr=""
for _c in \
  "${ALTARIS_RESOLVE_BB:-}" \
  "$(dirname "$0")/resolve_bb.sh" \
  /data/local/altaris/helpers/altaris_resolve_bb.sh
do
  [ -n "$_c" ] && [ -f "$_c" ] && _rr="$_c" && break
done
if [ -n "$_rr" ]; then
  # shellcheck disable=SC1090
  . "$_rr"
  resolve_bb || true
fi
if [ -z "${BB:-}" ]; then
  # sidecar missing (desktop/uninstall/staged setup) — same B1 walk as resolve_bb
  if [ -n "${ALTARIS_BB:-}" ] && [ -x "$ALTARIS_BB" ] &&
     "$ALTARIS_BB" --list >/dev/null 2>&1; then BB="$ALTARIS_BB"; fi
  if [ -z "${BB:-}" ] && [ -x /data/local/altaris/busybox/altaris_busybox ] &&
     /data/local/altaris/busybox/altaris_busybox --list >/dev/null 2>&1; then
    BB=/data/local/altaris/busybox/altaris_busybox
  fi
  if [ -z "${BB:-}" ]; then
    for path in \
      /data/adb/ksu/bin/busybox \
      /data/adb/ap/bin/busybox \
      /data/adb/magisk/busybox \
      /data/adb/modules/busybox-ndk/system/xbin/busybox \
      /data/adb/modules/busybox-ndk/system/bin/busybox \
      /debug_ramdisk/busybox \
      /sbin/busybox \
      /system/xbin/busybox \
      /system/bin/busybox
    do
      if [ -x "$path" ]; then BB="$path"; break; fi
    done
  fi
fi
if [ -z "${BB:-}" ]; then
  echo "Altaris: ERROR — root-capable busybox not found" >&2
  exit 1
fi


    progress "Using Root Busybox: $BB"

    DEBIANPATH="/data/local/altaris/chroots/debian"

    # Already installed: marker and/or a real shell binary (app cannot always see this path).
    if [ -f "$DEBIANPATH/.altaris_configured" ] || [ -f "$DEBIANPATH/.altaris_configured" ] || [ -e "$DEBIANPATH/bin/sh" ] || [ -e "$DEBIANPATH/usr/bin/bash" ]; then
        success "Debian 13 Chroot already installed."
        progress "Skipping installation (rootfs at $DEBIANPATH)..."
        # Ensure marker exists for future probes
        touch "$DEBIANPATH/.altaris_configured" 2>/dev/null || true
        # Print probe lines the app can match in logs
        progress "VERIFY: chroot present (bin/sh or bash + marker)"
        am start -a android.intent.action.VIEW -d "altaris://callback?result=success&name=distro_install_debian13_chroot" >/dev/null 2>&1 || true
        exit 0
    fi

    if [ ! -d "$DEBIANPATH" ]; then
        mkdir -p "$DEBIANPATH"
        success "Created directory: $DEBIANPATH"
    fi

    # Prefer app-deployed rootfs (same as altaris_install / assets/rootfs/)
    if resolve_rootfs_archive; then
        :
    else
        progress "No local rootfs — downloading $ROOTFS_URL"
        download_file "$DEBIANPATH" "$ROOTFS_NAME" "$ROOTFS_URL"
        ROOTFS_ARCHIVE="$DEBIANPATH/$ROOTFS_NAME"
    fi

    # Optional SHA check (when sha256sum available)
    if [ -n "$ROOTFS_SHA256" ] && command -v sha256sum >/dev/null 2>&1; then
        _got="$(sha256sum "$ROOTFS_ARCHIVE" | awk '{print $1}')"
        if [ "$_got" != "$ROOTFS_SHA256" ]; then
            error "SHA256 mismatch for $ROOTFS_ARCHIVE"
            error "  expected $ROOTFS_SHA256"
            error "  got      $_got"
            goodbye
        fi
        success "SHA256 OK"
    fi

    extract_file "$DEBIANPATH" "$ROOTFS_ARCHIVE"
    configure_debian_chroot
    exit 0
}

main "$@"
