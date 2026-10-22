#!/bin/sh
# setup_customization_xfce.sh
# Altaris XFCE branding for Fedora / Void / openSUSE / Deepin / Chimera / Manjaro.
# Same theme/icon/font/xfconf path as Alpine; package manager auto-detected.

CUSTOM_USER="flux"
CUSTOM_GROUP="altaris"
USER_HOME="/home/$CUSTOM_USER"
THEME_DIR="/usr/share/themes"
ICON_DIR="/usr/share/icons"

handle_error() {
    echo ""
    echo "Altaris Error: Script failed at step: $1"
    echo "---------------------------------------------------"
    if [ -t 0 ]; then
        printf "Press Enter to acknowledge error and exit..."
        # shellcheck disable=SC2034
        read -r _ack || true
    fi
    exit 1
}

echo "Altaris: Starting XFCE4 Customization..."

if [ "$(id -u)" -ne 0 ]; then
    echo "Altaris: ERROR: must run as root inside the guest (got uid=$(id -u))."
    handle_error "Not Root"
fi

export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin${PATH:+:$PATH}"
unset LD_LIBRARY_PATH
unset LD_PRELOAD
mkdir -p /tmp /var/tmp
chmod 1777 /tmp /var/tmp 2>/dev/null || true
unset PROOT_TMP_DIR
export TMPDIR=/tmp

_altaris_chown() { chown "$@" 2>/dev/null || true; }
_altaris_chown_r() { chown -R "$@" 2>/dev/null || true; }

# Fedora 44 + proot: sudo PAM account fails → "a password is required".
if command -v sudo >/dev/null 2>&1; then
    mkdir -p /etc/sudoers.d
    echo "flux ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/flux
    chmod 0440 /etc/sudoers.d/flux 2>/dev/null || true
    chmod 0755 /etc/sudoers.d 2>/dev/null || true
    if [ -f /etc/sudoers ]; then
        grep -qE '@includedir[[:space:]]+/etc/sudoers\.d' /etc/sudoers 2>/dev/null \
            || echo '@includedir /etc/sudoers.d' >> /etc/sudoers
        grep -q '^Defaults !authenticate' /etc/sudoers 2>/dev/null \
            || echo 'Defaults !authenticate' >> /etc/sudoers
        grep -q '^Defaults !pam_session' /etc/sudoers 2>/dev/null \
            || echo 'Defaults !pam_session' >> /etc/sudoers
        chmod 0440 /etc/sudoers 2>/dev/null || true
    fi
    if [ -d /etc/pam.d ]; then
        for _pam in /etc/pam.d/sudo /etc/pam.d/sudo-i; do
            cat > "$_pam" <<'PAM'
#%PAM-1.0
# Altaris proot: pam_unix/audit cannot run → sudo asks for a password.
auth       sufficient pam_permit.so
account    sufficient pam_permit.so
password   sufficient pam_permit.so
session    sufficient pam_permit.so
PAM
            chmod 0644 "$_pam" 2>/dev/null || true
        done
    fi
    chmod 4755 /usr/bin/sudo 2>/dev/null || chmod 4755 /usr/sbin/sudo 2>/dev/null || true
fi

if id -gn "$CUSTOM_USER" >/dev/null 2>&1; then
    CUSTOM_GROUP="$(id -gn "$CUSTOM_USER")"
fi

if [ ! -s /etc/resolv.conf ]; then
    printf 'nameserver 8.8.8.8\nnameserver 1.1.1.1\n' > /etc/resolv.conf
fi

_altaris_pkg_add() {
    if grep -q '^ID="chimera"' /usr/lib/os-release 2>/dev/null \
        || grep -q '^ID=chimera' /etc/os-release 2>/dev/null \
        || { [ -d /usr/lib/apk ] && [ ! -d /lib/apk ]; }; then
        # Chimera apk v3: non-interactive config, no --no-cache (Alpine v2 flag).
        # Map generic names to Chimera's (no sudo pkg; tar=gtar; wget=wget2).
        _mapped=""
        for _p in "$@"; do
            case "$_p" in
                wget) _mapped="$_mapped wget2" ;;
                tar)  _mapped="$_mapped gtar" ;;
                font-dejavu) _mapped="$_mapped fonts-dejavu" ;;
                sudo) _mapped="$_mapped opendoas" ;;
                *)    _mapped="$_mapped $_p" ;;
            esac
        done
        # shellcheck disable=SC2086
        apk add $_mapped || apk add "$@"
    elif command -v dnf5 >/dev/null 2>&1; then
        dnf5 -y --setopt=install_weak_deps=False --setopt=tsflags=nodocs,noscripts install "$@"
    elif command -v dnf >/dev/null 2>&1; then
        dnf -y --setopt=install_weak_deps=False --setopt=tsflags=nodocs,noscripts install "$@"
    elif command -v xbps-install >/dev/null 2>&1; then
        xbps-install -y "$@"
    elif command -v zypper >/dev/null 2>&1; then
        # Restore libcurl-mini if a previous curl install broke zypper.
        if [ -f /usr/lib64/libcurl.so.4.8.0.altaris-mini ]; then
            cp -f /usr/lib64/libcurl.so.4.8.0.altaris-mini /usr/lib64/libcurl.so.4.8.0 2>/dev/null || true
        fi
        zypper --non-interactive al libcurl-mini4 libcurl4 curl libldap2 2>/dev/null || true
        zypper --non-interactive install --no-recommends --auto-agree-with-licenses "$@"
    elif command -v pacman >/dev/null 2>&1; then
        pacman -S --noconfirm --needed "$@"
    elif command -v apk >/dev/null 2>&1; then
        apk add --no-cache "$@"                          # Alpine apk v2
    elif command -v apt-get >/dev/null 2>&1; then
        DEBIAN_FRONTEND=noninteractive apt-get install -y "$@"
    else
        return 1
    fi
}

_altaris_fetch() {
    _url="$1"
    _out="$2"
    if command -v wget >/dev/null 2>&1; then
        wget -q -O "$_out" "$_url"
    elif command -v curl >/dev/null 2>&1; then
        curl -fL --connect-timeout 10 --max-time 60 -o "$_out" "$_url"
    else
        return 1
    fi
}

echo "Altaris: Installing customization packages..."
# Never install `curl` on openSUSE — it replaces libcurl-mini and breaks zypper.
if command -v zypper >/dev/null 2>&1; then
    _altaris_pkg_add wget unzip fontconfig git zsh bash || handle_error "customization deps"
else
    _altaris_pkg_add curl wget unzip fontconfig git zsh bash || handle_error "customization deps"
fi
# xz for tar -xJf of theme/icon/cursor archives (self-sufficient re-runs on
# containers whose family pre-dates xz in the base set). Best-effort.
_altaris_pkg_add xz-utils 2>/dev/null || _altaris_pkg_add xz 2>/dev/null || true
_altaris_pkg_add xfce4-screenshooter 2>/dev/null || true
_altaris_pkg_add xfce4-whiskermenu-plugin 2>/dev/null || true
_altaris_pkg_add fastfetch 2>/dev/null || _altaris_pkg_add neofetch 2>/dev/null || true

# Fail closed: guest OMZ + pokemon need git and zsh. Host PREFIX/bin/git is
# not shipped, so this is the only install path. Retry via the native PM
# (Chimera apk v3 / Deepin apt / Manjaro pacman) before aborting.
if ! command -v git >/dev/null 2>&1 || ! command -v zsh >/dev/null 2>&1; then
    echo "Altaris: git/zsh missing after first package pass — retrying"
    if grep -q '^ID="chimera"' /usr/lib/os-release 2>/dev/null \
        || grep -q '^ID=chimera' /etc/os-release 2>/dev/null \
        || { [ -d /usr/lib/apk ] && [ ! -d /lib/apk ]; }; then
        apk add git zsh || true
    elif command -v apt-get >/dev/null 2>&1; then
        DEBIAN_FRONTEND=noninteractive apt-get install -y git zsh || true
    elif command -v pacman >/dev/null 2>&1; then
        pacman -S --noconfirm --needed git zsh || true
    else
        _altaris_pkg_add git zsh || true
    fi
fi
command -v git >/dev/null 2>&1 || handle_error "git missing (required for Oh My Zsh / pokemon)"
command -v zsh >/dev/null 2>&1 || handle_error "zsh missing (required for Altaris shell)"

# pokemon-colorscripts install.sh needs a python interpreter.
if ! command -v python3 >/dev/null 2>&1 && ! command -v python >/dev/null 2>&1; then
    echo "Altaris: Installing python for pokemon-colorscripts..."
    _altaris_pkg_add python3 2>/dev/null || _altaris_pkg_add python 2>/dev/null || true
fi

# Existing guests: customization-only re-run must materialize a UTF-8 locale
# so launchers stop printing "cannot change locale" and zsh can paint @altaris.
# Presence is locale -a only (directory names under /usr/lib/locale lie).
_altaris_pkg_add glibc-langpack-en 2>/dev/null || true
_altaris_pkg_add glibc-locale 2>/dev/null || true
_altaris_pkg_add glibc-locale-base 2>/dev/null || true
_altaris_pkg_add glibc-locales 2>/dev/null || true
_altaris_pkg_add locales 2>/dev/null || true
if command -v pacman >/dev/null 2>&1; then
    command -v gzip >/dev/null 2>&1 || pacman -S --noconfirm gzip 2>/dev/null || true
    if [ ! -f /usr/share/i18n/locales/en_US ] \
        || { [ ! -f /usr/share/i18n/charmaps/UTF-8 ] \
             && [ ! -f /usr/share/i18n/charmaps/UTF-8.gz ]; }; then
        # --needed is a no-op when glibc is already installed (ALARM).
        pacman -S --noconfirm glibc 2>/dev/null || true
    fi
fi
if command -v _altaris_ensure_en_us_locale >/dev/null 2>&1; then
    _altaris_ensure_en_us_locale || handle_error "UTF-8 locale"
else
    if grep -qE '^ID=.?chimera' /etc/os-release 2>/dev/null \
        || grep -qE '^ID=.?alpine' /etc/os-release 2>/dev/null; then
        printf 'LANG=C.UTF-8\n' > /etc/locale.conf
        export LANG=C.UTF-8
    else
        if [ -f /etc/locale.gen ]; then
            grep -q '^en_US.UTF-8 UTF-8' /etc/locale.gen 2>/dev/null \
                || printf 'en_US.UTF-8 UTF-8\n' >> /etc/locale.gen
        fi
        if command -v locale-gen >/dev/null 2>&1; then
            locale-gen en_US.UTF-8 2>/dev/null || locale-gen 2>/dev/null || true
        fi
        if ! locale -a 2>/dev/null | grep -qxFi en_US.utf8 \
            && ! locale -a 2>/dev/null | grep -qxFi en_US.UTF-8 \
            && command -v localedef >/dev/null 2>&1; then
            mkdir -p /usr/lib/locale
            _map=/tmp/altaris-UTF-8
            if [ -f /usr/share/i18n/charmaps/UTF-8.gz ] && command -v gzip >/dev/null 2>&1; then
                gzip -dc /usr/share/i18n/charmaps/UTF-8.gz > "$_map" || true
            elif [ -f /usr/share/i18n/charmaps/UTF-8 ]; then
                cp -f /usr/share/i18n/charmaps/UTF-8 "$_map"
            fi
            if [ -s "$_map" ]; then
                localedef --no-archive -c -i en_US -f "$_map" /usr/lib/locale/en_US.utf8 2>/dev/null || true
                localedef --no-archive -c -i POSIX -f "$_map" /usr/lib/locale/C.utf8 2>/dev/null || true
            else
                localedef --no-archive -c -i en_US -f UTF-8 /usr/lib/locale/en_US.utf8 2>/dev/null || true
            fi
            rm -f "$_map"
        fi
    fi
    mkdir -p /etc/profile.d
    cat > /etc/profile.d/altaris-locale.sh <<'EOF'
_have=$(locale -a 2>/dev/null || true)
_pick=""
for _c in en_US.UTF-8 en_US.utf8 C.UTF-8 C.utf8; do
  echo "$_have" | grep -qxFi "$_c" && { _pick="$_c"; break; }
done
if [ -n "$_pick" ]; then
  export LANG="$_pick" LC_ALL="$_pick"
else
  unset LC_ALL
  export LANG=C
  echo "Altaris: WARNING: no UTF-8 locale in locale -a; zsh staying LANG=C" >&2
fi
unset _have _c _pick
EOF
    . /etc/profile.d/altaris-locale.sh
    if locale -a 2>/dev/null | grep -qiE 'en_US\.(utf8|UTF-8)|C\.(utf8|UTF-8)'; then
        printf 'LANG=%s\n' "${LANG:-C.UTF-8}" > /etc/locale.conf
    else
        echo "Altaris: ERROR: no UTF-8 locale after generation (locale -a has neither en_US.utf8 nor C.utf8)"
        handle_error "UTF-8 locale"
    fi
fi

ASSET_REPO="abhay-byte/fluxlinux"
ASSET_TAG="debian-v1"
BASE_URL="https://github.com/$ASSET_REPO/releases/download/$ASSET_TAG"
ALTARIS_ASSET_DIR="${ALTARIS_ASSET_DIR:-/tmp/altaris_xfce_assets}"

theme_is_installed() {
    _n="$1"
    _d="$THEME_DIR/$_n"
    [ -d "$_d" ] && { [ -f "$_d/index.theme" ] || [ -d "$_d/gtk-3.0" ] || [ -d "$_d/xfwm4" ]; }
}

icon_is_installed() {
    _n="$1"
    _d="$ICON_DIR/$_n"
    [ -d "$_d" ] && { [ -f "$_d/index.theme" ] || [ -n "$(ls -A "$_d" 2>/dev/null)" ]; }
}

cursor_is_installed() {
    _n="$1"
    _d="$ICON_DIR/$_n"
    [ -d "$_d" ] && { [ -f "$_d/index.theme" ] || [ -d "$_d/cursors" ]; }
}

extract_local_tar() {
    _archive="$1"
    _target="$2"
    shift 2
    [ -f "$_archive" ] || return 1
    mkdir -p "$_target"
    chmod 755 "$(dirname "$_archive")" 2>/dev/null || true
    chmod 644 "$_archive" 2>/dev/null || true
    _copy="/var/tmp/$(basename "$_archive")"
    cp -f "$_archive" "$_copy" 2>/dev/null || _copy="$_archive"
    chmod 644 "$_copy" 2>/dev/null || true
    echo " - Extracting $(basename "$_copy") → $_target"
    case "$_copy" in
        *.tar.xz|*.txz) tar -xJf "$_copy" -C "$_target" "$@" ;;
        *.tar.gz|*.tgz) tar -xzf "$_copy" -C "$_target" "$@" ;;
        *.tar)          tar -xf  "$_copy" -C "$_target" "$@" ;;
        *) return 1 ;;
    esac
}

find_asset() {
    _name="$1"
    for f in \
        "$ALTARIS_ASSET_DIR/$_name" \
        "/tmp/altaris_xfce_assets/$_name"
    do
        if [ -f "$f" ]; then
            echo "$f"
            return 0
        fi
    done
    return 1
}

download_if_needed() {
    _url="$1"
    _out="$2"
    if [ -f "$_out" ] && [ -s "$_out" ]; then
        return 0
    fi
    mkdir -p "$(dirname "$_out")"
    echo " - Downloading $(basename "$_out")..."
    _altaris_fetch "$_url" "$_out" || return 1
    [ -s "$_out" ]
}

if [ -n "${ALTARIS_THEME:-}" ]; then
    echo "Altaris: Auto-applying Theme: $ALTARIS_THEME"
    if [ "$ALTARIS_THEME" = "light" ]; then
        THEME_CHOICE="2"
    else
        THEME_CHOICE="1"
    fi
else
    THEME_CHOICE="1"
fi

SEL_ICON="Papirus-Dark"
ICON_TAR="papirus-dark-only.tar.xz"
# papirus-icon-theme is ~100MiB. Seed extras first; only pull the package
# if the XFCE category gate still fails (see after extract).

if [ "$THEME_CHOICE" = "2" ]; then
    SEL_THEME="Space-light"
    SEL_CURSOR="Vimix-cursors"
    SEL_WALLPAPER="altaris-light.png"
    THEME_TAR="Space-light.tar.xz"
    CURSOR_TAR="01-Vimix-cursors.tar.xz"
else
    SEL_THEME="Space-transparency"
    SEL_CURSOR="Vimix-white-cursors"
    SEL_WALLPAPER="altaris-dark.png"
    THEME_TAR="Space-transparency.tar.xz"
    CURSOR_TAR="02-Vimix-white-cursors.tar.xz"
fi

mkdir -p "$THEME_DIR" "$ICON_DIR"

papirus_xfce_ok() {
    _p="$ICON_DIR/Papirus-Dark"
    [ -f "$_p/index.theme" ] || return 1
    for n in applications-internet applications-accessories applications-system; do
        _found=0
        for _d in "$_p/24x24/categories" "$_p/48x48/categories" "$_p/scalable/categories"; do
            [ -f "$_d/$n.svg" ] || [ -f "$_d/$n.png" ] || continue
            _found=1
            break
        done
        [ "$_found" = 1 ] || return 1
    done
    return 0
}

if [ "${ALTARIS_SKIP_THEME_ICONS:-0}" = "1" ]; then
    echo "Altaris: Themes/icons pre-installed on host — skip guest extract"
elif theme_is_installed "$SEL_THEME" && papirus_xfce_ok && cursor_is_installed "$SEL_CURSOR"; then
    echo "Altaris: Theme+icons+cursor already installed — skip extract"
else
    # Theme/icon/cursor failures must not abort before OMZ/pokemon.
    if ! theme_is_installed "$SEL_THEME"; then
        echo "Altaris: Installing theme $SEL_THEME..."
        TFILE="$(find_asset "$THEME_TAR" || true)"
        if [ -z "$TFILE" ]; then
            if download_if_needed "$BASE_URL/theme.zip" "/tmp/theme.zip"; then
                unzip -q -o /tmp/theme.zip -d /tmp/altaris_theme_zip
                TFILE="$(find /tmp/altaris_theme_zip -name "$THEME_TAR" 2>/dev/null | head -1)"
            else
                echo "Altaris: WARNING: theme download failed — continuing"
            fi
        fi
        if [ -n "$TFILE" ]; then
            extract_local_tar "$TFILE" "$THEME_DIR" \
                || echo "Altaris: WARNING: theme extract failed — continuing"
        fi
    fi
    if ! papirus_xfce_ok; then
        echo "Altaris: Installing icons $SEL_ICON..."
        IFILE="$(find_asset "$ICON_TAR" || find_asset "papirus-dark-only.tar.gz" || true)"
        if [ -z "$IFILE" ]; then
            if download_if_needed "$BASE_URL/icons.zip" "/tmp/icons.zip"; then
                unzip -q -o /tmp/icons.zip -d /tmp/altaris_icons_zip
                IFILE="$(find /tmp/altaris_icons_zip -name "$ICON_TAR" 2>/dev/null | head -1)"
                [ -n "$IFILE" ] || IFILE="$(find /tmp/altaris_icons_zip -name "papirus-dark-only.tar.gz" 2>/dev/null | head -1)"
            else
                echo "Altaris: WARNING: icons download failed — continuing"
            fi
        fi
        if [ -z "$IFILE" ]; then
            echo "Altaris: WARNING: Papirus archive missing — continuing (OMZ/pokemon still run)"
        else
            extract_local_tar "$IFILE" "$ICON_DIR" "Papirus-Dark" 2>/dev/null || \
                extract_local_tar "$IFILE" "$ICON_DIR" || \
                echo "Altaris: WARNING: icons extract failed — continuing"
        fi
    fi
    if ! cursor_is_installed "$SEL_CURSOR"; then
        echo "Altaris: Installing cursor $SEL_CURSOR..."
        CFILE="$(find_asset "$CURSOR_TAR" || true)"
        if [ -z "$CFILE" ]; then
            if download_if_needed "$BASE_URL/cursor.zip" "/tmp/cursor.zip"; then
                unzip -q -o /tmp/cursor.zip -d /tmp/altaris_cursor_zip
                CFILE="$(find /tmp/altaris_cursor_zip -name "$CURSOR_TAR" 2>/dev/null | head -1)"
            else
                echo "Altaris: WARNING: cursor download failed — continuing"
            fi
        fi
        if [ -n "$CFILE" ]; then
            extract_local_tar "$CFILE" "$ICON_DIR" \
                || echo "Altaris: WARNING: cursor extract failed — continuing"
        fi
    fi
fi

# Papirus-Dark ships many dirs as symlinks into sibling Papirus/. The stub
# (and guests that never installed papirus-icon-theme) leave those dangling,
# so mkdir -p / cp print "File exists" / "No such file". Replace the link
# with a real directory before writing.
_altaris_ensure_dir() {
    _p="$1"
    if [ -L "$_p" ] && [ ! -e "$_p" ]; then
        rm -f "$_p" 2>/dev/null || true
    elif [ -f "$_p" ]; then
        rm -f "$_p" 2>/dev/null || true
    elif [ -L "$_p" ]; then
        # Valid symlink (e.g. 16x16@2x -> 16x16): keep it, but if it points
        # at a file, drop it so we can mkdir.
        [ -d "$_p" ] || rm -f "$_p" 2>/dev/null || true
    fi
    mkdir -p "$_p" 2>/dev/null || true
    [ -d "$_p" ]
}

# Always repair Papirus-Dark even when extract was skipped (existing guests).
_altaris_fix_papirus_dangling() {
    for _theme in Papirus-Dark Papirus; do
        [ -e "$ICON_DIR/$_theme" ] || continue
        for _sz in 16x16 22x22 24x24 32x32 48x48 64x64 \
                   16x16@2x 22x22@2x 24x24@2x 32x32@2x 48x48@2x; do
            _altaris_ensure_dir "$ICON_DIR/$_theme/$_sz" || true
            for _ctx in status categories apps; do
                _altaris_ensure_dir "$ICON_DIR/$_theme/$_sz/$_ctx" || true
            done
        done
    done
}

_altaris_rewrite_papirus_inherits() {
    _idx="$ICON_DIR/Papirus-Dark/index.theme"
    [ -f "$_idx" ] || return 0
    _inh="Adwaita,hicolor"
    [ -d /usr/share/icons/AdwaitaLegacy ] && _inh="Adwaita,AdwaitaLegacy,hicolor"
    [ -d /usr/share/icons/adwaita-xfce ] && _inh="${_inh%,hicolor},adwaita-xfce,hicolor"
    _tmp="${_idx}.altaris.$$"
    awk -v inh="$_inh" '
        BEGIN { done=0 }
        /^Inherits=/ { print "Inherits=" inh; done=1; next }
        { print }
        END { if (!done) print "Inherits=" inh }
    ' "$_idx" > "$_tmp" && mv "$_tmp" "$_idx"
    echo "Altaris: Papirus-Dark Inherits=$_inh"
}

_altaris_find_category_icon() {
    # $1 = stem. Prints first matching file path.
    _stem="$1"
    for _c in \
        "$ICON_DIR/Papirus/24x24/categories/$_stem.svg" \
        "$ICON_DIR/Papirus/24x24/apps/$_stem.svg" \
        "$ICON_DIR/Papirus/48x48/categories/$_stem.svg" \
        "$ICON_DIR/Papirus/48x48/apps/$_stem.svg" \
        "$ICON_DIR/Papirus-Dark/24x24/apps/$_stem.svg" \
        "$ICON_DIR/Papirus-Dark/48x48/apps/$_stem.svg" \
        /usr/share/icons/adwaita-xfce/scalable/categories/"$_stem".svg \
        /usr/share/icons/AdwaitaLegacy/24x24/legacy/"$_stem".png \
        /usr/share/icons/AdwaitaLegacy/48x48/legacy/"$_stem".png \
        /usr/share/icons/Adwaita/24x24/categories/"$_stem".png \
        /usr/share/icons/Adwaita/48x48/categories/"$_stem".png \
        /usr/share/icons/Adwaita/scalable/categories/"$_stem".svg
    do
        if [ -f "$_c" ]; then
            printf '%s\n' "$_c"
            return 0
        fi
    done
    return 1
}

_altaris_seed_papirus_categories() {
    _stems="applications-accessories applications-development applications-games applications-graphics applications-internet applications-multimedia applications-office applications-other applications-science applications-system applications-utilities preferences-desktop preferences-system xfce-settings"
    _altaris_ensure_dir "$ICON_DIR/Papirus-Dark/24x24/categories" || true
    _altaris_ensure_dir "$ICON_DIR/Papirus-Dark/48x48/categories" || true
    for _n in $_stems; do
        _src="$(_altaris_find_category_icon "$_n" || true)"
        [ -n "$_src" ] || continue
        for _sz in 24x24 48x48; do
            _dest="$ICON_DIR/Papirus-Dark/$_sz/categories"
            _altaris_ensure_dir "$_dest" || continue
            _ext="${_src##*.}"
            [ -f "$_dest/$_n.$_ext" ] || cp -f "$_src" "$_dest/$_n.$_ext"
        done
    done
}

_altaris_fix_papirus_dangling
_altaris_rewrite_papirus_inherits

# Overlay XFCE category extras only when the gate is not yet satisfied.
# Re-extracting onto a root-owned stub hangs some Android tars ("File exists").
CATFILE="$(find_asset "papirus-xfce-categories.tar.xz" || true)"
if [ -n "$CATFILE" ] && ! papirus_xfce_ok; then
    extract_local_tar "$CATFILE" "$ICON_DIR" \
        || echo "Altaris: WARNING: category extras extract failed — continuing"
fi

_altaris_seed_papirus_categories

# Offline extras/stub usually satisfy the gate. Only then try the huge
# guest papirus-icon-theme package (Fedora ~100MiB).
if ! papirus_xfce_ok; then
    echo "Altaris: category icons still missing — trying papirus-icon-theme"
    _altaris_pkg_add papirus-icon-theme 2>/dev/null \
        || _altaris_pkg_add papirus-icon-theme-dark 2>/dev/null || true
    _altaris_fix_papirus_dangling
    _altaris_seed_papirus_categories
fi

# image-missing seed so GTK/glycin does not abort xfce4-panel.
_src_missing=""
for _c in /usr/share/icons/Adwaita/scalable/status/image-missing.svg \
          /usr/share/icons/Adwaita/symbolic/status/image-missing-symbolic.svg \
          /usr/share/icons/hicolor/scalable/status/image-missing.svg; do
    [ -f "$_c" ] && _src_missing="$_c" && break
done
if [ -n "$_src_missing" ]; then
    for _theme in Papirus-Dark Papirus; do
        [ -e "$ICON_DIR/$_theme" ] || continue
        for _sz in 16x16 22x22 24x24 32x32 48x48 64x64 \
                   16x16@2x 22x22@2x 24x24@2x 32x32@2x 48x48@2x; do
            _szd="$ICON_DIR/$_theme/$_sz"
            _altaris_ensure_dir "$_szd" || continue
            _dest="$_szd/status"
            _altaris_ensure_dir "$_dest" || continue
            [ -f "$_dest/image-missing.svg" ] || cp -f "$_src_missing" "$_dest/image-missing.svg" 2>/dev/null || true
        done
    done
fi

if papirus_xfce_ok; then
    SEL_ICON="Papirus-Dark"
    echo "Altaris: Icon theme Papirus-Dark (category icons present)"
else
    echo "Altaris: Papirus-Dark missing XFCE category icons — Adwaita fallback"
    SEL_ICON="Adwaita"
    mkdir -p "$ICON_DIR/Adwaita/24x24/categories"
    for n in applications-internet applications-accessories applications-system; do
        _src="$(_altaris_find_category_icon "$n" || true)"
        [ -n "$_src" ] || continue
        _ext="${_src##*.}"
        [ -f "$ICON_DIR/Adwaita/24x24/categories/$n.$_ext" ] \
            || cp -f "$_src" "$ICON_DIR/Adwaita/24x24/categories/$n.$_ext"
    done
fi
gtk-update-icon-cache -f "$ICON_DIR/Papirus-Dark" 2>/dev/null || true

WALLPAPER_DIR="$USER_HOME/Pictures/Wallpapers"
mkdir -p "$WALLPAPER_DIR"
_altaris_chown_r "$CUSTOM_USER:$CUSTOM_GROUP" "$USER_HOME/Pictures" 2>/dev/null || true

if [ ! -f "$WALLPAPER_DIR/$SEL_WALLPAPER" ]; then
    WFILE="$(find_asset "$SEL_WALLPAPER" || true)"
    if [ -n "$WFILE" ]; then
        cp -f "$WFILE" "$WALLPAPER_DIR/$SEL_WALLPAPER"
    else
        TEMP_WP_ZIP="/tmp/wallpaper.zip"
        if download_if_needed "$BASE_URL/wallpaper.zip" "$TEMP_WP_ZIP"; then
            unzip -o -j "$TEMP_WP_ZIP" -d "$WALLPAPER_DIR" 2>/dev/null || true
            [ -f "$WALLPAPER_DIR/dark.png" ] && mv -f "$WALLPAPER_DIR/dark.png" "$WALLPAPER_DIR/altaris-dark.png"
            [ -f "$WALLPAPER_DIR/light.png" ] && mv -f "$WALLPAPER_DIR/light.png" "$WALLPAPER_DIR/altaris-light.png"
        fi
    fi
fi
_altaris_chown "$CUSTOM_USER:$CUSTOM_GROUP" "$WALLPAPER_DIR"/* 2>/dev/null || true

FONT_DIR="/usr/share/fonts/jetbrains-mono-nerd"
if ! fc-list 2>/dev/null | grep -qi "JetBrainsMono Nerd"; then
    echo "Altaris: Installing JetBrains Mono Nerd Font..."
    mkdir -p "$FONT_DIR"
    TEMP_ZIP="/tmp/JetBrainsMono.zip"
    NERD_FONT_URL="https://github.com/ryanoasis/nerd-fonts/releases/latest/download/JetBrainsMono.zip"
    if _altaris_fetch "$NERD_FONT_URL" "$TEMP_ZIP"; then
        unzip -o -j "$TEMP_ZIP" "*.ttf" -d "$FONT_DIR" 2>/dev/null || unzip -o "$TEMP_ZIP" -d "$FONT_DIR" 2>/dev/null || true
        fc-cache -f "$FONT_DIR" 2>/dev/null || true
    fi
    rm -f "$TEMP_ZIP"
fi

if fc-list 2>/dev/null | grep -qi "JetBrainsMono Nerd"; then
    _gtk_font="JetBrainsMono Nerd Font 9"
    _mono_font="JetBrainsMono Nerd Font Mono 10"
    _term_font="JetBrainsMono Nerd Font 10"
    _title_font="JetBrainsMono Nerd Font Bold 9"
else
    echo "Altaris: JetBrainsMono Nerd not in fontconfig — VTE fallback DejaVu Sans Mono"
    _gtk_font="DejaVu Sans Mono 9"
    _mono_font="DejaVu Sans Mono 10"
    _term_font="DejaVu Sans Mono 10"
    _title_font="DejaVu Sans Bold 9"
fi

echo "Altaris: Applying XFCE4 Settings..."
XFCONF_DIR="$USER_HOME/.config/xfce4/xfconf/xfce-perchannel-xml"
mkdir -p "$XFCONF_DIR"
WALLPAPER_PATH="$WALLPAPER_DIR/$SEL_WALLPAPER"

cat > "$XFCONF_DIR/xsettings.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xsettings" version="1.0">
  <property name="Net" type="empty">
    <property name="ThemeName" type="string" value="$SEL_THEME"/>
    <property name="IconThemeName" type="string" value="$SEL_ICON"/>
    <property name="EnableEventSounds" type="bool" value="false"/>
    <property name="EnableInputFeedbackSounds" type="bool" value="false"/>
  </property>
  <property name="Gtk" type="empty">
    <property name="CursorThemeName" type="string" value="$SEL_CURSOR"/>
    <property name="CursorThemeSize" type="int" value="32"/>
    <property name="FontName" type="string" value="$_gtk_font"/>
    <property name="MonospaceFontName" type="string" value="$_mono_font"/>
    <property name="DecorationLayout" type="string" value="minimize,maximize,close"/>
  </property>
  <property name="Xft" type="empty">
    <property name="DPI" type="int" value="96"/>
    <property name="Antialias" type="int" value="1"/>
    <property name="HintStyle" type="string" value="hintslight"/>
    <property name="RGBA" type="string" value="rgb"/>
  </property>
  <property name="Gdk" type="empty">
    <property name="WindowScalingFactor" type="int" value="1"/>
  </property>
</channel>
EOF

mkdir -p "$USER_HOME/.config/gtk-3.0"
cat > "$USER_HOME/.config/gtk-3.0/settings.ini" <<EOF
[Settings]
gtk-theme-name=$SEL_THEME
gtk-icon-theme-name=$SEL_ICON
gtk-cursor-theme-name=$SEL_CURSOR
gtk-font-name=$_gtk_font
EOF

TERM_CONFIG_DIR="$USER_HOME/.config/xfce4/terminal"
mkdir -p "$TERM_CONFIG_DIR"
cat > "$TERM_CONFIG_DIR/terminalrc" <<EOF
[Configuration]
FontUseSystem=FALSE
FontName=$_term_font
EOF
cat > "$XFCONF_DIR/xfce4-terminal.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfce4-terminal" version="1.0">
  <property name="font-use-system" type="bool" value="false"/>
  <property name="font-name" type="string" value="$_term_font"/>
</channel>
EOF

cat > "$XFCONF_DIR/xfwm4.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfwm4" version="1.0">
  <property name="general" type="empty">
    <property name="theme" type="string" value="$SEL_THEME"/>
    <property name="title_font" type="string" value="$_title_font"/>
    <property name="title_alignment" type="string" value="left"/>
    <property name="button_layout" type="string" value="|HMC"/>
    <property name="borderless_maximize" type="bool" value="true"/>
    <property name="use_compositing" type="bool" value="false"/>
    <property name="snap_to_border" type="bool" value="true"/>
    <property name="snap_to_windows" type="bool" value="true"/>
    <property name="tile_on_move" type="bool" value="true"/>
    <property name="wrap_windows" type="bool" value="true"/>
    <property name="workspace_count" type="int" value="1"/>
    <property name="scroll_workspaces" type="bool" value="false"/>
  </property>
</channel>
EOF

MONITORS="monitor0 monitor1 monitorVNC-0 monitorbuiltin builtin monitorHDMI-A-0 monitorVirtual-0 monitorVirtual-1 monitorVirtual1"
MONITOR_PROPS=""
for M in $MONITORS; do
    MONITOR_PROPS="$MONITOR_PROPS
      <property name=\"$M\" type=\"empty\">
        <property name=\"workspace0\" type=\"empty\">
          <property name=\"last-image\" type=\"string\" value=\"$WALLPAPER_PATH\"/>
          <property name=\"image-style\" type=\"int\" value=\"5\"/>
          <property name=\"color-style\" type=\"int\" value=\"0\"/>
          <property name=\"rgba1\" type=\"array\">
            <value type=\"double\" value=\"0.0431372549019608\"/>
            <value type=\"double\" value=\"0.0470588235294118\"/>
            <value type=\"double\" value=\"0.0549019607843137\"/>
            <value type=\"double\" value=\"1\"/>
          </property>
          <property name=\"rgba2\" type=\"array\">
            <value type=\"double\" value=\"0.0431372549019608\"/>
            <value type=\"double\" value=\"0.0470588235294118\"/>
            <value type=\"double\" value=\"0.0549019607843137\"/>
            <value type=\"double\" value=\"1\"/>
          </property>
        </property>
      </property>"
done
cat > "$XFCONF_DIR/xfce4-desktop.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfce4-desktop" version="1.0">
  <property name="backdrop" type="empty">
    <property name="screen0" type="empty">$MONITOR_PROPS
    </property>
  </property>
  <property name="desktop-icons" type="empty">
    <property name="style" type="int" value="2"/>
    <property name="file-icons" type="empty">
      <property name="show-home" type="bool" value="true"/>
      <property name="show-filesystem" type="bool" value="false"/>
      <property name="show-trash" type="bool" value="false"/>
      <property name="show-removable" type="bool" value="false"/>
    </property>
  </property>
</channel>
EOF

_altaris_chown_r "$CUSTOM_USER:$CUSTOM_GROUP" "$USER_HOME/.config" 2>/dev/null || true
_altaris_chown_r "$CUSTOM_USER:$CUSTOM_GROUP" "$USER_HOME" 2>/dev/null || true

_altaris_write_altaris_xfce_panel() {
    _pcfg="$1"
    _phome="${2:-/home/flux}"
    [ -n "$_pcfg" ] || return 0
    mkdir -p "$_pcfg" 2>/dev/null || return 0

    # Menu button: Whisker when its panel plugin is installed, built-in menu else.
    _menu_plugin="applicationsmenu"
    for _pdir in /usr/lib/xfce4/panel/plugins /usr/lib64/xfce4/panel/plugins \
                 /usr/lib/*/xfce4/panel/plugins /usr/local/lib/xfce4/panel/plugins; do
        if [ -e "$_pdir/libwhiskermenu.so" ]; then
            _menu_plugin="whiskermenu"
            break
        fi
    done

    # Status indicators: only the ones actually loadable in this guest
    # (the PulseAudio panel module ships as libpulseaudio-plugin.so).
    _status=""
    for _pnew in pulseaudio-plugin battery netload; do
        for _pdir in /usr/lib/xfce4/panel/plugins /usr/lib64/xfce4/panel/plugins \
                     /usr/lib/*/xfce4/panel/plugins /usr/local/lib/xfce4/panel/plugins; do
            if [ -e "$_pdir/lib$_pnew.so" ]; then
                _status="$_status $_pnew"
                break
            fi
        done
    done

    # Dock launcher entries with explicit stock icons (Papirus-Dark-friendly).
    _browser=""
    for _bnew in firefox chromium chromium-browser epiphany midori falkon; do
        if command -v "$_bnew" >/dev/null 2>&1; then
            _browser="$_bnew"
            break
        fi
    done
    _appdir="$_phome/.local/share/applications"
    mkdir -p "$_appdir" 2>/dev/null || true
    if [ -d "$_appdir" ]; then
        cat > "$_appdir/altaris-terminal.desktop" <<'ALTARIS_DESKTOP'
[Desktop Entry]
Type=Application
Version=1.0
Name=Terminal
Comment=Terminal emulator
Exec=xfce4-terminal
Icon=utilities-terminal
Terminal=false
Categories=System;TerminalEmulator;
ALTARIS_DESKTOP
        cat > "$_appdir/altaris-files.desktop" <<'ALTARIS_DESKTOP'
[Desktop Entry]
Type=Application
Version=1.0
Name=Files
Comment=File manager
Exec=thunar
Icon=folder
Terminal=false
Categories=System;FileManager;
ALTARIS_DESKTOP
        if command -v xfce4-settings-manager >/dev/null 2>&1; then
            cat > "$_appdir/altaris-settings.desktop" <<'ALTARIS_DESKTOP'
[Desktop Entry]
Type=Application
Version=1.0
Name=Settings
Comment=Desktop settings
Exec=xfce4-settings-manager
Icon=preferences-system
Terminal=false
Categories=Settings;
ALTARIS_DESKTOP
        fi
        if [ -n "$_browser" ]; then
            cat > "$_appdir/altaris-browser.desktop" <<ALTARIS_DESKTOP
[Desktop Entry]
Type=Application
Version=1.0
Name=Browser
Comment=Web browser
Exec=$_browser
Icon=applications-internet
Terminal=false
Categories=Network;WebBrowser;
ALTARIS_DESKTOP
        fi
        # Root runs: hand the launcher entries to the home owner.
        _hu=$(stat -c %u "$_phome" 2>/dev/null || stat -f %u "$_phome" 2>/dev/null || true)
        _hg=$(stat -c %g "$_phome" 2>/dev/null || stat -f %g "$_phome" 2>/dev/null || true)
        if [ -n "$_hu" ] && [ "$(id -u 2>/dev/null || echo 0)" = "0" ]; then
            chown "$_hu:$_hg" "$_appdir"/altaris-*.desktop 2>/dev/null || true
        fi
    fi

    _pid=0
    _top_ids=""
    _dock_ids=""
    _plugins=""

    # ── TOP status bar: drawer + clock … systray + indicators ───────────────
    _pid=$((_pid + 1))
    _top_ids="$_top_ids        <value type='int' value='$_pid'/>
"
    _plugins="$_plugins
    <property name='plugin-$_pid' type='string' value='$_menu_plugin'>
      <property name='button-icon' type='string' value='open-menu'/>
      <property name='button-title' type='string' value='Apps'/>
      <property name='show-button-title' type='bool' value='false'/>
      <property name='show-menu-icons' type='bool' value='true'/>
      <property name='show-tooltips' type='bool' value='false'/>
    </property>"

    _pid=$((_pid + 1))
    _top_ids="$_top_ids        <value type='int' value='$_pid'/>
"
    _plugins="$_plugins
    <property name='plugin-$_pid' type='string' value='clock'>
      <property name='mode' type='uint' value='4'/>
      <property name='digital-layout' type='uint' value='1'/>
      <property name='digital-format' type='string' value='%H:%M'/>
      <property name='tooltip-format' type='string' value='%A, %B %d, %Y'/>
      <property name='show-seconds' type='bool' value='false'/>
      <property name='show-meridiem' type='bool' value='false'/>
      <property name='timezone' type='string' value=''/>
    </property>"

    _pid=$((_pid + 1))
    _top_ids="$_top_ids        <value type='int' value='$_pid'/>
"
    _plugins="$_plugins
    <property name='plugin-$_pid' type='string' value='separator'>
      <property name='expand' type='bool' value='true'/>
      <property name='style' type='uint' value='0'/>
    </property>"

    _pid=$((_pid + 1))
    _top_ids="$_top_ids        <value type='int' value='$_pid'/>
"
    _plugins="$_plugins
    <property name='plugin-$_pid' type='string' value='systray'>
      <property name='square-icons' type='bool' value='true'/>
    </property>"

    for _pnew in $_status; do
        _pid=$((_pid + 1))
        _top_ids="$_top_ids        <value type='int' value='$_pid'/>
"
        _plugins="$_plugins
    <property name='plugin-$_pid' type='string' value='$_pnew'/>"
    done

    # ── BOTTOM dock: apps first, icon-only tasklist in the middle, power ────
    _pid=$((_pid + 1))
    _dock_ids="$_dock_ids        <value type='int' value='$_pid'/>
"
    _plugins="$_plugins
    <property name='plugin-$_pid' type='string' value='$_menu_plugin'>
      <property name='button-icon' type='string' value='open-menu'/>
      <property name='button-title' type='string' value='Apps'/>
      <property name='show-button-title' type='bool' value='false'/>
      <property name='show-menu-icons' type='bool' value='true'/>
      <property name='show-tooltips' type='bool' value='false'/>
    </property>"

    _pid=$((_pid + 1))
    _dock_ids="$_dock_ids        <value type='int' value='$_pid'/>
"
    _plugins="$_plugins
    <property name='plugin-$_pid' type='string' value='launcher'>
      <property name='items' type='array'>
        <value type='string' value='altaris-terminal.desktop'/>
      </property>
    </property>"

    _pid=$((_pid + 1))
    _dock_ids="$_dock_ids        <value type='int' value='$_pid'/>
"
    _plugins="$_plugins
    <property name='plugin-$_pid' type='string' value='launcher'>
      <property name='items' type='array'>
        <value type='string' value='altaris-files.desktop'/>
      </property>
    </property>"

    if command -v xfce4-settings-manager >/dev/null 2>&1; then
        _pid=$((_pid + 1))
        _dock_ids="$_dock_ids        <value type='int' value='$_pid'/>
"
        _plugins="$_plugins
    <property name='plugin-$_pid' type='string' value='launcher'>
      <property name='items' type='array'>
        <value type='string' value='altaris-settings.desktop'/>
      </property>
    </property>"
    fi

    if [ -n "$_browser" ]; then
        _pid=$((_pid + 1))
        _dock_ids="$_dock_ids        <value type='int' value='$_pid'/>
"
        _plugins="$_plugins
    <property name='plugin-$_pid' type='string' value='launcher'>
      <property name='items' type='array'>
        <value type='string' value='altaris-browser.desktop'/>
      </property>
    </property>"
    fi

    _pid=$((_pid + 1))
    _dock_ids="$_dock_ids        <value type='int' value='$_pid'/>
"
    _plugins="$_plugins
    <property name='plugin-$_pid' type='string' value='separator'>
      <property name='expand' type='bool' value='true'/>
      <property name='style' type='uint' value='0'/>
    </property>"

    _pid=$((_pid + 1))
    _dock_ids="$_dock_ids        <value type='int' value='$_pid'/>
"
    _plugins="$_plugins
    <property name='plugin-$_pid' type='string' value='tasklist'>
      <property name='grouping' type='uint' value='1'/>
      <property name='flat-buttons' type='bool' value='true'/>
      <property name='show-labels' type='bool' value='false'/>
      <property name='show-handle' type='bool' value='false'/>
      <property name='show-wireframes' type='bool' value='false'/>
      <property name='window-scrolling' type='bool' value='true'/>
    </property>"

    _pid=$((_pid + 1))
    _dock_ids="$_dock_ids        <value type='int' value='$_pid'/>
"
    _plugins="$_plugins
    <property name='plugin-$_pid' type='string' value='separator'>
      <property name='expand' type='bool' value='true'/>
      <property name='style' type='uint' value='0'/>
    </property>"

    _pid=$((_pid + 1))
    _dock_ids="$_dock_ids        <value type='int' value='$_pid'/>
"
    _plugins="$_plugins
    <property name='plugin-$_pid' type='string' value='actions'>
      <property name='appearance' type='uint' value='0'/>
      <property name='items' type='array'>
        <value type='string' value='logout-dialog'/>
      </property>
    </property>"

    cat > "$_pcfg/xfce4-panel.xml" <<EOF
<?xml version="1.1" encoding="UTF-8"?>
<channel name="xfce4-panel" version="1.0">
  <property name="configver" type="int" value="2"/>
  <property name="panels" type="array">
    <value type="int" value="1"/>
    <value type="int" value="2"/>
    <property name="dark-mode" type="bool" value="true"/>
    <property name="panel-1" type="empty">
      <property name="position" type="string" value="p=6;x=0;y=0"/>
      <property name="length" type="uint" value="100"/>
      <property name="position-locked" type="bool" value="true"/>
      <property name="size" type="uint" value="28"/>
      <property name="icon-size" type="uint" value="16"/>
      <property name="background-style" type="uint" value="1"/>
      <property name="background-rgba" type="array">
        <value type="double" value="0.0431372549019608"/>
        <value type="double" value="0.0470588235294118"/>
        <value type="double" value="0.0549019607843137"/>
        <value type="double" value="0.9215686274509803"/>
      </property>
      <property name="plugin-ids" type="array">
$_top_ids      </property>
    </property>
    <property name="panel-2" type="empty">
      <property name="position" type="string" value="p=10;x=0;y=0"/>
      <property name="length" type="uint" value="98"/>
      <property name="position-locked" type="bool" value="true"/>
      <property name="size" type="uint" value="64"/>
      <property name="icon-size" type="uint" value="32"/>
      <property name="background-style" type="uint" value="1"/>
      <property name="background-rgba" type="array">
        <value type="double" value="0.0431372549019608"/>
        <value type="double" value="0.0470588235294118"/>
        <value type="double" value="0.0549019607843137"/>
        <value type="double" value="0.8784313725490196"/>
      </property>
      <property name="plugin-ids" type="array">
$_dock_ids      </property>
    </property>
  </property>
  <property name="plugins" type="empty">$_plugins
  </property>
</channel>
EOF

    unset _pcfg _phome _pdir _pnew _status _browser _bnew _appdir _hu _hg \
        _pid _top_ids _dock_ids _plugins
    return 0
}

# ── Compact phone layout: top status bar + floating dock + shortcuts ──────
# Reuses the guest's shipped theme/icons; no colors, no extra downloads.
_altaris_write_altaris_xfce_compact() {
    _cfg="$USER_HOME/.config/xfce4/xfconf/xfce-perchannel-xml"
    mkdir -p "$_cfg" "$USER_HOME/Pictures" 2>/dev/null || return 0
    _menu_plugin="applicationsmenu"
    if [ -e /usr/lib/xfce4/panel/plugins/libwhiskermenu.so ] \
        || [ -e /usr/lib64/xfce4/panel/plugins/libwhiskermenu.so ] \
        || command -v xfce4-whiskermenu-plugin >/dev/null 2>&1; then
        _menu_plugin="whiskermenu"
    fi

    # Android-style phone panels (thin status bar + floating dock); the
    # shared writer degrades gracefully and leaves _menu_plugin set for the
    # keyboard-shortcut block below.
    _altaris_write_altaris_xfce_panel "$_cfg" "$USER_HOME" || true

    cat > "$_cfg/xfce4-keyboard-shortcuts.xml" <<EOF
<?xml version="1.1" encoding="UTF-8"?>
<channel name="xfce4-keyboard-shortcuts" version="1.0">
  <property name="commands" type="empty">
    <property name="custom" type="empty">
      <property name="&lt;Super&gt;Return" type="string" value="xfce4-terminal">
        <property name="startup-notify" type="bool" value="true"/>
      </property>
      <property name="&lt;Super&gt;e" type="string" value="thunar">
        <property name="startup-notify" type="bool" value="true"/>
      </property>
      <property name="&lt;Primary&gt;&lt;Alt&gt;t" type="string" value="xfce4-terminal">
        <property name="startup-notify" type="bool" value="true"/>
      </property>
      <property name="Print" type="string" value="xfce4-screenshooter -f -s /home/flux/Pictures"/>
      <property name="&lt;Shift&gt;Print" type="string" value="xfce4-screenshooter -r -s /home/flux/Pictures"/>
      <property name="&lt;Alt&gt;F1" type="string" value="xfce4-popup-$_menu_plugin"/>
      <property name="&lt;Primary&gt;&lt;Alt&gt;Delete" type="string" value="xfce4-session-logout"/>
      <property name="override" type="bool" value="true"/>
    </property>
  </property>
  <property name="providers" type="array">
    <value type="string" value="xfwm4"/>
    <value type="string" value="commands"/>
  </property>
</channel>
EOF

    cat > "$_cfg/xfce4-screensaver.xml" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfce4-screensaver" version="1.0">
  <property name="saver" type="empty">
    <property name="enabled" type="bool" value="false"/>
  </property>
  <property name="lock" type="empty">
    <property name="enabled" type="bool" value="false"/>
  </property>
</channel>
EOF

    cat > "$_cfg/thunar.xml" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<channel name="thunar" version="1.0">
  <property name="misc-single-click" type="bool" value="true"/>
  <property name="misc-thumbnail-mode" type="string" value="THUNAR_THUMBNAIL_MODE_ONLY_LOCAL"/>
</channel>
EOF
    mkdir -p "$USER_HOME/.config/Thunar" 2>/dev/null || true
    if [ ! -f "$USER_HOME/.config/Thunar/thunarrc" ]; then
        cat > "$USER_HOME/.config/Thunar/thunarrc" <<'EOF'
[Configuration]
MiscSingleClick=TRUE
EOF
    fi
    _altaris_chown_r "$CUSTOM_USER:$CUSTOM_GROUP" "$_cfg" 2>/dev/null || true
    _altaris_chown_r "$CUSTOM_USER:$CUSTOM_GROUP" "$USER_HOME/.config/Thunar" 2>/dev/null || true
    unset _cfg _menu_plugin
    return 0
}
_altaris_write_altaris_xfce_compact

_altaris_timeout_works() {
    # Chimera/BSD timeout under Android chroot dies with
    # "sigaction(32): Invalid argument" and never execs the child.
    command -v timeout >/dev/null 2>&1 || return 1
    timeout 1 true >/dev/null 2>&1
}

_altaris_run() {
    _t="$1"; shift
    if _altaris_timeout_works; then
        timeout "$_t" "$@"
        return $?
    fi
    # Portable watchdog — keep stderr so clone failures are visible.
    "$@" &
    _cmd=$!
    (
        _i=0
        while [ "$_i" -lt "$_t" ]; do
            kill -0 "$_cmd" 2>/dev/null || exit 0
            sleep 1
            _i=$((_i + 1))
        done
        echo "Altaris: command timed out after ${_t}s"
        kill "$_cmd" 2>/dev/null || true
        sleep 1
        kill -9 "$_cmd" 2>/dev/null || true
    ) &
    _wd=$!
    wait "$_cmd"
    _rc=$?
    kill "$_wd" 2>/dev/null || true
    wait "$_wd" 2>/dev/null || true
    return "$_rc"
}

_altaris_safe_rm_tree() {
    _p="$1"
    [ -e "$_p" ] || return 0
    _bak="${_p}.altaris_rm.$$"
    mv "$_p" "$_bak" 2>/dev/null || { rm -rf "$_p" 2>/dev/null || true; return 0; }
    rm -rf "$_bak" 2>/dev/null || true
}

_altaris_git_clone() {
    _url="$1"; _dest="$2"; _secs="${3:-90}"
    _altaris_safe_rm_tree "$_dest"
    mkdir -p "$(dirname "$_dest")"
    _altaris_run "$_secs" git clone --depth 1 --single-branch "$_url" "$_dest"
}

echo "Altaris: Installing Oh My Zsh..."
OMZ_OK=0
if [ -f "$USER_HOME/.oh-my-zsh/oh-my-zsh.sh" ]; then
    echo "Altaris: Oh My Zsh already valid — skip install"
    OMZ_OK=1
else
    if [ -e "$USER_HOME/.oh-my-zsh" ]; then
        echo "Altaris: Incomplete Oh My Zsh detected — removing (bounded)…"
        _altaris_safe_rm_tree "$USER_HOME/.oh-my-zsh"
    fi
    if [ "${ALTARIS_SKIP_OMZ:-0}" = "1" ]; then
        echo "Altaris: ALTARIS_SKIP_OMZ set but oh-my-zsh.sh missing — guest fallback"
    fi
    if command -v git >/dev/null 2>&1; then
        echo "Altaris: Shallow git clone Oh My Zsh (timeout 120s)…"
        if _altaris_git_clone "https://github.com/ohmyzsh/ohmyzsh.git" "$USER_HOME/.oh-my-zsh" 120 \
            && [ -f "$USER_HOME/.oh-my-zsh/oh-my-zsh.sh" ]; then
            echo "Altaris: Oh My Zsh installed"
            OMZ_OK=1
        else
            echo "Altaris: WARNING: Oh My Zsh clone failed or timed out — not treating as success"
            # Never leave a half tree, but never rm a good oh-my-zsh.sh either.
            if [ ! -f "$USER_HOME/.oh-my-zsh/oh-my-zsh.sh" ]; then
                _altaris_safe_rm_tree "$USER_HOME/.oh-my-zsh"
            else
                OMZ_OK=1
            fi
        fi
    else
        echo "Altaris: WARNING: git unavailable — Oh My Zsh skipped"
    fi
fi

if [ "$OMZ_OK" = "1" ]; then
    ZSH_CUSTOM="$USER_HOME/.oh-my-zsh/custom"
    mkdir -p "$ZSH_CUSTOM/plugins" "$ZSH_CUSTOM/themes" 2>/dev/null || true
    echo "Altaris: Installing Zsh plugins (if missing)…"
    if [ ! -d "$ZSH_CUSTOM/plugins/zsh-autosuggestions/.git" ] \
        && [ ! -f "$ZSH_CUSTOM/plugins/zsh-autosuggestions/zsh-autosuggestions.zsh" ]; then
        echo "Altaris: Shallow clone zsh-autosuggestions (timeout 60s)…"
        if ! _altaris_git_clone "https://github.com/zsh-users/zsh-autosuggestions.git" \
            "$ZSH_CUSTOM/plugins/zsh-autosuggestions" 60 \
            || [ ! -f "$ZSH_CUSTOM/plugins/zsh-autosuggestions/zsh-autosuggestions.zsh" ]; then
            echo "Altaris: WARNING: zsh-autosuggestions clone failed or timed out"
            [ -f "$ZSH_CUSTOM/plugins/zsh-autosuggestions/zsh-autosuggestions.zsh" ] \
                || _altaris_safe_rm_tree "$ZSH_CUSTOM/plugins/zsh-autosuggestions"
        fi
    else
        echo "Altaris: zsh-autosuggestions already present — skip"
    fi
    if [ ! -d "$ZSH_CUSTOM/plugins/zsh-syntax-highlighting/.git" ] \
        && [ ! -f "$ZSH_CUSTOM/plugins/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh" ]; then
        echo "Altaris: Shallow clone zsh-syntax-highlighting (timeout 60s)…"
        if ! _altaris_git_clone "https://github.com/zsh-users/zsh-syntax-highlighting.git" \
            "$ZSH_CUSTOM/plugins/zsh-syntax-highlighting" 60 \
            || [ ! -f "$ZSH_CUSTOM/plugins/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh" ]; then
            echo "Altaris: WARNING: zsh-syntax-highlighting clone failed or timed out"
            [ -f "$ZSH_CUSTOM/plugins/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh" ] \
                || _altaris_safe_rm_tree "$ZSH_CUSTOM/plugins/zsh-syntax-highlighting"
        fi
    else
        echo "Altaris: zsh-syntax-highlighting already present — skip"
    fi
    # Local monochrome prompt theme — no remote download (old colorful theme removed).
    rm -f "$ZSH_CUSTOM/themes/agnosterzak.zsh-theme" 2>/dev/null || true
    if [ ! -s "$ZSH_CUSTOM/themes/altaris.zsh-theme" ]; then
        echo "Altaris: Installing altaris prompt theme…"
        cat > "$ZSH_CUSTOM/themes/altaris.zsh-theme" <<'ALTARIS_THEME_EOF'
# altaris prompt — Altaris's minimal monochrome prompt.
# White/gray on black only; ASCII-safe (no powerline glyphs, no ANSI colors 31-44).
# Segments:  altaris  host  working-dir  input-char
PROMPT='%F{79}altaris%f%F{244}@%m%f %F{110}%~%f %F{255}%#%f '
PROMPT2='%F{240}> %f'
ALTARIS_THEME_EOF
        if [ ! -s "$ZSH_CUSTOM/themes/altaris.zsh-theme" ]; then
            echo "Altaris: WARNING: altaris prompt theme write failed — zsh keeps default prompt"
        fi
    else
        echo "Altaris: altaris prompt theme already present — skip"
    fi
    _altaris_chown_r "$CUSTOM_USER:$CUSTOM_GROUP" "$USER_HOME/.oh-my-zsh"
else
    echo "Altaris: Skipping Zsh plugins (Oh My Zsh not installed)"
fi

# pokemon-colorscripts: try by default (60s budget). Skip only if explicitly 1.
if [ "${ALTARIS_SKIP_POKEMON:-0}" = "1" ]; then
    echo "Altaris: pokemon-colorscripts skip (ALTARIS_SKIP_POKEMON=1)"
elif command -v pokemon-colorscripts >/dev/null 2>&1; then
    echo "Altaris: pokemon-colorscripts already present — skip"
elif ! command -v git >/dev/null 2>&1; then
    echo "Altaris: pokemon-colorscripts skip (git missing)"
else
    echo "Altaris: Installing pokemon-colorscripts (optional, 60s budget)…"
    POKEMON_TEMP="/tmp/pokemon-colorscripts.$$"
    _altaris_safe_rm_tree "$POKEMON_TEMP"
    if _altaris_git_clone "https://gitlab.com/phoneybadger/pokemon-colorscripts.git" "$POKEMON_TEMP" 60 \
        && [ -f "$POKEMON_TEMP/install.sh" ]; then
        (cd "$POKEMON_TEMP" && _altaris_run 30 sh ./install.sh) \
            || echo "Altaris: WARNING: pokemon-colorscripts install.sh failed"
        if command -v pokemon-colorscripts >/dev/null 2>&1; then
            echo "Altaris: pokemon-colorscripts installed"
        else
            echo "Altaris: WARNING: pokemon-colorscripts not on PATH after install.sh"
        fi
    else
        echo "Altaris: WARNING: pokemon-colorscripts skipped (clone timeout/fail)"
    fi
    _altaris_safe_rm_tree "$POKEMON_TEMP"
fi

echo "Altaris: Configuring .zshrc..."
ZSHRC="$USER_HOME/.zshrc"
cat > "$ZSHRC" << 'ZSHEOF'
# Guest PATH only — never inherit host PREFIX/bin (nested proot glue errors).
export PATH="$HOME/.local/bin:/opt/nodejs/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

if [ -x /usr/local/sbin/altaris-ensure-locale ] && command -v sudo >/dev/null 2>&1; then
  sudo /usr/local/sbin/altaris-ensure-locale 2>/dev/null || true
fi
_have=$(locale -a 2>/dev/null || true)
_pick=""
for _c in en_US.UTF-8 en_US.utf8 C.UTF-8 C.utf8; do
  echo "$_have" | grep -qxFi "$_c" && { _pick="$_c"; break; }
done
if [ -n "$_pick" ]; then
  export LANG="$_pick" LC_ALL="$_pick"
else
  unset LC_ALL
  export LANG=C
  echo "Altaris: WARNING: no UTF-8 locale in locale -a; zsh staying LANG=C" >&2
fi
unset _have _c _pick
export PYTHONIOENCODING=UTF-8

unset PROOT_TMP_DIR
export TMPDIR="${TMPDIR:-/tmp}"
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/tmp}"

# proot does not implement tcsetpgrp — zsh job control would ENOSYS and the
# shell can get SIGTTIN-killed. Disable MONITOR (job control) for the guest.
setopt no_monitor

# Launch sticker + info block: ~/.config/altaris/banner.sh (sourced at the end).
# pokemon-colorscripts stays installed but no longer auto-prints (old style).

export ZSH="${ZSH:-$HOME/.oh-my-zsh}"
if [ -f "$ZSH/oh-my-zsh.sh" ]; then
  ZSH_THEME="altaris"
  DISABLE_UPDATE_PROMPT=true
  DISABLE_AUTO_UPDATE=true
  ZSH_DISABLE_COMPFIX=true
  plugins=(git zsh-autosuggestions zsh-syntax-highlighting)
  source "$ZSH/oh-my-zsh.sh"
fi

# Package managers need root. NOPASSWD sudo is configured for flux.
if command -v sudo >/dev/null 2>&1; then
  if command -v apt-get >/dev/null 2>&1; then
    apt-get() { command sudo apt-get "$@"; }
  fi
  if command -v apt >/dev/null 2>&1; then
    apt() { command sudo apt "$@"; }
  fi
  if command -v pacman >/dev/null 2>&1; then
    pacman() { command sudo pacman "$@"; }
  fi
  if command -v dnf >/dev/null 2>&1; then
    dnf() { command sudo dnf "$@"; }
  fi
  if command -v dnf5 >/dev/null 2>&1; then
    dnf5() { command sudo dnf5 "$@"; }
  fi
  if command -v xbps-install >/dev/null 2>&1; then
    xbps-install() { command sudo xbps-install "$@"; }
  fi
  if command -v zypper >/dev/null 2>&1; then
    zypper() { command sudo zypper "$@"; }
  fi
  if command -v apk >/dev/null 2>&1; then
    apk() { command sudo apk "$@"; }
  fi
fi
# ── Altaris launch sticker (random truecolor anime banner; opt out ALTARIS_NO_BANNER=1)
[ -r "$HOME/.config/altaris/banner.sh" ] && . "$HOME/.config/altaris/banner.sh"

ZSHEOF
_altaris_chown "$CUSTOM_USER:$CUSTOM_GROUP" "$ZSHRC"
ZPROFILE="$USER_HOME/.zprofile"
if [ ! -f "$ZPROFILE" ]; then
    printf '%s\n' '[[ -o interactive ]] || { [ -f "$HOME/.zshrc" ] && . "$HOME/.zshrc"; }' \
        > "$ZPROFILE"
    _altaris_chown "$CUSTOM_USER:$CUSTOM_GROUP" "$ZPROFILE"
fi

# ── Altaris launch stickers: 49 truecolor anime half-block designs ─
# Installs ~/.config/altaris/banner/*.txt (random pick per shell open) and
# ~/.config/altaris/banner.sh (the altaris_banner function sourced by .zshrc/.bashrc).
# Offline, idempotent (`.banner-rev` refreshes existing installs).
# Opt out at runtime: ALTARIS_NO_BANNER=1.
_altaris_write_altaris_banner() {
    _home="$1"
    _owner="${2:-}"
    [ -n "$_home" ] || return 0
    _bdir="$_home/.config/altaris/banner"
    _brev="9-l2"
    _need=0
    case "$(cat "$_bdir/.banner-rev" 2>/dev/null || true)" in
        9-*) ;;
        *) _need=1 ;;
    esac
    for _b in 01-catgirl 02-catgirl-teal 03-catgirl-violet 04-arch-tan 05-arch-tan-violet 06-icecat 07-icecat-blush 08-maidfull 09-maidfull-rose 10-maidfull-lime 11-fandom 12-fandom-teal 13-fox 14-fox-sky 15-godette 16-godette-violet 17-godette-gobot 18-godette-gobot-mint 19-newsboy 20-newsboy-rose 21-newsboy-violet 22-rational 23-rational-indigo 24-lufthan 25-lufthan-teal 26-lufthan-violet 27-publicq 28-publicq-blue 29-publicq-violet 30-chibisq 31-chibisq-cyan 32-chibisq-amber 33-loveweb 34-loveweb-teal 35-kitsune 36-kitsune-jade 37-kitsune-azure 38-animegirl 39-animegirl-teal 40-animegirl-lime 41-moemiku 42-moemiku-azure 43-moemiku-blush 44-afrowikipe 45-afrowikipe-indigo 46-vocaloid 47-vocaloid-azure 48-vocaloid-blush 49-blossom; do
        [ -s "$_bdir/$_b.txt" ] || _need=1
    done
    [ -s "$_home/.config/altaris/banner.sh" ] || _need=1
    grep -q "altaris-banner v9" "$_home/.config/altaris/banner.sh" 2>/dev/null || _need=1
    [ "$_need" = "1" ] || return 0

    mkdir -p "$_bdir" 2>/dev/null || return 0
    rm -f "$_bdir"/*.txt 2>/dev/null || true
    cat > "$_bdir/.altaris-banners.$$" <<'ALTARIS_BANNER_ALL'
==>01-catgirl
\033[48;2;0;0;0m              \033[38;2;0;0;0;48;2;46;32;21m▀▀ \033[38;2;46;32;21;48;2;0;0;0m▀\033[48;2;46;32;21m \033[38;2;86;53;28;48;2;194;115;54m▀\033[48;2;202;107;38m▀\033[38;2;46;32;21;48;2;172;87;29m▀\033[38;2;122;90;61;48;2;201;138;85m▀\033[38;2;201;181;159;48;2;160;165;163m▀\033[38;2;123;127;125;48;2;46;32;21m▀\033[38;2;46;32;21;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m              \033[38;2;46;32;21m▀▀▀\033[48;2;138;82;37m▀\033[38;2;166;106;57;48;2;225;138;61m▀\033[48;2;194;115;54m \033[38;2;194;115;54;48;2;225;138;61m▀ \033[38;2;172;87;29m▀\033[38;2;123;59;19;48;2;172;87;29m▀\033[48;2;123;59;19m \033[38;2;46;32;21;48;2;86;53;28m▀\033[0m
\033[48;2;0;0;0m                \033[38;2;0;0;0;48;2;138;82;37m▀\033[38;2;202;107;38;48;2;194;115;54m▀\033[38;2;166;106;57;48;2;124;112;98m▀\033[38;2;194;115;54;48;2;166;106;57m▀\033[38;2;225;138;61;48;2;194;115;54m▀\033[38;2;202;107;38;48;2;166;106;57m▀\033[38;2;225;138;61;48;2;194;115;54m▀▀\033[38;2;172;87;29m▀\033[38;2;86;53;28;48;2;123;59;19m▀\033[0m
\033[48;2;0;0;0m           \033[38;2;0;0;0;48;2;46;32;21m▀\033[38;2;46;32;21;48;2;104;102;96m▀\033[48;2;46;32;21m \033[48;2;0;0;0m \033[38;2;0;0;0;48;2;46;32;21m▀\033[38;2;95;73;53;48;2;154;127;100m▀\033[38;2;124;112;98;48;2;66;62;55m▀\033[38;2;123;127;125;48;2;160;165;163m▀\033[38;2;138;82;37;48;2;201;181;159m▀\033[38;2;213;169;127;48;2;231;201;165m▀\033[38;2;159;146;130;48;2;123;127;125m▀\033[38;2;138;82;37;48;2;124;112;98m▀\033[38;2;194;115;54;48;2;202;107;38m▀\033[48;2;172;87;29m \033[38;2;95;73;53;48;2;86;53;28m▀\033[0m
\033[48;2;0;0;0m          \033[38;2;0;0;0;48;2;166;106;57m▀\033[38;2;166;106;57;48;2;154;127;100m▀\033[38;2;104;102;96;48;2;81;88;90m▀\033[38;2;0;0;0;48;2;46;32;21m▀▀\033[38;2;95;73;53;48;2;123;59;19m▀\033[38;2;201;181;159;48;2;216;157;103m▀\033[38;2;123;127;125;48;2;231;201;165m▀ \033[38;2;247;224;195m▀ \033[38;2;123;127;125m▀\033[38;2;159;146;130;48;2;213;169;127m▀\033[38;2;172;87;29;48;2;166;106;57m▀\033[38;2;166;106;57;48;2;86;53;28m▀\033[0m
\033[48;2;0;0;0m        \033[38;2;0;0;0;48;2;46;32;21m▀\033[38;2;95;73;53m▀\033[38;2;194;115;54m▀\033[38;2;81;88;90m▀\033[38;2;46;32;21;48;2;0;0;0m▀▀\033[48;2;123;59;19m \033[38;2;124;112;98;48;2;122;90;61m▀\033[38;2;154;127;100;48;2;46;32;21m▀\033[38;2;213;169;127;48;2;95;73;53m▀\033[38;2;231;201;165;48;2;201;181;159m▀\033[48;2;213;169;127m \033[38;2;247;224;195;48;2;166;106;57m▀\033[38;2;213;169;127;48;2;0;0;0m▀\033[38;2;86;53;28m▀\033[38;2;81;88;90;48;2;46;32;21m▀\033[38;2;66;62;55m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;0;0;0;48;2;46;32;21m▀\033[48;2;201;138;85m▀\033[38;2;46;32;21;48;2;123;59;19m▀\033[48;2;46;32;21m \033[48;2;0;0;0m         \033[38;2;95;73;53m▀\033[38;2;166;106;57;48;2;95;73;53m▀\033[38;2;46;32;21;48;2;202;107;38m▀\033[38;2;0;0;0;48;2;172;87;29m▀\033[48;2;122;90;61m▀\033[48;2;154;127;100m▀\033[38;2;46;32;21;48;2;201;181;159m▀\033[38;2;66;62;55;48;2;247;224;195m▀\033[38;2;81;88;90;48;2;245;235;222m▀\033[38;2;66;62;55m▀\033[38;2;46;32;21;48;2;201;181;159m▀\033[38;2;0;0;0;48;2;46;32;21m▀\033[0m
\033[48;2;0;0;0m   \033[38;2;0;0;0;48;2;122;90;61m▀\033[38;2;124;112;98;48;2;247;224;195m▀\033[38;2;247;224;195;48;2;216;157;103m▀\033[38;2;213;169;127;48;2;86;53;28m▀\033[48;2;0;0;0m    \033[38;2;0;0;0;48;2;46;32;21m▀\033[38;2;46;32;21;48;2;0;0;0m▀       \033[38;2;86;53;28m▀\033[38;2;166;106;57;48;2;46;32;21m▀\033[38;2;123;59;19m▀\033[38;2;138;82;37;48;2;86;53;28m▀\033[38;2;122;90;61;48;2;245;235;222m▀\033[38;2;138;82;37;48;2;81;88;90m▀\033[38;2;201;138;85;48;2;0;0;0m▀\033[38;2;231;201;165;48;2;122;90;61m▀\033[38;2;245;235;222;48;2;213;169;127m▀\033[38;2;66;62;55;48;2;86;53;28m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;46;32;21;48;2;86;53;28m▀\033[48;2;201;138;85m \033[38;2;194;115;54m▀\033[38;2;172;87;29;48;2;201;181;159m▀\033[38;2;0;0;0;48;2;124;112;98m▀\033[38;2;46;32;21;48;2;95;73;53m▀\033[48;2;124;112;98m▀\033[38;2;66;62;55;48;2;201;181;159m▀\033[38;2;46;32;21;48;2;95;73;53m▀\033[48;2;0;0;0m   \033[38;2;0;0;0;48;2;46;32;21m▀\033[48;2;95;73;53m▀\033[48;2;86;53;28m▀\033[48;2;46;32;21m▀▀▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;46;32;21m▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;46;32;21m▀\033[38;2;216;211;198;48;2;245;235;222m▀\033[38;2;231;201;165;48;2;122;90;61m▀\033[38;2;122;90;61;48;2;201;138;85m▀\033[38;2;0;0;0;48;2;95;73;53m▀\033[38;2;95;73;53;48;2;0;0;0m▀\033[38;2;86;53;28;48;2;46;32;21m▀\033[38;2;46;32;21;48;2;0;0;0m▀\033[0m
\033[38;2;0;0;0;48;2;46;32;21m▀\033[38;2;46;32;21;48;2;138;82;37m▀\033[38;2;138;82;37;48;2;225;138;61m▀\033[38;2;225;138;61;48;2;202;107;38m▀\033[38;2;194;115;54;48;2;216;157;103m▀\033[38;2;231;201;165;48;2;194;115;54m▀\033[38;2;159;146;130;48;2;0;0;0m▀\033[48;2;124;112;98m▀\033[38;2;123;127;125;48;2;245;235;222m▀\033[48;2;124;112;98m▀\033[38;2;160;165;163;48;2;104;102;96m▀\033[48;2;122;90;61m \033[38;2;86;53;28;48;2;46;32;21m▀\033[38;2;95;73;53;48;2;66;62;55m▀\033[38;2;66;62;55;48;2;0;0;0m▀    \033[38;2;46;32;21m▀\033[38;2;0;0;0;48;2;46;32;21m▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;46;32;21m▀\033[38;2;123;127;125;48;2;245;235;222m▀\033[38;2;231;201;165;48;2;159;146;130m▀\033[48;2;0;0;0m \033[38;2;86;53;28;48;2;46;32;21m▀\033[38;2;138;82;37m▀\033[0m
\033[38;2;46;32;21;48;2;123;59;19m▀\033[38;2;202;107;38;48;2;225;138;61m▀\033[38;2;225;138;61;48;2;216;157;103m▀\033[38;2;194;115;54;48;2;213;169;127m▀\033[38;2;231;201;165m▀\033[38;2;166;106;57;48;2;201;138;85m▀\033[38;2;0;0;0;48;2;46;32;21m▀ \033[38;2;216;211;198;48;2;123;127;125m▀\033[48;2;245;235;222m \033[38;2;201;181;159m▀\033[48;2;247;224;195m▀\033[38;2;86;53;28;48;2;231;201;165m▀\033[38;2;0;0;0;48;2;124;112;98m▀\033[48;2;0;0;0m       \033[48;2;46;32;21m \033[38;2;231;201;165;48;2;154;127;100m▀\033[38;2;247;224;195;48;2;122;90;61m▀\033[38;2;46;32;21;48;2;0;0;0m▀ \033[48;2;46;32;21m \033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;46;32;21m▀\033[0m
\033[48;2;166;106;57m \033[38;2;225;138;61;48;2;216;157;103m▀\033[38;2;201;181;159;48;2;173;215;241m▀\033[48;2;201;181;159m \033[38;2;213;169;127;48;2;247;224;195m▀\033[38;2;216;157;103;48;2;213;169;127m▀\033[38;2;46;32;21;48;2;166;106;57m▀\033[38;2;66;62;55;48;2;0;0;0m▀\033[38;2;95;73;53;48;2;66;62;55m▀\033[38;2;159;146;130;48;2;95;73;53m▀\033[38;2;245;235;222;48;2;159;146;130m▀\033[38;2;231;201;165;48;2;216;211;198m▀\033[38;2;173;215;241;48;2;213;228;233m▀\033[38;2;213;228;233;48;2;173;215;241m▀\033[38;2;159;146;130m▀\033[38;2;46;32;21;48;2;216;211;198m▀\033[38;2;0;0;0;48;2;95;73;53m▀\033[48;2;0;0;0m    \033[38;2;0;0;0;48;2;46;32;21m▀\033[48;2;0;0;0m \033[38;2;95;73;53m▀\033[38;2;86;53;28m▀ \033[38;2;0;0;0;48;2;46;32;21m▀\033[38;2;46;32;21;48;2;0;0;0m▀\033[0m
\033[38;2;138;82;37;48;2;86;53;28m▀\033[48;2;213;169;127m \033[38;2;173;215;241;48;2;166;193;206m▀\033[38;2;216;211;198;48;2;66;62;55m▀\033[48;2;154;127;100m▀\033[38;2;213;228;233;48;2;201;181;159m▀\033[38;2;213;169;127;48;2;46;32;21m▀\033[38;2;154;127;100;48;2;216;157;103m▀\033[38;2;0;0;0;48;2;160;165;163m▀\033[48;2;46;32;21m \033[38;2;66;62;55;48;2;0;0;0m▀▀\033[38;2;123;127;125m▀\033[38;2;160;165;163m▀\033[38;2;166;193;206m▀\033[48;2;46;32;21m▀\033[38;2;231;201;165;48;2;66;62;55m▀\033[38;2;154;127;100;48;2;159;146;130m▀\033[38;2;0;0;0;48;2;95;73;53m▀\033[48;2;0;0;0m   \033[38;2;0;0;0;48;2;46;32;21m▀\033[48;2;0;0;0m \033[38;2;46;32;21m▀\033[0m
\033[48;2;0;0;0m \033[38;2;194;115;54;48;2;46;32;21m▀\033[38;2;154;127;100;48;2;122;90;61m▀\033[38;2;0;0;0;48;2;66;62;55m▀\033[38;2;46;32;21;48;2;0;0;0m▀▀  \033[38;2;213;169;127;48;2;46;32;21m▀\033[38;2;247;224;195;48;2;95;73;53m▀\033[48;2;160;165;163m \033[38;2;104;102;96;48;2;216;211;198m▀\033[38;2;66;62;55;48;2;213;228;233m▀\033[48;2;160;165;163m▀\033[48;2;46;32;21m \033[48;2;0;0;0m   \033[38;2;46;32;21m▀\033[48;2;46;32;21m \033[38;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m   \033[38;2;46;32;21m▀       \033[38;2;95;73;53m▀\033[38;2;154;127;100m▀\033[48;2;46;32;21m \033[38;2;0;0;0m▀▀▀▀\033[48;2;0;0;0m  \033[38;2;95;73;53m▀\033[48;2;46;32;21m▀\033[38;2;0;0;0m▀\033[0m
==>02-catgirl-teal
\033[48;2;0;0;0m                \033[38;2;0;0;0;48;2;20;23;39m▀\033[48;2;18;19;28m▀▀\033[48;2;20;23;39m▀▀\033[48;2;18;19;28m▀\033[48;2;37;42;71m▀\033[48;2;81;94;162m▀\033[48;2;94;98;126m▀\033[48;2;18;19;28m▀\033[0m
\033[48;2;0;0;0m              \033[48;2;18;19;28m \033[38;2;20;23;39m▀ \033[38;2;0;0;0m▀\033[38;2;18;19;28;48;2;48;57;101m▀\033[38;2;52;71;167;48;2;54;76;198m▀\033[38;2;54;76;198;48;2;58;81;225m▀\033[38;2;51;65;131;48;2;89;104;201m▀\033[38;2;121;134;209;48;2;51;65;131m▀\033[38;2;170;162;172;48;2;37;42;71m▀\033[38;2;62;61;70;48;2;21;28;62m▀\033[38;2;0;0;0;48;2;18;19;28m▀\033[0m
\033[48;2;0;0;0m             \033[38;2;18;19;28m▀  ▀\033[38;2;48;57;101;48;2;54;76;198m▀\033[38;2;89;104;201;48;2;52;71;167m▀\033[38;2;54;76;198;48;2;94;98;126m▀\033[38;2;58;81;225;48;2;89;104;201m▀\033[38;2;89;104;201;48;2;81;94;162m▀\033[38;2;94;98;126;48;2;121;134;209m▀\033[38;2;48;57;101;48;2;89;104;201m▀\033[38;2;51;65;131;48;2;52;71;167m▀\033[38;2;37;42;71;48;2;48;57;101m▀\033[0m
\033[48;2;0;0;0m                \033[38;2;37;42;71;48;2;48;57;101m▀\033[38;2;81;94;162;48;2;94;98;126m▀\033[38;2;83;86;103;48;2;132;133;141m▀\033[48;2;94;98;126m \033[38;2;81;94;162;48;2;132;133;141m▀\033[38;2;52;71;167m▀\033[48;2;51;65;131m▀\033[38;2;89;104;201;48;2;81;94;162m▀\033[38;2;81;94;162;48;2;52;71;167m▀\033[48;2;48;57;101m \033[0m
\033[48;2;0;0;0m           \033[38;2;0;0;0;48;2;48;57;101m▀\033[38;2;48;57;101;48;2;81;94;162m▀\033[38;2;20;23;39;48;2;18;19;28m▀\033[48;2;0;0;0m \033[38;2;20;23;39;48;2;36;36;44m▀\033[38;2;83;86;103;48;2;170;162;172m▀\033[38;2;48;57;101;48;2;51;65;131m▀\033[38;2;132;133;141;48;2;198;180;169m▀\033[48;2;230;204;174m▀\033[38;2;230;204;174;48;2;198;180;169m▀\033[38;2;174;157;148;48;2;51;65;131m▀\033[38;2;94;98;126;48;2;132;133;141m▀\033[48;2;52;71;167m \033[38;2;51;65;131m▀\033[38;2;62;61;70;48;2;20;23;39m▀\033[0m
\033[48;2;0;0;0m         \033[38;2;0;0;0;48;2;36;36;44m▀\033[38;2;37;42;71;48;2;89;104;201m▀\033[38;2;89;104;201;48;2;83;86;103m▀\033[38;2;83;86;103;48;2;36;36;44m▀\033[38;2;18;19;28;48;2;20;23;39m▀\033[48;2;21;28;62m▀\033[48;2;62;61;70m \033[38;2;198;180;169;48;2;174;157;148m▀\033[48;2;198;180;169m \033[48;2;230;204;174m \033[38;2;230;204;174;48;2;174;157;148m▀\033[48;2;230;204;174m \033[38;2;135;142;168m▀\033[48;2;94;98;126m▀\033[38;2;52;71;167;48;2;83;86;103m▀\033[38;2;51;65;131;48;2;36;36;44m▀\033[0m
\033[48;2;0;0;0m        \033[38;2;0;0;0;48;2;36;36;44m▀\033[38;2;36;36;44;48;2;18;19;28m▀\033[38;2;37;42;71;48;2;0;0;0m▀\033[48;2;18;19;28m▀\033[48;2;0;0;0m \033[38;2;20;23;39m▀\033[38;2;48;57;101;48;2;18;19;28m▀\033[38;2;94;98;126m▀\033[38;2;83;86;103;48;2;0;0;0m▀\033[38;2;174;157;148;48;2;18;19;28m▀\033[38;2;230;204;174;48;2;132;133;141m▀\033[38;2;198;180;169;48;2;81;94;162m▀\033[38;2;170;162;172;48;2;21;28;62m▀\033[38;2;62;61;70;48;2;0;0;0m▀ ▀\033[38;2;37;42;71m▀ \033[38;2;0;0;0;48;2;18;19;28m▀\033[0m
\033[48;2;0;0;0m    \033[38;2;0;0;0;48;2;18;19;28m▀\033[48;2;135;142;168m▀\033[38;2;21;28;62;48;2;169;187;215m▀\033[38;2;48;57;101;48;2;36;36;44m▀\033[48;2;18;19;28m \033[48;2;0;0;0m         \033[38;2;20;23;39m▀\033[38;2;52;71;167;48;2;18;19;28m▀\033[38;2;51;65;131;48;2;52;71;167m▀\033[38;2;21;28;62;48;2;54;76;198m▀\033[38;2;37;42;71;48;2;52;71;167m▀\033[38;2;83;86;103;48;2;81;94;162m▀\033[38;2;132;133;141;48;2;89;104;201m▀\033[38;2;198;180;169;48;2;144;159;215m▀\033[38;2;204;198;195;48;2;219;232;238m▀\033[38;2;198;180;169;48;2;250;241;225m▀\033[38;2;94;98;126;48;2;219;232;238m▀\033[38;2;18;19;28;48;2;36;36;44m▀\033[0m
\033[48;2;0;0;0m   \033[38;2;18;19;28;48;2;132;133;141m▀\033[38;2;204;198;195;48;2;144;159;215m▀\033[38;2;219;232;238;48;2;52;71;167m▀\033[38;2;51;65;131;48;2;18;19;28m▀\033[38;2;0;0;0;48;2;20;23;39m▀\033[38;2;18;19;28;48;2;0;0;0m▀\033[48;2;18;19;28m \033[38;2;0;0;0m▀▀\033[38;2;36;36;44;48;2;0;0;0m▀       \033[38;2;18;19;28m▀\033[38;2;48;57;101m▀\033[38;2;21;28;62m▀\033[38;2;48;57;101;48;2;18;19;28m▀\033[38;2;135;142;168;48;2;250;241;225m▀\033[38;2;21;28;62;48;2;135;142;168m▀\033[38;2;48;57;101;48;2;0;0;0m▀\033[38;2;121;134;209;48;2;20;23;39m▀\033[38;2;198;213;237;48;2;89;104;201m▀\033[48;2;37;42;71m \033[38;2;0;0;0;48;2;18;19;28m▀\033[0m
\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;18;19;28m▀\033[38;2;20;23;39;48;2;48;57;101m▀\033[48;2;89;104;201m \033[38;2;52;71;167;48;2;54;76;198m▀\033[38;2;81;94;162;48;2;227;216;210m▀\033[38;2;18;19;28;48;2;170;162;172m▀\033[38;2;20;23;39;48;2;94;98;126m▀\033[38;2;62;61;70;48;2;123;115;114m▀\033[38;2;94;98;126;48;2;169;187;215m▀\033[38;2;62;61;70;48;2;123;115;114m▀\033[38;2;0;0;0;48;2;18;19;28m▀▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;62;61;70m▀\033[38;2;18;19;28m▀\033[48;2;36;36;44m▀\033[48;2;20;23;39m▀▀\033[38;2;0;0;0m▀\033[48;2;18;19;28m▀\033[48;2;20;23;39m▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;62;61;70m▀\033[38;2;230;204;174;48;2;227;216;210m▀\033[38;2;144;159;215;48;2;36;36;44m▀\033[38;2;89;104;201;48;2;81;94;162m▀\033[38;2;18;19;28;48;2;83;86;103m▀\033[38;2;21;28;62;48;2;0;0;0m▀\033[38;2;20;23;39m▀\033[38;2;36;36;44m▀\033[0m
\033[38;2;18;19;28;48;2;36;36;44m▀\033[38;2;21;28;62;48;2;51;65;131m▀\033[38;2;52;71;167;48;2;58;81;225m▀\033[38;2;58;81;225;48;2;54;76;198m▀\033[38;2;54;76;198;48;2;144;159;215m▀\033[38;2;144;159;215;48;2;52;71;167m▀\033[38;2;83;86;103;48;2;0;0;0m▀\033[38;2;132;133;141;48;2;83;86;103m▀\033[38;2;170;162;172;48;2;250;241;225m▀\033[38;2;83;86;103;48;2;170;162;172m▀\033[38;2;170;162;172;48;2;83;86;103m▀\033[38;2;123;115;114m▀\033[38;2;62;61;70;48;2;18;19;28m▀\033[38;2;83;86;103m▀\033[38;2;20;23;39m▀\033[48;2;0;0;0m   \033[38;2;0;0;0;48;2;18;19;28m▀\033[38;2;18;19;28;48;2;0;0;0m▀\033[48;2;18;19;28m \033[38;2;0;0;0m▀\033[48;2;62;61;70m▀\033[38;2;174;157;148;48;2;250;241;225m▀\033[38;2;198;180;169;48;2;123;115;114m▀\033[48;2;0;0;0m \033[38;2;21;28;62;48;2;20;23;39m▀\033[38;2;48;57;101;48;2;18;19;28m▀\033[0m
\033[38;2;37;42;71;48;2;48;57;101m▀\033[48;2;58;81;225m \033[38;2;58;81;225;48;2;121;134;209m▀\033[38;2;89;104;201;48;2;144;159;215m▀\033[38;2;169;187;215;48;2;121;134;209m▀\033[38;2;81;94;162;48;2;89;104;201m▀\033[38;2;0;0;0;48;2;18;19;28m▀\033[48;2;37;42;71m▀\033[38;2;204;198;195;48;2;83;86;103m▀\033[38;2;219;232;238;48;2;227;216;210m▀\033[38;2;227;216;210;48;2;250;241;225m▀\033[48;2;230;204;174m▀\033[38;2;94;98;126;48;2;227;216;210m▀\033[38;2;0;0;0;48;2;135;142;168m▀\033[48;2;18;19;28m▀\033[48;2;0;0;0m      \033[38;2;36;36;44;48;2;18;19;28m▀\033[38;2;227;216;210;48;2;62;61;70m▀\033[38;2;198;180;169m▀\033[38;2;0;0;0;48;2;18;19;28m▀\033[38;2;18;19;28;48;2;0;0;0m▀\033[48;2;18;19;28m \033[48;2;0;0;0m \033[48;2;18;19;28m  \033[0m
\033[48;2;81;94;162m \033[48;2;89;104;201m \033[38;2;144;159;215;48;2;198;213;237m▀\033[48;2;144;159;215m \033[38;2;169;187;215;48;2;198;213;237m▀\033[38;2;89;104;201;48;2;169;187;215m▀\033[38;2;37;42;71;48;2;81;94;162m▀\033[38;2;36;36;44;48;2;18;19;28m▀\033[38;2;62;61;70;48;2;36;36;44m▀\033[38;2;94;98;126;48;2;83;86;103m▀\033[38;2;250;241;225;48;2;123;115;114m▀\033[38;2;230;204;174;48;2;204;198;195m▀\033[38;2;198;213;237;48;2;219;232;238m▀\033[48;2;198;213;237m \033[38;2;144;159;215m▀\033[38;2;48;57;101m▀\033[38;2;0;0;0;48;2;123;115;114m▀\033[48;2;0;0;0m    \033[48;2;18;19;28m \033[48;2;0;0;0m \033[38;2;62;61;70m▀\033[38;2;37;42;71m▀\033[38;2;0;0;0;48;2;18;19;28m▀▀\033[38;2;18;19;28;48;2;0;0;0m▀\033[0m
\033[38;2;51;65;131;48;2;37;42;71m▀\033[38;2;144;159;215;48;2;121;134;209m▀\033[38;2;219;232;238;48;2;135;142;168m▀\033[38;2;169;187;215;48;2;36;36;44m▀\033[48;2;94;98;126m▀\033[38;2;219;232;238;48;2;174;157;148m▀\033[38;2;121;134;209;48;2;18;19;28m▀\033[38;2;135;142;168;48;2;81;94;162m▀\033[38;2;18;19;28;48;2;204;198;195m▀\033[38;2;0;0;0;48;2;83;86;103m▀\033[38;2;36;36;44;48;2;0;0;0m▀▀\033[38;2;83;86;103m▀\033[38;2;132;133;141m▀\033[38;2;135;142;168m▀\033[38;2;170;162;172m▀\033[38;2;230;204;174;48;2;36;36;44m▀\033[38;2;174;157;148;48;2;123;115;114m▀\033[38;2;18;19;28;48;2;83;86;103m▀\033[48;2;0;0;0m  \033[38;2;18;19;28m▀\033[48;2;18;19;28m  \033[38;2;18;19;28;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m \033[38;2;51;65;131m▀\033[38;2;94;98;126;48;2;83;86;103m▀\033[38;2;0;0;0;48;2;62;61;70m▀\033[48;2;0;0;0m \033[48;2;18;19;28m \033[38;2;0;0;0m▀\033[38;2;18;19;28;48;2;0;0;0m▀\033[38;2;121;134;209;48;2;18;19;28m▀\033[38;2;198;213;237;48;2;21;28;62m▀\033[38;2;169;187;215;48;2;135;142;168m▀\033[38;2;132;133;141;48;2;198;213;237m▀\033[38;2;83;86;103m▀\033[48;2;135;142;168m▀\033[38;2;36;36;44;48;2;18;19;28m▀\033[48;2;0;0;0m   \033[38;2;18;19;28m▀\033[48;2;18;19;28m \033[38;2;0;0;0;48;2;36;36;44m▀\033[48;2;18;19;28m▀\033[0m
\033[48;2;0;0;0m   \033[38;2;36;36;44m▀\033[48;2;18;19;28m \033[48;2;0;0;0m      \033[38;2;37;42;71;48;2;18;19;28m▀\033[38;2;94;98;126;48;2;0;0;0m▀\033[38;2;18;19;28;48;2;20;23;39m▀\033[38;2;0;0;0;48;2;21;28;62m▀\033[48;2;20;23;39m▀\033[48;2;18;19;28m▀▀\033[48;2;0;0;0m  \033[38;2;62;61;70m▀\033[48;2;18;19;28m▀\033[38;2;0;0;0;48;2;20;23;39m▀\033[0m
==>03-catgirl-violet
\033[48;2;0;0;0m                \033[38;2;0;0;0;48;2;30;17;28m▀▀▀\033[48;2;54;16;52m▀▀\033[48;2;30;17;28m▀\033[48;2;76;43;73m▀\033[48;2;134;121;133m▀▀\033[48;2;30;17;28m▀\033[0m
\033[48;2;0;0;0m              \033[48;2;30;17;28m   \033[38;2;0;0;0m▀\033[38;2;30;17;28;48;2;132;42;132m▀\033[38;2;160;43;164;48;2;215;47;224m▀\033[38;2;215;47;224;48;2;193;68;191m▀\033[38;2;160;43;164m▀\033[38;2;208;115;208;48;2;132;42;132m▀\033[38;2;171;181;193;48;2;76;43;73m▀\033[38;2;79;72;80;48;2;54;16;52m▀\033[38;2;0;0;0;48;2;30;17;28m▀\033[0m
\033[48;2;0;0;0m             \033[38;2;30;17;28m▀  ▀\033[38;2;100;42;97;48;2;160;43;164m▀\033[38;2;193;68;191m▀\033[38;2;160;43;164;48;2;166;124;143m▀\033[48;2;217;81;221m \033[38;2;217;81;221;48;2;193;68;191m▀\033[38;2;168;76;168;48;2;208;115;208m▀\033[38;2;132;42;132;48;2;193;68;191m▀\033[48;2;132;42;132m \033[48;2;76;43;73m \033[0m
\033[48;2;0;0;0m                \033[38;2;100;42;97;48;2;132;42;132m▀\033[38;2;168;76;168;48;2;166;124;143m▀\033[38;2;137;84;120m▀\033[48;2;132;42;132m▀\033[38;2;193;68;191;48;2;166;124;143m▀\033[38;2;168;76;168m▀\033[48;2;132;42;132m▀\033[38;2;193;68;191;48;2;168;76;168m▀ \033[48;2;100;42;97m \033[0m
\033[48;2;0;0;0m           \033[38;2;0;0;0;48;2;100;42;97m▀\033[38;2;102;79;94;48;2;134;121;133m▀\033[48;2;30;17;28m \033[48;2;0;0;0m \033[38;2;49;36;44;48;2;76;43;73m▀\033[38;2;102;79;94;48;2;205;160;179m▀\033[38;2;76;43;73;48;2;79;72;80m▀\033[48;2;173;158;165m \033[38;2;166;124;143;48;2;227;206;189m▀\033[48;2;219;183;175m \033[38;2;166;124;143;48;2;102;79;94m▀\033[38;2;137;84;120;48;2;134;121;133m▀\033[38;2;193;68;191;48;2;160;43;164m▀\033[38;2;132;42;132m▀\033[38;2;76;43;73;48;2;30;17;28m▀\033[0m
\033[48;2;0;0;0m         \033[38;2;0;0;0;48;2;76;43;73m▀\033[38;2;100;42;97;48;2;217;81;221m▀\033[38;2;193;68;191;48;2;137;84;120m▀\033[38;2;102;79;94;48;2;49;36;44m▀\033[38;2;30;17;28;48;2;54;16;52m▀▀\033[48;2;79;72;80m \033[38;2;205;160;179;48;2;166;124;143m▀\033[48;2;219;183;175m▀\033[48;2;227;206;189m \033[38;2;227;206;189;48;2;205;160;179m▀\033[38;2;219;183;175;48;2;227;206;189m▀\033[38;2;175;154;155m▀\033[48;2;137;84;120m▀\033[38;2;132;42;132;48;2;102;79;94m▀\033[38;2;100;42;97;48;2;49;36;44m▀\033[0m
\033[48;2;0;0;0m        \033[38;2;30;17;28;48;2;49;36;44m▀\033[38;2;54;16;52;48;2;30;17;28m▀\033[38;2;76;43;73;48;2;0;0;0m▀\033[38;2;49;36;44;48;2;30;17;28m▀\033[48;2;0;0;0m \033[38;2;54;16;52m▀\033[38;2;100;42;97;48;2;30;17;28m▀\033[38;2;134;121;133m▀\033[38;2;79;72;80;48;2;0;0;0m▀\033[38;2;175;154;155;48;2;30;17;28m▀\033[38;2;227;206;189;48;2;166;124;143m▀\033[38;2;219;183;175;48;2;168;76;168m▀\033[48;2;54;16;52m▀\033[38;2;76;43;73;48;2;0;0;0m▀ ▀\033[38;2;49;36;44m▀ \033[38;2;0;0;0;48;2;30;17;28m▀\033[0m
\033[48;2;0;0;0m    \033[38;2;0;0;0;48;2;30;17;28m▀\033[48;2;166;124;143m▀\033[38;2;54;16;52;48;2;214;161;224m▀\033[38;2;100;42;97;48;2;49;36;44m▀\033[48;2;30;17;28m \033[48;2;0;0;0m         \033[38;2;30;17;28m▀\033[38;2;160;43;164;48;2;30;17;28m▀\033[38;2;100;42;97;48;2;160;43;164m▀\033[38;2;76;43;73;48;2;193;68;191m▀\033[48;2;132;42;132m▀\033[38;2;79;72;80m▀\033[38;2;134;121;133;48;2;168;76;168m▀\033[38;2;173;158;165;48;2;214;161;224m▀\033[38;2;171;181;193;48;2;233;225;234m▀▀\033[38;2;137;84;120;48;2;227;206;189m▀\033[38;2;30;17;28;48;2;49;36;44m▀\033[0m
\033[48;2;0;0;0m   \033[38;2;30;17;28;48;2;166;124;143m▀\033[38;2;217;186;214;48;2;217;128;224m▀\033[38;2;233;225;234;48;2;193;68;191m▀\033[38;2;100;42;97;48;2;30;17;28m▀\033[38;2;0;0;0m▀\033[38;2;30;17;28;48;2;0;0;0m▀\033[38;2;0;0;0;48;2;30;17;28m▀▀▀\033[38;2;30;17;28;48;2;0;0;0m▀       ▀\033[38;2;76;43;73m▀\033[38;2;54;16;52m▀\033[38;2;132;42;132;48;2;30;17;28m▀\033[38;2;175;154;155;48;2;233;225;234m▀\033[38;2;54;16;52;48;2;176;128;186m▀\033[38;2;100;42;97;48;2;0;0;0m▀\033[38;2;208;115;208;48;2;30;17;28m▀\033[38;2;217;186;214;48;2;168;76;168m▀\033[38;2;49;36;44;48;2;76;43;73m▀\033[38;2;0;0;0;48;2;30;17;28m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;54;16;52;48;2;76;43;73m▀\033[38;2;193;68;191;48;2;217;81;221m▀\033[48;2;193;68;191m \033[38;2;160;43;164;48;2;227;206;189m▀\033[38;2;0;0;0;48;2;175;154;155m▀\033[38;2;54;16;52;48;2;137;84;120m▀\033[38;2;76;43;73;48;2;134;121;133m▀\033[38;2;134;121;133;48;2;205;160;179m▀\033[38;2;49;36;44;48;2;134;121;133m▀\033[38;2;0;0;0;48;2;30;17;28m▀▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;76;43;73m▀\033[38;2;30;17;28m▀\033[48;2;49;36;44m▀▀▀\033[38;2;0;0;0;48;2;54;16;52m▀\033[48;2;30;17;28m▀▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;76;43;73m▀\033[48;2;227;206;189m \033[38;2;217;128;224;48;2;49;36;44m▀\033[38;2;168;76;168;48;2;137;84;120m▀\033[38;2;0;0;0;48;2;102;79;94m▀\033[38;2;54;16;52;48;2;0;0;0m▀\033[38;2;30;17;28m▀▀\033[0m
\033[38;2;30;17;28;48;2;49;36;44m▀\033[38;2;54;16;52;48;2;160;43;164m▀\033[38;2;160;43;164;48;2;215;47;224m▀ \033[38;2;193;68;191;48;2;173;153;208m▀\033[38;2;208;115;208;48;2;160;43;164m▀\033[38;2;102;79;94;48;2;0;0;0m▀\033[38;2;134;121;133;48;2;79;72;80m▀\033[38;2;175;154;155;48;2;233;225;234m▀\033[38;2;102;79;94;48;2;175;154;155m▀\033[38;2;176;128;186;48;2;102;79;94m▀\033[38;2;134;121;133;48;2;137;84;120m▀\033[38;2;76;43;73;48;2;30;17;28m▀\033[38;2;102;79;94m▀ \033[48;2;0;0;0m   \033[38;2;0;0;0;48;2;30;17;28m▀\033[38;2;30;17;28;48;2;0;0;0m▀\033[48;2;30;17;28m \033[38;2;0;0;0m▀\033[48;2;79;72;80m▀\033[38;2;175;154;155;48;2;233;225;234m▀\033[38;2;205;160;179;48;2;134;121;133m▀\033[48;2;0;0;0m \033[48;2;54;16;52m \033[38;2;100;42;97;48;2;30;17;28m▀\033[0m
\033[38;2;76;43;73;48;2;100;42;97m▀\033[48;2;215;47;224m \033[38;2;217;81;221;48;2;208;115;208m▀\033[38;2;208;115;208;48;2;176;128;186m▀\033[38;2;214;161;224;48;2;217;128;224m▀\033[38;2;168;76;168;48;2;193;68;191m▀\033[38;2;0;0;0;48;2;30;17;28m▀\033[38;2;30;17;28;48;2;49;36;44m▀\033[38;2;217;186;214;48;2;102;79;94m▀\033[48;2;233;225;234m  \033[38;2;217;186;214;48;2;227;206;189m▀\033[38;2;137;84;120m▀\033[38;2;0;0;0;48;2;176;128;186m▀\033[48;2;30;17;28m▀\033[48;2;0;0;0m      \033[38;2;49;36;44;48;2;30;17;28m▀\033[38;2;233;225;234;48;2;79;72;80m▀\033[38;2;175;154;155m▀\033[38;2;0;0;0;48;2;30;17;28m▀\033[38;2;30;17;28;48;2;0;0;0m▀\033[48;2;30;17;28m \033[48;2;0;0;0m \033[38;2;0;0;0;48;2;30;17;28m▀ \033[0m
\033[48;2;160;43;164m \033[48;2;217;81;221m \033[38;2;173;153;208;48;2;176;224;244m▀\033[48;2;173;153;208m \033[38;2;214;161;224;48;2;233;225;234m▀\033[38;2;217;81;221;48;2;214;161;224m▀\033[38;2;76;43;73;48;2;168;76;168m▀\033[38;2;49;36;44;48;2;30;17;28m▀\033[38;2;79;72;80;48;2;49;36;44m▀\033[38;2;134;121;133;48;2;79;72;80m▀\033[38;2;233;225;234;48;2;137;84;120m▀\033[38;2;227;206;189;48;2;217;186;214m▀\033[48;2;176;224;244m  \033[38;2;171;181;193m▀\033[38;2;100;42;97m▀\033[38;2;0;0;0;48;2;137;84;120m▀\033[48;2;0;0;0m    \033[38;2;0;0;0;48;2;30;17;28m▀\033[48;2;0;0;0m \033[38;2;79;72;80m▀\033[38;2;49;36;44m▀ \033[38;2;0;0;0;48;2;30;17;28m▀\033[38;2;30;17;28;48;2;0;0;0m▀\033[0m
\033[38;2;132;42;132;48;2;54;16;52m▀\033[48;2;217;128;224m \033[38;2;176;224;244;48;2;171;181;193m▀\033[38;2;214;161;224;48;2;49;36;44m▀\033[48;2;137;84;120m▀\033[38;2;233;225;234;48;2;175;154;155m▀\033[38;2;208;115;208;48;2;30;17;28m▀\033[38;2;175;154;155;48;2;168;76;168m▀\033[38;2;0;0;0;48;2;227;206;189m▀\033[48;2;79;72;80m▀\033[38;2;49;36;44;48;2;0;0;0m▀▀\033[38;2;79;72;80m▀\033[38;2;134;121;133m▀\033[38;2;171;181;193m▀▀\033[38;2;219;183;175;48;2;30;17;28m▀\033[38;2;175;154;155;48;2;102;79;94m▀\033[38;2;30;17;28;48;2;79;72;80m▀\033[48;2;0;0;0m   \033[48;2;30;17;28m \033[48;2;0;0;0m \033[38;2;30;17;28m▀\033[0m
\033[48;2;0;0;0m \033[38;2;160;43;164m▀\033[38;2;137;84;120;48;2;132;42;132m▀\033[38;2;0;0;0;48;2;79;72;80m▀\033[48;2;0;0;0m \033[48;2;30;17;28m \033[38;2;0;0;0m▀\033[38;2;30;17;28;48;2;0;0;0m▀\033[38;2;176;128;186;48;2;30;17;28m▀\033[38;2;233;225;234;48;2;76;43;73m▀\033[38;2;176;224;244;48;2;176;128;186m▀\033[38;2;134;121;133;48;2;176;224;244m▀\033[38;2;102;79;94m▀\033[48;2;134;121;133m▀\033[38;2;49;36;44;48;2;30;17;28m▀\033[48;2;0;0;0m   \033[38;2;30;17;28m▀\033[48;2;30;17;28m \033[38;2;0;0;0;48;2;49;36;44m▀\033[48;2;30;17;28m▀\033[0m
\033[48;2;0;0;0m   \033[38;2;49;36;44m▀\033[48;2;30;17;28m \033[48;2;0;0;0m      \033[38;2;76;43;73m▀\033[38;2;137;84;120m▀\033[38;2;30;17;28;48;2;54;16;52m▀\033[38;2;0;0;0m▀\033[48;2;30;17;28m▀▀▀\033[48;2;0;0;0m  \033[38;2;79;72;80m▀\033[38;2;102;79;94;48;2;30;17;28m▀\033[38;2;30;17;28;48;2;49;36;44m▀\033[0m
==>04-arch-tan
\033[48;2;0;0;0m               \033[38;2;0;0;0;48;2;17;23;28m▀\033[0m
\033[48;2;0;0;0m             \033[38;2;0;0;0;48;2;17;23;28m▀\033[38;2;17;23;28;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m           \033[38;2;0;0;0;48;2;17;23;28m▀\033[38;2;17;23;28;48;2;0;0;0m▀    \033[38;2;0;0;0;48;2;17;23;28m▀\033[38;2;17;23;28;48;2;82;131;159m▀\033[38;2;0;0;0;48;2;17;23;28m▀\033[38;2;17;23;28;48;2;82;131;159m▀\033[38;2;128;113;115;48;2;251;249;241m▀\033[38;2;86;74;82;48;2;187;170;168m▀\033[0m
\033[48;2;0;0;0m                \033[38;2;0;0;0;48;2;35;33;39m▀\033[38;2;62;63;80;48;2;224;202;194m▀\033[38;2;166;154;155m▀\033[38;2;224;202;194;48;2;248;230;215m▀▀\033[38;2;251;249;241;48;2;249;241;229m▀\033[48;2;104;94;102m \033[48;2;0;0;0m \033[38;2;0;0;0;48;2;17;23;28m▀\033[38;2;17;23;28;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m             \033[38;2;35;33;39;48;2;104;94;102m▀\033[38;2;55;50;58;48;2;35;33;39m▀\033[48;2;0;0;0m  \033[38;2;104;94;102m▀\033[38;2;248;230;215;48;2;187;170;168m▀\033[38;2;224;202;194;48;2;248;230;215m▀\033[38;2;248;230;215;48;2;224;202;194m▀\033[38;2;187;170;168;48;2;62;63;80m▀\033[38;2;82;131;159m▀\033[38;2;17;23;28;48;2;79;111;138m▀\033[48;2;0;0;0m▀\033[38;2;35;33;39m▀\033[0m
\033[48;2;0;0;0m            \033[38;2;75;89;109;48;2;35;33;39m▀\033[38;2;38;47;58;48;2;0;0;0m▀\033[38;2;0;0;0;48;2;17;23;28m▀\033[38;2;35;33;39;48;2;187;170;168m▀\033[38;2;38;47;58;48;2;104;94;102m▀\033[38;2;35;33;39;48;2;128;113;115m▀\033[48;2;0;0;0m▀\033[38;2;224;202;194;48;2;86;74;82m▀\033[38;2;166;154;155;48;2;128;113;115m▀\033[38;2;86;74;82m▀\033[38;2;128;113;115;48;2;224;202;194m▀\033[38;2;86;74;82;48;2;104;94;102m▀\033[38;2;82;131;159;48;2;75;89;109m▀\033[38;2;17;23;28;48;2;112;151;167m▀\033[38;2;0;0;0;48;2;17;23;28m▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;17;23;28m▀\033[0m
\033[48;2;0;0;0m       \033[38;2;0;0;0;48;2;17;23;28m▀\033[48;2;0;0;0m   \033[38;2;0;0;0;48;2;17;23;28m▀ \033[48;2;0;0;0m \033[38;2;35;33;39m▀\033[38;2;62;63;80m▀\033[48;2;86;74;82m \033[38;2;224;202;194;48;2;251;249;241m▀\033[38;2;38;47;58;48;2;234;228;221m▀\033[38;2;62;63;80;48;2;166;154;155m▀\033[38;2;128;113;115;48;2;224;202;194m▀\033[38;2;187;170;168;48;2;251;249;241m▀\033[48;2;224;202;194m  \033[38;2;166;154;155;48;2;248;230;215m▀\033[38;2;75;89;109;48;2;125;129;148m▀\033[38;2;112;151;167;48;2;62;63;80m▀\033[38;2;38;47;58;48;2;82;131;159m▀\033[38;2;0;0;0;48;2;75;89;109m▀\033[38;2;17;23;28;48;2;0;0;0m▀\033[48;2;86;74;82m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;0;0;0;48;2;17;23;28m▀\033[38;2;17;23;28;48;2;0;0;0m▀▀\033[48;2;35;33;39m▀▀\033[48;2;0;0;0m   \033[48;2;17;23;28m \033[38;2;150;140;147;48;2;251;249;241m▀\033[38;2;103;108;126;48;2;234;228;221m▀\033[38;2;79;111;138;48;2;148;205;228m▀\033[48;2;193;191;195m \033[48;2;251;249;241m \033[38;2;249;241;229m▀ \033[38;2;193;191;195;48;2;234;228;221m▀\033[38;2;95;127;152;48;2;148;205;228m▀\033[38;2;75;89;109;48;2;193;191;195m▀\033[38;2;187;170;168;48;2;249;241;229m▀\033[48;2;125;129;148m \033[38;2;75;89;109;48;2;82;131;159m▀\033[38;2;86;74;82;48;2;104;94;102m▀\033[38;2;82;131;159;48;2;62;63;80m▀\033[38;2;79;111;138m▀\033[38;2;17;23;28;48;2;38;47;58m▀\033[38;2;0;0;0m▀\033[0m
\033[38;2;0;0;0;48;2;17;23;28m▀\033[48;2;0;0;0m    \033[38;2;17;23;28m▀▀\033[38;2;0;0;0;48;2;17;23;28m▀\033[38;2;17;23;28;48;2;0;0;0m▀   \033[38;2;0;0;0;48;2;17;23;28m▀\033[48;2;0;0;0m \033[38;2;150;140;147;48;2;35;33;39m▀\033[38;2;251;249;241;48;2;234;228;221m▀\033[48;2;249;241;229m       \033[38;2;249;241;229;48;2;251;249;241m▀\033[38;2;248;230;215;48;2;187;170;168m▀\033[38;2;112;151;167;48;2;79;111;138m▀\033[48;2;82;131;159m▀\033[38;2;104;94;102;48;2;86;74;82m▀▀\033[38;2;55;50;58;48;2;62;63;80m▀\033[48;2;17;23;28m \033[38;2;35;33;39;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;17;23;28m▀▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;17;23;28m▀▀\033[48;2;0;0;0m \033[38;2;17;23;28m▀ \033[48;2;17;23;28m \033[38;2;0;0;0m▀\033[48;2;0;0;0m \033[38;2;35;33;39m▀\033[38;2;17;23;28;48;2;38;47;58m▀\033[48;2;0;0;0m \033[38;2;193;191;195;48;2;17;23;28m▀\033[38;2;251;249;241;48;2;193;191;195m▀\033[48;2;248;230;215m▀\033[48;2;249;241;229m▀▀▀\033[48;2;248;230;215m▀\033[48;2;224;202;194m▀\033[38;2;234;228;221;48;2;95;127;152m▀\033[38;2;103;108;126;48;2;79;111;138m▀\033[38;2;95;127;152m▀\033[38;2;79;111;138;48;2;86;74;82m▀\033[38;2;86;74;82;48;2;103;108;126m▀\033[48;2;79;111;138m▀\033[38;2;79;111;138;48;2;38;47;58m▀\033[38;2;38;47;58;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m   \033[38;2;17;23;28m▀\033[38;2;38;47;58m▀\033[48;2;17;23;28m \033[48;2;0;0;0m   \033[38;2;17;23;28m▀\033[38;2;35;33;39m▀\033[38;2;17;23;28m▀\033[38;2;128;113;115;48;2;104;94;102m▀\033[38;2;86;74;82;48;2;128;113;115m▀\033[48;2;0;0;0m \033[38;2;55;50;58;48;2;86;74;82m▀\033[38;2;224;202;194;48;2;251;249;241m▀\033[48;2;249;241;229m▀\033[38;2;248;230;215m▀▀ \033[48;2;248;230;215m \033[38;2;224;202;194m▀\033[38;2;148;205;228;48;2;151;216;233m▀\033[48;2;77;179;220m \033[38;2;112;151;167;48;2;75;89;109m▀\033[38;2;150;140;147;48;2;35;33;39m▀\033[38;2;75;89;109;48;2;0;0;0m▀\033[38;2;17;23;28m▀\033[0m
\033[48;2;0;0;0m    \033[38;2;17;23;28;48;2;35;33;39m▀\033[48;2;17;23;28m \033[48;2;0;0;0m       \033[38;2;38;47;58m▀ ▀\033[38;2;251;249;241;48;2;112;151;167m▀\033[48;2;251;249;241m \033[38;2;251;249;241;48;2;249;241;229m▀▀\033[38;2;248;230;215;48;2;251;249;241m▀\033[48;2;224;202;194m▀\033[48;2;55;50;58m▀\033[38;2;95;127;152;48;2;17;23;28m▀\033[38;2;35;33;39;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m    \033[38;2;17;23;28m▀▀▀        \033[48;2;17;23;28m \033[38;2;0;0;0;48;2;62;63;80m▀\033[38;2;75;89;109;48;2;0;0;0m▀\033[38;2;193;191;195m▀▀\033[38;2;187;170;168m▀\033[38;2;55;50;58;48;2;17;23;28m▀ \033[38;2;38;47;58;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m              \033[38;2;17;23;28m▀\033[38;2;35;33;39;48;2;17;23;28m▀\033[38;2;149;161;183;48;2;128;113;115m▀\033[38;2;75;89;109;48;2;62;63;80m▀\033[38;2;0;0;0;48;2;17;23;28m▀\033[48;2;0;0;0m \033[38;2;38;47;58;48;2;75;89;109m▀\033[38;2;62;63;80;48;2;17;23;28m▀\033[0m
\033[48;2;0;0;0m               \033[38;2;17;23;28m▀\033[38;2;103;108;126;48;2;17;23;28m▀\033[38;2;62;63;80m▀\033[38;2;38;47;58m▀\033[38;2;0;0;0m▀\033[38;2;75;89;109;48;2;38;47;58m▀\033[38;2;17;23;28;48;2;35;33;39m▀\033[48;2;0;0;0m▀\033[38;2;55;50;58;48;2;38;47;58m▀\033[38;2;0;0;0;48;2;35;33;39m▀\033[48;2;17;23;28m▀\033[0m
==>05-arch-tan-violet
\033[48;2;0;0;0m             \033[38;2;0;0;0;48;2;23;22;28m▀\033[38;2;23;22;28;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m            \033[38;2;23;22;28m▀    \033[38;2;0;0;0;48;2;23;22;28m▀\033[38;2;23;22;28;48;2;123;133;166m▀\033[38;2;0;0;0;48;2;41;36;45m▀\033[48;2;97;111;146m▀\033[38;2;116;104;120;48;2;251;246;240m▀\033[38;2;88;78;84;48;2;179;170;171m▀\033[0m
\033[48;2;0;0;0m                \033[38;2;0;0;0;48;2;41;36;45m▀\033[38;2;88;78;84;48;2;252;225;197m▀\033[38;2;180;163;158m▀\033[38;2;230;203;187m▀\033[38;2;225;207;201m▀\033[38;2;248;239;229m▀\033[48;2;90;84;104m \033[48;2;0;0;0m \033[38;2;0;0;0;48;2;23;22;28m▀\033[38;2;23;22;28;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m             \033[38;2;23;22;28;48;2;96;95;124m▀\033[38;2;50;46;56;48;2;23;22;28m▀\033[48;2;0;0;0m  \033[38;2;88;78;84m▀\033[38;2;252;225;197;48;2;180;163;158m▀\033[38;2;230;203;187;48;2;252;225;197m▀\033[38;2;252;225;197;48;2;230;203;187m▀\033[38;2;180;163;158;48;2;71;64;79m▀\033[38;2;120;106;147m▀\033[38;2;23;22;28;48;2;97;111;146m▀\033[48;2;0;0;0m▀\033[38;2;41;36;45m▀\033[0m
\033[48;2;0;0;0m           \033[38;2;0;0;0;48;2;23;22;28m▀\033[38;2;90;84;104;48;2;41;36;45m▀\033[38;2;50;46;56;48;2;0;0;0m▀\033[38;2;0;0;0;48;2;23;22;28m▀\033[38;2;23;22;28;48;2;180;163;158m▀\033[38;2;50;46;56;48;2;90;84;104m▀\033[38;2;41;36;45;48;2;131;120;125m▀\033[48;2;0;0;0m▀\033[38;2;230;203;187;48;2;88;78;84m▀\033[38;2;160;145;146;48;2;131;120;125m▀\033[38;2;90;84;104;48;2;116;104;120m▀\033[38;2;131;120;125;48;2;230;203;187m▀\033[38;2;71;64;79;48;2;90;84;104m▀\033[38;2;97;111;146;48;2;71;64;79m▀\033[38;2;23;22;28;48;2;123;133;166m▀\033[38;2;0;0;0;48;2;23;22;28m▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;23;22;28m▀\033[0m
\033[48;2;0;0;0m       \033[38;2;0;0;0;48;2;23;22;28m▀\033[48;2;0;0;0m   \033[38;2;0;0;0;48;2;23;22;28m▀▀\033[48;2;0;0;0m \033[38;2;41;36;45m▀\033[38;2;71;64;79m▀\033[48;2;88;78;84m \033[38;2;201;187;201;48;2;251;246;240m▀\033[38;2;50;46;56;48;2;238;231;227m▀\033[38;2;71;64;79;48;2;160;145;146m▀\033[38;2;131;120;125;48;2;230;203;187m▀\033[38;2;197;183;182;48;2;251;246;240m▀\033[38;2;225;207;201;48;2;197;183;182m▀\033[48;2;201;187;201m \033[38;2;160;145;146;48;2;248;239;229m▀\033[38;2;90;84;104;48;2;126;118;147m▀\033[38;2;146;126;155;48;2;88;78;84m▀\033[38;2;41;36;45;48;2;126;118;147m▀\033[38;2;0;0;0;48;2;88;78;84m▀\033[38;2;23;22;28;48;2;0;0;0m▀\033[48;2;88;78;84m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;0;0;0;48;2;23;22;28m▀ \033[38;2;23;22;28;48;2;0;0;0m▀\033[48;2;41;36;45m▀\033[48;2;50;46;56m▀\033[48;2;0;0;0m   \033[48;2;23;22;28m \033[38;2;131;120;125;48;2;251;246;240m▀\033[48;2;238;231;227m▀\033[38;2;96;95;124;48;2;184;170;207m▀\033[38;2;197;183;182;48;2;201;187;201m▀\033[48;2;251;246;240m \033[38;2;248;239;229m▀ \033[38;2;201;187;201;48;2;225;207;201m▀\033[38;2;97;111;146;48;2;184;170;207m▀\033[38;2;96;95;124;48;2;225;207;201m▀\033[38;2;197;183;182;48;2;248;239;229m▀\033[48;2;146;126;155m \033[38;2;96;95;124;48;2;120;106;147m▀\033[38;2;71;64;79;48;2;90;84;104m▀\033[38;2;146;126;155;48;2;71;64;79m▀\033[38;2;116;104;120m▀\033[38;2;23;22;28;48;2;41;36;45m▀\033[38;2;0;0;0;48;2;50;46;56m▀\033[0m
\033[38;2;0;0;0;48;2;23;22;28m▀\033[48;2;0;0;0m   \033[38;2;23;22;28m▀▀ \033[38;2;0;0;0;48;2;23;22;28m▀\033[38;2;23;22;28;48;2;0;0;0m▀   \033[38;2;0;0;0;48;2;23;22;28m▀\033[48;2;0;0;0m \033[38;2;160;145;146;48;2;41;36;45m▀\033[38;2;251;246;240;48;2;238;231;227m▀\033[48;2;248;239;229m     \033[38;2;251;246;240m▀ \033[48;2;251;246;240m \033[38;2;238;231;227;48;2;179;170;171m▀\033[38;2;146;126;155;48;2;120;106;147m▀\033[48;2;126;118;147m▀\033[38;2;96;95;124;48;2;90;84;104m▀\033[38;2;90;84;104;48;2;71;64;79m▀\033[38;2;50;46;56m▀\033[48;2;23;22;28m \033[38;2;41;36;45;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;23;22;28m▀▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;23;22;28m▀▀\033[48;2;0;0;0m \033[38;2;23;22;28m▀ \033[38;2;0;0;0;48;2;23;22;28m▀▀\033[48;2;0;0;0m \033[38;2;23;22;28m▀\033[48;2;41;36;45m▀\033[48;2;0;0;0m \033[38;2;201;187;201;48;2;23;22;28m▀\033[38;2;251;246;240;48;2;197;183;182m▀\033[48;2;238;231;227m▀▀\033[48;2;248;239;229m▀\033[48;2;238;231;227m▀\033[48;2;248;239;229m▀\033[48;2;201;187;201m▀\033[38;2;225;207;201;48;2;123;133;166m▀\033[38;2;116;104;120;48;2;96;95;124m▀\033[38;2;97;111;146m▀\033[38;2;96;95;124;48;2;90;84;104m▀\033[38;2;71;64;79m▀\033[38;2;88;78;84;48;2;96;95;124m▀\033[38;2;96;95;124;48;2;41;36;45m▀\033[38;2;50;46;56;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m   \033[38;2;23;22;28m▀\033[38;2;50;46;56m▀\033[38;2;41;36;45;48;2;23;22;28m▀\033[48;2;0;0;0m   \033[38;2;23;22;28m▀\033[38;2;41;36;45m▀ \033[38;2;131;120;125;48;2;90;84;104m▀\033[38;2;88;78;84;48;2;131;120;125m▀\033[48;2;0;0;0m \033[38;2;50;46;56;48;2;71;64;79m▀\033[38;2;225;207;201;48;2;251;246;240m▀\033[38;2;230;203;187;48;2;249;234;219m▀\033[38;2;249;234;219;48;2;248;239;229m▀\033[38;2;248;239;229;48;2;251;246;240m▀\033[48;2;248;239;229m \033[38;2;252;225;197;48;2;249;234;219m▀\033[38;2;230;203;187m▀\033[48;2;184;170;207m \033[48;2;120;152;197m \033[38;2;123;133;166;48;2;96;95;124m▀\033[38;2;160;145;146;48;2;41;36;45m▀\033[38;2;90;84;104;48;2;0;0;0m▀\033[48;2;23;22;28m \033[0m
\033[48;2;0;0;0m    \033[38;2;23;22;28;48;2;41;36;45m▀\033[48;2;23;22;28m \033[38;2;0;0;0m▀▀\033[48;2;0;0;0m     \033[38;2;41;36;45m▀ ▀\033[38;2;251;246;240;48;2;131;120;125m▀\033[38;2;249;234;219;48;2;251;246;240m▀▀\033[48;2;248;239;229m▀\033[48;2;251;246;240m▀\033[38;2;248;239;229;48;2;225;207;201m▀\033[38;2;249;234;219;48;2;50;46;56m▀\033[38;2;97;111;146;48;2;41;36;45m▀\033[38;2;23;22;28;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m    \033[38;2;23;22;28m▀▀▀        \033[48;2;23;22;28m \033[38;2;0;0;0;48;2;71;64;79m▀\033[38;2;90;84;104;48;2;0;0;0m▀\033[38;2;197;183;182m▀\033[38;2;201;187;201m▀\033[38;2;197;183;182m▀\033[38;2;50;46;56;48;2;23;22;28m▀ \033[48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m              \033[38;2;23;22;28m▀\033[38;2;41;36;45;48;2;23;22;28m▀\033[38;2;142;145;169;48;2;96;95;124m▀\033[48;2;71;64;79m \033[38;2;0;0;0;48;2;23;22;28m▀\033[48;2;0;0;0m \033[38;2;50;46;56;48;2;90;84;104m▀\033[38;2;71;64;79;48;2;41;36;45m▀\033[0m
\033[48;2;0;0;0m               \033[38;2;23;22;28m▀\033[38;2;116;104;120;48;2;23;22;28m▀\033[38;2;71;64;79m▀\033[38;2;41;36;45m▀\033[38;2;0;0;0m▀\033[38;2;88;78;84;48;2;50;46;56m▀\033[38;2;23;22;28;48;2;41;36;45m▀\033[48;2;0;0;0m▀\033[38;2;50;46;56;48;2;41;36;45m▀\033[38;2;0;0;0;48;2;23;22;28m▀\033[0m
==>06-icecat
\033[48;2;0;0;0m         \033[38;2;7;8;22;48;2;104;100;101m▀\033[38;2;34;41;70;48;2;253;251;241m▀\033[38;2;7;8;22;48;2;191;185;183m▀\033[38;2;0;0;0;48;2;95;100;147m▀\033[48;2;119;119;156m▀\033[48;2;191;185;183m▀\033[38;2;7;8;22;48;2;223;214;208m▀▀\033[38;2;0;0;0;48;2;168;163;166m▀\033[48;2;104;100;101m▀\033[48;2;73;70;83m▀\033[48;2;144;140;142m▀\033[38;2;7;8;22;48;2;191;185;183m▀\033[48;2;130;126;132m▀\033[0m
\033[48;2;0;0;0m         \033[38;2;79;79;101;48;2;73;70;83m▀\033[48;2;253;251;241m \033[48;2;242;233;222m  \033[38;2;253;251;241m▀ \033[38;2;242;233;222;48;2;253;251;241m▀▀  ▀\033[38;2;253;251;241;48;2;242;233;222m▀\033[48;2;253;251;241m \033[38;2;144;140;142;48;2;79;79;101m▀\033[0m
\033[48;2;0;0;0m         \033[38;2;33;32;37m▀\033[38;2;223;214;208;48;2;168;163;166m▀\033[38;2;253;251;241;48;2;242;233;222m▀\033[38;2;223;214;208;48;2;191;185;183m▀\033[38;2;242;233;222;48;2;223;214;208m▀\033[48;2;191;185;183m▀▀\033[38;2;223;214;208m▀▀\033[38;2;191;185;183;48;2;168;163;166m▀\033[38;2;223;214;208m▀\033[48;2;223;214;208m \033[38;2;223;214;208;48;2;130;126;132m▀\033[38;2;34;41;70;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m         \033[38;2;33;32;37;48;2;66;65;66m▀\033[48;2;191;185;183m \033[38;2;191;185;183;48;2;168;163;166m▀ ▀\033[38;2;147;146;163;48;2;191;185;183m▀\033[38;2;168;163;166m▀\033[38;2;223;214;208m▀\033[38;2;191;185;183;48;2;223;214;208m▀\033[38;2;168;163;166m▀\033[48;2;191;185;183m▀\033[38;2;191;185;183;48;2;168;163;166m▀\033[38;2;124;119;121;48;2;130;126;132m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;7;8;22m▀\033[38;2;104;100;101;48;2;34;41;70m▀\033[38;2;7;8;22;48;2;10;15;53m▀\033[38;2;0;0;0m▀\033[38;2;33;32;37;48;2;95;100;147m▀\033[48;2;73;70;83m▀\033[48;2;0;0;0m \033[48;2;66;65;66m \033[48;2;168;163;166m  \033[38;2;191;185;183;48;2;147;146;163m▀\033[48;2;7;8;22m▀\033[38;2;223;214;208;48;2;144;140;142m▀\033[48;2;253;251;241m▀\033[48;2;242;233;222m▀\033[38;2;104;100;101;48;2;34;41;70m▀\033[48;2;73;70;83m \033[38;2;223;214;208;48;2;191;185;183m▀\033[38;2;144;140;142;48;2;130;126;132m▀\033[38;2;191;185;183;48;2;168;163;166m▀\033[38;2;66;65;66;48;2;144;140;142m▀\033[0m
\033[38;2;0;0;0;48;2;33;32;37m▀\033[48;2;7;8;22m▀\033[48;2;10;15;53m \033[38;2;130;126;132;48;2;191;185;183m▀\033[38;2;124;119;121;48;2;95;100;147m▀\033[38;2;67;73;141;48;2;147;146;163m▀\033[38;2;95;100;147;48;2;151;152;180m▀\033[38;2;8;13;93;48;2;5;9;119m▀\033[38;2;7;8;22;48;2;119;119;156m▀\033[38;2;144;140;142;48;2;104;100;101m▀\033[48;2;124;119;121m▀\033[48;2;144;140;142m \033[38;2;191;185;183;48;2;223;214;208m▀\033[38;2;34;41;70m▀\033[38;2;147;146;163;48;2;242;233;222m▀\033[48;2;253;251;241m \033[38;2;253;251;241;48;2;242;233;222m▀\033[38;2;191;185;183;48;2;253;251;241m▀\033[48;2;242;233;222m▀\033[48;2;168;163;166m▀\033[38;2;168;163;166;48;2;191;185;183m▀\033[38;2;124;119;121;48;2;144;140;142m▀\033[38;2;168;163;166;48;2;130;126;132m▀\033[38;2;7;8;22;48;2;33;32;37m▀\033[0m
\033[38;2;34;41;70;48;2;0;0;0m▀\033[38;2;73;70;83;48;2;104;100;101m▀\033[38;2;130;126;132;48;2;253;251;241m▀\033[38;2;73;70;83;48;2;168;163;166m▀\033[38;2;147;146;163;48;2;223;214;208m▀\033[38;2;95;100;147;48;2;191;185;183m▀\033[38;2;144;140;142;48;2;119;119;156m▀\033[38;2;119;119;156;48;2;79;79;101m▀\033[38;2;30;40;115;48;2;33;32;37m▀ \033[38;2;130;126;132;48;2;104;100;101m▀\033[48;2;124;119;121m \033[38;2;144;140;142;48;2;34;41;70m▀\033[38;2;242;233;222;48;2;130;126;132m▀\033[38;2;253;251;241;48;2;124;119;121m▀\033[38;2;223;214;208;48;2;242;233;222m▀\033[38;2;168;163;166m▀\033[38;2;242;233;222;48;2;191;185;183m▀\033[38;2;223;214;208;48;2;168;163;166m▀\033[48;2;130;126;132m \033[38;2;191;185;183;48;2;124;119;121m▀\033[38;2;144;140;142;48;2;168;163;166m▀\033[38;2;79;79;101;48;2;66;65;66m▀ \033[0m
\033[38;2;7;8;22;48;2;0;0;0m▀\033[38;2;34;41;70;48;2;7;8;22m▀\033[38;2;79;79;101;48;2;8;13;93m▀\033[38;2;124;119;121m▀\033[38;2;104;100;101;48;2;30;40;115m▀\033[38;2;73;70;83;48;2;27;42;144m▀ \033[38;2;7;8;22;48;2;30;40;115m▀\033[48;2;0;0;0m \033[38;2;33;32;37;48;2;7;8;22m▀\033[38;2;73;70;83;48;2;33;32;37m▀\033[38;2;66;65;66;48;2;130;126;132m▀\033[38;2;7;8;22;48;2;168;163;166m▀\033[38;2;104;100;101m▀\033[38;2;0;0;0;48;2;73;70;83m▀\033[38;2;144;140;142;48;2;5;9;119m▀\033[38;2;242;233;222;48;2;67;73;141m▀\033[38;2;147;146;163;48;2;5;9;119m▀\033[38;2;144;140;142;48;2;30;40;115m▀\033[48;2;124;119;121m \033[38;2;33;32;37m▀\033[38;2;130;126;132;48;2;168;163;166m▀\033[38;2;33;32;37;48;2;130;126;132m▀\033[38;2;7;8;22;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;7;8;22m▀\033[38;2;8;13;93;48;2;7;8;22m▀\033[38;2;27;42;144;48;2;11;20;138m▀\033[48;2;151;152;180m▀\033[38;2;67;73;141;48;2;242;233;222m▀\033[38;2;30;40;115;48;2;33;32;37m▀\033[48;2;0;0;0m  \033[38;2;66;65;66;48;2;144;140;142m▀\033[48;2;253;251;241m \033[38;2;253;251;241;48;2;242;233;222m▀▀\033[38;2;119;119;156;48;2;30;40;115m▀\033[48;2;5;9;119m \033[38;2;30;40;115;48;2;119;119;156m▀\033[38;2;27;42;144;48;2;67;73;141m▀\033[48;2;5;9;119m \033[38;2;191;185;183;48;2;147;146;163m▀\033[48;2;253;251;241m   \033[38;2;66;65;66;48;2;168;163;166m▀\033[0m
\033[48;2;0;0;0m    \033[38;2;10;15;53m▀\033[38;2;130;126;132;48;2;7;8;22m▀\033[48;2;253;251;241m \033[38;2;79;79;101;48;2;168;163;166m▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;33;32;37m▀\033[38;2;191;185;183;48;2;242;233;222m▀\033[48;2;253;251;241m \033[38;2;253;251;241;48;2;223;214;208m▀\033[38;2;119;119;156;48;2;5;9;119m▀\033[38;2;5;9;119;48;2;11;20;138m▀ \033[38;2;27;42;144m▀\033[48;2;27;42;144m \033[38;2;11;20;138m▀\033[38;2;30;40;115;48;2;5;9;119m▀\033[38;2;223;214;208;48;2;119;119;156m▀\033[48;2;242;233;222m \033[48;2;253;251;241m \033[38;2;191;185;183;48;2;223;214;208m▀\033[38;2;7;8;22;48;2;33;32;37m▀\033[0m
\033[48;2;0;0;0m      \033[38;2;147;146;163;48;2;66;65;66m▀\033[48;2;253;251;241m \033[38;2;33;32;37;48;2;144;140;142m▀\033[38;2;66;65;66;48;2;104;100;101m▀\033[48;2;253;251;241m  \033[38;2;151;152;180;48;2;67;73;141m▀\033[48;2;5;9;119m \033[48;2;11;20;138m \033[38;2;11;20;138;48;2;27;42;144m▀ \033[38;2;27;42;144;48;2;38;65;171m▀\033[48;2;27;42;144m  \033[38;2;5;9;119;48;2;11;20;138m▀\033[38;2;168;163;166;48;2;67;73;141m▀\033[38;2;223;214;208;48;2;95;100;147m▀\033[38;2;67;73;141;48;2;11;20;138m▀\033[38;2;30;40;115;48;2;27;42;144m▀\033[38;2;8;13;93;48;2;38;65;171m▀\033[38;2;7;8;22;48;2;11;20;138m▀\033[38;2;0;0;0;48;2;7;8;22m▀\033[0m
\033[48;2;0;0;0m      \033[38;2;33;32;37m▀\033[38;2;242;233;222;48;2;191;185;183m▀\033[48;2;253;251;241m▀\033[38;2;191;185;183;48;2;223;214;208m▀\033[48;2;253;251;241m \033[38;2;223;214;208;48;2;124;119;121m▀\033[38;2;8;13;93;48;2;7;8;22m▀\033[38;2;11;20;138;48;2;5;9;119m▀\033[48;2;27;42;144m \033[38;2;27;42;144;48;2;38;65;171m▀\033[38;2;38;65;171;48;2;55;89;190m▀\033[38;2;55;89;190;48;2;38;65;171m▀ \033[38;2;27;42;144m▀\033[38;2;11;20;138;48;2;27;42;144m▀\033[38;2;30;40;115;48;2;10;15;53m▀\033[48;2;191;185;183m \033[38;2;151;152;180;48;2;253;251;241m▀\033[38;2;30;40;115;48;2;104;100;101m▀\033[38;2;8;13;93;48;2;0;0;0m▀\033[38;2;27;42;144;48;2;7;8;22m▀\033[38;2;11;20;138;48;2;27;42;144m▀\033[38;2;7;8;22;48;2;8;13;93m▀\033[0m
\033[48;2;0;0;0m       \033[38;2;130;126;132;48;2;73;70;83m▀\033[48;2;253;251;241m \033[38;2;223;214;208m▀\033[38;2;242;233;222;48;2;223;214;208m▀\033[38;2;66;65;66;48;2;7;8;22m▀\033[48;2;0;0;0m \033[38;2;5;9;119;48;2;30;40;115m▀\033[38;2;27;42;144;48;2;38;65;171m▀  \033[38;2;38;65;171;48;2;55;89;190m▀\033[38;2;55;89;190;48;2;38;65;171m▀ \033[38;2;27;42;144;48;2;8;13;93m▀\033[38;2;7;8;22;48;2;0;0;0m▀\033[38;2;144;140;142;48;2;130;126;132m▀\033[48;2;253;251;241m \033[38;2;130;126;132;48;2;168;163;166m▀\033[48;2;0;0;0m  \033[38;2;10;15;53;48;2;7;8;22m▀\033[38;2;11;20;138;48;2;27;42;144m▀\033[0m
\033[48;2;0;0;0m       \033[38;2;33;32;37m▀\033[38;2;242;233;222;48;2;66;65;66m▀\033[38;2;253;251;241;48;2;191;185;183m▀\033[38;2;168;163;166;48;2;104;100;101m▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;7;8;22m▀\033[38;2;30;40;115;48;2;11;20;138m▀\033[48;2;38;65;171m \033[38;2;38;65;171;48;2;55;89;190m▀\033[38;2;55;89;190;48;2;70;111;207m▀ ▀\033[48;2;55;89;190m \033[38;2;30;40;115;48;2;27;42;144m▀\033[48;2;0;0;0m \033[38;2;104;100;101;48;2;33;32;37m▀\033[38;2;253;251;241;48;2;242;233;222m▀\033[38;2;242;233;222;48;2;253;251;241m▀\033[38;2;33;32;37;48;2;73;70;83m▀\033[48;2;0;0;0m \033[48;2;10;15;53m \033[38;2;38;65;171;48;2;27;42;144m▀\033[0m
\033[48;2;0;0;0m            \033[38;2;10;15;53;48;2;8;13;93m▀\033[38;2;30;40;115;48;2;38;65;171m▀\033[38;2;27;42;144;48;2;55;89;190m▀\033[38;2;55;89;190;48;2;70;111;207m▀   \033[38;2;38;65;171;48;2;55;89;190m▀\033[38;2;27;42;144;48;2;38;65;171m▀\033[48;2;10;15;53m \033[48;2;0;0;0m \033[38;2;191;185;183;48;2;124;119;121m▀\033[48;2;253;251;241m \033[38;2;144;140;142;48;2;191;185;183m▀\033[38;2;0;0;0;48;2;33;32;37m▀\033[38;2;67;73;141;48;2;55;89;190m▀\033[38;2;30;40;115;48;2;7;8;22m▀\033[0m
\033[48;2;0;0;0m           \033[38;2;0;0;0;48;2;7;8;22m▀\033[38;2;8;13;93;48;2;11;20;138m▀\033[38;2;27;42;144;48;2;38;65;171m▀\033[48;2;55;89;190m \033[38;2;70;111;207;48;2;87;143;235m▀▀ ▀\033[38;2;55;89;190;48;2;70;111;207m▀\033[38;2;38;65;171;48;2;55;89;190m▀\033[38;2;8;13;93;48;2;27;42;144m▀\033[48;2;0;0;0m \033[38;2;66;65;66m▀\033[38;2;253;251;241;48;2;191;185;183m▀\033[38;2;223;214;208;48;2;253;251;241m▀\033[38;2;119;119;156;48;2;130;126;132m▀\033[38;2;30;40;115;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m            \033[38;2;10;15;53m▀▀\033[38;2;34;41;70m▀▀▀▀▀▀\033[38;2;10;15;53m▀▀  \033[38;2;33;32;37m▀\033[38;2;73;70;83m▀\033[38;2;33;32;37m▀\033[0m
==>07-icecat-blush
\033[48;2;0;0;0m         \033[38;2;25;27;34;48;2;60;76;104m▀\033[38;2;49;51;64;48;2;186;181;235m▀\033[38;2;9;11;19;48;2;162;154;187m▀\033[38;2;0;0;0;48;2;91;95;140m▀\033[48;2;66;89;149m▀\033[48;2;106;143;201m▀\033[38;2;9;11;19m▀▀\033[38;2;0;0;0;48;2;102;122;150m▀\033[48;2;60;76;104m▀▀\033[48;2;102;122;150m▀\033[38;2;9;11;19;48;2;162;154;187m▀\033[48;2;91;95;140m▀\033[0m
\033[48;2;0;0;0m         \033[38;2;60;76;104;48;2;49;51;64m▀\033[38;2;119;164;232;48;2;154;153;244m▀\033[48;2;119;164;232m \033[38;2;129;160;215m▀      ▀\033[38;2;154;153;244m▀\033[38;2;119;164;232;48;2;154;153;244m▀\033[38;2;91;95;140;48;2;60;76;104m▀\033[0m
\033[48;2;0;0;0m         \033[38;2;25;27;34m▀\033[38;2;129;160;215;48;2;106;143;201m▀\033[48;2;119;164;232m \033[38;2;119;164;232;48;2;113;133;173m▀\033[48;2;106;143;201m▀▀▀\033[48;2;129;160;215m▀\033[38;2;129;160;215;48;2;183;167;160m▀\033[38;2;113;133;173;48;2;134;128;137m▀\033[38;2;106;143;201;48;2;102;122;150m▀\033[48;2;129;160;215m \033[38;2;129;160;215;48;2;91;95;140m▀\033[38;2;25;34;62;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m         \033[38;2;25;27;34;48;2;25;34;62m▀\033[38;2;106;143;201;48;2;113;133;173m▀▀  \033[38;2;113;133;173;48;2;126;137;200m▀\033[48;2;162;154;187m▀ \033[38;2;156;148;149m▀\033[38;2;134;128;137;48;2;126;137;200m▀\033[38;2;113;133;173m▀\033[48;2;102;122;150m▀\033[38;2;60;76;104;48;2;87;88;108m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;9;11;19m▀\033[38;2;60;76;104;48;2;25;34;62m▀\033[38;2;9;11;19;48;2;7;11;46m▀\033[38;2;0;0;0;48;2;5;10;98m▀\033[38;2;25;27;34;48;2;60;76;104m▀\033[38;2;9;11;19;48;2;49;51;64m▀\033[48;2;0;0;0m \033[38;2;25;34;62;48;2;49;51;64m▀\033[38;2;113;133;173;48;2;102;122;150m▀ \033[38;2;126;137;200;48;2;91;95;140m▀\033[38;2;113;133;173;48;2;0;0;0m▀\033[38;2;126;137;200;48;2;91;95;140m▀\033[38;2;162;154;187;48;2;154;153;244m▀\033[38;2;126;137;200m▀\033[38;2;60;76;104;48;2;25;34;62m▀\033[38;2;49;51;64;48;2;60;76;104m▀\033[38;2;162;154;187;48;2;183;167;160m▀\033[48;2;91;95;140m \033[38;2;106;143;201;48;2;113;133;173m▀\033[38;2;49;51;64;48;2;66;89;149m▀\033[0m
\033[38;2;0;0;0;48;2;9;11;19m▀\033[48;2;0;0;0m \033[48;2;7;11;46m \033[38;2;134;128;137;48;2;210;199;186m▀\033[48;2;102;122;150m▀\033[38;2;91;95;140;48;2;134;128;137m▀\033[38;2;87;88;108m▀\033[38;2;25;34;62;48;2;10;22;135m▀\033[38;2;9;11;19;48;2;102;122;150m▀\033[38;2;66;89;149;48;2;60;76;104m▀\033[38;2;91;95;140m▀\033[38;2;102;122;150;48;2;91;95;140m▀\033[38;2;113;133;173;48;2;126;137;200m▀\033[38;2;49;51;64;48;2;119;164;232m▀\033[38;2;113;133;173;48;2;154;153;244m▀  \033[38;2;126;137;200m▀\033[38;2;129;160;215;48;2;238;224;227m▀\033[38;2;210;199;186;48;2;156;148;149m▀\033[38;2;102;122;150;48;2;106;143;201m▀\033[48;2;91;95;140m▀▀\033[38;2;9;11;19;48;2;25;27;34m▀\033[0m
\033[38;2;25;27;34;48;2;0;0;0m▀\033[38;2;49;51;64;48;2;87;88;108m▀\033[38;2;134;128;137;48;2;254;252;233m▀\033[38;2;60;76;104;48;2;183;167;160m▀\033[38;2;156;148;149;48;2;240;226;205m▀\033[38;2;87;88;108;48;2;183;167;160m▀\033[38;2;162;154;187;48;2;102;122;150m▀\033[38;2;113;133;173;48;2;60;76;104m▀\033[38;2;35;45;107;48;2;9;11;19m▀\033[38;2;25;27;34;48;2;25;34;62m▀\033[38;2;91;95;140;48;2;60;76;104m▀ \033[48;2;25;27;34m▀\033[38;2;154;153;244;48;2;91;95;140m▀▀\033[38;2;162;154;187;48;2;154;153;244m▀\033[48;2;186;181;235m▀\033[38;2;238;224;227;48;2;210;199;186m▀\033[38;2;210;199;186;48;2;102;122;150m▀\033[38;2;91;95;140;48;2;87;88;108m▀\033[38;2;106;143;201;48;2;91;95;140m▀\033[38;2;91;95;140;48;2;102;122;150m▀\033[38;2;60;76;104;48;2;49;51;64m▀ \033[0m
\033[48;2;0;0;0m \033[38;2;25;34;62;48;2;7;11;46m▀\033[38;2;87;88;108;48;2;5;10;98m▀\033[48;2;25;34;62m▀▀\033[48;2;10;22;135m▀\033[38;2;35;45;107;48;2;31;49;155m▀\033[38;2;9;11;19;48;2;35;45;107m▀\033[48;2;0;0;0m \033[38;2;25;27;34m▀\033[38;2;49;51;64;48;2;25;34;62m▀\033[48;2;91;95;140m▀\033[38;2;9;11;19m▀\033[38;2;87;88;108;48;2;113;133;173m▀\033[38;2;0;0;0;48;2;87;88;108m▀\033[38;2;156;148;149;48;2;5;10;98m▀\033[38;2;240;226;205;48;2;31;49;155m▀\033[38;2;113;133;173;48;2;5;10;98m▀\033[38;2;91;95;140;48;2;35;45;107m▀\033[48;2;60;76;104m \033[38;2;25;27;34;48;2;87;88;108m▀\033[38;2;66;89;149;48;2;183;167;160m▀\033[38;2;25;27;34;48;2;87;88;108m▀\033[38;2;9;11;19;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;9;11;19m▀\033[38;2;25;34;62;48;2;7;11;46m▀\033[38;2;35;45;107;48;2;10;22;135m▀\033[48;2;113;133;173m▀\033[38;2;31;49;155;48;2;186;181;235m▀\033[48;2;25;27;34m▀\033[48;2;0;0;0m  \033[38;2;25;34;62;48;2;91;95;140m▀\033[38;2;154;153;244;48;2;186;181;235m▀▀\033[38;2;254;252;233;48;2;240;226;205m▀\033[38;2;113;133;173;48;2;35;45;107m▀\033[48;2;5;10;98m \033[38;2;10;22;135;48;2;113;133;173m▀\033[48;2;66;89;149m▀\033[48;2;5;10;98m \033[38;2;126;137;200;48;2;113;133;173m▀\033[38;2;154;153;244;48;2;186;181;235m▀ \033[48;2;238;224;227m \033[38;2;49;51;64;48;2;183;167;160m▀\033[0m
\033[48;2;0;0;0m    \033[38;2;7;11;46m▀\033[38;2;134;128;137;48;2;9;11;19m▀\033[38;2;254;252;233;48;2;240;226;205m▀\033[38;2;87;88;108;48;2;183;167;160m▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;25;27;34m▀\033[38;2;210;199;186;48;2;240;226;205m▀\033[48;2;254;252;233m \033[38;2;254;252;233;48;2;240;226;205m▀\033[38;2;134;128;137;48;2;5;10;98m▀ \033[38;2;10;22;135;48;2;31;49;155m▀\033[38;2;31;49;155;48;2;10;22;135m▀▀\033[38;2;10;22;135;48;2;31;49;155m▀\033[38;2;35;45;107;48;2;10;22;135m▀\033[38;2;240;226;205;48;2;91;95;140m▀\033[48;2;240;226;205m \033[48;2;254;252;233m \033[38;2;210;199;186;48;2;240;226;205m▀\033[38;2;9;11;19;48;2;25;27;34m▀\033[0m
\033[48;2;0;0;0m      \033[38;2;156;148;149;48;2;60;76;104m▀\033[48;2;254;252;233m \033[38;2;25;27;34;48;2;134;128;137m▀\033[38;2;49;51;64;48;2;87;88;108m▀\033[48;2;254;252;233m  \033[38;2;156;148;149;48;2;91;95;140m▀\033[48;2;5;10;98m \033[48;2;10;22;135m \033[48;2;31;49;155m     \033[48;2;10;22;135m \033[38;2;183;167;160;48;2;60;76;104m▀\033[38;2;240;226;205;48;2;91;95;140m▀\033[38;2;91;95;140;48;2;10;22;135m▀\033[38;2;35;45;107;48;2;31;49;155m▀\033[38;2;5;10;98m▀\033[38;2;9;11;19;48;2;10;22;135m▀\033[38;2;0;0;0;48;2;7;11;46m▀\033[0m
\033[48;2;0;0;0m      \033[38;2;25;27;34m▀\033[38;2;240;226;205;48;2;183;167;160m▀\033[48;2;254;252;233m▀\033[38;2;210;199;186;48;2;240;226;205m▀\033[48;2;254;252;233m \033[38;2;210;199;186;48;2;134;128;137m▀\033[38;2;5;10;98;48;2;7;11;46m▀\033[48;2;10;22;135m \033[38;2;31;49;155m▀\033[48;2;31;49;155m  \033[38;2;59;90;187m▀ \033[38;2;10;22;135m▀▀\033[38;2;35;45;107;48;2;7;11;46m▀\033[38;2;162;154;187;48;2;210;199;186m▀\033[38;2;126;137;200;48;2;254;252;233m▀\033[38;2;60;76;104;48;2;87;88;108m▀\033[38;2;5;10;98;48;2;0;0;0m▀\033[38;2;31;49;155;48;2;9;11;19m▀\033[48;2;10;22;135m \033[38;2;9;11;19;48;2;7;11;46m▀\033[0m
\033[48;2;0;0;0m       \033[38;2;134;128;137;48;2;87;88;108m▀\033[48;2;254;252;233m \033[38;2;240;226;205m▀\033[38;2;254;252;233;48;2;210;199;186m▀\033[38;2;49;51;64;48;2;9;11;19m▀\033[48;2;0;0;0m \033[38;2;10;22;135;48;2;5;10;98m▀\033[48;2;31;49;155m \033[38;2;59;90;187m▀\033[48;2;59;90;187m   \033[48;2;31;49;155m \033[38;2;10;22;135;48;2;35;45;107m▀\033[38;2;9;11;19;48;2;0;0;0m▀\033[38;2;156;148;149;48;2;134;128;137m▀\033[48;2;254;252;233m \033[38;2;87;88;108;48;2;183;167;160m▀\033[48;2;0;0;0m  \033[38;2;25;34;62;48;2;7;11;46m▀\033[38;2;10;22;135;48;2;31;49;155m▀\033[0m
\033[48;2;0;0;0m       \033[38;2;25;27;34m▀\033[38;2;240;226;205;48;2;49;51;64m▀\033[38;2;254;252;233;48;2;183;167;160m▀\033[38;2;156;148;149;48;2;87;88;108m▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;9;11;19m▀\033[38;2;5;10;98;48;2;31;49;155m▀ \033[48;2;59;90;187m     \033[38;2;35;45;107;48;2;31;49;155m▀\033[48;2;0;0;0m \033[38;2;87;88;108;48;2;25;27;34m▀\033[38;2;254;252;233;48;2;240;226;205m▀\033[38;2;240;226;205;48;2;254;252;233m▀\033[38;2;25;27;34;48;2;87;88;108m▀\033[48;2;0;0;0m \033[38;2;7;11;46;48;2;25;34;62m▀\033[48;2;31;49;155m \033[0m
\033[48;2;0;0;0m            \033[38;2;7;11;46;48;2;5;10;98m▀\033[38;2;10;22;135;48;2;31;49;155m▀\033[38;2;31;49;155;48;2;59;90;187m▀     ▀\033[38;2;7;11;46;48;2;25;34;62m▀\033[48;2;0;0;0m \033[38;2;210;199;186;48;2;134;128;137m▀\033[48;2;254;252;233m \033[38;2;134;128;137;48;2;183;167;160m▀\033[38;2;0;0;0;48;2;25;34;62m▀\033[38;2;66;89;149;48;2;91;95;140m▀\033[38;2;35;45;107;48;2;9;11;19m▀\033[0m
\033[48;2;0;0;0m           \033[38;2;0;0;0;48;2;9;11;19m▀\033[38;2;5;10;98;48;2;10;22;135m▀\033[38;2;10;22;135;48;2;31;49;155m▀\033[48;2;59;90;187m \033[38;2;59;90;187;48;2;106;143;201m▀\033[38;2;106;143;201;48;2;119;164;232m▀ \033[38;2;59;90;187;48;2;106;143;201m▀\033[48;2;59;90;187m \033[38;2;31;49;155m▀\033[38;2;5;10;98;48;2;31;49;155m▀\033[48;2;0;0;0m \033[38;2;49;51;64m▀\033[38;2;254;252;233;48;2;210;199;186m▀\033[38;2;240;226;205;48;2;254;252;233m▀\033[38;2;113;133;173;48;2;134;128;137m▀\033[38;2;49;51;64;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m            \033[38;2;7;11;46m▀\033[38;2;25;34;62m▀▀▀\033[38;2;49;51;64m▀\033[38;2;25;34;62m▀\033[38;2;49;51;64m▀\033[38;2;25;34;62m▀▀\033[38;2;7;11;46m▀  \033[38;2;25;27;34m▀\033[38;2;87;88;108m▀\033[38;2;25;27;34m▀\033[0m
==>08-maidfull
\033[48;2;0;0;0m              \033[38;2;0;0;0;48;2;27;27;26m▀\033[48;2;0;0;0m    \033[38;2;0;0;0;48;2;27;27;26m▀▀\033[48;2;0;0;0m  \033[48;2;27;27;26m \033[0m
\033[48;2;0;0;0m  \033[48;2;27;27;26m \033[38;2;27;27;26;48;2;0;0;0m▀ \033[38;2;0;0;0;48;2;62;73;77m▀\033[48;2;58;56;50m▀\033[48;2;27;27;26m▀▀\033[48;2;0;0;0m    \033[38;2;0;0;0;48;2;27;27;26m▀\033[38;2;27;27;26;48;2;0;0;0m▀▀   \033[48;2;27;27;26m \033[48;2;0;0;0m   \033[38;2;27;27;26m▀ \033[38;2;0;0;0;48;2;27;27;26m▀\033[0m
\033[48;2;0;0;0m \033[38;2;27;27;26m▀\033[38;2;0;0;0;48;2;27;27;26m▀\033[48;2;0;0;0m  \033[38;2;123;116;105;48;2;27;27;26m▀\033[38;2;88;97;98;48;2;0;0;0m▀          \033[38;2;27;27;26m▀\033[38;2;0;0;0;48;2;27;27;26m▀\033[38;2;27;27;26;48;2;0;0;0m▀\033[38;2;58;56;50;48;2;88;97;98m▀\033[38;2;27;27;26;48;2;85;87;82m▀\033[48;2;0;0;0m \033[48;2;58;56;50m \033[38;2;27;27;26m▀\033[0m
\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;27;27;26m▀ \033[38;2;27;27;26;48;2;0;0;0m▀     \033[38;2;0;0;0;48;2;58;56;50m▀\033[48;2;98;85;70m▀\033[48;2;0;0;0m     \033[38;2;0;0;0;48;2;27;27;26m▀\033[48;2;0;0;0m   \033[38;2;62;73;77;48;2;27;27;26m▀\033[38;2;85;87;82;48;2;0;0;0m▀ \033[38;2;27;27;26m▀▀\033[0m
\033[48;2;0;0;0m \033[48;2;27;27;26m \033[38;2;0;0;0m▀\033[48;2;0;0;0m   \033[38;2;0;0;0;48;2;58;56;50m▀\033[38;2;27;27;26;48;2;88;97;98m▀\033[48;2;58;56;50m▀\033[38;2;121;96;72;48;2;148;114;81m▀\033[38;2;164;162;151;48;2;244;225;190m▀\033[38;2;58;56;50;48;2;131;147;150m▀\033[38;2;98;85;70;48;2;185;148;110m▀\033[38;2;62;73;77;48;2;145;169;180m▀\033[38;2;108;108;101;48;2;154;146;131m▀\033[38;2;88;97;98;48;2;164;162;151m▀\033[38;2;119;129;129;48;2;131;147;150m▀\033[48;2;0;0;0m   \033[38;2;27;27;26m▀ \033[38;2;0;0;0;48;2;27;27;26m▀\033[0m
\033[48;2;0;0;0m      \033[38;2;88;97;98;48;2;85;87;82m▀\033[38;2;131;147;150;48;2;145;169;180m▀\033[38;2;0;0;0;48;2;58;56;50m▀\033[48;2;148;114;81m \033[48;2;244;225;190m \033[38;2;131;147;150;48;2;238;214;175m▀\033[38;2;185;148;110;48;2;123;116;105m▀\033[38;2;131;147;150m▀\033[38;2;154;146;131;48;2;85;87;82m▀\033[38;2;193;175;149;48;2;108;108;101m▀\033[48;2;119;129;129m \033[38;2;0;0;0;48;2;27;27;26m▀\033[0m
\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;27;27;26m▀▀\033[48;2;0;0;0m   \033[38;2;121;96;72;48;2;81;70;56m▀\033[38;2;154;146;131;48;2;184;179;166m▀\033[38;2;108;108;101m▀\033[38;2;154;146;131;48;2;81;70;56m▀\033[38;2;244;225;190;48;2;238;214;175m▀ \033[38;2;200;194;182;48;2;244;243;234m▀\033[38;2;222;218;205;48;2;154;146;131m▀\033[38;2;164;162;151;48;2;58;56;50m▀\033[38;2;27;27;26m▀\033[38;2;85;87;82;48;2;81;70;56m▀\033[38;2;27;27;26;48;2;0;0;0m▀\033[38;2;0;0;0;48;2;121;96;72m▀\033[48;2;81;70;56m▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;27;27;26m▀\033[38;2;27;27;26;48;2;58;56;50m▀\033[38;2;0;0;0;48;2;27;27;26m▀\033[0m
\033[48;2;0;0;0m \033[48;2;58;56;50m \033[38;2;62;73;77;48;2;27;27;26m▀\033[48;2;0;0;0m  \033[38;2;27;27;26m▀\033[48;2;121;96;72m▀\033[38;2;214;208;193;48;2;193;175;149m▀\033[38;2;108;108;101;48;2;200;194;182m▀\033[38;2;27;27;26;48;2;108;108;101m▀\033[48;2;224;201;167m \033[38;2;238;214;175;48;2;244;225;190m▀ \033[38;2;184;179;166m▀\033[38;2;62;73;77;48;2;193;175;149m▀\033[38;2;119;129;129;48;2;238;214;175m▀\033[38;2;98;85;70m▀\033[38;2;81;70;56;48;2;220;166;113m▀\033[38;2;231;192;145m▀\033[38;2;185;148;110;48;2;155;127;99m▀\033[48;2;0;0;0m  \033[38;2;27;27;26m▀\033[0m
\033[48;2;0;0;0m \033[48;2;62;73;77m \033[38;2;27;27;26;48;2;0;0;0m▀   \033[38;2;155;127;99;48;2;27;27;26m▀\033[38;2;238;214;175;48;2;108;108;101m▀\033[38;2;224;201;167;48;2;238;214;175m▀\033[38;2;238;214;175;48;2;244;225;190m▀\033[48;2;193;175;149m▀\033[38;2;224;201;167;48;2;155;127;99m▀\033[48;2;185;148;110m▀\033[48;2;238;214;175m \033[38;2;238;214;175;48;2;244;225;190m▀ \033[38;2;224;201;167;48;2;155;127;99m▀\033[38;2;185;148;110;48;2;27;27;26m▀\033[38;2;220;166;113;48;2;0;0;0m▀\033[38;2;58;56;50m▀     \033[38;2;27;27;26m▀\033[38;2;0;0;0;48;2;27;27;26m▀\033[0m
\033[38;2;0;0;0;48;2;27;27;26m▀\033[48;2;62;73;77m \033[48;2;0;0;0m     \033[38;2;0;0;0;48;2;85;87;82m▀\033[38;2;81;70;56;48;2;108;108;101m▀\033[38;2;193;175;149;48;2;27;27;26m▀\033[38;2;224;201;167;48;2;81;70;56m▀\033[38;2;220;166;113;48;2;123;116;105m▀\033[48;2;148;114;81m▀\033[38;2;214;208;193;48;2;185;148;110m▀\033[38;2;193;175;149m▀\033[38;2;98;85;70;48;2;155;127;99m▀\033[38;2;0;0;0;48;2;62;73;77m▀\033[48;2;123;116;105m▀\033[38;2;123;116;105;48;2;232;229;217m▀\033[48;2;154;146;131m \033[48;2;0;0;0m       \033[38;2;27;27;26m▀\033[0m
\033[48;2;27;27;26m \033[48;2;62;73;77m \033[48;2;0;0;0m   \033[38;2;0;0;0;48;2;27;27;26m▀\033[38;2;58;56;50;48;2;214;208;193m▀\033[38;2;232;229;217;48;2;244;243;234m▀\033[48;2;222;218;205m \033[38;2;119;129;129;48;2;200;194;182m▀\033[38;2;154;146;131m▀\033[38;2;184;179;166m▀\033[38;2;154;146;131;48;2;214;208;193m▀\033[38;2;231;192;145m▀\033[38;2;238;214;175;48;2;232;229;217m▀\033[48;2;200;194;182m \033[38;2;214;208;193m▀\033[38;2;232;229;217;48;2;222;218;205m▀\033[38;2;222;218;205;48;2;62;73;77m▀\033[38;2;123;116;105;48;2;0;0;0m▀ \033[38;2;85;87;82;48;2;58;56;50m▀\033[0m
\033[48;2;0;0;0m \033[38;2;62;73;77;48;2;27;27;26m▀\033[38;2;0;0;0m▀\033[48;2;0;0;0m  \033[48;2;27;27;26m \033[48;2;200;194;182m \033[38;2;214;208;193;48;2;222;218;205m▀\033[38;2;222;218;205;48;2;214;208;193m▀▀▀\033[48;2;200;194;182m \033[38;2;200;194;182;48;2;214;208;193m▀\033[38;2;244;243;234;48;2;200;194;182m▀\033[38;2;232;229;217;48;2;214;208;193m▀\033[38;2;222;218;205;48;2;232;229;217m▀\033[38;2;200;194;182;48;2;222;218;205m▀\033[38;2;214;208;193;48;2;244;243;234m▀\033[38;2;27;27;26;48;2;58;56;50m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;0;0;0;48;2;27;27;26m▀\033[38;2;164;162;151;48;2;214;208;193m▀\033[38;2;222;218;205m▀\033[38;2;232;229;217;48;2;222;218;205m▀\033[38;2;214;208;193m▀\033[48;2;200;194;182m▀\033[48;2;184;179;166m \033[38;2;184;179;166;48;2;222;218;205m▀\033[38;2;200;194;182m▀\033[48;2;184;179;166m \033[38;2;222;218;205;48;2;214;208;193m▀\033[38;2;184;179;166;48;2;222;218;205m▀\033[38;2;214;208;193;48;2;85;87;82m▀\033[38;2;27;27;26;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;27;27;26m▀\033[38;2;108;108;101;48;2;232;229;217m▀\033[38;2;85;87;82;48;2;244;243;234m▀\033[38;2;0;0;0;48;2;200;194;182m▀\033[48;2;27;27;26m \033[38;2;222;218;205;48;2;164;162;151m▀\033[38;2;214;208;193;48;2;222;218;205m▀ ▀\033[38;2;184;179;166;48;2;232;229;217m▀\033[38;2;200;194;182;48;2;222;218;205m▀\033[38;2;222;218;205;48;2;200;194;182m▀\033[38;2;164;162;151;48;2;214;208;193m▀\033[48;2;232;229;217m \033[38;2;244;243;234;48;2;222;218;205m▀\033[38;2;214;208;193;48;2;154;146;131m▀\033[38;2;27;27;26;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m \033[38;2;27;27;26m▀\033[38;2;200;194;182;48;2;164;162;151m▀\033[48;2;244;243;234m \033[38;2;244;243;234;48;2;222;218;205m▀\033[38;2;108;108;101;48;2;85;87;82m▀\033[38;2;58;56;50;48;2;0;0;0m▀\033[38;2;222;218;205;48;2;27;27;26m▀\033[38;2;232;229;217;48;2;85;87;82m▀\033[38;2;244;243;234;48;2;200;194;182m▀▀\033[48;2;184;179;166m▀\033[48;2;232;229;217m \033[38;2;232;229;217;48;2;184;179;166m▀\033[38;2;244;243;234;48;2;108;108;101m▀\033[38;2;184;179;166;48;2;27;27;26m▀\033[38;2;27;27;26;48;2;0;0;0m▀  \033[38;2;0;0;0;48;2;27;27;26m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;108;108;101;48;2;27;27;26m▀\033[38;2;244;243;234;48;2;164;162;151m▀\033[38;2;200;194;182;48;2;81;70;56m▀\033[48;2;0;0;0m    \033[38;2;27;27;26m▀▀ \033[38;2;85;87;82m▀\033[38;2;27;27;26m▀\033[0m
==>09-maidfull-rose
\033[48;2;0;0;0m              \033[38;2;0;0;0;48;2;26;24;20m▀▀\033[48;2;0;0;0m   \033[38;2;0;0;0;48;2;26;24;20m▀▀\033[48;2;0;0;0m  \033[38;2;26;24;20;48;2;50;44;40m▀\033[48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;26;24;20m▀ \033[38;2;26;24;20;48;2;0;0;0m▀ \033[38;2;0;0;0;48;2;69;59;55m▀\033[48;2;50;44;40m▀\033[48;2;26;24;20m▀ \033[48;2;0;0;0m    \033[38;2;0;0;0;48;2;26;24;20m▀\033[38;2;26;24;20;48;2;0;0;0m▀\033[38;2;0;0;0;48;2;26;24;20m▀\033[48;2;0;0;0m   \033[48;2;26;24;20m \033[48;2;0;0;0m   \033[38;2;26;24;20m▀ \033[38;2;0;0;0;48;2;26;24;20m▀\033[0m
\033[48;2;0;0;0m \033[38;2;26;24;20m▀\033[38;2;0;0;0;48;2;26;24;20m▀\033[48;2;0;0;0m  \033[38;2;136;115;100;48;2;26;24;20m▀\033[38;2;102;92;88;48;2;0;0;0m▀          \033[38;2;26;24;20m▀\033[48;2;26;24;20m \033[38;2;26;24;20;48;2;0;0;0m▀\033[38;2;50;44;40;48;2;93;76;68m▀\033[38;2;26;24;20;48;2;84;78;72m▀\033[48;2;0;0;0m \033[38;2;50;44;40;48;2;69;59;55m▀▀\033[0m
\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;26;24;20m▀ \033[38;2;26;24;20;48;2;0;0;0m▀     \033[38;2;0;0;0;48;2;50;44;40m▀\033[48;2;93;76;68m▀\033[48;2;0;0;0m     \033[38;2;0;0;0;48;2;26;24;20m▀\033[38;2;26;24;20;48;2;0;0;0m▀  \033[38;2;69;59;55;48;2;26;24;20m▀\033[38;2;93;76;68;48;2;0;0;0m▀ \033[38;2;50;44;40m▀\033[38;2;26;24;20m▀\033[0m
\033[48;2;0;0;0m \033[48;2;26;24;20m \033[38;2;0;0;0m▀\033[48;2;0;0;0m   \033[38;2;0;0;0;48;2;50;44;40m▀\033[38;2;26;24;20;48;2;102;92;88m▀\033[38;2;0;0;0;48;2;69;59;55m▀\033[38;2;122;106;99;48;2;136;115;100m▀\033[38;2;170;162;150;48;2;241;235;211m▀\033[38;2;50;44;40;48;2;156;143;133m▀\033[38;2;111;87;69;48;2;206;160;123m▀\033[38;2;84;78;72;48;2;187;174;159m▀\033[38;2;122;106;99;48;2;164;129;107m▀\033[38;2;84;78;72;48;2;185;154;134m▀\033[38;2;122;106;99;48;2;140;124;115m▀\033[48;2;0;0;0m   \033[38;2;26;24;20m▀ \033[38;2;0;0;0;48;2;26;24;20m▀\033[0m
\033[48;2;0;0;0m      \033[48;2;84;78;72m \033[38;2;156;143;133;48;2;185;154;134m▀\033[38;2;0;0;0;48;2;69;59;55m▀\033[48;2;136;115;100m \033[38;2;248;246;233;48;2;241;235;211m▀\033[38;2;170;162;150;48;2;229;219;196m▀\033[38;2;191;143;105;48;2;136;115;100m▀\033[38;2;164;129;107;48;2;140;124;115m▀\033[48;2;102;92;88m▀\033[38;2;206;160;123;48;2;122;106;99m▀\033[38;2;140;124;115m▀\033[48;2;26;24;20m \033[0m
\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;26;24;20m▀▀\033[48;2;0;0;0m   \033[38;2;111;87;69;48;2;81;67;60m▀\033[38;2;156;143;133;48;2;189;181;171m▀\033[38;2;102;92;88m▀\033[38;2;156;143;133;48;2;81;67;60m▀\033[38;2;241;235;211;48;2;229;219;196m▀\033[48;2;241;235;211m \033[38;2;204;198;188;48;2;248;246;233m▀\033[38;2;225;220;210;48;2;156;143;133m▀\033[38;2;170;162;150;48;2;50;44;40m▀\033[38;2;26;24;20;48;2;69;59;55m▀\033[38;2;111;87;69;48;2;93;76;68m▀\033[38;2;26;24;20;48;2;0;0;0m▀\033[38;2;0;0;0;48;2;111;87;69m▀\033[48;2;69;59;55m▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;26;24;20m▀\033[38;2;26;24;20;48;2;50;44;40m▀\033[38;2;0;0;0;48;2;26;24;20m▀\033[0m
\033[48;2;0;0;0m \033[48;2;50;44;40m \033[38;2;69;59;55;48;2;26;24;20m▀\033[48;2;0;0;0m  \033[48;2;26;24;20m \033[38;2;26;24;20;48;2;111;87;69m▀\033[38;2;229;219;196;48;2;187;174;159m▀\033[38;2;122;106;99;48;2;208;194;173m▀\033[38;2;26;24;20;48;2;122;106;99m▀\033[48;2;224;201;171m \033[48;2;241;235;211m  \033[38;2;189;181;171m▀\033[38;2;81;67;60;48;2;215;173;131m▀\033[38;2;140;124;115;48;2;224;201;171m▀\033[38;2;102;92;88;48;2;229;219;196m▀\033[38;2;81;67;60;48;2;215;173;131m▀\033[48;2;238;181;124m \033[38;2;191;143;105;48;2;164;129;107m▀\033[38;2;26;24;20;48;2;0;0;0m▀▀▀\033[0m
\033[48;2;0;0;0m \033[48;2;81;67;60m \033[38;2;26;24;20;48;2;0;0;0m▀   \033[38;2;140;124;115;48;2;26;24;20m▀\033[38;2;224;201;171;48;2;111;87;69m▀\033[38;2;224;194;156;48;2;229;219;196m▀\033[38;2;229;219;196;48;2;248;246;233m▀\033[48;2;187;174;159m▀\033[38;2;208;194;173;48;2;164;129;107m▀\033[48;2;191;143;105m▀\033[38;2;241;235;211;48;2;229;219;196m▀\033[38;2;229;219;196;48;2;248;246;233m▀\033[38;2;241;235;211;48;2;229;219;196m▀\033[38;2;224;194;156;48;2;164;129;107m▀\033[38;2;191;143;105;48;2;50;44;40m▀\033[38;2;206;160;123;48;2;0;0;0m▀\033[38;2;50;44;40m▀     \033[38;2;26;24;20m▀\033[38;2;0;0;0;48;2;26;24;20m▀\033[0m
\033[38;2;0;0;0;48;2;26;24;20m▀\033[38;2;69;59;55;48;2;81;67;60m▀\033[48;2;0;0;0m     \033[38;2;0;0;0;48;2;84;78;72m▀\033[38;2;81;67;60;48;2;122;106;99m▀\033[38;2;208;194;173;48;2;26;24;20m▀\033[48;2;69;59;55m▀\033[38;2;206;160;123;48;2;136;115;100m▀\033[48;2;164;129;107m▀\033[38;2;224;201;171;48;2;206;160;123m▀\033[38;2;208;194;173;48;2;191;143;105m▀\033[38;2;93;76;68;48;2;140;124;115m▀\033[38;2;0;0;0;48;2;84;78;72m▀\033[48;2;140;124;115m▀\033[38;2;122;106;99;48;2;241;235;211m▀\033[48;2;156;143;133m \033[48;2;0;0;0m       \033[38;2;26;24;20m▀\033[0m
\033[48;2;26;24;20m \033[38;2;81;67;60;48;2;93;76;68m▀\033[48;2;0;0;0m   \033[38;2;0;0;0;48;2;26;24;20m▀\033[38;2;50;44;40;48;2;225;220;210m▀\033[38;2;225;220;210;48;2;248;246;233m▀\033[48;2;229;219;196m▀\033[38;2;140;124;115;48;2;208;194;173m▀\033[38;2;156;143;133;48;2;204;198;188m▀\033[38;2;189;181;171m▀\033[38;2;185;154;134;48;2;225;220;210m▀\033[38;2;224;194;156;48;2;204;198;188m▀\033[38;2;224;201;171;48;2;241;235;211m▀\033[38;2;189;181;171;48;2;204;198;188m▀ \033[38;2;248;246;233;48;2;225;220;210m▀\033[38;2;225;220;210;48;2;84;78;72m▀\033[38;2;136;115;100;48;2;0;0;0m▀ \033[38;2;102;92;88;48;2;50;44;40m▀\033[38;2;26;24;20;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m \033[38;2;81;67;60;48;2;26;24;20m▀\033[38;2;0;0;0m▀\033[48;2;0;0;0m  \033[38;2;50;44;40;48;2;26;24;20m▀\033[48;2;204;198;188m \033[38;2;208;194;173;48;2;225;220;210m▀\033[38;2;225;220;210;48;2;204;198;188m▀\033[48;2;229;219;196m▀\033[48;2;204;198;188m▀  \033[38;2;239;235;220;48;2;208;194;173m▀\033[38;2;248;246;233;48;2;204;198;188m▀\033[38;2;239;235;220;48;2;248;246;233m▀\033[38;2;189;181;171;48;2;229;219;196m▀\033[38;2;204;198;188;48;2;239;235;220m▀\033[38;2;26;24;20;48;2;50;44;40m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;0;0;0;48;2;26;24;20m▀\033[38;2;170;162;150;48;2;225;220;210m▀\033[38;2;229;219;196;48;2;204;198;188m▀\033[48;2;239;235;220m \033[38;2;204;198;188;48;2;225;220;210m▀\033[48;2;189;181;171m▀\033[38;2;187;174;159m▀\033[38;2;189;181;171;48;2;225;220;210m▀\033[38;2;204;198;188m▀\033[38;2;187;174;159;48;2;189;181;171m▀\033[38;2;225;220;210;48;2;204;198;188m▀\033[38;2;189;181;171;48;2;225;220;210m▀\033[38;2;229;219;196;48;2;102;92;88m▀\033[38;2;26;24;20;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m \033[48;2;26;24;20m \033[38;2;122;106;99;48;2;239;235;220m▀\033[38;2;84;78;72;48;2;248;246;233m▀\033[38;2;0;0;0;48;2;204;198;188m▀\033[48;2;26;24;20m \033[38;2;225;220;210;48;2;170;162;150m▀\033[38;2;204;198;188;48;2;225;220;210m▀\033[38;2;225;220;210;48;2;229;219;196m▀\033[38;2;204;198;188;48;2;225;220;210m▀\033[38;2;187;174;159m▀\033[38;2;189;181;171m▀\033[38;2;225;220;210;48;2;189;181;171m▀\033[38;2;170;162;150;48;2;225;220;210m▀ \033[38;2;248;246;233m▀\033[38;2;204;198;188;48;2;170;162;150m▀\033[38;2;26;24;20;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m \033[38;2;26;24;20m▀\033[38;2;204;198;188;48;2;170;162;150m▀\033[38;2;239;235;220;48;2;248;246;233m▀\033[38;2;248;246;233;48;2;225;220;210m▀\033[38;2;122;106;99;48;2;102;92;88m▀\033[38;2;50;44;40;48;2;0;0;0m▀\033[38;2;225;220;210;48;2;26;24;20m▀\033[48;2;84;78;72m▀\033[38;2;239;235;220;48;2;208;194;173m▀\033[48;2;189;181;171m▀\033[48;2;187;174;159m▀\033[48;2;239;235;220m \033[38;2;239;235;220;48;2;189;181;171m▀\033[38;2;248;246;233;48;2;122;106;99m▀\033[38;2;187;174;159;48;2;26;24;20m▀\033[38;2;26;24;20;48;2;0;0;0m▀  \033[38;2;0;0;0;48;2;26;24;20m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;102;92;88;48;2;26;24;20m▀\033[38;2;248;246;233;48;2;170;162;150m▀\033[38;2;204;198;188;48;2;69;59;55m▀\033[48;2;0;0;0m    \033[38;2;26;24;20m▀▀ \033[38;2;84;78;72m▀\033[38;2;26;24;20m▀\033[0m
==>10-maidfull-lime
\033[48;2;0;0;0m              \033[38;2;0;0;0;48;2;25;25;19m▀▀▀\033[48;2;0;0;0m   \033[38;2;0;0;0;48;2;25;25;19m▀▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;25;25;19m▀ \033[0m
\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;25;25;19m▀ \033[38;2;25;25;19;48;2;0;0;0m▀ \033[38;2;0;0;0;48;2;59;58;48m▀▀\033[48;2;0;0;0m \033[48;2;25;25;19m \033[38;2;25;25;19;48;2;0;0;0m▀   \033[38;2;0;0;0;48;2;25;25;19m▀\033[38;2;25;25;19;48;2;0;0;0m▀\033[48;2;25;25;19m \033[38;2;0;0;0m▀\033[48;2;0;0;0m  \033[48;2;25;25;19m  \033[48;2;0;0;0m  \033[38;2;25;25;19m▀▀ \033[38;2;0;0;0;48;2;25;25;19m▀\033[0m
\033[48;2;0;0;0m \033[38;2;25;25;19m▀\033[48;2;25;25;19m \033[48;2;0;0;0m  \033[38;2;117;111;86;48;2;25;25;19m▀\033[38;2;123;121;110;48;2;0;0;0m▀          \033[38;2;25;25;19m▀▀▀\033[48;2;25;25;19m \033[38;2;59;58;48;48;2;140;139;112m▀\033[38;2;25;25;19;48;2;47;44;36m▀\033[48;2;0;0;0m \033[38;2;75;72;62;48;2;91;91;80m▀\033[48;2;25;25;19m \033[0m
\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;25;25;19m▀\033[38;2;25;25;19;48;2;47;44;36m▀\033[48;2;0;0;0m▀      \033[38;2;0;0;0;48;2;117;111;86m▀\033[48;2;0;0;0m   \033[38;2;0;0;0;48;2;25;25;19m▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;47;44;36m▀\033[48;2;0;0;0m \033[48;2;25;25;19m \033[48;2;0;0;0m \033[38;2;0;0;0;48;2;25;25;19m▀\033[38;2;117;111;86m▀\033[38;2;47;44;36;48;2;0;0;0m▀ \033[38;2;59;58;48m▀\033[38;2;25;25;19m▀\033[0m
\033[48;2;0;0;0m \033[48;2;25;25;19m \033[38;2;0;0;0m▀\033[48;2;0;0;0m   \033[38;2;0;0;0;48;2;47;44;36m▀\033[38;2;25;25;19;48;2;117;111;86m▀\033[48;2;75;72;62m▀\033[38;2;59;58;48;48;2;97;85;61m▀\033[38;2;207;192;166;48;2;252;227;192m▀\033[38;2;59;58;48;48;2;183;179;168m▀\033[38;2;97;85;61;48;2;149;124;92m▀\033[38;2;75;72;62;48;2;207;192;166m▀\033[38;2;117;111;86;48;2;160;157;143m▀\033[38;2;81;81;61;48;2;186;147;109m▀\033[38;2;140;139;112;48;2;179;170;145m▀\033[38;2;47;44;36;48;2;75;72;62m▀\033[48;2;0;0;0m   \033[38;2;25;25;19m▀ \033[38;2;0;0;0;48;2;25;25;19m▀\033[0m
\033[48;2;0;0;0m      \033[48;2;59;58;48m \033[48;2;179;170;145m \033[38;2;25;25;19;48;2;97;85;61m▀\033[38;2;47;44;36m▀\033[48;2;252;227;192m \033[38;2;207;192;166m▀\033[48;2;163;149;117m \033[38;2;186;147;109;48;2;117;111;86m▀\033[38;2;140;139;112m▀\033[38;2;208;160;114;48;2;81;81;61m▀\033[38;2;179;170;145;48;2;140;139;112m▀\033[48;2;81;81;61m \033[48;2;0;0;0m     \033[38;2;25;25;19m▀\033[0m
\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;25;25;19m▀▀\033[48;2;0;0;0m   \033[38;2;80;66;48;48;2;47;44;36m▀\033[38;2;163;149;117;48;2;160;157;143m▀\033[38;2;123;121;110;48;2;218;216;206m▀\033[48;2;91;91;80m▀\033[38;2;252;227;192;48;2;207;192;166m▀\033[48;2;252;227;192m \033[38;2;219;212;196;48;2;235;232;221m▀\033[38;2;202;200;190;48;2;218;216;206m▀\033[38;2;218;216;206;48;2;59;58;48m▀\033[38;2;59;58;48;48;2;47;44;36m▀\033[38;2;80;66;48;48;2;91;91;80m▀\033[38;2;75;72;62;48;2;25;25;19m▀\033[38;2;0;0;0m▀\033[48;2;117;111;86m▀\033[48;2;25;25;19m▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;25;25;19m▀\033[38;2;25;25;19;48;2;47;44;36m▀\033[0m
\033[48;2;0;0;0m \033[38;2;47;44;36;48;2;59;58;48m▀\033[38;2;75;72;62;48;2;47;44;36m▀\033[48;2;0;0;0m  \033[38;2;25;25;19m▀\033[38;2;0;0;0;48;2;97;85;61m▀\033[38;2;207;192;166;48;2;208;160;114m▀\033[38;2;179;170;145;48;2;219;212;196m▀\033[38;2;25;25;19;48;2;117;111;86m▀\033[38;2;179;170;145;48;2;207;192;166m▀\033[48;2;252;227;192m  \033[38;2;234;228;212;48;2;235;232;221m▀\033[38;2;117;111;86;48;2;207;192;166m▀\033[38;2;91;91;80;48;2;218;188;148m▀\033[38;2;142;133;118;48;2;252;227;192m▀\033[38;2;47;44;36;48;2;235;206;168m▀\033[38;2;186;147;109;48;2;208;160;114m▀\033[48;2;229;166;118m \033[48;2;80;66;48m \033[48;2;0;0;0m \033[38;2;25;25;19m▀▀\033[0m
\033[48;2;0;0;0m \033[48;2;81;81;61m \033[38;2;25;25;19;48;2;0;0;0m▀   \033[38;2;117;111;86m▀\033[38;2;235;206;168;48;2;97;85;61m▀\033[38;2;218;188;148;48;2;235;206;168m▀\033[38;2;235;206;168;48;2;248;248;241m▀\033[38;2;252;227;192;48;2;235;206;168m▀\033[38;2;235;206;168;48;2;186;147;109m▀▀\033[38;2;252;227;192;48;2;218;188;148m▀\033[48;2;252;227;192m  \033[38;2;252;227;192;48;2;218;188;148m▀\033[38;2;208;160;114;48;2;81;81;61m▀\033[48;2;0;0;0m▀\033[38;2;149;124;92m▀      \033[48;2;25;25;19m \033[38;2;0;0;0m▀\033[0m
\033[38;2;0;0;0;48;2;25;25;19m▀\033[48;2;81;81;61m \033[48;2;0;0;0m     \033[38;2;0;0;0;48;2;59;58;48m▀\033[38;2;47;44;36;48;2;142;133;118m▀\033[38;2;179;170;145;48;2;47;44;36m▀\033[38;2;219;212;196m▀\033[38;2;208;160;114;48;2;123;121;110m▀\033[48;2;149;124;92m▀\033[38;2;218;188;148;48;2;186;147;109m▀\033[38;2;235;206;168;48;2;208;160;114m▀\033[38;2;163;149;117;48;2;149;124;92m▀\033[38;2;47;44;36;48;2;123;121;110m▀\033[38;2;0;0;0;48;2;75;72;62m▀\033[38;2;59;58;48;48;2;202;200;190m▀\033[38;2;183;179;168;48;2;219;212;196m▀\033[38;2;81;81;61;48;2;91;91;80m▀\033[48;2;0;0;0m       \033[38;2;25;25;19m▀\033[0m
\033[38;2;0;0;0;48;2;25;25;19m▀\033[48;2;81;81;61m \033[48;2;0;0;0m    \033[38;2;25;25;19;48;2;183;179;168m▀\033[38;2;202;200;190;48;2;248;248;241m▀\033[38;2;248;248;241;48;2;235;232;221m▀\033[38;2;160;157;143;48;2;207;192;166m▀\033[38;2;123;121;110;48;2;202;200;190m▀\033[48;2;183;179;168m \033[38;2;160;157;143;48;2;218;216;206m▀\033[38;2;218;188;148;48;2;207;192;166m▀\033[38;2;235;206;168;48;2;234;228;212m▀\033[38;2;207;192;166;48;2;219;212;196m▀\033[38;2;202;200;190;48;2;183;179;168m▀\033[48;2;218;216;206m \033[38;2;235;232;221;48;2;160;157;143m▀\033[38;2;202;200;190;48;2;25;25;19m▀\033[38;2;47;44;36;48;2;0;0;0m▀\033[48;2;25;25;19m \033[38;2;75;72;62;48;2;47;44;36m▀\033[0m
\033[48;2;0;0;0m \033[38;2;81;81;61;48;2;25;25;19m▀ \033[48;2;0;0;0m  \033[38;2;25;25;19m▀\033[38;2;183;179;168;48;2;179;170;145m▀\033[38;2;202;200;190;48;2;235;232;221m▀\033[38;2;218;216;206;48;2;219;212;196m▀\033[38;2;234;228;212;48;2;218;216;206m▀\033[38;2;218;216;206;48;2;202;200;190m▀\033[38;2;219;212;196m▀ \033[38;2;234;228;212;48;2;219;212;196m▀\033[38;2;248;248;241;48;2;183;179;168m▀\033[48;2;234;228;212m▀\033[38;2;219;212;196m▀\033[48;2;248;248;241m▀\033[48;2;160;157;143m \033[0m
\033[48;2;0;0;0m      \033[38;2;142;133;118;48;2;202;200;190m▀\033[48;2;218;216;206m \033[38;2;235;232;221m▀\033[38;2;218;216;206;48;2;234;228;212m▀\033[48;2;202;200;190m \033[38;2;202;200;190;48;2;183;179;168m▀\033[38;2;179;170;145;48;2;202;200;190m▀\033[38;2;202;200;190;48;2;234;228;212m▀\033[38;2;179;170;145;48;2;202;200;190m▀ \033[38;2;183;179;168;48;2;218;216;206m▀\033[38;2;219;212;196;48;2;183;179;168m▀\033[38;2;142;133;118;48;2;25;25;19m▀\033[0m
\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;25;25;19m▀\033[38;2;91;91;80;48;2;234;228;212m▀\033[48;2;248;248;241m▀\033[38;2;25;25;19;48;2;218;216;206m▀\033[38;2;0;0;0;48;2;47;44;36m▀\033[38;2;219;212;196;48;2;142;133;118m▀\033[48;2;234;228;212m▀\033[48;2;218;216;206m  \033[38;2;183;179;168m▀\033[48;2;235;232;221m▀\033[38;2;218;216;206;48;2;202;200;190m▀\033[38;2;183;179;168m▀\033[48;2;235;232;221m▀\033[38;2;248;248;241;48;2;218;216;206m▀\033[48;2;219;212;196m▀\033[38;2;117;111;86;48;2;59;58;48m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;202;200;190;48;2;160;157;143m▀\033[48;2;248;248;241m \033[38;2;248;248;241;48;2;235;232;221m▀\033[38;2;160;157;143;48;2;123;121;110m▀\033[38;2;25;25;19;48;2;0;0;0m▀\033[38;2;202;200;190;48;2;25;25;19m▀\033[38;2;234;228;212;48;2;75;72;62m▀\033[48;2;160;157;143m▀\033[38;2;248;248;241;48;2;219;212;196m▀\033[48;2;160;157;143m▀\033[48;2;234;228;212m▀\033[48;2;218;216;206m▀\033[48;2;142;133;118m▀\033[38;2;218;216;206;48;2;75;72;62m▀\033[38;2;75;72;62;48;2;0;0;0m▀  \033[38;2;0;0;0;48;2;25;25;19m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;91;91;80;48;2;25;25;19m▀\033[38;2;248;248;241;48;2;160;157;143m▀\033[38;2;234;228;212;48;2;91;91;80m▀\033[38;2;25;25;19;48;2;0;0;0m▀    \033[38;2;47;44;36m▀ \033[38;2;75;72;62m▀\033[38;2;47;44;36m▀\033[0m
==>11-fandom
\033[48;2;0;0;0m           \033[38;2;0;0;0;48;2;22;14;6m▀\033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;22;14;6m▀\033[0m
\033[0m
\033[48;2;0;0;0m   \033[38;2;0;0;0;48;2;22;14;6m▀\033[48;2;34;27;21m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;22;14;6m▀\033[38;2;129;113;89;48;2;222;200;154m▀\033[38;2;82;71;58;48;2;175;161;124m▀\033[38;2;0;0;0;48;2;22;14;6m▀\033[48;2;0;0;0m \033[38;2;22;14;6;48;2;115;103;83m▀\033[38;2;34;27;21;48;2;160;147;118m▀\033[38;2;39;34;28;48;2;129;113;89m▀\033[38;2;0;0;0;48;2;82;71;58m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;102;91;68;48;2;115;103;83m▀\033[38;2;254;245;184;48;2;160;147;118m▀\033[48;2;115;103;83m▀\033[38;2;175;161;124;48;2;224;205;162m▀\033[38;2;0;0;0;48;2;193;168;131m▀\033[48;2;191;175;135m \033[38;2;254;245;184;48;2;153;129;108m▀\033[48;2;160;147;118m▀\033[48;2;175;161;124m▀\033[38;2;160;147;118;48;2;222;200;154m▀\033[38;2;102;91;68;48;2;129;113;89m▀\033[48;2;0;0;0m \033[38;2;39;34;28;48;2;222;200;154m▀\033[38;2;0;0;0;48;2;66;56;42m▀\033[0m
\033[48;2;0;0;0m  \033[48;2;136;128;117m \033[38;2;129;113;89;48;2;195;185;162m▀\033[38;2;58;56;48;48;2;115;103;83m▀\033[38;2;246;227;175;48;2;231;214;176m▀\033[38;2;254;245;184;48;2;224;205;162m▀\033[38;2;246;227;175;48;2;254;245;184m▀\033[38;2;157;152;143;48;2;208;203;182m▀\033[38;2;22;14;6;48;2;82;71;58m▀\033[38;2;160;147;118;48;2;191;175;135m▀\033[38;2;136;128;117;48;2;231;214;176m▀\033[38;2;0;0;0;48;2;58;56;48m▀\033[48;2;0;0;0m \033[38;2;222;200;154;48;2;193;168;131m▀\033[38;2;82;71;58;48;2;34;27;21m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;222;200;154;48;2;224;205;162m▀\033[48;2;254;245;184m \033[48;2;246;227;175m \033[38;2;231;214;176m▀  \033[38;2;254;245;184m▀\033[38;2;198;177;138;48;2;254;245;184m▀\033[38;2;222;200;154m▀\033[38;2;254;245;184;48;2;246;227;175m▀\033[38;2;66;56;42;48;2;39;34;28m▀\033[48;2;0;0;0m \033[38;2;34;27;21m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;82;71;58m▀\033[38;2;254;245;184;48;2;115;103;83m▀\033[48;2;254;245;184m \033[38;2;246;227;175m▀\033[38;2;254;245;184;48;2;224;205;162m▀\033[48;2;195;185;162m▀\033[38;2;246;227;175;48;2;231;214;176m▀\033[38;2;222;200;154;48;2;208;203;182m▀\033[38;2;231;214;176;48;2;254;245;184m▀\033[38;2;224;205;162;48;2;222;200;154m▀\033[38;2;34;27;21;48;2;22;14;6m▀\033[0m
\033[48;2;0;0;0m    \033[38;2;66;56;42m▀\033[38;2;224;205;162;48;2;22;14;6m▀\033[38;2;254;245;184;48;2;82;71;58m▀\033[48;2;102;91;68m▀\033[38;2;246;227;175;48;2;198;177;138m▀\033[38;2;231;214;176;48;2;222;200;154m▀\033[48;2;246;227;175m \033[38;2;175;161;124;48;2;115;103;83m▀\033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;22;14;6m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;0;0;0;48;2;22;14;6m▀\033[48;2;0;0;0m \033[38;2;22;14;6;48;2;115;103;83m▀\033[38;2;222;200;154;48;2;254;245;184m▀\033[38;2;231;214;176m▀\033[38;2;246;227;175;48;2;175;161;124m▀\033[38;2;66;56;42;48;2;0;0;0m▀ \033[38;2;0;0;0;48;2;22;14;6m▀ \033[38;2;22;14;6;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m     \033[48;2;22;14;6m \033[48;2;0;0;0m \033[38;2;102;91;68;48;2;22;14;6m▀\033[38;2;254;245;184;48;2;39;34;28m▀\033[38;2;129;113;89;48;2;0;0;0m▀\033[38;2;22;14;6m▀\033[38;2;0;0;0;48;2;22;14;6m▀ ▀▀\033[38;2;22;14;6;48;2;0;0;0m▀\033[38;2;0;0;0;48;2;22;14;6m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;22;14;6m▀\033[38;2;22;14;6;48;2;0;0;0m▀ \033[38;2;33;17;14;48;2;67;26;25m▀ \033[48;2;0;0;0m   \033[38;2;67;26;25;48;2;91;34;33m▀\033[48;2;22;14;6m \033[48;2;0;0;0m     \033[38;2;22;14;6m▀\033[38;2;0;0;0;48;2;22;14;6m▀\033[0m
\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;22;14;6m▀\033[38;2;22;14;6;48;2;0;0;0m▀\033[48;2;22;14;6m \033[48;2;0;0;0m \033[38;2;56;21;22;48;2;67;26;25m▀\033[48;2;22;14;6m \033[48;2;0;0;0m  \033[38;2;22;14;6;48;2;33;17;14m▀\033[48;2;91;34;33m \033[48;2;0;0;0m        \033[38;2;22;14;6m▀\033[38;2;0;0;0;48;2;22;14;6m▀\033[0m
\033[48;2;0;0;0m \033[48;2;22;14;6m \033[38;2;0;0;0m▀ ▀\033[48;2;56;21;22m \033[48;2;0;0;0m   \033[38;2;56;21;22;48;2;67;26;25m▀\033[38;2;67;26;25;48;2;56;21;22m▀\033[48;2;0;0;0m          \033[38;2;22;14;6m▀\033[38;2;0;0;0;48;2;22;14;6m▀\033[0m
\033[48;2;22;14;6m \033[48;2;0;0;0m \033[48;2;22;14;6m \033[48;2;0;0;0m \033[38;2;22;14;6m▀\033[38;2;33;17;14m▀   \033[48;2;106;36;40m \033[38;2;56;21;22;48;2;33;17;14m▀\033[48;2;0;0;0m   \033[38;2;22;14;6m▀\033[38;2;0;0;0;48;2;22;14;6m▀\033[48;2;0;0;0m         \033[38;2;0;0;0;48;2;22;14;6m▀\033[0m
\033[48;2;0;0;0m \033[48;2;22;14;6m \033[38;2;22;14;6;48;2;0;0;0m▀      \033[38;2;91;34;33;48;2;67;26;25m▀\033[38;2;22;14;6;48;2;0;0;0m▀        \033[38;2;0;0;0;48;2;22;14;6m▀\033[48;2;33;17;14m▀\033[0m
\033[48;2;0;0;0m                    \033[38;2;22;14;6m▀\033[0m
==>12-fandom-teal
\033[48;2;0;0;0m           \033[38;2;0;0;0;48;2;20;15;6m▀\033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;20;15;6m▀\033[0m
\033[0m
\033[48;2;0;0;0m   \033[38;2;0;0;0;48;2;20;15;6m▀\033[48;2;24;26;21m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;20;15;6m▀\033[38;2;144;133;111;48;2;234;210;164m▀\033[38;2;92;82;64;48;2;164;150;116m▀\033[38;2;0;0;0;48;2;20;15;6m▀\033[48;2;0;0;0m \033[38;2;20;15;6;48;2;106;95;75m▀\033[38;2;24;26;21;48;2;164;150;116m▀\033[38;2;38;36;26;48;2;144;133;111m▀\033[38;2;0;0;0;48;2;92;82;64m▀\033[0m
\033[48;2;0;0;0m  \033[48;2;106;100;77m \033[38;2;253;245;184;48;2;164;150;116m▀\033[48;2;121;107;82m▀\033[38;2;164;150;116;48;2;234;210;164m▀\033[38;2;0;0;0;48;2;184;167;130m▀\033[38;2;184;167;130;48;2;197;188;155m▀\033[38;2;253;245;184;48;2;144;133;111m▀\033[48;2;164;150;116m▀\033[48;2;184;167;130m▀\033[38;2;164;150;116;48;2;217;199;159m▀\033[38;2;92;82;64;48;2;121;107;82m▀\033[48;2;0;0;0m \033[38;2;38;36;26;48;2;217;199;159m▀\033[38;2;0;0;0;48;2;65;57;40m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;159;153;133;48;2;144;133;111m▀\033[38;2;144;133;111;48;2;197;188;155m▀\033[38;2;36;68;60;48;2;95;133;116m▀\033[38;2;244;226;175;48;2;234;210;164m▀\033[38;2;253;245;184m▀\033[48;2;244;226;175m \033[38;2;159;153;133;48;2;217;199;159m▀\033[38;2;24;26;21;48;2;36;68;60m▀\033[48;2;159;153;133m \033[38;2;144;133;111;48;2;244;226;175m▀\033[38;2;0;0;0;48;2;65;57;40m▀\033[48;2;0;0;0m \033[38;2;217;199;159;48;2;184;167;130m▀\033[38;2;78;76;57;48;2;24;26;21m▀\033[0m
\033[48;2;0;0;0m  \033[48;2;217;199;159m \033[48;2;253;245;184m \033[48;2;244;226;175m \033[48;2;234;210;164m \033[48;2;244;226;175m  \033[38;2;253;245;184m▀\033[38;2;184;167;130;48;2;253;245;184m▀\033[38;2;217;199;159m▀\033[38;2;253;245;184;48;2;244;226;175m▀\033[38;2;65;57;40;48;2;38;36;26m▀\033[48;2;0;0;0m \033[38;2;24;26;21m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;78;76;57m▀\033[38;2;253;245;184;48;2;106;95;75m▀\033[48;2;253;245;184m \033[38;2;244;226;175m▀\033[38;2;253;245;184;48;2;234;210;164m▀\033[48;2;197;188;155m▀\033[48;2;244;226;175m▀\033[48;2;217;199;159m \033[38;2;234;210;164;48;2;253;245;184m▀\033[48;2;217;199;159m▀\033[38;2;24;26;21;48;2;20;15;6m▀\033[0m
\033[48;2;0;0;0m    \033[38;2;65;57;40m▀\033[38;2;217;199;159;48;2;20;15;6m▀\033[38;2;253;245;184;48;2;92;82;64m▀\033[38;2;244;226;175;48;2;106;95;75m▀\033[48;2;184;167;130m▀\033[38;2;234;210;164;48;2;217;199;159m▀\033[38;2;253;245;184;48;2;244;226;175m▀\033[38;2;164;150;116;48;2;121;107;82m▀\033[48;2;0;0;0m  \033[48;2;20;15;6m \033[0m
\033[48;2;0;0;0m     \033[38;2;0;0;0;48;2;20;15;6m▀\033[48;2;0;0;0m \033[38;2;20;15;6;48;2;106;95;75m▀\033[38;2;217;199;159;48;2;253;245;184m▀\033[38;2;234;210;164m▀\033[38;2;244;226;175;48;2;184;167;130m▀\033[38;2;65;57;40;48;2;0;0;0m▀ \033[38;2;0;0;0;48;2;20;15;6m▀ \033[38;2;20;15;6;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m     \033[48;2;20;15;6m \033[48;2;0;0;0m \033[38;2;92;82;64;48;2;20;15;6m▀\033[38;2;244;226;175;48;2;38;36;26m▀\033[38;2;121;107;82;48;2;0;0;0m▀\033[38;2;20;15;6m▀\033[38;2;0;0;0;48;2;20;15;6m▀ ▀▀\033[38;2;20;15;6;48;2;0;0;0m▀\033[38;2;0;0;0;48;2;20;15;6m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;20;15;6m▀\033[38;2;20;15;6;48;2;0;0;0m▀ \033[38;2;12;32;29;48;2;22;46;40m▀\033[38;2;31;65;58m▀\033[48;2;0;0;0m   \033[38;2;43;71;58;48;2;36;68;60m▀\033[38;2;24;26;21;48;2;0;0;0m▀     \033[38;2;20;15;6m▀\033[38;2;0;0;0;48;2;20;15;6m▀\033[0m
\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;20;15;6m▀\033[38;2;20;15;6;48;2;0;0;0m▀\033[48;2;20;15;6m \033[48;2;0;0;0m \033[38;2;22;46;40;48;2;36;68;60m▀\033[38;2;12;32;29;48;2;20;15;6m▀\033[48;2;0;0;0m  \033[38;2;20;15;6;48;2;12;32;29m▀\033[38;2;36;85;77;48;2;43;71;58m▀\033[48;2;0;0;0m        \033[38;2;20;15;6m▀\033[38;2;0;0;0;48;2;20;15;6m▀\033[0m
\033[48;2;0;0;0m \033[48;2;20;15;6m \033[38;2;0;0;0m▀ ▀\033[48;2;22;46;40m \033[48;2;0;0;0m   \033[38;2;22;46;40;48;2;36;68;60m▀\033[48;2;31;65;58m \033[48;2;0;0;0m          \033[38;2;20;15;6m▀\033[38;2;0;0;0;48;2;20;15;6m▀\033[0m
\033[48;2;20;15;6m \033[48;2;0;0;0m \033[48;2;20;15;6m \033[48;2;0;0;0m \033[38;2;24;26;21m▀▀   \033[38;2;36;85;77;48;2;47;94;86m▀\033[48;2;22;46;40m \033[48;2;0;0;0m   \033[38;2;20;15;6m▀\033[38;2;0;0;0;48;2;20;15;6m▀\033[48;2;0;0;0m         \033[38;2;0;0;0;48;2;20;15;6m▀\033[0m
\033[48;2;0;0;0m \033[48;2;20;15;6m \033[38;2;20;15;6;48;2;0;0;0m▀      \033[38;2;47;94;86;48;2;22;46;40m▀\033[38;2;24;26;21;48;2;0;0;0m▀        \033[38;2;0;0;0;48;2;20;15;6m▀\033[48;2;24;26;21m▀\033[0m
\033[48;2;0;0;0m                    \033[38;2;20;15;6m▀\033[0m
==>13-fox
\033[48;2;0;0;0m                \033[38;2;0;0;0;48;2;25;20;18m▀\033[48;2;52;50;62m▀\033[48;2;83;65;59m▀\033[48;2;123;84;67m▀\033[48;2;52;50;62m▀\033[48;2;85;76;81m▀▀\033[0m
\033[38;2;83;65;59;48;2;25;20;18m▀\033[48;2;193;164;160m▀\033[38;2;123;84;67;48;2;228;164;129m▀\033[38;2;152;99;72;48;2;247;178;137m▀▀\033[38;2;184;131;104;48;2;220;145;104m▀\033[38;2;152;99;72;48;2;192;117;79m▀\033[38;2;83;65;59m▀\033[38;2;33;40;55;48;2;220;145;104m▀\033[38;2;52;50;62m▀\033[48;2;247;178;137m▀\033[38;2;123;84;67m▀\033[38;2;83;65;59m▀\033[38;2;25;20;18m▀\033[38;2;83;65;59;48;2;228;164;129m▀\033[38;2;152;99;72;48;2;192;117;79m▀\033[38;2;220;145;104m▀\033[38;2;247;178;137;48;2;220;145;104m▀\033[48;2;228;164;129m▀\033[48;2;247;178;137m \033[38;2;228;164;129;48;2;225;190;181m▀\033[38;2;193;164;160;48;2;213;192;193m▀\033[38;2;52;50;62;48;2;25;20;18m▀\033[0m
\033[48;2;0;0;0m \033[38;2;129;103;97m▀\033[38;2;243;212;208;48;2;213;192;193m▀\033[38;2;228;164;129;48;2;245;225;226m▀\033[38;2;247;178;137;48;2;243;212;205m▀\033[38;2;192;117;79;48;2;193;164;160m▀\033[48;2;247;178;137m▀\033[38;2;228;164;129;48;2;220;145;104m▀\033[38;2;220;145;104;48;2;228;164;129m▀\033[48;2;247;178;137m  \033[38;2;228;164;129m▀\033[38;2;129;103;97;48;2;85;76;81m▀\033[38;2;211;157;133;48;2;33;40;55m▀\033[38;2;228;164;129;48;2;129;103;97m▀\033[48;2;228;164;129m \033[38;2;192;117;79m▀\033[48;2;247;178;137m▀\033[38;2;228;164;129;48;2;229;208;211m▀\033[38;2;225;190;181;48;2;243;219;218m▀\033[38;2;243;219;218;48;2;245;225;226m▀\033[38;2;157;146;150;48;2;33;40;55m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;33;40;55m▀\033[38;2;229;208;211;48;2;25;20;18m▀\033[38;2;213;192;193;48;2;129;103;97m▀\033[38;2;211;157;133;48;2;228;164;129m▀\033[38;2;247;178;137;48;2;220;145;104m▀\033[48;2;228;164;129m \033[38;2;247;178;137;48;2;220;145;104m▀\033[48;2;247;178;137m \033[38;2;247;178;137;48;2;211;157;133m▀\033[48;2;152;99;72m▀\033[38;2;184;131;104;48;2;192;117;79m▀\033[38;2;33;40;55;48;2;123;84;67m▀ \033[38;2;220;145;104m▀\033[38;2;228;164;129;48;2;52;50;62m▀\033[38;2;220;145;104;48;2;129;103;97m▀\033[38;2;225;190;181;48;2;247;178;137m▀\033[38;2;229;208;211;48;2;52;50;62m▀\033[38;2;129;103;97;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m    \033[38;2;152;99;72;48;2;184;131;104m▀\033[48;2;247;178;137m \033[38;2;220;145;104;48;2;192;117;79m▀\033[38;2;228;164;129;48;2;211;157;133m▀\033[38;2;211;157;133;48;2;243;219;218m▀\033[38;2;220;145;104;48;2;247;178;137m▀\033[38;2;129;103;97;48;2;123;84;67m▀\033[38;2;33;40;55;48;2;129;103;97m▀\033[38;2;83;65;59;48;2;192;117;79m▀\033[38;2;123;84;67;48;2;83;65;59m▀ \033[38;2;33;40;55;48;2;192;117;79m▀\033[38;2;52;50;62;48;2;184;131;104m▀\033[38;2;228;164;129;48;2;247;178;137m▀\033[48;2;220;145;104m▀\033[38;2;184;131;104;48;2;25;20;18m▀ \033[0m
\033[48;2;0;0;0m  \033[38;2;25;20;18m▀\033[38;2;83;65;59;48;2;123;84;67m▀\033[38;2;184;131;104;48;2;228;164;129m▀\033[38;2;247;178;137m▀\033[38;2;220;145;104;48;2;247;178;137m▀\033[38;2;211;157;133;48;2;193;164;160m▀\033[38;2;252;240;241;48;2;245;225;226m▀\033[38;2;245;225;226;48;2;252;240;241m▀\033[38;2;228;164;129;48;2;243;212;208m▀\033[48;2;247;178;137m \033[38;2;228;164;129m▀\033[38;2;52;50;62;48;2;184;131;104m▀\033[38;2;33;40;55;48;2;123;84;67m▀\033[48;2;184;131;104m \033[48;2;247;178;137m \033[48;2;228;164;129m \033[38;2;247;178;137m▀\033[38;2;83;65;59m▀\033[38;2;0;0;0;48;2;33;40;55m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;83;65;59m▀\033[38;2;184;131;104;48;2;228;164;129m▀\033[38;2;247;178;137;48;2;220;145;104m▀\033[38;2;228;164;129m▀\033[48;2;228;164;129m \033[38;2;225;190;181;48;2;177;146;139m▀\033[38;2;245;225;226;48;2;129;103;97m▀\033[38;2;252;240;241;48;2;213;192;193m▀\033[48;2;252;240;241m \033[38;2;245;225;226;48;2;213;192;193m▀\033[38;2;193;164;160;48;2;88;110;125m▀\033[38;2;177;146;139;48;2;157;146;150m▀\033[38;2;211;157;133;48;2;225;190;181m▀\033[38;2;247;178;137;48;2;220;145;104m▀▀▀\033[48;2;228;164;129m \033[38;2;247;178;137;48;2;220;145;104m▀\033[38;2;184;131;104;48;2;228;164;129m▀\033[38;2;0;0;0;48;2;25;20;18m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;192;117;79;48;2;152;99;72m▀\033[38;2;220;145;104;48;2;192;117;79m▀▀▀\033[48;2;220;145;104m \033[38;2;211;157;133m▀\033[38;2;213;192;193;48;2;229;208;211m▀\033[38;2;229;208;211;48;2;252;240;241m▀\033[38;2;243;212;208;48;2;245;225;226m▀\033[38;2;243;219;218m▀\033[48;2;252;240;241m  \033[38;2;225;190;181;48;2;211;157;133m▀\033[48;2;220;145;104m \033[38;2;220;145;104;48;2;192;117;79m▀▀\033[48;2;220;145;104m \033[38;2;192;117;79m▀\033[38;2;220;145;104;48;2;192;117;79m▀\033[38;2;52;50;62;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m   \033[38;2;192;117;79;48;2;25;20;18m▀\033[48;2;123;84;67m▀\033[48;2;192;117;79m  \033[38;2;192;117;79;48;2;220;145;104m▀\033[38;2;152;99;72m▀\033[38;2;177;146;139;48;2;184;131;104m▀\033[38;2;243;212;208;48;2;229;208;211m▀\033[38;2;245;225;226;48;2;193;164;160m▀\033[38;2;243;219;218;48;2;152;99;72m▀\033[38;2;184;131;104;48;2;123;84;67m▀\033[38;2;152;99;72;48;2;220;145;104m▀\033[48;2;192;117;79m \033[38;2;192;117;79;48;2;152;99;72m▀▀\033[48;2;52;50;62m▀\033[38;2;152;99;72;48;2;0;0;0m▀\033[38;2;25;20;18m▀\033[0m
\033[48;2;0;0;0m   \033[38;2;85;76;81;48;2;213;192;193m▀\033[38;2;213;192;193;48;2;243;219;218m▀\033[38;2;243;219;218;48;2;157;146;150m▀\033[38;2;225;190;181;48;2;193;164;160m▀\033[38;2;129;103;97;48;2;52;50;62m▀\033[38;2;123;84;67;48;2;33;40;55m▀\033[38;2;211;157;133;48;2;129;103;97m▀\033[38;2;225;190;181;48;2;184;131;104m▀\033[38;2;177;146;139;48;2;213;192;193m▀\033[38;2;184;131;104;48;2;245;225;226m▀\033[38;2;129;103;97m▀\033[38;2;52;50;62;48;2;146;135;142m▀\033[48;2;33;40;55m▀ \033[38;2;33;40;55;48;2;52;50;62m▀\033[38;2;0;0;0;48;2;33;40;55m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;52;50;62;48;2;83;65;59m▀\033[38;2;225;190;181;48;2;211;157;133m▀\033[38;2;228;164;129;48;2;243;212;205m▀\033[38;2;129;103;97;48;2;193;164;160m▀\033[38;2;33;40;55;48;2;52;50;62m▀\033[38;2;85;76;81;48;2;211;157;133m▀\033[48;2;184;131;104m▀\033[38;2;52;50;62;48;2;83;65;59m▀\033[48;2;184;131;104m▀\033[38;2;129;103;97;48;2;85;76;81m▀\033[38;2;85;76;81;48;2;52;50;62m▀\033[38;2;146;135;142m▀\033[38;2;252;240;241;48;2;213;192;193m▀\033[38;2;85;76;81;48;2;229;208;211m▀\033[38;2;33;40;55;48;2;193;164;160m▀\033[38;2;129;103;97;48;2;243;212;205m▀\033[38;2;123;84;67;48;2;129;103;97m▀\033[0m
\033[48;2;0;0;0m   \033[48;2;213;192;193m \033[48;2;252;240;241m \033[38;2;129;103;97;48;2;145;133;140m▀\033[48;2;52;50;62m \033[38;2;247;178;137;48;2;220;145;104m▀\033[38;2;129;103;97;48;2;152;99;72m▀\033[48;2;123;84;67m \033[48;2;247;178;137m \033[38;2;129;103;97;48;2;85;76;81m▀\033[38;2;85;76;81;48;2;52;50;62m▀\033[38;2;52;50;62;48;2;33;40;55m▀\033[38;2;193;164;160;48;2;145;133;140m▀\033[48;2;252;240;241m \033[48;2;229;208;211m \033[38;2;252;240;241;48;2;245;225;226m▀\033[38;2;85;76;81;48;2;83;65;59m▀\033[0m
\033[48;2;0;0;0m   \033[38;2;213;192;193;48;2;83;65;59m▀\033[48;2;252;240;241m \033[38;2;225;190;181;48;2;193;164;160m▀\033[38;2;33;40;55;48;2;25;20;18m▀\033[38;2;83;65;59;48;2;33;40;55m▀\033[38;2;184;131;104;48;2;211;157;133m▀\033[38;2;85;76;81;48;2;129;103;97m▀\033[38;2;129;103;97;48;2;85;76;81m▀\033[38;2;85;76;81;48;2;83;65;59m▀\033[38;2;33;40;55;48;2;52;50;62m▀\033[48;2;33;40;55m \033[38;2;129;103;97;48;2;85;76;81m▀\033[38;2;252;240;241;48;2;243;219;218m▀\033[48;2;252;240;241m \033[38;2;243;219;218;48;2;225;190;181m▀\033[38;2;33;40;55;48;2;25;20;18m▀\033[0m
\033[48;2;0;0;0m    \033[38;2;129;103;97m▀\033[38;2;83;65;59m▀ \033[48;2;33;40;55m \033[38;2;129;103;97m▀\033[38;2;85;76;81m▀\033[38;2;184;131;104m▀\033[38;2;85;76;81;48;2;52;50;62m▀\033[38;2;33;40;55m▀▀\033[48;2;33;40;55m \033[38;2;213;192;193m▀\033[38;2;252;240;241;48;2;213;192;193m▀\033[38;2;177;146;139;48;2;85;76;81m▀\033[0m
\033[48;2;0;0;0m      \033[38;2;0;0;0;48;2;33;40;55m▀ \033[38;2;52;50;62m▀     ▀\033[38;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;25;20;18;48;2;52;50;62m▀         \033[48;2;85;76;81m \033[38;2;25;20;18;48;2;52;50;62m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;25;20;18m▀▀▀▀▀▀▀▀▀▀▀▀\033[0m
==>14-fox-sky
\033[48;2;0;0;0m                \033[38;2;0;0;0;48;2;34;36;50m▀\033[48;2;51;45;51m▀\033[48;2;84;73;76m▀▀\033[48;2;50;50;63m▀▀▀\033[0m
\033[38;2;50;50;63;48;2;34;36;50m▀\033[38;2;51;45;51;48;2;149;160;177m▀\033[38;2;114;83;68;48;2;240;170;130m▀\033[38;2;144;96;69;48;2;240;182;149m▀\033[38;2;138;108;96m▀\033[38;2;193;121;78;48;2;227;156;114m▀\033[38;2;144;96;69;48;2;193;121;78m▀\033[38;2;80;63;58;48;2;217;138;92m▀\033[38;2;51;45;51;48;2;227;156;114m▀▀\033[48;2;240;170;130m▀\033[38;2;114;83;68m▀\033[38;2;80;63;58;48;2;240;182;149m▀\033[38;2;34;36;50;48;2;240;170;130m▀\033[38;2;80;63;58;48;2;227;156;114m▀\033[38;2;144;96;69;48;2;193;121;78m▀\033[38;2;227;156;114m▀\033[38;2;240;170;130;48;2;217;138;92m▀\033[48;2;227;156;114m▀\033[48;2;240;170;130m \033[38;2;227;156;114;48;2;184;169;170m▀\033[38;2;149;160;177;48;2;115;175;230m▀\033[38;2;34;36;50;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m \033[38;2;84;73;76m▀\033[38;2;148;183;223;48;2;118;146;177m▀\033[38;2;187;153;140;48;2;125;184;241m▀\033[38;2;240;170;130;48;2;134;183;227m▀\033[38;2;217;138;92;48;2;148;132;133m▀\033[38;2;188;132;101;48;2;240;170;130m▀\033[38;2;240;170;130;48;2;227;156;114m▀\033[38;2;217;138;92m▀\033[38;2;240;170;130;48;2;240;182;149m▀ ▀\033[38;2;138;108;96;48;2;114;83;68m▀\033[38;2;227;156;114;48;2;34;36;50m▀\033[48;2;138;108;96m▀\033[38;2;217;138;92;48;2;240;170;130m▀▀\033[38;2;188;132;101;48;2;184;169;170m▀\033[38;2;187;153;140;48;2;115;175;230m▀\033[38;2;184;169;170;48;2;125;184;241m▀\033[38;2;119;188;253;48;2;134;183;227m▀\033[38;2;99;113;133;48;2;34;36;50m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;34;36;50m▀\033[38;2;115;175;230m▀\033[48;2;126;127;137m▀\033[38;2;187;153;140;48;2;240;170;130m▀\033[38;2;240;170;130;48;2;227;156;114m▀ \033[48;2;217;138;92m▀\033[48;2;227;156;114m▀▀\033[38;2;240;182;149;48;2;138;108;96m▀\033[38;2;188;132;101;48;2;193;121;78m▀\033[38;2;34;36;50;48;2;114;83;68m▀\033[38;2;80;63;58;48;2;144;96;69m▀\033[38;2;227;156;114;48;2;114;83;68m▀\033[38;2;240;170;130;48;2;50;50;63m▀\033[38;2;188;132;101;48;2;144;96;69m▀\033[38;2;149;160;177;48;2;240;182;149m▀\033[38;2;119;188;253;48;2;50;50;63m▀\033[38;2;84;73;76;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m    \033[38;2;144;96;69;48;2;188;132;101m▀\033[38;2;240;182;149;48;2;240;170;130m▀\033[38;2;217;138;92;48;2;227;156;114m▀ \033[38;2;149;160;177;48;2;119;188;253m▀\033[38;2;240;170;130;48;2;184;169;170m▀\033[38;2;138;108;96;48;2;114;83;68m▀\033[38;2;34;36;50;48;2;138;108;96m▀\033[38;2;80;63;58;48;2;188;132;101m▀\033[38;2;114;83;68;48;2;80;63;58m▀\033[38;2;80;63;58;48;2;84;73;76m▀\033[38;2;51;45;51;48;2;193;121;78m▀\033[48;2;188;132;101m▀\033[38;2;227;156;114;48;2;240;182;149m▀\033[48;2;188;132;101m▀\033[38;2;138;108;96;48;2;34;36;50m▀\033[38;2;34;36;50;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m   \033[48;2;114;83;68m \033[38;2;188;132;101;48;2;240;170;130m▀\033[38;2;240;170;130;48;2;240;182;149m▀\033[48;2;240;170;130m \033[38;2;184;169;170;48;2;149;160;177m▀\033[48;2;119;188;253m \033[38;2;125;184;241m▀\033[38;2;187;153;140;48;2;134;183;227m▀\033[38;2;240;170;130;48;2;187;153;140m▀\033[38;2;227;156;114m▀\033[38;2;80;63;58;48;2;188;132;101m▀\033[38;2;34;36;50;48;2;144;96;69m▀\033[48;2;188;132;101m \033[48;2;240;182;149m \033[38;2;240;170;130m▀▀\033[38;2;80;63;58m▀\033[38;2;0;0;0;48;2;34;36;50m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;80;63;58m▀\033[38;2;188;132;101;48;2;240;170;130m▀\033[38;2;240;170;130;48;2;217;138;92m▀\033[38;2;227;156;114m▀\033[48;2;240;170;130m \033[38;2;149;160;177;48;2;148;132;133m▀\033[38;2;119;188;253;48;2;99;113;133m▀\033[48;2;115;175;230m▀\033[48;2;119;188;253m \033[38;2;119;188;253;48;2;115;175;230m▀\033[38;2;118;146;177;48;2;99;113;133m▀\033[38;2;148;132;133;48;2;118;146;177m▀\033[38;2;187;153;140;48;2;134;183;227m▀\033[38;2;240;182;149;48;2;227;156;114m▀\033[48;2;240;170;130m▀  \033[38;2;240;170;130;48;2;227;156;114m▀\033[38;2;188;132;101;48;2;240;170;130m▀\033[38;2;0;0;0;48;2;34;36;50m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;188;132;101;48;2;138;108;96m▀\033[48;2;217;138;92m \033[38;2;217;138;92;48;2;193;121;78m▀▀\033[38;2;227;156;114;48;2;217;138;92m▀\033[38;2;187;153;140;48;2;188;132;101m▀\033[38;2;134;183;227;48;2;115;175;230m▀\033[38;2;125;184;241;48;2;119;188;253m▀ ▀▀ \033[38;2;134;183;227;48;2;148;132;133m▀\033[38;2;227;156;114;48;2;217;138;92m▀\033[48;2;193;121;78m▀▀▀\033[48;2;217;138;92m \033[38;2;227;156;114;48;2;193;121;78m▀\033[38;2;51;45;51;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m   \033[38;2;193;121;78m▀\033[38;2;217;138;92;48;2;144;96;69m▀\033[48;2;193;121;78m  \033[38;2;193;121;78;48;2;188;132;101m▀\033[38;2;144;96;69m▀\033[38;2;118;146;177;48;2;138;108;96m▀\033[38;2;125;184;241;48;2;119;188;253m▀\033[38;2;119;188;253;48;2;149;160;177m▀\033[48;2;138;108;96m▀\033[38;2;126;127;137;48;2;144;96;69m▀\033[38;2;193;121;78;48;2;217;138;92m▀\033[38;2;217;138;92;48;2;193;121;78m▀\033[48;2;144;96;69m▀▀\033[38;2;193;121;78;48;2;51;45;51m▀\033[38;2;144;96;69;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m   \033[38;2;50;50;63;48;2;195;195;210m▀\033[38;2;115;175;230m▀\033[38;2;134;183;227;48;2;118;146;177m▀\033[38;2;148;183;223;48;2;149;160;177m▀\033[38;2;84;73;76;48;2;50;50;63m▀\033[38;2;114;83;68;48;2;34;36;50m▀\033[38;2;187;153;140;48;2;138;108;96m▀\033[38;2;149;160;177;48;2;188;132;101m▀\033[38;2;126;127;137;48;2;195;195;210m▀\033[38;2;99;113;133m▀\033[38;2;114;83;68m▀\033[38;2;80;63;58;48;2;148;132;133m▀\033[38;2;50;50;63;48;2;51;45;51m▀\033[48;2;50;50;63m \033[38;2;34;36;50m▀\033[38;2;0;0;0;48;2;34;36;50m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;51;45;51;48;2;80;63;58m▀\033[38;2;240;182;149;48;2;187;153;140m▀\033[48;2;238;207;204m▀\033[38;2;114;83;68;48;2;187;153;140m▀\033[38;2;51;45;51;48;2;50;50;63m▀\033[38;2;80;63;58;48;2;240;170;130m▀\033[38;2;84;73;76;48;2;188;132;101m▀\033[38;2;51;45;51;48;2;84;73;76m▀\033[38;2;50;50;63;48;2;188;132;101m▀\033[38;2;138;108;96;48;2;114;83;68m▀\033[38;2;80;63;58;48;2;51;45;51m▀\033[38;2;148;132;133;48;2;34;36;50m▀\033[38;2;251;237;237;48;2;195;195;210m▀\033[38;2;84;73;76;48;2;238;207;204m▀\033[38;2;34;36;50;48;2;184;169;170m▀\033[38;2;144;96;69;48;2;238;207;204m▀\033[38;2;114;83;68;48;2;138;108;96m▀\033[0m
\033[48;2;0;0;0m   \033[48;2;238;207;204m \033[48;2;251;237;237m \033[38;2;138;108;96;48;2;148;132;133m▀\033[38;2;80;63;58;48;2;51;45;51m▀\033[38;2;240;182;149;48;2;227;156;114m▀\033[48;2;138;108;96m \033[48;2;114;83;68m \033[38;2;240;182;149;48;2;240;170;130m▀\033[38;2;138;108;96;48;2;84;73;76m▀\033[38;2;50;50;63;48;2;80;63;58m▀\033[38;2;51;45;51;48;2;34;36;50m▀\033[38;2;184;169;170;48;2;148;132;133m▀\033[48;2;251;237;237m \033[48;2;238;207;204m \033[48;2;251;237;237m \033[38;2;84;73;76;48;2;80;63;58m▀\033[0m
\033[48;2;0;0;0m   \033[38;2;195;195;210;48;2;84;73;76m▀\033[48;2;251;237;237m \033[38;2;240;182;149;48;2;184;169;170m▀\033[38;2;34;36;50;48;2;0;0;0m▀\033[38;2;84;73;76;48;2;50;50;63m▀\033[38;2;188;132;101;48;2;217;138;92m▀\033[38;2;84;73;76;48;2;144;96;69m▀\033[38;2;138;108;96;48;2;84;73;76m▀\033[38;2;80;63;58;48;2;50;50;63m▀ \033[48;2;34;36;50m \033[38;2;138;108;96;48;2;84;73;76m▀\033[38;2;251;237;237;48;2;238;207;204m▀ \033[38;2;238;207;204;48;2;240;182;149m▀\033[38;2;34;36;50;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m    \033[38;2;138;108;96m▀\033[38;2;50;50;63m▀ \033[48;2;34;36;50m \033[38;2;114;83;68m▀▀\033[38;2;188;132;101;48;2;50;50;63m▀\033[38;2;84;73;76m▀\033[38;2;34;36;50m▀ \033[38;2;50;50;63;48;2;34;36;50m▀\033[38;2;195;195;210m▀\033[38;2;251;237;237;48;2;184;169;170m▀\033[38;2;187;153;140;48;2;84;73;76m▀\033[0m
\033[48;2;0;0;0m      \033[38;2;0;0;0;48;2;34;36;50m▀\033[48;2;50;50;63m        \033[38;2;0;0;0;48;2;34;36;50m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;0;0;0;48;2;51;45;51m▀\033[48;2;50;50;63m \033[38;2;50;50;63;48;2;80;63;58m▀▀  ▀▀\033[48;2;50;50;63m \033[38;2;34;36;50m▀\033[38;2;80;63;58m▀\033[38;2;34;36;50;48;2;51;45;51m▀\033[0m
==>15-godette
\033[48;2;0;0;0m           \033[38;2;0;0;0;48;2;26;46;57m▀\033[38;2;18;22;22;48;2;40;71;83m▀\033[48;2;42;77;95m▀\033[48;2;62;119;153m▀\033[48;2;76;148;195m▀\033[48;2;54;102;129m▀\033[48;2;37;47;49m▀\033[38;2;0;0;0;48;2;18;22;22m▀\033[48;2;0;0;0m       \033[38;2;0;0;0;48;2;26;35;36m▀\033[0m
\033[48;2;0;0;0m          \033[38;2;22;29;31m▀\033[38;2;26;35;36m▀\033[38;2;0;0;0;48;2;18;22;22m▀\033[38;2;22;29;31m▀\033[38;2;76;148;195;48;2;26;46;57m▀\033[48;2;18;22;22m▀\033[38;2;92;184;245;48;2;26;41;47m▀\033[38;2;26;46;57m▀\033[38;2;22;29;31;48;2;18;22;22m▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;18;22;22m▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;18;22;22m▀▀\033[48;2;0;0;0m \033[38;2;89;97;102;48;2;26;46;57m▀\033[38;2;253;253;253;48;2;205;210;212m▀\033[38;2;145;146;144;48;2;187;185;184m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;18;22;22m▀▀\033[48;2;0;0;0m \033[38;2;18;22;22m▀\033[48;2;22;29;31m▀\033[38;2;0;0;0;48;2;18;22;22m▀\033[48;2;26;35;36m▀\033[48;2;76;148;195m▀\033[38;2;48;79;99;48;2;81;161;214m▀\033[38;2;62;119;153;48;2;18;22;22m▀\033[38;2;18;22;22;48;2;0;0;0m▀\033[38;2;22;29;31;48;2;18;22;22m▀\033[38;2;18;22;22;48;2;0;0;0m▀  \033[38;2;0;0;0;48;2;18;22;22m▀\033[38;2;26;41;47;48;2;0;0;0m▀\033[38;2;89;97;102;48;2;105;114;116m▀\033[38;2;18;22;22;48;2;234;233;233m▀\033[48;2;26;35;36m▀\033[48;2;0;0;0m▀\033[38;2;0;0;0;48;2;18;22;22m▀\033[48;2;0;0;0m \033[48;2;18;22;22m \033[38;2;62;113;143;48;2;62;119;153m▀\033[48;2;40;71;83m \033[0m
\033[48;2;0;0;0m \033[48;2;18;22;22m  \033[38;2;0;0;0m▀▀\033[48;2;0;0;0m \033[38;2;26;46;57;48;2;24;68;76m▀\033[38;2;22;29;31;48;2;40;71;83m▀\033[38;2;26;46;57;48;2;0;0;0m▀\033[38;2;92;184;245;48;2;26;46;57m▀\033[38;2;26;46;57;48;2;18;22;22m▀\033[38;2;0;0;0m▀\033[48;2;0;0;0m      \033[48;2;18;22;22m \033[38;2;0;0;0m▀\033[38;2;187;185;184;48;2;0;0;0m▀\033[48;2;253;253;253m \033[38;2;26;35;36;48;2;234;233;233m▀\033[48;2;0;0;0m \033[38;2;22;29;31m▀\033[38;2;62;119;153;48;2;54;102;129m▀\033[38;2;76;148;195;48;2;62;113;143m▀\033[38;2;18;22;22;48;2;0;0;0m▀\033[0m
\033[38;2;0;0;0;48;2;26;35;36m▀\033[38;2;18;22;22;48;2;37;47;49m▀\033[38;2;26;35;36;48;2;0;0;0m▀ \033[38;2;18;22;22m▀ \033[38;2;42;77;95;48;2;76;148;195m▀\033[38;2;24;68;76;48;2;22;29;31m▀\033[48;2;0;0;0m      \033[38;2;0;0;0;48;2;18;22;22m▀▀\033[48;2;0;0;0m     \033[38;2;145;146;144;48;2;37;47;49m▀\033[48;2;253;253;253m \033[38;2;160;167;167;48;2;234;233;233m▀\033[38;2;0;0;0;48;2;26;35;36m▀\033[48;2;0;0;0m \033[48;2;18;22;22m \033[38;2;0;0;0m▀ \033[0m
\033[38;2;18;22;22;48;2;0;0;0m▀\033[48;2;18;22;22m \033[38;2;18;22;22;48;2;62;113;143m▀\033[38;2;24;68;76;48;2;76;148;195m▀\033[38;2;26;46;57;48;2;67;149;165m▀\033[38;2;76;148;195;48;2;48;79;99m▀\033[38;2;62;119;153;48;2;0;0;0m▀\033[38;2;0;0;0;48;2;22;29;31m▀\033[38;2;18;22;22;48;2;26;46;57m▀\033[48;2;0;0;0m▀   \033[48;2;18;22;22m \033[38;2;22;29;31;48;2;37;47;49m▀\033[48;2;26;35;36m \033[38;2;0;0;0m▀\033[48;2;26;41;47m▀\033[48;2;18;22;22m▀▀▀\033[38;2;18;22;22;48;2;0;0;0m▀\033[38;2;234;233;233m▀\033[38;2;205;210;212;48;2;26;35;36m▀\033[38;2;40;71;83;48;2;37;47;49m▀\033[48;2;0;0;0m  \033[38;2;26;35;36;48;2;18;22;22m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;26;41;47m▀\033[38;2;67;149;165;48;2;22;29;31m▀\033[48;2;62;113;143m \033[38;2;40;71;83;48;2;81;161;214m▀\033[48;2;42;77;95m \033[38;2;24;68;76;48;2;18;22;22m▀\033[38;2;18;22;22;48;2;0;0;0m▀ \033[48;2;40;71;83m▀\033[38;2;22;29;31m▀\033[48;2;0;0;0m▀  \033[38;2;18;22;22;48;2;22;29;31m▀\033[38;2;0;0;0;48;2;26;41;47m▀\033[48;2;18;22;22m \033[48;2;0;0;0m     \033[38;2;0;0;0;48;2;18;22;22m▀▀\033[38;2;18;22;22;48;2;0;0;0m▀\033[48;2;18;22;22m \033[38;2;18;22;22;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m    \033[38;2;26;46;57;48;2;18;22;22m▀\033[38;2;92;181;238;48;2;76;148;195m▀\033[38;2;42;77;95;48;2;81;161;214m▀\033[38;2;0;0;0;48;2;26;35;36m▀\033[48;2;0;0;0m  \033[38;2;48;79;99;48;2;18;22;22m▀\033[38;2;26;35;36;48;2;0;0;0m▀ \033[38;2;18;22;22m▀\033[38;2;26;46;57m▀\033[38;2;42;77;95;48;2;18;22;22m▀\033[38;2;62;119;153;48;2;26;35;36m▀\033[38;2;76;148;195;48;2;18;22;22m▀\033[38;2;22;29;31;48;2;0;0;0m▀   \033[38;2;62;113;143;48;2;18;22;22m▀\033[38;2;62;119;153m▀\033[38;2;42;77;95m▀\033[38;2;26;41;47m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;26;46;57m▀\033[38;2;76;148;195;48;2;22;29;31m▀\033[38;2;92;181;238;48;2;62;119;153m▀\033[38;2;22;29;31;48;2;40;71;83m▀\033[48;2;0;0;0m    \033[38;2;0;0;0;48;2;18;22;22m▀\033[38;2;26;35;36;48;2;105;114;116m▀\033[38;2;22;29;31;48;2;160;167;167m▀\033[48;2;0;0;0m▀\033[38;2;18;22;22;48;2;26;35;36m▀\033[38;2;0;0;0;48;2;18;22;22m▀\033[48;2;0;0;0m   \033[38;2;18;22;22;48;2;105;114;116m▀\033[48;2;37;47;49m \033[38;2;26;35;36;48;2;0;0;0m▀\033[48;2;18;22;22m \033[0m
\033[48;2;0;0;0m    \033[38;2;0;0;0;48;2;18;22;22m▀\033[48;2;0;0;0m \033[38;2;18;22;22m▀\033[38;2;0;0;0;48;2;18;22;22m▀\033[48;2;22;29;31m \033[38;2;0;0;0;48;2;18;22;22m▀\033[48;2;0;0;0m  \033[38;2;18;22;22m▀\033[48;2;18;22;22m \033[38;2;187;185;184;48;2;54;102;129m▀\033[48;2;67;149;165m▀\033[38;2;0;0;0;48;2;24;68;76m▀\033[48;2;0;0;0m    \033[48;2;26;35;36m \033[38;2;253;253;253;48;2;97;211;181m▀\033[38;2;37;47;49;48;2;42;77;95m▀\033[48;2;0;0;0m \033[38;2;26;35;36;48;2;18;22;22m▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;18;22;22m▀▀\033[0m
\033[48;2;0;0;0m     \033[38;2;18;22;22m▀\033[38;2;0;0;0;48;2;22;29;31m▀\033[48;2;18;22;22m▀\033[48;2;26;35;36m \033[48;2;0;0;0m \033[48;2;18;22;22m \033[38;2;0;0;0;48;2;26;35;36m▀\033[48;2;0;0;0m  \033[38;2;120;169;134;48;2;18;22;22m▀\033[38;2;255;255;221;48;2;40;71;83m▀\033[38;2;48;79;99;48;2;22;29;31m▀\033[48;2;0;0;0m     \033[38;2;203;255;181;48;2;18;22;22m▀\033[38;2;120;169;134;48;2;37;47;49m▀\033[48;2;0;0;0m \033[38;2;26;35;36m▀\033[48;2;18;22;22m \033[38;2;18;22;22;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m       \033[38;2;26;35;36m▀\033[48;2;18;22;22m \033[38;2;26;35;36;48;2;40;71;83m▀\033[38;2;18;22;22m▀\033[38;2;22;29;31;48;2;0;0;0m▀\033[38;2;18;22;22m▀             \033[38;2;0;0;0;48;2;18;22;22m▀\033[0m
\033[48;2;0;0;0m         \033[38;2;18;22;22m▀\033[38;2;22;29;31;48;2;18;22;22m▀\033[38;2;18;22;22;48;2;22;29;31m▀\033[38;2;0;0;0;48;2;18;22;22m▀\033[48;2;0;0;0m    \033[38;2;0;0;0;48;2;18;22;22m▀\033[48;2;0;0;0m   \033[38;2;18;22;22;48;2;22;29;31m▀\033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;18;22;22m▀\033[38;2;18;22;22;48;2;22;29;31m▀\033[48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m           \033[38;2;22;29;31m▀\033[38;2;18;22;22m▀▀\033[38;2;0;0;0;48;2;18;22;22m▀▀\033[48;2;0;0;0m \033[38;2;18;22;22m▀\033[38;2;0;0;0;48;2;18;22;22m▀▀\033[48;2;0;0;0m \033[38;2;18;22;22m▀\033[38;2;0;0;0;48;2;18;22;22m▀\033[38;2;18;22;22;48;2;26;35;36m▀\033[38;2;26;35;36;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m          \033[38;2;18;22;22m▀ ▀\033[48;2;18;22;22m \033[48;2;0;0;0m \033[38;2;18;22;22m▀▀\033[48;2;18;22;22m \033[38;2;0;0;0m▀▀\033[48;2;26;35;36m▀\033[38;2;22;29;31;48;2;18;22;22m▀\033[38;2;18;22;22;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;0;0;0;48;2;18;22;22m▀▀▀\033[48;2;0;0;0m   \033[48;2;18;22;22m \033[48;2;0;0;0m  \033[38;2;18;22;22m▀\033[38;2;0;0;0;48;2;18;22;22m▀▀\033[48;2;0;0;0m  \033[38;2;18;22;22m▀▀\033[38;2;0;0;0;48;2;22;29;31m▀\033[48;2;18;22;22m▀\033[0m
==>16-godette-violet
\033[48;2;0;0;0m           \033[38;2;0;0;0;48;2;46;36;35m▀\033[38;2;25;17;17;48;2;74;36;38m▀▀\033[48;2;153;64;70m▀\033[48;2;185;77;85m▀\033[48;2;136;58;64m▀\033[48;2;46;36;35m▀\033[38;2;0;0;0;48;2;25;17;17m▀\033[48;2;0;0;0m       \033[38;2;0;0;0;48;2;39;25;25m▀\033[0m
\033[48;2;0;0;0m          \033[38;2;25;17;17m▀\033[38;2;39;25;25m▀\033[38;2;0;0;0;48;2;25;17;17m▀ \033[38;2;203;81;90;48;2;55;44;43m▀\033[48;2;25;17;17m▀\033[38;2;244;94;105;48;2;39;25;25m▀\033[38;2;74;36;38;48;2;46;36;35m▀\033[48;2;25;17;17m \033[48;2;0;0;0m \033[38;2;0;0;0;48;2;25;17;17m▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;25;17;17m▀▀\033[48;2;0;0;0m \033[38;2;101;99;97;48;2;55;44;43m▀\033[38;2;253;254;253;48;2;215;212;204m▀\033[38;2;131;126;124;48;2;189;187;181m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;25;17;17m▀▀\033[48;2;0;0;0m  \033[48;2;25;17;17m \033[38;2;0;0;0m▀\033[48;2;39;25;25m▀\033[48;2;203;81;90m▀\033[38;2;136;58;64;48;2;228;89;100m▀\033[38;2;153;64;70;48;2;25;17;17m▀\033[38;2;25;17;17;48;2;0;0;0m▀\033[48;2;39;25;25m \033[48;2;0;0;0m    \033[38;2;46;36;35m▀\033[48;2;110;108;101m \033[38;2;39;25;25;48;2;237;237;225m▀\033[38;2;25;17;17;48;2;46;36;35m▀\033[48;2;0;0;0m▀\033[38;2;0;0;0;48;2;25;17;17m▀\033[48;2;0;0;0m \033[48;2;25;17;17m \033[48;2;153;64;70m \033[38;2;82;61;60;48;2;74;36;38m▀\033[48;2;25;17;17m \033[0m
\033[48;2;0;0;0m \033[48;2;25;17;17m \033[38;2;0;0;0m▀\033[48;2;0;0;0m   \033[48;2;74;36;38m \033[38;2;25;17;17;48;2;46;36;35m▀\033[38;2;39;25;25;48;2;0;0;0m▀\033[38;2;244;94;105;48;2;74;36;38m▀\033[38;2;74;36;38;48;2;0;0;0m▀\033[38;2;0;0;0;48;2;25;17;17m▀\033[48;2;0;0;0m   \033[38;2;0;0;0;48;2;25;17;17m▀\033[48;2;0;0;0m \033[38;2;25;17;17m▀\033[48;2;25;17;17m \033[38;2;0;0;0m▀\033[38;2;189;187;181;48;2;0;0;0m▀\033[48;2;253;254;253m \033[38;2;39;25;25m▀\033[48;2;0;0;0m \033[38;2;25;17;17m▀\033[38;2;153;64;70;48;2;136;58;64m▀\033[38;2;185;77;85;48;2;153;64;70m▀\033[38;2;25;17;17;48;2;0;0;0m▀\033[0m
\033[38;2;0;0;0;48;2;46;36;35m▀▀\033[38;2;39;25;25;48;2;0;0;0m▀   \033[38;2;96;44;47;48;2;203;81;90m▀\033[38;2;74;36;38;48;2;39;25;25m▀\033[48;2;0;0;0m      \033[38;2;0;0;0;48;2;25;17;17m▀ \033[48;2;0;0;0m     \033[38;2;131;126;124;48;2;55;44;43m▀\033[48;2;253;254;253m \033[38;2;169;168;163;48;2;237;237;225m▀\033[38;2;0;0;0;48;2;46;36;35m▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;25;17;17m▀▀▀\033[0m
\033[38;2;25;17;17;48;2;0;0;0m▀▀\033[48;2;136;58;64m▀\033[38;2;74;36;38;48;2;203;81;90m▀\033[48;2;185;77;85m▀\033[38;2;203;81;90;48;2;96;44;47m▀\033[38;2;153;64;70;48;2;0;0;0m▀\033[38;2;0;0;0;48;2;25;17;17m▀\033[38;2;25;17;17;48;2;74;36;38m▀\033[48;2;0;0;0m▀   \033[38;2;0;0;0;48;2;25;17;17m▀\033[38;2;39;25;25;48;2;55;44;43m▀\033[38;2;25;17;17;48;2;39;25;25m▀\033[38;2;0;0;0m▀\033[48;2;46;36;35m▀\033[48;2;25;17;17m▀ ▀\033[38;2;25;17;17;48;2;0;0;0m▀\033[38;2;237;237;225m▀\033[38;2;215;212;204;48;2;46;36;35m▀\033[38;2;70;62;54m▀\033[48;2;0;0;0m  \033[38;2;39;25;25;48;2;25;17;17m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;46;36;35m▀\033[38;2;185;77;85;48;2;25;17;17m▀\033[48;2;153;64;70m \033[38;2;74;36;38;48;2;203;81;90m▀\033[48;2;96;44;47m \033[38;2;39;25;25;48;2;25;17;17m▀\033[48;2;0;0;0m  \033[38;2;25;17;17;48;2;96;44;47m▀\033[48;2;74;36;38m▀\033[48;2;0;0;0m▀▀ \033[48;2;25;17;17m \033[38;2;0;0;0;48;2;39;25;25m▀\033[48;2;25;17;17m \033[48;2;0;0;0m     \033[38;2;0;0;0;48;2;25;17;17m▀▀\033[38;2;25;17;17;48;2;0;0;0m▀\033[38;2;0;0;0;48;2;25;17;17m▀\033[38;2;25;17;17;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m    \033[38;2;46;36;35;48;2;25;17;17m▀\033[38;2;244;94;105;48;2;203;81;90m▀\033[38;2;74;36;38;48;2;228;89;100m▀\033[38;2;0;0;0;48;2;39;25;25m▀\033[48;2;0;0;0m  \033[38;2;96;44;47;48;2;25;17;17m▀\033[38;2;39;25;25;48;2;0;0;0m▀ \033[38;2;25;17;17m▀\033[38;2;46;36;35m▀\033[38;2;96;44;47;48;2;25;17;17m▀\033[38;2;136;58;64;48;2;46;36;35m▀\033[38;2;203;81;90;48;2;25;17;17m▀\033[38;2;25;17;17;48;2;0;0;0m▀   \033[38;2;136;58;64;48;2;25;17;17m▀\033[38;2;153;64;70;48;2;46;36;35m▀\033[38;2;96;44;47;48;2;25;17;17m▀\033[38;2;46;36;35m▀\033[38;2;25;17;17;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;46;36;35m▀\033[38;2;203;81;90;48;2;46;36;35m▀\033[38;2;228;89;100;48;2;153;64;70m▀\033[38;2;25;17;17;48;2;74;36;38m▀\033[48;2;0;0;0m    \033[38;2;0;0;0;48;2;25;17;17m▀\033[38;2;46;36;35;48;2;110;108;101m▀\033[38;2;39;25;25;48;2;169;168;163m▀\033[48;2;0;0;0m▀\033[38;2;25;17;17;48;2;39;25;25m▀\033[38;2;0;0;0;48;2;25;17;17m▀\033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;25;17;17m▀\033[38;2;25;17;17;48;2;110;108;101m▀\033[48;2;46;36;35m \033[38;2;46;36;35;48;2;0;0;0m▀\033[38;2;0;0;0;48;2;25;17;17m▀\033[0m
\033[48;2;0;0;0m    \033[48;2;25;17;17m \033[38;2;0;0;0m▀\033[38;2;25;17;17;48;2;0;0;0m▀\033[38;2;0;0;0;48;2;25;17;17m▀\033[38;2;25;17;17;48;2;55;44;43m▀\033[38;2;0;0;0;48;2;25;17;17m▀\033[48;2;0;0;0m  \033[38;2;25;17;17m▀\033[48;2;25;17;17m \033[38;2;207;200;200;48;2;54;123;107m▀\033[38;2;189;187;181;48;2;71;178;148m▀\033[38;2;0;0;0;48;2;19;84;76m▀\033[48;2;0;0;0m    \033[38;2;55;44;43;48;2;25;17;17m▀\033[38;2;253;254;253;48;2;105;206;180m▀\033[38;2;55;44;43;48;2;19;84;76m▀\033[48;2;0;0;0m \033[38;2;46;36;35;48;2;25;17;17m▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;25;17;17m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;39;25;25m▀\033[38;2;0;0;0;48;2;39;25;25m▀\033[48;2;0;0;0m \033[38;2;46;36;35;48;2;25;17;17m▀\033[38;2;0;0;0m▀ \033[48;2;39;25;25m▀\033[48;2;0;0;0m  \033[38;2;105;170;132;48;2;25;17;17m▀\033[38;2;237;237;225;48;2;70;62;54m▀\033[38;2;54;123;107;48;2;39;25;25m▀\033[48;2;0;0;0m     \033[38;2;191;216;176;48;2;25;17;17m▀\033[38;2;112;168;137;48;2;39;25;25m▀\033[48;2;0;0;0m \033[38;2;46;36;35m▀\033[48;2;25;17;17m \033[38;2;25;17;17;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m       \033[38;2;39;25;25m▀\033[48;2;25;17;17m \033[38;2;46;36;35;48;2;70;62;54m▀\033[38;2;25;17;17;48;2;74;36;38m▀\033[48;2;0;0;0m▀              \033[38;2;0;0;0;48;2;25;17;17m▀\033[0m
\033[48;2;0;0;0m         \033[38;2;25;17;17m▀\033[48;2;25;17;17m \033[38;2;25;17;17;48;2;39;25;25m▀\033[38;2;0;0;0;48;2;25;17;17m▀\033[48;2;0;0;0m    \033[38;2;0;0;0;48;2;25;17;17m▀\033[48;2;0;0;0m   \033[38;2;25;17;17;48;2;39;25;25m▀\033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;25;17;17m▀ \033[38;2;25;17;17;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m           \033[38;2;25;17;17m▀\033[38;2;39;25;25m▀\033[38;2;25;17;17m▀\033[38;2;0;0;0;48;2;39;25;25m▀\033[48;2;25;17;17m▀\033[48;2;0;0;0m \033[38;2;39;25;25m▀\033[48;2;25;17;17m \033[38;2;0;0;0m▀ \033[38;2;25;17;17;48;2;0;0;0m▀\033[38;2;0;0;0;48;2;25;17;17m▀\033[38;2;25;17;17;48;2;46;36;35m▀\033[38;2;46;36;35;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m          \033[38;2;25;17;17m▀\033[38;2;0;0;0;48;2;25;17;17m▀ \033[38;2;25;17;17;48;2;0;0;0m▀ ▀▀\033[48;2;25;17;17m \033[38;2;0;0;0m▀▀\033[48;2;46;36;35m▀\033[48;2;25;17;17m \033[38;2;25;17;17;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m    \033[38;2;0;0;0;48;2;25;17;17m▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;25;17;17m▀▀\033[48;2;0;0;0m   \033[38;2;25;17;17;48;2;39;25;25m▀\033[48;2;0;0;0m  \033[38;2;25;17;17m▀\033[38;2;0;0;0;48;2;25;17;17m▀▀\033[48;2;0;0;0m  \033[48;2;25;17;17m \033[38;2;25;17;17;48;2;0;0;0m▀\033[38;2;0;0;0;48;2;25;17;17m▀▀\033[0m
==>17-godette-gobot
\033[48;2;0;0;0m                       \033[38;2;22;23;23m▀\033[48;2;55;57;53m▀\033[38;2;0;0;0;48;2;22;23;23m▀\033[0m
\033[48;2;0;0;0m           \033[38;2;0;0;0;48;2;53;69;69m▀\033[48;2;52;79;87m▀\033[38;2;40;40;38;48;2;97;139;146m▀\033[38;2;52;79;87;48;2;72;129;152m▀\033[38;2;0;0;0;48;2;40;40;38m▀\033[48;2;0;0;0m  \033[38;2;22;23;23m▀\033[38;2;40;40;38;48;2;55;57;53m▀\033[38;2;0;0;0;48;2;22;23;23m▀\033[0m
\033[38;2;0;0;0;48;2;22;23;23m▀\033[48;2;0;0;0m   \033[38;2;0;0;0;48;2;22;23;23m▀\033[48;2;0;0;0m     \033[38;2;28;33;33m▀\033[38;2;63;103;121;48;2;52;79;87m▀\033[38;2;72;129;152;48;2;126;147;148m▀\033[38;2;128;166;176;48;2;170;168;164m▀\033[48;2;155;162;158m▀\033[38;2;72;129;152;48;2;52;79;87m▀\033[38;2;62;91;101;48;2;53;69;69m▀\033[0m
\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;76;85;83m▀\033[48;2;55;57;53m▀\033[38;2;40;40;38;48;2;28;33;33m▀\033[38;2;76;85;83m▀\033[38;2;0;0;0;48;2;22;23;23m▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;22;23;23m▀\033[48;2;0;0;0m   \033[38;2;53;69;69;48;2;70;73;68m▀\033[48;2;208;183;170m \033[38;2;187;175;166;48;2;165;152;141m▀\033[38;2;126;147;148m▀\033[38;2;62;91;101;48;2;116;129;123m▀\033[38;2;22;23;23;48;2;0;0;0m▀    \033[38;2;0;0;0;48;2;28;33;33m▀▀\033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;55;57;53m▀\033[48;2;28;33;33m▀\033[0m
\033[48;2;0;0;0m \033[38;2;28;33;33;48;2;53;69;69m▀\033[38;2;22;23;23;48;2;62;91;101m▀\033[48;2;63;103;121m \033[48;2;62;91;101m \033[38;2;62;91;101;48;2;63;103;121m▀\033[48;2;0;0;0m     \033[38;2;40;40;38m▀\033[38;2;208;183;170;48;2;70;73;68m▀\033[38;2;244;220;210;48;2;165;152;141m▀\033[38;2;208;183;170;48;2;187;175;166m▀\033[38;2;40;40;38;48;2;53;69;69m▀\033[38;2;0;0;0;48;2;28;33;33m▀\033[48;2;0;0;0m  \033[38;2;40;40;38m▀\033[48;2;28;33;33m▀\033[38;2;22;23;23;48;2;70;73;68m▀\033[38;2;53;69;69;48;2;22;23;23m▀\033[38;2;22;23;23;48;2;0;0;0m▀  \033[38;2;70;73;68;48;2;22;23;23m▀\033[38;2;22;23;23;48;2;106;109;101m▀\033[0m
\033[48;2;0;0;0m \033[38;2;22;23;23m▀\033[38;2;62;91;101;48;2;70;73;68m▀▀\033[38;2;0;0;0;48;2;55;57;53m▀\033[38;2;70;73;68;48;2;187;175;166m▀\033[38;2;0;0;0;48;2;55;57;53m▀\033[48;2;0;0;0m   \033[38;2;0;0;0;48;2;28;33;33m▀\033[38;2;129;128;121;48;2;218;215;208m▀\033[38;2;83;89;83;48;2;170;168;164m▀\033[48;2;28;33;33m \033[38;2;129;128;121m▀\033[38;2;97;139;146;48;2;40;40;38m▀\033[38;2;162;185;184;48;2;244;220;210m▀\033[38;2;234;202;189m▀\033[38;2;83;89;83m▀\033[38;2;0;0;0;48;2;106;109;101m▀\033[48;2;0;0;0m \033[38;2;22;23;23m▀\033[38;2;76;85;83m▀\033[38;2;40;40;38;48;2;52;79;87m▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;22;23;23m▀\033[48;2;0;0;0m \033[38;2;55;57;53m▀\033[38;2;40;40;38;48;2;28;33;33m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;40;40;38m▀\033[38;2;83;89;83m▀\033[38;2;155;162;158m▀\033[38;2;182;195;188;48;2;28;33;33m▀\033[38;2;187;175;166;48;2;244;220;210m▀\033[38;2;28;33;33;48;2;218;215;208m▀\033[38;2;0;0;0;48;2;83;89;83m▀\033[48;2;0;0;0m \033[38;2;40;40;38;48;2;170;168;164m▀\033[38;2;126;147;148;48;2;234;202;189m▀\033[38;2;76;85;83;48;2;0;0;0m▀\033[38;2;28;33;33;48;2;22;23;23m▀\033[38;2;170;168;164;48;2;155;162;158m▀\033[38;2;22;23;23;48;2;55;57;53m▀\033[38;2;129;128;121;48;2;0;0;0m▀\033[38;2;244;220;210;48;2;106;109;101m▀\033[38;2;182;195;188;48;2;116;129;123m▀\033[38;2;106;109;101;48;2;234;202;189m▀\033[38;2;0;0;0;48;2;55;57;53m▀\033[48;2;22;23;23m \033[38;2;0;0;0;48;2;55;57;53m▀\033[48;2;22;23;23m▀\033[48;2;0;0;0m \033[38;2;28;33;33m▀\033[38;2;53;69;69;48;2;106;109;101m▀\033[38;2;0;0;0;48;2;70;73;68m▀\033[38;2;40;40;38;48;2;0;0;0m▀\033[38;2;22;23;23m▀\033[0m
\033[48;2;0;0;0m      \033[38;2;129;128;121;48;2;55;57;53m▀\033[38;2;218;215;208;48;2;116;129;123m▀\033[38;2;116;129;123;48;2;187;175;166m▀\033[38;2;55;57;53;48;2;234;202;189m▀\033[38;2;244;220;210;48;2;129;128;121m▀\033[38;2;106;109;101;48;2;0;0;0m▀ \033[38;2;0;0;0;48;2;22;23;23m▀\033[48;2;0;0;0m    \033[38;2;22;23;23m▀\033[38;2;208;183;170;48;2;22;23;23m▀\033[48;2;234;202;189m▀\033[38;2;0;0;0;48;2;106;109;101m▀\033[48;2;0;0;0m \033[38;2;83;89;83m▀\033[38;2;70;73;68;48;2;126;147;148m▀\033[38;2;0;0;0;48;2;62;91;101m▀\033[38;2;28;33;33;48;2;0;0;0m▀\033[38;2;128;166;176;48;2;94;124;124m▀\033[38;2;0;0;0;48;2;52;79;87m▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;22;23;23m▀\033[0m
\033[48;2;0;0;0m       \033[38;2;40;40;38m▀\033[38;2;244;220;210;48;2;70;73;68m▀\033[38;2;165;152;141;48;2;22;23;23m▀\033[48;2;0;0;0m    \033[38;2;0;0;0;48;2;22;23;23m▀\033[48;2;28;33;33m▀\033[48;2;55;57;53m▀\033[48;2;76;85;83m▀▀\033[48;2;22;23;23m▀\033[38;2;28;33;33;48;2;0;0;0m▀\033[38;2;234;202;189;48;2;70;73;68m▀\033[38;2;106;109;101;48;2;244;220;210m▀\033[38;2;0;0;0;48;2;165;152;141m▀\033[48;2;83;89;83m▀\033[38;2;97;139;146;48;2;0;0;0m▀\033[38;2;22;23;23;48;2;28;33;33m▀\033[38;2;40;40;38m▀\033[38;2;53;69;69;48;2;0;0;0m▀\033[38;2;40;40;38;48;2;53;69;69m▀\033[48;2;28;33;33m \033[0m
\033[48;2;0;0;0m            \033[38;2;0;0;0;48;2;55;57;53m▀\033[38;2;22;23;23;48;2;40;40;38m▀\033[38;2;55;57;53;48;2;0;0;0m▀\033[38;2;22;23;23m▀\033[48;2;28;33;33m▀\033[48;2;22;23;23m  \033[38;2;40;40;38m▀\033[48;2;0;0;0m  \033[38;2;55;57;53m▀\033[38;2;165;152;141;48;2;83;89;83m▀\033[38;2;218;215;208;48;2;155;162;158m▀\033[38;2;70;73;68;48;2;218;215;208m▀\033[38;2;129;128;121;48;2;162;185;184m▀\033[38;2;83;89;83;48;2;0;0;0m▀\033[38;2;22;23;23m▀\033[0m
\033[48;2;0;0;0m           \033[38;2;83;89;83;48;2;76;85;83m▀\033[38;2;155;162;158;48;2;106;109;101m▀\033[38;2;162;185;184;48;2;170;168;164m▀\033[38;2;106;109;101;48;2;162;185;184m▀\033[38;2;22;23;23;48;2;53;69;69m▀\033[38;2;0;0;0;48;2;22;23;23m▀\033[38;2;22;23;23;48;2;0;0;0m▀▀▀  \033[48;2;28;33;33m▀\033[38;2;0;0;0;48;2;22;23;23m▀\033[38;2;55;57;53;48;2;0;0;0m▀\033[38;2;76;85;83;48;2;53;69;69m▀\033[38;2;83;89;83;48;2;40;40;38m▀\033[38;2;22;23;23;48;2;28;33;33m▀\033[38;2;0;0;0;48;2;40;40;38m▀\033[38;2;22;23;23;48;2;0;0;0m▀\033[48;2;22;23;23m \033[0m
\033[48;2;0;0;0m            \033[38;2;129;128;121;48;2;165;152;141m▀\033[38;2;234;202;189;48;2;244;220;210m▀\033[38;2;129;128;121;48;2;83;89;83m▀\033[38;2;94;124;124;48;2;0;0;0m▀\033[38;2;155;162;158;48;2;22;23;23m▀\033[38;2;170;168;164;48;2;55;57;53m▀\033[38;2;83;89;83;48;2;129;128;121m▀\033[38;2;22;23;23;48;2;70;73;68m▀\033[48;2;0;0;0m  \033[38;2;70;73;68m▀\033[38;2;55;57;53;48;2;70;73;68m▀\033[48;2;162;185;184m▀\033[38;2;53;69;69;48;2;83;89;83m▀\033[38;2;28;33;33;48;2;55;57;53m▀\033[38;2;22;23;23;48;2;40;40;38m▀\033[38;2;70;73;68;48;2;55;57;53m▀ \033[0m
\033[48;2;0;0;0m            \033[38;2;28;33;33m▀\033[38;2;55;57;53m▀\033[38;2;22;23;23m▀ \033[38;2;0;0;0;48;2;22;23;23m▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;22;23;23m▀ \033[48;2;0;0;0m \033[38;2;0;0;0;48;2;40;40;38m▀\033[48;2;70;73;68m \033[38;2;128;166;176;48;2;22;23;23m▀\033[38;2;53;69;69;48;2;40;40;38m▀\033[38;2;106;109;101;48;2;55;57;53m▀\033[38;2;55;57;53;48;2;40;40;38m▀\033[38;2;94;124;124;48;2;52;79;87m▀\033[38;2;53;69;69;48;2;22;23;23m▀\033[38;2;22;23;23;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m               \033[48;2;22;23;23m \033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;22;23;23m▀ \033[48;2;0;0;0m \033[48;2;22;23;23m \033[48;2;28;33;33m \033[38;2;0;0;0;48;2;116;129;123m▀\033[48;2;28;33;33m▀\033[38;2;28;33;33;48;2;22;23;23m▀\033[38;2;55;57;53;48;2;40;40;38m▀\033[48;2;0;0;0m \033[38;2;40;40;38m▀\033[0m
\033[48;2;0;0;0m              \033[38;2;0;0;0;48;2;22;23;23m▀\033[38;2;22;23;23;48;2;0;0;0m▀   \033[48;2;53;69;69m▀\033[38;2;94;124;124;48;2;97;139;146m▀\033[38;2;22;23;23;48;2;40;40;38m▀\033[38;2;83;89;83;48;2;70;73;68m▀\033[38;2;94;124;124;48;2;28;33;33m▀\033[38;2;22;23;23;48;2;0;0;0m▀\033[38;2;106;109;101;48;2;116;129;123m▀\033[38;2;116;129;123;48;2;53;69;69m▀\033[0m
\033[48;2;0;0;0m                   \033[38;2;28;33;33m▀  \033[38;2;22;23;23m▀\033[38;2;28;33;33m▀ \033[38;2;40;40;38m▀\033[0m
==>18-godette-gobot-mint
\033[48;2;0;0;0m                       \033[38;2;19;19;18;48;2;25;26;26m▀\033[38;2;0;0;0;48;2;42;44;43m▀\033[0m
\033[48;2;0;0;0m          \033[38;2;0;0;0;48;2;57;69;74m▀▀\033[38;2;32;37;38;48;2;83;137;150m▀\033[38;2;57;69;74;48;2;76;114;135m▀\033[38;2;0;0;0;48;2;42;44;43m▀\033[48;2;0;0;0m   \033[38;2;42;44;43;48;2;25;26;26m▀\033[38;2;19;19;18;48;2;42;44;43m▀\033[48;2;0;0;0m    \033[38;2;19;19;18m▀\033[0m
\033[48;2;0;0;0m         \033[38;2;25;26;26m▀\033[38;2;61;93;112;48;2;57;69;74m▀\033[38;2;83;137;150;48;2;120;148;145m▀\033[38;2;116;163;171;48;2;161;148;141m▀ \033[38;2;83;137;150;48;2;61;93;112m▀\033[38;2;101;110;113;48;2;57;69;74m▀\033[0m
\033[38;2;19;19;18;48;2;78;74;72m▀\033[38;2;0;0;0;48;2;32;37;38m▀\033[38;2;42;44;43;48;2;19;19;18m▀\033[38;2;57;69;74m▀\033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;19;19;18m▀\033[48;2;0;0;0m   \033[48;2;60;59;55m \033[48;2;204;177;167m \033[38;2;204;177;167;48;2;178;158;150m▀\033[38;2;134;135;132;48;2;161;148;141m▀\033[38;2;101;110;113;48;2;134;135;132m▀\033[38;2;25;26;26;48;2;19;19;18m▀\033[48;2;0;0;0m    \033[38;2;0;0;0;48;2;25;26;26m▀\033[48;2;60;59;55m▀\033[48;2;0;0;0m  \033[38;2;19;19;18;48;2;25;26;26m▀\033[38;2;0;0;0;48;2;78;74;72m▀\033[0m
\033[38;2;25;26;26;48;2;57;69;74m▀\033[38;2;19;19;18;48;2;61;93;112m▀ \033[38;2;57;69;74m▀\033[48;2;76;114;135m▀\033[48;2;0;0;0m     \033[38;2;25;26;26m▀\033[38;2;178;166;163;48;2;60;59;55m▀\033[38;2;253;216;203;48;2;161;148;141m▀\033[38;2;233;201;191;48;2;204;177;167m▀\033[48;2;78;74;72m \033[38;2;0;0;0;48;2;32;37;38m▀\033[48;2;0;0;0m  \033[38;2;19;19;18m▀\033[38;2;60;59;55m▀\033[38;2;25;26;26;48;2;78;74;72m▀\033[38;2;60;59;55;48;2;42;44;43m▀\033[38;2;32;37;38;48;2;0;0;0m▀  ▀\033[38;2;60;59;55;48;2;78;74;72m▀\033[38;2;0;0;0m▀\033[0m
\033[38;2;25;26;26;48;2;0;0;0m▀\033[38;2;61;93;112;48;2;78;74;72m▀\033[48;2;57;69;74m▀\033[38;2;19;19;18m▀\033[38;2;78;74;72;48;2;186;188;188m▀\033[38;2;0;0;0;48;2;60;59;55m▀\033[48;2;0;0;0m   \033[38;2;0;0;0;48;2;19;19;18m▀\033[38;2;101;110;113;48;2;186;188;188m▀▀\033[38;2;19;19;18;48;2;25;26;26m▀\033[38;2;136;121;113;48;2;32;37;38m▀\033[38;2;83;137;150;48;2;19;19;18m▀\033[38;2;116;163;171;48;2;186;188;188m▀\033[38;2;210;202;197;48;2;245;226;219m▀\033[38;2;134;135;132m▀\033[38;2;19;19;18;48;2;161;148;141m▀\033[48;2;0;0;0m  \033[38;2;60;59;55m▀\033[38;2;78;74;72;48;2;42;44;43m▀\033[38;2;0;0;0;48;2;32;37;38m▀\033[48;2;0;0;0m   \033[38;2;78;74;72;48;2;19;19;18m▀\033[38;2;0;0;0m▀\033[0m
\033[38;2;19;19;18;48;2;0;0;0m▀\033[38;2;60;59;55m▀\033[38;2;94;92;85m▀\033[38;2;144;155;155m▀\033[38;2;210;202;197;48;2;42;44;43m▀\033[38;2;178;166;163;48;2;245;226;219m▀\033[38;2;25;26;26;48;2;210;202;197m▀\033[38;2;0;0;0;48;2;78;74;72m▀\033[48;2;0;0;0m \033[38;2;32;37;38;48;2;161;148;141m▀\033[38;2;120;148;145;48;2;245;226;219m▀\033[38;2;101;110;113;48;2;25;26;26m▀\033[38;2;19;19;18;48;2;0;0;0m▀\033[48;2;144;155;155m \033[38;2;32;37;38;48;2;94;92;85m▀\033[38;2;78;74;72;48;2;0;0;0m▀\033[38;2;210;202;197;48;2;94;92;85m▀\033[48;2;101;110;113m▀\033[38;2;111;133;141;48;2;210;202;197m▀\033[38;2;19;19;18;48;2;136;121;113m▀\033[48;2;0;0;0m \033[38;2;19;19;18;48;2;42;44;43m▀\033[38;2;0;0;0;48;2;32;37;38m▀\033[48;2;0;0;0m  \033[38;2;60;59;55;48;2;25;26;26m▀\033[38;2;25;26;26;48;2;136;121;113m▀\033[38;2;19;19;18;48;2;0;0;0m▀\033[38;2;32;37;38m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;117;117;115;48;2;42;44;43m▀\033[38;2;210;202;197;48;2;136;121;113m▀\033[38;2;134;135;132;48;2;204;177;167m▀\033[38;2;42;44;43;48;2;233;201;191m▀\033[38;2;245;226;219;48;2;157;138;134m▀\033[38;2;136;121;113;48;2;0;0;0m▀       ▀\033[38;2;253;216;203;48;2;161;148;141m▀\033[38;2;42;44;43;48;2;204;177;167m▀\033[48;2;0;0;0m \033[38;2;42;44;43m▀\033[38;2;94;92;85;48;2;78;74;72m▀\033[38;2;19;19;18;48;2;144;155;155m▀\033[48;2;0;0;0m \033[38;2;117;117;115;48;2;32;37;38m▀\033[38;2;79;89;89;48;2;120;148;145m▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;19;19;18m▀\033[0m
\033[48;2;0;0;0m      \033[38;2;42;44;43m▀\033[38;2;253;216;203;48;2;78;74;72m▀\033[38;2;178;158;150;48;2;19;19;18m▀\033[38;2;19;19;18;48;2;0;0;0m▀   \033[38;2;0;0;0;48;2;19;19;18m▀\033[48;2;32;37;38m▀▀\033[48;2;78;74;72m▀\033[48;2;79;89;89m▀\033[48;2;42;44;43m▀\033[48;2;0;0;0m \033[38;2;178;158;150;48;2;19;19;18m▀\033[38;2;178;166;163;48;2;204;177;167m▀\033[38;2;19;19;18m▀\033[38;2;0;0;0;48;2;101;110;113m▀\033[38;2;79;89;89;48;2;25;26;26m▀\033[38;2;57;69;74;48;2;19;19;18m▀\033[38;2;0;0;0;48;2;32;37;38m▀\033[38;2;57;69;74;48;2;19;19;18m▀\033[38;2;25;26;26m▀\033[38;2;60;59;55;48;2;57;69;74m▀\033[0m
\033[48;2;0;0;0m           \033[38;2;0;0;0;48;2;42;44;43m▀\033[38;2;19;19;18;48;2;60;59;55m▀\033[38;2;60;59;55;48;2;19;19;18m▀\033[38;2;32;37;38;48;2;0;0;0m▀\033[38;2;19;19;18;48;2;32;37;38m▀\033[48;2;25;26;26m \033[38;2;32;37;38;48;2;0;0;0m▀\033[38;2;42;44;43;48;2;19;19;18m▀\033[38;2;0;0;0m▀\033[48;2;0;0;0m  \033[38;2;136;121;113;48;2;42;44;43m▀\033[38;2;210;202;197;48;2;120;148;145m▀\033[38;2;134;135;132;48;2;186;188;188m▀\033[38;2;94;92;85;48;2;245;226;219m▀\033[38;2;134;135;132;48;2;94;92;85m▀\033[38;2;32;37;38;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m          \033[48;2;78;74;72m \033[38;2;144;155;155;48;2;101;110;113m▀\033[38;2;178;166;163;48;2;161;148;141m▀\033[38;2;117;117;115;48;2;186;188;188m▀\033[38;2;25;26;26;48;2;79;89;89m▀\033[38;2;0;0;0;48;2;19;19;18m▀ \033[38;2;19;19;18;48;2;0;0;0m▀▀  \033[48;2;42;44;43m▀\033[38;2;0;0;0;48;2;25;26;26m▀\033[38;2;25;26;26;48;2;0;0;0m▀\033[38;2;78;74;72;48;2;32;37;38m▀\033[38;2;79;89;89;48;2;60;59;55m▀\033[38;2;60;59;55;48;2;25;26;26m▀\033[38;2;0;0;0;48;2;42;44;43m▀\033[48;2;25;26;26m▀\033[38;2;19;19;18m▀\033[0m
\033[48;2;0;0;0m           \033[38;2;101;110;113;48;2;157;138;134m▀\033[38;2;253;216;203;48;2;245;226;219m▀\033[38;2;157;138;134;48;2;136;121;113m▀\033[38;2;117;117;115;48;2;0;0;0m▀\033[38;2;144;155;155;48;2;19;19;18m▀\033[38;2;178;166;163;48;2;32;37;38m▀\033[48;2;117;117;115m \033[38;2;32;37;38m▀\033[48;2;0;0;0m  \033[38;2;25;26;26m▀\033[38;2;79;89;89;48;2;19;19;18m▀\033[38;2;25;26;26;48;2;144;155;155m▀\033[38;2;79;89;89;48;2;83;137;150m▀\033[38;2;42;44;43;48;2;94;92;85m▀\033[38;2;19;19;18;48;2;32;37;38m▀\033[38;2;32;37;38;48;2;42;44;43m▀\033[38;2;78;74;72;48;2;60;59;55m▀\033[38;2;25;26;26;48;2;32;37;38m▀\033[0m
\033[48;2;0;0;0m           \033[38;2;25;26;26m▀\033[38;2;60;59;55m▀\033[38;2;25;26;26m▀ \033[38;2;0;0;0;48;2;19;19;18m▀\033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;25;26;26m▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;19;19;18m▀\033[38;2;25;26;26;48;2;78;74;72m▀\033[38;2;120;148;145;48;2;25;26;26m▀\033[38;2;76;114;135m▀\033[38;2;94;92;85;48;2;42;44;43m▀\033[38;2;101;110;113;48;2;25;26;26m▀\033[38;2;57;69;74;48;2;60;59;55m▀\033[38;2;120;148;145;48;2;42;44;43m▀\033[48;2;19;19;18m  \033[0m
\033[48;2;0;0;0m              \033[38;2;19;19;18m▀▀ \033[38;2;0;0;0;48;2;19;19;18m▀\033[38;2;19;19;18;48;2;32;37;38m▀\033[48;2;0;0;0m  \033[38;2;32;37;38;48;2;19;19;18m▀\033[38;2;0;0;0;48;2;79;89;89m▀\033[38;2;19;19;18m▀\033[48;2;0;0;0m \033[48;2;60;59;55m \033[38;2;25;26;26;48;2;19;19;18m▀\033[38;2;32;37;38;48;2;0;0;0m▀\033[38;2;25;26;26m▀\033[0m
\033[48;2;0;0;0m                 \033[38;2;19;19;18m▀\033[48;2;19;19;18m \033[38;2;101;110;113;48;2;116;163;171m▀\033[48;2;42;44;43m \033[38;2;25;26;26;48;2;60;59;55m▀\033[38;2;120;148;145m▀\033[38;2;32;37;38;48;2;0;0;0m▀\033[38;2;42;44;43;48;2;60;59;55m▀\033[38;2;144;155;155;48;2;120;148;145m▀\033[38;2;32;37;38;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m                  \033[38;2;19;19;18m▀▀  ▀ \033[38;2;25;26;26m▀\033[38;2;19;19;18m▀\033[0m
==>19-newsboy
\033[48;2;0;0;0m                  \033[38;2;0;0;0;48;2;117;124;125m▀\033[38;2;91;97;97;48;2;197;192;180m▀\033[48;2;160;155;143m▀\033[38;2;45;51;54m▀\033[38;2;0;0;0;48;2;134;162;178m▀\033[48;2;45;51;54m▀\033[0m
\033[48;2;0;0;0m                 \033[38;2;19;25;30;48;2;91;97;97m▀\033[38;2;186;215;226;48;2;211;225;226m▀\033[38;2;163;184;195;48;2;197;192;180m▀\033[48;2;186;215;226m \033[38;2;197;192;180;48;2;211;225;226m▀\033[38;2;160;155;143m▀\033[38;2;211;225;226;48;2;186;215;226m▀\033[38;2;117;124;125;48;2;211;225;226m▀\033[38;2;0;0;0;48;2;160;155;143m▀\033[0m
\033[48;2;0;0;0m    \033[38;2;0;0;0;48;2;11;65;112m▀\033[38;2;65;90;111;48;2;17;143;230m▀\033[38;2;11;65;112;48;2;56;126;180m▀\033[38;2;0;52;97;48;2;17;143;230m▀\033[38;2;19;25;30m▀\033[38;2;0;0;0;48;2;15;112;183m▀\033[48;2;10;37;60m▀\033[48;2;0;0;0m      \033[38;2;134;162;178;48;2;163;184;195m▀\033[38;2;211;225;226;48;2;186;215;226m▀\033[38;2;197;192;180m▀\033[38;2;186;215;226;48;2;211;225;226m▀▀▀▀ \033[38;2;211;225;226;48;2;186;215;226m▀\033[38;2;160;155;143;48;2;211;225;226m▀\033[38;2;0;0;0;48;2;91;97;97m▀\033[0m
\033[48;2;0;0;0m    \033[38;2;0;52;97m▀\033[38;2;17;143;230;48;2;109;149;176m▀\033[38;2;197;192;180;48;2;117;124;125m▀\033[38;2;134;162;178;48;2;160;155;143m▀\033[38;2;15;112;183;48;2;197;192;180m▀\033[38;2;109;149;176;48;2;91;97;97m▀\033[38;2;17;143;230;48;2;15;112;183m▀\033[38;2;15;112;183;48;2;17;143;230m▀\033[38;2;10;37;60m▀\033[38;2;0;0;0;48;2;0;52;97m▀\033[48;2;0;0;0m   \033[38;2;163;184;195m▀\033[38;2;211;225;226;48;2;91;97;97m▀\033[48;2;240;219;182m▀\033[38;2;197;192;180m▀\033[38;2;186;215;226;48;2;197;192;180m▀\033[48;2;186;215;226m \033[38;2;211;225;226m▀ \033[38;2;186;215;226;48;2;197;192;180m▀\033[38;2;197;192;180;48;2;160;155;143m▀\033[48;2;117;124;125m \033[0m
\033[48;2;0;0;0m   \033[38;2;0;0;0;48;2;91;97;97m▀\033[48;2;154;144;114m▀\033[48;2;197;192;180m \033[38;2;95;120;135m▀\033[38;2;160;155;143;48;2;240;219;182m▀ \033[38;2;91;97;97;48;2;163;184;195m▀\033[38;2;197;192;180;48;2;211;225;226m▀\033[48;2;240;219;182m▀\033[38;2;109;149;176m▀\033[38;2;15;112;183;48;2;95;120;135m▀\033[48;2;0;0;0m     \033[48;2;197;192;180m \033[38;2;197;192;180;48;2;240;219;182m▀\033[38;2;163;184;195m▀\033[38;2;197;192;180;48;2;211;225;226m▀\033[38;2;211;225;226;48;2;197;192;180m▀\033[38;2;163;184;195;48;2;45;51;54m▀\033[38;2;160;155;143m▀\033[48;2;0;0;0m▀\033[38;2;91;97;97m▀\033[0m
\033[48;2;0;0;0m  \033[48;2;45;51;54m \033[48;2;240;219;182m  \033[38;2;240;219;182;48;2;197;192;180m▀▀\033[48;2;154;144;114m▀\033[48;2;117;124;125m▀\033[48;2;240;219;182m   \033[38;2;240;219;182;48;2;109;149;176m▀\033[38;2;65;90;111;48;2;0;0;0m▀   \033[38;2;0;0;0;48;2;95;120;135m▀\033[38;2;95;120;135;48;2;162;219;251m▀\033[38;2;211;225;226;48;2;152;207;238m▀\033[38;2;240;219;182;48;2;186;215;226m▀\033[38;2;154;144;114;48;2;19;25;30m▀\033[38;2;95;120;135;48;2;0;0;0m▀\033[38;2;45;51;54m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;19;25;30m▀\033[38;2;240;219;182;48;2;197;192;180m▀\033[48;2;240;219;182m \033[38;2;197;192;180;48;2;91;97;97m▀\033[38;2;160;155;143;48;2;19;25;30m▀\033[38;2;154;144;114;48;2;117;124;125m▀\033[38;2;240;219;182;48;2;160;155;143m▀\033[38;2;197;192;180;48;2;240;219;182m▀\033[38;2;240;219;182;48;2;197;192;180m▀\033[38;2;134;162;178;48;2;0;0;0m▀\033[38;2;10;37;60m▀   \033[38;2;45;51;54;48;2;142;189;216m▀\033[48;2;162;219;251m \033[48;2;152;207;238m \033[48;2;162;219;251m \033[38;2;109;149;176;48;2;65;90;111m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;45;51;54;48;2;109;149;176m▀\033[38;2;152;207;238;48;2;162;219;251m▀\033[48;2;134;162;178m▀\033[38;2;19;25;30;48;2;0;0;0m▀ \033[38;2;0;0;0;48;2;19;25;30m▀\033[38;2;19;25;30;48;2;109;149;176m▀\033[38;2;240;219;182;48;2;163;184;195m▀\033[38;2;197;192;180;48;2;162;219;251m▀\033[48;2;45;51;54m \033[38;2;19;25;30;48;2;109;149;176m▀\033[38;2;65;90;111;48;2;162;219;251m▀▀\033[38;2;91;97;97m▀\033[38;2;162;219;251;48;2;152;207;238m▀ \033[48;2;162;219;251m \033[38;2;142;189;216;48;2;117;124;125m▀\033[38;2;19;25;30;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m \033[38;2;19;25;30;48;2;95;120;135m▀\033[38;2;142;189;216;48;2;162;219;251m▀\033[38;2;162;219;251;48;2;152;207;238m▀\033[38;2;45;51;54;48;2;95;120;135m▀\033[38;2;0;0;0;48;2;142;189;216m▀\033[38;2;95;120;135;48;2;162;219;251m▀\033[38;2;152;207;238;48;2;142;189;216m▀\033[38;2;109;149;176;48;2;65;90;111m▀\033[48;2;142;189;216m▀\033[38;2;142;189;216;48;2;45;51;54m▀\033[48;2;0;0;0m \033[38;2;134;162;178;48;2;95;120;135m▀\033[48;2;162;219;251m \033[38;2;152;207;238m▀▀▀▀\033[38;2;162;219;251;48;2;142;189;216m▀\033[38;2;45;51;54;48;2;19;25;30m▀\033[0m
\033[38;2;0;0;0;48;2;45;51;54m▀\033[38;2;142;189;216;48;2;152;207;238m▀\033[38;2;162;219;251m▀ ▀\033[48;2;162;219;251m \033[38;2;152;207;238;48;2;134;162;178m▀\033[38;2;163;184;195m▀\033[48;2;65;90;111m \033[38;2;142;189;216;48;2;152;207;238m▀\033[38;2;65;90;111;48;2;95;120;135m▀\033[38;2;0;0;0;48;2;19;25;30m▀ \033[38;2;162;219;251;48;2;65;90;111m▀\033[48;2;109;149;176m \033[38;2;65;90;111;48;2;0;0;0m▀▀\033[38;2;95;120;135m▀\033[38;2;65;90;111m▀  \033[38;2;0;0;0;48;2;19;25;30m▀▀\033[0m
\033[38;2;109;149;176;48;2;95;120;135m▀\033[48;2;162;219;251m \033[38;2;152;207;238m▀\033[38;2;162;219;251;48;2;142;189;216m▀\033[48;2;45;51;54m▀\033[38;2;95;120;135;48;2;0;0;0m▀ \033[38;2;109;149;176;48;2;65;90;111m▀\033[38;2;65;90;111;48;2;56;126;180m▀\033[38;2;134;162;178;48;2;95;120;135m▀\033[38;2;109;149;176;48;2;162;219;251m▀\033[48;2;19;25;30m \033[38;2;95;120;135;48;2;91;97;97m▀\033[38;2;0;0;0;48;2;134;162;178m▀\033[38;2;65;90;111;48;2;0;0;0m▀\033[48;2;19;25;30m▀\033[48;2;0;0;0m    \033[38;2;45;51;54;48;2;134;162;178m▀\033[48;2;211;225;226m \033[38;2;117;124;125;48;2;154;144;114m▀\033[0m
\033[38;2;19;25;30;48;2;0;0;0m▀\033[38;2;65;90;111m▀\033[38;2;91;97;97m▀\033[38;2;19;25;30m▀   \033[38;2;45;51;54m▀\033[38;2;109;149;176;48;2;95;120;135m▀\033[48;2;65;90;111m \033[38;2;186;215;226;48;2;134;162;178m▀\033[38;2;91;97;97;48;2;142;189;216m▀\033[38;2;0;0;0;48;2;65;90;111m▀\033[38;2;152;207;238;48;2;163;184;195m▀\033[38;2;142;189;216;48;2;95;120;135m▀\033[48;2;0;0;0m     \033[38;2;163;184;195;48;2;91;97;97m▀\033[38;2;186;215;226;48;2;45;51;54m▀\033[38;2;91;97;97;48;2;0;0;0m▀\033[38;2;154;144;114;48;2;19;25;30m▀\033[0m
\033[48;2;0;0;0m        \033[38;2;19;25;30m▀\033[38;2;95;120;135;48;2;45;51;54m▀\033[38;2;142;189;216;48;2;117;124;125m▀\033[38;2;134;162;178;48;2;0;0;0m▀\033[38;2;95;120;135m▀\033[38;2;197;192;180;48;2;19;25;30m▀\033[0m
\033[48;2;0;0;0m                         \033[38;2;0;0;0;48;2;117;124;125m▀\033[48;2;19;25;30m▀\033[0m
\033[48;2;0;0;0m \033[38;2;19;25;30;48;2;95;120;135m▀\033[38;2;0;0;0;48;2;65;90;111m▀\033[48;2;0;0;0m                      \033[48;2;117;124;125m \033[38;2;45;51;54m▀\033[0m
\033[48;2;0;0;0m \033[38;2;65;90;111m▀\033[38;2;91;97;97m▀                      \033[38;2;19;25;30m▀\033[38;2;91;97;97m▀\033[48;2;45;51;54m \033[0m
==>20-newsboy-rose
\033[48;2;0;0;0m                  \033[38;2;0;0;0;48;2;125;118;113m▀\033[38;2;92;77;82;48;2;204;195;181m▀\033[48;2;167;161;151m▀\033[38;2;51;42;44;48;2;201;172;154m▀\033[38;2;0;0;0;48;2;167;161;151m▀\033[48;2;51;42;44m▀\033[0m
\033[48;2;0;0;0m                 \033[38;2;0;0;0;48;2;97;99;95m▀\033[38;2;230;215;186;48;2;219;231;219m▀\033[48;2;204;195;181m  \033[38;2;204;195;181;48;2;230;215;186m▀\033[38;2;201;172;154;48;2;219;231;219m▀\033[38;2;219;231;219;48;2;230;215;186m▀\033[38;2;125;118;113;48;2;219;231;219m▀\033[38;2;0;0;0;48;2;162;150;135m▀\033[0m
\033[48;2;0;0;0m    \033[38;2;0;0;0;48;2;91;11;15m▀\033[38;2;175;18;26;48;2;242;1;18m▀\033[38;2;155;6;16;48;2;224;20;32m▀\033[38;2;91;11;15m▀\033[38;2;0;0;0;48;2;242;1;18m▀\033[48;2;155;6;16m▀\033[48;2;91;11;15m▀\033[48;2;0;0;0m      \033[38;2;167;161;151;48;2;167;175;176m▀\033[38;2;230;215;186;48;2;204;195;181m▀\033[48;2;230;215;186m \033[38;2;204;195;181;48;2;219;231;219m▀\033[38;2;230;215;186m▀\033[48;2;230;215;186m \033[38;2;230;215;186;48;2;219;231;219m▀ \033[38;2;219;231;219;48;2;204;195;181m▀\033[38;2;167;161;151;48;2;219;231;219m▀\033[38;2;0;0;0;48;2;92;77;82m▀\033[0m
\033[48;2;0;0;0m    \033[38;2;91;11;15m▀\033[38;2;242;1;18;48;2;203;101;96m▀\033[38;2;204;195;181;48;2;136;136;135m▀\033[38;2;203;101;96;48;2;162;150;135m▀\033[38;2;224;20;32;48;2;201;172;154m▀\033[38;2;203;101;96;48;2;97;99;95m▀\033[38;2;242;1;18;48;2;224;20;32m▀\033[38;2;224;20;32;48;2;242;1;18m▀\033[38;2;51;42;44m▀\033[38;2;0;0;0;48;2;91;11;15m▀\033[48;2;0;0;0m   \033[38;2;201;172;154m▀\033[38;2;219;231;219;48;2;112;108;107m▀\033[48;2;230;215;186m▀\033[38;2;204;195;181;48;2;213;194;165m▀\033[38;2;230;215;186;48;2;204;195;181m▀\033[48;2;230;215;186m \033[38;2;219;231;219m▀ \033[38;2;204;195;181;48;2;201;172;154m▀\033[48;2;167;161;151m▀\033[38;2;145;135;128;48;2;112;108;107m▀\033[0m
\033[48;2;0;0;0m   \033[38;2;0;0;0;48;2;90;85;81m▀\033[48;2;162;150;135m▀\033[38;2;204;195;181;48;2;213;194;165m▀\033[38;2;112;108;107;48;2;204;195;181m▀\033[38;2;162;150;135;48;2;213;194;165m▀\033[48;2;230;215;186m \033[38;2;71;86;99;48;2;167;175;176m▀\033[38;2;204;195;181;48;2;230;215;186m▀\033[38;2;201;172;154m▀\033[38;2;203;101;96;48;2;213;194;165m▀\033[38;2;92;77;82;48;2;114;105;95m▀\033[48;2;0;0;0m     \033[38;2;201;172;154;48;2;213;194;165m▀\033[48;2;230;215;186m \033[38;2;204;195;181m▀\033[48;2;219;231;219m▀\033[38;2;219;231;219;48;2;204;195;181m▀\033[38;2;204;195;181;48;2;92;77;82m▀\033[38;2;162;150;135;48;2;51;42;44m▀\033[38;2;167;161;151;48;2;0;0;0m▀\033[38;2;92;77;82m▀\033[0m
\033[48;2;0;0;0m  \033[48;2;51;42;44m \033[48;2;230;215;186m  \033[48;2;213;194;165m \033[38;2;230;215;186;48;2;201;172;154m▀\033[48;2;145;135;128m▀▀\033[48;2;213;194;165m▀\033[48;2;219;231;219m▀\033[48;2;230;215;186m \033[38;2;230;215;186;48;2;118;136;148m▀\033[38;2;92;77;82;48;2;0;0;0m▀   \033[38;2;0;0;0;48;2;94;115;129m▀\033[38;2;94;115;129;48;2;187;235;251m▀\033[38;2;204;195;181m▀\033[38;2;230;215;186;48;2;161;202;221m▀\033[38;2;145;135;128;48;2;51;42;44m▀\033[38;2;125;118;113;48;2;0;0;0m▀\033[38;2;51;42;44m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;51;42;44m▀\033[38;2;230;215;186;48;2;201;172;154m▀\033[48;2;230;215;186m \033[38;2;213;194;165;48;2;90;85;81m▀\033[38;2;162;150;135;48;2;0;0;0m▀\033[38;2;145;135;128;48;2;114;105;95m▀\033[38;2;230;215;186;48;2;162;150;135m▀\033[38;2;213;194;165;48;2;230;215;186m▀\033[38;2;230;215;186;48;2;213;194;165m▀\033[38;2;162;150;135;48;2;0;0;0m▀    \033[38;2;71;86;99;48;2;167;175;176m▀\033[48;2;187;235;251m   \033[38;2;121;155;173;48;2;71;86;99m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;71;86;99;48;2;118;136;148m▀\033[38;2;161;202;221;48;2;187;235;251m▀\033[48;2;167;175;176m▀\033[38;2;51;42;44;48;2;0;0;0m▀ \033[38;2;0;0;0;48;2;51;42;44m▀\033[38;2;51;42;44;48;2;121;155;173m▀\033[38;2;230;215;186;48;2;167;175;176m▀\033[38;2;204;195;181;48;2;187;235;251m▀\033[48;2;51;42;44m \033[38;2;51;42;44;48;2;121;155;173m▀\033[38;2;71;86;99;48;2;187;235;251m▀▀\033[38;2;94;115;129m▀   \033[38;2;161;202;221;48;2;118;136;148m▀\033[0m
\033[48;2;0;0;0m \033[38;2;51;42;44;48;2;94;115;129m▀\033[38;2;161;202;221;48;2;187;235;251m▀ \033[38;2;71;86;99;48;2;112;108;107m▀\033[38;2;0;0;0;48;2;161;202;221m▀\033[38;2;94;115;129;48;2;187;235;251m▀\033[48;2;161;202;221m \033[38;2;121;155;173;48;2;71;86;99m▀\033[48;2;161;202;221m▀\033[38;2;161;202;221;48;2;71;86;99m▀\033[48;2;0;0;0m \033[38;2;121;155;173;48;2;94;115;129m▀\033[48;2;187;235;251m     \033[38;2;187;235;251;48;2;161;202;221m▀\033[38;2;51;42;44;48;2;0;0;0m▀\033[0m
\033[38;2;0;0;0;48;2;51;42;44m▀\033[38;2;161;202;221;48;2;187;235;251m▀ ▀  \033[48;2;118;136;148m▀\033[48;2;121;155;173m▀\033[38;2;94;115;129;48;2;71;86;99m▀\033[48;2;161;202;221m \033[38;2;71;86;99;48;2;94;115;129m▀\033[48;2;0;0;0m \033[48;2;51;42;44m \033[38;2;161;202;221;48;2;71;86;99m▀\033[38;2;118;136;148;48;2;121;155;173m▀\033[38;2;97;99;95;48;2;0;0;0m▀\033[38;2;71;86;99m▀\033[38;2;94;115;129m▀\033[38;2;71;86;99m▀  \033[38;2;0;0;0;48;2;51;42;44m▀\033[0m
\033[38;2;118;136;148;48;2;94;115;129m▀\033[48;2;187;235;251m  \033[38;2;187;235;251;48;2;161;202;221m▀\033[48;2;71;86;99m▀\033[38;2;94;115;129;48;2;0;0;0m▀ \033[38;2;121;155;173;48;2;71;86;99m▀\033[38;2;71;86;99;48;2;94;115;129m▀\033[38;2;121;155;173m▀\033[48;2;187;235;251m▀\033[38;2;0;0;0;48;2;51;42;44m▀\033[38;2;94;115;129;48;2;97;99;95m▀\033[38;2;0;0;0;48;2;167;175;176m▀\033[38;2;71;86;99;48;2;0;0;0m▀\033[48;2;51;42;44m▀\033[48;2;0;0;0m    \033[38;2;51;42;44;48;2;167;161;151m▀\033[38;2;204;195;181;48;2;219;231;219m▀\033[38;2;112;108;107;48;2;145;135;128m▀\033[0m
\033[48;2;0;0;0m \033[38;2;71;86;99m▀\033[38;2;94;115;129m▀\033[38;2;51;42;44m▀   ▀\033[38;2;121;155;173;48;2;94;115;129m▀\033[48;2;71;86;99m \033[38;2;161;202;221;48;2;121;155;173m▀\033[38;2;71;86;99;48;2;161;202;221m▀\033[38;2;0;0;0;48;2;71;86;99m▀\033[38;2;161;202;221;48;2;167;175;176m▀\033[48;2;94;115;129m▀\033[48;2;0;0;0m     \033[38;2;167;175;176;48;2;97;99;95m▀\033[38;2;161;202;221;48;2;92;77;82m▀\033[38;2;114;105;95;48;2;0;0;0m▀\033[38;2;145;136;104m▀\033[0m
\033[48;2;0;0;0m         \033[38;2;94;115;129;48;2;51;42;44m▀\033[38;2;167;175;176;48;2;125;118;113m▀\033[38;2;121;155;173;48;2;0;0;0m▀\033[38;2;118;136;148m▀\033[38;2;204;195;181m▀\033[0m
\033[48;2;0;0;0m                         \033[38;2;0;0;0;48;2;125;118;113m▀\033[48;2;51;42;44m▀\033[0m
\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;118;136;148m▀\033[48;2;71;86;99m▀\033[48;2;0;0;0m                      \033[38;2;162;150;135;48;2;125;118;113m▀\033[38;2;51;42;44m▀\033[0m
\033[48;2;0;0;0m \033[38;2;71;86;99m▀\033[38;2;97;99;95m▀                      \033[38;2;51;42;44m▀\033[38;2;97;99;95m▀\033[38;2;51;42;44;48;2;92;77;82m▀\033[0m
==>21-newsboy-violet
\033[48;2;0;0;0m                  \033[38;2;0;0;0;48;2;105;120;136m▀\033[38;2;75;77;84;48;2;205;197;182m▀\033[48;2;162;152;156m▀\033[38;2;55;63;74;48;2;163;171;176m▀\033[38;2;0;0;0;48;2;162;152;156m▀\033[48;2;55;63;74m▀\033[0m
\033[48;2;0;0;0m                 \033[38;2;33;33;42;48;2;85;90;91m▀\033[48;2;215;214;203m \033[38;2;205;197;182;48;2;163;171;176m▀\033[38;2;215;214;203;48;2;205;197;182m▀\033[38;2;196;179;171;48;2;215;214;203m▀\033[38;2;163;171;176m▀\033[38;2;216;245;239m▀\033[38;2;96;103;113m▀\033[38;2;0;0;0;48;2;162;152;156m▀\033[0m
\033[48;2;0;0;0m    \033[38;2;0;0;0;48;2;72;36;82m▀\033[38;2;122;30;144;48;2;200;21;231m▀\033[48;2;194;60;205m▀\033[38;2;72;36;82m▀\033[38;2;33;33;42;48;2;200;21;231m▀\033[38;2;0;0;0;48;2;122;30;144m▀\033[48;2;72;36;82m▀\033[48;2;0;0;0m      \033[38;2;162;152;156;48;2;196;179;171m▀\033[38;2;215;214;203;48;2;205;197;182m▀\033[48;2;215;214;203m    \033[38;2;215;214;203;48;2;216;245;239m▀▀\033[38;2;216;245;239;48;2;215;214;203m▀\033[38;2;162;152;156;48;2;216;245;239m▀\033[38;2;0;0;0;48;2;75;77;84m▀\033[0m
\033[48;2;0;0;0m    \033[38;2;72;36;82m▀\033[38;2;200;21;231;48;2;162;152;156m▀\033[38;2;205;197;182;48;2;135;139;143m▀\033[38;2;187;111;191;48;2;162;152;156m▀\033[38;2;159;28;183;48;2;196;179;171m▀\033[38;2;187;111;191;48;2;85;90;91m▀\033[38;2;200;21;231;48;2;159;28;183m▀\033[38;2;159;28;183;48;2;200;21;231m▀\033[38;2;33;33;42m▀\033[38;2;0;0;0;48;2;72;36;82m▀\033[48;2;0;0;0m   \033[38;2;163;171;176m▀\033[38;2;216;245;239;48;2;96;103;113m▀\033[48;2;215;214;203m \033[38;2;205;197;182m▀\033[38;2;215;214;203;48;2;205;197;182m▀\033[48;2;215;214;203m   \033[38;2;215;214;203;48;2;196;179;171m▀\033[38;2;205;197;182;48;2;162;152;156m▀\033[38;2;105;120;136;48;2;96;103;113m▀\033[0m
\033[48;2;0;0;0m   \033[38;2;0;0;0;48;2;85;90;91m▀\033[48;2;141;140;127m▀\033[48;2;205;197;182m \033[38;2;105;120;136m▀\033[38;2;162;152;156;48;2;235;217;181m▀ \033[38;2;75;77;84;48;2;163;171;176m▀\033[38;2;205;197;182;48;2;215;214;203m▀\033[38;2;196;179;171;48;2;235;217;181m▀\033[38;2;194;60;205m▀\033[38;2;122;30;144;48;2;132;134;130m▀\033[48;2;0;0;0m     \033[48;2;196;179;171m \033[38;2;215;214;203;48;2;235;217;181m▀\033[38;2;205;197;182m▀\033[38;2;163;171;176m▀\033[38;2;216;245;239;48;2;196;179;171m▀\033[38;2;205;197;182;48;2;55;63;74m▀\033[38;2;162;152;156;48;2;33;33;42m▀\033[48;2;0;0;0m▀\033[38;2;85;90;91m▀\033[0m
\033[48;2;0;0;0m  \033[48;2;33;33;42m \033[48;2;235;217;181m \033[38;2;235;217;181;48;2;205;197;182m▀\033[48;2;196;179;171m▀▀\033[48;2;132;134;130m▀▀\033[48;2;205;197;182m▀\033[48;2;235;217;181m  \033[38;2;235;217;181;48;2;130;145;153m▀\033[38;2;75;77;84;48;2;33;33;42m▀\033[48;2;0;0;0m   \033[38;2;0;0;0;48;2;105;120;136m▀\033[38;2;105;120;136;48;2;216;245;239m▀\033[38;2;205;197;182;48;2;188;236;251m▀\033[38;2;235;217;181;48;2;164;199;220m▀\033[38;2;141;140;127;48;2;0;0;0m▀\033[38;2;105;120;136m▀\033[38;2;55;63;74m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;33;33;42m▀\033[38;2;235;217;181;48;2;196;179;171m▀\033[48;2;235;217;181m \033[38;2;205;197;182;48;2;75;77;84m▀\033[38;2;135;139;143;48;2;33;33;42m▀\033[38;2;136;138;136;48;2;96;103;113m▀\033[38;2;205;197;182;48;2;162;152;156m▀\033[48;2;235;217;181m▀\033[38;2;235;217;181;48;2;196;179;171m▀\033[38;2;162;152;156;48;2;0;0;0m▀\033[38;2;33;33;42m▀   \033[38;2;55;63;74;48;2;164;199;220m▀\033[48;2;188;236;251m   \033[38;2;123;152;177;48;2;75;77;84m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;55;63;74;48;2;123;152;177m▀\033[38;2;188;236;251;48;2;216;245;239m▀\033[38;2;164;199;220;48;2;123;152;177m▀\033[38;2;33;33;42;48;2;0;0;0m▀ \033[38;2;0;0;0;48;2;33;33;42m▀\033[38;2;33;33;42;48;2;163;171;176m▀\033[38;2;235;217;181m▀\033[38;2;205;197;182;48;2;188;236;251m▀\033[48;2;55;63;74m \033[38;2;33;33;42;48;2;123;152;177m▀\033[38;2;96;103;113;48;2;216;245;239m▀\033[38;2;85;90;91;48;2;188;236;251m▀▀   \033[38;2;164;199;220;48;2;105;120;136m▀\033[0m
\033[48;2;0;0;0m \033[38;2;33;33;42;48;2;91;106;135m▀\033[38;2;164;199;220;48;2;188;236;251m▀\033[38;2;188;236;251;48;2;164;199;220m▀\033[38;2;55;63;74;48;2;91;106;135m▀\033[38;2;0;0;0;48;2;164;199;220m▀\033[38;2;105;120;136;48;2;188;236;251m▀\033[48;2;164;199;220m \033[38;2;123;152;177;48;2;55;63;74m▀\033[48;2;164;199;220m▀\033[38;2;164;199;220;48;2;75;77;84m▀\033[48;2;0;0;0m \033[38;2;123;152;177;48;2;105;120;136m▀\033[48;2;188;236;251m    \033[38;2;188;236;251;48;2;216;245;239m▀\033[48;2;164;199;220m▀\033[38;2;55;63;74;48;2;0;0;0m▀\033[0m
\033[38;2;0;0;0;48;2;55;63;74m▀\033[38;2;164;199;220;48;2;188;236;251m▀ ▀▀ \033[48;2;123;152;177m▀ \033[38;2;96;103;113;48;2;75;77;84m▀\033[48;2;164;199;220m \033[38;2;85;90;91;48;2;105;120;136m▀\033[48;2;0;0;0m \033[48;2;33;33;42m \033[38;2;164;199;220;48;2;85;90;91m▀\033[38;2;130;145;153;48;2;123;152;177m▀\033[38;2;96;103;113;48;2;0;0;0m▀\033[38;2;85;90;91m▀\033[38;2;91;106;135m▀\033[38;2;75;77;84m▀  \033[38;2;0;0;0;48;2;33;33;42m▀▀\033[0m
\033[38;2;130;145;153;48;2;105;120;136m▀\033[38;2;188;236;251;48;2;216;245;239m▀\033[48;2;188;236;251m \033[38;2;188;236;251;48;2;164;199;220m▀\033[48;2;75;77;84m▀\033[38;2;91;106;135;48;2;0;0;0m▀ \033[38;2;130;145;153;48;2;91;106;135m▀\033[38;2;85;90;91m▀\033[38;2;123;152;177m▀\033[48;2;188;236;251m▀\033[38;2;0;0;0;48;2;33;33;42m▀\033[38;2;105;120;136;48;2;85;90;91m▀\033[38;2;0;0;0;48;2;163;171;176m▀\033[38;2;75;77;84;48;2;0;0;0m▀\033[38;2;55;63;74;48;2;33;33;42m▀\033[48;2;0;0;0m    \033[38;2;33;33;42;48;2;162;152;156m▀\033[38;2;164;199;220;48;2;216;245;239m▀\033[38;2;96;103;113;48;2;132;134;130m▀\033[0m
\033[48;2;0;0;0m \033[38;2;85;90;91m▀\033[38;2;96;103;113m▀\033[38;2;33;33;42m▀   ▀\033[38;2;130;145;153;48;2;96;103;113m▀\033[38;2;75;77;84;48;2;85;90;91m▀\033[38;2;164;199;220;48;2;123;152;177m▀\033[38;2;91;106;135;48;2;164;199;220m▀\033[38;2;0;0;0;48;2;85;90;91m▀\033[38;2;164;199;220;48;2;163;171;176m▀\033[48;2;105;120;136m▀\033[48;2;0;0;0m     \033[38;2;163;171;176;48;2;85;90;91m▀\033[38;2;164;199;220;48;2;55;63;74m▀\033[38;2;85;90;91;48;2;0;0;0m▀\033[38;2;161;161;61;48;2;33;33;42m▀\033[0m
\033[48;2;0;0;0m         \033[38;2;96;103;113;48;2;33;33;42m▀\033[38;2;163;171;176;48;2;105;120;136m▀\033[38;2;164;199;220;48;2;0;0;0m▀\033[38;2;136;138;136m▀\033[38;2;205;197;182m▀\033[0m
\033[48;2;0;0;0m                         \033[38;2;0;0;0;48;2;132;134;130m▀\033[48;2;33;33;42m▀\033[0m
\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;130;145;153m▀\033[48;2;85;90;91m▀\033[48;2;0;0;0m                      \033[38;2;141;140;127;48;2;96;103;113m▀\033[38;2;55;63;74m▀\033[0m
\033[48;2;0;0;0m \033[38;2;96;103;113m▀\033[38;2;85;90;91m▀                      \033[38;2;33;33;42m▀\033[38;2;85;90;91m▀\033[38;2;33;33;42;48;2;55;63;74m▀\033[0m
==>22-rational
\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;19;19;19m▀▀\033[38;2;19;19;19;48;2;0;0;0m▀▀ \033[48;2;19;19;19m \033[38;2;0;0;0m▀▀\033[48;2;22;22;15m▀\033[48;2;19;19;19m▀\033[38;2;19;19;19;48;2;0;0;0m▀▀\033[38;2;22;22;15m▀▀\033[48;2;19;19;19m \033[38;2;0;0;0m▀\033[0m
\033[38;2;0;0;0;48;2;19;19;19m▀\033[38;2;19;19;19;48;2;0;0;0m▀ \033[48;2;66;60;33m▀\033[38;2;0;0;0;48;2;163;153;83m▀\033[48;2;19;19;19m▀\033[48;2;0;0;0m       \033[38;2;0;0;0;48;2;162;151;79m▀\033[38;2;45;37;24;48;2;94;86;47m▀\033[48;2;0;0;0m \033[38;2;19;19;19m▀\033[48;2;19;19;19m \033[0m
\033[48;2;0;0;0m    \033[38;2;94;85;43m▀\033[38;2;37;36;17m▀      \033[38;2;22;22;15m▀\033[38;2;85;79;41m▀    \033[38;2;0;0;0;48;2;19;19;19m▀\033[0m
\033[48;2;19;19;19m \033[48;2;0;0;0m   \033[38;2;0;0;0;48;2;19;19;19m▀\033[48;2;0;0;0m         \033[38;2;22;22;15m▀   \033[38;2;0;0;0;48;2;22;22;15m▀\033[0m
\033[48;2;19;19;19m \033[48;2;0;0;0m     \033[38;2;19;19;19m▀\033[0m
\033[48;2;19;19;19m \033[48;2;0;0;0m     \033[38;2;0;0;0;48;2;22;22;15m▀\033[38;2;22;22;15;48;2;0;0;0m▀   \033[38;2;0;0;0;48;2;22;22;15m▀\033[48;2;0;0;0m      \033[38;2;0;0;0;48;2;26;26;15m▀\033[0m
\033[48;2;19;19;19m \033[38;2;0;0;0m▀\033[48;2;0;0;0m  \033[48;2;19;19;19m \033[48;2;0;0;0m         \033[38;2;22;22;15m▀   \033[48;2;22;22;15m \033[0m
\033[48;2;0;0;0m \033[48;2;22;22;15m \033[48;2;0;0;0m   \033[38;2;22;22;15m▀\033[38;2;0;0;0;48;2;19;19;19m▀\033[48;2;0;0;0m     \033[38;2;0;0;0;48;2;19;19;19m▀\033[38;2;22;22;15;48;2;0;0;0m▀  \033[38;2;0;0;0;48;2;19;19;19m▀\033[48;2;22;22;15m \033[0m
\033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;22;22;15m▀\033[48;2;0;0;0m    \033[38;2;22;22;15m▀  \033[48;2;19;19;19m \033[38;2;22;22;15;48;2;0;0;0m▀  \033[38;2;0;0;0;48;2;19;19;19m▀ \033[38;2;22;22;15;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;22;22;15m▀\033[38;2;0;0;0;48;2;22;22;15m▀\033[48;2;0;0;0m        \033[38;2;0;0;0;48;2;19;19;19m▀\033[38;2;19;19;19;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m   \033[38;2;19;19;19m▀\033[48;2;19;19;19m \033[48;2;0;0;0m       \033[38;2;19;19;19m▀\033[48;2;22;22;15m \033[38;2;0;0;0;48;2;19;19;19m▀\033[0m
\033[48;2;0;0;0m   \033[38;2;0;0;0;48;2;19;19;19m▀\033[48;2;0;0;0m           \033[38;2;0;0;0;48;2;19;19;19m▀\033[0m
\033[48;2;0;0;0m         \033[38;2;0;0;0;48;2;54;54;54m▀\033[48;2;25;25;25m▀\033[48;2;0;0;0m    \033[38;2;19;19;19m▀\033[0m
\033[48;2;0;0;0m         \033[38;2;157;157;157m▀\033[38;2;75;75;75m▀\033[0m
\033[0m
\033[48;2;0;0;0m   \033[48;2;19;19;19m \033[48;2;0;0;0m           \033[48;2;26;26;15m \033[0m
==>23-rational-indigo
\033[48;2;0;0;0m  \033[48;2;12;11;19m \033[48;2;0;0;0m \033[38;2;12;11;19m▀ \033[48;2;16;16;27m▀\033[38;2;0;0;0;48;2;12;11;19m▀▀▀▀\033[38;2;12;11;19;48;2;0;0;0m▀▀▀▀▀\033[38;2;0;0;0;48;2;12;11;19m▀\033[0m
\033[38;2;0;0;0;48;2;12;11;19m▀\033[38;2;12;11;19;48;2;0;0;0m▀ \033[38;2;19;19;11;48;2;81;72;38m▀\033[38;2;0;0;0;48;2;192;180;94m▀\033[48;2;19;19;11m▀\033[48;2;0;0;0m       \033[38;2;19;11;11;48;2;192;180;94m▀\033[38;2;48;44;22;48;2;111;103;54m▀\033[48;2;0;0;0m \033[38;2;12;11;19m▀\033[48;2;13;13;22m \033[0m
\033[38;2;12;11;19;48;2;0;0;0m▀   \033[38;2;111;103;54m▀\033[38;2;44;46;24m▀ \033[38;2;0;0;0;48;2;24;14;14m▀\033[48;2;19;11;11m▀\033[48;2;0;0;0m   \033[38;2;21;14;12m▀\033[38;2;97;94;49m▀    \033[38;2;0;0;0;48;2;12;11;19m▀\033[0m
\033[48;2;12;11;19m \033[48;2;0;0;0m   \033[38;2;0;0;0;48;2;19;11;11m▀\033[48;2;0;0;0m    \033[48;2;19;11;11m \033[38;2;0;0;0m▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;19;11;11m▀▀\033[38;2;19;11;11;48;2;0;0;0m▀\033[48;2;19;11;11m \033[48;2;0;0;0m  \033[48;2;12;11;19m \033[0m
\033[48;2;12;11;19m \033[48;2;0;0;0m  \033[38;2;19;11;11m▀  \033[38;2;24;14;14m▀\033[38;2;19;11;11m▀   \033[48;2;24;14;14m \033[38;2;19;11;11;48;2;0;0;0m▀\033[0m
\033[48;2;12;11;19m \033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;19;11;11m▀\033[48;2;0;0;0m \033[38;2;19;11;11m▀\033[38;2;0;0;0;48;2;19;19;11m▀\033[38;2;19;11;11;48;2;0;0;0m▀   \033[38;2;0;0;0;48;2;24;15;14m▀\033[48;2;19;12;11m \033[48;2;0;0;0m     \033[38;2;0;0;0;48;2;17;20;30m▀\033[0m
\033[48;2;12;11;19m \033[38;2;0;0;0m▀\033[48;2;0;0;0m  \033[48;2;19;11;11m \033[48;2;0;0;0m        \033[38;2;0;0;0;48;2;19;11;11m▀\033[48;2;21;14;12m \033[48;2;0;0;0m   \033[48;2;12;11;19m \033[0m
\033[48;2;0;0;0m \033[48;2;12;11;19m \033[48;2;0;0;0m   \033[38;2;27;16;16m▀\033[38;2;0;0;0;48;2;19;11;11m▀\033[48;2;0;0;0m     \033[38;2;0;0;0;48;2;19;12;11m▀\033[38;2;19;11;11;48;2;0;0;0m▀  \033[38;2;0;0;0;48;2;12;11;19m▀\033[48;2;13;13;22m \033[0m
\033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;12;11;19m▀\033[48;2;0;0;0m    \033[38;2;19;12;11m▀\033[48;2;19;11;11m \033[38;2;0;0;0m▀ \033[38;2;19;12;11;48;2;0;0;0m▀  \033[38;2;0;0;0;48;2;12;11;19m▀ \033[38;2;16;16;27;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m  \033[48;2;12;11;19m \033[38;2;0;0;0m▀\033[48;2;0;0;0m        \033[38;2;0;0;0;48;2;12;11;19m▀\033[38;2;12;11;19;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m   \033[38;2;12;11;19m▀\033[48;2;16;16;27m▀\033[48;2;0;0;0m        \033[38;2;16;16;27;48;2;12;11;19m▀\033[38;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m   \033[38;2;12;11;19m▀▀         ▀\033[48;2;12;11;19m \033[0m
\033[48;2;0;0;0m         \033[38;2;0;0;0;48;2;36;41;64m▀\033[48;2;17;20;30m▀\033[48;2;0;0;0m    \033[38;2;12;11;19m▀\033[0m
\033[48;2;0;0;0m         \033[38;2;112;121;185m▀\033[38;2;54;59;89m▀\033[0m
\033[0m
\033[48;2;0;0;0m   \033[48;2;12;11;19m \033[48;2;0;0;0m           \033[48;2;16;16;27m \033[0m
==>24-lufthan
\033[48;2;0;0;0m             \033[38;2;0;0;0;48;2;77;43;27m▀\033[48;2;48;26;16m \033[0m
\033[48;2;0;0;0m         \033[38;2;0;0;0;48;2;48;26;16m▀\033[48;2;115;57;29m▀▀\033[38;2;49;43;47m▀\033[38;2;80;56;46m▀ \033[38;2;115;57;29;48;2;80;56;46m▀\033[48;2;48;26;16m \033[0m
\033[48;2;0;0;0m      \033[38;2;0;0;0;48;2;77;41;16m▀\033[48;2;138;63;27m▀\033[38;2;115;57;29m▀\033[38;2;148;75;37m▀▀▀   \033[38;2;115;57;29m▀\033[38;2;48;26;16;48;2;115;57;29m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;0;0;0;48;2;77;43;27m▀\033[38;2;77;43;27;48;2;138;63;27m▀\033[38;2;148;75;37m▀  \033[38;2;138;63;27;48;2;159;108;80m▀\033[48;2;193;135;89m▀\033[48;2;204;156;119m▀\033[48;2;148;75;37m▀\033[38;2;148;75;37;48;2;138;63;27m▀\033[38;2;163;106;40;48;2;178;113;21m▀\033[38;2;178;113;21;48;2;163;106;40m▀\033[38;2;77;43;27;48;2;138;63;27m▀\033[38;2;0;0;0;48;2;48;26;16m▀\033[0m
\033[48;2;0;0;0m      \033[38;2;77;43;27;48;2;135;77;33m▀\033[48;2;138;63;27m \033[38;2;148;75;37;48;2;212;167;139m▀\033[38;2;212;167;139;48;2;250;212;185m▀\033[38;2;250;212;185;48;2;236;197;171m▀\033[38;2;254;222;198;48;2;250;212;185m▀\033[38;2;250;212;185;48;2;217;182;163m▀\033[38;2;193;135;89;48;2;236;197;171m▀\033[38;2;148;75;37;48;2;212;167;139m▀\033[38;2;138;63;27;48;2;148;75;37m▀\033[48;2;138;63;27m  \033[38;2;48;26;16;48;2;77;43;27m▀\033[0m
\033[48;2;0;0;0m    \033[38;2;0;0;0;48;2;48;26;16m▀\033[38;2;80;56;46m▀\033[48;2;115;57;29m \033[38;2;193;135;89;48;2;196;165;142m▀\033[38;2;236;197;171;48;2;143;124;112m▀\033[38;2;174;137;112;48;2;49;43;47m▀\033[38;2;236;197;171;48;2;132;98;81m▀\033[38;2;250;212;185;48;2;254;222;198m▀\033[48;2;250;212;185m \033[48;2;217;182;163m \033[48;2;103;102;109m \033[38;2;80;56;46m▀\033[38;2;138;63;27;48;2;148;75;37m▀\033[48;2;138;63;27m \033[38;2;115;57;29m▀\033[38;2;0;0;0;48;2;48;26;16m▀\033[0m
\033[48;2;0;0;0m      \033[38;2;48;26;16m▀\033[38;2;159;108;80;48;2;132;98;81m▀\033[38;2;143;124;112;48;2;236;197;171m▀\033[38;2;103;102;109;48;2;205;194;203m▀\033[38;2;127;135;167;48;2;197;193;212m▀\033[38;2;250;212;185;48;2;254;222;198m▀\033[48;2;250;212;185m \033[38;2;250;212;185;48;2;254;222;198m▀\033[38;2;127;135;167;48;2;212;167;139m▀\033[38;2;205;194;203;48;2;174;137;112m▀\033[38;2;204;156;119;48;2;236;197;171m▀\033[38;2;115;57;29;48;2;138;63;27m▀ \033[38;2;77;41;16;48;2;77;43;27m▀\033[48;2;0;0;0m    \033[38;2;48;26;16;48;2;49;43;47m▀▀\033[48;2;48;26;16m \033[0m
\033[48;2;0;0;0m       \033[38;2;77;41;16;48;2;115;57;29m▀\033[38;2;193;135;89m▀\033[38;2;217;182;163;48;2;159;108;80m▀\033[38;2;174;137;112;48;2;204;156;119m▀\033[38;2;250;212;185;48;2;236;197;171m▀\033[38;2;254;222;198;48;2;250;212;185m▀▀\033[38;2;193;135;89;48;2;254;222;198m▀\033[38;2;204;156;119;48;2;212;167;139m▀\033[38;2;132;98;81;48;2;48;26;16m▀\033[38;2;115;57;29;48;2;0;0;0m▀\033[38;2;138;63;27;48;2;77;43;27m▀\033[38;2;77;43;27;48;2;48;26;16m▀\033[48;2;0;0;0m \033[38;2;48;26;16m▀\033[38;2;0;0;0;48;2;49;43;47m▀\033[38;2;49;43;47;48;2;0;0;0m▀\033[48;2;48;26;16m▀\033[48;2;49;43;47m \033[48;2;48;26;16m \033[38;2;49;43;47;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m \033[38;2;49;43;47m▀\033[38;2;48;26;16m▀    \033[38;2;115;57;29;48;2;80;56;46m▀\033[48;2;48;26;16m \033[38;2;77;43;27;48;2;0;0;0m▀\033[38;2;212;167;139m▀\033[38;2;250;212;185;48;2;159;108;80m▀\033[48;2;254;222;198m▀ \033[38;2;204;156;119;48;2;159;108;80m▀\033[38;2;138;63;27;48;2;115;57;29m▀\033[38;2;77;43;27;48;2;138;63;27m▀\033[38;2;0;0;0;48;2;48;26;16m▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;48;26;16m▀\033[38;2;48;26;16;48;2;0;0;0m▀\033[38;2;0;0;0;48;2;48;26;16m▀\033[48;2;0;0;0m   \033[38;2;48;26;16m▀\033[38;2;49;43;47;48;2;48;26;16m▀\033[48;2;49;43;47m \033[0m
\033[48;2;0;0;0m  \033[38;2;48;26;16m▀\033[48;2;49;43;47m▀\033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;48;26;16m▀\033[48;2;0;0;0m   \033[48;2;77;41;16m \033[38;2;193;135;89;48;2;212;167;139m▀\033[48;2;254;222;198m \033[38;2;250;212;185;48;2;236;197;171m▀\033[38;2;193;135;89;48;2;198;139;29m▀\033[38;2;178;113;21;48;2;135;77;33m▀\033[38;2;138;63;27;48;2;77;43;27m▀\033[48;2;115;57;29m▀\033[38;2;48;26;16;48;2;138;63;27m▀\033[38;2;0;0;0;48;2;48;26;16m▀\033[38;2;48;26;16;48;2;0;0;0m▀\033[38;2;49;43;47m▀  \033[38;2;48;26;16m▀  ▀\033[0m
\033[48;2;48;26;16m \033[38;2;0;0;0m▀\033[48;2;0;0;0m   \033[38;2;0;0;0;48;2;48;26;16m▀▀\033[48;2;0;0;0m    \033[38;2;143;124;112;48;2;89;79;75m▀\033[48;2;254;222;198m \033[38;2;174;137;112;48;2;48;26;16m▀\033[48;2;0;0;0m    \033[38;2;77;41;16m▀\033[38;2;48;26;16m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;89;79;75;48;2;49;43;47m▀\033[38;2;0;0;0;48;2;48;26;16m▀\033[48;2;143;124;112m▀\033[38;2;217;182;163;48;2;254;222;198m▀\033[38;2;143;124;112;48;2;236;197;171m▀\033[38;2;0;0;0;48;2;48;26;16m▀\033[48;2;0;0;0m   \033[38;2;80;56;46;48;2;77;43;27m▀\033[38;2;143;124;112;48;2;115;57;29m▀\033[38;2;0;0;0;48;2;49;43;47m▀\033[38;2;80;56;46;48;2;89;79;75m▀\033[38;2;48;26;16;48;2;77;43;27m▀\033[48;2;0;0;0m        \033[38;2;0;0;0;48;2;48;26;16m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;48;26;16m▀\033[38;2;204;156;119;48;2;163;106;40m▀\033[38;2;254;222;198;48;2;250;212;185m▀▀\033[38;2;205;166;141;48;2;89;79;75m▀\033[48;2;0;0;0m    \033[38;2;135;77;33;48;2;115;57;29m▀\033[38;2;48;26;16;48;2;0;0;0m▀ ▀     \033[48;2;48;26;16m \033[0m
\033[48;2;0;0;0m    \033[38;2;163;106;40m▀\033[38;2;89;79;75m▀\033[48;2;49;43;47m▀\033[48;2;0;0;0m    \033[38;2;77;41;16;48;2;48;26;16m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;48;26;16;48;2;49;43;47m▀\033[48;2;0;0;0m▀       \033[38;2;0;0;0;48;2;77;41;16m▀\033[38;2;48;26;16m▀\033[0m
\033[48;2;0;0;0m           \033[48;2;48;26;16m  \033[48;2;0;0;0m       \033[38;2;48;26;16m▀\033[0m
==>25-lufthan-teal
\033[48;2;0;0;0m                \033[38;2;0;0;0;48;2;13;27;52m▀\033[38;2;13;27;52;48;2;29;41;62m▀\033[38;2;0;0;0;48;2;10;17;28m▀\033[0m
\033[48;2;0;0;0m            \033[38;2;0;0;0;48;2;10;17;28m▀\033[48;2;29;41;62m▀\033[48;2;25;54;112m▀▀\033[38;2;29;41;62m▀ \033[38;2;25;54;112;48;2;29;41;62m▀\033[48;2;25;54;112m \033[38;2;0;0;0;48;2;10;17;28m▀\033[0m
\033[48;2;0;0;0m         \033[38;2;0;0;0;48;2;10;17;28m▀\033[48;2;25;54;112m▀\033[38;2;13;27;52;48;2;26;65;139m▀    \033[38;2;13;54;132m▀▀ \033[38;2;29;41;62m▀\033[38;2;0;0;0;48;2;29;41;62m▀\033[0m
\033[48;2;0;0;0m        \033[38;2;0;0;0;48;2;10;17;28m▀\033[38;2;10;17;28;48;2;25;54;112m▀\033[38;2;25;54;112;48;2;26;65;139m▀ \033[38;2;26;65;139;48;2;13;54;132m▀\033[48;2;26;65;139m \033[38;2;13;54;132;48;2;109;121;160m▀\033[38;2;26;65;139;48;2;138;132;142m▀\033[38;2;13;54;132;48;2;108;114;143m▀\033[38;2;26;65;139;48;2;13;54;132m▀\033[48;2;63;82;127m \033[38;2;118;100;64;48;2;149;117;54m▀\033[48;2;63;82;127m \033[38;2;10;17;28;48;2;25;54;112m▀\033[0m
\033[48;2;0;0;0m         \033[38;2;10;17;28;48;2;13;27;52m▀\033[48;2;26;65;139m \033[38;2;26;65;139;48;2;63;82;127m▀\033[38;2;109;121;160;48;2;215;190;175m▀\033[38;2;215;190;175;48;2;246;215;190m▀\033[38;2;246;215;190;48;2;215;190;175m▀▀\033[38;2;215;190;175;48;2;193;174;168m▀\033[38;2;109;121;160;48;2;215;190;175m▀\033[38;2;26;65;139;48;2;138;132;142m▀\033[48;2;13;54;132m▀\033[48;2;26;65;139m \033[38;2;25;54;112;48;2;13;54;132m▀\033[38;2;0;0;0;48;2;10;17;28m▀\033[0m
\033[48;2;0;0;0m        \033[38;2;13;27;52;48;2;10;17;28m▀\033[38;2;25;54;112;48;2;13;27;52m▀\033[38;2;13;54;132;48;2;63;82;127m▀\033[38;2;175;163;169;48;2;180;155;130m▀\033[38;2;193;174;168;48;2;117;108;100m▀\033[38;2;180;155;130;48;2;29;41;62m▀\033[38;2;246;215;190;48;2;193;174;168m▀\033[48;2;246;215;190m  \033[38;2;180;155;130;48;2;193;174;168m▀\033[38;2;61;58;54;48;2;66;72;89m▀\033[38;2;25;54;112;48;2;108;114;143m▀\033[38;2;26;65;139;48;2;13;54;132m▀\033[48;2;26;65;139m \033[38;2;13;27;52;48;2;25;54;112m▀\033[0m
\033[48;2;0;0;0m         \033[38;2;10;17;28m▀\033[48;2;61;58;54m \033[38;2;148;133;122;48;2;109;121;160m▀\033[48;2;246;215;190m▀\033[38;2;63;82;127;48;2;193;174;168m▀\033[38;2;215;190;175;48;2;246;215;190m▀  \033[38;2;193;174;168m▀\033[38;2;109;121;160;48;2;138;132;142m▀\033[38;2;246;215;190;48;2;215;190;175m▀\033[38;2;63;82;127;48;2;109;121;160m▀\033[48;2;13;54;132m \033[38;2;25;54;112m▀\033[48;2;10;17;28m \033[48;2;0;0;0m   \033[38;2;10;17;28;48;2;30;33;33m▀ \033[48;2;26;26;21m \033[0m
\033[48;2;0;0;0m    \033[38;2;0;0;0;48;2;26;26;21m▀\033[48;2;10;17;28m▀\033[48;2;0;0;0m    \033[48;2;10;17;28m \033[38;2;26;65;139;48;2;25;54;112m▀\033[38;2;193;174;168;48;2;66;72;89m▀\033[48;2;138;132;142m▀\033[38;2;215;190;175;48;2;193;174;168m▀\033[48;2;246;215;190m  \033[38;2;215;190;175m▀\033[38;2;138;132;142m▀\033[38;2;148;133;122;48;2;63;82;127m▀\033[38;2;61;58;54;48;2;0;0;0m▀\033[38;2;26;65;139;48;2;29;41;62m▀▀\033[48;2;10;17;28m \033[38;2;26;26;21;48;2;0;0;0m▀\033[48;2;26;26;21m \033[38;2;10;17;28m▀\033[38;2;30;33;33;48;2;10;17;28m▀\033[38;2;26;26;21;48;2;30;33;33m▀\033[38;2;10;17;28m▀\033[38;2;30;33;33;48;2;26;26;21m▀\033[38;2;10;17;28;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m \033[38;2;10;17;28m▀  \033[38;2;0;0;0;48;2;10;17;28m▀\033[38;2;30;33;33m▀\033[38;2;26;26;21;48;2;0;0;0m▀\033[38;2;0;0;0;48;2;10;17;28m▀\033[48;2;0;0;0m  \033[48;2;10;17;28m \033[38;2;26;65;139;48;2;29;41;62m▀\033[48;2;0;0;0m \033[38;2;63;82;127m▀\033[38;2;215;190;175;48;2;29;41;62m▀\033[38;2;246;215;190;48;2;193;174;168m▀\033[48;2;246;215;190m \033[38;2;246;215;190;48;2;215;190;175m▀\033[38;2;108;114;143;48;2;25;54;112m▀\033[38;2;13;54;132;48;2;26;65;139m▀\033[38;2;10;17;28;48;2;25;54;112m▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;10;17;28m▀\033[38;2;26;26;21m▀ \033[38;2;0;0;0m▀\033[48;2;0;0;0m  \033[48;2;10;17;28m \033[38;2;30;33;33m▀\033[48;2;61;58;54m \033[38;2;10;17;28;48;2;26;26;21m▀\033[0m
\033[48;2;0;0;0m   \033[38;2;0;0;0;48;2;10;17;28m▀\033[48;2;0;0;0m \033[38;2;26;26;21m▀\033[38;2;30;33;33;48;2;10;17;28m▀\033[38;2;10;17;28;48;2;26;26;21m▀\033[48;2;0;0;0m \033[38;2;10;17;28m▀\033[48;2;26;26;21m▀\033[48;2;0;0;0m  \033[38;2;26;26;21;48;2;10;17;28m▀\033[38;2;124;93;24;48;2;149;117;54m▀\033[38;2;215;182;143;48;2;246;215;190m▀ \033[48;2;208;166;94m▀\033[48;2;195;134;5m \033[38;2;66;72;89;48;2;61;58;54m▀\033[38;2;26;65;139;48;2;13;54;132m▀\033[38;2;29;41;62;48;2;26;65;139m▀\033[38;2;0;0;0;48;2;25;54;112m▀\033[48;2;10;17;28m \033[38;2;30;33;33;48;2;0;0;0m▀▀ \033[38;2;26;26;21m▀  \033[38;2;10;17;28m▀\033[38;2;26;26;21m▀\033[0m
\033[48;2;0;0;0m    \033[38;2;30;33;33;48;2;26;26;21m▀\033[38;2;0;0;0;48;2;30;33;33m▀\033[48;2;0;0;0m   \033[38;2;0;0;0;48;2;61;58;54m▀\033[48;2;0;0;0m    \033[38;2;10;17;28m▀\033[38;2;215;190;175;48;2;193;174;168m▀\033[38;2;246;215;190;48;2;180;155;130m▀\033[38;2;61;58;54;48;2;0;0;0m▀   \033[38;2;10;17;28m▀\033[38;2;13;27;52m▀\033[38;2;10;17;28m▀      ▀\033[0m
\033[48;2;0;0;0m    \033[38;2;0;0;0;48;2;10;17;28m▀\033[38;2;29;41;62m▀\033[38;2;61;58;54;48;2;30;33;33m▀\033[38;2;0;0;0m▀\033[38;2;30;33;33;48;2;215;182;143m▀\033[48;2;246;215;190m \033[38;2;68;54;22;48;2;180;155;130m▀\033[48;2;0;0;0m    \033[38;2;148;133;122;48;2;124;93;24m▀\033[38;2;61;58;54;48;2;26;26;21m▀\033[38;2;10;17;28;48;2;61;58;54m▀\033[38;2;61;58;54;48;2;118;100;64m▀\033[38;2;10;17;28;48;2;26;26;21m▀\033[48;2;0;0;0m    \033[48;2;10;17;28m \033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;10;17;28m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;10;17;28m▀\033[48;2;0;0;0m   \033[38;2;68;54;22;48;2;30;33;33m▀\033[38;2;215;190;175;48;2;208;166;94m▀\033[48;2;246;215;190m \033[38;2;246;215;190;48;2;193;174;168m▀\033[38;2;117;108;100;48;2;26;26;21m▀\033[48;2;0;0;0m   \033[48;2;26;26;21m \033[38;2;124;93;24;48;2;68;54;22m▀\033[48;2;0;0;0m \033[38;2;10;17;28m▀▀  \033[38;2;0;0;0;48;2;26;26;21m▀\033[48;2;0;0;0m \033[38;2;10;17;28m▀▀\033[0m
\033[48;2;0;0;0m    \033[48;2;10;17;28m \033[38;2;10;17;28;48;2;0;0;0m▀ \033[38;2;68;54;22;48;2;30;33;33m▀\033[38;2;149;117;54;48;2;0;0;0m▀\033[38;2;66;72;89m▀\033[48;2;30;33;33m \033[48;2;0;0;0m   \033[38;2;30;33;33m▀\033[38;2;68;54;22;48;2;26;26;21m▀\033[48;2;0;0;0m       \033[38;2;0;0;0;48;2;10;17;28m▀\033[48;2;0;0;0m    \033[38;2;10;17;28m▀ \033[38;2;0;0;0;48;2;26;26;21m▀\033[0m
\033[48;2;0;0;0m   \033[38;2;10;17;28m▀ \033[38;2;0;0;0;48;2;10;17;28m▀\033[38;2;30;33;33;48;2;26;26;21m▀\033[38;2;10;17;28;48;2;0;0;0m▀       \033[38;2;30;33;33;48;2;68;54;22m▀\033[48;2;0;0;0m      \033[38;2;10;17;28m▀\033[38;2;0;0;0;48;2;10;17;28m▀\033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;10;17;28m▀\033[48;2;0;0;0m   \033[38;2;10;17;28m▀\033[0m
\033[38;2;0;0;0;48;2;10;17;28m▀▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;26;26;21m▀\033[48;2;10;17;28m▀\033[48;2;0;0;0m          \033[48;2;68;54;22m \033[48;2;0;0;0m       \033[38;2;10;17;28m▀ ▀\033[0m
==>26-lufthan-violet
\033[48;2;0;0;0m               \033[38;2;0;0;0;48;2;43;29;39m▀ ▀\033[0m
\033[48;2;0;0;0m           \033[38;2;0;0;0;48;2;43;29;39m▀\033[48;2;62;38;66m▀▀\033[38;2;43;29;39m▀\033[38;2;62;38;66;48;2;101;23;135m▀\033[38;2;84;22;109;48;2;88;31;109m▀\033[38;2;101;23;135m▀\033[38;2;62;38;66m▀\033[0m
\033[48;2;0;0;0m        \033[38;2;0;0;0;48;2;43;29;39m▀\033[48;2;88;31;109m▀\033[38;2;62;38;66;48;2;116;37;145m▀\033[38;2;116;37;145;48;2;108;27;139m▀▀\033[38;2;101;23;135;48;2;91;37;112m▀\033[48;2;101;23;135m \033[38;2;108;27;139;48;2;91;37;112m▀ \033[38;2;91;37;112;48;2;84;22;109m▀\033[38;2;43;29;39m▀\033[38;2;0;0;0;48;2;43;29;39m▀\033[0m
\033[48;2;0;0;0m       \033[38;2;0;0;0;48;2;43;29;39m▀\033[38;2;43;29;39;48;2;88;31;109m▀\033[38;2;101;23;135;48;2;108;27;139m▀ \033[38;2;108;27;139;48;2;101;23;135m▀\033[38;2;101;23;135;48;2;131;67;138m▀\033[38;2;84;22;109;48;2;146;113;117m▀\033[48;2;175;148;151m▀\033[48;2;137;88;143m▀\033[38;2;114;49;122;48;2;101;23;135m▀\033[38;2;122;80;78;48;2;155;98;93m▀ \033[38;2;91;37;112;48;2;108;27;139m▀\033[38;2;0;0;0;48;2;43;29;39m▀\033[0m
\033[48;2;0;0;0m        \033[38;2;43;29;39;48;2;62;38;66m▀\033[48;2;108;27;139m \033[38;2;108;27;139;48;2;137;88;143m▀\033[38;2;180;125;153;48;2;238;201;180m▀\033[48;2;229;190;174m \033[38;2;254;222;192;48;2;238;201;180m▀\033[48;2;229;190;174m▀\033[38;2;208;170;160m▀\033[38;2;131;67;138;48;2;238;201;180m▀\033[38;2;101;23;135;48;2;131;67;138m▀\033[38;2;108;27;139;48;2;101;23;135m▀\033[48;2;116;37;145m \033[38;2;62;38;66;48;2;84;22;109m▀\033[0m
\033[48;2;0;0;0m       \033[38;2;62;38;66;48;2;43;29;39m▀\033[38;2;88;31;109;48;2;62;38;66m▀\033[38;2;114;49;122;48;2;137;88;143m▀\033[38;2;238;201;180;48;2;175;148;151m▀\033[38;2;175;148;151;48;2;90;66;79m▀\033[38;2;208;170;160m▀\033[48;2;238;201;180m \033[48;2;241;202;186m \033[48;2;248;217;193m \033[38;2;146;113;117;48;2;137;88;143m▀\033[38;2;62;38;66;48;2;103;83;112m▀\033[38;2;108;27;139;48;2;131;67;138m▀\033[48;2;101;23;135m▀\033[38;2;101;23;135;48;2;116;37;145m▀\033[38;2;0;0;0;48;2;43;29;39m▀\033[0m
\033[48;2;0;0;0m        \033[38;2;43;29;39m▀\033[48;2;90;66;79m \033[38;2;146;113;117;48;2;180;125;153m▀\033[38;2;175;148;151;48;2;248;217;193m▀\033[38;2;103;83;112;48;2;210;200;205m▀\033[48;2;248;217;193m   \033[38;2;175;148;151;48;2;229;190;174m▀\033[38;2;210;200;205;48;2;180;125;153m▀\033[38;2;208;170;160;48;2;229;190;174m▀\033[38;2;84;22;109;48;2;131;67;138m▀\033[38;2;116;37;145;48;2;108;27;139m▀\033[38;2;84;22;109;48;2;91;37;112m▀\033[38;2;0;0;0;48;2;43;29;39m▀\033[48;2;0;0;0m   \033[38;2;0;0;0;48;2;43;29;39m▀ ▀\033[0m
\033[48;2;0;0;0m         \033[48;2;43;29;39m \033[38;2;131;67;138;48;2;101;23;135m▀\033[38;2;229;190;174;48;2;103;83;112m▀\033[38;2;208;170;160;48;2;180;125;153m▀\033[38;2;238;201;180;48;2;229;190;174m▀\033[48;2;254;222;192m \033[38;2;254;222;192;48;2;241;202;186m▀\033[38;2;208;170;160;48;2;254;222;192m▀\033[38;2;180;125;153;48;2;241;202;186m▀\033[38;2;146;113;117;48;2;62;38;66m▀\033[38;2;84;22;109;48;2;0;0;0m▀\033[38;2;108;27;139;48;2;62;38;66m▀\033[38;2;62;38;66;48;2;43;29;39m▀\033[48;2;0;0;0m \033[38;2;43;29;39m▀\033[38;2;0;0;0;48;2;43;29;39m▀\033[38;2;43;29;39;48;2;0;0;0m▀\033[48;2;43;29;39m  \033[38;2;0;0;0m▀\033[38;2;43;29;39;48;2;0;0;0m▀\033[0m
\033[38;2;43;29;39;48;2;0;0;0m▀   ▀    \033[48;2;43;29;39m \033[38;2;91;37;112;48;2;62;38;66m▀\033[48;2;0;0;0m \033[38;2;146;113;117m▀\033[38;2;241;202;186;48;2;103;83;112m▀\033[48;2;241;202;186m \033[48;2;248;217;193m \033[38;2;208;170;160;48;2;180;125;153m▀\033[38;2;116;37;145;48;2;101;23;135m▀\033[38;2;88;31;109;48;2;116;37;145m▀\033[38;2;0;0;0;48;2;62;38;66m▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;43;29;39m▀\033[38;2;43;29;39;48;2;0;0;0m▀\033[38;2;0;0;0;48;2;43;29;39m▀\033[48;2;0;0;0m   \033[38;2;43;29;39m▀\033[38;2;62;38;66;48;2;43;29;39m▀\033[48;2;62;38;66m \033[0m
\033[48;2;0;0;0m    \033[38;2;43;29;39m▀▀  \033[38;2;0;0;0;48;2;43;29;39m▀▀\033[48;2;0;0;0m  \033[38;2;74;54;26m▀\033[38;2;149;107;30;48;2;226;175;75m▀\033[38;2;238;201;180;48;2;248;217;193m▀\033[38;2;254;222;192;48;2;238;201;180m▀\033[38;2;213;176;129;48;2;226;175;75m▀\033[48;2;149;107;30m \033[38;2;114;49;122;48;2;62;38;66m▀\033[38;2;108;27;139;48;2;101;23;135m▀\033[38;2;43;29;39;48;2;116;37;145m▀\033[38;2;0;0;0;48;2;62;38;66m▀\033[38;2;43;29;39;48;2;0;0;0m▀▀     ▀\033[0m
\033[48;2;0;0;0m   \033[48;2;43;29;39m \033[38;2;0;0;0m▀\033[48;2;0;0;0m   \033[38;2;0;0;0;48;2;62;38;66m▀\033[48;2;0;0;0m    \033[38;2;62;38;66;48;2;43;29;39m▀\033[48;2;254;222;192m \033[38;2;208;170;160;48;2;122;80;78m▀\033[38;2;43;29;39;48;2;0;0;0m▀   ▀▀\033[0m
\033[48;2;0;0;0m    \033[38;2;62;38;66m▀\033[48;2;43;29;39m \033[38;2;0;0;0;48;2;62;38;66m▀\033[38;2;122;80;78;48;2;238;201;180m▀\033[38;2;238;201;180;48;2;254;222;192m▀\033[38;2;0;0;0;48;2;103;83;112m▀\033[48;2;0;0;0m   \033[38;2;0;0;0;48;2;43;29;39m▀\033[38;2;175;148;151;48;2;122;80;78m▀\033[48;2;43;29;39m \033[38;2;62;38;66;48;2;90;66;79m▀\033[38;2;43;29;39m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;122;80;78;48;2;74;54;26m▀\033[38;2;241;202;186;48;2;226;175;75m▀\033[48;2;254;222;192m \033[38;2;254;222;192;48;2;175;148;151m▀\033[38;2;62;38;66;48;2;0;0;0m▀   \033[48;2;74;54;26m \033[38;2;74;54;26;48;2;43;29;39m▀\033[48;2;0;0;0m \033[38;2;43;29;39m▀     ▀\033[0m
\033[48;2;0;0;0m   \033[38;2;43;29;39m▀▀ \033[38;2;74;54;26;48;2;43;29;39m▀\033[38;2;149;107;30;48;2;0;0;0m▀\033[38;2;90;66;79;48;2;43;29;39m▀\033[38;2;43;29;39;48;2;0;0;0m▀   \033[38;2;74;54;26;48;2;43;29;39m▀\033[0m
\033[48;2;0;0;0m    \033[38;2;0;0;0;48;2;43;29;39m▀\033[38;2;43;29;39;48;2;0;0;0m▀       \033[38;2;0;0;0;48;2;43;29;39m▀\033[48;2;74;54;26m \033[48;2;0;0;0m      \033[38;2;0;0;0;48;2;43;29;39m▀\033[0m
\033[48;2;0;0;0m             \033[38;2;43;29;39m▀\033[48;2;74;54;26m \033[0m
==>27-publicq
\033[48;2;0;0;0m      \033[38;2;0;0;0;48;2;101;71;48m▀▀\033[38;2;49;32;22;48;2;113;82;54m▀▀▀▀▀▀\033[38;2;101;71;48m▀\033[48;2;124;94;47m \033[38;2;141;127;16;48;2;174;152;24m▀\033[48;2;250;238;2m▀▀\033[48;2;213;191;18m▀\033[48;2;250;238;2m▀▀▀▀▀▀\033[48;2;49;32;22m \033[0m
\033[48;2;0;0;0m   \033[38;2;0;0;0;48;2;101;71;48m▀\033[38;2;49;32;22;48;2;113;82;54m▀ \033[38;2;113;82;54;48;2;101;71;48m▀\033[48;2;113;82;54m \033[38;2;101;71;48m▀▀▀▀▀▀▀▀▀\033[38;2;124;94;47m▀\033[38;2;174;152;24;48;2;101;71;48m▀\033[38;2;213;191;18;48;2;124;94;47m▀\033[38;2;250;238;2;48;2;213;191;18m▀\033[48;2;250;238;2m   \033[38;2;250;238;2;48;2;213;191;18m▀\033[38;2;174;152;24;48;2;113;82;54m▀\033[38;2;0;0;0;48;2;49;32;22m▀\033[0m
\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;101;71;48m▀\033[38;2;101;71;48;48;2;113;82;54m▀\033[38;2;113;82;54;48;2;101;71;48m▀\033[48;2;113;82;54m  \033[38;2;113;82;54;48;2;101;71;48m▀\033[48;2;113;82;54m            \033[38;2;101;71;48m▀ \033[38;2;124;94;47;48;2;101;71;48m▀\033[38;2;134;107;52m▀▀\033[48;2;113;82;54m  \033[38;2;101;71;48m▀\033[38;2;0;0;0;48;2;49;32;22m▀\033[0m
\033[48;2;49;32;22m \033[48;2;113;82;54m \033[48;2;101;71;48m \033[38;2;101;71;48;48;2;113;82;54m▀  \033[48;2;101;71;48m \033[48;2;113;82;54m   \033[38;2;113;82;54;48;2;101;71;48m▀▀\033[48;2;113;82;54m \033[38;2;113;82;54;48;2;101;71;48m▀▀\033[48;2;113;82;54m    \033[48;2;101;71;48m \033[48;2;113;82;54m      \033[48;2;101;71;48m \033[48;2;125;97;67m \033[38;2;49;32;22m▀\033[0m
\033[38;2;49;32;22;48;2;101;71;48m▀\033[38;2;113;82;54;48;2;125;97;67m▀\033[38;2;101;71;48;48;2;113;82;54m▀▀  ▀  \033[48;2;101;71;48m \033[38;2;113;82;54;48;2;125;97;67m▀\033[48;2;231;177;142m▀\033[38;2;101;71;48;48;2;125;97;67m▀\033[38;2;113;82;54;48;2;101;71;48m▀\033[38;2;140;112;81;48;2;180;153;118m▀\033[38;2;101;71;48;48;2;125;97;67m▀\033[38;2;113;82;54;48;2;101;71;48m▀\033[48;2;113;82;54m  \033[48;2;101;71;48m \033[48;2;113;82;54m       \033[38;2;113;82;54;48;2;101;71;48m▀▀\033[38;2;49;32;22;48;2;113;82;54m▀\033[38;2;0;0;0;48;2;49;32;22m▀\033[0m
\033[38;2;101;71;48;48;2;49;32;22m▀\033[38;2;140;112;81;48;2;124;94;47m▀\033[38;2;125;97;67;48;2;101;71;48m▀\033[48;2;165;130;94m \033[38;2;165;130;94;48;2;230;193;153m▀\033[48;2;113;82;54m \033[38;2;125;97;67m▀\033[48;2;101;71;48m▀\033[48;2;113;82;54m▀▀▀\033[38;2;246;209;168;48;2;231;177;142m▀\033[38;2;204;173;134;48;2;252;222;181m▀\033[38;2;101;71;48;48;2;204;173;134m▀\033[38;2;205;173;137;48;2;165;130;94m▀\033[38;2;230;193;153;48;2;246;209;168m▀\033[38;2;101;71;48;48;2;180;153;118m▀\033[48;2;113;82;54m  \033[38;2;101;71;48m▀        \033[48;2;101;71;48m \033[38;2;113;82;54m▀\033[38;2;101;71;48;48;2;113;82;54m▀\033[0m
\033[48;2;101;71;48m \033[48;2;124;94;47m \033[48;2;101;71;48m \033[38;2;154;123;92;48;2;179;139;103m▀\033[38;2;246;209;168;48;2;252;222;181m▀\033[38;2;157;131;100m▀\033[38;2;125;97;67;48;2;165;130;94m▀\033[38;2;154;123;92;48;2;230;193;153m▀\033[38;2;101;71;48;48;2;140;112;81m▀\033[38;2;113;82;54;48;2;101;71;48m▀ \033[38;2;204;173;134;48;2;180;153;118m▀\033[38;2;246;209;168;48;2;157;131;100m▀\033[38;2;154;123;92;48;2;140;112;81m▀\033[48;2;49;32;22m \033[38;2;49;32;22;48;2;0;0;0m▀\033[38;2;125;97;67;48;2;49;32;22m▀\033[38;2;113;82;54;48;2;101;71;48m▀\033[48;2;113;82;54m \033[38;2;101;71;48m▀       \033[48;2;101;71;48m \033[48;2;113;82;54m \033[38;2;49;32;22;48;2;101;71;48m▀\033[48;2;0;0;0m▀\033[0m
\033[48;2;101;71;48m \033[38;2;124;94;47;48;2;113;82;54m▀\033[38;2;101;71;48m▀\033[38;2;165;130;94;48;2;101;71;48m▀\033[38;2;172;137;103;48;2;125;97;67m▀ \033[38;2;125;97;67;48;2;0;0;0m▀\033[38;2;207;161;126;48;2;101;71;48m▀\033[38;2;246;209;168;48;2;252;222;181m▀\033[38;2;125;97;67;48;2;246;209;168m▀\033[38;2;101;71;48;48;2;140;112;81m▀\033[38;2;140;112;81;48;2;101;71;48m▀\033[38;2;246;209;168;48;2;230;193;153m▀\033[48;2;252;222;181m \033[38;2;49;32;22;48;2;101;71;48m▀\033[48;2;0;0;0m \033[38;2;49;32;22;48;2;140;112;81m▀\033[38;2;172;137;103;48;2;205;173;137m▀\033[38;2;101;71;48;48;2;113;82;54m▀\033[38;2;113;82;54;48;2;101;71;48m▀\033[38;2;101;71;48;48;2;140;112;81m▀\033[48;2;101;71;48m \033[38;2;49;32;22;48;2;0;0;0m▀\033[38;2;101;71;48;48;2;49;32;22m▀\033[48;2;113;82;54m    \033[48;2;101;71;48m \033[48;2;113;82;54m \033[48;2;49;32;22m \033[0m
\033[48;2;49;32;22m \033[48;2;124;94;47m \033[48;2;113;82;54m \033[38;2;101;71;48m▀\033[38;2;246;209;168;48;2;230;193;153m▀\033[38;2;172;137;103;48;2;246;209;168m▀\033[48;2;0;0;0m \033[48;2;101;71;48m \033[38;2;246;209;168;48;2;230;193;153m▀\033[48;2;252;222;181m \033[38;2;252;222;181;48;2;246;209;168m▀\033[38;2;165;130;94;48;2;252;222;181m▀\033[38;2;179;139;103;48;2;230;193;153m▀\033[38;2;252;222;181;48;2;246;209;168m▀\033[38;2;180;153;118m▀\033[38;2;0;0;0;48;2;154;123;92m▀\033[38;2;49;32;22;48;2;207;161;126m▀\033[38;2;180;153;118;48;2;172;137;103m▀\033[38;2;113;82;54;48;2;101;71;48m▀\033[38;2;101;71;48;48;2;113;82;54m▀\033[38;2;207;161;126;48;2;204;173;134m▀\033[38;2;125;97;67;48;2;49;32;22m▀\033[48;2;0;0;0m \033[48;2;49;32;22m \033[48;2;113;82;54m    \033[48;2;101;71;48m \033[48;2;113;82;54m \033[48;2;101;71;48m \033[0m
\033[48;2;0;0;0m \033[38;2;113;82;54;48;2;49;32;22m▀\033[48;2;124;94;47m▀\033[38;2;101;71;48;48;2;113;82;54m▀\033[38;2;154;123;92;48;2;125;97;67m▀\033[38;2;252;222;181;48;2;205;173;137m▀\033[38;2;140;112;81;48;2;231;177;142m▀\033[38;2;49;32;22;48;2;246;209;168m▀\033[38;2;230;193;153;48;2;252;222;181m▀ \033[38;2;246;209;168m▀▀    \033[38;2;230;193;153;48;2;231;177;142m▀\033[38;2;140;112;81;48;2;207;161;126m▀\033[38;2;125;97;67;48;2;157;131;100m▀\033[48;2;101;71;48m▀\033[38;2;49;32;22;48;2;0;0;0m▀\033[38;2;0;0;0;48;2;49;32;22m▀\033[48;2;0;0;0m \033[48;2;49;32;22m \033[38;2;113;82;54;48;2;101;71;48m▀\033[48;2;49;32;22m▀\033[48;2;113;82;54m \033[38;2;113;82;54;48;2;101;71;48m▀\033[48;2;113;82;54m   \033[0m
\033[48;2;0;0;0m  \033[38;2;49;32;22m▀\033[38;2;124;94;47;48;2;49;32;22m▀\033[38;2;125;97;67;48;2;101;71;48m▀\033[38;2;231;177;142;48;2;207;161;126m▀\033[38;2;230;193;153;48;2;252;222;181m▀\033[38;2;252;222;181;48;2;246;209;168m▀\033[38;2;246;209;168;48;2;252;222;181m▀\033[48;2;246;209;168m   \033[38;2;246;209;168;48;2;252;222;181m▀▀▀▀▀\033[38;2;204;173;134;48;2;101;71;48m▀\033[48;2;49;32;22m \033[38;2;49;32;22;48;2;0;0;0m▀\033[38;2;125;97;67;48;2;180;153;118m▀\033[38;2;180;153;118;48;2;207;161;126m▀\033[38;2;0;0;0;48;2;49;32;22m▀\033[38;2;101;71;48m▀\033[48;2;0;0;0m \033[38;2;49;32;22;48;2;101;71;48m▀\033[48;2;113;82;54m \033[48;2;101;71;48m  \033[48;2;113;82;54m \033[48;2;101;71;48m \033[0m
\033[48;2;0;0;0m    \033[38;2;49;32;22m▀\033[48;2;101;71;48m \033[38;2;230;193;153;48;2;49;32;22m▀\033[38;2;252;222;181;48;2;207;161;126m▀\033[48;2;252;222;181m \033[38;2;252;222;181;48;2;246;209;168m▀\033[48;2;252;222;181m   \033[38;2;246;209;168m▀▀\033[48;2;230;193;153m▀\033[48;2;246;209;168m \033[38;2;101;71;48;48;2;157;131;100m▀\033[38;2;49;32;22;48;2;0;0;0m▀ \033[38;2;157;131;100;48;2;140;112;81m▀\033[48;2;204;173;134m \033[48;2;0;0;0m   \033[48;2;101;71;48m \033[48;2;113;82;54m \033[38;2;101;71;48m▀ \033[48;2;49;32;22m▀\033[0m
\033[48;2;0;0;0m      \033[38;2;101;71;48;48;2;204;173;134m▀\033[38;2;180;153;118;48;2;252;222;181m▀\033[38;2;246;209;168m▀\033[38;2;230;193;153;48;2;231;177;142m▀\033[38;2;246;209;168;48;2;227;124;100m▀\033[38;2;252;222;181;48;2;230;193;153m▀\033[48;2;231;177;142m▀\033[38;2;246;209;168;48;2;230;193;153m▀\033[38;2;230;193;153;48;2;246;209;168m▀▀\033[38;2;252;222;181m▀ \033[38;2;180;153;118;48;2;252;222;181m▀▀\033[38;2;230;193;153m▀\033[38;2;246;209;168m▀\033[38;2;180;153;118m▀\033[38;2;157;131;100m▀\033[38;2;101;71;48;48;2;179;139;103m▀\033[48;2;125;97;67m▀\033[48;2;172;137;103m▀\033[48;2;101;71;48m \033[38;2;49;32;22;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;49;32;22;48;2;113;82;54m▀\033[48;2;252;222;181m \033[38;2;246;209;168m▀ \033[48;2;231;177;142m \033[48;2;216;111;92m \033[38;2;246;209;168;48;2;252;222;181m▀\033[38;2;230;193;153m▀\033[38;2;252;222;181;48;2;246;209;168m▀\033[38;2;246;209;168;48;2;252;222;181m▀▀▀▀\033[48;2;230;193;153m▀\033[48;2;246;209;168m     \033[38;2;252;222;181m▀\033[38;2;246;209;168;48;2;252;222;181m▀ \033[38;2;205;173;137m▀\033[38;2;0;0;0;48;2;101;71;48m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;113;82;54;48;2;101;71;48m▀\033[48;2;252;222;181m   \033[48;2;231;177;142m \033[48;2;216;111;92m \033[48;2;252;222;181m  \033[48;2;246;209;168m \033[48;2;252;222;181m    \033[38;2;246;209;168m▀\033[48;2;246;209;168m \033[38;2;246;209;168;48;2;252;222;181m▀▀\033[48;2;246;209;168m   \033[38;2;246;209;168;48;2;230;193;153m▀\033[48;2;246;209;168m \033[48;2;252;222;181m \033[38;2;140;112;81;48;2;157;131;100m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;49;32;22m▀\033[38;2;246;209;168;48;2;205;173;137m▀\033[48;2;252;222;181m  \033[38;2;207;161;126;48;2;227;124;100m▀\033[38;2;228;84;70;48;2;229;51;45m▀\033[38;2;230;193;153m▀\033[38;2;246;209;168;48;2;228;84;70m▀\033[38;2;252;222;181;48;2;227;124;100m▀\033[48;2;231;177;142m▀\033[48;2;216;111;92m▀\033[38;2;246;209;168;48;2;228;84;70m▀\033[38;2;231;177;142;48;2;229;51;45m▀\033[38;2;216;111;92m▀\033[38;2;189;101;81m▀\033[38;2;246;209;168;48;2;231;177;142m▀\033[48;2;252;222;181m \033[38;2;246;209;168m▀▀▀▀▀ \033[38;2;180;153;118;48;2;204;173;134m▀\033[0m
\033[48;2;0;0;0m      \033[38;2;49;32;22m▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀\033[0m
==>28-publicq-blue
\033[48;2;0;0;0m     \033[38;2;0;0;0;48;2;29;35;44m▀\033[48;2;62;68;76m▀\033[38;2;29;35;44;48;2;57;80;115m▀▀▀▀▀▀▀\033[38;2;89;85;55m▀\033[38;2;162;132;34;48;2;81;90;96m▀\033[38;2;167;150;33;48;2;184;188;42m▀\033[38;2;162;132;34;48;2;251;245;1m▀\033[38;2;89;85;55m▀\033[38;2;162;132;34;48;2;233;227;14m▀\033[48;2;251;245;1m▀▀▀▀▀\033[38;2;167;150;33m▀\033[38;2;29;35;44;48;2;62;44;34m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;29;35;44m▀\033[38;2;29;35;44;48;2;50;74;109m▀\033[38;2;62;68;76;48;2;57;80;115m▀\033[38;2;57;80;115;48;2;50;74;109m▀▀        \033[38;2;50;74;109;48;2;57;80;115m▀▀\033[38;2;108;96;74;48;2;50;74;109m▀\033[38;2;167;150;33m▀\033[38;2;233;227;14;48;2;108;96;74m▀\033[38;2;251;245;1;48;2;233;227;14m▀\033[48;2;251;245;1m   \033[38;2;251;245;1;48;2;167;150;33m▀\033[38;2;162;132;34;48;2;50;74;109m▀\033[38;2;0;0;0;48;2;29;35;44m▀\033[0m
\033[38;2;0;0;0;48;2;29;35;44m▀\033[38;2;29;35;44;48;2;57;80;115m▀\033[38;2;57;80;115;48;2;50;74;109m▀ \033[38;2;50;74;109;48;2;57;80;115m▀  \033[48;2;50;74;109m        \033[48;2;57;80;115m    \033[48;2;50;74;109m \033[38;2;71;84;101m▀\033[38;2;108;96;74m▀▀\033[38;2;81;90;96m▀ \033[38;2;57;80;115m▀\033[38;2;50;74;109;48;2;57;80;115m▀\033[38;2;0;0;0;48;2;62;68;76m▀\033[0m
\033[48;2;50;74;109m    \033[48;2;57;80;115m   \033[38;2;50;74;109m▀\033[48;2;50;74;109m      \033[38;2;57;80;115m▀\033[48;2;57;80;115m    \033[48;2;50;74;109m  \033[38;2;50;74;109;48;2;57;80;115m▀\033[38;2;62;68;76;48;2;50;74;109m▀   \033[38;2;50;74;109;48;2;71;84;101m▀\033[38;2;77;92;98;48;2;123;118;114m▀\033[38;2;29;35;44;48;2;77;92;98m▀\033[0m
\033[38;2;50;74;109;48;2;77;92;98m▀\033[48;2;66;82;106m▀\033[48;2;50;74;109m \033[38;2;50;74;109;48;2;57;80;115m▀▀\033[48;2;71;84;101m▀\033[48;2;81;90;96m▀\033[48;2;77;92;98m▀\033[48;2;71;84;101m▀\033[48;2;50;74;109m \033[38;2;77;92;98;48;2;165;133;107m▀\033[38;2;66;82;106;48;2;193;162;137m▀\033[48;2;50;74;109m \033[38;2;81;90;96;48;2;123;118;114m▀\033[38;2;71;84;101;48;2;183;147;124m▀\033[48;2;50;74;109m \033[48;2;57;80;115m   \033[48;2;50;74;109m  \033[48;2;57;80;115m \033[48;2;50;74;109m   \033[48;2;71;84;101m \033[38;2;81;90;96;48;2;50;74;109m▀\033[38;2;66;82;106m▀\033[38;2;57;80;115m▀\033[38;2;29;35;44m▀\033[38;2;0;0;0;48;2;29;35;44m▀\033[0m
\033[38;2;108;96;74;48;2;57;80;115m▀\033[38;2;81;90;96m▀▀\033[38;2;193;162;137;48;2;214;181;147m▀\033[38;2;108;96;74;48;2;123;118;114m▀\033[38;2;81;90;96;48;2;50;74;109m▀\033[38;2;77;92;98m▀\033[38;2;66;82;106m▀\033[38;2;57;80;115m▀ \033[48;2;123;118;114m \033[48;2;244;211;169m \033[38;2;123;118;114;48;2;251;222;179m▀\033[38;2;66;82;106;48;2;123;118;114m▀\033[38;2;244;211;169;48;2;193;162;137m▀\033[38;2;183;147;124;48;2;251;222;179m▀\033[38;2;50;74;109;48;2;123;118;114m▀\033[38;2;57;80;115;48;2;50;74;109m▀\033[48;2;57;80;115m \033[48;2;50;74;109m  \033[48;2;57;80;115m \033[48;2;50;74;109m       \033[38;2;57;80;115;48;2;62;68;76m▀\033[38;2;50;74;109;48;2;57;80;115m▀\033[0m
\033[48;2;57;80;115m \033[48;2;50;74;109m \033[48;2;57;80;115m \033[38;2;213;174;140;48;2;244;211;169m▀\033[38;2;214;181;147;48;2;251;222;179m▀\033[38;2;77;92;98;48;2;193;162;137m▀\033[38;2;123;118;114;48;2;165;133;107m▀\033[38;2;71;84;101;48;2;214;181;147m▀\033[48;2;50;74;109m  \033[38;2;77;92;98;48;2;71;84;101m▀\033[38;2;244;211;169;48;2;214;181;147m▀\033[38;2;214;181;147;48;2;108;96;74m▀\033[38;2;81;90;96;48;2;123;118;114m▀\033[48;2;0;0;0m \033[38;2;62;44;34m▀\033[38;2;108;96;74m▀\033[38;2;50;74;109;48;2;62;68;76m▀\033[38;2;57;80;115;48;2;50;74;109m▀  ▀ \033[38;2;50;74;109;48;2;57;80;115m▀\033[48;2;50;74;109m    \033[48;2;57;80;115m \033[38;2;29;35;44;48;2;50;74;109m▀\033[48;2;0;0;0m▀\033[0m
\033[48;2;57;80;115m \033[38;2;50;74;109m▀\033[38;2;57;80;115;48;2;50;74;109m▀\033[38;2;193;162;137;48;2;29;35;44m▀\033[38;2;123;118;114;48;2;165;133;107m▀\033[38;2;108;96;74;48;2;29;35;44m▀\033[48;2;0;0;0m▀\033[38;2;244;211;169;48;2;165;133;107m▀\033[38;2;193;162;137;48;2;251;222;179m▀\033[38;2;50;74;109;48;2;193;162;137m▀\033[48;2;50;74;109m \033[38;2;183;147;124;48;2;81;90;96m▀\033[48;2;251;222;179m \033[48;2;226;194;158m \033[38;2;0;0;0;48;2;29;35;44m▀\033[48;2;0;0;0m \033[38;2;89;85;55;48;2;165;133;107m▀\033[38;2;165;133;107;48;2;193;162;137m▀\033[48;2;50;74;109m  \033[38;2;50;74;109;48;2;77;92;98m▀\033[48;2;29;35;44m▀\033[38;2;29;35;44;48;2;0;0;0m▀\033[38;2;50;74;109;48;2;62;68;76m▀\033[48;2;57;80;115m▀\033[48;2;50;74;109m    \033[48;2;57;80;115m \033[48;2;29;35;44m \033[0m
\033[38;2;50;74;109;48;2;57;80;115m▀ \033[48;2;50;74;109m \033[48;2;123;118;114m \033[48;2;251;222;179m \033[38;2;29;35;44;48;2;108;96;74m▀\033[48;2;0;0;0m \033[38;2;193;162;137;48;2;165;133;107m▀\033[48;2;251;222;179m \033[38;2;251;222;179;48;2;244;211;169m▀\033[38;2;226;194;158;48;2;251;222;179m▀\033[38;2;123;118;114;48;2;244;211;169m▀\033[38;2;226;194;158m▀\033[48;2;251;222;179m \033[38;2;108;96;74;48;2;214;181;147m▀\033[38;2;0;0;0;48;2;123;118;114m▀\033[38;2;62;44;34;48;2;204;173;131m▀\033[38;2;183;147;124;48;2;77;92;98m▀\033[48;2;50;74;109m \033[38;2;50;74;109;48;2;77;92;98m▀\033[38;2;214;181;147;48;2;193;162;137m▀\033[38;2;62;44;34;48;2;29;35;44m▀\033[48;2;0;0;0m \033[48;2;29;35;44m \033[48;2;57;80;115m \033[48;2;50;74;109m    \033[48;2;57;80;115m \033[38;2;62;68;76;48;2;50;74;109m▀\033[0m
\033[38;2;62;68;76;48;2;0;0;0m▀\033[38;2;57;80;115;48;2;62;68;76m▀\033[38;2;50;74;109;48;2;57;80;115m▀\033[38;2;66;82;106;48;2;50;74;109m▀\033[38;2;214;181;147;48;2;123;118;114m▀\033[38;2;244;211;169;48;2;226;194;158m▀\033[38;2;29;35;44;48;2;214;181;147m▀\033[38;2;108;96;74;48;2;251;222;179m▀\033[38;2;251;222;179;48;2;244;211;169m▀     ▀▀\033[48;2;193;162;137m \033[38;2;81;90;96m▀\033[38;2;77;92;98;48;2;123;118;114m▀\033[38;2;66;82;106;48;2;29;35;44m▀\033[38;2;29;35;44;48;2;0;0;0m▀\033[38;2;0;0;0;48;2;29;35;44m▀\033[48;2;0;0;0m \033[48;2;62;68;76m \033[38;2;57;80;115m▀\033[38;2;50;74;109m▀\033[48;2;57;80;115m▀\033[48;2;50;74;109m  \033[48;2;57;80;115m \033[48;2;50;74;109m \033[0m
\033[48;2;0;0;0m  \033[38;2;50;74;109m▀\033[38;2;57;80;115;48;2;62;68;76m▀\033[38;2;165;133;107;48;2;77;92;98m▀\033[38;2;226;194;158;48;2;244;211;169m▀\033[48;2;251;222;179m▀\033[38;2;244;211;169m▀▀▀▀▀▀▀▀\033[38;2;226;194;158m▀\033[38;2;251;222;179;48;2;244;211;169m▀\033[38;2;165;133;107;48;2;29;35;44m▀ \033[38;2;29;35;44;48;2;0;0;0m▀\033[38;2;165;133;107;48;2;214;181;147m▀\033[38;2;108;96;74;48;2;123;118;114m▀\033[38;2;0;0;0;48;2;29;35;44m▀\033[38;2;66;82;106m▀\033[48;2;0;0;0m \033[48;2;62;68;76m \033[48;2;57;80;115m \033[48;2;50;74;109m  \033[48;2;57;80;115m \033[38;2;50;74;109;48;2;29;35;44m▀\033[0m
\033[48;2;0;0;0m    \033[38;2;62;68;76;48;2;62;44;34m▀\033[38;2;123;118;114m▀\033[38;2;251;222;179;48;2;89;85;55m▀\033[48;2;226;194;158m▀\033[48;2;244;211;169m▀▀     \033[38;2;244;211;169;48;2;214;181;147m▀\033[38;2;226;194;158;48;2;244;211;169m▀\033[38;2;29;35;44;48;2;108;96;74m▀\033[48;2;0;0;0m  \033[48;2;193;162;137m \033[38;2;123;118;114;48;2;165;133;107m▀\033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;29;35;44m▀\033[38;2;62;68;76;48;2;89;85;55m▀\033[38;2;66;82;106m▀\033[38;2;50;74;109m▀\033[38;2;66;82;106m▀\033[38;2;62;68;76;48;2;62;44;34m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;29;35;44;48;2;89;85;55m▀\033[38;2;89;85;55;48;2;251;222;179m▀\033[38;2;226;194;158m▀▀\033[38;2;244;211;169;48;2;226;126;105m▀\033[38;2;251;222;179;48;2;213;174;140m▀\033[48;2;214;181;147m▀▀\033[38;2;244;211;169;48;2;226;194;158m▀\033[38;2;226;194;158;48;2;244;211;169m▀\033[48;2;251;222;179m▀\033[38;2;251;222;179;48;2;244;211;169m▀\033[38;2;226;194;158m▀\033[38;2;165;133;107;48;2;251;222;179m▀▀\033[38;2;226;194;158;48;2;244;211;169m▀\033[48;2;251;222;179m▀\033[38;2;165;133;107m▀\033[38;2;123;118;114m▀\033[38;2;89;85;55;48;2;165;133;107m▀\033[48;2;89;85;55m \033[38;2;62;44;34;48;2;165;133;107m▀\033[38;2;89;85;55;48;2;62;44;34m▀\033[38;2;29;35;44;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;193;162;137;48;2;214;181;147m▀\033[48;2;251;222;179m \033[38;2;244;211;169m▀ \033[48;2;226;126;105m \033[48;2;213;174;140m \033[38;2;244;211;169;48;2;251;222;179m▀▀    \033[38;2;251;222;179;48;2;244;211;169m▀ \033[48;2;251;222;179m \033[38;2;251;222;179;48;2;244;211;169m▀▀\033[48;2;251;222;179m   \033[38;2;244;211;169m▀ \033[38;2;204;173;131m▀\033[38;2;0;0;0;48;2;62;44;34m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;193;162;137;48;2;200;159;130m▀\033[48;2;251;222;179m \033[38;2;251;222;179;48;2;244;211;169m▀\033[48;2;251;222;179m \033[48;2;226;126;105m \033[48;2;213;174;140m \033[48;2;251;222;179m       \033[38;2;244;211;169m▀\033[38;2;251;222;179;48;2;244;211;169m▀\033[38;2;244;211;169;48;2;251;222;179m▀   \033[38;2;251;222;179;48;2;244;211;169m▀ \033[38;2;244;211;169;48;2;251;222;179m▀ \033[38;2;108;96;74;48;2;165;133;107m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;123;118;114;48;2;89;85;55m▀\033[48;2;251;222;179m \033[38;2;244;211;169m▀\033[48;2;244;211;169m \033[38;2;226;126;105;48;2;230;61;59m▀▀\033[38;2;244;211;169m▀▀\033[48;2;226;126;105m▀▀\033[48;2;230;61;59m▀▀\033[38;2;213;174;140m▀\033[38;2;226;126;105m▀▀\033[38;2;251;222;179;48;2;213;174;140m▀\033[48;2;251;222;179m   \033[38;2;244;211;169m▀▀▀ \033[38;2;165;133;107;48;2;204;173;131m▀\033[0m
\033[48;2;0;0;0m      \033[38;2;62;44;34m▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀\033[0m
==>29-publicq-violet
\033[48;2;0;0;0m      \033[38;2;0;0;0;48;2;78;40;67m▀\033[48;2;103;49;96m▀\033[38;2;58;33;42;48;2;113;53;105m▀▀▀▀▀▀\033[38;2;78;40;67m▀\033[38;2;126;105;34;48;2;124;76;102m▀\033[38;2;144;125;26;48;2;202;123;71m▀\033[48;2;249;238;3m▀\033[38;2;126;105;34m▀\033[48;2;229;199;25m▀\033[48;2;249;238;3m▀▀\033[38;2;144;125;26m▀\033[38;2;126;105;34m▀▀\033[38;2;144;125;26m▀\033[38;2;126;105;34;48;2;144;125;26m▀\033[0m
\033[48;2;0;0;0m   \033[38;2;0;0;0;48;2;78;40;67m▀\033[38;2;58;33;42;48;2;125;60;114m▀\033[48;2;113;53;105m  \033[48;2;110;54;101m \033[48;2;107;51;100m        \033[38;2;103;49;96;48;2;110;54;101m▀ \033[38;2;144;125;26;48;2;107;51;100m▀\033[38;2;229;199;25;48;2;124;76;102m▀\033[38;2;249;238;3;48;2;202;123;71m▀\033[48;2;249;238;3m   \033[38;2;249;238;3;48;2;229;199;25m▀\033[38;2;229;199;25;48;2;139;93;100m▀\033[38;2;0;0;0;48;2;78;40;67m▀\033[0m
\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;78;40;67m▀\033[38;2;78;40;67;48;2;113;53;105m▀\033[38;2;113;53;105;48;2;103;49;96m▀\033[48;2;107;51;100m \033[48;2;113;53;105m \033[48;2;107;51;100m           \033[48;2;113;53;105m  \033[48;2;103;49;96m \033[38;2;103;49;96;48;2;110;54;101m▀\033[38;2;124;76;102;48;2;107;51;100m▀\033[38;2;131;93;79m▀▀\033[48;2;113;53;105m \033[48;2;107;51;100m \033[38;2;124;76;102m▀\033[38;2;58;33;42m▀\033[0m
\033[38;2;58;33;42;48;2;78;40;67m▀\033[38;2;125;60;114;48;2;113;53;105m▀\033[48;2;103;49;96m \033[48;2;107;51;100m \033[48;2;113;53;105m  \033[48;2;107;51;100m     \033[48;2;110;54;101m \033[48;2;107;51;100m  \033[38;2;110;54;101;48;2;103;49;96m▀\033[48;2;107;51;100m  \033[48;2;113;53;105m  \033[48;2;107;51;100m  \033[48;2;110;54;101m    \033[48;2;107;51;100m  \033[38;2;113;53;105;48;2;112;65;89m▀\033[38;2;86;65;56;48;2;139;93;100m▀\033[38;2;0;0;0;48;2;58;33;42m▀\033[0m
\033[38;2;78;40;67;48;2;86;65;56m▀\033[38;2;113;53;105;48;2;124;76;102m▀\033[48;2;107;51;100m \033[38;2;107;51;100;48;2;112;65;89m▀\033[38;2;113;53;105m▀▀\033[38;2;107;51;100m▀▀▀\033[48;2;107;51;100m \033[48;2;110;54;101m \033[38;2;124;76;102;48;2;215;175;145m▀\033[38;2;103;49;96;48;2;139;93;100m▀\033[38;2;113;53;105;48;2;103;49;96m▀\033[38;2;139;93;100;48;2;175;138;125m▀\033[38;2;107;51;100;48;2;139;93;100m▀\033[48;2;107;51;100m \033[48;2;113;53;105m  \033[48;2;107;51;100m       \033[38;2;112;65;89m▀▀\033[48;2;110;54;101m  \033[38;2;0;0;0;48;2;78;40;67m▀\033[0m
\033[38;2;86;65;56;48;2;78;40;67m▀\033[38;2;131;93;79;48;2;125;60;114m▀\033[48;2;107;51;100m▀\033[38;2;162;127;99;48;2;155;112;121m▀\033[38;2;175;138;125;48;2;222;182;149m▀\033[38;2;112;65;89;48;2;110;54;101m▀\033[38;2;131;93;79m▀\033[38;2;124;76;102;48;2;107;51;100m▀\033[48;2;110;54;101m▀▀\033[38;2;110;54;101;48;2;103;49;96m▀\033[38;2;222;182;149;48;2;203;168;132m▀\033[38;2;230;194;156;48;2;250;224;181m▀\033[38;2;113;53;105;48;2;230;194;156m▀\033[38;2;175;138;125;48;2;155;112;121m▀\033[48;2;242;209;168m \033[38;2;124;76;102;48;2;215;175;145m▀\033[48;2;113;53;105m  \033[48;2;107;51;100m          \033[38;2;110;54;101;48;2;103;49;96m▀\033[48;2;125;60;114m▀\033[48;2;58;33;42m \033[0m
\033[48;2;78;40;67m \033[48;2;125;60;114m \033[48;2;107;51;100m \033[48;2;155;112;121m \033[38;2;242;209;168;48;2;250;224;181m▀\033[38;2;175;138;125m▀\033[38;2;124;76;102;48;2;155;112;121m▀\033[38;2;155;112;121;48;2;222;182;149m▀\033[38;2;103;49;96;48;2;155;112;121m▀\033[38;2;110;54;101;48;2;103;49;96m▀ \033[38;2;203;149;129;48;2;175;138;125m▀\033[38;2;242;209;168m▀\033[38;2;162;127;99;48;2;131;93;79m▀\033[38;2;78;40;67;48;2;86;65;56m▀\033[38;2;58;33;42;48;2;0;0;0m▀\033[38;2;131;93;79;48;2;58;33;42m▀\033[38;2;113;53;105;48;2;78;40;67m▀\033[48;2;103;49;96m▀\033[48;2;107;51;100m         \033[48;2;113;53;105m \033[38;2;78;40;67m▀\033[48;2;58;33;42m \033[38;2;58;33;42;48;2;0;0;0m▀\033[0m
\033[48;2;78;40;67m \033[48;2;125;60;114m \033[38;2;107;51;100m▀\033[38;2;155;112;121;48;2;78;40;67m▀\033[38;2;175;138;125;48;2;131;93;79m▀\033[38;2;131;93;79;48;2;139;93;100m▀\033[38;2;112;65;89;48;2;0;0;0m▀\033[38;2;175;138;125;48;2;86;65;56m▀\033[38;2;250;224;181;48;2;242;209;168m▀\033[38;2;139;93;100;48;2;250;224;181m▀\033[38;2;103;49;96;48;2;155;112;121m▀\033[38;2;124;76;102;48;2;103;49;96m▀\033[38;2;230;194;156;48;2;203;168;132m▀\033[48;2;250;224;181m \033[38;2;86;65;56;48;2;139;93;100m▀\033[48;2;0;0;0m \033[38;2;58;33;42;48;2;86;65;56m▀\033[38;2;155;112;121;48;2;215;175;145m▀\033[38;2;124;76;102;48;2;139;93;100m▀\033[48;2;107;51;100m \033[38;2;103;49;96;48;2;124;76;102m▀\033[38;2;113;53;105m▀\033[38;2;58;33;42;48;2;0;0;0m▀\033[38;2;78;40;67;48;2;58;33;42m▀\033[48;2;113;53;105m \033[48;2;107;51;100m    \033[48;2;113;53;105m \033[38;2;78;40;67;48;2;103;49;96m▀\033[0m
\033[48;2;78;40;67m \033[48;2;125;60;114m \033[38;2;125;60;114;48;2;113;53;105m▀\033[38;2;78;40;67;48;2;107;51;100m▀\033[38;2;230;194;156;48;2;215;175;145m▀\033[38;2;175;138;125;48;2;250;224;181m▀\033[48;2;0;0;0m \033[48;2;58;33;42m \033[38;2;242;209;168;48;2;222;182;149m▀\033[48;2;250;224;181m  \033[38;2;175;138;125m▀\033[38;2;155;112;121;48;2;230;194;156m▀\033[38;2;250;224;181;48;2;242;209;168m▀\033[38;2;222;182;149;48;2;250;224;181m▀\033[38;2;58;33;42;48;2;175;138;125m▀\033[38;2;0;0;0m▀\033[38;2;175;138;125;48;2;203;149;129m▀\033[38;2;124;76;102;48;2;103;49;96m▀ \033[38;2;155;112;121;48;2;203;149;129m▀\033[38;2;175;138;125;48;2;86;65;56m▀\033[48;2;0;0;0m  \033[48;2;103;49;96m \033[48;2;110;54;101m \033[48;2;107;51;100m   \033[48;2;110;54;101m \033[38;2;103;49;96;48;2;113;53;105m▀\033[38;2;0;0;0;48;2;58;33;42m▀\033[0m
\033[38;2;58;33;42;48;2;0;0;0m▀\033[38;2;125;60;114;48;2;58;33;42m▀\033[38;2;113;53;105;48;2;125;60;114m▀\033[48;2;113;53;105m \033[38;2;139;93;100;48;2;110;54;101m▀\033[38;2;250;224;181;48;2;215;175;145m▀\033[38;2;155;112;121;48;2;230;194;156m▀\033[38;2;58;33;42m▀\033[38;2;222;182;149;48;2;250;224;181m▀ \033[38;2;242;209;168m▀▀▀▀ ▀\033[48;2;230;194;156m \033[38;2;155;112;121;48;2;203;149;129m▀\033[38;2;124;76;102;48;2;175;138;125m▀\033[48;2;110;54;101m▀\033[38;2;86;65;56;48;2;0;0;0m▀  \033[38;2;0;0;0;48;2;58;33;42m▀\033[48;2;103;49;96m \033[38;2;107;51;100;48;2;58;33;42m▀\033[48;2;107;51;100m   \033[48;2;103;49;96m \033[48;2;125;60;114m \033[48;2;58;33;42m \033[0m
\033[48;2;0;0;0m  \033[38;2;58;33;42m▀\033[38;2;125;60;114;48;2;58;33;42m▀\033[38;2;113;53;105;48;2;103;49;96m▀\033[38;2;215;175;145;48;2;175;138;125m▀\033[38;2;242;209;168;48;2;250;224;181m▀\033[48;2;242;209;168m         \033[38;2;242;209;168;48;2;250;224;181m▀\033[38;2;230;194;156;48;2;147;116;97m▀\033[38;2;58;33;42;48;2;0;0;0m▀\033[48;2;58;33;42m \033[38;2;58;33;42;48;2;86;65;56m▀\033[38;2;230;194;156;48;2;242;209;168m▀\033[38;2;0;0;0;48;2;58;33;42m▀\033[38;2;78;40;67m▀\033[38;2;58;33;42;48;2;0;0;0m▀\033[38;2;0;0;0;48;2;58;33;42m▀\033[48;2;107;51;100m \033[48;2;110;54;101m \033[48;2;107;51;100m  \033[38;2;113;53;105;48;2;103;49;96m▀\033[38;2;58;33;42;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m    \033[38;2;58;33;42m▀\033[38;2;112;65;89;48;2;86;65;56m▀\033[38;2;215;175;145;48;2;58;33;42m▀\033[38;2;250;224;181;48;2;175;138;125m▀\033[48;2;250;224;181m   \033[38;2;230;194;156m▀\033[38;2;242;209;168m▀ ▀\033[38;2;250;224;181;48;2;242;209;168m▀ \033[38;2;139;93;100;48;2;203;168;132m▀\033[48;2;0;0;0m  \033[38;2;86;65;56;48;2;58;33;42m▀\033[48;2;242;209;168m \033[38;2;58;33;42;48;2;0;0;0m▀  \033[48;2;86;65;56m▀\033[38;2;125;60;114;48;2;131;93;79m▀\033[38;2;110;54;101m▀▀\033[48;2;86;65;56m▀\033[38;2;58;33;42;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m      \033[38;2;58;33;42;48;2;203;168;132m▀\033[38;2;175;138;125;48;2;250;224;181m▀\033[38;2;242;209;168m▀\033[48;2;230;194;156m \033[38;2;230;194;156;48;2;223;113;101m▀\033[38;2;242;209;168;48;2;230;194;156m▀▀▀\033[38;2;230;194;156;48;2;242;209;168m▀▀\033[48;2;250;224;181m  \033[38;2;175;138;125;48;2;242;209;168m▀\033[38;2;155;112;121;48;2;250;224;181m▀\033[38;2;203;168;132;48;2;242;209;168m▀ \033[38;2;175;138;125;48;2;250;224;181m▀\033[38;2;162;127;99m▀\033[38;2;86;65;56;48;2;203;168;132m▀\033[48;2;131;93;79m \033[38;2;131;93;79;48;2;162;127;99m▀\033[48;2;147;116;97m▀\033[38;2;86;65;56;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;58;33;42;48;2;86;65;56m▀\033[48;2;250;224;181m   \033[48;2;230;194;156m \033[48;2;223;113;101m \033[38;2;242;209;168;48;2;250;224;181m▀\033[38;2;230;194;156m▀\033[38;2;242;209;168m▀▀▀  \033[38;2;230;194;156;48;2;242;209;168m▀  \033[38;2;242;209;168;48;2;230;194;156m▀\033[48;2;242;209;168m  \033[38;2;250;224;181m▀\033[38;2;242;209;168;48;2;250;224;181m▀\033[38;2;250;224;181;48;2;242;209;168m▀\033[48;2;250;224;181m \033[38;2;58;33;42;48;2;162;127;99m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;86;65;56;48;2;58;33;42m▀\033[48;2;250;224;181m   \033[48;2;230;194;156m \033[38;2;223;113;101;48;2;210;88;77m▀\033[48;2;250;224;181m        \033[48;2;242;209;168m \033[48;2;250;224;181m \033[38;2;230;194;156m▀\033[38;2;242;209;168m▀▀▀\033[38;2;250;224;181;48;2;242;209;168m▀\033[38;2;230;194;156m▀\033[48;2;250;224;181m \033[38;2;215;175;145;48;2;230;194;156m▀\033[0m
\033[48;2;0;0;0m      \033[38;2;230;194;156;48;2;175;138;125m▀\033[48;2;250;224;181m  \033[38;2;230;194;156;48;2;223;113;101m▀\033[48;2;232;59;57m \033[38;2;215;175;145m▀\033[38;2;250;224;181m▀\033[48;2;223;113;101m▀\033[48;2;203;149;129m▀\033[48;2;223;113;101m▀\033[48;2;232;59;57m▀\033[38;2;222;182;149m▀\033[38;2;223;113;101m▀\033[38;2;210;88;77m▀\033[38;2;230;194;156;48;2;223;113;101m▀\033[48;2;250;224;181m    \033[38;2;230;194;156m▀\033[38;2;242;209;168m▀ ▀\033[48;2;58;33;42m \033[0m
\033[48;2;0;0;0m      \033[38;2;58;33;42m▀\033[38;2;86;65;56m▀▀\033[38;2;58;33;42m▀▀▀▀▀▀▀▀▀▀▀▀▀\033[38;2;86;65;56m▀▀▀▀▀▀▀\033[0m
==>30-chibisq
\033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;181;19;114m▀\033[38;2;125;14;71;48;2;161;60;160m▀\033[48;2;170;31;142m▀\033[38;2;0;0;0;48;2;125;14;71m▀▀▀▀▀\033[48;2;55;12;41m▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;55;12;41m▀\033[48;2;181;19;114m▀▀\033[48;2;55;12;41m▀\033[0m
\033[48;2;0;0;0m \033[38;2;55;12;41m▀\033[38;2;181;19;114;48;2;125;14;71m▀\033[38;2;158;136;155;48;2;224;19;117m▀\033[38;2;181;19;114m▀      ▀\033[38;2;224;19;117;48;2;181;19;114m▀\033[38;2;185;153;156;48;2;187;179;179m▀\033[38;2;224;19;117;48;2;158;136;155m▀\033[48;2;224;19;117m \033[48;2;55;12;41m \033[0m
\033[48;2;0;0;0m \033[38;2;65;22;77;48;2;140;33;143m▀\033[38;2;224;19;117;48;2;170;31;142m▀\033[38;2;181;19;114m▀▀▀▀▀▀▀▀▀▀▀▀\033[48;2;55;12;41m▀\033[0m
\033[38;2;55;12;41;48;2;65;22;77m▀\033[38;2;170;31;142;48;2;144;49;158m▀\033[48;2;143;36;154m▀▀▀▀▀▀\033[48;2;135;46;154m▀\033[38;2;143;36;154;48;2;161;60;160m▀\033[38;2;140;33;143;48;2;158;136;155m▀\033[38;2;120;71;127;48;2;246;223;173m▀\033[38;2;135;46;154;48;2;185;153;156m▀\033[38;2;143;36;154;48;2;140;33;143m▀\033[48;2;143;36;154m \033[38;2;72;38;97;48;2;135;46;154m▀\033[38;2;55;12;41;48;2;125;14;71m▀\033[38;2;125;14;71;48;2;224;19;117m▀\033[38;2;181;19;114m▀▀▀\033[38;2;55;12;41m▀\033[38;2;0;0;0;48;2;125;14;71m▀\033[0m
\033[38;2;65;22;77;48;2;55;12;41m▀\033[38;2;144;49;158;48;2;65;22;77m▀\033[38;2;135;46;154;48;2;104;95;92m▀\033[38;2;158;136;155;48;2;217;196;162m▀\033[48;2;158;136;155m \033[38;2;158;136;155;48;2;140;125;123m▀\033[38;2;185;153;156;48;2;246;223;173m▀▀\033[38;2;158;136;155;48;2;217;204;188m▀\033[48;2;140;125;123m  \033[48;2;246;223;173m \033[48;2;185;153;156m \033[38;2;135;46;154;48;2;111;47;167m▀\033[38;2;143;36;154;48;2;135;46;154m▀ \033[48;2;115;29;121m \033[48;2;224;19;117m      \033[38;2;181;19;114m▀\033[38;2;0;0;0;48;2;55;12;41m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;52;45;49;48;2;55;12;41m▀\033[38;2;217;204;188;48;2;245;236;206m▀\033[38;2;181;152;176;48;2;140;125;123m▀\033[38;2;52;45;49;48;2;55;12;41m▀\033[48;2;217;196;162m \033[38;2;245;236;206;48;2;246;223;173m▀\033[38;2;187;179;179;48;2;217;204;188m▀\033[38;2;0;0;0;48;2;120;71;127m▀\033[38;2;104;95;92;48;2;187;179;179m▀\033[38;2;245;236;206;48;2;217;204;188m▀\033[38;2;140;125;123;48;2;78;40;122m▀\033[38;2;111;47;167;48;2;102;51;180m▀ ▀\033[38;2;78;40;122;48;2;115;29;121m▀\033[48;2;181;19;114m \033[38;2;224;19;117m▀\033[48;2;224;19;117m   \033[38;2;125;14;71;48;2;55;12;41m▀\033[38;2;55;12;41;48;2;0;0;0m▀▀\033[0m
\033[48;2;0;0;0m   \033[38;2;185;153;156;48;2;104;95;92m▀\033[38;2;245;236;206;48;2;246;223;173m▀\033[38;2;217;204;188m▀  \033[38;2;246;223;173;48;2;187;179;179m▀\033[48;2;217;196;162m▀\033[48;2;246;223;173m \033[38;2;217;196;162;48;2;158;136;155m▀\033[38;2;81;55;180;48;2;69;68;208m▀\033[38;2;102;51;180m▀▀\033[38;2;69;68;208;48;2;81;55;180m▀\033[38;2;72;38;97;48;2;125;14;71m▀\033[48;2;181;19;114m     \033[38;2;0;0;0;48;2;55;12;41m▀\033[0m
\033[48;2;0;0;0m   \033[38;2;52;45;49m▀\033[38;2;246;223;173;48;2;104;95;92m▀\033[48;2;217;196;162m▀\033[38;2;217;196;162;48;2;217;204;188m▀\033[38;2;217;204;188;48;2;217;196;162m▀\033[38;2;187;179;179m▀\033[38;2;217;196;162;48;2;246;223;173m▀\033[38;2;181;152;176;48;2;185;153;156m▀\033[38;2;69;68;208;48;2;72;38;97m▀\033[48;2;78;40;122m▀▀\033[38;2;81;55;180;48;2;115;29;121m▀\033[38;2;125;14;71;48;2;170;31;142m▀\033[38;2;170;31;142;48;2;143;36;154m▀▀▀▀▀\033[48;2;170;31;142m \033[38;2;65;22;77;48;2;115;29;121m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;55;12;41m▀\033[38;2;52;45;49m▀\033[38;2;104;95;92;48;2;120;71;127m▀\033[38;2;217;196;162;48;2;104;95;92m▀\033[38;2;217;204;188;48;2;187;179;179m▀\033[38;2;52;45;49;48;2;135;46;154m▀\033[38;2;0;0;0;48;2;120;71;127m▀\033[38;2;170;31;142m▀\033[48;2;143;36;154m▀\033[48;2;115;29;121m▀\033[48;2;72;38;97m▀\033[48;2;143;36;154m▀▀▀▀▀▀\033[38;2;140;33;143;48;2;144;49;158m▀\033[38;2;0;0;0;48;2;55;12;41m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;0;0;0;48;2;52;45;49m▀\033[38;2;104;95;92;48;2;158;136;155m▀\033[38;2;161;60;160;48;2;115;29;121m▀\033[38;2;72;38;97;48;2;55;12;41m▀\033[38;2;140;125;123;48;2;72;38;97m▀\033[38;2;135;46;154;48;2;143;36;154m▀\033[38;2;181;152;176;48;2;161;60;160m▀\033[48;2;187;179;179m▀\033[38;2;115;29;121;48;2;72;38;97m▀\033[38;2;104;95;92;48;2;187;179;179m▀\033[38;2;140;125;123;48;2;185;153;156m▀\033[38;2;115;29;121;48;2;72;38;97m▀\033[38;2;143;36;154;48;2;144;49;158m▀▀▀▀\033[38;2;135;46;154m▀ \033[38;2;65;22;77;48;2;72;38;97m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;104;95;92;48;2;158;136;155m▀\033[38;2;158;136;155;48;2;161;60;160m▀\033[48;2;143;36;154m \033[38;2;55;12;41;48;2;115;29;121m▀\033[38;2;78;40;122;48;2;140;33;143m▀\033[48;2;143;36;154m \033[38;2;144;49;158;48;2;140;33;143m▀\033[38;2;187;179;179;48;2;181;152;176m▀\033[48;2;245;236;206m▀ \033[38;2;158;136;155;48;2;140;125;123m▀\033[38;2;115;29;121;48;2;140;33;143m▀\033[38;2;143;36;154;48;2;144;49;158m▀\033[38;2;135;46;154;48;2;143;36;154m▀\033[38;2;140;33;143m▀▀▀\033[38;2;143;36;154;48;2;144;49;158m▀\033[48;2;115;29;121m \033[0m
\033[48;2;0;0;0m    \033[38;2;52;45;49;48;2;104;95;92m▀\033[38;2;187;179;179;48;2;181;152;176m▀\033[38;2;135;46;154;48;2;140;33;143m▀\033[38;2;143;36;154;48;2;144;49;158m▀\033[38;2;115;29;121;48;2;143;36;154m▀ \033[38;2;135;46;154m▀\033[38;2;140;33;143m▀\033[38;2;181;152;176;48;2;135;46;154m▀\033[38;2;245;236;206;48;2;187;179;179m▀\033[38;2;187;179;179;48;2;120;71;127m▀\033[38;2;115;29;121;48;2;111;47;167m▀\033[38;2;144;49;158m▀\033[38;2;135;46;154m▀\033[48;2;135;46;154m      \033[0m
\033[48;2;0;0;0m   \033[38;2;0;0;0;48;2;52;45;49m▀\033[38;2;187;179;179;48;2;217;204;188m▀\033[38;2;158;136;155;48;2;120;71;127m▀\033[38;2;143;36;154;48;2;140;33;143m▀\033[48;2;144;49;158m \033[38;2;143;36;154;48;2;135;46;154m▀▀\033[48;2;144;49;158m▀\033[38;2;144;49;158;48;2;135;46;154m▀\033[38;2;140;33;143;48;2;72;38;97m▀\033[38;2;78;40;122;48;2;111;47;167m▀\033[48;2;102;51;180m         \033[48;2;111;47;167m \033[0m
\033[48;2;0;0;0m   \033[38;2;52;45;49;48;2;104;95;92m▀\033[38;2;217;204;188;48;2;245;236;206m▀\033[48;2;52;45;49m \033[48;2;0;0;0m \033[38;2;115;29;121m▀\033[38;2;65;22;77m▀\033[38;2;72;38;97m▀\033[38;2;115;29;121m▀\033[38;2;55;12;41m▀ \033[38;2;81;55;180;48;2;72;38;97m▀\033[48;2;69;68;208m▀\033[38;2;102;51;180m▀▀▀\033[48;2;81;55;180m▀▀▀\033[48;2;102;51;180m \033[38;2;102;51;180;48;2;81;55;180m▀\033[38;2;72;38;97;48;2;55;12;41m▀\033[0m
\033[48;2;0;0;0m   \033[38;2;185;153;156;48;2;55;12;41m▀\033[38;2;217;196;162;48;2;0;0;0m▀        \033[38;2;78;40;122;48;2;65;22;77m▀\033[48;2;69;68;208m      \033[38;2;69;68;208;48;2;81;55;180m▀\033[48;2;65;22;77m▀\033[38;2;72;38;97;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m               \033[38;2;65;22;77m▀\033[38;2;72;38;97m▀\033[38;2;65;22;77m▀\033[38;2;72;38;97m▀\033[38;2;65;22;77m▀\033[0m
==>31-chibisq-cyan
\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;14;49;8m▀\033[38;2;14;49;8;48;2;25;219;17m▀\033[38;2;14;89;11;48;2;124;167;90m▀\033[48;2;62;183;27m▀\033[38;2;0;0;0;48;2;19;150;13m▀\033[48;2;14;89;11m▀▀▀▀▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;14;49;8m▀\033[48;2;25;219;17m▀▀\033[48;2;14;89;11m▀\033[0m
\033[48;2;0;0;0m \033[38;2;14;49;8m▀\033[38;2;25;219;17;48;2;19;150;13m▀\033[38;2;131;124;111;48;2;25;219;17m▀\033[38;2;95;171;30m▀       \033[38;2;25;219;17;48;2;62;183;27m▀\033[38;2;124;167;90;48;2;214;203;174m▀\033[38;2;95;171;30;48;2;165;177;116m▀\033[48;2;25;219;17m \033[38;2;14;49;8;48;2;14;89;11m▀\033[0m
\033[38;2;0;0;0;48;2;14;49;8m▀\033[38;2;14;89;11;48;2;62;183;27m▀\033[38;2;62;183;27;48;2;95;171;30m▀\033[48;2;62;183;27m      \033[38;2;62;183;27;48;2;95;171;30m▀▀\033[48;2;62;183;27m   \033[38;2;62;183;27;48;2;95;171;30m▀\033[38;2;19;150;13;48;2;41;46;26m▀\033[38;2;14;49;8;48;2;0;0;0m▀\033[0m
\033[48;2;41;46;26m \033[38;2;109;153;37;48;2;117;163;36m▀\033[48;2;109;153;37m    \033[38;2;109;153;37;48;2;117;163;36m▀▀\033[48;2;127;166;52m▀\033[48;2;124;167;90m▀▀\033[38;2;101;132;46;48;2;214;203;174m▀\033[48;2;165;177;116m▀\033[48;2;109;153;37m \033[38;2;109;153;37;48;2;129;166;41m▀\033[38;2;71;87;48;48;2;109;153;37m▀\033[38;2;14;49;8;48;2;19;150;13m▀\033[38;2;19;150;13;48;2;25;219;17m▀▀▀▀\033[38;2;14;89;11m▀\033[38;2;0;0;0;48;2;19;150;13m▀\033[0m
\033[38;2;71;87;48;48;2;41;46;26m▀\033[38;2;146;174;42;48;2;71;87;48m▀\033[38;2;109;153;37;48;2;131;124;111m▀\033[38;2;165;177;116;48;2;214;203;174m▀\033[48;2;180;177;143m▀\033[48;2;131;124;111m▀\033[38;2;180;177;143;48;2;242;234;200m▀\033[38;2;165;177;116m▀\033[38;2;131;124;111;48;2;214;203;174m▀\033[48;2;131;124;111m  \033[48;2;242;234;200m \033[48;2;180;177;143m \033[38;2;109;153;37;48;2;129;166;41m▀\033[38;2;117;163;36;48;2;127;166;52m▀\033[38;2;129;166;41;48;2;146;174;42m▀\033[38;2;19;150;13;48;2;101;132;46m▀\033[48;2;25;219;17m      \033[38;2;19;150;13m▀\033[38;2;0;0;0;48;2;14;89;11m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;71;87;48;48;2;26;26;31m▀\033[38;2;214;203;174;48;2;242;234;200m▀\033[38;2;180;177;143;48;2;131;124;111m▀\033[48;2;41;46;26m \033[38;2;180;177;143;48;2;214;203;174m▀\033[48;2;242;234;200m \033[48;2;214;203;174m \033[38;2;0;0;0;48;2;101;132;46m▀\033[38;2;131;124;111;48;2;180;177;143m▀\033[38;2;242;234;200;48;2;214;203;174m▀\033[38;2;131;124;111;48;2;46;46;105m▀\033[38;2;84;55;139;48;2;102;50;176m▀  \033[38;2;71;87;48;48;2;84;55;139m▀\033[38;2;25;219;17;48;2;62;183;27m▀\033[48;2;25;219;17m    \033[38;2;19;150;13;48;2;14;49;8m▀\033[48;2;0;0;0m \033[38;2;14;49;8m▀\033[0m
\033[48;2;0;0;0m   \033[38;2;180;177;143;48;2;71;87;48m▀\033[48;2;242;234;200m \033[38;2;214;203;174m▀ \033[38;2;242;234;200;48;2;214;203;174m▀▀▀\033[48;2;242;234;200m \033[38;2;214;203;174;48;2;131;124;111m▀\033[38;2;102;50;176;48;2;85;58;199m▀   \033[38;2;46;46;105;48;2;71;87;48m▀\033[48;2;62;183;27m    \033[38;2;25;219;17m▀\033[48;2;14;49;8m \033[0m
\033[48;2;0;0;0m   \033[38;2;45;44;36m▀\033[38;2;242;234;200;48;2;131;124;111m▀\033[48;2;214;203;174m▀  \033[38;2;180;177;143m▀\033[38;2;214;203;174;48;2;242;234;200m▀\033[48;2;180;177;143m \033[38;2;85;58;199;48;2;46;46;105m▀\033[48;2;71;87;48m▀▀\033[48;2;101;132;46m▀\033[38;2;71;87;48;48;2;109;153;37m▀\033[38;2;95;171;30m▀▀▀\033[38;2;62;183;27m▀\033[48;2;95;171;30m▀▀\033[38;2;14;89;11;48;2;101;132;46m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;26;26;31m▀\033[38;2;45;44;36m▀\033[38;2;71;87;48;48;2;101;132;46m▀\033[38;2;214;203;174;48;2;131;124;111m▀\033[38;2;242;234;200;48;2;180;177;143m▀\033[38;2;45;44;36;48;2;109;153;37m▀\033[38;2;0;0;0;48;2;131;124;111m▀\033[38;2;117;163;36;48;2;101;132;46m▀\033[48;2;117;163;36m \033[38;2;129;166;41;48;2;101;132;46m▀\033[38;2;117;163;36m▀\033[48;2;109;153;37m▀▀▀▀▀▀\033[38;2;109;153;37;48;2;117;163;36m▀\033[38;2;0;0;0;48;2;14;49;8m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;0;0;0;48;2;45;44;36m▀\033[38;2;131;124;111;48;2;165;177;116m▀\033[38;2;124;167;90;48;2;109;153;37m▀\033[38;2;71;87;48;48;2;26;26;31m▀\033[38;2;131;124;111;48;2;71;87;48m▀\033[48;2;117;163;36m \033[38;2;180;177;143;48;2;124;167;90m▀\033[38;2;165;177;116;48;2;180;177;143m▀\033[48;2;71;87;48m \033[38;2;101;132;46;48;2;214;203;174m▀\033[38;2;131;124;111;48;2;180;177;143m▀\033[48;2;71;87;48m \033[38;2;109;153;37;48;2;117;163;36m▀▀▀▀▀ \033[38;2;14;89;11;48;2;71;87;48m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;131;124;111;48;2;180;177;143m▀\033[38;2;165;177;116;48;2;153;174;65m▀\033[38;2;117;163;36;48;2;129;166;41m▀\033[38;2;41;46;26;48;2;101;132;46m▀\033[38;2;101;132;46;48;2;109;153;37m▀\033[48;2;146;174;42m \033[38;2;127;166;52;48;2;129;166;41m▀\033[48;2;180;177;143m \033[38;2;180;177;143;48;2;242;234;200m▀ \033[38;2;131;124;111;48;2;124;167;90m▀\033[38;2;101;132;46;48;2;109;153;37m▀\033[38;2;117;163;36;48;2;129;166;41m▀\033[38;2;109;153;37m▀▀▀▀\033[38;2;117;163;36m▀\033[48;2;101;132;46m \033[0m
\033[48;2;0;0;0m    \033[38;2;45;44;36;48;2;131;124;111m▀\033[48;2;180;177;143m \033[38;2;117;163;36;48;2;109;153;37m▀\033[48;2;146;174;42m \033[38;2;109;153;37;48;2;117;163;36m▀\033[38;2;129;166;41;48;2;146;174;42m▀ \033[38;2;117;163;36m▀\033[38;2;165;177;116;48;2;124;167;90m▀\033[38;2;242;234;200;48;2;180;177;143m▀\033[38;2;180;177;143;48;2;84;55;139m▀\033[38;2;101;132;46;48;2;102;50;176m▀\033[38;2;146;174;42;48;2;131;124;111m▀▀▀\033[48;2;124;167;90m▀▀\033[38;2;129;166;41;48;2;146;174;42m▀ \033[38;2;109;153;37m▀\033[38;2;0;0;0;48;2;14;49;8m▀\033[0m
\033[48;2;0;0;0m   \033[38;2;0;0;0;48;2;45;44;36m▀\033[38;2;180;177;143;48;2;214;203;174m▀\033[38;2;165;177;116;48;2;127;166;52m▀\033[38;2;146;174;42;48;2;117;163;36m▀\033[48;2;146;174;42m \033[38;2;117;163;36;48;2;127;166;52m▀\033[38;2;129;166;41m▀\033[48;2;146;174;42m▀ \033[38;2;109;153;37;48;2;71;87;48m▀\033[38;2;46;46;105;48;2;84;55;139m▀\033[48;2;102;50;176m         \033[38;2;131;124;111;48;2;84;55;139m▀\033[38;2;14;49;8;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m   \033[38;2;45;44;36;48;2;131;124;111m▀\033[38;2;214;203;174;48;2;242;234;200m▀\033[48;2;45;44;36m \033[48;2;0;0;0m \033[38;2;101;132;46m▀\033[38;2;45;44;36m▀\033[38;2;71;87;48m▀\033[38;2;109;153;37m▀\033[38;2;14;49;8m▀ \033[38;2;85;58;199;48;2;46;46;105m▀\033[48;2;85;58;199m \033[38;2;102;50;176m▀▀▀▀▀▀▀▀\033[38;2;46;46;105;48;2;26;26;31m▀\033[0m
\033[48;2;0;0;0m   \033[38;2;180;177;143;48;2;41;46;26m▀\033[38;2;214;203;174;48;2;26;26;31m▀\033[38;2;26;26;31;48;2;0;0;0m▀       \033[38;2;46;46;105;48;2;36;29;87m▀\033[48;2;85;58;199m       \033[38;2;85;58;199;48;2;36;29;87m▀\033[38;2;46;46;105;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m               \033[38;2;36;29;87m▀\033[38;2;46;46;105m▀\033[38;2;36;29;87m▀▀▀\033[0m
==>32-chibisq-amber
\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;44;22;19m▀\033[38;2;44;22;19;48;2;226;113;18m▀\033[38;2;144;38;37;48;2;211;135;90m▀\033[38;2;83;33;30;48;2;182;86;34m▀\033[38;2;0;0;0;48;2;83;33;30m▀▀\033[48;2;144;38;37m▀▀\033[48;2;83;33;30m▀\033[48;2;44;22;19m▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;83;33;30m▀\033[48;2;182;82;22m▀\033[48;2;226;113;18m▀\033[48;2;83;33;30m▀\033[0m
\033[48;2;0;0;0m \033[38;2;44;22;19m▀\033[38;2;221;113;29;48;2;182;82;22m▀\033[38;2;174;137;138;48;2;226;113;18m▀\033[38;2;182;86;34;48;2;229;117;20m▀ \033[38;2;239;121;21;48;2;226;113;18m▀\033[48;2;229;117;20m▀▀\033[48;2;226;113;18m▀\033[48;2;229;117;20m▀\033[38;2;182;82;22;48;2;239;121;21m▀\033[38;2;221;113;29;48;2;182;86;34m▀\033[38;2;211;135;90;48;2;193;179;170m▀\033[38;2;221;113;29;48;2;193;167;146m▀\033[48;2;226;113;18m \033[38;2;44;22;19;48;2;83;33;30m▀\033[0m
\033[48;2;0;0;0m \033[38;2;83;33;30;48;2;144;38;37m▀\033[38;2;182;82;22;48;2;167;49;39m▀▀▀▀▀▀▀▀\033[48;2;182;86;34m▀\033[48;2;167;49;39m▀▀\033[38;2;182;86;34;48;2;182;82;22m▀\033[48;2;167;49;39m▀\033[38;2;182;82;22;48;2;44;22;19m▀\033[0m
\033[38;2;44;22;19;48;2;83;33;30m▀\033[48;2;167;49;39m \033[48;2;144;38;37m  \033[38;2;167;49;39m▀\033[48;2;147;38;63m▀▀▀\033[38;2;144;38;37m▀\033[48;2;154;45;79m▀\033[48;2;163;102;96m▀\033[38;2;147;38;63;48;2;238;216;175m▀\033[48;2;174;137;138m▀\033[38;2;144;38;37;48;2;147;38;63m▀\033[38;2;167;49;39;48;2;154;43;75m▀\033[38;2;83;33;30;48;2;147;38;63m▀\033[38;2;44;22;19;48;2;167;49;39m▀\033[38;2;144;38;37;48;2;239;121;21m▀\033[38;2;182;86;34;48;2;229;117;20m▀\033[38;2;182;82;22m▀\033[38;2;144;38;37;48;2;239;121;21m▀\033[38;2;44;22;19;48;2;229;117;20m▀\033[38;2;0;0;0;48;2;144;38;37m▀\033[0m
\033[38;2;83;33;30;48;2;44;22;19m▀\033[38;2;154;43;75;48;2;83;33;30m▀\033[38;2;147;38;63;48;2;133;101;96m▀\033[38;2;163;102;96;48;2;193;179;170m▀\033[38;2;140;114;124;48;2;155;139;127m▀\033[48;2;140;114;124m \033[38;2;193;167;146;48;2;244;239;216m▀\033[38;2;174;137;138;48;2;234;222;188m▀\033[38;2;140;114;124;48;2;244;239;216m▀\033[38;2;133;101;96;48;2;155;139;127m▀\033[38;2;155;139;127;48;2;133;101;96m▀\033[48;2;238;216;175m \033[48;2;193;167;146m \033[48;2;154;43;75m   \033[38;2;167;49;39;48;2;144;38;37m▀\033[38;2;229;117;20;48;2;221;113;29m▀\033[48;2;226;113;18m \033[48;2;229;117;20m \033[48;2;226;113;18m  \033[38;2;239;121;21;48;2;229;117;20m▀\033[38;2;144;38;37;48;2;239;121;21m▀\033[38;2;0;0;0;48;2;83;33;30m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;74;56;62;48;2;44;22;19m▀\033[38;2;193;179;170;48;2;244;239;216m▀\033[48;2;140;114;124m▀\033[38;2;74;56;62;48;2;0;0;0m▀\033[48;2;193;167;146m \033[38;2;244;239;216;48;2;234;222;188m▀\033[38;2;193;179;170;48;2;238;216;175m▀\033[38;2;44;22;19;48;2;83;33;30m▀\033[38;2;133;101;96;48;2;193;167;146m▀\033[38;2;244;239;216;48;2;234;222;188m▀\033[38;2;174;137;138;48;2;60;40;114m▀\033[38;2;114;47;157;48;2;87;50;167m▀▀\033[48;2;81;63;201m▀\033[38;2;135;46;105;48;2;114;47;157m▀\033[48;2;182;82;22m \033[38;2;229;117;20m▀▀\033[48;2;229;117;20m \033[38;2;239;121;21m▀\033[38;2;182;82;22;48;2;44;22;19m▀\033[38;2;44;22;19;48;2;0;0;0m▀▀\033[0m
\033[48;2;0;0;0m   \033[38;2;193;167;146;48;2;133;101;96m▀\033[48;2;244;239;216m \033[38;2;193;179;170;48;2;238;216;175m▀  \033[38;2;234;222;188m▀ ▀\033[38;2;238;216;175;48;2;140;114;124m▀\033[38;2;87;50;167;48;2;81;63;201m▀▀▀ \033[38;2;60;40;114;48;2;83;33;30m▀\033[48;2;182;82;22m     \033[48;2;44;22;19m \033[0m
\033[48;2;0;0;0m   \033[38;2;74;56;62m▀\033[38;2;234;222;188;48;2;155;139;127m▀\033[48;2;238;216;175m \033[38;2;193;179;170m▀\033[38;2;234;222;188m▀\033[38;2;193;179;170m▀ \033[38;2;174;137;138;48;2;193;179;170m▀\033[38;2;81;63;201;48;2;60;40;114m▀▀▀\033[48;2;83;33;30m▀\033[38;2;74;56;62;48;2;144;38;37m▀ \033[38;2;167;49;39m▀▀▀▀\033[38;2;182;86;34;48;2;167;49;39m▀\033[48;2;83;33;30m \033[0m
\033[48;2;0;0;0m     \033[38;2;83;33;30m▀\033[38;2;74;56;62m▀\033[38;2;133;101;96;48;2;135;46;105m▀\033[38;2;234;222;188;48;2;140;114;124m▀\033[38;2;238;216;175;48;2;193;179;170m▀\033[38;2;74;56;62;48;2;154;45;79m▀\033[38;2;0;0;0;48;2;74;56;62m▀\033[38;2;144;38;37;48;2;147;38;63m▀▀\033[38;2;167;49;39;48;2;144;38;37m▀▀      \033[38;2;144;38;37;48;2;167;49;39m▀\033[38;2;0;0;0;48;2;44;22;19m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;0;0;0;48;2;44;22;19m▀\033[38;2;133;101;96;48;2;174;137;138m▀\033[38;2;163;102;96;48;2;147;38;63m▀\033[38;2;74;56;62;48;2;44;22;19m▀\033[38;2;155;139;127;48;2;74;56;62m▀\033[48;2;147;38;63m \033[38;2;193;167;146;48;2;163;102;96m▀\033[38;2;174;137;138;48;2;193;179;170m▀\033[38;2;144;38;37;48;2;83;33;30m▀\033[38;2;133;101;96;48;2;193;179;170m▀\033[48;2;193;167;146m▀\033[38;2;144;38;37;48;2;83;33;30m▀\033[38;2;167;49;39;48;2;144;38;37m▀    \033[48;2;167;49;39m \033[48;2;83;33;30m \033[0m
\033[48;2;0;0;0m     \033[38;2;74;56;62;48;2;155;139;127m▀\033[38;2;174;137;138;48;2;163;102;96m▀\033[38;2;147;38;63;48;2;154;43;75m▀\033[38;2;44;22;19;48;2;83;33;30m▀\033[38;2;83;33;30;48;2;147;38;63m▀\033[48;2;154;43;75m \033[38;2;154;45;79;48;2;147;38;63m▀\033[38;2;193;179;170;48;2;174;137;138m▀\033[38;2;174;137;138;48;2;244;239;216m▀ \033[38;2;155;139;127;48;2;163;102;96m▀\033[38;2;144;38;37;48;2;147;38;63m▀\033[38;2;147;38;63;48;2;154;43;75m▀\033[38;2;144;38;37m▀\033[48;2;147;38;63m▀\033[48;2;144;38;37m  \033[38;2;167;49;39;48;2;147;38;63m▀\033[38;2;144;38;37m▀\033[0m
\033[48;2;0;0;0m    \033[38;2;44;22;19;48;2;133;101;96m▀\033[48;2;193;179;170m \033[38;2;154;43;75;48;2;147;38;63m▀\033[48;2;154;43;75m \033[38;2;147;38;63m▀ \033[48;2;154;45;79m \033[48;2;147;38;63m \033[38;2;174;137;138;48;2;163;102;96m▀\033[38;2;244;239;216;48;2;234;222;188m▀\033[38;2;234;222;188;48;2;114;47;157m▀\033[38;2;147;38;63m▀\033[38;2;154;43;75;48;2;135;46;105m▀▀\033[48;2;154;43;75m    \033[48;2;154;45;79m \033[48;2;147;38;63m \033[0m
\033[48;2;0;0;0m   \033[38;2;0;0;0;48;2;44;22;19m▀\033[38;2;193;167;146;48;2;234;222;188m▀\033[38;2;174;137;138;48;2;163;102;96m▀\033[38;2;147;38;63;48;2;154;43;75m▀\033[48;2;154;45;79m \033[48;2;154;43;75m   \033[48;2;154;45;79m \033[38;2;147;38;63;48;2;144;38;37m▀\033[38;2;60;40;114;48;2;87;50;167m▀\033[38;2;87;50;167;48;2;81;63;201m▀▀▀▀▀\033[38;2;114;47;157m▀▀▀▀\033[48;2;87;50;167m▀\033[38;2;44;22;19;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m   \033[48;2;74;56;62m \033[38;2;234;222;188;48;2;238;216;175m▀\033[38;2;74;56;62;48;2;44;22;19m▀\033[38;2;44;22;19;48;2;0;0;0m▀\033[38;2;154;43;75m▀\033[38;2;83;33;30m▀\033[38;2;144;38;37;48;2;44;22;19m▀\033[38;2;154;45;79m▀\033[38;2;83;33;30;48;2;0;0;0m▀ \033[38;2;87;50;167;48;2;60;40;114m▀\033[48;2;81;63;201m \033[38;2;87;50;167m▀▀▀▀▀▀▀ \033[38;2;60;40;114;48;2;44;22;19m▀\033[0m
\033[48;2;0;0;0m   \033[38;2;193;167;146;48;2;74;56;62m▀\033[38;2;238;216;175m▀\033[38;2;44;22;19;48;2;0;0;0m▀       \033[48;2;60;40;114m \033[48;2;81;63;201m       \033[38;2;81;63;201;48;2;60;40;114m▀\033[38;2;60;40;114;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m              \033[38;2;60;40;114m▀\033[38;2;87;50;167m▀▀▀▀\033[38;2;60;40;114m▀\033[38;2;44;22;19m▀\033[0m
==>33-loveweb
\033[48;2;0;0;0m           \033[38;2;0;0;0;48;2;40;50;64m▀\033[38;2;0;11;25;48;2;175;179;186m▀\033[48;2;80;93;106m▀\033[0m
\033[38;2;0;0;0;48;2;0;11;25m▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;10;32;60m▀\033[48;2;34;58;95m▀▀\033[38;2;0;11;25;48;2;107;112;216m▀\033[38;2;34;58;95;48;2;136;135;254m▀\033[38;2;93;100;189m▀\033[48;2;128;128;249m▀\033[48;2;136;135;254m▀\033[38;2;66;78;147m▀\033[38;2;34;58;95;48;2;128;128;249m▀\033[38;2;102;107;113;48;2;66;78;147m▀\033[38;2;166;169;169;48;2;67;79;104m▀\033[38;2;102;107;113;48;2;133;138;142m▀\033[48;2;0;11;25m \033[0m
\033[48;2;102;107;113m \033[38;2;67;79;104;48;2;175;179;186m▀\033[38;2;27;65;119;48;2;133;138;142m▀\033[38;2;107;112;216;48;2;69;73;77m▀\033[38;2;136;135;254;48;2;107;112;216m▀\033[38;2;128;128;249;48;2;136;135;254m▀▀\033[48;2;126;126;242m \033[38;2;126;126;242;48;2;107;112;216m▀\033[38;2;107;112;216;48;2;85;94;146m▀\033[38;2;136;135;254;48;2;66;78;147m▀\033[38;2;125;125;237;48;2;128;128;249m▀\033[38;2;126;126;242;48;2;66;78;147m▀\033[38;2;107;112;216;48;2;128;128;249m▀\033[38;2;0;11;25;48;2;85;94;146m▀\033[0m
\033[38;2;69;73;77;48;2;40;50;64m▀\033[38;2;80;93;106;48;2;69;73;77m▀\033[38;2;205;205;204;48;2;133;138;142m▀\033[38;2;133;138;142;48;2;80;93;106m▀\033[38;2;66;78;147;48;2;93;100;189m▀\033[38;2;107;112;216;48;2;67;79;104m▀\033[38;2;85;94;146;48;2;40;50;64m▀\033[38;2;93;100;189;48;2;66;78;147m▀\033[38;2;40;50;64;48;2;185;187;188m▀\033[38;2;66;78;147;48;2;102;107;113m▀\033[38;2;40;50;64;48;2;67;79;104m▀\033[38;2;67;79;104;48;2;166;169;169m▀\033[38;2;93;100;189;48;2;67;79;104m▀\033[38;2;66;78;147;48;2;27;65;119m▀\033[38;2;136;135;254;48;2;125;125;237m▀\033[38;2;40;50;64;48;2;93;100;189m▀\033[0m
\033[38;2;10;32;60;48;2;40;50;64m▀\033[48;2;69;73;77m \033[38;2;69;73;77;48;2;133;138;142m▀\033[38;2;67;79;104;48;2;80;93;106m▀\033[38;2;107;112;216;48;2;66;78;147m▀\033[38;2;102;107;113;48;2;133;138;142m▀ \033[38;2;40;50;64;48;2;10;32;60m▀\033[38;2;254;254;254;48;2;231;231;231m▀\033[48;2;254;254;254m \033[38;2;185;187;188m▀\033[38;2;254;254;254;48;2;231;231;231m▀\033[38;2;40;50;64;48;2;34;58;95m▀\033[38;2;10;32;60;48;2;67;79;104m▀\033[38;2;66;78;147;48;2;10;32;60m▀\033[38;2;107;112;216;48;2;27;65;119m▀\033[38;2;0;0;0;48;2;0;11;25m▀\033[48;2;0;0;0m             \033[48;2;0;11;25m  \033[0m
\033[38;2;80;93;106;48;2;102;107;113m▀\033[38;2;205;205;204;48;2;175;179;186m▀\033[38;2;102;107;113;48;2;67;79;104m▀\033[48;2;66;78;147m \033[48;2;93;100;189m \033[38;2;80;93;106;48;2;40;50;64m▀\033[38;2;10;32;60m▀\033[48;2;102;107;113m▀\033[38;2;102;107;113;48;2;80;93;106m▀\033[48;2;231;231;231m \033[48;2;254;254;254m \033[38;2;185;187;188;48;2;166;169;169m▀\033[38;2;67;79;104;48;2;0;11;25m▀\033[38;2;175;179;186;48;2;67;79;104m▀\033[38;2;40;50;64;48;2;80;93;106m▀\033[38;2;0;32;72;48;2;66;78;147m▀\033[38;2;34;58;95m▀\033[38;2;0;11;25m▀\033[38;2;0;0;0;48;2;0;11;25m▀▀\033[48;2;0;0;0m        \033[38;2;0;0;0;48;2;0;11;25m▀ \033[38;2;0;11;25;48;2;0;0;0m▀\033[0m
\033[38;2;40;50;64;48;2;66;78;147m▀\033[38;2;66;78;147;48;2;136;135;254m▀\033[48;2;126;126;242m▀\033[48;2;40;50;64m \033[38;2;0;32;72;48;2;69;73;77m▀\033[38;2;10;32;60m▀\033[38;2;0;11;25;48;2;166;169;169m▀\033[38;2;5;52;96;48;2;0;32;72m▀\033[38;2;80;93;106;48;2;102;107;113m▀\033[48;2;254;254;254m  \033[38;2;133;138;142m▀▀ \033[48;2;80;93;106m▀\033[38;2;93;100;189;48;2;107;112;216m▀▀\033[38;2;10;32;60;48;2;0;0;0m▀▀\033[38;2;0;11;25m▀      \033[38;2;0;0;0;48;2;10;32;60m▀\033[38;2;0;11;25m▀\033[38;2;10;32;60;48;2;0;0;0m▀\033[0m
\033[48;2;85;94;146m \033[38;2;136;135;254;48;2;128;128;249m▀\033[48;2;136;135;254m \033[38;2;66;78;147;48;2;93;100;189m▀\033[38;2;205;205;204;48;2;80;93;106m▀\033[48;2;254;254;254m▀ ▀\033[38;2;231;231;231m▀ \033[38;2;80;93;106;48;2;205;205;204m▀\033[38;2;40;50;64m▀\033[38;2;205;205;204;48;2;254;254;254m▀\033[38;2;231;231;231;48;2;102;107;113m▀\033[38;2;34;58;95;48;2;66;78;147m▀\033[38;2;128;128;249;48;2;107;112;216m▀\033[38;2;93;100;189;48;2;67;79;104m▀\033[48;2;0;0;0m       \033[38;2;0;0;0;48;2;0;11;25m▀\033[38;2;0;11;25;48;2;40;50;64m▀\033[38;2;40;50;64;48;2;0;11;25m▀\033[38;2;0;11;25;48;2;0;0;0m▀\033[0m
\033[48;2;85;94;146m \033[38;2;107;112;216;48;2;136;135;254m▀\033[38;2;66;78;147;48;2;27;65;119m▀\033[38;2;136;135;254;48;2;40;50;64m▀\033[38;2;66;78;147;48;2;93;100;189m▀\033[38;2;166;169;169;48;2;34;58;95m▀\033[38;2;254;254;254;48;2;166;169;169m▀\033[48;2;205;205;204m▀▀▀\033[48;2;166;169;169m▀\033[48;2;67;79;104m▀\033[38;2;102;107;113;48;2;10;32;60m▀\033[48;2;0;0;0m \033[48;2;93;100;189m \033[38;2;66;78;147;48;2;27;65;119m▀\033[38;2;0;11;25;48;2;0;0;0m▀     \033[38;2;0;0;0;48;2;0;11;25m▀\033[38;2;0;11;25;48;2;69;73;77m▀\033[38;2;40;50;64;48;2;10;32;60m▀\033[38;2;0;11;25;48;2;0;0;0m▀\033[0m
\033[38;2;10;32;60;48;2;0;0;0m▀\033[38;2;107;112;216;48;2;40;50;64m▀\033[38;2;93;100;189;48;2;107;112;216m▀\033[38;2;0;0;0;48;2;40;50;64m▀\033[48;2;0;11;25m \033[48;2;4;60;110m \033[38;2;0;32;72;48;2;5;52;96m▀\033[38;2;67;79;104;48;2;205;205;204m▀\033[38;2;80;93;106;48;2;133;138;142m▀\033[38;2;10;32;60;48;2;40;50;64m▀\033[38;2;69;73;77;48;2;102;107;113m▀\033[38;2;85;94;146;48;2;80;93;106m▀\033[38;2;0;32;72;48;2;4;60;110m▀\033[38;2;0;11;25;48;2;5;52;96m▀\033[38;2;93;100;189;48;2;34;58;95m▀\033[38;2;0;11;25;48;2;0;0;0m▀ \033[38;2;0;0;0;48;2;10;32;60m▀▀\033[48;2;0;11;25m▀\033[48;2;80;93;106m▀\033[48;2;40;50;64m▀ \033[38;2;40;50;64;48;2;0;0;0m▀\033[0m
\033[38;2;0;0;0;48;2;0;11;25m▀\033[48;2;0;0;0m \033[38;2;34;58;95m▀\033[38;2;67;79;104;48;2;5;52;96m▀\033[38;2;5;52;96;48;2;4;60;110m▀\033[38;2;4;60;110;48;2;0;11;25m▀▀\033[38;2;34;58;95;48;2;5;52;96m▀\033[38;2;102;107;113m▀\033[38;2;69;73;77;48;2;34;58;95m▀\033[38;2;80;93;106;48;2;4;60;110m▀\033[38;2;0;32;72m▀\033[38;2;10;32;60;48;2;0;11;25m▀\033[38;2;5;52;96;48;2;0;0;0m▀\033[38;2;4;60;110;48;2;0;11;25m▀▀\033[38;2;5;52;96m▀\033[38;2;133;138;142;48;2;102;107;113m▀\033[38;2;185;187;188;48;2;69;73;77m▀\033[38;2;40;50;64m▀\033[38;2;170;176;184;48;2;133;138;142m▀\033[38;2;10;32;60;48;2;40;50;64m▀\033[0m
\033[48;2;0;0;0m \033[38;2;133;138;142;48;2;166;169;169m▀\033[38;2;102;107;113;48;2;254;254;254m▀\033[38;2;5;52;96;48;2;166;169;169m▀\033[38;2;4;60;110;48;2;34;58;95m▀\033[38;2;10;32;60;48;2;85;94;146m▀\033[38;2;40;50;64;48;2;166;169;169m▀\033[38;2;5;52;96;48;2;133;138;142m▀\033[38;2;34;58;95;48;2;231;231;231m▀\033[38;2;4;60;110;48;2;34;58;95m▀\033[48;2;69;73;77m▀\033[38;2;27;65;119;48;2;80;93;106m▀\033[38;2;10;32;60;48;2;40;50;64m▀\033[48;2;0;0;0m    \033[38;2;0;0;0;48;2;40;50;64m▀\033[38;2;0;11;25;48;2;69;73;77m▀\033[38;2;102;107;113;48;2;0;11;25m▀\033[38;2;80;93;106;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m \033[38;2;80;93;106;48;2;0;11;25m▀\033[38;2;254;254;254;48;2;205;205;204m▀\033[48;2;254;254;254m \033[38;2;170;176;184m▀\033[48;2;67;79;104m \033[38;2;102;107;113;48;2;0;32;72m▀\033[38;2;175;179;186;48;2;69;73;77m▀\033[38;2;205;205;204;48;2;80;93;106m▀\033[38;2;69;73;77;48;2;205;205;204m▀\033[38;2;166;169;169;48;2;254;254;254m▀\033[38;2;231;231;231m▀\033[48;2;205;205;204m \033[38;2;0;11;25;48;2;10;32;60m▀\033[48;2;4;60;110m▀\033[38;2;0;0;0;48;2;0;11;25m▀\033[48;2;0;0;0m \033[38;2;40;50;64m▀\033[38;2;0;11;25m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;80;93;106m▀\033[38;2;231;231;231;48;2;0;11;25m▀\033[38;2;133;138;142m▀\033[38;2;67;79;104;48;2;5;52;96m▀\033[38;2;5;52;96;48;2;4;60;110m▀\033[38;2;0;11;25;48;2;0;32;72m▀\033[38;2;205;205;204;48;2;40;50;64m▀\033[38;2;254;254;254;48;2;185;187;188m▀\033[48;2;205;205;204m▀\033[48;2;133;138;142m▀\033[38;2;133;138;142;48;2;0;11;25m▀\033[38;2;10;32;60;48;2;4;60;110m▀ \033[38;2;5;52;96;48;2;10;32;60m▀\033[0m
\033[48;2;0;0;0m    \033[38;2;10;32;60m▀\033[38;2;4;60;110;48;2;0;11;25m▀\033[48;2;4;60;110m \033[38;2;4;60;110;48;2;5;52;96m▀\033[38;2;5;52;96;48;2;10;32;60m▀\033[38;2;10;32;60;48;2;4;60;110m▀\033[48;2;5;52;96m▀\033[48;2;0;11;25m▀\033[38;2;0;32;72;48;2;0;0;0m▀\033[38;2;10;32;60m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;10;32;60;48;2;5;52;96m▀\033[48;2;4;60;110m \033[48;2;0;11;25m \033[38;2;0;11;25;48;2;5;52;96m▀\033[48;2;4;60;110m \033[38;2;10;32;60;48;2;0;11;25m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;0;11;25m▀▀ ▀▀\033[0m
==>34-loveweb-teal
\033[48;2;0;0;0m        \033[38;2;0;0;0;48;2;8;15;22m▀▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;39;48;57m▀\033[38;2;8;15;22;48;2;172;170;174m▀\033[48;2;87;98;110m▀\033[0m
\033[38;2;0;0;0;48;2;8;15;22m▀▀▀\033[48;2;39;48;57m▀\033[48;2;82;77;81m▀\033[38;2;8;15;22;48;2;192;120;106m▀\033[38;2;82;77;81;48;2;255;155;129m▀\033[38;2;158;109;102m▀\033[38;2;192;120;106m▀▀\033[38;2;158;109;102m▀\033[38;2;82;77;81;48;2;251;149;123m▀\033[38;2;122;112;114;48;2;158;109;102m▀\033[38;2;166;150;148;48;2;82;77;81m▀\033[38;2;87;98;110;48;2;138;140;145m▀\033[48;2;8;15;22m \033[0m
\033[38;2;87;98;110;48;2;122;112;114m▀\033[38;2;39;65;87;48;2;166;150;148m▀\033[38;2;82;77;81;48;2;122;112;114m▀\033[38;2;218;134;117;48;2;39;65;87m▀\033[38;2;255;155;129;48;2;218;134;117m▀\033[48;2;251;149;123m  \033[38;2;251;149;123;48;2;237;143;121m▀\033[48;2;218;134;117m▀\033[38;2;218;134;117;48;2;127;88;84m▀\033[38;2;255;155;129m▀\033[48;2;237;143;121m \033[38;2;251;149;123;48;2;127;88;84m▀\033[38;2;192;120;106;48;2;255;155;129m▀\033[38;2;0;22;42;48;2;158;109;102m▀\033[0m
\033[38;2;82;77;81;48;2;39;48;57m▀\033[38;2;87;98;110m▀\033[38;2;172;170;174;48;2;122;112;114m▀\033[48;2;87;98;110m \033[38;2;127;88;84;48;2;192;120;106m▀\033[38;2;192;120;106;48;2;127;88;84m▀\033[38;2;158;109;102;48;2;39;48;57m▀\033[48;2;127;88;84m▀\033[38;2;39;48;57;48;2;172;170;174m▀\033[48;2;127;88;84m \033[38;2;39;48;57;48;2;82;77;81m▀\033[38;2;82;77;81;48;2;172;170;174m▀\033[38;2;158;109;102;48;2;82;77;81m▀\033[48;2;127;88;84m▀\033[38;2;255;155;129;48;2;218;134;117m▀\033[38;2;39;48;57;48;2;158;109;102m▀\033[0m
\033[38;2;0;22;42;48;2;39;48;57m▀\033[38;2;82;77;81;48;2;39;65;87m▀\033[38;2;39;65;87;48;2;138;140;145m▀\033[48;2;87;98;110m \033[38;2;192;120;106;48;2;127;88;84m▀\033[38;2;122;112;114;48;2;138;140;145m▀ \033[48;2;39;48;57m \033[48;2;240;219;212m \033[38;2;254;238;230;48;2;248;225;217m▀\033[38;2;172;170;174;48;2;254;238;230m▀\033[38;2;254;238;230;48;2;218;205;202m▀\033[38;2;82;77;81;48;2;9;36;58m▀\033[38;2;0;22;42;48;2;87;98;110m▀\033[38;2;82;77;81;48;2;9;36;58m▀\033[38;2;192;120;106;48;2;82;77;81m▀\033[38;2;8;15;22;48;2;0;22;42m▀\033[48;2;0;0;0m             \033[38;2;0;0;0;48;2;8;15;22m▀ \033[0m
\033[48;2;87;98;110m \033[38;2;172;170;174;48;2;138;140;145m▀\033[38;2;87;98;110;48;2;39;65;87m▀\033[48;2;127;88;84m \033[38;2;192;120;106;48;2;158;109;102m▀\033[38;2;39;65;87;48;2;39;48;57m▀\033[38;2;9;36;58m▀\033[48;2;87;98;110m▀\033[38;2;122;112;114;48;2;82;77;81m▀\033[38;2;254;238;230;48;2;218;205;202m▀\033[48;2;254;238;230m \033[38;2;200;186;182;48;2;166;150;148m▀\033[38;2;87;98;110;48;2;8;15;22m▀\033[38;2;172;170;174;48;2;82;77;81m▀\033[38;2;9;52;85;48;2;87;98;110m▀\033[48;2;127;88;84m▀\033[38;2;39;65;87m▀\033[38;2;8;15;22m▀\033[38;2;0;0;0;48;2;9;36;58m▀\033[48;2;8;15;22m▀\033[48;2;0;0;0m        \033[38;2;0;0;0;48;2;8;15;22m▀ \033[38;2;8;15;22;48;2;0;0;0m▀\033[0m
\033[38;2;39;48;57;48;2;158;109;102m▀\033[38;2;87;98;110;48;2;255;155;129m▀\033[38;2;127;88;84;48;2;251;149;123m▀\033[48;2;39;48;57m \033[38;2;39;48;57;48;2;82;77;81m▀\033[48;2;39;65;87m▀\033[38;2;0;22;42;48;2;138;140;145m▀\033[38;2;39;65;87;48;2;9;52;85m▀\033[38;2;82;77;81;48;2;87;98;110m▀\033[38;2;248;225;217;48;2;254;238;230m▀\033[38;2;254;238;230;48;2;248;225;217m▀\033[38;2;192;120;106m▀\033[38;2;122;112;114;48;2;254;238;230m▀\033[38;2;240;219;212m▀\033[38;2;122;112;114;48;2;87;98;110m▀\033[38;2;192;120;106;48;2;218;134;117m▀\033[38;2;158;109;102;48;2;192;120;106m▀\033[38;2;8;15;22;48;2;0;0;0m▀\033[38;2;0;22;42m▀\033[38;2;8;15;22m▀      \033[38;2;0;0;0;48;2;8;15;22m▀ \033[38;2;8;15;22;48;2;0;0;0m▀\033[0m
\033[48;2;158;109;102m \033[48;2;255;155;129m  \033[38;2;82;77;81;48;2;192;120;106m▀\033[38;2;200;186;182;48;2;87;98;110m▀\033[38;2;218;205;202;48;2;254;238;230m▀\033[38;2;254;238;230;48;2;240;219;212m▀\033[38;2;218;205;202;48;2;254;238;230m▀\033[48;2;248;225;217m▀\033[38;2;254;238;230m▀\033[38;2;87;98;110;48;2;211;191;185m▀\033[38;2;39;65;87m▀\033[38;2;200;184;180;48;2;254;238;230m▀\033[38;2;218;205;202;48;2;87;98;110m▀\033[38;2;82;77;81;48;2;127;88;84m▀\033[38;2;251;149;123;48;2;218;134;117m▀\033[38;2;158;109;102;48;2;82;77;81m▀\033[48;2;0;0;0m       \033[38;2;0;0;0;48;2;8;15;22m▀▀\033[38;2;8;15;22;48;2;0;0;0m▀▀\033[0m
\033[48;2;158;109;102m \033[38;2;237;143;121;48;2;255;160;134m▀\033[38;2;127;88;84;48;2;82;77;81m▀\033[38;2;255;160;134;48;2;39;48;57m▀\033[38;2;127;88;84;48;2;158;109;102m▀\033[38;2;172;170;174;48;2;39;65;87m▀\033[38;2;254;238;230;48;2;166;150;148m▀\033[48;2;211;191;185m▀▀\033[48;2;218;205;202m▀\033[48;2;166;150;148m▀\033[48;2;82;77;81m▀\033[38;2;122;112;114;48;2;9;36;58m▀\033[48;2;0;0;0m \033[38;2;158;109;102;48;2;192;120;106m▀\033[48;2;82;77;81m▀\033[38;2;0;22;42;48;2;0;0;0m▀     \033[38;2;0;0;0;48;2;8;15;22m▀\033[38;2;8;15;22;48;2;39;65;87m▀\033[38;2;39;65;87;48;2;8;15;22m▀\033[38;2;8;15;22;48;2;0;0;0m▀\033[0m
\033[38;2;39;48;57;48;2;8;15;22m▀\033[38;2;192;120;106;48;2;39;48;57m▀\033[48;2;218;134;117m \033[38;2;0;0;0;48;2;39;48;57m▀\033[48;2;8;15;22m \033[48;2;9;52;85m \033[38;2;9;36;58m▀\033[38;2;82;77;81;48;2;200;186;182m▀\033[38;2;87;98;110;48;2;138;140;145m▀\033[38;2;39;48;57;48;2;82;77;81m▀\033[38;2;39;65;87;48;2;122;112;114m▀\033[48;2;82;77;81m▀\033[48;2;9;52;85m \033[38;2;8;15;22m▀\033[38;2;158;109;102;48;2;39;65;87m▀\033[38;2;8;15;22;48;2;0;0;0m▀ \033[38;2;0;0;0;48;2;9;36;58m▀\033[48;2;0;22;42m▀\033[48;2;8;15;22m▀\033[48;2;87;98;110m▀\033[48;2;39;48;57m▀\033[38;2;39;65;87m▀\033[38;2;39;48;57;48;2;0;0;0m▀\033[0m
\033[38;2;0;0;0;48;2;8;15;22m▀\033[48;2;0;0;0m \033[38;2;39;65;87m▀\033[38;2;82;77;81;48;2;9;52;85m▀ \033[38;2;9;52;85;48;2;0;22;42m▀\033[48;2;0;0;0m▀\033[48;2;9;52;85m \033[38;2;87;98;110m▀\033[38;2;82;77;81;48;2;39;65;87m▀\033[48;2;9;52;85m▀\033[38;2;9;36;58m▀\033[48;2;0;22;42m▀\033[38;2;9;52;85;48;2;0;0;0m▀\033[48;2;8;15;22m▀▀▀\033[38;2;138;140;145;48;2;122;112;114m▀\033[38;2;200;184;180;48;2;39;65;87m▀\033[38;2;39;48;57;48;2;82;77;81m▀\033[38;2;172;170;174;48;2;138;140;145m▀\033[48;2;9;36;58m \033[0m
\033[48;2;0;0;0m \033[38;2;122;112;114;48;2;166;150;148m▀\033[48;2;254;238;230m▀\033[38;2;9;36;58;48;2;138;140;145m▀\033[38;2;9;52;85;48;2;39;65;87m▀\033[38;2;9;36;58;48;2;87;98;110m▀\033[38;2;39;48;57;48;2;166;150;148m▀\033[38;2;9;52;85;48;2;138;140;145m▀\033[38;2;39;65;87;48;2;240;219;212m▀\033[48;2;9;52;85m \033[38;2;9;52;85;48;2;39;65;87m▀\033[48;2;82;77;81m▀\033[38;2;0;22;42;48;2;39;48;57m▀\033[48;2;0;0;0m    \033[38;2;0;0;0;48;2;39;48;57m▀\033[38;2;8;15;22;48;2;127;88;84m▀\033[38;2;87;98;110;48;2;8;15;22m▀\033[48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m \033[38;2;82;77;81;48;2;8;15;22m▀\033[38;2;254;238;230;48;2;218;205;202m▀\033[48;2;254;238;230m \033[38;2;172;170;174m▀\033[48;2;87;98;110m \033[38;2;87;98;110;48;2;9;36;58m▀\033[38;2;172;170;174;48;2;82;77;81m▀\033[38;2;211;191;185;48;2;87;98;110m▀\033[38;2;82;77;81;48;2;200;186;182m▀\033[38;2;172;170;174;48;2;254;238;230m▀\033[38;2;218;205;202m▀\033[38;2;240;219;212;48;2;211;191;185m▀\033[48;2;0;22;42m \033[38;2;8;15;22;48;2;9;52;85m▀\033[38;2;0;0;0;48;2;8;15;22m▀\033[48;2;0;0;0m \033[38;2;39;48;57m▀\033[38;2;8;15;22m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;87;98;110m▀\033[38;2;218;205;202;48;2;0;22;42m▀\033[38;2;122;112;114;48;2;8;15;22m▀\033[38;2;39;65;87;48;2;9;52;85m▀\033[38;2;9;36;58m▀\033[38;2;8;15;22;48;2;9;36;58m▀\033[38;2;218;205;202;48;2;39;48;57m▀\033[38;2;254;238;230;48;2;166;150;148m▀\033[38;2;248;225;217;48;2;218;205;202m▀\033[38;2;254;238;230;48;2;158;109;102m▀\033[38;2;122;112;114;48;2;0;22;42m▀\033[38;2;0;22;42;48;2;9;52;85m▀ \033[38;2;9;52;85;48;2;9;36;58m▀\033[0m
\033[48;2;0;0;0m    \033[38;2;8;15;22m▀\033[38;2;9;52;85;48;2;0;22;42m▀\033[48;2;9;52;85m \033[38;2;9;52;85;48;2;9;36;58m▀\033[48;2;0;22;42m▀\033[38;2;0;22;42;48;2;9;52;85m▀\033[38;2;39;48;57;48;2;9;36;58m▀\033[48;2;8;15;22m \033[38;2;9;36;58;48;2;0;0;0m▀▀\033[0m
\033[48;2;0;0;0m     \033[38;2;9;36;58;48;2;9;52;85m▀ \033[48;2;8;15;22m \033[38;2;8;15;22;48;2;9;52;85m▀ \033[38;2;9;36;58;48;2;8;15;22m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;0;22;42m▀\033[38;2;8;15;22m▀ ▀▀\033[0m
==>35-kitsune
\033[48;2;0;0;0m    \033[38;2;0;0;0;48;2;125;21;10m▀\033[38;2;21;9;6;48;2;217;12;6m▀\033[38;2;72;13;7m▀\033[38;2;125;21;10m▀▀▀\033[38;2;72;13;7m▀▀▀▀\033[38;2;21;9;6;48;2;175;30;18m▀\033[38;2;0;0;0;48;2;129;44;31m▀\033[38;2;21;9;6;48;2;191;153;48m▀\033[38;2;129;44;31m▀\033[38;2;134;71;46;48;2;178;84;42m▀\033[48;2;134;71;46m \033[38;2;129;44;31m▀\033[0m
\033[38;2;21;9;6;48;2;134;71;46m▀\033[48;2;192;95;68m▀\033[48;2;148;35;20m▀\033[38;2;175;30;18;48;2;217;12;6m▀          \033[38;2;217;12;6;48;2;175;30;18m▀\033[38;2;181;49;31m▀\033[38;2;178;84;42;48;2;125;21;10m▀\033[48;2;143;125;119m \033[38;2;129;44;31;48;2;210;178;163m▀\033[38;2;181;49;31;48;2;178;84;42m▀\033[48;2;134;54;17m \033[0m
\033[38;2;134;71;46;48;2;129;44;31m▀\033[38;2;178;84;42;48;2;148;35;20m▀\033[38;2;175;30;18;48;2;217;12;6m▀        \033[38;2;192;95;68;48;2;193;123;98m▀\033[38;2;217;12;6;48;2;192;95;68m▀\033[48;2;217;12;6m \033[38;2;184;66;43m▀\033[38;2;129;30;17;48;2;178;84;42m▀\033[38;2;181;49;31;48;2;125;21;10m▀\033[38;2;148;35;20;48;2;175;30;18m▀\033[38;2;166;146;139;48;2;129;44;31m▀\033[38;2;192;95;68;48;2;193;123;98m▀\033[38;2;129;30;17;48;2;125;21;10m▀\033[38;2;72;13;7;48;2;181;49;31m▀\033[38;2;0;0;0;48;2;129;30;17m▀\033[0m
\033[38;2;72;13;7;48;2;125;21;10m▀\033[48;2;217;12;6m \033[38;2;217;12;6;48;2;184;66;43m▀\033[48;2;193;123;98m▀▀\033[48;2;217;12;6m \033[38;2;192;95;68;48;2;193;123;98m▀\033[38;2;184;66;43;48;2;192;95;68m▀▀\033[38;2;193;123;98;48;2;184;66;43m▀\033[38;2;184;66;43;48;2;193;123;98m▀\033[38;2;178;84;42;48;2;148;35;20m▀\033[38;2;193;123;98;48;2;175;30;18m▀\033[38;2;192;95;68;48;2;193;123;98m▀▀\033[38;2;184;66;43;48;2;192;95;68m▀\033[38;2;178;84;42;48;2;193;123;98m▀\033[48;2;129;30;17m \033[48;2;175;30;18m \033[38;2;129;30;17;48;2;125;21;10m▀\033[48;2;129;30;17m \033[48;2;181;49;31m \033[38;2;181;49;31;48;2;175;30;18m▀\033[38;2;148;35;20;48;2;181;49;31m▀\033[38;2;21;9;6;48;2;148;35;20m▀\033[38;2;0;0;0;48;2;21;9;6m▀\033[0m
\033[38;2;129;30;17;48;2;129;44;31m▀\033[38;2;184;66;43;48;2;192;95;68m▀\033[38;2;193;123;98m▀\033[38;2;192;95;68;48;2;175;30;18m▀\033[48;2;181;49;31m▀\033[38;2;193;123;98;48;2;215;152;116m▀\033[38;2;184;66;43;48;2;175;30;18m▀\033[48;2;181;49;31m▀\033[38;2;193;123;98m▀\033[38;2;125;21;10;48;2;134;71;46m▀\033[38;2;181;49;31;48;2;129;44;31m▀\033[38;2;125;21;10;48;2;134;71;46m▀▀\033[38;2;134;71;46;48;2;129;44;31m▀\033[38;2;184;66;43;48;2;148;35;20m▀\033[38;2;148;35;20;48;2;72;13;7m▀\033[38;2;215;152;116;48;2;181;49;31m▀ \033[38;2;129;30;17;48;2;125;21;10m▀\033[38;2;72;13;7m▀\033[38;2;129;30;17;48;2;175;30;18m▀\033[48;2;181;49;31m   \033[48;2;217;12;6m \033[38;2;175;30;18m▀\033[38;2;21;9;6;48;2;148;35;20m▀\033[0m
\033[38;2;129;30;17;48;2;72;13;7m▀\033[38;2;175;30;18;48;2;148;35;20m▀\033[38;2;148;35;20;48;2;129;30;17m▀\033[38;2;129;30;17;48;2;129;44;31m▀\033[38;2;72;13;7;48;2;134;71;46m▀\033[38;2;184;66;43;48;2;129;44;31m▀\033[38;2;148;35;20;48;2;175;30;18m▀\033[38;2;129;30;17;48;2;129;44;31m▀\033[38;2;148;35;20;48;2;125;21;10m▀\033[38;2;193;123;98;48;2;203;158;137m▀\033[38;2;143;125;119;48;2;166;146;139m▀\033[38;2;134;71;46;48;2;75;64;62m▀\033[38;2;193;123;98;48;2;76;46;37m▀\033[38;2;134;71;46m▀\033[38;2;175;30;18;48;2;72;13;7m▀\033[48;2;125;21;10m \033[38;2;125;21;10;48;2;129;30;17m▀\033[38;2;148;35;20m▀ \033[48;2;125;21;10m \033[48;2;175;30;18m  \033[38;2;175;30;18;48;2;217;12;6m▀\033[38;2;148;35;20;48;2;181;49;31m▀\033[38;2;175;30;18;48;2;125;21;10m▀\033[48;2;192;95;68m \033[38;2;193;123;98;48;2;215;152;116m▀\033[38;2;76;46;37;48;2;193;123;98m▀\033[38;2;0;0;0;48;2;21;9;6m▀\033[0m
\033[38;2;21;9;6;48;2;0;0;0m▀\033[38;2;129;30;17;48;2;125;21;10m▀\033[38;2;125;21;10;48;2;129;30;17m▀\033[38;2;193;123;98;48;2;76;46;37m▀\033[38;2;75;64;62;48;2;210;178;163m▀\033[38;2;72;13;7;48;2;76;46;37m▀\033[38;2;125;21;10;48;2;143;125;119m▀\033[38;2;129;86;76;48;2;203;158;137m▀\033[38;2;134;71;46;48;2;202;190;185m▀\033[38;2;210;178;163;48;2;226;209;199m▀\033[38;2;143;125;119m▀\033[38;2;202;190;185;48;2;134;71;46m▀\033[38;2;192;95;68;48;2;129;44;31m▀\033[38;2;72;13;7;48;2;129;30;17m▀\033[38;2;21;9;6;48;2;72;13;7m▀\033[38;2;125;21;10;48;2;134;71;46m▀\033[38;2;148;35;20;48;2;175;30;18m▀\033[38;2;125;21;10;48;2;129;44;31m▀\033[38;2;129;44;31;48;2;129;86;76m▀\033[38;2;76;46;37;48;2;202;190;185m▀\033[38;2;148;35;20;48;2;134;71;46m▀\033[38;2;181;49;31;48;2;175;30;18m▀\033[38;2;184;66;43;48;2;181;49;31m▀\033[48;2;192;95;68m \033[38;2;134;71;46;48;2;215;152;116m▀\033[38;2;129;44;31;48;2;134;71;46m▀\033[38;2;215;152;116;48;2;192;95;68m▀\033[38;2;192;95;68;48;2;193;123;98m▀\033[38;2;134;71;46;48;2;175;30;18m▀\033[38;2;0;0;0;48;2;72;13;7m▀\033[0m
\033[48;2;0;0;0m \033[38;2;21;9;6m▀\033[38;2;125;21;10;48;2;21;9;6m▀\033[38;2;129;86;76;48;2;143;125;119m▀\033[38;2;143;125;119;48;2;203;158;137m▀\033[38;2;125;21;10;48;2;129;30;17m▀\033[38;2;202;190;185;48;2;182;166;163m▀\033[48;2;226;209;199m   \033[38;2;241;231;228m▀\033[38;2;134;71;46;48;2;193;123;98m▀\033[38;2;184;66;43;48;2;192;95;68m▀\033[48;2;193;123;98m▀\033[38;2;129;86;76;48;2;192;95;68m▀\033[38;2;192;95;68;48;2;178;84;42m▀\033[38;2;184;66;43;48;2;193;123;98m▀\033[38;2;166;146;139;48;2;182;166;163m▀\033[38;2;171;159;156;48;2;241;231;228m▀\033[38;2;226;209;199;48;2;171;159;156m▀\033[38;2;171;159;156;48;2;202;190;185m▀\033[38;2;129;30;17;48;2;75;64;62m▀\033[38;2;181;49;31;48;2;175;30;18m▀\033[38;2;184;66;43;48;2;181;49;31m▀\033[48;2;175;30;18m▀\033[38;2;215;152;116;48;2;181;49;31m▀\033[38;2;129;86;76;48;2;193;123;98m▀\033[38;2;215;152;116;48;2;143;125;119m▀\033[38;2;181;49;31;48;2;192;95;68m▀\033[48;2;148;35;20m \033[38;2;21;9;6;48;2;72;13;7m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;21;9;6m▀\033[38;2;129;86;76;48;2;129;44;31m▀\033[38;2;210;178;163;48;2;203;158;137m▀\033[38;2;193;123;98;48;2;226;209;199m▀ \033[38;2;226;209;199;48;2;171;159;156m▀\033[48;2;215;152;116m▀\033[48;2;193;123;98m▀\033[48;2;210;178;163m▀\033[38;2;210;178;163;48;2;241;231;228m▀\033[48;2;210;178;163m \033[48;2;215;152;116m \033[38;2;178;84;42;48;2;134;71;46m▀\033[48;2;175;30;18m▀\033[38;2;192;95;68;48;2;129;86;76m▀\033[38;2;202;190;185;48;2;241;231;228m▀ \033[38;2;226;209;199m▀\033[38;2;166;146;139;48;2;171;159;156m▀\033[38;2;134;71;46;48;2;125;21;10m▀\033[38;2;72;13;7;48;2;129;30;17m▀\033[38;2;129;44;31m▀\033[38;2;181;49;31m▀\033[48;2;148;35;20m  \033[38;2;192;95;68;48;2;175;30;18m▀\033[38;2;134;71;46;48;2;129;30;17m▀\033[38;2;148;35;20m▀\033[38;2;129;30;17;48;2;148;35;20m▀\033[38;2;21;9;6;48;2;72;13;7m▀\033[0m
\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;75;64;62m▀\033[48;2;166;146;139m▀\033[38;2;193;123;98;48;2;192;95;68m▀\033[38;2;129;86;76;48;2;178;84;42m▀\033[38;2;143;125;119;48;2;0;0;0m▀\033[38;2;226;209;199;48;2;21;9;6m▀\033[38;2;202;190;185;48;2;75;64;62m▀\033[38;2;193;123;98;48;2;129;86;76m▀\033[48;2;76;46;37m▀\033[38;2;226;209;199;48;2;129;86;76m▀\033[38;2;210;178;163m▀\033[38;2;193;123;98;48;2;21;9;6m▀\033[38;2;134;71;46;48;2;72;13;7m▀\033[38;2;148;35;20;48;2;134;71;46m▀\033[38;2;134;71;46;48;2;182;166;163m▀\033[38;2;226;209;199;48;2;241;231;228m▀   \033[38;2;202;190;185;48;2;166;146;139m▀\033[38;2;134;71;46;48;2;75;64;62m▀\033[48;2;148;35;20m \033[48;2;175;30;18m \033[38;2;175;30;18;48;2;217;12;6m▀\033[48;2;129;30;17m \033[38;2;148;35;20m▀\033[48;2;148;35;20m  \033[48;2;125;21;10m \033[38;2;175;30;18;48;2;148;35;20m▀\033[48;2;72;13;7m \033[0m
\033[48;2;75;64;62m \033[38;2;182;166;163;48;2;191;153;48m▀\033[38;2;143;125;119;48;2;134;54;17m▀\033[38;2;181;49;31;48;2;125;21;10m▀\033[38;2;72;13;7;48;2;76;46;37m▀\033[38;2;0;0;0;48;2;21;9;6m▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;21;9;6m▀\033[48;2;143;125;119m▀▀\033[38;2;75;64;62;48;2;191;153;48m▀\033[38;2;191;153;48;48;2;236;204;22m▀\033[38;2;129;86;76;48;2;178;84;42m▀\033[38;2;134;54;17;48;2;186;123;35m▀\033[38;2;186;123;35;48;2;236;204;22m▀\033[38;2;192;95;68;48;2;186;123;35m▀\033[38;2;226;209;199;48;2;182;166;163m▀\033[48;2;241;231;228m   \033[38;2;226;209;199m▀\033[38;2;143;125;119m▀\033[38;2;129;44;31;48;2;226;209;199m▀\033[38;2;175;30;18;48;2;143;125;119m▀\033[48;2;175;30;18m \033[38;2;175;30;18;48;2;217;12;6m▀\033[38;2;125;21;10;48;2;129;30;17m▀\033[38;2;148;35;20m▀\033[48;2;148;35;20m \033[48;2;129;30;17m \033[38;2;148;35;20m▀\033[48;2;72;13;7m \033[0m
\033[38;2;134;54;17;48;2;76;46;37m▀\033[38;2;236;204;22;48;2;186;123;35m▀\033[38;2;186;123;35;48;2;181;49;31m▀\033[48;2;178;84;42m▀\033[38;2;236;204;22;48;2;191;153;48m▀\033[38;2;76;46;37;48;2;0;0;0m▀ \033[48;2;21;9;6m▀\033[38;2;241;231;228;48;2;143;125;119m▀▀\033[38;2;129;86;76;48;2;75;64;62m▀\033[38;2;186;123;35;48;2;134;71;46m▀\033[38;2;184;66;43;48;2;192;95;68m▀\033[38;2;175;30;18;48;2;217;12;6m▀\033[48;2;134;71;46m \033[38;2;134;71;46;48;2;202;190;185m▀\033[38;2;202;190;185;48;2;241;231;228m▀    \033[48;2;226;209;199m  \033[38;2;241;231;228m▀\033[38;2;192;95;68m▀\033[38;2;217;12;6;48;2;148;35;20m▀\033[48;2;175;30;18m \033[38;2;129;30;17;48;2;72;13;7m▀\033[38;2;148;35;20;48;2;125;21;10m▀\033[48;2;129;30;17m▀\033[38;2;125;21;10;48;2;72;13;7m▀\033[48;2;21;9;6m \033[0m
\033[48;2;76;46;37m \033[48;2;184;66;43m \033[38;2;217;12;6;48;2;181;49;31m▀\033[38;2;175;30;18;48;2;148;35;20m▀\033[38;2;21;9;6;48;2;75;64;62m▀\033[38;2;129;86;76;48;2;171;159;156m▀\033[38;2;143;125;119;48;2;182;166;163m▀\033[38;2;21;9;6;48;2;171;159;156m▀\033[38;2;129;86;76;48;2;202;190;185m▀\033[38;2;226;209;199;48;2;241;231;228m▀\033[38;2;182;166;163;48;2;202;190;185m▀\033[38;2;184;66;43;48;2;134;71;46m▀\033[48;2;129;44;31m▀\033[48;2;181;49;31m▀\033[38;2;129;30;17;48;2;125;21;10m▀\033[38;2;226;209;199;48;2;166;146;139m▀\033[48;2;241;231;228m     \033[48;2;226;209;199m   \033[38;2;241;231;228m▀\033[38;2;166;146;139m▀\033[38;2;125;21;10;48;2;129;86;76m▀\033[38;2;148;35;20;48;2;125;21;10m▀\033[48;2;175;30;18m \033[48;2;148;35;20m \033[38;2;129;30;17;48;2;175;30;18m▀\033[48;2;72;13;7m \033[0m
\033[38;2;76;46;37;48;2;129;86;76m▀\033[48;2;182;166;163m▀\033[38;2;148;35;20;48;2;129;86;76m▀\033[38;2;125;21;10;48;2;76;46;37m▀\033[38;2;166;146;139;48;2;143;125;119m▀\033[48;2;171;159;156m \033[48;2;166;146;139m \033[38;2;171;159;156;48;2;182;166;163m▀ \033[38;2;241;231;228;48;2;226;209;199m▀\033[48;2;241;231;228m \033[38;2;226;209;199m▀\033[38;2;166;146;139;48;2;226;209;199m▀\033[38;2;125;21;10;48;2;143;125;119m▀\033[38;2;129;86;76;48;2;241;231;228m▀\033[38;2;171;159;156m▀     \033[48;2;226;209;199m    \033[38;2;241;231;228m▀\033[38;2;171;159;156m▀\033[38;2;125;21;10;48;2;129;86;76m▀\033[48;2;175;30;18m \033[48;2;148;35;20m  \033[48;2;72;13;7m \033[0m
\033[38;2;129;86;76;48;2;143;125;119m▀\033[38;2;202;190;185;48;2;241;231;228m▀\033[38;2;171;159;156;48;2;143;125;119m▀▀\033[38;2;182;166;163;48;2;171;159;156m▀\033[38;2;226;209;199;48;2;241;231;228m▀   \033[48;2;226;209;199m \033[38;2;241;231;228m▀\033[48;2;241;231;228m  \033[38;2;171;159;156;48;2;143;125;119m▀\033[38;2;241;231;228;48;2;202;190;185m▀\033[48;2;182;166;163m▀\033[48;2;171;159;156m▀\033[48;2;182;166;163m▀\033[48;2;171;159;156m▀\033[48;2;182;166;163m▀\033[48;2;202;190;185m▀\033[38;2;226;209;199m▀\033[48;2;226;209;199m \033[38;2;226;209;199;48;2;241;231;228m▀\033[48;2;226;209;199m   \033[38;2;166;146;139;48;2;202;190;185m▀\033[38;2;148;35;20;48;2;125;21;10m▀\033[48;2;148;35;20m  \033[48;2;72;13;7m \033[0m
\033[48;2;143;125;119m \033[48;2;241;231;228m \033[38;2;143;125;119m▀\033[38;2;182;166;163m▀\033[38;2;202;190;185m▀    \033[38;2;226;209;199m▀   ▀\033[38;2;129;86;76;48;2;226;209;199m▀\033[48;2;210;178;163m▀\033[38;2;215;152;116m▀\033[38;2;75;64;62;48;2;129;44;31m▀\033[38;2;143;125;119m▀\033[48;2;134;71;46m▀\033[48;2;129;86;76m▀▀\033[38;2;129;86;76;48;2;134;71;46m▀\033[38;2;143;125;119;48;2;148;35;20m▀\033[38;2;202;190;185m▀\033[38;2;171;159;156m▀\033[38;2;166;146;139;48;2;125;21;10m▀\033[38;2;129;86;76;48;2;175;30;18m▀\033[38;2;125;21;10m▀\033[48;2;148;35;20m \033[38;2;148;35;20;48;2;175;30;18m▀\033[48;2;72;13;7m \033[0m
\033[38;2;21;9;6;48;2;0;0;0m▀\033[38;2;75;64;62m▀▀▀▀▀\033[38;2;76;46;37m▀\033[38;2;75;64;62m▀▀▀▀▀▀▀▀\033[38;2;76;46;37m▀▀\033[38;2;72;13;7m▀▀▀▀\033[38;2;21;9;6m▀\033[38;2;72;13;7m▀▀▀▀▀▀▀▀▀\033[0m
==>36-kitsune-jade
\033[48;2;0;0;0m    \033[38;2;0;0;0;48;2;15;86;17m▀\033[48;2;17;190;26m▀\033[38;2;15;86;17m▀▀▀▀▀▀▀▀\033[38;2;0;0;0;48;2;18;137;27m▀\033[48;2;67;64;47m▀\033[48;2;189;122;32m▀\033[38;2;78;92;39m▀\033[38;2;122;90;27;48;2;154;117;36m▀\033[48;2;122;90;27m \033[38;2;67;64;47;48;2;154;117;36m▀\033[0m
\033[38;2;0;0;0;48;2;122;90;27m▀\033[48;2;189;122;32m▀\033[48;2;18;137;27m▀\033[38;2;18;137;27;48;2;17;190;26m▀           \033[38;2;68;126;45m▀\033[38;2;154;117;36;48;2;18;137;27m▀\033[38;2;164;127;94;48;2;160;147;141m▀\033[38;2;78;92;39;48;2;188;175;170m▀\033[38;2;189;122;32;48;2;154;117;36m▀\033[48;2;122;90;27m \033[0m
\033[48;2;122;90;27m \033[38;2;78;92;39;48;2;18;137;27m▀\033[48;2;17;190;26m         \033[38;2;73;179;58;48;2;152;170;84m▀\033[38;2;17;190;26;48;2;124;166;97m▀\033[48;2;17;190;26m \033[38;2;83;131;71;48;2;73;179;58m▀\033[38;2;18;137;27;48;2;83;131;71m▀\033[38;2;17;190;26;48;2;18;137;27m▀\033[38;2;18;137;27;48;2;17;190;26m▀\033[38;2;160;147;141;48;2;18;137;27m▀\033[48;2;164;127;94m \033[38;2;122;90;27;48;2;15;86;17m▀\033[38;2;15;86;17;48;2;17;190;26m▀\033[38;2;0;0;0;48;2;18;137;27m▀\033[0m
\033[38;2;15;86;17;48;2;18;137;27m▀\033[48;2;17;190;26m \033[38;2;17;190;26;48;2;73;179;58m▀\033[48;2;166;170;104m▀▀\033[48;2;17;190;26m \033[38;2;73;179;58;48;2;166;170;104m▀\033[38;2;17;190;26;48;2;124;166;97m▀\033[48;2;73;179;58m \033[38;2;152;170;84m▀\033[38;2;73;179;58;48;2;152;170;84m▀\033[38;2;83;131;71;48;2;18;137;27m▀\033[38;2;166;170;104m▀\033[38;2;124;166;97;48;2;166;170;104m▀\033[38;2;73;179;58m▀\033[48;2;73;179;58m \033[38;2;73;179;58;48;2;166;170;104m▀\033[48;2;18;137;27m \033[38;2;17;190;26m▀\033[38;2;78;92;39;48;2;15;86;17m▀\033[38;2;68;126;45;48;2;18;137;27m▀\033[38;2;18;137;27;48;2;17;190;26m▀\033[38;2;17;190;26;48;2;18;137;27m▀\033[38;2;18;137;27;48;2;17;190;26m▀\033[38;2;0;0;0m▀\033[0m
\033[48;2;18;137;27m \033[38;2;73;179;58;48;2;117;153;71m▀\033[38;2;152;170;84m▀\033[38;2;124;166;97;48;2;17;190;26m▀\033[48;2;73;179;58m▀\033[38;2;166;170;104;48;2;199;161;112m▀\033[38;2;73;179;58;48;2;17;190;26m▀▀\033[38;2;166;170;104;48;2;73;179;58m▀\033[38;2;18;137;27;48;2;114;107;70m▀\033[38;2;17;190;26;48;2;68;126;45m▀\033[38;2;18;137;27;48;2;83;131;71m▀\033[48;2;68;126;45m▀\033[38;2;68;126;45;48;2;18;137;27m▀\033[38;2;73;179;58m▀\033[38;2;68;126;45;48;2;15;86;17m▀\033[38;2;199;161;112;48;2;73;179;58m▀\033[48;2;18;137;27m  \033[48;2;15;86;17m \033[48;2;18;137;27m \033[38;2;17;190;26m▀ ▀\033[48;2;17;190;26m  \033[38;2;0;0;0;48;2;18;137;27m▀\033[0m
\033[38;2;18;137;27;48;2;15;86;17m▀\033[48;2;17;190;26m \033[48;2;18;137;27m \033[38;2;18;137;27;48;2;78;92;39m▀\033[38;2;15;86;17m▀\033[38;2;73;179;58;48;2;68;126;45m▀\033[48;2;18;137;27m \033[38;2;18;137;27;48;2;68;126;45m▀\033[48;2;18;137;27m \033[38;2;120;115;102;48;2;160;147;141m▀\033[38;2;164;127;94m▀\033[38;2;114;107;70;48;2;67;64;47m▀\033[38;2;164;127;94;48;2;15;86;17m▀\033[38;2;68;126;45m▀\033[38;2;18;137;27m▀\033[38;2;15;86;17;48;2;18;137;27m▀▀ \033[48;2;15;86;17m \033[38;2;18;137;27m▀\033[38;2;73;179;58;48;2;17;190;26m▀\033[38;2;17;190;26;48;2;18;137;27m▀\033[48;2;17;190;26m \033[38;2;18;137;27m▀\033[38;2;17;190;26;48;2;18;137;27m▀\033[48;2;117;153;71m \033[48;2;199;161;112m \033[38;2;78;92;39;48;2;166;170;104m▀\033[0m
\033[38;2;15;86;17;48;2;0;0;0m▀\033[38;2;18;137;27;48;2;15;86;17m▀▀\033[38;2;164;127;94;48;2;67;64;47m▀\033[38;2;67;64;47;48;2;188;175;170m▀\033[48;2;15;86;17m \033[38;2;15;86;17;48;2;120;115;102m▀\033[38;2;120;115;102;48;2;220;180;158m▀\033[38;2;78;92;39m▀\033[38;2;220;180;158;48;2;227;202;198m▀\033[38;2;120;115;102;48;2;223;196;189m▀\033[38;2;188;175;170;48;2;68;126;45m▀\033[38;2;117;153;71;48;2;18;137;27m▀\033[38;2;15;86;17m▀\033[38;2;0;0;0;48;2;15;86;17m▀\033[38;2;15;86;17;48;2;78;101;69m▀\033[48;2;18;137;27m \033[38;2;18;137;27;48;2;68;126;45m▀\033[38;2;15;86;17;48;2;78;101;69m▀\033[38;2;67;64;47;48;2;188;175;170m▀\033[38;2;18;137;27;48;2;68;126;45m▀\033[48;2;17;190;26m▀\033[38;2;73;179;58m▀\033[38;2;152;170;84;48;2;117;153;71m▀\033[38;2;68;126;45;48;2;199;161;112m▀\033[38;2;18;137;27;48;2;78;101;69m▀\033[38;2;199;161;112;48;2;117;153;71m▀\033[38;2;117;153;71;48;2;166;170;104m▀\033[38;2;83;131;71;48;2;17;190;26m▀\033[38;2;0;0;0;48;2;15;86;17m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;18;137;27;48;2;15;86;17m▀\033[38;2;78;101;69;48;2;120;115;102m▀\033[38;2;127;127;109;48;2;160;147;141m▀\033[38;2;15;86;17;48;2;18;137;27m▀\033[48;2;223;196;189m \033[38;2;243;231;230;48;2;227;202;198m▀   \033[38;2;78;101;69;48;2;199;161;112m▀\033[38;2;117;153;71;48;2;164;127;94m▀\033[48;2;117;153;71m \033[38;2;114;107;70;48;2;73;179;58m▀\033[48;2;117;153;71m \033[38;2;73;179;58;48;2;189;158;94m▀\033[38;2;160;147;141;48;2;188;175;170m▀\033[48;2;243;231;230m▀\033[38;2;227;202;198;48;2;188;175;170m▀\033[38;2;160;147;141m▀\033[38;2;18;137;27;48;2;78;101;69m▀\033[38;2;17;190;26;48;2;18;137;27m▀\033[48;2;17;190;26m \033[38;2;73;179;58;48;2;18;137;27m▀\033[38;2;199;161;112;48;2;73;179;58m▀\033[38;2;114;107;70;48;2;189;158;94m▀\033[38;2;199;161;112;48;2;164;127;94m▀\033[38;2;68;126;45;48;2;117;153;71m▀\033[48;2;18;137;27m \033[38;2;0;0;0;48;2;15;86;17m▀\033[0m
\033[48;2;0;0;0m   \033[38;2;114;107;70;48;2;68;126;45m▀\033[38;2;220;180;158;48;2;199;161;112m▀\033[38;2;164;127;94;48;2;227;202;198m▀ \033[38;2;227;202;198;48;2;160;147;141m▀\033[48;2;199;161;112m▀\033[48;2;164;127;94m▀\033[48;2;223;196;189m▀\033[38;2;223;196;189;48;2;243;231;230m▀\033[38;2;220;180;158;48;2;223;196;189m▀\033[38;2;199;161;112;48;2;220;180;158m▀\033[38;2;73;179;58;48;2;83;131;71m▀\033[48;2;17;190;26m▀\033[38;2;117;153;71;48;2;124;166;97m▀\033[38;2;223;196;189;48;2;243;231;230m▀ \033[38;2;227;202;198m▀\033[38;2;127;127;109;48;2;160;147;141m▀\033[38;2;68;126;45;48;2;15;86;17m▀\033[38;2;15;86;17;48;2;18;137;27m▀ \033[38;2;73;179;58m▀  \033[38;2;164;127;94m▀\033[38;2;83;131;71;48;2;15;86;17m▀\033[48;2;18;137;27m  \033[38;2;0;0;0;48;2;15;86;17m▀\033[0m
\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;78;101;69m▀\033[48;2;160;147;141m▀\033[38;2;164;127;94;48;2;117;153;71m▀\033[38;2;120;115;102;48;2;83;131;71m▀\033[38;2;127;127;109;48;2;0;0;0m▀\033[38;2;223;196;189m▀\033[38;2;220;180;158;48;2;78;101;69m▀\033[38;2;199;161;112;48;2;114;107;70m▀\033[38;2;189;158;94;48;2;67;64;47m▀\033[38;2;223;196;189;48;2;114;107;70m▀\033[38;2;220;180;158m▀\033[38;2;164;127;94;48;2;0;0;0m▀\033[38;2;78;92;39;48;2;15;86;17m▀\033[38;2;17;190;26;48;2;68;126;45m▀\033[38;2;83;131;71;48;2;188;175;170m▀\033[38;2;227;202;198;48;2;243;231;230m▀   \033[38;2;223;196;189;48;2;160;147;141m▀\033[38;2;68;126;45;48;2;78;92;39m▀\033[48;2;18;137;27m  \033[48;2;17;190;26m \033[48;2;18;137;27m \033[38;2;18;137;27;48;2;15;86;17m▀\033[48;2;18;137;27m  \033[38;2;18;137;27;48;2;15;86;17m▀\033[38;2;17;190;26;48;2;18;137;27m▀\033[48;2;15;86;17m \033[0m
\033[38;2;67;64;47;48;2;78;92;39m▀\033[38;2;188;175;170;48;2;210;171;35m▀\033[38;2;120;115;102;48;2;122;90;27m▀\033[38;2;17;190;26;48;2;18;137;27m▀\033[38;2;15;86;17;48;2;78;92;39m▀\033[48;2;0;0;0m   \033[38;2;0;0;0;48;2;127;127;109m▀\033[48;2;160;147;141m▀\033[38;2;78;92;39;48;2;189;122;32m▀\033[38;2;189;158;94;48;2;210;171;35m▀\033[38;2;114;107;70;48;2;189;122;32m▀\033[38;2;78;92;39;48;2;154;117;36m▀\033[38;2;154;117;36;48;2;210;171;35m▀▀\033[38;2;227;202;198;48;2;223;196;189m▀\033[48;2;243;231;230m   \033[38;2;227;202;198m▀\033[38;2;120;115;102m▀\033[38;2;18;137;27;48;2;223;196;189m▀\033[38;2;17;190;26;48;2;127;127;109m▀\033[48;2;17;190;26m  \033[38;2;15;86;17;48;2;18;137;27m▀    \033[48;2;15;86;17m \033[0m
\033[48;2;78;92;39m \033[38;2;245;201;48;48;2;154;117;36m▀\033[38;2;189;122;32;48;2;68;126;45m▀\033[48;2;154;117;36m▀\033[38;2;245;201;48;48;2;210;171;35m▀\033[38;2;78;92;39;48;2;0;0;0m▀ \033[38;2;67;64;47m▀\033[38;2;243;231;230;48;2;160;147;141m▀\033[48;2;120;115;102m▀\033[38;2;154;117;36;48;2;114;107;70m▀\033[48;2;120;115;102m▀\033[38;2;117;153;71;48;2;124;166;97m▀\033[48;2;17;190;26m \033[48;2;68;126;45m \033[38;2;122;90;27;48;2;223;196;189m▀\033[38;2;223;196;189;48;2;243;231;230m▀     \033[48;2;227;202;198m \033[38;2;243;231;230m▀\033[38;2;120;115;102m▀\033[38;2;17;190;26;48;2;18;137;27m▀\033[48;2;17;190;26m \033[38;2;18;137;27;48;2;15;86;17m▀▀\033[48;2;18;137;27m \033[38;2;18;137;27;48;2;15;86;17m▀\033[38;2;0;0;0;48;2;67;64;47m▀\033[0m
\033[48;2;67;64;47m \033[48;2;73;179;58m \033[48;2;17;190;26m \033[48;2;18;137;27m \033[38;2;0;0;0;48;2;78;101;69m▀\033[38;2;78;101;69;48;2;188;175;170m▀\033[38;2;120;115;102;48;2;223;196;189m▀\033[38;2;0;0;0;48;2;188;175;170m▀\033[38;2;78;101;69m▀\033[38;2;227;202;198;48;2;243;231;230m▀\033[38;2;188;175;170;48;2;227;202;198m▀\033[38;2;83;131;71;48;2;78;101;69m▀\033[38;2;73;179;58;48;2;18;137;27m▀\033[48;2;17;190;26m▀\033[38;2;18;137;27;48;2;15;86;17m▀\033[38;2;227;202;198;48;2;160;147;141m▀\033[48;2;243;231;230m      \033[48;2;227;202;198m  \033[38;2;243;231;230m▀\033[38;2;160;147;141m▀\033[38;2;18;137;27;48;2;78;101;69m▀\033[48;2;18;137;27m \033[48;2;17;190;26m \033[48;2;18;137;27m \033[38;2;18;137;27;48;2;17;190;26m▀\033[48;2;15;86;17m \033[0m
\033[38;2;67;64;47;48;2;78;101;69m▀\033[38;2;78;92;39;48;2;188;175;170m▀\033[38;2;18;137;27;48;2;78;101;69m▀\033[48;2;78;92;39m▀\033[38;2;160;147;141;48;2;120;115;102m▀\033[48;2;188;175;170m   \033[38;2;188;175;170;48;2;223;196;189m▀\033[38;2;243;231;230;48;2;227;202;198m▀\033[48;2;243;231;230m \033[38;2;227;202;198m▀\033[38;2;160;147;141;48;2;223;196;189m▀\033[38;2;15;86;17;48;2;127;127;109m▀\033[38;2;83;131;71;48;2;243;231;230m▀\033[38;2;160;147;141m▀      \033[48;2;227;202;198m    \033[38;2;188;175;170m▀\033[38;2;15;86;17;48;2;83;131;71m▀\033[38;2;17;190;26;48;2;18;137;27m▀  \033[48;2;15;86;17m \033[0m
\033[38;2;120;115;102;48;2;127;127;109m▀\033[38;2;227;202;198;48;2;243;231;230m▀\033[38;2;188;175;170;48;2;127;127;109m▀\033[48;2;160;147;141m▀\033[48;2;188;175;170m \033[38;2;227;202;198;48;2;243;231;230m▀   ▀\033[38;2;243;231;230;48;2;227;202;198m▀\033[48;2;243;231;230m  \033[38;2;188;175;170;48;2;160;147;141m▀\033[38;2;243;231;230;48;2;227;202;198m▀\033[48;2;188;175;170m▀\033[48;2;160;147;141m▀▀▀\033[48;2;188;175;170m▀\033[48;2;223;196;189m▀\033[38;2;227;202;198m▀\033[48;2;227;202;198m \033[38;2;227;202;198;48;2;243;231;230m▀\033[48;2;227;202;198m  \033[38;2;227;202;198;48;2;243;231;230m▀\033[38;2;160;147;141;48;2;220;180;158m▀\033[38;2;18;137;27;48;2;15;86;17m▀\033[48;2;18;137;27m  \033[48;2;15;86;17m \033[0m
\033[48;2;160;147;141m \033[48;2;243;231;230m \033[38;2;127;127;109m▀\033[38;2;188;175;170m▀\033[38;2;227;202;198m▀    ▀   \033[38;2;223;196;189m▀\033[38;2;120;115;102;48;2;227;202;198m▀\033[38;2;127;127;109;48;2;188;175;170m▀\033[38;2;199;161;112;48;2;223;196;189m▀\033[38;2;78;92;39;48;2;68;126;45m▀\033[38;2;127;127;109;48;2;18;137;27m▀\033[48;2;68;126;45m▀\033[48;2;83;131;71m▀▀\033[38;2;120;115;102;48;2;68;126;45m▀\033[38;2;160;147;141;48;2;18;137;27m▀\033[38;2;188;175;170m▀\033[38;2;160;147;141m▀\033[48;2;17;190;26m▀\033[38;2;120;115;102;48;2;18;137;27m▀\033[38;2;18;137;27;48;2;17;190;26m▀\033[48;2;18;137;27m  \033[48;2;15;86;17m \033[0m
\033[38;2;67;64;47;48;2;0;0;0m▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀\033[38;2;15;86;17m▀▀▀▀▀▀▀▀▀▀▀▀▀\033[0m
==>37-kitsune-azure
\033[48;2;0;0;0m    \033[38;2;0;0;0;48;2;14;31;87m▀\033[48;2;19;61;178m▀\033[38;2;14;31;87;48;2;13;69;208m▀▀▀▀▀▀▀▀\033[38;2;0;0;0;48;2;19;61;178m▀\033[48;2;74;62;49m▀\033[48;2;184;114;65m▀\033[38;2;74;62;49m▀▀\033[38;2;129;96;77;48;2;96;80;84m▀\033[38;2;74;62;49;48;2;149;110;45m▀\033[0m
\033[38;2;0;0;0;48;2;129;96;77m▀\033[48;2;184;114;65m▀\033[48;2;18;48;139m▀\033[38;2;19;61;178;48;2;13;69;208m▀         \033[38;2;13;69;208;48;2;19;61;178m▀\033[48;2;13;69;208m \033[38;2;62;71;128m▀\033[38;2;149;110;45;48;2;18;48;139m▀\033[38;2;129;112;125;48;2;152;131;127m▀\033[38;2;96;80;84;48;2;173;163;168m▀\033[38;2;184;114;65;48;2;150;118;79m▀\033[38;2;129;96;77;48;2;149;110;45m▀\033[0m
\033[38;2;129;96;77;48;2;74;62;49m▀\033[38;2;96;80;84;48;2;62;71;128m▀\033[38;2;19;61;178;48;2;13;69;208m▀        \033[48;2;125;121;165m \033[38;2;13;69;208m▀\033[38;2;19;61;178;48;2;13;69;208m▀\033[38;2;62;71;128;48;2;66;86;177m▀\033[38;2;18;48;139;48;2;95;89;119m▀\033[38;2;13;69;208;48;2;18;48;139m▀\033[38;2;18;48;139;48;2;13;69;208m▀\033[38;2;156;143;144;48;2;18;48;139m▀\033[38;2;183;131;113;48;2;150;118;79m▀\033[38;2;74;62;49;48;2;66;61;77m▀\033[38;2;14;31;87;48;2;19;61;178m▀\033[38;2;0;0;0;48;2;18;48;139m▀\033[0m
\033[38;2;14;31;87;48;2;18;48;139m▀\033[48;2;13;69;208m \033[38;2;13;69;208;48;2;66;86;177m▀\033[48;2;152;131;127m▀\033[38;2;66;86;177;48;2;156;143;144m▀\033[48;2;13;69;208m \033[38;2;66;86;177;48;2;125;121;165m▀▀▀\033[38;2;156;143;144;48;2;62;71;128m▀\033[38;2;66;86;177;48;2;125;121;165m▀\033[38;2;95;89;119;48;2;19;61;178m▀\033[38;2;129;112;125m▀\033[38;2;125;121;165;48;2;156;143;144m▀\033[38;2;66;86;177;48;2;152;131;127m▀\033[48;2;125;121;165m▀▀\033[48;2;18;48;139m \033[48;2;19;61;178m \033[38;2;66;61;77;48;2;14;31;87m▀\033[38;2;14;31;87;48;2;18;48;139m▀\033[38;2;13;69;208;48;2;19;61;178m▀▀\033[38;2;18;48;139m▀\033[38;2;0;0;0m▀\033[0m
\033[48;2;18;48;139m \033[38;2;66;86;177;48;2;125;121;165m▀\033[38;2;152;131;127;48;2;95;89;119m▀\033[38;2;129;112;125;48;2;19;61;178m▀\033[38;2;125;121;165;48;2;66;86;177m▀\033[38;2;156;143;144;48;2;191;153;144m▀\033[38;2;66;86;177;48;2;19;61;178m▀▀\033[38;2;156;143;144;48;2;66;86;177m▀\033[38;2;18;48;139;48;2;95;89;119m▀\033[38;2;19;61;178;48;2;62;71;128m▀\033[38;2;18;48;139;48;2;96;80;84m▀\033[48;2;95;89;119m▀\033[38;2;62;71;128;48;2;18;48;139m▀▀\033[38;2;18;48;139;48;2;14;31;87m▀\033[38;2;191;153;144;48;2;62;71;128m▀\033[38;2;62;71;128;48;2;18;48;139m▀ \033[48;2;14;31;87m \033[48;2;18;48;139m \033[38;2;19;61;178;48;2;13;69;208m▀\033[38;2;13;69;208;48;2;19;61;178m▀ ▀\033[38;2;19;61;178;48;2;13;69;208m▀\033[38;2;0;0;0;48;2;18;48;139m▀\033[0m
\033[38;2;18;48;139;48;2;14;31;87m▀\033[48;2;19;61;178m \033[38;2;19;61;178;48;2;18;48;139m▀\033[38;2;18;48;139;48;2;66;61;77m▀\033[38;2;14;31;87;48;2;96;80;84m▀\033[48;2;62;71;128m \033[48;2;18;48;139m \033[38;2;18;48;139;48;2;66;61;77m▀\033[48;2;14;31;87m▀\033[38;2;129;112;125;48;2;191;153;144m▀\033[38;2;152;131;127;48;2;156;143;144m▀\033[38;2;96;80;84;48;2;66;61;77m▀\033[38;2;183;131;113;48;2;14;31;87m▀\033[38;2;96;80;84m▀\033[38;2;19;61;178m▀\033[38;2;14;31;87;48;2;18;48;139m▀▀ \033[38;2;18;48;139;48;2;14;31;87m▀▀\033[48;2;19;61;178m  \033[38;2;19;61;178;48;2;13;69;208m▀\033[38;2;18;48;139;48;2;19;61;178m▀\033[38;2;13;69;208;48;2;18;48;139m▀\033[38;2;129;112;125;48;2;95;89;119m▀\033[38;2;191;153;144;48;2;220;154;109m▀\033[38;2;66;61;77;48;2;156;143;144m▀\033[38;2;0;0;0;48;2;74;62;49m▀\033[0m
\033[38;2;14;31;87;48;2;0;0;0m▀\033[38;2;18;48;139;48;2;14;31;87m▀▀\033[38;2;152;131;127;48;2;74;62;49m▀\033[38;2;74;62;49;48;2;173;163;168m▀\033[38;2;14;31;87;48;2;66;61;77m▀\033[48;2;129;112;125m▀\033[38;2;129;96;77;48;2;191;153;144m▀\033[38;2;66;61;77;48;2;214;198;196m▀\033[38;2;214;198;196;48;2;234;220;217m▀\033[38;2;129;112;125;48;2;214;198;196m▀\033[38;2;173;163;168;48;2;62;71;128m▀\033[38;2;95;89;119;48;2;18;48;139m▀\033[38;2;14;31;87m▀\033[38;2;0;0;0;48;2;14;31;87m▀\033[38;2;14;31;87;48;2;96;80;84m▀\033[38;2;18;48;139;48;2;19;61;178m▀\033[48;2;14;31;87m▀\033[38;2;14;31;87;48;2;95;89;119m▀\033[38;2;66;61;77;48;2;173;163;168m▀\033[38;2;18;48;139;48;2;62;71;128m▀\033[48;2;19;61;178m▀\033[38;2;13;69;208m▀\033[38;2;129;112;125;48;2;95;89;119m▀\033[38;2;96;80;84;48;2;220;154;109m▀\033[38;2;18;48;139;48;2;96;80;84m▀\033[38;2;191;153;144;48;2;129;112;125m▀\033[38;2;95;89;119;48;2;191;153;144m▀\033[38;2;96;80;84;48;2;19;61;178m▀\033[38;2;0;0;0;48;2;14;31;87m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;18;48;139;48;2;14;31;87m▀\033[38;2;95;89;119;48;2;129;112;125m▀\033[38;2;129;112;125;48;2;173;163;168m▀\033[48;2;14;31;87m \033[48;2;214;198;196m \033[48;2;234;220;217m    \033[38;2;96;80;84;48;2;191;153;144m▀\033[38;2;95;89;119;48;2;184;114;65m▀\033[38;2;96;80;84;48;2;129;112;125m▀\033[38;2;129;96;77;48;2;66;86;177m▀\033[48;2;95;89;119m \033[38;2;62;71;128;48;2;183;131;113m▀\033[48;2;173;163;168m \033[38;2;156;143;144;48;2;247;239;232m▀\033[38;2;234;220;217;48;2;173;163;168m▀\033[38;2;156;143;144m▀\033[38;2;18;48;139;48;2;66;61;77m▀\033[38;2;19;61;178;48;2;18;48;139m▀\033[48;2;13;69;208m \033[38;2;66;86;177;48;2;19;61;178m▀\033[38;2;191;153;144;48;2;66;86;177m▀\033[38;2;129;96;77;48;2;183;131;113m▀\033[38;2;191;153;144;48;2;150;118;79m▀\033[38;2;62;71;128;48;2;129;112;125m▀\033[48;2;18;48;139m \033[38;2;0;0;0;48;2;14;31;87m▀\033[0m
\033[48;2;0;0;0m   \033[38;2;96;80;84;48;2;62;71;128m▀\033[48;2;191;153;144m \033[38;2;183;131;113;48;2;234;220;217m▀\033[38;2;214;198;196m▀\033[38;2;234;220;217;48;2;191;153;144m▀▀\033[48;2;173;142;75m▀\033[48;2;214;198;196m \033[38;2;214;198;196;48;2;234;220;217m▀\033[48;2;214;198;196m \033[38;2;191;153;144;48;2;220;154;109m▀\033[38;2;62;71;128;48;2;96;80;84m▀\033[38;2;125;121;165;48;2;19;61;178m▀\033[38;2;129;112;125;48;2;125;121;165m▀\033[38;2;214;198;196;48;2;247;239;232m▀ ▀\033[38;2;152;131;127;48;2;156;143;144m▀\033[38;2;62;71;128;48;2;14;31;87m▀\033[38;2;14;31;87;48;2;18;48;139m▀ \033[38;2;19;61;178m▀  \033[38;2;129;112;125m▀\033[38;2;96;80;84m▀ \033[38;2;18;48;139;48;2;19;61;178m▀\033[38;2;0;0;0;48;2;14;31;87m▀\033[0m
\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;66;61;77m▀\033[48;2;156;143;144m▀\033[38;2;129;112;125;48;2;125;121;165m▀\033[38;2;129;96;77;48;2;95;89;119m▀\033[38;2;129;112;125;48;2;0;0;0m▀\033[38;2;214;198;196m▀\033[48;2;96;80;84m▀\033[38;2;198;133;97m▀\033[48;2;74;62;49m▀\033[38;2;214;198;196;48;2;96;80;84m▀\033[48;2;129;112;125m▀\033[38;2;183;131;113;48;2;0;0;0m▀\033[38;2;66;61;77;48;2;14;31;87m▀\033[38;2;19;61;178;48;2;62;71;128m▀\033[38;2;95;89;119;48;2;173;163;168m▀\033[38;2;214;198;196;48;2;247;239;232m▀  \033[48;2;234;220;217m \033[38;2;214;198;196;48;2;173;163;168m▀\033[38;2;62;71;128;48;2;66;61;77m▀\033[48;2;18;48;139m \033[38;2;18;48;139;48;2;19;61;178m▀\033[38;2;19;61;178;48;2;13;69;208m▀\033[48;2;18;48;139m    \033[38;2;14;31;87m▀\033[38;2;19;61;178m▀\033[48;2;14;31;87m \033[0m
\033[38;2;66;61;77;48;2;74;62;49m▀\033[38;2;173;163;168;48;2;235;205;18m▀\033[38;2;129;112;125;48;2;74;62;49m▀\033[38;2;19;61;178;48;2;18;48;139m▀\033[38;2;14;31;87;48;2;74;62;49m▀\033[48;2;0;0;0m   \033[38;2;0;0;0;48;2;129;112;125m▀\033[48;2;125;121;165m▀\033[38;2;74;62;49;48;2;182;146;34m▀\033[38;2;173;142;75;48;2;235;205;18m▀\033[38;2;150;118;79;48;2;182;146;34m▀\033[38;2;74;62;49;48;2;149;110;45m▀\033[38;2;149;110;45;48;2;235;205;18m▀\033[38;2;150;118;79;48;2;182;146;34m▀\033[38;2;234;220;217;48;2;214;198;196m▀\033[48;2;247;239;232m   \033[38;2;214;198;196m▀\033[38;2;95;89;119m▀\033[38;2;18;48;139;48;2;214;198;196m▀\033[38;2;19;61;178;48;2;125;121;165m▀\033[38;2;13;69;208;48;2;19;61;178m▀\033[38;2;19;61;178;48;2;13;69;208m▀\033[38;2;14;31;87;48;2;18;48;139m▀ \033[38;2;18;48;139;48;2;19;61;178m▀\033[48;2;18;48;139m  \033[48;2;14;31;87m \033[0m
\033[48;2;74;62;49m \033[38;2;235;205;18;48;2;173;142;75m▀\033[38;2;182;146;34;48;2;62;71;128m▀\033[48;2;149;110;45m▀\033[38;2;235;205;18;48;2;182;146;34m▀\033[38;2;74;62;49;48;2;0;0;0m▀ ▀\033[38;2;247;239;232;48;2;129;112;125m▀\033[38;2;234;220;217m▀\033[38;2;149;110;45;48;2;96;80;84m▀\033[48;2;129;96;77m▀\033[38;2;129;96;77;48;2;125;121;165m▀\033[38;2;19;61;178;48;2;13;69;208m▀\033[38;2;96;80;84;48;2;62;71;128m▀\033[38;2;149;110;45;48;2;214;198;196m▀\033[38;2;214;198;196;48;2;247;239;232m▀   \033[38;2;247;239;232;48;2;234;220;217m▀  ▀\033[38;2;125;121;165;48;2;214;198;196m▀\033[38;2;13;69;208;48;2;18;48;139m▀\033[48;2;19;61;178m \033[38;2;18;48;139;48;2;14;31;87m▀▀\033[48;2;18;48;139m \033[48;2;14;31;87m \033[0m
\033[48;2;74;62;49m \033[48;2;66;86;177m \033[48;2;13;69;208m \033[48;2;18;48;139m \033[38;2;0;0;0;48;2;66;61;77m▀\033[38;2;96;80;84;48;2;173;163;168m▀\033[38;2;129;112;125m▀\033[38;2;0;0;0m▀\033[38;2;96;80;84m▀\033[38;2;214;198;196;48;2;247;239;232m▀\033[38;2;191;153;144;48;2;214;198;196m▀\033[38;2;95;89;119;48;2;66;61;77m▀\033[38;2;66;86;177;48;2;18;48;139m▀\033[48;2;19;61;178m▀\033[38;2;18;48;139;48;2;14;31;87m▀\033[38;2;214;198;196;48;2;156;143;144m▀\033[48;2;247;239;232m    \033[48;2;234;220;217m     \033[38;2;125;121;165m▀\033[38;2;18;48;139;48;2;62;71;128m▀\033[48;2;18;48;139m \033[48;2;19;61;178m \033[48;2;18;48;139m \033[38;2;18;48;139;48;2;19;61;178m▀\033[48;2;14;31;87m \033[0m
\033[38;2;66;61;77;48;2;96;80;84m▀\033[38;2;14;31;87;48;2;173;163;168m▀\033[38;2;19;61;178;48;2;96;80;84m▀\033[38;2;18;48;139;48;2;66;61;77m▀\033[38;2;156;143;144;48;2;95;89;119m▀\033[48;2;173;163;168m   \033[38;2;173;163;168;48;2;214;198;196m▀\033[38;2;247;239;232m▀\033[48;2;234;220;217m▀\033[38;2;214;198;196m▀\033[38;2;173;163;168m▀\033[38;2;14;31;87;48;2;129;112;125m▀\033[38;2;95;89;119;48;2;234;220;217m▀\033[38;2;173;163;168;48;2;247;239;232m▀    \033[38;2;247;239;232;48;2;234;220;217m▀    \033[38;2;234;220;217;48;2;214;198;196m▀\033[38;2;156;143;144;48;2;234;220;217m▀\033[38;2;14;31;87;48;2;95;89;119m▀\033[38;2;19;61;178;48;2;18;48;139m▀ \033[38;2;18;48;139;48;2;19;61;178m▀\033[48;2;14;31;87m \033[0m
\033[38;2;95;89;119;48;2;152;131;127m▀\033[38;2;214;198;196;48;2;247;239;232m▀\033[38;2;173;163;168;48;2;152;131;127m▀\033[48;2;156;143;144m▀\033[48;2;173;163;168m \033[38;2;214;198;196;48;2;234;220;217m▀\033[38;2;234;220;217;48;2;247;239;232m▀  \033[38;2;214;198;196;48;2;234;220;217m▀\033[38;2;234;220;217;48;2;214;198;196m▀\033[48;2;247;239;232m \033[38;2;234;220;217m▀\033[38;2;173;163;168;48;2;129;112;125m▀\033[38;2;247;239;232;48;2;214;198;196m▀\033[48;2;173;163;168m▀\033[48;2;156;143;144m▀\033[48;2;173;163;168m▀▀▀\033[38;2;234;220;217;48;2;214;198;196m▀▀\033[48;2;234;220;217m   \033[48;2;214;198;196m \033[38;2;214;198;196;48;2;234;220;217m▀\033[38;2;156;143;144;48;2;214;198;196m▀\033[38;2;18;48;139;48;2;14;31;87m▀\033[48;2;18;48;139m  \033[48;2;14;31;87m \033[0m
\033[48;2;156;143;144m \033[38;2;234;220;217;48;2;247;239;232m▀\033[38;2;129;112;125m▀\033[38;2;173;163;168m▀\033[38;2;214;198;196m▀\033[38;2;234;220;217m▀\033[38;2;247;239;232;48;2;234;220;217m▀ \033[38;2;234;220;217;48;2;247;239;232m▀\033[38;2;214;198;196m▀\033[38;2;234;220;217m▀  \033[38;2;214;198;196m▀\033[38;2;95;89;119;48;2;234;220;217m▀\033[38;2;150;118;79;48;2;173;163;168m▀\033[38;2;198;133;97;48;2;214;198;196m▀\033[38;2;96;80;84;48;2;62;71;128m▀\033[38;2;152;131;127;48;2;18;48;139m▀\033[48;2;62;71;128m▀\033[48;2;95;89;119m▀\033[38;2;129;112;125m▀\033[38;2;129;96;77;48;2;66;86;177m▀\033[38;2;152;131;127;48;2;18;48;139m▀\033[38;2;214;198;196m▀\033[38;2;173;163;168m▀\033[38;2;152;131;127;48;2;19;61;178m▀\033[38;2;95;89;119;48;2;18;48;139m▀\033[38;2;18;48;139;48;2;19;61;178m▀\033[48;2;18;48;139m \033[38;2;18;48;139;48;2;19;61;178m▀\033[48;2;14;31;87m \033[0m
\033[38;2;74;62;49;48;2;0;0;0m▀\033[38;2;66;61;77m▀▀▀▀▀▀▀▀▀▀▀▀▀▀\033[38;2;74;62;49m▀▀\033[38;2;14;31;87m▀▀▀▀▀▀▀▀▀▀▀▀▀▀\033[0m
==>38-animegirl
\033[48;2;0;0;0m                  \033[38;2;0;0;0;48;2;200;128;61m▀\033[38;2;58;37;19;48;2;198;112;36m▀\033[48;2;165;88;28m▀\033[38;2;0;0;0;48;2;83;49;22m▀\033[48;2;58;37;19m▀\033[0m
\033[48;2;0;0;0m                \033[38;2;0;0;0;48;2;128;93;64m▀\033[38;2;200;128;61;48;2;238;156;64m▀\033[38;2;238;156;64;48;2;230;139;44m▀\033[38;2;230;139;44;48;2;198;112;36m▀\033[48;2;200;128;61m▀\033[38;2;198;112;36;48;2;230;139;44m▀▀\033[38;2;128;93;64;48;2;163;106;54m▀\033[38;2;101;104;109;48;2;160;152;142m▀\033[38;2;0;0;0;48;2;101;104;109m▀\033[48;2;54;50;48m▀\033[0m
\033[48;2;0;0;0m            \033[38;2;0;0;0;48;2;163;106;54m▀\033[38;2;58;37;19;48;2;200;128;61m▀\033[38;2;108;76;50;48;2;83;49;22m▀\033[38;2;58;37;19;48;2;134;78;33m▀\033[38;2;238;156;64;48;2;198;112;36m▀\033[48;2;201;149;103m▀\033[38;2;198;112;36;48;2;101;104;109m▀\033[38;2;163;106;54m▀\033[48;2;201;149;103m▀\033[38;2;230;139;44;48;2;200;128;61m▀\033[38;2;238;156;64;48;2;198;112;36m▀\033[38;2;165;88;28;48;2;230;139;44m▀\033[38;2;134;78;33;48;2;163;106;54m▀\033[48;2;101;104;109m \033[38;2;54;50;48;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m      \033[38;2;0;0;0;48;2;83;49;22m▀\033[38;2;58;37;19;48;2;201;149;103m▀\033[38;2;137;123;108;48;2;223;174;121m▀\033[38;2;160;152;142;48;2;232;167;93m▀\033[38;2;200;128;61;48;2;238;156;64m▀\033[48;2;163;106;54m▀\033[48;2;230;139;44m \033[38;2;200;128;61m▀\033[38;2;163;106;54;48;2;165;88;28m▀\033[38;2;165;88;28;48;2;185;142;104m▀\033[38;2;134;78;33;48;2;186;162;134m▀\033[38;2;201;149;103m▀\033[38;2;181;178;173;48;2;221;213;186m▀ \033[38;2;224;196;158;48;2;181;178;173m▀\033[38;2;137;123;108;48;2;101;104;109m▀\033[38;2;163;106;54;48;2;198;112;36m▀\033[38;2;198;112;36;48;2;134;78;33m▀\033[38;2;88;63;49;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;128;93;64m▀\033[38;2;83;49;22;48;2;232;167;93m▀\033[38;2;54;50;48;48;2;230;139;44m▀\033[38;2;134;78;33;48;2;238;156;64m▀\033[38;2;238;156;64;48;2;230;139;44m▀\033[38;2;230;139;44;48;2;201;149;103m▀\033[38;2;198;112;36;48;2;230;139;44m▀\033[38;2;230;139;44;48;2;232;167;93m▀\033[38;2;198;112;36;48;2;205;178;150m▀\033[48;2;200;128;61m▀\033[38;2;230;139;44;48;2;232;167;93m▀\033[38;2;200;128;61;48;2;229;233;214m▀\033[38;2;207;193;171;48;2;221;213;186m▀\033[38;2;229;233;214m▀\033[48;2;229;233;214m \033[48;2;221;213;186m \033[38;2;223;174;121;48;2;200;128;61m▀\033[48;2;83;49;22m▀\033[38;2;182;165;147;48;2;0;0;0m▀\033[38;2;128;93;64;48;2;54;50;48m▀\033[38;2;108;76;50m▀\033[38;2;58;37;19;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m \033[48;2;58;37;19m \033[38;2;232;167;93;48;2;224;196;158m▀\033[38;2;200;128;61;48;2;232;167;93m▀\033[38;2;238;156;64;48;2;205;178;150m▀\033[38;2;223;174;121;48;2;181;178;173m▀\033[38;2;186;162;134;48;2;223;174;121m▀\033[38;2;232;167;93;48;2;230;139;44m▀\033[48;2;238;156;64m \033[38;2;221;213;186;48;2;207;211;199m▀\033[38;2;207;193;171;48;2;205;178;150m▀\033[38;2;201;149;103;48;2;232;167;93m▀ \033[38;2;229;233;214;48;2;186;162;134m▀\033[38;2;207;193;171;48;2;205;178;150m▀\033[48;2;221;213;186m \033[38;2;229;233;214m▀▀\033[38;2;205;178;150;48;2;229;233;214m▀\033[38;2;165;88;28;48;2;185;142;104m▀\033[38;2;108;76;50;48;2;165;88;28m▀\033[38;2;54;50;48;48;2;182;165;147m▀\033[38;2;0;0;0;48;2;181;178;173m▀\033[48;2;108;76;50m▀\033[0m
\033[48;2;0;0;0m \033[38;2;54;50;48m▀\033[38;2;181;178;173;48;2;58;37;19m▀\033[38;2;232;167;93;48;2;201;149;103m▀\033[38;2;207;211;199;48;2;221;213;186m▀\033[48;2;207;193;171m  \033[38;2;200;128;61;48;2;238;156;64m▀\033[48;2;232;167;93m \033[38;2;221;213;186;48;2;224;196;158m▀\033[38;2;229;233;214;48;2;207;211;199m▀\033[38;2;201;149;103;48;2;221;213;186m▀\033[38;2;83;49;22;48;2;58;37;19m▀\033[48;2;0;0;0m▀\033[38;2;232;167;93;48;2;58;37;19m▀\033[38;2;224;196;158;48;2;108;76;50m▀\033[38;2;221;213;186;48;2;186;162;134m▀\033[48;2;221;213;186m \033[38;2;229;233;214m▀\033[38;2;181;178;173;48;2;207;211;199m▀\033[38;2;186;162;134;48;2;229;233;214m▀\033[38;2;128;93;64;48;2;163;106;54m▀\033[38;2;182;165;147;48;2;58;37;19m▀\033[38;2;229;233;214;48;2;205;178;150m▀\033[48;2;108;76;50m \033[0m
\033[48;2;0;0;0m   \033[38;2;88;63;49m▀\033[38;2;224;196;158;48;2;58;37;19m▀\033[38;2;160;152;142m▀\033[48;2;0;0;0m \033[38;2;201;149;103;48;2;108;76;50m▀\033[38;2;238;156;64;48;2;224;196;158m▀\033[38;2;232;167;93;48;2;238;156;64m▀\033[38;2;207;211;199;48;2;232;167;93m▀\033[38;2;229;233;214;48;2;221;213;186m▀\033[38;2;182;165;147;48;2;229;233;214m▀\033[38;2;0;0;0;48;2;108;76;50m▀\033[48;2;0;0;0m   \033[38;2;137;123;108;48;2;54;50;48m▀\033[38;2;229;233;214m▀\033[38;2;207;193;171;48;2;0;0;0m▀\033[38;2;137;123;108m▀\033[38;2;163;106;54;48;2;108;76;50m▀\033[48;2;58;37;19m \033[38;2;108;76;50;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m        \033[38;2;128;93;64m▀\033[38;2;137;123;108;48;2;58;37;19m▀\033[38;2;198;112;36;48;2;0;0;0m▀\033[38;2;238;156;64;48;2;83;49;22m▀\033[38;2;223;174;121;48;2;198;112;36m▀\033[38;2;186;162;134;48;2;232;167;93m▀\033[38;2;0;0;0;48;2;54;50;48m▀\033[48;2;0;0;0m   \033[38;2;58;37;19;48;2;54;50;48m▀\033[48;2;0;0;0m \033[38;2;58;37;19m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;58;37;19m▀    ▀\033[38;2;0;0;0;48;2;58;37;19m▀\033[48;2;0;0;0m \033[38;2;134;78;33m▀\033[38;2;198;112;36;48;2;163;106;54m▀\033[38;2;0;0;0;48;2;108;76;50m▀\033[38;2;58;37;19;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m               \033[38;2;134;78;33;48;2;58;37;19m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;58;37;19m▀  ▀       \033[38;2;0;0;0;48;2;58;37;19m▀▀\033[0m
\033[48;2;0;0;0m \033[38;2;58;37;19m▀ \033[38;2;0;0;0;48;2;58;37;19m▀\033[48;2;0;0;0m                   \033[38;2;0;0;0;48;2;58;37;19m▀\033[0m
\033[0m
\033[38;2;0;0;0;48;2;58;37;19m▀▀\033[48;2;0;0;0m         \033[38;2;58;37;19m▀▀\033[38;2;0;0;0;48;2;58;37;19m▀▀▀\033[0m
==>39-animegirl-teal
\033[48;2;0;0;0m                   \033[38;2;0;0;0;48;2;15;19;28m▀\033[0m
\033[48;2;0;0;0m                   \033[38;2;0;0;0;48;2;15;19;28m▀\033[48;2;30;65;191m▀\033[38;2;17;24;43;48;2;65;89;197m▀\033[48;2;26;54;145m▀\033[38;2;0;0;0;48;2;34;50;109m▀\033[48;2;17;24;43m▀\033[0m
\033[48;2;0;0;0m                  \033[38;2;0;0;0;48;2;69;78;120m▀\033[38;2;65;89;197;48;2;101;118;221m▀\033[48;2;66;91;226m \033[38;2;65;89;197;48;2;38;69;225m▀\033[38;2;66;91;226;48;2;65;89;197m▀\033[38;2;38;69;225m▀\033[38;2;30;65;191;48;2;66;91;226m▀\033[38;2;26;54;145;48;2;30;65;191m▀\033[38;2;99;102;123;48;2;107;115;164m▀\033[38;2;15;19;28;48;2;69;78;120m▀\033[38;2;0;0;0;48;2;33;36;45m▀\033[0m
\033[48;2;0;0;0m             \033[38;2;0;0;0;48;2;15;19;28m▀\033[48;2;69;78;120m▀\033[38;2;33;36;45;48;2;65;89;197m▀\033[38;2;34;50;109;48;2;31;41;82m▀\033[38;2;33;36;45;48;2;52;71;155m▀\033[38;2;66;91;226;48;2;38;69;225m▀\033[48;2;107;115;164m▀\033[38;2;52;71;155;48;2;69;78;120m▀\033[38;2;69;78;120;48;2;99;102;123m▀\033[38;2;99;102;123;48;2;107;115;164m▀\033[38;2;65;89;197m▀\033[38;2;101;118;221;48;2;65;89;197m▀\033[38;2;65;89;197;48;2;66;91;226m▀\033[38;2;6;25;83;48;2;52;71;155m▀\033[38;2;52;71;155;48;2;60;65;87m▀\033[38;2;60;65;87;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m        \033[38;2;0;0;0;48;2;31;41;82m▀\033[38;2;15;19;28;48;2;101;118;221m▀\033[38;2;99;102;123m▀\033[38;2;132;143;182;48;2;66;91;226m▀\033[38;2;65;89;197m▀\033[38;2;107;115;164;48;2;30;65;191m▀\033[48;2;38;69;225m \033[38;2;30;65;191m▀\033[48;2;30;65;191m \033[38;2;30;65;191;48;2;107;115;164m▀\033[38;2;26;54;145;48;2;151;143;135m▀\033[38;2;151;143;135;48;2;193;186;171m▀\033[38;2;193;186;171;48;2;225;214;179m▀▀\033[48;2;193;186;171m \033[38;2;151;143;135;48;2;107;115;164m▀\033[38;2;52;71;155;48;2;30;65;191m▀\033[38;2;30;65;191;48;2;26;54;145m▀\033[38;2;34;50;109;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m    \033[38;2;0;0;0;48;2;69;78;120m▀\033[38;2;31;41;82;48;2;101;118;221m▀\033[48;2;38;69;225m▀\033[38;2;26;54;145m▀\033[48;2;66;91;226m \033[38;2;38;69;225m▀\033[48;2;38;69;225m \033[38;2;38;69;225;48;2;101;118;221m▀\033[38;2;30;65;191;48;2;127;142;215m▀\033[38;2;38;69;225;48;2;66;91;226m▀\033[48;2;101;118;221m▀\033[38;2;65;89;197;48;2;225;214;179m▀\033[38;2;193;186;171m▀\033[38;2;224;222;198m▀▀\033[48;2;224;222;198m \033[38;2;193;186;171;48;2;65;89;197m▀\033[48;2;34;50;109m▀\033[38;2;151;143;135;48;2;0;0;0m▀\033[38;2;69;78;120;48;2;33;36;45m▀\033[38;2;26;54;145;48;2;17;24;43m▀\033[38;2;15;19;28;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m   \033[38;2;17;24;43;48;2;14;24;58m▀\033[38;2;101;118;221;48;2;167;182;226m▀\033[48;2;101;118;221m \033[38;2;66;91;226;48;2;127;142;215m▀\033[38;2;101;118;221;48;2;169;179;203m▀\033[38;2;127;142;215;48;2;101;118;221m▀\033[38;2;101;118;221;48;2;38;69;225m▀\033[38;2;38;69;225;48;2;66;91;226m▀\033[48;2;196;201;205m \033[48;2;127;142;215m \033[48;2;101;118;221m \033[38;2;127;142;215m▀\033[38;2;225;214;179;48;2;151;143;135m▀\033[48;2;193;186;171m▀▀\033[48;2;225;214;179m \033[38;2;225;214;179;48;2;224;222;198m▀\033[38;2;127;142;215;48;2;231;237;223m▀\033[38;2;30;65;191;48;2;66;91;226m▀\033[38;2;60;65;87;48;2;26;54;145m▀\033[38;2;33;36;45;48;2;127;142;215m▀\033[38;2;0;0;0;48;2;169;179;203m▀\033[48;2;69;78;120m▀\033[0m
\033[48;2;0;0;0m   \033[38;2;33;36;45m▀\033[38;2;169;179;203;48;2;17;24;43m▀\033[38;2;101;118;221;48;2;127;142;215m▀\033[38;2;196;201;205;48;2;224;222;198m▀\033[38;2;167;182;226;48;2;193;186;171m▀\033[48;2;169;179;203m▀\033[38;2;38;69;225;48;2;66;91;226m▀\033[38;2;101;118;221m▀\033[48;2;196;201;205m \033[38;2;231;237;223;48;2;225;214;179m▀\033[38;2;132;143;182;48;2;224;222;198m▀\033[38;2;6;25;83;48;2;33;36;45m▀\033[38;2;14;24;58;48;2;0;0;0m▀\033[38;2;132;143;182;48;2;6;25;83m▀\033[38;2;196;201;205;48;2;69;78;120m▀\033[38;2;225;214;179;48;2;151;143;135m▀\033[48;2;224;222;198m \033[38;2;224;222;198;48;2;225;214;179m▀\033[38;2;169;179;203;48;2;196;201;205m▀\033[38;2;132;143;182;48;2;231;237;223m▀\033[38;2;69;78;120;48;2;99;102;123m▀\033[38;2;132;143;182;48;2;14;24;58m▀\033[38;2;231;237;223;48;2;167;182;226m▀\033[48;2;34;50;109m \033[0m
\033[48;2;0;0;0m \033[38;2;15;19;28m▀   \033[38;2;34;50;109m▀\033[38;2;193;186;171;48;2;33;36;45m▀\033[38;2;151;143;135m▀\033[48;2;0;0;0m \033[38;2;132;143;182;48;2;60;65;87m▀\033[38;2;66;91;226;48;2;127;142;215m▀\033[38;2;101;118;221;48;2;66;91;226m▀\033[38;2;196;201;205;48;2;101;118;221m▀\033[38;2;231;237;223;48;2;196;201;205m▀\033[38;2;132;143;182;48;2;231;237;223m▀\033[38;2;0;0;0;48;2;60;65;87m▀\033[48;2;0;0;0m   \033[38;2;99;102;123;48;2;33;36;45m▀\033[38;2;231;237;223m▀\033[38;2;193;186;171;48;2;15;19;28m▀\033[38;2;99;102;123m▀\033[38;2;52;71;155;48;2;34;50;109m▀\033[38;2;33;36;45;48;2;15;19;28m▀\033[38;2;34;50;109;48;2;0;0;0m▀\033[48;2;15;19;28m \033[0m
\033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;15;19;28m▀▀\033[48;2;0;0;0m      \033[38;2;69;78;120;48;2;15;19;28m▀\033[38;2;107;115;164;48;2;17;24;43m▀\033[38;2;38;69;225;48;2;33;36;45m▀\033[48;2;31;41;82m▀\033[38;2;127;142;215;48;2;30;65;191m▀\033[38;2;193;186;171;48;2;101;118;221m▀\033[38;2;15;19;28;48;2;33;36;45m▀\033[48;2;0;0;0m  \033[48;2;15;19;28m \033[38;2;33;36;45;48;2;60;65;87m▀\033[38;2;15;19;28;48;2;0;0;0m▀\033[48;2;15;19;28m \033[38;2;15;19;28;48;2;0;0;0m▀\033[38;2;0;0;0;48;2;15;19;28m▀\033[0m
\033[48;2;0;0;0m      \033[38;2;15;19;28m▀\033[48;2;15;19;28m \033[48;2;0;0;0m   \033[38;2;15;19;28m▀\033[48;2;15;19;28m \033[38;2;0;0;0;48;2;33;36;45m▀\033[48;2;0;0;0m \033[38;2;26;54;145m▀\033[38;2;30;65;191;48;2;26;54;145m▀\033[38;2;0;0;0;48;2;34;50;109m▀\033[38;2;33;36;45;48;2;0;0;0m▀\033[38;2;15;19;28m▀ ▀\033[0m
\033[48;2;0;0;0m                 \033[38;2;34;50;109;48;2;15;19;28m▀\033[0m
\033[38;2;0;0;0;48;2;15;19;28m▀▀\033[48;2;0;0;0m     \033[38;2;15;19;28m▀ ▀\033[38;2;33;36;45;48;2;15;19;28m▀\033[48;2;0;0;0m      \033[38;2;15;19;28m▀\033[48;2;15;19;28m \033[38;2;0;0;0;48;2;17;24;43m▀\033[48;2;0;0;0m         \033[38;2;15;19;28m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;15;19;28m▀▀ \033[38;2;0;0;0;48;2;15;19;28m▀▀\033[48;2;0;0;0m  \033[38;2;15;19;28m▀     \033[38;2;0;0;0;48;2;15;19;28m▀\033[48;2;0;0;0m        \033[38;2;0;0;0;48;2;15;19;28m▀▀\033[0m
\033[48;2;0;0;0m              \033[38;2;15;19;28m▀           ▀▀▀\033[0m
\033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;15;19;28m▀▀\033[48;2;0;0;0m        \033[38;2;0;0;0;48;2;15;19;28m▀\033[38;2;15;19;28;48;2;0;0;0m▀ \033[38;2;0;0;0;48;2;15;19;28m▀\033[48;2;17;24;43m▀\033[48;2;15;19;28m▀\033[0m
==>40-animegirl-lime
\033[48;2;0;0;0m                 \033[38;2;0;0;0;48;2;63;196;65m▀\033[38;2;18;47;18m▀\033[48;2;31;139;28m▀\033[38;2;0;0;0m▀\033[48;2;18;47;18m▀\033[0m
\033[48;2;0;0;0m               \033[38;2;0;0;0;48;2;72;121;73m▀\033[38;2;66;171;63;48;2;116;229;116m▀\033[48;2;69;226;78m \033[38;2;49;220;57;48;2;37;207;42m▀\033[38;2;69;226;78;48;2;63;196;65m▀\033[38;2;49;220;57;48;2;69;226;78m▀\033[38;2;36;180;35m▀\033[38;2;31;139;28;48;2;66;171;63m▀\033[38;2;101;128;101;48;2;125;148;114m▀\033[38;2;18;47;18;48;2;69;80;89m▀\033[38;2;0;0;0;48;2;33;49;34m▀\033[0m
\033[48;2;0;0;0m          \033[38;2;0;0;0;48;2;18;47;18m▀\033[48;2;72;121;73m▀\033[38;2;33;49;34;48;2;63;196;65m▀\033[38;2;54;121;50;48;2;20;70;21m▀\033[38;2;33;49;34;48;2;31;139;28m▀\033[38;2;49;220;57;48;2;37;207;42m▀\033[38;2;69;226;78;48;2;123;191;109m▀\033[38;2;66;171;63;48;2;69;80;89m▀\033[38;2;101;128;101;48;2;125;148;114m▀\033[48;2;94;152;89m \033[38;2;69;226;78m▀\033[38;2;97;224;102;48;2;49;220;57m▀\033[38;2;66;171;63m▀\033[38;2;20;70;21;48;2;66;171;63m▀\033[38;2;101;128;101;48;2;69;80;89m▀\033[38;2;48;61;54;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;0;0;0;48;2;20;70;21m▀\033[38;2;18;47;18;48;2;97;224;102m▀\033[38;2;125;148;114;48;2;116;229;116m▀\033[38;2;143;195;138;48;2;97;224;102m▀\033[38;2;63;196;65;48;2;69;226;78m▀\033[38;2;97;224;102;48;2;37;207;42m▀\033[48;2;49;220;57m \033[38;2;36;180;35;48;2;37;207;42m▀\033[38;2;63;196;65;48;2;36;180;35m▀\033[38;2;36;180;35;48;2;123;191;109m▀\033[38;2;31;139;28;48;2;143;195;138m▀\033[38;2;177;190;153m▀\033[48;2;229;219;190m▀\033[38;2;193;210;167m▀\033[48;2;193;210;167m \033[38;2;125;148;114;48;2;101;128;101m▀\033[38;2;66;171;63;48;2;36;180;35m▀\033[48;2;31;139;28m▀\033[38;2;36;113;34;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;72;121;73m▀\033[38;2;20;70;21;48;2;116;229;116m▀\033[48;2;49;220;57m▀\033[38;2;31;139;28m▀\033[48;2;69;226;78m \033[38;2;69;226;78;48;2;97;224;102m▀\033[38;2;37;207;42;48;2;49;220;57m▀\033[48;2;116;229;116m▀\033[48;2;142;222;141m▀\033[48;2;69;226;78m▀\033[38;2;49;220;57;48;2;116;229;116m▀\033[38;2;63;196;65;48;2;229;219;190m▀\033[38;2;193;210;167m▀\033[38;2;229;219;190;48;2;223;214;174m▀▀\033[38;2;207;222;194;48;2;231;233;217m▀\033[38;2;177;190;153;48;2;63;196;65m▀\033[38;2;193;210;167;48;2;36;113;34m▀\033[38;2;143;195;138;48;2;0;0;0m▀\033[38;2;94;152;89;48;2;33;49;34m▀\033[38;2;36;113;34;48;2;18;47;18m▀\033[38;2;18;47;18;48;2;0;0;0m▀\033[0m
\033[48;2;18;47;18m \033[38;2;116;229;116;48;2;175;220;165m▀\033[48;2;97;224;102m \033[38;2;69;226;78;48;2;142;222;141m▀\033[38;2;116;229;116;48;2;177;190;153m▀\033[38;2;142;222;141;48;2;143;195;138m▀\033[38;2;97;224;102;48;2;69;226;78m▀\033[38;2;49;220;57m▀\033[38;2;175;220;165;48;2;207;222;194m▀\033[48;2;142;222;141m▀\033[48;2;97;224;102m \033[38;2;116;229;116m▀\033[38;2;229;219;190;48;2;143;195;138m▀\033[38;2;223;214;174;48;2;177;190;153m▀\033[48;2;193;210;167m▀\033[48;2;223;214;174m \033[48;2;229;219;190m \033[38;2;142;222;141;48;2;231;233;217m▀\033[38;2;36;180;35;48;2;97;224;102m▀\033[38;2;54;121;50;48;2;31;139;28m▀\033[38;2;33;49;34;48;2;143;195;138m▀\033[38;2;0;0;0;48;2;177;190;153m▀\033[48;2;72;121;73m▀\033[0m
\033[38;2;48;61;54;48;2;0;0;0m▀\033[38;2;177;190;153;48;2;18;47;18m▀\033[38;2;116;229;116;48;2;123;191;109m▀\033[38;2;207;222;194;48;2;229;219;190m▀\033[38;2;175;220;165;48;2;193;210;167m▀▀\033[48;2;69;226;78m \033[38;2;116;229;116;48;2;97;224;102m▀\033[38;2;207;222;194;48;2;175;220;165m▀\033[38;2;231;233;217;48;2;229;219;190m▀\033[38;2;125;148;114;48;2;207;222;194m▀\033[38;2;20;70;21;48;2;33;49;34m▀\033[48;2;0;0;0m▀\033[38;2;123;191;109;48;2;20;70;21m▀\033[38;2;175;220;165;48;2;54;121;50m▀\033[38;2;193;210;167;48;2;143;195;138m▀\033[48;2;207;222;194m \033[38;2;231;233;217;48;2;223;214;174m▀\033[48;2;207;222;194m \033[38;2;143;195;138;48;2;231;233;217m▀\033[38;2;54;121;50;48;2;101;128;101m▀\033[38;2;142;222;141;48;2;18;47;18m▀\033[38;2;231;233;217;48;2;175;220;165m▀\033[48;2;54;121;50m \033[0m
\033[48;2;0;0;0m  \033[38;2;54;121;50m▀\033[38;2;193;210;167;48;2;33;49;34m▀\033[38;2;125;148;114;48;2;18;47;18m▀\033[48;2;0;0;0m \033[38;2;123;191;109;48;2;72;121;73m▀\033[38;2;69;226;78;48;2;142;222;141m▀\033[38;2;97;224;102;48;2;69;226;78m▀\033[38;2;207;222;194;48;2;97;224;102m▀\033[38;2;231;233;217;48;2;175;220;165m▀\033[38;2;143;195;138;48;2;231;233;217m▀\033[38;2;0;0;0;48;2;54;121;50m▀\033[48;2;0;0;0m   \033[38;2;101;128;101;48;2;33;49;34m▀\033[38;2;231;233;217m▀\033[38;2;229;219;190;48;2;18;47;18m▀\033[38;2;125;148;114;48;2;33;49;34m▀\033[38;2;66;171;63;48;2;36;113;34m▀\033[38;2;33;49;34;48;2;18;47;18m▀\033[38;2;54;121;50;48;2;0;0;0m▀\033[38;2;18;47;18m▀\033[0m
\033[48;2;0;0;0m       \033[38;2;101;128;101m▀\033[38;2;94;152;89;48;2;33;49;34m▀\033[38;2;49;220;57;48;2;18;47;18m▀\033[48;2;20;70;21m▀\033[38;2;142;222;141;48;2;36;180;35m▀\033[38;2;177;190;153;48;2;116;229;116m▀\033[38;2;0;0;0;48;2;33;49;34m▀\033[48;2;0;0;0m  \033[38;2;18;47;18m▀\033[38;2;33;49;34;48;2;48;61;54m▀\033[48;2;0;0;0m \033[38;2;18;47;18m▀▀\033[0m
\033[48;2;0;0;0m    \033[48;2;18;47;18m \033[48;2;0;0;0m   \033[38;2;18;47;18m▀▀\033[38;2;0;0;0;48;2;18;47;18m▀\033[48;2;0;0;0m \033[38;2;31;139;28m▀\033[38;2;66;171;63;48;2;31;139;28m▀\033[38;2;0;0;0;48;2;36;113;34m▀\033[38;2;33;49;34;48;2;0;0;0m▀\033[38;2;18;47;18m▀ ▀\033[0m
\033[48;2;0;0;0m              \033[38;2;36;113;34;48;2;18;47;18m▀\033[0m
\033[48;2;0;0;0m    \033[38;2;33;49;34m▀  ▀      \033[38;2;18;47;18m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;33;49;34m▀\033[48;2;18;47;18m▀\033[48;2;0;0;0m                  \033[38;2;0;0;0;48;2;18;47;18m▀\033[0m
\033[48;2;0;0;0m           \033[38;2;18;47;18m▀           ▀▀\033[0m
\033[38;2;0;0;0;48;2;18;47;18m▀\033[48;2;0;0;0m         \033[38;2;18;47;18m▀\033[0m
==>41-moemiku
\033[48;2;0;0;0m          \033[38;2;0;0;0;48;2;61;65;65m▀\033[48;2;79;96;104m▀\033[48;2;112;158;180m▀\033[38;2;25;28;30;48;2;225;227;229m▀\033[38;2;89;89;92;48;2;252;250;250m▀\033[38;2;106;107;108m▀\033[38;2;48;47;47;48;2;240;239;239m▀\033[38;2;25;28;30;48;2;118;181;205m▀\033[48;2;44;130;178m▀\033[38;2;0;0;0;48;2;61;65;65m▀\033[0m
\033[48;2;0;0;0m        \033[38;2;0;0;0;48;2;89;89;92m▀\033[38;2;122;124;124;48;2;252;250;250m▀\033[38;2;240;239;239;48;2;225;227;229m▀\033[38;2;200;202;203;48;2;252;250;250m▀\033[38;2;225;227;229m▀ \033[38;2;240;239;239m▀▀▀▀\033[38;2;225;227;229m▀\033[48;2;240;239;239m \033[38;2;106;107;108m▀\033[38;2;0;0;0;48;2;89;89;92m▀\033[0m
\033[48;2;0;0;0m        \033[38;2;151;153;154;48;2;164;168;170m▀\033[38;2;240;239;239;48;2;225;227;229m▀ \033[48;2;252;250;250m \033[38;2;252;250;250;48;2;225;227;229m▀\033[38;2;240;239;239;48;2;173;175;176m▀\033[38;2;252;250;250;48;2;240;239;239m▀▀▀▀▀\033[38;2;240;239;239;48;2;225;227;229m▀\033[38;2;225;227;229;48;2;215;212;213m▀\033[38;2;140;141;141;48;2;151;153;154m▀\033[0m
\033[48;2;0;0;0m        \033[48;2;200;202;203m \033[48;2;215;212;213m \033[38;2;225;227;229;48;2;240;239;239m▀\033[38;2;240;239;239;48;2;225;227;229m▀\033[38;2;215;212;213;48;2;158;159;160m▀\033[38;2;225;227;229;48;2;173;175;176m▀\033[38;2;215;212;213;48;2;187;186;187m▀\033[38;2;252;250;250;48;2;240;239;239m▀\033[38;2;215;212;213;48;2;158;159;160m▀\033[38;2;187;186;187;48;2;140;141;141m▀\033[38;2;240;239;239;48;2;225;227;229m▀\033[48;2;252;250;250m▀\033[38;2;225;227;229;48;2;215;212;213m▀\033[48;2;187;186;187m \033[0m
\033[48;2;0;0;0m        \033[48;2;200;202;203m \033[38;2;215;212;213;48;2;225;227;229m▀\033[48;2;215;212;213m \033[38;2;225;227;229m▀\033[38;2;162;170;176;48;2;200;202;203m▀\033[38;2;114;144;156;48;2;252;250;250m▀\033[38;2;187;186;187;48;2;200;202;203m▀\033[38;2;200;202;203;48;2;215;212;213m▀\033[38;2;114;144;156;48;2;252;250;250m▀\033[38;2;164;168;170;48;2;215;212;213m▀\033[38;2;225;227;229m▀\033[48;2;200;202;203m▀\033[48;2;215;212;213m \033[38;2;200;202;203;48;2;187;186;187m▀\033[0m
\033[48;2;0;0;0m       \033[48;2;48;47;47m \033[48;2;200;202;203m \033[38;2;215;212;213;48;2;187;186;187m▀\033[48;2;252;250;250m▀\033[48;2;200;202;203m▀\033[38;2;122;124;124;48;2;48;47;47m▀\033[38;2;200;202;203;48;2;61;65;65m▀\033[38;2;252;250;250;48;2;215;212;213m▀\033[48;2;225;227;229m▀\033[38;2;240;239;239;48;2;122;124;124m▀\033[38;2;80;82;83;48;2;0;0;0m▀\033[48;2;164;168;170m \033[38;2;215;212;213;48;2;240;239;239m▀\033[48;2;225;227;229m \033[48;2;215;212;213m \033[48;2;25;28;30m \033[0m
\033[48;2;0;0;0m       \033[38;2;80;82;83;48;2;106;107;108m▀\033[38;2;162;170;176;48;2;252;250;250m▀\033[38;2;173;175;176;48;2;240;239;239m▀\033[38;2;240;239;239;48;2;252;250;250m▀\033[38;2;200;202;203m▀\033[38;2;106;107;108;48;2;225;227;229m▀\033[38;2;200;202;203;48;2;161;208;218m▀\033[38;2;140;141;141;48;2;122;124;124m▀\033[38;2;122;124;124;48;2;146;154;156m▀\033[38;2;140;141;141;48;2;252;250;250m▀\033[38;2;48;47;47;48;2;114;144;156m▀\033[38;2;187;186;187;48;2;173;175;176m▀\033[38;2;252;250;250;48;2;240;239;239m▀\033[38;2;225;227;229;48;2;215;212;213m▀\033[38;2;200;202;203;48;2;187;186;187m▀\033[38;2;25;28;30;48;2;48;47;47m▀\033[0m
\033[48;2;0;0;0m      \033[38;2;0;0;0;48;2;25;28;30m▀\033[38;2;151;153;154;48;2;215;212;213m▀\033[48;2;252;250;250m \033[38;2;240;239;239;48;2;225;227;229m▀▀\033[38;2;252;250;250;48;2;240;239;239m▀▀\033[38;2;215;212;213;48;2;252;250;250m▀\033[38;2;73;72;72;48;2;80;82;83m▀\033[38;2;61;65;65;48;2;140;141;141m▀\033[38;2;118;181;205;48;2;252;250;250m▀\033[38;2;240;239;239m▀ \033[38;2;225;227;229m▀\033[38;2;215;212;213;48;2;225;227;229m▀\033[48;2;187;186;187m \033[38;2;61;65;65;48;2;80;82;83m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;0;0;0;48;2;25;28;30m▀\033[38;2;106;107;108;48;2;173;175;176m▀\033[38;2;240;239;239;48;2;215;212;213m▀\033[48;2;252;250;250m \033[38;2;225;227;229;48;2;240;239;239m▀\033[48;2;252;250;250m   \033[38;2;215;212;213;48;2;164;168;170m▀\033[38;2;48;47;47;48;2;0;0;0m▀\033[38;2;173;175;176;48;2;187;186;187m▀\033[48;2;252;250;250m \033[38;2;252;250;250;48;2;240;239;239m▀\033[38;2;240;239;239;48;2;215;212;213m▀\033[38;2;252;250;250;48;2;240;239;239m▀ \033[38;2;173;175;176;48;2;164;168;170m▀\033[38;2;89;89;92;48;2;122;124;124m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;106;107;108;48;2;164;168;170m▀\033[38;2;122;124;124;48;2;25;28;30m▀\033[38;2;114;144;156;48;2;39;157;196m▀\033[38;2;225;227;229;48;2;118;181;205m▀\033[48;2;215;212;213m▀\033[48;2;225;227;229m \033[38;2;240;239;239m▀\033[38;2;252;250;250;48;2;240;239;239m▀\033[38;2;140;141;141;48;2;89;89;92m▀\033[48;2;0;0;0m \033[38;2;200;202;203;48;2;187;186;187m▀\033[38;2;252;250;250;48;2;240;239;239m▀\033[38;2;240;239;239;48;2;225;227;229m▀ \033[48;2;240;239;239m  \033[38;2;173;175;176;48;2;187;186;187m▀\033[38;2;140;141;141;48;2;146;154;156m▀\033[0m
\033[48;2;0;0;0m    \033[38;2;106;107;108;48;2;158;159;160m▀\033[38;2;89;89;92;48;2;25;28;30m▀\033[38;2;80;82;83;48;2;225;227;229m▀\033[48;2;200;202;203m \033[38;2;215;212;213;48;2;225;227;229m▀▀\033[48;2;215;212;213m \033[38;2;225;227;229;48;2;240;239;239m▀▀\033[38;2;80;82;83;48;2;89;89;92m▀\033[38;2;25;28;30;48;2;0;0;0m▀\033[38;2;173;175;176;48;2;187;186;187m▀\033[38;2;225;227;229;48;2;240;239;239m▀\033[48;2;215;212;213m \033[38;2;92;163;194;48;2;200;202;203m▀\033[38;2;113;196;211;48;2;39;157;196m▀\033[38;2;200;202;203;48;2;92;163;194m▀\033[48;2;215;212;213m▀\033[48;2;158;159;160m \033[38;2;48;47;47;48;2;80;82;83m▀\033[0m
\033[48;2;0;0;0m   \033[38;2;80;82;83;48;2;151;153;154m▀\033[38;2;106;107;108;48;2;48;47;47m▀\033[38;2;89;89;92;48;2;164;168;170m▀\033[38;2;240;239;239;48;2;215;212;213m▀\033[38;2;158;159;160;48;2;225;227;229m▀ \033[38;2;240;239;239;48;2;252;250;250m▀\033[48;2;225;227;229m \033[48;2;252;250;250m  \033[38;2;80;82;83;48;2;73;72;72m▀\033[48;2;25;28;30m \033[48;2;215;212;213m \033[48;2;225;227;229m \033[38;2;225;227;229;48;2;252;250;250m▀\033[48;2;151;153;154m▀\033[38;2;112;158;180;48;2;187;186;187m▀\033[38;2;200;202;203;48;2;215;212;213m▀\033[48;2;240;239;239m \033[48;2;164;168;170m \033[38;2;106;107;108;48;2;122;124;124m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;48;47;47;48;2;106;107;108m▀\033[38;2;146;154;156;48;2;122;124;124m▀\033[38;2;61;65;65;48;2;151;153;154m▀\033[38;2;215;212;213;48;2;252;250;250m▀\033[38;2;240;239;239m▀\033[48;2;225;227;229m▀\033[38;2;225;227;229;48;2;151;153;154m▀\033[48;2;252;250;250m \033[38;2;215;212;213;48;2;225;227;229m▀\033[48;2;252;250;250m  \033[38;2;61;65;65;48;2;200;202;203m▀\033[38;2;25;28;30;48;2;173;175;176m▀\033[38;2;215;212;213;48;2;225;227;229m▀\033[38;2;225;227;229;48;2;240;239;239m▀\033[38;2;252;250;250m▀\033[38;2;61;65;65;48;2;25;28;30m▀\033[38;2;158;159;160;48;2;122;124;124m▀\033[38;2;225;227;229;48;2;252;250;250m▀\033[48;2;187;186;187m▀\033[38;2;200;202;203;48;2;215;212;213m▀\033[38;2;122;124;124;48;2;106;107;108m▀\033[38;2;48;47;47;48;2;89;89;92m▀\033[0m
\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;89;89;92m▀\033[38;2;140;141;141;48;2;158;159;160m▀\033[38;2;48;47;47;48;2;73;72;72m▀\033[38;2;215;212;213;48;2;252;250;250m▀  \033[38;2;173;175;176;48;2;122;124;124m▀\033[38;2;48;47;47;48;2;0;0;0m▀\033[38;2;252;250;250;48;2;240;239;239m▀\033[38;2;200;202;203;48;2;173;175;176m▀\033[38;2;164;168;170;48;2;0;0;0m▀\033[38;2;187;186;187;48;2;25;28;30m▀\033[48;2;0;0;0m▀\033[48;2;25;28;30m▀\033[48;2;215;212;213m \033[48;2;252;250;250m \033[38;2;187;186;187;48;2;106;107;108m▀\033[48;2;0;0;0m \033[38;2;80;82;83;48;2;25;28;30m▀\033[38;2;252;250;250;48;2;240;239;239m▀\033[38;2;225;227;229;48;2;252;250;250m▀\033[38;2;200;202;203;48;2;240;239;239m▀\033[38;2;122;124;124;48;2;140;141;141m▀\033[48;2;106;107;108m \033[38;2;0;0;0;48;2;48;47;47m▀\033[0m
\033[38;2;25;28;30;48;2;61;65;65m▀\033[48;2;122;124;124m \033[38;2;89;89;92;48;2;80;82;83m▀\033[38;2;106;107;108;48;2;140;141;141m▀\033[48;2;252;250;250m \033[38;2;240;239;239m▀\033[38;2;252;250;250;48;2;240;239;239m▀\033[38;2;73;72;72;48;2;48;47;47m▀\033[48;2;0;0;0m \033[38;2;200;202;203;48;2;140;141;141m▀\033[38;2;215;212;213;48;2;240;239;239m▀\033[48;2;25;28;30m  \033[38;2;0;0;0m▀\033[38;2;106;107;108;48;2;187;186;187m▀\033[48;2;252;250;250m \033[38;2;200;202;203;48;2;106;107;108m▀\033[48;2;25;28;30m \033[48;2;0;0;0m  \033[38;2;200;202;203;48;2;151;153;154m▀\033[48;2;252;250;250m \033[38;2;252;250;250;48;2;240;239;239m▀\033[38;2;225;227;229;48;2;252;250;250m▀\033[38;2;158;159;160;48;2;140;141;141m▀\033[48;2;89;89;92m \033[0m
\033[38;2;25;28;30;48;2;61;65;65m▀\033[38;2;151;153;154;48;2;158;159;160m▀\033[38;2;73;72;72;48;2;61;65;65m▀\033[38;2;187;186;187;48;2;252;250;250m▀  \033[38;2;215;212;213;48;2;187;186;187m▀\033[48;2;25;28;30m \033[38;2;0;0;0m▀\033[38;2;140;141;141;48;2;200;202;203m▀\033[38;2;240;239;239;48;2;252;250;250m▀\033[38;2;48;47;47;48;2;122;124;124m▀\033[48;2;0;0;0m \033[38;2;61;65;65;48;2;151;153;154m▀\033[48;2;252;250;250m \033[38;2;187;186;187;48;2;73;72;72m▀\033[48;2;0;0;0m \033[48;2;25;28;30m  \033[48;2;0;0;0m \033[38;2;122;124;124;48;2;89;89;92m▀\033[48;2;252;250;250m \033[38;2;240;239;239m▀ \033[38;2;151;153;154;48;2;200;202;203m▀\033[38;2;122;124;124;48;2;106;107;108m▀\033[38;2;61;65;65;48;2;122;124;124m▀\033[0m
\033[38;2;25;28;30;48;2;0;0;0m▀\033[38;2;48;47;47m▀\033[38;2;25;28;30m▀\033[38;2;73;72;72m▀▀\033[38;2;80;82;83m▀\033[38;2;61;65;65m▀  ▀\033[38;2;73;72;72m▀\033[38;2;48;47;47m▀ \033[38;2;61;65;65m▀\033[38;2;73;72;72m▀\033[38;2;25;28;30m▀    ▀\033[38;2;73;72;72m▀▀▀\033[38;2;61;65;65m▀\033[38;2;25;28;30m▀▀\033[0m
==>42-moemiku-azure
\033[48;2;0;0;0m          \033[38;2;0;0;0;48;2;44;46;48m▀\033[48;2;59;87;109m▀\033[48;2;51;126;169m▀\033[48;2;112;166;215m▀\033[38;2;63;75;83;48;2;120;184;241m▀\033[38;2;59;87;109m▀\033[38;2;44;46;48m▀\033[38;2;24;30;34;48;2;58;148;201m▀\033[48;2;51;126;169m▀\033[38;2;0;0;0;48;2;44;46;48m▀\033[0m
\033[48;2;0;0;0m        \033[38;2;0;0;0;48;2;63;75;83m▀\033[38;2;63;102;135;48;2;120;184;241m▀\033[38;2;120;184;241;48;2;119;168;212m▀\033[38;2;100;155;202;48;2;120;184;241m▀\033[38;2;112;166;215m▀       \033[38;2;59;87;109m▀\033[38;2;0;0;0;48;2;58;62;66m▀\033[0m
\033[48;2;0;0;0m        \033[38;2;95;124;149;48;2;99;141;177m▀\033[38;2;120;184;241;48;2;112;166;215m▀▀\033[48;2;120;184;241m  \033[38;2;120;184;241;48;2;99;141;177m▀\033[48;2;120;184;241m     \033[38;2;120;184;241;48;2;112;166;215m▀▀\033[38;2;63;102;135;48;2;95;124;149m▀\033[0m
\033[48;2;0;0;0m        \033[48;2;100;155;202m \033[38;2;100;155;202;48;2;112;166;215m▀\033[38;2;112;166;215;48;2;120;184;241m▀\033[38;2;120;184;241;48;2;112;166;215m▀\033[38;2;154;178;204;48;2;95;124;149m▀\033[38;2;157;199;235m▀\033[38;2;112;166;215;48;2;99;141;177m▀\033[48;2;120;184;241m \033[38;2;100;155;202;48;2;63;102;135m▀▀\033[38;2;120;184;241;48;2;112;166;215m▀\033[48;2;120;184;241m \033[48;2;112;166;215m \033[48;2;99;141;177m \033[0m
\033[48;2;0;0;0m        \033[48;2;100;155;202m \033[38;2;112;166;215;48;2;120;184;241m▀\033[48;2;100;155;202m▀\033[48;2;112;166;215m \033[38;2;99;141;177m▀\033[38;2;59;87;109;48;2;120;184;241m▀\033[38;2;99;141;177;48;2;119;168;212m▀ \033[38;2;63;102;135;48;2;120;184;241m▀\033[38;2;99;141;177;48;2;112;166;215m▀\033[38;2;112;166;215;48;2;100;155;202m▀▀ \033[38;2;99;141;177m▀\033[0m
\033[48;2;0;0;0m       \033[38;2;24;30;34;48;2;44;46;48m▀\033[38;2;100;155;202;48;2;154;178;204m▀▀\033[48;2;210;220;231m▀\033[48;2;154;178;204m▀\033[38;2;59;87;109;48;2;44;46;48m▀\033[38;2;100;155;202;48;2;58;62;66m▀\033[48;2;157;199;235m \033[38;2;120;184;241;48;2;182;210;238m▀\033[48;2;59;87;109m▀\033[38;2;58;62;66;48;2;0;0;0m▀\033[38;2;51;126;169;48;2;99;141;177m▀\033[38;2;100;155;202;48;2;182;210;238m▀\033[38;2;112;166;215;48;2;157;199;235m▀\033[38;2;100;155;202;48;2;154;178;204m▀\033[48;2;24;30;34m \033[0m
\033[48;2;0;0;0m       \033[38;2;85;85;86;48;2;112;117;122m▀\033[38;2;168;168;170;48;2;249;248;249m▀\033[48;2;230;231;233m▀\033[48;2;249;248;249m \033[38;2;191;194;198m▀\033[38;2;112;117;122;48;2;220;224;230m▀\033[38;2;191;194;198;48;2;157;199;235m▀\033[38;2;145;151;157;48;2;95;124;149m▀\033[48;2;154;178;204m▀\033[48;2;249;248;249m▀\033[38;2;58;62;66;48;2;99;141;177m▀\033[38;2;191;194;198;48;2;168;168;170m▀\033[48;2;249;248;249m \033[38;2;220;224;230;48;2;210;220;231m▀\033[48;2;191;194;198m \033[38;2;44;46;48;48;2;58;62;66m▀\033[0m
\033[48;2;0;0;0m      \033[38;2;0;0;0;48;2;24;30;34m▀\033[38;2;145;151;157;48;2;213;212;214m▀\033[48;2;249;248;249m \033[38;2;249;248;249;48;2;220;224;230m▀\033[48;2;230;231;233m▀▀▀\033[38;2;210;220;231;48;2;249;248;249m▀\033[38;2;58;62;66;48;2;85;85;86m▀\033[48;2;137;138;140m▀\033[38;2;119;168;212;48;2;249;248;249m▀\033[38;2;230;231;233m▀ ▀\033[48;2;213;212;214m \033[38;2;191;194;198;48;2;168;168;170m▀\033[38;2;58;62;66;48;2;71;72;73m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;0;0;0;48;2;24;30;34m▀\033[38;2;105;105;106;48;2;191;194;198m▀\033[38;2;230;231;233;48;2;213;212;214m▀\033[48;2;249;248;249m \033[38;2;220;224;230m▀   \033[48;2;168;168;170m▀\033[38;2;44;46;48;48;2;24;30;34m▀\033[38;2;168;168;170;48;2;191;194;198m▀\033[48;2;249;248;249m  \033[38;2;249;248;249;48;2;213;212;214m▀\033[48;2;230;231;233m▀▀\033[38;2;191;194;198;48;2;168;168;170m▀\033[38;2;105;105;106;48;2;137;138;140m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;112;117;122;48;2;168;168;170m▀\033[48;2;24;30;34m▀\033[38;2;145;151;157;48;2;58;148;201m▀\033[38;2;210;220;231;48;2;119;168;212m▀\033[38;2;230;231;233;48;2;213;212;214m▀\033[48;2;230;231;233m \033[38;2;230;231;233;48;2;220;224;230m▀\033[38;2;249;248;249;48;2;230;231;233m▀\033[38;2;145;151;157;48;2;105;105;106m▀\033[38;2;24;30;34;48;2;0;0;0m▀\033[48;2;191;194;198m \033[48;2;249;248;249m \033[38;2;249;248;249;48;2;230;231;233m▀\033[38;2;230;231;233;48;2;220;224;230m▀\033[48;2;249;248;249m \033[48;2;230;231;233m \033[38;2;168;168;170;48;2;191;194;198m▀\033[48;2;137;138;140m \033[0m
\033[48;2;0;0;0m    \033[38;2;105;105;106;48;2;168;168;170m▀\033[38;2;85;85;86;48;2;44;46;48m▀\033[48;2;213;212;214m▀\033[38;2;157;199;235m▀\033[48;2;230;231;233m \033[38;2;220;224;230m▀\033[48;2;220;224;230m \033[38;2;220;224;230;48;2;230;231;233m▀▀\033[48;2;71;72;73m \033[48;2;0;0;0m \033[48;2;191;194;198m \033[48;2;230;231;233m \033[38;2;210;220;231;48;2;213;212;214m▀\033[38;2;112;166;215;48;2;191;194;198m▀\033[38;2;120;184;241;48;2;58;148;201m▀\033[38;2;191;194;198;48;2;100;155;202m▀\033[38;2;213;212;214;48;2;230;231;233m▀\033[38;2;168;168;170;48;2;145;151;157m▀\033[38;2;44;46;48;48;2;85;85;86m▀\033[0m
\033[48;2;0;0;0m   \033[38;2;85;85;86;48;2;145;151;157m▀\033[38;2;112;117;122;48;2;44;46;48m▀\033[38;2;85;85;86;48;2;168;168;170m▀\033[38;2;249;248;249;48;2;213;212;214m▀\033[38;2;168;168;170;48;2;220;224;230m▀\033[38;2;230;231;233m▀\033[48;2;249;248;249m▀\033[38;2;220;224;230;48;2;230;231;233m▀\033[48;2;249;248;249m  \033[48;2;71;72;73m \033[48;2;24;30;34m \033[38;2;220;224;230;48;2;213;212;214m▀\033[38;2;230;231;233m▀\033[48;2;249;248;249m▀\033[38;2;220;224;230;48;2;145;151;157m▀\033[38;2;99;141;177;48;2;191;194;198m▀\033[38;2;191;194;198;48;2;213;212;214m▀\033[38;2;230;231;233;48;2;249;248;249m▀\033[38;2;168;168;170;48;2;145;151;157m▀\033[38;2;105;105;106;48;2;112;117;122m▀\033[38;2;0;0;0;48;2;24;30;34m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;44;46;48;48;2;105;105;106m▀\033[38;2;137;138;140;48;2;112;117;122m▀\033[38;2;58;62;66;48;2;145;151;157m▀\033[38;2;213;212;214;48;2;249;248;249m▀\033[38;2;230;231;233m▀\033[48;2;220;224;230m▀\033[38;2;220;224;230;48;2;137;138;140m▀\033[48;2;249;248;249m \033[48;2;220;224;230m \033[48;2;249;248;249m  \033[38;2;71;72;73;48;2;191;194;198m▀\033[38;2;0;0;0;48;2;168;168;170m▀\033[38;2;213;212;214;48;2;230;231;233m▀ \033[48;2;249;248;249m \033[38;2;58;62;66;48;2;24;30;34m▀\033[38;2;168;168;170;48;2;112;117;122m▀\033[38;2;213;212;214;48;2;249;248;249m▀\033[38;2;230;231;233;48;2;191;194;198m▀\033[38;2;191;194;198;48;2;220;224;230m▀\033[38;2;112;117;122;48;2;105;105;106m▀\033[38;2;44;46;48;48;2;85;85;86m▀\033[0m
\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;85;85;86m▀\033[48;2;145;151;157m \033[38;2;44;46;48;48;2;71;72;73m▀\033[38;2;213;212;214;48;2;249;248;249m▀  \033[38;2;168;168;170;48;2;112;117;122m▀\033[38;2;44;46;48;48;2;0;0;0m▀\033[48;2;249;248;249m \033[38;2;191;194;198;48;2;168;168;170m▀\033[38;2;145;151;157;48;2;0;0;0m▀\033[38;2;168;168;170;48;2;24;30;34m▀▀\033[38;2;191;194;198m▀\033[38;2;213;212;214;48;2;220;224;230m▀\033[48;2;249;248;249m \033[38;2;191;194;198;48;2;105;105;106m▀\033[48;2;0;0;0m \033[38;2;71;72;73;48;2;24;30;34m▀\033[38;2;249;248;249;48;2;230;231;233m▀\033[38;2;230;231;233;48;2;249;248;249m▀\033[38;2;213;212;214m▀\033[38;2;112;117;122;48;2;137;138;140m▀\033[48;2;105;105;106m \033[38;2;0;0;0;48;2;44;46;48m▀\033[0m
\033[38;2;44;46;48;48;2;58;62;66m▀\033[38;2;112;117;122;48;2;137;138;140m▀\033[48;2;85;85;86m \033[38;2;105;105;106;48;2;137;138;140m▀\033[48;2;249;248;249m \033[38;2;230;231;233m▀ \033[38;2;71;72;73;48;2;44;46;48m▀\033[48;2;0;0;0m \033[38;2;191;194;198;48;2;145;151;157m▀\033[38;2;213;212;214;48;2;249;248;249m▀\033[38;2;24;30;34;48;2;44;46;48m▀\033[38;2;44;46;48;48;2;24;30;34m▀ \033[38;2;105;105;106;48;2;191;194;198m▀\033[48;2;249;248;249m \033[38;2;213;212;214;48;2;105;105;106m▀\033[48;2;24;30;34m \033[48;2;0;0;0m  \033[38;2;191;194;198;48;2;168;168;170m▀\033[48;2;249;248;249m  \033[38;2;230;231;233m▀\033[38;2;168;168;170;48;2;137;138;140m▀\033[48;2;85;85;86m \033[0m
\033[38;2;24;30;34;48;2;58;62;66m▀\033[48;2;145;151;157m \033[48;2;58;62;66m \033[38;2;191;194;198;48;2;249;248;249m▀  \033[38;2;213;212;214;48;2;191;194;198m▀\033[48;2;24;30;34m \033[38;2;0;0;0m▀\033[38;2;145;151;157;48;2;191;194;198m▀\033[48;2;249;248;249m \033[38;2;58;62;66;48;2;105;105;106m▀\033[38;2;24;30;34;48;2;0;0;0m▀\033[38;2;63;75;83;48;2;168;168;170m▀\033[48;2;249;248;249m \033[38;2;168;168;170;48;2;71;72;73m▀\033[48;2;24;30;34m \033[48;2;44;46;48m \033[48;2;24;30;34m \033[48;2;0;0;0m \033[38;2;112;117;122;48;2;105;105;106m▀\033[48;2;249;248;249m   \033[38;2;145;151;157;48;2;191;194;198m▀\033[48;2;112;117;122m \033[38;2;58;62;66m▀\033[0m
\033[38;2;24;30;34;48;2;0;0;0m▀\033[38;2;44;46;48m▀\033[38;2;24;30;34m▀\033[38;2;63;75;83m▀▀\033[38;2;85;85;86m▀\033[38;2;58;62;66m▀  ▀\033[38;2;71;72;73m▀\033[38;2;44;46;48m▀ \033[38;2;58;62;66m▀\033[38;2;71;72;73m▀\033[38;2;24;30;34m▀    ▀\033[38;2;71;72;73m▀▀▀\033[38;2;58;62;66m▀\033[38;2;24;30;34m▀▀\033[0m
==>43-moemiku-blush
\033[48;2;0;0;0m          \033[38;2;0;0;0;48;2;48;43;44m▀\033[48;2;82;88;93m▀\033[48;2;109;108;112m▀\033[38;2;27;29;29;48;2;227;118;150m▀\033[38;2;65;65;66;48;2;253;132;172m▀\033[38;2;100;86;91m▀\033[38;2;48;43;44;48;2;227;118;150m▀\033[38;2;27;29;29;48;2;137;154;161m▀\033[48;2;70;108;131m▀\033[38;2;0;0;0;48;2;48;43;44m▀\033[0m
\033[48;2;0;0;0m        \033[38;2;0;0;0;48;2;65;65;66m▀\033[38;2;100;86;91;48;2;253;132;172m▀\033[38;2;253;132;172;48;2;227;118;150m▀\033[38;2;202;111;139;48;2;253;132;172m▀\033[38;2;236;126;160m▀  \033[38;2;246;136;172m▀ ▀\033[38;2;227;118;150m▀\033[38;2;236;126;160;48;2;227;118;150m▀\033[38;2;100;86;91;48;2;236;126;160m▀\033[38;2;0;0;0;48;2;65;65;66m▀\033[0m
\033[48;2;0;0;0m        \033[38;2;139;103;116;48;2;170;100;120m▀\033[38;2;253;132;172;48;2;227;118;150m▀\033[38;2;236;126;160m▀\033[48;2;253;132;172m \033[38;2;253;132;172;48;2;236;126;160m▀\033[48;2;170;100;120m▀\033[48;2;236;126;160m▀\033[48;2;253;132;172m \033[38;2;253;132;172;48;2;246;136;172m▀\033[48;2;253;132;172m  \033[38;2;236;126;160;48;2;246;136;172m▀\033[38;2;227;118;150;48;2;202;111;139m▀\033[38;2;139;103;116;48;2;170;100;120m▀\033[0m
\033[48;2;0;0;0m        \033[48;2;202;111;139m  \033[48;2;236;126;160m \033[38;2;253;132;172m▀\033[38;2;236;126;160;48;2;170;100;120m▀▀\033[38;2;227;118;150;48;2;202;111;139m▀\033[38;2;253;132;172;48;2;227;118;150m▀\033[38;2;227;118;150;48;2;139;103;116m▀\033[38;2;202;111;139;48;2;118;107;111m▀\033[38;2;236;126;160;48;2;202;111;139m▀\033[48;2;236;126;160m \033[38;2;227;118;150;48;2;202;111;139m▀\033[38;2;170;100;120m▀\033[0m
\033[48;2;0;0;0m        \033[48;2;202;111;139m \033[38;2;202;111;139;48;2;227;118;150m▀\033[38;2;227;118;150;48;2;202;111;139m▀\033[48;2;227;118;150m \033[38;2;139;103;116;48;2;202;111;139m▀\033[38;2;70;108;131;48;2;246;136;172m▀\033[38;2;170;100;120;48;2;227;118;150m▀\033[38;2;202;111;139m▀\033[38;2;70;108;131;48;2;246;136;172m▀\033[38;2;139;103;116;48;2;227;118;150m▀\033[38;2;227;118;150;48;2;202;111;139m▀▀\033[38;2;202;111;139;48;2;227;118;150m▀\033[48;2;202;111;139m \033[0m
\033[48;2;0;0;0m       \033[38;2;27;29;29;48;2;48;43;44m▀\033[38;2;202;111;139;48;2;209;152;170m▀\033[38;2;227;118;150;48;2;202;111;139m▀\033[38;2;202;111;139;48;2;246;136;172m▀\033[48;2;209;152;170m▀\033[38;2;100;86;91;48;2;48;43;44m▀\033[38;2;202;111;139m▀\033[38;2;253;132;172;48;2;209;152;170m▀\033[38;2;236;126;160;48;2;246;136;172m▀\033[38;2;253;132;172;48;2;100;86;91m▀\033[38;2;65;65;66;48;2;0;0;0m▀\033[38;2;139;103;116;48;2;138;140;142m▀\033[38;2;202;111;139;48;2;246;136;172m▀\033[38;2;227;118;150;48;2;209;152;170m▀\033[38;2;202;111;139m▀\033[48;2;27;29;29m \033[0m
\033[48;2;0;0;0m       \033[38;2;80;80;81;48;2;109;108;112m▀\033[38;2;180;191;195;48;2;249;250;250m▀▀ \033[38;2;208;209;209m▀\033[38;2;123;123;125;48;2;226;226;226m▀\033[38;2;193;195;197;48;2;180;191;195m▀\033[38;2;137;154;161;48;2;138;140;142m▀\033[38;2;138;140;142;48;2;137;154;161m▀\033[48;2;249;250;250m▀\033[38;2;65;65;66;48;2;137;154;161m▀\033[38;2;180;191;195;48;2;168;166;169m▀\033[38;2;249;250;250;48;2;226;226;226m▀\033[38;2;226;226;226;48;2;208;209;209m▀\033[38;2;193;195;197;48;2;186;192;194m▀\033[38;2;48;43;44;48;2;65;65;66m▀\033[0m
\033[48;2;0;0;0m      \033[38;2;0;0;0;48;2;27;29;29m▀\033[38;2;168;166;169;48;2;208;209;209m▀\033[48;2;249;250;250m \033[38;2;249;250;250;48;2;226;226;226m▀▀\033[48;2;249;250;250m  \033[38;2;208;209;209m▀\033[38;2;65;65;66;48;2;80;80;81m▀\033[48;2;138;140;142m▀\033[38;2;154;188;197;48;2;249;250;250m▀\033[38;2;226;226;226m▀ ▀\033[38;2;208;209;209;48;2;226;226;226m▀\033[48;2;168;166;169m▀\033[38;2;65;65;66;48;2;80;80;81m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;0;0;0;48;2;27;29;29m▀\033[38;2;109;108;112;48;2;168;166;169m▀\033[38;2;249;250;250;48;2;208;209;209m▀\033[48;2;249;250;250m \033[38;2;226;226;226m▀   \033[48;2;180;191;195m▀\033[38;2;48;43;44;48;2;27;29;29m▀\033[48;2;186;192;194m \033[48;2;249;250;250m  \033[38;2;226;226;226;48;2;208;209;209m▀\033[48;2;249;250;250m \033[48;2;226;226;226m \033[48;2;168;166;169m \033[38;2;109;108;112;48;2;138;140;142m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;109;108;112;48;2;168;166;169m▀\033[48;2;27;29;29m▀\033[38;2;137;154;161;48;2;66;156;182m▀\033[38;2;208;209;209;48;2;111;190;207m▀\033[38;2;226;226;226;48;2;208;209;209m▀\033[48;2;226;226;226m \033[38;2;249;250;250m▀▀\033[38;2;138;140;142;48;2;100;86;91m▀\033[38;2;27;29;29;48;2;0;0;0m▀\033[38;2;193;195;197;48;2;186;192;194m▀\033[48;2;249;250;250m \033[38;2;249;250;250;48;2;226;226;226m▀ \033[48;2;249;250;250m  \033[38;2;168;166;169;48;2;193;195;197m▀\033[48;2;138;140;142m \033[0m
\033[48;2;0;0;0m    \033[38;2;109;108;112;48;2;168;166;169m▀\033[38;2;100;86;91;48;2;27;29;29m▀\033[38;2;80;80;81;48;2;226;226;226m▀\033[48;2;208;209;209m \033[48;2;226;226;226m \033[38;2;208;209;209m▀  \033[38;2;226;226;226;48;2;249;250;250m▀\033[38;2;80;80;81;48;2;82;88;93m▀\033[48;2;0;0;0m \033[48;2;186;192;194m \033[48;2;249;250;250m \033[38;2;226;226;226;48;2;208;209;209m▀\033[38;2;111;190;207m▀\033[48;2;66;156;182m▀\033[38;2;208;209;209;48;2;111;190;207m▀\033[48;2;226;226;226m▀\033[38;2;137;154;161;48;2;168;166;169m▀\033[38;2;48;43;44;48;2;80;80;81m▀\033[0m
\033[48;2;0;0;0m   \033[38;2;82;88;93;48;2;138;140;142m▀\033[38;2;109;108;112;48;2;48;43;44m▀\033[48;2;168;166;169m▀\033[38;2;249;250;250;48;2;208;209;209m▀\033[38;2;168;166;169m▀\033[48;2;226;226;226m \033[38;2;226;226;226;48;2;249;250;250m▀\033[48;2;226;226;226m \033[48;2;249;250;250m  \033[38;2;82;88;93;48;2;65;65;66m▀\033[48;2;27;29;29m \033[38;2;226;226;226;48;2;208;209;209m▀▀\033[48;2;249;250;250m▀\033[48;2;138;140;142m▀\033[38;2;66;156;182;48;2;186;192;194m▀\033[38;2;193;195;197;48;2;208;209;209m▀\033[38;2;226;226;226;48;2;249;250;250m▀\033[48;2;168;166;169m \033[38;2;118;107;111;48;2;123;123;125m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;48;43;44;48;2;109;108;112m▀\033[38;2;138;140;142;48;2;118;107;111m▀\033[38;2;65;65;66;48;2;137;154;161m▀\033[38;2;208;209;209;48;2;249;250;250m▀\033[38;2;226;226;226m▀\033[38;2;249;250;250;48;2;226;226;226m▀\033[38;2;226;226;226;48;2;138;140;142m▀\033[48;2;249;250;250m \033[48;2;226;226;226m \033[48;2;249;250;250m  \033[38;2;65;65;66;48;2;208;209;209m▀\033[38;2;27;29;29;48;2;168;166;169m▀\033[38;2;208;209;209;48;2;226;226;226m▀\033[38;2;226;226;226;48;2;249;250;250m▀ \033[38;2;65;65;66;48;2;0;0;0m▀\033[38;2;168;166;169;48;2;123;123;125m▀\033[38;2;226;226;226;48;2;249;250;250m▀\033[48;2;193;195;197m▀\033[38;2;208;209;209;48;2;226;226;226m▀\033[38;2;118;107;111;48;2;100;86;91m▀\033[38;2;48;43;44m▀\033[0m
\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;82;88;93m▀\033[38;2;138;140;142;48;2;137;154;161m▀\033[38;2;48;43;44;48;2;65;65;66m▀\033[38;2;208;209;209;48;2;249;250;250m▀  \033[38;2;168;166;169;48;2;123;123;125m▀\033[38;2;48;43;44;48;2;0;0;0m▀\033[48;2;249;250;250m \033[38;2;208;209;209;48;2;168;166;169m▀\033[38;2;168;166;169;48;2;0;0;0m▀\033[38;2;180;191;195;48;2;27;29;29m▀\033[38;2;186;192;194;48;2;0;0;0m▀\033[38;2;193;195;197;48;2;27;29;29m▀\033[38;2;208;209;209;48;2;226;226;226m▀\033[48;2;249;250;250m \033[38;2;186;192;194;48;2;100;86;91m▀\033[48;2;0;0;0m \033[38;2;80;80;81;48;2;27;29;29m▀\033[48;2;249;250;250m \033[38;2;226;226;226m▀\033[38;2;208;209;209m▀\033[38;2;109;108;112;48;2;123;123;125m▀\033[48;2;109;108;112m \033[38;2;0;0;0;48;2;48;43;44m▀\033[0m
\033[48;2;48;43;44m \033[48;2;123;123;125m \033[38;2;100;86;91;48;2;80;80;81m▀\033[38;2;109;108;112;48;2;138;140;142m▀\033[48;2;249;250;250m   \033[38;2;65;65;66;48;2;27;29;29m▀\033[48;2;0;0;0m \033[38;2;193;195;197;48;2;137;154;161m▀\033[38;2;208;209;209;48;2;249;250;250m▀\033[38;2;27;29;29;48;2;48;43;44m▀\033[48;2;27;29;29m \033[38;2;0;0;0m▀\033[38;2;109;108;112;48;2;186;192;194m▀\033[48;2;249;250;250m \033[38;2;208;209;209;48;2;109;108;112m▀\033[48;2;27;29;29m \033[48;2;0;0;0m  \033[38;2;186;192;194;48;2;168;166;169m▀\033[48;2;249;250;250m  \033[38;2;226;226;226m▀\033[38;2;137;154;161;48;2;123;123;125m▀\033[38;2;80;80;81;48;2;100;86;91m▀\033[0m
\033[38;2;27;29;29;48;2;65;65;66m▀\033[38;2;138;140;142;48;2;168;166;169m▀\033[38;2;80;80;81;48;2;65;65;66m▀\033[38;2;193;195;197;48;2;249;250;250m▀  \033[38;2;208;209;209;48;2;193;195;197m▀\033[48;2;27;29;29m  \033[38;2;137;154;161;48;2;208;209;209m▀\033[48;2;249;250;250m \033[38;2;48;43;44;48;2;123;123;125m▀\033[48;2;0;0;0m \033[38;2;65;65;66;48;2;168;166;169m▀\033[48;2;249;250;250m \033[38;2;186;192;194;48;2;80;80;81m▀\033[48;2;0;0;0m \033[48;2;27;29;29m \033[38;2;0;0;0m▀\033[48;2;0;0;0m \033[38;2;123;123;125;48;2;100;86;91m▀\033[48;2;249;250;250m   \033[38;2;138;140;142;48;2;193;195;197m▀\033[48;2;109;108;112m \033[38;2;65;65;66;48;2;118;107;111m▀\033[0m
\033[38;2;27;29;29;48;2;0;0;0m▀\033[38;2;48;43;44m▀\033[38;2;27;29;29m▀\033[38;2;80;80;81m▀▀▀\033[38;2;65;65;66m▀  ▀\033[38;2;80;80;81m▀\033[38;2;48;43;44m▀ \033[38;2;65;65;66m▀\033[38;2;80;80;81m▀     \033[38;2;27;29;29m▀\033[38;2;80;80;81m▀\033[38;2;65;65;66m▀\033[38;2;80;80;81m▀\033[38;2;65;65;66m▀\033[38;2;27;29;29m▀\033[38;2;48;43;44m▀\033[0m
==>44-afrowikipe
\033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;37;26;27m▀\033[48;2;72;47;42m▀\033[38;2;22;16;16;48;2;80;74;77m▀\033[38;2;37;26;27;48;2;72;47;42m▀\033[38;2;45;26;25m▀\033[48;2;40;28;25m    \033[38;2;40;28;25;48;2;37;40;38m▀\033[38;2;22;16;16;48;2;72;47;42m▀\033[38;2;0;0;0;48;2;45;26;25m▀\033[48;2;37;40;38m▀\033[48;2;22;16;16m▀\033[0m
\033[48;2;40;28;25m \033[38;2;72;47;42;48;2;0;0;0m▀\033[38;2;22;16;16m▀           ▀▀\033[0m
\033[48;2;0;0;0m \033[38;2;72;47;42;48;2;162;162;168m▀\033[38;2;80;74;77;48;2;188;193;200m▀\033[38;2;22;16;16;48;2;162;162;168m▀\033[38;2;0;0;0;48;2;37;40;38m▀\033[48;2;0;0;0m             \033[38;2;22;16;16m▀\033[48;2;107;104;114m \033[38;2;107;104;114;48;2;162;162;168m▀\033[38;2;37;26;27;48;2;22;16;16m▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;72;47;42m▀▀\033[0m
\033[38;2;37;40;38;48;2;141;146;154m▀\033[38;2;162;162;168;48;2;188;193;200m▀\033[48;2;162;162;168m \033[38;2;188;193;200m▀\033[38;2;80;74;77;48;2;22;16;16m▀\033[48;2;0;0;0m               \033[38;2;80;74;77;48;2;72;47;42m▀\033[38;2;0;0;0;48;2;107;104;114m▀\033[48;2;37;40;38m▀\033[38;2;80;74;77;48;2;134;123;131m▀\033[38;2;188;193;200;48;2;162;162;168m▀\033[38;2;134;123;131;48;2;188;193;200m▀\033[38;2;22;16;16;48;2;107;104;114m▀\033[0m
\033[38;2;134;123;131;48;2;22;16;16m▀\033[38;2;162;162;168;48;2;0;0;0m▀\033[38;2;188;193;200;48;2;107;104;114m▀\033[38;2;134;123;131;48;2;72;47;42m▀\033[48;2;0;0;0m                \033[38;2;22;16;16m▀\033[38;2;162;162;168;48;2;107;104;114m▀\033[48;2;188;193;200m  \033[38;2;162;162;168m▀ \033[38;2;107;104;114;48;2;72;47;42m▀\033[0m
\033[48;2;0;0;0m                     \033[38;2;37;40;38;48;2;22;16;16m▀\033[38;2;188;193;200;48;2;162;162;168m▀\033[38;2;134;123;131;48;2;22;16;16m▀\033[38;2;107;104;114;48;2;0;0;0m▀\033[38;2;141;146;154;48;2;37;26;27m▀\033[0m
\033[38;2;80;74;77;48;2;141;146;154m▀\033[38;2;141;146;154;48;2;188;193;200m▀\033[38;2;37;40;38;48;2;25;158;31m▀\033[48;2;0;0;0m     \033[38;2;0;0;0;48;2;97;60;54m▀\033[38;2;22;16;16;48;2;138;87;74m▀\033[38;2;72;47;42;48;2;158;93;77m▀\033[38;2;97;60;54m▀\033[38;2;108;65;57m▀\033[38;2;97;60;54m▀▀▀\033[38;2;22;16;16;48;2;138;87;74m▀\033[38;2;0;0;0;48;2;45;26;25m▀\033[48;2;0;0;0m    \033[48;2;28;118;33m \033[38;2;17;74;23;48;2;31;194;35m▀\033[38;2;0;0;0;48;2;25;158;31m▀\033[48;2;22;16;16m▀\033[0m
\033[38;2;141;146;154;48;2;134;123;131m▀\033[38;2;162;162;168m▀\033[38;2;31;194;35;48;2;25;158;31m▀\033[38;2;17;74;23m▀\033[48;2;0;0;0m  \033[38;2;37;26;27;48;2;138;87;74m▀\033[38;2;125;68;59;48;2;158;93;77m▀\033[38;2;138;87;74m▀▀▀   ▀▀▀ \033[38;2;72;47;42;48;2;138;87;74m▀\033[48;2;0;0;0m   \033[38;2;28;118;33;48;2;22;16;16m▀\033[38;2;31;194;35;48;2;25;158;31m▀\033[48;2;31;194;35m \033[38;2;25;158;31;48;2;17;74;23m▀\033[0m
\033[48;2;0;0;0m \033[48;2;17;74;23m \033[38;2;31;194;35;48;2;25;158;31m▀\033[48;2;22;16;16m▀\033[38;2;22;16;16;48;2;0;0;0m▀▀\033[38;2;158;93;77;48;2;48;26;21m▀\033[38;2;120;80;68;48;2;0;0;0m▀\033[38;2;108;65;57m▀▀\033[38;2;138;87;74;48;2;134;123;131m▀▀\033[38;2;158;93;77;48;2;72;47;42m▀\033[48;2;97;60;54m▀\033[48;2;72;47;42m▀\033[48;2;37;26;27m▀\033[48;2;80;74;77m▀\033[48;2;138;87;74m▀\033[48;2;125;68;59m▀\033[38;2;37;26;27;48;2;0;0;0m▀ \033[38;2;22;16;16m▀\033[48;2;25;158;31m▀\033[48;2;17;74;23m▀\033[38;2;31;194;35;48;2;0;0;0m▀\033[38;2;22;16;16m▀\033[0m
\033[48;2;0;0;0m     \033[48;2;22;16;16m \033[38;2;40;28;25;48;2;97;60;54m▀\033[48;2;0;0;0m   \033[38;2;134;123;131;48;2;107;104;114m▀\033[38;2;162;162;168;48;2;141;146;154m▀\033[38;2;37;40;38;48;2;158;93;77m▀\033[38;2;0;0;0;48;2;97;60;54m▀\033[48;2;0;0;0m  \033[48;2;80;74;77m \033[38;2;162;162;168;48;2;141;146;154m▀\033[38;2;37;40;38;48;2;97;60;54m▀\033[48;2;0;0;0m  \033[38;2;22;16;16;48;2;17;74;23m▀\033[38;2;25;158;31;48;2;31;194;35m▀▀\033[38;2;0;0;0;48;2;22;16;16m▀\033[0m
\033[48;2;0;0;0m   \033[38;2;37;26;27;48;2;97;60;54m▀\033[38;2;72;47;42;48;2;125;68;59m▀\033[38;2;0;0;0;48;2;108;65;57m▀\033[38;2;97;60;54;48;2;158;93;77m▀\033[38;2;72;47;42m▀\033[38;2;0;0;0;48;2;120;80;68m▀▀\033[38;2;107;104;114;48;2;138;87;74m▀\033[38;2;134;123;131m▀\033[48;2;158;93;77m \033[38;2;97;60;54;48;2;138;87;74m▀\033[38;2;0;0;0;48;2;72;47;42m▀\033[48;2;22;16;16m▀\033[38;2;107;104;114;48;2;80;74;77m▀\033[38;2;134;123;131;48;2;108;65;57m▀\033[38;2;48;26;21;48;2;0;0;0m▀  \033[48;2;17;74;23m \033[38;2;25;158;31m▀\033[48;2;0;0;0m▀\033[38;2;28;118;33;48;2;22;16;16m▀\033[0m
\033[38;2;37;26;27;48;2;0;0;0m▀ \033[38;2;22;16;16m▀\033[38;2;125;68;59;48;2;97;60;54m▀\033[38;2;108;65;57;48;2;138;87;74m▀\033[38;2;125;68;59;48;2;108;65;57m▀\033[38;2;158;93;77m▀\033[38;2;138;87;74;48;2;158;93;77m▀▀▀  ▀\033[38;2;120;80;68m▀ \033[38;2;138;87;74m▀\033[48;2;138;87;74m \033[48;2;72;47;42m \033[38;2;22;16;16;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m   \033[38;2;22;16;16m▀\033[38;2;37;26;27m▀\033[38;2;22;16;16m▀ \033[38;2;125;68;59;48;2;22;16;16m▀\033[38;2;158;93;77;48;2;108;65;57m▀\033[48;2;158;93;77m  \033[38;2;138;87;74m▀\033[38;2;120;80;68m▀\033[38;2;138;87;74m▀\033[38;2;158;93;77;48;2;138;87;74m▀\033[48;2;108;65;57m▀\033[38;2;108;65;57;48;2;22;16;16m▀\033[0m
\033[48;2;0;0;0m       \033[38;2;0;0;0;48;2;80;74;77m▀\033[38;2;72;47;42;48;2;134;123;131m▀\033[48;2;108;65;57m \033[38;2;120;80;68m▀\033[48;2;138;87;74m \033[38;2;97;60;54;48;2;107;104;114m▀\033[38;2;48;26;21;48;2;22;16;16m▀\033[38;2;22;16;16;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;80;74;77m▀\033[48;2;134;123;131m▀\033[38;2;37;40;38;48;2;141;146;154m▀\033[38;2;107;104;114;48;2;134;123;131m▀\033[38;2;134;123;131;48;2;107;104;114m▀\033[38;2;141;146;154m▀\033[48;2;188;193;200m \033[38;2;141;146;154m▀\033[38;2;120;80;68;48;2;162;162;168m▀\033[38;2;134;123;131;48;2;141;146;154m▀\033[48;2;188;193;200m \033[38;2;134;123;131;48;2;162;162;168m▀\033[38;2;80;74;77;48;2;107;104;114m▀\033[38;2;37;40;38;48;2;134;123;131m▀\033[38;2;0;0;0;48;2;107;104;114m▀\033[48;2;40;28;25m▀\033[0m
\033[38;2;0;0;0;48;2;50;72;44m▀\033[38;2;107;104;114;48;2;141;146;154m▀\033[38;2;141;146;154;48;2;134;123;131m▀ \033[38;2;107;104;114m▀▀▀▀\033[38;2;141;146;154m▀\033[48;2;188;193;200m \033[38;2;158;93;77m▀\033[38;2;196;0;0;48;2;208;31;30m▀\033[38;2;134;123;131;48;2;158;93;77m▀\033[38;2;162;162;168;48;2;188;193;200m▀\033[38;2;120;80;68;48;2;158;93;77m▀\033[38;2;107;104;114;48;2;138;87;74m▀\033[38;2;141;146;154;48;2;134;123;131m▀ \033[38;2;0;0;0;48;2;37;40;38m▀\033[0m
\033[38;2;22;16;16;48;2;0;0;0m▀\033[38;2;37;40;38m▀▀▀▀▀▀▀▀▀\033[38;2;80;74;77m▀\033[38;2;45;26;25m▀\033[38;2;48;26;21m▀\033[38;2;50;72;44m▀\033[38;2;37;40;38m▀\033[38;2;40;28;25m▀\033[38;2;37;40;38m▀▀\033[38;2;22;16;16m▀\033[0m
==>45-afrowikipe-indigo
\033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;19;16;15m▀\033[48;2;49;39;41m▀\033[38;2;19;16;15m▀▀\033[48;2;19;16;15m     \033[38;2;19;16;15;48;2;49;39;41m▀\033[48;2;19;37;19m▀\033[38;2;0;0;0;48;2;19;16;15m▀\033[48;2;19;37;19m▀\033[48;2;19;16;15m▀\033[0m
\033[38;2;49;39;41;48;2;19;16;15m▀\033[48;2;0;0;0m▀\033[38;2;19;16;15m▀           ▀▀\033[0m
\033[48;2;0;0;0m \033[38;2;49;39;41;48;2;151;150;158m▀\033[48;2;181;186;195m▀\033[38;2;19;16;15m▀\033[38;2;0;0;0;48;2;49;39;41m▀\033[48;2;0;0;0m             \033[38;2;19;16;15m▀\033[48;2;104;104;114m \033[38;2;104;104;114;48;2;181;186;195m▀\033[38;2;19;37;19;48;2;19;16;15m▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;49;39;41m▀▀\033[0m
\033[38;2;49;39;41;48;2;151;150;158m▀\033[38;2;151;150;158;48;2;181;186;195m▀\033[48;2;151;150;158m \033[38;2;181;186;195m▀\033[38;2;104;93;79;48;2;19;16;15m▀\033[48;2;0;0;0m               \033[38;2;93;63;57;48;2;49;39;41m▀\033[38;2;0;0;0;48;2;104;104;114m▀\033[48;2;49;39;41m▀\033[38;2;104;104;114;48;2;131;119;126m▀\033[48;2;181;186;195m \033[38;2;131;119;126m▀\033[38;2;19;16;15;48;2;104;104;114m▀\033[0m
\033[38;2;131;119;126;48;2;19;16;15m▀\033[38;2;151;150;158;48;2;0;0;0m▀\033[38;2;181;186;195;48;2;104;104;114m▀\033[38;2;131;119;126;48;2;49;39;41m▀\033[48;2;0;0;0m                 \033[38;2;151;150;158;48;2;104;104;114m▀\033[48;2;181;186;195m  \033[38;2;151;150;158m▀ \033[38;2;104;93;79;48;2;49;39;41m▀\033[0m
\033[48;2;0;0;0m                     \033[38;2;49;39;41;48;2;19;16;15m▀\033[38;2;181;186;195;48;2;151;150;158m▀\033[38;2;131;119;126;48;2;19;16;15m▀\033[38;2;104;104;114;48;2;0;0;0m▀\033[38;2;151;150;158;48;2;19;16;15m▀\033[0m
\033[38;2;104;93;79;48;2;151;150;158m▀\033[38;2;151;150;158;48;2;181;186;195m▀\033[38;2;49;39;41;48;2;48;131;41m▀\033[48;2;0;0;0m     \033[38;2;0;0;0;48;2;93;63;57m▀\033[38;2;19;16;15;48;2;140;84;72m▀\033[38;2;49;39;41;48;2;144;86;75m▀\033[38;2;111;69;60m▀▀\033[38;2;93;63;57m▀▀\033[48;2;152;91;77m▀\033[38;2;19;16;15;48;2;130;80;67m▀\033[38;2;0;0;0;48;2;49;39;41m▀\033[48;2;0;0;0m    \033[38;2;48;131;41;48;2;21;132;26m▀\033[38;2;19;37;19;48;2;32;194;37m▀\033[38;2;0;0;0;48;2;28;157;33m▀\033[48;2;19;37;19m▀\033[0m
\033[38;2;151;150;158;48;2;131;119;126m▀\033[48;2;104;104;114m▀\033[38;2;32;194;37;48;2;28;157;33m▀\033[38;2;21;132;26m▀\033[48;2;0;0;0m  \033[38;2;19;16;15;48;2;140;84;72m▀\033[38;2;130;80;67;48;2;152;91;77m▀ \033[38;2;144;86;75m▀▀▀▀▀▀▀▀ \033[38;2;49;39;41;48;2;130;80;67m▀\033[38;2;0;0;0;48;2;19;16;15m▀\033[48;2;0;0;0m  \033[38;2;21;132;26;48;2;19;16;15m▀\033[38;2;32;194;37;48;2;28;157;33m▀\033[48;2;32;194;37m \033[38;2;32;194;37;48;2;19;37;19m▀\033[0m
\033[48;2;0;0;0m \033[38;2;21;132;26;48;2;53;84;58m▀\033[38;2;32;194;37;48;2;28;157;33m▀\033[48;2;19;16;15m▀\033[38;2;19;37;19;48;2;0;0;0m▀\033[38;2;19;16;15m▀\033[38;2;152;91;77;48;2;49;39;41m▀\033[38;2;111;69;60;48;2;0;0;0m▀\033[38;2;93;63;57m▀\033[38;2;111;69;60m▀\033[38;2;122;75;63;48;2;131;119;126m▀\033[38;2;130;80;67m▀\033[38;2;152;91;77;48;2;93;63;57m▀▀\033[48;2;49;39;41m▀\033[48;2;19;16;15m▀\033[38;2;144;86;75;48;2;111;69;60m▀\033[38;2;152;91;77;48;2;131;89;79m▀\033[38;2;144;86;75;48;2;122;75;63m▀\033[38;2;19;16;15;48;2;0;0;0m▀ ▀\033[38;2;19;37;19;48;2;28;157;33m▀\033[38;2;19;16;15;48;2;19;37;19m▀\033[38;2;28;157;33;48;2;0;0;0m▀\033[38;2;19;16;15m▀\033[0m
\033[48;2;0;0;0m     \033[48;2;19;16;15m \033[38;2;49;39;41;48;2;93;63;57m▀\033[48;2;0;0;0m   \033[38;2;131;119;126;48;2;104;104;114m▀\033[38;2;181;186;195;48;2;151;150;158m▀\033[38;2;49;39;41;48;2;152;91;77m▀\033[38;2;0;0;0;48;2;93;63;57m▀\033[48;2;0;0;0m  \033[48;2;104;93;79m \033[48;2;151;150;158m \033[38;2;49;39;41;48;2;93;63;57m▀\033[48;2;0;0;0m  \033[38;2;19;16;15;48;2;19;37;19m▀\033[38;2;28;157;33;48;2;32;194;37m▀▀\033[38;2;0;0;0;48;2;19;37;19m▀\033[0m
\033[48;2;0;0;0m   \033[38;2;19;16;15;48;2;93;63;57m▀\033[38;2;49;39;41;48;2;122;75;63m▀\033[38;2;0;0;0;48;2;111;69;60m▀\033[38;2;93;63;57;48;2;144;86;75m▀\033[38;2;49;39;41;48;2;152;91;77m▀\033[38;2;0;0;0;48;2;130;80;67m▀\033[48;2;111;69;60m▀\033[38;2;104;104;114;48;2;131;89;79m▀\033[38;2;131;119;126;48;2;152;91;77m▀\033[48;2;144;86;75m \033[38;2;93;63;57m▀\033[38;2;0;0;0;48;2;93;63;57m▀\033[48;2;19;16;15m▀\033[48;2;104;93;79m \033[38;2;131;119;126;48;2;111;69;60m▀\033[38;2;49;39;41;48;2;0;0;0m▀  \033[48;2;19;37;19m \033[38;2;28;157;33m▀\033[48;2;0;0;0m▀\033[38;2;21;132;26;48;2;19;37;19m▀\033[0m
\033[38;2;19;16;15;48;2;0;0;0m▀ ▀\033[38;2;122;75;63;48;2;93;63;57m▀\033[38;2;111;69;60;48;2;144;86;75m▀\033[38;2;122;75;63;48;2;111;69;60m▀\033[38;2;152;91;77;48;2;93;63;57m▀\033[48;2;152;91;77m \033[38;2;152;91;77;48;2;144;86;75m▀▀▀▀▀\033[38;2;122;75;63m▀\033[38;2;152;91;77m▀▀ \033[48;2;93;63;57m \033[38;2;19;16;15;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;19;16;15m▀▀\033[38;2;49;39;41m▀\033[38;2;19;16;15m▀ \033[38;2;111;69;60;48;2;19;16;15m▀\033[38;2;152;91;77;48;2;111;69;60m▀\033[48;2;140;84;72m▀\033[38;2;144;86;75;48;2;152;91;77m▀▀\033[38;2;140;84;72m▀▀\033[38;2;152;91;77;48;2;130;80;67m▀\033[48;2;111;69;60m▀\033[38;2;111;69;60;48;2;19;16;15m▀\033[0m
\033[48;2;0;0;0m       \033[38;2;0;0;0;48;2;104;93;79m▀\033[38;2;49;39;41;48;2;151;150;158m▀\033[48;2;111;69;60m  \033[38;2;130;80;67;48;2;131;89;79m▀\033[38;2;93;63;57;48;2;104;104;114m▀\033[38;2;49;39;41;48;2;0;0;0m▀\033[38;2;19;16;15m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;53;84;58m▀\033[48;2;131;119;126m▀\033[38;2;49;39;41m▀\033[38;2;104;93;79m▀\033[48;2;104;104;114m \033[38;2;151;150;158;48;2;131;119;126m▀\033[48;2;181;186;195m \033[38;2;131;119;126m▀\033[38;2;111;69;60;48;2;151;150;158m▀\033[48;2;131;119;126m \033[48;2;181;186;195m \033[38;2;131;119;126;48;2;151;150;158m▀\033[38;2;104;93;79;48;2;131;119;126m▀\033[38;2;49;39;41m▀\033[38;2;0;0;0;48;2;104;104;114m▀\033[48;2;49;39;41m▀\033[0m
\033[38;2;0;0;0;48;2;49;39;41m▀\033[38;2;104;93;79;48;2;131;119;126m▀\033[38;2;151;150;158;48;2;104;104;114m▀ \033[38;2;104;104;114;48;2;131;119;126m▀▀▀▀\033[38;2;151;150;158m▀\033[48;2;181;186;195m \033[38;2;131;119;126m▀\033[38;2;196;0;0;48;2;210;32;28m▀\033[38;2;152;91;77;48;2;157;74;65m▀\033[38;2;151;150;158;48;2;181;186;195m▀\033[38;2;114;103;66;48;2;152;91;77m▀\033[38;2;104;104;114;48;2;114;103;66m▀\033[38;2;131;119;126;48;2;104;104;114m▀\033[48;2;131;119;126m \033[38;2;0;0;0;48;2;49;39;41m▀\033[0m
\033[38;2;19;16;15;48;2;0;0;0m▀\033[38;2;49;39;41m▀▀▀▀▀▀▀▀▀\033[38;2;53;84;58m▀\033[38;2;49;39;41m▀▀\033[38;2;53;84;58m▀\033[38;2;49;39;41m▀▀▀▀\033[38;2;19;16;15m▀\033[0m
==>46-vocaloid
\033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;110;117;6m▀\033[38;2;110;117;6;48;2;240;253;0m▀\033[38;2;145;154;6m▀▀\033[38;2;20;20;12;48;2;177;191;4m▀\033[38;2;0;0;0m▀\033[38;2;110;117;6;48;2;240;253;0m▀\033[38;2;145;154;6m▀▀▀▀▀▀\033[38;2;110;117;6m▀\033[38;2;20;20;12;48;2;230;246;3m▀\033[38;2;0;0;0;48;2;110;117;6m▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;110;117;6m▀\033[38;2;65;69;6;48;2;230;246;3m▀\033[48;2;240;253;0m▀\033[38;2;0;0;0;48;2;177;191;4m▀\033[48;2;20;20;12m▀\033[0m
\033[48;2;0;0;0m \033[38;2;65;69;6;48;2;177;191;4m▀\033[48;2;240;253;0m \033[38;2;240;253;0;48;2;225;245;0m▀\033[48;2;230;246;3m  \033[38;2;240;253;0;48;2;225;245;0m▀▀\033[48;2;230;246;3m        \033[38;2;240;253;0;48;2;225;245;0m▀▀\033[38;2;145;154;6;48;2;240;253;0m▀\033[48;2;225;245;0m \033[38;2;240;253;0m▀ ▀\033[38;2;177;191;4;48;2;240;253;0m▀\033[38;2;0;0;0;48;2;65;69;6m▀\033[0m
\033[38;2;20;20;12;48;2;65;69;6m▀\033[38;2;225;245;0;48;2;240;253;0m▀\033[38;2;240;253;0;48;2;230;246;3m▀ ▀ \033[38;2;230;246;3;48;2;240;253;0m▀\033[38;2;240;253;0;48;2;230;246;3m▀\033[48;2;225;245;0m▀\033[38;2;225;245;0;48;2;204;225;4m▀\033[48;2;225;245;0m \033[38;2;240;253;0m▀▀\033[38;2;230;246;3;48;2;227;235;40m▀▀\033[38;2;240;253;0;48;2;225;245;0m▀▀\033[48;2;230;246;3m  \033[48;2;225;245;0m \033[48;2;230;246;3m \033[48;2;240;253;0m \033[48;2;230;246;3m \033[48;2;240;253;0m \033[38;2;145;154;6;48;2;177;191;4m▀\033[0m
\033[48;2;110;117;6m \033[48;2;240;253;0m \033[38;2;225;245;0m▀\033[38;2;230;246;3m▀▀▀\033[48;2;230;246;3m \033[38;2;199;213;20;48;2;205;201;69m▀\033[38;2;227;235;40;48;2;228;226;85m▀\033[48;2;204;225;4m \033[38;2;225;245;0;48;2;227;235;40m▀\033[38;2;230;246;3m▀\033[48;2;240;253;0m▀\033[38;2;227;235;40;48;2;212;225;20m▀\033[48;2;234;223;125m \033[38;2;212;225;20;48;2;205;201;69m▀\033[38;2;199;213;20m▀\033[48;2;204;225;4m \033[38;2;204;225;4;48;2;212;225;20m▀\033[48;2;199;213;20m▀\033[48;2;225;245;0m    \033[48;2;204;225;4m \033[48;2;20;20;12m \033[0m
\033[38;2;110;117;6;48;2;102;107;24m▀\033[48;2;240;253;0m \033[48;2;225;245;0m \033[48;2;230;246;3m \033[48;2;225;245;0m \033[48;2;240;253;0m \033[38;2;204;225;4;48;2;177;191;4m▀\033[48;2;200;184;118m \033[38;2;215;202;116;48;2;208;182;161m▀\033[38;2;205;201;69;48;2;215;202;116m▀\033[38;2;234;223;125;48;2;240;210;178m▀▀\033[48;2;225;245;0m \033[48;2;212;225;20m \033[38;2;215;202;116;48;2;240;210;178m▀\033[38;2;200;184;118;48;2;208;182;161m▀\033[48;2;205;201;69m \033[48;2;204;225;4m \033[38;2;199;213;20m▀ \033[38;2;225;245;0;48;2;240;253;0m▀▀\033[48;2;225;245;0m \033[48;2;230;246;3m \033[48;2;204;225;4m \033[48;2;20;20;12m \033[0m
\033[38;2;65;69;6;48;2;20;20;12m▀\033[38;2;230;246;3;48;2;204;225;4m▀\033[48;2;240;253;0m \033[48;2;230;246;3m \033[38;2;225;245;0;48;2;204;225;4m▀\033[38;2;240;253;0;48;2;225;245;0m▀\033[48;2;199;213;20m \033[38;2;154;144;112;48;2;125;123;87m▀\033[48;2;59;58;44m \033[38;2;125;123;87;48;2;20;20;12m▀\033[38;2;250;236;220;48;2;240;210;178m▀\033[48;2;250;236;220m \033[38;2;234;223;125m▀\033[38;2;162;162;78;48;2;151;143;140m▀\033[38;2;59;58;44;48;2;20;20;12m▀\033[48;2;59;58;44m \033[38;2;200;184;118;48;2;162;162;78m▀\033[48;2;204;225;4m \033[38;2;199;213;20;48;2;148;155;28m▀\033[38;2;204;225;4;48;2;225;245;0m▀\033[48;2;230;246;3m  \033[38;2;225;245;0;48;2;204;225;4m▀\033[48;2;225;245;0m \033[38;2;199;213;20;48;2;177;191;4m▀\033[38;2;20;20;12;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m \033[38;2;177;191;4;48;2;110;117;6m▀\033[48;2;240;253;0m \033[48;2;230;246;3m \033[38;2;230;246;3;48;2;240;253;0m▀▀\033[48;2;212;225;20m \033[38;2;208;182;161;48;2;250;236;220m▀\033[38;2;102;107;24;48;2;200;184;118m▀▀\033[48;2;250;236;220m  \033[38;2;250;236;220;48;2;240;210;178m▀\033[38;2;208;182;161;48;2;250;236;220m▀\033[38;2;65;69;6;48;2;200;184;118m▀\033[38;2;154;144;112;48;2;208;182;161m▀\033[38;2;234;223;125;48;2;228;226;85m▀\033[38;2;177;191;4;48;2;145;154;6m▀\033[38;2;77;78;38;48;2;0;0;0m▀\033[38;2;225;245;0;48;2;199;213;20m▀\033[38;2;230;246;3;48;2;240;253;0m▀▀\033[48;2;204;225;4m \033[48;2;230;246;3m \033[48;2;177;191;4m \033[0m
\033[48;2;0;0;0m \033[48;2;65;69;6m \033[48;2;240;253;0m \033[38;2;230;246;3m▀ \033[48;2;225;245;0m \033[38;2;177;191;4;48;2;110;117;6m▀\033[38;2;208;182;161;48;2;20;20;12m▀\033[38;2;250;236;220;48;2;151;143;140m▀\033[48;2;250;236;220m \033[48;2;240;210;178m \033[38;2;208;182;161;48;2;225;139;124m▀ \033[48;2;240;210;178m \033[38;2;250;236;220m▀\033[48;2;154;144;112m▀\033[38;2;205;201;69;48;2;65;69;6m▀\033[38;2;20;20;12;48;2;0;0;0m▀ \033[38;2;145;154;6;48;2;102;107;24m▀\033[48;2;240;253;0m \033[38;2;225;245;0m▀\033[48;2;204;225;4m▀\033[48;2;230;246;3m \033[48;2;177;191;4m \033[0m
\033[48;2;0;0;0m \033[48;2;20;20;12m \033[38;2;212;225;20;48;2;204;225;4m▀\033[48;2;240;253;0m \033[48;2;230;246;3m \033[48;2;240;253;0m \033[38;2;102;107;24;48;2;177;191;4m▀\033[48;2;0;0;0m  \033[48;2;59;58;44m \033[38;2;151;143;140m▀\033[38;2;240;210;178;48;2;208;182;161m▀▀\033[38;2;154;144;112;48;2;94;88;81m▀\033[38;2;20;20;12m▀\033[38;2;0;0;0m▀\033[48;2;20;20;12m▀\033[48;2;0;0;0m  \033[38;2;65;69;6;48;2;20;20;12m▀\033[38;2;240;253;0;48;2;225;245;0m▀\033[48;2;240;253;0m \033[48;2;204;225;4m \033[48;2;230;246;3m \033[38;2;177;191;4;48;2;204;225;4m▀\033[38;2;0;0;0;48;2;20;20;12m▀\033[0m
\033[48;2;0;0;0m \033[38;2;20;20;12;48;2;65;69;6m▀\033[38;2;204;225;4;48;2;240;253;0m▀ \033[38;2;230;246;3m▀▀\033[38;2;225;245;0;48;2;177;191;4m▀\033[38;2;65;69;6;48;2;200;184;118m▀\033[38;2;151;143;140;48;2;250;236;220m▀\033[38;2;250;236;220;48;2;208;182;161m▀\033[38;2;151;143;140;48;2;119;115;116m▀\033[38;2;125;123;87;48;2;162;162;78m▀\033[38;2;162;162;78;48;2;199;213;20m▀\033[38;2;151;143;140;48;2;119;115;116m▀\033[38;2;240;210;178;48;2;151;143;140m▀\033[38;2;250;236;220;48;2;208;182;161m▀\033[38;2;240;210;178;48;2;250;236;220m▀\033[38;2;59;58;44;48;2;240;210;178m▀\033[38;2;0;0;0;48;2;20;20;12m▀▀\033[38;2;204;225;4;48;2;225;245;0m▀\033[48;2;240;253;0m \033[38;2;204;225;4;48;2;225;245;0m▀ \033[38;2;230;246;3;48;2;240;253;0m▀\033[38;2;20;20;12;48;2;65;69;6m▀\033[0m
\033[48;2;0;0;0m \033[48;2;65;69;6m \033[38;2;240;253;0;48;2;204;225;4m▀\033[48;2;110;117;6m▀\033[38;2;225;245;0;48;2;77;78;38m▀\033[38;2;148;155;28;48;2;59;58;44m▀\033[48;2;20;20;12m \033[38;2;151;143;140m▀\033[48;2;0;0;0m▀\033[38;2;119;115;116;48;2;125;123;87m▀\033[48;2;151;143;140m \033[38;2;154;144;112;48;2;162;162;78m▀\033[38;2;212;225;20;48;2;225;245;0m▀\033[38;2;151;143;140;48;2;154;144;112m▀\033[48;2;151;143;140m \033[38;2;59;58;44;48;2;94;88;81m▀\033[38;2;125;123;87;48;2;0;0;0m▀\033[38;2;151;143;140;48;2;20;20;12m▀\033[48;2;59;58;44m  \033[38;2;102;107;24m▀\033[38;2;148;155;28m▀\033[38;2;177;191;4;48;2;102;107;24m▀\033[48;2;204;225;4m \033[48;2;240;253;0m \033[38;2;110;117;6;48;2;177;191;4m▀\033[0m
\033[48;2;0;0;0m \033[38;2;110;117;6;48;2;145;154;6m▀\033[38;2;177;191;4;48;2;227;235;40m▀\033[48;2;59;58;44m \033[38;2;59;58;44;48;2;0;0;0m▀\033[48;2;20;20;12m \033[38;2;20;20;12;48;2;204;225;4m▀\033[38;2;145;154;6;48;2;177;191;4m▀\033[48;2;20;20;12m \033[48;2;119;115;116m \033[38;2;151;143;140m▀\033[48;2;162;162;78m \033[48;2;230;246;3m \033[38;2;162;162;78;48;2;148;155;28m▀\033[38;2;119;115;116;48;2;94;88;81m▀\033[38;2;77;78;38;48;2;59;58;44m▀\033[48;2;0;0;0m  \033[38;2;20;20;12m▀\033[48;2;110;117;6m▀▀\033[48;2;20;20;12m \033[38;2;148;155;28;48;2;208;182;161m▀\033[38;2;225;245;0;48;2;212;225;20m▀\033[48;2;230;246;3m \033[38;2;177;191;4;48;2;225;245;0m▀\033[38;2;0;0;0;48;2;20;20;12m▀\033[0m
\033[48;2;0;0;0m \033[48;2;177;191;4m \033[38;2;227;235;40;48;2;240;253;0m▀\033[38;2;199;213;20m▀\033[38;2;110;117;6m▀\033[38;2;225;245;0m▀\033[48;2;230;246;3m \033[48;2;177;191;4m \033[48;2;20;20;12m \033[38;2;119;115;116;48;2;94;88;81m▀▀\033[38;2;125;123;87;48;2;59;58;44m▀\033[38;2;225;245;0;48;2;145;154;6m▀\033[38;2;125;123;87;48;2;77;78;38m▀\033[48;2;94;88;81m \033[48;2;20;20;12m \033[48;2;0;0;0m   \033[48;2;145;154;6m \033[48;2;240;253;0m \033[38;2;145;154;6m▀\033[38;2;212;225;20;48;2;225;245;0m▀\033[48;2;204;225;4m  \033[38;2;230;246;3;48;2;240;253;0m▀\033[38;2;65;69;6;48;2;102;107;24m▀\033[0m
\033[48;2;0;0;0m \033[48;2;177;191;4m \033[48;2;230;246;3m \033[48;2;225;245;0m   \033[38;2;230;246;3;48;2;177;191;4m▀\033[38;2;177;191;4;48;2;145;154;6m▀\033[38;2;20;20;12;48;2;65;69;6m▀\033[38;2;59;58;44;48;2;20;20;12m▀▀\033[38;2;20;20;12;48;2;59;58;44m▀\033[38;2;77;78;38;48;2;20;20;12m▀\033[38;2;20;20;12;48;2;59;58;44m▀\033[48;2;20;20;12m   \033[38;2;0;0;0m▀\033[48;2;0;0;0m \033[48;2;177;191;4m \033[48;2;240;253;0m \033[38;2;240;253;0;48;2;225;245;0m▀\033[48;2;204;225;4m   \033[48;2;240;253;0m \033[48;2;110;117;6m \033[0m
\033[48;2;0;0;0m \033[48;2;177;191;4m \033[48;2;240;253;0m \033[38;2;225;245;0m▀\033[48;2;225;245;0m \033[48;2;240;253;0m \033[48;2;110;117;6m \033[38;2;20;20;12;48;2;0;0;0m▀\033[38;2;110;117;6;48;2;102;107;24m▀\033[38;2;20;20;12;48;2;145;154;6m▀\033[48;2;65;69;6m▀\033[38;2;59;58;44m▀\033[48;2;20;20;12m \033[38;2;20;20;12;48;2;59;58;44m▀\033[48;2;102;107;24m▀\033[38;2;59;58;44;48;2;177;191;4m▀\033[38;2;110;117;6;48;2;228;226;85m▀\033[48;2;59;58;44m▀\033[48;2;0;0;0m \033[48;2;145;154;6m \033[48;2;240;253;0m \033[38;2;177;191;4;48;2;145;154;6m▀\033[48;2;204;225;4m   \033[48;2;240;253;0m \033[48;2;110;117;6m \033[0m
\033[48;2;0;0;0m \033[38;2;177;191;4;48;2;145;154;6m▀\033[48;2;240;253;0m \033[38;2;230;246;3m▀\033[38;2;225;245;0m▀ \033[48;2;110;117;6m \033[48;2;0;0;0m \033[38;2;119;115;116;48;2;151;143;140m▀\033[48;2;250;236;220m \033[38;2;205;201;69;48;2;208;182;161m▀\033[38;2;145;154;6;48;2;0;0;0m▀\033[48;2;20;20;12m▀\033[38;2;110;117;6;48;2;0;0;0m▀\033[38;2;148;155;28;48;2;20;20;12m▀\033[38;2;240;210;178;48;2;250;236;220m▀ \033[38;2;119;115;116;48;2;162;162;78m▀\033[48;2;0;0;0m \033[38;2;65;69;6;48;2;20;20;12m▀\033[38;2;225;245;0;48;2;177;191;4m▀\033[48;2;145;154;6m \033[38;2;225;245;0;48;2;240;253;0m▀\033[48;2;204;225;4m \033[38;2;204;225;4;48;2;225;245;0m▀\033[48;2;240;253;0m \033[38;2;110;117;6;48;2;65;69;6m▀\033[0m
\033[48;2;0;0;0m \033[38;2;20;20;12m▀\033[38;2;65;69;6m▀▀▀▀\033[38;2;20;20;12m▀ \033[38;2;59;58;44m▀\033[38;2;77;78;38m▀\033[38;2;59;58;44m▀    ▀\033[38;2;77;78;38m▀\033[38;2;20;20;12m▀  ▀\033[38;2;65;69;6m▀▀▀▀▀\033[38;2;20;20;12m▀\033[0m
==>47-vocaloid-azure
\033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;49;2;118m▀\033[38;2;49;8;112;48;2;108;0;251m▀\033[38;2;59;16;120m▀▀\033[38;2;29;7;63;48;2;74;4;173m▀\033[38;2;0;0;0m▀\033[38;2;49;2;118;48;2;108;0;251m▀\033[38;2;74;4;173m▀▀▀▀▀▀\033[38;2;49;2;118m▀\033[38;2;29;7;63;48;2;104;5;241m▀\033[38;2;0;0;0;48;2;49;2;118m▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;49;2;118m▀\033[38;2;29;7;63;48;2;108;0;251m▀▀\033[38;2;0;0;0;48;2;74;4;173m▀\033[0m
\033[48;2;0;0;0m \033[38;2;29;7;63;48;2;74;4;173m▀\033[48;2;108;0;251m   \033[38;2;104;5;241m▀            \033[38;2;74;4;173m▀\033[38;2;108;0;251;48;2;104;5;241m▀\033[48;2;108;0;251m   \033[38;2;74;4;173m▀\033[38;2;0;0;0;48;2;29;7;63m▀\033[0m
\033[48;2;29;7;63m \033[38;2;100;7;227;48;2;108;0;251m▀      \033[38;2;108;0;251;48;2;104;5;241m▀\033[48;2;100;7;227m▀\033[48;2;104;5;241m▀\033[48;2;108;0;251m  \033[38;2;108;0;251;48;2;112;26;218m▀\033[48;2;138;61;221m▀\033[48;2;104;5;241m▀▀▀▀ \033[48;2;108;0;251m    \033[48;2;74;4;173m \033[0m
\033[48;2;49;8;112m \033[48;2;108;0;251m     \033[38;2;108;0;251;48;2;104;5;241m▀\033[38;2;112;26;218;48;2;144;83;189m▀\033[48;2;172;120;200m▀\033[38;2;100;7;227;48;2;112;26;218m▀\033[38;2;104;5;241;48;2;138;61;221m▀\033[38;2;108;0;251m▀\033[48;2;108;0;251m \033[38;2;116;39;201;48;2;112;26;218m▀\033[48;2;192;152;190m \033[38;2;100;7;227;48;2;144;83;189m▀\033[38;2;112;26;218m▀\033[38;2;100;7;227;48;2;93;2;223m▀▀▀\033[48;2;108;0;251m    \033[38;2;93;2;223;48;2;104;5;241m▀\033[0m
\033[48;2;49;8;112m \033[48;2;108;0;251m   \033[38;2;104;5;241;48;2;100;7;227m▀\033[48;2;108;0;251m \033[38;2;104;5;241;48;2;93;2;223m▀\033[38;2;151;118;165;48;2;192;152;190m▀\033[38;2;172;120;200m▀\033[38;2;138;61;221m▀\033[38;2;172;120;200;48;2;251;238;213m▀\033[38;2;192;152;190;48;2;236;197;199m▀\033[38;2;108;0;251;48;2;104;5;241m▀\033[38;2;112;26;218;48;2;116;39;201m▀\033[38;2;192;152;190;48;2;224;199;174m▀\033[38;2;175;156;162m▀\033[48;2;151;118;165m \033[48;2;93;2;223m   \033[48;2;108;0;251m   \033[38;2;108;0;251;48;2;104;5;241m▀\033[38;2;104;5;241;48;2;93;2;223m▀\033[38;2;29;7;63;48;2;0;0;0m▀\033[0m
\033[38;2;29;7;63;48;2;0;0;0m▀\033[38;2;104;5;241;48;2;93;2;223m▀\033[48;2;108;0;251m \033[48;2;104;5;241m \033[38;2;100;7;227m▀\033[48;2;108;0;251m \033[38;2;112;26;218;48;2;100;7;227m▀\033[38;2;175;156;162;48;2;111;95;98m▀\033[48;2;57;49;63m \033[38;2;114;115;108;48;2;0;0;0m▀\033[38;2;251;238;213;48;2;228;204;191m▀\033[48;2;251;238;213m \033[38;2;172;120;200m▀\033[38;2;117;78;163;48;2;175;156;162m▀\033[38;2;57;49;63;48;2;29;7;63m▀\033[48;2;57;49;63m \033[38;2;172;120;200;48;2;117;78;163m▀\033[38;2;100;7;227;48;2;93;2;223m▀\033[38;2;93;2;223;48;2;74;4;173m▀\033[38;2;100;7;227;48;2;104;5;241m▀\033[48;2;108;0;251m  \033[38;2;108;0;251;48;2;104;5;241m▀ \033[38;2;74;4;173;48;2;93;2;223m▀\033[0m
\033[48;2;0;0;0m \033[38;2;74;4;173;48;2;49;2;118m▀\033[48;2;108;0;251m  \033[38;2;104;5;241m▀ \033[48;2;112;26;218m \033[38;2;195;178;166;48;2;251;238;213m▀\033[38;2;64;38;98;48;2;151;118;165m▀\033[48;2;175;156;162m▀\033[48;2;251;238;213m  \033[38;2;251;238;213;48;2;228;204;191m▀\033[38;2;195;178;166;48;2;251;238;213m▀\033[38;2;29;7;63;48;2;151;118;165m▀\033[38;2;122;93;141;48;2;195;178;166m▀\033[48;2;172;120;200m \033[38;2;74;4;173;48;2;49;2;118m▀\033[38;2;29;7;63;48;2;0;0;0m▀\033[38;2;104;5;241;48;2;74;4;173m▀\033[48;2;108;0;251m  \033[38;2;104;5;241;48;2;100;7;227m▀\033[38;2;108;0;251;48;2;104;5;241m▀\033[48;2;93;2;223m \033[0m
\033[48;2;0;0;0m \033[48;2;29;7;63m \033[48;2;108;0;251m   \033[48;2;104;5;241m \033[38;2;74;4;173;48;2;49;2;118m▀\033[38;2;195;178;166;48;2;0;0;0m▀\033[38;2;251;238;213;48;2;145;138;131m▀\033[48;2;251;238;213m \033[38;2;224;199;174;48;2;236;197;199m▀\033[48;2;234;135;132m  \033[38;2;228;204;191;48;2;236;197;199m▀\033[38;2;251;238;213;48;2;228;204;191m▀\033[38;2;228;204;191;48;2;145;138;131m▀\033[38;2;144;83;189;48;2;29;7;63m▀\033[38;2;29;7;63;48;2;0;0;0m▀ \033[38;2;74;4;173;48;2;49;2;118m▀\033[48;2;108;0;251m  \033[38;2;104;5;241;48;2;100;7;227m▀\033[38;2;108;0;251;48;2;104;5;241m▀\033[48;2;93;2;223m \033[0m
\033[48;2;0;0;0m  \033[48;2;100;7;227m \033[48;2;108;0;251m \033[48;2;104;5;241m \033[48;2;108;0;251m \033[38;2;49;8;112;48;2;74;4;173m▀\033[48;2;0;0;0m  \033[48;2;57;49;63m \033[38;2;145;138;131m▀\033[38;2;228;204;191;48;2;195;178;166m▀\033[48;2;224;199;174m▀\033[38;2;114;115;108;48;2;89;88;93m▀\033[38;2;29;7;63m▀\033[38;2;0;0;0m▀\033[48;2;0;0;0m   \033[38;2;29;7;63m▀\033[38;2;108;0;251;48;2;100;7;227m▀\033[48;2;108;0;251m \033[48;2;100;7;227m \033[48;2;104;5;241m \033[48;2;93;2;223m \033[0m
\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;29;7;63m▀\033[38;2;100;7;227;48;2;104;5;241m▀\033[48;2;108;0;251m \033[38;2;104;5;241m▀ \033[38;2;100;7;227;48;2;74;4;173m▀\033[38;2;29;7;63;48;2;175;156;162m▀\033[38;2;145;138;131;48;2;251;238;213m▀\033[38;2;251;238;213;48;2;195;178;166m▀\033[38;2;175;156;162;48;2;145;138;131m▀\033[48;2;151;118;165m \033[38;2;117;78;163;48;2;112;26;218m▀\033[38;2;145;138;131;48;2;114;115;108m▀\033[38;2;251;238;213;48;2;145;138;131m▀\033[48;2;224;199;174m▀\033[38;2;224;199;174;48;2;251;238;213m▀\033[38;2;57;49;63;48;2;228;204;191m▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;29;7;63m▀\033[38;2;100;7;227;48;2;104;5;241m▀\033[48;2;108;0;251m \033[38;2;100;7;227m▀\033[48;2;104;5;241m▀\033[48;2;108;0;251m \033[48;2;29;7;63m \033[0m
\033[48;2;0;0;0m \033[48;2;29;7;63m \033[38;2;108;0;251;48;2;100;7;227m▀\033[48;2;59;16;120m▀\033[38;2;104;5;241;48;2;64;38;98m▀\033[38;2;74;4;173;48;2;57;49;63m▀\033[48;2;29;7;63m \033[38;2;145;138;131m▀\033[38;2;175;156;162;48;2;0;0;0m▀\033[48;2;114;115;108m \033[48;2;175;156;162m \033[38;2;151;118;165;48;2;144;83;189m▀\033[38;2;100;7;227;48;2;104;5;241m▀\033[38;2;145;138;131;48;2;122;93;141m▀\033[48;2;145;138;131m \033[38;2;57;49;63;48;2;89;88;93m▀\033[38;2;111;95;98;48;2;0;0;0m▀\033[38;2;145;138;131m▀\033[48;2;57;49;63m  \033[38;2;59;16;120m▀\033[38;2;74;4;173m▀\033[48;2;59;16;120m▀\033[38;2;104;5;241;48;2;100;7;227m▀\033[48;2;108;0;251m \033[38;2;49;2;118;48;2;74;4;173m▀\033[0m
\033[48;2;0;0;0m \033[38;2;49;2;118;48;2;74;4;173m▀\033[38;2;74;4;173;48;2;138;61;221m▀\033[48;2;57;49;63m \033[38;2;57;49;63;48;2;0;0;0m▀\033[38;2;0;0;0;48;2;29;7;63m▀\033[38;2;29;7;63;48;2;93;2;223m▀\033[48;2;74;4;173m \033[48;2;0;0;0m \033[48;2;114;115;108m \033[48;2;145;138;131m \033[48;2;117;78;163m \033[48;2;108;0;251m \033[48;2;117;78;163m \033[48;2;114;115;108m \033[38;2;89;88;93;48;2;57;49;63m▀\033[48;2;0;0;0m   \033[38;2;29;7;63;48;2;49;8;112m▀\033[38;2;0;0;0m▀\033[38;2;29;7;63;48;2;0;0;0m▀\033[38;2;59;16;120;48;2;192;152;190m▀\033[38;2;104;5;241;48;2;112;26;218m▀\033[38;2;108;0;251;48;2;104;5;241m▀\033[48;2;93;2;223m \033[0m
\033[48;2;0;0;0m \033[48;2;74;4;173m \033[38;2;138;61;221;48;2;108;0;251m▀\033[38;2;116;39;201m▀\033[38;2;49;2;118m▀\033[38;2;104;5;241m▀\033[48;2;104;5;241m \033[48;2;74;4;173m \033[38;2;29;7;63;48;2;0;0;0m▀\033[38;2;111;95;98;48;2;89;88;93m▀\033[48;2;114;115;108m \033[38;2;122;93;141;48;2;57;49;63m▀\033[38;2;104;5;241;48;2;74;4;173m▀\033[38;2;122;93;141;48;2;57;49;63m▀\033[38;2;111;95;98;48;2;89;88;93m▀\033[48;2;0;0;0m    \033[48;2;74;4;173m \033[48;2;108;0;251m \033[38;2;74;4;173m▀\033[48;2;104;5;241m \033[48;2;100;7;227m \033[48;2;93;2;223m \033[48;2;108;0;251m \033[38;2;29;7;63;48;2;49;8;112m▀\033[0m
\033[48;2;0;0;0m \033[48;2;93;2;223m \033[48;2;108;0;251m    \033[38;2;104;5;241;48;2;74;4;173m▀ \033[38;2;0;0;0;48;2;29;7;63m▀\033[38;2;57;49;63m▀\033[48;2;0;0;0m▀\033[38;2;29;7;63;48;2;57;49;63m▀\033[38;2;64;38;98;48;2;0;0;0m▀\033[38;2;0;0;0;48;2;29;7;63m▀  \033[48;2;0;0;0m   \033[48;2;74;4;173m \033[48;2;108;0;251m \033[38;2;104;5;241;48;2;100;7;227m▀  \033[48;2;93;2;223m \033[48;2;108;0;251m \033[48;2;49;8;112m \033[0m
\033[48;2;0;0;0m \033[38;2;93;2;223;48;2;74;4;173m▀\033[48;2;108;0;251m    \033[48;2;49;8;112m \033[38;2;29;7;63;48;2;0;0;0m▀\033[48;2;49;2;118m \033[38;2;29;7;63;48;2;74;4;173m▀\033[38;2;0;0;0;48;2;29;7;63m▀ \033[48;2;0;0;0m \033[48;2;29;7;63m \033[38;2;0;0;0;48;2;49;8;112m▀\033[38;2;29;7;63;48;2;74;4;173m▀\033[38;2;49;8;112;48;2;138;61;221m▀\033[48;2;29;7;63m▀\033[48;2;0;0;0m \033[38;2;74;4;173;48;2;49;2;118m▀\033[48;2;108;0;251m \033[48;2;74;4;173m \033[48;2;100;7;227m  \033[48;2;93;2;223m \033[48;2;108;0;251m \033[48;2;49;8;112m \033[0m
\033[48;2;0;0;0m \033[48;2;74;4;173m \033[48;2;108;0;251m  \033[38;2;104;5;241m▀ \033[48;2;59;16;120m \033[48;2;0;0;0m \033[38;2;111;95;98;48;2;145;138;131m▀\033[38;2;236;197;199;48;2;251;238;213m▀\033[38;2;144;83;189;48;2;224;199;174m▀\033[38;2;74;4;173;48;2;0;0;0m▀▀\033[38;2;49;2;118m▀\033[38;2;117;78;163m▀\033[38;2;236;197;199;48;2;251;238;213m▀ \033[38;2;111;95;98;48;2;122;93;141m▀\033[48;2;0;0;0m \033[38;2;49;8;112;48;2;29;7;63m▀\033[38;2;104;5;241;48;2;74;4;173m▀\033[38;2;59;16;120;48;2;49;2;118m▀\033[38;2;104;5;241;48;2;108;0;251m▀\033[38;2;100;7;227;48;2;104;5;241m▀\033[38;2;93;2;223;48;2;100;7;227m▀\033[48;2;108;0;251m \033[38;2;49;8;112;48;2;29;7;63m▀\033[0m
\033[48;2;0;0;0m \033[38;2;29;7;63m▀▀▀▀▀▀ \033[38;2;57;49;63m▀▀▀    ▀▀\033[38;2;29;7;63m▀    ▀▀▀▀\033[0m
==>48-vocaloid-blush
\033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;147;16;4m▀\033[38;2;110;12;4;48;2;252;19;0m▀\033[38;2;147;16;4m▀▀\033[38;2;21;9;8;48;2;186;16;2m▀\033[38;2;0;0;0m▀\033[38;2;110;12;4;48;2;252;19;0m▀\033[38;2;147;16;4m▀▀▀▀▀▀\033[38;2;110;12;4m▀\033[38;2;68;9;5;48;2;245;21;1m▀\033[38;2;0;0;0;48;2;110;12;4m▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;110;12;4m▀\033[38;2;68;9;5;48;2;245;21;1m▀\033[48;2;252;19;0m▀\033[38;2;0;0;0;48;2;186;16;2m▀\033[48;2;21;9;8m▀\033[0m
\033[48;2;0;0;0m \033[38;2;68;9;5;48;2;186;16;2m▀\033[48;2;252;19;0m \033[38;2;252;19;0;48;2;245;21;1m▀\033[38;2;245;21;1;48;2;252;19;0m▀▀  ▀▀ ▀▀▀▀▀  \033[38;2;186;16;2m▀\033[38;2;245;21;1m▀ ▀\033[38;2;252;19;0;48;2;245;21;1m▀\033[38;2;186;16;2;48;2;252;19;0m▀\033[38;2;0;0;0;48;2;68;9;5m▀\033[0m
\033[38;2;21;9;8;48;2;68;9;5m▀\033[38;2;235;18;0;48;2;252;19;0m▀\033[38;2;252;19;0;48;2;245;21;1m▀▀▀▀\033[48;2;252;19;0m \033[38;2;252;19;0;48;2;245;21;1m▀\033[48;2;235;18;0m▀▀\033[48;2;252;19;0m   \033[38;2;245;21;1;48;2;222;34;16m▀\033[38;2;252;19;0;48;2;240;66;49m▀\033[48;2;235;18;0m▀\033[48;2;245;21;1m▀\033[48;2;235;18;0m▀\033[48;2;245;21;1m▀\033[38;2;245;21;1;48;2;235;18;0m▀\033[48;2;252;19;0m    \033[38;2;147;16;4;48;2;186;16;2m▀\033[0m
\033[38;2;110;12;4;48;2;147;16;4m▀\033[48;2;252;19;0m \033[38;2;245;21;1m▀▀▀▀\033[48;2;235;18;0m▀\033[38;2;222;34;16;48;2;217;79;61m▀\033[38;2;240;66;49;48;2;227;123;107m▀\033[48;2;222;34;16m \033[38;2;245;21;1;48;2;240;66;49m▀\033[38;2;252;19;0m▀\033[48;2;252;19;0m \033[48;2;240;66;49m \033[38;2;227;123;107;48;2;226;163;147m▀\033[38;2;235;18;0;48;2;217;79;61m▀\033[38;2;222;34;16m▀\033[48;2;235;18;0m \033[38;2;235;18;0;48;2;222;34;16m▀▀\033[38;2;245;21;1;48;2;235;18;0m▀▀▀\033[38;2;252;19;0m▀\033[38;2;235;18;0;48;2;186;16;2m▀\033[48;2;21;9;8m \033[0m
\033[38;2;110;12;4;48;2;68;9;5m▀\033[48;2;252;19;0m \033[48;2;245;21;1m  \033[48;2;235;18;0m \033[48;2;252;19;0m \033[38;2;235;18;0;48;2;186;16;2m▀\033[38;2;227;123;107;48;2;226;163;147m▀ \033[38;2;217;79;61;48;2;227;123;107m▀\033[38;2;227;123;107;48;2;246;224;206m▀\033[38;2;226;163;147;48;2;235;202;185m▀\033[38;2;252;19;0;48;2;235;18;0m▀\033[38;2;222;34;16;48;2;240;66;49m▀\033[38;2;226;163;147;48;2;235;202;185m▀\033[38;2;227;123;107m▀\033[48;2;227;123;107m \033[38;2;186;16;2;48;2;235;18;0m▀\033[48;2;222;34;16m \033[38;2;222;34;16;48;2;235;18;0m▀\033[38;2;235;18;0;48;2;252;19;0m▀\033[38;2;245;21;1m▀\033[38;2;235;18;0m▀\033[38;2;245;21;1m▀\033[38;2;222;34;16;48;2;235;18;0m▀\033[48;2;21;9;8m \033[0m
\033[38;2;68;9;5;48;2;21;9;8m▀\033[48;2;235;18;0m \033[48;2;252;19;0m \033[38;2;252;19;0;48;2;245;21;1m▀\033[48;2;252;19;0m  \033[38;2;222;34;16;48;2;245;21;1m▀\033[38;2;170;117;106;48;2;132;106;99m▀\033[38;2;67;64;63;48;2;34;38;38m▀\033[38;2;163;72;60;48;2;22;20;20m▀\033[38;2;246;224;206;48;2;235;202;185m▀\033[48;2;254;249;233m \033[38;2;227;123;107m▀\033[38;2;217;79;61;48;2;166;156;150m▀\033[38;2;84;70;67;48;2;22;20;20m▀\033[48;2;67;64;63m \033[38;2;227;123;107;48;2;163;72;60m▀\033[48;2;235;18;0m \033[38;2;222;34;16;48;2;147;16;4m▀\033[48;2;235;18;0m \033[38;2;235;18;0;48;2;245;21;1m▀▀▀\033[38;2;245;21;1;48;2;252;19;0m▀\033[38;2;235;18;0;48;2;186;16;2m▀\033[38;2;21;9;8;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m \033[38;2;186;16;2;48;2;110;12;4m▀\033[48;2;252;19;0m \033[38;2;235;18;0m▀\033[38;2;245;21;1m▀ \033[48;2;222;34;16m \033[38;2;235;202;185;48;2;254;249;233m▀\033[38;2;89;35;30;48;2;170;117;106m▀\033[38;2;121;52;43;48;2;166;156;150m▀\033[38;2;246;224;206;48;2;254;249;233m▀\033[48;2;246;224;206m \033[38;2;254;249;233m▀\033[38;2;166;156;150m▀\033[38;2;68;9;5;48;2;227;123;107m▀\033[38;2;132;106;99;48;2;226;163;147m▀\033[48;2;227;123;107m \033[38;2;186;16;2;48;2;147;16;4m▀\033[38;2;68;9;5;48;2;0;0;0m▀\033[38;2;245;21;1;48;2;186;16;2m▀\033[48;2;252;19;0m▀▀\033[48;2;245;21;1m \033[48;2;252;19;0m \033[48;2;186;16;2m \033[0m
\033[48;2;0;0;0m \033[48;2;68;9;5m \033[48;2;252;19;0m \033[48;2;245;21;1m  \033[38;2;252;19;0m▀\033[38;2;222;34;16;48;2;110;12;4m▀\033[38;2;166;156;150;48;2;0;0;0m▀\033[38;2;254;249;233;48;2;166;156;150m▀\033[48;2;254;249;233m \033[48;2;235;202;185m \033[38;2;226;163;147;48;2;227;123;107m▀▀\033[48;2;235;202;185m \033[48;2;246;224;206m \033[38;2;246;224;206;48;2;170;117;106m▀\033[38;2;217;79;61;48;2;68;9;5m▀\033[38;2;21;9;8;48;2;0;0;0m▀ \033[38;2;147;16;4;48;2;110;12;4m▀\033[48;2;252;19;0m \033[38;2;235;18;0m▀\033[48;2;245;21;1m \033[48;2;252;19;0m \033[48;2;186;16;2m \033[0m
\033[48;2;0;0;0m \033[48;2;21;9;8m \033[48;2;235;18;0m \033[48;2;252;19;0m \033[48;2;245;21;1m \033[48;2;252;19;0m \033[38;2;110;12;4;48;2;186;16;2m▀\033[48;2;0;0;0m  \033[48;2;58;43;41m \033[38;2;170;117;106m▀\033[38;2;235;202;185;48;2;166;156;150m▀\033[48;2;235;202;185m \033[38;2;132;106;99;48;2;84;70;67m▀\033[38;2;34;38;38m▀\033[38;2;0;0;0m▀\033[48;2;22;20;20m▀\033[48;2;0;0;0m  \033[38;2;68;9;5;48;2;21;9;8m▀\033[38;2;252;19;0;48;2;235;18;0m▀\033[48;2;245;21;1m▀ \033[48;2;252;19;0m \033[48;2;186;16;2m \033[38;2;0;0;0;48;2;21;9;8m▀\033[0m
\033[48;2;0;0;0m \033[38;2;21;9;8;48;2;68;9;5m▀\033[38;2;235;18;0;48;2;252;19;0m▀   \033[38;2;252;19;0;48;2;186;16;2m▀\033[38;2;68;9;5;48;2;166;156;150m▀\033[38;2;131;134;134;48;2;254;249;233m▀\033[38;2;254;249;233;48;2;235;202;185m▀\033[38;2;166;156;150;48;2;131;134;134m▀\033[38;2;132;106;99;48;2;170;117;106m▀\033[38;2;163;72;60;48;2;222;34;16m▀\033[48;2;131;134;134m \033[38;2;246;224;206m▀\033[38;2;254;249;233;48;2;235;202;185m▀\033[38;2;235;202;185;48;2;254;249;233m▀\033[38;2;67;64;63;48;2;235;202;185m▀\033[38;2;0;0;0;48;2;22;20;20m▀\033[48;2;21;9;8m▀\033[48;2;235;18;0m \033[48;2;252;19;0m \033[38;2;245;21;1m▀\033[38;2;252;19;0;48;2;245;21;1m▀\033[48;2;252;19;0m \033[38;2;21;9;8;48;2;68;9;5m▀\033[0m
\033[48;2;0;0;0m \033[48;2;68;9;5m \033[38;2;252;19;0;48;2;235;18;0m▀\033[48;2;164;33;21m▀\033[38;2;235;18;0;48;2;84;70;67m▀\033[38;2;164;33;21;48;2;67;64;63m▀\033[48;2;34;38;38m \033[38;2;166;156;150;48;2;22;20;20m▀\033[48;2;21;9;8m▀\033[48;2;102;101;101m \033[48;2;166;156;150m \033[48;2;170;117;106m \033[48;2;235;18;0m \033[38;2;131;134;134;48;2;132;106;99m▀\033[48;2;131;134;134m \033[38;2;67;64;63;48;2;102;101;101m▀\033[38;2;102;101;101;48;2;0;0;0m▀\033[38;2;131;134;134;48;2;22;20;20m▀\033[38;2;67;64;63;48;2;84;70;67m▀\033[38;2;58;43;41m▀\033[38;2;121;52;43;48;2;67;64;63m▀\033[38;2;164;33;21m▀\033[38;2;186;16;2;48;2;89;35;30m▀\033[38;2;245;21;1;48;2;235;18;0m▀\033[48;2;245;21;1m \033[38;2;110;12;4;48;2;186;16;2m▀\033[0m
\033[48;2;0;0;0m \033[38;2;110;12;4;48;2;147;16;4m▀\033[38;2;186;16;2;48;2;240;66;49m▀\033[48;2;67;64;63m \033[38;2;67;64;63;48;2;0;0;0m▀\033[38;2;21;9;8;48;2;68;9;5m▀\033[48;2;222;34;16m▀\033[38;2;147;16;4;48;2;186;16;2m▀\033[48;2;21;9;8m \033[38;2;132;106;99;48;2;102;101;101m▀\033[38;2;166;156;150;48;2;131;134;134m▀\033[48;2;163;72;60m \033[48;2;252;19;0m \033[48;2;163;72;60m \033[38;2;131;134;134;48;2;102;101;101m▀\033[38;2;84;70;67;48;2;58;43;41m▀\033[48;2;0;0;0m  \033[38;2;22;20;20m▀\033[48;2;110;12;4m▀▀\033[38;2;34;38;38;48;2;21;9;8m▀\033[38;2;164;33;21;48;2;226;163;147m▀\033[38;2;235;18;0;48;2;240;66;49m▀\033[38;2;252;19;0;48;2;235;18;0m▀ \033[38;2;0;0;0;48;2;21;9;8m▀\033[0m
\033[48;2;0;0;0m \033[48;2;186;16;2m \033[38;2;240;66;49;48;2;252;19;0m▀\033[38;2;222;34;16;48;2;245;21;1m▀\033[38;2;110;12;4;48;2;252;19;0m▀\033[38;2;235;18;0;48;2;245;21;1m▀\033[38;2;245;21;1;48;2;235;18;0m▀\033[48;2;186;16;2m \033[48;2;21;9;8m \033[38;2;102;101;101;48;2;84;70;67m▀\033[38;2;131;134;134;48;2;102;101;101m▀\033[38;2;132;106;99;48;2;67;64;63m▀\033[38;2;245;21;1;48;2;186;16;2m▀\033[38;2;163;72;60;48;2;89;35;30m▀\033[48;2;102;101;101m \033[48;2;22;20;20m \033[48;2;0;0;0m   \033[38;2;147;16;4;48;2;186;16;2m▀\033[48;2;252;19;0m \033[38;2;186;16;2;48;2;245;21;1m▀\033[38;2;222;34;16;48;2;252;19;0m▀\033[48;2;235;18;0m▀\033[38;2;235;18;0;48;2;222;34;16m▀\033[48;2;252;19;0m \033[48;2;68;9;5m \033[0m
\033[48;2;0;0;0m \033[48;2;186;16;2m \033[48;2;245;21;1m \033[48;2;235;18;0m  \033[38;2;235;18;0;48;2;245;21;1m▀\033[48;2;147;16;4m▀\033[38;2;186;16;2m▀\033[38;2;21;9;8;48;2;68;9;5m▀\033[38;2;58;43;41;48;2;22;20;20m▀\033[48;2;21;9;8m▀\033[38;2;21;9;8;48;2;58;43;41m▀\033[38;2;68;9;5;48;2;21;9;8m▀ \033[38;2;34;38;38m▀\033[38;2;22;20;20m▀\033[38;2;21;9;8;48;2;22;20;20m▀\033[38;2;0;0;0;48;2;21;9;8m▀\033[48;2;0;0;0m \033[48;2;186;16;2m \033[48;2;252;19;0m \033[38;2;245;21;1;48;2;235;18;0m▀\033[38;2;252;19;0;48;2;245;21;1m▀\033[38;2;222;34;16;48;2;235;18;0m▀\033[48;2;222;34;16m \033[48;2;252;19;0m \033[48;2;110;12;4m \033[0m
\033[48;2;0;0;0m \033[48;2;186;16;2m \033[38;2;245;21;1;48;2;252;19;0m▀\033[38;2;235;18;0;48;2;245;21;1m▀▀\033[48;2;252;19;0m \033[48;2;110;12;4m \033[38;2;21;9;8;48;2;0;0;0m▀\033[38;2;110;12;4;48;2;89;35;30m▀\033[38;2;21;9;8;48;2;164;33;21m▀\033[38;2;22;20;20;48;2;68;9;5m▀\033[38;2;34;38;38m▀\033[48;2;21;9;8m \033[38;2;34;38;38;48;2;58;43;41m▀\033[38;2;22;20;20;48;2;89;35;30m▀\033[38;2;58;43;41;48;2;186;16;2m▀\033[38;2;110;12;4;48;2;217;79;61m▀\033[48;2;34;38;38m▀\033[48;2;0;0;0m \033[48;2;147;16;4m \033[48;2;252;19;0m \033[48;2;186;16;2m \033[48;2;245;21;1m \033[48;2;235;18;0m \033[48;2;222;34;16m \033[48;2;252;19;0m \033[48;2;110;12;4m \033[0m
\033[48;2;0;0;0m \033[38;2;186;16;2;48;2;147;16;4m▀\033[48;2;252;19;0m \033[38;2;245;21;1m▀▀ \033[38;2;110;12;4;48;2;147;16;4m▀\033[48;2;0;0;0m \033[38;2;132;106;99;48;2;131;134;134m▀\033[38;2;235;202;185;48;2;254;249;233m▀\033[38;2;217;79;61;48;2;235;202;185m▀\033[38;2;186;16;2;48;2;21;9;8m▀\033[38;2;147;16;4;48;2;0;0;0m▀\033[38;2;110;12;4m▀\033[38;2;164;33;21;48;2;22;20;20m▀\033[38;2;235;202;185;48;2;246;224;206m▀\033[38;2;254;249;233m▀\033[38;2;132;106;99;48;2;170;117;106m▀\033[48;2;0;0;0m \033[38;2;68;9;5;48;2;21;9;8m▀\033[38;2;235;18;0;48;2;186;16;2m▀\033[48;2;147;16;4m \033[48;2;252;19;0m \033[38;2;222;34;16;48;2;245;21;1m▀\033[38;2;186;16;2;48;2;235;18;0m▀\033[48;2;252;19;0m \033[38;2;110;12;4;48;2;68;9;5m▀\033[0m
\033[48;2;0;0;0m \033[38;2;21;9;8m▀\033[38;2;68;9;5m▀▀▀▀\033[38;2;21;9;8m▀ \033[38;2;22;20;20m▀\033[38;2;84;70;67m▀\033[38;2;58;43;41m▀    ▀▀\033[38;2;68;9;5m▀  \033[38;2;21;9;8m▀▀\033[38;2;68;9;5m▀▀▀▀\033[0m
==>49-blossom
\033[48;2;0;0;0m     \033[38;2;0;0;0;48;2;124;119;106m▀\033[38;2;112;106;91;48;2;244;232;219m▀\033[38;2;135;156;136;48;2;227;186;143m▀\033[48;2;229;216;197m▀\033[38;2;56;56;47m▀\033[38;2;37;39;35;48;2;206;193;175m▀\033[38;2;124;119;106;48;2;244;232;219m▀\033[38;2;85;84;76;48;2;227;186;143m▀▀\033[38;2;112;106;91m▀\033[38;2;135;156;136;48;2;208;205;182m▀\033[38;2;85;84;76;48;2;244;232;219m▀\033[38;2;0;0;0;48;2;94;113;96m▀\033[48;2;0;0;0m     \033[38;2;0;0;0;48;2;112;106;91m▀\033[48;2;185;189;174m▀\033[48;2;208;205;182m▀\033[48;2;185;189;174m▀\033[48;2;85;84;76m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;37;39;35m▀\033[48;2;185;189;174m▀\033[38;2;124;119;106;48;2;229;225;210m▀\033[38;2;244;232;219;48;2;185;189;174m▀\033[38;2;223;197;179;48;2;229;225;210m▀\033[48;2;227;186;143m   \033[38;2;229;216;197m▀\033[38;2;244;232;219;48;2;223;197;179m▀\033[38;2;223;197;179;48;2;229;216;197m▀\033[48;2;227;186;143m \033[38;2;212;180;145m▀\033[48;2;183;180;154m▀\033[48;2;212;180;145m \033[38;2;244;232;219m▀\033[38;2;56;56;47;48;2;185;189;174m▀\033[38;2;0;0;0;48;2;56;56;47m▀\033[38;2;37;39;35;48;2;208;205;182m▀\033[38;2;123;126;117;48;2;229;216;197m▀\033[38;2;208;205;182;48;2;227;186;143m▀\033[38;2;244;232;219;48;2;212;180;145m▀\033[38;2;229;216;197m▀\033[38;2;223;197;179m▀\033[38;2;244;232;219;48;2;229;216;197m▀\033[38;2;185;189;174;48;2;85;84;76m▀\033[0m
\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;37;39;35m▀\033[38;2;178;171;135;48;2;229;216;197m▀\033[38;2;244;232;219;48;2;206;193;175m▀\033[38;2;209;210;199;48;2;132;171;148m▀\033[38;2;155;181;160;48;2;185;189;174m▀\033[38;2;208;205;182;48;2;153;164;140m▀\033[38;2;206;193;175;48;2;183;180;154m▀\033[38;2;227;186;143;48;2;208;161;141m▀\033[48;2;212;180;145m▀\033[48;2;208;161;141m▀\033[38;2;208;161;141;48;2;212;180;145m▀\033[38;2;212;180;145;48;2;178;171;135m▀\033[38;2;183;180;154;48;2;206;193;175m▀\033[38;2;153;164;140;48;2;183;180;154m▀\033[38;2;178;171;135;48;2;123;149;113m▀\033[48;2;182;154;130m▀ \033[38;2;229;216;197;48;2;185;189;174m▀\033[48;2;178;171;135m▀\033[38;2;212;180;145;48;2;182;154;130m▀\033[38;2;208;161;141;48;2;212;180;145m▀\033[48;2;227;186;143m \033[38;2;227;186;143;48;2;208;161;141m▀\033[38;2;212;180;145;48;2;229;216;197m▀\033[38;2;244;232;219;48;2;123;126;117m▀\033[38;2;94;113;96;48;2;0;0;0m▀\033[0m
\033[38;2;0;0;0;48;2;56;56;47m▀\033[38;2;112;106;91;48;2;229;225;210m▀\033[38;2;229;225;210;48;2;155;181;160m▀\033[38;2;144;154;118;48;2;132;171;148m▀\033[48;2;153;164;140m \033[38;2;155;181;160;48;2;185;189;174m▀\033[38;2;135;156;136;48;2;206;193;175m▀\033[38;2;153;164;140;48;2;144;154;118m▀\033[38;2;178;171;135;48;2;183;180;154m▀\033[38;2;227;186;143;48;2;208;161;141m▀\033[38;2;208;161;141;48;2;178;171;135m▀\033[38;2;144;154;118;48;2;123;126;117m▀\033[38;2;123;149;113;48;2;182;154;130m▀\033[48;2;208;161;141m \033[38;2;212;180;145m▀ \033[48;2;182;154;130m \033[38;2;153;164;140;48;2;208;205;182m▀\033[38;2;183;180;154;48;2;227;186;143m▀\033[38;2;144;154;118;48;2;185;189;174m▀\033[48;2;123;149;113m▀\033[38;2;178;171;135;48;2;144;154;118m▀\033[48;2;183;180;154m▀\033[38;2;208;205;182m▀\033[38;2;244;232;219;48;2;208;205;182m▀\033[38;2;153;164;140;48;2;244;232;219m▀\033[38;2;37;39;35;48;2;209;210;199m▀\033[38;2;0;0;0;48;2;85;84;76m▀\033[0m
\033[38;2;85;84;76;48;2;0;0;0m▀\033[38;2;229;225;210;48;2;209;210;199m▀\033[38;2;135;156;136;48;2;208;205;182m▀\033[38;2;155;181;160;48;2;153;164;140m▀ \033[48;2;123;149;113m▀\033[48;2;144;154;118m \033[38;2;100;140;109;48;2;135;156;136m▀\033[38;2;132;171;148;48;2;178;171;135m▀\033[48;2;144;154;118m \033[38;2;123;149;113;48;2;135;156;136m▀\033[38;2;182;154;130;48;2;178;171;135m▀\033[38;2;227;186;143;48;2;208;161;141m▀\033[38;2;208;161;141;48;2;182;154;130m▀▀▀\033[38;2;182;154;130;48;2;208;161;141m▀\033[38;2;185;189;174;48;2;124;119;106m▀\033[38;2;223;197;179;48;2;153;164;140m▀\033[48;2;206;193;175m \033[38;2;153;164;140;48;2;212;180;145m▀\033[38;2;124;119;106;48;2;144;154;118m▀ \033[38;2;123;149;113;48;2;142;130;122m▀\033[38;2;178;171;135;48;2;153;164;140m▀\033[38;2;182;154;130;48;2;183;180;154m▀\033[38;2;223;197;179;48;2;229;225;210m▀\033[38;2;209;210;199;48;2;112;106;91m▀\033[0m
\033[48;2;0;0;0m \033[38;2;37;39;35;48;2;56;56;47m▀\033[38;2;209;210;199;48;2;244;232;219m▀\033[38;2;229;216;197m▀\033[38;2;135;156;136;48;2;229;225;210m▀\033[38;2;100;140;109;48;2;185;189;174m▀▀\033[38;2;123;149;113;48;2;183;180;154m▀\033[38;2;178;171;135;48;2;144;154;118m▀\033[38;2;123;126;117;48;2;142;130;122m▀\033[38;2;135;156;136m▀\033[38;2;185;189;174;48;2;212;180;145m▀\033[38;2;182;154;130;48;2;208;161;141m▀\033[38;2;208;161;141;48;2;212;180;145m▀▀\033[48;2;208;161;141m \033[38;2;208;161;141;48;2;182;154;130m▀\033[48;2;212;180;145m▀\033[38;2;206;193;175;48;2;208;161;141m▀\033[38;2;183;180;154;48;2;144;154;118m▀\033[38;2;208;161;141;48;2;178;171;135m▀\033[38;2;144;154;118;48;2;208;205;182m▀\033[38;2;100;140;109;48;2;183;180;154m▀\033[38;2;123;149;113;48;2;208;161;141m▀\033[38;2;185;189;174m▀\033[38;2;244;232;219;48;2;229;216;197m▀\033[38;2;124;119;106;48;2;208;205;182m▀\033[38;2;0;0;0;48;2;135;156;136m▀\033[48;2;37;39;35m▀\033[0m
\033[48;2;0;0;0m \033[38;2;85;84;76;48;2;56;56;47m▀\033[48;2;244;232;219m \033[48;2;185;189;174m \033[48;2;155;181;160m \033[38;2;209;210;199m▀\033[38;2;229;225;210;48;2;185;189;174m▀\033[38;2;123;149;113;48;2;209;210;199m▀\033[38;2;144;154;118;48;2;182;154;130m▀\033[38;2;182;154;130;48;2;208;161;141m▀ \033[38;2;212;180;145m▀\033[38;2;182;154;130m▀\033[38;2;212;180;145m▀\033[48;2;182;154;130m▀\033[48;2;208;161;141m▀\033[38;2;208;161;141;48;2;229;216;197m▀\033[38;2;212;180;145;48;2;208;161;141m▀ \033[38;2;182;154;130m▀\033[38;2;206;193;175m▀▀\033[48;2;182;154;130m \033[38;2;208;161;141m▀\033[48;2;208;161;141m \033[38;2;229;225;210;48;2;185;189;174m▀\033[38;2;155;181;160;48;2;113;168;143m▀\033[38;2;229;225;210;48;2;185;189;174m▀\033[38;2;153;164;140;48;2;208;205;182m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;185;189;174;48;2;85;84;76m▀\033[48;2;229;225;210m▀\033[38;2;155;181;160;48;2;135;156;136m▀\033[48;2;132;171;148m \033[38;2;113;168;143m▀\033[38;2;155;181;160;48;2;113;168;143m▀\033[38;2;142;130;122;48;2;135;156;136m▀\033[38;2;208;161;141;48;2;142;130;122m▀\033[38;2;178;171;135;48;2;182;154;130m▀\033[38;2;208;161;141m▀\033[38;2;212;180;145;48;2;208;205;182m▀\033[38;2;229;216;197;48;2;244;232;219m▀\033[38;2;223;197;179m▀▀\033[38;2;229;225;210;48;2;229;216;197m▀\033[38;2;229;216;197;48;2;183;180;154m▀\033[38;2;208;161;141;48;2;229;216;197m▀\033[38;2;182;154;130;48;2;223;197;179m▀\033[48;2;208;161;141m \033[48;2;212;180;145m \033[48;2;182;154;130m \033[38;2;142;130;122;48;2;208;161;141m▀\033[38;2;135;156;136;48;2;132;171;148m▀\033[48;2;113;168;143m  \033[38;2;155;181;160;48;2;185;189;174m▀\033[38;2;206;193;175;48;2;183;180;154m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;56;56;47m▀\033[38;2;142;130;122;48;2;206;193;175m▀\033[38;2;208;205;182;48;2;209;210;199m▀\033[38;2;155;181;160;48;2;185;189;174m▀\033[48;2;132;171;148m▀\033[48;2;113;168;143m \033[38;2;132;171;148m▀\033[38;2;142;130;122;48;2;182;154;130m▀\033[38;2;182;154;130;48;2;212;180;145m▀\033[38;2;142;130;122;48;2;208;161;141m▀\033[38;2;124;119;106;48;2;178;171;135m▀\033[48;2;244;232;219m   \033[38;2;206;193;175;48;2;229;216;197m▀\033[48;2;124;119;106m \033[38;2;112;106;91;48;2;85;84;76m▀\033[38;2;185;189;174;48;2;182;154;130m▀\033[38;2;212;180;145;48;2;227;186;143m▀\033[48;2;208;161;141m \033[38;2;182;154;130;48;2;212;180;145m▀\033[48;2;208;161;141m▀\033[38;2;132;171;148;48;2;113;168;143m▀ \033[38;2;113;168;143;48;2;155;181;160m▀\033[48;2;209;210;199m \033[38;2;85;84;76;48;2;37;39;35m▀\033[0m
\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;56;56;47m▀\033[38;2;208;205;182;48;2;209;210;199m▀\033[38;2;185;189;174;48;2;113;168;143m▀\033[48;2;132;171;148m \033[38;2;155;181;160;48;2;153;164;140m▀\033[48;2;132;171;148m \033[48;2;113;168;143m \033[38;2;113;168;143;48;2;123;149;113m▀\033[38;2;182;154;130;48;2;142;130;122m▀\033[38;2;212;180;145;48;2;183;180;154m▀\033[38;2;223;197;179;48;2;244;232;219m▀\033[38;2;244;232;219;48;2;229;225;210m▀\033[48;2;229;216;197m \033[38;2;229;225;210m▀\033[48;2;229;225;210m \033[38;2;223;197;179;48;2;229;216;197m▀\033[38;2;227;186;143;48;2;223;197;179m▀\033[48;2;206;193;175m \033[38;2;212;180;145;48;2;208;161;141m▀▀\033[38;2;208;161;141;48;2;206;193;175m▀\033[38;2;223;197;179;48;2;212;180;145m▀\033[38;2;208;161;141;48;2;182;154;130m▀\033[38;2;113;168;143;48;2;135;156;136m▀\033[38;2;155;181;160;48;2;185;189;174m▀\033[38;2;229;216;197;48;2;155;181;160m▀\033[38;2;209;210;199;48;2;113;168;143m▀\033[38;2;229;225;210;48;2;155;181;160m▀\033[38;2;85;84;76;48;2;112;106;91m▀\033[0m
\033[48;2;0;0;0m \033[38;2;37;39;35m▀\033[38;2;209;210;199;48;2;185;189;174m▀\033[38;2;113;168;143;48;2;155;181;160m▀\033[48;2;113;168;143m \033[38;2;132;171;148m▀ \033[38;2;144;154;118;48;2;100;140;109m▀\033[38;2;123;149;113m▀\033[38;2;153;164;140m▀\033[38;2;142;130;122m▀\033[38;2;153;164;140;48;2;94;113;96m▀\033[38;2;208;205;182m▀\033[38;2;229;225;210;48;2;112;106;91m▀\033[48;2;142;130;122m▀\033[48;2;208;161;141m▀\033[38;2;229;216;197m▀\033[38;2;223;197;179;48;2;142;130;122m▀\033[38;2;208;161;141;48;2;123;126;117m▀\033[38;2;182;154;130;48;2;94;113;96m▀\033[38;2;142;130;122;48;2;100;140;109m▀\033[38;2;182;154;130;48;2;144;154;118m▀\033[38;2;208;161;141;48;2;142;130;122m▀\033[38;2;142;130;122;48;2;124;119;106m▀\033[38;2;132;171;148;48;2;100;140;109m▀\033[48;2;113;168;143m▀\033[38;2;113;168;143;48;2;123;149;113m▀\033[48;2;113;168;143m \033[48;2;155;181;160m \033[48;2;85;84;76m \033[0m
\033[48;2;0;0;0m  \033[38;2;56;56;47;48;2;85;84;76m▀\033[38;2;206;193;175;48;2;208;205;182m▀\033[38;2;123;149;113;48;2;185;189;174m▀\033[38;2;113;168;143;48;2;100;140;109m▀  \033[38;2;100;140;109;48;2;123;149;113m▀\033[38;2;94;113;96;48;2;135;156;136m▀\033[38;2;112;106;91;48;2;212;180;145m▀\033[38;2;144;154;118;48;2;178;171;135m▀\033[48;2;123;149;113m \033[38;2;135;156;136;48;2;132;171;148m▀\033[38;2;208;161;141;48;2;209;210;199m▀\033[48;2;206;193;175m▀\033[48;2;209;210;199m▀\033[38;2;183;180;154;48;2;229;225;210m▀\033[38;2;185;189;174;48;2;153;164;140m▀\033[38;2;123;149;113m▀\033[48;2;183;180;154m▀\033[48;2;100;140;109m  \033[38;2;100;140;109;48;2;123;149;113m▀▀\033[48;2;100;140;109m  \033[38;2;100;140;109;48;2;94;113;96m▀\033[38;2;132;171;148;48;2;208;205;182m▀\033[38;2;94;113;96;48;2;112;106;91m▀\033[0m
\033[38;2;0;0;0;48;2;56;56;47m▀\033[38;2;142;130;122;48;2;229;216;197m▀\033[38;2;229;225;210;48;2;123;149;113m▀\033[38;2;178;171;135m▀\033[38;2;183;180;154;48;2;144;154;118m▀\033[38;2;123;149;113;48;2;135;156;136m▀\033[38;2;100;140;109;48;2;185;189;174m▀\033[48;2;155;181;160m▀\033[38;2;135;156;136;48;2;144;154;118m▀\033[38;2;244;232;219;48;2;183;180;154m▀\033[38;2;229;225;210;48;2;209;210;199m▀\033[38;2;153;164;140;48;2;155;181;160m▀\033[48;2;123;149;113m \033[38;2;153;164;140m▀\033[48;2;144;154;118m▀\033[38;2;178;171;135;48;2;153;164;140m▀\033[38;2;183;180;154;48;2;123;149;113m▀\033[38;2;135;156;136;48;2;100;140;109m▀\033[38;2;144;154;118;48;2;153;164;140m▀\033[38;2;185;189;174;48;2;229;225;210m▀ \033[38;2;209;210;199;48;2;155;181;160m▀\033[38;2;123;149;113;48;2;206;193;175m▀\033[38;2;182;154;130;48;2;144;154;118m▀\033[38;2;123;149;113;48;2;100;140;109m▀\033[38;2;100;140;109;48;2;144;154;118m▀▀\033[38;2;153;164;140;48;2;208;205;182m▀\033[38;2;208;205;182;48;2;142;130;122m▀\033[38;2;37;39;35;48;2;0;0;0m▀\033[0m
\033[38;2;56;56;47;48;2;0;0;0m▀\033[38;2;185;189;174;48;2;183;180;154m▀\033[38;2;182;154;130;48;2;223;197;179m▀\033[38;2;208;161;141;48;2;212;180;145m▀\033[38;2;123;149;113;48;2;178;171;135m▀\033[38;2;100;140;109;48;2;229;225;210m▀\033[38;2;208;205;182;48;2;178;171;135m▀\033[48;2;153;164;140m▀\033[38;2;178;171;135;48;2;144;154;118m▀\033[48;2;206;193;175m \033[38;2;206;193;175;48;2;244;232;219m▀\033[38;2;183;180;154;48;2;209;210;199m▀\033[38;2;135;156;136;48;2;185;189;174m▀\033[38;2;144;154;118m▀▀\033[48;2;206;193;175m▀\033[48;2;229;216;197m▀\033[38;2;135;156;136;48;2;229;225;210m▀\033[38;2;178;171;135;48;2;212;180;145m▀\033[38;2;206;193;175;48;2;183;180;154m▀\033[48;2;223;197;179m \033[38;2;178;171;135;48;2;182;154;130m▀\033[48;2;123;149;113m▀\033[38;2;206;193;175;48;2;178;171;135m▀ \033[48;2;144;154;118m \033[38;2;123;149;113m▀\033[38;2;178;171;135;48;2;206;193;175m▀\033[38;2;183;180;154;48;2;142;130;122m▀\033[0m
\033[38;2;124;119;106;48;2;229;225;210m▀\033[38;2;244;232;219;48;2;206;193;175m▀\033[38;2;212;180;145;48;2;185;189;174m▀\033[38;2;208;161;141;48;2;206;193;175m▀\033[38;2;209;210;199;48;2;183;180;154m▀\033[38;2;208;205;182;48;2;185;189;174m▀\033[38;2;183;180;154;48;2;209;210;199m▀\033[38;2;178;171;135;48;2;183;180;154m▀\033[38;2;135;156;136;48;2;144;154;118m▀\033[38;2;182;154;130;48;2;183;180;154m▀\033[38;2;229;225;210;48;2;206;193;175m▀\033[38;2;206;193;175;48;2;155;181;160m▀ \033[38;2;208;205;182;48;2;229;225;210m▀\033[38;2;155;181;160;48;2;185;189;174m▀\033[48;2;155;181;160m \033[48;2;208;205;182m \033[38;2;223;197;179;48;2;212;180;145m▀\033[38;2;227;186;143m▀\033[38;2;212;180;145;48;2;178;171;135m▀\033[38;2;223;197;179;48;2;183;180;154m▀\033[38;2;208;205;182;48;2;135;156;136m▀\033[48;2;206;193;175m▀\033[38;2;206;193;175;48;2;229;216;197m▀\033[48;2;178;171;135m \033[38;2;144;154;118m▀\033[38;2;212;180;145;48;2;223;197;179m▀\033[48;2;229;225;210m \033[48;2;37;39;35m \033[0m
\033[38;2;56;56;47;48;2;0;0;0m▀\033[38;2;244;232;219;48;2;94;113;96m▀\033[38;2;229;225;210;48;2;185;189;174m▀ \033[38;2;178;171;135;48;2;206;193;175m▀\033[38;2;223;197;179;48;2;229;216;197m▀\033[38;2;208;205;182;48;2;183;180;154m▀\033[38;2;153;164;140;48;2;123;149;113m▀\033[48;2;182;154;130m▀\033[38;2;182;154;130;48;2;212;180;145m▀\033[48;2;142;130;122m▀\033[48;2;153;164;140m \033[38;2;132;171;148;48;2;155;181;160m▀\033[38;2;155;181;160;48;2;185;189;174m▀\033[48;2;209;210;199m▀\033[48;2;208;205;182m▀\033[48;2;185;189;174m▀\033[48;2;144;154;118m \033[38;2;182;154;130m▀\033[38;2;144;154;118;48;2;178;171;135m▀\033[38;2;153;164;140m▀▀\033[38;2;223;197;179m▀\033[38;2;183;180;154;48;2;144;154;118m▀\033[38;2;144;154;118;48;2;183;180;154m▀\033[38;2;155;181;160;48;2;244;232;219m▀\033[48;2;153;164;140m \033[38;2;56;56;47;48;2;135;156;136m▀\033[38;2;0;0;0;48;2;37;39;35m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;37;39;35m▀\033[38;2;56;56;47m▀▀▀▀\033[38;2;37;39;35m▀\033[38;2;56;56;47m▀▀▀\033[38;2;37;39;35m▀\033[38;2;56;56;47m▀▀▀▀▀▀▀▀▀▀▀▀▀\033[38;2;85;84;76m▀\033[38;2;56;56;47m▀\033[38;2;85;84;76m▀\033[38;2;37;39;35m▀\033[0m
ALTARIS_BANNER_ALL
    awk -v dir="$_bdir" '
        /^==> ?[0-9A-Za-z._-]+$/ { name=substr($0,4); gsub(/^[ \t]+|[ \t]+$/, "", name); file=dir "/" name ".txt"; next }
        { if (file != "") print > file }
    ' "$_bdir/.altaris-banners.$$" 2>/dev/null || true
    rm -f "$_bdir/.altaris-banners.$$" 2>/dev/null || true
    printf '%s\n' "$_brev" > "$_bdir/.banner-rev" 2>/dev/null || true

    cat > "$_home/.config/altaris/banner.sh" <<'ALTARIS_BANNER_SH'
# altaris-banner.sh — Altaris launch sticker: truecolor half-block anime art,
# one random design per interactive shell, printed exactly ONCE per shell
# even when several rc files source it or nested shells inherit the
# environment. Works with zsh, bash and POSIX sh.
# Opt out at runtime: ALTARIS_NO_BANNER=1
# altaris-banner v9
altaris_banner_art() {
  # zsh: empty glob must not abort the function.
  if [ -n "${ZSH_VERSION:-}" ]; then
    setopt localoptions nullglob nonomatch 2>/dev/null || true
  fi
  _bb_dir="${HOME:-/root}/.config/altaris/banner"
  [ -d "$_bb_dir" ] || return 0
  _bb_list=""
  _bb_n=0
  for _bb_f in "$_bb_dir"/*.txt; do
    [ -f "$_bb_f" ] || continue
    _bb_list="$_bb_list$_bb_f
"
    _bb_n=$((_bb_n + 1))
  done
  [ "$_bb_n" -gt 0 ] || return 0
  if [ -n "${RANDOM:-}" ]; then
    _bb_i=$(( (RANDOM % _bb_n) + 1 ))
  else
    _bb_i="$(awk -v n="$_bb_n" 'BEGIN { srand(); print int(rand() * n) + 1 }' 2>/dev/null || echo 1)"
  fi
  _bb_pick="$(printf '%s' "$_bb_list" | sed -n "${_bb_i}p")"
  [ -f "$_bb_pick" ] || return 0
  # The designs carry literal \033 truecolor escapes -> rendered with printf %b.
  if grep -q '\\033' "$_bb_pick" 2>/dev/null; then
    printf '%b\n' "$(cat "$_bb_pick" 2>/dev/null || true)"
  else
    cat "$_bb_pick" 2>/dev/null || true
  fi
  unset _bb_dir _bb_list _bb_n _bb_f _bb_i _bb_pick
}
altaris_banner() {
  [ "${ALTARIS_NO_BANNER:-0}" = "1" ] && return 0
  # One sticker per shell process: exported, so nested shells and every
  # re-sourced rc file in the same process skip instantly. A brand-new
  # shell (new tab / new session) always prints exactly one design.
  [ -z "${_ALTARIS_BANNER_SHOWN:-}" ] || return 0
  case $- in
    *i*) ;;
    *) return 0 ;;
  esac
  export _ALTARIS_BANNER_SHOWN=1
  altaris_banner_art
  # Header: exactly one line, "altaris@<id>" (distro id only). ID= from
  # /etc/os-release; fallbacks: first word of PRETTY_NAME, then "linux".
  _bb_fid=""
  if [ -r /etc/os-release ]; then
    _bb_fid="$(sed -n 's/^ID=//p' /etc/os-release 2>/dev/null | head -n 1 | tr -d '"' | tr '[:upper:]' '[:lower:]')"
    if [ -z "$_bb_fid" ]; then
      _bb_fid="$(sed -n 's/^PRETTY_NAME=//p' /etc/os-release 2>/dev/null | head -n 1 | tr -d '"' | awk '{print $1}' | tr '[:upper:]' '[:lower:]')"
    fi
  fi
  [ -n "$_bb_fid" ] || _bb_fid="linux"
  printf '%s\n' "altaris@$_bb_fid"
  unset _bb_fid
}
altaris_banner
ALTARIS_BANNER_SH

    if [ -n "$_owner" ]; then
        chown -R "$_owner" "$_home/.config/altaris" 2>/dev/null || true
    fi
    return 0
}

# Random colored anime launch sticker for the guest shell.
# ── Bash login wiring: same launch sticker for bash sessions (idempotent).
_altaris_wire_bash_banner() {
    _home="$1"
    _owner="${2:-}"
    [ -n "$_home" ] && [ -d "$_home" ] || return 0
    _brc="$_home/.bashrc"
    if [ ! -f "$_brc" ]; then
        printf '%s\n' '# Altaris bash profile' 'export PATH="$HOME/.local/bin:$PATH"' > "$_brc" 2>/dev/null || true
    fi
    if [ -f "$_brc" ] && ! grep -q 'altaris/banner.sh' "$_brc" 2>/dev/null; then
        {
            printf '\n# Altaris launch sticker (random truecolor anime banner; opt out ALTARIS_NO_BANNER=1)\n'
            printf '[ -r "$HOME/.config/altaris/banner.sh" ] && . "$HOME/.config/altaris/banner.sh"\n'
        } >> "$_brc" 2>/dev/null || true
    fi
    _bpf="$_home/.bash_profile"
    if [ ! -f "$_bpf" ]; then
        {
            printf '%s\n' '[ -f "$HOME/.profile" ] && . "$HOME/.profile"'
            printf '%s\n' '[ -f "$HOME/.bashrc" ] && . "$HOME/.bashrc"'
        } > "$_bpf" 2>/dev/null || true
    elif ! grep -q 'bashrc' "$_bpf" 2>/dev/null; then
        printf '%s\n' '[ -f "$HOME/.bashrc" ] && . "$HOME/.bashrc"' >> "$_bpf" 2>/dev/null || true
    fi
    if [ -n "$_owner" ]; then
        chown "$_owner" "$_brc" "$_bpf" 2>/dev/null || true
    fi
    unset _brc _bpf
}

_altaris_write_altaris_banner "$USER_HOME" "$CUSTOM_USER:$CUSTOM_GROUP"
_altaris_write_altaris_banner /root
_altaris_wire_bash_banner "$USER_HOME" "$CUSTOM_USER:$CUSTOM_GROUP"
_altaris_wire_bash_banner /root

if command -v zsh >/dev/null 2>&1; then
    ZSH_PATH="$(command -v zsh)"
    if [ -f /etc/shells ] && ! grep -qxF "$ZSH_PATH" /etc/shells 2>/dev/null; then
        echo "$ZSH_PATH" >> /etc/shells
    fi
    chsh -s "$ZSH_PATH" "$CUSTOM_USER" 2>/dev/null || true
fi

_altaris_chown_r "$CUSTOM_USER:$CUSTOM_GROUP" "$USER_HOME" 2>/dev/null || true

# ── Altaris Linux appearance (wallpaper/colors/terminal) ───────────────────────
# Re-apply the app-staged appearance config. Runs last (after the compact
# defaults + theme setup) and forces one pass so a re-run always wins.
if [ -r "$USER_HOME/.config/altaris/appearance-apply.sh" ]; then
    ALTARIS_APPLY_HOME="$USER_HOME" ALTARIS_APPLY_FORCE=1 \
        sh "$USER_HOME/.config/altaris/appearance-apply.sh" >/dev/null 2>&1 || true
fi

# Proot-safe PM db ownership (portable stat: GNU -c / BSD -f)
_ref_u=$(stat -c %u /etc 2>/dev/null || stat -f %u /etc 2>/dev/null || true)
_ref_g=$(stat -c %g /etc 2>/dev/null || stat -f %g /etc 2>/dev/null || true)
if [ -n "$_ref_u" ]; then
    for p in /var/lib/dnf /var/cache/dnf /var/lib/rpm /var/db/xbps /var/cache/xbps \
             /var/cache/zypp /var/lib/zypp \
             /lib/apk /usr/lib/apk /var/cache/apk /etc/apk \
             /var/lib/pacman /var/cache/pacman /etc/pacman.d \
             /var/lib/apt /var/cache/apt /var/lib/dpkg; do
        [ -e "$p" ] || continue
        chown -R "$_ref_u:$_ref_g" "$p" 2>/dev/null || true
    done
fi

echo "Altaris: XFCE4 Customization complete!"
exit 0
