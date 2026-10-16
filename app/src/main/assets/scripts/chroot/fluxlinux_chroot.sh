#!/system/bin/sh
# fluxlinux-chroot v2.11
# SSOT chroot runner for NativeCode Debian 13 (requires root).
# Do not nest this under run_debian13_root.sh — it already owns mounts + one chroot.
# Guest entry always uses env -i + Debian PATH (never Android /system PATH).
# v2.2: TTY-safe b64 (no stdin pipe), login --workdir, devpts ptmx heal.
#
# Usage:
#   fluxlinux_chroot.sh version
#   fluxlinux_chroot.sh mount [--x11]
#   fluxlinux_chroot.sh login [--user flux|root] [--shell zsh|bash] [--workdir PATH]
#   fluxlinux_chroot.sh sh    [--user flux|root] -- 'shell string'
#   fluxlinux_chroot.sh exec  [--user flux|root] -- CMD [ARGS...]
#   fluxlinux_chroot.sh b64   [--user flux|root] -- BASE64_PAYLOAD
#
# Env:
#   FLUX_CHROOT  FLUX_PACKAGE  FLUX_HOST_TMP  FLUX_PREFIX  FLUX_BB  FLUX_SHELL
set -u

VERSION_STR="fluxlinux-chroot v2.11"
# Prefer caller-pinned env (RootShell / start_gui). Fallbacks cover both store flavors.
if [ -z "${FLUX_PACKAGE:-}" ]; then
  if [ -d /data/data/com.zenithblue.fluxlinux/files/usr ]; then
    FLUX_PACKAGE=com.zenithblue.fluxlinux
  else
    FLUX_PACKAGE=com.ivarna.fluxlinux
  fi
fi
FLUX_CHROOT="${FLUX_CHROOT:-/data/local/folio/chroots/debian}"
FLUX_HOST_TMP="${FLUX_HOST_TMP:-/data/data/${FLUX_PACKAGE}/files/usr/tmp}"
FLUX_PREFIX="${FLUX_PREFIX:-/data/data/${FLUX_PACKAGE}/files/usr}"
LOGIN_SHELL="${FLUX_SHELL:-zsh}"
USER_NAME="flux"
LOGIN_WORKDIR=""
WANT_X11=0
MODE=""
BB=""

die() {
  echo "fluxlinux_chroot: $*" >&2
  exit 2
}

usage() {
  cat <<'EOF' >&2
usage:
  fluxlinux_chroot.sh version
  fluxlinux_chroot.sh mount [--x11]
  fluxlinux_chroot.sh login [--user flux|root] [--shell zsh|bash] [--workdir PATH]
  fluxlinux_chroot.sh sh    [--user flux|root] -- SHELL_STRING
  fluxlinux_chroot.sh exec  [--user flux|root] -- CMD [ARGS...]
  fluxlinux_chroot.sh b64   [--user flux|root] -- BASE64_PAYLOAD
EOF
  exit 2
}

require_root() {
  [ "$(id -u)" = "0" ] || die "must run as root (id=$(id -u))"
}

# Shared resolver (sidecar). Helper stays single-file if only this script was copied.
_rr=""
for _c in \
  "$(dirname "$0")/resolve_bb.sh" \
  /data/local/folio/helpers/fluxlinux_resolve_bb.sh
do
  [ -n "$_c" ] && [ -f "$_c" ] && _rr="$_c" && break
done
_RESOLVE_BB_SOURCED=0
if [ -n "$_rr" ]; then
  # shellcheck disable=SC1090
  . "$_rr" && _RESOLVE_BB_SOURCED=1 || true
fi
if [ "$_RESOLVE_BB_SOURCED" != "1" ]; then
  bb_has() {
    "$1" --list 2>/dev/null | tr ' \t' '\n' | grep -qx "$2"
  }

  bb_ok() {
    [ -n "${1:-}" ] && [ -x "$1" ] || return 1
    case "$1" in *com.termux*|*fluxlinux*|*nativecode*) return 1 ;; esac
    bb_has "$1" chroot && bb_has "$1" mount
  }

  resolve_bb() {
    BB=""
    if [ -n "${FLUX_BB:-}" ] && bb_ok "$FLUX_BB"; then
      BB="$FLUX_BB"
    elif bb_ok /data/local/folio/busybox/flux_busybox; then
      BB=/data/local/folio/busybox/flux_busybox
    else
      for path in \
        /data/adb/ksu/bin/busybox \
        /data/adb/ap/bin/busybox \
        /data/adb/magisk/busybox \
        /data/adb/modules/busybox-ndk/system/xbin/busybox \
        /data/adb/modules/busybox-ndk/system/bin/busybox \
        /debug_ramdisk/busybox \
        /sbin/busybox
      do
        if bb_ok "$path"; then
          BB="$path"
          break
        fi
      done
      if [ -z "$BB" ]; then
        _det=$(command -v busybox 2>/dev/null || true)
        if [ -n "${_det:-}" ] && bb_ok "$_det"; then
          BB="$_det"
        fi
      fi
      if [ -z "$BB" ]; then
        for path in \
          /system/xbin/busybox \
          /system/bin/busybox
        do
          if bb_ok "$path"; then
            BB="$path"
            break
          fi
        done
      fi
    fi
    [ -n "$BB" ] || return 1
    if [ "$BB" != /data/local/folio/busybox/flux_busybox ]; then
      mkdir -p /data/local/folio/busybox 2>/dev/null || true
      cp -f "$BB" /data/local/folio/busybox/flux_busybox 2>/dev/null \
        && chmod 755 /data/local/folio/busybox/flux_busybox 2>/dev/null \
        && bb_ok /data/local/folio/busybox/flux_busybox \
        && BB=/data/local/folio/busybox/flux_busybox || true
    fi
    return 0
  }
fi

# True if target path is already a mount point (exact match in /proc/mounts).
is_mounted() {
  _tgt="$1"
  grep -q " ${_tgt} " /proc/mounts 2>/dev/null
}

bind_if_missing() {
  _src="$1"
  _dst="$2"
  mkdir -p "$_dst" 2>/dev/null || true
  if is_mounted "$_dst"; then
    return 0
  fi
  $BB mount --bind "$_src" "$_dst" 2>/dev/null \
    || /system/bin/mount --bind "$_src" "$_dst" 2>/dev/null || true
}

mount_type_if_missing() {
  _type="$1"
  _src="$2"
  _dst="$3"
  _opts="${4:-}"
  mkdir -p "$_dst" 2>/dev/null || true
  if is_mounted "$_dst"; then
    return 0
  fi
  if [ -n "$_opts" ]; then
    $BB mount -t "$_type" -o "$_opts" "$_src" "$_dst" 2>/dev/null \
      || /system/bin/mount -t "$_type" -o "$_opts" "$_src" "$_dst" 2>/dev/null || true
  else
    $BB mount -t "$_type" "$_src" "$_dst" 2>/dev/null \
      || /system/bin/mount -t "$_type" "$_src" "$_dst" 2>/dev/null || true
  fi
}

ensure_sticky_tmp() {
  mkdir -p "$FLUX_CHROOT/tmp" "$FLUX_CHROOT/var/tmp" 2>/dev/null || true
  # Never keep a host bind/tmpfs on guest /tmp (breaks apt _apt mkstemp).
  if is_mounted "$FLUX_CHROOT/tmp"; then
    $BB umount "$FLUX_CHROOT/tmp" 2>/dev/null || $BB umount -l "$FLUX_CHROOT/tmp" 2>/dev/null || true
  fi
  chmod 1777 "$FLUX_CHROOT/tmp" 2>/dev/null || true
  chmod 1777 "$FLUX_CHROOT/var/tmp" 2>/dev/null || true
}

# devpts with usable ptmx (stock mount often leaves ptmxmode=000 → c--------- pts/ptmx).
# Root still "passes" test -w on mode 000 — check numeric mode, not -w.
ensure_devpts() {
  _pts="$FLUX_CHROOT/dev/pts"
  mkdir -p "$_pts" 2>/dev/null || true
  if is_mounted "$_pts"; then
    _perm=""
    if [ -c "$_pts/ptmx" ]; then
      _perm=$($BB stat -c '%a' "$_pts/ptmx" 2>/dev/null || stat -c '%a' "$_pts/ptmx" 2>/dev/null || echo "")
    fi
    # 0 / 000 / empty missing → heal once (no mount storm: single umount+remount)
    case "$_perm" in
      ""|0|000)
        $BB umount "$_pts" 2>/dev/null || $BB umount -l "$_pts" 2>/dev/null || true
        ;;
    esac
  fi
  if ! is_mounted "$_pts"; then
    $BB mount -t devpts devpts "$_pts" -o newinstance,ptmxmode=0666,mode=0620 2>/dev/null \
      || $BB mount -t devpts devpts "$_pts" -o ptmxmode=0666,mode=0620 2>/dev/null \
      || $BB mount -t devpts -o ptmxmode=0666,mode=0620 devpts "$_pts" 2>/dev/null \
      || $BB mount -t devpts devpts "$_pts" 2>/dev/null \
      || true
  fi
  if [ ! -c "$FLUX_CHROOT/dev/ptmx" ] && [ -c "$_pts/ptmx" ]; then
    $BB ln -sf pts/ptmx "$FLUX_CHROOT/dev/ptmx" 2>/dev/null || true
  fi
  unset _pts _perm
}

ensure_mounts() {
  [ -d "$FLUX_CHROOT" ] || die "chroot missing: $FLUX_CHROOT"
  mkdir -p \
    "$FLUX_CHROOT/dev" "$FLUX_CHROOT/dev/pts" "$FLUX_CHROOT/dev/shm" \
    "$FLUX_CHROOT/proc" "$FLUX_CHROOT/sys" \
    "$FLUX_CHROOT/tmp" "$FLUX_CHROOT/mnt/host-tmp" "$FLUX_CHROOT/sdcard" \
    "$FLUX_HOST_TMP" 2>/dev/null || true

  # Soft remount /data for dev,suid (KSU/Magisk; fail soft)
  /system/bin/mount -o remount,dev,suid /data >/dev/null 2>&1 \
    || $BB mount -o remount,dev,suid /data >/dev/null 2>&1 \
    || $BB mount -o remount,dev,suid / >/dev/null 2>&1 \
    || true

  bind_if_missing /dev "$FLUX_CHROOT/dev"
  bind_if_missing /sys "$FLUX_CHROOT/sys"
  # /dev bind usually exposes kgsl. If the node is still missing, mknod (fail-soft).
  if [ -e /dev/kgsl-3d0 ] && [ ! -e "$FLUX_CHROOT/dev/kgsl-3d0" ]; then
    $BB mknod "$FLUX_CHROOT/dev/kgsl-3d0" c $($BB stat -c "%t %T" /dev/kgsl-3d0 2>/dev/null) \
      >/dev/null 2>&1 || true
    chmod 666 "$FLUX_CHROOT/dev/kgsl-3d0" >/dev/null 2>&1 || true
  fi
  mount_type_if_missing proc proc "$FLUX_CHROOT/proc"
  ensure_devpts
  mount_type_if_missing tmpfs tmpfs "$FLUX_CHROOT/dev/shm" "size=512M,mode=1777"

  ensure_sticky_tmp

  bind_if_missing "$FLUX_HOST_TMP" "$FLUX_CHROOT/mnt/host-tmp"
  bind_if_missing /sdcard "$FLUX_CHROOT/sdcard"

  # launch_tool bridge: host-tmp → guest sticky /tmp
  if [ -f "$FLUX_HOST_TMP/launch_tool.sh" ]; then
    cp -f "$FLUX_HOST_TMP/launch_tool.sh" "$FLUX_CHROOT/tmp/launch_tool.sh" 2>/dev/null || true
    chmod 755 "$FLUX_CHROOT/tmp/launch_tool.sh" 2>/dev/null || true
  fi

  if [ "$WANT_X11" = "1" ]; then
    mkdir -p "$FLUX_PREFIX/tmp/.X11-unix" "$FLUX_CHROOT/tmp/.X11-unix" 2>/dev/null || true
    chmod 1777 "$FLUX_PREFIX/tmp/.X11-unix" 2>/dev/null || true
    # Refresh X11 bind so new host sockets appear
    if is_mounted "$FLUX_CHROOT/tmp/.X11-unix"; then
      $BB umount "$FLUX_CHROOT/tmp/.X11-unix" 2>/dev/null \
        || $BB umount -l "$FLUX_CHROOT/tmp/.X11-unix" 2>/dev/null || true
    fi
    $BB mount --bind "$FLUX_PREFIX/tmp/.X11-unix" "$FLUX_CHROOT/tmp/.X11-unix" 2>/dev/null \
      || mount --bind "$FLUX_PREFIX/tmp/.X11-unix" "$FLUX_CHROOT/tmp/.X11-unix" 2>/dev/null || true
  fi
}

# Single-quote escape for embedding into su -c '…'
sq() {
  # shellcheck disable=SC2001
  printf "%s" "$1" | sed "s/'/'\\\\''/g"
}

# shell-join argv into a single-quoted string safe for su -c
quote_argv() {
  _out=""
  for _a in "$@"; do
    _q=$(sq "$_a")
    if [ -z "$_out" ]; then
      _out="'$_q'"
    else
      _out="$_out '$_q'"
    fi
  done
  printf "%s" "$_out"
}

# Canonical Debian PATH inside rootfs (no Android /system).
GUEST_PATH_ROOT="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

guest_path_for_user() {
  if [ "$USER_NAME" = "root" ]; then
    printf '%s' "$GUEST_PATH_ROOT"
  else
    printf '%s' "/home/flux/.local/bin:/home/flux/bin:/home/flux/.cargo/bin:/opt/nodejs/bin:$GUEST_PATH_ROOT"
  fi
}

# KEY=VAL list for guest /usr/bin/env -i (space-separated; values have no spaces).
build_guest_env_args() {
  _gp=$(guest_path_for_user)
  _term="${TERM:-xterm-256color}"
  # POSIX C until guest profile.d/zshrc picks a locale that exists.
  # Forcing en_US.UTF-8 here prints setlocale warnings on Manjaro ARM.
  _lang="${LANG:-C}"
  if [ "$USER_NAME" = "root" ]; then
    GUEST_ENV_ARGS="PATH=$_gp HOME=/root USER=root LOGNAME=root TERM=$_term LANG=$_lang TMPDIR=/tmp XDG_RUNTIME_DIR=/tmp PULSE_SERVER=tcp:127.0.0.1 DEBIAN_FRONTEND=noninteractive"
  else
    GUEST_ENV_ARGS="PATH=$_gp HOME=/home/flux USER=flux LOGNAME=flux NVM_DIR=/home/flux/.nvm TERM=$_term LANG=$_lang TMPDIR=/tmp XDG_RUNTIME_DIR=/tmp PULSE_SERVER=tcp:127.0.0.1 DEBIAN_FRONTEND=noninteractive"
  fi
}

# chroot + clean env -i + remaining guest argv (drops Android PATH/LD_*).
guest_chroot_env() {
  build_guest_env_args
  # shellcheck disable=SC2086
  if [ -n "${BB:-}" ] && bb_has "$BB" chroot; then
    exec $BB chroot "$FLUX_CHROOT" /usr/bin/env -i $GUEST_ENV_ARGS "$@"
  fi
  exec /system/bin/chroot "$FLUX_CHROOT" /usr/bin/env -i $GUEST_ENV_ARGS "$@"
}

guest_bin_exists() {
  [ -x "$FLUX_CHROOT/bin/$1" ] || [ -x "$FLUX_CHROOT/usr/bin/$1" ]
}

# Absolute guest path for an applet (/bin, /usr/bin, /sbin, /usr/sbin).
# Fedora 44+ puts su/runuser under util-linux, often only in /usr/sbin.
guest_bin_path() {
  for _d in /bin /usr/bin /sbin /usr/sbin; do
    if [ -x "$FLUX_CHROOT$_d/$1" ]; then
      printf '%s' "$_d/$1"
      return 0
    fi
  done
  return 1
}

# Switch to USER_NAME without guest su/runuser.
# Android busybox chroot is "NEWROOT [PROG]" only — GNU --userspec is treated
# as NEWROOT and fails with "can't change root directory to '--userspec=…'".
# Stage host busybox into the guest (argv0 must be "busybox") and drop uid
# with setuidgid NUMERIC (name lookup does not see guest "flux").
guest_userspec() {
  _pw="$FLUX_CHROOT/etc/passwd"
  _uid=$(awk -F: -v u="$USER_NAME" '$1==u {print $3; exit}' "$_pw")
  [ -n "${_uid:-}" ] || \
    die "cannot switch to $USER_NAME: no su/runuser and no passwd entry"
  if ! "$BB" --list 2>/dev/null | tr ' \t' '\n' | grep -qx setuidgid; then
    die "busybox setuidgid missing; cannot drop to $USER_NAME (no guest su)"
  fi
  mkdir -p "$FLUX_CHROOT/tmp" || true
  _bb=/tmp/busybox
  cp -f "$BB" "$FLUX_CHROOT$_bb" || die "cannot stage busybox for setuidgid"
  chmod 755 "$FLUX_CHROOT$_bb"
  build_guest_env_args
  # shellcheck disable=SC2086
  exec $BB chroot "$FLUX_CHROOT" "$_bb" setuidgid "$_uid" \
    /usr/bin/env -i $GUEST_ENV_ARGS "$@"
}

