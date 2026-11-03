#!/bin/sh
# Shared package-lock discipline for the guest installers.
#
# Sourced by install-tool.sh, install-archive.sh, rea-core-install.sh and the
# app's one-shot package script. Every package-manager call in REA goes
# through pkg_run, for two reasons:
#
#   1. apt takes an exclusive lock for the whole transaction, and REA reaches
#      the package manager from several places at once — the tool lane, the
#      asset toolkit, the pip bootstrap for frida. The second caller failed
#      immediately with "Could not get lock", so a parallel install looked
#      broken even though nothing was wrong.
#
#   2. An installer that was killed (app closed, screen rotated, timeout) left
#      apt running as root, still holding the lock. Every later apt — including
#      the user's own in a terminal — then failed against a process nobody
#      could see or stop.
#
# So: one flock serialises our own callers, a bounded wait handles a package
# manager the user is running, and an orphaned one (parent gone) is reaped
# instead of waited on forever. A lock file whose holder has died needs no
# cleanup: the kernel releases the flock when the process exits.

ALTARIS_PKG_LOCK="${ALTARIS_PKG_LOCK:-/tmp/.altaris-pkg.lock}"
ALTARIS_PKG_WAIT="${ALTARIS_PKG_WAIT:-900}"
ALTARIS_PKG_TIMEOUT="${ALTARIS_PKG_TIMEOUT:-1800}"

# Live processes that can hold the apt/dpkg lock, as "pid ppid" lines.
#
# Pure shell builtins: no cat, sed or cut per process. The first version forked
# four processes for every PID in /proc, which took seconds on a busy device and
# made the wait loop slower than the thing it was waiting for.
#
# /proc/PID/stat is "pid (comm) state ppid ...". comm may itself contain spaces
# and parentheses, so the fields are taken by stripping around the LAST ") "
# rather than by splitting on whitespace.
#
# Zombies are skipped: a zombie has already closed its descriptors, so it holds
# nothing, and counting it as a holder would wait forever.
pkg_scan() {
  for _d in /proc/[0-9]*; do
    _stat=
    { IFS= read -r _stat < "$_d/stat"; } 2>/dev/null || continue
    [ -n "$_stat" ] || continue
    _after=${_stat##*) }          # everything after the last ") "
    [ "$_after" = "$_stat" ] && continue   # no parenthesised comm: not a real stat line
    _state=${_after%% *}
    _rest=${_after#* }
    _ppid=${_rest%% *}
    case "$_state" in
      Z|X|'') continue ;;
    esac
    _tmp=${_stat#*(}
    _comm=${_tmp%%) *}
    case "$_comm" in
      apt-get|apt|dpkg|dpkg-deb|unattended-upgr) ;;
      *) continue ;;
    esac
    case "$_ppid" in
      ''|*[!0-9]*) continue ;;
    esac
    printf '%s %s\n' "${_d#/proc/}" "$_ppid"
  done
}

# Backwards-compatible name used by the installers.
pkg_holders() { pkg_scan; }

# True when [pid] is orphaned: reparented to init, or its parent is gone.
#
# This is the distinction that makes reaping safe. A package manager the user
# started in a terminal has a live shell as its parent, so it is never touched.
# Two shapes count as orphaned: reparented to init (ppid 1, or 0 under proot),
# or still pointing at a parent that no longer exists — either way nothing is
# supervising it and it will never release the lock on its own.
pkg_is_orphan() {
  case "$2" in
    ''|*[!0-9]*) return 1 ;;
  esac
  [ "$2" -le 1 ] && return 0
  [ -d "/proc/$2" ] && return 1
  return 0
}

# Orphaned holders, one pid per line.
pkg_orphans() {
  pkg_scan | while read -r _pid _ppid; do
    [ -n "$_pid" ] || continue
    if pkg_is_orphan "$_pid" "$_ppid"; then
      printf '%s\n' "$_pid"
    fi
  done
}

# Reap orphaned package managers without waiting for anything.
#
# Called after a timeout: the wrapper that started apt was killed, so apt is
# now an orphan still holding the database. Removing it here is what keeps the
# user's next `apt` in a terminal from failing against a process they cannot
# see. Only orphans are touched — a package manager the user is running has a
# live shell as its parent and is left alone.
pkg_reap_orphans() {
  _orphans=$(pkg_orphans)
  [ -n "$_orphans" ] || return 0
  echo "reaping orphaned package process(es): $(printf '%s' "$_orphans" | tr '\n' ' ')"
  for _p in $_orphans; do kill -TERM "$_p" 2>/dev/null; done
  sleep 2
  for _p in $(pkg_orphans); do kill -KILL "$_p" 2>/dev/null; done
  return 0
}

# Wait until no package manager holds the database, reaping orphans.
pkg_wait() {
  _deadline=$(( $(date +%s) + ALTARIS_PKG_WAIT ))
  while :; do
    if [ -n "$(pkg_orphans)" ]; then
      pkg_reap_orphans
      continue
    fi
    _live=$(pkg_holders | cut -d' ' -f1 | tr '\n' ' ')
    [ -z "$_live" ] && return 0
    if [ "$(date +%s)" -ge "$_deadline" ]; then
      echo "ERROR: the package database is still in use by pid(s) $_live"
      echo "ERROR: finish or stop that process, then retry"
      return 1
    fi
    echo "waiting for the package database (held by pid $_live)"
    sleep 3
  done
}

# Run a package-manager command with the lock held and a deadline.
#
# The deadline matters because the app cannot kill a guest process reliably:
# proot's children survive their tracer, so a hung apt outlives the session
# that started it. Bounding it here means it always ends, and ends cleanly —
# apt releases its locks on SIGTERM.
pkg_run() {
  [ "$#" -gt 0 ] || return 2
  _held=0
  if command -v flock >/dev/null 2>&1; then
    if exec 9>"$ALTARIS_PKG_LOCK" 2>/dev/null; then
      if flock -w "$ALTARIS_PKG_WAIT" 9 2>/dev/null; then
        _held=1
      else
        echo "ERROR: another Altaris package operation is still running"
        exec 9>&-
        return 75
      fi
    fi
  fi
  if ! pkg_wait; then
    [ "$_held" = "1" ] && exec 9>&-
    return 75
  fi
  if command -v timeout >/dev/null 2>&1; then
    timeout -k 30 "$ALTARIS_PKG_TIMEOUT" "$@"
    _rc=$?
  else
    "$@"
    _rc=$?
  fi
  if [ "$_held" = "1" ]; then
    exec 9>&-
  fi
  return $_rc
}
