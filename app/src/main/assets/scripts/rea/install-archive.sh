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
# Smallest download that can be the real release. Tools range from a few
# hundred KB (a Python zipapp) to hundreds of MB; a single floor either waves a
# captive-portal page through or rejects a real zipapp.
MIN_BYTES="$6"
case "$MIN_BYTES" in ''|*[!0-9]*) MIN_BYTES=100000 ;; esac

if [ -z "$URL" ] || [ -z "$FILE" ] || [ -z "$DIR" ]; then
  echo "usage: install-archive.sh <url> <file> <dir> <probes> [launcher] [min-bytes]"
  exit 2
fi

fail() { echo "ERROR: $*"; exit 1; }
say() { echo "$*"; }

# Package-manager calls go through the shared lock discipline. Archives install
# in parallel with the package lane, and both reach apt; without this the
# second one failed with "Could not get lock" (see pkg-lock.sh).
ALTARIS_PKG_SH_DIR=$(dirname "$0")
if [ -r "$ALTARIS_PKG_SH_DIR/pkg-lock.sh" ]; then
  . "$ALTARIS_PKG_SH_DIR/pkg-lock.sh"
elif [ -r /opt/altaris/rea/scripts/pkg-lock.sh ]; then
  . /opt/altaris/rea/scripts/pkg-lock.sh
fi
if ! command -v pkg_run >/dev/null 2>&1; then
  pkg_run() {
    if command -v timeout >/dev/null 2>&1; then
      timeout -k 30 1800 "$@"
    else
      "$@"
    fi
  }
fi

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
# Resuming matters: a phone link drops mid-download routinely (curl 56), and
# the previous code deleted the partial and restarted from zero, so a 23 MB
# archive that reached 19 MB was thrown away and failed. -C - continues.
if command -v curl >/dev/null 2>&1; then
  dl() { curl -fL --retry 3 --retry-delay 2 -C - -o "$2" "$1"; }
elif command -v wget >/dev/null 2>&1; then
  dl() { wget -c -O "$2" "$1"; }
else
  # Try to get one; some minimal rootfs images ship neither. curl is also what
  # the terminal needs for the usual one-liners, so it is installed here rather
  # than assumed — a rootfs with neither is common.
  if command -v apt-get >/dev/null 2>&1; then
    pkg_run apt-get update -qq >/dev/null 2>&1
    pkg_run apt-get install -y --no-install-recommends curl ca-certificates >/dev/null 2>&1
  elif command -v dnf >/dev/null 2>&1; then
    pkg_run dnf install -y curl ca-certificates >/dev/null 2>&1
  elif command -v apk >/dev/null 2>&1; then
    pkg_run apk add --no-cache curl ca-certificates >/dev/null 2>&1
  elif command -v pacman >/dev/null 2>&1; then
    pkg_run pacman -Sy --noconfirm curl ca-certificates >/dev/null 2>&1
  fi
  command -v curl >/dev/null 2>&1 || fail "no curl or wget available in the distro"
  dl() { curl -fL --retry 3 --retry-delay 2 -C - -o "$2" "$1"; }
fi

# --- unpack -----------------------------------------------------------------
mkdir -p "$DIR" || fail "cannot create $DIR"
TMP="$DIR/.download.$$"
rm -f "$TMP"

say "downloading $URL"
_attempt=1
while [ "$_attempt" -le 5 ]; do
  if dl "$URL" "$TMP"; then break; fi
  _attempt=$((_attempt + 1))
  say "download interrupted, resuming (attempt $_attempt/5)"
  sleep 2
done
if [ ! -s "$TMP" ]; then
  rm -f "$TMP"
  fail "download failed: $URL"
fi

SIZE=$(wc -c < "$TMP" 2>/dev/null | tr -d ' ')
case "$SIZE" in ''|*[!0-9]*) SIZE=0 ;; esac
# An HTML error page is a few KB; a real release is at least MIN_BYTES. Refuse
# both a failed transfer and a captive-portal page.
if [ "$SIZE" -lt "$MIN_BYTES" ]; then
  head -c 200 "$TMP" 2>/dev/null
  rm -f "$TMP"
  fail "download is only ${SIZE} bytes — not a release archive"
