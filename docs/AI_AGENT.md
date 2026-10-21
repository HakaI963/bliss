# Altaris agent guide

Altaris runs a loopback-only control plane for Android app clones. An agent can
install, launch, observe, drive and stop a clone without the Altaris UI being in
front. Everything below is local to the device (`127.0.0.1`); it is not exposed
to the LAN.

## Connect

MCP endpoint: `http://127.0.0.1:47831/mcp`

OpenCode / compatible client:

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

Protocol: JSON-RPC 2.0 over HTTP. Send `initialize`, then `tools/list`, then
`tools/call` with `{ "name": "...", "arguments": { ... } }`.

The same operations are available in any distro terminal through the `altaris`
helper, and over HTTP at `http://127.0.0.1:47831`.

## Navigating a clone (accessibility tree)

Screenshots plus OCR are the slow path. Altaris 3.20 reads the real UI tree of
the clone's virtual display through an accessibility service, so an element can
be addressed by name instead of by pixel.

- `GET /uiauto?display=<id>` returns the node tree of the display:
  `{"ok":true,"display":75,"source":"accessibility","nodes":[...]}` where each
  node carries `id` (its path in the tree, e.g. `"79:r.0.2"`), `class`, `text`,
  `desc`, `bounds`, `clickable`, `editable`, `scrollable`, `visible`.
- `POST /uiauto/tap-text {"text":"Projects"}` taps the first element whose text
  or content description matches — the shortest way to drive a UI.
- `POST /uiauto/click {"nodeId":"79:r.0.2"}` clicks a node from the last tree.
- `POST /uiauto/text {"nodeId":"...","text":"hello"}` types into an editable node.
- `POST /uiauto/scroll {"nodeId":"...","forward":true}` scrolls a node.
- `POST /uiauto/gesture {"x1":..,"y1":..,"x2":..,"y2":..}` swipes on the display.
- `POST /uiauto/enable` turns the service on with root (no user prompt needed);
  the same is available in the clone viewer under **Elements**.

MCP mirrors these as `bliss_ui_tree`, `bliss_ui_tap_text`, `bliss_ui_click`,
`bliss_ui_type`, `bliss_ui_scroll` and `bliss_ui_enable`.

Node ids are only valid inside one snapshot — read the tree again after every
navigation step. Canvas and game UIs expose no nodes; OCR and pixel taps stay
the fallback for them.

## Mental model

- A **source app** is installed on the phone and can be cloned.
- A **clone** is a separate user-0 package named `com.nopiece.hclone.h...`.
- There is **one virtual display** at a time. `bliss_launch_clone` creates it
  and launches the clone there; coordinates are display pixels, not screen pixels.
- `bliss_screenshot` and `bliss_ocr` work even when no viewer is open.
- `bliss_stop_clone` stops the process and releases the display.
- Opening the same clone again with `bliss_launch_clone` returns
  `already_running`; it does **not** restart the app.

## First calls

```text
bliss_status
bliss_clones          # registry: package, label, status, created, last_opened, parts
bliss_apps            # installed source apps available for cloning
```

Use `bliss_install_apk` with either a shared-storage `path` (e.g.
`/sdcard/Download/app.apk`) or a direct `url`. Guest paths such as
`/emulated/...` and `/root/...` are streamed by the CLI; if the guest cannot
see the path, the shell service resolves it (including `/sdcard`,
`/storage/emulated/0`, `/data/local/tmp` and proot/chroot rootfs aliases).

After install, pick the clone package from `bliss_clones`, then:

```text
bliss_launch_clone(package, width?, height?, fps?)
```

`width`/`height` are virtual-display pixels. Omit them to use the saved or
default profile.

## Perceive and act

```text
bliss_screenshot(width?)     -> MCP image content
bliss_ocr(width?)            -> text + boxes with x,y,w,h,cx,cy
bliss_tap(x, y)
bliss_swipe(x1, y1, x2, y2, ms?)
bliss_text(text)
bliss_key(key)               # back | home | enter | tab | esc | recents | keycode
```

Recommended loop:

1. `bliss_screenshot` or `bliss_ocr`.
2. Choose a target box centre from OCR, or a coordinate from the screenshot.
3. One action (`tap`/`swipe`/`text`/`key`).
4. Screenshot/OCR again to verify.
5. On a crash, call `bliss_logs(package, 300)` before stopping.

The clone viewer's ACTIONS strip mirrors every agent action with a timestamp,
so a person watching the display can see what the agent is doing. The Live
logs panel auto-scrolls and has copy/clear controls.

## Recording and diagnostics

```text
bliss_record_start(fps?, width?)
bliss_record_status
bliss_record_stop
bliss_logs(package, lines?)
```

Recordings are stored at
`/sdcard/Android/data/com.nopiece.bliss/files/recordings/`.

## CLI equivalents

The installed `altaris` helper uses the same API. For local files it streams the
APK bytes to `/install-upload`, so any path the distro can read works, including
`/emulated`, `/OpenCode...` and chroot `/root`.

```sh
altaris status
altaris clones
altaris apps
altaris list
altaris ps
altaris install /sdcard/Download/app.apk "My app"
altaris install /emulated/SomeApp.apk "My app"   # bytes uploaded; no shared path needed
altaris launch com.nopiece.hclone.hXXXX 720 1280 30
altaris shot /sdcard/Download/state.jpg
altaris ocr
altaris tap 540 1200
altaris swipe 540 1600 540 500 280
altaris text hello
altaris key back
altaris logs com.nopiece.hclone.hXXXX 300
altaris record start 6 720
altaris record stop
altaris stop com.nopiece.hclone.hXXXX
altaris uninstall com.nopiece.hclone.hXXXX
```

If `curl`/`wget` is missing in a guest, install one (`apt install curl`,
`apk add curl`, `dnf install curl`, …). Tools → Clone scans every installed
distro for supported agent binaries and gives each distro its own MCP config
button; it also detects host binaries and Android agent apps, and can copy this
brief to the clipboard.
