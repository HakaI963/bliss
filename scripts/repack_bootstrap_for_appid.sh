#!/usr/bin/env bash
# repack_bootstrap_for_appid.sh — derive a new app-id bootstrap from an existing one.
# The .debs in native/output/<src-id>/ are prefix-pinned, so assembling directly
# for a new id fails (assert_no_wrong_prefix). Instead: copy the assembled
# root tree, rename data/data/<src> -> data/data/<dst>, rewrite the prefix in
# text files, patch every baked `src-id` byte string (ELF constants and RUNPATH
# entries are NUL-terminated — the enclosing string is rewritten with NUL
# padding so the file never grows), and repack bootstrap.tar reproducibly.
# No patchelf: the byte patcher is deterministic and version-independent
# (patchelf left stale strings behind on the CI runner).
# Verifies jniLibs/root carry zero old-id references before exit.
#
# Usage:
#   ./scripts/repack_bootstrap_for_appid.sh com.ivarna.fluxlinux com.nopiece.bliss
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

# jniLibs: copy only — RUNPATH (a plain NUL-terminated string in .dynstr) is
# rewritten by the byte patcher below, exactly like every other baked path.
# Do NOT use patchelf here: its behavior/layout differs across versions and it
# can leave stale strings behind (CI failure, round 5) — the byte patcher is
# deterministic and was verified to keep ELFs valid (readelf: RUNPATH updated).
mkdir -p "$DST/jniLibs/arm64-v8a"
cp -f "$SRC/jniLibs/arm64-v8a/"*.so "$DST/jniLibs/arm64-v8a/"
chmod 755 "$DST/jniLibs/arm64-v8a/"*.so || true

# ── Binary-safe prefix patch (A1) ───────────────────────────────────────────
# Text files were rewritten above with sed; ELF / binary string constants in
# root (usr/bin, usr/lib, …) and jniLibs still bake `data/data/<src-id>` (e.g.
# bash SYS_PROFILE / SYS_BASHRC, curl DEFAULT_HOME, proot RUNPATH). Each
# NUL-terminated byte string containing the old prefix is rewritten with the
# new id and NUL-padded by the length delta, so the file never grows and every
# C string stays readable. Skips files >64 MiB, symlinks and non-regular files.
# Idempotent (a second run finds nothing to change).
set +e
python3 - "$SRC_ID" "$DST_ID" "$DST" <<'PYEOF'
import os
import stat
import sys

src_id, dst_id, dst = sys.argv[1], sys.argv[2], sys.argv[3]
# Patch the BARE id. Every baked anchor contains it (paths, RUNPATHs, app-id
# strings); patching only the wider `data/data/<id>` form left stale strings on
# the CI toolchain (round-5 failure), while the bare form is still exact enough
# — the rewrite keeps every byte after the occurrence inside its string.
src_pat = src_id.encode()
dst_pat = dst_id.encode()
MAX_BYTES = 64 * 1024 * 1024

if len(dst_pat) > len(src_pat):
    print(
        "WARNING: cannot patch binary prefixes: %s is longer than %s"
        % (dst_pat.decode(), src_pat.decode()),
        file=sys.stderr,
    )
    sys.exit(2)


def patch_buffer(data):
    """Rewrite every NUL-terminated string containing src_pat, NUL-padding the
    tail by the length delta. Returns (new_data, occurrences, changed)."""
    occurrences = data.count(src_pat)
    if occurrences == 0:
        return data, 0, False
    # Collect the enclosing NUL-terminated string spans once (read-only), then
    # rewrite right-to-left so earlier spans keep their offsets.
    spans = []
    i = data.find(src_pat)
    while i != -1:
        start = data.rfind(b"\x00", 0, i) + 1
        end = data.find(b"\x00", i)
        if end == -1:
            end = len(data)
        spans.append((start, end))
        i = data.find(src_pat, end)
    buf = bytearray(data)
    changed = False
    for start, end in reversed(spans):
        original = bytes(buf[start:end])
        rewritten = original.replace(src_pat, dst_pat)
        pad = len(original) - len(rewritten)
        if pad <= 0:
            continue
        buf[start:start + len(rewritten)] = rewritten
        buf[start + len(rewritten):end] = b"\x00" * pad
        changed = True
    return bytes(buf), occurrences, changed


