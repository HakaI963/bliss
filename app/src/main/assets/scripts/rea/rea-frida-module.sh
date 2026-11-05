#!/bin/sh
# Ensure the Node `frida` module is importable from REA's own tree.
#
#   rea-frida-module.sh <rea-base> [--diagnose]
#
# REA imports `frida` for every hook tool — start_frida_session,
# list_frida_processes, resume/close session and friends. REA declares it as an
# *optional* dependency, and npm silently skips optional dependencies when any
# of these is true:
#
#   - `npm config get omit` contains "optional" (some distro npm profiles);
#   - the install script fails (it downloads a prebuild, then falls back to
#     compiling against libfrida-core, which needs a toolchain);
#   - the platform/arch has no prebuild and the compile also fails.
#
# In all three cases npm exits 0, the module is absent, and REA later answers
# `capability_unavailable` with no hint that a module is missing. That silent
# skip is the bug this script exists to undo: it installs the module
# explicitly, with optional dependencies forced on, verifies the import from
# the directory Node actually resolves from, and falls back to a source build.
#
# With --diagnose it prints the environment and the import error instead of
# installing, so a failure can be explained rather than guessed at.
set +e

BASE="$1"
MODE="$2"
if [ -z "$BASE" ]; then
  echo "usage: rea-frida-module.sh <rea-base> [--diagnose]"
  exit 2
fi

NODE="$BASE/node/bin/node"
NPM="$BASE/node/bin/npm"
[ -x "$NODE" ] || NODE=$(command -v node 2>/dev/null)
[ -x "$NPM" ] || NPM=$(command -v npm 2>/dev/null)

if [ ! -x "$NODE" ]; then
  echo "ERROR: no node available, so the frida binding cannot be checked"
  exit 3
fi

AGENT_DIR="$BASE/npm/lib/node_modules/rea-agents"
if [ ! -d "$AGENT_DIR" ]; then
  echo "ERROR: rea-agents is not installed in $AGENT_DIR"
  exit 4
fi

# The version REA itself declares. Prefer the optional range so the binding
# matches what the server expects rather than whatever is newest today.
declared_range() {
  "$NODE" -e "
    try {
      const p = require('$AGENT_DIR/package.json');
      const d = p.dependencies || {}, o = p.optionalDependencies || {},
            pe = p.peerDependencies || {};
      console.log(o.frida || d.frida || pe.frida || '');
    } catch (e) { console.log(''); }
  " 2>/dev/null | tail -n 1
}

# Import from REA's own directory: that is where Node resolves it from.
importable() {
  ( cd "$AGENT_DIR" && "$NODE" --input-type=module -e "import('frida')" ) >/dev/null 2>&1
}

# The full error, for when the import fails and the reason matters.
import_error() {
  ( cd "$AGENT_DIR" && "$NODE" --input-type=module -e "import('frida')" ) 2>&1 | tail -n 12
}

installed_version() {
  "$NODE" -p "try{require('$AGENT_DIR/node_modules/frida/package.json').version}catch(e){''}" 2>/dev/null \
    || true
}

RANGE=$(declared_range)
[ -n "$RANGE" ] || RANGE=latest

if [ "$MODE" = "--diagnose" ]; then
  echo "node: $("$NODE" -v 2>&1)"
  echo "npm: $("$NPM" -v 2>&1)"
  echo "platform: $("$NODE" -p "process.platform + ' ' + process.arch + ' node ' + process.versions.node" 2>&1)"
  echo "arch: $(uname -m)"
  echo "rea-agents: $AGENT_DIR"
  echo "declared frida range: $RANGE"
  echo "npm omit: $("$NPM" config get omit 2>&1)"
  echo "npm optional: $("$NPM" config get optional 2>&1)"
  echo "module directory: $([ -d "$AGENT_DIR/node_modules/frida" ] && echo present || echo absent)"
  echo "installed version: $(installed_version)"
  if importable; then
    echo "import: OK"
  else
    echo "import: FAILED"
    import_error | while IFS= read -r line; do
      [ -n "$line" ] && echo "  $line"
    done
  fi
  exit 0
fi

if importable; then
  echo "frida binding already imports (version $(installed_version))"
  exit 0
fi

if [ -d "$AGENT_DIR/node_modules/frida" ]; then
  # Present but broken: a partial install, or a prebuild that does not match
  # this Node ABI. Remove it so npm cannot decide it is already satisfied.
  echo "the frida module is present but does not import; reinstalling it"
  rm -rf "$AGENT_DIR/node_modules/frida" 2>/dev/null
fi

echo "installing the frida binding (rea-agents declares $RANGE)"

NPM_LOG=$(mktemp 2>/dev/null || echo /tmp/altaris-frida-npm.log)

