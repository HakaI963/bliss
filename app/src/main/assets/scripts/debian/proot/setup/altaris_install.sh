#!/bin/bash
# altaris_install.sh — install/configure proot-distro guest from local rootfs archive
# One-click (scripts page): no args → debian + local setup_debian_family.sh
# Onboarding: altaris_install.sh debian <base64 setup script>
# Paths: TermuxHostPaths via altaris-host.env (SSOT), never stock com.termux.

set -u

DISTRO="${1:-debian}"
SETUP_B64="${2:-}"

# Base distro id for the rootfs selection below. Named installs pass a
# container name as $1 and the base distro via ALTARIS_DISTRO_ID (Kotlin).
DISTRO_TYPE="${ALTARIS_DISTRO_ID:-$DISTRO}"

# Per-distro pinned rootfs (packaged as assets/rootfs/, deployed to $HOME)
# Env ALTARIS_ROOTFS_* overrides always win (Kotlin HostScriptDeployer / onboarding).
case "$DISTRO_TYPE" in
    alpine)
        ROOTFS_NAME="${ALTARIS_ROOTFS_NAME:-alpine_3.24_rootfs.tar.gz}"
        ROOTFS_URL="${ALTARIS_ROOTFS_URL:-https://github.com/HakaI963/bliss/releases/download/rootfs/alpine_3.24_rootfs.tar.gz}"
        ROOTFS_SHA256="${ALTARIS_ROOTFS_SHA256:-f55a90f69052c5bd6f92cb09a8f47065970830b194c917a006fb94028e721259}"
        FAMILY_SCRIPT_NAME="setup_alpine_family.sh"
        ;;
    fedora)
        ROOTFS_NAME="${ALTARIS_ROOTFS_NAME:-fedora_44_rootfs.tar.xz}"
        ROOTFS_URL="${ALTARIS_ROOTFS_URL:-https://github.com/HakaI963/bliss/releases/download/rootfs/fedora_44_rootfs.tar.xz}"
        ROOTFS_SHA256="${ALTARIS_ROOTFS_SHA256:-2d89fe437973e4596d56bf096f71c182d273942a307e7e1e51462dba43db1bd4}"
        FAMILY_SCRIPT_NAME="setup_fedora_family.sh"
        ;;
    void)
        ROOTFS_NAME="${ALTARIS_ROOTFS_NAME:-void_20250202_rootfs.tar.xz}"
        ROOTFS_URL="${ALTARIS_ROOTFS_URL:-https://github.com/HakaI963/bliss/releases/download/rootfs/void_20250202_rootfs.tar.xz}"
        ROOTFS_SHA256="${ALTARIS_ROOTFS_SHA256:-01a30f17ae06d4d5b322cd579ca971bc479e02cc284ec1e5a4255bea6bac3ce6}"
        FAMILY_SCRIPT_NAME="setup_void_family.sh"
        ;;
    opensuse)
        ROOTFS_NAME="${ALTARIS_ROOTFS_NAME:-opensuse_tumbleweed_rootfs.tar.xz}"
        ROOTFS_URL="${ALTARIS_ROOTFS_URL:-https://github.com/HakaI963/bliss/releases/download/rootfs/opensuse_tumbleweed_rootfs.tar.xz}"
        ROOTFS_SHA256="${ALTARIS_ROOTFS_SHA256:-bdcb8522a9672cfa513081313b2788f8844340e800918d16a2154e4ed785a12a}"
        FAMILY_SCRIPT_NAME="setup_opensuse_family.sh"
        ;;
    deepin)
        ROOTFS_NAME="${ALTARIS_ROOTFS_NAME:-deepin_25_rootfs.tar.xz}"
        ROOTFS_URL="${ALTARIS_ROOTFS_URL:-https://github.com/HakaI963/bliss/releases/download/rootfs/deepin_25_rootfs.tar.xz}"
        ROOTFS_SHA256="${ALTARIS_ROOTFS_SHA256:-2c7abfe859db36249459251d0b29f853e9ffb79cd1b42c7661e997ba99193698}"
        FAMILY_SCRIPT_NAME="setup_deepin_family.sh"
        ;;
    chimera)
        ROOTFS_NAME="${ALTARIS_ROOTFS_NAME:-chimera_20251220_rootfs.tar.xz}"
        ROOTFS_URL="${ALTARIS_ROOTFS_URL:-https://github.com/HakaI963/bliss/releases/download/rootfs/chimera_20251220_rootfs.tar.xz}"
        ROOTFS_SHA256="${ALTARIS_ROOTFS_SHA256:-0900e3f2554faaf005c14a6850596dadae1e7d8a996138180eebb0b4694a4a6c}"
        FAMILY_SCRIPT_NAME="setup_chimera_family.sh"
        ;;
    manjaro)
        ROOTFS_NAME="${ALTARIS_ROOTFS_NAME:-manjaro_arm_rootfs.tar.xz}"
        ROOTFS_URL="${ALTARIS_ROOTFS_URL:-https://github.com/HakaI963/bliss/releases/download/rootfs/manjaro_arm_rootfs.tar.xz}"
        ROOTFS_SHA256="${ALTARIS_ROOTFS_SHA256:-b7339bcc289e8bbb40d1ffdc6ece4404865383d14d4b7f0fb83aa81e01720156}"
        FAMILY_SCRIPT_NAME="setup_manjaro_family.sh"
        ;;
    ubuntu)
        ROOTFS_NAME="${ALTARIS_ROOTFS_NAME:-ubuntu_26.04_rootfs.tar.xz}"
        ROOTFS_URL="${ALTARIS_ROOTFS_URL:-https://github.com/HakaI963/bliss/releases/download/rootfs/ubuntu_26.04_rootfs.tar.xz}"
        ROOTFS_SHA256="${ALTARIS_ROOTFS_SHA256:-e648a5302dd273c476e5658e652f88d1e66ece69b487431521c5caef4b960efc}"
        FAMILY_SCRIPT_NAME="setup_ubuntu_family.sh"
        ;;
    kali)
        ROOTFS_NAME="${ALTARIS_ROOTFS_NAME:-kali_2026_2_rootfs.tar.xz}"
        ROOTFS_URL="${ALTARIS_ROOTFS_URL:-https://github.com/HakaI963/bliss/releases/download/rootfs/kali_2026_2_rootfs.tar.xz}"
        ROOTFS_SHA256="${ALTARIS_ROOTFS_SHA256:-01c48a29ebb543954ef200e766076a143cf42744760d7ccdc31683a19f670689}"
        FAMILY_SCRIPT_NAME="setup_kali_family.sh"
        ;;
    parrot)
        ROOTFS_NAME="${ALTARIS_ROOTFS_NAME:-parrot_7.2_rootfs.tar.xz}"
        ROOTFS_URL="${ALTARIS_ROOTFS_URL:-https://github.com/HakaI963/bliss/releases/download/rootfs/parrot_7.2_rootfs.tar.xz}"
        ROOTFS_SHA256="${ALTARIS_ROOTFS_SHA256:-49f4c2899ef9574cc3b0d9aaa6eaff38c4b32a9ac1abea2faec73cfbaf8094d4}"
        FAMILY_SCRIPT_NAME="setup_parrot_family.sh"
        ;;
    archlinux)
        ROOTFS_NAME="${ALTARIS_ROOTFS_NAME:-archlinux_arm_rootfs.tar.xz}"
        ROOTFS_URL="${ALTARIS_ROOTFS_URL:-https://github.com/HakaI963/bliss/releases/download/rootfs/archlinux_arm_rootfs.tar.xz}"
        ROOTFS_SHA256="${ALTARIS_ROOTFS_SHA256:-40209ef6318d3aad732299d46ce224c6a0ecded80b6f8091f5e38b40fa031d75}"
        FAMILY_SCRIPT_NAME="setup_arch_family.sh"
        ;;
    rocky)
        ROOTFS_NAME="${ALTARIS_ROOTFS_NAME:-rocky_9_aarch64_plain.tar.xz}"
        ROOTFS_URL="${ALTARIS_ROOTFS_URL:-https://github.com/HakaI963/bliss/releases/download/rootfs/rocky_9_aarch64_plain.tar.xz}"
        ROOTFS_SHA256="${ALTARIS_ROOTFS_SHA256:-7af07eade178c80ad3d322940ec6064c566dd34a8cb7e931fcbf4a5e44c18611}"
        FAMILY_SCRIPT_NAME="setup_rpm_family.sh"
        ;;
    centos_stream)
        ROOTFS_NAME="${ALTARIS_ROOTFS_NAME:-centos_stream_9_aarch64_plain.tar.xz}"
        ROOTFS_URL="${ALTARIS_ROOTFS_URL:-https://github.com/HakaI963/bliss/releases/download/rootfs/centos_stream_9_aarch64_plain.tar.xz}"
        ROOTFS_SHA256="${ALTARIS_ROOTFS_SHA256:-d9ba19e6647c99d0c4d1424dc4a7717f350943c485116423445a64b1faa65f9a}"
        FAMILY_SCRIPT_NAME="setup_rpm_family.sh"
        ;;
    *)
        ROOTFS_NAME="${ALTARIS_ROOTFS_NAME:-debian_13_rootfs.tar.xz}"
        ROOTFS_URL="${ALTARIS_ROOTFS_URL:-https://github.com/HakaI963/bliss/releases/download/rootfs/debian_13_rootfs.tar.xz}"
        ROOTFS_SHA256="${ALTARIS_ROOTFS_SHA256:-13e29f6099c3b805e84694507ede460c03886ffb364c03317272691cf84e6803}"
        FAMILY_SCRIPT_NAME="setup_debian_family.sh"
        ;;
