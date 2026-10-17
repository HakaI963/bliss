# bliss-banner.sh — Bliss launch sticker: truecolor half-block anime art,
# one random design per interactive shell, printed at most ONCE per terminal
# tab (even if several rc files or nested shells source it). Works with zsh,
# bash and POSIX sh. Opt out at runtime: FLUX_NO_BANNER=1
bliss_banner_art() {
  # zsh: empty glob must not abort the function.
  if [ -n "${ZSH_VERSION:-}" ]; then
    setopt localoptions nullglob nonomatch 2>/dev/null || true
  fi
  _bb_dir="${HOME:-/root}/.config/bliss/banner"
  [ -d "$_bb_dir" ] || return 0
  _bb_list=""
  _bb_n=0
  for _bb_f in "$_bb_dir"/*.txt; do
    [ -f "$_bb_f" ] || continue
    _bb_list="$_bb_list$_bb_f
"
    _bb_n=$((_bb_n + 1))
  done
  [ "$_bb_n" -gt 0 ] || return 0
  if [ -n "${RANDOM:-}" ]; then
    _bb_i=$(( (RANDOM % _bb_n) + 1 ))
  else
    _bb_i="$(awk -v n="$_bb_n" 'BEGIN { srand(); print int(rand() * n) + 1 }' 2>/dev/null || echo 1)"
  fi
  _bb_pick="$(printf '%s' "$_bb_list" | sed -n "${_bb_i}p")"
  [ -f "$_bb_pick" ] || return 0
  # The designs carry literal \033 truecolor escapes -> rendered with printf %b.
  if grep -q '\\033' "$_bb_pick" 2>/dev/null; then
    printf '%b\n' "$(cat "$_bb_pick" 2>/dev/null || true)"
  else
    cat "$_bb_pick" 2>/dev/null || true
  fi
  unset _bb_dir _bb_list _bb_n _bb_f _bb_i _bb_pick
}
bliss_banner() {
  [ "${FLUX_NO_BANNER:-0}" = "1" ] && return 0
  [ -z "${_BLISS_BANNER_SHOWN:-}" ] || return 0
  case $- in
    *i*) ;;
    *) return 0 ;;
  esac
  # Once per terminal tab. The exported guard covers nested shells that
  # inherit the environment. A pty-keyed marker (pty node ctime + a short
  # time window as fallback) covers shells that lose the environment
  # (`su -` chains, re-sourced rc files in a fresh process): nested shells in
  # the SAME tab never reprint, while a NEW tab - even one that reuses the
  # same pty number quickly - gets its own sticker.
  _bb_skip=0
  _bb_tty="$(tty 2>/dev/null || true)"
  case "$_bb_tty" in
    /dev/*)
      _bb_mark="${TMPDIR:-/tmp}/.bliss-banner$(printf '%s' "$_bb_tty" | tr -d ' /')"
      _bb_now="$(date +%s 2>/dev/null || echo 0)"
      case "$_bb_now" in ''|*[!0-9]*) _bb_now=0 ;; esac
      # Sub-second pty node ctime: exact per-tab identity when the platform
      # reports it; otherwise a tight open-chain window dedupes nested shells.
      _bb_ct="$(stat -c %.9Z "$_bb_tty" 2>/dev/null || echo '')"
      case "$_bb_ct" in
        *.*) case "$_bb_ct" in *[!0-9.]*) _bb_ct="" ;; esac ;;
        *) _bb_ct="" ;;
      esac
      if [ -f "$_bb_mark" ]; then
        _bb_prev="$(sed -n 1p "$_bb_mark" 2>/dev/null || echo 0)"
        _bb_ct_prev="$(sed -n 2p "$_bb_mark" 2>/dev/null || echo '')"
        case "$_bb_prev" in ''|*[!0-9]*) _bb_prev=0 ;; esac
        if [ -n "$_bb_ct" ] && [ "$_bb_ct" = "$_bb_ct_prev" ]; then
          _bb_skip=1
        elif [ -z "$_bb_ct" ] && [ "$_bb_now" -gt 0 ] && [ "$_bb_prev" -gt 0 ] && [ $((_bb_now - _bb_prev)) -lt 10 ]; then
          _bb_skip=1
        fi
      fi
      if [ "$_bb_skip" = "0" ]; then
        printf '%s\n%s\n' "$_bb_now" "$_bb_ct" > "$_bb_mark" 2>/dev/null || true
      fi
      ;;
  esac
  if [ "$_bb_skip" = "1" ]; then
    export _BLISS_BANNER_SHOWN=1
    unset _bb_skip _bb_tty _bb_mark _bb_now _bb_prev _bb_ct _bb_ct_prev
    return 0
  fi
  export _BLISS_BANNER_SHOWN=1
  bliss_banner_art
  # Header: exactly one line, "bliss@<id>" (distro id only). ID= from
  # /etc/os-release; fallbacks: first word of PRETTY_NAME, then "linux".
  _bb_fid=""
  if [ -r /etc/os-release ]; then
    _bb_fid="$(sed -n 's/^ID=//p' /etc/os-release 2>/dev/null | head -n 1 | tr -d '"' | tr '[:upper:]' '[:lower:]')"
    if [ -z "$_bb_fid" ]; then
      _bb_fid="$(sed -n 's/^PRETTY_NAME=//p' /etc/os-release 2>/dev/null | head -n 1 | tr -d '"' | awk '{print $1}' | tr '[:upper:]' '[:lower:]')"
    fi
  fi
  [ -n "$_bb_fid" ] || _bb_fid="linux"
  printf '%s\n' "bliss@$_bb_fid"
  unset _bb_fid _bb_skip _bb_tty _bb_mark
}
bliss_banner
