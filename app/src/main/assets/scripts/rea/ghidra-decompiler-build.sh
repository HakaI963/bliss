#!/bin/sh
# Builds the Ghidra decompiler for linux_arm_64.
#
#   ghidra-decompiler-build.sh <ghidra-install-dir>
#
# NSA ships prebuilt decompiler binaries only for linux_x86_64 / mac / win; on
# ARM the native component must be compiled from the source bundled in the
# release zip. The zip also ships the pre-generated bison/flex output, so only
# g++, make and the zlib headers are needed. Exit status 0 means the decompiler
# is in place: the caller treats anything else as "analysis without
# decompilation", never as an install failure.
set +e

GH="$1"
if [ -z "$GH" ]; then
  echo "usage: ghidra-decompiler-build.sh <ghidra-install-dir>"
  exit 2
fi

ARCH=$(uname -m)
case "$ARCH" in
  aarch64|arm64|armv8l|arm64-v8a) ;;
  *) echo "arch $ARCH ships a prebuilt decompiler; nothing to build"; exit 0 ;;
esac

CPP="$GH/Ghidra/Features/Decompiler/src/decompile/cpp"
OUT="$GH/Ghidra/Features/Decompiler/os/linux_arm_64"
if [ -x "$OUT/decompile" ]; then
  echo "decompiler already built"
  exit 0
fi
if [ ! -d "$CPP" ]; then
  echo "decompiler source missing at $CPP"
  exit 3
fi

echo "installing build dependencies (g++, make, zlib headers)"
if command -v apt-get >/dev/null 2>&1; then
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq
  apt-get install -y --no-install-recommends g++ make zlib1g-dev \
    || apt-get install -y g++ make zlib1g-dev
elif command -v apk >/dev/null 2>&1; then
  apk add --no-cache g++ make zlib-dev
elif command -v dnf >/dev/null 2>&1; then
  dnf install -y gcc-c++ make zlib-devel
elif command -v pacman >/dev/null 2>&1; then
  pacman -Sy --noconfirm gcc make zlib
fi
if ! command -v g++ >/dev/null 2>&1; then
  echo "ERROR: g++ is unavailable, cannot build the decompiler"
  exit 4
fi

# Ghidra bundles the zlib headers it was built against; use them when the
# distro ships no zlib development package (only the runtime library).
ZINC=""
if ! echo "#include <zlib.h>" | g++ -E -x c - >/dev/null 2>&1; then
  ZLIB_SRC="$GH/Ghidra/Features/Decompiler/src/decompile/zlib"
  if [ -f "$ZLIB_SRC/zlib.h" ]; then
    echo "using the zlib headers bundled with Ghidra"
    ZINC="-I$ZLIB_SRC"
  else
    echo "ERROR: zlib.h is unavailable and Ghidra ships no copy"
    exit 5
  fi
fi

cd "$CPP" || exit 6
# The stock Makefile has no aarch64 branch (upstream TODO), so pin the arch
# settings unconditionally: this script only runs on ARM. Guard against a
# repeated run appending the block twice.
if ! grep -q "Altaris: force linux_arm_64" Makefile 2>/dev/null; then
  printf '\n# Altaris: force linux_arm_64\nARCH_TYPE=\nOSDIR=linux_arm_64\n' >> Makefile
fi

NPROC=$(nproc 2>/dev/null || echo 2)
case "$NPROC" in ''|*[!0-9]*) NPROC=2 ;; esac
[ "$NPROC" -gt 4 ] && NPROC=4

echo "building ghidra_opt with -j$NPROC (a few minutes on ARM)"
make ghidra_opt -j"$NPROC"
RC_OPT=$?

echo "building sleigh_opt with -j$NPROC"
# -l:libz.so.1 links the versioned runtime library when no dev symlink exists.
make sleigh_opt ADDITIONAL_FLAGS="$ZINC" LNK="-l:libz.so.1" -j"$NPROC"
RC_SLEIGH=$?
if [ "$RC_SLEIGH" -ne 0 ]; then
  make sleigh_opt ADDITIONAL_FLAGS="$ZINC" LNK="-lz" -j"$NPROC"
  RC_SLEIGH=$?
fi

if [ "$RC_OPT" -ne 0 ] || [ "$RC_SLEIGH" -ne 0 ]; then
  echo "ERROR: build failed (ghidra_opt=$RC_OPT sleigh_opt=$RC_SLEIGH)"
  exit 7
fi

mkdir -p "$OUT"
cp ghidra_opt "$OUT/decompile"
cp sleigh_opt "$OUT/sleigh"
chmod +x "$OUT/decompile" "$OUT/sleigh"
echo "decompiler installed at $OUT"
exit 0