esac

# Resolve script directory (deployed to $HOME or $HOME/scripts)
SCRIPT_DIR="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || SCRIPT_DIR=""
DEFAULT_SETUP=""
for candidate in \
    "${SCRIPT_DIR}/${FAMILY_SCRIPT_NAME}" \
    "${HOME:-}/${FAMILY_SCRIPT_NAME}" \
    "${HOME:-}/scripts/${FAMILY_SCRIPT_NAME}"
do
    if [ -n "$candidate" ] && [ -f "$candidate" ]; then
        DEFAULT_SETUP="$candidate"
        break
    fi
done

PKG="${TERMUX_APP__PACKAGE_NAME:-com.ivarna.fluxlinux}"
PREFIX_DEFAULT="${TERMUX__PREFIX:-/data/data/${PKG}/files/usr}"

# SSOT env from Kotlin TermuxHostPaths (altaris-host.env; legacy name fallback)
_HOST_ENV="${PREFIX_DEFAULT}/etc/altaris-host.env"
[ -r "$_HOST_ENV" ] || _HOST_ENV="${PREFIX_DEFAULT}/etc/fluxlinux-host.env"
if [ -r "$_HOST_ENV" ]; then
    # shellcheck source=/dev/null
    . "$_HOST_ENV"
fi

