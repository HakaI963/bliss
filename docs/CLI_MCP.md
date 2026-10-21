# Bliss local automation

Bliss exposes a **loopback-only** control plane on `127.0.0.1:47831`.
It is kept alive by a foreground service, so clones and the API remain reachable
while you are in another app or inside a Linux distro. Nothing is exposed to
the LAN.

## CLI inside a distro

The app installs a small `bliss` helper into the host prefix and into every
installed proot/chroot rootfs. Open a terminal in a distro and run:

```sh
bliss status
bliss clones          # package, label, status, created, last_opened, parts
bliss apps            # installed source apps available for cloning
bliss list            # alias for clones
bliss ps              # currently running clones
bliss install /sdcard/Download/app.apk "My app"
bliss install /emulated/SomeApp.apk "My app"
bliss install /OpenCodeMobile-v1.0.0.apk "My app"
bliss install /root/app.apk "My app"
bliss install https://example.com/app.apk "My app"
bliss launch com.nopiece.hclone.hXXXX
bliss shot /sdcard/Download/state.jpg
bliss logs com.nopiece.hclone.hXXXX 200
bliss ocr
bliss tap 540 1200
bliss swipe 540 1600 540 500 280
bliss text hello
bliss key back
bliss record start 6 720
bliss record stop
bliss stop com.nopiece.hclone.hXXXX
bliss uninstall com.nopiece.hclone.hXXXX
```

For a local APK, the helper **streams the bytes directly to Bliss** over
`/install-upload`. The Android app never has to see the guest path, so
`/emulated/...`, `/OpenCode...`, chroot `/root/...` and any other readable guest
path work. It still falls back to staging through shared storage if raw upload
is unavailable.

The helper uses `curl` or `wget` from the guest. If neither is installed, install
one (`apt install curl`, `apk add curl`, `dnf install curl`, …).

## HTTP API

| Method | Path | Body / query |
|---|---|---|
| GET | `/status` | engine, display and active clone state |
| GET | `/clones` | installed clone registry (created, last_opened, parts) |
| GET | `/apps` | installed source apps |
| GET | `/screenshot` | `width`, `quality` → JPEG |
| GET | `/ocr` | `width` → text + bounding boxes |
| GET | `/logs` | `package`, `lines` |
| POST | `/install` | `path` or `url`, optional `label` |
| POST | `/install-upload` | raw APK bytes; headers `X-Bliss-Filename`, `X-Bliss-Label` |
| POST | `/uninstall` | `package` |
| POST | `/launch` | `package`, optional `width`,`height`,`fps` |
| POST | `/stop` | `package` |
| POST | `/tap` | `x`,`y` |
| POST | `/swipe` | `x1`,`y1`,`x2`,`y2`,`ms` |
| POST | `/text` | `text` |
| POST | `/key` | `key` (name or Android keycode) |
| GET | `/record/status` | recording state |
| POST | `/record/start` | `fps`, `width` |
| POST | `/record/stop` | — |
| POST | `/mcp` | JSON-RPC 2.0 MCP request |

Recordings are written to
`/sdcard/Android/data/com.nopiece.bliss/files/recordings/`.

## MCP

The MCP endpoint is `http://127.0.0.1:47831/mcp` (JSON-RPC over HTTP).
Example OpenCode config:

```json
{
  "mcp": {
    "bliss": {
      "type": "remote",
      "url": "http://127.0.0.1:47831/mcp",
      "enabled": true
    }
  }
}
```

Tools: `bliss_status`, `bliss_clones`, `bliss_apps`, `bliss_install_apk`,
`bliss_uninstall_clone`, `bliss_launch_clone`, `bliss_stop_clone`,
`bliss_screenshot`, `bliss_logs`, `bliss_tap`, `bliss_swipe`, `bliss_text`,
`bliss_key`, `bliss_ocr`, `bliss_record_start`, `bliss_record_stop`,
`bliss_record_status`.

`bliss_screenshot` returns an MCP `image` content block. The rest return text
JSON so agents can reason about results without parsing a custom protocol.
A ready-to-use agent brief lives in `docs/AI_AGENT.md` and in
Tools → Clone → Copy agent brief. Tools has one **Clone** folder containing the
server/API, MCP, CLI, per-distro agent scan, OCR/input and recording. The agent
scan checks every installed proot/chroot distro and gives each one its own MCP
config button.

## Viewer and agent coexistence

`bliss_launch_clone` is idempotent while a clone already owns the display: it
returns `already_running` instead of restarting the app. Opening the viewer
for that clone attaches to the existing display, so an agent's in-app state is
not lost. The viewer's ACTIONS strip logs agent taps, swipes, text, keys and
launches with timestamps. Logs auto-scroll and have copy/clear controls.
The viewer also supports PiP and resizable display/log panes.

## OCR / UI automation

`bliss_ocr` captures the virtual display, runs ML Kit's on-device recognizer and
returns the full text plus per-block and per-line boxes (`x`, `y`, `w`, `h`,
`cx`, `cy`). An agent can OCR, choose a box centre, then call `bliss_tap`.
`bliss_screenshot` and coordinate input work even when the app is not showing
the clone viewer.

## Clone compatibility

Cloned apps are re-signed with the Bliss clone key, so signature checks remain
unsupported. The engine now also rewrites the resource-table package identity
and verifies installed split parts; re-clone an app after updating so it is
built by the current pipeline. For apps such as Alight Motion that resolve
shape strings dynamically, this identity sync is required. If it still fails,
capture `bliss logs <clone-package> 300` before stopping the clone.
