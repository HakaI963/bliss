#!/bin/sh
# altaris_guest_common.sh — shared guest helpers (sourced by family / customization).
# Safe to concatenate in front of a family payload. Idempotent functions.

# Guest bins only — never append host $PATH (PRoot repair used to see host pactl).
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
# Host Termux libs (OpenSSL/LDAP) must not leak into glibc guests.
unset LD_LIBRARY_PATH
unset LD_PRELOAD

_altaris_log() { echo "Altaris: $*"; }

# Portable stat: GNU coreutils uses `-c %u`, BSD userland (Chimera) uses `-f %u`.
_altaris_stat_u() { stat -c %u "$1" 2>/dev/null || stat -f %u "$1" 2>/dev/null || true; }
_altaris_stat_g() { stat -c %g "$1" 2>/dev/null || stat -f %g "$1" 2>/dev/null || true; }

# GNU sed -i '' edits a backup named ''; BSD sed -i requires a suffix (or '').
_altaris_sed_i() {
    _expr=$1
    _file=$2
    [ -n "$_file" ] && [ -f "$_file" ] || return 1
    _tmp="${_file}.altarissed.$$"
    if sed "$_expr" "$_file" > "$_tmp"; then
        mv "$_tmp" "$_file"
    else
        rm -f "$_tmp"
        return 1
    fi
}

# Detect Chimera apk v3 (musl, /usr/lib/apk DB, no --no-cache).
_altaris_is_chimera() {
    if grep -q '^ID="chimera"' /usr/lib/os-release 2>/dev/null \
        || grep -q '^ID=chimera' /etc/os-release 2>/dev/null; then
        return 0
    fi
    [ -d /usr/lib/apk ] && [ ! -d /lib/apk ]
}

# Detect Manjaro (arch family, must not rewrite mirrors to ALARM).
_altaris_is_manjaro() {
    grep -q 'ID="manjaro-arm"' /usr/lib/os-release 2>/dev/null \
        || grep -q 'manjaro' /etc/os-release 2>/dev/null
}

_altaris_ensure_tmp() {
    mkdir -p /tmp /var/tmp
    chmod 1777 /tmp /var/tmp 2>/dev/null || true
    unset PROOT_TMP_DIR
    export TMPDIR=/tmp
}

_altaris_ensure_dns() {
    if [ ! -s /etc/resolv.conf ] || ! grep -q nameserver /etc/resolv.conf 2>/dev/null; then
        _altaris_log "Writing /etc/resolv.conf"
        printf 'nameserver 8.8.8.8\nnameserver 1.1.1.1\nnameserver 8.8.4.4\n' > /etc/resolv.conf
    fi
}

_altaris_ensure_dbus() {
    if command -v dbus-uuidgen >/dev/null 2>&1; then
        dbus-uuidgen --ensure=/etc/machine-id 2>/dev/null || true
    fi
    mkdir -p /var/lib/dbus
    if [ ! -e /var/lib/dbus/machine-id ]; then
        if [ -f /etc/machine-id ]; then
            ln -sf /etc/machine-id /var/lib/dbus/machine-id 2>/dev/null \
                || cp -f /etc/machine-id /var/lib/dbus/machine-id 2>/dev/null || true
        elif command -v dbus-uuidgen >/dev/null 2>&1; then
            dbus-uuidgen --ensure=/var/lib/dbus/machine-id 2>/dev/null || true
        fi
    fi
}

_altaris_ensure_groups() {
    # Manjaro bootstrap ships a root-only group file. Create before useradd.
    if command -v groupadd >/dev/null 2>&1; then
        for g in wheel audio video input users netdev storage optical; do
            if ! grep -q "^$g:" /etc/group 2>/dev/null; then
                groupadd "$g" 2>/dev/null || true
            fi
        done
    elif command -v addgroup >/dev/null 2>&1; then
        for g in wheel audio video input users netdev; do
            if ! grep -q "^$g:" /etc/group 2>/dev/null; then
                addgroup "$g" 2>/dev/null || true
            fi
        done
    fi
}

_altaris_ensure_user() {
    # bash may not exist yet (Chimera bootstrap). Fall back to /bin/sh.
    _altaris_shell="/bin/bash"
    if [ ! -e /bin/bash ] && [ ! -e /usr/bin/bash ]; then
        _altaris_shell="/bin/sh"
    fi
    if ! id altaris >/dev/null 2>&1; then
        _altaris_log "Creating user altaris (shell $_altaris_shell)..."
        if command -v useradd >/dev/null 2>&1; then
            if ! id -u 1000 >/dev/null 2>&1; then
                useradd -m -u 1000 -s "$_altaris_shell" altaris 2>/dev/null \
                    || useradd -m -s "$_altaris_shell" altaris
            else
                useradd -m -s "$_altaris_shell" altaris
            fi
        else
            adduser -D -s "$_altaris_shell" altaris 2>/dev/null || true
        fi
        echo "altaris:altaris" | chpasswd 2>/dev/null || true
    fi
    # Legacy `flux` name: same uid, and /home/flux points at /home/altaris so
    # the setup helpers and older desktop shortcuts keep resolving.
    if id altaris >/dev/null 2>&1 && ! id flux >/dev/null 2>&1; then
        _altaris_uid=$(id -u altaris 2>/dev/null)
        _altaris_gid=$(id -g altaris 2>/dev/null)
        if [ -n "$_altaris_uid" ] && [ -n "$_altaris_gid" ]; then
            echo "flux:x:${_altaris_uid}:${_altaris_gid}:Legacy:/home/flux:${_altaris_shell}" >> /etc/passwd
            grep -q "^flux:" /etc/group 2>/dev/null || echo "flux:x:${_altaris_gid}:" >> /etc/group
        fi
    fi
    if [ -d /home/altaris ] && [ ! -e /home/flux ]; then
        ln -s /home/altaris /home/flux 2>/dev/null || true
    fi
    for g in wheel audio video netdev input users; do
        if command -v usermod >/dev/null 2>&1; then
            usermod -aG "$g" altaris 2>/dev/null || true
        else
            addgroup altaris "$g" 2>/dev/null || true
        fi
    done
}

# Fedora/sudo under proot still runs PAM account mgmt. pam_unix + audit
# fail → "password is required" even with NOPASSWD.
_altaris_repair_sudo_pam() {
    command -v sudo >/dev/null 2>&1 || return 0
    if [ -f /etc/sudoers ]; then
        grep -q '^Defaults !authenticate' /etc/sudoers 2>/dev/null \
            || echo 'Defaults !authenticate' >> /etc/sudoers
        grep -q '^Defaults !pam_session' /etc/sudoers 2>/dev/null \
            || echo 'Defaults !pam_session' >> /etc/sudoers
        chmod 0440 /etc/sudoers 2>/dev/null || true
    fi
    if [ -d /etc/pam.d ]; then
        _permit=""
        for _m in \
            /usr/lib64/security/pam_permit.so \
            /lib64/security/pam_permit.so \
            /usr/lib/security/pam_permit.so \
            /lib/security/pam_permit.so
        do
            if [ -f "$_m" ]; then _permit=1; break; fi
        done
        if [ -n "$_permit" ]; then
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
    fi
}

_altaris_ensure_sudo() {
    if command -v sudo >/dev/null 2>&1; then
        mkdir -p /etc/sudoers.d
        printf '%s\n' "altaris ALL=(ALL) NOPASSWD:ALL" "flux ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/altaris
        chmod 0440 /etc/sudoers.d/altaris
        chmod 0755 /etc/sudoers.d 2>/dev/null || true
        if [ -f /etc/sudoers ] && ! grep -qE '@includedir[[:space:]]+/etc/sudoers\.d' /etc/sudoers 2>/dev/null; then
            echo '@includedir /etc/sudoers.d' >> /etc/sudoers
        fi
        chmod 0440 /etc/sudoers 2>/dev/null || true
        for _sc in /etc/sudo.conf /usr/etc/sudo.conf; do
            [ -e "$_sc" ] || continue
            chown root:root "$_sc" 2>/dev/null || true
            chmod 0644 "$_sc" 2>/dev/null || true
        done
        chown root:root /etc/sudoers /etc/sudoers.d /etc/sudoers.d/altaris 2>/dev/null || true
        chmod 4755 /usr/bin/sudo 2>/dev/null || chmod 4755 /usr/sbin/sudo 2>/dev/null || true
        _altaris_repair_sudo_pam
    elif command -v doas >/dev/null 2>&1; then
        # Chimera ships doas (opendoas), not sudo.
        printf 'permit nopass altaris\n' > /etc/doas.conf
        # OpenDoas reads the config as the calling user before elevate.
        # 0400 root:root → "doas is not enabled, Permission denied" under proot.
        chmod 0644 /etc/doas.conf
        chown root:root /etc/doas.conf 2>/dev/null || true
        # sudo shim so Altaris wrappers (zshrc / scripts) keep working.
        if ! command -v sudo >/dev/null 2>&1; then
            mkdir -p /usr/local/bin
            cat > /usr/local/bin/sudo <<'EOF'
#!/bin/sh
# Altaris shim: sudo -> doas (nopass altaris).
exec doas "$@"
EOF
            chmod 755 /usr/local/bin/sudo
        fi
    fi
}