export TERMUX_APP__PACKAGE_NAME="${TERMUX_APP__PACKAGE_NAME:-$PKG}"
export TERMUX__PREFIX="${TERMUX__PREFIX:-/data/data/${TERMUX_APP__PACKAGE_NAME}/files/usr}"
export TERMUX__HOME="${TERMUX__HOME:-/data/data/${TERMUX_APP__PACKAGE_NAME}/files/home}"
export PREFIX="${PREFIX:-$TERMUX__PREFIX}"
export HOME="${HOME:-$TERMUX__HOME}"
export TMPDIR="${TMPDIR:-$PREFIX/tmp}"
export PROOT_TMP_DIR="${PROOT_TMP_DIR:-$(dirname "$PREFIX")/proot-tmp}"
export LD_LIBRARY_PATH="${LD_LIBRARY_PATH:-$PREFIX/lib}"
export PATH="$PREFIX/bin:$PREFIX/bin/applets:/system/bin:/system/xbin${PATH:+:$PATH}"

# Snapshot W^X proot paths from the ProcessBuilder / host env before profile can clobber.
# Without PD_PROOT_BIN + PROOT_LOADER (jniLibs), proot cannot exec guest bash on targetSdk 36.
_SAVED_PD_PROOT_BIN="${PD_PROOT_BIN:-}"
_SAVED_PROOT_LOADER="${PROOT_LOADER:-}"
_SAVED_PROOT_LOADER_32="${PROOT_LOADER_32:-}"

