# Folio — Project Instructions, Status & Next Steps

Updated: 2026-10-15 · Repo: `HakaI963/folio` (branch `main`) · Local working copy: `/emulated/folio` (no git) · Git clone: `/tmp/opencode/folio-clone` · Upstream reference clone: `/tmp/opencode/fluxlinux-src`

## 0. TL;DR

Folio is a rebranded, standalone Android build of the FluxLinux app (app id `com.nopiece.folio`, source package `com.ivarna.fluxlinux`, brand "Folio"). It boots Linux distros on-device via a bundled Termux-derived host bootstrap, proot containers, chroot distros, and termux-x11 GUI sessions (XFCE works, KDE is broken).

The host-boot failure is fixed and shipped (release `folio-debug-10ef127`). We are now in the **UI/UX + feature overhaul phase** driven by user screenshots (see §4). Work is executed by parallel agents with strict file-ownership partitioning (§7), then compiled via GitHub Actions (§9).

Quick facts:

| Item | Value |
|---|---|
| App id / brand | `com.nopiece.folio` / "Folio" |
| Source package | `com.ivarna.fluxlinux` |
| Branch | `main` |
| Latest green CI | run `38712100456` |
| Released APK | [`folio-debug-10ef127`](https://github.com/HakaI963/folio/releases/tag/folio-debug-10ef127) `app-folio-debug.apk` (177,088,881 B) |
| APK sha256 | `abb88c8489e3c6879e44bfb90500abe77aedcb6420e605badf41043b99682425` |
| Verify APK proof | package `com.nopiece.folio`, `targetSdkVersion 28`, `assets/bootstrap.tar` 129,792,000 B, libbash/libproot/libloader*.so packaged |
| Signing | debug only (per-run keystore — uninstall before re-install) |

---

## 1. What Folio is

Folio installs Linux distributions on Android using:

- **Host bootstrap** — Termux-derived, bundled in assets (`native/bootstrap/com.nopiece.folio`, repacked ~130 MB tar, prefix rewritten to `com.nopiece.folio`). Extracted into the app data dir; scripts then run under it.
- **proot containers** — `usr/var/lib/proot-distro` inside the app prefix. No root required.
- **chroot distros** — created under `/data/local/tmp` today (clash with the other FluxLinux app — must move, see §4 item 11). Require root.
- **termux-x11** — GUI sessions; XFCE4 (working) and KDE (declared but non-functional — must be fixed, §4 item 2).

---

## 2. Completed history (already shipped / already built)

### 2.1 Host bootstrap "Initialize Host" fix — commit `10ef127` (on main)

- **Root cause:** `targetSdk >= 29` triggers Android 10+ SELinux `W^X` policy on `app_data_file` — `execve()` of anything under `/data/data/<pkg>/` is denied regardless of file mode. Proof: on-device `exit=126` (EACCES) persisted after `chmod -R 755`.
- **Fixes shipped:**
  - `targetSdk = 28` for all flavors in `app/build.gradle.kts` (+ explanatory comment), `android:extractNativeLibs="true"`.
  - `android:requestLegacyExternalStorage="true"`; `SharedStorageAccess.granted()` accepts legacy external-storage mode.
  - `BootstrapInstaller.EXTRACT_VERSION = 3` → one-time re-extract + repair of previously broken trees; proot containers preserved.
  - Extractor applies **real** tar modes via `android.system.Os.chmod` (`applyTarMode`; `File.setExecutable` is a no-op on some Android 16 trees — see `HostScriptDeployer.chmodPulseLib` precedent) and a repair walk `repairHostExecPermissions` over `usr/bin`, `usr/libexec`, `usr/lib/apt/methods`, `files/home`.
  - Prefix rewrite to `com.nopiece.folio` (`TermuxHostPaths.applyPackageToExtractedPrefix`) happens **before** `setup_termux.sh` runs; shebangs and `usr/etc` incl. `profile.d` are covered.
  - `assets/scripts/host/setup_termux.sh` reverted **byte-identical to upstream** (all hacks removed). Fixes live in extractor + gradle instead.
  - bash/proot/loader/pulse stay pinned to `nativeLibraryDir` (jniLibs) as belt-and-braces.
- Bootstrap repack: `scripts/repack_bootstrap_for_appid.sh` (prefix rename + sed + patchelf RUNPATH rewrite).

### 2.2 Rebrand to Folio

App id, bootstrap prefix, jniLibs, UI strings are being migrated from Flux/FluxLinux to Folio. Remaining user-visible strings are listed in §6.

### 2.3 Wave 1 — recon (10 parallel agents, all areas mapped)

Areas covered: onboarding buttons · nav/tab-switch crash · nav overlap · terminal extra-keys + banner · install pipeline · KDE backend · Flux string inventory · chroot/tmp path inventory · home screen · theme system. Findings are folded into §4–§7.

### 2.4 Wave 2 — completed edits (in working tree, not yet committed)

- **NEW** `app/src/main/kotlin/com/ivarna/fluxlinux/ui/components/FolioBackground.kt` — `@Composable fun FolioAnimatedBackground(modifier: Modifier = Modifier)`: pure-black canvas + 3 very subtle slow-drifting white radial pools (alpha 0.035–0.055, 24/32/40 s), no pointer input, no expensive blur.
- `ui/theme/Color.kt` — added `FrostTopSheen`; softened `FolioFrostBorder` (0.12 / 0.035). All existing token names kept.
- `ui/theme/Theme.kt` — dark scheme `outline = TextMuted` + inverse mappings (monochrome snackbars); light scheme outline tokens.
- `ui/components/Frost.kt` — 0.75dp hairlines, soft top sheen in `FrostContainer` via `drawBehind`, hairline edge on non-primary `FrostPill`, animated hairline on selected `FrostTabPill`, shared `FrostPillShape`. **Public signatures unchanged.**
- `ui/onboarding/OnboardingFlowScreen.kt` — new private `FolioSecondaryButton` (≈1746–1772: frost fill 45 %, hairline border, pill). `StoragePage` (≈1583–1595) now = primary "Grant access"/"Continue" + secondary "Skip for now" (was two identical white pills). "Back" demoted to secondary on Consent (≈546), HostSetup (≈802), DistroPick (≈974), Options (≈1262). `DonePage` (≈1457–1480): "Start Desktop" primary, "Open Terminal"/"Go to Dashboard" stacked secondaries. onClick wiring unchanged. Added `Dp` import.
- `ui/screens/HomeScreen.kt` — `InstalledEnvRow` gains `onLaunchXfce`/`onLaunchKde`; new private `EnvDesktopPill` (frost pill, mono). XFCE pill shown iff no active session on that distro and `StateManager.isComponentInstalled(context, distroId, "xfce4_desktop")`; KDE pill same with `"kde_plasma"`. XFCE launches via `DesktopLauncher.start(...)`; KDE launches via the existing `KdeGpuPickerDialog`. "Grant superuser to FluxLinux first" rebranded to Folio. HomeScreen public callback params unchanged.

---

## 3. Standalone-host constraints (never regress these)

- `targetSdk` stays **28**. `setup_termux.sh` stays byte-identical to upstream. Keep the `Os.chmod`-based mode repair. Keep jniLibs pinning. Keep `preserveProotDistro` on re-extract.
- Any further device failure (`exit=126`, `Permission denied`) → read the on-screen `errorDetail`/`lastPrepareError` card or `adb logcat -s TerminalLauncher:V BootstrapInstaller:V`.

---

## 4. The user request being implemented — parse + status

Legend: DONE / PARTIAL / TODO.

1. **Onboarding "Grant access" / "Skip for now" buttons look ugly** (two identical white pills) — **DONE** (§2.4).
2. **Distro install needs component choice:** Terminal only / Terminal + XFCE4 / Terminal + KDE, with working toggles; **KDE option exists but does not work — find the real backend and fix it.** Today `InstallConfigScreen` has only a theme picker and hardcodes XFCE prose ("Installs Alpine 3.24 minirootfs, XFCE4 (apk)…"), and per-distro setup scripts install XFCE unconditionally — **TODO** (two agents: install-components + KDE-backend).
3. **Tab switch from Home to any other tab crashes** — **TODO.** Routing is enum-based (`enum class Screen`, `enum BottomTab`, `MainScreenContent`; no Navigation library). Root cause is **not yet identified** — find it by reading, do not guess.
4. **Floating bottom nav overlaps screen content (incl. terminal)** — **TODO** (nav agent; content bottom padding; terminal must respect nav insets).
5. **"UI is not good" — remake with better graphics, animated background; black/white is good** — **PARTIAL** (theme + animated background built; needs wiring into `MainActivity`/`GlassScaffold` and screen-level polish).
6. **Bottom nav: remove Terminal tab** (reachable from Home shell button), leave an **empty placeholder slot** — **TODO**.
7. **Home: show XFCE and KDE launch buttons on installed environments** — **DONE** (§2.4).
8. **Terminal "command menu" banner** prints date/time + FLUX ASCII art → restyle to Folio monochrome, print **Folio** — **TODO** (banner lives in terminal startup scripts under `assets/scripts`; wave 3).
9. **Terminal extra-keys bar** (CTRL/ALT/SHFT/ESC/TAB/ENT/BKSP + arrows): reposition organized, redesign simple, fix tofu glyphs (SHFT renders as ▤) — **TODO** (agent aborted, see §5).
10. **Remaining "Flux" names → "Folio"** everywhere user-visible — **PARTIAL** (onboarding + home done; see §6).
11. **Chroot distros + staging/mounts use shared `/data/local/tmp`** → move everything into the Folio-owned `/data/local/folio` (organized subdirs) so Folio and the other FluxLinux app coexist — **TODO** (see §7 paths).
12. **More install options for new distros, per method capability (proot vs chroot)** — **TODO** (with item 2).
13. **Workflow: 20+ agents — track down, then write, then compile last** — **IN PROGRESS** (wave 1 done; wave 2 partial; wave 3 + compile pending).

---

## 5. Wave-2 work interrupted (verify before restarting)

The "Nav crash + tab bar fix" and "Terminal UI redesign" agents were aborted mid-run with no final report. **Check `git diff`/file state for partial edits before relaunching them.** The theme agent follow-ups also still stand: wire `FolioAnimatedBackground` into `GlassScaffold`/root; `strings.xml` `accessibility_service_description` still says FluxLinux; `ActiveDesktopCard`/`CompactDistroCard`/`GlassBottomNavigation`/`MainActivity` still use 1dp borders (could adopt 0.75dp hairlines); unused `FluxBackgroundStart/Mid/End` imports in `GlassScaffold.kt`; `InstallProgressPanel.kt` retry/failure button stack; HostSetup inline "Initialize Host" card button.

---

## 6. Architecture map (read before editing)

- `app/src/main/kotlin/com/ivarna/fluxlinux/MainActivity.kt` — UI root: `enum class Screen` (~56–74), `enum BottomTab`, `MainScreenContent` tab dispatch; `GlassScaffold`/`GlassBottomNavigation` rendered only in the `Screen.HOME` branch (`blurContent = currentTab == BottomTab.HOME`); the TERMINAL tab renders `TerminalScreen(embeddedInBottomNav = true)`.
- `ui/components/` — `Frost.kt` (frost primitives), `GlassScaffold.kt`, `GlassBottomNavigation.kt`, `FolioBackground.kt` (new).
- `ui/screens/` — `HomeScreen.kt`, `InstallConfigScreen.kt`, `SettingsScreen.kt`, `AudioSettingsScreen.kt`, `Legacy*`, `TerminalScreen.kt`.
- `ui/terminal/` — `TerminalExtraKeys.kt` (bar ~224–273: row1 = 7 keys `CTRL ALT SHFT ESC TAB ENT BKSP` @44dp, row2 = scrollable arrows + symbols + Fn toggle, row3 = F1–F12 when Fn on), terminal view/session files.
- `ui/onboarding/OnboardingFlowScreen.kt`.
- `core/terminal/` — `BootstrapInstaller.kt` (EXTRACT_VERSION, `applyTarMode`, `repairHostExecPermissions`), `TerminalLauncher.kt` (`prepareHostBlocking`, `lastPrepareError`), `HostScriptDeployer.kt` (`deployScripts`, `chmodPulseLib`), `TermuxHostPaths.kt` (`applyPackageToExtractedPrefix`, host env, proot loader patch), `HostCommandBuilder.kt`, `TermuxIntentFactory.kt` (**hardcoded `/data/data/com.termux/files/usr/tmp`** — must use our own prefix).
- `core/root/` — `ChrootPaths.kt` + chroot scripts (chroots created under `/data/local/tmp/...`).
- `core/install/` — `OnboardingInstallRunner.kt`, `DistroInstallProfile`.
- `core/data/DistroRepository.kt` — distro + component ids. Debian components include `kde_plasma` → `debian/common/setup/setup_kde_debian.sh` and `kde_customization` → `setup_customization_kde_debian.sh`; used by `debian` and `debian13_chroot`; all other distros use `glibcXfceComponents`/`alpineComponents` (no KDE).
- `core/desktop/DesktopLauncher.kt` — desktop session start (XFCE works; KDE via GPU picker).
- `assets/scripts/` — `host/setup_termux.sh` (do not patch), per-distro setup scripts (install desktop + extras), terminal startup/banner scripts.
- `native/bootstrap/com.nopiece.folio/`, `scripts/repack_bootstrap_for_appid.sh`, `.github/workflows/build.yml` (CI + "Verify APK" step).

### Remaining user-visible "Flux" inventory (for the sweep agents)

- `ui/screens/SettingsScreen.kt:184` "Leftover installs from FluxLinux ≤ v1.8.0"
- `ui/screens/AudioSettingsScreen.kt:162` "Host PulseAudio in the FluxLinux prefix…"
- `ui/screens/Legacy*` screens
- `res/values/strings.xml` — `accessibility_service_description` ("FluxLinux")
- notification channels/names; termux-x11 viewer UI strings
- `assets/scripts/**` — user-visible echo/banner/prompt strings ("FluxLinux:" messages, motd, terminal FLUX ASCII art)

---

## 7. Multi-agent workflow rules (mandatory)

1. Work only in `/emulated/folio` (no git there). Sync changed files to `/tmp/opencode/folio-clone` only to commit/push.
2. **Strict file ownership per agent.** Never edit outside your scope; cross-scope needs are reported in the agent reply, not edited. Keep public signatures stable (callers live in other files).
3. No local gradle/git builds — compile only via GitHub Actions.
4. Run at most **2–4 subagents concurrently** (rate limits were hit at 5+).
5. Monochrome design law: black/white, frosted glass, hairline borders. No neon, no glow, no rainbow.
6. Keep host constraints in §3.

### Ownership map for remaining work

| Agent | Files |
|---|---|
| Nav agent | `MainActivity.kt`, `ui/components/GlassBottomNavigation.kt`, `ui/components/GlassScaffold.kt` (`FolioAnimatedBackground` wiring, tab redesign, crash fix, overlap) |
| Terminal UI agent | `ui/terminal/**`, TerminalScreen file (keep signature; `embeddedInBottomNav` param stays with default) |
| Install components agent | `ui/screens/InstallConfigScreen.kt`, `core/install/**` — **not** `DistroRepository.kt`, **not** kde-named scripts |
| KDE agent | `core/data/DistroRepository.kt`, kde-named scripts under `assets/scripts/`, `core/desktop/**` |
| Paths agent | `core/root/**`, `core/terminal/TermuxIntentFactory.kt`, `TermuxHostPaths.kt`, scripts referencing `/data/local/tmp` or `/data/data/com.termux` |
| Brand/sweep agents | all other `.kt`/`.xml` with user-visible Flux strings + assets/scripts banners — never the install-config or kde scripts above |

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

---

## 9. Build & release runbook

1. Push to `HakaI963/folio` `main` → CI builds folio + fluxlinux flavors and runs the "Verify APK" step.
2. Watch: `gh run watch <run-id>` (list: `gh run list --limit 10`). Delete failed runs with `gh run delete <id>`.
3. Download artifacts (`gh run download` stalls in this environment):
   `curl -sL -H "Authorization: token $(gh auth token)" https://api.github.com/repos/HakaI963/folio/actions/artifacts/<artifact-id>/zip -o /tmp/folio-apk.zip`
4. Publish prerelease: `gh release create folio-debug-<commit> <apk> --prerelease -n "..."`.
5. Local sync: edit `/emulated/folio` → copy changed files into `/tmp/opencode/folio-clone` → `git add/commit/push`.
6. Shell note: if `git`/`awk` are missing, prefix commands with `export PATH=/usr/local/bin:/usr/bin:/bin`.

---

## 10. Next steps (ordered)

1. **Nav agent** — find + fix the Home→tab crash root cause; remove the Terminal tab and add an empty placeholder slot; fix nav/content overlap (bottom content padding, terminal nav insets); wire `FolioAnimatedBackground` at the `MainActivity` root; Flux→Folio in those files.
2. **Terminal UI agent** — redesign the extra-keys bar (organized grid, monochrome frost, no tofu glyphs, preserve exact input dispatch), polish the session header, add `navigationBarsPadding`.
3. **Install components agent** — `InstallConfigScreen`: desktop picker (None / XFCE4 / KDE where supported) + method-aware extras (proot vs chroot); thread selection through `OnboardingInstallRunner`; gate per-distro setup scripts so desktop install is optional; never edit `DistroRepository.kt` or kde scripts.
4. **KDE agent** — trace UI option → `DistroRepository.scriptName` → script exists → deployed by `HostScriptDeployer` → invoked during install → session start (`startplasma-x11`); fix every break (packages, paths, deployment); make KDE install and launch for the Debian family; extend only if cheap.
5. **Paths agent** — move chroots, helper/staging (busybox, resolver, `fluxlinux_chroot.sh`, generated launchers) and any `/data/local/tmp`/Termux-tmp binds into `/data/local/folio/…` (parent created via su, best-effort migrate old `/data/local/tmp/chroot*`); fix `TermuxIntentFactory` to our own prefix tmp.
6. **Wave 3** — terminal banner scripts (FLUX→Folio, monochrome restyle), assets/scripts Flux sweep, `strings.xml`/notifications/termux-x11 brand sweep, `InstallProgressPanel` button stack.
7. **Verification agents** — cross-file consistency (signatures, imports, references, removed-tab leftovers), then CI compile + "Verify APK" step.
8. **On-device QA** — Initialize Host; install a distro with component selection; launch XFCE and KDE; switch tabs; terminal layout; confirm `/data/local/folio` isolation and no user-visible "Flux" left.

---

## 11. Definition of done for this phase

- CI green with "Verify APK" passing (`com.nopiece.folio`, `targetSdkVersion 28`).
- Every item in §4 marked DONE; wave-3 branding sweep clean.
- On device: tab switching never crashes, nav never overlaps content, component selection installs correctly, KDE installs and launches, chroot/staging isolated under `/data/local/folio`, banners read "Folio" in monochrome, host initialize still succeeds.