_altaris_ensure_home() {
    if [ ! -d /home/flux ]; then
        mkdir -p /home/flux
    fi
    _home_uid=$(_altaris_stat_u /home)
    _home_gid=$(_altaris_stat_g /home)
    if [ -n "$_home_uid" ]; then
        chown -R "$_home_uid:$_home_gid" /home/flux 2>/dev/null || true
        # Proot: /home is the Android app uid. chown flux:flux (uid 1000) makes
        # xfconfd unable to write ~/.config → failsafe / "cannot create /home/flux".
        # Only apply guest flux:flux ownership in real-root chroot (home owner 0).
        if [ "$_home_uid" = "0" ]; then
            chown -R flux:flux /home/flux 2>/dev/null || true
        fi
    else
        chown -R flux:flux /home/flux 2>/dev/null || true
    fi
    chmod 755 /home/flux 2>/dev/null || true
    mkdir -p /home/flux/.config /home/flux/.cache /home/flux/.local/share /home/flux/.vnc
    chmod -R u+rwX /home/flux/.config /home/flux/.cache /home/flux/.local 2>/dev/null || true
    # Desktop-aware session command (ALTARIS_DESKTOPS; legacy default xfce).
    _xs_de="startxfce4"
    case " ${DESKTOPS:-xfce} " in
        *" xfce "*) _xs_de="startxfce4" ;;
        *" kde "*) _xs_de="startplasma-x11" ;;
    esac
    cat > /home/flux/.vnc/xstartup <<EOF
#!/bin/sh
export PULSE_SERVER=tcp:127.0.0.1
[ -f "\$HOME/.Xresources" ] && xrdb "\$HOME/.Xresources"
# Altaris Linux appearance (wallpaper/colors/terminal; hash-guarded inside).
[ -r "\$HOME/.config/altaris/appearance-apply.sh" ] && sh "\$HOME/.config/altaris/appearance-apply.sh" >/dev/null 2>&1 || true
exec $_xs_de
EOF
    unset _xs_de
    chmod +x /home/flux/.vnc/xstartup
    if command -v _altaris_write_pulse_client >/dev/null 2>&1; then
        _altaris_write_pulse_client
    fi
}

_altaris_write_gpu_mode() {
[ -d /etc/bliss ] && [ ! -e /etc/altaris ] && mv /etc/bliss /etc/altaris 2>/dev/null || true
    _mode="${1:-virgl}"
    mkdir -p /etc/altaris
    printf '%s\n' "$_mode" > /etc/altaris/gpu_mode
    _altaris_log "gpu_mode=$_mode"
}

_altaris_fix_pm_writable() {
    _ref_u=$(_altaris_stat_u /etc)
    _ref_g=$(_altaris_stat_g /etc)
    [ -n "$_ref_u" ] || return 0
    for p in \
        /var/lib/dnf /var/cache/dnf /var/lib/rpm /var/cache/libdnf5 \
        /var/db/xbps /var/cache/xbps \
        /var/cache/zypp /var/lib/zypp /var/lib/rpm \
        /lib/apk /usr/lib/apk /usr/lib/apk/db /var/cache/apk /etc/apk \
        /var/lib/pacman /var/cache/pacman /etc/pacman.d \
        /var/lib/apt /var/cache/apt /var/lib/dpkg
    do
        [ -e "$p" ] || continue
        chown -R "$_ref_u:$_ref_g" "$p" 2>/dev/null || true
    done
}

_altaris_disable_guest_selinux() {
    if [ -f /etc/selinux/config ]; then
        sed -i 's/^SELINUX=.*/SELINUX=disabled/' /etc/selinux/config 2>/dev/null || true
    fi
    setenforce 0 2>/dev/null || true
}

_altaris_require_startxfce4() {
    if [ ! -e /usr/bin/startxfce4 ] && [ ! -e /usr/sbin/startxfce4 ] \
        && [ ! -e /usr/local/bin/startxfce4 ] \
        && ! command -v startxfce4 >/dev/null 2>&1; then
        echo "Altaris: ERROR: startxfce4 missing after desktop install"
        exit 1
    fi
}

# Materialize a UTF-8 locale so launchers / the zsh prompt / pokemon work.
# Manjaro ARM bootstrap has a fully-commented locale.gen and no
# locale-archive; localedef *into the archive* often fails under proot.
# Directory locales (--no-archive) work. Never export a name glibc cannot
# load (setlocale warnings + zsh "prompt_segment: character not in range").
# Presence is locale -a only — leftover /usr/lib/locale dir names lie.
_altaris_locale_listed() {
    # locale -a prints en_US.utf8; callers may pass en_US.UTF-8. Equate them.
    _want="$1"
    locale -a 2>/dev/null | grep -qxFi "$_want" && return 0
    case "$_want" in
        *.UTF-8)
            locale -a 2>/dev/null | grep -qxFi "${_want%.UTF-8}.utf8" && return 0
            ;;
        *.utf8)
            locale -a 2>/dev/null | grep -qxFi "${_want%.utf8}.UTF-8" && return 0
            ;;
    esac
    return 1
}

_altaris_locale_has_en_us() {
    _altaris_locale_listed en_US.UTF-8
}

_altaris_locale_has_c_utf8() {
    _altaris_locale_listed C.UTF-8
}

# Drop inherited LANG/LC_ALL when glibc cannot load them (install + login).
_altaris_sanitize_lang() {
    _cur="${LC_ALL:-${LANG:-}}"
    if [ -z "$_cur" ] || [ "$_cur" = "C" ] || [ "$_cur" = "POSIX" ]; then
        unset LC_ALL
        export LANG=C
        unset _cur
        return 0
    fi
    if _altaris_locale_listed "$_cur"; then
        unset _cur
        return 0
    fi
    unset LC_ALL
    export LANG=C
    unset _cur
}

_altaris_sanitize_lang

_altaris_write_locale_profile() {
    mkdir -p /etc/profile.d
    cat > /etc/profile.d/altaris-locale.sh <<'EOF'
# Never export a locale glibc cannot load.
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
}

_altaris_charmap_utf8() {
    _map=/tmp/altaris-UTF-8
    if [ -s "$_map" ]; then
        printf '%s' "$_map"
        return 0
    fi
    if [ -f /usr/share/i18n/charmaps/UTF-8.gz ] && command -v gzip >/dev/null 2>&1; then
        gzip -dc /usr/share/i18n/charmaps/UTF-8.gz > "$_map" || true
    elif [ -f /usr/share/i18n/charmaps/UTF-8 ]; then
        cp -f /usr/share/i18n/charmaps/UTF-8 "$_map" || true
    fi
    if [ -s "$_map" ]; then
        printf '%s' "$_map"
        return 0
    fi
    return 1
}

# Directory locale under /usr/lib/locale — works when locale-archive is
# missing or unwritable (Manjaro/Arch under proot).
_altaris_localedef_dir() {
    _in="$1"
    _dest="$2"
    command -v localedef >/dev/null 2>&1 || return 1
    mkdir -p /usr/lib/locale
    _map=$(_altaris_charmap_utf8 || true)
    if [ -n "$_map" ] && [ -s "$_map" ]; then
        localedef --no-archive -c -i "$_in" -f "$_map" "$_dest"
    else
        localedef --no-archive -c -i "$_in" -f UTF-8 "$_dest"
    fi
}

