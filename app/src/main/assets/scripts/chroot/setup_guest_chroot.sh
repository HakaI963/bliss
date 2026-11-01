#!/bin/sh
# setup_guest_chroot.sh
# Generic chroot extract + user bootstrap (Fedora / Void / openSUSE).
# XFCE is installed later by the family script (onboarding XFCE phase).
#
# Env (from DistroInstallProfile / OnboardingInstallRunner):
#   ALTARIS_CHROOT, ALTARIS_ROOTFS_PATH, ALTARIS_ROOTFS_SHA256, ALTARIS_ROOTFS_NAME,
#   ALTARIS_ROOTFS_URL (GitHub release download fallback)
#   TERMUX_APP__PACKAGE_NAME, TERMUX__HOME

GUESTPATH="${ALTARIS_CHROOT:-/data/local/altaris/chroots/guest}"
USERNAME="altaris"
DISTRO_LABEL="${ALTARIS_DISTRO_LABEL:-guest}"

PKG="${TERMUX_APP__PACKAGE_NAME:-com.ivarna.fluxlinux}"
APP_HOME="${TERMUX__HOME:-/data/data/${PKG}/files/home}"
APP_PREFIX="${TERMUX__PREFIX:-/data/data/${PKG}/files/usr}"
ROOTFS_NAME="${ALTARIS_ROOTFS_NAME:-rootfs.tar.xz}"
ROOTFS_SHA256="${ALTARIS_ROOTFS_SHA256:-}"
ROOTFS_URL="${ALTARIS_ROOTFS_URL:-}"

progress() { printf "\033[1;36m[+] %s\033[0m\n" "$1"; }
success()  { printf "\033[1;32m[✓] %s\033[0m\n" "$1"; }
error()    { printf "\033[1;31m[!] %s\033[0m\n" "$1"; }

cleanup_mounts() {
    progress "Safety Check: Unmounting filesystems..."
    $BB umount "$GUESTPATH/sdcard" 2>/dev/null || true
    $BB umount "$GUESTPATH/mnt/host-tmp" 2>/dev/null || true
    $BB umount "$GUESTPATH/dev/shm" 2>/dev/null || true
    $BB umount "$GUESTPATH/dev/pts" 2>/dev/null || true
    $BB umount "$GUESTPATH/proc" 2>/dev/null || true
    $BB umount "$GUESTPATH/sys" 2>/dev/null || true
    $BB umount "$GUESTPATH/dev" 2>/dev/null || true
    $BB umount "$GUESTPATH/tmp" 2>/dev/null || true
    return 0
}

goodbye() {
    error "Something went wrong."
    cleanup_mounts
    error "Exiting..."
    exit 1
}

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
        "/storage/emulated/0/Download/$ROOTFS_NAME" \
        "$GUESTPATH/$ROOTFS_NAME"
    do
        if [ -f "$candidate" ] && [ -s "$candidate" ]; then
            ROOTFS_ARCHIVE="$candidate"
            progress "rootfs found: $ROOTFS_ARCHIVE"
            return 0
        fi
    done
    return 1
}

# Download Helper (fallback only — prefer app-local archive). Same pattern as
# setup_debian13_chroot.sh / setup_alpine_chroot.sh.
download_file() {
    # $1=dir $2=filename $3=url
    progress "Downloading file..."
    if [ -e "$1/$2" ] && [ -s "$1/$2" ]; then
        printf "\033[1;33m[!] File already exists: %s\033[0m\n" "$2"
        return 0
    fi
    mkdir -p "$1" 2>/dev/null || true
    if command -v wget >/dev/null 2>&1; then
        wget -O "$1/$2" "$3" && success "File downloaded: $2" && return 0
    fi
    progress "Trying Busybox wget..."
    $BB wget -O "$1/$2" "$3" && success "File downloaded (Fallback)" && return 0
    goodbye
}

