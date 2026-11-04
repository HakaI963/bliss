#!/bin/sh
# ghidra-analyze.sh <target> [maxFunctions]
# Headless Ghidra analysis: import a binary, run default analyzers, dump functions.
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

# Size the heap from what is actually AVAILABLE, not from total RAM.
#
# This is what froze the device. The heap was half of MemTotal — on an 8 GB
# phone that is a 3 GB ceiling, promised to a JVM while Android itself is
# already using most of that memory. The kernel then had to reclaim and kill,
# and the OOM killer does not stop at our process: Altaris and the launcher go
# with it.
#
# MemAvailable is the kernel's own estimate of what can be handed out without
# swapping. Take a conservative share of that, and leave a hard floor of free
# memory for the rest of the system — a debugger must never be the reason the
# phone dies.
MAXRAM_MB=0
AVAIL_MB=0
if [ -r /proc/meminfo ]; then
  MAXRAM_MB=$(awk '/MemTotal/ {printf "%d", $2/1024}' /proc/meminfo 2>/dev/null)
  AVAIL_MB=$(awk '/MemAvailable/ {printf "%d", $2/1024}' /proc/meminfo 2>/dev/null)
fi
case "$MAXRAM_MB" in ''|*[!0-9]*) MAXRAM_MB=4096 ;; esac
case "$AVAIL_MB" in ''|*[!0-9]*) AVAIL_MB=$((MAXRAM_MB / 2)) ;; esac

# Half of what is available, never more than a third of total RAM.
HEAP_MB=$((AVAIL_MB / 2))
CAP_MB=$((MAXRAM_MB / 3))
[ "$HEAP_MB" -gt "$CAP_MB" ] && HEAP_MB=$CAP_MB

# Leave at least 768 MB free for Android. If that is not possible, refuse
# rather than risk the device.
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
# A hard ceiling for a phone. More than this and the JVM's own GC work costs
# more than it buys.
[ "$HEAP_MB" -gt 2048 ] && HEAP_MB=2048

# ParallelGC over G1: less bookkeeping, and it returns memory to the system
# sooner — which matters when the OS is under pressure.
export JAVA_OPTS="-Xmx${HEAP_MB}m -XX:+UseParallelGC -XX:MaxHeapFreeRatio=20 -XX:MinHeapFreeRatio=10"
echo "Ghidra heap: ${HEAP_MB} MB (available ${AVAIL_MB} MB, reserve ${RESERVE_MB} MB)"

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

# Low-memory mode by default.
#
# The pre-script turns off Decompiler Parameter ID and the other analyzers whose
# working set is many times the binary. That single analyzer is what took the
# heap to its ceiling and got the app — and the device — killed. Callers can ask
# for a full analysis, but it is opt-in because it is the mode that hurts.
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

