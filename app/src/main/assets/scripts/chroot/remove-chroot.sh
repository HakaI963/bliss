#!/bin/sh
# Removes a chroot rootfs safely.
#
#   remove-chroot.sh <chroot-path>
#
# Why this exists: the app used to run `rm -rf <chroot>` directly. A configured
# chroot has /dev, /sys, /proc, /dev/pts, /dev/shm, /tmp, /mnt/host-tmp and
# /sdcard bind-mounted inside it. `rm -rf` walks into those mounts and deletes
# the *host* filesystem through them, which is what froze the device and forced
# two reboots while the rootfs was never actually removed.
#
# Neither busybox `rm` nor busybox `find -xdev` protects against this: busybox
# rm has no -x at all, and -xdev does not prune a bind mount because it is the
# same filesystem. The only safe rule is therefore:
#
#   1. kill every process rooted in the chroot, and VERIFY they are gone;
#   2. unmount everything, deepest first, retrying lazy where needed;
#   3. VERIFY nothing is still mounted under the path;
#   4. only then delete, and refuse outright if step 3 found anything.
#
# Refusing is the correct outcome. A failed removal the user can retry is
# infinitely better than a device that will not boot.
#
# v2: the previous version found processes by matching the chroot path in
# `ps` output. A chrooted process has a cmdline like `/usr/bin/node …` with no
# reference to the chroot at all, so nothing was ever killed, the mounts stayed
# busy, and removal refused every single time ("container could not be
# removed"). The reliable identity is /proc/PID/root, which the kernel points at
# the chroot directory.
set +e

GUEST="$1"
if [ -z "$GUEST" ]; then
  echo "usage: remove-chroot.sh <chroot-path>"
  exit 2
fi

# Trailing slashes break both the prefix match and the unmount paths.
while [ "${GUEST%/}" != "$GUEST" ]; do GUEST="${GUEST%/}"; done

say() { echo "$*"; }
fail() { echo "ERROR: $*"; exit 1; }

[ -d "$GUEST" ] || { say "already removed: $GUEST"; exit 0; }

# Refuse the paths whose deletion would be catastrophic. The caller passes a
# distro rootfs; anything shallower is a bug.
case "$GUEST" in
  ""|"/"|"/data"|"/data/local"|"/data/local/altaris"|"/data/local/altaris/chroots"|\
  "/system"|"/sdcard"|"/storage"|"/mnt"|"/proc"|"/sys"|"/dev")
    fail "refusing to remove a protected path: $GUEST"
    ;;
esac

BB=""
for candidate in \
  "${ALTARIS_BB:-}" \
  /data/adb/ksu/bin/busybox \
  /data/adb/ap/bin/busybox \
  /data/adb/magisk/busybox \
  /data/local/altaris/busybox/altaris_busybox \
  /debug_ramdisk/busybox \
  /system/xbin/busybox \
  /system/bin/busybox
do
  if [ -n "$candidate" ] && [ -x "$candidate" ]; then BB="$candidate"; break; fi
done
if [ -z "$BB" ]; then
  for candidate in busybox toybox; do
    if command -v "$candidate" >/dev/null 2>&1; then BB=$(command -v "$candidate"); break; fi
  done
fi
say "using ${BB:-system tools}"

umount_path() { # umount_path <path>
  _p="$1"
  [ -e "$_p" ] || return 0
  if [ -n "$BB" ]; then
    "$BB" umount "$_p" 2>/dev/null && return 0
    "$BB" umount -l "$_p" 2>/dev/null && return 0
  fi
  umount "$_p" 2>/dev/null && return 0
  umount -l "$_p" 2>/dev/null && return 0
  return 0
}

# Mount points still attached under $GUEST, deepest first.
#
# The prefix test is done in awk so that "/…/rea" cannot match "/…/react":
# the mount path must be exactly $GUEST or start with "$GUEST/".
still_mounted() {
  awk -v base="$GUEST" '
    $2 == base || index($2, base "/") == 1 { print $2 }
  ' /proc/mounts 2>/dev/null | sort -r
}

# Every PID whose root directory is $GUEST (or inside it).
#
# readlink on /proc/PID/root is the only reliable signal: a chrooted process
# has an ordinary-looking cmdline, and matching that string found nothing.
rooted_pids() {
  for _d in /proc/[0-9]*; do
    [ -d "$_d" ] || continue
    _pid="${_d#/proc/}"
    [ "$_pid" = "1" ] && continue
    [ "$_pid" = "$$" ] && continue
    _root=$(readlink "$_d/root" 2>/dev/null) || continue
    case "$_root" in
      "$GUEST"|"$GUEST"/*) printf '%s ' "$_pid" ;;
    esac
  done
}

# 1. Kill anything rooted in the chroot, twice, and verify it is gone. A live
#    process inside the chroot is what makes an unmount fail with EBUSY.
say "stopping processes rooted in $GUEST"
_pass=1
while [ "$_pass" -le 3 ]; do
  _pids=$(rooted_pids)
  [ -z "$_pids" ] && break
  say "  killing: $_pids"
  for _pid in $_pids; do
    kill -9 "$_pid" 2>/dev/null
  done
  _pass=$((_pass + 1))
  [ "$_pass" -le 3 ] && sleep 0.3 2>/dev/null || sleep 1
done

_left_pids=$(rooted_pids)
if [ -n "$_left_pids" ]; then
  # A process that survives SIGKILL is unkillable (D state). Unmounting would
  # fail, so say which one it is instead of deleting blindly.
  say "REFUSING to delete: processes still rooted in the chroot:"
  for _pid in $_left_pids; do
    _comm=$(tr -d '\n\r' < "/proc/$_pid/comm" 2>/dev/null)
    say "  pid $_pid (${_comm:-?})"
  done
  fail "could not stop the processes inside the environment; nothing was deleted"
fi

# 2. Unmount, deepest first. Parents cannot be released while a child is busy.
say "unmounting $GUEST"
for rel in \
  sdcard \
  mnt/host-tmp \
  dev/shm \
  dev/pts \
  tmp/.X11-unix \
  tmp \
  run \
  var/tmp \
  proc \
  sys \
  dev
do
  umount_path "$GUEST/$rel"
done
# Anything else the setup script may have added, deepest first.
for m in $(still_mounted); do
  umount_path "$m"
done

# A second sweep, because a lazy unmount of a parent can detach a child that was
# reported as busy on the first pass.
for _attempt in 1 2 3; do
  _left=$(still_mounted)
  [ -z "$_left" ] && break
  for m in $_left; do
    umount_path "$m"
  done
  sleep 0.4 2>/dev/null || sleep 1
done

# 3. Verify. This is the guard that matters: if anything is still mounted,
#    deleting would destroy the host through it.
left=$(still_mounted)
if [ -n "$left" ]; then
  say "REFUSING to delete: still mounted:"
  for m in $left; do say "  $m"; done
  fail "unmount failed; nothing was deleted"
fi

# 4. Delete. Nothing is mounted now, so a recursive delete stays inside $GUEST.
say "deleting $GUEST"
if [ -n "$BB" ]; then
  "$BB" rm -rf "$GUEST" 2>/dev/null
else
  rm -rf "$GUEST" 2>/dev/null
fi

if [ -d "$GUEST" ] && [ -n "$(ls -A "$GUEST" 2>/dev/null | head -n 1)" ]; then
  fail "could not fully remove $GUEST (still: $(ls -A "$GUEST" 2>/dev/null | head -n 3 | tr '\n' ' '))"
fi
rmdir "$GUEST" 2>/dev/null

say "removed $GUEST"
exit 0