# True when the extracted tree is a usable rootfs.
#
# The old check accepted *any* of /bin/sh, /usr/bin/sh, /usr/bin/apk, bash —
# and a partially-extracted or half-removed tree satisfies one of those while
# missing the rest. Extraction was then skipped and the install carried on
# against a broken rootfs: the guest had no /usr/bin/env, so every later
# command failed with
#   chroot: can't execute '/usr/bin/env': No such file or directory
# and the app reported "package manager: unknown" for a Debian image that
# should have had apt.
#
# A rootfs is only usable when it has a shell *and* the loader *and* the
# basic utilities that the setup itself needs.
rootfs_is_usable() { # rootfs_is_usable <dir>
    _r="$1"
    [ -d "$_r" ] || return 1
    # A shell, resolvable as a real file or a symlink that resolves.
    { [ -e "$_r/bin/sh" ] || [ -e "$_r/usr/bin/sh" ]; } || return 1
    # The dynamic loader must exist for that shell to run at all.
    [ -e "$_r/lib/ld-linux-aarch64.so.1" ] || \
        [ -e "$_r/lib64/ld-linux-aarch64.so.1" ] || \
        [ -e "$_r/lib/ld-musl-aarch64.so.1" ] || \
        [ -e "$_r/usr/lib/ld-linux-aarch64.so.1" ] || return 1
    # The utilities the rest of this script and apt/apt-get need.
    [ -e "$_r/usr/bin/env" ] || [ -e "$_r/bin/env" ] || return 1
    return 0
}

extract_file() {
    _dest="$1"
    _archive="${2:-}"
    progress "Extracting file from $_archive ..."
    if rootfs_is_usable "$_dest"; then
        printf "\033[1;33m[!] Rootfs already complete: %s\033[0m\n" "$_dest"
        return 0
    fi
    # A tree that is present but NOT usable is a failed previous attempt. It
    # must be cleared, or tar will merge into the partial tree and the same
    # broken result is reported as success again.
    if [ -d "$_dest" ] && [ -n "$(ls -A "$_dest" 2>/dev/null | head -n1)" ]; then
        printf "\033[1;33m[!] Incomplete rootfs at %s — clearing it before extracting\033[0m\n" "$_dest"
        # Nothing is mounted yet at this point (mounts come later), so a plain
        # delete stays inside $_dest.
        $BB rm -rf "$_dest" 2>/dev/null || rm -rf "$_dest" 2>/dev/null
        mkdir -p "$_dest"
    fi
    if [ ! -f "$_archive" ] || [ ! -s "$_archive" ]; then
        error "Rootfs archive missing: $_archive"
        goodbye
    fi
    mkdir -p "$_dest"
    # Always use root busybox tar — toybox /system/bin/tar has no xz.
    _tar="$BB"
    [ -n "$_tar" ] || _tar=busybox
    case "$_archive" in
        *.tar.xz|*.txz)
            if $_tar tar xJf "$_archive" -C "$_dest"; then
                if rootfs_is_usable "$_dest"; then
                    success "Rootfs extracted successfully (xz)."
                    return 0
                fi
                error "Extraction finished but the tree is incomplete (no shell/loader/env)."
                goodbye
            fi
            ;;
        *.tar.gz|*.tgz)
            if $_tar tar xzf "$_archive" -C "$_dest"; then
                if rootfs_is_usable "$_dest"; then
                    success "Rootfs extracted successfully (gzip)."
                    return 0
                fi
                error "Extraction finished but the tree is incomplete (no shell/loader/env)."
                goodbye
            fi
            ;;
    esac
    if $_tar tar xf "$_archive" -C "$_dest"; then
        if rootfs_is_usable "$_dest"; then
            success "Rootfs extracted successfully."
            return 0
        fi
        error "Extraction finished but the tree is incomplete (no shell/loader/env)."
        goodbye
    fi
    error "Extraction Failed!"
    goodbye
}

ensure_chroot_tmp() {
    HOST_TMP="/data/data/${PKG}/files/usr/tmp"
    mkdir -p "$GUESTPATH/tmp" "$HOST_TMP" "$GUESTPATH/mnt/host-tmp" "$GUESTPATH/var/tmp"
    if grep -q " $GUESTPATH/tmp " /proc/mounts 2>/dev/null; then
        $BB umount "$GUESTPATH/tmp" 2>/dev/null || $BB umount -l "$GUESTPATH/tmp" 2>/dev/null || true
    fi
    chmod 1777 "$GUESTPATH/tmp" 2>/dev/null || true
    chmod 1777 "$GUESTPATH/var/tmp" 2>/dev/null || true
    chmod 1777 "$HOST_TMP" 2>/dev/null || true
    $BB mount --bind "$HOST_TMP" "$GUESTPATH/mnt/host-tmp" 2>/dev/null || true
}

guest_sh() {
    $BB chroot "$GUESTPATH" /bin/sh -c "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; $1"
}