# `--include=optional` is the point of this call. Without it, a machine whose
# npm config has `omit=optional` installs nothing and exits 0.
#
# `--force` matters more than it looks. npm runs a package's install script only
# when it actually (re)installs the package; if the directory is already there
# it skips the script and still reports success. Since the *script* is what
# fetches the native binding, a tree left with a half-installed module never
# repairs itself. Forcing the reinstall guarantees the script runs.
install_once() {
  ( cd "$AGENT_DIR" && \
    "$NPM" install "frida@$RANGE" \
      --include=optional --force --no-save --no-audit --no-fund --loglevel=error ) \
    >>"$NPM_LOG" 2>&1
}

# The binding is what `import('frida')` actually needs. npm can exit 0 with the
# package unpacked and no `.node` file at all — verified on linux-arm64 — so the
# file's presence is checked separately from the package directory.
binding_present() {
  find "$AGENT_DIR/node_modules/frida" -name "frida_binding.node" 2>/dev/null | grep -q .
}

# Run the package's own prebuild step. This is the part that silently fails
# inside `npm install`: prebuild-install downloads the N-API binary from the
# frida release. Running it directly surfaces the real error.
run_prebuild() {
  [ -d "$AGENT_DIR/node_modules/frida" ] || return 1
  ( cd "$AGENT_DIR/node_modules/frida" && "$NPM" exec --yes -- prebuild-install ) \
    >>"$NPM_LOG" 2>&1
}

if install_once && binding_present && importable; then
  rm -f "$NPM_LOG" 2>/dev/null
  echo "frida binding ok (version $(installed_version))"
  exit 0
fi

# The package unpacked but the native binary is missing. Run the prebuild step
# ourselves: npm swallowed its failure, which is the whole reason a tree can
# look installed and still fail at `import('frida')`.
if run_prebuild && binding_present && importable; then
  rm -f "$NPM_LOG" 2>/dev/null
  echo "frida binding ok after fetching the prebuild (version $(installed_version))"
  exit 0
fi

# The prebuild download failed. The package's own install script then tries
# `make`, which needs a compiler and the frida-core headers. On a minimal
# rootfs neither exists, so provide them once and retry — but only if a package
# manager is there to do it with.
echo "the prebuild did not work; trying a source build"
ALTARIS_PKG_SH_DIR=$(dirname "$0")
if [ -r "$ALTARIS_PKG_SH_DIR/pkg-lock.sh" ]; then
  . "$ALTARIS_PKG_SH_DIR/pkg-lock.sh"
elif [ -r /opt/altaris/rea/scripts/pkg-lock.sh ]; then
  . /opt/altaris/rea/scripts/pkg-lock.sh
fi
if ! command -v pkg_run >/dev/null 2>&1; then
  pkg_run() { "$@"; }
fi

toolchain() {
  if command -v apt-get >/dev/null 2>&1; then
    pkg_run apt-get update -qq >/dev/null 2>&1
    pkg_run apt-get install -y --no-install-recommends \
      build-essential python3 python3-setuptools python3-wheel pkg-config \
      ca-certificates >/dev/null 2>&1
  elif command -v dnf >/dev/null 2>&1; then
    pkg_run dnf install -y gcc gcc-c++ make python3 python3-setuptools \
      python3-wheel pkgconf-pkg-config ca-certificates >/dev/null 2>&1
  elif command -v apk >/dev/null 2>&1; then
    pkg_run apk add --no-cache build-base python3 py3-setuptools py3-wheel \
      pkgconf ca-certificates >/dev/null 2>&1
  elif command -v pacman >/dev/null 2>&1; then
    pkg_run pacman -Sy --noconfirm --needed base-devel python python-setuptools \
      python-wheel pkgconf ca-certificates >/dev/null 2>&1
  fi
}
toolchain

if install_once && run_prebuild && binding_present && importable; then
  rm -f "$NPM_LOG" 2>/dev/null
  echo "frida binding ok after a source build (version $(installed_version))"
  exit 0
fi

# Nothing worked. Say exactly what is wrong, in terms the caller can act on.
echo "ERROR: the frida Node binding could not be installed"
echo "  expected: frida@$RANGE"
echo "  node: $("$NODE" -v 2>&1)"
echo "  platform: $("$NODE" -p "process.platform + ' ' + process.arch" 2>&1)"
echo "  arch: $(uname -m)"
echo "  npm omit: $("$NPM" config get omit 2>&1)"
echo "  module directory: $([ -d "$AGENT_DIR/node_modules/frida" ] && echo present || echo absent)"
echo "  native binding: $(binding_present && echo present || echo absent)"
if [ -s "$NPM_LOG" ]; then
  echo "  npm said:"
  tail -n 10 "$NPM_LOG" 2>/dev/null | while IFS= read -r line; do
    [ -n "$line" ] && echo "    $line"
  done
fi
echo "  import error:"
import_error | while IFS= read -r line; do
  [ -n "$line" ] && echo "    $line"
done
rm -f "$NPM_LOG" 2>/dev/null
echo "REA's static tools are unaffected; only the hook tools need this."
echo "Clones can still be instrumented through Frida Gadget (altaris_gadget_prepare)."
exit 1