def collect_files():
    root_dir = os.path.join(dst, "root")
    jni_dir = os.path.join(dst, "jniLibs", "arm64-v8a")
    for base, dirs, files in os.walk(root_dir):
        dirs[:] = [d for d in dirs if not os.path.islink(os.path.join(base, d))]
        for name in files:
            yield os.path.join(base, name)
    if os.path.isdir(jni_dir):
        for name in sorted(os.listdir(jni_dir)):
            if name.endswith(".so"):
                yield os.path.join(jni_dir, name)


patched_root = 0
patched_jni = 0
occurrences = 0
skipped_large = 0
scanned = 0
seen = set()
for path in collect_files():
    real = os.path.realpath(path)
    if real in seen:
        continue
    seen.add(real)
    try:
        st = os.lstat(path)
    except OSError:
        continue
    if not stat.S_ISREG(st.st_mode):
        continue
    if st.st_size == 0:
        continue
    if st.st_size > MAX_BYTES:
        skipped_large += 1
        continue
    scanned += 1
    try:
        with open(path, "rb") as fh:
            data = fh.read()
    except OSError:
        continue
    new_data, hits, changed = patch_buffer(data)
    if not changed or new_data == data:
        continue
    try:
        with open(path, "r+b") as fh:
            fh.write(new_data)
    except OSError as exc:
        print("WARNING: cannot patch %s: %s" % (path, exc), file=sys.stderr)
        continue
    occurrences += hits
    if os.path.basename(os.path.dirname(path)) == "arm64-v8a":
        patched_jni += 1
    else:
        patched_root += 1

print(
    "[*] binary prefix patch: %d file(s) patched (%d root, %d jniLibs), "
    "%d occurrence(s); scanned %d, skipped %d >64MiB"
    % (patched_root + patched_jni, patched_root, patched_jni, occurrences,
       scanned, skipped_large)
)
PYEOF
_patch_rc=$?
set -e
if [ "$_patch_rc" -ne 0 ]; then
  echo "ERROR: binary prefix patch failed (rc=$_patch_rc)" >&2
  exit 1
fi

# ── Verification (A1) ───────────────────────────────────────────────────────
# No old-id reference may survive in jniLibs (binary scan). The root tree is
# checked too — the sed rewrite + binary patch must have removed every one.
_verify_fail=0
for _dir in "$DST/jniLibs" "$DST/root"; do
  _left=$(grep -ra "$SRC_ID" "$_dir" 2>/dev/null | wc -l || true)
  if [ "$_left" -ne 0 ]; then
    echo "ERROR: $_left line(s) under $_dir still contain $SRC_ID" >&2
    grep -ral "$SRC_ID" "$_dir" 2>/dev/null | head -10 >&2
    _verify_fail=1
  fi
done
if [ "$_verify_fail" -ne 0 ]; then
  exit 1
fi
echo "[*] verify: jniLibs + root carry zero $SRC_ID references"

# Repack bootstrap.tar reproducibly (same flags as assemble_bootstrap.py).
FILES_DIR="$DST/root/data/data/$DST_ID/files"
TAR="$DST/bootstrap.tar"
rm -f "$TAR"
tar --sort=name --mtime=@0 --owner=0 --group=0 --numeric-owner -cf "$TAR" usr -C "$FILES_DIR" 2>/dev/null || (
  cd "$FILES_DIR" && tar --sort=name --mtime=@0 --owner=0 --group=0 --numeric-owner -cf "$TAR" usr
)
echo "[*] repacked $DST_ID bootstrap: $(du -h "$TAR" | awk '{print $1}')"
echo "[*] jniLibs: $(ls "$DST/jniLibs/arm64-v8a" | tr '\n' ' ')"
