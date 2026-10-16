#!/bin/bash
# termux_tweaks.sh
# Enhance Termux with Oh My Zsh, themes, fonts, and fastfetch
# Based on: https://github.com/abhay-byte/Linux_Setup

echo "🎨 Folio: Enhancing Termux Environment..."

install_ohmyzsh() {
    echo "🐚 Installing Oh My Zsh..."
    RUNZSH=no CHSH=no sh -c "$(curl -fsSL https://raw.githubusercontent.com/ohmyzsh/ohmyzsh/master/tools/install.sh)"
    export ZSH_CUSTOM="${ZSH_CUSTOM:-$HOME/.oh-my-zsh/custom}"

    echo "🔌 Installing Zsh plugins..."
    git clone https://github.com/zsh-users/zsh-autosuggestions "$ZSH_CUSTOM/plugins/zsh-autosuggestions"
    git clone https://github.com/zsh-users/zsh-syntax-highlighting "$ZSH_CUSTOM/plugins/zsh-syntax-highlighting"
    git clone --depth 1 https://github.com/marlonrichert/zsh-autocomplete.git "$ZSH_CUSTOM/plugins/zsh-autocomplete"

    echo "⚙️ Configuring .zshrc..."
    # Monochrome Folio prompt theme (local; replaces the old colorful default).
    mkdir -p "$ZSH_CUSTOM/themes"
    rm -f "$ZSH_CUSTOM/themes/agnosterzak.zsh-theme" 2>/dev/null || true
    if [ ! -s "$ZSH_CUSTOM/themes/folio.zsh-theme" ]; then
        cat > "$ZSH_CUSTOM/themes/folio.zsh-theme" <<'FOLIO_THEME_EOF'
# folio.zsh-theme — Folio's minimal monochrome prompt.
# White/gray on black only; ASCII-safe (no powerline glyphs, no ANSI colors 31-44).
# Segments:  time  folio  working-dir  input-char
PROMPT='%F{240}%D{%H:%M}%f %F{255}folio%f %F{250}%~%f %F{255}%#%f '
PROMPT2='%F{240}> %f'
FOLIO_THEME_EOF
    fi
    if grep -q "plugins=" "$HOME/.zshrc" 2>/dev/null; then
        sed -i 's/plugins=(.*)/plugins=(git zsh-autosuggestions zsh-syntax-highlighting)/' "$HOME/.zshrc"
    else
        echo 'plugins=(git zsh-autosuggestions zsh-syntax-highlighting)' >> "$HOME/.zshrc"
    fi
    sed -i 's/^ZSH_THEME=.*$/ZSH_THEME="folio"/' "$HOME/.zshrc"
    echo "✅ Zsh configuration complete."
}

set_termux_colors() {
    echo "🎨 Choose a Termux color scheme:"
    echo "1) GitHub Dark (default)"
    echo "2) Dracula"
    echo "3) Gruvbox Dark"
    printf "Enter choice [1-3]: "
    read choice

    mkdir -p ~/.termux

    case "$choice" in
        2)
            echo "🎨 Applying Dracula color scheme..."
            cat > ~/.termux/colors.properties << 'EOF'
foreground=#f8f8f2
background=#282a36
cursor=#f8f8f2
color0=#21222c
color1=#ff5555
color2=#50fa7b
color3=#f1fa8c
color4=#bd93f9
color5=#ff79c6
color6=#8be9fd
color7=#f8f8f2
color8=#6272a4
color9=#ff6e6e
color10=#69ff94
color11=#ffffa5
color12=#d6acff
color13=#ff92df
color14=#a4ffff
color15=#ffffff
EOF
            ;;
        3)
            echo "🎨 Applying Gruvbox Dark color scheme..."
            cat > ~/.termux/colors.properties << 'EOF'
foreground=#ebdbb2
background=#282828
cursor=#ebdbb2
color0=#282828
color1=#cc241d
color2=#98971a
color3=#d79921
color4=#458588
color5=#b16286
color6=#689d6a
color7=#a89984
color8=#928374
color9=#fb4934
color10=#b8bb26
color11=#fabd2f
color12=#83a598
color13=#d3869b
color14=#8ec07c
color15=#ebdbb2
EOF
            ;;
        *)
            echo "🎨 Applying GitHub Dark color scheme..."
            cat > ~/.termux/colors.properties << 'EOF'
foreground=#c9d1d9
background=#0d1117
cursor=#c9d1d9
color0=#484f58
color1=#ff7b72
color2=#3fb950
color3=#d29922
color4=#58a6ff
color5=#bc8cff
color6=#39c5cf
color7=#b1bac4
color8=#6e7681
color9=#ffa198
color10=#56d364
color11=#e3b341
color12=#79c0ff
color13=#d2a8ff
color14=#56d4dd
color15=#f0f6fc
EOF
            ;;
    esac
    echo "✅ Color scheme applied."
}

