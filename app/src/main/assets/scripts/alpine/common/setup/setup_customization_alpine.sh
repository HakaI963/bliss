#!/bin/sh
# setup_customization_alpine.sh
# Folio branding for Alpine XFCE4 (proot + chroot).
# apk/musl port of setup_customization_debian.sh core path.

CUSTOM_USER="flux"
CUSTOM_GROUP="flux"
USER_HOME="/home/$CUSTOM_USER"
THEME_DIR="/usr/share/themes"
ICON_DIR="/usr/share/icons"

handle_error() {
    echo ""
    echo "Folio Error: Script failed at step: $1"
    echo "---------------------------------------------------"
    if [ -t 0 ]; then
        printf "Press Enter to acknowledge error and exit..."
        # shellcheck disable=SC2034
        read -r _ack || true
    fi
    exit 1
}

echo "Folio: Starting Alpine XFCE4 Customization..."

if [ "$(id -u)" -ne 0 ]; then
    echo "Folio: ERROR: must run as root inside the guest (got uid=$(id -u))."
    handle_error "Not Root"
fi

export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin${PATH:+:$PATH}"

_flux_chown() { chown "$@" 2>/dev/null || true; }
_flux_chown_r() { chown -R "$@" 2>/dev/null || true; }

# Resolve group for flux (Alpine useradd often creates matching group)
if id -gn "$CUSTOM_USER" >/dev/null 2>&1; then
    CUSTOM_GROUP="$(id -gn "$CUSTOM_USER")"
fi

# DNS
if [ ! -s /etc/resolv.conf ]; then
    printf 'nameserver 8.8.8.8\nnameserver 1.1.1.1\n' > /etc/resolv.conf
fi

echo "Folio: Installing customization packages..."
apk update || handle_error "apk update"
apk add --no-cache \
    curl wget unzip fontconfig git zsh bash \
    xfce4-screenshooter \
    || handle_error "apk add customization deps"

# Optional niceties (do not fail install)
apk add --no-cache fastfetch neofetch 2>/dev/null || true
apk add --no-cache xfce4-whiskermenu-plugin 2>/dev/null || true

export LANG="${LANG:-C.UTF-8}"
export LC_ALL="${LC_ALL:-C.UTF-8}"

ASSET_REPO="abhay-byte/fluxlinux"
ASSET_TAG="debian-v1"
BASE_URL="https://github.com/$ASSET_REPO/releases/download/$ASSET_TAG"
FLUX_ASSET_DIR="${FLUX_ASSET_DIR:-/tmp/flux_xfce_assets}"

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
    echo " - Extracting $(basename "$_archive") → $_target"
    case "$_archive" in
        *.tar.xz|*.txz) tar -xJf "$_archive" -C "$_target" "$@" ;;
        *.tar.gz|*.tgz) tar -xzf "$_archive" -C "$_target" "$@" ;;
        *.tar)          tar -xf  "$_archive" -C "$_target" "$@" ;;
        *) return 1 ;;
    esac
}

find_asset() {
    _name="$1"
    for f in \
        "$FLUX_ASSET_DIR/$_name" \
        "/tmp/flux_xfce_assets/$_name"
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
    wget -q -O "$_out" "$_url" 2>/dev/null || curl -fL -o "$_out" "$_url" || return 1
    [ -s "$_out" ]
}

# Theme selection
if [ -n "${FLUX_THEME:-}" ]; then
    echo "Folio: Auto-applying Theme: $FLUX_THEME"
    if [ "$FLUX_THEME" = "light" ]; then
        THEME_CHOICE="2"
    else
        THEME_CHOICE="1"
    fi
else
    THEME_CHOICE="1"
fi

SEL_ICON="Papirus-Dark"
ICON_TAR="papirus-dark-only.tar.gz"

if [ "$THEME_CHOICE" = "2" ]; then
    SEL_THEME="Space-light"
    SEL_CURSOR="Vimix-cursors"
    SEL_WALLPAPER="fluxlinux-light.png"
    THEME_TAR="Space-light.tar.xz"
    CURSOR_TAR="01-Vimix-cursors.tar.xz"
else
    SEL_THEME="Space-transparency"
    SEL_CURSOR="Vimix-white-cursors"
    SEL_WALLPAPER="fluxlinux-dark.png"
    THEME_TAR="Space-transparency.tar.xz"
    CURSOR_TAR="02-Vimix-white-cursors.tar.xz"