# Drop to USER_NAME then exec remaining argv (non-interactive).
# Order matches start_guest_gui: runuser, then su, then numeric setuidgid.
guest_as_user() {
  [ "$#" -ge 1 ] || die "guest_as_user requires CMD"
  _runuser=$(guest_bin_path runuser || true)
  if [ -n "${_runuser:-}" ]; then
    guest_chroot_env "$_runuser" -u "$USER_NAME" -- "$@"
  fi
  _su=$(guest_bin_path su || true)
  if [ -n "${_su:-}" ]; then
    # su - USER -s SHELL [ -c CMD … ]
    guest_chroot_env "$_su" - "$USER_NAME" -s "$@"
  fi
  guest_userspec "$@"
}

# Interactive login as USER_NAME. LOGIN_SHELL is already resolve_login_shell'd.
# su - handles home/cd; runuser/userspec cd via $HOME from build_guest_env_args.
guest_login_user() {
  case "$LOGIN_SHELL" in
    zsh)
      _bin=/bin/zsh
      _boot="exec /bin/zsh -l"
      ;;
    sh|ash)
      _bin=/bin/sh
      _boot="exec /bin/sh -l"
      ;;
    bash|*)
      _bin=/bin/bash
      _boot="exec /bin/bash --login"
      ;;
  esac
  _inner="${_cd}${_boot}"

  _runuser=$(guest_bin_path runuser || true)
  if [ -n "${_runuser:-}" ]; then
    guest_chroot_env "$_runuser" -u "$USER_NAME" -- /bin/sh -c "cd 2>/dev/null || true; $_inner"
  fi
  _su=$(guest_bin_path su || true)
  if [ -n "${_su:-}" ]; then
    if [ -n "$_cd" ]; then
      guest_chroot_env "$_su" - "$USER_NAME" -s "$_bin" -c "$_inner"
    else
      guest_chroot_env "$_su" - "$USER_NAME" -s "$_bin"
    fi
  fi
  guest_userspec /bin/sh -c "cd 2>/dev/null || true; $_inner"
}

# App uid cannot stat /data/local/folio (SELinux). Resolve as root from the rootfs.
resolve_login_shell() {
  _req="${1:-zsh}"
  case "$_req" in
    ash) _req=sh ;;
  esac
  if guest_bin_exists "$_req"; then
    printf '%s' "$_req"
    return
  fi
  for _s in zsh bash sh; do
    if guest_bin_exists "$_s"; then
      printf '%s' "$_s"
      return
    fi
  done
  printf '%s' "sh"
}

# Seed Bliss zsh profile when customization never wrote one (Alpine chroot).
ensure_flux_zsh_profile() {
  [ "$USER_NAME" = "flux" ] || return 0
  _home="$FLUX_CHROOT/home/flux"
  [ -d "$_home" ] || return 0
  if [ ! -f "$_home/.zshrc" ]; then
    cat > "$_home/.zshrc" <<'ZSHRC'
# Guest PATH only — never inherit host PREFIX/bin (nested proot glue errors).
export PATH="$HOME/.local/bin:/opt/nodejs/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

_have=$(locale -a 2>/dev/null || true)
_pick=""
for _c in en_US.UTF-8 en_US.utf8 C.UTF-8 C.utf8; do
  echo "$_have" | grep -qxFi "$_c" && { _pick="$_c"; break; }
done
if [ -n "$_pick" ]; then
  export LANG="$_pick" LC_ALL="$_pick"
else
  unset LC_ALL
  export LANG=C
fi
unset _have _c _pick
unset PROOT_TMP_DIR
export TMPDIR="${TMPDIR:-/tmp}"
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/tmp}"

# Launch sticker + info block: ~/.config/folio/banner.sh (sourced at the end).

export ZSH="${ZSH:-$HOME/.oh-my-zsh}"
if [ -f "$ZSH/oh-my-zsh.sh" ]; then
  ZSH_THEME="folio"
  DISABLE_UPDATE_PROMPT=true
  DISABLE_AUTO_UPDATE=true
  ZSH_DISABLE_COMPFIX=true
  plugins=(git zsh-autosuggestions zsh-syntax-highlighting)
  source "$ZSH/oh-my-zsh.sh"
fi

if command -v apk >/dev/null 2>&1 && command -v sudo >/dev/null 2>&1; then
  apk() { command sudo apk "$@"; }
fi

# Bliss launch sticker (random truecolor anime banner; opt out FLUX_NO_BANNER=1)
[ -r "$HOME/.config/folio/banner.sh" ] && . "$HOME/.config/folio/banner.sh"
ZSHRC
  fi
  # Monochrome Bliss prompt theme (replaces the removed agnosterzak theme).
  if [ -d "$_home/.oh-my-zsh" ]; then
    mkdir -p "$_home/.oh-my-zsh/custom/themes" 2>/dev/null || true
    rm -f "$_home/.oh-my-zsh/custom/themes/agnosterzak.zsh-theme" 2>/dev/null || true
    if [ ! -s "$_home/.oh-my-zsh/custom/themes/folio.zsh-theme" ]; then
      cat > "$_home/.oh-my-zsh/custom/themes/folio.zsh-theme" <<'FOLIO_THEME_EOF'