_altaris_ensure_en_us_locale() {
    if _altaris_is_chimera 2>/dev/null; then
        _altaris_log "Chimera/musl — skipping glibc locale generation"
        printf 'LANG=C.UTF-8\n' > /etc/locale.conf
        _altaris_write_locale_profile
        _altaris_sanitize_lang
        return 0
    fi
    _altaris_log "Ensuring en_US.UTF-8 locale"
    if command -v dnf5 >/dev/null 2>&1; then
        dnf5 -y --setopt=install_weak_deps=False --setopt=tsflags=nodocs,noscripts \
            install glibc-langpack-en 2>/dev/null || true
    elif command -v dnf >/dev/null 2>&1; then
        dnf -y --setopt=install_weak_deps=False --setopt=tsflags=nodocs,noscripts \
            install glibc-langpack-en 2>/dev/null || true
    elif command -v zypper >/dev/null 2>&1; then
        zypper --non-interactive install --no-recommends --auto-agree-with-licenses \
            glibc-locale glibc-locale-base 2>/dev/null || true
    elif command -v xbps-install >/dev/null 2>&1; then
        xbps-install -y glibc-locales 2>/dev/null || true
    elif command -v apt-get >/dev/null 2>&1; then
        DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
            locales 2>/dev/null || true
    elif command -v pacman >/dev/null 2>&1; then
        # gzip unpacks UTF-8.gz for localedef. --needed glibc is a no-op on
        # ALARM (already in the db) and does not restore stripped i18n.
        command -v gzip >/dev/null 2>&1 \
            || pacman -S --noconfirm gzip 2>/dev/null || true
        command -v sed >/dev/null 2>&1 \
            || pacman -S --noconfirm sed 2>/dev/null || true
        if [ ! -f /usr/share/i18n/locales/en_US ] \
            || { [ ! -f /usr/share/i18n/charmaps/UTF-8 ] \
                 && [ ! -f /usr/share/i18n/charmaps/UTF-8.gz ]; }; then
            _altaris_log "Reinstalling glibc to restore /usr/share/i18n"
            pacman -S --noconfirm glibc 2>/dev/null || true
        fi
    fi
    if [ -f /etc/locale.gen ]; then
        if ! grep -q '^en_US.UTF-8 UTF-8' /etc/locale.gen 2>/dev/null; then
            printf 'en_US.UTF-8 UTF-8\n' >> /etc/locale.gen
        fi
        if ! grep -q '^C.UTF-8 UTF-8' /etc/locale.gen 2>/dev/null; then
            printf 'C.UTF-8 UTF-8\n' >> /etc/locale.gen
        fi
    fi
    _ok=0
    _altaris_locale_has_en_us && _ok=1
    if [ "$_ok" != 1 ] && command -v locale-gen >/dev/null 2>&1; then
        locale-gen en_US.UTF-8 2>/dev/null || locale-gen || true
        _altaris_locale_has_en_us && _ok=1
    fi
    # Prefer a directory locale: archive writes often fail under proot.
    if [ "$_ok" != 1 ]; then
        if _altaris_localedef_dir en_US /usr/lib/locale/en_US.utf8; then
            _ok=1
        elif _altaris_localedef_dir en_US en_US.UTF-8; then
            _ok=1
        fi
    fi
    if ! _altaris_locale_has_c_utf8; then
        _altaris_localedef_dir POSIX /usr/lib/locale/C.utf8 || \
            _altaris_localedef_dir C /usr/lib/locale/C.utf8 || true
    fi
    rm -f /tmp/altaris-UTF-8
    _altaris_locale_has_en_us && _ok=1
    if [ "$_ok" = 1 ]; then
        printf 'LANG=en_US.UTF-8\n' > /etc/locale.conf
        export LANG=en_US.UTF-8
        export LC_ALL=en_US.UTF-8
    elif _altaris_locale_has_c_utf8; then
        _altaris_log "WARNING: en_US.UTF-8 missing — using C.UTF-8"
        printf 'LANG=C.UTF-8\n' > /etc/locale.conf
        export LANG=C.UTF-8
        export LC_ALL=C.UTF-8
    else
        _altaris_log "ERROR: no UTF-8 locale after generation (locale -a has neither en_US.utf8 nor C.utf8)"
        _altaris_write_locale_profile
        return 1
    fi
    _altaris_write_locale_profile
    _altaris_sanitize_lang
}

# Shared apt sandbox for Ubuntu / Kali / Parrot (and reusable by Deepin).
# One helper — do not copy-paste three slightly different sandboxes.
_altaris_apt_prep() {
    export DEBIAN_FRONTEND=noninteractive
    export DEBCONF_NONINTERACTIVE_SEEN=true
    export SYSTEMD_OFFLINE=1
    mkdir -p /etc/apt/apt.conf.d
    printf 'APT::Sandbox::User "root";\nDPkg::Use-Pty "false";\n' \
        > /etc/apt/apt.conf.d/99altaris-nosandbox
    # Altaris: earliest common apt point — apply the quiet speed tuning now.
    _altaris_pick_fast_mirror
}

# Host Pulse in the app PREFIX. Guests are clients only (no guest daemon).
_altaris_write_pulse_client() {
    mkdir -p /etc/profile.d
    printf 'export PULSE_SERVER=tcp:127.0.0.1\n' > /etc/profile.d/altaris-pulse.sh
    chmod 644 /etc/profile.d/altaris-pulse.sh 2>/dev/null || true
    if [ -f /etc/environment ]; then
        if grep -q '^PULSE_SERVER=' /etc/environment 2>/dev/null; then
            _altaris_sed_i 's|^PULSE_SERVER=.*|PULSE_SERVER=tcp:127.0.0.1|' /etc/environment
        else
            printf 'PULSE_SERVER=tcp:127.0.0.1\n' >> /etc/environment
        fi
    else
        printf 'PULSE_SERVER=tcp:127.0.0.1\n' > /etc/environment
    fi
    mkdir -p /etc/pulse/client.conf.d 2>/dev/null || true
    if [ -d /etc/pulse/client.conf.d ]; then
        cat > /etc/pulse/client.conf.d/99-altaris.conf <<'EOF'
default-server = tcp:127.0.0.1
autospawn = no
EOF
    fi
    if [ -f /etc/pulse/client.conf ]; then
        if grep -qE '^;?[[:space:]]*default-server' /etc/pulse/client.conf 2>/dev/null; then
            _altaris_sed_i 's/^;*[[:space:]]*default-server.*/default-server = tcp:127.0.0.1/' /etc/pulse/client.conf
        else
            printf '\ndefault-server = tcp:127.0.0.1\n' >> /etc/pulse/client.conf
        fi
        if grep -qE '^;?[[:space:]]*autospawn' /etc/pulse/client.conf 2>/dev/null; then
            _altaris_sed_i 's/^;*[[:space:]]*autospawn.*/autospawn = no/' /etc/pulse/client.conf
        else
            printf 'autospawn = no\n' >> /etc/pulse/client.conf
        fi
    fi
    if [ -f /home/flux/.vnc/xstartup ]; then
        _altaris_sed_i 's|^export PULSE_SERVER=.*|export PULSE_SERVER=tcp:127.0.0.1|' /home/flux/.vnc/xstartup
    fi
}

_altaris_mask_pipewire_pulse() {
    # Do not remove PipeWire — only stop pipewire-pulse stealing 4713 / the default.
    if command -v systemctl >/dev/null 2>&1; then
        systemctl --user mask --now pipewire-pulse.socket pipewire-pulse.service 2>/dev/null || true
        systemctl mask pipewire-pulse.socket pipewire-pulse.service 2>/dev/null || true
    fi
    for _unit in pipewire-pulse.socket pipewire-pulse.service; do
        for _dir in /etc/systemd/user /etc/systemd/system; do
            mkdir -p "$_dir"
            if [ ! -e "$_dir/$_unit" ]; then
                ln -sf /dev/null "$_dir/$_unit" 2>/dev/null || true
            fi
        done
    done
}

# ─────────────────────────────────────────────────────────────────────────────
# Altaris: fastest-mirror picker (B2). Runs ONCE per install — from
# `_altaris_retry_pkg` (all families, before the largest transaction) and from
# `_altaris_apt_prep` (early apt tuning). A handful of candidate mirrors race with
# a 256 KiB range GET each (`--max-time 4`, 200/206 only) and the fastest above
# 50 KB/s wins; the guest PM is then pointed at it (apk / xbps / pacman) or gets
# quiet retry/parallel tuning (apt / dnf). No winner, no curl, no writable
# config → nothing at all is touched. Best-effort, never fatal.
# ─────────────────────────────────────────────────────────────────────────────

_altaris_race_min_speed=51200