fi

mkdir -p "$THEME_DIR" "$ICON_DIR"

if [ "${FLUX_SKIP_THEME_ICONS:-0}" = "1" ]; then
    echo "Folio: Themes/icons pre-installed on host — skip guest extract"
elif theme_is_installed "$SEL_THEME" && icon_is_installed "$SEL_ICON" && cursor_is_installed "$SEL_CURSOR"; then
    echo "Folio: Theme+icons+cursor already installed — skip extract"
else
    if ! theme_is_installed "$SEL_THEME"; then
        echo "Folio: Installing theme $SEL_THEME..."
        TFILE="$(find_asset "$THEME_TAR" || true)"
        if [ -z "$TFILE" ]; then
            download_if_needed "$BASE_URL/theme.zip" "/tmp/theme.zip" || handle_error "Theme Download"
            unzip -q -o /tmp/theme.zip -d /tmp/flux_theme_zip
            TFILE="$(find /tmp/flux_theme_zip -name "$THEME_TAR" 2>/dev/null | head -1)"
        fi
        extract_local_tar "$TFILE" "$THEME_DIR" || handle_error "Theme Extract"
    fi
    if ! icon_is_installed "$SEL_ICON"; then
        echo "Folio: Installing icons $SEL_ICON..."
        IFILE="$(find_asset "$ICON_TAR" || find_asset "papirus-dark-only.tar.gz" \
            || find_asset "papirus-dark-only.tar.xz" || true)"
        [ -n "$IFILE" ] || handle_error "Icons Archive Missing"
        extract_local_tar "$IFILE" "$ICON_DIR" "Papirus-Dark" 2>/dev/null || \
            extract_local_tar "$IFILE" "$ICON_DIR" || handle_error "Icons Extract"
    fi
    if ! cursor_is_installed "$SEL_CURSOR"; then
        echo "Folio: Installing cursor $SEL_CURSOR..."
        CFILE="$(find_asset "$CURSOR_TAR" || true)"
        if [ -z "$CFILE" ]; then
            download_if_needed "$BASE_URL/cursor.zip" "/tmp/cursor.zip" || handle_error "Cursor Download"
            unzip -q -o /tmp/cursor.zip -d /tmp/flux_cursor_zip
            CFILE="$(find /tmp/flux_cursor_zip -name "$CURSOR_TAR" 2>/dev/null | head -1)"
        fi
        extract_local_tar "$CFILE" "$ICON_DIR" || handle_error "Cursor Extract"
    fi
fi

# Wallpaper
WALLPAPER_DIR="$USER_HOME/Pictures/Wallpapers"
mkdir -p "$WALLPAPER_DIR"
_flux_chown_r "$CUSTOM_USER:$CUSTOM_GROUP" "$USER_HOME/Pictures" 2>/dev/null || true

if [ ! -f "$WALLPAPER_DIR/$SEL_WALLPAPER" ]; then
    WFILE="$(find_asset "$SEL_WALLPAPER" || true)"
    if [ -n "$WFILE" ]; then
        cp -f "$WFILE" "$WALLPAPER_DIR/$SEL_WALLPAPER"
    else
        TEMP_WP_ZIP="/tmp/wallpaper.zip"
        if download_if_needed "$BASE_URL/wallpaper.zip" "$TEMP_WP_ZIP"; then
            unzip -o -j "$TEMP_WP_ZIP" -d "$WALLPAPER_DIR" 2>/dev/null || true
            [ -f "$WALLPAPER_DIR/dark.png" ] && mv -f "$WALLPAPER_DIR/dark.png" "$WALLPAPER_DIR/fluxlinux-dark.png"
            [ -f "$WALLPAPER_DIR/light.png" ] && mv -f "$WALLPAPER_DIR/light.png" "$WALLPAPER_DIR/fluxlinux-light.png"
        fi
    fi