# folio.zsh-theme — Bliss's minimal monochrome prompt.
# White/gray on black only; ASCII-safe (no powerline glyphs, no ANSI colors 31-44).
# Segments:  time  bliss  working-dir  input-char
PROMPT='%F{240}%D{%H:%M}%f %F{255}bliss%f %F{250}%~%f %F{255}%#%f '
PROMPT2='%F{240}> %f'
FOLIO_THEME_EOF
    fi
  fi
  # Existing old profile: point it at the new theme (kept file intact otherwise).
  if [ -f "$_home/.zshrc" ] && grep -q 'ZSH_THEME="agnosterzak"' "$_home/.zshrc" 2>/dev/null; then
    sed -i 's|ZSH_THEME="agnosterzak"|ZSH_THEME="folio"|' "$_home/.zshrc" 2>/dev/null || true
  fi
  # ── Bliss launch sticker: 5 truecolor anime half-block designs + -----------
  # banner.sh. Random one per interactive shell; opt out with FLUX_NO_BANNER=1.
  # Host-side write (root owns the chroot tree); guards keep it non-fatal.
  # `.banner-rev` refreshes existing installs on the next customization re-run.
  _fbdir="$_home/.config/folio/banner"
  mkdir -p "$_fbdir" "$_home/.config/folio" 2>/dev/null || true
  _fbrev="4"
  _fneed=0
  if [ "$(cat "$_fbdir/.banner-rev" 2>/dev/null || true)" != "$_fbrev" ]; then
    _fneed=1
  fi
  for _fb in 01-anime-girl 02-maid 03-kitsune 04-chibi 05-catgirl; do
    [ -s "$_fbdir/$_fb.txt" ] || _fneed=1
  done
  [ -s "$_home/.config/folio/banner.sh" ] || _fneed=1
  if [ "$_fneed" = "1" ]; then
    rm -f "$_fbdir"/*.txt 2>/dev/null || true
    cat > "$_fbdir/.folio-banners.$$" <<'FOLIO_BANNER_ALL'
==>01-anime-girl
\033[38;2;0;0;0;48;2;116;84;36m▀\033[38;2;116;84;44;48;2;124;92;44m▀\033[38;2;124;84;44;48;2;116;84;44m▀\033[48;2;124;84;44m                                 \033[38;2;108;76;36m▀\033[48;2;100;76;36m \033[38;2;116;84;44m▀\033[48;2;124;84;44m    \033[0m
\033[48;2;124;84;44m \033[38;2;116;84;44m▀                                   \033[38;2;116;84;36m▀\033[48;2;100;76;36m \033[38;2;116;84;44;48;2;108;76;36m▀\033[48;2;124;84;44m   \033[0m
\033[48;2;124;84;44m                 \033[38;2;124;84;44;48;2;116;84;36m▀▀\033[48;2;124;84;44m  \033[38;2;124;84;44;48;2;116;84;36m▀\033[38;2;116;84;36;48;2;124;84;44m▀\033[38;2;124;84;44;48;2;116;84;36m▀▀\033[48;2;124;84;44m           \033[48;2;116;84;44m \033[48;2;124;84;44m \033[38;2;100;76;36;48;2;108;76;36m▀\033[48;2;100;76;36m \033[48;2;124;84;44m \033[38;2;116;84;44m▀ \033[0m
\033[48;2;124;84;44m \033[38;2;124;84;44;48;2;116;84;36m▀▀▀▀▀▀▀▀▀▀\033[48;2;124;84;44m \033[38;2;116;84;36;48;2;124;92;44m▀▀\033[38;2;124;84;44m▀ \033[38;2;116;84;36;48;2;108;76;36m▀\033[38;2;140;108;68;48;2;164;124;76m▀\033[38;2;148;116;68;48;2;252;196;148m▀\033[38;2;108;76;36;48;2;140;100;52m▀\033[38;2;124;84;44;48;2;116;84;36m▀\033[38;2;116;84;36;48;2;124;84;44m▀\033[38;2;140;100;52m▀\033[38;2;188;156;108;48;2;236;196;148m▀\033[38;2;116;84;36;48;2;188;156;108m▀\033[38;2;124;84;44;48;2;108;76;36m▀\033[48;2;124;92;44m▀\033[48;2;124;84;44m           \033[38;2;116;84;36;48;2;116;84;44m▀\033[48;2;100;76;36m \033[38;2;124;84;44;48;2;116;84;44m▀\033[48;2;124;84;44m  \033[0m
\033[38;2;140;100;52;48;2;148;116;68m▀▀\033[38;2;124;92;44m▀\033[48;2;140;108;68m▀\033[48;2;164;124;76m▀\033[48;2;188;156;108m▀\033[48;2;140;108;68m▀\033[48;2;148;116;68m▀\033[48;2;140;108;68m▀\033[48;2;140;100;52m \033[38;2;140;100;52;48;2;124;92;44m▀\033[38;2;140;108;68;48;2;124;84;44m▀\033[38;2;140;100;52;48;2;116;84;36m▀\033[38;2;124;92;44m▀\033[48;2;124;84;44m  \033[48;2;108;76;36m \033[48;2;164;124;76m \033[38;2;252;236;172;48;2;252;228;172m▀\033[48;2;236;196;148m \033[38;2;108;76;36;48;2;188;156;108m▀\033[38;2;116;84;44;48;2;116;84;36m▀ \033[38;2;164;124;76;48;2;188;156;108m▀\033[38;2;212;188;140;48;2;252;236;180m▀\033[38;2;148;116;68;48;2;252;228;172m▀\033[38;2;108;76;36;48;2;140;100;52m▀\033[38;2;124;84;44;48;2;116;84;36m▀\033[48;2;124;84;44m          \033[48;2;116;84;44m \033[48;2;100;76;36m \033[38;2;116;84;44;48;2;124;84;44m▀  \033[0m
\033[38;2;140;100;52;48;2;116;84;36m▀\033[48;2;124;84;44m▀\033[38;2;140;108;68m▀\033[38;2;140;100;52;48;2;108;76;36m▀\033[38;2;164;124;76;48;2;188;156;108m▀\033[38;2;252;228;172;48;2;252;236;172m▀\033[38;2;164;124;76;48;2;236;196;148m▀\033[48;2;116;84;36m \033[38;2;124;92;44m▀\033[38;2;116;84;36;48;2;124;92;44m▀\033[48;2;124;84;44m▀     \033[48;2;116;84;36m \033[38;2;164;116;68;48;2;148;116;68m▀\033[48;2;252;228;172m  \033[38;2;252;236;180m▀\033[38;2;188;156;108;48;2;252;236;180m▀\033[38;2;100;76;36;48;2;188;156;108m▀\033[38;2;164;124;76;48;2;140;100;52m▀\033[38;2;252;228;172;48;2;252;196;148m▀\033[38;2;252;236;172;48;2;252;244;188m▀\033[38;2;236;196;148m▀\033[38;2;116;84;36;48;2;188;156;108m▀\033[38;2;124;84;44;48;2;108;76;36m▀\033[48;2;124;84;44m         \033[48;2;116;84;44m \033[38;2;100;76;36;48;2;108;76;36m▀\033[48;2;124;84;44m   \033[0m
\033[48;2;124;84;44m   \033[38;2;116;84;36;48;2;108;76;36m▀\033[38;2;164;124;76;48;2;188;156;108m▀\033[38;2;188;156;108;48;2;252;228;172m▀\033[38;2;212;188;140m▀\033[38;2;164;124;76m▀\033[38;2;108;76;36;48;2;140;100;52m▀\033[38;2;140;100;52;48;2;108;76;36m▀\033[38;2;164;124;76;48;2;228;172;124m▀\033[38;2;108;76;36;48;2;164;124;76m▀\033[38;2;124;84;44;48;2;108;76;36m▀\033[48;2;124;92;44m▀\033[48;2;124;84;44m  \033[38;2;116;84;36;48;2;116;84;44m▀\033[38;2;140;100;52;48;2;124;84;44m▀\033[38;2;236;196;148;48;2;228;172;124m▀\033[38;2;252;236;172;48;2;236;196;148m▀▀\033[38;2;252;244;188;48;2;156;140;108m▀\033[38;2;236;196;148;48;2;68;44;20m▀\033[38;2;100;76;44m▀\033[38;2;116;84;44;48;2;0;0;0m▀\033[38;2;140;108;68m▀\033[38;2;156;140;108m▀\033[38;2;236;196;148;48;2;68;44;20m▀\033[38;2;164;124;76;48;2;100;92;68m▀\033[38;2;116;84;36;48;2;124;84;44m▀        \033[48;2;108;76;36m▀ \033[48;2;124;84;44m   \033[0m
\033[48;2;124;84;44m   \033[48;2;116;84;36m \033[38;2;228;172;124;48;2;188;156;108m▀\033[48;2;252;236;172m \033[38;2;252;228;172;48;2;252;236;180m▀\033[38;2;252;236;180;48;2;252;244;188m▀\033[38;2;212;188;140m▀\033[38;2;100;76;36;48;2;188;156;108m▀\033[38;2;188;156;108;48;2;140;100;52m▀\033[48;2;252;228;172m \033[38;2;140;108;68m▀\033[38;2;108;76;36;48;2;140;100;52m▀\033[38;2;124;92;44;48;2;116;84;36m▀\033[38;2;124;84;44;48;2;124;92;44m▀\033[48;2;124;84;44m \033[48;2;108;76;36m \033[38;2;188;156;108;48;2;164;124;76m▀\033[38;2;252;236;172;48;2;252;228;172m▀\033[38;2;140;108;68;48;2;100;92;68m▀\033[38;2;0;0;0;48;2;188;156;108m▀\033[38;2;156;140;108;48;2;252;252;252m▀\033[38;2;100;92;68;48;2;100;76;44m▀\033[38;2;0;0;0;48;2;68;44;20m▀\033[38;2;68;44;20;48;2;0;0;0m▀\033[48;2;68;44;20m \033[38;2;68;44;20;48;2;0;0;0m▀\033[48;2;100;92;68m▀▀\033[38;2;124;92;44;48;2;100;76;36m▀\033[38;2;116;84;36;48;2;124;92;44m▀\033[38;2;124;84;44;48;2;116;84;44m▀\033[48;2;124;84;44m   \033[38;2;116;84;44m▀\033[38;2;124;84;44;48;2;116;84;36m▀\033[38;2;108;76;36;48;2;100;76;36m▀\033[38;2;116;84;36;48;2;124;84;44m▀\033[38;2;124;84;44;48;2;116;84;44m▀\033[48;2;124;84;44m  \033[0m
\033[48;2;124;84;44m   \033[38;2;108;76;36;48;2;116;84;36m▀\033[38;2;228;172;124;48;2;188;156;108m▀\033[38;2;252;228;172;48;2;100;76;44m▀\033[38;2;188;156;108;48;2;0;0;0m▀\033[38;2;140;108;68;48;2;100;92;68m▀\033[48;2;68;44;20m▀\033[38;2;188;156;108;48;2;0;0;0m▀\033[38;2;140;108;68;48;2;68;44;20m▀\033[38;2;228;172;124;48;2;164;124;76m▀\033[38;2;252;236;180;48;2;252;228;172m▀\033[38;2;236;196;148;48;2;252;236;180m▀\033[38;2;140;100;52;48;2;236;196;148m▀\033[38;2;108;76;36;48;2;140;100;52m▀\033[38;2;124;92;44;48;2;108;76;36m▀\033[38;2;116;84;36;48;2;124;92;44m▀\033[38;2;140;100;52;48;2;116;84;36m▀\033[38;2;236;196;148;48;2;228;172;124m▀\033[38;2;252;228;172;48;2;252;236;172m▀\033[38;2;244;244;204;48;2;252;236;180m▀\033[48;2;252;252;252m \033[38;2;68;44;20;48;2;100;92;68m▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;68;44;20m▀\033[38;2;68;44;20;48;2;0;0;0m▀\033[38;2;0;0;0;48;2;156;140;108m▀\033[38;2;100;76;44m▀\033[48;2;212;204;188m \033[38;2;68;44;20;48;2;108;76;36m▀\033[38;2;124;92;44;48;2;116;84;44m▀\033[38;2;116;84;44;48;2;124;84;44m▀ \033[38;2;124;84;44;48;2;116;84;36m▀\033[48;2;108;76;36m▀\033[48;2;124;92;44m \033[38;2;100;76;36;48;2;68;44;20m▀\033[48;2;0;0;0m \033[38;2;116;84;44;48;2;68;44;20m▀\033[38;2;124;84;44;48;2;124;92;44m▀\033[48;2;116;84;36m▀\033[48;2;124;84;44m \033[0m
\033[48;2;124;84;44m  \033[48;2;116;84;44m \033[38;2;108;76;36;48;2;124;92;44m▀\033[48;2;68;44;20m \033[38;2;68;44;20;48;2;140;108;68m▀\033[38;2;212;204;188;48;2;252;252;252m▀\033[48;2;212;204;188m \033[38;2;68;44;20;48;2;0;0;0m▀\033[48;2;68;44;20m \033[48;2;0;0;0m \033[38;2;100;76;44m▀\033[38;2;252;228;172;48;2;212;188;140m▀\033[48;2;252;236;172m▀\033[38;2;252;236;172;48;2;252;228;172m▀\033[38;2;252;228;172;48;2;252;236;172m▀\033[38;2;148;116;68m▀\033[38;2;108;76;36;48;2;188;156;108m▀\033[48;2;108;76;36m \033[38;2;164;124;76;48;2;124;84;44m▀\033[38;2;252;228;172;48;2;252;196;148m▀\033[48;2;252;228;172m \033[38;2;252;252;252;48;2;244;244;204m▀\033[38;2;156;140;108;48;2;212;204;188m▀\033[38;2;0;0;0;48;2;68;44;20m▀ ▀\033[38;2;156;140;108;48;2;0;0;0m▀\033[38;2;212;204;188;48;2;100;92;68m▀\033[48;2;212;204;188m \033[38;2;116;84;36;48;2;108;76;36m▀\033[48;2;124;84;44m  \033[38;2;116;84;36;48;2;108;76;36m▀\033[38;2;140;108;68;48;2;188;156;108m▀\033[38;2;164;124;76;48;2;252;236;180m▀\033[38;2;100;76;36;48;2;68;44;20m▀\033[48;2;0;0;0m   \033[38;2;124;84;44;48;2;100;76;36m▀\033[48;2;124;92;44m▀\033[48;2;116;84;44m▀\033[0m
\033[48;2;124;84;44m  \033[38;2;116;84;44m▀\033[38;2;124;92;44m▀\033[38;2;100;76;36;48;2;116;84;36m▀\033[38;2;156;140;108;48;2;188;156;108m▀\033[48;2;252;252;252m \033[38;2;244;244;236m▀\033[38;2;68;44;20;48;2;100;92;68m▀\033[48;2;0;0;0m  \033[48;2;100;92;68m \033[48;2;212;204;188m \033[38;2;252;236;172;48;2;252;228;172m▀   \033[38;2;252;236;180m▀\033[38;2;236;196;148;48;2;252;236;180m▀\033[38;2;140;108;68;48;2;252;228;172m▀\033[38;2;188;156;108;48;2;236;196;148m▀\033[48;2;252;228;172m  \033[38;2;244;244;236;48;2;252;236;172m▀\033[38;2;100;92;68;48;2;236;196;148m▀\033[38;2;0;0;0;48;2;100;92;68m▀\033[38;2;68;44;20;48;2;100;76;44m▀\033[38;2;0;0;0;48;2;140;108;68m▀\033[38;2;188;156;108;48;2;252;196;148m▀\033[48;2;164;124;76m▀\033[48;2;108;76;36m \033[48;2;124;84;44m  \033[48;2;108;76;36m \033[38;2;188;156;108;48;2;236;196;148m▀\033[38;2;252;236;180;48;2;212;188;140m▀\033[38;2;68;44;20;48;2;0;0;0m▀   \033[48;2;68;44;20m \033[48;2;124;92;44m \033[48;2;116;84;36m \033[0m
\033[38;2;116;84;44;48;2;124;92;44m▀\033[38;2;124;84;44;48;2;116;84;36m▀\033[48;2;124;84;44m  \033[38;2;116;84;36m▀\033[38;2;164;116;68;48;2;116;84;36m▀\033[38;2;252;228;172;48;2;228;172;124m▀\033[38;2;252;252;252;48;2;252;236;180m▀\033[38;2;212;204;188;48;2;252;252;252m▀\033[38;2;68;44;20;48;2;156;140;108m▀\033[48;2;0;0;0m▀ \033[38;2;100;92;68;48;2;156;140;108m▀\033[48;2;252;244;188m \033[48;2;252;228;172m      \033[38;2;252;236;172m▀   \033[48;2;252;236;172m \033[38;2;252;236;172;48;2;252;196;148m▀  \033[38;2;252;196;148;48;2;228;172;124m▀\033[38;2;140;100;52;48;2;124;84;44m▀\033[38;2;124;84;44;48;2;164;124;76m▀\033[48;2;116;84;36m▀ \033[48;2;140;108;68m▀\033[38;2;252;228;172;48;2;100;92;68m▀\033[38;2;100;76;44;48;2;0;0;0m▀    \033[38;2;68;44;20;48;2;100;76;36m▀\033[48;2;124;92;44m \033[38;2;116;84;36;48;2;116;84;44m▀\033[0m
\033[38;2;100;76;36;48;2;0;0;0m▀\033[38;2;124;92;44;48;2;100;76;36m▀\033[38;2;116;84;36;48;2;124;92;44m▀\033[38;2;124;84;44;48;2;116;84;36m▀\033[48;2;124;84;44m \033[38;2;116;84;36m▀\033[38;2;148;116;68;48;2;124;92;44m▀\033[38;2;252;196;148;48;2;188;156;108m▀\033[38;2;252;228;172;48;2;252;196;148m▀▀\033[38;2;140;108;68;48;2;252;228;172m▀\033[38;2;156;140;108;48;2;252;244;188m▀\033[48;2;252;228;172m              \033[38;2;252;196;148;48;2;252;236;172m▀\033[48;2;252;228;172m▀\033[48;2;164;124;76m \033[38;2;164;116;68;48;2;252;196;148m▀\033[38;2;228;172;124;48;2;212;188;140m▀\033[38;2;164;124;76;48;2;140;108;68m▀\033[38;2;124;84;44;48;2;124;92;44m▀\033[48;2;68;44;20m \033[48;2;0;0;0m     \033[38;2;0;0;0;48;2;68;44;20m▀\033[38;2;108;76;36;48;2;124;92;44m▀\033[38;2;124;92;44;48;2;116;84;44m▀\033[38;2;68;44;20;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;100;76;36m▀\033[38;2;124;92;44;48;2;100;76;36m▀\033[38;2;116;84;44;48;2;124;92;44m▀\033[48;2;116;84;36m \033[38;2;164;116;68;48;2;124;92;44m▀\033[38;2;228;172;124;48;2;252;196;148m▀ \033[38;2;252;196;148;48;2;252;228;172m▀▀               \033[38;2;252;236;172m▀\033[38;2;236;196;148m▀\033[38;2;252;196;148;48;2;252;244;188m▀\033[38;2;252;236;180;48;2;156;140;108m▀\033[38;2;68;44;20;48;2;0;0;0m▀\033[48;2;68;44;20m \033[38;2;124;92;44;48;2;108;76;36m▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;100;92;68m▀\033[38;2;236;196;148;48;2;252;244;188m▀\033[38;2;188;156;108;48;2;212;188;140m▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;68;44;20m▀\033[38;2;100;76;36;48;2;140;100;52m▀\033[38;2;140;100;52;48;2;68;44;20m▀\033[38;2;68;44;20;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m    \033[38;2;100;76;36m▀\033[38;2;116;84;36;48;2;68;44;20m▀\033[48;2;124;92;44m▀\033[38;2;188;156;108m▀\033[38;2;252;236;172;48;2;236;196;148m▀\033[38;2;252;228;172;48;2;252;236;172m▀\033[48;2;252;228;172m       \033[38;2;252;228;172;48;2;252;236;172m▀▀\033[38;2;252;236;172;48;2;252;228;172m▀\033[38;2;252;228;172;48;2;252;196;148m▀\033[48;2;252;228;172m      \033[38;2;252;228;172;48;2;252;236;172m▀\033[48;2;236;196;148m▀\033[38;2;68;44;20;48;2;0;0;0m▀ \033[48;2;100;76;36m \033[38;2;68;44;20;48;2;0;0;0m▀ \033[38;2;148;116;68;48;2;140;108;68m▀\033[48;2;252;244;188m \033[48;2;188;156;108m \033[38;2;0;0;0;48;2;68;44;20m▀\033[38;2;116;84;44m▀\033[38;2;68;44;20;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m      \033[38;2;108;76;36m▀\033[48;2;140;100;52m▀\033[38;2;100;92;68;48;2;68;44;20m▀\033[38;2;252;236;180;48;2;100;92;68m▀\033[38;2;252;228;172;48;2;252;244;188m▀\033[48;2;252;236;180m▀\033[48;2;252;228;172m    \033[38;2;252;196;148m▀▀▀\033[48;2;252;236;172m▀\033[48;2;252;228;172m     \033[38;2;252;228;172;48;2;252;236;172m▀\033[38;2;252;236;172;48;2;252;196;148m▀\033[38;2;252;196;148;48;2;228;172;124m▀\033[38;2;228;172;124;48;2;252;228;172m▀\033[48;2;68;44;20m \033[48;2;0;0;0m \033[48;2;68;44;20m \033[48;2;0;0;0m  \033[48;2;100;92;68m \033[48;2;252;244;188m \033[48;2;188;156;108m \033[48;2;0;0;0m     \033[38;2;0;0;0;48;2;68;44;20m▀\033[0m
\033[48;2;0;0;0m       \033[38;2;68;44;20m▀\033[38;2;124;92;44;48;2;100;76;36m▀\033[38;2;0;0;0;48;2;68;44;20m▀\033[38;2;68;44;20;48;2;0;0;0m▀\033[38;2;212;188;140;48;2;156;140;108m▀\033[38;2;252;236;172;48;2;252;196;148m▀\033[48;2;252;228;172m  \033[38;2;252;228;172;48;2;252;236;172m▀▀\033[48;2;252;228;172m     \033[38;2;252;228;172;48;2;252;236;172m▀\033[48;2;252;228;172m \033[38;2;252;228;172;48;2;236;196;148m▀\033[38;2;252;196;148m▀\033[38;2;228;172;124;48;2;252;228;172m▀ \033[38;2;252;244;188;48;2;252;236;172m▀\033[38;2;140;108;68;48;2;252;228;172m▀\033[38;2;0;0;0;48;2;140;108;68m▀\033[48;2;100;92;68m▀▀▀\033[38;2;68;44;20;48;2;156;140;108m▀\033[38;2;252;244;188;48;2;252;236;172m▀\033[38;2;236;196;148m▀\033[38;2;0;0;0;48;2;140;108;68m▀\033[48;2;100;76;44m▀▀\033[48;2;68;44;20m▀▀\033[38;2;68;44;20;48;2;116;84;36m▀\033[0m
\033[48;2;0;0;0m         \033[38;2;100;76;36;48;2;212;188;140m▀\033[38;2;236;196;148;48;2;252;228;172m▀\033[38;2;252;244;188m▀ \033[38;2;252;196;148m▀\033[48;2;252;236;172m▀\033[48;2;228;172;124m▀\033[48;2;252;36;36m▀\033[38;2;252;236;172;48;2;252;196;148m▀\033[48;2;236;196;148m▀▀▀\033[38;2;252;228;172;48;2;228;172;124m▀\033[38;2;252;196;148;48;2;236;196;148m▀\033[38;2;228;172;124;48;2;252;196;148m▀\033[38;2;236;196;148;48;2;252;228;172m▀    \033[38;2;252;236;172m▀\033[38;2;252;228;172;48;2;236;196;148m▀\033[38;2;252;236;172;48;2;252;228;172m▀\033[38;2;252;236;180;48;2;252;236;172m▀\033[38;2;252;228;172;48;2;236;196;148m▀\033[38;2;252;236;172;48;2;252;196;148m▀\033[38;2;252;228;172;48;2;252;236;172m▀\033[48;2;252;196;148m▀\033[48;2;252;228;172m \033[38;2;252;236;172m▀▀\033[38;2;252;196;148;48;2;236;196;148m▀\033[38;2;140;100;52;48;2;148;116;68m▀\033[48;2;116;84;36m \033[0m
\033[48;2;0;0;0m        \033[38;2;140;108;68;48;2;188;156;108m▀\033[38;2;252;244;188;48;2;252;236;180m▀\033[48;2;252;228;172m    \033[38;2;252;244;188;48;2;252;236;180m▀\033[48;2;228;172;124m \033[48;2;252;28;36m \033[38;2;252;196;148;48;2;252;228;172m▀\033[38;2;236;196;148;48;2;252;236;180m▀\033[38;2;228;172;124;48;2;236;196;148m▀\033[38;2;236;196;148;48;2;252;228;172m▀\033[38;2;252;228;172;48;2;252;236;172m▀\033[48;2;252;228;172m \033[38;2;252;236;172m▀     \033[38;2;252;228;172;48;2;252;236;172m▀\033[48;2;252;196;148m \033[48;2;252;228;172m \033[48;2;252;236;172m \033[38;2;252;196;148;48;2;252;228;172m▀   \033[38;2;252;236;172m▀\033[38;2;252;228;172;48;2;252;196;148m▀\033[48;2;252;228;172m \033[38;2;252;236;172m▀\033[38;2;252;236;180m▀\033[38;2;212;188;140m▀\033[0m
==>02-maid
\033[48;2;0;0;0m           \033[38;2;0;0;0;48;2;38;42;46m▀\033[48;2;22;22;22m▀\033[48;2;0;0;0m         \033[38;2;0;0;0;48;2;22;22;22m▀\033[48;2;30;38;46m▀\033[0m
\033[48;2;0;0;0m         \033[38;2;0;0;0;48;2;38;42;46m▀\033[38;2;38;42;46;48;2;94;94;98m▀\033[38;2;54;62;70m▀\033[38;2;30;38;46;48;2;78;74;74m▀\033[48;2;22;22;22m \033[48;2;0;0;0m      \033[38;2;22;22;22;48;2;54;50;54m▀▀\033[38;2;30;38;46;48;2;78;74;74m▀\033[38;2;54;50;54;48;2;94;94;98m▀\033[38;2;22;22;22;48;2;30;38;46m▀\033[0m
\033[48;2;0;0;0m       \033[38;2;0;0;0;48;2;30;38;46m▀\033[38;2;38;42;46;48;2;98;106;114m▀\033[38;2;94;94;98;48;2;118;114;118m▀\033[38;2;118;114;118;48;2;142;138;138m▀ \033[38;2;98;106;114m▀\033[38;2;54;50;54;48;2;98;106;114m▀\033[38;2;0;0;0;48;2;38;42;46m▀\033[38;2;22;22;22;48;2;0;0;0m▀\033[48;2;30;38;46m▀\033[48;2;0;0;0m   \033[38;2;54;50;54;48;2;78;74;74m▀\033[38;2;54;62;70m▀\033[38;2;94;94;98;48;2;98;106;114m▀\033[38;2;142;138;138;48;2;162;158;162m▀\033[38;2;94;94;98;48;2;142;138;138m▀\033[38;2;30;38;46;48;2;78;74;74m▀\033[38;2;22;22;22;48;2;54;50;54m▀\033[38;2;0;0;0;48;2;22;22;22m▀▀\033[0m
\033[48;2;0;0;0m     \033[38;2;0;0;0;48;2;22;22;22m▀ \033[38;2;54;50;54m▀\033[48;2;54;50;54m \033[38;2;54;50;54;48;2;22;22;22m▀\033[38;2;78;74;74m▀\033[38;2;118;114;118;48;2;30;38;46m▀\033[38;2;178;154;150;48;2;78;74;74m▀\033[38;2;162;158;162;48;2;174;174;178m▀\033[38;2;142;138;138;48;2;254;242;238m▀\033[38;2;22;22;22;48;2;174;174;178m▀\033[38;2;54;50;54;48;2;94;94;98m▀\033[38;2;30;38;46m▀\033[48;2;22;22;22m \033[38;2;22;22;22;48;2;142;138;138m▀\033[38;2;78;74;74;48;2;94;94;98m▀\033[38;2;118;114;118;48;2;178;154;150m▀\033[38;2;98;106;114;48;2;78;74;74m▀\033[38;2;174;174;178m▀\033[38;2;162;158;162;48;2;54;50;54m▀\033[38;2;118;114;118;48;2;78;74;74m▀\033[38;2;78;74;74;48;2;38;42;46m▀\033[48;2;94;94;98m▀\033[38;2;22;22;22;48;2;54;62;70m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;22;22;22;48;2;38;42;46m▀\033[48;2;94;94;98m▀\033[48;2;142;138;138m \033[38;2;118;114;118m▀\033[38;2;30;38;46;48;2;38;62;94m▀\033[38;2;38;62;94;48;2;94;138;178m▀\033[38;2;46;86;142m▀\033[38;2;134;154;174;48;2;74;118;166m▀\033[38;2;174;174;178;48;2;194;194;202m▀\033[38;2;246;218;214;48;2;254;242;238m▀ ▀\033[38;2;202;178;174m▀\033[38;2;118;114;118m▀\033[38;2;202;178;174m▀\033[38;2;246;218;214m▀\033[38;2;94;94;98;48;2;246;218;214m▀\033[38;2;54;62;70;48;2;46;86;142m▀\033[38;2;38;62;94m▀▀▀\033[38;2;54;50;54;48;2;90;114;142m▀\033[38;2;22;22;22;48;2;142;138;138m▀\033[38;2;54;50;54;48;2;0;0;0m▀\033[48;2;22;22;22m \033[0m
\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;22;22;22m▀\033[48;2;0;0;0m   \033[38;2;38;42;46;48;2;30;38;46m▀\033[48;2;142;138;138m \033[38;2;162;158;162;48;2;202;178;174m▀\033[38;2;194;194;202;48;2;246;206;198m▀\033[38;2;142;178;202;48;2;194;194;202m▀\033[38;2;154;226;250;48;2;138;186;222m▀\033[48;2;154;226;250m \033[38;2;130;194;234;48;2;142;178;202m▀\033[38;2;246;218;214;48;2;254;242;238m▀        \033[38;2;90;114;142;48;2;170;198;226m▀\033[38;2;90;170;222;48;2;154;226;250m▀\033[38;2;94;138;178;48;2;174;218;246m▀\033[38;2;90;170;222;48;2;130;194;234m▀\033[38;2;106;138;162;48;2;194;194;202m▀\033[38;2;162;158;162;48;2;202;178;174m▀\033[38;2;78;74;74;48;2;178;154;150m▀\033[48;2;38;42;46m \033[48;2;0;0;0m  \033[38;2;22;22;22;48;2;30;38;46m▀\033[48;2;38;42;46m▀\033[38;2;0;0;0;48;2;22;22;22m▀\033[0m
\033[48;2;0;0;0m \033[38;2;22;22;22;48;2;30;38;46m▀\033[38;2;0;0;0;48;2;22;22;22m▀\033[48;2;0;0;0m  \033[48;2;30;38;46m \033[38;2;142;138;138;48;2;162;158;162m▀\033[48;2;202;178;174m \033[38;2;242;194;182;48;2;246;206;198m▀ \033[38;2;246;206;198;48;2;246;218;214m▀▀▀\033[48;2;254;242;238m        \033[38;2;254;242;238;48;2;246;218;214m▀ \033[38;2;174;174;178;48;2;246;206;198m▀\033[48;2;242;194;182m▀\033[48;2;246;206;198m▀\033[38;2;246;218;214m▀ \033[38;2;142;138;138;48;2;118;114;118m▀\033[38;2;22;22;22;48;2;0;0;0m▀  \033[38;2;38;42;46;48;2;54;62;70m▀\033[38;2;54;50;54m▀\033[48;2;22;22;22m \033[0m
\033[38;2;0;0;0;48;2;22;22;22m▀\033[38;2;30;38;46;48;2;38;42;46m▀\033[48;2;22;22;22m \033[48;2;0;0;0m  \033[38;2;22;22;22m▀\033[38;2;174;174;178;48;2;162;158;162m▀\033[38;2;202;178;174;48;2;194;194;202m▀\033[38;2;246;206;198;48;2;246;218;214m▀▀\033[48;2;246;206;198m  \033[48;2;246;218;214m  \033[48;2;254;242;238m      \033[48;2;246;218;214m  \033[48;2;246;206;198m    \033[38;2;246;206;198;48;2;246;218;214m▀\033[48;2;242;194;182m▀\033[38;2;78;74;74;48;2;30;38;46m▀\033[48;2;0;0;0m   \033[48;2;78;74;74m \033[38;2;54;62;70m▀\033[48;2;30;38;46m \033[0m
\033[48;2;22;22;22m \033[48;2;38;42;46m \033[38;2;30;38;46m▀\033[48;2;0;0;0m   \033[38;2;134;154;174;48;2;106;138;162m▀\033[38;2;194;194;202;48;2;182;210;230m▀\033[38;2;246;218;214;48;2;194;194;202m▀\033[48;2;254;242;238m▀\033[48;2;246;218;214m  \033[38;2;246;218;214;48;2;254;242;238m▀ \033[48;2;246;206;198m▀\033[38;2;246;206;198;48;2;246;186;166m▀\033[38;2;246;218;214m▀▀▀\033[38;2;254;242;238;48;2;242;194;182m▀\033[48;2;254;242;238m \033[38;2;246;218;214m▀\033[48;2;246;218;214m \033[38;2;246;206;198m▀▀\033[38;2;246;218;214;48;2;254;242;238m▀\033[48;2;246;218;214m \033[38;2;174;174;178;48;2;194;194;202m▀\033[38;2;38;42;46;48;2;30;38;46m▀\033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;22;22;22m▀\033[48;2;94;94;98m \033[38;2;78;74;74;48;2;54;62;70m▀\033[48;2;38;42;46m \033[0m
\033[38;2;22;22;22;48;2;0;0;0m▀\033[48;2;38;42;46m  \033[38;2;22;22;22;48;2;54;50;54m▀\033[48;2;0;0;0m \033[48;2;30;38;46m \033[48;2;74;98;118m \033[38;2;170;198;226;48;2;142;178;202m▀\033[38;2;182;210;230;48;2;174;218;246m▀\033[38;2;246;218;214m▀\033[38;2;254;242;238;48;2;182;210;230m▀\033[48;2;246;218;214m▀\033[48;2;254;242;238m   \033[38;2;246;206;198m▀\033[38;2;246;186;166;48;2;246;206;198m▀▀\033[48;2;246;218;214m▀\033[38;2;246;218;214;48;2;254;242;238m▀   \033[38;2;254;242;238;48;2;246;218;214m▀\033[48;2;182;210;230m▀\033[38;2;246;218;214m▀\033[38;2;182;210;230;48;2;174;218;246m▀\033[38;2;142;178;202;48;2;90;114;142m▀\033[48;2;0;0;0m   \033[38;2;54;50;54;48;2;54;62;70m▀\033[38;2;94;94;98;48;2;78;74;74m▀\033[38;2;54;62;70;48;2;54;50;54m▀\033[38;2;30;38;46;48;2;22;22;22m▀\033[0m
\033[48;2;0;0;0m \033[38;2;30;38;46;48;2;22;22;22m▀\033[38;2;38;42;46;48;2;30;38;46m▀\033[38;2;62;78;94;48;2;78;74;74m▀\033[38;2;30;38;46;48;2;54;62;70m▀\033[38;2;22;22;22;48;2;30;38;46m▀\033[38;2;90;114;142;48;2;74;98;118m▀\033[48;2;106;138;162m \033[38;2;170;198;226;48;2;134;154;174m▀\033[48;2;174;218;246m  \033[38;2;182;210;230m▀\033[38;2;194;194;202m▀\033[38;2;254;242;238;48;2;194;194;202m▀\033[48;2;242;194;182m▀\033[48;2;254;242;238m    \033[38;2;254;242;238;48;2;246;206;198m▀\033[48;2;202;150;138m▀\033[38;2;246;218;214;48;2;162;158;162m▀\033[38;2;194;194;202;48;2;174;218;246m▀\033[38;2;170;198;226m▀▀\033[38;2;174;218;246;48;2;182;210;230m▀\033[38;2;182;210;230;48;2;142;178;202m▀\033[48;2;62;78;94m \033[48;2;0;0;0m  \033[48;2;22;22;22m \033[48;2;62;78;94m \033[48;2;54;62;70m \033[38;2;38;42;46;48;2;30;38;46m▀\033[48;2;22;22;22m \033[0m
\033[48;2;0;0;0m \033[38;2;22;22;22m▀\033[38;2;30;38;46;48;2;22;22;22m▀\033[38;2;54;50;54;48;2;30;38;46m▀\033[38;2;54;62;70;48;2;38;42;46m▀\033[38;2;62;78;94;48;2;66;94;114m▀\033[38;2;38;62;94m▀\033[38;2;106;138;162m▀\033[38;2;134;154;174;48;2;106;138;162m▀\033[48;2;142;178;202m \033[38;2;170;198;226;48;2;154;194;226m▀▀\033[48;2;94;94;98m▀\033[38;2;142;178;202;48;2;118;114;118m▀\033[48;2;202;150;138m  \033[38;2;202;178;174m▀▀ \033[38;2;202;150;138;48;2;222;158;146m▀\033[48;2;242;194;182m▀\033[38;2;174;174;178;48;2;202;178;174m▀\033[38;2;154;194;226;48;2;174;174;178m▀\033[38;2;174;218;246;48;2;142;178;202m▀\033[38;2;170;198;226;48;2;138;186;222m▀\033[38;2;142;178;202m▀\033[48;2;106;138;162m▀\033[38;2;38;42;46;48;2;30;38;46m▀\033[48;2;0;0;0m  \033[38;2;30;38;46;48;2;38;42;46m▀\033[38;2;62;78;94;48;2;54;62;70m▀\033[38;2;54;50;54;48;2;38;42;46m▀\033[38;2;30;38;46;48;2;22;22;22m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;22;22;22m▀\033[38;2;30;38;46;48;2;22;22;22m▀\033[48;2;30;38;46m \033[38;2;62;78;94;48;2;38;42;46m▀\033[38;2;74;98;118;48;2;62;78;94m▀\033[38;2;90;114;142;48;2;74;98;118m▀▀\033[38;2;106;138;162;48;2;22;22;22m▀\033[38;2;90;114;142m▀\033[38;2;94;94;98;48;2;0;0;0m▀ \033[38;2;30;38;46m▀\033[38;2;202;150;138;48;2;94;94;98m▀\033[48;2;222;158;146m▀\033[48;2;202;150;138m \033[38;2;202;150;138;48;2;222;158;146m▀\033[38;2;222;158;146;48;2;246;186;166m▀\033[38;2;246;186;166;48;2;242;194;182m▀\033[38;2;246;206;198;48;2;202;178;174m▀\033[38;2;202;178;174;48;2;194;194;202m▀  \033[38;2;142;178;202;48;2;54;62;70m▀\033[48;2;66;94;114m▀\033[48;2;90;114;142m \033[48;2;30;38;46m \033[48;2;0;0;0m \033[38;2;22;22;22;48;2;30;38;46m▀\033[48;2;54;62;70m \033[38;2;38;42;46;48;2;30;38;46m▀\033[48;2;22;22;22m \033[0m
\033[48;2;0;0;0m    \033[38;2;22;22;22m▀\033[48;2;38;42;46m \033[48;2;78;74;74m \033[38;2;94;94;98m▀\033[38;2;38;42;46;48;2;0;0;0m▀ \033[48;2;22;22;22m \033[48;2;0;0;0m    \033[38;2;94;94;98m▀\033[38;2;222;158;146;48;2;118;114;118m▀\033[38;2;246;186;166;48;2;178;154;150m▀\033[38;2;202;178;174;48;2;162;158;162m▀\033[38;2;174;174;178m▀▀▀▀\033[48;2;142;138;138m▀\033[38;2;22;22;22;48;2;0;0;0m▀ \033[38;2;62;78;94;48;2;30;38;46m▀\033[38;2;22;22;22;48;2;0;0;0m▀\033[38;2;0;0;0;48;2;22;22;22m▀\033[48;2;38;42;46m \033[38;2;38;42;46;48;2;22;22;22m▀\033[38;2;22;22;22;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;22;22;22m▀\033[38;2;54;50;54;48;2;22;22;22m▀\033[38;2;22;22;22;48;2;0;0;0m▀  ▀\033[48;2;22;22;22m \033[38;2;0;0;0m▀▀\033[38;2;22;22;22;48;2;30;38;46m▀\033[38;2;54;50;54;48;2;38;42;46m▀\033[48;2;98;106;114m \033[38;2;118;114;118m▀\033[48;2;54;50;54m▀\033[38;2;142;138;138;48;2;54;62;70m▀\033[48;2;118;114;118m▀\033[48;2;98;106;114m▀\033[48;2;118;114;118m▀\033[38;2;94;94;98;48;2;38;42;46m▀\033[48;2;0;0;0m   \033[38;2;22;22;22;48;2;30;38;46m▀\033[38;2;38;42;46;48;2;22;22;22m▀\033[38;2;22;22;22;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m          \033[38;2;22;22;22m▀\033[38;2;0;0;0;48;2;22;22;22m▀\033[38;2;38;42;46m▀\033[48;2;0;0;0m▀ \033[48;2;22;22;22m▀\033[38;2;54;50;54m▀\033[38;2;30;38;46m▀\033[48;2;0;0;0m▀ \033[38;2;54;50;54m▀\033[38;2;94;94;98;48;2;38;42;46m▀\033[48;2;30;38;46m▀\033[48;2;0;0;0m    \033[38;2;22;22;22m▀\033[0m
==>03-kitsune
\033[48;2;0;0;0m         \033[38;2;0;0;0;48;2;9;15;21m▀\033[38;2;9;15;21;48;2;0;0;0m▀▀▀ \033[48;2;39;9;2m▀▀\033[48;2;63;21;15m▀\033[48;2;99;9;2m▀▀▀\033[38;2;0;0;0;48;2;63;21;15m▀\033[38;2;9;15;21;48;2;39;9;2m▀▀\033[48;2;0;0;0m▀\033[38;2;39;27;27;48;2;9;15;21m▀\033[38;2;9;15;21;48;2;0;0;0m▀  \033[38;2;0;0;0;48;2;9;15;21m▀\033[48;2;63;21;15m▀\033[48;2;39;9;2m▀\033[0m
\033[48;2;0;0;0m          \033[38;2;0;0;0;48;2;63;21;15m▀\033[38;2;39;9;2;48;2;99;9;2m▀\033[38;2;63;21;15;48;2;153;15;5m▀\033[38;2;99;9;2m▀         \033[38;2;105;27;15m▀\033[38;2;63;21;15;48;2;105;27;15m▀\033[38;2;0;0;0;48;2;63;21;15m▀\033[48;2;171;63;27m▀\033[38;2;105;27;15;48;2;177;135;21m▀\033[38;2;123;87;33;48;2;171;63;27m▀\033[38;2;171;63;27;48;2;105;27;15m▀\033[48;2;39;9;2m \033[0m
\033[48;2;0;0;0m         \033[48;2;99;9;2m \033[38;2;99;9;2;48;2;153;15;5m▀   \033[38;2;153;15;5;48;2;219;9;4m▀▀        ▀▀\033[38;2;171;63;27;48;2;153;15;5m▀\033[48;2;57;57;39m▀\033[38;2;117;57;33;48;2;195;195;195m▀\033[38;2;105;27;15;48;2;141;105;93m▀\033[48;2;39;9;2m \033[0m
\033[48;2;0;0;0m         \033[38;2;99;9;2;48;2;153;15;5m▀ \033[38;2;153;15;5;48;2;219;9;4m▀        \033[38;2;219;9;4;48;2;243;93;57m▀\033[48;2;219;9;4m  \033[38;2;219;9;4;48;2;213;63;33m▀\033[48;2;171;63;27m▀\033[48;2;153;15;5m▀\033[48;2;219;9;4m  \033[38;2;117;57;33m▀\033[38;2;195;195;195;48;2;117;57;33m▀\033[48;2;57;57;39m \033[0m
\033[48;2;0;0;0m         \033[48;2;153;15;5m \033[48;2;219;9;4m   \033[38;2;219;9;4;48;2;213;63;33m▀\033[48;2;219;9;4m  \033[38;2;219;9;4;48;2;237;129;87m▀\033[48;2;243;93;57m▀\033[48;2;219;9;4m \033[38;2;213;63;33;48;2;243;93;57m▀\033[38;2;237;129;87;48;2;183;93;45m▀\033[38;2;243;93;57;48;2;249;177;135m▀\033[38;2;219;9;4;48;2;243;93;57m▀\033[48;2;219;9;4m \033[38;2;243;93;57m▀\033[38;2;183;93;45;48;2;243;93;57m▀\033[38;2;99;9;2;48;2;225;111;51m▀\033[48;2;153;15;5m \033[38;2;219;9;4m▀\033[38;2;153;15;5;48;2;219;9;4m▀\033[38;2;9;15;21;48;2;39;9;2m▀\033[0m
\033[48;2;0;0;0m         \033[38;2;171;63;27;48;2;183;93;45m▀\033[38;2;225;111;51;48;2;249;177;135m▀\033[48;2;219;9;4m \033[38;2;219;9;4;48;2;243;93;57m▀\033[38;2;249;177;135;48;2;225;111;51m▀\033[38;2;243;93;57m▀\033[38;2;219;9;4;48;2;243;93;57m▀\033[38;2;237;129;87;48;2;225;111;51m▀\033[48;2;153;15;5m▀\033[38;2;243;93;57;48;2;249;177;135m▀\033[38;2;213;63;33;48;2;171;63;27m▀\033[38;2;117;57;33;48;2;153;15;5m▀\033[38;2;225;111;51;48;2;171;63;27m▀\033[38;2;243;93;57m▀\033[38;2;237;129;87;48;2;249;177;135m▀\033[48;2;213;63;33m▀\033[38;2;219;9;4;48;2;237;129;87m▀ \033[38;2;213;63;33;48;2;225;111;51m▀\033[38;2;105;27;15;48;2;153;15;5m▀\033[38;2;219;9;4m▀\033[48;2;63;21;15m \033[0m
\033[48;2;0;0;0m        \033[48;2;9;15;21m \033[38;2;183;93;45;48;2;171;63;27m▀\033[38;2;237;129;87;48;2;213;63;33m▀\033[38;2;243;93;57;48;2;249;177;135m▀\033[48;2;237;129;87m \033[48;2;219;9;4m \033[38;2;213;63;33m▀\033[38;2;249;177;135;48;2;237;129;87m▀\033[38;2;213;63;33;48;2;171;63;27m▀\033[38;2;99;9;2;48;2;117;57;33m▀\033[38;2;213;63;33;48;2;171;63;27m▀\033[48;2;153;15;5m \033[48;2;105;27;15m \033[38;2;153;15;5m▀\033[48;2;99;9;2m▀\033[38;2;237;129;87;48;2;171;63;27m▀\033[38;2;213;63;33m▀\033[38;2;153;15;5;48;2;99;9;2m▀\033[38;2;249;177;135;48;2;171;63;27m▀\033[48;2;237;129;87m \033[38;2;153;15;5;48;2;171;63;27m▀\033[48;2;105;27;15m \033[38;2;63;21;15;48;2;39;9;2m▀\033[0m
\033[48;2;0;0;0m         \033[48;2;153;15;5m \033[38;2;153;15;5;48;2;99;9;2m▀\033[38;2;237;129;87;48;2;171;63;27m▀\033[38;2;225;111;51;48;2;213;63;33m▀\033[48;2;153;15;5m \033[38;2;153;15;5;48;2;105;27;15m▀\033[48;2;213;63;33m \033[48;2;153;15;5m \033[38;2;183;93;45;48;2;189;129;99m▀\033[48;2;183;165;153m▀\033[48;2;105;27;15m \033[38;2;189;129;99;48;2;237;129;87m▀\033[38;2;183;93;45;48;2;189;129;99m▀\033[38;2;171;63;27m▀\033[48;2;153;15;5m  \033[48;2;99;9;2m \033[48;2;105;27;15m \033[38;2;171;63;27;48;2;153;15;5m▀▀\033[38;2;105;27;15m▀\033[48;2;39;9;2m \033[0m
\033[48;2;0;0;0m        \033[38;2;0;0;0;48;2;39;9;2m▀\033[38;2;99;9;2;48;2;141;105;93m▀\033[48;2;117;57;33m \033[38;2;117;57;33;48;2;123;87;33m▀\033[48;2;153;15;5m  \033[38;2;117;57;33;48;2;183;93;45m▀\033[38;2;153;15;5;48;2;105;27;15m▀\033[48;2;117;57;33m▀\033[38;2;189;129;99;48;2;249;207;183m▀\033[38;2;249;207;183;48;2;141;105;93m▀\033[38;2;57;57;39;48;2;135;129;129m▀▀\033[38;2;39;9;2;48;2;105;27;15m▀\033[38;2;57;57;39;48;2;99;9;2m▀\033[38;2;63;21;15;48;2;39;9;2m▀\033[38;2;153;15;5m▀\033[38;2;105;27;15;48;2;99;9;2m▀\033[48;2;153;15;5m \033[38;2;99;9;2;48;2;105;27;15m▀\033[48;2;153;15;5m  \033[38;2;39;9;2;48;2;0;0;0m▀\033[0m
\033[38;2;9;15;21;48;2;0;0;0m▀\033[48;2;9;15;21m  \033[48;2;39;9;2m \033[48;2;99;9;2m \033[38;2;39;9;2;48;2;9;15;21m▀\033[48;2;99;9;2m \033[38;2;99;9;2;48;2;105;27;15m▀\033[38;2;117;57;33;48;2;39;9;2m▀\033[38;2;189;129;99;48;2;87;87;87m▀\033[38;2;39;33;33;48;2;249;231;207m▀\033[38;2;39;9;2;48;2;105;27;15m▀\033[38;2;63;21;15;48;2;87;87;87m▀\033[38;2;99;9;2;48;2;141;105;93m▀\033[38;2;189;129;99;48;2;183;165;153m▀\033[38;2;57;57;39m▀\033[38;2;189;129;99;48;2;249;207;183m▀ \033[38;2;141;105;93;48;2;249;231;207m▀\033[38;2;135;129;129;48;2;141;105;93m▀\033[38;2;249;231;207;48;2;153;15;5m▀\033[38;2;189;129;99;48;2;99;9;2m▀\033[48;2;153;15;5m \033[38;2;99;9;2m▀\033[38;2;0;0;0;48;2;9;15;21m▀\033[38;2;99;9;2;48;2;123;87;33m▀\033[38;2;153;15;5;48;2;171;63;27m▀\033[48;2;153;15;5m \033[38;2;99;9;2;48;2;105;27;15m▀\033[38;2;153;15;5m▀\033[38;2;9;15;21;48;2;39;27;27m▀\033[0m
\033[38;2;0;0;0;48;2;9;15;21m▀ \033[38;2;9;15;21;48;2;39;27;27m▀\033[48;2;9;15;21m \033[38;2;63;21;15;48;2;39;9;2m▀\033[38;2;9;15;21;48;2;0;0;0m▀\033[38;2;63;21;15;48;2;9;15;21m▀\033[38;2;153;15;5;48;2;105;27;15m▀\033[48;2;99;9;2m \033[38;2;141;105;93;48;2;189;129;99m▀\033[38;2;189;129;99;48;2;141;105;93m▀\033[38;2;99;9;2;48;2;153;15;5m▀\033[38;2;141;105;93;48;2;123;87;33m▀\033[48;2;249;231;207m \033[48;2;249;207;183m \033[38;2;249;231;207m▀  \033[48;2;249;231;207m \033[38;2;135;129;129;48;2;195;195;195m▀\033[38;2;171;63;27;48;2;123;87;33m▀\033[38;2;213;63;33;48;2;225;111;51m▀\033[48;2;177;135;21m▀\033[38;2;171;63;27;48;2;141;105;93m▀\033[38;2;87;87;87;48;2;237;129;87m▀\033[38;2;183;93;45;48;2;171;63;27m▀\033[38;2;213;63;33;48;2;225;111;51m▀\033[38;2;153;15;5m▀\033[48;2;141;105;93m \033[38;2;87;87;87;48;2;195;195;195m▀\033[38;2;39;9;2;48;2;39;33;33m▀\033[0m
\033[38;2;9;15;21;48;2;0;0;0m▀\033[48;2;9;15;21m \033[38;2;39;27;27m▀ \033[38;2;9;15;21;48;2;39;27;27m▀\033[38;2;0;0;0;48;2;39;33;33m▀\033[48;2;0;0;0m \033[38;2;9;15;21m▀\033[48;2;39;9;2m \033[38;2;183;165;153;48;2;189;129;99m▀\033[48;2;249;177;135m▀\033[38;2;153;15;5;48;2;123;87;33m▀\033[38;2;141;105;93;48;2;183;165;153m▀\033[48;2;249;231;207m \033[48;2;249;207;183m   \033[38;2;249;207;183;48;2;249;231;207m▀\033[48;2;249;207;183m  \033[38;2;237;129;87;48;2;249;177;135m▀\033[38;2;189;129;99m▀\033[38;2;183;93;45m▀\033[38;2;243;93;57;48;2;237;129;87m▀\033[38;2;225;111;51;48;2;183;93;45m▀\033[48;2;153;15;5m \033[38;2;249;177;135;48;2;237;129;87m▀\033[38;2;189;129;99;48;2;117;57;33m▀\033[38;2;57;57;39;48;2;33;33;39m▀\033[38;2;183;165;153;48;2;141;105;93m▀\033[48;2;87;87;87m \033[0m
\033[48;2;0;0;0m \033[48;2;9;15;21m  \033[48;2;39;33;33m  \033[48;2;39;27;27m  \033[38;2;9;15;21;48;2;57;57;39m▀\033[38;2;63;21;15;48;2;117;57;33m▀\033[38;2;183;93;45m▀\033[38;2;249;177;135;48;2;183;165;153m▀\033[48;2;249;231;207m▀\033[38;2;249;207;183m▀▀\033[48;2;183;165;153m▀\033[48;2;141;105;93m▀\033[48;2;189;129;99m▀\033[38;2;183;165;153;48;2;183;93;45m▀\033[38;2;249;207;183;48;2;183;165;153m▀\033[48;2;249;207;183m  \033[38;2;249;207;183;48;2;249;177;135m▀ \033[38;2;237;129;87;48;2;183;93;45m▀\033[38;2;171;63;27;48;2;153;15;5m▀\033[48;2;219;9;4m▀\033[38;2;213;63;33;48;2;117;57;33m▀\033[38;2;117;57;33;48;2;135;129;129m▀\033[38;2;9;15;21;48;2;0;0;0m▀\033[38;2;87;87;87;48;2;57;57;39m▀▀\033[0m
\033[48;2;0;0;0m \033[48;2;9;15;21m \033[38;2;9;15;21;48;2;39;33;33m▀\033[38;2;39;33;33;48;2;57;57;39m▀\033[48;2;87;87;87m▀▀\033[38;2;0;0;0;48;2;9;15;21m▀\033[38;2;9;15;21;48;2;0;0;0m▀\033[38;2;57;57;39;48;2;63;21;15m▀\033[48;2;189;129;99m \033[38;2;117;57;33;48;2;171;63;27m▀\033[38;2;87;87;87;48;2;0;0;0m▀\033[38;2;183;165;153m▀\033[38;2;249;207;183;48;2;57;57;39m▀\033[38;2;249;231;207;48;2;141;105;93m▀\033[38;2;237;129;87m▀\033[48;2;171;63;27m▀\033[48;2;123;87;33m▀\033[38;2;249;207;183;48;2;141;105;93m▀▀\033[38;2;249;177;135;48;2;183;165;153m▀\033[38;2;189;129;99;48;2;57;57;39m▀\033[38;2;123;87;33;48;2;0;0;0m▀\033[38;2;105;27;15;48;2;171;63;27m▀\033[38;2;219;9;4;48;2;117;57;33m▀\033[38;2;105;27;15;48;2;195;195;195m▀\033[38;2;195;195;195;48;2;183;165;153m▀\033[38;2;87;87;87;48;2;0;0;0m▀ \033[38;2;9;15;21m▀\033[38;2;39;27;27m▀\033[0m
\033[48;2;0;0;0m \033[38;2;9;15;21m▀\033[38;2;39;27;27;48;2;9;15;21m▀\033[38;2;57;57;39m▀▀\033[48;2;33;33;39m \033[38;2;33;33;39;48;2;57;57;39m▀\033[48;2;33;33;39m \033[48;2;63;21;15m \033[38;2;171;63;27;48;2;153;15;5m▀\033[38;2;105;27;15;48;2;39;9;2m▀\033[38;2;9;15;93;48;2;9;15;51m▀\033[48;2;27;33;93m▀\033[38;2;9;15;51;48;2;9;15;93m▀\033[38;2;33;33;39;48;2;57;57;39m▀\033[38;2;195;195;195;48;2;249;231;207m▀\033[48;2;27;33;57m \033[38;2;9;15;21m▀\033[38;2;135;129;129;48;2;87;87;87m▀\033[38;2;141;105;93;48;2;243;219;0m▀\033[38;2;183;165;153;48;2;177;135;21m▀\033[38;2;87;87;87;48;2;201;171;0m▀\033[38;2;99;9;2m▀\033[38;2;63;21;15;48;2;123;87;33m▀\033[38;2;87;87;87;48;2;243;219;0m▀\033[38;2;141;105;93;48;2;201;171;0m▀\033[48;2;57;57;39m \033[0m
\033[48;2;0;0;0m    \033[38;2;39;27;27m▀\033[48;2;39;27;27m \033[38;2;123;87;33;48;2;57;57;39m▀ \033[48;2;63;21;15m \033[38;2;99;9;2;48;2;123;87;33m▀\033[38;2;39;27;27;48;2;177;135;21m▀\033[38;2;39;9;2;48;2;123;87;33m▀\033[48;2;9;15;51m \033[38;2;9;15;93m▀\033[48;2;27;33;93m \033[48;2;249;231;207m \033[38;2;195;195;195m▀▀\033[38;2;123;87;33;48;2;177;135;21m▀\033[48;2;243;219;0m \033[38;2;201;171;0;48;2;123;87;33m▀▀\033[48;2;153;15;5m▀\033[38;2;177;135;21m▀\033[38;2;243;219;0;48;2;177;135;21m▀\033[48;2;201;171;0m▀\033[48;2;57;57;39m \033[0m
\033[48;2;0;0;0m     \033[48;2;39;9;2m \033[38;2;63;21;15m▀▀\033[38;2;57;57;39m▀\033[38;2;123;87;33;48;2;63;21;15m▀\033[38;2;177;135;21;48;2;117;57;33m▀\033[38;2;63;21;15;48;2;0;0;0m▀\033[48;2;9;15;21m \033[48;2;9;15;51m \033[38;2;27;33;57m▀\033[38;2;195;195;195;48;2;87;87;87m▀▀\033[38;2;183;165;153m▀\033[38;2;57;57;39;48;2;135;129;129m▀\033[38;2;105;27;15;48;2;57;57;39m▀\033[38;2;189;129;99;48;2;183;93;45m▀\033[38;2;225;111;51;48;2;171;63;27m▀\033[48;2;213;63;33m \033[48;2;219;9;4m \033[38;2;57;57;39;48;2;63;21;15m▀\033[48;2;33;33;39m▀\033[38;2;39;33;33;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m      \033[38;2;39;9;2m▀\033[48;2;39;9;2m \033[38;2;63;21;15m▀ \033[38;2;9;15;21;48;2;33;33;39m▀\033[38;2;57;57;39;48;2;87;87;87m▀  \033[38;2;27;33;57m▀ \033[38;2;135;129;129m▀\033[48;2;9;15;21m▀\033[38;2;183;165;153;48;2;33;33;39m▀\033[38;2;87;87;87;48;2;57;57;39m▀\033[38;2;171;63;27;48;2;105;27;15m▀\033[38;2;153;15;5m▀\033[38;2;171;63;27;48;2;153;15;5m▀\033[38;2;153;15;5;48;2;105;27;15m▀\033[38;2;105;27;15;48;2;99;9;2m▀\033[48;2;33;33;39m \033[0m
\033[48;2;0;0;0m        \033[38;2;39;9;2m▀▀\033[38;2;33;33;39;48;2;9;15;21m▀\033[38;2;57;57;39;48;2;39;27;27m▀\033[48;2;39;33;33m▀▀\033[38;2;87;87;87m▀\033[48;2;57;57;39m▀\033[48;2;39;33;33m \033[48;2;0;0;0m  \033[38;2;39;27;27m▀\033[38;2;39;33;33;48;2;9;15;21m▀▀\033[38;2;99;9;2;48;2;39;9;2m▀▀\033[48;2;39;27;27m▀ \033[48;2;9;15;21m \033[0m
==>04-chibi
\033[48;2;0;0;0m             \033[38;2;0;0;0;48;2;33;33;39m▀▀▀▀▀▀▀▀▀▀▀\033[0m
\033[48;2;0;0;0m         \033[38;2;0;0;0;48;2;33;33;39m▀\033[48;2;45;51;51m▀\033[38;2;33;33;39m▀\033[48;2;63;63;63m▀\033[38;2;45;51;51;48;2;69;75;75m▀▀\033[38;2;63;63;63;48;2;81;87;87m▀▀\033[48;2;87;93;99m▀▀▀▀\033[48;2;81;87;87m▀\033[38;2;45;51;51m▀\033[48;2;69;75;75m▀\033[38;2;33;33;39;48;2;63;63;63m▀▀\033[48;2;45;51;51m▀\033[38;2;0;0;0;48;2;33;33;39m▀▀\033[0m
\033[48;2;0;0;0m      \033[38;2;0;0;0;48;2;33;33;39m▀\033[38;2;33;33;39;48;2;45;51;51m▀\033[48;2;63;63;63m▀\033[38;2;45;51;51;48;2;69;75;75m▀\033[38;2;63;63;63;48;2;93;93;99m▀\033[38;2;69;75;75;48;2;99;99;99m▀\033[38;2;81;87;87;48;2;117;117;117m▀\033[38;2;99;99;99;48;2;123;129;129m▀▀\033[38;2;105;111;117;48;2;135;141;147m▀▀▀\033[38;2;117;117;117;48;2;135;147;153m▀▀\033[48;2;135;141;147m▀▀\033[38;2;105;111;117m▀\033[38;2;99;99;99;48;2;123;129;129m▀\033[38;2;93;93;99;48;2;117;117;117m▀\033[38;2;81;87;87;48;2;105;111;117m▀\033[38;2;69;75;75;48;2;93;93;99m▀\033[38;2;63;63;63;48;2;81;87;87m▀\033[38;2;45;51;51;48;2;63;63;63m▀\033[38;2;33;33;39;48;2;45;51;51m▀\033[38;2;0;0;0;48;2;33;33;39m▀\033[0m
\033[48;2;0;0;0m    \033[38;2;0;0;0;48;2;33;33;39m▀\033[38;2;33;33;39;48;2;45;51;51m▀\033[38;2;45;51;51;48;2;63;63;63m▀\033[38;2;63;63;63;48;2;81;87;87m▀\033[38;2;81;87;87;48;2;99;99;99m▀\033[38;2;99;99;99;48;2;117;117;117m▀\033[38;2;117;117;117;48;2;135;141;147m▀\033[38;2;123;129;129;48;2;153;153;153m▀\033[38;2;135;141;147;48;2;147;153;159m▀\033[38;2;135;147;153;48;2;165;177;177m▀\033[38;2;147;153;159;48;2;183;183;183m▀\033[38;2;159;165;171;48;2;183;189;189m▀\033[48;2;171;189;195m▀▀▀\033[38;2;165;177;177;48;2;189;201;201m▀\033[38;2;159;165;171;48;2;171;189;195m▀▀\033[48;2;183;189;189m▀\033[38;2;153;153;153;48;2;183;183;183m▀\033[38;2;135;141;147;48;2;159;165;171m▀\033[38;2;123;129;129;48;2;153;153;153m▀\033[38;2;117;117;117;48;2;135;141;147m▀\033[38;2;99;99;99;48;2;123;129;129m▀\033[38;2;81;87;87;48;2;105;111;117m▀\033[38;2;69;75;75;48;2;93;93;99m▀\033[38;2;45;51;51;48;2;69;75;75m▀\033[38;2;33;33;39;48;2;45;51;51m▀\033[38;2;0;0;0;48;2;33;33;39m▀\033[0m
\033[48;2;0;0;0m   \033[48;2;33;33;39m \033[38;2;45;51;51;48;2;63;63;63m▀\033[38;2;63;63;63;48;2;81;87;87m▀\033[38;2;81;87;87;48;2;99;99;99m▀\033[38;2;99;99;99;48;2;123;129;129m▀\033[38;2;123;129;129;48;2;135;141;147m▀\033[38;2;135;141;147;48;2;147;153;159m▀\033[38;2;147;153;159;48;2;165;177;177m▀\033[38;2;159;165;171;48;2;171;189;195m▀\033[38;2;165;177;177;48;2;189;201;201m▀\033[38;2;171;189;195;48;2;201;225;231m▀\033[38;2;201;207;207;48;2;213;237;243m▀\033[38;2;207;213;213;48;2;219;243;243m▀\033[38;2;201;225;231;48;2;231;249;249m▀▀\033[48;2;249;255;255m▀\033[48;2;231;249;249m▀▀▀\033[38;2;207;213;213;48;2;219;243;243m▀\033[38;2;201;207;207;48;2;219;219;219m▀\033[38;2;183;189;189m▀\033[38;2;183;183;183;48;2;201;207;207m▀\033[38;2;159;165;171;48;2;183;189;189m▀\033[38;2;153;153;153;48;2;159;165;171m▀\033[38;2;123;129;129;48;2;153;153;153m▀\033[38;2;105;111;117;48;2;123;129;129m▀\033[38;2;93;93;99;48;2;105;111;117m▀\033[38;2;69;75;75;48;2;93;93;99m▀\033[38;2;45;51;51;48;2;63;63;63m▀\033[38;2;33;33;39;48;2;45;51;51m▀\033[38;2;0;0;0;48;2;33;33;39m▀\033[0m
\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;33;33;39m▀\033[38;2;33;33;39;48;2;45;51;51m▀\033[38;2;45;51;51;48;2;69;75;75m▀ \033[38;2;99;99;99;48;2;81;87;87m▀\033[38;2;117;117;117;48;2;135;141;147m▀\033[38;2;135;141;147;48;2;147;153;159m▀\033[38;2;147;153;159;48;2;165;177;177m▀\033[38;2;165;177;177;48;2;171;189;195m▀\033[38;2;171;189;195;48;2;201;225;231m▀\033[38;2;207;213;213;48;2;213;237;243m▀\033[38;2;201;225;231;48;2;219;243;243m▀\033[38;2;231;249;249;48;2;249;255;255m▀\033[48;2;231;249;249m     \033[38;2;249;255;255m▀▀ \033[38;2;231;249;249;48;2;219;243;243m▀\033[38;2;249;255;255;48;2;231;249;249m▀\033[38;2;231;249;249;48;2;249;255;255m▀\033[38;2;219;219;219m▀\033[38;2;201;207;207;48;2;219;219;219m▀\033[38;2;183;189;189;48;2;201;207;207m▀\033[38;2;165;177;177;48;2;183;189;189m▀\033[38;2;153;153;153;48;2;159;165;171m▀\033[38;2;123;129;129;48;2;135;141;147m▀\033[38;2;111;99;99;48;2;117;117;117m▀\033[38;2;81;87;87;48;2;87;93;99m▀\033[38;2;63;63;63;48;2;45;51;51m▀\033[38;2;0;0;0;48;2;33;33;39m▀▀\033[0m
\033[48;2;0;0;0m \033[48;2;33;33;39m \033[38;2;45;51;51;48;2;63;63;63m▀\033[38;2;81;87;87;48;2;87;93;99m▀\033[38;2;99;99;99;48;2;105;111;117m▀\033[38;2;63;63;63;48;2;123;129;129m▀\033[38;2;135;141;147;48;2;147;153;159m▀\033[38;2;159;165;171;48;2;171;189;195m▀\033[38;2;171;189;195;48;2;207;213;213m▀\033[38;2;207;213;213;48;2;219;243;243m▀\033[38;2;231;249;249;48;2;249;255;255m▀\033[38;2;219;243;243;48;2;231;249;249m▀\033[38;2;231;249;249;48;2;249;255;255m▀      ▀▀\033[48;2;231;249;249m  \033[38;2;219;243;243m▀ \033[38;2;249;255;255m▀\033[38;2;231;249;249;48;2;249;255;255m▀\033[38;2;219;219;219m▀\033[38;2;201;207;207;48;2;207;213;213m▀\033[38;2;165;177;177;48;2;189;201;201m▀\033[38;2;147;153;159;48;2;165;177;177m▀\033[38;2;123;129;129;48;2;153;153;153m▀\033[38;2;99;99;99;48;2;123;129;129m▀\033[38;2;81;87;87;48;2;99;99;99m▀\033[38;2;63;63;63;48;2;69;75;75m▀\033[38;2;33;33;39;48;2;45;51;51m▀\033[38;2;0;0;0;48;2;33;33;39m▀\033[0m
\033[48;2;33;33;39m \033[48;2;45;51;51m \033[38;2;69;75;75;48;2;81;87;87m▀\033[38;2;99;99;99;48;2;105;111;117m▀\033[48;2;123;129;129m \033[38;2;135;147;153;48;2;147;153;159m▀\033[38;2;165;177;177;48;2;183;189;189m▀\033[38;2;201;207;207;48;2;207;213;213m▀\033[38;2;219;219;219;48;2;231;249;249m▀\033[38;2;249;255;255;48;2;219;243;243m▀\033[38;2;231;249;249m▀\033[48;2;249;255;255m           \033[48;2;231;249;249m  \033[48;2;249;255;255m \033[38;2;249;255;255;48;2;231;249;249m▀\033[48;2;105;111;117m▀\033[38;2;183;189;189;48;2;201;207;207m▀\033[38;2;219;219;219;48;2;249;255;255m▀\033[38;2;207;213;213;48;2;219;219;219m▀\033[38;2;183;183;183;48;2;189;201;201m▀\033[38;2;153;153;153;48;2;165;177;177m▀\033[48;2;135;141;147m \033[38;2;105;111;117;48;2;117;117;117m▀\033[38;2;81;87;87;48;2;93;93;99m▀\033[48;2;63;63;63m \033[48;2;33;33;39m \033[0m
\033[48;2;33;33;39m \033[48;2;63;63;63m \033[38;2;81;87;87;48;2;87;93;99m▀\033[48;2;117;117;117m \033[48;2;135;141;147m \033[48;2;165;177;177m \033[48;2;189;201;201m \033[38;2;219;219;219;48;2;201;225;231m▀\033[48;2;219;243;243m \033[38;2;219;243;243;48;2;231;249;249m▀\033[38;2;231;249;249;48;2;219;243;243m▀\033[48;2;249;255;255m           \033[48;2;231;249;249m \033[38;2;231;249;249;48;2;249;255;255m▀\033[38;2;249;255;255;48;2;231;249;249m▀\033[38;2;219;219;219;48;2;249;255;255m▀\033[38;2;183;183;183m▀\033[38;2;249;255;255;48;2;231;249;249m▀ \033[48;2;219;243;243m \033[48;2;201;207;207m \033[38;2;165;177;177;48;2;183;183;183m▀\033[38;2;147;153;159;48;2;153;153;153m▀\033[48;2;123;129;129m \033[38;2;99;99;99;48;2;93;93;99m▀\033[38;2;63;63;63;48;2;69;75;75m▀\033[38;2;33;33;39;48;2;45;51;51m▀\033[38;2;0;0;0;48;2;33;33;39m▀\033[0m
\033[38;2;33;33;39;48;2;0;0;0m▀\033[38;2;69;75;75;48;2;63;63;63m▀\033[38;2;93;93;99;48;2;99;99;99m▀\033[38;2;117;117;117;48;2;123;129;129m▀\033[38;2;135;147;153;48;2;153;153;153m▀\033[38;2;165;177;177;48;2;183;183;183m▀\033[38;2;189;201;201;48;2;201;207;207m▀\033[38;2;213;237;243;48;2;219;243;243m▀\033[38;2;249;255;255;48;2;183;159;159m▀▀\033[48;2;243;207;207m▀\033[38;2;231;249;249;48;2;249;255;255m▀        \033[38;2;249;255;255;48;2;231;249;249m▀\033[48;2;219;243;243m \033[38;2;249;255;255;48;2;231;249;249m▀\033[48;2;249;255;255m \033[48;2;231;249;249m \033[38;2;249;255;255m▀ \033[48;2;219;243;243m▀\033[48;2;231;249;249m▀\033[38;2;231;249;249;48;2;213;237;243m▀\033[38;2;207;213;213;48;2;201;207;207m▀\033[38;2;183;183;183;48;2;165;177;177m▀\033[38;2;153;153;153;48;2;135;147;153m▀\033[48;2;123;129;129m \033[38;2;87;93;99;48;2;99;99;99m▀\033[48;2;69;75;75m \033[48;2;45;51;51m \033[48;2;33;33;39m \033[0m
\033[48;2;0;0;0m \033[38;2;45;51;51;48;2;33;33;39m▀\033[38;2;99;99;99;48;2;93;93;99m▀\033[48;2;123;129;129m \033[48;2;153;153;153m \033[48;2;183;183;183m \033[38;2;207;213;213;48;2;201;207;207m▀\033[48;2;207;213;213m \033[38;2;117;117;117;48;2;177;207;231m▀\033[38;2;123;129;129;48;2;129;219;249m▀\033[38;2;99;99;99;48;2;177;207;231m▀\033[48;2;183;159;159m \033[38;2;249;255;255;48;2;201;207;207m▀\033[48;2;249;255;255m    \033[38;2;249;255;255;48;2;231;249;249m▀\033[48;2;249;255;255m \033[38;2;231;249;249m▀\033[38;2;249;255;255;48;2;243;207;207m▀▀\033[48;2;219;243;243m \033[38;2;249;255;255;48;2;231;249;249m▀\033[48;2;249;255;255m  \033[48;2;231;249;249m \033[48;2;219;243;243m \033[48;2;231;249;249m \033[48;2;213;237;243m \033[38;2;177;207;231;48;2;189;201;201m▀\033[38;2;171;189;195;48;2;165;177;177m▀\033[38;2;129;153;165;48;2;135;147;153m▀\033[48;2;123;129;129m \033[38;2;99;99;99;48;2;87;93;99m▀\033[48;2;69;75;75m \033[48;2;45;51;51m \033[48;2;33;33;39m \033[0m
\033[48;2;33;33;39m  \033[48;2;81;87;87m \033[48;2;117;117;117m \033[38;2;153;153;153;48;2;135;141;147m▀\033[48;2;165;177;177m \033[38;2;189;201;201;48;2;153;153;153m▀\033[38;2;183;159;159;48;2;123;129;129m▀\033[38;2;129;219;249;48;2;189;201;201m▀\033[48;2;177;207;231m▀\033[48;2;171;189;195m▀\033[38;2;249;255;255;48;2;243;207;207m▀\033[38;2;201;207;207;48;2;219;243;243m▀\033[48;2;249;255;255m \033[38;2;231;249;249m▀▀  \033[38;2;249;255;255;48;2;165;177;177m▀\033[38;2;153;153;153;48;2;129;153;165m▀\033[38;2;111;99;99;48;2;219;219;219m▀\033[48;2;201;207;207m▀\033[38;2;201;207;207;48;2;207;213;213m▀\033[38;2;231;249;249;48;2;249;255;255m▀\033[48;2;231;249;249m \033[38;2;249;255;255;48;2;213;237;243m▀\033[48;2;231;249;249m \033[38;2;219;243;243m▀\033[48;2;219;243;243m \033[48;2;201;225;231m \033[48;2;189;201;201m \033[38;2;165;177;177;48;2;159;165;171m▀\033[38;2;135;147;153;48;2;135;141;147m▀\033[48;2;105;111;117m \033[48;2;87;93;99m \033[38;2;69;75;75;48;2;63;63;63m▀\033[38;2;45;51;51;48;2;33;33;39m▀\033[38;2;33;33;39;48;2;0;0;0m▀\033[0m
\033[38;2;33;33;39;48;2;0;0;0m▀\033[48;2;33;33;39m \033[38;2;81;87;87;48;2;69;75;75m▀\033[38;2;111;99;99;48;2;93;93;99m▀\033[48;2;123;129;129m \033[38;2;153;153;153;48;2;81;87;87m▀\033[38;2;63;63;63;48;2;33;33;39m▀\033[38;2;159;165;171;48;2;153;153;153m▀\033[38;2;183;183;183;48;2;219;219;219m▀\033[38;2;243;207;207m▀\033[48;2;249;255;255m▀      \033[48;2;219;243;243m \033[48;2;129;219;249m \033[38;2;129;219;249;48;2;177;207;231m▀\033[48;2;243;207;207m \033[38;2;249;255;255;48;2;201;225;231m▀\033[48;2;249;255;255m  \033[48;2;231;249;249m \033[48;2;219;243;243m \033[38;2;219;243;243;48;2;231;249;249m▀\033[38;2;231;249;249;48;2;219;243;243m▀\033[38;2;213;237;243;48;2;201;225;231m▀\033[38;2;207;213;213;48;2;189;201;201m▀\033[38;2;171;189;195;48;2;165;177;177m▀\033[38;2;159;165;171;48;2;135;147;153m▀\033[48;2;123;129;129m \033[38;2;105;111;117;48;2;93;93;99m▀\033[38;2;81;87;87;48;2;69;75;75m▀\033[38;2;63;63;63;48;2;45;51;51m▀\033[48;2;33;33;39m \033[0m
\033[48;2;0;0;0m \033[48;2;33;33;39m \033[38;2;63;63;63;48;2;45;51;51m▀\033[38;2;93;93;99;48;2;63;63;63m▀\033[38;2;81;87;87;48;2;33;33;39m▀\033[38;2;33;33;39;48;2;105;111;117m▀\033[48;2;45;51;51m▀\033[38;2;93;93;99;48;2;33;33;39m▀\033[38;2;219;219;219;48;2;135;147;153m▀\033[38;2;219;243;243;48;2;219;219;219m▀\033[38;2;249;255;255;48;2;219;243;243m▀\033[48;2;249;255;255m    \033[38;2;249;255;255;48;2;243;207;207m▀\033[48;2;249;255;255m  \033[38;2;243;207;207m▀\033[48;2;231;249;249m▀\033[38;2;183;183;183;48;2;219;219;219m▀\033[38;2;189;201;201;48;2;249;255;255m▀  \033[48;2;231;249;249m \033[48;2;219;243;243m \033[38;2;219;243;243;48;2;213;237;243m▀\033[38;2;213;237;243;48;2;207;213;213m▀\033[38;2;201;207;207;48;2;189;201;201m▀\033[38;2;183;189;189;48;2;165;177;177m▀\033[38;2;159;165;171;48;2;147;153;159m▀\033[38;2;135;141;147;48;2;117;117;117m▀\033[38;2;105;111;117;48;2;93;93;99m▀\033[38;2;81;87;87;48;2;63;81;87m▀\033[38;2;63;63;63;48;2;45;51;51m▀\033[38;2;45;51;51;48;2;33;33;39m▀\033[38;2;33;33;39;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m  \033[48;2;33;33;39m  \033[48;2;69;75;75m \033[38;2;117;117;117;48;2;87;93;99m▀\033[38;2;81;87;87;48;2;105;111;117m▀\033[38;2;33;33;39;48;2;87;93;99m▀\033[38;2;123;129;129;48;2;147;153;159m▀\033[38;2;189;201;201;48;2;159;165;171m▀\033[38;2;207;213;213;48;2;171;189;195m▀\033[38;2;219;243;243;48;2;207;213;213m▀\033[38;2;249;255;255;48;2;219;219;219m▀\033[48;2;231;249;249m▀\033[48;2;249;255;255m     \033[38;2;249;255;255;48;2;159;165;171m▀\033[48;2;207;213;213m▀\033[48;2;249;255;255m  \033[38;2;249;255;255;48;2;213;237;243m▀\033[38;2;219;243;243;48;2;201;225;231m▀\033[38;2;213;237;243;48;2;201;207;207m▀\033[38;2;201;225;231;48;2;189;201;201m▀\033[38;2;189;201;201;48;2;165;177;177m▀\033[38;2;165;177;177;48;2;159;165;171m▀\033[38;2;153;153;153;48;2;135;141;147m▀\033[38;2;123;129;129;48;2;105;111;117m▀\033[38;2;105;111;117;48;2;87;93;99m▀\033[38;2;81;87;87;48;2;69;75;75m▀\033[38;2;63;63;63;48;2;45;51;51m▀\033[38;2;45;51;51;48;2;33;33;39m▀\033[38;2;33;33;39;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m   \033[38;2;33;33;39m▀\033[38;2;45;51;51;48;2;33;33;39m▀\033[38;2;69;75;75;48;2;63;63;63m▀\033[38;2;87;93;99;48;2;69;75;75m▀\033[38;2;117;117;117;48;2;87;93;99m▀\033[38;2;123;129;129;48;2;105;111;117m▀\033[38;2;135;147;153;48;2;123;129;129m▀\033[38;2;159;165;171;48;2;135;147;153m▀\033[38;2;123;129;129;48;2;63;63;63m▀\033[38;2;171;189;195;48;2;45;51;51m▀\033[38;2;219;219;219;48;2;105;111;117m▀\033[48;2;147;153;159m▀\033[38;2;219;243;243;48;2;63;81;87m▀\033[38;2;219;219;219m▀\033[38;2;249;255;255;48;2;165;177;177m▀\033[38;2;123;129;129;48;2;45;51;51m▀\033[38;2;81;87;87;48;2;135;147;153m▀\033[38;2;249;255;255;48;2;231;249;249m▀\033[48;2;165;177;177m▀\033[38;2;219;219;219;48;2;183;189;189m▀\033[38;2;201;207;207;48;2;171;189;195m▀\033[38;2;189;201;201;48;2;165;177;177m▀\033[38;2;171;189;195;48;2;153;153;153m▀\033[38;2;165;177;177;48;2;135;141;147m▀\033[38;2;153;153;153m▀\033[38;2;135;141;147;48;2;117;117;117m▀\033[38;2;105;111;117;48;2;87;93;99m▀\033[38;2;87;93;99;48;2;63;81;87m▀\033[38;2;63;81;87;48;2;63;63;63m▀\033[38;2;63;63;63;48;2;33;33;39m▀ \033[0m
\033[48;2;0;0;0m    \033[38;2;33;33;39m▀\033[48;2;33;33;39m \033[38;2;45;51;51m▀\033[38;2;63;63;63m▀\033[38;2;45;51;51;48;2;0;0;0m▀\033[38;2;69;75;75;48;2;33;33;39m▀\033[38;2;81;87;87m▀\033[38;2;45;51;51m▀  ▀\033[48;2;45;51;51m  \033[38;2;69;75;75m▀\033[38;2;45;51;51;48;2;87;93;99m▀\033[38;2;183;189;189;48;2;165;177;177m▀\033[38;2;123;129;129;48;2;87;93;99m▀\033[38;2;93;93;99;48;2;135;141;147m▀\033[38;2;183;189;189;48;2;153;153;153m▀\033[38;2;159;165;171;48;2;111;99;99m▀\033[38;2;153;153;153;48;2;63;63;63m▀\033[38;2;117;117;117;48;2;63;81;87m▀\033[38;2;87;93;99m▀\033[38;2;105;111;117m▀\033[38;2;87;93;99;48;2;69;75;75m▀\033[38;2;63;81;87;48;2;63;63;63m▀\033[38;2;63;63;63;48;2;45;51;51m▀\033[38;2;45;51;51;48;2;33;33;39m▀\033[38;2;33;33;39;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m        \033[48;2;33;33;39m  \033[38;2;33;33;39;48;2;0;0;0m▀\033[48;2;33;33;39m \033[38;2;33;33;39;48;2;63;63;63m▀\033[48;2;45;51;51m▀\033[48;2;33;33;39m \033[38;2;33;33;39;48;2;93;93;99m▀\033[38;2;45;51;51;48;2;69;75;75m▀\033[48;2;45;51;51m \033[38;2;123;129;129;48;2;117;117;117m▀\033[38;2;135;141;147;48;2;99;99;99m▀\033[38;2;99;99;99;48;2;69;75;75m▀\033[38;2;117;117;117;48;2;63;63;63m▀\033[38;2;81;87;87;48;2;45;51;51m▀\033[38;2;45;51;51;48;2;63;81;87m▀\033[38;2;63;81;87;48;2;81;87;87m▀\033[38;2;87;93;99;48;2;69;75;75m▀\033[38;2;81;87;87;48;2;63;63;63m▀\033[38;2;63;63;63;48;2;45;51;51m▀\033[38;2;45;51;51;48;2;33;33;39m▀\033[38;2;33;33;39;48;2;0;0;0m▀▀\033[0m
==>05-catgirl
\033[48;2;0;0;0m     \033[38;2;0;0;0;48;2;21;21;15m▀▀  ▀\033[48;2;57;45;27m▀▀▀▀\033[48;2;21;21;15m▀▀▀▀▀\033[48;2;57;45;27m▀\033[48;2;105;81;39m▀\033[48;2;111;99;75m▀\033[48;2;87;87;69m▀\033[48;2;57;63;57m▀\033[48;2;21;21;15m▀\033[48;2;57;45;27m▀▀\033[0m
\033[48;2;0;0;0m    \033[48;2;21;21;15m   \033[38;2;57;63;57;48;2;57;45;27m▀\033[48;2;105;45;15m▀\033[38;2;57;45;27;48;2;147;93;27m▀\033[38;2;105;81;39;48;2;105;45;15m▀  \033[38;2;105;45;15;48;2;153;51;9m▀\033[38;2;153;51;9;48;2;171;93;27m▀\033[48;2;189;93;21m▀\033[38;2;171;93;27m▀▀\033[38;2;135;99;69;48;2;153;51;9m▀\033[38;2;153;129;87;48;2;213;99;21m▀\033[38;2;159;177;171;48;2;159;165;141m▀\033[38;2;135;183;195;48;2;105;135;153m▀\033[38;2;129;147;147;48;2;57;63;57m▀\033[38;2;57;87;111;48;2;51;69;81m▀\033[38;2;51;69;81;48;2;57;87;111m▀▀▀\033[0m
\033[48;2;0;0;0m    \033[48;2;21;21;15m \033[48;2;57;63;57m \033[38;2;21;21;15;48;2;57;45;27m▀\033[38;2;57;45;27;48;2;105;81;39m▀\033[48;2;147;93;27m  \033[38;2;105;45;15;48;2;153;51;9m▀\033[38;2;153;51;9;48;2;207;123;39m▀\033[38;2;189;93;21m▀\033[38;2;207;123;39;48;2;213;153;81m▀\033[48;2;189;93;21m▀\033[38;2;225;129;33;48;2;213;99;21m▀\033[48;2;225;129;33m  \033[38;2;225;129;33;48;2;237;159;69m▀\033[38;2;153;51;9;48;2;213;99;21m▀\033[48;2;153;51;9m \033[38;2;105;45;15m▀\033[38;2;153;51;9;48;2;105;45;15m▀\033[38;2;105;81;39;48;2;153;51;9m▀\033[38;2;57;87;111;48;2;123;129;123m▀\033[48;2;105;135;153m▀\033[38;2;51;69;81;48;2;57;63;57m▀\033[0m
\033[48;2;0;0;0m    \033[48;2;21;21;15m \033[38;2;57;63;57;48;2;57;45;27m▀\033[38;2;57;45;27;48;2;105;81;39m▀\033[38;2;147;93;27;48;2;171;93;27m▀ \033[38;2;171;93;27;48;2;189;93;21m▀\033[38;2;207;123;39;48;2;225;129;33m▀\033[48;2;189;93;21m▀\033[48;2;225;129;33m \033[38;2;207;123;39;48;2;189;93;21m▀\033[38;2;213;99;21;48;2;171;93;27m▀\033[38;2;207;123;39;48;2;219;141;81m▀\033[48;2;225;129;33m \033[38;2;225;129;33;48;2;207;123;39m▀\033[38;2;237;159;69;48;2;225;129;33m▀\033[48;2;237;159;69m \033[38;2;213;99;21;48;2;225;129;33m▀\033[38;2;153;51;9m▀\033[48;2;189;93;21m▀\033[48;2;153;51;9m \033[38;2;123;129;123;48;2;123;117;93m▀\033[48;2;57;87;111m \033[48;2;21;21;15m \033[0m
\033[48;2;0;0;0m    \033[38;2;0;0;0;48;2;21;21;15m▀\033[38;2;57;45;27;48;2;57;63;57m▀\033[38;2;147;93;27;48;2;135;99;69m▀\033[38;2;171;93;27;48;2;189;93;21m▀\033[38;2;207;123;39;48;2;213;153;81m▀\033[48;2;207;123;39m \033[38;2;213;99;21;48;2;189;93;21m▀\033[38;2;189;93;21;48;2;207;123;39m▀\033[38;2;207;123;39;48;2;189;129;75m▀\033[38;2;153;51;9;48;2;135;99;69m▀\033[38;2;135;99;69;48;2;153;129;87m▀\033[38;2;219;141;81;48;2;153;51;9m▀\033[38;2;225;129;33;48;2;213;99;21m▀\033[38;2;189;93;21;48;2;189;129;75m▀\033[38;2;213;99;21;48;2;189;93;21m▀\033[38;2;225;129;33;48;2;171;93;27m▀\033[48;2;207;123;39m▀\033[48;2;225;129;33m \033[38;2;171;93;27;48;2;207;123;39m▀\033[38;2;189;93;21;48;2;171;93;27m▀\033[48;2;135;99;69m \033[38;2;51;69;81;48;2;21;21;15m▀\033[0m
\033[48;2;0;0;0m  \033[48;2;21;21;15m \033[38;2;0;0;0m▀\033[48;2;57;45;27m▀\033[38;2;57;63;57;48;2;105;81;39m▀\033[38;2;189;129;75;48;2;171;93;27m▀\033[48;2;189;93;21m \033[38;2;207;123;39;48;2;225;129;33m▀\033[48;2;189;93;21m▀\033[48;2;105;81;39m▀\033[38;2;171;93;27;48;2;21;21;15m▀\033[38;2;153;129;87;48;2;129;147;147m▀▀\033[38;2;189;129;75;48;2;111;99;75m▀\033[38;2;153;51;9;48;2;171;93;27m▀\033[38;2;213;153;81;48;2;213;165;117m▀\033[38;2;213;165;117;48;2;249;213;171m▀\033[38;2;147;93;27;48;2;153;129;87m▀\033[48;2;171;93;27m \033[38;2;171;93;27;48;2;207;123;39m▀ \033[38;2;189;93;21;48;2;153;51;9m▀\033[48;2;207;123;39m▀\033[48;2;87;87;69m \033[0m
\033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;21;21;15m▀\033[48;2;57;45;27m \033[48;2;51;69;81m \033[38;2;105;81;39;48;2;105;45;15m▀\033[48;2;189;93;21m  \033[48;2;225;129;33m \033[38;2;207;123;39;48;2;213;153;81m▀\033[38;2;159;153;123;48;2;213;165;117m▀\033[38;2;57;87;111;48;2;105;135;153m▀\033[38;2;21;21;15;48;2;57;87;111m▀\033[38;2;135;183;195;48;2;159;177;171m▀\033[38;2;213;219;177;48;2;249;213;171m▀\033[38;2;213;165;117m▀ \033[38;2;159;153;123;48;2;105;135;153m▀\033[38;2;123;129;123;48;2;57;87;111m▀\033[38;2;123;117;93;48;2;129;147;147m▀\033[38;2;189;93;21;48;2;171;93;27m▀\033[38;2;207;123;39;48;2;189;93;21m▀\033[38;2;153;51;9m▀\033[38;2;189;129;75;48;2;153;129;87m▀\033[38;2;57;63;57;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m \033[38;2;21;21;15;48;2;57;45;27m▀ \033[38;2;57;45;27;48;2;57;63;57m▀\033[38;2;51;69;81m▀\033[38;2;57;45;27;48;2;105;45;15m▀\033[38;2;189;93;21;48;2;153;51;9m▀\033[38;2;171;93;27m▀\033[48;2;189;93;21m \033[38;2;189;129;75;48;2;171;93;27m▀\033[48;2;213;165;117m \033[38;2;213;165;117;48;2;225;183;141m▀\033[38;2;189;183;147;48;2;249;213;171m▀    \033[38;2;129;147;147m▀\033[38;2;105;135;153m▀\033[38;2;225;183;141;48;2;213;165;117m▀\033[38;2;189;93;21;48;2;153;51;9m▀\033[38;2;171;93;27;48;2;147;93;27m▀\033[38;2;207;123;39;48;2;111;99;75m▀\033[38;2;87;87;69;48;2;21;21;15m▀\033[0m
\033[48;2;21;21;15m \033[48;2;57;45;27m \033[38;2;57;45;27;48;2;21;21;15m▀\033[38;2;21;21;15;48;2;57;45;27m▀\033[38;2;105;81;39;48;2;147;93;27m▀\033[38;2;147;93;27;48;2;105;45;15m▀ \033[38;2;153;51;9m▀\033[48;2;135;99;69m▀\033[38;2;171;93;27;48;2;189;129;75m▀\033[38;2;189;129;75;48;2;153;129;87m▀\033[38;2;213;165;117;48;2;189;129;75m▀\033[48;2;225;183;141m \033[48;2;249;213;171m \033[38;2;213;165;117;48;2;225;183;141m▀\033[38;2;219;141;81m▀\033[48;2;249;213;171m \033[38;2;249;213;171;48;2;225;183;141m▀\033[38;2;225;183;141;48;2;111;99;75m▀\033[38;2;153;129;87;48;2;21;21;15m▀\033[38;2;123;129;123;48;2;57;63;57m▀\033[38;2;57;87;111;48;2;105;135;153m▀\033[48;2;87;87;69m▀\033[0m
\033[38;2;21;21;15;48;2;0;0;0m▀\033[48;2;21;21;15m \033[48;2;57;45;27m \033[38;2;105;81;39m▀\033[48;2;105;45;15m \033[38;2;105;45;15;48;2;153;51;9m▀\033[48;2;105;45;15m \033[38;2;105;45;15;48;2;159;165;141m▀\033[38;2;159;153;123;48;2;213;219;177m▀\033[38;2;189;183;147m▀\033[38;2;213;219;177;48;2;231;249;213m▀\033[38;2;213;225;207;48;2;243;249;231m▀\033[38;2;189;183;147m▀\033[38;2;225;183;141;48;2;189;183;147m▀\033[38;2;249;213;171;48;2;213;99;21m▀\033[38;2;213;165;117m▀\033[38;2;207;123;39;48;2;135;99;69m▀\033[38;2;57;45;27;48;2;21;21;15m▀\033[38;2;0;0;0m▀▀▀\033[38;2;87;87;69;48;2;51;69;81m▀\033[38;2;57;63;57;48;2;21;21;15m▀\033[38;2;0;0;0;48;2;57;63;57m▀\033[48;2;111;99;75m▀\033[48;2;105;81;39m▀▀▀\033[48;2;57;45;27m▀\033[48;2;0;0;0m       \033[38;2;0;0;0;48;2;21;21;15m▀ \033[0m
\033[48;2;0;0;0m \033[48;2;21;21;15m \033[38;2;57;45;27m▀\033[48;2;57;45;27m \033[48;2;105;45;15m \033[38;2;105;45;15;48;2;105;81;39m▀\033[38;2;123;129;123;48;2;159;153;123m▀\033[38;2;159;165;141m▀\033[48;2;189;183;147m \033[48;2;213;219;177m \033[38;2;231;249;213m▀\033[48;2;231;249;213m \033[38;2;243;249;231m▀\033[38;2;231;249;213;48;2;243;249;231m▀\033[38;2;213;153;81;48;2;231;249;213m▀\033[38;2;213;99;21;48;2;213;165;117m▀\033[48;2;213;99;21m \033[38;2;207;123;39m▀\033[38;2;189;129;75;48;2;207;123;39m▀\033[38;2;213;153;81;48;2;219;141;81m▀\033[38;2;213;165;117m▀\033[38;2;189;183;147;48;2;213;165;117m▀\033[48;2;249;213;171m▀\033[38;2;225;183;141;48;2;243;249;231m▀\033[38;2;249;213;171m▀\033[38;2;213;225;207m▀\033[38;2;249;213;171m▀\033[38;2;225;183;141m▀\033[38;2;213;153;81;48;2;213;225;207m▀\033[38;2;111;99;75;48;2;237;159;69m▀\033[38;2;0;0;0;48;2;87;87;69m▀\033[0m
\033[48;2;0;0;0m \033[38;2;21;21;15m▀\033[48;2;21;21;15m \033[48;2;57;45;27m \033[38;2;57;45;27;48;2;57;63;57m▀\033[38;2;111;99;75;48;2;87;87;69m▀\033[38;2;123;129;123;48;2;123;117;93m▀\033[38;2;159;153;123m▀\033[38;2;159;165;141;48;2;159;153;123m▀\033[38;2;189;183;147;48;2;159;165;141m▀\033[38;2;213;219;177;48;2;189;183;147m▀\033[48;2;213;219;177m \033[38;2;231;249;213m▀\033[48;2;231;249;213m \033[38;2;243;249;231m▀▀\033[38;2;225;183;141;48;2;243;249;231m▀\033[38;2;213;99;21;48;2;249;213;171m▀\033[38;2;225;129;33;48;2;225;183;141m▀\033[48;2;213;165;117m▀\033[38;2;213;165;117;48;2;213;99;21m▀\033[38;2;225;183;141;48;2;237;159;69m▀\033[38;2;219;141;81;48;2;243;249;231m▀\033[38;2;207;123;39;48;2;123;129;123m▀\033[38;2;237;159;69;48;2;105;81;39m▀\033[38;2;225;183;141;48;2;171;93;27m▀\033[38;2;213;225;207;48;2;219;141;81m▀\033[38;2;243;249;231;48;2;213;165;117m▀\033[48;2;213;219;177m▀\033[38;2;225;183;141;48;2;213;165;117m▀\033[38;2;171;93;27;48;2;189;129;75m▀\033[48;2;21;21;15m \033[0m
\033[48;2;0;0;0m  \033[38;2;21;21;15m▀\033[38;2;57;45;27;48;2;21;21;15m▀\033[38;2;57;63;57;48;2;57;45;27m▀\033[38;2;87;87;69;48;2;57;63;57m▀\033[48;2;87;87;69m \033[38;2;123;117;93;48;2;111;99;75m▀\033[48;2;123;117;93m \033[38;2;159;153;123;48;2;123;129;123m▀\033[38;2;189;183;147;48;2;159;165;141m▀\033[48;2;189;183;147m \033[38;2;213;219;177m▀\033[48;2;213;219;177m \033[38;2;231;249;213m▀▀\033[38;2;243;249;231;48;2;231;249;213m▀\033[48;2;243;249;231m \033[38;2;231;249;213m▀\033[38;2;243;249;231;48;2;213;225;207m▀\033[38;2;213;219;177m▀\033[38;2;219;141;81m▀\033[38;2;243;249;231;48;2;249;213;171m▀\033[38;2;213;219;177;48;2;231;249;213m▀\033[38;2;57;63;57;48;2;237;159;69m▀\033[48;2;123;117;93m▀\033[38;2;111;99;75;48;2;0;0;0m▀\033[38;2;213;153;81;48;2;21;21;15m▀\033[38;2;213;165;117;48;2;153;129;87m▀\033[38;2;207;123;39;48;2;135;99;69m▀\033[38;2;153;129;87;48;2;87;87;69m▀\033[38;2;21;21;15m▀\033[0m
\033[48;2;0;0;0m   \033[38;2;21;21;15m▀\033[38;2;57;45;27;48;2;21;21;15m▀\033[38;2;57;63;57;48;2;57;45;27m▀\033[48;2;57;63;57m \033[38;2;87;87;69m▀\033[38;2;111;99;75;48;2;105;81;39m▀\033[38;2;123;117;93m▀\033[38;2;159;153;123;48;2;111;99;75m▀\033[48;2;135;99;69m▀\033[38;2;189;183;147;48;2;153;129;87m▀▀\033[38;2;213;219;177;48;2;159;153;123m▀\033[38;2;213;225;207;48;2;189;183;147m▀▀\033[38;2;231;249;213m▀▀\033[38;2;213;219;177m▀\033[38;2;213;225;207;48;2;213;219;177m▀\033[48;2;213;225;207m \033[38;2;249;213;171;48;2;213;219;177m▀\033[48;2;189;183;147m▀\033[38;2;207;123;39;48;2;159;177;171m▀\033[38;2;237;159;69;48;2;171;93;27m▀\033[38;2;123;117;93;48;2;189;129;75m▀\033[38;2;57;45;27;48;2;51;69;81m▀\033[38;2;87;87;69;48;2;0;0;0m▀\033[48;2;21;21;15m▀ \033[38;2;57;63;57m▀\033[38;2;21;21;15;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;21;21;15m▀\033[38;2;57;45;27;48;2;21;21;15m▀\033[48;2;57;45;27m \033[38;2;57;63;57m▀\033[38;2;87;87;69;48;2;57;63;57m▀\033[38;2;111;99;75;48;2;105;81;39m▀\033[38;2;123;117;93m▀\033[48;2;111;99;75m▀\033[38;2;159;153;123;48;2;135;99;69m▀\033[48;2;123;117;93m▀\033[48;2;159;153;123m \033[48;2;159;165;141m \033[38;2;159;165;141;48;2;159;177;171m▀\033[48;2;159;165;141m \033[38;2;189;183;147m▀\033[38;2;159;177;171m▀\033[48;2;159;153;123m▀\033[38;2;189;183;147;48;2;153;129;87m▀\033[38;2;123;129;123;48;2;57;63;57m▀\033[38;2;135;183;195;48;2;129;147;147m▀\033[38;2;123;129;123;48;2;105;135;153m▀\033[38;2;171;93;27;48;2;105;81;39m▀\033[38;2;57;63;57;48;2;57;45;27m▀\033[0m
\033[48;2;0;0;0m       \033[38;2;21;21;15m▀\033[48;2;21;21;15m \033[38;2;57;45;27m▀\033[48;2;57;45;27m \033[38;2;57;45;27;48;2;57;63;57m▀\033[38;2;105;81;39m▀\033[48;2;87;87;69m▀\033[38;2;111;99;75m▀\033[38;2;123;117;93;48;2;111;99;75m▀\033[38;2;123;129;123m▀\033[38;2;123;117;93m▀\033[38;2;123;129;123;48;2;123;117;93m▀▀▀\033[38;2;159;153;123m▀\033[38;2;123;117;93;48;2;87;87;69m▀\033[38;2;57;45;27;48;2;21;21;15m▀\033[38;2;123;129;123;48;2;57;87;111m▀\033[48;2;87;87;69m▀\033[38;2;105;81;39;48;2;21;21;15m▀\033[38;2;21;21;15;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m         \033[38;2;21;21;15m▀▀\033[38;2;57;45;27;48;2;21;21;15m▀▀\033[38;2;57;63;57;48;2;57;45;27m▀▀▀\033[38;2;87;87;69m▀▀\033[48;2;57;63;57m▀▀\033[48;2;57;45;27m▀\033[38;2;105;81;39m▀\033[38;2;87;87;69m▀\033[38;2;21;21;15;48;2;0;0;0m▀\033[38;2;57;63;57;48;2;21;21;15m▀ \033[38;2;0;0;0m▀\033[38;2;21;21;15;48;2;0;0;0m▀▀\033[38;2;0;0;0;48;2;21;21;15m▀\033[38;2;21;21;15;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m             \033[38;2;21;21;15m▀▀   ▀\033[38;2;57;45;27m▀\033[48;2;21;21;15m▀▀\033[38;2;21;21;15;48;2;0;0;0m▀\033[0m
FOLIO_BANNER_ALL
    awk -v dir="$_fbdir" '
        /^==> ?[0-9A-Za-z._-]+$/ { name=substr($0,4); gsub(/^[ \t]+|[ \t]+$/, "", name); file=dir "/" name ".txt"; next }
        { if (file != "") print > file }
    ' "$_fbdir/.folio-banners.$$" 2>/dev/null || true
    rm -f "$_fbdir/.folio-banners.$$" 2>/dev/null || true
    printf '%s\n' "$_fbrev" > "$_fbdir/.banner-rev" 2>/dev/null || true
    cat > "$_home/.config/folio/banner.sh" <<'FB_SH'
# folio-banner.sh — Bliss launch sticker. Truecolor half-block anime art
# (no emoji), offline, random design per interactive shell. Opt out FLUX_NO_BANNER=1.
folio-banner-art() {
  # Prints one randomly picked design from ~/.config/folio/banner/*.txt.
  # The designs carry literal \033 truecolor escapes -> rendered with printf %b.
  setopt localoptions nullglob 2>/dev/null || true
  local -a _bans
  _bans=( "$HOME"/.config/folio/banner/*.txt )
  [[ -f "${_bans[1]}" ]] || return 0
  local _bi=$(( RANDOM % ${#_bans} + 1 ))
  case "$(cat "${_bans[$_bi]}" 2>/dev/null || true)" in
    *'\033'*) printf '%b\n' "$(cat "${_bans[$_bi]}")" ;;
    *)         cat "${_bans[$_bi]}" 2>/dev/null || true ;;
  esac
  unset _bans _bi
}
folio-banner() {
  [ "${FLUX_NO_BANNER:-0}" = "1" ] && return 0
  case $- in *i*) ;; *) return 0;; esac
  folio-banner-art
  # Header: exactly one line, "bliss@<id>" (distro id only — no host,
  # version or time). ID= from /etc/os-release; fallbacks: first word of
  # PRETTY_NAME (lowercased), then "linux".
  local _fid=""
  if [ -r /etc/os-release ]; then
    _fid="$(sed -n 's/^ID=//p' /etc/os-release 2>/dev/null | head -n 1 | tr -d '"' | tr '[:upper:]' '[:lower:]')"
    if [ -z "$_fid" ]; then
      _fid="$(sed -n 's/^PRETTY_NAME=//p' /etc/os-release 2>/dev/null | head -n 1 | tr -d '"' | awk '{print $1}' | tr '[:upper:]' '[:lower:]')"
    fi
  fi
  [ -n "$_fid" ] || _fid="linux"
  printf '%s\n' "bliss@$_fid"
  unset _fid
}
folio-banner
FB_SH
    _fneed=0
  fi
  unset _fbdir _fb _fneed _fbrev
  # Existing .zshrc: source the sticker when missing (idempotent, cheap grep).
  if [ -f "$_home/.zshrc" ] && ! grep -q "folio/banner.sh" "$_home/.zshrc" 2>/dev/null; then
    {
      printf '\n# Bliss launch sticker (random truecolor anime banner; opt out FLUX_NO_BANNER=1)\n'
      printf '[ -r "$HOME/.config/folio/banner.sh" ] && . "$HOME/.config/folio/banner.sh"\n'
    } >> "$_home/.zshrc" 2>/dev/null || true
  fi
  if [ ! -f "$_home/.zprofile" ]; then
    printf '%s\n' '[[ -o interactive ]] || { [ -f "$HOME/.zshrc" ] && . "$HOME/.zshrc"; }' \
      > "$_home/.zprofile"
  fi
  if [ -x "$FLUX_CHROOT/bin/zsh" ] && [ -f "$FLUX_CHROOT/etc/passwd" ] && \
     grep -q '^flux:' "$FLUX_CHROOT/etc/passwd" && \
     ! grep -q '^flux:.*zsh$' "$FLUX_CHROOT/etc/passwd"; then
    sed -i 's|^\(flux:[^:]*:[^:]*:[^:]*:[^:]*:[^:]*\):.*|\1:/bin/zsh|' \
      "$FLUX_CHROOT/etc/passwd" 2>/dev/null || true
  fi
  chown --reference="$_home" "$_home/.zshrc" "$_home/.zprofile" \
    "$_home/.oh-my-zsh/custom/themes/folio.zsh-theme" 2>/dev/null || true
  if [ -d "$_home/.config/folio" ]; then
    chown -R --reference="$_home" "$_home/.config/folio" 2>/dev/null || true
  fi
  # Bliss Linux appearance: re-apply the app-staged config when it changed.
  # Runs inside the chroot (guest tools + guest paths); the script is
  # hash-guarded, so repeated logins are cheap no-ops when nothing changed.
  if [ -r "$_home/.config/folio/appearance-apply.sh" ]; then
    "$BB" chroot "$FLUX_CHROOT" /bin/sh -c \
      'export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; \
       FOLIO_APPLY_HOME=/home/flux /bin/sh /home/flux/.config/folio/appearance-apply.sh \
       >/dev/null 2>&1 || true' >/dev/null 2>&1 || true
  fi
}

guest_login() {
  ensure_flux_zsh_profile
  LOGIN_SHELL="$(resolve_login_shell "${LOGIN_SHELL:-zsh}")"
  # Optional project cwd (workspace shell). Path must not contain single quotes.
  _cd=""
  if [ -n "${LOGIN_WORKDIR:-}" ]; then
    case "$LOGIN_WORKDIR" in
      *"'"*) die "workdir must not contain single quotes" ;;
    esac
    _cd="cd '$LOGIN_WORKDIR' 2>/dev/null || true; "
  fi
  case "$USER_NAME" in
    root)
      case "$LOGIN_SHELL" in
        zsh)
          if [ -n "$_cd" ]; then
            guest_chroot_env /bin/zsh -c "${_cd}exec /bin/zsh -l"
          else
            guest_chroot_env /bin/zsh -l
          fi
          ;;
        sh|ash)
          # Alpine/Chimera minirootfs — resolver may return sh when bash/zsh missing
          if [ -n "$_cd" ]; then
            guest_chroot_env /bin/sh -c "${_cd}exec /bin/sh -l"
          else
            guest_chroot_env /bin/sh -l
          fi
          ;;
        bash|*)
          if [ -n "$_cd" ]; then
            guest_chroot_env /bin/bash --login -c "${_cd}exec /bin/bash --login"
          else
            guest_chroot_env /bin/bash --login
          fi
          ;;
      esac
      ;;
    flux|*)
      guest_login_user
      ;;
  esac
}

# Non-interactive: one shell string as USER_NAME (single chroot + one su layer).
# Host-encode to base64 then guest_b64 — avoids double-quote/$/` breakage (G3).
guest_sh() {
  _cmd="$1"
  _b64=""
  if command -v base64 >/dev/null 2>&1; then
    _b64=$(printf '%s' "$_cmd" | base64 | tr -d '\n')
  elif [ -n "$BB" ]; then
    _b64=$(printf '%s' "$_cmd" | $BB base64 2>/dev/null | tr -d '\n')
  fi
  if [ -n "$_b64" ]; then
    guest_b64 "$_b64"
    return
  fi
  # Fallback only if host has no base64 (should not happen on Android root)
  # Prefer bash when present; Alpine minirootfs only has /bin/sh until bootstrap.
  _gshell=/bin/sh
  [ -x "${FLUX_CHROOT:-}/bin/bash" ] || [ -x /bin/bash ] && _gshell=/bin/bash
  if [ "$USER_NAME" = "root" ]; then
    guest_chroot_env "$_gshell" -c "$_cmd"
  else
    guest_as_user "$_gshell" -c "$_cmd"
  fi
}

# Argv-preserving exec (root: chroot + binary; flux: su -c with quoted argv).
guest_exec() {
  if [ "$#" -lt 1 ]; then
    die "exec requires CMD"
  fi
  if [ "$USER_NAME" = "root" ]; then
    guest_chroot_env "$@"
  else
    _joined=$(quote_argv "$@")
    _eshell=/bin/bash
    guest_bin_exists bash || _eshell=/bin/sh
    guest_as_user "$_eshell" -c "exec $_joined"
  fi
}

# Base64 payload → guest shell as USER_NAME (Kotlin / RootShell path).
# Absolute /usr/bin/base64 — never depend on guest PATH for decode bootstrap.
# TTY-safe: decode to temp script then run FILE (do NOT pipe into bash — that steals stdin
# and breaks TUI tools needing /dev/tty: bubbletea, grok, claude, opencode).
# Prefers /bin/bash when present; falls back to /bin/sh for Alpine minirootfs.
guest_b64() {
  _b64="$1"
  [ -n "$_b64" ] || die "b64 requires payload"
  # alphabet-only payload — safe inside single quotes
  # \$ preserved for guest; host expands only ${_b64}
  _inner="_b='${_b64}'; _f=/tmp/.nc_b64_\$\$; { echo \$_b | /usr/bin/base64 -d 2>/dev/null || echo \$_b | /bin/base64 -d; } >\$_f || exit 2; if [ -x /bin/bash ]; then /bin/bash --noprofile --norc \$_f; else /bin/sh \$_f; fi; _e=\$?; rm -f \$_f; exit \$_e"
  if [ "$USER_NAME" = "root" ]; then
    # Outer bootstrap: sh always exists; inner script picks bash if available
    guest_chroot_env /bin/sh -c "$_inner"
  else
    guest_as_user /bin/sh -c "$_inner"
  fi
}

parse_common_flags() {
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --user)
        [ "$#" -ge 2 ] || die "--user needs value"
        USER_NAME="$2"
        shift 2
        ;;
      --shell)
        [ "$#" -ge 2 ] || die "--shell needs value"
        LOGIN_SHELL="$2"
        shift 2
        ;;
      --x11)
        WANT_X11=1
        shift
        ;;
      --)
        shift
        REMAINING_ARGS="$@"
        return 0
        ;;
      -*)
        die "unknown flag: $1"
        ;;
      *)
        REMAINING_ARGS="$@"
        return 0
        ;;
    esac
  done
  REMAINING_ARGS=""
}

# --- main ---
[ "$#" -ge 1 ] || usage
MODE="$1"
shift

case "$MODE" in
  version|-V|--version)
    echo "$VERSION_STR"
    exit 0
    ;;
  mount|login|sh|exec|b64) ;;
  -h|--help|help) usage ;;
  *) die "unknown mode: $MODE" ;;
esac

require_root
resolve_bb || die "root-capable busybox not found"

# Flag parse: collect until -- or end; modes sh/exec/b64 need --
REMAINING_ARGS=""
WANT_X11=0
USER_NAME="flux"
LOGIN_SHELL="${FLUX_SHELL:-zsh}"

case "$MODE" in
  mount)
    while [ "$#" -gt 0 ]; do
      case "$1" in
        --x11) WANT_X11=1; shift ;;
        *) die "mount: unknown arg $1" ;;
      esac
    done
    ensure_mounts
    exit 0
    ;;
  login)
    LOGIN_WORKDIR=""
    while [ "$#" -gt 0 ]; do
      case "$1" in
        --user)
          [ "$#" -ge 2 ] || die "--user needs value"
          USER_NAME="$2"; shift 2
          ;;
        --shell)
          [ "$#" -ge 2 ] || die "--shell needs value"
          LOGIN_SHELL="$2"; shift 2
          ;;
        --workdir)
          [ "$#" -ge 2 ] || die "--workdir needs value"
          LOGIN_WORKDIR="$2"; shift 2
          ;;
        --x11) WANT_X11=1; shift ;;
        *) die "login: unknown arg $1" ;;
      esac
    done
    case "$USER_NAME" in root|flux) ;; *) die "user must be flux|root" ;; esac
    ensure_mounts
    guest_login
    ;;
  sh)
    while [ "$#" -gt 0 ]; do
      case "$1" in
        --user)
          [ "$#" -ge 2 ] || die "--user needs value"
          USER_NAME="$2"; shift 2
          ;;
        --x11) WANT_X11=1; shift ;;
        --) shift; break ;;
        *)
          # allow bare string without -- for convenience
          break
          ;;
      esac
    done
    case "$USER_NAME" in root|flux) ;; *) die "user must be flux|root" ;; esac
    [ "$#" -ge 1 ] || die "sh requires a shell string"
    # Join remaining as one command string (caller may pass one quoted arg)
    CMD_STR="$*"
    ensure_mounts
    guest_sh "$CMD_STR"
    ;;
  exec)
    while [ "$#" -gt 0 ]; do
      case "$1" in
        --user)
          [ "$#" -ge 2 ] || die "--user needs value"
          USER_NAME="$2"; shift 2
          ;;
        --x11) WANT_X11=1; shift ;;
        --) shift; break ;;
        *) break ;;
      esac
    done
    case "$USER_NAME" in root|flux) ;; *) die "user must be flux|root" ;; esac
    [ "$#" -ge 1 ] || die "exec requires CMD"
    ensure_mounts
    guest_exec "$@"
    ;;
  b64)
    while [ "$#" -gt 0 ]; do
      case "$1" in
        --user)
          [ "$#" -ge 2 ] || die "--user needs value"
          USER_NAME="$2"; shift 2
          ;;
        --x11) WANT_X11=1; shift ;;
        --) shift; break ;;
        *) break ;;
      esac
    done
    case "$USER_NAME" in root|flux) ;; *) die "user must be flux|root" ;; esac
    [ "$#" -ge 1 ] || die "b64 requires payload"
    B64_PAYLOAD="$1"
    ensure_mounts
    guest_b64 "$B64_PAYLOAD"
    ;;
esac
