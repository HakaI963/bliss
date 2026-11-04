#!/bin/sh
# ghidra-analyze.sh <target> [maxFunctions]
# Headless Ghidra analysis: import a binary, run analyzers, dump functions.
GH=__GHIDRA_ROOT__
ROOT=__REA_ROOT__

# Ghidra refuses to start without a JDK (javac is required by LaunchSupport)
# and needs Java 21+. Prefer the JDK Altaris installed, then JAVA_HOME, then PATH.
if [ -x "$ROOT/jdk/bin/java" ]; then
  JAVA_HOME="$ROOT/jdk"
elif [ -z "$JAVA_HOME" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  JAVA_HOME=""
  for c in /usr/lib/jvm/java-21-openjdk-arm64 /usr/lib/jvm/java-21-openjdk \
           /usr/lib/jvm/java-25-openjdk-arm64 /usr/lib/jvm/java-17-openjdk-arm64 \
           /usr/lib/jvm/java-17-openjdk; do
    [ -x "$c/bin/java" ] && JAVA_HOME="$c" && break
  done
fi
[ -n "$JAVA_HOME" ] && export JAVA_HOME
export _JAVA_OPTIONS="-Djava.awt.headless=true"

# ---------------------------------------------------------------- the heap
#
# This is the part that was wrong, and it is why the whole phone froze.
#
# The script used to export JAVA_OPTS="-Xmx…" and assume Ghidra honoured it.
# It does not. Ghidra's launcher (support/analyzeHeadless -> launch.sh) builds
# the JVM arguments itself and reads only its own variables:
#
#   GHIDRA_HEADLESS_MAXMEM   the -Xmx value, "2G" style
#   GHIDRA_HEADLESS_JAVA_OPTIONS   extra VM arguments
#
# JAVA_OPTS is not in that list. So the "ceiling" was never applied, the JVM
# fell back to its default (a quarter of *physical* RAM), and on an 8 GB phone
# that is a 2 GB+ heap the OS had already committed elsewhere. The kernel
# reclaimed, the OOM killer fired, and it took Altaris and the launcher with
# it — which is exactly the reported "heap 5-6 GB, whole device crashes".
#
# Size from MemAvailable, not MemTotal: MemTotal counts memory Android is
# already using. Take half of what is genuinely free, cap it at a third of
# total RAM and at 2 GB, and hold 768 MB back for the system.
MAXRAM_MB=0
AVAIL_MB=0
if [ -r /proc/meminfo ]; then
  MAXRAM_MB=$(awk '/MemTotal/ {printf "%d", $2/1024}' /proc/meminfo 2>/dev/null)
  AVAIL_MB=$(awk '/MemAvailable/ {printf "%d", $2/1024}' /proc/meminfo 2>/dev/null)
fi
case "$MAXRAM_MB" in ''|*[!0-9]*) MAXRAM_MB=4096 ;; esac
case "$AVAIL_MB" in ''|*[!0-9]*) AVAIL_MB=$((MAXRAM_MB / 2)) ;; esac

HEAP_MB=$((AVAIL_MB / 2))
CAP_MB=$((MAXRAM_MB / 3))
[ "$HEAP_MB" -gt "$CAP_MB" ] && HEAP_MB=$CAP_MB

RESERVE_MB=768
if [ "$AVAIL_MB" -lt $((HEAP_MB + RESERVE_MB)) ]; then
  HEAP_MB=$((AVAIL_MB - RESERVE_MB))
fi
if [ "$HEAP_MB" -lt 512 ]; then
  echo "ERROR: not enough free memory for Ghidra."
  echo "Only ${AVAIL_MB} MB is available and ${RESERVE_MB} MB must stay free for Android."
  echo "Close other apps and try again, or analyse a smaller file."
  exit 8
fi
[ "$HEAP_MB" -gt 2048 ] && HEAP_MB=2048

