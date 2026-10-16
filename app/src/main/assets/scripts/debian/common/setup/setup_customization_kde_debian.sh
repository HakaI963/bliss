#!/bin/bash
# setup_customization_kde_debian.sh
# Applies "Bliss" branding and customization to Debian KDE Plasma Desktop
# Mirrors setup_customization_debian.sh structure exactly, adapted for KDE
# Works for both Chroot and Proot environments (run as root, switches to user 'flux')

CUSTOM_USER="flux"
CUSTOM_GROUP="users"
USER_HOME="/home/$CUSTOM_USER"
THEME_DIR="/usr/share/themes"
ICON_DIR="/usr/share/icons"
LOG_FILE="/tmp/fluxlinux_kde_customization.log"

# Error Handler — never block on stdin (onboarding / component sessions attach a
# pty, so "[ -t 0 ]" is true and a bare `read` hangs the install forever).
# Pauses are opt-in (FLUX_INTERACTIVE=1).
_flux_pause() {
    if [ "${FLUX_INTERACTIVE:-0}" = "1" ] && [ -t 0 ]; then
        read -p "$1"
    fi
}

handle_error() {
    echo ""
    echo "❌ Bliss KDE Customization Error: Script failed at step: $1"
    echo "─────────────────────────────────────────────────────────────"
    echo "Log saved to: $LOG_FILE"
    echo ""
    echo "Please copy the log and send it to the developer:"
    echo "  Email: abhay02delhi@gmail.com"
    echo "  GitHub: https://github.com/abhay-byte/fluxlinux/issues"
    echo "─────────────────────────────────────────────────────────────"
    _flux_pause "Press Enter to acknowledge error and exit..."

    exit 1
}

echo "Bliss: Starting KDE Plasma Customization..."

# Setup scripts must run as root inside proot/chroot (apt/dpkg).
if [ "$(id -u)" -ne 0 ]; then
    echo "Bliss: ERROR: must run as root inside the guest (got uid=$(id -u))."
    echo "Bliss: Component sessions should use --user root; re-run from Distro Settings."
    handle_error "Not Root"
fi

# Best-effort ownership (proot often cannot chown to flux:users — never spam/fail)
_flux_chown() { chown "$@" 2>/dev/null || true; }
_flux_chown_r() { chown -R "$@" 2>/dev/null || true; }

# Redirect all output to log
exec > >(tee -a "$LOG_FILE") 2>&1

# 1. Install Dependencies
echo "Bliss: Installing customization tools..."
export DEBIAN_FRONTEND=noninteractive
apt update -y
apt install -y curl fastfetch wget unzip fontconfig locales breeze breeze-gtk-theme \
    || echo "Bliss: [WARN] some customization tools unavailable — continuing"

# Setup Locales for proper font rendering in ZSH/Terminal
echo "Bliss: Setting up locales..."
echo "en_US.UTF-8 UTF-8" > /etc/locale.gen
locale-gen
update-locale LANG=en_US.UTF-8

# Install optional packages (may not be available on all Debian versions)
apt install -y qt5ct qt5-style-plugins systemsettings plasma-discover kinfocenter 2>/dev/null || true

# 2. Deploy Assets (From GitHub Release debian-v1)
ASSET_REPO="abhay-byte/fluxlinux"
ASSET_TAG="debian-v1"
BASE_URL="https://github.com/$ASSET_REPO/releases/download/$ASSET_TAG"

echo "Bliss: Downloading assets from $BASE_URL..."