# Load rewritten host profile (paths must match PREFIX)
if [ -r "$PREFIX/etc/profile" ]; then
    # shellcheck source=/dev/null
    . "$PREFIX/etc/profile" || true
    export TERMUX_APP__PACKAGE_NAME="${TERMUX_APP__PACKAGE_NAME}"
    export TERMUX__PREFIX="$PREFIX"
    export TERMUX__HOME="$HOME"
    export PREFIX
    export HOME
    export TMPDIR="$PREFIX/tmp"
    export PROOT_TMP_DIR="$(dirname "$PREFIX")/proot-tmp"
    export LD_LIBRARY_PATH="${LD_LIBRARY_PATH:-$PREFIX/lib}"
    export PATH="$PREFIX/bin:$PREFIX/bin/applets:/system/bin:/system/xbin${PATH:+:$PATH}"
fi

# Re-pin W^X proot binaries after profile (profile must not win over jniLibs).
if [ -n "$_SAVED_PD_PROOT_BIN" ]; then
    export PD_PROOT_BIN="$_SAVED_PD_PROOT_BIN"
fi
if [ -n "$_SAVED_PROOT_LOADER" ]; then
    export PROOT_LOADER="$_SAVED_PROOT_LOADER"
fi
if [ -n "$_SAVED_PROOT_LOADER_32" ]; then
    export PROOT_LOADER_32="$_SAVED_PROOT_LOADER_32"
fi

mkdir -p "$TMPDIR" "$PROOT_TMP_DIR" 2>/dev/null || true
chmod 1777 "$TMPDIR" 2>/dev/null || true
chmod 700 "$PROOT_TMP_DIR" 2>/dev/null || true

PYTHON="${PREFIX}/bin/python"
PROOT_DISTRO="${PREFIX}/bin/proot-distro"
if [ ! -x "$PYTHON" ]; then
    echo "Altaris: missing $PYTHON"
    exit 1
fi
if [ ! -f "$PROOT_DISTRO" ]; then
    echo "Altaris: missing $PROOT_DISTRO"
    exit 1
fi

# Fail early with a clear message if W^X proot paths are unset (host ProcessBuilder bug).
if [ -z "${PD_PROOT_BIN:-}" ] || [ ! -e "${PD_PROOT_BIN}" ]; then
    echo "Altaris: PD_PROOT_BIN missing or not found (${PD_PROOT_BIN:-unset})"
    echo "Altaris: Host env must set PD_PROOT_BIN to libproot.so under nativeLibraryDir."
    exit 1
fi
if [ -z "${PROOT_LOADER:-}" ] || [ ! -e "${PROOT_LOADER}" ]; then
    echo "Altaris: PROOT_LOADER missing or not found (${PROOT_LOADER:-unset})"
    echo "Altaris: Host env must set PROOT_LOADER to libloader.so under nativeLibraryDir."
    exit 1
fi

echo "Altaris: Debugging Environment:"
echo "HOME=$HOME"
echo "PREFIX=$PREFIX"
echo "TERMUX__HOME=$TERMUX__HOME"
echo "TERMUX__PREFIX=$TERMUX__PREFIX"
echo "TERMUX_APP__PACKAGE_NAME=$TERMUX_APP__PACKAGE_NAME"
echo "LD_LIBRARY_PATH=$LD_LIBRARY_PATH"
echo "PD_PROOT_BIN=$PD_PROOT_BIN"
echo "PROOT_LOADER=$PROOT_LOADER"
echo "PROOT_LOADER_32=${PROOT_LOADER_32:-}"
echo "TMPDIR=$TMPDIR"
echo "DISTRO=$DISTRO"
echo "----------------------------------------"

# Mirror fallback for GitHub release assets: ISPs commonly block the
# release-assets.githubusercontent.com redirect while github.com resolves.
root_download_urls() {
    echo "$1"
    case "$1" in
        https://github.com/*)
            echo "https://ghfast.top/$1"
            echo "https://gh-proxy.com/$1"
            echo "https://ghproxy.net/$1"
            ;;
    esac
}