# The two variables Ghidra actually reads.
export GHIDRA_HEADLESS_MAXMEM="${HEAP_MB}M"
export GHIDRA_MAXMEM="${HEAP_MB}M"
# Also keep JAVA_OPTS: harmless for Ghidra, and it covers any wrapper that
# does read it. It is not the mechanism, though.
export JAVA_OPTS="-Xmx${HEAP_MB}m"
# Return heap to the OS promptly: a phone under pressure cares more about the
# pages going back than about a slightly cheaper GC cycle.
export GHIDRA_HEADLESS_JAVA_OPTIONS="-XX:+UseParallelGC -XX:MaxHeapFreeRatio=20 -XX:MinHeapFreeRatio=10 -Djava.awt.headless=true -Dcpu.core.limit=2"
echo "Ghidra heap: ${HEAP_MB} MB (available ${AVAIL_MB} MB, reserve ${RESERVE_MB} MB)"

# One JVM at a time. Two Ghidra imports on a phone is two heaps, which is how
# a single analysis becomes a device-wide OOM.
LOCK=/tmp/altaris-ghidra.lock
if command -v flock >/dev/null 2>&1; then
  exec 9>"$LOCK" 2>/dev/null || true
  if ! flock -n 9; then
    echo "ERROR: another Ghidra analysis is already running."
    echo "Wait for it to finish — two at once is more memory than the device has."
    exit 9
  fi
fi

TARGET="$1"
MAX="$2"
[ -z "$MAX" ] && MAX=300
if [ ! -e "$TARGET" ]; then
  echo "target not found: $TARGET"
  exit 4
fi
WORK="$(mktemp -d)"
OUT="$ROOT/ghidra-out"
mkdir -p "$OUT"
FUNCS="$OUT/functions.txt"
rm -f "$FUNCS"

SIZE=$(wc -c < "$TARGET" 2>/dev/null | tr -d ' ')
case "$SIZE" in ''|*[!0-9]*) SIZE=0 ;; esac
SIZE_MB=$((SIZE / 1048576))
echo "target: $TARGET (${SIZE_MB} MB)"

# Refuse a target that cannot fit, before the JVM starts.
#
# The import roughly doubles the file in memory, plus the project. If the file
# is larger than half the heap there is no point trying: it would spend twenty
# minutes thrashing and then die, taking the device with it.
if [ "$SIZE_MB" -gt 0 ] && [ $((SIZE_MB * 2)) -gt "$HEAP_MB" ]; then
  echo "ERROR: $TARGET is ${SIZE_MB} MB and the heap is ${HEAP_MB} MB."
  echo "Ghidra needs roughly twice the file size. Free memory and try again,"
  echo "or extract the specific library you need from an archive first."
  exit 7
fi

# Low-memory mode by default.
#
# The pre-script turns off Decompiler Parameter ID and the other analyzers whose
# working set is many times the binary. That single analyzer is what took the
# heap to its ceiling. Callers can ask for a full analysis, but it is opt-in
# because it is the mode that hurts.
MODE="${GHIDRA_ANALYSIS:-low}"

"$GH/support/analyzeHeadless" "$WORK" altaris-proj \
  -import "$TARGET" \
  -preScript "$ROOT/scripts/LowMemoryAnalysis.java" "$MODE" \
  -postScript "$ROOT/scripts/DumpInfo.java" "$FUNCS" \
  -scriptPath "$ROOT/scripts" \
  -analysisTimeoutPerFile 900
RC=$?

CNT=0
[ -f "$FUNCS" ] && CNT=$(wc -l < "$FUNCS")
echo "Ghidra analysis complete (exit $RC): $CNT functions in $TARGET"
echo "--- first $MAX functions (name<TAB>address) ---"
[ -f "$FUNCS" ] && head -n "$MAX" "$FUNCS"
rm -rf "$WORK"
[ "$RC" -eq 0 ] || exit "$RC"