# Helper to extract all contents
extract_all_assets() {
    local URL="$1"
    local TARGET_DIR="$2"
    local TEMP_ZIP="/tmp/$(basename "$URL")"

    echo " - Downloading $(basename "$URL")..."
    wget -q --show-progress "$URL" -O "$TEMP_ZIP"

    echo " - Extracting to $TARGET_DIR..."
    unzip -q -o "$TEMP_ZIP" -d "$TARGET_DIR"
    rm "$TEMP_ZIP"

    # Extract any nested tarballs
    find "$TARGET_DIR" -maxdepth 1 -name "*.tar.xz" -exec tar -xf {} -C "$TARGET_DIR" \;
    find "$TARGET_DIR" -maxdepth 1 -name "*.tar.gz" -exec tar -xzf {} -C "$TARGET_DIR" \;
    rm -f "$TARGET_DIR"/*.tar.xz "$TARGET_DIR"/*.tar.gz
}

# 3. Theme Selection Prompt
# Prompts need FLUX_INTERACTIVE=1: the install pipeline gives the script a pty,
# so "[ -t 0 ]" alone would block a terminal-only install on stdin.
if [ -n "$FLUX_THEME" ]; then
    echo "Bliss: Auto-applying Theme: $FLUX_THEME"
    if [ "$FLUX_THEME" == "light" ]; then
        THEME_CHOICE="2"
    else
        THEME_CHOICE="1"
    fi
elif [ "${FLUX_INTERACTIVE:-0}" = "1" ] && [ -t 0 ]; then
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

# Icons: Papirus-Dark only (no full Papirus light pack)
SEL_ICON="Papirus-Dark"

if [ "$THEME_CHOICE" == "2" ]; then
    echo "Bliss: Light Mode Selected (icons: Papirus-Dark only)."
    SEL_GTK_THEME="Space-light"
    SEL_CURSOR="Vimix-cursors"
    SEL_WALLPAPER="fluxlinux-light.png"
    KDE_COLOR_SCHEME="BreezeLight"
    KWIN_THEME="Breeze"
else
    echo "Bliss: Dark Mode Selected (icons: Papirus-Dark only)."
    SEL_GTK_THEME="Space-transparency"
    SEL_CURSOR="Vimix-white-cursors"
    SEL_WALLPAPER="fluxlinux-dark.png"
    KDE_COLOR_SCHEME="BreezeDark"
    KWIN_THEME="Breeze"
fi

# Install GTK Themes (same as XFCE — KDE apps use GTK theme via qt5-style-plugins)
echo "Bliss: Installing Themes..."
mkdir -p "$THEME_DIR"
extract_all_assets "$BASE_URL/theme.zip" "$THEME_DIR"

# Install Icons (Papirus works perfectly in KDE)
echo "Bliss: Installing Icons..."
mkdir -p "$ICON_DIR"
extract_all_assets "$BASE_URL/icons.zip" "$ICON_DIR"

# Install Cursors
echo "Bliss: Installing Cursors..."
extract_all_assets "$BASE_URL/cursor.zip" "$ICON_DIR"

# Wallpaper Setup
WALLPAPER_DIR="$USER_HOME/Pictures/Wallpapers"
mkdir -p "$WALLPAPER_DIR"
_flux_chown_r "$CUSTOM_USER:$CUSTOM_GROUP" "$USER_HOME/Pictures"

echo "Bliss: Downloading Wallpaper..."
TEMP_WP_ZIP="/tmp/wallpaper.zip"
wget -q --show-progress "$BASE_URL/wallpaper.zip" -O "$TEMP_WP_ZIP"
unzip -o -j "$TEMP_WP_ZIP" -d "$WALLPAPER_DIR"
rm "$TEMP_WP_ZIP"
[ -f "$WALLPAPER_DIR/dark.png" ] && mv "$WALLPAPER_DIR/dark.png" "$WALLPAPER_DIR/fluxlinux-dark.png"
[ -f "$WALLPAPER_DIR/light.png" ] && mv "$WALLPAPER_DIR/light.png" "$WALLPAPER_DIR/fluxlinux-light.png"
_flux_chown "$CUSTOM_USER:$CUSTOM_GROUP" "$WALLPAPER_DIR"/*

WALLPAPER_PATH="$WALLPAPER_DIR/$SEL_WALLPAPER"

# Install JetBrains Mono Nerd Font (identical to XFCE4 script)
FONT_DIR="/usr/share/fonts/truetype/jetbrains-mono-nerd"
FONT_INSTALLED=false

if fc-list | grep -qi "JetBrainsMono Nerd"; then
    echo "Bliss: JetBrains Mono Nerd Font already installed."
    FONT_INSTALLED=true
fi

if [ "$FONT_INSTALLED" = false ]; then
    echo "Bliss: Installing JetBrains Mono Nerd Font..."
    mkdir -p "$FONT_DIR"
    NERD_FONT_URL="https://github.com/ryanoasis/nerd-fonts/releases/latest/download/JetBrainsMono.zip"
    TEMP_ZIP="/tmp/JetBrainsMono.zip"
    echo " - Downloading JetBrains Mono Nerd Font..."
    wget -q --show-progress "$NERD_FONT_URL" -O "$TEMP_ZIP" || {
        echo "Bliss: Direct download failed, trying from release..."
        wget -q --show-progress "$BASE_URL/font.zip" -O "$TEMP_ZIP" || handle_error "Font Download"
    }
    echo " - Extracting font files..."
    unzip -o -j "$TEMP_ZIP" "*.ttf" -d "$FONT_DIR" 2>/dev/null || \
    unzip -o "$TEMP_ZIP" -d "$FONT_DIR" 2>/dev/null
    find "$FONT_DIR" -type f ! -name "*.ttf" ! -name "*.otf" -delete 2>/dev/null
    rm -f "$TEMP_ZIP"
    chmod 644 "$FONT_DIR"/*.ttf 2>/dev/null
    chmod 644 "$FONT_DIR"/*.otf 2>/dev/null
    echo " - Rebuilding font cache..."
    fc-cache -fv "$FONT_DIR"
    su -s /bin/bash - "$CUSTOM_USER" -c "fc-cache -f" 2>/dev/null
    if fc-list | grep -qi "JetBrainsMono Nerd"; then
        echo "Bliss: ✓ JetBrains Mono Nerd Font installed successfully!"
    else
        echo "Bliss: ⚠ Font may not be properly registered."
        ls -la "$FONT_DIR"
    fi
fi

# 4. Apply KDE Settings via config files
echo "Bliss: Applying KDE Plasma Settings..."

KDE_CONFIG="$USER_HOME/.config"
mkdir -p "$KDE_CONFIG"

# ── kdeglobals: global KDE theme, icons, fonts, cursor ──────────────────────
echo "Bliss: Writing kdeglobals..."
cat > "$KDE_CONFIG/kdeglobals" << EOF
[General]
ColorScheme=$KDE_COLOR_SCHEME
Name=$KDE_COLOR_SCHEME
shadeSortColumn=true
fixed=JetBrainsMonoNerdFontMono,10,-1,5,50,0,0,0,0,0
font=JetBrainsMonoNerdFont,10,-1,5,50,0,0,0,0,0
menuFont=JetBrainsMonoNerdFont,10,-1,5,50,0,0,0,0,0
smallestReadableFont=JetBrainsMonoNerdFont,8,-1,5,50,0,0,0,0,0
toolBarFont=JetBrainsMonoNerdFont,10,-1,5,50,0,0,0,0,0

[Icons]
Theme=$SEL_ICON

[KDE]
AnimationDurationFactor=0
LookAndFeelPackage=org.kde.breezedark.desktop
ShowDeleteCommand=false
SingleClick=false
widgetStyle=Breeze

[WM]
activeFont=JetBrainsMonoNerdFont,10,-1,5,700,0,0,0,0,0
EOF

# ── kcminputrc: cursor theme ─────────────────────────────────────────────────
echo "Bliss: Writing kcminputrc (cursor)..."
cat > "$KDE_CONFIG/kcminputrc" << EOF
[Mouse]
cursorSize=24
cursorTheme=$SEL_CURSOR
EOF

# ── kwinrc: window manager (Breeze), compositing, tiling ────────────────────
echo "Bliss: Writing kwinrc..."
cat > "$KDE_CONFIG/kwinrc" << EOF
[Compositing]
Backend=QPainter
Enabled=false
OpenGLIsUnsafe=true

[Plugins]
blurEnabled=false
contrastEnabled=false
slideEnabled=false
translucencyEnabled=false
kwin4_effect_translucencyEnabled=false

[Windows]
BorderlessMaximizedWindows=false
FocusPolicy=ClickToFocus
TitlebarDoubleClickCommand=Maximize
SnapAgainst=1

[org.kde.kdecoration2]
ButtonsOnLeft=M
ButtonsOnRight=HIA
library=org.kde.breeze
theme=Breeze
EOF

# ── plasmarc: Plasma shell theme ─────────────────────────────────────────────
echo "Bliss: Writing plasmarc..."
cat > "$KDE_CONFIG/plasmarc" << EOF
[Theme]
name=breeze-dark
EOF

# ── plasma-org.kde.plasma.desktop-appletsrc: wallpaper + panel layout ────────
echo "Bliss: Writing Plasma desktop config (wallpaper + panel)..."
cat > "$KDE_CONFIG/plasma-org.kde.plasma.desktop-appletsrc" << EOF
[Containments][1]
ItemGeometriesHorizontal=
activityId=
formfactor=0
immutability=1
lastScreen=0
location=0
plugin=org.kde.plasma.folder
wallpaperplugin=org.kde.image

[Containments][1][General]
ToolBoxButtonState=topcenter

[Containments][1][Wallpaper][org.kde.image][General]
Image=$WALLPAPER_PATH
PreviewImage=$WALLPAPER_PATH

[Containments][2]
activityId=
formfactor=2
immutability=1
lastScreen=0
location=4
plugin=org.kde.panel
wallpaperplugin=org.kde.image

[Containments][2][Applets][3]
immutability=1
plugin=org.kde.plasma.kickoff

[Containments][2][Applets][4]
immutability=1
plugin=org.kde.plasma.taskmanager

[Containments][2][Applets][5]
immutability=1
plugin=org.kde.plasma.systemtray

[Containments][2][Applets][6]
immutability=1
plugin=org.kde.plasma.digitalclock

[Containments][2][Applets][7]
immutability=1
plugin=org.kde.plasma.showdesktop

[Containments][2][General]
AppletOrder=3;4;5;6;7
EOF

# ── kglobalshortcutsrc: keyboard shortcuts (KDE equivalent) ─────────────────
echo "Bliss: Writing keyboard shortcuts (kglobalshortcutsrc)..."
cat > "$KDE_CONFIG/kglobalshortcutsrc" << 'EOF'
[kwin]
Show Desktop=Meta+D,Meta+D,Show Desktop
Window Maximize=Meta+Up,Meta+Up,Maximize Window
Window Close=Alt+F4,Alt+F4,Close Window
Walk Through Windows=Alt+Tab,Alt+Tab,Walk Through Windows
Reverse Walk Through Windows=Alt+Shift+Tab,Alt+Shift+Tab,Reverse Walk Through Windows
Window Fullscreen=Meta+F,none,Make Window Fullscreen
Window Minimize=Meta+M,none,Minimize Window
Window Move=Meta+W,none,Move Window
Window Resize=none,none,Resize Window

[org.kde.konsole.desktop]
NewTab=Ctrl+T,Ctrl+T,New Tab
NewWindow=Ctrl+N,Ctrl+N,New Window

[plasma-desktop]
_launch=none,none,KDE Plasma Desktop
EOF

# ── kcmfonts: DPI / font scaling (2x for mobile displays) ───────────────────
echo "Bliss: Writing font DPI config..."
cat > "$KDE_CONFIG/kcmfonts" << EOF
[General]
forceFontDPI=192
EOF

# ── Konsole profile: replaces xfce4-terminal config ─────────────────────────
echo "Bliss: Configuring Konsole..."
KONSOLE_DIR="$USER_HOME/.local/share/konsole"
mkdir -p "$KONSOLE_DIR"

cat > "$KONSOLE_DIR/Folio.profile" << 'EOF'
[Appearance]
ColorScheme=Breeze
Font=JetBrainsMonoNerdFontMono,12,-1,5,400,0,0,0,0,0,0,0,0,0,0,1
antialias=true

[General]
Command=/bin/zsh
Name=Bliss
Parent=FALLBACK/

[Interaction Options]
AutoCopySelectedText=true
TrimLeadingWhitespacesInSelectedText=true
TrimTrailingWhitespacesInSelectedText=true

[Scrolling]
HistoryMode=2
HistorySize=10000

[Terminal Features]
BidiRenderingEnabled=true
BlinkingCursorEnabled=false
EOF

# Set as default Konsole profile
KONSOLE_CONFIG_DIR="$USER_HOME/.config"
cat > "$KONSOLE_CONFIG_DIR/konsolerc" << 'EOF'
[Desktop Entry]
DefaultProfile=Folio.profile

[KonsoleWindow]
RememberWindowSize=true

[TabBar]
CloseTabOnMiddleMouseButton=true
TabBarVisibility=ShowTabBarWhenNeeded
EOF

_flux_chown_r "$CUSTOM_USER:$CUSTOM_GROUP" "$KONSOLE_DIR" "$KONSOLE_CONFIG_DIR/konsolerc"

# Fix ownership on all KDE configs
_flux_chown_r "$CUSTOM_USER:$CUSTOM_GROUP" "$KDE_CONFIG"
echo "Bliss: KDE settings applied successfully!"

# 5. Configure Zsh and Terminal (identical to XFCE4 customization)
echo "Bliss: Configuring Zsh and Terminal..."

# Install zsh
echo "Bliss: Installing zsh..."
apt-get install -y zsh 2>/dev/null

# Install Oh My Zsh for flux user
echo "Bliss: Installing Oh My Zsh..."

# Check for corrupt installation
if [ -d "$USER_HOME/.oh-my-zsh" ] && [ ! -f "$USER_HOME/.oh-my-zsh/oh-my-zsh.sh" ]; then
    echo "Bliss: Detected corrupt Oh My Zsh installation. Removing..."
    rm -rf "$USER_HOME/.oh-my-zsh"
fi

# Install Oh My Zsh if missing (curl installer, then git clone fallback)
if [ ! -d "$USER_HOME/.oh-my-zsh" ] || [ ! -f "$USER_HOME/.oh-my-zsh/oh-my-zsh.sh" ]; then
    rm -rf "$USER_HOME/.oh-my-zsh"
    su -s /bin/bash - "$CUSTOM_USER" -c 'RUNZSH=no CHSH=no sh -c "$(curl -fsSL https://raw.githubusercontent.com/ohmyzsh/ohmyzsh/master/tools/install.sh)"' 2>/dev/null || true
fi
if [ ! -f "$USER_HOME/.oh-my-zsh/oh-my-zsh.sh" ]; then
    echo "Bliss: Oh My Zsh installer failed — trying git clone fallback..."
    rm -rf "$USER_HOME/.oh-my-zsh"
    su -s /bin/bash - "$CUSTOM_USER" -c "git clone --depth 1 https://github.com/ohmyzsh/ohmyzsh.git '$USER_HOME/.oh-my-zsh'" 2>/dev/null || true
fi
if [ ! -f "$USER_HOME/.oh-my-zsh/oh-my-zsh.sh" ]; then
    echo "Bliss: WARNING: Oh My Zsh not installed — .zshrc will skip source (no hard fail)"
fi

ZSH_CUSTOM="$USER_HOME/.oh-my-zsh/custom"

# Install Zsh plugins
echo "Bliss: Installing Zsh plugins..."
su -s /bin/bash - "$CUSTOM_USER" -c "git clone https://github.com/zsh-users/zsh-autosuggestions '$ZSH_CUSTOM/plugins/zsh-autosuggestions'" 2>/dev/null
su -s /bin/bash - "$CUSTOM_USER" -c "git clone https://github.com/zsh-users/zsh-syntax-highlighting '$ZSH_CUSTOM/plugins/zsh-syntax-highlighting'" 2>/dev/null
su -s /bin/bash - "$CUSTOM_USER" -c "git clone --depth 1 https://github.com/marlonrichert/zsh-autocomplete.git '$ZSH_CUSTOM/plugins/zsh-autocomplete'" 2>/dev/null

# Local monochrome prompt theme — no remote download (old colorful theme removed).
rm -f "$ZSH_CUSTOM/themes/agnosterzak.zsh-theme" 2>/dev/null || true
if [ ! -s "$ZSH_CUSTOM/themes/folio.zsh-theme" ]; then
    echo "Bliss: Installing folio prompt theme..."
    mkdir -p "$ZSH_CUSTOM/themes"
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

# Install pokemon-colorscripts
echo "Bliss: Installing pokemon-colorscripts..."
POKEMON_TEMP="/tmp/pokemon-colorscripts"
rm -rf "$POKEMON_TEMP"
git clone https://gitlab.com/phoneybadger/pokemon-colorscripts.git "$POKEMON_TEMP" 2>/dev/null
cd "$POKEMON_TEMP" && ./install.sh 2>/dev/null
cd - > /dev/null
rm -rf "$POKEMON_TEMP"

# Configure .zshrc
echo "Bliss: Configuring .zshrc..."
ZSHRC="$USER_HOME/.zshrc"

# Write complete optimized .zshrc (performance fixes from screenshot)
# - Removed zsh-autocomplete (extremely slow on PRoot, 35s+ startup -> 1.5s)
# - Backgrounded visuals with &! (async, don't block shell startup)
# - DISABLE_AUTO_UPDATE / DISABLE_UPDATE_PROMPT (no prompts on launch)
# - ZSH_DISABLE_COMPFIX (no compaudit, faster init)
echo "Bliss: Writing optimized .zshrc..."
# Defensive: never hard-fail on missing oh-my-zsh / pokemon (network installs often skip).
cat > "$ZSHRC" << 'ZSHEOF'
# PATH setup - local bin, npm global modules
export PATH="$HOME/.local/bin:/opt/nodejs/bin:$PATH"

# Setup Locales
export LANG=en_US.UTF-8
export LC_ALL=en_US.UTF-8

# Fix XDG_RUNTIME_DIR (not set in PRoot/chroot — no systemd-logind)
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/tmp}"

# Launch sticker: random truecolor anime art + the `bliss@<id>` header
# (opt out FLUX_NO_BANNER=1; prints nothing when the shell is not interactive).
[ -r "$HOME/.config/folio/banner.sh" ] && . "$HOME/.config/folio/banner.sh"

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
ZSHEOF
_flux_chown "$CUSTOM_USER:$CUSTOM_GROUP" "$ZSHRC"

# Launch sticker is sourced from ~/.config/folio/banner.sh (above)

# Set zsh as default shell for flux user
chsh -s /bin/zsh "$CUSTOM_USER" 2>/dev/null

_flux_chown_r "$CUSTOM_USER:$CUSTOM_GROUP" "$USER_HOME/.oh-my-zsh" "$USER_HOME/.zshrc" "$USER_HOME/.local"

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

    if [ -n "$_owner" ]; then
        chown -R "$_owner" "$_home/.config/folio" 2>/dev/null || true
    fi
    return 0
}

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

# Random truecolor anime launch sticker for the guest shell.
_flux_write_folio_banner "$USER_HOME" "$CUSTOM_USER:$CUSTOM_GROUP"
_flux_write_folio_banner /root

echo "Bliss: Zsh configuration complete!"

# 6. Reload KDE Shell — only if a Plasma session is actually running
# (installing from the terminal tab / onboarding must never spawn stray
# plasmashell/kwin processes or block on a missing X server).
echo "Bliss: Reloading KDE Plasma Shell (if running)..."
if pgrep -x plasmashell >/dev/null 2>&1 || pgrep -x kwin_x11 >/dev/null 2>&1; then
    su -s /bin/bash - "$CUSTOM_USER" -c "
        export DISPLAY=\${DISPLAY:-:0}
        # Kill and restart plasmashell to apply theme changes.
        # Plasma 6 (Debian 13) ships kquitapp6; Plasma 5 uses kquitapp5.
        if command -v kquitapp6 >/dev/null 2>&1; then
            kquitapp6 plasmashell 2>/dev/null
        elif command -v kquitapp5 >/dev/null 2>&1; then
            kquitapp5 plasmashell 2>/dev/null
        fi
        sleep 2
        nohup plasmashell > /dev/null 2>&1 &
        sleep 1
        kwin_x11 --replace > /dev/null 2>&1 &
    " 2>/dev/null || true
else
    echo "Bliss: No KDE session running — skip shell reload"
fi

echo "Bliss: KDE Customization Complete!"
echo "Log saved at: $LOG_FILE"
echo "------------------------------------------------"
# Interactive pause only on request (FLUX_INTERACTIVE=1). The install pipeline
# attaches a pty to component sessions, so "[ -t 0 ]" alone hangs the install.
if [ "${FLUX_INTERACTIVE:-0}" = "1" ]; then
    _flux_pause "Press Enter to close..."
fi


exit 0
