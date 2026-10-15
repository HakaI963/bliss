#!/usr/bin/env bash
# repack_bootstrap_for_appid.sh — derive a new app-id bootstrap from an existing one.
# The .debs in native/output/<src-id>/ are prefix-pinned, so assembling directly
# for a new id fails (assert_no_wrong_prefix). Instead: copy the assembled
# root tree, rename data/data/<src> -> data/data/<dst>, rewrite the prefix in
# text files, patchelf RUNPATH in jniLibs, and repack bootstrap.tar reproducibly.
#
# Usage:
#   ./scripts/repack_bootstrap_for_appid.sh com.ivarna.fluxlinux com.nopiece.folio
set -euo pipefail

SRC_ID="${1:?usage: $0 <src-app-id> <dst-app-id>}"
DST_ID="${2:?usage: $0 <src-app-id> <dst-app-id>}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/native/bootstrap/$SRC_ID"
DST="$ROOT/native/bootstrap/$DST_ID"

if [ ! -d "$SRC/root/data/data/$SRC_ID/files" ]; then
  echo "ERROR: assemble $SRC_ID first (missing $SRC/root/data/data/$SRC_ID/files)" >&2
  echo "  python3 scripts/assemble_bootstrap.py --package-name $SRC_ID" >&2
  exit 1
fi

rm -rf "$DST/root"
mkdir -p "$DST"
cp -a "$SRC/root" "$DST/root"
mv "$DST/root/data/data/$SRC_ID" "$DST/root/data/data/$DST_ID"

# Rewrite prefix in text files only (skip binaries).
grep -rlI "$SRC_ID" "$DST/root" | while read -r f; do
  sed -i "s|$SRC_ID|$DST_ID|g" "$f"
done

# jniLibs: copy + rewrite RUNPATH to new prefix.
mkdir -p "$DST/jniLibs/arm64-v8a"
cp -f "$SRC/jniLibs/arm64-v8a/"*.so "$DST/jniLibs/arm64-v8a/"
chmod 755 "$DST/jniLibs/arm64-v8a/"*.so || true
# Root-tree ELFs (usr/bin, usr/lib) also carry RUNPATH with the old prefix.
if command -v patchelf >/dev/null 2>&1 && command -v readelf >/dev/null 2>&1; then
  for f in $(find "$DST/root/data/data/$DST_ID/files/usr" -type f 2>/dev/null); do
    if readelf -d "$f" 2>/dev/null | grep -q "$SRC_ID"; then
      patchelf --set-rpath "/data/data/$DST_ID/files/usr/lib" "$f" 2>/dev/null || true
    fi
  done
  for so in "$DST/jniLibs/arm64-v8a/"*.so; do
    if readelf -d "$so" 2>/dev/null | grep -q "$SRC_ID"; then
      patchelf --set-rpath "/data/data/$DST_ID/files/usr/lib" "$so"
    fi
  done
elif command -v patchelf >/dev/null 2>&1; then
  for so in "$DST/jniLibs/arm64-v8a/"*.so; do
    patchelf --set-rpath "/data/data/$DST_ID/files/usr/lib" "$so" 2>/dev/null || true
  done
else
  echo "WARNING: patchelf not found — RUNPATH still points at $SRC_ID" >&2
fi

# Repack bootstrap.tar reproducibly (same flags as assemble_bootstrap.py).
FILES_DIR="$DST/root/data/data/$DST_ID/files"
TAR="$DST/bootstrap.tar"
rm -f "$TAR"
tar --sort=name --mtime=@0 --owner=0 --group=0 --numeric-owner -cf "$TAR" usr -C "$FILES_DIR" 2>/dev/null || (
  cd "$FILES_DIR" && tar --sort=name --mtime=@0 --owner=0 --group=0 --numeric-owner -cf "$TAR" usr
)
echo "[*] repacked $DST_ID bootstrap: $(du -h "$TAR" | awk '{print $1}')"
echo "[*] jniLibs: $(ls "$DST/jniLibs/arm64-v8a" | tr '\n' ' ')"
