#!/bin/sh
# Installs Ghidra (headless analyzer) into the Altaris REA root.
#
#   ghidra-install.sh <rea-base> <ghidra-root> <tag> <asset> <jdk-url> [build-script]
#
# Every step reports what it is doing; the analyzer itself only needs Java plus
# the unpacked release, so a failed decompiler build degrades the feature set
# instead of failing the whole install.
set +e

BASE="$1"
GH="$2"
TAG="$3"
ASSET="$4"
JDK_URL="$5"
BUILD_SH="$6"

if [ -z "$BASE" ] || [ -z "$GH" ] || [ -z "$TAG" ] || [ -z "$ASSET" ] || [ -z "$JDK_URL" ]; then
  echo "usage: ghidra-install.sh <base> <ghidra-root> <tag> <asset> <jdk-url> [build-script]"
  exit 2
fi

say() { echo "$*"; }
fail() { echo "ERROR: $*"; exit 1; }

mkdir -p "$BASE" || fail "cannot create $BASE"

java_major() { # java_major <java-binary>
  "$1" -version 2>&1 | head -n 1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p'
}

jdk_ok() { # jdk_ok <prefix>  -> 0 when prefix/bin has a Java 21+ javac
  prefix="$1"
  [ -x "$prefix/bin/javac" ] || return 1
  [ -x "$prefix/bin/java" ] || return 1
  v=$(java_major "$prefix/bin/java")
  case "$v" in ''|*[!0-9]*) return 1 ;; esac
  [ "$v" -ge 21 ]
}

# ---------------------------------------------------------------- Java 21+
JDK=0
if [ -x "$BASE/jdk/bin/javac" ] && jdk_ok "$BASE/jdk"; then
  JDK=1
  say "JDK ready: $BASE/jdk"
fi

if [ "$JDK" -eq 0 ] && command -v javac >/dev/null 2>&1; then
  v=$(java_major "$(command -v java)")
  case "$v" in ''|*[!0-9]*) v=0 ;; esac
  if [ "$v" -ge 21 ]; then
    JDK=1
    say "JDK ready: $(command -v javac) (Java $v)"
  else
    say "distro JDK is Java $v; Ghidra needs 21+"
  fi
fi

if [ "$JDK" -eq 0 ]; then
  say "installing a JDK 21 through the package manager"
  if command -v apt-get >/dev/null 2>&1; then
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq
    apt-get install -y --no-install-recommends openjdk-21-jdk-headless \
      || apt-get install -y --no-install-recommends openjdk-17-jdk-headless
  elif command -v apk >/dev/null 2>&1; then
    apk add --no-cache openjdk21-jdk || apk add --no-cache openjdk17-jdk
  elif command -v dnf >/dev/null 2>&1; then
    dnf install -y java-21-openjdk-devel || dnf install -y java-17-openjdk-devel
  elif command -v pacman >/dev/null 2>&1; then
    pacman -Sy --noconfirm jdk21-openjdk || pacman -Sy --noconfirm jdk-openjdk
  fi
  if command -v javac >/dev/null 2>&1; then
    v=$(java_major "$(command -v java)")
    case "$v" in ''|*[!0-9]*) v=0 ;; esac
    [ "$v" -ge 21 ] && JDK=1
  fi
fi

# Temurin tarball fallback: Debian 12 and several phone distros stop at 17.
if [ "$JDK" -eq 0 ] && [ ! -f /etc/alpine-release ]; then
  say "no distro JDK 21+; installing Temurin 21"
  if ! command -v curl >/dev/null 2>&1; then
    if command -v apt-get >/dev/null 2>&1; then
      apt-get update -qq
      apt-get install -y --no-install-recommends curl ca-certificates
    elif command -v dnf >/dev/null 2>&1; then
      dnf install -y curl ca-certificates
    fi
  fi
  rm -rf "$BASE/jdk" "$BASE/jdk.tmp" "$BASE/jdk.tgz"
  if command -v curl >/dev/null 2>&1; then
    curl -fL --retry 3 -o "$BASE/jdk.tgz" "$JDK_URL"
  else
    wget -O "$BASE/jdk.tgz" "$JDK_URL"
  fi
  if [ -s "$BASE/jdk.tgz" ]; then
    mkdir -p "$BASE/jdk.tmp"
    if tar -xzf "$BASE/jdk.tgz" -C "$BASE/jdk.tmp" --strip-components=1; then
      mv "$BASE/jdk.tmp" "$BASE/jdk"
      jdk_ok "$BASE/jdk" && JDK=1
    fi
    rm -f "$BASE/jdk.tgz"
  fi
