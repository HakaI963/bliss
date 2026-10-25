#!/bin/sh
# Installs Ghidra (headless analyzer) into the Altaris REA root.
set -e
BASE=__REA_ROOT__
GH=__GHIDRA_ROOT__
TAG=__GHIDRA_TAG__
ASSET=__GHIDRA_ASSET__
mkdir -p "$BASE"

# --- ensure a full JDK 21+ (javac is required, JRE is rejected) ---
JDK_OK=0
if command -v javac >/dev/null 2>&1 && command -v java >/dev/null 2>&1; then
  V=$(java -version 2>&1 | head -1 | sed -n "s/.*version \"\([0-9][0-9]*\).*/\1/p")
  case "$V" in ""|*[!0-9]*) V=0;; esac
  [ "$V" -ge 21 ] && JDK_OK=1
fi
if [ "$JDK_OK" -eq 0 ]; then
  if command -v apt-get >/dev/null 2>&1; then
    apt-get update -qq && apt-get install -y --no-install-recommends openjdk-21-jdk-headless || true
  elif command -v apk >/dev/null 2>&1; then
    apk add --no-cache openjdk21-jdk || true
  elif command -v dnf >/dev/null 2>&1; then
    dnf install -y java-21-openjdk-devel || true
  elif command -v pacman >/dev/null 2>&1; then
    pacman -Sy --noconfirm jdk21-openjdk || true
  fi
  if command -v javac >/dev/null 2>&1 && command -v java >/dev/null 2>&1; then
    V=$(java -version 2>&1 | head -1 | sed -n "s/.*version \"\([0-9][0-9]*\).*/\1/p")
    case "$V" in ""|*[!0-9]*) V=0;; esac
    [ "$V" -ge 21 ] && JDK_OK=1
  fi
fi
# Fallback: Temurin JDK 21 arm64 tarball (glibc). Alpine keeps its own package.
if [ "$JDK_OK" -eq 0 ] && [ ! -f /etc/alpine-release ]; then
  echo "no distro JDK 21+; installing Temurin 21"
  JDK_URL=__JDK_URL__
  command -v curl >/dev/null 2>&1 || { if command -v apt-get >/dev/null 2>&1; then apt-get update -qq && apt-get install -y --no-install-recommends curl ca-certificates; fi; }
  if command -v curl >/dev/null 2>&1; then
    curl -fL --retry 4 -o /tmp/altaris-jdk.tar.gz "$JDK_URL"
    rm -rf "$BASE/jdk" "$BASE/jdk.tmp"
    mkdir -p "$BASE/jdk.tmp"
    tar -xzf /tmp/altaris-jdk.tar.gz -C "$BASE/jdk.tmp" --strip-components=1
    mv "$BASE/jdk.tmp" "$BASE/jdk"
    JDK_OK=1
  fi
fi
if [ "$JDK_OK" -eq 0 ]; then echo "ERROR: could not provision a JDK 21+"; exit 5; fi
echo "JDK ready"

# --- install Ghidra ---
if [ -x "$GH/support/analyzeHeadless" ]; then echo "Ghidra already present"; exit 0; fi
command -v unzip >/dev/null 2>&1 || { if command -v apt-get >/dev/null 2>&1; then apt-get update -qq && apt-get install -y --no-install-recommends unzip; elif command -v apk >/dev/null 2>&1; then apk add --no-cache unzip; elif command -v dnf >/dev/null 2>&1; then dnf install -y unzip; elif command -v pacman >/dev/null 2>&1; then pacman -Sy --noconfirm unzip; fi; }
command -v curl >/dev/null 2>&1 || { if command -v apt-get >/dev/null 2>&1; then apt-get update -qq && apt-get install -y --no-install-recommends curl ca-certificates; elif command -v apk >/dev/null 2>&1; then apk add --no-cache curl ca-certificates; fi; }
URL="https://github.com/NationalSecurityAgency/ghidra/releases/download/$TAG/$ASSET"
echo "downloading Ghidra: $URL"
rm -f /tmp/ghidra-dl.zip
if command -v curl >/dev/null 2>&1; then curl -fL --retry 4 -o /tmp/ghidra-dl.zip "$URL"; else wget -qO /tmp/ghidra-dl.zip "$URL"; fi
echo "extracting Ghidra"
rm -rf "$BASE/ghidra.tmp"
mkdir -p "$BASE/ghidra.tmp"
unzip -q /tmp/ghidra-dl.zip -d "$BASE/ghidra.tmp"
DIR=$(find "$BASE/ghidra.tmp" -mindepth 1 -maxdepth 1 -type d -name "ghidra*" | head -1)
[ -z "$DIR" ] && DIR="$BASE/ghidra.tmp"
rm -rf "$GH"
mv "$DIR" "$GH"
find "$GH" -name "*.sh" -exec chmod +x {} + 2>/dev/null || true
chmod +x "$GH/support/analyzeHeadless" 2>/dev/null || true
echo "Ghidra installed at $GH"

# --- build the ARM64 decompiler (no prebuilt binary ships for linux_arm_64) ---
BUILD_SH="$BASE/scripts/ghidra-decompiler-build.sh"
if [ -f "$BUILD_SH" ]; then
  sh "$BUILD_SH" "$GH" || echo "warning: decompiler build failed; disassembly and function analysis still work, only decompilation is unavailable"
fi

