#!/bin/sh
# Install a tool from its official release archive.
#
#   install-archive.sh <url> <file-name> <install-dir> <probe,probe,...> [launcher]
#
# Used for tools that no package manager on the distro ships — JADX and Apktool
# on Fedora and Alpine, for example. Fedora has neither package, so "try another
# dnf name" could never work and the tool has to come from upstream.
#
# The launcher is linked into <install-dir>/../bin, which is prepended to PATH by
# the app, so `jadx`/`apktool` resolve exactly like a packaged install would.
# Every failure prints an ERROR line the app shows; a truncated download is never
# unpacked.
set +e

URL="$1"
FILE="$2"
DIR="$3"
PROBES="$4"
LAUNCHER="$5"

if [ -z "$URL" ] || [ -z "$FILE" ] || [ -z "$DIR" ]; then
  echo "usage: install-archive.sh <url> <file> <dir> <probes> [launcher]"
  exit 2
fi

fail() { echo "ERROR: $*"; exit 1; }
say() { echo "$*"; }

# Splitting the comma list matters: the probe list is "aapt,aapt2" style and a
# plain word split makes the whole string one token that can never be found.
probe_list() {
  [ -n "$PROBES" ] || return 0
  printf '%s\n' "$PROBES" | tr ',' '\n' | while IFS= read -r p; do
    [ -n "$p" ] && printf '%s\n' "$p"
  done
}

have() {
  probe_list | while IFS= read -r p; do
    if command -v "$p" >/dev/null 2>&1; then
      echo yes
      break
    fi
  done | grep -q yes
}

if have; then
  say "already installed: $PROBES"
  exit 0
fi

# --- downloader -------------------------------------------------------------
if command -v curl >/dev/null 2>&1; then
  dl() { curl -fL --retry 3 --retry-delay 2 -o "$2" "$1"; }
elif command -v wget >/dev/null 2>&1; then
  dl() { wget -O "$2" "$1"; }
else
  # Try to get one; some minimal rootfs images ship neither.
  if command -v apt-get >/dev/null 2>&1; then
    apt-get update -qq >/dev/null 2>&1
    apt-get install -y --no-install-recommends curl ca-certificates >/dev/null 2>&1
  elif command -v dnf >/dev/null 2>&1; then
    dnf install -y curl ca-certificates >/dev/null 2>&1
  elif command -v apk >/dev/null 2>&1; then
    apk add --no-cache curl ca-certificates >/dev/null 2>&1
  elif command -v pacman >/dev/null 2>&1; then
    pacman -Sy --noconfirm curl ca-certificates >/dev/null 2>&1
  fi
  command -v curl >/dev/null 2>&1 || fail "no curl or wget available in the distro"
  dl() { curl -fL --retry 3 --retry-delay 2 -o "$2" "$1"; }
fi

# --- unpack -----------------------------------------------------------------
mkdir -p "$DIR" || fail "cannot create $DIR"
TMP="$DIR/.download.$$"
rm -f "$TMP"

say "downloading $URL"
dl "$URL" "$TMP" || { rm -f "$TMP"; fail "download failed: $URL"; }

SIZE=$(wc -c < "$TMP" 2>/dev/null | tr -d ' ')
case "$SIZE" in ''|*[!0-9]*) SIZE=0 ;; esac
# An HTML error page is a few KB; a real release is megabytes. Refuse both a
# failed transfer and a captive-portal page.
if [ "$SIZE" -lt 100000 ]; then
  head -c 200 "$TMP" 2>/dev/null
  rm -f "$TMP"
  fail "download is only ${SIZE} bytes — not a release archive"
fi

case "$FILE" in
  *.zip)
    command -v unzip >/dev/null 2>&1 || {
      if command -v apt-get >/dev/null 2>&1; then
        apt-get install -y --no-install-recommends unzip >/dev/null 2>&1
      elif command -v dnf >/dev/null 2>&1; then
        dnf install -y unzip >/dev/null 2>&1
      elif command -v apk >/dev/null 2>&1; then
        apk add --no-cache unzip >/dev/null 2>&1
      elif command -v pacman >/dev/null 2>&1; then
        pacman -S --noconfirm --needed unzip >/dev/null 2>&1
      fi
    }
    command -v unzip >/dev/null 2>&1 || fail "unzip is required to extract $FILE"
    unzip -oq "$TMP" -d "$DIR" || { rm -f "$TMP"; fail "cannot extract $FILE"; }
    # Releases usually wrap everything in one top-level directory
    # (jadx-1.5.6/bin/jadx). Flatten it so the launcher path is stable.
    set -- "$DIR"/*
    if [ "$#" -eq 1 ] && [ -d "$1" ]; then
      TOP="$1"
      for item in "$TOP"/* "$TOP"/.[!.]*; do
        [ -e "$item" ] || continue
        mv -f "$item" "$DIR/" 2>/dev/null
      done
      rmdir "$TOP" 2>/dev/null
    fi
    ;;
  *.tar.gz|*.tgz)
    tar -xzf "$TMP" -C "$DIR" || { rm -f "$TMP"; fail "cannot extract $FILE"; }
    ;;
  *.jar)
    # A jar is the program; keep it under its own name.
    mv -f "$TMP" "$DIR/$FILE" || fail "cannot place $FILE"
    ;;
  *)
    fail "unsupported archive type: $FILE"
    ;;
esac

[ -f "$TMP" ] && rm -f "$TMP"

# --- launcher ---------------------------------------------------------------
BIN="$DIR/../bin"
mkdir -p "$BIN" || fail "cannot create $BIN"

if [ -n "$LAUNCHER" ]; then
  TARGET="$DIR/$LAUNCHER"
  [ -x "$TARGET" ] || chmod +x "$TARGET" 2>/dev/null
  [ -f "$TARGET" ] || fail "launcher $LAUNCHER is missing from $FILE"
  chmod +x "$TARGET" 2>/dev/null
  for p in $(probe_list); do
    ln -sf "$TARGET" "$BIN/$p" 2>/dev/null || cp -f "$TARGET" "$BIN/$p" 2>/dev/null
    chmod +x "$BIN/$p" 2>/dev/null
  done
else
  # No launcher in the archive (a bare jar): the caller generated a wrapper
  # named after the first probe, which is what PATH lookups will find.
  FIRST=$(probe_list | head -n 1)
  [ -n "$FIRST" ] || fail "no probe binary name to link"
  [ -f "$BIN/$FIRST" ] || fail "wrapper $BIN/$FIRST was not staged"
  chmod +x "$BIN/$FIRST" 2>/dev/null
fi

# Put the tools bin first for the rest of this script only; the app prepends it
# for real sessions (see ReaManager.REA_BIN).
PATH="$BIN:$PATH"
export PATH

if have; then
  say "ok: installed from $FILE"
  exit 0
fi

fail "extracted $FILE but none of [$PROBES] is on PATH"