fi

if [ "$JDK" -eq 0 ]; then
  fail "could not provision a JDK 21+ (Ghidra refuses to start without javac)"
fi

# ------------------------------------------------------------------ Ghidra
if [ -x "$GH/support/analyzeHeadless" ]; then
  say "Ghidra already present at $GH"
else
  for tool in unzip curl; do
    command -v "$tool" >/dev/null 2>&1 && continue
    if command -v apt-get >/dev/null 2>&1; then
      apt-get update -qq
      apt-get install -y --no-install-recommends "$tool" ca-certificates
    elif command -v apk >/dev/null 2>&1; then
      apk add --no-cache "$tool" ca-certificates
    elif command -v dnf >/dev/null 2>&1; then
      dnf install -y "$tool"
    elif command -v pacman >/dev/null 2>&1; then
      pacman -Sy --noconfirm "$tool"
    fi
  done
  command -v unzip >/dev/null 2>&1 || fail "unzip is unavailable in the distro"
  command -v curl >/dev/null 2>&1 || command -v wget >/dev/null 2>&1 \
    || fail "neither curl nor wget is available in the distro"

  URL="https://github.com/NationalSecurityAgency/ghidra/releases/download/$TAG/$ASSET"
  say "downloading Ghidra: $URL"
  rm -f "$BASE/ghidra-dl.zip"
  if command -v curl >/dev/null 2>&1; then
    curl -fL --retry 3 -o "$BASE/ghidra-dl.zip" "$URL"
  else
    wget -O "$BASE/ghidra-dl.zip" "$URL"
  fi
  SIZE=$(wc -c < "$BASE/ghidra-dl.zip" 2>/dev/null | tr -d ' ')
  case "$SIZE" in ''|*[!0-9]*) SIZE=0 ;; esac
  # A truncated or HTML-error download must not be unpacked.
  if [ "$SIZE" -lt 100000000 ]; then
    rm -f "$BASE/ghidra-dl.zip"
    fail "Ghidra download is too small ($SIZE bytes); check the network"
  fi
  say "extracting Ghidra ($SIZE bytes)"
  rm -rf "$BASE/ghidra.tmp"
  mkdir -p "$BASE/ghidra.tmp" || fail "cannot create $BASE/ghidra.tmp"
  unzip -q "$BASE/ghidra-dl.zip" -d "$BASE/ghidra.tmp" || fail "Ghidra zip could not be extracted"
  rm -f "$BASE/ghidra-dl.zip"
  DIR=$(find "$BASE/ghidra.tmp" -mindepth 1 -maxdepth 1 -type d -name "ghidra*" | head -1)
  [ -n "$DIR" ] || DIR="$BASE/ghidra.tmp"
  rm -rf "$GH"
  mv "$DIR" "$GH" || fail "cannot move Ghidra into $GH"
  rm -rf "$BASE/ghidra.tmp"
  find "$GH" -name "*.sh" -exec chmod +x {} + 2>/dev/null
  chmod +x "$GH/support/analyzeHeadless" 2>/dev/null
  [ -x "$GH/support/analyzeHeadless" ] || fail "Ghidra unpacked without support/analyzeHeadless"
  say "Ghidra installed at $GH"
fi

# --------------------------------------------- ARM64 native decompiler
# NSA ships prebuilt decompilers for x86_64/mac/win only; the ARM64 build must
# be compiled from the bundled source. Optional: without it, function lists and
# strings still work.
if [ -n "$BUILD_SH" ] && [ -f "$BUILD_SH" ]; then
  if [ -x "$GH/Ghidra/Features/Decompiler/os/linux_arm_64/decompile" ]; then
    say "ARM64 decompiler already built"
  else
    say "building the ARM64 decompiler (this takes a few minutes)"
    sh "$BUILD_SH" "$GH" 2>&1 | tee "$BASE/ghidra-build.log" | tail -n 25
    if [ -x "$GH/Ghidra/Features/Decompiler/os/linux_arm_64/decompile" ]; then
      say "ARM64 decompiler ready"
    else
      say "warning: decompiler build failed (log: $BASE/ghidra-build.log)."
      say "warning: function lists, strings and call graphs still work; decompilation does not."
    fi
  fi
else
  say "warning: decompiler build script not staged; only disassembly/analysis will work"
fi

touch "$BASE/.ghidra-installed"
say "Ghidra install finished"
