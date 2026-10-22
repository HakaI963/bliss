#!/bin/sh
# Altaris local control CLI (`altaris`, with `altaris` kept as an alias).
# Installed into the host prefix and every guest distro. Everything goes to the
# app's loopback control server; no root is needed here.

BASE="${ALTARIS_CONTROL_URL:-http://127.0.0.1:47831}"

usage() {
  cat <<'EOF'
Altaris local clone control

  altaris status
  altaris clones
  altaris apps
  altaris list
  altaris ps
  altaris install /sdcard/Download/app.apk [label]
  altaris install https://example.com/app.apk [label]
  altaris uninstall <clone-package>
  altaris launch <clone-package> [width height fps]
  altaris stop <clone-package>
  altaris shot [out.jpg]
  altaris logs <clone-package> [lines]
  altaris ocr [out.json]
  altaris tap <x> <y>
  altaris swipe <x1> <y1> <x2> <y2> [ms]
  altaris text <text...>
  altaris key <back|home|enter|tab|esc|recents|keycode>
  altaris record start [fps] [width]
  altaris record stop
  altaris record status
  altaris mcp
  altaris endpoint

Default endpoint: http://127.0.0.1:47831
EOF
}

need_tool() {
  # PATH inside a proot guest can put the host prefix first. On some updates
  # that host curl is missing libnghttp3/libngtcp2, so a bare `command -v curl`
  # selects a broken binary even when the distro's own curl works. Probe for a
  # runtime that actually starts, then fall back to the guest copy.
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
    echo "altaris: no working curl, wget or python3 found" >&2
    exit 127
  fi
  [ "$HTTP_TOOL" = curl ] && [ -z "${CURL_BIN:-}" ] && CURL_BIN=curl
  [ "$HTTP_TOOL" = wget ] && [ -z "${WGET_BIN:-}" ] && WGET_BIN=wget
}

py_get() {
  python3 -c 'import sys,urllib.request; sys.stdout.buffer.write(urllib.request.urlopen(sys.argv[1]).read())' "$1"
}

py_post() {
  python3 -c 'import sys,urllib.request; r=urllib.request.Request(sys.argv[1],data=sys.argv[2].encode(),headers={"Content-Type":"application/json"},method="POST"); sys.stdout.buffer.write(urllib.request.urlopen(r).read())' "$1" "$2"
}

get() {
  need_tool
  if [ "$HTTP_TOOL" = curl ]; then
    "$CURL_BIN" -fsS "$BASE$1"
  elif [ "$HTTP_TOOL" = wget ]; then
    "$WGET_BIN" -qO- "$BASE$1"
  else
    py_get "$BASE$1"
  fi
}

get_file() {
  need_tool
  if [ "$HTTP_TOOL" = curl ]; then
    "$CURL_BIN" -fsS "$BASE$1" -o "$2"
  elif [ "$HTTP_TOOL" = wget ]; then
    "$WGET_BIN" -qO "$2" "$BASE$1"
  else
    python3 -c 'import sys,urllib.request; open(sys.argv[2],"wb").write(urllib.request.urlopen(sys.argv[1]).read())' "$BASE$1" "$2"
  fi
}

post() {
  need_tool
  if [ "$HTTP_TOOL" = curl ]; then
    "$CURL_BIN" -fsS -X POST -H 'Content-Type: application/json' --data "$2" "$BASE$1"
  elif [ "$HTTP_TOOL" = wget ]; then
    "$WGET_BIN" -qO- --header='Content-Type: application/json' --post-data="$2" "$BASE$1"
  else
    py_post "$BASE$1" "$2"
  fi
}

