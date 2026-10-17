# Bliss — Project Instructions, Status & Next Steps

Updated: 2026-10-16 · Repo: `HakaI963/folio` (branch `main`) · Local working copy: `/emulated/folio` (no git) · Git clone: `/tmp/opencode/folio-clone` · Upstream reference clone: `/tmp/opencode/fluxlinux-src`

## 0. TL;DR

Bliss is a rebranded, standalone Android build of the FluxLinux app (app id `com.nopiece.bliss`, source package `com.ivarna.fluxlinux`, brand **"Bliss"**). It boots Linux distros on-device via a bundled Termux-derived host bootstrap, proot containers, chroot distros, and termux-x11 GUI sessions.

The host-boot failure (commit `10ef127`, release `folio-debug-10ef127`) is shipped. The **UI/UX + feature overhaul** (§4) shipped as release `folio-debug-33c97e9`; the **second user-feedback round** (§4b) shipped as `folio-debug-77e2c0a`; the **third** (§4c) as `folio-debug-7b4b67a`; the **fourth** (§4d) as `folio-debug-5791429`. The **fifth round** (§4e, three screenshots; §2.10) — host-shell **Permission denied fixed** (repack patches every baked app-id string in binaries + RUNPATH; host shell launches with its own generated rc), **installs made much faster** (6-connection parallel rootfs download, guest fast-mirror racing, dnf parallel downloads, apt retry tuning), **real anime banner art** (truecolor half-block, 5 free-licensed characters, header `bliss@<distro>`), **renamed to Bliss** (app id `com.nopiece.bliss`, new lotus icon, version 3.0.0), and the **distro list overhauled** (Rocky Linux 9 + CentOS Stream 9 added for arm64 proot; Adélie/Artix/BackBox/openKylin/Gentoo removed — no usable arm64 rootfs / source-based; Retro Emulation component enabled) — shipped as release **`bliss-debug-46bc7d8`** (CI run `38936982387`, first-try green after two repack-patcher fixes). See §2.10. **Round 7** (§2.12, §4g): the launch sticker now appears on **every** distro and **exactly once** (chroot login dedupe + once-per-shell guard + bash wiring), a latent **~128 KB su-argument cap** that silently broke chroot repairs was fixed with chunked staging (`ChrootStager`), the art got a **quality slider** with a smaller default (Pixelated 24 → Max 54 cols), and Settings → Terminal gained an optional **NSFW banner art** mode (off by default, 18+ warning, 4 levels, runtime API fetch + on-device half-block conversion) — released as **`bliss-debug-8c9d7ab`** (run `39037758339`, 6m44s). See §2.11 for round 6. **Round 6** (§2.11, §4f): HD re-render of the terminal banner (49 truecolor variants, 54×28 cells, black-blended characters), a new **dark minimal sparkle icon**, a **penguin-free Welcome screen**, and a **complete file-name rename** of every remaining `foila`/`folio`/`flux` file/asset name to `bliss` (scripts, wallpapers, presets, drawables, guest theme/config/marker/chroot paths, Konsole profile, prefs file) with automatic migrations for existing installs — shipped as **`bliss-debug-1ace0d8`** (run `39024115857`, first-try green).

Quick facts:

