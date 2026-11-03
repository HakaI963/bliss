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

# Every package-manager call goes through pkg_run (see pkg-lock.sh): it
# serialises Altaris's own callers, waits out a package manager the user is
# running, and reaps an orphaned one instead of leaving it holding the lock.
#
# The `[ -r ]` test is not cosmetic: sourcing a missing file is a fatal error
# for a POSIX shell, so an unguarded `.` would exit here and the installer
# would do nothing at all if staging had failed.
ALTARIS_PKG_SH_DIR=$(dirname "$0")
if [ -r "$ALTARIS_PKG_SH_DIR/pkg-lock.sh" ]; then
  . "$ALTARIS_PKG_SH_DIR/pkg-lock.sh"
elif [ -r /opt/altaris/rea/scripts/pkg-lock.sh ]; then
  . /opt/altaris/rea/scripts/pkg-lock.sh
fi

MG="$1"
PROBES="$2"
shift 2

if [ -z "$MG" ]; then
  echo "usage: install-tool.sh <manager> <probes> <batch> [batch...]"
  exit 2
fi

# Without the shared lock helper, still never fail on someone else's lock:
# wait a bounded time and say why if it does not clear.
if ! command -v pkg_run >/dev/null 2>&1; then
  pkg_run() {
    if command -v timeout >/dev/null 2>&1; then
      timeout -k 30 1800 "$@"
    else
      "$@"
    fi
  }
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
         pkg_run apt-get update -qq 2>&1 | tail -n 3 ;;
    apk) pkg_run apk update >/dev/null 2>&1 ;;
    pacman) pkg_run pacman -Sy --noconfirm >/dev/null 2>&1 ;;
    dnf) : ;;
    *) return 1 ;;
  esac
  return 0
}

install_batch() {
  case "$MG" in
    apt) pkg_run apt-get install -y --no-install-recommends "$@" || pkg_run apt-get install -y "$@" ;;
    apk) pkg_run apk add --no-cache "$@" ;;
    dnf) pkg_run dnf install -y "$@" ;;
    pacman) pkg_run pacman -S --noconfirm --needed "$@" ;;
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
  # 75 is pkg_run's "the database is still busy" status. Retrying the package
  # names cannot help; say what is wrong and stop.
  if [ "$rc" -eq 75 ]; then
    echo "ERROR: the package database is busy and did not free up"
    exit 1
  fi
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