jesc() {
  printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

# Stream the APK straight into Altaris. This is the reliable path: it works for
# /emulated, /OpenCode..., chroot /root and any other path the guest can read,
# without requiring the Android app to see the guest filesystem.
upload_apk() {
  src="$1"
  label="$2"
  need_tool
  safe_name=$(basename "$src" | tr -d '\r\n' | sed 's/[^A-Za-z0-9._-]/_/g')
  safe_label=$(printf '%s' "$label" | tr -d '\r\n')
  if [ "$HTTP_TOOL" = curl ]; then
    "$CURL_BIN" -fsS -X POST \
      -H 'Content-Type: application/octet-stream' \
      -H "X-Altaris-Filename: $safe_name" \
      -H "X-Altaris-Label: $safe_label" \
      --data-binary @"$src" \
      "$BASE/install-upload"
  elif [ "$HTTP_TOOL" = wget ]; then
    "$WGET_BIN" -qO- \
      --header='Content-Type: application/octet-stream' \
      --header="X-Altaris-Filename: $safe_name" \
      --header="X-Altaris-Label: $safe_label" \
      --post-file="$src" \
      "$BASE/install-upload"
  else
    python3 -c 'import sys,urllib.request; p=sys.argv[1]; q=sys.argv[2]; d=open(p,"rb").read(); r=urllib.request.Request(sys.argv[3],data=d,headers={"Content-Type":"application/octet-stream","X-Altaris-Filename":q,"X-Altaris-Label":sys.argv[4]},method="POST"); sys.stdout.buffer.write(urllib.request.urlopen(r).read())' "$src" "$safe_name" "$BASE/install-upload" "$safe_label"
  fi
}

# Fallback for older guest wget builds that cannot post a file.
stage_apk() {
  src="$1"
  [ -f "$src" ] || { printf '%s' "$src"; return; }
  for dir in /sdcard/Download /storage/emulated/0/Download /emulated/Download; do
    if [ -d "$dir" ] && [ -w "$dir" ]; then
      dst="$dir/.altaris-upload-$$-$(basename "$src")"
      if cp "$src" "$dst" 2>/dev/null; then
        printf '%s' "$dst"
        return
      fi
    fi
  done
  printf '%s' "$src"
}

cmd="$1"
[ -n "$cmd" ] || { usage; exit 1; }
shift 2>/dev/null || true

case "$cmd" in
  status) get /status ;;
  clones|list|ps) get /clones ;;
  apps) get /apps ;;
  endpoint) echo "$BASE" ;;
  mcp) echo "$BASE/mcp" ;;
  install)
    src="$1"
    label="${2:-}"
    [ -n "$src" ] || { echo "altaris install: APK path or URL required" >&2; exit 1; }
    case "$src" in
      http://*|https://*)
        post /install "{\"url\":\"$(jesc "$src")\",\"label\":\"$(jesc "$label")\"}" ;;
      *)
        if [ -f "$src" ]; then
          result=$(upload_apk "$src" "$label" 2>/dev/null)
          if [ -n "$result" ]; then
            printf '%s\n' "$result"
            exit 0
          fi
        fi
        src=$(stage_apk "$src")
        result=$(post /install "{\"path\":\"$(jesc "$src")\",\"label\":\"$(jesc "$label")\"}")
        case "$src" in */.altaris-upload-*) rm -f "$src" ;; esac
        printf '%s\n' "$result" ;;
    esac
    ;;
  uninstall)
    [ -n "$1" ] || { echo "altaris uninstall: package required" >&2; exit 1; }
    post /uninstall "{\"package\":\"$(jesc "$1")\"}" ;;
  launch)
    [ -n "$1" ] || { echo "altaris launch: package required" >&2; exit 1; }
    post /launch "{\"package\":\"$(jesc "$1")\",\"width\":${2:-0},\"height\":${3:-0},\"fps\":${4:-0}}" ;;
  stop)
    [ -n "$1" ] || { echo "altaris stop: package required" >&2; exit 1; }
    post /stop "{\"package\":\"$(jesc "$1")\"}" ;;
  shot)
    out="${1:-altaris-shot-$(date +%s).jpg}"
    get_file "/screenshot?width=${ALTARIS_SHOT_WIDTH:-900}" "$out" && echo "$out" ;;
  logs)
    [ -n "$1" ] || { echo "altaris logs: package required" >&2; exit 1; }
    get "/logs?package=$(jesc "$1")&lines=${2:-300}" ;;
  ocr)
    out="${1:-}"
    if [ -n "$out" ]; then
      get_file "/ocr?width=${ALTARIS_OCR_WIDTH:-1000}" "$out" && echo "$out"
    else
      get "/ocr?width=${ALTARIS_OCR_WIDTH:-1000}"
    fi
    ;;
  tap)
    [ -n "$1" ] && [ -n "$2" ] || { echo "altaris tap: x y required" >&2; exit 1; }
    post /tap "{\"x\":$1,\"y\":$2}" ;;
  swipe)
    [ -n "$1" ] && [ -n "$2" ] && [ -n "$3" ] && [ -n "$4" ] || {
      echo "altaris swipe: x1 y1 x2 y2 [ms] required" >&2; exit 1; }
    post /swipe "{\"x1\":$1,\"y1\":$2,\"x2\":$3,\"y2\":$4,\"ms\":${5:-260}}" ;;
  text)
    [ -n "$1" ] || { echo "altaris text: text required" >&2; exit 1; }
    joined="$*"
    post /text "{\"text\":\"$(jesc "$joined")\"}" ;;
  key)
    [ -n "$1" ] || { echo "altaris key: key required" >&2; exit 1; }
    post /key "{\"key\":\"$(jesc "$1")\"}" ;;
  record)
    sub="$1"
    case "$sub" in
      start) post /record/start "{\"fps\":${2:-6},\"width\":${3:-720}}" ;;
      stop) post /record/stop "{}" ;;
      status|"") get /record/status ;;
      *) echo "altaris record: start|stop|status required" >&2; exit 1 ;;
    esac
    ;;
  help|-h|--help) usage ;;
  *) echo "altaris: unknown command '$cmd'" >&2; usage; exit 1 ;;
esac
