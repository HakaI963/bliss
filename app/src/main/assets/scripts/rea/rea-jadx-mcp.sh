#!/bin/sh
# Install the jadx MCP bridge REA's Android lane needs.
#
#   rea-jadx-mcp.sh <rea-root> <url> <file-name> <bytes> <sha256>
#
# REA does not download or install this jar, and refuses every Android/Java
# operation until REA_JADX_MCP_JAR points at one it accepts. The installed
# `jadx` CLI is a different program and does not satisfy it.
#
# The version matters as much as the file. REA checks both the bridge's
# reported version and the release version, so a newer jar fails with the same
# "not configured" message as a missing one. The caller pins it.
set +e

BASE="$1"
URL="$2"
FILE="$3"
BYTES="$4"
SHA="$5"

if [ -z "$BASE" ] || [ -z "$URL" ] || [ -z "$FILE" ]; then
  echo "usage: rea-jadx-mcp.sh <rea-root> <url> <file-name> [bytes] [sha256]"
  exit 2
fi

DIR="$BASE/tools/jadx-mcp"
JAR="$DIR/$FILE"
mkdir -p "$DIR" || { echo "ERROR: cannot create $DIR"; exit 3; }

have_ok() {
  [ -f "$JAR" ] || return 1
  [ -n "$BYTES" ] && [ "$(wc -c < "$JAR" 2>/dev/null | tr -d ' ')" != "$BYTES" ] && return 1
  return 0
}

if have_ok; then
  if [ -z "$SHA" ] || command -v sha256sum >/dev/null 2>&1; then
    if [ -n "$SHA" ] && command -v sha256sum >/dev/null 2>&1; then
      ACTUAL=$(sha256sum "$JAR" 2>/dev/null | cut -d' ' -f1)
      if [ "$ACTUAL" = "$SHA" ]; then
        echo "jadx bridge already installed and verified"
        exit 0
      fi
      echo "existing jar failed its SHA-256 check; reinstalling"
    else
      echo "jadx bridge already installed"
      exit 0
    fi
  fi
fi

# Downloader. The jar is 42 MB, so resuming matters on a phone link.
fetch() {
  if command -v curl >/dev/null 2>&1; then
    curl -fL --retry 3 --retry-delay 2 -C - -o "$JAR.part" "$URL"
  elif command -v wget >/dev/null 2>&1; then
    wget -c -O "$JAR.part" "$URL"
  else
    echo "ERROR: neither curl nor wget is available"
    return 1
  fi
}

echo "downloading the jadx bridge: $URL"
attempt=1
while [ "$attempt" -le 4 ]; do
  if fetch; then break; fi
  attempt=$((attempt + 1))
  echo "download interrupted, retrying (attempt $attempt/4)"
  sleep 2
done

if [ ! -s "$JAR.part" ]; then
  rm -f "$JAR.part"
  echo "ERROR: the jadx bridge could not be downloaded"
  exit 4
fi

SIZE=$(wc -c < "$JAR.part" 2>/dev/null | tr -d ' ')
case "$SIZE" in ''|*[!0-9]*) SIZE=0 ;; esac
if [ -n "$BYTES" ] && [ "$SIZE" != "$BYTES" ]; then
  rm -f "$JAR.part"
  echo "ERROR: downloaded $SIZE bytes, expected $BYTES — not the pinned release"
  exit 5
fi

if [ -n "$SHA" ]; then
  if command -v sha256sum >/dev/null 2>&1; then
    ACTUAL=$(sha256sum "$JAR.part" 2>/dev/null | cut -d' ' -f1)
    if [ "$ACTUAL" != "$SHA" ]; then
      rm -f "$JAR.part"
      echo "ERROR: SHA-256 mismatch (got ${ACTUAL:-none}, expected $SHA)"
      exit 6
    fi
    echo "SHA-256 verified"
  else
    echo "warning: sha256sum is unavailable; the size check is all that ran"
  fi
fi

mv -f "$JAR.part" "$JAR" || { echo "ERROR: cannot move the jar into place"; exit 7; }
chmod 644 "$JAR" 2>/dev/null
echo "jadx bridge installed at $JAR"
echo "REA reads REA_JADX_MCP_JAR at startup; restart it to pick this up"