fi
_flux_chown "$CUSTOM_USER:$CUSTOM_GROUP" "$WALLPAPER_DIR"/* 2>/dev/null || true

# Font (best-effort)
FONT_DIR="/usr/share/fonts/jetbrains-mono-nerd"
if ! fc-list 2>/dev/null | grep -qi "JetBrainsMono Nerd"; then
    echo "Folio: Installing JetBrains Mono Nerd Font..."
    mkdir -p "$FONT_DIR"
    TEMP_ZIP="/tmp/JetBrainsMono.zip"
    NERD_FONT_URL="https://github.com/ryanoasis/nerd-fonts/releases/latest/download/JetBrainsMono.zip"
    if wget -q -O "$TEMP_ZIP" "$NERD_FONT_URL" 2>/dev/null || curl -fL -o "$TEMP_ZIP" "$NERD_FONT_URL" 2>/dev/null; then
        unzip -o -j "$TEMP_ZIP" "*.ttf" -d "$FONT_DIR" 2>/dev/null || unzip -o "$TEMP_ZIP" -d "$FONT_DIR" 2>/dev/null || true
        fc-cache -f "$FONT_DIR" 2>/dev/null || true
    fi
    rm -f "$TEMP_ZIP"
fi

# XFCE settings XML
echo "Folio: Applying XFCE4 Settings..."
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
    <property name="FontName" type="string" value="JetBrainsMono Nerd Font 9"/>
    <property name="MonospaceFontName" type="string" value="JetBrainsMono Nerd Font Mono 10"/>
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

cat > "$XFCONF_DIR/xfwm4.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfwm4" version="1.0">
  <property name="general" type="empty">
    <property name="theme" type="string" value="$SEL_THEME"/>
    <property name="title_font" type="string" value="JetBrainsMono Nerd Font Bold 9"/>
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

TERM_CONFIG_DIR="$USER_HOME/.config/xfce4/terminal"
mkdir -p "$TERM_CONFIG_DIR"
cat > "$TERM_CONFIG_DIR/terminalrc" <<EOF
[Configuration]
FontUseSystem=FALSE
FontName=JetBrainsMono Nerd Font Mono 10
MiscMenubarDefault=FALSE
MiscToolbarDefault=FALSE
MiscConfirmClose=TRUE
MiscAlwaysShowTabs=FALSE
MiscDefaultGeometry=80x24
ScrollingLines=1000
EOF
cat > "$XFCONF_DIR/xfce4-terminal.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfce4-terminal" version="1.0">
  <property name="font-use-system" type="bool" value="false"/>
  <property name="font-name" type="string" value="JetBrainsMono Nerd Font Mono 10"/>
  <property name="misc-menubar-default" type="bool" value="false"/>
  <property name="misc-toolbar-default" type="bool" value="false"/>
</channel>
EOF

_flux_chown_r "$CUSTOM_USER:$CUSTOM_GROUP" "$USER_HOME/.config" 2>/dev/null || true
_flux_chown_r "$CUSTOM_USER:$CUSTOM_GROUP" "$USER_HOME" 2>/dev/null || true

# ── Compact phone layout: single 30px mono top panel + mono shortcuts ───────
# Reuses the guest's shipped theme/icons; no colors, no extra downloads.
_flux_write_folio_xfce_compact() {
    _cfg="$USER_HOME/.config/xfce4/xfconf/xfce-perchannel-xml"
    mkdir -p "$_cfg" "$USER_HOME/Pictures" 2>/dev/null || return 0
    _menu_plugin="applicationsmenu"
    if [ -e /usr/lib/xfce4/panel/plugins/libwhiskermenu.so ] \
        || [ -e /usr/lib64/xfce4/panel/plugins/libwhiskermenu.so ] \
        || command -v xfce4-whiskermenu-plugin >/dev/null 2>&1; then
        _menu_plugin="whiskermenu"
    fi

    cat > "$_cfg/xfce4-panel.xml" <<EOF
<?xml version="1.1" encoding="UTF-8"?>
<channel name="xfce4-panel" version="1.0">
  <property name="configver" type="int" value="2"/>
  <property name="panels" type="array">
    <value type="int" value="1"/>
    <property name="dark-mode" type="bool" value="true"/>
    <property name="panel-1" type="empty">
      <property name="position" type="string" value="p=6;x=0;y=0"/>
      <property name="length" type="double" value="100"/>
      <property name="position-locked" type="bool" value="true"/>
      <property name="size" type="uint" value="30"/>
      <property name="icon-size" type="uint" value="16"/>
      <property name="background-style" type="uint" value="1"/>
      <property name="background-rgba" type="array">
        <value type="double" value="0.0431372549019608"/>
        <value type="double" value="0.0470588235294118"/>
        <value type="double" value="0.0549019607843137"/>
        <value type="double" value="0.94999999999999996"/>
      </property>
      <property name="plugin-ids" type="array">
        <value type="int" value="1"/>
        <value type="int" value="2"/>
        <value type="int" value="3"/>
        <value type="int" value="4"/>
        <value type="int" value="5"/>
        <value type="int" value="6"/>
      </property>
    </property>
  </property>
  <property name="plugins" type="empty">
    <property name="plugin-1" type="string" value="$_menu_plugin">
      <property name="button-icon" type="string" value="open-menu"/>
      <property name="button-title" type="string" value="Menu"/>
      <property name="show-button-title" type="bool" value="false"/>
      <property name="show-menu-icons" type="bool" value="true"/>
      <property name="show-tooltips" type="bool" value="false"/>
      <property name="small" type="bool" value="true"/>
    </property>
    <property name="plugin-2" type="string" value="tasklist">
      <property name="grouping" type="uint" value="1"/>
      <property name="flat-buttons" type="bool" value="true"/>
      <property name="show-labels" type="bool" value="false"/>
      <property name="show-handle" type="bool" value="false"/>
      <property name="show-wireframes" type="bool" value="false"/>
      <property name="window-scrolling" type="bool" value="true"/>
    </property>
    <property name="plugin-3" type="string" value="separator">
      <property name="expand" type="bool" value="true"/>
      <property name="style" type="uint" value="0"/>
    </property>
    <property name="plugin-4" type="string" value="systray">
      <property name="square-icons" type="bool" value="true"/>
    </property>
    <property name="plugin-5" type="string" value="clock">
      <property name="mode" type="uint" value="4"/>
      <property name="digital-layout" type="uint" value="1"/>
      <property name="digital-format" type="string" value="%H:%M"/>
      <property name="tooltip-format" type="string" value="%A, %B %d, %Y"/>
      <property name="show-seconds" type="bool" value="false"/>
      <property name="show-meridiem" type="bool" value="false"/>
      <property name="timezone" type="string" value=""/>
    </property>
    <property name="plugin-6" type="string" value="actions">
      <property name="appearance" type="uint" value="0"/>
      <property name="items" type="array">
        <value type="string" value="logout-dialog"/>
      </property>
    </property>
  </property>
</channel>
EOF

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
    _flux_chown_r "$CUSTOM_USER:$CUSTOM_GROUP" "$_cfg" 2>/dev/null || true
    _flux_chown_r "$CUSTOM_USER:$CUSTOM_GROUP" "$USER_HOME/.config/Thunar" 2>/dev/null || true
    unset _cfg _menu_plugin
    return 0
}
_flux_write_folio_xfce_compact

# ── Helpers for OMZ / plugins (bounded; avoid curl|sh hang under proot) ──────
_flux_run() {
    _t="$1"; shift
    if command -v timeout >/dev/null 2>&1; then
        timeout "$_t" "$@" 2>/dev/null
    else
        "$@" 2>/dev/null
    fi
}

_flux_safe_rm_tree() {
    _p="$1"
    [ -e "$_p" ] || return 0
    _bak="${_p}.flux_rm.$$"
    mv "$_p" "$_bak" 2>/dev/null || { rm -rf "$_p" 2>/dev/null || true; return 0; }
    rm -rf "$_bak" 2>/dev/null || true
}

_flux_git_clone() {
    _url="$1"; _dest="$2"; _secs="${3:-90}"
    _flux_safe_rm_tree "$_dest"
    mkdir -p "$(dirname "$_dest")"
    _flux_run "$_secs" git clone --depth 1 --single-branch --quiet "$_url" "$_dest"
}

# ── Oh My Zsh (prefer host pre-stage; never leave empty tree) ────────────────
echo "Folio: Installing Oh My Zsh..."
OMZ_OK=0
if [ -f "$USER_HOME/.oh-my-zsh/oh-my-zsh.sh" ]; then
    if [ "${FLUX_SKIP_OMZ:-0}" = "1" ]; then
        echo "Folio: Oh My Zsh pre-installed on host — skip guest install"
    else
        echo "Folio: Oh My Zsh already valid — skip install"
    fi
    OMZ_OK=1
else
    if [ -e "$USER_HOME/.oh-my-zsh" ]; then
        echo "Folio: Incomplete Oh My Zsh detected — removing (bounded)…"
        _flux_safe_rm_tree "$USER_HOME/.oh-my-zsh"
    fi
    if [ "${FLUX_SKIP_OMZ:-0}" = "1" ]; then
        echo "Folio: FLUX_SKIP_OMZ set but oh-my-zsh.sh missing — guest fallback"
    fi
    if command -v git >/dev/null 2>&1; then
        echo "Folio: Shallow git clone Oh My Zsh (timeout 120s)…"
        if _flux_git_clone "https://github.com/ohmyzsh/ohmyzsh.git" "$USER_HOME/.oh-my-zsh" 120 \
            && [ -f "$USER_HOME/.oh-my-zsh/oh-my-zsh.sh" ]; then
            echo "Folio: Oh My Zsh installed"
            OMZ_OK=1
        else
            echo "Folio: WARNING: Oh My Zsh install failed/timed out — continuing without it"
            _flux_safe_rm_tree "$USER_HOME/.oh-my-zsh"
        fi
    else
        echo "Folio: WARNING: git unavailable — Oh My Zsh skipped (host stage or install git)"
        _flux_safe_rm_tree "$USER_HOME/.oh-my-zsh"
    fi
fi

if [ "$OMZ_OK" = "1" ]; then
    ZSH_CUSTOM="$USER_HOME/.oh-my-zsh/custom"
    mkdir -p "$ZSH_CUSTOM/plugins" "$ZSH_CUSTOM/themes" 2>/dev/null || true

    echo "Folio: Installing Zsh plugins (if missing)…"
    if [ ! -d "$ZSH_CUSTOM/plugins/zsh-autosuggestions/.git" ] \
        && [ ! -f "$ZSH_CUSTOM/plugins/zsh-autosuggestions/zsh-autosuggestions.zsh" ]; then
        if command -v git >/dev/null 2>&1; then
            _flux_git_clone "https://github.com/zsh-users/zsh-autosuggestions.git" \
                "$ZSH_CUSTOM/plugins/zsh-autosuggestions" 60 || true
        fi
    else
        echo "Folio: zsh-autosuggestions already present — skip"
    fi
    if [ ! -d "$ZSH_CUSTOM/plugins/zsh-syntax-highlighting/.git" ] \
        && [ ! -f "$ZSH_CUSTOM/plugins/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh" ]; then
        if command -v git >/dev/null 2>&1; then
            _flux_git_clone "https://github.com/zsh-users/zsh-syntax-highlighting.git" \
                "$ZSH_CUSTOM/plugins/zsh-syntax-highlighting" 60 || true
        fi
    else
        echo "Folio: zsh-syntax-highlighting already present — skip"
    fi

    # Local monochrome prompt theme — no remote download (old colorful theme removed).
    rm -f "$ZSH_CUSTOM/themes/agnosterzak.zsh-theme" 2>/dev/null || true
    if [ ! -s "$ZSH_CUSTOM/themes/folio.zsh-theme" ]; then
        echo "Folio: Installing folio prompt theme…"
        cat > "$ZSH_CUSTOM/themes/folio.zsh-theme" <<'FOLIO_THEME_EOF'
# folio.zsh-theme — Folio's minimal monochrome prompt.
# White/gray on black only; ASCII-safe (no powerline glyphs, no ANSI colors 31-44).
# Segments:  time  folio  working-dir  input-char
PROMPT='%F{240}%D{%H:%M}%f %F{255}folio%f %F{250}%~%f %F{255}%#%f '
PROMPT2='%F{240}> %f'
FOLIO_THEME_EOF
        if [ ! -s "$ZSH_CUSTOM/themes/folio.zsh-theme" ]; then
            echo "Folio: WARNING: folio prompt theme write failed — zsh keeps default prompt"
        fi
    else
        echo "Folio: folio prompt theme already present — skip"
    fi
    _flux_chown_r "$CUSTOM_USER:$CUSTOM_GROUP" "$USER_HOME/.oh-my-zsh"
else
    echo "Folio: Skipping Zsh plugins (Oh My Zsh not installed)"
fi

# pokemon-colorscripts: optional; default skip under proot (gitlab stalls)
if [ "${FLUX_SKIP_POKEMON:-1}" = "1" ]; then
    echo "Folio: pokemon-colorscripts skip (disabled by default)"
elif command -v pokemon-colorscripts >/dev/null 2>&1; then
    echo "Folio: pokemon-colorscripts already present — skip"
elif ! command -v git >/dev/null 2>&1; then
    echo "Folio: pokemon-colorscripts skip (git missing)"
else
    echo "Folio: Installing pokemon-colorscripts (optional, 60s budget)…"
    POKEMON_TEMP="/tmp/pokemon-colorscripts.$$"
    _flux_safe_rm_tree "$POKEMON_TEMP"
    if _flux_git_clone "https://gitlab.com/phoneybadger/pokemon-colorscripts.git" "$POKEMON_TEMP" 60 \
        && [ -f "$POKEMON_TEMP/install.sh" ]; then
        (cd "$POKEMON_TEMP" && _flux_run 30 sh ./install.sh) 2>/dev/null \
            || echo "Folio: pokemon-colorscripts install skipped"
    else
        echo "Folio: pokemon-colorscripts skipped (clone timeout/fail)"
    fi
    _flux_safe_rm_tree "$POKEMON_TEMP"
fi

# Configure .zshrc (always write Flux defensive profile — matches Debian)
echo "Folio: Configuring .zshrc..."
ZSHRC="$USER_HOME/.zshrc"
echo "Folio: Writing optimized .zshrc..."
cat > "$ZSHRC" << 'ZSHEOF'
# Guest PATH only — never inherit host PREFIX/bin (nested proot glue errors).
export PATH="$HOME/.local/bin:/opt/nodejs/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

# Setup Locales
export LANG=en_US.UTF-8
export LC_ALL=en_US.UTF-8

# Host PROOT_TMP_DIR is not writable as guest uid; use guest /tmp.
unset PROOT_TMP_DIR
export TMPDIR="${TMPDIR:-/tmp}"

# Fix XDG_RUNTIME_DIR (not set in PRoot/chroot — no systemd-logind)
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/tmp}"

# Launch sticker + info block live in ~/.config/folio/banner.sh (sourced at the
# end of this file): one random monochrome anime ASCII design per shell open.
# pokemon-colorscripts stays installed but no longer auto-prints (old style).

# oh-my-zsh (optional — install may fail offline; shell still usable without it)
export ZSH="${ZSH:-$HOME/.oh-my-zsh}"
if [ -f "$ZSH/oh-my-zsh.sh" ]; then
  ZSH_THEME="folio"
  DISABLE_UPDATE_PROMPT=true
  DISABLE_AUTO_UPDATE=true
  ZSH_DISABLE_COMPFIX=true
  # Removed zsh-autocomplete (very slow), kept essential plugins
  plugins=(git zsh-autosuggestions zsh-syntax-highlighting)
  source "$ZSH/oh-my-zsh.sh"
fi

# Alpine: package manager needs root. NOPASSWD sudo is configured for flux.
# Function (not alias) so it works in scripts and interactive shells.
if command -v apk >/dev/null 2>&1 && command -v sudo >/dev/null 2>&1; then
  apk() { command sudo apk "$@"; }
fi
# ── Folio launch sticker (random monochrome anime banner; opt out FLUX_NO_BANNER=1)
[ -r "$HOME/.config/folio/banner.sh" ] && . "$HOME/.config/folio/banner.sh"

ZSHEOF
_flux_chown "$CUSTOM_USER:$CUSTOM_GROUP" "$ZSHRC"
# Login zsh (`su -` / `zsh -l`) skips .zshrc unless also interactive.
ZPROFILE="$USER_HOME/.zprofile"
if [ ! -f "$ZPROFILE" ]; then
    printf '%s\n' '[[ -o interactive ]] || { [ -f "$HOME/.zshrc" ] && . "$HOME/.zshrc"; }' \
        > "$ZPROFILE"
    _flux_chown "$CUSTOM_USER:$CUSTOM_GROUP" "$ZPROFILE"
fi

# ── Folio launch stickers: 8 monochrome anime-ish ASCII designs ─────────────
# Installs ~/.config/folio/banner/*.txt (random pick per shell open) and
# ~/.config/folio/banner.sh (the folio-banner function sourced by .zshrc).
# Offline, idempotent, cheap. Opt out at runtime: FLUX_NO_BANNER=1.
_flux_write_folio_banner() {
    _home="$1"
    _owner="${2:-}"
    [ -n "$_home" ] || return 0
    _bdir="$_home/.config/folio/banner"
    _need=0
    for _b in 01-catgirl 02-mecha 03-kitsune 04-shonen 05-chibi-star 06-robot-kitty 07-magical 08-samurai; do
        [ -s "$_bdir/$_b.txt" ] || _need=1
    done
    [ -s "$_home/.config/folio/banner.sh" ] || _need=1
    [ "$_need" = "1" ] || return 0

    mkdir -p "$_bdir" 2>/dev/null || return 0
    cat > "$_bdir/.folio-banners.$$" <<'FOLIO_BANNER_ALL'
==>01-catgirl
      /\   /\
     /  \_/  \        nyah~
    |  o     o  |
    |     ^     |
     \  \___/  /
      \_______/
       /|   |\
      / |   | \
        |___|
==>02-mecha
     __________________
    |  [=]  [=]  [=]  |
    |   ___________   |
    |  |  M E C H |   |
    |  |___________|  |
    |_________________|
      /|           |\
     / |           | \
       |___|_|_|___|
==>03-kitsune
         /\     /\
        /  \___/  \
       |  o     o  |
       |     ^     |
       |  \  U  /  |
        \  \___/  /
         \_______/
        kitsune mask
==>04-shonen
        ,--.
       / o  \__
      |  |\   |    FIGHT!
      |  | \  |
       \_|  \_|
        / \   \
      _/   \   \_
     (__)  (__)
==>05-chibi-star
    .    *    .    *
      .-=====-.
   * /  o   o  \ .
    |     ^     |
     \  '---'  /
   .  '-.___.-'  *
      *    .    .
==>06-robot-kitty
     /\_____/\
    | o     o |     beep
    | [=====] |     boop
     \_=====_/
      /|   |\
     / |   | \
       |_| |_|
==>07-magical
         \ | /
      .-=======-.
     /  *     *  \
    |  o       o  |   HENSHIN!
     \    ___    /
      '-..___..-'
         (o_o)[*]
==>08-samurai
         ___
        /o o\___
       |   >    _>
        \_ _ _/
         |___|
         /| |\
        / | | \
          |_|
      kabuto mask
FOLIO_BANNER_ALL
    awk -v dir="$_bdir" '
        /^==> ?[0-9A-Za-z._-]+$/ { name=substr($0,4); gsub(/^[ \t]+|[ \t]+$/, "", name); file=dir "/" name ".txt"; next }
        { if (file != "") print > file }
    ' "$_bdir/.folio-banners.$$" 2>/dev/null || true
    rm -f "$_bdir/.folio-banners.$$" 2>/dev/null || true

    cat > "$_home/.config/folio/banner.sh" <<'FOLIO_BANNER_SH'
# folio-banner.sh — Folio launch sticker. Monochrome, offline, random per shell.
# Opt out with FLUX_NO_BANNER=1. Sourced at the end of ~/.zshrc.
folio-banner() {
  [ "${FLUX_NO_BANNER:-0}" = "1" ] && return 0
  case $- in *i*) ;; *) return 0;; esac
  setopt localoptions nullglob
  local -a _bans
  _bans=( "$HOME"/.config/folio/banner/*.txt )
  if [[ -f "${_bans[1]}" ]]; then
    local _bi=$(( RANDOM % ${#_bans} + 1 ))
    cat "${_bans[$_bi]}" 2>/dev/null || true
  fi
  unset _bans _bi
  if command -v fastfetch >/dev/null 2>&1; then
    local _ff="$HOME/.local/share/fastfetch/presets/termux.jsonc"
    if [ -f "$_ff" ]; then
      { fastfetch --config "$_ff" 2>/dev/null || true; } &!
    else
      { fastfetch --config termux 2>/dev/null || true; } &!
    fi
    unset _ff
  else
    printf '%s@%s | %s | %s\n' "${USER:-flux}" "$(hostname 2>/dev/null || echo folio)" \
      "$(sed -n 's/^PRETTY_NAME=//p' /etc/os-release 2>/dev/null | tr -d '\"' | head -n 1)" \
      "$(date +%H:%M 2>/dev/null)"
  fi
}
folio-banner
FOLIO_BANNER_SH

    if [ -n "$_owner" ]; then
        chown -R "$_owner" "$_home/.config/folio" 2>/dev/null || true
    fi
    return 0
}

# Write monochrome Folio fastfetch preset locally (no remote download)
_flux_write_fastfetch_preset() {
    _home="$1"
    _owner="${2:-}"
    mkdir -p "$_home/.local/share/fastfetch/presets"
    cat > "$_home/.local/share/fastfetch/presets/termux.jsonc" <<'FFEOF'
{
  "logo": null,
  "display": { "separator": " ›  " },
  "modules": [
    { "type": "os", "key": "OS  " },
    { "type": "kernel", "key": "KER " },
    { "type": "cpu", "key": "CPU " },
    { "type": "gpu", "key": "GPU " },
    { "type": "packages", "key": "PKG " },
    { "type": "shell", "key": "SH  " },
    { "type": "terminal", "key": "TER " },
    {
      "type": "disk",
      "key": "DSK ",
      "folders": ["/"],
      "showRemovable": false,
      "showHidden": false,
      "showSubvolumes": false
    },
    { "type": "memory", "key": "MEM " },
    { "type": "swap", "key": "SWP " }
  ]
}
FFEOF
    if [ -n "$_owner" ]; then
        _flux_chown "$_owner" "$_home/.local/share/fastfetch/presets/termux.jsonc" 2>/dev/null || true
    fi
}
_flux_write_fastfetch_preset "$USER_HOME" "$CUSTOM_USER:$CUSTOM_GROUP"
_flux_write_fastfetch_preset /root
# Random monochrome anime launch sticker for the guest shell.
_flux_write_folio_banner "$USER_HOME" "$CUSTOM_USER:$CUSTOM_GROUP"
_flux_write_folio_banner /root
mkdir -p /usr/share/fastfetch/presets
if [ -f "$USER_HOME/.local/share/fastfetch/presets/termux.jsonc" ]; then
    cp -f "$USER_HOME/.local/share/fastfetch/presets/termux.jsonc" \
        /usr/share/fastfetch/presets/termux.jsonc 2>/dev/null || true
fi

# Ensure zsh is a valid login shell, then chsh
if command -v zsh >/dev/null 2>&1; then
    ZSH_PATH="$(command -v zsh)"
    if [ -f /etc/shells ] && ! grep -qxF "$ZSH_PATH" /etc/shells 2>/dev/null; then
        echo "$ZSH_PATH" >> /etc/shells
    fi
    chsh -s "$ZSH_PATH" "$CUSTOM_USER" 2>/dev/null || true
fi

if [ -f "$USER_HOME/.oh-my-zsh/oh-my-zsh.sh" ]; then
    _flux_chown_r "$CUSTOM_USER:$CUSTOM_GROUP" "$USER_HOME/.oh-my-zsh"
fi
_flux_chown "$CUSTOM_USER:$CUSTOM_GROUP" "$USER_HOME/.zshrc"
_flux_chown_r "$CUSTOM_USER:$CUSTOM_GROUP" "$USER_HOME/.local"
_flux_chown_r "$CUSTOM_USER:$CUSTOM_GROUP" "$USER_HOME" 2>/dev/null || true

# ── Folio Linux appearance (wallpaper/colors/terminal) ───────────────────────
# Re-apply the app-staged appearance config. Runs last (after the compact
# defaults + theme setup) and forces one pass so a re-run always wins.
if [ -r "$USER_HOME/.config/folio/appearance-apply.sh" ]; then
    FOLIO_APPLY_HOME="$USER_HOME" FOLIO_APPLY_FORCE=1 \
        sh "$USER_HOME/.config/folio/appearance-apply.sh" >/dev/null 2>&1 || true
fi

echo "Folio: Terminal configuration complete!"

# Same proot-safe apk ownership as setup_alpine_family.sh (db/lock must match /etc owner)
_ref_u=$(stat -c %u /etc 2>/dev/null || true)
_ref_g=$(stat -c %g /etc 2>/dev/null || true)
if [ -n "$_ref_u" ]; then
    for p in /lib/apk /var/cache/apk /var/log /etc/apk; do
        [ -e "$p" ] || continue
        chown -R "$_ref_u:$_ref_g" "$p" 2>/dev/null || true
    done
fi
mkdir -p /lib/apk/db /var/cache/apk /var/log
rm -f /lib/apk/db/lock 2>/dev/null || true
: > /lib/apk/db/lock 2>/dev/null || true
chmod 666 /lib/apk/db/lock 2>/dev/null || true
touch /var/log/apk.log 2>/dev/null || true
chmod 666 /var/log/apk.log 2>/dev/null || true

echo "Folio: Alpine XFCE4 Customization complete!"
exit 0
