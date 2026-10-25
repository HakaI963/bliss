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
"$GH/support/analyzeHeadless" "$WORK" altaris-proj -import "$TARGET" -postScript "$ROOT/scripts/DumpInfo.java" "$FUNCS" -scriptPath "$ROOT/scripts" -analysisTimeoutPerFile 900
RC=$?
CNT=0
[ -f "$FUNCS" ] && CNT=$(wc -l < "$FUNCS")
echo "Ghidra analysis complete (exit $RC): $CNT functions in $TARGET"
echo "--- first $MAX functions (name<TAB>address) ---"
[ -f "$FUNCS" ] && head -n "$MAX" "$FUNCS"
rm -rf "$WORK"
[ "$RC" -eq 0 ] || exit "$RC"

