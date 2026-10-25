#!/bin/sh
# Installs Ghidra (headless analyzer) into the Altaris REA root.
set -e
BASE=__REA_ROOT__
GH=__GHIDRA_ROOT__
TAG=__GHIDRA_TAG__
ASSET=__GHIDRA_ASSET__
mkdir -p "$BASE"
if [ -x "$GH/support/analyzeHeadless" ]; then echo "Ghidra already present"; exit 0; fi
command -v unzip >/dev/null 2>&1 || { if command -v apt-get >/dev/null 2>&1; then apt-get update -qq && apt-get install -y --no-install-recommends unzip; elif command -v apk >/dev/null 2>&1; then apk add --no-cache unzip; elif command -v dnf >/dev/null 2>&1; then dnf install -y unzip; elif command -v pacman >/dev/null 2>&1; then pacman -Sy --noconfirm unzip; fi; }
command -v curl >/dev/null 2>&1 || { if command -v apt-get >/dev/null 2>&1; then apt-get update -qq && apt-get install -y --no-install-recommends curl ca-certificates; elif command -v apk >/dev/null 2>&1; then apk add --no-cache curl ca-certificates; fi; }
cd /tmp
CAND=$(curl -fsSL "https://api.github.com/repos/NationalSecurityAgency/ghidra/releases/tags/$TAG" 2>/dev/null | grep -o 'ghidra_[^"]*linux_arm64[^"]*\.zip' | head -1)
[ -n "$CAND" ] && ASSET="$CAND"
URL="https://github.com/NationalSecurityAgency/ghidra/releases/download/$TAG/$ASSET"
echo "downloading Ghidra: $URL"
rm -f ghidra-dl.zip
if command -v curl >/dev/null 2>&1; then curl -fL --retry 4 -o ghidra-dl.zip "$URL"; else wget -qO ghidra-dl.zip "$URL"; fi
echo "extracting Ghidra"
rm -rf "$BASE/ghidra.tmp"
mkdir -p "$BASE/ghidra.tmp"
unzip -q ghidra-dl.zip -d "$BASE/ghidra.tmp"
DIR=$(find "$BASE/ghidra.tmp" -maxdepth 1 -type d -name "ghidra*" | head -1)
[ -z "$DIR" ] && DIR="$BASE/ghidra.tmp"
rm -rf "$GH"
mv "$DIR" "$GH"
find "$GH" -name "*.sh" -exec chmod +x {} + 2>/dev/null || true
chmod +x "$GH/support/analyzeHeadless" 2>/dev/null || true
echo "Ghidra installed at $GH"

