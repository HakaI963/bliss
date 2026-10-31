#!/bin/sh
# Installs the REA core (Node 22 + rea-agents) into the Altaris REA root.
#
#   rea-core-install.sh <rea-base> <node-version> <rea-version>
#
# Never aborts half-way without saying why: every failure prints an ERROR line
# the app shows in the REA log, so a failed install is diagnosable on device.
set +e

BASE="$1"
NODE_VERSION="$2"
REA_VERSION="$3"
if [ -z "$BASE" ] || [ -z "$NODE_VERSION" ] || [ -z "$REA_VERSION" ]; then
  echo "usage: rea-core-install.sh <rea-base> <node-version> <rea-version>"
  exit 2
fi

say() { echo "$*"; }
fail() { echo "ERROR: $*"; exit 1; }

mkdir -p "$BASE" || fail "cannot create $BASE"

ARCH=$(uname -m)
case "$ARCH" in
  aarch64|arm64) NARCH=arm64 ;;
  x86_64|amd64) NARCH=x64 ;;
  armv7l|armv8l) NARCH=armv7l ;;
  *) fail "unsupported architecture: $ARCH" ;;
esac

ensure_downloader() {
  command -v curl >/dev/null 2>&1 && return 0
  command -v wget >/dev/null 2>&1 && return 0
  if command -v apt-get >/dev/null 2>&1; then
    apt-get update -qq
    apt-get install -y --no-install-recommends curl ca-certificates
  elif command -v apk >/dev/null 2>&1; then
    apk add --no-cache curl ca-certificates
  elif command -v dnf >/dev/null 2>&1; then
    dnf install -y curl ca-certificates
  elif command -v pacman >/dev/null 2>&1; then
    pacman -Sy --noconfirm curl ca-certificates
  fi
  command -v curl >/dev/null 2>&1 && return 0
  command -v wget >/dev/null 2>&1 && return 0
  return 1
}

fetch() { # fetch <url> <outfile>
  if command -v curl >/dev/null 2>&1; then
    curl -fL --retry 3 -o "$2" "$1"
  else
    wget -O "$2" "$1"
  fi
}

node_major() { # node_major <node-binary>
  "$1" -v 2>/dev/null | sed 's/^v//' | cut -d. -f1
}

ensure_downloader || fail "no curl or wget available in the distro"

NODE=""
NPM=""
if [ -x "$BASE/node/bin/node" ] && [ -x "$BASE/node/bin/npm" ]; then
  NODE="$BASE/node/bin/node"
  NPM="$BASE/node/bin/npm"
fi

if [ -z "$NODE" ] && command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1; then
  V=$(node_major "$(command -v node)")
  case "$V" in ''|*[!0-9]*) V=0 ;; esac
  if [ "$V" -ge 22 ]; then
    NODE=$(command -v node)
    NPM=$(command -v npm)
  fi
fi

# Alpine has its own nodejs package; use it when it is new enough.
if [ -z "$NODE" ] && [ -f /etc/alpine-release ]; then
  apk add --no-cache nodejs npm >/dev/null 2>&1
  if command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1; then
    V=$(node_major "$(command -v node)")
    case "$V" in ''|*[!0-9]*) V=0 ;; esac
    if [ "$V" -ge 22 ]; then
      NODE=$(command -v node)
      NPM=$(command -v npm)
    fi
  fi
fi

if [ -z "$NODE" ]; then
  if [ -f /etc/alpine-release ]; then
    URL="https://unofficial-builds.nodejs.org/download/release/$NODE_VERSION/node-$NODE_VERSION-linux-$NARCH-musl.tar.gz"
  else
    URL="https://nodejs.org/dist/$NODE_VERSION/node-$NODE_VERSION-linux-$NARCH.tar.gz"
  fi
  say "downloading $URL"
  rm -rf "$BASE/node" "$BASE/node.tmp" "$BASE/node.tgz"
  fetch "$URL" "$BASE/node.tgz" || fail "node download failed: $URL"
  mkdir -p "$BASE/node.tmp" || fail "cannot create $BASE/node.tmp"
  tar -xzf "$BASE/node.tgz" -C "$BASE/node.tmp" --strip-components=1 \
    || fail "node archive could not be extracted"
  rm -f "$BASE/node.tgz"
  mv "$BASE/node.tmp" "$BASE/node" || fail "cannot move node into place"
  NODE="$BASE/node/bin/node"
  NPM="$BASE/node/bin/npm"
fi

[ -x "$NODE" ] || fail "node is not executable: $NODE"
say "node: $("$NODE" -v 2>&1)"

export PATH="$(dirname "$NODE"):$PATH"
export npm_config_cache="$BASE/.npm"
export npm_config_audit=false
export npm_config_fund=false
export npm_config_progress=false
mkdir -p "$BASE/npm" || fail "cannot create $BASE/npm"

install_rea() {
  "$NPM" install -g "rea-agents@$REA_VERSION" --prefix "$BASE/npm" \
    --no-audit --no-fund --loglevel=error
}

if ! install_rea; then
  say "npm install failed, retrying against the public registry"
  npm_config_registry=https://registry.npmjs.org install_rea \
    || fail "npm could not install rea-agents@$REA_VERSION"
fi

# npm creates the bin link without the exec bit in some proot setups.
if [ -f "$BASE/npm/bin/rea" ] && [ ! -x "$BASE/npm/bin/rea" ]; then
  chmod +x "$BASE/npm/bin/rea"
fi
[ -x "$BASE/npm/bin/rea" ] || fail "the rea binary is missing after install"

say "rea: $(PATH="$(dirname "$NODE"):$PATH" "$BASE/npm/bin/rea" --version 2>&1 | head -n 1)"
touch "$BASE/.installed" || fail "cannot write $BASE/.installed"
say "REA core installed in $BASE"
