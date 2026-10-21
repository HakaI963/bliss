# Bliss 3.17.0 test checklist

Install over the previous Bliss build (same `com.nopiece.bliss` package).

## Theme / motion
- [ ] First launch is **Midnight** (near-black, mint accents), even if the old build was in Light mode.
- [ ] Settings → Appearance → **Day** and **Midnight** both render cleanly; switching back is preserved.
- [ ] Bottom dock is a floating rounded bar; Home / Distros / Apps / Tools all use the same short fade-scale motion.

## Clone management
- [ ] Apps has three sections: **Installed**, **Running**, **Preference**.
- [ ] A clone card shows its full package name, created time, last-opened time and split-part count.
- [ ] A stopped clone shows **View**; a running clone shows **Resume** and **Stop**.
- [ ] No resume card appears above the Apps list after **Leave**; Resume lives only in the app folder and Running tab.
- [ ] Running tab shows every live clone with Resume / Stop / Logs.

## Viewer / agent coexistence
- [ ] View opens the display; switch to Tools or another bottom tab and come back — the same viewer is still open.
- [ ] Start a clone from an agent (`bliss launch ...`), then tap **View** — it must show the existing display, not relaunch the app.
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
- [ ] Tools is now a folder list: Control server, AI agents, Clone CLI, MCP tools, OCR & input, Recording.
- [ ] AI agents → **Detect agents** finds OpenCode / Pi / Claude / Codex / Hermes / Alpha or similar installed apps and host binaries.
- [ ] **Copy agent brief** explains the MCP protocol, command set and coordinate workflow.
- [ ] In a distro terminal:
  - `bliss status`
  - `bliss clones`, `bliss apps`, `bliss list`, `bliss ps`
  - `bliss install /emulated/OpenCodeMobile-v1.0.0.apk` (guest paths are staged through shared storage)
  - `bliss install /sdcard/Download/app.apk "Label"`
  - `bliss launch <clone-package>`
  - `bliss shot /sdcard/Download/shot.jpg`
  - `bliss ocr`
  - `bliss tap 540 1200`
  - `bliss record start 6 720` then `bliss record stop`
  - `bliss stop <clone-package>`
- [ ] MCP `bliss_apps` lists source apps; `bliss_clones` includes created / last-opened / parts.
- [ ] `bliss_screenshot` returns an image and `bliss_ocr` returns boxes.
- [ ] Recordings appear under `/sdcard/Android/data/com.nopiece.bliss/files/recordings/`.

Report the exact command + result, and the clone log lines for any app that still crashes.
