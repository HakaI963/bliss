# Altaris local automation

Altaris exposes a **loopback-only** control plane on `127.0.0.1:47831`.
It starts with the app process, so clones and the API stay reachable while you
are working in the app, a Linux distro, or an agent session. There is no
persistent control-plane notification. Nothing is exposed to the LAN.

## CLI inside a distro

The app installs a small `altaris` helper (legacy `bliss` alias kept) into the host prefix and into every
installed proot/chroot rootfs. Open a terminal in a distro and run:

```sh
altaris status
altaris clones          # package, label, status, created, last_opened, parts
altaris apps            # installed source apps available for cloning
altaris list            # alias for clones
altaris ps              # currently running clones
altaris install /sdcard/Download/app.apk "My app"
altaris install /emulated/SomeApp.apk "My app"
altaris install /OpenCodeMobile-v1.0.0.apk "My app"
altaris install /root/app.apk "My app"
altaris install https://example.com/app.apk "My app"
altaris launch com.nopiece.hclone.hXXXX
altaris shot /sdcard/Download/state.jpg
altaris logs com.nopiece.hclone.hXXXX 200
altaris ocr
altaris tap 540 1200
altaris swipe 540 1600 540 500 280
altaris text hello
altaris key back
altaris record start 6 720
altaris record stop
altaris stop com.nopiece.hclone.hXXXX
altaris uninstall com.nopiece.hclone.hXXXX
```

For a local APK, the helper **streams the bytes directly to Altaris** over
`/install-upload`. The Android app never has to see the guest path, so
`/emulated/...`, `/OpenCode...`, chroot `/root/...` and any other readable guest
path work. When the path is not visible inside the guest, `/install` hands the
path to the shell service, which resolves common `/sdcard`, `/storage/emulated/0`,
`/emulated`, `/data/local/tmp` and proot/root aliases without needing the app's
scoped-storage permission. It still falls back to staging through shared storage
if raw upload is unavailable.

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
| POST | `/install-upload` | raw APK bytes; headers `X-Altaris-Filename`, `X-Altaris-Label` |
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
| GET | `/uiauto/status` | accessibility service state |
| GET | `/uiauto` | `display` (optional), `max` (optional), `package` (optional) → UI node tree |
| POST | `/uiauto/click` | `nodeId` (node path) |
| POST | `/uiauto/text` | `nodeId` (node path), `text` |
| POST | `/uiauto/scroll` | `nodeId` (node path), optional `forward` |
| POST | `/uiauto/tap-text` | `text`, optional `exact`, optional `display` |
| POST | `/uiauto/gesture` | `x1`,`y1`,`x2`,`y2`, optional `ms`, optional `display` |
| POST | `/uiauto/enable` | — (enables the service with root) |
| POST | `/mcp` | JSON-RPC 2.0 MCP request |

Recordings are written to
`/sdcard/Android/data/com.nopiece.bliss/files/recordings/`.

## MCP

The MCP endpoint is `http://127.0.0.1:47831/mcp` (JSON-RPC over HTTP).
Example OpenCode config:

```json
{
  "mcp": {
    "altaris": {
      "type": "remote",
      "url": "http://127.0.0.1:47831/mcp",
      "enabled": true
    }
  }
}
```

Tools: `altaris_status`, `altaris_clones`, `altaris_apps`, `altaris_install_apk`,
`altaris_uninstall_clone`, `altaris_launch_clone`, `altaris_stop_clone`,
`altaris_screenshot`, `altaris_logs`, `altaris_tap`, `altaris_swipe`, `altaris_text`,
`altaris_key`, `altaris_ocr`, `altaris_record_start`, `altaris_record_stop`,
`altaris_record_status`, `altaris_ui_tree`, `altaris_ui_click`, `altaris_ui_type`,
`altaris_ui_scroll`, `altaris_ui_tap_text`, `altaris_ui_enable`.

`altaris_screenshot` returns an MCP `image` content block. The rest return text
JSON so agents can reason about results without parsing a custom protocol.
A ready-to-use agent brief lives in `docs/AI_AGENT.md` and in
Tools → Clone → Copy agent brief. Tools has one **Clone** folder containing the
server/API, MCP, CLI, per-distro agent scan, OCR/input and recording. The agent
scan checks every installed proot/chroot distro and gives each one its own MCP
config button.

## Viewer and agent coexistence

`altaris_launch_clone` is idempotent while a clone already owns the display: it
returns `already_running` instead of restarting the app. Opening the viewer
for that clone attaches to the existing display, so an agent's in-app state is
not lost. The viewer's ACTIONS strip logs agent taps, swipes, text, keys and
launches with timestamps. Logs auto-scroll and have copy/clear controls.
The viewer also supports PiP and resizable display/log panes.

## OCR / UI automation

`altaris_ocr` captures the virtual display, runs ML Kit's on-device recognizer and
returns the full text plus per-block and per-line boxes (`x`, `y`, `w`, `h`,
`cx`, `cy`). An agent can OCR, choose a box centre, then call `altaris_tap`.
`altaris_screenshot` and coordinate input work even when the app is not showing
the clone viewer.

## Accessibility tree (UI automation)

Altaris ships an `AccessibilityService` that runs in its own `:a11y` process.
When it is enabled it exposes the real node tree of the virtual display, so a
clone can be driven by element name instead of by pixel coordinates.

- `POST /uiauto/enable` (or `altaris_ui_enable`) enables the service with root:
  it writes `enabled_accessibility_services` and `accessibility_enabled` for
  the running application id and verifies the result. Without root the service
  has to be enabled from Android settings.
- `GET /uiauto?display=<id>` returns `{"ok":true,"display":id,
  "source":"accessibility","nodes":[...]}`; every node carries `id`, `class`,
  `text`, `desc`, `package`, `bounds`, `winBounds`, `clickable`, `editable`,
  `scrollable`, `focusable`, `enabled`, `visible`, `checked`, `actions`,
  `childCount` and `depth`.
- `nodeId` is the node path inside the tree (`"79:r.0.2"` — display id, then
  the path), not a platform id; it is only valid inside one snapshot, so read
  the tree again after every navigation step.
- Canvas and game UIs expose no nodes; OCR and pixel mode stay the fallback
  for them. The clone viewer shows the same list under **Elements**.

## Clone compatibility

Cloned apps are re-signed with the Altaris clone key, so signature checks remain
unsupported. The engine now also rewrites the resource-table package identity
and verifies installed split parts; re-clone an app after updating so it is
built by the current pipeline. For apps such as Alight Motion that resolve
shape strings dynamically, this identity sync is required. If it still fails,
capture `altaris logs <clone-package> 300` before stopping the clone.
