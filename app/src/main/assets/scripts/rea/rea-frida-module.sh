#!/bin/sh
# Ensure the Node `frida` module is importable from REA's own tree.
#
#   rea-frida-module.sh <rea-base>
#
# REA imports `frida` for every hook tool — start_frida_session,
# list_frida_processes, resume/close session and friends — but npm does not
# always lay it down, and when it is missing the provider fails with
# capability_unavailable and no hint that a module is absent.
#
# This is its own script because an installed REA core is never re-run: the
# installer skips it, so a tree created before the module was part of the core
# install would never gain it. This one is safe to run any number of times —
# it exits immediately when the module imports.
set +e

BASE="$1"
if [ -z "$BASE" ]; then
  echo "usage: rea-frida-module.sh <rea-base>"
  exit 2
fi

NODE="$BASE/node/bin/node"
NPM="$BASE/node/bin/npm"
[ -x "$NODE" ] || NODE=$(command -v node 2>/dev/null)
[ -x "$NPM" ] || NPM=$(command -v npm 2>/dev/null)
if [ ! -x "$NODE" ]; then
  echo "warning: no node available, so the frida module could not be checked"
  exit 0
fi

AGENT_DIR="$BASE/npm/lib/node_modules/rea-agents"
[ -d "$AGENT_DIR" ] || exit 0

importable() {
  # Import from REA's own directory: that is where Node resolves it from.
  ( cd "$AGENT_DIR" && "$NODE" --input-type=module -e "import('frida')" ) >/dev/null 2>&1
}

if [ -d "$BASE/npm/lib/node_modules/frida" ]; then
  if importable; then
    exit 0
  fi
  # Present but broken — a partial install. Fall through and replace it.
  echo "the frida module is present but does not import; reinstalling it"
fi

# Prefer the range REA itself declares, so the module matches what the server
# expects rather than whatever is newest today.
RANGE=$("$NODE" -e "
  try {
    const p = require('$AGENT_DIR/package.json');
    const d = p.dependencies || {}, o = p.optionalDependencies || {}, pe = p.peerDependencies || {};
    console.log(o.frida || d.frida || pe.frida || '');
  } catch (e) { console.log(''); }
" 2>/dev/null | tail -n 1)
[ -n "$RANGE" ] || RANGE=latest

echo "installing the frida module (rea-agents declares $RANGE)"
if ! "$NPM" install --prefix "$BASE/npm" "frida@$RANGE" \
    --no-audit --no-fund --loglevel=error; then
  echo "warning: the frida module did not install; REA's runtime-hook tools will report capability_unavailable"
  echo "warning: nothing else is affected — static analysis tools still work"
  exit 1
fi

if importable; then
  echo "frida module ok"
else
  echo "warning: the frida module installed but does not import; REA's runtime-hook tools will report capability_unavailable"
  exit 1
fi
