#!/bin/sh
# ghidra-analyze.sh <target> [maxFunctions]
# Headless Ghidra analysis: import a binary, run default analyzers, dump functions.
GH=__GHIDRA_ROOT__
ROOT=__REA_ROOT__
TARGET="$1"
MAX="$2"
[ -z "$MAX" ] && MAX=300
WORK="$(mktemp -d)"
OUT="$ROOT/ghidra-out"
mkdir -p "$OUT"
FUNCS="$OUT/functions.txt"
rm -f "$FUNCS"
"$GH/support/analyzeHeadless" "$WORK" altaris-proj -import "$TARGET" -postScript "$ROOT/scripts/DumpInfo.java" "$FUNCS" -scriptPath "$ROOT/scripts" -analysisTimeoutPerFile 900 >/dev/null 2>&1
CNT=0
[ -f "$FUNCS" ] && CNT=$(wc -l < "$FUNCS")
echo "Ghidra analysis complete: $CNT functions in $TARGET"
echo "--- first $MAX functions (name<TAB>address) ---"
[ -f "$FUNCS" ] && head -n "$MAX" "$FUNCS"
rm -rf "$WORK"

