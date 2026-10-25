#!/bin/sh
# Builds and installs the Ghidra decompiler for linux_arm_64.
# NSA ships prebuilt decompiler binaries only for linux_x86_64 / mac / win;
# on ARM the native component must be compiled from the source bundled in the
# Ghidra release. The zip also ships pre-generated bison/flex output, so only
# g++, make and the zlib headers are required.
set -e
GH="$1"
[ -n "$GH" ] || { echo "usage: ghidra-decompiler-build.sh <ghidra-install-dir>"; exit 2; }

ARCH=$(uname -m)
case "$ARCH" in
  aarch64|arm64) ;;
  *) echo "arch $ARCH ships a prebuilt decompiler; nothing to build"; exit 0 ;;
esac

CPP="$GH/Ghidra/Features/Decompiler/src/decompile/cpp"
OUT="$GH/Ghidra/Features/Decompiler/os/linux_arm_64"
if [ -x "$OUT/decompile" ]; then echo "decompiler already built"; exit 0; fi
[ -d "$CPP" ] || { echo "decompiler source missing at $CPP"; exit 3; }

echo "installing build dependencies"
if command -v apt-get >/dev/null 2>&1; then
  apt-get update -qq
  apt-get install -y --no-install-recommends g++ make zlib1g-dev || true
elif command -v apk >/dev/null 2>&1; then
  apk add --no-cache g++ make zlib-dev || true
elif command -v dnf >/dev/null 2>&1; then
  dnf install -y gcc-c++ make zlib-devel || true
elif command -v pacman >/dev/null 2>&1; then
  pacman -Sy --noconfirm gcc make zlib || true
fi
command -v g++ >/dev/null 2>&1 || { echo "ERROR: g++ unavailable"; exit 4; }

# Ghidra bundles the zlib headers it was built against; use them when the
# distro ships no zlib development package (only the runtime library).
ZINC=""
if ! echo "#include <zlib.h>" | g++ -E -x c - >/dev/null 2>&1; then
  ZLIB_SRC="$GH/Ghidra/Features/Decompiler/src/decompile/zlib"
  if [ -f "$ZLIB_SRC/zlib.h" ]; then
    echo "using bundled zlib headers"
    ZINC="-I$ZLIB_SRC"
  else
    echo "ERROR: zlib.h unavailable and no bundled copy found"
    exit 5
  fi
fi

cd "$CPP"
# The stock Makefile has no aarch64 branch (upstream TODO), so pin the arch
# settings unconditionally: this script only runs on ARM.
printf '\n# Altaris: force linux_arm_64\nARCH_TYPE=\nOSDIR=linux_arm_64\n' >> Makefile

echo "building decompiler (a few minutes on ARM)"
NPROC=$(nproc 2>/dev/null || echo 2)
make ghidra_opt -j"$NPROC"
# -l:libz.so.1 links the versioned runtime library when no dev symlink exists.
make sleigh_opt ADDITIONAL_FLAGS="$ZINC" LNK="-l:libz.so.1" -j"$NPROC" || \
  make sleigh_opt ADDITIONAL_FLAGS="$ZINC" LNK="-lz" -j"$NPROC"

mkdir -p "$OUT"
cp ghidra_opt "$OUT/decompile"
cp sleigh_opt "$OUT/sleigh"
chmod +x "$OUT/decompile" "$OUT/sleigh"
echo "decompiler installed at $OUT"

