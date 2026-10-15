# Folio — Project Instructions, Status & Next Steps

Updated: 2026-10-15 · Repo: `HakaI963/folio` (branch `main`) · Local working copy: `/emulated/folio` (no git) · Git clone: `/tmp/opencode/folio-clone` · Upstream reference clone: `/tmp/opencode/fluxlinux-src`

## 0. TL;DR

Folio is a rebranded, standalone Android build of the FluxLinux app (app id `com.nopiece.folio`, source package `com.ivarna.fluxlinux`, brand "Folio"). It boots Linux distros on-device via a bundled Termux-derived host bootstrap, proot containers, chroot distros, and termux-x11 GUI sessions.

The host-boot failure (commit `10ef127`, release `folio-debug-10ef127`) is shipped. The **UI/UX + feature overhaul** (§4) has now been **implemented across three waves** (parallel agents + orchestrator, strict file ownership) and is in the working tree, pending the CI compile + "Verify APK" run. See §2.5/§2.6 for the full change summary and §4 for item-by-item status.

Quick facts:

| Item | Value |
|---|---|
| App id / brand | `com.nopiece.folio` / "Folio" |
| Source package | `com.ivarna.fluxlinux` |
| Branch | `main` |
| Latest shipped CI | run `38712100456` (commit `10ef127`) |
| Released APK | [`folio-debug-10ef127`](https://github.com/HakaI963/folio/releases/tag/folio-debug-10ef127) `app-folio-debug.apk` (177,088,881 B) |
| Verify APK proof | package `com.nopiece.folio`, `targetSdkVersion 28`, `assets/bootstrap.tar` 129,792,000 B, libbash/libproot/libloader*.so packaged |
| Signing | debug only (per-run keystore — uninstall before re-install) |
| CI gate | `.github/workflows/build.yml` compiles **main sources only** (`assembleFolioDebug` + bootstrap assembly + Verify APK). Unit tests are not part of the gate. |

---

## 1. What Folio is

Folio installs Linux distributions on Android using:

- **Host bootstrap** — Termux-derived, bundled in assets (`native/bootstrap/com.nopiece.folio`, repacked ~130 MB tar, prefix rewritten to `com.nopiece.folio`). Extracted into the app data dir; scripts then run under it.
- **proot containers** — `usr/var/lib/proot-distro` inside the app prefix. No root required.
- **chroot distros** — created under `/data/local/folio/chroots/<distro>` (moved from `/data/local/tmp` so Folio and the other FluxLinux app coexist — §4 item 11). Require root.
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
  - Prefix rewrite to `com.nopiece.folio` happens **before** `setup_termux.sh` runs.
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
- **Paths** — **NEW SSOT** `core/root/ChrootPaths.kt`: `/data/local/folio/{chroots/<distro>, helpers, staging, busybox}`; helper `fluxlinux_chroot.sh` v2.10 (`CHROOT_HELPER_VERSION = "fluxlinux-chroot v2.10"`); 25 scripts + 6 Kotlin files migrated off `/data/local/tmp`; `TermuxIntentFactory` uses our own prefix tmp. Old `/data/local/tmp/chroot*` trees are untouched (never read/modified/deleted); `GuestStorageCatalog` denylist still protects them.
- **KDE backend** — real breaks found and fixed (see §4 item 2).

### 2.6 Wave 3 — scripts sweep + banner + desktop gating (working tree)

- **Banner** — the terminal banner was a remote **colorful** fastfetch preset + the `agnosterzak` oh-my-zsh theme. Replaced in **6 writers** (`common/setup/setup_customization_xfce.sh`, `debian/…/setup_customization_debian.sh`, `alpine/…/setup_customization_alpine.sh`, `debian/…/setup_customization_kde_debian.sh`, `chroot/fluxlinux_chroot.sh`, `termux/termux_tweaks.sh`) by:
  - a local monochrome `folio.zsh-theme` (`ZSH_THEME="folio"`, grays 240/250/255, ASCII), and
  - a locally-written `_flux_write_fastfetch_preset()` heredoc (`logo: null`, no colors).
  No remote downloads; existing installs get the theme repaired in place (`fluxlinux_chroot.sh` sed).
- **Brand sweep** — 780 `FluxLinux`→`Folio` replacements in 79 script files. Residuals: 14 lines in the never-touch `host/setup_termux.sh`, 5 functional markers (`FluxLinux proot` bwrap-shim greps).
- **`FLUX_DESKTOP` gating** — all 12 distro-family scripts + 4 chroot setup scripts normalize `xfce|xfce4|kde|none` (unset → xfce) and gate only desktop-specific packages; shared base stack stays. Chroot launchers select `startxfce4`/`startplasma-x11` at generation time.
- **Nav/final integration (orchestrator)** — see §4 items 3–6.

---

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
11. **Chroot distros + staging/mounts move to Folio's own `/data/local/folio`** — **DONE** (§2.5).
12. **More install options for new distros, per method capability (proot vs chroot)** — **DONE** (with item 2; `DesktopChoice` + `InstallExtra` per method).
13. **Workflow: track down → write → compile last** — **DONE**. Three waves of partitioned agents + orchestrator integration; CI compile is the final gate.

---

## 5. Wave-2 interruption checklist — resolved

The historical "verify before restarting" checklist is fully resolved: nav crash/tab bar (item 3/6), terminal redesign (item 9), `FolioAnimatedBackground` wiring (item 5), `strings.xml` accessibility description, 0.75dp hairlines on cards/nav, unused `FluxBackgroundStart/Mid/End` imports in `GlassScaffold.kt`, `InstallProgressPanel` button stack, HostSetup card button — all done in waves 2/2.5 and independently re-verified (import-resolution sweep, brace/paren scanner over all 165 `.kt`, `bash -n` over all 88 scripts).

---

## 6. Architecture map (read before editing)

- `app/src/main/kotlin/com/ivarna/fluxlinux/MainActivity.kt` — UI root: `enum class Screen`, `enum BottomTab`, `MainScreenContent` tab dispatch. Navigation state is **single-source** (Compose snapshot state); external requests (FGS/deep links) go through `pendingTabRequest`/`pendingScreenRequest` one-shot channels drained in `LaunchedEffect`s. On `BottomTab.TERMINAL` the chrome (top bar + nav) hides and the content takes the full window height.
- `ui/components/` — `Frost.kt` (frost primitives), `GlassScaffold.kt` (root Box + `FolioAnimatedBackground` + Scaffold + floating nav overlay), `GlassBottomNavigation.kt` (3 tabs + empty placeholder, 0.75dp hairline), `FolioBackground.kt`.
- `ui/screens/` — `HomeScreen.kt`, `InstallConfigScreen.kt` (desktop picker), `SettingsScreen.kt`, `DistroSettingsScreen.kt` (component re-run/subtitle), `TerminalScreen.kt`, legacy screens.
- `ui/terminal/` — `TerminalExtraKeys.kt` (grid redesign), `ImeController.kt` (`hideIme`).
- `core/install/` — `OnboardingInstallRunner.kt` (desktop/extras-aware), `DesktopInstallSelection.kt`, `BaseDesktopInstallPlan.kt`, `DistroInstallProfile`.
- `core/root/` — `ChrootPaths.kt` (**SSOT** for `/data/local/folio`), `BusyBoxPaths.kt`, `RootShell.kt`.
- `core/data/DistroRepository.kt` — distro + component ids (`kde_plasma → debian/common/setup/setup_kde_debian.sh`, `kde_customization → setup_customization_kde_debian.sh` for Debian family; XFCE components elsewhere).
- `core/desktop/DesktopLauncher.kt` — desktop session start (`FLUX_DESKTOP` passing; readiness via `X server PID=` / `host X0 socket ready`).
- `assets/scripts/` — `host/setup_termux.sh` (**byte-identical to upstream — do not patch**), per-distro setup scripts (desktop-gated), chroot scripts (paths v2.10), banner/theme writers (`folio.zsh-theme` + local fastfetch preset).
- `native/bootstrap/com.nopiece.folio/`, `scripts/repack_bootstrap_for_appid.sh`, `.github/workflows/build.yml`.

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
| Repacked bootstrap | `native/bootstrap/com.nopiece.folio/` |
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

1. **Commit + push this overhaul** (changed files only; exclude gitignored bootstrap/rootfs/jniLibs artifacts).
2. **CI compile + "Verify APK"** — fix any compile error that surfaces (main sourceset only). Confirm: package `com.nopiece.folio`, `targetSdkVersion 28`, `assets/bootstrap.tar` packaged.
3. **Release** `folio-debug-<commit>` with the built `app-folio-debug.apk`; update §0 quick facts.
4. **On-device QA** (cannot be done in this environment):
   - Initialize Host still succeeds (`exit=126` regression check).
   - Tab switching Home ↔ Distros ↔ Settings never crashes; chrome hides on terminal; no content hidden behind the floating nav (scroll to last item on Home/Distros).
   - Install a distro: Terminal-only, XFCE4, and KDE (Debian family) — picker respects availability; env gates skip desktop packages correctly.
   - Launch XFCE and KDE (proot + chroot); Stop reaps Plasma daemons; KDE banner/prompt is monochrome "Folio".
   - Confirm `/data/local/folio` tree exists; old `/data/local/tmp/chroot*` untouched; no user-visible "Flux" strings.

---

## 11. Definition of done for this phase

- CI green with "Verify APK" passing (`com.nopiece.folio`, `targetSdkVersion 28`) — **pending the push in §10.1**.
- Every item in §4 marked DONE — **done** (on-device confirmation is §10.4).
- Wave-3 branding sweep clean — **done** (residuals are functional/never-touch only).
- Working tree verified locally by: `bash -n` on all 88 scripts, import-resolution sweep + brace/paren scanner on all 165 `.kt`, byte-identical diff guard for `host/setup_termux.sh`, signature/call-site checks for every refactored API.
