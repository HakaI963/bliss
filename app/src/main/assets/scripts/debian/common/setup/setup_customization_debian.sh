#!/bin/bash
# setup_customization_debian.sh
# Applies "Bliss" branding and customization to Debian XFCE4 Desktop
# Works for both Chroot and Proot environments (run as root, switches to user 'flux')

CUSTOM_USER="flux"
CUSTOM_GROUP="users"
USER_HOME="/home/$CUSTOM_USER"
ASSETS_DIR="$(dirname "$0")/../../../assets"
THEME_DIR="/usr/share/themes"
ICON_DIR="/usr/share/icons"

# Error Handler — never block on stdin (onboarding / ProcessBuilder has no TTY)
handle_error() {
    echo ""
    echo "❌ Bliss Error: Script failed at step: $1"
    echo "---------------------------------------------------"
    echo "Please check the error message above for details."
    echo "---------------------------------------------------"
    if [ -t 0 ]; then
        read -r -p "Press Enter to acknowledge error and exit..."
    fi
    exit 1
}

echo "Bliss: Starting XFCE4 Customization..."

# Setup scripts must run as root inside proot/chroot (apt/dpkg).
if [ "$(id -u)" -ne 0 ]; then
    echo "Bliss: ERROR: must run as root inside the guest (got uid=$(id -u))."
    echo "Bliss: Component sessions should use --user root; re-run from Distro Settings."
    handle_error "Not Root"
fi

# Best-effort ownership (proot often cannot chown to flux:users — never spam/fail)
_flux_chown() { chown "$@" 2>/dev/null || true; }
_flux_chown_r() { chown -R "$@" 2>/dev/null || true; }

# 1. Install Dependencies
echo "Bliss: Installing customization tools (as root)..."
export DEBIAN_FRONTEND=noninteractive
apt-get update -y || handle_error "Dependency Installation (apt update)"
# git: guest fallback when host did not pre-stage Oh My Zsh; zsh: terminal shell
apt-get install -y -o Dpkg::Use-Pty=0 \
    xfce4-goodies curl fastfetch wget unzip fontconfig locales git zsh \
    || handle_error "Dependency Installation"
# Whisker panel menu for the compact phone panel (best-effort; goodies may
# already include it). Never fatal — the panel falls back to Applications menu.
apt-get install -y -o Dpkg::Use-Pty=0 xfce4-whiskermenu-plugin 2>/dev/null || true

# Setup Locales for proper font rendering in ZSH/Terminal
echo "Bliss: Setting up locales..."
echo "en_US.UTF-8 UTF-8" > /etc/locale.gen
locale-gen
update-locale LANG=en_US.UTF-8

# 2. Deploy Assets (From GitHub Release debian-v1)
ASSET_REPO="abhay-byte/fluxlinux"
ASSET_TAG="debian-v1"
BASE_URL="https://github.com/$ASSET_REPO/releases/download/$ASSET_TAG"

# Local asset dirs (host stages into shared /tmp for proot; optional offline paths)
FLUX_ASSET_DIR="${FLUX_ASSET_DIR:-/tmp/flux_xfce_assets}"

theme_is_installed() {
    local name="$1"
    local d="$THEME_DIR/$name"
    [ -d "$d" ] && { [ -f "$d/index.theme" ] || [ -d "$d/gtk-3.0" ] || [ -d "$d/xfwm4" ]; }
}

icon_is_installed() {
    local name="$1"
    local d="$ICON_DIR/$name"
    [ -d "$d" ] && { [ -f "$d/index.theme" ] || [ -n "$(ls -A "$d" 2>/dev/null)" ]; }
}

cursor_is_installed() {
    local name="$1"
    local d="$ICON_DIR/$name"
    [ -d "$d" ] && { [ -f "$d/index.theme" ] || [ -d "$d/cursors" ]; }
}

# Fast path: extract a single archive into TARGET (optional path filter as $3+)
extract_local_tar() {
    local archive="$1"
    local target="$2"
    shift 2
    [ -f "$archive" ] || return 1
    mkdir -p "$target"
    echo " - Extracting $(basename "$archive") → $target $*"
    case "$archive" in
        *.tar.xz|*.txz) tar -xJf "$archive" -C "$target" "$@" ;;
        *.tar.gz|*.tgz) tar -xzf "$archive" -C "$target" "$@" ;;
        *.tar)          tar -xf  "$archive" -C "$target" "$@" ;;
        *) return 1 ;;
    esac
}

# Resolve archive from FLUX_ASSET_DIR / common names; download zip only as last resort.
find_asset() {
    # find_asset <basename-or-glob-pattern>
    local name="$1"
    local f
    for f in \
        "$FLUX_ASSET_DIR/$name" \
        "/tmp/flux_xfce_assets/$name" \
        "$ASSETS_DIR/xfce4/theme/$name" \
        "$ASSETS_DIR/xfce4/icons/$name" \
        "$ASSETS_DIR/xfce4/cursor/$name" \
        "$ASSETS_DIR/xfce4/wallpaper/$name"
    do
        if [ -f "$f" ]; then
            echo "$f"
            return 0
        fi
    done
    return 1
}

download_if_needed() {
    local url="$1"
    local out="$2"
    if [ -f "$out" ] && [ -s "$out" ]; then
        return 0
    fi
    mkdir -p "$(dirname "$out")"
    echo " - Downloading $(basename "$out")..."
    wget -q --show-progress "$url" -O "$out" || curl -fL --progress-bar "$url" -o "$out" || return 1
    [ -s "$out" ]
}

# 3. Theme Selection Prompt
if [ -n "$FLUX_THEME" ]; then
    echo "Bliss: Auto-applying Theme: $FLUX_THEME"
    if [ "$FLUX_THEME" == "light" ]; then
        THEME_CHOICE="2"
    else
        THEME_CHOICE="1"
    fi
else
    if [ -t 0 ]; then
        echo "------------------------------------------------"
        echo "Select Theme Preference:"
        echo "1) Dark (Default)"
        echo "2) Light"
        read -r -p "Enter choice [1-2]: " THEME_CHOICE
        echo "------------------------------------------------"
    else
        echo "Bliss: No TTY / FLUX_THEME — defaulting to dark"
        THEME_CHOICE="1"
    fi
fi

# Icons: Papirus-Dark only (no full Papirus / Light / ePapirus packs)
SEL_ICON="Papirus-Dark"
ICON_TAR="papirus-dark-only.tar.gz"

if [ "$THEME_CHOICE" == "2" ]; then
    echo "Bliss: Light Mode Selected (icons: Papirus-Dark only)."
    SEL_THEME="Space-light"
    SEL_CURSOR="Vimix-cursors" # Dark cursor for light theme (better contrast)
    SEL_WALLPAPER="fluxlinux-light.png"
    THEME_TAR="Space-light.tar.xz"
    CURSOR_TAR="01-Vimix-cursors.tar.xz"
else
    echo "Bliss: Dark Mode Selected (icons: Papirus-Dark only)."
    SEL_THEME="Space-transparency"
    SEL_CURSOR="Vimix-white-cursors" # White cursor for dark theme (better contrast)
    SEL_WALLPAPER="fluxlinux-dark.png"
    THEME_TAR="Space-transparency.tar.xz"
    CURSOR_TAR="02-Vimix-white-cursors.tar.xz"
fi

mkdir -p "$THEME_DIR" "$ICON_DIR"

# Host may have already extracted into the proot rootfs (native tar — fast).
# FLUX_SKIP_THEME_ICONS=1 → only apply configs / wallpaper ownership.
if [ "${FLUX_SKIP_THEME_ICONS:-0}" = "1" ]; then
    echo "Bliss: Themes/icons pre-installed on host — skip guest extract"
elif theme_is_installed "$SEL_THEME" && icon_is_installed "$SEL_ICON" && cursor_is_installed "$SEL_CURSOR"; then
    echo "Bliss: Theme+icons+cursor already installed ($SEL_THEME / $SEL_ICON / $SEL_CURSOR) — skip extract"
else
    # ── Theme (selected only) ───────────────────────────────────────────────
    if theme_is_installed "$SEL_THEME"; then
        echo "Bliss: Theme $SEL_THEME already installed — skip"
    else
        echo "Bliss: Installing theme $SEL_THEME only..."
        TFILE="$(find_asset "$THEME_TAR" || true)"
        if [ -z "$TFILE" ]; then
            download_if_needed "$BASE_URL/theme.zip" "/tmp/theme.zip" || handle_error "Theme Download"
            unzip -q -o /tmp/theme.zip -d /tmp/flux_theme_zip
            TFILE="$(find /tmp/flux_theme_zip -name "$THEME_TAR" | head -1)"
            [ -n "$TFILE" ] || TFILE="$(find /tmp/flux_theme_zip -name "${SEL_THEME}*.tar.xz" | head -1)"
        fi
        extract_local_tar "$TFILE" "$THEME_DIR" || handle_error "Theme Extract"
        theme_is_installed "$SEL_THEME" || handle_error "Theme Missing After Extract"
    fi

    # ── Icons: Papirus-Dark only ────────────────────────────────────────────
    if icon_is_installed "$SEL_ICON"; then
        echo "Bliss: Icons $SEL_ICON already installed — skip"
    else
        echo "Bliss: Installing icons $SEL_ICON only..."
        IFILE="$(find_asset "$ICON_TAR" || find_asset "papirus-dark-only.tar.gz" \
            || find_asset "papirus-dark-only.tar.xz" || true)"
        if [ -z "$IFILE" ]; then
            # Legacy fallback: full pack but still extract only Papirus-Dark/
            IFILE="$(find_asset "papirus-icon-theme-20250501.tar.gz" || true)"
        fi
        if [ -z "$IFILE" ]; then
            echo "Bliss: ERROR: Papirus-Dark archive missing (expected $ICON_TAR in assets)"
            handle_error "Icons Archive Missing"
        fi
        extract_local_tar "$IFILE" "$ICON_DIR" "Papirus-Dark" || \
            extract_local_tar "$IFILE" "$ICON_DIR" || handle_error "Icons Extract"
        icon_is_installed "$SEL_ICON" || handle_error "Icons Missing After Extract"
    fi

    # ── Cursor (selected only) ──────────────────────────────────────────────
    if cursor_is_installed "$SEL_CURSOR"; then
        echo "Bliss: Cursor $SEL_CURSOR already installed — skip"
    else
        echo "Bliss: Installing cursor $SEL_CURSOR only..."
        CFILE="$(find_asset "$CURSOR_TAR" || true)"
        if [ -z "$CFILE" ]; then
            download_if_needed "$BASE_URL/cursor.zip" "/tmp/cursor.zip" || handle_error "Cursor Download"
            unzip -q -o /tmp/cursor.zip -d /tmp/flux_cursor_zip
            CFILE="$(find /tmp/flux_cursor_zip -name "$CURSOR_TAR" -o -name "*${SEL_CURSOR}*.tar.xz" | head -1)"
        fi
        extract_local_tar "$CFILE" "$ICON_DIR" || handle_error "Cursor Extract"
        cursor_is_installed "$SEL_CURSOR" || handle_error "Cursor Missing After Extract"
    fi
fi

# Wallpaper Setup (skip download if selected file already present)
WALLPAPER_DIR="$USER_HOME/Pictures/Wallpapers"
mkdir -p "$WALLPAPER_DIR"
_flux_chown_r "$CUSTOM_USER:$CUSTOM_GROUP" "$USER_HOME/Pictures"

if [ -f "$WALLPAPER_DIR/$SEL_WALLPAPER" ]; then
    echo "Bliss: Wallpaper $SEL_WALLPAPER already present — skip"
else
    echo "Bliss: Installing wallpaper..."
    WFILE="$(find_asset "$SEL_WALLPAPER" || true)"
    if [ -n "$WFILE" ]; then
        cp -f "$WFILE" "$WALLPAPER_DIR/$SEL_WALLPAPER"
    else
        TEMP_WP_ZIP="/tmp/wallpaper.zip"
        download_if_needed "$BASE_URL/wallpaper.zip" "$TEMP_WP_ZIP" || handle_error "Wallpaper Download"
        unzip -o -j "$TEMP_WP_ZIP" -d "$WALLPAPER_DIR"
        [ -f "$WALLPAPER_DIR/dark.png" ] && mv -f "$WALLPAPER_DIR/dark.png" "$WALLPAPER_DIR/fluxlinux-dark.png"
        [ -f "$WALLPAPER_DIR/light.png" ] && mv -f "$WALLPAPER_DIR/light.png" "$WALLPAPER_DIR/fluxlinux-light.png"
    fi
