# Altaris 3.19.0 test checklist

Install over the previous Altaris build (same `com.nopiece.bliss` package).

## Theme / motion
- [ ] First launch is **Midnight** (near-black, mint accents), even if the old build was in Light mode.
- [ ] Settings → Appearance → **Day** and **Midnight** both render cleanly; switching back is preserved.
- [ ] Bottom dock is a floating rounded bar; Home / Distros / Apps / Tools all use the same short fade-scale motion.

## Install speed / progress
- [ ] Install a small distro with **Terminal base** selected. It must not install Mesa, icon themes, VNC or XFCE packages; Alpine terminal base should finish in well under a minute on a warm network.
- [ ] The install bar must change to **Installed ✓** and auto-hide. A 100% bar must not remain stuck for minutes.
- [ ] Install a desktop distro separately; XFCE packages are only downloaded for that selection.
- [ ] During a terminal-base install, `apk` / `apt` output should show only shell and user tooling (no X11/Mesa packages).

## Clone management
- [ ] Apps has three sections: **Installed**, **Running**, **Preference**.
- [ ] A clone card shows its full package name, created time, last-opened time and split-part count.
- [ ] A stopped clone shows **View**; a running clone shows **Resume** and **Stop**.
- [ ] No resume card appears above the Apps list after **Leave**; Resume lives only in the app folder and Running tab.
- [ ] Running tab shows every live clone with Resume / Stop / Logs.

## Viewer / agent coexistence
- [ ] View opens the display; switch to Tools or another bottom tab and come back — the same viewer is still open.
- [ ] Start a clone from an agent (`altaris launch ...`), then tap **View** — it must show the existing display, not relaunch the app.
- [ ] Back or **Leave** hides the viewer and keeps the clone/display alive.
- [ ] **Stop** ends the clone and releases the display.
- [ ] **PiP** puts the viewer into a floating window (device/ROM support permitting).
- [ ] **Layout** adjusts display height, display width and log height; the thin divider can also be dragged.
- [ ] The ACTIONS strip shows user and agent taps, swipes, text, keys and launches as they happen.
- [ ] Live logs auto-scroll to the newest line; **copy** and **clear** buttons work.

## Alight Motion
- [ ] Re-clone Alight Motion after installing 3.17.0 (old clones were built by the previous engine).
- [ ] Clone it from **Installed apps**, not from a split APK file.
- [ ] If it still crashes, copy the last clone log lines. The 3.17.0 engine now syncs the resource-table package identity and verifies installed split parts; signature-verification failures remain unfixable.

## CLI / MCP
- [ ] Tools has **one Clone folder** containing server/API, MCP, CLI, agent scan, OCR/input and recording.
- [ ] **Scan installed distros** shows a per-distro matrix: each installed proot/chroot distro with the agents found inside it and its own **MCP** button.
- [ ] Host binaries and Android agent apps appear under Host / Android agents.
- [ ] **Copy agent brief** explains the MCP protocol, command set and coordinate workflow.
- [ ] In a distro terminal:
  - `altaris status`
  - `altaris clones`, `altaris apps`, `altaris list`, `altaris ps`
  - `altaris install /emulated/OpenCodeMobile-v1.0.0.apk` (APK bytes stream directly; no shared path needed)
  - `altaris install /OpenCodeMobile-v1.0.0.apk` (same)
  - `altaris install /sdcard/Download/app.apk "Label"`
  - `altaris launch <clone-package>`
  - `altaris shot /sdcard/Download/shot.jpg`
  - `altaris ocr`
  - `altaris tap 540 1200`
  - `altaris record start 6 720` then `altaris record stop`
  - `altaris stop <clone-package>`
- [ ] MCP `altaris_apps` lists source apps; `altaris_clones` includes created / last-opened / parts.
- [ ] `altaris_screenshot` returns an image and `altaris_ocr` returns boxes.
- [ ] Recordings appear under `/sdcard/Android/data/com.nopiece.bliss/files/recordings/`.
- [ ] Start a recording, wait at least 2 seconds, stop it: the MP4 must be playable, larger than a header-only 585-byte file, and `altaris record status` must report a non-zero frame count while recording.
- [ ] Start then stop immediately: the MP4 should still contain one frame and play (no empty/corrupt file).

Report the exact command + result, and the clone log lines for any app that still crashes.

## 3.19 additions
- [ ] Open a Parrot zsh terminal: the prompt is `altaris@<host>`, suggestions appear, and a valid command is green while a bogus command is red.
- [ ] Open a Debian/Alpine bash terminal: ble.sh supplies the same green/red syntax coloring and inline suggestions.
- [ ] In any proot terminal run `altaris status` and `bliss status`; both resolve the loopback server.
- [ ] Confirm the notification shade has no persistent Altaris/clone-control notification.
- [ ] Tools → Clone scans only host/guest Linux agent binaries; Android apps are absent.
- [ ] Tools → Clone has no MP4 capture card.