configure_guest_chroot() {
    progress "Configuring $DISTRO_LABEL chroot environment..."

    if [ ! -d "$GUESTPATH" ]; then
        mkdir -p "$GUESTPATH" || goodbye
    fi

    progress "Mounting filesystems..."
    /system/bin/mount -o remount,dev,suid /data 2>/dev/null \
        || $BB mount -o remount,dev,suid /data 2>/dev/null \
        || $BB mount -o remount,dev,suid / 2>/dev/null || true

    # Targets first: a bind to a missing directory fails with ENOENT, which
    # used to abort the install on the very first mount.
    mkdir -p "$GUESTPATH/dev" "$GUESTPATH/dev/pts" "$GUESTPATH/dev/shm" \
        "$GUESTPATH/sys" "$GUESTPATH/proc" "$GUESTPATH/tmp" \
        "$GUESTPATH/mnt/host-tmp" "$GUESTPATH/sdcard" 2>/dev/null || true

    # /dev and /proc are required (the package manager needs both). Everything
    # else is a convenience: failing /sdcard or /dev/pts must not abort a
    # multi-gigabyte install, which is what `|| goodbye` did.
    _try_mount() { # _try_mount <target> <args...>
        _tgt="$1"; shift
        $BB mount "$@" "$_tgt" 2>/dev/null && return 0
        /system/bin/mount "$@" "$_tgt" 2>/dev/null && return 0
        return 1
    }

    if ! _try_mount "$GUESTPATH/dev" --bind /dev; then
        if ! _try_mount "$GUESTPATH/dev" --rbind /dev; then
            error "cannot bind /dev — the package manager cannot run without it"
            goodbye
        fi
    fi
    _try_mount "$GUESTPATH/sys" --bind /sys || \
        _try_mount "$GUESTPATH/sys" --rbind /sys || \
        progress "warning: /sys not mounted"
    if ! _try_mount "$GUESTPATH/proc" -t proc proc; then
        error "cannot mount /proc — the package manager cannot run without it"
        goodbye
    fi
    _try_mount "$GUESTPATH/dev/pts" -t devpts devpts || \
        progress "warning: /dev/pts not mounted (no ptys in the guest)"
    mkdir -p "$GUESTPATH/dev/shm"
    _try_mount "$GUESTPATH/dev/shm" -t tmpfs -o size=512M,mode=1777 tmpfs || \
        progress "warning: /dev/shm not mounted"

    ensure_chroot_tmp

    mkdir -p "$GUESTPATH/sdcard"
    _try_mount "$GUESTPATH/sdcard" --bind /sdcard || \
        progress "warning: /sdcard not mounted"
    progress "Mounted: $(grep -c " $GUESTPATH/" /proc/mounts 2>/dev/null || echo 0) filesystems"

    progress "Configuring Network and Android groups..."
    guest_sh '
        rm -f /etc/resolv.conf
        echo "nameserver 8.8.8.8" > /etc/resolv.conf
        echo "nameserver 1.1.1.1" >> /etc/resolv.conf
        echo "127.0.0.1 localhost" > /etc/hosts

        if command -v groupadd >/dev/null 2>&1; then
            groupadd -g 3003 aid_inet 2>/dev/null || true
            groupadd -g 3004 aid_net_raw 2>/dev/null || true
            groupadd -g 1003 aid_graphics 2>/dev/null || true
            usermod -aG aid_inet root 2>/dev/null || true
        elif command -v addgroup >/dev/null 2>&1; then
            addgroup -g 3003 aid_inet 2>/dev/null || true
            addgroup -g 3004 aid_net_raw 2>/dev/null || true
            addgroup -g 1003 aid_graphics 2>/dev/null || true
            addgroup root aid_inet 2>/dev/null || true
        fi
    ' || goodbye

    progress "Creating User ($USERNAME)..."
    # The account is created with an explicit primary group and its existence
    # is VERIFIED afterwards.
    #
    # The old version piped `useradd … 2>/dev/null` and then wrote a sudoers
    # entry regardless. On an image whose useradd refuses (no default group,
    # USERGROUPS_ENAB=no, or a minimal rootfs without the tool) the install
    # still "succeeded" with a sudo rule pointing at an account that did not
    # exist, so the guest had no usable login and the failure only surfaced
    # much later as a terminal that would not start.
    if ! guest_sh "
        if ! id $USERNAME >/dev/null 2>&1; then
            if command -v useradd >/dev/null 2>&1; then
                (command -v groupadd >/dev/null 2>&1 && groupadd -f $USERNAME) 2>/dev/null || true
                useradd -m -g $USERNAME -s /bin/bash $USERNAME 2>/dev/null \
                    || useradd -m -s /bin/bash $USERNAME 2>/dev/null \
                    || useradd -m $USERNAME 2>/dev/null
            elif command -v adduser >/dev/null 2>&1; then
                adduser -D -s /bin/sh $USERNAME 2>/dev/null || true
            fi
        fi
        id $USERNAME >/dev/null 2>&1 || exit 3
        echo '$USERNAME:altaris' | chpasswd 2>/dev/null || true
        if command -v usermod >/dev/null 2>&1; then
            for g in wheel audio video users aid_inet aid_net_raw aid_graphics; do
                usermod -aG \$g $USERNAME 2>/dev/null || true
            done
        fi
        mkdir -p /etc/sudoers.d
        echo '$USERNAME ALL=(ALL) NOPASSWD:ALL' > /etc/sudoers.d/$USERNAME
        chmod 0440 /etc/sudoers.d/$USERNAME
    "; then
        error "Could not create the guest account '$USERNAME' in $GUESTPATH"
        error "The rootfs has no usable useradd/adduser. Reinstall, or check the image."
        goodbye
    fi
    success "Guest account $USERNAME ready"

    touch "$GUESTPATH/.altaris_configured"
    success "$DISTRO_LABEL environment configured (rootfs + user; XFCE via family phase)!"

    # Root-staged helper/launchers live in the Altaris helpers dir (idempotent).
    mkdir -p /data/local/altaris/helpers

    HELPER="/data/local/altaris/helpers/altaris_chroot.sh"
    progress "Installing chroot SSOT helper at $HELPER..."
    HELPER_SRC=""
    for cand in \
        "/data/data/${PKG}/files/home/altaris_chroot.sh" \
        "/data/data/${PKG}/files/staged_scripts/altaris_chroot.sh" \
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
        error "altaris_chroot.sh not found — app will stage on first session"
    fi

    for s in start_guest_gui.sh stop_guest_gui.sh; do
        src="/data/data/${PKG}/files/home/$s"
        if [ -f "$src" ]; then
            cp -f "$src" "/data/local/altaris/helpers/$s"
            chmod +x "/data/local/altaris/helpers/$s"
            success "Staged $s"
        fi
    done

    cleanup_mounts
    success "Altaris: $DISTRO_LABEL Chroot Setup Complete!"

    am start -a android.intent.action.VIEW \
        -d "altaris://callback?result=success&name=distro_install_${DISTRO_LABEL}_chroot" \
        >/dev/null 2>&1 || true
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

    GUESTPATH="${ALTARIS_CHROOT:-/data/local/altaris/chroots/guest}"

    # "Already installed" must mean *usable*, not "a marker file exists".
    #
    # A previous attempt that died mid-extraction left the marker and a bash
    # binary behind, so every retry took this branch and reported success on a
    # rootfs that could not even run /usr/bin/env. rootfs_is_usable is the same
    # check the extractor uses.
    if { [ -f "$GUESTPATH/.altaris_configured" ]; } && \
       rootfs_is_usable "$GUESTPATH" && \
       grep -q '^altaris:' "$GUESTPATH/etc/passwd" 2>/dev/null; then
        success "$DISTRO_LABEL Chroot already installed."
        progress "VERIFY: chroot present at $GUESTPATH"
        am start -a android.intent.action.VIEW \
            -d "altaris://callback?result=success&name=distro_install_${DISTRO_LABEL}_chroot" \
            >/dev/null 2>&1 || true
        exit 0
    fi

    mkdir -p "$GUESTPATH" || goodbye

    if ! resolve_rootfs_archive; then
        if [ -n "$ROOTFS_URL" ]; then
            progress "No local rootfs — downloading $ROOTFS_URL"
            download_file "$APP_PREFIX/var/lib/proot-distro/cache/rootfs" "$ROOTFS_NAME" "$ROOTFS_URL"
            ROOTFS_ARCHIVE="$APP_PREFIX/var/lib/proot-distro/cache/rootfs/$ROOTFS_NAME"
        else
            error "No local rootfs (expected $APP_HOME/$ROOTFS_NAME)"
            goodbye
        fi
    fi

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

    extract_file "$GUESTPATH" "$ROOTFS_ARCHIVE"
    # Pacman CheckSpace cannot see Android bind-mount free space
    # ("could not determine cachedir mount point /var/cache/pacman/pkg").
    if [ -f "$GUESTPATH/etc/pacman.conf" ]; then
        if grep -q '^CheckSpace' "$GUESTPATH/etc/pacman.conf" 2>/dev/null; then
            sed -i 's/^CheckSpace/#CheckSpace/' "$GUESTPATH/etc/pacman.conf" || true
            progress "Disabled pacman CheckSpace (Android bind mounts)"
        fi
        mkdir -p "$GUESTPATH/var/cache/pacman/pkg"
    fi
    configure_guest_chroot
    exit 0
}

main "$@"
