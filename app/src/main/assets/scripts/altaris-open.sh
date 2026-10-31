#!/bin/sh
# Open a link in the phone's browser from inside an Altaris guest.
#
# Installed as `altaris-open` and as the browser shims (`xdg-open`,
# `sensible-browser`, `x-www-browser`, `www-browser`, `termux-open`,
# `termux-open-url`). A distro has no browser and no display, so every one of
# those used to fail with "no method available for opening" — including the
# sign-in links printed by AI CLIs and `gh auth login`. The app can start an
# Android VIEW intent, so ask it over the loopback control server instead.

BASE="${ALTARIS_CONTROL_URL:-http://127.0.0.1:47831}"

usage() {
  cat <<'EOF'
altaris-open <url>

Opens the URL in the Android browser through the Altaris app.
Also installed as xdg-open / sensible-browser / $BROWSER.
EOF
}

need_tool() {
  if [ -z "${HTTP_TOOL:-}" ]; then
    for c in curl /usr/bin/curl /usr/local/bin/curl /bin/curl; do
      if command -v "$c" >/dev/null 2>&1 && "$c" --version >/dev/null 2>&1; then
        HTTP_TOOL=curl
        CURL_BIN="$c"
        break
      fi
    done
  fi
  if [ -z "${HTTP_TOOL:-}" ]; then
    for w in wget /usr/bin/wget /usr/local/bin/wget /bin/wget; do
      if command -v "$w" >/dev/null 2>&1 && "$w" --version >/dev/null 2>&1; then
        HTTP_TOOL=wget
        WGET_BIN="$w"
        break
      fi
    done
  fi
  if [ -z "${HTTP_TOOL:-}" ] && command -v python3 >/dev/null 2>&1 \
      && python3 -c 'import urllib.request' >/dev/null 2>&1; then
    HTTP_TOOL=python
  fi
  if [ -z "${HTTP_TOOL:-}" ]; then
    echo "altaris-open: no working curl, wget or python3 found" >&2
    return 1
  fi
  return 0
}

jesc() {
  printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

post_json() {
  body=$1
  if [ "$HTTP_TOOL" = curl ]; then
    "$CURL_BIN" -fsS -X POST -H 'Content-Type: application/json' --data "$body" "$BASE/open-url"
  elif [ "$HTTP_TOOL" = wget ]; then
    "$WGET_BIN" -qO- --header='Content-Type: application/json' --post-data="$body" "$BASE/open-url"
  else
    python3 -c 'import sys,urllib.request; r=urllib.request.Request(sys.argv[1],data=sys.argv[2].encode(),headers={"Content-Type":"application/json"},method="POST"); sys.stdout.buffer.write(urllib.request.urlopen(r).read())' "$BASE/open-url" "$body"
  fi
}

url=
for arg in "$@"; do
  case "$arg" in
    -h|--help) usage; exit 0 ;;
    -*) continue ;;
  esac
  [ -n "$url" ] || url=$arg
done

if [ -z "$url" ]; then
  usage >&2
  exit 1
fi

# A GUI desktop session (XFCE) has a real browser and a display: let it handle
# the link so it opens inside the guest, as the user expects. Plain terminal
# sessions have no DISPLAY, and that is the case this shim exists for.
if [ -n "${DISPLAY:-}" ]; then
  # Prefer the distro's own xdg-open when it is a real one. This shim lives in
  # /usr/local/bin, so /usr/bin/xdg-open (xdg-utils) is still intact; only
  # delegate to a file that is not a copy of this shim.
  for d in /usr/bin /bin; do
    if [ -x "$d/xdg-open" ] && [ -r "$d/xdg-open" ] && \
       ! grep -q 'Altaris only opens links in the phone browser' "$d/xdg-open" 2>/dev/null; then
      exec "$d/xdg-open" "$@"
    fi
  done
  for b in firefox firefox-esr chromium chromium-browser google-chrome epiphany; do
    if command -v "$b" >/dev/null 2>&1; then
      exec "$b" "$url"
    fi
  done
fi

case "$url" in
  *://*|mailto:*|tel:*|sms:*) ;;
  /*)
    if [ -e "$url" ]; then
      echo "altaris-open: $url is a local file; Altaris only opens links in the phone browser" >&2
      exit 1
    fi
    ;;
  *)
    # A bare host ("example.com/x") is what most CLIs print.
    url="https://$url"
    ;;
esac

need_tool || exit 127

if ! reply=$(post_json "{\"url\":\"$(jesc "$url")\"}" 2>&1); then
  echo "altaris-open: could not reach the Altaris app at $BASE" >&2
  echo "altaris-open: is Altaris running? ($reply)" >&2
  exit 1
fi

case "$reply" in
  *'"ok":true'*) printf '%s\n' "$url" ;;
  *)
    echo "altaris-open: the app refused the link: $reply" >&2
    exit 1
    ;;
esac
