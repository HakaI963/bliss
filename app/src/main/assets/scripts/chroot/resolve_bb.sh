#!/system/bin/sh
# Shared BusyBox resolver for Bliss chroot.
# Magisk / KernelSU / APatch built-in busybox is enough — NDK module not required.
# Safe to source under set -u. No set -e.

bb_has() {
  "$1" --list 2>/dev/null | tr ' \t' '\n' | grep -qx "$2"
}

bb_ok() {
  [ -n "${1:-}" ] && [ -x "$1" ] || return 1
  case "$1" in *com.termux*|*fluxlinux*|*nativecode*) return 1 ;; esac
  bb_has "$1" chroot && bb_has "$1" mount
}

resolve_bb() {
[ -d /data/local/folio ] && [ ! -e /data/local/bliss ] && mv /data/local/folio /data/local/bliss 2>/dev/null || true
  BB=""
  if [ -n "${FLUX_BB:-}" ] && bb_ok "$FLUX_BB"; then
    BB="$FLUX_BB"
  elif bb_ok /data/local/bliss/busybox/bliss_busybox; then
    BB=/data/local/bliss/busybox/bliss_busybox
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
  if [ "$BB" != /data/local/bliss/busybox/bliss_busybox ]; then
    mkdir -p /data/local/bliss/busybox 2>/dev/null || true
    cp -f "$BB" /data/local/bliss/busybox/bliss_busybox 2>/dev/null \
      && chmod 755 /data/local/bliss/busybox/bliss_busybox 2>/dev/null \
      && bb_ok /data/local/bliss/busybox/bliss_busybox \
      && BB=/data/local/bliss/busybox/bliss_busybox || true
  fi
  return 0
}