fi

case "$FILE" in
  *.zip)
    command -v unzip >/dev/null 2>&1 || {
      if command -v apt-get >/dev/null 2>&1; then
        pkg_run apt-get install -y --no-install-recommends unzip >/dev/null 2>&1
      elif command -v dnf >/dev/null 2>&1; then
        pkg_run dnf install -y unzip >/dev/null 2>&1
      elif command -v apk >/dev/null 2>&1; then
        pkg_run apk add --no-cache unzip >/dev/null 2>&1
      elif command -v pacman >/dev/null 2>&1; then
        pkg_run pacman -S --noconfirm --needed unzip >/dev/null 2>&1
      fi
    }
    command -v unzip >/dev/null 2>&1 || fail "unzip is required to extract $FILE"
    unzip -oq "$TMP" -d "$DIR" || { rm -f "$TMP"; fail "cannot extract $FILE"; }
    ;;
  *.tar.gz|*.tgz)
    tar -xzf "$TMP" -C "$DIR" || { rm -f "$TMP"; fail "cannot extract $FILE"; }
    ;;
  *.tar.xz|*.txz)
    # AssetRipper publishes .tar.xz for linux arm64; tar handles it on its own
    # in every distro that ships xz support, but say so plainly if it does not.
    tar -xJf "$TMP" -C "$DIR" || { rm -f "$TMP"; fail "cannot extract $FILE (is xz installed?)"; }
    ;;
  *.jar)
    # A jar is the program; keep it under its own name.
    mv -f "$TMP" "$DIR/$FILE" || fail "cannot place $FILE"
    ;;
  *.pyz)
    # A Python zipapp is the program too, and it must stay intact — unpacking
    # it would destroy it. It carries no shebang, so the caller's shim runs it
    # through python3 rather than exec'ing it directly.
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
  # Find the launcher by its base name anywhere in the extracted tree.
  #
  # Release tarballs nest arbitrarily and inconsistently: jadx keeps
  # jadx-1.5.6/bin/jadx, radare2 unpacks to
  # data/data/org.radare.radare2installer/radare2/bin/r2, and rizin to
  # data/data/org.rizinorg.rizininstaller/bin/rizin. A fixed "bin/x" path only
  # ever worked for jadx. Flattening cannot fix this either, because every level
  # of those trees is a single directory literally named "data", so the move
  # collides and the loop cannot make progress.
  LAUNCH_BASE=$(basename "$LAUNCHER")
  TARGET=""
  if [ -f "$DIR/$LAUNCHER" ]; then
    TARGET="$DIR/$LAUNCHER"
  else
    # Prefer a real file over a symlink, and prefer a bin/ directory, so a
    # script that happens to share the name does not win.
    for candidate in \
      $(find "$DIR" -type f -name "$LAUNCH_BASE" 2>/dev/null) \
      $(find "$DIR" -type l -name "$LAUNCH_BASE" 2>/dev/null); do
      case "$candidate" in
        */bin/*) TARGET="$candidate"; break ;;
        *) [ -z "$TARGET" ] && TARGET="$candidate" ;;
      esac
    done
  fi
  [ -n "$TARGET" ] || fail "launcher $LAUNCHER is missing from $FILE"
  [ -f "$TARGET" ] || [ -L "$TARGET" ] || fail "launcher $LAUNCHER is not a file in $FILE"
  chmod +x "$TARGET" 2>/dev/null || true
  for p in $(probe_list); do
    # Link to the absolute path inside the unpacked tree, so a relative symlink
    # in the release (r2 -> r2blob) still resolves from the bin directory.
    ln -sf "$TARGET" "$BIN/$p" 2>/dev/null || cp -f "$TARGET" "$BIN/$p" 2>/dev/null
    chmod +x "$BIN/$p" 2>/dev/null || true
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
