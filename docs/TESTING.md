# Bliss 3.16.0 test checklist

Install over the previous Bliss build (same `com.nopiece.bliss` package).

## Theme / motion
- [ ] First launch is **Midnight** (near-black, mint accents), even if the old build was in Light mode.
- [ ] Settings → Appearance → **Day** and **Midnight** both render cleanly; switching back is preserved.
- [ ] Bottom dock is a floating rounded bar; Home / Distros / Apps / Tools all use the same short fade-scale motion.
- [ ] Apps `Installed ↔ Preference` pills look like one control, not two old underline tabs.

## Clone viewer
- [ ] Apps → Installed → folder → **View** opens the display.
- [ ] **Live** turns preview off/on; touch is disabled while paused.
- [ ] **Leave** (Home icon) and the system Back gesture hide the viewer; the clone keeps running.
- [ ] Apps shows a “Still running in the background” card with Resume / Stop.
- [ ] Top bar shows **CLONE** while the clone is alive; tapping it returns to Apps.
- [ ] **Stop** ends the clone, releases the display, and removes the running card.
- [ ] No back action opens the clone fullscreen or crashes.

## Alight Motion
- [ ] Clone Alight Motion from **Installed apps** (not a split APK file).
- [ ] Launch it in View; if it still crashes, open Apps → folder → Customise → **View logs** and copy the last package lines.
- The split-name preservation + OBB copy pass is included. A clone cannot keep the original signature; apps that verify their signing certificate will still reject the clone.

## Terminal
- [ ] Terminal is dark and readable in both Midnight and Day.
- [ ] New tabs use the mint/teal palette, not a white background.
- [ ] `bliss help` works in the host shell and in a distro tab.

## Local control / MCP
- [ ] Tools shows **Control server online** and `http://127.0.0.1:47831`.
- [ ] In a distro terminal:
  - `bliss status`
  - `bliss clones`
  - `bliss launch <clone-package>`
  - `bliss shot /sdcard/Download/shot.jpg`
  - `bliss ocr /sdcard/Download/ocr.json`
  - `bliss tap 540 1200`
  - `bliss record start 6 720` then `bliss record stop`
  - `bliss stop <clone-package>`
- [ ] Copy the MCP config from Tools into your agent; `bliss_screenshot` returns an image and `bliss_ocr` returns boxes.
- [ ] Recordings appear under `/sdcard/Android/data/com.nopiece.bliss/files/recordings/`.

Report the exact command + result, and the clone log lines for any app that still crashes.