| Item | Value |
|---|---|
| App id / brand | `com.nopiece.bliss` / **"Bliss"** (renamed from "Foila Linux" in round 5; functional ids keep `folio`/`flux` internals) |
| Source package | `com.ivarna.fluxlinux` |
| Branch | `main` |
| Latest green CI | run `39043883518` (commit `63b57ee`, hotfix 3) |
| Released APK | [`bliss-debug-63b57ee`](https://github.com/HakaI963/folio/releases/tag/bliss-debug-63b57ee) `app-bliss-debug.apk` (172,847,722 B) — supersedes `bliss-debug-8c9d7ab` |
| APK sha256 | `5910249307ef4ea3b9b005b5c65eda5af4b90e26415b9d09177bf9696806c8cd` |
| Verify APK proof | package `com.nopiece.bliss`, `targetSdkVersion 28`, `assets/bootstrap.tar` packaged, libbash/libproot/libloader*.so packaged |
| Signing | debug only (per-run keystore — uninstall before re-install) |
| CI gate | `.github/workflows/build.yml` compiles **main sources only** (`assembleBlissDebug` + bootstrap assembly + Verify APK). Unit tests are not part of the gate. |

---

## 1. What Bliss is

Folio installs Linux distributions on Android using:

- **Host bootstrap** — Termux-derived, bundled in assets (`native/bootstrap/com.nopiece.bliss`, repacked ~130 MB tar, prefix rewritten to `com.nopiece.bliss`). Extracted into the app data dir; scripts then run under it.
- **proot containers** — `usr/var/lib/proot-distro` inside the app prefix. No root required.
- **chroot distros** — created under `/data/local/bliss/chroots/<distro>` (moved from `/data/local/tmp` so Folio and the other FluxLinux app coexist — §4 item 11). Require root.
- **termux-x11** — GUI sessions; XFCE4 and KDE Plasma 6 both installable and launchable (§4 item 2).

---

## 2. Completed history

### 2.1 Host bootstrap "Initialize Host" fix — commit `10ef127` (on main)

- **Root cause:** `targetSdk >= 29` triggers Android 10+ SELinux `W^X` policy on `app_data_file` — `execve()` of anything under `/data/data/<pkg>/` is denied regardless of file mode. Proof: on-device `exit=126` (EACCES) persisted after `chmod -R 755`.
- **Fixes shipped:**
  - `targetSdk = 28` for all flavors in `app/build.gradle.kts` (+ explanatory comment), `android:extractNativeLibs="true"`.
  - `android:requestLegacyExternalStorage="true"`; `SharedStorageAccess.granted()` accepts legacy external-storage mode.
  - `BootstrapInstaller.EXTRACT_VERSION = 3` → one-time re-extract + repair of previously broken trees; proot containers preserved.
  - Extractor applies **real** tar modes via `android.system.Os.chmod` (`applyTarMode`) and a repair walk `repairHostExecPermissions`.
  - Prefix rewrite to `com.nopiece.bliss` happens **before** `setup_termux.sh` runs.
  - `assets/scripts/host/setup_termux.sh` reverted **byte-identical to upstream** (still true after this overhaul — verified by diff).
  - bash/proot/loader/pulse stay pinned to `nativeLibraryDir` (jniLibs).

### 2.2 Rebrand to Folio

App id, bootstrap prefix, jniLibs and UI strings migrated from Flux/FluxLinux → Folio. Functional identifiers intentionally kept: guest user `flux`, `FLUX_*` env vars, `/etc/fluxlinux` paths, `fluxlinux://callback` scheme, `Theme.FluxLinux` legacy style alias, `.FluxApp` class, `com.termux.x11` references, `FluxTerminalSessionManager` (internal class), notification-channel **ids**. All user-visible strings now read "Folio" (see §4 item 10).

### 2.3 Wave 1 — recon (10 parallel agents)

Areas mapped: onboarding buttons · nav/tab-switch crash · nav overlap · terminal extra-keys + banner · install pipeline · KDE backend · Flux string inventory · chroot/tmp path inventory · home screen · theme system.

### 2.4 Wave 2 — theme system + UI polish (working tree)

- **NEW** `ui/components/FolioBackground.kt` — `FolioAnimatedBackground`: pure-black canvas + 3 subtle slow-drifting white pools (alpha 0.035–0.055), no pointer input.
- `ui/theme/Color.kt` — `FrostTopSheen` added; `FolioFrostBorder` softened; deprecated `Flux*` names kept as aliases of Folio tokens.
- `ui/theme/Theme.kt` — monochrome scheme mapping (dark + light).
- `ui/components/Frost.kt` — 0.75dp hairlines, top sheen, pill edge polish (public signatures unchanged).
- `ui/onboarding/OnboardingFlowScreen.kt` — secondary-button hierarchy (Consent/HostSetup/DistroPick/Options/Storage/Done), HostSetup "Initialize Host" frost card.
- `ui/screens/HomeScreen.kt` — XFCE/KDE launch pills on installed envs; monochrome polish.
- `ui/install/InstallProgressPanel.kt` — rewritten progress UI (primary/secondary buttons).

### 2.5 Wave 2.5 — agent workstreams (working tree)

- **Terminal UI** — `ui/terminal/TerminalExtraKeys.kt` redesigned (organized grid, frost keys, no tofu glyphs); `ui/screens/TerminalScreen.kt` polished (frost tokens, inset handling). Signatures kept (`embeddedInBottomNav`).
- **Install components** — `ui/screens/InstallConfigScreen.kt` desktop picker (Terminal only / XFCE4 / KDE when available) + theme row + HW-accel switch; **NEW** `core/install/DesktopInstallSelection.kt` (`DesktopChoice`, `InstallExtra`, component resolution); `OnboardingInstallRunner.start(distroId, theme, desktop, extras, onProgress)`; env contract `FLUX_DESKTOP=xfce|kde|none` + `FLUX_DESKTOP_ENV` mirror threaded into proot env / component payloads / chroot rootCmd.
- **Paths** — **NEW SSOT** `core/root/ChrootPaths.kt`: `/data/local/bliss/{chroots/<distro>, helpers, staging, busybox}`; helper `fluxlinux_chroot.sh` v2.10 (`CHROOT_HELPER_VERSION = "fluxlinux-chroot v2.10"`); 25 scripts + 6 Kotlin files migrated off `/data/local/tmp`; `TermuxIntentFactory` uses our own prefix tmp. Old `/data/local/tmp/chroot*` trees are untouched (never read/modified/deleted); `GuestStorageCatalog` denylist still protects them.
- **KDE backend** — real breaks found and fixed (see §4 item 2).

### 2.6 Wave 3 — scripts sweep + banner + desktop gating (working tree)

- **Banner** — the terminal banner was a remote **colorful** fastfetch preset + the `agnosterzak` oh-my-zsh theme. Replaced in **6 writers** (`common/setup/setup_customization_xfce.sh`, `debian/…/setup_customization_debian.sh`, `alpine/…/setup_customization_alpine.sh`, `debian/…/setup_customization_kde_debian.sh`, `chroot/fluxlinux_chroot.sh`, `termux/termux_tweaks.sh`) by:
  - a local monochrome `bliss.zsh-theme` (`ZSH_THEME="bliss"`, grays 240/250/255, ASCII), and
  - a locally-written `_flux_write_fastfetch_preset()` heredoc (`logo: null`, no colors).
  No remote downloads; existing installs get the theme repaired in place (`fluxlinux_chroot.sh` sed).
- **Brand sweep** — 780 `FluxLinux`→`Folio` replacements in 79 script files. Residuals: 14 lines in the never-touch `host/setup_termux.sh`, 5 functional markers (`FluxLinux proot` bwrap-shim greps).
- **`FLUX_DESKTOP` gating** — all 12 distro-family scripts + 4 chroot setup scripts normalize `xfce|xfce4|kde|none` (unset → xfce) and gate only desktop-specific packages; shared base stack stays. Chroot launchers select `startxfce4`/`startplasma-x11` at generation time.
- **Nav/final integration (orchestrator)** — see §4 items 3–6.

### 2.7 Wave 4 — second user-feedback round (working tree)

Evidence base: 4 screenshots + a 30s screen recording in `/emulated/` (Settings-tab crash captured on device; duplicate shell tabs; ugly default XFCE; stale "flux" prompt).

- **Crash workstream** — haze blur source is now mounted **unconditionally** in `GlassScaffold` (the old per-tab `blurContent` toggle unmounted the `HazeNode` while `hazeChild` nodes from the top bar/dock stayed mounted — the on-device crash class; `HazeNode.onDetach()` does not reset `contentDrawing`). NEW **`core/system/CrashLog.kt`**: uncaught-exception handler installed in `FluxApp.onCreate` writes full stack traces to `filesDir/crashlog/` (cap 20), chained to the previous handler so MIUI's dialog still appears. NEW **`ui/screens/CrashLogScreen.kt`** + `Screen.CRASH_LOGS` route + Settings row ("Crash logs", count subtitle, tap → trace, copy, delete all).
- **Dock redesign** — `GlassBottomNavigation` rebuilt: 64dp frosted pill, 16dp margins, 0.75dp hairline + top sheen, **three evenly distributed tabs** (dead placeholder slot removed), inverted white active pill with animated spring (icon tint/size/label brighten). `SettingsScreen` content now clears the dock (110dp reserve) and guards `SharedStorageAccess`/`PhantomProcessFixer` reads with `runCatching` (MIUI SecurityException safety).
- **Shell session reuse** — `FluxTerminalSessionManager.openSessionAfterHost`/`openHostShellAfterReady` now **focus the existing tab** (match on `type + distroId + shellCmd`) instead of stacking duplicates; `SessionRegistry.ManagedSession` gained `shellCmd`; new `restartSession`/`isRestartable` APIs.
- **Terminal tools + anime splash** — terminal top bar gained a tools button opening a QoL sheet (Ctrl+C/D, TAB, ESC, Paste with 100KB guard + bracketed paste, Copy screen/transcript, Clear via Ctrl+L, Restart, font A±, extra-keys toggle, Close session/all with confirms). NEW **`TerminalSessionSplash.kt`**: randomized monochrome anime-character art (5 original vector drawables) shown when opening the terminal / a new session, auto-fades, never blocks input.
- **XFCE experience** — display defaults flipped for fresh installs: `fullscreen=true`, `hideCutout=true`, `displayScale=120`, `scaled` resolution + stretch (edge-to-edge, aspect-matched; applied by `DesktopLauncher` before every launch/reopen). NEW shared `_flux_xfce_compact_defaults()` in `bliss_guest_common.sh`: single 30px top panel (whisker/tasklist/systray/clock/power), DPI 96, 9–10pt fonts, compact dark xfwm4 (`|HMC`, borderless maximize), Home-only desktop icons, Thunar single-click, screensaver off, keyboard shortcuts (Super+Enter/E, Ctrl+Alt+T, Print → xfce4-screenshooter); called by **all 12 family scripts** (base installs) and the 3 customization scripts (full experience). New monochrome 1280×2772 wallpapers replace the old color-tinted ones (~6.8 MB APK shrink) + 3 appearance presets.
- **Anime launch sticker (guest)** — 8 original monochrome ASCII designs installed to `~/.config/bliss/banner/` + `banner.sh`; random pick per interactive shell, then a local mono info block; `FLUX_NO_BANNER=1` opts out. **Existing installs auto-repair** on next shell open (proot: direct writes; chroot: root payload inside the chroot) — rewrites `ZSH_THEME="bliss"`, deletes the old `agnosterzak` theme, installs the banner; helper bumped to **v2.11**. `ProotZshBootstrap` no longer downloads any remote theme (writes the local folio theme).
- **Appearance editor** — NEW **`core/theme/AppearanceStore.kt`** + **`ui/screens/DistroAppearanceScreen.kt`** + NEW guest script `common/setup/bliss_appearance_apply.sh`: per-distro wallpaper (6 presets + custom SAF image + solid color + **random rotation**), wallpaper style, accent palettes (mono + accents), terminal FG/BG schemes + custom colors + prompt color, mpv-gated **video wallpaper** (honest disabled state without mpv; opt-in autostart overlay), all written to `~/.config/bliss/appearance.env` + applied on next session (hash-guarded; chroot applies at Save time via root). Reachable from Distro Settings → "Wallpaper, colors & terminal".
- **Brand rename** — user-visible "Folio" → **"Folio Linux"** everywhere (app label, wordmark, onboarding/prereqs/legacy screens, notifications, install titles, component descriptions, preset names; short `Folio:` guest progress prefixes intentionally kept).

### 2.8 Wave 5 — third user-feedback round (working tree)

Evidence base: two 1280×2772 screen recordings in `/emulated/` (`Screenrecorder-2026-10-16-12-02-53-603.mp4`, `…-12-06-08-285.mp4`; frames extracted to `/tmp/opencode/rec1f|rec2f`).

- **Install/download UX (agent A)** — `DistroScreen` rebuilt: collapsing parallax title, sticky animated PRoot/Chroot segmented control + category chips, richer frost cards (44dp icon plate, factual badges, Installed chip), animated empty states. `InstallConfigScreen`: the single-choice desktop picker became **independent toggles** — "Terminal base (always included)", "XFCE4 desktop", "KDE Plasma" (shown only when the distro has it); any combination incl. both. New env contract threaded end-to-end: **`FLUX_DESKTOPS`** (space-separated, canonical xfce→kde; "none" when empty) emitted at all 5 plumbing points (`BaseDesktopInstallPlan` ×3, `OnboardingInstallRunner` ×2), legacy `FLUX_DESKTOP`/`FLUX_DESKTOP_ENV` kept as first-selection fallback. **Live install task bar**: `InstallationState` gained phaseLabel/overallPercent/detail/failed/done (`updateActiveInstall`, `dismissTerminalState`); NEW `ui/components/InstallTaskBar.kt` rendered under the top bar on all chrome-visible tabs — pulsing progress + "Installing X · 42% — phase", cancelled/failed/done states with dismiss, tap → re-attaches the **real live progress panel** (runner publishes a `LiveInstall` StateFlow). **Back no longer cancels** — installs keep running (FGS) with the bar visible; cancel is explicit on the panel; the runner's `cancel()` is now ownership/busy-aware (no cross-session kills).
- **Terminal UX (agent B)** — "+" is now a deterministic **inline New Session panel** (`TerminalNewSessionPanel`, Close icon while open; no modal-sheet tap races) listing **only installed guests by default** with a "Show all" toggle and method tabs hidden when empty. Session indicator redesigned into **pill tabs** (distro icon + name, inverted white active, integrated ≥24dp close, spring animation, auto-scroll). Tools sheet expanded (Ctrl+A/E/K/U/Z/W/R, Home/End/PgUp/PgDn, arrows, F1–F12, "Custom key", "Edit shortcut bar"). **Shortcut bar fully customizable**: NEW `ui/terminal/ShortcutBarConfig.kt` (ordered key model: label/kind MODIFIER|SPECIAL|TEXT|COMBO/LOCK, payload, S/M/L size, fn-row flag; scale S/M/L; robust JSON in `TerminalPreferences` `shortcut_bar_config`) + NEW `ShortcutBarEditorSheet.kt` (reorder, size cycle, edit, duplicate, delete, reset w/ confirm) + `ShortcutKeyEditorSheet` creator (label + text/keys/combo) + long-press action menu on keys; TEXT keys write to the pty via the existing paste path, everything else through `TerminalKeyInjector`.
- **XFCE Android-feel + art (agent C)** — `_flux_write_folio_xfce_panel()` (bliss_guest_common.sh): **two-panel phone layout** — TOP 28px status bar (app-drawer + clock + systray + only-loadable indicators) and BOTTOM 64px floating dock (drawer, Terminal, Files, Settings, browser if present, icon-only tasklist, power; explicit stock icons, Papirus-Dark) — called by the compact defaults (all 12 families) and customization scripts; sandbox-verified: valid XML, 13 plugins, idempotent. New default wallpaper `bliss-android-dark.png`. **Multi-desktop scripts**: all 12 family scripts + 4 chroot/extra scripts gate per-desktop work by `DESKTOPS` membership (`FLUX_DESKTOPS` contract). **Anime art colorful/hi-res**: 6 rewritten splash drawables (11–18 KB vector characters: catgirl/mecha/kitsune/shonen/chibi/robot-kitty + NEW magical girl, multi-stop gradients, hair highlights, motif backgrounds) + `TerminalSessionSplash` upgraded (radial glow, float, twinkling sparkles, Animatable fade; same signature). **Terminal banner**: pure-ASCII 256-color art sets (no emoji — the old blue emoji circles are gone), per-design palettes (catgirl pink, mecha cyan, kitsune orange, …), banner revision **2** + `REPAIR_REVISION = 5` so existing installs re-write on next shell open; info line now prints **`folio@localhost`** (display-only; real guest user stays `flux`); fastfetch preset scrubbed of `flux@`.
- **Appearance fix + options (agent D)** — **root cause of the recorded wallpaper failure: no live-apply path existed at all** (staging wrote the guest files, but with the desktop already running nothing consumed them and "Open X11" merely re-opens the surface). NEW `core/theme/AppearanceLiveApply.kt`: when the distro's **XFCE session is running**, the staged applier is executed **inside the guest** (proot via the `proot-distro login` host pattern; chroot via `RootShell.captureInChroot`), then live xfconf values are set over the session D-Bus (discovered from `/proc/<pid>/environ`) + `xfdesktop --reload` (fallback `pkill -x xfdesktop`) + `xfce4-panel -r` — all fail-safe (75s watchdog; honest statuses "updated live" / "appears next start" / actionable hint). Also fixed: xfce4-terminal 0.9+ ignores legacy `terminalrc` only → the apply script now merges the **`xfce4-terminal` xfconf channel** too; single-line `<background-rgba>` alpha patch bug; autostart safety net `~/.config/autostart/folio-appearance.desktop`; subtitle refresh (no stale default). New options: **7 new wallpaper presets** (AMOLED/Mesh/Silk/Dither/Waves/Beams/Sakura, full-res + thumbs), **panel opacity** (Solid/Frosted/Glass), **icon size** S/M/L, **wallpaper dim** 0/25/50% (custom images, host-side pre-process), 2 new terminal schemes + **terminal font size** 10/12/14, live custom-image preview. Apply script sandbox-proven (60 assertions ×bash+dash; 20 e2e incl. the real compiled `AppearanceStore`).
- **Settings remake + motion (agent E)** — NEW `ui/theme/Motion.kt` (`FolioMotion` tokens, `staggerIn`/`rememberStaggerEntrance`). `SettingsScreen` fully remade: hero frost card ("Folio Linux" + version chip + live root/storage status chips), restyled groups, icon-plate rows with 68dp dividers, staggered entrances, `animateContentSize`, press-scale rows — **every callback/route 1:1 preserved**. `MainActivity` transitions: route pushes/pops (slide+fade by depth; fade-only for TERMINAL/INSTALL_WIZARD AndroidView), tab crossfade+rise, top-bar status-pill crossfade — task bar/haze/BackHandler/chrome logic untouched. `Frost.kt`: `FrostRow`/`FrostPill` press-scale + animated fills + `FrostDivider(insets)`/`FrostGroupTitle` + generic `folioSpringAny()`; shared cards press-animated; light motion passes on Home/Distros/CrashLog/Troubleshooting/X11/Audio/TerminalSettings/RootAccess/LegacyTermux/storage. E also caught two latent CI breaks pre-push (`folioSpringAny` Color spec; invalid `stickyHeader` import in DistroScreen) with a real-version stub compile.

### 2.9 Wave 6 — fourth user-feedback round (working tree)

Evidence base: `Screenrecorder-2026-10-16-16-47-41-257.mp4` (first-run: forced 5-step onboarding → distro pick → install) + `Screenshot_2026-10-16-16-49-42-950_com.nopiece.folio.jpg` (Void install failed at XFCE package install).

- **Onboarding de-forced (agent 1)** — `OnboardingFlowScreen` step graph is now `Welcome → Consent → HostSetup → Ready`; `ReadyPage` ("Foila Linux is ready") offers **"Install a distro now"** (enters the unchanged DistroPick→Options→Storage/Running sub-flow) or **"Later"** (finishes onboarding via the same marker and lands on Home). Step counter replaced with `StepDots` (4 reactive dots + a 3-dot optional install branch). MainActivity gating verified (auto-show only when incomplete; "Show onboarding" still opens it on demand).
- **Multi-shell (agent 2)** — `FluxTerminalSessionManager.openSessionAfterHost/openHostShellAfterReady/openSession` gained `reuseExisting: Boolean = true`; **the "+" panel (and empty-state selector) pass `false`** so any number of shells of the same distro open as separate tabs (MAX_TABS guard + toast unchanged); Home "Shell"/notification paths keep focus-existing behavior; `restartSession` forces `false` so restarts never collapse into a sibling tab.
- **Tools menu redesign (agent 2)** — sheet rewritten as a compact palette: title "Tools"; **Keys** (Ctrl C/D/Z, Ctrl L, Tab, Esc + `Add key` / `Edit keys`); **Clipboard** (Paste/Copy/Clear); **Session** (Restart, Hide/Show keys, Font −/size/Font +, Close/Close all). F1–F12 and redundant combos removed from the sheet (still available in the key editor's special-key list). Human copy pass across the session panel, selector (`Installed distros only` / `All distros`), bar editor/key creator ("Your keys", "Add key", "Size", "Reset", "Save") and confirms.
- **Banner header minimal (agent 3)** — every banner writer (3 customization scripts, chroot helper, termux tweaks, KDE .zshrc block, `GuestZshrcRepair` constants) now prints the art + exactly one line **`foila@<id>`** (`ID` from `/etc/os-release`, lowercased; PRETTY_NAME fallback; `linux` last resort). No host/version/time/fastfetch. `.banner-rev` → 3, `BANNER_ART_REVISION` → 3, `REPAIR_REVISION` → 6 (existing installs self-fix on next shell open). Dead fastfetch-preset writers removed.
- **Void XFCE install failure (agent 3)** — verdict: the single `xbps-install` transaction aborted mid-download on a flaky link and the block hard-exited with no retry (package names verified correct). NEW shared `_flux_pm_refresh()` + `_flux_retry_pkg()` (3 attempts, metadata refresh between, logs `Package download failed — retrying (2/3)…`, returns the last rc) in `bliss_guest_common.sh`; used by all 12 families' XFCE blocks + Debian KDE + standalone chroot scripts (inline loops); final message `…failed after 3 tries — check your network, then tap Retry Setup.` Sandbox-tested under dash + busybox (success/flaky/hard-fail paths).
- **XFCE display size (agent 1)** — `X11SettingsScreen` "Display size": width slider 480–2560 (8px snap, height from device aspect) writing `displayResolutionMode=custom` + `displayResolutionCustom="WxH"`; exact-field commits validated `WxH` (320–7680 × 200–4320); "Match device" restores adaptive `scaled`. Uses the vendored termux-x11's real keys (`LorieView` source of truth) with the same broadcast model as existing prefs; hint "takes effect when the desktop (re)opens".
- **Folio → Foila rename (all)** — brand confirmed by the user: **"Foila Linux"**. App label (`strings.xml`), wordmark, onboarding/prereqs/legacy screens, notifications, install titles, component/screen copy, preset labels, splash text, and **85 script files** (echoes, hints, `.desktop` names, Konsole display name, theme/PROMPT label → `foila`) are all renamed; `ProotZshBootstrap` theme label fixed too. Functional identifiers intentionally kept: `FLUX_*` env vars, `flux` guest user + `/home/flux`, `/etc/fluxlinux`, `/data/local/folio`, `folio.zsh-theme`/`folio-banner`/`folio-*` ids, `FOLIO_*` payload vars, `FluxLinux`/`FluxApp`/`Folio*` Kotlin identifiers, `Theme.Folio`/`Theme.FluxLinux` style ids, Konsole `Folio.profile` file name, `wallpapers/Folio` path. Zero user-visible `flux`/`Folio` left (verified by repo-wide scans).

### 2.10 Wave 7 — fifth user-feedback round (shipped)

Evidence base: `Screenshot_2026-10-16-19-15-12-464` (Alpine shell + old banner), `Screenshot_2026-10-16-19-20-30-209` (host shell: `libbash.so: /data/data/com.ivarna.fluxlinux/files/usr/etc/profile: Permission denied`), `Screenshot_2026-10-16-19-20-36-000` (Void terminal-base install at 70%).

- **Host shell fix (two layers)** —
  root cause: the Flavor bootstrap's `libbash.so` (and ~600 other files) had `/data/data/com.ivarna.fluxlinux/...` baked in; the repack script only rewrote *text* files, so login bash sourced a foreign path → SELinux EACCES + bare `libbash-5.3$` prompt.
  (a) `scripts/repack_bootstrap_for_appid.sh`: pure byte patcher (no patchelf anymore — its version-dependent behavior left stale strings on CI) rewrites the **bare app-id** in every NUL-terminated string (paths, RUNPATH, app-ids) with NUL-padding; hard verify (`grep -ra` must find zero old-id refs) fails the build. Two latent patcher bugs were fixed during validation: `seen`-set ordering that skipped real files behind symlinks (the CI failure: `realpath` of a versioned symlink entered `seen` before the S_ISREG skip, so `libreadline.so.8.3`-style targets were never patched when listed after their symlink) and the `data/data/`-prefixed-only match. Verified: repack → `verify_bootstrap.sh` PASS, `readelf` RUNPATH updated on bash/libreadline/libbash.so, flat tree zero refs.
  (b) Runtime safety net: `TermuxHostPaths.ensureHostShellRc()` writes `$HOME/.bashrc` (+`.bash_profile`, content-compared, refreshed on shell open / extract / app update) with PATH/PS1/HISTFILE + guarded sourcing of `$PREFIX/etc/profile`; `openHostShell` launches `libbash --noprofile --rcfile $HOME/.bashrc -i` so no baked path can break it. `rewriteStockPaths` now maps all legacy data roots (`com.termux`, `com.ivarna.fluxlinux`, `com.zenithblue.fluxlinux`, `com.nopiece.folio`, `com.nopiece.bliss`) → current.
- **Install speed** —
  `RootfsDownloader`: parallel segmented download (6 Range GETs sharing the probe response as segment 0, positional `FileChannel` writes into one `.partial`, per-segment resume ×3, 150 ms progress throttle, cancel-all; single-stream fallback for no-Range servers; SHA/min-size/rename gates unchanged). Guest side (`bliss_guest_common.sh`): `_flux_pick_fast_mirror` races candidates with `curl -r 0-262143 --max-time 4` and picks the fastest — rewrites `/etc/apk/repositories` + `/etc/xbps.d/00-bliss-repository.conf`, prepends winner server to Arch `mirrorlist`, drops `99-bliss-fast` apt tuning, sets `max_parallel_downloads=10` (+`fastestmirror=True` on dnf4) for dnf; hooked into `_flux_retry_pkg`/`_flux_apt_prep`; all best-effort, one-line log, sandbox-tested (12 cases, dash+bash). `bliss_install.sh` confirmed single-download (local archive path).
- **Real anime banner (truecolor)** — 5 characters (anime-girl, maid, kitsune, chibi, catgirl) generated from free-licensed illustrations (credits: `docs/art-credits.md`; generator: `scripts/tools/ansi_art_gen.py`) as **half-block truecolor** art (~31-43 visible cols × 16-19 rows, 10-15 KB each), embedded **identically** in all 6 writer scripts + `GuestZshrcRepair` (byte-stream equality verified). Header now exactly **`bliss@<id>`**; theme label `bliss`; `.banner-rev`=4, `BANNER_ART_REVISION`=4, `REPAIR_REVISION`=7 (existing installs self-fix). Sandbox: art + exactly one header line; `FLUX_NO_BANNER`/non-interactive silent; no literal escape leaks.
- **Bliss rebrand** — flavor/appId `bliss` / `com.nopiece.bliss` (versionCode 14, versionName "3.0.0"); CI `assembleBlissDebug` → `app-bliss-debug.apk`; `settings.gradle` rootProject "Bliss"; `HostBootstrap.BLISS_PACKAGE` (+legacy folio pin); `app/src/folio`→`app/src/bliss` (pulse-runtime `.so` RUNPATH patched to the bliss path); **new icon** `ic_bliss_mark` (five-petal lotus + sparkle over indigo→violet→pink gradient, vector monochrome layer; legacy `ic_launcher_monochrome.png` removed); brand sweep of all user-visible `Foila`→`Bliss` (39 Kotlin/res files + 83 scripts + `GuestZshrcRepair`); functional ids untouched ($0 table).
- **Distro list overhaul** — every `comingSoon` card resolved (zero left):
  **Added** `rocky` + `centos_stream` (proot-only, arm64): official container bases converted to plain rootfs tars (`/tmp/opencode/rootfs-staging/convert_image.py` method) and hosted on the `rootfs` release of `HakaI963/folio` — `rocky_9_aarch64_plain.tar.xz` 51,693,928 B sha256 `7af07eade178c80ad3d322940ec6064c566dd34a8cb7e931fcbf4a5e44c18611`; `centos_stream_9_aarch64_plain.tar.xz` 37,294,356 B sha256 `d9ba19e6647c99d0c4d1424dc4a7717f350943c485116423445a64b1faa65f9a`. Shared `rpm/common/setup/setup_rpm_family.sh` (dnf makecache → base pkgs → EPEL (+key import) → tolerant CRB enable → `groupinstall "Xfce"` fallback chain → whisker/screenshooter/battery/netload → locale/user/pulse/banner/GPU; KDE requested → skip with log; XFCE-missing → exit 1; all transactions via `_flux_retry_pkg`); customization reuses the distro-agnostic `setup_customization_xfce.sh`; `bliss_install.sh` cases; `HostScriptDeployer` deploys the rpm script for the manual path; enum/component/tests updated (26 installable: 14 proot + 12 chroot).
  **Removed** adelie (a viable `adelie-rootfs-mini-aarch64-1.0-beta6-20241223.txz` pin exists — recorded for a possible future round), artix (no official arm64 rootfs tarball), backbox (amd64-only), openkylin (ISOs only), gentoo (source-based desktop impractical).
  **Retro Emulation** (Debian component): enabled (`comingSoon` removed) — complete installer, arm64 packages verified reachable.

**Hotfix (round 5.1, shipped as `bliss-debug-0557a1a`).** The first round-5 build (`46bc7d8`) failed host setup with `Fatal Python error: init_android_streams … Frozen object named 'posixpath' is invalid`. Cause: the new **binary** prefix patcher rewrote an `com.ivarna.fluxlinux` occurrence inside CPython's **frozen-module marshal blob** in `libpython3.14.so` (length-prefixed data, not a C string) — NUL padding corrupted the blob. Fix in `repack_bootstrap_for_appid.sh`: an occurrence-shape gate (`is_nul_bounded`) patches only files whose every occurrence is a plain NUL-terminated printable run; everything else — Python program/stdlib/pyc caches, marshal/frozen blobs, packed tables (`coreutils`, `capsh`, `dbus-daemon`, `libcrypto`, `dbus-daemon-launch-helper`) — is **retained byte-identical** (they shipped unpatched in rounds 1–4 and worked). The verify step accepts exactly the retained list (`.repack-retained.txt`) and still fails on any other leftover. Local proof: `libpython3.14.so` dst ≡ src (frozen `posixpath` blob intact), bash/libreadline/RUNPATH still patched, `verify_bootstrap.sh` PASS. 84 retained files in total (python stdlib + dynload + the 6 packed-table binaries).

**Hotfix 2 (round 5.2, shipped as `bliss-debug-d83208f`).** The round-5.1 clean bootstrap alone did not help devices that had already run the round-5 build: installs share the app id, app data persists, and the extracted-tree marker (`home/.fluxlinux/bootstrap.extracted`, `EXTRACT_VERSION|pkg`) still matched — so `ensureExtracted()` took the happy path and kept the **corrupted prefix already on disk**. Fix: `BootstrapInstaller.EXTRACT_VERSION` 3→4 — the next host prepare re-extracts the full prefix from the new APK asset (`usr/var/lib/proot-distro` is preserved, so installed guests survive). Both hotfixes are in the tree; the shipped APK carries the clean bootstrap **and** the invalidation marker.

---


### 2.11 Wave 8 — sixth user-feedback round (shipped)

Evidence: ss1/ss2 (banner pixelation), ss3 (boxy cropped variant), ss4 (Welcome with penguin).

- **Banner art v2 (49 variants)** — 54×28-cell truecolor half-block art from 20 free-licensed anime artworks (Wikimedia mascots/OS-tans, CC0/CC-BY/CC-BY-SA/public domain) + hue-recolour derivatives (hair/eyes only). Characters are knocked out onto pure black (alpha flood-fill + key-drop + feather) so there is **no background box/crop**; the disliked `01-anime-girl` crop is gone. Identical payload in all 6 writers + `GuestZshrcRepair` (aggregate md5 `030ff30cbe6d…`, 49 sections × 7 copies) — verified independently. 833,737 B total (~17 KB/variant; ≤1.1 MB per writer). Revisions: `.banner-rev` 5, `BANNER_ART_REVISION` 5, `REPAIR_REVISION` 8. Generator/credits: `scripts/tools/ansi_art_gen.py`, `docs/art-credits.md` (49-row variant table + rejected list).
- **New app icon** — minimal four-point sparkle + companion dot on a near-black tile (#0A0B0F/#15161F + soft indigo glow), white→lavender gradient; monochrome themed-icon layer; preview-verified at 48/96/192 px (filled lotus and crescent concepts rejected at 48 px). `ic_bliss_mark` / `ic_launcher_background` / `ic_launcher_foreground` / `ic_launcher_monochrome` / legacy `ic_launcher.xml` updated.
- **Welcome redesign (penguin removed)** — the `onboarding_bg_1` illustration block and the pinned glass header were replaced by a one-shot animated hero (frost radial glow + always-dark tile + floating mark); feature cards kept, step dots + Get Started wiring unchanged; the dead `OnboardingScreen.kt` reference was swapped to the mark and the webp deleted (all 5 unreferenced webps removed).
- **Complete file-name rename** — see §4f item 50. 18 asset files renamed (`flux_guest_common`→`bliss_guest_common`, `flux_gpu_common`→`bliss_gpu_common`, `flux_install`→`bliss_install`, `folio_appearance_apply`→`bliss_appearance_apply`, `fluxlinux_chroot.sh`→`bliss_chroot.sh`, wallpapers `fluxlinux-*`/`folio-*`→`bliss-*`, presets `folio-*`→`bliss-*`), res `folio_wall_*`→`bliss_wall_*`, `ic_stat_flux`→`ic_stat_bliss`; guest names `bliss.zsh-theme` (+`ZSH_THEME="bliss"`), `~/.config/bliss`, `/etc/bliss`, `/data/local/bliss`, `bliss_busybox`, `bliss-locale.sh`, `bliss-ensure-locale`, `bliss-gpu.sh`, `bliss_kde_env.sh`, `bliss_resolve_bb.sh`, `bliss-*.desktop`, `bliss.profile` (Konsole), `bliss_xfce_assets`, `bliss_*` temp scripts, AppearanceStore prefs `bliss_appearance`. Migrations: appearance-apply moves `.config/folio`→`.config/bliss` and removes old theme/desktop leftovers; GuestZshrcRepair rewrites `.zshrc` (old paths sed-migrated, repair rev 8); chroot `.flux_configured` honored via dual-check; `/etc/fluxlinux` + `/data/local/folio` rename-once lines; prefs value migration; legacy wallpaper ids aliased. Frozen exceptions (documented): `fluxlinux-host.env` (dual-write; `setup_termux.sh` reads it) and `~/.fluxlinux` marker dir. Kept identifiers: `FLUX_*`/`FOLIO_*` env names, `flux` Linux user, `folio-banner` functions, `com.ivarna.fluxlinux` package.

### 2.12 Wave 9 — seventh user-feedback round (shipped)

Evidence: `Screenshot_2026-10-17-17-01-55` (Alpine: sticker printed 4×) and `…-17-02-19` (Debian rooted: plain bash prompt, no sticker).

- **Sticker printed 4× on Alpine (root cause)** — `bliss_chroot.sh`'s chroot-login helper still guarded the `.zshrc` append with the pre-rename string `folio/banner.sh`; since round 6 ships `.config/bliss/banner.sh`, the guard never matched and **every login appended another source line**. Fix: awk dedupe (keep first line matching `banner.sh` / `appearance-apply.sh`) before the guarded append, in the login helper + Kotlin `BannerArtWriter.repairRcFiles` + the chroot setup step; and a hard `_BLISS_BANNER_SHOWN` once-per-shell guard inside `banner.sh` so extra sources can never re-print. Sandbox: 4 duplicate lines → 1, idempotent on re-run; double-source prints one banner.
- **No sticker on Debian/Ubuntu chroots (root cause)** — those sessions fell back to **bash** (zsh is not in the debian13 chroot base) and only `.zshrc` was wired. Fix: `banner.sh` rewritten **POSIX** (works in zsh/bash/dash, verified in all three), wired into `.bashrc` + `.bash_profile` by every writer, the login helper, and GZR (proot + chroot); `.bashrc` is created when missing; `zsh` added to `setup_debian13_chroot.sh`'s base install list; a `.bash_profile` is created so login bash sources `.bashrc`. Also fixed `guest_login_user`'s sequential runuser/su/fallback blocks (a shell exit could start a second shell — now an exclusive `return` chain).
- **Root-cause find: ~128 KB `su -c` argument cap** — every root command rides `su -c <one string>`; Linux rejects a single argv element above `MAX_ARG_STRLEN` (verified: 200 KB fails `E2BIG`, 130 KB works). The old chroot repair/install payloads (embedded art set: 833 KB → base64 ~1.1 MB, then the helper call base64s again) **could never run on-device** — chroot repairs failed silently and every session retried them. Fix: new `core/root/ChrootStager` writes big content in bounded chunks (`printf %s '<b64 slice>' | base64 -d >> file`, 4-char-aligned slices, ~60 KB content per invocation → ~81 KB argv), and:
  - `BannerArtWriter.pushChroot`: small setup (dirs, rc dedupe/wiring, caller extras) → chunked `banner.sh` + `.keep` + all arts → finalize (prune via `.keep`, write `.banner-rev`, chown, marker). The art set is never deleted up-front, so a failed push keeps the previous set usable.
  - `OnboardingInstallRunner.runChrootGuestBlocking` stages family/customization/hw-accel script bodies into the chroot (`/.bliss-guest-step.sh`) and runs them by path — **chroot component installs (theme/zsh/banner) work now**.
  - `GuestZshrcRepair.chrootRepairScript` replaced by `chrootExtraRepairs()` (small repairs spliced into the setup step); `REPAIR_REVISION` 8 → 9.
- **Banner art quality levels** — the round-6 L5 grids were decoded, lanczos-downscaled by a uniform factor (tw/54) and re-encoded into 5 levels (`assets/banner/l1..l5` = 24/32/40/48/54 cols; 49 designs each; L1 242 KB → L5 813 KB). L5 files stay **byte-identical** to round 6 except 4 dark variants (15/16-godette, 22/23-rational) that got a luma lift (means 53.4/54.0/26.9/19.7 → ×1.25/1.25/1.35/1.6) so they are actually visible, as flagged in the user's screenshot. The writers now embed the **L2 (32-col) set** (~376 KB payload vs 843 KB) and `.banner-rev` is `7-l<quality>` (writers treat any `7-*` as fresh, so an app-selected level is never downgraded).
- **Settings → Terminal → "Banner art"** — 5-stop slider (Pixelated 24 / Small 32 / Medium 40 / Large 48 / Max 54); persisted in `TerminalPreferences` (`banner_quality`, default 2); changes push to every guest via `BannerArtWriter.refreshAsync` (proot roots + host home + chroots).
- **Settings → Terminal → "NSFW banner art"** (off by default) — FluxSwitch gated by an 18+ `AlertDialog` ("I am 18+ — enable"); 4-mode slider Minimal/Moderate/Fan service/Full; optional API URL template (`{level}` → `minimal|moderate|fanservice|full`, `{rand}`) + optional key; "Refresh art now"; cached-count status. `NsfwArtFetcher` fetches from keyless built-ins per level (purrbot sfw/nsfw neko, nekos.life lewd) or the custom template, and `HalfBlockRenderer` converts on-device to the half-block ANSI format (alpha flatten over black, border flood-fill knock-out tol 40, dark-frame ×1.5 lift, trim black borders, grouped SGR, literal `\033`, ≤45 KB with a re-render guard at 80% cols). Cache: `cacheDir/banner-nsfw/src/NN-<sha1>.img` + `…/l<q>/NN-<sha1>.txt`; `BannerAssets.active` prefers NSFW only when enabled **and** the set is non-empty (rev `7-n<level>-l<q>`). GIF/JPEG/PNG/WEBP via BitmapFactory (first frame for GIFs). The pipeline was validated off-device by a line-faithful Python port: 7 real API images rendered and visually checked (backgrounds clean, characters recognizable, no raw ESC bytes, `printf %b` conversion verified).
- **Kotlin core restructure** — new `core/banner/` (BannerAssets, BannerArtWriter), `HalfBlockRenderer`, `NsfwArtFetcher`, `core/root/ChrootStager`; `GuestZshrcRepair` shrank 2144 → ~620 lines (embedded art + `BANNER_SH` constants deleted; assets are the single source). `versionCode` 15 / `versionName 3.1.0`.
- **Verification** — 90/90 scripts `bash -n`; 181 `.kt` balanced (nested-comment-aware; caught + fixed a real `/*` sequence inside a KDoc that Kotlin would nest), 0 import problems; res color literals OK; `host/setup_termux.sh` byte-identical; 6 writer art payloads md5-identical (`f7a78afc3c87`) + `assets/banner/banner.sh` byte-identical to the canonical script; sandboxed dedupe/wiring/once-guard tests; chunk simulation (9 chunks for the L2 set, byte-exact reassembly, ~81 KB max argv); CI run `39037758339` green.

### 2.13 Hotfix 3 — host script deploy (shipped)

Symptoms: fresh install → onboarding Host Environment → "Setup failed …" with detail "Script deploy failed — check app storage space." (screenshot 20:20).

Diagnosis: the APK is intact — whole-APK `testzip` CRC pass + all 48 `HOST_SCRIPTS` assets, `loader.apk`, `fonts/font.ttf` readable and size-verified from the exact released artifact; the deploy target path (`files/home`) is created by the app (the bootstrap tar has no `home/` entries, `usr/etc/pulse` is a directory). The only unguarded failure paths were device-side IO: per-script writes, the `99-bliss.pa` write, the `libmp3lame.so.0` fallback, `deployLoaderApk`, and the deploy-marker write — a full disk (the APK + ~124 MB bootstrap extract are gigabytes of peak) explains a deploy that fails right after a successful extract.

Fixes (`63b57ee`):
- `HostScriptDeployer.copyScriptAsset`: remove a stale target (dir/symlink/leftover) + retry once; a copy is OK only when the file exists non-empty.
- Tail steps can no longer fail the deploy: pulse `.pa` write, `libmp3lame.so.0` fallback copy, `markDeployedSafe`.
- `lastDeployFailure` = first failing file + exception + free MB; `TerminalLauncher.lastPrepareError` surfaces it (both fast + cold paths) instead of the generic line.
- EACCES-style failures (only when free space > 50 MB) get one root `chown -R <appUid> files/home` + `chmod u+rwX` heal and a full retry (`healHomeOwnership`).
- `TerminalLauncher`: pre-extract free-space check (`MIN_HOST_FREE_BYTES` 256 MB) with an actionable message ("Not enough free space — need about 256 MB, have X MB").

Verification: battery green (90/90 scripts, 181 `.kt` balanced 0 unbalanced, 0 import problems, res literals OK, `setup_termux.sh` byte-identical); CI run `39043883518` green; release asset digest matches local sha256 (`591024930…`).

## 3. Standalone-host constraints (never regress these)

- `targetSdk` stays **28**. `setup_termux.sh` stays byte-identical to upstream. Keep the `Os.chmod`-based mode repair. Keep jniLibs pinning. Keep `preserveProotDistro` on re-extract.
- Any further device failure (`exit=126`, `Permission denied`) → read the on-screen `errorDetail`/`lastPrepareError` card or `adb logcat -s TerminalLauncher:V BootstrapInstaller:V`.

---

## 4. The user request being implemented — parse + status

Legend: DONE / PARTIAL / TODO.

1. **Onboarding "Grant access" / "Skip for now" buttons look ugly** — **DONE** (§2.4).
2. **Distro install needs component choice** (Terminal only / XFCE4 / KDE) **and KDE must actually work** — **DONE**.
   - Picker in `InstallConfigScreen` + `DesktopInstallSelection`; HW-accel as an explicit extra; env contract threaded end-to-end (§2.5).
   - KDE backend fixed — real breaks found: wrong Plasma 6 package names on Debian trixie (`spectacle`→`kde-spectacle`, `kwallet-pam`→`libpam-kwallet5`; `qt5-style-plugins` → best-effort), unconditional `read -p` hang (component sessions run on a pty, `[ -t 0 ]` is true — pauses now opt-in via `FLUX_INTERACTIVE=1`), missing shared X stack for KDE-only installs (now self-sufficient `BASE_PKGS`), `FLUX_DESKTOP` spelling normalization at launch, KDE session hygiene (runtime dir/sessions/ICE), complete stop/uninstall lists, `.vnc`/XFCE assets preserved on KDE uninstall.
3. **Tab switch from Home to any other tab crashes** — **DONE**. Root cause: the bidirectional tab/screen state mirror (Compose state ↔ activity-level `MutableStateFlow` refs via `LaunchedEffect`/`collectAsState` round trips) was removed; state is now single-source (snapshot state) with one-shot request channels (`pendingTabRequest`/`pendingScreenRequest`, replay=1) drained once by the composition — no state can feed back, so the class of loop is structurally gone. Dead write-only refs removed. On-device confirmation remains part of §10 QA.
4. **Floating bottom nav overlaps screen content (incl. terminal)** — **DONE**. Content viewport now consumes `navigationBarsPadding()` (Scaffold insets stay zero), so the 110dp bottom content padding of Home/Distro screens always clears the nav (nav = 24dp + 72dp; 110dp clears with ~14dp spare); Terminal tab excluded (owns its own insets, full height).
5. **"UI is not good" — remake with better graphics, animated background; black/white is good** — **DONE**. `FolioAnimatedBackground` wired at the `GlassScaffold` root (behind Scaffold + nav overlay, no pointer input); monochrome frost system applied across onboarding/install/home/settings/terminal; 0.75dp hairline border system; monochrome status/badges.
6. **Bottom nav: remove Terminal tab**, leave an **empty placeholder slot** — **DONE**. Visible tabs: Home · Distros · Settings + empty 4th slot (keeps original column spacing, non-clickable). `BottomTab.TERMINAL` stays in the enum for state; terminal reachable from Home's shell button and the FGS notification; on the terminal tab both top bar and bottom nav hide (upstream behavior) and the IME is dismissed when leaving.
7. **Home: show XFCE and KDE launch buttons on installed environments** — **DONE** (§2.4).
8. **Terminal "command menu" banner** (date/time + FLUX ASCII art) → monochrome Folio banner — **DONE** (§2.6).
9. **Terminal extra-keys bar redesign** (reposition, simple, no tofu glyphs) — **DONE** (§2.5).
10. **Remaining "Flux" names → "Folio"** — **DONE**. User-visible strings swept in Kotlin (screens, notifications, toasts, titles) and scripts (780 replacements). Remaining occurrences are functional (env vars, paths, scheme ids, guest user, class names, bwrap markers) or in the never-touch upstream file.
11. **Chroot distros + staging/mounts move to Folio's own `/data/local/bliss`** — **DONE** (§2.5).
12. **More install options for new distros, per method capability (proot vs chroot)** — **DONE** (with item 2; `DesktopChoice` + `InstallExtra` per method).
13. **Workflow: track down → write → compile last** — **DONE**. Three waves of partitioned agents + orchestrator integration; CI compile is the final gate.

### 4b. Second feedback round (2026-10-16) — screen recording + screenshots

14. **"UI navigation still crashes"** (recording: Settings tab → ~1.5s → "Folio keeps stopping"; predates the overhaul) — **DONE (hardened + instrumented)**. The haze source can no longer unmount while top bar/dock `hazeChild` nodes are mounted (the identified crash class), and the new in-app **crash logger** now writes the full stack to `filesDir/crashlog/` (viewable in Settings → Crash logs) if anything still throws — share that trace if it recurs. (Tapping MIUI's "View summary" on the old dialog would still give the definitive stack for the previous build.)
15. **Dock ("doch") redesign** — **DONE** (§2.7): 64dp frost pill, 3 evenly distributed tabs, inverted white active pill, spring animation, no dead placeholder; Settings content no longer hides under it.
16. **"Open Shell" always opens a new shell** — **DONE** (§2.7): focuses the existing session/tab for that distro instead of stacking duplicates.
17. **XFCE UI bad: not fullscreen, no good background, few features, needs compact phone layout** — **DONE** (§2.7): fullscreen/edge-to-edge defaults, compact top panel + small-screen metrics everywhere, new monochrome wallpapers, screenshooter + shortcuts + single-click Thunar.
18. **"In Termux it's still flux and old style"** — **DONE** (§2.7): existing installs repair themselves on the next shell open (theme + banner); new installs write the monochrome folio profile.
19. **Rename everything from "Folio" to "Folio Linux"** — **DONE** (§2.7).
20. **Distro settings: custom wallpaper (image/video), colors, terminal text colors, dynamic multiples** — **DONE** (§2.7 appearance editor; video is mpv-gated/honest).
21. **Bring back the terminal launch sticker, anime characters, not hardcoded** — **DONE** (§2.7): app-side randomized anime splash + guest-side random anime ASCII banner (8 designs).
22. **Terminal top bar: keyboard/plus — add more buttons/tools/QoL toolbar** — **DONE** (§2.7 tools sheet).

### 4c. Third feedback round (2026-10-16) — two screen recordings

23. **Distro download page redesign, friendlier** — **DONE** (§2.8): rebuilt DistroScreen (parallax title, animated sticky filters, richer cards, Installed chips).
24. **Desktop choice as toggles (Terminal/XFCE/KDE; pick all, one, or two)** — **DONE** (§2.8): independent XFCE/KDE toggles; both can install together (`FLUX_DESKTOPS`).
25. **"Downloading + back = no idea what's happening" → live task bar on top** — **DONE** (§2.8): pulsing live strip under the top bar on all tabs, honest cancelled/failed/done states, tap re-opens the real progress; back never cancels.
26. **"Wallpaper not working" (recorded) + more customization options** — **DONE** (§2.8): live-apply to the running desktop (root cause: none existed), terminal colors via xfconf, + 7 presets, panel opacity, icon size, wallpaper dim, terminal font size.
27. **"+" (new terminal) not working; shows uninstalled distros; redesign** — **DONE** (§2.8): deterministic inline panel, installed-only by default, redesigned rows/tabs/empty state.
28. **Terminal indicator icon redesign** — **DONE** (§2.8): pill session tabs with icon + name, inverted active state.
29. **Anime doesn't look like anime; make it colorful/high-res** — **DONE** (§2.8): 6 colorful detailed vector characters + animated splash; colorful 256-color ASCII banner (no emoji).
30. **"flux@localhost" — it's not flux** — **DONE** (§2.8): header prints `folio@localhost`; fastfetch preset scrubbed.
31. **XFCE remake to feel Android-friendly** — **DONE** (§2.8): status bar + floating app dock, big touch targets, fixed icons, new wallpaper; applies on fresh installs + XFCE-customization re-run.
32. **Shell tools: more features + custom keybinds + editable bottom shortcut menu (keys/position/size)** — **DONE** (§2.8): expanded tools, custom key creator, full bar editor (reorder/size/edit/duplicate/delete/reset, long-press menu).
33. **Settings tab remake + new animations all over (A-to-Z re-animated)** — **DONE** (§2.8): Settings hero/rows remake, route push/pop + tab transitions, staggered entrances across screens, press micro-interactions, shared Motion tokens.

### 4d. Fourth feedback round (2026-10-16 evening) — recording + screenshot

34. **"Remove that opening setup screen — don't force a distro download at first; user can download later as he wants"** — **DONE** (§2.9): onboarding ends at a Ready step with optional install; "Later" completes onboarding and lands Home.
35. **"Screenshot appears to be an error while downloading — investigate"** — **DONE** (§2.9): Void XFCE install died mid-download with no retry; added metadata-refresh + 3-attempt retry for every family + useful failure copy. Package names verified correct.
36. **"Plus → same distro won't open a new shell — need multiple shells/terminals of the same distro"** — **DONE** (§2.9): "+" panel always opens a new tab (`reuseExisting=false`); Home buttons keep focus-existing.
37. **"It writes folio@localhost and more more — totally unnecessary; only write foila@distro name"** — **DONE** (§2.9): banner = art + exactly `foila@<distro-id>`; fastfetch preset machinery removed; revisions bumped for existing installs.
38. **"Redesign tools menu and its options — lots of unnecessary stuff; AI-written text — make it human, clean, compact"** — **DONE** (§2.9): trimmed tools sheet + full plain-copy pass.
39. **"Add option to select XFCE display size (slider + absolute pixel value)"** — **DONE** (§2.9): X11 Display → Display size section.
40. **"Replace flux with foila; Foila Linux app new name — every UI file, backend file"** — **DONE** (§2.9): brand "Foila Linux" everywhere user-visible incl. 85 script files; functional internals documented and kept.

### 4e. Fifth feedback round (2026-10-16 night) — three screenshots

41. **"Host shell can't be used — Permission denied (`libbash.so: /data/data/com.ivarna.fluxlinux/.../profile`)"** — **DONE** (§2.10): repack patches every baked app-id string byte-wise (+RUNPATH, no patchelf), verify fails the build on leftovers; host shell runs `--noprofile --rcfile ~/.bashrc` with a generated rc; extraction re-roots every legacy data path.
42. **"Downloading way too slow — 30+ min even without XFCE, network is fast — make installation quick as hell"** — **DONE** (§2.10): 6-connection parallel rootfs download with resume/retries; guest fast-mirror racing (apk/xbps/pacman), apt retry tuning, dnf parallel downloads; single local rootfs confirmed.
43. **"ASCII figure is not anime — make real anime, high quality, real anime characters"** — **DONE** (§2.10): truecolor half-block art of 5 anime characters from free-licensed illustrations (`docs/art-credits.md`), random per shell, identical in all writers, revision-bumped for existing installs.
44. **"Rename app to Bliss, everything to bliss, change app icon"** — **DONE** (§2.10): app id/flavor `com.nopiece.bliss`, new lotus icon + gradient background + monochrome layer, version 3.0.0, full user-visible sweep (Kotlin, res, 83 scripts, prompts, install logs, notifications).
45. **"Distro list: many tagged Soon — diagnose for arm64; add all that work, remove what can't"** — **DONE** (§2.10): added Rocky Linux 9 + CentOS Stream 9 (arm64 proot, hosted+SHA-pinned); removed Adélie/Artix/BackBox/openKylin/Gentoo with evidence; zero "Soon" cards remain; Retro Emulation enabled.
46. **"Check every file — replace flux with bliss"** — **DONE** (§2.10): zero `Foila`/`foila`/`flux` display text anywhere in `app/src` (repo scans); functional ids (`FLUX_*`, `flux` user, `folio-*`) kept by design.

### 4f. Sixth feedback round (ss1–ss4)

47. **"Anime on top is damn good but increase its quality — too low/pixelated"** — **DONE** (§2.11): 54×28-cell truecolor art (~2.5× detail), sharper crops, 49 variants.
48. **"ss3 is a big boxy cropped image — remove it, add more like the others; only 2-3 total — make 40-50"** — **DONE** (§2.11): boxy crop dropped, black-blended knockouts, 49 designs (20 artworks + recolours).
49. **"ss4 remove the old setup screen, make a new one, remove the penguin"** — **DONE** (§2.11): Welcome redesigned with an animated hero; penguin asset + all references deleted.
50. **"Make a new Bliss app icon — something good, dark, clean, minimal, perfect"** — **DONE** (§2.11): sparkle-on-near-black icon, preview-verified at 48 px, monochrome layer.
51. **"See if any foila/folio/flux file name remains and make it bliss"** — **DONE** (§2.11): all remaining brand file/asset names renamed (repo assets + guest paths) with migrations; only documented functional identifiers and the two frozen host-contract names remain.

### 4g. Seventh feedback round (screenshots 17:01/17:02)

52. **"Fix anime to show on all distros"** — **DONE** (§2.12): 4× Alpine stacking fixed (login-helper stale guard + once-per-shell guard + dedupe); Debian/Ubuntu bash sessions now print the sticker (POSIX banner.sh + `.bashrc`/`.bash_profile` wiring + zsh in the chroot base); the ~128 KB `su -c` cap that silently broke all chroot repairs/component installs is fixed with chunked staging.
53. **"Fix size, too big — want small clean, high res"** — **DONE** (§2.12): default is the new 32-column level; 49 designs regenerated at 5 sizes; dark variants brightened.
54. **"Add resolution slider (pixelated → max)"** — **DONE** (§2.12): Settings → Terminal → Banner art, 5 stops (24/32/40/48/54), applies to every distro.
55. **"Add NSFW option, off by default, warning, slider with 4 modes (minimal/moderate/fan service/full), no hardcoded art — use an API or ask me"** — **DONE** (§2.12): consent-gated toggle + 4-level slider + runtime fetching from keyless built-in APIs with a custom-URL/key override; no art is hardcoded for NSFW. **Follow-up:** if the user provides their own site/API (URL format + optional key), set it as the default template and test on-device.


---

## 5. Wave-2 interruption checklist — resolved

The historical "verify before restarting" checklist is fully resolved: nav crash/tab bar (item 3/6), terminal redesign (item 9), `FolioAnimatedBackground` wiring (item 5), `strings.xml` accessibility description, 0.75dp hairlines on cards/nav, unused `FluxBackgroundStart/Mid/End` imports in `GlassScaffold.kt`, `InstallProgressPanel` button stack, HostSetup card button — all done in waves 2/2.5 and independently re-verified (import-resolution sweep, brace/paren scanner over all 165 `.kt`, `bash -n` over all 88 scripts).

---

## 6. Architecture map (read before editing)

- `app/src/main/kotlin/com/ivarna/fluxlinux/MainActivity.kt` — UI root: `enum class Screen`, `enum BottomTab`, `MainScreenContent` tab dispatch. Navigation state is **single-source** (Compose snapshot state); external requests (FGS/deep links) go through `pendingTabRequest`/`pendingScreenRequest` one-shot channels drained in `LaunchedEffect`s. On `BottomTab.TERMINAL` the chrome (top bar + nav) hides and the content takes the full window height.
- `ui/components/` — `Frost.kt` (frost primitives + press-springs incl. `folioSpringAny()`), `GlassScaffold.kt` (root Box + `FolioAnimatedBackground` + Scaffold + floating nav overlay), `GlassBottomNavigation.kt` (3 tabs, 64dp frosted pill, inverted active), `FolioBackground.kt`, `InstallTaskBar.kt` (live install strip under the top bar).
- `ui/theme/Motion.kt` — `FolioMotion` tokens + `staggerIn` entrance helper (used by Settings/Home/Distros + sub-screens).
- `ui/screens/` — `HomeScreen.kt`, `InstallConfigScreen.kt` (desktop toggle picker), `SettingsScreen.kt` (remade hero + grouped rows), `DistroScreen.kt` (rebuilt browse page), `DistroSettingsScreen.kt` (component re-run/subtitle + Appearance section), `DistroAppearanceScreen.kt` (wallpaper/colors/terminal/video editor), `TerminalScreen.kt` (tools sheet + anime splash + customizable bar), `CrashLogScreen.kt`, legacy screens.
- `ui/terminal/` — `TerminalToolSelector.kt` (installed-first selector + `TerminalNewSessionPanel`), `TerminalExtraKeys.kt` (config-driven bar + `ShortcutBarActions`), `TerminalToolsSheet.kt` (QoL tools), `ShortcutBarConfig.kt` (key model + `ShortcutBarStore`), `ShortcutBarEditorSheet.kt` (+ key creator), `TerminalSessionSplash.kt` (colorful animated launch sticker), `ImeController.kt` (`hideIme`).
- `core/install/` — `OnboardingInstallRunner.kt` (desktop-set/extras-aware + `LiveInstall` StateFlow + publishLive), `DesktopInstallSelection.kt` (set-based, `FLUX_DESKTOPS`), `BaseDesktopInstallPlan.kt`, `DistroInstallProfile`.
- `core/theme/AppearanceStore.kt` — per-distro appearance config (wallpapers/accents/terminal/video/panel/icon/dim) + guest staging for proot & chroot; `core/theme/AppearanceLiveApply.kt` — live apply to a running desktop.
- `core/system/CrashLog.kt` — uncaught-exception recorder (`filesDir/crashlog/`) installed from `FluxApp`.
- `core/root/` — `ChrootPaths.kt` (**SSOT** for `/data/local/bliss`, helper **v2.11**), `BusyBoxPaths.kt`, `RootShell.kt` (`captureInChroot`).
- `core/data/DistroRepository.kt` — distro + component ids (`kde_plasma → debian/common/setup/setup_kde_debian.sh`, `kde_customization → setup_customization_kde_debian.sh` for Debian family; XFCE components elsewhere).
- `core/desktop/DesktopLauncher.kt` — desktop session start (`FLUX_DESKTOP` passing; readiness via `X server PID=` / `host X0 socket ready`).
- `assets/scripts/` — `host/setup_termux.sh` (**byte-identical to upstream — do not patch**), per-distro setup scripts (`DESKTOPS` membership gates: `xfce`/`kde`/none), chroot scripts (helper v2.11), `common/setup/bliss_guest_common.sh` (`_flux_xfce_compact_defaults()` + `_flux_write_folio_xfce_panel()` two-panel android layout), `common/setup/bliss_appearance_apply.sh` (guest applier), banner writers (colored 256-color art, `.banner-rev 2`).
- `native/bootstrap/com.nopiece.bliss/`, `scripts/repack_bootstrap_for_appid.sh`, `.github/workflows/build.yml`.

### User-visible "Flux" inventory

**Clean.** Remaining `FluxLinux` occurrences are functional/never-touch only (script markers, upstream file, identifiers). If a new user-visible string is added, follow the monochrome "Folio" brand.

---

## 7. Multi-agent workflow rules (mandatory)

1. Work only in `/emulated/folio` (no git there). Sync changed files to `/tmp/opencode/folio-clone` only to commit/push.
2. **Strict file ownership per agent.** Never edit outside your scope; cross-scope needs are reported in the agent reply, not edited. Keep public signatures stable (callers live in other files).
3. No local gradle/git builds — compile only via GitHub Actions.
4. Run at most **2–4 subagents concurrently** (rate limits were hit at 5+).
5. Monochrome design law: black/white, frosted glass, hairline borders. No neon, no glow, no rainbow.
6. Keep host constraints in §3.

### Ownership map (this phase — completed)

| Agent | Files | Status |
|---|---|---|
| Nav agent | `MainActivity.kt`, `ui/components/GlassBottomNavigation.kt`, `ui/components/GlassScaffold.kt` | done (orchestrator finished/cleaned) |
| Terminal UI agent | `ui/terminal/**`, `ui/screens/TerminalScreen.kt` | done |
| Install components agent | `ui/screens/InstallConfigScreen.kt`, `core/install/**` | done |
| KDE agent | `core/data/DistroRepository.kt`, `core/desktop/**`, kde-named scripts, `ui/screens/DistroSettingsScreen.kt` | done |
| Paths agent | `core/root/**`, `TermuxIntentFactory.kt`, `TermuxHostPaths.kt`, all `/data/local/tmp` scripts | done |
| Wave-3 scripts agent | `assets/scripts/**` (banner/sweep/gating) | done |
| Orchestrator | integration, cross-scope fixes, theme wave, verification, CI | done |

---

## 8. Files/locations summary

| Area | Path |
|---|---|
| Working copy (edit here) | `/emulated/folio` |
| Git clone (commit/push) | `/tmp/opencode/folio-clone` |
| Upstream reference | `/tmp/opencode/fluxlinux-src` |
| CI | `.github/workflows/build.yml` |
| Bootstrap repack | `scripts/repack_bootstrap_for_appid.sh` |
| Repacked bootstrap | `native/bootstrap/com.nopiece.bliss/` |
| Wave-3 report | `/tmp/opencode/wave3-report.md` |

---

## 9. Build & release runbook

1. Push to `HakaI963/folio` `main` → CI builds folio + fluxlinux flavors and runs the "Verify APK" step.
2. Watch: `gh run watch <run-id>` (list: `gh run list --limit 10`). Delete failed runs with `gh run delete <id>`.
3. Download artifact (`gh run download` stalls in this environment):
   `curl -sL -H "Authorization: token $(gh auth token)" https://api.github.com/repos/HakaI963/folio/actions/artifacts/<artifact-id>/zip -o /tmp/folio-apk.zip`
4. Publish prerelease: `gh release create folio-debug-<commit> <apk> --prerelease -n "..."`.
5. Local sync: edit `/emulated/folio` → copy changed files into `/tmp/opencode/folio-clone` → `git add/commit/push`.
6. Shell note: if `git`/`awk` are missing, prefix commands with `export PATH=/usr/local/bin:/usr/bin:/bin`.

---

## 10. Next steps (ordered)

1. ~~Commit + push round 7~~ — **done** (`8c9d7ab`); hotfix 3 — **done** (`63b57ee`).
2. ~~CI compile + "Verify APK"~~ — **green** (runs `39037758339` + `39043883518`).
3. ~~Releases~~ — **done**: [`bliss-debug-8c9d7ab`](https://github.com/HakaI963/folio/releases/tag/bliss-debug-8c9d7ab) then hotfix [`bliss-debug-63b57ee`](https://github.com/HakaI963/folio/releases/tag/bliss-debug-63b57ee) (APK 172,847,722 B).
4. **On-device QA** (cannot be done in this environment):
   - **Alpine (Rooted)**: open the tab repeatedly → exactly **one** sticker + one `bliss@alpine` per shell (no more stacking); existing duplicates self-heal on first open.
   - **Debian/Ubuntu (Rooted)**: the sticker now prints in the bash session too; new chroot installs get zsh.
   - **Small default**: new shells show the small 32-col sticker; Settings → Terminal → Banner art slider to Pixelated/Large/Max and reopen shells → size changes on every distro.
   - **NSFW**: toggle on → warning dialog → confirm → level slider + "Refresh art now" → after a refresh, new shells show the fetched designs; off → normal art returns.
   - Chroot **component installs** (XFCE/customization) on a fresh chroot should now complete (previously E2BIG-failed silently).
   - Previous rounds: host shell, install speed, tools sheet, display size, distros (Rocky/CentOS), crash logs.
5. Optional: wire a user-provided NSFW API as the default template (§4g item 55).

---

## 11. Definition of done for this phase

- Round 1: CI green with "Verify APK" passing — **done** (run `38776662519`); release `folio-debug-33c97e9` published.
- Round 2: CI green + release — **done** (run `38854972100`, release `folio-debug-77e2c0a`).
- Round 3: CI green + release — **done** (run `38887109342`, release `folio-debug-7b4b67a`; two pre-CI fixes: 10-digit splash colors, missing `collectAsState` import + stray string constant).
- Round 4: CI green + release — **done** (run `38904315238`, release `folio-debug-5791429`, first-try green; APK upload needed a curl retry after a network drop).
- Round 5: CI green + release — **done** (run `38936982387`, release `bliss-debug-46bc7d8`), then the host-setup Python hotfixes (`bliss-debug-0557a1a` clean-bootstrap + `bliss-debug-d83208f` extract-version bump).
- Round 6: CI green + release — **done** (run `39024115857`, release [`bliss-debug-1ace0d8`](https://github.com/HakaI963/folio/releases/tag/bliss-debug-1ace0d8), first-try green).
- Round 7: CI green + release — **done** (run `39037758339`, release [`bliss-debug-8c9d7ab`](https://github.com/HakaI963/folio/releases/tag/bliss-debug-8c9d7ab), 6m44s); hotfix 3 (host deploy resilience + diagnostics) — **done** (run `39043883518`, release [`bliss-debug-63b57ee`](https://github.com/HakaI963/folio/releases/tag/bliss-debug-63b57ee)).
- Every item in §4, §4b, §4c, §4d, §4e, §4f, §4g marked DONE — **done** (on-device confirmation is §10.4).
- Branding: **"Bliss"** everywhere user-visible (Kotlin, XML, 90 scripts, prompts, logs) **and in file/asset names** (round 6); only functional identifiers (`FLUX_*`, `flux` user, `folio-banner` functions, `Folio*` class names, `Theme.Folio`, `com.ivarna.fluxlinux`) and the frozen host-contract names (`fluxlinux-host.env` dual-write, `~/.fluxlinux` marker) remain.
- Working tree verified locally by: `bash -n` on all 90 scripts; art equality across the 6 writers (md5-identical payload) + `assets/banner/banner.sh` byte-identical to the canonical POSIX sticker; banner sandbox (once-per-shell, multi-source, opt-out, non-interactive; bash/zsh/dash); rc dedupe idempotency; nested-comment-aware brace/paren scan on all 181 `.kt` + import-resolution sweep; res color-literal check; byte-identical guard for `host/setup_termux.sh`; chunked-staging simulation (byte-exact reassembly, ≤81 KB argv); cross-file signature checks for every refactored API.