# Race $@ URLs in parallel; sets _altaris_race_winner (empty when none qualifies)
# and _altaris_race_speed. Quiet: failures / timeouts / non-2xx / slow never win.
_altaris_race_urls() {
    _altaris_race_winner=""
    _altaris_race_speed=""
    [ "$#" -gt 0 ] || return 0
    _rt="${TMPDIR:-/tmp}/altaris-race.$$"
    mkdir -p "$_rt" 2>/dev/null || return 0
    _ri=0
    _rpids=""
    for _ru in "$@"; do
        _ri=$((_ri + 1))
        (
            curl -s -L -r 0-262143 --max-time 4 -o /dev/null \
                -w '%{speed_download} %{http_code}' "$_ru" > "$_rt/$_ri" 2>/dev/null
        ) &
        _rpids="$_rpids $!"
    done
    # Per-PID waits only: a family script may have unrelated background jobs.
    for _rp in $_rpids; do
        wait "$_rp" 2>/dev/null || true
    done
    _ri=0
    for _ru in "$@"; do
        _ri=$((_ri + 1))
        _rres=$(cat "$_rt/$_ri" 2>/dev/null || true)
        _rspd=${_rres%% *}
        _rcode=${_rres##* }
        case "$_rcode" in 200|206) ;; *) continue ;; esac
        case "$_rspd" in ''|*[!0-9.]*) continue ;; esac
        awk -v s="$_rspd" -v m="$_altaris_race_min_speed" 'BEGIN{exit !(s >= m)}' || continue
        if [ -z "$_altaris_race_winner" ] || \
            awk -v s="$_rspd" -v b="$_altaris_race_speed" 'BEGIN{exit !(s > b)}'; then
            _altaris_race_winner="$_ru"
            _altaris_race_speed="$_rspd"
        fi
    done
    rm -rf "$_rt" 2>/dev/null || true
    unset _rt _ri _rpids _rp _ru _rres _rspd _rcode
    return 0
}

# Host[:port] of a URL for the one-line log.
_altaris_mirror_host() {
    _mh="$1"
    _mh="${_mh#*://}"
    _mh="${_mh%%/*}"
    printf '%s' "$_mh"
    unset _mh
}

# apk (Alpine): race the pinned release's APKINDEX mirrors; the winner goes
# first in /etc/apk/repositories with dl-cdn as fallback. Chimera (apk v3) is
# skipped — its repos are not Alpine mirrors.
_altaris_apk_fast_mirror() {
    [ -r /etc/alpine-release ] || return 0
    _ver=$(cut -d. -f1,2 /etc/alpine-release 2>/dev/null)
    case "$_ver" in ''|*[!0-9.]*) return 0 ;; esac
    _arch=$(apk --print-arch 2>/dev/null || true)
    case "$_arch" in ''|*[!a-zA-Z0-9_]*) _arch=$(uname -m 2>/dev/null || echo aarch64) ;; esac
    _rel="v$_ver"
    _altaris_race_urls \
        "https://mirrors.edge.kernel.org/alpine/$_rel/main/$_arch/APKINDEX.tar.gz" \
        "https://mirror.leaseweb.com/alpine/$_rel/main/$_arch/APKINDEX.tar.gz" \
        "https://mirrors.tuna.tsinghua.edu.cn/alpine/$_rel/main/$_arch/APKINDEX.tar.gz" \
        "https://mirror.sjtu.edu.cn/alpine/$_rel/main/$_arch/APKINDEX.tar.gz" \
        "https://dl-2.alpinelinux.org/alpine/$_rel/main/$_arch/APKINDEX.tar.gz"
    if [ -n "$_altaris_race_winner" ]; then
        _base="${_altaris_race_winner%%/alpine/*}"
        echo "Altaris: fast mirror: $(_altaris_mirror_host "$_base")"
        _tmp="/etc/apk/repositories.altaris.$$"
        if {
            printf '%s\n' \
                "$_base/alpine/$_rel/main" \
                "$_base/alpine/$_rel/community" \
                "https://dl-cdn.alpinelinux.org/alpine/$_rel/main" \
                "https://dl-cdn.alpinelinux.org/alpine/$_rel/community"
        } > "$_tmp" 2>/dev/null; then
            mv -f "$_tmp" /etc/apk/repositories 2>/dev/null || rm -f "$_tmp" 2>/dev/null || true
        fi
        unset _base _tmp
    fi
    unset _ver _arch _rel
    return 0
}

