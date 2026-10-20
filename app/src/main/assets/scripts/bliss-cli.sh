#!/bin/sh
# Bliss local control CLI. Installed into the host prefix and every guest distro.
# Everything goes to the app's loopback control server; no root is needed here.

BASE="${BLISS_CONTROL_URL:-http://127.0.0.1:47831}"

usage() {
  cat <<'EOF'
Bliss local clone control

  bliss status
  bliss clones
  bliss install /sdcard/Download/app.apk [label]
  bliss install https://example.com/app.apk [label]
  bliss uninstall <clone-package>
  bliss launch <clone-package> [width height fps]
  bliss stop <clone-package>
  bliss shot [out.jpg]
  bliss logs <clone-package> [lines]
  bliss ocr [out.json]
  bliss tap <x> <y>
  bliss swipe <x1> <y1> <x2> <y2> [ms]
  bliss text <text...>
  bliss key <back|home|enter|tab|esc|recents|keycode>
  bliss record start [fps] [width]
  bliss record stop
  bliss record status
  bliss mcp
  bliss endpoint

Default endpoint: http://127.0.0.1:47831
EOF
}

need_tool() {
  if command -v curl >/dev/null 2>&1; then HTTP_TOOL=curl
  elif command -v wget >/dev/null 2>&1; then HTTP_TOOL=wget
  else
    echo "bliss: curl or wget is required" >&2
    exit 127
  fi
}

get() {
  need_tool
  if [ "$HTTP_TOOL" = curl ]; then
    curl -fsS "$BASE$1"
  else
    wget -qO- "$BASE$1"
  fi
}

get_file() {
  need_tool
  if [ "$HTTP_TOOL" = curl ]; then
    curl -fsS "$BASE$1" -o "$2"
  else
    wget -qO "$2" "$BASE$1"
  fi
}

post() {
  need_tool
  if [ "$HTTP_TOOL" = curl ]; then
    curl -fsS -X POST -H 'Content-Type: application/json' --data "$2" "$BASE$1"
  else
    wget -qO- --header='Content-Type: application/json' --post-data="$2" "$BASE$1"
  fi
}

jesc() {
  printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

cmd="$1"
[ -n "$cmd" ] || { usage; exit 1; }
shift 2>/dev/null || true

case "$cmd" in
  status) get /status ;;
  clones) get /clones ;;
  endpoint) echo "$BASE" ;;
  mcp) echo "$BASE/mcp" ;;
  install)
    src="$1"
    label="${2:-}"
    [ -n "$src" ] || { echo "bliss install: APK path or URL required" >&2; exit 1; }
    case "$src" in
      http://*|https://*)
        post /install "{\"url\":\"$(jesc "$src")\",\"label\":\"$(jesc "$label")\"}" ;;
      *)
        post /install "{\"path\":\"$(jesc "$src")\",\"label\":\"$(jesc "$label")\"}" ;;
    esac
    ;;
  uninstall)
    [ -n "$1" ] || { echo "bliss uninstall: package required" >&2; exit 1; }
    post /uninstall "{\"package\":\"$(jesc "$1")\"}" ;;
  launch)
    [ -n "$1" ] || { echo "bliss launch: package required" >&2; exit 1; }
    post /launch "{\"package\":\"$(jesc "$1")\",\"width\":${2:-0},\"height\":${3:-0},\"fps\":${4:-0}}" ;;
  stop)
    [ -n "$1" ] || { echo "bliss stop: package required" >&2; exit 1; }
    post /stop "{\"package\":\"$(jesc "$1")\"}" ;;
  shot)
    out="${1:-bliss-shot-$(date +%s).jpg}"
    get_file "/screenshot?width=${BLISS_SHOT_WIDTH:-900}" "$out" && echo "$out" ;;
  logs)
    [ -n "$1" ] || { echo "bliss logs: package required" >&2; exit 1; }
    get "/logs?package=$(jesc "$1")&lines=${2:-300}" ;;
  ocr)
    out="${1:-}"
    if [ -n "$out" ]; then
      get_file "/ocr?width=${BLISS_OCR_WIDTH:-1000}" "$out" && echo "$out"
    else
      get "/ocr?width=${BLISS_OCR_WIDTH:-1000}"
    fi
    ;;
  tap)
    [ -n "$1" ] && [ -n "$2" ] || { echo "bliss tap: x y required" >&2; exit 1; }
    post /tap "{\"x\":$1,\"y\":$2}" ;;
  swipe)
    [ -n "$1" ] && [ -n "$2" ] && [ -n "$3" ] && [ -n "$4" ] || {
      echo "bliss swipe: x1 y1 x2 y2 [ms] required" >&2; exit 1; }
    post /swipe "{\"x1\":$1,\"y1\":$2,\"x2\":$3,\"y2\":$4,\"ms\":${5:-260}}" ;;
  text)
    [ -n "$1" ] || { echo "bliss text: text required" >&2; exit 1; }
    joined="$*"
    post /text "{\"text\":\"$(jesc "$joined")\"}" ;;
  key)
    [ -n "$1" ] || { echo "bliss key: key required" >&2; exit 1; }
    post /key "{\"key\":\"$(jesc "$1")\"}" ;;
  record)
    sub="$1"
    case "$sub" in
      start) post /record/start "{\"fps\":${2:-6},\"width\":${3:-720}}" ;;
      stop) post /record/stop "{}" ;;
      status|"") get /record/status ;;
      *) echo "bliss record: start|stop|status required" >&2; exit 1 ;;
    esac
    ;;
  help|-h|--help) usage ;;
  *) echo "bliss: unknown command '$cmd'" >&2; usage; exit 1 ;;
esac