fi
_flux_chown "$CUSTOM_USER:$CUSTOM_GROUP" "$WALLPAPER_DIR"/*


# Install JetBrains Mono Nerd Font
# Using proper Debian font location: /usr/share/fonts/truetype/
FONT_DIR="/usr/share/fonts/truetype/jetbrains-mono-nerd"
FONT_INSTALLED=false

# Check if font already installed
if fc-list | grep -qi "JetBrainsMono Nerd"; then
    echo "Bliss: JetBrains Mono Nerd Font already installed."
    FONT_INSTALLED=true
fi

if [ "$FONT_INSTALLED" = false ]; then
    echo "Bliss: Installing JetBrains Mono Nerd Font..."
    
    # Create font directory
    mkdir -p "$FONT_DIR"
    
    # Download from official Nerd Fonts GitHub releases
    NERD_FONT_URL="https://github.com/ryanoasis/nerd-fonts/releases/latest/download/JetBrainsMono.zip"
    TEMP_ZIP="/tmp/JetBrainsMono.zip"
    
    echo " - Downloading JetBrains Mono Nerd Font..."
    wget -q --show-progress "$NERD_FONT_URL" -O "$TEMP_ZIP" || {
        echo "Bliss: Direct download failed, trying from release..."
        wget -q --show-progress "$BASE_URL/font.zip" -O "$TEMP_ZIP" || handle_error "Font Download"
    }
    
    # Extract only .ttf files (ignore nested folders, Windows-only formats)
    echo " - Extracting font files..."
    unzip -o -j "$TEMP_ZIP" "*.ttf" -d "$FONT_DIR" 2>/dev/null || \
    unzip -o "$TEMP_ZIP" -d "$FONT_DIR" 2>/dev/null
    
    # Clean up any non-font files that might have been extracted
    find "$FONT_DIR" -type f ! -name "*.ttf" ! -name "*.otf" -delete 2>/dev/null
    
    # Remove temp file
    rm -f "$TEMP_ZIP"
    
    # Set correct permissions
    chmod 644 "$FONT_DIR"/*.ttf 2>/dev/null
    chmod 644 "$FONT_DIR"/*.otf 2>/dev/null
    
    # Rebuild font cache (system-wide, verbose)
    echo " - Rebuilding font cache..."
    fc-cache -fv "$FONT_DIR"
    
    # Also rebuild user cache
    su -s /bin/bash - "$CUSTOM_USER" -c "fc-cache -f" 2>/dev/null
    
    # Verify installation
    if fc-list | grep -qi "JetBrainsMono Nerd"; then
        echo "Bliss: ✓ JetBrains Mono Nerd Font installed successfully!"
    else
        echo "Bliss: ⚠ Font may not be properly registered. Checking installed files..."
        ls -la "$FONT_DIR"
    fi
fi
# 4. Apply Settings for User 'flux'
# Write directly to XML config files (dbus-launch creates ephemeral sessions that don't persist)
echo "Bliss: Applying XFCE4 Settings..."

XFCONF_DIR="$USER_HOME/.config/xfce4/xfconf/xfce-perchannel-xml"
mkdir -p "$XFCONF_DIR"

# Generate xsettings.xml (Theme, Icons, Cursor, Fonts, Scaling)
echo "Bliss: Writing xsettings.xml..."
cat <<EOF > "$XFCONF_DIR/xsettings.xml"
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
  <property name="Gdk" type="empty">
    <property name="WindowScalingFactor" type="int" value="1"/>
  </property>
  <property name="Xft" type="empty">
    <property name="DPI" type="int" value="96"/>
    <property name="Antialias" type="int" value="1"/>
    <property name="HintStyle" type="string" value="hintslight"/>
    <property name="RGBA" type="string" value="rgb"/>
  </property>
</channel>
EOF

# Generate xfwm4.xml (Window Manager Theme and Title Font)
echo "Bliss: Writing xfwm4.xml..."
cat <<EOF > "$XFCONF_DIR/xfwm4.xml"
<?xml version="1.0" encoding="UTF-8"?>

<channel name="xfwm4" version="1.0">
  <property name="general" type="empty">
    <property name="theme" type="string" value="$SEL_THEME"/>
    <property name="title_font" type="string" value="JetBrainsMono Nerd Font Bold 9"/>
    <property name="title_alignment" type="string" value="left"/>
    <property name="button_layout" type="string" value="|HMC"/>
    <property name="borderless_maximize" type="bool" value="true"/>
    <property name="placement_ratio" type="int" value="20"/>
    <property name="scroll_workspaces" type="bool" value="false"/>
    <property name="workspace_count" type="int" value="1"/>
    <property name="show_dock_shadow" type="bool" value="true"/>
    <property name="show_frame_shadow" type="bool" value="true"/>
    <property name="snap_to_border" type="bool" value="true"/>
    <property name="snap_to_windows" type="bool" value="true"/>
    <property name="use_compositing" type="bool" value="false"/>
    <property name="tile_on_move" type="bool" value="true"/>
    <property name="wrap_windows" type="bool" value="true"/>
  </property>
</channel>
EOF

# Generate xfce4-desktop.xml (Wallpaper)
echo "Bliss: Writing xfce4-desktop.xml..."
WALLPAPER_PATH="$WALLPAPER_DIR/$SEL_WALLPAPER"
MONITORS="monitor0 monitor1 monitorVNC-0 monitorbuiltin builtin monitorHDMI-A-0 monitorVirtual-0 monitorVirtual-1 monitorVirtual1"

# Build monitor properties dynamically
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

cat <<EOF > "$XFCONF_DIR/xfce4-desktop.xml"
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

# Fix ownership
_flux_chown_r "$CUSTOM_USER:$CUSTOM_GROUP" "$XFCONF_DIR"
echo "Bliss: XFCE4 settings applied successfully!"

# Generate xfce4-keyboard-shortcuts.xml (Custom Keyboard Shortcuts)
# Note: Angle brackets in key names must be XML-escaped as &lt; and &gt;
echo "Bliss: Writing keyboard shortcuts..."
cat <<'SHORTCUTEOF' > "$XFCONF_DIR/xfce4-keyboard-shortcuts.xml"
<?xml version="1.1" encoding="UTF-8"?>

<channel name="xfce4-keyboard-shortcuts" version="1.0">
  <property name="commands" type="empty">
    <property name="default" type="empty">
      <property name="&lt;Alt&gt;F1" type="empty"/>
      <property name="&lt;Alt&gt;F2" type="empty">
        <property name="startup-notify" type="empty"/>
      </property>
      <property name="&lt;Alt&gt;F3" type="empty">
        <property name="startup-notify" type="empty"/>
      </property>
      <property name="&lt;Primary&gt;&lt;Alt&gt;Delete" type="empty"/>
      <property name="&lt;Primary&gt;&lt;Alt&gt;l" type="empty"/>
      <property name="&lt;Primary&gt;&lt;Alt&gt;t" type="empty"/>
      <property name="XF86Display" type="empty"/>
      <property name="&lt;Super&gt;p" type="empty"/>
      <property name="&lt;Primary&gt;Escape" type="empty"/>
      <property name="XF86WWW" type="empty"/>
      <property name="HomePage" type="empty"/>
      <property name="XF86Mail" type="empty"/>
      <property name="Print" type="empty"/>
      <property name="&lt;Alt&gt;Print" type="empty"/>
      <property name="&lt;Shift&gt;Print" type="empty"/>
      <property name="&lt;Super&gt;e" type="empty"/>
      <property name="&lt;Primary&gt;&lt;Alt&gt;f" type="empty"/>
      <property name="&lt;Primary&gt;&lt;Alt&gt;Escape" type="empty"/>
      <property name="&lt;Primary&gt;&lt;Shift&gt;Escape" type="empty"/>
      <property name="&lt;Super&gt;r" type="empty">
        <property name="startup-notify" type="empty"/>
      </property>
      <property name="&lt;Alt&gt;&lt;Super&gt;s" type="empty"/>
    </property>
    <property name="custom" type="empty">
      <property name="&lt;Primary&gt;w" type="string" value="xfce4-appfinder"/>
      <property name="&lt;Primary&gt;b" type="string" value="exo-open --launch WebBrowser"/>
      <property name="&lt;Primary&gt;e" type="string" value="thunar"/>
      <property name="&lt;Primary&gt;t" type="string" value="exo-open --launch TerminalEmulator"/>
      <property name="&lt;Primary&gt;&lt;Shift&gt;s" type="string" value="xfce4-screenshooter -r"/>
      <property name="&lt;Primary&gt;q" type="string" value="xfce4-session-logout"/>
      <property name="&lt;Primary&gt;at" type="string" value="xfce4-screenshooter -r"/>
      <property name="&lt;Alt&gt;F2" type="string" value="xfce4-appfinder --collapsed">
        <property name="startup-notify" type="bool" value="true"/>
      </property>
      <property name="&lt;Alt&gt;Print" type="string" value="xfce4-screenshooter -w"/>
      <property name="&lt;Super&gt;r" type="string" value="xfce4-appfinder -c">
        <property name="startup-notify" type="bool" value="true"/>
      </property>
      <property name="XF86WWW" type="string" value="exo-open --launch WebBrowser"/>
      <property name="XF86Mail" type="string" value="exo-open --launch MailReader"/>
      <property name="&lt;Alt&gt;F3" type="string" value="xfce4-appfinder">
        <property name="startup-notify" type="bool" value="true"/>
      </property>
      <property name="Print" type="string" value="xfce4-screenshooter -f -s /home/flux/Pictures"/>
      <property name="&lt;Super&gt;Return" type="string" value="xfce4-terminal">
        <property name="startup-notify" type="bool" value="true"/>
      </property>
      <property name="&lt;Primary&gt;Escape" type="string" value="xfdesktop --menu"/>
      <property name="&lt;Shift&gt;Print" type="string" value="xfce4-screenshooter -r"/>
      <property name="&lt;Primary&gt;&lt;Alt&gt;Delete" type="string" value="xfce4-session-logout"/>
      <property name="&lt;Alt&gt;&lt;Super&gt;s" type="string" value="orca"/>
      <property name="&lt;Primary&gt;&lt;Alt&gt;t" type="string" value="exo-open --launch TerminalEmulator"/>
      <property name="&lt;Primary&gt;&lt;Alt&gt;f" type="string" value="thunar"/>
      <property name="&lt;Primary&gt;&lt;Alt&gt;l" type="string" value="xflock4"/>
      <property name="&lt;Alt&gt;F1" type="string" value="xfce4-popup-applicationsmenu"/>
      <property name="&lt;Super&gt;p" type="string" value="xfce4-display-settings --minimal"/>
      <property name="&lt;Primary&gt;&lt;Shift&gt;Escape" type="string" value="xfce4-taskmanager"/>
      <property name="&lt;Super&gt;e" type="string" value="thunar"/>
      <property name="&lt;Primary&gt;&lt;Alt&gt;Escape" type="string" value="xkill"/>
      <property name="HomePage" type="string" value="exo-open --launch WebBrowser"/>
      <property name="XF86Display" type="string" value="xfce4-display-settings --minimal"/>
      <property name="override" type="bool" value="true"/>
    </property>
  </property>
  <property name="xfwm4" type="empty">
    <property name="default" type="empty">
      <property name="&lt;Alt&gt;Insert" type="empty"/>
      <property name="Escape" type="empty"/>
      <property name="Left" type="empty"/>
      <property name="Right" type="empty"/>
      <property name="Up" type="empty"/>
      <property name="Down" type="empty"/>
      <property name="&lt;Alt&gt;Tab" type="empty"/>
      <property name="&lt;Alt&gt;&lt;Shift&gt;Tab" type="empty"/>
      <property name="&lt;Alt&gt;Delete" type="empty"/>
      <property name="&lt;Alt&gt;F4" type="empty"/>
      <property name="&lt;Alt&gt;F6" type="empty"/>
      <property name="&lt;Alt&gt;F7" type="empty"/>
      <property name="&lt;Alt&gt;F8" type="empty"/>
      <property name="&lt;Alt&gt;F9" type="empty"/>
      <property name="&lt;Alt&gt;F10" type="empty"/>
      <property name="&lt;Alt&gt;F11" type="empty"/>
      <property name="&lt;Alt&gt;F12" type="empty"/>
      <property name="&lt;Primary&gt;&lt;Alt&gt;d" type="empty"/>
      <property name="&lt;Super&gt;Tab" type="empty"/>
      <property name="&lt;Super&gt;KP_Left" type="empty"/>
      <property name="&lt;Super&gt;KP_Right" type="empty"/>
      <property name="&lt;Super&gt;KP_Down" type="empty"/>
      <property name="&lt;Super&gt;KP_Up" type="empty"/>
    </property>
    <property name="custom" type="empty">
      <property name="&lt;Alt&gt;F4" type="string" value="close_window_key"/>
      <property name="&lt;Super&gt;KP_Down" type="string" value="tile_down_key"/>
      <property name="&lt;Super&gt;KP_Up" type="string" value="tile_up_key"/>
      <property name="&lt;Super&gt;KP_Right" type="string" value="tile_right_key"/>
      <property name="&lt;Super&gt;KP_Left" type="string" value="tile_left_key"/>
      <property name="Right" type="string" value="right_key"/>
      <property name="Down" type="string" value="down_key"/>
      <property name="&lt;Alt&gt;Tab" type="string" value="cycle_windows_key"/>
      <property name="&lt;Alt&gt;F6" type="string" value="stick_window_key"/>
      <property name="&lt;Alt&gt;F10" type="string" value="maximize_window_key"/>
      <property name="&lt;Alt&gt;Delete" type="string" value="del_workspace_key"/>
      <property name="&lt;Super&gt;Tab" type="string" value="switch_window_key"/>
      <property name="&lt;Primary&gt;&lt;Alt&gt;d" type="string" value="show_desktop_key"/>
      <property name="&lt;Alt&gt;F7" type="string" value="move_window_key"/>
      <property name="Up" type="string" value="up_key"/>
      <property name="&lt;Alt&gt;F11" type="string" value="fullscreen_key"/>
      <property name="Escape" type="string" value="cancel_key"/>
      <property name="&lt;Alt&gt;&lt;Shift&gt;Tab" type="string" value="cycle_reverse_windows_key"/>
      <property name="&lt;Alt&gt;F12" type="string" value="above_key"/>
      <property name="&lt;Alt&gt;F8" type="string" value="resize_window_key"/>
      <property name="&lt;Alt&gt;F9" type="string" value="hide_window_key"/>
      <property name="Left" type="string" value="left_key"/>
      <property name="&lt;Alt&gt;Insert" type="string" value="add_workspace_key"/>
      <property name="override" type="bool" value="true"/>
    </property>
  </property>
  <property name="providers" type="array">
    <value type="string" value="xfwm4"/>
    <value type="string" value="commands"/>
  </property>
</channel>
SHORTCUTEOF

echo "Bliss: Keyboard shortcuts configured!"


_flux_write_folio_xfce_panel() {
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
        cat > "$_appdir/folio-terminal.desktop" <<'FOLIO_DESKTOP'
[Desktop Entry]
Type=Application
Version=1.0
Name=Terminal
Comment=Terminal emulator
Exec=xfce4-terminal
Icon=utilities-terminal
Terminal=false
Categories=System;TerminalEmulator;
FOLIO_DESKTOP
        cat > "$_appdir/folio-files.desktop" <<'FOLIO_DESKTOP'
[Desktop Entry]
Type=Application
Version=1.0
Name=Files
Comment=File manager
Exec=thunar
Icon=folder
Terminal=false
Categories=System;FileManager;
FOLIO_DESKTOP
        if command -v xfce4-settings-manager >/dev/null 2>&1; then
            cat > "$_appdir/folio-settings.desktop" <<'FOLIO_DESKTOP'
[Desktop Entry]
Type=Application
Version=1.0
Name=Settings
Comment=Desktop settings
Exec=xfce4-settings-manager
Icon=preferences-system
Terminal=false
Categories=Settings;
FOLIO_DESKTOP
        fi
        if [ -n "$_browser" ]; then
            cat > "$_appdir/folio-browser.desktop" <<FOLIO_DESKTOP
[Desktop Entry]
Type=Application
Version=1.0
Name=Browser
Comment=Web browser
Exec=$_browser
Icon=applications-internet
Terminal=false
Categories=Network;WebBrowser;
FOLIO_DESKTOP
        fi
        # Root runs: hand the launcher entries to the home owner.
        _hu=$(stat -c %u "$_phome" 2>/dev/null || stat -f %u "$_phome" 2>/dev/null || true)
        _hg=$(stat -c %g "$_phome" 2>/dev/null || stat -f %g "$_phome" 2>/dev/null || true)
        if [ -n "$_hu" ] && [ "$(id -u 2>/dev/null || echo 0)" = "0" ]; then
            chown "$_hu:$_hg" "$_appdir"/folio-*.desktop 2>/dev/null || true
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
        <value type='string' value='folio-terminal.desktop'/>
      </property>
    </property>"

    _pid=$((_pid + 1))
    _dock_ids="$_dock_ids        <value type='int' value='$_pid'/>
"
    _plugins="$_plugins
    <property name='plugin-$_pid' type='string' value='launcher'>
      <property name='items' type='array'>
        <value type='string' value='folio-files.desktop'/>
      </property>
    </property>"

    if command -v xfce4-settings-manager >/dev/null 2>&1; then
        _pid=$((_pid + 1))
        _dock_ids="$_dock_ids        <value type='int' value='$_pid'/>
"
        _plugins="$_plugins
    <property name='plugin-$_pid' type='string' value='launcher'>
      <property name='items' type='array'>
        <value type='string' value='folio-settings.desktop'/>
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
        <value type='string' value='folio-browser.desktop'/>
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

# 5. Configure XFCE4 Panel
echo "Bliss: Configuring Panel..."
PANEL_CONFIG_DIR="$USER_HOME/.config/xfce4/xfconf/xfce-perchannel-xml"
mkdir -p "$PANEL_CONFIG_DIR"

# Android-style phone panels (thin status bar + floating dock), written by
# the shared writer above; idempotent + graceful plugin degrade.
_flux_write_folio_xfce_panel "$PANEL_CONFIG_DIR" "$USER_HOME" || true
_flux_chown_r "$CUSTOM_USER:$CUSTOM_GROUP" "$PANEL_CONFIG_DIR"

# ── Compact phone layout: top status bar + floating dock + shortcuts ──────
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

    # Android-style phone panels (thin status bar + floating dock); the
    # shared writer degrades gracefully and leaves _menu_plugin set for the
    # keyboard-shortcut block below.
    _flux_write_folio_xfce_panel "$_cfg" "$USER_HOME" || true

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

# Create plugin configuration files
PLUGIN_CONFIG_DIR="$USER_HOME/.config/xfce4/panel"
mkdir -p "$PLUGIN_CONFIG_DIR"

# Create cpufreq plugin configuration
cat <<'EOF' > "$PLUGIN_CONFIG_DIR/cpufreq-20.rc"
show_icon=false
show_label_governor=false
keep_compact=true
one_line=true
EOF

# Create info.sh script (RAM, SWAP, and Battery)
cat <<'EOF' > "$USER_HOME/.config/info.sh"
#!/bin/bash

# Get combined memory percentage (RAM + SWAP)
MEM_PERCENT=$(free -m | awk '
/Mem:/ {
    mem_used = $3
    mem_total = $2
}
/Swap:/ {
    swap_used = $3
    swap_total = $2
}
END {
    total = mem_total + swap_total
    used = mem_used + swap_used
    if (total > 0) {
        percent = (used / total) * 100
        printf "%.0f", percent
    } else {
        print "0"
    }
}')

# Get battery info from sysfs
BATTERY_PATH="/sys/class/power_supply/battery"
if [ -f "$BATTERY_PATH/capacity" ] && [ -f "$BATTERY_PATH/status" ]; then
    CAPACITY=$(cat "$BATTERY_PATH/capacity" 2>/dev/null || echo "0")
    STATUS=$(cat "$BATTERY_PATH/status" 2>/dev/null || echo "Unknown")
    
    # Choose indicator based on status
    if [ "$STATUS" = "Charging" ]; then
        INDICATOR="CHG"
    elif [ "$STATUS" = "Full" ]; then
        INDICATOR="FULL"
    else
        INDICATOR="BAT"
    fi
    
    BATTERY_INFO=" | ${INDICATOR} ${CAPACITY}%"
else
    BATTERY_INFO=""
fi

# Output in genmon XML format
echo "<txt>MEM ${MEM_PERCENT}%${BATTERY_INFO}</txt>"
EOF

chmod +x "$USER_HOME/.config/info.sh"

# Create genmon plugin configuration (both 19 and 24)
cat <<EOF > "$PLUGIN_CONFIG_DIR/genmon-19.rc"
Command=$USER_HOME/.config/info.sh
UseLabel=0
Text=(genmon)
UpdatePeriod=1000
Font=JetBrainsMono Nerd Font 10
EOF

cat <<EOF > "$PLUGIN_CONFIG_DIR/genmon-24.rc"
Command=$USER_HOME/.config/info.sh
UseLabel=0
Text=(genmon)
UpdatePeriod=1000
Font=JetBrainsMono Nerd Font 10
EOF

_flux_chown_r "$CUSTOM_USER:$CUSTOM_GROUP" "$PLUGIN_CONFIG_DIR"
_flux_chown "$CUSTOM_USER:$CUSTOM_GROUP" "$USER_HOME/.config/info.sh"


# 6. Configure Terminal (Direct Config File)
echo "Bliss: Configuring Terminal..."
TERM_CONFIG_DIR="$USER_HOME/.config/xfce4/terminal"
mkdir -p "$TERM_CONFIG_DIR"
cat <<EOF > "$TERM_CONFIG_DIR/terminalrc"
[Configuration]
FontUseSystem=TRUE
FontName=JetBrainsMono Nerd Font 10
MiscAlwaysShowTabs=FALSE
MiscBell=FALSE
MiscBordersDefault=TRUE
MiscCursorBlinks=FALSE
MiscCursorShape=TERMINAL_CURSOR_SHAPE_IBEAM
MiscDefaultGeometry=80x24
MiscInheritGeometry=FALSE
MiscMenubarDefault=FALSE
MiscMouseAutohide=FALSE
MiscToolbarDefault=FALSE
MiscConfirmClose=TRUE
MiscCycleTabs=TRUE
MiscTabCloseButtons=TRUE
MiscTabCloseMiddleClick=TRUE
MiscTabPosition=TERMINAL_TAB_POSITION_TOP
MiscHighlightUrls=TRUE
MiscScrollAlternateScreen=TRUE
ScrollingLines=1000
BackgroundMode=TERMINAL_BACKGROUND_TRANSPARENT
BackgroundDarkness=0.7
EOF
_flux_chown_r "$CUSTOM_USER:$CUSTOM_GROUP" "$USER_HOME/.config"


# 7. Configure Zsh and Terminal Enhancements
echo "Bliss: Configuring Zsh and Terminal..."

# Bounded network helpers (proot hangs forever on bare curl/git)
_flux_have_timeout() { command -v timeout >/dev/null 2>&1; }
_flux_run() {
    # _flux_run <seconds> <cmd...>
    local sec="$1"; shift
    if _flux_have_timeout; then
        timeout "$sec" "$@"
    else
        "$@"
    fi
}
_flux_git_clone() {
    # _flux_git_clone <url> <dest> [timeout_sec]
    local url="$1" dest="$2" sec="${3:-90}"
    if ! command -v git >/dev/null 2>&1; then
        echo "Bliss: git not in guest PATH — skip clone $url"
        return 1
    fi
    export GIT_TERMINAL_PROMPT=0
    export GIT_HTTP_LOW_SPEED_LIMIT=1000
    export GIT_HTTP_LOW_SPEED_TIME=30
    if [ -d "$dest/.git" ] || [ -n "$(ls -A "$dest" 2>/dev/null)" ]; then
        return 0
    fi
    rm -rf "$dest"
    mkdir -p "$(dirname "$dest")"
    _flux_run "$sec" git clone --depth 1 --single-branch --quiet "$url" "$dest"
}

# Safe remove: rename first (instant), delete in background — never block forever on proot unlink
_flux_safe_rm_tree() {
    local d="$1"
    [ -e "$d" ] || return 0
    local trash="${d}.trash.$$"
    if mv "$d" "$trash" 2>/dev/null; then
        if _flux_have_timeout; then
            timeout 45 rm -rf "$trash" 2>/dev/null || (rm -rf "$trash" >/dev/null 2>&1 &)
        else
            (rm -rf "$trash" >/dev/null 2>&1 &)
        fi
    else
        if _flux_have_timeout; then
            timeout 45 rm -rf "$d" 2>/dev/null || true
        else
            rm -rf "$d" 2>/dev/null || true
        fi
    fi
}

# zsh/git should already be present from apt above; re-check for older rootfs
if ! command -v zsh >/dev/null 2>&1; then
    echo "Bliss: Installing zsh..."
    DEBIAN_FRONTEND=noninteractive apt-get install -y -o Dpkg::Use-Pty=0 zsh 2>/dev/null || true
else
    echo "Bliss: zsh already installed — skip apt"
fi
if ! command -v git >/dev/null 2>&1; then
    echo "Bliss: Installing git (needed for Oh My Zsh fallback)..."
    DEBIAN_FRONTEND=noninteractive apt-get install -y -o Dpkg::Use-Pty=0 git 2>/dev/null || true
fi

# ── Oh My Zsh ────────────────────────────────────────────────────────────────
# Prefer host pre-install (FLUX_SKIP_OMZ=1 + oh-my-zsh.sh present).
# Never use curl|sh under proot — hangs. Never mkdir empty OMZ after a failed clone
# (that re-triggers plugin clones into a broken tree).
echo "Bliss: Installing Oh My Zsh..."

OMZ_OK=0
if [ -f "$USER_HOME/.oh-my-zsh/oh-my-zsh.sh" ]; then
    if [ "${FLUX_SKIP_OMZ:-0}" = "1" ]; then
        echo "Bliss: Oh My Zsh pre-installed on host — skip guest install"
    else
        echo "Bliss: Oh My Zsh already valid — skip install"
    fi
    OMZ_OK=1
else
    # Partial / corrupt tree — remove with bounded rename+rm (not bare rm -rf hang)
    if [ -e "$USER_HOME/.oh-my-zsh" ]; then
        echo "Bliss: Incomplete Oh My Zsh detected — removing (bounded)…"
        _flux_safe_rm_tree "$USER_HOME/.oh-my-zsh"
    fi
    if [ "${FLUX_SKIP_OMZ:-0}" = "1" ]; then
        echo "Bliss: FLUX_SKIP_OMZ set but oh-my-zsh.sh missing — guest fallback"
    fi
    if command -v git >/dev/null 2>&1; then
        echo "Bliss: Shallow git clone Oh My Zsh (timeout 120s)…"
        if _flux_git_clone "https://github.com/ohmyzsh/ohmyzsh.git" "$USER_HOME/.oh-my-zsh" 120 \
            && [ -f "$USER_HOME/.oh-my-zsh/oh-my-zsh.sh" ]; then
            echo "Bliss: Oh My Zsh installed"
            OMZ_OK=1
        else
            echo "Bliss: WARNING: Oh My Zsh install failed/timed out — continuing without it"
            _flux_safe_rm_tree "$USER_HOME/.oh-my-zsh"
        fi
    else
        echo "Bliss: WARNING: git unavailable — Oh My Zsh skipped (host stage or install git)"
        _flux_safe_rm_tree "$USER_HOME/.oh-my-zsh"
    fi
fi

# Plugins/themes only when OMZ is actually valid — do not recreate empty tree
if [ "$OMZ_OK" = "1" ]; then
    ZSH_CUSTOM="$USER_HOME/.oh-my-zsh/custom"
    mkdir -p "$ZSH_CUSTOM/plugins" "$ZSH_CUSTOM/themes" 2>/dev/null || true

    echo "Bliss: Installing Zsh plugins (if missing)…"
    if [ ! -d "$ZSH_CUSTOM/plugins/zsh-autosuggestions/.git" ] \
        && [ ! -f "$ZSH_CUSTOM/plugins/zsh-autosuggestions/zsh-autosuggestions.zsh" ]; then
        if command -v git >/dev/null 2>&1; then
            _flux_git_clone "https://github.com/zsh-users/zsh-autosuggestions.git" \
                "$ZSH_CUSTOM/plugins/zsh-autosuggestions" 60 || true
        else
            echo "Bliss: git missing — skip zsh-autosuggestions"
        fi
    else
        echo "Bliss: zsh-autosuggestions already present — skip"
    fi
    if [ ! -d "$ZSH_CUSTOM/plugins/zsh-syntax-highlighting/.git" ] \
        && [ ! -f "$ZSH_CUSTOM/plugins/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh" ]; then
        if command -v git >/dev/null 2>&1; then
            _flux_git_clone "https://github.com/zsh-users/zsh-syntax-highlighting.git" \
                "$ZSH_CUSTOM/plugins/zsh-syntax-highlighting" 60 || true
        else
            echo "Bliss: git missing — skip zsh-syntax-highlighting"
        fi
    else
        echo "Bliss: zsh-syntax-highlighting already present — skip"
    fi
    # Do NOT install zsh-autocomplete

    # Local monochrome prompt theme — no remote download (old colorful theme removed).
    rm -f "$ZSH_CUSTOM/themes/agnosterzak.zsh-theme" 2>/dev/null || true
    if [ ! -s "$ZSH_CUSTOM/themes/folio.zsh-theme" ]; then
        echo "Bliss: Installing folio prompt theme…"
        cat > "$ZSH_CUSTOM/themes/folio.zsh-theme" <<'FOLIO_THEME_EOF'
# folio.zsh-theme — Bliss's minimal monochrome prompt.
# White/gray on black only; ASCII-safe (no powerline glyphs, no ANSI colors 31-44).
# Segments:  time  bliss  working-dir  input-char
PROMPT='%F{240}%D{%H:%M}%f %F{255}bliss%f %F{250}%~%f %F{255}%#%f '
PROMPT2='%F{240}> %f'
FOLIO_THEME_EOF
        if [ ! -s "$ZSH_CUSTOM/themes/folio.zsh-theme" ]; then
            echo "Bliss: WARNING: folio prompt theme write failed — zsh keeps default prompt"
        fi
    else
        echo "Bliss: folio prompt theme already present — skip"
    fi
    _flux_chown_r "$CUSTOM_USER:$CUSTOM_GROUP" "$USER_HOME/.oh-my-zsh"
else
    echo "Bliss: Skipping Zsh plugins (Oh My Zsh not installed)"
fi

# pokemon-colorscripts: optional; default skip under proot (gitlab stalls; needs git)
if [ "${FLUX_SKIP_POKEMON:-1}" = "1" ]; then
    echo "Bliss: pokemon-colorscripts skip (disabled by default)"
elif command -v pokemon-colorscripts >/dev/null 2>&1; then
    echo "Bliss: pokemon-colorscripts already present — skip"
elif ! command -v git >/dev/null 2>&1; then
    echo "Bliss: pokemon-colorscripts skip (git missing)"
else
    echo "Bliss: Installing pokemon-colorscripts (optional, 60s budget)…"
    POKEMON_TEMP="/tmp/pokemon-colorscripts.$$"
    _flux_safe_rm_tree "$POKEMON_TEMP"
    if _flux_git_clone "https://gitlab.com/phoneybadger/pokemon-colorscripts.git" "$POKEMON_TEMP" 60 \
        && [ -f "$POKEMON_TEMP/install.sh" ]; then
        (cd "$POKEMON_TEMP" && _flux_run 30 sh ./install.sh) 2>/dev/null \
            || echo "Bliss: pokemon-colorscripts install skipped"
    else
        echo "Bliss: pokemon-colorscripts skipped (clone timeout/fail)"
    fi
    _flux_safe_rm_tree "$POKEMON_TEMP"
fi

# Configure .zshrc
echo "Bliss: Configuring .zshrc..."
ZSHRC="$USER_HOME/.zshrc"

# Write complete optimized .zshrc (performance fixes)
# - Removed zsh-autocomplete (extremely slow on PRoot, 35s+ startup)
# - Backgrounded visuals with &! (async, don't block shell startup)
# - DISABLE_AUTO_UPDATE / DISABLE_UPDATE_PROMPT (no prompts on launch)
# - ZSH_DISABLE_COMPFIX (no compaudit, faster init)
# Defensive: never hard-fail on missing oh-my-zsh / pokemon
echo "Bliss: Writing optimized .zshrc..."
cat > "$ZSHRC" << 'ZSHEOF'
# PATH setup - local bin, npm global modules
export PATH="$HOME/.local/bin:/opt/nodejs/bin:$PATH"

# Setup Locales
export LANG=en_US.UTF-8
export LC_ALL=en_US.UTF-8

# Fix XDG_RUNTIME_DIR (not set in PRoot/chroot — no systemd-logind)
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/tmp}"

# Launch sticker + info block live in ~/.config/folio/banner.sh (sourced at the
# end of this file): one random colored anime ASCII design per shell open.
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
# ── Bliss launch sticker (random truecolor anime banner; opt out FLUX_NO_BANNER=1)
[ -r "$HOME/.config/folio/banner.sh" ] && . "$HOME/.config/folio/banner.sh"

ZSHEOF
_flux_chown "$CUSTOM_USER:$CUSTOM_GROUP" "$ZSHRC"

# ── Bliss launch stickers: 5 truecolor anime half-block designs ─────────────
# Installs ~/.config/folio/banner/*.txt (random pick per shell open) and
# ~/.config/folio/banner.sh (the folio-banner function sourced by .zshrc).
# Offline, idempotent (`.banner-rev` refreshes existing installs).
# Opt out at runtime: FLUX_NO_BANNER=1.
_flux_write_folio_banner() {
    _home="$1"
    _owner="${2:-}"
    [ -n "$_home" ] || return 0
    _bdir="$_home/.config/folio/banner"
    _brev="4"
    _need=0
    if [ "$(cat "$_bdir/.banner-rev" 2>/dev/null || true)" != "$_brev" ]; then
        _need=1
    fi
    for _b in 01-anime-girl 02-maid 03-kitsune 04-chibi 05-catgirl; do
        [ -s "$_bdir/$_b.txt" ] || _need=1
    done
    [ -s "$_home/.config/folio/banner.sh" ] || _need=1
    [ "$_need" = "1" ] || return 0

    mkdir -p "$_bdir" 2>/dev/null || return 0
    rm -f "$_bdir"/*.txt 2>/dev/null || true
    cat > "$_bdir/.folio-banners.$$" <<'FOLIO_BANNER_ALL'
==>01-anime-girl
\033[38;2;0;0;0;48;2;116;84;36m▀\033[38;2;116;84;44;48;2;124;92;44m▀\033[38;2;124;84;44;48;2;116;84;44m▀\033[48;2;124;84;44m                                 \033[38;2;108;76;36m▀\033[48;2;100;76;36m \033[38;2;116;84;44m▀\033[48;2;124;84;44m    \033[0m
\033[48;2;124;84;44m \033[38;2;116;84;44m▀                                   \033[38;2;116;84;36m▀\033[48;2;100;76;36m \033[38;2;116;84;44;48;2;108;76;36m▀\033[48;2;124;84;44m   \033[0m
\033[48;2;124;84;44m                 \033[38;2;124;84;44;48;2;116;84;36m▀▀\033[48;2;124;84;44m  \033[38;2;124;84;44;48;2;116;84;36m▀\033[38;2;116;84;36;48;2;124;84;44m▀\033[38;2;124;84;44;48;2;116;84;36m▀▀\033[48;2;124;84;44m           \033[48;2;116;84;44m \033[48;2;124;84;44m \033[38;2;100;76;36;48;2;108;76;36m▀\033[48;2;100;76;36m \033[48;2;124;84;44m \033[38;2;116;84;44m▀ \033[0m
\033[48;2;124;84;44m \033[38;2;124;84;44;48;2;116;84;36m▀▀▀▀▀▀▀▀▀▀\033[48;2;124;84;44m \033[38;2;116;84;36;48;2;124;92;44m▀▀\033[38;2;124;84;44m▀ \033[38;2;116;84;36;48;2;108;76;36m▀\033[38;2;140;108;68;48;2;164;124;76m▀\033[38;2;148;116;68;48;2;252;196;148m▀\033[38;2;108;76;36;48;2;140;100;52m▀\033[38;2;124;84;44;48;2;116;84;36m▀\033[38;2;116;84;36;48;2;124;84;44m▀\033[38;2;140;100;52m▀\033[38;2;188;156;108;48;2;236;196;148m▀\033[38;2;116;84;36;48;2;188;156;108m▀\033[38;2;124;84;44;48;2;108;76;36m▀\033[48;2;124;92;44m▀\033[48;2;124;84;44m           \033[38;2;116;84;36;48;2;116;84;44m▀\033[48;2;100;76;36m \033[38;2;124;84;44;48;2;116;84;44m▀\033[48;2;124;84;44m  \033[0m
\033[38;2;140;100;52;48;2;148;116;68m▀▀\033[38;2;124;92;44m▀\033[48;2;140;108;68m▀\033[48;2;164;124;76m▀\033[48;2;188;156;108m▀\033[48;2;140;108;68m▀\033[48;2;148;116;68m▀\033[48;2;140;108;68m▀\033[48;2;140;100;52m \033[38;2;140;100;52;48;2;124;92;44m▀\033[38;2;140;108;68;48;2;124;84;44m▀\033[38;2;140;100;52;48;2;116;84;36m▀\033[38;2;124;92;44m▀\033[48;2;124;84;44m  \033[48;2;108;76;36m \033[48;2;164;124;76m \033[38;2;252;236;172;48;2;252;228;172m▀\033[48;2;236;196;148m \033[38;2;108;76;36;48;2;188;156;108m▀\033[38;2;116;84;44;48;2;116;84;36m▀ \033[38;2;164;124;76;48;2;188;156;108m▀\033[38;2;212;188;140;48;2;252;236;180m▀\033[38;2;148;116;68;48;2;252;228;172m▀\033[38;2;108;76;36;48;2;140;100;52m▀\033[38;2;124;84;44;48;2;116;84;36m▀\033[48;2;124;84;44m          \033[48;2;116;84;44m \033[48;2;100;76;36m \033[38;2;116;84;44;48;2;124;84;44m▀  \033[0m
\033[38;2;140;100;52;48;2;116;84;36m▀\033[48;2;124;84;44m▀\033[38;2;140;108;68m▀\033[38;2;140;100;52;48;2;108;76;36m▀\033[38;2;164;124;76;48;2;188;156;108m▀\033[38;2;252;228;172;48;2;252;236;172m▀\033[38;2;164;124;76;48;2;236;196;148m▀\033[48;2;116;84;36m \033[38;2;124;92;44m▀\033[38;2;116;84;36;48;2;124;92;44m▀\033[48;2;124;84;44m▀     \033[48;2;116;84;36m \033[38;2;164;116;68;48;2;148;116;68m▀\033[48;2;252;228;172m  \033[38;2;252;236;180m▀\033[38;2;188;156;108;48;2;252;236;180m▀\033[38;2;100;76;36;48;2;188;156;108m▀\033[38;2;164;124;76;48;2;140;100;52m▀\033[38;2;252;228;172;48;2;252;196;148m▀\033[38;2;252;236;172;48;2;252;244;188m▀\033[38;2;236;196;148m▀\033[38;2;116;84;36;48;2;188;156;108m▀\033[38;2;124;84;44;48;2;108;76;36m▀\033[48;2;124;84;44m         \033[48;2;116;84;44m \033[38;2;100;76;36;48;2;108;76;36m▀\033[48;2;124;84;44m   \033[0m
\033[48;2;124;84;44m   \033[38;2;116;84;36;48;2;108;76;36m▀\033[38;2;164;124;76;48;2;188;156;108m▀\033[38;2;188;156;108;48;2;252;228;172m▀\033[38;2;212;188;140m▀\033[38;2;164;124;76m▀\033[38;2;108;76;36;48;2;140;100;52m▀\033[38;2;140;100;52;48;2;108;76;36m▀\033[38;2;164;124;76;48;2;228;172;124m▀\033[38;2;108;76;36;48;2;164;124;76m▀\033[38;2;124;84;44;48;2;108;76;36m▀\033[48;2;124;92;44m▀\033[48;2;124;84;44m  \033[38;2;116;84;36;48;2;116;84;44m▀\033[38;2;140;100;52;48;2;124;84;44m▀\033[38;2;236;196;148;48;2;228;172;124m▀\033[38;2;252;236;172;48;2;236;196;148m▀▀\033[38;2;252;244;188;48;2;156;140;108m▀\033[38;2;236;196;148;48;2;68;44;20m▀\033[38;2;100;76;44m▀\033[38;2;116;84;44;48;2;0;0;0m▀\033[38;2;140;108;68m▀\033[38;2;156;140;108m▀\033[38;2;236;196;148;48;2;68;44;20m▀\033[38;2;164;124;76;48;2;100;92;68m▀\033[38;2;116;84;36;48;2;124;84;44m▀        \033[48;2;108;76;36m▀ \033[48;2;124;84;44m   \033[0m
\033[48;2;124;84;44m   \033[48;2;116;84;36m \033[38;2;228;172;124;48;2;188;156;108m▀\033[48;2;252;236;172m \033[38;2;252;228;172;48;2;252;236;180m▀\033[38;2;252;236;180;48;2;252;244;188m▀\033[38;2;212;188;140m▀\033[38;2;100;76;36;48;2;188;156;108m▀\033[38;2;188;156;108;48;2;140;100;52m▀\033[48;2;252;228;172m \033[38;2;140;108;68m▀\033[38;2;108;76;36;48;2;140;100;52m▀\033[38;2;124;92;44;48;2;116;84;36m▀\033[38;2;124;84;44;48;2;124;92;44m▀\033[48;2;124;84;44m \033[48;2;108;76;36m \033[38;2;188;156;108;48;2;164;124;76m▀\033[38;2;252;236;172;48;2;252;228;172m▀\033[38;2;140;108;68;48;2;100;92;68m▀\033[38;2;0;0;0;48;2;188;156;108m▀\033[38;2;156;140;108;48;2;252;252;252m▀\033[38;2;100;92;68;48;2;100;76;44m▀\033[38;2;0;0;0;48;2;68;44;20m▀\033[38;2;68;44;20;48;2;0;0;0m▀\033[48;2;68;44;20m \033[38;2;68;44;20;48;2;0;0;0m▀\033[48;2;100;92;68m▀▀\033[38;2;124;92;44;48;2;100;76;36m▀\033[38;2;116;84;36;48;2;124;92;44m▀\033[38;2;124;84;44;48;2;116;84;44m▀\033[48;2;124;84;44m   \033[38;2;116;84;44m▀\033[38;2;124;84;44;48;2;116;84;36m▀\033[38;2;108;76;36;48;2;100;76;36m▀\033[38;2;116;84;36;48;2;124;84;44m▀\033[38;2;124;84;44;48;2;116;84;44m▀\033[48;2;124;84;44m  \033[0m
\033[48;2;124;84;44m   \033[38;2;108;76;36;48;2;116;84;36m▀\033[38;2;228;172;124;48;2;188;156;108m▀\033[38;2;252;228;172;48;2;100;76;44m▀\033[38;2;188;156;108;48;2;0;0;0m▀\033[38;2;140;108;68;48;2;100;92;68m▀\033[48;2;68;44;20m▀\033[38;2;188;156;108;48;2;0;0;0m▀\033[38;2;140;108;68;48;2;68;44;20m▀\033[38;2;228;172;124;48;2;164;124;76m▀\033[38;2;252;236;180;48;2;252;228;172m▀\033[38;2;236;196;148;48;2;252;236;180m▀\033[38;2;140;100;52;48;2;236;196;148m▀\033[38;2;108;76;36;48;2;140;100;52m▀\033[38;2;124;92;44;48;2;108;76;36m▀\033[38;2;116;84;36;48;2;124;92;44m▀\033[38;2;140;100;52;48;2;116;84;36m▀\033[38;2;236;196;148;48;2;228;172;124m▀\033[38;2;252;228;172;48;2;252;236;172m▀\033[38;2;244;244;204;48;2;252;236;180m▀\033[48;2;252;252;252m \033[38;2;68;44;20;48;2;100;92;68m▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;68;44;20m▀\033[38;2;68;44;20;48;2;0;0;0m▀\033[38;2;0;0;0;48;2;156;140;108m▀\033[38;2;100;76;44m▀\033[48;2;212;204;188m \033[38;2;68;44;20;48;2;108;76;36m▀\033[38;2;124;92;44;48;2;116;84;44m▀\033[38;2;116;84;44;48;2;124;84;44m▀ \033[38;2;124;84;44;48;2;116;84;36m▀\033[48;2;108;76;36m▀\033[48;2;124;92;44m \033[38;2;100;76;36;48;2;68;44;20m▀\033[48;2;0;0;0m \033[38;2;116;84;44;48;2;68;44;20m▀\033[38;2;124;84;44;48;2;124;92;44m▀\033[48;2;116;84;36m▀\033[48;2;124;84;44m \033[0m
\033[48;2;124;84;44m  \033[48;2;116;84;44m \033[38;2;108;76;36;48;2;124;92;44m▀\033[48;2;68;44;20m \033[38;2;68;44;20;48;2;140;108;68m▀\033[38;2;212;204;188;48;2;252;252;252m▀\033[48;2;212;204;188m \033[38;2;68;44;20;48;2;0;0;0m▀\033[48;2;68;44;20m \033[48;2;0;0;0m \033[38;2;100;76;44m▀\033[38;2;252;228;172;48;2;212;188;140m▀\033[48;2;252;236;172m▀\033[38;2;252;236;172;48;2;252;228;172m▀\033[38;2;252;228;172;48;2;252;236;172m▀\033[38;2;148;116;68m▀\033[38;2;108;76;36;48;2;188;156;108m▀\033[48;2;108;76;36m \033[38;2;164;124;76;48;2;124;84;44m▀\033[38;2;252;228;172;48;2;252;196;148m▀\033[48;2;252;228;172m \033[38;2;252;252;252;48;2;244;244;204m▀\033[38;2;156;140;108;48;2;212;204;188m▀\033[38;2;0;0;0;48;2;68;44;20m▀ ▀\033[38;2;156;140;108;48;2;0;0;0m▀\033[38;2;212;204;188;48;2;100;92;68m▀\033[48;2;212;204;188m \033[38;2;116;84;36;48;2;108;76;36m▀\033[48;2;124;84;44m  \033[38;2;116;84;36;48;2;108;76;36m▀\033[38;2;140;108;68;48;2;188;156;108m▀\033[38;2;164;124;76;48;2;252;236;180m▀\033[38;2;100;76;36;48;2;68;44;20m▀\033[48;2;0;0;0m   \033[38;2;124;84;44;48;2;100;76;36m▀\033[48;2;124;92;44m▀\033[48;2;116;84;44m▀\033[0m
\033[48;2;124;84;44m  \033[38;2;116;84;44m▀\033[38;2;124;92;44m▀\033[38;2;100;76;36;48;2;116;84;36m▀\033[38;2;156;140;108;48;2;188;156;108m▀\033[48;2;252;252;252m \033[38;2;244;244;236m▀\033[38;2;68;44;20;48;2;100;92;68m▀\033[48;2;0;0;0m  \033[48;2;100;92;68m \033[48;2;212;204;188m \033[38;2;252;236;172;48;2;252;228;172m▀   \033[38;2;252;236;180m▀\033[38;2;236;196;148;48;2;252;236;180m▀\033[38;2;140;108;68;48;2;252;228;172m▀\033[38;2;188;156;108;48;2;236;196;148m▀\033[48;2;252;228;172m  \033[38;2;244;244;236;48;2;252;236;172m▀\033[38;2;100;92;68;48;2;236;196;148m▀\033[38;2;0;0;0;48;2;100;92;68m▀\033[38;2;68;44;20;48;2;100;76;44m▀\033[38;2;0;0;0;48;2;140;108;68m▀\033[38;2;188;156;108;48;2;252;196;148m▀\033[48;2;164;124;76m▀\033[48;2;108;76;36m \033[48;2;124;84;44m  \033[48;2;108;76;36m \033[38;2;188;156;108;48;2;236;196;148m▀\033[38;2;252;236;180;48;2;212;188;140m▀\033[38;2;68;44;20;48;2;0;0;0m▀   \033[48;2;68;44;20m \033[48;2;124;92;44m \033[48;2;116;84;36m \033[0m
\033[38;2;116;84;44;48;2;124;92;44m▀\033[38;2;124;84;44;48;2;116;84;36m▀\033[48;2;124;84;44m  \033[38;2;116;84;36m▀\033[38;2;164;116;68;48;2;116;84;36m▀\033[38;2;252;228;172;48;2;228;172;124m▀\033[38;2;252;252;252;48;2;252;236;180m▀\033[38;2;212;204;188;48;2;252;252;252m▀\033[38;2;68;44;20;48;2;156;140;108m▀\033[48;2;0;0;0m▀ \033[38;2;100;92;68;48;2;156;140;108m▀\033[48;2;252;244;188m \033[48;2;252;228;172m      \033[38;2;252;236;172m▀   \033[48;2;252;236;172m \033[38;2;252;236;172;48;2;252;196;148m▀  \033[38;2;252;196;148;48;2;228;172;124m▀\033[38;2;140;100;52;48;2;124;84;44m▀\033[38;2;124;84;44;48;2;164;124;76m▀\033[48;2;116;84;36m▀ \033[48;2;140;108;68m▀\033[38;2;252;228;172;48;2;100;92;68m▀\033[38;2;100;76;44;48;2;0;0;0m▀    \033[38;2;68;44;20;48;2;100;76;36m▀\033[48;2;124;92;44m \033[38;2;116;84;36;48;2;116;84;44m▀\033[0m
\033[38;2;100;76;36;48;2;0;0;0m▀\033[38;2;124;92;44;48;2;100;76;36m▀\033[38;2;116;84;36;48;2;124;92;44m▀\033[38;2;124;84;44;48;2;116;84;36m▀\033[48;2;124;84;44m \033[38;2;116;84;36m▀\033[38;2;148;116;68;48;2;124;92;44m▀\033[38;2;252;196;148;48;2;188;156;108m▀\033[38;2;252;228;172;48;2;252;196;148m▀▀\033[38;2;140;108;68;48;2;252;228;172m▀\033[38;2;156;140;108;48;2;252;244;188m▀\033[48;2;252;228;172m              \033[38;2;252;196;148;48;2;252;236;172m▀\033[48;2;252;228;172m▀\033[48;2;164;124;76m \033[38;2;164;116;68;48;2;252;196;148m▀\033[38;2;228;172;124;48;2;212;188;140m▀\033[38;2;164;124;76;48;2;140;108;68m▀\033[38;2;124;84;44;48;2;124;92;44m▀\033[48;2;68;44;20m \033[48;2;0;0;0m     \033[38;2;0;0;0;48;2;68;44;20m▀\033[38;2;108;76;36;48;2;124;92;44m▀\033[38;2;124;92;44;48;2;116;84;44m▀\033[38;2;68;44;20;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;100;76;36m▀\033[38;2;124;92;44;48;2;100;76;36m▀\033[38;2;116;84;44;48;2;124;92;44m▀\033[48;2;116;84;36m \033[38;2;164;116;68;48;2;124;92;44m▀\033[38;2;228;172;124;48;2;252;196;148m▀ \033[38;2;252;196;148;48;2;252;228;172m▀▀               \033[38;2;252;236;172m▀\033[38;2;236;196;148m▀\033[38;2;252;196;148;48;2;252;244;188m▀\033[38;2;252;236;180;48;2;156;140;108m▀\033[38;2;68;44;20;48;2;0;0;0m▀\033[48;2;68;44;20m \033[38;2;124;92;44;48;2;108;76;36m▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;100;92;68m▀\033[38;2;236;196;148;48;2;252;244;188m▀\033[38;2;188;156;108;48;2;212;188;140m▀\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;68;44;20m▀\033[38;2;100;76;36;48;2;140;100;52m▀\033[38;2;140;100;52;48;2;68;44;20m▀\033[38;2;68;44;20;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m    \033[38;2;100;76;36m▀\033[38;2;116;84;36;48;2;68;44;20m▀\033[48;2;124;92;44m▀\033[38;2;188;156;108m▀\033[38;2;252;236;172;48;2;236;196;148m▀\033[38;2;252;228;172;48;2;252;236;172m▀\033[48;2;252;228;172m       \033[38;2;252;228;172;48;2;252;236;172m▀▀\033[38;2;252;236;172;48;2;252;228;172m▀\033[38;2;252;228;172;48;2;252;196;148m▀\033[48;2;252;228;172m      \033[38;2;252;228;172;48;2;252;236;172m▀\033[48;2;236;196;148m▀\033[38;2;68;44;20;48;2;0;0;0m▀ \033[48;2;100;76;36m \033[38;2;68;44;20;48;2;0;0;0m▀ \033[38;2;148;116;68;48;2;140;108;68m▀\033[48;2;252;244;188m \033[48;2;188;156;108m \033[38;2;0;0;0;48;2;68;44;20m▀\033[38;2;116;84;44m▀\033[38;2;68;44;20;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m      \033[38;2;108;76;36m▀\033[48;2;140;100;52m▀\033[38;2;100;92;68;48;2;68;44;20m▀\033[38;2;252;236;180;48;2;100;92;68m▀\033[38;2;252;228;172;48;2;252;244;188m▀\033[48;2;252;236;180m▀\033[48;2;252;228;172m    \033[38;2;252;196;148m▀▀▀\033[48;2;252;236;172m▀\033[48;2;252;228;172m     \033[38;2;252;228;172;48;2;252;236;172m▀\033[38;2;252;236;172;48;2;252;196;148m▀\033[38;2;252;196;148;48;2;228;172;124m▀\033[38;2;228;172;124;48;2;252;228;172m▀\033[48;2;68;44;20m \033[48;2;0;0;0m \033[48;2;68;44;20m \033[48;2;0;0;0m  \033[48;2;100;92;68m \033[48;2;252;244;188m \033[48;2;188;156;108m \033[48;2;0;0;0m     \033[38;2;0;0;0;48;2;68;44;20m▀\033[0m
\033[48;2;0;0;0m       \033[38;2;68;44;20m▀\033[38;2;124;92;44;48;2;100;76;36m▀\033[38;2;0;0;0;48;2;68;44;20m▀\033[38;2;68;44;20;48;2;0;0;0m▀\033[38;2;212;188;140;48;2;156;140;108m▀\033[38;2;252;236;172;48;2;252;196;148m▀\033[48;2;252;228;172m  \033[38;2;252;228;172;48;2;252;236;172m▀▀\033[48;2;252;228;172m     \033[38;2;252;228;172;48;2;252;236;172m▀\033[48;2;252;228;172m \033[38;2;252;228;172;48;2;236;196;148m▀\033[38;2;252;196;148m▀\033[38;2;228;172;124;48;2;252;228;172m▀ \033[38;2;252;244;188;48;2;252;236;172m▀\033[38;2;140;108;68;48;2;252;228;172m▀\033[38;2;0;0;0;48;2;140;108;68m▀\033[48;2;100;92;68m▀▀▀\033[38;2;68;44;20;48;2;156;140;108m▀\033[38;2;252;244;188;48;2;252;236;172m▀\033[38;2;236;196;148m▀\033[38;2;0;0;0;48;2;140;108;68m▀\033[48;2;100;76;44m▀▀\033[48;2;68;44;20m▀▀\033[38;2;68;44;20;48;2;116;84;36m▀\033[0m
\033[48;2;0;0;0m         \033[38;2;100;76;36;48;2;212;188;140m▀\033[38;2;236;196;148;48;2;252;228;172m▀\033[38;2;252;244;188m▀ \033[38;2;252;196;148m▀\033[48;2;252;236;172m▀\033[48;2;228;172;124m▀\033[48;2;252;36;36m▀\033[38;2;252;236;172;48;2;252;196;148m▀\033[48;2;236;196;148m▀▀▀\033[38;2;252;228;172;48;2;228;172;124m▀\033[38;2;252;196;148;48;2;236;196;148m▀\033[38;2;228;172;124;48;2;252;196;148m▀\033[38;2;236;196;148;48;2;252;228;172m▀    \033[38;2;252;236;172m▀\033[38;2;252;228;172;48;2;236;196;148m▀\033[38;2;252;236;172;48;2;252;228;172m▀\033[38;2;252;236;180;48;2;252;236;172m▀\033[38;2;252;228;172;48;2;236;196;148m▀\033[38;2;252;236;172;48;2;252;196;148m▀\033[38;2;252;228;172;48;2;252;236;172m▀\033[48;2;252;196;148m▀\033[48;2;252;228;172m \033[38;2;252;236;172m▀▀\033[38;2;252;196;148;48;2;236;196;148m▀\033[38;2;140;100;52;48;2;148;116;68m▀\033[48;2;116;84;36m \033[0m
\033[48;2;0;0;0m        \033[38;2;140;108;68;48;2;188;156;108m▀\033[38;2;252;244;188;48;2;252;236;180m▀\033[48;2;252;228;172m    \033[38;2;252;244;188;48;2;252;236;180m▀\033[48;2;228;172;124m \033[48;2;252;28;36m \033[38;2;252;196;148;48;2;252;228;172m▀\033[38;2;236;196;148;48;2;252;236;180m▀\033[38;2;228;172;124;48;2;236;196;148m▀\033[38;2;236;196;148;48;2;252;228;172m▀\033[38;2;252;228;172;48;2;252;236;172m▀\033[48;2;252;228;172m \033[38;2;252;236;172m▀     \033[38;2;252;228;172;48;2;252;236;172m▀\033[48;2;252;196;148m \033[48;2;252;228;172m \033[48;2;252;236;172m \033[38;2;252;196;148;48;2;252;228;172m▀   \033[38;2;252;236;172m▀\033[38;2;252;228;172;48;2;252;196;148m▀\033[48;2;252;228;172m \033[38;2;252;236;172m▀\033[38;2;252;236;180m▀\033[38;2;212;188;140m▀\033[0m
==>02-maid
\033[48;2;0;0;0m           \033[38;2;0;0;0;48;2;38;42;46m▀\033[48;2;22;22;22m▀\033[48;2;0;0;0m         \033[38;2;0;0;0;48;2;22;22;22m▀\033[48;2;30;38;46m▀\033[0m
\033[48;2;0;0;0m         \033[38;2;0;0;0;48;2;38;42;46m▀\033[38;2;38;42;46;48;2;94;94;98m▀\033[38;2;54;62;70m▀\033[38;2;30;38;46;48;2;78;74;74m▀\033[48;2;22;22;22m \033[48;2;0;0;0m      \033[38;2;22;22;22;48;2;54;50;54m▀▀\033[38;2;30;38;46;48;2;78;74;74m▀\033[38;2;54;50;54;48;2;94;94;98m▀\033[38;2;22;22;22;48;2;30;38;46m▀\033[0m
\033[48;2;0;0;0m       \033[38;2;0;0;0;48;2;30;38;46m▀\033[38;2;38;42;46;48;2;98;106;114m▀\033[38;2;94;94;98;48;2;118;114;118m▀\033[38;2;118;114;118;48;2;142;138;138m▀ \033[38;2;98;106;114m▀\033[38;2;54;50;54;48;2;98;106;114m▀\033[38;2;0;0;0;48;2;38;42;46m▀\033[38;2;22;22;22;48;2;0;0;0m▀\033[48;2;30;38;46m▀\033[48;2;0;0;0m   \033[38;2;54;50;54;48;2;78;74;74m▀\033[38;2;54;62;70m▀\033[38;2;94;94;98;48;2;98;106;114m▀\033[38;2;142;138;138;48;2;162;158;162m▀\033[38;2;94;94;98;48;2;142;138;138m▀\033[38;2;30;38;46;48;2;78;74;74m▀\033[38;2;22;22;22;48;2;54;50;54m▀\033[38;2;0;0;0;48;2;22;22;22m▀▀\033[0m
\033[48;2;0;0;0m     \033[38;2;0;0;0;48;2;22;22;22m▀ \033[38;2;54;50;54m▀\033[48;2;54;50;54m \033[38;2;54;50;54;48;2;22;22;22m▀\033[38;2;78;74;74m▀\033[38;2;118;114;118;48;2;30;38;46m▀\033[38;2;178;154;150;48;2;78;74;74m▀\033[38;2;162;158;162;48;2;174;174;178m▀\033[38;2;142;138;138;48;2;254;242;238m▀\033[38;2;22;22;22;48;2;174;174;178m▀\033[38;2;54;50;54;48;2;94;94;98m▀\033[38;2;30;38;46m▀\033[48;2;22;22;22m \033[38;2;22;22;22;48;2;142;138;138m▀\033[38;2;78;74;74;48;2;94;94;98m▀\033[38;2;118;114;118;48;2;178;154;150m▀\033[38;2;98;106;114;48;2;78;74;74m▀\033[38;2;174;174;178m▀\033[38;2;162;158;162;48;2;54;50;54m▀\033[38;2;118;114;118;48;2;78;74;74m▀\033[38;2;78;74;74;48;2;38;42;46m▀\033[48;2;94;94;98m▀\033[38;2;22;22;22;48;2;54;62;70m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;22;22;22;48;2;38;42;46m▀\033[48;2;94;94;98m▀\033[48;2;142;138;138m \033[38;2;118;114;118m▀\033[38;2;30;38;46;48;2;38;62;94m▀\033[38;2;38;62;94;48;2;94;138;178m▀\033[38;2;46;86;142m▀\033[38;2;134;154;174;48;2;74;118;166m▀\033[38;2;174;174;178;48;2;194;194;202m▀\033[38;2;246;218;214;48;2;254;242;238m▀ ▀\033[38;2;202;178;174m▀\033[38;2;118;114;118m▀\033[38;2;202;178;174m▀\033[38;2;246;218;214m▀\033[38;2;94;94;98;48;2;246;218;214m▀\033[38;2;54;62;70;48;2;46;86;142m▀\033[38;2;38;62;94m▀▀▀\033[38;2;54;50;54;48;2;90;114;142m▀\033[38;2;22;22;22;48;2;142;138;138m▀\033[38;2;54;50;54;48;2;0;0;0m▀\033[48;2;22;22;22m \033[0m
\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;22;22;22m▀\033[48;2;0;0;0m   \033[38;2;38;42;46;48;2;30;38;46m▀\033[48;2;142;138;138m \033[38;2;162;158;162;48;2;202;178;174m▀\033[38;2;194;194;202;48;2;246;206;198m▀\033[38;2;142;178;202;48;2;194;194;202m▀\033[38;2;154;226;250;48;2;138;186;222m▀\033[48;2;154;226;250m \033[38;2;130;194;234;48;2;142;178;202m▀\033[38;2;246;218;214;48;2;254;242;238m▀        \033[38;2;90;114;142;48;2;170;198;226m▀\033[38;2;90;170;222;48;2;154;226;250m▀\033[38;2;94;138;178;48;2;174;218;246m▀\033[38;2;90;170;222;48;2;130;194;234m▀\033[38;2;106;138;162;48;2;194;194;202m▀\033[38;2;162;158;162;48;2;202;178;174m▀\033[38;2;78;74;74;48;2;178;154;150m▀\033[48;2;38;42;46m \033[48;2;0;0;0m  \033[38;2;22;22;22;48;2;30;38;46m▀\033[48;2;38;42;46m▀\033[38;2;0;0;0;48;2;22;22;22m▀\033[0m
\033[48;2;0;0;0m \033[38;2;22;22;22;48;2;30;38;46m▀\033[38;2;0;0;0;48;2;22;22;22m▀\033[48;2;0;0;0m  \033[48;2;30;38;46m \033[38;2;142;138;138;48;2;162;158;162m▀\033[48;2;202;178;174m \033[38;2;242;194;182;48;2;246;206;198m▀ \033[38;2;246;206;198;48;2;246;218;214m▀▀▀\033[48;2;254;242;238m        \033[38;2;254;242;238;48;2;246;218;214m▀ \033[38;2;174;174;178;48;2;246;206;198m▀\033[48;2;242;194;182m▀\033[48;2;246;206;198m▀\033[38;2;246;218;214m▀ \033[38;2;142;138;138;48;2;118;114;118m▀\033[38;2;22;22;22;48;2;0;0;0m▀  \033[38;2;38;42;46;48;2;54;62;70m▀\033[38;2;54;50;54m▀\033[48;2;22;22;22m \033[0m
\033[38;2;0;0;0;48;2;22;22;22m▀\033[38;2;30;38;46;48;2;38;42;46m▀\033[48;2;22;22;22m \033[48;2;0;0;0m  \033[38;2;22;22;22m▀\033[38;2;174;174;178;48;2;162;158;162m▀\033[38;2;202;178;174;48;2;194;194;202m▀\033[38;2;246;206;198;48;2;246;218;214m▀▀\033[48;2;246;206;198m  \033[48;2;246;218;214m  \033[48;2;254;242;238m      \033[48;2;246;218;214m  \033[48;2;246;206;198m    \033[38;2;246;206;198;48;2;246;218;214m▀\033[48;2;242;194;182m▀\033[38;2;78;74;74;48;2;30;38;46m▀\033[48;2;0;0;0m   \033[48;2;78;74;74m \033[38;2;54;62;70m▀\033[48;2;30;38;46m \033[0m
\033[48;2;22;22;22m \033[48;2;38;42;46m \033[38;2;30;38;46m▀\033[48;2;0;0;0m   \033[38;2;134;154;174;48;2;106;138;162m▀\033[38;2;194;194;202;48;2;182;210;230m▀\033[38;2;246;218;214;48;2;194;194;202m▀\033[48;2;254;242;238m▀\033[48;2;246;218;214m  \033[38;2;246;218;214;48;2;254;242;238m▀ \033[48;2;246;206;198m▀\033[38;2;246;206;198;48;2;246;186;166m▀\033[38;2;246;218;214m▀▀▀\033[38;2;254;242;238;48;2;242;194;182m▀\033[48;2;254;242;238m \033[38;2;246;218;214m▀\033[48;2;246;218;214m \033[38;2;246;206;198m▀▀\033[38;2;246;218;214;48;2;254;242;238m▀\033[48;2;246;218;214m \033[38;2;174;174;178;48;2;194;194;202m▀\033[38;2;38;42;46;48;2;30;38;46m▀\033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;22;22;22m▀\033[48;2;94;94;98m \033[38;2;78;74;74;48;2;54;62;70m▀\033[48;2;38;42;46m \033[0m
\033[38;2;22;22;22;48;2;0;0;0m▀\033[48;2;38;42;46m  \033[38;2;22;22;22;48;2;54;50;54m▀\033[48;2;0;0;0m \033[48;2;30;38;46m \033[48;2;74;98;118m \033[38;2;170;198;226;48;2;142;178;202m▀\033[38;2;182;210;230;48;2;174;218;246m▀\033[38;2;246;218;214m▀\033[38;2;254;242;238;48;2;182;210;230m▀\033[48;2;246;218;214m▀\033[48;2;254;242;238m   \033[38;2;246;206;198m▀\033[38;2;246;186;166;48;2;246;206;198m▀▀\033[48;2;246;218;214m▀\033[38;2;246;218;214;48;2;254;242;238m▀   \033[38;2;254;242;238;48;2;246;218;214m▀\033[48;2;182;210;230m▀\033[38;2;246;218;214m▀\033[38;2;182;210;230;48;2;174;218;246m▀\033[38;2;142;178;202;48;2;90;114;142m▀\033[48;2;0;0;0m   \033[38;2;54;50;54;48;2;54;62;70m▀\033[38;2;94;94;98;48;2;78;74;74m▀\033[38;2;54;62;70;48;2;54;50;54m▀\033[38;2;30;38;46;48;2;22;22;22m▀\033[0m
\033[48;2;0;0;0m \033[38;2;30;38;46;48;2;22;22;22m▀\033[38;2;38;42;46;48;2;30;38;46m▀\033[38;2;62;78;94;48;2;78;74;74m▀\033[38;2;30;38;46;48;2;54;62;70m▀\033[38;2;22;22;22;48;2;30;38;46m▀\033[38;2;90;114;142;48;2;74;98;118m▀\033[48;2;106;138;162m \033[38;2;170;198;226;48;2;134;154;174m▀\033[48;2;174;218;246m  \033[38;2;182;210;230m▀\033[38;2;194;194;202m▀\033[38;2;254;242;238;48;2;194;194;202m▀\033[48;2;242;194;182m▀\033[48;2;254;242;238m    \033[38;2;254;242;238;48;2;246;206;198m▀\033[48;2;202;150;138m▀\033[38;2;246;218;214;48;2;162;158;162m▀\033[38;2;194;194;202;48;2;174;218;246m▀\033[38;2;170;198;226m▀▀\033[38;2;174;218;246;48;2;182;210;230m▀\033[38;2;182;210;230;48;2;142;178;202m▀\033[48;2;62;78;94m \033[48;2;0;0;0m  \033[48;2;22;22;22m \033[48;2;62;78;94m \033[48;2;54;62;70m \033[38;2;38;42;46;48;2;30;38;46m▀\033[48;2;22;22;22m \033[0m
\033[48;2;0;0;0m \033[38;2;22;22;22m▀\033[38;2;30;38;46;48;2;22;22;22m▀\033[38;2;54;50;54;48;2;30;38;46m▀\033[38;2;54;62;70;48;2;38;42;46m▀\033[38;2;62;78;94;48;2;66;94;114m▀\033[38;2;38;62;94m▀\033[38;2;106;138;162m▀\033[38;2;134;154;174;48;2;106;138;162m▀\033[48;2;142;178;202m \033[38;2;170;198;226;48;2;154;194;226m▀▀\033[48;2;94;94;98m▀\033[38;2;142;178;202;48;2;118;114;118m▀\033[48;2;202;150;138m  \033[38;2;202;178;174m▀▀ \033[38;2;202;150;138;48;2;222;158;146m▀\033[48;2;242;194;182m▀\033[38;2;174;174;178;48;2;202;178;174m▀\033[38;2;154;194;226;48;2;174;174;178m▀\033[38;2;174;218;246;48;2;142;178;202m▀\033[38;2;170;198;226;48;2;138;186;222m▀\033[38;2;142;178;202m▀\033[48;2;106;138;162m▀\033[38;2;38;42;46;48;2;30;38;46m▀\033[48;2;0;0;0m  \033[38;2;30;38;46;48;2;38;42;46m▀\033[38;2;62;78;94;48;2;54;62;70m▀\033[38;2;54;50;54;48;2;38;42;46m▀\033[38;2;30;38;46;48;2;22;22;22m▀\033[0m
\033[48;2;0;0;0m  \033[38;2;22;22;22m▀\033[38;2;30;38;46;48;2;22;22;22m▀\033[48;2;30;38;46m \033[38;2;62;78;94;48;2;38;42;46m▀\033[38;2;74;98;118;48;2;62;78;94m▀\033[38;2;90;114;142;48;2;74;98;118m▀▀\033[38;2;106;138;162;48;2;22;22;22m▀\033[38;2;90;114;142m▀\033[38;2;94;94;98;48;2;0;0;0m▀ \033[38;2;30;38;46m▀\033[38;2;202;150;138;48;2;94;94;98m▀\033[48;2;222;158;146m▀\033[48;2;202;150;138m \033[38;2;202;150;138;48;2;222;158;146m▀\033[38;2;222;158;146;48;2;246;186;166m▀\033[38;2;246;186;166;48;2;242;194;182m▀\033[38;2;246;206;198;48;2;202;178;174m▀\033[38;2;202;178;174;48;2;194;194;202m▀  \033[38;2;142;178;202;48;2;54;62;70m▀\033[48;2;66;94;114m▀\033[48;2;90;114;142m \033[48;2;30;38;46m \033[48;2;0;0;0m \033[38;2;22;22;22;48;2;30;38;46m▀\033[48;2;54;62;70m \033[38;2;38;42;46;48;2;30;38;46m▀\033[48;2;22;22;22m \033[0m
\033[48;2;0;0;0m    \033[38;2;22;22;22m▀\033[48;2;38;42;46m \033[48;2;78;74;74m \033[38;2;94;94;98m▀\033[38;2;38;42;46;48;2;0;0;0m▀ \033[48;2;22;22;22m \033[48;2;0;0;0m    \033[38;2;94;94;98m▀\033[38;2;222;158;146;48;2;118;114;118m▀\033[38;2;246;186;166;48;2;178;154;150m▀\033[38;2;202;178;174;48;2;162;158;162m▀\033[38;2;174;174;178m▀▀▀▀\033[48;2;142;138;138m▀\033[38;2;22;22;22;48;2;0;0;0m▀ \033[38;2;62;78;94;48;2;30;38;46m▀\033[38;2;22;22;22;48;2;0;0;0m▀\033[38;2;0;0;0;48;2;22;22;22m▀\033[48;2;38;42;46m \033[38;2;38;42;46;48;2;22;22;22m▀\033[38;2;22;22;22;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;22;22;22m▀\033[38;2;54;50;54;48;2;22;22;22m▀\033[38;2;22;22;22;48;2;0;0;0m▀  ▀\033[48;2;22;22;22m \033[38;2;0;0;0m▀▀\033[38;2;22;22;22;48;2;30;38;46m▀\033[38;2;54;50;54;48;2;38;42;46m▀\033[48;2;98;106;114m \033[38;2;118;114;118m▀\033[48;2;54;50;54m▀\033[38;2;142;138;138;48;2;54;62;70m▀\033[48;2;118;114;118m▀\033[48;2;98;106;114m▀\033[48;2;118;114;118m▀\033[38;2;94;94;98;48;2;38;42;46m▀\033[48;2;0;0;0m   \033[38;2;22;22;22;48;2;30;38;46m▀\033[38;2;38;42;46;48;2;22;22;22m▀\033[38;2;22;22;22;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m          \033[38;2;22;22;22m▀\033[38;2;0;0;0;48;2;22;22;22m▀\033[38;2;38;42;46m▀\033[48;2;0;0;0m▀ \033[48;2;22;22;22m▀\033[38;2;54;50;54m▀\033[38;2;30;38;46m▀\033[48;2;0;0;0m▀ \033[38;2;54;50;54m▀\033[38;2;94;94;98;48;2;38;42;46m▀\033[48;2;30;38;46m▀\033[48;2;0;0;0m    \033[38;2;22;22;22m▀\033[0m
==>03-kitsune
\033[48;2;0;0;0m         \033[38;2;0;0;0;48;2;9;15;21m▀\033[38;2;9;15;21;48;2;0;0;0m▀▀▀ \033[48;2;39;9;2m▀▀\033[48;2;63;21;15m▀\033[48;2;99;9;2m▀▀▀\033[38;2;0;0;0;48;2;63;21;15m▀\033[38;2;9;15;21;48;2;39;9;2m▀▀\033[48;2;0;0;0m▀\033[38;2;39;27;27;48;2;9;15;21m▀\033[38;2;9;15;21;48;2;0;0;0m▀  \033[38;2;0;0;0;48;2;9;15;21m▀\033[48;2;63;21;15m▀\033[48;2;39;9;2m▀\033[0m
\033[48;2;0;0;0m          \033[38;2;0;0;0;48;2;63;21;15m▀\033[38;2;39;9;2;48;2;99;9;2m▀\033[38;2;63;21;15;48;2;153;15;5m▀\033[38;2;99;9;2m▀         \033[38;2;105;27;15m▀\033[38;2;63;21;15;48;2;105;27;15m▀\033[38;2;0;0;0;48;2;63;21;15m▀\033[48;2;171;63;27m▀\033[38;2;105;27;15;48;2;177;135;21m▀\033[38;2;123;87;33;48;2;171;63;27m▀\033[38;2;171;63;27;48;2;105;27;15m▀\033[48;2;39;9;2m \033[0m
\033[48;2;0;0;0m         \033[48;2;99;9;2m \033[38;2;99;9;2;48;2;153;15;5m▀   \033[38;2;153;15;5;48;2;219;9;4m▀▀        ▀▀\033[38;2;171;63;27;48;2;153;15;5m▀\033[48;2;57;57;39m▀\033[38;2;117;57;33;48;2;195;195;195m▀\033[38;2;105;27;15;48;2;141;105;93m▀\033[48;2;39;9;2m \033[0m
\033[48;2;0;0;0m         \033[38;2;99;9;2;48;2;153;15;5m▀ \033[38;2;153;15;5;48;2;219;9;4m▀        \033[38;2;219;9;4;48;2;243;93;57m▀\033[48;2;219;9;4m  \033[38;2;219;9;4;48;2;213;63;33m▀\033[48;2;171;63;27m▀\033[48;2;153;15;5m▀\033[48;2;219;9;4m  \033[38;2;117;57;33m▀\033[38;2;195;195;195;48;2;117;57;33m▀\033[48;2;57;57;39m \033[0m
\033[48;2;0;0;0m         \033[48;2;153;15;5m \033[48;2;219;9;4m   \033[38;2;219;9;4;48;2;213;63;33m▀\033[48;2;219;9;4m  \033[38;2;219;9;4;48;2;237;129;87m▀\033[48;2;243;93;57m▀\033[48;2;219;9;4m \033[38;2;213;63;33;48;2;243;93;57m▀\033[38;2;237;129;87;48;2;183;93;45m▀\033[38;2;243;93;57;48;2;249;177;135m▀\033[38;2;219;9;4;48;2;243;93;57m▀\033[48;2;219;9;4m \033[38;2;243;93;57m▀\033[38;2;183;93;45;48;2;243;93;57m▀\033[38;2;99;9;2;48;2;225;111;51m▀\033[48;2;153;15;5m \033[38;2;219;9;4m▀\033[38;2;153;15;5;48;2;219;9;4m▀\033[38;2;9;15;21;48;2;39;9;2m▀\033[0m
\033[48;2;0;0;0m         \033[38;2;171;63;27;48;2;183;93;45m▀\033[38;2;225;111;51;48;2;249;177;135m▀\033[48;2;219;9;4m \033[38;2;219;9;4;48;2;243;93;57m▀\033[38;2;249;177;135;48;2;225;111;51m▀\033[38;2;243;93;57m▀\033[38;2;219;9;4;48;2;243;93;57m▀\033[38;2;237;129;87;48;2;225;111;51m▀\033[48;2;153;15;5m▀\033[38;2;243;93;57;48;2;249;177;135m▀\033[38;2;213;63;33;48;2;171;63;27m▀\033[38;2;117;57;33;48;2;153;15;5m▀\033[38;2;225;111;51;48;2;171;63;27m▀\033[38;2;243;93;57m▀\033[38;2;237;129;87;48;2;249;177;135m▀\033[48;2;213;63;33m▀\033[38;2;219;9;4;48;2;237;129;87m▀ \033[38;2;213;63;33;48;2;225;111;51m▀\033[38;2;105;27;15;48;2;153;15;5m▀\033[38;2;219;9;4m▀\033[48;2;63;21;15m \033[0m
\033[48;2;0;0;0m        \033[48;2;9;15;21m \033[38;2;183;93;45;48;2;171;63;27m▀\033[38;2;237;129;87;48;2;213;63;33m▀\033[38;2;243;93;57;48;2;249;177;135m▀\033[48;2;237;129;87m \033[48;2;219;9;4m \033[38;2;213;63;33m▀\033[38;2;249;177;135;48;2;237;129;87m▀\033[38;2;213;63;33;48;2;171;63;27m▀\033[38;2;99;9;2;48;2;117;57;33m▀\033[38;2;213;63;33;48;2;171;63;27m▀\033[48;2;153;15;5m \033[48;2;105;27;15m \033[38;2;153;15;5m▀\033[48;2;99;9;2m▀\033[38;2;237;129;87;48;2;171;63;27m▀\033[38;2;213;63;33m▀\033[38;2;153;15;5;48;2;99;9;2m▀\033[38;2;249;177;135;48;2;171;63;27m▀\033[48;2;237;129;87m \033[38;2;153;15;5;48;2;171;63;27m▀\033[48;2;105;27;15m \033[38;2;63;21;15;48;2;39;9;2m▀\033[0m
\033[48;2;0;0;0m         \033[48;2;153;15;5m \033[38;2;153;15;5;48;2;99;9;2m▀\033[38;2;237;129;87;48;2;171;63;27m▀\033[38;2;225;111;51;48;2;213;63;33m▀\033[48;2;153;15;5m \033[38;2;153;15;5;48;2;105;27;15m▀\033[48;2;213;63;33m \033[48;2;153;15;5m \033[38;2;183;93;45;48;2;189;129;99m▀\033[48;2;183;165;153m▀\033[48;2;105;27;15m \033[38;2;189;129;99;48;2;237;129;87m▀\033[38;2;183;93;45;48;2;189;129;99m▀\033[38;2;171;63;27m▀\033[48;2;153;15;5m  \033[48;2;99;9;2m \033[48;2;105;27;15m \033[38;2;171;63;27;48;2;153;15;5m▀▀\033[38;2;105;27;15m▀\033[48;2;39;9;2m \033[0m
\033[48;2;0;0;0m        \033[38;2;0;0;0;48;2;39;9;2m▀\033[38;2;99;9;2;48;2;141;105;93m▀\033[48;2;117;57;33m \033[38;2;117;57;33;48;2;123;87;33m▀\033[48;2;153;15;5m  \033[38;2;117;57;33;48;2;183;93;45m▀\033[38;2;153;15;5;48;2;105;27;15m▀\033[48;2;117;57;33m▀\033[38;2;189;129;99;48;2;249;207;183m▀\033[38;2;249;207;183;48;2;141;105;93m▀\033[38;2;57;57;39;48;2;135;129;129m▀▀\033[38;2;39;9;2;48;2;105;27;15m▀\033[38;2;57;57;39;48;2;99;9;2m▀\033[38;2;63;21;15;48;2;39;9;2m▀\033[38;2;153;15;5m▀\033[38;2;105;27;15;48;2;99;9;2m▀\033[48;2;153;15;5m \033[38;2;99;9;2;48;2;105;27;15m▀\033[48;2;153;15;5m  \033[38;2;39;9;2;48;2;0;0;0m▀\033[0m
\033[38;2;9;15;21;48;2;0;0;0m▀\033[48;2;9;15;21m  \033[48;2;39;9;2m \033[48;2;99;9;2m \033[38;2;39;9;2;48;2;9;15;21m▀\033[48;2;99;9;2m \033[38;2;99;9;2;48;2;105;27;15m▀\033[38;2;117;57;33;48;2;39;9;2m▀\033[38;2;189;129;99;48;2;87;87;87m▀\033[38;2;39;33;33;48;2;249;231;207m▀\033[38;2;39;9;2;48;2;105;27;15m▀\033[38;2;63;21;15;48;2;87;87;87m▀\033[38;2;99;9;2;48;2;141;105;93m▀\033[38;2;189;129;99;48;2;183;165;153m▀\033[38;2;57;57;39m▀\033[38;2;189;129;99;48;2;249;207;183m▀ \033[38;2;141;105;93;48;2;249;231;207m▀\033[38;2;135;129;129;48;2;141;105;93m▀\033[38;2;249;231;207;48;2;153;15;5m▀\033[38;2;189;129;99;48;2;99;9;2m▀\033[48;2;153;15;5m \033[38;2;99;9;2m▀\033[38;2;0;0;0;48;2;9;15;21m▀\033[38;2;99;9;2;48;2;123;87;33m▀\033[38;2;153;15;5;48;2;171;63;27m▀\033[48;2;153;15;5m \033[38;2;99;9;2;48;2;105;27;15m▀\033[38;2;153;15;5m▀\033[38;2;9;15;21;48;2;39;27;27m▀\033[0m
\033[38;2;0;0;0;48;2;9;15;21m▀ \033[38;2;9;15;21;48;2;39;27;27m▀\033[48;2;9;15;21m \033[38;2;63;21;15;48;2;39;9;2m▀\033[38;2;9;15;21;48;2;0;0;0m▀\033[38;2;63;21;15;48;2;9;15;21m▀\033[38;2;153;15;5;48;2;105;27;15m▀\033[48;2;99;9;2m \033[38;2;141;105;93;48;2;189;129;99m▀\033[38;2;189;129;99;48;2;141;105;93m▀\033[38;2;99;9;2;48;2;153;15;5m▀\033[38;2;141;105;93;48;2;123;87;33m▀\033[48;2;249;231;207m \033[48;2;249;207;183m \033[38;2;249;231;207m▀  \033[48;2;249;231;207m \033[38;2;135;129;129;48;2;195;195;195m▀\033[38;2;171;63;27;48;2;123;87;33m▀\033[38;2;213;63;33;48;2;225;111;51m▀\033[48;2;177;135;21m▀\033[38;2;171;63;27;48;2;141;105;93m▀\033[38;2;87;87;87;48;2;237;129;87m▀\033[38;2;183;93;45;48;2;171;63;27m▀\033[38;2;213;63;33;48;2;225;111;51m▀\033[38;2;153;15;5m▀\033[48;2;141;105;93m \033[38;2;87;87;87;48;2;195;195;195m▀\033[38;2;39;9;2;48;2;39;33;33m▀\033[0m
\033[38;2;9;15;21;48;2;0;0;0m▀\033[48;2;9;15;21m \033[38;2;39;27;27m▀ \033[38;2;9;15;21;48;2;39;27;27m▀\033[38;2;0;0;0;48;2;39;33;33m▀\033[48;2;0;0;0m \033[38;2;9;15;21m▀\033[48;2;39;9;2m \033[38;2;183;165;153;48;2;189;129;99m▀\033[48;2;249;177;135m▀\033[38;2;153;15;5;48;2;123;87;33m▀\033[38;2;141;105;93;48;2;183;165;153m▀\033[48;2;249;231;207m \033[48;2;249;207;183m   \033[38;2;249;207;183;48;2;249;231;207m▀\033[48;2;249;207;183m  \033[38;2;237;129;87;48;2;249;177;135m▀\033[38;2;189;129;99m▀\033[38;2;183;93;45m▀\033[38;2;243;93;57;48;2;237;129;87m▀\033[38;2;225;111;51;48;2;183;93;45m▀\033[48;2;153;15;5m \033[38;2;249;177;135;48;2;237;129;87m▀\033[38;2;189;129;99;48;2;117;57;33m▀\033[38;2;57;57;39;48;2;33;33;39m▀\033[38;2;183;165;153;48;2;141;105;93m▀\033[48;2;87;87;87m \033[0m
\033[48;2;0;0;0m \033[48;2;9;15;21m  \033[48;2;39;33;33m  \033[48;2;39;27;27m  \033[38;2;9;15;21;48;2;57;57;39m▀\033[38;2;63;21;15;48;2;117;57;33m▀\033[38;2;183;93;45m▀\033[38;2;249;177;135;48;2;183;165;153m▀\033[48;2;249;231;207m▀\033[38;2;249;207;183m▀▀\033[48;2;183;165;153m▀\033[48;2;141;105;93m▀\033[48;2;189;129;99m▀\033[38;2;183;165;153;48;2;183;93;45m▀\033[38;2;249;207;183;48;2;183;165;153m▀\033[48;2;249;207;183m  \033[38;2;249;207;183;48;2;249;177;135m▀ \033[38;2;237;129;87;48;2;183;93;45m▀\033[38;2;171;63;27;48;2;153;15;5m▀\033[48;2;219;9;4m▀\033[38;2;213;63;33;48;2;117;57;33m▀\033[38;2;117;57;33;48;2;135;129;129m▀\033[38;2;9;15;21;48;2;0;0;0m▀\033[38;2;87;87;87;48;2;57;57;39m▀▀\033[0m
\033[48;2;0;0;0m \033[48;2;9;15;21m \033[38;2;9;15;21;48;2;39;33;33m▀\033[38;2;39;33;33;48;2;57;57;39m▀\033[48;2;87;87;87m▀▀\033[38;2;0;0;0;48;2;9;15;21m▀\033[38;2;9;15;21;48;2;0;0;0m▀\033[38;2;57;57;39;48;2;63;21;15m▀\033[48;2;189;129;99m \033[38;2;117;57;33;48;2;171;63;27m▀\033[38;2;87;87;87;48;2;0;0;0m▀\033[38;2;183;165;153m▀\033[38;2;249;207;183;48;2;57;57;39m▀\033[38;2;249;231;207;48;2;141;105;93m▀\033[38;2;237;129;87m▀\033[48;2;171;63;27m▀\033[48;2;123;87;33m▀\033[38;2;249;207;183;48;2;141;105;93m▀▀\033[38;2;249;177;135;48;2;183;165;153m▀\033[38;2;189;129;99;48;2;57;57;39m▀\033[38;2;123;87;33;48;2;0;0;0m▀\033[38;2;105;27;15;48;2;171;63;27m▀\033[38;2;219;9;4;48;2;117;57;33m▀\033[38;2;105;27;15;48;2;195;195;195m▀\033[38;2;195;195;195;48;2;183;165;153m▀\033[38;2;87;87;87;48;2;0;0;0m▀ \033[38;2;9;15;21m▀\033[38;2;39;27;27m▀\033[0m
\033[48;2;0;0;0m \033[38;2;9;15;21m▀\033[38;2;39;27;27;48;2;9;15;21m▀\033[38;2;57;57;39m▀▀\033[48;2;33;33;39m \033[38;2;33;33;39;48;2;57;57;39m▀\033[48;2;33;33;39m \033[48;2;63;21;15m \033[38;2;171;63;27;48;2;153;15;5m▀\033[38;2;105;27;15;48;2;39;9;2m▀\033[38;2;9;15;93;48;2;9;15;51m▀\033[48;2;27;33;93m▀\033[38;2;9;15;51;48;2;9;15;93m▀\033[38;2;33;33;39;48;2;57;57;39m▀\033[38;2;195;195;195;48;2;249;231;207m▀\033[48;2;27;33;57m \033[38;2;9;15;21m▀\033[38;2;135;129;129;48;2;87;87;87m▀\033[38;2;141;105;93;48;2;243;219;0m▀\033[38;2;183;165;153;48;2;177;135;21m▀\033[38;2;87;87;87;48;2;201;171;0m▀\033[38;2;99;9;2m▀\033[38;2;63;21;15;48;2;123;87;33m▀\033[38;2;87;87;87;48;2;243;219;0m▀\033[38;2;141;105;93;48;2;201;171;0m▀\033[48;2;57;57;39m \033[0m
\033[48;2;0;0;0m    \033[38;2;39;27;27m▀\033[48;2;39;27;27m \033[38;2;123;87;33;48;2;57;57;39m▀ \033[48;2;63;21;15m \033[38;2;99;9;2;48;2;123;87;33m▀\033[38;2;39;27;27;48;2;177;135;21m▀\033[38;2;39;9;2;48;2;123;87;33m▀\033[48;2;9;15;51m \033[38;2;9;15;93m▀\033[48;2;27;33;93m \033[48;2;249;231;207m \033[38;2;195;195;195m▀▀\033[38;2;123;87;33;48;2;177;135;21m▀\033[48;2;243;219;0m \033[38;2;201;171;0;48;2;123;87;33m▀▀\033[48;2;153;15;5m▀\033[38;2;177;135;21m▀\033[38;2;243;219;0;48;2;177;135;21m▀\033[48;2;201;171;0m▀\033[48;2;57;57;39m \033[0m
\033[48;2;0;0;0m     \033[48;2;39;9;2m \033[38;2;63;21;15m▀▀\033[38;2;57;57;39m▀\033[38;2;123;87;33;48;2;63;21;15m▀\033[38;2;177;135;21;48;2;117;57;33m▀\033[38;2;63;21;15;48;2;0;0;0m▀\033[48;2;9;15;21m \033[48;2;9;15;51m \033[38;2;27;33;57m▀\033[38;2;195;195;195;48;2;87;87;87m▀▀\033[38;2;183;165;153m▀\033[38;2;57;57;39;48;2;135;129;129m▀\033[38;2;105;27;15;48;2;57;57;39m▀\033[38;2;189;129;99;48;2;183;93;45m▀\033[38;2;225;111;51;48;2;171;63;27m▀\033[48;2;213;63;33m \033[48;2;219;9;4m \033[38;2;57;57;39;48;2;63;21;15m▀\033[48;2;33;33;39m▀\033[38;2;39;33;33;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m      \033[38;2;39;9;2m▀\033[48;2;39;9;2m \033[38;2;63;21;15m▀ \033[38;2;9;15;21;48;2;33;33;39m▀\033[38;2;57;57;39;48;2;87;87;87m▀  \033[38;2;27;33;57m▀ \033[38;2;135;129;129m▀\033[48;2;9;15;21m▀\033[38;2;183;165;153;48;2;33;33;39m▀\033[38;2;87;87;87;48;2;57;57;39m▀\033[38;2;171;63;27;48;2;105;27;15m▀\033[38;2;153;15;5m▀\033[38;2;171;63;27;48;2;153;15;5m▀\033[38;2;153;15;5;48;2;105;27;15m▀\033[38;2;105;27;15;48;2;99;9;2m▀\033[48;2;33;33;39m \033[0m
\033[48;2;0;0;0m        \033[38;2;39;9;2m▀▀\033[38;2;33;33;39;48;2;9;15;21m▀\033[38;2;57;57;39;48;2;39;27;27m▀\033[48;2;39;33;33m▀▀\033[38;2;87;87;87m▀\033[48;2;57;57;39m▀\033[48;2;39;33;33m \033[48;2;0;0;0m  \033[38;2;39;27;27m▀\033[38;2;39;33;33;48;2;9;15;21m▀▀\033[38;2;99;9;2;48;2;39;9;2m▀▀\033[48;2;39;27;27m▀ \033[48;2;9;15;21m \033[0m
==>04-chibi
\033[48;2;0;0;0m             \033[38;2;0;0;0;48;2;33;33;39m▀▀▀▀▀▀▀▀▀▀▀\033[0m
\033[48;2;0;0;0m         \033[38;2;0;0;0;48;2;33;33;39m▀\033[48;2;45;51;51m▀\033[38;2;33;33;39m▀\033[48;2;63;63;63m▀\033[38;2;45;51;51;48;2;69;75;75m▀▀\033[38;2;63;63;63;48;2;81;87;87m▀▀\033[48;2;87;93;99m▀▀▀▀\033[48;2;81;87;87m▀\033[38;2;45;51;51m▀\033[48;2;69;75;75m▀\033[38;2;33;33;39;48;2;63;63;63m▀▀\033[48;2;45;51;51m▀\033[38;2;0;0;0;48;2;33;33;39m▀▀\033[0m
\033[48;2;0;0;0m      \033[38;2;0;0;0;48;2;33;33;39m▀\033[38;2;33;33;39;48;2;45;51;51m▀\033[48;2;63;63;63m▀\033[38;2;45;51;51;48;2;69;75;75m▀\033[38;2;63;63;63;48;2;93;93;99m▀\033[38;2;69;75;75;48;2;99;99;99m▀\033[38;2;81;87;87;48;2;117;117;117m▀\033[38;2;99;99;99;48;2;123;129;129m▀▀\033[38;2;105;111;117;48;2;135;141;147m▀▀▀\033[38;2;117;117;117;48;2;135;147;153m▀▀\033[48;2;135;141;147m▀▀\033[38;2;105;111;117m▀\033[38;2;99;99;99;48;2;123;129;129m▀\033[38;2;93;93;99;48;2;117;117;117m▀\033[38;2;81;87;87;48;2;105;111;117m▀\033[38;2;69;75;75;48;2;93;93;99m▀\033[38;2;63;63;63;48;2;81;87;87m▀\033[38;2;45;51;51;48;2;63;63;63m▀\033[38;2;33;33;39;48;2;45;51;51m▀\033[38;2;0;0;0;48;2;33;33;39m▀\033[0m
\033[48;2;0;0;0m    \033[38;2;0;0;0;48;2;33;33;39m▀\033[38;2;33;33;39;48;2;45;51;51m▀\033[38;2;45;51;51;48;2;63;63;63m▀\033[38;2;63;63;63;48;2;81;87;87m▀\033[38;2;81;87;87;48;2;99;99;99m▀\033[38;2;99;99;99;48;2;117;117;117m▀\033[38;2;117;117;117;48;2;135;141;147m▀\033[38;2;123;129;129;48;2;153;153;153m▀\033[38;2;135;141;147;48;2;147;153;159m▀\033[38;2;135;147;153;48;2;165;177;177m▀\033[38;2;147;153;159;48;2;183;183;183m▀\033[38;2;159;165;171;48;2;183;189;189m▀\033[48;2;171;189;195m▀▀▀\033[38;2;165;177;177;48;2;189;201;201m▀\033[38;2;159;165;171;48;2;171;189;195m▀▀\033[48;2;183;189;189m▀\033[38;2;153;153;153;48;2;183;183;183m▀\033[38;2;135;141;147;48;2;159;165;171m▀\033[38;2;123;129;129;48;2;153;153;153m▀\033[38;2;117;117;117;48;2;135;141;147m▀\033[38;2;99;99;99;48;2;123;129;129m▀\033[38;2;81;87;87;48;2;105;111;117m▀\033[38;2;69;75;75;48;2;93;93;99m▀\033[38;2;45;51;51;48;2;69;75;75m▀\033[38;2;33;33;39;48;2;45;51;51m▀\033[38;2;0;0;0;48;2;33;33;39m▀\033[0m
\033[48;2;0;0;0m   \033[48;2;33;33;39m \033[38;2;45;51;51;48;2;63;63;63m▀\033[38;2;63;63;63;48;2;81;87;87m▀\033[38;2;81;87;87;48;2;99;99;99m▀\033[38;2;99;99;99;48;2;123;129;129m▀\033[38;2;123;129;129;48;2;135;141;147m▀\033[38;2;135;141;147;48;2;147;153;159m▀\033[38;2;147;153;159;48;2;165;177;177m▀\033[38;2;159;165;171;48;2;171;189;195m▀\033[38;2;165;177;177;48;2;189;201;201m▀\033[38;2;171;189;195;48;2;201;225;231m▀\033[38;2;201;207;207;48;2;213;237;243m▀\033[38;2;207;213;213;48;2;219;243;243m▀\033[38;2;201;225;231;48;2;231;249;249m▀▀\033[48;2;249;255;255m▀\033[48;2;231;249;249m▀▀▀\033[38;2;207;213;213;48;2;219;243;243m▀\033[38;2;201;207;207;48;2;219;219;219m▀\033[38;2;183;189;189m▀\033[38;2;183;183;183;48;2;201;207;207m▀\033[38;2;159;165;171;48;2;183;189;189m▀\033[38;2;153;153;153;48;2;159;165;171m▀\033[38;2;123;129;129;48;2;153;153;153m▀\033[38;2;105;111;117;48;2;123;129;129m▀\033[38;2;93;93;99;48;2;105;111;117m▀\033[38;2;69;75;75;48;2;93;93;99m▀\033[38;2;45;51;51;48;2;63;63;63m▀\033[38;2;33;33;39;48;2;45;51;51m▀\033[38;2;0;0;0;48;2;33;33;39m▀\033[0m
\033[48;2;0;0;0m \033[38;2;0;0;0;48;2;33;33;39m▀\033[38;2;33;33;39;48;2;45;51;51m▀\033[38;2;45;51;51;48;2;69;75;75m▀ \033[38;2;99;99;99;48;2;81;87;87m▀\033[38;2;117;117;117;48;2;135;141;147m▀\033[38;2;135;141;147;48;2;147;153;159m▀\033[38;2;147;153;159;48;2;165;177;177m▀\033[38;2;165;177;177;48;2;171;189;195m▀\033[38;2;171;189;195;48;2;201;225;231m▀\033[38;2;207;213;213;48;2;213;237;243m▀\033[38;2;201;225;231;48;2;219;243;243m▀\033[38;2;231;249;249;48;2;249;255;255m▀\033[48;2;231;249;249m     \033[38;2;249;255;255m▀▀ \033[38;2;231;249;249;48;2;219;243;243m▀\033[38;2;249;255;255;48;2;231;249;249m▀\033[38;2;231;249;249;48;2;249;255;255m▀\033[38;2;219;219;219m▀\033[38;2;201;207;207;48;2;219;219;219m▀\033[38;2;183;189;189;48;2;201;207;207m▀\033[38;2;165;177;177;48;2;183;189;189m▀\033[38;2;153;153;153;48;2;159;165;171m▀\033[38;2;123;129;129;48;2;135;141;147m▀\033[38;2;111;99;99;48;2;117;117;117m▀\033[38;2;81;87;87;48;2;87;93;99m▀\033[38;2;63;63;63;48;2;45;51;51m▀\033[38;2;0;0;0;48;2;33;33;39m▀▀\033[0m
\033[48;2;0;0;0m \033[48;2;33;33;39m \033[38;2;45;51;51;48;2;63;63;63m▀\033[38;2;81;87;87;48;2;87;93;99m▀\033[38;2;99;99;99;48;2;105;111;117m▀\033[38;2;63;63;63;48;2;123;129;129m▀\033[38;2;135;141;147;48;2;147;153;159m▀\033[38;2;159;165;171;48;2;171;189;195m▀\033[38;2;171;189;195;48;2;207;213;213m▀\033[38;2;207;213;213;48;2;219;243;243m▀\033[38;2;231;249;249;48;2;249;255;255m▀\033[38;2;219;243;243;48;2;231;249;249m▀\033[38;2;231;249;249;48;2;249;255;255m▀      ▀▀\033[48;2;231;249;249m  \033[38;2;219;243;243m▀ \033[38;2;249;255;255m▀\033[38;2;231;249;249;48;2;249;255;255m▀\033[38;2;219;219;219m▀\033[38;2;201;207;207;48;2;207;213;213m▀\033[38;2;165;177;177;48;2;189;201;201m▀\033[38;2;147;153;159;48;2;165;177;177m▀\033[38;2;123;129;129;48;2;153;153;153m▀\033[38;2;99;99;99;48;2;123;129;129m▀\033[38;2;81;87;87;48;2;99;99;99m▀\033[38;2;63;63;63;48;2;69;75;75m▀\033[38;2;33;33;39;48;2;45;51;51m▀\033[38;2;0;0;0;48;2;33;33;39m▀\033[0m
\033[48;2;33;33;39m \033[48;2;45;51;51m \033[38;2;69;75;75;48;2;81;87;87m▀\033[38;2;99;99;99;48;2;105;111;117m▀\033[48;2;123;129;129m \033[38;2;135;147;153;48;2;147;153;159m▀\033[38;2;165;177;177;48;2;183;189;189m▀\033[38;2;201;207;207;48;2;207;213;213m▀\033[38;2;219;219;219;48;2;231;249;249m▀\033[38;2;249;255;255;48;2;219;243;243m▀\033[38;2;231;249;249m▀\033[48;2;249;255;255m           \033[48;2;231;249;249m  \033[48;2;249;255;255m \033[38;2;249;255;255;48;2;231;249;249m▀\033[48;2;105;111;117m▀\033[38;2;183;189;189;48;2;201;207;207m▀\033[38;2;219;219;219;48;2;249;255;255m▀\033[38;2;207;213;213;48;2;219;219;219m▀\033[38;2;183;183;183;48;2;189;201;201m▀\033[38;2;153;153;153;48;2;165;177;177m▀\033[48;2;135;141;147m \033[38;2;105;111;117;48;2;117;117;117m▀\033[38;2;81;87;87;48;2;93;93;99m▀\033[48;2;63;63;63m \033[48;2;33;33;39m \033[0m
\033[48;2;33;33;39m \033[48;2;63;63;63m \033[38;2;81;87;87;48;2;87;93;99m▀\033[48;2;117;117;117m \033[48;2;135;141;147m \033[48;2;165;177;177m \033[48;2;189;201;201m \033[38;2;219;219;219;48;2;201;225;231m▀\033[48;2;219;243;243m \033[38;2;219;243;243;48;2;231;249;249m▀\033[38;2;231;249;249;48;2;219;243;243m▀\033[48;2;249;255;255m           \033[48;2;231;249;249m \033[38;2;231;249;249;48;2;249;255;255m▀\033[38;2;249;255;255;48;2;231;249;249m▀\033[38;2;219;219;219;48;2;249;255;255m▀\033[38;2;183;183;183m▀\033[38;2;249;255;255;48;2;231;249;249m▀ \033[48;2;219;243;243m \033[48;2;201;207;207m \033[38;2;165;177;177;48;2;183;183;183m▀\033[38;2;147;153;159;48;2;153;153;153m▀\033[48;2;123;129;129m \033[38;2;99;99;99;48;2;93;93;99m▀\033[38;2;63;63;63;48;2;69;75;75m▀\033[38;2;33;33;39;48;2;45;51;51m▀\033[38;2;0;0;0;48;2;33;33;39m▀\033[0m
\033[38;2;33;33;39;48;2;0;0;0m▀\033[38;2;69;75;75;48;2;63;63;63m▀\033[38;2;93;93;99;48;2;99;99;99m▀\033[38;2;117;117;117;48;2;123;129;129m▀\033[38;2;135;147;153;48;2;153;153;153m▀\033[38;2;165;177;177;48;2;183;183;183m▀\033[38;2;189;201;201;48;2;201;207;207m▀\033[38;2;213;237;243;48;2;219;243;243m▀\033[38;2;249;255;255;48;2;183;159;159m▀▀\033[48;2;243;207;207m▀\033[38;2;231;249;249;48;2;249;255;255m▀        \033[38;2;249;255;255;48;2;231;249;249m▀\033[48;2;219;243;243m \033[38;2;249;255;255;48;2;231;249;249m▀\033[48;2;249;255;255m \033[48;2;231;249;249m \033[38;2;249;255;255m▀ \033[48;2;219;243;243m▀\033[48;2;231;249;249m▀\033[38;2;231;249;249;48;2;213;237;243m▀\033[38;2;207;213;213;48;2;201;207;207m▀\033[38;2;183;183;183;48;2;165;177;177m▀\033[38;2;153;153;153;48;2;135;147;153m▀\033[48;2;123;129;129m \033[38;2;87;93;99;48;2;99;99;99m▀\033[48;2;69;75;75m \033[48;2;45;51;51m \033[48;2;33;33;39m \033[0m
\033[48;2;0;0;0m \033[38;2;45;51;51;48;2;33;33;39m▀\033[38;2;99;99;99;48;2;93;93;99m▀\033[48;2;123;129;129m \033[48;2;153;153;153m \033[48;2;183;183;183m \033[38;2;207;213;213;48;2;201;207;207m▀\033[48;2;207;213;213m \033[38;2;117;117;117;48;2;177;207;231m▀\033[38;2;123;129;129;48;2;129;219;249m▀\033[38;2;99;99;99;48;2;177;207;231m▀\033[48;2;183;159;159m \033[38;2;249;255;255;48;2;201;207;207m▀\033[48;2;249;255;255m    \033[38;2;249;255;255;48;2;231;249;249m▀\033[48;2;249;255;255m \033[38;2;231;249;249m▀\033[38;2;249;255;255;48;2;243;207;207m▀▀\033[48;2;219;243;243m \033[38;2;249;255;255;48;2;231;249;249m▀\033[48;2;249;255;255m  \033[48;2;231;249;249m \033[48;2;219;243;243m \033[48;2;231;249;249m \033[48;2;213;237;243m \033[38;2;177;207;231;48;2;189;201;201m▀\033[38;2;171;189;195;48;2;165;177;177m▀\033[38;2;129;153;165;48;2;135;147;153m▀\033[48;2;123;129;129m \033[38;2;99;99;99;48;2;87;93;99m▀\033[48;2;69;75;75m \033[48;2;45;51;51m \033[48;2;33;33;39m \033[0m
\033[48;2;33;33;39m  \033[48;2;81;87;87m \033[48;2;117;117;117m \033[38;2;153;153;153;48;2;135;141;147m▀\033[48;2;165;177;177m \033[38;2;189;201;201;48;2;153;153;153m▀\033[38;2;183;159;159;48;2;123;129;129m▀\033[38;2;129;219;249;48;2;189;201;201m▀\033[48;2;177;207;231m▀\033[48;2;171;189;195m▀\033[38;2;249;255;255;48;2;243;207;207m▀\033[38;2;201;207;207;48;2;219;243;243m▀\033[48;2;249;255;255m \033[38;2;231;249;249m▀▀  \033[38;2;249;255;255;48;2;165;177;177m▀\033[38;2;153;153;153;48;2;129;153;165m▀\033[38;2;111;99;99;48;2;219;219;219m▀\033[48;2;201;207;207m▀\033[38;2;201;207;207;48;2;207;213;213m▀\033[38;2;231;249;249;48;2;249;255;255m▀\033[48;2;231;249;249m \033[38;2;249;255;255;48;2;213;237;243m▀\033[48;2;231;249;249m \033[38;2;219;243;243m▀\033[48;2;219;243;243m \033[48;2;201;225;231m \033[48;2;189;201;201m \033[38;2;165;177;177;48;2;159;165;171m▀\033[38;2;135;147;153;48;2;135;141;147m▀\033[48;2;105;111;117m \033[48;2;87;93;99m \033[38;2;69;75;75;48;2;63;63;63m▀\033[38;2;45;51;51;48;2;33;33;39m▀\033[38;2;33;33;39;48;2;0;0;0m▀\033[0m
\033[38;2;33;33;39;48;2;0;0;0m▀\033[48;2;33;33;39m \033[38;2;81;87;87;48;2;69;75;75m▀\033[38;2;111;99;99;48;2;93;93;99m▀\033[48;2;123;129;129m \033[38;2;153;153;153;48;2;81;87;87m▀\033[38;2;63;63;63;48;2;33;33;39m▀\033[38;2;159;165;171;48;2;153;153;153m▀\033[38;2;183;183;183;48;2;219;219;219m▀\033[38;2;243;207;207m▀\033[48;2;249;255;255m▀      \033[48;2;219;243;243m \033[48;2;129;219;249m \033[38;2;129;219;249;48;2;177;207;231m▀\033[48;2;243;207;207m \033[38;2;249;255;255;48;2;201;225;231m▀\033[48;2;249;255;255m  \033[48;2;231;249;249m \033[48;2;219;243;243m \033[38;2;219;243;243;48;2;231;249;249m▀\033[38;2;231;249;249;48;2;219;243;243m▀\033[38;2;213;237;243;48;2;201;225;231m▀\033[38;2;207;213;213;48;2;189;201;201m▀\033[38;2;171;189;195;48;2;165;177;177m▀\033[38;2;159;165;171;48;2;135;147;153m▀\033[48;2;123;129;129m \033[38;2;105;111;117;48;2;93;93;99m▀\033[38;2;81;87;87;48;2;69;75;75m▀\033[38;2;63;63;63;48;2;45;51;51m▀\033[48;2;33;33;39m \033[0m
\033[48;2;0;0;0m \033[48;2;33;33;39m \033[38;2;63;63;63;48;2;45;51;51m▀\033[38;2;93;93;99;48;2;63;63;63m▀\033[38;2;81;87;87;48;2;33;33;39m▀\033[38;2;33;33;39;48;2;105;111;117m▀\033[48;2;45;51;51m▀\033[38;2;93;93;99;48;2;33;33;39m▀\033[38;2;219;219;219;48;2;135;147;153m▀\033[38;2;219;243;243;48;2;219;219;219m▀\033[38;2;249;255;255;48;2;219;243;243m▀\033[48;2;249;255;255m    \033[38;2;249;255;255;48;2;243;207;207m▀\033[48;2;249;255;255m  \033[38;2;243;207;207m▀\033[48;2;231;249;249m▀\033[38;2;183;183;183;48;2;219;219;219m▀\033[38;2;189;201;201;48;2;249;255;255m▀  \033[48;2;231;249;249m \033[48;2;219;243;243m \033[38;2;219;243;243;48;2;213;237;243m▀\033[38;2;213;237;243;48;2;207;213;213m▀\033[38;2;201;207;207;48;2;189;201;201m▀\033[38;2;183;189;189;48;2;165;177;177m▀\033[38;2;159;165;171;48;2;147;153;159m▀\033[38;2;135;141;147;48;2;117;117;117m▀\033[38;2;105;111;117;48;2;93;93;99m▀\033[38;2;81;87;87;48;2;63;81;87m▀\033[38;2;63;63;63;48;2;45;51;51m▀\033[38;2;45;51;51;48;2;33;33;39m▀\033[38;2;33;33;39;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m  \033[48;2;33;33;39m  \033[48;2;69;75;75m \033[38;2;117;117;117;48;2;87;93;99m▀\033[38;2;81;87;87;48;2;105;111;117m▀\033[38;2;33;33;39;48;2;87;93;99m▀\033[38;2;123;129;129;48;2;147;153;159m▀\033[38;2;189;201;201;48;2;159;165;171m▀\033[38;2;207;213;213;48;2;171;189;195m▀\033[38;2;219;243;243;48;2;207;213;213m▀\033[38;2;249;255;255;48;2;219;219;219m▀\033[48;2;231;249;249m▀\033[48;2;249;255;255m     \033[38;2;249;255;255;48;2;159;165;171m▀\033[48;2;207;213;213m▀\033[48;2;249;255;255m  \033[38;2;249;255;255;48;2;213;237;243m▀\033[38;2;219;243;243;48;2;201;225;231m▀\033[38;2;213;237;243;48;2;201;207;207m▀\033[38;2;201;225;231;48;2;189;201;201m▀\033[38;2;189;201;201;48;2;165;177;177m▀\033[38;2;165;177;177;48;2;159;165;171m▀\033[38;2;153;153;153;48;2;135;141;147m▀\033[38;2;123;129;129;48;2;105;111;117m▀\033[38;2;105;111;117;48;2;87;93;99m▀\033[38;2;81;87;87;48;2;69;75;75m▀\033[38;2;63;63;63;48;2;45;51;51m▀\033[38;2;45;51;51;48;2;33;33;39m▀\033[38;2;33;33;39;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m   \033[38;2;33;33;39m▀\033[38;2;45;51;51;48;2;33;33;39m▀\033[38;2;69;75;75;48;2;63;63;63m▀\033[38;2;87;93;99;48;2;69;75;75m▀\033[38;2;117;117;117;48;2;87;93;99m▀\033[38;2;123;129;129;48;2;105;111;117m▀\033[38;2;135;147;153;48;2;123;129;129m▀\033[38;2;159;165;171;48;2;135;147;153m▀\033[38;2;123;129;129;48;2;63;63;63m▀\033[38;2;171;189;195;48;2;45;51;51m▀\033[38;2;219;219;219;48;2;105;111;117m▀\033[48;2;147;153;159m▀\033[38;2;219;243;243;48;2;63;81;87m▀\033[38;2;219;219;219m▀\033[38;2;249;255;255;48;2;165;177;177m▀\033[38;2;123;129;129;48;2;45;51;51m▀\033[38;2;81;87;87;48;2;135;147;153m▀\033[38;2;249;255;255;48;2;231;249;249m▀\033[48;2;165;177;177m▀\033[38;2;219;219;219;48;2;183;189;189m▀\033[38;2;201;207;207;48;2;171;189;195m▀\033[38;2;189;201;201;48;2;165;177;177m▀\033[38;2;171;189;195;48;2;153;153;153m▀\033[38;2;165;177;177;48;2;135;141;147m▀\033[38;2;153;153;153m▀\033[38;2;135;141;147;48;2;117;117;117m▀\033[38;2;105;111;117;48;2;87;93;99m▀\033[38;2;87;93;99;48;2;63;81;87m▀\033[38;2;63;81;87;48;2;63;63;63m▀\033[38;2;63;63;63;48;2;33;33;39m▀ \033[0m
\033[48;2;0;0;0m    \033[38;2;33;33;39m▀\033[48;2;33;33;39m \033[38;2;45;51;51m▀\033[38;2;63;63;63m▀\033[38;2;45;51;51;48;2;0;0;0m▀\033[38;2;69;75;75;48;2;33;33;39m▀\033[38;2;81;87;87m▀\033[38;2;45;51;51m▀  ▀\033[48;2;45;51;51m  \033[38;2;69;75;75m▀\033[38;2;45;51;51;48;2;87;93;99m▀\033[38;2;183;189;189;48;2;165;177;177m▀\033[38;2;123;129;129;48;2;87;93;99m▀\033[38;2;93;93;99;48;2;135;141;147m▀\033[38;2;183;189;189;48;2;153;153;153m▀\033[38;2;159;165;171;48;2;111;99;99m▀\033[38;2;153;153;153;48;2;63;63;63m▀\033[38;2;117;117;117;48;2;63;81;87m▀\033[38;2;87;93;99m▀\033[38;2;105;111;117m▀\033[38;2;87;93;99;48;2;69;75;75m▀\033[38;2;63;81;87;48;2;63;63;63m▀\033[38;2;63;63;63;48;2;45;51;51m▀\033[38;2;45;51;51;48;2;33;33;39m▀\033[38;2;33;33;39;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m        \033[48;2;33;33;39m  \033[38;2;33;33;39;48;2;0;0;0m▀\033[48;2;33;33;39m \033[38;2;33;33;39;48;2;63;63;63m▀\033[48;2;45;51;51m▀\033[48;2;33;33;39m \033[38;2;33;33;39;48;2;93;93;99m▀\033[38;2;45;51;51;48;2;69;75;75m▀\033[48;2;45;51;51m \033[38;2;123;129;129;48;2;117;117;117m▀\033[38;2;135;141;147;48;2;99;99;99m▀\033[38;2;99;99;99;48;2;69;75;75m▀\033[38;2;117;117;117;48;2;63;63;63m▀\033[38;2;81;87;87;48;2;45;51;51m▀\033[38;2;45;51;51;48;2;63;81;87m▀\033[38;2;63;81;87;48;2;81;87;87m▀\033[38;2;87;93;99;48;2;69;75;75m▀\033[38;2;81;87;87;48;2;63;63;63m▀\033[38;2;63;63;63;48;2;45;51;51m▀\033[38;2;45;51;51;48;2;33;33;39m▀\033[38;2;33;33;39;48;2;0;0;0m▀▀\033[0m
==>05-catgirl
\033[48;2;0;0;0m     \033[38;2;0;0;0;48;2;21;21;15m▀▀  ▀\033[48;2;57;45;27m▀▀▀▀\033[48;2;21;21;15m▀▀▀▀▀\033[48;2;57;45;27m▀\033[48;2;105;81;39m▀\033[48;2;111;99;75m▀\033[48;2;87;87;69m▀\033[48;2;57;63;57m▀\033[48;2;21;21;15m▀\033[48;2;57;45;27m▀▀\033[0m
\033[48;2;0;0;0m    \033[48;2;21;21;15m   \033[38;2;57;63;57;48;2;57;45;27m▀\033[48;2;105;45;15m▀\033[38;2;57;45;27;48;2;147;93;27m▀\033[38;2;105;81;39;48;2;105;45;15m▀  \033[38;2;105;45;15;48;2;153;51;9m▀\033[38;2;153;51;9;48;2;171;93;27m▀\033[48;2;189;93;21m▀\033[38;2;171;93;27m▀▀\033[38;2;135;99;69;48;2;153;51;9m▀\033[38;2;153;129;87;48;2;213;99;21m▀\033[38;2;159;177;171;48;2;159;165;141m▀\033[38;2;135;183;195;48;2;105;135;153m▀\033[38;2;129;147;147;48;2;57;63;57m▀\033[38;2;57;87;111;48;2;51;69;81m▀\033[38;2;51;69;81;48;2;57;87;111m▀▀▀\033[0m
\033[48;2;0;0;0m    \033[48;2;21;21;15m \033[48;2;57;63;57m \033[38;2;21;21;15;48;2;57;45;27m▀\033[38;2;57;45;27;48;2;105;81;39m▀\033[48;2;147;93;27m  \033[38;2;105;45;15;48;2;153;51;9m▀\033[38;2;153;51;9;48;2;207;123;39m▀\033[38;2;189;93;21m▀\033[38;2;207;123;39;48;2;213;153;81m▀\033[48;2;189;93;21m▀\033[38;2;225;129;33;48;2;213;99;21m▀\033[48;2;225;129;33m  \033[38;2;225;129;33;48;2;237;159;69m▀\033[38;2;153;51;9;48;2;213;99;21m▀\033[48;2;153;51;9m \033[38;2;105;45;15m▀\033[38;2;153;51;9;48;2;105;45;15m▀\033[38;2;105;81;39;48;2;153;51;9m▀\033[38;2;57;87;111;48;2;123;129;123m▀\033[48;2;105;135;153m▀\033[38;2;51;69;81;48;2;57;63;57m▀\033[0m
\033[48;2;0;0;0m    \033[48;2;21;21;15m \033[38;2;57;63;57;48;2;57;45;27m▀\033[38;2;57;45;27;48;2;105;81;39m▀\033[38;2;147;93;27;48;2;171;93;27m▀ \033[38;2;171;93;27;48;2;189;93;21m▀\033[38;2;207;123;39;48;2;225;129;33m▀\033[48;2;189;93;21m▀\033[48;2;225;129;33m \033[38;2;207;123;39;48;2;189;93;21m▀\033[38;2;213;99;21;48;2;171;93;27m▀\033[38;2;207;123;39;48;2;219;141;81m▀\033[48;2;225;129;33m \033[38;2;225;129;33;48;2;207;123;39m▀\033[38;2;237;159;69;48;2;225;129;33m▀\033[48;2;237;159;69m \033[38;2;213;99;21;48;2;225;129;33m▀\033[38;2;153;51;9m▀\033[48;2;189;93;21m▀\033[48;2;153;51;9m \033[38;2;123;129;123;48;2;123;117;93m▀\033[48;2;57;87;111m \033[48;2;21;21;15m \033[0m
\033[48;2;0;0;0m    \033[38;2;0;0;0;48;2;21;21;15m▀\033[38;2;57;45;27;48;2;57;63;57m▀\033[38;2;147;93;27;48;2;135;99;69m▀\033[38;2;171;93;27;48;2;189;93;21m▀\033[38;2;207;123;39;48;2;213;153;81m▀\033[48;2;207;123;39m \033[38;2;213;99;21;48;2;189;93;21m▀\033[38;2;189;93;21;48;2;207;123;39m▀\033[38;2;207;123;39;48;2;189;129;75m▀\033[38;2;153;51;9;48;2;135;99;69m▀\033[38;2;135;99;69;48;2;153;129;87m▀\033[38;2;219;141;81;48;2;153;51;9m▀\033[38;2;225;129;33;48;2;213;99;21m▀\033[38;2;189;93;21;48;2;189;129;75m▀\033[38;2;213;99;21;48;2;189;93;21m▀\033[38;2;225;129;33;48;2;171;93;27m▀\033[48;2;207;123;39m▀\033[48;2;225;129;33m \033[38;2;171;93;27;48;2;207;123;39m▀\033[38;2;189;93;21;48;2;171;93;27m▀\033[48;2;135;99;69m \033[38;2;51;69;81;48;2;21;21;15m▀\033[0m
\033[48;2;0;0;0m  \033[48;2;21;21;15m \033[38;2;0;0;0m▀\033[48;2;57;45;27m▀\033[38;2;57;63;57;48;2;105;81;39m▀\033[38;2;189;129;75;48;2;171;93;27m▀\033[48;2;189;93;21m \033[38;2;207;123;39;48;2;225;129;33m▀\033[48;2;189;93;21m▀\033[48;2;105;81;39m▀\033[38;2;171;93;27;48;2;21;21;15m▀\033[38;2;153;129;87;48;2;129;147;147m▀▀\033[38;2;189;129;75;48;2;111;99;75m▀\033[38;2;153;51;9;48;2;171;93;27m▀\033[38;2;213;153;81;48;2;213;165;117m▀\033[38;2;213;165;117;48;2;249;213;171m▀\033[38;2;147;93;27;48;2;153;129;87m▀\033[48;2;171;93;27m \033[38;2;171;93;27;48;2;207;123;39m▀ \033[38;2;189;93;21;48;2;153;51;9m▀\033[48;2;207;123;39m▀\033[48;2;87;87;69m \033[0m
\033[48;2;0;0;0m  \033[38;2;0;0;0;48;2;21;21;15m▀\033[48;2;57;45;27m \033[48;2;51;69;81m \033[38;2;105;81;39;48;2;105;45;15m▀\033[48;2;189;93;21m  \033[48;2;225;129;33m \033[38;2;207;123;39;48;2;213;153;81m▀\033[38;2;159;153;123;48;2;213;165;117m▀\033[38;2;57;87;111;48;2;105;135;153m▀\033[38;2;21;21;15;48;2;57;87;111m▀\033[38;2;135;183;195;48;2;159;177;171m▀\033[38;2;213;219;177;48;2;249;213;171m▀\033[38;2;213;165;117m▀ \033[38;2;159;153;123;48;2;105;135;153m▀\033[38;2;123;129;123;48;2;57;87;111m▀\033[38;2;123;117;93;48;2;129;147;147m▀\033[38;2;189;93;21;48;2;171;93;27m▀\033[38;2;207;123;39;48;2;189;93;21m▀\033[38;2;153;51;9m▀\033[38;2;189;129;75;48;2;153;129;87m▀\033[38;2;57;63;57;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m \033[38;2;21;21;15;48;2;57;45;27m▀ \033[38;2;57;45;27;48;2;57;63;57m▀\033[38;2;51;69;81m▀\033[38;2;57;45;27;48;2;105;45;15m▀\033[38;2;189;93;21;48;2;153;51;9m▀\033[38;2;171;93;27m▀\033[48;2;189;93;21m \033[38;2;189;129;75;48;2;171;93;27m▀\033[48;2;213;165;117m \033[38;2;213;165;117;48;2;225;183;141m▀\033[38;2;189;183;147;48;2;249;213;171m▀    \033[38;2;129;147;147m▀\033[38;2;105;135;153m▀\033[38;2;225;183;141;48;2;213;165;117m▀\033[38;2;189;93;21;48;2;153;51;9m▀\033[38;2;171;93;27;48;2;147;93;27m▀\033[38;2;207;123;39;48;2;111;99;75m▀\033[38;2;87;87;69;48;2;21;21;15m▀\033[0m
\033[48;2;21;21;15m \033[48;2;57;45;27m \033[38;2;57;45;27;48;2;21;21;15m▀\033[38;2;21;21;15;48;2;57;45;27m▀\033[38;2;105;81;39;48;2;147;93;27m▀\033[38;2;147;93;27;48;2;105;45;15m▀ \033[38;2;153;51;9m▀\033[48;2;135;99;69m▀\033[38;2;171;93;27;48;2;189;129;75m▀\033[38;2;189;129;75;48;2;153;129;87m▀\033[38;2;213;165;117;48;2;189;129;75m▀\033[48;2;225;183;141m \033[48;2;249;213;171m \033[38;2;213;165;117;48;2;225;183;141m▀\033[38;2;219;141;81m▀\033[48;2;249;213;171m \033[38;2;249;213;171;48;2;225;183;141m▀\033[38;2;225;183;141;48;2;111;99;75m▀\033[38;2;153;129;87;48;2;21;21;15m▀\033[38;2;123;129;123;48;2;57;63;57m▀\033[38;2;57;87;111;48;2;105;135;153m▀\033[48;2;87;87;69m▀\033[0m
\033[38;2;21;21;15;48;2;0;0;0m▀\033[48;2;21;21;15m \033[48;2;57;45;27m \033[38;2;105;81;39m▀\033[48;2;105;45;15m \033[38;2;105;45;15;48;2;153;51;9m▀\033[48;2;105;45;15m \033[38;2;105;45;15;48;2;159;165;141m▀\033[38;2;159;153;123;48;2;213;219;177m▀\033[38;2;189;183;147m▀\033[38;2;213;219;177;48;2;231;249;213m▀\033[38;2;213;225;207;48;2;243;249;231m▀\033[38;2;189;183;147m▀\033[38;2;225;183;141;48;2;189;183;147m▀\033[38;2;249;213;171;48;2;213;99;21m▀\033[38;2;213;165;117m▀\033[38;2;207;123;39;48;2;135;99;69m▀\033[38;2;57;45;27;48;2;21;21;15m▀\033[38;2;0;0;0m▀▀▀\033[38;2;87;87;69;48;2;51;69;81m▀\033[38;2;57;63;57;48;2;21;21;15m▀\033[38;2;0;0;0;48;2;57;63;57m▀\033[48;2;111;99;75m▀\033[48;2;105;81;39m▀▀▀\033[48;2;57;45;27m▀\033[48;2;0;0;0m       \033[38;2;0;0;0;48;2;21;21;15m▀ \033[0m
\033[48;2;0;0;0m \033[48;2;21;21;15m \033[38;2;57;45;27m▀\033[48;2;57;45;27m \033[48;2;105;45;15m \033[38;2;105;45;15;48;2;105;81;39m▀\033[38;2;123;129;123;48;2;159;153;123m▀\033[38;2;159;165;141m▀\033[48;2;189;183;147m \033[48;2;213;219;177m \033[38;2;231;249;213m▀\033[48;2;231;249;213m \033[38;2;243;249;231m▀\033[38;2;231;249;213;48;2;243;249;231m▀\033[38;2;213;153;81;48;2;231;249;213m▀\033[38;2;213;99;21;48;2;213;165;117m▀\033[48;2;213;99;21m \033[38;2;207;123;39m▀\033[38;2;189;129;75;48;2;207;123;39m▀\033[38;2;213;153;81;48;2;219;141;81m▀\033[38;2;213;165;117m▀\033[38;2;189;183;147;48;2;213;165;117m▀\033[48;2;249;213;171m▀\033[38;2;225;183;141;48;2;243;249;231m▀\033[38;2;249;213;171m▀\033[38;2;213;225;207m▀\033[38;2;249;213;171m▀\033[38;2;225;183;141m▀\033[38;2;213;153;81;48;2;213;225;207m▀\033[38;2;111;99;75;48;2;237;159;69m▀\033[38;2;0;0;0;48;2;87;87;69m▀\033[0m
\033[48;2;0;0;0m \033[38;2;21;21;15m▀\033[48;2;21;21;15m \033[48;2;57;45;27m \033[38;2;57;45;27;48;2;57;63;57m▀\033[38;2;111;99;75;48;2;87;87;69m▀\033[38;2;123;129;123;48;2;123;117;93m▀\033[38;2;159;153;123m▀\033[38;2;159;165;141;48;2;159;153;123m▀\033[38;2;189;183;147;48;2;159;165;141m▀\033[38;2;213;219;177;48;2;189;183;147m▀\033[48;2;213;219;177m \033[38;2;231;249;213m▀\033[48;2;231;249;213m \033[38;2;243;249;231m▀▀\033[38;2;225;183;141;48;2;243;249;231m▀\033[38;2;213;99;21;48;2;249;213;171m▀\033[38;2;225;129;33;48;2;225;183;141m▀\033[48;2;213;165;117m▀\033[38;2;213;165;117;48;2;213;99;21m▀\033[38;2;225;183;141;48;2;237;159;69m▀\033[38;2;219;141;81;48;2;243;249;231m▀\033[38;2;207;123;39;48;2;123;129;123m▀\033[38;2;237;159;69;48;2;105;81;39m▀\033[38;2;225;183;141;48;2;171;93;27m▀\033[38;2;213;225;207;48;2;219;141;81m▀\033[38;2;243;249;231;48;2;213;165;117m▀\033[48;2;213;219;177m▀\033[38;2;225;183;141;48;2;213;165;117m▀\033[38;2;171;93;27;48;2;189;129;75m▀\033[48;2;21;21;15m \033[0m
\033[48;2;0;0;0m  \033[38;2;21;21;15m▀\033[38;2;57;45;27;48;2;21;21;15m▀\033[38;2;57;63;57;48;2;57;45;27m▀\033[38;2;87;87;69;48;2;57;63;57m▀\033[48;2;87;87;69m \033[38;2;123;117;93;48;2;111;99;75m▀\033[48;2;123;117;93m \033[38;2;159;153;123;48;2;123;129;123m▀\033[38;2;189;183;147;48;2;159;165;141m▀\033[48;2;189;183;147m \033[38;2;213;219;177m▀\033[48;2;213;219;177m \033[38;2;231;249;213m▀▀\033[38;2;243;249;231;48;2;231;249;213m▀\033[48;2;243;249;231m \033[38;2;231;249;213m▀\033[38;2;243;249;231;48;2;213;225;207m▀\033[38;2;213;219;177m▀\033[38;2;219;141;81m▀\033[38;2;243;249;231;48;2;249;213;171m▀\033[38;2;213;219;177;48;2;231;249;213m▀\033[38;2;57;63;57;48;2;237;159;69m▀\033[48;2;123;117;93m▀\033[38;2;111;99;75;48;2;0;0;0m▀\033[38;2;213;153;81;48;2;21;21;15m▀\033[38;2;213;165;117;48;2;153;129;87m▀\033[38;2;207;123;39;48;2;135;99;69m▀\033[38;2;153;129;87;48;2;87;87;69m▀\033[38;2;21;21;15m▀\033[0m
\033[48;2;0;0;0m   \033[38;2;21;21;15m▀\033[38;2;57;45;27;48;2;21;21;15m▀\033[38;2;57;63;57;48;2;57;45;27m▀\033[48;2;57;63;57m \033[38;2;87;87;69m▀\033[38;2;111;99;75;48;2;105;81;39m▀\033[38;2;123;117;93m▀\033[38;2;159;153;123;48;2;111;99;75m▀\033[48;2;135;99;69m▀\033[38;2;189;183;147;48;2;153;129;87m▀▀\033[38;2;213;219;177;48;2;159;153;123m▀\033[38;2;213;225;207;48;2;189;183;147m▀▀\033[38;2;231;249;213m▀▀\033[38;2;213;219;177m▀\033[38;2;213;225;207;48;2;213;219;177m▀\033[48;2;213;225;207m \033[38;2;249;213;171;48;2;213;219;177m▀\033[48;2;189;183;147m▀\033[38;2;207;123;39;48;2;159;177;171m▀\033[38;2;237;159;69;48;2;171;93;27m▀\033[38;2;123;117;93;48;2;189;129;75m▀\033[38;2;57;45;27;48;2;51;69;81m▀\033[38;2;87;87;69;48;2;0;0;0m▀\033[48;2;21;21;15m▀ \033[38;2;57;63;57m▀\033[38;2;21;21;15;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m     \033[38;2;21;21;15m▀\033[38;2;57;45;27;48;2;21;21;15m▀\033[48;2;57;45;27m \033[38;2;57;63;57m▀\033[38;2;87;87;69;48;2;57;63;57m▀\033[38;2;111;99;75;48;2;105;81;39m▀\033[38;2;123;117;93m▀\033[48;2;111;99;75m▀\033[38;2;159;153;123;48;2;135;99;69m▀\033[48;2;123;117;93m▀\033[48;2;159;153;123m \033[48;2;159;165;141m \033[38;2;159;165;141;48;2;159;177;171m▀\033[48;2;159;165;141m \033[38;2;189;183;147m▀\033[38;2;159;177;171m▀\033[48;2;159;153;123m▀\033[38;2;189;183;147;48;2;153;129;87m▀\033[38;2;123;129;123;48;2;57;63;57m▀\033[38;2;135;183;195;48;2;129;147;147m▀\033[38;2;123;129;123;48;2;105;135;153m▀\033[38;2;171;93;27;48;2;105;81;39m▀\033[38;2;57;63;57;48;2;57;45;27m▀\033[0m
\033[48;2;0;0;0m       \033[38;2;21;21;15m▀\033[48;2;21;21;15m \033[38;2;57;45;27m▀\033[48;2;57;45;27m \033[38;2;57;45;27;48;2;57;63;57m▀\033[38;2;105;81;39m▀\033[48;2;87;87;69m▀\033[38;2;111;99;75m▀\033[38;2;123;117;93;48;2;111;99;75m▀\033[38;2;123;129;123m▀\033[38;2;123;117;93m▀\033[38;2;123;129;123;48;2;123;117;93m▀▀▀\033[38;2;159;153;123m▀\033[38;2;123;117;93;48;2;87;87;69m▀\033[38;2;57;45;27;48;2;21;21;15m▀\033[38;2;123;129;123;48;2;57;87;111m▀\033[48;2;87;87;69m▀\033[38;2;105;81;39;48;2;21;21;15m▀\033[38;2;21;21;15;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m         \033[38;2;21;21;15m▀▀\033[38;2;57;45;27;48;2;21;21;15m▀▀\033[38;2;57;63;57;48;2;57;45;27m▀▀▀\033[38;2;87;87;69m▀▀\033[48;2;57;63;57m▀▀\033[48;2;57;45;27m▀\033[38;2;105;81;39m▀\033[38;2;87;87;69m▀\033[38;2;21;21;15;48;2;0;0;0m▀\033[38;2;57;63;57;48;2;21;21;15m▀ \033[38;2;0;0;0m▀\033[38;2;21;21;15;48;2;0;0;0m▀▀\033[38;2;0;0;0;48;2;21;21;15m▀\033[38;2;21;21;15;48;2;0;0;0m▀\033[0m
\033[48;2;0;0;0m             \033[38;2;21;21;15m▀▀   ▀\033[38;2;57;45;27m▀\033[48;2;21;21;15m▀▀\033[38;2;21;21;15;48;2;0;0;0m▀\033[0m
FOLIO_BANNER_ALL
    awk -v dir="$_bdir" '
        /^==> ?[0-9A-Za-z._-]+$/ { name=substr($0,4); gsub(/^[ \t]+|[ \t]+$/, "", name); file=dir "/" name ".txt"; next }
        { if (file != "") print > file }
    ' "$_bdir/.folio-banners.$$" 2>/dev/null || true
    rm -f "$_bdir/.folio-banners.$$" 2>/dev/null || true
    printf '%s\n' "$_brev" > "$_bdir/.banner-rev" 2>/dev/null || true

    cat > "$_home/.config/folio/banner.sh" <<'FOLIO_BANNER_SH'
# folio-banner.sh — Bliss launch sticker. Truecolor half-block anime art
# (no emoji), offline, random design per interactive shell. Opt out FLUX_NO_BANNER=1.
folio-banner-art() {
  # Prints one randomly picked design from ~/.config/folio/banner/*.txt.
  # The designs carry literal \033 truecolor escapes -> rendered with printf %b.
  setopt localoptions nullglob 2>/dev/null || true
  local -a _bans
  _bans=( "$HOME"/.config/folio/banner/*.txt )
  [[ -f "${_bans[1]}" ]] || return 0
  local _bi=$(( RANDOM % ${#_bans} + 1 ))
  case "$(cat "${_bans[$_bi]}" 2>/dev/null || true)" in
    *'\033'*) printf '%b\n' "$(cat "${_bans[$_bi]}")" ;;
    *)         cat "${_bans[$_bi]}" 2>/dev/null || true ;;
  esac
  unset _bans _bi
}
folio-banner() {
  [ "${FLUX_NO_BANNER:-0}" = "1" ] && return 0
  case $- in *i*) ;; *) return 0;; esac
  folio-banner-art
  # Header: exactly one line, "bliss@<id>" (distro id only — no host,
  # version or time). ID= from /etc/os-release; fallbacks: first word of
  # PRETTY_NAME (lowercased), then "linux".
  local _fid=""
  if [ -r /etc/os-release ]; then
    _fid="$(sed -n 's/^ID=//p' /etc/os-release 2>/dev/null | head -n 1 | tr -d '"' | tr '[:upper:]' '[:lower:]')"
    if [ -z "$_fid" ]; then
      _fid="$(sed -n 's/^PRETTY_NAME=//p' /etc/os-release 2>/dev/null | head -n 1 | tr -d '"' | awk '{print $1}' | tr '[:upper:]' '[:lower:]')"
    fi
  fi
  [ -n "$_fid" ] || _fid="linux"
  printf '%s\n' "bliss@$_fid"
  unset _fid
}
folio-banner
FOLIO_BANNER_SH

    if [ -n "$_owner" ]; then
        chown -R "$_owner" "$_home/.config/folio" 2>/dev/null || true
    fi
    return 0
}

# Random colored anime launch sticker for the guest shell.
_flux_write_folio_banner "$USER_HOME" "$CUSTOM_USER:$CUSTOM_GROUP"
_flux_write_folio_banner /root

# Set zsh as default shell for flux user
chsh -s /bin/zsh "$CUSTOM_USER" 2>/dev/null || true

# Ownership (best-effort; proot cannot always chown)
if [ -f "$USER_HOME/.oh-my-zsh/oh-my-zsh.sh" ]; then
    _flux_chown_r "$CUSTOM_USER:$CUSTOM_GROUP" "$USER_HOME/.oh-my-zsh"
fi
_flux_chown "$CUSTOM_USER:$CUSTOM_GROUP" "$USER_HOME/.zshrc"
_flux_chown_r "$CUSTOM_USER:$CUSTOM_GROUP" "$USER_HOME/.local"

echo "Bliss: Terminal configuration complete!"

# ── Bliss Linux appearance (wallpaper/colors/terminal) ───────────────────────
# Re-apply the app-staged appearance config BEFORE the daemon reload below so
# xfdesktop picks up the wallpaper live. Forced pass: a re-run always wins.
if [ -r "$USER_HOME/.config/folio/appearance-apply.sh" ]; then
    FOLIO_APPLY_HOME="$USER_HOME" FOLIO_APPLY_FORCE=1 \
        sh "$USER_HOME/.config/folio/appearance-apply.sh" >/dev/null 2>&1 || true
fi

# 8. Reload XFCE Daemons — only if a session is actually running (don't hang install)
echo "Bliss: Reloading Desktop (if running)..."
if pgrep -x xfdesktop >/dev/null 2>&1 || pgrep -x xfwm4 >/dev/null 2>&1; then
    su -s /bin/bash - "$CUSTOM_USER" -c "killall -9 xfdesktop xfwm4 xfsettingsd" 2>/dev/null || true
    sleep 1
    su -s /bin/bash - "$CUSTOM_USER" -c "DISPLAY=:0 nohup xfdesktop >/dev/null 2>&1 &" 2>/dev/null || true
    su -s /bin/bash - "$CUSTOM_USER" -c "DISPLAY=:0 nohup xfwm4 --replace >/dev/null 2>&1 &" 2>/dev/null || true
else
    echo "Bliss: No XFCE session running — skip daemon reload"
fi
su -s /bin/bash - "$CUSTOM_USER" -c "DISPLAY=:0 nohup xfsettingsd > /dev/null 2>&1 &" 2>/dev/null
sleep 1

echo "Bliss: Customization Complete!"
echo "------------------------------------------------"
# Interactive pause only in a real terminal — hangs forever under onboarding
# (ProcessBuilder / proot-distro with no stdin TTY).
if [ -t 0 ]; then
    read -r -p "Press Enter to close..."
fi
exit 0