# Resolve local rootfs archive for proot-distro install ./path --name <distro>
# Path must start with / ./ ../ or ~ so proot-distro treats it as a file, not registry.
resolve_rootfs_archive() {
    ROOTFS_ARCHIVE=""

    if [ -n "${ALTARIS_ROOTFS_PATH:-}" ] && [ -f "$ALTARIS_ROOTFS_PATH" ] && [ -s "$ALTARIS_ROOTFS_PATH" ]; then
        ROOTFS_ARCHIVE="$ALTARIS_ROOTFS_PATH"
        echo "Altaris: rootfs from ALTARIS_ROOTFS_PATH=$ROOTFS_ARCHIVE"
        return 0
    fi

    # Prefer absolute paths under HOME/PREFIX (app-deployed asset)
    for candidate in \
        "$HOME/$ROOTFS_NAME" \
        "$HOME/rootfs/$ROOTFS_NAME" \
        "$PREFIX/var/lib/proot-distro/cache/rootfs/$ROOTFS_NAME" \
        "/sdcard/Download/$ROOTFS_NAME" \
        "/sdcard/Download/rootfs.tar.xz" \
        "/sdcard/Download/rootfs.tar.gz" \
        "/storage/emulated/0/Download/$ROOTFS_NAME" \
        "/storage/emulated/0/Download/rootfs.tar.xz" \
        "/storage/emulated/0/Download/rootfs.tar.gz"
    do
        if [ -f "$candidate" ] && [ -s "$candidate" ]; then
            ROOTFS_ARCHIVE="$candidate"
            echo "Altaris: rootfs found: $ROOTFS_ARCHIVE"
            return 0
        fi
    done

    # Optional download into cache (escape hatch if asset not deployed)
    if [ "${ALTARIS_PD_INSTALL_MODE:-file}" = "registry" ]; then
        return 1
    fi

    CACHE_DIR="$PREFIX/var/lib/proot-distro/cache/rootfs"
    mkdir -p "$CACHE_DIR" 2>/dev/null || true
    DEST="$CACHE_DIR/$ROOTFS_NAME"
    echo "Altaris: rootfs not in app paths — downloading $ROOTFS_URL"
    for _url in $(root_download_urls "$ROOTFS_URL"); do
        if [ "$_url" != "$ROOTFS_URL" ]; then
            echo "Altaris: trying mirror: $_url"
        fi
        rm -f "$DEST.partial" 2>/dev/null || true
        if command -v curl >/dev/null 2>&1; then
            if curl -fL --retry 3 --retry-delay 2 -o "$DEST.partial" "$_url" \
                && [ -s "$DEST.partial" ] && mv -f "$DEST.partial" "$DEST"; then
                ROOTFS_ARCHIVE="$DEST"
                echo "Altaris: rootfs downloaded: $ROOTFS_ARCHIVE"
                return 0
            fi
            rm -f "$DEST.partial" 2>/dev/null || true
        elif command -v wget >/dev/null 2>&1; then
            if wget -O "$DEST.partial" "$_url" && [ -s "$DEST.partial" ] && mv -f "$DEST.partial" "$DEST"; then
                ROOTFS_ARCHIVE="$DEST"
                echo "Altaris: rootfs downloaded: $ROOTFS_ARCHIVE"
                return 0
            fi
            rm -f "$DEST.partial" 2>/dev/null || true
        fi
    done

    echo "Altaris: no rootfs archive found (expected $HOME/$ROOTFS_NAME from app assets)"
    return 1
}

verify_rootfs_sha() {
    _file="$1"
    [ -z "$ROOTFS_SHA256" ] && return 0
    if command -v sha256sum >/dev/null 2>&1; then
        _got="$(sha256sum "$_file" | awk '{print $1}')"
        if [ "$_got" != "$ROOTFS_SHA256" ]; then
            echo "Altaris: SHA256 mismatch for $_file"
            echo "  expected $ROOTFS_SHA256"
            echo "  got      $_got"
            return 1
        fi
        echo "Altaris: SHA256 OK"
    fi
    return 0
}

echo "Altaris: Installing $DISTRO..."

# Alpine minirootfs: bin/sh -> /bin/busybox (absolute). Host-side test -e follows
# the link into Android /bin/busybox (missing) and false-negatives. Prefer -L or
# real guest binaries (busybox/apk/bash).
rootfs_has_shell() {
    _r="$1"
    [ -d "$_r" ] || return 1
    [ -L "$_r/bin/sh" ] || [ -e "$_r/bin/sh" ] || \
        [ -x "$_r/bin/busybox" ] || [ -f "$_r/sbin/apk" ] || \
        [ -e "$_r/usr/bin/sh" ] || [ -f "$_r/usr/bin/apk" ] || \
        [ -x "$_r/usr/bin/bash" ] || [ -L "$_r/bin/ash" ]
}

