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
#   1. unmount everything, deepest first;
#   2. VERIFY nothing is still mounted under the path;
#   3. only then delete, and refuse outright if step 2 found anything.
#
# Refusing is the correct outcome. A failed removal the user can retry is
# infinitely better than a device that will not boot.
set +e

GUEST="$1"
if [ -z "$GUEST" ]; then
  echo "usage: remove-chroot.sh <chroot-path>"
  exit 2
fi

say() { echo "$*"; }
fail() { echo "ERROR: $*"; exit 1; }

[ -d "$GUEST" ] || { say "already removed: $GUEST"; exit 0; }

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
still_mounted() {
  grep -F " $GUEST/" /proc/mounts 2>/dev/null \
    | awk '{print $2}' | sort -r
}

# 1. Stop anything running inside, so it cannot hold a mount open.
for pattern in "$GUEST"; do
  pids=$(ps -A -o PID,ARGS 2>/dev/null | grep -F "$pattern" | grep -v grep | awk '{print $1}')
  for pid in $pids; do
    [ "$pid" = "$$" ] && continue
    kill -9 "$pid" 2>/dev/null || true
  done
done

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