# xbps (Void): race current/<arch> repodata mirrors; the winner is written to
# /etc/xbps.d/00-altaris-repository.conf. The stock repo file stays untouched, so
# the default remains as fallback.
_altaris_xbps_fast_mirror() {
    _adir="aarch64"
    for _xf in /etc/xbps.d/*.conf /usr/share/xbps.d/*.conf; do
        [ -f "$_xf" ] || continue
        _xline=$(grep -m1 '^repository=' "$_xf" 2>/dev/null || true)
        [ -n "$_xline" ] || continue
        case "$_xline" in
            */current/*)
                _xdir=${_xline#*/current/}
                _xdir=${_xdir%%/*}
                [ -n "$_xdir" ] && _adir="$_xdir"
                ;;
        esac
        break
    done
    _altaris_race_urls \
        "https://repo-default.voidlinux.org/current/$_adir/$_adir-repodata" \
        "https://repo-fastly.voidlinux.org/current/$_adir/$_adir-repodata" \
        "https://mirrors.servercentral.com/voidlinux/current/$_adir/$_adir-repodata" \
        "https://mirror.clarkson.edu/voidlinux/current/$_adir/$_adir-repodata"
    if [ -n "$_altaris_race_winner" ]; then
        _wbase="${_altaris_race_winner%/$_adir-repodata}"
        echo "Altaris: fast mirror: $(_altaris_mirror_host "$_wbase")"
        if mkdir -p /etc/xbps.d 2>/dev/null; then
            {
                printf '# Altaris: fastest mirror from a one-shot speed race.\n'
                printf 'repository=%s\n' "$_wbase"
            } > /etc/xbps.d/00-altaris-repository.conf 2>/dev/null || true
        fi
        unset _wbase
    fi
    unset _adir _xf _xline _xdir
    return 0
}

# pacman Server template -> concrete core.db URL ($arch/$repo substituted).
_altaris_pacman_url() {
    _pu=$1
    _pu=$(printf '%s' "$_pu" | sed -e "s/\\\$arch/$2/g" -e 's/\$repo/core/g')
    printf '%s/core.db' "${_pu%/}"
    unset _pu
}

# pacman: Arch / Arch Linux ARM ONLY (Manjaro skipped — different URL scheme).
# Races ALARM- or arch-pattern candidates plus up to 3 servers already in the
# mirrorlist; the winner's Server line is put first, the original list below.
_altaris_pacman_fast_mirror() {
    # Manjaro's mirrors use a different URL scheme — never touch its list.
    _altaris_is_manjaro 2>/dev/null && return 0
    [ -f /etc/pacman.d/mirrorlist ] || return 0
    _arch=$(uname -m 2>/dev/null || echo aarch64)
    if grep -q 'archlinuxarm' /etc/pacman.d/mirrorlist 2>/dev/null; then
        _c1='https://mirrors.tuna.tsinghua.edu.cn/archlinuxarm/$arch/$repo'
        _c2='https://mirrors.ustc.edu.cn/archlinuxarm/$arch/$repo'
        _c3='https://mirror.csclub.uwaterloo.ca/archlinuxarm/$arch/$repo'
        _c4='http://mirror.archlinuxarm.org/$arch/$repo'
    else
        _c1='https://geo.mirror.pkgbuild.com/$repo/os/$arch'
        _c2='https://mirror.rackspace.com/archlinux/$repo/os/$arch'
        _c3='https://mirrors.kernel.org/archlinux/$repo/os/$arch'
        _c4='https://mirrors.tuna.tsinghua.edu.cn/archlinux/$repo/os/$arch'
    fi
    _pf="${TMPDIR:-/tmp}/altaris-pacman.$$"
    : > "$_pf" 2>/dev/null || return 0
    for _ct in "$_c1" "$_c2" "$_c3" "$_c4"; do
        _cu=$(_altaris_pacman_url "$_ct" "$_arch")
        grep -Fq "|$_cu" "$_pf" 2>/dev/null || printf '%s|%s\n' "$_ct" "$_cu" >> "$_pf"
    done
    _ml="${TMPDIR:-/tmp}/altaris-ml.$$"
    grep -E '^[[:space:]]*Server[[:space:]]*=' /etc/pacman.d/mirrorlist 2>/dev/null \
        | head -3 > "$_ml" 2>/dev/null || true
    if [ -s "$_ml" ]; then
        while IFS= read -r _srv; do
            _srv=$(printf '%s' "${_srv#*=}" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')
            case "$_srv" in http://*|https://*) ;; *) continue ;; esac
            _cu=$(_altaris_pacman_url "$_srv" "$_arch")
            grep -Fq "|$_cu" "$_pf" 2>/dev/null || printf '%s|%s\n' "$_srv" "$_cu" >> "$_pf"
        done < "$_ml"
    fi
    rm -f "$_ml" 2>/dev/null || true
    _urls=$(sed -n 's/^[^|]*|//p' "$_pf" 2>/dev/null | tr '\n' ' ')
    # shellcheck disable=SC2086
    _altaris_race_urls $_urls
    if [ -n "$_altaris_race_winner" ]; then
        _wtpl=$(awk -F'|' -v u="$_altaris_race_winner" '$2 == u { print $1; exit }' "$_pf")
        if [ -n "$_wtpl" ]; then
            echo "Altaris: fast mirror: $(_altaris_mirror_host "$_wtpl")"
            _mtmp="/etc/pacman.d/mirrorlist.altaris.$$"
            {
                printf '# Altaris: fastest mirror from a one-shot speed race.\n'
                printf 'Server = %s\n' "$_wtpl"
                grep -vF "Server = $_wtpl" /etc/pacman.d/mirrorlist 2>/dev/null || true
            } > "$_mtmp" 2>/dev/null && mv -f "$_mtmp" /etc/pacman.d/mirrorlist 2>/dev/null \
                || rm -f "$_mtmp" 2>/dev/null || true
            unset _mtmp
        fi
        unset _wtpl
    fi
    rm -f "$_pf" 2>/dev/null || true
    unset _arch _c1 _c2 _c3 _c4 _pf _ml _srv _cu _ct _urls
    return 0
}

# apt: no mirror rewriting (deb822 complexity) — a quiet retry/timeout tuning
# drop-in instead. No curl needed; runs even in a bare minirootfs.
_altaris_apt_fast_tuning() {
    [ -d /etc/apt/apt.conf.d ] || return 0
    cat > /etc/apt/apt.conf.d/99-altaris-fast 2>/dev/null <<'EOF'
// Altaris: retry/timeout/parallel tuning (quiet, idempotent).
Acquire::Retries "5";
Acquire::http::Timeout "30";
Acquire::https::Timeout "30";
Acquire::Languages { "" };
Acquire::Queue-Mode "access";
EOF
    chmod 0644 /etc/apt/apt.conf.d/99-altaris-fast 2>/dev/null || true
    return 0
}

# dnf: max_parallel_downloads for both; fastestmirror only for dnf4.
_altaris_dnf_conf_add() {
    _key=$1
    _tmp="/etc/dnf/dnf.conf.altaris.$$"
    awk -v add="$_key" '
        BEGIN { done = 0 }
        /^[[:space:]]*\[main\]/ && !done { print; print add; done = 1; next }
        { print }
        END { if (!done) { print "[main]"; print add } }
    ' /etc/dnf/dnf.conf > "$_tmp" 2>/dev/null && mv -f "$_tmp" /etc/dnf/dnf.conf 2>/dev/null \
        || rm -f "$_tmp" 2>/dev/null || true
    unset _key _tmp
}

_altaris_dnf_fast_tuning() {
    [ -f /etc/dnf/dnf.conf ] || return 0
    if command -v dnf5 >/dev/null 2>&1; then
        grep -q '^max_parallel_downloads=' /etc/dnf/dnf.conf 2>/dev/null \
            || _altaris_dnf_conf_add 'max_parallel_downloads=10'
    else
        grep -q '^max_parallel_downloads=' /etc/dnf/dnf.conf 2>/dev/null \
            || _altaris_dnf_conf_add 'max_parallel_downloads=10'
        grep -q '^fastestmirror=' /etc/dnf/dnf.conf 2>/dev/null \
            || _altaris_dnf_conf_add 'fastestmirror=True'
    fi
    return 0
}

# Dispatch: once per install; never fatal, never noisy. The guard is only set
# when the chosen branch could actually act (so a curl-less early call can
# still be followed by a successful pick once curl exists).
_altaris_pick_fast_mirror() {
    [ -n "${_ALTARIS_FAST_MIRROR_DONE:-}" ] && return 0
    if command -v apt-get >/dev/null 2>&1; then
        _altaris_apt_fast_tuning
        _ALTARIS_FAST_MIRROR_DONE=1
        return 0
    fi
    if command -v dnf5 >/dev/null 2>&1 || command -v dnf >/dev/null 2>&1; then
        _altaris_dnf_fast_tuning
        _ALTARIS_FAST_MIRROR_DONE=1
        return 0
    fi
    command -v curl >/dev/null 2>&1 || return 0
    if command -v apk >/dev/null 2>&1; then
        _altaris_is_chimera 2>/dev/null || _altaris_apk_fast_mirror
    elif command -v xbps-install >/dev/null 2>&1; then
        _altaris_xbps_fast_mirror
    elif command -v pacman >/dev/null 2>&1; then
        _altaris_pacman_fast_mirror
    fi
    _ALTARIS_FAST_MIRROR_DONE=1
    return 0
}

# ─────────────────────────────────────────────────────────────────────────────
# Required-package retry: flaky links abort a package transaction mid-download
# (xbps/dnf/apt/pacman/zypper all do). `_altaris_retry_pkg <cmd…>` runs the given
# package command up to 3 tries, refreshing the metadata of whichever PM
# exists between tries. Returns the last exit code (0 on success).
# Optional/best-effort installs stay `|| true` — this is only for REQUIRED
# desktop (XFCE/KDE) transactions that used to hard-exit on first failure.
# ─────────────────────────────────────────────────────────────────────────────
_altaris_pm_refresh() {
    if command -v xbps-install >/dev/null 2>&1; then
        xbps-install -Sy
    elif command -v apk >/dev/null 2>&1; then
        apk update
    elif command -v apt-get >/dev/null 2>&1; then
        apt-get update
    elif command -v dnf5 >/dev/null 2>&1; then
        dnf5 -y makecache
    elif command -v dnf >/dev/null 2>&1; then
        dnf -y makecache
    elif command -v zypper >/dev/null 2>&1; then
        zypper --non-interactive --gpg-auto-import-keys refresh
    elif command -v pacman >/dev/null 2>&1; then
        pacman -Sy --noconfirm
    else
        return 1
    fi
}

_altaris_retry_pkg() {
    # Altaris: one mirror race per install, before the first (largest) transaction.
    _altaris_pick_fast_mirror
    _retry_tries=3
    _retry_n=1
    _retry_rc=0
    while [ "$_retry_n" -le "$_retry_tries" ]; do
        if "$@"; then
            _retry_rc=0
            break
        else
            _retry_rc=$?
        fi
        [ "$_retry_n" -lt "$_retry_tries" ] || break
        _altaris_log "Package download failed — retrying ($((_retry_n + 1))/$_retry_tries)…"
        _altaris_pm_refresh >/dev/null 2>&1 || true
        _retry_n=$((_retry_n + 1))
    done
    unset _retry_tries _retry_n
    return "$_retry_rc"
}

_altaris_try_install() {
    # Best-effort named packages. Never handle_error the family if a plugin is missing.
    _altaris_log "Installing Pulse client: $*"
    if command -v apt-get >/dev/null 2>&1; then
        _altaris_apt_prep
        DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends "$@"
    elif command -v apk >/dev/null 2>&1; then
        if _altaris_is_chimera 2>/dev/null; then
            apk add "$@"
        else
            apk add --no-cache "$@"
        fi
    elif command -v dnf5 >/dev/null 2>&1; then
        # noscripts is not optional under proot: icon-cache/udev/systemd
        # triggers can sleep forever and make the install look stalled.
        dnf5 -y --setopt=install_weak_deps=False \
            --setopt=tsflags=nodocs,noscripts \
            --setopt=max_parallel_downloads=10 install "$@"
    elif command -v dnf >/dev/null 2>&1; then
        dnf -y --setopt=install_weak_deps=False \
            --setopt=tsflags=nodocs,noscripts \
            --setopt=max_parallel_downloads=10 install "$@"
    elif command -v zypper >/dev/null 2>&1; then
        zypper --non-interactive install --no-recommends --auto-agree-with-licenses "$@"
    elif command -v xbps-install >/dev/null 2>&1; then
        xbps-install -y "$@"
    elif command -v pacman >/dev/null 2>&1; then
        pacman -S --noconfirm --needed "$@"
    else
        return 1
    fi
}

# Host $PREFIX/bin/pactl must not count. Only a guest-tree binary.
_altaris_guest_pactl() {
    for _p in /usr/bin/pactl /usr/sbin/pactl /bin/pactl; do
        if [ -x "$_p" ]; then
            echo "$_p"
            return 0
        fi
    done
    return 1
}

_altaris_install_pulse_client() {
    # The XFCE panel plugin is a desktop-only extra. A terminal-only install
    # (ALTARIS_DESKTOPS=none) must not pull its icon/GTK dependency chain in;
    # unset keeps the historical desktop behavior.
    _pulse_plugin="xfce4-pulseaudio-plugin"
    _pulse_plugin_apk="xfce4-pulseaudio"
    case " ${ALTARIS_DESKTOPS:-${ALTARIS_DESKTOP:-}} " in
        *" none "*) _pulse_plugin=""; _pulse_plugin_apk="" ;;
    esac
    _have=$(_altaris_guest_pactl || true)
    if [ -n "$_have" ]; then
        _altaris_log "pulse client already present ($_have)"
        if command -v dnf5 >/dev/null 2>&1 || command -v dnf >/dev/null 2>&1 \
            || command -v zypper >/dev/null 2>&1; then
            _altaris_mask_pipewire_pulse
        fi
        return 0
    fi
    if command -v apt-get >/dev/null 2>&1; then
        _altaris_try_install libpulse0 pulseaudio-utils $_pulse_plugin \
            || _altaris_try_install libpulse0 pulseaudio-utils || true
    elif _altaris_is_chimera 2>/dev/null; then
        _altaris_try_install libpulse libpulse-progs $_pulse_plugin \
            || _altaris_try_install libpulse libpulse-progs || true
    elif command -v apk >/dev/null 2>&1; then
        _altaris_try_install libpulse pulseaudio-utils $_pulse_plugin_apk \
            || _altaris_try_install libpulse pulseaudio-utils || true
    elif command -v dnf5 >/dev/null 2>&1 || command -v dnf >/dev/null 2>&1; then
        _altaris_try_install pulseaudio-libs pulseaudio-utils $_pulse_plugin \
            || _altaris_try_install pulseaudio-libs pulseaudio-utils || true
        _altaris_mask_pipewire_pulse
    elif command -v zypper >/dev/null 2>&1; then
        _altaris_try_install libpulse0 pulseaudio-utils $_pulse_plugin \
            || _altaris_try_install libpulse0 pulseaudio-utils \
            || _altaris_try_install libpulse0 || true
        _altaris_mask_pipewire_pulse
    elif command -v xbps-install >/dev/null 2>&1; then
        # Never fall back to the `pulseaudio` server package.
        _altaris_try_install pulseaudio-utils $_pulse_plugin \
            || _altaris_try_install pulseaudio-utils \
            || _altaris_try_install libpulseaudio || true
    elif command -v pacman >/dev/null 2>&1; then
        _altaris_try_install libpulse $_pulse_plugin \
            || _altaris_try_install libpulse || true
    fi
    _have=$(_altaris_guest_pactl || true)
    if [ -n "$_have" ]; then
        _altaris_log "pulse client ready ($_have)"
    else
        _altaris_log "WARN pulse client (pactl) still missing"
    fi
}

_altaris_setup_pulse() {
    _altaris_write_pulse_client
    _altaris_install_pulse_client
}

# ─────────────────────────────────────────────────────────────────────────────
# Altaris Linux appearance: apply the app-staged wallpaper/colors/terminal config
# (~/.config/altaris/appearance.env + appearance-apply.sh, staged by the app).
# No-op when the user never configured appearance. Forces ONE pass because
# callers are install / script re-run paths that just rewrote the defaults.
# ─────────────────────────────────────────────────────────────────────────────
_altaris_apply_altaris_appearance() {
    _home="${1:-/home/flux}"
    _script="$_home/.config/altaris/appearance-apply.sh"
    [ -r "$_script" ] || { unset _home _script; return 0; }
    ALTARIS_APPLY_HOME="$_home" ALTARIS_APPLY_FORCE=1 \
        sh "$_script" >/dev/null 2>&1 || true
    unset _home _script
    return 0
}

# ─────────────────────────────────────────────────────────────────────────────
# Android-style XFCE panel writer (idempotent, offline, never fatal).
#
# TWO panels, single workspace:
#   panel-1  TOP thin status bar (28px, 100% width, near-black, 92% alpha):
#            [app-drawer button] [clock %H:%M] --- [systray] [indicators…]
#            NO window title / task buttons.
#   panel-2  BOTTOM floating dock (64px, 98% width centered, near-black):
#            [drawer] [Terminal] [Files] [Settings] [Browser?] --- tasklist
#            (running windows, icon-only, 32px) --- [power/logout]
#
# Every optional plugin is emitted only when its shared object exists in the
# guest (graceful degrade); launcher entries are written with explicit stock
# icon names so the trimmed Papirus-Dark resolves them. Caller passes the
# xfconf dir + the guest home. `_menu_plugin` is intentionally left set for
# callers' keyboard-shortcut heredocs.
# ─────────────────────────────────────────────────────────────────────────────
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

# ─────────────────────────────────────────────────────────────────────────────
# Phone-compact XFCE defaults (idempotent, offline, never fatal).
#
# Android-style phone layout: thin 28px top status bar (drawer + clock left,
# systray + indicators right) + 64px floating bottom dock (apps, icon-only
# tasklist, power), both near-black. Xft DPI 96 (scale factor 1), 9-10pt fonts,
# Home-only desktop icons, android-dark wallpaper when reachable (existing dark
# PNG / solid charcoal fallback), Super+Enter terminal / Super+E Thunar /
# PrintScreen-to-~/Pictures shortcuts, single-click Thunar.
# Called from every family script's XFCE branch (guarded, `|| true`).
# Full theming still happens in setup_customization_*.sh.
# ─────────────────────────────────────────────────────────────────────────────
_altaris_xfce_compact_defaults() {
    _h="/home/flux"
    [ -d "$_h" ] || return 0
    _cfg="$_h/.config/xfce4/xfconf/xfce-perchannel-xml"
    mkdir -p "$_cfg" 2>/dev/null || return 0

    # Reuse what the guest ships; never invent theme/icon names.
    _theme="Adwaita"
    for _t in Space-transparency Adwaita-dark Greybird-dark; do
        if [ -d "/usr/share/themes/$_t" ]; then _theme="$_t"; break; fi
    done
    _icon="Adwaita"
    [ -d /usr/share/icons/Papirus-Dark ] && _icon="Papirus-Dark"
    _cursor="default"
    [ -d /usr/share/icons/Vimix-white-cursors ] && _cursor="Vimix-white-cursors"
    if [ "$_cursor" = "default" ] && [ -d /usr/share/icons/Vimix-cursors ]; then
        _cursor="Vimix-cursors"
    fi
    if fc-list 2>/dev/null | grep -qi "JetBrainsMono Nerd"; then
        _font="JetBrainsMono Nerd Font 9"
        _mono="JetBrainsMono Nerd Font Mono 10"
        _title="JetBrainsMono Nerd Font Bold 9"
    elif fc-list 2>/dev/null | grep -qi "DejaVu Sans Mono"; then
        _font="DejaVu Sans 9"
        _mono="DejaVu Sans Mono 10"
        _title="DejaVu Sans Bold 9"
    else
        _font="Sans 9"
        _mono="Monospace 10"
        _title="Sans Bold 9"
    fi

    # Whisker menu only when its panel plugin is actually installed.
    _menu_plugin="applicationsmenu"
    if [ -e /usr/lib/xfce4/panel/plugins/libwhiskermenu.so ] \
        || [ -e /usr/lib64/xfce4/panel/plugins/libwhiskermenu.so ] \
        || command -v xfce4-whiskermenu-plugin >/dev/null 2>&1; then
        _menu_plugin="whiskermenu"
    fi

    # Wallpaper: new android-dark default when staged/reachable, else the
    # existing dark PNG, else a solid charcoal desktop (image-style 0).
    _wpdir="$_h/Pictures/Wallpapers"
    mkdir -p "$_wpdir" 2>/dev/null || true
    # Pre-rename installs: migrate the old wallpaper file names once (same images).
    [ -f "$_wpdir/folio-android-dark.png" ] && [ ! -f "$_wpdir/altaris-android-dark.png" ] \
        && mv -f "$_wpdir/folio-android-dark.png" "$_wpdir/altaris-android-dark.png" 2>/dev/null || true
    [ -f "$_wpdir/fluxlinux-dark.png" ] && [ ! -f "$_wpdir/altaris-dark.png" ] \
        && mv -f "$_wpdir/fluxlinux-dark.png" "$_wpdir/altaris-dark.png" 2>/dev/null || true
    [ -f "$_wpdir/fluxlinux-light.png" ] && [ ! -f "$_wpdir/altaris-light.png" ] \
        && mv -f "$_wpdir/fluxlinux-light.png" "$_wpdir/altaris-light.png" 2>/dev/null || true
    _wp="$_wpdir/altaris-android-dark.png"
    if [ ! -f "$_wp" ]; then
        for _src in /tmp/altaris_xfce_assets/altaris-android-dark.png \
                    /usr/share/backgrounds/altaris/altaris-android-dark.png; do
            if [ -f "$_src" ]; then
                cp -f "$_src" "$_wp" 2>/dev/null && break
            fi
        done
    fi
    if [ ! -f "$_wp" ]; then
        _wp="$_wpdir/altaris-dark.png"
        if [ ! -f "$_wp" ]; then
            for _src in /tmp/altaris_xfce_assets/altaris-dark.png \
                        /usr/share/backgrounds/altaris/altaris-dark.png; do
                if [ -f "$_src" ]; then
                    cp -f "$_src" "$_wp" 2>/dev/null && break
                fi
            done
        fi
    fi
    _img_style=5
    [ -f "$_wp" ] || _img_style=0

    cat > "$_cfg/xsettings.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xsettings" version="1.0">
  <property name="Net" type="empty">
    <property name="ThemeName" type="string" value="$_theme"/>
    <property name="IconThemeName" type="string" value="$_icon"/>
    <property name="EnableEventSounds" type="bool" value="false"/>
    <property name="EnableInputFeedbackSounds" type="bool" value="false"/>
  </property>
  <property name="Gtk" type="empty">
    <property name="CursorThemeName" type="string" value="$_cursor"/>
    <property name="CursorThemeSize" type="int" value="32"/>
    <property name="FontName" type="string" value="$_font"/>
    <property name="MonospaceFontName" type="string" value="$_mono"/>
    <property name="DecorationLayout" type="string" value="menu:minimize,maximize,close"/>
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

    cat > "$_cfg/xfwm4.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfwm4" version="1.0">
  <property name="general" type="empty">
    <property name="theme" type="string" value="$_theme"/>
    <property name="title_font" type="string" value="$_title"/>
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

    _monitors="monitor0 monitor1 monitorVNC-0 monitorbuiltin builtin monitorHDMI-A-0 monitorVirtual-0 monitorVirtual-1 monitorVirtual1"
    _props=""
    for _m in $_monitors; do
        _props="$_props
      <property name=\"$_m\" type=\"empty\">
        <property name=\"workspace0\" type=\"empty\">
          <property name=\"last-image\" type=\"string\" value=\"$_wp\"/>
          <property name=\"image-style\" type=\"int\" value=\"$_img_style\"/>
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
    cat > "$_cfg/xfce4-desktop.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfce4-desktop" version="1.0">
  <property name="backdrop" type="empty">
    <property name="screen0" type="empty">$_props
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

    # Android-style panels (thin status bar + floating dock) via the shared
    # writer; `_menu_plugin` stays set for the shortcuts block below.
    if command -v _altaris_write_altaris_xfce_panel >/dev/null 2>&1; then
        _altaris_write_altaris_xfce_panel "$_cfg" "$_h" || true
    fi

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
    mkdir -p "$_h/.config/Thunar" 2>/dev/null || true
    if [ ! -f "$_h/.config/Thunar/thunarrc" ] \
        || ! grep -q '^MiscSingleClick=' "$_h/.config/Thunar/thunarrc" 2>/dev/null; then
        cat > "$_h/.config/Thunar/thunarrc" <<'EOF'
[Configuration]
MiscSingleClick=TRUE
EOF
    fi

    mkdir -p "$_h/.config/xfce4/terminal" 2>/dev/null || true
    cat > "$_h/.config/xfce4/terminal/terminalrc" <<EOF
[Configuration]
FontUseSystem=FALSE
FontName=$_mono
MiscMenubarDefault=FALSE
MiscToolbarDefault=FALSE
MiscConfirmClose=TRUE
MiscAlwaysShowTabs=FALSE
MiscBell=FALSE
MiscCursorBlinks=FALSE
MiscDefaultGeometry=80x24
ScrollingLines=1000
EOF
    cat > "$_cfg/xfce4-terminal.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfce4-terminal" version="1.0">
  <property name="font-use-system" type="bool" value="false"/>
  <property name="font-name" type="string" value="$_mono"/>
  <property name="misc-menubar-default" type="bool" value="false"/>
  <property name="misc-toolbar-default" type="bool" value="false"/>
</channel>
EOF

    # Ownership stays with the home owner (proot: Android app uid).
    _uid=$(_altaris_stat_u "$_h")
    _gid=$(_altaris_stat_g "$_h")
    if [ -n "${_uid:-}" ]; then
        chown -R "$_uid:$_gid" "$_h/.config" 2>/dev/null || true
    fi
    # Altaris Linux appearance (staged by the app) — applied after the compact
    # defaults so the user's wallpaper/colors win. No-op when unconfigured.
    if command -v _altaris_apply_altaris_appearance >/dev/null 2>&1; then
        _altaris_apply_altaris_appearance "$_h" || true
    fi
    unset _h _cfg _theme _icon _cursor _font _mono _title _menu_plugin \
        _wpdir _wp _img_style _monitors _props _m _t _src _uid _gid
    return 0
}

# ─────────────────────────────────────────────────────────────────────────────
# Altaris universal interactive shell
#
# Every distro gets the same zsh experience, not just the desktop-customized
# Alpine/Debian images:
#   * zsh as the login shell for the Linux user
#   * inline history suggestions (zsh-autosuggestions)
#   * green/red command validity coloring (zsh-syntax-highlighting)
#   * the random truecolor anime launch sticker already staged by the app
#   * the same `altaris@<distro>` prompt on every family
#
# Packages are preferred because they are offline-fast after the normal mirror
# pass. If a distro does not package a plugin, a bounded shallow git clone into
# /usr/local/share/altaris/zsh fills the gap. Every step is best-effort: a
# missing plugin never fails an install.
# ─────────────────────────────────────────────────────────────────────────────

_altaris_shell_pm() {
    if [ "$(id -u 2>/dev/null || echo 1)" = "0" ]; then _as=""
    elif command -v sudo >/dev/null 2>&1; then _as="sudo"
    else _as=""; fi
    if command -v apt-get >/dev/null 2>&1; then
        DEBIAN_FRONTEND=noninteractive $_as apt-get install -y --no-install-recommends "$@" >/dev/null 2>&1
    elif command -v apk >/dev/null 2>&1; then
        $_as apk add --no-cache "$@" >/dev/null 2>&1
    elif command -v pacman >/dev/null 2>&1; then
        $_as pacman -S --noconfirm --needed "$@" >/dev/null 2>&1
    elif command -v dnf >/dev/null 2>&1; then
        $_as dnf install -y "$@" >/dev/null 2>&1
    elif command -v dnf5 >/dev/null 2>&1; then
        $_as dnf5 install -y "$@" >/dev/null 2>&1
    elif command -v zypper >/dev/null 2>&1; then
        $_as zypper --non-interactive install "$@" >/dev/null 2>&1
    elif command -v xbps-install >/dev/null 2>&1; then
        $_as xbps-install -y "$@" >/dev/null 2>&1
    else
        return 1
    fi
}

_altaris_shell_clone() {
    command -v git >/dev/null 2>&1 || return 1
    mkdir -p /usr/local/share/altaris/zsh 2>/dev/null || return 1
    _url="$1"; _dst="$2"
    [ -s "$_dst" ] && return 0
    if command -v timeout >/dev/null 2>&1; then
        timeout 60 git clone --depth 1 --single-branch --quiet "$_url" "$_dst" >/dev/null 2>&1
    else
        GIT_TERMINAL_PROMPT=0 git clone --depth 1 --single-branch --quiet "$_url" "$_dst" >/dev/null 2>&1
    fi
    unset _url _dst
}

_altaris_shell_ensure_plugins() {
    # zsh itself is the only hard requirement; the helper is called after the
    # family package transaction, so usually this is one tiny no-op.
    _altaris_shell_pm zsh git || true
    _altaris_shell_pm zsh-autosuggestions zsh-syntax-highlighting || true

    _asug=""
    _ssyn=""
    for _p in \
        /usr/share/zsh-autosuggestions/zsh-autosuggestions.zsh \
        /usr/share/zsh/plugins/zsh-autosuggestions/zsh-autosuggestions.zsh \
        /usr/share/zsh/plugins/zsh-autosuggestions/zsh-autosuggestions.plugin.zsh \
        /usr/local/share/zsh-autosuggestions/zsh-autosuggestions.zsh \
        /usr/local/share/altaris/zsh/zsh-autosuggestions/zsh-autosuggestions.zsh \
        /home/flux/.oh-my-zsh/custom/plugins/zsh-autosuggestions/zsh-autosuggestions.zsh \
        /root/.oh-my-zsh/custom/plugins/zsh-autosuggestions/zsh-autosuggestions.zsh; do
        [ -r "$_p" ] && { _asug="$_p"; break; }
    done
    for _p in \
        /usr/share/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh \
        /usr/share/zsh/plugins/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh \
        /usr/share/zsh-syntax-highlighting/zsh-syntax-highlighting.plugin.zsh \
        /usr/local/share/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh \
        /usr/local/share/altaris/zsh/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh \
        /home/flux/.oh-my-zsh/custom/plugins/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh \
        /root/.oh-my-zsh/custom/plugins/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh; do
        [ -r "$_p" ] && { _ssyn="$_p"; break; }
    done

    [ -n "$_asug" ] || _altaris_shell_clone \
        https://github.com/zsh-users/zsh-autosuggestions.git \
        /usr/local/share/altaris/zsh/zsh-autosuggestions || true
    [ -n "$_ssyn" ] || _altaris_shell_clone \
        https://github.com/zsh-users/zsh-syntax-highlighting.git \
        /usr/local/share/altaris/zsh/zsh-syntax-highlighting || true
    unset _asug _ssyn _p
}

_altaris_shell_write_profile() {
    _h="$1"
    [ -n "$_h" ] && [ -d "$_h" ] || return 0
    _z="$_h/.zshrc"
    if [ ! -e "$_z" ]; then
        printf '# Altaris interactive shell\n' > "$_z" 2>/dev/null || return 0
    fi
    if ! grep -q 'altaris shell integration v2' "$_z" 2>/dev/null; then
        cat >> "$_z" <<'ALTARIS_SHELL_EOF'

# >>> altaris shell integration v2 >>>
# One profile for every distro: history ghost text, live green/red command
# validation, and the same launch sticker. Loaded after the distro profile.
ZSH_AUTOSUGGEST_HIGHLIGHT_STYLE='fg=244'
_altaris_shell_source() {
  [ -r "$1" ] || return 0
  case "$1" in
    *zsh-autosuggestions*) (( ${+functions[_zsh_autosuggest_start]} )) && return 0 ;;
    *zsh-syntax-highlighting*) (( ${+ZSH_HIGHLIGHT_VERSION} )) && return 0 ;;
  esac
  source "$1"
}
_altaris_shell_source "${ZSH:-$HOME/.oh-my-zsh}/custom/plugins/zsh-autosuggestions/zsh-autosuggestions.zsh"
_altaris_shell_source "$HOME/.oh-my-zsh/custom/plugins/zsh-autosuggestions/zsh-autosuggestions.zsh"
_altaris_shell_source /usr/share/zsh-autosuggestions/zsh-autosuggestions.zsh
_altaris_shell_source /usr/share/zsh/plugins/zsh-autosuggestions/zsh-autosuggestions.zsh
_altaris_shell_source /usr/share/zsh/plugins/zsh-autosuggestions/zsh-autosuggestions.plugin.zsh
_altaris_shell_source /usr/local/share/zsh-autosuggestions/zsh-autosuggestions.zsh
_altaris_shell_source /usr/local/share/altaris/zsh/zsh-autosuggestions/zsh-autosuggestions.zsh
# Syntax highlighting must be the last zle source.
_altaris_shell_source "${ZSH:-$HOME/.oh-my-zsh}/custom/plugins/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh"
_altaris_shell_source "$HOME/.oh-my-zsh/custom/plugins/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh"
_altaris_shell_source /usr/share/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh
_altaris_shell_source /usr/share/zsh/plugins/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh
_altaris_shell_source /usr/share/zsh-syntax-highlighting/zsh-syntax-highlighting.plugin.zsh
_altaris_shell_source /usr/local/share/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh
_altaris_shell_source /usr/local/share/altaris/zsh/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh
unset -f _altaris_shell_source 2>/dev/null || true
if [ -z "${ZSH_THEME:-}" ] || [ "${ZSH_THEME:-}" = "altaris" ]; then
  PROMPT='%F{79}altaris%f%F{244}@%m%f %F{110}%~%f %F{255}%#%f '
fi
# Sticker arrives from the app (BannerArtWriter); both old/new dirs are read.
[ -r "$HOME/.config/altaris/banner.sh" ] && . "$HOME/.config/altaris/banner.sh"
[ -r "$HOME/.config/altaris/banner.sh" ] && . "$HOME/.config/altaris/banner.sh"
# <<< altaris shell integration v2 <<<
ALTARIS_SHELL_EOF
        if [ ! -s "$_z" ]; then
            printf '# Altaris interactive shell\n' > "$_z" 2>/dev/null || true
        fi
    fi
    _u=$(stat -c %u "$_h" 2>/dev/null || stat -f %u "$_h" 2>/dev/null || true)
    _g=$(stat -c %g "$_h" 2>/dev/null || stat -f %g "$_h" 2>/dev/null || true)
    [ -n "$_u" ] && chown "$_u:$_g" "$_z" 2>/dev/null || true
    if [ -f "$_h/.bashrc" ] && ! grep -q 'altaris banner' "$_h/.bashrc" 2>/dev/null; then
        printf '\n# altaris banner\n[ -r "$HOME/.config/altaris/banner.sh" ] && . "$HOME/.config/altaris/banner.sh"\n[ -r "$HOME/.config/altaris/banner.sh" ] && . "$HOME/.config/altaris/banner.sh"\n' >> "$_h/.bashrc" 2>/dev/null || true
    fi
    # Bash (Debian, Alpine, ...): keep the identity and failed-command marker
    # even when stock .bashrc replaces PS1 after /etc/profile.d has run.
    if [ -f "$_h/.bashrc" ] && ! grep -q 'altaris bash prompt v1' "$_h/.bashrc" 2>/dev/null; then
        cat >> "$_h/.bashrc" <<'ALTARIS_BASH_PROMPT_EOF'

# >>> altaris bash prompt v1 >>>
if [ -n "$BASH_VERSION" ] && [ -n "$PS1" ]; then
  _ALTARIS_PS1_OK='\[\e[38;5;79m\]altaris@\h\[\e[0m\]:\[\e[38;5;110m\]\w\[\e[0m\]\$ '
  _ALTARIS_PS1_FAIL='\[\e[38;5;79m\]altaris@\h\[\e[0m\]:\[\e[38;5;110m\]\w\[\e[0m\]\[\e[38;5;203m\]\$\[\e[0m\] '
  PROMPT_COMMAND='if [ "$?" = 0 ]; then PS1="$_ALTARIS_PS1_OK"; else PS1="$_ALTARIS_PS1_FAIL"; fi'
  PS1="$_ALTARIS_PS1_OK"
fi
# <<< altaris bash prompt v1 <<<
ALTARIS_BASH_PROMPT_EOF
    fi
    [ -n "$_u" ] && chown "$_u:$_g" "$_h/.bashrc" 2>/dev/null || true
    unset _z _u _g
}

_altaris_shell_bootstrap() {
    _altaris_shell_ensure_plugins || true
    if command -v zsh >/dev/null 2>&1; then
        _zp=$(command -v zsh)
        grep -qxF "$_zp" /etc/shells 2>/dev/null || echo "$_zp" >> /etc/shells 2>/dev/null || true
        for _u in flux altaris; do
            id "$_u" >/dev/null 2>&1 && chsh -s "$_zp" "$_u" >/dev/null 2>&1 || true
        done
        unset _zp
    fi
    for _h in /home/flux /home/altaris /root; do
        _altaris_shell_write_profile "$_h"
    done
    unset _h _u
}

_altaris_setup_altaris_shell() {
    _altaris_shell_bootstrap || true
}
