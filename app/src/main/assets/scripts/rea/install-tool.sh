#!/bin/sh
# Installs one analysis tool through the distro package manager, trying every
# known package name until one of them actually provides the tool binary.
#
#   install-tool.sh <manager> <probe,probe,...> <batch> [batch...]
#
# A batch is a comma-separated group of packages that must be installed in one
# transaction (python3,python3-pip). The exit status is 0 only when a probe
# binary exists afterwards or a probe-less batch installed cleanly, so the app
# can report a real per-tool result instead of guessing.
set +e

MG="$1"
PROBES="$2"
shift 2

if [ -z "$MG" ]; then
  echo "usage: install-tool.sh <manager> <probes> <batch> [batch...]"
  exit 2
fi

# The probe list is comma-separated ("aapt,aapt2", "7z,7za,7zr"). Splitting it
# with IFS=',' is required: the old word split produced one literal
# "aapt,aapt2" token, so every tool with more than one probe binary could
# never verify and reported failure even after a clean install.
probe_list() {
  [ -n "$PROBES" ] || return 0
  printf '%s\n' "$PROBES" | tr ',' '\n' | while IFS= read -r p; do
    [ -n "$p" ] && printf '%s\n' "$p"
  done
}

# True when any probe binary is already on PATH. (The `while` runs in a
# subshell, so the result travels back through stdout, not a variable.)
have() {
  probe_list | while IFS= read -r p; do
    if command -v "$p" >/dev/null 2>&1; then
      echo yes
      break
    fi
  done | grep -q yes
}

if [ -n "$PROBES" ] && have; then
  echo "already installed: $PROBES"
  exit 0
fi

prepare() {
  case "$MG" in
    apt) export DEBIAN_FRONTEND=noninteractive
         apt-get update -qq 2>&1 | tail -n 3 ;;
    apk) apk update >/dev/null 2>&1 ;;
    pacman) pacman -Sy --noconfirm >/dev/null 2>&1 ;;
    dnf) : ;;
    *) return 1 ;;
  esac
  return 0
}

install_batch() {
  case "$MG" in
    apt) apt-get install -y --no-install-recommends "$@" || apt-get install -y "$@" ;;
    apk) apk add --no-cache "$@" ;;
    dnf) dnf install -y "$@" ;;
    pacman) pacman -S --noconfirm --needed "$@" ;;
    *) echo "unsupported package manager: $MG"; return 9 ;;
  esac
}

prepare || { echo "ERROR: unsupported package manager: $MG"; exit 9; }

i=0
for batch in "$@"; do
  i=$((i + 1))
  pkgs=$(echo "$batch" | tr ',' ' ')
  [ -n "$pkgs" ] || continue
  echo "--- $MG attempt $i: $pkgs"
  install_batch $pkgs
  rc=$?
  if [ -n "$PROBES" ]; then
    if have; then
      echo "ok: $pkgs provides $PROBES"
      exit 0
    fi
    if [ "$rc" -eq 0 ]; then
      echo "warning: $pkgs installed but none of [$PROBES] is on PATH"
    fi
  elif [ "$rc" -eq 0 ]; then
    echo "ok: $pkgs"
    exit 0
  fi
done

if [ -n "$PROBES" ]; then
  echo "ERROR: $MG could not provide [$PROBES]"
else
  echo "ERROR: $MG could not install any of the known packages"
fi
exit 1