# Customization (fonts/icons) can leave root-owned files in a proot tree
# (guest script ran under real root, or a rooted helper wrote the path).
# App-uid `rm -rf` then prints Permission denied and install dies at 0%.
wipe_proot_container() {
    _wipe_name="$1"
    _wipe_dir="$PREFIX/var/lib/proot-distro/containers/$_wipe_name"
    [ -e "$_wipe_dir" ] || return 0
    echo "Altaris: removing container $_wipe_dir"
    rm -rf "$_wipe_dir" 2>/dev/null || true
    if [ ! -e "$_wipe_dir" ]; then
        return 0
    fi
    echo "Altaris: leftover after unprivileged rm (root-owned files) — trying su"
    _wipe_q=$(printf "%s" "$_wipe_dir" | sed "s/'/'\\\\''/g")
    for _su in /system/bin/su /system/xbin/su su; do
        if [ -x "$_su" ] || command -v "$_su" >/dev/null 2>&1; then
            "$_su" -c "rm -rf '$_wipe_q'" </dev/null 2>/dev/null || true
            if [ ! -e "$_wipe_dir" ]; then
                echo "Altaris: privileged wipe ok"
                return 0
            fi
        fi
    done
    echo "Altaris: ERROR: cannot remove $_wipe_dir (root-owned leftovers)."
    echo "Altaris: From a root shell run: rm -rf $_wipe_dir"
    return 1
}

guest_version_id() {
    _gr="$1"
    if [ -r "$_gr/usr/lib/os-release" ]; then
        grep -E '^VERSION_ID=' "$_gr/usr/lib/os-release" 2>/dev/null | head -1 | tr -d '"' | cut -d= -f2
    elif [ -r "$_gr/etc/os-release" ]; then
        grep -E '^VERSION_ID=' "$_gr/etc/os-release" 2>/dev/null | head -1 | tr -d '"' | cut -d= -f2
    fi
}

if [ "$DISTRO" = "termux" ]; then
    echo "Altaris: Native Termux Mode"
    EXIT_CODE=0