install_nerd_font() {
    echo "🔤 Choose a Nerd Font for Termux:"
    echo "1) Meslo (default)"
    echo "2) FiraCode"
    echo "3) JetBrainsMono"
    printf "Enter choice [1-3]: "
    read font_choice

    mkdir -p ~/.termux
    TMPFONT="$HOME/tmpfont"
    mkdir -p "$TMPFONT" && cd "$TMPFONT" || exit 1

    case "$font_choice" in
        2)
            echo "🔤 Installing FiraCode Nerd Font..."
            curl -fsSL -o font.zip https://github.com/ryanoasis/nerd-fonts/releases/latest/download/FiraCode.zip
            ;;
        3)
            echo "🔤 Installing JetBrainsMono Nerd Font..."
            curl -fsSL -o font.zip https://github.com/ryanoasis/nerd-fonts/releases/latest/download/JetBrainsMono.zip
            ;;
        *)
            echo "🔤 Installing Meslo Nerd Font (default)..."
            curl -fsSL -o font.zip https://github.com/ryanoasis/nerd-fonts/releases/latest/download/Meslo.zip
            ;;
    esac

    unzip -jo font.zip "*Regular.ttf" -d ~/.termux
    FONTFILE=$(ls ~/.termux/*Regular.ttf | head -n 1)
    if [ -n "$FONTFILE" ]; then
        mv -f "$FONTFILE" ~/.termux/font.ttf
        echo "✅ Font installed as Termux default."
    else
        echo "❌ No Regular font found!"
    fi

    cd ~ && rm -rf "$TMPFONT"
    termux-reload-settings
}

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

configure_fastfetch() {
    echo > "$PREFIX/etc/motd"
    rm -f "$PREFIX/etc/motd"

    echo "⚡ Configuring fastfetch..."
    # Monochrome Folio preset written locally (no remote colorful download).
    mkdir -p ~/.local/share/fastfetch/presets
    cat > ~/.local/share/fastfetch/presets/termux.jsonc <<'FFEOF'
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

    # Random monochrome anime launch sticker (replaces the plain fastfetch line).
    _flux_write_folio_banner "$HOME"

    RCFILE="$HOME/.zshrc"
    [ -f "$RCFILE" ] || touch "$RCFILE"

    # Drop the plain auto-print fastfetch line from older runs; the banner runs
    # fastfetch itself (info block) after printing the sticker.
    if grep -q 'fastfetch --config' "$RCFILE" 2>/dev/null; then
        grep -v '^fastfetch --config' "$RCFILE" > "${RCFILE}.folio.$$" 2>/dev/null \
            && mv -f "${RCFILE}.folio.$$" "$RCFILE" 2>/dev/null || rm -f "${RCFILE}.folio.$$"
    fi
    if ! grep -q 'folio/banner.sh' "$RCFILE" 2>/dev/null; then
        {
            printf '\n# Folio launch sticker (random monochrome anime banner; opt out FLUX_NO_BANNER=1)\n'
            printf '[ -r "$HOME/.config/folio/banner.sh" ] && . "$HOME/.config/folio/banner.sh"\n'
        } >> "$RCFILE"
    fi

    echo "✅ Fastfetch configured."
    echo "🐚 Setting Zsh as default shell..."
    chsh -s zsh
}

# ── Folio Linux appearance (host Termux side) ────────────────────────────────
# Applies the terminal colors from ~/.config/folio/appearance.env when the app
# staged one for the host bootstrap. No-op otherwise; never fatal.
apply_host_appearance() {
    ENV="$HOME/.config/folio/appearance.env"
    [ -r "$ENV" ] || return 0
    FG=$(. "$ENV" >/dev/null 2>&1 || true; printf '%s' "${FLUX_TERM_FG:-#F4F4F5}")
    BG=$(. "$ENV" >/dev/null 2>&1 || true; printf '%s' "${FLUX_TERM_BG:-#09090B}")
    mkdir -p "$HOME/.termux" 2>/dev/null || true
    {
        printf 'foreground=%s\n' "$FG"
        printf 'background=%s\n' "$BG"
        printf 'cursor=%s\n' "$FG"
    } > "$HOME/.termux/colors.properties" 2>/dev/null || true
    if command -v termux-reload-settings >/dev/null 2>&1; then
        termux-reload-settings >/dev/null 2>&1 || true
    fi
    unset ENV FG BG
    return 0
}

# Main execution
install_ohmyzsh
set_termux_colors
install_nerd_font
configure_fastfetch
apply_host_appearance

echo ""
echo "🎉 Folio: Termux enhancement complete!"
echo "🔄 Restart Termux to see all changes."

# Create marker file to track tweaks application
touch "$HOME/.fluxlinux/termux_tweaks.done"
am start -a android.intent.action.VIEW -d "fluxlinux://callback?result=success&name=termux_tweaks"