else
    DISTRO_ROOTFS="$PREFIX/var/lib/proot-distro/containers/$DISTRO/rootfs"

    SKIP_EXTRACT=0
    if rootfs_has_shell "$DISTRO_ROOTFS"; then
        # Fedora pin moved 43 → 44. Re-extract when the guest is the old release.
        if [ "$DISTRO_TYPE" = "fedora" ]; then
            _have_ver=$(guest_version_id "$DISTRO_ROOTFS")
            if [ "$_have_ver" != "44" ]; then
                echo "Altaris: fedora guest is VERSION_ID=${_have_ver:-unknown} — replacing with 44"
            else
                SKIP_EXTRACT=1
            fi
        else
            SKIP_EXTRACT=1
        fi
    fi

    if [ "$SKIP_EXTRACT" -eq 1 ]; then
        echo "Altaris: $DISTRO already installed with valid rootfs. Skipping base installation."
        EXIT_CODE=0
    else
        if [ "${ALTARIS_PD_INSTALL_MODE:-file}" = "registry" ]; then
            echo "Altaris: ALTARIS_PD_INSTALL_MODE=registry — installing $DISTRO_TYPE from registry"
            if [ "$DISTRO" != "$DISTRO_TYPE" ]; then
                echo "Altaris: ERROR: named install requires file mode (unset ALTARIS_PD_INSTALL_MODE)"
                exit 1
            fi
            wipe_proot_container "$DISTRO" || exit 1
            "$PYTHON" "$PROOT_DISTRO" install "$DISTRO_TYPE"
            EXIT_CODE=$?
        else
            if ! resolve_rootfs_archive; then
                echo "Altaris: Install Failed — no local rootfs archive"
                exit 1
            fi
            # Ensure absolute path so proot-distro never treats name as registry image
            case "$ROOTFS_ARCHIVE" in
                /*|~*) ;;
                ./*|../*) ;;
                *) ROOTFS_ARCHIVE="$(cd "$(dirname "$ROOTFS_ARCHIVE")" && pwd)/$(basename "$ROOTFS_ARCHIVE")" ;;
            esac
            if ! verify_rootfs_sha "$ROOTFS_ARCHIVE"; then
                exit 1
            fi
            echo "Altaris: Installing $DISTRO from local archive..."
            echo "Altaris: install $ROOTFS_ARCHIVE --name $DISTRO"
            wipe_proot_container "$DISTRO" || exit 1
            "$PYTHON" "$PROOT_DISTRO" install "$ROOTFS_ARCHIVE" --name "$DISTRO"
            EXIT_CODE=$?
            if [ "$EXIT_CODE" -eq 0 ] && ! rootfs_has_shell "$DISTRO_ROOTFS"; then
                echo "Altaris: install reported OK but no shell/busybox under $DISTRO_ROOTFS"
                EXIT_CODE=1
            fi
        fi
    fi
fi

if [ "$EXIT_CODE" -ne 0 ]; then
    echo "Altaris: Install Failed with code $EXIT_CODE!"
    exit 1
fi

echo "Altaris: Install Successful!"

# openSUSE: keep a copy of libcurl-mini. Family setup must never replace it
# with full libcurl4+libldap (EVP_md2 vs OpenSSL 3.5.3 breaks zypper).
if [ "$DISTRO_TYPE" = "opensuse" ] && [ -n "${DISTRO_ROOTFS:-}" ]; then
    _curl=""
    for _c in \
        "$DISTRO_ROOTFS/usr/lib64/libcurl.so.4.8.0" \
        "$DISTRO_ROOTFS/usr/lib64/libcurl.so.4"
    do
        if [ -f "$_c" ] && [ ! -L "$_c" ]; then
            _curl="$_c"
            break
        fi
    done
    if [ -n "$_curl" ] && [ ! -f "${_curl}.altaris-mini" ]; then
        cp -f "$_curl" "${_curl}.altaris-mini" 2>/dev/null || true
        echo "Altaris: stashed libcurl-mini → ${_curl}.altaris-mini"
    fi
    # Stage EVP_md2 stub into guest /tmp (shared-tmp) and /usr/lib64.
    for _stub in \
        "${HOME:-}/libevp_md2.so" \
        "${SCRIPT_DIR}/libevp_md2.so" \
        "$TMPDIR/libevp_md2.so"
    do
        if [ -f "$_stub" ]; then
            cp -f "$_stub" "$TMPDIR/libevp_md2.so" 2>/dev/null || true
            mkdir -p "$DISTRO_ROOTFS/usr/lib64"
            cp -f "$_stub" "$DISTRO_ROOTFS/usr/lib64/libevp_md2.so" 2>/dev/null || true
            echo "Altaris: staged EVP_md2 stub"
            break
        fi
    done
fi

# Resolve setup script: base64 payload (onboarding) OR local setup_debian_family.sh (one-click)
# Write via $HOME first — $PREFIX/tmp may contain root-owned leftovers from
# earlier chroot/GUI sessions (sticky tmp + W^X-safe decode).
_decode_b64() {
    _in="$1"
    _out="$2"
    if [ -x /system/bin/base64 ]; then
        printf '%s' "$_in" | /system/bin/base64 -d > "$_out"
    elif command -v python >/dev/null 2>&1; then
        printf '%s' "$_in" | python -c 'import sys,base64; sys.stdout.buffer.write(base64.b64decode(sys.stdin.read()))' > "$_out"
    else
        printf '%s' "$_in" | base64 -d > "$_out"
    fi
}

mkdir -p "$HOME" "$TMPDIR" 2>/dev/null || true
chmod 1777 "$TMPDIR" 2>/dev/null || true
SETUP_HOST_PATH="$HOME/.altaris_setup_${DISTRO}.sh"
SETUP_GUEST_NAME="altaris_setup_${DISTRO}.sh"
rm -f "$SETUP_HOST_PATH" "$TMPDIR/flux_setup_temp.sh" "$TMPDIR/$SETUP_GUEST_NAME"
SETUP_MODE=""

if [ -n "$SETUP_B64" ] && [ "$SETUP_B64" != "null" ]; then
    echo "Altaris: Configuring from base64 payload..."
    if ! _decode_b64 "$SETUP_B64" "$SETUP_HOST_PATH"; then
        echo "Altaris: base64 decode failed"
        exit 1
    fi
    SETUP_MODE="b64"
elif [ -n "$DEFAULT_SETUP" ]; then
    echo "Altaris: Configuring from local setup: $DEFAULT_SETUP"
    : > "$SETUP_HOST_PATH"
    for _common in \
        "${HOME:-}/altaris_guest_common.sh" \
        "${SCRIPT_DIR}/altaris_guest_common.sh"
    do
        if [ -f "$_common" ]; then
            cat "$_common" >> "$SETUP_HOST_PATH"
            echo "" >> "$SETUP_HOST_PATH"
            break
        fi
    done
    cat "$DEFAULT_SETUP" >> "$SETUP_HOST_PATH" || {
        echo "Altaris: failed to copy $DEFAULT_SETUP"
        exit 1
    }
    SETUP_MODE="local"
else
    echo "Altaris: No setup payload and no ${FAMILY_SCRIPT_NAME} found — install only."
    SETUP_MODE=""
fi

if [ -n "$SETUP_MODE" ]; then
    chmod +x "$SETUP_HOST_PATH"

    if [ "$DISTRO" = "termux" ]; then
        bash "$SETUP_HOST_PATH"
        SETUP_EXIT=$?
    else
        # Ensure shared tmp exists and is writable before proot binds it as guest /tmp
        mkdir -p "$TMPDIR" "$PROOT_TMP_DIR" 2>/dev/null || true
        chmod 1777 "$TMPDIR" 2>/dev/null || true
        chmod 700 "$PROOT_TMP_DIR" 2>/dev/null || true
        # --shared-tmp: host $PREFIX/tmp → guest /tmp
        if ! cp -f "$SETUP_HOST_PATH" "$TMPDIR/$SETUP_GUEST_NAME"; then
            echo "Altaris: failed to stage setup script into $TMPDIR"
            exit 1
        fi
        chmod 755 "$TMPDIR/$SETUP_GUEST_NAME" 2>/dev/null || true
        # Alpine minirootfs has only ash until family installs bash — prefer sh then bash.
        echo "Altaris: Running setup inside proot (shared-tmp)…"
        echo "Altaris: PD_PROOT_BIN=$PD_PROOT_BIN PROOT_LOADER=$PROOT_LOADER"
        # proot-distro's login wrapper starts the guest with `env -i`, so
        # host-side ALTARIS_DESKTOPS/ALTARIS_DESKTOP would silently disappear and
        # every install would fall back to XFCE (the slow path). Re-export the
        # desktop contract inside the guest command.
        # Default xfce for the one-click path; onboarding always passes a
        # concrete value (including "none" for a terminal base).
        GUEST_ENV="export ALTARIS_DESKTOPS='${ALTARIS_DESKTOPS:-xfce}'; export ALTARIS_DESKTOP='${ALTARIS_DESKTOP:-xfce}'; export ALTARIS_DESKTOP_ENV='${ALTARIS_DESKTOP_ENV:-xfce}';"
        GUEST_RUN="$GUEST_ENV"'export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; unset LD_LIBRARY_PATH LD_PRELOAD PROOT_TMP_DIR; export TMPDIR=/tmp; if [ -x /bin/bash ]; then exec /bin/bash /tmp/'"$SETUP_GUEST_NAME"' '"$DISTRO_TYPE"'; else exec /bin/sh /tmp/'"$SETUP_GUEST_NAME"' '"$DISTRO_TYPE"'; fi'
        "$PYTHON" "$PROOT_DISTRO" login "$DISTRO" --shared-tmp -- \
            /bin/sh -c "$GUEST_RUN"
        SETUP_EXIT=$?
    fi

    rm -f "$SETUP_HOST_PATH" "$TMPDIR/$SETUP_GUEST_NAME" "$TMPDIR/flux_setup_temp.sh"

    if [ "$SETUP_EXIT" -ne 0 ]; then
        echo "Altaris: Configuration/Setup Script Failed! (exit $SETUP_EXIT)"
        echo "Altaris: Hint: if you saw 'execve(...): Permission denied' or 'loader was not found',"
        echo "  ensure PROOT_LOADER points at libloader.so (jniLibs) and proot-distro was patched"
        echo "  to pass PROOT_LOADER through (TermuxHostPaths.patchProotDistroLoaderPassThrough)."
        exit 1
    fi
    echo "Altaris: Configuration Complete!"
fi

touch "$HOME/.altarislinux_distro_${DISTRO}_installed"
echo "Distro installation and configuration completed successfully!"
exit 0
